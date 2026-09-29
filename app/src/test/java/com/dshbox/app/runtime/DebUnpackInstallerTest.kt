package com.dshbox.app.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 解包安装入口的纯逻辑：命令构造的边界（绝不写账本）、输出解析、清单追加。
 */
class DebUnpackInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun onlyDebNamesAreInstallable() {
        assertTrue(DebUnpackInstaller.isDeb("htop_3.3.0_arm64.deb"))
        assertTrue(DebUnpackInstaller.isDeb("FOO.DEB"))
        assertFalse(DebUnpackInstaller.isDeb("htop.tar.gz"))
        assertFalse(DebUnpackInstaller.isDeb("deb"))
        assertFalse(DebUnpackInstaller.isDeb("notes.deb.txt"))
    }

    @Test
    fun pathIsSingleQuotedAndQuotesAreEscaped() {
        assertEquals("'/tmp/a.deb'", DebUnpackInstaller.shellQuote("/tmp/a.deb"))
        assertEquals("'/tmp/a b.deb'", DebUnpackInstaller.shellQuote("/tmp/a b.deb"))
        assertEquals("'/tmp/it'\\''s.deb'", DebUnpackInstaller.shellQuote("/tmp/it's.deb"))
    }

    @Test
    fun commandNeverTouchesTheDpkgLedger() {
        val cmd = DebUnpackInstaller.buildCommand("/root/projects/tool.deb")
        // 账本是「整层组装方」的产物：本入口只摊数据部分，两个写入方共用一个库必然出错。
        assertFalse(cmd.contains("dpkg -i"))
        assertFalse(cmd.contains("--unpack"))
        assertFalse(cmd.contains("--configure"))
        assertFalse(cmd.contains("var/lib/dpkg"))
        assertFalse(cmd.contains("apt-get"))
    }

    @Test
    fun commandUnpacksDataAndRefreshesLibraryCache() {
        val cmd = DebUnpackInstaller.buildCommand("/root/projects/tool.deb")
        assertTrue(cmd.contains("dpkg-deb --fsys-tarfile '/root/projects/tool.deb'"))
        assertTrue(cmd.contains("tar -x --no-same-owner -p -C /"))
        assertTrue(cmd.contains("ldconfig"))
        // 控制字段与条目数用于清单，缺失也不该让解包失败。两个真机实测要点：
        // ① 字段名必须显式给出（不带字段名的 -f 在本环境不输出内容）；
        // ② TMPDIR 必须指向 guest 的 /tmp——`-f` 要解到临时目录，宿主注入的环境里
        //    TMPDIR 在 rootfs 内不存在，会以"unable to create temporary directory"失败。
        assertTrue(cmd.contains("TMPDIR=/tmp dpkg-deb -f '/root/projects/tool.deb' Package Version Architecture"))
        // 失败原因要能落进报告，所以这一处不屏蔽 stderr。
        assertFalse(cmd.contains("dpkg-deb -f '/root/projects/tool.deb' Package Version Architecture 2>/dev/null"))
        assertTrue(cmd.contains(DebUnpackInstaller.MARK_OK))
    }

    @Test
    fun reportCommandWritesTheConclusionIntoTheWorkspace() {
        val cmd = DebUnpackInstaller.buildReportCommand("/root/projects/tool.deb")
        // 目录先建，报告路径固定在工作区我们自己的资产目录下（宿主侧可读同一份）。
        assertTrue(cmd.contains("mkdir -p '/root/projects/.dsh/dshbox'"))
        assertTrue(cmd.contains("> '/root/projects/.dsh/dshbox/deb-install.report' 2>&1"))
        assertTrue(cmd.contains("dpkg-deb --fsys-tarfile"))
    }

    @Test
    fun realControlOutputIsParsedByFieldPrefix() {
        // 真机实测形态：`dpkg-deb -f` 打印 `字段: 值`，且维护脚本缺字段时会在 stdout 前
        // 插一条**含空格的警告**；多行 Description 的续行也可能带前导空格。
        val out = listOf(
            "dpkg-deb: warning: parsing file '/tmp/dpkg-deb.PNf0vh/control' near line 5",
            " package 'dshbox-debtest':",
            "missing 'Maintainer' field",
            DebUnpackInstaller.MARK_CONTROL,
            "Package: dshbox-debtest",
            "Version: 0.1",
            "Architecture: arm64",
            "Description: test",
            " Package: 这不是字段",
            DebUnpackInstaller.MARK_ENTRIES,
            "6",
            DebUnpackInstaller.MARK_OK,
        )
        val info = DebUnpackInstaller.parseReport(out)
        assertNotNull(info)
        assertEquals("dshbox-debtest", info!!.name)
        assertEquals("0.1", info.version)
        assertEquals("arm64", info.architecture)
        assertTrue(info.unpacked)
    }

    @Test
    fun missingFieldIsNotAConclusion() {
        val out = listOf(
            DebUnpackInstaller.MARK_CONTROL,
            "Package: x",
            "Architecture: arm64",
            DebUnpackInstaller.MARK_ENTRIES,
            "1",
            DebUnpackInstaller.MARK_OK,
        )
        assertNull(DebUnpackInstaller.parseReport(out))
    }

    @Test
    fun reportIsParsedFromMarkedOutput() {
        val out = listOf(
            DebUnpackInstaller.MARK_CONTROL,
            "Package: ripgrep",
            "Version: 14.1.0-1",
            "Architecture: arm64",
            DebUnpackInstaller.MARK_ENTRIES,
            "17",
            DebUnpackInstaller.MARK_OK,
        )
        val info = DebUnpackInstaller.parseReport(out)
        assertNotNull(info)
        assertEquals("ripgrep", info!!.name)
        assertEquals("14.1.0-1", info.version)
        assertEquals("arm64", info.architecture)
        assertEquals(17, info.entries)
        assertTrue(info.unpacked)
    }

    @Test
    fun failureMarkerMeansNotUnpacked() {
        val out = listOf(
            DebUnpackInstaller.MARK_CONTROL,
            "Package: broken",
            "Version: 0.1",
            "Architecture: arm64",
            DebUnpackInstaller.MARK_ENTRIES,
            "3",
            "tar: unexpected EOF",
            DebUnpackInstaller.MARK_FAIL,
        )
        val info = DebUnpackInstaller.parseReport(out)
        assertNotNull(info)
        assertFalse(info!!.unpacked)
    }

    @Test
    fun reportWithoutMarkersIsRejected() {
        assertNull(DebUnpackInstaller.parseReport(emptyList()))
        assertNull(DebUnpackInstaller.parseReport(listOf("bash: dpkg-deb: command not found")))
        // 有控制字段但没有结论（命令被中途打断）——不当作成功。
        assertNull(
            DebUnpackInstaller.parseReport(
                listOf(
                    DebUnpackInstaller.MARK_CONTROL,
                    "Package: x",
                    "Version: 1",
                    "Architecture: arm64",
                    DebUnpackInstaller.MARK_ENTRIES,
                    "1",
                ),
            ),
        )
        // 控制字段不完整（不是 deb）——不记录清单。
        assertNull(
            DebUnpackInstaller.parseReport(
                listOf(
                    DebUnpackInstaller.MARK_CONTROL,
                    "dpkg-deb: error: no such file",
                    DebUnpackInstaller.MARK_ENTRIES,
                    "0",
                    DebUnpackInstaller.MARK_FAIL,
                ),
            ),
        )
    }

    @Test
    fun inventoryAppendsOneTabSeparatedLinePerInstall() {
        val dir = File(tmp.root, "inventory")
        val info = DebUnpackInstaller.DebInfo("ripgrep", "14.1.0-1", "arm64", 17, true)
        DebUnpackInstaller.appendInventory(dir, info, "ripgrep.deb", nowMs = 1234L)
        DebUnpackInstaller.appendInventory(dir, info, "again.deb", nowMs = 5678L)
        val lines = DebUnpackInstaller.inventoryFile(dir).readLines()
        assertEquals(2, lines.size)
        assertEquals("1234\tripgrep\t14.1.0-1\tarm64\t17\ttrue\tripgrep.deb", lines[0])
        assertTrue(lines[1].startsWith("5678\t"))
    }
}
