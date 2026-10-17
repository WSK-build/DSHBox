package com.dshbox.browser.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserLifecycleTest {
    private class FakeClock(var now: Long = 0) : BrowserClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun processRestartSeedRejectsPreviousSessionContext() {
        val before = BrowserLifecycleController(FakeClock(), initialSessionGeneration = 1000)
        val previous = before.open(BrowserOwner.AI).commandContext()
        val after = BrowserLifecycleController(FakeClock(), initialSessionGeneration = 2000)
        after.open(BrowserOwner.AI)
        assertNull(after.beginOperation("old-request", previous, 1000))
        assertFalse(after.recordEffectiveActivity(previous))
        assertTrue(after.snapshot().sessionGeneration > previous.sessionGeneration)
    }

    @Test
    fun hiddenSessionClosesAtIdleBoundaryAndVisibleSessionDoesNotClose() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open()
        assertEquals(BrowserHomeStatus.IDLE, controller.snapshot().homeStatus())
        clock.now = 10
        controller.setVisible(false)
        assertEquals(300_010L, BrowserIdlePolicy().closeDeadline(controller.snapshot()))
        clock.now = 300_009L
        assertEquals(BrowserLifecycle.ACTIVE, controller.expireIfNeeded().lifecycle)
        clock.now = 300_010L
        assertEquals(BrowserLifecycle.CLOSING, controller.expireIfNeeded().lifecycle)
        assertEquals(BrowserCloseReason.IDLE_TIMEOUT, controller.snapshot().closeReason)
        assertNull(controller.snapshot().homeStatus())
        assertEquals(BrowserLifecycle.CLOSED, controller.finishClose().lifecycle)
        assertEquals(BrowserCloseReason.IDLE_TIMEOUT, controller.snapshot().closeReason)

        controller.open()
        clock.now += 60_000L
        controller.setVisible(false)
        clock.now += 900_000L
        assertEquals(BrowserLifecycle.CLOSING, controller.setVisible(true).lifecycle)
        assertEquals(BrowserLifecycle.CLOSED, controller.finishClose().lifecycle)
    }

    @Test
    fun effectiveCompletionRenewsHiddenIdleDeadlineButQueriesDoNot() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open()
        controller.setVisible(false)
        clock.now = 100_000L
        val token = controller.beginOperation("read-1", controller.snapshot().commandContext(), 150_000L)!!
        assertEquals(BrowserHomeStatus.RUNNING, controller.snapshot().homeStatus())
        assertTrue(controller.completeOperation(token, effective = true))
        assertEquals(400_000L, BrowserIdlePolicy().closeDeadline(controller.snapshot()))
        repeat(4) { controller.snapshot().homeStatus() }
        assertEquals(100_000L, controller.snapshot().lastEffectiveOperationCompletedAtMillis)
    }

    @Test
    fun successfulAiReadRenewsIdleLeaseUntilReadsStopAndExpiredLeaseCannotBeRevived() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock, initialIdleTimeoutMillis = 60_000L)
        controller.open(BrowserOwner.AI)
        val aiContext = controller.snapshot().commandContext()
        controller.setVisible(false)

        clock.now = 50_000L
        assertEquals(10_000L, BrowserIdlePolicy(60_000L).remainingMillis(controller.snapshot(), clock.now))
        assertTrue(controller.recordReadActivity(aiContext))
        assertEquals(60_000L, BrowserIdlePolicy(60_000L).remainingMillis(controller.snapshot(), clock.now))

        clock.now = 100_000L
        assertTrue(controller.recordReadActivity(aiContext))
        assertEquals(60_000L, BrowserIdlePolicy(60_000L).remainingMillis(controller.snapshot(), clock.now))
        assertEquals(160_000L, BrowserIdlePolicy(60_000L).closeDeadline(controller.snapshot()))

        // Polling the snapshot is diagnostic only and does not keep the lease alive.
        repeat(5) { controller.snapshot() }
        clock.now = 159_999L
        assertEquals(1L, BrowserIdlePolicy(60_000L).remainingMillis(controller.snapshot(), clock.now))
        clock.now = 160_000L
        assertEquals(BrowserLifecycle.CLOSING, controller.expireIfNeeded().lifecycle)
        assertEquals(BrowserCloseReason.IDLE_TIMEOUT, controller.snapshot().closeReason)
        assertFalse(controller.recordReadActivity(aiContext))
        assertEquals(BrowserCloseReason.IDLE_TIMEOUT, controller.snapshot().closeReason)
        assertEquals(BrowserLifecycle.CLOSED, controller.finishClose().lifecycle)
        assertEquals(BrowserCloseReason.IDLE_TIMEOUT, controller.snapshot().closeReason)
    }

    @Test
    fun readActivityDoesNotRenewHumanOwnedSessionAndCloseReasonsAreStable() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock, initialIdleTimeoutMillis = 60_000L)
        controller.open(BrowserOwner.AI)
        controller.setVisible(false)
        controller.takeOver()
        val humanContext = controller.snapshot().commandContext()
        clock.now = 30_000L
        assertFalse(controller.recordReadActivity(humanContext))
        assertEquals(30_000L, BrowserIdlePolicy(60_000L).remainingMillis(controller.snapshot(), clock.now))

        controller.close(BrowserCloseReason.USER_CLOSE)
        assertEquals(BrowserLifecycle.CLOSED, controller.snapshot().lifecycle)
        assertEquals(BrowserCloseReason.USER_CLOSE, controller.snapshot().closeReason)
        controller.open(BrowserOwner.AI)
        assertEquals(BrowserCloseReason.UNKNOWN, controller.snapshot().closeReason)
        controller.close(BrowserCloseReason.AI_CLOSE)
        assertEquals(BrowserCloseReason.AI_CLOSE, controller.snapshot().closeReason)
    }

    @Test
    fun idleRemainingIsNullWhenVisibleAndZeroAfterDeadline() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock, initialIdleTimeoutMillis = 60_000L)
        controller.open(BrowserOwner.AI)
        val policy = BrowserIdlePolicy(60_000L)
        assertNull(policy.remainingMillis(controller.snapshot(), clock.now))
        controller.setVisible(false)
        assertEquals(60_000L, policy.remainingMillis(controller.snapshot(), clock.now))
        clock.now = 60_000L
        assertEquals(0L, policy.remainingMillis(controller.snapshot(), clock.now))
    }

    @Test
    fun operationDeadlineIsClampedAndTimeoutClearsInFlightBeforeIdleCheck() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open()
        controller.setVisible(false)
        clock.now = 299_000L
        val token = controller.beginOperation("slow", controller.snapshot().commandContext(), 900_000L)!!
        assertEquals(359_000L, token.deadlineAtMillis)
        clock.now = token.deadlineAtMillis
        assertEquals(BrowserLifecycle.CLOSING, controller.expireIfNeeded().lifecycle)
        assertNull(controller.snapshot().inFlightOperationId)
        assertFalse(controller.completeOperation(token, effective = true))
    }

    @Test
    fun takeoverAndCloseInvalidateOldCommandContextsAndOperationTokens() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open(BrowserOwner.AI)
        val oldContext = controller.snapshot().commandContext()
        val operation = controller.beginOperation("write", oldContext, 30_000L)!!
        val takenOver = controller.takeOver()
        assertEquals(BrowserOwner.HUMAN, takenOver.owner)
        assertEquals(BrowserHomeStatus.IDLE, takenOver.homeStatus())
        assertNull(controller.beginOperation("stale", oldContext, 30_000L))
        assertFalse(controller.completeOperation(operation, effective = true))

        val humanContext = takenOver.commandContext()
        controller.handToAi()
        assertNull(controller.beginOperation("stale-human", humanContext, 30_000L))
        val beforeClose = controller.snapshot()
        val closing = controller.beginClose()
        assertEquals(BrowserLifecycle.CLOSING, closing.lifecycle)
        assertEquals(beforeClose.sessionGeneration + 1, closing.sessionGeneration)
        assertEquals(beforeClose.controlGeneration + 1, closing.controlGeneration)
        assertNull(closing.homeStatus())
        assertEquals(BrowserLifecycle.CLOSING, controller.open().lifecycle)
        val closed = controller.finishClose()
        assertEquals(BrowserLifecycle.CLOSED, closed.lifecycle)
        assertEquals(BrowserLifecycle.CLOSED, controller.expireIfNeeded().lifecycle)
    }

    @Test
    fun rejectsWrongControlOwnerBusyExpiredAndClosedRequests() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        val context = controller.snapshot().commandContext()
        assertNull(controller.beginOperation("closed", context, 10L))
        controller.open(BrowserOwner.AI)
        assertNull(controller.beginOperation("wrong-owner", context, 10L))
        val aiContext = controller.snapshot().commandContext()
        assertNull(controller.beginOperation("expired", aiContext, 0L))
        val first = controller.beginOperation("first", aiContext, 30_000L)!!
        assertNull(controller.beginOperation("second", aiContext, 30_000L))
        assertTrue(controller.completeOperation(first, effective = false))
    }

    @Test
    fun reusedOperationIdCannotCompleteLaterOperation() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open(BrowserOwner.AI)
        val context = controller.snapshot().commandContext()
        val first = controller.beginOperation("same-id", context, 30_000L)!!
        assertTrue(controller.completeOperation(first, effective = false))
        clock.now = 1_000L
        val second = controller.beginOperation("same-id", context, 30_000L)!!
        assertTrue(second.operationGeneration > first.operationGeneration)
        assertFalse(controller.completeOperation(first, effective = true))
        assertEquals("same-id", controller.snapshot().inFlightOperationId)
        assertTrue(controller.completeOperation(second, effective = true))
    }

    @Test
    fun idleTimeoutAcceptsOneToFifteenMinutesAndShorteningCanExpireImmediately() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open()
        assertEquals(60_000L, controller.updateIdleTimeout(60_000L).idleTimeoutMillis)
        assertEquals(900_000L, controller.updateIdleTimeout(900_000L).idleTimeoutMillis)
        assertThrows(IllegalArgumentException::class.java) {
            controller.updateIdleTimeout(59_999L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            controller.updateIdleTimeout(900_001L)
        }

        val hiddenClock = FakeClock()
        val hiddenController = BrowserLifecycleController(hiddenClock)
        hiddenController.open()
        hiddenController.setVisible(false)
        hiddenClock.now = 120_000L
        assertEquals(BrowserLifecycle.CLOSING, hiddenController.updateIdleTimeout(120_000L).lifecycle)
    }
}
