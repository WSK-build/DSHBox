package com.dshbox.browser.runtime

import android.content.Context
import com.dshbox.browser.api.BrowserEffectJournalEntry
import com.dshbox.browser.api.BrowserEffectKind
import com.dshbox.browser.api.BrowserEffectPhase
import com.dshbox.browser.api.BrowserTaskBudget
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserTaskRecord
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserTaskWaitReason
import com.dshbox.browser.api.BrowserTaskRecoveryMetadata
import com.dshbox.browser.api.BrowserTaskStateMachine
import com.dshbox.browser.api.BrowserTaskTransition
import com.dshbox.browser.api.BrowserUserEffectChoice
import com.dshbox.browser.api.BrowserTaskRecoveryPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal data class BrowserTaskLiveContext(
    val active: Boolean,
    val automationEnabled: Boolean,
    val ownerIsAi: Boolean,
    val identity: BrowserTaskPageIdentity?,
    val state: String,
)

internal data class BudgetedBrowserTaskListPage(
    val response: JSONObject,
    val includedCount: Int,
    val requiredBytesForOversizedTask: Int? = null,
    val oversizedTaskId: String? = null,
)

/** Sizes the complete response envelope in UTF-8; records are never truncated. */
internal object BrowserTaskListResponseBudget {
    const val SOFT_BYTES = 192 * 1024
    const val HARD_BYTES = 256 * 1024

    fun encode(requestId: String, state: String, items: List<JSONObject>, total: Int, offset: Int): BudgetedBrowserTaskListPage {
        val accepted = mutableListOf<JSONObject>()
        for (item in items) {
            val candidate = accepted + item
            val response = response(requestId, state, candidate, total, offset)
            val bytes = response.toString().toByteArray(Charsets.UTF_8).size
            if (bytes > HARD_BYTES && accepted.isNotEmpty()) break
            if (bytes > HARD_BYTES) {
                val taskId = item.optString("taskId").takeIf { it.isNotBlank() }
                val error = commandResponse(requestId, "TASK_RECORD_TOO_LARGE", false, state,
                    data = JSONObject().put("taskId", taskId).put("offset", offset).put("nextOffset", offset)
                        .put("requiredBytes", bytes).put("maxResponseBytes", HARD_BYTES)
                        .put("hardFrameLimitBytes", HARD_BYTES))
                return BudgetedBrowserTaskListPage(error, accepted.size, bytes, taskId)
            }
            if (bytes > SOFT_BYTES && accepted.isNotEmpty()) break
            accepted.add(item)
        }
        return BudgetedBrowserTaskListPage(response(requestId, state, accepted, total, offset), accepted.size)
    }

    private fun response(requestId: String, state: String, tasks: List<JSONObject>, total: Int, offset: Int) =
        commandResponse(requestId, "OK", false, state, data = JSONObject()
            .put("tasks", JSONArray().apply { tasks.forEach { put(it) } })
            .put("totalCount", total)
            .put("nextOffset", if (offset + tasks.size < total) offset + tasks.size else JSONObject.NULL))
}

internal data class BrowserRecoveryChallenge(
    val challengeId: String,
    val taskId: String,
    val taskRevision: Long,
    val newIdentity: BrowserTaskPageIdentity,
    val expiresElapsedMillis: Long,
    val oldIdentity: BrowserTaskPageIdentity?,
    val oldStatus: BrowserTaskStatus,
    val unknownEffectCount: Int,
    val newPageOrigin: String?,
)

/** Serializes all journal I/O off the UI thread and fails closed until recovery has completed. */
internal class BrowserTaskCoordinator(private val runtime: BrowserTaskCoordinatorRuntime) {
    constructor(context: Context) : this(AndroidBrowserTaskCoordinatorRuntime(context))

    @Volatile private var resources: BrowserTaskCoordinatorResources? = null
    @Volatile private var bootId: String? = null
    @Volatile private var ready = false
    private val lifecycleLock = Any()
    private var closed = false
    private val initializationStarted = AtomicBoolean(false)
    private val recoveryChallenges = ConcurrentHashMap<String, RecoveryChallengeState>()

    private data class RecoveryChallengeState(
        val publicValue: BrowserRecoveryChallenge,
        val oldIdentity: BrowserTaskPageIdentity?,
        val bootId: String,
        val nonce: String,
    )

    fun initialize() {
        synchronized(lifecycleLock) {
            if (closed) return
            if (!initializationStarted.compareAndSet(false, true)) return
        }
        if (!runtime.tryPostIo {
            synchronized(lifecycleLock) {
                if (closed) {
                    initializationStarted.set(false)
                    return@tryPostIo
                }
            }
            var candidateResources: BrowserTaskCoordinatorResources? = null
            try {
                val opened = runtime.openResources()
                candidateResources = opened
                opened.store.recoverAfterProcessStart(opened.bootId, runtime.elapsedRealtime())
                val publish = synchronized(lifecycleLock) {
                    if (closed) {
                        false
                    } else {
                        resources = opened
                        bootId = opened.bootId
                        candidateResources = null
                        ready = true
                        true
                    }
                }
                if (!publish) {
                    initializationStarted.set(false)
                    candidateResources?.let { runCatching { runtime.closeResources(it) } }
                    candidateResources = null
                }
            } catch (_: Throwable) {
                synchronized(lifecycleLock) {
                    ready = false
                    initializationStarted.set(false)
                    candidateResources?.let { runCatching { runtime.closeResources(it) } }
                    resources?.let { runCatching { runtime.closeResources(it) } }
                    resources = null
                    bootId = null
                }
            }
        }) initializationStarted.set(false)
    }

