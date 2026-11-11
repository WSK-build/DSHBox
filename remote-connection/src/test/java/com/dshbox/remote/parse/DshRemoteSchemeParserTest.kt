package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dsh-Remote scheme 解析器测试：多 server 拆分为多端点、通道标签按主机形态
 * 判定、令牌为各端点共用凭据、畸形载荷脱敏报错、scheme 大小写不敏感。
 */
class DshRemoteSchemeParserTest {

    private val parser = DshRemoteSchemeParser()

    @Test fun multiServerPayloadSplitsIntoEndpoints() {
        val raw = "dshremote://pair?token=shared-token&server=192.168.1.5:8787&server=gw.example.com:8787"
        val pairing = parser.parse(raw)
        assertEquals("dsh-remote-scheme", pairing.parserId)
        assertEquals(2, pairing.endpoints.size)
        assertEquals("192.168.1.5", pairing.deviceName)
        assertEquals("http://192.168.1.5:8787/", pairing.endpoints[0].targetUrl)
        assertEquals("http://gw.example.com:8787/", pairing.endpoints[1].targetUrl)
    }

    @Test fun tokenIsSharedCredentialOnEveryEndpoint() {
        val raw = "dshremote://pair?token=shared-token&server=192.168.1.5:8787&server=10.0.0.8:8787"
        val endpoints = parser.parse(raw).endpoints
        assertTrue(endpoints.all { it.credential == "shared-token" })
    }

    @Test fun channelLabelFollowsServerHostForm() {
        val raw = "dshremote://pair?token=t&server=192.168.1.5:8787&server=gw.example.com:8787" +
            "&server=quick-tunnel.trycloudflare.com:8443"
        val endpoints = parser.parse(raw).endpoints
        assertEquals("LAN", endpoints[0].channelLabel)
        assertEquals("PUBLIC", endpoints[1].channelLabel)
        assertEquals("TUNNEL", endpoints[2].channelLabel)
    }

    @Test fun payloadWithoutServerDoesNotMatchAndFailsRedacted() {
        val raw = "dshremote://pair?token=secret-token"
        assertFalse(parser.matches(raw))
        val failure = runCatching { parser.parse(raw) }.exceptionOrNull()
        assertTrue(failure is PairingParseException)
        assertFalse(failure!!.message!!.contains("secret-token"))
    }

    @Test fun payloadWithoutTokenDoesNotMatchAndFailsRedacted() {
        val raw = "dshremote://pair?server=192.168.1.5:8787"
        assertFalse(parser.matches(raw))
        val failure = runCatching { parser.parse(raw) }.exceptionOrNull()
        assertTrue(failure is PairingParseException)
    }

    @Test fun malformedServerFailsRedacted() {
        // 整段 URI 解析失败（server 含空格）：报错文案中令牌被遮蔽
        val raw = "dshremote://pair?token=secrettoken&server=bad host:8787"
        val failure = runCatching { parser.parse(raw) }.exceptionOrNull()
        assertTrue(failure is PairingParseException)
        assertFalse(failure!!.message!!.contains("secrettoken"))

        // 可解析但无主机的 server（"/path-only"）：显式报错而非产出 http://null 目标
        val hostless = "dshremote://pair?token=t&server=/path-only"
        val hostlessFailure = runCatching { parser.parse(hostless) }.exceptionOrNull()
        assertTrue(hostlessFailure is PairingParseException)
        assertFalse(hostlessFailure!!.message!!.contains("http://null"))
    }

    @Test fun schemeIsCaseInsensitive() {
        val raw = "DSHREMOTE://pair?token=tok&server=192.168.1.5:8787"
        assertTrue(parser.matches(raw))
        assertEquals("dsh-remote-scheme", parser.parse(raw).parserId)
    }

    @Test fun otherSchemesAreNotClaimed() {
        assertFalse(parser.matches("dshbox://pair?token=t&server=192.168.1.5:8787"))
        assertFalse(parser.matches("http://192.168.1.5:8787"))
    }

    @Test fun nonPairActionIsNotClaimed() {
        // scheme 动作校验：authority 必须是配对动作（dshremote://任意?… 不认领）
        assertFalse(parser.matches("dshremote://other?token=t&server=192.168.1.5:8787"))
        assertFalse(parser.matches("dshremote://web?token=t&server=192.168.1.5:8787"))
        val failure = runCatching { parser.parse("dshremote://other?token=t&server=192.168.1.5:8787") }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
    }

    @Test fun registryRoutesSchemeFormToDshRemoteScheme() {
        val registry = ParserRegistry(
            urlParsers = p0UrlParsers() + listOf(AnonRtParser()),
            schemeParsers = listOf(DshRemoteSchemeParser()),
            delimitedParsers = listOf(ChongYepDelimitedParser()),
        )
        val pairing = registry
            .parse("dshremote://pair?token=tok&server=192.168.1.5:8787")
            .getOrThrow()
        assertEquals("dsh-remote-scheme", pairing.parserId)
    }
}
