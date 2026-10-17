package com.dshbox.app.ui.onboarding

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.common.AppResult
import com.dshbox.app.runtime.OnlineImportState
import com.dshbox.app.runtime.OnlineSourceProbe
import com.dshbox.app.ui.asString
import com.dshbox.app.common.R as CommonR

/** 引导 eyebrow 的步进总数：Debian/Node 两层安装 + WebView 检测各占一步（欢迎页与结束页不计数）。 */
internal const val OnboardingTotalSteps = 3

/** 内容列最大宽度：大屏下居中收窄，避免正文行长过宽。 */
internal val OnboardingContentMaxWidth = 600.dp

/** 步进指示：五个圆点，已完成/当前点按当前步高亮，纯装饰（语义由 eyebrow 文案承担）。 */
@Composable
internal fun OnboardingStepDots(currentIndex: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(5) { index ->
            val active = index <= currentIndex
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        CircleShape,
                    ),
            )
        }
    }
}


/**
 * 新手引导的安装页模板（Debian / Node.js 两个实例共用）。
 *
 * 一页承载本层的完整安装编排：并行探测全部镜像源 → 预选「含所需内容且延迟最低」
 * 的源 → 自动开装 → 阶段文案与进度（底层只有阶段文本、无百分比，故用不确定态
 * 进度条，不伪造数值）→ 失败可重试、可手选源重开。
 *
 * 探测与选中状态是页内的：探测协程随本页组合生命周期启停，离开页面即取消，
 * 不留悬空状态；而**安装**由 manager 的独立作用域推进，离开页面不中断——页只
 * 负责发起与呈现。两层的状态由 [OnboardingFlow] 持有并传入，保证 Node 页能看
 * 到 Debian 页的实时态。
 */
@Composable
internal fun <S> OnboardingInstallPage(
    step: Int,
    title: String,
    desc: String,
    debianStatus: OnboardingLayerStatus,
    nodeStatus: OnboardingLayerStatus,
    activeLayer: OnboardingLayer,
    activeProbing: Boolean,
    onProbingChange: (Boolean) -> Unit,
    state: OnlineImportState,
    sources: List<S>,
    urlOf: (S) -> String,
    nameOf: (S) -> String,
    onProbe: suspend ((S, OnlineSourceProbe) -> Unit) -> Unit,
    onStart: (List<S>) -> Unit,
    /** 非空时展示前置阻塞卡（Node 页在 Debian 层未就绪时），并隐藏源区与进度区。 */
    blockedText: String?,
    onBlockedAction: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val probes = remember { mutableStateMapOf<String, OnlineSourceProbe>() }
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var probeDone by remember { mutableStateOf(false) }
    // 重试以自增触发：失败后选中源与探测结果都不变，靠它重新发起同一份安装。
    var retryTick by remember { mutableIntStateOf(0) }

    val blocked = blockedText != null
    val activeReady = if (activeLayer == OnboardingLayer.DEBIAN) {
        debianStatus == OnboardingLayerStatus.DONE
    } else {
        nodeStatus == OnboardingLayerStatus.DONE
    }
    // 前置条件：Debian 页只需本层未就绪；Node 页还要求 Debian 层已就绪。
    val gateOpen = if (activeLayer == OnboardingLayer.DEBIAN) {
        !activeReady
    } else {
        debianStatus == OnboardingLayerStatus.DONE && !activeReady
    }

    // 进页即探测（阻塞时不探测：源区整体隐藏，探测无意义）。探测协程挂在页的
    // 生命周期上，离开页面自动取消；finally 保证上报的「探测中」不会被取消卡住。
    LaunchedEffect(blocked) {
        if (blocked) return@LaunchedEffect
        probes.clear()
        selectedUrl = null
        probeDone = false
        onProbingChange(true)
        try {
            onProbe { source, probe ->
                val url = urlOf(source)
                probes[url] = probe
                // 预选：可达且含所需内容中延迟最低者（不覆盖用户后续手选）。
                val current = selectedUrl?.let { probes[it] }
                if (probe.reachable && probe.hasTarget &&
                    (current == null || !current.hasTarget || probe.latencyMs < current.latencyMs)
                ) {
                    selectedUrl = url
                }
            }
        } finally {
            onProbingChange(false)
        }
        probeDone = true
    }

    // 前置满足 + 探测完成 → 以当前选中源开装（排序后的全清单，选中源排第一，
    // 与设置页同一底层语义）。安装中源选择锁定（startDebianImport 在途时会早退），
    // 失败重试与尚未开装时换源都会经本效果重开一次。
    LaunchedEffect(gateOpen, probeDone, selectedUrl, retryTick) {
        if (!gateOpen || !probeDone) return@LaunchedEffect
        val chosen = selectedUrl ?: return@LaunchedEffect
        onStart(sources.sortedBy { urlOf(it) != chosen })
    }

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
            OnboardingStepDots(currentIndex = step)
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
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_step_eyebrow, step, OnboardingTotalSteps),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                LayerStack(
                    debianStatus = debianStatus,
                    nodeStatus = nodeStatus,
                    activeLayer = activeLayer,
                )

                if (blocked) {
                    BlockedCard(text = blockedText.orEmpty(), onAction = onBlockedAction)
                } else {
                    SourcesSection(
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        probing = activeProbing || !probeDone,
                        locked = state.running,
                        sourceCount = sources.size,
                        selectedUrl = selectedUrl,
                        probes = probes,
                        sources = sources,
                        urlOf = urlOf,
                        nameOf = nameOf,
                        onSelect = { url -> selectedUrl = url },
                    )
                    ProgressSection(state = state)
                    ErrorSection(state = state, onRetry = { retryTick++ })
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 「返回」「下一步」为 ghost 描边风格（同首页状态卡按钮），「继续」为实心主色。
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_back))
            }
            OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_next))
            }
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f),
                enabled = activeReady,
            ) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    }
}