    fun isReady(): Boolean = ready

    fun readRecoveryMetadata(offset: Int = 0, limit: Int = 100, callback: (List<BrowserTaskRecoveryMetadata>) -> Unit) {
        dispatchIo({ callback(emptyList()) }) {
            val rows = runCatching {
                if (!ready) emptyList() else requireNotNull(resources).store.listRecoveryMetadata(
                    requireNotNull(bootId), runtime.elapsedRealtime(), offset, limit,
                )
            }.getOrDefault(emptyList())
            dispatchMain { callback(rows) }
        }
    }

    fun updateManagedTask(taskId: String, revision: Long, title: String?, goal: String?, notes: String?, callback: (BrowserTaskUpdateResult?) -> Unit) {
        dispatchIo({ callback(null) }) {
            val result = runCatching { if (!ready) null else requireNotNull(resources).store.updateManagedTask(
                taskId, revision, title, goal, notes, runtime.currentTimeMillis()) }.getOrNull()
            dispatchMain { callback(result) }
        }
    }

    fun deleteManagedTask(taskId: String, revision: Long, callback: (BrowserTaskDeleteResult?) -> Unit) {
        dispatchIo({ callback(null) }) {
            val result = runCatching { if (!ready) null else requireNotNull(resources).store.deleteManagedTask(taskId, revision) }.getOrNull()
            dispatchMain { callback(result) }
        }
    }

    fun validateEventContext(
        taskId: String, identity: BrowserTaskPageIdentity, expectedEpoch: Long?, allowDocumentAdvance: Boolean,
        parentEffectId: String? = null, parentIdentity: BrowserTaskPageIdentity? = null,
        callback: (BrowserTaskEventGate?) -> Unit,
    ) {
        dispatchIo({ callback(null) }) {
            val result = runCatching {
                if (!ready) null else requireNotNull(resources).store.readEventGate(
                    taskId, identity, expectedEpoch, allowDocumentAdvance, parentEffectId,
                    parentIdentity, bootId ?: return@runCatching null, runtime.elapsedRealtime(),
                )
            }.getOrNull()
            dispatchMain { callback(result) }
        }
    }

    /** Requires an active task bound to the exact page document before file access or delivery. */
    fun validateUploadTask(taskId: String, identity: BrowserTaskPageIdentity, callback: (Boolean) -> Unit) {
        validateEventContext(taskId, identity, null, false, callback = { callback(it != null) })
    }

    /** Persist a one-use dialog continuation decision without storing page-provided prompt text. */
    fun recordEventDialogResolution(
        taskId: String, expectedRevision: Long, eventId: String, parentEffectId: String,
        identity: BrowserTaskPageIdentity, decision: String, promptText: String?,
        callback: (Boolean) -> Unit,
    ) {
        dispatchIo({ callback(false) }) {
            val stored = runCatching {
                if (!ready) false else {
                    val promptHmac = promptText?.let {
                        requireNotNull(resources).digest(it.toByteArray(Charsets.UTF_8))
                    }
                    requireNotNull(resources).store.recordEventDialogResolution(
                        taskId, expectedRevision, eventId, parentEffectId, identity, decision, promptHmac,
                        bootId ?: return@runCatching false, runtime.elapsedRealtime(),
                    )
                }
            }.getOrDefault(false)
            dispatchMain { callback(stored) }
        }
    }

    fun completeEventDialogResolution(eventId: String, resolved: Boolean, callback: (Boolean) -> Unit) {
        dispatchIo({ callback(false) }) {
            val stored = runCatching {
                ready && resources?.store?.completeEventDialogResolution(eventId, resolved) == true
            }
                .getOrDefault(false)
            dispatchMain { callback(stored) }
        }
    }

    /** Starts a short-lived, one-use UI challenge bound to this task revision and complete page identity. */
    fun prepareRecoveryBinding(
        taskId: String, context: BrowserTaskLiveContext?, newPageOrigin: String?,
        callback: (BrowserRecoveryChallenge?, String?) -> Unit,
    ) {
        dispatchIo({ callback(null, "JOURNAL_UNAVAILABLE") }) {
            val prepared = runCatching {
                if (!ready) return@runCatching null to "JOURNAL_UNAVAILABLE"
                val live = context?.takeIf { authorizedRecoveryContext(it) }
                    ?: return@runCatching null to "AI_CONTROL_REQUIRED"
                val store = requireNotNull(resources).store
                val boot = requireNotNull(bootId)
                val task = store.readTask(taskId, boot, runtime.elapsedRealtime())
                    ?: return@runCatching null to "TASK_NOT_FOUND"
                val now = runtime.elapsedRealtime()
                if (!BrowserTaskRecoveryPolicy.canPrepareBinding(
                        task, live.identity, live.automationEnabled, live.ownerIsAi, now, boot,
                    )
                )
                    return@runCatching null to "TASK_RECOVERY_INVALID"
                val target = live.identity ?: return@runCatching null to "TASK_PAGE_MISMATCH"
                val metadata = store.findRecoveryMetadata(taskId, boot, now)
                    ?: return@runCatching null to "TASK_NOT_FOUND"
                val challengeId = UUID.randomUUID().toString().replace("-", "")
                val expires = minOf(task.deadlineElapsedMillis, now + RECOVERY_CHALLENGE_TTL_MILLIS)
                recoveryChallenges.entries.removeIf { it.value.publicValue.expiresElapsedMillis <= now }
                if (recoveryChallenges.size >= MAX_PENDING_RECOVERY_CHALLENGES)
                    return@runCatching null to "TASK_RECOVERY_BUSY"
                val challenge = BrowserRecoveryChallenge(
                    challengeId, taskId, task.revision, target, expires, task.pageIdentity, task.status,
                    metadata.unknownEffects.size, newPageOrigin,
                )
                recoveryChallenges[challengeId] = RecoveryChallengeState(
                    challenge, task.pageIdentity, boot, UUID.randomUUID().toString().replace("-", ""),
                )
                challenge to null
            }.getOrElse { null to "JOURNAL_UNAVAILABLE" }
            dispatchMain { callback(prepared.first, prepared.second) }
        }
    }

