package com.dshbox.browser.runtime

import java.net.URI
import java.util.IdentityHashMap

/** Tracks main-frame navigation state independently for each WebView. */
internal class BrowserPageStateTracker<T : Any> {
    enum class LoadState { ACCEPTED, LOADING, FINISHED, FAILED }

    data class State(
        val navigationId: Long,
        val requestedUrl: String?,
        var currentUrl: String? = null,
        var loadState: LoadState = LoadState.ACCEPTED,
        var readyState: String? = null,
        var mainFrameError: Int? = null,
        var httpStatus: Int? = null,
        var urlBefore: String? = null,
        var urlAfter: String? = null,
        var committed: Boolean = false,
    )

    private val states = IdentityHashMap<T, State>()
    private var sequence = 0L

    /** Installing UI callbacks must not replace a live document's navigation identity. */
    @Synchronized
    fun ensure(target: T, initialUrl: String?): State = states[target] ?: begin(target, initialUrl)

    @Synchronized
    fun begin(target: T, requestedUrl: String?): State = begin(target, requestedUrl, null)

    @Synchronized
    fun begin(target: T, requestedUrl: String?, actualUrlBefore: String?): State {
        val previous = states[target]
        return State(
            navigationId = ++sequence,
            requestedUrl = requestedUrl,
            urlBefore = actualUrlBefore ?: previous?.urlAfter ?: previous?.urlBefore,
        ).also { states[target] = it }
    }

    /**
     * WebView callbacks do not carry a navigation token. Correlate them with the WebView and its
     * current URL, assigning a new local identity when a terminal document starts navigating.
     */
    @Synchronized
    fun pageStarted(target: T, callbackUrl: String?, actualUrl: String?): State? {
        var state = states[target] ?: return null
        val url = callbackUrl ?: return null
        if (url != actualUrl) return null
        if (state.loadState == LoadState.FINISHED || state.loadState == LoadState.FAILED) {
            state = State(
                navigationId = ++sequence,
                requestedUrl = url,
                currentUrl = url,
                loadState = LoadState.LOADING,
                urlBefore = state.urlAfter ?: state.urlBefore,
            )
            states[target] = state
        } else {
            if (state.committed) {
                state.urlBefore = state.urlAfter ?: state.urlBefore
                state.urlAfter = null
                state.committed = false
            }
            // The first callback may already carry a redirect destination; later starts stay in
            // the same local navigation identity while WebView reports the redirect chain.
            state.currentUrl = url
            state.readyState = null
            state.loadState = LoadState.LOADING
            state.mainFrameError = null
            state.httpStatus = null
        }
        return state
    }

    /**
     * Records main-frame commit evidence only. A page-started callback means that loading began,
     * not that a response document committed.
     */
    @Synchronized
    fun pageCommittedVisible(target: T, callbackUrl: String?, actualUrl: String?): State? {
        val state = currentForCallback(target, callbackUrl, actualUrl) ?: return null
        state.committed = true
        state.urlAfter = state.currentUrl ?: actualUrl
        return state
    }

    @Synchronized
    fun pageFinished(target: T, callbackUrl: String?, actualUrl: String?, readyState: String?): State? {
        val state = currentForCallback(target, callbackUrl, actualUrl) ?: return null
        if (state.loadState == LoadState.FAILED) return state
        state.readyState = readyState
        if (readyState == "complete") state.loadState = LoadState.FINISHED
        return state
    }

    @Synchronized
    fun mainFrameError(target: T, callbackUrl: String?, actualUrl: String?, errorCode: Int): State? {
        val pending = states[target] ?: return null
        val url = callbackUrl ?: return null
        val failedBeforeStart = pending.loadState == LoadState.ACCEPTED && pending.currentUrl == null &&
            pending.requestedUrl == url && (actualUrl == url || actualUrl.isNullOrBlank() ||
            actualUrl == pending.urlBefore || actualUrl == "about:blank" ||
            actualUrl == "chrome-error://chromewebdata/")
        val state = currentForCallback(target, callbackUrl, actualUrl)
            ?: pending.takeIf { failedBeforeStart }?.also { it.currentUrl = url }
            ?: return null
        state.loadState = LoadState.FAILED
        state.mainFrameError = errorCode
        state.readyState = null
        return state
    }

    @Synchronized
    fun mainFrameHttpStatus(target: T, callbackUrl: String?, actualUrl: String?, statusCode: Int): State? {
        val state = currentForCallback(target, callbackUrl, actualUrl) ?: return null
        state.httpStatus = statusCode
        return state
    }

    @Synchronized
    fun state(target: T): State? = states[target]?.copy()

    /** Records a verified history transition that retained the current document. */
    @Synchronized
    fun sameDocumentCommitted(target: T, before: String, after: String): State? {
        if (!states.containsKey(target)) return null
        return State(
            navigationId = ++sequence, requestedUrl = after, currentUrl = after,
            loadState = LoadState.FINISHED, readyState = "complete", urlBefore = before,
            urlAfter = after, committed = true,
        ).also { states[target] = it }
    }

    @Synchronized
    fun remove(target: T) { states.remove(target) }

    private fun currentForCallback(target: T, callbackUrl: String?, actualUrl: String?): State? {
        val state = states[target] ?: return null
        val url = callbackUrl ?: return null
        if (url != actualUrl || state.currentUrl != url) return null
        return state
    }

    companion object {
        /** URLs returned to automation preserve origin and path while withholding query and fragment. */
        fun publicUrl(raw: String?): PublicUrl {
            if (raw.isNullOrBlank()) return PublicUrl(null, false, false)
            val parts = runCatching { URI(raw) }.getOrNull()
            if (parts == null || parts.scheme == null) {
                return PublicUrl(null, raw.contains('?'), raw.contains('#'))
            }
            val scheme = parts.scheme.lowercase(java.util.Locale.ROOT)
            if (scheme == "about" && parts.rawSchemeSpecificPart == "blank") {
                return PublicUrl("about:blank", parts.rawQuery != null, parts.rawFragment != null)
            }
            if (scheme !in setOf("http", "https") || parts.isOpaque || parts.host.isNullOrBlank()) {
                return PublicUrl(null, parts.rawQuery != null, parts.rawFragment != null)
            }
            val safe = runCatching {
                val authority = parts.rawAuthority?.substringAfterLast('@')
                scheme + ":" + (authority?.let { "//$it" } ?: "") + (parts.rawPath ?: "")
            }.getOrNull() ?: return PublicUrl(null, parts.rawQuery != null, parts.rawFragment != null)
            return PublicUrl(safe.take(2048), parts.rawQuery != null, parts.rawFragment != null)
        }

    }

    data class PublicUrl(val value: String?, val queryRedacted: Boolean, val fragmentRedacted: Boolean)
}
