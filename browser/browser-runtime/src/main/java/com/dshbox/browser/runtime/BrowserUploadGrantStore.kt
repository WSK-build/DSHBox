package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserTaskPageIdentity
import java.util.UUID

internal enum class BrowserUploadGrantState { WAITING_USER, READY, CHOOSER_PENDING, CHOOSER_DELIVERED, DENIED, CANCELLED, EXPIRED, FAILED }

internal val BrowserUploadGrantState.wireName: String
    get() = when (this) {
        BrowserUploadGrantState.WAITING_USER -> "pending"
        BrowserUploadGrantState.READY -> "ready"
        BrowserUploadGrantState.CHOOSER_PENDING -> "chooser_pending"
        BrowserUploadGrantState.CHOOSER_DELIVERED -> "chooser_delivered"
        BrowserUploadGrantState.DENIED -> "denied"
        BrowserUploadGrantState.CANCELLED -> "cancelled"
        BrowserUploadGrantState.EXPIRED -> "expired"
        BrowserUploadGrantState.FAILED -> "failed"
    }

internal data class BrowserUploadGrant(
    val id: String,
    val taskId: String,
    val identity: BrowserTaskPageIdentity,
    val origin: String,
    val summary: String,
    val acceptTypes: List<String>,
    val multiple: Boolean,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val sources: List<BrowserUploadSource>,
    val state: BrowserUploadGrantState = BrowserUploadGrantState.WAITING_USER,
    val staged: List<BrowserUploadStagedFile> = emptyList(),
    val actualAcceptTypes: List<String> = emptyList(),
    val chooserMultiple: Boolean? = null,
)

/** In-memory grants are deliberately lost on process death; staged copies are swept by TTL. */
internal class BrowserUploadGrantStore {
    private val grants = LinkedHashMap<String, BrowserUploadGrant>()
    private var consumed = HashSet<String>()

    @Synchronized
    fun create(
        taskId: String,
        identity: BrowserTaskPageIdentity,
        origin: String,
        summary: String,
        acceptTypes: List<String>,
        multiple: Boolean,
        sources: List<BrowserUploadSource>,
        nowMillis: Long,
    ): BrowserUploadGrant? {
        prune(nowMillis)
        if (grants.values.count { it.state in ACTIVE } >= MAX_ACTIVE_GRANTS) return null
        if (grants.values.any { it.taskId == taskId && it.identity == identity && it.state in ACTIVE }) return null
        if (sources.isEmpty() || sources.size > BrowserUploadPolicy.MAX_FILES || sources.sumOf { it.size } > BrowserUploadPolicy.MAX_TOTAL_BYTES) return null
        val id = UUID.randomUUID().toString().replace("-", "")
        return BrowserUploadGrant(id, taskId, identity, origin, summary.take(MAX_SUMMARY_CHARS), acceptTypes.toList(), multiple,
            nowMillis, nowMillis + BrowserUploadPolicy.GRANT_TTL_MILLIS, sources.toList()).also { grants[id] = it }
    }

    @Synchronized fun get(id: String, nowMillis: Long): BrowserUploadGrant? {
        expire(id, nowMillis)
        return grants[id]
    }

    @Synchronized fun activeWaitingApproval(nowMillis: Long): BrowserUploadGrant? {
        prune(nowMillis)
        return grants.values.firstOrNull { it.state == BrowserUploadGrantState.WAITING_USER }
    }

    @Synchronized fun readyForPage(identity: BrowserTaskPageIdentity, nowMillis: Long): BrowserUploadGrant? {
        prune(nowMillis)
        return grants.values.firstOrNull { it.identity == identity && it.state == BrowserUploadGrantState.READY }
    }

    @Synchronized fun waitingForPage(identity: BrowserTaskPageIdentity, nowMillis: Long): BrowserUploadGrant? {
        prune(nowMillis)
        return grants.values.firstOrNull { it.identity == identity && it.state == BrowserUploadGrantState.WAITING_USER }
    }

    @Synchronized fun approve(id: String, staged: List<BrowserUploadStagedFile>, nowMillis: Long): BrowserUploadGrant? {
        expire(id, nowMillis)
        val current = grants[id]?.takeIf { it.state == BrowserUploadGrantState.WAITING_USER } ?: return null
        if (staged.size != current.sources.size || staged.map { it.fileId }.toSet() != current.sources.map { it.id }.toSet()) return null
        if (staged.any { file -> current.sources.firstOrNull { it.id == file.fileId }?.let {
                file.name != it.name || file.mimeType != it.mimeType || file.size != it.size || file.sha256 != it.sha256
            } != false }) return null
        return current.copy(state = BrowserUploadGrantState.READY, staged = staged.toList()).also { grants[id] = it }
    }

    @Synchronized fun deny(id: String, state: BrowserUploadGrantState, nowMillis: Long): BrowserUploadGrant? {
        require(state in setOf(BrowserUploadGrantState.DENIED, BrowserUploadGrantState.CANCELLED, BrowserUploadGrantState.EXPIRED, BrowserUploadGrantState.FAILED))
        expire(id, nowMillis)
        val current = grants[id] ?: return null
        if (current.state in TERMINAL) return current
        return current.copy(state = state, staged = emptyList()).also { grants[id] = it }
    }

