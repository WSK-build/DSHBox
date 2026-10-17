package com.dshbox.browser.devtools

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.nio.file.SecureDirectoryStream
import org.junit.Assume.assumeTrue

class NetworkCaptureStoreTest {
    @Test
    fun optedInTextRequestBodyIsBoundedAndReplayableWithDigestPreview() {
        val store = newStore(Files.createTempDirectory("capture-request-body-test").toFile())
        store.begin(includeBodies = false, includeRequestBodies = true)
        store.onRequest(JSONObject().put("requestId", "write-json").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/api/write?mode=1").put("method", "POST")
                .put("hasPostData", true).put("headers", JSONObject()
                    .put("Content-Type", "application/json").put("Authorization", "Bearer hidden"))))

        assertEquals("pending", store.requestBodyCaptureState("write-json"))
        assertEquals(true, store.saveRequestBody("write-json", "{\"message\":\"hello\"}"))
        val entryId = store.list(0, 1).getJSONArray("entries").getJSONObject(0).getString("entryId")
        val source = store.replaySource(entryId) ?: error("Complete body should be eligible")
        assertEquals("{\"message\":\"hello\"}", source.getString("requestBody"))
        assertEquals("{\"message\":\"hello\"}", store.replayBodyForNativePrompt(entryId))
        assertEquals(19, source.getInt("requestBodyBytes"))
        val preview = store.replayPreview(entryId) ?: error("Risk preview missing")
        assertEquals("POST", preview.getString("method"))
        assertEquals("https://example.test", preview.getString("origin"))
        assertFalse(preview.has("requestBody"))
        assertTrue(preview.getBoolean("queryOmitted"))
        assertEquals(64, preview.getString("querySha256").length)
        assertFalse(preview.getBoolean("sensitiveHeadersCopied"))
    }

    @Test
    fun requestBodyCaptureDoesNotStoreByDefaultAndRejectsOversize() {
        val defaultStore = newStore(Files.createTempDirectory("capture-request-body-default-test").toFile())
        defaultStore.begin(includeBodies = false)
        defaultStore.onRequest(JSONObject().put("requestId", "default-post").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/default").put("method", "POST")
                .put("hasPostData", true).put("headers", JSONObject().put("Content-Type", "text/plain"))))
        assertEquals(false, defaultStore.capturesRequestBodies())
        assertEquals("not_requested", defaultStore.requestBodyCaptureState("default-post"))
        assertEquals(false, defaultStore.saveRequestBody("default-post", "secret"))

        val bounded = newStore(Files.createTempDirectory("capture-request-body-limit-test").toFile())
        bounded.begin(includeBodies = false, includeRequestBodies = true)
        bounded.onRequest(JSONObject().put("requestId", "large-post").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/large").put("method", "POST")
                .put("hasPostData", true).put("headers", JSONObject().put("Content-Type", "text/plain"))))
        assertEquals(false, bounded.saveRequestBody("large-post", "x".repeat(NetworkCaptureStore.MAX_REQUEST_BODY_BYTES + 1)))
        assertEquals("size_limit", bounded.requestBodyCaptureState("large-post"))
        assertEquals(null, bounded.replaySource(bounded.list(0, 1).getJSONArray("entries").getJSONObject(0).getString("entryId")))
    }

    @Test
    fun revocationClearsOptedInWritePayloadAndInvalidatesReplayEligibility() {
        val store = newStore(Files.createTempDirectory("capture-request-body-revoke-test").toFile())
        store.begin(includeBodies = false, includeRequestBodies = true)
        store.onRequest(JSONObject().put("requestId", "revoked-post").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/api").put("method", "POST")
                .put("hasPostData", true).put("headers", JSONObject().put("Content-Type", "text/plain"))))
        assertEquals(true, store.saveRequestBody("revoked-post", "sensitive-body"))
        val entryId = store.list(0, 1).getJSONArray("entries").getJSONObject(0).getString("entryId")
        assertEquals("sensitive-body", store.replayBodyForNativePrompt(entryId))

        store.clearSensitiveRequestBodies()

        assertEquals("cleared_by_revocation", store.requestBodyCaptureState("revoked-post"))
        assertEquals(null, store.replaySource(entryId))
        assertEquals(null, store.replayBodyForNativePrompt(entryId))
        assertEquals(0, store.requestBodyBytesUsed())
    }

    @Test
    fun replaySourceAllowsOnlyCompleteBodylessGetOrHead() {
        val root = Files.createTempDirectory("capture-replay-safety-test").toFile()
        val store = newStore(root)
        store.begin(includeBodies = false)
        store.onRequest(JSONObject().put("requestId", "safe-get").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/safe-get?q=1").put("method", "GET")))
        store.onRequest(JSONObject().put("requestId", "safe-head").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/safe-head").put("method", "HEAD")))
        store.onRequest(JSONObject().put("requestId", "write-post").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/write-post").put("method", "POST")))
        store.onRequest(JSONObject().put("requestId", "body-get").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/body-get").put("method", "GET")
                .put("hasPostData", true)))

        assertEquals("GET", store.replaySource(entryIdFor(store, "/safe-get"))?.getString("method"))
        assertEquals("HEAD", store.replaySource(entryIdFor(store, "/safe-head"))?.getString("method"))
        assertEquals(null, store.replaySource(entryIdFor(store, "/write-post")))
        assertEquals(null, store.replaySource(entryIdFor(store, "/body-get")))
    }

    @Test
    fun plaintextResponseBodyIsStoredAndReadable() {
        val root = Files.createTempDirectory("capture-body-test").toFile()
        val output = File(root, "browser-tools/out/network")
        val store = newStore(root)
        val captureId = store.begin(includeBodies = true)
        store.onRequest(JSONObject().put("requestId", "request-body").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/data").put("method", "GET")))
        val saved = store.saveResponseBody("request-body", JSONObject().put("body", "{\"fixture\":true}")
            .put("base64Encoded", false), captureId)
        assertEquals(null, store.artifactErrorCode())
        assertEquals(true, saved)
        val entry = store.list(0, 1).getJSONArray("entries").getJSONObject(0)
        assertEquals(true, entry.getBoolean("bodyAvailable"))
        val artifact = store.body(entry.getString("entryId")) ?: error("Stored body missing")
        assertEquals("{\"fixture\":true}", File(output, File(artifact.getString("artifactPath")).name).readText())
    }

    @Test
    fun validBase64ResponseBodyIsDecodedAndStored() {
        val root = Files.createTempDirectory("capture-body-base64-test").toFile()
        val output = File(root, "browser-tools/out/network")
        val store = newStore(root)
        val captureId = store.begin(includeBodies = true)
        store.onRequest(JSONObject().put("requestId", "request-binary-body").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/data").put("method", "GET")))

        val expected = byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte())
        assertEquals(true, store.saveResponseBody("request-binary-body", JSONObject()
            .put("body", java.util.Base64.getEncoder().encodeToString(expected))
            .put("base64Encoded", true), captureId))
        val entry = store.list(0, 1).getJSONArray("entries").getJSONObject(0)
        val artifact = store.body(entry.getString("entryId")) ?: error("Stored body missing")
        assertArrayEquals(expected, File(output, File(artifact.getString("artifactPath")).name).readBytes())
    }

    @Test
    fun invalidBase64BodyHasDecodeDiagnostic() {
        val root = Files.createTempDirectory("capture-body-decode-test").toFile()
        val store = newStore(root)
        val captureId = store.begin(includeBodies = true)
        store.onRequest(JSONObject().put("requestId", "request-invalid-body").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/data").put("method", "GET")))

        assertFalse(store.saveResponseBody("request-invalid-body", JSONObject()
            .put("body", "a").put("base64Encoded", true), captureId))
        assertEquals("BODY_DECODE_FAILED", store.artifactErrorCode())
    }

    @Test
    fun staleInvalidBodyDoesNotChangeCurrentCaptureDiagnostic() {
        val root = Files.createTempDirectory("capture-stale-body-test").toFile()
        val store = newStore(root)
        val staleCaptureId = store.begin(includeBodies = true)
        store.onRequest(JSONObject().put("requestId", "request-stale-body").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/data").put("method", "GET")))
        store.begin(includeBodies = true)

        assertFalse(store.saveResponseBody("request-stale-body", JSONObject()
            .put("body", "a").put("base64Encoded", true), staleCaptureId))
        assertEquals(null, store.artifactErrorCode())
    }

    @Test
    fun unsafeOutputPathHasStableDiagnostic() {
        val root = Files.createTempDirectory("capture-body-path-test").toFile()
        File(root, "browser-tools").writeText("not a directory")
        val store = newStore(root)
        val captureId = store.begin(includeBodies = true)
        store.onRequest(JSONObject().put("requestId", "request-path-error").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/data").put("method", "GET")))

        assertFalse(store.saveResponseBody("request-path-error", JSONObject()
            .put("body", "body").put("base64Encoded", false), captureId))
        assertEquals("ARTIFACT_PATH_UNSAFE", store.artifactErrorCode())
    }

    @Test
    fun legitimateFilesDirectoryAliasIsResolvedBeforePrivateTreeValidation() {
        val root = Files.createTempDirectory("capture-body-alias-test").toFile()
        val actualFiles = File(root, "actual/files").apply { mkdirs() }
        val alias = File(root, "files-alias").toPath()
        try {
            Files.createSymbolicLink(alias, actualFiles.toPath())
        } catch (error: Exception) {
            assumeNoException("Symbolic links are unavailable on this test host", error)
        }
        val output = File(alias.toFile(), "browser-tools/out/network")
        val store = newStore(alias.toFile())
        val captureId = store.begin(includeBodies = true)
        store.onRequest(JSONObject().put("requestId", "request-body-alias").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/data").put("method", "GET")))

        assertEquals(true, store.saveResponseBody("request-body-alias", JSONObject()
            .put("body", "aliased-private-storage").put("base64Encoded", false), captureId))
        assertEquals(null, store.artifactErrorCode())
        assertEquals("aliased-private-storage", File(actualFiles, "browser-tools/out/network")
            .listFiles()!!.single { it.extension == "body" }.readText())
    }

    @Test
    fun lateCompletionEventsDoNotMutateStoppedCapture() {
        val root = Files.createTempDirectory("capture-store-test").toFile()
        val store = newStore(root)
        store.begin(includeBodies = false)
        store.onRequest(JSONObject()
            .put("requestId", "request-1")
            .put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/data").put("method", "GET")))

        store.stop()
        store.onFinished("request-1")
        store.onFailed("request-1", "late failure")

        val entry = store.list(cursor = 0, limit = 1).getJSONArray("entries").getJSONObject(0)
        assertFalse(entry.getBoolean("finished"))
        assertEquals(JSONObject.NULL, entry.get("failed"))
        assertEquals(JSONObject.NULL, entry.get("durationMillis"))
    }

    @Test
    fun harExplicitlyDeclaresWebSocketFramesAreExcluded() {
        val root = Files.createTempDirectory("capture-har-test").toFile()
        val store = newStore(root)
        store.begin(includeBodies = false)

        val exported = store.exportHar(includeBodies = false) ?: error("HAR export failed")
        val artifactName = File(exported.getString("artifactPath")).name
        val harFile = File(File(root, "browser-tools/out/network"), artifactName)
        val har = JSONObject(String(Files.readAllBytes(harFile.toPath()), StandardCharsets.UTF_8))
        assertEquals(true, har.getJSONObject("_browser").getBoolean("webSocketFramesExcluded"))
        assertEquals(0, har.getJSONObject("log").getJSONArray("entries").length())
    }

    @Test
    fun oversizedBase64BodyStoresBoundedPrefixAndMarksTruncated() {
        val root = Files.createTempDirectory("capture-body-bounded-base64-test").toFile()
        val store = newStore(root)
        val captureId = store.begin(includeBodies = true)
        store.onRequest(JSONObject().put("requestId", "request-large-base64").put("type", "XHR")
            .put("request", JSONObject().put("url", "https://example.test/data").put("method", "GET")))
        val expectedPrefix = ByteArray(1024 * 1024 + 1) { (it % 251).toByte() }
        val encoded = java.util.Base64.getEncoder().encodeToString(expectedPrefix)
        assertEquals(true, store.saveResponseBody("request-large-base64", JSONObject()
            .put("body", encoded).put("base64Encoded", true), captureId))
        val entry = store.list(0, 1).getJSONArray("entries").getJSONObject(0)
        assertEquals(1024 * 1024, entry.getInt("bodyBytes"))
        assertEquals(true, entry.getBoolean("bodyTruncated"))
        assertEquals(null, store.artifactErrorCode())
    }

    @Test
    fun artifactQuotaDeleteFailureRejectsNewArtifactWithoutExceedingCount() {
        val root = Files.createTempDirectory("capture-body-quota-delete-test").toFile()
        val stream = Files.newDirectoryStream(root.canonicalFile.toPath())
        assumeTrue("SecureDirectoryStream unavailable on this filesystem", stream is SecureDirectoryStream<*>)
        stream.close()
        val store = NetworkCaptureStore(root, { 1000L }) { _, _ -> error("simulated delete failure") }
        val captureId = store.begin(includeBodies = true)
        repeat(33) { index ->
            val requestId = "request-quota-$index"
            store.onRequest(JSONObject().put("requestId", requestId).put("type", "XHR")
                .put("request", JSONObject().put("url", "https://example.test/$index").put("method", "GET")))
            if (index < 32) assertEquals(true, store.saveResponseBody(requestId,
                JSONObject().put("body", "x").put("base64Encoded", false), captureId))
            else assertEquals(false, store.saveResponseBody(requestId,
                JSONObject().put("body", "y").put("base64Encoded", false), captureId))
        }
        assertEquals("ARTIFACT_DELETE_FAILED", store.artifactErrorCode())
        assertEquals(32, File(root, "browser-tools/out/network").listFiles()!!.size)
    }

    private fun newStore(filesRoot: File): NetworkCaptureStore {
        val stream = Files.newDirectoryStream(filesRoot.canonicalFile.toPath())
        val supported = stream is SecureDirectoryStream<*>
        stream.close()
        assumeTrue("SecureDirectoryStream unavailable on this filesystem", supported)
        return NetworkCaptureStore(filesRoot, elapsedClock = { 1000L })
    }

    private fun entryIdFor(store: NetworkCaptureStore, requestId: String): String =
        (0 until store.entryCount()).mapNotNull { index ->
            store.list(index, 1).getJSONArray("entries").optJSONObject(0)
        }.first { it.getJSONObject("request").optString("url").contains(requestId) }
            .getString("entryId")
}
