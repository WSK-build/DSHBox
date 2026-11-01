package developer.access.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import developer.access.DeveloperAccessController
import developer.access.R
import developer.access.DeveloperAccessUiState
import kotlinx.coroutines.launch

/**
 * 开发者模式面板。
 *
 * ## 结构
 *
 * 两条通道各成一块：**ADB 权限**与 **Root 权限**。每块内固定三段：
 * 开关（唯一的权限动作）、连接方式、以及该通道独有的内容
 * （ADB 侧：配对向导与设备授权；Root 侧：访问码）。
 *
 * ## 反馈
 *
 * 操作结果以行内提示呈现（错误用 error 色）；沙箱未运行时的功能项点击给出 Toast
 * 。
 */
@Composable
fun DeveloperAccessPage(
    controller: DeveloperAccessController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) { controller.refresh() }

    // 子页容器已提供滚动与留白，此处只负责内容排布。
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.dev_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ── ADB 权限 ──
        DevCard {
            DevHeader(
                title = stringResource(R.string.dev_adb_title),
                subtitle = stringResource(
                    if (state.devOptionsEnabled) {
                        R.string.dev_adb_subtitle
                    } else {
                        R.string.dev_adb_subtitle_no_dev_options
                    },
                ),
                checked = state.adbEnabled,
                enabled = true,
                busy = state.busy,
                onCheckedChange = { on -> scope.launch { controller.setAdbEnabled(on) } },
            )
            // 授权持久性说明紧跟开关：用户判断"关掉是否清净"时看的就是这一行。
            DevNote(stringResource(R.string.dev_adb_auth_note))
            DevSectionDivider()
            AdbServerSpecSection(state = state, controller = controller)

            DevSectionDivider()
            PairingSection(
                state = state,
                controller = controller,
                onAdbDown = {
                    android.widget.Toast
                        .makeText(
                            context,
                            context.getString(R.string.dev_pairing_needs_adb),
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        .show()
                },
                onSandboxDown = {
                    android.widget.Toast
                        .makeText(
                            context,
                            context.getString(R.string.dev_pairing_needs_sandbox),
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        .show()
                },
            )

            DevSectionDivider()
            KeyManagementSection(state = state, controller = controller)
        }

        // 提示紧跟产生它的那张卡：面板较长，堆在页尾会让"点了没反应"的错觉成立。
        if (state.hint != null && !state.hintFromRoot) HintCard(state = state, controller = controller)

        // ── Root 权限 ──
        DevCard {
            DevHeader(
                title = stringResource(R.string.dev_root_title),
                subtitle = stringResource(
                    if (state.suAvailable) R.string.dev_root_subtitle else R.string.dev_root_subtitle_unrooted,
                ),
                checked = state.rootEnabled,
                enabled = state.suAvailable,
                busy = state.busy,
                onCheckedChange = { on -> scope.launch { controller.setRootEnabled(on) } },
                onDisabledClick = {
                    android.widget.Toast
                        .makeText(
                            context,
                            context.getString(R.string.dev_msg_no_root),
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        .show()
                },
            )
            // 无论是否 root 都展示该分区的内容：未 root 时给出明确引导，
            // 而不是把整块藏起来变成一个不可解释的空面板。
            DevSectionDivider()
            if (!state.suAvailable) {
                DevNote(stringResource(R.string.dev_root_guidance_unrooted), error = true)
            }
            RootExecutorSpecSection(state = state, controller = controller)
        }

        if (state.hint != null && state.hintFromRoot) HintCard(state = state, controller = controller)
        Spacer(Modifier.height(4.dp))
    }

    // 关闭开关后的确认弹窗——用户最容易误以为"关了手机就干净了"。
    if (state.showAdbDisableNotice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { controller.dismissAdbDisableNotice() },
            title = { Text(stringResource(R.string.dev_adb_disable_notice_title)) },
            text = { Text(stringResource(R.string.dev_adb_disable_notice_body)) },
            confirmButton = {
                Text(
                    text = stringResource(R.string.dev_hint_dismiss),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { controller.dismissAdbDisableNotice() },
                )
            },
        )
    }
}

/** 操作结果提示卡：一行文字 + [知道了]。 */
@Composable
private fun HintCard(state: DeveloperAccessUiState, controller: DeveloperAccessController) {
    val context = LocalContext.current
    DevCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.hint?.asString(context).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.hintIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.dev_hint_dismiss),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { controller.clearHint() },
            )
        }
    }
}

