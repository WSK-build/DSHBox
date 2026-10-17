package com.dshbox.app.ui

import android.app.Activity
import android.content.res.Configuration
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import com.dshbox.pluginmanager.core.PluginManagerComponents
import com.dshbox.pluginmanager.core.PluginManagerFactory
import com.dshbox.pluginmanager.market.ui.MarketScreen
import com.dshbox.pluginmanager.ui.LocalPluginPanelFullscreen
import com.dshbox.pluginmanager.ui.PluginManagerHost
import dshbox.adapter.dshboxRelayConfig
import dshbox.adapter.ui.PilotScreen
import interlock.relay.core.runtime.RelayRuntime
import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
// 底部导航由 Material3 的 NavigationBar/NavigationBarItem 换为自绘紧凑实现，
// 这两个导入随旧的 NavigationBar 代码块一起保留（回退时同步取消注释）。
// import androidx.compose.material3.NavigationBar
// import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dshbox.app.DshApp
import com.dshbox.app.browser.BrowserAppController
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.ui.files.InboundShareItem
import com.dshbox.app.ui.files.keepAliveHidden
import com.dshbox.app.ui.files.FilesScreen
import com.dshbox.app.ui.home.HomeScreen
import com.dshbox.app.ui.launch.LaunchScreen
import com.dshbox.app.ui.onboarding.OnboardingFlow
import com.dshbox.app.ui.onboarding.OnboardingGate
import com.dshbox.app.ui.onboarding.OnboardingPrefs
import com.dshbox.app.ui.settings.SettingsScreen
import com.dshbox.app.ui.terminal.TerminalScreen
import com.dshbox.app.ui.theme.AppThemeState
import com.dshbox.app.ui.theme.ThemeMode
// AppIcons 仅出现在上方的回退注释里，随之一并注释，
// 免得留下"未使用的导入"；回退时与注释块一起恢复。
// import com.dshbox.app.ui.theme.AppIcons
import com.dshbox.app.ui.webview.DshQuickAction
import com.dshbox.app.ui.webview.DshWebViewScreen
import com.dshbox.app.ui.webview.dshBrowserChooser
import com.dshbox.app.service.SandboxService
import com.dshbox.app.util.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import com.dshbox.app.common.R as CommonR

/**
 * 导航项定义。[contentIndex] 非 null 时与内容栈下标一一对应，点击写入 selectedTab；
 * null 表示「动作页签」：不承载内容、不写 selectedTab、永不滞留选中态，
 * 点击只触发自身动作（当前为全屏打开独立进程浏览器）。
 */
private data class TabSpec(val labelRes: Int, val icon: ImageVector, val contentIndex: Int? = null)

/**
 * 内容栈固定下标（TabContent 内五个常驻 composable 的位置）。
 * 导航项通过 [TabSpec.contentIndex] 引用这套编号，两套语义分离后：
 * Browser 动作页签不占内容索引，设置页仍可安全占用索引 4（仅由首页入口进入）。
 */
private const val HOME_CONTENT_INDEX = 0
private const val FILES_CONTENT_INDEX = 1
private const val DSH_CONTENT_INDEX = 2
private const val TERMINAL_CONTENT_INDEX = 3
private const val SETTINGS_CONTENT_INDEX = 4

/** Minimum time the brand launch animation stays visible on cold start. */
private const val SPLASH_MIN_MILLIS = 2_000L

/** 页签切换时新页的淡入时长（被切走的页由 keepAliveHidden 零尺寸化，不参与动画）。 */
private const val CONTENT_FADE_IN_MILLIS = 180

/**
 * DSH 根页面返回的落点：**进入 DSH 之前的页签**；记不到（DSH 自己 / 越界）时回首页。
 *
 * 独立的纯函数便于验证页签下标及越界回退。
 */
internal fun rootBackTabOf(previousTab: Int): Int =
    if (previousTab in 0..SETTINGS_CONTENT_INDEX && previousTab != DSH_CONTENT_INDEX) {
        previousTab
    } else {
        HOME_CONTENT_INDEX
    }

private const val TAG = "MainScreen"

