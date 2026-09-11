package com.dshbox.app.ui.webview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 沙箱选择器目录越界判定单测（1.3.1 复查）。
 *
 * 修复前的判定是 `currentDir.absolutePath.startsWith(root.absolutePath)`，
 * 纯字符串前缀：`…/user-data-backup`、`…/user-data2` 这类**同前缀的兄弟目录**
 * 会被误判为仍在根内，点「返回上级」就会往上走出工作区、把 runtime 层暴露成
 * 上传目标。本测试锁定「路径段比较」这一语义。
 */
class SandboxPickerBoundaryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 根自身算「在根内」（返回上级时的边界允许停在根上）。 */
    @Test
    fun rootItselfIsWithin() {
        val root = tmp.newFolder("user-data")
        assertTrue(isWithinRoot(root, root.canonicalFile))
    }

    @Test
    fun nestedSubdirectoryIsWithin() {
        val root = tmp.newFolder("user-data")
        val nested = File(root, "a/b/c").apply { mkdirs() }
        assertTrue(isWithinRoot(nested, root.canonicalFile))
    }

    @Test
    fun parentOfRootIsOutside() {
        val root = tmp.newFolder("user-data")
        val parent = root.parentFile
        assertNotNull("TemporaryFolder 必有父目录", parent)
        assertFalse(isWithinRoot(parent!!, root.canonicalFile))
    }

    /**
     * 核心回归：同前缀兄弟目录必须判为越界。
     * `…/user-data-backup` 以 `…/user-data` 开头，字符串前缀判定会放行。
     */
    @Test
    fun siblingWithSamePrefixIsOutside() {
        val root = tmp.newFolder("user-data")
        val sibling = tmp.newFolder("user-data-backup")
        assertFalse("同前缀兄弟目录必须越界", isWithinRoot(sibling, root.canonicalFile))

        val sibling2 = tmp.newFolder("user-data2")
        assertFalse("同前缀兄弟目录必须越界", isWithinRoot(sibling2, root.canonicalFile))
    }

    /** 工作区里真实存在的 `.dsh` 子目录当然在根内（不是被名字骗到的那类）。 */
    @Test
    fun dshDataDirIsWithin() {
        val root = tmp.newFolder("user-data")
        val dsh = File(root, ".dsh").apply { mkdirs() }
        assertTrue(isWithinRoot(dsh, root.canonicalFile))
    }

    /** 符号链接指向根外时必须判为越界（canonical 解析的意义所在）。 */
    @Test
    fun symlinkPointingOutsideIsOutside() {
        val root = tmp.newFolder("user-data")
        val outside = tmp.newFolder("outside")
        val link = File(root, "escape")
        val created = runCatching {
            java.nio.file.Files.createSymbolicLink(
                link.toPath(),
                outside.toPath(),
            )
        }.isSuccess
        if (!created) return  // 无权限建链（Windows 非开发者模式）时跳过
        assertFalse("指向根外的符号链接必须越界", isWithinRoot(link, root.canonicalFile))
    }
}
