package com.dshbox.remote.render

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dshbox.remote.DownloadNotice
import com.dshbox.remote.R
import com.dshbox.remote.RemoteConnectionController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 远程下载器：代理/直连会话落盘后从底部拉起，读取下载目录本身作为列表
 * （最新修改时间在前）。路径区分应用内「共享区」与系统文件的「内部存储」，
 * 两者是同一份数据。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteDownloadSheet(
    controller: RemoteConnectionController,
    notice: DownloadNotice,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var files by remember(notice.id) { mutableStateOf(controller.downloadedFiles()) }
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.remote_download_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            // 路径信息块：一处写清两个入口，避免用户以为落到了两个地方
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.remote_download_share),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.remote_download_system),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!notice.success && notice.id > 0L) {
                Text(
                    text = failureText(notice.reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!controller.hasDownloadPermission()) {
                Text(
                    text = stringResource(R.string.remote_download_perm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                SheetActionButton(
                    label = stringResource(R.string.remote_download_grant),
                    onClick = { controller.requestDownloadPermission() },
                )
            }
            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.remote_download_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                ) {
                    items(files, key = { it.absolutePath to it.lastModified() }) { file ->
                        DownloadRow(
                            file = file,
                            subtitle = fileSizeText(file.length()) + " · " +
                                timeFormat.format(Date(file.lastModified())),
                            onClick = { openDownloadedFile(context, file) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                // 「打开文件夹」：点击时才查权限——无权限先去系统设置页并提示
                //（本 sheet 的进入路径不预检，避免每次拉起都打扰）；有权限交给
                // 宿主跳转（如文件页定位共享区下载目录），宿主无入口时静默保持。
                SheetActionButton(
                    label = stringResource(R.string.remote_download_open_folder),
                    onClick = {
                        if (!controller.hasDownloadPermission()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.remote_download_perm),
                                Toast.LENGTH_SHORT,
                            ).show()
                            controller.requestDownloadPermission()
                        } else {
                            controller.openDownloadsFolder()
                        }
                    },
                )
                SheetActionButton(
                    label = stringResource(R.string.remote_download_copy),
                    onClick = {
                        val path = controller.downloadDirectory?.absolutePath.orEmpty()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("DSHBox download path", path))
                    },
                )
                SheetActionButton(
                    label = stringResource(R.string.remote_download_back),
                    onClick = onDismiss,
                )
            }
        }
    }
}

/** 底部动作：灰色圆角方框 + 主色文字（与首页 DSH 卡片“更多”同款）。 */
@Composable
private fun SheetActionButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DownloadRow(file: File, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun failureText(reason: String?): String = when (reason) {
    "permission" -> stringResource(R.string.remote_download_fail_perm)
    "origin" -> stringResource(R.string.remote_download_fail_origin)
    "http" -> stringResource(R.string.remote_download_fail_http)
    "dir_unavailable" -> stringResource(R.string.remote_download_fail_dir)
    else -> stringResource(R.string.remote_download_fail_io)
}

/** 用系统默认应用打开下载好的文件；FileProvider 已声明共享区路径。 */
private fun openDownloadedFile(context: Context, file: File) {
    runCatching {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val mime = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?: "*/*"
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun fileSizeText(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
