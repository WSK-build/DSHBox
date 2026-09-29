package com.dshbox.pluginmanager.market.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.filled.ArrowBack
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Close
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Refresh
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.content.Context
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.market.MarketRepository
import com.dshbox.pluginmanager.market.model.HostCompatibility
import com.dshbox.pluginmanager.market.model.InstalledPlugin
import com.dshbox.pluginmanager.market.model.MarketTab
import com.dshbox.pluginmanager.market.model.Registry
import com.dshbox.pluginmanager.market.model.RegistryFailure
import com.dshbox.pluginmanager.market.model.RegistryPlugin
import com.dshbox.pluginmanager.market.model.UpdateStatus
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * 已结束的操作记录最多留这么多条。
 *
 * 面板回答的是"当前发生了什么"，不是操作历史；不设上限的话，一次失败重试风暴
 * 就能把面板填满历史噪声（真机上就积攒到过 6 条，用户看到的是一屏红）。
 */
private const val MAX_SETTLED_OPERATIONS = 3

/** 丢弃最早的已结束记录。 */
private fun pruneSettledOperations(operations: MutableList<MarketOperation>) {
    val settled = operations.filter { it.state != MarketOperationState.RUNNING }
    if (settled.size <= MAX_SETTLED_OPERATIONS) return
    settled.take(settled.size - MAX_SETTLED_OPERATIONS).forEach { operations.remove(it) }
}

