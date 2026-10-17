package com.dshbox.browser.runtime

/** Associates WebView main-frame callbacks with the current navigation attempt. */
internal class BrowserNavigationTracker<T : Any, Token> {
    class Attempt<T : Any, Token>(
        val id: Long,
        val target: T,
        val token: Token,
        val expectedUrl: String?,
        internal var startedUrl: String? = null,
        internal var ignoreFailureCallbacks: Boolean = false,
    )

    private var sequence = 0L
    private var current: Attempt<T, Token>? = null

    fun current(): Attempt<T, Token>? = current

    fun begin(target: T, token: Token, expectedUrl: String?): Attempt<T, Token> {
        val previous = current
        val attempt = Attempt<T, Token>(++sequence, target, token, expectedUrl)
        if (previous != null && previous.target === target &&
            (previous.expectedUrl == expectedUrl || previous.startedUrl == expectedUrl)
        ) {
            attempt.ignoreFailureCallbacks = true
        }
        current = attempt
        return attempt
    }

    fun bindStart(target: T, callbackUrl: String?, currentUrl: String?): Attempt<T, Token>? {
        val attempt = current ?: return null
        val url = callbackUrl ?: return null
        if (attempt.target !== target || currentUrl != url) return null
        if (attempt.startedUrl == null) {
            if (attempt.expectedUrl != null && attempt.expectedUrl != url) return null
        }
        attempt.startedUrl = url
        return attempt
    }

    fun mayComplete(
        attempt: Attempt<T, Token>,
        target: T,
        callbackUrl: String?,
        currentUrl: String?,
        progress: Int,
        readyState: String?,
    ): Boolean = current === attempt && attempt.target === target &&
        callbackUrl != null && callbackUrl == currentUrl && attempt.startedUrl == callbackUrl &&
        progress >= 100 && readyState == "complete"

    fun mayFail(attempt: Attempt<T, Token>, target: T, callbackUrl: String?, currentUrl: String?): Boolean =
        current === attempt && !attempt.ignoreFailureCallbacks && attempt.target === target &&
            callbackUrl != null && callbackUrl == currentUrl && attempt.startedUrl == callbackUrl

    fun clear(attempt: Attempt<T, Token>? = current): Boolean {
        if (attempt == null || current !== attempt) return false
        current = null
        return true
    }
}
