package com.dshbox.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.common.DshInstance
import com.dshbox.app.common.Versions
import com.dshbox.app.instance.InstanceOpState
import com.dshbox.app.service.SandboxService
import com.dshbox.app.common.R as CommonR

/**
 * 应急修复与实例切换子页：呈现当前活跃实例与应急实例存在性，提供切换/创建/删除
 * 应急实例与层修复（回滚上一版本/在线重装当前版本/恢复随包基线）。破坏性动作先确认。
 * 视觉沿用全局设置页语言（分节卡片 + 行 + 紧凑描边按钮），内容限宽居中避免平板拉伸。
 */
@Composable
internal fun InstanceManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as DshApp
    val registry = app.container.instanceRegistry
    val sandboxManager = app.container.sandboxManager
    val runtimeUpdateManager = app.container.runtimeUpdateManager

    val active by registry.active.collectAsState()
    val op by InstanceOpState.state.collectAsState()
    val installState by runtimeUpdateManager.installState.collectAsState()
    val emergencyExists = remember(active, op.running, installState.running) { registry.emergencyExists() }
    val previousAvailable = remember(op.running, installState.running) { sandboxManager.dshPreviousLayerAvailable() }
    val installedVersion = remember(op.running, installState.running) { sandboxManager.installedDshVersion() }
    val bundledVersion = remember { SandboxService.bundledDshVersion(context) }
    val baselineMessage = when {
        bundledVersion == null -> stringResource(R.string.instance_baseline_unavailable)
        installedVersion == null -> stringResource(R.string.instance_baseline_confirm_message)
        Versions.compare(bundledVersion, installedVersion) < 0 ->
            stringResource(R.string.instance_baseline_downgrade_message, bundledVersion, installedVersion)
        Versions.compare(bundledVersion, installedVersion) == 0 ->
            stringResource(R.string.instance_baseline_reinstall_message, bundledVersion)
        else -> stringResource(R.string.instance_baseline_upgrade_message, bundledVersion, installedVersion)
    }
    val busy = op.running || installState.running
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmBaseline by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.home_browser_more_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(12.dp)
                        .size(22.dp),
                )
                Text(
                    text = stringResource(R.string.instance_manager_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                WarningBanner(stringResource(R.string.instance_warning))

                Text(
                    text = stringResource(R.string.instance_manager_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                InstanceSection(stringResource(R.string.instance_section_instances)) {
                    InstanceRow(
                        iconRes = CommonR.drawable.ic_devices,
                        title = stringResource(R.string.instance_current_label),
                        description = if (active == DshInstance.EMERGENCY) {
                            stringResource(R.string.instance_emergency)
                        } else {
                            stringResource(R.string.instance_main)
                        } + " · " + stringResource(R.string.instance_emergency_state_label) + ": " +
                            if (emergencyExists) {
                                stringResource(R.string.instance_emergency_present)
                            } else {
                                stringResource(R.string.instance_emergency_absent)
                            },
                    )
                    if (active == DshInstance.EMERGENCY) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InstanceRow(
                            iconRes = CommonR.drawable.ic_arrows_right_left,
                            title = stringResource(R.string.instance_switch_to_main),
                            description = stringResource(R.string.instance_switch_to_main_help),
                            trailing = {
                                CompactButton(
                                    text = stringResource(R.string.instance_switch_action),
                                    enabled = !busy,
                                    onClick = { SandboxService.switchInstance(context, DshInstance.MAIN) },
                                )
                            },
                        )
                    } else if (emergencyExists) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InstanceRow(
                            iconRes = CommonR.drawable.ic_arrows_right_left,
                            title = stringResource(R.string.instance_switch_to_emergency),
                            description = stringResource(R.string.instance_switch_to_emergency_help),
                            trailing = {
                                CompactButton(
                                    text = stringResource(R.string.instance_switch_action),
                                    enabled = !busy,
                                    onClick = { SandboxService.switchInstance(context, DshInstance.EMERGENCY) },
                                )
                            },
                        )
                    }
                    if (!emergencyExists) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InstanceRow(
                            iconRes = CommonR.drawable.ic_alert_triangle,
                            title = stringResource(R.string.instance_create_emergency),
                            description = stringResource(R.string.instance_create_emergency_help),
                            trailing = {
                                CompactButton(
                                    text = stringResource(R.string.instance_create_action),
                                    enabled = !busy,
                                    onClick = { SandboxService.createEmergency(context) },
                                )
                            },
                        )
                    }
                    if (emergencyExists) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        InstanceRow(
                            iconRes = CommonR.drawable.ic_trash,
                            title = stringResource(R.string.instance_delete_emergency),
                            description = stringResource(R.string.instance_delete_emergency_help),
                            trailing = {
                                CompactButton(
                                    text = stringResource(R.string.instance_delete_action),
                                    enabled = !busy,
                                    onClick = { confirmDelete = true },
                                )
                            },
                        )
                    }
                }

                InstanceSection(stringResource(R.string.instance_repair_title)) {
                    Text(
                        text = stringResource(R.string.instance_repair_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InstanceRow(
                        iconRes = CommonR.drawable.ic_refresh,
                        title = stringResource(R.string.instance_repair_rollback),
                        description = stringResource(R.string.instance_repair_rollback_help),
                        trailing = {
                            CompactButton(
                                text = stringResource(R.string.instance_repair_rollback_action),
                                enabled = !busy && previousAvailable,
                                onClick = { SandboxService.repairLayerRollback(context) },
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InstanceRow(
                        iconRes = CommonR.drawable.ic_refresh,
                        title = stringResource(R.string.instance_repair_reinstall_online),
                        description = stringResource(R.string.instance_repair_reinstall_help),
                        trailing = {
                            CompactButton(
                                text = stringResource(R.string.instance_repair_reinstall_action),
                                enabled = !busy,
                                onClick = { runtimeUpdateManager.reinstallCurrentDshOnline() },
                            )
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    InstanceRow(
                        iconRes = CommonR.drawable.ic_refresh,
                        title = stringResource(R.string.instance_repair_restore_baseline),
                        description = stringResource(R.string.instance_repair_baseline_help),
                        trailing = {
                            CompactButton(
                                text = stringResource(R.string.instance_repair_baseline_action),
                                enabled = !busy && bundledVersion != null,
                                onClick = { confirmBaseline = true },
                            )
                        },
                    )
                }

                if (busy) {
                    Text(
                        text = stringResource(R.string.instance_op_running),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (op.error != null) {
                    Text(
                        text = stringResource(R.string.instance_op_failed) + ": " + op.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (op.lastSuccess == true) {
                    Text(
                        text = stringResource(R.string.instance_op_success),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    text = stringResource(R.string.instance_lan_follow_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.instance_delete_confirm_title)) },
            text = { Text(stringResource(R.string.instance_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    SandboxService.deleteEmergency(context)
                }) { Text(stringResource(R.string.instance_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.instance_cancel))
                }
            },
        )
    }

    if (confirmBaseline) {
        AlertDialog(
            onDismissRequest = { confirmBaseline = false },
            title = { Text(stringResource(R.string.instance_baseline_confirm_title)) },
            text = { Text(baselineMessage) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBaseline = false
                    SandboxService.repairLayerRestoreBaseline(context)
                }) { Text(stringResource(R.string.instance_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmBaseline = false }) {
                    Text(stringResource(R.string.instance_cancel))
                }
            },
        )
    }
}

/** 红色警示横幅（图标 + 文案），用于管理页与破坏性确认弹窗。 */
@Composable
private fun WarningBanner(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_alert_triangle),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** 分节卡片：标题 + 表面色内容块（上下分隔线），沿用全局设置页语言。 */
@Composable
private fun InstanceSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** 设置行：图标 + 标题/描述 + 紧凑尾部按钮。 */
@Composable
private fun InstanceRow(
    iconRes: Int,
    title: String,
    description: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailing()
        }
    }
}

/** 紧凑描边按钮（28dp 高），与全局 CompactActionButton 同规格，避免大色块拉伸。 */
@Composable
private fun CompactButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier
            .widthIn(min = 64.dp)
            .height(28.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}
