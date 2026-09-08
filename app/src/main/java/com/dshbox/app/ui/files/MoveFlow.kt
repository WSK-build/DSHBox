package com.dshbox.app.ui.files

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.dshbox.app.R
import com.dshbox.app.util.ConflictMode
import com.dshbox.app.util.FileEntry
import com.dshbox.app.util.MoveEngine
import com.dshbox.app.util.IssueKind
import com.dshbox.app.util.Layer
import com.dshbox.app.util.LayerRoots
import com.dshbox.app.util.MoveIssue
import com.dshbox.app.util.MovePlan
import com.dshbox.app.util.MovePlanner
import com.dshbox.app.util.MoveRequest
import com.dshbox.app.util.MoveResult
import com.dshbox.app.util.PathMapper
import com.dshbox.app.util.ProgressListener
import com.dshbox.app.util.layerOf
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 进度对话框状态（§7.3 自 FilesScreen 迁出为 internal，供移动编排与文件页操作共用）。 */
internal data class ProgressUi(
    val active: Boolean = false,
    val stage: String = "",
    val done: Long = 0L,
    val total: Long = -1L,
)

/** 移动冲突决策的进行中状态（1.2.0 §5.1：逐项决策 +「应用到其余全部」）。 */
internal data class PendingMove(
    val sources: List<File>,
    val targetDir: File,
    /** 用户选定的逻辑目标目录（跨层确认文案展示用）。 */
    val targetLogical: File,
    /** 尚未决策的冲突项。 */
    val conflicts: List<MoveIssue>,
    /** 已决策：key = source absolutePath。 */
    val decisions: Map<String, ConflictMode>,
)

/** §5.4 阶段二跨层强确认（目标落点已知、计划生成后弹出）。 */
internal data class MatrixConfirm(val message: String, val plan: MovePlan)

/**
 * 移动流程编排状态（1.2.0 §7.3：自 FilesScreen 收敛为单一数据类，行为零变化）。
 * 展示层经 [MoveFlow.state] 读取；对话框渲染见 MoveDialogs.kt。
 */
internal data class MoveFlowState(
    val showFolderPicker: Boolean = false,
    val moveSources: List<File> = emptyList(),
    /** 多选移动的涉险数量（弹窗知情提示）。 */
    val moveRiskCount: Int = 0,
    val pendingMove: PendingMove? = null,
    /** 冲突对话框「应用到其余全部」勾选状态。 */
    val applyConflictsToAll: Boolean = false,
    val showMoveConflictDialog: Boolean = false,
    /** §5.4 阶段二：跨层强确认（消费 plan.layerRisks）。 */
    val pendingMatrixConfirm: MatrixConfirm? = null,
    val showMoveResultDialog: Boolean = false,
    val lastMoveResult: MoveResult? = null,
    val lastMoveSkippedByDecision: Int = 0,
    /** 结果对话框中的跨视图提示文案（§5.6：目标在另一视图时非空）。 */
    val moveCrossViewHint: String? = null,
)

/**
 * 「移动到指定文件夹」全流程编排（1.2.0 §7.3 自 FilesScreen 迁出，行为零变化）：
 * startMove → FolderPickerScreen → planMove → 冲突决策 → §5.4 矩阵确认 → executeMove → 结果。
 *
 * 与 FilesScreen 的边界：磁盘 IO 之外的全部页面状态（选择态/搜索态/进度对话框/风险弹窗）
 * 仍归 FilesScreen 所有，经构造回调委托；本类只持有移动流程自身的状态 [state]。
 */