@Composable
fun MainScreen(
    browserDshRouteRequest: Long = 0L,
    /** 外部应用分享/打开进来的待导入清单（非空即切到文件页，由其消费后置空）。 */
    inboundShareItems: List<InboundShareItem>? = null,
    onInboundShareConsumed: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as DshApp
    val sandboxManager = app.container.sandboxManager
    val browserAdapter = remember(app) { BrowserAppController(app) }
    DisposableEffect(browserAdapter) {
        browserAdapter.start()
        onDispose { browserAdapter.stop() }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // 进入当前页签之前所在的页签：DSH 根页面的返回键用它回到「进入 DSH 之前的页面」
    // （首页 / 文件 / 终端 / 设置），与浏览器根返回回到 App 是同一语义——此前 DSH 根部
    // 是把返回交还系统兜底，表现为任务退后台、独立任务的浏览器被顶到前台。
    var previousTab by rememberSaveable { mutableIntStateOf(0) }
    // 页签切换的唯一入口：所有写入都必须走它，previousTab 才不会漏记（散点赋值会漂移）。
    val selectTab: (Int) -> Unit = { next ->
        if (next != selectedTab) {
            previousTab = selectedTab
            selectedTab = next
        }
    }
    // Runtime truth starts fresh each process; remember() avoids restoring stale
    // READY from a previous process via rememberSaveable.
    var sandboxRunning by remember { mutableStateOf(false) }
    var sandboxError by remember { mutableStateOf(false) }
    var dshState by remember { mutableStateOf(DshState.UNINITIALIZED) }
    var dshUnresponsive by remember { mutableStateOf(false) }
    // 首页子页整屏提升：Pilot / Cordis 模块内子页打开时，首页壳层（品牌栏 + 页签行）
    // 与这里的底部导航同步收起，整屏让给模块子页（返回由子页顶栏箭头与系统返回键承接）。
    // 两个模块各记一份：Cordis 的 insets 反档只看它自己的子页，不被另一模块的
    // 上报串扰（只作整屏判定，不参与导航，故不会互相覆盖）。
    var pilotSubPageVisible by remember { mutableStateOf(false) }
    var cordisSubPageVisible by remember { mutableStateOf(false) }
    var showLaunch by remember { mutableStateOf(true) }
    var runtimeInstalled by remember { mutableStateOf(sandboxManager.isRuntimeInstalled()) }
    var nodeLayerInstalled by remember { mutableStateOf(sandboxManager.isNodeLayerInstalled()) }
    // DSH 启动 token：首页「浏览器」入口必须把它拼进 URL 才打得开——
    // 外部浏览器没有 WebView 的 cookie 交换与注入通道（用户实证：不带 token 会停在鉴权页）。
    var dshLaunchToken by remember { mutableStateOf<String?>(null) }
    val bundledRuntimeAvailable = remember {
        BundledRuntimeInstaller(app, app.container.sandboxConfig).hasBundledBundle()
    }

    // 启动静默检测：冷启动数秒后查询一次最新版本，有新版本则弹一次提示。
    var autoUpdate by remember { mutableStateOf<AppUpdater.CheckResult?>(null) }

    // Cordis 页签的数据装配：组件按 composition 生命周期创建一次，
    // 它的生命周期观察协程随 scope 走。Pilot 页签的通道容器由 PilotTabContent
    // 幂等装配（沙盒服务已启动过通道时沿用既有容器）。
    val pluginScope = rememberCoroutineScope()
    val pluginManager = remember {
        PluginManagerFactory.create(
            context = app,
            sandbox = sandboxManager,
            scope = pluginScope,
        )
    }

    // 首页「运行环境缺失」卡 → 在线获取直达（切到设置页，由其消费并打开覆盖页）。
    var pendingOnlineImport by remember { mutableStateOf(false) }

    LaunchedEffect(browserDshRouteRequest) {
        if (browserDshRouteRequest > 0L) {
            pendingOnlineImport = false
            selectTab(DSH_CONTENT_INDEX)
        }
    }

    // 外部分享/打开进来：先落到文件页，再由文件页弹目录选择器决定落点。
    LaunchedEffect(inboundShareItems) {
        if (inboundShareItems.isNullOrEmpty()) return@LaunchedEffect
        pendingOnlineImport = false
        selectTab(FILES_CONTENT_INDEX)
    }

    // 底部导航视觉调整：五个页签统一用 Tabler 描边风格图标
    // （res/drawable/ic_nav_home|folder|dsh|terminal|settings.xml），与页面整体线性视觉统一。
    // DSH 页签原先是 Material **填充**图标（ImageVector.vectorResource(CommonR.drawable.ic_world)），笔画密度与视觉重量都比
    // 描边图标重，夹在四个描边图标中间显得更粗更深（真机反馈"不协调"），现换成同源的 Tabler `world`。
    // 旧的 Material 图标写法保留在下（回退用）。
    // val tabs = listOf(
    //     TabSpec(R.string.tab_home, AppIcons.Home.imageVector),
    //     TabSpec(R.string.tab_files, AppIcons.Files.imageVector),
    //     TabSpec(R.string.tab_dsh, AppIcons.Dsh.imageVector),
    //     TabSpec(R.string.tab_terminal, AppIcons.Terminal.imageVector),
    //     TabSpec(R.string.tab_settings, AppIcons.Settings.imageVector),
    // )
    val tabs = listOf(
        TabSpec(R.string.tab_home, ImageVector.vectorResource(R.drawable.ic_nav_home), contentIndex = HOME_CONTENT_INDEX),
        TabSpec(R.string.tab_files, ImageVector.vectorResource(R.drawable.ic_nav_folder), contentIndex = FILES_CONTENT_INDEX),
        // DSH 页签：换成同源的 Tabler 描边地球（旧行见上方注释块，回退时替换回来即可）。
        TabSpec(R.string.tab_dsh, ImageVector.vectorResource(R.drawable.ic_nav_dsh), contentIndex = DSH_CONTENT_INDEX),
        TabSpec(R.string.tab_terminal, ImageVector.vectorResource(R.drawable.ic_nav_terminal), contentIndex = TERMINAL_CONTENT_INDEX),
        // Browser 是动作页签（contentIndex = null）：点击即全屏打开独立进程浏览器，
        // 不写 selectedTab、不滞留选中态；图标用同源 Tabler brand-safari（ic_browser_safari），
        // 标签 browser_entry_title 按语言本地化（不写死英文）。
        // 设置不再是导航项（入口迁至首页），原行注释保留作回退。
        // TabSpec(R.string.tab_settings, ImageVector.vectorResource(R.drawable.ic_nav_settings), contentIndex = SETTINGS_CONTENT_INDEX),
        TabSpec(R.string.browser_entry_title, ImageVector.vectorResource(R.drawable.ic_browser_safari)),
    )

    LaunchedEffect(sandboxManager) {
        sandboxManager.sandboxState.collectLatest { state ->
            sandboxRunning = state == SandboxState.RUNNING
            sandboxError = state == SandboxState.ERROR
            runtimeInstalled = sandboxManager.isRuntimeInstalled()
            nodeLayerInstalled = sandboxManager.isNodeLayerInstalled()
        }
    }

    LaunchedEffect(sandboxManager) {
        sandboxManager.dshState.collectLatest { state ->
            dshState = state
            // Dismiss the launch animation only when DSH settles into a
            // terminal state. STOPPED is NOT terminal here: on every cold
            // start the manager passes through STOPPED before starting.
            if (state == DshState.READY || state == DshState.ERROR) {
                showLaunch = false
            }
        }
    }

    LaunchedEffect(sandboxManager) {
        sandboxManager.dshLaunchToken.collectLatest { dshLaunchToken = it }
    }

    // 无响应提示（展示层）：健康循环只在连续不应答达阈值时置位，任一成功即复位。
    LaunchedEffect(sandboxManager) {
        sandboxManager.dshUnresponsive.collectLatest { dshUnresponsive = it }
    }

    // Brand splash minimum duration.
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MILLIS)
        showLaunch = false
    }

    // 新手引导判定：依据是运行环境**当前**是否真正就绪，而非一次性标志——
    // 用户跳过后只要未装完，下次启动仍会重新展示。runtimeReady 取装配级判定
    // （sandboxManager.isRuntimeInstalled()：base/node/android-side 逐层与
    // runtime-profile.json 校验，任一层损坏或 profile 缺失的半成品均判未安装），
    // nodeReady 取 sandboxManager.isNodeLayerInstalled()（node 层文件系统口径）。
    // 探测读文件系统，必须放到 IO 线程；初值取 false，首帧暂不展示引导（宁可短暂
    // 不弹，也不要误判为已装而永久不弹）。bundledRuntimeAvailable 为真时不展示：
    // 随包层由启动流程异步解包，首帧探测必然未就绪，据此展示会误入在线全量重装。
    var onboardingCompleted by remember { mutableStateOf(OnboardingPrefs.isCompleted(app)) }
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val (runtimeReady, nodeReady) = withContext(Dispatchers.IO) {
            sandboxManager.isRuntimeInstalled() to sandboxManager.isNodeLayerInstalled()
        }
        showOnboarding = OnboardingGate.shouldShow(
            runtimeReady = runtimeReady,
            nodeReady = nodeReady,
            bundledRuntimeAvailable = bundledRuntimeAvailable,
        )
    }

    // 启动静默更新检测：等首屏稳定后再发起，避免与冷启动抢资源；仅在有新版本时弹提示。
    // 引导展示期间不发起：引导是全屏流程，更新弹窗会与之叠加。
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MILLIS + 4_000)
        if (showOnboarding) return@LaunchedEffect
        val result = AppUpdater.checkLatest()
        if (result.isNewer && !AppUpdater.shouldSuppressAutoPrompt(app, result.latestTag)) {
            autoUpdate = result
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showOnboarding) {
            OnboardingFlow(
                sandboxManager = sandboxManager,
                importManager = app.container.onlineRuntimeImportManager,
                sandboxConfig = app.container.sandboxConfig,
                onFinish = {
                    // 任何出口（完成 / 跳过）立即写标志并收起引导。
                    OnboardingPrefs.markCompleted(app)
                    onboardingCompleted = true
                    showOnboarding = false
                },
            )
        } else {
            val splashVisible = showLaunch && dshState != DshState.READY
            val density = LocalDensity.current

            // Immersive tabs share one condition with three coordinated effects:
            // (1) contentWindowInsets is adjusted — the terminal with the keyboard up
            //     must not be inset by BOTH the navigation bar height (Scaffold
            //     innerPadding) AND the full IME height (TerminalScreen.imePadding()),
            //     which would leave a blank band between the extra-keys bar and the
            //     keyboard; the DSH tab keeps only the system navigation-bar inset so
            //     the web page's bottom input area is not covered by the gesture bar;
            // (2) the bottom navigation bar is hidden — the keyboard (terminal) or the
            //     full-page DSH web app owns that area;
            // (3) the status bar icons are hidden — content reaches the screen top,
            //     so the edge-to-edge transparent status bar would otherwise leave the
            //     system icons floating over the content.
            // 系统导航栏（手势条）两条路径都不隐藏：需求只覆盖 app 自绘底部导航与顶部状态栏。
            val imeVisible = WindowInsets.ime.getBottom(density) > 0
            val immersiveTerminal = imeVisible && selectedTab == TERMINAL_CONTENT_INDEX
            val immersiveDsh = selectedTab == DSH_CONTENT_INDEX
            val immersive = immersiveTerminal || immersiveDsh

            ImmersiveStatusBar(enabled = immersive)

            Scaffold(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (splashVisible) 0f else 1f),
                contentWindowInsets = when {
                    immersiveTerminal -> WindowInsets(0, 0, 0, 0)
                    // DSH 整页沉浸：只顶掉顶部状态栏留白，保留手势条留白；
                    // 有意舍弃 contentWindowInsets 里的 displayCutout 分量（与终端分支的
                    // 全清同一先例），横屏刘海两侧不再额外内缩。
                    immersiveDsh -> WindowInsets.navigationBars
                    // 首页三页签外壳：状态栏让位由壳层品牌顶栏与内嵌模块页各自的顶栏
                    // （Pilot / Cordis 自带 statusBars insets）处理，这里再叠一层会让
                    // 内嵌页顶栏上方出现双重空带。子页整屏提升（pilotSubPageVisible /
                    // cordisSubPageVisible）时壳层与底部导航都收起，该值仍为 0：整屏子页的
                    // insets 由模块页自让。
                    selectedTab == HOME_CONTENT_INDEX -> WindowInsets(0, 0, 0, 0)
                    else -> ScaffoldDefaults.contentWindowInsets
                },
                bottomBar = {
                    // 首页子页整屏提升时底部导航让位：子页自身顶栏提供返回，导航条不该
                    // 出现在整屏面板底下（其交互也被子页覆盖）。
                    if (!immersive && !pilotSubPageVisible && !cordisSubPageVisible) {
                        AppBottomNavigationBar(
                            tabs = tabs,
                            selectedTab = selectedTab,
                            onSelect = { tab -> tab.contentIndex?.let { selectTab(it) } ?: browserAdapter.open() },
                        )
                    }
                },
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    TabContent(
                        selectedTab = selectedTab,
                        sandboxRunning = sandboxRunning,
                        sandboxError = sandboxError,
                        dshState = dshState,
                        dshUnresponsive = dshUnresponsive,
                        runtimeInstalled = runtimeInstalled,
                        nodeLayerInstalled = nodeLayerInstalled,
                        bundledRuntimeAvailable = bundledRuntimeAvailable,
                        onNavigateToSettings = { selectTab(SETTINGS_CONTENT_INDEX) },
                        onBackToHome = { selectTab(HOME_CONTENT_INDEX) },
                        onGetRuntimeOnline = {
                            pendingOnlineImport = true
                            selectTab(SETTINGS_CONTENT_INDEX)
                        },
                        pilotContent = { isActive, onBackToControl, onSubPageVisible ->
                            PilotTabContent(
                                isActive = isActive,
                                onBack = onBackToControl,
                                onSubPageVisibilityChanged = { visible ->
                                    pilotSubPageVisible = visible
                                    onSubPageVisible(visible)
                                },
                            )
                        },
                        cordisContent = { _, onBackToControl, onSubPageVisible ->
                            CordisTabContent(
                                components = pluginManager,
                                scope = pluginScope,
                                onBack = onBackToControl,
                                fullscreen = cordisSubPageVisible,
                                onSubPageVisibilityChanged = { visible ->
                                    cordisSubPageVisible = visible
                                    onSubPageVisible(visible)
                                },
                            )
                        },
                        // DSH 快捷条路由：MainScreen 是唯一知道内容栈下标的地方；
                        // DSH 页只报语义动作（DshQuickAction），不感知页签编号。
                        onQuickAction = { action ->
                            when (action) {
                                // 刷新在 DSH 页内闭环（refreshAction），不经宿主路由；分支仅为穷尽。
                                DshQuickAction.REFRESH -> Unit
                                DshQuickAction.HOME -> selectTab(HOME_CONTENT_INDEX)
                                DshQuickAction.FILES -> selectTab(FILES_CONTENT_INDEX)
                                DshQuickAction.TERMINAL -> selectTab(TERMINAL_CONTENT_INDEX)
                                DshQuickAction.BROWSER -> browserAdapter.open()
                            }
                        },
                        // DSH 根页面返回：回到进入 DSH 之前的页签（记不到就回首页）。
                        onDshRootBack = { selectTab(rootBackTabOf(previousTab)) },
                        dshLaunchToken = dshLaunchToken,
                        // 监控卡按需读取沙箱树的 CPU 时间（只读 /proc，未运行时返回 null）。
                        readSandboxCpuMillis = { sandboxManager.sandboxCpuMillis() },
                        pendingOnlineImport = pendingOnlineImport,
                        onPendingOnlineImportConsumed = { pendingOnlineImport = false },
                        inboundShareItems = inboundShareItems,
                        onInboundShareConsumed = onInboundShareConsumed,
                    )
                }
            }

            if (showLaunch && dshState != DshState.READY) {
                LaunchScreen()
            }

            autoUpdate?.let { result ->
                val dialogContext = LocalContext.current
                AlertDialog(
                    onDismissRequest = { autoUpdate = null },
                    title = { Text(stringResource(R.string.settings_check_update)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.settings_update_available_msg,
                                result.latestTag.orEmpty(),
                                result.currentVersion,
                            ),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            autoUpdate = null
                            AppUpdater.openSite(dialogContext)
                        }) {
                            Text(stringResource(R.string.settings_update_open_site))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            result.latestTag?.let { AppUpdater.ignoreVersion(dialogContext, it) }
                            autoUpdate = null
                        }) {
                            Text(stringResource(R.string.settings_update_ignore))
                        }
                    },
                )
            }
        }
    }
}
/**
 * Pilot 页签内容：内嵌适配层助手页 [PilotScreen]。
 *
 * 通道容器装配与助手页同一条路径（start 幂等）：沙盒服务已启动过通道时沿用既有容器，
 * 否则在此按宿主配置装配。前台呈现语义与助手页一致——页签在场时审批由页内卡接管，
 * 离场后同一待决项换通知栏呈现。
 */
