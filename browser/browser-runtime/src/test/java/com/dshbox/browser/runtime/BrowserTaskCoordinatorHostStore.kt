package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserEffectJournalEntry
import com.dshbox.browser.api.BrowserTaskBudget
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserTaskRecord
import com.dshbox.browser.api.BrowserTaskRecoveryMetadata
import com.dshbox.browser.api.BrowserTaskStateMachine
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserTaskTransition
import com.dshbox.browser.api.BrowserTaskWaitReason
import com.dshbox.browser.api.BrowserUnknownEffectMetadata
import com.dshbox.browser.api.BrowserUserEffectChoice
import com.dshbox.browser.api.BrowserEffectKind
import com.dshbox.browser.api.BrowserEffectPhase

/** Bounded host store used only to execute the production coordinator without Android SQLite. */
internal class BrowserTaskCoordinatorHostStore(
    private val bootId: String = "boot-host",
    private val maxTasks: Int = 512,
    private val maxActiveTasks: Int = 20,
) : BrowserTaskCoordinatorStore {
    private val tasks = LinkedHashMap<String, BrowserTaskRecord>()
    private val effects = LinkedHashMap<String, BrowserEffectJournalEntry>()
    private val verifications = mutableMapOf<String, BrowserUserEffectChoice>()
    private var closed = false

    fun snapshotTasks(): List<BrowserTaskRecord> = synchronized(this) { tasks.values.toList() }
    fun snapshotEffects(): List<BrowserEffectJournalEntry> = synchronized(this) { effects.values.toList() }
    fun hasTask(taskId: String): Boolean = synchronized(this) { tasks.containsKey(taskId) }
    fun task(taskId: String): BrowserTaskRecord? = synchronized(this) { tasks[taskId] }
    fun effect(requestId: String): BrowserEffectJournalEntry? = synchronized(this) { effects[requestId] }

    fun seedRecoveryTask(taskId: String, identity: BrowserTaskPageIdentity, now: Long) {
        synchronized(this) {
            tasks[taskId] = BrowserTaskRecord(
                taskId = taskId,
                revision = 1,
                status = BrowserTaskStatus.PAUSED,
                createdElapsedMillis = now - 100,
                deadlineElapsedMillis = now + 100_000,
                bootId = bootId,
                operationLimit = 500,
                operationsUsed = 0,
                pageIdentity = identity,
                waitReason = null,
                updatedElapsedMillis = now,
                createdAtWallMillis = now,
                updatedAtWallMillis = now,
            )
        }
    }

    override fun recoverAfterProcessStart(currentBootId: String, nowElapsedMillis: Long) {
        synchronized(this) {
            effects.keys.toList().forEach { requestId ->
                val effect = effects[requestId] ?: return@forEach
                if (effect.phase == BrowserEffectPhase.INTENT) {
                    effects[requestId] = effect.copy(
                        phase = if (effect.kind == BrowserEffectKind.READ_ONLY) {
                            BrowserEffectPhase.CONFIRMED
                        } else {
                            BrowserEffectPhase.UNKNOWN
                        },
                        terminalCode = if (effect.kind == BrowserEffectKind.READ_ONLY) "READ_INTERRUPTED" else null,
                        terminalExecuted = if (effect.kind == BrowserEffectKind.READ_ONLY) false else null,
                        updatedElapsedMillis = nowElapsedMillis,
                    )
                }
            }
            val iterator = tasks.values.iterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                if (task.bootId == currentBootId && task.status in setOf(
                        BrowserTaskStatus.RUNNING,
                        BrowserTaskStatus.WAITING_PAGE,
                        BrowserTaskStatus.WAITING_USER,
                        BrowserTaskStatus.PAUSED,
                    )
                ) {
                    tasks[task.taskId] = task.copy(
                        revision = task.revision + 1,
                        status = BrowserTaskStatus.NEEDS_RECONCILIATION,
                        waitReason = BrowserTaskWaitReason.RECOVERY_CHECK,
                        updatedElapsedMillis = nowElapsedMillis,
                    )
                }
            }
        }
    }

    override fun close() {
        synchronized(this) { closed = true }
    }

    override fun listRecoveryMetadata(
        currentBootId: String,
        nowElapsedMillis: Long,
        offset: Int,
        limit: Int,
    ): List<BrowserTaskRecoveryMetadata> {
        require(offset >= 0 && limit in 1..100)
        return synchronized(this) {
            tasks.values.sortedWith(
                compareByDescending<BrowserTaskRecord> { it.createdAtWallMillis }.thenByDescending { it.taskId },
            ).drop(offset).take(limit).map { recoveryMetadata(it) }
        }
    }

    override fun findRecoveryMetadata(
        taskId: String,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): BrowserTaskRecoveryMetadata? = synchronized(this) {
        tasks[taskId]?.takeIf { it.bootId == currentBootId }?.let { recoveryMetadata(it) }
    }

    override fun updateManagedTask(
        taskId: String,
        expectedRevision: Long,
        title: String?,
        goal: String?,
        notes: String?,
        nowWallMillis: Long,
    ): BrowserTaskUpdateResult = synchronized(this) {
        val task = tasks[taskId] ?: return BrowserTaskUpdateResult.NotFound
        if (task.revision != expectedRevision) return BrowserTaskUpdateResult.StaleRevision
        val next = task.copy(
            revision = task.revision + 1,
            title = title ?: task.title,
            goal = goal ?: task.goal,
            notes = notes ?: task.notes,
            updatedAtWallMillis = maxOf(task.updatedAtWallMillis, nowWallMillis),
        )
        tasks[taskId] = next
        BrowserTaskUpdateResult.Updated(next)
    }

    override fun deleteManagedTask(taskId: String, expectedRevision: Long): BrowserTaskDeleteResult = synchronized(this) {
        val task = tasks[taskId] ?: return BrowserTaskDeleteResult.NotFound
        if (task.revision != expectedRevision) return BrowserTaskDeleteResult.StaleRevision
        if (task.status !in setOf(
                BrowserTaskStatus.BUDGET_EXHAUSTED,
                BrowserTaskStatus.COMPLETED,
                BrowserTaskStatus.FAILED,
                BrowserTaskStatus.EXPIRED,
            )
        ) return BrowserTaskDeleteResult.NotTerminal
        if (hasUnresolvedLocked(taskId)) return BrowserTaskDeleteResult.UnresolvedEffects
        tasks.remove(taskId)
        BrowserTaskDeleteResult.Deleted
    }

    override fun readEventGate(
        taskId: String,
        expectedIdentity: BrowserTaskPageIdentity,
        expectedEpoch: Long?,
        allowDocumentAdvance: Boolean,
        parentEffectId: String?,
        parentIdentity: BrowserTaskPageIdentity?,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): BrowserTaskEventGate? = synchronized(this) {
        val task = tasks[taskId] ?: return@synchronized null
        val identity = task.pageIdentity ?: return@synchronized null
        if (identity.sessionGeneration != expectedIdentity.sessionGeneration ||
            identity.controlGeneration != expectedIdentity.controlGeneration ||
            identity.pageId != expectedIdentity.pageId ||
            (!allowDocumentAdvance && identity.documentGeneration != expectedIdentity.documentGeneration) ||
            task.bootId != currentBootId ||
            task.status !in setOf(
                BrowserTaskStatus.RUNNING,
                BrowserTaskStatus.WAITING_PAGE,
                BrowserTaskStatus.WAITING_USER,
                BrowserTaskStatus.BUDGET_EXHAUSTED,
            ) ||
            nowElapsedMillis >= task.deadlineElapsedMillis
        ) return@synchronized null
        if (expectedEpoch != null && expectedEpoch != 0L) return@synchronized null
        BrowserTaskEventGate(0L, task)
    }

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
    ) = false

    override fun completeEventDialogResolution(eventId: String, resolved: Boolean) = false

    override fun consumeRecoveryBinding(
        taskId: String,
        expectedRevision: Long,
        expectedOldIdentity: BrowserTaskPageIdentity?,
        newIdentity: BrowserTaskPageIdentity,
        nonce: String,
        currentBootId: String,
        nowElapsedMillis: Long,
        challengeExpiresElapsedMillis: Long,
    ): BrowserTaskRecord? = null

    override fun invalidateRecoveryBinding(
        taskId: String,
        expectedRevision: Long,
        nowElapsedMillis: Long,
    ) = false

    override fun recordUserEffectVerification(
        taskId: String,
        expectedRevision: Long,
        effectId: String,
        observedIdentity: BrowserTaskPageIdentity,
        choice: BrowserUserEffectChoice,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): Boolean = synchronized(this) {
        val task = tasks[taskId] ?: return false
        val effect = effects[effectId] ?: return false
        if (task.revision != expectedRevision || effect.taskId != taskId ||
            effect.phase != BrowserEffectPhase.UNKNOWN || effect.bootId != currentBootId ||
            task.bootId != currentBootId || task.pageIdentity != observedIdentity
        ) return false
        verifications[effectId] = choice
        true
    }

    override fun reserveEffect(entry: BrowserEffectJournalEntry): BrowserEffectAdmission = synchronized(this) {
        val prior = effects[entry.requestId]
        if (prior != null) {
            return if (prior.payloadHmacSha256 == entry.payloadHmacSha256 &&
                prior.method == entry.method && prior.kind == entry.kind &&
                prior.pageIdentity == entry.pageIdentity &&
                (prior.taskId == entry.taskId || entry.method == "task")
            ) {
                BrowserEffectAdmission.Duplicate(prior.phase, prior.terminalCode, prior.terminalExecuted)
            } else BrowserEffectAdmission.RequestIdConflict
        }
        if (entry.phase != BrowserEffectPhase.INTENT || entry.terminalCode != null || entry.terminalExecuted != null) {
            return BrowserEffectAdmission.RequestIdConflict
        }
        val task = entry.taskId?.let { tasks[it] }
        if (task != null && entry.method != "task") {
            if (entry.kind == BrowserEffectKind.POTENTIALLY_MUTATING && hasUnresolvedLocked(task.taskId)) {
                return BrowserEffectAdmission.TaskRejected("TASK_UNKNOWN_EFFECT_UNVERIFIED")
            }
            when {
                task.bootId != entry.bootId || entry.createdElapsedMillis >= task.deadlineElapsedMillis ->
                    return BrowserEffectAdmission.TaskRejected("TASK_EXPIRED")
                task.status == BrowserTaskStatus.BUDGET_EXHAUSTED ->
                    return BrowserEffectAdmission.TaskRejected("TASK_BUDGET_EXHAUSTED")
                task.status != BrowserTaskStatus.RUNNING ->
                    return BrowserEffectAdmission.TaskRejected("TASK_NOT_RUNNING")
                task.pageIdentity == null || task.pageIdentity != entry.pageIdentity ->
                    return BrowserEffectAdmission.TaskRejected("TASK_PAGE_MISMATCH")
                task.remainingOperations <= 0 ->
                    return BrowserEffectAdmission.TaskRejected("TASK_BUDGET_EXHAUSTED")
            }
        }
        if (effects.size >= 512) return BrowserEffectAdmission.Full(BrowserJournalCapacity.TOTAL_RECEIPTS)
        effects[entry.requestId] = entry
        if (task != null && entry.method != "task") {
            val used = task.operationsUsed + 1
            tasks[task.taskId] = task.copy(
                revision = task.revision + 1,
                operationsUsed = used,
                status = if (used >= task.operationLimit) BrowserTaskStatus.BUDGET_EXHAUSTED else task.status,
                updatedElapsedMillis = entry.createdElapsedMillis,
            )
        }
        BrowserEffectAdmission.NewIntent
    }

    override fun finishEffect(
        requestId: String,
        payloadHmacSha256: String,
        terminalCode: String?,
        executed: Boolean?,
        nowElapsedMillis: Long,
        taskId: String?,
    ): Boolean = synchronized(this) {
        val prior = effects[requestId] ?: return false
        if (prior.payloadHmacSha256 != payloadHmacSha256 || prior.phase != BrowserEffectPhase.INTENT) return false
        val confirmed = nowElapsedMillis < prior.deadlineElapsedMillis &&
            terminalCode != null && terminalCode != "UNKNOWN" && executed != null &&
            terminalCode.matches(Regex("[A-Z0-9_]{1,48}"))
        effects[requestId] = prior.copy(
            taskId = taskId ?: prior.taskId,
            phase = if (confirmed) BrowserEffectPhase.CONFIRMED else BrowserEffectPhase.UNKNOWN,
            terminalCode = if (confirmed) terminalCode else null,
            terminalExecuted = if (confirmed) executed else null,
            updatedElapsedMillis = nowElapsedMillis,
        )
        val affectedTaskId = taskId ?: prior.taskId
        if (!confirmed && prior.kind == BrowserEffectKind.POTENTIALLY_MUTATING && affectedTaskId != null) {
            val task = tasks[affectedTaskId]
            if (task != null && task.status in setOf(
                    BrowserTaskStatus.RUNNING,
                    BrowserTaskStatus.WAITING_PAGE,
                    BrowserTaskStatus.WAITING_USER,
                    BrowserTaskStatus.PAUSED,
                )
            ) {
                tasks[affectedTaskId] = task.copy(
                    revision = task.revision + 1,
                    status = BrowserTaskStatus.NEEDS_RECONCILIATION,
                    waitReason = BrowserTaskWaitReason.RECOVERY_CHECK,
                    updatedElapsedMillis = nowElapsedMillis,
                )
            }
        }
        true
    }

    override fun readTask(taskId: String, currentBootId: String, nowElapsedMillis: Long): BrowserTaskRecord? = synchronized(this) {
        val task = tasks[taskId] ?: return null
        if (task.status !in setOf(
                BrowserTaskStatus.COMPLETED,
                BrowserTaskStatus.FAILED,
                BrowserTaskStatus.EXPIRED,
            )
        ) {
            if (task.bootId != currentBootId || nowElapsedMillis >= task.deadlineElapsedMillis) {
                tasks[taskId] = task.copy(
                    revision = task.revision + 1,
                    status = BrowserTaskStatus.EXPIRED,
                    waitReason = null,
                    updatedElapsedMillis = nowElapsedMillis,
                )
            } else if (task.operationsUsed >= task.operationLimit && task.status != BrowserTaskStatus.BUDGET_EXHAUSTED) {
                tasks[taskId] = task.copy(
                    revision = task.revision + 1,
                    status = BrowserTaskStatus.BUDGET_EXHAUSTED,
                    waitReason = null,
                    updatedElapsedMillis = nowElapsedMillis,
                )
            }
        }
        tasks[taskId]
    }

    override fun listTasks(
        currentBootId: String,
        nowElapsedMillis: Long,
        offset: Int,
        limit: Int,
    ): List<BrowserTaskRecord> {
        require(offset >= 0 && limit in 1..100)
        return synchronized(this) {
            tasks.values.sortedWith(
                compareByDescending<BrowserTaskRecord> { it.createdAtWallMillis }.thenByDescending { it.taskId },
            ).drop(offset).take(limit)
        }
    }

    override fun taskCount(): Int = synchronized(this) { tasks.size }

    override fun hasUnresolvedEffects(taskId: String): Boolean = synchronized(this) { hasUnresolvedLocked(taskId) }

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
    ): BrowserTaskCreateResult = synchronized(this) {
        if (closed || tasks.containsKey(taskId)) return BrowserTaskCreateResult.Conflict
        if (tasks.size >= maxTasks || tasks.values.count {
                it.status !in setOf(
                    BrowserTaskStatus.BUDGET_EXHAUSTED,
                    BrowserTaskStatus.COMPLETED,
                    BrowserTaskStatus.FAILED,
                    BrowserTaskStatus.EXPIRED,
                )
            } >= maxActiveTasks
        ) return BrowserTaskCreateResult.Full
        val record = BrowserTaskRecord(
            taskId = taskId,
            revision = 0,
            status = BrowserTaskStatus.RUNNING,
            createdElapsedMillis = nowElapsedMillis,
            deadlineElapsedMillis = nowElapsedMillis + budget.durationMillis,
            bootId = bootId,
            operationLimit = budget.operationLimit,
            operationsUsed = 0,
            pageIdentity = pageIdentity,
            waitReason = null,
            updatedElapsedMillis = nowElapsedMillis,
            title = title,
            goal = goal,
            notes = notes,
            createdAtWallMillis = nowWallMillis,
            updatedAtWallMillis = nowWallMillis,
        )
        tasks[taskId] = record
        BrowserTaskCreateResult.Created(record)
    }

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
    ): BrowserTaskUpdateResult = synchronized(this) {
        val task = tasks[taskId] ?: return BrowserTaskUpdateResult.NotFound
        if (task.revision != expectedRevision) return BrowserTaskUpdateResult.StaleRevision
        if (target in setOf(BrowserTaskStatus.RUNNING, BrowserTaskStatus.COMPLETED) &&
            hasUnresolvedLocked(taskId)
        ) return BrowserTaskUpdateResult.UnknownEffectUnverified
        if (failureReason != null && (target != BrowserTaskStatus.FAILED || failureReason.isBlank() ||
                failureReason.length > BrowserTaskRecord.MAX_FAILURE_REASON_LENGTH)
        ) return BrowserTaskUpdateResult.InvalidTransition
        if (!BrowserTaskStateMachine.canTransition(task.status, target)) return BrowserTaskUpdateResult.InvalidTransition
        if (target != BrowserTaskStatus.FAILED && (task.bootId != currentBootId ||
                nowElapsedMillis >= task.deadlineElapsedMillis)
        ) return BrowserTaskUpdateResult.Expired
        if (target == BrowserTaskStatus.RUNNING) {
            when (BrowserTaskStateMachine.validateResume(
                task, expectedRevision, observedIdentity, automationEnabled, ownerIsAi,
                nowElapsedMillis, currentBootId,
            )) {
                BrowserTaskTransition.Applied -> Unit
                BrowserTaskTransition.StaleRevision -> return BrowserTaskUpdateResult.StaleRevision
                BrowserTaskTransition.Expired -> return BrowserTaskUpdateResult.Expired
                BrowserTaskTransition.PageOrAuthorizationMismatch ->
                    return BrowserTaskUpdateResult.AuthorizationOrPageMismatch
                BrowserTaskTransition.BudgetExhausted -> return BrowserTaskUpdateResult.BudgetExhausted
                BrowserTaskTransition.Invalid -> return BrowserTaskUpdateResult.InvalidTransition
            }
        }
        if (target != BrowserTaskStatus.FAILED && task.remainingOperations <= 0 &&
            target !in setOf(BrowserTaskStatus.COMPLETED, BrowserTaskStatus.BUDGET_EXHAUSTED)
        ) return BrowserTaskUpdateResult.BudgetExhausted
        if ((target in setOf(
                BrowserTaskStatus.WAITING_PAGE,
                BrowserTaskStatus.WAITING_USER,
                BrowserTaskStatus.NEEDS_RECONCILIATION,
            )) != (waitReason != null)
        ) return BrowserTaskUpdateResult.InvalidTransition
        val next = task.copy(
            revision = task.revision + 1,
            status = target,
            waitReason = waitReason,
            failureReason = if (target == BrowserTaskStatus.FAILED) failureReason else null,
            updatedElapsedMillis = nowElapsedMillis,
            updatedAtWallMillis = maxOf(task.updatedAtWallMillis, nowWall()),
        )
        tasks[taskId] = next
        BrowserTaskUpdateResult.Updated(next)
    }

    override fun reconcileTask(
        taskId: String,
        expectedRevision: Long,
        observedIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
    ): BrowserTaskUpdateResult = synchronized(this) {
        val task = tasks[taskId] ?: return BrowserTaskUpdateResult.NotFound
        if (task.bootId != currentBootId || nowElapsedMillis >= task.deadlineElapsedMillis) {
            return BrowserTaskUpdateResult.Expired
        }
        when (BrowserTaskStateMachine.validateReconcile(
            task, expectedRevision, observedIdentity, automationEnabled, ownerIsAi,
            nowElapsedMillis, currentBootId,
        )) {
            BrowserTaskTransition.Applied -> Unit
            BrowserTaskTransition.StaleRevision -> return BrowserTaskUpdateResult.StaleRevision
            BrowserTaskTransition.Expired -> return BrowserTaskUpdateResult.Expired
            BrowserTaskTransition.PageOrAuthorizationMismatch ->
                return BrowserTaskUpdateResult.AuthorizationOrPageMismatch
            BrowserTaskTransition.BudgetExhausted -> return BrowserTaskUpdateResult.BudgetExhausted
            BrowserTaskTransition.Invalid -> return BrowserTaskUpdateResult.RecoveryNotAllowed
        }
        val next = task.copy(
            revision = task.revision + 1,
            status = BrowserTaskStatus.PAUSED,
            pageIdentity = observedIdentity,
            waitReason = null,
            updatedElapsedMillis = nowElapsedMillis,
            updatedAtWallMillis = maxOf(task.updatedAtWallMillis, nowWall()),
        )
        tasks[taskId] = next
        BrowserTaskUpdateResult.Updated(next)
    }

    private fun recoveryMetadata(task: BrowserTaskRecord): BrowserTaskRecoveryMetadata {
        val unknown = effects.values.filter {
            it.taskId == task.taskId && it.kind == BrowserEffectKind.POTENTIALLY_MUTATING &&
                it.phase in setOf(BrowserEffectPhase.INTENT, BrowserEffectPhase.UNKNOWN)
        }.take(128).map {
            BrowserUnknownEffectMetadata(
                requestId = it.requestId,
                method = it.method,
                createdElapsedMillis = it.createdElapsedMillis,
                phase = it.phase,
                hasUserVerification = verifications.containsKey(it.requestId),
                lastUserChoice = verifications[it.requestId],
            )
        }
        return BrowserTaskRecoveryMetadata(task, unknown, task.pageIdentity)
    }

    private fun hasUnresolvedLocked(taskId: String): Boolean = effects.values.any {
        it.taskId == taskId && it.kind == BrowserEffectKind.POTENTIALLY_MUTATING &&
            it.phase in setOf(BrowserEffectPhase.INTENT, BrowserEffectPhase.UNKNOWN)
    }

    private fun hasUnverifiedUnknownLocked(taskId: String): Boolean = effects.values.any {
        it.taskId == taskId && it.kind == BrowserEffectKind.POTENTIALLY_MUTATING &&
        it.phase in setOf(BrowserEffectPhase.INTENT, BrowserEffectPhase.UNKNOWN) &&
            verifications[it.requestId] !in setOf(
                BrowserUserEffectChoice.OCCURRED,
                BrowserUserEffectChoice.NOT_OCCURRED,
            )
    }

    private fun nowWall(): Long = System.currentTimeMillis()
}
