package interlock.relay.core.exec.shizuku

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空闲自动收屏与手动 release **同一路径、同语义** 的结构钉。
 *
 * `TrustedDisplay.releaseIfCurrent` 跑在服务端线程（设备场景），JVM 起不了；但它曾带
 * 的"枚举 → move-stack → release → force-stop"move-first 顺序在 Android 17 上会因
 * BLAST 过渡重叠打出 system_server NPE（已把手动的 panelTaskRelease 改成单次
 * release，这条钉保证空闲路径不再残存第二条 move-stack→release 过渡）。因此以源码
 * 结构为判据：空闲收屏函数体只许一次 `release()`，任务枚举/move/force-stop 一个不许
 * 有；`cleanupVirtualPackages`/`forceStopCommands` 这条死路径必须整体消失。
 */
class IdleReleaseSemanticsTest {

    /** RelayShellService.kt 源码（单测工作目录=模块根）。 */
    private val serviceSource: String by lazy {
        val candidates = listOf(
            "src/main/java/interlock/relay/core/exec/shizuku/RelayShellService.kt",
        )
        candidates.map { File(it) }.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 RelayShellService.kt；候选路径：$candidates")
    }

    /** 取 `signature` 所在函数的代码体（从签名行起做花括号配对）。 */
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

    /** 去掉块注释与行注释后的源码（禁令注释里会提到被禁的词，负向断言只看代码）。 */
    private fun stripComments(source: String): String = source
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `releaseIfCurrent 只 release 一次：零枚举 零move 零force-stop`() {
        val body = stripComments(functionBody(serviceSource, "private fun releaseIfCurrent("))
        assertEquals(
            "空闲收屏函数体只允许一次 release() 调用（与手动 release 同一路径）",
            1, Regex("\\brelease\\(").findAll(body).count(),
        )
        assertFalse("空闲收屏不得枚举任务（dumpsys）", body.contains("dumpsys"))
        assertFalse("空闲收屏不得 move-stack", body.contains("move-stack"))
        assertFalse("空闲收屏不得 force-stop", body.contains("force-stop"))
        assertFalse("空闲收屏不得走僵尸清理死路径", body.contains("cleanupVirtualPackages"))
        assertFalse("空闲收屏不再依赖任务解析", body.contains("TrustedTasks"))
        // 空闲路径必须先过租约复查且校验与收屏同一把锁。
        assertTrue("租约复查仍在（N3C）", body.contains("hasLeases()"))
        assertTrue("校验与收屏同一把对象锁", body.contains("synchronized(this)"))
    }

    @Test
    fun `move-first 死路径整体消失：cleanupVirtualPackages 与 forceStopCommands 不再存在`() {
        val code = stripComments(serviceSource)
        assertFalse("cleanupVirtualPackages 已删", code.contains("cleanupVirtualPackages"))
        assertFalse("forceStopCommands 已删", code.contains("forceStopCommands"))
        assertFalse("生产源不再拼 am force-stop 命令", code.contains("am force-stop"))
        // 的禁令注释要在场：不要恢复 move-stack→release。
        assertTrue(
            "releaseIfCurrent 注释写明 Android 17 BLAST/NPE 禁令",
            functionBody(serviceSource, "private fun releaseIfCurrent(").let { body ->
                val docStart = serviceSource.indexOf("private fun releaseIfCurrent(")
                val doc = serviceSource.substring(maxOf(0, docStart - 1500), docStart) + body
                doc.contains("move-stack") && doc.contains("BLAST") && doc.contains("NPE")
            },
        )
    }
}
