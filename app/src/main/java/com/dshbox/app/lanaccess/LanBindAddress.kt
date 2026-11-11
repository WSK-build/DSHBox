package com.dshbox.app.lanaccess

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * 局域网监听地址的选取（纯函数 + 枚举壳，JVM 单测锁定选择口径）。
 *
 * 硬边界：只接受 **Wi-Fi 侧**（`wlan*` / `wlp*` 网卡名）的**私网 IPv4** 非
 * 回环地址；回环（127/8）、蜂窝（rmnet/ccmni 等非 wlan 命名）、IPv6、公网
 * 地址、down 的接口一律不选。一项都拿不到返回 null——调用方显式报错并
 * 拒绝开启（不回退 0.0.0.0、不回退蜂窝、不回退 IPv6）。
 *
 * 选择顺序（同 key 排序从前拿）：按地址字节自然序。多网卡（罕见）时给
 * 出可复现的选择。
 */
object LanBindAddress {

    /** 待绑定地址的判定：Wi-Fi 网卡名 + 私网 IPv4 + 可用。 */
    internal fun isBindableWlanIp4(name: String, address: InetAddress, up: Boolean): Boolean {
        if (!up) return false
        if (!isWifiLikeName(name)) return false
        if (address.isLoopbackAddress) return false
        val quad = address.address ?: return false
        if (quad.size != 4) return false
        return isPrivateIpv4(quad)
    }

    /**
     * Wi-Fi 类网卡名判定：Android 常见为 `wlan0/wlan1`（aosp 口径），
     * 部分 OEM 用 `swlan0/wl0.1`；Intel 风格 `wlp*s0` 也纳入。以 wlan/wlp
     * 前缀 + 名内含「wlan」为宽口径（防 OEM 自命名漂移），前缀外的
     * rmnet/ccmni/eth0/lo/sp/ 同为蜂窝或其它传输而不合格。
     */
    internal fun isWifiLikeName(name: String): Boolean {
        val lower = name.lowercase()
        if (!lower.startsWith("wlan") && !lower.startsWith("wlp") && !lower.startsWith("swlan")) {
            return false
        }
        return true
    }

    /** RFC 1918 私网三段判定（Octet 数组已保证长度 4）。 */
    internal fun isPrivateIpv4(octets: ByteArray): Boolean {
        val first = octets[0].toInt() and 0xFF
        val second = octets[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    /**
     * 从指定接口列表里选一枚可绑定的私网 Wi-Fi IPv4；拿不到返回 null。
     * [interfaces] 由调用方枚举（测试注入任意集合），生产路径用全量表。
     */
    fun pick(interfaces: List<NetworkInterface>): InetAddress? {
        val candidates = interfaces.asSequence()
            .filter { it.isUp }
            .flatMap { nic ->
                nic.interfaceAddresses.asSequence().mapNotNull { addr ->
                    val address = addr.address ?: return@mapNotNull null
                    if (isBindableWlanIp4(nic.name, address, nic.isUp)) address else null
                }
            }
            .map { it.hostAddress ?: "" }
            .sorted()
            .map { InetAddress.getByName(it) }
            .toList()
        return candidates.firstOrNull()
    }

    /** 生产入口：枚举本机所有网卡后选一枚绑定地址。 */
    fun pickCurrent(): InetAddress? = runCatching {
        pick(Network.getNetworkInterfaces().toList())
    }.getOrNull()

    private object Network {
        fun getNetworkInterfaces(): java.util.Enumeration<NetworkInterface> =
            NetworkInterface.getNetworkInterfaces()
    }
}
