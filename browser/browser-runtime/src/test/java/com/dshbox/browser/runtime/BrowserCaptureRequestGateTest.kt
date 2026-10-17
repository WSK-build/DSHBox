package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCaptureRequestGateTest {
    @Test
    fun oldSuccessAndFailureCannotOverwriteNewSelection() {
        var displayedState = "new-selection"
        fun deliver(request: Long, state: String) {
            if (BrowserCaptureRequestGate.isCurrent(request, 2L, 7L, 7L, true)) {
                displayedState = state
            }
        }
        deliver(1L, "old-bitmap")
        deliver(1L, "old-error")
        assertEquals("new-selection", displayedState)
        deliver(2L, "current-bitmap")
        assertEquals("current-bitmap", displayedState)
    }

    @Test
    fun restartedOrCancelledPickerRejectsOutstandingFrame() {
        assertEquals(false, BrowserCaptureRequestGate.isCurrent(2L, 2L, 7L, 8L, true))
        assertEquals(false, BrowserCaptureRequestGate.isCurrent(2L, 2L, 7L, 7L, false))
    }
}
