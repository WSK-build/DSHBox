package com.dshbox.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * saya-ch native-pair 兑换单元测试：成功（含 `__Host-` cookie 与 snake_case
 * 字段宽容）、非 2xx（不回显 body）、缺 csrf、缺 cookie、`__Host-` 前缀干扰
 * 项、请求体转义——全部用 fake transport/响应形态，纯 JVM，不联网不落档。
 */
class SayaChPairExchangeTest {

    private val token = "Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56Qr78St12Uvw3"

    private fun response(
        status: Int = 201,
        setCookie: List<String> = listOf("$HOST_COOKIE=$token; Path=/; Secure; HttpOnly; SameSite=Strict"),
        body: String = "{\"csrfToken\":\"csrf-value\",\"deviceToken\":\"device-90d\",\"sessionToken\":\"session-8h\"}",
    ): SayaChHttpResponse = SayaChHttpResponse(
        statusCode = status,
        headers = setCookie.map { "Set-Cookie" to it },
        body = body,
    )

    companion object {
        const val HOST_COOKIE = "__Host-dsh_ma_session"
        const val PLAIN_COOKIE = "dsh_ma_session"
    }

    @Test
    fun successWithHostCookieAndSnakeCaseFields() {
        var capturedUrl: String? = null
        val transport = SayaChPairTransport { url, _ ->
            capturedUrl = url
            SayaChHttpResponse(
                statusCode = 201,
                headers = listOf("Set-Cookie" to "$HOST_COOKIE=host-session-value; Path=/; Secure; HttpOnly"),
                body = "{\"device_token\":\"device-90d\",\"csrf_token\":\"csrf-value\",\"session_token\":\"session-8h\",\"sessionExpiresAt\":1730000000000}",
            )
        }
        val url = "https://192.168.1.20:3443".trimEnd('/') + SayaChPairExchange.NATIVE_PAIR_PATH
        val response = transport.post(url, SayaChPairExchange.requestJson(token))
        val result = SayaChPairExchange.parse(response.statusCode, response.headers, response.body)
        assertEquals("https://192.168.1.20:3443/mobile-access/auth/native-pair", capturedUrl)
        assertEquals(HOST_COOKIE, result.cookieName)
        assertEquals("host-session-value", result.cookieValue)
        assertEquals("csrf-value", result.csrfToken)
        assertEquals("device-90d", result.deviceToken)
        assertEquals(1730000000000L, result.sessionExpiresAt)
    }

    @Test
    fun non2xxFailsWithStatusWithoutTokenOrBodyEcho() {
        try {
            SayaChPairExchange.parse(
                status = 401,
                headers = emptyList(),
                body = "{\"error\":\"invalid_token\",\"token\":\"$token\",\"secret\":\"LEAK-MARKER\"}",
            )
            throw AssertionError("expected SayaChPairException")
        } catch (error: SayaChPairException) {
            assertEquals(401, error.statusCode)
            assertTrue(error.message!!.contains("401"))
            assertTrue(!error.message!!.contains(token))
            // 非 2xx 不回显响应体（网关内部细节不出模块）
            assertTrue(!error.message!!.contains("LEAK-MARKER"))
        }
    }

    @Test
    fun missingCsrfFails() {
        try {
            SayaChPairExchange.parse(
                status = 201,
                headers = listOf("Set-Cookie" to "$HOST_COOKIE=v; Path=/; Secure"),
                body = "{\"deviceToken\":\"device-90d\"}",
            )
            throw AssertionError("expected SayaChPairException")
        } catch (error: SayaChPairException) {
            assertTrue(error.message!!.contains("csrf"))
        }
    }

    @Test
    fun jsonSessionTokenIsUsedWithoutSetCookie() {
        val result = SayaChPairExchange.parse(
            status = 201,
            headers = listOf("Content-Type" to "application/json"),
            body = "{\"sessionToken\":\"session-8h\",\"csrfToken\":\"csrf-value\"}",
        )
        assertEquals("__Host-dsh_ma_session", result.cookieName)
        assertEquals("session-8h", result.cookieValue)
        assertEquals("csrf-value", result.csrfToken)
    }

