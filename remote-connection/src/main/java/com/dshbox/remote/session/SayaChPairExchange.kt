package com.dshbox.remote.session

import com.dshbox.remote.parse.FlatJsonReader
import com.dshbox.remote.parse.PairingParseException
import com.dshbox.remote.parse.SensitiveTextRedactor

/**
 * saya-ch / dsh-mobile 非 iroh 通道（LAN/公网 HTTPS）的 native-pair 兑换单元：
 * 把配对链接里的一次性票（`token`）在窗口内兑换为会话凭据（session cookie +
 * CSRF）。
 *
 * 纯逻辑 + 可注入传输：本单元不做任何网络 I/O、不落任何存档，HTTP 细节由
 * [SayaChPairTransport] 注入（生产实现见 [SayaChHttpTransport]；单测用 fake）。
 * 失败文案一律经 [SensitiveTextRedactor] 脱敏，且不携带 token 原文——token 是
 * 一次性凭据，不得进异常链路或日志。
 */

/** native-pair 兑换请求：目标 origin（https）与配对链接里的一次性票。 */
data class SayaChPairRequest(val origin: String, val token: String)

/**
 * native-pair 兑换产物：会话 cookie（名/值）与 CSRF。
 *
 * `deviceToken`（90 天）与 `sessionExpiresAt` 由调用方写入加密存档，供
 * native-renew 自动续期；`sessionToken` 本身即会话 cookie 值。
 */
data class SayaChPairResult(
    /** 会话 cookie 名：`__Host-dsh_ma_session`（优先）或 `dsh_ma_session`。 */
    val cookieName: String,
    /** 会话 cookie 值（即网关的 sessionToken，随会话逐请求注入）。 */
    val cookieValue: String,
    /** CSRF 令牌：写请求的 `x-dsh-mobile-csrf` 头值。 */
    val csrfToken: String,
    /** 90 天设备凭据：native-renew 续期用；native-renew 响应里为 null。 */
    val deviceToken: String? = null,
    /** 会话到期时间（epoch millis，网关给出）；未知为 null。 */
    val sessionExpiresAt: Long? = null,
)

/** native-pair 响应的传输视图：状态码、响应头（保序，Set-Cookie 可重复）与响应体文本。 */
data class SayaChHttpResponse(
    val statusCode: Int,
    val headers: List<Pair<String, String>>,
    val body: String,
)

/** 可注入传输：向 `url` POST 一个 JSON 对象体并返回响应视图（不做重定向/重试）。 */
fun interface SayaChPairTransport {
    fun post(url: String, jsonBody: String): SayaChHttpResponse
}

/** 兑换失败：文案经脱敏且不含 token 明文；`statusCode` 仅在网关明确拒绝时非空。 */
class SayaChPairException(message: String, val statusCode: Int? = null) : Exception(message)

object SayaChPairExchange {

    /** 网关 native-pair 固定路径（dsh-mobile gateway.ts 的 AUTH_PREFIX 分支）。 */
    const val NATIVE_PAIR_PATH = "/mobile-access/auth/native-pair"

    /** 会话 cookie：TLS 下双写，`__Host-` 前缀优先（绑定精确 origin）。 */
    const val HOST_SESSION_COOKIE = "__Host-dsh_ma_session"
    const val SESSION_COOKIE = "dsh_ma_session"

    /** 响应 JSON 的字段：驼峰为当前实现，snake_case 为宽容形态。 */
    private const val SESSION_TOKEN_FIELD = "sessionToken"
    private const val SESSION_TOKEN_FIELD_SNAKE = "session_token"
    private const val DEVICE_TOKEN_FIELD = "deviceToken"
    private const val DEVICE_TOKEN_FIELD_SNAKE = "device_token"
    private const val SESSION_EXPIRES_FIELD = "sessionExpiresAt"
    private const val SESSION_EXPIRES_FIELD_SNAKE = "session_expires_at"
    private const val CSRF_FIELD = "csrfToken"
    private const val CSRF_FIELD_SNAKE = "csrf_token"

    /**
     * 请求体构造：严格 `{"token":"<转义后的 token>"}`。token 虽在配对链接层
     * 已按 base64url 白名单校验，这里仍做完整 JSON 字符串转义（`"`、`\` 与
     * 控制字符），不依赖上游字符集承诺。
     */
    fun requestJson(token: String): String =
        "{\"token\":\"${escapeJson(token)}\"}"

