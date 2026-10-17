package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserRenderingProtocolTest {
    @Test fun getRejectsWriteFieldsAndCallerTarget() {
        val args = JSONObject().put("action", "rendering").put("operation", "get")
        assertTrue(parse(args))
        assertFalse(parse(JSONObject(args.toString()).put("targetId", "foreign")))
        assertFalse(parse(JSONObject(args.toString()).put("expectedRevision", 0)))
    }

    @Test fun setUsesSafeLongRevisionAndStrictKeyValueTypes() {
        val base = JSONObject().put("action", "rendering").put("operation", "set")
            .put("key", "textScalePercent").put("value", 120)
        assertTrue(parse(JSONObject(base.toString()).put("expectedRevision", BrowserRenderingSettings.MAX_JS_SAFE_INTEGER)))
        for (bad in listOf(-1L, 9_007_199_254_740_992L, "1", 1.5, JSONObject.NULL)) {
            assertFalse(parse(JSONObject(base.toString()).put("expectedRevision", bad)))
        }
        for (bad in listOf(49, 201, "120", 120.5, JSONObject.NULL)) {
            assertFalse(parse(JSONObject(base.toString()).put("expectedRevision", 0).put("value", bad)))
        }
        assertFalse(parse(JSONObject(base.toString()).put("expectedRevision", 0).put("key", "revision")))
        assertFalse(parse(JSONObject(base.toString()).put("expectedRevision", 0).put("targetId", "foreign")))
    }

    @Test fun renderingReadsAreNotTaskMutationsButWritesAre() {
        fun request(operation: String) = BrowserCommandRequest.parse(envelope(JSONObject()
            .put("action", "rendering").put("operation", operation).apply {
                if (operation == "set") put("key", "overviewMode").put("value", true).put("expectedRevision", 0)
            }), nowElapsedMillis = 1234).getOrThrow()
        assertFalse(BrowserTaskCoordinator.isPotentiallyMutating(request("get")))
        assertTrue(BrowserTaskCoordinator.isPotentiallyMutating(request("set")))
    }

    private fun parse(args: JSONObject) = BrowserCommandRequest.parse(envelope(args), nowElapsedMillis = 1234).isSuccess
    private fun envelope(args: JSONObject) = JSONObject().put("version", 1).put("requestId", "render_protocol")
        .put("method", "debug").put("deadlineAtMillis", System.currentTimeMillis() + 10_000)
        .put("sessionGeneration", 7).put("controlGeneration", 2).put("owner", "ai").put("args", args).toString()
}
