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

    private fun paths(): TerminalPaths = TerminalPaths(
        prootBinary = tmp.newFile("libproot.so"),
        prootLoader = tmp.newFile("libproot-loader.so"),
        nativeLibDir = tmp.newFolder("nativelib"),
        debianRootfs = File(tmp.root, "runtime-current/base"),
        nodeDir = File(tmp.root, "runtime-current/node"),
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
    fun `failsafe command is plain system shell`() {
        assertEquals(listOf("/system/bin/sh"), TerminalCommandFactory.failsafeShell())
    }
}
