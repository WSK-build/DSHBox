package com.dshbox.browser.api

/** A bounded DSH task is separate from the lifetime and ownership of its Browser page. */
enum class BrowserTaskStatus(val wireValue: String) {
    RUNNING("running"),
    WAITING_PAGE("waiting_page"),
    WAITING_USER("waiting_user"),
    PAUSED("paused"),
    NEEDS_RECONCILIATION("needs_reconciliation"),
    BUDGET_EXHAUSTED("budget_exhausted"),
    COMPLETED("completed"),
    FAILED("failed"),
    EXPIRED("expired"),
}

enum class BrowserTaskWaitReason(val wireValue: String) {
    PAGE_LOAD("page_load"),
    PAGE_CONDITION("page_condition"),
    USER_ACTION("user_action"),
    USER_AUTHENTICATION("user_authentication"),
    RECOVERY_CHECK("recovery_check"),
}

enum class BrowserEffectKind(val wireValue: String) {
    READ_ONLY("read_only"),
    POTENTIALLY_MUTATING("potentially_mutating"),
}

enum class BrowserEffectPhase(val wireValue: String) {
    INTENT("intent"),
    CONFIRMED("confirmed"),
    UNKNOWN("unknown"),
    RECONCILED("reconciled"),
}

enum class BrowserEffectVerificationResult {
    EFFECT_PRESENT,
    EFFECT_ABSENT,
}

/** A human's explicit UI attestation about an uncertain effect; never supplied by CLI/API callers. */
enum class BrowserUserEffectChoice(val wireValue: String) {
    OCCURRED("occurred"), NOT_OCCURRED("not_occurred"), STILL_UNKNOWN("still_unknown"),
}

data class BrowserUnknownEffectMetadata(
    val requestId: String,
    val method: String,
    val createdElapsedMillis: Long,
    val phase: BrowserEffectPhase,
    val hasUserVerification: Boolean,
    val lastUserChoice: BrowserUserEffectChoice?,
)

data class BrowserTaskRecoveryMetadata(
    val task: BrowserTaskRecord,
    val unknownEffects: List<BrowserUnknownEffectMetadata>,
    val boundIdentity: BrowserTaskPageIdentity?,
)

/** Shared fail-closed policy for the UI recovery path. */
object BrowserTaskRecoveryPolicy {
    fun canPrepareBinding(
        task: BrowserTaskRecord,
        currentIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
    ): Boolean = automationEnabled && ownerIsAi && currentIdentity != null &&
        task.status in setOf(BrowserTaskStatus.PAUSED, BrowserTaskStatus.NEEDS_RECONCILIATION, BrowserTaskStatus.WAITING_USER) &&
        task.remainingOperations > 0 && task.bootId == currentBootId &&
        nowElapsedMillis >= task.createdElapsedMillis && nowElapsedMillis >= task.updatedElapsedMillis &&
        nowElapsedMillis < task.deadlineElapsedMillis

    fun blocksMutatingOperation(effects: List<BrowserUnknownEffectMetadata>): Boolean = effects.any { effect ->
        effect.phase in setOf(BrowserEffectPhase.UNKNOWN, BrowserEffectPhase.INTENT) &&
            effect.lastUserChoice !in setOf(BrowserUserEffectChoice.OCCURRED, BrowserUserEffectChoice.NOT_OCCURRED)
    }
}

/** Immutable task limits. Values can only be selected at creation and are never replenished. */
data class BrowserTaskBudget(
    val durationMillis: Long = DEFAULT_DURATION_MILLIS,
    val operationLimit: Int = DEFAULT_OPERATION_LIMIT,
) {
    init {
        require(durationMillis in 1..MAX_DURATION_MILLIS)
        require(operationLimit in 1..MAX_OPERATION_LIMIT)
    }

    companion object {
        const val DEFAULT_DURATION_MILLIS = 30L * 60 * 1000
        const val DEFAULT_OPERATION_LIMIT = 500
        const val MAX_DURATION_MILLIS = 4L * 60 * 60 * 1000
        const val MAX_OPERATION_LIMIT = 2000
    }
}

/** Page identity contains no URL, title, DOM, or user-entered content. */
data class BrowserTaskPageIdentity(
    val sessionGeneration: Long,
    val controlGeneration: Long,
    val pageId: String,
    val documentGeneration: Long,
) {
    init {
        require(sessionGeneration > 0 && controlGeneration > 0 && documentGeneration >= 0)
        require(pageId.matches(Regex("[A-Za-z0-9_-]{1,96}")))
    }
}

