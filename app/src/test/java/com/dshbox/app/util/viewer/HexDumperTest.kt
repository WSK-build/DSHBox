package com.dshbox.app.util.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 十六进制转储单测（1.2.0 §10.1）：块边界、尾部不足块、偏移正确性、熵粗估。
 */
class HexDumperTest {

    // ---------------- 行格式 ----------------

    @Test
    fun formatRowColumns() {
        val row = HexDumper.formatRow(byteArrayOf(0x48, 0x65, 0x6C, 0x0A), 0)
        // hex 列 16 字节对齐（不足补空）
        assertEquals("48 65 6C 0A" + "   ".repeat(12), row.hex)
        // 不可见字节显示 .
        assertEquals("Hel." + " ".repeat(12), row.ascii)
        assertEquals(0L, row.offset)
    }

    @Test
    fun formatRowOffsetLabel() {
        val row = HexDumper.formatRow(ByteArray(16), 0x1A2B3C4DL)
        assertEquals(0x1A2B3C4DL, row.offset)
    }

    // ---------------- 块格式与边界 ----------------

    @Test
    fun formatBlockRowBoundaries() {
        // 35 字节 = 2 行整行 + 3 字节尾行
        val data = ByteArray(35) { it.toByte() }
        val rows = HexDumper.formatBlock(data, 0)
        assertEquals(3, rows.size)
        assertEquals(0L, rows[0].offset)
        assertEquals(16L, rows[1].offset)
        assertEquals(32L, rows[2].offset)
        // 尾行 3 字节（20 21 22）起头，其后按 16 字节宽度补齐空格
        assertTrue(rows[2].hex.startsWith("20 21 22"))
        assertEquals(16 * 3 - 1, rows[2].hex.length)
    }

    @Test
    fun formatBlockBaseOffsetApplied() {
        val rows = HexDumper.formatBlock(ByteArray(16), HexDumper.BLOCK_SIZE.toLong())
        assertEquals(HexDumper.BLOCK_SIZE.toLong(), rows[0].offset)
    }

    @Test
    fun rowCountMath() {
        assertEquals(0, HexDumper.rowCount(0))
        assertEquals(1, HexDumper.rowCount(1))
        assertEquals(1, HexDumper.rowCount(16))
        assertEquals(2, HexDumper.rowCount(17))
        assertEquals(4096, HexDumper.rowCount(HexDumper.BLOCK_SIZE.toLong()))
    }

    // ---------------- 随机读块 ----------------

    @Test
    fun readBlockCoversTailAndBounds() {
        val tmp = File.createTempFile("hexdump", ".bin").apply {
            writeBytes(ByteArray(100_000) { (it % 251).toByte() })
            deleteOnExit()
        }
        // 第一块
        val b0 = HexDumper.readBlock(tmp, 0)
        assertEquals(HexDumper.BLOCK_SIZE, b0.size)
        assertEquals(0.toByte(), b0[0])
        // 第二块（尾部不足 64KB）
        val b1 = HexDumper.readBlock(tmp, 1)
        assertEquals(100_000 - HexDumper.BLOCK_SIZE, b1.size)
        // 越界块返回空
        assertEquals(0, HexDumper.readBlock(tmp, 5).size)
        assertEquals(0, HexDumper.readBlock(tmp, -1).size)
        // 块拼接等价原文件
        assertEquals(tmp.readBytes().toList(), (b0 + b1).toList())
    }

    // ---------------- 熵 ----------------

    @Test
    fun entropyBounds() {
        // 常量字节 → 0
        assertEquals(0.0, HexDumper.entropy(ByteArray(1000)), 1e-9)
        // 两种字节均分 → 1 bit/byte
        val two = ByteArray(100) { if (it < 50) 0 else 1 }
        assertEquals(1.0, HexDumper.entropy(two), 1e-9)
        // 256 种均分 → 8 bits/byte
        val uniform = ByteArray(256 * 8) { (it % 256).toByte() }
        assertEquals(8.0, HexDumper.entropy(uniform), 0.01)
        // ASCII 文本介于 3–5
        val text = ("the quick brown fox jumps over the lazy dog. ").toByteArray()
        val h = HexDumper.entropy(text)
        assertTrue("h=$h", h in 3.0..5.0)
    }
}
