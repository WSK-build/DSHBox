package com.dshbox.browser.runtime

import org.junit.Assert.*
import org.junit.Test

class BrowserVisualPixelDigestTest {
    private fun digest(width: Int = 3, height: Int = 2, changed: Boolean = false) =
        BrowserVisualPixelDigest.fromRows(width, height) { y, row ->
            for (x in row.indices) row[x] = if (changed && y == height - 1 && x == width - 1) 99 else x + y * width
        }
    @Test fun fullFrameDigestIncludesLastPixelAndDimensions() {
        assertEquals(digest(), digest())
        assertNotEquals(digest(), digest(changed = true))
        assertNotEquals(digest(3, 2), digest(2, 3))
        assertTrue(digest()!!.matches(Regex("[a-f0-9]{64}")))
    }
    @Test fun oversizeFramesAreRejectedBeforeAnyPixelAllocationOrRead() {
        var reads = 0
        for ((width, height) in listOf(0 to 1, 4097 to 1, 1 to 8193, 4096 to 8192)) {
            assertNull(BrowserVisualPixelDigest.fromRows(width, height) { _, _ -> reads++ })
        }
        assertEquals(0, reads)
    }
}
