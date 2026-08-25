package com.dshbox.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.theme.AppIconsContentCopy
import com.dshbox.app.ui.theme.AppIconsStop
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    sandboxRunning: Boolean,
    sandboxError: Boolean,
    dshReady: Boolean,
    dshError: Boolean,
    runtimeInstalled: Boolean,
    bundledRuntimeAvailable: Boolean,
    onNavigateToSettings: () -> Unit,
) {
    val context = LocalContext.current
    var showSandboxStopDialog by remember { mutableStateOf(false) }
    var showDshStopDialog by remember { mutableStateOf(false) }
    var dshNeedsSandboxToast by remember { mutableStateOf(false) }

    if (dshNeedsSandboxToast) {
        LaunchedEffect(Unit) {
            Toast.makeText(
                context,
                R.string.home_start_dsh_needs_sandbox,
                Toast.LENGTH_LONG,
            ).show()
            dshNeedsSandboxToast = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!runtimeInstalled) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (bundledRuntimeAvailable) R.string.home_runtime_installing else R.string.home_runtime_missing,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    if (bundledRuntimeAvailable) {
                        Text(
                            text = stringResource(R.string.home_runtime_installing_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.home_runtime_missing_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Button(
                            shape = MaterialTheme.shapes.medium,
                            onClick = onNavigateToSettings,
                        ) {
                            Text(stringResource(R.string.home_runtime_import))
                        }
                    }
                }
            }
        }

        SandboxStatusCard(
            sandboxRunning = sandboxRunning,
            sandboxError = sandboxError,
            onStart = { SandboxService.startSandbox(context) },
            onStop = { showSandboxStopDialog = true },
            onRestart = { SandboxService.restartSandbox(context) },
        )

        DshStatusCard(
            dshReady = dshReady,
            dshError = dshError,
            sandboxRunning = sandboxRunning,
            onStart = {
                if (sandboxRunning) {
                    SandboxService.startDsh(context)
                } else {
                    dshNeedsSandboxToast = true
                }
            },
            onStop = { showDshStopDialog = true },
            onRestart = { SandboxService.restartDsh(context) },
        )

        AddressCard(context = context)

        Button(
            shape = MaterialTheme.shapes.medium,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.DSH_BASE_URL))
                context.startActivity(intent)
            },
            enabled = dshReady,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.home_open))
        }
    }

    if (showSandboxStopDialog) {
        AlertDialog(
            onDismissRequest = { showSandboxStopDialog = false },
            title = { Text(stringResource(R.string.home_sandbox_stop_confirm_title)) },
            text = { Text(stringResource(R.string.home_sandbox_stop_confirm_message)) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        showSandboxStopDialog = false
                        SandboxService.stopSandbox(context)
                    },
                ) {
                    Text(stringResource(R.string.home_stop_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSandboxStopDialog = false }) {
                    Text(stringResource(R.string.home_stop_cancel))
                }
            },
        )
    }

    if (showDshStopDialog) {
        AlertDialog(
            onDismissRequest = { showDshStopDialog = false },
            title = { Text(stringResource(R.string.home_dsh_stop_confirm_title)) },
            text = { Text(stringResource(R.string.home_dsh_stop_confirm_message)) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        showDshStopDialog = false
                        SandboxService.stopDsh(context)
                    },
                ) {
                    Text(stringResource(R.string.home_stop_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDshStopDialog = false }) {
                    Text(stringResource(R.string.home_stop_cancel))
                }
            },
        )
    }
}

@Composable
private fun SandboxStatusCard(
    sandboxRunning: Boolean,
    sandboxError: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val statusText = stringResource(
        when {
            sandboxError -> R.string.home_sandbox_error
            sandboxRunning -> R.string.home_sandbox_running
            else -> R.string.home_sandbox_stopped
        },
    )
    val statusColor = when {
        sandboxError -> MaterialTheme.colorScheme.error
        sandboxRunning -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = when {
            sandboxError -> MaterialTheme.colorScheme.errorContainer
            sandboxRunning -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            when {
                sandboxError -> MaterialTheme.colorScheme.error
                sandboxRunning -> MaterialTheme.colorScheme.outlineVariant
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = onStart,
                    enabled = !sandboxRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.home_sandbox_start))
                }
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = onRestart,
                    enabled = sandboxRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.home_sandbox_restart))
                }
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = onStop,
                    enabled = sandboxRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = AppIconsStop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.home_sandbox_stop))
                }
            }
        }
    }
}

@Composable
private fun DshStatusCard(
    dshReady: Boolean,
    dshError: Boolean,
    sandboxRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    LaunchedEffect(dshReady) {
        elapsedSeconds = 0L
        if (dshReady) {
            while (true) {
                delay(1_000)
                elapsedSeconds += 1
            }
        }
    }
    val uptime = remember(elapsedSeconds) {
        val hours = elapsedSeconds / 3600
        val minutes = (elapsedSeconds % 3600) / 60
        val seconds = elapsedSeconds % 60
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
    val statusText = stringResource(
        when {
            dshError -> R.string.home_dsh_error
            dshReady -> R.string.home_dsh_ready
            else -> R.string.home_dsh_stopped
        },
    )
    val statusColor = when {
        dshError -> MaterialTheme.colorScheme.error
        dshReady -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = when {
            dshError -> MaterialTheme.colorScheme.errorContainer
            dshReady -> MaterialTheme.colorScheme.surface
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            when {
                dshError -> MaterialTheme.colorScheme.error
                dshReady -> MaterialTheme.colorScheme.outlineVariant
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusColor, CircleShape),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = Constants.DSH_BASE_URL,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    Text(
                        text = stringResource(R.string.home_uptime_format, uptime),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                    Text(
                        text = stringResource(R.string.home_version_format, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = onStart,
                    enabled = sandboxRunning && !dshReady,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.home_dsh_start))
                }
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = onRestart,
                    enabled = dshReady || sandboxRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.home_dsh_restart))
                }
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = onStop,
                    enabled = dshReady || dshError,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = AppIconsStop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.home_dsh_stop))
                }
            }
            if (!sandboxRunning) {
                Text(
                    text = stringResource(R.string.home_dsh_needs_sandbox_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddressCard(context: Context) {
    var copied by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_address_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = Constants.DSH_BASE_URL,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Text(
                    text = stringResource(R.string.home_address_caption),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DSH URL", Constants.DSH_BASE_URL))
                    copied = true
                    Toast.makeText(context, R.string.home_copied, Toast.LENGTH_SHORT).show()
                },
            ) {
                Icon(
                    imageVector = AppIconsContentCopy,
                    contentDescription = stringResource(R.string.home_copy),
                )
            }
        }
    }
}
