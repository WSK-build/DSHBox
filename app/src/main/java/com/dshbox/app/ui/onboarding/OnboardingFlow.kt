package com.dshbox.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.DebianSources
import com.dshbox.app.common.NodeSources
import com.dshbox.app.runtime.OnlineImportState
import com.dshbox.app.runtime.OnlineRuntimeImportManager
import com.dshbox.app.sandbox.RuntimeProfile
import com.dshbox.app.sandbox.SandboxConfig
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.app.common.R as CommonR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** 引导的五页。 */
private enum class OnboardingPage { WELCOME, DEBIAN, NODE, WEBVIEW, DONE }

/** 引导的两层（DSH 随 APK 打包，不参与安装）。 */
internal enum class OnboardingLayer { DEBIAN, NODE }

/** 层状态机：待安装 / 安装中 / 已就绪 / 失败 / 已跳过。 */
internal enum class OnboardingLayerStatus { PENDING, INSTALLING, DONE, FAILED, SKIPPED }

/**
 * 新手引导根组合：五页状态机（欢迎 → Debian 层 → Node 层 → WebView 检测 → 结束页）。
 *
 * 只负责编排与状态归属，不碰底层安装能力：探测/安装/取消全部经
 * [OnlineRuntimeImportManager]（其安装跑在独立作用域，离开页面不中断）；层是否已
 * 安装一律以 manager 的实时文件系统探测为准，两页共享一份状态，避免
 * Node 页与 Debian 页各自缓存漂移。WebView 检测页只读内核版本信息并给出建议，
 * 不参与层状态机，也不阻塞出口。
 *
 * 出口语义两分：右上角「跳过引导」结束整个引导并取消在途安装（用户明说不装）；
 * 底部「下一步」只跳过当前层（后台安装继续），由后置页阻塞卡或结束页兜底承载。
 */
