package developer.access.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import developer.access.R

/**
 * 顶栏右上角的说明入口。
 *
 * 面板承载的是**完全体**能力（开关即授权、不受助手通道安全闸门管控），
 * 用户需要一处不进设置也能读到的定位说明，故入口放在本页顶栏，而不是埋在设置里。
 */
@Composable
fun DeveloperAccessHelpButton() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_dev_help),
            contentDescription = stringResource(R.string.dev_help_action),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) DeveloperAccessHelpDialog(onDismiss = { open = false })
}

/**
 * 说明弹窗：先讲定位（完全体、不受闸门管控），再按通道分条列能力与注意事项。
 *
 * 正文较长，故弹窗内自带滚动；每条一行，避免整段文字在窄屏上糊成一片。
 */
@Composable
private fun DeveloperAccessHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.dev_help_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 开篇定位：这两条通道为什么不与助手通道同规。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.dev_help_lead),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HelpSection(
                    title = stringResource(R.string.dev_help_adb_title),
                    items = listOf(
                        stringResource(R.string.dev_help_adb_switch),
                        stringResource(R.string.dev_help_adb_shared),
                        stringResource(R.string.dev_help_adb_pair),
                        stringResource(R.string.dev_help_adb_direct),
                        stringResource(R.string.dev_help_adb_scope),
                        stringResource(R.string.dev_help_adb_caution),
                    ),
                )

                HelpSection(
                    title = stringResource(R.string.dev_help_root_title),
                    items = listOf(
                        stringResource(R.string.dev_help_root_switch),
                        stringResource(R.string.dev_help_root_shared),
                        stringResource(R.string.dev_help_root_caution),
                        stringResource(R.string.dev_help_root_unrooted),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dev_help_close))
            }
        },
    )
}

/** 一个通道一节：加粗小标题 + 若干条要点。 */
@Composable
private fun HelpSection(title: String, items: List<String>) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 8.dp),
    )
    items.forEach { line ->
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
