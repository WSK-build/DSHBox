package com.dshbox.browser.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaptureEventWindowTest {
    @Test
    fun restartingOnSameConnectionExcludesAlreadyReceivedQueuedEvents() {
        val queuedBeforeRestart = listOf(9L, 10L)
        val receivedDuringEnable = listOf(11L, 12L)
        val captureBoundary = 10L
        val admitted = (queuedBeforeRestart + receivedDuringEnable)
            .filter { isCurrentCaptureEvent(true, it, captureBoundary) }
        assertEquals(receivedDuringEnable, admitted)
    }

    @Test
    fun revocationRejectsNewEventsRegardlessOfTheirSequence() {
        assertFalse(isCurrentCaptureEvent(false, 101L, 100L))
        assertFalse(isCurrentCaptureEvent(false, Long.MAX_VALUE, 0L))
    }
}
