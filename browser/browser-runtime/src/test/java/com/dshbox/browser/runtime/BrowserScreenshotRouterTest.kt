package com.dshbox.browser.runtime

import org.junit.Assert.*
import org.junit.Test

internal class BrowserScreenshotRouterTest {
    @Test fun switchingVisibilitySelectsExactlyOneBackendAndRetainsItsCancellation() {
        var visible = true
        val calls = mutableListOf<String>()
        val cancellations = mutableListOf<String>()
        fun source(name: String) = BrowserScreenshotCaptureSource<String> { deadline, callback ->
            assertEquals(123L, deadline)
            calls += name
            callback(BrowserScreenshotCaptureResult(name, "OK"))
            BrowserScreenshotCaptureHandle { cancellations += name; true }
        }
        val router = BrowserScreenshotRouter({ visible }, source("native"), source("background"))
        val results = mutableListOf<String?>()
        assertTrue(router.capture(123) { results += it.value }.cancel())
        assertEquals(listOf("native"), calls)
        visible = false
        assertTrue(router.capture(123) { results += it.value }.cancel())
        assertEquals(listOf("native", "background"), calls)
        assertEquals(calls, results)
        assertEquals(calls, cancellations)
    }
}
