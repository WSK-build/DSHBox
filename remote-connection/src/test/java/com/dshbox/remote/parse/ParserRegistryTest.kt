package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserRegistryTest {

    /** 带 URL 签名的桩解析器：模拟插件 parser（按查询参数签名命中）。 */
    private class SignatureParser(private val idValue: String) : PairingParser {
        override val id: String get() = idValue

        override fun matches(raw: String): Boolean =
            runCatching { java.net.URI(raw).rawQuery?.contains("auth=") }.getOrNull() == true

        override fun parse(raw: String): ParsedPairing = ParsedPairing(
            parserId = id,
            deviceName = "signed",
            endpoints = listOf(ParsedEndpoint(raw, "签名", null)),
        )
    }

    private val registry = ParserRegistry(urlParsers = listOf(SignatureParser("dsh-bridge")))

    @Test fun formDetection() {
        assertEquals(PayloadForm.URL, registry.detectForm("https://host:3080/login"))
        assertEquals(PayloadForm.URL, registry.detectForm("HTTPS://host:3080/login"))
        assertEquals(PayloadForm.CUSTOM_SCHEME, registry.detectForm("dshremote://pair?token=abc"))
        assertEquals(PayloadForm.JSON, registry.detectForm("""{"type":"pair"}"""))
        // 竖线判定收窄：首段不可解析为 http(s) 的载荷不再按分隔形态兜底
        assertEquals(PayloadForm.UNKNOWN, registry.detectForm("host|port|token"))
        assertEquals(PayloadForm.UNKNOWN, registry.detectForm("random text payload"))
        assertEquals(PayloadForm.UNKNOWN, registry.detectForm(""))
    }

    @Test fun urlShapedDelimitedTextStaysDelimitedForm() {
        // 竖线不是合法 URL 字符：`http(s)地址|令牌`（ChongYep 手输，首段可
        // 解析为 http(s) 且恰一个竖线）落分隔文本路由
        assertEquals(
            PayloadForm.DELIMITED_TEXT,
            registry.detectForm("http://192.168.1.5:8787|token-value"),
        )
        assertEquals(
            PayloadForm.DELIMITED_TEXT,
            registry.detectForm("https://chongyep.example.com|token-value"),
        )
        // 多段竖线（首段可解析）：不再判分隔形态——显式报错而非静默建档
        assertEquals(PayloadForm.URL, registry.detectForm("http://host:8787|a|b"))
    }

    @Test fun urlSignatureRoutingPrefersPluginParser() {
        val result = registry.parse("http://pc.local:3082/?auth=tok")
        assertTrue(result.isSuccess)
        assertEquals("dsh-bridge", result.getOrThrow().parserId)
    }

    @Test fun bareUrlFallsBackToGenericParser() {
        val result = registry.parse("http://192.168.1.5:3081")
        assertTrue(result.isSuccess)
        val pairing = result.getOrThrow()
        assertEquals(GenericUrlParser().id, pairing.parserId)
        assertEquals(1, pairing.endpoints.size)
        assertEquals("http://192.168.1.5:3081", pairing.endpoints[0].targetUrl)
        assertEquals("192.168.1.5", pairing.deviceName)
    }

    @Test fun customSchemeWithoutParserFailsRedacted() {
        val raw = "dshremote://pair?token=SUPERSECRET123"
        val result = registry.parse(raw)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertFalse(message.contains("SUPERSECRET123"))
        assertTrue(message.contains("token=***"))
        assertTrue(message.contains("dshremote://pair"))
    }

    @Test fun jsonWithoutParserFailsRedacted() {
        val raw = """{"type":"pair","secret":"JSONSECRET99"}"""
        val result = registry.parse(raw)
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertFalse(message.contains("JSONSECRET99"))
        assertTrue(message.contains("***"))
    }

    @Test fun delimitedTextWithoutParserFailsRedacted() {
        val raw = "host|token=SECRET|extra"
        val result = registry.parse(raw)
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()!!.message!!.contains("SECRET"))
    }

    @Test fun unknownFormFailsRedacted() {
        val result = registry.parse("totally unknown payload token=LEAKME")
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertFalse(message.contains("LEAKME"))
        assertTrue(message.contains("token=***"))
    }

    @Test fun longPayloadIsTruncatedAfterRedaction() {
        val raw = "x".repeat(500) + "?token=SECRET"
        val message = registry.parse(raw).exceptionOrNull()!!.message!!
        assertTrue(message.length < 300)
        assertFalse(message.contains("SECRET"))
    }

    @Test fun fragmentIsRedactedAsSecret() {
        // fragment 是一次性票的藏身处：即使解析失败，文案里也不得出现票值
        val message = registry.parse("dshremote://pair#oneshotticket").exceptionOrNull()!!.message!!
        assertFalse(message.contains("oneshotticket"))
        assertTrue(message.contains("#***"))
    }

    /**
     * 生产路由（含账号型直连解析器与通用兜底）下，明文 http 的账号型入口
     * 经 `registry.parse` 直接失败：直连标记只在 https 载荷上产生，非
     * https 载荷在解析期拒绝——既不落账号型端点也不产生 generic 端点
     * （解析层不回落）。
     */
    @Test fun nonHttpsAccountPayloadsFailThroughProductionRegistry() {
        val production = ParserRegistry(
            urlParsers = p0UrlParsers() + listOf(
                AnonRtParser(),
                ZcodeRemoteParser(),
                DsHarnessRemoteParser(),
            ),
        )
        assertTrue(production.parse("http://zcode.z.ai/remote/abc").isFailure)
        assertTrue(production.parse("http://dsh.r2049.cn/app/xyz").isFailure)
        // 通用兜底也不承接：generic 路径同样拒绝非直连语义的明文账号入口，
        // 不会静默产出通用网页端点
        val genericFallback = GenericUrlParser().parse("http://zcode.z.ai/remote/abc")
        assertFalse(
            genericFallback.endpoints
                .any { it.targetUrl == "http://zcode.z.ai/remote/abc" && it.sessionMode != null },
        )
    }
}
