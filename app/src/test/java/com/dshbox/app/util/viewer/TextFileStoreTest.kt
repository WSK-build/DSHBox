package com.dshbox.app.util.viewer

import com.dshbox.app.R
import com.dshbox.app.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 文本保存链路回归（返工修正 #4/#5 锁定）：新建文件不走 ExternalChanged；
 * 外部变更检测（内容指纹）；元数据警告透传机制（跨平台可验证路径）。
 */
class TextFileStoreTest {

    private fun tempFile(bytes: ByteArray? = null): File =
        File.createTempFile("store_test", ".bin").apply {
            bytes?.let { writeBytes(it) }
            deleteOnExit()
        }

    @Test
    fun newFileSaveSucceedsWithoutForce() {
        // 返工修正 #5：新建文件（expected=null 且目标不存在）直接写入，
        // 不得被判 ExternalChanged(null)
        val target = File(tempDir(), "brand-new-${System.nanoTime()}.txt")
        val outcome = TextFileStore.save(target, "hello".toByteArray(), expected = null)
        assertTrue("actual=$outcome", outcome is TextFileStore.SaveOutcome.Success)
        target.delete()
    }

    @Test
    fun unchangedContentSavesDirectly() {
        val f = tempFile("content".toByteArray())
        val loaded = TextFileStore.load(f)!!
        val outcome = TextFileStore.save(f, "changed".toByteArray(), expected = loaded.fingerprint)
        assertTrue(outcome is TextFileStore.SaveOutcome.Success)
        assertEquals("changed", f.readText())
    }

    @Test
    fun externalChangeDetectedBeforeSave() {
        val f = tempFile("v1".toByteArray())
        val loaded = TextFileStore.load(f)!!
        // 外部并发修改：内容变化使首/尾哈希不同（无需等待 mtime 粒度）
        f.writeText("v1-external")
        val outcome = TextFileStore.save(f, "mine".toByteArray(), expected = loaded.fingerprint)
        assertTrue(outcome is TextFileStore.SaveOutcome.ExternalChanged)
        assertEquals("v1-external", f.readText()) // 未写入
        // force 覆盖成功
        val forced = TextFileStore.save(f, "mine".toByteArray(), expected = loaded.fingerprint, force = true)
        assertTrue(forced is TextFileStore.SaveOutcome.Success)
        assertEquals("mine", f.readText())
    }

    @Test
    fun externallyDeletedFileReportsExternalChanged() {
        val f = tempFile("gone".toByteArray())
        val loaded = TextFileStore.load(f)!!
        f.delete()
        val outcome = TextFileStore.save(f, "x".toByteArray(), expected = loaded.fingerprint)
        val external = outcome as TextFileStore.SaveOutcome.ExternalChanged
        assertNull(external.current) // current == null 表示「文件已不存在」
    }

    @Test
    fun metadataPreservedOnReplace() {
        val f = tempFile("abc".toByteArray())
        f.setLastModified(1_000_000_000)
        val wasExecutable = f.canExecute()
        val loaded = TextFileStore.load(f)!!
        val outcome = TextFileStore.save(f, "abcdef".toByteArray(), expected = loaded.fingerprint)
        val success = outcome as TextFileStore.SaveOutcome.Success
        // 本机文件系统应完整恢复（无警告）
        assertNull(success.warning)
        assertEquals(1_000_000_000, f.lastModified())
        // Windows 上 canExecute 恒 false（无 POSIX 位语义），两种值都算恢复成功
        assertEquals(wasExecutable, f.canExecute())
    }

    @Test
    fun loadCleansStaleTmpResidue() {
        val f = tempFile("data".toByteArray())
        val stale = File(f.parentFile, f.name + TextFileStore.TEMP_SUFFIX).apply { writeText("half") }
        val loaded = TextFileStore.load(f)
        assertNotNull(loaded)
        assertFalse(stale.exists())
    }

    @Test
    fun failedOutcomeHasUiTextReason() {
        // 父路径是已存在「文件」时 mkdirs 必失败 → 稳定触发目录不可用分支：
        // reason 应为 UiText.Res（。
        val blocker = File(tempDir(), "blocker-${System.nanoTime()}.txt").apply { writeText("x") }
        val badPath = File(blocker, "file.txt")
        val outcome = TextFileStore.save(badPath, "data".toByteArray(), expected = null)
        assertTrue("actual=$outcome", outcome is TextFileStore.SaveOutcome.Failed)
        val failed = outcome as TextFileStore.SaveOutcome.Failed
        assertTrue("reason should be UiText.Res", failed.reason is UiText.Res)
        assertEquals(R.string.textstore_err_dir_unavailable, (failed.reason as UiText.Res).id)
    }

    private fun tempDir(): File {
        val d = File(System.getProperty("java.io.tmpdir"))
        assertTrue(d.isDirectory)
        return d
    }
}
