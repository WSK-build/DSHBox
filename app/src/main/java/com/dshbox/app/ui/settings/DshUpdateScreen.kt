package com.dshbox.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.DshApp
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.DshNpmSource
import com.dshbox.app.common.DshSources
import com.dshbox.app.common.Versions
import com.dshbox.app.runtime.DshOnlineInstallState
import com.dshbox.app.runtime.DshSourceProbe
import com.dshbox.app.sandbox.SandboxState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 1.1.0 (M8) — 更新 DSH（在线）新界面。
 *
 * 进入即并行探测所有 npm 源（DshSources.ALL），逐个展示：源名称 / 元数据地址 /
 * dist-tags.latest 版本号 / 网络延迟 / 可达状态；比当前安装版本新的源会高亮。
 * 点击可达的源 → 版本选择弹窗（全部已发布版本，latest 预选，可改选）→ 确认安装。
 * 安装在 guest Debian 内用 npm 从所选源拉取 @deepseek-ai/dsh 及完整依赖树
 * （与官方构建脚本 install_dsh.sh 相同方式），阶段 + npm 日志实时滚动展示，
 * 支持取消。完成后旧层自动备份到 previous/dsh，DSH 自动重启。
 */
@Composable
fun DshUpdateScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as DshApp).container
    val sandboxManager = container.sandboxManager
    val runtimeUpdateManager = container.runtimeUpdateManager
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    val dshVersion by sandboxManager.dshVersion.collectAsState()
    val sandboxState by sandboxManager.sandboxState.collectAsState()
    val installState by runtimeUpdateManager.installState.collectAsState()

    val probes = remember { mutableStateMapOf<String, DshSourceProbe>() }
    var probing by remember { mutableStateOf(false) }
    var versionDialogSource by remember { mutableStateOf<DshNpmSource?>(null) }
    var versionDialogSelection by remember { mutableStateOf<String?>(null) }
    var downgradeConfirm by remember { mutableStateOf<Pair<DshNpmSource, String>?>(null) }

    fun startProbe() {
        if (probing || installState.running) return
        probing = true
        probes.clear()
        scope.launch {
            runtimeUpdateManager.probeSources { probe -> probes[probe.source.url] = probe }
            probing = false
        }
    }

    // 进入界面自动探测一次。
    LaunchedEffect(Unit) { startProbe() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // M12.6：左上角显式返回键（此前仅系统 BackHandler，无可见入口）。
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.dsh_update_back),
                )
            }
            Text(
                text = stringResource(R.string.dsh_update_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                runtimeUpdateManager.clearInstallResult()
                startProbe()
            }) {
                Text(stringResource(R.string.dsh_update_reprobe))
            }
        }
        Text(
            text = stringResource(R.string.dsh_update_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dsh_update_installed),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = dshVersion?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.settings_dsh_not_installed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (sandboxState != SandboxState.RUNNING) {
            Text(
                text = stringResource(R.string.dsh_update_sandbox_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (installState.running || installState.result != null) {
            InstallProgressView(
                state = installState,
                onBackToList = { runtimeUpdateManager.clearInstallResult() },
                onCancel = { runtimeUpdateManager.cancelDshInstall() },
                onDone = onBack,
            )
        } else {
            // 源探测结果列表。
            Text(
                text = stringResource(R.string.dsh_update_sources_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (source in DshSources.ALL) {
                val probe = probes[source.url]
                SourceRow(
                    source = source,
                    probe = probe,
                    installedVersion = dshVersion?.takeIf { it.isNotBlank() },
                    enabled = !probing,
                    onClick = {
                        if (probe?.reachable == true && probe.latestVersion != null) {
                            versionDialogSource = source
                            versionDialogSelection = probe.latestVersion
                        }
                    },
                )
            }
            if (probing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.dsh_update_probing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 版本选择弹窗。
    val dialogSource = versionDialogSource
    if (dialogSource != null) {
        val probe = probes[dialogSource.url]
        AlertDialog(
            onDismissRequest = { versionDialogSource = null },
            title = { Text(stringResource(R.string.dsh_update_pick_version_title, dialogSource.name)) },
            text = {
                if (probe == null || probe.versions.isEmpty()) {
                    Text(stringResource(R.string.dsh_update_no_versions))
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        probe.versions.forEach { version ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { versionDialogSelection = version }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = versionDialogSelection == version,
                                    onClick = { versionDialogSelection = version },
                                )
                                Text(
                                    text = version + versionBadgeSuffix(version, dshVersion),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                val selection = versionDialogSelection
                TextButton(
                    enabled = selection != null && probe?.reachable == true,
                    onClick = {
                        if (selection != null) {
                            val installed = dshVersion?.takeIf { it.isNotBlank() }
                            val isDowngrade = installed != null &&
                                Versions.compare(installed, selection) >= 0
                            versionDialogSource = null
                            if (isDowngrade) {
                                downgradeConfirm = dialogSource to selection
                            } else {
                                runtimeUpdateManager.startDshInstall(dialogSource, selection, allowDowngrade = false)
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.dsh_update_install_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { versionDialogSource = null }) {
                    Text(stringResource(R.string.settings_cancel_action))
                }
            },
        )
    }

    // 降级（或同版本重装）二次确认。
    val confirm = downgradeConfirm
    if (confirm != null) {
        val (source, version) = confirm
        AlertDialog(
            onDismissRequest = { downgradeConfirm = null },
            title = { Text(stringResource(R.string.dsh_update_downgrade_title)) },
            text = { Text(stringResource(R.string.dsh_update_downgrade_msg, version, source.name)) },
            confirmButton = {
                TextButton(onClick = {
                    runtimeUpdateManager.startDshInstall(source, version, allowDowngrade = true)
                    downgradeConfirm = null
                }) {
                    Text(stringResource(R.string.dsh_update_downgrade_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { downgradeConfirm = null }) {
                    Text(stringResource(R.string.settings_cancel_action))
                }
            },
        )
    }
}

/** 版本号后的标记：latest / 比当前新 / 与当前一致 / 比当前旧。 */
private fun versionBadgeSuffix(version: String, installed: String?): String = when {
    installed == null -> ""
    Versions.compare(installed, version) < 0 -> "（比当前新）"
    Versions.compare(installed, version) == 0 -> "（当前版本）"
    else -> "（比当前旧）"
}

@Composable
private fun SourceRow(
    source: DshNpmSource,
    probe: DshSourceProbe?,
    installedVersion: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val hasNewer = probe?.latestVersion != null && installedVersion != null &&
        Versions.compare(installedVersion, probe.latestVersion) < 0
    val accent = when {
        probe == null -> MaterialTheme.colorScheme.onSurfaceVariant
        !probe.reachable -> MaterialTheme.colorScheme.error
        hasNewer -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (probe?.reachable == true && enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (hasNewer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (hasNewer) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (source.chinaMirror) {
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    RoundedCornerShape(4.dp),
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.dsh_update_china_tag),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }
                Text(
                    text = source.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = probe?.let {
                        if (it.reachable) {
                            stringResource(R.string.dsh_update_latest_version, it.latestVersion ?: "—")
                        } else {
                            stringResource(R.string.dsh_update_unreachable, it.error ?: "")
                        }
                    } ?: stringResource(R.string.dsh_update_waiting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                )
            }
            Text(
                text = probe?.let {
                    if (it.reachable) stringResource(R.string.dsh_update_latency, it.latencyMs) else "—"
                } ?: "…",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = accent,
            )
        }
    }
}

@Composable
private fun InstallProgressView(
    state: DshOnlineInstallState,
    onBackToList: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 1.1.1 (M1)：去掉内层 verticalScroll —— 本视图直接位于更新页根
    // Column(verticalScroll) 之内，嵌套滚动组件会被以无限最大高度约束测量，
    // 首次组合即抛 IllegalStateException（点「安装」后整个 app 闪退，真机
    // FATAL EXCEPTION: main 实证）。外层页面 Column 已可滚，日志区由固定
    // 260dp 的 LazyColumn 自行滚动，此层无需再滚。
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            state.running -> {
                // 1.1.1 (T1)：下载阶段展示「已用时」与保底提示（安装始终在后台
                // scope 运行，离开本页不影响；本提示行不遮挡任何操作）。
                var now by remember { mutableStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.startedAtMs) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1000)
                    }
                }
                val elapsedSec = ((now - state.startedAtMs) / 1000).coerceAtLeast(0)
                val elapsedText = buildString {
                    if (elapsedSec >= 3600) append("${elapsedSec / 3600} 小时 ")
                    if (elapsedSec >= 60) append("${(elapsedSec % 3600) / 60} 分 ")
                    append("${elapsedSec % 60} 秒")
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Text(text = state.stage, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = stringResource(R.string.dsh_update_elapsed, elapsedText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.dsh_update_install_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.result is AppResult.Success -> {
                Text(
                    text = stringResource(
                        R.string.dsh_update_done_success,
                        state.result.value.version ?: "—",
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            state.result is AppResult.Failure -> {
                Text(
                    text = stringResource(
                        R.string.dsh_update_done_failed,
                        state.result.error.message,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                if (state.cancelled) {
                    Text(
                        text = stringResource(R.string.dsh_update_cancelled_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .padding(12.dp),
            ) {
                items(state.logs.size) { index ->
                    Text(
                        text = state.logs[index],
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
        LaunchedEffect(state.logs.size) {
            if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.size - 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (state.running) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsh_update_cancel_install))
                }
            } else {
                OutlinedButton(onClick = onBackToList, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsh_update_back_to_sources))
                }
                Button(onClick = onDone, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.dsh_update_done_close))
                }
            }
        }
    }
}
