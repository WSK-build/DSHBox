package com.dshbox.pluginmanager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.market.MarketRepository
import com.dshbox.pluginmanager.safety.PluginSafetyMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 模块内部的页面标识。 */
private enum class PanelRoute { PANEL, OFFICIAL, LOAD_LOG, REPAIR, ISOLATION, MARKET }

/**
 * 插件管理模块的入口。
 *
 * 首页那张入口卡点进来就是这里——**不放设置页**：它是排障入口，
 * 出事时用户应该在看到首页的第一屏就能进。
 *
 * 文件读写（层、状态、日志）一律走 IO 线程：这个模块的每个动作都会碰磁盘，
 * 在组合或点击回调里同步做会把界面卡住。
 *
 * 市场界面由调用方注入（[marketContent]）：宿主只负责把模块接进导航，
 * 市场界面与数据层各自独立演进。
 */
@Composable
fun PluginManagerHost(
    safety: PluginSafetyMode,
    market: MarketRepository,
    adapter: com.dshbox.pluginmanager.core.AdapterPluginController,
    official: com.dshbox.pluginmanager.core.OfficialPluginController,
    repair: com.dshbox.pluginmanager.repair.OpenCodeToolController,
    scope: CoroutineScope,
    onBack: () -> Unit,
    /** 子页（非面板根）可见性上报：宿主据此把内嵌区域提升为整屏面板。 */
    onSubPageVisibilityChanged: (Boolean) -> Unit = {},
    marketContent: @Composable (onBack: () -> Unit) -> Unit,
) {
    var route by rememberSaveable { mutableStateOf(PanelRoute.PANEL) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val state by safety.state.collectAsState()

    // 子页可见性上报：宿主收到后把内嵌区域提升为整屏（盖过品牌栏/页签行/底部导航）。
    // 路由变更一律走 setRoute——上报与跳转在同一个事件帧落地，宿主不会先画一帧
    // 「壳内版式」的子页再跳到整屏（那一下跳动很扎眼）。SideEffect 只兜底首次组合与
    // 重建恢复（route 是 rememberSaveable）；按边沿触发，值没变不写宿主状态。
    var reportedSubPage by remember { mutableStateOf<Boolean?>(null) }
    fun reportSubPage(visible: Boolean) {
        if (reportedSubPage != visible) {
            reportedSubPage = visible
            onSubPageVisibilityChanged(visible)
        }
    }
    fun setRoute(next: PanelRoute) {
        route = next
        reportSubPage(next != PanelRoute.PANEL)
    }
    SideEffect { reportSubPage(route != PanelRoute.PANEL) }

    // 系统返回键：子页回面板、面板退出模块。没有它，用户在排障页按返回会直接
    // 结束 Activity——而这一页正是 DSH 起不来时唯一的入口。
    BackHandler(enabled = true) {
        if (route != PanelRoute.PANEL) {
            setRoute(PanelRoute.PANEL)
        } else {
            onBack()
        }
    }

    // 整屏提升时底部导航已由宿主收起，本层自行补手势条让位；
    // 内嵌（壳层可见）时由宿主底部导航让位，不再叠加。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (LocalPluginPanelFullscreen.current) {
                    Modifier.navigationBarsPadding()
                } else {
                    Modifier
                },
            ),
    ) {
        when (route) {
            PanelRoute.PANEL -> PluginPanelScreen(
                adapter = adapter,
                state = state,
                busy = busy,
                statusLine = status,
                onToggleSafety = { enabled ->
                    scope.launch {
                        withContext(Dispatchers.IO) { safety.setSafetyMode(enabled) }
                    }
                },
                onToggleAbsolute = { enabled ->
                    scope.launch {
                        busy = true
                        status = null
                        val result = safety.setAbsoluteMode(enabled, onEvent = { line -> status = line })
                        status = when (result) {
                            is AppResult.Success -> null
                            is AppResult.Failure -> result.error.message
                        }
                        busy = false
                    }
                },
                onOpenOfficial = { setRoute(PanelRoute.OFFICIAL) },
                onOpenLoadLog = { setRoute(PanelRoute.LOAD_LOG) },
                onOpenRepair = { setRoute(PanelRoute.REPAIR) },
                onOpenIsolation = { setRoute(PanelRoute.ISOLATION) },
                onOpenMarket = { setRoute(PanelRoute.MARKET) },
            )

            PanelRoute.OFFICIAL -> OfficialPluginsScreen(
                controller = official,
                onBack = { setRoute(PanelRoute.PANEL) },
            )

            PanelRoute.LOAD_LOG -> {
                // 日志读取放 IO：整份日志在组合里同步读会拖慢首帧。保留策略在写入侧
                // （按启动分段、只留最近若干段），这里只读一次。
                val logText by produceState(initialValue = "", safety) {
                    value = withContext(Dispatchers.IO) { safety.readBootLog() }
                }
                LoadLogScreen(text = logText, onBack = { setRoute(PanelRoute.PANEL) })
            }

            PanelRoute.REPAIR -> com.dshbox.pluginmanager.repair.RepairScreen(
                controller = repair,
                onBack = { setRoute(PanelRoute.PANEL) },
            )

            PanelRoute.ISOLATION -> IsolationListScreen(
                records = state.isolated,
                busy = busy,
                onRestore = { id ->
                    scope.launch {
                        busy = true
                        val result = withContext(Dispatchers.IO) { safety.restorePlugin(id) }
                        status = when (result) {
                            is AppResult.Success -> null
                            is AppResult.Failure -> result.error.message
                        }
                        busy = false
                    }
                },
                onBack = { setRoute(PanelRoute.PANEL) },
            )

            PanelRoute.MARKET -> marketContent { setRoute(PanelRoute.PANEL) }
        }
    }
}
