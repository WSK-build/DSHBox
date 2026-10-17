package com.dshbox.browser.runtime

internal object BrowserUploadApprovalFocusPolicy {
    fun canAcknowledge(
        activityVisible: Boolean,
        currentDialog: Boolean,
        dialogShowing: Boolean,
        dialogWindowFocused: Boolean,
    ): Boolean = activityVisible && currentDialog && dialogShowing && dialogWindowFocused
}
