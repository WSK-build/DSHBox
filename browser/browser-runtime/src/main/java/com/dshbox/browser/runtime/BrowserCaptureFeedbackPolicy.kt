package com.dshbox.browser.runtime

internal enum class BrowserCaptureFailure {
    PAGE_NOT_READY,
    TARGET_CHANGED,
    SAVE_FAILED,
    IMAGE_FAILED,
    FRAME_MISSING,
    UNKNOWN,
}

internal enum class BrowserAdvancedFailureHint {
    NO_PAGE,
    CONNECTION_UNAVAILABLE,
    NONE,
}

/** Maps runtime failure codes to concise Browser UI explanations without changing capture behavior. */
internal object BrowserCaptureFeedbackPolicy {
    fun captureFailure(code: String?): BrowserCaptureFailure = when (code) {
        "BACKGROUND_UNAVAILABLE", "DEADLINE_EXPIRED" -> BrowserCaptureFailure.PAGE_NOT_READY
        "STALE_PROVENANCE", "ELEMENT_CHANGED_DURING_CAPTURE" -> BrowserCaptureFailure.TARGET_CHANGED
        "STORAGE_FAILED", "PERSIST_FAILED" -> BrowserCaptureFailure.SAVE_FAILED
        "CAPTURE_FAILED" -> BrowserCaptureFailure.IMAGE_FAILED
        "NO_FRAME" -> BrowserCaptureFailure.FRAME_MISSING
        else -> BrowserCaptureFailure.UNKNOWN
    }

    fun advancedFailureHint(action: String, code: String): BrowserAdvancedFailureHint = when {
        code == "TARGET_UNAVAILABLE" && action in PAGE_INSPECTION_ACTIONS -> BrowserAdvancedFailureHint.NO_PAGE
        code == "CLOSED" -> BrowserAdvancedFailureHint.CONNECTION_UNAVAILABLE
        else -> BrowserAdvancedFailureHint.NONE
    }

    private val PAGE_INSPECTION_ACTIONS = setOf("console", "dom", "evaluate", "emulate")
}
