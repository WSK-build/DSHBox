package com.dshbox.browser.bridge

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRequestValidatorTest {
    private val now = 1_000_000L

    @Test
    fun structuredValidationPreservesBooleanCompatibilityWithoutEchoingInput() {
        val valid = JSONObject().put("version", 1).put("requestId", "req_1").put("method", "status")
            .put("deadlineAtMillis", now + 10_000).put("args", JSONObject())
        val accepted = BrowserRequestValidator.validate(valid, now)
        assertTrue(accepted.valid)
        assertTrue(accepted.stage == "accepted")
        assertTrue(BrowserRequestValidator.isValid(valid, now))

        val invalid = JSONObject(valid.toString()).put("unexpectedSensitiveValue", "never echo me")
        val rejected = BrowserRequestValidator.validate(invalid, now)
        assertFalse(rejected.valid)
        assertTrue(rejected.stage == "request_schema")
        assertFalse(BrowserRequestValidator.isValid(invalid, now))
    }

    @Test
    fun controlApprovalRequestsAreTheOnlyGenerationFreeControlException() {
        assertTrue(valid("control", """{"action":"request","summary":"Need to continue","taskId":"task_01"}"""))
        assertTrue(valid("control", """{"action":"status","approvalId":"${"a".repeat(32)}"}"""))
        assertTrue(valid("control", """{"action":"cancel","approvalId":"${"a".repeat(32)}"}"""))
        assertFalse(valid("control", """{"action":"request","summary":"  "}"""))
        assertFalse(valid("control", """{"action":"request","summary":"${"x".repeat(1001)}"}"""))
        assertFalse(valid("control", """{"action":"request","summary":"ok","taskId":"../bad"}"""))
        assertFalse(valid("control", """{"action":"approve","approvalId":"${"a".repeat(32)}"}"""))
        assertFalse(valid("control", """{"action":"request","summary":"ok"}""", session = 2L))
        assertFalse(valid("control", """{"action":"request","summary":"ok"}""", owner = "ai"))
        assertFalse(validWithTaskId("control", """{"action":"request","summary":"ok"}""", "task_01", 2L, 3L, "ai"))
    }

    @Test
    fun taskManagementHasClosedSessionSafeSchemaAndBoundedBudgets() {
        assertTrue(valid("task", """{"action":"begin","durationMs":14400000,"operationLimit":2000}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("task", """{"action":"begin","title":"QA","goal":"resume","notes":"note"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("task", """{"action":"status","taskId":"task_01"}"""))
        assertTrue(valid("task", """{"action":"list","offset":100,"limit":100}"""))
        assertTrue(valid("task", """{"action":"update","taskId":"task_01","expectedRevision":2,"goal":"new goal"}"""))
        assertTrue(valid("task", """{"action":"update","taskId":"task_01","expectedRevision":0,"goal":"initial revision"}"""))
        assertTrue(valid("task", """{"action":"delete","taskId":"task_01","expectedRevision":2}"""))
        assertTrue(valid("task", """{"action":"delete","taskId":"task_01","expectedRevision":0}"""))
        assertTrue(valid("task", """{"action":"wait","taskId":"task_01","reason":"page_load"}"""))
        assertTrue(valid("task", """{"action":"fail","taskId":"task_01","reason":"network failure"}"""))
        assertTrue(valid("task", """{"action":"fail","taskId":"task_01"}"""))
        assertFalse(valid("task", """{"action":"fail","taskId":"task_01","reason":"   "}"""))
        assertFalse(valid("task", """{"action":"fail","taskId":"task_01","reason":"${"x".repeat(1001)}"}"""))
        assertFalse(valid("task", """{"action":"wait","taskId":"task_01","reason":"free text"}"""))
        assertFalse(valid("task", """{"action":"reconcile","taskId":"task_01","expectedRevision":2}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("click", """{"nodeId":"n1"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("click", """{"screenshotId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","x":1.5,"y":2}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("click", """{"nodeId":"n1","screenshotId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","x":1,"y":2}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("click", """{"screenshotId":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","x":1,"y":2}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("click", """{"screenshotId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","x":"1","y":2}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(validWithTaskId("click", """{"nodeId":"n1"}""", "task_01", 4L, 9L, "ai"))
        assertFalse(valid("task", """{"action":"begin","operationLimit":2001}"""))
        assertFalse(valid("task", """{"action":"begin","goal":"x"}"""))
        assertFalse(valid("task", """{"action":"begin","goal":"${"x".repeat(4001)}"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("task", """{"action":"list","limit":101}"""))
        assertFalse(valid("task", """{"action":"update","taskId":"task_01","expectedRevision":2}"""))
        assertFalse(valid("task", """{"action":"update","taskId":"task_01","expectedRevision":-1,"goal":"negative revision"}"""))
        assertFalse(valid("task", """{"action":"delete","taskId":"task_01","expectedRevision":-1}"""))
        assertFalse(valid("task", """{"action":"delete","taskId":"task_01"}"""))
        assertFalse(valid("task", """{"action":"status","taskId":"../task"}"""))
        assertFalse(valid("task", """{"action":"resume","taskId":"task_01"}""", session = 4L))
        assertFalse(validWithTaskId("task", """{"action":"list"}""", "task_01", 4L, 9L, "ai"))
    }

    @Test
    fun collectWindowAndNetworkFramesUseStrictBoundedSchemas() {
        val collect = """{"action":"window","containerSelector":"#list","messageSelector":"article.item","keyAttribute":"data-id","declaredRange":"latest 20","maxRecords":20,"maxContentChars":1024}"""
        assertTrue(valid("collect", collect, session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("collect", JSONObject(collect).put("excludeSelector", ".draft").toString(), session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("collect", JSONObject(collect).put("excludeSelector", "x".repeat(513)).toString(), session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("collect", collect, session = 4L, control = 9L, owner = "human"))
        assertFalse(valid("collect", JSONObject(collect).put("maxRecords", 201).toString(), session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("collect", JSONObject(collect).put("extra", true).toString(), session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("network", """{"action":"frames","cursor":0,"limit":20}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("network", """{"action":"frames","cursor":-1}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("network", """{"action":"frames","limit":21}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("network", """{"action":"start","includeBodies":false,"includeRequestBodies":true}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("network", """{"action":"export_frames"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("network", """{"action":"export_frames","includeBodies":true}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("network", """{"action":"export_frames","includeBodies":false}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("network", """{"action":"export_frames","includeBodies":"true"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("network", """{"action":"replay_prepare","entryId":"entry-1"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("network", """{"action":"replay_status","requestId":"${"a".repeat(32)}"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("network", """{"action":"replay","entryId":"entry-1","riskToken":"${"a".repeat(32)}","captureId":"cap-${"b".repeat(20)}"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("network", """{"action":"start","includeRequestBodies":1}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("network", """{"action":"export_frames","path":"/tmp/x.json"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("network", """{"action":"replay_prepare","entryId":"entry-1","sessionGeneration":4}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("network", """{"action":"replay","entryId":"entry-1","riskToken":"${"a".repeat(32)}"}""", session = 4L, control = 9L, owner = "ai"))
    }

    @Test
    fun emulateClearIsExclusiveAndNonClearStillRequiresBoundedDimensions() {
        assertTrue(valid("debug", """{"action":"emulate","clear":true}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("debug", """{"action":"emulate","width":240,"height":2560,"mobile":false}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("debug", """{"action":"emulate","width":800,"height":600,"mobile":true,"clear":false}""", session = 4L, control = 9L, owner = "ai"))

        assertFalse(valid("debug", """{"action":"emulate","clear":true,"width":800}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","clear":true,"height":600}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","clear":true,"mobile":true}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","clear":true,"unknown":false}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","clear":1}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","width":239,"height":600,"mobile":true}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","width":800,"height":2561,"mobile":true}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","width":800.5,"height":600,"mobile":true}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","width":800,"height":600,"mobile":true,"unknown":false}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("debug", """{"action":"emulate","clear":false}""", session = 4L, control = 9L, owner = "ai"))
    }

    @Test
    fun pageListIsSessionReadAndMutationsRequireAiControl() {
        assertTrue(valid("page", """{"action":"list"}""", session = 4L))
        assertTrue(valid("page", """{"action":"new"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("page", """{"action":"switch","pageId":"pg-0123456789abcdefabcd"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("page", """{"action":"close","pageId":"pg-0123456789abcdefabcd"}""", session = 4L, control = 9L, owner = "ai"))

        assertFalse(valid("page", """{"action":"list"}""", session = 4L, owner = "ai"))
        assertFalse(valid("page", """{"action":"new"}""", session = 4L, control = 9L))
        assertFalse(valid("page", """{"action":"switch","pageId":"../other"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("page", """{"action":"new","url":"https://example.test"}""", session = 4L, control = 9L, owner = "ai"))
    }

    @Test
    fun waitIsSessionScopedAndNavigateRequiresAiControl() {
        assertTrue(valid("wait", """{"condition":"selector","selector":"#ready","state":"visible","timeoutMs":30000,"pollIntervalMs":100}""", session = 4L))
        assertTrue(valid("navigate", """{"action":"back"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("navigate", """{"action":"back","thumbnail":true}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("navigate", """{"action":"back","thumbnail":"true"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("open", """{"url":"https://example.test/","thumbnail":true}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("open", """{"thumbnail":false}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("open", """{"thumbnail":true}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("open", """{"url":"https://example.test/","thumbnail":1}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("wait", """{"condition":"load","selector":"#unexpected"}""", session = 4L))
        assertFalse(valid("wait", """{"condition":"url","url":"https://example.test","timeoutMs":30001}""", session = 4L))
        assertFalse(valid("wait", """{"condition":"text","text":"ready","pollIntervalMs":99}""", session = 4L))
        assertFalse(valid("wait", """{"condition":"text","text":"ready"}""", session = 4L, owner = "ai"))
        assertFalse(valid("navigate", """{"action":"back"}""", session = 4L))
        assertFalse(valid("navigate", """{"action":"goto"}""", session = 4L, control = 9L, owner = "ai"))
    }

    @Test
    fun locateRequiresAiControlAndStrictStrategyArguments() {
        val ref = "b:4:9:3:7:0123456789abcdef0123456789abcdef:0"
        assertTrue(valid("locate", """{"strategy":"role","value":"button","exact":true,"name":"Continue","scopeRef":"$ref"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("locate", """{"strategy":"css","value":"#submit","exact":false}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("locate", """{"strategy":"css","value":"#submit","exact":false,"partial":true}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("locate", """{"strategy":"css","value":"#submit","exact":false,"partial":false}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("locate", """{"strategy":"css","value":"#submit","exact":false,"partial":"true"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("locate", """{"strategy":"role","value":"button","exact":true,"name":""}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("locate", """{"strategy":"label","value":"Email","exact":false,"name":"forbidden"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("locate", """{"strategy":"css","value":"#submit","exact":1}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("locate", """{"strategy":"role","value":"button","exact":true,"scopeRef":"n1"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("locate", """{"strategy":"xpath","value":"//button","exact":true}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("locate", """{"strategy":"role","value":"button","exact":true}""", session = 4L, owner = "ai"))
        assertFalse(valid("locate", """{"strategy":"role","value":"button","exact":true}""", session = 4L, control = 9L, owner = "human"))
    }

    @Test
    fun fileReadsAreSessionOnlyAndMutationsRequireAiControl() {
        val id = "0123456789abcdef0123456789abcdef"
        assertTrue(valid("file", """{"action":"list","limit":20,"beforeId":"$id"}""", session = 4L))
        assertTrue(valid("file", """{"action":"status","id":"$id"}""", session = 4L))
        assertTrue(valid("file", """{"action":"start","url":"https://example.test/file","suggestedFileName":"报告.zip"}""", session = 4L, control = 9L, owner = "ai"))
        assertTrue(valid("file", """{"action":"cancel","id":"$id"}""", session = 4L, control = 9L, owner = "ai"))

        assertFalse(valid("file", """{"action":"list","limit":21}""", session = 4L))
        assertFalse(valid("file", """{"action":"status","id":"../secret"}""", session = 4L))
        assertFalse(valid("file", """{"action":"status","id":"$id"}""", session = 4L, owner = "ai"))
        assertFalse(valid("file", """{"action":"cancel","id":"$id"}""", session = 4L))
        assertFalse(valid("file", """{"action":"start","url":"file:///secret"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("file", """{"action":"start","url":"https://user:pass@example.test/file"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("file", """{"action":"start","url":"https://example.test/file","suggestedFileName":"../secret"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("file", """{"action":"start","url":"https://example.test/file","headers":{"Authorization":"secret"}}""", session = 4L, control = 9L, owner = "ai"))
    }

    @Test
    fun uploadIsTaskBoundAiControlledAndRejectsProtectedOrArbitraryPaths() {
        val id = "0123456789abcdef0123456789abcdef"
        assertTrue(validWithTaskId("upload", """{"action":"request","paths":["reports/report.pdf"],"multiple":false,"acceptTypes":["application/pdf"]}""", "task_01", 4L, 9L, "ai"))
        assertTrue(validWithTaskId("upload", """{"action":"list","approvalId":"$id"}""", "task_01", 4L, 9L, "ai"))
        assertTrue(validWithTaskId("upload", """{"action":"submit","approvalId":"$id","fileIds":["${"a".repeat(32)}"]}""", "task_01", 4L, 9L, "ai"))
        assertTrue(validWithTaskId("upload", """{"action":"activate","approvalId":"$id","nodeId":"b:4:9:3:1:${"b".repeat(32)}:0"}""", "task_01", 4L, 9L, "ai"))
        assertFalse(validWithTaskId("upload", """{"action":"activate","approvalId":"$id","nodeId":"bogus"}""", "task_01", 4L, 9L, "ai"))
        for (path in listOf("/etc/passwd", "../secret", "user-data/private.txt", ".dsh/config", ".env.local", "id_rsa", "x.p12")) {
            assertFalse(validWithTaskId("upload", """{"action":"request","paths":["$path"],"multiple":false}""", "task_01", 4L, 9L, "ai"))
        }
        assertFalse(valid("upload", """{"action":"list","approvalId":"$id"}""", session = 4L, control = 9L, owner = "ai"))
        assertFalse(validWithTaskId("upload", """{"action":"list","approvalId":"$id"}""", "task_01", 4L, 9L, "human"))
        assertFalse(validWithTaskId("upload", """{"action":"submit","approvalId":"$id","fileIds":["../bad"]}""", "task_01", 4L, 9L, "ai"))
    }

    @Test fun selectionReceiptRequiresExactFieldsAndCurrentSession() {
        val id = "12345678-1234-4234-8234-123456789abc"
        val receipt = """{"action":"receipt","selectionId":"$id","destinationSessionKey":"session-a","deliveryCode":"DELIVERED","requestId":"0123456789abcdef0123456789abcdef","imageIncluded":true}"""
        assertTrue(valid("selection", receipt, session = 4L))
        assertFalse(valid("selection", receipt))
        assertFalse(valid("selection", receipt, session = 4L, control = 9L, owner = "ai"))
        assertFalse(valid("selection", receipt.replace("true", "1"), session = 4L))
        assertFalse(valid("selection", receipt.replace(id, "------------------------------------"), session = 4L))
        assertFalse(valid("selection", receipt.replace("DELIVERED", "delivered"), session = 4L))
        assertFalse(valid("selection", receipt.dropLast(1) + ",\"sessionId\":\"foreign\"}", session = 4L))
    }

    @Test fun selectionClaimAndAdmissionRequireTargetAndDigest() {
        val id = "12345678-1234-4234-8234-123456789abc"
        val fields = "\"selectionId\":\"$id\",\"destinationSessionKey\":\"session-a\",\"requestId\":\"0123456789abcdef0123456789abcdef\",\"payloadDigest\":\"${"a".repeat(64)}\""
        for (action in listOf("claim", "admit")) {
            assertTrue(valid("selection", """{"action":"$action",$fields}""", session = 4L))
            val badFields = fields.substringBefore(",\"payloadDigest\"")
            assertFalse(valid("selection", """{"action":"$action",$badFields}""", session = 4L))
        }
    }

    @Test fun taskFeedbackActionsRequireTheSelectionSessionAndExactPayload() {
        val id = "a".repeat(32)
        val requestId = "b".repeat(32)
        val digest = "c".repeat(64)
        val pending = "\"afterSequence\":0,\"destinationSessionKey\":\"agent-session-1\""
        assertTrue(valid("selection", """{"action":"task_pending",$pending}""", session = 4L))
        assertFalse(valid("selection", """{"action":"task_pending",$pending}"""))
        val claim = "\"feedbackId\":\"$id\",\"destinationSessionKey\":\"agent-session-1\",\"requestId\":\"$requestId\",\"payloadDigest\":\"$digest\""
        for (action in listOf("task_claim", "task_admit")) {
            assertTrue(valid("selection", """{"action":"$action",$claim}""", session = 4L))
            assertFalse(valid("selection", """{"action":"$action",$claim,"extra":true}""", session = 4L))
        }
        for (code in listOf("PROMPT_ACCEPTED", "UNKNOWN", "BLOCKED")) {
            val receipt = "\"feedbackId\":\"$id\",\"destinationSessionKey\":\"agent-session-1\",\"requestId\":\"$requestId\",\"deliveryCode\":\"$code\""
            assertTrue(valid("selection", """{"action":"task_receipt",$receipt}""", session = 4L))
        }
        val badReceipt = "\"feedbackId\":\"$id\",\"destinationSessionKey\":\"agent-session-1\",\"requestId\":\"$requestId\",\"deliveryCode\":\"DELIVERED\""
        assertFalse(valid("selection", """{"action":"task_receipt",$badReceipt}""", session = 4L))
    }

    private fun valid(method: String, argsJson: String, session: Long? = null, control: Long? = null, owner: String? = null): Boolean {
        val request = JSONObject()
            .put("version", 1)
            .put("requestId", "req_1")
            .put("method", method)
            .put("deadlineAtMillis", now + 10_000)
            .put("args", JSONObject(argsJson))
        session?.let { request.put("sessionGeneration", it) }
        control?.let { request.put("controlGeneration", it) }
        owner?.let { request.put("owner", it) }
        return BrowserRequestValidator.isValid(request, now)
    }

    private fun validWithTaskId(method: String, argsJson: String, taskId: String, session: Long, control: Long, owner: String): Boolean {
        val request = JSONObject()
            .put("version", 1).put("requestId", "req_1").put("method", method)
            .put("deadlineAtMillis", now + 10_000).put("args", JSONObject(argsJson))
            .put("taskId", taskId).put("sessionGeneration", session)
            .put("controlGeneration", control).put("owner", owner)
        return BrowserRequestValidator.isValid(request, now)
    }
}