/**
 * 市场主页。
 *
 * 结构照上游的信息架构：返回栏（右侧常驻任务面板入口）+ 页签 + 搜索 + 列表，
 * 详情与安装确认是弹层，操作进度落在任务面板里。
 *
 * 状态全部用 `remember` + `LaunchedEffect` 从 [repository] 拉取，不引入新的
 * 架构层：模块里没有可用的 ViewModel 基类，为了这一个页面自造一层反而会让
 * 数据流更难追。
 *
 * 任务记录（[MarketOperation]）由本层持有——数据层只给 `onLine` 回调，不保存
 * 操作历史，所以"正在进行什么"这件事必须在 UI 侧有落点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(repository: MarketRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var tabIndex by rememberSaveable { mutableStateOf(MarketTab.DISCOVER.ordinal) }
    var query by rememberSaveable { mutableStateOf("") }

    var registry by remember { mutableStateOf<Registry?>(null) }
    var installed by remember { mutableStateOf<List<InstalledPlugin>>(emptyList()) }
    var installedFailure by remember { mutableStateOf<AppError?>(null) }
    var updates by remember { mutableStateOf<Map<String, UpdateStatus>>(emptyMap()) }
    var updatesFailure by remember { mutableStateOf<AppError?>(null) }
    var compatibility by remember { mutableStateOf<Map<String, HostCompatibility>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    // reloadKey：重新读一遍数据（磁盘真状态）。refreshKey：**显式刷新**，
    // 只有它变化时才允许绕过目录内存副本与更新检测缓存。两者分开是因为本地
    // 操作（启停、写备注、装完重载）原来也走 reloadKey，而 force 又跟 reloadKey
    // 绑定——于是每拨一次开关都要重下几 MB 的目录、重查一遍 npm。
    //
    // appliedRefreshKey 记录"上一次加载时用的是哪个 refreshKey"：`refreshKey > 0`
    // 这种写法会让**之后每一次**本地重载都重新联网，等于把 bug 换个地方。
    var reloadKey by remember { mutableStateOf(0) }
    var refreshKey by remember { mutableStateOf(0) }
    var appliedRefreshKey by remember { mutableStateOf(0) }
    // 纯本地重载标记：拨开关 / 写备注这类**不可能改变插件版本**的操作，重载时
    // 复用上一份更新结果，不重跑更新检测。装/卸/更新结束后的重载仍走完整检测
    // （版本可能真的变了，复用会显示过期的"有更新"）。
    var reloadIsLocal by remember { mutableStateOf(false) }
    // 更新检测是否正在跑：行内更新按钮据此禁用并显示"更新检测中"。
    var checkingUpdates by remember { mutableStateOf(false) }
    // 市场状态文件坏了：所有写入都会被拒，界面给用户一条「备份并重建」的出路。
    var stateBroken by remember { mutableStateOf(false) }

    val operations = remember { mutableStateListOf<MarketOperation>() }
    val notices = remember { mutableStateMapOf<String, String>() }

    var detailTarget by remember { mutableStateOf<RegistryPlugin?>(null) }
    var confirmTarget by remember { mutableStateOf<RegistryPlugin?>(null) }
    var showTasks by remember { mutableStateOf(false) }
    // 数据源选择页是否在显示。刻意用 remember（非 saveable）：它是瞬时的二级页，
    // 不该在配置变更后被"恢复"出来。
    var showCatalogSource by remember { mutableStateOf(false) }

    val registryFailure by repository.registryFailure.collectAsState()
    val catalogSource by repository.catalogSource.collectAsState()
    val busy = operations.any { it.state == MarketOperationState.RUNNING }
    val language = marketLanguage()
    // 正在进行的操作按种类落到名字集合上，卡片/行据此显示"安装中…/更新中…"，
    // 而不是把整页按钮统一置灰——用户能看出到底在忙哪一条。
    val runningNames = operations
        .filter { it.state == MarketOperationState.RUNNING }
        .groupBy({ it.kind }, { it.pluginName })
    val installingNames = runningNames[MarketOperationKind.INSTALL].orEmpty().toSet()
    val updatingNames = runningNames[MarketOperationKind.UPDATE].orEmpty().toSet()

    // 目录 / 已装 / 更新三项一起刷新：它们互相依赖（已装清单决定卡片上的"已装"
    // 标记，更新状态决定行上的按钮），分三次加载会让界面短暂自相矛盾。
    //
    // force 只看 refreshKey 是否**变化**：显式刷新才联网重取，本地操作只重读磁盘。
    LaunchedEffect(reloadKey, refreshKey) {
        val force = forceForLoad(refreshKey, appliedRefreshKey)
        appliedRefreshKey = refreshKey
        // 先读一次数据源选择、再取目录：用户选了非默认源时，否则会先用默认源
        // 白拉一遍网络，界面还会闪一下别人的内容。
        repository.loadCatalogSource()
        loading = true
        // 纯本地重载且非显式刷新时，复用上一份更新结果，**不重跑更新检测**：
        // 启停/写备注不会改变任何插件的版本，重算没有意义，还会在查询失败时
        // 留下几十秒的"检测中"空窗。
        val reuse = if (reloadIsLocal && !force) updates else null
        checkingUpdates = reuse == null
        val snapshot = loadMarket(repository, force = force, reuseUpdates = reuse)
        // 目录失败时保留上一份快照：网络抖动不该把用户正在看的目录清空，
        // 失败原因由 registryFailure 横幅给出。
        snapshot.registry?.let { registry = it }
        installed = snapshot.installed
        // 失败要**留着**：折叠成空列表会让界面显示"尚未安装社区插件"。
        installedFailure = snapshot.installedFailure
        updates = snapshot.updates
        updatesFailure = snapshot.updatesFailure
        loading = false
        checkingUpdates = false
    }

    /** 本地重载：只重读磁盘，复用上一份更新结果。 */
    fun reloadLocal() {
        reloadIsLocal = true
        reloadKey++
    }

    /** 完整重载：重读磁盘并重跑更新检测（版本可能变了的场合）。 */
    fun reloadFull() {
        reloadIsLocal = false
        reloadKey++
    }

    // 数据源选择页：整页替换市场本体，但**留在同一个 composable 里** ——
    // 市场自己的 remember 状态（列表位置、筛选条件、已加载的目录）因此不会丢，
    // 返回时就是原样，不需要重新拉一遍。
    if (showCatalogSource) {
        CatalogSourceScreen(
            current = catalogSource,
            onPick = { picked ->
                // 选择后**不返回**：用户可能接着要试另一个源，跳回去只会碍事。
                // 重载照常在后台发生 —— reloadKey 一变，市场的加载协程就会跑，
                // 与当前屏幕显示的是哪一页无关。
                if (picked != catalogSource) {
                    scope.launch {
                        when (val result = repository.setCatalogSource(picked)) {
                            // 取数点在 setCatalogSource 内部已经丢掉了上一个源的目录副本，
                            // 所以这里一次**普通**重载就会联网取新源 —— 不必去动 refreshKey。
                            is AppResult.Success -> reloadLocal()
                            is AppResult.Failure ->
                                snackbarHostState.showSnackbar(result.userText(context, language))
                        }
                    }
                }
            },
            onBack = { showCatalogSource = false },
        )
        return
    }

    // 宿主兼容性按批查询（数据层契约上限 64 个/批），一次把整个目录问完，
    // 免得滚动时才逐个加载、卡片上的徽章忽明忽暗。
    LaunchedEffect(registry) {
        val plugins = registry?.plugins.orEmpty()
        if (plugins.isEmpty()) return@LaunchedEffect
        val accumulator = mutableMapOf<String, HostCompatibility>()
        plugins.map { it.displayName }.distinct().chunked(COMPAT_BATCH).forEach { batch ->
            accumulator += repository.hostCompatibility(batch)
        }
        compatibility = accumulator
    }

    /** 执行（或重试）一次操作。同一时间只允许一个——数据层是串行的。 */
    fun launchOperation(operation: MarketOperation) {        if (operations.any { it.state == MarketOperationState.RUNNING && it !== operation }) {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.pm_market_busy_wait)) }
            return
        }
        if (operations.none { it === operation }) {
            // 同一个插件的**已结束**记录先撤掉：重试不该把上一次的失败留在面板里，
            // 否则每失败一次就多一条，面板很快就只剩一串历史噪声（真机上就是这么积攒的）。
            operations.removeAll { it !== operation && it.pluginName == operation.pluginName && it.state != MarketOperationState.RUNNING }
            operations.add(operation)
            pruneSettledOperations(operations)
        }
        // 一次性提示：这一条开始新操作了，上一次的提示就该消失，否则
        // "已停用，重启后生效"会一直挂在这一行上，与当前状态无关。
        notices.remove(operation.pluginName)
        operation.restart()
        showTasks = true
        scope.launch {
            val result = when (operation.kind) {
                MarketOperationKind.INSTALL -> repository.install(
                    pluginName = operation.pluginName,
                    installTarget = operation.installTarget.orEmpty(),
                    onLine = { operation.append(it) },
                )
                MarketOperationKind.UPDATE -> repository.update(
                    name = operation.pluginName,
                    onLine = { operation.append(it) },
                )
                MarketOperationKind.UNINSTALL -> repository.uninstall(
                    name = operation.pluginName,
                    onLine = { operation.append(it) },
                )
            }
            when (result) {
                is AppResult.Success -> operation.succeed()
                // 失败要带原因：只显示"失败"用户无从下手，也没法判断该不该重试。
                is AppResult.Failure -> operation.fail(result.userText(context, language))
            }
            // 成功与失败都要重载：失败也可能是"装了一半"，界面必须回到磁盘
            // 真状态，而不是停在操作前的快照上。装/卸/更新**可能改变了版本**，
            // 所以走完整重载（重跑更新检测），不能复用旧结论。
            reloadFull()
        }
    }

    fun installPlugin(plugin: RegistryPlugin) {
        launchOperation(
            MarketOperation(
                kind = MarketOperationKind.INSTALL,
                pluginName = plugin.displayName,
                installTarget = installTargetOf(plugin),
            ),
        )
    }

    fun uninstallPlugin(plugin: InstalledPlugin) {
        launchOperation(
            MarketOperation(kind = MarketOperationKind.UNINSTALL, pluginName = plugin.name),
        )
    }

    fun updatePlugin(plugin: InstalledPlugin) {
        launchOperation(
            MarketOperation(kind = MarketOperationKind.UPDATE, pluginName = plugin.name),
        )
    }

    fun setEnabled(plugin: InstalledPlugin, enabled: Boolean) {
        scope.launch {
            when (val result = repository.setEnabled(plugin.name, enabled)) {
                is AppResult.Success -> {
                    // 数据层只写层、不热重载，所以如实说"下次启动生效"。
                    notices[plugin.name] = context.getString(R.string.pm_market_toggle_restart_notice)
                }
                is AppResult.Failure -> {
                    notices[plugin.name] = result.userText(context, language)
                    // 状态文件坏了：给用户「备份并重建」的出路，否则开关永远存不住。
                    if (result.error.code == "STATE_UNREADABLE") stateBroken = true
                }
            }
            // 失败也要重载：这次操作可能已经在磁盘上留下了痕迹（层写成功、
            // 状态写失败），界面必须回到磁盘真状态。启停不会改变版本 → 本地重载。
            reloadLocal()
        }
    }

    fun saveNote(plugin: InstalledPlugin, note: String) {
        scope.launch {
            when (val result = repository.setNote(plugin.name, note)) {
                is AppResult.Success -> notices.remove(plugin.name)
                is AppResult.Failure -> {
                    notices[plugin.name] = result.userText(context, language)
                    if (result.error.code == "STATE_UNREADABLE") stateBroken = true
                }
            }
            reloadLocal()
        }
    }

    fun copyToClipboard(text: String) {
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.pm_copied)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.pm_market_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.pm_back),
                        )
                    }
                },
                actions = {
                    // 显式刷新：只有这里允许绕过目录内存副本与更新检测缓存。
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.pm_refresh),
                        )
                    }
                    MarketTaskEntry(
                        operations = operations,
                        onClick = { showTasks = true },
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tabIndex) {
                MarketTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = {
                            Text(
                                text = stringResource(
                                    when (tab) {
                                        MarketTab.DISCOVER -> R.string.pm_market_tab_discover
                                        MarketTab.INSTALLED -> R.string.pm_market_tab_installed
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            // 搜索只对目录类页签有意义；"已安装"按包名精确找，不需要模糊搜索。
            if (MarketTab.entries[tabIndex] != MarketTab.INSTALLED) {
                SearchField(
                    query = query,
                    onQueryChange = { query = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // 加载中的提示只看 loading：原先还要求 `registry == null &&
            // installed.isEmpty()`，于是"重载已装列表"这类本地刷新期间界面
            // 完全没有反馈，用户以为按钮没生效。
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.pm_market_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            registryFailure?.let { failure ->
                RegistryFailureBanner(
                    failure = failure,
                    language = language,
                    onRetry = { refreshKey++ },
                )
            }

            // 市场状态文件读不出来：开关与备注**存不住**。给两档动作：重试读一遍，
            // 或把损坏的原文件留档后重建一份只含我们字段的新文件。
            if (stateBroken) {
                MarketFailureBanner(
                    title = stringResource(R.string.pm_market_state_broken_title),
                    message = stringResource(R.string.pm_market_state_broken_message),
                    onRetry = { reloadFull() },
                    secondaryLabel = stringResource(R.string.pm_market_state_rebuild),
                    onSecondary = {
                        scope.launch {
                            when (val result = repository.rebuildState()) {
                                is AppResult.Success -> {
                                    stateBroken = false
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.pm_market_state_rebuilt),
                                    )
                                }
                                is AppResult.Failure ->
                                    snackbarHostState.showSnackbar(result.userText(context, language))
                            }
                            reloadFull()
                        }
                    },
                )
            }

            // 忙碌时按钮会置灰，必须同时说清原因，否则用户只看到一片点不动的按钮。
            if (busy) {
                Text(
                    text = stringResource(R.string.pm_market_busy_wait),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            val registrySnapshot = registry
            when (MarketTab.entries[tabIndex]) {
                MarketTab.DISCOVER -> if (registrySnapshot == null) {
                    // 三种"没有目录"的原因必须分开：还在加载 / 加载失败（原因在
                    // 上面的横幅里）/ 数据层根本没接上。都显示"正在加载…"会把
                    // 一个永久性的不可用状态伪装成"再等等就好"。
                    when {
                        // 加载中不再重复文案：上方全局横幅已经是"进度条 + 正在加载
                        // 插件目录…"，这里再写同一句就是同屏两份。留空是有意的，
                        // 与「已安装」页对齐（那边加载中也只剩全局横幅）。
                        loading -> Unit
                        registryFailure != null -> Unit
                        else -> MarketEmptyState(text = stringResource(R.string.pm_market_unavailable))
                    }
                } else {
                    DiscoverSection(
                        plugins = registrySnapshot.plugins,
                        categoryLabels = categoryLabels(registrySnapshot, language),
                        query = query,
                        compatibility = compatibility,
                        installedNames = installed.map { it.name }.toSet(),
                        installingNames = installingNames,
                        busy = busy,
                        onInstall = { confirmTarget = it },
                        onOpenDetail = { detailTarget = it },
                        catalogSource = catalogSource,
                        onOpenCatalogSource = { showCatalogSource = true },
                        modifier = Modifier.weight(1f),
                    )
                }

                MarketTab.INSTALLED -> InstalledSection(
                    plugins = installed,
                    updates = updates,
                    notices = notices,
                    updatingNames = updatingNames,
                    busy = busy,
                    loading = loading,
                    checkingUpdates = checkingUpdates,
                    // 失败原因原样传下去：列表位置要显示"读不出来 + 重试"，
                    // 而不是"尚未安装社区插件"。
                    loadFailure = installedFailure?.userText(context, language),
                    updatesFailure = updatesFailure?.userText(context, language),
                    onRetry = { reloadFull() },
                    onDismissNotice = { notices.remove(it.name) },
                    onSetEnabled = { plugin, enabled -> setEnabled(plugin, enabled) },
                    onUpdate = { updatePlugin(it) },
                    onUninstall = { uninstallPlugin(it) },
                    onSaveNote = { plugin, note -> saveNote(plugin, note) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    confirmTarget?.let { plugin ->
        InstallConfirmDialog(
            plugin = plugin,
            compatibility = compatibilityFor(plugin, compatibility),
            language = language,
            onConfirm = {
                confirmTarget = null
                installPlugin(plugin)
            },
            onDismiss = { confirmTarget = null },
        )
    }

    detailTarget?.let { plugin ->
        PluginDetailSheet(
            plugin = plugin,
            compatibility = compatibilityFor(plugin, compatibility),
            installed = isInstalled(plugin, installed),
            busy = busy,
            onInstall = { installPlugin(plugin) },
            onCopy = { copyToClipboard(it) },
            onDismiss = { detailTarget = null },
        )
    }

    // 详情与任务面板互斥：两个 ModalBottomSheet 同时挂着会互相盖住。
    if (showTasks) {
        MarketTaskPanel(
            operations = operations,
            onRetry = { launchOperation(it) },
                        onClearSettled = {
                            // 面板是"当前发生了什么"，不是操作历史：已结束的（含失败）
                            // 一律清掉，只留正在跑的。
                            operations.removeAll { it.state != MarketOperationState.RUNNING }
                        },
            onDismiss = { showTasks = false },
        )
    }
}

/**
 * 安装确认弹窗。
 *
 * 上游的确认 Modal 必须让用户在按下按钮前看到：安装命令、第三方代码提示、
 * 构建脚本默认被禁止。三者缺一，用户就不知道自己授权了什么。
 */
@Composable
private fun InstallConfirmDialog(
    plugin: RegistryPlugin,
    compatibility: HostCompatibility?,
    language: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val incompatible = compatibility?.status == HostCompatibility.Status.INCOMPATIBLE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pm_market_confirm_install)) },
        text = {
            Column {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MarketPluginSummary(
                    plugin = plugin,
                    language = language,
                    maxDescriptionLines = 4,
                    modifier = Modifier.padding(top = 6.dp),
                )
                CompatBadge(compatibility, modifier = Modifier.padding(top = 8.dp))
                if (incompatible) {
                    Text(
                        text = stringResource(R.string.pm_market_incompatible_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (plugin.deprecated) {
                    Text(
                        text = stringResource(R.string.pm_market_deprecated_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.pm_market_detail_cmd),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = installTargetOf(plugin),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.pm_market_confirm_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    text = stringResource(R.string.pm_market_detail_build_blocked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    text = stringResource(
                        if (incompatible) {
                            R.string.pm_market_detail_install_anyway
                        } else {
                            R.string.pm_market_confirm_install
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.pm_market_cancel))
            }
        },
    )
}

/**
 * 目录加载失败横幅。
 *
 * 上游刻意不把网络失败回喂成旧目录，我们也不隐藏失败；同时必须给出**具体
 * 原因 + 尝试次数 + 耗时**，否则"加载失败"四个字让用户无从判断是网络、镜像
 * 还是目录本身坏了。
 */
@Composable
private fun RegistryFailureBanner(
    failure: RegistryFailure,
    language: String,
    onRetry: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.pm_market_load_failed_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = localizeBilingual(failure.message, language),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = stringResource(R.string.pm_market_load_failed_attempts, failure.attempts) +
                    " · " +
                    stringResource(R.string.pm_market_load_failed_elapsed, failure.elapsedMs),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.pm_market_load_retry))
                }
            }
        }
    }
}

/**
 * 搜索框：**窄条**，无占位文案。
 *
 * 上游是 commit 式（回车才搜）；移动端键盘小、来回切输入法更烦，所以这里改成
 * 边打边筛（输入即生效，同时保留 IME 搜索键触发一次重载）。这是本轮有意做的
 * 移动端简化。
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_search),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQueryChange(query) }),
                modifier = Modifier.weight(1f),
            )
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
                    contentDescription = stringResource(R.string.pm_market_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onQueryChange("") },
                )
            }
        }
    }
}

/**
 * 数据加载失败横幅（目录之外的加载项共用）。
 *
 * 与目录失败横幅同一套视觉：**原因 + 重试**。失败不能只留一句"加载失败"，
 * 也不能折叠成空列表——"已装清单读不出来"与"一个插件都没装"在用户眼里
 * 是天差地别的两件事。
 */
@Composable
internal fun MarketFailureBanner(
    title: String,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    /** 第二档动作（如「备份并重建状态文件」）；为 null 时不渲染该按钮。 */
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (secondaryLabel != null && onSecondary != null) {
                    OutlinedButton(onClick = onSecondary) {
                        Text(secondaryLabel)
                    }
                }
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.pm_market_load_retry))
                }
            }
        }
    }
}

