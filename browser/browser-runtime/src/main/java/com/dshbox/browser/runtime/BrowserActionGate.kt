package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserOperationToken
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserSnapshot
import java.util.concurrent.atomic.AtomicReference

/** Atomic, one-use authorization for a single fixed WebView action. */
internal class BrowserActionGate(
    private val elapsedRealtime: () -> Long,
) {
    private data class Authorization(
        val nonce: String,
        val token: BrowserOperationToken,
    )

    private data class State(
        val snapshot: BrowserSnapshot? = null,
        val automationEnabled: Boolean = false,
        val authorization: Authorization? = null,
    )

    private val state = AtomicReference(State())

    fun update(snapshot: BrowserSnapshot, automationEnabled: Boolean) {
        while (true) {
            val previous = state.get()
            val authorization = previous.authorization?.takeIf {
                isAllowed(snapshot, automationEnabled, it.token)
            }
            if (state.compareAndSet(previous, previous.copy(
                    snapshot = snapshot,
                    automationEnabled = automationEnabled,
                    authorization = authorization,
                ))) return
        }
    }

    fun register(nonce: String, token: BrowserOperationToken): Boolean {
        if (nonce.length !in 1..MAX_NONCE_LENGTH) return false
        while (true) {
            val previous = state.get()
            val snapshot = previous.snapshot ?: return false
            if (!isAllowed(snapshot, previous.automationEnabled, token)) return false
            val next = previous.copy(authorization = Authorization(nonce, token))
            if (state.compareAndSet(previous, next)) return true
        }
    }

    /** Consumes a matching grant so a page cannot replay it for another action. */
    fun authorize(nonce: String): Boolean {
        if (nonce.length !in 1..MAX_NONCE_LENGTH) return false
        while (true) {
            val previous = state.get()
            val authorization = previous.authorization ?: return false
            if (authorization.nonce != nonce) return false
            val snapshot = previous.snapshot ?: return false
            if (!isAllowed(snapshot, previous.automationEnabled, authorization.token)) {
                val cleared = previous.copy(authorization = null)
                if (state.compareAndSet(previous, cleared)) return false
                continue
            }
            if (state.compareAndSet(previous, previous.copy(authorization = null))) return true
        }
    }

    fun invalidate() {
        while (true) {
            val previous = state.get()
            if (previous.authorization == null || state.compareAndSet(previous, previous.copy(authorization = null))) return
        }
    }

    private fun isAllowed(
        snapshot: BrowserSnapshot,
        automationEnabled: Boolean,
        token: BrowserOperationToken,
    ): Boolean = automationEnabled &&
        snapshot.lifecycle == BrowserLifecycle.ACTIVE &&
        snapshot.owner == BrowserOwner.AI &&
        token.owner == BrowserOwner.AI &&
        snapshot.sessionGeneration == token.sessionGeneration &&
        snapshot.controlGeneration == token.controlGeneration &&
        snapshot.operationGeneration == token.operationGeneration &&
        snapshot.inFlightOperationId == token.operationId &&
        snapshot.operationDeadlineAtMillis == token.deadlineAtMillis &&
        elapsedRealtime() < token.deadlineAtMillis

    private companion object {
        const val MAX_NONCE_LENGTH = 64
    }
}
