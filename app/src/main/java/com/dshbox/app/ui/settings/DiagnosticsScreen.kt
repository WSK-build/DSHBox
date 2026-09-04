package com.dshbox.app.ui.settings

import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import java.io.File

/**
 * 1.1.1 (T3)：诊断日志升级——展示全部进程日志条目（DSH / 沙箱 / 访客命令），
 * 每条尾部最多 [TAIL_LINES] 行（页面内直接可滚动查看，DSH 排障重点依赖其启动
 * 过程输出）；导出时合并当前文件与其 `.prev` 轮转文件（策略 A 保留最近两代）。
 */
@Composable
fun DiagnosticsScreen(
    sandboxReady: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val logsDir = File(context.filesDir, "logs")

    data class LogEntry(val titleRes: Int, val fileName: String)

    val entries = remember(logsDir) {
        listOf(
            LogEntry(R.string.diagnostics_log_dsh, "process-dsh.log"),
            LogEntry(R.string.diagnostics_log_sandbox, "process-sandbox.log"),
            LogEntry(R.string.diagnostics_log_guest, "process-guest.log"),
        ).map { e ->
            val file = File(logsDir, e.fileName)
            val lines = runCatching { file.readLines().takeLast(TAIL_LINES) }.getOrDefault(emptyList())
            e to lines
        }
    }

    // 导出 = 全部条目（当前 + .prev 轮转）合并，带分隔头。
    val exportText = remember(entries) {
        buildString {
            for ((entry, lines) in entries) {
                append("\n===== ${entry.fileName} =====\n")
                append(File(logsDir, entry.fileName + ".prev").takeIf { it.isFile }
                    ?.let { runCatching { it.readText() }.getOrNull() } ?: "")
                append(lines.joinToString("\n"))
                append("\n")
            }
        }
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(exportText.toByteArray())
            }
            Toast.makeText(context, R.string.diagnostics_export_done, Toast.LENGTH_SHORT).show()
        }
        Unit
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_diagnostics),
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = stringResource(R.string.diagnostics_dsh_address) + "：" + Constants.DSH_BASE_URL,
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = stringResource(
                if (sandboxReady) R.string.diagnostics_status_running
                else R.string.diagnostics_status_not_ready,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = stringResource(R.string.diagnostics_log_title),
            style = MaterialTheme.typography.titleMedium,
        )

        for ((entry, lines) in entries) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(entry.titleRes) + "（" + entry.fileName + "）",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = lines.joinToString("\n").ifEmpty { "（空）" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }

        OutlinedButton(
            shape = MaterialTheme.shapes.medium,
            onClick = { exportLogLauncher.launch("dsh-log.txt") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.diagnostics_export))
        }

        Button(
            shape = MaterialTheme.shapes.medium,
            onClick = onBack,
        ) {
            Text(stringResource(R.string.diagnostics_back))
        }
    }
}

/** 诊断页每条日志显示的尾部行数（页面内直接可滚动查看）。 */
private const val TAIL_LINES = 150