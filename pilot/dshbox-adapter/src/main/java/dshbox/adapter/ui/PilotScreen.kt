package dshbox.adapter.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import interlock.relay.core.runtime.monotonicNow
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.key
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import interlock.relay.core.runtime.RelayContainer
import dshbox.adapter.R
import interlock.relay.core.storage.ArtifactOut
import interlock.relay.core.runtime.RuntimePhase
import interlock.relay.core.protocol.CapabilityCategory
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.GrantGap
import interlock.relay.core.protocol.GuideClass
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.defaultMessageRes
import interlock.relay.core.protocol.SettingsTarget
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.protocol.SystemGrantState
import interlock.relay.core.protocol.TierCeiling
import interlock.relay.core.interlock.AccessibilityGap
import interlock.relay.core.interlock.InterlockChoice
import dshbox.adapter.surfaces.ApprovalCardView
import interlock.relay.core.interlock.ApprovalPrompt
import interlock.relay.core.interlock.RiskLevel
import interlock.relay.core.interlock.SettingsIntents
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.surface.SurfacePreference

/** 页面内导航用的目的地。外部路由不认识其中任何一个，拔除本模块不留残口。 */
sealed class PilotDestination {
    data object Overview : PilotDestination()
    data object Capabilities : PilotDestination()
    data object Surface : PilotDestination()
    data object Accessibility : PilotDestination()
    data object Privileged : PilotDestination()
    data object Permissions : PilotDestination()
    data object Overlay : PilotDestination()
    data object Logs : PilotDestination()
    data object Audit : PilotDestination()
    data object Storage : PilotDestination()
    data object Usage : PilotDestination()
    data class Detail(val id: CapabilityId) : PilotDestination()
}

/**
 * 手机助手主界面。页面间用栈式导航，内容不平铺。
 *
 * 状态与判定一律来自装配根；此处只做呈现与意图转发。
 */
