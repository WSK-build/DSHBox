package com.dshbox.remote.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.rotate
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.common.R as CommonR
import com.dshbox.remote.R
import com.dshbox.remote.RemoteConnectionController
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.SessionState
import com.dshbox.remote.model.retentionPolicyFromArchive
import com.dshbox.remote.model.toArchiveValue
import com.dshbox.remote.RemoteConnectionController.EndpointUpdateResult
import com.dshbox.remote.model.effectiveSessionMode
import com.dshbox.remote.parse.ManualPayloadSupport
import kotlinx.coroutines.launch

/** 端点行连接状态点的直径。 */
private val StatusDotSize = 8.dp

/** 通道标签 chip 的水平内边距。 */
private val ChipHorizontalPadding = 8.dp

/**
 * 设备折叠卡：卡头（设备名 · 连接状态点 · 展开箭头）+ 展开区（端点条目 ×N
 * 与删除操作）。卡头 = 34dp 图标底 + 设备名 + 重命名铅笔 + 展开箭头（无
 * 会话状态点）。端点条目 = 状态点 + 目标地址（+ 状态文案）+ 显示源单选框 /
 * 通道标签 chip / 操作行（代理-直连段选 · 保留档下拉 · 更多三点菜单：编辑、
 * 移动到设备、重新扫码更新、重新配对与删除此端点）。折叠状态随设备标识记
 * 忆；平板形态只渲染卡头（点选即右栏出端点详情，[headOnly]）。删除设备经
 * 二次确认（文案含设备名），确认即销毁该设备的全部会话与登录态存档并回收
 * 槽位。
 */
