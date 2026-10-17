package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserEffectPhase
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserTaskStateMachine
import com.dshbox.browser.api.BrowserTaskStatus
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class BrowserTaskCoordinatorIntegrationTest {
    private lateinit var store: BrowserTaskCoordinatorHostStore
    private lateinit var runtime: BrowserTaskCoordinatorHostRuntime
    private lateinit var coordinator: BrowserTaskCoordinator
    private val identity = BrowserTaskPageIdentity(1, 1, "pg-0123456789abcdefabcd", 1)
    private val freshIdentity = identity.copy(controlGeneration = 2, documentGeneration = 2)

    @Before
    fun setUp() {
        store = BrowserTaskCoordinatorHostStore()
        runtime = BrowserTaskCoordinatorHostRuntime(store)
        coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        assertTrue(runtime.awaitInitialized())
        assertTrue(coordinator.isReady())
    }

    @After
    fun tearDown() {
        coordinator.close()
        assertTrue(runtime.awaitClosed())
        assertTrue(runtime.awaitShutdown())
        assertTrue(runtime.shutdownCalled)
    }

    @Test
    fun actualExecuteTaskChainPersistsWaitReconcileResumeAndBusinessCheckpoint() {
        val beginRequest = taskRequest(
            action = "begin",
            requestId = "task_begin",
            args = JSONObject()
                .put("durationMs", 60_000L)
                .put("operationLimit", 10)
                .put("title", "Coordinator host")
                .put("goal", "exercise production task chain"),
            context = live(identity),
            control = true,
        )
        val begin = execute(beginRequest, live(identity))
        val created = begin.getJSONObject("data").getJSONObject("task")
        val taskId = created.getString("taskId")
        assertEquals(0, created.getInt("revision"))
        assertEquals(BrowserTaskStatus.RUNNING.wireValue, created.getString("status"))

        val waiting = execute(
            taskRequest(
                action = "wait",
                requestId = "task_wait",
                taskId = taskId,
                args = JSONObject().put("reason", "user_authentication"),
            ),
            null,
        )
        assertEquals(BrowserTaskStatus.WAITING_USER.wireValue,
            waiting.getJSONObject("data").getJSONObject("task").getString("status"))
        assertFalse(waiting.getJSONObject("data").getJSONObject("task").getBoolean("canResume"))
        val staleWait = execute(
            taskRequest(
                action = "wait",
                requestId = "task_wait_stale_page",
                taskId = taskId,
                args = JSONObject().put("reason", "user_authentication"),
                context = live(identity.copy(pageId = "pg-ffffffffffffffffffff")),
                control = false,
            ),
            live(identity.copy(pageId = "pg-ffffffffffffffffffff")),
        )
        assertEquals("TASK_PAGE_MISMATCH", staleWait.getString("code"))

        val statusWithLive = execute(
            taskRequest(
                action = "status",
                requestId = "task_status_live",
                taskId = taskId,
                args = JSONObject(),
            ),
            live(identity),
        )
        assertFalse(statusWithLive.getJSONObject("data").getJSONObject("task").getBoolean("canResume"))
        val statusInactive = execute(
            taskRequest(
                action = "status",
                requestId = "task_status_inactive",
                taskId = taskId,
                args = JSONObject(),
            ),
            live(identity).copy(active = false),
        )
        assertFalse(statusInactive.getJSONObject("data").getJSONObject("task").getBoolean("canResume"))

        val staleReconcile = execute(
            taskRequest(
                action = "reconcile",
                requestId = "task_reconcile_stale",
                taskId = taskId,
                args = JSONObject(),
                context = live(identity.copy(pageId = "pg-ffffffffffffffffffff")),
                control = true,
            ),
            live(identity.copy(pageId = "pg-ffffffffffffffffffff")),
        )
        assertEquals("TASK_PAGE_MISMATCH", staleReconcile.getString("code"))

        val resumedBeforeFresh = execute(
            taskRequest(
                action = "resume",
                requestId = "task_resume_before_fresh",
                taskId = taskId,
                args = JSONObject(),
                context = live(identity),
                control = true,
            ),
            live(identity),
        )
        assertEquals("TASK_TRANSITION_INVALID", resumedBeforeFresh.getString("code"))

        val reconciled = execute(
            taskRequest(
                action = "reconcile",
                requestId = "task_reconcile",
                taskId = taskId,
                args = JSONObject(),
                context = live(freshIdentity),
                control = true,
            ),
            live(freshIdentity),
        )
        val paused = reconciled.getJSONObject("data").getJSONObject("task")
        assertEquals(BrowserTaskStatus.PAUSED.wireValue, paused.getString("status"))
        assertTrue(paused.getBoolean("canResume"))

        val exhaustedRecord = store.task(taskId)!!.copy(
            revision = store.task(taskId)!!.revision + 1,
            operationLimit = 1,
            operationsUsed = 1,
        )
        assertFalse(coordinator.exhaustedCanResumeForTest(
            exhaustedRecord, live(freshIdentity), runtime.elapsedRealtime(), "boot-host",
        ))
        assertEquals(0, exhaustedRecord.remainingOperations)

        val staleResume = execute(
            taskRequest(
                action = "resume",
                requestId = "task_resume_stale",
                taskId = taskId,
                args = JSONObject(),
                context = live(identity),
                control = true,
            ),
            live(identity),
        )
        assertEquals("TASK_PAGE_MISMATCH", staleResume.getString("code"))

        val resumed = execute(
            taskRequest(
                action = "resume",
                requestId = "task_resume",
                taskId = taskId,
                args = JSONObject(),
                context = live(freshIdentity),
                control = true,
            ),
            live(freshIdentity),
        )
        val running = resumed.getJSONObject("data").getJSONObject("task")
        assertEquals(BrowserTaskStatus.RUNNING.wireValue, running.getString("status"))
        assertFalse(running.getBoolean("canResume"))

        val duplicate = execute(beginRequest, live(identity))
        assertEquals("DUPLICATE_RECEIPT", duplicate.getString("code"))

        val clickRequest = BrowserCommandRequest.parse(
            JSONObject()
                .put("version", 1)
                .put("requestId", "effect_click")
                .put("method", "click")
                .put("owner", "ai")
                .put("sessionGeneration", 1)
                .put("controlGeneration", 2)
                .put("deadlineAtMillis", runtime.elapsedRealtime() + 10_000)
                .put("taskId", taskId)
                .put("args", JSONObject().put("nodeId", nodeId(2, 0)))
                .toString(),
            runtime.elapsedRealtime(),
        ).getOrThrow()
        val admission = AtomicReference<BrowserEffectAdmission>()
        val digest = AtomicReference<String>()
        val admitted = CountDownLatch(1)
        coordinator.admitOperation(clickRequest, live(freshIdentity)) { value, valueDigest ->
            admission.set(value)
            digest.set(valueDigest)
            admitted.countDown()
        }
        assertTrue(admitted.await(2, TimeUnit.SECONDS))
        assertTrue(admission.get() is BrowserEffectAdmission.NewIntent)
        assertEquals(1, store.task(taskId)!!.operationsUsed)
        assertEquals(BrowserEffectPhase.INTENT, store.effect("effect_click")!!.phase)

        val terminal = JSONObject()
            .put("version", 1)
            .put("requestId", "effect_click")
            .put("code", "OK")
            .put("executed", true)
            .put("sessionGeneration", 1)
            .put("controlGeneration", 2)
            .put("state", "RUNNING")
            .put("owner", "ai")
            .put("data", JSONObject().put("nodeId", nodeId(2, 0)))
            .toString()
        val finished = CountDownLatch(1)
        val finishedResponse = AtomicReference<String>()
        coordinator.finishOperation(
            "effect_click",
            digest.get(),
            terminal,
            { value -> finishedResponse.set(value); finished.countDown() },
        )
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals("OK", JSONObject(finishedResponse.get()).getString("code"))
        assertEquals(BrowserEffectPhase.CONFIRMED, store.effect("effect_click")!!.phase)
        assertEquals(1, store.task(taskId)!!.operationsUsed)

        val completeRequest = taskRequest(
            action = "complete",
            requestId = "task_complete",
            taskId = taskId,
            args = JSONObject(),
            context = live(freshIdentity),
            control = true,
        )
        val complete = execute(completeRequest, live(freshIdentity))
        assertEquals(BrowserTaskStatus.COMPLETED.wireValue,
            complete.getJSONObject("data").getJSONObject("task").getString("status"))
        assertTrue(complete.getJSONObject("data").getJSONObject("task").getBoolean("canDelete"))
        val replayComplete = execute(completeRequest, live(freshIdentity))
        assertEquals("DUPLICATE_RECEIPT", replayComplete.getString("code"))
    }

    @Test
    fun staleManagementRevisionAndUnknownEffectFailClosedWithoutReplay() {
        val begin = execute(
            taskRequest(
                action = "begin",
                requestId = "negative_begin",
                args = JSONObject()
                    .put("durationMs", 60_000L)
                    .put("operationLimit", 10)
                    .put("title", "Negative"),
                context = live(identity),
                control = true,
            ),
            live(identity),
        )
        val taskId = begin.getJSONObject("data").getJSONObject("task").getString("taskId")
        val stale = execute(
            taskRequest(
                action = "update",
                requestId = "stale_update",
                taskId = taskId,
                args = JSONObject().put("expectedRevision", 99L).put("notes", "stale"),
            ),
            null,
        )
        assertEquals("TASK_STALE", stale.getString("code"))

        val click = BrowserCommandRequest.parse(
            JSONObject()
                .put("version", 1)
                .put("requestId", "unknown_click")
                .put("method", "click")
                .put("owner", "ai")
                .put("sessionGeneration", 1)
                .put("controlGeneration", 1)
                .put("deadlineAtMillis", runtime.elapsedRealtime() + 10_000)
                .put("taskId", taskId)
                .put("args", JSONObject().put("nodeId", nodeId(1, 0)))
                .toString(),
            runtime.elapsedRealtime(),
        ).getOrThrow()
        val admitted = CountDownLatch(1)
        val admission = AtomicReference<BrowserEffectAdmission>()
        val digest = AtomicReference<String>()
        coordinator.admitOperation(click, live(identity)) { value, valueDigest ->
            admission.set(value)
            digest.set(valueDigest)
            admitted.countDown()
        }
        assertTrue(admitted.await(2, TimeUnit.SECONDS))
        assertTrue(admission.get() is BrowserEffectAdmission.NewIntent)

        val unknownComplete = execute(
            taskRequest(
                action = "complete",
                requestId = "unknown_complete",
                taskId = taskId,
                args = JSONObject(),
                context = live(identity),
                control = true,
            ),
            live(identity),
        )
        assertEquals("TASK_UNKNOWN_EFFECT_UNVERIFIED", unknownComplete.getString("code"))
        assertEquals(1, store.effect("unknown_click")!!.let {
            if (it.phase == BrowserEffectPhase.INTENT) 1 else 0
        })

        val unknownTerminal = JSONObject()
            .put("version", 1)
            .put("requestId", "unknown_click")
            .put("code", "UNKNOWN")
            .put("executed", JSONObject.NULL)
            .put("sessionGeneration", 1)
            .put("controlGeneration", 1)
            .put("state", "RUNNING")
            .put("owner", "ai")
            .put("data", JSONObject().put("sideEffectsMayHaveOccurred", true))
            .toString()
        val finished = CountDownLatch(1)
        coordinator.finishOperation(
            "unknown_click",
            digest.get(),
            unknownTerminal,
            callback = { finished.countDown() },
        )
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(BrowserEffectPhase.UNKNOWN, store.effect("unknown_click")!!.phase)
        assertEquals(BrowserTaskStatus.NEEDS_RECONCILIATION, store.task(taskId)!!.status)
    }

    @Test
    fun controlDriftAndChangingLiveContextProduceStrictFailuresWithoutReplay() {
        val begin = execute(
            taskRequest(
                action = "begin",
                requestId = "drift_begin",
                args = JSONObject()
                    .put("durationMs", 60_000L)
                    .put("operationLimit", 10)
                    .put("title", "Drift"),
                context = live(identity),
                control = true,
            ),
            live(identity),
        )
        val taskId = begin.getJSONObject("data").getJSONObject("task").getString("taskId")
        execute(
            taskRequest(
                action = "wait",
                requestId = "drift_wait",
                taskId = taskId,
                args = JSONObject().put("reason", "user_authentication"),
            ),
            null,
        )
        execute(
            taskRequest(
                action = "reconcile",
                requestId = "drift_reconcile",
                taskId = taskId,
                args = JSONObject(),
                context = live(freshIdentity),
                control = true,
            ),
            live(freshIdentity),
        )

        val request = taskRequest(
            action = "resume",
            requestId = "drift_resume",
            taskId = taskId,
            args = JSONObject(),
            context = live(freshIdentity),
            control = true,
        )
        val requestJson = JSONObject(request.rawJson)
        requestJson.put("controlGeneration", 99L)
        val drifted = BrowserCommandRequest.parse(requestJson.toString(), runtime.elapsedRealtime()).getOrThrow()
        val staleSession = execute(drifted, live(freshIdentity))
        assertEquals("STALE_SESSION", staleSession.getString("code"))

        val changing = executeChanging(
            taskRequest(
                action = "resume",
                requestId = "drift_context_change",
                taskId = taskId,
                args = JSONObject(),
                context = live(freshIdentity),
                control = true,
            ),
            before = live(freshIdentity),
            after = live(freshIdentity.copy(pageId = "pg-ffffffffffffffffffff")),
        )
        assertEquals("UNKNOWN", changing.getString("code"))
        assertTrue(changing.isNull("executed"))
        assertTrue(changing.getJSONObject("data").getBoolean("taskOutcomeUnconfirmed"))
        val unknown = store.snapshotEffects().first { it.requestId == "drift_context_change" }
        assertEquals(BrowserEffectPhase.UNKNOWN, unknown.phase)
        assertEquals(BrowserTaskStatus.NEEDS_RECONCILIATION, store.task(taskId)!!.status)
    }

    @Test
    fun beginContextDriftLinksGeneratedTaskAndRequiresReconciliation() {
        val response = executeChanging(
            taskRequest(
                action = "begin",
                requestId = "drift_begin_link",
                args = JSONObject().put("durationMs", 60_000L).put("operationLimit", 10),
                context = live(identity),
                control = true,
            ),
            before = live(identity),
            after = live(identity.copy(pageId = "pg-ffffffffffffffffffff")),
        )
        assertEquals("UNKNOWN", response.getString("code"))
        assertTrue(response.getJSONObject("data").getBoolean("taskOutcomeUnconfirmed"))
        val effect = store.snapshotEffects().first { it.requestId == "drift_begin_link" }
        assertEquals(BrowserEffectPhase.UNKNOWN, effect.phase)
        assertNotNull(effect.taskId)
        assertEquals(effect.taskId, store.task(effect.taskId!!)!!.taskId)
        assertEquals(BrowserTaskStatus.NEEDS_RECONCILIATION, store.task(effect.taskId!!)!!.status)
    }

    @Test
    fun cancelAndDenyControlPoliciesNeverAuthorizeTransfer() {
        val transfer = BrowserControlTransferPolicy()
        val pending = requireNotNull(transfer.begin(BrowserControlTransferTarget.HUMAN, 1L, 1L))
        assertTrue(transfer.cancel(pending.id))
        assertFalse(transfer.hasPending)
        assertNull(transfer.resolve(pending.id, confirmed = true, sessionGeneration = 1L, controlGeneration = 1L))

        val approvals = BrowserControlApprovalStore()
        val approvalId = "b".repeat(32)
        val started = approvals.start(
            approvalId, "nonce", "Handle blocked page", "task_host_01", identity, 100L, 1_000L,
        )
        assertTrue(started is BrowserControlApprovalStore.Start.Created)
        val denied = approvals.settle(
            approvalId, "nonce", BrowserControlApprovalStatus.DENIED, 101L,
        )
        assertEquals(BrowserControlApprovalStatus.DENIED, denied?.status)
        assertFalse(approvals.hasPending())
    }

    @Test
    fun recoveryMetadataFindIsBoundedAndCanResumeReflectsProductionState() {
        val now = runtime.elapsedRealtime()
        repeat(101) { index ->
            store.seedRecoveryTask(
                "recovery_${index.toString().padStart(3, '0')}",
                identity,
                now - index,
            )
        }
        val challenge = AtomicReference<BrowserRecoveryChallenge>()
        val error = AtomicReference<String?>()
        val prepared = CountDownLatch(1)
        coordinator.prepareRecoveryBinding(
            "recovery_100",
            live(identity),
            "https://local.test/",
        ) { value, failure ->
            challenge.set(value)
            error.set(failure)
            prepared.countDown()
        }
        assertTrue(prepared.await(2, TimeUnit.SECONDS))
        assertNull(error.get())
        assertNotNull(challenge.get())
        assertEquals("recovery_100", challenge.get()!!.taskId)
        assertEquals(0, challenge.get()!!.unknownEffectCount)
        assertFalse(runtime.shutdownCalled)
    }

    private fun execute(
        request: BrowserCommandRequest,
        context: BrowserTaskLiveContext?,
        liveContext: BrowserTaskLiveContext? = context,
    ): JSONObject {
        val response = AtomicReference<String>()
        val completed = CountDownLatch(1)
        coordinator.executeTask(request, context, { value ->
            response.set(value)
            completed.countDown()
        }, { liveContext })
        assertTrue("coordinator callback timed out", completed.await(2, TimeUnit.SECONDS))
        return JSONObject(response.get())
    }

    private fun executeChanging(
        request: BrowserCommandRequest,
        before: BrowserTaskLiveContext,
        after: BrowserTaskLiveContext,
    ): JSONObject {
        val response = AtomicReference<String>()
        val completed = CountDownLatch(1)
        var calls = 0
        coordinator.executeTask(request, before, { value ->
            response.set(value)
            completed.countDown()
        }, {
            calls += 1
            if (calls == 1) before else after
        })
        assertTrue("changing context callback timed out", completed.await(2, TimeUnit.SECONDS))
        return JSONObject(response.get())
    }

    private fun taskRequest(
        action: String,
        requestId: String,
        args: JSONObject,
        taskId: String? = null,
        context: BrowserTaskLiveContext? = null,
        control: Boolean = false,
    ): BrowserCommandRequest {
        val json = JSONObject()
            .put("version", 1)
            .put("requestId", requestId)
            .put("method", "task")
            .put("deadlineAtMillis", runtime.elapsedRealtime() + 10_000)
            .put("args", JSONObject(args.toString()).put("action", action))
        taskId?.let { json.getJSONObject("args").put("taskId", it) }
        if (control) {
            json.put("owner", "ai")
                .put("sessionGeneration", context?.identity?.sessionGeneration ?: 1L)
                .put("controlGeneration", context?.identity?.controlGeneration ?: 1L)
        }
        return BrowserCommandRequest.parse(json.toString(), runtime.elapsedRealtime()).getOrThrow()
    }

    private fun live(identity: BrowserTaskPageIdentity?) = BrowserTaskLiveContext(
        active = true,
        automationEnabled = true,
        ownerIsAi = true,
        identity = identity,
        state = "RUNNING",
    )

    private fun nodeId(controlGeneration: Long, index: Int): String =
        "b:1:$controlGeneration:1:1:${"a".repeat(32)}:$index"

    private fun BrowserTaskCoordinator.exhaustedCanResumeForTest(
        record: com.dshbox.browser.api.BrowserTaskRecord,
        context: BrowserTaskLiveContext,
        now: Long,
        boot: String,
    ): Boolean = BrowserTaskStateMachine.validateResume(
        record,
        record.revision,
        context.identity,
        context.automationEnabled,
        context.ownerIsAi,
        now,
        boot,
    ) == com.dshbox.browser.api.BrowserTaskTransition.Applied
}
