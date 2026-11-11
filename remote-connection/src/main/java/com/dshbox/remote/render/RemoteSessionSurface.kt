package com.dshbox.remote.render

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.dshbox.app.common.R as CommonR
import com.dshbox.dsh.renderer.api.DshFileChooserRequest
import com.dshbox.dsh.renderer.api.DshSelectedFile
import com.dshbox.remote.R
import com.dshbox.remote.RemoteConnectionController
import com.dshbox.remote.parse.ChannelKind
import com.dshbox.remote.ui.channelLabelText
import com.dshbox.remote.ui.deviceDisplayName
import com.dshbox.remote.ui.parserLabelText


import com.dshbox.remote.model.Device
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.SessionState

/** 会话顶部微条高度（贴边通栏的常显操作条；底部另有 1px 分隔线）。 */
private val MicroBarHeight = 48.dp

/** 刷新按钮的加载环直径（加载中原位替换图标的小尺寸旋转环）。 */
private val RefreshIndicatorSize = 18.dp

/** 覆盖层状态圆点直径（与本机 DSH 等待状态同规格）。 */
private val StatusDotSize = 8.dp

/** 触发渲染器宿主重建的前序状态：失败/断连/释放后的重新建立均为新会话。 */
internal val RESTART_PRECEDING_STATES =
    setOf(SessionState.Failed, SessionState.Disconnected, SessionState.Released)

/**
 * 会话代次推进判定：从 [previous] 到 [current] 是否意味着新会话开启
 * （重试/重连/回场重建都会经过 Connecting）。同端点内 Ready→Connecting
 * 不存在（重建只经 Failed/Disconnected/Released）。
 */
internal fun isSessionRestart(previous: SessionState, current: SessionState): Boolean =
    current == SessionState.Connecting && previous in RESTART_PRECEDING_STATES

/**
 * 远程会话表面：DSH 页签显示槽在远程指向下的呈现层。
 *
 * 结构：顶部微条（贴边通栏：返回本机 / 打开远程面板 / 切换器 / 刷新 / 连接
 * 状态点，常显，底部 1px 分隔线）+
 * 渲染器视图；根容器铺页面背景色并整条消费微条区的指针事件——渲染器只占
 * 微条以下，本机页面在显示槽下层保活，微条区不得露出或穿透点击。
 * 连接态覆盖层对齐本机 DSH 的等待状态视觉——Connecting 居中加载指示、
 * Failed/Disconnected 呈现原因与重试/重连按钮；Ready/Loading 纯渲染，
 * Loading 额外以顶部进度条呈现 1..99。
 *
 * 微条语义：贴边通栏常显操作条——← 打开远程面板（系统返回同样直接回远程
 * 面板，DSH 无浏览器历史语义，页面级返回键处理已撤销）、⇄ 就地展开页面
 * 切换器（本机 +
 * 全部已建档端点，点击即切换显示源）、↻ 刷新（加载中原位转环并禁点）。
 * 渲染器宿主以「端点 + 会话代次」为键重建：端点切换或重连/
 * 重试/回场重建开启新会话时，旧宿主的视图与记账一并作废（重组行为属
 * Compose 运行时语义；代次判定逻辑有单测锁定）。
 *
 * 页签激活/失活不由组合生命周期承载（保活组合常驻），激活恢复回调经
 * [tabActive] 驱动；激活/失活的策略处置由宿主接入门面的页签上报完成。
 *
 * 页面热池：同一端点的表面在保活期（保留档非 IMMEDIATE 的失活会话）以
 * [visible] = false 驻留组合——零尺寸不放置不命中（微条与覆盖层一并隐去、
 * 不接管返回键），渲染器视图与引擎会话原样保活，切走再切回不重载页面；
 * 隐藏时释放输入焦点并收起软键盘、渲染器视图置 INVISIBLE 停绘制（与本机
 * 终端页同口径），系统级副作用（文件选择器/外链）一并门控；
 * [visible] = true 时行为与单一活跃呈现完全一致。
 *
 * @param visible 是否为当前呈现的表面：false 时零尺寸保活（热池驻留）。
 * @param tabActive 本表面呈现且 DSH 页签选中：返回接管开关 + 保活恢复的
 * 激活回调触发（不满足时不得劫持系统返回，也无需补激活）。
 * @param onOpenPanel 打开远程面板：宿主切回首页的远程页签（← 与系统返回共用）。
 */
