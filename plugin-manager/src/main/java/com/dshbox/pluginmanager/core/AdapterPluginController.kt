package com.dshbox.pluginmanager.core

import android.content.Context
import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.SandboxManager
import com.dshbox.pluginmanager.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.channels.Channels
import java.io.File
import java.util.UUID

/**
 * 「DSHBox 专属适配插件」开关的状态与动作。
 *
 * 与设置页里那套装配逻辑同源（同样的 guest 命令、同样的偏好键），只是入口搬到了
 * 插件面板：开关状态用本地偏好做瞬时响应；与 guest 实际状态的对齐由 `SandboxService`
 * 在启动期与 DSH 就绪时完成（探测到缺失就按当前开关把包装配到位）。
 *
 * 既有适配开关**默认开启**（键**从未写过**时才置 true；写过之后一律不再改动，
 * 因此升级 / 重装 APK 不会覆盖用户的选择）：
 * - 移动端适配包（mobile-adapt）：装配/移除都执行随包资产里的安装脚本，重启 DSH 后生效。
 * - DSH连接手机（mobile-pilot）：与上一条**同形** —— 同一套 guest 命令、同一个脚本目录结构、
 *   同样的失败回滚，只是包名与 staging 目录不同。两条链路都只动 profile，不涉及 `--patch` 覆盖层。
 * - Browser：新用户默认装配，保留用户明确关闭状态；其适配开关不授予自动化控制权。
 *
 * @param scope 界面侧生命周期；动作命令本身在 IO 上跑。
 */
