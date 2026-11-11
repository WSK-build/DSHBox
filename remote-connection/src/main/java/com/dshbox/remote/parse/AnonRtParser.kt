package com.dshbox.remote.parse

/**
 * anonRTtty 配对解析器：URL 签名 `http(s)://<host>[:port]/pair?ticket=<票>`
 * （一次性票，短时效有效）。
 *
 * 与 dsh-remote-web-gateway 的 `/pair` 配对页同路径、凭据位置不同：gateway
 * 的 secret 在 fragment（注册顺序在前，fragment 形态先被认领），本解析器
 * 只认领无 fragment 的 query ticket 形态，两签名互斥。票经会话首次加载
 * 地址携带（生命周期同通用票据通道）：完整配对 URL 整体入加密存档，清单
 * 只存剥离 ticket 的地址（保留 /pair 路径作应用入口），消费后不重放票。
 */
class AnonRtParser(override val id: String = ParserIds.ANON_RT) : PairingParser {

    override fun matches(raw: String): Boolean {
        val uri = UrlParserSupport.parseHttpUrl(raw) ?: return false
        // fragment 形态是 gateway 的配对页：不认领（两签名互斥不重叠）
        if (!uri.rawFragment.isNullOrEmpty()) return false
        return (uri.rawPath ?: "/") == PAIR_PATH &&
            UrlParserSupport.queryParam(uri, TICKET_PARAM) != null
    }

    override fun parse(raw: String): ParsedPairing {
        val uri = UrlParserSupport.parseHttpUrl(raw)
            ?: throw PairingParseException(
                "Not a usable URL payload: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        if (!matches(raw)) {
            throw PairingParseException(
                "anonRT pairing URL without ticket: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        }
        return ParsedPairing(
            parserId = id,
            deviceName = uri.host ?: id,
            endpoints = listOf(
                ParsedEndpoint(
                    // 清单只存剥离 ticket 的存档地址（保留 /pair 应用入口路径）；
                    // 完整配对 URL 整体作为凭据入加密存档（会话首载重建用）
                    targetUrl = UrlParserSupport.targetUrlWithoutSecret(uri),
                    channelLabel = UrlParserSupport.channelLabelFor(uri),
                    credential = raw.trim(),
                ),
            ),
        )
    }

    private companion object {
        const val PAIR_PATH = "/pair"
        const val TICKET_PARAM = "ticket"
    }
}