@Composable
private fun PilotTabContent(
    isActive: Boolean,
    onBack: () -> Unit,
    /** 子页（模块内栈深 > 1）可见性上报：宿主据此收起壳层与底部导航。 */
    onSubPageVisibilityChanged: (Boolean) -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as DshApp
    val container = remember {
        RelayRuntime.start(app, dshboxRelayConfig(app))
        RelayRuntime.get(app)
    }
    // App 前后台：前台呈现随宿主 Activity 生命周期启停——退后台时待决审批换通知栏
    // 呈现、节拍轮询转 idle，与助手页独立打开时的语义一致（对照 PilotActivity 的
    // onStart/onStop）。仅跟页签激活态会让后台持续轮询、审批通路停留在页内。
    var appStarted by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appStarted = true
                Lifecycle.Event.ON_STOP -> appStarted = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(container, isActive, appStarted) {
        container.foreground.visible = isActive && appStarted
        container.onForegroundChanged()
    }
    DisposableEffect(container) {
        onDispose {
            container.foreground.visible = false
            container.onForegroundChanged()
        }
    }
    // 内嵌页自带的返回接管只在内部栈非底时启用；栈底时系统返回由本宿主接手，
    // 回到控制页签——否则返回键穿透到 Activity 默认行为（退出应用）。后注册者
    // 优先：PilotScreen 的栈弹出处理器注册在其后，启用时自然胜出。
    BackHandler(enabled = isActive) { onBack() }
    // App 内切换深浅走 AppCompat 资源内更新，不经视图层配置派发；Pilot 主题读
    // isSystemInDarkTheme()（基于 LocalConfiguration），不覆写时切主题要重启才跟随。
    // 按宿主主题状态覆写夜模式位的 Configuration 副本，切换当帧即生效（SYSTEM 档
    // 保持系统现值）；PilotActivity 独立入口不经过此路径，语义不变。
    val currentConfiguration = LocalConfiguration.current
    val themeConfiguration = remember(currentConfiguration, AppThemeState.mode) {
        Configuration(currentConfiguration).apply {
            when (AppThemeState.mode) {
                ThemeMode.LIGHT ->
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        Configuration.UI_MODE_NIGHT_NO
                ThemeMode.DARK ->
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        Configuration.UI_MODE_NIGHT_YES
                ThemeMode.SYSTEM -> Unit
            }
        }
    }
    CompositionLocalProvider(LocalConfiguration provides themeConfiguration) {
        // 子页整屏提升：模块内栈深 > 1 时壳层已收起，内嵌区域从「壳内」切回
        // 「独占整屏」的 insets 语义——子页顶栏自让状态栏、内容区自让手势条。
        var subPageVisible by remember { mutableStateOf(false) }
        PilotScreen(
            container = container,
            onBack = onBack,
            embedded = !subPageVisible,
            onSubPageVisibilityChanged = { visible ->
                subPageVisible = visible
                onSubPageVisibilityChanged(visible)
            },
        )
    }
}

