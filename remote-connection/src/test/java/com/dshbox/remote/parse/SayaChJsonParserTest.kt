package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * saya-ch / dsh-mobile JSON 配对描述符解析器测试：正例全字段、缺字段、
 * 过期、CA 指纹不符、CA 解码失败、type/version 不匹配、脱敏（gatewayToken/
 * irohCode/caCertificate 不进错误文案）。
 */
class SayaChJsonParserTest {

    private val parser = SayaChJsonParser()

    companion object {
        /** 测试夹具：自签 CA 证书（DER Base64）及其 SHA-256 指纹（hex）。 */
        internal const val CA_BASE64 =
            "MIIBTjCB9aADAgECAggpN61zbh9Z8DAKBggqhkjOPQQDAzAcMRowGAYDVQQDExFzYXlhLXRlc3QtZ2F0ZXdheTAeFw0yNjExMDQxODE2MjNaFw0zNjExMDExODE2MjNaMBwxGjAYBgNVBAMTEXNheWEtdGVzdC1nYXRld2F5MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAESv/reYmXrK56GlUzEBQGeXZq3nMhtjYbfD0Q/0jkzuGvifQir2A+GrXimSym4hwRtD9F5aUNi+qhohJ1h+dw/aMhMB8wHQYDVR0OBBYEFCupi9JZrK6O0CW7zB5rNIHfzK1eMAoGCCqGSM49BAMDA0gAMEUCIAugk+KaXi0gcVr0gObDo0C4omrK1jMOuT1eJ4t5NhlpAiEAyIw9FsoS0gdG59GzFM5QtSX5rF56lwWyNhcKCzhv3fI="
        internal const val CA_FINGERPRINT_HEX = "c4aa4290470914d318fadb8cdcf6646bf93785f290ccd92935c15889091244d4"

        private fun payload(
            origin: String = "https://192.168.1.20:4433",
            gatewayToken: String = "gw-token-value-43chars",
            caCertificate: String = CA_BASE64,
            instanceId: String = CA_FINGERPRINT_HEX,
            expiresAt: Long = System.currentTimeMillis() + 600_000,
            endpointId: String = "endpoint-abc123",
            type: String = "dsh-mobile-iroh-pairing",
            version: String = "1",
            irohCode: String = "482913",
        ): String = """{"version":$version,"type":"$type","endpointId":"$endpointId","origin":"$origin",""" +
            """"localPort":4433,"irohCode":"$irohCode","gatewayToken":"$gatewayToken",""" +
            """"caCertificate":"$caCertificate","instanceId":"$instanceId","expiresAt":$expiresAt}"""
    }

    @Test fun fullDescriptorParsesSingleEndpointWithGatewayTokenCredential() {
        assertTrue(parser.matches(payload()))
        val pairing = parser.parse(payload())
        assertEquals("saya-ch-remote", pairing.parserId)
        assertEquals("endpoint-abc123", pairing.deviceName)
        val endpoint = pairing.endpoints.single()
        // 目标取 origin 本身（剥路径/query）；origin 已带端口时 localPort 不参与
        assertEquals("https://192.168.1.20:4433", endpoint.targetUrl)
        assertEquals("LAN", endpoint.channelLabel)
        assertEquals("gw-token-value-43chars", endpoint.credential)
        assertEquals(CA_BASE64, endpoint.caCertificate)
    }

    @Test fun originWithoutPortFallsBackToLocalPort() {
        val endpoint = parser.parse(payload(origin = "https://192.168.1.20")).endpoints.single()
        assertEquals("https://192.168.1.20:4433", endpoint.targetUrl)
    }

    @Test fun channelLabelFollowsHostForm() {
        assertEquals(
            "TUNNEL",
            parser.parse(payload(origin = "https://saya-box.ts.net:4433")).endpoints.single().channelLabel,
        )
        assertEquals(
            "PUBLIC",
            parser.parse(payload(origin = "https://gw.example.com:4433")).endpoints.single().channelLabel,
        )
    }

    @Test fun expiryAcceptsIsoTimestampToo() {
        val iso = java.time.Instant.ofEpochMilli(System.currentTimeMillis() + 600_000).toString()
        val pairing = parser.parse(payload(expiresAt = -1).replace("\"expiresAt\":-1", "\"expiresAt\":\"$iso\""))
        assertEquals("saya-ch-remote", pairing.parserId)
    }

    @Test fun expiredDescriptorIsRejected() {
        val failure = runCatching { parser.parse(payload(expiresAt = System.currentTimeMillis() - 1_000)) }
        val message = failure.exceptionOrNull()!!.message!!
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertTrue(message.contains("expired"))
        // 过期文案不携带载荷片段
        assertFalse(message.contains("gw-token"))
    }

    @Test fun fingerprintMismatchIsRejected() {
        val failure = runCatching { parser.parse(payload(instanceId = "deadbeef")) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("fingerprint"))
    }

