package com.dshbox.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import com.dshbox.browser.api.BrowserArtifactFiles
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID

/** Bounded WebSocket message metadata and payloads, kept separate from HTTP entries and HAR. */
internal class WebSocketCaptureStore {
    private data class SocketInfo(val id: String, var createdSeen: Boolean = false)
    private data class SanitizedHeaders(val value: JSONObject, val redacted: Boolean, val truncated: Boolean)

    private val frames = ArrayDeque<JSONObject>()
    private val sockets = linkedMapOf<String, SocketInfo>()
    private var captureId: String? = null
    private var recording = false
    private var includeBodies = false
    private var payloadBytes = 0
    private var metadataBytes = 0
    private var nextSequence = 0L
    private var droppedFrames = 0L
    private var frozenEventDrops = 0L
    private var loss = false

    @Synchronized fun begin(id: String, includeBodies: Boolean) {
        frames.clear()
        sockets.clear()
        captureId = id
        this.includeBodies = includeBodies
        recording = true
        payloadBytes = 0
        metadataBytes = 0
        nextSequence = 0L
        droppedFrames = 0L
        frozenEventDrops = 0L
        loss = false
    }

    @Synchronized fun stop() { recording = false }
    @Synchronized fun isRecording(): Boolean = recording

    /** Called only after controller identity, revocation and event-sequence checks. */
    @Synchronized fun onEvent(method: String, params: JSONObject) {
        if (!recording) return
        val rawRequestId = params.opt("requestId") as? String
        val requestId = rawRequestId?.takeIf { it.isNotBlank() && it.length <= MAX_REQUEST_ID_CHARS }
        if (requestId == null) {
            markLoss()
            return
        }
        var socket = sockets[requestId]
        if (socket == null) {
            if (sockets.size >= MAX_SOCKETS) {
                markLoss()
                return
            }
            socket = SocketInfo(newSocketId())
            sockets[requestId] = socket
        }
        val kind = when (method) {
            "Network.webSocketCreated" -> "created"
            "Network.webSocketWillSendHandshakeRequest" -> "handshakeRequest"
            "Network.webSocketHandshakeResponseReceived" -> "handshakeResponse"
            "Network.webSocketFrameSent" -> "sent"
            "Network.webSocketFrameReceived" -> "received"
            "Network.webSocketFrameError" -> "error"
            "Network.webSocketClosed" -> "closed"
            else -> return
        }
        val record = JSONObject()
            .put("sequence", ++nextSequence)
            .put("webSocketId", socket.id)
            .put("kind", kind)
        if (!socket.createdSeen && kind != "created") record.put("metadataIncomplete", true)
        val timestamp = params.opt("timestamp") as? Number
        if (timestamp != null && timestamp.toDouble().isFinite() && timestamp.toDouble() >= 0) record.put("timestamp", timestamp.toDouble())
        else if (kind != "created") record.put("metadataIncomplete", true)

        when (kind) {
            "created" -> {
                val url = params.opt("url") as? String ?: ""
                if (url.isBlank()) record.put("metadataIncomplete", true)
                else {
                    val safeUrl = sanitizeUrl(url)
                    socket.createdSeen = true
                    if (safeUrl.first.isNotBlank()) record.put("url", safeUrl.first)
                    else record.put("metadataIncomplete", true)
                    if (safeUrl.second) {
                        record.put("urlTruncated", true)
                        loss = true
                    }
                }
            }
            "handshakeRequest" -> {
                val request = params.optJSONObject("request")
                if (request == null) record.put("metadataIncomplete", true)
                else {
                    val headers = sanitizeHeaders(request.optJSONObject("headers"))
                    record.put("requestHeaders", headers.value)
                    if (headers.redacted) record.put("requestHeadersRedacted", true)
                    if (headers.truncated) record.put("requestHeadersTruncated", true)
                    if (request.optJSONObject("headers") == null || params.opt("wallTime") !is Number ||
                        !params.optDouble("wallTime").isFinite() || params.optDouble("wallTime") < 0) record.put("metadataIncomplete", true)
                    else record.put("wallTime", params.optDouble("wallTime"))
                    if (headers.truncated) loss = true
                }
            }
            "handshakeResponse" -> {
                val response = params.optJSONObject("response")
                if (response == null) record.put("metadataIncomplete", true)
                else {
                    val status = response.opt("status") as? Number
                    val validStatus = status != null && status.toDouble() in 100.0..599.0 && status.toDouble() % 1.0 == 0.0
                    if (!validStatus) record.put("metadataIncomplete", true)
                    record.put("status", if (validStatus) status!!.toInt() else -1)
                    record.put("statusText", response.optString("statusText").take(MAX_STATUS_TEXT_CHARS))
                    val headers = sanitizeHeaders(response.optJSONObject("headers"))
                    record.put("responseHeaders", headers.value)
                    if (headers.redacted) record.put("responseHeadersRedacted", true)
                    if (headers.truncated) record.put("responseHeadersTruncated", true)
                    if (status == null || response.opt("statusText") !is String || response.optJSONObject("headers") == null) record.put("metadataIncomplete", true)
                    if (response.optString("statusText").length > MAX_STATUS_TEXT_CHARS) {
                        record.put("statusTextTruncated", true)
                        loss = true
                    }
                    if (headers.truncated) loss = true
                }
            }
            "sent", "received" -> appendFramePayload(record, params)
            "error" -> {
                val message = params.optString("errorMessage")
                if (message.isBlank()) record.put("metadataIncomplete", true)
                else {
                    val redacted = redactText(message)
                    record.put("detail", redacted.take(MAX_DETAIL_CHARS))
                    if (redacted != message) record.put("detailRedacted", true)
                    if (redacted.length > MAX_DETAIL_CHARS) {
                        record.put("detailTruncated", true)
                        loss = true
                    }
                }
            }
        }

        if (record.optBoolean("metadataIncomplete")) loss = true

        boundRecord(record)

        if (frames.size >= MAX_FRAMES) {
            removeOldest()
        }
        val recordBytes = record.toString().toByteArray(StandardCharsets.UTF_8).size
        frames.addLast(record)
        metadataBytes += recordBytes
        while (metadataBytes > MAX_METADATA_BYTES && frames.isNotEmpty()) removeOldest()
    }

