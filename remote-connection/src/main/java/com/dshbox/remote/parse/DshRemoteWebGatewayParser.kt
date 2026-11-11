package com.dshbox.remote.parse

import java.net.URI

/**
 * dsh-remote-web-gateway 配对解析器：URL 签名 `…/pair#<secret>`（一次性票，
 * 5 分钟有效）。
 *
 * 该插件的配对页由页面 JS 读取地址 fragment 完成兑换（query 携带凭据会被
 * 上游一律拒绝），故 fragment 是配对链路的必要组成：完整配对 URL 整体入
 * 加密存档，会话的首次加载地址由其重建（fragment 只存在于渲染器侧地址，
 * 不进上游请求、不落明文存档）；票一次性，兑换出的 Device Session cookie
 * 由代理侧罐代持持久。
 */
class DshRemoteWebGatewayParser(override val id: String = ParserIds.GATEWAY) : PairingParser {

    override fun matches(raw: String): Boolean {
        val uri = UrlParserSupport.parseHttpUrl(raw) ?: return false
        return (uri.rawPath ?: "/").startsWith(PAIR_PATH) && !uri.rawFragment.isNullOrEmpty()
    }

    override fun parse(raw: String): ParsedPairing {
        val uri = UrlParserSupport.parseHttpUrl(raw)
            ?: throw PairingParseException("Not a gateway pairing URL: ${SensitiveTextRedactor.redactAndTruncate(raw)}")
        if (!matches(raw)) {
            throw PairingParseException(
                "Gateway pairing URL without secret: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        }
        return ParsedPairing(
            parserId = id,
            deviceName = uri.host ?: id,
            endpoints = listOf(
                ParsedEndpoint(
                    // 凭据不入清单存档：目标地址剥掉 fragment，完整配对 URL
                    // 整体作为端点凭据入加密存档（会话首载与首跳改写用）
                    targetUrl = UrlParserSupport.targetUrlWithoutSecret(uri),
                    channelLabel = ChannelKind.TUNNEL.name,
                    credential = raw.trim(),
                ),
            ),
        )
    }

    private companion object {
        const val PAIR_PATH = "/pair"
    }
}
