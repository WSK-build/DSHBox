package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserClock
import com.dshbox.browser.api.BrowserHomeStatus
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOperationToken
import com.dshbox.browser.api.BrowserOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BrowserUploadOperationFinalizerTest {
    private class FakeClock(var now: Long = 0L) : BrowserClock {
        override fun nowMillis(): Long = now
    }

    @Test
    fun successfulUploadFinalizationAllowsNextClickAndUploadStatus() {
        val controller = activeController()
        val context = controller.snapshot().commandContext()
        val token = controller.beginOperation("upload-request", context, 10_000L)!!
        val finalizer = finalizer(controller, token)

        var tokenCompleted = false
        assertTrue(finalizer.finish(effective = true) { completed, _ -> tokenCompleted = completed })

        assertTrue(tokenCompleted)
        assertNull(controller.snapshot().inFlightOperationId)
        val click = controller.beginOperation("upload-click", context, 10_000L)
        assertTrue(click != null)
        assertTrue(controller.completeOperation(click!!, effective = true))
        val status = controller.beginOperation("upload-status", context, 10_000L)
        assertTrue(status != null)
        assertTrue(controller.completeOperation(status!!, effective = false))
    }

    @Test
    fun failedUploadFinalizationReleasesTheMatchingOperation() {
        val controller = activeController()
        val context = controller.snapshot().commandContext()
        val token = controller.beginOperation("upload-failed", context, 10_000L)!!
        val finalizer = finalizer(controller, token)

        var tokenCompleted = false
        assertTrue(finalizer.finish(effective = false) { completed, _ -> tokenCompleted = completed })

        assertTrue(tokenCompleted)
        assertNull(controller.snapshot().inFlightOperationId)
        assertEquals(BrowserHomeStatus.IDLE, controller.snapshot().homeStatus())
    }

    @Test
    fun duplicateAndLateCallbacksCannotReplyTwiceOrClearANewerOperation() {
        val controller = activeController()
        val context = controller.snapshot().commandContext()
        val oldToken = controller.beginOperation("upload-old", context, 10_000L)!!
        val finalizer = finalizer(controller, oldToken)
        val callbacks = AtomicInteger()
        val staleCallbacks = AtomicInteger()

        assertTrue(finalizer.finish(effective = false) { _, _ -> callbacks.incrementAndGet() })
        val currentToken = controller.beginOperation("new-click", context, 10_000L)!!
        assertFalse(finalizer.finish(effective = false) { _, _ -> callbacks.incrementAndGet() })
        assertEquals(currentToken.operationId, controller.snapshot().inFlightOperationId)

        val staleFinalizer = finalizer(controller, currentToken)
        assertTrue(controller.completeOperation(currentToken, effective = false))
        val newerToken = controller.beginOperation("new-status", context, 10_000L)!!
        assertTrue(staleFinalizer.finish(effective = false) { _, _ -> staleCallbacks.incrementAndGet() })
        assertEquals(newerToken.operationId, controller.snapshot().inFlightOperationId)
        assertEquals(1, callbacks.get())
        assertEquals(1, staleCallbacks.get())
    }

    @Test
    fun expiredSuccessIsUnknownAndDoesNotScheduleAnAutomaticReplay() {
        val clock = FakeClock()
        val controller = activeController(clock)
        val token = controller.beginOperation("upload-timeout", controller.snapshot().commandContext(), 1_000L)!!
        val finalizer = finalizer(controller, token)
        val callbacks = AtomicInteger()
        clock.now = 1_000L

        var completed = true
        assertTrue(finalizer.finish(effective = false) { tokenCompleted, _ ->
            callbacks.incrementAndGet()
            completed = tokenCompleted
        })
        val (code, executed) = browserUploadOutcome("OK", true, authorized = false, tokenCompleted = completed)

        assertFalse(completed)
        assertEquals("UNKNOWN", code)
        assertNull(executed)
        val (failureCode, failureExecuted) = browserUploadOutcome(
            "UPLOAD_SOURCE_DENIED", false, authorized = false, tokenCompleted = false,
        )
        assertEquals("UPLOAD_SOURCE_DENIED", failureCode)
        assertEquals(false, failureExecuted)
        assertFalse(finalizer.finish(effective = false) { _, _ -> callbacks.incrementAndGet() })
        assertEquals(1, callbacks.get())
        assertNull(controller.snapshot().inFlightOperationId)
    }

    private fun activeController(clock: FakeClock = FakeClock()): BrowserLifecycleController =
        BrowserLifecycleController(clock).apply { open(BrowserOwner.AI) }

    private fun finalizer(controller: BrowserLifecycleController, token: BrowserOperationToken) =
        BrowserUploadOperationFinalizer(
            token = token,
            completeOperation = controller::completeOperation,
            snapshot = controller::snapshot,
            publishSnapshot = {},
        )
}