    @Test fun irohDescriptorIsRejectedExplicitly() {
        // iroh 描述符（endpointId 派生域名 + localPort=25443）：parse 显式
        // 拒绝，不产出可连接端点（避免建档后页面停留在加载态）
        val irohOrigin = "https://a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6.f7e8d9c0b1a2f3e4d5c6b7a8f9e0d1c2.iroh.localhost:25443"
        val failure = runCatching { parser.parse(payload(origin = irohOrigin)) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        val message = failure.exceptionOrNull()!!.message!!
        assertTrue(message.contains("iroh"))
        assertTrue(message.contains("局域网/Cloudflare"))
    }

    @Test fun fingerprintAcceptsHexCaseAndBase64Forms() {
        val upper = parser.parse(payload(instanceId = CA_FINGERPRINT_HEX.uppercase())).endpoints.single()
        assertEquals("https://192.168.1.20:4433", upper.targetUrl)
        val b64Digest = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.util.Base64.getMimeDecoder().decode(CA_BASE64)),
        )
        parser.parse(payload(instanceId = b64Digest))
    }

    @Test fun undecodableCaCertificateIsRejected() {
        val notACert = java.util.Base64.getEncoder().encodeToString("definitely not a certificate".toByteArray())
        val failure = runCatching { parser.parse(payload(caCertificate = notACert)) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("CA certificate"))
    }

    @Test fun gatewayTokenWithLineBreakIsRejected() {
        // 字段内换行不得穿透到加密存档（行式编码被破坏）与 HTTP 注入汇点
        val failure = runCatching { parser.parse(payload(gatewayToken = "abc\ndef")) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertFalse(failure.exceptionOrNull()!!.message!!.contains("abc"))
        // 值不进错误文案（脱敏口径）
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("illegal characters"))
    }

    @Test fun gatewayTokenWithNonBase64UrlCharsIsRejected() {
        // base64url 字符集外（+/= 与空格等 URL 元字符/分隔符）一律拒绝
        listOf("abc+def", "ab/cd", "ab=cd", "abc def").forEach { token ->
            val failure = runCatching { parser.parse(payload(gatewayToken = token)) }
            assertTrue("token=$token should be rejected", failure.exceptionOrNull() is PairingParseException)
        }
        assertTrue(parser.matches(payload(gatewayToken = "abc-def_123")))
    }

    @Test fun caCertificateWithMimeFoldingIsAcceptedAndNormalized() {
        // MIME 折行（每 64 字符插入 CR/LF）：剥折行后合法即接受，存档存剥离值
        val folded = CA_BASE64.chunked(64).joinToString("\r\n")
        val endpoint = parser.parse(payload(caCertificate = folded)).endpoints.single()
        assertEquals(CA_BASE64, endpoint.caCertificate)
    }

    @Test fun caCertificateWithLineBreakOrIllegalCharIsRejected() {
        // 剥折行后仍有非法字符（空格/元字符）：拒绝——换行不得成为凭据偷渡通道
        val failure = runCatching { parser.parse(payload(caCertificate = CA_BASE64 + "\nMIIB extra")) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("caCertificate"))
        val spaced = runCatching { parser.parse(payload(caCertificate = "MIIB TjCB")) }
        assertTrue(spaced.exceptionOrNull() is PairingParseException)
    }

    @Test fun nonHttpsOriginIsRejected() {
        val failure = runCatching { parser.parse(payload(origin = "http://192.168.1.20:4433")) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("https origin"))
    }

    @Test fun typeOrVersionMismatchIsNotClaimed() {
        assertFalse(parser.matches(payload(type = "other-pairing")))
        assertFalse(parser.matches(payload(version = "2")))
        val failure = runCatching { parser.parse(payload(version = "2")) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
    }

    @Test fun missingRequiredFieldIsRejected() {
        // 缺 gatewayToken（描述符按契约必备）
        val missing = payload().replace(""""gatewayToken":"gw-token-value-43chars",""", "")
        val failure = runCatching { parser.parse(missing) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("gatewayToken"))
    }

    @Test fun malformedJsonIsNotClaimed() {
        assertFalse(parser.matches("""{"type": "dsh-mobile-iroh-pairing"""))
        assertFalse(parser.matches("not json at all"))
    }

    @Test fun secretsNeverLeakIntoFailureMessages() {
        val failures = listOf(
            runCatching { parser.parse(payload(version = "9")) },
            runCatching { parser.parse(payload(origin = "http://x")) },
            runCatching { parser.parse(payload(expiresAt = System.currentTimeMillis() - 1)) },
            runCatching { parser.parse(payload(instanceId = "bad")) },
        )
        failures.forEach { failure ->
            val message = failure.exceptionOrNull()!!.message!!
            assertFalse(message.contains("gw-token-value-43chars"))
            assertFalse(message.contains("482913"))
            assertFalse(message.contains("MIIBTj"))
        }
    }

    @Test fun registryRoutesJsonDescriptorToSayaChParser() {
        val registry = ParserRegistry(
            urlParsers = p0UrlParsers() + listOf(AnonRtParser()),
            schemeParsers = listOf(DshRemoteSchemeParser()),
            jsonParsers = listOf(SayaChJsonParser()),
            delimitedParsers = listOf(ChongYepDelimitedParser()),
        )
        assertEquals(PayloadForm.JSON, registry.detectForm(payload()))
        assertEquals("saya-ch-remote", registry.parse(payload()).getOrThrow().parserId)
        // 未知 JSON：脱敏报错（gatewayToken 值被遮蔽）
        val message = registry.parse("""{"type":"other","gatewayToken":"JSONSECRET1"}""")
            .exceptionOrNull()!!.message!!
        assertFalse(message.contains("JSONSECRET1"))
        assertTrue(message.contains("gatewayToken"))
    }

    @Test fun gatewayTokenIsAOneTimeConsumableTicket() {
        // gatewayToken 是一次性配对票（native-pair 单次兑换）：进权威消费集合；
        // 兑换产物（会话 cookie/CSRF）的注入不受消费标记禁用（注入器选取在
        // 消费降级之前，见 AuthInjectors.forParser）
        assertTrue(ParserIds.SAYA_CH in ParserIds.CONSUMABLE_CREDENTIAL_PARSER_IDS)
    }
}