    /**
     * 解析 native-pair 响应：仅 2xx 视为成功（非 2xx 抛带状态码的异常，文案
     * 只含状态码——不回显响应体，body 可能含网关内部细节）；Set-Cookie 取
     * `__Host-dsh_ma_session`（优先）或 `dsh_ma_session`，缺 cookie 失败；
     * CSRF 从响应 JSON 的 `csrfToken`/`csrf_token` 读取，必填。
     */
    fun parse(status: Int, headers: List<Pair<String, String>>, body: String): SayaChPairResult {
        if (status !in 200..299) {
            val hint = if (status == 403) {
                "（配对码可能已过期或已使用，或网关同源校验失败；请重新生成配对码后立即添加）"
            } else {
                ""
            }
            throw SayaChPairException(
                "native-pair rejected: HTTP $status$hint",
                statusCode = status,
            )
        }
        val fields = FlatJsonReader.read(body)
            ?: throw SayaChPairException(
                "native-pair response is not a JSON object (${SensitiveTextRedactor.redactAndTruncate(body)})",
            )
        // 官方协议：native-pair 201 的响应体直接给 sessionToken/csrfToken，
        // 不依赖 Set-Cookie（官方 Android App 自行把它写成会话 cookie）。
        // 兼容带 Set-Cookie 的旧形态：优先用服务端下发的 cookie，否则用 JSON
        // sessionToken 合成 __Host- 会话 cookie。
        val jsonSession = fields[SESSION_TOKEN_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[SESSION_TOKEN_FIELD_SNAKE]?.takeIf { it.isNotBlank() }
        val cookie = sessionCookie(headers)
            ?: jsonSession?.let { HOST_SESSION_COOKIE to it }
            ?: throw SayaChPairException(
                "native-pair response is missing session token/cookie (HTTP $status)",
            )
        val csrf = fields[CSRF_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[CSRF_FIELD_SNAKE]?.takeIf { it.isNotBlank() }
            ?: throw SayaChPairException("native-pair response is missing csrf token (HTTP $status)")
        val deviceToken = fields[DEVICE_TOKEN_FIELD]?.takeIf { it.isNotBlank() }
            ?: fields[DEVICE_TOKEN_FIELD_SNAKE]?.takeIf { it.isNotBlank() }
        val sessionExpiresAt = (fields[SESSION_EXPIRES_FIELD] ?: fields[SESSION_EXPIRES_FIELD_SNAKE])
            ?.toLongOrNull()
        return SayaChPairResult(
            cookieName = cookie.first,
            cookieValue = cookie.second,
            csrfToken = csrf,
            deviceToken = deviceToken,
            sessionExpiresAt = sessionExpiresAt,
        )
    }

    /**
     * 会话 cookie 提取：按 `name=value` 的首段精确匹配 cookie 名（值 trim）。
     * `__Host-` 前缀按 RFC 6265bis 的约定校验：必须带 `Secure` 与 `Path=/`、
     * 不得带 `Domain`——不满足的 `__Host-` 候选视为无效（防前缀干扰项），
     * 回落普通名。
     */
    internal fun sessionCookie(headers: List<Pair<String, String>>): Pair<String, String>? {
        val candidates = headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }
            .map { it.second }
            .mapNotNull(::parseSetCookie)
        val host = candidates.firstOrNull { it.first == HOST_SESSION_COOKIE }
        val plain = candidates.firstOrNull { it.first == SESSION_COOKIE }
        return (host ?: plain)?.let { it.first to it.second }
    }

    /** 单条 Set-Cookie 的解析：`(名, 值, 属性集合)`；名不匹配会话 cookie 返回 null。 */
    private fun parseSetCookie(header: String): Triple<String, String, Set<String>>? {
        val parts = header.split(';')
        val pair = parts.firstOrNull()?.trim() ?: return null
        val eq = pair.indexOf('=')
        if (eq <= 0) return null
        val name = pair.substringBefore('=').trim()
        val value = pair.substringAfter('=').substringBefore(';').trim()
        if (name != HOST_SESSION_COOKIE && name != SESSION_COOKIE) return null
        if (value.isEmpty()) return null
        val attributes = parts.drop(1).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        // RFC 6265bis：__Host- 前缀要求 Secure + Path=/（恰为根路径）+ 无 Domain
        if (name == HOST_SESSION_COOKIE) {
            val hasSecure = attributes.any { it == "secure" }
            val rootPath = attributes.any { it == "path=/" || it == "path" }
            val hasDomain = attributes.any { it.startsWith("domain") }
            if (!hasSecure || !rootPath || hasDomain) return null
        }
        return Triple(name, value, attributes.toSet())
    }

    /** JSON 字符串转义：引号、反斜杠与 <0x20 控制字符（\b\f\n\r\t 具名，余 \uXXXX）。 */
    private fun escapeJson(value: String): String = buildString {
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character < ' ') {
                        append("\\u").append(String.format("%04x", character.code))
                    } else {
                        append(character)
                    }
            }
        }
    }
}
