package com.dshbox.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * saya-ch HTTP 传输单元测试（不联网）：CA 指纹比对（解析层同口径：hex 大小
 * 写 / Base64 / Base64URL 四形态）与兑换结果解析（fake HTTP 响应形态）。
 */
class SayaChHttpTransportTest {

    @Test
    fun fingerprintMatchesAllParserAcceptedForms() {
        // 与 SayaChJsonParser.verifyCaFingerprint 相同的接受集：hex 大小写 /
        // Base64 / Base64URL——Base64 形态的 instanceId 不被裸 hex 归一化误判
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(java.util.Base64.getMimeDecoder().decode(TEST_CA_BASE64))
        val hexLower = digest.joinToString("") { "%02x".format(it) }
        val base64 = java.util.Base64.getEncoder().encodeToString(digest)
        val base64Url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        // 接受集与解析层 verifyCaFingerprint 一致（四形态）：hex 大小写/Base64/Base64URL
        for (form in listOf(hexLower, hexLower.uppercase(), base64, base64Url)) {
            assertTrue("form failed: $form", SayaChHttpTransport.fingerprintMatches(TEST_CA_BASE64, form))
        }
        // 不匹配：另一位翻转 / 缺 instance
        assertFalse(SayaChHttpTransport.fingerprintMatches(TEST_CA_BASE64, hexLower.dropLast(2) + "ff"))
        assertFalse(SayaChHttpTransport.fingerprintMatches(TEST_CA_BASE64, null))
        assertFalse(SayaChHttpTransport.fingerprintMatches(TEST_CA_BASE64, "  "))
    }

    @Test
    fun parseAcceptsFakeNativePairResponse() {
        // fake HTTP 响应（含 __Host- cookie 与可重复 Set-Cookie），不联网
        val headers = listOf(
            "Content-Type" to "application/json",
            "Set-Cookie" to "dsh_ma_session=plain-value; Path=/; Secure; HttpOnly",
            "Set-Cookie" to "__Host-dsh_ma_session=host-value; Path=/; Secure; HttpOnly",
        )
        val result = SayaChPairExchange.parse(
            status = 201,
            headers = headers,
            body = "{\"instanceId\":\"abc\",\"deviceId\":\"d\",\"deviceToken\":\"dev-token\"," +
                "\"sessionToken\":\"sess-token\",\"csrfToken\":\"csrf-token\",\"sessionExpiresAt\":1}",
        )
        assertEquals("__Host-dsh_ma_session", result.cookieName)
        assertEquals("host-value", result.cookieValue)
        assertEquals("csrf-token", result.csrfToken)
    }

    companion object {
        /** 与 SayaChJsonParserTest 相同的测试 CA（DER Base64），用于指纹计算。 */
        const val TEST_CA_BASE64 =
            "MIIBTjCB9aADAgECAggpN61zbh9Z8DAKBggqhkjOPQQDAzAcMRowGAYDVQQDExFzYXlhLXRlc3QtZ2F0ZXdheTAeFw0yNjExMDQxODE2MjNaFw0zNjExMDExODE2MjNaMBwxGjAYBgNVBAMTEXNheWEtdGVzdC1nYXRld2F5MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESv/reYmXrK56GlUzEBQGeXZq3nMhtjYbfD0Q/0jkzuGvifQir2A+GrXimSym4hwRtD9F5aUNi+qhohJ1h+dw/aMhMB8wHQYDVR0OBBYEFCupi9JZrK6O0CW7zB5rNIHfzK1eMAoGCCqGSM49BAMDA0gAMEUCIAugk+KaXi0gcVr0gObDo0C4omrK1jMOuT1eJ4t5NhlpAiEAyIw9FsoS0gdG59GzFM5QtSX5rF56lwWyNhcKCzhv3fI="

        /** 测试 CA 的 SHA-256 指纹（hex 小写）：instanceId 比对基准。 */
        val TEST_CA_FINGERPRINT: String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(java.util.Base64.getMimeDecoder().decode(TEST_CA_BASE64))
            .joinToString("") { "%02x".format(it) }
    }
}
