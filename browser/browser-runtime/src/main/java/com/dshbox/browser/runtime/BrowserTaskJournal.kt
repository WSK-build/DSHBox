package com.dshbox.browser.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.provider.Settings
import com.dshbox.browser.api.BrowserEffectJournalEntry
import com.dshbox.browser.api.BrowserEffectKind
import com.dshbox.browser.api.BrowserEffectPhase
import com.dshbox.browser.api.BrowserTaskBudget
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserTaskRecord
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserTaskWaitReason
import com.dshbox.browser.api.BrowserTaskStateMachine
import com.dshbox.browser.api.BrowserTaskTransition
import com.dshbox.browser.api.BrowserEffectVerification
import com.dshbox.browser.api.BrowserEffectVerificationResult
import com.dshbox.browser.api.BrowserUserEffectChoice
import com.dshbox.browser.api.BrowserTaskRecoveryMetadata
import com.dshbox.browser.api.BrowserUnknownEffectMetadata
import java.io.File

internal sealed interface BrowserTaskCreateResult {
    data class Created(val record: BrowserTaskRecord) : BrowserTaskCreateResult
    data object Conflict : BrowserTaskCreateResult
    data object Full : BrowserTaskCreateResult
}

internal sealed interface BrowserEffectAdmission {
    data object NewIntent : BrowserEffectAdmission
    data class Duplicate(val phase: BrowserEffectPhase, val terminalCode: String?, val terminalExecuted: Boolean?) : BrowserEffectAdmission
    data object RequestIdConflict : BrowserEffectAdmission
    data class Full(val capacity: BrowserJournalCapacity) : BrowserEffectAdmission
    data class TaskRejected(val code: String) : BrowserEffectAdmission
}

internal enum class BrowserJournalCapacity {
    UNKNOWN_TOMBSTONES,
    TOTAL_RECEIPTS,
}

internal sealed interface BrowserTaskUpdateResult {
    data class Updated(val record: BrowserTaskRecord) : BrowserTaskUpdateResult
    data object NotFound : BrowserTaskUpdateResult
    data object InvalidTransition : BrowserTaskUpdateResult
    data object StaleRevision : BrowserTaskUpdateResult
    data object AuthorizationOrPageMismatch : BrowserTaskUpdateResult
    data object Expired : BrowserTaskUpdateResult
    data object BudgetExhausted : BrowserTaskUpdateResult
    data object RecoveryNotAllowed : BrowserTaskUpdateResult
    data object UnknownEffectUnverified : BrowserTaskUpdateResult
}

internal enum class BrowserTaskDeleteResult { Deleted, NotFound, StaleRevision, NotTerminal, UnresolvedEffects }

internal data class BrowserTaskEventGate(val bindingEpoch: Long, val task: BrowserTaskRecord)

/** Stable across app-process restarts and different after a device reboot. Null means fail closed. */
internal fun browserSystemBootId(context: Context): String? {
    val bootCount = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrNull()
    if (bootCount != null && bootCount >= 0) return "boot_$bootCount"
    return runCatching {
        File("/proc/sys/kernel/random/boot_id").readText(Charsets.US_ASCII).trim()
            .takeIf { it.matches(Regex("[A-Fa-f0-9-]{16,64}")) }
    }.getOrNull()
}

