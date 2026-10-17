package com.dshbox.browser.runtime

internal enum class BrowserControlTransferTarget {
    HUMAN,
    AI,
}

/** Keeps a single, one-shot user confirmation pending without changing browser authorization state. */
internal class BrowserControlTransferPolicy {
    internal data class Pending(
        val id: Long,
        val target: BrowserControlTransferTarget,
        val sessionGeneration: Long? = null,
        val controlGeneration: Long? = null,
    )

    private var nextId = 0L
    @Volatile private var pending: Pending? = null
    private var destroyed = false

    val hasPending: Boolean get() = pending != null

    fun begin(
        target: BrowserControlTransferTarget,
        sessionGeneration: Long? = null,
        controlGeneration: Long? = null,
    ): Pending? {
        if (destroyed || pending != null) return null
        val next = Pending(++nextId, target, sessionGeneration, controlGeneration)
        pending = next
        return next
    }

    fun resolve(
        id: Long,
        confirmed: Boolean,
        sessionGeneration: Long? = null,
        controlGeneration: Long? = null,
    ): BrowserControlTransferTarget? {
        val current = pending?.takeIf { it.id == id } ?: return null
        pending = null
        if (current.sessionGeneration != null && current.sessionGeneration != sessionGeneration) return null
        if (current.controlGeneration != null && current.controlGeneration != controlGeneration) return null
        return current.target.takeIf { confirmed }
    }

    fun cancel(id: Long): Boolean {
        if (pending?.id != id) return false
        pending = null
        return true
    }

    fun cancelPending() {
        pending = null
    }

    fun destroy() {
        pending = null
        destroyed = true
    }
}
