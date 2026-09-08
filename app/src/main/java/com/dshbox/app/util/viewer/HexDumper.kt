package com.dshbox.app.util.viewer

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log2

/**
 * 十六进制转储（1.2.0 §6.9，纯 JVM 格式化逻辑 + 文件随机读）。
 *
 * 三栏：偏移量 | 每行 16 字节 hex | ASCII（不可见显示 `.`）。
 * UI 侧按 [BLOCK_SIZE]（64KB）块随机读取（[readBlock]），LazyColumn 只渲染可视块，
 * 内存恒定。熵粗估（[entropy]）辅助判断压缩/加密内容。
 */
object HexDumper {

    /** 每行字节数（经典 16 列）。 */
    const val ROW_BYTES = 16

    /** 随机读块大小：64KB（§6.9）。 */
    const val BLOCK_SIZE = 64 * 1024

    /** 单行转储结果：[offset] 为行首字节偏移，[hex] 与 [ascii] 按 [ROW_BYTES] 对齐（不足补齐）。 */
    data class HexRow(val offset: Long, val hex: String, val ascii: String)

    /**
     * 把最多 [ROW_BYTES] 字节的行数据格式化为三栏（纯函数）。
     * 尾部不足行以两空格补齐 hex 列、空格补齐 ASCII 列，保证列对齐。
     */
    fun formatRow(data: ByteArray, offset: Long): HexRow {
        val n = data.size.coerceAtMost(ROW_BYTES)
        val hex = StringBuilder(ROW_BYTES * 3)
        val ascii = StringBuilder(ROW_BYTES)
        for (i in 0 until ROW_BYTES) {
            if (i > 0) hex.append(' ')
            if (i < n) {
                val b = data[i].toInt() and 0xFF
                hex.append(HEX_DIGITS[b ushr 4]).append(HEX_DIGITS[b and 0x0F])
                ascii.append(if (b in 0x20..0x7E) b.toChar() else '.')
            } else {
                hex.append("  ")
                ascii.append(' ')
            }
        }
        return HexRow(offset, hex.toString(), ascii.toString())
    }

    /**
     * 把一整块 [data]（自文件偏移 [baseOffset] 起）格式化为行列表。
     * 尾部不足 [ROW_BYTES] 的行照常输出（列仍对齐）。
     */
    fun formatBlock(data: ByteArray, baseOffset: Long): List<HexRow> {
        val rows = ArrayList<HexRow>((data.size + ROW_BYTES - 1) / ROW_BYTES)
        var off = 0
        while (off < data.size) {
            val len = minOf(ROW_BYTES, data.size - off)
            rows += formatRow(data.copyOfRange(off, off + len), baseOffset + off)
            off += ROW_BYTES
        }
        return rows
    }

    /** 文件总行数（UI LazyColumn item count）。 */
    fun rowCount(fileSize: Long): Int = ((fileSize + ROW_BYTES - 1) / ROW_BYTES).toInt()

    /** 行首偏移：rowIndex * ROW_BYTES。 */
    fun rowOffset(rowIndex: Int): Long = rowIndex.toLong() * ROW_BYTES

    /** 行所在块索引：偏移 / [BLOCK_SIZE]。 */
    fun blockOf(offset: Long): Int = (offset / BLOCK_SIZE).toInt()

    /** 随机读取一个 64KB 块（IO，调用方负责线程）。[blockIndex] 超出范围返回空数组。 */
    fun readBlock(file: File, blockIndex: Int): ByteArray {
        if (!file.isFile || blockIndex < 0) return ByteArray(0)
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = blockIndex.toLong() * BLOCK_SIZE
                if (start >= raf.length()) return ByteArray(0)
                raf.seek(start)
                val buf = ByteArray(BLOCK_SIZE)
                var off = 0
                while (off < BLOCK_SIZE) {
                    val n = raf.read(buf, off, BLOCK_SIZE - off)
                    if (n < 0) break
                    off += n
                }
                if (off == BLOCK_SIZE) buf else buf.copyOf(off)
            }
        }.getOrDefault(ByteArray(0))
    }

    /**
     * 熵粗估（bits/byte，0–8）：对采样字节做 256 桶频率统计。
     * 均匀随机/加密内容趋近 8；文本约 4–5；全零/单值内容趋近 0。
     */
    fun entropy(sample: ByteArray): Double {
        if (sample.isEmpty()) return 0.0
        val freq = IntArray(256)
        for (b in sample) freq[b.toInt() and 0xFF]++
        val n = sample.size.toDouble()
        var h = 0.0
        for (f in freq) {
            if (f == 0) continue
            val p = f / n
            h -= p * log2(p)
        }
        return h
    }

    /** 采样整文件熵（大文件取头/中/尾块；返工修正 #8：块去重——size 略超 64KB 时
     *  三块会落在同一 64KB 块上，重复采样无意义）。 */
    fun entropyOf(file: File): Double {
        if (!file.isFile || file.length() <= 0L) return 0.0
        val size = file.length()
        if (size <= BLOCK_SIZE) return entropy(readBlock(file, 0))
        val blocks = sortedSetOf(0, blockOf(size / 2), blockOf(size - BLOCK_SIZE))
        val sample = blocks.flatMap { readBlock(file, it).toList() }.toByteArray()
        return entropy(sample)
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
