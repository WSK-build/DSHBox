package com.dshbox.browser.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserCliRunSchedulerTest {
    @Test
    fun requestKeysAreScopedToRunIdentity() {
        val oldRun = BrowserCliRunIdentity(1, "old")
        val newRun = BrowserCliRunIdentity(2, "new")
        assertNotEquals(BrowserCliRequestKey(oldRun, "same"), BrowserCliRequestKey(newRun, "same"))
    }

    @Test
    fun finishingOldRunCompletesOnlyItsRequestsAndWakesDuplicates() {
        val registry = BrowserCliActiveRequestRegistry<java.util.concurrent.CompletableFuture<String>>()
        val oldRun = BrowserCliRunIdentity(1, "old")
        val newRun = BrowserCliRunIdentity(2, "new")
        val oldFuture = java.util.concurrent.CompletableFuture<String>()
        val newFuture = java.util.concurrent.CompletableFuture<String>()
        registry.register(BrowserCliRequestKey(oldRun, "same"), oldFuture)
        registry.register(BrowserCliRequestKey(newRun, "same"), newFuture)

        registry.finishRun(oldRun) { id, future -> future.complete("UNKNOWN:$id") }

        assertEquals("UNKNOWN:same", oldFuture.get(1, TimeUnit.SECONDS))
        assertFalse(newFuture.isDone)
        assertTrue(registry.contains(BrowserCliRequestKey(newRun, "same")))
    }

    @Test
    fun intakeReservationMakesQueuedRequestCancellableBeforeWorkerDispatch() {
        val scheduler = BrowserCliRunScheduler()
        val registry = BrowserCliActiveRequestRegistry<BrowserCliActiveRequest>()
        val run = BrowserCliRunIdentity(7, "run")
        val workerEntered = CountDownLatch(4)
        val releaseWorkers = CountDownLatch(1)
        val dispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        val queuedPeerReleased = CountDownLatch(1)
        val active = BrowserCliActiveRequest(run, "fingerprint", java.util.concurrent.CompletableFuture())
        active.queuedCancelAction.set { queuedPeerReleased.countDown() }
        try {
            repeat(4) {
                assertTrue(scheduler.executeCommand(run, Runnable {
                    workerEntered.countDown()
                    releaseWorkers.await(3, TimeUnit.SECONDS)
                }))
            }
            assertTrue(workerEntered.await(1, TimeUnit.SECONDS))

            val key = BrowserCliRequestKey(run, "request-1")
            assertEquals(null, registry.register(key, active))
            assertTrue(active.schedule(scheduler, Runnable {
                if (active.beginDispatch()) dispatched.set(true)
            }))

            assertEquals(BrowserCliCancelDisposition.CANCELLED_BEFORE_DISPATCH, active.requestCancel())
            active.result.complete("CANCELLED:request-1")
            active.finishQueuedCancellation()
            assertTrue(registry.remove(key, active))
            releaseWorkers.countDown()

            assertEquals("CANCELLED:request-1", active.result.get(1, TimeUnit.SECONDS))
            assertFalse("cancelled intake request reached command dispatch", dispatched.get())
            assertEquals(0L, queuedPeerReleased.count)
            assertFalse(registry.contains(key))
        } finally {
            releaseWorkers.countDown()
            scheduler.close()
        }
    }

    @Test
    fun stoppedRunQueuedCommandIsRemovedBeforeRestartWork() {
        val scheduler = BrowserCliRunScheduler()
        val oldRun = BrowserCliRunIdentity(1, "old")
        val newRun = BrowserCliRunIdentity(2, "new")
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val staleRan = java.util.concurrent.atomic.AtomicBoolean(false)
        val freshRan = CountDownLatch(1)
        try {
            repeat(4) {
                assertTrue(scheduler.executeCommand(oldRun, Runnable {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }))
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(scheduler.executeCommand(oldRun, Runnable { staleRan.set(true) }))
            scheduler.cancelQueuedCommands(oldRun)
            assertTrue(scheduler.executeCommand(newRun, Runnable { freshRan.countDown() }))
            release.countDown()
            assertTrue(freshRan.await(1, TimeUnit.SECONDS))
            assertFalse(staleRan.get())
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test
    fun cancellationLaneRunsWhileEveryCommandWorkerIsBlocked() {
        val scheduler = BrowserCliRunScheduler()
        val run = BrowserCliRunIdentity(1, "token")
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        try {
            repeat(4) {
                assertTrue(scheduler.executeCommand(run, Runnable {
                    entered.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }))
            }
            assertTrue("command workers did not all start", entered.await(1, TimeUnit.SECONDS))
            assertTrue(scheduler.executeCancellation(run, Runnable { cancelled.countDown() }))
            assertTrue("cancel waited behind command workers", cancelled.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test
    fun cancelWinningBeforeDispatchAdmissionSuppressesAlreadyDequeuedWork() {
        val scheduler = BrowserCliRunScheduler()
        val run = BrowserCliRunIdentity(8, "run")
        val dequeued = CountDownLatch(1)
        val actionFinished = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatched = java.util.concurrent.atomic.AtomicBoolean(false)
        val active = BrowserCliActiveRequest(run, "fingerprint", java.util.concurrent.CompletableFuture())
        try {
            assertTrue(active.schedule(scheduler, Runnable {
                dequeued.countDown()
                release.await(3, TimeUnit.SECONDS)
                if (active.beginDispatch()) dispatched.set(true)
                actionFinished.countDown()
            }))
            assertTrue(dequeued.await(1, TimeUnit.SECONDS))

            assertEquals(BrowserCliCancelDisposition.CANCELLED_BEFORE_DISPATCH, active.requestCancel())
            release.countDown()

            assertTrue(actionFinished.await(1, TimeUnit.SECONDS))
            assertFalse("dequeued request crossed dispatch admission after cancel", dispatched.get())
            assertTrue(active.wasCancelledBeforeDispatch())
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test
    fun duplicateCancelReturnsAlreadyHandledWithoutReenteringDispatch() {
        val scheduler = BrowserCliRunScheduler()
        val run = BrowserCliRunIdentity(9, "duplicate-run")
        val active = BrowserCliActiveRequest(run, "duplicate", java.util.concurrent.CompletableFuture())
        val dispatchCount = java.util.concurrent.atomic.AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            assertTrue(scheduler.executeCommand(run, Runnable {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                if (active.beginDispatch()) dispatchCount.incrementAndGet()
            }))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(BrowserCliCancelDisposition.CANCELLED_BEFORE_DISPATCH, active.requestCancel())
            assertEquals(BrowserCliCancelDisposition.ALREADY_HANDLED, active.requestCancel())
            assertTrue(active.wasCancelledBeforeDispatch())
            assertEquals(0, dispatchCount.get())
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test
    fun concurrentCancelAndAdmissionProduceExactlyOneTerminalDisposition() {
        val scheduler = BrowserCliRunScheduler()
        val run = BrowserCliRunIdentity(10, "race-run")
        val active = BrowserCliActiveRequest(run, "race", java.util.concurrent.CompletableFuture())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatchCount = java.util.concurrent.atomic.AtomicInteger()
        val start = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(
            mutableListOf<BrowserCliCancelDisposition?>(),
        )
        try {
            assertTrue(scheduler.executeCommand(run, Runnable {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                if (active.beginDispatch()) dispatchCount.incrementAndGet()
            }))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val first = Thread {
                start.await()
                results.add(active.requestCancel())
            }
            val second = Thread {
                start.await()
                results.add(active.requestCancel())
            }
            first.start(); second.start(); start.countDown()
            first.join(2_000); second.join(2_000)
            release.countDown()
            assertEquals(1, results.count { it == BrowserCliCancelDisposition.CANCELLED_BEFORE_DISPATCH })
            assertEquals(1, results.count { it == BrowserCliCancelDisposition.ALREADY_HANDLED })
            assertTrue(active.wasCancelledBeforeDispatch())
            assertEquals(0, dispatchCount.get())
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test
    fun finalCloseRejectsFurtherDispatch() {
        val scheduler = BrowserCliRunScheduler()
        scheduler.close()
        assertFalse(scheduler.executeCancellation(BrowserCliRunIdentity(1, "token"), Runnable {}))
        assertFalse(scheduler.executeCommand(BrowserCliRunIdentity(1, "token"), Runnable {}))
    }

    @Test
    fun cancellationLaneSaturationIsBoundedAndReportedToCaller() {
        val scheduler = BrowserCliRunScheduler()
        val run = BrowserCliRunIdentity(1, "token")
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        try {
            assertTrue(scheduler.executeCancellation(run, Runnable {
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(4) { assertTrue(scheduler.executeCancellation(run, Runnable {})) }
            assertFalse("bounded cancel queue accepted overflow work", scheduler.executeCancellation(run, Runnable {}))
        } finally {
            release.countDown()
            scheduler.close()
        }
    }
}
