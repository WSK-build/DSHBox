package com.dshbox.remote.parse

import java.net.URI

/**
 * dsh-bridge 配对解析器：URL 签名 `?auth=<48 位十六进制 token>`。
 *
 * 存档地址剥离 auth 参数（明文凭据只入加密存档）；会话注入器在首个文档
 * 请求（直至检出会话 cookie）追加 `?auth=<token>`，兑换出的会话 cookie
 * 由代理侧罐代持。
 */
class DshBridgeParser(override val id: String = ParserIds.BRIDGE) : PairingParser {

    override fun matches(raw: String): Boolean {
        val uri = UrlParserSupport.parseHttpUrl(raw) ?: return false
        val token = UrlParserSupport.queryParam(uri, AUTH_PARAM) ?: return false
        return token.matches(TOKEN_PATTERN)
    }

    override fun parse(raw: String): ParsedPairing {
        val uri = UrlParserSupport.parseHttpUrl(raw)
            ?: throw PairingParseException("Not a dsh-bridge URL: ${SensitiveTextRedactor.redactAndTruncate(raw)}")
        val token = UrlParserSupport.queryParam(uri, AUTH_PARAM)
            ?: throw PairingParseException("dsh-bridge URL without auth token: ${SensitiveTextRedactor.redactAndTruncate(raw)}")
        return ParsedPairing(
            parserId = id,
            deviceName = uri.host ?: id,
            endpoints = listOf(
                ParsedEndpoint(
                    targetUrl = UrlParserSupport.targetUrlWithoutSecret(uri),
                    channelLabel = UrlParserSupport.channelLabelFor(uri),
                    credential = token,
                ),
            ),
        )
    }

    private companion object {
        const val AUTH_PARAM = "auth"
        val TOKEN_PATTERN = Regex("""[0-9a-fA-F]{48}""")
    }
}
