package com.dshbox.app.service

import com.github.luben.zstd.ZstdInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 内置 corepack 缓存包（zstd 压缩的 tar）的展开器。
 *
 * 进程内解压：zstd-jni classes（BundleManager 解运行层的同一 jar）加最小 tar 头
 * 解析——只为读"普通文件 + 目录"两类条目，避免把 commons-compress 引进 app 模块。
 *
 * ## 安全与健壮性边界
 *
 * - **条目名越界**：名字为绝对路径、含 `..` 段或为空时，整个展开立即失败
 *   （返回 false，不再继续写盘）——半个缓存在磁盘上比没有缓存更糟。
 * - **不落盘的条目**：目录（type '5'）由 [File.mkdirs] 覆盖；符号链接（'2'）、
 *   硬链接（'1'）与 pax 扩展头（'x'/'g'）等跳过落盘但**跳过其内容时按声明长度
 *   走完**——pax 头里藏着后续条目的真名，错位会让下一个文件挂到错误的路径上。
 * - **非空成功条件**：全程无错位且至少落盘一个普通文件才算成功。本类曾因对齐
 *   算式错误解出 0 个文件却返回 true（tar 头固定 512 字节，余量只由内容长度
 *   决定；曾误写 "136 + size"——136 是 size 字段起始偏移，不是块基数），因此
 *   "至少一个文件"被列为成功的必要条件。
 * - **失败原子性**：任何解析错位（读不满、非法 size、越界名）都让整体失败；
 *   调用方负责丢弃目标目录与就绪标记。
 *
 * [unpackBytes] 是纯函数面（不触碰压缩与真实路径解析），供 JVM 单测直接钉住
 * 上述每条边界；[unpack] 与它共用同一套控制流。
 */
object CorepackCacheUnpacker {

    /** tar 块大小（512 字节）。 */
    const val TAR_BLOCK = 512

    /**
     * 把 zstd 压缩的 tar 包展开到 [dest]。
     *
     * @return true 表示至少写出了一个文件且全程无错位；调用方据此决定是否写就绪标记。
     */
    fun unpack(tarball: File, dest: File): Boolean = runCatching {
        // 两阶段：dryRun 轮通过后才写盘轮。zstd 流不可重读，两个阶段各自从文件
        // 重开解压流（资产 ~3 MB，多解压一次的代价远小于"半个缓存"的风险）。
        FileInputStream(tarball).use { raw ->
            ZstdInputStream(raw).use { zin ->
                if (!unpackStream({ buf, off, len -> readFully(zin, buf, off, len) }, dest, dryRun = true)) {
                    return false
                }
            }
        }
        FileInputStream(tarball).use { raw ->
            ZstdInputStream(raw).use { zin ->
                unpackStream({ buf, off, len -> readFully(zin, buf, off, len) }, dest, dryRun = false)
            }
        }
    }.getOrDefault(false)

    /**
     * 从已解压的 tar 字节序列展开（纯函数面，供 JVM 单测使用）。
     * 成功条件与 [unpack] 相同：全程无错位，且至少落盘一个普通文件。
     * **两阶段**：先做一轮不落盘的全量扫描（越界名、size 合法性、读满），全部
     * 通过才进入写盘轮——任何条目越界都不会留下半个缓存。
     */
    fun unpackBytes(tarBytes: ByteArray, dest: File): Boolean {
        // 两轮各自用独立的流：第一轮（dryRun）会把流读到尾，第二轮必须重开。
        ByteArrayInputStream(tarBytes).use { input ->
            if (!unpackStream({ buf, off, len -> readFully(input, buf, off, len) }, dest, dryRun = true)) {
                return false
            }
        }
        ByteArrayInputStream(tarBytes).use { input ->
            return unpackStream({ buf, off, len -> readFully(input, buf, off, len) }, dest, dryRun = false)
        }
    }

    private fun unpackStream(read: (ByteArray, Int, Int) -> Int, dest: File, dryRun: Boolean): Boolean {
        var fileCount = 0
        while (true) {
            val header = ByteArray(TAR_BLOCK)
            if (read(header, 0, TAR_BLOCK) != TAR_BLOCK) break
            val name = tarText(header, 0, 100)
            if (name.isEmpty()) break
            val size = tarText(header, 124, 12).toLongOrNull(8) ?: return false
            val type = header[156].toInt().toChar()
            if (type == '0' || type == '\u0000') {
                val entry = safeEntry(dest, name) ?: return false
                val data = if (size > 0) ByteArray(size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) else null
                if (data != null && read(data, 0, data.size) != data.size) return false
                // 零长度普通文件也要落盘：缓存包里有真实的空标记文件（如
                // node-gyp 的 py.typed / .gitkeep），漏建它们与漏建普通文件同罪。
                if (!dryRun) {
                    entry.parentFile?.mkdirs()
                    FileOutputStream(entry).use { output -> data?.let { output.write(it) } }
                }
                fileCount++
            } else if (size > 0) {
                // 非普通文件（目录/链接/pax 头）不落盘，但内容必须按声明长度走完，
                // 否则后续条目头全部错位。
                var remaining = size
                val sink = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = read(sink, 0, minOf(sink.size.toLong(), remaining).toInt())
                    if (n < 0) return false
                    remaining -= n
                }
            }
            // 512 对齐：头固定 512 字节且已读毕，填充只由内容长度决定。
            val pad = (TAR_BLOCK - (size % TAR_BLOCK).toInt()) % TAR_BLOCK
            val buf = ByteArray(TAR_BLOCK)
            var remaining = pad
            while (remaining > 0) {
                val n = read(buf, 0, remaining)
                if (n < 0) return false
                remaining -= n
            }
        }
        return fileCount > 0
    }

    /** 越界防护后的目标文件；名字含绝对路径或 `..` 段时返回 null（拒绝逃逸）。 */
    private fun safeEntry(dest: File, name: String): File? {
        if (name.isEmpty() || name.startsWith("/") || name.split('/').contains("..")) return null
        return File(dest, name)
    }

    /** tar 头里的 NUL 结尾 ASCII 字段。 */
    internal fun tarText(header: ByteArray, offset: Int, length: Int): String =
        header.sliceArray(offset until offset + length)
            .toString(Charsets.US_ASCII).substringBefore('\u0000').trim()

    /** 从流里读满 [buffer]，流提前结束返回 false。 */
    private fun readFully(input: java.io.InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val n = input.read(buffer, pos, end - pos)
            if (n < 0) return -1
            pos += n
        }
        return length
    }
}
