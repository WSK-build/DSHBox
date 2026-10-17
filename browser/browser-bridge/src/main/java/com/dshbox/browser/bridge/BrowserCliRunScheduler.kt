package com.dshbox.browser.bridge

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class BrowserCliRunIdentity(val generation: Long, val token: String)

internal data class BrowserCliRequestKey(val run: BrowserCliRunIdentity, val requestId: String)

internal enum class BrowserCliRequestPhase { QUEUED, RUNNING, CANCELLING, CANCELLED, FINISHED }

internal enum class BrowserCliCancelDisposition { CANCELLED_BEFORE_DISPATCH, RUNNING, ALREADY_HANDLED, FINISHED }

internal class BrowserCliActiveRequest(
    val identity: BrowserCliRunIdentity,
    val fingerprint: String,
    val result: java.util.concurrent.CompletableFuture<String>,
) {
    private var phase = BrowserCliRequestPhase.QUEUED
    private var scheduler: BrowserCliRunScheduler? = null
    private var queuedCommand: Runnable? = null
    private var queueEntryRemoved = false
    val cancelWait = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)
    val queuedCancelAction = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    @Synchronized
    fun installCancelWait(action: () -> Unit): Boolean {
        if (phase == BrowserCliRequestPhase.CANCELLED || phase == BrowserCliRequestPhase.FINISHED) {
            action()
            return false
        }
        cancelWait.set(action)
        return true
    }

    @Synchronized
    fun clearCancelWait(action: () -> Unit) {
        cancelWait.compareAndSet(action, null)
    }

    @Synchronized
    fun schedule(scheduler: BrowserCliRunScheduler, command: Runnable): Boolean {
        if (phase != BrowserCliRequestPhase.QUEUED) return false
        this.scheduler = scheduler
        queuedCommand = command
        if (scheduler.executeCommand(identity, command)) return true
        queuedCommand = null
        this.scheduler = null
        phase = BrowserCliRequestPhase.FINISHED
        queuedCancelAction.set(null)
        return false
    }

    @Synchronized
    fun beginDispatch(): Boolean {
        if (phase != BrowserCliRequestPhase.QUEUED) return false
        phase = BrowserCliRequestPhase.RUNNING
        queuedCommand = null
        scheduler = null
        queuedCancelAction.set(null)
        return true
    }

    @Synchronized
    fun wasCancelledBeforeDispatch(): Boolean = phase == BrowserCliRequestPhase.CANCELLED

    @Synchronized
    fun requestCancel(): BrowserCliCancelDisposition = when (phase) {
        BrowserCliRequestPhase.QUEUED -> {
            phase = BrowserCliRequestPhase.CANCELLED
            val command = queuedCommand
            queuedCommand = null
            queueEntryRemoved = scheduler?.cancelQueuedCommand(identity, command) == true
            scheduler = null
            BrowserCliCancelDisposition.CANCELLED_BEFORE_DISPATCH
        }
        BrowserCliRequestPhase.RUNNING -> {
            phase = BrowserCliRequestPhase.CANCELLING
            BrowserCliCancelDisposition.RUNNING
        }
        BrowserCliRequestPhase.CANCELLING, BrowserCliRequestPhase.CANCELLED ->
            BrowserCliCancelDisposition.ALREADY_HANDLED
        BrowserCliRequestPhase.FINISHED -> BrowserCliCancelDisposition.FINISHED
    }

    @Synchronized
    fun cancelDispatchRejected() {
        if (phase == BrowserCliRequestPhase.CANCELLING) phase = BrowserCliRequestPhase.RUNNING
    }

    fun finishQueuedCancellation() {
        val action = synchronized(this) {
            if (!queueEntryRemoved) return
            queueEntryRemoved = false
            queuedCancelAction.getAndSet(null)
        }
        action?.invoke()
    }

    @Synchronized
    fun finish() {
        phase = BrowserCliRequestPhase.FINISHED
        queuedCommand = null
        scheduler = null
        cancelWait.set(null)
        queuedCancelAction.set(null)
    }

    @Synchronized
    fun cancelForRunStop(
        requestId: String,
        unknownResponse: (String) -> String,
        cancelledResponse: (String) -> String,
    ) {
        if (phase == BrowserCliRequestPhase.FINISHED) return
        val wasQueued = phase == BrowserCliRequestPhase.QUEUED
        phase = BrowserCliRequestPhase.CANCELLED
        val command = queuedCommand
        queuedCommand = null
        scheduler?.cancelQueuedCommand(identity, command)
        scheduler = null
        cancelWait.getAndSet(null)?.invoke()
        queuedCancelAction.set(null)
        result.complete(if (wasQueued) cancelledResponse(requestId) else unknownResponse(requestId))
    }
}

