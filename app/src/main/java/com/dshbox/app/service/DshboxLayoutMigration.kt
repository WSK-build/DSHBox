package com.dshbox.app.service

import java.io.File

/**
 * `.dsh/dshbox/` 资产目录布局的一次性迁移（旧名 / 旧位置 → 新名）。
 *
 * ## 为什么必须"搬"而不是让新路径从零开始
 *
 * 三份层文件承载**用户状态**：
 * - 官方插件开关（`feature-overlay.yml`）
 * - **哪些插件被停用**（`plugin-overlay.yml`）
 * - 绝对安全模式整层（`absolute-overlay.yml`）
 *
 * 其中"被停用"是安全机制：丢了会把此前被隔离的坏插件重新加载 —— 真机上出过"每次都起不来"
 * 的事故。所以整理目录时**必须连内容一起搬**。
 *
 * ## 幂等规则（唯一重要的一条）
 *
 * **新路径已存在 ⇒ 什么都不做。** 反过来（用旧内容覆盖新文件）会在下一次启动把已经迁移好的
 * 新内容顶回旧版本，是为最难查的一类故障。
 *
 * 纯 `File` 操作、无 Android 依赖，可直接在 JVM 单测里跑。
 */
internal object DshboxLayoutMigration {

    /** 需要搬移的文件：旧相对路径 → 新相对路径（均相对 `.dsh/dshbox`）。 */
    val FILE_MOVES: List<Pair<String, String>> = listOf(
        "features/feature-overlay.yml" to
            "dsh-official-plugin/dsh-official-plugin-overlay.yml",
        "plugin-manager/plugin-overlay.yml" to
            "plugin-market/market-and-safe-mode-overlay.yml",
        "plugin-manager/absolute-overlay.yml" to
            "safe-mode/absolute-safe-mode.yml",
        "boot-log-previous.txt" to "safe-mode/boot-log-previous.txt",
    )

    /**
     * 内容搬空后应清掉的旧目录。
     *
     * 用 `delete()`（不递归）：目录里若还有别的东西（用户自己放的）就删不掉，正好保留。
     */
    val LEGACY_DIRS: List<String> = listOf("features", "plugin-manager")

    /**
     * 旧位置的插件暂存目录：**没有状态**（每次启动由 APK 资产重铺），直接递归删。
     *
     * 与"搬文件"分开列，是因为它们不需要搬 —— 搬反而会把过期副本带进新布局。
     */
    val LEGACY_STAGE_DIRS: List<String> = listOf("mobile-adapt", "mobile-pilot")

    /** 本次迁移做了什么，供日志与单测断言。 */
    data class Report(
        /** 实际搬移的文件（新相对路径）。 */
        val moved: List<String> = emptyList(),
        /** 因新路径已存在且内容相同而丢掉的陈旧旧文件（新相对路径）。 */
        val discarded: List<String> = emptyList(),
        /** 新路径已存在但内容**不同**：保留旧文件待人工判断（新相对路径）。 */
        val keptDivergent: List<String> = emptyList(),
        /** 因新路径已存在、且不属于上面两种情形而跳过的文件（新相对路径）。 */
        val skipped: List<String> = emptyList(),
        /** 被删掉的旧目录。 */
        val removedDirs: List<String> = emptyList(),
        /** 搬移失败的文件（下次启动会重试）。 */
        val failed: List<String> = emptyList(),
    ) {
        val changed: Boolean get() = moved.isNotEmpty() ||
            discarded.isNotEmpty() ||
            removedDirs.isNotEmpty() ||
            failed.isNotEmpty()
    }

    /**
     * 执行迁移。永不抛：单个文件失败只记录并继续，其余项照常处理。
     *
     * @param root 资产目录（宿主机上的 `.dsh/dshbox`）
     */
    fun run(root: File): Report {
        val moved = mutableListOf<String>()
        val discarded = mutableListOf<String>()
        val keptDivergent = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for ((from, to) in FILE_MOVES) {
            val src = File(root, from)
            val dst = File(root, to)
            if (!src.isFile) continue
            if (dst.exists()) {
                // 新路径已有内容：**绝不覆盖**。
                // 若两边内容一致，旧的那份只是陈旧副本，删掉它旧目录才可能被清空；
                // 若不一致，说明有人在中间改过，保留旧文件并报告，交人工判断。
                val same = runCatching {
                    dst.isFile && src.readBytes().contentEquals(dst.readBytes())
                }.getOrDefault(false)
                if (same) {
                    if (runCatching { src.delete() }.getOrDefault(false)) discarded += to else keptDivergent += to
                } else {
                    keptDivergent += to
                }
                skipped += to
                continue
            }
            val ok = runCatching {
                dst.parentFile?.mkdirs()
                // renameTo 在同一文件系统内是原子搬移；失败（跨设备等）退回拷贝 + 删源。
                src.renameTo(dst) || run {
                    src.copyTo(dst, overwrite = false)
                    src.delete()
                }
            }.getOrDefault(false)
            if (ok) moved += to else failed += to
        }

        val removedDirs = mutableListOf<String>()
        for (name in LEGACY_DIRS) {
            val dir = File(root, name)
            if (!dir.isDirectory) continue
            if (runCatching { dir.delete() }.getOrDefault(false)) removedDirs += name
        }
        for (name in LEGACY_STAGE_DIRS) {
            val dir = File(root, name)
            if (!dir.isDirectory) continue
            if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) removedDirs += name
        }

        return Report(moved, discarded, keptDivergent, skipped, removedDirs, failed)
    }
}