    @Synchronized fun attachChooser(
        id: String, identity: BrowserTaskPageIdentity, actualAcceptTypes: List<String>, multiple: Boolean, nowMillis: Long,
    ): BrowserUploadGrant? {
        expire(id, nowMillis)
        val current = grants[id]?.takeIf { it.state == BrowserUploadGrantState.READY && it.identity == identity } ?: return null
        if (current.sources.none {
                BrowserUploadPolicy.accepts(it.mimeType, it.name, current.acceptTypes) &&
                    BrowserUploadPolicy.accepts(it.mimeType, it.name, actualAcceptTypes)
            }
        ) return null
        return current.copy(state = BrowserUploadGrantState.CHOOSER_PENDING, actualAcceptTypes = actualAcceptTypes.toList(), chooserMultiple = multiple)
            .also { grants[id] = it }
    }

    @Synchronized fun consume(id: String, taskId: String, identity: BrowserTaskPageIdentity, fileIds: List<String>, nowMillis: Long): List<BrowserUploadStagedFile>? {
        expire(id, nowMillis)
        val current = grants[id]?.takeIf {
            it.state == BrowserUploadGrantState.CHOOSER_PENDING && it.taskId == taskId && it.identity == identity &&
                it.expiresAtMillis > nowMillis && id !in consumed
        } ?: return null
        if (fileIds.isEmpty() || fileIds.size > BrowserUploadPolicy.MAX_FILES || fileIds.distinct().size != fileIds.size ||
            ((!current.multiple || current.chooserMultiple != true) && fileIds.size != 1)) return null
        val byId = current.staged.associateBy { it.fileId }
        val selected = fileIds.map { byId[it] ?: return null }
        if (selected.any { !BrowserUploadPolicy.accepts(it.mimeType, it.name, current.acceptTypes) ||
                    !BrowserUploadPolicy.accepts(it.mimeType, it.name, current.actualAcceptTypes) }) return null
        consumed.add(id)
        grants[id] = current.copy(state = BrowserUploadGrantState.CHOOSER_DELIVERED)
        return selected
    }

    @Synchronized fun list(id: String, taskId: String, identity: BrowserTaskPageIdentity, nowMillis: Long): BrowserUploadGrant? {
        val current = get(id, nowMillis) ?: return null
        return current.takeIf { it.taskId == taskId && it.identity == identity }
    }

    @Synchronized fun revokeIdentity(identity: BrowserTaskPageIdentity, nowMillis: Long): List<BrowserUploadGrant> {
        prune(nowMillis)
        return grants.values.filter { it.identity == identity && it.state in REVOCABLE }.mapNotNull {
            deny(it.id, BrowserUploadGrantState.CANCELLED, nowMillis)
        }
    }

    @Synchronized fun revokeAll(nowMillis: Long): List<BrowserUploadGrant> {
        prune(nowMillis)
        return grants.values.filter { it.state in REVOCABLE }.mapNotNull {
            deny(it.id, BrowserUploadGrantState.CANCELLED, nowMillis)
        }
    }

    @Synchronized fun revokeTask(taskId: String, nowMillis: Long): List<BrowserUploadGrant> {
        prune(nowMillis)
        return grants.values.filter { it.taskId == taskId && it.state in REVOCABLE }.mapNotNull {
            deny(it.id, BrowserUploadGrantState.CANCELLED, nowMillis)
        }
    }

    @Synchronized fun revokeExcept(identity: BrowserTaskPageIdentity?, nowMillis: Long): List<BrowserUploadGrant> {
        prune(nowMillis)
        return grants.values.filter { it.state in REVOCABLE && it.identity != identity }.mapNotNull {
            deny(it.id, BrowserUploadGrantState.CANCELLED, nowMillis)
        }
    }

    @Synchronized fun cancel(id: String, taskId: String, identity: BrowserTaskPageIdentity, nowMillis: Long): BrowserUploadGrant? {
        val current = get(id, nowMillis)?.takeIf { it.taskId == taskId && it.identity == identity } ?: return null
        return deny(current.id, BrowserUploadGrantState.CANCELLED, nowMillis)
    }

    @Synchronized fun prune(nowMillis: Long): List<BrowserUploadGrant> {
        val expired = grants.values.filter { it.state !in TERMINAL && it.expiresAtMillis <= nowMillis }.mapNotNull {
            deny(it.id, BrowserUploadGrantState.EXPIRED, nowMillis)
        }
        grants.entries.removeIf { it.value.state in TERMINAL && nowMillis - it.value.createdAtMillis > BrowserUploadPolicy.ARTIFACT_TTL_MILLIS }
        consumed.removeIf { id -> id !in grants }
        return expired
    }

    private fun expire(id: String, nowMillis: Long) {
        grants[id]?.takeIf { it.state !in TERMINAL && it.expiresAtMillis <= nowMillis }?.let {
            grants[id] = it.copy(state = BrowserUploadGrantState.EXPIRED, staged = emptyList())
        }
    }

    companion object {
        private const val MAX_ACTIVE_GRANTS = 4
        private const val MAX_SUMMARY_CHARS = 160
        private val ACTIVE = setOf(BrowserUploadGrantState.WAITING_USER, BrowserUploadGrantState.READY, BrowserUploadGrantState.CHOOSER_PENDING)
        private val REVOCABLE = ACTIVE + BrowserUploadGrantState.CHOOSER_DELIVERED
        private val TERMINAL = setOf(BrowserUploadGrantState.CHOOSER_DELIVERED, BrowserUploadGrantState.DENIED,
            BrowserUploadGrantState.CANCELLED, BrowserUploadGrantState.EXPIRED, BrowserUploadGrantState.FAILED)
    }
}