    private fun appendFramePayload(record: JSONObject, params: JSONObject) {
        val frame = params.optJSONObject("response")
        if (frame == null) {
            record.put("metadataIncomplete", true)
            markLoss()
            return
        }
        val opcode = frame.opt("opcode") as? Number
        if (opcode == null || opcode.toDouble() !in 0.0..15.0 || opcode.toDouble() % 1.0 != 0.0) {
            record.put("metadataIncomplete", true)
            markLoss()
            return
        }
        val op = opcode.toInt()
        record.put("opcode", op)
        record.put("direction", if (record.optString("kind") == "sent") "outgoing" else "incoming")
        if (frame.opt("mask") is Boolean) record.put("masked", frame.getBoolean("mask"))
        else record.put("metadataIncomplete", true)
        val payload = frame.opt("payloadData") as? String
        if (payload == null) {
            record.put("metadataIncomplete", true)
            record.put("payloadUnavailable", true)
            markLoss()
            return
        }
        val binary = op != 1
        val raw = if (binary) runCatching { Base64.getDecoder().decode(payload) }.getOrNull() else payload.toByteArray(StandardCharsets.UTF_8)
        if (raw == null) {
            record.put("payloadEncoding", if (binary) "base64" else "text")
                .put("payloadUnavailable", true).put("metadataIncomplete", true)
            markLoss()
            return
        }
        record.put("payloadEncoding", if (binary) "base64" else "text")
            .put("payloadBytes", raw.size)
        if (!includeBodies) {
            record.put("payloadOmitted", true)
            return
        }
        val captureBudget = (MAX_CAPTURE_PAYLOAD_BYTES - payloadBytes).coerceAtLeast(0)
        val accepted = minOf(raw.size, MAX_FRAME_PAYLOAD_BYTES, captureBudget)
        val clipped = accepted < raw.size
        var storedBytes = 0
        if (raw.isEmpty()) record.put("payload", "")
        if (accepted > 0) {
            if (binary) {
                record.put("payload", Base64.getEncoder().encodeToString(raw.copyOf(accepted)))
                storedBytes = accepted
            }
            else {
                val (text, validBytes) = decodeUtf8Prefix(raw, accepted)
                record.put("payload", text)
                storedBytes = validBytes
                if (validBytes < accepted) {
                    record.put("frameTruncated", true)
                    loss = true
                }
            }
            payloadBytes += storedBytes
        }
        if (clipped) {
            record.put("frameTruncated", true)
            loss = true
        }
    }

