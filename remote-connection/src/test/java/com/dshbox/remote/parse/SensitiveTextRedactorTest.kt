package com.dshbox.remote.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveTextRedactorTest {

    @Test fun redactsUrlQuerySecrets() {
        val redacted = SensitiveTextRedactor.redact("http://pc:3082/?auth=abc123&next=/room")
        assertEquals("http://pc:3082/?auth=***&next=/room", redacted)
    }

    @Test fun redactsAllKnownSecretKeysCaseInsensitively() {
        val redacted = SensitiveTextRedactor.redact(
            "a?Token=x&SECRET=y&Ticket=z&Code=w&password=p&key=k&pin=1234",
        )
        assertFalse(redacted.contains("1234"))
        assertTrue(redacted.contains("Token=***"))
        assertTrue(redacted.contains("SECRET=***"))
        assertTrue(redacted.contains("pin=***"))
    }

    @Test fun leavesInnocentKeysAlone() {
        val redacted = SensitiveTextRedactor.redact("http://pc:3081/?room=lobby&user=alice")
        assertEquals("http://pc:3081/?room=lobby&user=alice", redacted)
    }

    @Test fun redactsJsonSecrets() {
        val redacted = SensitiveTextRedactor.redact("""{"type":"pair","secret":"topsecret","name":"pc"}""")
        assertFalse(redacted.contains("topsecret"))
        assertTrue(redacted.contains("name"))
        assertTrue(redacted.contains(""""secret":"***""""))
    }

    @Test fun redactsPairingDescriptorMemberValues() {
        // 配对描述符成员：gatewayToken/irohCode/caCertificate 按值遮蔽
        val redacted = SensitiveTextRedactor.redact(
            """{"type":"dsh-mobile-iroh-pairing","gatewayToken":"GWTOKENV","""" +
                """"irohCode":"482913","caCertificate":"MIIBTjCB"}""",
        )
        assertFalse(redacted.contains("GWTOKENV"))
        assertFalse(redacted.contains("482913"))
        assertFalse(redacted.contains("MIIBTjCB"))
        assertTrue(redacted.contains("gatewayToken"))
        assertTrue(redacted.contains("type"))
    }

    @Test fun redactsUrlFragment() {
        val redacted = SensitiveTextRedactor.redact("http://pc:8443/pair#oneshotticket")
        assertEquals("http://pc:8443/pair#***", redacted)
    }

    @Test fun redactsUrlUserInfo() {
        // userinfo（user:pass）整体遮蔽，主机与路径保持可读
        val redacted = SensitiveTextRedactor.redact("http://admin:s3cret@192.168.1.5:3082/room")
        assertEquals("http://***@192.168.1.5:3082/room", redacted)
        assertFalse(redacted.contains("admin"))
        assertFalse(redacted.contains("s3cret"))
    }

    @Test fun userInfoContainingAtSignInPasswordIsFullyMasked() {
        // 口令本身含 @：匹配终止于 host 前最后一个 @，不残留凭据片段
        val redacted = SensitiveTextRedactor.redact("http://admin:p@ss@host/path")
        assertEquals("http://***@host/path", redacted)
        assertFalse(redacted.contains("p@ss"))
    }

    @Test fun atSignInQueryIsNotMistakenForUserInfo() {
        // 无 userinfo 的 URL：查询串里的 @ 不触发脱敏
        val redacted = SensitiveTextRedactor.redact("http://pc:3082/?mail=a@b.com")
        assertEquals("http://pc:3082/?mail=a@b.com", redacted)
    }

    @Test fun dshremotePairPayloadIsFullyRedacted() {
        val redacted = SensitiveTextRedactor.redactAndTruncate("dshremote://pair?token=deadbeef01")
        assertFalse(redacted.contains("deadbeef01"))
        assertTrue(redacted.contains("token=***"))
    }

    @Test fun truncateOnlyHappensAfterRedaction() {
        val raw = "dshremote://pair?token=" + "a".repeat(400)
        val display = SensitiveTextRedactor.redactAndTruncate(raw)
        assertTrue(display.length <= 210)
        assertFalse(display.contains("aaaaaaaaaa"))
        assertTrue(display.startsWith("dshremote://pair?token=***…") || display.endsWith("***"))
    }

    @Test fun delimitedTextTailIsMaskedAsCredential() {
        // 竖线分隔文本（地址|令牌）：令牌不带键名，首个竖线之后整段遮蔽
        val redacted = SensitiveTextRedactor.redact("http://192.168.1.5:8787|bare-token-value")
        assertEquals("http://192.168.1.5:8787|***", redacted)
        // 地址段自身的键值凭据仍按规则遮蔽（不因竖线规则提前返回）
        val mixed = SensitiveTextRedactor.redact("http://pc:3082/?auth=abc123|bare-token")
        assertFalse(mixed.contains("abc123"))
        assertFalse(mixed.contains("bare-token"))
    }

    @Test fun multilineDelimitedTailIsFullyMasked() {
        // 竖线后的换行内容同样属凭据段：跨行不残留
        val redacted = SensitiveTextRedactor.redact("http://host|secret\nmore-secret")
        assertFalse(redacted.contains("secret"))
        assertEquals("http://host|***", redacted)
    }
}