@Composable
internal fun DeviceCard(
    device: Device,
    activeDisplay: DisplayTarget,
    sessionStates: Map<EndpointId, SessionState>,
    controller: RemoteConnectionController,
    onRescanEndpoint: (EndpointId) -> Unit,
    modifier: Modifier = Modifier,
    headOnly: Boolean = false,
    onSelectDevice: (() -> Unit)? = null,
    onRenameDevice: () -> Unit = {},
    selected: Boolean = false,
    pairingRejected: Set<EndpointId> = emptySet(),
) {
    val scope = rememberCoroutineScope()
    val defaultRetention by controller.retention.collectAsState()
    var expanded by rememberSaveable(device.id.value) { mutableStateOf(false) }
    var deleteConfirming by rememberSaveable(device.id.value) { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (selected) {
            MaterialTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column {
            CardHeader(
                device = device,
                expanded = expanded,
                onToggle = {
                    // 平板（headOnly）：点选卡头 = 选中设备（右栏出详情）；
                    // 手机：点选卡头 = 切换折叠
                    when {
                        headOnly -> onSelectDevice?.invoke()
                        else -> expanded = !expanded
                    }
                },
                onRename = onRenameDevice,
                tablet = headOnly,
            )
            if (!headOnly) {
                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // 端点标签：端点 · N 条通道
                        Text(
                            text = stringResource(
                                R.string.remote_device_endpoint_summary,
                                device.endpoints.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        device.endpoints.forEach { endpoint ->
                            EndpointItem(
                                device = device,
                                endpoint = endpoint,
                                activeDisplay = activeDisplay,
                                sessionStates = sessionStates,
                                controller = controller,
                                defaultRetention = defaultRetention,
                                pairingRejected = endpoint.id in pairingRejected,
                                onRescan = { onRescanEndpoint(endpoint.id) },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = { deleteConfirming = true },
                            ) {
                                Icon(
                                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_trash),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.remote_device_delete))
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleteConfirming) {
        AlertDialog(
            onDismissRequest = { deleteConfirming = false },
            title = { Text(stringResource(R.string.remote_device_delete_title)) },
            text = { Text(stringResource(R.string.remote_device_delete_message, deviceDisplayName(device))) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirming = false
                    controller.removeDevice(device.id)
                }) {
                    Text(stringResource(R.string.remote_device_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirming = false }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }

    // 重新配对（手动输入入口）：目标端点由更多菜单显式登记
    var repairingEndpointId by remember { mutableStateOf<String?>(null) }
    val repairTarget = repairingEndpointId?.let { target ->
        device.endpoints.firstOrNull { it.id.value == target }
    }
    if (repairTarget != null) {
        var repairError by remember(repairTarget.id.value) { mutableStateOf<String?>(null) }
        ManualInputDialog(
            title = stringResource(R.string.remote_device_repair),
            description = stringResource(R.string.remote_repair_description, deviceDisplayName(device)),
            confirmLabel = stringResource(R.string.remote_device_repair),
            showNote = false,
            error = repairError?.let { stringResource(R.string.remote_edit_endpoint_invalid) },
            onDismiss = { repairingEndpointId = null },
            onConfirm = { address, token, _ ->
                scope.launch {
                    // 重新配对即编辑语义：解析归一化、凭据替换、代次自增。
                    // 整段载荷优先：含竖线或非 http(s) 形态的地址栏内容原样
                    // 提交（JSON 描述符/自定义 scheme 可经重配对更新端点）
                    val raw = ManualPayloadSupport.compose(address, token)
                    when (controller.updateEndpointTarget(device.id, repairTarget.id, raw)) {
                        EndpointUpdateResult.UPDATED -> {
                            repairingEndpointId = null
                            repairError = null
                        }
                        // 无变更本应成功收口；设备/端点在确认期间被删除时重配
                        // 链路同样返回空变更，复查清单就地报错（与面板/编辑框
                        // 同口径，不静默成功）
                        EndpointUpdateResult.UNCHANGED -> {
                            val stillArchived = controller.devices.value.any { candidate ->
                                candidate.id == device.id &&
                                    candidate.endpoints.any { it.id == repairTarget.id }
                            }
                            if (stillArchived) {
                                repairingEndpointId = null
                                repairError = null
                            } else {
                                repairError = "invalid"
                            }
                        }
                        EndpointUpdateResult.INVALID -> repairError = "invalid"
                    }
                }
            },
        )
    }
}

/** 设备显示名：无分组条目（空名）以六语文案呈现，其余原样。 */
@Composable
internal fun deviceDisplayName(device: Device): String =
    device.name.ifEmpty { stringResource(R.string.remote_group_none) }

/**
 * 卡头（.device-head）：图标底 + 设备名 + 重命名铅笔 + 展开箭头，
 * 整行可点切换折叠。列表行不再挂会话状态点——状态在端点行呈现。
 */
@Composable
private fun CardHeader(
    device: Device,
    expanded: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    tablet: Boolean = false,
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "device-chevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(
                start = 20.dp,
                end = if (tablet) 20.dp else 16.dp,
                top = if (tablet) 13.dp else 16.dp,
                bottom = if (tablet) 13.dp else 16.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 设备图标底：34dp 圆角方底 + 19dp 图标
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_devices),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = deviceDisplayName(device),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .size(30.dp)
                .clickable(role = Role.Button, onClick = onRename),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_pencil),
                contentDescription = stringResource(R.string.remote_device_rename),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_right),
            contentDescription = stringResource(
                if (expanded) R.string.remote_device_collapse else R.string.remote_device_expand,
            ),
            // 平板卡头箭头弱化（.dh-chevron 透明度 .55）
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (tablet) 0.55f else 1f,
            ),
            modifier = Modifier
                .size(20.dp)
                .rotate(chevronRotation),
        )
    }
}

/**
 * 单个端点条目（设备卡展开区与平板详情面板共用）：端点子卡 + 其菜单与对话
 * 弹层的编排容器。端点子卡分三段：顶部行（状态点 + 目标地址 + 状
 * 态文案 + 显示源单选框）、通道标签 chip、操作行（代理/直连段选 · 保留档下
 * 拉 · 更多三点菜单）。
 */
@Composable
internal fun EndpointItem(
    device: Device,
    endpoint: Endpoint,
    activeDisplay: DisplayTarget,
    sessionStates: Map<EndpointId, SessionState>,
    controller: RemoteConnectionController,
    defaultRetention: RetentionPolicy,
    pairingRejected: Boolean = false,
    onRescan: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 更多菜单 / 移动目标子菜单 / 保留档菜单 / 编辑与删除弹层的开关记账
    var moreMenuOpen by remember { mutableStateOf(false) }
    var moveMenuOpen by remember { mutableStateOf(false) }
    var retentionMenuOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }
    // 移动到新建分组：目标面板选「新建分组」后弹命名卡，确认才迁移
    var movingToNewGroup by remember { mutableStateOf(false) }
    val selected = (activeDisplay as? DisplayTarget.Session)?.endpointId == endpoint.id
    // 移动目标清单：除本设备外的全部设备（含无分组空名条目）；移动入口
    // 恒在，目标面板末尾另提供「新建分组」
    val moveTargets = moveTargets(controller.devices.value, device.id)
    // 单选框的无障碍朗读文案（原行内语义描述的延续：选中即设为显示源）
    val setAsDisplaySourceDescription = stringResource(R.string.remote_endpoint_set_display)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 顶部行：状态点 + 地址（+ 状态文案）+ 显示源单选框
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.RadioButton,
                        onClick = { controller.selectEndpoint(device.id, endpoint.id) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(StatusDotSize)
                        .background(
                            statusColor(sessionStates[endpoint.id])
                                ?: MaterialTheme.colorScheme.outlineVariant,
                            CircleShape,
                        ),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = endpoint.targetUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = endpointStatusLine(
                            sessionStates[endpoint.id],
                            selected,
                            pairingRejected,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RadioButton(
                    selected = selected,
                    // 无障碍朗读口径：单选框的动作语义（行点击同语义，整行可点）
                    onClick = { controller.selectEndpoint(device.id, endpoint.id) },
                    modifier = Modifier.semantics {
                        contentDescription = setAsDisplaySourceDescription
                    },
                )
            }
            // 通道标签 + 失败/凭据失效时的行内快捷重配（.ep-meta）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = channelLabelText(endpoint.channelLabel),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    )
                }
                if (pairingRejected) {
                    Spacer(modifier = Modifier.weight(1f))
                    // 快捷重配：复用「重新配对」手动输入链路（与更多菜单同源）
                    Row(
                        modifier = Modifier
                            .clickable(role = Role.Button) { repairing = true }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(R.string.remote_device_repair),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                }
            }
            // 操作行（单行，不换行）：代理/直连段选 · 保留档下拉 · 更多三点
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeSegment(
                    sessionMode = endpoint.sessionMode,
                    directEligible = endpoint.targetUrl.trim()
                        .startsWith("https://", ignoreCase = true),
                    onSelect = { mode ->
                        controller.setEndpointSessionMode(device.id, endpoint.id, mode)
                    },
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box {
                    // 保留档触发器（.retain-trigger：抬升底胶囊 + 12sp）
                    Surface(
                        modifier = Modifier.clickable(role = Role.Button) { retentionMenuOpen = true },
                        // 保留档胶囊：6dp 方正小圆角，非全圆胶囊
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.remote_retention_trigger,
                                    retentionLabelText(endpoint.retentionOverride, defaultRetention),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // 长语言（德/俄式合成词）超宽省略而非硬裁：段选与三
                                // 点按钮先占位，触发器独占剩余宽度
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Icon(
                                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_down),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    RetentionMenu(
                        expanded = retentionMenuOpen,
                        currentOverride = endpoint.retentionOverride,
                        defaultRetention = defaultRetention,
                        onDismiss = { retentionMenuOpen = false },
                        onSelect = { policy ->
                            retentionMenuOpen = false
                            controller.setEndpointRetention(device.id, endpoint.id, policy)
                        },
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clickable(role = Role.Button) { moreMenuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_dots_vertical),
                            contentDescription = stringResource(R.string.remote_endpoint_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    MoreMenu(
                        expanded = moreMenuOpen,
                        // 移动/切换分组恒在：目标面板里可并入已有设备或新建分组
                        moveAvailable = true,
                        // 无分组条目 → 移入分组；已在分组 → 切换到其它分组
                        moveLabel = stringResource(
                            if (device.name.isEmpty()) {
                                R.string.remote_endpoint_move_to_group
                            } else {
                                R.string.remote_endpoint_switch_group
                            },
                        ),
                        onDismiss = { moreMenuOpen = false },
                        onEdit = {
                            moreMenuOpen = false
                            editing = true
                        },
                        onRequestMoveMenu = {
                            // 移动到设备：更多菜单收起后同点位弹出目标子菜单
                            moreMenuOpen = false
                            moveMenuOpen = true
                        },
                        onRescan = {
                            moreMenuOpen = false
                            onRescan()
                        },
                        onRepair = {
                            moreMenuOpen = false
                            repairing = true
                        },
                        onDelete = {
                            moreMenuOpen = false
                            deleting = true
                        },
                    )
                    // 移动目标子菜单：锚在同一三点按钮上，点选即迁移
                    MoveTargetMenu(
                        expanded = moveMenuOpen,
                        targets = moveTargets,
                        onDismiss = { moveMenuOpen = false },
                        onSelect = { target ->
                            moveMenuOpen = false
                            scope.launch { controller.moveEndpointTo(device.id, endpoint.id, target) }
                        },
                        onNewGroup = {
                            moveMenuOpen = false
                            movingToNewGroup = true
                        },
                    )
                }
            }
        }
    }

    if (movingToNewGroup) {
        var groupName by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { movingToNewGroup = false },
            title = { Text(stringResource(R.string.remote_group_new)) },
            text = {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text(stringResource(R.string.remote_confirm_group_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = groupName.isNotBlank(),
                    onClick = {
                        movingToNewGroup = false
                        scope.launch {
                            controller.moveEndpointToNewGroup(device.id, endpoint.id, groupName.trim())
                        }
                    },
                ) {
                    Text(stringResource(R.string.remote_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { movingToNewGroup = false }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }

    if (editing) {
        var editError by remember(endpoint.id.value) { mutableStateOf<String?>(null) }
        EndpointEditDialog(
            initialAddress = endpoint.targetUrl,
            currentRetentionOverride = endpoint.retentionOverride,
            currentSessionMode = endpoint.sessionMode,
            defaultRetention = defaultRetention,
            error = editError?.let { stringResource(R.string.remote_edit_endpoint_invalid) },
            onDismiss = { editing = false },
            onConfirm = { address, retentionOverride, sessionMode ->
                scope.launch {
                    // 会话模式作为显式用户选择随编辑一并透传：对齐层以它优先于
                    // 载荷声明并经口径函数收口（直连要求 https），档案在数据层
                    // 原子落盘——不再有确认后快照比较与二次模式写入（双写会各
                    // 自自增代次）。
                    when (controller.updateEndpointTarget(
                        device.id,
                        endpoint.id,
                        address,
                        effectiveSessionMode(address, sessionMode),
                    )) {
                        // 地址与凭据均未变化且模式未改：无变更信号，正常关闭而非
                        // 报错（模式已在数据层原子落盘）；设备在编辑期间被删除时
                        // 空变更不可分辨，复查清单就地报错
                        EndpointUpdateResult.UPDATED, EndpointUpdateResult.UNCHANGED -> {
                            if (controller.devices.value.none { it.id == device.id }) {
                                editError = "invalid"
                            } else {
                                controller.setEndpointRetention(device.id, endpoint.id, retentionOverride)
                                editing = false
                                editError = null
                            }
                        }
                        // 解析失败（非法或非 http(s) 地址）：对话框保持打开就地报错
                        EndpointUpdateResult.INVALID -> editError = "invalid"
                    }
                }
            },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.remote_endpoint_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.remote_endpoint_delete_message,
                        channelLabelText(endpoint.channelLabel),
                        endpoint.targetUrl,
                        deviceDisplayName(device),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch { controller.removeEndpoint(device.id, endpoint.id) }
                }) {
                    Text(stringResource(R.string.remote_device_confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = false }) {
                    Text(stringResource(R.string.remote_cancel))
                }
            },
        )
    }

    if (repairing) {
        var repairError by remember(endpoint.id.value) { mutableStateOf<String?>(null) }
        ManualInputDialog(
            title = stringResource(R.string.remote_device_repair),
            description = stringResource(R.string.remote_repair_description, deviceDisplayName(device)),
            confirmLabel = stringResource(R.string.remote_device_repair),
            showNote = false,
            error = repairError?.let { stringResource(R.string.remote_edit_endpoint_invalid) },
            onDismiss = { repairing = false },
            onConfirm = { address, token, _ ->
                scope.launch {
                    // 重新配对即编辑语义：解析归一化、凭据替换、代次自增；
                    // 整段载荷优先（JSON 描述符/自定义 scheme 可经重配对更新端点）
                    val raw = ManualPayloadSupport.compose(address, token)
                    when (controller.updateEndpointTarget(device.id, endpoint.id, raw)) {
                        EndpointUpdateResult.UPDATED -> {
                            repairing = false
                            repairError = null
                        }
                        // 设备/端点在确认期间被删除时空变更不可分辨，复查清单
                        // 就地报错（与编辑对话框同口径，不静默成功）
                        EndpointUpdateResult.UNCHANGED -> {
                            val stillArchived = controller.devices.value.any { candidate ->
                                candidate.id == device.id &&
                                    candidate.endpoints.any { it.id == endpoint.id }
                            }
                            if (stillArchived) {
                                repairing = false
                                repairError = null
                            } else {
                                repairError = "invalid"
                            }
                        }
                        EndpointUpdateResult.INVALID -> repairError = "invalid"
                    }
                }
            },
        )
    }
}

/** 移动到设备的目标清单：除源设备外的全部设备（含无分组空名条目）。 */
internal fun moveTargets(devices: List<Device>, sourceId: DeviceId): List<Device> =
    devices.filter { it.id != sourceId }

/** 端点更多菜单：编辑 / 移动或切换分组 / 重新扫码更新 / 重新输入配对 / 删除此端点。 */
@Composable
private fun MoreMenu(
    expanded: Boolean,
    moveAvailable: Boolean,
    moveLabel: String,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onRequestMoveMenu: () -> Unit,
    onRescan: () -> Unit,
    onRepair: () -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remote_endpoint_edit)) },
            leadingIcon = {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_pencil),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onEdit,
        )
        if (moveAvailable) {
            DropdownMenuItem(
                text = { Text(moveLabel) },
                leadingIcon = {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrows_right_left),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = onRequestMoveMenu,
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remote_device_rescan)) },
            leadingIcon = {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_qr_scan),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onRescan,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remote_device_repair_input)) },
            leadingIcon = {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_pencil),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onRepair,
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remote_endpoint_delete)) },
            leadingIcon = {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_trash),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = onDelete,
        )
    }
}

