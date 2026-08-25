package com.dshbox.app.ui.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

@Composable
fun DiagnosticsScreen(
    sandboxReady: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val logsDir = File(context.filesDir, "logs")
    val logLines = remember(logsDir) {
        runCatching {
            val dshLog = File(logsDir, "process-dsh.log")
            val sandboxLog = File(logsDir, "process-sandbox.log")
            buildList {
                if (dshLog.isFile) addAll(dshLog.readLines().takeLast(15))
                if (sandboxLog.isFile) addAll(sandboxLog.readLines().takeLast(15))
            }.takeLast(30)
        }.getOrDefault(emptyList())
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(logLines.joinToString("\n").toByteArray())
            }
            Toast.makeText(context, R.string.diagnostics_export_done, Toast.LENGTH_SHORT).show()
        }
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

        Text(
            text = logLines.joinToString("\n").ifEmpty { stringResource(R.string.diagnostics_log_empty) },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )

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