    @Synchronized fun list(cursor: Int, limit: Int, eventDrops: Long): JSONObject {
        frozenEventDrops = maxOf(frozenEventDrops, eventDrops.coerceAtLeast(0L))
        val values = frames.toList()
        val start = cursor.coerceIn(0, values.size)
        val page = JSONArray()
        val requestedEnd = minOf(values.size, start + limit.coerceIn(1, MAX_PAGE))
        var end = start
        while (end < requestedEnd) {
            page.put(JSONObject(values[end].toString()))
            if (listResponse(start, end + 1, values.size, page, eventDrops).toString().toByteArray(StandardCharsets.UTF_8).size > MAX_PAGE_JSON_BYTES) {
                page.remove(page.length() - 1)
                break
            }
            end++
        }
        return listResponse(start, end, values.size, page, eventDrops)
    }

    @Synchronized fun export(filesRoot: File, eventDrops: Long, exportBodies: Boolean = false): JSONObject? {
        val id = captureId ?: return null
        val exportedBodies = exportBodies && includeBodies
        val allFrames = JSONArray()
        var truncated = loss || droppedFrames > 0L || frozenEventDrops > 0L || eventDrops > 0L
        for (frame in frames) {
            val exportedFrame = JSONObject(frame.toString())
            if (!exportedBodies && exportedFrame.has("payload")) {
                exportedFrame.remove("payload")
                exportedFrame.put("payloadOmitted", true)
            }
            val candidate = JSONArray(allFrames.toString()).put(exportedFrame)
            val document = JSONObject()
                .put("format", "dshbox-browser-websocket-capture")
                .put("version", 1)
                .put("captureId", id)
                .put("frames", candidate)
                .put("recording", recording)
                .put("includeBodies", exportedBodies)
                .put("payloadsIncluded", exportedBodies)
                .put("payloadBytes", if (exportedBodies) payloadBytes else 0)
                .put("droppedFrames", droppedFrames)
                .put("eventDrops", maxOf(frozenEventDrops, eventDrops))
                .put("truncated", truncated)
                .put("redacted", !exportedBodies)
                .put("payloadUnredacted", exportedBodies)
                .put("headersRedacted", headersWereRedacted(candidate))
            if (document.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_EXPORT_BYTES) {
                truncated = true
                break
            }
            allFrames.put(exportedFrame)
        }
        val document = JSONObject()
            .put("format", "dshbox-browser-websocket-capture")
            .put("version", 1)
            .put("captureId", id)
            .put("frames", allFrames)
            .put("recording", recording)
            .put("includeBodies", exportedBodies)
            .put("payloadsIncluded", exportedBodies)
            .put("payloadBytes", if (exportedBodies) payloadBytes else 0)
            .put("droppedFrames", droppedFrames)
            .put("eventDrops", maxOf(frozenEventDrops, eventDrops))
            .put("truncated", truncated)
            .put("redacted", !exportedBodies)
            .put("payloadUnredacted", exportedBodies)
            .put("headersRedacted", headersWereRedacted(allFrames))
        val bytes = document.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_EXPORT_BYTES) return null
        val name = "net-${UUID.randomUUID().toString().replace("-", "").take(24)}.ws.json"
        val file = runCatching {
            BrowserArtifactFiles.writeNetworkArtifactAtomically(filesRoot, name, bytes, MAX_EXPORT_BYTES)
        }.getOrNull() ?: return null
        return JSONObject()
            .put("artifactPath", "/opt/browser/out/network/${file.name}")
            .put("mimeType", "application/json")
            .put("bytes", bytes.size)
            .put("captureId", id)
            .put("frameCount", allFrames.length())
            .put("payloadsIncluded", exportedBodies)
            .put("redacted", !exportedBodies)
            .put("payloadUnredacted", exportedBodies)
            .put("headersRedacted", headersWereRedacted(allFrames))
            .put("truncated", truncated)
    }

    private fun listResponse(start: Int, end: Int, total: Int, page: JSONArray, eventDrops: Long): JSONObject {
        val effectiveDrops = maxOf(frozenEventDrops, eventDrops.coerceAtLeast(0L))
        val effectiveLoss = loss || droppedFrames > 0L || effectiveDrops > 0L
        return JSONObject()
            .put("captureId", captureId ?: JSONObject.NULL)
            .put("frames", page)
            .put("cursor", start)
            .put("nextCursor", if (end < total) end else JSONObject.NULL)
            .put("total", total)
            .put("droppedFrames", droppedFrames)
            .put("eventDrops", effectiveDrops)
            .put("recording", recording)
            .put("includeBodies", includeBodies)
            .put("payloadUnredacted", includeBodies)
            .put("headersRedacted", headersWereRedacted(JSONArray(frames.toList().map { JSONObject(it.toString()) })))
            .put("redacted", !includeBodies)
            .put("payloadBytes", payloadBytes)
            .put("maxFramePayloadBytes", MAX_FRAME_PAYLOAD_BYTES)
            .put("maxCapturePayloadBytes", MAX_CAPTURE_PAYLOAD_BYTES)
            .put("maxFrames", MAX_FRAMES)
            .put("maxPageBytes", MAX_PAGE_JSON_BYTES)
            .put("truncated", effectiveLoss)
            .put("loss", effectiveLoss)
    }

    private fun headersWereRedacted(records: JSONArray): Boolean = (0 until records.length()).any { index ->
        val record = records.optJSONObject(index) ?: return@any false
        record.optBoolean("requestHeadersRedacted") || record.optBoolean("responseHeadersRedacted")
    }

    @Synchronized fun markEventDrops(count: Long = 1L) {
        frozenEventDrops = maxOf(frozenEventDrops, count.coerceAtLeast(0L))
        if (count > 0L) markLoss()
    }

    /** Keep one record small enough that pagination can always advance past it. */
    private fun boundRecord(record: JSONObject) {
        boundPayloadRecord(record)
        for (field in listOf("requestHeaders", "responseHeaders")) {
            if (record.toString().toByteArray(StandardCharsets.UTF_8).size <= MAX_SINGLE_RECORD_JSON_BYTES) return
            if (record.has(field)) {
                record.put(field, JSONObject()).put(field + "Truncated", true).put("metadataIncomplete", true)
                loss = true
            }
        }
        boundPayloadRecord(record)
        if (record.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_SINGLE_RECORD_JSON_BYTES) {
            for (field in listOf("url", "statusText", "detail")) record.remove(field)
            record.put("metadataIncomplete", true)
            loss = true
        }
    }

    private fun boundPayloadRecord(record: JSONObject) {
        var payload = record.opt("payload") as? String ?: return
        var previousBytes = if (record.optString("payloadEncoding") == "base64") {
            runCatching { Base64.getDecoder().decode(payload).size }.getOrDefault(0)
        } else payload.toByteArray(StandardCharsets.UTF_8).size
        while (record.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_SINGLE_RECORD_JSON_BYTES && payload.isNotEmpty()) {
            payload = if (record.optString("payloadEncoding") == "base64") {
                val raw = runCatching { Base64.getDecoder().decode(payload) }.getOrDefault(ByteArray(0))
                Base64.getEncoder().encodeToString(raw.copyOf(raw.size / 2))
            } else {
                val count = payload.codePointCount(0, payload.length)
                payload.substring(0, payload.offsetByCodePoints(0, count / 2))
            }
            record.put("payload", payload).put("frameTruncated", true)
            val currentBytes = if (record.optString("payloadEncoding") == "base64") {
                runCatching { Base64.getDecoder().decode(payload).size }.getOrDefault(0)
            } else payload.toByteArray(StandardCharsets.UTF_8).size
            payloadBytes = (payloadBytes - (previousBytes - currentBytes)).coerceAtLeast(0)
            previousBytes = currentBytes
            loss = true
        }
    }

    private fun markLoss() {
        loss = true
    }

    private fun removeOldest() {
        val removed = frames.removeFirst()
        metadataBytes = (metadataBytes - removed.toString().toByteArray(StandardCharsets.UTF_8).size).coerceAtLeast(0)
        droppedFrames++
        loss = true
    }

    private fun newSocketId(): String = "ws-${UUID.randomUUID().toString().replace("-", "").take(20)}"

    private fun sanitizeUrl(value: String, webSocketOnly: Boolean = true): Pair<String, Boolean> = runCatching {
        val uri = java.net.URI(value)
        val schemes = if (webSocketOnly) setOf("ws", "wss") else setOf("http", "https", "ws", "wss")
        require(uri.scheme in schemes && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
        java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
    }.getOrNull()?.let { it.take(MAX_URL_CHARS) to (it.length > MAX_URL_CHARS) }
        ?: ("" to true)

    private fun sanitizeHeaders(headers: JSONObject?): SanitizedHeaders {
        val result = JSONObject()
        if (headers == null) return SanitizedHeaders(result, redacted = false, truncated = false)
        val iterator = headers.keys()
        var count = 0
        var scanned = 0
        var redacted = false
        var truncated = false
        while (iterator.hasNext()) {
            if (scanned++ >= MAX_HEADER_SCAN) {
                truncated = true
                break
            }
            val originalKey = iterator.next()
            val key = originalKey.take(MAX_HEADER_NAME_CHARS)
            val lower = originalKey.lowercase()
            if (SENSITIVE_HEADER_PATTERN.containsMatchIn(lower)) { redacted = true; continue }
            val value = headers.opt(originalKey)?.toString() ?: continue
            val safeValue = redactText(value)
            if (safeValue != value) redacted = true
            if (count >= MAX_HEADERS || originalKey.length > MAX_HEADER_NAME_CHARS || safeValue.length > MAX_HEADER_VALUE_CHARS) {
                truncated = true
                continue
            }
            result.put(key, safeValue)
            count++
        }
        return SanitizedHeaders(result, redacted, truncated)
    }

    private fun redactText(value: String): String = value
        .replace(Regex("(?i)(?:https?|wss?)://[^\\s<>\"']+")) { match ->
            sanitizeUrl(match.value, webSocketOnly = false).first.ifBlank { "[redacted-url]" }
        }
        .replace(Regex("(?i)\\bBearer\\s+[^\\s,;]+"), "Bearer [redacted]")
        .replace(Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"), "[redacted]")
        .replace(Regex("(?i)(authorization|cookie|set-cookie|(?:x[-_])?api[-_]?key|password|secret|csrf|token|key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[redacted]")

    private fun decodeUtf8Prefix(bytes: ByteArray, maxBytes: Int): Pair<String, Int> {
        var length = maxBytes
        while (length >= 0) {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val decoded = runCatching { decoder.decode(ByteBuffer.wrap(bytes, 0, length)).toString() }.getOrNull()
            if (decoded != null) return decoded to length
            length--
        }
        return "" to 0
    }

    companion object {
        const val MAX_FRAMES = 1000
        const val MAX_FRAME_PAYLOAD_BYTES = 8 * 1024
        const val MAX_CAPTURE_PAYLOAD_BYTES = 1024 * 1024
        const val MAX_METADATA_BYTES = 2 * 1024 * 1024
        const val MAX_EXPORT_BYTES = 2 * 1024 * 1024
        const val MAX_SOCKETS = 1000
        private const val MAX_PAGE = 20
        private const val MAX_PAGE_JSON_BYTES = 14 * 1024
        private const val MAX_SINGLE_RECORD_JSON_BYTES = 12 * 1024
        private const val MAX_URL_CHARS = 2048
        private const val MAX_DETAIL_CHARS = 256
        private const val MAX_STATUS_TEXT_CHARS = 128
        private const val MAX_HEADER_NAME_CHARS = 64
        private const val MAX_HEADERS = 16
        private const val MAX_HEADER_SCAN = 64
        private const val MAX_HEADER_VALUE_CHARS = 256
        private const val MAX_REQUEST_ID_CHARS = 128
        private val SENSITIVE_HEADER_PATTERN = Regex("(?i)(token|secret|password|csrf|auth|api[-_]?key|cookie)")
    }
}
