package com.dshbox.app.ui

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import com.dshbox.pluginmanager.core.PluginManagerFactory
import com.dshbox.pluginmanager.market.ui.MarketScreen
import com.dshbox.pluginmanager.ui.PluginManagerHost
import dshbox.adapter.entry.PilotActivity
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
// M74：底部导航由 Material3 的 NavigationBar/NavigationBarItem 换为自绘紧凑实现，
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
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
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.ui.files.keepAliveHidden
import com.dshbox.app.ui.files.FilesScreen
import com.dshbox.app.ui.home.HomeScreen
import com.dshbox.app.ui.launch.LaunchScreen
import com.dshbox.app.ui.settings.SettingsScreen
import com.dshbox.app.ui.terminal.TerminalScreen
// M74/M75 返工后 AppIcons 仅出现在上方的回退注释里，现随之一并注释，
// 免得留下"未使用的导入"；回退时与注释块一起恢复。
// import com.dshbox.app.ui.theme.AppIcons
import com.dshbox.app.ui.webview.DshWebViewScreen
import com.dshbox.app.ui.webview.dshBrowserChooser
import com.dshbox.app.service.SandboxService
import com.dshbox.app.util.AppUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.dshbox.app.common.R as CommonR

private data class TabSpec(val labelRes: Int, val icon: ImageVector)

/** Minimum time the brand launch animation stays visible on cold start. */
private const val SPLASH_MIN_MILLIS = 2_000L

private const val TAG = "MainScreen"