internal class MoveFlow(
    private val context: Context,
    private val scope: CoroutineScope,
    private val mapper: PathMapper,
    private val layerRoots: LayerRoots,
    /** 沙盒运行中标记（NODE/DSH 层移动文案追加停机建议，§4.2/§5.4）。 */
    private val sandboxRunning: () -> Boolean,
    /** 当前逻辑目录提供者（§5.6 刷新集合比较、跨视图提示）。 */
    private val currentDir: () -> File,
    // ---- FilesScreen 页面状态回调 ----
    private val exitSelection: () -> Unit,
    private val clearSearch: () -> Unit,
    private val refreshEntries: () -> Unit,
    private val showError: (String) -> Unit,
    private val showToastRes: (Int) -> Unit,
    private val showRisk: (FileEntry, String) -> Unit,
    // ---- 进度对话框（FilesScreen 持有，导入/导出/移动共享）----
    private val cancelProgressJob: () -> Unit,
    private val registerProgressJob: (Job) -> Unit,
    private val currentProgressJob: () -> Job?,
    private val showProgressUi: (ProgressUi) -> Unit,
    private val clearProgress: () -> Unit,
) {
    var state by mutableStateOf(MoveFlowState())
        private set

    private fun update(transform: (MoveFlowState) -> MoveFlowState) {
        state = transform(state)
    }

    /** 逻辑路径 → 物理层判定（与 FilesScreen.pathLayer 同一套 mapper/layerRoots）。 */
    private fun entryLayer(entry: FileEntry): Layer =
        layerOf(mapper.resolvePhysical(File(entry.logicalPath)).absolutePath, layerRoots)

    // ---------------- 入口（§5.1） ----------------

    /** 「移动到…」入口：源侧风险预检（§5.1）——系统目录直接拒绝（§5.4），其余风险强确认后进选择器。 */
    fun startMove(targets: List<FileEntry>) {
        if (targets.isEmpty()) return
        val sources = targets.map { mapper.resolvePhysical(File(it.logicalPath)) }
        update { it.copy(moveSources = sources) }
        if (sources.any { layerOf(it.absolutePath, layerRoots) == Layer.SYSTEM_DIR }) {
            showError(context.getString(R.string.files_move_system_forbidden))
            return
        }
        // 涉险判定与弹窗文案同源（entryLayer 物理层判定而非 entry.risk 名称口径）；
        // 多选时记录涉险数量，弹窗提示「N 项中 M 项涉险」
        val riskyLayers = targets.map { it to entryLayer(it) }
            .filter { riskLevelOfLayer(it.second) != null }
        if (riskyLayers.isNotEmpty()) {
            update { it.copy(moveRiskCount = riskyLayers.size) }
            showRisk(riskyLayers.first().first, "move")
        } else {
            update { it.copy(moveRiskCount = 0, showFolderPicker = true) }
        }
    }

    /** 源侧风险强确认通过后进入目标选择器（FilesScreen.confirmRisk 的 "move" 分支）。 */
    fun onMoveRiskConfirmed() {
        if (state.moveSources.isNotEmpty()) update { it.copy(showFolderPicker = true) }
    }

    fun dismissPicker() {
        update { it.copy(showFolderPicker = false) }
    }

    // ---------------- 计划与决策（§5.2 / §5.4） ----------------

    /** 选择器确认：目标侧预检（§5.4 系统目录禁令）→ 生成计划（§5.2）。 */
    fun onMoveTargetPicked(targetLogical: File) {
        update { it.copy(showFolderPicker = false) }
        val resolved = mapper.resolvePhysical(targetLogical)
        val targetDir = runCatching { resolved.canonicalFile }.getOrDefault(resolved)
        if (layerOf(targetDir.absolutePath, layerRoots) == Layer.SYSTEM_DIR) {
            showError(context.getString(R.string.files_move_system_forbidden))
            return
        }
        planMove(state.moveSources, targetDir, targetLogical, emptyMap())
    }

    /**
     * 生成移动计划并分流（§5.2 校验序）：防环/系统目录 → 错误中止；
     * 无操作 → 提示；冲突 → 冲突对话框逐项决策；无阻塞 → §5.4 阶段二跨层确认 → 执行。
     */
    private fun planMove(sources: List<File>, targetDir: File, targetLogical: File, decisions: Map<String, ConflictMode>) {
        val plan = MovePlanner.planMove(
            MoveRequest(
                sources = sources,
                targetDir = targetDir,
                conflictPreference = null,
                conflictDecisions = decisions,
                roots = layerRoots,
                sandboxRunning = sandboxRunning(),
            ),
        )
        val blocking = plan.issues.firstOrNull {
            it.kind == IssueKind.ILLEGAL_CYCLE || it.kind == IssueKind.SYSTEM_DIR_FORBIDDEN
        }
        if (blocking != null) {
            showError(blocking.message)
            return
        }
        val noOps = plan.issues.filter { it.kind == IssueKind.NO_OP }
        val conflicts = plan.issues.filter { it.kind == IssueKind.CONFLICT_FILE || it.kind == IssueKind.CONFLICT_DIR_MERGE }
        if (conflicts.isNotEmpty()) {
            update {
                it.copy(
                    pendingMove = PendingMove(sources, targetDir, targetLogical, conflicts, decisions),
                    applyConflictsToAll = conflicts.size == 1,
                    showMoveConflictDialog = true,
                )
            }
            return
        }
        if (noOps.isNotEmpty()) showToastRes(R.string.files_move_no_op)
        if (plan.items.isEmpty()) return
        // §5.4 阶段二：跨层语义矩阵确认（消费 plan.layerRisks，目标落点已知）
        maybeMatrixConfirm(targetLogical, plan)
    }

    /** 冲突决策（§5.1）：mode 应用到当前冲突；勾选「应用到其余全部」时应用到全部剩余冲突。 */
    fun resolveMoveConflict(mode: ConflictMode) {
        val snapshot = state
        val pending = snapshot.pendingMove ?: return
        val current = pending.conflicts.first()
        val decided = if (snapshot.applyConflictsToAll) {
            pending.decisions + pending.conflicts.associate { it.source.absolutePath to mode }
        } else {
            pending.decisions + (current.source.absolutePath to mode)
        }
        val remaining = pending.conflicts.drop(if (snapshot.applyConflictsToAll) pending.conflicts.size else 1)
        if (remaining.isNotEmpty()) {
            update {
                it.copy(
                    pendingMove = pending.copy(conflicts = remaining, decisions = decided),
                    applyConflictsToAll = remaining.size == 1,
                )
            }
        } else {
            update { it.copy(pendingMove = null, showMoveConflictDialog = false) }
            planMove(pending.sources, pending.targetDir, pending.targetLogical, decided)
        }
    }

    fun cancelMoveConflicts() {
        update { it.copy(pendingMove = null, showMoveConflictDialog = false) }
    }

    fun setApplyConflictsToAll(value: Boolean) {
        update { it.copy(applyConflictsToAll = value) }
    }

    /**
     * §5.4 跨层语义矩阵的阶段二确认（此前 UI 从不消费 layerRisks，
     * workspace→base/node/dsh、base→任意层、目标入 .dsh 等格全部静默通过——复查第五轮修正）。
     *
     * 免确认组合：
     * - 全部源在 workspace 且目标在 workspace（矩阵唯一 ✅ 格；.dsh 段自动排除）；
     * - 源侧预检已确认且全部源属涉险层且目标在 workspace/base：阶段一文案已覆盖。
     * 其余组合 → 强确认后执行。
     */
    private fun maybeMatrixConfirm(targetLogical: File, plan: MovePlan) {
        val risks = plan.layerRisks
        // §5.4 判定抽为纯函数（MovePlanner.needsCrossLayerConfirm）供单测覆盖
        if (!MovePlanner.needsCrossLayerConfirm(risks, sourcePrecheckFired = state.moveRiskCount > 0)) {
            executeMove(plan)
            return
        }
        val targetLayer = risks.first().targetLayer
        val message = when {
            targetLayer == Layer.NODE || targetLayer == Layer.DSH ->
                context.getString(R.string.files_move_confirm_layer_target, targetLogical.name)
            targetLayer == Layer.DSH_DATA ->
                context.getString(R.string.files_move_confirm_target_dsh, targetLogical.name)
            risks.all { it.sourceLayer == Layer.BASE } ->
                context.getString(
                    R.string.files_move_confirm_base,
                    plan.items.firstOrNull()?.source?.name ?: targetLogical.name,
                )
            else -> context.getString(R.string.files_move_confirm_generic)
        }
        update { it.copy(pendingMatrixConfirm = MatrixConfirm(message, plan)) }
    }

    fun confirmMatrix() {
        val confirm = state.pendingMatrixConfirm ?: return
        update { it.copy(pendingMatrixConfirm = null) }
        executeMove(confirm.plan)
    }

    fun dismissMatrix() {
        update { it.copy(pendingMatrixConfirm = null) }
    }

    // ---------------- 执行与结果（§5.3 / §5.6） ----------------

    /** §5.6：目标视图与当前视图不同时，结果对话框提示「已移动到工作区/沙盒」。 */
    private fun crossViewHintText(dest: File?): String? {
        if (dest == null) return null
        val currentWorkspace = layerOf(mapper.resolvePhysical(currentDir()).absolutePath, layerRoots) == Layer.WORKSPACE
        val destWorkspace = layerOf(dest.absolutePath, layerRoots) == Layer.WORKSPACE
        if (currentWorkspace == destWorkspace) return null
        return context.getString(
            if (destWorkspace) R.string.files_moved_to_workspace else R.string.files_moved_to_sandbox,
        )
    }

    /** §5.6：刷新集合 = 所有源父目录 ∪ 目标目录（去重，由 [MovePlanner.computeMoveRefreshDirs] 计算）；
     *  清除搜索态；单源停在原目录。 */
    private fun afterMoveRefresh(plan: MovePlan) {
        exitSelection()
        clearSearch()
        // 当前目录与刷新集合统一用 canonical 形态比较（复查修正：原实现两者形态不一致）
        val resolved = mapper.resolvePhysical(currentDir())
        val currentPhysical = runCatching { resolved.canonicalFile.absolutePath }
            .getOrDefault(resolved.absolutePath)
        if (MovePlanner.computeMoveRefreshDirs(plan.items, plan.skippedSources).contains(currentPhysical)) {
            refreshEntries()
        }
    }

    /** 执行计划（§5.3 moveWithin）并展示结果对话框（§5.6 刷新）。 */
    private fun executeMove(plan: MovePlan) {
        cancelProgressJob()
        val job = scope.launch {
            val self = currentCoroutineContext()[Job]
            showProgressUi(ProgressUi(active = true, stage = context.getString(R.string.files_progress_move_prepare)))
            try {
                val result = withContext(Dispatchers.IO) {
                    MoveEngine.moveWithin(
                        tasks = plan.items,
                        listener = ProgressListener { done, total, stage ->
                            showProgressUi(ProgressUi(true, stage, done, total))
                        },
                    )
                }
                clearProgress()
                update {
                    it.copy(
                        lastMoveResult = result,
                        lastMoveSkippedByDecision = plan.skippedSources.size,
                        moveCrossViewHint = result.moved.takeIf { moved -> moved > 0 }
                            ?.let { crossViewHintText(plan.items.firstOrNull()?.dest) },
                        showMoveResultDialog = true,
                    )
                }
                afterMoveRefresh(plan)
            } catch (e: CancellationException) {
                clearProgress()
                if (currentProgressJob() == self) showToastRes(R.string.files_progress_cancelled)
                afterMoveRefresh(plan)
            } catch (e: Exception) {
                clearProgress()
                showError(context.getString(R.string.files_move_failed, e.message ?: "未知错误"))
                afterMoveRefresh(plan)
            }
        }
        registerProgressJob(job)
    }

    fun dismissResult() {
        update { it.copy(showMoveResultDialog = false) }
    }
}
