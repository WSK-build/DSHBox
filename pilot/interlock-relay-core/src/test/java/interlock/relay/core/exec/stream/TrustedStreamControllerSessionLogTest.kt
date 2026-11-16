package interlock.relay.core.exec.stream

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主侧流会话的**诊断读数**（纯 JVM，假设备 + 假 log）：
 *
 * - 开流失败：设备侧拒绝 → 一条 phase=start / result=failed，reason 是设备侧原因；
 * - 停流：收场已确认（confirmed）或结局未知（unknown）；
 * - EOF：会话自然结束 → phase=eof，result 区分设备侧收尾与客户端自己走了；
 * - 看门狗到点：phase=watchdog / result=timeout。
 *
 * 这里**不碰任何 Android 依赖**：设备面用 [StreamDevice] 替身，日志面用
 * [StreamSessionLog] 替身 —— 控制器只产出读数，往哪里写由装配方决定。
 */
class TrustedStreamControllerSessionLogTest {

    /** 假设备：按脚本回开流结果，并记录关停次数。 */
    private class FakeDevice(
        private val openResult: () -> StreamDevice.OpenResult,
        private val closeResult: Boolean = true,
    ) : StreamDevice {
        var closeCount = 0

        override suspend fun open(waitMs: Long): StreamDevice.OpenResult = openResult()

        override fun close(waitMs: Long): Boolean {
            closeCount++
            return closeResult
        }
    }

    /** 假读端：给一段固定字节，读完即 EOF。 */
    private class FakeReadEnd(private val bytes: ByteArray) : StreamReadEnd {
        var closed = false

        override fun openStream(): InputStream = ByteArrayInputStream(bytes)

        override fun close() {
            closed = true
        }
    }

    /** 假日志：把读数收进列表，供断言。 */
    private class FakeSessionLog : StreamSessionLog {
        val records = java.util.Collections.synchronizedList(mutableListOf<StreamSession>())

        override fun write(record: StreamSession) {
            records.add(record)
        }

        fun find(phase: String): StreamSession? =
            records.firstOrNull { it.phase == phase }

        /** 取一条读数（没有就是断言失败，不给 null 继续走下去）。 */
        fun require(phase: String): StreamSession =
            find(phase) ?: throw AssertionError("缺少 phase=$phase 的会话读数")
    }

    private fun controller(
        device: StreamDevice,
        log: FakeSessionLog,
        watchdogDelayMs: Long = 60_000L,
    ): TrustedStreamController = TrustedStreamController(
        device = device,
        sessionLog = log,
        now = { 0L },
        watchdogDelayMs = { _, _ -> watchdogDelayMs },
    )

    @Test
    fun `a failed start writes one session record with the device-side reason`() {
        val log = FakeSessionLog()
        val device = FakeDevice(openResult = { StreamDevice.OpenResult.Failed("no trusted display") })
        val controller = controller(device, log)

        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertTrue(outcome is StreamControl.StartOutcome.Failed)
        val record = log.require(StreamSessionPhase.START)
        assertEquals("failed", record.result)
        assertEquals("no trusted display", record.reason)
        assertTrue("会话序号必须是 S 开头的计数（不是 token）", record.session.startsWith("S"))
    }