@Composable
fun MainScreen() {
    val app = LocalContext.current.applicationContext as DshApp
    val sandboxManager = app.container.sandboxManager

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Runtime truth starts fresh each process; remember() avoids restoring stale
    // READY from a previous process via rememberSaveable.
    var sandboxRunning by remember { mutableStateOf(false) }
    var sandboxError by remember { mutableStateOf(false) }
    var dshState by remember { mutableStateOf(DshState.UNINITIALIZED) }
    var dshUnresponsive by remember { mutableStateOf(false) }
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

    // 助手页入口：打开适配层的 PilotActivity（其内部会一并装配通道）。
    val onOpenPilot: () -> Unit = { app.startActivity(PilotActivity.intent(app)) }

    // 插件管理：整页覆盖在 Tab 之上（首页入口进入，返回即关闭）。
    // 组件按 composition 生命周期创建一次；它的生命周期观察协程随 scope 走。
    // 覆盖态用 rememberSaveable：DSH 起不来时这一页是唯一入口，
    // 不该因为一次配置变更（旋转/分屏）就被关掉。
    var showPluginManager by rememberSaveable { mutableStateOf(false) }
    val pluginScope = rememberCoroutineScope()
    val pluginManager = remember {
        PluginManagerFactory.create(
            context = app,
            sandbox = sandboxManager,
            scope = pluginScope,
        )
    }
    val onOpenPluginManager: () -> Unit = { showPluginManager = true }

    // 首页「运行环境缺失」卡 → 在线获取直达（切到设置页，由其消费并打开覆盖页）。
    var pendingOnlineImport by remember { mutableStateOf(false) }

    // M74 底部导航视觉调整：五个页签统一用 Tabler 描边风格图标
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
        TabSpec(R.string.tab_home, ImageVector.vectorResource(R.drawable.ic_nav_home)),
        TabSpec(R.string.tab_files, ImageVector.vectorResource(R.drawable.ic_nav_folder)),
        // DSH 页签：换成同源的 Tabler 描边地球（旧行见上方注释块，回退时替换回来即可）。
        TabSpec(R.string.tab_dsh, ImageVector.vectorResource(R.drawable.ic_nav_dsh)),
        TabSpec(R.string.tab_terminal, ImageVector.vectorResource(R.drawable.ic_nav_terminal)),
        TabSpec(R.string.tab_settings, ImageVector.vectorResource(R.drawable.ic_nav_settings)),
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

    // 启动静默更新检测：等首屏稳定后再发起，避免与冷启动抢资源；仅在有新版本时弹提示。
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MILLIS + 4_000)
        val result = AppUpdater.checkLatest()
        if (result.isNewer && !AppUpdater.shouldSuppressAutoPrompt(app, result.latestTag)) {
            autoUpdate = result
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val splashVisible = showLaunch && dshState != DshState.READY
        val density = LocalDensity.current

        // Terminal tab with the soft keyboard up enters an immersive layout: the
        // terminal owns "screen height - IME height" entirely. Three coordinated
        // effects share this condition: (1) contentWindowInsets is cleared —
        // otherwise the terminal is inset by BOTH the navigation bar height
        // (Scaffold innerPadding) AND the full IME height
        // (TerminalScreen.imePadding()), leaving a blank band the height of the
        // navigation bar between the extra-keys bar and the keyboard;
        // (2) the bottom navigation bar is hidden — the keyboard covers its area;
        // (3) the status bar icons are hidden — content reaches the screen top,
        // so the edge-to-edge transparent status bar would otherwise leave the
        // system icons floating over the terminal surface.
        val imeVisible = WindowInsets.ime.getBottom(density) > 0
        val immersiveTerminal = imeVisible && selectedTab == 3

        TerminalImmersiveStatusBar(enabled = immersiveTerminal)

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (splashVisible) 0f else 1f),
            contentWindowInsets = if (immersiveTerminal) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            bottomBar = {
                if (!immersiveTerminal) {
                    AppBottomNavigationBar(
                        tabs = tabs,
                        selectedTab = selectedTab,
                        onSelect = { selectedTab = it },
                    )
                    // M74：上面这行替换了原来的 Material3 底部导航（默认 80dp 偏高、
                    // 背景取 surfaceContainer 与页面有色差、且不暴露"图标与文字间距"）。
                    // 原实现整块保留在下面，回退时取消注释即可
                    // （同时恢复文件顶部 NavigationBar/NavigationBarItem 两个导入）。
                    // NavigationBar {
                    //     tabs.forEachIndexed { index, tab ->
                    //         NavigationBarItem(
                    //             selected = selectedTab == index,
                    //             onClick = { selectedTab = index },
                    //             // TabSpec 直接持有 ImageVector（不是 AppIcons 枚举），
                    //             // 将来新增页签不必再改枚举。
                    //             icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                    //             label = {
                    //                 // 页签标签限单行，长翻译（AR/RU/FR）不换行挤爆。
                    //                 // （v1.3.0 六语化引入，合并时不可退回。）
                    //                 Text(
                    //                     stringResource(tab.labelRes),
                    //                     maxLines = 1,
                    //                     overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    //                     softWrap = false,
                    //                 )
                    //             },
                    //         )
                    //     }
                    // }
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
                    onNavigateToSettings = { selectedTab = 4 },
                    onGetRuntimeOnline = {
                        pendingOnlineImport = true
                        selectedTab = 4
                    },
                    onOpenPilot = onOpenPilot,
                    onOpenPluginManager = onOpenPluginManager,
                    dshLaunchToken = dshLaunchToken,
                    pendingOnlineImport = pendingOnlineImport,
                    onPendingOnlineImportConsumed = { pendingOnlineImport = false },
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

        // 插件管理整页：覆盖在 Tab 内容之上，返回即关闭。
        // 放在最外层 Box 里而不是某个页签内，是为了让「DSH 起不来」时也能从首页
        // 一步进来排障（它不依赖 WebView 或终端）。
        if (showPluginManager) {
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = androidx.compose.material3.MaterialTheme.colorScheme.background,
            ) {
                PluginManagerHost(
                    safety = pluginManager.safety,
                    market = pluginManager.market,
                    adapter = pluginManager.adapter,
                    official = pluginManager.official,
                    repair = pluginManager.repair,
                    scope = pluginScope,
                    onBack = { showPluginManager = false },
                    // 市场界面由模块自己实现，宿主只负责把它接进来。
                    marketContent = { back -> MarketScreen(pluginManager.market, back) },
                )
            }
        }
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
    onGetRuntimeOnline: () -> Unit,
    onOpenPilot: () -> Unit,
    onOpenPluginManager: () -> Unit,
    /** DSH 启动 token（首页「浏览器」入口拼进 URL 用；未就绪时为 null）。 */
    dshLaunchToken: String?,
    pendingOnlineImport: Boolean,
    onPendingOnlineImportConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val dshReady = dshState == DshState.READY
    val dshError = dshState == DshState.ERROR
    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 0) 1f else 0f)
                .alpha(if (selectedTab == 0) 1f else 0f)
                .then(if (selectedTab == 0) Modifier else Modifier.keepAliveHidden()),
            sandboxRunning = sandboxRunning,
            sandboxError = sandboxError,
            dshReady = dshReady,
            dshError = dshError,
            dshUnresponsive = dshUnresponsive,
            runtimeInstalled = runtimeInstalled,
            nodeLayerInstalled = nodeLayerInstalled,
            bundledRuntimeAvailable = bundledRuntimeAvailable,
            onNavigateToSettings = onNavigateToSettings,
            onGetRuntimeOnline = onGetRuntimeOnline,
            onOpenPilot = onOpenPilot,
            onOpenPluginManager = onOpenPluginManager,
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
                .zIndex(if (selectedTab == 1) 1f else 0f)
                .alpha(if (selectedTab == 1) 1f else 0f)
                .then(if (selectedTab == 1) Modifier else Modifier.keepAliveHidden()),
            isActiveTab = selectedTab == 1,
        )
        DshWebViewScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 2) 1f else 0f)
                .alpha(if (selectedTab == 2) 1f else 0f)
                .then(if (selectedTab == 2) Modifier else Modifier.keepAliveHidden()),
            url = com.dshbox.app.common.Constants.DSH_BASE_URL,
            dshState = dshState,
            sandboxRunning = sandboxRunning,
            isActiveTab = selectedTab == 2,
            onStartDsh = { SandboxService.startDsh(context) },
            onStartSandbox = { SandboxService.startSandbox(context) },
        )
        TerminalScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 3) 1f else 0f)
                .alpha(if (selectedTab == 3) 1f else 0f)
                .then(if (selectedTab == 3) Modifier else Modifier.keepAliveHidden()),
            sandboxRunning = sandboxRunning,
            isActiveTab = selectedTab == 3,
        )
        SettingsScreen(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(if (selectedTab == 4) 1f else 0f)
                .alpha(if (selectedTab == 4) 1f else 0f)
                .then(if (selectedTab == 4) Modifier else Modifier.keepAliveHidden()),
            // 设置页常驻组合（keepAlive），存储占用统计需要在切进
            // 设置页时重新触发；dshActive 供 /tmp 智能清理判定使用。
            isActive = selectedTab == 4,
            sandboxRunning = sandboxRunning,
            dshReady = dshReady,
            dshActive = dshState == DshState.STARTING || dshState == DshState.RUNNING || dshState == DshState.READY,
            pendingOnlineImport = pendingOnlineImport,
            onPendingOnlineImportConsumed = onPendingOnlineImportConsumed,
        )
    }
}

