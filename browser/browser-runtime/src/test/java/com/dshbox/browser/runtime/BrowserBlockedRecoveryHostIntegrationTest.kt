package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserTaskRecord
import com.dshbox.browser.api.BrowserTaskRecoveryPolicy
import com.dshbox.browser.api.BrowserTaskStateMachine
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserTaskWaitReason
import com.dshbox.browser.api.BrowserTaskTransition
import com.dshbox.browser.api.BrowserUnknownEffectMetadata
import com.dshbox.browser.api.BrowserEffectPhase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM fixture validating production evidence and task state rules against observations.
 * Optional JSON input is selected by a system property or environment variable; otherwise local fixtures are used.
 */
class BrowserBlockedRecoveryHostIntegrationTest {
    @Test
    fun blockedEvidenceMovesToWaitingUserOnlyAfterFreshRecovery() {
        val stage = System.getProperty("browser.blocked.host.stage")
            ?: System.getenv("BROWSER_BLOCKED_HOST_STAGE") ?: "blocked"
        val inputPath = System.getProperty("browser.blocked.host.input")
            ?: System.getenv("BROWSER_BLOCKED_HOST_INPUT")
        val input = inputPath?.let { JSONObject(File(it).readText(Charsets.UTF_8)) } ?: fallbackInput()
        val observation = input.getJSONObject("observation")
        val identity = identity(input.getJSONObject("identity"))
        val evidence = BrowserBlockedPageEvidence.inspect(observation)
        val result = JSONObject()

        if (stage == "blocked") {
            assertEquals("possible", evidence.getString("assessment"))
            assertFalse(evidence.getBoolean("automaticResolutionAllowed"))
            assertTrue(evidence.getJSONArray("signals").length() > 0)

            val record = taskRecord(identity, status = BrowserTaskStatus.RUNNING, revision = 0)
            assertTrue(BrowserTaskStateMachine.canTransition(record.status, BrowserTaskStatus.WAITING_USER))
            val waiting = record.copy(
                revision = record.revision + 1,
                status = BrowserTaskStatus.WAITING_USER,
                waitReason = BrowserTaskWaitReason.USER_AUTHENTICATION,
            )

            val transfer = BrowserControlTransferPolicy()
            val pending = requireNotNull(transfer.begin(BrowserControlTransferTarget.HUMAN, 1L, 1L))
            val transferAfterCancel = transfer.cancel(pending.id) && !transfer.hasPending
            assertNull(transfer.resolve(pending.id, confirmed = true, sessionGeneration = 1L, controlGeneration = 1L))

            val approvals = BrowserControlApprovalStore()
            val approvalId = "a".repeat(32)
            val started = approvals.start(approvalId, "nonce", "Handle the blocked page", "task_host_01",
                identity, 100L, 1_000L)
            assertTrue(started is BrowserControlApprovalStore.Start.Created)
            val denied = approvals.settle(approvalId, "nonce", BrowserControlApprovalStatus.DENIED, 101L)
            val transferAfterDeny = denied?.status == BrowserControlApprovalStatus.DENIED && !approvals.hasPending()

            val unknown = BrowserUnknownEffectMetadata(
                requestId = "effect_unknown_01",
                method = "click",
                createdElapsedMillis = 100L,
                phase = BrowserEffectPhase.UNKNOWN,
                hasUserVerification = false,
                lastUserChoice = null,
            )
            assertTrue(BrowserTaskRecoveryPolicy.blocksMutatingOperation(listOf(unknown)))
            assertEquals(
                BrowserTaskTransition.PageOrAuthorizationMismatch,
                BrowserTaskStateMachine.validateReconcile(
                    waiting, waiting.revision, identity.copy(pageId = "other_page"),
                    automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 300L, currentBootId = "boot-host",
                ),
            )
            assertEquals(
                BrowserTaskTransition.PageOrAuthorizationMismatch,
                BrowserTaskStateMachine.validateReconcile(
                    waiting, waiting.revision, identity.copy(sessionGeneration = 2L),
                    automationEnabled = true, ownerIsAi = true, nowElapsedMillis = 300L, currentBootId = "boot-host",
                ),
            )

            result.put("stage", "blocked")
                .put("assessment", evidence.getString("assessment"))
                .put("blockInfo", evidence)
                .put("status", waiting.status.wireValue)
                .put("waitReason", waiting.waitReason?.wireValue)
                .put("controlTransferAfterCancel", transferAfterCancel)
                .put("controlTransferAfterDeny", transferAfterDeny)
                .put("unknownBlocksMutation", true)
                .put("staleIdentityRejected", true)
        } else if (stage == "resume") {
            assertEquals("not_detected", evidence.getString("assessment"))
            val waiting = taskRecord(
                identity,
                status = BrowserTaskStatus.WAITING_USER,
                revision = 1,
                waitReason = BrowserTaskWaitReason.USER_AUTHENTICATION,
            )
            val fresh = identity(input.getJSONObject("freshIdentity"))
            assertTrue(BrowserTaskStateMachine.canTransition(waiting.status, BrowserTaskStatus.PAUSED))
            assertEquals(
                BrowserTaskTransition.Applied,
                BrowserTaskStateMachine.validateReconcile(
                    waiting, waiting.revision, fresh, automationEnabled = true, ownerIsAi = true,
                    nowElapsedMillis = 500L, currentBootId = "boot-host",
                ),
            )
            val paused = waiting.copy(
                revision = waiting.revision + 1,
                status = BrowserTaskStatus.PAUSED,
                pageIdentity = fresh,
                waitReason = null,
            )
            assertEquals(
                BrowserTaskTransition.Applied,
                BrowserTaskStateMachine.validateResume(
                    paused, paused.revision, fresh, automationEnabled = true, ownerIsAi = true,
                    nowElapsedMillis = 600L, currentBootId = "boot-host",
                ),
            )
            assertEquals(
                BrowserTaskTransition.PageOrAuthorizationMismatch,
                BrowserTaskStateMachine.validateResume(
                    paused, paused.revision, fresh.copy(pageId = "other_page"), automationEnabled = true,
                    ownerIsAi = true, nowElapsedMillis = 600L, currentBootId = "boot-host",
                ),
            )
            assertEquals(
                BrowserTaskTransition.PageOrAuthorizationMismatch,
                BrowserTaskStateMachine.validateResume(
                    paused, paused.revision, fresh.copy(controlGeneration = 1L), automationEnabled = true,
                    ownerIsAi = true, nowElapsedMillis = 600L, currentBootId = "boot-host",
                ),
            )
            val resumed = paused.copy(
                revision = paused.revision + 1,
                status = BrowserTaskStatus.RUNNING,
                waitReason = null,
            )
            assertEquals(BrowserTaskStatus.RUNNING, resumed.status)
            assertEquals("task_host_01", resumed.taskId)
            result.put("stage", "resume")
                .put("assessment", evidence.getString("assessment"))
                .put("blockInfo", evidence)
                .put("status", resumed.status.wireValue)
                .put("reconciled", true)
                .put("resumed", true)
                .put("freshObservation", true)
                .put("staleIdentityRejected", true)
                .put("businessEffect", input.optBoolean("businessEffect", false))
                .put("taskId", resumed.taskId)
                .put("revision", resumed.revision)
        } else {
            throw AssertionError("unknown host stage: $stage")
        }

        val outputPath = System.getProperty("browser.blocked.host.output")
            ?: System.getenv("BROWSER_BLOCKED_HOST_OUTPUT")
        if (outputPath != null) {
            File(outputPath).parentFile?.mkdirs()
            File(outputPath).writeText(result.toString(2), Charsets.UTF_8)
        }
    }

