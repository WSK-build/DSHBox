package com.dshbox.remote.parse

import java.net.URI
import java.util.Locale

/**
 * 配对解析器注册表：扫码与手动输入共用的两级路由入口。
 *
 * 第一级·形态路由（与 [detectForm] 实现一致）：JSON 对象 → URL（整段可解析
 * 为 http(s)）→ 竖线分隔文本（收窄口径：首段可解析为 http(s) 且恰一个竖线）
 * → 自定义 scheme → URL（scheme 前缀兜底）→ 未知（显式报错）。
 * 第二级·签名路由：URL 形态内部按各插件解析器的签名（路径/参数/端口模式）
 * 分发；无签名命中落通用 URL 通道。新增插件 = 注册新解析器，内核零改动。
 *
 * 任何失败路径的输出（异常文案）都经 [SensitiveTextRedactor] 脱敏后再截断，
 * 保证 `dshremote://pair?token=…` 类载荷不会把凭据带出模块。
 */
class ParserRegistry(
    /** URL 签名解析器：按注册顺序匹配。 */
    private val urlParsers: List<PairingParser> = emptyList(),
    /** 自定义 scheme 解析器（dshremote:// 等，随插件接入注册）。 */
    private val schemeParsers: List<PairingParser> = emptyList(),
    /** JSON 描述符解析器（随插件接入注册）。 */
    private val jsonParsers: List<PairingParser> = emptyList(),
    /** 竖线分隔文本解析器（随插件接入注册）。 */
    private val delimitedParsers: List<PairingParser> = emptyList(),
    /** URL 兜底通道：默认通用 URL 解析器。 */
    private val fallbackUrlParser: PairingParser = GenericUrlParser(),
) {

    /**
     * 载荷形态判定（第一级路由）。竖线判定收窄：竖线不是合法 URL 字符，
     * 含竖线载荷的整段 URL 解析必然失败，但只有「首段可解析为 http(s) 且
     * 恰一个竖线」的 `http(s)地址|令牌` 形态才判分隔文本（ChongYep 手输
     * 签名）——首段不可解析或多段竖线的载荷不再按分隔形态兜底建档，转
     * scheme/未知形态显式报错。
     */
    fun detectForm(raw: String): PayloadForm {
        val payload = raw.trim()
        if (payload.isEmpty()) return PayloadForm.UNKNOWN
        if (payload.startsWith("{")) return PayloadForm.JSON
        if (UrlParserSupport.parseHttpUrl(payload) != null) return PayloadForm.URL
        if (payload.count { it == '|' } == 1 &&
            UrlParserSupport.parseHttpUrl(payload.substringBefore('|').trim()) != null
        ) {
            return PayloadForm.DELIMITED_TEXT
        }
        val schemeMatch = SCHEME_REGEX.matchAt(payload, 0) ?: return PayloadForm.UNKNOWN
        val scheme = schemeMatch.groupValues[1].lowercase(Locale.ROOT)
        return when (scheme) {
            "http", "https" -> PayloadForm.URL
            else -> PayloadForm.CUSTOM_SCHEME
        }
    }

    /** 解析配对载荷；无解析器可用或解析失败时抛出脱敏后的 [PairingParseException]。 */
    fun parse(raw: String): Result<ParsedPairing> = runCatching {
        try {
            route(raw).parse(raw)
        } catch (error: PairingParseException) {
            throw error
        } catch (error: Exception) {
            // 解析器自身的非预期失败同样不得携带原始载荷出模块
            throw PairingParseException(
                "Pairing payload rejected: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
        }
    }

    private fun route(raw: String): PairingParser {
        val candidates = when (detectForm(raw)) {
            PayloadForm.URL -> urlParsers + fallbackUrlParser
            PayloadForm.CUSTOM_SCHEME -> schemeParsers
            PayloadForm.JSON -> jsonParsers
            PayloadForm.DELIMITED_TEXT -> delimitedParsers
            PayloadForm.UNKNOWN -> emptyList()
        }
        return candidates.firstOrNull { it.matches(raw) }
            ?: throw PairingParseException(
                "Unsupported pairing payload: ${SensitiveTextRedactor.redactAndTruncate(raw)}",
            )
    }

    private companion object {
        val SCHEME_REGEX = Regex("""([a-z][a-z0-9+.\-]*)://""", RegexOption.IGNORE_CASE)
    }
}
