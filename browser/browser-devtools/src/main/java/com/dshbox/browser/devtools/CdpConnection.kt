package com.dshbox.browser.devtools

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val CDP_IDLE_READ_TIMEOUT_MILLIS = 0

internal data class CdpEvent(val method: String, val params: JSONObject, val wireBytes: Int, val receivedSequence: Long = 0L, val sessionId: String? = null)
internal fun isCurrentCaptureEvent(authorized: Boolean, receivedSequence: Long, afterSequence: Long): Boolean =
    authorized && receivedSequence > afterSequence
internal enum class CdpConnectionLoss { INSPECTOR_DETACHED, TRANSPORT_CLOSED }
internal data class CdpCaptureLossState(
    val stopRecording: Boolean,
    val networkTrackingEnabled: Boolean,
    val captureInterrupted: Boolean,
    val lastErrorCode: String,
)

internal fun cdpCaptureLossState(recording: Boolean, loss: CdpConnectionLoss) = CdpCaptureLossState(
    stopRecording = recording,
    networkTrackingEnabled = false,
    captureInterrupted = recording,
    lastErrorCode = if (recording) "CAPTURE_INTERRUPTED" else when (loss) {
        CdpConnectionLoss.INSPECTOR_DETACHED -> "CDP_DETACHED"
        CdpConnectionLoss.TRANSPORT_CLOSED -> "CDP_CONNECTION_CLOSED"
    },
)

