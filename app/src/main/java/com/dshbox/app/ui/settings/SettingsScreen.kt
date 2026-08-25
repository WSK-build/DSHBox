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
import androidx.compose.runtime.collectAsState
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
import com.dshbox.app.util.ArchiveExtractor
import com.dshbox.app.util.formatFileSize
import com.dshbox.app.util.queryDisplayName
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.app.sandbox.DshUpdateOutcome
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
    sandboxRunning: Boolean,
    dshReady: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sandboxManager = (context.applicationContext as DshApp).container.sandboxManager
    var showDiagnostics by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    // §7.6 更新DSH 的进度 + 在线更新管理器
    val container = (context.applicationContext as DshApp).container
    val runtimeUpdateManager = container.runtimeUpdateManager
    var dshOnlineUpdating by remember { mutableStateOf(false) }
    var dshOnlineUpdateText by remember { mutableStateOf<String?>(null) }
    // 更新 DSH（离线导入）说明弹窗
    var showDshOfflineInfo by remember { mutableStateOf(false) }
    // 离线上导入运行环境包的二次确认（重置虚拟系统/数据丢失）
    var showImportRuntimeWarn by remember { mutableStateOf(false) }
    // 装配 DSH 移动端适配包（cordis 插件，指令注入方式 B）
    var showAssembleMobileAdapt by remember { mutableStateOf(false) }
    var assembleRunning by remember { mutableStateOf(false) }
    var assembleLog by remember { mutableStateOf<String?>(null) }
    var showAssembleResult by remember { mutableStateOf(false) }

    val importUpdateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
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

    val dshVersion by sandboxManager.dshVersion.collectAsState()
    val dshUpdateProgress by sandboxManager.dshUpdateProgress.collectAsState()

    val importDshLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = installDshFromUri(context, sandboxManager, uri)
                when (result) {
                    is AppResult.Success -> {
                        val newVer = result.value.version?.takeIf { it.isNotBlank() } ?: "—"
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_dsh_updated, newVer),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is AppResult.Failure -> Toast.makeText(
                        context,
                        context.getString(R.string.settings_update_import_failed, result.error.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    if (showDiagnostics) {
        DiagnosticsScreen(
            sandboxReady = sandboxRunning,
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
        SettingsSection(title = stringResource(R.string.settings_section_sandbox)) {
            var storageSize by remember { mutableStateOf("") }
            LaunchedEffect(context.filesDir, sandboxRunning) {
                storageSize = withContext(Dispatchers.IO) {
                    formatFileSize(directorySize(context.filesDir))
                }
            }
            SettingsRow(
                title = stringResource(R.string.settings_sandbox_runtime_env),
                value = stringResource(
                    if (sandboxRunning) R.string.settings_sandbox_ready else R.string.settings_sandbox_not_ready,
                ),
            )
            SettingsRow(
                title = stringResource(R.string.settings_sandbox_storage_usage),
                value = storageSize,
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_sandbox_start),
                onClick = { SandboxService.startSandbox(context) },
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_sandbox_stop),
                onClick = { SandboxService.stopSandbox(context) },
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_sandbox_restart),
                onClick = { SandboxService.restartSandbox(context) },
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_dsh)) {
            SettingsRow(
                title = stringResource(
                    if (dshReady) R.string.home_dsh_ready else R.string.home_dsh_stopped,
                ),
                value = Constants.DSH_BASE_URL,
            )
            SettingsRow(
                title = stringResource(R.string.settings_dsh_version),
                value = dshVersion?.takeIf { it.isNotBlank() } ?: context.getString(R.string.settings_dsh_not_installed),
            )
            val dshUpdateText = dshUpdateProgress
            if (dshUpdateText != null) {
                Text(
                    text = dshUpdateText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            val dshOnlineText = dshOnlineUpdateText
            if (dshOnlineText != null) {
                Text(
                    text = dshOnlineText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
            // §7.6：更新 DSH（在线）——镜像探测 + 下载预编译层 + 版本仲裁替换。
            SettingsActionRow(
                title = stringResource(R.string.settings_dsh_update_online),
                onClick = {
                    if (dshOnlineUpdating) return@SettingsActionRow
                    dshOnlineUpdating = true
                    dshOnlineUpdateText = context.getString(R.string.settings_dsh_update_checking)
                    scope.launch {
                        val res = runtimeUpdateManager.updateDshLatest { msg -> dshOnlineUpdateText = msg }
                        dshOnlineUpdating = false
                        dshOnlineUpdateText = null
                        when (res) {
                            is AppResult.Success -> {
                                val ver = res.value.version?.takeIf { it.isNotBlank() } ?: "—"
                                val msg = if (res.value.changed) {
                                    context.getString(R.string.settings_dsh_updated, ver)
                                } else {
                                    context.getString(R.string.settings_dsh_already_latest, ver)
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                            is AppResult.Failure -> Toast.makeText(
                                context,
                                context.getString(R.string.settings_dsh_update_failed, res.error.message),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
            )
            // 更新 DSH（离线导入）——先弹"导入什么"说明，再选文件。
            SettingsActionRow(
                title = stringResource(R.string.settings_dsh_update_offline),
                onClick = { showDshOfflineInfo = true },
            )
            SettingsActionRow(
                title = stringResource(R.string.home_dsh_start),
                onClick = { SandboxService.startDsh(context) },
            )
            SettingsActionRow(
                title = stringResource(R.string.home_dsh_restart),
                onClick = { SandboxService.restartDsh(context) },
            )
            SettingsActionRow(
                title = stringResource(R.string.home_dsh_stop),
                onClick = { SandboxService.stopDsh(context) },
            )
        }

        // 装配 DSH 移动端适配包（cordis 插件，指令注入方式 B）——位于「外观」上方。
        SettingsActionRow(
            title = stringResource(R.string.settings_assemble_mobile_adapt),
            onClick = { showAssembleMobileAdapt = true },
        )

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
            // Row 1：检查更新 App —— App 自查更新能力尚未接入，机制预留。
            SettingsActionRow(
                title = stringResource(R.string.settings_check_update),
                onClick = {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_check_app_update_reserved),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
            )
            // Row 2：离线导入运行环境包 —— 先弹"重置虚拟系统/数据丢失"二次确认，再选包导入。
            SettingsActionRow(
                title = stringResource(R.string.settings_import_update),
                onClick = { showImportRuntimeWarn = true },
            )
        }

        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            SettingsRow(
                title = stringResource(R.string.app_name),
                value = BuildConfig.VERSION_NAME,
            )
        }
    }

    // 装配 DSH 移动端适配包（指令注入方式 B）：往 DSH guest 注入 install.sh，实时回传状态，失败自动 uninstall 回滚。
    val runAssembleMobileAdapt = fun() {
        if (assembleRunning) return
        assembleRunning = true
        assembleLog = null
        showAssembleMobileAdapt = false
        showAssembleResult = true
        scope.launch {
            val profile = "/root/projects/.dsh/profiles/web"
            val stage = "/root/projects/.dsh/mobile-adapt"
            val res = sandboxManager.runGuestCommand("bash $stage/install.sh $profile") { line ->
                // 过滤 proot/guest 系统级 linker 警告（无意义噪音），只展示真实装配输出。
                if (!(line.contains("WARNING: linker", ignoreCase = true) || line.contains("linkerconfig"))) {
                    assembleLog = (assembleLog ?: "") + line + "\n"
                }
            }
            assembleRunning = false
            when (res) {
                is AppResult.Success -> {
                    runCatching { sandboxManager.restartDsh() }
                    assembleLog = (assembleLog ?: "") + "\n[" + context.getString(R.string.settings_assemble_mobile_adapt_success) + "] " + context.getString(R.string.settings_assemble_mobile_adapt_restart_hint) + "\n"
                }
                is AppResult.Failure -> {
                    scope.launch { sandboxManager.runGuestCommand("bash $stage/uninstall.sh $profile") {} }
                    assembleLog = (assembleLog ?: "") + "\n[" + context.getString(R.string.settings_assemble_mobile_adapt_failed) + "] " + res.error.message + "\n"
                }
            }
        }
    }

    if (showAssembleMobileAdapt) {
        AlertDialog(
            onDismissRequest = { showAssembleMobileAdapt = false },
            title = { Text(stringResource(R.string.settings_assemble_mobile_adapt)) },
            text = { Text(stringResource(R.string.settings_assemble_mobile_adapt_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = { runAssembleMobileAdapt() }) {
                    Text(stringResource(R.string.settings_assemble_mobile_adapt_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAssembleMobileAdapt = false }) {
                    Text(stringResource(R.string.settings_cancel_action))
                }
            },
        )
    }

    if (showAssembleResult) {
        AlertDialog(
            onDismissRequest = { showAssembleResult = false },
            title = { Text(stringResource(R.string.settings_assemble_mobile_adapt_result_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (assembleRunning) {
                        Text(
                            stringResource(R.string.settings_assemble_mobile_adapt_running),
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    val log = assembleLog
                    if (!log.isNullOrBlank()) Text(log, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAssembleResult = false }) {
                    Text(stringResource(R.string.home_stop_cancel))
                }
            },
        )
    }

    if (showDshOfflineInfo) {
        AlertDialog(
            onDismissRequest = { showDshOfflineInfo = false },
            title = { Text(stringResource(R.string.settings_dsh_import_info_title)) },
            text = { Text(stringResource(R.string.settings_dsh_import_info_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showDshOfflineInfo = false
                    importDshLauncher.launch(arrayOf("*/*"))
                }) {
                    Text(stringResource(R.string.settings_dsh_import_info_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDshOfflineInfo = false }) {
                    Text(stringResource(R.string.settings_cancel_action))
                }
            },
        )
    }

    if (showImportRuntimeWarn) {
        AlertDialog(
            onDismissRequest = { showImportRuntimeWarn = false },
            title = { Text(stringResource(R.string.settings_import_runtime_confirm_title)) },
            text = { Text(stringResource(R.string.settings_import_runtime_confirm_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    showImportRuntimeWarn = false
                    importUpdateLauncher.launch(arrayOf("*/*"))
                }) {
                    Text(stringResource(R.string.settings_import_runtime_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportRuntimeWarn = false }) {
                    Text(stringResource(R.string.settings_cancel_action))
                }
            },
        )
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
    // Copy the picked runtime bundle (zip of layered body) into a temp file, then
    // hand it to SandboxManager.importRuntimeBundle (layered clean-replace per §2.3:
    // new body -> runtime-current, old body -> previous/, protects DSH layer + user-data).
    val target = File(context.cacheDir, "runtime-bundle-${System.currentTimeMillis()}.zip")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: return@withContext AppResult.Failure(AppError("UPDATE_READ_FAILED", "无法读取所选文件"))
    try {
        sandboxManager.stopSandbox()
        sandboxManager.importRuntimeBundle(target)
    } finally {
        target.delete()
    }
}

private suspend fun installDshFromUri(
    context: Context,
    sandboxManager: SandboxManager,
    uri: Uri,
): AppResult<DshUpdateOutcome> = withContext(Dispatchers.IO) {
    // 接受：单个 .tar.gz / .tar.zst（DSH 层包）或 .zip（内含 dsh_layer.tar.* + 可选 .sha256）。
    // 不依赖文件名——按内容魔数识别类型。
    val probe = File(context.cacheDir, "dsh-import-${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(uri)?.use { input ->
        probe.outputStream().use { output -> input.copyTo(output) }
    } ?: return@withContext AppResult.Failure(AppError("DSH_READ_FAILED", "无法读取所选文件"))
    try {
        when (ArchiveExtractor.detectFormat(probe)) {
            ArchiveExtractor.Format.ZIP -> {
                val staging = File(context.cacheDir, "dsh-import-staging-${System.currentTimeMillis()}")
                try {
                    ArchiveExtractor.extract(probe, staging, null)
                    val layer = staging.listFiles()?.firstOrNull {
                        it.isFile && (it.name.endsWith(".tar.gz") || it.name.endsWith(".tar.zst"))
                    } ?: return@withContext AppResult.Failure(
                        AppError("DSH_BUNDLE_NOT_FOUND", "zip 内未找到 DSH 层包（dsh_layer.tar.*）"),
                    )
                    val sha = File(staging, "${layer.name}.sha256")
                        .takeIf { it.isFile }
                        ?.readText()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                    sandboxManager.updateDsh(layer, sha, null)
                } finally {
                    staging.deleteRecursively()
                }
            }
            else -> sandboxManager.updateDsh(probe, null, null)
        }
    } finally {
        probe.delete()
    }
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
