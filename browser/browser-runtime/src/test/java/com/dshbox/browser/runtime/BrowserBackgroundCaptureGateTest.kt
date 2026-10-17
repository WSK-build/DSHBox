package com.dshbox.browser.runtime

import org.junit.Assert.*
import org.junit.Test

class BrowserBackgroundCaptureGateTest {
    private fun header(width: Int, height: Int): ByteArray = ByteArray(24).also { bytes ->
        byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).copyInto(bytes)
        fun putInt(offset: Int, value: Int) {
            for (i in 0..3) bytes[offset + i] = (value ushr (24 - 8 * i)).toByte()
        }
        putInt(8, 13)
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(bytes, 12)
        putInt(16, width)
        putInt(20, height)
    }

    @Test fun requiresPngHeaderAndExactViewportDimensions() {
        val png = header(320, 240)
        assertTrue(BrowserBackgroundCaptureGate.validPng(png, 320, 240))
        assertFalse(BrowserBackgroundCaptureGate.validPng(png, 321, 240))
        png[0] = 0
        assertFalse(BrowserBackgroundCaptureGate.validPng(png, 320, 240))
    }

    @Test fun rejectsInvalidAndOversizedImagesBeforeDecode() {
        assertFalse(BrowserBackgroundCaptureGate.validPng(ByteArray(23), 1, 1))
        assertFalse(BrowserBackgroundCaptureGate.validPng(header(1, 1), 0, 1))
        assertFalse(BrowserBackgroundCaptureGate.validPng(header(1, 1), 5000, 5000))
        assertFalse(BrowserBackgroundCaptureGate.validPng(ByteArray(BrowserBackgroundCaptureGate.MAX_PNG_BYTES + 1), 1, 1))
    }
}