    private fun identity(value: JSONObject): BrowserTaskPageIdentity = BrowserTaskPageIdentity(
        sessionGeneration = value.optLong("sessionGeneration", 1L),
        controlGeneration = value.optLong("controlGeneration", 1L),
        pageId = value.optString("pageId", "page-host"),
        documentGeneration = value.optLong("documentGeneration", 1L),
    )

    private fun fallbackInput(): JSONObject = JSONObject()
        .put("observation", JSONObject()
            .put("title", "Verify you are human")
            .put("text", "Human verification required")
            .put("elements", JSONArray()))
        .put("identity", JSONObject()
            .put("sessionGeneration", 1L)
            .put("controlGeneration", 1L)
            .put("pageId", "page-host")
            .put("documentGeneration", 1L))

    private fun taskRecord(
        identity: BrowserTaskPageIdentity,
        status: BrowserTaskStatus,
        revision: Long,
        waitReason: BrowserTaskWaitReason? = null,
    ): BrowserTaskRecord = BrowserTaskRecord(
        taskId = "task_host_01",
        revision = revision,
        status = status,
        createdElapsedMillis = 100L,
        deadlineElapsedMillis = 10_000L,
        bootId = "boot-host",
        operationLimit = 500,
        operationsUsed = 0,
        pageIdentity = identity,
        waitReason = waitReason,
        updatedElapsedMillis = 200L,
    )

}