internal class BrowserCliActiveRequestRegistry<T : Any> {
    private val requests = java.util.concurrent.ConcurrentHashMap<BrowserCliRequestKey, T>()

    fun register(key: BrowserCliRequestKey, request: T): T? = requests.putIfAbsent(key, request)
    fun get(key: BrowserCliRequestKey): T? = requests[key]
    fun contains(key: BrowserCliRequestKey): Boolean = requests.containsKey(key)
    fun remove(key: BrowserCliRequestKey, request: T): Boolean = requests.remove(key, request)

    fun finishRun(run: BrowserCliRunIdentity, finish: (String, T) -> Unit) {
        requests.entries.filter { it.key.run == run }.forEach { (key, request) ->
            if (requests.remove(key, request)) finish(key.requestId, request)
        }
    }
}

/** Owns bounded command and cancellation lanes for one broker instance. */
internal class BrowserCliRunScheduler(
    private val commandExecutor: ThreadPoolExecutor = createBrowserCliWorkerPool(),
    private val cancellationExecutor: ThreadPoolExecutor = createBrowserCliCancellationPool(),
    private val intakeExecutor: ThreadPoolExecutor = createBrowserCliIntakePool(),
) : AutoCloseable {
    private val queuedCommands = java.util.concurrent.ConcurrentHashMap.newKeySet<RunCommand>()
    private val queuedCancellations = java.util.concurrent.ConcurrentHashMap.newKeySet<RunCommand>()
    @Volatile private var closed = false

    fun executeCommand(run: BrowserCliRunIdentity, command: Runnable): Boolean = enqueue(run, commandExecutor, command)

    fun executeCancellation(run: BrowserCliRunIdentity, command: Runnable): Boolean =
        enqueue(run, cancellationExecutor, command)

    fun executeIntake(command: Runnable): Boolean = if (closed) false else try {
        intakeExecutor.execute(command)
        true
    } catch (_: java.util.concurrent.RejectedExecutionException) {
        false
    }

    private fun enqueue(run: BrowserCliRunIdentity, executor: ThreadPoolExecutor, command: Runnable): Boolean {
        if (closed) return false
        val targetQueue = if (executor === commandExecutor) queuedCommands else queuedCancellations
        val task = RunCommand(run, command) { targetQueue.remove(it) }
        targetQueue.add(task)
        return try {
            executor.execute(task)
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            targetQueue.remove(task)
            false
        }
    }

    fun cancelQueuedCommands(run: BrowserCliRunIdentity) {
        for (task in queuedCommands.toList()) {
            if (task.run == run && commandExecutor.remove(task)) queuedCommands.remove(task)
        }
        for (task in queuedCancellations.toList()) {
            if (task.run == run && cancellationExecutor.remove(task)) queuedCancellations.remove(task)
        }
    }

    fun cancelQueuedCommand(run: BrowserCliRunIdentity, command: Runnable?): Boolean {
        if (command == null) return false
        for (task in queuedCommands.toList()) {
            if (task.run == run && task.command === command && commandExecutor.remove(task)) {
                queuedCommands.remove(task)
                return true
            }
        }
        return false
    }

    fun isClosed(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        commandExecutor.shutdownNow()
        cancellationExecutor.shutdownNow()
        intakeExecutor.shutdownNow()
        queuedCommands.clear()
        queuedCancellations.clear()
        try {
            commandExecutor.awaitTermination(1, TimeUnit.SECONDS)
            cancellationExecutor.awaitTermination(1, TimeUnit.SECONDS)
            intakeExecutor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private class RunCommand(
        val run: BrowserCliRunIdentity,
        val command: Runnable,
        private val onFinish: (RunCommand) -> Unit,
    ) : Runnable {
        override fun run() {
            onFinish(this)
            command.run()
        }
    }
}

internal fun createBrowserCliCancellationPool(): ThreadPoolExecutor = ThreadPoolExecutor(
    1, 1, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(4),
    { task -> Thread(task, "browser-cli-cancel").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
)

internal fun createBrowserCliIntakePool(): ThreadPoolExecutor = ThreadPoolExecutor(
    2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(16),
    { task -> Thread(task, "browser-cli-intake").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
)