/** 移动到设备的目标子菜单：其他设备（含无分组），点选即迁移。 */
@Composable
private fun MoveTargetMenu(
    expanded: Boolean,
    targets: List<Device>,
    onDismiss: () -> Unit,
    onSelect: (DeviceId) -> Unit,
    onNewGroup: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        targets.forEach { target ->
            DropdownMenuItem(
                text = { Text(deviceDisplayName(target)) },
                onClick = { onSelect(target.id) },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.remote_group_new)) },
            onClick = onNewGroup,
        )
    }
}

/** 代理/直连段选（.seg-mini）：非 https 端点禁用直连。 */
@Composable
private fun ModeSegment(
    sessionMode: String?,
    directEligible: Boolean,
    onSelect: (com.dshbox.remote.model.SessionMode) -> Unit,
) {
    val current = sessionMode?.let { value ->
        runCatching { com.dshbox.remote.model.SessionMode.valueOf(value) }.getOrNull()
    } ?: com.dshbox.remote.model.SessionMode.PROXIED
    // 段选：抬升灰底容器 + 选中项浅底药丸（主文本 + semibold），
    // 与未选中项形成明显明暗差
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ModeSegmentButton(
                label = stringResource(R.string.remote_session_mode_proxied),
                selected = current == com.dshbox.remote.model.SessionMode.PROXIED,
                onClick = { onSelect(com.dshbox.remote.model.SessionMode.PROXIED) },
            )
            ModeSegmentButton(
                label = stringResource(R.string.remote_session_mode_direct),
                selected = current == com.dshbox.remote.model.SessionMode.DIRECT,
                enabled = directEligible,
                onClick = { onSelect(com.dshbox.remote.model.SessionMode.DIRECT) },
            )
        }
    }
}

