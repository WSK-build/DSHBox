package com.dshbox.remote.session

import java.net.URI

/**
 * 渲染器侧站点数据清理的目标计算（纯函数，JVM 单测锁定）。
 *
 * 清理按两个粒度展开，对应浏览器存储模型：
 * - **cookie URL** 粒度：cookie 的枚举与清空都发生在完整 URL 上
 *   （scheme://host[:port]/）——https 站点的 `Secure` cookie 只在
 *   `https://` URL 下被 [android.webkit.CookieManager.getCookie] 枚举到，
 *   按 host 拼 `http://` 前缀的旧口径会白扫（枚举为空、Secure 登录态清不
 *   掉）；cookie 本身仍不区分端口（PF host 层级覆盖该 host 全部端口的会
 *   话），URL 里不带非缺省端口；
 * - **origin** 粒度：站点数据（localStorage/IndexedDB 按 origin 存储）。
 *
 * 目标来源：
 * - 删除设备：该设备 **当前绑定槽位 IP** 为回环 host——cookie URL 恒
 *   `http://<槽位IP>/`（回环代理只以 http 暴露）；各端点的回环 origin 取
 *   `http://<槽位IP>:<端口>`（端口读自端点存档 `slot.port.<deviceId>.<ep>`
 *   ——端口尚未记账（端点从未建立过会话）的端点无 origin 可清，仅清
 *   cookie URL）；直连端点额外加真实上游 origin 与其 cookie URL（scheme
 *   随端点真实地址：https → `https://host/`，http → `http://host/`），再
 *   加**注册域**与同族站点的 origin/cookie URL（见 [registeredDomainOf]）。
 * - 槽位首次分配（新建绑定）：新 IP 曾经属于别的设备，按该 IP 清一次
 *   cookie（此时端口未知，不判 origin）；port 随会话建立记账后再由
 *   设备删除路径按 origin 兜底。
 *
 * 删除清理范围边界（口径）：清到注册域，即把同族站点的登录态一并抹掉
 * （`zcode.z.ai` 的删除连带 `chat.z.ai` 等——同注册域都是同一服务的站点，
 * 删「这个连接」等于退出整个服务）；**第三方身份域不进清理范围**
 * （`google.com`/`accounts.google.com` 等——删连接只是退出本站，谷歌账
 * 号在应用内 WebView 仍被识别，重登无需重输密码）。
 */
internal object SessionCleanupTargets {

    /**
     * 删除设备的清理目标：去重后的 origin 与 cookie URL 清单。[boundIp]
     * 为该设备当前绑定槽位（null = 从未建立过代理会话，只处理直连端点）。
     */
    fun forDeviceRemoval(
        targetUrls: List<String>,
        sessionModes: List<String?>,
        boundIp: String?,
        portFor: (Int) -> Int?,
    ): Pair<List<String>, List<String>> {
        val origins = LinkedHashSet<String>()
        val cookieUrls = LinkedHashSet<String>()
        if (boundIp != null && isLoopbackHost(boundIp)) {
            cookieUrls.add("http://$boundIp/")
        }
        for ((index, targetUrl) in targetUrls.withIndex()) {
            if (sessionModes.getOrNull(index) == DIRECT_MODE) {
                upstreamTargetOf(targetUrl)?.let { (origin, cookieUrl) ->
                    origins.add(origin)
                    cookieUrls.add(cookieUrl)
                }
                continue
            }
            val ip = boundIp ?: continue
            val port = portFor(index) ?: continue
            origins.add("http://$ip:$port")
        }
        // 直连上游的注册域与同族站点：在精确 host 的 origin/cookie URL 之
        // 后追加（去重后同 host 的注册域重合项自然合并）。cookie URL 按
        // 各自真实 scheme 产出（注册域与精确 host 同 scheme——同族站点只
        // 收已知 https 站点）。
        val registeredDomains = LinkedHashSet<String>()
        for ((index, targetUrl) in targetUrls.withIndex()) {
            if (sessionModes.getOrNull(index) != DIRECT_MODE) continue
            registeredDomains.addAll(registeredDomainsFromTarget(targetUrl))
        }
        for (domain in registeredDomains) {
            // 注册域本身的 origin/cookie URL（按 https 口径——同族追加规则
            // 只对 z.ai/bigmodel.cn 族的 https 站点生效）
            origins.add("https://$domain")
            cookieUrls.add("https://$domain/")
            for (sibling in siblingSitesFor(domain)) {
                origins.add("https://$sibling")
                cookieUrls.add("https://$sibling/")
            }
        }
        return origins.toList() to cookieUrls.toList()
    }

