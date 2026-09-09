package com.dshbox.app.util

import com.dshbox.app.R
import com.dshbox.app.common.UiText
import java.io.File

/**
 * 移动计划器（1.2.0 §5.2，纯 JVM，无 Android 依赖）。
 *
 * 职责：在真正执行 IO 前完成全部校验与预演——防环 → 无操作 → 冲突预演 → 跨层标注。
 * 产出 [MovePlan]：[MovePlan.items] 是可直接交给 [FileOps.moveWithin] 的执行任务；
 * [MovePlan.issues] 非空表示存在不能盲目执行的因素（UI 必须先处理：中止 / 提示 / 冲突决策）。
 *
 * 冲突决策模型：[MoveRequest.conflictPreference] 为全局策略（null = 逐项确认）；
 * [MoveRequest.conflictDecisions] 为逐源决策（UI 的「应用到其余全部」会一次性填入全部冲突源）。
 * 两者都未覆盖的冲突源 → 冲突原样作为 issue 报告，等下一轮决策。
 */
data class MoveRequest(
    val sources: List<File>,
    val targetDir: File,
    val conflictPreference: ConflictMode? = null,
    val conflictDecisions: Map<String, ConflictMode> = emptyMap(),
    val roots: LayerRoots,
    val sandboxRunning: Boolean = false,
)

/** 问题类型（§5.2 校验序 + §5.4 系统目录禁令）。 */
enum class IssueKind {
    /** 防环：目标与某源目录同路径或位于其内部，整体拒绝。 */
    ILLEGAL_CYCLE,

    /** 无操作：源的当前父目录就是目标目录，前置提示不执行。 */
    NO_OP,

    /** 冲突：目标落点已存在同名项（文件↔文件 / 文件↔目录 / 目录↔文件）。 */
    CONFLICT_FILE,

    /** 冲突：源目录与目标同名目录，OVERWRITE 语义为递归合并。 */
    CONFLICT_DIR_MERGE,

    /** 系统绑定目录（proc/sys/dev/system/apex/tmp/.dshbox）：§5.4 禁止作为源或目标。 */
    SYSTEM_DIR_FORBIDDEN,
}

data class MoveIssue(
    val source: File,
    val kind: IssueKind,
    val message: UiText,
    /** CONFLICT_DIR_MERGE 时的子树合并冲突条目预演数；其余为 0。 */
    val conflictCount: Int = 0,
)

/** 单个源与其目标落点的跨层风险标注（§5.2.4），UI 据此弹强确认。 */
data class LayerRiskReport(
    val source: File,
    val target: File,
    val sourceLayer: Layer,
    val targetLayer: Layer,
    val sandboxRunning: Boolean,
)

/** 目标落点已存在时的处置策略（执行器语义，§5.3.3/§5.3.4）。 */
enum class MoveExisting {
    /** 目标不应存在；若执行时仍存在则按失败处理（防并发误覆盖）。 */
    FAIL,

    /** 覆盖：先就位后替换（源移到 .dsh-moving-* 临时名，删旧目标再就位）。 */
    OVERWRITE,

    /** 目录递归合并：绝不整体删除目标目录。 */
    MERGE_DIR,
}

/** 单个移动执行任务：source → dest。由 [MovePlanner.planMove] 产出。 */
data class MoveTask(
    val source: File,
    val dest: File,
    val existing: MoveExisting = MoveExisting.FAIL,
)

data class MovePlan(
    /** 非空则不进入执行（UI 先处理：中止 / 提示 / 冲突决策）。 */
    val issues: List<MoveIssue>,
    /** 跨层风险报告（含沙盒运行中标记），UI 据此弹窗。 */
    val layerRisks: List<LayerRiskReport>,
    /** 可执行任务（冲突已按决策解析；SKIP 项不在其中）。 */
    val items: List<MoveTask>,
    /** 因 SKIP 决策被跳过的源。 */
    val skippedSources: List<File>,
)

object MovePlanner {

    /** 子树合并冲突预演的条目数上限（防超大目录拖慢计划阶段）。 */
    private const val MERGE_PREVIEW_CAP = 500