    /** The consume tap is revalidated on Main, then committed on the journal executor, then checked again. */
    fun consumeRecoveryBinding(
        challengeId: String, liveContextProvider: () -> BrowserTaskLiveContext?,
        callback: (BrowserTaskRecord?, String?) -> Unit,
    ) {
        dispatchMain {
            val before = liveContextProvider()
            dispatchIo({ callback(null, "JOURNAL_UNAVAILABLE") }) {
                val challenge = recoveryChallenges.remove(challengeId)
                val state = challenge?.takeIf { it.publicValue.challengeId == challengeId }
                val reason = when {
                    !ready -> "JOURNAL_UNAVAILABLE"
                    state == null -> "TASK_RECOVERY_CHALLENGE_INVALID"
                    state.bootId != bootId || state.bootId != runtime.currentBootId() -> "TASK_RECOVERY_CHALLENGE_INVALID"
                    !authorizedRecoveryContext(before) || before?.identity != state.publicValue.newIdentity -> "TASK_PAGE_MISMATCH"
                    else -> null
                }
                val committed = if (reason != null) null else runCatching {
                    requireNotNull(resources).store.consumeRecoveryBinding(
                        taskId = requireNotNull(state).publicValue.taskId,
                        expectedRevision = state.publicValue.taskRevision,
                        expectedOldIdentity = state.oldIdentity,
                        newIdentity = state.publicValue.newIdentity,
                        nonce = state.nonce,
                        currentBootId = state.bootId,
                        nowElapsedMillis = runtime.elapsedRealtime(),
                        challengeExpiresElapsedMillis = state.publicValue.expiresElapsedMillis,
                    )
                }.getOrNull()
                dispatchMain {
                    val after = liveContextProvider()
                    if (committed != null && (!authorizedRecoveryContext(after) || after?.identity != committed.pageIdentity)) {
                        dispatchIo({ callback(null, "TASK_PAGE_MISMATCH") }) {
                            runCatching { requireNotNull(resources).store.invalidateRecoveryBinding(
                                committed.taskId, committed.revision, runtime.elapsedRealtime(),
                            ) }
                            dispatchMain { callback(null, "TASK_PAGE_MISMATCH") }
                        }
                    } else callback(committed, reason ?: if (committed == null) "TASK_RECOVERY_CHALLENGE_INVALID" else null)
                }
            }
        }
    }

    /** Records only an explicit UI attestation; it never updates or replays the original effect receipt. */
    fun recordUserEffectChoice(
        taskId: String, expectedRevision: Long, effectId: String, choice: BrowserUserEffectChoice,
        liveContextProvider: () -> BrowserTaskLiveContext?, callback: (Boolean) -> Unit,
    ) {
        dispatchMain {
            val context = liveContextProvider()
            dispatchIo({ callback(false) }) {
                val result = runCatching {
                    if (!ready || !authorizedRecoveryContext(context)) false else requireNotNull(resources).store.recordUserEffectVerification(
                        taskId, expectedRevision, effectId, requireNotNull(context).identity!!,
                        choice, requireNotNull(bootId), runtime.elapsedRealtime(),
                    )
                }.getOrDefault(false)
                dispatchMain { callback(result) }
            }
        }
    }

    private fun authorizedRecoveryContext(context: BrowserTaskLiveContext?): Boolean = context != null &&
        context.active && context.automationEnabled && context.ownerIsAi && context.identity != null

    private fun dispatchIo(rejected: () -> Unit, task: () -> Unit) {
        val accepted = synchronized(lifecycleLock) {
            !closed && runtime.tryPostIo(task)
        }
        if (!accepted) runtime.tryPostMain(rejected)
    }

    private fun dispatchMain(task: () -> Unit) {
        // A dead Main looper cannot provide a bounded completion guarantee.
        runtime.tryPostMain(task)
    }

    private fun normalizeUnknown(response: JSONObject): JSONObject {
        if (response.optString("code") != "UNKNOWN") return response
        return JSONObject(response.toString()).put("executed", JSONObject.NULL)
    }

