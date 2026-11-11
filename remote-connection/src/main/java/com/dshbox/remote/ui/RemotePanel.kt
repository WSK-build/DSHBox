package com.dshbox.remote.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.common.R as CommonR
import com.dshbox.remote.scan.QrScanOverlay
import com.dshbox.remote.R
import com.dshbox.remote.RemoteConnectionController
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.PairingTarget
import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.SessionState
import com.dshbox.remote.parse.ManualPayloadSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/**
 * 面板的平板判定阈值：可用宽度 ≥ 600dp 双栏（左 = 配对 + 列表 + 设置，右 =
 * 端点详情），与远程会话微条的收起按钮阈值（data-ff="tablet"）同口径。
 */
private val TabletMinWidthDp = 600

/**
 * 远程连接面板：首页「远程」页签的内容。
 *
 * 布局：添加区（扫码/手动输入）→ 设备清单（本机 DSH 为清单
 * 首行，选中即切回本机显示；其后为设备存档驱动的折叠卡）→ 空状态引导（清
 * 单为空时）→ 设置区（会话保留时长五档与页面热池释放）。平板（可用宽度
 * ≥ 600dp）双栏：左栏 = 添加区 + 设备清单 + 设置卡（顶部留与上方的间距），
 * 右栏 = 选中设备的端点详情；手机单栏顺序同上、设置卡垫底。状态一律由注入
 * 的 [controller] 驱动，面板不持有模块业务状态。本机行选中即切回本机档并
 * 接续唤起本机 DSH 页签（[onOpenLocalDsh]，宿主接续页签事务），与端点行的
 * 「选中即呈现远程会话」语义对齐。
 *
 * 视觉与首页控制页同一份账：卡片圆角取 [MaterialTheme.shapes.large]，
 * 内容区左右出血 16dp、顶部 12dp、底部 24dp。
 */
