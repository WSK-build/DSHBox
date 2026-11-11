package com.dshbox.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.lanaccess.LanAccessController
import com.dshbox.app.ui.configurationAwareResources
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.dshbox.app.common.R as CommonR
import kotlinx.coroutines.delay

/**
 * DSH 局域网访问子页：开关、访问地址（含 /不含密钥两行，可复制）、
 * 密钥的显示/隐藏/重置、二维码、在线设备数、「全部断开并重置密钥」（确认
 * 对话后执行）与一行安全说明。
 *
 * 密钥默认隐藏（防旁人瞟屏）：隐藏态下含密钥的链接整行不渲染，二维码也
 * 只在密钥可见时生成（同一份密钥的两种呈现）。开关打开而 DSH 未就绪时
 * 给出提示行；错误态（无 Wi-Fi 地址 / 绑定失败）分型显式报出，不静默吞；
 * 监听停止后补一行「已停止」确认。
 */
@Composable
internal fun DshLanAccessScreen(
    dshReady: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = configurationAwareResources()
    val state by LanAccessController.state.collectAsState()
    var keyVisible by remember { mutableStateOf(false) }
    var copiedTarget by remember { mutableStateOf<String?>(null) }
    // 「全部断开并重置密钥」的确认对话（按钮即时生效的破坏性动作先确认）。
    var confirmDisconnect by remember { mutableStateOf(false) }

    // 首次进入即确保密钥存在（持久化首次生成）。
    LaunchedEffect(Unit) { LanAccessController.ensureKey(context) }
    LaunchedEffect(copiedTarget) {
        if (copiedTarget != null) {
            delay(1_500)
            copiedTarget = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 顶栏（与 DshAccessSettingsScreen 同规格）。
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                    contentDescription = stringResource(R.string.home_browser_more_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(12.dp)
                        .size(22.dp),
                )
                Text(
                    text = stringResource(R.string.home_lan_title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                text = stringResource(R.string.home_lan_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 开关行。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsIconRow(CommonR.drawable.ic_world)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.home_lan_switch),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { enabled -> LanAccessController.setEnabled(enabled) },
                    )
                }
            }

            if (state.enabled && !dshReady) {
                Text(
                    text = stringResource(R.string.home_lan_dsh_not_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (state.lastError != null) {
                // 错误分型：绑定失败（端口占用等）与无 Wi-Fi 地址文案区分。
                Text(
                    text = stringResource(
                        if (state.lastError == LAN_ERROR_BIND_FAILED) {
                            R.string.home_lan_bind_failed
                        } else {
                            R.string.home_lan_no_wlan
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (!state.enabled && state.everStarted) {
                // 曾开启后停止（关开关/DSH 掉 READY/断网）的一行确认提示，
                // 页面不留空态；进程首次进入且从未开启过不显示（空态不是
                // 停止事件）。
                Text(
                    text = stringResource(R.string.home_lan_gateway_stopped),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.enabled && state.bound) {
                // 地址区块：含密钥整链（二维码同源）与不含密钥的续访地址。
                // 密钥默认隐藏（防旁人瞟屏）：隐藏态下含密钥的链接整行不
                // 渲染（不回填掩码占位，杜绝明文残留在布局/无障碍树里），
                // 点「显示」后该行与二维码一起出现。
                val withKey = state.addressWithKey
                val plain = state.addressPlain
                if (withKey != null && plain != null) {
                    LanSection(stringResource(R.string.home_lan_section_address)) {
                        CopyRow(
                            title = stringResource(R.string.home_lan_address_plain),
                            value = plain,
                            copied = copiedTarget == plain,
                            onCopy = {
                                copyToClipboard(context, resources, plain)
                                copiedTarget = plain
                            },
                        )
                        if (keyVisible) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            CopyRow(
                                title = stringResource(R.string.home_lan_address_with_key),
                                value = withKey,
                                copied = copiedTarget == withKey,
                                onCopy = {
                                    copyToClipboard(context, resources, withKey)
                                    copiedTarget = withKey
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            QrImage(value = withKey)
                        }
                    }
                }

                // 密钥区块：显示/隐藏/重置。
                LanSection(stringResource(R.string.home_lan_section_key)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIconRow(CommonR.drawable.ic_lock)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.home_lan_key_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (keyVisible) {
                                    state.accessKey ?: ""
                                } else {
                                    stringResource(R.string.home_lan_key_hidden)
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        ActionButton(
                            text = stringResource(
                                if (keyVisible) R.string.home_lan_key_hide else R.string.home_lan_key_show,
                            ),
                            onClick = { keyVisible = !keyVisible },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        ActionButton(
                            text = stringResource(R.string.home_lan_key_reset),
                            onClick = { LanAccessController.resetKey(context) },
                        )
                    }
                }

                // 连接区块：在线设备数与「全部断开并重置密钥」。
                LanSection(stringResource(R.string.home_lan_section_connections)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsIconRow(CommonR.drawable.ic_devices)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.home_lan_devices_format, state.devices),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        ActionButton(
                            text = stringResource(R.string.home_lan_disconnect_all),
                            enabled = state.devices > 0,
                            onClick = { confirmDisconnect = true },
                        )
                    }
                }
            }

            // 安全说明（一行）：仅同一 Wi-Fi 下可访问；密钥失效即断。
            Text(
                text = stringResource(R.string.home_lan_security_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }

    if (confirmDisconnect) {
        // 「全部断开并重置密钥」确认对话：断开即轮换密钥，已连接
        // 的设备必须用新密钥（新链接/扫码）重新进入。
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.home_lan_disconnect_confirm_title)) },
            text = { Text(stringResource(R.string.home_lan_disconnect_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    LanAccessController.disconnectAll(context)
                }) {
                    Text(stringResource(R.string.home_lan_disconnect_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(stringResource(R.string.home_lan_disconnect_confirm_cancel))
                }
            },
        )
    }
}

@Composable
private fun LanSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CopyRow(
    title: String,
    value: String,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            ActionButton(
                text = stringResource(
                    if (copied) R.string.home_browser_more_copied else R.string.home_browser_more_copy,
                ),
                onClick = onCopy,
            )
        }
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun QrImage(value: String) {
    val bitmap = remember(value) {
        runCatching {
            val size = 480
            val matrix = QRCodeWriter().encode(
                value,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(EncodeHintType.MARGIN to 1),
            )
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { canvas ->
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        canvas.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                    }
                }
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(200.dp),
            )
        }
    }
}

@Composable
private fun SettingsIconRow(iconRes: Int) {
    Icon(
        imageVector = ImageVector.vectorResource(iconRes),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier
            .widthIn(min = 56.dp)
            .height(28.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun copyToClipboard(
    context: android.content.Context,
    resources: android.content.res.Resources,
    value: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    if (clipboard != null) {
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                resources.getString(R.string.home_lan_title),
                value,
            ),
        )
    }
}

/** [LanAccessController] 状态里「绑定失败」的错误码（网关端口被占用等）。 */
private const val LAN_ERROR_BIND_FAILED = "bind_failed"
