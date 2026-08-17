package com.dshbox.app.ui.settings

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshbox.app.BuildConfig
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.util.formatFileSize
import com.dshbox.app.util.queryDisplayName
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.app.service.SandboxService
import com.dshbox.app.ui.theme.AppThemeState
import com.dshbox.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    sandboxReady: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sandboxManager = (context.applicationContext as DshApp).container.sandboxManager
    var showDiagnostics by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }

    val importUpdateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = installUpdateFromUri(context, sandboxManager, uri)
                val message = when (result) {
                    is AppResult.Success -> context.getString(R.string.settings_update_imported)
                    is AppResult.Failure -> context.getString(
                        R.string.settings_update_import_failed,
                        result.error.message,
                    )
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showDiagnostics) {
        DiagnosticsScreen(
            sandboxReady = sandboxReady,
            onBack = { showDiagnostics = false },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsSection(title = stringResource(R.string.settings_section_dsh)) {
            SettingsRow(
                title = stringResource(
                    if (sandboxReady) R.string.home_ready else R.string.home_starting,
                ),
                value = Constants.DSH_BASE_URL,
            )
            SettingsActionRow(
                title = stringResource(R.string.home_restart),
                onClick = { SandboxService.restart(context) },
            )
            SettingsActionRow(
                title = stringResource(R.string.home_stop),
                onClick = { SandboxService.stop(context) },
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_sandbox)) {
            var storageSize by remember { mutableStateOf("") }
            // Recompute whenever the sandbox readiness changes: on first boot
            // the bundled runtime is still being extracted when the settings
            // screen first composes, so a one-shot computation would report a
            // near-empty tree (0B) and never refresh. Reading readiness into
            // the key re-runs the computation once extraction finished.
            LaunchedEffect(context.filesDir, sandboxReady) {
                storageSize = withContext(Dispatchers.IO) {
                    formatFileSize(directorySize(context.filesDir))
                }
            }
            SettingsRow(
                title = stringResource(R.string.settings_sandbox_runtime_env),
                value = stringResource(
                    if (sandboxReady) R.string.settings_sandbox_ready else R.string.settings_sandbox_not_ready,
                ),
            )
            SettingsRow(
                title = stringResource(R.string.settings_sandbox_storage_usage),
                value = storageSize,
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_sandbox_start),
                onClick = { SandboxService.start(context) },
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_sandbox_stop),
                onClick = { SandboxService.stop(context) },
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_sandbox_restart),
                onClick = { SandboxService.restart(context) },
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_sandbox_repair),
                onClick = {
                    Toast.makeText(
                        context,
                        R.string.settings_sandbox_repair_message,
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_appearance)) {
            Text(
                text = stringResource(R.string.settings_appearance),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ThemeModeButton(
                    label = stringResource(R.string.settings_appearance_follow_system),
                    selected = AppThemeState.mode == ThemeMode.SYSTEM,
                    onClick = { AppThemeState.setMode(context, ThemeMode.SYSTEM) },
                    modifier = Modifier.weight(1f),
                )
                ThemeModeButton(
                    label = stringResource(R.string.settings_appearance_light),
                    selected = AppThemeState.mode == ThemeMode.LIGHT,
                    onClick = { AppThemeState.setMode(context, ThemeMode.LIGHT) },
                    modifier = Modifier.weight(1f),
                )
                ThemeModeButton(
                    label = stringResource(R.string.settings_appearance_dark),
                    selected = AppThemeState.mode == ThemeMode.DARK,
                    onClick = { AppThemeState.setMode(context, ThemeMode.DARK) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_permissions)) {
            SettingsActionRow(
                title = stringResource(R.string.settings_battery_whitelist),
                onClick = { showBatteryDialog = true },
            )
        }

        SettingsActionRow(
            title = stringResource(R.string.settings_diagnostics),
            onClick = { showDiagnostics = true },
        )

        SettingsSection(title = stringResource(R.string.settings_section_update)) {
            SettingsActionRow(
                title = stringResource(R.string.settings_check_update),
                onClick = {
                    val count = countAvailableUpdates(context)
                    val message = if (count > 0) {
                        context.getString(R.string.settings_update_available, count)
                    } else {
                        context.getString(R.string.settings_update_none)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                },
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_import_update),
                onClick = {
                    importUpdateLauncher.launch(arrayOf("*/*"))
                },
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_rollback),
                onClick = {
                    scope.launch {
                        val result = sandboxManager.rollbackRuntime()
                        val message = if (result is AppResult.Success) {
                            context.getString(R.string.settings_rollback_done)
                        } else {
                            context.getString(R.string.settings_rollback_failed)
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            SettingsRow(
                title = stringResource(R.string.app_name),
                value = BuildConfig.VERSION_NAME,
            )
        }
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            title = { Text(stringResource(R.string.settings_battery_whitelist)) },
            text = { Text(stringResource(R.string.settings_battery_whitelist_message)) },
            confirmButton = {
                TextButton(onClick = { showBatteryDialog = false }) {
                    Text(stringResource(R.string.home_stop_cancel))
                }
            },
        )
    }
}

@Composable
private fun ThemeModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            shape = MaterialTheme.shapes.medium,
            onClick = onClick,
            modifier = modifier.height(44.dp),
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            shape = MaterialTheme.shapes.medium,
            onClick = onClick,
            modifier = modifier.height(44.dp),
        ) {
            Text(label)
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "›",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private suspend fun installUpdateFromUri(
    context: Context,
    sandboxManager: SandboxManager,
    uri: Uri,
): AppResult<Unit> = withContext(Dispatchers.IO) {
    val displayName = queryDisplayName(context, uri) ?: "update.tar.gz"
    val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
    val target = File(updatesDir, displayName)

    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@withContext AppResult.Failure(AppError("UPDATE_READ_FAILED", "无法读取所选文件"))

    val sha256 = sha256File(target)
    File(updatesDir, "$displayName.sha256").writeText(sha256)

    sandboxManager.stop()
    val installed = sandboxManager.installRuntimeBundle(target, sha256)
    if (installed is AppResult.Failure) return@withContext installed
    sandboxManager.promoteRuntimeBundle()
}

private fun countAvailableUpdates(context: Context): Int {
    val updatesDir = File(context.filesDir, "updates")
    if (!updatesDir.isDirectory) return 0
    return updatesDir.listFiles { file -> file.isFile && file.name.endsWith(".tar.gz") }
        ?.count { File(updatesDir, "${it.name}.sha256").isFile }
        ?: 0
}

/**
 * Total logical size of [directory], matching the platform's storage stats
 * (du-style): symbolic links are NOT followed, so linked content is not
 * counted twice. Without this, the thousands of symlinks inside the runtime
 * (node_modules/.bin, debian /usr) inflated the number ~2x.
 *
 * Symlink detection uses android.system.Os.lstat: java.nio.file.Files is only
 * partially implemented on Android and must not be relied on here.
 */
private fun directorySize(directory: File): Long {
    val files = directory.listFiles() ?: return 0L
    var total = 0L
    for (file in files) {
        if (isSymlink(file)) continue
        total += if (file.isDirectory) {
            directorySize(file)
        } else {
            file.length()
        }
    }
    return total
}

private fun isSymlink(file: File): Boolean = try {
    val mode = android.system.Os.lstat(file.path).st_mode
    (mode and android.system.OsConstants.S_IFMT) == android.system.OsConstants.S_IFLNK
} catch (t: Throwable) {
    false
}


private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
