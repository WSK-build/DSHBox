package com.dshbox.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
// 「浏览器」入口那个地球**刻意保留** Material 的实心图标（用户专门设计成与其它入口不同，
// 不参与描边统一），所以这两个导入保持启用、不要注释。
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.service.SandboxService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** 首页页签数：控制 / Pilot / Cordis。 */
private const val HomeTabCount = 3

/** 首页三页签文案。 */
private val HomeTabLabels =
    listOf(R.string.home_tab_control, R.string.home_tab_pilot, R.string.home_tab_cordis)

/**
 * 首页外壳：品牌顶栏 + 三页签（控制 / Pilot / Cordis）。
 *
 * 「控制」承载沙箱与 DSH 的启停状态卡；Pilot 与 Cordis 的内容由宿主注入
 * （[pilotContent] / [cordisContent]），内嵌的模块页组合原样复用、零改动。
 *
 * 垂直方向的 insets 账目（宿主 Scaffold 对首页 contentWindowInsets = 0，见 MainScreen）：
 * - 状态栏：品牌顶栏让位（本文件 HomeBrandBar），只让一次；
 * - 系统导航栏：壳层 bottomBar（AppBottomNavigationBar）让位，页签内容区底部即其上沿；
 * - 三页签内容区：左右出血 16dp、顶部 12dp、底部 24dp，由各页自行处理，
 *   内嵌模块页的 contentWindowInsets 清零（embedded 两态语义见各模块页）。
 *
 * 子页整屏提升：Pilot / Cordis 模块内子页打开时（经 [pilotContent] / [cordisContent]
 * 的 onSubPageVisible 上报），品牌顶栏与页签行整块收起、宿主底部导航同步隐藏，
 * 整屏让给子页；insets 让位随之切换——子页顶栏自让状态栏、子页内容区自让手势条
 * （模块侧 embedded / fullscreen 两态语义）。子页返回根页后壳层原样恢复。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    /**
     * 顶层页签激活态。首页被切走后仍常驻组合（keepAlive），内嵌模块页的
     * 返回接管与 Pilot 的前台呈现都跟随它开关。
     */
    isActive: Boolean,
    sandboxRunning: Boolean,
    sandboxError: Boolean,
    dshReady: Boolean,
    /** DSH 已启动、尚未就绪（STARTING/RUNNING）：卡片走中间态而非「已停止」，启动按钮此时不可点。 */
    dshStarting: Boolean,
    dshError: Boolean,
    /** 连续不应答已达展示阈值：状态行改提示「无响应」，供用户在等待期间自行选择重启。 */
    dshUnresponsive: Boolean,
    runtimeInstalled: Boolean,
    nodeLayerInstalled: Boolean = true,
    bundledRuntimeAvailable: Boolean,
    onNavigateToSettings: () -> Unit,
    onGetRuntimeOnline: () -> Unit,
    /**
     * 用**外部浏览器**打开 DSH。由宿主（[com.dshbox.app.ui.MainScreen]）实现：
     * URL 必须带 DSH 启动 token，否则外部浏览器会停在鉴权页（它没有 WebView 的
     * cookie 交换与注入通道），这正是该入口"点开不可用"的原因。
     */
    onOpenInBrowser: () -> Unit,
    /**
     * 沙箱与 DSH 进程树当前的累计 CPU 时间（毫秒），由宿主注入给控制页的监控卡：
     * 沙箱是应用内部的东西，统计时并入「本应用」一档，不单列条目。未运行时返回 null。
     */
    readSandboxCpuMillis: suspend () -> Long? = { null },
    /**
     * Pilot 页签内容（宿主注入，内嵌适配层助手页）。参数为页签激活态、
     * 「回到控制页签」回调与子页可见性上报：模块内子页打开时壳层（品牌栏 +
     * 页签行）收起、页签区域提升为整屏；模块页自身的返回接管跟随激活态开关，
     * 其内部返回栈到底时经回调回到控制页签。
     */
    pilotContent: @Composable (
        isActive: Boolean,
        onBackToControl: () -> Unit,
        onSubPageVisible: (Boolean) -> Unit,
    ) -> Unit,
    /** Cordis 页签内容（宿主注入，内嵌插件管理面板）。参数同 [pilotContent]。 */
    cordisContent: @Composable (
        isActive: Boolean,
        onBackToControl: () -> Unit,
        onSubPageVisible: (Boolean) -> Unit,
    ) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSandboxStopDialog by remember { mutableStateOf(false) }
    var showDshStopDialog by remember { mutableStateOf(false) }
    var dshNeedsSandboxToast by remember { mutableStateOf(false) }

    if (dshNeedsSandboxToast) {
        LaunchedEffect(Unit) {
            Toast.makeText(
                context,
                R.string.home_start_dsh_needs_sandbox,
                Toast.LENGTH_LONG,
            ).show()
            dshNeedsSandboxToast = false
        }
    }

    // 三页签外壳：选中态由 Pager 持有（rememberPagerState 内部经 rememberSaveable
    // 存档，配置变更不丢）。beyondViewportPageCount = 2 让三页全部常驻组合——
    // 切走不销毁、状态不重置，页间滑动与 TabRow 指示器动画由组件自身承接，切换平滑无跳变。
    val pagerState = rememberPagerState(pageCount = { HomeTabCount })
    // 内嵌模块页首次到访才装配（Pilot 在其组合内装配通道容器、Cordis 在其组合内
    // 校准适配开关），到访后保持常驻；未到访不产生任何模块初始化开销。
    var pilotVisited by rememberSaveable { mutableStateOf(false) }
    var cordisVisited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        // settledPage：真正落定才记到访/切换激活——半拖又拖回不触发模块装配，
        // 前台呈现路由也不在动画中途翻转。
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page == 1) pilotVisited = true
            if (page == 2) cordisVisited = true
        }
    }

    // 子页整屏提升：每个页签各记一份子页可见性——模块的上报只写自己那一份，两个
    // 常驻组合的页签互不覆盖（共用一份时，不活跃那个模块每轮上报都会把当前页的
    // 整屏状态顶回壳内，页签行与底部导航会来回闪）。壳层收起与否只看落定页签那份；
    // 页签切换不需要复位：子页打开期间页签行与横向翻页本就已收起。
    var pilotSubPageVisible by remember { mutableStateOf(false) }
    var cordisSubPageVisible by remember { mutableStateOf(false) }
    val subPageVisible = when (pagerState.settledPage) {
        1 -> pilotSubPageVisible
        2 -> cordisSubPageVisible
        else -> false
    }

    // 壳层画布取 background：品牌栏、页签行与三个页签内容区整列同色。宿主 Scaffold
    // 的默认容器色是 surface，深色主题下比 background 亮一档，不显式铺底就会在
    // 品牌栏与页签行之间留下一圈色缝（浅色两值同白，看不出；深色是肉眼可见的带子）。
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 子页整屏提升（subPageVisible）：品牌栏与页签行整块收起，页签区域占满
        // 整屏（子页顶栏自带返回），与宿主底部导航的隐藏同一时刻生效。
        if (!subPageVisible) {
            HomeBrandBar(
                onNavigateToSettings = onNavigateToSettings,
                modifier = Modifier.fillMaxWidth(),
            )
            // 页签行：三页签等宽铺满整行（weight(1f)），背景与壳层同色；自绘页签单元——
            // material3 Tab 的内建波纹铺满整个页签格且无法从外部关闭，改用
            // selectable + indication = null（无波纹）。除主色滑动下划线（指示器动画由
            // 组件承接）外，页签行不出现任何品牌绿：选中/未选中文字均用中性色。
            // 底部灰线不画，与壳层同色背景整列连贯。
            TabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = MaterialTheme.colorScheme.background,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                        height = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                divider = {},
            ) {
                HomeTabLabels.forEachIndexed { index, labelRes ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .minimumInteractiveComponentSize()
                            .selectable(
                                selected = selected,
                                role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (index != pagerState.currentPage) {
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(labelRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            beyondViewportPageCount = 2,
            // 子页整屏提升期间关掉手势翻页：页签行已收起，横向拖动应交给子页内容
            // 自己处理，而不是把用户甩到另一个页签（程序化跳页不受影响）。
            userScrollEnabled = !subPageVisible,
        ) { page ->
            when (page) {
                0 -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // 与两个模块根页同一份账：左右出血 16dp、顶部 12dp 呼吸、
                        // 底部 24dp 滚动到底的留白（内容区底部已由壳层 bottomBar 让位）。
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (!runtimeInstalled) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(
                                        if (bundledRuntimeAvailable) R.string.home_runtime_installing else R.string.home_runtime_missing,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                if (bundledRuntimeAvailable) {
                                    Text(
                                        text = stringResource(R.string.home_runtime_installing_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.home_runtime_missing_hint),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                    // 主入口：在线获取（镜像源探测 + 本机组装精简版）。
                                    Button(
                                        shape = MaterialTheme.shapes.medium,
                                        onClick = onGetRuntimeOnline,
                                    ) {
                                        Text(stringResource(R.string.home_runtime_get_online))
                                    }
                                    // 次入口：离线导入（无网兜底，进入设置页走既有流程）。
                                    OutlinedButton(
                                        shape = MaterialTheme.shapes.medium,
                                        onClick = onNavigateToSettings,
                                    ) {
                                        Text(stringResource(R.string.home_runtime_import))
                                    }
                                }
                            }
                        }
                    }

                    // node 层未导入引导：Linux 层装完后 runtimeInstalled 已为 true，
                    // 但 DSH 需要 node 层才能启动——此卡补齐"下一步该做什么"的指引。
                    if (runtimeInstalled && !nodeLayerInstalled) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.errorContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.home_node_missing),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    text = stringResource(R.string.home_node_missing_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Button(
                                    shape = MaterialTheme.shapes.medium,
                                    onClick = onGetRuntimeOnline,
                                ) {
                                    Text(stringResource(R.string.home_node_missing_action))
                                }
                            }
                        }
                    }

                    SandboxStatusCard(
                        sandboxRunning = sandboxRunning,
                        sandboxError = sandboxError,
                        onStart = { SandboxService.startSandbox(context) },
                        onStop = { showSandboxStopDialog = true },
                        onRestart = { SandboxService.restartSandbox(context) },
                    )

                    DshStatusCard(
                        dshReady = dshReady,
                        dshStarting = dshStarting,
                        dshError = dshError,
                        dshUnresponsive = dshUnresponsive,
                        sandboxRunning = sandboxRunning,
                        onStart = {
                            if (sandboxRunning) {
                                SandboxService.startDsh(context)
                            } else {
                                dshNeedsSandboxToast = true
                            }
                        },
                        onStop = { showDshStopDialog = true },
                        onRestart = { SandboxService.restartDsh(context) },
                        onOpenInBrowser = onOpenInBrowser,
                    )

                    // 系统状态：整机 CPU 占用、温度、内存、存储四项。采样只在「本页
                    // 可见 + App 前台」时进行，纪律与开销说明见 SystemMetricsCard。
                    SystemMetricsCard(
                        active = isActive && pagerState.settledPage == 0,
                        sandboxCpuMillis = readSandboxCpuMillis,
                    )
                }

                1 -> if (pilotVisited) {
                    TabBackGate(active = isActive && pagerState.settledPage == 1) {
                        pilotContent(
                            isActive && pagerState.settledPage == 1,
                            { scope.launch { pagerState.animateScrollToPage(0) } },
                            { visible -> pilotSubPageVisible = visible },
                        )
                    }
                }

                // Cordis 是最后一个页签，其余下标只会是 0 / 1。
                else -> if (cordisVisited) {
                    TabBackGate(active = isActive && pagerState.settledPage == 2) {
                        cordisContent(
                            isActive && pagerState.settledPage == 2,
                            { scope.launch { pagerState.animateScrollToPage(0) } },
                            { visible -> cordisSubPageVisible = visible },
                        )
                    }
                }
            }
        }
    }

    if (showSandboxStopDialog) {
        AlertDialog(
            onDismissRequest = { showSandboxStopDialog = false },
            title = { Text(stringResource(R.string.home_sandbox_stop_confirm_title)) },
            text = { Text(stringResource(R.string.home_sandbox_stop_confirm_message)) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        showSandboxStopDialog = false
                        SandboxService.stopSandbox(context)
                    },
                ) {
                    Text(stringResource(R.string.home_stop_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSandboxStopDialog = false }) {
                    Text(stringResource(R.string.home_stop_cancel))
                }
            },
        )
    }

    if (showDshStopDialog) {
        AlertDialog(
            onDismissRequest = { showDshStopDialog = false },
            title = { Text(stringResource(R.string.home_dsh_stop_confirm_title)) },
            text = { Text(stringResource(R.string.home_dsh_stop_confirm_message)) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        showDshStopDialog = false
                        SandboxService.stopDsh(context)
                    },
                ) {
                    Text(stringResource(R.string.home_stop_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDshStopDialog = false }) {
                    Text(stringResource(R.string.home_stop_cancel))
                }
            },
        )
    }
}

