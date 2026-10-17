package com.dshbox.browser.devtools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.io.File
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import java.nio.file.SecureDirectoryStream

class WebSocketCaptureStoreTest {
    @Test
    fun handshakeHttpOriginRedactionDoesNotInventCaptureLoss() {
        val store = WebSocketCaptureStore()
        store.begin("cap-origin", includeBodies = true)
        onEvent(store, "Network.webSocketCreated", JSONObject().put("requestId", "origin-socket").put("url", "wss://example.test/socket"))
        onEvent(store, "Network.webSocketWillSendHandshakeRequest", JSONObject().put("requestId", "origin-socket")
            .put("timestamp", 1.0).put("wallTime", 2.0).put("request", JSONObject()
                .put("headers", JSONObject().put("Origin", "https://example.test/?token=private"))))
        val data = store.list(0, 20, 0)
        assertFalse(data.getBoolean("loss"))
        assertFalse(data.getBoolean("truncated"))
        val handshake = data.getJSONArray("frames").getJSONObject(1)
        assertTrue(handshake.getBoolean("requestHeadersRedacted"))
        assertEquals("https://example.test/", handshake.getJSONObject("requestHeaders").getString("Origin"))
        assertFalse(data.toString().contains("private"))
    }
    @Test
    fun exportsFramesAsIndependentBoundedArtifact() {
        val root = Files.createTempDirectory("websocket-export-test").toFile()
        try {
        Files.newDirectoryStream(root.toPath()).use { assumeTrue("Run the Linux zero-skip storage gate", it is SecureDirectoryStream<*>) }
        val store = WebSocketCaptureStore()
        store.begin("cap-export", includeBodies = true)
        onEvent(store, "Network.webSocketCreated", JSONObject().put("requestId", "cdp-export")
            .put("url", "wss://example.test/events?token=secret"))
        onEvent(store, "Network.webSocketWillSendHandshakeRequest", JSONObject()
            .put("requestId", "cdp-export").put("request", JSONObject()
                .put("headers", JSONObject().put("Cookie", "private").put("X-Mode", "chat"))))
        onEvent(store, frameEvent("Network.webSocketFrameSent", "cdp-export", 1, "hello"))

        val exported = store.export(root, 0, exportBodies = true) ?: error("WebSocket export failed")
        assertEquals("application/json", exported.getString("mimeType"))
        assertEquals(3, exported.getInt("frameCount"))
        assertTrue(exported.getBoolean("payloadsIncluded"))
        assertTrue(exported.getBoolean("payloadUnredacted"))
        assertFalse(exported.getBoolean("redacted"))
        assertTrue(exported.getBoolean("headersRedacted"))
        assertTrue(exported.getString("artifactPath").endsWith(".ws.json"))
        val file = File(root, "browser-tools/out/network/${File(exported.getString("artifactPath")).name}")
        val document = JSONObject(file.readText())
        assertEquals("dshbox-browser-websocket-capture", document.getString("format"))
        assertEquals(3, document.getJSONArray("frames").length())
        assertTrue(document.getBoolean("payloadUnredacted"))
        assertFalse(document.getBoolean("redacted"))
        assertTrue(document.getBoolean("headersRedacted"))
        assertFalse(document.getJSONArray("frames").getJSONObject(0).getString("url").contains("secret"))
        val privateExport = store.export(root, 0, exportBodies = false) ?: error("Metadata export failed")
        assertFalse(privateExport.getBoolean("payloadsIncluded"))
        assertFalse(privateExport.getBoolean("payloadUnredacted"))
        assertTrue(privateExport.getBoolean("redacted"))
        val metadataFile = File(root, "browser-tools/out/network/${File(privateExport.getString("artifactPath")).name}")
        val metadata = JSONObject(metadataFile.readText())
        assertEquals(0, metadata.getInt("payloadBytes"))
        assertFalse(metadata.toString().contains("hello"))
        assertFalse(metadata.getJSONArray("frames").getJSONObject(2).has("payload"))
        assertTrue(metadata.getJSONArray("frames").getJSONObject(2).getBoolean("payloadOmitted"))
        assertEquals("hello", store.list(0, 20, 0).getJSONArray("frames").getJSONObject(2).getString("payload"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun recordsLifecycleTextAndBinaryFramesWithSanitizedMetadata() {
        val store = WebSocketCaptureStore()
        store.begin("cap-test", includeBodies = true)
        onEvent(store, "Network.webSocketCreated", JSONObject()
            .put("requestId", "cdp-1").put("url", "wss://example.test/chat?token=secret#fragment"))
        onEvent(store, "Network.webSocketWillSendHandshakeRequest", JSONObject()
            .put("requestId", "cdp-1").put("timestamp", 1.25).put("wallTime", 10.0)
            .put("request", JSONObject().put("headers", JSONObject()
                .put("Cookie", "private").put("Authorization", "Bearer secret").put("X-Mode", "chat"))))
        onEvent(store, "Network.webSocketHandshakeResponseReceived", JSONObject()
            .put("requestId", "cdp-1").put("timestamp", 1.5)
            .put("response", JSONObject().put("status", 101).put("statusText", "Switching Protocols")
                .put("headers", JSONObject().put("Set-Cookie", "private").put("Upgrade", "websocket"))))
        onEvent(store, frameEvent("Network.webSocketFrameSent", "cdp-1", 1, "你好"))
        onEvent(store, frameEvent("Network.webSocketFrameReceived", "cdp-1", 2,
            Base64.getEncoder().encodeToString(byteArrayOf(0, 1, -1))))
        val data = store.list(cursor = 0, limit = 20, eventDrops = 0)
        val frames = data.getJSONArray("frames")
        assertEquals(5, frames.length())
        assertFalse(data.getBoolean("truncated"))
        assertTrue(data.getBoolean("payloadUnredacted"))
        assertFalse(data.getBoolean("redacted"))
        assertTrue(data.getBoolean("headersRedacted"))
        val created = frames.getJSONObject(0)
        assertEquals("created", created.getString("kind"))
        assertEquals("wss://example.test/chat", created.getString("url"))
        val requestHeaders = frames.getJSONObject(1).getJSONObject("requestHeaders")
        assertFalse(requestHeaders.has("Cookie"))
        assertFalse(requestHeaders.has("Authorization"))
        assertTrue(frames.getJSONObject(1).getBoolean("requestHeadersRedacted"))
        assertTrue(data.getBoolean("headersRedacted"))
        assertEquals("chat", requestHeaders.getString("X-Mode"))
        assertFalse(frames.getJSONObject(2).getJSONObject("responseHeaders").has("Set-Cookie"))
        val text = frames.getJSONObject(3)
        assertEquals("outgoing", text.getString("direction"))
        assertEquals("text", text.getString("payloadEncoding"))
        assertEquals("你好", text.getString("payload"))
        assertEquals(6, text.getInt("payloadBytes"))
        val binary = frames.getJSONObject(4)
        assertEquals("base64", binary.getString("payloadEncoding"))
        assertEquals("AAH/", binary.getString("payload"))
        assertEquals(3, binary.getInt("payloadBytes"))
        onEvent(store, "Network.webSocketFrameError", JSONObject().put("requestId", "cdp-1")
            .put("timestamp", 1.75).put("errorMessage", "Authorization: secret frame failure"))
        onEvent(store, "Network.webSocketClosed", JSONObject().put("requestId", "cdp-1").put("timestamp", 2.0))
        val completed = store.list(cursor = 0, limit = 20, eventDrops = 0)
        val completedFrames = completed.getJSONArray("frames")
        assertEquals(7, completedFrames.length())
        assertEquals("error", completedFrames.getJSONObject(5).getString("kind"))
        assertTrue(completedFrames.getJSONObject(5).getBoolean("detailRedacted"))
        assertFalse(completedFrames.getJSONObject(5).getString("detail").contains("secret"))
        assertEquals("closed", completedFrames.getJSONObject(6).getString("kind"))
        assertTrue(completed.toString().toByteArray().size <= completed.getInt("maxPageBytes"))
    }

    @Test
    fun payloadIsOmittedWithoutOptInAndMissingCreationIsReportedIncomplete() {
        val store = WebSocketCaptureStore()
        store.begin("cap-redacted", includeBodies = false)
        onEvent(store, frameEvent("Network.webSocketFrameReceived", "cdp-before-start", 1, "private"))

        val data = store.list(0, 20, eventDrops = 0)
        val frame = data.getJSONArray("frames").getJSONObject(0)
        assertFalse(frame.has("payload"))
        assertTrue(frame.getBoolean("payloadOmitted"))
        assertTrue(frame.getBoolean("metadataIncomplete"))
        assertFalse(data.getBoolean("payloadUnredacted"))
        assertTrue(data.getBoolean("redacted"))
        assertTrue(data.getBoolean("truncated"))
    }

    @Test
    fun payloadAndFrameBoundsAreVisibleAndPaginationIsBounded() {
        val store = WebSocketCaptureStore()
        store.begin("cap-bounds", includeBodies = true)
        onEvent(store, "Network.webSocketCreated", JSONObject().put("requestId", "cdp-1").put("url", "wss://e.test/ws"))
        val text = "x".repeat(WebSocketCaptureStore.MAX_FRAME_PAYLOAD_BYTES + 2)
        onEvent(store, frameEvent("Network.webSocketFrameSent", "cdp-1", 1, text))
        val clipped = store.list(0, 100, 0).getJSONArray("frames").getJSONObject(1)
        assertEquals(WebSocketCaptureStore.MAX_FRAME_PAYLOAD_BYTES, clipped.getString("payload").toByteArray().size)
        assertTrue(clipped.getBoolean("frameTruncated"))

        repeat(WebSocketCaptureStore.MAX_FRAMES + 5) { index ->
            onEvent(store, frameEvent("Network.webSocketFrameSent", "cdp-1", 1, "m$index"))
        }
        val firstPage = store.list(0, 20, 0)
        assertEquals(20, firstPage.getJSONArray("frames").length())
        assertEquals(20, firstPage.getInt("nextCursor"))
        assertEquals(WebSocketCaptureStore.MAX_FRAMES, firstPage.getInt("total"))
        assertEquals(7, firstPage.getLong("droppedFrames"))
        assertTrue(firstPage.getBoolean("loss"))
    }

    @Test
    fun stoppedCaptureDoesNotAcceptEventsAndMissingPayloadSetsLoss() {
        val store = WebSocketCaptureStore()
        store.begin("cap-stop", includeBodies = true)
        onEvent(store, "Network.webSocketCreated", JSONObject().put("requestId", "cdp-1").put("url", "wss://e.test/ws"))
        store.stop()
        onEvent(store, frameEvent("Network.webSocketFrameSent", "cdp-1", 1, "late"))
        var frames = store.list(0, 20, 0).getJSONArray("frames")
        assertEquals(1, frames.length())
        assertFalse(store.isRecording())

        store.begin("cap-bad", includeBodies = true)
        onEvent(store, "Network.webSocketFrameReceived", JSONObject()
            .put("requestId", "cdp-2").put("timestamp", 2.0).put("response", JSONObject().put("opcode", 1)))
        val result = store.list(0, 20, 0)
        frames = result.getJSONArray("frames")
        assertTrue(frames.getJSONObject(0).getBoolean("payloadUnavailable"))
        assertTrue(result.getBoolean("loss"))
    }

    @Test
    fun perCapturePayloadQuotaIsEnforced() {
        val store = WebSocketCaptureStore()
        store.begin("cap-total", includeBodies = true)
        onEvent(store, "Network.webSocketCreated", JSONObject().put("requestId", "cdp-1").put("url", "wss://e.test/ws"))
        val payload = "a".repeat(WebSocketCaptureStore.MAX_FRAME_PAYLOAD_BYTES)
        repeat(WebSocketCaptureStore.MAX_CAPTURE_PAYLOAD_BYTES / WebSocketCaptureStore.MAX_FRAME_PAYLOAD_BYTES) {
            onEvent(store, frameEvent("Network.webSocketFrameSent", "cdp-1", 1, payload))
        }
        onEvent(store, frameEvent("Network.webSocketFrameSent", "cdp-1", 1, "over"))
        val data = store.list(129, 20, 0)
        assertEquals(WebSocketCaptureStore.MAX_CAPTURE_PAYLOAD_BYTES, data.getInt("payloadBytes"))
        val last = data.getJSONArray("frames").getJSONObject(0)
        assertTrue(last.getBoolean("frameTruncated"))
        assertTrue(data.getBoolean("truncated"))
    }

    @Test
    fun escapedTextPayloadCannotBlockPaginationAndSensitiveHeaderNamesAreRedacted() {
        val store = WebSocketCaptureStore()
        store.begin("cap-page-safe", includeBodies = true)
        onEvent(store, "Network.webSocketCreated", JSONObject().put("requestId", "cdp-1").put("url", "wss://e.test/ws"))
        onEvent(store, "Network.webSocketWillSendHandshakeRequest", JSONObject().put("requestId", "cdp-1")
            .put("timestamp", 1.0).put("wallTime", 2.0).put("request", JSONObject().put("headers", JSONObject()
                .put("X-Csrf-Token", "csrf-private").put("X-Session-Token", "session-private")
                .put("X-Password", "password-private").put("X-Secret", "secret-private").put("X-Mode", "safe"))))
        onEvent(store, "Network.webSocketFrameSent", frameEvent("Network.webSocketFrameSent", "cdp-1", 1, "\u0000".repeat(8192)))

        val first = store.list(0, 20, 0)
        assertEquals(3, first.getJSONArray("frames").length())
        assertTrue(first.isNull("nextCursor"))
        assertTrue(first.getBoolean("loss"))
        val payloadEvent = first.getJSONArray("frames").getJSONObject(2)
        assertTrue(payloadEvent.getBoolean("frameTruncated"))
        assertTrue(payloadEvent.getString("payload").length < 8192)
        assertTrue(first.toString().toByteArray().size <= first.getInt("maxPageBytes"))

        val safeHeaders = first.getJSONArray("frames").getJSONObject(1).getJSONObject("requestHeaders")
        assertFalse(safeHeaders.has("X-Csrf-Token"))
        assertFalse(safeHeaders.has("X-Session-Token"))
        assertFalse(safeHeaders.has("X-Password"))
        assertFalse(safeHeaders.has("X-Secret"))
        assertEquals("safe", safeHeaders.getString("X-Mode"))
    }

    @Test
    fun eventDropCountAndLossRemainVisibleAfterCaptureStops() {
        val store = WebSocketCaptureStore()
        store.begin("cap-frozen-drops", includeBodies = false)
        store.markEventDrops(7)
        store.stop()
        val stopped = store.list(0, 20, eventDrops = 0)
        assertEquals(7, stopped.getLong("eventDrops"))
        assertTrue(stopped.getBoolean("loss"))
        assertFalse(stopped.getBoolean("recording"))
    }

    @Test
    fun oversizedHandshakeMetadataCanBePagedAndErrorCredentialsAreRedacted() {
        val store = WebSocketCaptureStore()
        store.begin("cap-meta", includeBodies = true)
        onEvent(store, "Network.webSocketCreated", JSONObject().put("requestId", "cdp-1").put("url", "wss://example.test/ws"))
        val headers = JSONObject()
        repeat(16) { headers.put("H$it", "\u0000".repeat(256)) }
        onEvent(store, "Network.webSocketWillSendHandshakeRequest", JSONObject().put("requestId", "cdp-1")
            .put("timestamp", 1.0).put("wallTime", 2.0).put("request", JSONObject().put("headers", headers)))
        onEvent(store, "Network.webSocketFrameError", JSONObject().put("requestId", "cdp-1").put("timestamp", 3.0)
            .put("errorMessage", "Failed: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.signature wss://example.test/ws?key=private-key password=private-password"))
        val page = store.list(1, 1, 0)
        assertEquals(1, page.getJSONArray("frames").length())
        assertEquals(2, page.getInt("nextCursor"))
        assertTrue(page.getBoolean("loss"))
        assertTrue(page.getJSONArray("frames").getJSONObject(0).getBoolean("metadataIncomplete"))
        assertTrue(page.toString().toByteArray(StandardCharsets.UTF_8).size <= page.getInt("maxPageBytes"))
        val error = store.list(2, 1, 0).getJSONArray("frames").getJSONObject(0)
        assertTrue(error.getBoolean("detailRedacted"))
        assertFalse(error.toString().contains("signature"))
        assertFalse(error.toString().contains("private-key"))
        assertFalse(error.toString().contains("private-password"))
    }

    @Test
    fun emitsNativeIncompleteContractSamples() {
        val samples = JSONArray()
        for (includeBodies in listOf(false, true)) {
            val store = WebSocketCaptureStore()
            store.begin("cap-0123456789abcdef0123", includeBodies)
            onEvent(store, "Network.webSocketCreated", JSONObject().put("requestId", "cdp-sample")
                .put("url", "wss://user:private@example.test/socket"))
            onEvent(store, "Network.webSocketFrameSent", JSONObject().put("requestId", "cdp-sample"))
            onEvent(store, "Network.webSocketFrameReceived", JSONObject().put("requestId", "cdp-sample").put("timestamp", -1)
                .put("response", JSONObject().put("opcode", 1).put("payloadData", "你好")))
            onEvent(store, "Network.webSocketFrameReceived", JSONObject().put("requestId", "cdp-sample").put("timestamp", 2)
                .put("response", JSONObject().put("opcode", 2).put("mask", true).put("payloadData", "not-base64!")))
            onEvent(store, "Network.webSocketWillSendHandshakeRequest", JSONObject().put("requestId", "cdp-sample")
                .put("request", JSONObject().put("headers", JSONObject())))
            val data = store.list(0, 20, 0)
            assertEquals(5, data.getJSONArray("frames").length())
            assertTrue(data.getBoolean("loss"))
            samples.put(data)
        }
        val output = File("build/outputs/ws-native-contract-samples.json")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText(samples.toString(), Charsets.UTF_8)
    }

    private fun onEvent(store: WebSocketCaptureStore, method: String, params: JSONObject) = store.onEvent(method, params)
    private fun onEvent(store: WebSocketCaptureStore, params: JSONObject) = store.onEvent(params.getString("method"), params)

    private fun frameEvent(method: String, requestId: String, opcode: Int, payload: String) =
        JSONObject().put("method", method).put("requestId", requestId).put("timestamp", 5.0)
            .put("response", JSONObject().put("opcode", opcode).put("mask", false).put("payloadData", payload))
}