@Composable
fun PilotScreen(container: RelayContainer, onBack: () -> Unit) {
    val viewModel: PilotViewModel = viewModel(factory = PilotViewModel.factory(container))
    // 用收集而不是额外的 StateFlow 绑定依赖：本模块不为一个语法糖再拉一个库。
    var state by remember { mutableStateOf(viewModel.uiState.value) }
    LaunchedEffect(viewModel) { viewModel.uiState.collect { state = it } }
    // 启动阶段单独收集：它是装配根状态机的直通流，与节拍刷新无关，
    // 不等下一次复查就把「正在启动/未完成」画出来。
    var runtimePhase by remember { mutableStateOf(viewModel.runtimePhase.value) }
    LaunchedEffect(viewModel) { viewModel.runtimePhase.collect { runtimePhase = it } }
    var stack by remember { mutableStateOf(listOf<PilotDestination>(PilotDestination.Overview)) }

    // 组合在屏期间才要节拍：系统权限、悬浮窗授权、通道存活与存储占用都能在应用外变化，
    // 且没有任何回调可挂。离开组合即停表，不在后台留一个没人看的轮询。
    DisposableEffect(viewModel) {
        viewModel.onScreenShown()
        onDispose { viewModel.onScreenHidden() }
    }

    // 屏幕采集的系统弹框不在这里发起：那一趟归 RelayConsentActivity，主页只负责展示。
    // 这里再放一个结果收集者，同一张弹框就会有两个发起方。
    // 系统返回键逐级弹栈；栈底才交给 Activity 退出。没有这一条时，
    // 系统返回会直接结束 Activity，从详情页按返回等于整个界面退出。
    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    PilotTheme {
        Scaffold(
            topBar = {
                PilotTopBar(
                    titleRes = destinationTitleRes(stack.last()),
                    onBack = { if (stack.size > 1) stack = stack.dropLast(1) else onBack() },
                )
            },
        ) { padding ->
            val pageScroll = rememberScrollState()
            // 每一页各自记住自己的滚动位置：进新页从顶部开始（它的最上面那截通常是标题与状态，
            // 被上一页的偏移顶出去就读不到了），从子页返回父页则回到离开时的位置
            // （在长列表里点进详情再返回，回到顶等于让用户重找一遍）。
            val page = stack.last()
            val scrollByPage = remember { mutableMapOf<PilotDestination, Int>() }
            // onDispose 的闭包捕获的是这一轮的 page：页面切换时它先执行，存下的就是离开那一页的位置。
            DisposableEffect(page) {
                onDispose { scrollByPage[page] = pageScroll.value }
            }
            LaunchedEffect(page) { pageScroll.scrollTo(scrollByPage[page] ?: 0) }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(pageScroll),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val open: (PilotDestination) -> Unit = { stack = stack + it }
                when (val page = stack.last()) {
                    PilotDestination.Overview -> OverviewPage(
                        state = state,
                        phase = runtimePhase,
                        degradedReason = viewModel.degradedReason(),
                        open = open,
                    )
                    PilotDestination.Capabilities -> CapabilitiesPage(
                        state = state,
                        onOpenDetail = { open(PilotDestination.Detail(it)) },
                        onSetTier = viewModel::setTier,
                    )

                    is PilotDestination.Detail -> DetailPage(
                        row = state.rows.firstOrNull { it.descriptor.id == page.id },
                        onSetTier = { viewModel.setTier(page.id, it) },
                    )

                    PilotDestination.Surface -> SurfacePage(
                        preference = state.surfacePreference,
                        onSet = viewModel::setSurfacePreference,
                        block = state.backgroundSurfaceBlock,
                        open = open,
                    )

                    PilotDestination.Accessibility -> AccessibilityPage(
                        on = state.accessibilityOn,
                        gap = state.accessibilityGap,
                        batteryIgnored = state.batteryIgnored,
                    )

                    PilotDestination.Privileged -> PrivilegedGuidePage(container = container, state = state)
                    PilotDestination.Overlay -> OverlayGuidePage(
                        container = container,
                        granted = state.overlayGranted,
                    )

                    PilotDestination.Permissions -> PermissionsGuidePage(
                        permissions = state.permissions,
                        requestAll = viewModel::requestablePermissions,
                        onChanged = viewModel::refresh,
                    )
                    PilotDestination.Logs -> LogsPage(
                        events = state.recentEvents,
                        verbose = state.verboseLog,
                        onSetVerbose = viewModel::setVerboseLog,
                    )

                    PilotDestination.Audit -> AuditPage(lines = state.recentAudits)
                    PilotDestination.Storage -> StoragePage(
                        snapshot = state.storage,
                        onClear = viewModel::clearArtifacts,
                    )

                    PilotDestination.Usage -> UsagePage(state.usageText)
                }
            }
        }

        state.prompt?.let { prompt ->
            // 编号与这张卡取自同一个状态快照：回填认的就是画出来的那一张
            val requestId = state.promptId
            ApprovalDialog(prompt = prompt, requestId = requestId) { viewModel.resolveApproval(it, requestId) }
        }

        // 调用被「缺无障碍」挡下时，信箱那头只拿到一条错误码，用户屏幕上没有任何反馈，
        // 这张框补的就是用户可见的那一份提示。
        state.accessibilityNotice?.let { notice ->
            AccessibilityNoticeDialog(notice = notice, onDismiss = viewModel::dismissAccessibilityNotice)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PilotTopBar(titleRes: Int, onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text(text = stringResource(titleRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        // 返回箭头在标题文字左边（导航位），每一页都有：站内有栈就逐级弹栈，
        // 站在栈底就是退出手机助手。少了栈底这一档，从宿主点进来后屏幕上
        // 没有任何一个可点的「回去」，只能靠系统手势。
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.pilot_action_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

private fun destinationTitleRes(destination: PilotDestination): Int = when (destination) {
    PilotDestination.Overview -> R.string.pilot_page_overview
    PilotDestination.Capabilities -> R.string.pilot_page_capabilities
    PilotDestination.Surface -> R.string.pilot_page_surface
    PilotDestination.Accessibility -> R.string.pilot_page_accessibility
    PilotDestination.Privileged -> R.string.pilot_page_privileged
    PilotDestination.Permissions -> R.string.pilot_page_permissions
    PilotDestination.Overlay -> R.string.pilot_page_overlay
    PilotDestination.Logs -> R.string.pilot_page_logs
    PilotDestination.Audit -> R.string.pilot_page_audit
    PilotDestination.Storage -> R.string.pilot_page_storage
    PilotDestination.Usage -> R.string.pilot_page_usage
    is PilotDestination.Detail -> R.string.pilot_page_capability_detail
}

@Composable
private fun OverviewPage(
    state: PilotUiState,
    phase: RuntimePhase,
    degradedReason: String?,
    open: (PilotDestination) -> Unit,
) {
    val context = LocalContext.current
    val usableCount = state.rows.count { it.enabled }
    // 停滞判据：运行中 + 收件箱确有积压 + 循环长时间没有推进。空转的空闲与合法长执行
    // （processing 区被一条 30 秒录屏占着）都不算停滞——只认「有件在等却没人取」这一形状，
    // 那正是执行器卡死的唯一现场。
    val executorStalled = state.channelRunning && state.workLoopLive == false && state.workQueueDepth > 0

    PilotSection(title = stringResource(R.string.pilot_section_status)) {
        PilotRow(
            title = stringResource(R.string.pilot_row_channel),
            value = stringResource(
                when {
                    !state.channelRunning -> R.string.pilot_channel_stopped
                    // 协程活着不等于在推进：实测事故里执行器停滞 40 分钟，界面却照常写着
                    // 运行中。停滞必须在这一行就说出来，而不是让用户对着一条不消费的通道猜。
                    executorStalled -> R.string.pilot_channel_stalled
                    else -> R.string.pilot_channel_running
                },
            ),
        )
        // 只看「运行中/已停止」分不出「正在起来」与「起失败了」：两者要做的下一步完全
        // 不同（等一等 vs 回来重试）。这两行只在对应阶段出现，跑起来后不留痕。
        if (phase == RuntimePhase.STARTING) {
            PilotDivider()
            PilotNote(text = stringResource(R.string.pilot_runtime_starting))
        }
        if (phase == RuntimePhase.DEGRADED) {
            PilotDivider()
            PilotNote(text = stringResource(R.string.pilot_runtime_degraded, degradedReason ?: "-"))
        }
        // 执行器停滞与「正在起来」同样是用户管不了的宿主侧故障：给出积压读数与
        // 「重启沙箱」这个唯一有效的下一步，不让人对着一条不消费的通道等回包。
        if (executorStalled) {
            PilotDivider()
            PilotNote(text = stringResource(R.string.pilot_channel_stalled_note, state.workQueueDepth))
        }
        PilotDivider()
        PilotRow(
            title = stringResource(R.string.pilot_row_usable_caps),
            value = stringResource(R.string.pilot_count_of_total, usableCount, state.rows.size),
        )
        PilotDivider()
        PilotRow(
            title = stringResource(R.string.pilot_row_surface),
            value = stringResource(surfacePreferenceLabel(state.surfacePreference)),
        )
        // 队列里有件在等时把排队情况摆在明面上：此刻屏上只有一处可点，但那之外还压着
        // 几件，从截图或旁观里是看不出来的。没有这一行，被挡在后面的那几件在他眼里
        // 等同于"没发生"。
        if (state.prompt != null) {
            PilotDivider()
            PilotRow(
                title = stringResource(R.string.pilot_row_approval_pending),
                value = if (state.approvalWaiting > 0) {
                    stringResource(R.string.pilot_approval_pending_queued, state.approvalWaiting)
                } else {
                    stringResource(R.string.pilot_approval_pending_now)
                },
            )
        }
    }

    // 闸门条目只留文字：状态由点进去的那一页说明，行首不再画圆点。
    PilotSection(title = stringResource(R.string.pilot_section_entries)) {
        PilotActionRow(
            title = stringResource(R.string.pilot_page_capabilities),
            onClick = { open(PilotDestination.Capabilities) },
        )
        PilotDivider()
        PilotActionRow(
            title = stringResource(R.string.pilot_page_surface),
            onClick = { open(PilotDestination.Surface) },
        )
        PilotDivider()
        PilotActionRow(
            title = stringResource(R.string.pilot_page_accessibility),
            onClick = { open(PilotDestination.Accessibility) },
        )
        PilotDivider()
        PilotActionRow(
            title = stringResource(R.string.pilot_page_privileged),
            onClick = { open(PilotDestination.Privileged) },
        )
        PilotDivider()
        PilotActionRow(
            title = stringResource(R.string.pilot_page_overlay),
            onClick = { open(PilotDestination.Overlay) },
        )
        PilotDivider()
        PilotActionRow(
            title = stringResource(R.string.pilot_page_permissions),
            onClick = { open(PilotDestination.Permissions) },
        )
    }

    PilotSection(title = stringResource(R.string.pilot_section_diagnostics)) {
        PilotActionRow(
            title = stringResource(R.string.pilot_page_logs),
            onClick = { open(PilotDestination.Logs) },
        )
        PilotDivider()
        PilotActionRow(
            title = stringResource(R.string.pilot_page_audit),
            onClick = { open(PilotDestination.Audit) },
        )
        PilotDivider()
        PilotActionRow(
            title = stringResource(R.string.pilot_page_storage),
            onClick = { open(PilotDestination.Storage) },
            // 概览这一行报的是占用页上各项之和（不是"清理能释放多少"：日志、授权记录与
            // 媒体库都不受「立即清理」管辖）。
            value = formatBytes(context, state.storage.totalBytes),
        )
        PilotDivider()
        PilotActionRow(
            title = stringResource(R.string.pilot_page_usage),
            onClick = { open(PilotDestination.Usage) },
        )
    }
}

/**
 * 「优先后台」没有生效时的那一段：一句此刻的实际通路，一句缺的那一环，
 * 再留一条走得通的修路入口。
 *
 * 每一环各配一行说法：把人送去装一个已经装好的东西、或让他在 Shizuku 里找一个不存在的
 * 授权，都比不说更糟。判据在装配根，这里只按档取词与取入口，不再比一次状态。
 * 入口的行名直接用目标页的标题——首页那批入口行本来就是这么写的。
 * Android 版本不够时既没东西可装也没屏可建，本机没有可修的路，那一行就不画：
 * 一个点了什么也不会改的入口，比没有入口更糟。
 */
@Composable
private fun BackgroundSurfaceFallback(
    block: RelayContainer.BackgroundSurfaceBlock,
    open: (PilotDestination) -> Unit,
) {
    PilotNote(text = stringResource(R.string.pilot_surface_bg_fallback_note))
    PilotNote(text = stringResource(backgroundBlockReasonRes(block)))
    // 只给"去另一页能把这一环修好"的入口。
    backgroundBlockRoute(block)?.let { destination ->
        PilotActionRow(
            title = stringResource(destinationTitleRes(destination)),
            onClick = { open(destination) },
        )
    }
}

/** 缺的那一环该怎么说。版本那一档复用响应里同一条错误码文案，两处口径一致。 */
private fun backgroundBlockReasonRes(block: RelayContainer.BackgroundSurfaceBlock): Int = when (block) {
    RelayContainer.BackgroundSurfaceBlock.ANDROID_VERSION -> R.string.pilot_err_surface_api_level
    RelayContainer.BackgroundSurfaceBlock.SHIZUKU_NOT_INSTALLED -> R.string.pilot_surface_bg_reason_shizuku_missing
    RelayContainer.BackgroundSurfaceBlock.SHIZUKU_NOT_RUNNING -> R.string.pilot_surface_bg_reason_shizuku_offline
    RelayContainer.BackgroundSurfaceBlock.SHIZUKU_NOT_GRANTED -> R.string.pilot_surface_bg_reason_not_granted
    RelayContainer.BackgroundSurfaceBlock.DISPLAY_NOT_CREATED -> R.string.pilot_surface_bg_reason_display_missing
}

/**
 * 该去哪一步修。Shizuku 那三环都落在特权模式页（安装、启动与授权在那一页）。
 *
 * 建屏那一档**不给跳转入口**：能做的只有"让助手去建"，而指向「助手引导」会和首页末尾
 * 那同一行入口在一屏上出现两次 —— 同名两行只会让人以为点错了地方。缺的那一环由上面
 * 那句话说清楚就够了。版本不够时本机没有可修的路，同样不给。
 */
private fun backgroundBlockRoute(block: RelayContainer.BackgroundSurfaceBlock): PilotDestination? = when (block) {
    RelayContainer.BackgroundSurfaceBlock.SHIZUKU_NOT_INSTALLED,
    RelayContainer.BackgroundSurfaceBlock.SHIZUKU_NOT_RUNNING,
    RelayContainer.BackgroundSurfaceBlock.SHIZUKU_NOT_GRANTED,
    -> PilotDestination.Privileged

    RelayContainer.BackgroundSurfaceBlock.DISPLAY_NOT_CREATED,
    RelayContainer.BackgroundSurfaceBlock.ANDROID_VERSION,
    -> null
}

@Composable
private fun CapabilitiesPage(
    state: PilotUiState,
    onOpenDetail: (CapabilityId) -> Unit,
    onSetTier: (CapabilityId, TierSelection) -> Unit,
) {
    CapabilityCategory.entries.forEach { category ->
        val rows = state.rows.filter { it.descriptor.id.category == category }
        if (rows.isEmpty()) return@forEach
        PilotGroupLabel(text = stringResource(categoryLabelRes(category)))
        // 一条能力一张卡：卡与卡之间靠留白分组，不再用分隔线把整类挤成一坨。
        rows.forEach { row ->
            PilotCard {
                CapabilityRowItem(row = row, onOpenDetail = onOpenDetail, onSetTier = onSetTier)
            }
        }
    }
}

@Composable
private fun CapabilityRowItem(
    row: RelayContainer.CapabilityRow,
    onOpenDetail: (CapabilityId) -> Unit,
    onSetTier: (CapabilityId, TierSelection) -> Unit,
) {
    // 圆点跟着标题那一行居中：它说的是这条能力当前的状态，挂在整行顶部会像是在指下面那段描述。
    val titleLineHeight = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 14.dp)) {
        Box(modifier = Modifier.height(titleLineHeight), contentAlignment = Alignment.Center) {
            PilotStatusDot(color = capabilityDotColor(row))
        }
        Spacer(modifier = Modifier.width(12.dp))
        // 标题与描述即点击区：不再另设「详情」二字，点这块文字就进详情页。
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { onOpenDetail(row.descriptor.id) },
        ) {
            Text(text = stringResource(row.descriptor.titleRes), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(row.descriptor.summaryRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 档位已经开了却还缺系统那一侧的东西：在条目下方另起一行红字说清缺哪一项。
            // 跟在标题后面排会因宽度不够被截成「·无障碍模式未…」，正好把要说的事说没了。
            row.gaps.firstOrNull()?.let { gap ->
                Text(
                    text = stringResource(
                        if (gap is GrantGap.RuntimePermission) R.string.pilot_cap_missing_perm
                        else R.string.pilot_cap_missing_service,
                        stringResource(gapLabelRes(gap)),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        TierPickerBox(tier = row.state.tier, onSet = { selection -> onSetTier(row.descriptor.id, selection) })
    }
}

/** 缺口该显示成什么名字：运行时权限用权限页那套标签，其余用它们各自页面的名字，不另造词汇。 */
private fun gapLabelRes(gap: GrantGap): Int = when (gap) {
    GrantGap.Accessibility -> R.string.pilot_page_accessibility
    GrantGap.NotificationListener -> R.string.pilot_perm_notification_listener
    GrantGap.Shizuku -> R.string.pilot_row_shizuku_service
    is GrantGap.RuntimePermission -> permissionLabelRes(gap.permission)
}

@Composable
private fun DetailPage(
    row: RelayContainer.CapabilityRow?,
    onSetTier: (TierSelection) -> Unit,
) {
    if (row == null) {
        PilotNote(text = stringResource(R.string.pilot_detail_missing))
        return
    }
    val descriptor = row.descriptor
    val context = LocalContext.current

    Column {
        Text(text = stringResource(descriptor.titleRes), style = MaterialTheme.typography.titleMedium)
        Text(
            text = descriptor.id.wire,
            style = MaterialTheme.typography.bodySmall,
            color = LocalPilotExtraColors.current.tertiaryText,
        )
    }

    // `sys.shell` 的动词清单：只读那一组随能力开合、不给逐条开关；会改系统那一组每条一个开关，
    // 默认关。形状那一行是字面命令写法，不进翻译——形状是最不会说错的那一句；
    // 需授权那几条另外配一句本地化说明，因为用户批的是"能不能动这个东西"，只看命令形状判不出来。
    if (descriptor.id.wire == "sys.shell") {
        val shellContainer = interlock.relay.core.runtime.RelayRuntime.get(context)
        val shellRevision = remember { androidx.compose.runtime.mutableStateOf(0) }
        val readOnly = remember(shellRevision.value) { shellContainer.shellReadOnlyVerbs() }
        val optIn = remember(shellRevision.value) { shellContainer.shellOptInVerbs() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.shapes.medium,
                ),
        ) {
            readOnly.forEachIndexed { index, state ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        text = state.shape,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        // 每条只读动词各给一句它自己干什么；共用一句"随 sys.shell 开合"会把
                        // 七条不同的读法说成同一件事。表外的动词退回那句总说。
                        text = stringResource(
                            when (state.verb) {
                                "dumpsys" -> R.string.pilot_shell_ro_dumpsys
                                "pm" -> R.string.pilot_shell_ro_pm
                                "settings" -> R.string.pilot_shell_ro_settings
                                "getprop" -> R.string.pilot_shell_ro_getprop
                                "appops" -> R.string.pilot_shell_ro_appops
                                "wm" -> R.string.pilot_shell_ro_wm
                                "screencap" -> R.string.pilot_shell_ro_screencap
                                else -> R.string.pilot_shell_readonly_row
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalPilotExtraColors.current.tertiaryText,
                    )
                }
            }
            optIn.forEachIndexed { index, state ->
                val descriptionRes = when (state.verb) {
                    "svc" -> R.string.pilot_shell_verb_svc
                    "media" -> R.string.pilot_shell_verb_media
                    "input" -> R.string.pilot_shell_verb_input
                    else -> null
                }
                if (index > 0 || readOnly.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.shape,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                if (state.supported) {
                                    descriptionRes ?: R.string.pilot_shell_readonly_row
                                } else {
                                    R.string.pilot_shell_verb_unsupported
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalPilotExtraColors.current.tertiaryText,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = state.enabled,
                        enabled = state.supported,
                        onCheckedChange = { open ->
                            shellContainer.setShellOptIn(state.verb, open)
                            shellRevision.value += 1
                        },
                    )
                }
            }
        }
    }

    PilotSection(title = stringResource(R.string.pilot_section_assistant_access)) {
        // 上限说明与档位框同一行：这条说明只有半句长，单独占一行时它上面那一截全是空白，
        // 而读的人要的是"这一档能到哪儿"跟选择框一起看到。
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PilotNote(
                text = stringResource(ceilingExplanationRes(descriptor.ceiling)),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            TierPickerBox(tier = row.state.tier, onSet = onSetTier)
        }
    }

    PilotSection(title = stringResource(R.string.pilot_section_system_access)) {
        PilotRow(
            title = stringResource(R.string.pilot_row_system_state),
            value = systemStateLabel(row.state.system),
        )
        // 剪贴板那一类没有系统弹框、也没有设置页可跳，"要什么条件"只能由这一页讲完：
        // 状态标签那一格放不下一整句，完整条件写在这条说明里。
        if (row.state.system == SystemGrantState.FOREGROUND_FOCUS) {
            PilotDivider()
            PilotNote(text = stringResource(R.string.pilot_note_foreground_focus))
        }
        // 未实现的能力不给跳转，也不给"到打开的页面里去授予"这句引导：
        // 用户照做之后开关仍不可用，等于让界面骗人去改一个改了也没用的系统设置。
        // 实现落地的同时这一条会自动恢复，不需要每个能力各自配一遍。
        if (descriptor.settingsTarget != SettingsTarget.NONE && row.implemented) {
            PilotDivider()
            PilotActionRow(
                title = stringResource(R.string.pilot_action_open_system_setting),
                onClick = {
                    SettingsIntents.build(descriptor.settingsTarget, context)?.let { context.startActivity(it) }
                },
            )
        }
        if (row.implemented) {
            PilotDivider()
            PilotNote(text = stringResource(guideStepsRes(descriptor)))
        }
    }

    PilotSection(title = stringResource(R.string.pilot_section_execution)) {
        PilotRow(
            title = stringResource(R.string.pilot_row_implemented),
            value = stringResource(
                if (row.implemented) R.string.pilot_state_ready else R.string.pilot_state_planned,
            ),
        )
        PilotDivider()
        PilotRow(
            title = stringResource(R.string.pilot_row_surfaces),
            value = surfacesLabel(row.descriptor.surfaces),
            valueBelow = true,
        )
        PilotDivider()
        PilotRow(
            title = stringResource(R.string.pilot_row_min_api),
            value = descriptor.minSdk.toString(),
        )
        row.surface?.let {
            PilotDivider()
            PilotRow(
                title = stringResource(R.string.pilot_row_chosen_surface),
                value = stringResource(surfaceKindLabel(it)),
            )
        }
        row.degradeReason?.let {
            PilotDivider()
            DegradationNote(it)
        }
    }
}

@Composable
private fun TierPickerBox(
    tier: AccessTier,
    onSet: (TierSelection) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = TierSelection.entries.first { it.tier == tier }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            ) {
                Text(
                    text = stringResource(tierSelectionLabel(current)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (expanded) "▴" else "▾",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 三档一律呈现、一律可选：上限由裁决层强制，界面不替用户预先藏掉一档。
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TierSelection.entries.forEach { selection ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(tierSelectionLabel(selection))) },
                    onClick = {
                        expanded = false
                        onSet(selection)
                    },
                )
            }
        }
    }
}

@Composable
private fun SurfacePage(
    preference: SurfacePreference,
    onSet: (SurfacePreference) -> Unit,
    block: RelayContainer.BackgroundSurfaceBlock?,
    open: (PilotDestination) -> Unit,
) {
    PilotSection(
        title = stringResource(R.string.pilot_section_surface_mode),
        summary = stringResource(R.string.pilot_surface_summary),
    ) {
        SurfacePreference.entries.forEachIndexed { index, value ->
            if (index > 0) PilotDivider()
            PilotSwitchRow(
                title = stringResource(surfacePreferenceLabel(value)),
                summary = stringResource(surfacePreferenceSummary(value)),
                checked = preference == value,
                onCheckedChange = { onSet(value) },
            )
        }
        // 这三段跟着这张卡：它们解释的就是上面这三个选择各会怎样，散在卡外面就成了
        // 三段不知道在说谁的说明。
        PilotDivider()
        PilotNote(text = stringResource(R.string.pilot_surface_degrade_note))
        // 「优先后台」没生效时缺的是哪一环，说在这一页而不是首页：这一页正是做这个选择的地方，
        // 人在这里时才用得上。判据仍由装配根给（[RelayContainer.backgroundSurfaceBlock]），
        // 这里只按档取词，不再自己比一遍状态。
        block?.let { BackgroundSurfaceFallback(block = it, open = open) }
    }
}

/**
 * 无障碍模式页：先给出本机当前的启用状态，再给去系统设置的路径，最后是后台保活一项。
 *
 * 状态读系统里的「已启用无障碍服务」名单，用户在系统页里打开后回到本页会自动复查；
 * 只有步骤文字和跳转按钮时，开没开要用户自己回来看。
 */
@Composable
private fun AccessibilityPage(on: Boolean, gap: AccessibilityGap?, batteryIgnored: Boolean) {
    PilotSection(title = stringResource(R.string.pilot_section_status)) {
        PilotRow(
            title = stringResource(R.string.pilot_page_accessibility),
            value = stringResource(if (on) R.string.pilot_state_on else R.string.pilot_state_off),
        )
        // 「已开启」在名单里还有本应用而服务没绑上时并不等于能用：权限没丢，
        // 进程却不在，`ui.*` 一样跑不动。少了这一行，用户读到「已开启」而助手什么也做不了。
        if (on && gap != null) {
            PilotDivider()
            PilotNote(text = stringResource(R.string.pilot_a11y_state_not_bound))
        }
    }
    SettingsGuidePage(
        stepsRes = R.string.pilot_guide_accessibility_steps,
        footnoteRes = R.string.pilot_guide_accessibility_limit,
        target = SettingsTarget.ACCESSIBILITY,
        actionRes = R.string.pilot_action_open_accessibility_list,
    )
    KeepAliveGuidePage(batteryIgnored = batteryIgnored)
}

/**
 * 后台保活这一档的入口，只列普通应用真能直达的三项。
 *
 * 厂商的自启动与后台运行开关没有公开 action，只有各家公开在用的组件名，且同一厂商不同
 * ROM 版本改过名。所以那一行给一组候选、点下去依次试，全试不通才退化成文字说明 ——
 * 直接断言"打不开"会让这一条在最需要它的厂商 ROM 上什么都不给。
 */
@Composable
private fun KeepAliveGuidePage(batteryIgnored: Boolean) {
    val context = LocalContext.current
    PilotSection(
        title = stringResource(R.string.pilot_section_keep_alive),
        summary = stringResource(R.string.pilot_keep_alive_summary),
    ) {
        KeepAliveRow(
            titleRes = R.string.pilot_action_ignore_battery_optimization,
            intent = SettingsIntents.build(SettingsTarget.BATTERY_EXEMPT, context),
            value = stringResource(
                if (batteryIgnored) R.string.pilot_state_exempt else R.string.pilot_state_not_exempt,
            ),
        )
        PilotDivider()
        KeepAliveRow(
            titleRes = R.string.pilot_action_battery_optimization_list,
            intent = SettingsIntents.batteryOptimizationList(),
        )
        PilotDivider()
        KeepAliveRow(
            titleRes = R.string.pilot_action_open_app_details,
            intent = SettingsIntents.build(SettingsTarget.APP_DETAILS, context),
        )
        PilotDivider()
        KeepAliveTryRow(
            titleRes = R.string.pilot_action_vendor_autostart,
            intents = SettingsIntents.vendorAutoStartCandidates(context),
        )
        PilotNote(text = stringResource(R.string.pilot_keep_alive_vendor_note))
    }
}

/**
 * 一行「去系统页面」的入口，只在真跳得过去时才是按钮：点下去抛异常就换成纯文字说明，
 * 不留一个按了没反应的行。
 *
 * 不用 `resolveActivity` 预先藏掉这一行：厂商 ROM 的应用可见性过滤会对系统设置页给出
 * 假阴性，那样会把本来能跳的入口整行删掉。
 */
@Composable
private fun KeepAliveRow(titleRes: Int, intent: Intent?, value: String? = null) {
    val context = LocalContext.current
    var unreachable by remember { mutableStateOf(false) }
    if (intent == null || unreachable) {
        PilotNote(text = stringResource(R.string.pilot_keep_alive_no_entry))
        return
    }
    PilotActionRow(
        title = stringResource(titleRes),
        value = value,
        onClick = { unreachable = !openSystemSettings(context, intent) },
    )
}

/** 起一个系统页面。返回 false 表示本机这条路走不通，调用方要退化成文字说明。 */
private fun openSystemSettings(context: Context, intent: Intent?): Boolean =
    intent != null && runCatching { context.startActivity(intent) }.isSuccess

/**
 * 一行「依次试候选」的入口：厂商权限管理页没有公开 action，只有各家公开在用的组件名，
 * 而同一厂商在不同 ROM 版本里改过名，所以给一组候选按顺序试到第一个真起得来的。
 *
 * 试完都不行才换成文字说明。同样不在渲染时用 `resolveActivity` 预先藏掉这一行：Android 11
 * 起的应用可见性过滤会让我们看不见那些系统应用，那样会把本来跳得过去的入口整行删掉。
 */
@Composable
private fun KeepAliveTryRow(titleRes: Int, intents: List<Intent>) {
    val context = LocalContext.current
    var unreachable by remember { mutableStateOf(false) }
    if (intents.isEmpty() || unreachable) {
        PilotNote(text = stringResource(R.string.pilot_keep_alive_no_entry))
        return
    }
    PilotActionRow(
        title = stringResource(titleRes),
        onClick = { unreachable = intents.none { openSystemSettings(context, it) } },
    )
}

/**
 * 助手要用需要无障碍的能力、而服务没绑上时的提示框。
 *
 * 两种缺口的话术必须分开：名单里已经没有本应用是权限被真的关掉，要回设置页重开；
 * 名单里还有本应用则只是进程被系统带走，重开应用即可，此时把人丢进无障碍列表
 * 只会让用户去动一个不用动的开关。
 *
 * 跳转只能落到无障碍功能列表：带包名直达那条 action 要 signature|installer 权限，
 * 三方应用发不出去。正因为落点是一张列表，「到了列表之后做什么」必须写在框里。
 */
@Composable
private fun AccessibilityNoticeDialog(
    notice: RelayContainer.AccessibilityNotice,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val revoked = notice.gap == AccessibilityGap.REVOKED
    val capabilityTitle = CapabilityRegistry.find(notice.capability)
        ?.let { stringResource(it.titleRes) }
        ?: stringResource(R.string.pilot_page_capabilities)
    val intent = SettingsIntents.build(SettingsTarget.ACCESSIBILITY, context)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.pilot_a11y_notice_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(
                        if (revoked) R.string.pilot_a11y_notice_revoked else R.string.pilot_a11y_notice_not_bound,
                        capabilityTitle,
                    ),
                )
                if (revoked) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.pilot_guide_accessibility_steps),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            // 只有真要回设置页时才给这一跳；另一种缺口动了设置也没用。
            if (revoked && intent != null) {
                TextButton(onClick = { if (openSystemSettings(context, intent)) onDismiss() }) {
                    Text(text = stringResource(R.string.pilot_action_open_accessibility_list))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.pilot_action_understood))
            }
        },
    )
}

