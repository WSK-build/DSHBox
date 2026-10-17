package com.dshbox.browser.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUploadActivationIsolationTest {
    @Test
    fun timeoutOrCancellationBlocksOnlyItsViewUntilLateChooserIsConsumed() {
        val isolation = BrowserUploadActivationIsolation<String, String>()
        isolation.markUnresolved("old-view", "page-a")

        // Both timeout and cancellation retain the same per-view quarantine until a late callback arrives.
        assertTrue(isolation.isUnresolved("old-view"))
        assertFalse(isolation.isUnresolved("new-view"))
        assertTrue(isolation.consumeLateChooser("old-view"))
        assertFalse(isolation.isUnresolved("old-view"))
        assertFalse(isolation.consumeLateChooser("old-view"))
    }

    @Test
    fun destroyedViewCanReleaseItsUnresolvedGesture() {
        val isolation = BrowserUploadActivationIsolation<String, String>()
        isolation.markUnresolved("view", "page")
        isolation.release("view")
        assertFalse(isolation.isUnresolved("view"))
    }
}
