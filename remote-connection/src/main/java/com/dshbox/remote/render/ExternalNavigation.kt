package com.dshbox.remote.render

import java.net.URI

/**
 * 页内外链导航决策（纯判定，JVM 契约测试锁定）：远程页面点出的绝对地址按
 * 「同一上游站点就地打开 / 其它 http(s) 域交系统浏览器 / 其余忽略」分流。
 */
internal sealed interface ExternalNavDecision {

    /** 同一上游 host 的站内绝对地址：改写为回环地址就地加载。 */
    data class Internal(val targetUrl: String) : ExternalNavDecision

    /** 其它 http(s) 域的真外链：交系统浏览器打开。 */
    data class ExternalBrowser(val url: String) : ExternalNavDecision

    /** 非 http(s)、解析失败或空地址：无操作（引擎已取消该导航）。 */
    data object Ignore : ExternalNavDecision
}

/**
 * 外链导航判定（主框架导航口径）：目标与上游的 host 相同（忽略 scheme 与
 * 端口差异）即视为站内跳转，改写为回环 origin + 路径 + query 就地加载——
 * 路径与 query 的百分号编码原样保留不解码（`%2F`/`%26` 等编码字节改变语
 * 义，解码即破坏目标地址；query 也不剥除，远端页面自带的签名/一次性链接
 * 依赖完整 query），fragment 丢弃（凭据类信息不得跨文档复用）；host 不同
 * 或上游信息缺失（null）一律交系统浏览器；带内嵌凭据（user:pass@host）或
 * 超长地址与本机浏览器外链口径一致拒开；非 http(s) 与解析失败一律忽略。
 */
internal fun resolveExternalNavigation(
    url: String,
    upstreamHost: String?,
    loopbackOrigin: String,
): ExternalNavDecision {
    // 与本机浏览器外链口径对齐的守卫：超长地址拒开（不外放也不就地加载）
    if (url.trim().length > MAX_NAVIGATION_URL_CHARS) return ExternalNavDecision.Ignore
    val target = parseHttpTarget(url) ?: return ExternalNavDecision.Ignore
    // 内嵌凭据（user:pass@host）一律拒开：凭据出现在链接里是钓鱼形态，
    // 就地改写会静默丢弃它，外放浏览器则会带着它出应用
    if (target.hasEmbeddedCredentials) return ExternalNavDecision.Ignore
    if (upstreamHost == null || target.host != upstreamHost) {
        return ExternalNavDecision.ExternalBrowser(url.trim())
    }
    val path = target.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    val query = target.rawQuery?.takeIf { it.isNotEmpty() }
    return ExternalNavDecision.Internal(
        targetUrl = loopbackOrigin.trimEnd('/') + path + (query?.let { "?$it" } ?: ""),
    )
}

/** 上游 origin 的 host 小写文本；null 或解析失败返回 null（一律外放浏览器）。 */
internal fun upstreamOriginHost(upstreamOrigin: String?): String? =
    upstreamOrigin?.let { parseHttpTarget(it)?.host }

/**
 * 强制外放目标的解析（纯函数，JVM 单测锁定）：页面打开请求（宿主消息通道
 * 帧、`dshbox://open-external` 内部动作）产生的地址一律交系统浏览器开新窗
 * 口，绝不就地加载——就地加载会卸载当前远程文档；也不比对其与上游是否同
 * 站（同站/同源目标同样外放）。回环改写仅在代理拓扑（回环 host 与上游
 * host 不同）下进行：门禁 cookie 按回环 host 下发，代理会话页面的回环目标
 * 系统浏览器没有门禁 cookie，先把与回环 host 相同的目标改写为真实上游
 * origin 后交付——path/query/fragment 的百分号编码原样保留不解码。直连拓
 * 扑下 `loopbackOrigin` 即上游 origin，同 host 的目标本就是真实地址（跨
 * scheme/端口与大小写形态也在其列），一律原文外放不做改写；回环 origin
 * 解析失败时无从识别回环目标，目标原文外放。上游 origin 缺失/解析失败时
 * 无从判定代理拓扑：与回环 host 相同的目标对应无门禁 cookie 的回环地址，
 * 系统浏览器只会得到代理 404，丢弃；其余目标原文外放不做改写。跨源
 * http(s) 地址原文外放（去掉首尾空白）；非 http(s)、解析失败、带内嵌凭据
 * （user:pass@host）与超长地址一律丢弃（返回 null，不外放也不就地加载）。
 */