@Composable
private fun SettingsGuidePage(
    stepsRes: Int,
    footnoteRes: Int,
    target: SettingsTarget,
    actionRes: Int,
) {
    val context = LocalContext.current
    val intent = SettingsIntents.build(target, context)
    PilotSection(title = stringResource(R.string.pilot_section_how_to_enable)) {
        PilotNote(text = stringResource(stepsRes))
        if (intent != null) {
            PilotActionRow(
                title = stringResource(actionRes),
                onClick = { context.startActivity(intent) },
            )
        }
        // 两条限定语跟着这张卡，不散在卡与卡之间：它们说的是同一步能做到哪、做不到哪，
        // 隔一张卡去读就断了，而夹在两段之间的那几行看上去像另一件没头没尾的事。
        if (intent == null || !SettingsIntents.deepLinksToApp(target)) {
            PilotNote(text = stringResource(R.string.pilot_guide_list_only))
        }
        PilotNote(text = stringResource(footnoteRes))
    }
}

@Composable
private fun PrivilegedGuidePage(container: RelayContainer, state: PilotUiState) {
    val context = LocalContext.current
    // 服务在跑与已授权是两条独立的事实：只读合成后的一个布尔，会让「在跑但没授权」
    // 显示成「未检测到」，而那一档恰恰是界面上唯一能给出下一步动作的场合。
    val running = state.shizuku.running
    val granted = state.shizuku.granted
    // 第三条事实：执行侧的用户服务有没有连上。真机实测出现过「服务在跑、已授权、
    // 页面显示一切正常」而所有 shell 调用全断的现场——running/granted 都回答不了它，
    // 必须单列一行。读的是后端此刻的绑定状态，与执行路径同一口径。
    val bound = container.shizukuBound()
    PilotSection(title = stringResource(R.string.pilot_section_status)) {
        PilotRow(
            title = stringResource(R.string.pilot_row_shizuku_service),
            value = stringResource(if (running) R.string.pilot_state_ready else R.string.pilot_state_absent),
        )
        PilotDivider()
        PilotRow(
            title = stringResource(R.string.pilot_row_shizuku_granted),
            value = stringResource(if (granted) R.string.pilot_state_granted else R.string.pilot_state_not_granted),
        )
        PilotDivider()
        PilotRow(
            title = stringResource(R.string.pilot_row_shizuku_bound),
            value = stringResource(
                if (bound) R.string.pilot_state_connected else R.string.pilot_state_disconnected,
            ),
        )
    }
    // 「在跑、已授权、用户服务没连上」是唯一会导致「界面说正常、调用全失败」的组合：
    // 必须给一句人话把下一步说清，而不是让用户对着两行绿去猜为什么能力全不可用。
    PilotSection(title = stringResource(R.string.pilot_section_how_to_enable)) {
        if (running && granted && !bound) {
            PilotNote(text = stringResource(R.string.pilot_guide_shizuku_unbound))
            PilotDivider()
        }
        PilotNote(text = stringResource(R.string.pilot_guide_shizuku_steps))
        // 一键跳到 Shizuku 本体：服务端要在那个应用里手动启停，缺一跳就得让用户
        // 自己回桌面找图标。未安装时这一行不出现，只留下方的下载页入口。
        if (state.shizukuInstalled) {
            PilotActionRow(
                title = stringResource(R.string.pilot_action_open_shizuku_app),
                onClick = {
                    container.shizukuLaunchIntent()?.let { context.startActivity(it) }
                },
            )
            PilotDivider()
        }
        PilotActionRow(
            title = stringResource(R.string.pilot_action_open_shizuku_releases),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SHIZUKU_RELEASE_URL)))
            },
        )
    }
    if (running && granted && !bound) {
        // 用户服务没绑上时给一个当场重试的入口：周期巡检每分钟会自己试一次，
        // 但用户看到「未连接」的那一刻最需要的就是立刻再试一下。
        Button(onClick = { container.reconnectShizuku() }) {
            Text(text = stringResource(R.string.pilot_action_shizuku_reconnect))
        }
    }
    if (running && !granted) {
        // 服务在跑但未获授权：这一档需要用户当场答应，界面因此提供主动请求的入口。
        Button(onClick = { container.requestShizukuAuthorization() }) {
            Text(text = stringResource(R.string.pilot_action_shizuku_request))
        }
    }
    if (!running) {
        PilotNote(text = stringResource(R.string.pilot_guide_shizuku_offline))
    }
}

