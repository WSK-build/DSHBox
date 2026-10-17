package com.dshbox.browser.runtime

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.dshbox.browser.api.BrowserHomeStatus
import com.dshbox.browser.api.BrowserArtifactFiles
import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserIdlePolicy
import com.dshbox.browser.api.BrowserOperationToken
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserSnapshot
import com.dshbox.browser.api.ScreenshotQuotaExceededException
import com.dshbox.browser.api.CommandContext
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserEffectPhase
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

internal fun recordSuccessfulObserveActivity(
    lifecycle: BrowserLifecycleController,
    initial: BrowserSnapshot,
    current: BrowserSnapshot,
    automationEnabled: Boolean,
    successfulDom: Boolean,
    sameDocument: Boolean,
    deadlineAtElapsedMillis: Long,
    nowElapsedMillis: Long,
): Boolean {
    if (nowElapsedMillis >= deadlineAtElapsedMillis ||
        !automationEnabled || !successfulDom || !sameDocument ||
        initial.lifecycle != BrowserLifecycle.ACTIVE || current.lifecycle != BrowserLifecycle.ACTIVE ||
        initial.owner != BrowserOwner.AI || current.owner != BrowserOwner.AI ||
        initial.sessionGeneration != current.sessionGeneration ||
        initial.controlGeneration != current.controlGeneration
    ) return false
    return lifecycle.recordReadActivity(initial.commandContext())
}

internal fun isAdvancedRequestDocumentBound(family: String, action: String): Boolean =
    (family == "debug" && action != "status") ||
    (family == "network" && action in setOf("start", "rules", "resolve", "replay", "replay_prepare", "replay_status", "clear_rules"))

/** Classifies bounded screenshot write failures without inspecting exception text. */
internal object BrowserScreenshotWriteFailurePolicy {
    const val QUOTA_CODE = "SCREENSHOT_QUOTA_EXCEEDED"
    const val STORAGE_STAGE = "storage"

    fun code(error: Throwable?, fallback: String): String =
        if (error is ScreenshotQuotaExceededException) QUOTA_CODE else fallback

    fun stage(code: String): String? = when (code) {
        QUOTA_CODE, "STORAGE_FAILED" -> STORAGE_STAGE
        else -> null
    }
}

/** Classifies advanced-request completion without inferring side effects from lost context. */
internal object BrowserAdvancedCompletionPolicy {
    sealed interface Outcome {
        object PreserveNative : Outcome
        data class Respond(val code: String, val executed: Boolean?, val data: JSONObject) : Outcome
    }

    private val readActions = setOf("ax_tree", "ax_observe")
    private val writeActions = setOf("ax_click", "ax_type", "ax_scroll")

    fun contextLost(action: String, nativeExecuted: Boolean?): Outcome = when {
        action in readActions -> definiteReadFailure("completion", "context_lost")
        action in writeActions && nativeExecuted == false -> Outcome.PreserveNative
        action in writeActions -> unknownWrite("context_lost")
        else -> unknownOther()
    }

    fun synchronousFailure(action: String): Outcome.Respond = when {
        action in readActions -> definiteReadFailure("dispatch", "synchronous_exception")
        action in writeActions -> unknownWrite("synchronous_exception")
        else -> unknownOther()
    }

    private fun definiteReadFailure(stage: String, reason: String) = Outcome.Respond(
        code = "EXECUTION_FAILED",
        executed = false,
        data = JSONObject().put("stage", stage).put("reason", reason),
    )

    private fun unknownWrite(reason: String) = Outcome.Respond(
        code = "UNKNOWN",
        executed = null,
        data = JSONObject()
            .put("nonRetryable", true)
            .put("sideEffectsMayHaveOccurred", true)
            .put("reason", reason),
    )

    private fun unknownOther() = Outcome.Respond("UNKNOWN", null, JSONObject())
}

internal fun isEligibleForAiToolAnimation(
    request: BrowserCommandRequest,
    snapshot: BrowserSnapshot,
    automationEnabled: Boolean,
    nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
): Boolean {
    if (!automationEnabled || snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.owner != BrowserOwner.AI ||
        request.owner != null && request.owner != "ai" ||
        request.sessionGeneration != null && request.sessionGeneration != snapshot.sessionGeneration ||
        request.controlGeneration != null && request.controlGeneration != snapshot.controlGeneration ||
        nowElapsedMillis >= request.deadlineAtElapsedMillis
    ) return false
    if (request.method != BrowserCommandMethod.STATUS && request.sessionGeneration == null) return false
    if (request.method == BrowserCommandMethod.WAIT) return false
    if (request.method == BrowserCommandMethod.SELECTION && request.args.optString("action") in setOf("pending", "receipt", "task_pending", "task_receipt")) {
        return false
    }
    return true
}

