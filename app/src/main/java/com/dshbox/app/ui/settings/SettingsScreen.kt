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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dshbox.app.BuildConfig
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.util.ArchiveErrors
import com.dshbox.app.util.ArchiveExtractor
import com.dshbox.app.util.BackgroundOps
import com.dshbox.app.util.SandboxCleanup
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    // 1.1.0 (M12)：设置页常驻组合（keepAlive），由 MainScreen 传当前页激活状态，
    // 用于「进设置页自动重算存储占用」；dshActive 供 /tmp 智能清理判定。
    isActive: Boolean = false,
    sandboxRunning: Boolean,
    dshReady: Boolean,
    dshActive: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sandboxManager = (context.applicationContext as DshApp).container.sandboxManager
    var showDiagnostics by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    // 1.1.0 (M8)：更新 DSH（在线）改为独立界面 DshUpdateScreen（源探测 → 选版本 → guest npm 安装）。
    var showDshOnlineUpdate by remember { mutableStateOf(false) }
    // 更新 DSH（离线导入）说明弹窗
    var showDshOfflineInfo by remember { mutableStateOf(false) }
    // 离线上导入运行环境包的二次确认（重置虚拟系统/数据丢失）
    var showImportRuntimeWarn by remember { mutableStateOf(false) }
    // 1.1.0 (M9)：两个离线导入的进行中状态（202MB 官方包复制+分层解压耗时较长，必须有反馈）
    var importingRuntime by remember { mutableStateOf(false) }
    var importingDsh by remember { mutableStateOf(false) }
    // 1.1.0 (M12)：存储占用（系统分配块口径，filesDir + cacheDir）与清理弹窗状态。
    var storageScan by remember { mutableStateOf<SandboxCleanup.ScanResult?>(null) }
    var storageScanning by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var cleanupRunning by remember { mutableStateOf(false) }
    var cleanupFreed by remember { mutableStateOf<Long?>(null) }
    // 1.1.0 (M12.1)：各清理项独立勾选（P2⑬）；回滚备份默认不勾，安全项默认勾选。
    val cleanupSelected = remember { mutableStateMapOf<SandboxCleanup.Category, Boolean>() }
    // 1.1.0 (M12.1 P2⑫)：30s 内复用上次扫描；切走页取消扫描协程；支持手动刷新。
    var scanJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastScanAt by remember { mutableStateOf(0L) }
    var lastScanGuard by remember { mutableStateOf<Boolean?>(null) }
    // 装配 DSH 移动端适配包（cordis 插件，指令注入方式 B）
    var assembleRunning by remember { mutableStateOf(false) }
    var assembleChecking by remember { mutableStateOf(false) }
    // 1.1.1 (T2 开关版)：装配状态本地标记（开关瞬时响应），进入设置页自动检测校准。
    val assemblePrefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }
    var assembleInstalled by remember {
        mutableStateOf(assemblePrefs.getBoolean(PREF_MOBILE_ADAPT_INSTALLED, false))
    }
    // 装配行动作占位：运行函数定义在本函数体更后处，用 var 引用、点击时取值。
    var assembleRowAction by remember { mutableStateOf<() -> Unit>({}) }

    // 1.1.1 (T2 开关版)：仅首次（本地标记从未设置过）进入设置页时校准一次开关；
    // 此后开关状态完全由本地标记保持（装配/移除成功时翻转），不再反复查询。
    LaunchedEffect(isActive) {
        if (isActive && !assembleRunning && !assemblePrefs.contains(PREF_MOBILE_ADAPT_INSTALLED)) {
            assembleChecking = true
            val res = sandboxManager.runGuestCommand(
                "grep -q mobile-adapt /root/projects/.dsh/profiles/web",
                onLine = {},
            )
            // 首启校准：内联写入本地标记（setAssembleInstalled 声明在其后，避免前向引用）。
            val detected = res is AppResult.Success
            assembleInstalled = detected
            assemblePrefs.edit().putBoolean(PREF_MOBILE_ADAPT_INSTALLED, detected).apply()
            assembleChecking = false
        }
    }

    // 1.1.1 (T2)：本地装配标记持久化（免 guest 查询：点击即切，瞬时响应；仅装配/移除
    // 成功时更新，失败保持原状）。
    fun setAssembleInstalled(v: Boolean) {
        assembleInstalled = v
        assemblePrefs.edit().putBoolean(PREF_MOBILE_ADAPT_INSTALLED, v).apply()
    }

    // 装配 DSH 移动端适配包（指令注入方式 B）：往 DSH guest 注入 install.sh；
    // 不重启 DSH、无弹窗，结果经 Toast 提示（重启 DSH 后生效）。
    val runAssembleMobileAdapt = fun() {
        if (assembleRunning) return
        assembleRunning = true
        scope.launch {
            val profile = "/root/projects/.dsh/profiles/web"
            val stage = "/root/projects/.dsh/mobile-adapt"
            val res = sandboxManager.runGuestCommand("bash $stage/install.sh $profile", onLine = {})
            assembleRunning = false
            when (res) {
                is AppResult.Success -> {
                    setAssembleInstalled(true)
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_assemble_mobile_adapt_success) +
                            context.getString(R.string.settings_assemble_mobile_adapt_restart_hint),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is AppResult.Failure -> {
                    scope.launch { sandboxManager.runGuestCommand("bash $stage/uninstall.sh $profile", onLine = {}) }
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_assemble_mobile_adapt_failed) + "：" + res.error.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    // 1.1.1 (T2)：一键移除已装配的移动端适配插件（uninstall.sh）；不重启 DSH、无弹窗。
    val runRemoveMobileAdapt = fun() {
        if (assembleRunning) return
        assembleRunning = true
        scope.launch {
            val profile = "/root/projects/.dsh/profiles/web"
            val stage = "/root/projects/.dsh/mobile-adapt"
            val res = sandboxManager.runGuestCommand("bash $stage/uninstall.sh $profile", onLine = {})
            assembleRunning = false
            when (res) {
                is AppResult.Success -> {
                    setAssembleInstalled(false)
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_assemble_mobile_adapt_remove_success) +
                            context.getString(R.string.settings_assemble_mobile_adapt_restart_hint),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                is AppResult.Failure -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_assemble_mobile_adapt_remove_failed) + "：" + res.error.message,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }


    // 装配行一键切换：已装配 → 移除；未装配 → 装配（本地标记瞬时响应）。
    assembleRowAction = {
        if (assembleInstalled) runRemoveMobileAdapt() else runAssembleMobileAdapt()
    }

    // 沙箱或 DSH 任一运行中时，guest /tmp 与 proot 临时目录走 24h 智能清理。
    val tmpGuardActive = sandboxRunning || dshActive
    // 1.1.0 (M12.1 P1③)：后台安装/导入进行中时禁止清理（与其清理目标并发会摧毁
    // 首启安装或打断导入）。
    val busyOps by BackgroundOps.busyCount.collectAsState()
    val maintenanceBusy = busyOps > 0

    fun rescanStorage(force: Boolean = false) {
        if (storageScanning) return
        // 节流：30s 内同策略的扫描直接复用，避免每次切页全量 lstat 十万级文件。
        if (!force && storageScan != null && lastScanGuard == tmpGuardActive &&
            System.currentTimeMillis() - lastScanAt < 30_000L
        ) {
            return
        }
        storageScanning = true
        scanJob?.cancel()
        scanJob = scope.launch {
            try {
                // 预先捕获协程上下文：checkCancelled 回调非 suspend，用外层 context 的
                // job 做取消检测（withContext 继承同一 job，取消即时生效）。
                val jobContext = currentCoroutineContext()
                val result = withContext(Dispatchers.IO) {
                    SandboxCleanup.scan(context, tmpGuardActive, checkCancelled = { jobContext.ensureActive() })
                }
                storageScan = result
                lastScanAt = System.currentTimeMillis()
                lastScanGuard = tmpGuardActive
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } finally {
                // 1.1.0 (M12.1 P1④)：无论成败（含取消）都复位，杜绝「统计中…」死锁。
                storageScanning = false
            }
        }
    }

    // 进设置页自动重算；策略变化（沙箱/DSH 启停）也重算；切走页取消进行中的扫描。
    LaunchedEffect(isActive, tmpGuardActive) {
        if (isActive) rescanStorage() else scanJob?.cancel()
    }


    val importUpdateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importingRuntime = true
                try {
                    // M12.1 P1③：登记后台操作，阻止清理与其并发（写入 cacheDir）。
                    val result = BackgroundOps.runTracked { installUpdateFromUri(context, sandboxManager, uri) }
                    val message = when (result) {
                        is AppResult.Success -> context.getString(R.string.settings_update_imported)
                        is AppResult.Failure -> context.getString(
                            R.string.settings_update_import_failed,
                            result.error.message,
                        )
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // 1.1.0 (M11): 兜底——任何异常都不得直通协程（rememberCoroutineScope
                    // 无异常处理器，直通即 crash）。
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_update_import_failed, e.message ?: "未知错误"),
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    importingRuntime = false
                }
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
                importingDsh = true
                try {
                    // M12.1 P1③：登记后台操作，阻止清理与其并发（写入 cacheDir）。
                    val result = BackgroundOps.runTracked { installDshFromUri(context, sandboxManager, uri) }
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
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    // 1.1.0 (M11): 兜底——函数内已把复制/解压转成 AppResult，此处只防
                    // 未预见的异常路径直通协程导致 crash。
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_update_import_failed, e.message ?: "未知错误"),
                        Toast.LENGTH_LONG,
                    ).show()
                } finally {
                    importingDsh = false
                }
            }
        }
    }

    // 1.1.0 (M8)：在线更新走独立界面（仿 DiagnosticsScreen 的覆盖式挂载）。
    if (showDshOnlineUpdate) {
        DshUpdateScreen(
            onBack = { showDshOnlineUpdate = false },
            modifier = modifier,
        )
        return
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
            SettingsRow(
                title = stringResource(R.string.settings_sandbox_runtime_env),
                value = stringResource(
                    if (sandboxRunning) R.string.settings_sandbox_ready else R.string.settings_sandbox_not_ready,
                ),
            )
            // 1.1.0 (M12)：存储占用拆为「沙盒数据 + 应用缓存」两行，系统分配块口径
            // （含 cacheDir——崩溃残留的导入暂存在这里，系统存储页也把它算在内），
            // 进设置页自动重算；两行均可点击手动强制刷新，行尾刷新图标作提示
            // （M12.1 P2⑫ + M12.3 UX 一致性）。
            val scan = storageScan
            SettingsRow(
                title = stringResource(R.string.settings_storage_data),
                value = when {
                    scan != null -> formatFileSize(scan.dataBytes)
                    else -> stringResource(R.string.settings_storage_scanning)
                },
                onClick = { rescanStorage(force = true) },
                trailingIcon = { RefreshHintIcon() },
            )
            SettingsRow(
                title = stringResource(R.string.settings_storage_cache),
                value = scan?.let { formatFileSize(it.cacheBytes) }
                    ?: stringResource(R.string.settings_storage_scanning),
                onClick = { rescanStorage(force = true) },
                trailingIcon = { RefreshHintIcon() },
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_cleanup_title),
                onClick = {
                    // M12.2：入口预判——后台安装/导入进行中直接 toast，不再先弹窗再显示
                    // 「后台忙」（弹窗内的忙碌分支仍保留，兜住弹窗打开期间新启动的任务）。
                    if (maintenanceBusy) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_cleanup_busy),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        cleanupFreed = null
                        // M12.1 P2⑬：各清理项独立勾选——安全项默认勾选，回滚备份默认不勾。
                        cleanupSelected.clear()
                        SandboxCleanup.Category.values().forEach {
                            cleanupSelected[it] = it != SandboxCleanup.Category.ROLLBACK
                        }
                        showCleanupDialog = true
                        if (storageScan == null) rescanStorage()
                    }
                },
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
            // 1.1.0 (M8)：更新 DSH（在线）——进入独立界面：并行探测各 npm 源
            // （版本号 + 延迟），选源选版本后在沙箱内用 npm 拉取 @deepseek-ai/dsh
            // 及完整依赖替换内置层（沿用官方构建方式）。
            SettingsActionRow(
                title = stringResource(R.string.settings_dsh_update_online),
                onClick = { showDshOnlineUpdate = true },
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
        // 1.1.1 (T2 开关版)：无弹窗开关，进入设置页自动检测校准；切换中禁用防连点。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !assembleRunning && !assembleChecking) { assembleRowAction() }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_assemble_mobile_adapt),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = assembleInstalled,
                enabled = !assembleRunning && !assembleChecking,
                onCheckedChange = { assembleRowAction() },
                // 1.1.1 (T2)：打开=绿、关闭=灰白（覆盖 M3 默认主题色）。
                colors = SwitchDefaults.colors(
                    checkedTrackColor = Color(0xFF10A37F),
                    uncheckedTrackColor = Color(0xFFD5D5D5),
                    checkedThumbColor = Color.White,
                    uncheckedThumbColor = Color(0xFF9E9E9E),
                    checkedBorderColor = Color(0xFF10A37F),
                    uncheckedBorderColor = Color(0xFFBDBDBD),
                    disabledCheckedTrackColor = Color(0x6610A37F),
                    disabledUncheckedTrackColor = Color(0xFFE3E3E3),
                ),
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
                // M12.3：显示 v 前缀，与版本号写法统一（BuildConfig.VERSION_NAME="1.1.0"）。
                value = "v${BuildConfig.VERSION_NAME}",
            )
        }
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

    // 1.1.0 (M9)：导入进行中提示（不可取消——中断会留下半成品，流程内部会自行清理）。
    if (importingRuntime || importingDsh) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.settings_import_running_title)) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.settings_import_running_msg))
                }
            },
            confirmButton = { },
        )
    }

    // 1.1.0 (M12)：清理缓存与垃圾文件。弹窗先展示逐项可释放大小（与实际执行同一
    // 判定函数），各清理项独立勾选（M12.1 P2⑬），确认后执行并回报释放量。
    if (showCleanupDialog) {
        val scan = storageScan
        val rollbackBytes = scan?.reclaimable?.get(SandboxCleanup.Category.ROLLBACK) ?: 0L
        val selectedCategories = SandboxCleanup.Category.values()
            .filter { (cleanupSelected[it] == true) && (scan?.reclaimable?.get(it) ?: 0L) > 0L }
        val anythingToClean = selectedCategories.isNotEmpty()

        fun runCleanup() {
            if (cleanupRunning) return
            // M12.1 P1③：执行前二次校验——弹窗打开期间可能有后台安装/导入启动。
            if (maintenanceBusy) return
            val categories = selectedCategories.toSet()
            if (categories.isEmpty()) return
            cleanupRunning = true
            scope.launch {
                try {
                    val freed = withContext(Dispatchers.IO) { SandboxCleanup.clean(context, tmpGuardActive, categories) }
                    cleanupFreed = freed
                    rescanStorage()
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } finally {
                    // M12.1 P1④：无论成败（含取消）都复位，杜绝弹窗 dismiss 死锁。
                    cleanupRunning = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!cleanupRunning) showCleanupDialog = false },
            title = { Text(stringResource(R.string.settings_cleanup_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.settings_cleanup_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 局部快照：delegated property 无法被 when 智能转换。
                    val freedNow = cleanupFreed
                    when {
                        maintenanceBusy && cleanupFreed == null -> {
                            // M12.1 P1③：后台安装/导入进行中——清理入口整体禁用。
                            Text(
                                text = stringResource(R.string.settings_cleanup_busy),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        cleanupRunning -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.settings_cleanup_running))
                            }
                        }
                        scan == null -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                                Text(stringResource(R.string.settings_cleanup_scanning))
                            }
                        }
                        freedNow != null -> {
                            Text(
                                text = stringResource(R.string.settings_cleanup_done, formatFileSize(freedNow)),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        else -> {
                            val reclaimable = scan.reclaimable
                            val visible = SandboxCleanup.Category.values()
                                .filter { (reclaimable[it] ?: 0L) > 0L }
                            if (visible.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.settings_cleanup_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            visible.forEach { category ->
                                val label = when (category) {
                                    SandboxCleanup.Category.CACHE -> R.string.settings_cleanup_item_cache
                                    SandboxCleanup.Category.GUEST_TMP -> R.string.settings_cleanup_item_tmp
                                    SandboxCleanup.Category.LOGS -> R.string.settings_cleanup_item_logs
                                    SandboxCleanup.Category.APT -> R.string.settings_cleanup_item_apt
                                    SandboxCleanup.Category.ROLLBACK -> R.string.settings_cleanup_item_rollback
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = cleanupSelected[category] == true,
                                        onCheckedChange = { cleanupSelected[category] = it },
                                    )
                                    Text(
                                        text = stringResource(label) +
                                            if (category == SandboxCleanup.Category.ROLLBACK) {
                                                "（" + stringResource(
                                                    R.string.settings_cleanup_rollback_hint,
                                                    formatFileSize(reclaimable[category] ?: 0L),
                                                ) + "）"
                                            } else "",
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = formatFileSize(reclaimable[category] ?: 0L),
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                            if (tmpGuardActive) {
                                Text(
                                    text = stringResource(R.string.settings_cleanup_guard_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                when {
                    cleanupRunning -> { }
                    maintenanceBusy && cleanupFreed == null -> { }
                    cleanupFreed != null -> TextButton(onClick = { showCleanupDialog = false }) {
                        Text(stringResource(R.string.settings_cleanup_done_action))
                    }
                    else -> TextButton(enabled = anythingToClean, onClick = { runCleanup() }) {
                        Text(stringResource(R.string.settings_cleanup_action))
                    }
                }
            },
            dismissButton = {
                if (!cleanupRunning && cleanupFreed == null) {
                    TextButton(onClick = { showCleanupDialog = false }) {
                        Text(stringResource(R.string.settings_cancel_action))
                    }
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
    onClick: (() -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
        if (trailingIcon != null) trailingIcon()
    }
}

/** 存储行尾部的「点击刷新」小图标（M12.3：与可点击行为配对，避免视觉不一致）。 */
@Composable
private fun RefreshHintIcon() {
    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = stringResource(R.string.settings_storage_refresh),
        modifier = Modifier
            .padding(start = 6.dp)
            .size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    /** 1.1.1 (T2)：行右侧状态文本（装配行用），显示在箭头前。 */
    value: String? = null,
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
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
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
    //
    // 1.1.0 (M11): the SAF->cache copy can itself throw IOException (cloud provider
    // interrupted, cache disk full — the official bundle is ~200MB). Convert it to an
    // AppResult; an exception escaping this function would crash the app because the
    // caller's rememberCoroutineScope has no exception handler.
    val target = File(context.cacheDir, "runtime-bundle-${System.currentTimeMillis()}.zip")
    try {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext AppResult.Failure(AppError("UPDATE_READ_FAILED", "无法读取所选文件"))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            return@withContext AppResult.Failure(
                AppError("UPDATE_COPY_FAILED", "读取所选文件失败：${ArchiveErrors.describe(e)}"),
            )
        }
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
    // 接受：单个 .tar.gz / .tar.zst / .tar（1.1.0 M5 起支持裸 tar）DSH 层包，
    // 或 .zip（内含 DSH 层包，zip 内允许一层目录前缀，1.1.0 起递归查找）。
    // 不依赖文件名——按内容魔数识别类型。
    //
    // 1.1.0 (M11)：ArchiveExtractor.extract 的契约是「清理后重抛」，损坏 zip（截断 /
    // CRC 失败 / 加密）与 SAF 复制中断都会抛异常；rememberCoroutineScope 无异常处理器，
    // 异常直通协程即 crash。这里把复制与解压都转成 AppResult，只有 CancellationException
    // 保持重抛（结构化取消语义）。
    val probe = File(context.cacheDir, "dsh-import-${System.currentTimeMillis()}")
    val staging = File(context.cacheDir, "dsh-import-staging-${System.currentTimeMillis()}")
    try {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                probe.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext AppResult.Failure(AppError("DSH_READ_FAILED", "无法读取所选文件"))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: Exception) {
            return@withContext AppResult.Failure(
                AppError("DSH_COPY_FAILED", "读取所选文件失败：${ArchiveErrors.describe(e)}"),
            )
        }
        when (ArchiveExtractor.detectFormat(probe)) {
            ArchiveExtractor.Format.ZIP -> {
                try {
                    ArchiveExtractor.extract(probe, staging, null)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    return@withContext AppResult.Failure(
                        AppError("DSH_EXTRACT_FAILED", "解压失败：${ArchiveErrors.describe(e)}"),
                    )
                }
                // 1.1.0 (M4): 递归查找层包（右键压缩文件夹的 zip 有一层目录前缀）；
                // 即使误选了其它 tar 包（如运行环境 zip 里的 base.tar.zst）也不会再
                // 损坏现有层——DshLayer.installFromBundle 会先解到暂存区做形态校验
                // （bin.js 缺失即拒绝并保留原层）。
                // 1.1.0 (M12.4): 查找放宽到 .tar（裸 tar）与 .tgz——压缩格式本就按
                // 内容魔数识别，zip 内层包不再要求特定扩展名。
                val layer = staging.walkTopDown().firstOrNull {
                    it.isFile && (it.name.endsWith(".tar.gz") || it.name.endsWith(".tar.zst") ||
                        it.name.endsWith(".tar") || it.name.endsWith(".tgz"))
                } ?: return@withContext AppResult.Failure(
                    AppError("DSH_BUNDLE_NOT_FOUND", "zip 内未找到 DSH 层包（*.tar.gz / *.tar.zst / *.tar / *.tgz）"),
                )
                val sha = File(layer.path + ".sha256")
                    .takeIf { it.isFile }
                    ?.readText()?.trim()?.split(Regex("\\s+"))?.firstOrNull()
                sandboxManager.updateDsh(layer, sha, null)
            }
            else -> sandboxManager.updateDsh(probe, null, null)
        }
    } finally {
        probe.delete()
        staging.deleteRecursively()
    }
}

/** 装配移动端适配包状态标记（1.1.1 T2，持久化于 user-data 之外的应用偏好）。 */
private const val PREF_MOBILE_ADAPT_INSTALLED = "mobile_adapt_installed"
