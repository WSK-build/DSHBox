package com.dshbox.app.service

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置 corepack 缓存包展开器的边界钉子。
 *
 * **P0 回归用例居首**：tar 的 512 对齐余量只由内容长度决定（头固定 512 字节且
 * 已读毕）。历史缺陷把余量算成 `136 + size`（136 是 size 字段的起始偏移，不是
 * 块基数），首个目录条目（size=0）之后指针错位、整个包解出 0 个文件却报成功——
 * 空缓存被就绪标记永久锁死。`对齐算式` 一组用例把正确算式与该缺陷形态一起钉死。
 *
 * 输入用手工构造的 tar 字节（无需压缩：[CorepackCacheUnpacker.unpackBytes] 走
 * 已解压面，压缩层由 zstd-jni 承担，不在本测试范围）。
 */
class CorepackCacheUnpackerTest {

    private val roots = mutableListOf<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("unpacker-test").toFile().also { roots.add(it) }

    // ------------------------------------------------------------ tar 构造工具

    private fun header(name: String, size: Long, type: Char = '0'): ByteArray {
        val h = ByteArray(512)
        name.toByteArray(Charsets.US_ASCII).copyInto(h, 0, 0, minOf(name.length, 100))
        "%011o".format(size).toByteArray(Charsets.US_ASCII).copyInto(h, 124)
        h[156] = type.code.toByte()
        // 校验和字段必须合法吗？本解析器不校验 checksum（资产来自 APK 自身），
        // 这里写全零即如实反映被测行为——如果未来加校验，此构造器会提醒更新。
        return h
    }

    private fun entry(name: String, content: ByteArray, type: Char = '0'): ByteArray {
        val head = header(name, content.size.toLong(), type)
        val out = head + content
        val pad = (512 - content.size % 512) % 512
        return out + ByteArray(pad)
    }

    private fun entry(name: String, content: String): ByteArray =
        entry(name, content.toByteArray(Charsets.UTF_8))

    private val endBlocks = ByteArray(1024)

    // ---------------------------------------------------------------- 成功路径

