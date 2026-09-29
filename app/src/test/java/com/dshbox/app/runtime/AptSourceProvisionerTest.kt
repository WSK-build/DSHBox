package com.dshbox.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * apt 源幂等校验：出厂源失效时改写为官方源，用户自配的源不动。
 */
class AptSourceProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun baseWith(sources: String?): File {
        val base = tmp.newFolder("base")
        if (sources != null) {
            val f = File(base, "etc/apt/sources.list")
            f.parentFile!!.mkdirs()
            f.writeText(sources)
        }
        return base
    }

    @Test
    fun `rewrites the dead mirror shipped with the bundle`() {
        val base = baseWith("deb https://mirrors.tuna.tsinghua.edu.cn/debian trixie main\n")
        assertTrue(AptSourceProvisioner.provision(base))
        val text = File(base, "etc/apt/sources.list").readText()
        assertTrue(text.contains("https://deb.debian.org/debian"))
        assertFalse(text.contains("mirrors.tuna.tsinghua.edu.cn"))
    }

    @Test
    fun `writes a source when the file is missing`() {
        val base = baseWith(null)
        assertTrue(AptSourceProvisioner.provision(base))
        assertTrue(File(base, "etc/apt/sources.list").readText().contains("deb "))
    }

    @Test
    fun `keeps a user configured working mirror untouched`() {
        val custom = "deb https://mirrors.ustc.edu.cn/debian trixie main\n"
        val base = baseWith(custom)
        assertFalse(AptSourceProvisioner.provision(base))
        assertEquals(custom, File(base, "etc/apt/sources.list").readText())
    }

    @Test
    fun `keeps the official source untouched`() {
        val base = baseWith("deb https://deb.debian.org/debian trixie main\n")
        assertFalse(AptSourceProvisioner.provision(base))
    }

    @Test
    fun `does nothing when the base layer is absent`() {
        assertFalse(AptSourceProvisioner.provision(File(tmp.root, "missing")))
    }

    @Test
    fun `comment only file counts as needing a rewrite`() {
        assertTrue(AptSourceProvisioner.needsRewrite("# nothing here\n"))
        assertTrue(AptSourceProvisioner.needsRewrite(""))
        assertFalse(AptSourceProvisioner.needsRewrite("deb https://deb.debian.org/debian trixie main\n"))
        assertTrue(AptSourceProvisioner.needsRewrite("deb https://mirrors.bfsu.edu.cn/debian trixie main\n"))
    }
}
