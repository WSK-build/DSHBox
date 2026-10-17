package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUploadApprovalPresentationPolicyTest {
    @Test fun focusedVisibleActivityShowsInlineDialog() {
        assertEquals(
            BrowserUploadApprovalPresentation.INLINE_DIALOG,
            BrowserUploadApprovalPresentationPolicy.select(activityVisible = true, windowFocused = true),
        )
    }

    @Test fun backgroundedActivityUsesNoticeWithoutForeground() {
        assertEquals(
            BrowserUploadApprovalPresentation.BACKGROUND_NOTICE,
            BrowserUploadApprovalPresentationPolicy.select(activityVisible = true, windowFocused = false),
        )
        assertEquals(
            BrowserUploadApprovalPresentation.BACKGROUND_NOTICE,
            BrowserUploadApprovalPresentationPolicy.select(activityVisible = false, windowFocused = false),
        )
    }

    @Test fun unfocusedVisibleActivityNeverForcesDialog() {
        assertEquals(
            BrowserUploadApprovalPresentation.BACKGROUND_NOTICE,
            BrowserUploadApprovalPresentationPolicy.select(activityVisible = false, windowFocused = true),
        )
    }
}
