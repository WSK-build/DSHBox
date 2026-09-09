package com.dshbox.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import com.dshbox.app.ui.asString
import com.dshbox.app.util.ConflictMode
import com.dshbox.app.util.IssueKind
import com.dshbox.app.util.MoveFailure
import com.dshbox.app.util.MoveResult

/**
 * 移动流程三个对话框的纯展示组件（1.2.0 §7.3 自 FilesScreen 抽出）：
 * 冲突决策（§5.1）/ §5.4 跨层强确认 / 移动结果（§5.3.7、§5.6）。
 * 组件不持有任何状态，全部经参数与回调与 [MoveFlow] 交互。
 */

/** 移动冲突对话框：覆盖 / 跳过 / 自动改名 +「应用到其余全部」勾选（§5.1、§5.5）。 */
@Composable
internal fun MoveConflictDialog(
    pending: PendingMove,
    applyToAll: Boolean,
    onApplyToAllChange: (Boolean) -> Unit,
    onDecide: (ConflictMode) -> Unit,
    onCancel: () -> Unit,
) {
    val conflict = pending.conflicts.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.files_conflict_title)) },
        text = {
            Column {
                Text(
                    text = if (conflict.kind == IssueKind.CONFLICT_DIR_MERGE) {
                        stringResource(
                            R.string.files_conflict_move_dir_merge,
                            conflict.source.name,
                            conflict.conflictCount,
                        )
                    } else {
                        stringResource(R.string.files_conflict_move_file, conflict.source.name)
                    },
                    fontSize = 14.sp,
                    color = TextSecondary(),
                )
                if (pending.conflicts.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pluralStringResource(R.plurals.files_conflict_remaining, pending.conflicts.size - 1, pending.conflicts.size - 1),
                        fontSize = 12.sp,
                        color = TextHint(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // 「应用到其余全部」勾选（§5.5）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onApplyToAllChange(!applyToAll) },
                ) {
                    Checkbox(
                        checked = applyToAll,
                        onCheckedChange = { onApplyToAllChange(it) },
                    )
                    Text(
                        text = stringResource(R.string.files_conflict_apply_all),
                        fontSize = 13.sp,
                        color = TextSecondary(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDecide(ConflictMode.OVERWRITE) },
            ) {
                Text(stringResource(R.string.files_conflict_overwrite), color = PrimaryGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = { onDecide(ConflictMode.SKIP) }) {
                Text(stringResource(R.string.files_conflict_skip))
            }
            TextButton(onClick = { onDecide(ConflictMode.RENAME) }) {
                Text(stringResource(R.string.files_conflict_rename))
            }
        },
    )
}

/** §5.4 阶段二：跨层移动强确认（消费 layerRisks）。 */
@Composable
internal fun MoveMatrixConfirmDialog(
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_move_confirm_title)) },
        text = {
            Text(
                text = message,
                fontSize = 14.sp,
                color = TextSecondary(),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.files_risk_continue), color = PrimaryGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_cancel))
            }
        },
    )
}

/** 移动结果对话框（§5.3.7 / §5.6）：成功/跳过/失败计数 + 失败明细 + 跨视图提示。 */
@Composable
internal fun MoveResultDialog(
    result: MoveResult?,
    skippedByDecision: Int,
    crossViewHint: String?,
    onDismiss: () -> Unit,
) {
    val skippedTotal = (result?.skipped ?: 0) + skippedByDecision
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_move_result_title)) },
        text = {
            Column {
                Text(
                    text = if (result?.cancelled == true) {
                        stringResource(
                            R.string.files_move_result_cancelled,
                            result.moved,
                            skippedTotal,
                            result.failed.size,
                        )
                    } else {
                        stringResource(
                            R.string.files_move_result_summary,
                            result?.moved ?: 0,
                            skippedTotal,
                            result?.failed?.size ?: 0,
                        )
                    },
                    fontSize = 14.sp,
                    color = TextPrimary(),
                )
                crossViewHint?.let { hint ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = hint,
                        fontSize = 12.sp,
                        color = PrimaryGreen,
                    )
                }
                if (!result?.failed.isNullOrEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.files_move_result_failures),
                        fontSize = 12.sp,
                        color = TextSecondary(),
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        result.failed.forEach { failure: MoveFailure ->
                            Text(
                                text = stringResource(
                                    R.string.files_move_failed_item,
                                    failure.source.name,
                                    failure.message.asString(),
                                ),
                                fontSize = 11.sp,
                                color = DangerRed,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_close))
            }
        },
    )
}