    fun planMove(request: MoveRequest): MovePlan {
        val issues = mutableListOf<MoveIssue>()
        val risks = mutableListOf<LayerRiskReport>()
        val items = mutableListOf<MoveTask>()
        val skipped = mutableListOf<File>()

        // 层根同样 canonical 化，保证与源/目标的前缀比较在同一形态下进行
        // （Windows 短路径等环境下 canonical 与 absolute 可能不同形）。
        val roots = LayerRoots(
            sandboxRoot = request.roots.sandboxRoot.canonicalFile,
            workspaceRoot = request.roots.workspaceRoot.canonicalFile,
            nodeLayer = request.roots.nodeLayer?.canonicalFile,
            dshLayer = request.roots.dshLayer?.canonicalFile,
        )
        val targetCanon = request.targetDir.canonicalFile
        val targetLayer = layerOf(targetCanon.absolutePath, roots)

        // §5.4：系统绑定目录禁止作为目标——整体拒绝
        if (targetLayer == Layer.SYSTEM_DIR) {
            issues += MoveIssue(
                source = request.targetDir,
                kind = IssueKind.SYSTEM_DIR_FORBIDDEN,
                message = UiText.Res(R.string.move_plan_target_system_dir, listOf(request.targetDir.name)),
            )
            return MovePlan(issues, risks, items, skipped)
        }

        // 源去重（同一物理路径只计划一次）
        val seen = HashSet<String>()
        // 批内落点登记（复查修正）：同批两个同名源都选 RENAME 时，后一个自动跳到 a-2，
        // 而不是双方都拿到 a-1 后在执行期撞 FAIL
        val reservedDests = HashSet<String>()

        for (source in request.sources) {
            val srcCanon = source.canonicalFile
            if (!seen.add(srcCanon.absolutePath)) continue
            val srcLayer = layerOf(srcCanon.absolutePath, roots)
            val intended = File(targetCanon, srcCanon.name)
            risks += LayerRiskReport(srcCanon, intended, srcLayer, targetLayer, request.sandboxRunning)

            // 1. 防环（§5.2.1）：目标与源目录同路径，或目标位于源目录内部 → 该项拒绝。
            //    目标同路径对文件源同样无意义（目标即源本身），一并拒绝。
            if (srcCanon == targetCanon || (srcCanon.isDirectory && isWithin(targetCanon, srcCanon))) {
                issues += MoveIssue(
                    source = source,
                    kind = IssueKind.ILLEGAL_CYCLE,
                    message = UiText.Res(R.string.move_plan_cycle, listOf(srcCanon.name)),
                )
                continue
            }

            // §5.4：系统绑定目录禁止作为源
            if (srcLayer == Layer.SYSTEM_DIR) {
                issues += MoveIssue(
                    source = source,
                    kind = IssueKind.SYSTEM_DIR_FORBIDDEN,
                    message = UiText.Res(R.string.move_plan_source_system_dir, listOf(srcCanon.name)),
                )
                continue
            }

            // 2. 无操作（§5.2.2）：源的当前父目录就是目标目录
            val parent = srcCanon.parentFile?.canonicalFile
            if (parent != null && parent == targetCanon) {
                issues += MoveIssue(
                    source = source,
                    kind = IssueKind.NO_OP,
                    message = UiText.Res(R.string.move_plan_no_op, listOf(srcCanon.name)),
                )
                continue
            }

            // 3. 冲突预演（§5.2.3）。决策键按「原始路径 → canonical 路径」双形式查找：
            //    UI 以 issue.source（原始形态）为键，Windows 等环境两者可能不同形。
            if (intended.exists()) {
                val bothDirs = srcCanon.isDirectory && intended.isDirectory
                val kind = if (bothDirs) IssueKind.CONFLICT_DIR_MERGE else IssueKind.CONFLICT_FILE
                val decision = request.conflictDecisions[source.absolutePath]
                    ?: request.conflictDecisions[srcCanon.absolutePath]
                    ?: request.conflictPreference
                when (decision) {
                    ConflictMode.SKIP -> skipped += srcCanon
                    ConflictMode.OVERWRITE -> {
                        reservedDests += intended.absolutePath
                        items += MoveTask(
                            source = srcCanon,
                            dest = intended,
                            existing = if (bothDirs) MoveExisting.MERGE_DIR else MoveExisting.OVERWRITE,
                        )
                    }
                    ConflictMode.RENAME -> {
                        // 批内去重：除目标已存在外，同批已分配的落点也占用计数命名
                        val dot = srcCanon.name.lastIndexOf('.')
                        val stem = if (dot > 0) srcCanon.name.substring(0, dot) else srcCanon.name
                        val ext = if (dot > 0) srcCanon.name.substring(dot) else ""
                        var candidate = srcCanon.name
                        var counter = 1
                        while (File(targetCanon, candidate).exists() ||
                            !reservedDests.add(File(targetCanon, candidate).absolutePath)
                        ) {
                            candidate = "$stem-$counter$ext"
                            counter++
                        }
                        items += MoveTask(srcCanon, File(targetCanon, candidate))
                    }
                    null -> issues += MoveIssue(
                        source = source,
                        kind = kind,
                        message = if (bothDirs) {
                            UiText.Res(R.string.move_plan_conflict_dir_merge, listOf(srcCanon.name))
                        } else {
                            UiText.Res(R.string.move_plan_conflict_name, listOf(srcCanon.name))
                        },
                        conflictCount = if (bothDirs) countMergeConflicts(srcCanon, intended) else 0,
                    )
                }
            } else {
                reservedDests += intended.absolutePath
                items += MoveTask(srcCanon, intended)
            }
        }

        return MovePlan(issues, risks, items, skipped)
    }

