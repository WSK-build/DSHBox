package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserTaskPageIdentity
import java.util.ArrayDeque

internal enum class BrowserControlApprovalStatus { PENDING, APPROVED, DENIED, CANCELLED, EXPIRED }

internal data class BrowserControlApproval(
    val id: String,
    val nonce: String,
    val summary: String,
    val taskId: String?,
    val identity: BrowserTaskPageIdentity,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val createdAtElapsedMillis: Long,
    val expiresAtElapsedMillis: Long,
    val status: BrowserControlApprovalStatus = BrowserControlApprovalStatus.PENDING,
)

/** Single-pending approval state with bounded terminal history. Time arguments are monotonic. */
internal class BrowserControlApprovalStore(private val ttlMillis: Long = 120_000L) {
    internal sealed interface Start {
        data class Created(val approval: BrowserControlApproval) : Start
        data class Coalesced(val approval: BrowserControlApproval) : Start
        data object PendingConflict : Start
    }

    private var pending: BrowserControlApproval? = null
    private val terminalIds = ArrayDeque<String>()
    private val records = LinkedHashMap<String, BrowserControlApproval>()

    @Synchronized
    fun start(
        id: String,
        nonce: String,
        summary: String,
        taskId: String?,
        identity: BrowserTaskPageIdentity,
        nowElapsedMillis: Long,
        nowWallMillis: Long,
    ): Start {
        expireIfNeeded(nowElapsedMillis)
        val active = pending
        if (active != null) {
            return if (active.summary == summary && active.taskId == taskId && active.identity == identity) {
                Start.Coalesced(active)
            } else Start.PendingConflict
        }
        val created = BrowserControlApproval(id, nonce, summary, taskId, identity,
            nowWallMillis, nowWallMillis + ttlMillis, nowElapsedMillis, nowElapsedMillis + ttlMillis)
        pending = created
        records[id] = created
        return Start.Created(created)
    }

    @Synchronized
    fun get(id: String, nowElapsedMillis: Long): BrowserControlApproval? {
        expireIfNeeded(nowElapsedMillis)
        return records[id]
    }

    @Synchronized
    fun pending(nowElapsedMillis: Long): BrowserControlApproval? {
        expireIfNeeded(nowElapsedMillis)
        return pending
    }

    @Synchronized
    fun settle(
        id: String,
        nonce: String,
        status: BrowserControlApprovalStatus,
        nowElapsedMillis: Long,
    ): BrowserControlApproval? {
        expireIfNeeded(nowElapsedMillis)
        val current = pending?.takeIf { it.id == id && it.nonce == nonce } ?: return null
        if (status == BrowserControlApprovalStatus.PENDING) return null
        return finish(current, status)
    }

    @Synchronized
    fun cancelForIdentityChange(identity: BrowserTaskPageIdentity?, nowElapsedMillis: Long): BrowserControlApproval? {
        expireIfNeeded(nowElapsedMillis)
        val current = pending ?: return null
        return if (identity == current.identity) null else finish(current, BrowserControlApprovalStatus.CANCELLED)
    }

    @Synchronized
    fun close(nowElapsedMillis: Long): BrowserControlApproval? {
        expireIfNeeded(nowElapsedMillis)
        return pending?.let { finish(it, BrowserControlApprovalStatus.CANCELLED) }
    }

    @Synchronized
    fun expireIfNeeded(nowElapsedMillis: Long): BrowserControlApproval? {
        val current = pending ?: return null
        return if (nowElapsedMillis >= current.expiresAtElapsedMillis) {
            finish(current, BrowserControlApprovalStatus.EXPIRED)
        } else null
    }

    @Synchronized
    fun hasPending(): Boolean = pending != null

    private fun finish(current: BrowserControlApproval, status: BrowserControlApprovalStatus): BrowserControlApproval {
        val settled = current.copy(status = status)
        pending = null
        records[current.id] = settled
        terminalIds.addLast(current.id)
        while (terminalIds.size > TERMINAL_HISTORY_LIMIT) {
            val removed = terminalIds.removeFirst()
            records.remove(removed)
        }
        return settled
    }

    companion object { const val TERMINAL_HISTORY_LIMIT = 16 }
}
