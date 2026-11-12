package com.dshbox.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.ui.IssueFeedbackDialog
import com.dshbox.app.ui.StarFeedbackDialog

/**
 * 首页「版本更新要点」卡：仅在「开关开启 && 本版本未展示过」时进入组合。
 *
 * 展示判定由 [AppUpdateHighlightsState] 承载（SharedPreferences 持久化）：
 * `enabled && lastShownVersionCode < BuildConfig.VERSION_CODE` 才展示——新
 * 安装（lastShown 缺省 0）也展示一次；点「关闭」写回当前 versionCode，
 * 本版本不再出现，下个版本重新展示一次。
 *
 * 内容三段：新增 / 修复 / 优化（文案随版本更新在 strings.xml 里维护），
 * 尾部一行小字引导 Star 与问题反馈。两个入口都先弹设置页同款引导窗
 * （[StarFeedbackDialog] / [IssueFeedbackDialog]），确认后才跳转，不直接
 * 把用户带走。视觉与首页其它卡一致：`shapes.large` 圆角 + outlineVariant
 * 描边 + 20dp 内边距。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpdateHighlightsCard(onDismiss: () -> Unit) {
    var showStarDialog by remember { mutableStateOf(false) }
    var showIssueDialog by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.home_update_highlights_title, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium,
            )
            HighlightRow(textRes = R.string.home_update_highlights_added)
            HighlightRow(textRes = R.string.home_update_highlights_fixed)
            HighlightRow(textRes = R.string.home_update_highlights_improved)
            Text(
                text = stringResource(R.string.home_update_highlights_support),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Star / 问题反馈：先弹与设置页同款的引导窗，确认后再跳转
                TextButton(onClick = { showStarDialog = true }) {
                    Text(
                        text = stringResource(R.string.home_update_highlights_star),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { showIssueDialog = true }) {
                    Text(
                        text = stringResource(R.string.home_update_highlights_feedback),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.home_update_highlights_close),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
    if (showStarDialog) {
        StarFeedbackDialog(onDismiss = { showStarDialog = false })
    }
    if (showIssueDialog) {
        IssueFeedbackDialog(onDismiss = { showIssueDialog = false })
    }
}

/** 要点整段：文案自带「新增：」等段标与编号，允许完整换行展示。 */
@Composable
private fun HighlightRow(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
    )
}