/**
 * 首页品牌顶栏：圆角品牌图 + 应用名，右上角设置齿轮入口。
 *
 * 状态栏让位由本栏自行处理（外壳层对首页 contentWindowInsets = 0，见 [HomeScreen] 的
 * 账目说明）——内嵌模块页（Pilot / Cordis）上方不再出现双重空带。
 * 左右出血与页签内容区同宽（16dp）：品牌图左缘与下方卡片左缘在同一铅垂线上。
 */
@Composable
private fun HomeBrandBar(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌标记必须用 Image 而不是 Icon：Icon 会按 tint 把图形**单色化**，
        // 品牌绿与图形细节会一起丢失。
        Image(
            painter = painterResource(R.mipmap.brand_icon),
            contentDescription = null,
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onNavigateToSettings) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_nav_settings),
                contentDescription = stringResource(R.string.tab_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 页签内容的返回接管开关。
 *
 * 内嵌模块页自带 BackHandler（组合即注册，布局隐藏不会注销）：页签保活常驻下，
 * 非激活页的返回接管会吞掉其它页签上的系统返回。activity-compose 的约定是
 * 「BackHandler 只在注册它的 LifecycleOwner 处于 STARTED 及以上时才进入分发链」，
 * 因此这里为内嵌内容换上一个受控的 [LocalLifecycleOwner]：激活时升到 RESUMED、
 * 离开时回落到 CREATED——模块自身的返回语义（含各自的 enabled 门控）不受影响。
 */
@Composable
private fun TabBackGate(active: Boolean, content: @Composable () -> Unit) {
    val owner = remember { TabBackLifecycle() }
    SideEffect { owner.setActive(active) }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

/** 页签返回接管的受控生命周期宿主（见 [TabBackGate]）。 */
private class TabBackLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    fun setActive(active: Boolean) {
        val target = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED
        if (registry.currentState != target) {
            registry.currentState = target
        }
    }

    override val lifecycle: Lifecycle get() = registry
}

@Composable
private fun SandboxStatusCard(
    sandboxRunning: Boolean,
    sandboxError: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val statusText = stringResource(
        when {
            sandboxError -> R.string.home_sandbox_error
            sandboxRunning -> R.string.home_sandbox_running
            else -> R.string.home_sandbox_stopped
        },
    )
    val statusColor = when {
        sandboxError -> MaterialTheme.colorScheme.error
        sandboxRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = when {
            sandboxError -> MaterialTheme.colorScheme.errorContainer
            sandboxRunning -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            when {
                sandboxError -> MaterialTheme.colorScheme.error
                sandboxRunning -> MaterialTheme.colorScheme.outlineVariant
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 通用短词三按钮（启动/重启/关闭），纯文字无图标。
                StatusActionButton(
                    label = stringResource(R.string.action_start),
                    enabled = !sandboxRunning,
                    onClick = onStart,
                )
                StatusActionButton(
                    label = stringResource(R.string.action_restart),
                    enabled = sandboxRunning,
                    onClick = onRestart,
                )
                StatusActionButton(
                    label = stringResource(R.string.action_stop),
                    enabled = sandboxRunning,
                    onClick = onStop,
                )
            }
        }
    }
}

/**
 * 状态卡通用短词按钮（启动/重启/关闭）。图标区分语义；
 * 文本限单行防溢出（六语翻译在窄按钮内不换行、不挤爆）。
 * RowScope 扩展以使用等宽 weight 布局。
 */
@Composable
private fun RowScope.StatusActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        shape = MaterialTheme.shapes.medium,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
    ) {
        // 纯文字短词按钮（启动/重启/关闭）——去除图标，
        // 文字铺满按钮宽度、居中；省略号仅在真正超出按钮宽度时出现。
        // 文字短词语义自明；图标占位会挤压文字，故不设图标。
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * DSH 卡片的状态文案映射。
 *
 * 分支顺序即优先级：错误、无响应、就绪、启动中、停止。
 * 无响应是「尚未确认」的中间态：先提示、并保持重启入口可用，是否重启仍由引擎按
 * 不应答窗口判定，展示层不参与。
 */
internal fun dshStatusTextRes(
    dshReady: Boolean,
    dshError: Boolean,
    dshUnresponsive: Boolean,
    dshStarting: Boolean,
): Int = when {
    dshError -> R.string.home_dsh_error
    dshUnresponsive -> R.string.home_dsh_unresponsive
    dshReady -> R.string.home_dsh_ready
    dshStarting -> R.string.home_dsh_starting
    else -> R.string.home_dsh_stopped
}

@Composable
private fun DshStatusCard(
    dshReady: Boolean,
    dshStarting: Boolean,
    dshError: Boolean,
    dshUnresponsive: Boolean,
    sandboxRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(dshReady) {
        elapsedSeconds = 0L
        if (dshReady) {
            while (true) {
                delay(1_000)
                elapsedSeconds += 1
            }
        }
    }
    val uptime = remember(elapsedSeconds) {
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
    val statusText = stringResource(dshStatusTextRes(dshReady, dshError, dshUnresponsive, dshStarting))
    // 配色只随 异常 / 无响应 / 就绪 三档走；「启动中」只改文案，圆点与底色同「已停止」。
    val statusColor = when {
        dshError -> MaterialTheme.colorScheme.error
        dshUnresponsive -> MaterialTheme.colorScheme.error
        dshReady -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = when {
            dshError -> MaterialTheme.colorScheme.errorContainer
            dshReady -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            when {
                dshError -> MaterialTheme.colorScheme.error
                dshReady -> MaterialTheme.colorScheme.outlineVariant
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 头部：圆点 + 状态 + 右上角「浏览器」入口，三者同一行垂直居中——
            // 状态文字与按钮文案因此在同一基线上，不需要靠 offset/margin 硬凑对齐。
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, CircleShape),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // 「浏览器」入口：**必须带 token**，否则外部浏览器会停在鉴权页。
                    // 位于状态卡右上角，沿用与下方 启动/重启/关闭 完全相同的 OutlinedButton
                    // 变色逻辑（可用=主题色、不可用=灰，透明底 + 描边方框），不做深绿实底。
                    // 高度压到 28dp：既与状态行同高（不把卡片顶高），又满足 24dp 最小触达尺寸。
                    OutlinedButton(
                        shape = MaterialTheme.shapes.medium,
                        onClick = onOpenInBrowser,
                        enabled = dshReady,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Icon(
                            // 刻意保留的实心地球：与其它入口区分，不参与描边统一。
                            imageVector = Icons.Filled.Public,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.home_browser),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                // 地址 / 运行时长：左缘对齐状态文字（圆点 8dp + 间距 12dp = 20dp）。
                // 地址单行不折行：窄屏（320dp）或未来换成更长的地址时，宁可省略号也不要
                // 折行把卡片顶高（本卡刚按"更紧凑"调过）。
                Column(modifier = Modifier.padding(start = 20.dp)) {
                    Text(
                        text = Constants.DSH_BASE_URL,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.home_uptime_format, uptime),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 通用短词三按钮（启动/重启/关闭），纯文字无图标。
                StatusActionButton(
                    label = stringResource(R.string.action_start),
                    // 启动中不可点：管理器本来就幂等忽略重复启动，这里只是让可用性与真实状态一致。
                    enabled = sandboxRunning && !dshReady && !dshStarting,
                    onClick = onStart,
                )
                StatusActionButton(
                    label = stringResource(R.string.action_restart),
                    enabled = dshReady || sandboxRunning,
                    onClick = onRestart,
                )
                StatusActionButton(
                    label = stringResource(R.string.action_stop),
                    enabled = dshReady || dshError,
                    onClick = onStop,
                )
            }
        }
    }
}
