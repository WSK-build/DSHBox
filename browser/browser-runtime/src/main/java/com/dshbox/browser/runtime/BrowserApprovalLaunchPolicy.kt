package com.dshbox.browser.runtime

internal enum class BrowserActivityLaunchKind {
    APPROVAL_ONLY,
    AI_SESSION,
    BROWSER_SESSION,
}

internal object BrowserApprovalLaunchPolicy {
    fun classify(approvalOnly: Boolean, startAsAi: Boolean): BrowserActivityLaunchKind =
        when {
            approvalOnly -> BrowserActivityLaunchKind.APPROVAL_ONLY
            startAsAi -> BrowserActivityLaunchKind.AI_SESSION
            else -> BrowserActivityLaunchKind.BROWSER_SESSION
        }
}
