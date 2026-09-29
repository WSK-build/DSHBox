package com.dshbox.app.sandbox

import java.io.File

/**
 * 清理 `.dsh` 下**已死的写者锁**。
 *
 * 背景（机制）：`dsh-atomic-write` 用 `wx` 独占创建 `<file>.lock`（内容为持有者 PID），
 * 在 `finally` 里删除；等待上限 2 秒，且**竞争者不会清理已存在的锁**。因此持有者若被强杀，
 * 这把锁会永久留下，之后每次启动都在该锁上超时失败（`.credentials.yaml.lock` 就是这样
 * 让 DSH 完全起不来的）。启动前清掉"持有者已不存在"的锁是唯一的自动免疫手段。
 *
 * 判定纪律（宁可不删，不可错删）：
 * 1. 只处理文件名以 `.lock` 结尾的文件；只读文件头部少量字节。
 * 2. 锁内容必须是纯十进制 PID；解析不出来 → **不动**（不是我们所知的形态）。
 * 3. `/proc/<pid>` 存在 → 有活着的持有者 → **不动**（PID 被复用时会保守地保留死锁，
 *    这是可接受的一侧：错误方向更贵）。
 * 4. `/proc` 自身不可用（自检失败）→ **一把都不删**，只记一条日志。
 *
 * 不涉及 `.dsh` 下任何非锁文件；不写入、不移动任何内容。
 */
internal class DshWriterLockSweeper(
    private val workspaceDir: File,
    /** PID 存活判定；由调用方注入，便于单测。 */
    private val isProcessAlive: (Long) -> Boolean,
    /** 自检：判定链路是否可用（例如本进程自己的 PID 必须被判为存活）。 */
    private val selfCheck: () -> Boolean,
) {

    data class Result(
        val scanned: Int,
        val removed: List<String>,
        /** 判定链路不可用时为 true：本次未删任何东西。 */
        val skipped: Boolean = false,
    )

    fun sweep(): Result {
        val root = File(workspaceDir, DSH_RELATIVE_DIR)
        if (!root.isDirectory) return Result(scanned = 0, removed = emptyList())
        if (!selfCheck()) {
            return Result(scanned = 0, removed = emptyList(), skipped = true)
        }
        val locks = mutableListOf<File>()
        collectLocks(root, depth = 0, into = locks)
        val removed = mutableListOf<String>()
        locks.forEach { lock ->
            val pid = readPid(lock) ?: return@forEach
            if (isProcessAlive(pid)) return@forEach
            if (lock.delete()) {
                removed += lock.relativeTo(workspaceDir).path
            }
        }
        return Result(scanned = locks.size, removed = removed)
    }

    private fun collectLocks(dir: File, depth: Int, into: MutableList<File>) {
        if (depth > MAX_DEPTH) return
        val children = dir.listFiles() ?: return
        children.forEach { child ->
            when {
                child.isDirectory -> collectLocks(child, depth + 1, into)
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
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > PID_MAX_DIGITS) return null
        if (!trimmed.all { it in '0'..'9' }) return null
        return trimmed.toLongOrNull()?.takeIf { it > 0 }
    }

    private companion object {
        /** `.dsh` 在工作区内的相对路径。 */
        const val DSH_RELATIVE_DIR = ".dsh"

        const val LOCK_SUFFIX = ".lock"

        /** 会话目录层级有限，6 层足够覆盖 `sessions/<ws>/<session>/`。 */
        const val MAX_DEPTH = 6

        const val PID_READ_BYTES = 32

        const val PID_MAX_DIGITS = 10
    }
}
