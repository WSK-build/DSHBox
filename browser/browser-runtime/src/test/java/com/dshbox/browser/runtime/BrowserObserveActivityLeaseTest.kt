package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserClock
import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserObserveActivityLeaseTest {
    private class FakeClock(var now: Long = 0L) : BrowserClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun freshAiObservationsKeepHiddenSessionAliveAndStoppingThemLetsIdleClose() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open(BrowserOwner.AI)
        controller.setVisible(false)

        repeat(3) {
            clock.now += 240_000L
            val initial = controller.snapshot()
            assertTrue(recordSuccessfulObserveActivity(
                controller, initial, controller.snapshot(),
                automationEnabled = true, successfulDom = true, sameDocument = true,
                deadlineAtElapsedMillis = clock.now + 1L,
                nowElapsedMillis = clock.now,
            ))
            assertEquals(BrowserLifecycle.ACTIVE, controller.expireIfNeeded().lifecycle)
        }

        clock.now += 299_999L
        assertEquals(BrowserLifecycle.ACTIVE, controller.expireIfNeeded().lifecycle)
        clock.now += 1L
        assertEquals(BrowserLifecycle.CLOSING, controller.expireIfNeeded().lifecycle)
    }

    @Test
    fun humanOwnerAndStaleDocumentCannotRenewHiddenSession() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open(BrowserOwner.AI)
        controller.setVisible(false)
        val oldAi = controller.snapshot()
        val human = controller.takeOver()
        clock.now = 240_000L
        assertFalse(recordSuccessfulObserveActivity(
            controller, oldAi, human,
            automationEnabled = true, successfulDom = true, sameDocument = true,
            deadlineAtElapsedMillis = 300_001L,
            nowElapsedMillis = clock.now,
        ))
        clock.now = 300_000L
        assertEquals(BrowserLifecycle.CLOSING, controller.expireIfNeeded().lifecycle)

        val secondClock = FakeClock()
        val second = BrowserLifecycleController(secondClock)
        second.open(BrowserOwner.AI)
        second.setVisible(false)
        val ai = second.snapshot()
        secondClock.now = 240_000L
        assertFalse(recordSuccessfulObserveActivity(
            second, ai, second.snapshot(),
            automationEnabled = true, successfulDom = true, sameDocument = false,
            deadlineAtElapsedMillis = 240_001L,
            nowElapsedMillis = secondClock.now,
        ))
        secondClock.now = 300_000L
        assertEquals(BrowserLifecycle.CLOSING, second.expireIfNeeded().lifecycle)
    }

    @Test
    fun failedOrDisabledObservationDoesNotRecordEffectiveActivity() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open(BrowserOwner.AI)
        controller.setVisible(false)
        val snapshot = controller.snapshot()
        val lastEffectiveBefore = snapshot.lastEffectiveOperationCompletedAtMillis
        clock.now = 100_000L

        assertFalse(recordSuccessfulObserveActivity(
            controller, snapshot, controller.snapshot(),
            automationEnabled = false, successfulDom = true, sameDocument = true,
            deadlineAtElapsedMillis = 100_001L,
            nowElapsedMillis = clock.now,
        ))
        assertFalse(recordSuccessfulObserveActivity(
            controller, snapshot, controller.snapshot(),
            automationEnabled = true, successfulDom = false, sameDocument = true,
            deadlineAtElapsedMillis = 100_001L,
            nowElapsedMillis = clock.now,
        ))
        assertEquals(lastEffectiveBefore, controller.snapshot().lastEffectiveOperationCompletedAtMillis)
    }

    @Test
    fun successfulDomCallbackAfterCommandDeadlineDoesNotRenewLease() {
        val clock = FakeClock()
        val controller = BrowserLifecycleController(clock)
        controller.open(BrowserOwner.AI)
        controller.setVisible(false)
        val initial = controller.snapshot()
        val lastEffectiveBefore = initial.lastEffectiveOperationCompletedAtMillis

        // The command's deadline has elapsed, while the five-minute hidden idle lease is fresh.
        clock.now = 1_000L
        assertFalse(recordSuccessfulObserveActivity(
            lifecycle = controller,
            initial = initial,
            current = controller.snapshot(),
            automationEnabled = true,
            successfulDom = true,
            sameDocument = true,
            deadlineAtElapsedMillis = 500L,
            nowElapsedMillis = clock.now,
        ))
        assertEquals(lastEffectiveBefore, controller.snapshot().lastEffectiveOperationCompletedAtMillis)
        assertEquals(BrowserLifecycle.ACTIVE, controller.snapshot().lifecycle)
    }
}
