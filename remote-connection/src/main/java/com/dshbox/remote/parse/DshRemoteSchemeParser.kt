package com.dshbox.remote.parse

import java.net.URI

/**
 * dsh-Remote 配对解析器：自定义 scheme
 * `dshremote://pair?token=<t>&server=<host:port>&server=…`。
 *
 * 令牌是长期 Bearer 凭据（存代理侧、随会话逐请求注入）；`server` 参数可重复，
 * 每个取值产出一条端点（同一设备的局域网/公网多通道），通道标签按目标形态
 * 判定（隧道域名 → 隧道、明文 → 局域网、其余 → 公网）。
 */
class DshRemoteSchemeParser(override val id: String = ParserIds.REMOTE_SCHEME) : PairingParser {

    override fun matches(raw: String): Boolean {
        if (!raw.trim().lowercase().startsWith(SCHEME_PREFIX)) return false
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        // scheme 动作校验：authority 必须是配对动作（`dshremote://任意?…` 不认领）
        if (!uri.authority.equals(PAIR_AUTHORITY, ignoreCase = true)) return false
        val token = UrlParserSupport.queryParam(uri, TOKEN_PARAM).orEmpty()
        return token.isNotEmpty() && UrlParserSupport.queryParams(uri, SERVER_PARAM).isNotEmpty()
    }

    override fun parse(raw: String): ParsedPairing {
        val uri = runCatching { URI(raw.trim()) }.getOrNull()
            ?: throw PairingParseException(
                "Not a dsh-remote payload: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        if (!uri.authority.equals(PAIR_AUTHORITY, ignoreCase = true)) {
            throw PairingParseException(
                "Not a dsh-remote pairing payload: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        }
        val token = UrlParserSupport.queryParam(uri, TOKEN_PARAM).orEmpty()
        val servers = UrlParserSupport.queryParams(uri, SERVER_PARAM)
        if (token.isEmpty() || servers.isEmpty()) {
            throw PairingParseException(
                "dsh-remote payload without token or server: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        }
        return ParsedPairing(
            parserId = id,
            deviceName = deviceNameOf(servers.first()),
            endpoints = servers.map { server -> endpointFor(server, token) },
        )
    }

    /** server 取值（host:port）→ 端点：明文 HTTP 目标 + 按目标形态判定通道。 */
    private fun endpointFor(server: String, token: String): ParsedEndpoint {
        // 经安全解析复用 host 非空校验：无主机的畸形 server（如 "/path"）显式报错
        val uri = UrlParserSupport.parseHttpUrl("$HTTP_PREFIX$server")
            ?: throw PairingParseException(
                "dsh-remote server is malformed: ${SensitiveTextRedactor.redactAndTruncate(server)}",
            )
        return ParsedEndpoint(
            targetUrl = UrlParserSupport.targetUrlWithoutSecret(uri),
            // 通道标签按主机形态判定（隧道后缀清单的单一来源在 UrlParserSupport）
            channelLabel = UrlParserSupport.channelLabelForHost(uri.host),
            // 令牌为该设备全部通道共用：每条端点各持引用，存档按端点隔离
            credential = token,
        )
    }

    private fun deviceNameOf(server: String): String =
        server.substringBefore(':').ifEmpty { id }

    private companion object {
        const val SCHEME_PREFIX = "dshremote://"
        const val PAIR_AUTHORITY = "pair"
        const val TOKEN_PARAM = "token"
        const val SERVER_PARAM = "server"
        const val HTTP_PREFIX = "http://"
    }
}
