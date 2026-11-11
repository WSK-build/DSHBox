package com.dshbox.remote.parse

import java.net.URI
import java.util.Locale

/**
 * 通用 URL 解析器：签名路由的兜底通道。
 *
 * 无插件签名命中的 URL（dsh-pocket 未覆盖的端口形态、anonRTtty 类一次性
 * ticket URL 等）落「通用 URL 设备」：透明代理、无凭据注入。载荷含 query
 * 或 fragment（一次性票据的常见位置）时，剥离后的地址入清单、完整 URL
 * 整体作为端点凭据入加密存档——会话首次加载地址由存档凭据重建，明文不落
 * 设备清单；用户可在设备卡上标注插件类型。
 */
class GenericUrlParser(override val id: String = ParserIds.GENERIC) : PairingParser {

    override fun matches(raw: String): Boolean {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    override fun parse(raw: String): ParsedPairing {
        if (!matches(raw)) throw PairingParseException("Not a usable URL payload: ${SensitiveTextRedactor.redactAndTruncate(raw)}")
        val trimmed = raw.trim()
        val uri = URI(trimmed)
        // 凭据判定收敛：fragment 非空或 query 含已知一次性票据键——持久
        // 参数（?lang= 等）是地址的一部分，不剥离、不消费、不入存档
        val carriesSecret = !uri.rawFragment.isNullOrEmpty() ||
            UrlParserSupport.queryCarriesTicketKey(uri.rawQuery)
        return ParsedPairing(
            parserId = id,
            deviceName = uri.host ?: trimmed,
            endpoints = listOf(
                ParsedEndpoint(
                    targetUrl = if (carriesSecret) {
                        UrlParserSupport.targetUrlWithoutSecret(uri)
                    } else {
                        trimmed
                    },
                    channelLabel = ChannelKind.URL.name,
                    credential = if (carriesSecret) trimmed else null,
                ),
            ),
        )
    }
}
