package com.dshbox.app.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导入 / 导出 / 删除等核心文件 IO 操作。
 *
 * - 全部操作带实时进度回调（[ProgressListener]）与协程取消支持；
 * - 进度为**总体单调累计**：调用方传入已累计字节 [offset] 与总体积 [total]，
 *   复制循环内上报 `offset + 已复制字节`，保证进度条不跳变；
 * - 取消通过协程 Job 实现：复制 / 打包循环内定期 `ensureActive()`；
 *   导入 / 解压的半成品由调用方在 `catch(CancellationException)` 中调用
 *   [deleteQuietly] 清理；
 * - 失败时抛出带具体原因 [FileOpException]，UI 展示真实错误而非笼统的「操作失败」。
 */
class FileOpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 导出到 SAF 目录时统一使用的 MIME（避免按扩展名猜测 MIME 导致创建失败）。 */
private const val GENERIC_MIME = "application/octet-stream"

/** 进度回调：done/total 单位字节或条目；total <= 0 表示未知总量。 */
fun interface ProgressListener {
    fun onProgress(done: Long, total: Long, stage: String)
}

object FileOps {

    /**
     * 带进度与取消检查的流复制。
     * [offset] 为该文件开始前的已累计字节；[total] 为整批操作总字节（用于百分比）。
     * listener 收到的是单调递增的总体进度。
     */
    suspend fun copyStream(
        input: InputStream,
        output: OutputStream,
        offset: Long,
        total: Long,
        stage: String,
        listener: ProgressListener?,
        bufferSize: Int = DEFAULT_BUFFER_SIZE,
    ) {
        val buffer = ByteArray(bufferSize)
        var fileDone = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            fileDone += read
            if (fileDone % (bufferSize * 32) == 0L || read == 0) {
                listener?.onProgress(offset + fileDone, total, stage)
            }
        }
        output.flush()
        listener?.onProgress(offset + fileDone, total, stage)
    }

    /** 安静地删除文件或目录（null 或不存在时忽略）。 */
    fun deleteQuietly(file: File?) {
        if (file == null) return
        runCatching { if (file.exists()) file.deleteRecursively() }
    }

    /**
     * 从 ContentResolver 导入单个文件到 [targetDir]，返回落盘文件名。
     * [resolvedName] 为调用方已按冲突策略确定的最终文件名。
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        targetDir: File,
        resolvedName: String,
        listener: ProgressListener?,
        stage: String = "导入中",
    ): File {
        targetDir.mkdirs()
        // 消毒失败（含 /、..、控制字符等）一律拒绝，绝不回退未消毒原名，防路径穿越
        val safe = sanitizeFileName(resolvedName)
            ?: throw FileOpException("文件名无效，已拒绝导入：$resolvedName")
        val target = File(targetDir, safe)
        val resolver = context.contentResolver
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val input = resolver.openInputStream(uri)
            ?: throw FileOpException("无法打开所选文件（openInputStream 返回 null）")
        input.use { ins ->
            target.outputStream().use { out ->
                copyStream(ins, out, offset = 0L, total = size, stage = stage, listener = listener)
            }
        }
        return target
    }

    /**
     * 批量导出到用户选定的 SAF 目录：保留相对目录结构，无绝对路径。返回导出文件数。
     * [conflictMode] 决定目标已存在同名文件时的处理：OVERWRITE 覆盖 / RENAME 自动改名 / SKIP 跳过。
     */
    suspend fun exportToTree(
        context: Context,
        treeUri: Uri,
        selected: List<File>,
        listener: ProgressListener?,
        stage: String = "导出中",
        conflictMode: ConflictMode = ConflictMode.OVERWRITE,
    ): Int {
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw FileOpException("无法访问所选导出目录")
        val items = computeExportItems(selected)
        val total = items.sumOf { totalSize(it.first) }
        var done = 0L
        var count = 0
        for ((src, rel) in items) {
            currentCoroutineContext().ensureActive()
            val segs = rel.split(File.separatorChar).filter { it.isNotBlank() && it != "." }
            if (src.isDirectory) {
                // 单次遍历：目录建节点、文件写入
                src.walkTopDown().forEach { f ->
                    currentCoroutineContext().ensureActive()
                    val relPath = if (f == src) "" else f.relativeTo(src).path
                    val targetSegs = buildList {
                        addAll(segs)
                        if (relPath.isNotEmpty()) addAll(relPath.split(File.separatorChar))
                    }
                    if (f.isDirectory) {
                        var node = rootDoc
                        for (seg in targetSegs) {
                            node = node.findFile(seg) ?: node.createDirectory(seg)
                                ?: throw FileOpException("无法在导出目录创建子目录 $seg")
                        }
                    } else {
                        val node = resolveTargetNode(rootDoc, targetSegs, conflictMode)
                            ?: return@forEach // SKIP
                        val out = context.contentResolver.openOutputStream(node.uri)
                            ?: throw FileOpException("无法写入导出文件 ${targetSegs.lastOrNull() ?: f.name}")
                        out.use { o ->
                            f.inputStream().use { ins ->
                                copyStream(ins, o, offset = done, total = total, stage = stage, listener = listener)
                            }
                        }
                        done += f.length()
                        count++
                        listener?.onProgress(done, total, stage)
                    }
                }
            } else {
                val node = resolveTargetNode(rootDoc, segs, conflictMode) ?: continue // SKIP
                val out = context.contentResolver.openOutputStream(node.uri)
                    ?: throw FileOpException("无法写入导出文件 ${src.name}")
                out.use { o ->
                    src.inputStream().use { ins ->
                        copyStream(ins, o, offset = done, total = total, stage = stage, listener = listener)
                    }
                }
                done += src.length()
                count++
                listener?.onProgress(done, total, stage)
            }
        }
        listener?.onProgress(total, total, stage)
        return count
    }

    /**
     * 在 SAF 树中按 [segs] 逐段定位/创建目标节点（最后一段为文件）。
     * 返回 null 表示按策略应跳过（SKIP 且已存在）。
     */
    private fun resolveTargetNode(
        rootDoc: DocumentFile,
        segs: List<String>,
        conflictMode: ConflictMode,
    ): DocumentFile? {
        var node = rootDoc
        for (i in 0 until segs.size) {
            val seg = segs[i]
            val isLast = i == segs.lastIndex
            if (isLast) {
                val existing = node.findFile(seg)
                if (existing != null) {
                    return when (conflictMode) {
                        ConflictMode.SKIP -> null
                        ConflictMode.RENAME -> {
                            var counter = 1
                            val dot = seg.lastIndexOf('.')
                            val stem = if (dot > 0) seg.substring(0, dot) else seg
                            val ext = if (dot > 0) seg.substring(dot) else ""
                            var candidate: DocumentFile?
                            do {
                                candidate = node.findFile("${stem}-$counter$ext")
                                counter++
                            } while (candidate != null)
                            candidate = node.createFile(GENERIC_MIME, "${stem}-${counter - 1}$ext")
                                ?: throw FileOpException("无法在导出目录创建文件 $seg")
                            candidate
                        }
                        ConflictMode.OVERWRITE -> existing
                    }
                }
                return node.createFile(GENERIC_MIME, seg)
                    ?: throw FileOpException("无法在导出目录创建文件 $seg")
            } else {
                node = node.findFile(seg) ?: node.createDirectory(seg)
                    ?: throw FileOpException("无法在导出目录创建子目录 $seg")
            }
        }
        throw FileOpException("导出目标路径为空")
    }

    /**
     * 将所选文件/目录打包为 ZIP 写入 [zipUri]。
     * 保留相对目录结构，压缩包内不含绝对路径。取消时删除已创建的压缩包。
     */
    suspend fun exportToZip(
        context: Context,
        zipUri: Uri,
        selected: List<File>,
        listener: ProgressListener?,
        stage: String = "打包中",
    ): Int {
        val items = computeExportItems(selected)
        val total = items.sumOf { totalSize(it.first) }
        var done = 0L
        var count = 0
        try {
            val out = context.contentResolver.openOutputStream(zipUri)
                ?: throw FileOpException("无法创建压缩包文件")
            out.use { stream ->
                ZipOutputStream(stream).use { zip ->
                    for ((src, rel) in items) {
                        currentCoroutineContext().ensureActive()
                        if (src.isDirectory) {
                            val base = rel.trimEnd(File.separatorChar)
                            zip.putNextEntry(ZipEntry("$base/"))
                            zip.closeEntry()
                            src.walkTopDown().forEach { f ->
                                currentCoroutineContext().ensureActive()
                                if (f.isDirectory) {
                                    val relDir = f.relativeTo(src).path
                                    if (relDir != ".") {
                                        zip.putNextEntry(ZipEntry("$base/$relDir/"))
                                        zip.closeEntry()
                                    }
                                } else {
                                    val relFile = f.relativeTo(src).path
                                    zip.putNextEntry(ZipEntry("$base/$relFile"))
                                    f.inputStream().use { ins ->
                                        copyStream(ins, zip, offset = done, total = total, stage = stage, listener = listener)
                                    }
                                    zip.closeEntry()
                                    done += f.length()
                                    count++
                                    listener?.onProgress(done, total, stage)
                                }
                            }
                        } else {
                            zip.putNextEntry(ZipEntry(rel))
                            src.inputStream().use { ins ->
                                copyStream(ins, zip, offset = done, total = total, stage = stage, listener = listener)
                            }
                            zip.closeEntry()
                            done += src.length()
                            count++
                            listener?.onProgress(done, total, stage)
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消时删除半成品压缩包
            runCatching { context.contentResolver.delete(zipUri, null, null) }
            throw e
        }
        listener?.onProgress(total, total, stage)
        return count
    }

    /** 递归统计文件大小（目录为子树文件总字节）。 */
    fun totalSize(file: File): Long = when {
        file.isDirectory -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        file.isFile -> file.length()
        else -> 0L
    }

    /** 计算导出相对路径的公共祖先，使多选导出保留目录结构。 */
    private fun computeExportItems(selected: List<File>): List<Pair<File, String>> {
        if (selected.isEmpty()) return emptyList()
        val containers = selected.map { if (it.isDirectory) it else (it.parentFile ?: it) }
        val base = commonAncestor(containers)
        return selected.map { src ->
            val rel = src.relativeTo(base).path
            src to (if (rel == "." || rel.startsWith("..")) src.name else rel)
        }
    }

    /** 求一组目录的最近公共祖先目录。 */
    private fun commonAncestor(dirs: List<File>): File {
        if (dirs.isEmpty()) return File("/")
        var common = dirs.first().absolutePath
        for (d in dirs.drop(1)) {
            val a = common.trimEnd(File.separatorChar)
            val b = d.absolutePath.trimEnd(File.separatorChar)
            var i = 0
            while (i < a.length && i < b.length && a[i] == b[i]) i++
            if (i == 0) {
                common = File.separator
            } else {
                val cut = a.lastIndexOf(File.separatorChar, i - 1)
                common = a.substring(0, if (cut <= 0) 1 else cut)
            }
        }
        return File(common)
    }
}
