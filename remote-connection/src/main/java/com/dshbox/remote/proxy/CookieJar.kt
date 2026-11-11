package com.dshbox.remote.proxy

import java.net.URI
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 代理自持的设备级 cookie 罐：上游登录态记账的唯一真相。
 *
 * 按上游 origin 与 Domain 作用域匹配记账，覆盖 name/value/Domain/Path/
 * Expires/HttpOnly 要素；SameSite 语义不实现——代理视角一切请求均为第一方。
 * 会话启动、应用重启与设备切换都不丢登录态；删除设备即整体销毁。
 *
 * Domain 防护边界：带 Domain 属性的声明要求值含点且能覆盖请求主机——不含
 * 点的单标签 TLD（Domain=com 一类）一律拒绝，防任意同后缀主机进入作用域。
 * 已知限制（经确认）：多标签公共后缀（co.uk 一类）不在本期防护范围；连带
 * 边界是单标签主机（如 localhost）显式携带 Domain 的 Set-Cookie 同样被拒。
 *
 * 线程安全：代理为每连接一线程模型，罐的读写以内部锁串行化。
 */
class CookieJar {

    /** 写入回调：代理侧用于触发经凭据存档的持久化（序列化快照落盘）。 */
    var onChangeListener: (() -> Unit)? = null

    private val cookies = LinkedHashMap<CookieKey, StoredCookie>()

    /**
     * 查询对指定上游 origin 有效的 cookie（按路径 `/` 匹配，RFC 6265 序）。
     * 过期条目在此惰性清除。
     */
    fun cookiesFor(upstreamOrigin: String): List<StoredCookie> = cookiesFor(parseUri(upstreamOrigin))

    /** 只读预检：指定上游 origin 是否已持有同名 cookie（重连会话跳过凭据重发）。 */
    fun containsCookie(upstreamOrigin: String, name: String): Boolean =
        cookiesFor(upstreamOrigin).any { it.name == name }

    /** 读取指定上游 URL 生效的同名 cookie 值（路径按 RFC 6265 匹配）；不存在返回 null。 */
    fun valueFor(upstreamUri: URI, name: String): String? =
        cookiesFor(upstreamUri).firstOrNull { it.name == name }?.value

    /** 查询对指定上游 URL 有效的 cookie（按其路径匹配，RFC 6265 序：路径前缀长者优先）。 */
    fun cookiesFor(upstreamUri: URI): List<StoredCookie> {
        val host = upstreamUri.host?.lowercase(Locale.ROOT) ?: return emptyList()
        val path = requestPathOf(upstreamUri)
        val now = System.currentTimeMillis()
        synchronized(cookies) {
            val matched = cookies.values
                .filter { it.expiresAtMillis ?: Long.MAX_VALUE > now }
                .filter { matchesHost(it, host) && matchesPath(it.path, path) }
            // RFC 6265 5.4：路径更长者更具体优先；同长者按创建序。
            return matched.sortedByDescending { it.path.length }
        }
    }

    /** 记账上游的一批 Set-Cookie 头：入库（含过期淘汰），并返回需要双写到
     *  渲染器的 Set-Cookie 头（非 HttpOnly，剥除 Domain 与 Secure 属性）。
     *  HttpOnly cookie 只进罐、不透传；保留名 [GateCheck.COOKIE_NAME]
     *  （本机门禁）一律不入罐、不双写——上游不得种植同名 cookie。 */
    fun storeAll(upstreamOrigin: String, setCookieHeaders: List<String>): List<String> =
        storeAll(parseUri(upstreamOrigin), setCookieHeaders)

    /** 同 [storeAll] 的 URL 重载：cookie 请求路径参与默认路径推导。 */
    fun storeAll(upstreamUri: URI, setCookieHeaders: List<String>): List<String> {
        val host = upstreamUri.host?.lowercase(Locale.ROOT) ?: return emptyList()
        val requestPath = requestPathOf(upstreamUri)
        val dualWrites = ArrayList<String>()
        synchronized(cookies) {
            for (header in setCookieHeaders) {
                val stored = parseSetCookie(header, host, requestPath) ?: continue
                if (stored.name == GateCheck.COOKIE_NAME) continue
                val key = CookieKey(stored.domain, stored.path, stored.name)
                if (stored.expired) {
                    cookies.remove(key)
                } else {
                    cookies[key] = stored
                }
                if (!stored.httpOnly && !stored.expired) {
                    dualWrites.add(dualWriteHeader(stored))
                }
            }
        }
        if (dualWrites.isNotEmpty() || setCookieHeaders.isNotEmpty()) {
            onChangeListener?.invoke()
        }
        return dualWrites
    }

