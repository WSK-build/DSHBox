package interlock.relay.core.exec.shizuku

import interlock.relay.core.exec.BackendResult
import interlock.relay.core.protocol.RelayError
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板释放=单次 release 的语义钉。
 *
 * [ShizukuBackend.panelTaskRelease] 是设备事务面（binder/挂起），JVM 起不了真场景；
 * 但"释放绝不走 枚举→move-stack→release→force-stop"这件事必须钉死 —— Android 17 上
 * move 与 release 的 BLAST 过渡重叠会把 system_server 打出 NPE，谁哪天"顺手优化"把它
 * 恢复回来就是一台设备的 system_server 重启。因此这里以**源码结构**为判据：释放函数体
 * 只许有一次 release 调用，任务枚举/move/force-stop/windowing 校正一个不许出现；
 * 回包映射（[ShizukuBackend.panelReleaseOutcome]）与校正命令生成
 * （[RelayShellService.TrustedTasks.windowingFixCommands]）是纯函数，直接断言行为。
 */
class PanelReleaseSemanticsTest {

    /** ShizukuBackend.kt 源码（单测工作目录=模块根；候选路径兜底，找不到当场失败）。 */
    private val backendSource: String by lazy {
        val candidates = listOf(
            "src/main/java/interlock/relay/core/exec/shizuku/ShizukuBackend.kt",
        )
        candidates.map { File(it) }.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 ShizukuBackend.kt；候选路径：$candidates")
    }

    /** 取 `signature` 所在函数的**代码体**（从签名行起做花括号配对，不含 KDoc）。 */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        if (start < 0) throw AssertionError("找不到函数：$signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        throw AssertionError("函数体花括号不配对：$signature")
    }

    @Test
    fun `panelTaskRelease 是单次 release：零枚举 零move 零force-stop 零校正`() {
        val body = functionBody(backendSource, "suspend fun panelTaskRelease()")
        assertEquals(
            "释放函数体只允许一次 releaseDisplay 调用（单次 release，不制造第二条过渡）",
            1, Regex("\\breleaseDisplay\\(").findAll(body).count(),
        )
        // 的核心禁令：release 路径不碰任务枚举/move-stack/force-stop/校正。
        assertFalse("release 不得枚举任务", body.contains("panelTaskList"))
        assertFalse("release 不得发 move-stack 事务", body.contains("CODE_TASKS_MOVE"))
        assertFalse("release 不得 force-stop", body.contains("forceStopCommands"))
        assertFalse("release 不得拼 move-stack 命令", body.contains("move-stack"))
        assertFalse("windowing 校正不进 release 路径", body.contains("windowingFixCommands"))
        assertFalse("windowing 校正不进 release 路径", body.contains("correctWindowingAfterMoveToMain"))
    }

    @Test
    fun `panelTaskRelease 的注释写明不得恢复 move-first 及 Android 17 NPE 原因`() {
        val start = backendSource.indexOf("suspend fun panelTaskRelease()")
        assertTrue(start > 0)
        // KDoc 在签名之前：取签名前 1500 个字符做注释判据（函数体上方就是它的 KDoc）。
        val doc = backendSource.substring(maxOf(0, start - 1500), start)
        assertTrue("注释要写明不要恢复 move-stack→release", doc.contains("move-stack"))
        assertTrue("注释要写明 Android 17 BLAST 过渡重叠的现场", doc.contains("BLAST"))
        assertTrue("注释要写明 system_server NPE 的后果", doc.contains("NPE"))
    }

    @Test
    fun `panelTaskMove 的 windowing 校正只在 main 方向、release 路径不校正`() {
        val moveBody = functionBody(backendSource, "suspend fun panelTaskMove(")
        val guard = Regex("if \\(direction == PanelMoveDirection\\.MAIN\\)\\s+correctWindowingAfterMoveToMain")
        assertTrue(
            "main 方向搬迁成功后才做 windowing 校正（virtual 方向不受影响）",
            guard.containsMatchIn(moveBody),
        )
        // 校正必须排在 move 成功判定之后（reply.code != 0 已提前 return）。
        val replyGuard = moveBody.indexOf("if (reply.code != 0)")
        val correction = moveBody.indexOf("correctWindowingAfterMoveToMain")
        assertTrue("校正放在 move 成功判定之后，不影响失败回包", replyGuard in 0 until correction)
    }

    @Test
    fun `panelReleaseOutcome：成功回 true，失败如实回 reason，缺 reason 落错误码名`() {
        assertEquals(
            true to "",
            ShizukuBackend.panelReleaseOutcome(BackendResult.Ok()),
        )
        assertEquals(
            false to "display release failed: IllegalStateException",
            ShizukuBackend.panelReleaseOutcome(
                BackendResult.Failed(RelayError.SURFACE_TRUSTED_UNSAFE, "display release failed: IllegalStateException"),
            ),
        )
        // 服务端没给 reason：如实落错误码名，不编原因。
        val noReason = ShizukuBackend.panelReleaseOutcome(BackendResult.Failed(RelayError.TRANSPORT_TIMEOUT))
        assertEquals(false, noReason.first)
        assertEquals(RelayError.TRANSPORT_TIMEOUT.name, noReason.second)
    }
}
