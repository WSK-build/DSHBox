package developer.access.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import developer.access.DeveloperAccessController
import developer.access.DeveloperAccessUiState
import developer.access.R
import developer.access.core.ServerTransport

/**
 * ADB 通道的连接方式分区。
 *
 * 四档：Unix socket（默认，最受限）、TCP 仅本机、TCP 局域网、自定义 spec。
 * 选档只改草稿，**点 [应用] 才落盘并终止旧 server**——「切换生效」要求
 * 改配置后精确杀掉本通道的 server，让下次调用按新方式重建。
 *
 * 端口状态三态呈现：空闲 / 已被占用 / 未探测。被占用时给处置建议，不自动杀他人进程。
 */
@Composable
internal fun AdbServerSpecSection(
    state: DeveloperAccessUiState,
    controller: DeveloperAccessController,
) {
    // 端口或档位变化时重探：局域网档要按 0.0.0.0 探测，同端口切档也得重启探测。
    LaunchedEffect(state.adbSpec.port, state.adbSpec.transport) { controller.probeAdbPort() }

    Column(modifier = Modifier.fillMaxWidth()) {
        DevSectionHeader(stringResource(R.string.dev_section_transport))
        TransportOption(
            label = stringResource(R.string.dev_transport_unix),
            selected = state.adbSpec.transport == ServerTransport.UNIX_SOCKET,
            onClick = { controller.setAdbTransport(ServerTransport.UNIX_SOCKET) },
        )
        TransportOption(
            label = stringResource(R.string.dev_transport_tcp_local, state.adbSpec.port),
            selected = state.adbSpec.transport == ServerTransport.TCP_LOCAL,
            onClick = { controller.setAdbTransport(ServerTransport.TCP_LOCAL) },
        )
        TransportOption(
            label = stringResource(R.string.dev_transport_tcp_lan, state.adbSpec.port),
            selected = state.adbSpec.transport == ServerTransport.TCP_LAN,
            onClick = { controller.setAdbTransport(ServerTransport.TCP_LAN) },
            warning = stringResource(R.string.dev_transport_lan_warning_adb),
        )
        TransportOption(
            label = stringResource(R.string.dev_transport_custom),
            selected = state.adbSpec.transport == ServerTransport.CUSTOM,
            onClick = { controller.setAdbTransport(ServerTransport.CUSTOM) },
        )

        // TCP 与自定义档需要端口或 spec 输入；unix socket 档没有这两项。
        when (state.adbSpec.transport) {
            ServerTransport.TCP_LOCAL, ServerTransport.TCP_LAN -> PortRow(
                port = state.adbSpec.port,
                status = state.adbPortStatus,
                onPortChange = controller::setAdbDraftPort,
                onApply = controller::applyAdb,
            )

            ServerTransport.CUSTOM -> SpecInput(
                value = state.adbDraftCustom,
                onValueChange = controller::setAdbDraftCustom,
                onApply = controller::applyAdb,
            )

            ServerTransport.UNIX_SOCKET -> DevActionRow {
                DevChip(stringResource(R.string.dev_apply), onClick = controller::applyAdb)
            }
        }

        // 端口越界就地红字提示：越界值落盘会让之后每条 adb 命令都失败，
        // 提示必须紧贴输入框，不能只落在卡片底部的全局提示区。
        if (!state.adbSpec.hasValidPort()) {
            DevNote(stringResource(R.string.dev_msg_port_out_of_range), error = true)
        } else if (state.adbSpec != state.adbPersistedSpec) {
            // 草稿与落盘值不一致时说清楚"现在生效的是哪一份"——否则用户会以为选完就生效，
            // 而 DSH 读到的仍是 server.conf 里的旧值。
            DevNote(
                stringResource(R.string.dev_spec_dirty, state.adbPersistedSpec.displaySpec()),
                error = true,
            )
        }
        // server.conf 对 DSH 可写；它改过之后面板要能主动重读，而不是只能退出重进。
        DevActionRow {
            DevChip(
                text = stringResource(R.string.dev_spec_reload),
                onClick = { controller.refresh() },
            )
        }
    }
}