@Composable
fun OnboardingFlow(
    sandboxManager: SandboxManager,
    importManager: OnlineRuntimeImportManager,
    sandboxConfig: SandboxConfig,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(OnboardingPage.WELCOME) }
    var debianStatus by remember { mutableStateOf(OnboardingLayerStatus.PENDING) }
    var nodeStatus by remember { mutableStateOf(OnboardingLayerStatus.PENDING) }
    // 探测中的上报：源区骨架用。真正发起探测的协程在安装页内，随页取消。
    var debianProbing by remember { mutableStateOf(false) }
    var nodeProbing by remember { mutableStateOf(false) }
    var showSkipDialog by remember { mutableStateOf(false) }
    // 宿主回调：跳过与完成都终结引导，仅上报，不在此处写标志（由挂载点统一写）。
    val finish by rememberUpdatedState(onFinish)
    // 阻塞卡上的「返回安装」需要异步探一次文件系统，故取一个组合作用域发起。
    val scope = rememberCoroutineScope()

    val debianState = importManager.debianState.collectAsState().value
    val nodeState = importManager.nodeState.collectAsState().value
    // 源名/源说明是 UiText，须在非组合的 nameOf 回调里解析：用成员函数 asString(context)
    // （组合内的 @Composable 重载不能出现在普通 lambda 中）。
    val context = LocalContext.current

    // 实时对齐两层状态机：结果/在途变化时重探一次文件系统与结果流——装好的层
    // 直接置 DONE（进入引导时的「已装好直接放行」亦走这条），失败置 FAILED，
    // 在途置 INSTALLING，其余回落待安装，不留残态。键刻意取 running/result 两个
    // 标量而非整个 state：日志滚动会改变 state，整对象作键会让这里反复重探文件
    // 系统（无意义的主线程↔IO 往返）。
    LaunchedEffect(debianState.running, debianState.result, nodeState.running, nodeState.result) {
        debianStatus = nextLayerStatus(
            current = debianStatus,
            installed = debianLayerInstalled(sandboxConfig),
            state = debianState,
        )
        nodeStatus = nextLayerStatus(
            current = nodeStatus,
            installed = nodeLayerInstalled(importManager),
            state = nodeState,
        )
    }

    // 系统返回键逐页接管：欢迎页吞掉返回（引导尚未开始，无处可退）；两层安装页
    // 各自回上一页（与「返回」按钮同语义）；WebView 页回 Node 页；结束页退出引导
    // （等同「进入 DSHBox」）。
    BackHandler(enabled = true) {
        when (page) {
            OnboardingPage.WELCOME -> Unit
            OnboardingPage.DEBIAN -> page = OnboardingPage.WELCOME
            OnboardingPage.NODE -> page = OnboardingPage.DEBIAN
            OnboardingPage.WEBVIEW -> page = OnboardingPage.NODE
            OnboardingPage.DONE -> finish()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (page) {
            OnboardingPage.WELCOME -> WelcomePage(
                onStart = { page = OnboardingPage.DEBIAN },
                onSkip = { showSkipDialog = true },
            )

            OnboardingPage.DEBIAN -> OnboardingInstallPage(
                step = 1,
                title = stringResource(R.string.onboarding_debian_title),
                desc = stringResource(R.string.onboarding_debian_desc),
                debianStatus = debianStatus,
                nodeStatus = nodeStatus,
                activeLayer = OnboardingLayer.DEBIAN,
                activeProbing = debianProbing,
                onProbingChange = { debianProbing = it },
                state = debianState,
                sources = DebianSources.ALL,
                urlOf = { it.url },
                nameOf = { it.name.asString(context) },
                onProbe = { cb -> importManager.probeDebianSources(cb) },
                onStart = { ordered ->
                    debianStatus = OnboardingLayerStatus.INSTALLING
                    importManager.startDebianImport(ordered)
                },
                blockedText = null,
                onBlockedAction = {},
                onBack = { page = OnboardingPage.WELCOME },
                onNext = {
                    // 「下一步」= 跳过本层：仅 UI 标记，后台安装不取消。
                    debianStatus = OnboardingLayerStatus.SKIPPED
                    page = OnboardingPage.NODE
                },
                onContinue = { page = OnboardingPage.NODE },
                onSkip = { showSkipDialog = true },
            )

            OnboardingPage.NODE -> OnboardingInstallPage(
                step = OnboardingTotalSteps,
                title = stringResource(R.string.onboarding_node_title),
                desc = stringResource(R.string.onboarding_node_desc),
                debianStatus = debianStatus,
                nodeStatus = nodeStatus,
                activeLayer = OnboardingLayer.NODE,
                activeProbing = nodeProbing,
                onProbingChange = { nodeProbing = it },
                state = nodeState,
                sources = NodeSources.ALL,
                urlOf = { it.url },
                nameOf = { it.name.asString(context) },
                onProbe = { cb -> importManager.probeNodeSources(cb) },
                onStart = { ordered ->
                    nodeStatus = OnboardingLayerStatus.INSTALLING
                    importManager.startNodeImport(ordered)
                },
                blockedText = nodeBlockedText(debianStatus = debianStatus, debianRunning = debianState.running),
                onBlockedAction = {
                    // 返回安装：若 Debian 层实际已装好（后台刚完成）则直接放行，不重装；
                    // 否则回 Debian 页由其重开安装。DONE 置位后本页阻塞卡即撤销。
                    scope.launch {
                        if (debianLayerInstalled(sandboxConfig)) {
                            debianStatus = OnboardingLayerStatus.DONE
                        } else {
                            debianStatus = OnboardingLayerStatus.PENDING
                            page = OnboardingPage.DEBIAN
                        }
                    }
                },
                onBack = { page = OnboardingPage.DEBIAN },
                onNext = {
                    nodeStatus = OnboardingLayerStatus.SKIPPED
                    page = OnboardingPage.WEBVIEW
                },
                onContinue = { page = OnboardingPage.WEBVIEW },
                onSkip = { showSkipDialog = true },
            )

            OnboardingPage.WEBVIEW -> OnboardingWebviewPage(
                onBack = { page = OnboardingPage.NODE },
                onNext = { page = OnboardingPage.DONE },
                onContinue = { page = OnboardingPage.DONE },
                onSkip = { showSkipDialog = true },
            )

            OnboardingPage.DONE -> DonePage(
                debianStatus = debianStatus,
                nodeStatus = nodeStatus,
                onEnter = { finish() },
            )
        }
    }

    if (showSkipDialog) {
        val busy = debianState.running || nodeState.running || debianProbing || nodeProbing
        if (busy) {
            // 在途任务：弹确认框。破坏性出口「跳过并进入」放在 confirmButton 位，
            // 非破坏性的「继续配置」放在 dismissButton 位；未显式指定初始焦点，
            // 焦点位置由 Material3 按 confirmButton / dismissButton 的次序决定。
            AlertDialog(
                onDismissRequest = { showSkipDialog = false },
                title = { Text(stringResource(R.string.onboarding_skip_dialog_title)) },
                text = { Text(stringResource(R.string.onboarding_skip_dialog_msg)) },
                confirmButton = {
                    TextButton(onClick = {
                        showSkipDialog = false
                        cancelAll(importManager)
                        finish()
                    }) {
                        Text(stringResource(R.string.onboarding_skip_dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showSkipDialog = false }) {
                        Text(stringResource(R.string.onboarding_skip_dialog_cancel))
                    }
                },
            )
        } else {
            // 无在途任务：跳过即退出，无需确认。副作用移出组合体执行。
            LaunchedEffect(Unit) {
                showSkipDialog = false
                cancelAll(importManager)
                finish()
            }
        }
    }
}

/**
 * 单层的下一状态：文件系统事实优先（装好即就绪），其次失败、在途、回落。
 * 回落只针对曾进入 就绪/失败/安装中 的层，避免抹掉「已跳过」这一 UI 语义。
 */
internal fun nextLayerStatus(
    current: OnboardingLayerStatus,
    installed: Boolean,
    state: OnlineImportState,
): OnboardingLayerStatus = when {
    installed -> OnboardingLayerStatus.DONE
    state.result is AppResult.Failure -> OnboardingLayerStatus.FAILED
    state.running -> OnboardingLayerStatus.INSTALLING
    current == OnboardingLayerStatus.DONE ||
        current == OnboardingLayerStatus.FAILED ||
        current == OnboardingLayerStatus.INSTALLING -> OnboardingLayerStatus.PENDING
    else -> current
}

/** 欢迎页：品牌图标 + 标题 + 副标题 + 三条隐私/权限说明。 */
@Composable
private fun WelcomePage(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = OnboardingContentMaxWidth)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 品牌标记必须用 Image 而不是 Icon：Icon 会按 tint 单色化，品牌绿会丢失。
                Image(
                    painter = painterResource(R.drawable.ic_splash_mark),
                    contentDescription = null,
                    modifier = Modifier
                        .size(welcomeMarkSize())
                        .clip(MaterialTheme.shapes.large),
                )
                Text(
                    text = stringResource(R.string.onboarding_welcome_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.onboarding_welcome_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FeatureRow(
                    icon = CommonR.drawable.ic_archive,
                    title = stringResource(R.string.onboarding_feature_local_title),
                    desc = stringResource(R.string.onboarding_feature_local_desc),
                )
                FeatureRow(
                    icon = CommonR.drawable.ic_lock,
                    title = stringResource(R.string.onboarding_feature_privacy_title),
                    desc = stringResource(R.string.onboarding_feature_privacy_desc),
                )
                FeatureRow(
                    icon = CommonR.drawable.ic_world,
                    title = stringResource(R.string.onboarding_feature_network_title),
                    desc = stringResource(R.string.onboarding_feature_network_desc),
                )
            }
        }

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = OnboardingContentMaxWidth)
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 12.dp),
        ) {
            Text(stringResource(R.string.onboarding_start))
        }
    }
}