/** 段选单钮：选中档以表面底色浮起（.seg-mini button.is-on）。 */
@Composable
private fun ModeSegmentButton(
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        tonalElevation = if (selected) 1.dp else 0.dp,
    ) {
        Box(
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick, role = Role.Button)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    selected -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

/**
 * 选择 chip（.fchip）：6dp 方正小圆角、抬升底；选中为主色容器 + 1dp
 * 主色描边 + semibold。设置卡与配对确认卡共用，尺寸可由调用方覆写（确认卡
 * 用 11sp / 4×9，设置卡用 12sp / 7×13）。
 */
@Composable
internal fun PillChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    fontSize: TextUnit = 12.sp,
    horizontalPadding: Dp = 13.dp,
    verticalPadding: Dp = 7.dp,
) {
    Surface(
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = fontSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding,
            ),
        )
    }
}

/** 保留档下拉菜单：继承默认 / 即时 / 1 / 5 / 15 分钟 / 永久（选中项带勾）。 */
@Composable
private fun RetentionMenu(
    expanded: Boolean,
    currentOverride: String?,
    defaultRetention: RetentionPolicy,
    onDismiss: () -> Unit,
    onSelect: (RetentionPolicy?) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        RetentionOption(
            label = stringResource(
                R.string.remote_retention_inherit,
                retentionPolicyText(defaultRetention),
            ),
            selected = currentOverride == null,
            onClick = { onSelect(null) },
        )
        RetentionOption(
            label = stringResource(R.string.remote_retention_immediate),
            selected = currentOverride == RetentionPolicy.IMMEDIATE.toArchiveValue(),
            onClick = { onSelect(RetentionPolicy.IMMEDIATE) },
        )
        listOf(1, 5, 15).forEach { minutes ->
            RetentionOption(
                label = stringResource(R.string.remote_retention_minutes, minutes),
                selected = currentOverride == RetentionPolicy.Retain(minutes).toArchiveValue(),
                onClick = { onSelect(RetentionPolicy.Retain(minutes)) },
            )
        }
        RetentionOption(
            label = stringResource(R.string.remote_retention_forever),
            selected = currentOverride == RetentionPolicy.FOREVER.toArchiveValue(),
            onClick = { onSelect(RetentionPolicy.FOREVER) },
        )
    }
}

