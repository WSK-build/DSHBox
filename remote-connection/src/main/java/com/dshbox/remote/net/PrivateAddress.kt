package com.dshbox.remote.net

/**
 * 私有地址判定（纯函数，JVM 单测锁定）：给定上游端点 host，判断它是否落
 * 在 RFC 1918/4193/4291 定义的私有或链路本地区段——这类地址只在本地网络
 * （含 VPN 虚拟内网）可达，公网路由不可达。
 *
 * 口径：
 * - IPv4：`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`；segment 十进制
 *   严格无前导零（`010.0.0.1` 非法而非 10.x——前导零与某些解析器的八进
 *   制/ legacy 口径有歧义，按拒绝处理）；
 * - IPv6：`fc00::/7`（ULA）与 `fe80::/10`（链路本地）；
 * - IPv4-mapped IPv6（`::ffff:a.b.c.d`，RFC 4291 §2.5.5.2）：解包为末尾
 *   32 位按 IPv4 私网口径判定（`::ffff:192.168.1.1` 真私网、
 *   `::ffff:8.8.8.8` 公网）；
 * - 排除回环：`127.0.0.0/8` 与 `::1`（回环地址不经物理网络，无 VPN 提示
 *   意义；IPv4-mapped 回环 `::ffff:127.x` 同排除）；
 * - 排除公网地址与其余非私网段（100.64.0.0/10 CGNAT 之类不判定）；
 * - 仅接受 IP 字面量：主机名（域名）一律 false——本判定用于判断端点是
 *   否为私有地址，供面板 VPN 提示判定；域名解析本身可能受 VPN 影响，
 *   不参与该判定。
 */
object PrivateAddress {

    /** host 是否为私网/链路本地 IP 字面量（回环除外）。 */
    fun isPrivateAddressHost(host: String): Boolean {
        val raw = host.trim()
        if (raw.isEmpty()) return false
        // host 形态允许带端口或方括号（端点 targetUrl 的 authority 段）：
        // `[::1]:8080`、`192.168.1.5:3080` 都先剥出纯地址再判定
        val bare = stripAuthorityDecorations(raw)
        if (bare.isEmpty() || bare != bare.trim()) return false
        if (':' in bare) return isPrivateIpv6(bare)
        return isPrivateIpv4(bare)
    }

    /** 剥离方括号与端口后缀：`[a:b]`、`[a:b]:80`、`a.b:80` → 纯地址。 */
    private fun stripAuthorityDecorations(host: String): String {
        if (host.startsWith("[")) {
            val close = host.indexOf(']')
            if (close < 0) return host
            val rest = host.substring(close + 1)
            if (rest.isNotEmpty() && rest[0] != ':') return host
            return host.substring(1, close)
        }
        // IPv4 带端口：只允许一个冒号；多个冒号按 IPv6 文本原样返回（由
        // IPv6 解析自身判定合法性）
        val colon = host.indexOf(':')
        if (colon >= 0 && host.indexOf(':', colon + 1) >= 0) return host
        if (colon >= 0) return host.substring(0, colon)
        return host
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = ipv4Octets(host) ?: return false
        val first = octets[0]
        val second = octets[1]
        // 回环 127/8 排除；其余私网三段逐一判定
        if (first == 127) return false
        if (first == 10) return true
        if (first == 172 && second in 16..31) return true
        if (first == 192 && second == 168) return true
        return false
    }

    /**
     * IPv4 点分十进制串解析为 4 个 0..255 八位组；非法形态（非四段、非数
     * 字、越界、**前导零**——`010` 这类与八进制/ legacy 解析口径有歧义的
     * 串）返回 null。
     */
    private fun ipv4Octets(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for ((index, part) in parts.withIndex()) {
            // 前导零拒绝：非单「0」串不得以 0 开头（`010`、`00`…
            // RFC 3986 §2.4 对 IPv4 十进制也要求无前导零）
            if (part.isEmpty() || (part.length > 1 && part[0] == '0')) return null
            val value = part.toUIntOrNull() ?: return null
            if (value > 255u) return null
            octets[index] = value.toInt()
        }
        return octets
    }

    private fun isPrivateIpv6(host: String): Boolean {
        val address = parseIpv6(host) ?: return false
        // IPv4-mapped IPv6（::ffff:0:0/96）：末尾 32 位解包按 IPv4 口径判定
        // （含真私网与回环排除、公网不判定）；16 位组以 Short 寄存，按无符
        // 号口径先掩 0..0xFFFF 再比
        if (((address[0].toInt() and 0xFFFF) == 0) && ((address[1].toInt() and 0xFFFF) == 0) &&
            ((address[2].toInt() and 0xFFFF) == 0) && ((address[3].toInt() and 0xFFFF) == 0) &&
            ((address[4].toInt() and 0xFFFF) == 0) && ((address[5].toInt() and 0xFFFF) == 0xFFFF)
        ) {
            return isPrivateIpv4Mapped(address[6], address[7])
        }
        // 16 位组按无符号口径比较：Short 寄存为负值，先掩回 0..0xFFFF
        val first = address[0].toInt() and 0xFFFF
        // fe80::/10（链路本地）：首 10 位 1111 1110 10，即高 16 位 0xfe80..0xfebf
        if (first in FE80_LOW..FEBF_HIGH) return true
        // fc00::/7（ULA）：首 7 位 1111 110，即高 16 位 0xfc00..0xfdff
        if (first in FC00_LOW..FDFF_HIGH) return true
        return false
    }

