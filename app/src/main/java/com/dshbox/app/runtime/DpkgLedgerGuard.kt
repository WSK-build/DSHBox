package com.dshbox.app.runtime

import com.dshbox.app.util.PackageToolProbe
import java.io.File

/**
 * dpkg 账本的快照与自动回滚。
 *
 * guest 内的包事务可能被中断（App 被系统回收、用户在装包途中停止沙箱、维护脚本失败），
 * 而本环境下 dpkg 无法自愈：它写 `status-old` 备份依赖 `link(2)`，Android 应用数据文件系统
 * 不支持硬链接，于是连被提示的 `dpkg --configure -a` 也会失败，用户会永久卡在
 * 「dpkg was interrupted」上。
 *
 * 这里在宿主侧留一份账本快照：账本干净时（重新）快照，发现账本未闭合时用快照恢复，
 * 让下一次会话回到「上一次一致状态」。快照放宿主侧、**不放层内**（换层会把层内文件一并换掉）。
 * 全程静默，只由调用方记一条日志。
 */
object DpkgLedgerGuard {

    /** guest 内 dpkg 账本目录（相对 base 层根）。 */
    private const val DPKG_REL = "var/lib/dpkg"

    /** dpkg 在「事务进行中」时写入的日志文件（正常状态下仅有这一个空占位）。 */
    private const val JOURNAL_PLACEHOLDER = "tmp.i"

    /** 账本里表示「未闭合」的状态值。 */
    private val UNFINISHED_STATES = listOf(
        "Status: half-installed",
        "Status: half-configured",
        "triggers-pending",
        "triggers-awaited",
    )

    enum class Outcome {
        /** base 层不存在（未安装运行环境）——什么都没做。 */
        UNAVAILABLE,

        /** 有包管理工具正在运行——本次跳过，避免与事务抢账本。 */
        SKIPPED,

        /** 账本干净，已（重新）留下快照。 */
        SNAPSHOTTED,

        /** 账本干净且与现有快照一致——无需复制。 */
        UP_TO_DATE,

        /** 账本未闭合，已用快照恢复。 */
        RESTORED,
    }

    /**
     * 校验账本：[snapshotDir] 为空或账本干净 → 重新快照；账本未闭合 → 用快照恢复。
     *
     * @param isBusy 是否正有包管理工具在跑（可注入，便于单测）。
     */
    fun guard(
        baseRoot: File,
        snapshotDir: File,
        isBusy: () -> Boolean = { PackageToolProbe.isRunning() },
    ): Outcome {
        val dpkgDir = File(baseRoot, DPKG_REL)
        if (!dpkgDir.isDirectory) return Outcome.UNAVAILABLE
        if (isBusy()) return Outcome.SKIPPED

        val dirty = isLedgerDirty(dpkgDir)
        if (!dirty) {
            // 每次 App 启动都复制一份 5.5 MB 的账本没有意义：内容未变就跳过。
            if (upToDate(snapshotDir, dpkgDir)) return Outcome.UP_TO_DATE
            return if (snapshot(dpkgDir, snapshotDir)) Outcome.SNAPSHOTTED else Outcome.UNAVAILABLE
        }
        // 未闭合：只有在确有快照时才恢复；没有快照就维持现状（留给用户或后续版本处理）。
        if (!File(snapshotDir, "status").isFile) return Outcome.UNAVAILABLE
        return if (restore(snapshotDir, dpkgDir)) Outcome.RESTORED else Outcome.UNAVAILABLE
    }

    /** 现有快照是否与当前账本一致（比 status 内容与 journal 文件集合）。 */
    private fun upToDate(snap: File, live: File): Boolean = runCatching {
        val snapStatus = File(snap, "status")
        val liveStatus = File(live, "status")
        if (!snapStatus.isFile || !liveStatus.isFile) return false
        if (!snapStatus.readBytes().contentEquals(liveStatus.readBytes())) return false
        journalNames(snap) == journalNames(live)
    }.getOrDefault(false)

    private fun journalNames(dpkgDir: File): Set<String> =
        File(dpkgDir, "updates").listFiles()?.map { it.name }?.toSet().orEmpty()

    /**
     * 账本是否「未闭合」：`status` 缺失或含半装/半配/待触发状态，或 `updates/` 里除占位文件外
     * 还有 dpkg 的事务日志（后者正是 apt 拒绝继续并提示 `dpkg --configure -a` 的判据）。
     */
    internal fun isLedgerDirty(dpkgDir: File): Boolean {
        val status = File(dpkgDir, "status")
        if (!status.isFile) return true
        val text = runCatching { status.readText() }.getOrNull() ?: return true
        if (UNFINISHED_STATES.any { text.contains(it) }) return true

        val journal = File(dpkgDir, "updates").listFiles() ?: return false
        return journal.any { it.name != JOURNAL_PLACEHOLDER }
    }

    /** 把 [from] 整个目录复制到 [to]（先清空 [to]）。 */
    private fun snapshot(from: File, to: File): Boolean = runCatching {
        to.deleteRecursively()
        if (!to.mkdirs()) return false
        copyTree(from, to)
        true
    }.getOrDefault(false)

    /**
     * 用快照 [snap] 整目录替换 [live]。
     *
     * 必须是替换而不是逐文件覆盖：账本是一致性单元，覆盖式恢复会留下快照里没有的残留
     * （典型是中断事务留下的 journal 文件），恢复后依然被判为「未闭合」。
     * 若替换过程中失败，账本会缺少 status 而被判为未闭合，而快照仍在——下次启动会再次尝试恢复。
     */
    private fun restore(snap: File, live: File): Boolean = runCatching {
        if (!live.deleteRecursively()) return false
        if (!live.mkdirs()) return false
        copyTree(snap, live)
        true
    }.getOrDefault(false)

    private fun copyTree(from: File, to: File) {
        val children = from.listFiles() ?: return
        for (child in children) {
            val target = File(to, child.name)
            if (child.isDirectory) {
                target.mkdirs()
                copyTree(child, target)
            } else if (child.isFile) {
                child.copyTo(target, overwrite = true)
            }
        }
    }

}