    fun executeTask(
        request: BrowserCommandRequest,
        context: BrowserTaskLiveContext?,
        callback: (String) -> Unit,
        liveContextProvider: () -> BrowserTaskLiveContext? = { context },
    ) {
        if (request.args.optString("action") in setOf("list", "status")) {
            dispatchIo({
                callback(taskError(request, context, "JOURNAL_UNAVAILABLE").toString())
            }) {
                val result = runCatching { performTask(request, context) }
                    .getOrElse { taskError(request, context, "JOURNAL_UNAVAILABLE") }
                dispatchMain { callback(result.toString()) }
            }
            return
        }
        dispatchIo({
            callback(taskError(request, context, "JOURNAL_UNAVAILABLE").toString())
        }) {
            val reserved = runCatching { reserveTaskIntent(request, context) }
                .getOrElse { BrowserEffectAdmission.TaskRejected("JOURNAL_UNAVAILABLE") to "" }
            dispatchMain {
                val admission = reserved.first
                val digest = reserved.second
                when (admission) {
                    BrowserEffectAdmission.NewIntent -> {
                        val before = liveContextProvider()
                        if (runtime.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                            finishTaskIntent(request, before, digest, taskError(request, before, "DEADLINE_EXPIRED"), callback)
                            return@dispatchMain
                        }
                        if (taskActionNeedsControl(request) && !taskContextMatches(request, context, before)) {
                            val rejected = taskError(request, before, "STALE_SESSION")
                            finishTaskIntent(request, before, digest, rejected, callback)
                            return@dispatchMain
                        }
                        dispatchIo({
                            callback(unknownTaskResponse(taskError(request, before, "UNKNOWN")).toString())
                        }) {
                            val result = runCatching { performTask(request, before) }
                                .getOrElse { taskError(request, before, "UNKNOWN") }
                            dispatchMain {
                                val after = liveContextProvider()
                                val code = result.optString("code")
                                val canConfirm = !taskActionNeedsControl(request) || code != "OK" ||
                                    taskContextMatches(request, before, after)
                                val taskLinkId = linkedTaskId(request, result)
                                val terminal = normalizeUnknown(if (canConfirm) result else unknownTaskResponse(result))
                                finishTaskIntent(
                                    request, after, digest, terminal, callback,
                                    taskLinkId = taskLinkId,
                                )
                            }
                        }
                    }
                    is BrowserEffectAdmission.Duplicate -> {
                        val unknown = admission.phase == BrowserEffectPhase.UNKNOWN || admission.phase == BrowserEffectPhase.INTENT
                        callback((if (unknown) unknownTaskResponse(taskError(request, context, "UNKNOWN"))
                            else taskError(request, context, "DUPLICATE_RECEIPT"))
                        .put("data", JSONObject().put("receiptPhase", admission.phase.wireValue)
                            .put("terminalCode", admission.terminalCode ?: JSONObject.NULL)
                            .put("terminalExecuted", admission.terminalExecuted ?: JSONObject.NULL)).toString())
                    }
                    BrowserEffectAdmission.RequestIdConflict -> callback(taskError(request, context, "REQUEST_ID_CONFLICT").toString())
                    is BrowserEffectAdmission.Full -> callback(taskError(request, context, "BUSY").toString())
                    is BrowserEffectAdmission.TaskRejected -> callback(taskError(request, context, admission.code).toString())
                }
            }
        }
    }

    private fun reserveTaskIntent(
        request: BrowserCommandRequest,
        context: BrowserTaskLiveContext?,
    ): Pair<BrowserEffectAdmission, String> {
        if (!ready) return BrowserEffectAdmission.TaskRejected("JOURNAL_UNAVAILABLE") to ""
        val now = runtime.elapsedRealtime()
        val boot = bootId ?: return BrowserEffectAdmission.TaskRejected("JOURNAL_UNAVAILABLE") to ""
        val digest = requireNotNull(resources).digest(request.rawJson.toByteArray(Charsets.UTF_8))
        val entry = BrowserEffectJournalEntry(
            requestId = request.requestId, payloadHmacSha256 = digest, method = "task",
            kind = BrowserEffectKind.POTENTIALLY_MUTATING, taskId = null, pageIdentity = context?.identity,
            deadlineElapsedMillis = request.deadlineAtElapsedMillis, bootId = boot,
            phase = BrowserEffectPhase.INTENT, createdElapsedMillis = now, updatedElapsedMillis = now,
        )
        return requireNotNull(resources).store.reserveEffect(entry) to digest
    }

    private fun taskActionNeedsControl(request: BrowserCommandRequest): Boolean =
        request.args.optString("action") in setOf("begin", "complete", "reconcile", "resume")

    private fun taskContextMatches(
        request: BrowserCommandRequest,
        original: BrowserTaskLiveContext?,
        current: BrowserTaskLiveContext?,
    ): Boolean = current != null && original?.identity != null &&
        current.active && current.automationEnabled && current.ownerIsAi &&
        current.identity == original.identity &&
        current.identity.sessionGeneration == request.sessionGeneration &&
        current.identity.controlGeneration == request.controlGeneration &&
            runtime.elapsedRealtime() < request.deadlineAtElapsedMillis

    private fun unknownTaskResponse(response: JSONObject): JSONObject = response
        .put("code", "UNKNOWN").put("executed", JSONObject.NULL)
        .put("data", JSONObject().put("taskOutcomeUnconfirmed", true))

    private fun linkedTaskId(request: BrowserCommandRequest, response: JSONObject): String? =
        request.args.optString("taskId").takeIf { it.isNotBlank() }
            ?: response.optJSONObject("data")?.optJSONObject("task")?.optString("taskId")
                ?.takeIf { it.isNotBlank() }

