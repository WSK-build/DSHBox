package com.dshbox.remote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.app.common.R as CommonR
import com.dshbox.remote.R
import com.dshbox.remote.RemoteConnectionController
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.EndpointChoice
import com.dshbox.remote.model.PairingTarget
import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.SessionMode
import com.dshbox.remote.model.toArchiveValue

/**
 * 配对确认卡：扫码/手动输入解析成功后的建档前确认（不静默建档）。
 *
 * 标题与确认按钮文案由调用方给定（新建设备与重新配对各自成套，确认动作
 * 的语义随来源不同）。内容自上而下：插件类型（复用设备卡的类型标注）→
 * **分组归属**（无分组 / 新建分组 + 名称 / 并入已有设备；即设备名行，重配
 * 对来源不显示）→ 通道数（多端点载荷提示按来源成套：新建「将添加」/ 重配
 * 「更新」，[endpointCountNote] 覆写）→ **逐端点
 * 选择区**（每行：通道 + 地址摘要，配「代理/直连」与会话保留档两组
 * chips——直连按载荷预填，保留档默认「继承(全局默认档位)」，选择落档
 * 随确认一次写入档案）；通用网页通道追加「按普通网页代理」的提示行；
 * 建档校验失败的就地报错渲染在卡内（卡保持打开，用户可取消或重试）。
 * 取消即关闭卡且不产生任何档案变更。
 *
 * 卡体高度受控：内容区 `heightIn(max = …)` + 内部滚动，手机小屏/多端点
 * 载荷不越过对话框可用区（chips 行横向滚动，不换行不挤压）。
 *
 * @param availableGroups 既有设备清单（分组下拉的「并入已有」选项来源）。
 * @param selectedTarget 当前分组归属选择（无分组默认；配置变更即回到默认值）。
 * @param onTargetChange 分组归属选择回调（下拉点选/分组名输入即上抛）。
 * @param endpointCountNote 多端点载荷的通道数提示覆写（null 用新建成套文案）。
 * @param choicesEnabled 逐端点选择区是否可见（true = 可选 chips；false =
 *   重配对来源——选择不生效，隐藏整块选择区并附一行说明，避免用户改了
 *   却被静默丢弃）。
 * @param confirmEnabled 确认按钮可用态：建档/重配进行中禁用（防快速二次点按重复建档）。
 * @param defaultRetention 全局默认保留档（「继承」chip 的显示文案附此档位）。
 * @param onConfirm 携带分组归属与逐端点选择提交（[EndpointChoice.sessionMode] 为兜底
 *   收口后的存储枚举名；非 https 的直连选中在选择时即收敛为代理档，
 *   提交前不再二次改写）。
 */