/**
 * Cordis 页签内容：内嵌插件管理面板 [PluginManagerHost]。
 *
 * 面板内部自带页面栈与系统返回接管（页签侧经外壳的返回门控按激活态开关），
 * 面板退到首页时经 [onBack] 回到控制页签。
 */
@Composable
private fun CordisTabContent(
    components: PluginManagerComponents,
    scope: CoroutineScope,
    onBack: () -> Unit,
    /** 宿主是否已把 Cordis 区域提升为整屏层（子页打开、壳层与底部导航收起）。 */
    fullscreen: Boolean,
    /** 子页（模块内非面板根）可见性上报：宿主据此收起壳层与底部导航。 */
    onSubPageVisibilityChanged: (Boolean) -> Unit = {},
) {
    // 面板模块据本标志切换 insets 语义：整屏时子页顶栏自让状态栏、内容区自让手势条。
    CompositionLocalProvider(LocalPluginPanelFullscreen provides fullscreen) {
        PluginManagerHost(
            safety = components.safety,
            market = components.market,
            adapter = components.adapter,
            official = components.official,
            repair = components.repair,
            scope = scope,
            onBack = onBack,
            onSubPageVisibilityChanged = onSubPageVisibilityChanged,
            // 市场界面由模块自己实现，宿主只负责把它接进来。
            marketContent = { back -> MarketScreen(components.market, back) },
        )
    }
}

