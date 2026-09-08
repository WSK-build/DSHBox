package com.dshbox.app.util.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 大文本分块装载回归（返工修正 #3 锁定）：行对齐窗口边界正确、超长单行不丢内容
 * （扫描封顶后窗口边界落在字节位而非行首，prev/next 仍可无缝遍历全文）。
 */
class LargeTextLoaderTest {

    private val detection = TextEncoding.Detection(TextEncoding.TextCharset.UTF_8, 0)

    private fun newFile(content: String): File =
        File.createTempFile("bigtext", ".txt").apply {
            writeText(content)
            deleteOnExit()
        }

    @Test
    fun planTiers() {
        assertEquals(LargeTextLoader.Policy.FULL_EDITABLE, LargeTextLoader.planFor(2L * 1024 * 1024).policy)
        assertEquals(LargeTextLoader.Policy.READONLY_CHUNKS, LargeTextLoader.planFor(2L * 1024 * 1024 + 1).policy)
        assertEquals(LargeTextLoader.Policy.FORCE_READONLY_TAIL, LargeTextLoader.planFor(10L * 1024 * 1024 + 1).policy)
    }

    @Test
    fun windowAlignsToLineBoundariesAndConcats() {
        val content = (1..100).joinToString("\n", postfix = "\n") { "line-$it" }
        val f = newFile(content)
        val w1 = LargeTextLoader.readAlignedWindow(f, 0, detection, maxBytes = 40)
        // 窗口从 0 开始、结束于某个 \n 之后（整行）
        assertEquals(0L, w1.startByte)
        assertTrue(w1.text.endsWith("\n"))
        assertFalse(w1.hasPrev)
        assertTrue(w1.hasNext)
        // 下一窗口自上一窗口 end 起，内容无缝衔接
        val w2 = LargeTextLoader.readAlignedWindow(f, w1.endByte, detection, maxBytes = 40)
        assertTrue(w2.hasPrev)
        assertEquals(w1.text, content.substring(0, w1.endByte.toInt()))
        assertEquals(w2.text, content.substring(w1.endByte.toInt(), w2.endByte.toInt()))
    }

    @Test
    fun singleLineFileNavigableWithoutContentLoss() {
        // 超长单行（返工修正 #3 场景）：吸附/延伸触顶后窗口边界落字节位，内容仍连续
        val single = "x".repeat(1_000_000) // 1MB、无换行
        val f = newFile(single)
        val w1 = LargeTextLoader.readAlignedWindow(f, 0, detection, maxBytes = 256 * 1024)
        assertEquals(0L, w1.startByte)
        assertEquals(256 * 1024L, w1.endByte)
        assertTrue(w1.hasNext)
        assertTrue(w1.text.all { it == 'x' }) // 内容无损
        val w2 = LargeTextLoader.readAlignedWindow(f, w1.endByte, detection, maxBytes = 256 * 1024)
        assertEquals(w1.endByte, w2.startByte) // 无缝衔接
        assertEquals(512 * 1024L, w2.endByte)
    }

    @Test
    fun tailWindowCoversFileEnd() {
        val content = (1..2000).joinToString("\n", postfix = "\n") { "row-$it" }
        val f = newFile(content)
        val size = f.length()
        val tail = LargeTextLoader.readTailWindow(f, detection, tailBytes = 1024)
        assertEquals(size, tail.endByte)
        assertTrue(tail.text.startsWith("row-"))
        assertFalse(tail.hasNext)
        // 小文件：tail 覆盖整个文件
        val small = newFile("a\nb\n")
        val smallTail = LargeTextLoader.readTailWindow(small, detection, tailBytes = 1024)
        assertEquals(0L, smallTail.startByte)
        assertEquals("a\nb\n", smallTail.text)
    }

    @Test
    fun windowCarriesLossyFlag() {
        // 返工 P1：窗口解码带有损标记——含无法解码字节的文件 lossy=true。
        // 注意必须写「原始字节」（Shift_JIS 风格 0x83 0x65 …）：经 String 会变成合法 UTF-8
        val bad = File.createTempFile("bigtext", ".txt").apply {
            writeBytes(
                "ok-line\n".toByteArray() +
                    byteArrayOf(0x83.toByte(), 0x65.toByte(), 0x83.toByte(), 0x58.toByte()) +
                    "\ntail\n".toByteArray(),
            )
            deleteOnExit()
        }
        val wBad = LargeTextLoader.readAlignedWindow(bad, 0, detection, maxBytes = 64 * 1024)
        assertTrue(wBad.lossy)
        // 合法 UTF-8 → lossy=false
        val good = newFile("ok-line\ntail\n")
        val wGood = LargeTextLoader.readAlignedWindow(good, 0, detection, maxBytes = 64 * 1024)
        assertFalse(wGood.lossy)
        // 尾窗同样携带标记
        val tailBad = LargeTextLoader.readTailWindow(bad, detection, tailBytes = 1024)
        assertTrue(tailBad.lossy)
    }
}