@Composable
fun RemoteSessionSurface(
    controller: RemoteConnectionController,
    endpointId: EndpointId,
    visible: Boolean,
    modifier: Modifier = Modifier,
    tabActive: Boolean = true,
    onOpenPanel: () -> Unit,
) {
    val sessionStates by controller.sessionStates.collectAsState()
    // 加载进度仅可见时收集：隐藏表面（热池驻留）不因进度百分点变化触发全
    // 表面重组（不可见时按空表呈现，覆盖层本就不绘制）
    val progress = if (visible) {
        controller.rendererProgress.collectAsState().value
    } else {
        remember { emptyMap<EndpointId, Int>() }
    }
    val failureReasons by controller.sessionFailureReasons.collectAsState()
    val origins by controller.sessionOrigins.collectAsState()
    val upstreamOrigins by controller.sessionUpstreamOrigins.collectAsState()
    val pendingCredentials by controller.pendingCredential.collectAsState()
    val pairingRejectedEndpoints by controller.pairingRejected.collectAsState()
    val state = sessionStates[endpointId] ?: SessionState.Connecting
    val devices by controller.devices.collectAsState()
    val activeDisplay by controller.activeDisplay.collectAsState()
    // 下载结束事件：落盘成功或失败都拉起底部下载器（失败时由列表/权限引导说明）
    val downloadNotice by controller.downloadEvents.collectAsState()
    var downloadSheetOpen by remember { mutableStateOf(false) }
    // 每个表面只对“自己组合之后出现的新事件”弹窗：首次组合把当前 id 记为
    // 已处理，切换页面/展开面板时不会重放全局最后一条旧事件
    var handledDownloadId by remember { mutableStateOf(downloadNotice.id) }
    // 只对当前可见表面推进“已处理”：隐藏的热池表面不能吞掉全局事件，
    // 切回显示时补弹一次，避免下载通知永久丢失
    LaunchedEffect(downloadNotice.id, visible) {
        if (!visible) return@LaunchedEffect
        if (downloadNotice.id > handledDownloadId) {
            handledDownloadId = downloadNotice.id
            downloadSheetOpen = true
        }
    }
    LaunchedEffect(visible) {
        if (!visible) downloadSheetOpen = false
    }
    // ☰ 会话切换器的就地展开态：展开时列出本机与全部已建档端点，点击即切换
    var switcherOpen by remember { mutableStateOf(false) }
    // 平板（≥600dp）下的微条收起态：只隐去中间信息块与刷新按钮，保留
    // 切换器与收起按钮（.is-collapsed 规则），不改变页面加载；
    // 手机宽度恒展开。
    var microBarCollapsed by remember { mutableStateOf(false) }
    // 刷新加载环：点击刷新后亮起，页面加载完成（Ready）或失败/断连时熄灭；
    // 亮起期间刷新按钮禁用，防重复点击。
    val refreshing = state == SessionState.Loading ||
        (state != SessionState.Ready && state != SessionState.Idle && progress[endpointId] == null)
    val origin = origins[endpointId]
    val endpointCredentialPending = endpointId in pendingCredentials
    var host by remember { mutableStateOf<SessionRendererHost?>(null) }
    val canvasColor = MaterialTheme.colorScheme.background
    // 回车键「换行」开关的现值（宿主注入的设置读取口，拨动变化经重组到达）。
    // 推送走 AndroidView 的 update 块：设置变化 → 重组 → update 推新值，已建立
    // 的页面即刻生效、不重载。
    val enterKeyNewline = controller.enterKeyNewlineProvider()

    // 会话代次（按端点记账）：端点切换或新会话开启时归零/自增，渲染器宿主
    // 随「端点 + 代次」键重建（端点切换必须换节点：旧节点的视图与事件归属
    // 都属于原端点会话）。
    var rendererEpoch by remember(endpointId) { mutableIntStateOf(0) }
    var lastState by remember(endpointId) { mutableStateOf(state) }
    LaunchedEffect(endpointId, state) {
        if (isSessionRestart(lastState, state)) rendererEpoch += 1
        lastState = state
    }

    // 保活页签从零尺寸放置恢复时补一次激活回调（参照本机 DSH 链路的同款
    // 处理：部分设备上恢复放置不重发 attach，引擎会话停在停用态表现为空白页）。
    LaunchedEffect(tabActive) {
        if (tabActive) host?.onAttachedToWindowActive()
    }

    // 表面隐藏/恢复的焦点与绘制处置（与本机终端页同口径）：隐藏时释放输入
    // 焦点并收起软键盘、渲染器视图置 INVISIBLE 停绘制（引擎会话保活）；
    // 恢复时视图回 VISIBLE。release 路径不经此处置（宿主 release 自行收尾）。
    LaunchedEffect(visible) {
        val currentHost = host ?: return@LaunchedEffect
        currentHost.visible = visible
        if (visible) {
            currentHost.setRendererViewVisible(true)
        } else {
            currentHost.clearFocusAndHideKeyboard()
            currentHost.setRendererViewVisible(false)
        }
    }

    // 文件选择器：引擎请求 → 系统 OpenDocument（多选请求走多选契约）。结果
    // 按发起请求的请求 id 回填报给宿主，宿主做配对校验，晚到结果安全作废。
    // 启动失败（无文件管理器等）以取消结清请求，页面 input 不被永久挂起。
    var pendingFileRequest by remember(endpointId) { mutableStateOf<DshFileChooserRequest?>(null) }
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val request = pendingFileRequest
        pendingFileRequest = null
        if (request != null) {
            host?.completeFileChooser(request.requestId, uri?.let { listOf(DshSelectedFile(it)) })
        }
    }
    val openMultipleDocumentsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val request = pendingFileRequest
        pendingFileRequest = null
        if (request != null) {
            host?.completeFileChooser(request.requestId, uris.map { DshSelectedFile(it) })
        }
    }

    // 根容器为 Box：会话内容在下，切换器抽屉作为全屏遮罩叠在其上，
    // 不挤压渲染器高度。
    Box(
        modifier = modifier
            // 热池驻留：零尺寸不放置不命中，组合与渲染器视图原样保活
            .then(if (visible) Modifier else Modifier.keepAliveInvisible()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 根容器铺页面背景：微条区以下才是渲染器，本机页面在下层保活，
                // 不得从表面空白处露出（零尺寸保活时不参与绘制，无影响）
                .background(canvasColor),
        ) {
            if (visible) {
                SessionMicroBar(
                    state = state,
                    refreshing = refreshing,
                    endpointId = endpointId,
                    devices = devices,
                    collapsed = microBarCollapsed,
                    onOpenPanel = onOpenPanel,
                    onSwitcher = { switcherOpen = !switcherOpen },
                    onRefresh = { if (!refreshing) host?.refresh() },
                    onToggleCollapse = { microBarCollapsed = !microBarCollapsed },
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                if (origin != null) {
                    // 以「端点 + 会话代次 + 回环 origin」为键：端点切换、新会话
                    // 开启或会话重建换了端口（origin 变化）时重建渲染器宿主。
                    // 宿主在装配期固化 origin/上游快照，沿用旧节点会让页面继续
                    // 指向已停止的回环端口（WebSocket 重连失败、页面停留在空白态）。
                    key(endpointId, rendererEpoch, origin) {
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(0f),
                            factory = { context ->
                                // 宿主参数经唯一组装点生成（纯函数，JVM 契约测试锁定）
                                val hostParams = buildSessionHostParams(
                                    loopbackOrigin = origin,
                                    controllerInitialLoadUrl = controller.sessionInitialLoadUrl(endpointId),
                                    sessionUsesConsumableCredential =
                                        controller.sessionUsesConsumableCredential(endpointId),
                                    pairingCredentialPending = endpointCredentialPending,
                                )
                                SessionRendererHost(
                                    context = context,
                                    adapter = controller.rendererAdapter(),
                                    loopbackOrigin = origin,
                                    gateToken = controller.sessionGateToken(endpointId),
                                    initialLoadUrl = hostParams.initialLoadUrl,
                                    // 上游 origin 与首载地址同层取快照（页内链接分流判定用）
                                    upstreamOrigin = upstreamOrigins[endpointId],
                                    sessionUsesConsumableCredential = hostParams.sessionUsesConsumableCredential,
                                    // 直连会话：渲染器直接加载真实上游 origin（档案
                                    // 会话模式驱动；快照随会话代次重建生效）
                                    directMode = controller.sessionIsDirect(endpointId),
                                    initialLoadCarriesTicket = hostParams.initialLoadCarriesTicket,
                                    pairingCredentialPending = { endpointCredentialPending },
                                    sayaSessionExchanged = { controller.sayaSessionExchanged(endpointId) },
                                    // 直连模式：页面下载交给同一套下载目录/下载器
                                    onDownloadRequest = { request -> controller.downloadDirect(endpointId, request) },
                                    // 导航型下载：跨源 <a download> 被引擎当普通导航，
                                    // 只把已批准的文件 CDN 取消导航并转下载器
                                    downloadUrlPredicate = controller::isDownloadNavigationUrl,
                                    onPairingConsumed = { controller.markSessionCredentialConsumed(endpointId) },
                                    // 宿主附件来源面板：页面文件选择请求改走与本地 DSH
                                    // 页同一套七来源通道（宿主未提供时退回系统选择器）
                                    uploadPicker = controller.uploadPicker,
                                    // 回车键「换行」档：装配期读应用设置现值（两档都
                                    // 按设置传入，与本机 DSH 同开关）。
                                    enterKeyNewlineProvider = controller.enterKeyNewlineProvider,
                                    callbacks = object : SessionRendererCallbacks {
                                        override fun onProgress(percent: Int) {
                                            controller.notifyRendererProgress(endpointId, percent)
                                        }

                                        override fun onPageStarted() {
                                            controller.notifyRendererLoading(endpointId)
                                        }

                                        override fun onPageFinished() {
                                            controller.notifyRendererReady(endpointId)
                                        }

                                        override fun onLoadFailed(reason: String, pairingRejected: Boolean) {
                                            controller.notifyRendererFailed(endpointId, reason, pairingRejected)
                                        }

                                        override fun onRendererBroken(reason: String) {
                                            controller.notifyRendererFailed(endpointId, reason)
                                        }

                                        override fun onFileChooserRequested(request: DshFileChooserRequest): Boolean {
                                            pendingFileRequest = request
                                            val launcher = if (request.multiple) {
                                                openMultipleDocumentsLauncher
                                            } else {
                                                openDocumentLauncher
                                            }
                                            // capture 语义系统 OpenDocument 不承载，忽略；
                                            // MIME 过滤只保留合法的 type/subtype 项
                                            val mimeTypes = request.acceptMimeTypes
                                                .filter { it.contains('/') }
                                                .toTypedArray()
                                                .ifEmpty { arrayOf("*/*") }
                                            val launched = runCatching { launcher.launch(mimeTypes) }
                                            if (launched.isFailure) {
                                                host?.completeFileChooser(request.requestId, null)
                                            }
                                            return@onFileChooserRequested true
                                        }
                                    },
                                ).also { created ->
                                    host = created
                                    created.applyAppTheme(canvasColor.toArgb())
                                }
                            },
                            // 主题、凭据在档标志与表面可见性随重组下发（401/403
                            // 失败引导判定用；可见性同步覆盖宿主在隐藏期重建的情形）。
                            // 回车键「换行」开关同块推送：设置拨动的重组把新档推进
                            // 已建立的会话（页面可变全局翻转，即时生效不重载）。
                            update = {
                                it.visible = visible
                                it.setRendererViewVisible(visible)
                                it.pairingCredentialPending = { endpointCredentialPending }
                                it.applyAppTheme(canvasColor.toArgb())
                                it.setEnterKeyNewline(enterKeyNewline)
                            },
                        )
                        DisposableEffect(origin, rendererEpoch) {
                            onDispose {
                                host?.release()
                                host = null
                            }
                        }
                    }
                }
                if (visible) {
                    SessionOverlay(
                        state = state,
                        failureReason = failureReasons[endpointId],
                        pairingRejected = endpointId in pairingRejectedEndpoints,
                        progressPercent = progress[endpointId] ?: 0,
                        onRetry = { controller.retryEndpoint(endpointId) },
                    )
                }
            }
        }
        if (visible && switcherOpen) {
            SessionSwitcher(
                devices = devices,
                activeDisplay = activeDisplay,
                sessionStates = sessionStates,
                onSelectLocal = {
                    switcherOpen = false
                    controller.selectLocal()
                },
                onSelectEndpoint = { deviceId, endpointId ->
                    switcherOpen = false
                    controller.selectEndpoint(deviceId, endpointId)
                },
                onDismiss = { switcherOpen = false },
            )
        }
    }

    // 返回接管：远程会话内系统返回直接回首页远程面板。热池驻留的隐藏表面
    // 不接管（避免多个表面争抢返回键）。微条 ← 不变：始终只回面板。
    if (downloadSheetOpen) {
        RemoteDownloadSheet(
            controller = controller,
            notice = downloadNotice,
            onDismiss = { downloadSheetOpen = false },
        )
    }

    if (visible) {
        BackHandler(enabled = tabActive) {
            // 抽屉展开时返回先收抽屉，再按原语义回远程面板
            if (switcherOpen) switcherOpen = false else onOpenPanel()
        }
    }
}

