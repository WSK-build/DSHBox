package com.dshbox.pluginmanager.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshbox.pluginmanager.R

/**
 * 「DSHBox专属适配插件」卡片：两个开关（移动端适配包 / DSH连接手机）。
 *
 * 视觉与设置页原来的装配行一致——同样的开关配色、同样的行结构与内边距；
 * 只有标题按小节样式写成小字。
 */
@Composable
fun AdapterPluginsCard(
    controller: com.dshbox.pluginmanager.core.AdapterPluginController,
    dimmed: Boolean,
) {
    val mobileInstalled by controller.mobileInstalled.collectAsState()
    val mobileBusy by controller.busy.collectAsState()
    val mobilePilotEnabled by controller.mobilePilotEnabled.collectAsState()
    val mobilePilotBusy by controller.mobilePilotBusy.collectAsState()
    val lastError = if (mobileInstalled) null else controller.lastError()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Text(
                text = stringResource(R.string.pm_adapt_section_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
            )
            ToggleRow(
                label = stringResource(R.string.pm_adapt_mobile),
                checked = mobileInstalled,
                enabled = !mobileBusy && !dimmed,
                onToggle = { controller.toggleMobileAdapt() },
            )
            ToggleRow(
                label = stringResource(R.string.pm_adapt_mobile_pilot),
                checked = mobilePilotEnabled,
                enabled = !mobilePilotBusy && !dimmed,
                onToggle = { controller.toggleMobilePilot() },
            )
            if (!mobileInstalled && !lastError.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.pm_market_act_why_empty) + "\n" + lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        }
    }
}

/**
 * 「标题 + 可选一行小字 + 右侧开关」的通用行。
 *
 * 开关配色与设置页装配行同款：打开=品牌绿、关闭=灰白。
 * 同时被「DSHBox专属适配插件」卡与「DSH官方插件」子页使用，故为模块内可见。
 */
@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onToggle() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
            )
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onToggle() },
            // 与设置页装配行同款配色：打开=品牌绿、关闭=灰白。
            colors = SwitchDefaults.colors(
                checkedTrackColor = Color(0xFF10A37F),
                uncheckedTrackColor = Color(0xFFD5D5D5),
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color(0xFF9E9E9E),
                checkedBorderColor = Color(0xFF10A37F),
                uncheckedBorderColor = Color(0xFFBDBDBD),
                disabledCheckedTrackColor = Color(0x6610A37F),
                disabledUncheckedTrackColor = Color(0xFFE3E3E3),
            ),
        )
    }
}
