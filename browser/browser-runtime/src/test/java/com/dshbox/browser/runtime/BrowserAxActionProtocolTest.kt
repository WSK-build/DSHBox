package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserAxActionProtocolTest {
    private val ref = "axa:01234567-89ab-cdef-0123-456789abcdef:255"

    @Test fun typedActionsAcceptOnlyOpaqueReferencesAndBoundedFields() {
        assertTrue(parse(JSONObject().put("action", "ax_click").put("actionRef", ref)).isSuccess)
        assertTrue(parse(JSONObject().put("action", "ax_type").put("actionRef", ref).put("text", "").put("replace", true)).isSuccess)
        assertTrue(parse(JSONObject().put("action", "ax_scroll").put("actionRef", ref).put("direction", "up").put("steps", 10)).isSuccess)
        for (bad in listOf("ax:frame:node", "b:old", ref.replace(":255", ":256"), ref.replace("01234567-", "invalid-"))) {
            assertFalse(parse(JSONObject().put("action", "ax_click").put("actionRef", bad)).isSuccess)
        }
        for (key in listOf("targetId", "frameId", "sessionId", "backendNodeId", "nodeId")) {
            assertFalse(parse(JSONObject().put("action", "ax_click").put("actionRef", ref).put(key, "caller")).isSuccess)
        }
        assertFalse(parse(JSONObject().put("action", "ax_type").put("actionRef", ref).put("text", "x").put("replace", "true")).isSuccess)
        assertFalse(parse(JSONObject().put("action", "ax_type").put("actionRef", ref).put("text", "x".repeat(4097))).isSuccess)
        assertFalse(parse(JSONObject().put("action", "ax_scroll").put("actionRef", ref).put("direction", "down").put("steps", "1")).isSuccess)
    }

    @Test fun observationDoesNotConsumeTaskWriteStepsButActionsDo() {
        val observation = parse(JSONObject().put("action", "ax_observe").put("maxNodes", 256)
            .put("maxDepth", 8).put("includeIgnored", false)).getOrThrow()
        assertFalse(BrowserTaskCoordinator.isPotentiallyMutating(observation))
        assertTrue(BrowserTaskCoordinator.isPotentiallyMutating(parse(JSONObject()
            .put("action", "ax_click").put("actionRef", ref)).getOrThrow()))
        assertFalse(parse(JSONObject().put("action", "ax_observe").put("maxNodes", "64")).isSuccess)
        assertFalse(parse(JSONObject().put("action", "ax_observe").put("actionRef", ref)).isSuccess)
    }

    private fun parse(args: JSONObject) = BrowserCommandRequest.parse(JSONObject().put("version", 1)
        .put("requestId", "ax_protocol_test").put("method", "debug")
        .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("sessionGeneration", 7)
        .put("controlGeneration", 2).put("owner", "ai").put("args", args).toString(), nowElapsedMillis = 1234)
}
