package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserEffectJournalEntry
import com.dshbox.browser.api.BrowserTaskBudget
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserTaskRecord
import com.dshbox.browser.api.BrowserTaskRecoveryMetadata
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserTaskWaitReason
import com.dshbox.browser.api.BrowserUserEffectChoice

/** Persistence boundary used by BrowserTaskCoordinator while preserving journal semantics. */
internal interface BrowserTaskCoordinatorStore {
    fun recoverAfterProcessStart(currentBootId: String, nowElapsedMillis: Long)
    fun close()
    fun listRecoveryMetadata(
        currentBootId: String,
        nowElapsedMillis: Long,
        offset: Int,
        limit: Int,
    ): List<BrowserTaskRecoveryMetadata>
    fun findRecoveryMetadata(
        taskId: String,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): BrowserTaskRecoveryMetadata?
    fun updateManagedTask(
        taskId: String,
        expectedRevision: Long,
        title: String?,
        goal: String?,
        notes: String?,
        nowWallMillis: Long,
    ): BrowserTaskUpdateResult
    fun deleteManagedTask(taskId: String, expectedRevision: Long): BrowserTaskDeleteResult
    fun readEventGate(
        taskId: String,
        expectedIdentity: BrowserTaskPageIdentity,
        expectedEpoch: Long?,
        allowDocumentAdvance: Boolean,
        parentEffectId: String?,
        parentIdentity: BrowserTaskPageIdentity?,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): BrowserTaskEventGate?
    fun recordEventDialogResolution(
        taskId: String,
        expectedRevision: Long,
        eventId: String,
        parentEffectId: String,
        identity: BrowserTaskPageIdentity,
        decision: String,
        promptHmac: String?,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): Boolean
    fun completeEventDialogResolution(eventId: String, resolved: Boolean): Boolean
    fun consumeRecoveryBinding(
        taskId: String,
        expectedRevision: Long,
        expectedOldIdentity: BrowserTaskPageIdentity?,
        newIdentity: BrowserTaskPageIdentity,
        nonce: String,
        currentBootId: String,
        nowElapsedMillis: Long,
        challengeExpiresElapsedMillis: Long,
    ): BrowserTaskRecord?
    fun invalidateRecoveryBinding(
        taskId: String,
        expectedRevision: Long,
        nowElapsedMillis: Long,
    ): Boolean
    fun recordUserEffectVerification(
        taskId: String,
        expectedRevision: Long,
        effectId: String,
        observedIdentity: BrowserTaskPageIdentity,
        choice: BrowserUserEffectChoice,
        currentBootId: String,
        nowElapsedMillis: Long,
    ): Boolean
    fun reserveEffect(entry: BrowserEffectJournalEntry): BrowserEffectAdmission
    fun finishEffect(
        requestId: String,
        payloadHmacSha256: String,
        terminalCode: String?,
        executed: Boolean?,
        nowElapsedMillis: Long,
        taskId: String? = null,
    ): Boolean
    fun readTask(taskId: String, currentBootId: String, nowElapsedMillis: Long): BrowserTaskRecord?
    fun listTasks(
        currentBootId: String,
        nowElapsedMillis: Long,
        offset: Int,
        limit: Int,
    ): List<BrowserTaskRecord>
    fun taskCount(): Int
    fun hasUnresolvedEffects(taskId: String): Boolean
    fun createTask(
        taskId: String,
        budget: BrowserTaskBudget,
        bootId: String,
        nowElapsedMillis: Long,
        pageIdentity: BrowserTaskPageIdentity?,
        title: String,
        goal: String,
        notes: String,
        nowWallMillis: Long,
    ): BrowserTaskCreateResult
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
        failureReason: String?,
    ): BrowserTaskUpdateResult
    fun reconcileTask(
        taskId: String,
        expectedRevision: Long,
        observedIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
    ): BrowserTaskUpdateResult
}
