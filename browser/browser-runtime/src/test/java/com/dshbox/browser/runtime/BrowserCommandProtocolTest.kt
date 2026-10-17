package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCommandProtocolTest {
    @Test
    fun controlMethodAllowsOnlyHumanApprovalRequestAndLookupActionsWithoutBrowserCredentials() {
        fun parse(args: JSONObject, extras: JSONObject = JSONObject()) = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "control_protocol_01").put("method", "control")
                .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("args", args)
                .apply { extras.keys().forEach { key -> put(key, extras.get(key)) } }.toString(),
            nowElapsedMillis = 1_234L,
        )
        assertTrue(parse(JSONObject().put("action", "request").put("summary", "Need to continue")
            .put("taskId", "task_01")).isSuccess)
        assertTrue(parse(JSONObject().put("action", "status").put("approvalId", "a".repeat(32))).isSuccess)
        assertTrue(parse(JSONObject().put("action", "cancel").put("approvalId", "a".repeat(32))).isSuccess)
        assertFalse(parse(JSONObject().put("action", "request").put("summary", " ")).isSuccess)
        assertFalse(parse(JSONObject().put("action", "request").put("summary", "x".repeat(1001))).isSuccess)
        assertFalse(parse(JSONObject().put("action", "approve").put("approvalId", "a".repeat(32))).isSuccess)
        assertFalse(parse(JSONObject().put("action", "request").put("summary", "ok"), JSONObject().put("owner", "ai")).isSuccess)
        assertFalse(parse(JSONObject().put("action", "request").put("summary", "ok"), JSONObject().put("sessionGeneration", 4)).isSuccess)
    }

    @Test
    fun taskFeedbackUsesSessionBoundSelectionActionsAndFixedReceiptCodes() {
        fun parseSelection(args: JSONObject, session: Long? = 7L) = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "feedback_protocol_01").put("method", "selection")
                .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("args", args)
                .apply { session?.let { put("sessionGeneration", it) } }.toString(), nowElapsedMillis = 1_234L,
        )
        val id = "a".repeat(32)
        val requestId = "b".repeat(32)
        val digest = "c".repeat(64)
        assertTrue(parseSelection(JSONObject().put("action", "task_pending").put("afterSequence", 0)
            .put("destinationSessionKey", "agent-session-1")).isSuccess)
        for (action in listOf("task_claim", "task_admit")) {
            assertTrue(parseSelection(JSONObject().put("action", action).put("feedbackId", id)
                .put("destinationSessionKey", "agent-session-1").put("requestId", requestId).put("payloadDigest", digest)).isSuccess)
        }
        for (code in listOf("PROMPT_ACCEPTED", "UNKNOWN", "BLOCKED")) {
            assertTrue(parseSelection(JSONObject().put("action", "task_receipt").put("feedbackId", id)
                .put("destinationSessionKey", "agent-session-1").put("requestId", requestId).put("deliveryCode", code)).isSuccess)
        }
        assertFalse(parseSelection(JSONObject().put("action", "task_receipt").put("feedbackId", id)
            .put("destinationSessionKey", "agent-session-1").put("requestId", requestId).put("deliveryCode", "DELIVERED")).isSuccess)
        assertFalse(parseSelection(JSONObject().put("action", "task_pending").put("afterSequence", "0")
            .put("destinationSessionKey", "agent-session-1")).isSuccess)
        assertFalse(parseSelection(JSONObject().put("action", "task_claim").put("feedbackId", id)
            .put("destinationSessionKey", "agent-session-1").put("requestId", requestId).put("payloadDigest", digest), session = null).isSuccess)
    }

    @Test
    fun networkAdvancedActionsUseThePublicEnvelopeForGenerationsAndStrictReplayTickets() {
        fun parseNetwork(args: JSONObject) = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "network_contract_01").put("method", "network")
                .put("sessionGeneration", 4).put("controlGeneration", 9).put("owner", "ai")
                .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("args", args).toString(),
            nowElapsedMillis = 1_234L,
        )
        val entry = "net-${"a".repeat(24)}"
        val requestId = "b".repeat(32)
        val capture = "cap-${"c".repeat(20)}"
        assertTrue(parseNetwork(JSONObject().put("action", "start").put("includeRequestBodies", true)).isSuccess)
        assertTrue(parseNetwork(JSONObject().put("action", "export_frames")).isSuccess)
        assertTrue(parseNetwork(JSONObject().put("action", "export_frames").put("includeBodies", false)).isSuccess)
        assertTrue(parseNetwork(JSONObject().put("action", "export_frames").put("includeBodies", true)).isSuccess)
        assertFalse(parseNetwork(JSONObject().put("action", "export_frames").put("includeBodies", "true")).isSuccess)
        assertTrue(parseNetwork(JSONObject().put("action", "frames").put("cursor", 0).put("limit", 20)).isSuccess)
        assertTrue(parseNetwork(JSONObject().put("action", "replay_prepare").put("entryId", entry)).isSuccess)
        assertTrue(parseNetwork(JSONObject().put("action", "replay_status").put("requestId", requestId)).isSuccess)
        assertTrue(parseNetwork(JSONObject().put("action", "replay").put("entryId", entry)).isSuccess)
        assertTrue(parseNetwork(JSONObject().put("action", "replay").put("entryId", entry)
            .put("riskToken", requestId).put("captureId", capture)).isSuccess)

        assertFalse(parseNetwork(JSONObject().put("action", "start").put("includeRequestBodies", "true")).isSuccess)
        assertFalse(parseNetwork(JSONObject().put("action", "export_frames").put("path", "/tmp/a.ws.json")).isSuccess)
        assertFalse(parseNetwork(JSONObject().put("action", "frames").put("cursor", -1)).isSuccess)
        assertFalse(parseNetwork(JSONObject().put("action", "replay_prepare").put("entryId", entry).put("sessionGeneration", 4)).isSuccess)
        assertFalse(parseNetwork(JSONObject().put("action", "replay_status").put("requestId", requestId).put("controlGeneration", 9)).isSuccess)
        assertFalse(parseNetwork(JSONObject().put("action", "replay").put("entryId", entry).put("riskToken", requestId)).isSuccess)
        assertFalse(parseNetwork(JSONObject().put("action", "replay").put("entryId", entry).put("riskToken", requestId)
            .put("captureId", capture).put("sessionGeneration", 4)).isSuccess)
    }

    @Test
    fun clickAcceptsExactlyOneNodeOrScreenshotCoordinateContract() {
        fun parseClick(args: JSONObject) = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "visual_click_01").put("method", "click")
                .put("sessionGeneration", 4).put("controlGeneration", 9).put("owner", "ai")
                .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("args", args).toString(),
            nowElapsedMillis = 1_234,
        )
        fun assertAccepted(args: JSONObject) {
            val result = parseClick(args)
            assertTrue(result.exceptionOrNull()?.message ?: "valid click args rejected: $args", result.isSuccess)
        }
        assertAccepted(JSONObject().put("nodeId", "n1"))
        assertAccepted(JSONObject().put("screenshotId", "a".repeat(32)).put("x", 0.5).put("y", 10))
        assertFalse(parseClick(JSONObject().put("nodeId", "n1").put("screenshotId", "a".repeat(32)).put("x", 1).put("y", 1)).isSuccess)
        assertFalse(parseClick(JSONObject().put("screenshotId", "a".repeat(32)).put("x", 1)).isSuccess)
        assertFalse(parseClick(JSONObject().put("screenshotId", "A".repeat(32)).put("x", 1).put("y", 1)).isSuccess)
        assertFalse(parseClick(JSONObject().put("screenshotId", "a".repeat(32)).put("x", "NaN").put("y", 1)).isSuccess)
        assertFalse(parseClick(JSONObject().put("screenshotId", "a".repeat(32)).put("x", 1).put("y", 1).put("js", "alert(1)")).isSuccess)
    }

    @Test
    fun uploadProtocolRequiresTaskAndStrictActionArguments() {
        fun parse(args: JSONObject, taskId: String? = "task_01", owner: String = "ai") = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "upload_protocol").put("method", "upload")
                .put("sessionGeneration", 4).put("controlGeneration", 9).put("owner", owner)
                .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("args", args)
                .apply { if (taskId != null) put("taskId", taskId) }.toString(),
            nowElapsedMillis = 1_234L,
        )
        assertTrue(parse(JSONObject().put("action", "request").put("paths", org.json.JSONArray().put("reports/a.pdf")).put("multiple", false)).isSuccess)
        assertTrue(parse(JSONObject().put("action", "list").put("approvalId", "a".repeat(32))).isSuccess)
        assertTrue(parse(JSONObject().put("action", "activate").put("approvalId", "a".repeat(32))
            .put("nodeId", "b:4:9:3:1:${"b".repeat(32)}:0")).isSuccess)
        assertFalse(parse(JSONObject().put("action", "activate").put("approvalId", "a".repeat(32))
            .put("nodeId", "not-a-reference")).isSuccess)
        assertFalse(parse(JSONObject().put("action", "request").put("paths", org.json.JSONArray().put("../private")).put("multiple", false)).isSuccess)
        assertFalse(parse(JSONObject().put("action", "request").put("paths", org.json.JSONArray().put("a.txt").put("a.txt")).put("multiple", false)).isSuccess)
        assertFalse(parse(JSONObject().put("action", "submit").put("approvalId", "a".repeat(32)).put("fileIds", org.json.JSONArray().put("../bad"))).isSuccess)
        assertFalse(parse(JSONObject().put("action", "list").put("approvalId", "a".repeat(32)), taskId = null).isSuccess)
        assertFalse(parse(JSONObject().put("action", "list").put("approvalId", "a".repeat(32)), owner = "human").isSuccess)
    }

    @Test
    fun collectWindowRequiresAiControlAndStrictBoundedConfiguration() {
        val args = JSONObject().put("action", "window").put("containerSelector", "#list")
            .put("messageSelector", "article.item").put("keyAttribute", "data-id")
            .put("declaredRange", "latest 20").put("maxRecords", 20).put("maxContentChars", 1024)
        fun parseCollect(value: JSONObject, owner: String = "ai") = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "collect_protocol").put("method", "collect")
                .put("sessionGeneration", 4).put("controlGeneration", 9).put("owner", owner)
                .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("args", value).toString(),
            nowElapsedMillis = 1_234L,
        )
        assertTrue(parseCollect(args).isSuccess)
        assertTrue(parseCollect(JSONObject(args.toString()).put("excludeSelector", ".draft")).isSuccess)
        assertFalse(parseCollect(JSONObject(args.toString()).put("excludeSelector", "")).isSuccess)
        assertFalse(parseCollect(JSONObject(args.toString()).put("excludeSelector", "x".repeat(513))).isSuccess)
        assertFalse(parseCollect(JSONObject(args.toString()).put("maxRecords", 201)).isSuccess)
        assertFalse(parseCollect(JSONObject(args.toString()).put("extra", true)).isSuccess)
        assertFalse(parseCollect(args, "human").isSuccess)
    }

    @Test
    fun taskManagementAndOptionalActionTaskIdUseStrictWireShape() {
        fun parseTask(args: JSONObject, taskId: String? = null, withControl: Boolean = false) = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "task_protocol").put("method", "task")
                .apply { if (withControl) put("sessionGeneration", 4).put("controlGeneration", 9).put("owner", "ai") }
                .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("args", args).toString()
                .let { json -> if (taskId == null) json else JSONObject(json).put("taskId", taskId).toString() },
            nowElapsedMillis = 1_234L,
        )
        assertTrue(parseTask(JSONObject().put("action", "begin").put("durationMs", 1_000).put("operationLimit", 3), withControl = true).isSuccess)
        assertFalse(parseTask(JSONObject().put("action", "begin").put("durationMs", 1_000).put("operationLimit", 3)).isSuccess)
        assertTrue(parseTask(JSONObject().put("action", "status").put("taskId", "task_01")).isSuccess)
        assertTrue(parseTask(JSONObject().put("action", "wait").put("taskId", "task_01").put("reason", "page_load")).isSuccess)
        assertFalse(parseTask(JSONObject().put("action", "wait").put("taskId", "task_01").put("reason", "free text")).isSuccess)
        assertTrue(parseTask(JSONObject().put("action", "fail").put("taskId", "task_01").put("reason", "network failure")).isSuccess)
        assertTrue(parseTask(JSONObject().put("action", "fail").put("taskId", "task_01")).isSuccess)
        assertFalse(parseTask(JSONObject().put("action", "fail").put("taskId", "task_01").put("reason", "   ")).isSuccess)
        assertFalse(parseTask(JSONObject().put("action", "reconcile").put("taskId", "task_01").put("expectedRevision", 2), withControl = true).isSuccess)
        assertFalse(parseTask(JSONObject().put("action", "begin").put("operationLimit", 2001), withControl = true).isSuccess)
        assertFalse(parseTask(JSONObject().put("action", "list"), "task_01").isSuccess)

        val action = JSONObject().put("version", 1).put("requestId", "action_task")
            .put("method", "click").put("sessionGeneration", 4).put("controlGeneration", 9)
            .put("owner", "ai").put("taskId", "task_01")
            .put("deadlineAtMillis", System.currentTimeMillis() + 10_000).put("args", JSONObject().put("nodeId", "n1"))
        assertTrue(BrowserCommandRequest.parse(action.toString(), nowElapsedMillis = 1_234L).isSuccess)
        action.put("taskId", "../bad")
        assertFalse(BrowserCommandRequest.parse(action.toString(), nowElapsedMillis = 1_234L).isSuccess)
    }

    @Test
    fun waitRejectsMixedConditionsAndKeepsExactUrlPrivate() {
        fun wait(args: JSONObject, control: Boolean = false) = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "wait_protocol")
                .put("method", "wait").put("sessionGeneration", 4)
                .put("deadlineAtMillis", System.currentTimeMillis() + 35_000)
                .put("args", args).apply {
                    if (control) put("controlGeneration", 9).put("owner", "ai")
                }.toString(), nowElapsedMillis = 1_234L,
        )
        assertTrue(wait(JSONObject().put("condition", "url").put("url", "https://example.test/path?q=1#route")).isSuccess)
        assertFalse(wait(JSONObject().put("condition", "url").put("url", "https://user:secret@example.test/")).isSuccess)
        assertFalse(wait(JSONObject().put("condition", "url").put("url", "file:///private")).isSuccess)
        assertFalse(wait(JSONObject().put("condition", "text").put("text", "ready").put("selector", "#ready")).isSuccess)
        assertFalse(wait(JSONObject().put("condition", "load"), control = true).isSuccess)
        assertTrue(wait(JSONObject().put("condition", "selector").put("selector", "x".repeat(2048)).put("state", "attached")).isSuccess)
        assertFalse(wait(JSONObject().put("condition", "selector").put("selector", "x".repeat(2049))).isSuccess)
        assertFalse(wait(JSONObject().put("condition", "load").put("timeoutMs", 30_001)).isSuccess)
        assertFalse(wait(JSONObject().put("condition", "load").put("pollIntervalMs", 99)).isSuccess)
    }

    @Test
    fun openAndNavigateAcceptOptionalBooleanThumbnailOnly() {
        fun request(method: String, args: JSONObject) = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "thumbnail_protocol")
                .put("method", method).put("sessionGeneration", 4).put("controlGeneration", 9)
                .put("owner", "ai").put("deadlineAtMillis", System.currentTimeMillis() + 10_000)
                .put("args", args).toString(), nowElapsedMillis = 1_234L,
        )
        assertTrue(request("open", JSONObject().put("url", "https://example.test/")).isSuccess)
        assertTrue(request("open", JSONObject().put("url", "https://example.test/").put("thumbnail", true)).isSuccess)
        assertTrue(request("open", JSONObject().put("thumbnail", false)).isSuccess)
        assertFalse(request("open", JSONObject().put("thumbnail", true)).isSuccess)
        assertFalse(request("open", JSONObject().put("url", "https://example.test/").put("thumbnail", "true")).isSuccess)
        assertTrue(request("navigate", JSONObject().put("action", "back").put("thumbnail", true)).isSuccess)
        assertFalse(request("navigate", JSONObject().put("action", "back").put("thumbnail", 1)).isSuccess)
    }

    @Test
    fun locateRequiresCurrentAiControlAndNarrowStrategyArguments() {
        fun locate(args: JSONObject, owner: String = "ai", control: Long? = 9L) = BrowserCommandRequest.parse(
            JSONObject().put("version", 1).put("requestId", "locate_protocol").put("method", "locate")
                .put("sessionGeneration", 4).put("deadlineAtMillis", System.currentTimeMillis() + 10_000)
                .put("args", args).apply {
                    if (control != null) put("controlGeneration", control)
                    if (owner.isNotEmpty()) put("owner", owner)
                }.toString(), nowElapsedMillis = 1_234L,
        )
        assertTrue(locate(JSONObject().put("strategy", "role").put("value", "button").put("exact", true)
            .put("name", "Continue").put("scopeRef", "b:4:9:2:3:nonce:0")).isSuccess)
        assertTrue(locate(JSONObject().put("strategy", "label").put("value", "Email").put("exact", false)).isSuccess)
        assertTrue(locate(JSONObject().put("strategy", "css").put("value", "#submit").put("exact", true)).isSuccess)
        assertTrue(locate(JSONObject().put("strategy", "css").put("value", "#submit").put("exact", true)
            .put("partial", true)).isSuccess)
        assertTrue(locate(JSONObject().put("strategy", "css").put("value", "#submit").put("exact", true)
            .put("partial", false)).isSuccess)
        assertFalse(locate(JSONObject().put("strategy", "css").put("value", "#submit").put("exact", true)
            .put("partial", "true")).isSuccess)
        assertFalse(locate(JSONObject().put("strategy", "role").put("value", "button")).isSuccess)
        assertFalse(locate(JSONObject().put("strategy", "css").put("value", "#submit").put("exact", true).put("name", "Submit")).isSuccess)
        assertFalse(locate(JSONObject().put("strategy", "css").put("value", "#submit").put("exact", true)
            .put("scopeRef", "bad ref")).isSuccess)
        assertFalse(locate(JSONObject().put("strategy", "xpath").put("value", "//button").put("exact", true)).isSuccess)
        assertFalse(locate(JSONObject().put("strategy", "role").put("value", "button").put("exact", true), control = null).isSuccess)
        assertFalse(locate(JSONObject().put("strategy", "role").put("value", "button").put("exact", true), owner = "human").isSuccess)
    }

    @Test
    fun emulateClearIsExclusiveAndNonClearKeepsBoundedDimensions() {
        assertParses("""{"action":"emulate","clear":true}""")
        assertParses("""{"action":"emulate","width":240,"height":2560,"mobile":false}""")
        assertParses("""{"action":"emulate","width":800,"height":600,"mobile":true,"clear":false}""")

        assertFalse(parse("""{"action":"emulate","clear":true,"width":800}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","clear":true,"height":600}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","clear":true,"mobile":true}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","clear":true,"unknown":false}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","clear":1}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","width":239,"height":600,"mobile":true}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","width":800,"height":2561,"mobile":true}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","width":800.5,"height":600,"mobile":true}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","width":800,"height":600,"mobile":true,"unknown":false}""").isSuccess)
        assertFalse(parse("""{"action":"emulate","clear":false}""").isSuccess)
    }

    @Test fun axObservationBoundsAreStrictAndDoNotAcceptCallerTargets() {
        assertParses("""{"action":"ax_tree"}""")
        assertParses("""{"action":"ax_tree","maxNodes":256,"maxDepth":8,"includeIgnored":true}""")
        listOf("""{"action":"ax_tree","maxNodes":0}""", """{"action":"ax_tree","maxNodes":257}""",
            """{"action":"ax_tree","maxDepth":9}""", """{"action":"ax_tree","maxDepth":"4"}""",
            """{"action":"ax_tree","includeIgnored":1}""", """{"action":"ax_tree","targetId":"other"}""",
            """{"action":"ax_tree","frameId":"other"}""").forEach { assertFalse(parse(it).isSuccess) }
    }

    private fun parse(args: String) = BrowserCommandRequest.parse(
        JSONObject()
            .put("version", 1)
            .put("requestId", "req_1")
            .put("method", "debug")
            .put("sessionGeneration", 4)
            .put("controlGeneration", 9)
            .put("owner", "ai")
            .put("deadlineAtMillis", System.currentTimeMillis() + 10_000)
            .put("args", JSONObject(args))
            .toString(),
        nowElapsedMillis = 1_234L,
    )

    private fun assertParses(args: String) {
        val result = parse(args)
        assertTrue("Expected valid args $args; parse failed: ${result.exceptionOrNull()}", result.isSuccess)
    }
}