/** 保留档菜单单选项：选中项尾随勾形。 */
@Composable
private fun RetentionOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = if (selected) {
            {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        } else {
            null
        },
        onClick = onClick,
    )
}

/**
 * 端点编辑对话框：目标地址可更新（隧道域名易变），并附「会话模式」选择
 * （代理承载 / 渲染器直连，确认即按新档重建该端点会话）与「保留策略」
 * 选择（继承默认 / 即时 / 1 / 5 / 15 分钟 / 永久；null = 继承）。身份与
 * 代次语义由控制器处理，保留档写入设备档案 v3 字段，会话模式随编辑链路
 * 原子落盘并即时生效。
 */
@Composable
private fun EndpointEditDialog(
    initialAddress: String,
    currentRetentionOverride: String?,
    currentSessionMode: String?,
    defaultRetention: RetentionPolicy,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (address: String, retentionOverride: RetentionPolicy?, sessionMode: String) -> Unit,
) {
    var address by rememberSaveable { mutableStateOf(initialAddress) }
    // 保留档以档案编码值入可恢复状态（"" = 继承默认），配置变更后选择不丢
    var retentionValue by rememberSaveable { mutableStateOf(currentRetentionOverride ?: "") }
    // 会话模式以档案枚举名入可恢复状态（null 档案 = 默认代理档），配置变更后选择不丢
    var sessionModeValue by rememberSaveable {
        mutableStateOf(currentSessionMode ?: com.dshbox.remote.model.SessionMode.PROXIED.name)
    }
    // 直连意向：用户在 chips 上显式点选时登记（点直连置真、点代理置假），
    // 地址随行收敛携带该意向——地址临时改走非 https 时显示收敛为代理档，
    // 地址改回 https 后恢复用户此前明确选过的直连，不做单向吞没
    var prefersDirect by rememberSaveable { mutableStateOf(currentSessionMode == com.dshbox.remote.model.SessionMode.DIRECT.name) }
    // 前缀判定与模式口径函数同只做前缀复验，完整可达性由会话层收口：确认
    // 按钮以宽松 http 前缀放行（非 http(s) 形态的地址栏内容由解析层判定），
    // 直连资格以 https 前缀收紧（两种前缀口径不是同一件事，各自对应用途）
    val estimateEligible = address.trim().startsWith("http", ignoreCase = true)
    val directEligible = address.trim().startsWith("https://", ignoreCase = true)
    // 地址输入随行收敛会话模式（带显式意向）：地址非 https 时收敛代理档，
    // 地址为 https 时按用户意向取档——已明确选过直连的用户全选重打地址的
    // 中间态不再永久吞掉直连意图
    LaunchedEffect(address) {
        val target = when {
            !directEligible -> com.dshbox.remote.model.SessionMode.PROXIED.name
            prefersDirect -> com.dshbox.remote.model.SessionMode.DIRECT.name
            else -> com.dshbox.remote.model.SessionMode.PROXIED.name
        }
        if (target != sessionModeValue) {
            sessionModeValue = target
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_edit_endpoint_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.remote_edit_endpoint_address_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.remote_session_mode_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 直连选项的准入判定随地址输入实时生效：以当前编辑的地址
                    // 是否为 https 为准（https 前缀即可，完整可达性由会话层
                    // 复验）——非 https 端点禁用直连，阻止会话层构造期拒绝的
                    // 组合落进档案；点选即登记显式意向，地址改回 https 后恢复
                    FilterChip(
                        selected = sessionModeValue == com.dshbox.remote.model.SessionMode.PROXIED.name,
                        onClick = {
                            prefersDirect = false
                            sessionModeValue = com.dshbox.remote.model.SessionMode.PROXIED.name
                        },
                        label = { Text(stringResource(R.string.remote_session_mode_proxied)) },
                    )
                    FilterChip(
                        selected = sessionModeValue == com.dshbox.remote.model.SessionMode.DIRECT.name,
                        enabled = directEligible,
                        onClick = {
                            prefersDirect = true
                            sessionModeValue = com.dshbox.remote.model.SessionMode.DIRECT.name
                        },
                        label = { Text(stringResource(R.string.remote_session_mode_direct)) },
                    )
                }
                // 地址非 https 时说明一行原因：直连选项不可用（代理档会话也
                // 显示该提示，作前置说明）——直连仅对 https 端点开放
                if (!directEligible) {
                    Text(
                        text = stringResource(R.string.remote_direct_https_limitation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.remote_retention_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = retentionValue.isEmpty(),
                        onClick = { retentionValue = "" },
                        label = {
                            Text(
                                // 继承项附默认档位，一眼可见实际生效档
                                stringResource(
                                    R.string.remote_retention_inherit,
                                    retentionPolicyText(defaultRetention),
                                ),
                            )
                        },
                    )
                    FilterChip(
                        selected = retentionValue == RetentionPolicy.IMMEDIATE.toArchiveValue(),
                        onClick = { retentionValue = RetentionPolicy.IMMEDIATE.toArchiveValue() },
                        label = { Text(stringResource(R.string.remote_retention_immediate)) },
                    )
                    listOf(1, 5, 15).forEach { minutes ->
                        val value = RetentionPolicy.Retain(minutes).toArchiveValue()
                        FilterChip(
                            selected = retentionValue == value,
                            onClick = { retentionValue = value },
                            label = { Text(stringResource(R.string.remote_retention_minutes, minutes)) },
                        )
                    }
                    FilterChip(
                        selected = retentionValue == RetentionPolicy.FOREVER.toArchiveValue(),
                        onClick = { retentionValue = RetentionPolicy.FOREVER.toArchiveValue() },
                        label = { Text(stringResource(R.string.remote_retention_forever)) },
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
            TextButton(
                // 确认可点按宽松 http 前缀放行（含非 http(s) 形态的整段载荷
                // 提交），与直连资格的 https 前缀口径不同；完整合法性由解析
                // 层与会话层复验
                enabled = estimateEligible,
                onClick = {
                    onConfirm(
                        address.trim(),
                        retentionValue.takeIf { it.isNotEmpty() }
                            ?.let(::retentionPolicyFromArchive),
                        // 最后一道保险：以确认时刻的地址再次收敛会话模式（口径
                        // 函数与随行收敛同源，直连要求 https）
                        effectiveSessionMode(address, sessionModeValue),
                    )
                },
            ) {
                Text(stringResource(R.string.remote_edit_endpoint_confirm))
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
 * 端点行的状态文案行（ep-addr small 的 STATUS 表映射）：Ready 已连
 * 接 / Loading 连接中 / Connecting 正在连接 / Failed·Disconnected 连接失败
 * / 其余（含无状态）未连接；正在显示的端点在状态后追加「正在显示」。
 */
@Composable
internal fun endpointStatusLine(
    state: SessionState?,
    displaying: Boolean,
    pairingRejected: Boolean = false,
): String {
    val status = when {
        // 凭据失效优先于通用失败态（STATUS.rejected）
        pairingRejected -> stringResource(R.string.remote_session_status_rejected)
        else -> when (state) {
            SessionState.Ready -> stringResource(R.string.remote_session_status_ready)
            SessionState.Loading -> stringResource(R.string.remote_session_status_loading)
            SessionState.Connecting -> stringResource(R.string.remote_session_status_connecting)
            SessionState.Failed, SessionState.Disconnected ->
                stringResource(R.string.remote_session_status_failed)
            else -> stringResource(R.string.remote_endpoint_status_idle)
        }
    }
    return if (displaying) {
        stringResource(R.string.remote_endpoint_displaying, status)
    } else {
        status
    }
}

/** 端点保留档展示文案：覆盖值优先，继承默认时附默认档位（如「继承(1分钟)」）。 */
@Composable
internal fun retentionLabelText(retentionOverride: String?, default: RetentionPolicy): String {
    val effectiveText = retentionPolicyText(retentionOverride?.let(::retentionPolicyFromArchive) ?: default)
    return if (retentionOverride == null) {
        stringResource(R.string.remote_retention_inherit, effectiveText)
    } else {
        effectiveText
    }
}

/** 单个保留策略的展示文案（六语资源映射，确认卡共用）。 */
@Composable
internal fun retentionPolicyText(policy: RetentionPolicy): String = when (policy) {
    RetentionPolicy.IMMEDIATE -> stringResource(R.string.remote_retention_immediate)
    is RetentionPolicy.Retain -> stringResource(R.string.remote_retention_minutes, policy.minutes)
    RetentionPolicy.FOREVER -> stringResource(R.string.remote_retention_forever)
}

/** 通道标签展示文案：枚举名映射到六语资源；未知标签原样显示（用户备注语义）。 */
@Composable
internal fun channelLabelText(label: String): String = when (
    runCatching { com.dshbox.remote.parse.ChannelKind.valueOf(label) }.getOrNull()
) {
    com.dshbox.remote.parse.ChannelKind.LAN -> stringResource(R.string.remote_channel_lan)
    com.dshbox.remote.parse.ChannelKind.PUBLIC -> stringResource(R.string.remote_channel_public)
    com.dshbox.remote.parse.ChannelKind.TUNNEL -> stringResource(R.string.remote_channel_tunnel)
    com.dshbox.remote.parse.ChannelKind.URL -> stringResource(R.string.remote_channel_url)
    null -> label
}

/**
 * 端点插件类型的产品专名映射（设备卡与配对确认卡共用，产品自称不翻译）：
 * generic 与未知标识返回 null（generic 走资源文案、未知原样显示）。
 */
internal fun parserProductLabel(parserId: String): String? = when (parserId) {
    com.dshbox.remote.parse.ParserIds.REMOTE_SCHEME -> "Dsh Remote"
    com.dshbox.remote.parse.ParserIds.ANON_RT -> "Anon RT"
    com.dshbox.remote.parse.ParserIds.CHONG_YEP -> "ChongYep"
    com.dshbox.remote.parse.ParserIds.SAYA_CH -> "Saya DSH Mobile"
    com.dshbox.remote.parse.ParserIds.GATEWAY -> "Gateway"
    com.dshbox.remote.parse.ParserIds.FULL_REMOTE -> "Full Remote"
    com.dshbox.remote.parse.ParserIds.BRIDGE -> "Dsh Bridge"
    com.dshbox.remote.parse.ParserIds.POCKET -> "Dsh Pocket"
    com.dshbox.remote.parse.ParserIds.ZCODE_REMOTE -> "ZCode 工作区" // i18n-skip：产品自称，随档案持久化
    com.dshbox.remote.parse.ParserIds.DS_HARNESS_REMOTE -> "DSH Remote"
    else -> null
}

/** 端点插件类型只读标注：产品专名不翻译；generic 走资源文案；未知标识原样显示。 */
@Composable
internal fun parserLabelText(parserId: String): String = parserProductLabel(parserId)
    ?: if (parserId == com.dshbox.remote.parse.ParserIds.GENERIC) {
        stringResource(R.string.remote_parser_generic)
    } else {
        parserId
    }

/** 设备卡状态点颜色：就绪主色、过程轮廓色、异常错误色；无状态按空闲处理。 */
@Composable
internal fun statusColor(state: SessionState?): Color? = when (state) {
    SessionState.Ready, SessionState.Loading -> MaterialTheme.colorScheme.primary
    SessionState.Failed, SessionState.Disconnected, SessionState.Terminated ->
        MaterialTheme.colorScheme.error
    SessionState.Connecting -> MaterialTheme.colorScheme.outline
    null -> null
    else -> MaterialTheme.colorScheme.outline
}
