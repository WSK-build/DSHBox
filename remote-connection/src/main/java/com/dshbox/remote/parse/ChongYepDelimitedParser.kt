package com.dshbox.remote.parse

/**
 * ChongYep 手输令牌解析器：竖线分隔文本 `<http(s)地址>|<token>`（分隔文本
 * 形态首个解析器）。
 *
 * 地址是上游根地址（query 若有属地址的一部分，不剥离）；token 为长期
 * Bearer 凭据（存代理侧、随会话逐请求注入，非一次性票）。畸形载荷（左段
 * 非 http(s) URL、右段为空、多段）抛脱敏异常——分隔文本的凭据位于首个
 * 竖线之后，错误文案经脱敏后不会携带令牌明文。
 */
class ChongYepDelimitedParser(override val id: String = ParserIds.CHONG_YEP) : PairingParser {

    override fun matches(raw: String): Boolean {
        val parts = raw.trim().split('|')
        if (parts.size < 2) return false
        // 右段（令牌）非空才算候选；多段交由 parse 显式报错
        if (parts[1].isBlank()) return false
        return UrlParserSupport.parseHttpUrl(parts[0]) != null
    }

    override fun parse(raw: String): ParsedPairing {
        val trimmed = raw.trim()
        val parts = trimmed.split('|')
        val uri = parts.firstOrNull()?.let { UrlParserSupport.parseHttpUrl(it) }
            ?: throw PairingParseException(
                "Not a ChongYep pairing payload: ${SensitiveTextRedactor.redactAndTruncate(trimmed)}",
            )
        val token = parts.getOrNull(1)?.trim().orEmpty()
        if (parts.size != 2 || token.isEmpty()) {
            throw PairingParseException(
                "ChongYep pairing payload must be exactly `<address>|<token>`: " +
                    SensitiveTextRedactor.redactAndTruncate(trimmed),
            )
        }
        return ParsedPairing(
            parserId = id,
            deviceName = uri.host ?: id,
            endpoints = listOf(
                ParsedEndpoint(
                    // 地址是上游根地址：query 若有属地址的一部分，仅剥离右段凭据
                    targetUrl = parts[0].trim(),
                    channelLabel = UrlParserSupport.channelLabelFor(uri),
                    credential = token,
                ),
            ),
        )
    }
}