    private fun finishTaskIntent(
        request: BrowserCommandRequest,
        context: BrowserTaskLiveContext?,
        digest: String,
        result: JSONObject,
        callback: (String) -> Unit,
        taskLinkId: String? = null,
    ) {
        dispatchIo({
            callback(unknownTaskResponse(taskError(request, context, "UNKNOWN")).toString())
        }) {
            val normalized = normalizeUnknown(result)
            val persisted = runCatching {
                ready && digest.matches(Regex("[a-f0-9]{64}")) &&
                    requireNotNull(resources).store.finishEffect(
                        requestId = request.requestId,
                        payloadHmacSha256 = digest,
                        terminalCode = normalized.optString("code").takeIf { normalized.opt("executed") is Boolean },
                        executed = normalized.opt("executed") as? Boolean,
                        nowElapsedMillis = runtime.elapsedRealtime(),
                        taskId = taskLinkId ?: linkedTaskId(request, result),
                    )
            }.getOrDefault(false)
            val delivered = if (persisted) normalized else unknownTaskResponse(taskError(request, context, "UNKNOWN"))
            dispatchMain { callback(delivered.toString()) }
        }
    }

    fun admitOperation(
        request: BrowserCommandRequest,
        context: BrowserTaskLiveContext,
        callback: (BrowserEffectAdmission, String) -> Unit,
    ) {
        dispatchIo({
            callback(BrowserEffectAdmission.TaskRejected("JOURNAL_UNAVAILABLE"), "")
        }) {
            val requestDigest = runCatching {
                check(ready)
                requireNotNull(resources).digest(request.rawJson.toByteArray(Charsets.UTF_8))
            }
            val admission = requestDigest.fold(
                onSuccess = { digest ->
                    val boot = bootId ?: return@fold BrowserEffectAdmission.TaskRejected("JOURNAL_UNAVAILABLE")
                    val identity = context.identity
                    val entry = BrowserEffectJournalEntry(
                        requestId = request.requestId,
                        payloadHmacSha256 = digest,
                        method = request.method.name.lowercase(),
                        kind = if (isPotentiallyMutating(request))
                            BrowserEffectKind.POTENTIALLY_MUTATING else BrowserEffectKind.READ_ONLY,
                        taskId = request.taskId,
                        pageIdentity = identity,
                        deadlineElapsedMillis = request.deadlineAtElapsedMillis,
                        bootId = boot,
                        phase = BrowserEffectPhase.INTENT,
                        createdElapsedMillis = runtime.elapsedRealtime(),
                        updatedElapsedMillis = runtime.elapsedRealtime(),
                    )
                    runCatching {
                        requireNotNull(resources).store.reserveEffect(entry)
                    }.getOrElse { BrowserEffectAdmission.TaskRejected("JOURNAL_UNAVAILABLE") }
                },
                onFailure = { BrowserEffectAdmission.TaskRejected("JOURNAL_UNAVAILABLE") },
            )
            val digest = requestDigest.getOrNull().orEmpty()
            dispatchMain { callback(admission, digest) }
        }
    }

    fun finishOperation(
        requestId: String,
        digest: String,
        responseJson: String,
        callback: ((String) -> Unit)? = null,
        deadlineElapsedMillis: Long? = null,
        screenshotReadOnly: Boolean = false,
    ) {
        dispatchIo({
            if (callback != null) {
                val unavailable = if (screenshotReadOnly) {
                    commandResponse(requestId, "JOURNAL_UNAVAILABLE", false, "CLOSED",
                        data = JSONObject().put("cancelStage", "journal").put("reason", "JOURNAL_UNAVAILABLE"))
                } else {
                    commandResponse(requestId, "UNKNOWN", null, "CLOSED",
                        data = JSONObject().put("journalOutcome", "UNCONFIRMED"))
                }
                callback(unavailable.toString())
            }
        }) {
            val terminalJson = if (deadlineElapsedMillis != null &&
                runtime.elapsedRealtime() >= deadlineElapsedMillis
            ) runCatching {
                JSONObject(responseJson).put("code", if (screenshotReadOnly) "DEADLINE_EXPIRED" else "UNKNOWN")
                    .put("executed", if (screenshotReadOnly) false else JSONObject.NULL)
                    .put("data", if (screenshotReadOnly) JSONObject().put("cancelStage", "pixel_read").put("reason", "DEADLINE_EXPIRED")
                        else JSONObject().put("journalOutcome", "deadline_after_dispatch"))
                    .toString()
            }.getOrElse { commandResponse(requestId, "UNKNOWN", null, "CLOSED").toString() }
            else responseJson
            val persisted = runCatching {
                if (ready && digest.matches(Regex("[a-f0-9]{64}"))) {
                    val response = runCatching { normalizeUnknown(JSONObject(terminalJson)) }.getOrNull()
                    val valid = response != null && response.optInt("version") == 1 &&
                        response.optString("requestId") == requestId &&
                        ((response.opt("executed") is Boolean && response.optString("code") != "UNKNOWN") ||
                            (response.opt("executed") == JSONObject.NULL && response.optString("code") == "UNKNOWN")) &&
                        response.optString("code").matches(Regex("[A-Z0-9_]{1,48}"))
                    valid && requireNotNull(resources).store.finishEffect(
                        requestId = requestId,
                        payloadHmacSha256 = digest,
                        terminalCode = if (valid) response!!.optString("code") else null,
                        executed = response!!.opt("executed") as? Boolean,
                        nowElapsedMillis = runtime.elapsedRealtime(),
                    )
                } else false
            }.getOrDefault(false)
            callback?.let {
                val terminal = runCatching { normalizeUnknown(JSONObject(terminalJson)) }.getOrNull()
                    ?: commandResponse(requestId, "UNKNOWN", null, "CLOSED")
                val deliver = if (persisted) terminal.toString() else runCatching {
                    val source = JSONObject(terminal.toString())
                    source.put("code", if (screenshotReadOnly) "JOURNAL_UNAVAILABLE" else "UNKNOWN")
                        .put("executed", if (screenshotReadOnly) false else JSONObject.NULL)
                    source.put("data", if (screenshotReadOnly) JSONObject().put("cancelStage", "journal").put("reason", "JOURNAL_UNAVAILABLE")
                        else JSONObject().put("journalOutcome", "UNCONFIRMED"))
                    source.toString()
                }.getOrElse { commandResponse(requestId, "UNKNOWN", null, "CLOSED").toString() }
                dispatchMain { it(deliver) }
            }
        }
    }