// ────────────────────────────── 辅助 ──────────────────────────────

/** 宿主兼容性一次查询的批上限，与数据层契约一致。 */
private const val COMPAT_BATCH = 64

/** 失败文案：优先用领域层给的可本地化文案，回退到原始 message 并**择半双语**。 */
private fun AppResult.Failure.userText(context: Context, language: String): String =
    error.userText(context, language)

private fun AppError.userText(context: Context, language: String): String =
    // 资源文案（UiText.Res）已按界面语言取好，不再动它；领域层的
    // "中文 / English" 双语串必须在这里择半，否则横幅会把两半都显示出来。
    userMessage?.asString(context) ?: localizeBilingual(message, language)

/**
 * 目录里的一条是否已装。
 *
 * 目录身份是 npm 名（`displayName`），而已装清单读的是 profile manifest 里的
 * 包名——上游为此专门写过"同一个插件两边的名字不一样"的注释，所以两种都认。
 */
private fun isInstalled(plugin: RegistryPlugin, installed: List<InstalledPlugin>): Boolean =
    installed.any { it.name == plugin.displayName || it.name == plugin.name }

/**
 * 分类键 → 本地化标签。
 *
 * 目录的 `categories` 是 `键 → (语言 → 标签)`；缺标签时用键本身兜底，缺语言时
 * 退到 `en`。同时把插件里出现、但目录没登记的分类补进来，免得那些分类在筛选
 * 行里点不到。
 */
private fun categoryLabels(registry: Registry?, language: String): Map<String, String> {
    if (registry == null) return emptyMap()
    val labels = linkedMapOf<String, String>()
    registry.categories.forEach { (key, translations) ->
        labels[key] = translations[language] ?: translations["en"] ?: translations.values.firstOrNull() ?: key
    }
    registry.plugins.forEach { plugin ->
        plugin.categories.forEach { key -> labels.getOrPut(key) { key } }
    }
    return labels
}
