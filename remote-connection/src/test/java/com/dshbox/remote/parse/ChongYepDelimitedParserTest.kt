package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChongYep 竖线分隔文本解析器测试：`地址|token` 正例、畸形载荷（左段非
 * http(s) URL / 右段为空 / 多段）脱敏报错、地址 query 保留、注册表路由。
 */
class ChongYepDelimitedParserTest {

    private val parser = ChongYepDelimitedParser()

    @Test fun delimitedPayloadIsParsed() {
        val raw = "http://192.168.1.5:8787|long-term-token"
        val pairing = parser.parse(raw)
        assertEquals("chongyep-remote", pairing.parserId)
        assertEquals("192.168.1.5", pairing.deviceName)
        val endpoint = pairing.endpoints.single()
        // 地址是上游根地址，仅剥离右段凭据
        assertEquals("http://192.168.1.5:8787", endpoint.targetUrl)
        assertEquals("long-term-token", endpoint.credential)
        assertEquals("LAN", endpoint.channelLabel)
    }

    @Test fun httpsAddressIsLabeledPublic() {
        val endpoint = parser.parse("https://chongyep.example.com|tok").endpoints.single()
        assertEquals("PUBLIC", endpoint.channelLabel)
        assertEquals("https://chongyep.example.com", endpoint.targetUrl)
    }

    @Test fun queryInAddressIsPartOfTargetUrl() {
        // 地址 query 属地址的一部分：不剥离、不按凭据消费处理
        val endpoint = parser.parse("http://192.168.1.5:8787/root?mode=full|tok").endpoints.single()
        assertEquals("http://192.168.1.5:8787/root?mode=full", endpoint.targetUrl)
        assertEquals("tok", endpoint.credential)
    }

    @Test fun leftSegmentNotAnUrlIsRejectedRedacted() {
        val raw = "not-an-url|secret-token"
        assertFalse(parser.matches(raw))
        val registry = ParserRegistry(delimitedParsers = listOf(ChongYepDelimitedParser()))
        val failure = registry.parse(raw).exceptionOrNull()
        assertTrue(failure is PairingParseException)
        assertFalse(failure!!.message!!.contains("secret-token"))
    }

    @Test fun emptyTokenDoesNotMatchAndDirectParseFails() {
        val raw = "http://192.168.1.5:8787|"
        assertFalse(parser.matches(raw))
        val failure = runCatching { parser.parse(raw) }.exceptionOrNull()
        assertTrue(failure is PairingParseException)
    }

    @Test fun multiSegmentPayloadIsRejectedRedacted() {
        val raw = "http://192.168.1.5:8787|token-one|token-two"
        assertTrue(parser.matches(raw))
        val failure = runCatching { parser.parse(raw) }.exceptionOrNull()
        assertTrue(failure is PairingParseException)
        // 多段载荷的额外段按凭据遮蔽：令牌值不得出现在错误文案中
        assertFalse(failure!!.message!!.contains("token-one"))
        assertFalse(failure.message!!.contains("token-two"))
    }

    @Test fun registryRoutesDelimitedFormToChongYep() {
        val registry = ParserRegistry(
            urlParsers = p0UrlParsers() + listOf(AnonRtParser()),
            schemeParsers = listOf(DshRemoteSchemeParser()),
            delimitedParsers = listOf(ChongYepDelimitedParser()),
        )
        // 含竖线的 http(s) 载荷落分隔文本路由（不进 URL 签名路由）
        assertEquals(
            PayloadForm.DELIMITED_TEXT,
            registry.detectForm("http://192.168.1.5:8787|tok"),
        )
        val pairing = registry.parse("http://192.168.1.5:8787|tok").getOrThrow()
        assertEquals("chongyep-remote", pairing.parserId)
    }
}
