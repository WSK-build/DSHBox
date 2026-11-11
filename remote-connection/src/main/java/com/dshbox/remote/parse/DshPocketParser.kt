package com.dshbox.remote.parse

import java.net.URI

/**
 * dsh-pocket 配对解析器：裸 URL 形态（局域网 `http://<ip>:3081` 或公网
 * `https://<随机子域>.trycloudflare.com`）。
 *
 * 该插件二维码只含纯 URL、无凭据：会话注入器为透明直通，8 位数字 PIN 由
 * 页面表单输入，兑换出的会话 cookie 由代理侧罐代持。
 */
class DshPocketParser(override val id: String = ParserIds.POCKET) : PairingParser {

    override fun matches(raw: String): Boolean {
        val uri = UrlParserSupport.parseHttpUrl(raw) ?: return false
        // 该插件二维码为纯 URL：携带 query/fragment 的隧道地址是其它形态
        // （一次性 ticket / 配对 secret），不得认领——凭据会被剥离丢弃
        if (!uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) return false
        return uri.port == POCKET_PORT ||
            uri.host?.lowercase().orEmpty().endsWith(TUNNEL_HOST_SUFFIX)
    }

    override fun parse(raw: String): ParsedPairing {
        val uri = UrlParserSupport.parseHttpUrl(raw)
            ?: throw PairingParseException("Not a dsh-pocket URL: ${SensitiveTextRedactor.redactAndTruncate(raw)}")
        return ParsedPairing(
            parserId = id,
            deviceName = uri.host ?: id,
            endpoints = listOf(
                ParsedEndpoint(
                    targetUrl = UrlParserSupport.targetUrlWithoutSecret(uri),
                    channelLabel = UrlParserSupport.channelLabelFor(uri),
                    credential = null,
                ),
            ),
        )
    }

    private companion object {
        const val POCKET_PORT = 3081
        const val TUNNEL_HOST_SUFFIX = ".trycloudflare.com"
    }
}
