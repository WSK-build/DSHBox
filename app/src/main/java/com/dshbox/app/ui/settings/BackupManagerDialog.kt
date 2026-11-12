package com.dshbox.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.instance.BackupEntry
import com.dshbox.app.instance.InstanceOpState
import com.dshbox.app.service.SandboxService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 备份管理对话框：罗列主实例备份（`.dsh-backup-*`，时间倒序），每项可单独还原或删除
 * （均强确认）。**无「全删」**——用户逐项选择。还原为复制模式（自动备份现状、原备份保留）。
 */
@Composable
fun BackupManagerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val registry = (context.applicationContext as DshApp).container.instanceRegistry
    val op by InstanceOpState.state.collectAsState()
    var backups by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<BackupEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<BackupEntry?>(null) }
    val busy = op.running || com.dshbox.app.util.BackgroundOps.isBusy

    // 打开时加载；任一实例操作结束后刷新列表。
    LaunchedEffect(op.running, op.lastSuccess) {
        if (!op.running) {
            backups = withContext(Dispatchers.IO) { registry.listBackups() }
            loaded = true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = stringResource(R.string.backup_manager_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (op.kind == InstanceOpState.Kind.RESTORE_BACKUP || op.kind == InstanceOpState.Kind.DELETE_BACKUP) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = when {
                            op.running -> stringResource(R.string.instance_op_running)
                            op.error != null -> stringResource(R.string.instance_op_failed) + ": " + op.error
                            op.lastSuccess == true -> stringResource(R.string.instance_op_success)
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (op.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                when {
                    !loaded -> CircularProgressIndicator(modifier = Modifier.height(24.dp).width(24.dp))
                    backups.isEmpty() -> Text(
                        text = stringResource(R.string.backup_manager_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        backups.forEach { entry ->
                            BackupRow(
                                entry = entry,
                                busy = busy,
                                onRestore = { restoreTarget = entry },
                                onDelete = { deleteTarget = entry },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.instance_cancel)) }
                }
            }
        }
    }

    restoreTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        SandboxService.restoreBackup(context, entry.name)
                        restoreTarget = null
                    },
                ) { Text(stringResource(R.string.instance_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text(stringResource(R.string.instance_cancel)) }
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.backup_delete_confirm_title)) },
            text = { Text(stringResource(R.string.backup_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        SandboxService.deleteBackup(context, entry.name)
                        deleteTarget = null
                    },
                ) { Text(stringResource(R.string.instance_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.instance_cancel)) }
            },
        )
    }
}

@Composable
private fun BackupRow(
    entry: BackupEntry,
    busy: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val time = remember(entry.createdAtMillis) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(entry.createdAtMillis))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = time, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = formatSize(entry.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedButton(
            onClick = onRestore,
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(stringResource(R.string.backup_restore_action), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
        Spacer(modifier = Modifier.width(6.dp))
        OutlinedButton(
            onClick = onDelete,
            enabled = !busy,
            shape = MaterialTheme.shapes.medium,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(stringResource(R.string.backup_delete_action), style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}