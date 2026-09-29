package com.dshbox.app.util

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [PackageToolProbe] 的进程表判定。
 *
 * 用临时目录伪造 `/proc`：每个进程一个数字命名的子目录，`cmdline` 为 NUL 分隔的 argv。
 */
class PackageToolProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 伪造的 /proc（TemporaryFolder 在 @Before 之前已就绪，不能写成属性初始化器）。 */
    private lateinit var proc: File

    @Before
    fun setUp() {
        proc = File(tmp.root, "proc").apply { mkdirs() }
    }

    private fun proc(pid: Int, vararg argv: String) {
        val dir = File(proc, pid.toString()).apply { mkdirs() }
        File(dir, "cmdline").writeBytes(argv.joinToString("\u0000", postfix = "\u0000").toByteArray())
    }

    private fun proc(pid: Int, raw: ByteArray) {
        val dir = File(proc, pid.toString()).apply { mkdirs() }
        File(dir, "cmdline").writeBytes(raw)
    }

    @Test
    fun `apt and dpkg variants are detected`() {
        proc(101, "/usr/bin/apt-get", "install", "vim")
        assertTrue(PackageToolProbe.isRunning(proc))
    }

    @Test
    fun `dpkg-deb and unpack subprocesses are detected`() {
        proc(202, "/usr/bin/dpkg-deb", "--fsys-tarfile", "x.deb")
        assertTrue(PackageToolProbe.isRunning(proc))

        val other = File(tmp.root, "proc2").apply { mkdirs() }
        val dir = File(other, "203").apply { mkdirs() }
        File(dir, "cmdline").writeBytes("/usr/lib/dpkg/unpack\u0000---\u0000".toByteArray())
        assertTrue(PackageToolProbe.isRunning(other))
    }

    @Test
    fun `unrelated processes alone are not detected`() {
        proc(301, "/bin/bash", "-l")
        proc(302, "/opt/dshapp/runtime/bin/node", "bin.js")
        proc(303, "/system/bin/app_process64", "com.dshbox.app")
        assertFalse(PackageToolProbe.isRunning(proc))
    }

    @Test
    fun `non-package tool whose name merely contains apt is not detected`() {
        // 前缀匹配而非子串匹配：`adapt` / `capture` 不该被当成 apt。
        proc(401, "/usr/bin/adapt")
        proc(402, "/usr/bin/capture")
        assertFalse(PackageToolProbe.isRunning(proc))
    }

    @Test
    fun `non-numeric entries and empty cmdline are ignored`() {
        File(proc, "self").apply { mkdirs() }
        File(proc, "sys").apply { mkdirs() }
        proc(501, ByteArray(0))
        assertFalse(PackageToolProbe.isRunning(proc))
    }

    @Test
    fun `missing proc root reports not running`() {
        assertFalse(PackageToolProbe.isRunning(File(tmp.root, "no-such-proc")))
    }
}
