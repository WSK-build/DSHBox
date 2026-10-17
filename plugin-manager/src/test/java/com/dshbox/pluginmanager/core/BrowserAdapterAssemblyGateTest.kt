package com.dshbox.pluginmanager.core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserAdapterAssemblyGateTest {
    @Test
    fun startupAndUiAssemblyCannotOverlap() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val jobs = (1..12).map {
            launch {
                BrowserAdapterAssemblyGate.withLock {
                    val now = active.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, now) }
                    delay(4)
                    active.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, maximum.get())
    }
}
