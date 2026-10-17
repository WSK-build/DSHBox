package com.dshbox.browser.runtime

import android.content.Context
import android.os.SystemClock
import com.dshbox.browser.devtools.BrowserDevToolsController
import com.dshbox.browser.devtools.BrowserDevToolsPermissions
import com.dshbox.browser.devtools.BrowserDevToolsScreenshotResult
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Serializes Browser-owned advanced actions and separates UI-origin grants from AI grants. */
internal class BrowserAdvancedController(
    context: Context,
    private val debugEnabled: Boolean,
    private val networkMutationEnabled: Boolean,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val pageUrl = AtomicReference<String?>(null)
    private val documentIdentity = AtomicReference(UUID.randomUUID().toString())
    private val permissions = AtomicReference(
        BrowserDevToolsPermissions(debugEnabled, networkMutationEnabled && debugEnabled),
    )
    private val humanSurfaceGeneration = AtomicLong()
    private val executor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(8),
        { task -> Thread(task, "browser-advanced-commands").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val lock = Any()
    private var devTools: BrowserDevToolsController? = null
    @Volatile private var closed = false

    fun updatePageUrl(url: String?) {
        val next = url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (pageUrl.getAndSet(next) != next) documentIdentity.set(UUID.randomUUID().toString())
    }

    fun onDocumentStarted(url: String?) {
        documentIdentity.set(UUID.randomUUID().toString())
        closeHumanSurface()
        updatePageUrl(url)
    }

    /** A WebView target switch invalidates the cached CDP target, unlike normal navigation. */
    fun onPageChanged(url: String?) {
        documentIdentity.set(UUID.randomUUID().toString())
        closeHumanSurface()
        synchronized(lock) { devTools }?.revoke()
        updatePageUrl(url)
    }

    fun openHumanSurface(): Long = humanSurfaceGeneration.incrementAndGet()

    fun closeHumanSurface() {
        humanSurfaceGeneration.incrementAndGet()
    }

    fun closeHumanSurface(expectedGeneration: Long) {
        closeOwnedHumanSurface(humanSurfaceGeneration, expectedGeneration)
    }

    fun revoke() {
        documentIdentity.set(UUID.randomUUID().toString())
        closeHumanSurface()
        synchronized(lock) { devTools }?.revoke()
    }

    fun updatePermissions(debug: Boolean, networkMutation: Boolean) {
        val next = BrowserDevToolsPermissions(debug, networkMutation && debug)
        val previous = permissions.getAndSet(next)
        if (!debug || (previous.networkMutationEnabled && !next.networkMutationEnabled)) {
            closeHumanSurface()
            val current = synchronized(lock) { devTools }
            current?.revoke()
            if (!debug && current != null) {
                synchronized(lock) { if (devTools === current) devTools = null }
                runCatching { executor.execute { current.close() } }
            }
        }
    }

    fun executeAi(
        family: String,
        args: JSONObject,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        submit(family, args, deadlineElapsedMillis, stillAuthorized, callback)
    }

    fun executeHuman(
        family: String,
        args: JSONObject,
        deadlineElapsedMillis: Long,
        surfaceGeneration: Long,
        callback: (JSONObject) -> Unit,
    ) {
        submit(
            family,
            args,
            deadlineElapsedMillis,
            originAuthorization = {
                surfaceGeneration == humanSurfaceGeneration.get() &&
                    permissionAllows(family, args) &&
                    SystemClock.elapsedRealtime() < deadlineElapsedMillis
            },
            callback = callback,
        )
    }

    fun captureBackgroundScreenshot(
        expectedUrl: String,
        expectedDocumentIdentity: String,
        expectedPixelWidth: Int,
        expectedPixelHeight: Int,
        deadlineElapsedMillis: Long,
        callback: (BrowserDevToolsScreenshotResult) -> Unit,
    ) {
        if (closed || !permissions.get().debugEnabled) {
            callback(BrowserDevToolsScreenshotResult(null, "BACKGROUND_UNAVAILABLE"))
            return
        }
        try {
            executor.execute {
                val result = runCatching {
                    if (closed || !permissions.get().debugEnabled || pageUrl.get() != expectedUrl ||
                        documentIdentity.get() != expectedDocumentIdentity
                    ) return@runCatching BrowserDevToolsScreenshotResult(null, "IDENTITY_CHANGED")
                    val active = controller() ?: return@runCatching BrowserDevToolsScreenshotResult(null, "BACKGROUND_UNAVAILABLE")
                    val captured = active.captureViewportScreenshot(expectedUrl, expectedDocumentIdentity,
                        expectedPixelWidth, expectedPixelHeight, deadlineElapsedMillis)
                    if (closed || !permissions.get().debugEnabled || pageUrl.get() != expectedUrl ||
                        documentIdentity.get() != expectedDocumentIdentity
                    ) {
                        captured.screenshot?.pngBytes?.fill(0)
                        BrowserDevToolsScreenshotResult(null, "IDENTITY_CHANGED")
                    } else captured
                }.getOrElse { BrowserDevToolsScreenshotResult(null, "PIXEL_READ_FAILED") }
                callback(result)
            }
        } catch (_: RejectedExecutionException) {
            callback(BrowserDevToolsScreenshotResult(null, if (closed) "BACKGROUND_UNAVAILABLE" else "CDP_TIMEOUT"))
        }
    }

    fun currentDocumentIdentity(): String? = documentIdentity.get().takeIf { !closed && pageUrl.get() != null }

    fun status(): JSONObject {
        val snapshot = synchronized(lock) { devTools?.status() }
        return snapshot ?: JSONObject()
            .put("code", "OK")
            .put("executed", false)
            .put("data", JSONObject()
                .put("connected", false)
                .put("debugEnabled", permissions.get().debugEnabled)
                .put("networkMutationEnabled", permissions.get().networkMutationEnabled)
                .put("recording", false)
                .put("entries", 0)
                .put("pausedRequests", 0))
    }

    /** Native-only confirmation data; request bodies and tokens never enter ordinary status. */
    fun pendingReplayApprovals(): JSONArray = if (closed || !permissions.get().networkMutationEnabled) JSONArray()
        else synchronized(lock) { devTools?.pendingReplayApprovals() ?: JSONArray() }

    fun approveReplayFromHuman(
        requestId: String,
        sessionGeneration: Long,
        controlGeneration: Long,
        surfaceGeneration: Long,
        callback: (JSONObject) -> Unit,
    ) = replayDecisionFromHuman(requestId, sessionGeneration, controlGeneration, surfaceGeneration, true, callback)

    fun denyReplayFromHuman(requestId: String, sessionGeneration: Long, controlGeneration: Long,
        surfaceGeneration: Long, callback: (JSONObject) -> Unit) =
        replayDecisionFromHuman(requestId, sessionGeneration, controlGeneration, surfaceGeneration, false, callback)

    private fun replayDecisionFromHuman(
        requestId: String,
        sessionGeneration: Long,
        controlGeneration: Long,
        surfaceGeneration: Long,
        approve: Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        val identity = documentIdentity.get()
        try {
            executor.execute {
                val approved = runCatching {
                    if (closed || !permissions.get().networkMutationEnabled ||
                        identity != documentIdentity.get() || surfaceGeneration != humanSurfaceGeneration.get()) return@runCatching null
                    val controller = synchronized(lock) { devTools } ?: return@runCatching null
                    val pending = controller.pendingReplayApprovals()
                    val matches = (0 until pending.length()).mapNotNull { pending.optJSONObject(it) }.any {
                        it.optString("requestId") == requestId && it.optLong("sessionGeneration") == sessionGeneration &&
                            it.optLong("controlGeneration") == controlGeneration
                    }
                    if (!matches) return@runCatching null
                    if (approve) controller.approveReplayAfterNativeConfirmation(requestId)
                    else controller.denyReplayAfterNativeConfirmation(requestId)
                }.getOrNull()
                // The native UI needs only an acknowledgement; the matching AI reads its token through replay_status.
                callback(if (approved == null) result("NOT_AUTHORIZED", false)
                    else result("OK", true).put("data", JSONObject().put("requestId", requestId).put("status", if (approve) "approved" else "denied")))
            }
        } catch (_: RejectedExecutionException) { callback(result(if (closed) "CLOSED" else "BUSY", false)) }
    }

    private fun submit(
        family: String,
        args: JSONObject,
        deadlineElapsedMillis: Long,
        originAuthorization: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        if (closed) {
            callback(result("CLOSED", false))
            return
        }
        val request = JSONObject(args.toString())
        try {
            executor.execute {
                val output = runCatching {
                    val permission = permissions.get()
                    if (!permission.debugEnabled) return@runCatching result("NOT_AUTHORIZED", false)
                    val controller = controller()
                        ?: return@runCatching result("NOT_AUTHORIZED", false)
                    controller.execute(family, request, deadlineElapsedMillis) {
                        !closed && permissionAllows(family, request) &&
                            SystemClock.elapsedRealtime() < deadlineElapsedMillis &&
                            runCatching(originAuthorization).getOrDefault(false)
                    }
                }.getOrElse { result("EXECUTION_FAILED", false) }
                callback(output)
            }
        } catch (_: RejectedExecutionException) {
            callback(result(if (closed) "CLOSED" else "BUSY", false))
        } catch (_: RuntimeException) {
            callback(result("CLOSED", false))
        }
    }

    private fun controller(): BrowserDevToolsController? {
        if (closed || !permissions.get().debugEnabled) return null
        synchronized(lock) {
            if (closed || !permissions.get().debugEnabled) return null
            return devTools ?: BrowserDevToolsController(
                appContext,
                currentPageUrl = { pageUrl.get() },
                permission = { permissions.get() },
                currentDocumentIdentity = { documentIdentity.get().takeIf { !closed && pageUrl.get() != null } },
            ).also { devTools = it }
        }
    }

    private fun permissionAllows(family: String, args: JSONObject): Boolean {
        val current = permissions.get()
        if (!current.debugEnabled) return false
        val action = args.optString("action")
        val networkMutation = family == "network" && action in NETWORK_MUTATIONS
        return !networkMutation || current.networkMutationEnabled
    }

    override fun close() {
        if (closed) return
        closed = true
        closeHumanSurface()
        permissions.set(BrowserDevToolsPermissions(false, false))
        val current = synchronized(lock) { devTools.also { devTools = null } }
        current?.revoke()
        executor.shutdownNow()
        current?.close()
    }

    private fun result(code: String, executed: Boolean?) = JSONObject()
        .put("code", code)
        .put("executed", executed)
        .put("data", JSONObject())

    private companion object {
        val NETWORK_MUTATIONS = setOf("rules", "resolve", "replay", "replay_prepare", "replay_status", "clear_rules")
    }
}
