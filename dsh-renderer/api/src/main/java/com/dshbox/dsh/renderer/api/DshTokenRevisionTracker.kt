package com.dshbox.dsh.renderer.api

/** Session-local token availability and non-secret, monotonic change identity. */
class DshTokenRevisionTracker(initialToken: String? = null) {
    private var lastAvailableToken = initialToken?.takeIf(String::isNotEmpty)
    private var generation = if (lastAvailableToken == null) 0L else 1L
    var token: String? = lastAvailableToken
        private set

    val revision: Long? get() = if (token == null) null else generation

    fun update(value: String?): Boolean {
        val available = value?.takeIf(String::isNotEmpty)
        if (available == token) return false
        token = available
        if (available != null && available != lastAvailableToken) {
            generation += 1
            lastAvailableToken = available
        }
        return true
    }

    /** Teardown only; the owning renderer identity must already be released. */
    fun clearForRelease() {
        token = null
        lastAvailableToken = null
        generation = 0
    }

    override fun toString(): String = "DshTokenRevisionTracker(revision=$revision)"
}