/**
 * 悬浮确认窗的授权状态页。
 *
 * 三条通路的次序是「助手页在前台 -> 悬浮卡 -> 通知横幅」：没有这条授权时，若助手页不在
 * 前台，问题改挂到通知横幅上；助手页就在眼前时本来也不需要它。真正问不到人的是通知也被
 * 关掉的那一种组合——而助手干活时用户常看到的正是别的应用，所以这一页值得单独讲清楚。
 */
@Composable
private fun OverlayGuidePage(container: RelayContainer, granted: Boolean) {
    val context = LocalContext.current
    PilotSection(title = stringResource(R.string.pilot_section_status)) {
        PilotRow(
            title = stringResource(R.string.pilot_page_overlay),
            value = stringResource(if (granted) R.string.pilot_state_granted else R.string.pilot_state_not_granted),
        )
    }
    PilotSection(title = stringResource(R.string.pilot_section_how_to_enable)) {
        PilotNote(text = stringResource(R.string.pilot_overlay_note))
        PilotActionRow(
            title = stringResource(R.string.pilot_action_open_system_setting),
            onClick = { container.overlayPermissionIntent()?.let { context.startActivity(it) } },
        )
    }
}

@Composable
private fun PermissionsGuidePage(
    permissions: List<RelayContainer.PermissionState>,
    requestAll: () -> List<String>,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { onChanged() }
    // 单条开关的语义是「到系统里这一条权限的条目上改」：本应用不能撤销自己已拿到的
    // 运行时权限，所以不本地翻牌，一律回到快照按系统的实际授予情况复查。
    val openEntry = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { onChanged() }
    PilotSection(title = stringResource(R.string.pilot_section_runtime_permissions)) {
        permissions.forEachIndexed { index, entry ->
            if (index > 0) PilotDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(permissionLabelRes(entry.permission)),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (entry.granted) R.string.pilot_state_granted else R.string.pilot_state_not_granted,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp),
                )
                PilotSplitSwitch(on = entry.granted) {
                    openEntry.launch(SettingsIntents.permissionEntry(context))
                }
            }
        }
    }
    Button(onClick = { launcher.launch(requestAll().toTypedArray()) }) {
        Text(text = stringResource(R.string.pilot_action_request_permissions))
    }
    PilotNote(text = stringResource(R.string.pilot_permissions_special_note))
}

