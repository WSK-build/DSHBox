package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

class BrowserTaskFeedbackDigestTest {
    @Test
    fun digestUsesExactUtf8TextBytesWithoutJsonEscapingOrUnicodeNormalization() {
        val text = "任务: 😀\u00a0\u2028"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals(17, bytes.size)
        assertEquals("f886fe771f1bc08f01dca4b77c034329761c4b76c8df069af7c22806b1b0a6e3", digest)
    }
}
