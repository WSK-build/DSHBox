package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserApprovalLaunchPolicyTest {
    @Test
    fun approvalOnlyIntentWinsOverSessionCreationFlags() {
        assertEquals(
            BrowserActivityLaunchKind.APPROVAL_ONLY,
            BrowserApprovalLaunchPolicy.classify(approvalOnly = true, startAsAi = true),
        )
        assertEquals(
            BrowserActivityLaunchKind.AI_SESSION,
            BrowserApprovalLaunchPolicy.classify(approvalOnly = false, startAsAi = true),
        )
        assertEquals(
            BrowserActivityLaunchKind.BROWSER_SESSION,
            BrowserApprovalLaunchPolicy.classify(approvalOnly = false, startAsAi = false),
        )
    }
}
