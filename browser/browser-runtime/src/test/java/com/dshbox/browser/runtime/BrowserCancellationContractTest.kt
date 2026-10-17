package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCancellationContractTest {
    @Test
    fun receiptSeparatesCancelAcknowledgementFromActionOutcome() {
        val receipt = BrowserCancellationContract.receipt("condition_wait", "cancel_accepted")
        assertEquals(
            setOf(
                "receiptKind", "cancelStage", "reason", "actionOutcome",
                "nextAction", "automaticRetryAllowed",
            ),
            receipt.keys().asSequence().toSet(),
        )
        assertEquals("cancel_request", receipt.getString("receiptKind"))
        assertEquals("condition_wait", receipt.getString("cancelStage"))
        assertEquals("unknown", receipt.getString("actionOutcome"))
        assertEquals("read_original_request_result", receipt.getString("nextAction"))
        assertFalse(receipt.getBoolean("automaticRetryAllowed"))
    }

    @Test
    fun illegalStagesAndReasonsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            BrowserCancellationContract.receipt("action_dispatch", "cancel_accepted")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserCancellationContract.receipt("dispatch", "made_up")
        }
        assertTrue(BrowserCancellationContract.stages.contains("queued"))
        assertTrue(BrowserCancellationContract.reasons.contains("transport_unavailable"))
    }

    @Test
    fun receiptShapeRejectsMutableOrMissingCompatibilityFields() {
        val receipt = BrowserCancellationContract.receipt("event_wait", "cancel_accepted")
        receipt.remove("automaticRetryAllowed")
        assertEquals(5, receipt.keys().asSequence().count())
        assertThrows(IllegalArgumentException::class.java) {
            BrowserCancellationContract.receipt(JSONObject.NULL.toString(), "cancel_accepted")
        }
    }
}
