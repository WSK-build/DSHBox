package com.dshbox.remote.proxy

import java.net.URI
import java.util.Locale

/**
 * 出站/入站改写矩阵：代理视角下的请求头改写（出站）与响应头改写（入站）。
 *
 * 出站：`Host` → 上游 host:port；`Origin`/`Referer` → 上游 origin（Referer 保留路径）。
 * 入站：`Location` 绝对地址指向上游 origin 时重写为回环 origin；
 * `Content-Security-Policy` 剥除 `upgrade-insecure-requests`（回环为 http，
 * 不剥会让页面拒绝加载自身资源）。
 */
class RequestRewriter(
    loopbackOrigin: String,
    upstreamOrigin: String,
) {
    /** 回环 origin 文本（`http://127.0.0.x:port`）。 */
    val loopbackOrigin: String

    /** 上游 origin 文本（`http(s)://host[:port]`）。 */
    val upstreamOrigin: String

    private val loopbackUri: URI
    private val upstreamUri: URI

    init {
        this.loopbackUri = parseOrigin(loopbackOrigin, "loopback origin")
        this.upstreamUri = parseOrigin(upstreamOrigin, "upstream origin")
        this.loopbackOrigin = originText(loopbackUri)
        this.upstreamOrigin = originText(upstreamUri)
    }

    /** 出站 `Host` 头：上游 host:port（端口为协议默认时省略）。 */
    fun outboundHost(): String = hostWithPort(upstreamUri)

    /** 出站 `Origin` 头改写：无 Origin 返回 null（代理据此移除该头）。 */
    fun rewriteOrigin(original: String?): String? = if (original.isNullOrBlank()) null else upstreamOrigin

    /** 出站 `Referer` 头改写：保留路径与查询，origin 换成上游；非绝对地址原样返回。 */
    fun rewriteReferer(original: String?): String? {
        if (original.isNullOrBlank()) return null
        val uri = runCatching { URI(original) }.getOrNull() ?: return original
        if (uri.scheme == null || uri.host == null) return original
        val path = uri.rawPath ?: ""
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        return upstreamOrigin + path + query
    }

    /**
     * 入站 `Location` 头改写：绝对地址指向上游 origin 时把 scheme/host/port
     * 换成回环 origin（路径、查询、fragment 保留）；相对地址与外部地址原样。
     */
    fun rewriteLocation(location: String): String {
        val uri = runCatching { URI(location) }.getOrNull() ?: return location
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return location
        if (scheme != "http" && scheme != "https") return location
        if (uri.host == null) return location
        val originPart = originText(uri)
        if (originPart != upstreamOrigin) return location
        val path = uri.rawPath ?: ""
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""
        return loopbackOrigin + path + query + fragment
    }

    /** 入站 `Content-Security-Policy` 值剥除 `upgrade-insecure-requests` 指令。 */
    fun stripUpgradeInsecure(policyValue: String): String {
        val directives = policyValue.split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.substringBefore(' ').equals("upgrade-insecure-requests", ignoreCase = true) }
        return directives.joinToString("; ")
    }

    private fun originText(uri: URI): String {
        val scheme = uri.scheme!!.lowercase(Locale.ROOT)
        val defaultPort = if (scheme == "https") 443 else 80
        val port = if (uri.port == -1 || uri.port == defaultPort) -1 else uri.port
        return buildString {
            append(scheme).append("://").append(uri.host!!.lowercase(Locale.ROOT))
            if (port != -1) append(':').append(port)
        }
    }

    private fun hostWithPort(uri: URI): String {
        val scheme = uri.scheme!!.lowercase(Locale.ROOT)
        val defaultPort = if (scheme == "https") 443 else 80
        return if (uri.port == -1 || uri.port == defaultPort) {
            uri.host!!.lowercase(Locale.ROOT)
        } else {
            uri.host!!.lowercase(Locale.ROOT) + ":" + uri.port
        }
    }

    private companion object {
        fun parseOrigin(value: String, what: String): URI {
            val uri = runCatching { URI(value.trim()) }.getOrElse {
                throw IllegalArgumentException("$what is not a valid URI: $value", it)
            }
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https" || uri.host.isNullOrBlank()) {
                throw IllegalArgumentException("$what must be an absolute http(s) origin: $value")
            }
            return uri
        }
    }
}