    /** 序列化快照：供凭据存档加密持久化；过期条目不入快照，会话级条目随罐代持持久。 */
    fun serialize(): String {
        val lines = synchronized(cookies) {
            cookies.values
                .filter { !it.expired }
                .joinToString("\n") { it.serializeLine() }
        }
        return "$SERIALIZE_VERSION\n$lines"
    }

    /** 从序列化快照恢复（覆盖式）；保留名 [GateCheck.COOKIE_NAME] 不入罐（防旧快照把门禁令牌注入上游）。 */
    fun load(serialized: String) {
        synchronized(cookies) {
            cookies.clear()
            val lines = serialized.lineSequence().toList()
            if (lines.firstOrNull() != SERIALIZE_VERSION) return
            for (line in lines.drop(1)) {
                if (line.isBlank()) continue
                StoredCookie.deserializeLine(line)?.let {
                    if (it.name != GateCheck.COOKIE_NAME) {
                        cookies[CookieKey(it.domain, it.path, it.name)] = it
                    }
                }
            }
        }
    }

    private fun parseSetCookie(header: String, requestHost: String, requestPath: String): StoredCookie? {
        val parts = header.split(';')
        val pair = parts.firstOrNull()?.trim() ?: return null
        val eq = pair.indexOf('=')
        if (eq <= 0) return null
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        if (name.isEmpty()) return null

        var domain: String = requestHost
        var hostOnly = true
        var path: String? = null
        var expiresAtMillis: Long? = null
        var httpOnly = false
        for (rawAttribute in parts.drop(1)) {
            val attribute = rawAttribute.trim()
            if (attribute.isEmpty()) continue
            val attrEq = attribute.indexOf('=')
            val attrName: String
            val attrValue: String
            if (attrEq >= 0) {
                attrName = attribute.substring(0, attrEq).trim().lowercase(Locale.ROOT)
                attrValue = attribute.substring(attrEq + 1).trim()
            } else {
                attrName = attribute.lowercase(Locale.ROOT)
                attrValue = ""
            }
            when (attrName) {
                "domain" -> {
                    val declared = attrValue.removePrefix(".").lowercase(Locale.ROOT)
                    // 仅当声明的域能覆盖请求主机时接受（否则按 RFC 6265 丢弃该
                    // cookie）；不含点的声明（TLD/单标签）一律拒绝——域匹配会把
                    // 任意同后缀主机放进作用域（Public Suffix 防护）。host-only
                    // 与 IP 主机不受影响；单标签主机自身的显式 Domain 声明亦
                    // 被拒（类注释中的已知边界，行为固化）。
                    if (declared.contains('.') && domainMatches(requestHost, declared)) {
                        domain = declared
                        hostOnly = false
                    } else {
                        return null
                    }
                }
                "path" -> if (attrValue.startsWith("/")) path = attrValue
                "expires" -> expiresAtMillis = parseHttpDate(attrValue)
                "max-age" -> {
                    // RFC 6265：Max-Age 优先于 Expires；非正值即立即过期删除。
                    val seconds = attrValue.toLongOrNull()
                    if (seconds != null) {
                        expiresAtMillis = System.currentTimeMillis() + seconds * 1000
                    }
                }
                "httponly" -> httpOnly = true
                // Secure 与 SameSite 属性入罐记录但代理侧无需消费：回环为 http、全部请求第一方。
            }
        }
        return StoredCookie(
            name = name,
            value = value,
            domain = domain,
            path = path ?: defaultPathOf(requestPath),
            expiresAtMillis = expiresAtMillis,
            httpOnly = httpOnly,
            hostOnly = hostOnly || isIpLiteral(requestHost),
        )
    }

    /** 双写渲染器的 Set-Cookie 头：剥 Domain（变 host-only 落本回环 host）与 Secure（回环为 http）。 */
    private fun dualWriteHeader(cookie: StoredCookie): String = buildString {
        append(cookie.name).append('=').append(cookie.value)
        append("; Path=").append(cookie.path)
        cookie.expiresAtMillis?.let {
            append("; Expires=").append(formatHttpDate(it))
        }
    }

