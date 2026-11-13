package com.dshbox.app.sandbox

import java.io.File
import java.nio.file.Files

/** 清理已退出写者的 PID 锁；历史空锁只交给持有内核排他租约的恢复器。 */
internal class DshWriterLockSweeper(
    private val workspaceDir: File,
    /** 实例 home 相对目录名（主实例 `.dsh`；应急实例 `.dsh-emergency`）。 */
    private val homeRelativeDir: String = DSH_RELATIVE_DIR,
    /** PID 存活判定；由调用方注入，便于单测。 */
    private val isProcessAlive: (Long) -> Boolean,
    /** 自检：判定链路是否可用（例如本进程自己的 PID 必须被判为存活）。 */
    private val selfCheck: () -> Boolean,
    /** 恢复器须持有内核写租约；不支持租约或仍有打开的句柄时保留。 */
    private val recoverEmpty: (File) -> Boolean = { false },
) {

    data class Result(
        val scanned: Int,
        val removed: List<String>,
        /** 判定链路不可用时为 true：本次未删任何东西。 */
        val skipped: Boolean = false,
    )

    /** 空锁年龄不参与判断；只由内核确认没有打开的文件描述符。 */
    fun sweep(): Result {
        val root = File(workspaceDir, homeRelativeDir)
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return Result(scanned = 0, removed = emptyList())
        if (!selfCheck()) {
            return Result(scanned = 0, removed = emptyList(), skipped = true)
        }
        val locks = mutableListOf<File>()
        collectLocks(root, depth = 0, into = locks)
        val removed = mutableListOf<String>()
        locks.forEach { lock ->
            if (lock.length() == 0L) {
                if (lock.parentFile == root && lock.name == ".credentials.yaml.lock" && recoverEmpty(lock)) {
                    removed += relativePath(lock)
                }
                return@forEach
            }
            val pid = readPid(lock) ?: return@forEach
            if (isProcessAlive(pid)) return@forEach
            if (readPid(lock) == pid && lock.delete()) removed += relativePath(lock)
        }
        return Result(scanned = locks.size, removed = removed)
    }

    private fun relativePath(lock: File): String = lock.relativeTo(workspaceDir).path

    private fun collectLocks(dir: File, depth: Int, into: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        children.forEach { child ->
            val symbolic = runCatching { Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)
            if (symbolic) return@forEach
            when {
                child.isDirectory -> if (child.name !in SKIPPED_DIRS) collectLocks(child, depth + 1, into)
                child.isFile && child.name.endsWith(LOCK_SUFFIX) -> into += child
            }
        }
    }

    /** 锁内容为持有者 PID（十进制）；其余形态一律返回 null（不猜）。 */
    private fun readPid(lock: File): Long? {
        val text = runCatching {
            lock.inputStream().use { stream ->
                val buffer = ByteArray(PID_READ_BYTES)
                val read = stream.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.US_ASCII)
            }
        }.getOrNull() ?: return null
        if (!text.endsWith("\n")) return null
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > PID_MAX_DIGITS) return null
        if (!trimmed.all { it in '0'..'9' }) return null
        return trimmed.toLongOrNull()?.takeIf { it > 0 }
    }

    private companion object {
        /** `.dsh` 在工作区内的相对路径。 */
        const val DSH_RELATIVE_DIR = ".dsh"

        const val LOCK_SUFFIX = ".lock"

        /** 不进入的目录：插件依赖包与 git 仓库元数据，其中的 `.lock` 不是写者锁。 */
        val SKIPPED_DIRS = setOf("node_modules", ".git")

        /** 会话目录层级有限，6 层足够覆盖 `sessions/<ws>/<session>/`。 */
        const val MAX_DEPTH = 6

        const val PID_READ_BYTES = 32

        const val PID_MAX_DIGITS = 10
    }
}
