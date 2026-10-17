package com.dshbox.browser.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.dshbox.browser.api.BrowserEffectJournalEntry
import com.dshbox.browser.api.BrowserTaskBudget
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserTaskRecord
import com.dshbox.browser.api.BrowserTaskRecoveryMetadata
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserTaskWaitReason
import com.dshbox.browser.api.BrowserUserEffectChoice
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class BrowserTaskCoordinatorResources(
    val store: BrowserTaskCoordinatorStore,
    val digest: (ByteArray) -> String,
    val bootId: String,
)

internal fun openBrowserTaskCoordinatorResources(
    openStore: () -> BrowserTaskCoordinatorStore,
    openDigest: () -> (ByteArray) -> String,
    readBootId: () -> String,
): BrowserTaskCoordinatorResources {
    val bootId = readBootId()
    val store = openStore()
    return try {
        BrowserTaskCoordinatorResources(store, openDigest(), bootId)
    } catch (error: Throwable) {
        runCatching { store.close() }
        throw error
    }
}

internal fun <T> findBoundedRecoveryMetadata(
    taskId: String,
    maxTasks: Int,
    pageSize: Int,
    loadPage: (offset: Int, limit: Int) -> List<T>,
    taskIdOf: (T) -> String,
): T? {
    require(maxTasks > 0 && pageSize > 0)
    var offset = 0
    while (offset < maxTasks) {
        val page = loadPage(offset, pageSize)
        page.firstOrNull { taskIdOf(it) == taskId }?.let { return it }
        if (page.size < pageSize) return null
        offset += page.size
    }
    return null
}