    /** IPv4-mapped 尾 32 位（两个 16 位组）按 IPv4 私网/回环口径判定。 */
    private fun isPrivateIpv4Mapped(high: Short, low: Short): Boolean {
        val asInt4 = buildString {
            append((((high.toInt() and 0xFFFF) ushr 8))).append('.')
            append(((high.toInt() and 0xFFFF) and 0xFF)).append('.')
            append((((low.toInt() and 0xFFFF) ushr 8))).append('.')
            append(((low.toInt() and 0xFFFF) and 0xFF))
        }
        return isPrivateIpv4(asInt4)
    }

    /**
     * IPv6 字面量解析为 8 段 16 位组（RFC 4291 文本形式，含 `::` 压缩与
     * 结尾 IPv4 穿嵌）。zone id（`%` 后缀）不接受；解析失败（非法字符、
     * 段数溢出、压缩符不唯一等）返回 null。
     */
    private fun parseIpv6(host: String): ShortArray? {
        if (host.isEmpty() || host.contains("%")) return null
        if (host.countSubstring("::") > 1) return null
        val compressed = host == "::" || host.contains("::")
        val before: String
        val after: String
        if (compressed) {
            val split = host.split("::")
            before = split[0]
            after = split.getOrElse(1) { "" }
        } else {
            before = host
            after = ""
        }
        val beforeGroups = ipv6Groups(before) ?: return null
        val afterGroups = if (compressed) ipv6Groups(after) ?: return null else emptyList()
        val total = beforeGroups.size + afterGroups.size
        if (!compressed && total != 8) return null
        if (compressed && beforeGroups.isEmpty() && afterGroups.isEmpty()) {
            // `::`（未指定地址）：不算私有
            return ShortArray(8)
        }
        if (total > 8) return null
        val filler = 8 - total
        if (compressed && filler < 1) return null
        val result = ShortArray(8)
        var index = 0
        for (group in beforeGroups) {
            result[index++] = group.toShort()
        }
        if (compressed) {
            repeat(filler) { result[index++] = 0 }
        }
        for (group in afterGroups) {
            result[index++] = group.toShort()
        }
        return result
    }

    /** 解析冒号分隔的段组（`x:x:…`，允许结尾段穿嵌 IPv4，拆成两个 16 位组）。 */
    private fun ipv6Groups(segment: String): List<Int>? {
        if (segment.isEmpty()) return emptyList()
        val parts = segment.split(':')
        if (parts.count { it.isEmpty() } > 1) return null
        val groups = mutableListOf<Int>()
        for ((index, part) in parts.withIndex()) {
            if (part.isEmpty()) {
                // 压缩边界贴着分隔符（如 `::1` 的首段、`fc00::` 的末段）：
                // 单个空段合法，两个空段已在上方拦截
                if (index == 0 || index == parts.lastIndex) continue
                return null
            }
            if (part.contains('.')) {
                // IPv4 穿嵌只允许在地址最后的段组结尾
                if (index != parts.lastIndex) return null
                groups.addAll(ipv4IntoIpv6Tail(part) ?: return null)
                continue
            }
            val value = part.toUIntOrNull(16) ?: return null
            if (part.length > 4 || value > 0xFFFFu) return null
            groups.add(value.toInt())
        }
        return groups
    }

    /** 结尾 IPv4 穿嵌（RFC 4291）：`192.168.1.1` → 两个 16 位组。前导零段拒绝。 */
    private fun ipv4IntoIpv6Tail(part: String): List<Int>? {
        val octets = ipv4Octets(part) ?: return null
        val a = (octets[0] shl 8) or octets[1]
        val b = (octets[2] shl 8) or octets[3]
        return listOf(a, b)
    }

    private fun String.countSubstring(pattern: String): Int {
        var count = 0
        var index = indexOf(pattern)
        while (index >= 0) {
            count += 1
            index = indexOf(pattern, index + pattern.length)
        }
        return count
    }

    private const val FE80_LOW = 0xfe80
    private const val FEBF_HIGH = 0xfebf
    private const val FC00_LOW = 0xfc00
    private const val FDFF_HIGH = 0xfdff
}
