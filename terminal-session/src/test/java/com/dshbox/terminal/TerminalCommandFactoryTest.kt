package com.dshbox.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalCommandFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * 默认的 [pilotDir] **故意不预先创建**：首次启动时宿主目录还不存在，
     * 绑定那一步的 mkdirs 正要在这种状态下生效，预建就会把该行为测没。
     */
    private fun paths(pilotDir: File? = File(tmp.root, "pilot")): TerminalPaths = TerminalPaths(
        prootBinary = tmp.newFile("libproot.so"),
        prootLoader = tmp.newFile("libproot-loader.so"),
        nativeLibDir = tmp.newFolder("nativelib"),
        debianRootfs = File(tmp.root, "runtime-current/base"),
        nodeDir = File(tmp.root, "runtime-current/node"),
        pilotDir = pilotDir,
        workspaceBind = tmp.newFolder("user-data"),
        prootTmpDir = tmp.newFolder("proot-tmp"),
        failsafeHome = tmp.newFolder("home"),
        failsafeTmpDir = tmp.newFolder("hometmp"),
    )

    @Test
    fun `sandbox command repeats program path as argv0`() {
        val p = paths()
        assertEquals(p.prootBinary.absolutePath, TerminalCommandFactory.sandboxLoginShell(p).first())
    }

    @Test
    fun `normal sandbox command execs guest bash directly`() {
        val p = paths()
        val argv = TerminalCommandFactory.sandboxLoginShell(p)
        assertEquals(listOf("/usr/bin/bash", "--login"), argv.takeLast(2))
        assertTrue(argv.contains("--kill-on-exit"))
        assertTrue(argv.contains("-0"))
        assertTrue(argv.any { it.startsWith("--rootfs=") })
        assertTrue(argv.any { it == "--bind=${p.workspaceBind.absolutePath}:/root/projects" })
    }

    @Test
    fun `overlay session runs snippet then bash login via wrapper and clears screen`() {
        val p = paths()
        val argv = TerminalCommandFactory.sandboxLoginShell(p, "do_stuff; ")
        assertEquals(listOf("/system/bin/sh", "-c", "do_stuff; exec /usr/bin/bash --login"), argv.takeLast(3))
    }

    @Test
    fun `sandbox command binds the pilot entry and creates the host dir`() {
        val p = paths()
        val bind = "--bind=${p.pilotDir!!.absolutePath}:/opt/pilot"
        assertTrue(TerminalCommandFactory.sandboxLoginShell(p).contains(bind))
        // 宿主目录由绑定这一步自己建：proot 对不存在的 bind 源整条命令都会失败，
        // 而首次启动时 RelayRuntime 还没铺过资产，目录本就不存在。
        assertTrue(p.pilotDir!!.isDirectory)
    }

    @Test
    fun `pilot bind is skipped when the entry dir is absent`() {
        val argv = TerminalCommandFactory.sandboxLoginShell(paths(pilotDir = null))
        assertTrue(argv.none { it.contains("/opt/pilot") })
    }

    @Test
    fun `failsafe command is plain system shell`() {
        assertEquals(listOf("/system/bin/sh"), TerminalCommandFactory.failsafeShell())
    }
}
