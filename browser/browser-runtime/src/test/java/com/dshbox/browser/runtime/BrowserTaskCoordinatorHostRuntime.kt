package com.dshbox.browser.runtime

import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

internal class BrowserTaskCoordinatorHostRuntime(
    val store: BrowserTaskCoordinatorHostStore,
    private val bootId: String = "boot-host",
    private val failOpen: Boolean = false,
) : BrowserTaskCoordinatorRuntime {
    val io = Executors.newSingleThreadExecutor { task ->
        Thread(task, "browser-task-host-io").apply { isDaemon = true }
    }
    val main = Executors.newSingleThreadExecutor { task ->
        Thread(task, "browser-task-host-main").apply { isDaemon = true }
    }
    val initialized = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val shutdown = CountDownLatch(1)
    @Volatile var shutdownCalled = false
        private set
    @Volatile private var openFailed = false

    override fun openResources(): BrowserTaskCoordinatorResources {
        if (failOpen) {
            openFailed = true
            throw IllegalStateException("host resources unavailable")
        }
        return BrowserTaskCoordinatorResources(store, ::digest, bootId)
    }

    fun awaitInitialized(timeoutMillis: Long = 2_000): Boolean =
        initialized.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitClosed(timeoutMillis: Long = 2_000): Boolean =
        closed.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitShutdown(timeoutMillis: Long = 2_000): Boolean =
        shutdown.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun failedOpen(): Boolean = openFailed

    override fun postIo(task: () -> Unit) {
        if (io.isShutdown) {
            task()
            return
        }
        try {
            io.execute {
                task()
                initialized.countDown()
            }
        } catch (_: RejectedExecutionException) {
            task()
        }
    }

    override fun postMain(task: () -> Unit) {
        if (main.isShutdown) {
            task()
            return
        }
        try {
            main.execute(task)
        } catch (_: RejectedExecutionException) {
            task()
        }
    }

    override fun elapsedRealtime(): Long = System.currentTimeMillis()

    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun currentBootId(): String = bootId

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

    private fun digest(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { "%02x".format(it) }
}