internal fun resolveForcedExternalTarget(
    url: String,
    loopbackOrigin: String,
    upstreamOrigin: String?,
): String? {
    val trimmed = url.trim()
    if (trimmed.length > MAX_NAVIGATION_URL_CHARS) return null
    val target = parseHttpTarget(trimmed) ?: return null
    // 内嵌凭据（user:pass@host）一律丢弃：凭据出现在链接里是钓鱼形态，
    // 外放浏览器会把它一起带出应用
    if (target.hasEmbeddedCredentials) return null
    val loopbackHost = parseHttpTarget(loopbackOrigin)?.host
    val upstreamHost = upstreamOrigin?.let { parseHttpTarget(it)?.host }
    // 回环目标 + 上游 host 缺失：确处代理拓扑但上游 origin 缺失/解析失败，
    // 无从还原真实地址，丢弃（系统浏览器对无门禁 cookie 的回环地址只会得
    // 到代理 404）
    if (target.host == loopbackHost && upstreamHost == null) return null
    // 代理拓扑判定（纯函数内计算）：仅当回环 host 与上游 host 都解析成功且
    // 不同时才视为代理拓扑——直连会话 loopbackOrigin == upstreamOrigin，同
    // host 的跨 scheme/端口目标是真实地址，一律原文外放不改写
    val isProxiedTopology = loopbackHost != null && upstreamHost != null &&
        loopbackHost != upstreamHost
    if (isProxiedTopology && target.host == loopbackHost) {
        // 回环目标（与回环 origin 同 host，覆盖按 host 下发的门禁 cookie 的
        // 全部端口形态）：还原真实上游 origin 后外放；此处仅余上游改写失败
        // 的兜底路径（正常入参上游 host 已解析成功）
        val upstream = parseUpstreamRewrite(upstreamOrigin.orEmpty()) ?: return null
        val path = target.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val query = target.rawQuery?.takeIf { it.isNotEmpty() }
        val fragment = target.rawFragment?.takeIf { it.isNotEmpty() }
        return upstream.scheme + "://" + upstream.authorityHost() +
            path + (query?.let { "?$it" } ?: "") + (fragment?.let { "#$it" } ?: "")
    }
    return trimmed
}

/**
 * 上游 origin → 外放地址前缀要素：小写 scheme 与"host[:显式端口]"（缺省端
 * 口——http:80/https:443——不出现，与标准 origin 书写一致）。
 */
private data class UpstreamRewrite(
    val scheme: String,
    val host: String,
    val port: Int?,
) {
    fun authorityHost(): String = host + (port?.let { ":$it" } ?: "")
}

private fun parseUpstreamRewrite(origin: String): UpstreamRewrite? {
    val trimmed = origin.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val standardizedPort = uri.port.takeIf { declared ->
        declared >= 0 && !(scheme == "http" && declared == 80) &&
            !(scheme == "https" && declared == 443)
    }
    return UpstreamRewrite(scheme = scheme, host = host, port = standardizedPort)
}

/** http(s) 地址的判定要素（raw 系取值保留原始编码；不百分号解码）。 */
private data class HttpTarget(
    val host: String,
    val rawPath: String?,
    val rawQuery: String?,
    val rawFragment: String?,
    val hasEmbeddedCredentials: Boolean,
)

/** http(s) 地址的要素提取；非 http(s)、无 host、解析失败返回 null。 */
private fun parseHttpTarget(url: String): HttpTarget? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return null
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return HttpTarget(
        host = host,
        rawPath = uri.rawPath,
        rawQuery = uri.rawQuery,
        rawFragment = uri.rawFragment,
        hasEmbeddedCredentials = uri.userInfo != null,
    )
}

/** 导航地址长度上限（与本机浏览器外链口径一致）：超过即拒开。 */
private const val MAX_NAVIGATION_URL_CHARS = 8_192