/** Durable, bounded task checkpoints and request replay receipts. No request or response bodies are stored. */
internal class BrowserTaskJournal(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE tasks (
                task_id TEXT PRIMARY KEY NOT NULL,
                revision INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_elapsed INTEGER NOT NULL,
                deadline_elapsed INTEGER NOT NULL,
                boot_id TEXT NOT NULL,
                operation_limit INTEGER NOT NULL,
                operations_used INTEGER NOT NULL,
                session_generation INTEGER,
                control_generation INTEGER,
                page_id TEXT,
                document_generation INTEGER,
                wait_reason TEXT,
                updated_elapsed INTEGER NOT NULL,
                title TEXT NOT NULL DEFAULT '', goal TEXT NOT NULL DEFAULT '', notes TEXT NOT NULL DEFAULT '',
                failure_reason TEXT,
                created_wall INTEGER NOT NULL DEFAULT 0, updated_wall INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE effects (
                request_id TEXT PRIMARY KEY NOT NULL,
                payload_hmac_sha256 TEXT NOT NULL,
                method TEXT NOT NULL,
                kind TEXT NOT NULL,
                task_id TEXT,
                session_generation INTEGER,
                control_generation INTEGER,
                page_id TEXT,
                document_generation INTEGER,
                deadline_elapsed INTEGER NOT NULL,
                boot_id TEXT NOT NULL,
                phase TEXT NOT NULL,
                created_elapsed INTEGER NOT NULL,
                updated_elapsed INTEGER NOT NULL,
                terminal_code TEXT,
                terminal_executed INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX effects_phase_created ON effects(phase, created_elapsed)")
        db.execSQL("CREATE INDEX effects_task_id ON effects(task_id)")
        createRecoveryTables(db)
        createEventAuditTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) throw IllegalStateException("Unsupported Browser task journal schema upgrade")
        if (oldVersion < 2) createRecoveryTables(db)
        if (oldVersion < 3) createEventAuditTables(db)
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN title TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tasks ADD COLUMN goal TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tasks ADD COLUMN notes TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tasks ADD COLUMN created_wall INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN updated_wall INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) db.execSQL("ALTER TABLE tasks ADD COLUMN failure_reason TEXT")
    }

    @Synchronized
    fun createTask(
        taskId: String,
        budget: BrowserTaskBudget,
        bootId: String,
        nowElapsedMillis: Long,
        pageIdentity: BrowserTaskPageIdentity?,
        title: String = "",
        goal: String = "",
        notes: String = "",
        nowWallMillis: Long = System.currentTimeMillis(),
    ): BrowserTaskCreateResult = writableDatabase.inTransaction { db ->
        require(title.length <= 120 && goal.length <= 4000 && notes.length <= 2000 && nowWallMillis >= 0)
        pruneConfirmedAndFinished(db, bootId, nowElapsedMillis)
        expireStaleTasks(db, bootId, nowElapsedMillis)
        pruneTasks(db, bootId, nowElapsedMillis)
        if (getTask(db, taskId) != null) return@inTransaction BrowserTaskCreateResult.Conflict
        if (count(db, "tasks") >= MAX_MANAGED_TASKS || countActiveTasks(db) >= MAX_ACTIVE_TASKS)
            return@inTransaction BrowserTaskCreateResult.Full
        val deadline = saturatingAdd(nowElapsedMillis, budget.durationMillis)
        val record = BrowserTaskRecord(
            taskId = taskId,
            revision = 0,
            status = BrowserTaskStatus.RUNNING,
            createdElapsedMillis = nowElapsedMillis,
            deadlineElapsedMillis = deadline,
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
        check(db.insertOrThrow("tasks", null, record.toValues()) >= 0)
        BrowserTaskCreateResult.Created(record)
    }

    @Synchronized
    fun getTask(taskId: String): BrowserTaskRecord? = getTask(readableDatabase, taskId)

    /** Read-only event continuation gate; does not reserve another effect or operation budget. */
    fun readEventGate(
        taskId: String, expectedIdentity: BrowserTaskPageIdentity, expectedEpoch: Long?,
        allowDocumentAdvance: Boolean, parentEffectId: String?, parentIdentity: BrowserTaskPageIdentity?,
        currentBootId: String, nowElapsedMillis: Long,
    ): BrowserTaskEventGate? = readableDatabase.inTransaction { db ->
        val task = getTask(db, taskId) ?: return@inTransaction null
        val bound = task.pageIdentity ?: return@inTransaction null
        val stableIdentityMatches = bound.sessionGeneration == expectedIdentity.sessionGeneration &&
            bound.controlGeneration == expectedIdentity.controlGeneration && bound.pageId == expectedIdentity.pageId
        val identityMatches = stableIdentityMatches && (allowDocumentAdvance || bound.documentGeneration == expectedIdentity.documentGeneration)
        if (!identityMatches || task.bootId != currentBootId || task.status !in setOf(
                BrowserTaskStatus.RUNNING, BrowserTaskStatus.WAITING_PAGE, BrowserTaskStatus.WAITING_USER,
                BrowserTaskStatus.BUDGET_EXHAUSTED,
            ) || nowElapsedMillis < task.createdElapsedMillis || nowElapsedMillis < task.updatedElapsedMillis ||
            nowElapsedMillis >= task.deadlineElapsedMillis
        ) return@inTransaction null
        val epoch = db.rawQuery(
            "SELECT COALESCE(MAX(task_revision),0) FROM task_binding_audit WHERE task_id=?", arrayOf(taskId),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        if (expectedEpoch != null && epoch != expectedEpoch) return@inTransaction null
        if (parentEffectId != null) {
            val parent = getEffect(db, parentEffectId) ?: return@inTransaction null
            if (parent.taskId != taskId || parent.bootId != currentBootId ||
                parent.kind != BrowserEffectKind.POTENTIALLY_MUTATING || parent.pageIdentity != parentIdentity ||
                parent.method !in setOf("click", "navigate", "event") ||
                parent.phase !in setOf(BrowserEffectPhase.INTENT, BrowserEffectPhase.CONFIRMED) ||
                parent.phase == BrowserEffectPhase.INTENT && nowElapsedMillis >= parent.deadlineElapsedMillis ||
                task.status == BrowserTaskStatus.BUDGET_EXHAUSTED && parent.phase !in setOf(BrowserEffectPhase.INTENT, BrowserEffectPhase.CONFIRMED)
            ) return@inTransaction null
            val otherPending = db.rawQuery(
                "SELECT COUNT(*) FROM effects WHERE task_id=? AND kind='potentially_mutating' AND phase IN (?,?) AND request_id!=?",
                arrayOf(taskId, BrowserEffectPhase.INTENT.wireValue, BrowserEffectPhase.UNKNOWN.wireValue, parentEffectId),
            ).use { it.moveToFirst() && it.getInt(0) > 0 }
            if (otherPending) return@inTransaction null
        } else if (task.status == BrowserTaskStatus.BUDGET_EXHAUSTED) {
            return@inTransaction null
        }
        BrowserTaskEventGate(epoch, task)
    }

    /** One-use audit for resolving a captured JS dialog; payload text is never persisted. */
    @Synchronized
    fun recordEventDialogResolution(
        taskId: String, expectedRevision: Long, eventId: String, parentEffectId: String,
        identity: BrowserTaskPageIdentity, decision: String, promptHmac: String?,
        currentBootId: String, nowElapsedMillis: Long,
    ): Boolean = writableDatabase.inTransaction { db ->
        pruneRecoveryAudit(db, nowElapsedMillis)
        if (count(db, "event_dialog_resolution_audit") >= MAX_EVENT_DIALOG_AUDIT) return@inTransaction false
        val task = getTask(db, taskId) ?: return@inTransaction false
        val parent = getEffect(db, parentEffectId) ?: return@inTransaction false
        if (task.revision != expectedRevision || task.status !in setOf(
                BrowserTaskStatus.RUNNING, BrowserTaskStatus.BUDGET_EXHAUSTED,
            ) || task.bootId != currentBootId || task.pageIdentity != identity ||
            nowElapsedMillis < task.updatedElapsedMillis || nowElapsedMillis >= task.deadlineElapsedMillis ||
            parent.taskId != taskId || parent.method !in setOf("click", "navigate") ||
            parent.kind != BrowserEffectKind.POTENTIALLY_MUTATING || parent.phase != BrowserEffectPhase.INTENT ||
            parent.pageIdentity != identity || parent.bootId != currentBootId ||
            nowElapsedMillis >= parent.deadlineElapsedMillis ||
            !eventId.matches(Regex("[a-f0-9]{32}")) ||
            decision !in setOf("accept", "dismiss", "prompt") ||
            (decision == "prompt") != (promptHmac != null) ||
            promptHmac?.matches(Regex("[a-f0-9]{64}")) == false
        ) return@inTransaction false
        val otherPending = db.rawQuery(
            "SELECT COUNT(*) FROM effects WHERE task_id=? AND kind='potentially_mutating' AND phase IN (?,?) AND request_id!=?",
            arrayOf(taskId, BrowserEffectPhase.INTENT.wireValue, BrowserEffectPhase.UNKNOWN.wireValue, parentEffectId),
        ).use { it.moveToFirst() && it.getInt(0) > 0 }
        if (otherPending) return@inTransaction false
        val values = ContentValues().apply {
            put("event_id", eventId); put("task_id", taskId); put("parent_effect_id", parentEffectId)
            put("task_revision", task.revision); put("session_generation", identity.sessionGeneration)
            put("control_generation", identity.controlGeneration); put("page_id", identity.pageId)
            put("document_generation", identity.documentGeneration); put("decision", decision)
            if (promptHmac == null) putNull("prompt_hmac") else put("prompt_hmac", promptHmac)
            put("boot_id", currentBootId); put("created_elapsed", nowElapsedMillis)
        }
        db.insert("event_dialog_resolution_audit", null, values) != -1L
    }

    /** Task status reads also enforce its absolute monotonic budget and boot boundary. */
    @Synchronized
    fun readTask(taskId: String, currentBootId: String, nowElapsedMillis: Long): BrowserTaskRecord? =
        writableDatabase.inTransaction { db ->
            val task = getTask(db, taskId) ?: return@inTransaction null
            if (task.status !in TERMINAL_TASK_STATES) {
                when {
                    task.bootId != currentBootId || nowElapsedMillis >= task.deadlineElapsedMillis ->
                        expireTask(db, task, currentBootId, nowElapsedMillis)
                    task.operationsUsed >= task.operationLimit && task.status != BrowserTaskStatus.BUDGET_EXHAUSTED ->
                        markBudgetExhausted(db, task, nowElapsedMillis)
                }
            }
            getTask(db, taskId)
        }

    @Synchronized
    fun listTasks(): List<BrowserTaskRecord> = readableDatabase.rawQuery(
        "SELECT * FROM tasks ORDER BY created_wall DESC, task_id DESC LIMIT ?",
        arrayOf(DEFAULT_TASK_PAGE_SIZE.toString()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTask()) } }

    @Synchronized
    fun listTasks(currentBootId: String, nowElapsedMillis: Long, offset: Int = 0, limit: Int = DEFAULT_TASK_PAGE_SIZE): List<BrowserTaskRecord> = writableDatabase.inTransaction { db ->
        require(offset >= 0 && limit in 1..MAX_TASK_PAGE_SIZE)
        expireStaleTasks(db, currentBootId, nowElapsedMillis)
        db.query("tasks", null, null, null, null, null, "created_wall DESC, task_id DESC", "$limit OFFSET $offset")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTask()) } }
    }

    @Synchronized
    fun taskCount(): Int = count(readableDatabase, "tasks")

    @Synchronized
    fun hasUnresolvedEffects(taskId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM effects WHERE task_id=? AND kind='potentially_mutating' AND phase IN (?,?) LIMIT 1",
        arrayOf(taskId, BrowserEffectPhase.INTENT.wireValue, BrowserEffectPhase.UNKNOWN.wireValue),
    ).use { it.moveToFirst() }

    @Synchronized
    fun updateManagedTask(taskId: String, expectedRevision: Long, title: String?, goal: String?, notes: String?, nowWallMillis: Long): BrowserTaskUpdateResult =
        writableDatabase.inTransaction { db ->
            require(expectedRevision >= 0 && (title == null || title.length <= 120) &&
                (goal == null || goal.length <= 4000) && (notes == null || notes.length <= 2000) && nowWallMillis >= 0)
            val task = getTask(db, taskId) ?: return@inTransaction BrowserTaskUpdateResult.NotFound
            if (task.revision != expectedRevision) return@inTransaction BrowserTaskUpdateResult.StaleRevision
            val next = task.copy(revision = task.revision + 1, title = title ?: task.title, goal = goal ?: task.goal,
            notes = notes ?: task.notes, updatedAtWallMillis = monotonicWall(task, nowWallMillis))
            if (db.update("tasks", next.toValues(), "task_id=? AND revision=?", arrayOf(taskId, expectedRevision.toString())) != 1)
                return@inTransaction BrowserTaskUpdateResult.StaleRevision
            BrowserTaskUpdateResult.Updated(next)
        }

    @Synchronized
    fun deleteManagedTask(taskId: String, expectedRevision: Long): BrowserTaskDeleteResult = writableDatabase.inTransaction { db ->
        val task = getTask(db, taskId) ?: return@inTransaction BrowserTaskDeleteResult.NotFound
        if (task.revision != expectedRevision) return@inTransaction BrowserTaskDeleteResult.StaleRevision
        if (task.status !in DELETABLE_TASK_STATES) return@inTransaction BrowserTaskDeleteResult.NotTerminal
        val unresolved = db.rawQuery("SELECT 1 FROM effects WHERE task_id=? AND kind='potentially_mutating' AND phase IN (?,?) LIMIT 1",
            arrayOf(taskId, BrowserEffectPhase.INTENT.wireValue, BrowserEffectPhase.UNKNOWN.wireValue)).use { it.moveToFirst() }
        if (unresolved) return@inTransaction BrowserTaskDeleteResult.UnresolvedEffects
        if (db.delete("tasks", "task_id=? AND revision=?", arrayOf(taskId, expectedRevision.toString())) != 1)
            return@inTransaction BrowserTaskDeleteResult.StaleRevision
        BrowserTaskDeleteResult.Deleted
    }

    /** The budget is consumed atomically; calls never extend the task deadline. */
    @Synchronized
    fun consumeOperation(
        taskId: String,
        expectedRevision: Long,
        nowElapsedMillis: Long,
        currentBootId: String,
    ): BrowserTaskRecord? = writableDatabase.inTransaction { db ->
        val task = getTask(db, taskId) ?: return@inTransaction null
        if (task.revision != expectedRevision || task.status != BrowserTaskStatus.RUNNING ||
            task.bootId != currentBootId || nowElapsedMillis >= task.deadlineElapsedMillis ||
            task.operationsUsed >= task.operationLimit
        ) return@inTransaction null
        val operationsUsed = task.operationsUsed + 1
        val next = task.copy(
            revision = task.revision + 1,
            operationsUsed = operationsUsed,
            status = if (operationsUsed >= task.operationLimit) BrowserTaskStatus.BUDGET_EXHAUSTED else task.status,
            updatedElapsedMillis = nowElapsedMillis,
            updatedAtWallMillis = monotonicWall(task, System.currentTimeMillis()),
        )
        check(db.update("tasks", next.toValues(), "task_id=? AND revision=?", arrayOf(taskId, expectedRevision.toString())) == 1)
        next
    }

    /** State changes are revision-checked; returning to RUNNING requires fresh page and owner evidence. */
    @Synchronized
    fun transitionTask(
        taskId: String,
        expectedRevision: Long,
        target: BrowserTaskStatus,
        waitReason: BrowserTaskWaitReason?,
        observedIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
        failureReason: String? = null,
    ): BrowserTaskUpdateResult = writableDatabase.inTransaction { db ->
        val task = getTask(db, taskId) ?: return@inTransaction BrowserTaskUpdateResult.NotFound
        if (task.revision != expectedRevision) return@inTransaction BrowserTaskUpdateResult.StaleRevision
        if (failureReason != null && (
                target != BrowserTaskStatus.FAILED ||
                    failureReason.isBlank() ||
                    failureReason.length > BrowserTaskRecord.MAX_FAILURE_REASON_LENGTH
            )
        ) return@inTransaction BrowserTaskUpdateResult.InvalidTransition
        if (target in setOf(BrowserTaskStatus.RUNNING, BrowserTaskStatus.COMPLETED) && hasUnverifiedUnknown(db, task)) {
            return@inTransaction BrowserTaskUpdateResult.UnknownEffectUnverified
        }
        if (!BrowserTaskStateMachine.canTransition(task.status, target)) {
            return@inTransaction BrowserTaskUpdateResult.InvalidTransition
        }
        if (target != BrowserTaskStatus.FAILED &&
            (task.bootId != currentBootId || nowElapsedMillis >= task.deadlineElapsedMillis)
        ) {
            expireTask(db, task, currentBootId, nowElapsedMillis)
            return@inTransaction BrowserTaskUpdateResult.Expired
        }
        if (target == BrowserTaskStatus.RUNNING) {
            val effectiveTask = task
            when (BrowserTaskStateMachine.validateResume(
                effectiveTask, expectedRevision, observedIdentity, automationEnabled, ownerIsAi, nowElapsedMillis, currentBootId,
            )) {
                BrowserTaskTransition.Applied -> Unit
                BrowserTaskTransition.StaleRevision -> return@inTransaction BrowserTaskUpdateResult.StaleRevision
                BrowserTaskTransition.Expired -> return@inTransaction BrowserTaskUpdateResult.Expired
                BrowserTaskTransition.PageOrAuthorizationMismatch -> return@inTransaction BrowserTaskUpdateResult.AuthorizationOrPageMismatch
                BrowserTaskTransition.BudgetExhausted -> {
                    markBudgetExhausted(db, task, nowElapsedMillis)
                    return@inTransaction BrowserTaskUpdateResult.BudgetExhausted
                }
                BrowserTaskTransition.Invalid -> return@inTransaction BrowserTaskUpdateResult.InvalidTransition
            }
        }
        if (target == BrowserTaskStatus.EXPIRED) return@inTransaction BrowserTaskUpdateResult.InvalidTransition
        if (target != BrowserTaskStatus.FAILED && task.remainingOperations <= 0 &&
            target !in setOf(BrowserTaskStatus.COMPLETED, BrowserTaskStatus.BUDGET_EXHAUSTED)
        ) {
            markBudgetExhausted(db, task, nowElapsedMillis)
            return@inTransaction BrowserTaskUpdateResult.BudgetExhausted
        }
        if ((target in setOf(BrowserTaskStatus.WAITING_PAGE, BrowserTaskStatus.WAITING_USER, BrowserTaskStatus.NEEDS_RECONCILIATION)) !=
            (waitReason != null)
        ) return@inTransaction BrowserTaskUpdateResult.InvalidTransition
        val next = task.copy(
            revision = task.revision + 1,
            status = target,
            waitReason = waitReason,
            failureReason = if (target == BrowserTaskStatus.FAILED) failureReason else null,
            updatedElapsedMillis = if (task.bootId == currentBootId) nowElapsedMillis else task.updatedElapsedMillis,
            updatedAtWallMillis = monotonicWall(task, System.currentTimeMillis()),
        )
        if (db.update("tasks", next.toValues(), "task_id=? AND revision=?", arrayOf(taskId, expectedRevision.toString())) != 1) {
            return@inTransaction BrowserTaskUpdateResult.StaleRevision
        }
        BrowserTaskUpdateResult.Updated(next)
    }

    /** Refreshes a checkpoint after a fresh observation; never dispatches browser actions or clears UNKNOWN receipts. */
    @Synchronized
    fun reconcileTask(
        taskId: String,
        expectedRevision: Long,
        observedIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
    ): BrowserTaskUpdateResult = writableDatabase.inTransaction { db ->
        val task = getTask(db, taskId) ?: return@inTransaction BrowserTaskUpdateResult.NotFound
        if (task.bootId != currentBootId || nowElapsedMillis >= task.deadlineElapsedMillis) {
            expireTask(db, task, currentBootId, nowElapsedMillis)
            return@inTransaction BrowserTaskUpdateResult.Expired
        }
        when (BrowserTaskStateMachine.validateReconcile(
            task, expectedRevision, observedIdentity, automationEnabled, ownerIsAi, nowElapsedMillis, currentBootId,
        )) {
            BrowserTaskTransition.StaleRevision -> return@inTransaction BrowserTaskUpdateResult.StaleRevision
            BrowserTaskTransition.Expired -> {
                expireTask(db, task, currentBootId, nowElapsedMillis)
                return@inTransaction BrowserTaskUpdateResult.Expired
            }
            BrowserTaskTransition.PageOrAuthorizationMismatch -> return@inTransaction BrowserTaskUpdateResult.AuthorizationOrPageMismatch
            BrowserTaskTransition.BudgetExhausted -> {
                markBudgetExhausted(db, task, nowElapsedMillis)
                return@inTransaction BrowserTaskUpdateResult.BudgetExhausted
            }
            BrowserTaskTransition.Invalid -> return@inTransaction BrowserTaskUpdateResult.RecoveryNotAllowed
            BrowserTaskTransition.Applied -> Unit
        }
        val next = task.copy(
            revision = task.revision + 1,
            status = BrowserTaskStatus.PAUSED,
            pageIdentity = observedIdentity,
            waitReason = null,
            updatedElapsedMillis = nowElapsedMillis,
            updatedAtWallMillis = monotonicWall(task, System.currentTimeMillis()),
        )
        if (db.update("tasks", next.toValues(), "task_id=? AND revision=?", arrayOf(taskId, expectedRevision.toString())) != 1) {
            return@inTransaction BrowserTaskUpdateResult.StaleRevision
        }
        BrowserTaskUpdateResult.Updated(next)
    }

    /** Creates the durable intent before dispatch. A duplicate ID is always returned without dispatch. */
    @Synchronized
    fun reserveEffect(entry: BrowserEffectJournalEntry): BrowserEffectAdmission = writableDatabase.inTransaction { db ->
        pruneConfirmedAndFinished(db, entry.bootId, entry.createdElapsedMillis)
        expireStaleTasks(db, entry.bootId, entry.createdElapsedMillis)
        pruneTasks(db, entry.bootId, entry.createdElapsedMillis)
        val prior = getEffect(db, entry.requestId)
        if (prior != null) {
            return@inTransaction if (prior.payloadHmacSha256 == entry.payloadHmacSha256 && prior.method == entry.method &&
                prior.kind == entry.kind && prior.pageIdentity == entry.pageIdentity &&
                (prior.taskId == entry.taskId || entry.method == "task")
            ) {
                BrowserEffectAdmission.Duplicate(prior.phase, prior.terminalCode, prior.terminalExecuted)
            } else BrowserEffectAdmission.RequestIdConflict
        }
        if (entry.phase != BrowserEffectPhase.INTENT || entry.terminalCode != null || entry.terminalExecuted != null) {
            return@inTransaction BrowserEffectAdmission.RequestIdConflict
        }
        val task = entry.taskId?.let { taskId ->
            getTask(db, taskId) ?: return@inTransaction BrowserEffectAdmission.TaskRejected("TASK_NOT_FOUND")
        }
        if (task != null && entry.method != "task") {
            if (entry.kind == BrowserEffectKind.POTENTIALLY_MUTATING && hasUnverifiedUnknown(db, task)) {
                return@inTransaction BrowserEffectAdmission.TaskRejected("TASK_UNKNOWN_EFFECT_UNVERIFIED")
            }
            when {
                task.bootId != entry.bootId || entry.createdElapsedMillis >= task.deadlineElapsedMillis -> {
                    expireTask(db, task, entry.bootId, entry.createdElapsedMillis)
                    return@inTransaction BrowserEffectAdmission.TaskRejected("TASK_EXPIRED")
                }
                task.status != BrowserTaskStatus.RUNNING -> return@inTransaction BrowserEffectAdmission.TaskRejected(
                    if (task.status == BrowserTaskStatus.BUDGET_EXHAUSTED) "TASK_BUDGET_EXHAUSTED" else "TASK_NOT_RUNNING",
                )
                task.pageIdentity == null || task.pageIdentity != entry.pageIdentity ->
                    return@inTransaction BrowserEffectAdmission.TaskRejected("TASK_PAGE_MISMATCH")
                task.remainingOperations <= 0 -> {
                    markBudgetExhausted(db, task, entry.createdElapsedMillis)
                    return@inTransaction BrowserEffectAdmission.TaskRejected("TASK_BUDGET_EXHAUSTED")
                }
            }
        }
        if (countReservedEffects(db) >= MAX_UNCERTAIN_EFFECTS) {
            return@inTransaction BrowserEffectAdmission.Full(BrowserJournalCapacity.UNKNOWN_TOMBSTONES)
        }
        if (count(db, "effects") >= MAX_EFFECT_RECORDS) {
            return@inTransaction BrowserEffectAdmission.Full(BrowserJournalCapacity.TOTAL_RECEIPTS)
        }
        check(db.insertOrThrow("effects", null, entry.toValues()) >= 0)
        if (task != null && entry.method != "task") {
            val used = task.operationsUsed + 1
            val next = task.copy(
                revision = task.revision + 1,
                operationsUsed = used,
                status = if (used >= task.operationLimit) BrowserTaskStatus.BUDGET_EXHAUSTED else task.status,
                updatedElapsedMillis = entry.createdElapsedMillis,
                updatedAtWallMillis = monotonicWall(task, System.currentTimeMillis()),
            )
            check(db.update("tasks", next.toValues(), "task_id=? AND revision=?", arrayOf(task.taskId, task.revision.toString())) == 1)
        }
        BrowserEffectAdmission.NewIntent
    }

    /** A malformed/unknown completion is persisted as UNKNOWN, never as a confirmed success. */
    @Synchronized
    fun finishEffect(
        requestId: String,
        payloadHmacSha256: String,
        terminalCode: String?,
        executed: Boolean?,
        nowElapsedMillis: Long,
        taskId: String?,
    ): Boolean = writableDatabase.inTransaction { db ->
        val prior = getEffect(db, requestId) ?: return@inTransaction false
        if (prior.payloadHmacSha256 != payloadHmacSha256 || prior.phase != BrowserEffectPhase.INTENT) return@inTransaction false
        val confirmed = nowElapsedMillis < prior.deadlineElapsedMillis &&
            terminalCode != null && terminalCode != "UNKNOWN" && executed != null &&
            terminalCode.matches(Regex("[A-Z0-9_]{1,48}"))
        val values = ContentValues().apply {
            put("phase", if (confirmed) BrowserEffectPhase.CONFIRMED.wireValue else BrowserEffectPhase.UNKNOWN.wireValue)
            put("updated_elapsed", nowElapsedMillis)
            if (taskId != null) put("task_id", taskId)
            if (confirmed) {
                put("terminal_code", terminalCode)
                put("terminal_executed", if (executed == true) 1 else 0)
            } else {
                putNull("terminal_code")
                putNull("terminal_executed")
            }
        }
        val updated = db.update("effects", values, "request_id=? AND phase=?", arrayOf(requestId, BrowserEffectPhase.INTENT.wireValue)) == 1
        val effectTaskId = taskId ?: prior.taskId
        if (updated && !confirmed && prior.kind == BrowserEffectKind.POTENTIALLY_MUTATING && effectTaskId != null) {
            val task = getTask(db, effectTaskId)
            if (task != null && task.status in setOf(
                    BrowserTaskStatus.RUNNING, BrowserTaskStatus.WAITING_PAGE,
                    BrowserTaskStatus.WAITING_USER, BrowserTaskStatus.PAUSED,
                )
            ) {
                if (task.bootId != prior.bootId || nowElapsedMillis >= task.deadlineElapsedMillis) {
                    expireTask(db, task, prior.bootId, nowElapsedMillis)
                } else {
                    val recovery = task.copy(
                        revision = task.revision + 1,
                        status = BrowserTaskStatus.NEEDS_RECONCILIATION,
                        waitReason = BrowserTaskWaitReason.RECOVERY_CHECK,
                        updatedElapsedMillis = nowElapsedMillis,
                        updatedAtWallMillis = monotonicWall(task, System.currentTimeMillis()),
                    )
                    check(db.update("tasks", recovery.toValues(), "task_id=? AND revision=?", arrayOf(task.taskId, task.revision.toString())) == 1)
                }
            }
        }
        updated
    }

    /** Resolves UNKNOWN only with a recent same-page observation under current AI authorization. */
    @Synchronized
    fun reconcileUnknown(
        verification: BrowserEffectVerification,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): Boolean = writableDatabase.inTransaction { db ->
        val prior = getEffect(db, verification.requestId) ?: return@inTransaction false
        val identity = prior.pageIdentity ?: return@inTransaction false
        if (!automationEnabled || !ownerIsAi || prior.phase != BrowserEffectPhase.UNKNOWN ||
            prior.payloadHmacSha256 != verification.payloadHmacSha256 || prior.bootId != currentBootId ||
            identity.sessionGeneration != verification.observedIdentity.sessionGeneration ||
            identity.pageId != verification.observedIdentity.pageId ||
            verification.observedAtElapsedMillis > nowElapsedMillis ||
            nowElapsedMillis - verification.observedAtElapsedMillis > OBSERVATION_FRESHNESS_MILLIS
        ) return@inTransaction false
        val values = ContentValues().apply {
            put("phase", BrowserEffectPhase.RECONCILED.wireValue)
            put("updated_elapsed", nowElapsedMillis)
            put("terminal_code", when (verification.result) {
                BrowserEffectVerificationResult.EFFECT_PRESENT -> "RECONCILED_PRESENT"
                BrowserEffectVerificationResult.EFFECT_ABSENT -> "RECONCILED_ABSENT"
            })
            put("terminal_executed", if (verification.result == BrowserEffectVerificationResult.EFFECT_PRESENT) 1 else 0)
        }
        db.update("effects", values, "request_id=? AND phase=?", arrayOf(verification.requestId, BrowserEffectPhase.UNKNOWN.wireValue)) == 1
    }

    fun getEffect(requestId: String): BrowserEffectJournalEntry? = getEffect(readableDatabase, requestId)

    /** Recovery metadata contains identifiers and bounded phase data only; never payload bodies. */
    @Synchronized
    fun listRecoveryMetadata(currentBootId: String, nowElapsedMillis: Long, offset: Int = 0, limit: Int = DEFAULT_TASK_PAGE_SIZE): List<BrowserTaskRecoveryMetadata> =
        writableDatabase.inTransaction { db ->
            require(offset >= 0 && limit in 1..MAX_TASK_PAGE_SIZE)
            pruneRecoveryAudit(db, nowElapsedMillis)
            expireStaleTasks(db, currentBootId, nowElapsedMillis)
            val tasks = db.query("tasks", null, null, null, null, null, "created_wall DESC, task_id DESC", "$limit OFFSET $offset").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.toTask()) }
            }
            tasks.map { task ->
                val effects = db.rawQuery(
                    "SELECT e.request_id,e.method,e.created_elapsed,e.phase," +
                        "v.choice FROM effects e LEFT JOIN (SELECT effect_id,choice FROM user_effect_verification " +
                        "WHERE row_id IN (SELECT MAX(v2.row_id) FROM user_effect_verification v2 JOIN tasks t " +
                        "ON t.task_id=v2.task_id WHERE v2.task_revision>=COALESCE((SELECT MAX(b.task_revision) FROM task_binding_audit b " +
                        "WHERE b.task_id=t.task_id),0) AND " +
                        "v2.session_generation=t.session_generation AND v2.control_generation=t.control_generation " +
                        "AND v2.page_id=t.page_id AND v2.document_generation=t.document_generation " +
                        "GROUP BY v2.effect_id)) v " +
                        "ON v.effect_id=e.request_id WHERE e.task_id=? AND e.kind='potentially_mutating' AND e.phase IN (?,?) " +
                        "ORDER BY e.created_elapsed DESC LIMIT ?",
                    arrayOf(task.taskId, BrowserEffectPhase.UNKNOWN.wireValue, BrowserEffectPhase.INTENT.wireValue,
                        MAX_UNKNOWN_EFFECTS_PER_TASK.toString()),
                ).use { cursor -> buildList {
                    while (cursor.moveToNext()) {
                        val choice = cursor.getString(4)?.let { value -> BrowserUserEffectChoice.entries.firstOrNull { it.wireValue == value } }
                        add(BrowserUnknownEffectMetadata(
                            requestId = cursor.getString(0), method = cursor.getString(1),
                            createdElapsedMillis = cursor.getLong(2),
                            phase = BrowserEffectPhase.entries.first { it.wireValue == cursor.getString(3) },
                            hasUserVerification = choice != null, lastUserChoice = choice,
                        ))
                    }
                } }
                BrowserTaskRecoveryMetadata(task, effects, task.pageIdentity)
            }
        }

    /** Add a UI attestation as a separate receipt; the UNKNOWN effect row remains immutable. */
    @Synchronized
    fun recordUserEffectVerification(
        taskId: String, expectedRevision: Long, effectId: String, observedIdentity: BrowserTaskPageIdentity,
        choice: BrowserUserEffectChoice, currentBootId: String, nowElapsedMillis: Long,
    ): Boolean = writableDatabase.inTransaction { db ->
        pruneRecoveryAudit(db, nowElapsedMillis)
        val task = getTask(db, taskId) ?: return@inTransaction false
        val effect = getEffect(db, effectId) ?: return@inTransaction false
        val bound = task.pageIdentity
        if (task.revision != expectedRevision || effect.taskId != taskId || effect.kind != BrowserEffectKind.POTENTIALLY_MUTATING ||
            effect.phase != BrowserEffectPhase.UNKNOWN || effect.bootId != currentBootId ||
            nowElapsedMillis < effect.createdElapsedMillis ||
            nowElapsedMillis - effect.createdElapsedMillis > EFFECT_RETENTION_MILLIS ||
            task.bootId != currentBootId || nowElapsedMillis < task.createdElapsedMillis ||
            nowElapsedMillis < task.updatedElapsedMillis || nowElapsedMillis >= task.deadlineElapsedMillis ||
            task.status !in setOf(BrowserTaskStatus.PAUSED, BrowserTaskStatus.NEEDS_RECONCILIATION, BrowserTaskStatus.WAITING_USER) ||
            bound == null || bound != observedIdentity
        ) return@inTransaction false
        if (count(db, "user_effect_verification") >= MAX_USER_VERIFICATIONS) return@inTransaction false
        val values = ContentValues().apply {
            put("task_id", taskId); put("task_revision", expectedRevision); put("effect_id", effectId); put("choice", choice.wireValue)
            put("session_generation", observedIdentity.sessionGeneration)
            put("control_generation", observedIdentity.controlGeneration); put("page_id", observedIdentity.pageId)
            put("document_generation", observedIdentity.documentGeneration); put("verified_elapsed", nowElapsedMillis)
            put("actor", "USER")
        }
        db.insertOrThrow("user_effect_verification", null, values) >= 0
    }

    /** Atomically updates the current page binding and pauses the task, while archiving the old checkpoint. */
    @Synchronized
    fun consumeRecoveryBinding(
        taskId: String, expectedRevision: Long, expectedOldIdentity: BrowserTaskPageIdentity?,
        newIdentity: BrowserTaskPageIdentity, nonce: String, currentBootId: String,
        nowElapsedMillis: Long, challengeExpiresElapsedMillis: Long,
    ): BrowserTaskRecord? = writableDatabase.inTransaction { db ->
        pruneRecoveryAudit(db, nowElapsedMillis)
        val task = getTask(db, taskId) ?: return@inTransaction null
        if (task.revision != expectedRevision || task.pageIdentity != expectedOldIdentity ||
            task.status !in setOf(BrowserTaskStatus.NEEDS_RECONCILIATION, BrowserTaskStatus.PAUSED, BrowserTaskStatus.WAITING_USER) ||
            task.bootId != currentBootId || nowElapsedMillis < task.createdElapsedMillis ||
            nowElapsedMillis < task.updatedElapsedMillis || nowElapsedMillis >= task.deadlineElapsedMillis ||
            nowElapsedMillis >= challengeExpiresElapsedMillis || task.remainingOperations <= 0 ||
            !nonce.matches(Regex("[A-Za-z0-9_-]{32,96}")) || count(db, "task_binding_audit") >= MAX_BINDING_AUDIT
        ) return@inTransaction null
        val next = task.copy(revision = task.revision + 1, status = BrowserTaskStatus.PAUSED,
            pageIdentity = newIdentity, waitReason = null, updatedElapsedMillis = nowElapsedMillis,
            updatedAtWallMillis = monotonicWall(task, System.currentTimeMillis()))
        val values = ContentValues().apply {
            put("task_id", taskId); put("task_revision", next.revision); put("nonce", nonce)
            put("old_revision", task.revision); put("old_status", task.status.wireValue)
            put("old_created_elapsed", task.createdElapsedMillis); put("old_deadline_elapsed", task.deadlineElapsedMillis)
            put("old_updated_elapsed", task.updatedElapsedMillis)
            put("old_boot_id", task.bootId); put("old_operation_limit", task.operationLimit); put("old_operations_used", task.operationsUsed)
            put("old_wait_reason", task.waitReason?.wireValue)
            put("old_session_generation", expectedOldIdentity?.sessionGeneration)
            put("old_control_generation", expectedOldIdentity?.controlGeneration)
            put("old_page_id", expectedOldIdentity?.pageId)
            put("old_document_generation", expectedOldIdentity?.documentGeneration)
            put("session_generation", newIdentity.sessionGeneration); put("control_generation", newIdentity.controlGeneration)
            put("page_id", newIdentity.pageId); put("document_generation", newIdentity.documentGeneration)
            put("boot_id", currentBootId); put("bound_elapsed", nowElapsedMillis); put("actor", "USER")
        }
        check(db.insertOrThrow("task_binding_audit", null, values) >= 0)
        check(db.update("tasks", next.toValues(), "task_id=? AND revision=?", arrayOf(taskId, expectedRevision.toString())) == 1)
        next
    }

    @Synchronized
    fun invalidateRecoveryBinding(taskId: String, expectedRevision: Long, nowElapsedMillis: Long): Boolean =
        writableDatabase.inTransaction { db ->
            val task = getTask(db, taskId) ?: return@inTransaction false
            if (task.revision != expectedRevision || task.status != BrowserTaskStatus.PAUSED) return@inTransaction false
            val next = task.copy(revision = task.revision + 1, status = BrowserTaskStatus.NEEDS_RECONCILIATION,
                pageIdentity = null, waitReason = BrowserTaskWaitReason.RECOVERY_CHECK, updatedElapsedMillis = nowElapsedMillis,
                updatedAtWallMillis = monotonicWall(task, System.currentTimeMillis()))
            db.update("tasks", next.toValues(), "task_id=? AND revision=?", arrayOf(taskId, expectedRevision.toString())) == 1
        }

    private fun createRecoveryTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS task_binding_audit (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, task_revision INTEGER NOT NULL,
            old_revision INTEGER NOT NULL, old_status TEXT NOT NULL, old_created_elapsed INTEGER NOT NULL,
            old_deadline_elapsed INTEGER NOT NULL, old_updated_elapsed INTEGER NOT NULL, old_boot_id TEXT NOT NULL, old_operation_limit INTEGER NOT NULL,
            old_operations_used INTEGER NOT NULL, old_wait_reason TEXT,
            old_session_generation INTEGER, old_control_generation INTEGER, old_page_id TEXT, old_document_generation INTEGER,
            nonce TEXT NOT NULL UNIQUE, session_generation INTEGER NOT NULL, control_generation INTEGER NOT NULL,
            page_id TEXT NOT NULL, document_generation INTEGER NOT NULL, boot_id TEXT NOT NULL, bound_elapsed INTEGER NOT NULL,
            actor TEXT NOT NULL CHECK(actor='USER')
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS task_binding_latest ON task_binding_audit(task_id,row_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS user_effect_verification (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, task_revision INTEGER NOT NULL, effect_id TEXT NOT NULL,
            choice TEXT NOT NULL CHECK(choice IN ('occurred','not_occurred','still_unknown')),
            session_generation INTEGER NOT NULL, control_generation INTEGER NOT NULL, page_id TEXT NOT NULL,
            document_generation INTEGER NOT NULL, verified_elapsed INTEGER NOT NULL, actor TEXT NOT NULL CHECK(actor='USER')
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS user_effect_latest ON user_effect_verification(effect_id,row_id)")
    }

    private fun createEventAuditTables(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS event_dialog_resolution_audit (
            event_id TEXT PRIMARY KEY NOT NULL, task_id TEXT NOT NULL, parent_effect_id TEXT NOT NULL,
            task_revision INTEGER NOT NULL, session_generation INTEGER NOT NULL, control_generation INTEGER NOT NULL,
            page_id TEXT NOT NULL, document_generation INTEGER NOT NULL,
            decision TEXT NOT NULL CHECK(decision IN ('accept','dismiss','prompt')),
            prompt_hmac TEXT CHECK(prompt_hmac IS NULL OR length(prompt_hmac)=64),
            boot_id TEXT NOT NULL, created_elapsed INTEGER NOT NULL,
            outcome TEXT NOT NULL CHECK(outcome IN ('INTENT','RESOLVED','UNKNOWN')) DEFAULT 'INTENT'
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS event_dialog_task_created ON event_dialog_resolution_audit(task_id,created_elapsed)")
    }

    @Synchronized
    fun completeEventDialogResolution(eventId: String, resolved: Boolean): Boolean = writableDatabase.inTransaction { db ->
        val outcome = if (resolved) "RESOLVED" else "UNKNOWN"
        db.update("event_dialog_resolution_audit", ContentValues().apply { put("outcome", outcome) },
            "event_id=? AND outcome='INTENT'", arrayOf(eventId)) == 1
    }

    private fun hasUnverifiedUnknown(db: SQLiteDatabase, task: BrowserTaskRecord): Boolean = db.rawQuery(
        "SELECT 1 FROM effects e WHERE e.task_id=? AND e.kind=? AND e.phase IN (?,?) AND " +
            "COALESCE((SELECT v.choice FROM user_effect_verification v WHERE v.effect_id=e.request_id " +
            "AND v.task_revision>=COALESCE((SELECT MAX(b.task_revision) FROM task_binding_audit b WHERE b.task_id=e.task_id),0) " +
            "AND v.session_generation=? AND v.control_generation=? AND v.page_id=? " +
            "AND v.document_generation=? ORDER BY v.row_id DESC LIMIT 1),?) NOT IN (?,?) LIMIT 1",
        arrayOf(task.taskId, BrowserEffectKind.POTENTIALLY_MUTATING.wireValue,
            BrowserEffectPhase.UNKNOWN.wireValue, BrowserEffectPhase.INTENT.wireValue,
            task.pageIdentity?.sessionGeneration?.toString() ?: "-1",
            task.pageIdentity?.controlGeneration?.toString() ?: "-1", task.pageIdentity?.pageId ?: "",
            task.pageIdentity?.documentGeneration?.toString() ?: "-1",
            BrowserUserEffectChoice.STILL_UNKNOWN.wireValue,
            BrowserUserEffectChoice.OCCURRED.wireValue, BrowserUserEffectChoice.NOT_OCCURRED.wireValue),
    ).use { it.moveToFirst() }

    /** Call once when the runtime process starts; stale intents become UNKNOWN and tasks require recovery. */
    @Synchronized
    fun recoverAfterProcessStart(currentBootId: String, nowElapsedMillis: Long) = writableDatabase.inTransaction { db ->
        db.execSQL(
            "UPDATE effects SET phase=?, updated_elapsed=?, terminal_code=?, terminal_executed=0 WHERE kind=? AND phase IN (?,?)",
            arrayOf(BrowserEffectPhase.CONFIRMED.wireValue, nowElapsedMillis, "READ_INTERRUPTED",
                BrowserEffectKind.READ_ONLY.wireValue, BrowserEffectPhase.INTENT.wireValue, BrowserEffectPhase.UNKNOWN.wireValue),
        )
        db.execSQL(
            "UPDATE effects SET phase=?, updated_elapsed=?, terminal_code=NULL, terminal_executed=NULL WHERE phase=?",
            arrayOf(BrowserEffectPhase.UNKNOWN.wireValue, nowElapsedMillis, BrowserEffectPhase.INTENT.wireValue),
        )
        expireStaleTasks(db, currentBootId, nowElapsedMillis)
        db.execSQL(
            "UPDATE tasks SET status=?, revision=revision+1, wait_reason=?, updated_elapsed=?, updated_wall=MAX(created_wall,updated_wall,?) WHERE boot_id=? AND status IN (?, ?, ?, ?) AND deadline_elapsed>?",
            arrayOf(
                BrowserTaskStatus.NEEDS_RECONCILIATION.wireValue,
                BrowserTaskWaitReason.RECOVERY_CHECK.wireValue,
                nowElapsedMillis,
                System.currentTimeMillis().coerceAtLeast(0),
                currentBootId,
                BrowserTaskStatus.RUNNING.wireValue,
                BrowserTaskStatus.WAITING_PAGE.wireValue,
                BrowserTaskStatus.WAITING_USER.wireValue,
                BrowserTaskStatus.PAUSED.wireValue,
                nowElapsedMillis,
            ),
        )
        pruneConfirmedAndFinished(db, currentBootId, nowElapsedMillis)
    }

    private fun expireTask(db: SQLiteDatabase, task: BrowserTaskRecord, currentBootId: String, nowElapsedMillis: Long) {
        val values = ContentValues().apply {
            put("status", BrowserTaskStatus.EXPIRED.wireValue)
            put("revision", task.revision + 1)
            putNull("wait_reason")
            put("updated_elapsed", if (task.bootId == currentBootId) nowElapsedMillis else task.updatedElapsedMillis)
            put("updated_wall", monotonicWall(task, System.currentTimeMillis()))
        }
        db.update("tasks", values, "task_id=? AND revision=?", arrayOf(task.taskId, task.revision.toString()))
    }

    private fun markBudgetExhausted(db: SQLiteDatabase, task: BrowserTaskRecord, nowElapsedMillis: Long) {
        val values = ContentValues().apply {
            put("status", BrowserTaskStatus.BUDGET_EXHAUSTED.wireValue)
            put("revision", task.revision + 1)
            putNull("wait_reason")
            put("updated_elapsed", nowElapsedMillis)
            put("updated_wall", monotonicWall(task, System.currentTimeMillis()))
        }
        db.update("tasks", values, "task_id=? AND revision=?", arrayOf(task.taskId, task.revision.toString()))
    }

    private fun expireStaleTasks(db: SQLiteDatabase, bootId: String, now: Long) {
        db.execSQL(
            "UPDATE tasks SET status=?, revision=revision+1, wait_reason=NULL, updated_wall=MAX(created_wall,updated_wall,?) WHERE " +
                "boot_id<>? AND status IN (?, ?, ?, ?, ?, ?)",
            arrayOf(
                BrowserTaskStatus.EXPIRED.wireValue,
                System.currentTimeMillis().coerceAtLeast(0),
                bootId,
                BrowserTaskStatus.RUNNING.wireValue,
                BrowserTaskStatus.WAITING_PAGE.wireValue,
                BrowserTaskStatus.WAITING_USER.wireValue,
                BrowserTaskStatus.PAUSED.wireValue,
                BrowserTaskStatus.NEEDS_RECONCILIATION.wireValue,
                BrowserTaskStatus.BUDGET_EXHAUSTED.wireValue,
            ),
        )
        db.execSQL(
            "UPDATE tasks SET status=?, revision=revision+1, wait_reason=NULL, updated_wall=MAX(created_wall,updated_wall,?) WHERE " +
                "boot_id=? AND deadline_elapsed<=? AND status IN (?, ?, ?, ?, ?, ?)",
            arrayOf(
                BrowserTaskStatus.EXPIRED.wireValue,
                System.currentTimeMillis().coerceAtLeast(0),
                bootId,
                now,
                BrowserTaskStatus.RUNNING.wireValue,
                BrowserTaskStatus.WAITING_PAGE.wireValue,
                BrowserTaskStatus.WAITING_USER.wireValue,
                BrowserTaskStatus.PAUSED.wireValue,
                BrowserTaskStatus.NEEDS_RECONCILIATION.wireValue,
                BrowserTaskStatus.BUDGET_EXHAUSTED.wireValue,
            ),
        )
        db.execSQL(
            "UPDATE tasks SET status=?, revision=revision+1, wait_reason=NULL, updated_wall=MAX(created_wall,updated_wall,?) WHERE " +
                "boot_id=? AND operations_used>=operation_limit AND status IN (?, ?, ?, ?, ?)",
            arrayOf(
                BrowserTaskStatus.BUDGET_EXHAUSTED.wireValue,
                System.currentTimeMillis().coerceAtLeast(0),
                bootId,
                BrowserTaskStatus.RUNNING.wireValue,
                BrowserTaskStatus.WAITING_PAGE.wireValue,
                BrowserTaskStatus.WAITING_USER.wireValue,
                BrowserTaskStatus.PAUSED.wireValue,
                BrowserTaskStatus.NEEDS_RECONCILIATION.wireValue,
            ),
        )
    }

    private fun monotonicWall(task: BrowserTaskRecord, nowWallMillis: Long): Long =
        maxOf(task.createdAtWallMillis, task.updatedAtWallMillis, nowWallMillis.coerceAtLeast(0))

    private fun pruneConfirmedAndFinished(db: SQLiteDatabase, bootId: String, now: Long) {
        db.delete(
            "effects",
            "phase IN (?, ?) AND boot_id=? AND created_elapsed<=?",
            arrayOf(BrowserEffectPhase.CONFIRMED.wireValue, BrowserEffectPhase.RECONCILED.wireValue, bootId,
                (now - EFFECT_RETENTION_MILLIS).coerceAtLeast(0).toString()),
        )
        db.delete(
            "effects",
            "phase=? AND boot_id=? AND created_elapsed<=?",
            arrayOf(BrowserEffectPhase.UNKNOWN.wireValue, bootId, (now - EFFECT_RETENTION_MILLIS).coerceAtLeast(0).toString()),
        )
    }

    private fun pruneTasks(db: SQLiteDatabase, bootId: String, now: Long) {
        // Task records are user-managed recovery history; only explicit safe deletion frees capacity.
        pruneRecoveryAudit(db, now)
    }

    /** Retain recovery history while its task or any unresolved receipt remains live. */
    private fun pruneRecoveryAudit(db: SQLiteDatabase, now: Long) {
        val cutoff = (now - EFFECT_RETENTION_MILLIS).coerceAtLeast(0).toString()
        val eligible = "NOT EXISTS (SELECT 1 FROM tasks t WHERE t.task_id=%TABLE%.task_id AND " +
            "(t.status NOT IN (?, ?, ?) OR t.updated_elapsed>?)) AND " +
            "NOT EXISTS (SELECT 1 FROM effects e WHERE e.task_id=%TABLE%.task_id AND e.phase IN (?, ?))"
        val terminalArgs = arrayOf(
            BrowserTaskStatus.COMPLETED.wireValue, BrowserTaskStatus.FAILED.wireValue,
            BrowserTaskStatus.EXPIRED.wireValue, cutoff,
            BrowserEffectPhase.INTENT.wireValue, BrowserEffectPhase.UNKNOWN.wireValue,
        )
        db.delete("user_effect_verification", "verified_elapsed<=? AND " + eligible.replace("%TABLE%", "user_effect_verification"),
            arrayOf(cutoff, *terminalArgs))
        db.delete("task_binding_audit", "bound_elapsed<=? AND " + eligible.replace("%TABLE%", "task_binding_audit"),
            arrayOf(cutoff, *terminalArgs))
        db.delete("event_dialog_resolution_audit", "created_elapsed<=? AND " + eligible.replace("%TABLE%", "event_dialog_resolution_audit"),
            arrayOf(cutoff, *terminalArgs))
    }

    private fun countReservedEffects(db: SQLiteDatabase): Int = db.rawQuery(
        "SELECT COUNT(*) FROM effects WHERE phase IN (?, ?)",
        arrayOf(BrowserEffectPhase.INTENT.wireValue, BrowserEffectPhase.UNKNOWN.wireValue),
    ).use { if (it.moveToFirst()) it.getInt(0) else MAX_UNCERTAIN_EFFECTS }

    private fun count(db: SQLiteDatabase, table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null)
        .use { if (it.moveToFirst()) it.getInt(0) else MAX_MANAGED_TASKS }

    private fun countActiveTasks(db: SQLiteDatabase): Int = db.rawQuery(
        "SELECT COUNT(*) FROM tasks WHERE status NOT IN (?, ?, ?, ?)",
        arrayOf(BrowserTaskStatus.BUDGET_EXHAUSTED.wireValue, BrowserTaskStatus.COMPLETED.wireValue,
            BrowserTaskStatus.FAILED.wireValue, BrowserTaskStatus.EXPIRED.wireValue),
    ).use { if (it.moveToFirst()) it.getInt(0) else MAX_ACTIVE_TASKS }

    private fun getTask(db: SQLiteDatabase, taskId: String): BrowserTaskRecord? = db.query(
        "tasks", null, "task_id=?", arrayOf(taskId), null, null, null, "1",
    ).use { if (it.moveToFirst()) it.toTask() else null }

    private fun getEffect(db: SQLiteDatabase, requestId: String): BrowserEffectJournalEntry? = db.query(
        "effects", null, "request_id=?", arrayOf(requestId), null, null, null, "1",
    ).use { if (it.moveToFirst()) it.toEffect() else null }

    private inline fun <T> SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> T): T {
        beginTransaction()
        try {
            val result = block(this)
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }

    private fun BrowserTaskRecord.toValues() = ContentValues().apply {
        put("task_id", taskId); put("revision", revision); put("status", status.wireValue)
        put("created_elapsed", createdElapsedMillis); put("deadline_elapsed", deadlineElapsedMillis)
        put("boot_id", bootId); put("operation_limit", operationLimit); put("operations_used", operationsUsed)
        putIdentity(pageIdentity); put("wait_reason", waitReason?.wireValue); put("updated_elapsed", updatedElapsedMillis)
        put("title", title); put("goal", goal); put("notes", notes)
        if (failureReason == null) putNull("failure_reason") else put("failure_reason", failureReason)
        put("created_wall", createdAtWallMillis); put("updated_wall", updatedAtWallMillis)
    }

    private fun BrowserEffectJournalEntry.toValues() = ContentValues().apply {
        put("request_id", requestId); put("payload_hmac_sha256", payloadHmacSha256); put("method", method)
        put("kind", kind.wireValue); put("task_id", taskId); putIdentity(pageIdentity)
        put("deadline_elapsed", deadlineElapsedMillis); put("boot_id", bootId); put("phase", phase.wireValue)
        put("created_elapsed", createdElapsedMillis); put("updated_elapsed", updatedElapsedMillis)
        put("terminal_code", terminalCode); if (terminalExecuted == null) putNull("terminal_executed") else put("terminal_executed", if (terminalExecuted == true) 1 else 0)
    }

    private fun ContentValues.putIdentity(identity: BrowserTaskPageIdentity?) {
        if (identity == null) {
            putNull("session_generation"); putNull("control_generation"); putNull("page_id"); putNull("document_generation")
        } else {
            put("session_generation", identity.sessionGeneration); put("control_generation", identity.controlGeneration)
            put("page_id", identity.pageId); put("document_generation", identity.documentGeneration)
        }
    }

    private fun android.database.Cursor.toTask(): BrowserTaskRecord {
        val session = getColumnIndexOrThrow("session_generation")
        val pageIdentity = if (isNull(session)) null else BrowserTaskPageIdentity(
            getLong(session), getLong(getColumnIndexOrThrow("control_generation")),
            getString(getColumnIndexOrThrow("page_id")), getLong(getColumnIndexOrThrow("document_generation")),
        )
        return BrowserTaskRecord(
            getString(getColumnIndexOrThrow("task_id")), getLong(getColumnIndexOrThrow("revision")),
            BrowserTaskStatus.entries.first { it.wireValue == getString(getColumnIndexOrThrow("status")) },
            getLong(getColumnIndexOrThrow("created_elapsed")), getLong(getColumnIndexOrThrow("deadline_elapsed")),
            getString(getColumnIndexOrThrow("boot_id")), getInt(getColumnIndexOrThrow("operation_limit")),
            getInt(getColumnIndexOrThrow("operations_used")), pageIdentity,
            getString(getColumnIndexOrThrow("wait_reason"))?.let { value -> BrowserTaskWaitReason.entries.first { it.wireValue == value } },
            getLong(getColumnIndexOrThrow("updated_elapsed")),
            getString(getColumnIndexOrThrow("title")), getString(getColumnIndexOrThrow("goal")),
            getString(getColumnIndexOrThrow("notes")), getLong(getColumnIndexOrThrow("created_wall")),
            getLong(getColumnIndexOrThrow("updated_wall")), getString(getColumnIndexOrThrow("failure_reason")),
        )
    }

    private fun android.database.Cursor.toEffect(): BrowserEffectJournalEntry {
        val session = getColumnIndexOrThrow("session_generation")
        val identity = if (isNull(session)) null else BrowserTaskPageIdentity(
            getLong(session), getLong(getColumnIndexOrThrow("control_generation")),
            getString(getColumnIndexOrThrow("page_id")), getLong(getColumnIndexOrThrow("document_generation")),
        )
        val executedIndex = getColumnIndexOrThrow("terminal_executed")
        return BrowserEffectJournalEntry(
            getString(getColumnIndexOrThrow("request_id")), getString(getColumnIndexOrThrow("payload_hmac_sha256")),
            getString(getColumnIndexOrThrow("method")),
            BrowserEffectKind.entries.first { it.wireValue == getString(getColumnIndexOrThrow("kind")) },
            getString(getColumnIndexOrThrow("task_id")), identity,
            getLong(getColumnIndexOrThrow("deadline_elapsed")), getString(getColumnIndexOrThrow("boot_id")),
            BrowserEffectPhase.entries.first { it.wireValue == getString(getColumnIndexOrThrow("phase")) },
            getLong(getColumnIndexOrThrow("created_elapsed")), getLong(getColumnIndexOrThrow("updated_elapsed")),
            getString(getColumnIndexOrThrow("terminal_code")), if (isNull(executedIndex)) null else getInt(executedIndex) != 0,
        )
    }

    private companion object {
        const val DATABASE_NAME = "browser-task-journal.db"
        const val DATABASE_VERSION = 5
        const val MAX_ACTIVE_TASKS = 20
        const val MAX_MANAGED_TASKS = 512
        const val DEFAULT_TASK_PAGE_SIZE = 100
        const val MAX_TASK_PAGE_SIZE = 100
        const val MAX_UNCERTAIN_EFFECTS = 128
        const val MAX_EFFECT_RECORDS = 512
        const val EFFECT_RETENTION_MILLIS = 24L * 60 * 60 * 1000
        const val OBSERVATION_FRESHNESS_MILLIS = 5_000L
        const val MAX_UNKNOWN_EFFECTS_PER_TASK = 128
        const val MAX_BINDING_AUDIT = 256
        const val MAX_USER_VERIFICATIONS = 512
        const val MAX_EVENT_DIALOG_AUDIT = 512
        val TERMINAL_TASK_STATES = setOf(BrowserTaskStatus.COMPLETED, BrowserTaskStatus.FAILED, BrowserTaskStatus.EXPIRED)
        val DELETABLE_TASK_STATES = setOf(BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.COMPLETED, BrowserTaskStatus.FAILED, BrowserTaskStatus.EXPIRED)
    }

    private fun saturatingAdd(value: Long, increment: Long): Long =
        if (Long.MAX_VALUE - value < increment) Long.MAX_VALUE else value + increment
}
