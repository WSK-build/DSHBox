package com.dshbox.pluginmanager.safemode

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条目集合缓存的判定：**只有 profile 指纹完全一致才复用**，否则必须让调用方重跑命令。
 */
class AbsoluteTargetsCacheTest {

    private fun tempFile(): File =
        File(Files.createTempDirectory("cache").toFile(), "absolute-targets.json")

    private val fp = AbsoluteTargetsCache.Fingerprint(manifestModified = 100L, nodeModulesModified = 200L)

    @Test
    fun `写入后同指纹可读回`() {
        val cache = AbsoluteTargetsCache(tempFile())
        assertTrue(cache.write(fp, listOf("univer", "dsh-market")))
        assertEquals(listOf("univer", "dsh-market"), cache.read(fp))
    }

    @Test
    fun `清单变化后缓存失效`() {
        val cache = AbsoluteTargetsCache(tempFile())
        cache.write(fp, listOf("univer"))
        assertNull(cache.read(fp.copy(manifestModified = 101L)))
    }

    @Test
    fun `node_modules 变化后缓存失效`() {
        val cache = AbsoluteTargetsCache(tempFile())
        cache.write(fp, listOf("univer"))
        assertNull(cache.read(fp.copy(nodeModulesModified = 201L)))
    }

    @Test
    fun `缓存文件损坏或缺失时返回空`() {
        val file = tempFile()
        assertNull(AbsoluteTargetsCache(file).read(fp))
        file.writeText("{ not json", Charsets.UTF_8)
        assertNull(AbsoluteTargetsCache(file).read(fp))
    }

    @Test
    fun `空集合也会被缓存（0 个第三方条目是合法结果）`() {
        val cache = AbsoluteTargetsCache(tempFile())
        assertTrue(cache.write(fp, emptyList()))
        assertEquals(emptyList<String>(), cache.read(fp))
    }
}
