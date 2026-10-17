package com.dshbox.browser.runtime

/** How a pending upload approval should be surfaced to the user. */
internal enum class BrowserUploadApprovalPresentation {
    /** Browser already owns the focused window, so the native dialog is shown inline. */
    INLINE_DIALOG,

    /** Browser is not in the foreground; inform the user without pulling the task forward. */
    BACKGROUND_NOTICE,
}

/**
 * Selects the upload approval presentation. The background branch must never start the Browser
 * activity on its own; the notice only routes the user back when they choose to open it.
 */
internal object BrowserUploadApprovalPresentationPolicy {
    fun select(activityVisible: Boolean, windowFocused: Boolean): BrowserUploadApprovalPresentation =
        if (activityVisible && windowFocused) {
            BrowserUploadApprovalPresentation.INLINE_DIALOG
        } else {
            BrowserUploadApprovalPresentation.BACKGROUND_NOTICE
        }
}