@Composable
private fun LogsPage(events: List<RunLogEntry>, verbose: Boolean, onSetVerbose: (Boolean) -> Unit) {
    PilotSection(title = stringResource(R.string.pilot_section_options)) {
        PilotSwitchRow(
            title = stringResource(R.string.pilot_verbose_title),
            summary = stringResource(R.string.pilot_verbose_summary),
            checked = verbose,
            onCheckedChange = onSetVerbose,
        )
    }
    // 说明写在「近期事件」标题下（标题与方框之间），不再放页面底部：
    // 它与这一节的内容直接相关，贴在标题下才读得出来"这是说这批记录的"。
    PilotSection(
        title = stringResource(R.string.pilot_section_recent_events),
        summary = stringResource(R.string.pilot_logs_location_note),
    ) {
        if (events.isEmpty()) {
            PilotNote(text = stringResource(R.string.pilot_logs_empty))
        }
        val stampFormat = remember {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        }
        events.forEach { entry ->
            // 每条记录前一行时间戳分隔：列表里过去只有相对顺序，看不出这条是什么时候发生的。
            // 字体与颜色沿用记录本身那一档，只多一行，不改既有的读法。
            Text(
                text = stringResource(R.string.pilot_log_time, stampFormat.format(java.util.Date(entry.atWallMs))),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                // 时间戳那一行恒为一行：等号已经收到六个，再大的字号也不该把它折成两行
                // （折行时那一行看着像两段无意义的符号，比省掉几个字符难读得多）。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp, bottom = 1.dp),
            )
            Text(
                text = stringResource(
                    R.string.pilot_log_line,
                    stringResource(levelLabelRes(entry.level)),
                    stringResource(subsystemLabelRes(entry.subsystem)),
                    entry.text,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                // 正文**不设行数上限**：这一页是排障读证据的地方，一条读数（带原因串、
                // 带字段的回包）被切到两行以内就只剩半句，设备字号偏大时两行还会各截两头，
                // 等于把唯一能读的东西切没了。长行完整换行显示，页面照常纵向滚动。
                lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.35f,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun AuditPage(lines: List<String>) {
    // 与运行日志同规：保留期写在「近期调用」标题下（标题与方框之间）。
    PilotSection(
        title = stringResource(R.string.pilot_section_recent_calls),
        summary = stringResource(R.string.pilot_audit_retention_note),
    ) {
        if (lines.isEmpty()) {
            PilotNote(text = stringResource(R.string.pilot_audit_empty))
        }
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                // 与运行日志同规：审计行也是排障读数，不设行数上限（理由见 LogsPage）。
                lineHeight = MaterialTheme.typography.bodySmall.fontSize * 1.35f,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
    PilotNote(text = stringResource(R.string.pilot_audit_note))
}

@Composable
private fun StoragePage(snapshot: RelayContainer.StorageSnapshot, onClear: () -> Long?) {
    val context = LocalContext.current
    // 上半区：手机操控产物——「立即清理」清的就是这一区。卡内按通路分三组，
    // 组内明细缩进一档，组与组之间用顶到卡片边界的粗线分开：
    //   交付副本（宿主侧配额里的那份）→ 交付DSH产物（交给用户消费的副本）→ 入站中转。
    PilotSection(title = stringResource(R.string.pilot_section_artifacts)) {
        PilotRow(
            title = stringResource(R.string.pilot_row_artifact_usage),
            value = stringResource(
                R.string.pilot_usage_of_quota,
                formatBytes(context, snapshot.usedBytes),
                formatBytes(context, snapshot.maxBytes),
            ),
        )
        PilotDivider()
        PilotSubRow(
            title = stringResource(R.string.pilot_row_artifact_count),
            value = stringResource(R.string.pilot_count_files, snapshot.artifacts),
        )
        PilotGroupDivider()
        // 交付目录（out/）：助手与用户消费的产物都在这儿，「立即清理」清的就是它。
        PilotRow(
            title = stringResource(R.string.pilot_row_out_usage),
            value = stringResource(
                R.string.pilot_usage_of_quota,
                formatBytes(context, snapshot.outBytes),
                formatBytes(context, ArtifactOut.MAX_TOTAL_BYTES),
            ),
        )
        // 四类各占一行：挤在一个 value 里会被单行省略截掉（真机核对时"其它文件 0…"正是如此），
        // 而这一页的读数就是给人看的，看不全等于没写。
        for ((labelRes, kind) in listOf(
            R.string.pilot_out_kind_shot to ArtifactOut.SHOT,
            R.string.pilot_out_kind_video to ArtifactOut.VIDEO,
            R.string.pilot_out_kind_audio to ArtifactOut.AUDIO,
            R.string.pilot_out_kind_file to ArtifactOut.FILE,
        )) {
            PilotDivider()
            PilotSubRow(
                title = stringResource(labelRes),
                value = formatBytes(context, snapshot.outByKindBytes[kind] ?: 0L),
            )
        }
        PilotDivider()
        PilotSubRow(
            title = stringResource(R.string.pilot_row_out_files),
            value = stringResource(R.string.pilot_count_files, snapshot.outFiles),
        )
        PilotGroupDivider()
        PilotRow(
            title = stringResource(R.string.pilot_row_uploads_usage),
            value = formatBytes(context, snapshot.uploadsBytes),
        )
    }
    var deferred by remember { mutableStateOf(false) }
    Button(
        onClick = { deferred = onClear() == null },
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) {
        Text(text = stringResource(R.string.pilot_action_clear_artifacts))
    }
    if (deferred) {
        PilotNote(text = stringResource(R.string.pilot_clear_deferred))
    }
    // 下半区：不在这里清除的读数，各自成节。记录与相册由各自的机制回收（轮转、保留期、
    // 系统媒体库），信箱与暂存随通道自行回收；把它们与可清的一区混在一张卡里，
    // 用户会以为「立即清理」也能清掉它们。
    PilotSection(title = stringResource(R.string.pilot_section_records)) {
        PilotRow(
            title = stringResource(R.string.pilot_page_logs),
            value = formatBytes(context, snapshot.runLogBytes),
        )
        PilotDivider()
        PilotRow(
            title = stringResource(R.string.pilot_page_audit),
            value = formatBytes(context, snapshot.auditBytes),
        )
    }
    PilotSection(title = stringResource(R.string.pilot_section_album)) {
        // 图片与视频进系统相册，音频进媒体库的音频集合（相册看不到）：分成两行报，
        // 用户才不会拿着"相册里没有这首歌"来对账。
        PilotRow(
            title = stringResource(R.string.pilot_row_media_usage),
            value = formatBytes(context, snapshot.galleryBytes),
        )
        PilotDivider()
        PilotRow(
            title = stringResource(R.string.pilot_row_media_audio),
            value = formatBytes(context, snapshot.audioBytes),
        )
    }
    PilotSection(title = stringResource(R.string.pilot_section_auto_recycle)) {
        PilotRow(
            title = stringResource(R.string.pilot_row_mailbox_usage),
            value = formatBytes(context, snapshot.mailboxBytes),
        )
    }
    PilotNote(text = stringResource(R.string.pilot_storage_note))
}

@Composable
private fun UsagePage(text: String?) {
    val context = LocalContext.current
    val command = stringResource(R.string.pilot_usage_command)
    var copied by remember { mutableStateOf(false) }

    // 两条接入方法各占一个方框，标签写在框外左上角。方法一里引导语、可复制的指令与复制键
    // 收在同一张框内：用户不必自己拼路径，也不必懂协议就能把助手接上。
    UsageMethod(label = stringResource(R.string.pilot_usage_method_one)) {
        Text(
            text = stringResource(R.string.pilot_usage_lead),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = command,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        TextButton(
            onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.pilot_page_usage), command))
                copied = true
            },
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(text = stringResource(if (copied) R.string.pilot_usage_copied else R.string.pilot_usage_copy))
        }
    }
    // 方法二：不碰命令行，从界面里把插件装上。开关在 Cordis 面板里，名字与面板上那一条一致。
    UsageMethod(label = stringResource(R.string.pilot_usage_method_two)) {
        Text(
            text = stringResource(R.string.pilot_usage_method_two_guide),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Text(
        text = text ?: stringResource(R.string.pilot_usage_missing),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

/**
 * 一张「标签在框外左上角 + 方框」的接入方法卡。
 *
 * 标签与方框必须在同一个子树里：页面按固定间距摊开每个直接子项，分成两个子项写
 * 就会把标签和它所属的框拉开成两段，看起来像两条互不相干的条目。
 */
@Composable
private fun UsageMethod(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ApprovalDialog(
    prompt: ApprovalPrompt,
    requestId: Int,
    onChoose: (InterlockChoice) -> Unit,
) {
    // 到点自己收：调用方可能早已回「还在等」走开，这张卡没人替它负责。
    // 过点之后它既不该继续挡界面，也不该被下一次点击当成一次答复。
    // 键要带上编号：只按截止时刻起效的话，两张截止时刻相同的卡会共用一条协程，
    // 到点自收就会拿着上一张的编号去回填 —— 那张卡已经没人认了，于是它永远留在屏上。
    LaunchedEffect(prompt, requestId) {
        // delay 走 uptimeMillis，而截止时刻是单调时钟：设备打盹时这一觉会睡过头几十秒，
        // 那张卡既答不了又还挡着页。醒来先对一遍表，没到点再睡剩下的量。
        while (true) {
            val remaining = prompt.deadlineAtMs - monotonicNow()
            if (remaining <= 0L) break
            delay(remaining)
        }
        onChoose(InterlockChoice.EXPIRED)
    }
    // 返回键按「拒绝」处理：这张卡没有整屏遮罩，页内按返回的语义仍是拒绝。
    BackHandler { onChoose(InterlockChoice.DENY) }
    // 挂的是悬浮窗那张卡本体而不是再画一遍：样式写两份会各自漂移，
    // 而两条通路问的是同一件事，用户看到的也必须是同一张卡。
    val context = LocalContext.current
    val night = (context.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    key(prompt, night) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val card = ApprovalCardView(ctx, prompt, night) { onChoose(it) }
                    card.start()
                    // 容器只给一块满幅区域，不补边距、也不画背景：贴顶与左右留白由卡片自己的
                    // LayoutParams 决定，整屏遮罩这里同样不存在。
                    android.widget.FrameLayout(ctx).apply {
                        addView(card.view)
                        tag = card
                    }
                },
                // 卡片里那两条无限重复的动画由主线程帧回调驱动，视图树离开组合不会自己停下；
                // 这里补一次叫停，和悬浮窗那条支路收在同一个出口。
                onRelease = { root -> (root.tag as? ApprovalCardView)?.stop() },
            )
        }
    }
}

/**
 * 运行日志的一行呈现。子系统与级别是本模块自己的枚举，必须本地化；
 * 正文保持机器可读的英文定长串，以便按原样 grep 与比对。
 */
data class RunLogEntry(
    val level: interlock.relay.core.log.LogLevel,
    val subsystem: LogSubsystem,
    val text: String,
    /** 这条发生时的墙上时钟：界面按它给每条记录打一行时间戳分隔。 */
    val atWallMs: Long,
)

private fun levelLabelRes(level: interlock.relay.core.log.LogLevel): Int = when (level) {
    interlock.relay.core.log.LogLevel.INFO -> R.string.pilot_level_info
    interlock.relay.core.log.LogLevel.WARN -> R.string.pilot_level_warn
    interlock.relay.core.log.LogLevel.ERROR -> R.string.pilot_level_error
}

private fun subsystemLabelRes(subsystem: LogSubsystem): Int = when (subsystem) {
    LogSubsystem.TRANSPORT -> R.string.pilot_subsystem_transport
    LogSubsystem.GATE -> R.string.pilot_subsystem_gate
    LogSubsystem.BACKEND -> R.string.pilot_subsystem_backend
    LogSubsystem.STORAGE -> R.string.pilot_subsystem_storage
    LogSubsystem.UI -> R.string.pilot_subsystem_ui
}

/** 降级原因是错误码，界面按同一张表取文案，不把机器码直接摊给用户。 */
@Composable
private fun DegradationNote(code: String) {
    val res = RelayError.fromCode(code)?.defaultMessageRes()
    PilotNote(text = if (res != null) stringResource(res) else code)
}

private fun tierSelectionLabel(selection: TierSelection): Int = when (selection) {
    TierSelection.DENIED -> R.string.pilot_tier_choice_denied
    TierSelection.ASK -> R.string.pilot_tier_choice_ask
    TierSelection.ALWAYS -> R.string.pilot_tier_choice_always
}

private fun categoryLabelRes(category: CapabilityCategory): Int = when (category) {
    CapabilityCategory.OBSERVE -> R.string.pilot_category_observe
    CapabilityCategory.CONTROL -> R.string.pilot_category_control
    CapabilityCategory.DATA -> R.string.pilot_category_data
    CapabilityCategory.SYSTEM -> R.string.pilot_category_system
    CapabilityCategory.DANGEROUS -> R.string.pilot_category_dangerous
}

private fun surfacePreferenceLabel(preference: SurfacePreference): Int = when (preference) {
    SurfacePreference.FOREGROUND -> R.string.pilot_surface_foreground
    SurfacePreference.BACKGROUND_PREFERRED -> R.string.pilot_surface_background
}

private fun surfacePreferenceSummary(preference: SurfacePreference): Int = when (preference) {
    SurfacePreference.FOREGROUND -> R.string.pilot_surface_foreground_summary
    SurfacePreference.BACKGROUND_PREFERRED -> R.string.pilot_surface_background_summary
}

private fun surfaceKindLabel(surface: SurfaceKind): Int = when (surface) {
    SurfaceKind.FOREGROUND -> R.string.pilot_surface_kind_foreground
    SurfaceKind.BACKEND_TRUSTED -> R.string.pilot_surface_kind_trusted
    SurfaceKind.OBSERVE_ONLY -> R.string.pilot_surface_kind_observe
}

/** 取词必须在组合上下文里逐个做，不能塞进 joinToString 的转换lambda。 */
@Composable
private fun surfacesLabel(surfaces: Set<SurfaceKind>): String {
    if (surfaces.isEmpty()) return stringResource(R.string.pilot_row_surface_na)
    val parts = ArrayList<String>(surfaces.size)
    for (surface in surfaces) parts += stringResource(surfaceKindLabel(surface))
    return parts.joinToString(" · ")
}

private fun ceilingExplanationRes(ceiling: TierCeiling): Int = when (ceiling) {
    TierCeiling.ANY -> R.string.pilot_ceiling_any
    TierCeiling.SESSION_ONLY -> R.string.pilot_ceiling_session
    TierCeiling.ASK_ONLY -> R.string.pilot_ceiling_ask_only
}

/** 引导文字取自注册表分类，界面不再自己判断「该给哪种引导」。 */
private fun guideStepsRes(descriptor: CapabilityDescriptor): Int = when (descriptor.guideClass) {
    GuideClass.DEEP_LINK_OK -> R.string.pilot_guide_deep_link
    GuideClass.LIST_ONLY -> R.string.pilot_guide_list_only
    GuideClass.NO_SETTINGS_PAGE -> R.string.pilot_guide_no_settings_page
    GuideClass.NOT_REQUIRED -> R.string.pilot_guide_not_required
}

private fun formatBytes(context: Context, bytes: Long): String =
    Formatter.formatShortFileSize(context, bytes)

/** 官方 release 页；版本相关的直链由页面自己保持最新，不在应用内写死版本号。 */
private const val SHIZUKU_RELEASE_URL = "https://github.com/RikkaApps/Shizuku/releases/latest"

private fun permissionLabelRes(permission: String): Int = when (permission) {
    // 读与写、图片/视频/音频各配独立标签：共用同一个标签会让权限列表出现同名条目，
    // 用户分不清自己关的是哪一条。
    Manifest.permission.READ_CONTACTS -> R.string.pilot_perm_contacts_read
    Manifest.permission.WRITE_CONTACTS -> R.string.pilot_perm_contacts_write
    Manifest.permission.READ_CALENDAR -> R.string.pilot_perm_calendar_read
    Manifest.permission.WRITE_CALENDAR -> R.string.pilot_perm_calendar_write
    Manifest.permission.ACCESS_FINE_LOCATION -> R.string.pilot_perm_location_fine
    Manifest.permission.ACCESS_COARSE_LOCATION -> R.string.pilot_perm_location_coarse
    Manifest.permission.RECORD_AUDIO -> R.string.pilot_perm_microphone
    Manifest.permission.POST_NOTIFICATIONS -> R.string.pilot_perm_notification
    Manifest.permission.READ_MEDIA_IMAGES -> R.string.pilot_perm_media_images
    Manifest.permission.READ_MEDIA_VIDEO -> R.string.pilot_perm_media_video
    Manifest.permission.READ_MEDIA_AUDIO -> R.string.pilot_perm_media_audio
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> R.string.pilot_perm_media_selected
    else -> R.string.pilot_perm_media
}
