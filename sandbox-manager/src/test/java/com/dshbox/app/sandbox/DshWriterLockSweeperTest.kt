package com.dshbox.app.sandbox

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 写者锁清理的判定纪律：**只删"持有者已不存在"的锁**，其余一律不动。
 *
 * 这些用例固定的是真机上验证过的机制：`dsh-atomic-write` 的锁文件内容就是持有者 PID，
 * 持有者被强杀后锁永久留下（`.credentials.yaml.lock` 曾因此让 DSH 完全无法启动）；
 * 强杀若落在创建锁与写入 PID 之间，留下的是 0 字节锁。
 */
class DshWriterLockSweeperTest {

    private val cutoff = 1_800_000_000_000L

    private fun workspace(): File = Files.createTempDirectory("ws").toFile()

    private fun lock(dir: File, relative: String, content: String): File {
        val file = File(dir, relative)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    private fun emptyLock(dir: File, relative: String, modifiedAt: Long): File {
        val file = lock(dir, relative, "")
        assertTrue(file.setLastModified(modifiedAt))
        return file
    }

    private fun sweeper(
        ws: File,
        isProcessAlive: (Long) -> Boolean = { false },
        selfCheck: Boolean = true,
    ) = DshWriterLockSweeper(
        ws,
        isProcessAlive = isProcessAlive,
        selfCheck = { selfCheck },
    )

    private fun sweep(
        ws: File,
        isProcessAlive: (Long) -> Boolean = { false },
        selfCheck: Boolean = true,
    ) = sweeper(ws, isProcessAlive, selfCheck).sweep()

    @Test
    fun `持有者已不存在时删除`() {
        val ws = workspace()
        val stale = lock(ws, ".dsh/.credentials.yaml.lock", "18374\n")
        val result = sweep(ws)
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
        val result = sweep(ws, isProcessAlive = { it == 4242L })
        assertTrue(alive.exists())
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `内容不是纯 PID 时保留（不猜）`() {
        val ws = workspace()
        val odd = lock(ws, ".dsh/weird.lock", "pid=123 held\n")
        val result = sweep(ws)
        assertTrue(odd.exists())
        assertTrue(result.removed.isEmpty())
        assertEquals(1, result.scanned)
    }

    @Test
    fun `会话目录里的锁也会被覆盖`() {
        val ws = workspace()
        val nested = lock(ws, ".dsh/sessions/-root-projects-ws-/session-abc/session.lock", "777\n")
        sweep(ws)
        assertTrue(nested.exists().not())
    }

    @Test
    fun `判定链路不可用时一把都不删`() {
        val ws = workspace()
        val stale = lock(ws, ".dsh/.credentials.yaml.lock", "18374\n")
        val empty = emptyLock(ws, ".dsh/settings.yaml.lock", cutoff - 60_000)
        val result = sweep(ws, selfCheck = false)
        assertTrue(stale.exists())
        assertTrue(empty.exists())
        assertTrue(result.skipped)
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `非锁文件一律不碰`() {
        val ws = workspace()
        val credentials = lock(ws, ".dsh/.credentials.yaml", "version: 1\nrecords: {}\n")
        val keep = lock(ws, ".dsh/some-notes.txt", "123\n")
        val emptyOther = emptyLock(ws, ".dsh/empty.txt", cutoff - 60_000)
        sweep(ws)
        assertTrue(credentials.exists())
        assertTrue(keep.exists())
        assertTrue(emptyOther.exists())
    }

    @Test
    fun `没有内核恢复器时再旧的空锁也保留`() {
        val ws = workspace()
        val old = emptyLock(ws, ".dsh/.credentials.yaml.lock", cutoff - 86_400_000)
        assertTrue(sweep(ws).removed.isEmpty())
        assertTrue(old.exists())
    }

    @Test
    fun `内核确认可回收时空锁恢复不依赖修改时间`() {
        for (mtime in listOf(0L, cutoff, cutoff + 86_400_000)) {
            val ws = workspace()
            val file = emptyLock(ws, ".dsh/.credentials.yaml.lock", mtime)
            val result = DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { true },
                recoverEmpty = { it.delete() }).sweep()
            assertTrue(!file.exists())
            assertEquals(1, result.removed.size)
        }
    }

    @Test
    fun `判定界限后新建的空锁保留`() {
        val ws = workspace()
        val fresh = emptyLock(ws, ".dsh/.credentials.yaml.lock", cutoff + 1_000)
        val result = sweep(ws)
        assertTrue(fresh.exists())
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `修改时间不可读的空锁保留`() {
        val ws = workspace()
        val file = lock(ws, ".dsh/.credentials.yaml.lock", "")
        assumeTrue(file.setLastModified(0L) && file.lastModified() == 0L)
        sweep(ws)
        assertTrue(file.exists())
    }

    @Test
    fun `只有空白字符的锁不按空锁处理`() {
        val ws = workspace()
        val blank = lock(ws, ".dsh/.credentials.yaml.lock", "\n")
        assertTrue(blank.setLastModified(cutoff - 60_000))
        sweep(ws)
        assertTrue(blank.exists())
    }

    @Test
    fun `混合场景只删孤儿空锁与死持有者锁`() {
        val ws = workspace()
        val orphanEmpty = emptyLock(ws, ".dsh/a.yaml.lock", cutoff - 1_000)
        val freshEmpty = emptyLock(ws, ".dsh/b.yaml.lock", cutoff + 1_000)
        val alive = lock(ws, ".dsh/c.yaml.lock", "4242\n")
        val dead = lock(ws, ".dsh/d.yaml.lock", "18374\n")
        val odd = lock(ws, ".dsh/e.yaml.lock", "held\n")
        val result = sweep(ws, isProcessAlive = { it == 4242L })
        assertTrue(orphanEmpty.exists())
        assertTrue(dead.exists().not())
        assertTrue(freshEmpty.exists())
        assertTrue(alive.exists())
        assertTrue(odd.exists())
        assertEquals(5, result.scanned)
        assertEquals(1, result.removed.size)
    }

    @Test
    fun `不进入符号链接目录`() {
        val ws = workspace()
        val outside = workspace()
        val foreign = lock(outside, "plugin/state.lock", "18374\n")
        File(ws, ".dsh").mkdirs()
        val linked = runCatching {
            Files.createSymbolicLink(File(ws, ".dsh/linked").toPath(), File(outside, "plugin").toPath())
        }.isSuccess
        assumeTrue("当前环境不允许创建符号链接", linked)
        val result = sweep(ws)
        assertTrue(foreign.exists())
        assertEquals(0, result.scanned)
    }

    @Test
    fun `不进入插件依赖目录`() {
        val ws = workspace()
        val packaged = emptyLock(ws, ".dsh/plugins/demo/node_modules/pkg/yarn.lock", cutoff - 60_000)
        val deadInDeps = lock(ws, ".dsh/plugins/demo/node_modules/pkg/run.lock", "18374\n")
        val pluginLock = lock(ws, ".dsh/plugins/demo/state.lock", "18374\n")
        val result = sweep(ws)
        assertTrue(packaged.exists())
        assertTrue(deadInDeps.exists())
        assertTrue(pluginLock.exists().not())
        assertEquals(1, result.scanned)
    }

    @Test
    fun `内核拒绝租约时空锁保留且不回退到时间判定`() {
        val ws = workspace()
        val file = emptyLock(ws, ".dsh/.credentials.yaml.lock", cutoff - 86_400_000)
        var calls = 0
        val result = DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { true },
            recoverEmpty = { calls++; false }).sweep()
        assertEquals(1, calls)
        assertTrue(file.exists())
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `链路不可用不调用空锁恢复器`() {
        val ws = workspace()
        emptyLock(ws, ".dsh/.credentials.yaml.lock", cutoff)
        val result = DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { false },
            recoverEmpty = { error("must not recover") }).sweep()
        assertTrue(result.skipped)
    }

    @Test
    fun `非空内容不调用空锁恢复器`() {
        val ws = workspace()
        lock(ws, ".dsh/unknown.lock", "not-pid")
        lock(ws, ".dsh/alive.lock", "4242\n")
        val result = DshWriterLockSweeper(ws, isProcessAlive = { true }, selfCheck = { true },
            recoverEmpty = { error("must not recover") }).sweep()
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun `不进入 git 仓库元数据目录`() {
        val ws = workspace()
        val index = emptyLock(ws, ".dsh/plugins/demo/.git/index.lock", cutoff - 60_000)
        val ref = lock(ws, ".dsh/plugins/demo/.git/refs/heads/main.lock", "18374\n")
        val result = sweep(ws)
        assertTrue(index.exists())
        assertTrue(ref.exists())
        assertEquals(0, result.scanned)
    }

    @Test
    fun `尚未写完的 PID 不当成死写者`() {
        val ws = workspace()
        val partial = lock(ws, ".dsh/.credentials.yaml.lock", "42")
        assertTrue(sweep(ws).removed.isEmpty())
        assertTrue(partial.exists())
    }

    @Test
    fun `未知空标记不交给恢复器`() {
        val ws = workspace()
        val unknown = lock(ws, ".dsh/plugin-marker.lock", "")
        val result = DshWriterLockSweeper(ws, isProcessAlive = { false }, selfCheck = { true },
            recoverEmpty = { error("must not recover unknown marker") }).sweep()
        assertTrue(result.removed.isEmpty())
        assertTrue(unknown.exists())
    }

    @Test
    fun `没有 dsh 目录时安全返回`() {
        val ws = workspace()
        val result = sweep(ws)
        assertEquals(0, result.scanned)
        assertTrue(result.removed.isEmpty())
    }
}