    /** 槽位首次分配（新建绑定）的目标：仅清新 IP 的 cookie URL。 */
    fun forSlotFirstBind(loopbackIp: String): Pair<List<String>, List<String>> =
        emptyList<String>() to listOf("http://$loopbackIp/")

    /**
     * 端点 targetUrl 的（scheme 归一）origin + cookie URL；非 http(s) 或无
     * host 的地址视为无上游（返回 null，不进清理目标）。cookie URL 不带非
     * 缺省端口（cookie 按 host 匹配，端口的显式与否不影响枚举/写回；缺省
     * 形态也便于去重）。
     */
    fun upstreamTargetOf(targetUrl: String?): Pair<String, String>? {
        val raw = targetUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val originPort = uri.port.takeIf { it != -1 && it != defaultPort }
        val origin = buildString {
            append(scheme).append("://").append(host)
            if (originPort != null) append(':').append(originPort)
        }
        return origin to "$scheme://$host/"
    }

    /** 回环 host 判定：127.0.0.0/8 字面量。 */
    fun isLoopbackHost(host: String): Boolean {
        val bare = host.trim()
        if (bare.startsWith("[")) return false
        val parts = bare.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return octets.all { it in 0..255 } && octets[0] == 127
    }

    /**
     * host 的注册域：取末两标签（`zcode.z.ai` → `z.ai`，`open.bigmodel.cn`
     * → `bigmodel.cn`）——本清单场景下的上游域都是「两标签注册域 + 子域」
     * 形态，末两标签即注册域适用。host 与权威形（带端口/方括号）同口径
     * 归一后判定：先剥 `:port` 后缀（与 [cookieDomainCandidates] 一致）、
     * 整段方括号 IPv6 无注册域；IP（v4 点分）、单标签与畸形 host 无注册
     * 域（返回 null）；大小写归一后产出（`ZCODE.Z.AI` → `z.ai`）。
     *
     * 已知限制：公开后缀日历不在此实现（`co.uk` 等多标签后缀会给出错误
     * 注册域）——本插件的直连上游域集合不受此影响，记录为已知限制。
     */
    internal fun registeredDomainOf(host: String?): String? {
        // 与 cookieDomainCandidates 同口径：host:port 只保留 host 段；
        // 方括号（IPv6 字面量或畸形 authority 形态）无注册域
        var bare = host?.trim()?.lowercase()?.takeUnless { it.isEmpty() } ?: return null
        bare = bare.substringBefore(':').trim()
        if (bare.startsWith("[") || bare.endsWith("]") || bare.isEmpty()) {
            return null // 方括号 IPv6 / 畸形形态
        }
        val labels = bare.split('.')
        if (labels.size < 2) return null // 单标签（intranet 等）
        // 字面 IP（v4 点分，标签全数字）无注册域
        if (labels.all { it.toIntOrNull() != null }) return null
        // 标签不得含空/空段（双点等畸形形态）
        if (labels.any { it.isBlank() }) return null
        return labels.takeLast(2).joinToString(".")
    }