@Composable
fun RemotePanel(
    controller: RemoteConnectionController,
    modifier: Modifier = Modifier,
    onSubPageVisibilityChanged: (Boolean) -> Unit = {},
    onOpenLocalDsh: () -> Unit = {},
) {
    val activeDisplay by controller.activeDisplay.collectAsState()
    val devices by controller.devices.collectAsState()
    val sessionStates by controller.sessionStates.collectAsState()
    // 凭据失效端点集合：端点行呈现「凭据失效」状态与「重新配对」快捷入口
    val pairingRejected by controller.pairingRejected.collectAsState()
    val retention by controller.retention.collectAsState()
    val hotEndpoints by controller.hotEndpoints.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 平板判定：可用宽度 ≥ 600dp 双栏（data-ff="tablet"）
    val isTablet = LocalConfiguration.current.screenWidthDp >= TabletMinWidthDp
    // 平板右栏详情目标：点选设备头即切换（[DeviceDetailPane] 渲染其端点）
    var detailDeviceValue by rememberSaveable { mutableStateOf<String?>(null) }
    // 平板进场默认选中首台命名设备（detailDeviceId 初值 = devices[0]）；
    // 只补空值，不覆盖用户已选目标
    LaunchedEffect(isTablet, devices) {
        if (isTablet && detailDeviceValue == null) {
            devices.firstOrNull { it.name.isNotEmpty() }?.let { device ->
                detailDeviceValue = device.id.value
            }
        }
    }
    // 设备重命名目标（设备卡头铅笔）：非空即弹重命名卡
    var renameDeviceValue by rememberSaveable { mutableStateOf<String?>(null) }
    val detailDevice = detailDeviceValue?.let { id -> devices.firstOrNull { it.id.value == id } }
    // VPN 活跃观测：仅在本面板生命周期内注册回调，离场即注销（回调本身轻量，
    // 但重复注册多次会让失效多路放大）
    val vpnWatcher = remember { com.dshbox.remote.net.LanVpnWatcher(context) }
    DisposableEffect(vpnWatcher) {
        onDispose { vpnWatcher.close() }
    }
    val vpnActive by vpnWatcher.vpnActive.collectAsState()
    // 提示判定收口在纯函数：VPN 活跃且存在私网地址端点才提示（提示不阻断
    // 任何操作，用户可继续尝试连接）
    val showLanVpnHint = com.dshbox.remote.net.LanVpnHint.shouldShow(
        vpnActive = vpnActive,
        targetUrls = devices.flatMap { device -> device.endpoints.map { it.targetUrl } },
    )

    var showManualDialog by rememberSaveable { mutableStateOf(false) }
    // 添加区「说明」面板：已适配插件、网页 Agent 与代理/直连的简单说明
    var showHelpDialog by rememberSaveable { mutableStateOf(false) }

    // 建档前确认卡（扫码与手动输入共用）：载荷解析成功后先展示预览，确认
    // 才建档/重配对，不静默建档。[scanDecisionGate] 非空表示决定通道挂在
    // 扫码回调上（动作由扫码链路执行）；null 表示来自手动输入（动作在卡内
    // 直接触发）。
    //
    // 门闩取用约定：决策时先取走引用再完成（complete 幂等失败会让二次决策
    // 静默失效）；扫码链路回头校验失败时也一并清空门闩——失败后「确认」重试
    // 改走 executePairing，「取消」与返回键（closeScan）真正收卡。UI 状态编
    // 排无法进 JVM 单测；控制器层的建档/重配链路（含代次与变更语义）由
    // RemotePairingKernelTest 锁定，确认卡的 INVALID/设备已删就地报错属 UI
    // 行为，无 JVM 断言。
    var pendingPairing by remember { mutableStateOf<PendingPairingRequest?>(null) }
    var scanDecisionGate by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }
    // 确认卡收集的逐端点落档选择（会话模式 + 保留档覆盖）：确认时一并
    // 提交建档，选择状态在卡关闭/取消时重置；无卡即无选择可透传
    var collectedChoices by remember { mutableStateOf<List<com.dshbox.remote.model.EndpointChoice>?>(null) }
    // 确认卡的归属选择（无分组默认）：与逐端点选择同生命周期（卡关即重置）
    var collectedTarget by remember { mutableStateOf<PairingTarget>(PairingTarget.Ungrouped) }
    // 确认卡建档/重配进行中：确认按钮禁用，防快速二次点按重复建档
    var pairingInFlight by remember { mutableStateOf(false) }

    // 扫码目标：新建设备 or 端点重新配对（扫码成功走对应建档/更新链路）；
    // 非空即「子页整屏提升」（宿主收起首页壳层与底部导航，返回由扫码页承接）。
    // 目标拆成可入 Bundle 的标量存档（配置变更/进程重建后子页不静默关闭）
    var scanOpen by rememberSaveable { mutableStateOf(false) }
    var repairDeviceValue by rememberSaveable { mutableStateOf<String?>(null) }
    var repairEndpointValue by rememberSaveable { mutableStateOf<String?>(null) }
    val scanTarget: ScanTarget? = when {
        !scanOpen -> null
        repairDeviceValue != null && repairEndpointValue != null ->
            ScanTarget.RePair(
                com.dshbox.remote.model.DeviceId(repairDeviceValue!!),
                com.dshbox.remote.model.EndpointId(repairEndpointValue!!),
            )
        else -> ScanTarget.NewDevice
    }

    fun openScan(target: ScanTarget) {
        when (target) {
            ScanTarget.NewDevice -> {
                scanOpen = true
                repairDeviceValue = null
                repairEndpointValue = null
            }
            is ScanTarget.RePair -> {
                scanOpen = true
                repairDeviceValue = target.deviceId.value
                repairEndpointValue = target.endpointId.value
            }
        }
    }

    fun closeScan() {
        scanOpen = false
        // 扫码页离场时一并收起确认卡与挂起中的决定通道（确认卡不得越过
        // 已关闭的扫码页独立存活）
        scanDecisionGate?.complete(false)
        scanDecisionGate = null
        pendingPairing = null
        confirmError = null
        pairingInFlight = false
        collectedChoices = null
        collectedTarget = PairingTarget.Ungrouped
    }

    // 确认卡内建档/重配对的执行（手动输入来源与扫码失败后的重试走此入口；
    // 扫码首次确认由扫码回调链路执行同一套动作）。校验失败仍在确认卡内就地
    // 显示；成功收口统一走扫码离场路径（手动来源 scanOpen 本就为 false，无
    // 额外影响）。
    fun executePairing(request: PendingPairingRequest, target: PairingTarget, choices: List<com.dshbox.remote.model.EndpointChoice>?) {
        pairingInFlight = true
        scope.launch {
            when (val scan = request.target) {
                ScanTarget.NewDevice -> controller.addFromPayload(request.raw, request.displayName, choices, target)
                    .onSuccess { closeScan() }
                    .onFailure { error ->
                        confirmError = context.getString(R.string.remote_add_failed, error.message ?: "")
                        pairingInFlight = false
                    }
                is ScanTarget.RePair -> when (
                    controller.updateEndpointTarget(scan.deviceId, scan.endpointId, request.raw)
                ) {
                    RemoteConnectionController.EndpointUpdateResult.UPDATED -> closeScan()
                    RemoteConnectionController.EndpointUpdateResult.UNCHANGED -> {
                        // 无变更本应成功收口；设备在确认期间被删除时重配链路
                        // 同样返回空变更，此处复查清单并在卡内报错（不静默成功）
                        if (controller.devices.value.none { it.id == scan.deviceId }) {
                            confirmError = context.getString(R.string.remote_edit_endpoint_invalid)
                            pairingInFlight = false
                        } else {
                            closeScan()
                        }
                    }
                    RemoteConnectionController.EndpointUpdateResult.INVALID -> {
                        confirmError = context.getString(R.string.remote_edit_endpoint_invalid)
                        pairingInFlight = false
                    }
                }
            }
        }
    }
    LaunchedEffect(scanTarget) {
        onSubPageVisibilityChanged(scanTarget != null)
    }

    // 面板主体（手机单栏 / 平板双栏）：本组合作用内直接渲染。
    // 左栏（平板）/ 单栏（手机）共用的清单与设置序列
    @Composable
    fun ListColumn() {
        AddSection(
            onScan = { openScan(ScanTarget.NewDevice) },
            onManual = { showManualDialog = true },
            onInfo = { showHelpDialog = true },
        )
        if (devices.isNotEmpty()) {
            // 清单组标题 + 组内 12dp 间距（.group-title 与 .device-list）
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_devices),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.remote_devices_group_title,
                            devices.count { it.name.isNotEmpty() },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.96.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 本机 DSH：设备清单首行（与设备行同构），整行点击 =
                    // 切回本机显示并接续唤起本机 DSH 页签
                    LocalEntryRow(
                        selected = activeDisplay is DisplayTarget.Local,
                        onSelect = {
                            controller.selectLocal()
                            onOpenLocalDsh()
                        },
                    )
                    // 命名分组的折叠卡（平板只留卡头、点选即选中；清单入账已按
                    // 「命名分组在前、无分组垫底」排序，此处按名称二次分流只为
                    // 渲染形态区分）
                    devices.filter { it.name.isNotEmpty() }.forEach { device ->
                        DeviceCard(
                            device = device,
                            activeDisplay = activeDisplay,
                            sessionStates = sessionStates,
                            controller = controller,
                            onRescanEndpoint = { endpointId ->
                                // 端点行内重扫：目标由端点行显式给出，扫码成功走重新
                                // 配对语义（代次自增、在用会话释放）
                                openScan(ScanTarget.RePair(device.id, endpointId))
                            },
                            headOnly = isTablet,
                            selected = isTablet && detailDeviceValue == device.id.value,
                            pairingRejected = pairingRejected,
                            // 平板：点选设备头即右栏出该设备端点详情
                            onSelectDevice = if (isTablet) {
                                { detailDeviceValue = device.id.value }
                            } else {
                                null
                            },
                            onRenameDevice = { renameDeviceValue = device.id.value },
                        )
                    }
                    // 无分组（名称为空串的档案条目）：所有命名分组之后恒展开，
                    // 不渲染设备头，仅端点行（.device-item--loose 形态）
                    devices.filter { it.name.isEmpty() }.forEach { device ->
                        UngroupedEndpointBlock(
                            device = device,
                            activeDisplay = activeDisplay,
                            sessionStates = sessionStates,
                            controller = controller,
                            defaultRetention = retention,
                            pairingRejected = pairingRejected,
                            onRescanEndpoint = { endpointId ->
                                openScan(ScanTarget.RePair(device.id, endpointId))
                            },
                        )
                    }
                }
            }
        } else {
            EmptyGuideCard(onScan = { openScan(ScanTarget.NewDevice) })
        }
        // 局域网可达性提示：不阻断（用户仍可尝试连接；连接层已无绕行绑定，
        // 私网地址在 VPN 下可能不可达）
        if (showLanVpnHint) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = stringResource(R.string.remote_lan_vpn_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
        SettingsSection(
            retention = retention,
            hotCount = hotEndpoints.size,
            hotCapacity = controller.hotPoolCapacity,
            onRetentionChange = controller::setRetentionDefault,
            onReleaseAll = { hotEndpoints.forEach(controller::releaseNow) },
        )
    }

    // 根容器用 Box 叠放：面板本体在下，扫码全屏子页（含确认卡）覆盖其上。
    // 不能用相邻兄弟节点——宿主在子页可见时收起首页壳层，扫码器必须真正
    // 覆盖面板而非与面板并排。
    Box(modifier = modifier.fillMaxSize()) {
        if (isTablet) {
            // 平板双栏：左 = 配对 + 清单 + 设置（顶部留与上方的间距），右 =
            // 选中设备的端点详情（detail-pane）
            // 平板两栏：外边距 24/18，两栏间距 20，左栏顶部 12
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ListColumn()
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    DeviceDetailPane(
                        device = detailDevice,
                        activeDisplay = activeDisplay,
                        sessionStates = sessionStates,
                        controller = controller,
                        defaultRetention = retention,
                        pairingRejected = pairingRejected,
                        onRescanEndpoint = { deviceId, endpointId ->
                            openScan(ScanTarget.RePair(deviceId, endpointId))
                        },
                        // 删除后回占位态：空串是「显式无选中」，不再自动选首台
                        onDeleted = { detailDeviceValue = "" },
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ListColumn()
            }
        }

        // 扫码器全屏子页：载荷先解析出预览弹确认卡，确认才走控制器既有建档/
        // 重配对链路；解析失败保持就地显示脱敏文案
        scanTarget?.let { target ->
            QrScanOverlay(
                // 确认卡展示期间暂停帧分析：同码反复命中会反复清空错误文案并把
                // 卡片弹回，卡内决策期取景固定不动
                analysisPaused = pendingPairing != null,
                onPayload = { raw ->
                    val preview = controller.previewPairing(raw).getOrElse { error ->
                        return@QrScanOverlay Result.failure(error)
                    }
                    // 弹确认卡并挂起等待用户决定；取消/确认经决定通道回流
                    confirmError = null
                    collectedChoices = null // 新载荷新选择：旧选择不跨载荷复用
                    collectedTarget = PairingTarget.Ungrouped // 归属同口径回默认
                    pendingPairing = PendingPairingRequest(raw, preview, target, displayName = null)
                    val gate = CompletableDeferred<Boolean>()
                    scanDecisionGate = gate
                    if (!gate.await()) {
                        // 取消：收起确认卡；以无文案失败返回（扫码页经重扫计数
                        // 恢复取景，不显示错误）
                        pendingPairing = null
                        pairingInFlight = false
                        Result.failure(IllegalStateException())
                    } else {
                        val failureMessage: String? = when (target) {
                            ScanTarget.NewDevice -> {
                                val choices = collectedChoices
                                controller.addFromPayload(raw, null, choices, collectedTarget).fold(
                                    onSuccess = { null },
                                    onFailure = { error ->
                                        context.getString(R.string.remote_add_failed, error.message ?: "")
                                    },
                                )
                            }
                            is ScanTarget.RePair -> when (
                                controller.updateEndpointTarget(target.deviceId, target.endpointId, raw)
                            ) {
                                // 更新成功为关页条件；无变化（同地址同凭据）同样收口，
                                // 但设备在确认期间被删除时（重配链路同样返回空变更）
                                // 复查清单并在卡内报错，不静默成功
                                RemoteConnectionController.EndpointUpdateResult.UPDATED -> null
                                RemoteConnectionController.EndpointUpdateResult.UNCHANGED ->
                                    if (controller.devices.value.none { it.id == target.deviceId }) {
                                        context.getString(R.string.remote_edit_endpoint_invalid)
                                    } else {
                                        null
                                    }
                                RemoteConnectionController.EndpointUpdateResult.INVALID ->
                                    context.getString(R.string.remote_edit_endpoint_invalid)
                            }
                        }
                        if (failureMessage == null) {
                            pendingPairing = null
                            Result.success(Unit)
                        } else {
                            // 回头校验失败仍在确认卡内就地显示；门闩一并取走——
                            // 「确认」重试改走 executePairing，「取消」可真正关闭
                            // 卡；无文案失败让扫码页恢复取景（不重复报错）
                            scanDecisionGate = null
                            confirmError = failureMessage
                            pairingInFlight = false
                            Result.failure(IllegalStateException())
                        }
                    }
                },
                onDismiss = { closeScan() },
            )
        }
    }

    if (showHelpDialog) {
        RemoteHelpDialog(onDismiss = { showHelpDialog = false })
    }

    // 重命名设备（rename-device 弹卡）：只改设备显示名，设备与端点
    // 的会话/凭据均不动
    renameDeviceValue?.let { targetId ->
        val target = devices.firstOrNull { it.id.value == targetId }
        if (target == null) {
            renameDeviceValue = null
        } else {
            var renameText by rememberSaveable(targetId) { mutableStateOf(target.name) }
            AlertDialog(
                onDismissRequest = { renameDeviceValue = null },
                title = {
                    // 弹卡标题：铅笔 20dp 与标题同行、间距 9dp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_pencil),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(stringResource(R.string.remote_device_rename))
                    }
                },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.remote_device_rename_label)) },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        // 空名保存为空操作（不改档、不把命名分组降级成
                        // 无分组），弹卡照常收起
                        val name = renameText.trim()
                        renameDeviceValue = null
                        if (name.isNotEmpty()) {
                            scope.launch { controller.renameDevice(target.id, name) }
                        }
                    }) {
                        Text(stringResource(R.string.remote_device_rename_save))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameDeviceValue = null }) {
                        Text(stringResource(R.string.remote_cancel))
                    }
                },
            )
        }
    }

    if (showManualDialog) {
        var manualError by remember { mutableStateOf<String?>(null) }
        ManualInputDialog(
            // 失败就地渲染：对话框保持打开，成功才关闭
            error = manualError,
            onDismiss = { showManualDialog = false },
            onConfirm = { address, token, note ->
                scope.launch {
                    // 整段载荷优先：JSON 描述符/自定义 scheme/竖线分隔文本原样
                    // 提交，仅 http(s) 地址无内嵌凭据时以 ?auth= 追加令牌栏
                    val raw = ManualPayloadSupport.compose(address, token)
                    controller.previewPairing(raw)
                        .onSuccess { preview ->
                            // 解析成功：关输入框弹确认卡（确认才建档，备注作
                            // 新建分组的设备名）；解析失败保持输入框就地报错
                            manualError = null
                            showManualDialog = false
                            collectedChoices = null // 新载荷新选择：旧选择不跨载荷复用
                            collectedTarget = PairingTarget.Ungrouped
                            pendingPairing = PendingPairingRequest(
                                raw = raw,
                                preview = preview,
                                target = ScanTarget.NewDevice,
                                displayName = note.ifBlank { null },
                            )
                        }
                        .onFailure { error -> manualError = error.message }
                }
            },
        )
    }

    // 建档前确认卡：预览展示（类型/设备归属/已剥凭据的地址/通道数），确认才
    // 建档或重配对；取消即收起且不产生任何档案变更。重配对来源以「修改既有
    // 端点」语义呈现：标题/按钮用重新配对文案，设备名取被修改设备的当前名
    // （非新载荷默认名），目标地址展示新载荷地址；手动输入的设备备注仅在
    // 用户未在确认卡命名「新建分组」时作默认名（确认卡命名的分组名优先）。
    pendingPairing?.let { request ->
        val isRepair = request.target is ScanTarget.RePair
        val repairDevice = (request.target as? ScanTarget.RePair)
            ?.let { t -> devices.firstOrNull { it.id == t.deviceId } }
        PairingConfirmCard(
            preview = when {
                repairDevice != null -> request.preview.copy(deviceName = repairDevice.name)
                request.displayName != null ->
                    request.preview.copy(deviceName = request.displayName)
                else -> request.preview
            },
            title = stringResource(
                if (isRepair) R.string.remote_device_repair else R.string.remote_confirm_add_title,
            ),
            confirmLabel = stringResource(
                if (isRepair) R.string.remote_device_repair else R.string.remote_manual_confirm,
            ),
            // 多端点载荷的通道数提示按来源成套：新建设备「将添加」，重配对「更新」
            endpointCountNote = if (isRepair) {
                stringResource(R.string.remote_confirm_endpoint_count_update, request.preview.endpointCount)
            } else {
                null
            },
            // 重配对不落选择（编辑对话框是重配对来源的唯一模式/保留档入口）：
            // 选择区整块隐藏，避免用户改了却被静默丢弃
            choicesEnabled = !isRepair,
            confirmEnabled = !pairingInFlight,
            error = confirmError,
            availableGroups = devices,
            selectedTarget = collectedTarget,
            onTargetChange = { collectedTarget = it },
            defaultRetention = retention,
            onConfirm = onConfirm@{ target, choices ->
                // 同帧双点防重入：in-flight 中的再次确认直接忽略（禁用态随
                // 重组生效前，同帧的第二次点按仍会进入本回调）
                if (pairingInFlight) return@onConfirm
                // 决策时先取走门闩引用再完成：扫码来源首次确认由挂起中的扫码
                // 回调执行；失败后的重试（门闩已清空）改走 executePairing。
                // in-flight 置位于决策分派之前：建档/重配完成前确认按钮禁用，
                // 防快速二次点按重复建档。归属与选择随决策一并记账：确认后
                // 一次性落档（新建设备链路透传给建档出口；重配对链路不落
                // 档——编辑对话框是重配对来源的唯一模式/保留档入口）。
                pairingInFlight = true
                collectedChoices = choices.takeIf { !isRepair }
                collectedTarget = target.takeIf { !isRepair } ?: PairingTarget.Ungrouped
                val gate = scanDecisionGate
                scanDecisionGate = null
                if (gate != null) {
                    gate.complete(true)
                } else {
                    executePairing(request, target.takeIf { !isRepair } ?: PairingTarget.Ungrouped, choices.takeIf { !isRepair })
                }
            },
            onDismiss = {
                val gate = scanDecisionGate
                scanDecisionGate = null
                if (gate != null) {
                    gate.complete(false)
                } else {
                    pendingPairing = null
                    confirmError = null
                    collectedChoices = null
                    collectedTarget = PairingTarget.Ungrouped
                }
            },
        )
    }
}

