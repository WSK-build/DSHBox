package com.dshbox.app.ui.home

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
// 插件管理模块的资源（字符串在它自己的模块里，非传递 R 类需要显式引用）。
import com.dshbox.pluginmanager.R as PmR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
// 「浏览器」入口那个地球**刻意保留** Material 的实心图标（用户专门设计成与其它入口不同，
// 不参与描边统一），所以这两个导入保持启用、不要注释。
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.service.SandboxService
import kotlinx.coroutines.delay
import java.util.Locale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    sandboxRunning: Boolean,
    sandboxError: Boolean,
    dshReady: Boolean,
    dshError: Boolean,
    /** 连续不应答已达展示阈值：状态行改提示「无响应」，供用户在等待期间自行选择重启。 */
    dshUnresponsive: Boolean,
    runtimeInstalled: Boolean,
    nodeLayerInstalled: Boolean = true,
    bundledRuntimeAvailable: Boolean,
    onNavigateToSettings: () -> Unit,
    onGetRuntimeOnline: () -> Unit,
    /**
     * 「进入 pilot」入口。卡片在首页，`:pilot` 模块及其整页仍处于摘除状态，
     * 所以宿主侧目前接的是空实现——新界面就绪后只改宿主那个 lambda，本页不必再动。
     */
    onOpenPilot: () -> Unit,
    onOpenPluginManager: () -> Unit,
    /**
     * 用**外部浏览器**打开 DSH。由宿主（[com.dshbox.app.ui.MainScreen]）实现：
     * URL 必须带 DSH 启动 token，否则外部浏览器会停在鉴权页（它没有 WebView 的
     * cookie 交换与注入通道），这正是该入口"点开不可用"的原因。
     */
    onOpenInBrowser: () -> Unit,
) {
    val context = LocalContext.current
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
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

        // 入口卡放在启停板块之下：首页的阅读顺序是"先看状态，再进页面"。
        // DshPilot 入口：只做入口，不放任何启停按钮 —— 它自己的启动/权限/模型配置
        // 全在页面内部完成（见 DshPilotPage）。
        PilotEntryCard(onClick = onOpenPilot)

        // 插件管理入口：排障入口，出事时要能在首页第一屏进来。
        PluginManagerEntryCard(onClick = onOpenPluginManager)
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
 * 首页的 DshPilot 入口卡片。
 *
 * 刻意做成"整卡可点 + 右侧箭头"而不是按钮行：它是进入另一个界面的门，
 * 不是对当前对象的状态操作（那才是启动/重启/关闭按钮的位置）。
 */
@Composable
private fun PilotEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 品牌标记必须用 Image 而不是 Icon：Icon 会按 tint 把图形**单色化**，
            // 品牌绿与图形内的白色分隔线会一起丢失。
            Image(
                painter = painterResource(R.drawable.ic_pilot_brand),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_pilot_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.home_pilot_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 插件管理入口卡：形态与 DshPilot 卡一致（同款描边、图标位、右箭头），
 * 图标是同色调的品牌绿底 + 白色拼图图形。
 */
@Composable
private fun PluginManagerEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_plugin_manager),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(PmR.string.pm_home_entry_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(PmR.string.pm_home_entry_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

@Composable
private fun DshStatusCard(
    dshReady: Boolean,
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
    val statusText = stringResource(
        when {
            dshError -> R.string.home_dsh_error
            // 无响应是「尚未确认」的中间态：先提示、并保持重启入口可用，
            // 是否重启仍由引擎按不应答窗口判定，展示层不参与。
            dshUnresponsive -> R.string.home_dsh_unresponsive
            dshReady -> R.string.home_dsh_ready
            else -> R.string.home_dsh_stopped
        },
    )
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
                    enabled = sandboxRunning && !dshReady,
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
