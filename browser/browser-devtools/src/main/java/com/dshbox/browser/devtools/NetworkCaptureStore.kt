package com.dshbox.browser.devtools

import android.content.Context
import android.util.Base64
import com.dshbox.browser.api.BrowserArtifactFiles
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.io.InputStream

internal class NetworkCaptureStore internal constructor(
    private val filesRoot: File,
    private val elapsedClock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val deleteArtifact: (File, String) -> Unit = { root, name -> BrowserArtifactFiles.deleteNetworkArtifact(root, name) },
) {
    constructor(context: Context) : this(context.applicationContext.filesDir)

    private data class Entry(
        val id: String,
        val cdpRequestId: String,
        val startedMillis: Long,
        val startedElapsedMillis: Long,
        var completedElapsedMillis: Long? = null,
        val request: JSONObject,
        var response: JSONObject = JSONObject(),
        var finished: Boolean = false,
        var failure: String? = null,
        var mimeType: String = "",
        var responseBodyFile: File? = null,
        var responseBodyBytes: Int = 0,
        var responseBodyTruncated: Boolean = false,
        var streamingBodyActive: Boolean = false,
        var contentLength: Long = -1L,
        val requestContentType: String = "",
        var requestBody: ByteArray? = null,
        var requestBodyState: String = "not_requested",
    )

    private val entries = ArrayDeque<Entry>()
    private val byCdpId = mutableMapOf<String, Entry>()
    private val artifacts = LinkedHashMap<String, Pair<File, Long>>()
    private var artifactsScanned = false
    private var artifactBytes = 0L
    private var captureId: String? = null
    private var includeBodies = false
    private var includeRequestBodies = false
    private var requestBodyBytes = 0
    private var bodyBytes = 0
    private var droppedCount = 0L
    private var eventLoss = false
    private var recording = false
    private var artifactError: String? = null

    @Synchronized fun begin(includeBodies: Boolean, includeRequestBodies: Boolean = false): String {
        clearMemory(deleteArtifacts = true)
        this.captureId = "cap-${UUID.randomUUID().toString().replace("-", "").take(20)}"
        this.includeBodies = includeBodies
        this.includeRequestBodies = includeRequestBodies
        this.requestBodyBytes = 0
        this.recording = true
        artifactError = null
        return captureId!!
    }

    @Synchronized fun stop() {
        recording = false
        entries.forEach { if (it.requestBodyState == "pending") it.requestBodyState = "incomplete" }
    }

    @Synchronized fun isRecording(): Boolean = recording
    @Synchronized fun capturesBodies(): Boolean = recording && includeBodies
    @Synchronized fun capturesRequestBodies(): Boolean = recording && includeRequestBodies
    @Synchronized fun captureId(): String? = captureId
    @Synchronized fun droppedCount(): Long = droppedCount
    @Synchronized fun entryCount(): Int = entries.size
    @Synchronized fun requestBodyBytesUsed(): Int = requestBodyBytes
    @Synchronized fun artifactErrorCode(): String? = artifactError

    @Synchronized fun markEventDrops() { eventLoss = true }

    @Synchronized fun onRequest(params: JSONObject): String? {
        if (!recording) return null
        val cdpId = params.optString("requestId").takeIf { it.isNotBlank() } ?: return null
        val request = params.optJSONObject("request") ?: JSONObject()
        val headers = request.optJSONObject("headers") ?: JSONObject()
        val contentType = headers.keys().asSequence().firstOrNull { it.equals("content-type", true) }
            ?.let { headers.optString(it).substringBefore(';').trim().lowercase(Locale.ROOT) }.orEmpty()
        val method = request.optString("method").uppercase(Locale.ROOT)
        val hasPostData = request.optBoolean("hasPostData") || request.has("postData")
        val bodyState = when {
            !includeRequestBodies -> "not_requested"
            method !in WRITE_METHODS -> "not_applicable"
            !hasPostData -> "captured"
            contentType !in REPLAYABLE_REQUEST_MIMES -> "unsupported_content_type"
            else -> "pending"
        }
        val entry = Entry(
            id = "net-${UUID.randomUUID().toString().replace("-", "").take(24)}",
            cdpRequestId = cdpId,
            startedMillis = System.currentTimeMillis(),
            startedElapsedMillis = elapsedClock(),
            request = JSONObject()
                .put("url", request.optString("url"))
                .put("method", request.optString("method").take(32))
                .put("headers", sanitizeHeaders(request.optJSONObject("headers") ?: JSONObject()))
                .put("resourceType", params.optString("type").take(32))
                .put("hasPostData", hasPostData),
            requestContentType = contentType,
            requestBody = if (bodyState == "captured" && method in WRITE_METHODS && !hasPostData) ByteArray(0) else null,
            requestBodyState = bodyState,
        )
        val rawUrl = request.optString("url")
        entry.request.put("url", rawUrl.take(MAX_CAPTURE_URL_CHARS))
            .put("urlTruncated", rawUrl.length > MAX_CAPTURE_URL_CHARS)
        val replaced = byCdpId[cdpId]
        params.optJSONObject("redirectResponse")?.let { redirect ->
            replaced?.apply {
                response = responseSummary(redirect)
                mimeType = redirect.optString("mimeType").take(128)
                finished = true
                completedElapsedMillis = elapsedClock()
            }
        }
        byCdpId[cdpId] = entry
        entries.addLast(entry)
        trimEntries()
        return entry.id
    }

    @Synchronized fun saveRequestBody(cdpRequestId: String, postData: String): Boolean {
        if (!recording || !includeRequestBodies) return false
        val entry = byCdpId[cdpRequestId] ?: return false
        if (entry.requestBodyState != "pending") return false
        val bytes = postData.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_REQUEST_BODY_BYTES || requestBodyBytes + bytes.size > MAX_REQUEST_CAPTURE_BYTES) {
            entry.requestBodyState = "size_limit"
            return false
        }
        entry.requestBody = bytes.copyOf()
        entry.requestBodyState = "captured"
        requestBodyBytes += bytes.size
        return true
    }

    @Synchronized fun requestBodyCaptureState(cdpRequestId: String): String? = byCdpId[cdpRequestId]?.requestBodyState

    @Synchronized fun requestCaptureSource(cdpRequestId: String): JSONObject? {
        val entry = byCdpId[cdpRequestId] ?: return null
        return JSONObject()
            .put("url", entry.request.optString("url"))
            .put("method", entry.request.optString("method"))
            .put("contentType", entry.requestContentType)
    }

    @Synchronized fun markRequestBodyUnavailable(cdpRequestId: String, reason: String) {
        val entry = byCdpId[cdpRequestId] ?: return
        if (entry.requestBodyState == "pending") entry.requestBodyState = reason.take(32)
    }

    @Synchronized fun clearSensitiveRequestBodies() {
        entries.forEach { entry ->
            entry.requestBody?.fill(0)
            entry.requestBody = null
            if (entry.requestBodyState == "captured" || entry.requestBodyState == "pending") {
                entry.requestBodyState = "cleared_by_revocation"
            }
        }
        requestBodyBytes = 0
        includeRequestBodies = false
    }

    private fun trimEntries() {
        var metadataBytes = entries.sumOf { metadataSize(it) }
        while (entries.size > MAX_ENTRIES || metadataBytes > MAX_METADATA_BYTES) {
            val removed = entries.removeFirst()
            if (byCdpId[removed.cdpRequestId] === removed) byCdpId.remove(removed.cdpRequestId)
            metadataBytes -= metadataSize(removed)
            removed.requestBody?.let { bytes -> bytes.fill(0); requestBodyBytes = (requestBodyBytes - bytes.size).coerceAtLeast(0) }
            removed.requestBody = null
            removed.responseBodyFile?.let(::removeArtifact)
            droppedCount++
        }
    }

    @Synchronized fun onResponse(params: JSONObject) {
        if (!recording) return
        val entry = byCdpId[params.optString("requestId")] ?: return
        val response = params.optJSONObject("response") ?: return
        entry.mimeType = response.optString("mimeType").take(128)
        val responseHeaders = response.optJSONObject("headers") ?: JSONObject()
        entry.contentLength = responseHeaders.optString("content-length").toLongOrNull() ?: -1L
        entry.response = responseSummary(response)
        trimEntries()
    }

    @Synchronized fun onFinished(requestId: String) {
        if (!recording) return
        byCdpId[requestId]?.apply { finished = true; completedElapsedMillis = elapsedClock() }
    }

    @Synchronized fun onFailed(requestId: String, error: String) {
        if (!recording) return
        byCdpId[requestId]?.apply { finished = true; completedElapsedMillis = elapsedClock(); failure = error.take(128) }
    }

    @Synchronized fun appendStreamingBody(cdpRequestId: String, bytes: ByteArray): Boolean {
        if (!recording || !includeBodies) return false
        val entry = byCdpId[cdpRequestId] ?: return false
        entry.streamingBodyActive = true
        if (entry.responseBodyFile == null) {
            entry.responseBodyFile = writeArtifact(entry.id, ".body", ByteArray(0)) ?: return false
        }
        val perEntryRemaining = (MAX_BODY_BYTES - entry.responseBodyBytes).coerceAtLeast(0)
        val totalRemaining = (MAX_TOTAL_BODY_BYTES - bodyBytes).coerceAtLeast(0)
        val accepted = boundedBodyPrefix(bytes, perEntryRemaining, totalRemaining)
        if (accepted.isNotEmpty()) {
            val file = entry.responseBodyFile ?: return false
            if (!makeArtifactRoom(file.name, accepted.size.toLong())) return false
            val appendResult = runCatching {
                BrowserArtifactFiles.appendNetworkArtifact(filesRoot, file.name, accepted, MAX_ARTIFACT_BYTES)
            }
            if (appendResult.isFailure) {
                val appendFailure = appendResult.exceptionOrNull()
                artifactError = if (appendFailure is SecurityException || appendFailure is java.nio.file.AccessDeniedException) {
                    "ARTIFACT_APPEND_ACCESS_DENIED"
                } else if (appendFailure is UnsupportedOperationException) {
                    "ARTIFACT_SECURE_STREAM_UNAVAILABLE"
                } else {
                    "ARTIFACT_APPEND_FAILED"
                }
                return false
            }
            entry.responseBodyBytes += accepted.size
            bodyBytes += accepted.size
            trackArtifact(file, appendResult.getOrThrow())
            if (!artifacts.containsKey(file.name)) entry.responseBodyFile = null
        }
        if (accepted.size < bytes.size) entry.responseBodyTruncated = true
        return accepted.size == bytes.size && entry.responseBodyFile?.let {
            BrowserArtifactFiles.networkArtifactExists(filesRoot, it.name)
        } == true
    }

    @Synchronized fun finishStreamingBody(cdpRequestId: String) {
        byCdpId[cdpRequestId]?.apply { streamingBodyActive = false; finished = true }
    }

    @Synchronized fun truncateStreamingBody(cdpRequestId: String) {
        byCdpId[cdpRequestId]?.apply { streamingBodyActive = false; responseBodyTruncated = true }
    }

    fun saveResponseBody(cdpRequestId: String, result: JSONObject, expectedCaptureId: String): Boolean {
        synchronized(this) {
            if (!recording || !includeBodies || captureId != expectedCaptureId || byCdpId[cdpRequestId] == null) return false
        }
        val body = result.opt("body")
        if (body !is String) {
            recordBodyError(cdpRequestId, expectedCaptureId, "BODY_CONTENT_MISSING")
            return false
        }
        val raw = body
        val base64Encoded = result.optBoolean("base64Encoded")
        val maxSaveBytes = synchronized(this) {
            if (!recording || !includeBodies || captureId != expectedCaptureId) return false
            (MAX_BODY_BYTES - (byCdpId[cdpRequestId]?.responseBodyBytes ?: MAX_BODY_BYTES)).coerceAtLeast(0)
                .coerceAtMost((MAX_TOTAL_BODY_BYTES - bodyBytes).coerceAtLeast(0))
        }
        val decoded = runCatching { decodeBounded(raw, base64Encoded, maxSaveBytes) }.getOrNull() ?: run {
            recordBodyError(cdpRequestId, expectedCaptureId, "BODY_DECODE_FAILED")
            return false
        }
        synchronized(this) {
            if (!recording || !includeBodies || captureId != expectedCaptureId) return false
            val entry = byCdpId[cdpRequestId] ?: return false
            if (entry.responseBodyFile != null) return true
            val saved = decoded.bytes
            if (saved.isEmpty() && decoded.truncated) {
                entry.responseBodyTruncated = true
                return false
            }
            if (!makeArtifactRoom(null, saved.size.toLong())) return false
            val file = writeArtifact(entry.id, ".body", saved) ?: return false
            entry.responseBodyFile = file
            entry.responseBodyBytes = saved.size
            entry.responseBodyTruncated = decoded.truncated
            bodyBytes += saved.size
            return true
        }
    }

    @Synchronized private fun recordBodyError(cdpRequestId: String, expectedCaptureId: String, code: String) {
        if (recording && includeBodies && captureId == expectedCaptureId && byCdpId[cdpRequestId] != null) {
            artifactError = code
        }
    }

    @Synchronized fun list(cursor: Int, limit: Int): JSONObject {
        val values = entries.toList()
        val start = cursor.coerceIn(0, values.size)
        val requestedEnd = minOf(values.size, start + limit.coerceIn(1, MAX_PAGE))
        val page = JSONArray()
        var end = start
        for (index in start until requestedEnd) {
            page.put(publicEntry(values[index]))
            val candidate = listResponse(start, end + 1, requestedEnd, values.size, page)
            if (candidate.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_LIST_BYTES) {
                page.remove(page.length() - 1)
                break
            }
            end++
        }
        return listResponse(start, end, requestedEnd, values.size, page)
    }

    @Synchronized fun body(entryId: String): JSONObject? {
        val entry = entries.firstOrNull { it.id == entryId } ?: return null
        val file = entry.responseBodyFile?.takeIf { BrowserArtifactFiles.networkArtifactExists(filesRoot, it.name) } ?: return null
        return JSONObject()
            .put("entryId", entry.id)
            .put("artifactPath", guestPath(file))
            .put("mimeType", entry.mimeType.ifBlank { "application/octet-stream" })
            .put("bytes", entry.responseBodyBytes)
            .put("truncated", entry.responseBodyTruncated || eventLoss)
            .put("bodyStreaming", entry.streamingBodyActive)
            .put("finished", entry.finished)
            .put("complete", entry.finished && entry.failure == null && !entry.responseBodyTruncated && !entry.streamingBodyActive && !eventLoss)
            .put("redacted", false)
    }

    @Synchronized fun entryByCdpId(cdpRequestId: String): JSONObject? {
        val entry = byCdpId[cdpRequestId] ?: return null
        return JSONObject()
            .put("entryId", entry.id)
            .put("mimeType", entry.mimeType)
            .put("contentLength", entry.contentLength)
    }

    @Synchronized fun replaySource(entryId: String): JSONObject? {
        val entry = entries.firstOrNull { it.id == entryId } ?: return null
        val url = entry.request.optString("url")
        val method = entry.request.optString("method").uppercase(Locale.ROOT)
        val readOnly = method in setOf("GET", "HEAD") && !entry.request.optBoolean("hasPostData")
        val writeReplayable = method in WRITE_METHODS && entry.requestBodyState == "captured" &&
            entry.requestBody != null && (entry.requestBody!!.isEmpty() || entry.requestContentType in REPLAYABLE_REQUEST_MIMES)
        if (!safeHttpUrl(url) || entry.cdpRequestId.startsWith("worker:") || entry.request.optString("resourceType") != "XHR" ||
            entry.request.optBoolean("urlTruncated") || (!readOnly && !writeReplayable)) return null
        return JSONObject()
            .put("requestId", entry.cdpRequestId)
            .put("url", url)
            .put("method", method)
            .put("resourceType", entry.request.optString("resourceType"))
            .put("hasPostData", entry.request.optBoolean("hasPostData"))
            .put("requestBody", entry.requestBody?.let { String(it, StandardCharsets.UTF_8) } ?: JSONObject.NULL)
            .put("requestBodyBytes", entry.requestBody?.size ?: 0)
            .put("requestBodySha256", entry.requestBody?.let(::sha256) ?: "")
            .put("requestBodyState", entry.requestBodyState)
            .put("requestContentType", entry.requestContentType)
            .put("headers", JSONObject(entry.request.optJSONObject("headers")?.toString() ?: "{}"))
    }

    @Synchronized fun replayPreview(entryId: String): JSONObject? {
        val source = replaySource(entryId) ?: return null
        val uri = runCatching { java.net.URI(source.getString("url")) }.getOrNull() ?: return null
        val origin = "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}"
        return JSONObject()
            .put("captureId", captureId ?: JSONObject.NULL)
            .put("entryId", entryId)
            .put("method", source.getString("method"))
            .put("origin", origin.take(512))
            .put("path", (uri.rawPath.ifBlank { "/" }).take(1024))
            .put("queryOmitted", uri.rawQuery != null)
            .put("querySha256", uri.rawQuery?.let { sha256(it.toByteArray(StandardCharsets.UTF_8)) } ?: JSONObject.NULL)
            .put("requestBodyBytes", source.optInt("requestBodyBytes"))
            .put("requestBodySha256", source.optString("requestBodySha256"))
            .put("contentType", source.optString("requestContentType"))
            .put("credentials", "same-origin")
            .put("sensitiveHeadersCopied", false)
            .put("possibleDuplicateSideEffects", source.getString("method") !in setOf("GET", "HEAD"))
    }

    /** Full captured payload for the native risk prompt only; never included in tool list/status output. */
    @Synchronized fun replayBodyForNativePrompt(entryId: String): String? {
        val source = replaySource(entryId) ?: return null
        if (source.getString("method") !in WRITE_METHODS || source.optInt("requestBodyBytes") > MAX_REQUEST_BODY_BYTES) return null
        return source.optString("requestBody").takeIf { it.toByteArray(StandardCharsets.UTF_8).size <= MAX_REQUEST_BODY_BYTES }
    }

    @Synchronized fun exportHar(includeBodies: Boolean, eventDrops: Long = 0L): JSONObject? {
        if (captureId == null) return null
        val logEntries = JSONArray()
        var harBodyBytes = 0
        var harBodiesOmitted = false
        entries.forEach { entry ->
            var bodyOmittedFromHar = false
            val started = java.time.Instant.ofEpochMilli(entry.startedMillis).toString()
            val req = JSONObject()
                .put("method", entry.request.optString("method"))
                .put("url", sanitizeUrl(entry.request.optString("url")))
                .put("httpVersion", entry.response.optString("protocol", ""))
                .put("headers", toHarHeaders(entry.request.optJSONObject("headers") ?: JSONObject()))
                .put("queryString", JSONArray())
                .put("cookies", JSONArray())
                .put("headersSize", -1)
                .put("bodySize", -1)
            val response = JSONObject()
                .put("status", entry.response.optInt("status"))
                .put("statusText", entry.response.optString("statusText"))
                .put("httpVersion", entry.response.optString("protocol", ""))
                .put("headers", toHarHeaders(entry.response.optJSONObject("headers") ?: JSONObject()))
                .put("cookies", JSONArray())
                .put("content", JSONObject()
                    .put("size", entry.responseBodyBytes)
                    .put("mimeType", entry.mimeType.ifBlank { "application/octet-stream" }))
                .put("redirectURL", "")
                .put("headersSize", -1)
                .put("bodySize", entry.responseBodyBytes)
            if (includeBodies && entry.responseBodyFile?.let {
                    BrowserArtifactFiles.networkArtifactExists(filesRoot, it.name)
                } == true &&
                harBodyBytes + entry.responseBodyBytes <= MAX_HAR_BODY_BYTES
            ) {
                val body = BrowserArtifactFiles.readNetworkArtifact(filesRoot, entry.responseBodyFile!!.name,
                    MAX_ARTIFACT_BYTES) ?: error("Network body artifact disappeared")
                val textual = entry.mimeType.startsWith("text/") || entry.mimeType.contains("json") ||
                    entry.mimeType.contains("javascript") || entry.mimeType.contains("xml")
                response.getJSONObject("content")
                    .put("text", if (textual) String(body, StandardCharsets.UTF_8) else Base64.encodeToString(body, Base64.NO_WRAP))
                if (!textual) response.getJSONObject("content").put("encoding", "base64")
                harBodyBytes += body.size
            } else if (includeBodies && entry.responseBodyFile?.let {
                    BrowserArtifactFiles.networkArtifactExists(filesRoot, it.name)
                } == true) {
                harBodiesOmitted = true
                bodyOmittedFromHar = true
            }
            logEntries.put(JSONObject()
                .put("startedDateTime", started)
                .put("time", 0)
                .put("request", req)
                .put("response", response)
                .put("cache", JSONObject())
                .put("timings", JSONObject().put("send", -1).put("wait", -1).put("receive", -1))
                .put("_browser", JSONObject()
                    .put("resourceType", entry.request.optString("resourceType"))
                    .put("failed", entry.failure ?: JSONObject.NULL)
                    .put("bodyArtifact", entry.responseBodyFile?.let(::guestPath) ?: JSONObject.NULL)
                    .put("bodyTruncated", entry.responseBodyTruncated || eventLoss)
                    .put("bodyStreaming", entry.streamingBodyActive)
                    .put("bodyOmittedFromHar", bodyOmittedFromHar)))
        }
        var metadataOmitted = false
        val browserMeta = JSONObject().put("eventDrops", eventDrops).put("bodyOmitted", harBodiesOmitted)
            .put("webSocketFramesExcluded", true)
        val har = JSONObject()
            .put("log", JSONObject()
                .put("version", "1.2")
                .put("creator", JSONObject().put("name", "DSHBox Browser").put("version", "1"))
                .put("entries", logEntries))
            .put("_browser", browserMeta)
        var bytes = har.toString().toByteArray(StandardCharsets.UTF_8)
        while (bytes.size > MAX_HAR_BYTES && logEntries.length() > 0) {
            logEntries.remove(logEntries.length() - 1)
            metadataOmitted = true
            bytes = har.toString().toByteArray(StandardCharsets.UTF_8)
        }
        browserMeta.put("metadataOmitted", metadataOmitted)
            .put("truncated", eventLoss || droppedCount > 0 || eventDrops > 0 || harBodiesOmitted || metadataOmitted ||
                entries.any { it.responseBodyTruncated || it.streamingBodyActive })
        bytes = har.toString().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_HAR_BYTES) return null
        val file = writeArtifact(captureId!!, ".har", bytes) ?: return null
        return JSONObject()
            .put("artifactPath", guestPath(file))
            .put("mimeType", "application/json")
            .put("bytes", bytes.size)
            .put("entryCount", logEntries.length())
            .put("redacted", !includeBodies)
    }

    @Synchronized fun clearRulesArtifacts() {
        recording = false
        clearMemory(deleteArtifacts = true)
    }

    private fun clearMemory(deleteArtifacts: Boolean) {
        if (deleteArtifacts) clearArtifacts()
        entries.forEach { it.requestBody?.fill(0) }
        entries.clear()
        byCdpId.clear()
        bodyBytes = 0
        requestBodyBytes = 0
        droppedCount = 0
        eventLoss = false
        recording = false
    }

    private fun publicEntry(entry: Entry): JSONObject {
        val request = JSONObject(entry.request.toString())
            .put("url", sanitizeUrl(entry.request.optString("url")))
        request.remove("documentUrl")
        val response = JSONObject(entry.response.toString())
            .put("url", sanitizeUrl(entry.response.optString("url")))
        return JSONObject()
            .put("entryId", entry.id)
            .put("startedAtMillis", entry.startedMillis)
            .put("durationMillis", boundedDurationMillis(entry.startedElapsedMillis, entry.completedElapsedMillis) ?: JSONObject.NULL)
            .put("request", request)
            .put("response", response)
            .put("finished", entry.finished)
            .put("failed", entry.failure ?: JSONObject.NULL)
            .put("bodyAvailable", entry.responseBodyFile?.let {
                BrowserArtifactFiles.networkArtifactExists(filesRoot, it.name)
            } == true)
            .put("bodyBytes", entry.responseBodyBytes)
            .put("bodyTruncated", entry.responseBodyTruncated || eventLoss)
            .put("bodyStreaming", entry.streamingBodyActive)
            .put("requestBodyState", entry.requestBodyState)
            .put("requestBodyBytes", entry.requestBody?.size ?: 0)
            .put("requestContentType", entry.requestContentType)
    }

    private fun responseSummary(response: JSONObject): JSONObject {
        val headers = response.optJSONObject("headers") ?: JSONObject()
        return JSONObject()
            .put("url", sanitizeUrl(response.optString("url")))
            .put("status", response.optInt("status"))
            .put("statusText", response.optString("statusText").take(128))
            .put("headers", sanitizeHeaders(headers))
            .put("mimeType", response.optString("mimeType").take(128))
            .put("fromDiskCache", response.optBoolean("fromDiskCache"))
            .put("fromServiceWorker", response.optBoolean("fromServiceWorker"))
            .put("protocol", response.optString("protocol").take(32))
    }

    private fun sanitizeHeaders(headers: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = headers.keys()
        var copied = 0
        while (keys.hasNext() && copied < MAX_HEADERS) {
            val key = keys.next()
            if (sensitiveHeader(key)) continue
            val value = headers.opt(key)
            if (value is String || value is Number || value is Boolean) {
                out.put(key.take(64), value.toString().take(MAX_HEADER_VALUE_CHARS))
                copied++
            }
        }
        return out
    }

    private fun listResponse(start: Int, end: Int, requestedEnd: Int, total: Int, page: JSONArray) = JSONObject()
        .put("captureId", captureId ?: JSONObject.NULL)
        .put("cursor", start)
        .put("nextCursor", if (end < total) end else JSONObject.NULL)
        .put("entries", page)
        .put("droppedCount", droppedCount)
        .put("truncated", droppedCount > 0 || end < requestedEnd)

    private fun toHarHeaders(headers: JSONObject): JSONArray {
        val output = JSONArray()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.optString(key)
            output.put(JSONObject().put("name", key.take(128)).put("value", value.take(1024)))
        }
        return output
    }

    private fun writeArtifact(id: String, suffix: String, bytes: ByteArray): File? {
        var stage = "ARTIFACT_DIRECTORY"
        return runCatching {
            BrowserArtifactFiles.ensureNetworkDirectory(filesRoot)
            scanArtifacts()
            val name = "$id$suffix"
            stage = "ARTIFACT_PATH_VALIDATE"
            require(OWNED_ARTIFACT.matches(name) && bytes.size <= MAX_ARTIFACT_BYTES) { "UNSAFE_ARTIFACT_PATH" }
            if (!makeArtifactRoom(name, bytes.size.toLong())) error("ARTIFACT_QUOTA_DELETE_FAILED")
            stage = "ARTIFACT_SECURE_WRITE"
            val target = BrowserArtifactFiles.writeNetworkArtifactAtomically(filesRoot, name, bytes, MAX_ARTIFACT_BYTES)
            trackArtifact(target, bytes.size.toLong())
            target.takeIf { artifacts.containsKey(it.name) }
        }.onFailure { error ->
            artifactError = when {
                error.message == "UNSAFE_ARTIFACT_PATH" || error.message?.contains("Unsafe") == true ||
                    error.message?.contains("Invalid app-private") == true -> "ARTIFACT_PATH_UNSAFE"
                error.message == "ARTIFACT_QUOTA_DELETE_FAILED" -> "ARTIFACT_DELETE_FAILED"
                error is UnsupportedOperationException -> "ARTIFACT_SECURE_STREAM_UNAVAILABLE"
                error is SecurityException || error is java.nio.file.AccessDeniedException -> "${stage}_ACCESS_DENIED"
                stage == "ARTIFACT_DIRECTORY" -> "ARTIFACT_DIRECTORY_FAILED"
                stage == "ARTIFACT_PATH_VALIDATE" -> "ARTIFACT_PATH_VALIDATE_FAILED"
                stage == "ARTIFACT_SECURE_WRITE" -> "ARTIFACT_SECURE_WRITE_FAILED"
                else -> "ARTIFACT_ATOMIC_MOVE_FAILED"
            }
        }
        .getOrNull()
    }

    private fun scanArtifacts() {
        if (artifactsScanned) return
        BrowserArtifactFiles.listNetworkArtifacts(filesRoot).sortedBy { it.lastModifiedMillis }.forEach { artifact ->
            if (OWNED_ARTIFACT.matches(artifact.name)) {
                val file = File(BrowserArtifactFiles.ensureNetworkDirectory(filesRoot), artifact.name)
                artifacts[artifact.name] = file to artifact.sizeBytes
                artifactBytes += artifact.sizeBytes
            }
        }
        artifactsScanned = true
    }

    private fun trackArtifact(file: File, size: Long) {
        artifacts.remove(file.name)?.let { artifactBytes -= it.second }
        artifacts[file.name] = file to size
        artifactBytes += size
    }

    private fun makeArtifactRoom(replacingName: String?, incomingBytes: Long): Boolean {
        val existing = replacingName?.let(artifacts::get)?.second ?: 0L
        while (artifacts.size - (if (replacingName != null && artifacts.containsKey(replacingName)) 1 else 0) + 1 > MAX_ARTIFACT_COUNT ||
            artifactBytes - existing + incomingBytes > MAX_ARTIFACT_DISK_BYTES) {
            val oldest = artifacts.entries.firstOrNull { it.key != replacingName } ?: run {
                artifactError = "ARTIFACT_QUOTA_EXCEEDED"
                return false
            }
            val removed = runCatching { deleteArtifact(filesRoot, oldest.key) }.isSuccess
            if (!removed) {
                artifactError = "ARTIFACT_DELETE_FAILED"
                return false
            }
            artifacts.remove(oldest.key)
            artifactBytes -= oldest.value.second
            entries.filter { it.responseBodyFile?.name == oldest.key }.forEach {
                it.responseBodyFile = null
                it.responseBodyTruncated = true
            }
        }
        return true
    }

    private data class BoundedBody(val bytes: ByteArray, val truncated: Boolean)

    private fun decodeBounded(raw: String, base64Encoded: Boolean, budget: Int): BoundedBody {
        if (!base64Encoded) {
            val encoder = StandardCharsets.UTF_8.newEncoder()
            var maxChars = minOf(raw.length, budget + 1)
            if (maxChars < raw.length && maxChars > 0 && Character.isHighSurrogate(raw[maxChars - 1])) maxChars--
            val prefix = raw.substring(0, maxChars)
            val buffer = java.nio.ByteBuffer.allocate(budget + 4)
            val result = encoder.encode(java.nio.CharBuffer.wrap(prefix), buffer, maxChars == raw.length)
            if (result.isError) result.throwException()
            val encodedSize = buffer.position()
            val truncated = raw.length > maxChars || encodedSize > budget || result.isOverflow
            val bytes = ByteArray(minOf(encodedSize, budget))
            buffer.flip(); buffer.get(bytes)
            return BoundedBody(bytes, truncated)
        }
        val decoder = java.util.Base64.getDecoder().wrap(object : InputStream() {
            private var position = 0
            override fun read(): Int {
                if (position >= raw.length) return -1
                val value = raw[position++].code
                if (value > 0x7f) throw java.io.IOException("Non-ASCII Base64 input")
                return value
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (position >= raw.length) return -1
                val count = minOf(length, raw.length - position)
                for (index in 0 until count) {
                    val value = raw[position++].code
                    if (value > 0x7f) throw java.io.IOException("Non-ASCII Base64 input")
                    bytes[offset + index] = value.toByte()
                }
                return count
            }
        })
        val output = java.io.ByteArrayOutputStream(minOf(budget, 8192))
        val buffer = ByteArray(minOf(8192, budget + 1).coerceAtLeast(1))
        var remaining = budget + 1
        while (remaining > 0) {
            val count = decoder.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            output.write(buffer, 0, count)
            remaining -= count
        }
        val bytes = output.toByteArray()
        return if (bytes.size > budget) BoundedBody(bytes.copyOf(budget), true) else BoundedBody(bytes, false)
    }

    private fun removeArtifact(file: File) {
        if (runCatching { BrowserArtifactFiles.deleteNetworkArtifact(filesRoot, file.name) }.isSuccess) {
            artifacts.remove(file.name)?.let { artifactBytes -= it.second }
        } else {
            artifactError = "ARTIFACT_DELETE_FAILED"
        }
    }

    private fun clearArtifacts() {
        if (runCatching { BrowserArtifactFiles.clearNetworkArtifacts(filesRoot) }.isSuccess) {
            artifacts.clear()
            artifactBytes = 0
            artifactsScanned = true
        } else {
            artifactError = "ARTIFACT_DELETE_FAILED"
        }
    }

    private fun guestPath(file: File): String = "/opt/browser/out/network/${file.name}"

    private fun sanitizeUrl(value: String): String = runCatching {
        val uri = java.net.URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) return@runCatching ""
        java.net.URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString().take(MAX_URL_CHARS)
    }.getOrDefault("")

    private fun safeHttpUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sensitiveHeader(name: String): Boolean {
        val key = name.lowercase(Locale.ROOT)
        return key in setOf("cookie", "set-cookie", "authorization", "proxy-authorization", "x-api-key", "x-csrf-token") ||
            key.contains("token") || key.contains("secret") || key.contains("password") || key.contains("api-key")
    }

    private fun metadataSize(entry: Entry): Int = entry.request.toString().toByteArray(StandardCharsets.UTF_8).size +
        entry.response.toString().toByteArray(StandardCharsets.UTF_8).size + 192

    companion object {
        private const val MAX_ENTRIES = 500
        private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        private const val MAX_CAPTURE_URL_CHARS = 4096
        private const val MAX_PAGE = 20
        private const val MAX_LIST_BYTES = 12 * 1024
        private const val MAX_HEADERS = 16
        private const val MAX_HEADER_VALUE_CHARS = 128
        private const val MAX_URL_CHARS = 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_TOTAL_BODY_BYTES = 8 * 1024 * 1024
        const val MAX_REQUEST_BODY_BYTES = 8 * 1024
        const val MAX_REQUEST_CAPTURE_BYTES = 512 * 1024
        val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")
        val REPLAYABLE_REQUEST_MIMES = setOf("application/json", "application/x-www-form-urlencoded", "text/plain")
        private const val MAX_HAR_BYTES = 4 * 1024 * 1024
        private const val MAX_HAR_BODY_BYTES = 2 * 1024 * 1024
        private const val MAX_ARTIFACT_BYTES = MAX_HAR_BYTES
        private const val MAX_ARTIFACT_COUNT = 32
        private const val MAX_ARTIFACT_DISK_BYTES = 12L * 1024 * 1024
        private val OWNED_ARTIFACT = Regex("^(?:net-[a-f0-9]{24}|cap-[a-f0-9]{20})\\.(?:body|har)$")
    }
}