    private fun performTask(request: BrowserCommandRequest, context: BrowserTaskLiveContext?): JSONObject {
        if (!ready || resources == null || bootId == null) return taskError(request, context, "JOURNAL_UNAVAILABLE")
        if (runtime.elapsedRealtime() >= request.deadlineAtElapsedMillis) return taskError(request, context, "DEADLINE_EXPIRED")
        val store = requireNotNull(resources).store
        val boot = requireNotNull(bootId)
        val now = runtime.elapsedRealtime()
        val args = request.args
        val action = args.optString("action")
        val id = args.optString("taskId").takeIf { it.isNotBlank() }
        val live = context?.takeIf {
            it.active && it.automationEnabled && it.ownerIsAi && it.identity != null &&
                it.identity.sessionGeneration == request.sessionGeneration &&
                it.identity.controlGeneration == request.controlGeneration
        }
        when (action) {
            "begin" -> {
                if (live == null) return taskError(request, context, "AI_CONTROL_REQUIRED")
                val actionNow = runtime.elapsedRealtime()
                if (actionNow >= request.deadlineAtElapsedMillis) return taskError(request, context, "DEADLINE_EXPIRED")
                val taskId = "task_" + UUID.randomUUID().toString().replace("-", "")
                val budget = BrowserTaskBudget(
                    durationMillis = args.optLong("durationMs", BrowserTaskBudget.DEFAULT_DURATION_MILLIS),
                    operationLimit = args.optInt("operationLimit", BrowserTaskBudget.DEFAULT_OPERATION_LIMIT),
                )
                return when (val created = store.createTask(taskId, budget, boot, actionNow, live.identity,
                    args.optString("title"), args.optString("goal"), args.optString("notes"), runtime.currentTimeMillis())) {
                    is BrowserTaskCreateResult.Created -> taskResponse(request, context, "OK", created.record)
                    BrowserTaskCreateResult.Conflict -> taskError(request, context, "REQUEST_ID_CONFLICT")
                    BrowserTaskCreateResult.Full -> taskError(request, context, "TASK_CAPACITY_FULL")
                }
            }
            "list" -> {
                val offset = args.optInt("offset", 0)
                val limit = args.optInt("limit", 100)
                val rows = store.listTasks(boot, now, offset, limit)
                val total = store.taskCount()
                val items = rows.map { taskRecordJson(it, store.hasUnresolvedEffects(it.taskId), boot, now, live) }
                val page = BrowserTaskListResponseBudget.encode(request.requestId, context?.state ?: "CLOSED", items, total, offset)
                if (page.requiredBytesForOversizedTask != null) return page.response
                return page.response
            }
        }
        if (id == null) return taskError(request, context, "INVALID_ARGUMENT")
        if (action == "delete") {
            return when (store.deleteManagedTask(id, args.optLong("expectedRevision", -1))) {
                BrowserTaskDeleteResult.Deleted -> commandResponse(request.requestId, "OK", true,
                    context?.state ?: "CLOSED", data = JSONObject().put("taskId", id).put("deleted", true))
                BrowserTaskDeleteResult.NotFound -> taskError(request, context, "TASK_NOT_FOUND")
                BrowserTaskDeleteResult.StaleRevision -> taskError(request, context, "TASK_STALE")
                BrowserTaskDeleteResult.NotTerminal -> taskError(request, context, "TASK_DELETE_REQUIRES_TERMINAL")
                BrowserTaskDeleteResult.UnresolvedEffects -> taskError(request, context, "TASK_DELETE_HAS_UNRESOLVED_EFFECTS")
            }
        }
        val record = store.readTask(id, boot, now) ?: return taskError(request, context, "TASK_NOT_FOUND")
        if (action == "status") return taskResponse(request, context, "OK", record)
        if (context != null && action in setOf("pause", "wait", "fail")) {
            if (record.pageIdentity != context.identity) return taskError(request, context, "TASK_PAGE_MISMATCH")
            if (!taskContextMatches(request, context, context)) return taskError(request, context, "STALE_SESSION")
        }
        if (action == "update") {
            return when (val updated = store.updateManagedTask(id, args.optLong("expectedRevision", -1),
                args.optString("title").takeIf { args.has("title") },
                args.optString("goal").takeIf { args.has("goal") },
                args.optString("notes").takeIf { args.has("notes") }, runtime.currentTimeMillis())) {
                is BrowserTaskUpdateResult.Updated -> taskResponse(request, context, "OK", updated.record)
                BrowserTaskUpdateResult.NotFound -> taskError(request, context, "TASK_NOT_FOUND")
                BrowserTaskUpdateResult.StaleRevision -> taskError(request, context, "TASK_STALE")
                else -> taskError(request, context, "TASK_UPDATE_FAILED")
            }
        }
        val target = when (action) {
            "pause" -> BrowserTaskStatus.PAUSED
            "wait" -> if (args.optString("reason") in setOf(
                    BrowserTaskWaitReason.PAGE_LOAD.wireValue, BrowserTaskWaitReason.PAGE_CONDITION.wireValue,
                )) BrowserTaskStatus.WAITING_PAGE else BrowserTaskStatus.WAITING_USER
            "complete" -> BrowserTaskStatus.COMPLETED
            "fail" -> BrowserTaskStatus.FAILED
            "resume" -> BrowserTaskStatus.RUNNING
            "reconcile" -> null
            else -> return taskError(request, context, "INVALID_ARGUMENT")
        }
        val failureReason = if (action == "fail" && args.has("reason")) args.getString("reason") else null
        if (action in setOf("begin", "resume", "reconcile", "complete") && live == null) {
            return taskError(request, context, "AI_CONTROL_REQUIRED")
        }
        if (action in setOf("resume", "complete") && record.pageIdentity != live?.identity) {
            return taskError(request, context, "TASK_PAGE_MISMATCH")
        }
        val actionNow = runtime.elapsedRealtime()
        if (actionNow >= request.deadlineAtElapsedMillis) return taskError(request, context, "DEADLINE_EXPIRED")
        val update = if (action == "reconcile") {
            store.reconcileTask(
                id, record.revision, live?.identity, automationEnabled = live != null, ownerIsAi = live != null,
                nowElapsedMillis = actionNow, currentBootId = boot,
            )
        } else {
            val reason = if (action == "wait") BrowserTaskWaitReason.entries.firstOrNull { it.wireValue == args.optString("reason") } else null
            store.transitionTask(
                id, record.revision, requireNotNull(target), reason,
                live?.identity ?: record.pageIdentity,
                automationEnabled = live != null,
                ownerIsAi = live != null,
                nowElapsedMillis = actionNow,
                currentBootId = boot,
                failureReason = failureReason,
            )
        }
        return when (update) {
            is BrowserTaskUpdateResult.Updated -> taskResponse(request, context, "OK", update.record)
            BrowserTaskUpdateResult.NotFound -> taskError(request, context, "TASK_NOT_FOUND")
            BrowserTaskUpdateResult.InvalidTransition -> taskError(request, context, "TASK_TRANSITION_INVALID")
            BrowserTaskUpdateResult.StaleRevision -> taskError(request, context, "TASK_STALE")
            BrowserTaskUpdateResult.AuthorizationOrPageMismatch -> taskError(request, context, "TASK_PAGE_MISMATCH")
            BrowserTaskUpdateResult.Expired -> taskError(request, context, "TASK_EXPIRED")
            BrowserTaskUpdateResult.BudgetExhausted -> taskError(request, context, "TASK_BUDGET_EXHAUSTED")
            BrowserTaskUpdateResult.RecoveryNotAllowed -> taskError(request, context, "TASK_RECOVERY_INVALID")
            BrowserTaskUpdateResult.UnknownEffectUnverified -> taskError(request, context, "TASK_UNKNOWN_EFFECT_UNVERIFIED")
        }
    }

