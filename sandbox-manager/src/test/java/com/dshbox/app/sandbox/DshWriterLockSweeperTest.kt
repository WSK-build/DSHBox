package com.dshbox.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 写者锁清理的判定纪律：**只删"持有者已不存在"的锁**，其余一律不动。
 *
 * 这些用例固定的是真机上验证过的机制：`dsh-atomic-write` 的锁文件内容就是持有者 PID，
 * 持有者被强杀后锁永久留下（`.credentials.yaml.lock` 曾因此让 DSH 完全无法启动）。
 */
class DshWriterLockSweeperTest {

    private fun workspace(): File = Files.createTempDirectory("ws").toFile()

    private fun lock(dir: File, relative: String, content: String): File {
        val file = File(dir, relative)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    @Test
    fun `持有者已不存在时删除`() {
        val ws = workspace()
        val stale = lock(ws, ".dsh/.credentials.yaml.lock", "18374\n")
        val result = DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { true }).sweep()
        assertTrue(stale.exists().not())
        // 路径分隔符随平台（JVM 单测在桌面、真机在 Android），只断言形态。
        assertEquals(1, result.removed.size)
        assertTrue(result.removed.single().startsWith(".dsh"))
        assertTrue(result.removed.single().endsWith(".credentials.yaml.lock"))
        assertEquals(1, result.scanned)
    }

    @Test
    fun `持有者仍在时保留`() {
        val ws = workspace()
        val alive = lock(ws, ".dsh/.credentials.yaml.lock", "4242\n")
        val result = DshWriterLockSweeper(ws, isProcessAlive = { it == 4242L }, selfCheck = { true }).sweep()
        assertTrue(alive.exists())
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `内容不是纯 PID 时保留（不猜）`() {
        val ws = workspace()
        val odd = lock(ws, ".dsh/weird.lock", "pid=123 held\n")
        val result = DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { true }).sweep()
        assertTrue(odd.exists())
        assertTrue(result.removed.isEmpty())
        assertEquals(1, result.scanned)
    }

    @Test
    fun `会话目录里的锁也会被覆盖`() {
        val ws = workspace()
        val nested = lock(ws, ".dsh/sessions/-root-projects-ws-/session-abc/session.lock", "777\n")
        DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { true }).sweep()
        assertTrue(nested.exists().not())
    }

    @Test
    fun `判定链路不可用时一把都不删`() {
        val ws = workspace()
        val stale = lock(ws, ".dsh/.credentials.yaml.lock", "18374\n")
        val result = DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { false }).sweep()
        assertTrue(stale.exists())
        assertTrue(result.skipped)
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `非锁文件一律不碰`() {
        val ws = workspace()
        val credentials = lock(ws, ".dsh/.credentials.yaml", "version: 1\nrecords: {}\n")
        val keep = lock(ws, ".dsh/some-notes.txt", "123\n")
        DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { true }).sweep()
        assertTrue(credentials.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun `没有 dsh 目录时安全返回`() {
        val ws = workspace()
        val result = DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { true }).sweep()
        assertEquals(0, result.scanned)
        assertTrue(result.removed.isEmpty())
    }
}
