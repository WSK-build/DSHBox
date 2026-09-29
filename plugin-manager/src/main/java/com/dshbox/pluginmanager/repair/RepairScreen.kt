package com.dshbox.pluginmanager.repair

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.ui.PanelTopBar

/**
 * 「插件崩溃修复辅助」页：一键安装 / 更新 / 删除 opencode 终端 Agent。
 *
 * 定位：DSH 插件加载失败会让 DSH 起不来，这里给用户一个终端自助修复入口；工具本体按需下载，
 * 不随 APK 打包。本页只负责呈现与触发，命令构造与状态机在 [OpenCodeToolController]。
 */
@Composable
fun RepairScreen(
    controller: OpenCodeToolController,
    onBack: () -> Unit,
) {
    val state by controller.state.collectAsState()
    var confirmRemove by remember { mutableStateOf(false) }

    // 进页面探一次真实安装态：判据在安装目录，不在常驻的命令包装脚本。
    LaunchedEffect(Unit) { controller.refresh() }

    Column(Modifier.fillMaxSize()) {
        PanelTopBar(title = stringResource(R.string.pm_repair_row), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.pm_repair_card_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (state.installed) {
                            // 版本号取自包清单，读不到只影响这一行，不能因此改口说"未安装"。
                            state.version?.let { stringResource(R.string.pm_repair_state_installed, it) }
                                ?: stringResource(R.string.pm_repair_state_installed_no_version)
                        } else {
                            stringResource(R.string.pm_repair_state_missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Row(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            // 横滚与市场筛选行同款：西里尔/阿拉伯语标签较长，窄屏上宁可滑动，
                            // 也不要把标签挤成省略号。
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActionBox(
                            label = stringResource(R.string.pm_repair_install),
                            enabled = !state.busy && !state.installed,
                            onClick = { controller.installOrUpdate() },
                        )
                        ActionBox(
                            label = stringResource(R.string.pm_repair_update),
                            enabled = !state.busy && state.installed,
                            onClick = { controller.installOrUpdate() },
                        )
                        ActionBox(
                            label = stringResource(R.string.pm_repair_remove),
                            enabled = !state.busy && state.installed,
                            onClick = { confirmRemove = true },
                        )
                    }
                    ProgressPanel(controller = controller)
                }
            }
            val notes = listOf(
                R.string.pm_repair_note_1,
                R.string.pm_repair_note_2,
                R.string.pm_repair_note_3,
                R.string.pm_repair_note_4,
            )
            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                notes.forEach { note ->
                    Text(
                        text = stringResource(note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.pm_repair_remove)) },
            text = { Text(stringResource(R.string.pm_repair_remove_confirm_body)) },
            confirmButton = {
                Button(onClick = {
                    confirmRemove = false
                    controller.remove()
                }) {
                    Text(stringResource(R.string.pm_repair_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) {
                    Text(stringResource(R.string.pm_market_cancel))
                }
            },
        )
    }
}

/**
 * 卡片内的执行面板：进行中显示下载提示与命令输出尾部，结束后显示结果与"重试 / 关闭"。
 *
 * 结果只存内存，因此重启应用后自然消失，不需要额外清理。
 */
@Composable
private fun ProgressPanel(controller: OpenCodeToolController) {
    val state by controller.state.collectAsState()
    if (!state.busy && state.outcome == OpenCodeToolController.Outcome.NONE) return

    Column(Modifier.padding(top = 12.dp)) {
        when {
            state.busy -> {
                Text(
                    text = stringResource(R.string.pm_repair_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (state.attempt > 1) {
                    Text(
                        text = stringResource(R.string.pm_repair_retrying),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.outcome == OpenCodeToolController.Outcome.FAILED -> Text(
                text = stringResource(
                    R.string.pm_repair_failed,
                    state.error.orEmpty().ifBlank { stringResource(R.string.pm_repair_failed_unknown) },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                text = stringResource(R.string.pm_repair_done),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.lines.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = state.lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
        if (!state.busy) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.outcome == OpenCodeToolController.Outcome.FAILED) {
                    // 与上面三个方框同款，免得同屏出现两种按钮外观。
                    ActionBox(
                        label = stringResource(R.string.pm_repair_retry),
                        enabled = true,
                        onClick = {
                            when (state.action) {
                                OpenCodeToolController.Action.REMOVE -> controller.remove()
                                else -> controller.installOrUpdate()
                            }
                        },
                    )
                }
                TextButton(onClick = { controller.dismiss() }) {
                    Text(stringResource(R.string.pm_market_close))
                }
            }
        }
    }
}

/**
 * 页面里的动作方框：版式与「插件市场」筛选区的方框一致 —— 小圆角、32dp 高、1dp 描边。
 *
 * 不用按钮默认外观的两个原因：默认是全圆头，与市场那排筛选方框不是一种东西；
 * 默认描边取 `outline`，浅色主题下几乎看不见（真机反馈）。这里描边用主色。
 *
 * 关掉的方框**保留描边**（换成中性色）而不是让它整只消失：同排三个框始终看得出是三个动作，
 * 而"哪个现在能点"由描边与文字的颜色区分。
 */
@Composable
private fun ActionBox(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(32.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
