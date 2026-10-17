package com.dshbox.browser.devtools

import android.content.Context
import android.os.Process
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal fun cdpErrorAfterCommandFailure(current: String?, next: String): String =
    if (current == "CAPTURE_INTERRUPTED") current else next

internal fun axObservationFailure(expired: Boolean, authorized: Boolean, identityCurrent: Boolean): String? = when {
    expired -> "DEADLINE_EXPIRED"
    !authorized -> "NOT_AUTHORIZED"
    !identityCurrent -> "IDENTITY_CHANGED"
    else -> null
}

data class BrowserDevToolsPermissions(
    val debugEnabled: Boolean,
    val networkMutationEnabled: Boolean,
)

data class BrowserDevToolsScreenshot(
    val targetId: String,
    val url: String,
    val documentIdentity: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val viewportScale: Double,
    val pngBytes: ByteArray,
)

data class BrowserDevToolsScreenshotResult(
    val screenshot: BrowserDevToolsScreenshot?,
    val code: String,
)

/** Internal Browser-process CDP facade. It never accepts a raw CDP command from callers. */
class BrowserDevToolsController(
    context: Context,
    private val currentPageUrl: () -> String?,
    private val permission: () -> BrowserDevToolsPermissions,
    /** Native-owned document identity; never supplied by a CLI/tool caller. Null fails closed for writes. */
    private val currentDocumentIdentity: () -> String? = { null },
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val backgroundAllowed = AtomicBoolean(false)
    private val revocationEpoch = AtomicLong(0L)
    private val axActionReferences = BrowserAxActionReferences(SystemClock::elapsedRealtime)
    private val eventExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "browser-devtools-events").apply { isDaemon = true }
    }
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "browser-devtools-timeouts").apply { isDaemon = true }
    }
    private val store = NetworkCaptureStore(appContext)
    private val webSocketStore = WebSocketCaptureStore()
    private val captureStartGate = NetworkCaptureStartGate()
    private val console = ArrayDeque<JSONObject>()
    private val eventCounts = mutableMapOf<String, Long>()
    private val paused = ConcurrentHashMap<String, PausedFetch>()
    private val streamingSse = ConcurrentHashMap<String, SseStreamWindow>()
    private val replayed = ConcurrentHashMap.newKeySet<String>()
    private val requestBodyProbeCount = AtomicLong(0L)
    private val replayApprovals = ConcurrentHashMap<String, ReplayRiskApproval>()
    private val targetSessions = ConcurrentHashMap<String, String>()
    private val capabilities = ConcurrentHashMap<String, String>()
    private val networkRules = mutableListOf<JSONObject>()
    private var connection: CdpConnection? = null
    private var captureConnection: CdpConnection? = null
    private var eventPump: Thread? = null
    private var captureTimeout: ScheduledFuture<*>? = null
    private var consoleDropped = 0L
    private var connectionEpoch = 0L
    private var captureAfterEventSequence = 0L
    private var captureAfterDroppedEventCount = 0L
    private var lastErrorCode: String? = null
    @Volatile private var sseSupportState = "not_detected"
    @Volatile private var responseBodySupportState = "not_detected"
    @Volatile private var networkDomainEnabled = false
    @Volatile private var captureInterrupted = false
    @Volatile private var workerCoverage = "unsupported"
    @Volatile private var serviceWorkerCoverage = "unsupported_shared_target_unverified"

    init {
        listOf("Target.setAutoAttach", "Network.enable", "Network.getResponseBody", "Network.getRequestPostData",
            "Network.streamResourceContent", "Fetch.enable", "Runtime.evaluate", "Network.webSocketEvents",
            "page.fetchReplay")
            .forEach { capabilities[it] = "unknown" }
    }

    private data class PausedFetch(
        val id: String,
        val cdpId: String,
        val params: JSONObject,
        val deadline: Long,
        val resolved: AtomicBoolean = AtomicBoolean(false),
        var timeout: ScheduledFuture<*>? = null,
    )

    private data class ReplayRiskApproval(
        val requestId: String,
        val entryId: String,
        val captureId: String,
        val targetId: String,
        val connectionEpoch: Long,
        val method: String,
        val urlDigest: String,
        val bodyDigest: String,
        val sessionGeneration: Long,
        val controlGeneration: Long,
        val documentIdentity: String,
        val expiresAtElapsedMillis: Long,
        @Volatile var riskToken: String? = null,
        @Volatile var denied: Boolean = false,
    )

    /** Create an expiring request. The native panel must display this preview before approving it. */
    fun prepareReplayApproval(entryId: String, sessionGeneration: Long, controlGeneration: Long): JSONObject? {
        val source = store.replaySource(entryId) ?: return null
        if (source.optString("method") !in NetworkCaptureStore.WRITE_METHODS || !sameOrigin(source.optString("url"), currentPageUrl())) return null
        val doc = runCatching(currentDocumentIdentity).getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val active = synchronized(lock) { connection?.takeIf { !it.isClosed() } } ?: return null
        if (synchronized(lock) { captureConnection !== active }) return null
        val captureId = store.captureId() ?: return null
        val now = SystemClock.elapsedRealtime()
        replayApprovals.entries.removeIf { it.value.expiresAtElapsedMillis <= now }
        if (replayApprovals.size >= MAX_REPLAY_APPROVALS) return null
        val requestId = UUID.randomUUID().toString().replace("-", "")
        replayApprovals[requestId] = ReplayRiskApproval(
            requestId, entryId, captureId, active.targetId, synchronized(lock) { connectionEpoch },
            source.getString("method"), sha256(source.getString("url").toByteArray(StandardCharsets.UTF_8)),
            source.getString("requestBodySha256"), sessionGeneration, controlGeneration, doc,
            now + REPLAY_APPROVAL_TTL_MILLIS,
        )
        return JSONObject().put("requestId", requestId).put("status", "awaiting_user")
            .put("expiresInMillis", REPLAY_APPROVAL_TTL_MILLIS).put("preview", store.replayPreview(entryId))
    }

    /** Called only from Browser native UI after the user accepts the displayed request. */
    fun approveReplayAfterNativeConfirmation(requestId: String): JSONObject? {
        if (!permitted(true)) return null
        val approval = replayApprovals[requestId] ?: return null
        if (approval.denied || approval.riskToken != null || !approvalIsCurrent(approval)) return null
        val token = UUID.randomUUID().toString().replace("-", "")
        approval.riskToken = token
        return JSONObject().put("requestId", requestId).put("riskToken", token)
            .put("expiresInMillis", (approval.expiresAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    fun denyReplayAfterNativeConfirmation(requestId: String): JSONObject? {
        val approval = replayApprovals[requestId] ?: return null
        if (approval.riskToken != null || approval.denied || !approvalIsCurrent(approval)) return null
        approval.denied = true
        return JSONObject().put("requestId", requestId).put("status", "denied")
    }

    /** Native status surface for rendering pending requests. Never returns a token. */
    fun pendingReplayApprovals(): JSONArray {
        val now = SystemClock.elapsedRealtime()
        replayApprovals.entries.removeIf { it.value.expiresAtElapsedMillis <= now }
        return JSONArray(replayApprovals.values.filter { !it.denied && it.riskToken == null && approvalIsCurrent(it) }
            .map { JSONObject().put("requestId", it.requestId).put("entryId", it.entryId)
                .put("captureId", it.captureId).put("sessionGeneration", it.sessionGeneration)
                .put("controlGeneration", it.controlGeneration).put("preview", store.replayPreview(it.entryId))
                .put("requestUrlForConfirmation", store.replaySource(it.entryId)?.optString("url") ?: "")
                .put("requestBodyForConfirmation", store.replayBodyForNativePrompt(it.entryId) ?: "") })
    }

    private fun approvalIsCurrent(a: ReplayRiskApproval): Boolean {
        val source = store.replaySource(a.entryId) ?: return false
        val active = synchronized(lock) { connection?.takeIf { !it.isClosed() } } ?: return false
        return permitted(true) && synchronized(lock) { captureConnection === active && connectionEpoch == a.connectionEpoch } &&
            active.targetId == a.targetId && store.captureId() == a.captureId &&
            runCatching(currentDocumentIdentity).getOrNull() == a.documentIdentity &&
            source.optString("method") == a.method &&
            sha256(source.optString("url").toByteArray(StandardCharsets.UTF_8)) == a.urlDigest &&
            source.optString("requestBodySha256") == a.bodyDigest && sameOrigin(source.optString("url"), currentPageUrl()) &&
            SystemClock.elapsedRealtime() < a.expiresAtElapsedMillis
    }

    private fun replayApprovalStatus(requestId: String, sessionGeneration: Long, controlGeneration: Long): JSONObject {
        val a = replayApprovals[requestId] ?: return result("ENTRY_NOT_FOUND", false)
        if (a.sessionGeneration != sessionGeneration || a.controlGeneration != controlGeneration || !approvalIsCurrent(a)) {
            replayApprovals.remove(requestId, a)
            return result("NOT_AUTHORIZED", false)
        }
        if (a.denied) return result("OK", false, JSONObject().put("requestId", requestId).put("status", "denied")
            .put("expiresInMillis", (a.expiresAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
        val token = a.riskToken ?: return result("OK", false, JSONObject().put("requestId", requestId)
            .put("status", "awaiting_user").put("expiresInMillis", (a.expiresAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
        return result("OK", false, JSONObject().put("requestId", requestId).put("status", "approved")
            .put("riskToken", token).put("entryId", a.entryId).put("captureId", a.captureId)
            .put("sessionGeneration", a.sessionGeneration).put("controlGeneration", a.controlGeneration)
            .put("expiresInMillis", (a.expiresAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L)))
    }

    /** Blocking I/O API. Call only from the Browser-owned serialized background executor. */
    fun execute(
        family: String,
        args: JSONObject,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
    ): JSONObject {
        val request = validate(family, args, deadlineElapsedMillis) ?: return result("INVALID_ARGUMENT", false)
        if (closed.get()) return result("CLOSED", false)
        if (family == "debug" && request.action == "status") return status()
        if (!permitted(request.mutating)) return result("NOT_AUTHORIZED", false)
        val epoch = revocationEpoch.get()
        val scopedAuthorization = { revocationEpoch.get() == epoch && runCatching(stillAuthorized).getOrDefault(false) }
        synchronized(lock) {
            if (!authorized(scopedAuthorization, request.mutating)) return result("NOT_AUTHORIZED", false)
            backgroundAllowed.set(true)
        }
        return try {
            when (family) {
                "debug" -> executeDebug(request, deadlineElapsedMillis, scopedAuthorization)
                "network" -> executeNetwork(request, deadlineElapsedMillis, scopedAuthorization)
                else -> result("UNSUPPORTED", false)
            }
        } catch (error: Throwable) {
            val code = mapError(error)
            rememberCommandError(code)
            result(code, if (code == "UNKNOWN") null else false)
        }
    }

    /** Captures a read-only viewport image over a short-lived, uniquely matched CDP target. */
    fun captureViewportScreenshot(
        expectedUrl: String,
        expectedDocumentIdentity: String,
        expectedPixelWidth: Int,
        expectedPixelHeight: Int,
        deadlineElapsedMillis: Long,
    ): BrowserDevToolsScreenshotResult {
        if (!permitted(false)) return BrowserDevToolsScreenshotResult(null, "BACKGROUND_UNAVAILABLE")
        if (deadlineElapsedMillis <= SystemClock.elapsedRealtime()) {
            return BrowserDevToolsScreenshotResult(null, "DEADLINE_EXPIRED")
        }
        val expectedUrlAtStart = currentPageUrl()?.takeIf(::safeUrl)
            ?: return BrowserDevToolsScreenshotResult(null, "TARGET_UNAVAILABLE")
        if (!sameCaptureUrl(expectedUrlAtStart, expectedUrl) || expectedDocumentIdentity.isBlank() ||
            runCatching(currentDocumentIdentity).getOrNull() != expectedDocumentIdentity
        ) return BrowserDevToolsScreenshotResult(null, "IDENTITY_CHANGED")
        if (expectedPixelWidth <= 0 || expectedPixelHeight <= 0 ||
            expectedPixelWidth.toLong() * expectedPixelHeight.toLong() > MAX_CAPTURE_PIXELS
        ) return captureFailure("CLIPPED_VIEWPORT")

        val captureEpoch = revocationEpoch.get()
        val authorized = {
            !closed.get() && revocationEpoch.get() == captureEpoch && permitted(false) && SystemClock.elapsedRealtime() < deadlineElapsedMillis &&
                sameCaptureUrl(currentPageUrl(), expectedUrl) &&
                runCatching(currentDocumentIdentity).getOrNull() == expectedDocumentIdentity
        }
        var active: CdpConnection? = null
        var pngToWipe: ByteArray? = null
        return try {
            active = CdpConnection.connect(Process.myPid(), expectedUrl, deadlineElapsedMillis)
            if (!authorized()) return captureFailure("IDENTITY_CHANGED")

            val beforeHistory = active.call("Page.getNavigationHistory", JSONObject(), deadlineElapsedMillis, authorized)
            val beforeUrl = navigationHistoryUrl(beforeHistory) ?: return captureFailure("TARGET_UNAVAILABLE")
            if (!sameCaptureUrl(beforeUrl, expectedUrl)) return captureFailure("IDENTITY_CHANGED")

            val beforeMetrics = active.call("Page.getLayoutMetrics", JSONObject(), deadlineElapsedMillis, authorized)
            val viewportBefore = captureViewport(beforeMetrics) ?: return captureFailure("VIEWPORT_UNAVAILABLE")
            if (viewportBefore.width * viewportBefore.height > MAX_CAPTURE_PIXELS) {
                return captureFailure("CLIPPED_VIEWPORT")
            }
            // The private render window already has the exact physical viewport. A CSS clip
            // would unnecessarily emulate/rescale it and truncate fractional-DIP dimensions.
            val captured = active.call("Page.captureScreenshot", JSONObject()
                .put("format", "png")
                .put("fromSurface", true)
                .put("captureBeyondViewport", false)
                .put("optimizeForSpeed", true), deadlineElapsedMillis, authorized)
            val encoded = captured.optString("data")
            if (encoded.isBlank()) return captureFailure("PNG_INVALID")
            if (encoded.length > MAX_CAPTURE_BASE64_CHARS) return captureFailure("PNG_TOO_LARGE")
            val png = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                return captureFailure("PNG_INVALID")
            }
            pngToWipe = png
            if (png.isEmpty()) return captureFailure("PNG_INVALID")
            if (png.size > MAX_CAPTURE_PNG_BYTES) {
                png.fill(0)
                return captureFailure("PNG_TOO_LARGE")
            }
            val pngSize = capturePngDimensions(png) ?: run {
                png.fill(0)
                return captureFailure("PNG_INVALID")
            }
            if (!CdpViewportRaster.matchesNativeViewport(expectedPixelWidth, expectedPixelHeight,
                    pngSize.first, pngSize.second, appContext.resources.displayMetrics.density)) {
                android.util.Log.w("BrowserCapture", "CDP viewport mismatch: native=${expectedPixelWidth}x${expectedPixelHeight}, " +
                    "png=${pngSize.first}x${pngSize.second}, density=${appContext.resources.displayMetrics.density}")
                png.fill(0)
                return captureFailure("CLIPPED_VIEWPORT")
            }

            val afterMetrics = active.call("Page.getLayoutMetrics", JSONObject(), deadlineElapsedMillis, authorized)
            val viewportAfter = captureViewport(afterMetrics) ?: run {
                png.fill(0)
                return captureFailure("VIEWPORT_UNAVAILABLE")
            }
            val afterHistory = active.call("Page.getNavigationHistory", JSONObject(), deadlineElapsedMillis, authorized)
            val afterUrl = navigationHistoryUrl(afterHistory)
            if (!authorized() || !sameCaptureUrl(afterUrl, expectedUrl) ||
                !viewportBefore.sameViewport(viewportAfter)
            ) {
                png.fill(0)
                return captureFailure("IDENTITY_CHANGED")
            }
            BrowserDevToolsScreenshotResult(
                BrowserDevToolsScreenshot(
                    active.targetId,
                    afterUrl.orEmpty(),
                    expectedDocumentIdentity,
                    pngSize.first,
                    pngSize.second,
                    viewportAfter.scale,
                    png,
                ),
                "OK",
            ).also { pngToWipe = null }
        } catch (error: Throwable) {
            captureFailure(captureErrorCode(error, deadlineElapsedMillis))
        } finally {
            pngToWipe?.fill(0)
            runCatching { active?.close() }
        }
    }

    private data class CaptureViewport(val pageX: Double, val pageY: Double, val width: Double, val height: Double, val scale: Double) {
        fun sameViewport(other: CaptureViewport): Boolean = pageX == other.pageX && pageY == other.pageY &&
            width == other.width && height == other.height && scale == other.scale
    }

    private fun captureViewport(metrics: JSONObject): CaptureViewport? {
        val viewport = metrics.optJSONObject("cssVisualViewport") ?: return null
        val x = viewport.optDouble("pageX", Double.NaN)
        val y = viewport.optDouble("pageY", Double.NaN)
        val width = viewport.optDouble("clientWidth", Double.NaN)
        val height = viewport.optDouble("clientHeight", Double.NaN)
        val scale = viewport.optDouble("scale", Double.NaN)
        if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite() || !scale.isFinite() ||
            width < 1 || height < 1 || width > Int.MAX_VALUE || height > Int.MAX_VALUE || scale <= 0.0 || scale > 10.0
        ) return null
        return CaptureViewport(x, y, width, height, scale)
    }

    private fun navigationHistoryUrl(history: JSONObject): String? {
        val entries = history.optJSONArray("entries") ?: return null
        val index = history.optInt("currentIndex", -1)
        if (index !in 0 until entries.length()) return null
        return entries.optJSONObject(index)?.optString("url")?.takeIf(::safeUrl)
    }

    private fun capturePngDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 24) return null
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        fun readInt(offset: Int) = ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
        if (!bytes.copyOfRange(0, 8).contentEquals(signature) || readInt(8) != 13 ||
            String(bytes, 12, 4, StandardCharsets.US_ASCII) != "IHDR"
        ) return null
        return readInt(16) to readInt(20)
    }

    private fun sameCaptureUrl(left: String?, right: String?): Boolean = runCatching {
        val a = java.net.URI(left ?: return false).normalize()
        val b = java.net.URI(right ?: return false).normalize()
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b) &&
            a.rawUserInfo == null && b.rawUserInfo == null && a.rawPath == b.rawPath &&
            a.rawQuery == b.rawQuery && a.rawFragment == b.rawFragment
    }.getOrDefault(false)

    private fun captureErrorCode(error: Throwable, deadline: Long): String {
        val message = generateSequence(error as Throwable?) { it.cause }.take(8).mapNotNull { it.message }.joinToString("|")
        if (message.contains("DEADLINE_EXPIRED")) return "DEADLINE_EXPIRED"
        if (SystemClock.elapsedRealtime() >= deadline ||
            generateSequence(error as Throwable?) { it.cause }.any { it is java.util.concurrent.TimeoutException }) return "CDP_TIMEOUT"
        if (message.contains("AMBIGUOUS_TARGET")) return "AMBIGUOUS_TARGET"
        if (message.contains("TARGET_UNAVAILABLE")) return "TARGET_UNAVAILABLE"
        if (message.contains("FRAME_TOO_LARGE")) return "PNG_TOO_LARGE"
        if (error is CdpCommandRejectedException && isUnsupportedCdpMethod(error.protocolCode, error.protocolMessage)) return "CDP_UNSUPPORTED"
        if (message.contains("IDENTITY_CHANGED") || message.contains("NOT_AUTHORIZED")) return "IDENTITY_CHANGED"
        return "PIXEL_READ_FAILED"
    }

    private fun captureFailure(code: String) = BrowserDevToolsScreenshotResult(null, code)

    /** Returns cached state only. This never discovers targets or opens a CDP socket. */
    fun status(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("code", "OK")
            .put("executed", false)
            .put("data", JSONObject()
                .put("connected", connection?.isClosed() == false)
                .put("connectionEpoch", connectionEpoch)
                .put("eventCounts", JSONObject().also { counts ->
                    eventCounts.forEach { (method, count) -> counts.put(method, count) }
                })
                .put("debugEnabled", safePermissions().debugEnabled)
                .put("networkMutationEnabled", safePermissions().networkMutationEnabled)
                .put("recording", store.isRecording())
                .put("networkTrackingEnabled", networkDomainEnabled)
                .put("captureInterrupted", captureInterrupted)
                .put("captureBodiesEnabled", store.capturesBodies())
                .put("captureRequestBodiesEnabled", store.capturesRequestBodies())
                .put("requestBodyCaptureLimitBytes", NetworkCaptureStore.MAX_REQUEST_BODY_BYTES)
                .put("requestBodyCaptureUsedBytes", store.requestBodyBytesUsed())
                .put("responseBodyCaptureSupport", responseBodySupportState)
                .put("artifactError", store.artifactErrorCode() ?: JSONObject.NULL)
                .put("ruleCount", synchronized(lock) { networkRules.size })
                .put("interceptionEnabled", synchronized(lock) { networkRules.isNotEmpty() })
                .put("captureId", store.captureId() ?: JSONObject.NULL)
                .put("entries", store.entryCount())
                .put("eventDrops", connection?.droppedEventCount() ?: 0L)
                .put("truncated", (connection?.droppedEventCount() ?: 0L) > 0L)
                .put("pausedRequests", paused.size)
                .put("sseStreamingRequests", streamingSse.size)
                .put("sseStreamingSupport", sseSupportState)
                .put("lastError", lastErrorCode ?: JSONObject.NULL)
                .put("capabilities", JSONObject().also { out -> capabilities.toSortedMap().forEach { (key, value) -> out.put(key, value) } })
                .put("coverage", JSONObject()
                    .put("mainPage", if (connection?.isClosed() == false) "connected" else "disconnected")
                    .put("workerTargets", workerCoverage)
                    .put("serviceWorkerTargets", serviceWorkerCoverage)
                    .put("otherTargets", "excluded")))
    }

    /** Immediately invalidates future commands and releases Fetch pauses on the event worker. */
    fun revoke() {
        axActionReferences.clear()
        val current = synchronized(lock) {
            revocationEpoch.incrementAndGet()
            backgroundAllowed.set(false)
            markWebSocketEventDrops()
            store.stop()
            store.clearSensitiveRequestBodies()
            webSocketStore.stop()
            captureTimeout?.cancel(false)
            captureTimeout = null
            stopStreamingCaptures()
            networkRules.clear()
            replayApprovals.clear()
            val previous = connection
            connection = null
            captureConnection = null
            targetSessions.clear()
            workerCoverage = "detached"
            networkDomainEnabled = false
            eventPump?.interrupt()
            eventPump = null
            previous
        }
        val outstanding = paused.values.toList()
        runCatching { eventExecutor.execute {
            releasePausedBatch(current, outstanding)
            runCatching { current?.call("Fetch.disable", JSONObject(), SystemClock.elapsedRealtime() + 500L) { true } }
            runCatching { current?.close() }
        } }.onFailure { runCatching { current?.close() } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        revoke()
        captureTimeout?.cancel(false)
        scheduler.shutdownNow()
        eventExecutor.shutdown()
        runCatching { eventExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        closeConnection()
    }

    private data class Validated(val action: String, val args: JSONObject, val mutating: Boolean)

    private fun validate(family: String, args: JSONObject, deadline: Long): Validated? {
        if (deadline <= SystemClock.elapsedRealtime() || deadline - SystemClock.elapsedRealtime() > 60_000L) return null
        val action = args.opt("action") as? String ?: return null
        val allowed = when (family) {
            "debug" -> when (action) {
                "status" -> emptySet()
                "console" -> setOf("limit")
                "evaluate" -> setOf("expression")
                "dom" -> setOf("selector")
                "ax_tree", "ax_observe" -> setOf("maxNodes", "maxDepth", "includeIgnored")
                "ax_click" -> setOf("actionRef")
                "ax_type" -> setOf("actionRef", "text", "replace")
                "ax_scroll" -> setOf("actionRef", "direction", "steps")
                "emulate" -> setOf("width", "height", "mobile", "clear")
                "interact" -> setOf("kind", "key", "x", "y", "toX", "toY", "selector", "value")
                else -> return null
            }
            "network" -> when (action) {
                "start" -> setOf("includeBodies", "includeRequestBodies")
                "stop", "clear_rules", "paused" -> emptySet()
                "list" -> setOf("cursor", "limit")
                "frames" -> setOf("cursor", "limit")
                "export_frames" -> setOf("includeBodies")
                "body" -> setOf("entryId")
                "replay_prepare" -> setOf("entryId", "sessionGeneration", "controlGeneration")
                "replay_status" -> setOf("requestId", "sessionGeneration", "controlGeneration")
                "replay" -> setOf("entryId", "riskToken", "captureId", "sessionGeneration", "controlGeneration")
                "har" -> setOf("includeBodies")
                "rules" -> setOf("rules")
                "resolve" -> setOf("pausedId", "decision", "headers", "url", "method", "postData", "body", "responseCode")
                else -> return null
            }
            else -> return null
        }
        if (!onlyKeys(args, allowed + "action")) return null
        val mutating = when (family to action) {
            "network" to "rules", "network" to "resolve", "network" to "replay", "network" to "clear_rules",
            "network" to "replay_prepare" -> true
            else -> false
        }
        if (family == "debug") when (action) {
            "status" -> Unit
            "console" -> if (intArg(args, "limit", 20) !in 1..50) return null
            "evaluate" -> if (stringArg(args, "expression", 8192)?.isNotEmpty() != true) return null
            "dom" -> if ((args.opt("selector") != null && stringArg(args, "selector", 512) == null)) return null
            "ax_tree", "ax_observe" -> if (intArg(args, "maxNodes", 64) !in 1..256 || intArg(args, "maxDepth", 4) !in 1..8 ||
                (args.has("includeIgnored") && boolArg(args, "includeIgnored", null) == null)) return null
            "ax_click", "ax_type", "ax_scroll" -> {
                val ref = args.opt("actionRef") as? String ?: return null
                if (!ref.matches(Regex("axa:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}:[0-9]{1,3}")) ||
                    ref.substringAfterLast(':').toIntOrNull() !in 0..255) return null
                if (action == "ax_type" && (stringArg(args, "text", 4096) == null ||
                        (args.has("replace") && boolArg(args, "replace", null) == null))) return null
                if (action == "ax_scroll" && (args.opt("direction") !in setOf("up", "down") ||
                        intArg(args, "steps", 1) !in 1..10)) return null
            }
            "emulate" -> {
                if (args.has("clear") && boolArg(args, "clear", null) == null) return null
                if (boolArg(args, "clear", false) == true) {
                    if (!onlyKeys(args, setOf("action", "clear"))) return null
                } else if (!onlyKeys(args, setOf("action", "clear", "width", "height", "mobile")) ||
                    intArg(args, "width", -1) !in 240..2560 || intArg(args, "height", -1) !in 240..2560 ||
                    boolArg(args, "mobile", null) == null
                ) return null
            }
            "interact" -> {
                if (stringArg(args, "kind", 8) !in setOf("key", "tap", "drag", "select")) return null
                val kind = args.optString("kind")
                if (kind == "key" && stringArg(args, "key", 32).isNullOrBlank()) return null
                if (kind == "tap") {
                    if (args.has("selector")) {
                        if (stringArg(args, "selector", 512).isNullOrBlank() || args.has("x") || args.has("y") ||
                            !onlyKeys(args, setOf("action", "kind", "selector"))) return null
                    } else if (numberArg(args, "x") == null || numberArg(args, "y") == null ||
                        !onlyKeys(args, setOf("action", "kind", "x", "y"))) return null
                }
                if (kind == "drag" && listOf("x", "y", "toX", "toY").any { numberArg(args, it) == null }) return null
                if (kind == "select" && (stringArg(args, "selector", 512).isNullOrBlank() || stringArg(args, "value", 4096) == null)) return null
            }
        }
        if (family == "network") when (action) {
            "start" -> if ((args.has("includeBodies") && boolArg(args, "includeBodies", null) == null) ||
                (args.has("includeRequestBodies") && boolArg(args, "includeRequestBodies", null) == null)) return null
            "har", "export_frames" -> if (args.has("includeBodies") && boolArg(args, "includeBodies", null) == null) return null
            "list" -> if (intArg(args, "cursor", 0) < 0 || intArg(args, "limit", 20) !in 1..20) return null
            "frames" -> if (intArg(args, "cursor", 0) < 0 || intArg(args, "limit", 20) !in 1..20) return null
            "body" -> if (stringArg(args, "entryId", 128)?.matches(SAFE_ID) != true) return null
            "replay_prepare" -> if (stringArg(args, "entryId", 128)?.matches(SAFE_ID) != true ||
                longArg(args, "sessionGeneration") == null || longArg(args, "controlGeneration") == null) return null
            "replay_status" -> if (stringArg(args, "requestId", 64)?.matches(RISK_TOKEN) != true ||
                longArg(args, "sessionGeneration") == null || longArg(args, "controlGeneration") == null) return null
            "replay" -> {
                if (stringArg(args, "entryId", 128)?.matches(SAFE_ID) != true) return null
                val ticketKeys = setOf("riskToken", "captureId", "sessionGeneration", "controlGeneration")
                val ticketPresent = ticketKeys.any { key -> args.has(key) }
                if (ticketPresent) {
                    if (ticketKeys.any { !args.has(it) } || stringArg(args, "riskToken", 64)?.matches(RISK_TOKEN) != true ||
                        stringArg(args, "captureId", 64)?.matches(SAFE_ID) != true ||
                        longArg(args, "sessionGeneration") == null || longArg(args, "controlGeneration") == null) return null
                }
            }
            "rules" -> if (!validateRules(args.optJSONArray("rules"))) return null
            "resolve" -> {
                if (stringArg(args, "pausedId", 128)?.matches(SAFE_ID) != true ||
                    stringArg(args, "decision", 16) !in setOf("continue", "block", "modify", "fulfill")
                ) return null
                if (args.has("method") && SAFE_HTTP_METHOD.matches(stringArg(args, "method", 16) ?: "").not()) return null
                if (args.has("url") && !safeUrl(stringArg(args, "url", 2048))) return null
                if (args.has("postData") && stringArg(args, "postData", 8192) == null) return null
                if (args.has("body") && stringArg(args, "body", 8192) == null) return null
                if (args.has("responseCode") && intArg(args, "responseCode", -1) !in 200..599) return null
                if (args.has("headers") && !validHeaders(args.optJSONObject("headers"))) return null
                if (args.optString("decision") == "fulfill" && !args.has("body")) return null
            }
        }
        return Validated(action, args, mutating)
    }

    private fun executeDebug(request: Validated, deadline: Long, auth: () -> Boolean): JSONObject {
        if (request.action == "status") return status()
        val connection = ensureConnected(deadline, auth)
        when (request.action) {
            "console" -> {
                command(connection, "Runtime.enable", JSONObject(), deadline, auth)
                command(connection, "Log.enable", JSONObject(), deadline, auth)
                val limit = intArg(request.args, "limit", 20)
                val messages = synchronized(lock) { JSONArray(console.toList().takeLast(limit).reversed()) }
                var truncated = false
                while (messages.length() > 0 && JSONObject().put("entries", messages).put("dropped", consoleDropped)
                        .toString().toByteArray(StandardCharsets.UTF_8).size > MAX_DATA_BYTES) {
                    messages.remove(messages.length() - 1)
                    truncated = true
                }
                return result("OK", false, JSONObject().put("entries", messages).put("dropped", consoleDropped).put("truncated", truncated))
            }
            "evaluate" -> {
                val expression = request.args.getString("expression")
                val response = command(connection, "Runtime.evaluate", JSONObject()
                    .put("expression", expression)
                    .put("returnByValue", true)
                    .put("awaitPromise", true)
                    .put("userGesture", false)
                    .put("timeout", remainingCdpTimeout(deadline)), deadline, auth)
                val exception = response.optJSONObject("exceptionDetails")
                if (exception != null) return result("UNKNOWN", null,
                    JSONObject().put("exception", truncateUtf8(exception.optString("text"), 1024).first)
                        .put("nonRetryable", true).put("sideEffectsMayHaveOccurred", true))
                val remote = response.optJSONObject("result") ?: JSONObject()
                val value = remote.opt("value")
                val output = when (value) {
                    null, JSONObject.NULL -> if (remote.optString("type") == "undefined") "undefined" else "null"
                    is String -> value
                    else -> value.toString()
                }
                val bounded = truncateUtf8(output, MAX_DATA_BYTES)
                return result("OK", true, JSONObject()
                    .put("type", remote.optString("type"))
                    .put("value", bounded.first)
                    .put("truncated", bounded.second))
            }
            "ax_tree", "ax_observe" -> {
                axActionReferences.clear()
                val expectedUrl = currentPageUrl()?.takeIf(::safeUrl) ?: return result("TARGET_UNAVAILABLE", false)
                val identity = runCatching(currentDocumentIdentity).getOrNull()
                    ?: return result("IDENTITY_CHANGED", false)
                val epoch = synchronized(lock) { connectionEpoch }
                val transport = object : BrowserAxTreeTransport {
                    override val targetId: String get() = connection.targetId
                    override val connectionEpoch: Long get() = synchronized(lock) { this@BrowserDevToolsController.connectionEpoch }
                    override fun call(method: String, params: JSONObject, deadlineElapsedMillis: Long,
                        stillAuthorized: () -> Boolean): JSONObject = command(connection, method, params,
                        deadlineElapsedMillis, { auth() && stillAuthorized() })
                }
                val observation = BrowserAxTreeObserver(transport, currentDocumentIdentity, SystemClock::elapsedRealtime)
                    .observe(BrowserAxTargetContext(connection.targetId, epoch, expectedUrl, identity),
                        BrowserAxTreeRequest(request.args.optInt("maxNodes", 64), request.args.optInt("maxDepth", 4),
                            request.args.optBoolean("includeIgnored", false)), deadline, auth)
                if (observation.code != "OK" || request.action == "ax_tree") {
                    return result(observation.code, false, observation.data)
                }
                axObservationFailure(SystemClock.elapsedRealtime() >= deadline, auth(),
                    currentDocumentIdentity() == identity && synchronized(lock) { connectionEpoch } == epoch)
                    ?.let { return result(it, false) }
                val binding = BrowserAxActionReferences.Identity(connection.targetId, epoch, identity, revocationEpoch.get())
                val published = BrowserAxActionObservation.publish(observation, binding, axActionReferences)
                axObservationFailure(SystemClock.elapsedRealtime() >= deadline, auth(),
                    currentDocumentIdentity() == identity && synchronized(lock) { connectionEpoch } == epoch)
                    ?.let {
                        axActionReferences.clear()
                        return result(it, false)
                    }
                return result("OK", false, published)
            }
            "ax_click", "ax_type", "ax_scroll" -> {
                val document = currentDocumentIdentity() ?: return result("IDENTITY_CHANGED", false)
                val binding = BrowserAxActionReferences.Identity(connection.targetId,
                    synchronized(lock) { connectionEpoch }, document, revocationEpoch.get())
                val candidate = axActionReferences.resolve(request.args.getString("actionRef"), binding)
                    ?: return result("STALE_REFERENCE", false)
                val transport = object : BrowserAxTreeTransport {
                    override val targetId: String get() = connection.targetId
                    override val connectionEpoch: Long get() = synchronized(lock) { this@BrowserDevToolsController.connectionEpoch }
                    override fun call(method: String, params: JSONObject, deadlineElapsedMillis: Long,
                        stillAuthorized: () -> Boolean): JSONObject = command(connection, method, params,
                        deadlineElapsedMillis, { auth() && stillAuthorized() })
                    override fun releaseObjectGroup(objectGroup: String, deadlineElapsedMillis: Long) {
                        require(objectGroup.matches(Regex("browser-ax-action-[1-9][0-9]{0,18}")))
                        connection.call("Runtime.releaseObjectGroup", JSONObject().put("objectGroup", objectGroup),
                            deadlineElapsedMillis, { !connection.isTransportClosed() })
                    }
                }
                val action = when (request.action) {
                    "ax_click" -> BrowserAxAction.Click
                    "ax_type" -> BrowserAxAction.Type(request.args.getString("text"), request.args.optBoolean("replace", true))
                    else -> BrowserAxAction.Scroll(if (request.args.getString("direction") == "up")
                        BrowserAxScrollDirection.UP else BrowserAxScrollDirection.DOWN, request.args.optInt("steps", 1))
                }
                return BrowserAxActionBridge(transport, currentDocumentIdentity, SystemClock::elapsedRealtime)
                    .execute(candidate, binding, action, deadline, auth)
            }
            "dom" -> {
                val selector = request.args.optString("selector", "*")
                val script = buildDomSummaryExpression(selector)
                val response = command(connection, "Runtime.evaluate", JSONObject()
                    .put("expression", script).put("returnByValue", true).put("awaitPromise", false), deadline, auth)
                val exception = response.optJSONObject("exceptionDetails")
                if (exception != null) return result("DOM_QUERY_FAILED", false)
                val value = response.optJSONObject("result")?.opt("value")
                val json = value as? String ?: return result("DOM_QUERY_FAILED", false)
                val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return result("DOM_QUERY_FAILED", false)
                val items = parsed.optJSONArray("items") ?: JSONArray()
                var truncated = false
                while (items.length() > 0 && parsed.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_DATA_BYTES) {
                    items.remove(items.length() - 1)
                    truncated = true
                }
                return result("OK", false, JSONObject().put("summary", parsed).put("truncated", truncated))
            }
            "emulate" -> {
                if (request.args.optBoolean("clear", false)) {
                    command(connection, "Emulation.clearDeviceMetricsOverride", JSONObject(), deadline, auth)
                    command(connection, "Emulation.setTouchEmulationEnabled", JSONObject().put("enabled", false), deadline, auth)
                } else {
                    val mobile = request.args.getBoolean("mobile")
                    command(connection, "Emulation.setDeviceMetricsOverride", JSONObject()
                        .put("width", request.args.getInt("width"))
                        .put("height", request.args.getInt("height"))
                        .put("deviceScaleFactor", 1)
                        .put("mobile", mobile), deadline, auth)
                    command(connection, "Emulation.setTouchEmulationEnabled", JSONObject().put("enabled", mobile), deadline, auth)
                }
                return result("OK", true)
            }
            "interact" -> return interact(connection, request.args, deadline, auth)
        }
        return result("UNSUPPORTED", false)
    }

    private fun executeNetwork(request: Validated, deadline: Long, auth: () -> Boolean): JSONObject {
        when (request.action) {
            "replay_prepare" -> {
                val prepared = prepareReplayApproval(request.args.getString("entryId"),
                    request.args.getLong("sessionGeneration"), request.args.getLong("controlGeneration"))
                    ?: return result("UNSUPPORTED", false)
                return result("OK", true, prepared)
            }
            "replay_status" -> return replayApprovalStatus(request.args.getString("requestId"),
                request.args.getLong("sessionGeneration"), request.args.getLong("controlGeneration"))
            "list" -> {
                val active = synchronized(lock) { connection }
                if (active != null && active.isClosed()) {
                    handleConnectionLoss(active, connectionLoss(active))
                } else if (active == null && store.isRecording()) {
                    interruptCaptureWithoutConnection(CdpConnectionLoss.TRANSPORT_CLOSED)
                }
                markCaptureEventDrops()
                val data = store.list(intArg(request.args, "cursor", 0), intArg(request.args, "limit", 20))
                val dropped = synchronized(lock) { connection?.droppedEventCount() ?: 0L }
                data.put("eventDrops", dropped)
                    .put("captureInterrupted", captureInterrupted)
                    .put("truncated", data.optBoolean("truncated") || dropped > 0L)
                return result("OK", false, data)
            }
            "body" -> {
                markCaptureEventDrops()
                return store.body(request.args.getString("entryId"))
                    ?.let { result("OK", false, it) } ?: result("ENTRY_NOT_FOUND", false)
            }
            "frames" -> {
                val active = synchronized(lock) { captureConnection }
                val recording = webSocketStore.isRecording()
                val dropped = if (recording) {
                    ((active?.droppedEventCount() ?: 0L) - synchronized(lock) { captureAfterDroppedEventCount }).coerceAtLeast(0L)
                } else 0L
                if (dropped > 0L) webSocketStore.markEventDrops(dropped)
                val data = webSocketStore.list(
                    intArg(request.args, "cursor", 0),
                    intArg(request.args, "limit", 20),
                    dropped,
                )
                return result("OK", false, data)
            }
            "export_frames" -> {
                val active = synchronized(lock) { captureConnection }
                val drops = ((active?.droppedEventCount() ?: 0L) - synchronized(lock) { captureAfterDroppedEventCount }).coerceAtLeast(0L)
                if (drops > 0L) webSocketStore.markEventDrops(drops)
                return webSocketStore.export(appContext.filesDir, drops, request.args.optBoolean("includeBodies", false))
                    ?.let { result("OK", true, it) } ?: result("ARTIFACT_FAILED", false)
            }
            "paused" -> return result("OK", false, JSONObject().put("requests", paused.values.map { pausedInfo(it) }.let(::JSONArray)))
            "har" -> {
                markCaptureEventDrops()
                return store.exportHar(request.args.optBoolean("includeBodies", false),
                    synchronized(lock) { connection?.droppedEventCount() ?: 0L })
                    ?.let { result("OK", true, it) } ?: result("ARTIFACT_FAILED", false)
            }
            "stop" -> {
                synchronized(lock) {
                    markWebSocketEventDrops()
                    store.stop()
                    webSocketStore.stop()
                    stopStreamingCaptures()
                    captureTimeout?.cancel(false)
                    captureTimeout = null
                }
                synchronized(lock) { connection }?.let { detachWorkerTargets(it, deadline, auth) }
                // Keep Network enabled on the same target so recorded XHRs remain replayable;
                // the stopped store retains no further request/body artifacts.
                return result("OK", true, JSONObject().put("captureId", store.captureId() ?: JSONObject.NULL))
            }
            "clear_rules" -> {
                val connection = synchronized(lock) { connection }
                synchronized(lock) { networkRules.clear() }
                if (connection != null) {
                    releaseAllPaused(connection)
                    command(connection, "Fetch.disable", JSONObject(), deadline, auth, true)
                }
                return result("OK", true)
            }
            "start" -> {
                if (store.isRecording()) {
                    val active = synchronized(lock) { connection }
                    if (active != null && !active.isClosed()) {
                        return result("OK", false, JSONObject()
                            .put("captureId", store.captureId() ?: JSONObject.NULL)
                            .put("alreadyRecording", true))
                    }
                    if (active != null) {
                        handleConnectionLoss(active, connectionLoss(active))
                    } else {
                        interruptCaptureWithoutConnection(CdpConnectionLoss.TRANSPORT_CLOSED)
                    }
                }
                val connection = ensureConnected(deadline, auth)
                enableWorkerTargets(connection, deadline, auth)
                var firstEventSequence = 0L
                var firstEventDropCount = 0L
                val id = captureStartGate.enableThenStart(
                    enable = {
                        firstEventSequence = connection.lastReceivedEventSequence()
                        firstEventDropCount = connection.droppedEventCount()
                        val needsNetworkEnable = synchronized(lock) {
                            if (this.connection !== connection || connection.isClosed()) error("CDP_CLOSED")
                            !networkDomainEnabled
                        }
                        if (needsNetworkEnable) {
                            command(connection, "Network.enable", JSONObject()
                                .put("maxTotalBufferSize", MAX_TOTAL_BODY_BYTES)
                                .put("maxResourceBufferSize", MAX_BODY_BYTES), deadline, auth)
                        }
                        Unit
                    },
                    begin = {
                        synchronized(lock) {
                            if (this.connection !== connection || connection.isClosed()) error("CDP_CLOSED")
                            if (!allowedBackground() || !authorized(auth, false)) error("NOT_AUTHORIZED")
                            networkDomainEnabled = true
                            captureInterrupted = false
                            lastErrorCode = null
                            streamingSse.clear()
                            sseSupportState = "not_detected"
                            responseBodySupportState = "not_detected"
                            replayed.clear()
                            replayApprovals.clear()
                            requestBodyProbeCount.set(0L)
                            eventCounts.clear()
                            captureConnection = connection
                            captureAfterEventSequence = firstEventSequence
                            captureAfterDroppedEventCount = firstEventDropCount
                            captureTimeout?.cancel(false)
                            val captureId = store.begin(request.args.optBoolean("includeBodies", false),
                                request.args.optBoolean("includeRequestBodies", false))
                            webSocketStore.begin(captureId, request.args.optBoolean("includeBodies", false))
                            captureTimeout = scheduler.schedule({
                                captureStartGate.dispatchEvent {
                                    synchronized(lock) {
                                        if (captureConnection === connection && store.captureId() == captureId && store.isRecording()) {
                                            markWebSocketEventDrops(connection)
                                            store.stop()
                                            webSocketStore.stop()
                                            stopStreamingCaptures()
                                            detachWorkerTargets(connection, SystemClock.elapsedRealtime() + 700L) { allowedBackground() }
                                        }
                                    }
                                }
                            }, MAX_CAPTURE_MILLIS, TimeUnit.MILLISECONDS)
                            captureId
                        }
                    },
                )
                return result("OK", true, JSONObject().put("captureId", id)
                    .put("expiresInMillis", MAX_CAPTURE_MILLIS).put("networkTrackingEnabled", true))
            }
            "rules" -> {
                requireNetworkMutation()
                val connection = ensureConnected(deadline, auth)
                val rules = request.args.getJSONArray("rules")
                synchronized(lock) {
                    networkRules.clear()
                    for (index in 0 until rules.length()) networkRules.add(JSONObject(rules.getJSONObject(index).toString()))
                }
                releaseAllPaused(connection)
                if (rules.length() == 0) {
                    command(connection, "Fetch.disable", JSONObject(), deadline, auth, true)
                    return result("OK", true, JSONObject().put("ruleCount", 0))
                }
                val patterns = JSONArray()
                networkRules.forEach { rule ->
                    patterns.put(JSONObject().put("urlPattern", rule.getString("urlPattern"))
                        .put("requestStage", if (rule.getString("stage") == "response") "Response" else "Request"))
                }
                command(connection, "Fetch.disable", JSONObject(), deadline, auth, true)
                command(connection, "Fetch.enable", JSONObject().put("patterns", patterns), deadline, auth, true)
                return result("OK", true, JSONObject().put("ruleCount", rules.length()))
            }
            "resolve" -> {
                requireNetworkMutation()
                val id = request.args.getString("pausedId")
                val pending = paused[id] ?: return result("ENTRY_NOT_FOUND", false)
                val decision = request.args.getString("decision")
                return resolvePaused(pending, decision, request.args, deadline, auth)
            }
            "replay" -> {
                requireNetworkMutation()
                val source = store.replaySource(request.args.getString("entryId")) ?: return result("UNSUPPORTED", false)
                val entryId = request.args.getString("entryId")
                val capturedUrl = source.optString("url")
                val method = source.optString("method")
                val writeReplay = method in NetworkCaptureStore.WRITE_METHODS
                if ((!writeReplay && method !in setOf("GET", "HEAD")) || !sameOrigin(capturedUrl, currentPageUrl())) {
                    return result("UNSUPPORTED", false)
                }
                val capturedConnection = synchronized(lock) { captureConnection }
                    ?: return result("TARGET_UNAVAILABLE", false)
                if (capturedConnection.isClosed()) return result("TARGET_UNAVAILABLE", false)
                val connection = ensureConnected(deadline, auth)
                if (connection !== capturedConnection) return result("TARGET_UNAVAILABLE", false)
                // A page may replace its own fetch/Promise. Replay uses a distinct main-frame realm.
                val frame = command(connection, "Page.getFrameTree", JSONObject(), deadline, auth)
                    .optJSONObject("frameTree")?.optJSONObject("frame") ?: return result("TARGET_UNAVAILABLE", false)
                val frameId = frame.optString("id")
                if (frameId.length !in 1..128 || !sameOrigin(frame.optString("url"), currentPageUrl())) return result("TARGET_UNAVAILABLE", false)
                val world = try {
                    command(connection, "Page.createIsolatedWorld", JSONObject().put("frameId", frameId)
                        .put("worldName", "dsh-browser-replay"), deadline, auth)
                } catch (error: CdpCommandRejectedException) {
                    if (isUnsupportedCdpMethod(error.protocolCode, error.protocolMessage)) {
                        capabilities["page.fetchReplay"] = "unsupported"
                        return result("UNSUPPORTED", false)
                    }
                    return result("TARGET_UNAVAILABLE", false)
                }
                val contextId = world.optInt("executionContextId", -1)
                if (contextId <= 0) return result("UNSUPPORTED", false)
                val approvalToken = if (writeReplay) stringArg(request.args, "riskToken", 64) else null
                if (writeReplay) {
                    if (approvalToken == null) return result("NOT_AUTHORIZED", false)
                    val approval = replayApprovals.values.firstOrNull { it.riskToken == approvalToken }
                        ?: return result("NOT_AUTHORIZED", false)
                    val valid = approvalIsCurrent(approval) && approval.entryId == entryId && approval.captureId == store.captureId() &&
                        approval.captureId == request.args.optString("captureId") && approval.targetId == connection.targetId &&
                        approval.connectionEpoch == synchronized(lock) { connectionEpoch } && approval.method == method &&
                        approval.urlDigest == sha256(capturedUrl.toByteArray(StandardCharsets.UTF_8)) &&
                        approval.bodyDigest == source.optString("requestBodySha256") &&
                        approval.sessionGeneration == longArg(request.args, "sessionGeneration") &&
                        approval.controlGeneration == longArg(request.args, "controlGeneration") &&
                        SystemClock.elapsedRealtime() < approval.expiresAtElapsedMillis
                    if (!valid) {
                        replayApprovals.remove(approval.requestId, approval)
                        return result("NOT_AUTHORIZED", false)
                    }
                    if (!replayApprovals.remove(approval.requestId, approval)) return result("NOT_AUTHORIZED", false)
                } else if (request.args.has("riskToken")) return result("INVALID_ARGUMENT", false)
                if (!replayed.add(entryId)) return result("ALREADY_REPLAYED", false)
                val urlLiteral = JSONObject.quote(capturedUrl)
                val headers = replayHeaderAllowlist(source.optJSONObject("headers"))
                val options = JSONObject().put("method", method).put("credentials", "same-origin")
                    // Do not follow a captured same-origin URL to an unreviewed cross-origin redirect.
                    .put("redirect", "manual").put("headers", headers)
                if (writeReplay) options.put("body", source.getString("requestBody"))
                val replayReadBudget = minOf(10_000, remainingCdpTimeout(deadline))
                val expression = "(async()=>{const a=new AbortController(),t=setTimeout(()=>a.abort(),$replayReadBudget);try{const r=await fetch($urlLiteral,{...${options},signal:a.signal});if(r.body){const q=r.body.getReader();let n=0;try{while(true){const v=await q.read();if(v.done)break;n+=v.value.byteLength;if(n>65536){await q.cancel();break}}}finally{q.releaseLock()}}return {status:r.status,redirected:r.type==='opaqueredirect'}}finally{clearTimeout(t)}})()"
                val response = command(connection, "Runtime.evaluate", JSONObject()
                    .put("expression", expression)
                    .put("contextId", contextId)
                    .put("awaitPromise", true)
                    .put("returnByValue", true)
                    .put("timeout", remainingCdpTimeout(deadline)), deadline, auth, true)
                capabilities["page.fetchReplay"] = "supported"
                if (response.has("exceptionDetails")) return result("UNKNOWN", null,
                    JSONObject().put("nonRetryable", true).put("sideEffectsMayHaveOccurred", true))
                val value = response.optJSONObject("result")?.optJSONObject("value")
                if (value == null || !value.has("status")) return result("UNKNOWN", null,
                    JSONObject().put("nonRetryable", true).put("sideEffectsMayHaveOccurred", writeReplay))
                return result("OK", true, JSONObject()
                    .put("event", "replayCompleted")
                    .put("method", method)
                    .put("status", value.optInt("status"))
                    .put("redirected", value.optBoolean("redirected"))
                    .put("requestBodyBytes", source.optInt("requestBodyBytes"))
                    .put("requestBodySha256", source.optString("requestBodySha256"))
                    .put("resourceType", source.getString("resourceType")))
            }
        }
        return result("UNSUPPORTED", false)
    }

    private fun interact(connection: CdpConnection, args: JSONObject, deadline: Long, auth: () -> Boolean): JSONObject {
        return when (args.getString("kind")) {
            "key" -> {
                val key = args.getString("key")
                val normalized = normalizeKey(key) ?: return result("UNSUPPORTED", false)
                val params = JSONObject().put("key", normalized.first).put("code", normalized.second)
                command(connection, "Input.dispatchKeyEvent", JSONObject(params.toString()).put("type", "keyDown"), deadline, auth)
                command(connection, "Input.dispatchKeyEvent", JSONObject(params.toString()).put("type", "keyUp"), deadline, auth)
                result("OK", true)
            }
            "tap" -> {
                val selector = stringArg(args, "selector", 512)
                if (selector != null) {
                    val response = command(connection, "Runtime.evaluate", JSONObject()
                        .put("expression", buildTapPointExpression(selector))
                        .put("returnByValue", true)
                        .put("timeout", remainingCdpTimeout(deadline)), deadline, auth)
                    if (response.has("exceptionDetails")) return result("ACTION_REJECTED", false)
                    val pointValue = response.optJSONObject("result")?.opt("value") as? String
                        ?: return result("ACTION_REJECTED", false)
                    val point = runCatching { JSONObject(pointValue) }.getOrNull()
                        ?: return result("ACTION_REJECTED", false)
                    val x = boundedCoordinate(point.opt("x")) ?: return result("ACTION_REJECTED", false)
                    val y = boundedCoordinate(point.opt("y")) ?: return result("ACTION_REJECTED", false)
                    coordinateRejection(connection, listOf(x to y), deadline, auth)?.let { return it }
                    mouseClick(connection, x, y, deadline, auth)
                    return result("OK", true)
                }
                val x = numberArg(args, "x")!!; val y = numberArg(args, "y")!!
                coordinateRejection(connection, listOf(x to y), deadline, auth)?.let { return it }
                mouseClick(connection, x, y, deadline, auth)
                result("OK", true)
            }
            "drag" -> {
                val x = numberArg(args, "x")!!; val y = numberArg(args, "y")!!
                val tx = numberArg(args, "toX")!!; val ty = numberArg(args, "toY")!!
                coordinateRejection(connection, listOf(x to y, tx to ty), deadline, auth)?.let { return it }
                command(connection, "Input.dispatchMouseEvent", mouseParams("mousePressed", x, y).put("button", "left").put("clickCount", 1), deadline, auth)
                command(connection, "Input.dispatchMouseEvent", mouseParams("mouseMoved", tx, ty).put("button", "left").put("buttons", 1), deadline, auth)
                command(connection, "Input.dispatchMouseEvent", mouseParams("mouseReleased", tx, ty).put("button", "left"), deadline, auth)
                result("OK", true)
            }
            "select" -> {
                val selector = JSONObject.quote(args.getString("selector"))
                val value = JSONObject.quote(args.getString("value"))
                val expression = "(()=>{const e=document.querySelector($selector);if(!(e instanceof HTMLSelectElement))return false;e.value=$value;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return true})()"
                val response = command(connection, "Runtime.evaluate", JSONObject().put("expression", expression)
                    .put("returnByValue", true).put("timeout", remainingCdpTimeout(deadline)), deadline, auth)
                if (response.has("exceptionDetails")) return result("UNKNOWN", null,
                    JSONObject().put("nonRetryable", true).put("sideEffectsMayHaveOccurred", true))
                if (response.optJSONObject("result")?.optBoolean("value") == true) result("OK", true) else result("ACTION_REJECTED", false)
            }
            else -> result("UNSUPPORTED", false)
        }
    }

    private fun ensureConnected(deadline: Long, auth: () -> Boolean): CdpConnection {
        if (!permitted(false) || !authorized(auth, false)) error("NOT_AUTHORIZED")
        val existing = synchronized(lock) { connection }
        existing?.takeIf { !it.isClosed() }?.let { return it }
        if (existing != null) {
            handleConnectionLoss(existing, connectionLoss(existing))
        } else if (store.isRecording()) {
            interruptCaptureWithoutConnection(CdpConnectionLoss.TRANSPORT_CLOSED)
        }
        val url = currentPageUrl()?.takeIf { safeUrl(it) } ?: error("TARGET_UNAVAILABLE")
        val opened = CdpConnection.connect(Process.myPid(), url, deadline)
        if (!permitted(false) || !authorized(auth, false)) {
            opened.close()
            error("NOT_AUTHORIZED")
        }
        synchronized(lock) {
            if (closed.get()) { opened.close(); error("CLOSED") }
            connection?.takeIf { !it.isClosed() }?.let { opened.close(); return it }
            connection = opened
            connectionEpoch++
            eventCounts.clear()
            if (lastErrorCode != "CAPTURE_INTERRUPTED") lastErrorCode = null
            startEventPump(opened)
        }
        runCatching { command(opened, "Runtime.enable", JSONObject(), deadline, auth) }
        return opened
    }

    private fun enableWorkerTargets(active: CdpConnection, deadline: Long, auth: () -> Boolean) {
        try {
            command(active, "Target.setAutoAttach", JSONObject()
                .put("autoAttach", true)
                .put("waitForDebuggerOnStart", false)
                .put("flatten", true)
                .put("filter", JSONArray().put(JSONObject().put("type", "worker"))), deadline, auth)
            capabilities["Target.setAutoAttach"] = "supported"
            workerCoverage = "waiting_for_attached_target"
        } catch (error: Throwable) {
            capabilities["Target.setAutoAttach"] = if (error is CdpCommandRejectedException) "unsupported" else "unknown"
            workerCoverage = "unsupported"
        }
    }

    private fun detachWorkerTargets(active: CdpConnection, deadline: Long, auth: () -> Boolean) {
        targetSessions.keys.toList().forEach { session ->
            runCatching { command(active, "Network.disable", JSONObject(), deadline, auth, sessionId = session) }
            runCatching { command(active, "Target.detachFromTarget", JSONObject().put("sessionId", session), deadline, auth) }
        }
        targetSessions.clear()
        runCatching { command(active, "Target.setAutoAttach", JSONObject()
            .put("autoAttach", false).put("waitForDebuggerOnStart", false).put("flatten", true), deadline, auth) }
        workerCoverage = "detached"
    }

    private fun startEventPump(active: CdpConnection) {
        val thread = Thread({
            while (!closed.get() && synchronized(lock) { connection === active } && !active.isTransportClosed()) {
                val event = runCatching { active.nextEvent(500L) }.getOrNull()
                if (event != null) runCatching { onEvent(active, event) }
                val detached = active.detachedReason()
                if (detached != null) {
                    handleConnectionLoss(active, CdpConnectionLoss.INSPECTOR_DETACHED)
                    break
                }
            }
            if (active.isTransportClosed()) handleConnectionLoss(active, CdpConnectionLoss.TRANSPORT_CLOSED)
        }, "browser-devtools-event-reader").apply { isDaemon = true }
        eventPump = thread
        thread.start()
    }

    private fun onEvent(active: CdpConnection, event: CdpEvent) {
        captureStartGate.dispatchEvent {
            if (synchronized(lock) { connection !== active }) return@dispatchEvent
            if (event.method.startsWith("Network.") && synchronized(lock) {
                    !isCurrentCaptureEvent(allowedBackground(), event.receivedSequence, captureAfterEventSequence)
                }) return@dispatchEvent
            onEventLocked(active, event)
        }
    }

    private fun onEventLocked(active: CdpConnection, event: CdpEvent) {
        synchronized(lock) { eventCounts[event.method] = (eventCounts[event.method] ?: 0L) + 1L }
        markCaptureEventDrops(active)
        if (event.method == "Target.attachedToTarget") {
            onAttachedTarget(active, event)
            return
        }
        if (event.method == "Target.detachedFromTarget") {
            val detached = event.params.optString("sessionId")
            targetSessions.remove(detached)
            workerCoverage = if (targetSessions.isEmpty()) "detached" else "attached"
            return
        }
        if (event.sessionId != null) {
            if (targetSessions[event.sessionId] != "worker") return
            val requestId = event.params.opt("requestId") as? String
            if (requestId != null) event.params.put("requestId", scopedRequestId(event.sessionId, requestId))
        }
        when (event.method) {
            "Runtime.consoleAPICalled" -> rememberConsole(event.params)
            "Log.entryAdded" -> rememberLog(event.params)
            "Inspector.detached" -> handleConnectionLoss(active, CdpConnectionLoss.INSPECTOR_DETACHED)
            "Network.requestWillBeSent" -> {
                store.onRequest(event.params)
                val requestId = event.params.optString("requestId")
                if (store.capturesRequestBodies() && store.requestBodyCaptureState(requestId) == "pending") {
                    captureRequestBody(active, requestId)
                }
            }
            "Network.responseReceived" -> {
                store.onResponse(event.params)
                val response = event.params.optJSONObject("response") ?: return
                val mime = response.optString("mimeType").substringBefore(';').trim()
                if (store.capturesBodies() && mime.equals("text/event-stream", ignoreCase = true)) {
                    startSseStream(active, event.params.optString("requestId"))
                }
            }
            "Network.dataReceived" -> {
                val requestId = event.params.optString("requestId")
                val stream = streamingSse[requestId]
                if (stream != null && store.capturesBodies()) {
                    if (!stream.belongsTo(store.captureId())) {
                        streamingSse.remove(requestId, stream)
                        return
                    }
                    val dataLength = event.params.optInt("dataLength", 0)
                    when (stream.classify(event.receivedSequence, dataLength, event.params.has("data"))) {
                        SseDataDisposition.APPEND_PAYLOAD -> {
                            val encoded = event.params.opt("data") as? String
                            val bytes = encoded?.let {
                                runCatching { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }.getOrNull()
                            }
                            if (bytes == null || (dataLength > 0 && bytes.isEmpty()) ||
                                !store.appendStreamingBody(requestId, bytes)) {
                                store.truncateStreamingBody(requestId)
                                streamingSse.remove(requestId, stream)
                            }
                        }
                        SseDataDisposition.REJECT_STREAM -> {
                            store.truncateStreamingBody(requestId)
                            streamingSse.remove(requestId, stream)
                        }
                        SseDataDisposition.COVERED_BY_BUFFER, SseDataDisposition.EMPTY_METADATA -> Unit
                    }
                }
            }
            "Network.loadingFinished" -> {
                val requestId = event.params.optString("requestId")
                store.onFinished(requestId)
                val wasSse = streamingSse.remove(requestId) != null
                if (wasSse) store.finishStreamingBody(requestId)
                if (!wasSse && store.capturesBodies() && allowedBackground()) {
                    val entry = store.entryByCdpId(requestId)
                    if (entry != null && !entry.optString("mimeType").substringBefore(';').trim().equals("text/event-stream", true) &&
                        supportedBodyMime(entry.optString("mimeType")) &&
                        (entry.optLong("contentLength", -1L) < 0L || entry.optLong("contentLength") <= MAX_BODY_BYTES)
                    ) {
                        val deadline = SystemClock.elapsedRealtime() + 2500L
                        val bodyCaptureId = store.captureId()
                        try {
                            val route = requestRoute(requestId)
                            if (route.requestId.isEmpty()) error("WORKER_TARGET_DETACHED")
                            val body = active.call("Network.getResponseBody", JSONObject().put("requestId", route.requestId), deadline,
                                { allowedBackground() }, route.sessionId)
                            synchronized(lock) {
                                if (connection === active && allowedBackground() && store.capturesBodies() &&
                                    bodyCaptureId != null && store.captureId() == bodyCaptureId) {
                                    responseBodySupportState = if (store.saveResponseBody(requestId, body, bodyCaptureId)) {
                                        capabilities["Network.getResponseBody"] = "supported"
                                        "supported"
                                    } else "storage_failed"
                                }
                            }
                        } catch (error: Throwable) {
                            responseBodySupportState = if (mapError(error) == "UNSUPPORTED") "unsupported" else "unavailable"
                            if (responseBodySupportState == "unsupported") capabilities["Network.getResponseBody"] = "unsupported"
                            rememberCommandError(mapError(error))
                        }
                    }
                }
            }
            "Network.loadingFailed" -> {
                val requestId = event.params.optString("requestId")
                if (streamingSse.remove(requestId) != null) store.truncateStreamingBody(requestId)
                store.onFailed(requestId, event.params.optString("errorText"))
            }
            "Network.webSocketCreated",
            "Network.webSocketWillSendHandshakeRequest",
            "Network.webSocketHandshakeResponseReceived",
            "Network.webSocketFrameSent",
            "Network.webSocketFrameReceived",
            "Network.webSocketFrameError",
            "Network.webSocketClosed" -> {
                capabilities["Network.webSocketEvents"] = "supported"
                webSocketStore.onEvent(event.method, event.params)
            }
            "Fetch.requestPaused" -> handleFetchPause(active, event.params)
        }
    }

    private fun onAttachedTarget(active: CdpConnection, event: CdpEvent) {
        val sessionId = event.params.optString("sessionId")
        val info = event.params.optJSONObject("targetInfo") ?: return
        val type = info.optString("type")
        if (type == "service_worker") {
            serviceWorkerCoverage = "unsupported_shared_target_unverified"
            // A shared worker may belong to another page. Release only this CDP attachment and
            // never enable Network or consume events from it.
            val sessionId = event.params.optString("sessionId")
            if (event.sessionId == null && sessionId.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) {
                runCatching { command(active, "Target.detachFromTarget", JSONObject().put("sessionId", sessionId),
                    SystemClock.elapsedRealtime() + 500L, { allowedBackground() }) }
            }
            return
        }
        val pageUrl = currentPageUrl()
        val workerUrl = info.optString("url")
        if (type != "worker" || event.sessionId != null || sessionId.isBlank() || !sameOrigin(workerUrl, pageUrl)) {
            if (sessionId.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) {
                runCatching { command(active, "Target.detachFromTarget", JSONObject().put("sessionId", sessionId),
                    SystemClock.elapsedRealtime() + 500L, { true }) }
            }
            return
        }
        targetSessions[sessionId] = "worker"
        try {
            command(active, "Network.enable", JSONObject()
                .put("maxTotalBufferSize", MAX_TOTAL_BODY_BYTES)
                .put("maxResourceBufferSize", MAX_BODY_BYTES),
                SystemClock.elapsedRealtime() + 1500L, { allowedBackground() }, sessionId = sessionId)
            workerCoverage = "attached_network_enabled"
        } catch (_: Throwable) {
            targetSessions.remove(sessionId)
            workerCoverage = "unsupported"
            runCatching { command(active, "Target.detachFromTarget", JSONObject().put("sessionId", sessionId),
                SystemClock.elapsedRealtime() + 500L, { true }) }
        }
    }

    private fun captureRequestBody(active: CdpConnection, scopedRequestId: String) {
        if (requestBodyProbeCount.incrementAndGet() > MAX_REQUEST_BODY_PROBES) {
            store.markRequestBodyUnavailable(scopedRequestId, "probe_limit")
            return
        }
        val source = store.requestCaptureSource(scopedRequestId)
        if (source == null || !sameOrigin(source.optString("url"), currentPageUrl())) {
            store.markRequestBodyUnavailable(scopedRequestId, "entry_unavailable")
            return
        }
        val route = requestRoute(scopedRequestId)
        if (route.requestId.isEmpty()) {
            store.markRequestBodyUnavailable(scopedRequestId, "target_detached")
            return
        }
        try {
            val result = active.call("Network.getRequestPostData", JSONObject().put("requestId", route.requestId),
                SystemClock.elapsedRealtime() + REQUEST_BODY_READ_TIMEOUT_MILLIS,
                { allowedBackground() }, route.sessionId)
            capabilities["Network.getRequestPostData"] = "supported"
            val postData = result.opt("postData") as? String
            if (postData == null) {
                store.markRequestBodyUnavailable(scopedRequestId, "incomplete")
            } else if (!store.saveRequestBody(scopedRequestId, postData)) {
                store.markRequestBodyUnavailable(scopedRequestId, "size_limit")
            }
        } catch (error: Throwable) {
            if (mapError(error) == "UNSUPPORTED") capabilities["Network.getRequestPostData"] = "unsupported"
            store.markRequestBodyUnavailable(scopedRequestId,
                if (mapError(error) == "UNSUPPORTED") "unsupported" else "unavailable")
        }
    }

    private fun scopedRequestId(sessionId: String, requestId: String) = "worker:$sessionId:$requestId"

    private data class RequestRoute(val sessionId: String?, val requestId: String)

    private fun requestRoute(value: String): RequestRoute {
        if (!value.startsWith("worker:")) return RequestRoute(null, value)
        val separator = value.indexOf(':', startIndex = 7)
        if (separator < 0) return RequestRoute(null, value)
        val sessionId = value.substring(7, separator).takeIf { targetSessions.containsKey(it) }
            ?: return RequestRoute(null, "")
        return RequestRoute(sessionId, value.substring(separator + 1))
    }

    private fun connectionLoss(active: CdpConnection): CdpConnectionLoss = active.lossReason()

    private fun handleConnectionLoss(active: CdpConnection, loss: CdpConnectionLoss) {
        synchronized(lock) {
            if (connection !== active) return
            val transition = cdpCaptureLossState(store.isRecording(), loss)
            if (transition.stopRecording) {
                markWebSocketEventDrops(active)
                store.stop()
                webSocketStore.stop()
            }
            connection = null
            captureConnection = null
            targetSessions.clear()
            workerCoverage = "detached"
            networkDomainEnabled = transition.networkTrackingEnabled
            captureInterrupted = transition.captureInterrupted || captureInterrupted
            lastErrorCode = transition.lastErrorCode
            captureTimeout?.cancel(false)
            captureTimeout = null
            stopStreamingCaptures()
        }
        runCatching { active.close() }
    }

    private fun interruptCaptureWithoutConnection(loss: CdpConnectionLoss) {
        synchronized(lock) {
            val transition = cdpCaptureLossState(store.isRecording(), loss)
            if (transition.stopRecording) {
                markWebSocketEventDrops(captureConnection)
                store.stop()
                webSocketStore.stop()
            }
            captureConnection = null
            networkDomainEnabled = transition.networkTrackingEnabled
            captureInterrupted = transition.captureInterrupted || captureInterrupted
            lastErrorCode = transition.lastErrorCode
            captureTimeout?.cancel(false)
            captureTimeout = null
            stopStreamingCaptures()
        }
    }

    private fun rememberCommandError(code: String) {
        synchronized(lock) { lastErrorCode = cdpErrorAfterCommandFailure(lastErrorCode, code) }
    }

    private fun markCaptureEventDrops(active: CdpConnection? = synchronized(lock) { connection }) {
        if ((active?.droppedEventCount() ?: 0L) > 0L) store.markEventDrops()
    }

    private fun markWebSocketEventDrops(active: CdpConnection? = synchronized(lock) { captureConnection }) {
        val baseline = synchronized(lock) { captureAfterDroppedEventCount }
        val dropped = ((active?.droppedEventCount() ?: baseline) - baseline).coerceAtLeast(0L)
        webSocketStore.markEventDrops(dropped)
    }

    private fun startSseStream(active: CdpConnection, requestId: String) {
        if (requestId.isBlank()) return
        if (sseSupportState == "unsupported" || streamingSse.size >= MAX_SSE_STREAMS || !allowedBackground()) {
            store.truncateStreamingBody(requestId)
            return
        }
        val streamCaptureId = store.captureId()
        try {
            // The event worker waits for this CDP response; the socket reader stays independent.
            val route = requestRoute(requestId)
            if (route.requestId.isEmpty()) error("WORKER_TARGET_DETACHED")
            val reply = active.callFenced("Network.streamResourceContent", JSONObject().put("requestId", route.requestId),
                SystemClock.elapsedRealtime() + 1500L, { allowedBackground() }, route.sessionId)
            val result = reply.result
            val encoded = result.opt("bufferedData") as? String ?: run {
                store.truncateStreamingBody(requestId)
                sseSupportState = "unavailable"
                return
            }
            val buffered = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }.getOrNull() ?: run {
                store.truncateStreamingBody(requestId)
                sseSupportState = "unavailable"
                return
            }
            synchronized(lock) {
                if (connection !== active || !allowedBackground() || !store.capturesBodies() ||
                    streamCaptureId == null || store.captureId() != streamCaptureId) return
                if (buffered.isNotEmpty() && !store.appendStreamingBody(requestId, buffered)) {
                    store.truncateStreamingBody(requestId)
                    return
                }
                streamingSse[requestId] = SseStreamWindow(streamCaptureId, reply.eventSequenceFence, buffered.size)
                sseSupportState = "supported"
                capabilities["Network.streamResourceContent"] = "supported"
            }
        } catch (error: Throwable) {
            store.truncateStreamingBody(requestId)
            sseSupportState = if (mapError(error) == "UNSUPPORTED") "unsupported" else "unavailable"
            if (sseSupportState == "unsupported") capabilities["Network.streamResourceContent"] = "unsupported"
            rememberCommandError(if (sseSupportState == "unsupported") "UNSUPPORTED" else mapError(error))
        }
    }

    private fun stopStreamingCaptures() {
        streamingSse.keys.toList().forEach(store::truncateStreamingBody)
        streamingSse.clear()
    }

    private fun handleFetchPause(active: CdpConnection, params: JSONObject) {
        val cdpId = params.optString("requestId").takeIf { it.isNotBlank() } ?: return
        if (paused.size >= MAX_PAUSED_REQUESTS || !allowedNetworkMutation()) {
            if (!active.continueAfterRevoke(cdpId, SystemClock.elapsedRealtime() + 700L, params.has("responseStatusCode"))) {
                active.close()
            }
            return
        }
        val request = params.optJSONObject("request") ?: JSONObject()
        val id = "pause-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val pausedRequest = PausedFetch(id, cdpId, JSONObject(params.toString()), SystemClock.elapsedRealtime() + MAX_PAUSE_MILLIS)
        paused[id] = pausedRequest
        pausedRequest.timeout = scheduler.schedule({ releasePaused(active, pausedRequest) }, MAX_PAUSE_MILLIS, TimeUnit.MILLISECONDS)
        val responseStage = params.has("responseStatusCode")
        val rule = synchronized(lock) { networkRules.firstOrNull { matchesRule(it, request, responseStage) }?.let { JSONObject(it.toString()) } }
        if (rule == null || !allowedNetworkMutation()) {
            releasePaused(active, pausedRequest)
            return
        }
        val continueMethod = if (responseStage) "Fetch.continueResponse" else "Fetch.continueRequest"
        when (rule.optString("action")) {
            "continue" -> resolveFetchCommand(active, pausedRequest, continueMethod, JSONObject().put("requestId", cdpId))
            "block" -> resolveFetchCommand(active, pausedRequest, "Fetch.failRequest", JSONObject().put("requestId", cdpId).put("errorReason", "BlockedByClient"))
            "modify" -> runCatching { applyRule(active, pausedRequest, rule) }
                .onFailure {
                    rememberCommandError(mapError(it))
                    resolveFetchCommand(active, pausedRequest, continueMethod, JSONObject().put("requestId", cdpId))
                }
            "fulfill" -> runCatching { applyFulfillRule(active, pausedRequest, rule) }
                .onFailure {
                    rememberCommandError(mapError(it))
                    resolveFetchCommand(active, pausedRequest, continueMethod, JSONObject().put("requestId", cdpId))
                }
            else -> releasePaused(active, pausedRequest)
        }
    }

    private fun applyRule(active: CdpConnection, request: PausedFetch, rule: JSONObject) {
        val responseStage = request.params.has("responseStatusCode")
        if (responseStage) {
            if (rule.optString("action") == "fulfill") applyResponseFulfill(active, request, rule.optInt("responseCode", -1), rule.optJSONObject("headers") ?: JSONObject(), rule.optString("body"))
            else applyResponseModification(active, request, rule.optInt("responseCode", -1), rule.optJSONObject("headers") ?: JSONObject(), rule.optString("body").takeIf { rule.has("body") })
            return
        }
        val cdpRequest = request.params.optJSONObject("request") ?: JSONObject()
        val params = JSONObject().put("requestId", request.cdpId)
        rule.optString("url").takeIf { it.isNotBlank() }?.let { params.put("url", it) }
        val postData = rule.optString("postData").takeIf { rule.has("postData") }
        if (postData != null) params.put("postData", encodeBinary(postData.toByteArray(StandardCharsets.UTF_8)))
        val overrides = rule.optJSONObject("headers")
        if (overrides != null || postData != null) {
            val headers = interceptedRequestHeaders(cdpRequest.optJSONObject("headers") ?: JSONObject(), overrides ?: JSONObject(), postData != null)
            params.put("headers", headerEntries(headers))
        }
        resolveFetchCommand(active, request, "Fetch.continueRequest", params)
    }

    private fun applyFulfillRule(active: CdpConnection, request: PausedFetch, rule: JSONObject) {
        if (request.params.has("responseStatusCode")) {
            applyResponseFulfill(active, request, rule.optInt("responseCode", -1), rule.optJSONObject("headers") ?: JSONObject(), rule.optString("body"))
            return
        }
        val code = rule.optInt("responseCode", 200).coerceIn(200, 599)
        val responseHeaders = rule.optJSONObject("headers") ?: JSONObject()
        val headers = headerEntries(responseHeaders)
        val body = rule.getString("body").toByteArray(StandardCharsets.UTF_8)
        resolveFetchCommand(active, request, "Fetch.fulfillRequest", JSONObject()
            .put("requestId", request.cdpId).put("responseCode", code).put("responseHeaders", headers).put("body", encodeBinary(body)))
    }

    private fun applyResponseModification(active: CdpConnection, request: PausedFetch, statusOverride: Int, overrides: JSONObject, replacementBody: String?) {
        val originalCode = request.params.optInt("responseStatusCode", 200)
        val originalHeaders = request.params.optJSONArray("responseHeaders") ?: JSONArray()
        val params = JSONObject().put("requestId", request.cdpId)
        if (statusOverride in 200..599 || overrides.length() > 0) {
            params.put("responseCode", if (statusOverride in 200..599) statusOverride else originalCode)
            params.put("responseHeaders", mergeResponseHeaders(originalHeaders, overrides))
            request.params.optString("responseStatusText").takeIf { it.isNotBlank() && statusOverride !in 200..599 }
                ?.let { params.put("responsePhrase", it.take(128)) }
        }
        if (replacementBody == null) {
            resolveFetchCommand(active, request, "Fetch.continueResponse", params)
            return
        }
        val original = active.call("Fetch.getResponseBody", JSONObject().put("requestId", request.cdpId),
            minOf(request.deadline, SystemClock.elapsedRealtime() + 1500L)) { allowedNetworkMutation() }
        val originalBytes = decodeBinary(original.optString("body"), original.optBoolean("base64Encoded"))
        if (originalBytes.size > MAX_INTERCEPTED_BODY_BYTES) {
            resolveFetchCommand(active, request, "Fetch.continueResponse", JSONObject().put("requestId", request.cdpId))
            return
        }
        val body = replacementBody.toByteArray(StandardCharsets.UTF_8)
        val code = if (statusOverride in 200..599) statusOverride else originalCode
        val responseHeaders = mergeResponseHeaders(originalHeaders, overrides, body.size)
        resolveFetchCommand(active, request, "Fetch.fulfillRequest", JSONObject()
            .put("requestId", request.cdpId)
            .put("responseCode", code)
            .put("responseHeaders", responseHeaders)
            .put("body", encodeBinary(body)))
    }

    private fun applyResponseFulfill(active: CdpConnection, request: PausedFetch, statusOverride: Int, overrides: JSONObject, replacementBody: String) {
        val original = active.call("Fetch.getResponseBody", JSONObject().put("requestId", request.cdpId),
            minOf(request.deadline, SystemClock.elapsedRealtime() + 1500L)) { allowedNetworkMutation() }
        val originalBytes = decodeBinary(original.optString("body"), original.optBoolean("base64Encoded"))
        if (originalBytes.size > MAX_INTERCEPTED_BODY_BYTES) {
            resolveFetchCommand(active, request, "Fetch.continueResponse", JSONObject().put("requestId", request.cdpId))
            return
        }
        val originalHeaders = request.params.optJSONArray("responseHeaders") ?: JSONArray()
        val body = replacementBody.toByteArray(StandardCharsets.UTF_8)
        val code = if (statusOverride in 200..599) statusOverride else request.params.optInt("responseStatusCode", 200)
        resolveFetchCommand(active, request, "Fetch.fulfillRequest", JSONObject()
            .put("requestId", request.cdpId)
            .put("responseCode", code)
            .put("responseHeaders", mergeResponseHeaders(originalHeaders, overrides, body.size))
            .put("body", encodeBinary(body)))
    }

    private fun mergeResponseHeaders(original: JSONArray, overrides: JSONObject, bodySize: Int? = null): JSONArray {
        val values = LinkedHashMap<String, JSONObject>()
        for (index in 0 until original.length()) {
            val item = original.optJSONObject(index) ?: continue
            val name = item.optString("name").take(128)
            if (name.isNotBlank()) values[name.lowercase(Locale.ROOT)] = JSONObject().put("name", name).put("value", item.optString("value").take(1024))
        }
        val keys = overrides.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            values[key.lowercase(Locale.ROOT)] = JSONObject().put("name", key).put("value", overrides.getString(key))
        }
        if (bodySize != null) {
            values.remove("content-encoding")
            values.remove("transfer-encoding")
            values.remove("content-length")
            values["content-length"] = JSONObject().put("name", "content-length").put("value", bodySize.toString())
        }
        return JSONArray(values.values.toList())
    }

    private fun encodeBinary(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decodeBinary(value: String, base64: Boolean): ByteArray =
        if (base64) android.util.Base64.decode(value, android.util.Base64.DEFAULT) else value.toByteArray(StandardCharsets.UTF_8)

    private fun resolvePaused(
        pausedRequest: PausedFetch,
        decision: String,
        args: JSONObject,
        deadline: Long,
        auth: () -> Boolean,
    ): JSONObject {
        val active = synchronized(lock) { connection } ?: return result("TARGET_UNAVAILABLE", false)
        val responseStage = pausedRequest.params.has("responseStatusCode")
        if (responseStage && (args.has("url") || args.has("method") || args.has("postData"))) return result("INVALID_ARGUMENT", false)
        if (!responseStage && decision == "modify" && (args.has("body") || args.has("responseCode"))) return result("INVALID_ARGUMENT", false)
        if (decision in setOf("continue", "block") && !onlyKeys(args, setOf("pausedId", "decision", "action"))) {
            return result("INVALID_ARGUMENT", false)
        }
        if (decision == "fulfill" && (args.has("url") || args.has("method") || args.has("postData"))) return result("INVALID_ARGUMENT", false)
        if (!pausedRequest.resolved.compareAndSet(false, true)) return result("ALREADY_RESOLVED", false)
        paused.remove(pausedRequest.id)
        pausedRequest.timeout?.cancel(false)
        return try {
            val params = JSONObject().put("requestId", pausedRequest.cdpId)
            val method = if (decision == "block") {
                params.put("errorReason", "BlockedByClient")
                "Fetch.failRequest"
            } else if (responseStage) {
                when (decision) {
                    "continue" -> "Fetch.continueResponse"
                    "modify", "fulfill" -> {
                        val originalHeaders = pausedRequest.params.optJSONArray("responseHeaders") ?: JSONArray()
                        val overrides = args.optJSONObject("headers") ?: JSONObject()
                        val originalCode = pausedRequest.params.optInt("responseStatusCode", 200)
                        val code = intArg(args, "responseCode", originalCode).let { if (it in 200..599) it else originalCode }
                        if (decision == "fulfill" || args.has("body")) {
                            val original = command(active, "Fetch.getResponseBody", JSONObject().put("requestId", pausedRequest.cdpId),
                                minOf(deadline, pausedRequest.deadline), auth)
                            val originalBytes = decodeBinary(original.optString("body"), original.optBoolean("base64Encoded"))
                            if (originalBytes.size > MAX_INTERCEPTED_BODY_BYTES) error("UNSUPPORTED")
                            val body = args.getString("body").toByteArray(StandardCharsets.UTF_8)
                            params.put("responseCode", code)
                                .put("responseHeaders", mergeResponseHeaders(originalHeaders, overrides, body.size))
                                .put("body", encodeBinary(body))
                            "Fetch.fulfillRequest"
                        } else {
                            if (args.has("responseCode") || overrides.length() > 0) {
                                params.put("responseCode", code)
                                    .put("responseHeaders", mergeResponseHeaders(originalHeaders, overrides))
                                pausedRequest.params.optString("responseStatusText").takeIf { it.isNotBlank() && !args.has("responseCode") }
                                    ?.let { params.put("responsePhrase", it.take(128)) }
                            }
                            "Fetch.continueResponse"
                        }
                    }
                    else -> error("INVALID_ARGUMENT")
                }
            } else {
                when (decision) {
                    "continue" -> "Fetch.continueRequest"
                    "modify" -> {
                        if (args.has("url")) params.put("url", args.getString("url"))
                        if (args.has("method")) params.put("method", args.getString("method"))
                        val postData = args.optString("postData").takeIf { args.has("postData") }
                        if (postData != null) params.put("postData", encodeBinary(postData.toByteArray(StandardCharsets.UTF_8)))
                        if (args.has("headers") || postData != null) {
                            val request = pausedRequest.params.optJSONObject("request") ?: JSONObject()
                            val headers = interceptedRequestHeaders(
                                request.optJSONObject("headers") ?: JSONObject(),
                                args.optJSONObject("headers") ?: JSONObject(),
                                postData != null,
                            )
                            params.put("headers", headerEntries(headers))
                        }
                        "Fetch.continueRequest"
                    }
                    "fulfill" -> {
                        val headers = headerEntries(args.optJSONObject("headers") ?: JSONObject())
                        params.put("responseCode", intArg(args, "responseCode", 200))
                            .put("responseHeaders", headers)
                            .put("body", encodeBinary(args.getString("body").toByteArray(StandardCharsets.UTF_8)))
                        "Fetch.fulfillRequest"
                    }
                    else -> error("INVALID_ARGUMENT")
                }
            }
            command(active, method, params, deadline, auth, true)
            result("OK", true)
        } catch (error: Throwable) {
            val code = mapError(error)
            if (!active.isClosed() && !active.continueAfterRevoke(
                    pausedRequest.cdpId,
                    minOf(deadline, SystemClock.elapsedRealtime() + 700L),
                    responseStage,
                )) active.close()
            result(code, if (code == "UNKNOWN") null else false)
        }
    }

    private fun resolveFetchCommand(active: CdpConnection, pendingRequest: PausedFetch, method: String, params: JSONObject) {
        if (!pendingRequest.resolved.compareAndSet(false, true)) return
        paused.remove(pendingRequest.id)
        pendingRequest.timeout?.cancel(false)
        val completed = runCatching {
            active.call(method, params, minOf(pendingRequest.deadline, SystemClock.elapsedRealtime() + 800L)) { allowedNetworkMutation() }
        }
        if (completed.isFailure && !active.isClosed()) {
            completed.exceptionOrNull()?.let { rememberCommandError(mapError(it)) }
            val released = active.continueAfterRevoke(pendingRequest.cdpId,
                minOf(pendingRequest.deadline, SystemClock.elapsedRealtime() + 700L), pendingRequest.params.has("responseStatusCode"))
            if (!released) active.close()
        }
    }

    private fun releaseAllPaused(active: CdpConnection) {
        releasePausedBatch(active, paused.values.toList())
    }

    private fun releasePausedBatch(active: CdpConnection?, requests: List<PausedFetch>) {
        val deadline = SystemClock.elapsedRealtime() + 700L
        requests.forEach { releasePaused(active, it, deadline) }
    }

    private fun releasePaused(
        active: CdpConnection?,
        request: PausedFetch,
        deadline: Long = SystemClock.elapsedRealtime() + 700L,
    ) {
        if (!request.resolved.compareAndSet(false, true)) return
        paused.remove(request.id)
        request.timeout?.cancel(false)
        if (active != null && !active.isClosed() && !active.continueAfterRevoke(
                request.cdpId,
                deadline,
                request.params.has("responseStatusCode"),
            )) active.close()
    }

    private fun rememberConsole(params: JSONObject) {
        val type = params.optString("type").take(32)
        val args = JSONArray()
        params.optJSONArray("args")?.let { values ->
            for (index in 0 until minOf(values.length(), 8)) {
                val item = values.optJSONObject(index) ?: continue
                val value = item.opt("value") ?: item.opt("description") ?: item.opt("type")
                if (value != null && value !== JSONObject.NULL) args.put(redactText(truncateUtf8(value.toString(), 512).first))
            }
        }
        rememberConsoleEntry(JSONObject()
            .put("level", type)
            .put("text", redactText(args.join(" ").take(2048)))
            .put("timestamp", params.optDouble("timestamp")))
    }

    private fun rememberLog(params: JSONObject) {
        val entry = params.optJSONObject("entry") ?: return
        rememberConsoleEntry(JSONObject()
            .put("level", entry.optString("level").take(32))
            .put("text", redactText(truncateUtf8(entry.optString("text"), 2048).first))
            .put("source", entry.optString("source").take(64))
            .put("timestamp", entry.optDouble("timestamp")))
    }

    private fun rememberConsoleEntry(entry: JSONObject) = synchronized(lock) {
        console.addLast(entry)
        while (console.size > MAX_CONSOLE_ENTRIES) { console.removeFirst(); consoleDropped++ }
    }

    private fun command(
        active: CdpConnection,
        method: String,
        params: JSONObject,
        deadline: Long,
        auth: () -> Boolean,
        mutation: Boolean = false,
        sessionId: String? = null,
    ): JSONObject {
        if (!permitted(mutation) || !authorized(auth, mutation)) error("NOT_AUTHORIZED")
        return try {
            val result = active.call(method, params, deadline,
                { permitted(mutation) && authorized(auth, mutation) }, sessionId)
            capabilityFor(method)?.let { capabilities[it] = "supported" }
            result
        } catch (error: Throwable) {
            if (error is CdpCommandRejectedException && isUnsupportedCdpMethod(error.protocolCode, error.protocolMessage)) {
                capabilityFor(method)?.let { capabilities[it] = "unsupported" }
            }
            throw error
        }
    }

    private fun capabilityFor(method: String): String? = when (method) {
        "Target.setAutoAttach" -> method
        "Network.enable", "Network.getResponseBody", "Network.getRequestPostData", "Network.streamResourceContent" -> method
        "Fetch.enable" -> method
        "Runtime.evaluate" -> method
        else -> null
    }

    private fun remainingCdpTimeout(deadlineElapsedMillis: Long): Int {
        val remaining = deadlineElapsedMillis - SystemClock.elapsedRealtime()
        check(remaining > 0L) { "DEADLINE_EXPIRED" }
        return remaining.coerceAtMost(MAX_CDP_TIMEOUT_MILLIS).toInt()
    }

    private fun allowedBackground(): Boolean = backgroundAllowed.get() && safePermissions().debugEnabled

    private fun allowedNetworkMutation(): Boolean = backgroundAllowed.get() && safePermissions().debugEnabled &&
        safePermissions().networkMutationEnabled

    private fun permitted(mutation: Boolean): Boolean {
        val permissions = safePermissions()
        return permissions.debugEnabled && (!mutation || permissions.networkMutationEnabled)
    }

    private fun requireNetworkMutation() { if (!permitted(true)) error("NOT_AUTHORIZED") }

    private fun authorized(auth: () -> Boolean, mutation: Boolean): Boolean =
        permitted(mutation) && runCatching(auth).getOrDefault(false)

    private fun safePermissions(): BrowserDevToolsPermissions =
        runCatching(permission).getOrDefault(BrowserDevToolsPermissions(false, false))

    private fun closeConnection() {
        axActionReferences.clear()
        markCaptureEventDrops()
        markWebSocketEventDrops()
        stopStreamingCaptures()
        val old = synchronized(lock) {
            store.stop()
            webSocketStore.stop()
            val value = connection
            connection = null
            captureConnection = null
            targetSessions.clear()
            workerCoverage = "detached"
            networkDomainEnabled = false
            eventPump?.interrupt()
            eventPump = null
            value
        }
        runCatching { old?.close() }
    }

    private fun result(code: String, executed: Boolean?, data: JSONObject = JSONObject()): JSONObject = JSONObject()
        .put("code", code)
        .put("executed", executed ?: JSONObject.NULL)
        .put("data", data)

    private fun mapError(error: Throwable): String {
        return mapBrowserDevToolsError(error)
    }

    private fun validateRules(rules: JSONArray?): Boolean {
        if (rules == null || rules.length() > MAX_RULES) return false
        for (index in 0 until rules.length()) {
            val rule = rules.optJSONObject(index) ?: return false
            val allowed = setOf("urlPattern", "stage", "method", "action", "headers", "url", "postData", "body", "responseCode")
            if (!onlyKeys(rule, allowed)) return false
            if (stringArg(rule, "urlPattern", 256).isNullOrBlank() ||
                stringArg(rule, "stage", 16) !in setOf("request", "response") ||
                stringArg(rule, "action", 16) !in setOf("continue", "block", "modify", "fulfill")
            ) return false
            if (rule.has("method") && !SAFE_HTTP_METHOD.matches(stringArg(rule, "method", 16) ?: "")) return false
            if (rule.has("url") && !safeUrl(stringArg(rule, "url", 2048))) return false
            if (rule.has("postData") && stringArg(rule, "postData", 8192) == null) return false
            if (rule.has("body") && stringArg(rule, "body", 8192) == null) return false
            if (rule.has("responseCode") && intArg(rule, "responseCode", -1) !in 200..599) return false
            if (rule.has("headers") && !validHeaders(rule.optJSONObject("headers"))) return false
            val responseStage = rule.optString("stage") == "response"
            val action = rule.optString("action")
            if (responseStage && (rule.has("url") || rule.has("postData"))) return false
            if (action == "fulfill" && !rule.has("body")) return false
            if (!responseStage && action == "modify" && (rule.has("body") || rule.has("responseCode"))) return false
            if (action in setOf("continue", "block") &&
                listOf("headers", "url", "postData", "body", "responseCode").any { rule.has(it) }
            ) return false
            if (action == "fulfill" && (rule.has("url") || rule.has("postData"))) return false
        }
        return true
    }

    private fun validHeaders(headers: JSONObject?): Boolean {
        if (headers == null || headers.length() > 16) return false
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!key.matches(Regex("^[A-Za-z0-9-]{1,128}$")) || key.lowercase(Locale.ROOT) in FORBIDDEN_HEADERS) return false
            if (headers.opt(key) !is String || (headers.opt(key) as String).length > 1024) return false
        }
        return true
    }

    private fun headerEntries(headers: JSONObject): JSONArray {
        val result = JSONArray()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.opt(key)
            if (value is String || value is Number || value is Boolean) {
                result.put(JSONObject().put("name", key).put("value", value.toString()))
            }
        }
        return result
    }

    private fun matchesRule(rule: JSONObject, request: JSONObject, responseStage: Boolean): Boolean {
        if ((rule.optString("stage") == "response") != responseStage) return false
        val method = rule.optString("method")
        if (method.isNotBlank() && method != request.optString("method")) return false
        val pattern = rule.optString("urlPattern")
        return runCatching { compileUrlPattern(pattern).matches(request.optString("url")) }.getOrDefault(false)
    }

    private fun pausedInfo(item: PausedFetch): JSONObject {
        val request = item.params.optJSONObject("request") ?: JSONObject()
        return JSONObject()
            .put("pausedId", item.id)
            .put("url", sanitizeUrl(request.optString("url")))
            .put("method", request.optString("method"))
            .put("stage", if (item.params.has("responseStatusCode")) "response" else "request")
            .put("expiresInMillis", (item.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun coordinateRejection(
        connection: CdpConnection,
        points: List<Pair<Double, Double>>,
        deadline: Long,
        auth: () -> Boolean,
    ): JSONObject? {
        val viewport = inputViewport(command(connection, "Page.getLayoutMetrics", JSONObject(), deadline, auth))
            ?: return result("ACTION_REJECTED", false, JSONObject()
                .put("stage", "coordinate_validation").put("reason", "VIEWPORT_UNAVAILABLE")
                .put("nextAction", "observe_current_viewport"))
        if (points.all { viewport.contains(it.first, it.second) }) return null
        return result("ACTION_REJECTED", false, JSONObject()
            .put("stage", "coordinate_validation").put("reason", "OUTSIDE_VISUAL_VIEWPORT")
            .put("coordinateSpace", "main_frame_css_pixels")
            .put("viewport", JSONObject().put("x", viewport.left).put("y", viewport.top)
                .put("width", viewport.width).put("height", viewport.height))
            .put("nextAction", "observe_current_viewport"))
    }

    private fun mouseClick(connection: CdpConnection, x: Double, y: Double, deadline: Long, auth: () -> Boolean) {
        command(connection, "Input.dispatchMouseEvent", mouseParams("mousePressed", x, y).put("button", "left").put("clickCount", 1), deadline, auth)
        command(connection, "Input.dispatchMouseEvent", mouseParams("mouseReleased", x, y).put("button", "left"), deadline, auth)
    }

    private fun mouseParams(type: String, x: Double, y: Double) = JSONObject().put("type", type).put("x", x).put("y", y)

    private fun normalizeKey(value: String): Pair<String, String>? {
        val named = mapOf(
            "Enter" to ("Enter" to "Enter"), "Tab" to ("Tab" to "Tab"),
            "Escape" to ("Escape" to "Escape"), "Backspace" to ("Backspace" to "Backspace"),
            "ArrowUp" to ("ArrowUp" to "ArrowUp"), "ArrowDown" to ("ArrowDown" to "ArrowDown"),
            "ArrowLeft" to ("ArrowLeft" to "ArrowLeft"), "ArrowRight" to ("ArrowRight" to "ArrowRight"),
            "Space" to (" " to "Space"),
        )
        named[value]?.let { return it }
        if (value.length == 1 && value[0].isLetterOrDigit()) return value to if (value[0].isUpperCase()) "Key$value" else "Key${value.uppercase(Locale.ROOT)}"
        return null
    }

    private fun stringArg(obj: JSONObject, key: String, maxLength: Int): String? {
        if (!obj.has(key)) return null
        val value = obj.opt(key) as? String ?: return null
        return value.takeIf { it.length <= maxLength }
    }

    private fun intArg(obj: JSONObject, key: String, default: Int): Int {
        if (!obj.has(key)) return default
        val value = obj.opt(key)
        if (value !is Int && value !is Long) return Int.MIN_VALUE
        val long = (value as Number).toLong()
        return if (long in Int.MIN_VALUE..Int.MAX_VALUE) long.toInt() else Int.MIN_VALUE
    }

    private fun longArg(obj: JSONObject, key: String): Long? {
        if (!obj.has(key)) return null
        val value = obj.opt(key)
        if (value !is Int && value !is Long) return null
        return (value as Number).toLong().takeIf { it >= 0L }
    }

    private fun numberArg(obj: JSONObject, key: String): Double? {
        if (!obj.has(key)) return null
        val number = obj.opt(key) as? Number ?: return null
        return boundedCoordinate(number)
    }

    private fun boolArg(obj: JSONObject, key: String, default: Boolean?): Boolean? {
        if (!obj.has(key)) return default
        return obj.opt(key) as? Boolean
    }

    private fun onlyKeys(obj: JSONObject, allowed: Set<String>): Boolean {
        val keys = obj.keys()
        while (keys.hasNext()) if (keys.next() !in allowed) return false
        return true
    }

    private fun truncateUtf8(value: String, limit: Int): Pair<String, Boolean> {
        if (value.toByteArray(StandardCharsets.UTF_8).size <= limit) return value to false
        val output = StringBuilder()
        var size = 0
        for (codePoint in value.codePoints().toArray()) {
            val piece = String(Character.toChars(codePoint))
            val bytes = piece.toByteArray(StandardCharsets.UTF_8).size
            if (size + bytes > limit) break
            output.append(piece); size += bytes
        }
        return output.toString() to true
    }

    private fun safeUrl(value: String?): Boolean = runCatching {
        val uri = java.net.URI(value ?: return false)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    private fun sameOrigin(left: String?, right: String?): Boolean = runCatching {
        val a = java.net.URI(left ?: return false)
        val b = java.net.URI(right ?: return false)
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b) &&
            a.rawUserInfo == null && b.rawUserInfo == null && a.scheme.lowercase(Locale.ROOT) in setOf("http", "https")
    }.getOrDefault(false)

    private fun effectivePort(uri: java.net.URI): Int = if (uri.port >= 0) uri.port else if (uri.scheme.equals("https", true)) 443 else 80

    private fun sanitizeUrl(value: String): String = runCatching {
        val uri = java.net.URI(value)
        java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    }.getOrDefault("")

    private fun supportedBodyMime(value: String): Boolean {
        val mime = value.lowercase(Locale.ROOT)
        return mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || mime.contains("javascript")
    }

    private fun replayHeaderAllowlist(captured: JSONObject?): JSONObject {
        val result = JSONObject()
        if (captured == null) return result
        val allow = setOf("accept", "content-type", "accept-language")
        val keys = captured.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = captured.opt(key) as? String ?: continue
            if (key.lowercase(Locale.ROOT) in allow && value.length <= 512) result.put(key, value)
        }
        return result
    }

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun redactText(value: String): String = value
        .replace(Regex("(?i)(authorization|proxy-authorization|cookie|set-cookie|token|secret|api[_-]?key|password)(\\s*[:=]\\s*)([^\\s,;]+)"), "\$1\$2[redacted]")
        .replace(Regex("(?i)(https?://[^\\s?#]+)\\?[^\\s#]*"), "\$1?[redacted]")

    companion object {
        private const val MAX_DATA_BYTES = 12 * 1024
        private const val MAX_CDP_TIMEOUT_MILLIS = 60_000L
        private const val MAX_CONSOLE_ENTRIES = 200
        private const val MAX_RULES = 8
        private const val MAX_CAPTURE_MILLIS = 5 * 60 * 1000L
        private const val MAX_PAUSE_MILLIS = 5_000L
        private const val MAX_PAUSED_REQUESTS = 32
        private const val MAX_SSE_STREAMS = 8
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_TOTAL_BODY_BYTES = 8 * 1024 * 1024
        private const val MAX_INTERCEPTED_BODY_BYTES = 1024 * 1024
        private const val MAX_REQUEST_BODY_PROBES = 64L
        private const val REQUEST_BODY_READ_TIMEOUT_MILLIS = 400L
        private const val REPLAY_APPROVAL_TTL_MILLIS = 120_000L
        private const val MAX_REPLAY_APPROVALS = 16
        private const val MAX_CAPTURE_PIXELS = 16_777_216L
        private const val MAX_CAPTURE_PNG_BYTES = 1_350_000
        private const val MAX_CAPTURE_BASE64_CHARS = 1_800_000
        private val SAFE_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val RISK_TOKEN = Regex("^[A-Fa-f0-9]{32}$")
        private val SAFE_HTTP_METHOD = Regex("^[A-Za-z][A-Za-z0-9_-]{0,15}$")
        private val FORBIDDEN_HEADERS = setOf("cookie", "set-cookie", "authorization", "proxy-authorization", "host", "origin", "referer")
    }
}

internal fun mapBrowserDevToolsError(error: Throwable): String {
    if (error is CdpCommandRejectedException) {
        return if (isUnsupportedCdpMethod(error.protocolCode, error.protocolMessage)) {
            "UNSUPPORTED"
        } else "CDP_COMMAND_FAILED"
    }
    val messages = generateSequence(error as Throwable?) { it.cause }
        .take(8)
        .mapNotNull { it.message }
        .joinToString(" | ")
    return when {
        messages.contains("NOT_AUTHORIZED") -> "NOT_AUTHORIZED"
        messages.contains("DEADLINE_EXPIRED") || generateSequence(error as Throwable?) { it.cause }
            .take(8).any { it is java.util.concurrent.TimeoutException } -> "UNKNOWN"
        messages.contains("AMBIGUOUS_TARGET") -> "AMBIGUOUS_TARGET"
        messages.contains("CDP_CONNECTION_CLOSED") || messages.contains("CDP_CLOSED") || messages.contains("CLOSED") -> "CLOSED"
        messages.contains("TARGET_UNAVAILABLE") -> "TARGET_UNAVAILABLE"
        messages.contains("UNSUPPORTED") -> "UNSUPPORTED"
        messages.contains("BUSY") -> "BUSY"
        else -> "UNKNOWN"
    }
}
