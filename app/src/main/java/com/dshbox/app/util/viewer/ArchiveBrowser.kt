package com.dshbox.app.util.viewer

import com.dshbox.app.R
import com.dshbox.app.common.UiText
import com.github.luben.zstd.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CCZipFile

/**
 * 压缩包只读条目枚举（1.2.0 §6.8，纯 JVM 无 Android 依赖）。
 *
 * 支持格式：ZIP 族（ZipFile）、TAR 族（commons-compress：tar/tar.gz/tgz/tar.bz2/tbz2）、
 * tar.zst（zstd-jni，工程内 classes jar + jniLibs arm64 .so）。7z/RAR 不支持（计划 D5），
 * 分类器识别后由 UI 走信息卡兜底。
 *
 * 只读约束：不落盘工作区、不解压，无 Zip-Slip 面；加密 ZIP 仅列条目名（内容拒绝预览）。
 * x86_64 模拟器无 zstd .so：zstd 加载失败必须捕获为 [Result.Error]（不得崩溃），
 * 仅 arm64 真机可实际枚举 tar.zst。
 */
object ArchiveBrowser {

    /** 条目枚举上限（防病态超大包拖爆内存；超出部分 [Snapshot.truncated] 标记）。 */
    const val MAX_ENTRIES = 100_000

    enum class Format { ZIP, TAR, TAR_GZ, TAR_BZ2, TAR_ZST }

    data class Entry(
        /** 归一化包内路径（'/' 分隔，无前导 ./ 与 /）。 */
        val path: String,
        /** 最后一段名称（列表展示用）。 */
        val name: String,
        val isDirectory: Boolean,
        /** 解压后大小；目录为 0，未知为 -1。 */
        val size: Long,
        /** 压缩后大小（仅 ZIP 有意义，TAR 族 = size）。 */
        val compressedSize: Long,
        /** 修改时间（epoch ms），未知为 0。 */
        val lastModified: Long,
        /** ZIP 加密条目（general purpose bit 0x1）；TAR 族恒 false。 */
        val isEncrypted: Boolean,
        /** 目录层级：根下文件为 0。 */
        val depth: Int,
        /** 父目录路径（根为空串）。 */
        val parentPath: String,
    )

    data class Snapshot(
        val format: Format,
        val entries: List<Entry>,
        val hasEncrypted: Boolean,
        /** 条目数超 [MAX_ENTRIES] 被截断。 */
        val truncated: Boolean,
        /**
         * 条目名字符集（"UTF-8"/"GBK"）。第三轮审查启发式：UTF-8 先行，
         * 任一非 ASCII 名含 U+FFFD（替换字符）→ 判定 GBK 并整包回退重读。
         * openEntryStream / exportAllToZip 必须复用本值，否则名称错配找不到条目。
         */
        val charsetLabel: String = "UTF-8",
    )

    sealed interface Result {
        data class Ok(val snapshot: Snapshot) : Result
        data class Error(val message: UiText, val cause: Throwable? = null) : Result
    }

    /**
     * 由分类器结果（扩展名 + 二级子类型）推断浏览器格式；不可承接的压缩容器
     * （gzip/zstd/bzip2/xz 非 tar、7z、rar 等）返回 null → UI 信息卡兜底。
     * 纯函数，单测覆盖。
     */
    fun formatOf(extension: String, subType: String?): Format? = when {
        subType == "tar.gz" -> Format.TAR_GZ
        subType == "tar.bz2" -> Format.TAR_BZ2
        subType == "tar.zst" -> Format.TAR_ZST
        subType == "tar" -> Format.TAR
        subType == "zip" -> Format.ZIP
        else -> when (extension) {
            "zip", "jar", "apk", "aar", "war", "epub" -> Format.ZIP
            "tar" -> Format.TAR
            "tgz", "tar.gz" -> Format.TAR_GZ
            "tbz2", "tbz", "tar.bz2" -> Format.TAR_BZ2
            "tar.zst" -> Format.TAR_ZST
            else -> null
        }
    }

