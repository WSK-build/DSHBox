package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * saya-ch / dsh-mobile 非 iroh 通道（LAN/公网 HTTPS）配对链接解析测试：
 * fragment 形态、query 形态、缺 token 显式报错；同一解析器不得吞掉 JSON
 * 描述符的既有行为（由 SayaChJsonParserTest 覆盖）。
 */
class SayaChUrlPairingTest {

    private val parser = SayaChJsonParser()

    @Test
    fun fragmentLinkParsesLanOriginAndToken() {
        val raw = "https://192.168.1.20:3443/mobile-access/pair" +
            "#instance=c4aa4290470914d318fadb8cdcf6646bf93785f290ccd92935c15889091244d4" +
            "&token=Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56Qr78St12Uvw3"
        assertTrue(parser.matches(raw))
        val pairing = parser.parse(raw)
        assertEquals(ParserIds.SAYA_CH, pairing.parserId)
        assertEquals("192.168.1.20", pairing.deviceName)
        val endpoint = pairing.endpoints.single()
        assertEquals("https://192.168.1.20:3443", endpoint.targetUrl)
        assertEquals("LAN", endpoint.channelLabel)
        assertEquals("Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56Qr78St12Uvw3", endpoint.credential)
        assertNull(endpoint.caCertificate)
        assertNull(endpoint.sessionMode)
    }

    @Test
    fun queryFormParsesSameWay() {
        val raw = "https://dsh.example.com:8443/mobile-access/pair" +
            "?instance=c4aa4290470914d318fadb8cdcf6646bf93785f290ccd92935c15889091244d4" +
            "&token=Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56Qr78St12Uvw3"
        assertTrue(parser.matches(raw))
        val pairing = parser.parse(raw)
        assertEquals("dsh.example.com", pairing.deviceName)
        val endpoint = pairing.endpoints.single()
        assertEquals("https://dsh.example.com:8443", endpoint.targetUrl)
        assertEquals("PUBLIC", endpoint.channelLabel)
        assertEquals("Ab12Cd34Ef56Gh78Ij90Kl12Mn34Op56Qr78St12Uvw3", endpoint.credential)
    }

    @Test
    fun fragmentWithoutTokenFailsExplicitly() {
        val raw = "https://192.168.1.20:3443/mobile-access/pair" +
            "#instance=c4aa4290470914d318fadb8cdcf6646bf93785f290ccd92935c15889091244d4"
        // matches 不吞载荷（token 缺失不构成可消费的配对链接）
        assertFalse(parser.matches(raw))
        try {
            parser.parse(raw)
            throw AssertionError("expected PairingParseException")
        } catch (error: PairingParseException) {
            assertTrue(error.message!!.contains("token"))
        }
    }

    @Test
    fun nonPairPathDoesNotMatch() {
        assertFalse(parser.matches("https://192.168.1.20:3443/other#token=abc"))
        assertFalse(parser.matches("http://192.168.1.20:3443/mobile-access/pair#token=abc"))
    }

    @Test
    fun tokenWithLineBreakIsRejected() {
        // token 复用 JSON 描述符的 base64url 白名单：换行的百分号编码（%0A，
        // 复制/转义引入）在白名单处显式拒绝，值不进文案
        val encoded = "https://192.168.1.20:3443/mobile-access/pair?token=abc%0Adef"
        val failure = runCatching { parser.parse(encoded) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        val message = failure.exceptionOrNull()!!.message!!
        assertTrue(message.contains("illegal characters"))
        assertFalse(message.contains("abc"))
        // 字面 CR/LF（URI 非法字符）：同样以 PairingParseException 拒绝
        // （不会以静默通过/存档半写收场）
        val literal = "https://192.168.1.20:3443/mobile-access/pair?token=abc\ndef"
        val literalFailure = runCatching { parser.parse(literal) }
        assertTrue(literalFailure.exceptionOrNull() is PairingParseException)
    }

    @Test
    fun irohUrlPairingLinkIsRejectedExplicitly() {
        val payload = "https://abc.def.iroh.localhost:25443/mobile-access/pair#instance=AA&token=abc"
        val failure = runCatching { parser.parse(payload) }
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("iroh"))
    }
}