@Composable
private fun FeatureRow(icon: Int, title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = ImageVector.vectorResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 结束页双态：两层就绪 = 对勾环 + 两步启动指引；否则 = 灰警示环 + 缺失层兜底文案。
 * 就绪与否直接读根组合持有的实时层状态（文件系统事实优先），后台仍在装的会随
 * 状态自愈为就绪态，不必在本页二次探测。
 */
@Composable
private fun DonePage(
    debianStatus: OnboardingLayerStatus,
    nodeStatus: OnboardingLayerStatus,
    onEnter: () -> Unit,
) {
    val ready = debianStatus == OnboardingLayerStatus.DONE &&
        nodeStatus == OnboardingLayerStatus.DONE
    val separator = stringResource(R.string.onboarding_list_separator)
    // 未就绪分两档：安装中（后台推进，完成即自动就绪）与尚未安装/失败/已跳过。
    val installing = buildList {
        if (debianStatus == OnboardingLayerStatus.INSTALLING) {
            add(stringResource(R.string.onboarding_layer_debian))
        }
        if (nodeStatus == OnboardingLayerStatus.INSTALLING) {
            add(stringResource(R.string.onboarding_layer_node))
        }
    }.joinToString(separator)
    val missing = buildList {
        if (debianStatus != OnboardingLayerStatus.DONE &&
            debianStatus != OnboardingLayerStatus.INSTALLING
        ) {
            add(stringResource(R.string.onboarding_layer_debian))
        }
        if (nodeStatus != OnboardingLayerStatus.DONE &&
            nodeStatus != OnboardingLayerStatus.INSTALLING
        ) {
            add(stringResource(R.string.onboarding_layer_node))
        }
    }.joinToString(separator)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = OnboardingContentMaxWidth)
                    .padding(top = 48.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DoneRing(ready = ready)
                Text(
                    text = if (ready) {
                        stringResource(R.string.onboarding_done_title)
                    } else {
                        stringResource(R.string.onboarding_done_partial_title)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (ready) {
                        stringResource(R.string.onboarding_done_sub)
                    } else {
                        buildList {
                            if (missing.isNotEmpty()) {
                                add(
                                    stringResource(R.string.onboarding_done_partial_msg, missing),
                                )
                            }
                            if (installing.isNotEmpty()) {
                                add(
                                    stringResource(
                                        R.string.onboarding_done_installing_fmt,
                                        installing,
                                    ),
                                )
                            }
                        }.joinToString(" ")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ready) {
                    HintCard(
                        number = 1,
                        title = stringResource(R.string.onboarding_done_hint1_title),
                        desc = stringResource(R.string.onboarding_done_hint1_desc),
                    )
                    HintCard(
                        number = 2,
                        title = stringResource(R.string.onboarding_done_hint2_title),
                        desc = stringResource(R.string.onboarding_done_hint2_desc),
                    )
                }
            }
        }

        Button(
            onClick = onEnter,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = OnboardingContentMaxWidth)
                .align(Alignment.CenterHorizontally)
                .padding(vertical = 12.dp),
        ) {
            Text(stringResource(R.string.onboarding_done_enter))
        }
    }
}

