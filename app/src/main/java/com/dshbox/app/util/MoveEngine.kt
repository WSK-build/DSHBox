package com.dshbox.app.util

import com.dshbox.app.R
import com.dshbox.app.common.UiText
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 移动引擎（1.2.0 §5.3；§7.3 模块化收尾自 FileOps.kt 纯搬移拆出，行为零变化）。
 *
 * 单项执行序（§5.3）：目标无同名 → renameTo（同卷原子）；renameTo 失败 → 此时才比较
 * 空间并走 `.dsh-moving-*` 复制兜底（同步 rwx/时间戳、字节校验）；目标同名 → OVERWRITE
 * 「先就位后替换」/ 目录递归合并。取消以单个文件为最小不可中断单元；finally 清理本轮
 * 全部中转残留，崩溃残留由启动扫描 cleanupMovingResidualsInAppDirs 覆盖。
 */

/** 单项移动失败明细（路径 + 中文原因）。 */
data class MoveFailure(val source: File, val message: UiText)

/**
 * 批量移动结果（1.2.0 §5.3.7）。取消不打断已完成项：
 * [cancelled] 为 true 时 moved/skipped/failed 如实反映已完成与未处理情况。
 */
data class MoveResult(
    val moved: Int,
    val skipped: Int,
    val failed: List<MoveFailure>,
    val cancelled: Boolean = false,
)

/** `.dsh-moving` 中转临时名前缀（§5.3.3/§5.3.6）。 */
private const val MOVING_TEMP_PREFIX = ".dsh-moving-"

/** 中转残留的精确形态（仅识别本引擎生成的纯数字时间戳后缀，避免误删用户同名文件）。 */
private val MOVING_TEMP_REGEX = Regex("^\\.dsh-moving-\\d+$")

/** 复制兜底的缓冲区大小（64KB）。 */
private const val COPY_BUFFER = 64 * 1024

/**
 * 移动进度累积器：done 单调递增（含当前正在复制的文件的 in-flight 字节）。
 * 重命名类操作按项补齐（advanceTo），复制类操作按实际字节累计（finishFile）。
 */
private class MoveProgress(val listener: ProgressListener?, val total: Long) {
    var done: Long = 0L
    var inFlight: Long = 0L
    private var logicalDone: Long = 0L

    fun report(stage: UiText) {
        listener?.onProgress(minOf(done + inFlight, total), total, stage)
    }

    /** 单文件复制完成：把 in-flight 字节转为已累计。 */
    fun finishFile(bytes: Long, stage: UiText) {
        done += bytes
        inFlight = 0L
        report(stage)
    }

    /** 复制中的文件：更新 in-flight，进度条平滑推进。 */
    fun copyInFlight(bytes: Long, stage: UiText) {
        inFlight = bytes
        report(stage)
    }

    /** 一项完成（重命名路径无逐字节进度，按项大小补齐，保证整体单调到 total）。 */
    fun advanceTo(itemSize: Long, stage: UiText) {
        logicalDone += itemSize
        if (done < logicalDone) {
            done = logicalDone
            inFlight = 0L
            report(stage)
        }
    }
}
/** 从 FileOpException 提取 uiText，兜底走 raw(message) 或指定资源 ID。 */
private fun FileOpException.asUiTextOr(defaultRes: Int): UiText =
    uiText ?: message?.let { UiText.raw(it) } ?: UiText.Res(defaultRes)

