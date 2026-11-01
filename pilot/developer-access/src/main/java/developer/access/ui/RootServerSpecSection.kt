package developer.access.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import developer.access.DeveloperAccessController
import developer.access.DeveloperAccessUiState
import developer.access.R
import developer.access.core.ServerTransport

/**
 * Root 通道的连接方式分区。
 *
 * 三档：Unix socket（默认，靠 SO_PEERCRED 校验同 uid）、TCP 仅本机、TCP 局域网。
 * 两种 TCP 档都需要访问码（128 bit + HMAC 挑战应答），故访问码区块只在 TCP 档展开——
 * unix socket 免认证，显示访问码反而误导。
 *
 * 端口可改，点 [应用] 才落盘；执行器在跑时按新方式重启。
 */
@Composable
internal fun RootExecutorSpecSection(
    state: DeveloperAccessUiState,
    controller: DeveloperAccessController,
) {
    // 端口或档位变化时重探：局域网档要按 0.0.0.0 探测，同端口切档也得重启探测。
    LaunchedEffect(state.rootSpec.port, state.rootSpec.transport) { controller.probeRootPort() }

    Column(modifier = Modifier.fillMaxWidth()) {
        DevSectionHeader(stringResource(R.string.dev_section_transport))
        TransportOption(
            label = stringResource(R.string.dev_transport_unix),
            selected = state.rootSpec.transport == ServerTransport.UNIX_SOCKET,
            onClick = { controller.setRootTransport(ServerTransport.UNIX_SOCKET) },
        )
        TransportOption(
            label = stringResource(R.string.dev_transport_tcp_local, state.rootSpec.port),
            selected = state.rootSpec.transport == ServerTransport.TCP_LOCAL,
            onClick = { controller.setRootTransport(ServerTransport.TCP_LOCAL) },
            warning = stringResource(R.string.dev_transport_local_warning_root),
        )
        TransportOption(
            label = stringResource(R.string.dev_transport_tcp_lan, state.rootSpec.port),
            selected = state.rootSpec.transport == ServerTransport.TCP_LAN,
            onClick = { controller.setRootTransport(ServerTransport.TCP_LAN) },
            warning = stringResource(R.string.dev_transport_lan_warning_root),
        )

        if (state.rootSpec.transport != ServerTransport.UNIX_SOCKET) {
            PortRow(
                port = state.rootSpec.port,
                status = state.rootPortStatus,
                onPortChange = controller::setRootDraftPort,
                onApply = controller::applyRoot,
            )
            DevDivider()
            DevLabel(stringResource(R.string.dev_access_code))
            AccessCodeBlock(
                code = state.rootAccessCode,
                address = remember(state.rootSpec.port, state.rootSpec.transport) {
                    accessAddress(state.rootSpec.transport, state.rootSpec.port)
                },
                onRegenerate = { controller.regenerateAccessCode() },
            )
        } else {
            DevActionRow {
                DevChip(stringResource(R.string.dev_apply), onClick = controller::applyRoot)
            }
        }

        // 端口越界就地红字提示（与 ADB 档同一口径）：执行器同样以端口起监听。
        if (!state.rootSpec.hasValidPort()) {
            DevNote(stringResource(R.string.dev_msg_port_out_of_range), error = true)
        } else if (state.rootSpec != state.rootPersistedSpec) {
            // 选档只改草稿；未点 [应用] 时执行器仍在旧方式上监听，必须说明。
            DevNote(
                stringResource(R.string.dev_spec_dirty, state.rootPersistedSpec.displaySpec()),
                error = true,
            )
        }

        // 连续认证失败到阈值后服务端拒绝一切接入——只能靠重新生成访问码解除。
        if (state.rootAuthLocked) {
            DevNote(stringResource(R.string.dev_root_locked_out), error = true)
        }
    }
}

/** 去掉分组符后的原始访问码，供复制与二维码使用。 */
internal fun normalized(code: String): String = code.replace("-", "").trim()

/**
 * 外部工具要连的地址。
 *
 * 局域网档下外部设备不能用 `127.0.0.1`，故取本机在本网段的 IPv4；取不到时如实回落
 * 回环地址，不编造一个连不上的地址。本机档与 unix 档只可能来自本机，用回环。
 */
private fun accessAddress(transport: ServerTransport, port: Int): String {
    if (transport != ServerTransport.TCP_LAN) return "127.0.0.1:$port"
    val lan = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
    return "${lan ?: "127.0.0.1"}:$port"
}

/**
 * 访问码区块：等宽分组显示 + 二维码 + 复制 + 重新生成。
 *
 * 二维码载荷含**传输地址 + 访问码 + 协议标识**：外部工具扫一次即可完成配置，
 * 不必再另行得知连哪台、哪个端口。26 位码不可能手输，故只提供复制与扫码。
 */
@Composable
internal fun AccessCodeBlock(code: String, address: String, onRegenerate: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    // 分组规则由 RootAuthCode 统一持有，界面不再重复一遍 chunked 逻辑。
    val grouped = remember(code) { developer.access.root.RootAuthCode.group(code) }
    val payload = remember(code, address) {
        if (code.isBlank()) {
            null
        } else {
            """{"v":1,"transport":"tcp","address":"$address","code":"${normalized(code)}"}"""
        }
    }
    val qr = remember(payload) { payload?.let { QrCode.encode(it, 512)?.asImageBitmap() } }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.dev_access_address, address),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = grouped,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (qr != null) {
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                        .padding(6.dp),
                ) {
                    Image(
                        bitmap = qr,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = stringResource(R.string.dev_access_code_scan_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DevChip(
                text = stringResource(R.string.dev_access_code_copy),
                onClick = { clipboard.setText(AnnotatedString(normalized(code))) },
            )
            DevChip(
                text = stringResource(R.string.dev_access_code_regen),
                onClick = onRegenerate,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.dev_access_code_regen_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
