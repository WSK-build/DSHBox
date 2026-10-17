package com.dshbox.browser.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCliStatusTest {
    @Test fun disabledAutomationStillDispatchesOnlyApprovalManagement() {
        val disabled = JSONObject(statusJson("idle", "unknown")).put("automationEnabled", false).put("owner", "human")
        for (action in listOf("request", "status", "cancel")) {
            val request = JSONObject().put("method", "control").put("args", JSONObject().put("action", action))
            assertNull(browserCliAdmissionRejection(request, disabled))
            assertNull(browserCliAdmissionRejection(request, JSONObject(disabled.toString()).put("state", "closed")))
        }
        for (method in listOf("open", "click", "type", "navigate", "screenshot", "page")) {
            assertEquals("AUTOMATION_DISABLED", browserCliAdmissionRejection(JSONObject().put("method", method), disabled))
        }
        assertEquals("AUTOMATION_DISABLED", browserCliAdmissionRejection(JSONObject().put("method", "control")
            .put("args", JSONObject().put("action", "approve")), disabled))
    }

    @Test fun admissionStillRejectsClosedOrMismatchedNavigationSessions() {
        val closed = JSONObject(statusJson("closed", "unknown"))
        assertEquals("NO_ACTIVE_SESSION", browserCliAdmissionRejection(JSONObject().put("method", "click"), closed))
        assertEquals("STALE_SESSION", browserCliAdmissionRejection(JSONObject().put("method", "open").put("sessionGeneration", 1), closed))
        assertEquals("SESSION_REQUIRED", browserCliAdmissionRejection(JSONObject().put("method", "open"), JSONObject(statusJson("idle", "unknown"))))
    }
    @Test fun liveStateSurvivesStaleMirrorAfterWriteFailure() {
        val old = parseBrowserStatusSnapshot(statusJson("closed", "unknown"))!!
        val live = JSONObject(old.toString()).put("state", "idle").put("owner", "ai")
            .put("sessionGeneration", old.getLong("sessionGeneration") + 1)
        assertEquals("ai", selectBrowserStatus(live, old).getString("owner"))
        assertEquals("idle", selectBrowserStatus(live, old).getString("state"))
    }

    @Test fun currentLiveStateWinsWithinIdentityButNewerGenerationWins() {
        val old = parseBrowserStatusSnapshot(statusJson("running", "unknown"))!!
        val live = JSONObject(old.toString()).put("state", "idle").put("visible", false)
        assertFalse(selectBrowserStatus(live, old).getBoolean("visible"))
        val newerControl = JSONObject(old.toString()).put("controlGeneration", old.getLong("controlGeneration") + 1)
        assertEquals(newerControl.toString(), selectBrowserStatus(live, newerControl).toString())
        val newerSession = JSONObject(old.toString()).put("sessionGeneration", old.getLong("sessionGeneration") + 1)
            .put("controlGeneration", 0)
        assertEquals(newerSession.toString(), selectBrowserStatus(live, newerSession).toString())
        assertEquals(old.toString(), selectBrowserStatus(null, old).toString())
    }
    @Test
    fun onlyTaskInspectionAndSuspensionCanReachServiceWithoutActiveBrowser() {
        for (action in listOf("status", "list", "pause", "wait", "fail", "update", "delete")) {
            assertTrue(browserTaskOfflineAction(JSONObject().put("method", "task")
                .put("args", JSONObject().put("action", action))))
        }
        for (action in listOf("begin", "resume", "reconcile", "complete", "click", "unknown")) {
            assertFalse(browserTaskOfflineAction(JSONObject().put("method", "task")
                .put("args", JSONObject().put("action", action))))
        }
        assertFalse(browserTaskOfflineAction(JSONObject().put("method", "click")
            .put("args", JSONObject().put("action", "status"))))
        assertFalse(browserTaskOfflineAction(JSONObject().put("method", "task")))
    }

    @Test
    fun acceptsRuntimeDiagnosticMirrorForEveryLifecycleState() {
        val statuses = listOf(
            "running" to "unknown",
            "idle" to "unknown",
            "closed" to "idle_timeout",
        )
        for ((state, closeReason) in statuses) {
            val parsed = parseBrowserStatusSnapshot(statusJson(state, closeReason))!!
            assertEquals(state, parsed.getString("state"))
            assertEquals(true, parsed.getBoolean("visible") == (state == "running"))
            assertEquals(closeReason, parsed.getString("lastCloseReason"))
            assertTrue(parsed.has("idleRemainingMs"))
        }
    }

    @Test
    fun acceptsOlderMirrorWithoutOptionalDiagnosticFields() {
        val parsed = parseBrowserStatusSnapshot(
            """{"state":"running","automationEnabled":true,"sessionGeneration":1025,"controlGeneration":4,"owner":"ai"}""",
        )!!

        assertEquals("running", parsed.getString("state"))
        assertFalse(parsed.has("visible"))
        assertFalse(parsed.has("idleRemainingMs"))
        assertFalse(parsed.has("lastCloseReason"))
    }

    @Test
    fun rejectsUnknownOrMalformedOptionalStatusFields() {
        val valid = statusJson("running", "unknown")
        val malformed = listOf(
            valid.replace("\"visible\":true", "\"visible\":1"),
            valid.replace("\"idleRemainingMs\":null", "\"idleRemainingMs\":-1"),
            valid.replace("\"idleRemainingMs\":null", "\"idleRemainingMs\":960001"),
            valid.replace("\"lastCloseReason\":\"unknown\"", "\"lastCloseReason\":\"process_lost\""),
            valid.dropLast(1) + ",\"unexpected\":true}",
        )

        malformed.forEach { assertNull(parseBrowserStatusSnapshot(it)) }
    }

    @Test
    fun statusResponsePreservesLifecycleDiagnosticsAndUnknownProcessLossReason() {
        val running = parseBrowserStatusSnapshot(statusJson("running", "unknown"))!!
        val runningResponse = browserCachedStatusResponse("req_1", running)
        assertEquals("RUNNING", runningResponse.getString("state"))
        assertEquals(true, runningResponse.getJSONObject("data").getBoolean("visible"))
        assertEquals("unknown", runningResponse.getJSONObject("data").getString("lastCloseReason"))

        val processLost = parseBrowserStatusSnapshot(
            statusJson("closed", "unknown", visible = false, idleRemaining = null),
        )!!
        val closedResponse = browserCachedStatusResponse("req_2", processLost)
        assertEquals("CLOSED", closedResponse.getString("state"))
        assertEquals("unknown", closedResponse.getJSONObject("data").getString("lastCloseReason"))
        assertFalse(closedResponse.getJSONObject("data").has("idleTimeout"))
    }

    private fun statusJson(
        state: String,
        closeReason: String,
        visible: Boolean = state == "running",
        idleRemaining: Long? = if (visible) null else 123_000L,
    ): String = JSONObject()
        .put("state", state)
        .put("automationEnabled", true)
        .put("sessionGeneration", 1025)
        .put("controlGeneration", 4)
        .put("owner", if (state == "closed") "none" else "ai")
        .put("visible", visible)
        .put("idleRemainingMs", idleRemaining ?: JSONObject.NULL)
        .put("lastCloseReason", closeReason)
        .toString()
}
