package com.dshbox.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalEnvFactoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun paths(): TerminalPaths = TerminalPaths(
        prootBinary = tmp.newFile("libproot.so"),
        prootLoader = tmp.newFile("loader"),
        nativeLibDir = tmp.newFolder("nativelib"),
        debianRootfs = tmp.newFolder("base"),
        nodeDir = tmp.newFolder("node"),
        workspaceBind = tmp.newFolder("user-data"),
        prootTmpDir = tmp.newFolder("proot-tmp"),
        failsafeHome = tmp.newFolder("home"),
        failsafeTmpDir = tmp.newFolder("hometmp"),
    )

    @Test
    fun `sandbox env is complete for clearenv semantics`() {
        val env = TerminalEnvFactory.sandboxEnv(paths()).associate {
            val idx = it.indexOf('=')
            it.take(idx) to it.substring(idx + 1)
        }
        // Host side: required by proot itself.
        assertNotNull(env["LD_LIBRARY_PATH"])
        assertNotNull(env["PROOT_LOADER"])
        assertNotNull(env["PROOT_TMP_DIR"])
        // Guest side: minimum viable shell environment.
        assertEquals("/root", env["HOME"])
        assertEquals("xterm-256color", env["TERM"])
        assertTrue(env["PATH"]!!.contains("/usr/bin"))
        assertNotNull(env["LANG"])
        assertNotNull(env["TMPDIR"])
    }

    @Test
    fun `failsafe env has writable home and system path`() {
        val p = paths()
        val env = TerminalEnvFactory.failsafeEnv(p).associate {
            val idx = it.indexOf('=')
            it.take(idx) to it.substring(idx + 1)
        }
        assertEquals(p.failsafeHome.absolutePath, env["HOME"])
        assertEquals("/system/bin:/system/xbin:/vendor/bin", env["PATH"])
        assertEquals("xterm-256color", env["TERM"])
    }
}
