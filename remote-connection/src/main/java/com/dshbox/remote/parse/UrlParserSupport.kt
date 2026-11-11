package com.dshbox.remote.parse

import java.net.URI
import java.util.Locale

import com.dshbox.remote.parse.ChannelKind

/**
 * URL 形态解析器的公共支撑：安全的 URI 读取、通道标签判定与「剥离凭据的
 * 存档地址」推导。解析器只在此处触碰原始 URL，凭据剥离规则集中一处。
 */
internal object UrlParserSupport {

    /**
     * 隧道域名后缀（公网入口，地址随会话易变）：通道标签判定的单一来源，
     * 各解析器一律引用此列表（避免后缀清单在解析器间漂移）。
     */
    internal val TUNNEL_HOST_SUFFIXES = listOf(
        ".trycloudflare.com",
        ".ngrok.io",
        ".ngrok-free.app",
        ".ts.net",
    )

    /**
     * 已知一次性票据的 query 键：generic 通道据此判定载荷是否携带凭据——
     * 持久参数（语言/租户等）是地址的一部分，不按凭据消费处理。
     */
    val ONE_TIME_TICKET_KEYS = setOf("ticket", "auth", "invite", "code", "secret", "token")

    /** query 是否携带已知一次性票据键。 */
    fun queryCarriesTicketKey(rawQuery: String?): Boolean =
        rawQuery?.split('&')
            ?.map { it.substringBefore('=').lowercase(Locale.ROOT) }
            ?.any { it in ONE_TIME_TICKET_KEYS }
            ?: false

    /** 安全解析 http(s) URL；畸形或非 http(s) 返回 null。 */
    fun parseHttpUrl(raw: String): URI? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    /** 通道标签（存档存枚举名）：隧道域名 / 局域网明文 / 公网。 */
    fun channelLabelFor(uri: URI): String {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (TUNNEL_HOST_SUFFIXES.any { host.endsWith(it) }) return ChannelKind.TUNNEL.name
        if (uri.scheme.equals("http", ignoreCase = true)) return ChannelKind.LAN.name
        return ChannelKind.PUBLIC.name
    }

    /**
     * 通道标签（按裸主机形态判定）：隧道域名 → 隧道、回环/私网地址 → 局域网、
     * 其余 → 公网。供无 scheme 可依的解析器（server 为 host:port 的 scheme
     * 载荷、自签 CA 的 https 局域网 origin 等）共用。
     */
    fun channelLabelForHost(host: String?): String {
        val normalized = host?.lowercase(Locale.ROOT).orEmpty()
        if (TUNNEL_HOST_SUFFIXES.any { normalized.endsWith(it) }) return ChannelKind.TUNNEL.name
        if (isPrivateHost(normalized)) return ChannelKind.LAN.name
        return ChannelKind.PUBLIC.name
    }

    /** 主机是否回环/私网地址（localhost、127.0.0.1、RFC1918 三段）；非 IPv4 数字形态一律否。 */
    fun isPrivateHost(host: String?): Boolean {
        val normalized = host?.lowercase(Locale.ROOT).orEmpty()
        if (normalized == "localhost" || normalized == "127.0.0.1" || normalized == "::1") return true
        if (normalized.startsWith("fc") || normalized.startsWith("fd") || normalized.startsWith("fe80")) {
            // IPv6 ULA（fc00::/7）与链路本地（fe80::/10）的简单前缀判定
            return normalized.contains(':')
        }
        if (normalized.endsWith(".local")) return true
        val octets = normalized.split('.')
        if (octets.size == 4 && octets.all { it.all(Char::isDigit) }) {
            val values = octets.map { it.toIntOrNull() ?: return false }
            return values[0] == 10 ||
                (values[0] == 192 && values[1] == 168) ||
                (values[0] == 172 && values[1] in 16..31)
        }
        return false
    }

    /** URL 的 host（小写）；畸形或无 host 返回 null。 */
    fun hostOf(url: String): String? =
        runCatching { URI(url.trim()).host }.getOrNull()?.lowercase(Locale.ROOT)

    /**
     * 剥离凭据的存档地址：保留 scheme/host/port/路径，去掉 query 与 fragment
     * ——配对凭据（auth/invite/secret）只入加密存档，明文不落设备清单。
     */
    fun targetUrlWithoutSecret(uri: URI): String = buildString {
        append(uri.scheme.lowercase(Locale.ROOT)).append("://")
        append(uri.host?.lowercase(Locale.ROOT))
        if (uri.port != -1) append(':').append(uri.port)
        append(uri.rawPath?.ifEmpty { "/" } ?: "/")
    }

    /** 读取同名查询参数的全部值（保序；dshremote 的 server 可重复）。 */
    fun queryParams(uri: URI, name: String): List<String> {
        val query = uri.rawQuery ?: return emptyList()
        val prefix = "$name="
        return query.split('&')
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .map { it.substringAfter('=') }
            .filter { it.isNotEmpty() }
    }

    /** 读取首个同名查询参数的值。 */
    fun queryParam(uri: URI, name: String): String? {
        val query = uri.rawQuery ?: return null
        val prefix = "$name="
        return query.split('&')
            .firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }
    }
}
