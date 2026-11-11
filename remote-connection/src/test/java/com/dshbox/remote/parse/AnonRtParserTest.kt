package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * anonRTtty 配对解析器测试：`/pair?ticket=` 签名正例、无 ticket 负例、与
 * gateway `/pair#secret` 签名互斥不冲突、注册表路由归属。
 */
class AnonRtParserTest {

    private val parser = AnonRtParser()

    @Test fun pairTicketUrlIsParsed() {
        val raw = "http://192.168.1.9:8899/pair?ticket=one-time"
        val pairing = parser.parse(raw)
        assertEquals("anonrt-remote-mode", pairing.parserId)
        assertEquals("192.168.1.9", pairing.deviceName)
        val endpoint = pairing.endpoints.single()
        // 清单只存剥离 ticket 的存档地址（保留 /pair 应用入口路径）
        assertEquals("http://192.168.1.9:8899/pair", endpoint.targetUrl)
        // 完整配对 URL 整体作为凭据入加密存档
        assertEquals(raw, endpoint.credential)
        assertEquals("LAN", endpoint.channelLabel)
    }

    @Test fun httpsPairUrlIsLabeledPublic() {
        val endpoint = parser.parse("https://relay.example.com/pair?ticket=t").endpoints.single()
        assertEquals("PUBLIC", endpoint.channelLabel)
        assertEquals("https://relay.example.com/pair", endpoint.targetUrl)
    }

    @Test fun pairPathWithoutTicketDoesNotMatch() {
        assertFalse(parser.matches("http://192.168.1.9:8899/pair"))
        assertFalse(parser.matches("http://192.168.1.9:8899/pair?other=1"))
        assertFalse(parser.matches("http://192.168.1.9:8899/other?ticket=t"))
    }

    @Test fun gatewayFragmentFormIsNotClaimed() {
        // /pair + fragment 是 gateway 的签名：本解析器不认领（两签名互斥）
        assertFalse(parser.matches("https://gw.trycloudflare.com/pair#s3cret"))
    }

    @Test fun registryRoutesPairTicketToAnonRtAndFragmentToGateway() {
        val registry = ParserRegistry(
            urlParsers = p0UrlParsers() + listOf(AnonRtParser()),
            schemeParsers = listOf(DshRemoteSchemeParser()),
            delimitedParsers = listOf(ChongYepDelimitedParser()),
        )
        assertEquals(
            "anonrt-remote-mode",
            registry.parse("http://192.168.1.9:8899/pair?ticket=one-time").getOrThrow().parserId,
        )
        // gateway 形态不受新签名影响：注册顺序在前，fragment 先被认领
        assertEquals(
            "dsh-remote-web-gateway",
            registry.parse("https://gw.trycloudflare.com/pair#s3cret").getOrThrow().parserId,
        )
        // 无签名的其它 URL 仍落通用通道
        assertEquals(
            "generic-url",
            registry.parse("http://192.168.1.9:8899/pair").getOrThrow().parserId,
        )
    }
}