data class BrowserTaskRecord(
    val taskId: String,
    val revision: Long,
    val status: BrowserTaskStatus,
    val createdElapsedMillis: Long,
    val deadlineElapsedMillis: Long,
    val bootId: String,
    val operationLimit: Int,
    val operationsUsed: Int,
    val pageIdentity: BrowserTaskPageIdentity?,
    val waitReason: BrowserTaskWaitReason?,
    val updatedElapsedMillis: Long,
    val title: String = "",
    val goal: String = "",
    val notes: String = "",
    val createdAtWallMillis: Long = 0,
    val updatedAtWallMillis: Long = createdAtWallMillis,
    val failureReason: String? = null,
) {
    init {
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(revision >= 0 && createdElapsedMillis >= 0 && deadlineElapsedMillis > createdElapsedMillis)
        require(bootId.matches(Regex("[A-Za-z0-9_-]{1,96}")))
        require(operationLimit in 1..BrowserTaskBudget.MAX_OPERATION_LIMIT)
        require(operationsUsed in 0..operationLimit)
        require(updatedElapsedMillis >= createdElapsedMillis)
        require(title.length <= 120 && goal.length <= 4000 && notes.length <= 2000)
        require(failureReason == null || (
            status == BrowserTaskStatus.FAILED &&
                failureReason.isNotBlank() &&
                failureReason.length <= MAX_FAILURE_REASON_LENGTH
        ))
        require(createdAtWallMillis >= 0 && updatedAtWallMillis >= createdAtWallMillis)
        require(
            (status in setOf(BrowserTaskStatus.WAITING_PAGE, BrowserTaskStatus.WAITING_USER, BrowserTaskStatus.NEEDS_RECONCILIATION)) ==
                (waitReason != null),
        )
    }

    val remainingOperations: Int get() = operationLimit - operationsUsed

    companion object {
        const val MAX_FAILURE_REASON_LENGTH = 1000
    }
}

/** Persist only a digest and a small outcome receipt; never persist request args or full response data. */
data class BrowserEffectJournalEntry(
    val requestId: String,
    val payloadHmacSha256: String,
    val method: String,
    val kind: BrowserEffectKind,
    val taskId: String?,
    val pageIdentity: BrowserTaskPageIdentity?,
    val deadlineElapsedMillis: Long,
    val bootId: String,
    val phase: BrowserEffectPhase,
    val createdElapsedMillis: Long,
    val updatedElapsedMillis: Long,
    val terminalCode: String? = null,
    val terminalExecuted: Boolean? = null,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(payloadHmacSha256.matches(Regex("[a-f0-9]{64}")))
        require(method.matches(Regex("[a-z_]{1,32}")))
        require(taskId == null || taskId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(deadlineElapsedMillis > createdElapsedMillis && createdElapsedMillis >= 0)
        require(bootId.matches(Regex("[A-Za-z0-9_-]{1,96}")))
        require(updatedElapsedMillis >= createdElapsedMillis)
        require(terminalCode == null || terminalCode.matches(Regex("[A-Z0-9_]{1,48}")))
        require((phase in setOf(BrowserEffectPhase.CONFIRMED, BrowserEffectPhase.RECONCILED)) ==
            (terminalCode != null && terminalExecuted != null))
        require(phase != BrowserEffectPhase.INTENT || (terminalCode == null && terminalExecuted == null))
    }
}

/** Evidence passed only by the runtime after it obtains a fresh, authorized page observation. */
data class BrowserEffectVerification(
    val requestId: String,
    val payloadHmacSha256: String,
    val observedIdentity: BrowserTaskPageIdentity,
    val observationId: String,
    val observedAtElapsedMillis: Long,
    val result: BrowserEffectVerificationResult,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(payloadHmacSha256.matches(Regex("[a-f0-9]{64}")))
        require(observationId.matches(Regex("[A-Za-z0-9_-]{1,96}")))
        require(observedAtElapsedMillis >= 0)
    }
}

