package com.dshbox.browser.runtime

import java.util.concurrent.atomic.AtomicBoolean

internal class BrowserScreenshotCaptureCoordinator<T>(
    private val primary: BrowserScreenshotCaptureSource<T>,
    private val fallback: BrowserScreenshotCaptureSource<T>,
    private val debugEnabled: () -> Boolean,
    private val discardValue: (T) -> Unit = { value ->
        @Suppress("UNCHECKED_CAST")
        (value as? android.graphics.Bitmap)?.recycle()
    },
    private val recoverPrimaryRestore: () -> Boolean = { false },
) : BrowserScreenshotCaptureSource<T>, AutoCloseable {
    private val activeLock = Any()
    private var active: Session? = null
    private val restorationBlocked = AtomicBoolean(false)
    @Volatile private var closed = false

    override fun capture(
        deadlineElapsedMillis: Long,
        callback: (BrowserScreenshotCaptureResult<T>) -> Unit,
    ): BrowserScreenshotCaptureHandle {
        val session = Session(deadlineElapsedMillis, callback)
        val rejection = synchronized(activeLock) {
            when {
                closed -> "CLOSED"
                restorationBlocked.get() -> BrowserScreenshotCaptureContract.RESTORE_FAILED
                active != null -> BrowserScreenshotCaptureContract.CAPTURE_BUSY
                else -> { active = session; null }
            }
        }
        if (rejection != null) {
            callback(BrowserScreenshotCaptureResult(null, rejection))
            return BrowserScreenshotCaptureHandle { false }
        }
        session.startPrimary()
        return object : BrowserScreenshotCaptureHandle {
            override fun cancel(): Boolean = session.cancel(requireRestore = false)

            override fun cancelAndRestore(): Boolean = session.cancel(requireRestore = true)
        }
    }

    override fun close() {
        val session = synchronized(activeLock) {
            closed = true
            active
        }
        session?.cancel(requireRestore = false)
    }

    /** Cancels the active capture inline before its WebView is reparented. Main-thread only. */
    fun cancelActiveForReparent(): Boolean {
        if (restorationBlocked.get()) {
            if (!runCatching(recoverPrimaryRestore).getOrDefault(false)) return false
            restorationBlocked.set(false)
        }
        val session = synchronized(activeLock) { active } ?: return true
        return session.cancel(requireRestore = true)
    }

    private inner class Session(
        private val deadlineElapsedMillis: Long,
        private val callback: (BrowserScreenshotCaptureResult<T>) -> Unit,
    ) {
        private val lock = Any()
        private val terminal = AtomicBoolean(false)
        private val attempts = mutableListOf<BrowserScreenshotAttempt>()
        private var phase = 0L
        private var currentHandle: BrowserScreenshotCaptureHandle? = null
        private var currentBackend: String? = null
        private var debug = false

        fun startPrimary() {
            debug = runCatching(debugEnabled).getOrDefault(false)
            startSource(primary, BrowserScreenshotCaptureContract.PRIMARY_BACKEND) { result ->
                val primaryCode = normalizedFailureCode(result.code, result.value)
                record(BrowserScreenshotCaptureContract.PRIMARY_BACKEND, primaryCode)
                if (primaryCode == "OK" && result.value != null) {
                    complete(BrowserScreenshotCaptureResult(result.value, "OK", BrowserScreenshotOutcome(
                        backend = BrowserScreenshotCaptureContract.PRIMARY_BACKEND,
                        attempts = attemptsSnapshot(), debugEnabled = debug,
                    )))
                    return@startSource
                }
                result.value?.let(::discard)
                if (!BrowserScreenshotCaptureContract.fallbackAllowed(primaryCode)) {
                    complete(failure(primaryCode))
                } else if (!debug) {
                    complete(BrowserScreenshotCaptureResult(null, BrowserScreenshotCaptureContract.DEBUG_REQUIRED,
                        BrowserScreenshotOutcome(attempts = attemptsSnapshot(), userActionRequired = true)))
                } else if (!runCatching(debugEnabled).getOrDefault(false)) {
                    complete(BrowserScreenshotCaptureResult(null, BrowserScreenshotCaptureContract.DEBUG_REQUIRED,
                        BrowserScreenshotOutcome(attempts = attemptsSnapshot(), userActionRequired = true)))
                } else {
                    startSource(fallback, BrowserScreenshotCaptureContract.FALLBACK_BACKEND) { secondary ->
                        val fallbackCode = normalizedFailureCode(secondary.code, secondary.value)
                        record(BrowserScreenshotCaptureContract.FALLBACK_BACKEND, fallbackCode)
                        if (!runCatching(debugEnabled).getOrDefault(false)) {
                            secondary.value?.let(::discard)
                            complete(BrowserScreenshotCaptureResult(null, BrowserScreenshotCaptureContract.DEBUG_REQUIRED,
                                BrowserScreenshotOutcome(attempts = attemptsSnapshot(), debugEnabled = false, userActionRequired = true)))
                        } else if (fallbackCode == "OK" && secondary.value != null) {
                            complete(BrowserScreenshotCaptureResult(secondary.value, "OK", BrowserScreenshotOutcome(
                                backend = BrowserScreenshotCaptureContract.FALLBACK_BACKEND,
                                attempts = attemptsSnapshot(), debugEnabled = true,
                            )))
                        } else {
                            secondary.value?.let(::discard)
                            complete(failure(fallbackCode))
                        }
                    }
                }
            }
        }

        private fun startSource(
            source: BrowserScreenshotCaptureSource<T>,
            backend: String,
            onResult: (BrowserScreenshotCaptureResult<T>) -> Unit,
        ) {
            val sourcePhase = synchronized(lock) {
                if (terminal.get()) return
                (++phase).also {
                    currentHandle = null
                    currentBackend = backend
                }
            }
            var callbackFailure: Throwable? = null
            val returnedHandle = try {
                source.capture(deadlineElapsedMillis) { result ->
                    val accepted = synchronized(lock) {
                        if (terminal.get() || phase != sourcePhase) false
                        else { phase++; currentHandle = null; currentBackend = null; true }
                    }
                    try {
                        if (accepted) onResult(result) else result.value?.let(::discard)
                    } catch (error: Throwable) {
                        callbackFailure = error
                        throw error
                    }
                }
            } catch (error: Throwable) {
                if (callbackFailure === error) throw error
                val accepted = synchronized(lock) {
                    if (terminal.get() || phase != sourcePhase) false
                    else { phase++; currentHandle = null; currentBackend = null; true }
                }
                if (accepted) onResult(BrowserScreenshotCaptureResult(
                    null, BrowserScreenshotCaptureContract.VIRTUAL_DISPLAY_UNAVAILABLE,
                ))
                return
            }
            val keepHandle = synchronized(lock) {
                if (terminal.get() || phase != sourcePhase) false
                else { currentHandle = returnedHandle; true }
            }
            if (!keepHandle) returnedHandle.cancel()
        }

        fun cancel(requireRestore: Boolean): Boolean {
            if (!terminal.compareAndSet(false, true)) return false
            val (handle, backend) = synchronized(lock) {
                phase++
                (currentHandle to currentBackend).also {
                    currentHandle = null
                    currentBackend = null
                }
            }
            val restored = runCatching {
                when {
                    !requireRestore -> { handle?.cancel(); true }
                    handle == null -> backend != BrowserScreenshotCaptureContract.PRIMARY_BACKEND
                    else -> handle.cancelAndRestore()
                }
            }.getOrDefault(false)
            if (requireRestore && !restored) restorationBlocked.set(true)
            clearActive()
            runCatching { callback(BrowserScreenshotCaptureResult(
                null,
                if (!restored) BrowserScreenshotCaptureContract.RESTORE_FAILED else "CANCELLED",
            )) }
            return !requireRestore || restored
        }

        private fun record(backend: String, code: String) {
            synchronized(lock) { attempts += BrowserScreenshotAttempt(backend, code) }
        }

        private fun attemptsSnapshot(): List<BrowserScreenshotAttempt> = synchronized(lock) { attempts.toList() }

        private fun failure(code: String) = BrowserScreenshotCaptureResult<T>(
            null, code, BrowserScreenshotOutcome(
                attempts = attemptsSnapshot(), debugEnabled = debug, userActionRequired = false,
            ),
        )

        private fun normalizedFailureCode(code: String, value: T?): String {
            if (code == "OK" && value == null) return "CAPTURE_EMPTY_RESULT"
            return code
        }

        private fun complete(result: BrowserScreenshotCaptureResult<T>) {
            if (!terminal.compareAndSet(false, true)) {
                result.value?.let(::discard)
                return
            }
            if (result.code == BrowserScreenshotCaptureContract.RESTORE_FAILED) {
                restorationBlocked.set(true)
            }
            synchronized(lock) {
                phase++
                currentHandle = null
                currentBackend = null
            }
            clearActive()
            callback(result)
        }

        private fun clearActive() {
            synchronized(activeLock) { if (active === this@Session) active = null }
        }

        private fun discard(value: T) { discardValue(value) }
    }
}
