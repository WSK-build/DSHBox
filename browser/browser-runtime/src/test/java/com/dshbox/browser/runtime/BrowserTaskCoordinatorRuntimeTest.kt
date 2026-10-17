package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserEffectJournalEntry
import com.dshbox.browser.api.BrowserTaskBudget
import com.dshbox.browser.api.BrowserTaskPageIdentity
import com.dshbox.browser.api.BrowserTaskRecord
import com.dshbox.browser.api.BrowserTaskRecoveryMetadata
import com.dshbox.browser.api.BrowserTaskStatus
import com.dshbox.browser.api.BrowserTaskWaitReason
import com.dshbox.browser.api.BrowserUserEffectChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BrowserTaskCoordinatorRuntimeTest {
    @Test
    fun resourceOpenOrderClosesStoreWhenDigestFails() {
        val order = mutableListOf<String>()
        val store = ClosingStore(order)
        val error = assertThrows(IllegalStateException::class.java) {
            openBrowserTaskCoordinatorResources(
                openStore = { order += "store"; store },
                openDigest = { order += "digest"; throw IllegalStateException("digest failed") },
                readBootId = { order += "boot"; "boot-host" },
            )
        }
        assertEquals("digest failed", error.message)
        assertEquals(listOf("boot", "store", "digest", "close"), order)
        assertTrue(store.closed)
    }

    @Test
    fun resourceOpenDoesNotCreateStoreBeforeBootFailure() {
        val order = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            openBrowserTaskCoordinatorResources(
                openStore = { order += "store"; ClosingStore(order) },
                openDigest = { order += "digest"; { _: ByteArray -> "" } },
                readBootId = { order += "boot"; throw IllegalStateException("boot failed") },
            )
        }
        assertEquals(listOf("boot"), order)
    }

    @Test
    fun boundedRecoverySearchFindsTargetBeyondFirstPageWithoutUnboundedScan() {
        val rows = (0 until 101).map { index ->
            RecoveryRow("task_${index.toString().padStart(3, '0')}")
        }
        var pagesRead = 0
        val found = findBoundedRecoveryMetadata(
            taskId = "task_100",
            maxTasks = 512,
            pageSize = 100,
            loadPage = { offset, limit ->
                pagesRead += 1
                rows.drop(offset).take(limit)
            },
            taskIdOf = { it.taskId },
        )
        assertEquals("task_100", found?.taskId)
        assertEquals(2, pagesRead)
        assertTrue(pagesRead * 100 <= 512)

        pagesRead = 0
        val missing = findBoundedRecoveryMetadata(
            taskId = "missing",
            maxTasks = 512,
            pageSize = 100,
            loadPage = { offset, limit ->
                pagesRead += 1
                rows.drop(offset).take(limit)
            },
            taskIdOf = { it.taskId },
        )
        assertNull(missing)
        assertTrue(pagesRead <= 6)
    }

    @Test
    fun coordinatorCloseClosesResourcesAndShutsRuntimeDown() {
        val store = ClosingStore(mutableListOf())
        val runtime = TestRuntime(store)
        val coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        assertTrue(awaitLatch(runtime.initialized))
        coordinator.close()
        assertTrue(awaitLatch(runtime.closed))
        assertTrue(awaitLatch(runtime.shutdown))
        assertTrue(store.closed)
        assertTrue(runtime.shutdownCalled)
        assertTrue(runtime.io.isShutdown)
        assertTrue(runtime.main.isShutdown)
    }

    @Test
    fun initializationFailureLeavesRuntimeRetryableUntilClose() {
        val store = ClosingStore(mutableListOf())
        val runtime = TestRuntime(store)
        runtime.failOpen = true
        val coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        assertTrue(awaitLatch(runtime.openFailed))
        assertFalse(coordinator.isReady())
        assertFalse(runtime.shutdownCalled)
        assertFalse(runtime.io.isShutdown)
        runtime.failOpen = false
        var initialized = false
        for (attempt in 0 until 20) {
            coordinator.initialize()
            if (runtime.initialized.await(100, TimeUnit.MILLISECONDS)) {
                initialized = true
                break
            }
        }
        assertTrue(initialized)
        assertTrue(coordinator.isReady())
        coordinator.close()
        assertTrue(awaitLatch(runtime.closed))
        assertTrue(awaitLatch(runtime.shutdown))
        assertTrue(runtime.shutdownCalled)
    }

    private fun awaitLatch(latch: CountDownLatch): Boolean = latch.await(2, TimeUnit.SECONDS)

    private data class RecoveryRow(val taskId: String)

    private class ClosingStore(private val order: MutableList<String>) : BrowserTaskCoordinatorStore {
        var closed = false
            private set

        override fun close() {
            closed = true
            order += "close"
        }

        override fun recoverAfterProcessStart(currentBootId: String, nowElapsedMillis: Long) = Unit
        override fun listRecoveryMetadata(
            currentBootId: String,
            nowElapsedMillis: Long,
            offset: Int,
            limit: Int,
        ): List<BrowserTaskRecoveryMetadata> = emptyList()
        override fun findRecoveryMetadata(
            taskId: String,
            currentBootId: String,
            nowElapsedMillis: Long,
        ): BrowserTaskRecoveryMetadata? = null
        override fun updateManagedTask(
            taskId: String,
            expectedRevision: Long,
            title: String?,
            goal: String?,
            notes: String?,
            nowWallMillis: Long,
        ) = BrowserTaskUpdateResult.NotFound
        override fun deleteManagedTask(taskId: String, expectedRevision: Long) = BrowserTaskDeleteResult.NotFound
        override fun readEventGate(
            taskId: String,
            expectedIdentity: BrowserTaskPageIdentity,
            expectedEpoch: Long?,
            allowDocumentAdvance: Boolean,
            parentEffectId: String?,
            parentIdentity: BrowserTaskPageIdentity?,
            currentBootId: String,
            nowElapsedMillis: Long,
        ): BrowserTaskEventGate? = null
        override fun recordEventDialogResolution(
            taskId: String,
            expectedRevision: Long,
            eventId: String,
            parentEffectId: String,
            identity: BrowserTaskPageIdentity,
            decision: String,
            promptHmac: String?,
            currentBootId: String,
            nowElapsedMillis: Long,
        ) = false
        override fun completeEventDialogResolution(eventId: String, resolved: Boolean) = false
        override fun consumeRecoveryBinding(
            taskId: String,
            expectedRevision: Long,
            expectedOldIdentity: BrowserTaskPageIdentity?,
            newIdentity: BrowserTaskPageIdentity,
            nonce: String,
            currentBootId: String,
            nowElapsedMillis: Long,
            challengeExpiresElapsedMillis: Long,
        ): BrowserTaskRecord? = null
        override fun invalidateRecoveryBinding(
            taskId: String,
            expectedRevision: Long,
            nowElapsedMillis: Long,
        ) = false
        override fun recordUserEffectVerification(
            taskId: String,
            expectedRevision: Long,
            effectId: String,
            observedIdentity: BrowserTaskPageIdentity,
            choice: BrowserUserEffectChoice,
            currentBootId: String,
            nowElapsedMillis: Long,
        ) = false
        override fun reserveEffect(entry: BrowserEffectJournalEntry) =
            BrowserEffectAdmission.TaskRejected("JOURNAL_UNAVAILABLE")
        override fun finishEffect(
            requestId: String,
            payloadHmacSha256: String,
            terminalCode: String?,
            executed: Boolean?,
            nowElapsedMillis: Long,
            taskId: String?,
        ) = false
        override fun readTask(taskId: String, currentBootId: String, nowElapsedMillis: Long): BrowserTaskRecord? = null
        override fun listTasks(
            currentBootId: String,
            nowElapsedMillis: Long,
            offset: Int,
            limit: Int,
        ): List<BrowserTaskRecord> = emptyList()
        override fun taskCount() = 0
        override fun hasUnresolvedEffects(taskId: String) = false
        override fun createTask(
            taskId: String,
            budget: BrowserTaskBudget,
            bootId: String,
            nowElapsedMillis: Long,
            pageIdentity: BrowserTaskPageIdentity?,
            title: String,
            goal: String,
            notes: String,
            nowWallMillis: Long,
        ) = BrowserTaskCreateResult.Full
        override fun transitionTask(
            taskId: String,
            expectedRevision: Long,
            target: BrowserTaskStatus,
            waitReason: BrowserTaskWaitReason?,
            observedIdentity: BrowserTaskPageIdentity?,
            automationEnabled: Boolean,
            ownerIsAi: Boolean,
            nowElapsedMillis: Long,
            currentBootId: String,
            failureReason: String?,
        ) = BrowserTaskUpdateResult.NotFound
        override fun reconcileTask(
            taskId: String,
            expectedRevision: Long,
            observedIdentity: BrowserTaskPageIdentity?,
            automationEnabled: Boolean,
            ownerIsAi: Boolean,
            nowElapsedMillis: Long,
            currentBootId: String,
        ) = BrowserTaskUpdateResult.NotFound
    }

    private class TestRuntime(
        private val store: BrowserTaskCoordinatorStore,
    ) : BrowserTaskCoordinatorRuntime {
        val io = Executors.newSingleThreadExecutor { task -> Thread(task, "test-task-io").apply { isDaemon = true } }
        val main = Executors.newSingleThreadExecutor { task -> Thread(task, "test-task-main").apply { isDaemon = true } }
        val initialized = CountDownLatch(1)
        val openFailed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val shutdown = CountDownLatch(1)
        @Volatile var failOpen = false
        @Volatile var shutdownCalled = false

        override fun openResources(): BrowserTaskCoordinatorResources {
            if (failOpen) {
                openFailed.countDown()
                throw IllegalStateException("open failed")
            }
            return BrowserTaskCoordinatorResources(store, { payload -> "a".repeat(64) }, "boot-host")
        }

        override fun postIo(task: () -> Unit) {
            io.execute {
                val shouldSignalInitialized = !failOpen
                task()
                if (shouldSignalInitialized) initialized.countDown()
            }
        }

        override fun postMain(task: () -> Unit) {
            main.execute(task)
        }

        override fun elapsedRealtime() = 1_000L
        override fun currentTimeMillis() = 2_000L
        override fun currentBootId() = "boot-host"
        override fun closeResources(resources: BrowserTaskCoordinatorResources) {
            resources.store.close()
            closed.countDown()
        }

        override fun shutdown() {
            shutdownCalled = true
            main.shutdown()
            io.shutdown()
            shutdown.countDown()
        }
    }
}