class AdapterPluginController(
    private val context: Context,
    private val sandbox: SandboxManager,
    private val profile: String,
    private val scope: CoroutineScope,
) {

    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    // 必须排在下面两个 StateFlow 之前：预置的默认值要能被它们的初值读到
    // （类体内 init 块与属性初始化按声明顺序执行）。
    init {
        seedDefaultsOnce()
    }

    /**
     * 预置「默认开启」——只在**键从未写过**时写 true，写过就再也不碰。
     *
     * 为什么需要一个独立的标记键：只靠取值键的缺失无法区分"用户从没动过"与
     * "用户动过（关掉了）"，那样每次升级都会把用户的关闭动作翻回开启。
     * 标记键记下"默认值已经预置过"，此后两个开关的状态完全由用户与运行期状态决定。
     */
    private fun seedDefaultsOnce() {
        if (!prefs.contains(PREF_BROWSER_PLUGIN_ENABLED)) {
            prefs.edit().putBoolean(PREF_BROWSER_PLUGIN_ENABLED, true).apply()
        }
        if (prefs.contains(PREF_DEFAULTS_SEEDED)) return
        val editor = prefs.edit().putBoolean(PREF_DEFAULTS_SEEDED, true)
        if (!prefs.contains(PREF_MOBILE_ADAPT_INSTALLED)) {
            editor.putBoolean(PREF_MOBILE_ADAPT_INSTALLED, true)
        }
        if (!prefs.contains(PREF_MOBILE_PILOT_ENABLED)) {
            // 这个开关改名过（旧键名字里有 pilot）。拨过旧开关的老用户，其选择要跟着搬过来，
            // 不能被"默认开启"顶掉。
            val carried = prefs.getBoolean(PREF_LEGACY_PILOT_ENABLED, true)
            editor.putBoolean(PREF_MOBILE_PILOT_ENABLED, carried)
        }
        editor.apply()
    }

    private val _mobileInstalled = MutableStateFlow(prefs.getBoolean(PREF_MOBILE_ADAPT_INSTALLED, true))

    /** 移动端适配包是否已装配（本地标记，装配/移除成功时翻转）。 */
    val mobileInstalled: StateFlow<Boolean> = _mobileInstalled.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** 命令执行中：开关禁用，防止连点。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _mobilePilotEnabled = MutableStateFlow(prefs.getBoolean(PREF_MOBILE_PILOT_ENABLED, true))

    /** 「DSH连接手机」是否已装配（本地标记，装配/卸载成功时翻转）。 */
    val mobilePilotEnabled: StateFlow<Boolean> = _mobilePilotEnabled.asStateFlow()

    private val _mobilePilotBusy = MutableStateFlow(false)

    /** mobile-pilot 命令执行中：开关禁用，防止连点（与 [busy] 同义，两条链路各自独立）。 */
    val mobilePilotBusy: StateFlow<Boolean> = _mobilePilotBusy.asStateFlow()

    private val _browserPluginEnabled = MutableStateFlow(
        browserPluginEnabledFromPreference(
            prefs.contains(PREF_BROWSER_PLUGIN_ENABLED),
            prefs.getBoolean(PREF_BROWSER_PLUGIN_ENABLED, false),
        ),
    )

    /** Browser Cordis adapter package is assembled in the DSH profile. */
    val browserPluginEnabled: StateFlow<Boolean> = _browserPluginEnabled.asStateFlow()

    private val _browserPluginBusy = MutableStateFlow(false)

    /** Browser package assembly or refresh is running. */
    val browserPluginBusy: StateFlow<Boolean> = _browserPluginBusy.asStateFlow()

    private val browserStageDir: File by lazy {
        File(PluginPaths(context, profile).assetsDir, "${Constants.DSHBOX_PLUGINS_LEAF}/browser-use")
    }

    /** 最近一次自动刷新失败的原因（由 bootstrap 写入偏好，可为空）。 */
    fun lastError(): String? = prefs.getString(Constants.PREF_MOBILE_ADAPT_LAST_ERROR, null)

    fun browserLastError(): String? = prefs.getString(Constants.PREF_BROWSER_PLUGIN_LAST_ERROR, null)

    /** 装配与移除共用一个入口：按当前状态决定执行哪个脚本。 */
    fun toggleMobileAdapt() {
        if (_busy.value) return
        val target = !_mobileInstalled.value
        _busy.value = true
        scope.launch {
            val script = if (target) "install.sh" else "uninstall.sh"
            val command = "chmod -R u+w $PROFILE_DIR/node_modules/@local/dsh-mobile-adapt 2>/dev/null || true; " +
                "bash $STAGE_DIR/$script $PROFILE_DIR"
            val result = withContext(Dispatchers.IO) { sandbox.runGuestCommand(command, onLine = {}) }
            if (result is AppResult.Success) {
                _mobileInstalled.value = target
                prefs.edit().putBoolean(PREF_MOBILE_ADAPT_INSTALLED, target).apply()
                toast(
                    context.getString(R.string.pm_adapt_ok) +
                        context.getString(R.string.pm_adapt_restart_hint),
                    android.widget.Toast.LENGTH_SHORT,
                )
            } else if (!target) {
                // 装配失败时把 guest 侧回滚一遍，避免留下半装配状态。
                withContext(Dispatchers.IO) {
                    sandbox.runGuestCommand(
                        "chmod -R u+w $PROFILE_DIR/node_modules/@local/dsh-mobile-adapt 2>/dev/null || true; " +
                            "bash $STAGE_DIR/uninstall.sh $PROFILE_DIR",
                        onLine = {},
                    )
                }
            }
            if (result is AppResult.Failure) {
                toast(
                    context.getString(R.string.pm_adapt_fail) + " " + result.error.message,
                    android.widget.Toast.LENGTH_LONG,
                )
            }
            _busy.value = false
        }
    }

    /**
     * mobile-pilot（DSH连接手机）的装配与卸载共用一个入口 —— 与 [toggleMobileAdapt] **同形**：
     * 同一套 guest 命令、同一个脚本目录结构、同样的失败回滚，只是包名与 staging 目录不同。
     */
    fun toggleMobilePilot() {
        if (_mobilePilotBusy.value) return
        val target = !_mobilePilotEnabled.value
        _mobilePilotBusy.value = true
        scope.launch {
            val script = if (target) "install.sh" else "uninstall.sh"
            val command = "chmod -R u+w $MOBILE_PILOT_PROFILE_PKG 2>/dev/null || true; " +
                "bash $MOBILE_PILOT_STAGE_DIR/$script $PROFILE_DIR"
            val result = withContext(Dispatchers.IO) { sandbox.runGuestCommand(command, onLine = {}) }
            if (result is AppResult.Success) {
                _mobilePilotEnabled.value = target
                prefs.edit().putBoolean(PREF_MOBILE_PILOT_ENABLED, target).apply()
                toast(
                    context.getString(R.string.pm_adapt_ok) +
                        context.getString(R.string.pm_adapt_restart_hint),
                    android.widget.Toast.LENGTH_SHORT,
                )
            } else if (!target) {
                // 装配失败时把 guest 侧回滚一遍，避免留下半装配状态。
                withContext(Dispatchers.IO) {
                    sandbox.runGuestCommand(
                        "chmod -R u+w $MOBILE_PILOT_PROFILE_PKG 2>/dev/null || true; " +
                            "bash $MOBILE_PILOT_STAGE_DIR/uninstall.sh $PROFILE_DIR",
                        onLine = {},
                    )
                }
            }
            if (result is AppResult.Failure) {
                toast(
                    context.getString(R.string.pm_adapt_fail) + " " + result.error.message,
                    android.widget.Toast.LENGTH_LONG,
                )
            }
            _mobilePilotBusy.value = false
        }
    }

    /** Toggle only the Browser Cordis adapter assembly; it never grants Browser control. */
    fun toggleBrowserPlugin() {
        if (_browserPluginBusy.value) return
        _browserPluginBusy.value = true
        scope.launch {
            try {
                val result = BrowserAdapterAssemblyGate.withLock {
                    withContext(Dispatchers.IO) {
                        // Re-read under the shared gate: startup may have changed the guest state
                        // while this UI action was waiting for the gate.
                        val target = !prefs.getBoolean(PREF_BROWSER_PLUGIN_ENABLED, true)
                        try {
                            stageBrowserAssets().getOrThrow()
                            val script = if (target) "install.sh" else "uninstall.sh"
                            val commandResult = sandbox.runGuestCommand("bash $BROWSER_STAGE_DIR/$script $PROFILE_DIR", onLine = {})
                            if (commandResult is AppResult.Success) {
                                _browserPluginEnabled.value = target
                                prefs.edit()
                                    .putBoolean(PREF_BROWSER_PLUGIN_ENABLED, target)
                                    .remove(Constants.PREF_BROWSER_PLUGIN_LAST_ERROR)
                                    .apply()
                            } else if (commandResult is AppResult.Failure) {
                                prefs.edit().putString(
                                    Constants.PREF_BROWSER_PLUGIN_LAST_ERROR,
                                    commandResult.error.message.ifBlank { commandResult.error.code },
                                ).apply()
                            }
                            commandResult
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Throwable) {
                            prefs.edit().putString(
                                Constants.PREF_BROWSER_PLUGIN_LAST_ERROR,
                                failure.message ?: "Browser assets could not be staged",
                            ).apply()
                            AppResult.Failure(AppError("BROWSER_STAGE_FAILED", failure.message ?: "Browser assets could not be staged", failure))
                        }
                    }
                }
                if (result is AppResult.Success) {
                    val target = prefs.getBoolean(PREF_BROWSER_PLUGIN_ENABLED, true)
                    toast(
                        context.getString(if (target) R.string.pm_browser_assembled else R.string.pm_browser_removed) +
                            context.getString(R.string.pm_adapt_restart_hint),
                        android.widget.Toast.LENGTH_SHORT,
                    )
                } else if (result is AppResult.Failure) {
                    toast(context.getString(R.string.pm_browser_assembly_failed), android.widget.Toast.LENGTH_LONG)
                }
            } finally {
                _browserPluginBusy.value = false
            }
        }
    }

    /** Copy only the Browser-owned APK asset tree, using a sibling stage and rollback directory. */
    private fun stageBrowserAssets(): Result<Unit> = runCatching {
        val parent = browserStageDir.parentFile ?: error("Browser stage parent is unavailable")
        check(parent.mkdirs() || parent.isDirectory) { "Browser stage directory is unavailable" }
        val suffix = UUID.randomUUID().toString()
        val staged = File(parent, ".browser-use-stage-$suffix")
        val backup = File(parent, ".browser-use-backup-$suffix")
        check(!staged.exists() && !backup.exists()) { "Browser stage transaction collision" }
        try {
            copyAssetTree("plugins/browser-use", staged)
            check(File(staged, "install.sh").isFile) { "Browser installer asset is missing" }
            check(File(staged, "uninstall.sh").isFile) { "Browser uninstaller asset is missing" }
            check(readBrowserOwner(File(staged, "plugin/browser-use-install.json"))) { "Browser ownership marker is invalid" }
            if (browserStageDir.exists()) {
                check(browserStageDir.isDirectory && !java.nio.file.Files.isSymbolicLink(browserStageDir.toPath()) &&
                    (readBrowserOwner(File(browserStageDir, "plugin/browser-use-install.json")) ||
                        readBrowserOwner(File(browserStageDir, "plugin/.browser-use-install.json")))) {
                    "Browser stage directory is not owned by this adapter"
                }
                check(browserStageDir.renameTo(backup)) { "Could not preserve the previous Browser stage" }
            }
            if (!staged.renameTo(browserStageDir)) {
                if (backup.exists()) backup.renameTo(browserStageDir)
                error("Could not publish Browser stage assets")
            }
            backup.deleteRecursively()
        } catch (failure: Throwable) {
            staged.deleteRecursively()
            if (backup.exists() && !browserStageDir.exists()) backup.renameTo(browserStageDir)
            throw failure
        }
    }

    private fun readBrowserOwner(file: File): Boolean = runCatching {
        val path = file.toPath()
        val parent = Files.readAttributes(path.parent, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(parent.isDirectory && !parent.isSymbolicLink) { "Browser ownership marker parent must be a directory" }
        val before = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        check(before.isRegularFile && !before.isSymbolicLink) { "Browser ownership marker must be a regular file" }
        Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val opened = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            check(before.fileKey() != null && opened.isRegularFile && opened.fileKey() == before.fileKey()) {
                "Browser ownership marker changed during read"
            }
            Channels.newInputStream(channel).reader().buffered().use { markerReader ->
                android.util.JsonReader(markerReader).use { reader ->
                    var packageName: String? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (name == "package") packageName = reader.nextString() else reader.skipValue()
                    }
                    reader.endObject()
                    packageName == "@local/browser-use"
                }
            }
        }
    }.getOrDefault(false)

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        check(children.isNotEmpty()) { "Browser adapter assets are missing" }
        check(destination.mkdirs() || destination.isDirectory) { "Could not create Browser stage" }
        for (name in children) {
            val childPath = "$assetPath/$name"
            val child = File(destination, name)
            if (context.assets.list(childPath).orEmpty().isNotEmpty()) {
                copyAssetTree(childPath, child)
            } else {
                context.assets.open(childPath).use { input -> child.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }


    /** 主线程显示；失败留痕（Toast 必须在带 Looper 的线程上创建）。 */
    private fun toast(text: String, duration: Int) {
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(context.applicationContext, text, duration).show()
            }.onFailure { android.util.Log.w(TAG, "toast failed: ${it.message}") }
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private companion object {
        const val TAG = "PluginAdapt"

        /** 与设置页同源的偏好键（bootstrap 也读它）。 */
        const val PREF_MOBILE_ADAPT_INSTALLED = Constants.PREF_MOBILE_ADAPT_INSTALLED

        /** 「DSH连接手机」开关的键（bootstrap 也读它，决定启动期要不要把包装配到位）。 */
        const val PREF_MOBILE_PILOT_ENABLED = Constants.PREF_MOBILE_PILOT_ENABLED

        /** 该开关改名前的旧键，只用于一次性把老用户的选择搬过来。 */
        const val PREF_LEGACY_PILOT_ENABLED = Constants.PREF_LEGACY_PILOT_ENABLED

        /**
         * 「默认开启」是否已预置过（一次性标记）。
         *
         * 与取值键分开是必须的：只看取值键的缺失无法区分"用户从没动过"与"用户关掉了"，
         * 那样每次升级都会把用户的关闭动作翻回开启。
         */
        const val PREF_DEFAULTS_SEEDED = "adapter_defaults_seeded"

        const val STAGE_DIR = "${Constants.DSHBOX_PLUGINS_GUEST_DIR}/mobile-adapt"

        const val PROFILE_DIR = Constants.DSH_WEB_PROFILE_GUEST_DIR

        /** mobile-pilot 包的 staging 目录（随包资产复制进 guest 的落点）。 */
        const val MOBILE_PILOT_STAGE_DIR = "${Constants.DSHBOX_PLUGINS_GUEST_DIR}/mobile-pilot"

        /** mobile-pilot 包装配后在 profile 内的落点（由 install.sh 建立）。 */
        const val MOBILE_PILOT_PROFILE_PKG = "$PROFILE_DIR/node_modules/@local/mobile-pilot"

        const val PREF_BROWSER_PLUGIN_ENABLED = Constants.PREF_BROWSER_PLUGIN_ENABLED

        const val BROWSER_STAGE_DIR = "${Constants.DSHBOX_PLUGINS_GUEST_DIR}/browser-use"
    }
}

internal fun browserPluginEnabledFromPreference(hasStoredValue: Boolean, storedValue: Boolean): Boolean =
    if (hasStoredValue) storedValue else true