/** Orderly-drains journal work before closing its store; interruption never permits an early close. */
internal object BrowserTaskCoordinatorResourceCloser {
    fun closeAfterIoDrained(io: ExecutorService, closeStore: () -> Unit) {
        io.shutdown()
        var interrupted = false
        try {
            while (!io.isTerminated) {
                try {
                    io.awaitTermination(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            closeStore()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}

/** Runtime services required by BrowserTaskCoordinator; Android production wires these to system facilities. */
internal interface BrowserTaskCoordinatorRuntime {
    fun openResources(): BrowserTaskCoordinatorResources
    fun postIo(task: () -> Unit)
    fun postMain(task: () -> Unit)
    fun tryPostIo(task: () -> Unit): Boolean {
        postIo(task)
        return true
    }

    fun tryPostMain(task: () -> Unit): Boolean {
        postMain(task)
        return true
    }

    fun elapsedRealtime(): Long
    fun currentTimeMillis(): Long
    fun currentBootId(): String
    fun closeResources(resources: BrowserTaskCoordinatorResources)
    fun closeResourcesAsync(resources: BrowserTaskCoordinatorResources) {
        closeResources(resources)
    }

    fun shutdown()
}

internal class BrowserTaskJournalStore(private val journal: BrowserTaskJournal) : BrowserTaskCoordinatorStore {
    override fun recoverAfterProcessStart(currentBootId: String, nowElapsedMillis: Long) =
        journal.recoverAfterProcessStart(currentBootId, nowElapsedMillis)

    override fun close() = journal.close()

    override fun listRecoveryMetadata(
        currentBootId: String,
        nowElapsedMillis: Long,
        offset: Int,
        limit: Int,
    ): List<BrowserTaskRecoveryMetadata> = journal.listRecoveryMetadata(
        currentBootId, nowElapsedMillis, offset, limit,
    )

    override fun findRecoveryMetadata(
        taskId: String,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): BrowserTaskRecoveryMetadata? = findBoundedRecoveryMetadata(
        taskId = taskId,
        maxTasks = MAX_MANAGED_TASKS,
        pageSize = DEFAULT_TASK_PAGE_SIZE,
        loadPage = { offset, limit ->
            journal.listRecoveryMetadata(currentBootId, nowElapsedMillis, offset, limit)
        },
        taskIdOf = { it.task.taskId },
    )

    override fun updateManagedTask(
        taskId: String,
        expectedRevision: Long,
        title: String?,
        goal: String?,
        notes: String?,
        nowWallMillis: Long,
    ): BrowserTaskUpdateResult = journal.updateManagedTask(
        taskId, expectedRevision, title, goal, notes, nowWallMillis,
    )

    override fun deleteManagedTask(taskId: String, expectedRevision: Long) =
        journal.deleteManagedTask(taskId, expectedRevision)

    override fun readEventGate(
        taskId: String,
        expectedIdentity: BrowserTaskPageIdentity,
        expectedEpoch: Long?,
        allowDocumentAdvance: Boolean,
        parentEffectId: String?,
        parentIdentity: BrowserTaskPageIdentity?,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): BrowserTaskEventGate? = journal.readEventGate(
        taskId, expectedIdentity, expectedEpoch, allowDocumentAdvance,
        parentEffectId, parentIdentity, currentBootId, nowElapsedMillis,
    )

    override fun recordEventDialogResolution(
        taskId: String,
        expectedRevision: Long,
        eventId: String,
        parentEffectId: String,
        identity: BrowserTaskPageIdentity,
        decision: String,
        promptHmac: String?,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): Boolean = journal.recordEventDialogResolution(
        taskId, expectedRevision, eventId, parentEffectId, identity, decision,
        promptHmac, currentBootId, nowElapsedMillis,
    )

    override fun completeEventDialogResolution(eventId: String, resolved: Boolean) =
        journal.completeEventDialogResolution(eventId, resolved)

    override fun consumeRecoveryBinding(
        taskId: String,
        expectedRevision: Long,
        expectedOldIdentity: BrowserTaskPageIdentity?,
        newIdentity: BrowserTaskPageIdentity,
        nonce: String,
        currentBootId: String,
        nowElapsedMillis: Long,
        challengeExpiresElapsedMillis: Long,
    ): BrowserTaskRecord? = journal.consumeRecoveryBinding(
        taskId, expectedRevision, expectedOldIdentity, newIdentity, nonce, currentBootId,
        nowElapsedMillis, challengeExpiresElapsedMillis,
    )

    override fun invalidateRecoveryBinding(
        taskId: String,
        expectedRevision: Long,
        nowElapsedMillis: Long,
    ): Boolean = journal.invalidateRecoveryBinding(taskId, expectedRevision, nowElapsedMillis)

    override fun recordUserEffectVerification(
        taskId: String,
        expectedRevision: Long,
        effectId: String,
        observedIdentity: BrowserTaskPageIdentity,
        choice: BrowserUserEffectChoice,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): Boolean = journal.recordUserEffectVerification(
        taskId, expectedRevision, effectId, observedIdentity, choice, currentBootId, nowElapsedMillis,
    )

    override fun reserveEffect(entry: BrowserEffectJournalEntry) = journal.reserveEffect(entry)

    override fun finishEffect(
        requestId: String,
        payloadHmacSha256: String,
        terminalCode: String?,
        executed: Boolean?,
        nowElapsedMillis: Long,
        taskId: String?,
    ): Boolean = journal.finishEffect(
        requestId, payloadHmacSha256, terminalCode, executed, nowElapsedMillis, taskId,
    )

    override fun readTask(taskId: String, currentBootId: String, nowElapsedMillis: Long) =
        journal.readTask(taskId, currentBootId, nowElapsedMillis)

    override fun listTasks(
        currentBootId: String,
        nowElapsedMillis: Long,
        offset: Int,
        limit: Int,
    ): List<BrowserTaskRecord> = journal.listTasks(currentBootId, nowElapsedMillis, offset, limit)

    override fun taskCount(): Int = journal.taskCount()

    override fun hasUnresolvedEffects(taskId: String) = journal.hasUnresolvedEffects(taskId)

    override fun createTask(
        taskId: String,
        budget: BrowserTaskBudget,
        bootId: String,
        nowElapsedMillis: Long,
        pageIdentity: BrowserTaskPageIdentity?,
        title: String,
        goal: String,
        notes: String,
        nowWallMillis: Long,
    ): BrowserTaskCreateResult = journal.createTask(
        taskId, budget, bootId, nowElapsedMillis, pageIdentity, title, goal, notes, nowWallMillis,
    )

    override fun transitionTask(
        taskId: String,
        expectedRevision: Long,
        target: BrowserTaskStatus,
        waitReason: BrowserTaskWaitReason?,
        observedIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
        failureReason: String?,
    ): BrowserTaskUpdateResult = journal.transitionTask(
        taskId, expectedRevision, target, waitReason, observedIdentity, automationEnabled,
        ownerIsAi, nowElapsedMillis, currentBootId, failureReason,
    )

    override fun reconcileTask(
        taskId: String,
        expectedRevision: Long,
        observedIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
    ): BrowserTaskUpdateResult = journal.reconcileTask(
        taskId, expectedRevision, observedIdentity, automationEnabled, ownerIsAi,
        nowElapsedMillis, currentBootId,
    )

    private companion object {
        const val MAX_MANAGED_TASKS = 512
        const val DEFAULT_TASK_PAGE_SIZE = 100
    }
}

internal class AndroidBrowserTaskCoordinatorRuntime(context: Context) : BrowserTaskCoordinatorRuntime {
    private val appContext = context.applicationContext
    private val io: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "browser-task-journal").apply { isDaemon = true }
    }
    private val closeExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "browser-task-close").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    override fun openResources(): BrowserTaskCoordinatorResources {
        return openBrowserTaskCoordinatorResources(
            openStore = { BrowserTaskJournalStore(BrowserTaskJournal(appContext)) },
            openDigest = {
                val hmac = BrowserTaskPayloadHmac(appContext)
                hmac.digest(ByteArray(0))
                hmac::digest
            },
            readBootId = {
                requireNotNull(browserSystemBootId(appContext)) {
                    "Browser system boot identity unavailable"
                }
            },
        )
    }

    override fun postIo(task: () -> Unit) {
        tryPostIo(task)
    }

    override fun postMain(task: () -> Unit) {
        tryPostMain(task)
    }

    override fun tryPostIo(task: () -> Unit): Boolean {
        if (io.isShutdown) return false
        return try {
            io.execute { task() }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            false
        }
    }

    override fun tryPostMain(task: () -> Unit): Boolean = main.post { task() }

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun currentBootId(): String = requireNotNull(browserSystemBootId(appContext)) {
        "Browser system boot identity unavailable"
    }

    override fun closeResources(resources: BrowserTaskCoordinatorResources) {
        resources.store.close()
    }

    override fun closeResourcesAsync(resources: BrowserTaskCoordinatorResources) {
        closeExecutor.execute {
            runCatching {
                BrowserTaskCoordinatorResourceCloser.closeAfterIoDrained(io) {
                    resources.store.close()
                }
            }
        }
    }

    override fun shutdown() {
        io.shutdown()
        closeExecutor.shutdown()
    }
}
