package com.dshbox.browser.runtime

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.os.RemoteException
import com.dshbox.browser.api.BrowserContract
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserUiSettings
import com.dshbox.browser.api.BrowserArtifactFiles
import com.dshbox.browser.api.BrowserEffectPhase
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Signature-protected Messenger endpoint for the active Browser command gateway. */
class BrowserCommandService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var messenger: Messenger
    private var statusReceiverRegistered = false
    private var uiSettingsReceiverRegistered = false
    private var openingRequestId: String? = null
    private val openTimeouts = mutableMapOf<String, Runnable>()
    private val activeMethods = mutableMapOf<String, BrowserCommandMethod>()
    private val coldOpenDigests = mutableMapOf<String, String>()
    private val coldOpenFinalizing = mutableSetOf<String>()
    private lateinit var taskCoordinator: BrowserTaskCoordinator
    private lateinit var controlApprovals: BrowserControlApprovalCoordinator

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val requestId = intent.getStringExtra(BrowserRuntimeSettings.EXTRA_OPEN_REQUEST_ID) ?: return
            if (requestId != openingRequestId) return
            if (requestId in coldOpenFinalizing) return
            val json = intent.getStringExtra(BrowserContract.EXTRA_STATUS_JSON) ?: return
            val status = runCatching { JSONObject(json) }.getOrNull() ?: return
            if (status.optString("state") != BrowserContract.STATUS_IDLE &&
                status.optString("state") != BrowserContract.STATUS_RUNNING
            ) return
            if (!status.optBoolean("automationEnabled") || status.optString("owner") != "ai") return
            val entry = requestLedger.find(requestId) ?: return
            if (entry.responseJson != null) {
                coldOpenDigests.remove(requestId)
                clearOpening(requestId)
                return
            }
            val request = BrowserCommandRequest.parse(entry.rawPayload).getOrNull() ?: return
            if (SystemClock.elapsedRealtime() >= entry.deadlineAtElapsedMillis) {
                val expired = commandResponse(requestId, "UNKNOWN", null, "CLOSED",
                    data = JSONObject().put("dispatched", true).put("reason", "deadline_after_dispatch")).toString()
                val digest = coldOpenDigests[requestId]
                if (digest != null) finishColdOpen(request, entry, digest, expired)
                else {
                    complete(entry, expired)
                    clearOpening(requestId)
                }
                return
            }
            val response = commandResponse(
                requestId = requestId,
                code = "OK",
                executed = true,
                state = status.optString("state").uppercase(Locale.ROOT),
                sessionGeneration = status.optLong("sessionGeneration"),
                controlGeneration = status.optLong("controlGeneration"),
                owner = status.optString("owner"),
                data = JSONObject().put("event", "opened").put("selectionSequence", 0L),
            )
            val digest = coldOpenDigests[requestId]
            if (digest == null) {
                complete(entry, response.toString())
                clearOpening(requestId)
            } else {
                finishColdOpen(request, entry, digest, response.toString())
            }
        }
    }

    private val uiSettingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BrowserContract.ACTION_UI_SETTINGS) return
            updateUiSettings(
                intent.getStringExtra(BrowserContract.EXTRA_LOCALE_TAG),
                intent.getStringExtra(BrowserContract.EXTRA_THEME_MODE),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            BrowserContract.MESSAGE_EXECUTE -> handleExecute(message)
            BrowserContract.MESSAGE_CANCEL -> handleCancel(message)
            else -> false
        }
        true
    }

    override fun onCreate() {
        super.onCreate()
        Thread({ runCatching { BrowserArtifactFiles.removeExpired(filesDir) } }, "browser-artifact-cleanup").start()
        taskCoordinator = BrowserTaskCoordinatorRegistry.getOrCreate(this)
        controlApprovals = BrowserControlApprovalRegistry.getOrCreate(applicationContext, ::currentControlHost)
        messenger = Messenger(handler)
        val filter = IntentFilter(BrowserContract.ACTION_STATUS)
        val permission = BrowserContract.internalPermission(packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, permission, mainHandler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter, permission, mainHandler)
        }
        statusReceiverRegistered = true
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(uiSettingsReceiver, IntentFilter(BrowserContract.ACTION_UI_SETTINGS), permission, mainHandler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(uiSettingsReceiver, IntentFilter(BrowserContract.ACTION_UI_SETTINGS), permission, mainHandler)
        }
        uiSettingsReceiverRegistered = true
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        openTimeouts.toMap().forEach { (requestId, timeout) ->
            mainHandler.removeCallbacks(timeout)
            val entry = requestLedger.find(requestId) ?: return@forEach
            val unknown = commandResponse(requestId, "UNKNOWN", null, "CLOSED").toString()
            val digest = coldOpenDigests.remove(requestId)
            if (digest != null && coldOpenFinalizing.add(requestId) && ::taskCoordinator.isInitialized) {
                taskCoordinator.finishOperation(
                    requestId, digest, unknown, callback = null,
                    deadlineElapsedMillis = entry.deadlineAtElapsedMillis,
                )
            }
            val waiters = requestLedger.complete(
                entry,
                unknown,
                SystemClock.elapsedRealtime(),
            )
            waiters.forEach { replyJson(it, entry.responseJson.orEmpty()) }
        }
        openTimeouts.clear()
        coldOpenDigests.clear()
        coldOpenFinalizing.clear()
        activeMethods.clear()
        openingRequestId = null
        if (statusReceiverRegistered) runCatching { unregisterReceiver(statusReceiver) }
        statusReceiverRegistered = false
        if (uiSettingsReceiverRegistered) runCatching { unregisterReceiver(uiSettingsReceiver) }
        uiSettingsReceiverRegistered = false
        super.onDestroy()
    }

    private fun handleExecute(message: Message) {
        val replyTo = message.replyTo ?: return
        message.data?.let { data ->
            updateUiSettings(
                data.getString(BrowserContract.EXTRA_LOCALE_TAG),
                data.getString(BrowserContract.EXTRA_THEME_MODE),
            )
        }
        val json = message.data?.getString(BrowserContract.KEY_REQUEST_JSON)
        if (json.isNullOrBlank()) {
            reply(replyTo, commandResponse("", "INVALID_ARGUMENT", false))
            return
        }
        val parsed = BrowserCommandRequest.parse(json)
        if (parsed.isFailure) {
            val id = runCatching { JSONObject(json).optString("requestId") }.getOrDefault("")
            val error = parsed.exceptionOrNull()?.message
            val code = error?.takeIf { it in ERROR_CODES } ?: "INVALID_ARGUMENT"
            reply(replyTo, commandResponse(id, code, false))
            return
        }
        val request = parsed.getOrThrow()
        when (val claim = requestLedger.claim(
            request.requestId, request.rawJson, request.deadlineAtElapsedMillis,
            replyTo, SystemClock.elapsedRealtime(),
        )) {
            is BrowserRequestLedger.Claim.ExistingComplete<*> -> replyJson(replyTo, claim.responseJson)
            is BrowserRequestLedger.Claim.ExistingPending<*> -> Unit
            is BrowserRequestLedger.Claim.Conflict<*> -> reply(
                replyTo, commandResponse(request.requestId, "REQUEST_ID_CONFLICT", false),
            )
            is BrowserRequestLedger.Claim.Full<*> -> reply(
                replyTo, commandResponse(request.requestId, "BUSY", false),
            )
            is BrowserRequestLedger.Claim.WaitersFull<*> -> reply(
                replyTo, commandResponse(request.requestId, "BUSY", false),
            )
            is BrowserRequestLedger.Claim.Expired<*> -> reply(
                replyTo, commandResponse(request.requestId, "DEADLINE_EXPIRED", false),
            )
            is BrowserRequestLedger.Claim.New<*> -> {
                @Suppress("UNCHECKED_CAST")
                val entry = claim.entry as BrowserRequestLedger.Entry<Messenger>
                activeMethods[entry.requestId] = request.method
                if (request.method == BrowserCommandMethod.WAIT) scheduleWaitTimeout(entry)
                else scheduleTimeout(entry, request.method == BrowserCommandMethod.OPEN)
                route(request, entry)
            }
        }
    }

    private fun route(request: BrowserCommandRequest, entry: BrowserRequestLedger.Entry<Messenger>) {
        if (request.method == BrowserCommandMethod.CONTROL) {
            if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
                val snapshot = currentSessionSnapshot()
                complete(entry, commandResponse(request.requestId, "DEADLINE_EXPIRED", false,
                    state = snapshot?.homeStatus()?.name ?: "CLOSED",
                    sessionGeneration = snapshot?.sessionGeneration,
                    controlGeneration = snapshot?.controlGeneration,
                    owner = snapshot?.owner?.name?.lowercase(Locale.ROOT)).toString())
                return
            }
            val result = controlApprovals.execute(request.args)
            val snapshot = currentSessionSnapshot()
            complete(entry, commandResponse(
                requestId = request.requestId,
                code = result.optString("code", "EXECUTION_FAILED"),
                executed = false,
                state = snapshot?.homeStatus()?.name ?: "CLOSED",
                sessionGeneration = snapshot?.sessionGeneration,
                controlGeneration = snapshot?.controlGeneration,
                owner = snapshot?.owner?.name?.lowercase(Locale.ROOT),
                data = result.optJSONObject("data") ?: JSONObject(),
            ).toString())
            return
        }
        if (request.method == BrowserCommandMethod.TASK) {
            val action = request.args.optString("action")
            if (action in setOf("status", "list", "pause", "wait", "fail")) {
                taskCoordinator.executeTask(request, null, { complete(entry, it) })
                return
            }
            val gateway = activeGateway()
            if (gateway == null) {
                complete(entry, commandResponse(request.requestId,
                    if (taskCoordinator.isReady()) "CLOSED" else "JOURNAL_UNAVAILABLE", false, "CLOSED").toString())
            } else gateway.execute(request.rawJson) { complete(entry, it) }
            return
        }
        when (request.method) {
            BrowserCommandMethod.OPEN -> handleOpen(request, entry)
            BrowserCommandMethod.STATUS -> {
                val gateway = activeGateway()
                if (gateway != null) gateway.execute(request.rawJson) { complete(entry, it) }
                else replyClosedStatus(request, entry)
            }
            else -> {
                val gateway = activeGateway()
                if (gateway == null) {
                    complete(entry, commandResponse(
                        request.requestId, "CLOSED", false, "CLOSED",
                    ).toString())
                } else gateway.execute(request.rawJson) { complete(entry, it) }
            }
        }
    }

    /** Prefers the mounted activity gateway, then the background session gateway. */
    private fun activeGateway(): BrowserCommandGateway? =
        BrowserCommandGatewayRegistry.current() ?: BrowserSessionGatewayRegistry.current()

    /**
     * Starts (or reuses) the background session and loads the requested URL without bringing any
     * activity to the foreground. Returns false if the session could not be created.
     */
    private fun startBackgroundSession(url: String?): Boolean {
        val existing = BrowserSessionGatewayRegistry.current()
        val gateway = existing ?: BrowserSessionFactory.createOrReuse(this) ?: return false
        return try {
            gateway.open(BrowserOwner.AI)
            if (!url.isNullOrBlank()) gateway.loadUrl(url)
            true
        } catch (_: RuntimeException) {
            // Roll back a session this call created so no half-open live session remains.
            if (existing == null) runCatching { gateway.close() }
            false
        }
    }

    private fun handleOpen(request: BrowserCommandRequest, entry: BrowserRequestLedger.Entry<Messenger>) {
        val gateway = activeGateway()
        if (gateway != null) {
            gateway.execute(request.rawJson) { complete(entry, it) }
            return
        }
        if (openingRequestId != null) {
            complete(entry, commandResponse(request.requestId, "BUSY", false, "CLOSED").toString())
            return
        }
        val preferences = BrowserRuntimeSettings.preferences(this)
        if (!preferences.getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)) {
            complete(entry, commandResponse(
                request.requestId, "AUTOMATION_DISABLED", false, "CLOSED",
            ).toString())
            return
        }
        if (request.sessionGeneration != null || request.controlGeneration != null) {
            complete(entry, commandResponse(request.requestId, "STALE_SESSION", false, "CLOSED").toString())
            return
        }
        if (SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis) {
            complete(entry, commandResponse(request.requestId, "DEADLINE_EXPIRED", false, "CLOSED").toString())
            return
        }
        if (request.taskId != null) {
            complete(entry, commandResponse(request.requestId, "TASK_PAGE_MISMATCH", false, "CLOSED").toString())
            return
        }
        val closedContext = BrowserTaskLiveContext(
            active = false,
            automationEnabled = preferences.getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED),
            ownerIsAi = false,
            identity = null,
            state = "CLOSED",
        )
        taskCoordinator.admitOperation(request, closedContext) { admission, digest ->
            when (admission) {
                BrowserEffectAdmission.NewIntent -> {
                    val currentGateway = BrowserCommandGatewayRegistry.current()
                    val currentPreferences = BrowserRuntimeSettings.preferences(this)
                    val stillAllowed = SystemClock.elapsedRealtime() < request.deadlineAtElapsedMillis &&
                        currentGateway == null && openingRequestId == null &&
                        currentPreferences.getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
                    if (!stillAllowed) {
                        val code = when {
                            SystemClock.elapsedRealtime() >= request.deadlineAtElapsedMillis -> "DEADLINE_EXPIRED"
                            !currentPreferences.getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED) -> "AUTOMATION_DISABLED"
                            else -> "BUSY_OR_STALE"
                        }
                        finishColdOpen(request, entry, digest, commandResponse(request.requestId, code, false, "CLOSED").toString())
                        return@admitOperation
                    }
                    val url = request.args.optString("url").takeIf { it.isNotBlank() }
                    coldOpenDigests[request.requestId] = digest
                    openingRequestId = request.requestId
                    if (!startBackgroundSession(url)) {
                        coldOpenDigests.remove(request.requestId)
                        clearOpening(request.requestId)
                        finishColdOpen(request, entry, digest,
                            commandResponse(request.requestId, "OPEN_FAILED", false, "CLOSED").toString())
                    } else {
                        val snapshot = BrowserSessionGatewayRegistry.current()?.lifecycleController?.snapshot()
                        finishColdOpen(request, entry, digest, commandResponse(
                            requestId = request.requestId,
                            code = "OK",
                            executed = true,
                            state = snapshot?.homeStatus()?.name ?: "IDLE",
                            sessionGeneration = snapshot?.sessionGeneration,
                            controlGeneration = snapshot?.controlGeneration,
                            owner = snapshot?.owner?.name?.lowercase(Locale.ROOT),
                            data = JSONObject().put("event", "opened").put("selectionSequence", 0L),
                        ).toString())
                    }
                }
                is BrowserEffectAdmission.Duplicate -> complete(entry, commandResponse(
                    request.requestId,
                    if (admission.phase == BrowserEffectPhase.CONFIRMED || admission.phase == BrowserEffectPhase.RECONCILED) "DUPLICATE_RECEIPT" else "UNKNOWN",
                    if (admission.phase == BrowserEffectPhase.CONFIRMED || admission.phase == BrowserEffectPhase.RECONCILED) false else null,
                    "CLOSED",
                ).toString())
                BrowserEffectAdmission.RequestIdConflict -> complete(entry, commandResponse(request.requestId, "REQUEST_ID_CONFLICT", false, "CLOSED").toString())
                is BrowserEffectAdmission.Full -> complete(entry, commandResponse(request.requestId, "BUSY", false, "CLOSED").toString())
                is BrowserEffectAdmission.TaskRejected -> complete(entry, commandResponse(request.requestId, admission.code, false, "CLOSED").toString())
            }
        }
    }

    private fun finishColdOpen(
        request: BrowserCommandRequest,
        entry: BrowserRequestLedger.Entry<Messenger>,
        digest: String,
        response: String,
    ) {
        if (entry.responseJson != null) {
            coldOpenDigests.remove(request.requestId)
            clearOpening(request.requestId)
            return
        }
        if (!coldOpenFinalizing.add(request.requestId)) return
        taskCoordinator.finishOperation(
            request.requestId,
            digest,
            response,
            callback = { durable ->
                coldOpenFinalizing.remove(request.requestId)
                coldOpenDigests.remove(request.requestId)
                if (entry.responseJson == null) complete(entry, durable)
                clearOpening(request.requestId)
            },
            deadlineElapsedMillis = request.deadlineAtElapsedMillis,
        )
    }

    private fun scheduleTimeout(entry: BrowserRequestLedger.Entry<Messenger>, isOpen: Boolean) {
        val timeout = Runnable {
            if (entry.responseJson != null) return@Runnable
            if (isOpen && entry.requestId in coldOpenFinalizing) return@Runnable
            val now = SystemClock.elapsedRealtime()
            if (isOpen) {
                val alreadyDispatched = openingRequestId == entry.requestId
                val response = commandResponse(
                    entry.requestId,
                    if (alreadyDispatched) "UNKNOWN" else "OPEN_FAILED",
                    if (alreadyDispatched) null else false,
                    "CLOSED",
                    data = JSONObject().put("dispatched", alreadyDispatched),
                ).toString()
                val digest = coldOpenDigests[entry.requestId]
                if (digest != null) {
                    val request = runCatching { BrowserCommandRequest.parse(entry.rawPayload).getOrThrow() }.getOrNull()
                    if (request == null) {
                        complete(entry, response)
                        clearOpening(entry.requestId)
                    } else finishColdOpen(request, entry, digest, response)
                } else {
                    complete(entry, response)
                    clearOpening(entry.requestId)
                }
            } else {
                val timeoutResponse = if (activeMethods[entry.requestId] == BrowserCommandMethod.SCREENSHOT) {
                    val snapshot = currentSessionSnapshot()
                    commandResponse(entry.requestId, "DEADLINE_EXPIRED", false,
                        state = snapshot?.homeStatus()?.name ?: "CLOSED",
                        sessionGeneration = snapshot?.sessionGeneration,
                        controlGeneration = snapshot?.controlGeneration,
                        owner = snapshot?.owner?.name?.lowercase(Locale.ROOT),
                        data = JSONObject().put("cancelStage", "pixel_read").put("reason", "DEADLINE_EXPIRED")).toString()
                } else commandResponse(entry.requestId, "UNKNOWN", null, "CLOSED").toString()
                val waiters = requestLedger.expire(
                    entry, now, timeoutResponse,
                )
                waiters.forEach { replyJson(it, entry.responseJson.orEmpty()) }
                clearOpening(entry.requestId)
            }
        }
        openTimeouts[entry.requestId] = timeout
        val delay = (entry.deadlineAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        mainHandler.postDelayed(timeout, delay)
    }

    private fun scheduleWaitTimeout(entry: BrowserRequestLedger.Entry<Messenger>) {
        val timeout = Runnable {
            if (entry.responseJson != null) return@Runnable
            val gateway = activeGateway()
            if (gateway != null && gateway.cancelWait(entry.requestId, "WAIT_TIMEOUT")) return@Runnable
            if (entry.responseJson != null) return@Runnable
            val request = runCatching { JSONObject(entry.rawPayload) }.getOrNull()
            val condition = request?.optJSONObject("args")?.optString("condition").orEmpty()
            val mirror = readStatusMirror()
            val state = mirror.optString("state", "closed").uppercase(Locale.ROOT)
            val data = JSONObject().put("matched", false).put("condition", condition)
                .put("pageId", JSONObject.NULL).put("navigationId", JSONObject.NULL)
                .put("documentGeneration", JSONObject.NULL).put("url", JSONObject.NULL)
                .put("loadState", "unknown")
            val waiters = requestLedger.complete(entry, commandResponse(
                entry.requestId, "WAIT_TIMEOUT", false, state,
                mirror.optLong("sessionGeneration").takeIf { mirror.has("sessionGeneration") },
                mirror.optLong("controlGeneration").takeIf { mirror.has("controlGeneration") },
                mirror.optString("owner").takeIf { mirror.has("owner") }, data,
            ).toString(), SystemClock.elapsedRealtime())
            waiters.forEach { replyJson(it, entry.responseJson.orEmpty()) }
        }
        openTimeouts[entry.requestId] = timeout
        mainHandler.postDelayed(timeout, (entry.deadlineAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
    }

    private fun handleCancel(message: Message) {
        val replyTo = message.replyTo ?: return
        val requestId = message.data?.getString(BrowserContract.KEY_REQUEST_ID).orEmpty()
        if (requestId.isBlank()) {
            reply(replyTo, commandResponse(
                "", "INVALID_ARGUMENT", null, "CLOSED",
                data = BrowserCancellationContract.receipt("transport", "invalid_runtime_receipt"),
            ))
            return
        }
        val pendingEntry = requestLedger.find(requestId)
        val pendingMethod = activeMethods[requestId]
        val pendingArgs = pendingEntry?.rawPayload?.let { runCatching { JSONObject(it).optJSONObject("args") }.getOrNull() }
        val isEventWait = pendingMethod == BrowserCommandMethod.EVENT && pendingArgs?.optString("action") == "wait"
        if (pendingEntry != null && pendingEntry.responseJson == null && (pendingMethod == BrowserCommandMethod.WAIT || isEventWait)) {
            val gateway = activeGateway()
            if (gateway == null) reply(replyTo, commandResponse(
                requestId, "CLOSED", null, "CLOSED",
                data = BrowserCancellationContract.receipt("transport", "transport_unavailable"),
            ))
            else gateway.cancel(requestId) { respond(replyTo, it) }
            return
        }
        if (openingRequestId == requestId || (pendingEntry != null && pendingEntry.responseJson == null)) {
            reply(replyTo, commandResponse(
                requestId, "TOO_LATE", null, "RUNNING",
                data = BrowserCancellationContract.receipt("dispatch", "request_in_flight"),
            ))
            return
        }
        val gateway = activeGateway()
        if (gateway == null) {
            reply(replyTo, commandResponse(
                requestId, "NOT_FOUND", null, "CLOSED",
                data = BrowserCancellationContract.receipt("dispatch", "request_not_found"),
            ))
        } else gateway.cancel(requestId) { respond(replyTo, it) }
    }

    private fun replyClosedStatus(request: BrowserCommandRequest, entry: BrowserRequestLedger.Entry<Messenger>) {
        val cached = readStatusMirror()
        cached.put("state", "closed")
        cached.put("owner", "none")
        complete(entry, commandResponse(
            requestId = request.requestId,
            code = "OK",
            executed = false,
            state = "CLOSED",
            sessionGeneration = cached.optLong("sessionGeneration").takeIf { cached.has("sessionGeneration") },
            controlGeneration = cached.optLong("controlGeneration").takeIf { cached.has("controlGeneration") },
            owner = "none",
            data = cached,
        ).toString())
    }

    private fun readStatusMirror(): JSONObject {
        val file = File(filesDir, "browser-tools/run/status.json")
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse {
            val prefs = BrowserRuntimeSettings.preferences(this)
            JSONObject()
                .put("automationEnabled", prefs.getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED))
                .put("sessionGeneration", prefs.getLong(BrowserRuntimeSettings.KEY_LAST_SESSION_GENERATION, 0L))
                .put("controlGeneration", prefs.getLong(BrowserRuntimeSettings.KEY_LAST_CONTROL_GENERATION, 0L))
        }
    }

    private fun updateUiSettings(localeTag: String?, themeMode: String?) {
        val edit = BrowserRuntimeSettings.preferences(this).edit()
        localeTag?.let { value ->
            edit.putString(BrowserRuntimeSettings.KEY_LOCALE_TAG, BrowserUiSettings.normalizeLocaleTag(value))
        }
        themeMode?.let { value ->
            edit.putString(BrowserRuntimeSettings.KEY_THEME_MODE, BrowserUiSettings.normalizeThemeMode(value))
        }
        edit.apply()
    }

    private fun complete(entry: BrowserRequestLedger.Entry<Messenger>, json: String) {
        val waiters = requestLedger.complete(entry, json, SystemClock.elapsedRealtime())
        if (entry.responseJson != null) {
            openTimeouts.remove(entry.requestId)?.let(mainHandler::removeCallbacks)
            activeMethods.remove(entry.requestId)
        }
        waiters.forEach { replyJson(it, entry.responseJson ?: json) }
    }

    private fun clearOpening(requestId: String) {
        if (openingRequestId == requestId) openingRequestId = null
        openTimeouts.remove(requestId)?.let(mainHandler::removeCallbacks)
    }

    private fun respond(replyTo: Messenger, json: String) {
        val response = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
        reply(replyTo, response)
    }

    private fun replyJson(replyTo: Messenger, json: String) {
        val response = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
        reply(replyTo, response)
    }

    private fun reply(replyTo: Messenger, response: JSONObject) {
        val message = Message.obtain(null, BrowserContract.MESSAGE_RESULT)
        message.data = Bundle().apply { putString(BrowserContract.KEY_RESPONSE_JSON, response.toString()) }
        runCatching { replyTo.send(message) }
    }

    companion object {
        private fun currentSessionSnapshot() =
            BrowserSessionGatewayRegistry.current()?.lifecycleController?.snapshot()
                ?: (BrowserCommandGatewayRegistry.current() as? BrowserActivity)?.controlRequestSnapshot

        private fun currentControlHost(): BrowserControlApprovalCoordinator.Host? {
            val activity = (BrowserCommandGatewayRegistry.current() as? BrowserActivity)
                ?.takeIf { it.currentControlRequestIdentity != null }
                ?: return BrowserSessionGatewayRegistry.current()?.controlApprovalHost()
            return object : BrowserControlApprovalCoordinator.Host {
                override fun identity() = activity.currentControlRequestIdentity
                override val aiEnabled get() = activity.aiControlPreferenceEnabled
                override val owner get() = activity.controlRequestSnapshot.owner
                override val lifecycle get() = activity.controlRequestSnapshot.lifecycle
                override val controlTransferPending get() = activity.controlTransferPending
                override fun presentationContext() = activity.controlApprovalUiContext
                override fun showForeground(summary: String, onDecision: (Boolean) -> Unit) =
                    activity.showAiControlRequest(summary, onDecision)
                override fun dismissForeground(approvalId: String) = activity.dismissExternalAiControlRequest()
                override fun grantAfterVerifiedApproval() = activity.grantApprovedAiControl()
            }
        }

        // In-memory replay guard spans Service instances within this process, not process death.
        private val requestLedger = BrowserRequestLedger<Messenger>()
        private val ERROR_CODES = setOf("UNSUPPORTED_METHOD", "DEADLINE_INVALID")
    }
}
