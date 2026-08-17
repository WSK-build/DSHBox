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
    sandboxReady: Boolean,
    sandboxError: Boolean,
    sandboxStopped: Boolean = false,
    runtimeInstalled: Boolean,
    bundledRuntimeAvailable: Boolean,
    onNavigateToSettings: () -> Unit,
) {
    val context = LocalContext.current
    var showStopDialog by remember { mutableStateOf(false) }

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

        StatusCard(
            sandboxReady = sandboxReady,
            sandboxError = sandboxError,
            sandboxStopped = sandboxStopped,
            onRetry = { SandboxService.restart(context) },
            onViewDiagnostics = onNavigateToSettings,
        )
        AddressCard(context = context)
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            shape = MaterialTheme.shapes.medium,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.DSH_BASE_URL))
                context.startActivity(intent)
            },
            enabled = sandboxReady,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(stringResource(R.string.home_open))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                shape = MaterialTheme.shapes.medium,
                onClick = { SandboxService.restart(context) },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.home_restart))
            }
            OutlinedButton(
                shape = MaterialTheme.shapes.medium,
                onClick = { showStopDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                Icon(
                    imageVector = AppIconsStop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.home_stop))
            }
        }
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(stringResource(R.string.home_stop_confirm_title)) },
            text = { Text(stringResource(R.string.home_stop_confirm_message)) },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = {
                        showStopDialog = false
                        SandboxService.stop(context)
                    },
                ) {
                    Text(stringResource(R.string.home_stop_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopDialog = false }) {
                    Text(stringResource(R.string.home_stop_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusCard(
    sandboxReady: Boolean,
    sandboxError: Boolean,
    sandboxStopped: Boolean = false,
    onRetry: () -> Unit,
    onViewDiagnostics: () -> Unit,
) {
    var elapsedSeconds by remember { mutableStateOf(0L) }
    // The uptime clock starts when the sandbox becomes ready and resets when
    // it leaves the ready state (e.g. after a restart), matching the real
    // runtime instead of the composable lifetime.
    LaunchedEffect(sandboxReady) {
        elapsedSeconds = 0L
        if (sandboxReady) {
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
            sandboxStopped -> R.string.home_stopped
            sandboxReady -> R.string.home_ready
            sandboxError -> R.string.home_environment_error
            else -> R.string.home_starting
        },
    )
    val statusColor = when {
        sandboxStopped -> MaterialTheme.colorScheme.outline
        sandboxReady -> MaterialTheme.colorScheme.primary
        sandboxError -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = when {
            sandboxReady -> MaterialTheme.colorScheme.surface
            sandboxError -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            when {
                sandboxReady -> MaterialTheme.colorScheme.outlineVariant
                sandboxError -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outlineVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
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
                    color = when {
                        sandboxReady -> MaterialTheme.colorScheme.onSurface
                        sandboxError -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = Constants.DSH_BASE_URL,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Spacer(modifier = Modifier.height(4.dp))
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
                if (sandboxError) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            shape = MaterialTheme.shapes.medium,
                            onClick = onRetry,
                            modifier = Modifier.height(36.dp),
                        ) {
                            Text(stringResource(R.string.home_retry))
                        }
                        OutlinedButton(
                            shape = MaterialTheme.shapes.medium,
                            onClick = onViewDiagnostics,
                            modifier = Modifier.height(36.dp),
                        ) {
                            Text(stringResource(R.string.home_view_diagnostics))
                        }
                    }
                }
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
