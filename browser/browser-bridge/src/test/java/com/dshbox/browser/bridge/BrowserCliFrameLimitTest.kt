package com.dshbox.browser.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class BrowserCliFrameLimitTest {
    @Test
    fun requestReaderAcceptsAtMostSixteenKibibytes() {
        val exactRequest = "x".repeat(BrowserCliBroker.MAX_REQUEST_FRAME_BYTES)
        assertEquals(exactRequest, readBoundedLine((exactRequest + "\n").byteInputStream(), BrowserCliBroker.MAX_REQUEST_FRAME_BYTES))

        val oversizedRequest = "x".repeat(BrowserCliBroker.MAX_REQUEST_FRAME_BYTES + 1)
        assertNull(readBoundedLine(ByteArrayInputStream((oversizedRequest + "\n").toByteArray()), BrowserCliBroker.MAX_REQUEST_FRAME_BYTES))

        val carriageReturnPadding = "\r".repeat(BrowserCliBroker.MAX_REQUEST_FRAME_BYTES + 1)
        assertNull(readBoundedLine(ByteArrayInputStream((carriageReturnPadding + "\n").toByteArray()), BrowserCliBroker.MAX_REQUEST_FRAME_BYTES))
    }
}