/** 扫码目标：新建设备或对既有端点重新配对（扫码成功后分流建档/更新链路）。 */
internal sealed interface ScanTarget {

    /** 扫码新建设备。 */
    data object NewDevice : ScanTarget

    /** 对既有端点重新扫码配对（等价手动重配对语义）。 */
    data class RePair(val deviceId: com.dshbox.remote.model.DeviceId, val endpointId: com.dshbox.remote.model.EndpointId) : ScanTarget
}

/**
 * 建档前确认卡的挂起请求：原始载荷 + 解析预览 + 目标链路。扫码来源的决定
 * 通道挂在扫码回调上（[RemotePanel] 内记账），手动输入来源在卡内直接执行。
 */
private data class PendingPairingRequest(
    val raw: String,
    val preview: RemoteConnectionController.PairingPreview,
    val target: ScanTarget,
    /** 手动输入来源的设备备注（扫码来源为 null，设备名用载荷默认名）。 */
    val displayName: String?,
)

/** 添加区：扫码与手动输入双入口。 */
@Composable
private fun AddSection(
    onScan: () -> Unit,
    onManual: () -> Unit,
    onInfo: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 配对区标题：组标题「添加设备」+ 右侧说明入口
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.remote_add_section_title),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.96.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clickable(role = Role.Button) { onInfo() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_info),
                        contentDescription = stringResource(R.string.remote_help_title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // 扫码为主按钮（实心强调），手动输入为描边次按钮
                Button(
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_qr_scan),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_add_scan))
                }
                OutlinedButton(
                    onClick = onManual,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_pencil),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_add_manual))
                }
            }
            // 配对说明行：12sp 第三级文字 + 信息图标
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_info),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.remote_add_note),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 手动输入对话框：地址（必填，支持整段载荷——含令牌/配对密钥的完整链接、
 * 自定义 scheme、JSON 配对描述符、竖线分隔文本）、令牌（可选，仅 http(s)
 * 地址且无内嵌凭据时以 ?auth= 追加）、设备备注（可选，作新建分组的设备名）。
 * 标题/说明/确认文案可由重新配对入口覆写；[error] 非空时就地渲染（对话框
 * 不关闭）。输入口径见 [ManualPayloadSupport]。
 */
