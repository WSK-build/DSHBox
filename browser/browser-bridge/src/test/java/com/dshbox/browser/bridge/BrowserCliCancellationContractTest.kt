package com.dshbox.browser.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserCliCancellationContractTest {
    @Test
    fun receiptAndQueuedTerminalHaveDistinctActionOutcomes() {
        val receipt = BrowserCliCancellationContract.receipt("queued", "cancel_before_admission")
        assertEquals("cancel_request", receipt.getString("receiptKind"))
        assertEquals("unknown", receipt.getString("actionOutcome"))
        assertEquals("read_original_request_result", receipt.getString("nextAction"))
        assertFalse(receipt.getBoolean("automaticRetryAllowed"))

        val terminal = BrowserCliCancellationContract.preDispatchTerminal()
        assertEquals("queued", terminal.getString("cancelStage"))
        assertEquals("cancel_before_dispatch", terminal.getString("reason"))
        assertEquals("not_dispatched", terminal.getString("actionOutcome"))
        assertFalse(terminal.getBoolean("automaticRetryAllowed"))
        assertFalse(terminal.has("receiptKind"))
    }

    @Test
    fun illegalCancellationMetadataIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            BrowserCliCancellationContract.receipt("unknown_stage", "cancel_accepted")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserCliCancellationContract.receipt("transport", "made_up")
        }
    }
}
