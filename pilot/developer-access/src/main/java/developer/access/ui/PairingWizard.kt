package developer.access.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import developer.access.R
import developer.access.adb.PairingCode

/**
 * 配对码向导。
 *
 * ## 为什么分屏引导是必需的
 *
 * 配对码显示在**手机系统设置**（开发者选项 → 无线调试 → 使用配对码配对）里，
 * 而输入框在本应用里——两个界面抢同一块屏幕。故第二步明确给出分屏建议，
 * 并把用户要做的事压到最小：**只抄 6 位码**。
 *
 * ## 地址由应用自动填入
 *
 * 配对端口通过 mDNS 自动发现（`_adb-tls-pairing._tcp`），用户不需要看 IP 与端口。
 * 未发现时如实告知"请确认无线调试已开启"，而不是让用户手填。
 *
 * ## 配对 ≠ 连接
 *
 * `adb pair` 成功只代表公钥进了设备；真正建立连接还要对**连接端口**执行
 * `adb connect`（两个端口是不同服务，）。故第三步只在两步都成功后出现。
 */
enum class PairingStep { INTRO, CODE, DONE }

/**
 * 配对向导。
 *
 * @param step 当前步骤。
 * @param code 已输入的配对码（仅数字）。
 * @param busy 正在配对（会锁住码输入）。
 * @param discovering 正在自动检测地址（不锁输入）。
 * @param error 失败原因；为空表示无错误。
 * @param address 已发现的配对地址（展示用）。
 * @param pairDraft 手填的配对地址（`ip:端口`）；自动发现失败时用它。
 * @param onPairDraftChange 手填配对地址的变化。
 * @param needsConnectTarget 配对成功但自动连接失败，需要用户抄连接地址。
 * @param connectDraft 手填的连接地址。
 * @param connectAddress 已建立的连接地址（成功页展示并可复制）。
 * @param onNext 第一步的"我已开启，下一步"（触发自动发现）。
 * @param onRedetect 第二步的"重新检测地址"：手机侧弹窗稍后才开时，不必退回第一步。
 * @param onCodeChange 输入变化；**填满 6 位即由调用方提交**。
 * @param onConnectDraftChange 手填连接地址的变化。
 * @param onConnect 用手填地址执行连接。
 * @param onRestart 回到第二步重新获取配对码。
 * @param onDone 完成并关闭。
 */
