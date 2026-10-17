package com.dshbox.browser.bridge

/** Keeps authenticated early cancellation ahead of concurrently read request frames. Never evicts a live entry. */
internal class BrowserCliEarlyCancellation(
    private val elapsedMillis: () -> Long,
    private val capacity: Int = 128,
    private val ttlMillis: Long = 90_000L,
) {
    private val entries = LinkedHashMap<BrowserCliRequestKey, Long>()
    init { require(capacity in 1..128 && ttlMillis >= 60_000 && ttlMillis <= 120_000) }

    fun remember(key: BrowserCliRequestKey): Boolean = rememberWhen(key) { true }

    @Synchronized fun rememberWhen(key: BrowserCliRequestKey, stillCancellable: () -> Boolean): Boolean {
        val now = elapsedMillis()
        if (now < 0 || now > Long.MAX_VALUE - ttlMillis) return false
        prune(now)
        if (!entries.containsKey(key) && entries.size >= capacity) return false
        if (!stillCancellable()) return false
        if (!entries.containsKey(key)) entries[key] = now + ttlMillis
        return true
    }

    @Synchronized fun contains(key: BrowserCliRequestKey): Boolean {
        val now = elapsedMillis()
        if (now < 0) return true
        prune(now)
        return entries.containsKey(key)
    }

    @Synchronized fun finishRun(run: BrowserCliRunIdentity) { entries.keys.removeAll { it.run == run } }
    private fun prune(now: Long) { entries.entries.removeAll { it.value <= now } }
}