internal fun decodeCdpEventMessage(message: JSONObject, wireBytes: Int): CdpEvent? {
    if (message.opt("id") is Number) return null
    val method = message.opt("method") as? String ?: return null
    val sessionId = if (message.has("sessionId")) {
        (message.opt("sessionId") as? String)?.takeIf { it.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$")) } ?: return null
    } else null
    return CdpEvent(method, message.optJSONObject("params") ?: JSONObject(), wireBytes,
        sessionId = sessionId)
}

internal fun cdpDetachedReason(event: CdpEvent): String? =
    if (event.method == "Inspector.detached") event.params.optString("reason").ifBlank { "unknown" } else null

internal class CdpConnectionHealth {
    private val transportClosed = AtomicBoolean(false)
    @Volatile private var detached: String? = null

    fun observe(event: CdpEvent) {
        cdpDetachedReason(event)?.let { detached = it }
    }

    fun closeTransport(): Boolean = transportClosed.compareAndSet(false, true)
    fun isTransportClosed(): Boolean = transportClosed.get()
    fun isClosed(): Boolean = isTransportClosed() || detached != null
    fun detachedReason(): String? = detached
    fun lossReason(): CdpConnectionLoss =
        if (detachedReason() != null) CdpConnectionLoss.INSPECTOR_DETACHED else CdpConnectionLoss.TRANSPORT_CLOSED
}

internal class CdpCommandRejectedException(val protocolCode: Int, val protocolMessage: String) :
    IllegalStateException("CDP_COMMAND_REJECTED")
internal class CdpOutcomeUnknownException(cause: Throwable) : IllegalStateException("CDP_UNKNOWN_AFTER_SEND", cause)

/** Private abstract LocalSocket CDP client. No endpoint is exposed to callers. */
internal class CdpConnection private constructor(
    private val socket: LocalSocket,
    private val eventQueue: ArrayBlockingQueue<CdpEvent>,
    val targetId: String,
) : AutoCloseable {
    private val input = socket.inputStream
    private val output = socket.outputStream
    private val health = CdpConnectionHealth()
    private val commandId = AtomicLong(0L)
    private val writeLock = Any()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<CdpResponseMessage>>()
    private val droppedEvents = AtomicLong(0L)
    private val receivedEvents = AtomicLong(0L)
    private val eventByteBudget = CdpEventByteBudget(MAX_QUEUED_EVENT_BYTES.toLong())
    private val frameDecoder = CdpWebSocketFrameDecoder(MAX_PROTOCOL_FRAME_BYTES)
    private val reader = Thread(::readLoop, "browser-devtools-reader").apply { isDaemon = true; start() }

    fun call(method: String, params: JSONObject, deadlineElapsedMillis: Long, stillAuthorized: () -> Boolean): JSONObject =
        call(method, params, deadlineElapsedMillis, stillAuthorized, null)

    fun call(
        method: String,
        params: JSONObject,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
        sessionId: String?,
    ): JSONObject = callFenced(method, params, deadlineElapsedMillis, stillAuthorized, sessionId).result

    fun callFenced(
        method: String,
        params: JSONObject,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
        sessionId: String?,
    ): CdpCommandResult {
        check(!isClosed()) { "CDP_CLOSED" }
        check(stillAuthorized()) { "NOT_AUTHORIZED" }
        val remaining = deadlineElapsedMillis - android.os.SystemClock.elapsedRealtime()
        check(remaining > 0L) { "DEADLINE_EXPIRED" }
        check(pending.size < MAX_PENDING) { "BUSY" }
        val id = commandId.incrementAndGet()
        val future = CompletableFuture<CdpResponseMessage>()
        check(pending.putIfAbsent(id, future) == null) { "BUSY" }
        var writeAttempted = false
        try {
            val command = JSONObject().put("id", id).put("method", method).put("params", params)
            if (sessionId != null) {
                require(sessionId.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) { "INVALID_SESSION" }
                command.put("sessionId", sessionId)
            }
            val bytes = command
                .toString().toByteArray(StandardCharsets.UTF_8)
            check(bytes.size <= MAX_COMMAND_BYTES) { "FRAME_TOO_LARGE" }
            synchronized(writeLock) {
                check(!isClosed()) { "CDP_CLOSED" }
                check(stillAuthorized()) { "NOT_AUTHORIZED" }
                check(android.os.SystemClock.elapsedRealtime() < deadlineElapsedMillis) { "DEADLINE_EXPIRED" }
                writeAttempted = true
                writeTextFrame(output, bytes)
            }
            val waitMillis = deadlineElapsedMillis - android.os.SystemClock.elapsedRealtime()
            check(waitMillis > 0L) { "DEADLINE_EXPIRED" }
            val reply = future.get(waitMillis, TimeUnit.MILLISECONDS)
            val response = reply.message
            if (response.has("error")) {
                val cdpError = response.optJSONObject("error") ?: JSONObject()
                throw CdpCommandRejectedException(cdpError.optInt("code", 0), cdpError.optString("message").take(160))
            }
            return CdpCommandResult(response.optJSONObject("result") ?: JSONObject(), reply.eventSequenceFence)
        } catch (error: Exception) {
            if (writeAttempted && error !is CdpCommandRejectedException) throw CdpOutcomeUnknownException(error)
            throw error
        } finally {
            pending.remove(id, future)
        }
    }

    /** Safety-only release of a Fetch pause after authorization has been revoked. */
    fun continueAfterRevoke(requestId: String, deadlineElapsedMillis: Long, responseStage: Boolean = false): Boolean {
        if (isClosed() || !requestId.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$"))) return false
        val id = commandId.incrementAndGet()
        val future = CompletableFuture<CdpResponseMessage>()
        if (pending.size >= MAX_PENDING || pending.putIfAbsent(id, future) != null) return false
        return try {
            synchronized(writeLock) {
                check(!isClosed())
                writeTextFrame(output, JSONObject().put("id", id)
                    .put("method", if (responseStage) "Fetch.continueResponse" else "Fetch.continueRequest")
                    .put("params", JSONObject().put("requestId", requestId))
                    .toString().toByteArray(StandardCharsets.UTF_8))
            }
            val remaining = deadlineElapsedMillis - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return false
            val response = future.get(remaining, TimeUnit.MILLISECONDS)
            !response.message.has("error")
        } catch (_: Exception) {
            false
        } finally {
            pending.remove(id, future)
        }
    }

    fun isClosed(): Boolean = health.isClosed()

    fun isTransportClosed(): Boolean = health.isTransportClosed()

    fun detachedReason(): String? = health.detachedReason()

    fun lossReason(): CdpConnectionLoss = health.lossReason()

    fun nextEvent(timeoutMillis: Long): CdpEvent? {
        val event = eventQueue.poll(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS) ?: return null
        eventByteBudget.release(event.wireBytes)
        return event
    }

    fun droppedEventCount(): Long = droppedEvents.get()

    fun lastReceivedEventSequence(): Long = receivedEvents.get()

    override fun close() {
        if (!health.closeTransport()) return
        runCatching { socket.close() }
        pending.values.forEach { it.completeExceptionally(EOFException("CDP_CLOSED")) }
        pending.clear()
        reader.interrupt()
    }

    private fun readLoop() {
        while (!health.isTransportClosed()) {
            try {
                val frame = readFrame(input) ?: continue
                when (frame.opcode) {
                    0x1 -> routeText(frame.payload)
                    0x8 -> throw EOFException("CDP_CLOSED")
                    0x9 -> synchronized(writeLock) { writeControlFrame(output, 0xA, frame.payload) }
                }
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                close()
                return
            }
        }
    }

    private fun routeText(payload: ByteArray) {
        if (payload.size > MAX_PROTOCOL_FRAME_BYTES) return
        val message = runCatching { JSONObject(String(payload, StandardCharsets.UTF_8)) }.getOrNull() ?: return
        val responseId = message.opt("id")
        if (responseId is Number) {
            pending[responseId.toLong()]?.complete(captureCdpResponseMessage(message, receivedEvents.get()))
            return
        }
        val decoded = decodeCdpEventMessage(message, payload.size) ?: return
        val event = decoded.copy(receivedSequence = receivedEvents.incrementAndGet())
        health.observe(event)
        val reserved = eventByteBudget.reserve(event.wireBytes)
        if (!reserved || !eventQueue.offer(event)) {
            if (reserved) eventByteBudget.release(event.wireBytes)
            droppedEvents.incrementAndGet()
            // The event worker cannot resolve a dropped pause. Detach instead of silently
            // issuing an unobserved command whose CDP result/error cannot be verified.
            if (event.method == "Fetch.requestPaused") {
                close()
            }
        }
    }

    private fun readFrame(input: InputStream): CdpWebSocketFrame? = frameDecoder.read(input)

    companion object {
        private const val MAX_PENDING = 32
        private const val MAX_COMMAND_BYTES = 64 * 1024
        private const val MAX_PROTOCOL_FRAME_BYTES = 2 * 1024 * 1024
        private const val MAX_QUEUED_EVENT_BYTES = 8 * 1024 * 1024

        internal fun decodeEventPayload(payload: ByteArray): CdpEvent? {
            if (payload.size > MAX_PROTOCOL_FRAME_BYTES) return null
            val message = runCatching { JSONObject(String(payload, StandardCharsets.UTF_8)) }.getOrNull() ?: return null
            return decodeCdpEventMessage(message, payload.size)
        }

        fun connect(pid: Int, currentUrl: String, deadlineElapsedMillis: Long): CdpConnection {
            val socketName = "webview_devtools_remote_$pid"
            val discovery = LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = remainingTimeout(deadlineElapsedMillis)
                writeAscii(socket.outputStream, "GET /json HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                val response = readHttpResponse(socket, deadlineElapsedMillis)
                check(response.status == 200) { "TARGET_UNAVAILABLE" }
                JSONArray(String(response.body, StandardCharsets.UTF_8))
            }
            val targets = (0 until discovery.length()).mapNotNull { index -> discovery.optJSONObject(index) }
                .filter { it.optString("type") == "page" && it.has("webSocketDebuggerUrl") }
            val urlMatches = targets.filter { target -> samePage(target.optString("url"), currentUrl) }
            check(urlMatches.size == 1) { if (urlMatches.isEmpty()) "TARGET_UNAVAILABLE" else "AMBIGUOUS_TARGET" }
            val wsUrl = URI(urlMatches.single().getString("webSocketDebuggerUrl"))
            check(wsUrl.scheme == "ws" && wsUrl.host == "localhost" && wsUrl.rawPath.startsWith("/devtools/page/")) {
                "TARGET_UNAVAILABLE"
            }
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = remainingTimeout(deadlineElapsedMillis)
                websocketHandshake(socket, wsUrl.rawPath, deadlineElapsedMillis)
                socket.soTimeout = CDP_IDLE_READ_TIMEOUT_MILLIS
                val targetId = urlMatches.single().optString("id")
                    .takeIf { it.matches(Regex("^[A-Za-z0-9_.:-]{1,128}$")) }
                    ?: error("TARGET_UNAVAILABLE")
                return CdpConnection(socket, ArrayBlockingQueue(EVENT_QUEUE_CAPACITY), targetId)
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }

        private data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

        private fun readHttpResponse(socket: LocalSocket, deadline: Long): HttpResponse {
            val input = socket.inputStream
            val rawHeader = ByteArrayOutputStream()
            var marker = 0
            while (rawHeader.size() < MAX_HTTP_HEADER_BYTES) {
                socket.soTimeout = remainingTimeout(deadline)
                val byte = input.read()
                if (byte < 0) throw EOFException("HTTP_HEADERS_TRUNCATED")
                rawHeader.write(byte)
                marker = when {
                    marker == 0 && byte == 13 -> 1
                    marker == 1 && byte == 10 -> 2
                    marker == 2 && byte == 13 -> 3
                    marker == 3 && byte == 10 -> 4
                    byte == 13 -> 1
                    else -> 0
                }
                if (marker == 4) break
            }
            check(marker == 4) { "HTTP_HEADERS_TOO_LARGE" }
            val lines = rawHeader.toString("US-ASCII").split("\r\n")
            val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            val headers = lines.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val length = headers["content-length"]?.toIntOrNull() ?: error("HTTP_LENGTH_REQUIRED")
            check(length in 0..MAX_DISCOVERY_BYTES) { "HTTP_BODY_TOO_LARGE" }
            return HttpResponse(status, headers, readExactly(socket, length, deadline))
        }

        private fun websocketHandshake(socket: LocalSocket, path: String, deadline: Long) {
            val nonce = ByteArray(16).also(java.security.SecureRandom()::nextBytes)
            val key = Base64.getEncoder().encodeToString(nonce)
            writeAscii(socket.outputStream,
                "GET $path HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n")
            val headers = ByteArrayOutputStream()
            var marker = 0
            while (headers.size() < MAX_HTTP_HEADER_BYTES) {
                socket.soTimeout = remainingTimeout(deadline)
                val byte = socket.inputStream.read()
                if (byte < 0) throw EOFException("WEBSOCKET_HEADERS_TRUNCATED")
                headers.write(byte)
                marker = when {
                    marker == 0 && byte == 13 -> 1
                    marker == 1 && byte == 10 -> 2
                    marker == 2 && byte == 13 -> 3
                    marker == 3 && byte == 10 -> 4
                    byte == 13 -> 1
                    else -> 0
                }
                if (marker == 4) break
            }
            val text = headers.toString("US-ASCII")
            check(marker == 4) { "WEBSOCKET_HEADERS_TOO_LARGE" }
            val fields = text.split("\r\n").drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
            }.toMap()
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + WS_GUID).toByteArray(StandardCharsets.US_ASCII)))
            check(text.startsWith("HTTP/1.1 101") &&
                fields["upgrade"].equals("websocket", ignoreCase = true) &&
                fields["connection"]?.split(',')?.any { it.trim().equals("upgrade", ignoreCase = true) } == true &&
                fields["sec-websocket-accept"] == accept
            ) { "WEBSOCKET_HANDSHAKE_FAILED" }
        }

        private fun writeTextFrame(output: OutputStream, payload: ByteArray) =
            CdpWebSocketFrameCodec.writeClientFrame(output, 0x1, payload, MAX_PROTOCOL_FRAME_BYTES)

        private fun writeControlFrame(output: OutputStream, opcode: Int, payload: ByteArray) =
            CdpWebSocketFrameCodec.writeClientFrame(output, opcode, payload, MAX_PROTOCOL_FRAME_BYTES)

        private fun writeAscii(output: OutputStream, text: String) {
            output.write(text.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }

        private fun readExactly(socket: LocalSocket, count: Int, deadline: Long): ByteArray {
            val input = socket.inputStream
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                socket.soTimeout = remainingTimeout(deadline)
                val read = input.read(bytes, offset, count - offset)
                if (read < 0) throw EOFException("HTTP_BODY_TRUNCATED")
                offset += read
            }
            return bytes
        }

        private fun remainingTimeout(deadline: Long): Int {
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            check(remaining > 0L) { "DEADLINE_EXPIRED" }
            return remaining.coerceAtMost(60_000L).toInt()
        }

        private fun samePage(target: String, current: String): Boolean = runCatching {
            val a = URI(target)
            val b = URI(current)
            a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && a.port == b.port &&
                a.path == b.path && a.rawQuery == b.rawQuery
        }.getOrDefault(false)

        private const val EVENT_QUEUE_CAPACITY = 256
        private const val MAX_HTTP_HEADER_BYTES = 16 * 1024
        private const val MAX_DISCOVERY_BYTES = 1024 * 1024
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