/**
 * 零尺寸保活修饰符（本模块内实现，与 app 模块 keepAliveHidden 同款语义）：
 * 参与测量但不放置不绘制不命中，组合保留——切回时原样恢复。
 */
private fun Modifier.keepAliveInvisible(): Modifier = this.layout { measurable, constraints ->
    measurable.measure(constraints)
    layout(0, 0) { /* intentionally not placed */ }
}

/**
 * 会话顶部微条：贴边通栏，
 * 左 = 返回远程面板；中 = 状态点 + 设备名 · 通道 + 状态文案（占剩余宽度，
 * 左对齐）；右 = 刷新 / 切换器 / 收起（收起仅平板显示）。48dp 高、无圆角
 * 无阴影、底部 1px 分隔线；平板收起态（.is-collapsed）隐去中间信息块与
 * 刷新按钮，保留切换器与收起按钮。
 */
@Composable
private fun SessionMicroBar(
    state: SessionState,
    refreshing: Boolean,
    endpointId: EndpointId,
    devices: List<Device>,
    collapsed: Boolean,
    onOpenPanel: () -> Unit,
    onSwitcher: () -> Unit,
    onRefresh: () -> Unit,
    onToggleCollapse: () -> Unit,
) {
    // 中间名称块：本机 = 「本机 DSH」；端点 = 「设备名 · 通道」。
    // 数据取自控制器的既有设备账，不新增数据源。
    // 无分组（名称为空串）的设备回退「无分组」文案——不出现「 · LAN」式空名拼接。
    val nameText = devices.asSequence()
        .flatMap { device -> device.endpoints.asSequence().map { device to it } }
        .firstOrNull { it.second.id == endpointId }
        ?.let { (device, ep) ->
            (device.name.ifEmpty { stringResource(R.string.remote_group_none) }) +
                " · ${channelLabelText(ep.channelLabel)}"
        }
        ?: stringResource(R.string.remote_session_local_name)
    // 状态文案与 STATUS 表同语义：Ready 已连接 / Loading 连接中 /
    // Connecting 正在连接 / Failed·Disconnected 连接失败 / 其余未连接。
    val statusText = when (state) {
        SessionState.Ready -> stringResource(R.string.remote_session_status_ready)
        SessionState.Loading -> stringResource(R.string.remote_session_status_loading)
        SessionState.Connecting -> stringResource(R.string.remote_session_status_connecting)
        SessionState.Failed, SessionState.Disconnected ->
            stringResource(R.string.remote_session_status_failed)
        else -> stringResource(R.string.remote_session_status_connecting)
    }
    // 平板判定：可用宽度 ≥ 600dp 才显示收起按钮（data-ff="tablet"）。
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 贴边通栏：铺 surface 底、无圆角无阴影，沉浸页签下避让状态栏/刘海
            // （状态栏隐藏时 inset 为 0，不受影响）
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.surface)
            // 整条消费指针事件：空白处点击不得穿透到下层保活的本机页面
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MicroBarHeight)
                .padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左（mb-side）：返回远程面板
            MicroBarButton(
                icon = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.remote_session_open_panel),
                onClick = onOpenPanel,
            )
            if (!collapsed) {
                // 中：状态点 + 名称 · 通道 + 状态文案；整组居中，
                // 名称最多占屏宽 60%。
                val nameMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.6f).dp
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    Box(
                        modifier = Modifier
                            .size(StatusDotSize)
                            .background(statusColor(state), CircleShape),
                    )
                    Text(
                        text = nameText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = nameMaxWidth),
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            // 右（mb-side）：刷新 / 切换器 / 收起（收起仅平板）。收起态按
            // .is-collapsed 规则只隐去中间块与刷新，切换器与收起按钮保留
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                // 收起态也保留刷新（可用性优先于 .is-collapsed 的隐刷新）
                MicroBarButton(
                    icon = ImageVector.vectorResource(CommonR.drawable.ic_reload),
                    contentDescription = stringResource(R.string.remote_session_refresh),
                    enabled = !refreshing,
                    onClick = onRefresh,
                ) {
                    if (refreshing) {
                        // 加载环原位替换图标：灰色细环（非主色绿），页面加载完成即熄灭
                        CircularProgressIndicator(
                            modifier = Modifier.size(RefreshIndicatorSize),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                    } else {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_reload),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                MicroBarButton(
                    icon = ImageVector.vectorResource(CommonR.drawable.ic_replace),
                    contentDescription = stringResource(R.string.remote_session_switch),
                    onClick = onSwitcher,
                )
                if (isTablet) {
                    MicroBarButton(
                        icon = ImageVector.vectorResource(CommonR.drawable.ic_chevron_down),
                        contentDescription = stringResource(
                            if (collapsed) {
                                R.string.remote_session_expand
                            } else {
                                R.string.remote_session_collapse
                            },
                        ),
                        onClick = onToggleCollapse,
                    )
                }
            }
        }
        // 底部 1px 分隔线：与下方渲染器内容分界（border-bottom）
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = DividerDefaults.Thickness,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * 页面切换器抽屉（.sheet-wrap / .sheet）：全屏遮罩 + 底部（平板右侧）
 * 抽屉，列出本机 DSH 与全部端点的通道，点选即把显示源切到对应页面并收起；
 * 不离开 DSH 页签，切换期间不触发页签离场处置。
 */