/**
 * 终端页键盘沉浸的状态栏控制：键盘弹出时隐藏顶部状态栏图标，让终端内容独占
 * 被顶起的区域；键盘收起、切走页签、组合销毁三处均恢复。
 *
 * 只控制状态栏——导航栏在同一场景下已被键盘覆盖。恢复路径必须完整：
 * 状态栏停留在隐藏态会让系统图标永久消失。
 */
@Composable
private fun TerminalImmersiveStatusBar(enabled: Boolean) {
    val view = LocalView.current
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

// ---------- 底部导航（M74 自绘） ----------
//
// 为什么不再用 Material3 的 NavigationBar / NavigationBarItem：
//   1. 容器高固定 80dp，且"图标与文字之间"没有任何可调参数（M3 规格写死），
//      而本次诉求是"整体下压 + 图标文字贴近"，只能自绘；
//   2. 容器色取 surfaceContainer（浅色 #F7F7F8 / 暗色 #2A2A2A），
//      与页面背景（浅色 #FFFFFF / 暗色 #212121）有色差，看着像贴了一条灰带。
//
// 自绘只改排布，不改配色语义：选中态仍是 secondaryContainer 药丸 +
// onSecondaryContainer、未选中仍是 onSurfaceVariant——浅色/暗色都由主题 token
// 驱动，两套主题各自协调，无需硬编码颜色。

/** 导航栏内容高度（不含系统导航栏 inset）。M3 默认 80dp，这里压到 56dp。 */
private val NavBarHeight = 56.dp

/** 选中态药丸底，比 M3 的 64×32 收一圈，配合更低的总高。 */
private val NavIndicatorWidth = 44.dp
private val NavIndicatorHeight = 30.dp

/** 图标尺寸：药丸内留上下余量，30dp 药丸里塞 24dp 图标会显挤。 */
private val NavIconSize = 22.dp

/** 图标与文字间距。M3 不暴露这个参数，自绘才能收到 2dp。 */
private val NavIconLabelGap = 2.dp

/**
 * 标签字号 13sp。字体族与字重对齐页面标题体系（titleMedium，首页「沙箱在线」
 * 同款：FontFamily.Default + SemiBold），只收字号——18sp 在五个页签下，
 * 长翻译（六语中最长者是法语 Paramètres）在本就偏窄的单页签宽度里会走省略号。
 * 行高必须同时收紧：titleMedium 的 24sp 行高会把 56dp 的容器顶破。
 */
private val NavLabelFontSize = 13.sp
private val NavLabelLineHeight = 15.sp

@Composable
private fun AppBottomNavigationBar(
    tabs: List<TabSpec>,
    selectedTab: Int,
    onSelect: (Int) -> Unit,
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
            tabs.forEachIndexed { index, tab ->
                NavBarItem(
                    tab = tab,
                    selected = selectedTab == index,
                    onClick = { onSelect(index) },
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
                // 整列**不画**按压波纹。默认取 LocalIndication 时，波纹会按整列尺寸
                // 铺出一个"1/5 屏宽的矩形色块"，点一下像闪了个方框（用户实测复现）。
                // Material3 的 NavigationBarItem 也是这么分工的：整列只管命中与语义，
                // 波纹画在指示器（药丸）里 —— 见下面 Box 的 indication。
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
                // contentDescription 置 null：语义由下面那行标签承担，
                // 与旧 NavigationBarItem 的处理一致（六语下不双读）。
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
            // 页签标签限单行，长翻译（AR/RU/FR）不换行挤爆。
            // （v1.3.0 六语化引入，合并时不可退回。）
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

/**
 * Keeps a non-selected tab composed (state preserved) while removing it from
 * the hit-test graph entirely: measured as usual but placed at zero size.
 *
 * The previous approach (a pointerInput loop consuming every event) sat as a
 * sibling of the ACTIVE tab in the same Box and stole/cancelled the active
 * tab's real touches — most visibly breaking TerminalView scrolling/pinch.
 * Zero-size placement achieves the same "no stray taps" goal without ever
 * touching the pointer stream.
 */
// keepAliveHidden 是 ui/files/FilesCommon.kt 的 internal 扩展，
// 供覆盖式二级页（FileViewerScreen/FolderPickerScreen）与各 tab 共用（返工批次）。
