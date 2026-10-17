package com.dshbox.browser.runtime

import com.dshbox.browser.api.ScreenshotQuotaExceededException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BrowserScreenshotWriteFailurePolicyTest {
    @Test fun typedQuotaFailureMapsToExplicitCodeAndStorageStage() {
        val code = BrowserScreenshotWriteFailurePolicy.code(
            ScreenshotQuotaExceededException(),
            fallback = "STORAGE_FAILED",
        )
        assertEquals("SCREENSHOT_QUOTA_EXCEEDED", code)
        assertEquals("storage", BrowserScreenshotWriteFailurePolicy.stage(code))
    }

    @Test fun exceptionTextIsNeverUsedForQuotaClassification() {
        val similarText = RuntimeException("Browser screenshot quota exceeded; retry after 30 seconds")
        val code = BrowserScreenshotWriteFailurePolicy.code(similarText, fallback = "STORAGE_FAILED")
        assertEquals("STORAGE_FAILED", code)
        assertEquals("storage", BrowserScreenshotWriteFailurePolicy.stage(code))
    }

    @Test fun thumbnailQuotaFailureOnlyPublishesCapturedAndCode() {
        val code = BrowserScreenshotWriteFailurePolicy.code(
            ScreenshotQuotaExceededException(),
            fallback = "STORAGE_FAILED",
        )
        val thumbnail = BrowserNavigationThumbnailPolicy.failureData(code)
        assertEquals(setOf("captured", "code"), thumbnail.keys().asSequence().toSet())
        assertEquals("SCREENSHOT_QUOTA_EXCEEDED", thumbnail.getString("code"))
        assertFalse(thumbnail.getBoolean("captured"))
    }
}
