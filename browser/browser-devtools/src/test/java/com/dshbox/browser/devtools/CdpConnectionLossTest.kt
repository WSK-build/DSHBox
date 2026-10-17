package com.dshbox.browser.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class CdpConnectionLossTest {
    @Test
    fun flattenedTargetEventsRetainValidatedSessionIdentity() {
        val event = CdpConnection.decodeEventPayload(
            "{\"method\":\"Network.requestWillBeSent\",\"sessionId\":\"worker-session-1\",\"params\":{\"requestId\":\"7\"}}"
                .toByteArray(Charsets.UTF_8))
        assertEquals("worker-session-1", event?.sessionId)
        assertEquals("7", event?.params?.getString("requestId"))

        val invalid = CdpConnection.decodeEventPayload(
            "{\"method\":\"Network.requestWillBeSent\",\"sessionId\":\"bad/session\",\"params\":{}}"
                .toByteArray(Charsets.UTF_8))
        assertEquals(null, invalid)
    }

    @Test
    fun inspectorDetachedWireEventInterruptsCaptureAndDisablesNetworkTracking() {
        val payload = """{"method":"Inspector.detached","params":{"reason":"replaced_with_devtools"}}"""
            .toByteArray(StandardCharsets.UTF_8)
        val event = CdpConnection.decodeEventPayload(payload)

        assertEquals("Inspector.detached", event?.method)
        assertEquals("replaced_with_devtools", event?.let(::cdpDetachedReason))
        val health = CdpConnectionHealth()
        health.observe(event!!)
        assertTrue(health.isClosed())
        assertFalse(health.isTransportClosed())
        assertEquals(CdpConnectionLoss.INSPECTOR_DETACHED, health.lossReason())
        val state = cdpCaptureLossState(recording = true, loss = CdpConnectionLoss.INSPECTOR_DETACHED)
        assertTrue(state.stopRecording)
        assertFalse(state.networkTrackingEnabled)
        assertTrue(state.captureInterrupted)
        assertEquals("CAPTURE_INTERRUPTED", state.lastErrorCode)
    }

    @Test
    fun transportCloseDuringCaptureMarksCaptureInterrupted() {
        val health = CdpConnectionHealth()
        assertFalse(health.isClosed())
        assertTrue(health.closeTransport())
        assertTrue(health.isClosed())
        assertTrue(health.isTransportClosed())
        assertEquals(CdpConnectionLoss.TRANSPORT_CLOSED, health.lossReason())
        assertFalse("connection closure must be recorded once", health.closeTransport())

        val state = cdpCaptureLossState(recording = true, loss = health.lossReason())

        assertTrue(state.stopRecording)
        assertFalse(state.networkTrackingEnabled)
        assertTrue(state.captureInterrupted)
        assertEquals("CAPTURE_INTERRUPTED", state.lastErrorCode)
    }

    @Test
    fun lossWithoutActiveCaptureReportsItsCauseWithoutMarkingACompletedCaptureInterrupted() {
        val detached = cdpCaptureLossState(recording = false, loss = CdpConnectionLoss.INSPECTOR_DETACHED)
        assertFalse(detached.stopRecording)
        assertFalse(detached.networkTrackingEnabled)
        assertFalse(detached.captureInterrupted)
        assertEquals("CDP_DETACHED", detached.lastErrorCode)

        val closed = cdpCaptureLossState(recording = false, loss = CdpConnectionLoss.TRANSPORT_CLOSED)
        assertEquals("CDP_CONNECTION_CLOSED", closed.lastErrorCode)
        assertNull(CdpConnection.decodeEventPayload("""{"id":3,"result":{}}""".toByteArray()))
    }

    @Test
    fun laterCommandFailureDoesNotHideConfirmedCaptureInterruption() {
        assertEquals("CAPTURE_INTERRUPTED", cdpErrorAfterCommandFailure("CAPTURE_INTERRUPTED", "TARGET_UNAVAILABLE"))
        assertEquals("TARGET_UNAVAILABLE", cdpErrorAfterCommandFailure(null, "TARGET_UNAVAILABLE"))
        // A successful new capture resets the stored error before any later failure is recorded.
        assertEquals("CDP_CLOSED", cdpErrorAfterCommandFailure(null, "CDP_CLOSED"))
    }
}
