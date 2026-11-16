package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建屏后兜底的编排矩阵（[DisplayPolicy.apply]，假 [DisplayPolicy.PolicyIo]）：
 * - 隐藏调用成功即收工，不跑 shell 回退；
 * - 隐藏失败退回 `wm set-display-windowing-mode`（命令串与目标屏号都要对）；
 * - 两条路都失败只把 windowingOk 记 false 并写全原因，**不抛、不判死**；
 * - IME 只有隐藏一条路，失败只记录。
 */
class DisplayPolicyTest {

    /** 可编程假 IO：记录收到的命令，按脚本回话。 */
    private class FakeIo(
        val windowingResult: Result<Unit>,
        val imeResult: Result<Unit>,
        val shellExit: Int = 0,
        val shellStderr: String = "",
    ) : DisplayPolicy.PolicyIo {
        val shellCommands = mutableListOf<String>()

        override fun hiddenSetWindowingMode(id: Int): Result<Unit> = windowingResult
        override fun hiddenSetImePolicy(id: Int): Result<Unit> = imeResult
        override fun runShell(command: String, timeoutMs: Long): RelayShellService.Outcome {
            shellCommands += command
            return RelayShellService.Outcome(shellExit, "", shellStderr)
        }
    }

    private fun io(
        windowing: Result<Unit> = Result.success(Unit),
        ime: Result<Unit> = Result.success(Unit),
        shellExit: Int = 0,
        shellStderr: String = "",
    ) = FakeIo(windowing, ime, shellExit, shellStderr)

    /** 隐藏调用成功：直接收工，不发 shell 回退。 */
    @Test
    fun hiddenSuccessSkipsTheShellFallback() {
        val fake = io()
        val outcome = DisplayPolicy.apply(5, fake, shellTimeoutMs = 1_000L)
        assertTrue(outcome.windowingOk)
        assertEquals("hidden setWindowingMode ok", outcome.windowingNote)
        assertTrue(outcome.imePolicyOk)
        assertTrue(fake.shellCommands.isEmpty())
    }

    /** 隐藏失败、wm 回退成功：跑的是带屏号的全屏窗口命令，note 说清走了回退。 */
    @Test
    fun hiddenFailureFallsBackToTheWmCommandForTheSameDisplay() {
        val fake = io(windowing = Result.failure(IllegalStateException("no window manager service")))
        val outcome = DisplayPolicy.apply(7, fake, shellTimeoutMs = 1_000L)
        assertTrue(outcome.windowingOk)
        assertEquals(listOf("wm set-display-windowing-mode -d 7 1"), fake.shellCommands)
        assertTrue(outcome.windowingNote.startsWith("wm fallback ok"))
        assertTrue(outcome.windowingNote.contains("IllegalStateException"))
    }

    /** 两条路都失败：windowingOk=false、note 带两段成因，不抛异常（兜底不判死建屏）。 */
    @Test
    fun bothWindowingPathsFailingOnlyRecordsTheReason() {
        val fake = io(
            windowing = Result.failure(SecurityException("refused")),
            shellExit = 2,
            shellStderr = "Error: Invalid display",
        )
        val outcome = DisplayPolicy.apply(9, fake, shellTimeoutMs = 1_000L)
        assertFalse(outcome.windowingOk)
        assertTrue(outcome.windowingNote.contains("SecurityException"))
        assertTrue(outcome.windowingNote.contains("wm fallback exit=2"))
        assertTrue(outcome.windowingNote.contains("Invalid display"))
    }

    /** IME 只有隐藏一条路：失败只记录，不产生任何 shell 命令。 */
    @Test
    fun imeFailureIsRecordedWithoutAShellFallback() {
        val fake = io(ime = Result.failure(NoSuchMethodException("setDisplayImePolicy")))
        val outcome = DisplayPolicy.apply(3, fake, shellTimeoutMs = 1_000L)
        assertFalse(outcome.imePolicyOk)
        assertTrue(outcome.imePolicyNote.contains("NoSuchMethodException"))
        assertTrue(fake.shellCommands.isEmpty())
    }

    /** 窗口失败但 IME 成功（及反向）：两半互不干扰，各记各的。 */
    @Test
    fun windowingAndImeOutcomesAreIndependent() {
        // 窗口失败要连同 shell 回退一起失败（默认假 IO 的 shell 是成功的）。
        val windowingBroken = DisplayPolicy.apply(
            1,
            io(windowing = Result.failure(RuntimeException("x")), shellExit = 3, shellStderr = "boom"),
            shellTimeoutMs = 1_000L,
        )
        assertFalse(windowingBroken.windowingOk)
        assertTrue(windowingBroken.imePolicyOk)
        val imeBroken = DisplayPolicy.apply(
            1,
            io(ime = Result.failure(RuntimeException("y"))),
            shellTimeoutMs = 1_000L,
        )
        assertTrue(imeBroken.windowingOk)
        assertFalse(imeBroken.imePolicyOk)
    }
}
