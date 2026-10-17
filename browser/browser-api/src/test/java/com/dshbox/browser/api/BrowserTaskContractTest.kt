package com.dshbox.browser.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class BrowserTaskContractTest {
    private val identity = BrowserTaskPageIdentity(3, 8, "page-abc", 12)
    private val record = BrowserTaskRecord(
        taskId = "task_1", revision = 4, status = BrowserTaskStatus.NEEDS_RECONCILIATION,
        createdElapsedMillis = 100, deadlineElapsedMillis = 1000, bootId = "boot-a",
        operationLimit = 500, operationsUsed = 4, pageIdentity = identity,
        waitReason = BrowserTaskWaitReason.RECOVERY_CHECK, updatedElapsedMillis = 200,
    )

    @Test
    fun boundedBudgetsAcceptDefaultAndMaximumAndRejectOverflow() {
        assertEquals(30 * 60 * 1000L, BrowserTaskBudget().durationMillis)
        assertEquals(500, BrowserTaskBudget().operationLimit)
        assertEquals(4 * 60 * 60 * 1000L, BrowserTaskBudget(4 * 60 * 60 * 1000L, 2000).durationMillis)
        assertThrows(IllegalArgumentException::class.java) { BrowserTaskBudget(4 * 60 * 60 * 1000L + 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { BrowserTaskBudget(1, 2001) }
    }

    @Test
    fun taskTransitionsAreFiniteAndTerminalStatesCannotResume() {
        assertTrue(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.RUNNING, BrowserTaskStatus.WAITING_PAGE))
        assertTrue(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.WAITING_USER, BrowserTaskStatus.PAUSED))
        assertFalse(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.WAITING_USER, BrowserTaskStatus.RUNNING))
        assertFalse(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.COMPLETED, BrowserTaskStatus.RUNNING))
        assertFalse(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.EXPIRED, BrowserTaskStatus.RUNNING))
    }

    @Test
    fun budgetExhaustedCanOnlyCloseTheAlreadyAdmittedFinalOperation() {
        assertTrue(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.COMPLETED))
        assertTrue(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.FAILED))
        assertFalse(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.RUNNING))
        assertFalse(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.WAITING_USER))
        assertFalse(BrowserTaskStateMachine.canTransition(BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.NEEDS_RECONCILIATION))
    }

    @Test
    fun resumeRequiresFreshMatchingPageAndCurrentAiAuthorization() {
        assertEquals(BrowserTaskTransition.Applied, BrowserTaskStateMachine.validateResume(
            record, 4, identity, automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 300, currentBootId = "boot-a",
        ))
        assertEquals(BrowserTaskTransition.PageOrAuthorizationMismatch, BrowserTaskStateMachine.validateResume(
            record, 4, identity.copy(documentGeneration = 13), true, true, 300, "boot-a",
        ))
        assertEquals(BrowserTaskTransition.PageOrAuthorizationMismatch, BrowserTaskStateMachine.validateResume(
            record, 4, identity, true, false, 300, "boot-a",
        ))
        assertEquals(BrowserTaskTransition.Expired, BrowserTaskStateMachine.validateResume(
            record, 4, identity, true, true, 1000, "boot-a",
        ))
        assertEquals(BrowserTaskTransition.Expired, BrowserTaskStateMachine.validateResume(
            record, 4, identity, true, true, 300, "boot-b",
        ))
        assertEquals(BrowserTaskTransition.StaleRevision, BrowserTaskStateMachine.validateResume(
            record, 3, identity, true, true, 300, "boot-a",
        ))
    }

    @Test
    fun resumeRejectsExhaustedOperationBudget() {
        val exhausted = record.copy(status = BrowserTaskStatus.PAUSED, waitReason = null, operationLimit = 4, operationsUsed = 4)
        assertEquals(BrowserTaskTransition.BudgetExhausted, BrowserTaskStateMachine.validateResume(
            exhausted, exhausted.revision, identity, automationEnabled = true, ownerIsAi = true,
            nowElapsedMillis = 300, currentBootId = "boot-a",
        ))
    }

    @Test
    fun reconcileRefreshesGenerationsButCannotBindAnotherPageOrSession() {
        val refreshed = identity.copy(controlGeneration = 9, documentGeneration = 13)
        fun check(candidate: BrowserTaskPageIdentity?, enabled: Boolean = true, ai: Boolean = true) =
            BrowserTaskStateMachine.validateReconcile(record, record.revision, candidate, enabled, ai, 300, "boot-a")
        assertEquals(BrowserTaskTransition.Applied, check(refreshed))
        assertEquals(BrowserTaskTransition.PageOrAuthorizationMismatch, check(refreshed.copy(pageId = "other_page")))
        assertEquals(BrowserTaskTransition.PageOrAuthorizationMismatch, check(refreshed.copy(sessionGeneration = 4)))
        assertEquals(BrowserTaskTransition.PageOrAuthorizationMismatch, check(null))
        assertEquals(BrowserTaskTransition.PageOrAuthorizationMismatch, check(refreshed, enabled = false))
        assertEquals(BrowserTaskTransition.PageOrAuthorizationMismatch, check(refreshed, ai = false))
        assertEquals(BrowserTaskTransition.Expired, BrowserTaskStateMachine.validateReconcile(
            record, record.revision, refreshed, true, true, 1000, "boot-a",
        ))
        assertEquals(BrowserTaskTransition.StaleRevision, BrowserTaskStateMachine.validateReconcile(
            record, record.revision - 1, refreshed, true, true, 300, "boot-a",
        ))
    }

    @Test
    fun waitingStatesRequireReasonAndJournalCannotStoreResponseBody() {
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(status = BrowserTaskStatus.WAITING_USER, waitReason = null)
        }
        val journal = BrowserEffectJournalEntry(
            requestId = "request_1", payloadHmacSha256 = "a".repeat(64), method = "click",
            kind = BrowserEffectKind.POTENTIALLY_MUTATING, taskId = record.taskId,
            pageIdentity = identity, deadlineElapsedMillis = 1000, bootId = "boot-a",
            phase = BrowserEffectPhase.INTENT, createdElapsedMillis = 100, updatedElapsedMillis = 100,
        )
        assertEquals(BrowserEffectPhase.INTENT, journal.phase)
        assertThrows(IllegalArgumentException::class.java) {
            journal.copy(phase = BrowserEffectPhase.CONFIRMED, terminalCode = "OK", terminalExecuted = null)
        }
    }

    @Test
    fun failureReasonIsNullableBoundedAndOnlyValidForFailedTasks() {
        assertEquals(null, record.copy(status = BrowserTaskStatus.FAILED, waitReason = null).failureReason)
        assertEquals(
            "Network unavailable",
            record.copy(
                status = BrowserTaskStatus.FAILED,
                waitReason = null,
                failureReason = "Network unavailable",
            ).failureReason,
        )
        record.copy(
            status = BrowserTaskStatus.FAILED,
            waitReason = null,
            failureReason = "x".repeat(BrowserTaskRecord.MAX_FAILURE_REASON_LENGTH),
        )
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(status = BrowserTaskStatus.FAILED, waitReason = null, failureReason = "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(
                status = BrowserTaskStatus.FAILED,
                waitReason = null,
                failureReason = "x".repeat(BrowserTaskRecord.MAX_FAILURE_REASON_LENGTH + 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            record.copy(failureReason = "Not failed")
        }
    }

    @Test
    fun recoveryBindingRequiresCurrentAuthorizationPageBootBudgetAndLiveDeadline() {
        assertTrue(BrowserTaskRecoveryPolicy.canPrepareBinding(record, identity, true, true, 300, "boot-a"))
        assertFalse(BrowserTaskRecoveryPolicy.canPrepareBinding(record, identity, true, false, 300, "boot-a"))
        assertFalse(BrowserTaskRecoveryPolicy.canPrepareBinding(record, null, true, true, 300, "boot-a"))
        assertFalse(BrowserTaskRecoveryPolicy.canPrepareBinding(record, identity, true, true, 1000, "boot-a"))
        assertFalse(BrowserTaskRecoveryPolicy.canPrepareBinding(record, identity, true, true, 300, "boot-b"))
        assertFalse(BrowserTaskRecoveryPolicy.canPrepareBinding(record, identity, true, true, 199, "boot-a"))
        val exhausted = record.copy(operationLimit = 4, operationsUsed = 4)
        assertFalse(BrowserTaskRecoveryPolicy.canPrepareBinding(exhausted, identity, true, true, 300, "boot-a"))
    }

    @Test
    fun onlyLatestExplicitUserChoiceCanReleaseUnknownMutationGate() {
        fun effect(choice: BrowserUserEffectChoice?) = BrowserUnknownEffectMetadata(
            "req_unknown", "click", 100, BrowserEffectPhase.UNKNOWN, choice != null, choice,
        )
        assertTrue(BrowserTaskRecoveryPolicy.blocksMutatingOperation(listOf(effect(null))))
        assertTrue(BrowserTaskRecoveryPolicy.blocksMutatingOperation(listOf(effect(BrowserUserEffectChoice.STILL_UNKNOWN))))
        assertFalse(BrowserTaskRecoveryPolicy.blocksMutatingOperation(listOf(effect(BrowserUserEffectChoice.NOT_OCCURRED))))
        assertFalse(BrowserTaskRecoveryPolicy.blocksMutatingOperation(listOf(effect(BrowserUserEffectChoice.OCCURRED))))
        assertTrue(BrowserTaskRecoveryPolicy.blocksMutatingOperation(listOf(
            effect(BrowserUserEffectChoice.NOT_OCCURRED), effect(BrowserUserEffectChoice.STILL_UNKNOWN),
        )))
    }
}