    /** 展示用格式标签（头部信息行）。 */
    fun formatLabel(format: Format): String = when (format) {
        Format.ZIP -> "ZIP"
        Format.TAR -> "tar"
        Format.TAR_GZ -> "tar.gz"
        Format.TAR_BZ2 -> "tar.bz2"
        Format.TAR_ZST -> "tar.zst"
    }

    /** 枚举全部条目（IO 线程调用）。任何异常收敛为 [Result.Error]，不得外抛。 */
    fun browse(file: File, format: Format): Result = try {
        val entries = when (format) {
            Format.ZIP -> browseZip(file)
            Format.TAR, Format.TAR_GZ, Format.TAR_BZ2, Format.TAR_ZST -> browseTar(file, format)
        }
        Result.Ok(
            Snapshot(
                format = format,
                entries = entries.first,
                hasEncrypted = entries.first.any { it.isEncrypted },
                truncated = entries.second,
                charsetLabel = entries.third,
            ),
        )
    } catch (t: Throwable) {
        // zstd .so 缺失（x86_64 模拟器）表现为 UnsatisfiedLinkError；若类加载已先失败，
        // 再访问可能抛 NoClassDefFoundError（cause 链含 UnsatisfiedLinkError）——必须
        // 遍历 cause 链判定，软提示而非崩溃。
        if (t.hasUnsatisfiedLinkCause()) {
            Result.Error(UiText.Res(R.string.archivebrowser_err_no_zstd_native), t)
        } else {
            Result.Error(UiText.Res(R.string.archivebrowser_err_open_failed), t)
        }
    }

    /**
     * 打开单个条目的内容流（IO 线程调用）。TAR 族为顺序重扫（流式格式无随机访问），
     * 返回的流关闭时同步释放底层解压流。加密条目返回 null（由调用方先按 isEncrypted 提示）。
     */
    fun openEntryStream(file: File, format: Format, path: String, charset: String = "UTF-8"): InputStream? = try {
        when (format) {
            Format.ZIP -> openZipEntry(file, path, charset)
            Format.TAR, Format.TAR_GZ, Format.TAR_BZ2, Format.TAR_ZST -> openTarEntry(file, format, path, charset)
        }
    } catch (t: Throwable) {
        if (t is UnsatisfiedLinkError || (t.cause is UnsatisfiedLinkError)) null else null
    }

    /**
     * 全部文件条目单遍导出为 ZIP 流（「全部导出」，§6.8：复用既有 SAF 落盘）。
     * 加密条目跳过；[cancelCheck] 在条目间调用（协程取消传播）。返回已写入条目数。
     */
    fun exportAllToZip(
        file: File,
        format: Format,
        zipOut: java.util.zip.ZipOutputStream,
        cancelCheck: () -> Unit,
        charset: String = "UTF-8",
    ): Int = when (format) {
        Format.ZIP -> exportZipToZip(file, zipOut, cancelCheck, charset)
        Format.TAR, Format.TAR_GZ, Format.TAR_BZ2, Format.TAR_ZST -> exportTarToZip(file, format, zipOut, cancelCheck, charset)
    }

    // ---------------- ZIP ----------------
    // 2026-09-07 审查 P2：中文条目名。java.util.zip.ZipFile 无法指定条目名字符集——
    // 国内 Windows 压缩软件的中文 ZIP 多为 GBK（bit11 未置位），JDK 默认按 UTF-8 解出
    // 乱码。改用 commons-compress ZipFile（charset=GBK）：bit11 置位条目按 UTF-8、
    // 未置位按 GBK 解码；其 GeneralPurposeBit 直接暴露加密位（替代自研 CEN 扫描），
    // 打开加密包不拒绝（免 JDK 的流式回退）。损坏/垃圾包打开抛异常 → browse 收敛 Error。

