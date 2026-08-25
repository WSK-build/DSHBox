package com.dshbox.app.util

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * ZIP / TAR.GZ 安全解压。
 *
 * 安全约束（防 Zip Slip / Tar Slip 路径穿越）：
 * - 所有条目路径经 canonical 归一后必须位于目标目录内，绝对路径 / `..` / 非法段直接拒绝；
 * - 符号链接 / 硬链接条目解析后越界的直接拒绝；
 * - 设备节点 / socket / FIFO 条目跳过；
 * - 目标目录必须先创建，解压时保持目录结构。
 *
 * 解压过程支持进度回调与协程取消，取消时调用方负责清理半成品。
 */
object ArchiveExtractor {

    /** 支持的归档格式。 */
    enum class Format { ZIP, TAR_GZ }

    private val zipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val zipMagicEmpty = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val gzipMagic = byteArrayOf(0x1F.toByte(), 0x8B.toByte())

    /** 检测文件是否 zip 或 gzip（tar.gz），按魔数判断，不受扩展名欺骗。 */
    fun detectFormat(file: File): Format? {
        if (!file.isFile) return null
        return runCatching {
            file.inputStream().buffered().use { input ->
                val head = ByteArray(4)
                val read = input.read(head)
                if (read < 2) return null
                when {
                    head[0] == zipMagic[0] && head[1] == zipMagic[1] -> Format.ZIP
                    head[0] == gzipMagic[0] && head[1] == gzipMagic[1] -> Format.TAR_GZ
                    else -> null
                }
            }
        }.getOrNull()
    }

    /**
     * 解压 [archive] 到 [destDir]。返回解压条目数。
     * [listener] 收到字节进度；阶段文案由调用方组合。
     *
     * **约定**：[destDir] 必须是本次操作专用的目标目录（调用方传临时目录）；
     * 解压失败或取消时自动清理 [destDir] 全部内容，避免半成品残留（S1）。
     */
    suspend fun extract(
        archive: File,
        destDir: File,
        listener: ProgressListener?,
    ): Int {
        destDir.mkdirs()
        val destRoot = destDir.canonicalFile
        return try {
            when (detectFormat(archive)) {
                Format.ZIP -> extractZip(archive, destRoot, listener)
                Format.TAR_GZ -> extractTarGz(archive, destRoot, listener)
                null -> throw FileOpException("无法识别的压缩包格式（仅支持 ZIP / TAR.GZ）")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            FileOps.deleteQuietly(destDir)
            throw e
        } catch (e: Throwable) {
            FileOps.deleteQuietly(destDir)
            throw e
        }
    }

    private suspend fun extractZip(archive: File, destRoot: File, listener: ProgressListener?): Int {
        val total = archive.length()
        var done = 0L
        var count = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    val dir = safeResolve(destRoot, entry.name)
                    dir.mkdirs()
                } else {
                    val target = safeResolve(destRoot, entry.name)
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        FileOps.copyStream(zip, out, offset = done, total = total, stage = "解压中", listener = listener)
                    }
                    count++
                }
                done += entry.compressedSize
                listener?.onProgress(done, total, "解压中")
                zip.closeEntry()
            }
        }
        listener?.onProgress(total, total, "解压完成")
        return count
    }

    private suspend fun extractTarGz(archive: File, destRoot: File, listener: ProgressListener?): Int {
        val total = archive.length()
        var done = 0L
        var lastBytes = 0L
        var count = 0
        GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive))).use { gzip ->
            TarArchiveInputStream(gzip).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()
                    val name = entry.name.trimStart('/')
                    if (name.isNotEmpty()) {
                        when {
                            entry.isDirectory -> {
                                safeResolve(destRoot, name).mkdirs()
                            }
                            entry.isFile -> {
                                val target = safeResolve(destRoot, name)
                                target.parentFile?.mkdirs()
                                FileOutputStream(target).use { out ->
                                    FileOps.copyStream(tar, out, offset = done, total = total, stage = "解压中", listener = listener)
                                }
                                count++
                            }
                            entry.isSymbolicLink || entry.isLink -> {
                                // 拒绝越界链接；越界一律抛错中止
                                val linkTarget = resolveLinkTarget(destRoot, name, entry.linkName)
                                if (!isWithin(linkTarget, destRoot)) {
                                    throw FileOpException("压缩包含越界链接，已中止：$name -> ${entry.linkName}")
                                }
                                // 安全范围内的链接：普通文件内容复制（Android 无法可靠创建 symlink，保持数据）
                                val target = safeResolve(destRoot, name)
                                if (linkTarget.isFile) {
                                    target.parentFile?.mkdirs()
                                    linkTarget.inputStream().use { ins ->
                                        FileOutputStream(target).use { out -> ins.copyTo(out) }
                                    }
                                    count++
                                }
                            }
                            else -> Unit // 跳过设备 / socket / fifo
                        }
                    }
                    // S5: 按 gzip 已消费的压缩字节增量累计，与 total(=archive.length()) 同量纲，
                    // 避免用解压字节(tar.bytesRead)对比压缩总量导致进度早满/超算
                    val currentBytes = gzip.compressedCount
                    done += (currentBytes - lastBytes)
                    lastBytes = currentBytes
                    listener?.onProgress(done, total, "解压中")
                    entry = tar.nextEntry
                }
            }
        }
        listener?.onProgress(total, total, "解压完成")
        return count
    }

    /** 将条目名安全解析为目标内文件：拒绝绝对路径、`..`、空段导致越界。 */
    private fun safeResolve(destRoot: File, name: String): File {
        val cleaned = name.replace('\\', '/').trimStart('/')
        if (cleaned.isEmpty()) return destRoot
        val parts = cleaned.split('/')
        if (parts.any { it == ".." }) {
            throw FileOpException("压缩包含非法路径，已拦截：$name")
        }
        if (parts.any { it.isEmpty() }) {
            // 容忍空段（如 ./a/b），仅过滤
        }
        val target = File(destRoot, parts.joinToString(File.separator)).canonicalFile
        if (!isWithin(target, destRoot)) {
            throw FileOpException("压缩包路径越界，已拦截：$name")
        }
        return target
    }

    /** 解析链接目标（绝对链接以 destRoot 为根，相对链接以条目所在目录为基）。 */
    private fun resolveLinkTarget(destRoot: File, entryName: String, linkName: String): File {
        val targetName = if (linkName.startsWith('/')) {
            linkName.trimStart('/')
        } else {
            val parent = entryName.substringBeforeLast('/', "")
            if (parent.isEmpty()) linkName else "$parent/$linkName"
        }
        val cleaned = targetName.replace('\\', '/')
        if (cleaned.split('/').any { it == ".." }) {
            // 允许在解压根内解析，最终 isWithin 校验
        }
        return File(destRoot, cleaned).canonicalFile
    }

    private fun isWithin(path: File, root: File): Boolean {
        val rootPath = root.absolutePath.trimEnd(File.separatorChar)
        val pathPath = path.absolutePath
        return pathPath == rootPath || pathPath.startsWith("$rootPath${File.separator}")
    }

    /** 文件是否为压缩包（按扩展名快速判断，供 UI 区分「导入文件 / 导入压缩包并解压」入口）。 */
    fun isArchiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz")
    }
}
