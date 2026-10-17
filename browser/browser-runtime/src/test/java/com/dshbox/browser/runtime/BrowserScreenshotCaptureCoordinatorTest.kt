package com.dshbox.browser.runtime

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class BrowserScreenshotCaptureCoordinatorTest {
    @Test fun fallbackRestoreFailureBlocksCaptureAndReparentUntilVerifiedRecovery() {
        var recovered = false
        val primary = ImmediateSource<String>(BrowserScreenshotCaptureResult(null, "NO_FRAME"))
        val fallback = DeferredSource<String>()
        val coordinator = BrowserScreenshotCaptureCoordinator(
            primary, fallback, { true }, recoverPrimaryRestore = { recovered },
        )
        coordinator.capture(10_000) {}
        fallback.emit(BrowserScreenshotCaptureResult(null, "RESTORE_FAILED"))
        assertFalse(coordinator.cancelActiveForReparent())
        var rejection: String? = null
        coordinator.capture(10_000) { rejection = it.code }
        assertEquals("RESTORE_FAILED", rejection)
        assertEquals(1, fallback.captureCount)
        recovered = true
        assertTrue(coordinator.cancelActiveForReparent())
        coordinator.capture(10_000) {}
        assertEquals(2, fallback.captureCount)
        coordinator.close()
    }

    @Test fun verifiedRestoreRecoveryUnblocksReparentWithoutStartingAnotherCapture() {
        var restored = false
        val primary = DeferredSource<String>(handle = object : BrowserScreenshotCaptureHandle {
            override fun cancel() = true
            override fun cancelAndRestore() = false
        })
        val coordinator = BrowserScreenshotCaptureCoordinator(
            primary, DeferredSource(), { true }, recoverPrimaryRestore = { restored },
        )
        coordinator.capture(10_000) { }
        assertFalse(coordinator.cancelActiveForReparent())
        assertFalse(coordinator.cancelActiveForReparent())
        restored = true
        assertTrue(coordinator.cancelActiveForReparent())
        assertEquals(1, primary.captureCount)
    }

    @Test fun productionFailureDataMatchesSharedCliClientFixture() {
        val response = JSONObject().put("version", 1).put("requestId", "capture-review")
            .put("code", "PIXEL_READ_FAILED").put("executed", false).put("state", "IDLE")
            .put("sessionGeneration", 4).put("controlGeneration", 7).put("owner", "ai")
            .put("data", BrowserScreenshotCaptureContract.failureData(
                "PIXEL_READ_FAILED",
                BrowserScreenshotOutcome(
                    attempts = listOf(BrowserScreenshotAttempt("virtual_display", "PIXEL_READ_FAILED")),
                    debugEnabled = false,
                    userActionRequired = false,
                ),
            ))
        assertEquals(JSONObject(PRODUCTION_FAILURE_SAMPLE_JSON).toString(), response.toString())
    }

    @Test fun synchronousPrimaryFailureStartsFallbackAndKeepsFallbackHandle() {
        val fallback = DeferredSource<String>()
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val coordinator = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult(null, "NO_FRAME")), fallback, { true },
        )
        val handle = coordinator.capture(10_000) { terminal = it }
        assertTrue(fallback.started)
        assertTrue(handle.cancel())
        assertEquals("CANCELLED", terminal?.code)
        assertEquals(1, fallback.cancelCount)
    }

    @Test fun cancelAndRestoreCompletesPrimaryRestoreBeforeTerminalCallback() {
        var restored = false
        var callbackSawRestored = false
        var callbackCount = 0
        val primary = DeferredSource<String>(handle = object : BrowserScreenshotCaptureHandle {
            override fun cancel() = true
            override fun cancelAndRestore(): Boolean {
                restored = true
                return true
            }
        })
        val coordinator = BrowserScreenshotCaptureCoordinator(primary, DeferredSource(), { true })
        val handle = coordinator.capture(10_000) {
            callbackCount++
            callbackSawRestored = restored
            assertEquals("CANCELLED", it.code)
        }

        assertTrue(handle.cancelAndRestore())
        assertFalse(handle.cancelAndRestore())
        assertTrue(callbackSawRestored)
        assertEquals(1, callbackCount)
    }

    @Test fun cancelAndRestorePropagatesPrimaryRestoreFailureExactlyOnce() {
        var callbackCount = 0
        val primary = DeferredSource<String>(handle = object : BrowserScreenshotCaptureHandle {
            override fun cancel() = true
            override fun cancelAndRestore() = false
        })
        val coordinator = BrowserScreenshotCaptureCoordinator(primary, DeferredSource(), { true })
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val handle = coordinator.capture(10_000) {
            callbackCount++
            terminal = it
        }

        assertFalse(handle.cancelAndRestore())
        assertEquals(BrowserScreenshotCaptureContract.RESTORE_FAILED, terminal?.code)
        assertFalse(handle.cancelAndRestore())
        assertEquals(1, callbackCount)
    }

    @Test fun reparentCancellationReportsRestoreOutcomeAndNoActiveCaptureIsSafe() {
        var restoreAttempts = 0
        val primary = DeferredSource<String>(handle = object : BrowserScreenshotCaptureHandle {
            override fun cancel() = true
            override fun cancelAndRestore(): Boolean {
                restoreAttempts++
                return false
            }
        })
        val coordinator = BrowserScreenshotCaptureCoordinator(primary, DeferredSource(), { true })
        var result: BrowserScreenshotCaptureResult<String>? = null
        coordinator.capture(10_000) { result = it }

        assertFalse(coordinator.cancelActiveForReparent())
        assertEquals(1, restoreAttempts)
        assertEquals(BrowserScreenshotCaptureContract.RESTORE_FAILED, result?.code)
        assertFalse(coordinator.cancelActiveForReparent())
        assertEquals(1, restoreAttempts)
        var rejected: BrowserScreenshotCaptureResult<String>? = null
        coordinator.capture(10_000) { rejected = it }
        assertEquals(BrowserScreenshotCaptureContract.RESTORE_FAILED, rejected?.code)
        assertEquals(1, primary.captureCount)
    }

    @Test fun throwingCancellationCallbackDoesNotBreakRestoreResultOrRepeatTerminal() {
        var callbackCount = 0
        val primary = DeferredSource<String>(handle = object : BrowserScreenshotCaptureHandle {
            override fun cancel() = true
            override fun cancelAndRestore() = true
        })
        val coordinator = BrowserScreenshotCaptureCoordinator(primary, DeferredSource(), { true })
        coordinator.capture(10_000) {
            callbackCount++
            throw IllegalStateException("consumer failed")
        }

        assertTrue(coordinator.cancelActiveForReparent())
        assertEquals(1, callbackCount)
        assertTrue(coordinator.cancelActiveForReparent())
        assertEquals(1, callbackCount)
    }

    @Test fun cancelAndRestoreOnFallbackCancelsWithoutReportingPrimaryRestoreFailure() {
        val fallback = DeferredSource<String>()
        val coordinator = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult(null, "NO_FRAME")), fallback, { true },
        )
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val handle = coordinator.capture(10_000) { terminal = it }

        assertTrue(handle.cancelAndRestore())
        assertEquals("CANCELLED", terminal?.code)
        assertEquals(1, fallback.cancelCount)
    }

    @Test fun primarySuccessDoesNotStartFallback() {
        val fallback = DeferredSource<String>()
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val coordinator = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult("pixels", "OK")), fallback, { true },
        )
        coordinator.capture(10_000) { terminal = it }
        assertEquals("pixels", terminal?.value)
        assertFalse(fallback.started)
    }

    @Test fun fallbackRequiresDebugAndRetainsPrimaryFailure() {
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val coordinator = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult(null, "NO_FRAME")),
            ImmediateSource(BrowserScreenshotCaptureResult("cdp", "OK")), { true },
        )
        coordinator.capture(10_000) { terminal = it }
        assertEquals("cdp", terminal?.value)
        assertEquals("NO_FRAME", terminal?.outcome?.attempts?.firstOrNull()?.code)
    }

    @Test fun restoreFailureDoesNotInvokeCdpAndDebugIsRecheckedBeforeFallback() {
        var fallbackStarted = false
        val restoreFailure = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult<String>(null, BrowserScreenshotCaptureContract.RESTORE_FAILED)),
            BrowserScreenshotCaptureSource { _, callback ->
                fallbackStarted = true
                callback(BrowserScreenshotCaptureResult("cdp", "OK"))
                BrowserScreenshotCaptureHandle { false }
            }, { true },
        )
        var failed: BrowserScreenshotCaptureResult<String>? = null
        restoreFailure.capture(10_000) { failed = it }
        assertEquals(BrowserScreenshotCaptureContract.RESTORE_FAILED, failed?.code)
        assertFalse(fallbackStarted)
        assertFalse(restoreFailure.cancelActiveForReparent())

        var enabled = true
        val revoked = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult<String>(null, "NO_FRAME")),
            BrowserScreenshotCaptureSource { _, callback ->
                fallbackStarted = true
                callback(BrowserScreenshotCaptureResult("should-not-run", "OK"))
                BrowserScreenshotCaptureHandle { false }
            }, { enabled.also { enabled = false } },
        )
        var denied: BrowserScreenshotCaptureResult<String>? = null
        revoked.capture(10_000) { denied = it }
        assertEquals(BrowserScreenshotCaptureContract.DEBUG_REQUIRED, denied?.code)
        assertFalse(fallbackStarted)
    }

    @Test fun failedCaptureValuesAreDiscardedAndOkWithoutValueIsNormalized() {
        val discarded = mutableListOf<String>()
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val coordinator = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult("partial", "NO_FRAME")),
            ImmediateSource(BrowserScreenshotCaptureResult<String>(null, "OK")),
            { true }, discarded::add,
        )
        coordinator.capture(10_000) { terminal = it }
        assertEquals(listOf("partial"), discarded)
        assertEquals("CAPTURE_EMPTY_RESULT", terminal?.code)
        assertNull(terminal?.value)
    }

    @Test fun revokedDebugAfterFallbackCompletesDiscardsValueAndReportsActionRequired() {
        var reads = 0
        val discarded = mutableListOf<String>()
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val coordinator = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult<String>(null, "NO_FRAME")),
            ImmediateSource(BrowserScreenshotCaptureResult("cdp-image", "OK")),
            { ++reads < 3 }, discarded::add,
        )
        coordinator.capture(10_000) { terminal = it }
        assertEquals(listOf("cdp-image"), discarded)
        assertEquals(BrowserScreenshotCaptureContract.DEBUG_REQUIRED, terminal?.code)
        assertTrue(terminal?.outcome?.userActionRequired == true)
        assertEquals(false, terminal?.outcome?.debugEnabled)
    }

    @Test fun revokedDebugDiscardsFailedFallbackValueExactlyOnceAndAttemptsUseNormalizedCodes() {
        var reads = 0
        var discardCount = 0
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val coordinator = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult<String>(null, "NO_FRAME")),
            ImmediateSource(BrowserScreenshotCaptureResult("partial-cdp", "PIXEL_READ_FAILED")),
            { ++reads < 3 }, { discardCount++ },
        )
        coordinator.capture(10_000) { terminal = it }
        assertEquals(1, discardCount)
        assertEquals(BrowserScreenshotCaptureContract.DEBUG_REQUIRED, terminal?.code)
        assertTrue(terminal?.outcome?.userActionRequired == true)
        assertEquals(listOf("NO_FRAME", "PIXEL_READ_FAILED"), terminal?.outcome?.attempts?.map { it.code })

        var emptyTerminal: BrowserScreenshotCaptureResult<String>? = null
        BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult<String>(null, "OK")),
            DeferredSource(), { false },
        ).capture(10_000) { emptyTerminal = it }
        assertEquals("CAPTURE_EMPTY_RESULT", emptyTerminal?.code)
        assertEquals("CAPTURE_EMPTY_RESULT", emptyTerminal?.outcome?.attempts?.singleOrNull()?.code)
    }

    @Test fun repeatedSourceCallbackDiscardsSecondValueAndTerminalCallbackCanThrowOnce() {
        val discarded = mutableListOf<String>()
        var sourceCallback: ((BrowserScreenshotCaptureResult<String>) -> Unit)? = null
        val repeated = BrowserScreenshotCaptureSource<String> { _, callback ->
            sourceCallback = callback
            callback(BrowserScreenshotCaptureResult("first", "OK"))
            BrowserScreenshotCaptureHandle { false }
        }
        val coordinator = BrowserScreenshotCaptureCoordinator(
            repeated, DeferredSource(), { false }, discarded::add,
        )
        var deliveries = 0
        assertThrows(IllegalStateException::class.java) {
            coordinator.capture(10_000) { deliveries++; error("consumer failed") }
        }
        sourceCallback?.invoke(BrowserScreenshotCaptureResult("late", "OK"))
        assertEquals(1, deliveries)
        assertEquals(listOf("late"), discarded)

        var secondCapture: BrowserScreenshotCaptureResult<String>? = null
        val reusable = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult("next", "OK")), DeferredSource(), { false }, discarded::add,
        )
        assertThrows(IllegalStateException::class.java) {
            reusable.capture(10_000) { throw IllegalStateException("terminal callback") }
        }
        reusable.capture(10_000) { secondCapture = it }
        assertEquals("next", secondCapture?.value)
    }

    @Test fun busyAndCloseProduceOneTerminalAndLateValuesAreDiscarded() {
        val primary = DeferredSource<String>()
        val coordinator = BrowserScreenshotCaptureCoordinator(
            primary, DeferredSource(), { false },
        )
        var firstCount = 0
        var second: BrowserScreenshotCaptureResult<String>? = null
        coordinator.capture(10_000) { firstCount++ }
        coordinator.capture(10_000) { second = it }
        assertEquals(BrowserScreenshotCaptureContract.CAPTURE_BUSY, second?.code)
        coordinator.close()
        assertEquals(1, firstCount)
        primary.emit(BrowserScreenshotCaptureResult("late", "OK"))
        assertEquals(1, firstCount)
    }

    @Test fun disabledDebugReturnsActionRequiredAndCancelBeforeHandleInstallIsTerminal() {
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val coordinator = BrowserScreenshotCaptureCoordinator(
            ImmediateSource(BrowserScreenshotCaptureResult<String>(null, "NO_FRAME")), DeferredSource<String>(), { false },
        )
        coordinator.capture(10_000) { terminal = it }
        assertEquals(BrowserScreenshotCaptureContract.DEBUG_REQUIRED, terminal?.code)
        assertTrue(terminal?.outcome?.userActionRequired == true)
    }

    @Test fun handleReturnedAfterFallbackCompletionIsCancelledAndCannotReplaceFallback() {
        val callbackStarted = CountDownLatch(1)
        val allowPrimaryReturn = CountDownLatch(1)
        val primaryHandleCancelled = CountDownLatch(1)
        var primaryCancelCount = 0
        val primary = BrowserScreenshotCaptureSource<String> { _, callback ->
            Thread {
                callback(BrowserScreenshotCaptureResult(null, "NO_FRAME"))
                callbackStarted.countDown()
                allowPrimaryReturn.await(2, TimeUnit.SECONDS)
            }.start()
            callbackStarted.await(2, TimeUnit.SECONDS)
            BrowserScreenshotCaptureHandle { primaryCancelCount++; primaryHandleCancelled.countDown(); true }
        }
        val fallback = ImmediateSource(BrowserScreenshotCaptureResult("cdp", "OK"))
        var terminal: BrowserScreenshotCaptureResult<String>? = null
        val coordinator = BrowserScreenshotCaptureCoordinator(primary, fallback, { true })
        coordinator.capture(10_000) { terminal = it }
        assertEquals("cdp", terminal?.value)
        allowPrimaryReturn.countDown()
        assertTrue(primaryHandleCancelled.await(2, TimeUnit.SECONDS))
        assertEquals(1, primaryCancelCount)
    }

    private class ImmediateSource<T>(private val result: BrowserScreenshotCaptureResult<T>) : BrowserScreenshotCaptureSource<T> {
        override fun capture(deadlineElapsedMillis: Long, callback: (BrowserScreenshotCaptureResult<T>) -> Unit) =
            BrowserScreenshotCaptureHandle { false }.also { callback(result) }
    }

    private class DeferredSource<T>(
        private val handle: BrowserScreenshotCaptureHandle? = null,
    ) : BrowserScreenshotCaptureSource<T> {
        private var callback: ((BrowserScreenshotCaptureResult<T>) -> Unit)? = null
        var started = false
        var captureCount = 0
        var cancelCount = 0
        override fun capture(deadlineElapsedMillis: Long, callback: (BrowserScreenshotCaptureResult<T>) -> Unit): BrowserScreenshotCaptureHandle {
            started = true
            captureCount++
            this.callback = callback
            return handle ?: BrowserScreenshotCaptureHandle { cancelCount++; true }
        }
        fun emit(result: BrowserScreenshotCaptureResult<T>) { callback?.invoke(result) }
    }
}

internal const val PRODUCTION_FAILURE_SAMPLE_JSON = """{"version":1,"requestId":"capture-review","code":"PIXEL_READ_FAILED","executed":false,"state":"IDLE","sessionGeneration":4,"controlGeneration":7,"owner":"ai","data":{"reason":"PIXEL_READ_FAILED","cancelStage":"pixel_read","primaryBackend":"virtual_display","primaryFailure":"PIXEL_READ_FAILED","debugEnabled":false,"userActionRequired":false}}"""