@Composable
internal fun PairingConfirmCard(
    preview: RemoteConnectionController.PairingPreview,
    title: String,
    confirmLabel: String,
    error: String?,
    availableGroups: List<Device>,
    selectedTarget: PairingTarget,
    onTargetChange: (PairingTarget) -> Unit,
    onConfirm: (PairingTarget, List<EndpointChoice>) -> Unit,
    onDismiss: () -> Unit,
    defaultRetention: RetentionPolicy,
    endpointCountNote: String? = null,
    choicesEnabled: Boolean = true,
    confirmEnabled: Boolean = true,
) {
    // 逐端点选择的组合期状态：会话模式档案枚举名 + 保留档 ""（继承）或
    // 档案编码值；配置变更即重组本卡（状态随之重建，选择回到预填值）。
    // 可变对齐列表：索引即预览端点序。
    var selections by remember(preview) {
        mutableStateOf(
            preview.endpoints.map { endpoint ->
                endpoint.defaultSessionMode to ""
            },
        )
    }
    // 「新建分组」命名态：只有点行内「确定」才把名字写进归属；若用户直接按
    // 主按钮，也采用已输入的草稿名（不让输入白费）
    var namingGroup by rememberSaveable { mutableStateOf(false) }
    var groupNameDraft by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = ConfirmCardContentMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = parserLabelText(preview.parserId),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                // 分组归属（即设备名那一行，参照反馈图）：无分组 / 新建分组
                // （就地命名 + 确定）/ 并入已有设备；重配对来源不显示
                if (choicesEnabled) {
                    // 选「新建分组」进入命名态：输入框 + 确定；确认后才把名字
                    // 写进归属（未确认前不落档，避免挂着一个无主名字）
                    DeviceTargetSelector(
                        availableGroups = availableGroups,
                        selectedTarget = selectedTarget,
                        onTargetChange = { target ->
                            if (target is PairingTarget.NewGroup) {
                                groupNameDraft = target.name ?: preview.deviceName
                                namingGroup = true
                                onTargetChange(PairingTarget.NewGroup(null))
                            } else {
                                namingGroup = false
                                onTargetChange(target)
                            }
                        },
                    )
                    if (namingGroup) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = groupNameDraft,
                                onValueChange = { groupNameDraft = it },
                                label = { Text(stringResource(R.string.remote_confirm_group_name_label)) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(
                                enabled = groupNameDraft.isNotBlank(),
                                onClick = {
                                    onTargetChange(PairingTarget.NewGroup(groupNameDraft.trim()))
                                    namingGroup = false
                                },
                            ) {
                                Text(stringResource(R.string.remote_confirm))
                            }
                        }
                    }
                }
                if (preview.endpointCount > 1) {
                    Text(
                        text = endpointCountNote
                            ?: stringResource(R.string.remote_confirm_endpoint_count, preview.endpointCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 逐端点选择区：通道 + 地址摘要 + 会话模式/保留档两组 chips
                // （每端点独立成块；与落档序一一对应）。重配对来源隐藏整块
                // 选择区（不是置灰——可选择却无效的控件更误导），改附一行
                // 说明：模式/保留档按既有档案沿用，可在设备卡内修改。
                if (choicesEnabled) {
                    preview.endpoints.forEachIndexed { index, endpoint ->
                        EndpointChoiceRows(
                            channel = endpoint.channelLabel,
                            targetUrl = endpoint.targetUrl,
                            sessionMode = selections[index].first,
                            retentionValue = selections[index].second,
                            directEligible = endpoint.targetUrl.trim()
                                .startsWith("https://", ignoreCase = true),
                            defaultRetention = defaultRetention,
                            onSessionModeChange = { mode ->
                                selections = selections.mapIndexed { i, pair ->
                                    if (i == index) mode to pair.second else pair
                                }
                            },
                            onRetentionChange = { value ->
                                selections = selections.mapIndexed { i, pair ->
                                    if (i == index) pair.first to value else pair
                                }
                            },
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.remote_confirm_repair_choices_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.isGeneric) {
                    Text(
                        text = stringResource(R.string.remote_confirm_generic_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = confirmEnabled, onClick = {
                // 兜底收口（口径与预填同源）：非 https 的直连选中提交前收敛
                // 为代理档；空/未知保留档值按继承（null）落档
                val fallback = selections.mapIndexed { index, (mode, retention) ->
                    EndpointChoice(
                        sessionMode = com.dshbox.remote.model.effectiveSessionMode(
                            address = preview.endpoints[index].targetUrl,
                            selected = mode,
                        ),
                        retentionOverride = retention.takeIf { it.isNotEmpty() },
                    )
                }
                val effectiveTarget = if (namingGroup && groupNameDraft.isNotBlank()) {
                    PairingTarget.NewGroup(groupNameDraft.trim())
                } else {
                    selectedTarget
                }
                onConfirm(effectiveTarget, fallback)
            }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_cancel))
            }
        },
    )
}

/**
 * 单端点的选择块：通道 + 地址摘要 + 会话模式两枚 chips + 保留档六枚
 * chips（继承默认 / 即时 / 1 / 5 / 15 分钟 / 永久）。非 https 端点的直
 * 连 chip 置灰并附一行原因（直连仅放行 https）；地址摘要超长截断（不
 * 撑开卡体）。
 */
@Composable
private fun EndpointChoiceRows(
    channel: String,
    targetUrl: String,
    sessionMode: String,
    retentionValue: String,
    directEligible: Boolean,
    defaultRetention: RetentionPolicy,
    onSessionModeChange: (String) -> Unit,
    onRetentionChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = channelLabelText(channel),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = targetUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 直连选项的准入：非 https 端点置禁并附一行原因（与设备卡编辑
            // 对话框同口径），阻止会话层构造期拒绝的组合落进档案
            FilterChip(
                selected = sessionMode == SessionMode.PROXIED.name,
                onClick = { onSessionModeChange(SessionMode.PROXIED.name) },
                label = { Text(stringResource(R.string.remote_session_mode_proxied)) },
            )
            FilterChip(
                selected = sessionMode == SessionMode.DIRECT.name,
                enabled = directEligible,
                onClick = { onSessionModeChange(SessionMode.DIRECT.name) },
                label = { Text(stringResource(R.string.remote_session_mode_direct)) },
            )
        }
        if (!directEligible) {
            Text(
                text = stringResource(R.string.remote_direct_https_limitation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 继承项附全局默认档位：一眼可见实际生效档（继承 = 继承全局默认）
            FilterChip(
                selected = retentionValue.isEmpty(),
                onClick = { onRetentionChange("") },
                label = {
                    Text(
                        stringResource(
                            R.string.remote_retention_inherit,
                            retentionPolicyText(defaultRetention),
                        ),
                    )
                },
            )
            FilterChip(
                selected = retentionValue == RetentionPolicy.IMMEDIATE.toArchiveValue(),
                onClick = { onRetentionChange(RetentionPolicy.IMMEDIATE.toArchiveValue()) },
                label = { Text(stringResource(R.string.remote_retention_immediate)) },
            )
            listOf(1, 5, 15).forEach { minutes ->
                val value = RetentionPolicy.Retain(minutes).toArchiveValue()
                FilterChip(
                    selected = retentionValue == value,
                    onClick = { onRetentionChange(value) },
                    label = { Text(stringResource(R.string.remote_retention_minutes, minutes)) },
                )
            }
            FilterChip(
                selected = retentionValue == RetentionPolicy.FOREVER.toArchiveValue(),
                onClick = { onRetentionChange(RetentionPolicy.FOREVER.toArchiveValue()) },
                label = { Text(stringResource(R.string.remote_retention_forever)) },
            )
        }
    }
}

/** 确认卡内容区的最大高度（手机小屏留出标题/按钮区）。 */
private val ConfirmCardContentMaxHeight = 400.dp

/**
 * 分组归属选择器：内联触发器（当前分组 + 下箭头）与下拉菜单（无分组 /
 * 新建分组 / 已有设备——只列非空名设备）。分组名输入由调用方在选中
 * 「新建分组」后就地渲染。
 */
@Composable
private fun DeviceTargetSelector(
    availableGroups: List<Device>,
    selectedTarget: PairingTarget,
    onTargetChange: (PairingTarget) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clickable { menuOpen = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = targetLabel(selectedTarget, availableGroups),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_down),
                contentDescription = stringResource(R.string.remote_confirm_device_target),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_group_none)) },
                onClick = {
                    menuOpen = false
                    onTargetChange(PairingTarget.Ungrouped)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.remote_group_new)) },
                onClick = {
                    menuOpen = false
                    onTargetChange(PairingTarget.NewGroup(null))
                },
            )
            availableGroups.filter { it.name.isNotEmpty() }.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name) },
                    onClick = {
                        menuOpen = false
                        onTargetChange(PairingTarget.Existing(device.id))
                    },
                )
            }
        }
    }
}

/** 归属选择的展示文案：无分组 / 新建分组 / 目标设备名（设备已删回退无分组）。 */
@Composable
private fun targetLabel(target: PairingTarget, groups: List<Device>): String = when (target) {
    is PairingTarget.Existing ->
        groups.firstOrNull { it.id == target.deviceId }?.name
            ?: stringResource(R.string.remote_group_none)
    PairingTarget.Ungrouped -> stringResource(R.string.remote_group_none)
    is PairingTarget.NewGroup ->
        target.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.remote_group_new)
}
