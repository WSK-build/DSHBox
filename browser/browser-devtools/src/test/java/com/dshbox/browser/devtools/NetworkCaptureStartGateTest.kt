package com.dshbox.browser.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NetworkCaptureStartGateTest {
    @Test
    fun failedEnablePreservesPreviousCaptureAndReleasesEventGate() {
        val gate = NetworkCaptureStartGate()
        var capture = "previous"
        var eventProcessed = false
        try {
            gate.enableThenStart(
                enable = { throw IllegalStateException("enable rejected") },
                begin = { capture = "replacement" },
            )
            throw AssertionError("enable failure must propagate")
        } catch (error: IllegalStateException) {
            assertEquals("enable rejected", error.message)
        }
        gate.dispatchEvent { eventProcessed = true }
        assertEquals("previous", capture)
        assertTrue(eventProcessed)
    }

    @Test
    fun requestEventReceivedDuringNetworkEnableIsProcessedAfterCaptureBegins() {
        val gate = NetworkCaptureStartGate()
        val enableEntered = CountDownLatch(1)
        val finishEnable = CountDownLatch(1)
        val eventReady = CountDownLatch(1)
        val eventProcessed = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())

        val starter = Thread {
            gate.enableThenStart(
                enable = {
                    order += "enable_sent"
                    enableEntered.countDown()
                    check(finishEnable.await(2, TimeUnit.SECONDS))
                    order += "enable_acknowledged"
                },
                begin = { order += "recording_started" },
            )
        }
        starter.start()
        assertTrue(enableEntered.await(2, TimeUnit.SECONDS))

        val event = Thread {
            eventReady.countDown()
            gate.dispatchEvent {
                order += "request_event_processed"
                eventProcessed.countDown()
            }
        }
        event.start()
        assertTrue(eventReady.await(2, TimeUnit.SECONDS))
        assertFalse("events must wait for the Network.enable acknowledgement and store begin",
            eventProcessed.await(100, TimeUnit.MILLISECONDS))

        finishEnable.countDown()
        starter.join(2_000)
        event.join(2_000)

        assertFalse(starter.isAlive)
        assertFalse(event.isAlive)
        assertEquals(listOf("enable_sent", "enable_acknowledged", "recording_started", "request_event_processed"), order)
    }
}