@Composable
private fun TabContent(
    selectedTab: Int,
    sandboxRunning: Boolean,
    sandboxError: Boolean,
    dshState: DshState,
    dshUnresponsive: Boolean,
    runtimeInstalled: Boolean,
    nodeLayerInstalled: Boolean,
    bundledRuntimeAvailable: Boolean,
    onNavigateToSettings: () -> Unit,
    /** 设置页返回（标题栏箭头 / 系统返回键）：回到首页页签。 */
    onBackToHome: () -> Unit,
    onGetRuntimeOnline: () -> Unit,
    /** Pilot 页签内容（宿主注入的内嵌模块页）：参数为页签激活态、回到控制页签的回调与子页可见性上报。 */
    pilotContent: @Composable (
        isActive: Boolean,
        onBackToControl: () -> Unit,
        onSubPageVisible: (Boolean) -> Unit,
    ) -> Unit,
    /** Cordis 页签内容（宿主注入的内嵌模块页）：参数同 [pilotContent]。 */
    cordisContent: @Composable (
        isActive: Boolean,
        onBackToControl: () -> Unit,
        onSubPageVisible: (Boolean) -> Unit,
    ) -> Unit,
    /** DSH 悬浮球快捷条的语义路由（页签下标只在本文件解析）。 */
    onQuickAction: (DshQuickAction) -> Unit,
    /** DSH 根页面返回（其 WebView 历史见底）：宿主切回进入 DSH 之前的页签。 */
    onDshRootBack: () -> Unit,
    /** DSH 启动 token（首页「浏览器」入口拼进 URL 用；未就绪时为 null）。 */
    dshLaunchToken: String?,
    /** 沙箱与 DSH 进程树的累计 CPU 时间（毫秒）：控制页监控卡按需读取，未运行返回 null。 */
    readSandboxCpuMillis: suspend () -> Long?,
    pendingOnlineImport: Boolean,
    onPendingOnlineImportConsumed: () -> Unit,
    /** 外部应用分享/打开进来的待导入清单；文件页消费后回调置空。 */
    inboundShareItems: List<InboundShareItem>?,
    onInboundShareConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val dshReady = dshState == DshState.READY
    val dshError = dshState == DshState.ERROR
    // 启动中（STARTING/RUNNING 统一按一档展示）：READY 由健康循环首次探测成功置位，
    // 之前的整段窗口都属此档；状态机当前不再写 RUNNING（历史状态），一并归入以防回退。
    val dshStarting = dshState == DshState.STARTING || dshState == DshState.RUNNING
    // 页签切换的淡入：只有「被激活」的页做 180ms 淡入（alpha 0→1）；被切走的页当帧即被
    // keepAliveHidden 零尺寸放置，其 1→0 动画不可见。非激活页零尺寸放置，故只能做
    // 新页淡入、无法做交叉淡化（交叉淡化要求两页同尺寸叠加，与保活放置语义冲突）。
    // zIndex 仍即时切换，避免淡入的半透明新页被旧页盖住。
    val homeAlpha by animateFloatAsState(
        targetValue = if (selectedTab == HOME_CONTENT_INDEX) 1f else 0f,
        animationSpec = tween(durationMillis = CONTENT_FADE_IN_MILLIS),
        label = "tabAlphaHome",
    )
    val filesAlpha by animateFloatAsState(
        targetValue = if (selectedTab == FILES_CONTENT_INDEX) 1f else 0f,
        animationSpec = tween(durationMillis = CONTENT_FADE_IN_MILLIS),
        label = "tabAlphaFiles",
    )
    val dshAlpha by animateFloatAsState(
        targetValue = if (selectedTab == DSH_CONTENT_INDEX) 1f else 0f,
        animationSpec = tween(durationMillis = CONTENT_FADE_IN_MILLIS),
        label = "tabAlphaDsh",
    )
    val terminalAlpha by animateFloatAsState(
        targetValue = if (selectedTab == TERMINAL_CONTENT_INDEX) 1f else 0f,
        animationSpec = tween(durationMillis = CONTENT_FADE_IN_MILLIS),
        label = "tabAlphaTerminal",
    )
    val settingsAlpha by animateFloatAsState(
        targetValue = if (selectedTab == SETTINGS_CONTENT_INDEX) 1f else 0f,
        animationSpec = tween(durationMillis = CONTENT_FADE_IN_MILLIS),
        label = "tabAlphaSettings",
    )
    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == HOME_CONTENT_INDEX) 1f else 0f)
                .alpha(homeAlpha)
                .then(if (selectedTab == HOME_CONTENT_INDEX) Modifier else Modifier.keepAliveHidden()),
            // 首页内嵌的三页签按激活态开关各自的返回接管与前台呈现。
            isActive = selectedTab == HOME_CONTENT_INDEX,
            sandboxRunning = sandboxRunning,
            sandboxError = sandboxError,
            dshReady = dshReady,
            dshStarting = dshStarting,
            dshError = dshError,
            dshUnresponsive = dshUnresponsive,
            runtimeInstalled = runtimeInstalled,
            nodeLayerInstalled = nodeLayerInstalled,
            bundledRuntimeAvailable = bundledRuntimeAvailable,
            onNavigateToSettings = onNavigateToSettings,
            onGetRuntimeOnline = onGetRuntimeOnline,
            readSandboxCpuMillis = readSandboxCpuMillis,
            pilotContent = pilotContent,
            cordisContent = cordisContent,
            onOpenInBrowser = {
                // 必须带 token：外部浏览器拿不到 WebView 的 cookie 交换，也拿不到注入脚本，
                // 直接开 Constants.DSH_BASE_URL 会停在鉴权页（用户实证"打不开"）。
                // token 未就绪时**不开**：裸开必然停在鉴权页，不如给一句可重试的提示。
                val token = dshLaunchToken
                if (token.isNullOrEmpty()) {
                    Toast.makeText(context, R.string.home_open_no_token, Toast.LENGTH_SHORT).show()
                } else {
                    // chooser：隐式 http 意图可被任何声明了 127.0.0.1 的 App 抢走，
                    // 而这条 URL 带着等同沙箱控制权的 token（详见 DshBrowser.kt）。
                    val opened = runCatching {
                        context.startActivity(
                            dshBrowserChooser(
                                Constants.DSH_BASE_URL,
                                token,
                                context.getString(R.string.home_open),
                            ),
                        )
                    }.onFailure { t -> Log.w(TAG, "open DSH in browser failed: ${t.message}") }.isSuccess
                    if (!opened) {
                        Toast.makeText(context, R.string.home_open_browser_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        FilesScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == FILES_CONTENT_INDEX) 1f else 0f)
                .alpha(filesAlpha)
                .then(if (selectedTab == FILES_CONTENT_INDEX) Modifier else Modifier.keepAliveHidden()),
            isActiveTab = selectedTab == FILES_CONTENT_INDEX,
            inboundShareItems = inboundShareItems,
            onInboundShareConsumed = onInboundShareConsumed,
        )
        DshWebViewScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == DSH_CONTENT_INDEX) 1f else 0f)
                .alpha(dshAlpha)
                .then(if (selectedTab == DSH_CONTENT_INDEX) Modifier else Modifier.keepAliveHidden()),
            url = com.dshbox.app.common.Constants.DSH_BASE_URL,
            dshState = dshState,
            sandboxRunning = sandboxRunning,
            isActiveTab = selectedTab == DSH_CONTENT_INDEX,
            onStartDsh = { SandboxService.startDsh(context) },
            onStartSandbox = { SandboxService.startSandbox(context) },
            onQuickAction = onQuickAction,
            onBackAtRoot = onDshRootBack,
        )
        TerminalScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == TERMINAL_CONTENT_INDEX) 1f else 0f)
                .alpha(terminalAlpha)
                .then(if (selectedTab == TERMINAL_CONTENT_INDEX) Modifier else Modifier.keepAliveHidden()),
            sandboxRunning = sandboxRunning,
            isActiveTab = selectedTab == TERMINAL_CONTENT_INDEX,
        )
        SettingsScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == SETTINGS_CONTENT_INDEX) 1f else 0f)
                .alpha(settingsAlpha)
                .then(if (selectedTab == SETTINGS_CONTENT_INDEX) Modifier else Modifier.keepAliveHidden()),
            // 设置页常驻组合（keepAlive），存储占用统计需要在切进
            // 设置页时重新触发；dshActive 供 /tmp 智能清理判定使用。
            isActive = selectedTab == SETTINGS_CONTENT_INDEX,
            // 设置页返回（标题栏箭头 / 系统返回键）回到首页。
            onBack = onBackToHome,
            sandboxRunning = sandboxRunning,
            dshReady = dshReady,
            dshActive = dshStarting || dshReady,
            pendingOnlineImport = pendingOnlineImport,
            onPendingOnlineImportConsumed = onPendingOnlineImportConsumed,
        )
    }
}

