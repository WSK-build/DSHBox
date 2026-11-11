package com.dshbox.remote.parse

import java.net.URI

/**
 * dsh-full-remote 配对解析器：URL 签名 `…/_dsh_reverse_proxy/login?invite=…`
 * （一次性邀请码，15 分钟有效）。
 *
 * 存档地址保留登录路径、剥离 invite 参数；完整邀请 URL（含 invite）整体入
 * 加密存档——会话注入器把首个文档请求改用该存档 URL，兑换出的会话 cookie
 * 由代理侧罐代持。
 */
class DshFullRemoteParser(override val id: String = ParserIds.FULL_REMOTE) : PairingParser {

    override fun matches(raw: String): Boolean {
        val uri = UrlParserSupport.parseHttpUrl(raw) ?: return false
        return uri.rawPath.orEmpty().contains(LOGIN_PATH) &&
            !UrlParserSupport.queryParam(uri, INVITE_PARAM).isNullOrEmpty()
    }

    override fun parse(raw: String): ParsedPairing {
        val uri = UrlParserSupport.parseHttpUrl(raw)
            ?: throw PairingParseException("Not a dsh-full-remote URL: ${SensitiveTextRedactor.redactAndTruncate(raw)}")
        if (!matches(raw)) {
            throw PairingParseException(
                "dsh-full-remote URL without invite: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        }
        return ParsedPairing(
            parserId = id,
            deviceName = uri.host ?: id,
            endpoints = listOf(
                ParsedEndpoint(
                    // 凭据不入清单存档：目标地址剥掉 invite，完整邀请 URL 整体
                    // 作为端点凭据入加密存档（会话注入器改写首个文档请求用）
                    targetUrl = UrlParserSupport.targetUrlWithoutSecret(uri),
                    channelLabel = UrlParserSupport.channelLabelFor(uri),
                    credential = raw.trim(),
                ),
            ),
        )
    }

    private companion object {
        const val LOGIN_PATH = "/_dsh_reverse_proxy/login"
        const val INVITE_PARAM = "invite"
    }
}