@Composable
fun PairingWizard(
    step: PairingStep,
    code: String,
    busy: Boolean,
    discovering: Boolean,
    error: String?,
    address: String,
    pairDraft: String,
    needsConnectTarget: Boolean,
    connectDraft: String,
    connectAddress: String,
    onNext: () -> Unit,
    onRedetect: () -> Unit,
    onCodeChange: (String) -> Unit,
    onPairDraftChange: (String) -> Unit,
    onConnectDraftChange: (String) -> Unit,
    onConnect: () -> Unit,
    onRestart: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StepHeader(step)

        when (step) {
            PairingStep.INTRO -> {
                StepLine(stringResource(R.string.dev_pairing_intro))
                Text(
                    text = if (address.isNotBlank()) {
                        stringResource(R.string.dev_pairing_found, address)
                    } else {
                        stringResource(R.string.dev_pairing_auto_hint)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ActionText(
                    text = stringResource(
                        if (busy) R.string.dev_pairing_searching else R.string.dev_pairing_next,
                    ),
                    enabled = !busy,
                    onClick = onNext,
                )
            }

            PairingStep.CODE -> {
                StepLine(stringResource(R.string.dev_pairing_code_step))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dev_pairing_split_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (address.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.dev_pairing_address, address),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 自动发现不是每次都能成功：始终给出手填入口，用户从「使用配对码配对」
                // 弹窗里抄 IP 和端口即可，不再卡在"找不到服务"。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.dev_pairing_pair_address_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 进入第二步时它可能还没广播（弹窗稍后才开），给一个就地重查入口。
                    ActionText(
                        text = stringResource(
                            if (discovering) {
                                R.string.dev_pairing_searching
                            } else {
                                R.string.dev_pairing_redetect
                            },
                        ),
                        enabled = !discovering,
                        onClick = onRedetect,
                    )
                }
                DevTextInput(
                    value = pairDraft,
                    placeholder = stringResource(R.string.dev_pairing_pair_address_hint),
                    onValueChange = onPairDraftChange,
                )
                StepLine(stringResource(R.string.dev_pairing_code_step2))
                CodeField(code = code, enabled = !busy, onCodeChange = onCodeChange)
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (busy) {
                        Text(
                            text = stringResource(R.string.dev_pairing_busy),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        ActionText(
                            text = stringResource(R.string.dev_pairing_restart),
                            enabled = true,
                            onClick = onRestart,
                        )
                    }
                }
            }

            PairingStep.DONE -> {
                Text(
                    text = stringResource(R.string.dev_pairing_done_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.dev_pairing_done_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 连接地址是本侧唯一可复制的"配对结果"——配对码本身在手机设置里，抄不出来。
                if (connectAddress.isNotBlank()) {
                    val clipboard = LocalClipboardManager.current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.dev_pairing_connected, connectAddress),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        DevChip(
                            text = stringResource(R.string.dev_access_code_copy),
                            onClick = { clipboard.setText(AnnotatedString(connectAddress)) },
                        )
                    }
                    // 说明本侧能复制什么、不能复制什么，避免用户去找一个不存在的"配对码"。
                    Text(
                        text = stringResource(R.string.dev_pairing_copy_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ActionText(
                    text = stringResource(R.string.dev_pairing_done_action),
                    enabled = true,
                    onClick = onDone,
                )
            }
        }

        // 自动发现拿不到连接端口时的回落：配对端口与连接端口是两个服务，
        // 拿错端口去 connect 必然失败，故让用户从无线调试界面抄一次。
        if (needsConnectTarget) {
            StepLine(stringResource(R.string.dev_pairing_connect_title))
            Text(
                text = stringResource(R.string.dev_pairing_connect_hint),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConnectField(
                value = connectDraft,
                enabled = !busy,
                onValueChange = onConnectDraftChange,
            )
            ActionText(
                text = stringResource(R.string.dev_pairing_connect_action),
                enabled = !busy && connectDraft.isNotBlank(),
                onClick = onConnect,
            )
        }
    }
}

/** 连接地址输入：`ip:端口`，只做展示宽度限制，合法性由动作侧判定。 */
@Composable
private fun ConnectField(value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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
}

@Composable
private fun StepHeader(step: PairingStep) {
    val index = when (step) {
        PairingStep.INTRO -> 1
        PairingStep.CODE -> 2
        PairingStep.DONE -> 3
    }
    Text(
        text = stringResource(R.string.dev_pairing_step, index),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 步骤说明行；编号已含在文案里，此处只负责排版。 */
@Composable
private fun StepLine(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 6 位配对码输入：单框等宽大字号 + 字距。
 *
 * 不做六个独立方格的写法：粘贴时分散格会丢字符，而配对码常从别处复制过来。
 * 填满 6 位由调用方（onCodeChange）立即提交。
 */
@Composable
private fun CodeField(code: String, enabled: Boolean, onCodeChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { focusRequester.requestFocus() },
    ) {
        // 六个格子只负责显示；输入由一个覆盖其上的透明输入框统一承载，
        // 这样既保留"自动分格"的视觉，又不会像六个独立输入框那样在粘贴时丢字符。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            repeat(CODE_LENGTH) { index ->
                val digit = code.getOrNull(index)?.toString().orEmpty()
                val active = enabled && code.length < CODE_LENGTH && index == code.length
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = if (active) 1.5.dp else 1.dp,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = digit,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        BasicTextField(
            value = code,
            onValueChange = { raw -> onCodeChange(PairingCode.normalize(raw)) },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(fontSize = 1.sp, color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            // 输入框只为承载按键与粘贴，视觉全部由上面六个格子负责。
            //
            // **不能只覆盖在格子上**：字色/光标刷透明、甚至整层 alpha=0 之后，
            // 系统仍会在输入框原点画出插入手柄（设备上表现为第一个格子左上角的绿色水滴），
            // 它不属于该层的绘制内容，遮不住。改为把输入框缩到 1dp 并挪到格子区域之外，
            // 手柄随之落在外框裁剪区外；点击格子仍由 requestFocus 把焦点交给它。
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = (-8).dp, y = (-8).dp)
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester),
        )
    }
}

/** 配对码位数：系统固定 6 位。 */
private const val CODE_LENGTH = 6

/** 文字动作（与面板 DevChip 同源的轻量按钮）。 */
@Composable
private fun ActionText(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (enabled) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
