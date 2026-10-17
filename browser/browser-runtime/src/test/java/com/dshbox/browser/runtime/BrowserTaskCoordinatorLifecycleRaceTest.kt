package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserEffectPhase
import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class BrowserTaskCoordinatorLifecycleRaceTest {
    private val identity = BrowserTaskPageIdentity(4, 9, "pg-0123456789abcdefabcd", 3)

    @Test
    fun initializationFailureCanRetryUntilCloseAndCloseCannotReviveIt() {
        val store = BrowserTaskCoordinatorHostStore()
        val runtime = LifecycleRuntime(store)
        val coordinator = BrowserTaskCoordinator(runtime)

        runtime.failNextOpen = true
        coordinator.initialize()
        assertTrue(runtime.releaseIo())
        assertFalse(coordinator.isReady())
        assertEquals(1, runtime.openAttempts)

        coordinator.initialize()
        assertTrue(runtime.releaseIo())
        assertTrue(coordinator.isReady())
        assertEquals(2, runtime.openAttempts)

        coordinator.close()
        assertTrue(runtime.releaseIo())
        assertTrue(runtime.shutdownCalled)
        assertTrue(runtime.closeResourcesCalls >= 1)

        coordinator.initialize()
        assertFalse(runtime.releaseIo())
        assertFalse(coordinator.isReady())
        assertEquals(2, runtime.openAttempts)
    }

    @Test
    fun closeDuringBlockedOpenClosesCandidateAndNeverPublishesReady() {
        val store = BrowserTaskCoordinatorHostStore()
        val runtime = LifecycleRuntime(store)
        runtime.blockNextOpen()
        val coordinator = BrowserTaskCoordinator(runtime)
        val opening = thread(name = "lifecycle-open") { coordinator.initialize(); runtime.releaseIo() }

        assertTrue(runtime.openEntered.await(2, TimeUnit.SECONDS))
        coordinator.close()
        runtime.openGate.countDown()
        opening.join(2_000)
        assertFalse(opening.isAlive)
        assertFalse(coordinator.isReady())
        assertTrue(runtime.releaseIo())
        assertTrue(runtime.shutdownCalled)
        assertTrue(runtime.closeResourcesCalls >= 1)
        assertEquals(1, runtime.closeResourcesCalls)

        coordinator.initialize()
        assertFalse(runtime.releaseIo())
        assertEquals(1, runtime.openAttempts)
    }

    @Test
    fun pendingPreAndPostCallbacksSurviveCloseWithoutDroppingMainReceipts() {
        val store = BrowserTaskCoordinatorHostStore()
        val runtime = LifecycleRuntime(store)
        val coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        assertTrue(runtime.releaseIo())
        assertTrue(coordinator.isReady())

        val admissionResult = mutableListOf<BrowserEffectAdmission>()
        var admissionDigest = ""
        coordinator.admitOperation(admissionRequest(), liveContext()) { admission, digest ->
            admissionResult.add(admission)
            admissionDigest = digest
        }
        assertTrue(runtime.releaseIo())
        coordinator.close()
        assertTrue(runtime.releaseIo())
        assertEquals(1, runtime.pendingMainCount())
        runtime.releaseMain()
        assertEquals(1, admissionResult.size)
        assertTrue(admissionResult.single() is BrowserEffectAdmission.NewIntent)
        assertEquals("a".repeat(64), admissionDigest)
        assertEquals(BrowserEffectPhase.INTENT, store.effect("effect_close_pre")!!.phase)
        assertTrue(runtime.shutdownCalled)

        val postStore = BrowserTaskCoordinatorHostStore()
        val postRuntime = LifecycleRuntime(postStore)
        val postCoordinator = BrowserTaskCoordinator(postRuntime)
        postCoordinator.initialize()
        assertTrue(postRuntime.releaseIo())
        val admitted = admit(postCoordinator, postRuntime, "effect_close_post")
        val response = JSONObject()
            .put("version", 1)
            .put("requestId", "effect_close_post")
            .put("code", "OK")
            .put("executed", true)
            .put("state", "RUNNING")
            .put("data", JSONObject())
            .toString()
        val terminal = mutableListOf<String>()
        postCoordinator.finishOperation("effect_close_post", admitted.second, response, callback = {
            terminal.add(it)
        })
        assertTrue(postRuntime.releaseIo())
        assertEquals(1, postRuntime.pendingMainCount())
        postCoordinator.close()
        assertTrue(postRuntime.releaseIo())
        assertEquals(1, postRuntime.pendingMainCount())
        postRuntime.releaseMain()
        assertEquals(1, terminal.size)
        assertEquals("OK", JSONObject(terminal.single()).getString("code"))
        assertEquals(BrowserEffectPhase.CONFIRMED, postStore.effect("effect_close_post")!!.phase)
        assertTrue(postRuntime.shutdownCalled)
    }

    @Test
    fun admittedFinishAfterCloseDeliversOneConservativeUnknown() {
        val store = BrowserTaskCoordinatorHostStore()
        val runtime = LifecycleRuntime(store)
        val coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        assertTrue(runtime.releaseIo())
        val admitted = admit(coordinator, runtime, "effect_close_unknown")
        val terminal = mutableListOf<String>()

        coordinator.close()
        coordinator.finishOperation("effect_close_unknown", admitted.second, validOkResponse("effect_close_unknown"), callback = {
            terminal.add(it)
        })
        assertTrue(runtime.releaseIo())
        assertEquals(1, runtime.pendingMainCount())
        runtime.releaseMain()

        assertEquals(1, terminal.size)
        val response = JSONObject(terminal.single())
        assertEquals("UNKNOWN", response.getString("code"))
        assertTrue(response.isNull("executed"))
        assertEquals("UNCONFIRMED", response.getJSONObject("data").getString("journalOutcome"))
        assertEquals(BrowserEffectPhase.INTENT, store.effect("effect_close_unknown")!!.phase)
    }

    @Test
    fun ioRejectionNeverRunsFinishTaskAndDeliversUnknownOnce() {
        val store = BrowserTaskCoordinatorHostStore()
        val runtime = LifecycleRuntime(store)
        val coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        assertTrue(runtime.releaseIo())
        val admitted = admit(coordinator, runtime, "effect_io_rejected")
        val terminal = mutableListOf<String>()

        runtime.rejectIo = true
        coordinator.finishOperation("effect_io_rejected", admitted.second, validOkResponse("effect_io_rejected"), callback = {
            terminal.add(it)
        })
        assertEquals(0, runtime.ioTaskCount())
        assertEquals(1, runtime.pendingMainCount())
        runtime.releaseMain()

        assertEquals(1, terminal.size)
        val response = JSONObject(terminal.single())
        assertEquals("UNKNOWN", response.getString("code"))
        assertTrue(response.isNull("executed"))
        assertEquals(BrowserEffectPhase.INTENT, store.effect("effect_io_rejected")!!.phase)
    }

    @Test
    fun deadMainDoesNotExecuteTaskOrInventCompletion() {
        val store = BrowserTaskCoordinatorHostStore()
        val runtime = LifecycleRuntime(store)
        val coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        assertTrue(runtime.releaseIo())
        runtime.rejectMain = true
        var callbackCount = 0

        coordinator.readRecoveryMetadata { callbackCount++ }
        assertTrue(runtime.releaseIo())

        assertEquals(0, callbackCount)
        assertEquals(1, runtime.mainRejectedPosts)
        assertEquals(0, runtime.pendingMainCount())
    }

    @Test
    fun rejectedCloseClosesResourcesAndPreservesQueuedMainCallback() {
        val store = BrowserTaskCoordinatorHostStore()
        val runtime = LifecycleRuntime(store)
        val coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        assertTrue(runtime.releaseIo())
        var rows = -1
        coordinator.readRecoveryMetadata { rows = it.size }
        assertTrue(runtime.releaseIo())
        assertEquals(1, runtime.pendingMainCount())

        runtime.rejectIo = true
        coordinator.close()
        coordinator.close()
        assertEquals(1, runtime.closeResourcesCalls)
        assertEquals(1, runtime.shutdownCalls)
        runtime.releaseMain()
        assertEquals(0, rows)

        coordinator.initialize()
        assertFalse(runtime.releaseIo())
        assertEquals(1, runtime.openAttempts)
    }

    @Test
    fun closeAfterIoDrainedWaitsForOldTaskBeforeClosingStore() {
        val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "resource-drain-io").apply { isDaemon = true }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        io.execute {
            entered.countDown()
            if (!release.await(2, TimeUnit.SECONDS)) throw AssertionError("IO task was not released")
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = thread(name = "resource-drain-close") {
            BrowserTaskCoordinatorResourceCloser.closeAfterIoDrained(io) {
                closed.incrementAndGet()
            }
        }
        try {
            assertTrue(waitUntil { io.isShutdown })
            assertEquals(0, closed.get())
            assertTrue(closer.isAlive)
        } finally {
            release.countDown()
            closer.join(2_000)
        }
        assertFalse(closer.isAlive)
        assertEquals(1, closed.get())
        assertTrue(io.isTerminated)
    }

    @Test
    fun interruptedDrainDoesNotCloseEarlyAndRestoresInterruptAfterRelease() {
        val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "interrupted-drain-io").apply { isDaemon = true }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicInteger()
        val interrupted = AtomicBoolean()
        io.execute {
            entered.countDown()
            if (!release.await(2, TimeUnit.SECONDS)) throw AssertionError("IO task was not released")
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = thread(name = "interrupted-drain-close") {
            BrowserTaskCoordinatorResourceCloser.closeAfterIoDrained(io) {
                closed.incrementAndGet()
            }
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        try {
            assertTrue(waitUntil { io.isShutdown })
            closer.interrupt()
            assertTrue(waitUntil { !closer.isInterrupted && closer.state == Thread.State.TIMED_WAITING })
            assertEquals(0, closed.get())
        } finally {
            release.countDown()
            closer.join(2_000)
        }
        assertFalse(closer.isAlive)
        assertEquals(1, closed.get())
        assertTrue(interrupted.get())
        assertTrue(io.isTerminated)
    }

    @Test
    fun closeStoreFailureStillRestoresRecordedInterruptAfterIoDrain() {
        val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "close-failure-io").apply { isDaemon = true }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeAttempts = AtomicInteger()
        val interrupted = AtomicBoolean()
        val failure = AtomicReference<Throwable?>()
        io.execute {
            entered.countDown()
            if (!release.await(2, TimeUnit.SECONDS)) throw AssertionError("IO task was not released")
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = thread(name = "close-failure-close") {
            failure.set(runCatching {
                BrowserTaskCoordinatorResourceCloser.closeAfterIoDrained(io) {
                    closeAttempts.incrementAndGet()
                    throw IllegalStateException("store close failed")
                }
            }.exceptionOrNull())
            interrupted.set(Thread.currentThread().isInterrupted)
        }
        try {
            assertTrue(waitUntil { io.isShutdown })
            closer.interrupt()
            assertTrue(waitUntil { !closer.isInterrupted && closer.state == Thread.State.TIMED_WAITING })
            assertEquals(0, closeAttempts.get())
        } finally {
            release.countDown()
            closer.join(2_000)
        }
        assertFalse(closer.isAlive)
        assertEquals(1, closeAttempts.get())
        assertTrue(failure.get() is IllegalStateException)
        assertEquals("store close failed", failure.get()?.message)
        assertTrue(interrupted.get())
        assertTrue(io.isTerminated)
    }

    private fun admit(
        coordinator: BrowserTaskCoordinator,
        runtime: LifecycleRuntime,
        requestId: String = "effect_close_pre",
    ): Pair<BrowserEffectAdmission, String> {
        var admission: BrowserEffectAdmission? = null
        var digest = ""
        val completed = CountDownLatch(1)
        coordinator.admitOperation(admissionRequest(requestId), liveContext()) { value, valueDigest ->
            admission = value
            digest = valueDigest
            completed.countDown()
        }
        assertTrue(runtime.releaseIo())
        assertTrue(runtime.releaseMain())
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertTrue(admission is BrowserEffectAdmission.NewIntent)
        return admission!! to digest
    }

    private fun admissionRequest(requestId: String = "effect_close_pre") = BrowserCommandRequest.parse(
        JSONObject()
            .put("version", 1)
            .put("requestId", requestId)
            .put("method", "click")
            .put("owner", "ai")
            .put("sessionGeneration", 4)
            .put("controlGeneration", 9)
            .put("deadlineAtMillis", System.currentTimeMillis() + 10_000L)
            .put("args", JSONObject().put("nodeId", "b:4:9:3:7:${"a".repeat(32)}:0"))
            .toString(),
        nowElapsedMillis = 1_000L,
    ).getOrThrow()

    private fun validOkResponse(requestId: String = "effect_close_unknown") = JSONObject()
        .put("version", 1)
        .put("requestId", requestId)
        .put("code", "OK")
        .put("executed", true)
        .put("state", "RUNNING")
        .put("data", JSONObject())
        .toString()

    private fun liveContext() = BrowserTaskLiveContext(
        active = true,
        automationEnabled = true,
        ownerIsAi = true,
        identity = identity,
        state = "RUNNING",
    )

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10L)
        }
        return condition()
    }

    private class LifecycleRuntime(
        private val store: BrowserTaskCoordinatorHostStore,
    ) : BrowserTaskCoordinatorRuntime {
        private val io = ArrayDeque<() -> Unit>()
        private val main = ArrayDeque<() -> Unit>()
        val openEntered = CountDownLatch(1)
        val openGate = CountDownLatch(1)
        @Volatile var failNextOpen = false
        @Volatile var rejectIo = false
        @Volatile var rejectMain = false
        @Volatile var openAttempts = 0
        @Volatile var closeResourcesCalls = 0
        @Volatile var shutdownCalls = 0
        @Volatile var mainRejectedPosts = 0
        @Volatile var shutdownCalled = false
        private var blockNextOpen = false

        fun blockNextOpen() {
            blockNextOpen = true
        }

        fun ioTaskCount(): Int = io.size
        fun pendingMainCount(): Int = main.size

        fun releaseIo(): Boolean {
            val task = io.poll() ?: return false
            task()
            return true
        }

        fun releaseMain(): Boolean {
            val task = main.poll() ?: return false
            task()
            return true
        }

        override fun openResources(): BrowserTaskCoordinatorResources {
            openAttempts++
            if (blockNextOpen) {
                blockNextOpen = false
                openEntered.countDown()
                if (!openGate.await(2, TimeUnit.SECONDS)) throw IllegalStateException("open gate timeout")
            }
            if (failNextOpen) {
                failNextOpen = false
                throw IllegalStateException("open failed")
            }
            return BrowserTaskCoordinatorResources(store, { "a".repeat(64) }, "boot-host")
        }

        override fun postIo(task: () -> Unit) {
            tryPostIo(task)
        }

        override fun postMain(task: () -> Unit) {
            tryPostMain(task)
        }

        override fun tryPostIo(task: () -> Unit): Boolean {
            if (rejectIo || shutdownCalled) return false
            io.add(task)
            return true
        }

        override fun tryPostMain(task: () -> Unit): Boolean {
            if (rejectMain) {
                mainRejectedPosts++
                return false
            }
            main.add(task)
            return true
        }

        override fun elapsedRealtime() = 1_000L
        override fun currentTimeMillis() = 2_000L
        override fun currentBootId() = "boot-host"

        override fun closeResources(resources: BrowserTaskCoordinatorResources) {
            closeResourcesCalls++
            resources.store.close()
        }

        override fun shutdown() {
            shutdownCalls++
            shutdownCalled = true
        }
    }
}