@Composable
internal fun ManualInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (address: String, token: String, note: String) -> Unit,
    title: String? = null,
    description: String? = null,
    confirmLabel: String? = null,
    error: String? = null,
    showNote: Boolean = true,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title ?: stringResource(R.string.remote_manual_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(stringResource(R.string.remote_manual_address_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(stringResource(R.string.remote_manual_token_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 备注只对新建分组的命名有意义：重新配对入口不显示
                if (showNote) {
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.remote_manual_note_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
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
                // 门禁放宽为任意非空 scheme / JSON / 竖线分隔形态的载荷：
                // 自定义 scheme 与 JSON 描述符同样可经手动输入建档
                enabled = ManualPayloadSupport.isPlausiblePayload(address),
                onClick = { onConfirm(address.trim(), token.trim(), note.trim()) },
            ) {
                Text(confirmLabel ?: stringResource(R.string.remote_manual_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_cancel))
            }
        },
    )
}

/** 设置区：会话保留时长五档（切换即释放 / 1 / 5 / 15 分钟 / 永久），并显示页面热池保活数量与「全部释放」动作。 */
@Composable
private fun SettingsSection(
    retention: RetentionPolicy,
    hotCount: Int,
    hotCapacity: Int,
    onRetentionChange: (RetentionPolicy) -> Unit,
    onReleaseAll: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 设置区：组标题「设置」，卡体恒展开
            Text(
                text = stringResource(R.string.remote_settings_title),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.96.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 默认保留档 · 全局
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.remote_settings_default_retention),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.remote_settings_inactive_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                // 五档 chip 一行放不下（小屏/大字号）：横向滚动不换行不挤压
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PillChoiceChip(
                        label = stringResource(R.string.remote_retention_immediate),
                        selected = retention == RetentionPolicy.IMMEDIATE,
                        onClick = { onRetentionChange(RetentionPolicy.IMMEDIATE) },
                    )
                    listOf(1, 5, 15).forEach { minutes ->
                        PillChoiceChip(
                            label = stringResource(R.string.remote_retention_minutes, minutes),
                            selected = retention == RetentionPolicy.Retain(minutes),
                            onClick = { onRetentionChange(RetentionPolicy.Retain(minutes)) },
                        )
                    }
                    PillChoiceChip(
                        label = stringResource(R.string.remote_retention_forever),
                        selected = retention == RetentionPolicy.FOREVER,
                        onClick = { onRetentionChange(RetentionPolicy.FOREVER) },
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            // 热池占用：数量 / 容量 + 占用条 + 立即释放全部
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.remote_settings_hot_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.remote_settings_hot_usage,
                            hotCount,
                            hotCapacity,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 占用条（hot-bar：6dp 圆角槽 + 主色填充）
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(6.dp),
                            ),
                    ) {
                        val fraction = if (hotCapacity > 0) {
                            (hotCount.toFloat() / hotCapacity).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(6.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(6.dp),
                                ),
                        )
                    }
                    TextButton(onClick = onReleaseAll) {
                        Text(stringResource(R.string.remote_settings_release_all))
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.remote_settings_hot_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 「本机 DSH」清单首行：与设备卡头同构（34dp 图标底 + 名称 + 右侧选中圆
 * 圈，无状态点），整行点击 = 切回本机显示并接续唤起本机 DSH 页签
 * （localItemHTML / .device-head 的首行形态）。
 */
@Composable
private fun LocalEntryRow(
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onSelect,
                )
                .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 与设备卡头同构的图标底（.dh-icon：34dp 圆角方底 + 19dp 图标）
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
                text = stringResource(R.string.remote_local_entry_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/**
 * 无分组（名称为空串的档案条目）的端点块（.device-item--loose）：
 * 不渲染设备头、恒展开，只列端点行 ×N（无删除设备操作——空设备随最后一条
 * 端点移动/删除自动消失，无头可删）。端点条目复用设备卡的 [EndpointItem]。
 */
@Composable
private fun UngroupedEndpointBlock(
    device: Device,
    activeDisplay: DisplayTarget,
    sessionStates: Map<EndpointId, SessionState>,
    controller: RemoteConnectionController,
    defaultRetention: RetentionPolicy,
    pairingRejected: Set<EndpointId>,
    onRescanEndpoint: (EndpointId) -> Unit,
) {
    // 空无分组条目（端点移空瞬间的窗口）不渲染空卡
    if (device.endpoints.isEmpty()) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
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
        }
    }
}

/**
 * 平板右栏的端点详情面板（detail-pane）：选中设备的端点条目 ×N 与删
 * 除设备操作；未选中设备时以占位文案呈现。端点条目复用设备卡的 [EndpointItem]
 * （菜单/弹层编排同源），选中语义、保留档与模式切换与展开区一致。
 */
@Composable
private fun DeviceDetailPane(
    device: Device?,
    activeDisplay: DisplayTarget,
    sessionStates: Map<EndpointId, SessionState>,
    controller: RemoteConnectionController,
    defaultRetention: RetentionPolicy,
    pairingRejected: Set<EndpointId>,
    onRescanEndpoint: (com.dshbox.remote.model.DeviceId, EndpointId) -> Unit,
    onDeleted: () -> Unit = {},
) {
    var deleteConfirming by remember(device?.id?.value) { mutableStateOf(false) }
    // 端点详情面板：无卡片底，标题行 + 端点标签 + 端点行 + 删除设备
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (device == null) {
            Text(
                text = stringResource(R.string.remote_detail_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
            )
            return@Column
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = deviceDisplayName(device),
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = stringResource(
                        R.string.remote_detail_channel_count,
                        device.endpoints.size,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
        Text(
            text = stringResource(R.string.remote_detail_endpoint_label),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        // 端点列表容器：与手机设备卡体同构的抬升底，端点条（surface）在其上
        // 才有可见色块边界；右栏本身不再另铺卡片底
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                device.endpoints.forEach { endpoint ->
                    EndpointItem(
                        device = device,
                        endpoint = endpoint,
                        activeDisplay = activeDisplay,
                        sessionStates = sessionStates,
                        controller = controller,
                        defaultRetention = defaultRetention,
                        pairingRejected = endpoint.id in pairingRejected,
                        onRescan = { onRescanEndpoint(device.id, endpoint.id) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { deleteConfirming = true }) {
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
    if (device != null && deleteConfirming) {
        AlertDialog(
            onDismissRequest = { deleteConfirming = false },
            title = { Text(stringResource(R.string.remote_device_delete_title)) },
            text = { Text(stringResource(R.string.remote_device_delete_message, deviceDisplayName(device))) },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirming = false
                    controller.removeDevice(device.id)
                    onDeleted()
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
}

/** 空状态引导卡：清单为空时说明如何添加设备（添加入口在其上方）。 */
@Composable
private fun EmptyGuideCard(onScan: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 36.dp, bottom = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 空列表引导：56dp 圆角 16 的强调底 + 30dp 图标
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        RoundedCornerShape(16.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_devices),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.remote_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.remote_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp),
            )
            Spacer(modifier = Modifier.height(18.dp))
            // 空状态大扫码按钮：清单为空时的首要引导动作
            Button(onClick = onScan) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_qr_scan),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.remote_add_scan))
            }
        }
    }
}

/**
 * 添加区说明面板：只讲用户需要知道的事——已适配的 DSH 插件（可点开仓库）、
 * 网页版 Agent、远程连接其他 Agent、代理/直连怎么选。文案保持短句，不写
 * 实现细节；插件行的备注（未更新/推荐）随语言本地化。
 */
@Composable
private fun RemoteHelpDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val plugins = listOf(
        HelpPlugin(
            "dsh-pocket",
            "https://github.com/shaobeichen/dsh-pocket",
            R.string.remote_help_pocket_note,
        ),
        HelpPlugin(
            "dsh-bridge",
            "https://github.com/wenbin-wb/dsh-bridge",
            R.string.remote_help_bridge_note,
        ),
        HelpPlugin("dsh-mobile", "https://github.com/saya-ch/dsh-mobile", null),
        HelpPlugin(
            "ds-harness-remote",
            "https://github.com/liguobao/ds-harness-remote",
            null,
        ),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_help_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.remote_help_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.remote_help_pt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                plugins.forEach { plugin ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching { uriHandler.openUri(plugin.url) }
                            },
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = plugin.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            plugin.noteRes?.let { noteRes ->
                                Text(
                                    text = stringResource(noteRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = plugin.url,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.remote_help_pn_future),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.remote_help_pn_diy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.remote_help_pn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.remote_help_at),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.remote_help_ax),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.remote_help_ra),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.remote_help_rax),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.remote_help_mt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.remote_help_mp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.remote_help_md),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.remote_help_close))
            }
        },
    )
}

/** 说明面板里的插件行：名称、仓库地址与可选的本地化备注。 */
private data class HelpPlugin(
    val name: String,
    val url: String,
    val noteRes: Int?,
)
