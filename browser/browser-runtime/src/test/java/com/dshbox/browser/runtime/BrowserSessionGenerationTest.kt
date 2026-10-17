package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Locks the shared allocator so recreated sessions never reuse a generation. */
class BrowserSessionGenerationTest {
    @Test fun reservationsAdvanceMonotonically() {
        assertEquals(1024L, BrowserSessionGeneration.reserve(0L))
        assertEquals(2048L, BrowserSessionGeneration.reserve(1024L))
        assertEquals(3072L, BrowserSessionGeneration.reserve(2048L))
    }

    @Test fun overflowIsRejectedNotWrapped() {
        assertNull(BrowserSessionGeneration.reserve(-1L))
        assertNull(BrowserSessionGeneration.reserve(BrowserRuntimeSettings.MAX_SAFE_GENERATION))
        assertNull(BrowserSessionGeneration.reserve(
            BrowserRuntimeSettings.MAX_SAFE_GENERATION - BrowserRuntimeSettings.SESSION_GENERATION_RESERVATION + 1L,
        ))
    }
}