    /**
     * 第三轮审查：条目名编码判定改为**字节级 CEN 严格校验**。
     * 实证（commons-compress 1.27.1）：UTF-8 解码 GBK 条目名产出 U+003F（问号）而非
     * U+FFFD——解码结果启发式（按替换字符回退）在 commons 上不成立、永不触发。
     * 方案：扫中央目录，对 general purpose bit11 **未置位**的条目用严格 UTF-8 解码器
     * （REPORT 模式）校验 nameBytes，非法序列 → 判 GBK（bit11 置位条目恒按 UTF-8）。
     * 字节级判定不依赖解码器的替换字符行为，可靠。
     */
    private val CHARSET_UTF8: Charset = Charsets.UTF_8
    private val CHARSET_GBK: Charset = Charset.forName("GBK")

    internal fun ccZipFile(file: File, charset: Charset): CCZipFile =
        CCZipFile.builder().setFile(file).setCharset(charset).get()

    /** 返回 (条目列表, 截断标志, 条目名字符集)。 */
    private fun browseZip(file: File): Triple<List<Entry>, Boolean, String> {
        val charset = detectZipCharset(file)
        return browseZipWithCharset(file, if (charset == "GBK") CHARSET_GBK else CHARSET_UTF8)
    }

    private fun browseZipWithCharset(file: File, charset: Charset): Triple<List<Entry>, Boolean, String> {
        val out = ArrayList<Entry>()
        var truncated = false
        ccZipFile(file, charset).use { zip ->
            val it = zip.entries
            while (it.hasMoreElements()) {
                if (out.size >= MAX_ENTRIES) {
                    truncated = true
                    break
                }
                val e = it.nextElement()
                out.add(
                    buildEntry(
                        e.name, e.isDirectory, e.size, e.compressedSize, e.time,
                        encrypted = e.generalPurposeBit.usesEncryption(),
                    ),
                )
            }
        }
        return Triple(out, truncated, charset.name())
    }