/** Validates live browser state and executes only fixed WebView actions. */
internal class BrowserCommandExecutor(
    private val windowHost: BrowserSessionWindowHost,
    private val lifecycle: () -> BrowserLifecycleController,
    private val webView: () -> WebView?,
    private val actionGate: BrowserActionGate,
    private val automationEnabled: () -> Boolean,
    private val capture: (Long, (Bitmap?, String) -> Unit) -> Unit,
    private val captureDetailed: BrowserScreenshotCaptureSource<Bitmap>? = null,
    private val publish: (BrowserSnapshot) -> Unit,
    private val loadUrl: (String) -> Boolean,
    private val closeSession: () -> Unit,
    private val executePage: (String, JSONObject) -> BrowserPageCommandResult,
    private val executeFile: (String, JSONObject, Long, () -> Boolean, (JSONObject) -> Unit) -> Unit,
    private val executeUpload: (BrowserCommandRequest, BrowserTaskPageIdentity, BrowserOperationToken, () -> Boolean, (JSONObject) -> Unit) -> Unit,
    private val invalidateUploadTask: (String) -> Unit,
    private val executeAdvanced: (String, JSONObject, Long, () -> Boolean, (JSONObject) -> Unit) -> Unit,
    private val selectionSequence: () -> Long,
    private val pageState: () -> BrowserPageStateTracker.State?,
    private val scheduleIdle: (BrowserSnapshot) -> Unit,
    private val onAiToolActivity: () -> Unit,
    private val activePageId: () -> String?,
    private val visualSurfaceAvailable: () -> Boolean,
    private val performNavigation: (String) -> Boolean,
    private val pauseMutatingDispatch: () -> Boolean = { false },
    private val commitSameDocument: (WebView, String, String) -> Unit = { _, _, _ -> },
) {
    constructor(
        activity: Activity,
        lifecycle: () -> BrowserLifecycleController,
        webView: () -> WebView?,
        actionGate: BrowserActionGate,
        automationEnabled: () -> Boolean,
        capture: (Long, (Bitmap?, String) -> Unit) -> Unit,
        captureDetailed: BrowserScreenshotCaptureSource<Bitmap>? = null,
        publish: (BrowserSnapshot) -> Unit,
        loadUrl: (String) -> Boolean,
        closeSession: () -> Unit,
        executePage: (String, JSONObject) -> BrowserPageCommandResult,
        executeFile: (String, JSONObject, Long, () -> Boolean, (JSONObject) -> Unit) -> Unit,
        executeUpload: (BrowserCommandRequest, BrowserTaskPageIdentity, BrowserOperationToken, () -> Boolean, (JSONObject) -> Unit) -> Unit,
        invalidateUploadTask: (String) -> Unit,
        executeAdvanced: (String, JSONObject, Long, () -> Boolean, (JSONObject) -> Unit) -> Unit,
        selectionSequence: () -> Long,
        pageState: () -> BrowserPageStateTracker.State?,
        scheduleIdle: (BrowserSnapshot) -> Unit,
        onAiToolActivity: () -> Unit,
        activePageId: () -> String?,
        visualSurfaceAvailable: () -> Boolean,
        performNavigation: (String) -> Boolean,
        pauseMutatingDispatch: () -> Boolean = { false },
        commitSameDocument: (WebView, String, String) -> Unit = { _, _, _ -> },
    ) : this(
        ActivitySessionWindowHost(activity), lifecycle, webView, actionGate, automationEnabled, capture,
        captureDetailed, publish, loadUrl, closeSession, executePage, executeFile, executeUpload,
        invalidateUploadTask, executeAdvanced, selectionSequence, pageState, scheduleIdle, onAiToolActivity,
        activePageId, visualSurfaceAvailable, performNavigation, pauseMutatingDispatch, commitSameDocument,
    )

    private val main = Handler(Looper.getMainLooper())
    private val eventCoordinator = BrowserEventCoordinator(HandlerBrowserEventScheduler(main))
    @Volatile private var documentGeneration = 0L
    private var observationGeneration = 0L
    private var referenceNonce: String? = null
    private var latestObservation: Long? = null
    private var referencePageId: String? = null
    private var referenceView: WebView? = null
    private val actionGateBridge = BrowserActionGateJavascriptBridge(actionGate, windowHost, visualSurfaceAvailable)
    private val authorizationMirror = AtomicReference<AuthorizationMirror?>(null)
    private val visualTokens = BrowserVisualActionTokenRegistry(SystemClock::elapsedRealtime)
    private val visualViewGenerations = WeakHashMap<WebView, Long>()
    private var visualViewGenerationSequence = 0L
    private val preAdmittedTokens = HashMap<String, BrowserOperationToken>()
    private val pendingScreenshotArtifacts = HashMap<String, BrowserArtifactFiles.WrittenArtifact>()
    private val screenshotCaptures = HashMap<String, ScreenshotCaptureRegistration>()
    private val thumbnailCancels = ConcurrentHashMap<String, () -> Unit>()
    private val eventWaitSubscriptions = HashMap<String, String>()
    private val waitCoordinator = BrowserWaitCoordinator(
        clock = SystemClock::elapsedRealtime,
        scheduler = object : BrowserWaitCoordinator.Scheduler {
            private val handles = HashMap<BrowserWaitCoordinator.Handle, Runnable>()
            override fun postDelayed(delayMillis: Long, task: () -> Unit): BrowserWaitCoordinator.Handle {
                val handle = object : BrowserWaitCoordinator.Handle {}
                val runnable = Runnable {
                    handles.remove(handle)
                    task()
                }
                handles[handle] = runnable
                main.postDelayed(runnable, delayMillis)
                return handle
            }
            override fun remove(handle: BrowserWaitCoordinator.Handle) {
                handles.remove(handle)?.let(main::removeCallbacks)
            }
        },
    )

    fun updateAuthorizationState(snapshot: BrowserSnapshot, enabled: Boolean) {
        val previous = authorizationMirror.getAndSet(AuthorizationMirror(snapshot, enabled))
        if (previous != null && (!enabled || previous.snapshot.sessionGeneration != snapshot.sessionGeneration ||
                previous.snapshot.controlGeneration != snapshot.controlGeneration || previous.snapshot.owner != snapshot.owner)) {
            waitCoordinator.cancelAll()
            eventCoordinator.invalidateAll()
            cancelThumbnailWaits()
        }
    }

    private fun publishSnapshot(snapshot: BrowserSnapshot) {
        authorizationMirror.set(AuthorizationMirror(snapshot, automationEnabled()))
        publish(snapshot)
    }

    fun attachActionGate(target: WebView? = webView()) {
        target?.addJavascriptInterface(actionGateBridge, BrowserDomScript.ACTION_GATE_NAME)
    }

    fun detachActionGate() {
        actionGate.invalidate()
        eventCoordinator.invalidateAll()
        referenceNonce = null
        latestObservation = null
        referencePageId = null
        referenceView = null
        webView()?.removeJavascriptInterface(BrowserDomScript.ACTION_GATE_NAME)
    }

    fun onDocumentStarted() {
        waitCoordinator.revalidate()
        documentGeneration++
        referenceNonce = null
        latestObservation = null
        referencePageId = null
        referenceView = null
        actionGate.invalidate()
    }

    internal fun onRenderingSettingsChanged() {
        referenceNonce = null
        latestObservation = null
        referencePageId = null
        referenceView = null
        visualTokens.invalidateAll()
        actionGate.invalidate()
    }

    fun execute(rawJson: String, callback: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { execute(rawJson, callback) }
            return
        }
        val parsed = BrowserCommandRequest.parse(rawJson)
        if (parsed.isFailure) {
            val requestId = runCatching { JSONObject(rawJson).optString("requestId") }.getOrDefault("")
            val error = parsed.exceptionOrNull()?.message
            callback(commandResponse(requestId, error?.takeIf { it in ERROR_CODES } ?: "INVALID_ARGUMENT", false).toString())
            return
        }
        val request = parsed.getOrThrow()
        val activitySnapshot = expireIfNeeded()
        if (pauseMutatingDispatch() && (BrowserTaskCoordinator.isPotentiallyMutating(request) ||
                request.method == BrowserCommandMethod.SCREENSHOT)) {
            callback(response(request, "CONTROL_TRANSFER_PENDING", false, activitySnapshot).toString())
            return
        }
        if (isEligibleForAiToolAnimation(request, activitySnapshot, automationEnabled())) {
            onAiToolActivity()
        }
        if (request.method == BrowserCommandMethod.EVENT && request.args.optString("action") != "arm") {
            executeEventRequest(request, callback)
            return
        }
        if (request.method == BrowserCommandMethod.TASK) {
            val coordinator = BrowserTaskCoordinatorRegistry.get()
            if (coordinator == null) {
                callback(response(request, "JOURNAL_UNAVAILABLE", false, lifecycle().snapshot()).toString())
                return
            }
            executeTaskRequest(request, coordinator, callback)
            return
        }
        val coordinator = BrowserTaskCoordinatorRegistry.get()
        if (coordinator == null && BrowserTaskCoordinator.shouldAdmitOperation(request)) {
            callback(response(request, "JOURNAL_UNAVAILABLE", false, lifecycle().snapshot()).toString())
            return
        }
        if (coordinator != null && BrowserTaskCoordinator.shouldAdmitOperation(request)) {
            admitThenDispatch(request, coordinator, callback)
            return
        }
        dispatchParsedRequest(request, callback)
    }

    private fun executeTaskRequest(
        request: BrowserCommandRequest,
        coordinator: BrowserTaskCoordinator,
        callback: (String) -> Unit,
    ) {
        val action = request.args.optString("action")
        if (action in setOf("pause", "fail", "complete", "resume", "reconcile", "delete")) {
            request.args.optString("taskId").takeIf { it.isNotBlank() }?.let(eventCoordinator::invalidateTask)
        }
        val needsControl = action in setOf("begin", "complete", "reconcile", "resume")
        val token = if (needsControl) beginAiOperation(request) else null
        if (needsControl && token == null) {
            callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString())
            return
        }
        val context = currentTaskContext()
        coordinator.executeTask(request, context, { raw ->
            val taskResult = runCatching { JSONObject(raw) }.getOrNull()
            if (taskResult?.optString("code") == "OK" && taskResult.opt("executed") == true &&
                action in setOf("pause", "fail", "complete", "resume", "reconcile", "delete")
            ) {
                request.args.optString("taskId").takeIf { it.isNotBlank() }?.let {
                    eventCoordinator.invalidateTask(it)
                    invalidateUploadTask(it)
                }
            }
            if (token == null) {
                callback(raw)
                return@executeTask
            }
            val current = currentTaskContext()
            val result = runCatching { JSONObject(raw) }.getOrNull()
            val accepted = result?.optString("code") == "OK" && result.opt("executed") == true &&
                isTokenCurrent(token) && SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis &&
                context.identity == current.identity && current.active && current.automationEnabled && current.ownerIsAi
            val completed = lifecycle().completeOperation(token, accepted)
            val latest = lifecycle().snapshot()
            publishSnapshot(latest)
            if (accepted && completed && stillAuthorizedAfterCompletion(token, latest)) callback(raw)
            else callback(response(request, "UNKNOWN", null, latest, JSONObject().put("taskOutcomeUnconfirmed", true)).toString())
        }) { currentTaskContext() }
    }

    private fun dispatchParsedRequest(request: BrowserCommandRequest, callback: (String) -> Unit) {
        when (request.method) {
            BrowserCommandMethod.STATUS -> status(request, callback)
            BrowserCommandMethod.OPEN -> open(request, callback)
            BrowserCommandMethod.OBSERVE -> observe(request, callback)
            BrowserCommandMethod.LOCATE -> locate(request, callback)
            BrowserCommandMethod.WAIT -> wait(request, callback)
            BrowserCommandMethod.NAVIGATE -> navigate(request, callback)
            BrowserCommandMethod.TASK -> error("Task requests are handled before browser dispatch")
            BrowserCommandMethod.CONTROL -> error("Control requests are handled by the approval service")
            BrowserCommandMethod.COLLECT -> collect(request, callback)
            BrowserCommandMethod.CLICK,
            BrowserCommandMethod.TYPE,
            BrowserCommandMethod.SCROLL,
            BrowserCommandMethod.SCREENSHOT,
            BrowserCommandMethod.CLOSE -> executeAiAction(request, callback)
            BrowserCommandMethod.DEBUG,
            BrowserCommandMethod.NETWORK,
            BrowserCommandMethod.SELECTION -> executeAdvancedRequest(request, callback)
            BrowserCommandMethod.PAGE -> executePageRequest(request, callback)
            BrowserCommandMethod.FILE -> executeFileRequest(request, callback)
            BrowserCommandMethod.UPLOAD -> executeUploadRequest(request, callback)
            BrowserCommandMethod.EVENT -> executeEventRequest(request, callback)
        }
    }

    private fun admitThenDispatch(
        request: BrowserCommandRequest,
        coordinator: BrowserTaskCoordinator,
        callback: (String) -> Unit,
    ) {
        val initialContext = currentTaskContext()
        val token = if (requiresPreAdmittedToken(request)) beginAiOperation(request) else null
        if (requiresPreAdmittedToken(request) && token == null) {
            callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString())
            return
        }
        coordinator.admitOperation(request, initialContext) { admission, digest ->
            when (admission) {
                is BrowserEffectAdmission.Duplicate -> {
                    token?.let { lifecycle().completeOperation(it, false) }
                    preAdmittedTokens.remove(request.requestId)
                    val code = if (admission.phase == BrowserEffectPhase.UNKNOWN || admission.phase == BrowserEffectPhase.INTENT) "UNKNOWN" else "DUPLICATE_RECEIPT"
                    callback(response(request, code, if (code == "UNKNOWN") null else false, lifecycle().snapshot(), JSONObject()
                        .put("receiptPhase", admission.phase.wireValue)
                        .put("terminalCode", admission.terminalCode ?: JSONObject.NULL)
                        .put("terminalExecuted", admission.terminalExecuted ?: JSONObject.NULL)).toString())
                }
                BrowserEffectAdmission.RequestIdConflict -> {
                    token?.let { lifecycle().completeOperation(it, false) }
                    callback(response(request, "REQUEST_ID_CONFLICT", false, lifecycle().snapshot()).toString())
                }
                is BrowserEffectAdmission.Full -> {
                    token?.let { lifecycle().completeOperation(it, false) }
                    callback(response(request, "BUSY", false, lifecycle().snapshot(), JSONObject()
                        .put("capacity", admission.capacity.name.lowercase(Locale.ROOT))).toString())
                }
                is BrowserEffectAdmission.TaskRejected -> {
                    token?.let { lifecycle().completeOperation(it, false) }
                    callback(response(request, admission.code, false, lifecycle().snapshot()).toString())
                }
                BrowserEffectAdmission.NewIntent -> {
                    val current = currentTaskContext()
                    val sameIdentity = initialContext.identity == current.identity && initialContext.active == current.active
                    val needsAi = request.taskId != null || token != null ||
                        request.method == BrowserCommandMethod.PAGE ||
                        request.method == BrowserCommandMethod.SELECTION && !BrowserTaskCoordinator.isFeedbackBookkeeping(request)
                    val authorized = !needsAi || (current.active && current.automationEnabled && current.ownerIsAi)
                    if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis || !sameIdentity || !authorized) {
                        token?.let { lifecycle().completeOperation(it, false) }
                        val denied = response(request,
                            if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) "DEADLINE_EXPIRED" else "STALE_SESSION",
                            false, lifecycle().snapshot()).toString()
                        coordinator.finishOperation(
                            request.requestId, digest, denied, callback, request.deadlineAtElapsedMillis,
                        )
                        return@admitOperation
                    }
                    if (request.args.has("eventSubscriptionId")) {
                        val eventIdentity = currentEventIdentity()
                        val supportedKinds = when (request.method) {
                            BrowserCommandMethod.CLICK -> BrowserEventCoordinator.EVENT_KINDS
                            BrowserCommandMethod.NAVIGATE -> setOf("navigation_started", "navigation_finished", "navigation_failed")
                            else -> emptySet()
                        }
                        val bound = if (eventIdentity == null) null else eventCoordinator.bindAction(
                            request.args.optString("eventSubscriptionId"), request.args.optString("eventNonce"),
                            request.taskId.orEmpty(), eventIdentity, request.requestId, supportedKinds,
                        )
                        if (bound == null) {
                            token?.let { lifecycle().completeOperation(it, false) }
                            val denied = response(request, "EVENT_SUBSCRIPTION_INVALID", false, lifecycle().snapshot()).toString()
                            coordinator.finishOperation(request.requestId, digest, denied, callback, request.deadlineAtElapsedMillis)
                            return@admitOperation
                        }
                    }
                    if (token != null) preAdmittedTokens[request.requestId] = token
                    val delivered = AtomicBoolean(false)
                    dispatchParsedRequest(request) { raw ->
                        if (!delivered.compareAndSet(false, true)) return@dispatchParsedRequest
                        preAdmittedTokens.remove(request.requestId)
                        var dispatchResponse = raw
                        if (request.method == BrowserCommandMethod.EVENT && request.args.optString("action") == "arm" && token != null) {
                            val armResult = runCatching { JSONObject(raw) }.getOrNull()
                            val currentContext = currentTaskContext()
                            val effective = armResult?.optString("code") == "OK" && armResult.opt("executed") == true &&
                                isTokenCurrent(token) && SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis &&
                                currentContext.identity == initialContext.identity && currentContext.active &&
                                currentContext.automationEnabled && currentContext.ownerIsAi
                            val completed = lifecycle().completeOperation(token, effective)
                            val latest = lifecycle().snapshot()
                            publishSnapshot(latest)
                            if (effective && completed && stillAuthorizedAfterCompletion(token, latest)) {
                                dispatchResponse = raw
                            } else if (armResult?.optString("code") == "OK" && armResult.opt("executed") == true) {
                                armResult.optJSONObject("data")?.optString("subscriptionId")
                                    ?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                                    ?.let { eventCoordinator.cancel(it, request.taskId.orEmpty()) }
                                dispatchResponse = response(request, "UNKNOWN", null, latest,
                                    JSONObject().put("eventArmOutcomeUnconfirmed", true)).toString()
                            }
                        }
                        val rawResult = runCatching { JSONObject(dispatchResponse) }.getOrNull()
                        val actionSucceeded = rawResult?.optString("code") == "OK" && rawResult.opt("executed") == true &&
                            SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis
                        if (!actionSucceeded) eventCoordinator.invalidateAction(request.requestId)
                        val terminal = if (eventCoordinator.consumeUncertainAction(request.requestId)) {
                            response(request, "UNKNOWN", null, lifecycle().snapshot(),
                                JSONObject().put("eventOutcomeUnconfirmed", true)).toString()
                        } else dispatchResponse
                        coordinator.finishOperation(
                            request.requestId, digest, terminal, { delivered ->
                                val finalResponse = if (request.method == BrowserCommandMethod.SCREENSHOT &&
                                    SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                                    response(request, "DEADLINE_EXPIRED", false, lifecycle().snapshot(),
                                        screenshotFailureData("DEADLINE_EXPIRED")).toString()
                                } else delivered
                                pendingScreenshotArtifacts.remove(request.requestId)?.let { artifact ->
                                    if (runCatching { JSONObject(finalResponse).optString("code") != "OK" }.getOrDefault(true)) {
                                        discardScreenshot(artifact)
                                    }
                                }
                                callback(finalResponse)
                            }, request.deadlineAtElapsedMillis,
                            screenshotReadOnly = request.method == BrowserCommandMethod.SCREENSHOT,
                        )
                    }
                }
            }
        }
    }

    private fun currentTaskContext(): BrowserTaskLiveContext {
        val snapshot = lifecycle().snapshot()
        val pageId = activePageId()
        val identity = if (snapshot.sessionGeneration > 0 && snapshot.controlGeneration > 0 &&
            pageId != null && pageId.matches(Regex("[A-Za-z0-9_-]{1,96}"))
        ) runCatching {
            BrowserTaskPageIdentity(snapshot.sessionGeneration, snapshot.controlGeneration, pageId, documentGeneration)
        }.getOrNull() else null
        return BrowserTaskLiveContext(
            active = snapshot.lifecycle == BrowserLifecycle.ACTIVE && webView() != null,
            automationEnabled = automationEnabled(),
            ownerIsAi = snapshot.owner == BrowserOwner.AI,
            identity = identity,
            state = snapshot.stateName(),
        )
    }

    /** UI-only read of the live browser authorization and page identity; never exposed through the command bridge. */
    internal fun recoveryContextForUi(): BrowserTaskLiveContext = currentTaskContext()

    internal fun publishBrowserEvent(
        kind: String, viewPageId: String, payload: JSONObject = JSONObject(), terminal: Boolean = false,
    ): JSONObject? {
        val identity = currentEventIdentity(viewPageId) ?: return null
        val details = JSONObject(payload.toString())
        if (kind in setOf("navigation_started", "navigation_finished", "navigation_failed") && viewPageId == activePageId()) {
            BrowserNavigationMetadata.addTo(details, pageState())
        }
        return eventCoordinator.publishForPage(kind, viewPageId, identity, details, terminal)
    }

    internal fun publishBrowserDialog(
        viewPageId: String, payload: JSONObject, resolver: (String, String?) -> Boolean,
    ): JSONObject? {
        val identity = currentEventIdentity(viewPageId) ?: return null
        return eventCoordinator.publishDialogForPage(viewPageId, identity, payload, resolver)
    }

    internal fun invalidateBrowserEvents() = eventCoordinator.invalidateAll()
    internal fun invalidateTaskBrowserEvents(taskId: String) = eventCoordinator.invalidateTask(taskId)
    internal fun invalidatePageBrowserEvents(pageId: String) = eventCoordinator.invalidatePage(pageId)
    internal fun invalidateUnassociatedNavigation(pageId: String) = eventCoordinator.invalidatePage(pageId)
    internal fun dismissBrowserDialogsForPage(pageId: String): Int = eventCoordinator.dismissDialogsForPage(pageId)
    internal fun dismissAllBrowserDialogs(): Int = eventCoordinator.dismissAllDialogs()
    internal fun onMainFrameStarted(pageId: String) {
        val identity = currentEventIdentity(pageId)
        val details = BrowserNavigationMetadata.addTo(JSONObject(), pageState())
        if (identity == null || !eventCoordinator.onMainFrameStarted(pageId, identity, details)) {
            eventCoordinator.invalidatePage(pageId)
        }
    }
    internal fun hasBrowserEventExpectation(pageId: String, kind: String): Boolean =
        eventCoordinator.hasBoundExpectation(pageId, kind)

    internal fun claimBrowserEventExpectation(pageId: String, kind: String): BrowserEventCoordinator.OneShotClaim? {
        val identity = currentEventIdentity(pageId) ?: return null
        return eventCoordinator.claimOneShotExpectation(pageId, kind, identity)
    }

    internal fun registerDialogResolver(eventId: String, resolver: (String, String?) -> Boolean): Boolean =
        eventCoordinator.registerDialogResolver(eventId, resolver)

    private fun currentEventIdentity(pageIdOverride: String? = null): BrowserEventCoordinator.Identity? {
        val snapshot = lifecycle().snapshot()
        val pageId = pageIdOverride ?: activePageId() ?: return null
        val navigationId = pageState()?.navigationId ?: return null
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.sessionGeneration <= 0 ||
            snapshot.controlGeneration <= 0 || documentGeneration < 0
        ) return null
        return BrowserEventCoordinator.Identity(
            snapshot.sessionGeneration, snapshot.controlGeneration, pageId, documentGeneration, navigationId,
        )
    }

    private fun executeEventRequest(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = lifecycle().snapshot()
        val taskId = request.taskId
        val context = currentTaskContext()
        if (taskId == null || snapshot.lifecycle != BrowserLifecycle.ACTIVE || !automationEnabled() ||
            snapshot.owner != BrowserOwner.AI || snapshot.sessionGeneration != request.sessionGeneration ||
            snapshot.controlGeneration != request.controlGeneration || !context.active || !context.ownerIsAi
        ) {
            callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
            return
        }
        val action = request.args.optString("action")
        val taskCoordinator = BrowserTaskCoordinatorRegistry.get()
        if (taskCoordinator == null) {
            callback(response(request, "JOURNAL_UNAVAILABLE", false, snapshot).toString())
            return
        }
        when (action) {
            "arm" -> {
                val identity = currentEventIdentity()
                if (identity == null || context.identity?.pageId != identity.pageId || context.identity.documentGeneration != identity.documentGeneration) {
                    callback(response(request, "STALE_SESSION", false, snapshot).toString()); return
                }
                val kinds = request.args.optJSONArray("eventKinds")!!
                val kindSet = (0 until kinds.length()).map { kinds.getString(it) }.toSet()
                val ttl = request.args.optLong("ttlMs", 15_000L).coerceAtMost(30_000L)
                val browserTaskIdentity = identity.toTaskIdentity() ?: run {
                    callback(response(request, "STALE_SESSION", false, snapshot).toString()); return
                }
                taskCoordinator.validateEventContext(
                    taskId, browserTaskIdentity, null, false,
                ) { gate ->
                if (gate == null || SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis || !isAuthorizedNow(request)) {
                    callback(response(request, "TASK_PAGE_MISMATCH", false, lifecycle().snapshot()).toString()); return@validateEventContext
                }
                val remainingTaskMillis = (gate.task.deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val ttlBounded = minOf(ttl, request.deadlineAtElapsedMillis - SystemClock.elapsedRealtime(), remainingTaskMillis)
                if (ttlBounded <= 0L) {
                    callback(response(request, "DEADLINE_EXPIRED", false, lifecycle().snapshot()).toString()); return@validateEventContext
                }
                when (val result = eventCoordinator.arm(taskId, identity, gate.bindingEpoch, kindSet, ttlBounded)) {
                        BrowserEventCoordinator.ArmResult.Full -> callback(response(request, "BUSY", false, lifecycle().snapshot()).toString())
                        is BrowserEventCoordinator.ArmResult.Created -> {
                            val sub = result.subscription
                            val identityJson = JSONObject().put("sessionGeneration", identity.sessionGeneration)
                                .put("controlGeneration", identity.controlGeneration).put("pageId", identity.pageId)
                                .put("documentGeneration", identity.documentGeneration).put("navigationId", identity.navigationId)
                            callback(response(request, "OK", true, lifecycle().snapshot(), JSONObject()
                                .put("subscriptionId", sub.id).put("nonce", sub.nonce)
                                .put("expiresAtElapsedMillis", sub.expiresAt).put("identity", identityJson)).toString())
                        }
                    }
                }
            }
            "wait" -> {
                val id = request.args.optString("subscriptionId")
                val timeout = minOf(request.args.optLong("timeoutMs", 15_000L),
                    (request.deadlineAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                val identity = currentEventIdentity()
                if (identity == null) { callback(response(request, "STALE_SESSION", false, snapshot).toString()); return }
                val sub = eventCoordinator.subscription(id)
                val taskIdentity = identity.toTaskIdentity()
                if (sub == null || taskIdentity == null || sub.taskId != taskId || SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                    callback(response(request, "EVENT_SUBSCRIPTION_INVALID", false, snapshot).toString()); return
                }
                taskCoordinator.validateEventContext(
                    taskId, taskIdentity, sub.bindingEpoch, sub.actionBound,
                    sub.actionRequestId, sub.initialIdentity.toTaskIdentity(),
                ) { gate ->
                    if (gate == null || !isAuthorizedNow(request) || SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                        callback(response(request, "EVENT_SUBSCRIPTION_INVALID", false, lifecycle().snapshot()).toString()); return@validateEventContext
                    }
                    eventWaitSubscriptions[request.requestId] = id
                    val accepted = eventCoordinator.await(id, taskId, identity, timeout) { result ->
                        eventWaitSubscriptions.remove(request.requestId)
                        val code = result.optString("code", "OK")
                        if (code == "EVENT_TIMEOUT" || code == "EVENT_CANCELLED") {
                            val data = JSONObject().put("subscriptionId", id).put("matched", false)
                            if (code == "EVENT_CANCELLED") data.put("stage", "event_wait")
                                .put("reason", result.optString("reason", "subscription_removed"))
                                .put("nextAction", "read_status_and_rearm").put("automaticRetryAllowed", false)
                            callback(response(request, if (code == "EVENT_CANCELLED") "CANCELLED" else code,
                                false, lifecycle().snapshot(), data).toString())
                        } else callback(response(request, "OK", true, lifecycle().snapshot(), result).toString())
                    }
                    if (!accepted) {
                        eventWaitSubscriptions.remove(request.requestId)
                        callback(response(request, "EVENT_SUBSCRIPTION_INVALID", false, lifecycle().snapshot()).toString())
                    }
                }
            }
            "cancel" -> {
                val id = request.args.optString("subscriptionId")
                val sub = eventCoordinator.subscription(id)
                val identity = currentEventIdentity()?.toTaskIdentity()
                if (sub == null || identity == null || sub.taskId != taskId) {
                    callback(response(request, "EVENT_SUBSCRIPTION_INVALID", false, snapshot).toString()); return
                }
                taskCoordinator.validateEventContext(
                    taskId, identity, sub.bindingEpoch, true, sub.actionRequestId, sub.initialIdentity.toTaskIdentity(),
                ) { gate ->
                    val cancelled = gate != null && isAuthorizedNow(request) && eventCoordinator.cancel(id, taskId)
                    callback(response(request, if (cancelled) "OK" else "EVENT_SUBSCRIPTION_INVALID", cancelled,
                        lifecycle().snapshot(), JSONObject().put("cancelled", cancelled)).toString())
                }
            }
            "resolve_dialog" -> {
                val eventId = request.args.optString("eventId")
                val sub = eventCoordinator.subscriptionForDialog(eventId)
                val identity = currentEventIdentity()?.toTaskIdentity()
                if (sub == null || identity == null || sub.taskId != taskId || sub.actionRequestId == null) {
                    callback(response(request, "EVENT_SUBSCRIPTION_INVALID", false, snapshot).toString()); return
                }
                val parentEffectId = sub.actionRequestId ?: run {
                    callback(response(request, "EVENT_SUBSCRIPTION_INVALID", false, snapshot).toString()); return
                }
                if (sub.identity != sub.initialIdentity) {
                    callback(response(request, "EVENT_SUBSCRIPTION_INVALID", false, snapshot).toString()); return
                }
                taskCoordinator.validateEventContext(
                    taskId, identity, sub.bindingEpoch, true, parentEffectId, sub.initialIdentity.toTaskIdentity(),
                ) { gate ->
                    val currentIdentity = currentEventIdentity()
                    val decision = request.args.optString("decision")
                    val promptText = request.args.optString("promptText").takeIf { request.args.has("promptText") }
                    val taskIdentity = sub.initialIdentity.toTaskIdentity()
                    if (gate == null || !isAuthorizedNow(request) || currentIdentity == null || taskIdentity == null ||
                        currentIdentity.pageId != sub.identity.pageId || currentIdentity.documentGeneration != sub.identity.documentGeneration
                    ) {
                        callback(response(request, "EVENT_SUBSCRIPTION_INVALID", false, lifecycle().snapshot()).toString())
                        return@validateEventContext
                    }
                    taskCoordinator.recordEventDialogResolution(
                        taskId, gate.task.revision, eventId, parentEffectId, taskIdentity, decision, promptText,
                    ) { audited ->
                        val latestIdentity = currentEventIdentity()
                        val resolved = audited && isAuthorizedNow(request) && latestIdentity != null &&
                            latestIdentity.pageId == sub.identity.pageId && latestIdentity.documentGeneration == sub.identity.documentGeneration &&
                            eventCoordinator.resolveDialog(eventId, taskId, latestIdentity, decision, promptText)
                        taskCoordinator.completeEventDialogResolution(eventId, resolved) { auditComplete ->
                            val accepted = resolved && auditComplete
                            callback(response(request, if (accepted) "OK" else "EVENT_SUBSCRIPTION_INVALID", accepted,
                                lifecycle().snapshot(), JSONObject().put("resolved", accepted)).toString())
                        }
                    }
                }
            }
            else -> callback(response(request, "INVALID_ARGUMENT", false, snapshot).toString())
        }
    }

    private fun BrowserEventCoordinator.Identity.toTaskIdentity(): BrowserTaskPageIdentity? = runCatching {
        BrowserTaskPageIdentity(sessionGeneration, controlGeneration, pageId, documentGeneration)
    }.getOrNull()

    private fun isAuthorizedNow(request: BrowserCommandRequest): Boolean {
        val snapshot = lifecycle().snapshot()
        return snapshot.lifecycle == BrowserLifecycle.ACTIVE && automationEnabled() && snapshot.owner == BrowserOwner.AI &&
            snapshot.sessionGeneration == request.sessionGeneration && snapshot.controlGeneration == request.controlGeneration &&
            SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis
    }

    private fun requiresPreAdmittedToken(request: BrowserCommandRequest): Boolean = when (request.method) {
        BrowserCommandMethod.OPEN -> request.sessionGeneration != null
        BrowserCommandMethod.CLICK, BrowserCommandMethod.TYPE, BrowserCommandMethod.SCROLL,
        BrowserCommandMethod.SCREENSHOT, BrowserCommandMethod.CLOSE, BrowserCommandMethod.NAVIGATE,
        BrowserCommandMethod.DEBUG, BrowserCommandMethod.NETWORK -> true
        BrowserCommandMethod.FILE -> request.args.optString("action") in setOf("start", "cancel")
        BrowserCommandMethod.UPLOAD -> true
        BrowserCommandMethod.EVENT -> request.args.optString("action") == "arm"
        else -> false
    }

    private fun executeFileRequest(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        if (!automationEnabled()) {
            callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
            return
        }
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) {
            callback(response(request, "CLOSED", false, snapshot).toString())
            return
        }
        if (request.sessionGeneration != snapshot.sessionGeneration) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString())
            return
        }
        if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
            callback(response(request, "DEADLINE_EXPIRED", false, snapshot).toString())
            return
        }
        val action = request.args.optString("action")
        val controlled = action == "start" || action == "cancel"
        if (controlled) {
            if (!automationEnabled()) {
                callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
                return
            }
            if (snapshot.owner != BrowserOwner.AI) {
                callback(response(request, "USER_CONTROLLED", false, snapshot).toString())
                return
            }
            if (request.owner != "ai" || request.controlGeneration != snapshot.controlGeneration) {
                callback(response(request, "STALE_SESSION", false, snapshot).toString())
                return
            }
            val token = beginAiOperation(request)
            if (token == null) {
                callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString())
                return
            }
            val stillAuthorized = { isTokenCurrent(token) }
            try {
                executeFile(action, request.args, request.deadlineAtElapsedMillis, stillAuthorized) { result ->
                    main.post {
                        val executed = result.opt("executed") as? Boolean
                        val code = result.optString("code", "UNKNOWN").takeIf { it.matches(CODE_PATTERN) } ?: "UNKNOWN"
                        val effective = executed == true && stillAuthorized()
                        lifecycle().completeOperation(token, effective)
                        val latest = lifecycle().snapshot()
                        publishSnapshot(latest)
                        val responseCode = if (!stillAuthorizedAfterCompletion(token, latest) && effective) "UNKNOWN" else code
                        val responseExecuted: Boolean? = if (responseCode == "UNKNOWN" && effective) null else executed
                        callback(boundedAdvancedResponse(request, result, latest, responseCode, responseExecuted).toString())
                    }
                }
            } catch (_: Exception) {
                lifecycle().completeOperation(token, false)
                val latest = lifecycle().snapshot()
                publishSnapshot(latest)
                callback(response(request, "UNKNOWN", null, latest).toString())
            }
            return
        }

        val stillAuthorized = {
            val authorization = authorizationMirror.get()
            val current = authorization?.snapshot
            authorization?.enabled == true && current != null && current.lifecycle == BrowserLifecycle.ACTIVE &&
                current.sessionGeneration == request.sessionGeneration &&
                SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis
        }
        try {
            executeFile(action, request.args, request.deadlineAtElapsedMillis, stillAuthorized) { result ->
                main.post {
                    val latest = lifecycle().snapshot()
                    if (!stillAuthorized()) {
                        callback(response(request, "SESSION_REVOKED", false, latest).toString())
                    } else if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                        callback(response(request, "DEADLINE_EXPIRED", null, latest).toString())
                    } else {
                        callback(boundedAdvancedResponse(request, result, latest).toString())
                    }
                }
            }
        } catch (_: Exception) {
            callback(response(request, "UNKNOWN", null, lifecycle().snapshot()).toString())
        }
    }

    private fun executeUploadRequest(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val taskId = request.taskId
        val initial = currentTaskContext()
        val identity = initial.identity
        val token = preAdmittedTokens[request.requestId]
        if (token == null) {
            callback(response(request, "TASK_PAGE_MISMATCH", false, lifecycle().snapshot()).toString())
            return
        }
        val stillAuthorized = {
            isTokenCurrent(token) && currentTaskContext().let {
                it.active && it.automationEnabled && it.ownerIsAi && identity != null && it.identity == identity
            } && SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis
        }
        val finalizer = BrowserUploadOperationFinalizer(
            token = token,
            completeOperation = lifecycle()::completeOperation,
            snapshot = { lifecycle().snapshot() },
            publishSnapshot = ::publishSnapshot,
        )
        var timeout: Runnable? = null
        fun finishResult(
            result: JSONObject,
            forcedCode: String? = null,
            forcedExecuted: Boolean? = null,
            forceUnknown: Boolean = false,
        ) {
            main.post {
                val code = forcedCode ?: result.optString("code", "UNKNOWN").takeIf { it.matches(CODE_PATTERN) } ?: "UNKNOWN"
                val executed = if (forcedCode == null) result.opt("executed") as? Boolean else forcedExecuted
                val authorized = stillAuthorized()
                val effective = code == "OK" && executed == true && authorized
                finalizer.finish(effective) { tokenCompleted, latest ->
                    timeout?.let { main.removeCallbacks(it) }
                    val authorizedAfterCompletion = tokenCompleted && latest.lifecycle == BrowserLifecycle.ACTIVE &&
                        latest.owner == BrowserOwner.AI && latest.sessionGeneration == token.sessionGeneration &&
                        latest.controlGeneration == token.controlGeneration && automationEnabled() &&
                        SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis &&
                        currentTaskContext().let { it.active && it.ownerIsAi && it.automationEnabled && it.identity == identity }
                    val authorizationValid = authorized && authorizedAfterCompletion
                    val (finalCode, finalExecuted) = browserUploadOutcome(code, executed, authorizationValid, tokenCompleted)
                    val output = when {
                        forceUnknown || finalCode == "UNKNOWN" ->
                            response(request, "UNKNOWN", null, latest)
                        !authorizationValid && executed != true && code == "OK" -> response(
                            request,
                            if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) "DEADLINE_EXPIRED" else "SESSION_REVOKED",
                            false,
                            latest,
                        )
                        else -> boundedAdvancedResponse(request, result, latest, finalCode, finalExecuted)
                    }
                    callback(output.toString())
                    if (latest.lifecycle == BrowserLifecycle.CLOSING) closeSession()
                }
            }
        }

        val timeoutDeadline = minOf(token.deadlineAtMillis, request.deadlineAtElapsedMillis)
        val timeoutTask = Runnable { finishResult(JSONObject(), forcedCode = "UNKNOWN", forceUnknown = true) }
        timeout = timeoutTask
        main.postDelayed(timeoutTask, (timeoutDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))

        if (taskId == null || identity == null || !initial.active || !initial.automationEnabled || !initial.ownerIsAi) {
            finishResult(response(request, "TASK_PAGE_MISMATCH", false, lifecycle().snapshot()))
            return
        }
        val coordinator = BrowserTaskCoordinatorRegistry.get()
        if (coordinator == null) {
            finishResult(response(request, "JOURNAL_UNAVAILABLE", false, lifecycle().snapshot()))
            return
        }
        try {
            coordinator.validateUploadTask(taskId, identity) { taskBound ->
                if (!taskBound || !stillAuthorized()) {
                    finishResult(response(request,
                        if (stillAuthorized()) "TASK_PAGE_MISMATCH" else "SESSION_REVOKED",
                        false,
                        lifecycle().snapshot()))
                    return@validateUploadTask
                }
                try {
                    executeUpload(request, identity, token, stillAuthorized) { result -> finishResult(result) }
                } catch (_: Exception) {
                    finishResult(JSONObject(), forcedCode = "UNKNOWN", forceUnknown = true)
                }
            }
        } catch (_: Exception) {
            finishResult(JSONObject(), forcedCode = "UNKNOWN", forceUnknown = true)
        }
    }

    internal fun currentUploadPageIdentity(): BrowserTaskPageIdentity? = currentTaskContext().identity

    internal fun uploadNodeReference(value: String): Pair<String, Int>? = parseObservationRef(value, lifecycle().snapshot())

    private fun isTokenCurrent(token: BrowserOperationToken): Boolean {
        val auth = authorizationMirror.get() ?: return false
        val current = auth.snapshot
        return auth.enabled && current.lifecycle == BrowserLifecycle.ACTIVE &&
            current.owner == BrowserOwner.AI && current.sessionGeneration == token.sessionGeneration &&
            current.controlGeneration == token.controlGeneration &&
            current.inFlightOperationId == token.operationId &&
            current.operationGeneration == token.operationGeneration &&
            SystemClock.elapsedRealtime() < token.deadlineAtMillis
    }

    private fun executePageRequest(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) {
            callback(response(request, "CLOSED", false, snapshot).toString())
            return
        }
        if (!automationEnabled()) {
            callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
            return
        }
        if (request.sessionGeneration != snapshot.sessionGeneration) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString())
            return
        }
        if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
            callback(response(request, "DEADLINE_EXPIRED", false, snapshot).toString())
            return
        }

        val action = request.args.optString("action")
        if (action == "list") {
            val result = runCatching { executePage(action, request.args) }.getOrElse {
                BrowserPageCommandResult("UNKNOWN", null, JSONObject())
            }
            val expired = SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis
            callback(response(request, if (expired) "DEADLINE_EXPIRED" else result.code,
                if (expired) false else result.executed, lifecycle().snapshot(), result.data).toString())
            return
        }
        if (snapshot.owner != BrowserOwner.AI) {
            callback(response(request, "USER_CONTROLLED", false, snapshot).toString())
            return
        }
        if (request.owner != "ai" || request.controlGeneration != snapshot.controlGeneration) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString())
            return
        }
        if (snapshot.inFlightOperationId != null) {
            callback(response(request, "BUSY_OR_STALE", false, snapshot).toString())
            return
        }

        val result = runCatching { executePage(action, request.args) }.getOrElse {
            BrowserPageCommandResult("UNKNOWN", null, JSONObject())
        }
        if (result.executed == true && result.contextChanged) {
            lifecycle().recordEffectiveActivity(snapshot.commandContext())
            val changed = lifecycle().handToAi()
            publishSnapshot(changed)
            val expired = SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis
            callback(response(request, if (expired) "UNKNOWN" else result.code,
                if (expired) null else result.executed, changed, result.data).toString())
        } else {
            callback(response(request, result.code, result.executed, lifecycle().snapshot(), result.data).toString())
        }
    }

    private fun executeAdvancedRequest(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) {
            callback(response(request, "CLOSED", false, snapshot).toString())
            return
        }
        if (!automationEnabled()) {
            callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
            return
        }
        if (request.sessionGeneration != snapshot.sessionGeneration) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString())
            return
        }
        val family = when (request.method) {
            BrowserCommandMethod.DEBUG -> "debug"
            BrowserCommandMethod.NETWORK -> "network"
            BrowserCommandMethod.SELECTION -> "selection"
            else -> error("Not an advanced method")
        }
        if (family == "selection") {
            val stillAuthorized = {
                val current = authorizationMirror.get()
                current != null && current.enabled &&
                    current.snapshot.lifecycle == BrowserLifecycle.ACTIVE &&
                    current.snapshot.sessionGeneration == request.sessionGeneration &&
                    SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis
            }
            try {
                executeAdvanced(family, request.args, request.deadlineAtElapsedMillis, stillAuthorized) { result ->
                    main.post {
                        val current = lifecycle().snapshot()
                        if (!stillAuthorized()) {
                            callback(response(request, "SESSION_REVOKED", false, current).toString())
                        } else {
                            callback(boundedAdvancedResponse(request, result, current).toString())
                        }
                    }
                }
            } catch (_: Exception) {
                callback(response(request, "UNKNOWN", null, lifecycle().snapshot()).toString())
            }
            return
        }
        if (!automationEnabled()) {
            callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
            return
        }
        if (snapshot.owner != BrowserOwner.AI) {
            callback(response(request, "USER_CONTROLLED", false, snapshot).toString())
            return
        }
        if (request.owner != "ai" || request.controlGeneration != snapshot.controlGeneration) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString())
            return
        }
        val startDocument = documentGeneration
        val token = beginAiOperation(request)
        if (token == null) {
            callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString())
            return
        }
        val documentBound = isAdvancedRequestDocumentBound(family, request.args.optString("action"))
        val stillAuthorized = {
            val auth = authorizationMirror.get()
            val current = auth?.snapshot
            auth != null && auth.enabled && current != null &&
                current.lifecycle == BrowserLifecycle.ACTIVE &&
                current.owner == BrowserOwner.AI &&
                current.sessionGeneration == token.sessionGeneration &&
                current.controlGeneration == token.controlGeneration &&
                current.inFlightOperationId == token.operationId &&
                current.operationGeneration == token.operationGeneration &&
                (!documentBound || documentGeneration == startDocument) &&
                SystemClock.elapsedRealtime() < token.deadlineAtMillis
        }
        try {
            val advancedArgs = JSONObject(request.args.toString())
            if (family == "network" && (advancedArgs.optString("action") in setOf("replay_prepare", "replay_status") || advancedArgs.has("riskToken"))) {
                advancedArgs.put("sessionGeneration", token.sessionGeneration).put("controlGeneration", token.controlGeneration)
            }
            executeAdvanced(family, advancedArgs, request.deadlineAtElapsedMillis, stillAuthorized) { result ->
                main.post {
                    val executed = result.opt("executed") as? Boolean
                    val code = result.optString("code", "UNKNOWN").takeIf { it.matches(CODE_PATTERN) } ?: "UNKNOWN"
                    val effective = executed == true && stillAuthorized()
                    val current = lifecycle().completeOperation(token, effective)
                    val latest = lifecycle().snapshot()
                    publishSnapshot(latest)
                    val contextValid = current && stillAuthorizedAfterCompletion(token, latest) &&
                        !(documentBound && documentGeneration != startDocument)
                    if (!contextValid) {
                        when (val outcome = BrowserAdvancedCompletionPolicy.contextLost(
                            request.args.optString("action"), executed
                        )) {
                            BrowserAdvancedCompletionPolicy.Outcome.PreserveNative ->
                                callback(boundedAdvancedResponse(request, result, latest, code, executed).toString())
                            is BrowserAdvancedCompletionPolicy.Outcome.Respond ->
                                callback(response(request, outcome.code, outcome.executed, latest, outcome.data).toString())
                        }
                    } else {
                        callback(boundedAdvancedResponse(request, result, latest, code, executed).toString())
                    }
                    if (latest.lifecycle == BrowserLifecycle.CLOSING) closeSession()
                }
            }
        } catch (_: Exception) {
            lifecycle().completeOperation(token, false)
            val latest = lifecycle().snapshot()
            publishSnapshot(latest)
            val outcome = BrowserAdvancedCompletionPolicy.synchronousFailure(request.args.optString("action"))
            callback(response(request, outcome.code, outcome.executed, latest, outcome.data).toString())
        }
    }

    private fun stillAuthorizedAfterCompletion(token: BrowserOperationToken, snapshot: BrowserSnapshot): Boolean =
        snapshot.lifecycle == BrowserLifecycle.ACTIVE && automationEnabled() && snapshot.owner == BrowserOwner.AI &&
            snapshot.sessionGeneration == token.sessionGeneration && snapshot.controlGeneration == token.controlGeneration

    private fun boundedAdvancedResponse(
        request: BrowserCommandRequest,
        result: JSONObject,
        snapshot: BrowserSnapshot,
        forcedCode: String? = null,
        forcedExecuted: Boolean? = null,
    ): JSONObject {
        val code = forcedCode ?: result.optString("code", "UNKNOWN").takeIf { it.matches(CODE_PATTERN) } ?: "UNKNOWN"
        val executed = if (forcedCode == null) result.opt("executed") as? Boolean else forcedExecuted
        val data = runCatching { JSONObject(result.optJSONObject("data")?.toString() ?: "{}") }.getOrDefault(JSONObject())
        val responseLimitBytes = if (request.method == BrowserCommandMethod.SELECTION && request.args.optString("action") == "task_pending")
            BrowserCommandRequest.MAX_RESPONSE_JSON_BYTES else BrowserCommandRequest.MAX_JSON_BYTES
        fun encoded() = response(request, code, executed, snapshot, data)
        var output = encoded()
        if (request.method == BrowserCommandMethod.DEBUG && request.args.optString("action") == "ax_observe" &&
            output.toString().toByteArray(Charsets.UTF_8).size > responseLimitBytes) {
            return response(request, "EXECUTION_FAILED", false, snapshot, JSONObject()
                .put("stage", "ax_observation_encoding").put("reason", "OUTPUT_BUDGET_EXCEEDED"))
        }
        while (output.toString().toByteArray(Charsets.UTF_8).size > responseLimitBytes) {
            val arrays = data.keys().asSequence().mapNotNull { key -> data.optJSONArray(key)?.let { key to it } }
            val largest = arrays.maxByOrNull { it.second.length() }
            if (largest != null && largest.second.length() > 0) {
                largest.second.remove(largest.second.length() - 1)
                data.put("truncated", true)
                if (request.method == BrowserCommandMethod.FILE && request.args.optString("action") == "list" && largest.first == "items") {
                    val lastItem = largest.second.optJSONObject(largest.second.length() - 1)
                    data.put("nextCursor", lastItem?.optString("id") ?: JSONObject.NULL)
                }
                output = encoded()
                continue
            }
            val strings = data.keys().asSequence().filter { key ->
                key !in setOf("artifactPath", "mimeType") && data.opt(key) is String && (data.optString(key).isNotEmpty())
            }.toList()
            val field = strings.maxByOrNull { data.optString(it).toByteArray(Charsets.UTF_8).size }
            if (field != null) {
                val value = data.optString(field)
                data.put(field, truncateUtf8(value, (value.toByteArray(Charsets.UTF_8).size / 2).coerceAtLeast(1)).first)
                data.put("truncated", true)
                output = encoded()
                continue
            }
            val keys = data.keys().asSequence().filter { it !in setOf("artifactPath", "mimeType", "bytes", "width", "height", "format", "truncated") }.toList()
            if (keys.isNotEmpty()) {
                keys.forEach { data.remove(it) }
                data.put("truncated", true)
                output = encoded()
                continue
            }
            return response(request, "LIMIT_EXCEEDED", executed, snapshot, JSONObject().put("truncated", true))
        }
        return output
    }

    fun cancel(requestId: String, callback: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { cancel(requestId, callback) }
            return
        }
        val snapshot = lifecycle().snapshot()
        if (screenshotCaptures.remove(requestId)?.cancel() == true) {
            callback(commandResponse(requestId, "CANCELLED", null, snapshot.stateName(),
                snapshot.sessionGeneration, snapshot.controlGeneration,
                snapshot.owner.name.lowercase(Locale.ROOT),
                BrowserCancellationContract.receipt("capture_wait", "cancel_accepted")).toString())
            return
        }
        if (cancelThumbnailWait(requestId)) {
            callback(commandResponse(requestId, "CANCELLED", null, snapshot.stateName(),
                snapshot.sessionGeneration, snapshot.controlGeneration,
                snapshot.owner.name.lowercase(Locale.ROOT),
                BrowserCancellationContract.receipt("capture_wait", "cancel_accepted")).toString())
            return
        }
        val eventSubscription = eventWaitSubscriptions.remove(requestId)
        if (eventSubscription != null && eventCoordinator.cancelWait(eventSubscription)) {
            callback(commandResponse(requestId, "CANCELLED", null, snapshot.stateName(),
                snapshot.sessionGeneration, snapshot.controlGeneration, snapshot.owner.name.lowercase(Locale.ROOT),
                BrowserCancellationContract.receipt("event_wait", "cancel_accepted")).toString())
            return
        }
        if (waitCoordinator.cancel(requestId)) {
            callback(commandResponse(requestId, "CANCELLED", null, snapshot.stateName(),
                snapshot.sessionGeneration, snapshot.controlGeneration, snapshot.owner.name.lowercase(Locale.ROOT),
                BrowserCancellationContract.receipt("condition_wait", "cancel_accepted")).toString())
            return
        }
        val inFlight = snapshot.inFlightOperationId == "ai-" + requestId
        val code = if (inFlight) "TOO_LATE" else "NOT_FOUND"
        val reason = if (inFlight) "request_in_flight" else "request_not_found"
        callback(commandResponse(
            requestId, code, null, snapshot.stateName(), snapshot.sessionGeneration,
            snapshot.controlGeneration, snapshot.owner.name.lowercase(Locale.ROOT),
            BrowserCancellationContract.receipt("dispatch", reason),
        ).toString())
    }

    private fun status(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        callback(response(request, "OK", false, snapshot, JSONObject()
            .put("automationEnabled", automationEnabled())
            .put("owner", snapshot.owner.name.lowercase(Locale.ROOT))
            .put("visible", snapshot.visible)
            .put("idleRemainingMs", BrowserIdlePolicy(snapshot.idleTimeoutMillis)
                .remainingMillis(snapshot, SystemClock.elapsedRealtime()) ?: JSONObject.NULL)
            .put("lastCloseReason", snapshot.closeReason.name.lowercase(Locale.ROOT))).toString())
    }

    private fun open(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val selectionBaseline = selectionSequence()
        val snapshot = expireIfNeeded()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) {
            callback(response(request, "CLOSED", false, snapshot).toString())
            return
        }
        if (!automationEnabled()) {
            callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
            return
        }
        if (snapshot.owner != BrowserOwner.AI) {
            callback(response(request, "USER_CONTROLLED", false, snapshot).toString())
            return
        }
        if (request.sessionGeneration != snapshot.sessionGeneration ||
            request.controlGeneration != snapshot.controlGeneration
        ) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString())
            return
        }
        val token = beginAiOperation(request)
        if (token == null) {
            callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString())
            return
        }
        val url = request.args.optString("url").takeIf { it.isNotBlank() }
        if (url == null) {
            finish(request, token, false, callback, "OK", JSONObject().put("event", "opened")
                .put("selectionSequence", selectionBaseline))
            return
        }
        val view = webView()
        if (view == null) {
            finish(request, token, false, callback, "CLOSED")
            return
        }
        val baselinePageId = activePageId()
        val baselineState = pageState()
        val baselineDocumentGeneration = documentGeneration
        if (!loadUrl(url)) {
            finish(request, token, false, callback, "INVALID_ARGUMENT")
        } else {
            val accepted = pageState()
            val safeRequested = BrowserPageStateTracker.publicUrl(url)
            val data = JSONObject()
                .put("event", "navigation_accepted")
                .put("selectionSequence", selectionBaseline)
                .put("navigationId", accepted?.navigationId)
                .put("loadState", accepted?.loadState?.name?.lowercase(Locale.ROOT) ?: "accepted")
                .put("requestedUrl", safeRequested.value)
                .put("requestedUrlQueryRedacted", safeRequested.queryRedacted)
                .put("requestedUrlFragmentRedacted", safeRequested.fragmentRedacted)
                .also { BrowserNavigationMetadata.addTo(it, accepted) }
            if (request.args.optBoolean("thumbnail", false)) {
                startThumbnailAfterAcceptedNavigation(
                    request = request,
                    token = token,
                    view = view,
                    baselinePageId = baselinePageId,
                    baselineNavigationId = baselineState?.navigationId,
                    baselineDocumentGeneration = baselineDocumentGeneration,
                    expectedNavigationId = accepted?.navigationId ?: -1L,
                    expectedRequestedUrl = accepted?.requestedUrl,
                    data = data,
                    callback = callback,
                )
            } else {
                finish(request, token, true, callback, "OK", data)
            }
        }
    }

    private fun observe(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
            callback(response(request, "DEADLINE_EXPIRED", false, snapshot).toString())
            return
        }
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) {
            callback(response(request, "CLOSED", false, snapshot).toString())
            return
        }
        if (!automationEnabled()) {
            callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
            return
        }
        if (request.sessionGeneration != snapshot.sessionGeneration) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString())
            return
        }
        val view = webView()
        if (view == null) {
            callback(response(request, "CLOSED", false, snapshot).toString())
            return
        }
        val maxTextChars = request.args.optInt("maxTextChars", 4096)
        if (maxTextChars !in 1..4096 || request.args.length() > 1) {
            callback(response(request, "INVALID_ARGUMENT", false, snapshot).toString())
            return
        }
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val pageSnapshot = pageState()
        if (pageSnapshot?.loadState != BrowserPageStateTracker.LoadState.FINISHED) {
            val stateUrl = pageSnapshot?.currentUrl ?: pageSnapshot?.requestedUrl
            val safeUrl = BrowserPageStateTracker.publicUrl(stateUrl)
            val data = JSONObject()
                .put("url", safeUrl.value)
                .put("urlQueryRedacted", safeUrl.queryRedacted)
                .put("urlFragmentRedacted", safeUrl.fragmentRedacted)
                .put("loadState", pageSnapshot?.loadState?.name?.lowercase(Locale.ROOT) ?: "unknown")
                .put("readyState", pageSnapshot?.readyState ?: JSONObject.NULL)
                .put("navigationId", pageSnapshot?.navigationId ?: JSONObject.NULL)
            pageSnapshot?.mainFrameError?.let { data.put("mainFrameError", it) }
            pageSnapshot?.httpStatus?.let { data.put("httpStatus", it) }
            BrowserNavigationMetadata.addTo(data, pageSnapshot)
            callback(response(request, "OK", true, snapshot, data).toString())
            return
        }
        val previousNonce = referenceNonce
        val observedPageId = activePageId()
        val observedDocumentGeneration = documentGeneration
        val observation = ++observationGeneration
        referenceNonce = nonce
        latestObservation = observation
        referencePageId = observedPageId
        referenceView = view
        view.evaluateJavascript(BrowserSameDocumentHistoryScript.install + ";\n" +
            BrowserDomScript.observe(maxTextChars, nonce, previousNonce)) { encoded ->
            var current = lifecycle().snapshot()
            if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                current = expireIfNeeded()
                callback(response(request, "DEADLINE_EXPIRED", false, current).toString())
                return@evaluateJavascript
            }
            if (current.lifecycle != BrowserLifecycle.ACTIVE ||
                current.sessionGeneration != request.sessionGeneration ||
                current.sessionGeneration != snapshot.sessionGeneration ||
                current.controlGeneration != snapshot.controlGeneration ||
                documentGeneration != observedDocumentGeneration ||
                referenceNonce != nonce || !automationEnabled() || observation != observationGeneration ||
                activePageId() != observedPageId || webView() !== view
            ) {
                callback(response(request, "STALE_REFERENCE", null, current).toString())
                return@evaluateJavascript
            }
            val pageJson = decodeJavascriptString(encoded)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (pageJson == null) {
                callback(response(request, "EXECUTION_FAILED", false, current).toString())
                return@evaluateJavascript
            }
            val currentPageState = pageState()
            if (pageSnapshot != null && currentPageState?.navigationId != pageSnapshot.navigationId) {
                callback(response(request, "STALE_REFERENCE", null, current).toString())
                return@evaluateJavascript
            }
            if (currentPageState == null ||
                currentPageState.loadState != BrowserPageStateTracker.LoadState.FINISHED ||
                currentPageState.readyState != "complete"
            ) {
                if (currentPageState?.loadState == BrowserPageStateTracker.LoadState.FAILED) {
                    val failedUrl = BrowserPageStateTracker.publicUrl(currentPageState.currentUrl)
                    val failedData = JSONObject()
                        .put("url", failedUrl.value)
                        .put("urlQueryRedacted", failedUrl.queryRedacted)
                        .put("urlFragmentRedacted", failedUrl.fragmentRedacted)
                        .put("loadState", "failed")
                        .put("readyState", JSONObject.NULL)
                    currentPageState.mainFrameError?.let { failedData.put("mainFrameError", it) }
                    currentPageState.httpStatus?.let { failedData.put("httpStatus", it) }
                    BrowserNavigationMetadata.addTo(failedData, currentPageState)
                    callback(response(request, "OK", true, current, failedData).toString())
                } else {
                    callback(response(request, "STALE_REFERENCE", null, current).toString())
                }
                return@evaluateJavascript
            }
            val scopeElements = pageJson.optJSONArray("elements")
            if (!BrowserDomScopeMetadata.valid(pageJson) || scopeElements == null ||
                (0 until scopeElements.length()).any { index ->
                    val element = scopeElements.optJSONObject(index)
                    element == null || !BrowserDomScopeMetadata.observationIndexValid(element, index) ||
                        !BrowserDomScopeMetadata.nameFieldsValid(element) ||
                        !BrowserDomScopeMetadata.targetValid(element, pageJson.getJSONArray("frameScopes"))
                }
            ) {
                latestObservation = null
                callback(response(request, "EXECUTION_FAILED", false, current).toString())
                return@evaluateJavascript
            }
            val sameDocument = pageSnapshot != null && currentPageState != null &&
                currentPageState.navigationId == pageSnapshot.navigationId &&
                currentPageState.loadState == BrowserPageStateTracker.LoadState.FINISHED &&
                currentPageState.readyState == "complete"
            val renewed = recordSuccessfulObserveActivity(
                lifecycle = lifecycle(),
                initial = snapshot,
                current = current,
                automationEnabled = automationEnabled(),
                successfulDom = true,
                sameDocument = sameDocument && documentGeneration == observedDocumentGeneration &&
                    referenceNonce == nonce && observation == observationGeneration,
                deadlineAtElapsedMillis = request.deadlineAtElapsedMillis,
                nowElapsedMillis = SystemClock.elapsedRealtime(),
            )
            if (renewed) {
                current = lifecycle().snapshot()
                publishSnapshot(current)
                scheduleIdle(current)
            } else if (current.lifecycle == BrowserLifecycle.ACTIVE &&
                lifecycle().snapshot().lifecycle != BrowserLifecycle.ACTIVE
            ) {
                current = expireIfNeeded()
            }
            val safePageUrl = BrowserPageStateTracker.publicUrl(pageJson.optString("url"))
            pageJson.put("url", safePageUrl.value)
                .put("urlQueryRedacted", safePageUrl.queryRedacted)
                .put("urlFragmentRedacted", safePageUrl.fragmentRedacted)
            pageJson.put("loadState", currentPageState?.loadState?.name?.lowercase(Locale.ROOT) ?: "unknown")
                .put("readyState", pageJson.optString("readyState").takeIf { it.isNotBlank() })
            BrowserNavigationMetadata.addTo(pageJson, currentPageState)
            currentPageState?.httpStatus?.let { pageJson.put("httpStatus", it) }
            currentPageState?.mainFrameError?.let { pageJson.put("mainFrameError", it) }
            val text = pageJson.optString("text")
            val (boundedText, truncated) = truncateUtf8(text, 4096)
            pageJson.put("text", boundedText).put("truncated", truncated || pageJson.optBoolean("truncated"))
                .put("textTruncated", truncated || pageJson.optBoolean("textTruncated"))
            val elements = pageJson.optJSONArray("elements") ?: JSONArray()
            for (index in 0 until elements.length()) {
                val element = elements.optJSONObject(index) ?: continue
                val safeHref = BrowserPageStateTracker.publicUrl(element.optString("href"))
                element.put("href", safeHref.value ?: "")
                    .put("hrefQueryRedacted", safeHref.queryRedacted)
                    .put("hrefFragmentRedacted", safeHref.fragmentRedacted)
                element.put("nodeId", observationRef(current, observation, nonce, element.optInt("index")))
                element.remove("index")
            }
            BrowserOverlayMetadata.attach(pageJson, elements)
            pageJson.put("blockInfo", BrowserBlockedPageEvidence.inspect(pageJson))
            pageJson.put("observationGeneration", observation)
            pageJson.put("topFrameOnly", false)
            pageJson.put("openShadowRootsSupported", true)
            pageJson.put("closedShadowRootsSupported", false)
            pageJson.put("crossOriginFramesSupported", false)
            callback(fitObservationResponse(request, current, pageJson, elements))
        }
    }

    private fun fitObservationResponse(
        request: BrowserCommandRequest,
        snapshot: BrowserSnapshot,
        data: JSONObject,
        elements: JSONArray,
    ): String {
        var encoded = response(request, "OK", true, snapshot, data).toString()
        while (encoded.toByteArray(Charsets.UTF_8).size > BrowserCommandRequest.MAX_JSON_BYTES && elements.length() > 0) {
            elements.remove(elements.length() - 1)
            BrowserOverlayMetadata.prune(data, elements)
            data.put("truncated", true).put("elementsTruncated", true)
            encoded = response(request, "OK", true, snapshot, data).toString()
        }
        var text = data.optString("text")
        while (encoded.toByteArray(Charsets.UTF_8).size > BrowserCommandRequest.MAX_JSON_BYTES && text.isNotEmpty()) {
            val halfBytes = (text.toByteArray(Charsets.UTF_8).size / 2).coerceAtLeast(1)
            text = truncateUtf8(text, halfBytes).first
            data.put("text", text).put("truncated", true).put("textTruncated", true)
            encoded = response(request, "OK", true, snapshot, data).toString()
        }
        if (encoded.toByteArray(Charsets.UTF_8).size > BrowserCommandRequest.MAX_JSON_BYTES) {
            data.remove("url")
            data.remove("title")
            encoded = response(request, "OK", true, snapshot, data).toString()
        }
        if (encoded.toByteArray(Charsets.UTF_8).size > BrowserCommandRequest.MAX_JSON_BYTES) {
            return response(request, "EXECUTION_FAILED", false, snapshot, JSONObject()
                .put("stage", "observation_encoding").put("reason", "OUTPUT_BUDGET_EXCEEDED")).toString()
        }
        return encoded
    }

    private fun executeAiAction(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) {
            callback(response(request, "CLOSED", false, snapshot).toString())
            return
        }
        if (!automationEnabled()) {
            callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString())
            return
        }
        if (snapshot.owner != BrowserOwner.AI) {
            callback(response(request, "USER_CONTROLLED", false, snapshot).toString())
            return
        }
        if (request.sessionGeneration != snapshot.sessionGeneration ||
            request.controlGeneration != snapshot.controlGeneration
        ) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString())
            return
        }
        val token = beginAiOperation(request)
        if (token == null) {
            callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString())
            return
        }
        when (request.method) {
            BrowserCommandMethod.CLICK, BrowserCommandMethod.TYPE -> executeDomAction(request, token, callback)
            BrowserCommandMethod.SCROLL -> executeScroll(request, token, callback)
            BrowserCommandMethod.SCREENSHOT -> executeScreenshot(request, token, callback)
            BrowserCommandMethod.CLOSE -> {
                actionGate.invalidate()
                val closing = lifecycle().beginClose()
                publishSnapshot(closing)
                callback(response(request, "OK", true, closing, JSONObject().put("event", "closed")).toString())
                closeSession()
            }
            else -> finish(request, token, false, callback, "UNSUPPORTED_METHOD")
        }
    }

    private fun executeDomAction(
        request: BrowserCommandRequest,
        token: BrowserOperationToken,
        callback: (String) -> Unit,
    ) {
        if (request.method == BrowserCommandMethod.CLICK && request.args.has("screenshotId")) {
            executeVisualDomClick(request, token, callback)
            return
        }
        val current = lifecycle().snapshot()
        val reference = request.args.optString("nodeId")
        val parsedReference = parseObservationRef(reference, current)
        if (parsedReference == null) {
            finish(request, token, false, callback, "STALE_REFERENCE")
            return
        }
        val (nonce, index) = parsedReference
        val authorizationNonce = newActionNonce()
        if (!actionGate.register(authorizationNonce, token)) {
            finish(request, token, false, callback, "AUTHORIZATION_REVOKED")
            return
        }
        val script = when (request.method) {
            BrowserCommandMethod.CLICK -> BrowserDomScript.click(nonce, index, authorizationNonce)
            BrowserCommandMethod.TYPE -> {
                val text = request.args.optString("text", "")
                if (text.length > 4096) {
                    finish(request, token, false, callback, "INVALID_ARGUMENT")
                    return
                }
                BrowserDomScript.type(nonce, index, text, request.args.optBoolean("replace", true), authorizationNonce)
            }
            else -> return
        }
        val view = webView()
        if (view == null) {
            finish(request, token, false, callback, "CLOSED")
            return
        }
        view.evaluateJavascript(script) { encoded ->
            val result = decodeJavascriptString(encoded)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val code = result?.optString("code") ?: "EXECUTION_FAILED"
            val data = JSONObject()
            if (code == "OBSCURED") {
                result?.optJSONObject("obscuredBy")?.takeIf { obstruction ->
                    obstruction.length() == 3 && obstruction.opt("tag") is String &&
                        obstruction.opt("id") is String && obstruction.opt("className") is String &&
                        obstruction.getString("tag").length <= 64 && obstruction.getString("id").length <= 128 &&
                        obstruction.getString("className").length <= 256
                }?.let { data.put("obscuredBy", it) }
            }
            finish(request, token, code == "OK", callback, code, data)
        }
    }

    private fun executeScroll(
        request: BrowserCommandRequest,
        token: BrowserOperationToken,
        callback: (String) -> Unit,
    ) {
        val view = webView()
        if (view == null) {
            finish(request, token, false, callback, "CLOSED")
            return
        }
        val direction = request.args.optString("direction")
        val steps = request.args.optInt("steps", 1)
        val nodeId = request.args.optString("nodeId").takeIf { it.isNotEmpty() }
        if (nodeId == null) {
            val nonce = newActionNonce()
            if (!actionGate.register(nonce, token) || !actionGate.authorize(nonce)) {
                finish(request, token, false, callback, "AUTHORIZATION_REVOKED")
                return
            }
            val before = view.scrollY
            val delta = (view.height * 0.8f).toInt().coerceAtLeast(120) * steps
            view.scrollBy(0, if (direction == "up") -delta else delta)
            val after = view.scrollY
            val moved = after != before
            finish(request, token, moved, callback, if (moved) "OK" else "NO_EFFECT", JSONObject()
                .put("scrollY", view.scrollY).put("direction", direction).put("steps", steps))
            return
        }
        val reference = parseObservationRef(nodeId, lifecycle().snapshot())
        if (reference == null) {
            finish(request, token, false, callback, "STALE_REFERENCE")
            return
        }
        val nonce = newActionNonce()
        if (!actionGate.register(nonce, token)) {
            finish(request, token, false, callback, "AUTHORIZATION_REVOKED")
            return
        }
        view.evaluateJavascript(BrowserDomScript.scroll(
            reference.first, reference.second, direction, steps, nonce,
        )) { encoded ->
            val result = decodeJavascriptString(encoded)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val code = result?.optString("code") ?: "EXECUTION_FAILED"
            finish(request, token, code == "OK", callback, code, result ?: JSONObject())
        }
    }

    private fun executeScreenshot(
        request: BrowserCommandRequest,
        token: BrowserOperationToken,
        callback: (String) -> Unit,
    ) {
        if (request.args.length() != 0) {
            finish(request, token, false, callback, "INVALID_ARGUMENT")
            return
        }
        val view = webView()
        if (view == null) {
            finish(request, token, false, callback, "CLOSED")
            return
        }
        val capturePageId = activePageId()
        val captureDocumentGeneration = documentGeneration
        val captureNavigationId = pageState()?.navigationId
        val captureWidth = view.width
        val captureHeight = view.height
        val captureScrollX = view.scrollX
        val captureScrollY = view.scrollY
        val captureScale = view.scale
        val captureScopeCurrent = {
            view === webView() && activePageId() == capturePageId &&
                documentGeneration == captureDocumentGeneration && pageState()?.navigationId == captureNavigationId &&
                view.width == captureWidth && view.height == captureHeight &&
                view.scrollX == captureScrollX && view.scrollY == captureScrollY && view.scale == captureScale
        }
        fun probe(callback: (JSONObject?) -> Unit) {
            if (visualSurfaceAvailable()) withVisualProbe(view, callback) else callback(null)
        }
        val registration = ScreenshotCaptureRegistration()
        screenshotCaptures[request.requestId] = registration
        val detailed = captureDetailed ?: BrowserScreenshotCaptureSource { deadline, detailedCallback ->
            capture(deadline) { bitmap, code ->
                detailedCallback(BrowserScreenshotCaptureResult(bitmap, code))
            }
            BrowserScreenshotCaptureHandle { false }
        }
        probe { beforeMetrics ->
        val handle = detailed.capture(token.deadlineAtMillis) { captureResult ->
            registration.complete()
            val bitmap = captureResult.value
            val captureCode = captureResult.code
            if (bitmap == null) {
                finish(request, token, false, callback, captureCode,
                    screenshotFailureData(captureCode, captureResult.outcome))
                return@capture
            }
            val beforeWrite = lifecycle().snapshot()
            if (beforeWrite.lifecycle != BrowserLifecycle.ACTIVE ||
                beforeWrite.sessionGeneration != token.sessionGeneration ||
                beforeWrite.controlGeneration != token.controlGeneration ||
                beforeWrite.inFlightOperationId != token.operationId ||
                beforeWrite.operationGeneration != token.operationGeneration ||
                SystemClock.elapsedRealtime() >= token.deadlineAtMillis
            ) {
                bitmap.recycle()
                finish(request, token, false, callback, "IDENTITY_CHANGED",
                    screenshotFailureData("IDENTITY_CHANGED", captureResult.outcome))
                return@capture
            }
            val width = bitmap.width
            val height = bitmap.height
            probe afterProbe@ { afterMetrics ->
                val before = beforeMetrics?.let { visualIdentity(view, it, width, height) }
                val after = afterMetrics?.let { visualIdentity(view, it, width, height) }
                val identityStable = visualSurfaceAvailable() && before != null && before == after &&
                    width == view.width && height == view.height
                val pixelDigest = if (identityStable) BrowserVisualPixelDigest.fromBitmap(bitmap) else null
                val rasterMatches = width == view.width && height == view.height ||
                    captureResult.outcome.backend == BrowserScreenshotCaptureContract.FALLBACK_BACKEND &&
                    com.dshbox.browser.devtools.CdpViewportRaster.matchesNativeViewport(
                        view.width, view.height, width, height, view.resources.displayMetrics.density)
                if (!captureScopeCurrent() || !rasterMatches) {
                    bitmap.recycle()
                    finish(request, token, false, callback, "IDENTITY_CHANGED",
                        screenshotFailureData("IDENTITY_CHANGED", captureResult.outcome))
                    return@afterProbe
                }
                var writeFailure = "STORAGE_FAILED"
                val artifact = try {
                    writeScreenshot(request.requestId, bitmap)
                } catch (_: ScreenshotEncodingException) {
                    writeFailure = "ENCODE_FAILED"
                    null
                } catch (error: Exception) {
                    writeFailure = BrowserScreenshotWriteFailurePolicy.code(error, writeFailure)
                    null
                } finally {
                    bitmap.recycle()
                }
                if (artifact == null) {
                    finish(request, token, false, callback, writeFailure, screenshotFailureData(writeFailure))
                } else {
                    pendingScreenshotArtifacts[request.requestId] = artifact
                    main.postDelayed({
                        pendingScreenshotArtifacts.remove(request.requestId)?.let(::discardScreenshot)
                    }, (request.deadlineAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                    val complete = { finalIdentity: BrowserVisualIdentity? ->
                        if (!captureScopeCurrent() || !isTokenCurrent(token)) {
                            discardScreenshot(artifact)
                            finish(request, token, false, callback, "IDENTITY_CHANGED",
                                screenshotFailureData("IDENTITY_CHANGED", captureResult.outcome))
                        } else {
                            val screenshotId = if (identityStable && after == finalIdentity && pixelDigest != null) {
                                visualTokens.issue(before!!, finalIdentity!!, pixelDigest)
                            } else null
                            val data = JSONObject().put("artifactPath", "/opt/browser/out/shot/" + artifact.file.name).put("width", width)
                                .put("height", height).put("format", "png").put("bytes", artifact.sizeBytes)
                            if (screenshotId != null) data.put("screenshotId", screenshotId)
                            BrowserScreenshotCaptureContract.successData(data, captureResult.outcome)
                            finish(request, token, true, { raw ->
                                if (runCatching { JSONObject(raw).optString("code") != "OK" }.getOrDefault(true)) {
                                    discardScreenshot(artifact)
                                }
                                callback(raw)
                            }, "OK", data)
                        }
                    }
                    probe { finalMetrics ->
                        complete(finalMetrics?.let { visualIdentity(view, it, width, height) })
                    }
                }
            }
        }
        registration.install(handle)
        }
    }

    private fun screenshotFailureData(
        code: String,
        outcome: BrowserScreenshotOutcome = BrowserScreenshotOutcome(),
    ): JSONObject = BrowserScreenshotCaptureContract.failureData(code, outcome)

    private fun discardScreenshot(artifact: BrowserArtifactFiles.WrittenArtifact) {
        runCatching { BrowserArtifactFiles.removeScreenshot(windowHost.context.filesDir, artifact) }
    }

    private class ScreenshotEncodingException : java.io.IOException("Screenshot PNG encoding failed")

    private class ScreenshotCaptureRegistration {
        private val lock = Any()
        private var handle: BrowserScreenshotCaptureHandle? = null
        private var cancelled = false
        private var completed = false

        fun install(value: BrowserScreenshotCaptureHandle) {
            val cancelNow = synchronized(lock) {
                if (cancelled || completed) true else {
                    handle = value
                    false
                }
            }
            if (cancelNow) value.cancel()
        }

        fun cancelBeforeInstall(): Boolean {
            synchronized(lock) {
                if (completed || cancelled) return false
                cancelled = true
                return true
            }
        }

        fun complete() {
            synchronized(lock) {
                completed = true
                handle = null
            }
        }

        fun cancel(): Boolean {
            val current = synchronized(lock) {
                if (completed || cancelled) return false
                cancelled = true
                handle.also { handle = null }
            }
            return current?.cancel() ?: false
        }
    }

    private fun executeVisualDomClick(
        request: BrowserCommandRequest,
        token: BrowserOperationToken,
        callback: (String) -> Unit,
    ) {
        val view = webView()
        if (view == null) return finish(request, token, false, callback, "CLOSED")
        if (!visualSurfaceAvailable() || !windowHost.hasWindowFocus()) {
            return finish(request, token, false, callback, "UNSUPPORTED")
        }
        withVisualProbe(view) { metrics ->
            val current = metrics?.let { visualIdentity(view, it, view.width, view.height) }
            if (current == null || !visualSurfaceAvailable() || !windowHost.hasWindowFocus()) {
                finish(request, token, false, callback, "UNSUPPORTED")
                return@withVisualProbe
            }
            val screenshotId = request.args.optString("screenshotId")
            val bitmapPoint = BrowserVisualPoint(request.args.optDouble("x"), request.args.optDouble("y"))
            val validation = visualTokens.consume(screenshotId, current, bitmapPoint)
            if (validation.code != BrowserVisualActionCode.OK || validation.webViewPoint == null) {
                finish(request, token, false, callback, validation.code.name)
                return@withVisualProbe
            }
            val point = validation.webViewPoint
            val nonce = newActionNonce()
            if (!actionGate.register(nonce, token) || !visualSurfaceAvailable() || !windowHost.hasWindowFocus()) {
                finish(request, token, false, callback, "AUTHORIZATION_REVOKED")
                return@withVisualProbe
            }
            val script = BrowserVisualScript.click(point, current, nonce)
            view.evaluateJavascript(script) { encoded ->
                val result = decodeJavascriptString(encoded)?.let { runCatching { JSONObject(it) }.getOrNull() }
                val code = result?.optString("code") ?: "UNKNOWN"
                val data = JSONObject().put("screenshotId", screenshotId)
                    .put("bitmapX", bitmapPoint.bitmapX).put("bitmapY", bitmapPoint.bitmapY)
                    .put("cssX", point.x).put("cssY", point.y).put("targetKind", "DOM_HTML")
                if (code == "UNSUPPORTED" && validation.pixelDigest != null) {
                    executeVisualSurfaceClick(request, token, callback, view, current, bitmapPoint, point, validation.pixelDigest, data)
                } else if (code == "UNKNOWN") finishUnknown(request, token, callback, data)
                else finish(request, token, code == "OK", callback, code, data)
            }
        }
    }

    private fun executeVisualSurfaceClick(
        request: BrowserCommandRequest,
        token: BrowserOperationToken,
        callback: (String) -> Unit,
        view: WebView,
        identity: BrowserVisualIdentity,
        bitmapPoint: BrowserVisualPoint,
        cssPoint: BrowserWebViewPoint,
        expectedDigest: String,
        data: JSONObject,
    ) {
        fun authorized() = isTokenCurrent(token) && view === webView() && visualSurfaceAvailable() &&
            windowHost.hasWindowFocus() && documentGeneration == identity.documentGeneration &&
            activePageId() == identity.pageId && view.width == identity.webViewWidth && view.height == identity.webViewHeight &&
            view.scrollX == identity.scrollX && view.scrollY == identity.scrollY &&
            kotlin.math.abs(view.scale - identity.scale) < 0.001f && windowHost.displayRotationDegrees == identity.rotation
        if (!authorized()) return finish(request, token, false, callback, "AUTHORIZATION_REVOKED", data)
        capture(token.deadlineAtMillis) { bitmap, code ->
            if (bitmap == null) return@capture finish(request, token, false, callback, code, data)
            val samePixels = try { code == "OK" && BrowserVisualPixelDigest.fromBitmap(bitmap) == expectedDigest }
                finally { bitmap.recycle() }
            if (!samePixels || !authorized()) {
                finish(request, token, false, callback, "STALE_SCREENSHOT", data)
                return@capture
            }
            withVisualProbe(view) { metrics ->
                val after = metrics?.let { visualIdentity(view, it, view.width, view.height) }
                if (after != identity || !authorized()) {
                    finish(request, token, false, callback, "STALE_SCREENSHOT", data)
                    return@withVisualProbe
                }
                val nonce = newActionNonce()
                if (!actionGate.register(nonce, token)) {
                    finish(request, token, false, callback, "AUTHORIZATION_REVOKED", data)
                    return@withVisualProbe
                }
                view.evaluateJavascript(BrowserVisualScript.click(cssPoint, identity, nonce, nativeSurface = true)) { encoded ->
                    val prepared = decodeJavascriptString(encoded)?.let { runCatching { JSONObject(it).optString("code") }.getOrNull() }
                    if (prepared != "NATIVE_READY") {
                        // Preparation has no native input side effect; a missing reply cannot justify a tap.
                        finish(request, token, false, callback,
                            prepared?.takeIf { it in setOf("UNSUPPORTED", "STALE_SCREENSHOT", "AUTHORIZATION_REVOKED") } ?: "UNSUPPORTED", data)
                    } else {
                        data.put("targetKind", "NATIVE_MEDIA").put("pixelMatchBeforeDispatch", true).put("atomicSnapshot", false)
                        capture(token.deadlineAtMillis) finalCapture@ { finalFrame, finalCode ->
                            val matches = finalFrame?.let {
                                try { finalCode == "OK" && BrowserVisualPixelDigest.fromBitmap(it) == expectedDigest }
                                finally { it.recycle() }
                            } == true
                            if (!matches || !authorized()) {
                                finish(request, token, false, callback, "STALE_SCREENSHOT", data)
                                return@finalCapture
                            }
                            withVisualProbe(view) { finalMetrics ->
                                if (finalMetrics?.let { visualIdentity(view, it, view.width, view.height) } != identity || !authorized()) {
                                    finish(request, token, false, callback, "STALE_SCREENSHOT", data)
                                } else {
                                    val dispatched = BrowserVisualSurfaceTap.dispatch(view, bitmapPoint.bitmapX, bitmapPoint.bitmapY, ::authorized)
                                    if (dispatched == "UNKNOWN") finishUnknown(request, token, callback, data)
                                    else finish(request, token, dispatched == "OK", callback, dispatched, data)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun withVisualProbe(view: WebView, callback: (JSONObject?) -> Unit) {
        if (view !== webView() || !view.isAttachedToWindow ||
            !runCatching { BrowserVisualScript.install(view) }.getOrDefault(false)
        ) {
            callback(null)
            return
        }
        view.evaluateJavascript(BrowserVisualScript.probe()) { encoded ->
            callback(encoded?.let { runCatching { JSONObject(it) }.getOrNull() })
        }
    }

    private fun visualIdentity(view: WebView, metrics: JSONObject, bitmapWidth: Int, bitmapHeight: Int): BrowserVisualIdentity? {
        val snapshot = lifecycle().snapshot()
        val page = pageState() ?: return null
        val pageId = activePageId() ?: return null
        if (!snapshot.visible || snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.owner != BrowserOwner.AI ||
            !automationEnabled() || !visualSurfaceAvailable() || !windowHost.hasWindowFocus() || view !== webView() ||
            !view.isAttachedToWindow || !view.isShown || view.width <= 0 || view.height <= 0 ||
            bitmapWidth <= 0 || bitmapHeight <= 0 || view.paddingLeft != 0 || view.paddingTop != 0 ||
            view.paddingRight != 0 || view.paddingBottom != 0
        ) return null
        val identity = runCatching {
            val animationsActive = metrics.opt("animationsActive") as? Boolean ?: return null
            BrowserVisualIdentity(
                pageId = pageId, documentGeneration = documentGeneration, documentToken = metrics.getString("documentToken"),
                navigationId = page.navigationId,
                sessionGeneration = snapshot.sessionGeneration, controlGeneration = snapshot.controlGeneration,
                viewInstanceGeneration = visualViewGeneration(view),
                bitmapWidth = bitmapWidth, bitmapHeight = bitmapHeight, webViewWidth = view.width, webViewHeight = view.height,
                scrollX = view.scrollX, scrollY = view.scrollY, scale = view.scale,
                density = windowHost.displayDensity,
                rotation = windowHost.displayRotationDegrees,
                viewportGeneration = 0,
                mutationGeneration = metrics.getLong("mutationGeneration"),
                visualViewportWidth = metrics.getDouble("visualViewportWidth"),
                visualViewportHeight = metrics.getDouble("visualViewportHeight"),
                visualViewportOffsetX = metrics.getDouble("visualViewportOffsetX"),
                visualViewportOffsetY = metrics.getDouble("visualViewportOffsetY"),
                devicePixelRatio = metrics.getDouble("devicePixelRatio"),
                visualViewportScale = metrics.getDouble("visualViewportScale"),
                animationsActive = animationsActive,
            )
        }.getOrNull() ?: return null
        return identity.takeIf { it.isValid() }
    }

    @Synchronized
    private fun visualViewGeneration(view: WebView): Long = visualViewGenerations.getOrPut(view) {
        visualViewGenerationSequence = if (visualViewGenerationSequence == Long.MAX_VALUE) 1L else visualViewGenerationSequence + 1L
        visualViewGenerationSequence
    }

    private fun finish(
        request: BrowserCommandRequest,
        token: BrowserOperationToken,
        effective: Boolean,
        callback: (String) -> Unit,
        code: String,
        data: JSONObject = JSONObject(),
    ) {
        val completed = lifecycle().completeOperation(token, effective)
        val current = lifecycle().snapshot()
        publishSnapshot(current)
        if (!completed && request.method == BrowserCommandMethod.SCREENSHOT) {
            val failure = if (SystemClock.elapsedRealtime() >= token.deadlineAtMillis) "DEADLINE_EXPIRED" else "IDENTITY_CHANGED"
            callback(response(request, failure, false, current, screenshotFailureData(failure)).toString())
            if (current.lifecycle == BrowserLifecycle.CLOSING) closeSession()
            return
        }
        callback(response(request, if (completed) code else "UNKNOWN", if (completed) effective else null, current, data).toString())
        if (current.lifecycle == BrowserLifecycle.CLOSING) closeSession()
    }

    private fun finishUnknown(
        request: BrowserCommandRequest,
        token: BrowserOperationToken,
        callback: (String) -> Unit,
        data: JSONObject = JSONObject(),
    ) {
        lifecycle().completeOperation(token, effective = false)
        val current = lifecycle().snapshot()
        publishSnapshot(current)
        callback(response(request, "UNKNOWN", null, current, data).toString())
        if (current.lifecycle == BrowserLifecycle.CLOSING) closeSession()
    }

    private fun startThumbnailAfterAcceptedNavigation(
        request: BrowserCommandRequest,
        token: BrowserOperationToken,
        view: WebView,
        baselinePageId: String?,
        baselineNavigationId: Long?,
        baselineDocumentGeneration: Long,
        expectedNavigationId: Long,
        expectedRequestedUrl: String?,
        data: JSONObject,
        callback: (String) -> Unit,
    ) {
        val completed = lifecycle().completeOperation(token, effective = true)
        val navigationSnapshot = lifecycle().snapshot()
        publishSnapshot(navigationSnapshot)
        val navigationData = JSONObject(data.toString())
        if (!completed) {
            callback(response(request, "UNKNOWN", null, navigationSnapshot, navigationData).toString())
            if (navigationSnapshot.lifecycle == BrowserLifecycle.CLOSING) closeSession()
            return
        }
        waitForNavigationThumbnail(
            request = request,
            view = view,
            baselinePageId = baselinePageId,
            baselineNavigationId = baselineNavigationId,
            baselineDocumentGeneration = baselineDocumentGeneration,
            expectedNavigationId = expectedNavigationId,
            expectedRequestedUrl = expectedRequestedUrl,
            navigationSnapshot = navigationSnapshot,
            navigationData = navigationData,
            callback = callback,
        )
    }

    private fun waitForNavigationThumbnail(
        request: BrowserCommandRequest,
        view: WebView,
        baselinePageId: String?,
        baselineNavigationId: Long?,
        baselineDocumentGeneration: Long,
        expectedNavigationId: Long,
        expectedRequestedUrl: String?,
        navigationSnapshot: BrowserSnapshot,
        navigationData: JSONObject,
        callback: (String) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val budget = BrowserNavigationThumbnailPolicy.budget(startedAt, request.deadlineAtElapsedMillis)
        if (budget == null) {
            navigationData.put("thumbnail", BrowserNavigationThumbnailPolicy.failureData("TIMEOUT"))
            callback(response(request, "OK", true, navigationSnapshot, navigationData).toString())
            return
        }

        val settled = AtomicBoolean(false)
        var targetPageId: String? = null
        var targetDocumentGeneration = -1L
        var targetNavigationId = -1L
        var cancellation: (() -> Unit)? = null
        var pollTask: Runnable? = null

        fun lifecycleGuard(): String? {
            val current = lifecycle().snapshot()
            return if (current.lifecycle != BrowserLifecycle.ACTIVE ||
                current.owner != BrowserOwner.AI ||
                current.sessionGeneration != request.sessionGeneration ||
                current.controlGeneration != request.controlGeneration ||
                !automationEnabled()
            ) {
                "CANCELLED"
            } else {
                null
            }
        }

        fun identityGuard(requireReady: Boolean): String? {
            lifecycleGuard()?.let { return it }
            val expectedPageId = if (requireReady) targetPageId else baselinePageId
            val state = pageState()
            if (view !== webView() || activePageId() != expectedPageId || state == null) {
                return "IDENTITY_CHANGED"
            }
            if (requireReady && (targetDocumentGeneration < 0 ||
                    documentGeneration != targetDocumentGeneration ||
                    state.navigationId != targetNavigationId ||
                    !state.committed ||
                    state.loadState != BrowserPageStateTracker.LoadState.FINISHED)
            ) {
                return "IDENTITY_CHANGED"
            }
            return null
        }

        fun completeAfterSettled(code: String, thumbnail: JSONObject) {
            cancellation?.let { thumbnailCancels.remove(request.requestId, it) }
            val finalData = JSONObject(navigationData.toString()).put("thumbnail", thumbnail)
            callback(response(request, "OK", true, navigationSnapshot, finalData).toString())
        }

        fun completeOnce(code: String, thumbnail: JSONObject): Boolean {
            if (!settled.compareAndSet(false, true)) return false
            pollTask?.let(main::removeCallbacks)
            completeAfterSettled(code, thumbnail)
            return true
        }

        fun captureReady() {
            if (settled.get()) return
            identityGuard(requireReady = true)?.let { code ->
                completeOnce(code, BrowserNavigationThumbnailPolicy.failureData(code))
                return
            }
            val readyPageId = targetPageId
            val readyDocumentGeneration = targetDocumentGeneration
            val readyNavigationId = targetNavigationId
            capture(budget.captureUntilElapsedMillis) { bitmap, captureCode ->
                if (settled.get()) {
                    bitmap?.recycle()
                    return@capture
                }
                identityGuard(requireReady = true)?.let { code ->
                    bitmap?.recycle()
                    completeOnce(code, BrowserNavigationThumbnailPolicy.failureData(code))
                    return@capture
                }
                if (bitmap == null) {
                    completeOnce(
                        BrowserNavigationThumbnailPolicy.normalizeCode(captureCode),
                        BrowserNavigationThumbnailPolicy.failureData(captureCode),
                    )
                    return@capture
                }
                if (readyPageId == null) {
                    bitmap.recycle()
                    completeOnce("IDENTITY_CHANGED", BrowserNavigationThumbnailPolicy.failureData("IDENTITY_CHANGED"))
                    return@capture
                }

                var scaled: Bitmap? = null
                var thumbnailWidth = 0
                var thumbnailHeight = 0
                var writeCode = "STORAGE_FAILED"
                val artifact = try {
                    val targetSize = BrowserNavigationThumbnailPolicy.scaledSize(bitmap.width, bitmap.height)
                    thumbnailWidth = targetSize.first
                    thumbnailHeight = targetSize.second
                    scaled = if (bitmap.width == targetSize.first && bitmap.height == targetSize.second) {
                        bitmap
                    } else {
                        Bitmap.createScaledBitmap(bitmap, targetSize.first, targetSize.second, true)
                            .also { if (it !== bitmap) bitmap.recycle() }
                    }
                    writeScreenshot(
                        request.requestId,
                        scaled!!,
                        maxBytes = BrowserNavigationThumbnailPolicy.MAX_BYTES,
                    )
                } catch (_: ScreenshotEncodingException) {
                    writeCode = "ENCODE_FAILED"
                    null
                } catch (error: Exception) {
                    writeCode = BrowserScreenshotWriteFailurePolicy.code(error, writeCode)
                    null
                } finally {
                    scaled?.takeIf { !it.isRecycled && it !== bitmap }?.recycle()
                    if (!bitmap.isRecycled) bitmap.recycle()
                }

                if (settled.get()) {
                    artifact?.let(::discardScreenshot)
                    return@capture
                }
                identityGuard(requireReady = true)?.let { code ->
                    artifact?.let(::discardScreenshot)
                    completeOnce(code, BrowserNavigationThumbnailPolicy.failureData(code))
                    return@capture
                }
                if (artifact == null) {
                    completeOnce(writeCode, BrowserNavigationThumbnailPolicy.failureData(writeCode))
                    return@capture
                }
                val thumbnail = BrowserNavigationThumbnailPolicy.successData(
                    artifactPath = "/opt/browser/out/shot/" + artifact.file.name,
                    width = thumbnailWidth,
                    height = thumbnailHeight,
                    bytes = artifact.sizeBytes,
                    pageId = readyPageId,
                    documentGeneration = readyDocumentGeneration,
                    navigationId = readyNavigationId,
                )
                completeOnce("OK", thumbnail)
            }
        }

        fun checkReady() {
            if (settled.get()) return
            lifecycleGuard()?.let { code ->
                completeOnce(code, BrowserNavigationThumbnailPolicy.failureData(code))
                return
            }
            if (view !== webView() || activePageId() != baselinePageId || pageState() == null) {
                completeOnce("IDENTITY_CHANGED", BrowserNavigationThumbnailPolicy.failureData("IDENTITY_CHANGED"))
                return
            }
            val now = SystemClock.elapsedRealtime()
            val state = pageState()
            when (BrowserNavigationThumbnailPolicy.readiness(
                budget = budget,
                nowElapsedMillis = now,
                baselineNavigationId = baselineNavigationId,
                baselineDocumentGeneration = baselineDocumentGeneration,
                expectedNavigationId = expectedNavigationId,
                expectedRequestedUrl = expectedRequestedUrl,
                currentDocumentGeneration = documentGeneration,
                state = state,
            )) {
                BrowserNavigationThumbnailReadiness.READY -> {
                    targetPageId = activePageId()
                    targetDocumentGeneration = documentGeneration
                    targetNavigationId = state!!.navigationId
                    captureReady()
                }
                BrowserNavigationThumbnailReadiness.IDENTITY_CHANGED ->
                    completeOnce("IDENTITY_CHANGED", BrowserNavigationThumbnailPolicy.failureData("IDENTITY_CHANGED"))
                BrowserNavigationThumbnailReadiness.NAVIGATION_FAILED ->
                    completeOnce("NAVIGATION_FAILED", BrowserNavigationThumbnailPolicy.failureData("NAVIGATION_FAILED"))
                BrowserNavigationThumbnailReadiness.TIMEOUT ->
                    completeOnce("TIMEOUT", BrowserNavigationThumbnailPolicy.failureData("TIMEOUT"))
                BrowserNavigationThumbnailReadiness.WAIT -> {
                    val remaining = (budget.waitUntilElapsedMillis - now).coerceAtLeast(1L)
                    val next = Runnable { checkReady() }
                    pollTask = next
                    main.postDelayed(next, minOf(BrowserNavigationThumbnailPolicy.POLL_MILLIS, remaining))
                }
            }
        }

        val cancelWait: () -> Unit = {
            if (settled.compareAndSet(false, true)) {
                pollTask?.let(main::removeCallbacks)
                main.post {
                    completeAfterSettled("CANCELLED", BrowserNavigationThumbnailPolicy.failureData("CANCELLED"))
                }
            }
        }
        cancellation = cancelWait
        thumbnailCancels[request.requestId] = cancelWait
        checkReady()
    }

    private fun cancelThumbnailWait(requestId: String): Boolean {
        val cancel = thumbnailCancels.remove(requestId) ?: return false
        cancel()
        return true
    }

    private fun cancelThumbnailWaits() {
        thumbnailCancels.keys.toList().forEach { requestId ->
            thumbnailCancels.remove(requestId)?.invoke()
        }
    }

    private fun beginAiOperation(request: BrowserCommandRequest): BrowserOperationToken? {
        preAdmittedTokens[request.requestId]?.let { return it }
        val session = request.sessionGeneration ?: return null
        val control = request.controlGeneration ?: return null
        if (request.owner != "ai") return null
        val token = lifecycle().beginOperation(
            "ai-" + request.requestId,
            CommandContext(session, control, BrowserOwner.AI),
            request.deadlineAtElapsedMillis,
        ) ?: return null
        publishSnapshot(lifecycle().snapshot())
        return token
    }

    private fun newActionNonce(): String = UUID.randomUUID().toString().replace("-", "")

    private fun expireIfNeeded(): BrowserSnapshot {
        val snapshot = lifecycle().expireIfNeeded()
        if (snapshot.lifecycle == BrowserLifecycle.CLOSING) {
            publishSnapshot(snapshot)
            closeSession()
        }
        return snapshot
    }

    private fun response(
        request: BrowserCommandRequest,
        code: String,
        executed: Boolean?,
        snapshot: BrowserSnapshot = lifecycle().snapshot(),
        data: JSONObject = JSONObject(),
    ): JSONObject = commandResponse(
        requestId = request.requestId,
        code = code,
        executed = executed,
        state = snapshot.stateName(),
        sessionGeneration = snapshot.sessionGeneration,
        controlGeneration = snapshot.controlGeneration,
        owner = snapshot.owner.name.lowercase(Locale.ROOT),
        data = JSONObject(data.toString()).also { details ->
            BrowserTaskErrorGuidance.forCode(code)?.let { advice ->
                details.put("stage", advice.stage).put("reason", advice.reason)
                    .put("nextAction", advice.nextAction).put("automaticRetryAllowed", false)
            }
        },
    )

    private fun writeScreenshot(
        requestId: String,
        bitmap: Bitmap,
        maxBytes: Long = BrowserArtifactFiles.SCREENSHOT_MAX_BYTES,
    ): BrowserArtifactFiles.WrittenArtifact {
        BrowserArtifactFiles.removeExpired(windowHost.context.filesDir)
        val safeId = requestId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(72)
        val artifact = BrowserArtifactFiles.writeScreenshotAtomically(
            windowHost.context.filesDir,
            requestedName = "shot-$safeId-${SystemClock.elapsedRealtime()}-${java.util.UUID.randomUUID().toString().take(8)}.png",
            maxBytes = maxBytes,
        ) { output -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw ScreenshotEncodingException() }
        return artifact
    }

    private fun locate(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) { callback(response(request, "CLOSED", false, snapshot).toString()); return }
        if (!automationEnabled()) { callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString()); return }
        if (snapshot.owner != BrowserOwner.AI) { callback(response(request, "USER_CONTROLLED", false, snapshot).toString()); return }
        if (request.sessionGeneration != snapshot.sessionGeneration || request.controlGeneration != snapshot.controlGeneration) {
            callback(response(request, "STALE_SESSION", false, snapshot).toString()); return
        }
        if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) { callback(response(request, "DEADLINE_EXPIRED", false, snapshot).toString()); return }
        val view = webView() ?: run { callback(response(request, "CLOSED", false, snapshot).toString()); return }
        val scopeRef = request.args.optString("scopeRef").takeIf { request.args.has("scopeRef") }
        val scope = scopeRef?.let { parseObservationRef(it, snapshot) }
        if (scopeRef != null && scope == null) { callback(response(request, "STALE_REFERENCE", false, snapshot).toString()); return }
        val startPage = pageState()
        if (startPage == null || startPage.loadState != BrowserPageStateTracker.LoadState.FINISHED || startPage.readyState != "complete") {
            callback(response(request, "PAGE_NOT_READY", false, snapshot).toString()); return
        }
        val token = beginAiOperation(request)
        if (token == null) { callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString()); return }
        val startDocument = documentGeneration
        val startPageId = activePageId()
        val nonce = newActionNonce()
        val startObservation = observationGeneration
        val startNonce = referenceNonce
        val observation = observationGeneration + 1
        view.evaluateJavascript(BrowserDomScript.locate(
            nonce = nonce,
            previousNonce = referenceNonce,
            strategy = request.args.getString("strategy"),
            value = request.args.getString("value"),
            exact = request.args.getBoolean("exact"),
            name = request.args.optString("name").takeIf { request.args.has("name") },
            scopeNonce = scope?.first,
            scopeIndex = scope?.second,
            partial = request.args.optBoolean("partial", false),
        )) { encoded ->
            val result = decodeJavascriptString(encoded)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val current = lifecycle().snapshot()
            if (!isTokenCurrent(token) || SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis ||
                documentGeneration != startDocument || current.sessionGeneration != snapshot.sessionGeneration ||
                current.controlGeneration != snapshot.controlGeneration || current.owner != BrowserOwner.AI ||
                observationGeneration != startObservation || referenceNonce != startNonce ||
                webView() !== view || activePageId() != startPageId || pageState()?.navigationId != startPage.navigationId
            ) {
                finish(request, token, false, callback, "STALE_REFERENCE"); return@evaluateJavascript
            }
            val code = result?.optString("code") ?: "EXECUTION_FAILED"
            val count = result?.optInt("count", 0) ?: 0
            val hasMetadata = result?.has("scopeCoverage") == true || result?.has("frameScopes") == true
            val validMetadata = result != null && BrowserDomScopeMetadata.valid(result)
            val partialRequested = request.args.optBoolean("partial", false)
            val partialResult = result?.optBoolean("partial", false) == true
            val coverageComplete = validMetadata &&
                result!!.getJSONObject("scopeCoverage").getBoolean("complete")
            val partialCode = code in setOf("OK", "NOT_FOUND", "AMBIGUOUS", "LOCATE_LIMIT")
            val successBudgetClear = validMetadata && run {
                val coverage = result!!.getJSONObject("scopeCoverage")
                !coverage.getBoolean("textLimitReached") &&
                    !coverage.getBoolean("frameLimitReached") &&
                    !coverage.getBoolean("shadowLimitReached") &&
                    !coverage.getBoolean("elementLimitReached")
            }
            if ((hasMetadata && !validMetadata) ||
                (partialResult && (!partialRequested || !validMetadata || coverageComplete)) ||
                (partialCode && !coverageComplete && hasMetadata && (!partialRequested || !partialResult)) ||
                (code == "OK" && (!validMetadata ||
                    (!coverageComplete && (!partialRequested || !partialResult || !successBudgetClear)) ||
                    !BrowserDomScopeMetadata.targetValid(result, result.getJSONArray("frameScopes")))) ||
                (code == "INCOMPLETE_SCOPE" && (!validMetadata || result!!.getJSONObject("scopeCoverage").getBoolean("complete")))
            ) {
                finish(request, token, false, callback, "EXECUTION_FAILED"); return@evaluateJavascript
            }
            if (code == "OK" && count == 1) {
                observationGeneration = observation
                latestObservation = observation
                referenceNonce = nonce
                referencePageId = startPageId
                referenceView = view
                val ref = observationRef(current, observation, nonce, 0)
                val data = JSONObject().put("ref", ref).put("count", 1).put("strategy", request.args.getString("strategy"))
                    .put("pageId", activePageId() ?: JSONObject.NULL).put("documentGeneration", documentGeneration)
                    .put("observationId", observation)
                    .put("frameOrdinal", result!!.getInt("frameOrdinal"))
                    .put("shadowDepth", result.getInt("shadowDepth"))
                if (partialResult) data.put("partial", true)
                BrowserDomScopeMetadata.copy(result, data)
                finish(request, token, true, callback, "OK", data)
            } else {
                val data = JSONObject().put("count", count)
                result?.optJSONArray("matches")?.let { data.put("matches", it) }
                if (validMetadata) BrowserDomScopeMetadata.copy(result!!, data)
                if (partialResult) data.put("partial", true)
                finish(request, token, false, callback, when (code) {
                    "NOT_FOUND", "AMBIGUOUS", "LOCATE_LIMIT", "INCOMPLETE_SCOPE", "INVALID_SELECTOR", "STALE_REFERENCE" -> code
                    else -> "EXECUTION_FAILED"
                }, data)
            }
        }
    }

    private fun collect(request: BrowserCommandRequest, callback: (String) -> Unit) {
        BrowserCollectCommand(
            expireIfNeeded = ::expireIfNeeded,
            lifecycle = lifecycle,
            automationEnabled = automationEnabled,
            webView = webView,
            activePageId = activePageId,
            pageState = pageState,
            documentGeneration = { documentGeneration },
            previousNonce = { referenceNonce },
            beginObservation = { nonce, pageId, view, docGeneration ->
                val snapshot = expireIfNeeded()
                val currentView = webView()
                val currentPage = pageState()
                if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || snapshot.owner != BrowserOwner.AI ||
                    !automationEnabled() || request.owner != "ai" ||
                    request.sessionGeneration != snapshot.sessionGeneration ||
                    request.controlGeneration != snapshot.controlGeneration || currentView !== view ||
                    activePageId() != pageId || documentGeneration != docGeneration ||
                    currentPage?.loadState != BrowserPageStateTracker.LoadState.FINISHED ||
                    currentPage.readyState != "complete"
                ) null else {
                    val observation = ++observationGeneration
                    latestObservation = observation
                    referenceNonce = nonce
                    referencePageId = pageId
                    referenceView = view
                    observation
                }
            },
            isObservationCurrent = { initial, pageId, view, docGeneration, navigationId, nonce, observationId ->
                val current = lifecycle().snapshot()
                val page = pageState()
                current.lifecycle == BrowserLifecycle.ACTIVE && current.owner == BrowserOwner.AI &&
                    automationEnabled() && initial.lifecycle == BrowserLifecycle.ACTIVE && initial.owner == BrowserOwner.AI &&
                    initial.sessionGeneration == request.sessionGeneration &&
                    initial.controlGeneration == request.controlGeneration &&
                    current.sessionGeneration == initial.sessionGeneration &&
                    current.controlGeneration == initial.controlGeneration &&
                    webView() === view && activePageId() == pageId && documentGeneration == docGeneration &&
                    page?.navigationId == navigationId && page.loadState == BrowserPageStateTracker.LoadState.FINISHED &&
                    page.readyState == "complete" && referenceNonce == nonce && latestObservation == observationId &&
                    referencePageId == pageId && referenceView === view
            },
            observationRef = ::observationRef,
            buildScript = { args, nonce, oldNonce -> BrowserDomScript.collectWindow(args, nonce, oldNonce) },
            buildResponse = { req, code, executed, snapshot, data -> response(req, code, executed, snapshot, data) },
            callback = callback,
        ).execute(request)
    }

    fun cancelWait(requestId: String, code: String): Boolean =
        waitCoordinator.terminate(requestId, BrowserWaitCoordinator.Result(code, false))

    private fun wait(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        val view = webView()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || view == null) {
            callback(response(request, "CLOSED", false, snapshot).toString()); return
        }
        if (!automationEnabled()) { callback(response(request, "AUTOMATION_DISABLED", false, snapshot).toString()); return }
        if (snapshot.sessionGeneration != request.sessionGeneration) { callback(response(request, "STALE_SESSION", false, snapshot).toString()); return }
        val startControl = snapshot.controlGeneration
        val startPage = pageState()
        val startDocument = documentGeneration
        val condition = request.args.getString("condition")
        val timeout = request.args.optLong("timeoutMs", 10_000L)
        val deadline = minOf(request.deadlineAtElapsedMillis, SystemClock.elapsedRealtime() + timeout)
        val interval = request.args.optLong("pollIntervalMs", 200L)
        val startPageId = activePageId()
        val valid = {
            val current = lifecycle().snapshot()
            automationEnabled() && current.lifecycle == BrowserLifecycle.ACTIVE &&
                current.sessionGeneration == request.sessionGeneration && current.controlGeneration == startControl &&
                webView() === view && activePageId() == startPageId && current.owner == snapshot.owner
        }
        val accepted = waitCoordinator.start(
            id = request.requestId,
            deadlineAtMillis = deadline,
            intervalMillis = interval,
            stillValid = valid,
            probe = { done -> probeWait(view, condition, request.args, startPage?.navigationId, startPageId, done) },
        ) { result ->
            val current = lifecycle().snapshot()
            val samePage = webView() === view && activePageId() == startPageId
            val currentPage = if (samePage) pageState() else startPage
            val safe = BrowserPageStateTracker.publicUrl(
                if (samePage) view.url ?: currentPage?.currentUrl ?: currentPage?.requestedUrl
                else currentPage?.currentUrl ?: currentPage?.requestedUrl,
            )
            val data = JSONObject().put("matched", result.matched == true).put("condition", condition)
                .put("pageId", startPageId ?: JSONObject.NULL).put("navigationId", currentPage?.navigationId ?: JSONObject.NULL)
                .put("documentGeneration", if (samePage) documentGeneration else startDocument).put("url", safe.value ?: JSONObject.NULL)
                .put("loadState", currentPage?.loadState?.name ?: "unknown")
                .put("waitScope", BrowserWaitScopeDiagnostics.data(condition))
            result.reason?.let { data.put("reason", it) }
            if (result.code == "CANCELLED" || result.code == "SESSION_REVOKED") {
                data.put("stage", "condition_wait")
                    .put("cancellationReason", if (result.code == "CANCELLED") "user_cancelled" else "context_changed")
                    .put("nextAction", "read_status_and_observe").put("automaticRetryAllowed", false)
            }
            BrowserNavigationMetadata.addTo(data, currentPage)
            val code = when (result.code) {
                "OK", "WAIT_TIMEOUT", "CANCELLED", "SESSION_REVOKED" -> result.code
                "INVALID_SELECTOR", "NAVIGATION_FAILED", "NAVIGATION_CHANGED", "WAIT_PROBE_LIMIT" -> result.code
                else -> "UNKNOWN"
            }
            callback(response(request, code, result.matched, current, data).toString())
        }
        if (!accepted) callback(response(request, "BUSY", false, snapshot).toString())
    }

    private fun probeWait(
        view: WebView,
        condition: String,
        args: JSONObject,
        startingNavigationId: Long?,
        startPageId: String?,
        done: (Boolean?, String?) -> Unit,
    ) {
        if (webView() !== view) { done(false, "SESSION_REVOKED"); return }
        val page = pageState()
        when (condition) {
            "load" -> {
                if (page == null || page.navigationId != startingNavigationId) done(false, "NAVIGATION_CHANGED")
                else when (page.loadState) {
                    BrowserPageStateTracker.LoadState.FINISHED -> done(true, null)
                    BrowserPageStateTracker.LoadState.FAILED -> done(false, "NAVIGATION_FAILED")
                    else -> done(false, null)
                }
            }
            "url", "text", "selector" -> {
                val probePage = page?.navigationId
                val probeDocument = documentGeneration
                val conditionScript = BrowserWaitScript.condition(condition, args)
                view.evaluateJavascript(conditionScript) { encoded ->
                    if (webView() !== view) { done(false, "SESSION_REVOKED"); return@evaluateJavascript }
                    if (activePageId() != startPageId) { done(false, "SESSION_REVOKED"); return@evaluateJavascript }
                    if (pageState()?.navigationId != probePage || documentGeneration != probeDocument) {
                        done(false, null); return@evaluateJavascript
                    }
                    when (encoded) {
                        "true" -> done(true, null)
                        "false" -> done(false, null)
                        "\"INVALID_SELECTOR\"" -> done(false, "INVALID_SELECTOR")
                        "\"WAIT_PROBE_LIMIT\"" -> done(false, "WAIT_PROBE_LIMIT")
                        else -> done(null, "UNKNOWN")
                    }
                }
            }
        }
    }

    private fun navigate(request: BrowserCommandRequest, callback: (String) -> Unit) {
        val snapshot = expireIfNeeded()
        if (snapshot.lifecycle != BrowserLifecycle.ACTIVE || !automationEnabled()) {
            callback(response(request, if (snapshot.lifecycle != BrowserLifecycle.ACTIVE) "CLOSED" else "AUTOMATION_DISABLED", false, snapshot).toString()); return
        }
        if (snapshot.owner != BrowserOwner.AI || snapshot.sessionGeneration != request.sessionGeneration || snapshot.controlGeneration != request.controlGeneration) {
            callback(response(request, if (snapshot.owner != BrowserOwner.AI) "USER_CONTROLLED" else "STALE_SESSION", false, snapshot).toString()); return
        }
        val view = webView() ?: run { callback(response(request, "CLOSED", false, snapshot).toString()); return }
        val action = request.args.getString("action")
        if (action == "back" && !view.canGoBackOrForward(-1)) {
            navigateSameDocumentBack(request, callback, view, snapshot)
            return
        }
        if (action == "forward" && !view.canGoBackOrForward(1)) {
            val data = JSONObject().put("action", action).put("accepted", false)
                .put("canGoBack", view.canGoBackOrForward(-1)).put("canGoForward", view.canGoBackOrForward(1))
            callback(response(request, "NO_HISTORY", false, snapshot, data).toString()); return
        }
        if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) { callback(response(request, "DEADLINE_EXPIRED", false, snapshot).toString()); return }
        val operation = beginAiOperation(request)
        if (operation == null) { callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString()); return }
        val baselinePageId = activePageId()
        val baselineState = pageState()
        val baselineDocumentGeneration = documentGeneration
        try {
            if (!performNavigation(action)) {
                finish(request, operation, false, callback, "NAVIGATION_FAILED")
                return
            }
            val currentPage = pageState()
            val safe = BrowserPageStateTracker.publicUrl(currentPage?.currentUrl ?: currentPage?.requestedUrl)
            val data = JSONObject().put("action", action).put("accepted", true).put("pageId", activePageId() ?: JSONObject.NULL)
                .put("navigationId", currentPage?.navigationId ?: JSONObject.NULL).put("url", safe.value ?: JSONObject.NULL)
            BrowserNavigationMetadata.addTo(data, currentPage)
            val late = SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis
            if (request.args.optBoolean("thumbnail", false) && !late) {
                startThumbnailAfterAcceptedNavigation(
                    request = request,
                    token = operation,
                    view = view,
                    baselinePageId = baselinePageId,
                    baselineNavigationId = baselineState?.navigationId,
                    baselineDocumentGeneration = baselineDocumentGeneration,
                    expectedNavigationId = currentPage?.navigationId ?: -1L,
                    expectedRequestedUrl = currentPage?.requestedUrl,
                    data = data,
                    callback = callback,
                )
                return
            }
            val completed = lifecycle().completeOperation(operation, true)
            val current = lifecycle().snapshot()
            publishSnapshot(current)
            callback(response(request, if (late || !completed) "UNKNOWN" else "OK",
                if (late || !completed) null else true, current, data).toString())
        } catch (_: RuntimeException) {
            lifecycle().completeOperation(operation, effective = false)
            val current = lifecycle().snapshot()
            publishSnapshot(current)
            callback(response(request, "UNKNOWN", null, current).toString())
        }
    }

    private fun navigateSameDocumentBack(
        request: BrowserCommandRequest,
        callback: (String) -> Unit,
        view: WebView,
        initialSnapshot: BrowserSnapshot,
    ) {
        val startPageId = activePageId()
        val baselineState = pageState()
        val baselineDocumentGeneration = documentGeneration
        val urlBefore = view.url.orEmpty()
        if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
            callback(response(request, "DEADLINE_EXPIRED", false, initialSnapshot).toString())
            return
        }
        view.evaluateJavascript(BrowserSameDocumentHistoryScript.target) { encoded ->
            val current = lifecycle().snapshot()
            if (webView() !== view || activePageId() != startPageId ||
                current.lifecycle != BrowserLifecycle.ACTIVE || current.owner != BrowserOwner.AI ||
                current.sessionGeneration != request.sessionGeneration || current.controlGeneration != request.controlGeneration ||
                !automationEnabled()
            ) {
                callback(response(request, "SESSION_REVOKED", false, current).toString())
                return@evaluateJavascript
            }
            val result = runCatching { JSONObject(encoded?.let(::decodeJavascriptString) ?: "null") }.getOrNull()
            if (result?.optString("state") != "target") {
                val known = result?.optBoolean("known") == true
                val code = if (known && result?.optString("state") == "no_history") "NO_HISTORY" else "UNKNOWN"
                val data = JSONObject().put("action", "back").put("accepted", false)
                    .put("sameDocumentHistoryKnown", known)
                callback(response(request, code, if (code == "UNKNOWN") null else false, current, data).toString())
                return@evaluateJavascript
            }
            val expectedUrl = result.optString("url")
            val token = UUID.randomUUID().toString().replace("-", "")
            if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                callback(response(request, "DEADLINE_EXPIRED", false, current).toString())
                return@evaluateJavascript
            }
            val operation = beginAiOperation(request)
            if (operation == null) {
                callback(response(request, "BUSY_OR_STALE", false, lifecycle().snapshot()).toString())
                return@evaluateJavascript
            }
            val authorizationNonce = newActionNonce()
            if (!actionGate.register(authorizationNonce, operation) ||
                !isTokenCurrent(operation) || SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis
            ) {
                finishUnknown(request, operation, callback,
                    JSONObject().put("action", "back").put("accepted", false))
                return@evaluateJavascript
            }
            view.evaluateJavascript(BrowserSameDocumentHistoryScript.dispatchBack(expectedUrl, token, authorizationNonce)) { dispatched ->
                if (!isTokenCurrent(operation) || SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                    finishUnknown(request, operation, callback,
                        JSONObject().put("action", "back").put("accepted", dispatched == "true"))
                    return@evaluateJavascript
                }
                if (dispatched != "true") {
                    finishUnknown(request, operation, callback,
                        JSONObject().put("action", "back").put("accepted", false))
                    return@evaluateJavascript
                }
                val startedAt = SystemClock.elapsedRealtime()
                val verify = object : Runnable {
                    override fun run() {
                        if (!isTokenCurrent(operation) || SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis ||
                            webView() !== view || activePageId() != startPageId
                        ) {
                            finishUnknown(request, operation, callback)
                            return
                        }
                        view.evaluateJavascript(BrowserSameDocumentHistoryScript.verifyBack(expectedUrl, token)) { verified ->
                            val now = SystemClock.elapsedRealtime()
                            val actualUrl = view.url.orEmpty()
                            if (!isTokenCurrent(operation) || now >= request.deadlineAtElapsedMillis) {
                                finishUnknown(request, operation, callback,
                                    JSONObject().put("action", "back").put("accepted", true)
                                        .put("sameDocumentFallback", true)
                                        .put("url", BrowserPageStateTracker.publicUrl(actualUrl).value ?: JSONObject.NULL))
                                return@evaluateJavascript
                            }
                            if (verified == "true" && actualUrl == expectedUrl) {
                                commitSameDocument(view, urlBefore, actualUrl)
                                val committedState = pageState()
                                if (committedState == null) {
                                    finishUnknown(request, operation, callback,
                                        JSONObject().put("action", "back").put("accepted", true))
                                    return@evaluateJavascript
                                }
                                val data = JSONObject().put("action", "back").put("accepted", true)
                                    .put("pageId", activePageId() ?: JSONObject.NULL)
                                    .put("navigationId", committedState.navigationId)
                                    .put("url", BrowserPageStateTracker.publicUrl(expectedUrl).value ?: JSONObject.NULL)
                                BrowserNavigationMetadata.addTo(data, committedState)
                                if (request.args.optBoolean("thumbnail", false)) {
                                    startThumbnailAfterAcceptedNavigation(
                                        request = request,
                                        token = operation,
                                        view = view,
                                        baselinePageId = startPageId,
                                        baselineNavigationId = baselineState?.navigationId,
                                        baselineDocumentGeneration = baselineDocumentGeneration,
                                        expectedNavigationId = committedState.navigationId,
                                        expectedRequestedUrl = committedState.requestedUrl,
                                        data = data,
                                        callback = callback,
                                    )
                                } else {
                                    finish(request, operation, true, callback, "OK", data)
                                }
                            } else if (now >= request.deadlineAtElapsedMillis || now - startedAt >= SAME_DOCUMENT_BACK_VERIFY_MILLIS) {
                                finishUnknown(request, operation, callback,
                                    JSONObject().put("action", "back").put("accepted", true)
                                        .put("sameDocumentFallback", true).put("expectedUrl", expectedUrl)
                                        .put("url", BrowserPageStateTracker.publicUrl(actualUrl).value ?: JSONObject.NULL))
                            } else {
                                main.postDelayed(this, SAME_DOCUMENT_BACK_POLL_MILLIS)
                            }
                        }
                    }
                }
                main.post(verify)
            }
        }
    }

    private fun observationRef(snapshot: BrowserSnapshot, observation: Long, nonce: String, index: Int): String =
        "b:" + snapshot.sessionGeneration + ":" + snapshot.controlGeneration + ":" +
            documentGeneration + ":" + observation + ":" + nonce + ":" + index

    private fun parseObservationRef(value: String, snapshot: BrowserSnapshot): Pair<String, Int>? {
        val parts = value.split(':')
        if (parts.size != 7 || parts[0] != "b") return null
        val session = parts[1].toLongOrNull() ?: return null
        val control = parts[2].toLongOrNull() ?: return null
        val document = parts[3].toLongOrNull() ?: return null
        val observation = parts[4].toLongOrNull() ?: return null
        val nonce = parts[5]
        val index = parts[6].toIntOrNull() ?: return null
        if (session != snapshot.sessionGeneration || control != snapshot.controlGeneration ||
            document != documentGeneration || observation != latestObservation ||
            nonce != referenceNonce || index !in 0..79 || referencePageId == null ||
            referencePageId != activePageId() || referenceView !== webView()
        ) return null
        return nonce to index
    }

    private fun decodeJavascriptString(encoded: String?): String? = runCatching {
        if (encoded == null || encoded == "null") return null
        JSONArray("[" + encoded + "]").getString(0)
    }.getOrNull()

    private fun BrowserSnapshot.stateName(): String = when (homeStatus()) {
        BrowserHomeStatus.RUNNING -> "RUNNING"
        BrowserHomeStatus.IDLE -> "IDLE"
        null -> "CLOSED"
    }

    private companion object {
        val ERROR_CODES = setOf("DEADLINE_INVALID", "UNSUPPORTED_METHOD")
        val CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
        const val SAME_DOCUMENT_BACK_VERIFY_MILLIS = 1_500L
        const val SAME_DOCUMENT_BACK_POLL_MILLIS = 50L
    }

    private data class AuthorizationMirror(val snapshot: BrowserSnapshot, val enabled: Boolean)

}

internal class BrowserActionGateJavascriptBridge(
    private val gate: BrowserActionGate,
    private val windowHost: BrowserSessionWindowHost,
    private val visualSurfaceAvailable: () -> Boolean,
) {
    @JavascriptInterface
    fun authorize(nonce: String): Boolean = runCatching { gate.authorize(nonce) }.getOrDefault(false)

    @JavascriptInterface
    fun authorizeVisual(nonce: String): Boolean {
        val allowed = if (Looper.myLooper() == Looper.getMainLooper()) {
            windowHost.hasWindowFocus() && visualSurfaceAvailable() && runCatching { gate.authorize(nonce) }.getOrDefault(false)
        } else {
            val latch = java.util.concurrent.CountDownLatch(1)
            val result = AtomicBoolean(false)
            Handler(Looper.getMainLooper()).post {
                result.set(windowHost.hasWindowFocus() && visualSurfaceAvailable() &&
                    runCatching { gate.authorize(nonce) }.getOrDefault(false))
                latch.countDown()
            }
            runCatching { latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrDefault(false) && result.get()
        }
        return allowed
    }
}
