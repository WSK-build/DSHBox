package com.dshbox.app.util.viewer

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files

/**
 * 文本保存链路（1.2.0 §6.4，纯 JVM 文件操作）。
 *
 * 1. **原子写**：写 `name.dsh-tmp` → flush/fsync → 替换原文件，杜绝写一半崩溃损坏原文件；
 * 2. **元数据保留**：原子替换产生新 inode，必须把原文件 rwx 权限位（rootfs 脚本必须
 *    保持 x 位）与 lastModified 复制到新文件；恢复后**逐项校验**（返工修正：失败不再
 *    静默），File API 失败再尝试 NIO POSIX chmod，仍失败则以警告随
 *    [SaveOutcome.Success] 透传给 UI（用户可见「已保存但权限位恢复失败」）；
 * 3. **外部变更检测**：打开时记录 [ContentFingerprint]（size + mtime + 首尾各 64KB
 *    SHA-256）；保存前复查，不一致 → 返回 [SaveOutcome.ExternalChanged]，UI 弹
 *    「文件已被外部修改：用我的覆盖 / 重新载入 / 另存」；
 * 4. **新建文件语义**（返工修正）：`expected == null` 且目标不存在 = 新建，直接写入；
 *    `expected != null` 而目标已消失 = 被外部删除，仍走 ExternalChanged(null)；
 * 5. 加载时顺带清理本文件遗留的 `.dsh-tmp`（上次写盘中途崩溃的残留）。
 */
object TextFileStore {

    /** 原子写临时后缀。 */
    const val TEMP_SUFFIX = ".dsh-tmp"

    /** 加载结果：原始字节（供编码切换重复解码）+ 打开时刻指纹。 */
    class LoadedFile(val bytes: ByteArray, val fingerprint: ContentFingerprint?)

    /** 读取文件原始字节与指纹（IO，调用方负责线程）。 */
    fun load(file: File): LoadedFile? {
        if (!file.isFile) return null
        return runCatching {
            // 清理本文件上次写盘中途崩溃的临时残留（仅匹配自身名 + 后缀，不触碰他物）
            val stale = File(file.parentFile, file.name + TEMP_SUFFIX)
            if (stale.isFile) stale.delete()
            val bytes = file.readBytes()
            LoadedFile(bytes, ContentFingerprint.of(file))
        }.getOrNull()
    }

    sealed class SaveOutcome {
        /**
         * 保存成功，携带保存后的新指纹（调用方更新当前指纹）。
         * [warning] 非空 = 内容已写入但元数据（rwx/时间戳）恢复未完全成功，
         * UI 必须把警告文案展示给用户（不得静默）。
         */
        data class Success(val fingerprint: ContentFingerprint, val warning: String? = null) : SaveOutcome()

        /**
         * 外部已修改/已删除，**未写入**。[current] 为当前文件指纹（null = 文件已不存在）。
         * UI 三选一：用我的覆盖（force 重试）/ 重新载入 / 取消（另存走 SAF 导出）。
         */
        data class ExternalChanged(val current: ContentFingerprint?) : SaveOutcome()

        data class Failed(val message: String) : SaveOutcome()
    }

