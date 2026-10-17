package com.dshbox.browser.runtime

/** Owns bounded, read-only condition waits and guarantees a single terminal callback. */
internal class BrowserWaitCoordinator(
    private val clock: () -> Long,
    private val scheduler: Scheduler,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    interface Scheduler {
        fun postDelayed(delayMillis: Long, task: () -> Unit): Handle
        fun remove(handle: Handle)
    }

    interface Handle

    data class Result(val code: String, val matched: Boolean?, val reason: String? = null)

    private data class Wait(
        val id: String,
        val deadlineAtMillis: Long,
        val intervalMillis: Long,
        val stillValid: () -> Boolean,
        val probe: ((Boolean?, String?) -> Unit) -> Unit,
        val callback: (Result) -> Unit,
        var scheduled: Handle? = null,
        var deadlineHandle: Handle? = null,
        var probing: Boolean = false,
        var complete: Boolean = false,
    )

    private val waits = LinkedHashMap<String, Wait>()

    @Synchronized
    fun start(
        id: String,
        deadlineAtMillis: Long,
        intervalMillis: Long,
        stillValid: () -> Boolean,
        probe: ((Boolean?, String?) -> Unit) -> Unit,
        callback: (Result) -> Unit,
    ): Boolean {
        if (id in waits || waits.size >= maxConcurrent || deadlineAtMillis <= clock()) return false
        val wait = Wait(id, deadlineAtMillis, intervalMillis.coerceIn(100L, 1_000L), stillValid, probe, callback)
        waits[id] = wait
        wait.deadlineHandle = scheduler.postDelayed((deadlineAtMillis - clock()).coerceAtLeast(1L)) {
            synchronized(this) {
                if (!wait.complete) finish(wait, Result("WAIT_TIMEOUT", false))
            }
        }
        tick(wait)
        return true
    }

    @Synchronized
    fun cancel(id: String): Boolean {
        return terminate(id, Result("CANCELLED", false, "cancelled"))
    }

    @Synchronized
    fun terminate(id: String, result: Result): Boolean {
        val wait = waits[id] ?: return false
        finish(wait, result)
        return true
    }

    @Synchronized
    fun cancelAll(code: String = "SESSION_REVOKED") {
        waits.values.toList().forEach { runCatching { finish(it, Result(code, false)) } }
    }

    @Synchronized
    fun size(): Int = waits.size

    @Synchronized
    fun revalidate() {
        waits.values.toList().forEach {
            if (!runCatching(it.stillValid).getOrDefault(false)) finish(it, Result("SESSION_REVOKED", false))
        }
    }

    private fun tick(wait: Wait) {
        if (wait.complete || wait.probing) return
        if (!runCatching(wait.stillValid).getOrDefault(false)) return finish(wait, Result("SESSION_REVOKED", false))
        if (clock() >= wait.deadlineAtMillis) return finish(wait, Result("WAIT_TIMEOUT", false))
        wait.probing = true
        try {
            wait.probe { matched, error ->
                synchronized(this) {
                    if (wait.complete) return@synchronized
                    wait.probing = false
                    if (!runCatching(wait.stillValid).getOrDefault(false)) return@synchronized finish(wait, Result("SESSION_REVOKED", false))
                    if (clock() >= wait.deadlineAtMillis) return@synchronized finish(wait, Result("WAIT_TIMEOUT", false))
                    if (error != null) return@synchronized finish(wait, Result(error, false))
                    if (matched == true) return@synchronized finish(wait, Result("OK", true))
                    wait.scheduled = scheduler.postDelayed(wait.intervalMillis) { synchronized(this) { tick(wait) } }
                }
            }
        } catch (_: Exception) {
            wait.probing = false
            finish(wait, Result("UNKNOWN", null))
        }
    }

    private fun finish(wait: Wait, result: Result) {
        if (wait.complete) return
        wait.complete = true
        wait.scheduled?.let { runCatching { scheduler.remove(it) } }
        wait.deadlineHandle?.let { runCatching { scheduler.remove(it) } }
        wait.scheduled = null
        wait.deadlineHandle = null
        waits.remove(wait.id)
        runCatching { wait.callback(result) }
    }

    companion object {
        const val DEFAULT_MAX_CONCURRENT = 8
    }
}