/**
 * 沉浸页签的状态栏控制，覆盖两个场景：终端页键盘弹出（键盘顶起时终端内容独占
 * 被顶起区域）与 DSH 页签整页沉浸（网页顶到屏幕顶）。enabled 变化、离开页签、
 * 组合销毁均恢复；ON_RESUME 时按当前 enabled 再断言一次，兜底部分 ROM 在
 * Activity stop→start 后不重放窗口 insets 状态的情况。
 *
 * 只控制状态栏——导航栏在这两个场景分别被键盘覆盖（终端）或按需保留（DSH）。
 * 恢复路径必须完整：状态栏停留在隐藏态会让系统图标永久消失。
 */
@Composable
private fun ImmersiveStatusBar(enabled: Boolean) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // 生命周期观察者里只读最新值：observer 注册一次，enabled 后续变化要能被看到。
    val currentEnabled by rememberUpdatedState(enabled)
    DisposableEffect(lifecycleOwner, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val window = (view.context as? Activity)?.window ?: return@LifecycleEventObserver
            val controller = WindowCompat.getInsetsController(window, view)
            if (currentEnabled) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(enabled, view) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (enabled) {
            // 隐藏后仍可从顶部下滑临时唤出，避免图标"彻底消失"的错觉。
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    // 兜底恢复：组合被移除（Activity 销毁 / 配置变更）时图标必须回到可见。
    // controller 在 setup 阶段构造——窗口销毁后再构造会失败。
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        onDispose {
            runCatching { controller?.show(WindowInsetsCompat.Type.statusBars()) }
        }
    }
}