    private fun taskResponse(request: BrowserCommandRequest, context: BrowserTaskLiveContext?, code: String, record: BrowserTaskRecord) =
        commandResponse(
            request.requestId, code,
            request.args.optString("action") !in setOf("status", "list"),
            context?.state ?: "CLOSED", data = JSONObject().put("task", taskRecordJson(record,
                resources?.store?.hasUnresolvedEffects(record.taskId) ?: true, bootId ?: "", runtime.elapsedRealtime(), context)),
        )

    private fun taskError(request: BrowserCommandRequest, context: BrowserTaskLiveContext?, code: String): JSONObject {
        val advice = BrowserTaskErrorGuidance.forCode(code)
        val data = if (advice == null) JSONObject() else JSONObject()
            .put("stage", advice.stage).put("reason", advice.reason)
            .put("nextAction", advice.nextAction).put("automaticRetryAllowed", false)
        return commandResponse(request.requestId, code, false, context?.state ?: "CLOSED",
            sessionGeneration = context?.identity?.sessionGeneration,
            controlGeneration = context?.identity?.controlGeneration,
            owner = context?.let { if (!it.active) "none" else if (it.ownerIsAi) "ai" else null },
            data = data)
    }

    private fun taskRecordJson(record: BrowserTaskRecord, unresolved: Boolean = false,
        currentBoot: String = "", nowElapsed: Long = 0L, live: BrowserTaskLiveContext? = null) = JSONObject()
        .put("taskId", record.taskId)
        .put("revision", record.revision)
        .put("status", record.status.wireValue)
        .put("deadlineElapsedMillis", record.deadlineElapsedMillis)
        .put("operationLimit", record.operationLimit)
        .put("operationsUsed", record.operationsUsed)
        .put("remainingOperations", record.remainingOperations)
        .put("waitReason", record.waitReason?.wireValue ?: JSONObject.NULL)
        .put("failureReason", record.failureReason ?: JSONObject.NULL)
        .put("title", record.title).put("goal", record.goal).put("notes", record.notes)
        .put("createdAtWallMillis", record.createdAtWallMillis).put("updatedAtWallMillis", record.updatedAtWallMillis)
        .put("canResume", live?.active == true && !unresolved && BrowserTaskStateMachine.validateResume(
            record,
            record.revision,
            live?.identity,
            live?.automationEnabled == true,
            live?.ownerIsAi == true,
            nowElapsed,
            currentBoot,
        ) == BrowserTaskTransition.Applied)
        .put("canDelete", record.status in setOf(BrowserTaskStatus.BUDGET_EXHAUSTED, BrowserTaskStatus.COMPLETED, BrowserTaskStatus.FAILED, BrowserTaskStatus.EXPIRED) && !unresolved)