sealed interface BrowserTaskTransition {
    data object Applied : BrowserTaskTransition
    data object Invalid : BrowserTaskTransition
    data object StaleRevision : BrowserTaskTransition
    data object PageOrAuthorizationMismatch : BrowserTaskTransition
    data object Expired : BrowserTaskTransition
    data object BudgetExhausted : BrowserTaskTransition
}

/** Shared state rules for CLI, Cordis, and the Android runtime. */
object BrowserTaskStateMachine {
    fun canTransition(from: BrowserTaskStatus, to: BrowserTaskStatus): Boolean = when (from) {
        BrowserTaskStatus.RUNNING -> to in setOf(
            BrowserTaskStatus.WAITING_PAGE, BrowserTaskStatus.WAITING_USER,
                BrowserTaskStatus.PAUSED, BrowserTaskStatus.NEEDS_RECONCILIATION,
            BrowserTaskStatus.COMPLETED, BrowserTaskStatus.FAILED,
        )
        BrowserTaskStatus.WAITING_PAGE -> to in setOf(
            BrowserTaskStatus.RUNNING, BrowserTaskStatus.WAITING_USER,
            BrowserTaskStatus.PAUSED, BrowserTaskStatus.NEEDS_RECONCILIATION,
            BrowserTaskStatus.FAILED,
        )
        BrowserTaskStatus.WAITING_USER -> to in setOf(
            BrowserTaskStatus.PAUSED, BrowserTaskStatus.NEEDS_RECONCILIATION,
            BrowserTaskStatus.FAILED,
        )
        BrowserTaskStatus.PAUSED -> to in setOf(BrowserTaskStatus.RUNNING, BrowserTaskStatus.NEEDS_RECONCILIATION, BrowserTaskStatus.FAILED)
        BrowserTaskStatus.NEEDS_RECONCILIATION -> to in setOf(BrowserTaskStatus.RUNNING, BrowserTaskStatus.FAILED)
        BrowserTaskStatus.BUDGET_EXHAUSTED -> to in setOf(BrowserTaskStatus.COMPLETED, BrowserTaskStatus.FAILED)
        BrowserTaskStatus.COMPLETED, BrowserTaskStatus.FAILED, BrowserTaskStatus.EXPIRED -> false
    }

    fun validateResume(
        record: BrowserTaskRecord,
        expectedRevision: Long,
        observedIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
    ): BrowserTaskTransition = when {
        record.revision != expectedRevision -> BrowserTaskTransition.StaleRevision
        nowElapsedMillis >= record.deadlineElapsedMillis || currentBootId != record.bootId -> BrowserTaskTransition.Expired
        record.remainingOperations <= 0 -> BrowserTaskTransition.BudgetExhausted
        !automationEnabled || !ownerIsAi || record.pageIdentity == null || record.pageIdentity != observedIdentity ->
            BrowserTaskTransition.PageOrAuthorizationMismatch
        record.status !in setOf(BrowserTaskStatus.PAUSED, BrowserTaskStatus.NEEDS_RECONCILIATION, BrowserTaskStatus.WAITING_PAGE) ->
            BrowserTaskTransition.Invalid
        else -> BrowserTaskTransition.Applied
    }

    /** Explicit recovery may refresh control/document generations only for the same page session. */
    fun validateReconcile(
        record: BrowserTaskRecord,
        expectedRevision: Long,
        observedIdentity: BrowserTaskPageIdentity?,
        automationEnabled: Boolean,
        ownerIsAi: Boolean,
        nowElapsedMillis: Long,
        currentBootId: String,
    ): BrowserTaskTransition = when {
        record.revision != expectedRevision -> BrowserTaskTransition.StaleRevision
        nowElapsedMillis >= record.deadlineElapsedMillis || currentBootId != record.bootId -> BrowserTaskTransition.Expired
        record.remainingOperations <= 0 -> BrowserTaskTransition.BudgetExhausted
        record.status !in setOf(BrowserTaskStatus.NEEDS_RECONCILIATION, BrowserTaskStatus.WAITING_USER, BrowserTaskStatus.PAUSED) ->
            BrowserTaskTransition.Invalid
        !automationEnabled || !ownerIsAi || record.pageIdentity == null || observedIdentity == null ||
            record.pageIdentity.sessionGeneration != observedIdentity.sessionGeneration ||
            record.pageIdentity.pageId != observedIdentity.pageId -> BrowserTaskTransition.PageOrAuthorizationMismatch
        else -> BrowserTaskTransition.Applied
    }
}
