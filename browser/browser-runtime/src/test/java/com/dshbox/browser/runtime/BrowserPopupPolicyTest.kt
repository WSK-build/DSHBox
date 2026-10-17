package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserOwner
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserPopupPolicyTest {
    @Test
    fun aiPopupRequiresEnabledAutomationActiveParentAndOneShotExpectation() {
        assertEquals(
            BrowserPopupDecision.AI_EXPECTED,
            decide(owner = BrowserOwner.AI, automationEnabled = true, activePage = true, hasExpectedClaim = true),
        )
        assertRejected(owner = BrowserOwner.AI, automationEnabled = false, activePage = true, hasExpectedClaim = true)
        assertRejected(owner = BrowserOwner.AI, automationEnabled = true, activePage = false, hasExpectedClaim = true)
        assertRejected(owner = BrowserOwner.AI, automationEnabled = true, activePage = true, hasExpectedClaim = false)
        assertRejected(owner = BrowserOwner.AI, automationEnabled = true, activePage = true, userGesture = true)
        assertRejected(owner = BrowserOwner.AI, automationEnabled = true, activePage = true, hasExpectedClaim = true, sessionActive = false)
    }

    @Test
    fun humanPopupRequiresAnActivePageAndRealGestureButNotAiAuthorization() {
        assertEquals(
            BrowserPopupDecision.HUMAN_USER_GESTURE,
            decide(owner = BrowserOwner.HUMAN, automationEnabled = false, activePage = true, userGesture = true),
        )
        assertRejected(owner = BrowserOwner.HUMAN, automationEnabled = false, activePage = true, userGesture = false)
        assertRejected(owner = BrowserOwner.HUMAN, automationEnabled = true, activePage = false, userGesture = true)
        assertRejected(owner = BrowserOwner.HUMAN, automationEnabled = false, activePage = true, userGesture = true, visible = false)
        assertRejected(owner = BrowserOwner.HUMAN, automationEnabled = false, activePage = true, userGesture = true, sessionActive = false)
    }

    @Test
    fun rejectsUnownedOrOverLimitPopupRequests() {
        assertRejected(owner = BrowserOwner.NONE, automationEnabled = true, activePage = true, hasExpectedClaim = true, userGesture = true)
        assertRejected(owner = BrowserOwner.AI, automationEnabled = true, activePage = true, hasExpectedClaim = true, pageCount = 4)
        assertRejected(owner = BrowserOwner.HUMAN, automationEnabled = false, activePage = true, userGesture = true, pageCount = 4)
    }

    private fun assertRejected(
        owner: BrowserOwner,
        automationEnabled: Boolean,
        activePage: Boolean,
        hasExpectedClaim: Boolean = false,
        userGesture: Boolean = false,
        pageCount: Int = 1,
        visible: Boolean = true,
        sessionActive: Boolean = true,
    ) {
        assertEquals(
            BrowserPopupDecision.REJECT,
            decide(owner, automationEnabled, activePage, hasExpectedClaim, userGesture, pageCount, visible, sessionActive),
        )
    }

    private fun decide(
        owner: BrowserOwner,
        automationEnabled: Boolean,
        activePage: Boolean,
        hasExpectedClaim: Boolean = false,
        userGesture: Boolean = false,
        pageCount: Int = 1,
        visible: Boolean = true,
        sessionActive: Boolean = true,
    ): BrowserPopupDecision = BrowserPopupPolicy.decide(
        owner = owner,
        automationEnabled = automationEnabled,
        activePage = activePage,
        hasExpectedClaim = hasExpectedClaim,
        userGesture = userGesture,
        pageCount = pageCount,
        maxPages = BrowserPageManager.MAX_PAGES,
        visible = visible,
        sessionActive = sessionActive,
    )
}
