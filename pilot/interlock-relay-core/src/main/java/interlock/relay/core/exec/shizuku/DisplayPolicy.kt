package interlock.relay.core.exec.shizuku

/**
 * 建屏后兜底的纯编排：全屏窗口模式 + IME 屏内策略。
 *
 * 与 IO 的边界收在 [PolicyIo]：隐藏 `IWindowManager` 的两次调用与一次 shell 回退由实现方
 * 提供，本对象只决定「先试哪条、失败退哪条、note 怎么写」。JVM 测试用假 IO 穷举回退路径，
 * 真实现（`RelayShellService.TrustedDisplay`）只接 Android 侧。
 *
 * 两个动作都**尽力而为**：做不成都只写 note，不把建屏判死——判死的尺子是主屏自检的
 * 状态对比，不是这两步。
 */
internal object DisplayPolicy {

    /** 与 IO 的边界：一次应用 = 两次隐藏调用 + 一条 shell 回退命令的执行能力。 */
    internal interface PolicyIo {
        /** 隐藏 `IWindowManager.setWindowingMode(displayId, mode)`；不可达/被拒时返回失败。 */
        fun hiddenSetWindowingMode(id: Int): Result<Unit>

        /** shell 回退命令的执行（用户服务里是 shell 身份的 `sh -c`）。 */
        fun runShell(command: String, timeoutMs: Long): RelayShellService.Outcome

        /** 隐藏 `IWindowManager.setDisplayImePolicy(displayId, policy)`；没有第二条通路。 */
        fun hiddenSetImePolicy(id: Int): Result<Unit>
    }

    data class Outcome(
        val windowingOk: Boolean,
        val windowingNote: String,
        val imePolicyOk: Boolean,
        val imePolicyNote: String,
    )

    /** 全屏窗口模式的 mode 值（`WindowingMode` 的 FULLSCREEN）。 */
    internal const val WINDOWING_MODE_FULLSCREEN = 1

    /** IME 政策 local：输入法跟随这块屏，不再回主屏。 */
    internal const val IME_POLICY_LOCAL = 0

    /** 隐藏调用失败时的窗口模式回退命令；命令串收在纯函数里，JVM 可测。 */
    internal fun windowingFallbackCommand(id: Int): String =
        "wm set-display-windowing-mode -d $id 1"

    fun apply(id: Int, io: PolicyIo, shellTimeoutMs: Long): Outcome {
        val hidden = io.hiddenSetWindowingMode(id)
        val windowing = if (hidden.isSuccess) {
            true to "hidden setWindowingMode ok"
        } else {
            // 隐藏调用失败退回 wm 命令（同一件事的 shell 入口，shell 身份下可用）。
            val fallback = io.runShell(windowingFallbackCommand(id), shellTimeoutMs)
            val cause = hidden.exceptionOrNull()
            if (fallback.success) {
                true to "wm fallback ok (hidden setWindowingMode failed: " +
                    "${cause?.javaClass?.simpleName}: ${cause?.message ?: "no message"})"
            } else {
                false to "hidden setWindowingMode failed (${cause?.javaClass?.simpleName}: " +
                    "${cause?.message ?: "no message"}); wm fallback exit=${fallback.exitCode}: " +
                    fallback.stderr.trim().take(NOTE_CHARS)
            }
        }
        val ime = io.hiddenSetImePolicy(id)
        val imeOutcome = if (ime.isSuccess) {
            true to "setDisplayImePolicy local ok"
        } else {
            val cause = ime.exceptionOrNull()
            false to "setDisplayImePolicy failed: ${cause?.javaClass?.simpleName}: ${cause?.message ?: "no message"}"
        }
        return Outcome(windowing.first, windowing.second, imeOutcome.first, imeOutcome.second)
    }

    /** 兜底失败原因里 stderr 片段的截断长度。 */
    private const val NOTE_CHARS = 120
}
