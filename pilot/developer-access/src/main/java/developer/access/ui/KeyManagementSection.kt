package developer.access.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import developer.access.DeveloperAccessController
import developer.access.DeveloperAccessUiState
import developer.access.R
import developer.access.adb.KeyStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设备授权分区。
 *
 * ## 两个危险操作必须分开
 *
 * 删除本机密钥会**改变本机身份**——所有设备都要重新配对；清除配对记录只丢
 * "该自动连谁"，身份保留、重连不弹框。两者后果不同，合成一个按钮会让用户在
 * 错误前提下做选择。
 *
 * ## 已配对设备
 *
 * 从 adb 自己维护的 `adb_known_hosts.pb` 读取（只读，不编辑）。取不到可读标识时
 * 如实说明，不伪造条目。
 */
@Composable
internal fun KeyManagementSection(
    state: DeveloperAccessUiState,
    controller: DeveloperAccessController,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canUseChannel = state.adbEnabled && state.sandboxRunning
    val explainChannel = {
        android.widget.Toast
            .makeText(
                context,
                context.getString(
                    if (!state.adbEnabled) R.string.dev_pairing_needs_adb else R.string.dev_pairing_needs_sandbox,
                ),
                android.widget.Toast.LENGTH_SHORT,
            )
            .show()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.dev_keys_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.dev_keys_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DevDivider()
        DevLabel(stringResource(R.string.dev_keys_local))
        if (state.keys.isEmpty()) {
            DevNote(stringResource(R.string.dev_keys_none))
        } else {
            state.keys.forEach { key ->
                KeyRow(key = key, onDelete = { controller.deleteKey(key.id) })
            }
        }
        DevActionRow {
            DevChip(
                text = stringResource(R.string.dev_key_generate),
                // 生成走 guest 内的 adb keygen：缺通道或沙箱时点不动，并说明缺的是哪一项。
                enabled = canUseChannel,
                onDisabledClick = { explainChannel() },
                onClick = { scope.launch { controller.generateKey() } },
            )
        }
        DevDivider()
        DevLabel(stringResource(R.string.dev_paired_section))
        if (state.pairedDevices.isEmpty()) {
            DevNote(
                stringResource(
                    if (state.hasPairedDevices) R.string.dev_paired_unreadable else R.string.dev_paired_none,
                ),
            )
        } else {
            state.pairedDevices.forEach { device ->
                Text(
                    text = device,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 配对记录只含设备标识：adb 的 pb 文件不提供配对时间与配对方式，界面如实说明。
            DevNote(stringResource(R.string.dev_paired_note))
        }
        DevActionRow {
            DevChip(
                text = stringResource(R.string.dev_paired_refresh),
                // 只重读授权状态：全量刷新会覆盖上方尚未应用的连接方式草稿。
                onClick = { controller.refreshKeys() },
            )
            DevChip(
                text = stringResource(R.string.dev_paired_clear),
                onClick = { controller.clearPairedDevices() },
            )
        }
        DevDivider()
        OnlineDevices(
            state = state,
            controller = controller,
        )
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_dev_warning),
                contentDescription = null,
                tint = warningTextColor(),
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.dev_keys_warning),
                style = MaterialTheme.typography.labelSmall,
                color = warningTextColor(),
            )
        }
    }
}

/**
 * 在线设备：向 adb server 现查 `adb devices`，并提供断开入口。
 *
 * 与上方"已配对设备"的区别是本质的：那边读的是历史配对记录（文件），这边问的是
 * server 此刻真正握着的连接——"面板说连上了"与"DSH 真的能操作"是否一致，看这里。
 * 两个动作都依赖 ADB 通道与沙箱（命令在 guest 内执行），缺前提时置灰并给 Toast。
 */
@Composable
private fun OnlineDevices(
    state: DeveloperAccessUiState,
    controller: DeveloperAccessController,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val canQuery = state.adbEnabled && state.sandboxRunning
    val explain = {
        android.widget.Toast
            .makeText(
                context,
                context.getString(
                    if (!state.adbEnabled) R.string.dev_pairing_needs_adb else R.string.dev_pairing_needs_sandbox,
                ),
                android.widget.Toast.LENGTH_SHORT,
            )
            .show()
    }

    DevLabel(stringResource(R.string.dev_devices_section))
    DevActionRow {
        DevChip(
            text = stringResource(R.string.dev_devices_view),
            enabled = canQuery && !state.devicesBusy,
            onDisabledClick = { if (!canQuery) explain() },
            onClick = { scope.launch { controller.refreshDevices() } },
        )
        DevChip(
            text = stringResource(R.string.dev_devices_disconnect),
            enabled = canQuery && !state.devicesBusy,
            onDisabledClick = { if (!canQuery) explain() },
            onClick = { scope.launch { controller.disconnectDevices() } },
        )
    }
    when {
        !canQuery -> DevNote(
            stringResource(
                if (!state.adbEnabled) R.string.dev_pairing_needs_adb_hint else R.string.dev_sandbox_not_running,
            ),
        )

        state.devicesBusy -> DevNote(stringResource(R.string.dev_devices_busy))

        state.connectedDevices.isEmpty() ->
            if (state.devicesLoaded) DevNote(stringResource(R.string.dev_devices_none))

        else -> state.connectedDevices.forEach { device ->
            Text(
                text = device,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    DevNote(stringResource(R.string.dev_devices_hint))
}

/** 单把密钥一行：标签 + 指纹 + 创建时间 + 复制/删除。 */
@Composable
private fun KeyRow(key: KeyStore.KeyInfo, onDelete: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.ic_dev_key),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (key.isPrimary) stringResource(R.string.dev_key_primary) else key.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (key.isPrimary) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.dev_key_identity),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                text = key.fingerprint.ifEmpty { stringResource(R.string.dev_key_fingerprint_na) },
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.dev_key_created, formatTime(key.createdAtMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        DevChip(
            text = stringResource(R.string.dev_access_code_copy),
            onClick = { clipboard.setText(AnnotatedString(key.fingerprint.ifEmpty { key.label })) },
        )
        Spacer(Modifier.width(8.dp))
        DevChip(text = stringResource(R.string.dev_key_delete), onClick = onDelete)
    }
}

/** 时间戳格式化；取不到时如实显示"未知"。 */
@Composable
private fun formatTime(ms: Long): String {
    if (ms <= 0L) return stringResource(R.string.dev_key_time_unknown)
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}