    /**
     * 字节级 ZIP 条目名字符集判定（IO）：扫中央目录（EOCD → CEN），对 bit11 未置位的
     * 非 ASCII 名称做严格 UTF-8 校验；任一非法 → "GBK"。全 ASCII / 全合法 / 解析异常
     * → "UTF-8"。
     */
    internal fun detectZipCharset(file: File): String = try {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (size < 22L) return "UTF-8"
            val scanLen = minOf(size, 22L + 65535L).toInt()
            raf.seek(size - scanLen)
            val buf = ByteArray(scanLen)
            raf.readFully(buf)
            var eocd = -1
            var i = buf.size - 22
            while (i >= 0) {
                if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4B.toByte() &&
                    buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()
                ) {
                    eocd = i
                    break
                }
                i--
            }
            if (eocd < 0) return "UTF-8"
            val cdSize = leInt(buf, eocd + 12).toLong() and 0xFFFFFFFFL
            val cdOffset = leInt(buf, eocd + 16).toLong() and 0xFFFFFFFFL
            if (cdOffset + cdSize > size || cdSize > Int.MAX_VALUE) return "UTF-8"
            raf.seek(cdOffset)
            val cd = ByteArray(cdSize.toInt())
            raf.readFully(cd)
            val strictUtf8 = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            var p = 0
            while (p + 46 <= cd.size && leInt(cd, p) == 0x02014b50) {
                val flags = leShort(cd, p + 8)
                val nameLen = leShort(cd, p + 28).toInt()
                val extraLen = leShort(cd, p + 30).toInt()
                val commentLen = leShort(cd, p + 32).toInt()
                // bit11 = 0x800：置位按 UTF-8，不校验；未置位校验
                if (flags and 0x800 == 0 && nameLen in 1..(cd.size - p - 46)) {
                    val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
                    if (nameBytes.any { (it.toInt() and 0xFF) >= 0x80 }) {
                        try {
                            strictUtf8.decode(ByteBuffer.wrap(nameBytes))
                        } catch (_: CharacterCodingException) {
                            return "GBK"
                        }
                    }
                }
                p += 46 + nameLen + extraLen + commentLen
            }
            "UTF-8"
        }
    } catch (_: Exception) {
        "UTF-8"
    }

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun leInt(b: ByteArray, off: Int): Int =
        leShort(b, off) or (leShort(b, off + 2) shl 16)

    private fun openZipEntry(file: File, path: String, charset: String): InputStream? {
        val zip = ccZipFile(file, charset.toCharsetCompat())
        val entry = zip.getEntry(path) ?: run {
            zip.close()
            return null
        }
        // 加密条目防御性拒绝（UI 已按 isEncrypted 提示，双保险）
        if (entry.generalPurposeBit.usesEncryption()) {
            zip.close()
            return null
        }
        val inner = try {
            zip.getInputStream(entry)
        } catch (t: Throwable) {
            zip.close()
            throw t
        }
        // ZipFile 必须在内容流生命周期内存活：包装流关闭时连带释放
        return object : FilterInputStream(inner) {
            override fun close() {
                runCatching { super.close() }
                runCatching { zip.close() }
            }
        }
    }

    private fun exportZipToZip(file: File, zipOut: java.util.zip.ZipOutputStream, cancelCheck: () -> Unit, charset: String): Int {
        var count = 0
        ccZipFile(file, charset.toCharsetCompat()).use { zip ->
            val it = zip.entries
            while (it.hasMoreElements()) {
                val e = it.nextElement()
                if (e.isDirectory || e.generalPurposeBit.usesEncryption()) continue
                cancelCheck()
                zipOut.putNextEntry(ZipEntry(normalizeName(e.name)).apply { time = e.time })
                zip.getInputStream(e).use { ins -> ins.copyTo(zipOut, 64 * 1024) }
                zipOut.closeEntry()
                count++
            }
        }
        return count
    }

    // ---------------- TAR 族 ----------------

    /** TAR 名统一 UTF-8（第三轮审查实证修正）：tar 无编码标志位，commons 用 UTF-8 解
     *  GBK 字节产出问号（U+003F）而非替换字符，解码结果启发不成立；字节级判定对 tar
     *  不适用（头部无编码信息）。沙盒内 `tar czf` 打出的 UTF-8 包为主场景，保持正确；
     *  GBK 编码 tar 为已知限制（条目名乱码可辨，1.2.x 评估 UI 编码切换入口）。 */
    private fun browseTar(file: File, format: Format): Triple<List<Entry>, Boolean, String> =
        browseTarWithCharset(file, format, CHARSET_UTF8)

    private fun browseTarWithCharset(file: File, format: Format, charset: Charset): Triple<List<Entry>, Boolean, String> {
        val out = ArrayList<Entry>()
        var truncated = false
        decompressor(file, format).use { dec ->
            TarArchiveInputStream(dec, charset.name()).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    if (out.size >= MAX_ENTRIES) {
                        truncated = true
                        break
                    }
                    val name = normalizeName(entry.name)
                    if (name.isNotEmpty()) {
                        out.add(buildEntry(name, entry.isDirectory, entry.size, entry.size, entry.modTime.time, false))
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        // 合法空明文 tar 恰为两个 512 字节 EOF 块（1024 字节）；commons 对「记录中途截断」
        // 表现为无异常返回 0 条目——明文 tar 不足 1024 字节的空结果按损坏包处理（压缩格式
        // 的合法空包可能远小于 1024，不做此判定）
        if (format == Format.TAR && out.isEmpty() && !truncated && file.length() in 1 until 1024L) {
            throw IOException("Archive entry stream incomplete or corrupted")
        }
        return Triple(out, truncated, charset.name())
    }

    private fun openTarEntry(file: File, format: Format, path: String, charset: String): InputStream? {
        // 不能用 use{}：命中条目后要带着底层解压流返回调用方，close 时机移交包装流
        val dec = decompressor(file, format)
        try {
            val tar = TarArchiveInputStream(dec, charset)
            var entry = tar.nextTarEntry
            while (entry != null) {
                val name = normalizeName(entry.name)
                if (!entry.isDirectory && name == path) {
                    // TarArchiveInputStream 在当前条目边界内读取、越过边界返回 -1
                    return boundedStream(tar, entry.size, onClosed = { runCatching { dec.close() } })
                }
                entry = tar.nextTarEntry
            }
            dec.close()
            return null
        } catch (t: Throwable) {
            runCatching { dec.close() }
            throw t
        }
    }

    private fun exportTarToZip(file: File, format: Format, zipOut: java.util.zip.ZipOutputStream, cancelCheck: () -> Unit, charset: String): Int {
        var count = 0
        decompressor(file, format).use { dec ->
            TarArchiveInputStream(dec, charset).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val name = normalizeName(entry.name)
                    if (!entry.isDirectory && name.isNotEmpty()) {
                        cancelCheck()
                        zipOut.putNextEntry(ZipEntry(name).apply { time = entry.modTime.time })
                        var remaining = entry.size
                        val buf = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val n = tar.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            zipOut.write(buf, 0, n)
                            remaining -= n
                        }
                        zipOut.closeEntry()
                        count++
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        return count
    }

    /** 按格式建立解压流（tar 为透传）。zstd 原生缺失抛 UnsatisfiedLinkError，由 browse 收敛。 */
    private fun decompressor(file: File, format: Format): InputStream {
        val raw = BufferedInputStream(file.inputStream())
        return when (format) {
            Format.ZIP -> throw IOException("ZIP must not take the tar path")
            Format.TAR -> raw
            Format.TAR_GZ -> GzipCompressorInputStream(raw)
            Format.TAR_BZ2 -> BZip2CompressorInputStream(raw)
            Format.TAR_ZST -> ZstdInputStream(raw)
        }
    }

    // ---------------- 公共 ----------------

    private fun buildEntry(
        rawName: String,
        isDirectory: Boolean,
        size: Long,
        compressedSize: Long,
        time: Long,
        encrypted: Boolean,
    ): Entry {
        val path = normalizeName(rawName).trimEnd('/')
        val dir = isDirectory || rawName.endsWith("/")
        val segments = path.split('/')
        val depth = (segments.size - 1).coerceAtLeast(0)
        return Entry(
            path = path,
            name = segments.lastOrNull()?.takeIf { it.isNotEmpty() } ?: path,
            isDirectory = dir,
            size = if (dir) 0L else size,
            compressedSize = compressedSize,
            lastModified = time,
            isEncrypted = encrypted && !dir,
            depth = depth,
            parentPath = segments.dropLast(1).joinToString("/"),
        )
    }

    /** 归一化包内路径：统一 '/'、去前导 ./ 与 /、折叠空段。仅用于展示与匹配，不落盘。 */
    internal fun normalizeName(raw: String): String {
        var s = raw.replace('\\', '/')
        while (s.startsWith("./")) s = s.substring(2)
        s = s.trimStart('/')
        return s.split('/').filter { it.isNotEmpty() }.joinToString("/")
    }

    /**
     * 将 [input] 包装为最多读 [limit] 字节的流；[onClosed] 在 close 时回调（释放底层
     * 解压流）。TarArchiveInputStream 自带条目边界（越过返回 -1），limit 只是防御。
     */
    private fun boundedStream(input: InputStream, limit: Long, onClosed: () -> Unit): InputStream =
        object : FilterInputStream(input) {
            var remaining = limit
            override fun read(): Int {
                if (remaining <= 0) return -1
                val r = super.read()
                if (r >= 0) remaining--
                return r
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val r = super.read(b, off, minOf(len.toLong(), remaining).toInt())
                if (r > 0) remaining -= r
                return r
            }

            override fun close() {
                runCatching { super.close() }
                onClosed()
            }
        }

    /** "UTF-8"/"GBK" 标签 → Charset（openEntry/export 复用 browse 判定的字符集）。 */
    private fun String.toCharsetCompat(): Charset = if (this == "GBK") CHARSET_GBK else CHARSET_UTF8

/** 遍历 cause 链：是否由 zstd 原生库缺失（UnsatisfiedLinkError）引起。 */
private fun Throwable.hasUnsatisfiedLinkCause(): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if (cur is UnsatisfiedLinkError) return true
        // zstd-jni 1.5.7 在 JVM 上把 UnsatisfiedLinkError 嵌进
        // ExceptionInInitializerError 的消息文本而非 cause 链，需按消息兜底。
        if (cur.message?.contains("UnsatisfiedLinkError") == true) return true
        if (cur.cause === cur) break
        cur = cur.cause
    }
    return false
}
}