/**
 * 配对分区。
 *
 * 配对命令要在运行环境层内执行（`adb pair` / `adb connect`），故它需要两个前提：
 * ADB 通道已开启（wrapper 与密钥就在通道开启时装好）、沙箱在运行（命令在 guest 里跑）。
 * 缺任何一个都**置灰但仍可点**，点了给 Toast 说明缺的是哪一项。
 */
@Composable
private fun PairingSection(
    state: DeveloperAccessUiState,
    controller: DeveloperAccessController,
    onAdbDown: () -> Unit,
    onSandboxDown: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canPair = state.adbEnabled && state.sandboxRunning
    Column(modifier = Modifier.fillMaxWidth()) {
        // 一级小标题：与「连接方式」「设备授权记录」同级加粗。
        DevSectionHeader(stringResource(R.string.dev_pairing_group_section))
        // 二级条目：配对码连接（配对成功后可免去再次授权弹窗）。
        DevLabel(stringResource(R.string.dev_pairing_code_label))
        DevActionRow {
            DevChip(
                text = stringResource(
                    if (state.pairingOpen) R.string.dev_pairing_collapse else R.string.dev_pairing_start,
                ),
                enabled = canPair || state.pairingOpen,
                onDisabledClick = { if (!state.adbEnabled) onAdbDown() else onSandboxDown() },
                onClick = {
                    when {
                        state.pairingOpen -> controller.closePairing()
                        !state.adbEnabled -> onAdbDown()
                        !state.sandboxRunning -> onSandboxDown()
                        else -> controller.openPairing()
                    }
                },
            )
        }
        // 常驻状态说明：功能项在没有前提时必须写明"为什么现在不能用"。
        if (!state.adbEnabled) {
            DevNote(stringResource(R.string.dev_pairing_needs_adb_hint))
        } else if (!state.sandboxRunning) {
            DevNote(stringResource(R.string.dev_sandbox_not_running))
        }
        if (state.pairingOpen) {
            PairingWizard(
                step = state.pairingStep,
                code = state.pairingCode,
                busy = state.pairingBusy,
                discovering = state.pairingDiscovering,
                error = state.pairingError?.asString(context),
                address = state.pairingAddress,
                pairDraft = state.pairingPairDraft,
                needsConnectTarget = state.pairingNeedsConnectTarget,
                connectDraft = state.pairingConnectDraft,
                connectAddress = state.pairingConnectAddress,
                onNext = { scope.launch { controller.pairingNext() } },
                onRedetect = { scope.launch { controller.redetectPairingAddress() } },
                onCodeChange = { raw -> scope.launch { controller.setPairingCode(raw) } },
                onPairDraftChange = controller::setPairingPairDraft,
                onConnectDraftChange = controller::setPairingConnectDraft,
                onConnect = { scope.launch { controller.submitManualConnect() } },
                onRestart = { scope.launch { controller.restartPairing() } },
                onDone = controller::closePairing,
            )
        }

        // 传统授权方式：直接 adb connect，由手机弹「允许调试」授权。
        // 与配对码路径并列保留，不需要配对码。
        DevDivider()
        DevLabel(stringResource(R.string.dev_direct_section))
        DevNote(stringResource(R.string.dev_direct_note))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DevTextInput(
                value = state.directConnectDraft,
                placeholder = stringResource(R.string.dev_direct_hint),
                onValueChange = controller::setDirectConnectDraft,
            )
        }
        DevActionRow {
            DevChip(
                text = stringResource(
                    if (state.directConnectBusy) R.string.dev_direct_status_connecting
                    else R.string.dev_pairing_connect_action,
                ),
                enabled = canPair && !state.directConnectBusy,
                onDisabledClick = { if (!state.adbEnabled) onAdbDown() else onSandboxDown() },
                onClick = { scope.launch { controller.connectDirect() } },
            )
        }
        // 就地反馈：结果必须出现在按钮旁边，否则用户会以为按钮没反应。
        state.directConnectStatus?.let { status ->
            Text(
                text = status.asString(context),
                style = MaterialTheme.typography.labelMedium,
                color = if (state.directConnectStatusIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}
