package com.dshbox.browser.bridge

import org.junit.Assert.*
import org.junit.Test

class BrowserCliEarlyCancellationTest {
    private val run = BrowserCliRunIdentity(1, "first-token")
    @Test fun cancelReadBeforeRequestAdmissionPreventsLaterDispatch() {
        var now = 100L
        val ledger = BrowserCliEarlyCancellation({ now })
        val key = BrowserCliRequestKey(run, "request")
        assertTrue(ledger.remember(key))
        now += 30_000 // Authentication/intake completes after cancel.
        var dispatched = false
        if (!ledger.contains(key)) dispatched = true
        assertFalse(dispatched)
        assertFalse(ledger.contains(BrowserCliRequestKey(BrowserCliRunIdentity(2, "second-token"), "request")))
    }
    @Test fun saturationRejectsCancellationWithoutEvictingEarlierAcknowledgedCancel() {
        var now = 0L
        val ledger = BrowserCliEarlyCancellation({ now }, capacity = 1)
        val first = BrowserCliRequestKey(run, "first")
        val other = BrowserCliRequestKey(run, "other")
        assertTrue(ledger.remember(first))
        assertFalse(ledger.remember(other))
        assertTrue(ledger.contains(first))
        now = 90_000
        assertFalse(ledger.contains(first))
        assertTrue(ledger.remember(other))
        ledger.finishRun(run)
        assertFalse(ledger.contains(other))
    }

    @Test fun finishedButStillRegisteredRequestDoesNotLeaveContradictoryCancellation() {
        val ledger = BrowserCliEarlyCancellation({ 100L })
        val key = BrowserCliRequestKey(run, "finishing")
        val active = BrowserCliActiveRequest(run, "fingerprint", java.util.concurrent.CompletableFuture())
        assertTrue(active.beginDispatch())
        active.finish() // Registry removal has not happened yet.
        var disposition: BrowserCliCancelDisposition? = null
        assertFalse(ledger.rememberWhen(key) {
            disposition = active.requestCancel()
            disposition != BrowserCliCancelDisposition.FINISHED
        })
        assertEquals(BrowserCliCancelDisposition.FINISHED, disposition)
        assertFalse(ledger.contains(key))
        assertTrue(ledger.remember(key))
        assertFalse(ledger.rememberWhen(key) { false })
        assertTrue(ledger.contains(key)) // A prior acknowledged cancel must not be discarded.
    }
}
