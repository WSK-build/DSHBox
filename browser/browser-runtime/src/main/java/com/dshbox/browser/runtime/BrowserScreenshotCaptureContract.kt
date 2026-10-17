package com.dshbox.browser.runtime

import org.json.JSONObject

internal data class BrowserScreenshotAttempt(
    val backend: String,
    val code: String,
)

internal data class BrowserScreenshotOutcome(
    val backend: String? = null,
    val attempts: List<BrowserScreenshotAttempt> = emptyList(),
    val debugEnabled: Boolean = false,
    val userActionRequired: Boolean = false,
)

internal data class BrowserScreenshotCaptureResult<T>(
    val value: T?,
    val code: String,
    val outcome: BrowserScreenshotOutcome = BrowserScreenshotOutcome(),
)

internal fun interface BrowserScreenshotCaptureHandle {
    /** Returns true only when an active, local capture was cancelled. */
    fun cancel(): Boolean

    /** Cancels inline and confirms that any temporarily reparented view is restored. */
    fun cancelAndRestore(): Boolean = cancel()
}

internal fun interface BrowserScreenshotCaptureSource<T> {
    fun capture(
        deadlineElapsedMillis: Long,
        callback: (BrowserScreenshotCaptureResult<T>) -> Unit,
    ): BrowserScreenshotCaptureHandle
}

internal object BrowserScreenshotCaptureContract {
    const val PRIMARY_BACKEND = "virtual_display"
    const val FALLBACK_BACKEND = "cdp"
    const val DEBUG_REQUIRED = "SCREENSHOT_DEBUG_REQUIRED"
    const val CAPTURE_BUSY = "CAPTURE_BUSY"
    const val VIRTUAL_DISPLAY_UNAVAILABLE = "VIRTUAL_DISPLAY_UNAVAILABLE"
    const val RESTORE_FAILED = "RESTORE_FAILED"

    private val fallbackCodes = setOf(
        "BACKGROUND_UNAVAILABLE",
        "WINDOW_NOT_ACTIVE",
        "CLIPPED_VIEWPORT",
        "VIEWPORT_UNAVAILABLE",
        "NO_FRAME",
        "PIXEL_READ_FAILED",
        VIRTUAL_DISPLAY_UNAVAILABLE,
    )

    fun fallbackAllowed(code: String): Boolean = code in fallbackCodes

    fun successData(
        data: JSONObject,
        outcome: BrowserScreenshotOutcome,
    ): JSONObject {
        outcome.backend?.let { data.put("backend", it) }
        val primary = outcome.attempts.firstOrNull()
        if (outcome.backend == FALLBACK_BACKEND && primary?.backend == PRIMARY_BACKEND) {
            data.put("primaryBackend", primary.backend).put("primaryFailure", primary.code)
        }
        return data
    }

    fun failureData(
        data: JSONObject,
        outcome: BrowserScreenshotOutcome,
    ): JSONObject {
        val primary = outcome.attempts.firstOrNull()
        if (primary != null) data.put("primaryBackend", primary.backend).put("primaryFailure", primary.code)
        val fallback = outcome.attempts.getOrNull(1)
        if (fallback != null) {
            data.put("fallbackBackend", fallback.backend).put("fallbackFailure", fallback.code)
        }
        if (primary != null) {
            data.put("debugEnabled", outcome.debugEnabled)
            data.put("userActionRequired", outcome.userActionRequired)
        }
        return data
    }

    fun failureData(code: String, outcome: BrowserScreenshotOutcome = BrowserScreenshotOutcome()): JSONObject =
        failureData(JSONObject().put("reason", code).put("cancelStage", failureStage(code)), outcome)

    private fun failureStage(code: String): String = when (code) {
        "BACKGROUND_UNAVAILABLE", "WINDOW_NOT_ACTIVE", "CLIPPED_VIEWPORT", "VIEWPORT_UNAVAILABLE",
        "CLOSED", DEBUG_REQUIRED, CAPTURE_BUSY, VIRTUAL_DISPLAY_UNAVAILABLE, RESTORE_FAILED -> "surface_attach"
        "TARGET_UNAVAILABLE", "AMBIGUOUS_TARGET" -> "target_attach"
        "NO_FRAME", "PIXEL_READ_FAILED", "CDP_UNSUPPORTED", "CDP_TIMEOUT", "CAPTURE_EMPTY_RESULT",
        "CANCELLED", "DEADLINE_EXPIRED" -> "pixel_read"
        "PNG_INVALID", "PNG_TOO_LARGE" -> "decode"
        "JOURNAL_UNAVAILABLE" -> "journal"
        "IDENTITY_CHANGED" -> "identity_match"
        "ENCODE_FAILED" -> "encode"
        "STORAGE_FAILED", "SCREENSHOT_QUOTA_EXCEEDED" -> "storage"
        else -> ""
    }
}
