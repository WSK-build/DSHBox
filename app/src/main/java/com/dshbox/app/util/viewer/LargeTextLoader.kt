package com.dshbox.app.util.viewer

import java.io.File
import java.io.RandomAccessFile

/**
 * 大文本分级装载（1.2.0 §6.4，纯 JVM；阈值真机验证后可调）。
 *
 * | 文件大小 | 策略 |
 * |---|---|
 * | ≤ [FULL_EDIT_LIMIT]（2 MB） | 全量载入，可编辑 |
 * | 2–10 MB | 默认只读分块查看（[readAlignedWindow] 按行对齐窗口懒加载）；显式点「仍要编辑」才全量载入并提示风险 |
 * | > [CONFIRM_EDIT_LIMIT]（10 MB） | 强制只读分块 / 尾部模式（日志场景「仅看最后 N KB」），编辑引导外部应用 |
 *
 * 行扫实现说明（返工修正）：行首吸附/行尾延伸一律 64KB 成块扫描并设 [LINE_SCAN_CAP]
 * 上限——超长单行（压缩 json / min.js）不再退化为数千万次单字节 `raf.read()`；
 * 触顶时窗口边界取当前字节位（内容不跳过、不丢失，仅该窗口边界不在行首）。
 */
object LargeTextLoader {

    /** 全量可编辑上限：2MB。 */
    const val FULL_EDIT_LIMIT = 2L * 1024 * 1024

    /** 显式确认后可编辑上限：10MB。 */
    const val CONFIRM_EDIT_LIMIT = 10L * 1024 * 1024

    /** 「仅看最后 N KB」的尾窗默认大小：512KB。 */
    const val TAIL_WINDOW = 512L * 1024

    /** 分块窗口默认字节数（懒加载前后各缓存 1–2 块由 UI 状态承担）。 */
    const val CHUNK_BYTES = 256 * 1024

    /** 行扫描上限（4MB）：超长单行超出此长度时窗口边界直接落在当前字节位。 */
    const val LINE_SCAN_CAP = 4L * 1024 * 1024

    private const val SCAN_BUFFER = 64 * 1024

    enum class Policy { FULL_EDITABLE, READONLY_CHUNKS, FORCE_READONLY_TAIL }

    data class Plan(val policy: Policy, val sizeBytes: Long) {
        val editable: Boolean get() = policy == Policy.FULL_EDITABLE
    }

    /** 按文件大小确定装载策略（§6.4 分级）。 */
    fun planFor(size: Long): Plan = when {
        size <= FULL_EDIT_LIMIT -> Plan(Policy.FULL_EDITABLE, size)
        size <= CONFIRM_EDIT_LIMIT -> Plan(Policy.READONLY_CHUNKS, size)
        else -> Plan(Policy.FORCE_READONLY_TAIL, size)
    }

    /**
     * 从 [from] 起成块扫描下一个 `\n`，返回其后的位置；[to]（或 [from] + 扫描缓冲总量）
     * 前无换行返回 null。调用方无需关心 raf 当前位置（函数内部先 seek）。
     */
    private fun findNewlineAfter(raf: RandomAccessFile, from: Long, to: Long): Long? {
        val buf = ByteArray(SCAN_BUFFER)
        var pos = from
        raf.seek(from)
        while (pos < to) {
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), to - pos).toInt())
            if (n < 0) return null
            for (i in 0 until n) {
                if (buf[i] == '\n'.code.toByte()) return pos + i + 1
            }
            pos += n
        }
        return null
    }

    /**
     * 把任意字节偏移吸附到其后第一个行首（`\n` 之后；0 保持不变）。
     * 在 [maxScan] 字节内成块扫描；找不到（超长单行）返回 [start] 原值——
     * 内容不跳过，仅该窗口起点不在行首。
     */
    private fun snapToLineStart(raf: RandomAccessFile, start: Long, size: Long, maxScan: Long): Long {
        if (start <= 0) return 0
        val pos = start.coerceIn(1, size)
        raf.seek(pos - 1)
        if (raf.read() == '\n'.code) return pos // 恰在行首
        return findNewlineAfter(raf, pos, minOf(size, pos + maxScan)) ?: pos
    }

    private fun readFullyFrom(raf: RandomAccessFile, start: Long, length: Int): ByteArray {
        raf.seek(start)
        val buf = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = raf.read(buf, off, length - off)
            if (n < 0) break
            off += n
        }
        return if (off == length) buf else buf.copyOf(off)
    }

    /**
     * 行对齐只读窗口：[startByte] 自动吸附到行首；窗口末尾延伸到下一个换行（含），
     * 解码按 [detection]（大文件只采样头部，探测结果近似适用全文）。
     *
     * @return [TextWindow]；hasPrev/hasNext 供 UI 决定前后翻块按钮可用性。
     */
    fun readAlignedWindow(
        file: File,
        startByte: Long,
        detection: TextEncoding.Detection,
        maxBytes: Int = CHUNK_BYTES,
    ): TextWindow {
        if (!file.isFile) return TextWindow(0, 0, "", false, false)
        val size = file.length()
        if (size <= 0) return TextWindow(0, 0, "", false, false)
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = snapToLineStart(raf, startByte, size, LINE_SCAN_CAP)
                val want = maxBytes.coerceAtLeast(1).toLong().coerceAtMost(size - start).toInt()
                var end = start
                var decoded = TextEncoding.DecodedText("", lossy = false)
                if (want > 0) {
                    end = start + want
                    // 末尾若截断在行中间，成块扫描延伸到下一个 \n（含）；超长单行触顶则停在 want 边界
                    if (end < size) {
                        end = findNewlineAfter(raf, end, minOf(size, end + LINE_SCAN_CAP)) ?: end
                    }
                    val full = readFullyFrom(raf, start, (end - start).toInt().coerceAtMost(Int.MAX_VALUE))
                    decoded = TextEncoding.decodeChecked(full, detection)
                }
                TextWindow(start, end, decoded.text, hasPrev = start > 0, hasNext = end < size, lossy = decoded.lossy)
            }
        }.getOrDefault(TextWindow(0, 0, "", false, false))
    }

    /**
     * 尾部窗口（日志「仅看最后 N KB」）：从 size−[tailBytes] 后第一个行首到文件末尾。
     * 吸附扫描上限 1MB——超长单行起点保持原值（尾窗内容不跳过）。
     */
    fun readTailWindow(file: File, detection: TextEncoding.Detection, tailBytes: Long = TAIL_WINDOW): TextWindow {
        if (!file.isFile) return TextWindow(0, 0, "", false, false)
        val size = file.length()
        if (size <= 0) return TextWindow(0, 0, "", false, false)
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = snapToLineStart(raf, if (size <= tailBytes) 0L else size - tailBytes, size, maxScan = 1L * 1024 * 1024)
                val len = (size - start).toInt().coerceAtMost(Int.MAX_VALUE)
                val buf = readFullyFrom(raf, start, len)
                val decoded = TextEncoding.decodeChecked(buf, detection)
                TextWindow(start, size, decoded.text, hasPrev = start > 0, hasNext = false, lossy = decoded.lossy)
            }
        }.getOrDefault(TextWindow(0, 0, "", false, false))
    }

    data class TextWindow(
        val startByte: Long,
        val endByte: Long,
        val text: String,
        val hasPrev: Boolean,
        val hasNext: Boolean,
        /** 返工 P1：窗口内存在无法解码的字节（有损解码）——只读展示，无保存路径。 */
        val lossy: Boolean = false,
    )
}
