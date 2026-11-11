package com.dshbox.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.dshbox.app.R
import com.dshbox.app.util.AppUpdater

/**
 * 「点 Star」引导弹窗：设置页与首页版本更新卡共用。先说明为什么值得点，
 * 再由用户确认跳转；跳转失败（无浏览器）静默忽略，不打断当前操作。
 */
@Composable
internal fun StarFeedbackDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_feedback_star_title)) },
        text = { Text(stringResource(R.string.settings_feedback_star_msg)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                AppUpdater.openUrl(context, AppUpdater.REPO_URL)
            }) {
                Text(stringResource(R.string.settings_feedback_star_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                // 用「稍后再说」而非「取消」：非破坏性引导，措辞更柔和。
                Text(stringResource(R.string.settings_feedback_later))
            }
        },
    )
}

/**
 * 「提 Issue」引导弹窗：设置页与首页版本更新卡共用。正文给出复现步骤/
 * 截图建议，降低提交门槛；同样先确认再跳转。
 */
@Composable
internal fun IssueFeedbackDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_feedback_issue_title)) },
        text = { Text(stringResource(R.string.settings_feedback_issue_msg)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                AppUpdater.openUrl(context, AppUpdater.ISSUES_URL)
            }) {
                Text(stringResource(R.string.settings_feedback_issue_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_feedback_later))
            }
        },
    )
}