/** 层叠卡：自上而下 Debian → Node，与安装顺序一致（DSH 随 APK 打包，不参与安装）。 */
@Composable
private fun LayerStack(
    debianStatus: OnboardingLayerStatus,
    nodeStatus: OnboardingLayerStatus,
    activeLayer: OnboardingLayer,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LayerCard(
            label = stringResource(R.string.onboarding_layer_debian),
            status = debianStatus,
            active = activeLayer == OnboardingLayer.DEBIAN,
        )
        LayerCard(
            label = stringResource(R.string.onboarding_layer_node),
            status = nodeStatus,
            active = activeLayer == OnboardingLayer.NODE,
        )
    }
}

@Composable
private fun LayerCard(label: String, status: OnboardingLayerStatus, active: Boolean) {
    val accent = layerAccent(status)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (status == OnboardingLayerStatus.DONE) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(accent, CircleShape),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = layerStateLabel(status = status, active = active),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}

@Composable
private fun layerAccent(status: OnboardingLayerStatus) = when (status) {
    OnboardingLayerStatus.DONE,
    OnboardingLayerStatus.INSTALLING,
    -> MaterialTheme.colorScheme.primary
    OnboardingLayerStatus.FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun layerStateLabel(status: OnboardingLayerStatus, active: Boolean): String = when (status) {
    OnboardingLayerStatus.PENDING -> stringResource(R.string.onboarding_state_pending)
    OnboardingLayerStatus.INSTALLING -> stringResource(
        if (active) R.string.onboarding_state_installing else R.string.onboarding_state_background,
    )
    OnboardingLayerStatus.DONE -> stringResource(R.string.onboarding_state_ready)
    OnboardingLayerStatus.FAILED -> stringResource(R.string.onboarding_state_failed)
    OnboardingLayerStatus.SKIPPED -> stringResource(R.string.onboarding_state_skipped)
}

/**
 * 镜像源选择：折叠态一行（标题 + 已选源与延迟 + 箭头），展开态列出全部源，
 * 「含所需内容 → 延迟升序 → 其余」排序，最优源带徽标，不可达置灰不可点。
 * [locked] 为真（本层安装进行中）时全部源行禁用：安装由在途任务按已选源推进，
 * 换源会因早退而不生效，故此处一律锁死并在折叠头 meta 标注。
 */
@Composable
private fun <S> SourcesSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    probing: Boolean,
    locked: Boolean,
    sourceCount: Int,
    selectedUrl: String?,
    probes: Map<String, OnlineSourceProbe>,
    sources: List<S>,
    urlOf: (S) -> String,
    nameOf: (S) -> String,
    onSelect: (String) -> Unit,
) {
    val expandLabel = stringResource(R.string.onboarding_sources_expand)
    val collapseLabel = stringResource(R.string.onboarding_sources_collapse)
    val lockedLabel = stringResource(R.string.onboarding_source_locked)
    val ordered = sources.sortedWith(
        compareByDescending<S> { probes[urlOf(it)]?.let { p -> p.reachable && p.hasTarget } == true }
            .thenByDescending { probes[urlOf(it)]?.reachable == true }
            .thenBy { probes[urlOf(it)]?.latencyMs ?: Long.MAX_VALUE },
    )
    val bestUrl = ordered.firstOrNull {
        probes[urlOf(it)]?.let { p -> p.reachable && p.hasTarget } == true
    }?.let(urlOf)
    val selectedProbe = selectedUrl?.let { probes[it] }
    val metaText = when {
        probing && selectedUrl == null -> stringResource(R.string.onboarding_sources_probing, sourceCount)
        selectedUrl == null -> stringResource(R.string.onboarding_sources_none)
        selectedProbe?.reachable == true -> stringResource(
            R.string.onboarding_source_selected,
            nameOf(ordered.first { urlOf(it) == selectedUrl }),
            selectedProbe.latencyMs,
        )
        else -> stringResource(R.string.onboarding_sources_none)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .semantics { stateDescription = if (expanded) collapseLabel else expandLabel }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.onboarding_sources_title),
                    style = MaterialTheme.typography.bodyLarge,
                    // 固有宽度（fill=false）：标题按内容取宽，剩余空间让给 meta，
                    // 避免 meta 过长时反压标题折成竖列。
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    // 安装中在已选源文案后缀锁定语，说明此时不可更换（源行同时禁用）。
                    text = if (locked) "$metaText$lockedLabel" else metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // meta 是可变长文本：占满剩余宽、靠右、单行省略，
                    // 不与标题争夺宽度。
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_right),
                    contentDescription = if (expanded) collapseLabel else expandLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .rotate(if (expanded) 90f else 0f),
                )
            }
            if (expanded) {
                for (source in ordered) {
                    val url = urlOf(source)
                    SourceRow(
                        name = nameOf(source),
                        probe = probes[url],
                        best = url == bestUrl,
                        selected = url == selectedUrl,
                        enabled = !locked,
                        onSelect = { onSelect(url) },
                    )
                }
            }
        }
    }
}

