package interlock.relay.core.exec.stream

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙箱重启后的孤儿流会话：宿主已无会话、服务端还回
 * "already running (or still draining)" 时，先 stop 孤儿再重试一次，
 * 不能把永久 502 丢给面板。
 */
class TrustedStreamControllerStaleSessionTest {

    private class FakeDevice(private val results: ArrayDeque<StreamDevice.OpenResult>) : StreamDevice {
        var opens = 0
        var closes = 0

        override suspend fun open(waitMs: Long): StreamDevice.OpenResult {
            opens++
            return results.removeFirst()
        }

        override fun close(waitMs: Long): Boolean {
            closes++
            return true
        }
    }

    private class FakeReadEnd : StreamReadEnd {
        override fun openStream(): InputStream = ByteArrayInputStream(ByteArray(4))
        override fun close() = Unit
    }

    private class FakeLog : StreamSessionLog {
        val records = mutableListOf<StreamSession>()
        override fun write(record: StreamSession) { records.add(record) }
    }

    private fun controller(device: StreamDevice): TrustedStreamController = TrustedStreamController(
        device = device,
        sessionLog = FakeLog(),
        now = { 0L },
        watchdogDelayMs = { _, _ -> 60_000L },
    )

    @Test
    fun `stale already-running session is stopped and opened again`() {
        val device = FakeDevice(ArrayDeque(listOf(
            StreamDevice.OpenResult.Failed("a stream session is already running (or still draining); stop it first"),
            StreamDevice.OpenResult.Opened(FakeReadEnd(), "{\"width\":1280,\"height\":1032}"),
        )))
        val controller = controller(device)

        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }
        try {
            assertTrue(outcome is StreamControl.StartOutcome.Running)
            assertEquals("stale session must be stopped exactly once", 1, device.closes)
            assertEquals("after the stop, open is retried", 2, device.opens)
        } finally {
            controller.stop()
        }
    }

    @Test
    fun `a normal failure is not retried and does not stop anything`() {
        val device = FakeDevice(ArrayDeque(listOf(
            StreamDevice.OpenResult.Failed("no trusted display"),
        )))
        val controller = controller(device)

        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertTrue(outcome is StreamControl.StartOutcome.Failed)
        assertEquals("no trusted display", (outcome as StreamControl.StartOutcome.Failed).reason)
        assertEquals(1, device.opens)
        assertEquals(0, device.closes)
    }

    @Test
    fun `the conflict matcher only matches the stale-session wording`() {
        assertTrue(isStaleStreamConflict("a stream session is already running (or still draining); stop it first"))
        assertTrue(isStaleStreamConflict("still draining"))
        assertFalse(isStaleStreamConflict("no trusted display"))
        assertFalse(isStaleStreamConflict("protected-apps list"))
    }
}
