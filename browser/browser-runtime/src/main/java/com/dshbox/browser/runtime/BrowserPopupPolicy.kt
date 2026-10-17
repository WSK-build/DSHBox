package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserOwner

/** Outcome of a WebView new-window request. */
internal enum class BrowserPopupDecision {
    /** AI session with a matching one-use expectation: create the child without stealing focus. */
    AI_EXPECTED,

    /** Human-owned window with a real user gesture: a normal link open. */
    HUMAN_USER_GESTURE,

    /** Anything else (closed/background/unexpected/over limit): the popup must be dropped. */
    REJECT,
}

/**
 * Single source of truth for whether a WebView popup may open. Enabling multiple windows must not
 * let pages spawn windows on their own: AI popups need an active page, automation on and a one-use
 * expectation; human popups need the window in the foreground and a real gesture.
 */
internal object BrowserPopupPolicy {
    fun decide(
        owner: BrowserOwner,
        automationEnabled: Boolean,
        activePage: Boolean,
        hasExpectedClaim: Boolean,
        userGesture: Boolean,
        pageCount: Int,
        maxPages: Int,
        visible: Boolean,
        sessionActive: Boolean,
    ): BrowserPopupDecision = when {
        !sessionActive -> BrowserPopupDecision.REJECT
        pageCount >= maxPages -> BrowserPopupDecision.REJECT
        owner == BrowserOwner.AI && automationEnabled && activePage && hasExpectedClaim ->
            BrowserPopupDecision.AI_EXPECTED
        owner == BrowserOwner.HUMAN && activePage && visible && userGesture ->
            BrowserPopupDecision.HUMAN_USER_GESTURE
        else -> BrowserPopupDecision.REJECT
    }
}
