package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserScreenshotEffectAdmissionTest {
    @Test
    fun feedbackBookkeepingKeepsJournalAdmissionWithoutBeingAPageMutation() {
        for (action in listOf("claim", "admit", "receipt", "task_claim", "task_admit", "task_receipt")) {
            val feedback = request(BrowserCommandMethod.SELECTION).copy(args = JSONObject().put("action", action))
            assertTrue(BrowserTaskCoordinator.isFeedbackBookkeeping(feedback))
            assertTrue(BrowserTaskCoordinator.shouldAdmitOperation(feedback))
            assertFalse(BrowserTaskCoordinator.isPotentiallyMutating(feedback))
        }
        for (action in listOf("clear", "consume")) {
            val other = request(BrowserCommandMethod.SELECTION).copy(args = JSONObject().put("action", action))
            assertFalse(BrowserTaskCoordinator.isFeedbackBookkeeping(other))
            assertTrue(BrowserTaskCoordinator.isPotentiallyMutating(other))
        }
    }

    private fun request(method: BrowserCommandMethod) = BrowserCommandRequest(
        "capture_effect", method, 10_000L, 1L, 2L, "ai", null, JSONObject(), "{}",
    )

    @Test
    fun screenshotKeepsReceiptAdmissionWithoutBlockingRecoveryAsAnUnknownWrite() {
        val screenshot = request(BrowserCommandMethod.SCREENSHOT)
        assertTrue(BrowserTaskCoordinator.shouldAdmitOperation(screenshot))
        assertFalse(BrowserTaskCoordinator.isPotentiallyMutating(screenshot))
    }

    @Test
    fun pageWritesKeepTheirUncertainEffectProtection() {
        for (method in listOf(BrowserCommandMethod.CLICK, BrowserCommandMethod.TYPE,
            BrowserCommandMethod.NAVIGATE, BrowserCommandMethod.CLOSE)) {
            assertTrue(BrowserTaskCoordinator.shouldAdmitOperation(request(method)))
            assertTrue(BrowserTaskCoordinator.isPotentiallyMutating(request(method)))
        }
    }
}