    /** [path] 是否位于 [ancestor] 目录内部（canonical 前缀判断，§5.2.1）。 */
    fun isWithin(path: File, ancestor: File): Boolean {
        val p = path.absolutePath.trimEnd(File.separatorChar)
        val a = ancestor.absolutePath.trimEnd(File.separatorChar)
        if (p == a) return false
        return p.startsWith("$a${File.separator}") || p.startsWith("$a/")
    }

    /**
     * §5.6：移动完成后的刷新集合 = **所有源父目录** ∪ 目标目录（去重，统一 canonical 形态）。
     * 复查修正：此前只纳入目标目录与 SKIP 项父目录，漏掉已移动项的源父目录——
     * 在 A 目录把文件移到 B 后停在 A 时目录未变、列表不重扫，原目录残留幽灵条目。
     * 纯路径计算（canonicalFile 仅做路径解析，无任何变更操作）。
     */
    fun computeMoveRefreshDirs(items: List<MoveTask>, skippedSources: List<File>): Set<String> = buildSet {
        fun addDir(file: File?) {
            if (file == null) return
            val path = runCatching { file.canonicalFile.absolutePath }.getOrDefault(file.absolutePath)
            add(path)
        }
        items.forEach {
            addDir(it.source.parentFile) // 源父目录（移出后需刷新）
            addDir(it.dest.parentFile)   // 目标目录（移入后需刷新）
        }
        skippedSources.forEach { addDir(it.parentFile) }
    }

    /** 预演两个目录合并时会冲突的条目数（递归，带上限）。 */
    private fun countMergeConflicts(src: File, dst: File): Int {
        var n = 0
        val children = src.listFiles() ?: return 0
        for (child in children) {
            if (n >= MERGE_PREVIEW_CAP) break
            val target = File(dst, child.name)
            if (target.exists()) {
                n++
                if (child.isDirectory && target.isDirectory && n < MERGE_PREVIEW_CAP) {
                    n += countMergeConflicts(child, target)
                }
            }
        }
        return n
    }

    /**
     * §5.4 跨层语义矩阵的阶段二判定（纯函数，供 UI 与单测共用）：
     * 本次移动是否需要「移动风险确认」。
     *
     * 免确认组合：
     * - 全部源在 workspace 且目标在 workspace —— 矩阵唯一 ✅ 格（`.dsh` 段自动排除：
     *   DSH_DATA ≠ WORKSPACE）；
     * - **全部源**都属阶段一已确认的涉险层（NODE/DSH/DSH_DATA）、源侧预检已确认、
     *   且目标在 workspace/base —— 阶段一文案已覆盖「移出运行环境层」语义，不重复弹窗。
     *   （复查第八轮修正：去重条件从「批内有涉险源」收紧为「**全部**源已涉险」——
     *   rootfs 挂载镜像与普通兄弟同目录共存（如 /usr 下 local(NODE)+bin(BASE)），
     *   同目录多选即可构造混合层批，混合批中 BASE 项必须有矩阵确认。）
     *
     * 其余组合（目标入 node/dsh/DSH_DATA、base 源跨层、混合层批等）→ 需强确认。
     */
    fun needsCrossLayerConfirm(layerRisks: List<LayerRiskReport>, sourcePrecheckFired: Boolean): Boolean {
        if (layerRisks.isEmpty()) return false
        val targetLayer = layerRisks.first().targetLayer
        val allSourcesWorkspace = layerRisks.all { it.sourceLayer == Layer.WORKSPACE }
        if (allSourcesWorkspace && targetLayer == Layer.WORKSPACE) return false
        val allSourcesRisky = layerRisks.all {
            it.sourceLayer == Layer.NODE || it.sourceLayer == Layer.DSH || it.sourceLayer == Layer.DSH_DATA
        }
        if (sourcePrecheckFired && allSourcesRisky && (targetLayer == Layer.WORKSPACE || targetLayer == Layer.BASE)) {
            return false
        }
        return true
    }
}