// 底部导航使用页面背景色，并通过主题提供选中指示器、图标和标签颜色。

/** 导航栏内容高度，不含系统导航栏 inset。 */
private val NavBarHeight = 56.dp

/** 选中态圆角指示器的宽度与高度。 */
private val NavIndicatorWidth = 44.dp
private val NavIndicatorHeight = 30.dp

/** 导航图标尺寸，在指示器内保留垂直间距。 */
private val NavIconSize = 22.dp

/** 导航图标与标签的垂直间距。 */
private val NavIconLabelGap = 2.dp

/**
 * 标签沿用 titleMedium 的字体族与字重，单独设置字号与行高以适配导航栏高度。
 */
private val NavLabelFontSize = 13.sp
private val NavLabelLineHeight = 15.sp

@Composable
private fun AppBottomNavigationBar(
    tabs: List<TabSpec>,
    selectedTab: Int,
    onSelect: (TabSpec) -> Unit,
) {
    // 容器色 = 页面背景色（colorScheme.background），与上方内容区无缝衔接。
    // Surface 默认 tonalElevation = 0dp，不会再被 M3 叠一层 tonal 色调而偏离 token。
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 系统导航栏（手势条 / 三键）留白：自绘后没有 NavigationBar 自带的
                // windowInsets，必须自己加，否则页签会被手势条压住。
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(NavBarHeight)
                // 无障碍：整行作为一个页签组播报。
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                NavBarItem(
                    tab = tab,
                    // 选中判定走 contentIndex：动作页签（contentIndex = null）永不选中，
                    // 设置页打开时五个导航项也无一高亮（它已不是导航项）。
                    selected = tab.contentIndex != null && selectedTab == tab.contentIndex,
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

/**
 * 单个页签。抽成独立 composable 是为了让每个页签各自 `remember` 一份
 * [MutableInteractionSource]——"整列只负责命中与语义、波纹只画在药丸里"这套分工
 * 需要两处 modifier 共用同一个 interactionSource（见下）。
 */
@Composable
private fun RowScope.NavBarItem(
    tab: TabSpec,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val indicatorShape = RoundedCornerShape(NavIndicatorHeight / 2)
    val indicatorColor =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val iconTint =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    val labelColor =
        if (selected) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = interactionSource,
                // 整列负责点击与页签语义；波纹由下方指示器绘制，避免覆盖整列。
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .width(NavIndicatorWidth)
                .height(NavIndicatorHeight)
                // clip 在前 ⇒ 后面的 background 与 indication 都被收进药丸形状内。
                .clip(indicatorShape)
                .background(indicatorColor)
                // 波纹限定在药丸内，颜色取本页签图标色：深浅主题各自跟随，无需硬编码。
                .indication(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true, color = iconTint),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tab.icon,
                // 由标签提供语义，避免与图标重复播报。
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(NavIconSize),
            )
        }
        Spacer(modifier = Modifier.height(NavIconLabelGap))
        Text(
            text = stringResource(tab.labelRes),
            // 字体族/字重取自页面标题体系，字号另见上面常量说明。
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = NavLabelFontSize,
                lineHeight = NavLabelLineHeight,
            ),
            color = labelColor,
            // 标签限单行，较长翻译使用省略号，避免挤压导航布局。
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}
