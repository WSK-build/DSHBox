package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对载荷空白包裹处理测试：二维码内容带首尾空白/换行包裹时与净载荷等效
 * （扫码命中与手动粘贴同一入口）。
 */
class PayloadTrimTest {

    private val registry = ParserRegistry(p0UrlParsers())

    @Test fun whitespaceWrappedPocketUrlParsesLikeTrimmed() {
        val raw = "http://192.168.1.5:3081"
        val wrapped = "\n  \t$raw\r\n  "
        val pairing = registry.parse(wrapped).getOrThrow()
        assertEquals("dsh-pocket", pairing.parserId)
        assertEquals("http://192.168.1.5:3081/", pairing.endpoints.single().targetUrl)
    }

    @Test fun whitespaceWrappedBridgeUrlParsesLikeTrimmed() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef"
        val wrapped = "\nhttp://192.168.1.6:3082/?auth=$token\n"
        val pairing = registry.parse(wrapped).getOrThrow()
        assertEquals("dsh-bridge", pairing.parserId)
        assertEquals(token, pairing.endpoints.single().credential)
    }

    @Test fun whitespaceWrappedGatewayUrlParsesLikeTrimmed() {
        val raw = "https://gw.trycloudflare.com/pair#s3cret"
        val pairing = registry.parse("   \n$raw\t ").getOrThrow()
        assertEquals("dsh-remote-web-gateway", pairing.parserId)
        // 建档地址剥离凭据；完整 URL 入存档
        assertEquals("https://gw.trycloudflare.com/pair", pairing.endpoints.single().targetUrl)
        assertEquals(raw, pairing.endpoints.single().credential)
    }

    @Test fun embeddedWhitespaceOnlyPayloadIsRejected() {
        // 纯空白载荷：显式失败（不产出空地址条目）
        val failure = registry.parse(" \n\t ").exceptionOrNull()
        assertTrue(failure is PairingParseException)
    }
}