    /**
     * 直连端点 targetUrl 产出的注册域清单（0 或 1 项）：仅 https 且可推出
     * 注册域的地址才产；http 与其它 scheme 不产（注册域精确覆盖 http host
     * 自身的登录态已由 host 项收口，同族追加只面向已知 https 站点族）。
     */
    internal fun registeredDomainsFromTarget(targetUrl: String?): List<String> {
        val raw = targetUrl?.trim().takeUnless { it.isNullOrEmpty() } ?: return emptyList()
        val uri = runCatching { URI(raw) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return emptyList()
        if (scheme != "https") return emptyList()
        return listOfNotNull(registeredDomainOf(uri.host))
    }

    /**
     * 同族站点追加：注册域为 `z.ai` 或 `bigmodel.cn` 时追加该族已知站点
     * （注册域自身也一并列入——精确 host 项之外的注册域级登录态覆盖）；
     * 其它注册域产空清单（第三方身份域 `google.com` 等永不进入清理范围）。
     */
    internal fun siblingSitesFor(registeredDomain: String): List<String> = when (registeredDomain) {
        "z.ai" -> listOf("z.ai", "chat.z.ai", "open.bigmodel.cn", "bigmodel.cn")
        "bigmodel.cn" -> listOf("z.ai", "chat.z.ai", "open.bigmodel.cn", "bigmodel.cn")
        else -> emptyList()
    }

    /**
     * 单个 cookie URL 的 `Domain=` 级过期候选（纯函数，JVM 单测锁定）：
     * 仅**精确 host** 本身（`Domain=<host>`——上游对本站 host 下发的域 cookie
     * 由此过期）；**不产父域候选**（`.parent` 写法会把注册域外的无关站点
     * cookie 一并清掉——注册域与同族站点已在 [forDeviceRemoval] 作为独立
     * 精确目标产出，不需要父域通配）。单标签 host、回环/字面 IP URL
     * （host 为字面 IP）与畸形 URL 没有域归属（Domain 写法只会写废）
     * ——返回空清单。
     */
    internal fun cookieDomainCandidates(cookieUrl: String): List<String> {
        val bare = cookieUrl.substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore(':')
        if (bare.isEmpty()) return emptyList()
        // 字面 IP（v4 与方括号 v6）与单标签 host 无域候选
        if (bare.startsWith("[")) return emptyList()
        val labels = bare.split('.')
        if (labels.size < 2) return emptyList()
        // 回环字面 IP（标签全数字）无域归属：127.0.0.2 → 空清单
        if (labels.all { it.toIntOrNull() != null }) return emptyList()
        return listOf(bare)
    }

    /**
     * 单个 cookie 名的过期置回串清单（纯函数，JVM 单测锁定）：属性按前缀
     * 与域归属正确产出（缺属性会写废——`Secure` cookie 用非 Secure 键过期
     * 不命中、`__Host-` 带 Domain 直接被拒，Secure/`__Host-` 登录态就清不
     * 掉）。变体枚举口径：
     * - `__Host-` 前缀：`Secure; Path=/`（此类 cookie 的强制属性，也不能
     *   带 Domain）+ host-only 分区变体；
     * - `__Secure-` 前缀：`Secure; Path=/` + 可写域时的 `Secure` 两种 Domain
     *   变体（`Domain=<host>` 与 `Domain=.<host>`——域 cookie 两种存储键都
     *   覆到）+ host-only 分区变体；IP 与单标签无可写域，仅根 Path 变体与
     *   分区变体；
     * - 其它名：带/不带 Secure 各一套——每套根 Path 变体加可写域的两种
     *   Domain 变体，再加 host-only 分区变体（带 Domain 的分区 cookie 不
     *   合法，分区变体只有 host-only 一种）。
     *
     * 分区（Partitioned / CHIPS）变体必须 `Secure; SameSite=None; Partitioned`
     * 且 host-only：分区 cookie 的过期写入若缺属性会落在另一个键上，原
     * cookie 覆盖不掉（实证：`chat.z.ai` 的分区 JWT token 残留）。所有名字
     * 都追加同一条 host-only 分区串（分区 cookie 亦可为普通名存在）。
     * 写入 URL 的 scheme 口径在实现层（[com.dshbox.remote.render.WebViewSessionDataCleaner]
     * 按 cookie URL 原样写回——https 站点的过期串必须经 `https://<host>/`
     * 写入，Secure 键才被命中）。
     */
    internal fun cookieExpireValuesFor(name: String, cookieUrl: String): List<String> {
        val base = "$name=; Max-Age=0"
        val partitioned = "$base; Path=/; Secure; SameSite=None; Partitioned"
        if (name.startsWith(HOST_PREFIX)) {
            return listOf(
                "$base; Path=/; Secure",
                partitioned,
            )
        }
        val domains = cookieDomainCandidates(cookieUrl)
        if (name.startsWith(SECURE_PREFIX)) {
            return buildList {
                add("$base; Path=/; Secure")
                for (domain in domains) {
                    add("$base; Path=/; Secure; Domain=$domain")
                    add("$base; Path=/; Secure; Domain=.$domain")
                }
                add(partitioned)
            }
        }
        return buildList {
            add("$base; Path=/")
            add("$base; Path=/; Secure")
            for (domain in domains) {
                add("$base; Path=/; Secure; Domain=$domain")
                add("$base; Path=/; Secure; Domain=.$domain")
                add("$base; Path=/; Domain=$domain")
                add("$base; Path=/; Domain=.$domain")
            }
            add(partitioned)
        }
    }

    private const val DIRECT_MODE = "DIRECT"
    private const val HOST_PREFIX = "__Host-"
    private const val SECURE_PREFIX = "__Secure-"
}
