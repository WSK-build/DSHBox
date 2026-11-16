package interlock.relay.core.exec.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数据面守卫：面板流在受保护应用前台时必须拒绝，且不能靠调用方
 * 出示什么 token 绕过去——守卫在校验 tabToken/会话之前，按顶层包与名单判定。
 *
 * 这里只碰 [StreamDevice] 替身：拒绝路径不应触发 `open`，允许路径才落到设备面。
 */
class TrustedStreamControllerProtectedGuardTest {

    private class FakeDevice : StreamDevice {
        var openCount = 0

        override suspend fun open(waitMs: Long): StreamDevice.OpenResult {
            openCount++
            return StreamDevice.OpenResult.Failed("device not used in this test")
        }

        override fun close(waitMs: Long): Boolean = true
    }

    private class FakeLog : StreamSessionLog {
        val records = mutableListOf<StreamSession>()
        override fun write(record: StreamSession) { records.add(record) }
    }

    private fun controller(
        device: StreamDevice,
        log: StreamSessionLog,
        protected: Set<String>,
        top: String?,
    ): TrustedStreamController = TrustedStreamController(
        device = device,
        protectedPackages = { protected },
        topPackage = { top },
        sessionLog = log,
        now = { 0L },
        watchdogDelayMs = { _, _ -> 60_000L },
    )

    @Test
    fun `protected app in front fails closed before the device is opened`() {
        val device = FakeDevice()
        val log = FakeLog()
        val controller = controller(device, log, setOf("com.bank"), "com.bank")

        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertTrue(outcome is StreamControl.StartOutcome.Failed)
        assertEquals(CODE_PROTECTED_APP, (outcome as StreamControl.StartOutcome.Failed).code)
        assertEquals("guard must run before the device", 0, device.openCount)
        val record = log.records.single { it.phase == StreamSessionPhase.START }
        assertEquals("failed", record.result)
        assertEquals(SHORT_BLOCKED, record.reason)
    }

    @Test
    fun `unknown top package fails closed when a protected list exists`() {
        val device = FakeDevice()
        val log = FakeLog()
        val controller = controller(device, log, setOf("com.bank"), null)

        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertTrue(outcome is StreamControl.StartOutcome.Failed)
        assertEquals(CODE_PROTECTED_APP, (outcome as StreamControl.StartOutcome.Failed).code)
        assertEquals(0, device.openCount)
    }

    @Test
    fun `a non-protected top package proceeds to the device`() {
        val device = FakeDevice()
        val log = FakeLog()
        val controller = controller(device, log, setOf("com.bank"), "com.other")

        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertTrue(outcome is StreamControl.StartOutcome.Failed)
        assertEquals("device not used in this test", (outcome as StreamControl.StartOutcome.Failed).reason)
        assertEquals(1, device.openCount)
    }

    @Test
    fun `an empty protected list skips the top-package read entirely`() {
        val device = FakeDevice()
        val log = FakeLog()
        var reads = 0
        val controller = TrustedStreamController(
            device = device,
            protectedPackages = { emptySet() },
            topPackage = { reads++; "com.bank" },
            sessionLog = log,
            now = { 0L },
            watchdogDelayMs = { _, _ -> 60_000L },
        )

        val outcome = kotlinx.coroutines.runBlocking { controller.start(1_000L) }

        assertTrue(outcome is StreamControl.StartOutcome.Failed)
        assertEquals("no protected apps: no dumpsys read", 0, reads)
        assertEquals(1, device.openCount)
    }
}