/** 结束页图形：就绪 = 品牌对勾环；未就绪 = 灰警示环。 */
@Composable
private fun DoneRing(ready: Boolean) {
    val icon = if (ready) CommonR.drawable.ic_circle_check else CommonR.drawable.ic_alert_triangle
    val tint = if (ready) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(3.dp, tint),
    ) {
        Box(modifier = Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = ImageVector.vectorResource(icon),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(44.dp),
            )
        }
    }
}

@Composable
private fun HintCard(number: Int, title: String, desc: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 欢迎页图标边长：屏幕短边 ×28%，夹在 72–112dp（对齐 LaunchScreen.markSize 的缩放惯例）。 */
@Composable
private fun welcomeMarkSize(): Dp {
    val cfg = LocalConfiguration.current
    val shortSide = min(cfg.screenWidthDp, cfg.screenHeightDp)
    return (shortSide * 0.28f).dp.coerceIn(72.dp, 112.dp)
}

/** Node 页阻塞文案：按 Debian 层的实际状态分三档（后台安装中 / 失败 / 已跳过）。 */
@Composable
private fun nodeBlockedText(
    debianStatus: OnboardingLayerStatus,
    debianRunning: Boolean,
): String? {
    if (debianStatus == OnboardingLayerStatus.DONE) return null
    return when {
        debianStatus == OnboardingLayerStatus.INSTALLING || debianRunning ->
            stringResource(R.string.onboarding_node_blocked_installing)
        debianStatus == OnboardingLayerStatus.FAILED ->
            stringResource(R.string.onboarding_node_blocked_failed)
        else -> stringResource(R.string.onboarding_node_blocked_skipped)
    }
}

/** 结束整个引导：取消两层在途安装并清结果（用户明确表示不装）。 */
private fun cancelAll(importManager: OnlineRuntimeImportManager) {
    importManager.cancelDebianImport()
    importManager.cancelNodeImport()
    importManager.clearDebianResult()
    importManager.clearNodeResult()
}

/**
 * Debian 层是否在位：**层级判定**——base 层目录在位、runtime-profile.json 可解析、
 * 且 profile 中 base 项的哨兵校验通过（IO 线程查询）。半成品（base 目录在位但
 * profile 缺失）判为未装，引导据此引导用户重装（重装时 body 替换会覆盖半成品）。
 *
 * 不用 [SandboxManager.isRuntimeInstalled]——那是**装配级**判定，会连同 node 与
 * android-side 一起校验：node 层损坏会被误判为「Debian 未就绪」，引导让用户
 * 重装 Debian（实际坏的是 node）。也不用
 * [OnlineRuntimeImportManager.isDebianLayerInstalled]——那是「装没装过」的宽松
 * 口径（设置页检测口专用），会让 profile 缺失的半成品被判为已装。
 */
private suspend fun debianLayerInstalled(
    sandboxConfig: SandboxConfig,
): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val currentDir = File(sandboxConfig.runtimeDir, "runtime-current")
        val baseDir = File(currentDir, "base")
        if (!baseDir.isDirectory) return@runCatching false
        val profile = RuntimeProfile.parse(File(currentDir, "runtime-profile.json"))
            ?: return@runCatching false
        val expected = profile.layer("base")?.sha256?.takeIf { it.isNotBlank() }
            ?: return@runCatching false
        val sentinel = File(File(baseDir, ".dshbox"), "layer-base.sha256")
        if (!sentinel.isFile) return@runCatching false
        sentinel.readText().trim().equals(expected, ignoreCase = true)
    }.getOrDefault(false)
}

/** Node 层是否在位：以 manager 的文件系统事实为准（IO 线程查询）。 */
private suspend fun nodeLayerInstalled(
    importManager: OnlineRuntimeImportManager,
): Boolean = withContext(Dispatchers.IO) {
    runCatching { importManager.isNodeLayerInstalled() }.getOrDefault(false)
}
