package com.dshbox.app.sandbox.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrimSpecTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun trimsLocaleDocMan() {
        val rootfs = File(tmp.root, "rootfs")
        listOf(
            "usr/share/locale/en/LC_MESSAGES/a.mo",
            "usr/share/locale/fr/LC_MESSAGES/a.mo",
            "usr/share/locale/zh_CN/LC_MESSAGES/a.mo",
            "usr/share/locale/locale.alias",
            "usr/share/doc/hello/copyright",
            "usr/share/doc/hello/CHANGELOG",
            "usr/share/doc/other/copyright",
            "usr/share/doc/gone/README",
            "usr/share/man/man1/hello.1.gz",
        ).forEach { p ->
            val f = rootfs.resolve(p)
            f.parentFile.mkdirs()
            f.writeText("x")
        }

        val stats = TrimSpec(setOf("C", "C.UTF-8", "zh_CN", "zh_CN.utf8")).trim(rootfs)

        // locale：白名单外的目录删除，白名单内与 locale.alias 保留
        assertFalse(rootfs.resolve("usr/share/locale/en").exists())
        assertFalse(rootfs.resolve("usr/share/locale/fr").exists())
        assertTrue(rootfs.resolve("usr/share/locale/zh_CN/LC_MESSAGES/a.mo").isFile)
        assertTrue(rootfs.resolve("usr/share/locale/locale.alias").isFile)
        // doc：copyright 保留、其余文件删除，但目录保留（随包层里包声明的空目录也在）。
        assertTrue(rootfs.resolve("usr/share/doc/hello/copyright").isFile)
        assertTrue(rootfs.resolve("usr/share/doc/other/copyright").isFile)
        assertFalse(rootfs.resolve("usr/share/doc/hello/CHANGELOG").exists())
        assertTrue(rootfs.resolve("usr/share/doc/gone").isDirectory)
        // man：整体删除
        assertFalse(rootfs.resolve("usr/share/man").exists())

        assertEquals(2, stats.localeDirsRemoved)
        assertEquals(2, stats.docFilesRemoved)
        assertTrue(stats.manRemoved)
    }

    @Test
    fun toleratesMissingTrees() {
        val rootfs = File(tmp.root, "empty-rootfs").apply { mkdirs() }
        val stats = TrimSpec(setOf("C")).trim(rootfs)
        assertEquals(0, stats.localeDirsRemoved)
        assertEquals(0, stats.docFilesRemoved)
        assertFalse(stats.manRemoved)
    }
}
