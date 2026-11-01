package developer.access.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import developer.access.R
import developer.access.core.PortStatus
import developer.access.ui.theme.DevTokens

/**
 * 面板内共用的构件。
 *
 * 取值与助手既有组件语言对齐：卡片圆角取 `shapes.medium`、描边取 `outlineVariant`、
 * 内容边距 16dp、分隔线同源。强调色只用于选中态与状态点，不做装饰。
 */

@Composable
internal fun DevCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
        content = content,
    )
}

@Composable
internal fun DevDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * 主分区之间的分隔线（比 [DevDivider] 深一档）。
 *
 * 面板里一级标题有三块（连接方式 / 设备配对 / 设备授权记录），用更深的线把它们分开，
 * 条目归属才一眼可辨；分区内部的子项之间仍用浅线。
 */
@Composable
internal fun DevSectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline),
    )
}

/** 一级分区标题：与「设备授权记录」同款加粗小标题，三块并列时视觉同级。 */
@Composable
internal fun DevSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun DevLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** 标题行 + 开关。开关是卡片内唯一的权限动作。 */
@Composable
internal fun DevHeader(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    busy: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    /**
     * 开关不可用时点击整行触发的反馈。
     *
     * 置灰的 `Switch` 自身不接收点击，若不额外接一层，用户点了没有任何反应，
     * 也就无从知道"为什么不能用"。
     */
    onDisabledClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .then(
                if (!enabled && !busy && onDisabledClick != null) {
                    Modifier.clickable(onClick = onDisabledClick)
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                StatusDot(on = checked)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled && !busy,
        )
    }
}

/** 状态点：开启时用强调色，关闭时用中性描边色。 */
@Composable
internal fun StatusDot(on: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(if (on) DevTokens.Success else MaterialTheme.colorScheme.outline),
    )
}

/** 连接方式单选项；带 warning 的档位在该档被选中后才展开警示，避免常驻噪声。 */
@Composable
internal fun TransportOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    warning: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioMark(selected = selected)
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (selected && warning != null) {
            Row(
                modifier = Modifier.padding(start = 42.dp, end = 16.dp, bottom = 12.dp),
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
                    text = warning,
                    style = MaterialTheme.typography.labelMedium,
                    color = warningTextColor(),
                )
            }
        }
    }
}

@Composable
internal fun RadioMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .border(
                width = if (selected) 5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
    )
}

/**
 * 轻量文字按钮。
 *
 * [onDisabledClick] 用于"不能用，但要告诉用户为什么"的场景：置灰态仍接收点击，
 * 否则用户点了没反应，只能猜。
 */
@Composable
internal fun DevChip(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
        color = if (enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (enabled) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable(
                enabled = enabled || onDisabledClick != null,
                onClick = if (enabled) onClick else ({ onDisabledClick?.invoke() ?: Unit }),
            )            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * 警示文字色：小字需 4.5:1 以上。
 *
 * 浅色下用更深的琥珀（对白底约 5.1:1），深色下用较亮的琥珀（对深表面约 6.4:1）。
 */
@Composable
internal fun warningTextColor(): Color =
    if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFF59E0B) else Color(0xFFB45309)

/**
 * 连接方式的可读表示（即落盘值）。
 *
 * 用于"界面上选的和正在生效的不是一回事"时的提示，故只印真实配置，不做美化。
 */
internal fun developer.access.core.ServerSpec.displaySpec(): String = when (transport) {
    developer.access.core.ServerTransport.UNIX_SOCKET -> "unix"
    developer.access.core.ServerTransport.TCP_LOCAL -> "tcp:localhost:$port"
    developer.access.core.ServerTransport.TCP_LAN -> "tcp:0.0.0.0:$port"
    developer.access.core.ServerTransport.CUSTOM -> customSpec?.trim().orEmpty().ifEmpty { "custom" }
}

/**
 * 端口行：输入框 + 可用性状态 + [应用]。
 *
 * 状态三态呈现（空闲/被占用/无法判定），不把"不知道"说成"空闲"。
 * 被占用时给出的处置建议，**不自动杀他人进程**。
 */
@Composable
internal fun PortRow(
    port: Int,
    status: PortStatus,
    onPortChange: (Int) -> Unit,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.dev_port_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(96.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            BasicTextField(
                value = port.toString(),
                onValueChange = { raw ->
                    raw.filter { it in '0'..'9' }.toIntOrNull()?.let(onPortChange)
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(
                when (status) {
                    PortStatus.FREE -> R.string.dev_port_free
                    PortStatus.OCCUPIED -> R.string.dev_port_occupied
                    PortStatus.UNKNOWN -> R.string.dev_port_unknown
                },
            ),
            style = MaterialTheme.typography.labelMedium,
            color = when (status) {
                PortStatus.FREE -> DevTokens.Success
                PortStatus.OCCUPIED -> MaterialTheme.colorScheme.error
                PortStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.weight(1f),
        )
        DevChip(text = stringResource(R.string.dev_apply), onClick = onApply)
    }
    if (status == PortStatus.OCCUPIED) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
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
                text = stringResource(R.string.dev_port_occupied_hint),
                style = MaterialTheme.typography.labelMedium,
                color = warningTextColor(),
            )
        }
    }
}

/**
 * 通用单行文本输入（等宽小字）。
 *
 * 用于 `ip:端口` 这类地址输入：不做格式校验（由动作侧判定并给出具体原因），
 * 只负责把内容交给调用方。
 */
@Composable
internal fun DevTextInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
    }
}

/** 自定义 socket spec 输入行。 */
@Composable
internal fun SpecInput(    value: String,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.dev_custom_hint),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(Modifier.width(12.dp))
        DevChip(
            text = stringResource(R.string.dev_apply),
            onClick = onApply,
            enabled = value.isNotBlank(),
        )
    }
}

/** 分组内的说明文字。 */
@Composable
internal fun DevNote(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
    )
}

/** 一组动作按钮行。 */
@Composable
internal fun DevActionRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}