    fun close() {
        val firstClose = synchronized(lifecycleLock) {
            if (closed) {
                false
            } else {
                closed = true
                ready = false
                true
            }
        }
        if (!firstClose) return
        recoveryChallenges.clear()
        val queued = runtime.tryPostIo {
            resources?.let { runCatching { runtime.closeResources(it) } }
            resources = null
            bootId = null
            runtime.shutdown()
        }
        if (!queued) {
            resources?.let { runtime.closeResourcesAsync(it) }
            resources = null
            bootId = null
            runtime.shutdown()
        }
    }

    companion object {
        private const val RECOVERY_CHALLENGE_TTL_MILLIS = 30_000L
        private const val MAX_PENDING_RECOVERY_CHALLENGES = 20

        /** Outbox transitions record explicit shared context without operating a web page. */
        internal fun isFeedbackBookkeeping(request: BrowserCommandRequest): Boolean =
            request.method == BrowserCommandMethod.SELECTION && request.args.optString("action") in setOf(
                "claim", "admit", "receipt", "task_claim", "task_admit", "task_receipt",
            )

        fun shouldAdmitOperation(request: BrowserCommandRequest): Boolean = request.taskId != null || when (request.method) {
            BrowserCommandMethod.OPEN, BrowserCommandMethod.CLICK, BrowserCommandMethod.TYPE,
            BrowserCommandMethod.SCROLL, BrowserCommandMethod.SCREENSHOT, BrowserCommandMethod.CLOSE,
            BrowserCommandMethod.NAVIGATE -> true
            BrowserCommandMethod.DEBUG -> isDebugMutation(request)
            BrowserCommandMethod.NETWORK -> request.args.optString("action") !in setOf("list", "paused")
            BrowserCommandMethod.SELECTION -> request.args.optString("action") !in setOf("list", "latest", "pending", "task_pending")
            BrowserCommandMethod.PAGE -> request.args.optString("action") != "list"
            BrowserCommandMethod.FILE -> request.args.optString("action") !in setOf("list", "status")
            BrowserCommandMethod.UPLOAD -> true
            else -> false
        }

        private fun isDebugMutation(request: BrowserCommandRequest): Boolean =
            if (request.args.optString("action") == "rendering") request.args.optString("operation") == "set"
            else request.args.optString("action") !in setOf("status", "console", "dom", "ax_tree", "ax_observe")

        internal fun isPotentiallyMutating(request: BrowserCommandRequest): Boolean = when (request.method) {
            BrowserCommandMethod.OPEN, BrowserCommandMethod.CLICK, BrowserCommandMethod.TYPE,
            BrowserCommandMethod.SCROLL, BrowserCommandMethod.CLOSE,
            BrowserCommandMethod.NAVIGATE -> true
            BrowserCommandMethod.DEBUG -> isDebugMutation(request)
            BrowserCommandMethod.NETWORK -> request.args.optString("action") !in setOf("list", "paused")
            BrowserCommandMethod.SELECTION -> !isFeedbackBookkeeping(request) &&
                request.args.optString("action") !in setOf("list", "latest", "pending", "task_pending")
            BrowserCommandMethod.PAGE -> request.args.optString("action") != "list"
            BrowserCommandMethod.FILE -> request.args.optString("action") !in setOf("list", "status")
            BrowserCommandMethod.UPLOAD -> request.args.optString("action") in setOf("request", "activate", "submit", "cancel")
            BrowserCommandMethod.EVENT -> request.args.optString("action") == "arm"
            else -> false
        }
    }
}

internal object BrowserTaskCoordinatorRegistry {
    private val processCoordinator = ProcessScopedInstance<BrowserTaskCoordinator>()

    /** One coordinator per app process: bound-service recreation must not look like process recovery. */
    @Synchronized
    fun getOrCreate(context: Context): BrowserTaskCoordinator {
        return processCoordinator.getOrCreate { BrowserTaskCoordinator(context.applicationContext) }
            .also(BrowserTaskCoordinator::initialize)
    }

    fun get(): BrowserTaskCoordinator? = processCoordinator.getOrNull()
}

/** Keeps app-process state across short-lived bound Service instances. */
internal class ProcessScopedInstance<T : Any> {
    @Volatile private var instance: T? = null

    @Synchronized
    fun getOrCreate(factory: () -> T): T = instance ?: factory().also { instance = it }

    fun getOrNull(): T? = instance
}