    private fun matchesHost(cookie: StoredCookie, host: String): Boolean =
        if (cookie.hostOnly) {
            cookie.domain == host
        } else {
            domainMatches(host, cookie.domain)
        }

    private val StoredCookie.expired: Boolean
        get() = expiresAtMillis != null && expiresAtMillis <= System.currentTimeMillis()

    private data class CookieKey(val domain: String, val path: String, val name: String)

    private companion object {
        const val SERIALIZE_VERSION = "dshbox-cookie-jar-v1"

        fun parseUri(origin: String): URI =
            runCatching { URI(origin) }.getOrElse {
                throw IllegalArgumentException("Upstream origin is not a valid URI: $origin", it)
            }

        fun requestPathOf(uri: URI): String = uri.rawPath.takeIf { it.isNotEmpty() } ?: "/"

        fun defaultPathOf(requestPath: String): String {
            val pathOnly = requestPath.substringBefore('?')
            if (!pathOnly.startsWith("/")) return "/"
            val lastSlash = pathOnly.lastIndexOf('/')
            return if (lastSlash <= 0) "/" else pathOnly.substring(0, lastSlash)
        }

        /** RFC 6265 5.1.3 域匹配：相等或请求主机以 `.域` 结尾；IP 字面量不做域匹配。 */
        fun domainMatches(host: String, domain: String): Boolean =
            host == domain || (!isIpLiteral(host) && host.endsWith(".$domain"))

        fun isIpLiteral(host: String): Boolean =
            host.contains(':') || IPV4_REGEX.matches(host)

        val IPV4_REGEX = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        /** RFC 6265 5.1.4 路径匹配。 */
        fun matchesPath(cookiePath: String, requestPath: String): Boolean =
            requestPath == cookiePath ||
                (requestPath.startsWith(cookiePath) &&
                    (cookiePath.endsWith("/") || requestPath[cookiePath.length] == '/'))

        fun parseHttpDate(value: String): Long? {
            for (formatter in DATE_FORMATTERS) {
                runCatching {
                    return ZonedDateTime.parse(value, formatter).toInstant().toEpochMilli()
                }
            }
            return null
        }

        fun formatHttpDate(epochMillis: Long): String =
            DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC))

        val DATE_FORMATTERS = listOf(
            DateTimeFormatter.RFC_1123_DATE_TIME,
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", Locale.US),
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US),
        )
    }
}

/**
 * 罐内一条 cookie 记录：覆盖 name/value/Domain/Path/Expires/HttpOnly 五要素，
 * 并记录 hostOnly（无 Domain 属性时绑定精确主机）。
 */
data class StoredCookie(
    val name: String,
    val value: String,
    /** 绑定主机或覆盖域（小写）。 */
    val domain: String,
    val path: String,
    /** 过期时刻（epoch 毫秒）；null 为会话级 cookie。 */
    val expiresAtMillis: Long?,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
) {
    internal fun serializeLine(): String =
        listOf(
            name,
            value,
            domain,
            path,
            expiresAtMillis?.toString() ?: "",
            if (httpOnly) "1" else "0",
            if (hostOnly) "1" else "0",
        ).joinToString("\u001f") { it.escapeField() }

    internal companion object {
        fun deserializeLine(line: String): StoredCookie? {
            val fields = line.split('\u001f').map { it.unescapeField() }
            if (fields.size != 7) return null
            val name = fields[0]
            val value = fields[1]
            val domain = fields[2]
            val path = fields[3]
            val expires = fields[4]
            val httpOnly = fields[5]
            val hostOnly = fields[6]
            if (name.isEmpty() || domain.isEmpty() || path.isEmpty()) return null
            return StoredCookie(
                name = name,
                value = value,
                domain = domain,
                path = path,
                expiresAtMillis = expires.toLongOrNull(),
                httpOnly = httpOnly == "1",
                hostOnly = hostOnly == "1",
            )
        }

        private fun String.escapeField(): String =
            replace("\\", "\u0000").replace("\u001f", "\u0001")

        private fun String.unescapeField(): String =
            replace("\u0001", "\u001f").replace("\u0000", "\\")
    }
}
