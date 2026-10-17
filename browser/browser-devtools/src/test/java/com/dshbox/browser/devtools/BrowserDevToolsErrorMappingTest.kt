package com.dshbox.browser.devtools

import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserDevToolsErrorMappingTest {
    @Test fun unavailableTargetAndClosedConnectionKeepDistinctCodes() {
        assertEquals("TARGET_UNAVAILABLE", mapBrowserDevToolsError(IllegalStateException("TARGET_UNAVAILABLE")))
        assertEquals("CLOSED", mapBrowserDevToolsError(EOFException("CDP_CLOSED")))
        assertEquals("CLOSED", mapBrowserDevToolsError(IllegalStateException("request failed", EOFException("CDP_CLOSED"))))
    }
}