    /**
     * 保存 [bytes] 到 [file]。
     *
     * @param expected 打开时记录的指纹（null = 新建/打开时无指纹）。
     * @param force true = 跳过外部变更检查直接覆盖（「用我的覆盖」决策后）。
     */
    fun save(
        file: File,
        bytes: ByteArray,
        expected: ContentFingerprint?,
        force: Boolean = false,
    ): SaveOutcome {
        if (!force) {
            val fileExists = file.isFile
            val current = if (fileExists) ContentFingerprint.of(file) else null
            when {
                // 新建（expected == null 且目标不存在）：直接写入（返工修正 #5）
                !fileExists && expected == null -> {}
                // 打开过但已被外部删除
                !fileExists -> return SaveOutcome.ExternalChanged(null)
                // 目标空文件且无指纹可比对：视为未变更直接覆盖
                expected == null && current?.size == 0L -> {}
                current != expected -> return SaveOutcome.ExternalChanged(current)
            }
        }
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            return SaveOutcome.Failed("目录不可用：${parent.absolutePath}")
        }
        val tmp = File(parent ?: File("."), file.name + TEMP_SUFFIX)
        val existed = file.isFile
        val srcReadable = if (existed) file.canRead() else true
        val srcWritable = if (existed) file.canWrite() else true
        val srcExecutable = if (existed) file.canExecute() else false
        val srcMtime = if (existed) file.lastModified() else System.currentTimeMillis()
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                runCatching { out.fd.sync() }
            }
            // 替换：POSIX renameTo 可覆盖既有目标；失败（Windows 等）退化为先删后改名
            if (!tmp.renameTo(file)) {
                if (file.exists() && !file.delete()) {
                    return SaveOutcome.Failed("无法替换目标文件：${file.absolutePath}")
                }
                if (!tmp.renameTo(file)) {
                    // 最后兜底：复制覆盖（非原子，仅极端场景）
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return SaveOutcome.Failed(e.message ?: "写入失败")
        }
        // 元数据复制：rwx 权限位（rootfs 可执行位至关重要）+ lastModified。
        // 返工修正 #4：不再静默吞异常——逐项校验，File API 失败重试 NIO POSIX，
        // 仍未恢复的差异以警告透传（UI 必须展示）。
        val warning = restoreMetadata(file, existed, srcReadable, srcWritable, srcExecutable, srcMtime)
        val fingerprint = ContentFingerprint.of(file)
            ?: return SaveOutcome.Failed("保存后校验失败：无法读取 ${file.name}")
        return SaveOutcome.Success(fingerprint, warning)
    }

    /**
     * 恢复 rwx/lastModified 并校验；返回 null = 全部恢复，非 null = 用户可读的警告文案。
     */
    private fun restoreMetadata(
        file: File,
        existed: Boolean,
        srcReadable: Boolean,
        srcWritable: Boolean,
        srcExecutable: Boolean,
        srcMtime: Long,
    ): String? {
        if (!existed) return null
        val failures = mutableListOf<String>()
        runCatching {
            file.setReadable(srcReadable)
            file.setWritable(srcWritable)
            file.setExecutable(srcExecutable)
            file.setLastModified(srcMtime)
        }.onFailure { failures.add("元数据设置异常：${it.message ?: "未知错误"}") }
        // File API 校验失败 → NIO POSIX chmod 重试（JVM/Android API26+ 均可用；
        // 非 POSIX 文件系统该调用抛 UnsupportedOperationException，视为不可恢复）
        if (file.canRead() != srcReadable || file.canWrite() != srcWritable || file.canExecute() != srcExecutable) {
            runCatching {
                // 与 File.setReadable/Write/Executable 同语义：owner/group/other 三层统一
                val perms = java.util.EnumSet.noneOf(java.nio.file.attribute.PosixFilePermission::class.java)
                if (srcReadable) {
                    perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_READ)
                    perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_READ)
                    perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)
                }
                if (srcWritable) {
                    perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
                    perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
                    perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
                }
                if (srcExecutable) {
                    perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)
                    perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE)
                    perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE)
                }
                Files.setPosixFilePermissions(file.toPath(), perms)
            }.onFailure { failures.add("POSIX chmod 重试失败：${it.message ?: "未知错误"}") }
        }
        if (file.canRead() != srcReadable) failures.add("读权限未恢复")
        if (file.canWrite() != srcWritable) failures.add("写权限未恢复")
        if (file.canExecute() != srcExecutable) {
            failures.add(if (srcExecutable) "可执行位未恢复（脚本可能无法执行，请手动 chmod +x）" else "可执行位未能去除")
        }
        if (file.lastModified() != srcMtime) failures.add("修改时间未能还原")
        return if (failures.isEmpty()) null else "已保存，但元数据恢复不完整：${failures.joinToString("；")}"
    }

    /** 另存到 [target]（SAF「另存」路径落盘物理文件后调用；不做变更检测）。 */
    fun saveAs(target: File, bytes: ByteArray): SaveOutcome = save(target, bytes, expected = null, force = true)

    /** 快速读取文件尾部字节（外部变更提示后「重新载入」等场景的通用读入口）。 */
    fun readAll(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(raf.length().toInt())
            raf.readFully(buf)
            buf
        }
    }.getOrNull()
}