/**
 * 不可达行置灰显示「不可达」；可达行点选即以该源重开安装。
 * [enabled] 为假（本层安装中）时整行禁用并置灰：点选不会生效，故不给出可点反馈。
 */
@Composable
private fun SourceRow(
    name: String,
    probe: OnlineSourceProbe?,
    best: Boolean,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val reachable = probe?.reachable == true
    val selectable = reachable && enabled
    val accent = when {
        probe == null -> MaterialTheme.colorScheme.onSurfaceVariant
        !reachable -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.clickable(onClick = onSelect) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .border(
                    width = 2.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier
                .weight(1f)
                .alpha(if (reachable && enabled) 1f else 0.5f),
        )
        if (best) {
            Text(
                text = stringResource(R.string.onboarding_source_best),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = when {
                probe == null -> stringResource(R.string.online_source_probing)
                reachable -> stringResource(R.string.onboarding_source_latency, probe.latencyMs)
                else -> stringResource(R.string.onboarding_source_unreachable)
            },
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
    }
}

/** Node 页前置阻塞卡：Debian 层未就绪时按缺失原因展示对应文案与回退入口。 */
@Composable
private fun BlockedCard(text: String, onAction: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_alert_triangle),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.onboarding_node_blocked_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAction) {
                Text(stringResource(R.string.onboarding_node_blocked_action))
            }
        }
    }
}

/** 进度区：阶段文案 + 技术性进度文本 + 不确定态进度条（底层无百分比，不伪造数值）。 */
@Composable
private fun ProgressSection(state: OnlineImportState) {
    val stage = state.stage.asString().ifBlank { stringResource(R.string.onboarding_progress_idle) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Text(text = stage, style = MaterialTheme.typography.titleSmall)
            }
            state.progress?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** 错误卡：失败时给出本层原因与「重试」（以当前选中源重新发起）。 */
@Composable
private fun ErrorSection(state: OnlineImportState, onRetry: () -> Unit) {
    val failure = state.result as? AppResult.Failure ?: return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_error_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = failure.error.userMessage?.asString()
                    ?: failure.error.message
                    ?: stringResource(R.string.error_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.onboarding_retry))
            }
        }
    }
}
