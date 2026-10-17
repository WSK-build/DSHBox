package com.dshbox.browser.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.dshbox.browser.api.BrowserEffectJournalEntry
import com.dshbox.browser.api.BrowserEffectKind
import com.dshbox.browser.api.BrowserEffectPhase
import com.dshbox.browser.api.BrowserEffectVerification
import com.dshbox.browser.api.BrowserEffectVerificationResult
import com.dshbox.browser.api.BrowserTaskBudget
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BrowserTaskJournalInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var journal: BrowserTaskJournal

    @Before
    fun setUp() {
        context.deleteDatabase("browser-task-journal.db")
        journal = BrowserTaskJournal(context)
    }

    @After
    fun tearDown() {
        journal.close()
        context.deleteDatabase("browser-task-journal.db")
    }

    @Test
    fun intentSurvivesHelperRecreationAndBecomesUnknownBeforeReplay() {
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(effect("req_restart", 10)))
        journal.close()

        journal = BrowserTaskJournal(context)
        journal.recoverAfterProcessStart("boot-a", 20)

        val retained = journal.getEffect("req_restart")
        assertEquals(BrowserEffectPhase.UNKNOWN, retained?.phase)
        assertEquals(
            BrowserEffectAdmission.Duplicate(BrowserEffectPhase.UNKNOWN, null, null),
            journal.reserveEffect(effect("req_restart", 30)),
        )
    }

    @Test
    fun interruptedScreenshotDoesNotRequireWriteVerificationOrPreventTaskDeletion() {
        val identity = BrowserTaskPageIdentity(4, 9, "page_read_capture", 2)
        val readTask = (journal.createTask("task_read_capture", BrowserTaskBudget(), "boot-a", 100, identity)
            as BrowserTaskCreateResult.Created).record
        val writeTask = (journal.createTask("task_write_capture", BrowserTaskBudget(), "boot-a", 100, identity)
            as BrowserTaskCreateResult.Created).record
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(effect("shot_restart", 110).copy(
            method = "screenshot", kind = BrowserEffectKind.READ_ONLY, taskId = readTask.taskId, pageIdentity = identity)))
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(effect("click_restart", 110).copy(taskId = writeTask.taskId, pageIdentity = identity)))
        journal.close()
        journal = BrowserTaskJournal(context)
        journal.recoverAfterProcessStart("boot-a", 120)
        assertEquals(BrowserEffectPhase.CONFIRMED, journal.getEffect("shot_restart")?.phase)
        assertEquals("READ_INTERRUPTED", journal.getEffect("shot_restart")?.terminalCode)
        assertEquals(false, journal.getEffect("shot_restart")?.terminalExecuted)
        assertFalse(journal.hasUnresolvedEffects(readTask.taskId))
        assertTrue(journal.hasUnresolvedEffects(writeTask.taskId))
        val metadata = journal.listRecoveryMetadata("boot-a", 121)
        assertTrue(metadata.first { it.task.taskId == readTask.taskId }.unknownEffects.isEmpty())
        assertEquals("click_restart", metadata.first { it.task.taskId == writeTask.taskId }.unknownEffects.single().requestId)
        for (task in listOf(readTask, writeTask)) {
            val current = journal.getTask(task.taskId)!!
            val failed = journal.transitionTask(task.taskId, current.revision, BrowserTaskStatus.FAILED,
                null, null, false, false, 122, "boot-a") as BrowserTaskUpdateResult.Updated
            assertEquals(if (task.taskId == readTask.taskId) BrowserTaskDeleteResult.Deleted else BrowserTaskDeleteResult.UnresolvedEffects,
                journal.deleteManagedTask(task.taskId, failed.record.revision))
        }
    }

    @Test
    fun unknownCapacityFailsClosedWithoutEvictingUnresolvedIds() {
        val identity = BrowserTaskPageIdentity(4, 3, "page_unknown", 2)
        repeat(128) { index ->
            val entry = effect("req_$index", 10L + index).copy(pageIdentity = if (index == 0) identity else null)
            assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(entry))
            assertTrue(journal.finishEffect("req_$index", "a".repeat(64), null, null, 20L + index, taskId = entry.taskId))
        }
        assertEquals(BrowserEffectAdmission.Full(BrowserJournalCapacity.UNKNOWN_TOMBSTONES), journal.reserveEffect(effect("req_overflow", 500)))
        assertEquals(BrowserEffectPhase.UNKNOWN, journal.getEffect("req_0")?.phase)
        assertEquals(
            BrowserEffectAdmission.Duplicate(BrowserEffectPhase.UNKNOWN, null, null),
            journal.reserveEffect(effect("req_127", 600)),
        )
        val verified = BrowserEffectVerification(
            requestId = "req_0", payloadHmacSha256 = "a".repeat(64), observedIdentity = identity,
            observationId = "fresh_1", observedAtElapsedMillis = 700,
            result = BrowserEffectVerificationResult.EFFECT_ABSENT,
        )
        assertTrue(journal.reconcileUnknown(verified, true, true, "boot-a", 705))
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(effect("req_after_resolution", 710)))
        assertEquals(BrowserEffectPhase.UNKNOWN, journal.getEffect("req_1")?.phase)
    }

    @Test
    fun taskBudgetAndRecoveryAreDurableAndDoNotRestoreRunningState() {
        val created = journal.createTask("task_a", BrowserTaskBudget(), "boot-a", 100, null)
        assertTrue(created is BrowserTaskCreateResult.Created)
        assertTrue(journal.consumeOperation("task_a", 0, 150, "boot-a") != null)
        journal.recoverAfterProcessStart("boot-a", 200)
        val restored = journal.getTask("task_a")
        assertEquals(BrowserTaskStatus.NEEDS_RECONCILIATION, restored?.status)
        assertEquals(1, restored?.operationsUsed)
        assertFalse(restored?.deadlineElapsedMillis == 200 + BrowserTaskBudget.DEFAULT_DURATION_MILLIS)
    }

    @Test
    fun expiredOrOldBootTaskCannotCompleteButCanBeSafelyFailed() {
        val created = (journal.createTask("task_expired", BrowserTaskBudget(100, 3), "boot-a", 100, null)
            as BrowserTaskCreateResult.Created).record
        assertEquals(
            BrowserTaskUpdateResult.Expired,
            journal.transitionTask(
                "task_expired", created.revision, BrowserTaskStatus.COMPLETED, null, null,
                automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 200, currentBootId = "boot-a",
            ),
        )
        assertEquals(BrowserTaskStatus.EXPIRED, journal.getTask("task_expired")?.status)
        val failAfterDeadline = (journal.createTask("task_fail_deadline", BrowserTaskBudget(100, 3), "boot-a", 300, null)
            as BrowserTaskCreateResult.Created).record
        val failed = journal.transitionTask(
            "task_fail_deadline", failAfterDeadline.revision, BrowserTaskStatus.FAILED, null, null,
            automationEnabled = false, ownerIsAi = false, nowElapsedMillis = 400, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        assertEquals(BrowserTaskStatus.FAILED, failed.record.status)

        val oldBoot = (journal.createTask("task_oldboot", BrowserTaskBudget(), "boot-a", 300, null)
            as BrowserTaskCreateResult.Created).record
        assertEquals(
            BrowserTaskUpdateResult.Expired,
            journal.transitionTask(
                "task_oldboot", oldBoot.revision, BrowserTaskStatus.COMPLETED, null, null,
                automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 10, currentBootId = "boot-b",
            ),
        )
        assertEquals(BrowserTaskStatus.EXPIRED, journal.getTask("task_oldboot")?.status)
        val failAfterReboot = (journal.createTask("task_fail_reboot", BrowserTaskBudget(), "boot-a", 500, null)
            as BrowserTaskCreateResult.Created).record
        assertTrue(journal.transitionTask(
            "task_fail_reboot", failAfterReboot.revision, BrowserTaskStatus.FAILED, null, null,
            automationEnabled = false, ownerIsAi = false, nowElapsedMillis = 10, currentBootId = "boot-b",
        ) is BrowserTaskUpdateResult.Updated)
    }

    @Test
    fun exhaustedBudgetCannotEnterAnotherWaitButCanRecordTerminalCompletion() {
        val task = (journal.createTask("task_budget", BrowserTaskBudget(10_000, 1), "boot-a", 100, null)
            as BrowserTaskCreateResult.Created).record
        val consumed = journal.consumeOperation("task_budget", task.revision, 110, "boot-a")!!
        assertEquals(
            BrowserTaskUpdateResult.InvalidTransition,
            journal.transitionTask(
                "task_budget", consumed.revision, BrowserTaskStatus.WAITING_PAGE,
                com.dshbox.browser.api.BrowserTaskWaitReason.PAGE_LOAD, null,
                automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 120, currentBootId = "boot-a",
            ),
        )
        val completed = journal.transitionTask(
            "task_budget", consumed.revision, BrowserTaskStatus.COMPLETED, null, null,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 120, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        assertEquals(BrowserTaskStatus.COMPLETED, completed.record.status)
        assertEquals(0, completed.record.remainingOperations)
    }

    @Test
    fun finalSuccessfulOperationRemainsCompletableAfterStatusRead() {
        val task = (journal.createTask("task_final_success", BrowserTaskBudget(10_000, 1), "boot-a", 100, null)
            as BrowserTaskCreateResult.Created).record
        val consumed = journal.consumeOperation("task_final_success", task.revision, 110, "boot-a")!!
        assertEquals(BrowserTaskStatus.BUDGET_EXHAUSTED, consumed.status)

        val read = journal.readTask("task_final_success", "boot-a", 120)!!
        assertEquals(BrowserTaskStatus.BUDGET_EXHAUSTED, read.status)
        assertEquals(consumed.revision, read.revision)
        assertEquals(
            BrowserTaskUpdateResult.InvalidTransition,
            journal.transitionTask(
                "task_final_success", read.revision, BrowserTaskStatus.RUNNING, null, null,
                automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 125, currentBootId = "boot-a",
            ),
        )
        val completed = journal.transitionTask(
            "task_final_success", read.revision, BrowserTaskStatus.COMPLETED, null, null,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 130, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        assertEquals(BrowserTaskStatus.COMPLETED, completed.record.status)
        assertEquals(1, completed.record.operationsUsed)
    }

    @Test
    fun exhaustedBudgetIsNotExpiredButDeadlineStillExpiresIt() {
        val task = (journal.createTask("task_budget_deadline", BrowserTaskBudget(100, 1), "boot-a", 100, null)
            as BrowserTaskCreateResult.Created).record
        val consumed = journal.consumeOperation("task_budget_deadline", task.revision, 110, "boot-a")!!
        assertEquals(BrowserTaskStatus.BUDGET_EXHAUSTED, consumed.status)
        assertEquals(BrowserTaskStatus.BUDGET_EXHAUSTED, journal.readTask("task_budget_deadline", "boot-a", 199)?.status)
        assertEquals(BrowserTaskStatus.EXPIRED, journal.readTask("task_budget_deadline", "boot-a", 200)?.status)
    }

    @Test
    fun oldBootTasksExpireAndRemainManagedWithoutRenewingDeadline() {
        val identity = BrowserTaskPageIdentity(1, 1, "page_a", 0)
        repeat(20) { index ->
            assertTrue(journal.createTask("old_$index", BrowserTaskBudget(), "boot-a", 100L + index, if (index == 0) identity else null) is BrowserTaskCreateResult.Created)
        }
        val unknown = effect("old_task_unknown", 100).copy(taskId = "old_0", pageIdentity = identity)
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(unknown))
        assertTrue(journal.finishEffect("old_task_unknown", "a".repeat(64), null, null, 110, taskId = unknown.taskId))
        journal.recoverAfterProcessStart("boot-b", 20)
        assertEquals(20, journal.listTasks().size)
        assertTrue(journal.listTasks().all { it.status == BrowserTaskStatus.EXPIRED })
        assertTrue(journal.createTask("new_task", BrowserTaskBudget(), "boot-b", 30, null) is BrowserTaskCreateResult.Created)
        assertEquals(21, journal.listTasks().size)
        assertEquals(BrowserEffectPhase.UNKNOWN, journal.getEffect("old_task_unknown")?.phase)
    }

    @Test
    fun twentyActiveTasksAreCappedButTerminalHistoryDoesNotUseActiveSlots() {
        repeat(20) { index ->
            assertTrue(journal.createTask("active_$index", BrowserTaskBudget(), "boot-a", 100L + index, null) is BrowserTaskCreateResult.Created)
        }
        assertTrue(journal.createTask("active_overflow", BrowserTaskBudget(), "boot-a", 200, null) is BrowserTaskCreateResult.Full)
        for (index in 0 until 20) {
            val task = journal.getTask("active_$index")!!
            assertTrue(journal.transitionTask(task.taskId, task.revision, BrowserTaskStatus.FAILED, null, null,
                automationEnabled = false, ownerIsAi = false, nowElapsedMillis = 300L + index, currentBootId = "boot-a") is BrowserTaskUpdateResult.Updated)
        }
        assertTrue(journal.createTask("after_terminal", BrowserTaskBudget(), "boot-a", 400, null) is BrowserTaskCreateResult.Created)
        assertEquals(21, journal.listTasks().size)
    }

    @Test
    fun totalHistoryCapacityFailsWithoutDeletingOldTasks() {
        val db = journal.writableDatabase
        repeat(512) { index -> db.execSQL(
            "INSERT INTO tasks(task_id,revision,status,created_elapsed,deadline_elapsed,boot_id,operation_limit,operations_used,updated_elapsed) " +
                "VALUES(?,0,'completed',?,?,?,1,0,?)",
            arrayOf("history_$index", 10L + index, 20L + index, "boot-a", 10L + index),
        ) }
        assertTrue(journal.createTask("capacity_overflow", BrowserTaskBudget(), "boot-a", 1_000, null) is BrowserTaskCreateResult.Full)
        assertEquals(512, journal.taskCount())
        assertEquals(BrowserTaskStatus.COMPLETED, journal.getTask("history_0")?.status)
        assertEquals(100, journal.listTasks("boot-a", 2_000, 0, 100).size)
        assertEquals(12, journal.listTasks("boot-a", 2_000, 500, 100).size)
    }

    @Test
    fun taskManagementMetadataSurvivesReopenAndExpiredTaskCannotResume() {
        val created = (journal.createTask("task_manage", BrowserTaskBudget(100, 3), "boot-a", 100, null,
            "Draft title", "Finish the report", "User note", 1_800_000_000_000L) as BrowserTaskCreateResult.Created).record
        assertEquals(BrowserTaskStatus.EXPIRED, journal.readTask(created.taskId, "boot-a", 200)?.status)
        journal.close()
        journal = BrowserTaskJournal(context)
        val restored = journal.getTask(created.taskId)!!
        assertEquals("Draft title", restored.title)
        assertEquals("Finish the report", restored.goal)
        assertEquals("User note", restored.notes)
        assertEquals(1_800_000_000_000L, restored.createdAtWallMillis)
        assertTrue(restored.updatedAtWallMillis >= restored.createdAtWallMillis)
        assertEquals(1_800_000_000_000L, restored.updatedAtWallMillis)
        assertEquals(BrowserTaskStatus.EXPIRED, restored.status)
    }

    @Test
    fun versionThreeJournalUpgradePreservesExpiredTask() {
        journal.close()
        context.deleteDatabase("browser-task-journal.db")
        val dbFile = context.getDatabasePath("browser-task-journal.db")
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("CREATE TABLE tasks (task_id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, status TEXT NOT NULL, " +
            "created_elapsed INTEGER NOT NULL, deadline_elapsed INTEGER NOT NULL, boot_id TEXT NOT NULL, operation_limit INTEGER NOT NULL, " +
            "operations_used INTEGER NOT NULL, session_generation INTEGER, control_generation INTEGER, page_id TEXT, " +
            "document_generation INTEGER, wait_reason TEXT, updated_elapsed INTEGER NOT NULL)")
        db.execSQL("INSERT INTO tasks(task_id,revision,status,created_elapsed,deadline_elapsed,boot_id,operation_limit,operations_used,updated_elapsed) " +
            "VALUES('legacy_expired',2,'expired',10,100,'boot-a',3,1,90)")
        db.version = 3
        db.close()

        journal = BrowserTaskJournal(context)
        val restored = journal.getTask("legacy_expired")!!
        assertEquals(BrowserTaskStatus.EXPIRED, restored.status)
        assertEquals(2L, restored.revision)
        assertEquals("", restored.goal)
        assertEquals(0, restored.createdAtWallMillis)
        assertEquals(0, restored.updatedAtWallMillis)
        assertEquals(null, restored.failureReason)
        assertEquals(5, journal.readableDatabase.version)
    }

    @Test
    fun versionFourJournalUpgradeAddsNullableFailureReasonWithoutChangingOldRows() {
        journal.close()
        context.deleteDatabase("browser-task-journal.db")
        val dbFile = context.getDatabasePath("browser-task-journal.db")
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.execSQL("""CREATE TABLE tasks (
            task_id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, status TEXT NOT NULL,
            created_elapsed INTEGER NOT NULL, deadline_elapsed INTEGER NOT NULL, boot_id TEXT NOT NULL,
            operation_limit INTEGER NOT NULL, operations_used INTEGER NOT NULL, session_generation INTEGER,
            control_generation INTEGER, page_id TEXT, document_generation INTEGER, wait_reason TEXT,
            updated_elapsed INTEGER NOT NULL, title TEXT NOT NULL DEFAULT '', goal TEXT NOT NULL DEFAULT '',
            notes TEXT NOT NULL DEFAULT '', created_wall INTEGER NOT NULL DEFAULT 0,
            updated_wall INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("""INSERT INTO tasks(
            task_id,revision,status,created_elapsed,deadline_elapsed,boot_id,operation_limit,
            operations_used,updated_elapsed
        ) VALUES('legacy_failed',4,'failed',100,1000,'boot-a',3,1,120)""")
        db.version = 4
        db.close()

        journal = BrowserTaskJournal(context)
        val restored = journal.getTask("legacy_failed")!!
        assertEquals(BrowserTaskStatus.FAILED, restored.status)
        assertEquals(null, restored.failureReason)
        assertEquals(5, journal.readableDatabase.version)
        assertTrue(journal.readableDatabase.rawQuery("PRAGMA table_info(tasks)", null).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.contains("failure_reason")
        })
    }

    @Test
    fun failedTaskFailureReasonPersistsAcrossReopenAndRejectsInvalidValues() {
        val created = (journal.createTask(
            "task_failure_reason", BrowserTaskBudget(), "boot-a", 100, null,
        ) as BrowserTaskCreateResult.Created).record
        assertEquals(
            BrowserTaskUpdateResult.InvalidTransition,
            journal.transitionTask(
                created.taskId, created.revision, BrowserTaskStatus.FAILED, null, null,
                automationEnabled = false, ownerIsAi = false, nowElapsedMillis = 110,
                currentBootId = "boot-a", failureReason = "   ",
            ),
        )
        val failed = journal.transitionTask(
            created.taskId, created.revision, BrowserTaskStatus.FAILED, null, null,
            automationEnabled = false, ownerIsAi = false, nowElapsedMillis = 110,
            currentBootId = "boot-a", failureReason = "Network unavailable",
        ) as BrowserTaskUpdateResult.Updated
        assertEquals("Network unavailable", failed.record.failureReason)

        journal.close()
        journal = BrowserTaskJournal(context)
        assertEquals("Network unavailable", journal.getTask(created.taskId)?.failureReason)
    }

    @Test
    fun taskEditDoesNotChangeEffectReceiptAndDeleteRequiresTerminalWithoutUnknown() {
        val identity = BrowserTaskPageIdentity(7, 8, "page_edit", 1)
        val created = (journal.createTask("task_edit", BrowserTaskBudget(10_000, 3), "boot-a", 100, identity,
            "old", "goal", "", 1_800_000_000_000L) as BrowserTaskCreateResult.Created).record
        val receipt = effect("req_edit", 101).copy(taskId = created.taskId, pageIdentity = identity)
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(receipt))
        assertTrue(journal.finishEffect(receipt.requestId, receipt.payloadHmacSha256, null, null, 102, taskId = receipt.taskId))
        val current = journal.getTask(created.taskId)!!
        assertTrue(current.updatedAtWallMillis >= current.createdAtWallMillis)
        assertEquals(created.createdAtWallMillis, current.updatedAtWallMillis)
        val edited = journal.updateManagedTask(created.taskId, current.revision, "new", "new goal", "memo", 1_800_000_000_100L)
            as BrowserTaskUpdateResult.Updated
        assertEquals("new goal", edited.record.goal)
        assertEquals(BrowserEffectPhase.UNKNOWN, journal.getEffect(receipt.requestId)?.phase)
        assertEquals(BrowserTaskDeleteResult.NotTerminal, journal.deleteManagedTask(created.taskId, edited.record.revision))

        val terminal = journal.transitionTask(created.taskId, edited.record.revision, BrowserTaskStatus.FAILED, null, null,
            automationEnabled = false, ownerIsAi = false, nowElapsedMillis = 103, currentBootId = "boot-a") as BrowserTaskUpdateResult.Updated
        assertEquals(BrowserTaskDeleteResult.UnresolvedEffects, journal.deleteManagedTask(created.taskId, terminal.record.revision))
    }

    @Test
    fun confirmedReceiptSurvivesRestartAndDifferentPayloadConflicts() {
        val originalIdentity = BrowserTaskPageIdentity(4, 9, "page_a", 2)
        val original = effect("req_done", 10).copy(pageIdentity = originalIdentity)
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(original))
        assertTrue(journal.finishEffect("req_done", "a".repeat(64), "OK", true, 20, taskId = original.taskId))
        journal.close()
        journal = BrowserTaskJournal(context)
        journal.recoverAfterProcessStart("boot-a", 30)

        assertEquals(BrowserEffectPhase.CONFIRMED, journal.getEffect("req_done")?.phase)
        assertEquals(
            BrowserEffectAdmission.Duplicate(BrowserEffectPhase.CONFIRMED, "OK", true),
            journal.reserveEffect(effect("req_done", 40).copy(pageIdentity = originalIdentity)),
        )
        assertEquals(
            BrowserEffectAdmission.RequestIdConflict,
            journal.reserveEffect(effect("req_done", 40).copy(pageIdentity = originalIdentity.copy(
                controlGeneration = 10, pageId = "page_b", documentGeneration = 8,
            ))),
        )
        assertEquals(
            BrowserEffectAdmission.RequestIdConflict,
            journal.reserveEffect(effect("req_done", 40).copy(pageIdentity = originalIdentity, payloadHmacSha256 = "b".repeat(64))),
        )
    }

    @Test
    fun completionAtOrAfterRequestDeadlineIsStoredAsUnknown() {
        val intent = effect("late_completion", 10)
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(intent))
        assertTrue(journal.finishEffect(
            "late_completion", intent.payloadHmacSha256, "OK", true, intent.deadlineElapsedMillis,
            taskId = intent.taskId,
        ))
        assertEquals(BrowserEffectPhase.UNKNOWN, journal.getEffect("late_completion")?.phase)
        assertEquals(null, journal.getEffect("late_completion")?.terminalCode)
        assertEquals(null, journal.getEffect("late_completion")?.terminalExecuted)
    }

    @Test
    fun unknownRequiresFreshAuthorizedSamePageVerificationAndKeepsRequestIdReceipt() {
        val identity = BrowserTaskPageIdentity(4, 9, "page_a", 2)
        val entry = effect("req_verify", 10).copy(pageIdentity = identity)
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(entry))
        assertTrue(journal.finishEffect("req_verify", "a".repeat(64), null, null, 20, taskId = entry.taskId))
        val verified = BrowserEffectVerification(
            requestId = "req_verify", payloadHmacSha256 = "a".repeat(64),
            observedIdentity = identity.copy(controlGeneration = 10, documentGeneration = 3),
            observationId = "observe_1", observedAtElapsedMillis = 40,
            result = BrowserEffectVerificationResult.EFFECT_PRESENT,
        )
        assertFalse(journal.reconcileUnknown(verified, automationEnabled = true, ownerIsAi = false, currentBootId = "boot-a", nowElapsedMillis = 45))
        assertFalse(journal.reconcileUnknown(verified.copy(observedAtElapsedMillis = 10), true, true, "boot-a", 20_000))
        assertFalse(journal.reconcileUnknown(verified.copy(observedIdentity = identity.copy(pageId = "other")), true, true, "boot-a", 45))
        assertTrue(journal.reconcileUnknown(verified, automationEnabled = true, ownerIsAi = true, currentBootId = "boot-a", nowElapsedMillis = 45))
        assertEquals(
            BrowserEffectAdmission.Duplicate(BrowserEffectPhase.RECONCILED, "RECONCILED_PRESENT", true),
            journal.reserveEffect(entry.copy(createdElapsedMillis = 50, updatedElapsedMillis = 50, deadlineElapsedMillis = 60_050)),
        )
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(effect("req_verify_next", 60)))
    }

    @Test
    fun crossBootConfirmedRowsAreBoundedAndFailClosedInsteadOfGrowing() {
        repeat(512) { index ->
            val id = "done_$index"
            val digest = index.toString(16).padStart(64, '0').takeLast(64)
            val entry = effect(id, 10).copy(payloadHmacSha256 = digest)
            assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(entry))
            assertTrue(journal.finishEffect(id, digest, "OK", true, 20, taskId = entry.taskId))
        }
        journal.recoverAfterProcessStart("boot-b", 5)
        assertEquals(
            BrowserEffectAdmission.Full(BrowserJournalCapacity.TOTAL_RECEIPTS),
            journal.reserveEffect(effect("new_after_boot", 10).copy(bootId = "boot-b")),
        )
        assertEquals(BrowserEffectPhase.CONFIRMED, journal.getEffect("done_0")?.phase)
    }

    @Test
    fun requestFingerprintUsesPersistentPrivateHmacKey() {
        val keyFile = File(context.getDatabasePath("browser-task-journal.db").parentFile, "browser-task-hmac.key")
        keyFile.delete()
        val first = BrowserTaskPayloadHmac(context).digest("order 5".toByteArray())
        val guessed = BrowserTaskPayloadHmac(context).digest("order 6".toByteArray())
        assertEquals(64, first.length)
        assertFalse(first == guessed)
        keyFile.delete()
    }

    @Test
    fun malformedOrOversizedHmacKeyIsRejectedWithoutRotation() {
        val keyFile = File(context.getDatabasePath("browser-task-journal.db").parentFile, "browser-task-hmac.key")
        val backupFile = File(keyFile.path + ".bak")
        keyFile.delete()
        backupFile.delete()

        listOf(ByteArray(31) { 0x31 }, ByteArray(4096) { 0x42 }).forEach { invalidKey ->
            keyFile.writeBytes(invalidKey)
            assertThrows(IllegalStateException::class.java) {
                BrowserTaskPayloadHmac(context).digest("payload".toByteArray())
            }
            assertTrue(keyFile.readBytes().contentEquals(invalidKey))
            keyFile.delete()
        }
    }

    @Test
    fun resumeNeedsFreshMatchingIdentityAndUnchangedAuthorization() {
        val identity = BrowserTaskPageIdentity(4, 9, "page_a", 2)
        val record = (journal.createTask("task_resume", BrowserTaskBudget(), "boot-a", 100, identity) as BrowserTaskCreateResult.Created).record
        val paused = journal.transitionTask(
            "task_resume", record.revision, BrowserTaskStatus.PAUSED, null, identity,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 120, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        val rejected = journal.transitionTask(
            "task_resume", paused.record.revision, BrowserTaskStatus.RUNNING, null, identity.copy(documentGeneration = 3),
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 130, currentBootId = "boot-a",
        )
        assertEquals(BrowserTaskUpdateResult.AuthorizationOrPageMismatch, rejected)
        val resumed = journal.transitionTask(
            "task_resume", paused.record.revision, BrowserTaskStatus.RUNNING, null, identity,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 140, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        assertEquals(BrowserTaskStatus.RUNNING, resumed.record.status)
        assertEquals(0, resumed.record.operationsUsed)
    }

    @Test
    fun reconcileRefreshesControlAndDocumentOnlyAfterSamePageReobservation() {
        val identity = BrowserTaskPageIdentity(4, 9, "page_a", 2)
        val created = (journal.createTask("task_recover", BrowserTaskBudget(), "boot-a", 100, identity) as BrowserTaskCreateResult.Created).record
        val needs = journal.transitionTask(
            "task_recover", created.revision, BrowserTaskStatus.NEEDS_RECONCILIATION,
            com.dshbox.browser.api.BrowserTaskWaitReason.RECOVERY_CHECK, identity,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 120, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        val refreshed = identity.copy(controlGeneration = 10, documentGeneration = 3)
        val mismatch = journal.reconcileTask(
            "task_recover", needs.record.revision, refreshed.copy(pageId = "other_page"),
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 130, currentBootId = "boot-a",
        )
        assertEquals(BrowserTaskUpdateResult.AuthorizationOrPageMismatch, mismatch)
        val reconciled = journal.reconcileTask(
            "task_recover", needs.record.revision, refreshed,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 140, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        assertEquals(BrowserTaskStatus.PAUSED, reconciled.record.status)
        assertEquals(refreshed, reconciled.record.pageIdentity)
        val resumed = journal.transitionTask(
            "task_recover", reconciled.record.revision, BrowserTaskStatus.RUNNING, null, refreshed,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 150, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        assertEquals(BrowserTaskStatus.RUNNING, resumed.record.status)
    }

    @Test
    fun androidExposesStableBootIdentityForProcessRecovery() {
        val first = browserSystemBootId(context)
        val second = browserSystemBootId(context)
        assertTrue(first != null && first.isNotBlank())
        assertEquals(first, second)
    }

    @Test
    fun recoveryBindingAndUserAttestationAreAtomicBoundedAndNeverRewriteEffectReceipt() {
        val oldIdentity = BrowserTaskPageIdentity(4, 9, "page_old", 2)
        val created = (journal.createTask("task_bind", BrowserTaskBudget(), "boot-a", 100, oldIdentity)
            as BrowserTaskCreateResult.Created).record
        val originalEffect = effect("req_bind_unknown", 110).copy(
            taskId = created.taskId, pageIdentity = oldIdentity, deadlineElapsedMillis = 125,
        )
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(originalEffect))
        assertTrue(journal.finishEffect(originalEffect.requestId, originalEffect.payloadHmacSha256, null, null, 120, taskId = originalEffect.taskId))
        journal.recoverAfterProcessStart("boot-a", 130)
        val needsRecovery = journal.getTask(created.taskId)!!
        val newIdentity = BrowserTaskPageIdentity(7, 3, "page_new", 1)

        val bound = journal.consumeRecoveryBinding(
            created.taskId, needsRecovery.revision, oldIdentity, newIdentity, "a".repeat(32),
            "boot-a", 140, 500,
        )!!
        assertEquals(BrowserTaskStatus.PAUSED, bound.status)
        assertEquals(newIdentity, bound.pageIdentity)
        assertEquals(BrowserEffectPhase.UNKNOWN, journal.getEffect(originalEffect.requestId)?.phase)
        assertEquals(oldIdentity, journal.getEffect(originalEffect.requestId)?.pageIdentity)
        assertEquals(null, journal.consumeRecoveryBinding(
            created.taskId, needsRecovery.revision, oldIdentity, newIdentity, "a".repeat(32),
            "boot-a", 141, 500,
        ))
        assertEquals(BrowserEffectAdmission.TaskRejected("TASK_UNKNOWN_EFFECT_UNVERIFIED"), journal.reserveEffect(
            effect("req_blocked_new_id", 150).copy(taskId = bound.taskId, pageIdentity = newIdentity),
        ))
        assertFalse(journal.recordUserEffectVerification(
            bound.taskId, bound.revision - 1, originalEffect.requestId, newIdentity,
            com.dshbox.browser.api.BrowserUserEffectChoice.NOT_OCCURRED, "boot-a", 160,
        ))
        assertFalse(journal.recordUserEffectVerification(
            bound.taskId, bound.revision, originalEffect.requestId, newIdentity,
            com.dshbox.browser.api.BrowserUserEffectChoice.NOT_OCCURRED, "boot-a", 139,
        ))
        assertTrue(journal.recordUserEffectVerification(
            bound.taskId, bound.revision, originalEffect.requestId, newIdentity,
            com.dshbox.browser.api.BrowserUserEffectChoice.NOT_OCCURRED, "boot-a", 160,
        ))
        // Metadata reads run the safe-pruning pass; active task/effect history must survive it.
        journal.listRecoveryMetadata("boot-a", 161)
        assertEquals(1, journal.writableDatabase.rawQuery(
            "SELECT row_id FROM user_effect_verification WHERE task_id=?", arrayOf(bound.taskId),
        ).use { it.count })
        assertEquals(1, journal.writableDatabase.rawQuery(
            "SELECT row_id FROM task_binding_audit WHERE task_id=?", arrayOf(bound.taskId),
        ).use { it.count })
        assertEquals(
            com.dshbox.browser.api.BrowserUserEffectChoice.NOT_OCCURRED,
            journal.listRecoveryMetadata("boot-a", 160).first { it.task.taskId == bound.taskId }
                .unknownEffects.single().lastUserChoice,
        )
        val resumed = journal.transitionTask(
            bound.taskId, bound.revision, BrowserTaskStatus.RUNNING, null, newIdentity,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 170, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        val admitted = journal.reserveEffect(effect("req_after_user_check", 180).copy(
            taskId = bound.taskId, pageIdentity = newIdentity,
        ))
        assertEquals(BrowserEffectAdmission.NewIntent, admitted)
        assertEquals(BrowserEffectPhase.UNKNOWN, journal.getEffect(originalEffect.requestId)?.phase)
        assertEquals(
            BrowserEffectAdmission.Duplicate(BrowserEffectPhase.UNKNOWN, null, null),
            journal.reserveEffect(originalEffect.copy(
                createdElapsedMillis = 181, updatedElapsedMillis = 181, deadlineElapsedMillis = 60_181,
            )),
        )

        val paused = journal.transitionTask(
            bound.taskId, resumed.record.revision + 1, BrowserTaskStatus.PAUSED, null, newIdentity,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 190, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        val reboundIdentity = BrowserTaskPageIdentity(8, 4, "page_again", 0)
        val rebound = journal.consumeRecoveryBinding(
            bound.taskId, paused.record.revision, newIdentity, reboundIdentity, "b".repeat(32),
            "boot-a", 200, 600,
        )!!
        assertEquals(BrowserTaskUpdateResult.UnknownEffectUnverified, journal.transitionTask(
            rebound.taskId, rebound.revision, BrowserTaskStatus.RUNNING, null, reboundIdentity,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 210, currentBootId = "boot-a",
        ))
        val reboundEffects = journal.listRecoveryMetadata("boot-a", 210)
            .first { it.task.taskId == rebound.taskId }.unknownEffects
        assertEquals(2, reboundEffects.size)
        assertEquals(null, reboundEffects.single { it.requestId == originalEffect.requestId }.lastUserChoice)
        assertEquals(BrowserEffectPhase.INTENT,
            reboundEffects.single { it.requestId == "req_after_user_check" }.phase)
        assertEquals(2, journal.writableDatabase.rawQuery("SELECT row_id FROM task_binding_audit WHERE task_id=?", arrayOf(bound.taskId)).use { it.count })
        assertTrue(journal.writableDatabase.rawQuery(
            "SELECT row_id FROM task_binding_audit WHERE task_id=? AND actor='USER' AND old_page_id=? AND page_id=?",
            arrayOf(bound.taskId, newIdentity.pageId, reboundIdentity.pageId),
        ).use { it.moveToFirst() })
    }

    @Test
    fun versionOneJournalMigrationAddsAuditTablesWithoutDroppingExistingTables() {
        journal.close()
        context.deleteDatabase("browser-task-journal.db")
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("browser-task-journal.db"), null)
        legacy.execSQL("CREATE TABLE tasks (legacy_marker TEXT NOT NULL)")
        legacy.execSQL("CREATE TABLE effects (legacy_marker TEXT NOT NULL)")
        legacy.execSQL("INSERT INTO tasks (legacy_marker) VALUES ('task-preserved')")
        legacy.execSQL("INSERT INTO effects (legacy_marker) VALUES ('effect-preserved')")
        legacy.version = 1
        legacy.close()

        journal = BrowserTaskJournal(context)
        val migrated = journal.writableDatabase
        assertTrue(migrated.rawQuery("PRAGMA table_info(tasks)", null).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.contains("legacy_marker")
        })
        assertTrue(migrated.rawQuery("PRAGMA table_info(effects)", null).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.contains("legacy_marker")
        })
        assertEquals("task-preserved", migrated.rawQuery("SELECT legacy_marker FROM tasks", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        })
        assertEquals("effect-preserved", migrated.rawQuery("SELECT legacy_marker FROM effects", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        })
        assertTrue(migrated.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='task_binding_audit'", null)
            .use { it.moveToFirst() })
        assertTrue(migrated.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='user_effect_verification'", null)
            .use { it.moveToFirst() })
        assertTrue(migrated.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='event_dialog_resolution_audit'", null)
            .use { it.moveToFirst() })
    }

    @Test
    fun versionTwoUpgradeAddsEventAuditAndPreservesTaskAndEffectRows() {
        journal.close()
        context.deleteDatabase("browser-task-journal.db")
        val legacy = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("browser-task-journal.db"), null)
        legacy.execSQL("""CREATE TABLE tasks (
            task_id TEXT PRIMARY KEY NOT NULL, revision INTEGER NOT NULL, status TEXT NOT NULL,
            created_elapsed INTEGER NOT NULL, deadline_elapsed INTEGER NOT NULL, boot_id TEXT NOT NULL,
            operation_limit INTEGER NOT NULL, operations_used INTEGER NOT NULL, session_generation INTEGER,
            control_generation INTEGER, page_id TEXT, document_generation INTEGER, wait_reason TEXT,
            updated_elapsed INTEGER NOT NULL
        )""")
        legacy.execSQL("""CREATE TABLE effects (
            request_id TEXT PRIMARY KEY NOT NULL, payload_hmac_sha256 TEXT NOT NULL, method TEXT NOT NULL,
            kind TEXT NOT NULL, task_id TEXT, session_generation INTEGER, control_generation INTEGER,
            page_id TEXT, document_generation INTEGER, deadline_elapsed INTEGER NOT NULL, boot_id TEXT NOT NULL,
            phase TEXT NOT NULL, created_elapsed INTEGER NOT NULL, updated_elapsed INTEGER NOT NULL,
            terminal_code TEXT, terminal_executed INTEGER
        )""")
        legacy.execSQL("CREATE INDEX effects_phase_created ON effects(phase, created_elapsed)")
        legacy.execSQL("CREATE INDEX effects_task_id ON effects(task_id)")
        legacy.execSQL("""CREATE TABLE task_binding_audit (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, task_revision INTEGER NOT NULL,
            old_revision INTEGER NOT NULL, old_status TEXT NOT NULL, old_created_elapsed INTEGER NOT NULL,
            old_deadline_elapsed INTEGER NOT NULL, old_updated_elapsed INTEGER NOT NULL, old_boot_id TEXT NOT NULL,
            old_operation_limit INTEGER NOT NULL, old_operations_used INTEGER NOT NULL, old_wait_reason TEXT,
            old_session_generation INTEGER, old_control_generation INTEGER, old_page_id TEXT, old_document_generation INTEGER,
            nonce TEXT NOT NULL UNIQUE, session_generation INTEGER NOT NULL, control_generation INTEGER NOT NULL,
            page_id TEXT NOT NULL, document_generation INTEGER NOT NULL, boot_id TEXT NOT NULL,
            bound_elapsed INTEGER NOT NULL, actor TEXT NOT NULL CHECK(actor='USER')
        )""")
        legacy.execSQL("CREATE INDEX task_binding_latest ON task_binding_audit(task_id,row_id)")
        legacy.execSQL("""CREATE TABLE user_effect_verification (
            row_id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL, task_revision INTEGER NOT NULL,
            effect_id TEXT NOT NULL, choice TEXT NOT NULL CHECK(choice IN ('occurred','not_occurred','still_unknown')),
            session_generation INTEGER NOT NULL, control_generation INTEGER NOT NULL, page_id TEXT NOT NULL,
            document_generation INTEGER NOT NULL, verified_elapsed INTEGER NOT NULL, actor TEXT NOT NULL CHECK(actor='USER')
        )""")
        legacy.execSQL("CREATE INDEX user_effect_latest ON user_effect_verification(effect_id,row_id)")
        legacy.execSQL("""INSERT INTO tasks(task_id,revision,status,created_elapsed,deadline_elapsed,boot_id,
            operation_limit,operations_used,session_generation,control_generation,page_id,document_generation,wait_reason,updated_elapsed)
            VALUES('task_v2_preserved',2,'running',100,1000,'boot-a',3,1,2,3,'page_v2',4,NULL,110)""")
        legacy.execSQL("""INSERT INTO effects(request_id,payload_hmac_sha256,method,kind,task_id,session_generation,
            control_generation,page_id,document_generation,deadline_elapsed,boot_id,phase,created_elapsed,updated_elapsed)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf(
                "effect_v2_preserved", "a".repeat(64), "click", "potentially_mutating", "task_v2_preserved",
                2, 3, "page_v2", 4, 900, "boot-a", "intent", 110, 110,
            ))
        legacy.version = 2
        legacy.close()

        journal = BrowserTaskJournal(context)
        val upgraded = journal.writableDatabase
        val restoredTask = journal.getTask("task_v2_preserved")!!
        assertEquals("task_v2_preserved", restoredTask.taskId)
        assertEquals(BrowserTaskStatus.RUNNING, restoredTask.status)
        assertEquals(2L, restoredTask.revision)
        assertEquals(1, restoredTask.operationsUsed)
        assertEquals(BrowserTaskPageIdentity(2, 3, "page_v2", 4), restoredTask.pageIdentity)
        assertEquals("", restoredTask.title)
        assertEquals("", restoredTask.goal)
        assertEquals("", restoredTask.notes)
        assertEquals(0, restoredTask.createdAtWallMillis)
        assertEquals(0, restoredTask.updatedAtWallMillis)
        val restoredEffect = journal.getEffect("effect_v2_preserved")!!
        assertEquals(BrowserEffectPhase.INTENT, restoredEffect.phase)
        assertEquals("task_v2_preserved", restoredEffect.taskId)
        assertEquals("a".repeat(64), restoredEffect.payloadHmacSha256)
        assertTrue(upgraded.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='event_dialog_resolution_audit'", null,
        ).use { it.moveToFirst() })
    }

    @Test
    fun dialogDecisionAuditIsOneUseAndStoresOnlyPromptHmac() {
        val identity = BrowserTaskPageIdentity(2, 3, "page_dialog", 4)
        val created = (journal.createTask("task_dialog_audit", BrowserTaskBudget(), "boot-a", 100, identity)
            as BrowserTaskCreateResult.Created).record
        val parentId = "parent_dialog_audit"
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(
            effect(parentId, 110).copy(method = "click", taskId = created.taskId, pageIdentity = identity),
        ))
        val current = journal.getTask(created.taskId)!!
        val eventId = "a".repeat(32)
        assertTrue(journal.recordEventDialogResolution(
            created.taskId, current.revision, eventId, parentId, identity, "prompt", "b".repeat(64),
            "boot-a", 120,
        ))
        assertFalse(journal.recordEventDialogResolution(
            created.taskId, current.revision, eventId, parentId, identity, "prompt", "c".repeat(64),
            "boot-a", 121,
        ))
        assertTrue(journal.completeEventDialogResolution(eventId, true))
        assertFalse(journal.completeEventDialogResolution(eventId, false))
        val audit = journal.writableDatabase.rawQuery(
            "SELECT prompt_hmac,outcome FROM event_dialog_resolution_audit WHERE event_id=?", arrayOf(eventId),
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) to cursor.getString(1) }
        assertEquals("b".repeat(64), audit.first)
        assertEquals("RESOLVED", audit.second)
    }

    @Test
    fun interruptedUserWaitCanBeBoundOnFreshPageAndStaysPaused() {
        val oldIdentity = BrowserTaskPageIdentity(2, 2, "page_login", 1)
        val created = (journal.createTask("task_login_wait", BrowserTaskBudget(), "boot-a", 100, oldIdentity)
            as BrowserTaskCreateResult.Created).record
        val waiting = journal.transitionTask(
            created.taskId, created.revision, BrowserTaskStatus.WAITING_USER,
            com.dshbox.browser.api.BrowserTaskWaitReason.USER_ACTION, oldIdentity,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 110, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated
        val currentPage = BrowserTaskPageIdentity(3, 4, "page_after_login", 0)
        val bound = journal.consumeRecoveryBinding(
            created.taskId, waiting.record.revision, oldIdentity, currentPage, "c".repeat(32),
            "boot-a", 120, 300,
        )!!
        assertEquals(BrowserTaskStatus.PAUSED, bound.status)
        assertEquals(null, bound.waitReason)
        assertEquals(currentPage, bound.pageIdentity)
    }

    @Test
    fun oldTerminalRecoveryHistoryIsCollectedAfterRetentionWithoutBlockingNewBinding() {
        val identity = BrowserTaskPageIdentity(1, 1, "page_0", 0)
        val created = (journal.createTask("task_many_bindings", BrowserTaskBudget(), "boot-a", 100, identity)
            as BrowserTaskCreateResult.Created).record
        var current = (journal.transitionTask(
            created.taskId, created.revision, BrowserTaskStatus.PAUSED, null, identity,
            automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 105, currentBootId = "boot-a",
        ) as BrowserTaskUpdateResult.Updated).record
        var currentIdentity = identity
        repeat(256) { index ->
            val nextIdentity = BrowserTaskPageIdentity(index + 2L, index + 2L, "page_$index", index.toLong())
            current = journal.consumeRecoveryBinding(
                current.taskId, current.revision, currentIdentity, nextIdentity,
                ("n" + index.toString().padStart(31, '0')), "boot-a", 110L + index, 1_000L + index,
            )!!
            currentIdentity = nextIdentity
        }
        assertEquals(256, journal.writableDatabase.rawQuery("SELECT row_id FROM task_binding_audit", null).use { it.count })
        assertTrue(journal.transitionTask(
            current.taskId, current.revision, BrowserTaskStatus.FAILED, null, currentIdentity,
            automationEnabled = false, ownerIsAi = false, nowElapsedMillis = 1_000,
            currentBootId = "boot-a",
        ) is BrowserTaskUpdateResult.Updated)

        val now = BrowserTaskBudget.DEFAULT_DURATION_MILLIS + 24L * 60 * 60 * 1000 + 2_000
        val fresh = journal.createTask("task_after_audit_cleanup", BrowserTaskBudget(), "boot-a", now, identity)
        assertTrue(fresh is BrowserTaskCreateResult.Created)
        assertEquals(0, journal.writableDatabase.rawQuery("SELECT row_id FROM task_binding_audit", null).use { it.count })
        journal.recoverAfterProcessStart("boot-a", now + 5)
        val ready = journal.getTask("task_after_audit_cleanup")!!
        assertTrue(journal.consumeRecoveryBinding(
            "task_after_audit_cleanup", ready.revision, identity, currentIdentity,
            "z".repeat(32), "boot-a", now + 10, now + 1_000,
        ) != null)
        assertEquals(1, journal.writableDatabase.rawQuery("SELECT row_id FROM task_binding_audit", null).use { it.count })
    }

    @Test
    fun unknownReceiptLinksTaskCreatedAfterIntentAndRequiresReconciliation() {
        val identity = BrowserTaskPageIdentity(4, 9, "page_late_task", 2)
        val intent = effect("req_late_task", 100).copy(pageIdentity = identity)
        assertEquals(null, intent.taskId)
        assertEquals(BrowserEffectAdmission.NewIntent, journal.reserveEffect(intent))
        val created = (journal.createTask("task_late_link", BrowserTaskBudget(), "boot-a", 101, identity)
            as BrowserTaskCreateResult.Created).record

        assertTrue(journal.finishEffect(intent.requestId, intent.payloadHmacSha256, null, null, 102,
            taskId = created.taskId))

        assertEquals(created.taskId, journal.getEffect(intent.requestId)?.taskId)
        assertEquals(BrowserEffectPhase.UNKNOWN, journal.getEffect(intent.requestId)?.phase)
        assertEquals(BrowserTaskStatus.NEEDS_RECONCILIATION, journal.getTask(created.taskId)?.status)
        assertEquals(created.revision + 1, journal.getTask(created.taskId)?.revision)
    }

    private fun effect(requestId: String, now: Long) = BrowserEffectJournalEntry(
        requestId = requestId,
        payloadHmacSha256 = "a".repeat(64),
        method = "click",
        kind = BrowserEffectKind.POTENTIALLY_MUTATING,
        taskId = null,
        pageIdentity = null,
        deadlineElapsedMillis = now + 60_000,
        bootId = "boot-a",
        phase = BrowserEffectPhase.INTENT,
        createdElapsedMillis = now,
        updatedElapsedMillis = now,
    )
}
