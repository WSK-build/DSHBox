package com.dshbox.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.dsh.DshRendererReportStore
import com.dshbox.app.dsh.DshDiagnosticLogs
import com.dshbox.app.dsh.DshLogWindow
import com.dshbox.app.ui.files.PrimaryGreen
import com.dshbox.app.ui.webview.DshRendererReportCard
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Diagnostics shows the report populated by the current real DSH container and
 * the existing bounded process-log views. The report card never creates an
 * off-screen WebView or turns capability data into a business-ready verdict.
 */
@Composable
fun DiagnosticsScreen(
    sandboxReady: Boolean,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** 宿主页签失活时关闭返回接管，避免不可见子页吞掉其它页签的返回键。 */
    backEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val logsDir = File(context.filesDir, "logs")
    val rendererReport by DshRendererReportStore.report.collectAsState()
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableIntStateOf(0) }

    data class LogEntry(val titleRes: Int, val fileName: String)
    var snapshots by remember { mutableStateOf<List<Pair<LogEntry, DshLogWindow>>>(emptyList()) }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val entries = remember(logsDir) {
        listOf(
            LogEntry(R.string.diagnostics_log_dsh, "process-dsh.log"),
            LogEntry(R.string.diagnostics_log_sandbox, "process-sandbox.log"),
            LogEntry(R.string.diagnostics_log_guest, "process-guest.log"),
        )
    }

    LaunchedEffect(logsDir, refreshTick) {
        snapshots = withContext(Dispatchers.IO) {
            entries.map { entry -> entry to DshDiagnosticLogs.readTail(File(logsDir, entry.fileName), TAIL_LINES) }
        }
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri == null || payload == null) {
            exporting = false
        } else {
            scope.launch {
                val succeeded = withContext(Dispatchers.IO) {
                    runCatching {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: throw IOException("Export destination unavailable")
                        output.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                    }.isSuccess
                }
                exporting = false
                Toast.makeText(context,
                    if (succeeded) R.string.diagnostics_export_done else R.string.dsh_renderer_export_failed,
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackHandler(enabled = backEnabled, onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_diagnostics),
            style = MaterialTheme.typography.titleLarge,
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

        DshRendererReportCard(
            report = rendererReport,
            onViewLogs = onOpenLogs,
        )

        Text(
            text = stringResource(R.string.diagnostics_log_title),
            style = MaterialTheme.typography.titleMedium,
        )

        OutlinedButton(onClick = { refreshTick += 1 }) {
            Text(stringResource(R.string.files_refresh))
        }

        for ((entry, window) in snapshots) {
            val logContent = remember(window) { window.exportText() }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(entry.titleRes) + "（" + entry.fileName + "）",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        CopyButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(ClipboardManager::class.java)
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText(entry.fileName, logContent),
                                )
                                Toast.makeText(
                                    context,
                                    R.string.diagnostics_copy_done,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                        )
                    }
                    Text(
                        text = logContent.ifEmpty { stringResource(R.string.diagnostics_empty) },
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }

        OutlinedButton(
            shape = MaterialTheme.shapes.medium,
            enabled = !exporting,
            onClick = {
                exporting = true
                val report = rendererReport
                scope.launch {
                    try {
                        pendingExport = withContext(Dispatchers.IO) {
                            buildString {
                                append(DshDiagnosticLogs.exportReport(report))
                                for (entry in entries) {
                                    for (suffix in listOf(".prev", "")) {
                                        val file = File(logsDir, entry.fileName + suffix)
                                        if (suffix.isNotEmpty() && !file.isFile) continue
                                        append(DshDiagnosticLogs.readTail(file, TAIL_LINES).exportText())
                                    }
                                }
                            }
                        }
                        exportLogLauncher.launch("dsh-log.txt")
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        pendingExport = null
                        exporting = false
                        Toast.makeText(context, R.string.dsh_renderer_export_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
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

/** Full-screen bounded log viewer opened from the renderer report card. */
@Composable
fun DshLogViewerScreen(
    title: String,
    content: String,
    onBack: () -> Unit,
    backEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = backEnabled, onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
        )
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = content.ifEmpty { stringResource(R.string.diagnostics_empty) },
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        Button(
            shape = MaterialTheme.shapes.medium,
            onClick = onBack,
        ) {
            Text(stringResource(R.string.diagnostics_back))
        }
    }
}

/** Each refresh/export captures a new bounded window and records its timestamp/truncation. */
private const val TAIL_LINES = 150

/** Copy the same bounded, redacted snapshot shown in the log card. */
@Composable
private fun CopyButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, PrimaryGreen),
    ) {
        Text(
            text = stringResource(R.string.diagnostics_copy),
            style = MaterialTheme.typography.bodySmall,
            color = PrimaryGreen,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