    @Test
    fun `普通文件与目录条目全部落盘`() {
        val dest = tempDir()
        val tar = entry("./v1/pnpm/11.1.2/", ByteArray(0), '5') +
            entry("./lastKnownGood.json", "{\"pnpm\": \"11.1.2\"}") +
            entry("./v1/pnpm/11.1.2/bin/pnpm.cjs", "// entry".toByteArray()) +
            endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))

        assertEquals("{\"pnpm\": \"11.1.2\"}", File(dest, "lastKnownGood.json").readText())
        assertEquals("// entry", File(dest, "v1/pnpm/11.1.2/bin/pnpm.cjs").readText())
        assertTrue(File(dest, "v1/pnpm/11.1.2").isDirectory)
    }

    @Test
    fun `size 恰为 512 倍数与非倍数都正确对齐`() {
        val dest = tempDir()
        val exact = "x".repeat(1024)          // 512 的 2 倍，无填充
        val ragged = "y".repeat(700)          // 需要 368 字节填充
        val tar = entry("./exact.bin", exact.toByteArray()) +
            entry("./ragged.bin", ragged.toByteArray()) +
            endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))

        assertEquals(1024, File(dest, "exact.bin").length())
        assertEquals(700, File(dest, "ragged.bin").length())
        assertEquals(ragged, File(dest, "ragged.bin").readText())
    }

    @Test
    fun `目录条目 size 为零不需要内容`() {
        val dest = tempDir()
        val tar = entry("./d/", ByteArray(0), '5') +
            entry("./d/f.txt", "ok".toByteArray()) +
            endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertEquals("ok", File(dest, "d/f.txt").readText())
    }

    // ------------------------------------------------------- P0 回归：对齐算式

    @Test
    fun `对齐算式——目录条目之后仍能继续解析（P0 缺陷形态）`() {
        // 缺陷算式（"136 + size"）在首个目录条目（size=0）后多跳 376 字节，
        // 第二个头落在数据区中部 → name 为空 → break → 0 文件却报成功。
        // 本用例与缺陷形态同构：首条目为目录、后续有文件——必须全部解出。
        val dest = tempDir()
        val tar = entry("./", ByteArray(0), '5') +
            entry("./marker.json", "{}".toByteArray()) +
            endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertEquals("{}", File(dest, "marker.json").readText())
    }

    @Test
    fun `对齐算式——首条目即短文件且紧随目录`() {
        val dest = tempDir()
        val tar = entry("./a/", ByteArray(0), '5') +
            entry("./a/b/", ByteArray(0), '5') +
            entry("./a/b/c.json", "1".toByteArray()) +
            endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertEquals("1", File(dest, "a/b/c.json").readText())
    }

    // -------------------------------------------------------------- 越界防护

    @Test
    fun `绝对路径条目整体失败且不落盘`() {
        val dest = tempDir()
        val tar = entry("/etc/passwd", "pwned".toByteArray()) + endBlocks

        assertFalse(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertEquals(0, dest.walkTopDown().filter { it.isFile }.count())
    }

    @Test
    fun `双点段条目整体失败且不落盘`() {
        val dest = tempDir()
        val tar = entry("./../escape.txt", "pwned".toByteArray()) + endBlocks

        assertFalse(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertFalse(File(dest.parentFile, "escape.txt").exists())
    }

    @Test
    fun `先合法后越界的序列同样整体失败（不留下半个缓存）`() {
        val dest = tempDir()
        val tar = entry("./good.json", "{}".toByteArray()) +
            entry("./../evil.txt", "x".toByteArray()) +
            endBlocks

        assertFalse(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertFalse(File(dest, "good.json").exists())
    }

    @Test
    fun `符号链接与硬链接条目不落盘但可继续解析`() {
        val dest = tempDir()
        val linkTarget = "link-target-should-not-exist"
        val tar = entry("./lnk", linkTarget.toByteArray(), '2') +
            entry("./after.json", "ok".toByteArray()) +
            endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertFalse(File(dest, "lnk").exists())
        assertEquals("ok", File(dest, "after.json").readText())
    }

    // --------------------------------------------------------------- 失败形态

    @Test
    fun `size 字段非八进制数字则失败`() {
        val dest = tempDir()
        val h = header("./x", 0)
        "zz".toByteArray(Charsets.US_ASCII).copyInto(h, 124)
        val tar = h + endBlocks

        assertFalse(CorepackCacheUnpacker.unpackBytes(tar, dest))
    }

    @Test
    fun `空输入解出零文件报失败而非成功`() {
        val dest = tempDir()
        // 空流（连结束块都没有）：没有任何文件落盘，必须判失败——
        // "0 文件却成功"正是 P0 缺陷的锁死形态。
        assertFalse(CorepackCacheUnpacker.unpackBytes(ByteArray(0), dest))
    }

    @Test
    fun `只有目录没有文件的包报失败`() {
        val dest = tempDir()
        val tar = entry("./empty/", ByteArray(0), '5') + endBlocks

        assertFalse(CorepackCacheUnpacker.unpackBytes(tar, dest))
    }

    @Test
    fun `流提前截断（size 声明大于实际数据）报失败`() {
        val dest = tempDir()
        // 头声明 1000 字节内容，但只给了 512 的头本身 + 部分数据，无填充。
        val head = header("./big.bin", 1000)
        val partial = head + ByteArray(100)
        assertFalse(CorepackCacheUnpacker.unpackBytes(partial, dest))
    }

    @Test
    fun `零长度普通文件也要落盘`() {
        // 缓存包里存在真实的空标记文件（node-gyp 的 py.typed / .gitkeep）——
        // 回归钉子：size==0 曾被跳过不落盘（静默丢内容）。
        val dest = tempDir()
        val tar = entry("./v1/py.typed", ByteArray(0)) +
            entry("./v1/real.txt", "data".toByteArray()) +
            endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertTrue("空文件必须存在", File(dest, "v1/py.typed").isFile)
        assertEquals(0, File(dest, "v1/py.typed").length())
        assertEquals("data", File(dest, "v1/real.txt").readText())
    }

    @Test
    fun `整包只含零长度普通文件也报成功`() {
        // fileCount 的成功条件（>0）必须把空文件算进去：否则一个只含空标记
        // 文件的合法包会被判失败（回归钉子：fileCount 曾漏计 size==0）。
        val dest = tempDir()
        val tar = entry("./marker.typed", ByteArray(0)) + endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertTrue(File(dest, "marker.typed").isFile)
    }

    // ------------------------------------------------------------- tarText

    @Test
    fun `tarText 取 NUL 前内容并 trim`() {
        val h = ByteArray(12)
        "1234".toByteArray(Charsets.US_ASCII).copyInto(h)
        assertEquals("1234", CorepackCacheUnpacker.tarText(h, 0, 12))

        val h2 = ByteArray(12)
        "  ab ".toByteArray(Charsets.US_ASCII).copyInto(h2)
        assertEquals("ab", CorepackCacheUnpacker.tarText(h2, 0, 12))
    }

    // ------------------------------------------------------- P0 缺陷的负面模拟

    @Test
    fun `缺陷算式（136 基数）在本解析器中不再复现——多目录多文件序列全解`() {
        // 用真实资产的条目形态（目录、深路径、混合 size）连成一条长序列：
        // 旧算式在第二个条目就会错位 break。
        val dest = tempDir()
        var tar = ByteArray(0)
        tar += entry("./", ByteArray(0), '5')
        tar += entry("./lastKnownGood.json", "{\"pnpm\": \"11.1.2\"}".toByteArray())
        tar += entry("./v1/", ByteArray(0), '5')
        tar += entry("./v1/pnpm/", ByteArray(0), '5')
        tar += entry("./v1/pnpm/11.1.2/", ByteArray(0), '5')
        tar += entry("./v1/pnpm/11.1.2/bin/", ByteArray(0), '5')
        val big = "z".repeat(600)   // 非对齐长度，额外踩填充路径
        tar += entry("./v1/pnpm/11.1.2/bin/pnpm.cjs", big.toByteArray())
        tar += entry("./v1/pnpm/11.1.2/.corepack", "{}".toByteArray())
        tar += endBlocks

        assertTrue(CorepackCacheUnpacker.unpackBytes(tar, dest))
        assertEquals(3, dest.walkTopDown().filter { it.isFile }.count())
        assertEquals(big, File(dest, "v1/pnpm/11.1.2/bin/pnpm.cjs").readText())
    }
}
