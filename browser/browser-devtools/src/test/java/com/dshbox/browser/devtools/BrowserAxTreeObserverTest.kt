package com.dshbox.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAxTreeObserverTest {
    @Test
    fun frameWithOnlyDroppedNodesIsNotReportedObserved() {
        val observation = observe(FakeTransport(
            frameTree = listOf(frame("main", context.expectedUrl), frame("child", "https://example.test/child")),
            rootNodes = JSONArray().put(axNode("root", "main", "button", "Root"))
                .put(axNode("child", "child", "button", "Child")),
            frameNodes = mapOf("child" to JSONArray().put(axNode("child", "child", "button", "Child"))),
        ), BrowserAxTreeRequest(maxNodes = 1))
        assertEquals("OK", observation.code)
        val child = observation.data.getJSONArray("frames").getJSONObject(1)
        assertEquals("not_covered", child.getString("coverage"))
        assertEquals(0, child.getInt("nodeCount"))
        assertEquals("partial", observation.data.getString("coverage"))
    }

    @Test
    fun identicalPublicNodeWithConflictingPrivateBackendFailsClosed() {
        val observation = observe(FakeTransport(
            frameTree = listOf(frame("main", context.expectedUrl), frame("child", "https://example.test/child")),
            rootNodes = JSONArray().put(axNode("root", "main", "button", "Same").put("backendDOMNodeId", 71)),
            frameNodes = mapOf("child" to JSONArray().put(axNode("root", "main", "button", "Same").put("backendDOMNodeId", 72))),
        ))
        assertEquals("AX_NODE_CONFLICT", observation.code)
        assertTrue(observation.actionCandidates.isEmpty())
    }

    @Test
    fun actionCandidatesRemainPrivateAndRequireQueryOrKnownFrameAndIntegerBackend() {
        val transport = FakeTransport(rootNodes = JSONArray()
            .put(axNode("valid", "main", "button", "Continue").put("backendDOMNodeId", 71))
            .put(axNode("unknown", "", "button", "Unknown").put("backendDOMNodeId", 72))
            .put(axNode("string", "main", "button", "String").put("backendDOMNodeId", "73"))
            .put(axNode("ignored", "main", "button", "Ignored").put("backendDOMNodeId", 74).put("ignored", true)))
        val observation = observe(transport)
        assertEquals("OK", observation.code)
        assertEquals(mapOf("ax:main:valid" to BrowserAxActionCandidate("main", 71),
            "ax:main:unknown" to BrowserAxActionCandidate("main", 72)), observation.actionCandidates)
        assertFalse(observation.data.toString().contains("backendDOMNodeId"))
    }

    private val context = BrowserAxTargetContext(
        targetId = "target-main",
        connectionEpoch = 7,
        expectedUrl = "https://example.test/start",
        documentIdentity = "document-7",
    )

    @Test
    fun outputUsesReadOnlyAxRefsAndNeverExposesRawValueOrDomRefs() {
        val password = axNode("n1", "main", "textbox", "Password")
            .put("value", "hunter2")
            .put("childIds", JSONArray().put("n3"))
        val transport = FakeTransport(
            rootNodes = JSONArray()
                .put(password)
                .put(axNode("n2", "", "text", "Unattributed"))
                .put(axNode("n3", "main", "text", "Child")),
        )
        val observation = observe(transport)

        assertEquals("OK", observation.code)
        val node = observation.data.getJSONArray("nodes").getJSONObject(0)
        assertEquals("ax:main:n1", node.getString("axRef"))
        assertEquals(
            "ax:main:n3",
            node.getJSONArray("childAxRefs").getString(0),
        )
        assertFalse(node.getBoolean("childRefsTruncated"))
        assertEquals("textbox", node.getString("role"))
        assertEquals("known", node.getString("frameAttribution"))
        assertTrue(node.getJSONArray("states").toString().contains("focused"))
        assertFalse(node.has("value"))
        assertFalse(node.has("nodeId"))
        assertFalse(node.has("ref"))
        assertFalse(node.has("scopeRef"))
        assertFalse(node.has("backendDOMNodeId"))
        val unattributed = observation.data.getJSONArray("nodes").getJSONObject(1)
        assertEquals("ax:main:n2", unattributed.getString("axRef"))
        assertEquals("known", unattributed.getString("frameAttribution"))
        assertTrue(transport.methods.none { it.startsWith("Target.") })
        assertFalse(observation.data.getBoolean("fullPageAxCovered"))
        assertEquals("inventoried_frames_only", observation.data.getString("coverageScope"))
        assertEquals(5, observation.commandCount)
    }

    @Test
    fun productionSampleRetainsExactTwentyFieldSuccessContract() {
        val transport = FakeTransport(
            rootNodes = JSONArray().put(axNode("root", "main", "root", "Page")),
        )
        val observation = observe(transport)

        assertEquals("OK", observation.code)
        assertJsonEquals(JSONObject(PRODUCTION_SAMPLE_JSON), observation.data)
        assertTrue(observation.data.toString().toByteArray(Charsets.UTF_8).size <= 12 * 1024)
        assertEquals(9, observation.data.getJSONArray("nodes").getJSONObject(0).keySet().size)
    }

    @Test
    fun sensitiveAxNamesRedactUrlFragmentsBearerJwtAndCredentialForms() {
        val secrets = listOf(
            "query-secret",
            "fragment-secret",
            "header-secret",
            "jwt-secret-part",
            "cookie-secret",
            "body-secret",
        )
        val name = "Open https://example.test/cb?access_token=query-secret" +
            "#access_token=fragment-secret; Authorization: Bearer header-secret; " +
            "eyJhbGciOiJIUzI1NiJ9.jwt-secret-part.signature; " +
            "Cookie: cookie-secret; access_token: body-secret"
        val observation = observe(
            FakeTransport(rootNodes = JSONArray().put(axNode("n1", "main", "link", name))),
        )

        assertEquals("OK", observation.code)
        val sanitized = observation.data.getJSONArray("nodes").getJSONObject(0).getString("name")
        secrets.forEach { assertFalse(sanitized.contains(it)) }
        assertTrue(sanitized.contains("https://example.test/cb?[redacted]"))
        assertFalse(sanitized.contains("#access_token"))
        assertTrue(sanitized.contains("[redacted]"))
    }

    @Test
    fun commandBudgetCountsIdentityInventoryEnableRetriesAndClosingIdentity() {
        val frames = mutableListOf(frame("main", context.expectedUrl))
        repeat(10) { frames += frame("child-$it", "https://other.test/$it") }
        val transport = FakeTransport(
            frameTree = frames,
            rootNodes = JSONArray().put(axNode("main-root", "main", "root", "Page")),
        )
        val observation = observe(transport, BrowserAxTreeRequest(maxNodes = 64))

        assertEquals("OK", observation.code)
        assertEquals(12, observation.commandCount)
        assertEquals(12, observation.data.getJSONObject("budgets").getInt("maxCommands"))
        assertEquals(12, observation.data.getJSONObject("budgets").getInt("usedCommands"))

        val frameArray = observation.data.getJSONArray("frames")
        var notAttempted = 0
        for (index in 0 until frameArray.length()) {
            if (frameArray.getJSONObject(index).getString("coverage") == "not_attempted") {
                notAttempted++
            }
        }
        assertEquals(3, notAttempted)
        assertEquals("partial", observation.data.getString("coverage"))
    }

    @Test
    fun nodeLimitAppliesAcrossRootAndFrameResults() {
        val nodes = JSONArray()
        repeat(30) { nodes.put(axNode("n$it", "main", "text", "Item $it")) }
        val observation = observe(FakeTransport(rootNodes = nodes), BrowserAxTreeRequest(maxNodes = 8))

        assertEquals("OK", observation.code)
        assertEquals(8, observation.data.getJSONArray("nodes").length())
        assertEquals(22, observation.data.getInt("droppedNodes"))
        assertEquals(256, observation.data.getJSONObject("budgets").getInt("maxTotalNodes"))
        assertTrue(observation.data.getBoolean("truncated"))
        assertTrue(observation.commandCount < 12)
    }

    @Test
    fun outputByteBudgetIsIndependentOfNodeAndCommandBudgets() {
        val nodes = JSONArray()
        repeat(100) { index ->
            nodes.put(axNode("n$index", "main", "text", "N".repeat(500) + index))
        }
        val observation = observe(
            FakeTransport(rootNodes = nodes),
            BrowserAxTreeRequest(maxNodes = 256),
        )

        val bytes = observation.data.toString().toByteArray(Charsets.UTF_8).size
        assertTrue(bytes <= 12 * 1024)
        assertEquals(12 * 1024, observation.data.getJSONObject("budgets").getInt("maxOutputBytes"))
        assertTrue(observation.data.getBoolean("truncated"))
        assertTrue(observation.data.getInt("droppedNodes") > 0)
        assertEquals(5, observation.commandCount)
    }

    @Test
    fun crossOriginFrameIsNotClaimedAsCoveredWithoutAttribution() {
        val transport = FakeTransport(
            frameTree = listOf(
                frame("main", context.expectedUrl),
                frame("ooipif", "https://other.test/frame"),
            ),
            rootNodes = JSONArray().put(axNode("main-root", "main", "root", "Page")),
        )
        val observation = observe(transport)

        assertEquals("OK", observation.code)
        assertEquals("partial", observation.data.getString("coverage"))
        val frames = observation.data.getJSONArray("frames")
        assertEquals("observed", frames.getJSONObject(0).getString("coverage"))
        assertEquals("not_covered", frames.getJSONObject(1).getString("coverage"))
        assertEquals("cross", frames.getJSONObject(1).getString("origin"))
        assertEquals(6, observation.commandCount)
    }

    @Test
    fun inventoryTruncationIsReportedAndForcesPartialCoverage() {
        val frames = mutableListOf(frame("main", context.expectedUrl))
        repeat(33) { index ->
            frames += frame("child-$index", "https://example.test/frame-$index")
        }
        val transport = FakeTransport(
            frameTree = frames,
            rootNodes = JSONArray().put(axNode("main-root", "main", "root", "Page")),
        )
        val observation = observe(transport)

        assertEquals("OK", observation.code)
        assertTrue(observation.data.getBoolean("inventoryTruncated"))
        assertEquals(
            "frame_limit_or_depth",
            observation.data.getString("inventoryTruncatedReason"),
        )
        assertTrue(observation.data.getBoolean("truncated"))
        assertEquals("partial", observation.data.getString("coverage"))
        assertTrue(observation.data.getJSONArray("frames").length() <= 32)
        assertEquals(12, observation.commandCount)
    }

    @Test
    fun inventoryDepthLimitIsAlsoReportedAsTruncated() {
        var tree = frame("leaf", "https://example.test/leaf")
        repeat(18) { index ->
            val id = if (index == 17) "root-frame" else "level-$index"
            tree = JSONObject()
                .put("frame", JSONObject().put("id", id).put("url", context.expectedUrl))
                .put("childFrames", JSONArray().put(tree))
        }
        val transport = FakeTransport(
            rawFrameTree = tree,
            rootNodes = JSONArray().put(axNode("main-root", "root-frame", "root", "Page")),
        )
        val observation = observe(transport)

        assertEquals("OK", observation.code)
        assertTrue(observation.data.getBoolean("inventoryTruncated"))
        assertEquals(
            "frame_limit_or_depth",
            observation.data.getString("inventoryTruncatedReason"),
        )
        assertEquals("partial", observation.data.getString("coverage"))
    }

    @Test
    fun limitedDepthAndUnreturnedChildRefsForcePartialCoverage() {
        val children = JSONArray()
        repeat(20) { children.put("child-$it") }
        val root = axNode("root", "main", "root", "Page").put("childIds", children)
        val observation = observe(
            FakeTransport(rootNodes = JSONArray().put(root)),
            BrowserAxTreeRequest(maxDepth = 4),
        )

        assertEquals("OK", observation.code)
        assertTrue(observation.data.getBoolean("depthLimited"))
        assertTrue(observation.data.getBoolean("childReferencesTruncated"))
        assertTrue(
            observation.data.getJSONArray("nodes").getJSONObject(0)
                .getBoolean("childRefsTruncated"),
        )
        assertEquals(16, observation.data.getJSONArray("nodes").getJSONObject(0)
            .getJSONArray("childAxRefs").length())
        assertEquals("partial", observation.data.getString("coverage"))
        assertFalse(observation.data.getBoolean("fullPageAxCovered"))
    }

    @Test
    fun invalidChildIdIsDroppedButForcesChildReferenceTruncation() {
        val root = axNode("root", "main", "root", "Page")
            .put("childIds", JSONArray().put("child-ok").put("invalid/child"))
        val child = axNode("child-ok", "main", "text", "Child")
        val observation = observe(
            FakeTransport(rootNodes = JSONArray().put(root).put(child)),
        )

        assertEquals("OK", observation.code)
        val returnedRoot = observation.data.getJSONArray("nodes").getJSONObject(0)
        assertEquals(1, returnedRoot.getJSONArray("childAxRefs").length())
        assertEquals("ax:main:child-ok", returnedRoot.getJSONArray("childAxRefs").getString(0))
        assertTrue(returnedRoot.getBoolean("childRefsTruncated"))
        assertTrue(observation.data.getBoolean("childReferencesTruncated"))
        assertEquals("partial", observation.data.getString("coverage"))
        assertTrue(observation.data.getBoolean("truncated"))
    }

    @Test
    fun repeatedFrameQueriesDeduplicateScopedAxRefsAndReportCount() {
        val duplicate = axNode("n1", "main", "textbox", "Password")
        val childNode = axNode("child-1", "child", "link", "Child")
        val transport = FakeTransport(
            frameTree = listOf(
                frame("main", context.expectedUrl),
                frame("child", "https://example.test/child"),
            ),
            rootNodes = JSONArray().put(duplicate),
            frameNodes = mapOf("child" to JSONArray().put(duplicate).put(childNode)),
        )
        val observation = observe(transport)

        assertEquals("OK", observation.code)
        assertEquals(2, observation.data.getJSONArray("nodes").length())
        assertEquals(2, observation.data.getInt("uniqueAxRefs"))
        assertEquals(1, observation.data.getInt("duplicateNodes"))
        val nodes = observation.data.getJSONArray("nodes")
        val refs = (0 until nodes.length()).map { nodes.getJSONObject(it).getString("axRef") }
        assertEquals(refs.size, refs.toSet().size)
    }

    @Test
    fun defaultHttpsPortIsNormalizedForFrameOrigin() {
        val childNode = axNode("child-1", "child", "link", "Child")
        val transport = FakeTransport(
            frameTree = listOf(
                frame("main", context.expectedUrl),
                frame("child", "https://example.test:443/child"),
            ),
            rootNodes = JSONArray().put(axNode("main-root", "main", "root", "Page")),
            frameNodes = mapOf("child" to JSONArray().put(childNode)),
        )
        val observation = observe(transport)

        assertEquals("OK", observation.code)
        assertEquals(
            "same",
            observation.data.getJSONArray("frames").getJSONObject(1).getString("origin"),
        )
        assertEquals("complete", observation.data.getString("coverage"))
    }

    @Test
    fun authorizationDeadlineAndIdentityChangesFailClosed() {
        val denied = BrowserAxTreeObserver(
            FakeTransport(),
            { "document-7" },
            { 100L },
        ).observe(context, BrowserAxTreeRequest(), 200L, { false })
        assertEquals("NOT_AUTHORIZED", denied.code)
        assertEquals(0, denied.commandCount)

        val expired = BrowserAxTreeObserver(
            FakeTransport(),
            { "document-7" },
            { 200L },
        ).observe(context, BrowserAxTreeRequest(), 200L, { false })
        assertEquals("DEADLINE_EXPIRED", expired.code)
        assertEquals(0, expired.commandCount)

        val changed = FakeTransport(identityUrls = listOf(
            context.expectedUrl,
            "https://example.test/after-navigation",
        ), rootNodes = JSONArray().put(axNode("main-root", "main", "root", "Page")))
        val observation = observe(changed)
        assertEquals("IDENTITY_CHANGED", observation.code)
        assertFalse(observation.data.has("nodes"))
        assertEquals(5, observation.commandCount)

        val staleTransport = FakeTransport().apply { connectionEpoch = 8L }
        val stale = BrowserAxTreeObserver(
            staleTransport,
            { "document-7" },
            { 100L },
        ).observe(context, BrowserAxTreeRequest(), 200L, { false })
        assertEquals("IDENTITY_CHANGED", stale.code)
        assertEquals(0, stale.commandCount)
    }

    @Test
    fun deadlineElapsedDuringTransportIsNotMisclassifiedAsPermissionDenial() {
        for (throwDuringCall in listOf(false, true)) {
            var now = 100L
            val delegate = FakeTransport()
            val transport = object : BrowserAxTreeTransport by delegate {
                override fun call(method: String, params: JSONObject, deadlineElapsedMillis: Long,
                    stillAuthorized: () -> Boolean): JSONObject {
                    val response = delegate.call(method, params, deadlineElapsedMillis, stillAuthorized)
                    now = 200L
                    if (throwDuringCall) throw IllegalStateException("transport interrupted")
                    return response
                }
            }
            val observation = BrowserAxTreeObserver(transport, { "document-7" }, { now })
                .observe(context, BrowserAxTreeRequest(), 200L, { true })
            assertEquals("DEADLINE_EXPIRED", observation.code)
            assertEquals(1, observation.commandCount)
            assertFalse(observation.data.has("nodes"))
        }
    }

    @Test
    fun identityMutationBeforeTransportSendUsesCombinedAuthorizationAndFailsClosed() {
        val transport = PreSendMutationTransport()
        val observation = BrowserAxTreeObserver(
            transport,
            { "document-7" },
            { 100L },
        ).observe(context, BrowserAxTreeRequest(), 200L, { true })

        assertEquals("IDENTITY_CHANGED", observation.code)
        assertEquals(2, observation.commandCount)
    }

    @Test
    fun documentMutationBeforeTransportSendAlsoRejectsTheObservation() {
        val transport = PreSendMutationTransport()
        var documentIdentity = "document-7"
        transport.onBeforeInventory = { documentIdentity = "document-8" }
        val observation = BrowserAxTreeObserver(
            transport,
            { documentIdentity },
            { 100L },
        ).observe(context, BrowserAxTreeRequest(), 200L, { true })

        assertEquals("IDENTITY_CHANGED", observation.code)
        assertEquals(2, observation.commandCount)
    }

    @Test
    fun conflictingScopedNodeAcrossFrameQueriesFailsClosed() {
        val rootDuplicate = axNode("n1", "main", "textbox", "Original")
        val frameDuplicate = axNode("n1", "main", "textbox", "Changed")
        val observation = observe(
            FakeTransport(
                frameTree = listOf(
                    frame("main", context.expectedUrl),
                    frame("child", "https://example.test/child"),
                ),
                rootNodes = JSONArray().put(rootDuplicate),
                frameNodes = mapOf("child" to JSONArray().put(frameDuplicate)),
            ),
        )

        assertEquals("AX_NODE_CONFLICT", observation.code)
        assertEquals("AX_NODE_CONFLICT", observation.data.getString("reason"))
        assertFalse(observation.data.has("nodes"))
        assertEquals(5, observation.commandCount)
    }

    @Test
    fun ordinaryFrameQueryFailureIsNotReportedAsUnsupported() {
        val observation = observe(
            FakeTransport(
                frameTree = listOf(
                    frame("main", context.expectedUrl),
                    frame("child", "https://example.test/child"),
                ),
                rootNodes = JSONArray().put(axNode("main-root", "main", "root", "Page")),
                failFrameQueries = true,
            ),
        )

        assertEquals("OK", observation.code)
        val childFrame = observation.data.getJSONArray("frames").getJSONObject(1)
        assertEquals("not_covered", childFrame.getString("coverage"))
        assertEquals("cdp_failed", childFrame.getString("reason"))
        assertEquals("partial", observation.data.getString("coverage"))
        assertEquals(6, observation.commandCount)
    }

    private fun observe(
        transport: FakeTransport,
        request: BrowserAxTreeRequest = BrowserAxTreeRequest(
            maxDepth = BrowserAxTreeRequest.MAX_DEPTH,
        ),
    ): BrowserAxTreeObservation = BrowserAxTreeObserver(
        transport = transport,
        documentIdentity = { "document-7" },
        nowElapsedMillis = { 100L },
    ).observe(context, request, 200L, { true })

    private fun axNode(id: String, frameId: String, role: String, name: String): JSONObject = JSONObject()
        .put("nodeId", id)
        .put("frameId", frameId)
        .put("role", JSONObject().put("type", "role").put("value", role))
        .put("name", JSONObject().put("type", "truncatedString").put("value", name))
        .put(
            "properties",
            JSONArray().put(JSONObject().put("name", "focused").put("value", true)),
        )

    private fun frame(id: String, url: String): JSONObject = JSONObject()
        .put("frame", JSONObject().put("id", id).put("url", url))
        .put("childFrames", JSONArray())

    private class FakeTransport(
        private val frameTree: List<JSONObject> = listOf(
            frameStatic("main", "https://example.test/start"),
        ),
        private val rawFrameTree: JSONObject? = null,
        private val rootNodes: JSONArray = JSONArray(),
        private val frameNodes: Map<String, JSONArray> = emptyMap(),
        private val identityUrls: List<String> = listOf(
            "https://example.test/start",
            "https://example.test/start",
        ),
        private val failFrameQueries: Boolean = false,
    ) : BrowserAxTreeTransport {
        override val targetId = "target-main"
        override var connectionEpoch = 7L
        val methods = mutableListOf<String>()
        private var identityIndex = 0

        override fun call(
            method: String,
            params: JSONObject,
            deadlineElapsedMillis: Long,
            stillAuthorized: () -> Boolean,
        ): JSONObject {
            methods += method
            return when (method) {
                "Runtime.evaluate" -> {
                    val url = identityUrls.getOrElse(identityIndex++) { identityUrls.last() }
                    JSONObject().put("result", JSONObject().put(
                        "value",
                        JSONObject().put("href", url).put("doc", "time-origin-7").toString(),
                    ))
                }
                "Page.getFrameTree" -> {
                    if (rawFrameTree != null) {
                        return JSONObject().put("frameTree", rawFrameTree)
                    }
                    val children = JSONArray()
                    frameTree.drop(1).forEach(children::put)
                    JSONObject().put("frameTree", JSONObject()
                        .put("frame", frameTree.first().getJSONObject("frame"))
                        .put("childFrames", children))
                }
                "Accessibility.enable" -> JSONObject()
                "Accessibility.getFullAXTree" -> JSONObject().put(
                    "nodes",
                    when {
                        params.has("frameId") && failFrameQueries -> error("frame query failed")
                        params.has("frameId") ->
                            frameNodes[params.optString("frameId")] ?: JSONArray()
                        else -> rootNodes
                    },
                )
                else -> error("unexpected method: $method")
            }
        }

        companion object {
            private fun frameStatic(id: String, url: String): JSONObject = JSONObject()
                .put("frame", JSONObject().put("id", id).put("url", url))
                .put("childFrames", JSONArray())
        }
    }

    private class PreSendMutationTransport : BrowserAxTreeTransport {
        override val targetId = "target-main"
        override var connectionEpoch = 7L
        var onBeforeInventory: (() -> Unit)? = null

        override fun call(
            method: String,
            params: JSONObject,
            deadlineElapsedMillis: Long,
            stillAuthorized: () -> Boolean,
        ): JSONObject {
            if (method == "Page.getFrameTree") {
                onBeforeInventory?.invoke()
                connectionEpoch = 8L
                check(stillAuthorized()) { "NOT_AUTHORIZED" }
            }
            return when (method) {
                "Runtime.evaluate" -> JSONObject().put("result", JSONObject().put(
                    "value",
                    JSONObject()
                        .put("href", "https://example.test/start")
                        .put("doc", "time-origin-7")
                        .toString(),
                ))
                "Page.getFrameTree" -> JSONObject()
                else -> JSONObject()
            }
        }
    }

    private fun assertJsonEquals(expected: JSONObject, actual: JSONObject) {
        assertEquals(expected.keySet(), actual.keySet())
        expected.keySet().forEach { key ->
            assertJsonValue(expected.opt(key), actual.opt(key), key)
        }
    }

    private fun assertJsonValue(expected: Any?, actual: Any?, path: String) {
        when {
            expected === JSONObject.NULL || expected == null ->
                assertTrue("$path must be null", actual === JSONObject.NULL || actual == null)
            expected is JSONObject -> {
                assertTrue("$path must be an object", actual is JSONObject)
                assertJsonEquals(expected, actual as JSONObject)
            }
            expected is JSONArray -> {
                assertTrue("$path must be an array", actual is JSONArray)
                val actualArray = actual as JSONArray
                assertEquals("$path length", expected.length(), actualArray.length())
                for (index in 0 until expected.length()) {
                    assertJsonValue(
                        expected.opt(index),
                        actualArray.opt(index),
                        "$path[$index]",
                    )
                }
            }
            else -> assertEquals("$path value", expected, actual)
        }
    }

    private companion object {
        private const val PRODUCTION_SAMPLE_JSON = """
            {
              "coverage": "complete",
              "coverageScope": "inventoried_frames_only",
              "fullPageAxCovered": false,
              "frames": [
                {
                  "frameId": "main",
                  "relation": "main",
                  "origin": "same",
                  "coverage": "observed",
                  "reason": "ok",
                  "nodeCount": 1
                }
              ],
              "nodes": [
                {
                  "axRef": "ax:main:root",
                  "parentAxRef": null,
                  "frameId": "main",
                  "frameAttribution": "known",
                  "role": "root",
                  "name": "Page",
                  "states": ["focused"],
                  "childAxRefs": [],
                  "childRefsTruncated": false
                }
              ],
              "uniqueAxRefs": 1,
              "requestedNodes": 64,
              "maxDepth": 8,
              "truncated": false,
              "inventoryTruncated": false,
              "inventoryTruncatedReason": null,
              "droppedNodes": 0,
              "duplicateNodes": 0,
              "unattributedNodes": 0,
              "childReferencesTruncated": false,
              "unresolvedChildReferences": 0,
              "depthLimited": false,
              "droppedFrames": 0,
              "commandCount": 5,
              "budgets": {
                "maxCommands": 12,
                "usedCommands": 5,
                "maxOutputBytes": 12288,
                "maxTotalNodes": 256
              }
            }
        """
    }
}