@Composable
private fun SessionSwitcher(
    devices: List<Device>,
    activeDisplay: DisplayTarget,
    sessionStates: Map<EndpointId, SessionState>,
    onSelectLocal: () -> Unit,
    onSelectEndpoint: (com.dshbox.remote.model.DeviceId, EndpointId) -> Unit,
    onDismiss: () -> Unit,
) {
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    Box(modifier = Modifier.fillMaxSize().zIndex(40f)) {
        // 遮罩：点按空白处收起（无涟漪，避免全屏闪色）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Surface(
            // 平板右侧 360dp 全高，手机底部 78% 高（.sheet 规则）。
            // 面板自身消费指针事件：否则点到面板空白（标题 padding、把手、
            // 列表间隙）会穿透到同层遮罩的 clickable，误收抽屉。
            modifier = (if (isTablet) {
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(360.dp)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.78f)
            }).clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
            shape = if (isTablet) {
                RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
            } else {
                RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            },
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column {
                if (!isTablet) {
                    // 抽屉把手（.sheet__grip）
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp, bottom = 2.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(4.dp),
                            )
                            .align(Alignment.CenterHorizontally),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.remote_session_switch),
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clickable(role = Role.Button, onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
                            contentDescription = stringResource(R.string.remote_cancel),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                ) {
                    SwitcherGroupLabel(stringResource(R.string.remote_switch_local))
                    SwitcherRow(
                        icon = CommonR.drawable.ic_bolt,
                        title = stringResource(R.string.remote_switch_local),
                        tag = null,
                        subtitle = stringResource(R.string.remote_switch_local_sub),
                        statusColor = MaterialTheme.colorScheme.primary,
                        selected = activeDisplay is DisplayTarget.Local,
                        onClick = onSelectLocal,
                    )
                    devices.forEach { device ->
                        SwitcherGroupLabel(
                            text = deviceDisplayName(device) + " · " + stringResource(
                                R.string.remote_detail_channel_count,
                                device.endpoints.size,
                            ),
                        )
                        device.endpoints.forEach { endpoint ->
                            SwitcherRow(
                                icon = switcherIcon(endpoint),
                                title = channelLabelText(endpoint.channelLabel),
                                tag = parserLabelText(endpoint.parserId),
                                subtitle = endpoint.targetUrl,
                                statusColor = statusColor(sessionStates[endpoint.id] ?: SessionState.Idle),
                                selected = (activeDisplay as? DisplayTarget.Session)?.endpointId == endpoint.id,
                                onClick = { onSelectEndpoint(device.id, endpoint.id) },
                            )
                        }
                    }
                    if (devices.isEmpty()) {
                        Text(
                            text = stringResource(R.string.remote_switch_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 切换器分组标题（.sw-group-label：12sp 三级文字）。 */
@Composable
private fun SwitcherGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.48.sp,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
    )
}

/** 端点通道图标：局域网 wifi / 公网 world / 隧道 link / 直连 bolt。 */
private fun switcherIcon(endpoint: Endpoint): Int = when {
    endpoint.sessionMode == com.dshbox.remote.model.SessionMode.DIRECT.name ->
        CommonR.drawable.ic_bolt
    else -> when (
        runCatching { com.dshbox.remote.parse.ChannelKind.valueOf(endpoint.channelLabel) }.getOrNull()
    ) {
        com.dshbox.remote.parse.ChannelKind.LAN -> CommonR.drawable.ic_wifi
        com.dshbox.remote.parse.ChannelKind.TUNNEL -> CommonR.drawable.ic_link
        else -> CommonR.drawable.ic_world
    }
}

/**
 * 切换器行（.sw-item）：通道图标底 + 名称与插件标签 + 地址摘要 +
 * 连接状态点；当前项强调底、图标反色并追加对号。
 */
@Composable
private fun SwitcherRow(
    icon: Int,
    title: String,
    tag: String?,
    subtitle: String?,
    statusColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(icon),
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                )
                if (!tag.isNullOrEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(statusColor, CircleShape),
        )
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_check),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MicroBarButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    // 微条按钮：36dp 方正小圆角触控块 + 19dp 图标（非 M3 48dp IconButton）
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) {
            content()
        } else {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * 连接态覆盖层（对齐本机 DSH 等待状态视觉）：Connecting 加载指示与文案，
 * Failed/Disconnected 呈现原因与重试/重连按钮；Ready/Loading 纯渲染
 * （Loading 由顶部进度条呈现 1..99）。
 */
@Composable
private fun SessionOverlay(
    state: SessionState,
    failureReason: String?,
    pairingRejected: Boolean,
    progressPercent: Int,
    onRetry: () -> Unit,
) {
    if (state == SessionState.Loading && progressPercent in 1..99) {
        LinearProgressIndicator(
            progress = { progressPercent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f),
        )
    }
    when (state) {
        SessionState.Connecting, SessionState.Idle -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .zIndex(2f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.remote_session_connecting),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        SessionState.Failed -> {
            // 一次性凭据被拒的失败以专属标题呈现（布尔驱动，控制器记账）
            OverlayState(
                dotColor = MaterialTheme.colorScheme.error,
                title = if (pairingRejected) {
                    stringResource(R.string.remote_pairing_rejected_title)
                } else {
                    stringResource(R.string.remote_session_failed)
                },
                hint = failureReason,
                actionLabel = stringResource(R.string.remote_session_retry),
                onAction = onRetry,
            )
        }
        SessionState.Disconnected -> {
            OverlayState(
                dotColor = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.remote_session_disconnected),
                hint = failureReason,
                actionLabel = stringResource(R.string.remote_session_reconnect),
                onAction = onRetry,
            )
        }
        else -> Unit // Ready / Loading / Released / Terminated：纯渲染或无覆盖
    }
}

/** Failed/Disconnected 共用的居中状态层：状态点 + 标题 + 原因 + 主按钮。 */
@Composable
private fun OverlayState(
    dotColor: Color,
    title: String,
    hint: String?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .zIndex(2f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(StatusDotSize)
                    .background(dotColor, CircleShape),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

/** 连接状态点的颜色语义：就绪主色、过程轮廓色、异常错误色。 */
@Composable
private fun statusColor(state: SessionState): Color = when (state) {
    SessionState.Ready -> MaterialTheme.colorScheme.primary
    SessionState.Failed, SessionState.Disconnected, SessionState.Terminated ->
        MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}
