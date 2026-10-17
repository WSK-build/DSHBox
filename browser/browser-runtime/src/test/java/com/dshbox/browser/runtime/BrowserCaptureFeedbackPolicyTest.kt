package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserCaptureFeedbackPolicyTest {
    @Test fun captureCodesKeepFailureStageSpecific() {
        assertEquals(BrowserCaptureFailure.PAGE_NOT_READY, BrowserCaptureFeedbackPolicy.captureFailure("BACKGROUND_UNAVAILABLE"))
        assertEquals(BrowserCaptureFailure.TARGET_CHANGED, BrowserCaptureFeedbackPolicy.captureFailure("STALE_PROVENANCE"))
        assertEquals(BrowserCaptureFailure.SAVE_FAILED, BrowserCaptureFeedbackPolicy.captureFailure("PERSIST_FAILED"))
        assertEquals(BrowserCaptureFailure.IMAGE_FAILED, BrowserCaptureFeedbackPolicy.captureFailure("CAPTURE_FAILED"))
        assertEquals(BrowserCaptureFailure.FRAME_MISSING, BrowserCaptureFeedbackPolicy.captureFailure("NO_FRAME"))
        assertEquals(BrowserCaptureFailure.UNKNOWN, BrowserCaptureFeedbackPolicy.captureFailure("UNEXPECTED"))
    }

    @Test fun onlyPageInspectionFailuresReceiveNoPageHint() {
        assertEquals(BrowserAdvancedFailureHint.NO_PAGE, BrowserCaptureFeedbackPolicy.advancedFailureHint("dom", "TARGET_UNAVAILABLE"))
        assertEquals(BrowserAdvancedFailureHint.NONE, BrowserCaptureFeedbackPolicy.advancedFailureHint("status", "TARGET_UNAVAILABLE"))
        assertEquals(BrowserAdvancedFailureHint.CONNECTION_UNAVAILABLE, BrowserCaptureFeedbackPolicy.advancedFailureHint("status", "CLOSED"))
        assertEquals(BrowserAdvancedFailureHint.NONE, BrowserCaptureFeedbackPolicy.advancedFailureHint("console", "TIMEOUT"))
    }
}
