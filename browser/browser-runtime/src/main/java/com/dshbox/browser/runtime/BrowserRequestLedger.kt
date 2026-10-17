package com.dshbox.browser.runtime

import org.json.JSONObject

/**
 * Process-local bounded idempotency ledger. Access is serialized by the service owner.
 * Pending operations reserve UNKNOWN slots, so effective admission is min(capacity,
 * unknownCapacity - retainedUnknownCount); this guarantees uncertainty can be retained.
 * UNKNOWN reservations expire after their monotonic retention window. This does not survive
 * process death; durable replay protection requires a separate persistent journal.
 */
internal class BrowserRequestLedger<T : Any>(
    private val capacity: Int = DEFAULT_PENDING_CAPACITY,
    private val completedCapacity: Int = DEFAULT_COMPLETED_CAPACITY,
    private val unknownCapacity: Int = DEFAULT_UNKNOWN_CAPACITY,
    private val completedRetentionMillis: Long = DEFAULT_COMPLETED_RETENTION_MILLIS,
    private val unknownRetentionMillis: Long = DEFAULT_UNKNOWN_RETENTION_MILLIS,
) {
    data class Entry<T : Any>(
        val requestId: String,
        val rawPayload: String,
        val deadlineAtElapsedMillis: Long,
        val waiters: MutableList<T> = mutableListOf(),
        var responseJson: String? = null,
        var completedAtElapsedMillis: Long? = null,
    )

    sealed interface Claim<T : Any> {
        class New<T : Any>(val entry: Entry<T>) : Claim<T>
        class ExistingPending<T : Any>(val entry: Entry<T>) : Claim<T>
        class ExistingComplete<T : Any>(val responseJson: String) : Claim<T>
        class Conflict<T : Any> : Claim<T>
        class Full<T : Any> : Claim<T>
        class WaitersFull<T : Any> : Claim<T>
        class Expired<T : Any> : Claim<T>
    }

    private val pending = linkedMapOf<String, Entry<T>>()
    private val completed = linkedMapOf<String, Entry<T>>()
    private val unknown = linkedMapOf<String, Entry<T>>()

    init {
        require(capacity > 0 && completedCapacity > 0 && unknownCapacity > 0)
        require(completedRetentionMillis > 0 && unknownRetentionMillis > 0)
    }

    fun claim(
        requestId: String,
        rawPayload: String,
        deadlineAtElapsedMillis: Long,
        waiter: T,
        now: Long,
    ): Claim<T> {
        if (now >= deadlineAtElapsedMillis) return Claim.Expired()
        pruneCaches(now)
        val existing = pending[requestId] ?: unknown[requestId] ?: completed[requestId]
        if (existing != null) {
            if (existing.rawPayload != rawPayload) return Claim.Conflict()
            val response = existing.responseJson
            if (response != null) return Claim.ExistingComplete(response)
            if (existing.waiters.size >= MAX_WAITERS_PER_REQUEST) return Claim.WaitersFull()
            existing.waiters += waiter
            return Claim.ExistingPending(existing)
        }
        // Each accepted pending request reserves a slot in case its final outcome is uncertain.
        // UNKNOWN completion transfers the reservation; a definite completion releases it.
        if (pending.size >= capacity || pending.size + unknown.size >= unknownCapacity) return Claim.Full()
        val entry = Entry<T>(requestId, rawPayload, deadlineAtElapsedMillis)
        entry.waiters += waiter
        pending[requestId] = entry
        return Claim.New(entry)
    }

    fun complete(entry: Entry<T>, responseJson: String, now: Long): List<T> =
        complete(entry, responseJson, now, forceUnknown = false)

    fun expire(entry: Entry<T>, now: Long, unknownResponseJson: String): List<T> {
        if (now < entry.deadlineAtElapsedMillis) return emptyList()
        return complete(entry, unknownResponseJson, now, forceUnknown = true)
    }

    fun find(requestId: String): Entry<T>? = pending[requestId] ?: unknown[requestId] ?: completed[requestId]

    /** Number of operations still awaiting a terminal response. */
    fun size(): Int = pending.size

    private fun complete(
        entry: Entry<T>,
        responseJson: String,
        now: Long,
        forceUnknown: Boolean,
    ): List<T> {
        if (pending[entry.requestId] !== entry || entry.responseJson != null) return emptyList()
        entry.responseJson = responseJson
        entry.completedAtElapsedMillis = now
        pending.remove(entry.requestId)
        if (forceUnknown || responseIsUncertain(entry.requestId, responseJson)) {
            // Do not evict live uncertainty records. New IDs fail closed at the reserved bound.
            unknown[entry.requestId] = entry
        } else {
            completed[entry.requestId] = entry
            while (completed.size > completedCapacity) {
                val oldestId = completed.keys.firstOrNull() ?: break
                completed.remove(oldestId)
            }
        }
        return entry.waiters.toList().also { entry.waiters.clear() }
    }

    private fun pruneCaches(now: Long) {
        prune(completed, now, completedRetentionMillis)
        prune(unknown, now, unknownRetentionMillis)
    }

    private fun prune(cache: LinkedHashMap<String, Entry<T>>, now: Long, retentionMillis: Long) {
        val iterator = cache.values.iterator()
        while (iterator.hasNext()) {
            val completedAt = iterator.next().completedAtElapsedMillis ?: continue
            if (now >= completedAt && now - completedAt >= retentionMillis) iterator.remove()
        }
    }

    private fun responseIsUncertain(requestId: String, serialized: String): Boolean {
        val response = runCatching { JSONObject(serialized) }.getOrNull() ?: return true
        if (response.optInt("version") != 1 || response.optString("requestId") != requestId ||
            response.optString("code").isBlank() || !response.has("executed")
        ) return true
        if (response.optString("code") == "UNKNOWN") return true
        return response.opt("executed") !is Boolean
    }

    private companion object {
        const val MAX_WAITERS_PER_REQUEST = 8
        const val DEFAULT_PENDING_CAPACITY = 512
        const val DEFAULT_COMPLETED_CAPACITY = 128
        const val DEFAULT_UNKNOWN_CAPACITY = 128
        const val DEFAULT_COMPLETED_RETENTION_MILLIS = 30_000L
        const val DEFAULT_UNKNOWN_RETENTION_MILLIS = 24L * 60 * 60 * 1000
    }
}