    @Test
    fun `a successful start writes an ok record`() {
        val log = FakeSessionLog()
        val device = FakeDevice(
            openResult = {
                StreamDevice.OpenResult.Opened(
                    FakeReadEnd(ByteArray(8)),
                    "{\"mime\":\"video/mp4\",\"width\":720,\"height\":1280,\"sourceWidth\":1080,\"sourceHeight\":1920}",
                )
            },
        )
        val controller = controller(device, log)

        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }
        assertTrue(outcome is StreamControl.StartOutcome.Running)
        val record = log.require(StreamSessionPhase.START)
        assertEquals("ok", record.result)
        // metadata 的 source/encode 维度进读数：诊断"源多大、实际编多大"全靠它。
        assertEquals(1080, record.sourceW)
        assertEquals(1920, record.sourceH)
        assertEquals(720, record.encodeW)
        assertEquals(1280, record.encodeH)
    }

    @Test
    fun `a stop writes the outcome and the bytes already written`() {
        val log = FakeSessionLog()
        val device = FakeDevice(
            openResult = { StreamDevice.OpenResult.Opened(FakeReadEnd(ByteArray(8)), "{}") },
            closeResult = true,
        )
        val controller = controller(device, log)
        kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertTrue(controller.stop())

        val record = log.require(StreamSessionPhase.STOP)
        assertEquals("confirmed", record.result)
        assertNotNull("停流读数要带已写出的字节数", record.bytes)
        assertEquals(1, device.closeCount)
    }

    @Test
    fun `a stop with a lost device reply is recorded as unknown`() {
        val log = FakeSessionLog()
        val device = FakeDevice(
            openResult = { StreamDevice.OpenResult.Opened(FakeReadEnd(ByteArray(8)), "{}") },
            closeResult = false,
        )
        val controller = controller(device, log)
        kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertEquals(false, controller.stop())

        val record = log.require(StreamSessionPhase.STOP)
        assertEquals("unknown", record.result)
    }

    @Test
    fun `an ended stream writes an eof record that tells eof from a client gone`() {
        val log = FakeSessionLog()
        val device = FakeDevice(
            openResult = { StreamDevice.OpenResult.Opened(FakeReadEnd(ByteArray(4)), "{}") },
        )
        val controller = controller(device, log)
        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }
        val handle = (outcome as StreamControl.StartOutcome.Running).stream

        // 取一次流并读到头：设备侧读完 → 服务端报 EOF → 装配方清账。
        val socket = Socket(InetAddress.getByName("127.0.0.1"), handle.port)
        socket.soTimeout = 5_000
        socket.getOutputStream().apply {
            write(("GET /stream?token=" + handle.token + " HTTP/1.1\r\nConnection: close\r\n\r\n").toByteArray())
            flush()
        }
        socket.getInputStream().readBytes()
        socket.close()

        val deadline = System.currentTimeMillis() + 5_000
        while (log.find(StreamSessionPhase.EOF) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val record = log.require(StreamSessionPhase.EOF)
        assertEquals("设备侧读完 → eof", "eof", record.result)
        assertEquals("eof 读数要带已写出的字节数", 4L, record.bytes)
    }

    @Test
    fun `the watchdog expiry writes a watchdog record with a timeout result`() {
        val log = FakeSessionLog()
        val device = FakeDevice(
            openResult = { StreamDevice.OpenResult.Opened(FakeReadEnd(ByteArray(8)), "{}") },
        )
        // 看门狗立刻到点：等余量给 1ms，走的是同一条收场通路。
        val controller = controller(device, log, watchdogDelayMs = 1L)
        kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        val deadline = System.currentTimeMillis() + 5_000
        while (log.find(StreamSessionPhase.WATCHDOG) == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        val record = log.require(StreamSessionPhase.WATCHDOG)
        assertEquals("timeout", record.result)
        assertEquals("host watchdog expired", record.reason)
    }

    @Test
    fun `the session label is a counter that never leaks the token`() {
        val log = FakeSessionLog()
        val device = FakeDevice(openResult = { StreamDevice.OpenResult.Failed("nope") })
        val controller = controller(device, log)

        kotlinx.coroutines.runBlocking { controller.start(1_000L) }
        val first = controller.sessionLabel
        kotlinx.coroutines.runBlocking { controller.start(1_000L) }
        val second = controller.sessionLabel

        assertNotNull(first)
        assertNotNull(second)
        val firstLabel = first!!
        val secondLabel = second!!
        assertTrue("序号必须逐场递增", firstLabel != secondLabel)
        assertTrue(firstLabel.startsWith("S"))
        assertTrue(secondLabel.startsWith("S"))
    }

    @Test
    fun `every recorded session carries the six diagnostic fields`() {
        val log = FakeSessionLog()
        val device = FakeDevice(openResult = { StreamDevice.OpenResult.Failed("no trusted display") })
        val controller = controller(device, log)
        kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertEquals(1, log.records.size)
        val record = log.records.first()
        assertTrue(record.session.isNotEmpty())
        assertTrue(record.phase.isNotEmpty())
        assertTrue(record.result.isNotEmpty())
        assertNotNull(record.durationMs)
    }
}
