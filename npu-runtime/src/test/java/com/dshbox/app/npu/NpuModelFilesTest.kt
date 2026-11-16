package com.dshbox.app.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模型分段完整性校验的单元测试。
 *
 * 重点覆盖「字节数不符必须被发现」——历史上曾因为只判断「文件存在」，
 * 把 4B 模型的运行结果误记成 1B。
 */
class NpuModelFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(name: String, bytes: Int): File {
        val f = tmp.newFile(name)
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test
    fun `所有分段字节数匹配时通过`() {
        write("part1_of_4.bin", 100)
        write("part2_of_4.bin", 200)
        val r = NpuPreflight.checkModelFiles(
            mapOf("part1_of_4.bin" to 100L, "part2_of_4.bin" to 200L),
            tmp.root,
        )
        assertTrue(r.ok)
    }

    @Test
    fun `分段字节数不符时失败`() {
        write("part1_of_4.bin", 100)
        val r = NpuPreflight.checkModelFiles(
            mapOf("part1_of_4.bin" to 100L, "part2_of_4.bin" to 200L),
            tmp.root,
        )
        assertFalse(r.ok)
        assertTrue(r.fatal)
        assertTrue(r.message.contains("part2_of_4.bin"))
    }

    @Test
    fun `缺少分段时失败`() {
        write("part1_of_4.bin", 100)
        val r = NpuPreflight.checkModelFiles(
            mapOf("part1_of_4.bin" to 100L, "part2_of_4.bin" to 200L),
            tmp.root,
        )
        assertFalse(r.ok)
    }

    @Test
    fun `模型目录不存在时失败`() {
        val r = NpuPreflight.checkModelFiles(
            mapOf("part1_of_4.bin" to 100L),
            File(tmp.root, "nope"),
        )
        assertFalse(r.ok)
        assertTrue(r.fatal)
    }

    @Test
    fun `清单为空时失败`() {
        val r = NpuPreflight.checkModelFiles(emptyMap(), tmp.root)
        assertFalse(r.ok)
        assertTrue(r.fatal)
    }
}
