package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关/邀请/bridge/pocket 解析器与两级路由测试：签名正例/负例、畸形载荷、存档地址的
 * 凭据剥离与错误文案脱敏。
 */
class P0ParserTest {

    // ---- dsh-bridge ----

    @Test fun bridgeUrlWith48HexAuthIsParsed() {
        val raw = "http://192.168.1.5:3082/?auth=" + "a".repeat(48)
        val pairing = DshBridgeParser().parse(raw)
        assertEquals("dsh-bridge", pairing.parserId)
        assertEquals("192.168.1.5", pairing.deviceName)
        val endpoint = pairing.endpoints.single()
        // 凭据只入存档：目标地址剥离 auth 参数
        assertEquals("http://192.168.1.5:3082/", endpoint.targetUrl)
        assertEquals("a".repeat(48), endpoint.credential)
        assertEquals("LAN", endpoint.channelLabel)
    }

    @Test fun bridgeUrlWithTunnelHostIsLabeledAsTunnel() {
        val raw = "https://random-bridge.trycloudflare.com/?auth=" + "b".repeat(48)
        val endpoint = DshBridgeParser().parse(raw).endpoints.single()
        assertEquals("TUNNEL", endpoint.channelLabel)
        assertEquals("https://random-bridge.trycloudflare.com/", endpoint.targetUrl)
    }

    @Test fun bridgeUrlWithShortTokenDoesNotMatch() {
        val raw = "http://192.168.1.5:3082/?auth=abc123"
        assertFalse(DshBridgeParser().matches(raw))
        // 签名不命中：落通用 URL 通道
        val registry = ParserRegistry(p0UrlParsers())
        val pairing = registry.parse(raw).getOrThrow()
        assertEquals("generic-url", pairing.parserId)
    }

    // ---- dsh-full-remote ----

    @Test fun fullRemoteInviteUrlIsParsed() {
        val raw = "https://gw.example.com/_dsh_reverse_proxy/login?invite=Ab12Cd34"
        val pairing = DshFullRemoteParser().parse(raw)
        assertEquals("dsh-full-remote", pairing.parserId)
        val endpoint = pairing.endpoints.single()
        assertEquals("https://gw.example.com/_dsh_reverse_proxy/login", endpoint.targetUrl)
        // 完整邀请 URL（含 invite）整体作为凭据入加密存档
        assertEquals(raw, endpoint.credential)
        assertEquals("PUBLIC", endpoint.channelLabel)
    }

    @Test fun fullRemoteLoginPathWithoutInviteDoesNotMatch() {
        assertFalse(DshFullRemoteParser().matches("https://gw.example.com/_dsh_reverse_proxy/login"))
        assertFalse(DshFullRemoteParser().matches("https://gw.example.com/other?invite=x"))
    }

    // ---- dsh-remote-web-gateway ----

    @Test fun gatewayPairUrlWithFragmentIsParsed() {
        val raw = "https://random-gw.trycloudflare.com/pair#s3cret"
        val pairing = DshRemoteWebGatewayParser().parse(raw)
        assertEquals("dsh-remote-web-gateway", pairing.parserId)
        val endpoint = pairing.endpoints.single()
        assertEquals("https://random-gw.trycloudflare.com/pair", endpoint.targetUrl)
        assertEquals(raw, endpoint.credential)
        assertEquals("TUNNEL", endpoint.channelLabel)
    }

    @Test fun gatewayPairPathWithoutFragmentDoesNotMatch() {
        assertFalse(DshRemoteWebGatewayParser().matches("https://random-gw.trycloudflare.com/pair"))
        // query 携带凭据被上游一律拒绝：不是该插件的合法形态
        assertFalse(DshRemoteWebGatewayParser().matches("https://random-gw.trycloudflare.com/pair?secret=x"))
    }

    // ---- dsh-pocket ----

    @Test fun pocketLanUrlIsParsed() {
        val pairing = DshPocketParser().parse("http://192.168.1.5:3081")
        assertEquals("dsh-pocket", pairing.parserId)
        val endpoint = pairing.endpoints.single()
        assertEquals("http://192.168.1.5:3081/", endpoint.targetUrl)
        assertNull(endpoint.credential)
        assertEquals("LAN", endpoint.channelLabel)
    }

    @Test fun pocketTunnelUrlIsParsed() {
        val endpoint = DshPocketParser().parse("https://pocket-quick.trycloudflare.com").endpoints.single()
        assertEquals("https://pocket-quick.trycloudflare.com/", endpoint.targetUrl)
        assertEquals("TUNNEL", endpoint.channelLabel)
        assertEquals("pocket-quick.trycloudflare.com", DshPocketParser().parse("https://pocket-quick.trycloudflare.com").deviceName)
    }

    @Test fun pocketParserDoesNotClaimOtherUrls() {
        assertFalse(DshPocketParser().matches("http://192.168.1.5:8080"))
        assertFalse(DshPocketParser().matches("https://gw.example.com"))
        // 带 query/fragment 的同特征 URL 是一次性票/配对 secret 形态：不认领
        assertFalse(DshPocketParser().matches("http://192.168.1.5:3081?x=1"))
        assertFalse(DshPocketParser().matches("http://192.168.1.5:3081#frag"))
        assertFalse(DshPocketParser().matches("https://quick.trycloudflare.com?ticket=one-time"))
    }

    // ---- 注册表两级路由 ----

    @Test fun registryRoutesEachSignatureToItsParser() {
        val registry = ParserRegistry(p0UrlParsers())
        assertEquals(
            "dsh-remote-web-gateway",
            registry.parse("https://gw.trycloudflare.com/pair#abc").getOrThrow().parserId,
        )
        assertEquals(
            "dsh-full-remote",
            registry.parse("https://gw.example.com/_dsh_reverse_proxy/login?invite=x").getOrThrow().parserId,
        )
        assertEquals(
            "dsh-bridge",
            registry.parse("http://192.168.1.5:3082/?auth=${"c".repeat(48)}").getOrThrow().parserId,
        )
        assertEquals(
            "dsh-pocket",
            registry.parse("http://192.168.1.5:3081").getOrThrow().parserId,
        )
    }

    @Test fun unsignedBareUrlFallsBackToGenericChannel() {
        val registry = ParserRegistry(p0UrlParsers())
        val pairing = registry.parse("http://192.168.1.9:9000").getOrThrow()
        assertEquals("generic-url", pairing.parserId)
    }

    @Test fun nonHttpPayloadIsRejectedWithRedactedMessage() {
        val registry = ParserRegistry(p0UrlParsers())
        val failure = registry.parse("dshremote://pair?token=super-secret-token").exceptionOrNull()
        assertTrue(failure is PairingParseException)
        // 错误文案脱敏：token 键值被遮蔽，且不出现原始值
        assertFalse(failure!!.message!!.contains("super-secret-token"))
    }

    @Test fun malformedGatewayUrlErrorIsRedacted() {
        val failure = DshRemoteWebGatewayParser()
            .runCatching { parse("https://gw.trycloudflare.com/pair") }
            .exceptionOrNull()
        assertTrue(failure is PairingParseException)
        assertTrue(failure!!.message!!.contains("https://gw.trycloudflare.com/pair"))
    }

}