    @Test
    fun missingSessionCookieFails() {
        try {
            SayaChPairExchange.parse(
                status = 201,
                headers = listOf("Content-Type" to "application/json"),
                body = "{\"csrfToken\":\"csrf-value\"}",
            )
            throw AssertionError("expected SayaChPairException")
        } catch (error: SayaChPairException) {
            assertTrue(error.message!!.contains("session token"))
        }
    }

    @Test
    fun hostPrefixRequiresSecurePathAndNoDomain() {
        // 合法 __Host-：Secure + Path=/ + 无 Domain → 优先选中
        val good = SayaChPairExchange.sessionCookie(
            listOf("Set-Cookie" to "$HOST_COOKIE=host-v; Path=/; Secure; HttpOnly"),
        )!!
        assertEquals(HOST_COOKIE to "host-v", good)
        // 缺 Secure → 无效
        assertNull(
            SayaChPairExchange.sessionCookie(
                listOf("Set-Cookie" to "$HOST_COOKIE=bad; Path=/; HttpOnly"),
            ),
        )
        // Path 非 / → 无效
        assertNull(
            SayaChPairExchange.sessionCookie(
                listOf("Set-Cookie" to "$HOST_COOKIE=bad; Path=/x; Secure"),
            ),
        )
        // 带 Domain → 无效
        assertNull(
            SayaChPairExchange.sessionCookie(
                listOf("Set-Cookie" to "$HOST_COOKIE=bad; Path=/; Secure; Domain=example.com"),
            ),
        )
        // __Host- 候选全部无效时回落普通名
        val fallback = SayaChPairExchange.sessionCookie(
            listOf(
                "Set-Cookie" to "$HOST_COOKIE=bad; Path=/x; Secure",
                "Set-Cookie" to "$PLAIN_COOKIE=plain-v; Path=/",
            ),
        )!!
        assertEquals(PLAIN_COOKIE to "plain-v", fallback)
    }

    @Test
    fun prefixDecoyCookiesAreIgnored() {
        // 前缀干扰项：名字带同前缀但不是会话 cookie（后缀扩展/大小写变体/
        // 值内伪装），精确名匹配必须跳过它们
        val chosen = SayaChPairExchange.sessionCookie(
            listOf(
                "Set-Cookie" to "__Host-dsh_ma_session_evil=evil-v; Path=/; Secure",
                "Set-Cookie" to "dsh_ma_session_extra=extra-v; Path=/",
                "Set-Cookie" to "dsh_ma_sessionX=decoy; Path=/",
            ),
        )
        assertNull(chosen)
        // 干扰项 + 真会话 cookie 并存：精确名命中真值
        val real = SayaChPairExchange.sessionCookie(
            listOf(
                "Set-Cookie" to "__Host-dsh_ma_session_evil=evil-v; Path=/; Secure",
                "Set-Cookie" to "$HOST_COOKIE=real-v; Path=/; Secure",
            ),
        )!!
        assertEquals(HOST_COOKIE to "real-v", real)
    }

    @Test
    fun valueIsTrimmedAndParamsIgnored() {
        val chosen = SayaChPairExchange.sessionCookie(
            listOf("Set-Cookie" to "$PLAIN_COOKIE=  padded-value ; Path=/; HttpOnly"),
        )!!
        assertEquals(PLAIN_COOKIE to "padded-value", chosen)
    }

    @Test
    fun requestJsonEscapes() {
        assertEquals("{\"token\":\"$token\"}", SayaChPairExchange.requestJson(token))
        // 期望体里的每个反斜杠都是字面转义序列（\" \\ \n \t）
        val tricky = "a\"b\\c\nd\te"
        assertEquals("{\"token\":\"a\\\"b\\\\c\\nd\\te\"}", SayaChPairExchange.requestJson(tricky))
    }
}

private fun assertNull(actual: Any?) {
    org.junit.Assert.assertNull(actual)
}