object MoveEngine {

/**
 * 批量移动执行引擎（1.2.0 §5.3）。任务来自 [MovePlanner.planMove]（冲突已预演/决策）。
 *
 * 单项执行序：
 * 1. 目标无同名 → `renameTo`（同卷原子，天然保留权限位与时间戳）；
 * 2. `renameTo` 失败（跨挂载点等极端情况）→ **此时才**统计源子树总字节并与目标分区
 *    可用空间比较：不足 → 该项失败（计入结果）继续其余项；充足 → 复制兜底（先复制到
 *    `.dsh-moving-*` 中转位、逐文件同步 rwx 权限位与 lastModified、校验字节一致，
 *    再改名就位、删源）；
 * 3. 目标已同名 → 按 [MoveTask.existing]：OVERWRITE「先就位后替换」（源先移到
 *    `.dsh-moving-*`，确认就位后删旧目标再改名到位）；目录同名 → 递归合并
 *    （[mergeTree]，绝不整体删除目标目录）。
 *
 * 取消（§5.3.5）：以单个文件为最小不可中断单元，`ensureActive()` 只在文件之间检查；
 * 取消即停止后续项、保留已完成项，[MoveResult.cancelled] = true，不做全局回滚。
 *
 * 清理（§5.3.6）：结束（含取消与异常）在 `finally` 中删除本轮产生的全部 `.dsh-moving-*`
 * 中转残留；崩溃/断电遗留由 App 启动时 [cleanupMovingResiduals] 全局清理。
 */
suspend fun moveWithin(
    tasks: List<MoveTask>,
    listener: ProgressListener? = null,
    forceCopyFallback: Boolean = false,
    deleteTarget: (File) -> Boolean = { it.deleteRecursively() },
): MoveResult {
    var moved = 0
    var skipped = 0
    var cancelled = false
    val failed = mutableListOf<MoveFailure>()
    val temps = mutableListOf<File>()
    val sizes = if (listener != null) {
        tasks.map { runCatching { FileOps.totalSize(it.source) }.getOrDefault(0L) }
    } else {
        null
    }
    val progress = MoveProgress(listener, sizes?.sum() ?: -1L)

    try {
        for ((index, task) in tasks.withIndex()) {
            currentCoroutineContext().ensureActive() // 取消检查点：仅在项与项之间
            val source = task.source
            val dest = task.dest
            if (!source.exists()) {
                failed += MoveFailure(source, UiText.Res(R.string.move_err_source_missing))
                continue
            }
            val itemSize = sizes?.get(index) ?: 0L
            progress.report(UiText.Res(R.string.move_progress_moving_name, listOf(source.name)))
            try {
                when {
                    // 目录递归合并（§5.3.4）：绝不整体删除目标目录
                    task.existing == MoveExisting.MERGE_DIR && source.isDirectory && dest.isDirectory -> {
                        val innerSkipped = mergeTreeInternal(
                            source, dest, ConflictMode.OVERWRITE, temps, progress,
                            MergeStats(), deleteTarget,
                        )
                        skipped += innerSkipped
                        moved++
                    }
                    // 覆盖（§5.3.3）：先就位后替换
                    task.existing == MoveExisting.OVERWRITE && dest.exists() -> {
                        replaceViaTemp(source, dest, temps, progress, forceCopyFallback, deleteTarget)
                        moved++
                    }
                    task.existing == MoveExisting.FAIL && dest.exists() -> {
                        // 计划时无冲突、执行时目标仍被占用（并发写入等）：按失败处理，绝不静默覆盖
                        failed += MoveFailure(source, UiText.Res(R.string.move_err_dest_exists, listOf(dest.name)))
                    }
                    else -> {
                        moveSingle(source, dest, temps, progress, forceCopyFallback)
                        moved++
                    }
                }
                progress.advanceTo(itemSize, UiText.Res(R.string.move_progress_moving_name, listOf(source.name)))
            } catch (e: FileOpException) {
                failed += MoveFailure(source, e.asUiTextOr(R.string.move_err_generic))
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        cancelled = true
    } finally {
        // §5.3.6：清理本轮全部 .dsh-moving-* 中转残留（含取消与异常路径）
        for (t in temps) runCatching { if (t.exists()) t.deleteRecursively() }
    }
    return MoveResult(moved, skipped, failed.toList(), cancelled)
}

/**
 * 扫描并删除 [root] 下全部 `.dsh-moving-*` 中转残留（§5.3.6，App 启动时调用，
 * 覆盖崩溃/断电场景）。只匹配本引擎生成的 `\.dsh-moving-\d+` 精确形态。
 * @return 删除的残留数量
 */
fun cleanupMovingResiduals(root: File): Int {
    if (!root.isDirectory) return 0
    val all = root.walkTopDown().filter { MOVING_TEMP_REGEX.matches(it.name) }.toList()
    var count = 0
    val removed = mutableListOf<String>()
    for (f in all) {
        // 已随父残留一起删除的子残留跳过（walkTopDown 保证父先于子出现）
        if (removed.any { f.absolutePath.startsWith("$it${File.separator}") }) continue
        if (runCatching { f.deleteRecursively() }.getOrDefault(false)) {
            removed += f.absolutePath
            count++
        }
    }
    return count
}

/**
 * App 启动清理入口（§5.3.6，审查修正：限定扫描范围）：只遍历移动落点可达的四层目录
 * （base / node / dsh / user-data，含 1.1.x legacy debian 根）——中转位只会创建在移动
 * 目标旁，不会出现在 cacheDir 或 android-side 等其他位置。避免全量递归 filesDir
 * （runtime 资产树数万条目）与沙盒启动争 IO。由 [com.dshbox.app.DshApp] 后台延迟调用。
 * @return 删除的残留数量
 */
fun cleanupMovingResidualsInAppDirs(filesDir: File): Int {
    val current = File(filesDir, "runtime/runtime-current")
    var count = 0
    for (root in arrayOf(
        File(current, "base"),
        File(current, "node"),
        File(current, "dsh"),
        File(current, "debian"),
        File(filesDir, "user-data"),
    )) {
        count += cleanupMovingResiduals(root)
    }
    return count
}

/**
 * 递归合并 [src] 到已存在的目标目录 [destDir]（§5.3.4，原 FilesScreen.mergeTree 下沉）：
 * - 目标不存在的子项：走单项移动（rename 优先 + 复制兜底 + rwx/时间戳同步）；
 * - 同名子项按 [mode]：OVERWRITE 文件替换/目录继续合并，RENAME 自动改名，SKIP 跳过；
 * - 绝不整体删除目标目录（保持 1.1.0 修复后的「不静默丢弃目标子文件」语义）；
 * - 全部子项处理完后删除 [src] 本体。
 *
 * @return 被 SKIP / RENAME 失败跳过的条目数
 */
suspend fun mergeTree(
    src: File,
    destDir: File,
    mode: ConflictMode,
    listener: ProgressListener? = null,
): Int = mergeTreeInternal(
    src, destDir, mode, mutableListOf(), MoveProgress(listener, -1L),
    MergeStats(), deleteTarget = { it.deleteRecursively() },
)

/** 合并聚合计数器：递归层级间共享，失败消息中的「已合并 N 项」跨层级精确（复查修正）。 */
private class MergeStats {
    var moved = 0
}

private suspend fun mergeTreeInternal(
    src: File,
    destDir: File,
    mode: ConflictMode,
    temps: MutableList<File>,
    progress: MoveProgress?,
    stats: MergeStats,
    deleteTarget: (File) -> Boolean,
): Int {
    var skipped = 0
    // 复查修正（可追溯性）：单个子项失败不再立即中断——继续其余子项（§5.3.7
    // 「单文件 IO 失败继续其余」同口径），结束时抛出带「已合并计数 + 首个失败子项」
    // 的异常，用户能看到哪些子项已过去、哪个失败，避免补移时重复/覆盖。
    var firstFailure: UiText? = null
    src.listFiles()?.forEach { child ->
        currentCoroutineContext().ensureActive()
        val dest = File(destDir, child.name)
        try {
            if (!dest.exists()) {
                moveSingle(child, dest, temps, progress)
                stats.moved++
            } else {
                when (mode) {
                    ConflictMode.SKIP -> skipped++
                    ConflictMode.OVERWRITE -> {
                        if (child.isDirectory && dest.isDirectory) {
                            skipped += mergeTreeInternal(child, dest, mode, temps, progress, stats, deleteTarget)
                            stats.moved++
                        } else {
                            replaceViaTemp(child, dest, temps, progress, deleteTarget = deleteTarget)
                            stats.moved++
                        }
                    }
                    ConflictMode.RENAME -> {
                        val newName = resolveConflictName(destDir, child.name, ConflictMode.RENAME)
                        if (newName == null) {
                            skipped++
                        } else {
                            moveSingle(child, File(destDir, newName), temps, progress)
                            stats.moved++
                        }
                    }
                }
            }
        } catch (e: FileOpException) {
            if (firstFailure == null) firstFailure = UiText.Concat(listOf(
                UiText.raw(child.name),
                UiText.Separator(": "),
                e.uiText ?: (e.message?.let { UiText.raw(it) } ?: UiText.Res(R.string.error_unknown)),
            ))
        }
    }
    if (firstFailure != null) {
        // 源目录保留（未成功子项仍在源中）；已合并子项留在目标中，可重新执行补移
        val displayFailure = firstFailure ?: UiText.Res(R.string.error_unknown)
        throw FileOpException(
            "Merged ${stats.moved} item(s) then interrupted at \"$displayFailure\"; merged content remains in target, re-execute to move remaining items",
            uiText = UiText.Concat(listOf(
                UiText.Res(R.string.move_err_merged_interrupted_1, listOf(stats.moved)),
                displayFailure,
                UiText.Res(R.string.move_err_merged_interrupted_2),
            )),
        )
    }
    runCatching { src.deleteRecursively() }
    return skipped
}

/**
 * 单项移动到尚不存在的 [dest]：renameTo 优先；失败才查空间并走复制兜底
 * （§5.3.1–2）。失败抛 [FileOpException]，由调用方计入结果并继续其余项。
 */
private suspend fun moveSingle(
    source: File,
    dest: File,
    temps: MutableList<File>,
    progress: MoveProgress?,
    forceCopyFallback: Boolean = false,
) {
    if (!forceCopyFallback && source.renameTo(dest)) return
    // renameTo 失败（跨挂载点等）：此时才统计源子树字节并与目标分区可用空间比较
    val need = runCatching { FileOps.totalSize(source) }.getOrDefault(0L)
    val usable = dest.parentFile?.usableSpace ?: 0L
    if (need > 0 && usable < need) {
        throw FileOpException(
            "Insufficient space on target partition: need ${formatFileSize(need)}, available ${formatFileSize(usable)}",
            uiText = UiText.Res(R.string.move_err_insufficient_space, listOf(formatFileSize(need), formatFileSize(usable))),
        )
    }
    // 复制兜底：先复制到 .dsh-moving 中转位（校验 + 元数据同步），再改名就位，最后删源
    val temp = stageCopy(source, dest.parentFile, temps, progress)
    if (!temp.renameTo(dest)) {
        try {
            copyTreeWithMeta(temp, dest, progress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 源数据完好，仅清掉我们创建的半成品目标
            runCatching { dest.deleteRecursively() }
            throw e
        } catch (e: Exception) {
            runCatching { dest.deleteRecursively() }
            val detail = e.message?.let { UiText.raw(it) } ?: UiText.Res(R.string.error_unknown)
            throw FileOpException(
                "Copy-and-place failed: ${dest.name} (${e.message ?: "unknown error"})",
                uiText = UiText.Concat(listOf(
                    UiText.Res(R.string.move_err_copy_place_failed, listOf(dest.name)),
                    UiText.Separator("（"),
                    detail,
                    UiText.Separator("）"),
                )),
            )
        }
        runCatching { temp.deleteRecursively() }
    }
    if (!runCatching { source.deleteRecursively() }.getOrDefault(false) && source.exists()) {
        throw FileOpException(
            "Copy completed but failed to delete source: ${source.absolutePath}",
            uiText = UiText.Res(R.string.move_err_delete_source_failed, listOf(source.absolutePath)),
        )
    }
}

/**
 * 覆盖替换（§5.3.3 先就位后替换）：源先移到目标旁 `.dsh-moving-*` 中转位并完整就位，
 * 确认就位后删旧目标、再把中转位改名到位。
 *
 * 数据保全不变量（审查修正）：任何失败路径都绝不允许销毁数据的唯一副本——
 * - 源已 rename 进中转位（[stagedByRename]）且后续步骤失败：先尽力还原回源路径；
 *   还原失败则改名为**不匹配 `\.dsh-moving-\d+$` 清理规则**的 `.dsh-unmoved-*` 保全名
 *   （finally 与启动扫描都不会删除它），并在错误信息中上报路径，交用户手动找回；
 * - 源本体仍在（复制兜底路径）时中转位只是副本，可安全丢弃。
 */
private suspend fun replaceViaTemp(
    source: File,
    dest: File,
    temps: MutableList<File>,
    progress: MoveProgress?,
    forceCopyFallback: Boolean = false,
    deleteTarget: (File) -> Boolean = { it.deleteRecursively() },
) {
    val temp = newMovingTemp(dest.parentFile)
    temps += temp
    // 阶段 1：源数据完整就位到中转位。stagedByRename = 源唯一副本已在中转位。
    val stagedByRename = if (forceCopyFallback) false else source.renameTo(temp)
    if (!stagedByRename) {
        copyTreeWithMeta(source, temp, progress)
    }
    // 阶段 2：删除旧目标。失败 → 先保全数据再抛错，绝不留给 finally 删掉唯一副本。
    if (!runCatching { deleteTarget(dest) }.getOrDefault(false)) {
        val preserved = if (stagedByRename) preserveOrRestore(temp, source, temps) else null
        throw FileOpException(
            "Cannot delete old target ${dest.name}" + preserved?.let { "; source data preserved to ${it.name}" }.orEmpty(),
            uiText = UiText.Concat(buildList {
                add(UiText.Res(R.string.move_err_delete_old_dest, listOf(dest.name)))
                preserved?.let { add(UiText.Res(R.string.move_err_data_preserved, listOf(it.name))) }
            }),
        )
    }
    // 阶段 3：中转位改名到位；失败 → 复制就位 → 均失败/取消时保全数据并清掉 dest 半成品。
    if (!temp.renameTo(dest)) {
        try {
            copyTreeWithMeta(temp, dest, progress)
            runCatching { temp.deleteRecursively() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { dest.deleteRecursively() } // dest 是我们创建的半成品
            if (stagedByRename) preserveOrRestore(temp, source, temps)
            throw e
        } catch (e: Exception) {
            runCatching { dest.deleteRecursively() }
            val preserved = if (stagedByRename) preserveOrRestore(temp, source, temps) else null
            val detail = if (e is FileOpException) e.uiText else e.message?.let { UiText.raw(it) }
            throw FileOpException(
                "Replace \"${dest.name}\" failed: ${e.message ?: "copy-and-place failed"}" +
                    preserved?.let { "; source data preserved to ${it.name}" }.orEmpty(),
                uiText = UiText.Concat(buildList {
                    add(UiText.Res(R.string.move_err_replace_failed_no_detail, listOf(dest.name)))
                    if (detail != null) {
                        add(UiText.Separator("："))
                        add(detail)
                    }
                    preserved?.let { add(UiText.Res(R.string.move_err_data_preserved, listOf(it.name))) }
                }),
            )
        }
    }
    // 若源是复制到中转位的（rename 失败过），源本体仍在，就位成功后删除
    if (source.exists()) runCatching { source.deleteRecursively() }
}

/**
 * 数据保全（审查修正）：中转位持有数据唯一副本时的失败收尾。
 * 1) 优先还原回 [source] 原路径（成功后中转位自然消失，返回 null）；
 * 2) 还原失败：改名为 `.dsh-unmoved-<ts>` —— 不匹配 `\.dsh-moving-\d+$` 清理规则，
 *    finally 与启动扫描都不会删除，数据留待用户手动找回，返回保全路径；
 * 3) 连改名都失败（如所在目录不可写）：**复制**保全到 `.dsh-unmoved-<ts>` 名下
 *    （二轮审查修正：绝不以 `.dsh-moving-*` 形态留底——那会被下次启动扫描按残留删除），
 *    temp 本体交回 finally / 启动扫描清理，数据已另存不丢；
 * 4) 复制也失败（极端环境）：从 [temps] 移除，仅保证本轮不删，原地保留并返回之。
 */
private fun preserveOrRestore(temp: File, source: File, temps: MutableList<File>): File? {
    if (runCatching { temp.renameTo(source) }.getOrDefault(false) && source.exists()) {
        return null
    }
    val preserved = File(
        temp.parentFile,
        "${source.name}.dsh-unmoved-${System.nanoTime() and Long.MAX_VALUE}",
    )
    return when {
        runCatching { temp.renameTo(preserved) }.getOrDefault(false) -> {
            temps.remove(temp)
            preserved
        }
        runCatching {
            if (temp.isDirectory) {
                temp.copyRecursively(preserved, overwrite = false)
            } else {
                temp.copyTo(preserved, overwrite = false)
            }
        }.isSuccess -> {
            temps.remove(temp)
            preserved
        }
        else -> {
            temps.remove(temp)
            temp
        }
    }
}

/** 把 source 完整复制到 destParent 下的 `.dsh-moving-*` 中转位并登记清理。 */
private suspend fun stageCopy(
    source: File,
    destParent: File?,
    temps: MutableList<File>,
    progress: MoveProgress?,
): File {
    val temp = newMovingTemp(destParent)
    temps += temp
    copyTreeWithMeta(source, temp, progress)
    return temp
}

/**
 * 递归复制 source → dest（dest 必须尚未占用）：逐文件复制后同步 rwx 权限位与
 * lastModified（rootfs 内可执行位至关重要），并校验目标字节数与源一致。
 * 取消检查仅在文件之间（§5.3.5：单个文件为最小不可中断单元）。
 */
private suspend fun copyTreeWithMeta(source: File, dest: File, progress: MoveProgress?) {
    currentCoroutineContext().ensureActive()
    // 符号链接：原样重建链接、不跟入解引用（复查修正）。rootfs 内大量链接指向 guest
    // 绝对路径（如 /usr/...），解引用复制会破坏结构且可能沿指向祖先的链无限递归。
    if (java.nio.file.Files.isSymbolicLink(source.toPath())) {
        dest.parentFile?.mkdirs()
        runCatching {
            java.nio.file.Files.createSymbolicLink(
                dest.toPath(),
                java.nio.file.Files.readSymbolicLink(source.toPath()),
            )
        }.onFailure {
            val detail = it.message?.let { msg -> UiText.raw(msg) } ?: UiText.Res(R.string.error_unknown)
            throw FileOpException(
                "Cannot copy symlink ${source.name} (${it.message ?: "unknown error"})",
                uiText = UiText.Concat(listOf(
                    UiText.Res(R.string.move_err_symlink_copy_failed, listOf(source.name)),
                    UiText.Separator("（"),
                    detail,
                    UiText.Separator("）"),
                )),
            )
        }
        return
    }
    if (source.isDirectory) {
        if (!dest.exists() && !dest.mkdirs()) {
            throw FileOpException("Cannot create directory ${dest.name}", uiText = UiText.Res(R.string.move_err_mkdir_failed, listOf(dest.name)))
        }
        val children = source.listFiles()
        if (children != null) {
            for (child in children) {
                copyTreeWithMeta(child, File(dest, child.name), progress)
            }
        }
        // 目录元数据在子项全部复制后同步，避免创建子项时 mtime 被覆盖
        syncMetadata(source, dest)
    } else {
        dest.parentFile?.mkdirs()
        val expected = runCatching { source.length() }.getOrDefault(0L)
        var copied = 0L
        val stage: UiText = UiText.Res(R.string.move_progress_copying_name, listOf(source.name))
        source.inputStream().use { ins ->
            dest.outputStream().use { out ->
                val buffer = ByteArray(COPY_BUFFER)
                while (true) {
                    val read = ins.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    if (copied % (COPY_BUFFER * 32) == 0L) progress?.copyInFlight(copied, stage)
                }
                out.flush()
            }
        }
        progress?.copyInFlight(copied, stage)
        if (copied != expected) {
            throw FileOpException(
                "Copy verification failed: ${source.name} (expected $expected bytes, actual $copied bytes)",
                uiText = UiText.Res(R.string.move_err_copy_verify_failed, listOf(source.name, expected, copied)),
            )
        }
        syncMetadata(source, dest)
        progress?.finishFile(copied, stage)
    }
}

/** 同步 rwx 权限位与 lastModified；目录额外保证可进入（rootfs 内可执行位至关重要）。 */
private fun syncMetadata(src: File, dst: File) {
    runCatching {
        dst.setReadable(src.canRead())
        dst.setWritable(src.canWrite())
        dst.setExecutable(src.canExecute())
        if (dst.isDirectory && !dst.canExecute()) dst.setExecutable(true)
        dst.setLastModified(src.lastModified())
    }
}

/** 在 [parent] 下生成一个不冲突的 `.dsh-moving-<ts>` 中转名（§5.3.3）。
 *  时间戳按符号位归正（审查修正：nanoTime 可为负，负号会破坏 `\.dsh-moving-\d+$` 残留识别）。 */
private fun newMovingTemp(parent: File?): File {
    val dir = parent ?: File(".")
    var candidate: File
    do {
        candidate = File(dir, MOVING_TEMP_PREFIX + (System.nanoTime() and Long.MAX_VALUE))
    } while (candidate.exists())
    return candidate
}
}
