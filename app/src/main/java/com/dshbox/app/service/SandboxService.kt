package com.dshbox.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dshbox.app.DshApp
import com.dshbox.app.util.BackgroundOps
import com.dshbox.app.R
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.ui.theme.AppLocaleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the sandbox and DSH lifecycles.
 *
 * The sandbox and DSH are now decoupled:
 * - Sandbox (Debian/PRoot) can run independently.
 * - DSH requires the sandbox to be running; if not, the user is told to wake it.
 * - First launch auto-starts both; afterwards the user controls each separately.
 */
class SandboxService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var restartInProgress = false

    private val sandboxManager
        get() = (application as DshApp).container.sandboxManager

    private val terminalManager
        get() = (application as DshApp).container.dshTerminalManager

    private val prefs: SharedPreferences
        get() = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startAsForeground()
        serviceScope.launch {
            sandboxManager.sandboxState.collectLatest { state ->
                updateNotification()
            }
        }
        serviceScope.launch {
            sandboxManager.dshState.collectLatest { state ->
                updateNotification()
            }
        }
        serviceScope.launch { bootstrap() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SANDBOX -> serviceScope.launch { startSandbox() }
            ACTION_STOP_SANDBOX -> serviceScope.launch { stopSandbox() }
            ACTION_RESTART_SANDBOX -> serviceScope.launch { restartSandbox() }
            ACTION_START_DSH -> serviceScope.launch { startDsh() }
            ACTION_RESTART_DSH -> serviceScope.launch { restartDsh() }
            ACTION_STOP_DSH -> serviceScope.launch { stopDsh() }
            ACTION_STOP_ALL -> {
                serviceScope.launch {
                    terminalManager.stopAll()
                    sandboxManager.forceStop()
                    stopSelf()
                }
            }
            // 语言切换后由 MainActivity.onResume 触发，通知按新语言重建。
            ACTION_REFRESH_NOTIFICATION -> updateNotification()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        terminalManager.stopAll()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * One-time bootstrap: initialize directories, install bundled runtime if needed,
     * then on first launch start both sandbox and DSH. Subsequent launches leave
     * control to the user. The first-run marker is persisted only when BOTH
     * started successfully, so a failed first boot is retried on the next launch.
     *
     * P1③): the whole bootstrap runs inside [BackgroundOps.runTracked] —
     * it writes cleanup targets (bundled-runtime-staging, cacheDir/dsh-bundled-*.tar,
     * dsh-staging via updateDsh), so the settings cleanup entry must stay disabled
     * until it finishes.
     */
    private suspend fun bootstrap() = BackgroundOps.runTracked {
        sandboxManager.initialize()
        provisionMobileAdaptPlugin()
        provisionDshLinkShim()
        val container = (application as DshApp).container
        when (val bundled = BundledRuntimeInstaller(applicationContext, container.sandboxConfig).installIfAbsent()) {
            is AppResult.Success -> if (bundled.value) Log.i(TAG, "bootstrap: bundled runtime installed")
            is AppResult.Failure -> Log.w(TAG, "bootstrap: bundled runtime install failed: ${bundled.error.message}")
        }
        if (!sandboxManager.isRuntimeInstalled()) {
            val installed = sandboxManager.installFirstAvailableBundle()
            if (installed is AppResult.Success) {
                sandboxManager.promoteRuntimeBundle()
            }
        }
        if (sandboxManager.isRuntimeInstalled()) {
            provisionBundledDsh()
            // 已装配过移动端适配插件的话，把 profile 里的副本刷新为本次 APK 内的版本。
            // 必须在 startDsh 之前：刷新后当次启动即加载新插件，用户无需手动移除再装配。
            refreshAssembledMobileAdaptPlugin()
            val firstRun = !prefs.getBoolean(Constants.PREF_FIRST_RUN_COMPLETED, false)
            if (firstRun) {
                Log.i(TAG, "bootstrap: first run, starting sandbox and dsh")
                sandboxManager.startSandbox()
                if (sandboxManager.sandboxState.value == SandboxState.RUNNING) {
                    val dshResult = startDsh()
                    if (dshResult is AppResult.Success) {
                        prefs.edit().putBoolean(Constants.PREF_FIRST_RUN_COMPLETED, true).apply()
                        Log.i(TAG, "bootstrap: first run complete")
                    } else {
                        Log.w(TAG, "bootstrap: first run dsh not ready, will retry next launch")
                    }
                } else {
                    Log.w(TAG, "bootstrap: first run sandbox failed, will retry next launch")
                }
            }
        }
    }

    /**
     * Copy the APK-bundled mobile-adapt cordis plugin into the DSH-HOME staging
     * area (user-data/.dsh/mobile-adapt), fully overwritten each install, so the
     * user-triggered 装配 step can assemble it into the DSH profile via guest
     * command injection (method B).
     */
    private suspend fun provisionMobileAdaptPlugin() = withContext(Dispatchers.IO) {
        val assetDir = "plugins/dsh-mobile-adapt"
        val stageDir = File(File(File(applicationContext.filesDir, "user-data"), ".dsh"), "mobile-adapt")
        try {
            if (stageDir.exists()) stageDir.deleteRecursively()
            stageDir.mkdirs()
            copyAssetTree(assetDir, stageDir)
            Log.i(TAG, "bootstrap: mobile-adapt plugin staged to ${stageDir.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap: provision mobile-adapt plugin failed: ${t.message}")
        }
    }

    /**
     * 若用户**已经装配过**移动端适配插件，则把 profile 里的副本刷新为 APK 内的当前版本。
     *
     * ## 为什么需要它
     *
     * `install.sh` 把插件复制进 `profiles/web/node_modules/@local/dsh-mobile-adapt` 后
     * **不会自动跟随 APK 升级**：用户在 v1.3.0 装配一次，之后装上 v1.3.1 的 APK，
     * profile 里仍是旧副本，页面上跑的就是旧逻辑——表现为「app 里改了插件行为，
     * 真机上毫无变化，除非手动移除再装配一次」。
     *
     * ## 设计：只刷新「已装配」的，不擅自装配
     *
     * - **未装配**（profile 里没有该插件）：什么都不做。装配与否是用户的显式选择
     *   （设置页开关），app 不替用户决定是否往他的 DSH profile 里塞插件。
     * - **已装配**：把最新副本覆盖进去（等价于用户手动点一次「装配」）。
     *
     * ## 两道前置判断（在一次 guest 命令内完成，绝不强行覆盖）
     *
     * 两条判断**合并为单次 `runGuestCommand`**：每次调用都要 spawn 一个 proot 进程，
     * 拆成两条会让"插件已是最新"这个最常见的路径也付两次 spawn。
     * 合并用输出标记区分三种状态（不能用 `&&` 串联——两条判断的失败去向相反，详见下方）。
     *
     * 1. `probeReady` —— profile 里**确实已有**该插件（`profile` 目录不存在 = DSH 从没跑过，
     *    此时不该凭空塞插件）**且** staged 的 `install.sh` 已就位（assets 复制成功）。
     * 2. `probeUpToDate` —— 关键文件**内容一致**且 bundle 已注册。
     *    一致则**跳过安装**：省掉每次启动的解包/写盘/改 JSON，也**避免覆盖用户
     *    对 profile 内插件的手动修改**（用户可能自己调过插件）。
     *
     * ## 失败必须让用户可感知（不能只写日志）
     *
     * `install.sh` 依赖 guest 内的 `python3`（用于改 `package.json`）；若 python3 缺失、
     * 或 profile 结构变化导致脚本失败，**仅记日志会让设置页仍显示绿色「已装配」**，
     * 用户以为装好了、实际跑的是旧插件——这正是旧的手动装配踩过的坑。
     * 因此失败时把 [Constants.PREF_MOBILE_ADAPT_INSTALLED] **回退为 false**：
     * 设置页据此显示为「未装配」，用户可手动重装并看到失败原因。
     *
     * 用 guest 命令而非宿主文件直写：profile 目录的属主/权限在 guest 视角下才正确，
     * 且与用户手动装配（同一个 install.sh）走完全相同的代码路径，行为一致。
     *
     * 刷新后需要重启 DSH 才生效——本方法由 bootstrap 在 DSH 启动**之前**调用，
     * 因此当次启动即加载新插件，无需用户再手动重启。
     */
    private suspend fun refreshAssembledMobileAdaptPlugin() {
        val prefs = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        // 仅当用户装配过（本地标记为 true）才尝试。标记由设置页在装配/移除成功时翻转；
        // 首次进入设置页会用 guest 查询校准一次（见 SettingsScreen）。
        if (!prefs.getBoolean(Constants.PREF_MOBILE_ADAPT_INSTALLED, false)) {
            Log.i(TAG, "bootstrap: mobile-adapt not assembled by user; skipping refresh")
            return
        }
        val profile = "/root/projects/.dsh/profiles/web"
        val pluginDir = "$profile/node_modules/@local/dsh-mobile-adapt"
        val stage = "/root/projects/.dsh/mobile-adapt"
        val stagePlugin = "$stage/plugin"

        // ── 探测：判断 1 + 判断 2 合并为**一次** guest 命令 ────────────────────
        // 每次 runGuestCommand 都会 spawn 一个 proot 进程，启动期成本可观；
        // 原先拆成两条命令 → 插件已是最新时也要付两次 spawn。合并后只剩一次。
        //
        // ⚠️ 不能简单用 `A && B` 串联：两条判断的**失败去向相反** ——
        //   判断 1 不成立（未装配）→ 必须**跳过且不安装**（不擅自往用户 profile 塞插件）；
        //   判断 2 不成立（非最新）→ 必须**继续安装**。
        // 由于 runGuestCommand 只把退出码折叠成 Success/Failure（拿不到具体码值），
        // 这里靠脚本**输出标记**区分三种状态，由 Kotlin 侧分派。
        //
        // 判断 1：**确实已装配**才刷新 —— 两个条件都必须是"文件系统事实"，
        // 不能只信本地偏好标记（标记可能与实际脱节）。
        //   • `test -d $pluginDir`  —— profile 里有该插件目录。
        //     **这一条同时覆盖三种"不该刷新"的情形**：
        //       ① profile 目录不存在 = DSH 从没跑过 → 跳过（不凭空塞插件）；
        //       ② profile 存在但插件不在 = 用户没装配过 或 手动卸过 → 跳过；
        //       ③ 用户手动删过插件目录 → 跳过。
        //   • `test -f $stage/install.sh` —— staged 插件已就位（provision 阶段成功）。
        //     缺了说明 APK 资产复制失败，装也装不成，直接跳过。

        // 判断 2：内容一致 + bundle 已注册 → 已是最新，直接跳过（省 I/O、不覆盖用户手改）。
        // 指纹 = 关键文件内容串联后的 sha256（只用 coreutils：test/cat/sha256sum/grep，
        // 不依赖 diff/awk —— guest 里一定可用）。详见 [MobileAdaptProbe]。
        //
        // 脚本拼接与标记解析收敛在 [MobileAdaptProbe]（纯函数、有单测）：
        // 手工拼接的字符串少一个 `&&` 就会静默改变判定语义，编译器看不出来，
        // 故把这段提出来由单测锁住。
        val probeScript = MobileAdaptProbe.buildScript(
            pluginDir = pluginDir,
            stageInstall = "$stage/install.sh",
            stagePlugin = stagePlugin,
            profilePackageJson = "$profile/package.json",
        )

        // 输出行由后台读流线程回调（见 SandboxProcessRunner），用 AtomicReference 承接，
        // 避免跨线程可见性问题。
        val probeOutcome = java.util.concurrent.atomic.AtomicReference<String?>(null)
        sandboxManager.runGuestCommand(probeScript, onLine = { line ->
            MobileAdaptProbe.markerFrom(line)?.let { probeOutcome.set(it) }
        })
        when (probeOutcome.get()) {
            MobileAdaptProbe.MARKER_UP_TO_DATE -> {
                Log.i(TAG, "bootstrap: mobile-adapt already up to date; skipping refresh")
                return
            }
            MobileAdaptProbe.MARKER_NEEDS_INSTALL -> {
                // 落到下方安装流程。
            }
            else -> {
                // NOT_READY（未装配 / staged 缺失），或**根本没拿到标记**
                // （proot spawn 失败、guest 异常、脚本被中断）。信息不足时一律不动
                // 用户的 profile —— 宁可下次启动再试，也不擅自装配。
                Log.i(TAG, "bootstrap: mobile-adapt not assembled in profile (or stage missing); skipping refresh")
                return
            }
        }

        val res = sandboxManager.runGuestCommand("bash $stage/install.sh $profile", onLine = {})
        when (res) {
            is AppResult.Success -> {
                Log.i(TAG, "bootstrap: mobile-adapt plugin refreshed to bundled version")
                prefs.edit().remove(Constants.PREF_MOBILE_ADAPT_LAST_ERROR).apply()
            }
            is AppResult.Failure -> {
                Log.w(TAG, "bootstrap: mobile-adapt refresh failed: ${res.error.message}")
                // 回退「已装配」标记：避免设置页继续显示绿色已装配而实际跑的是旧插件。
                // 用户下次进入设置页会看到「未装配」，可手动重装并看到失败提示。
                prefs.edit()
                    .putBoolean(Constants.PREF_MOBILE_ADAPT_INSTALLED, false)
                    .putString(
                        Constants.PREF_MOBILE_ADAPT_LAST_ERROR,
                        // 此处拿不到前台 UI 上下文，无法弹 Toast；
                        // 把原因写进偏好，由设置页读取展示（见 SettingsScreen）。
                        res.error.message.ifBlank { res.error.code },
                    )
                    .apply()
                Log.w(TAG, "bootstrap: cleared assembled flag so the UI reflects the failed refresh")
            }
        }
    }

    /**
     * Stage the APK-bundled **Android hard-link compatibility shim** into the
     * host directory that is bound to the guest's `/opt/dshbox` and preloaded
     * via `node --import` when DSH starts (see SandboxProcessRunner).
     *
     * This replaces the old approach of REWRITING DSH's JS files at install time:
     * the shim only swaps `node:fs/promises`'s `link` at RUNTIME, so no DSH source
     * byte is ever modified and there is nothing to re-anchor when upstream
     * refactors its internals.
     *
     * Overwritten on every boot so an updated APK always ships the current shim.
     */
    private suspend fun provisionDshLinkShim() = withContext(Dispatchers.IO) {
        val config = (application as DshApp).container.sandboxConfig
        try {
            config.dshShimDir.mkdirs()
            applicationContext.assets.open("dshbox/link-shim.mjs").use { input ->
                config.dshShimFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "bootstrap: dsh link shim staged to ${config.dshShimFile.absolutePath}")
        } catch (t: Throwable) {
            // 垫片缺失只会让硬链接兼容失效（DSH 仍可启动，见 linkShimHostDir 的说明），
            // 不该阻断 bootstrap，因此这里只记录。
            Log.w(TAG, "bootstrap: provision dsh link shim failed: ${t.message}")
        }
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val assets = applicationContext.assets
        val children = assets.list(assetPath) ?: return
        for (name in children) {
            val full = "$assetPath/$name"
            val out = File(dest, name)
            // A directory is a path whose AssetManager::list returns a NON-EMPTY array;
            // a FILE returns null or an empty array — treat only non-empty as dir, else
            // copy as a regular file (nested dirs like plugin/ have many entries).
            if (assets.list(full)?.isNotEmpty() == true) {
                out.mkdirs()
                copyAssetTree(full, out)
            } else {
                assets.open(full).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private suspend fun provisionBundledDsh() {
        // DshBundler: provision the APK-bundled DSH baseline (assets/dsh/
        // <version>.tar.gz [+ .sha256]) into runtime-current/dsh with version
        // arbitration (installed-newer wins). No-op when the APK carries no DSH
        // baseline (e.g. a dev build that ships DSH separately).
        val assetManager = applicationContext.assets
        val entries = runCatching { assetManager.list("dsh") }.getOrNull() ?: return
        // zstd baseline (preferred) or a gzip fallback; DshLayer/extractTarGz sniffs
        // the actual compression from the stream magic, not the extension.
        val dshTarball = entries.firstOrNull { it.endsWith(".tar.zst") }
            ?: entries.firstOrNull { it.endsWith(".tar.gz") } ?: return
        // strip the build-side "-patched" marker from the derived version.
        // The asset is named e.g. "0.1.1-rc.2-patched.tar.zst" but the RELEASE it packs is
        // With the suffix left in, the arbitration below treats a legitimately
        // offline-imported "0.1.1-rc.2" (or a version discovered as "unknown") as OLDER and
        // silently re-provisioned the bundled layer over it on every boot — offline imports
        // appeared to "not stick". Removing the marker makes the comparison exact; existing
        // installs that already recorded "...-patched" still compare as newer (kept, no churn).
        val version = dshTarball.removeSuffix(".tar.zst").removeSuffix(".tar.gz").removeSuffix("-patched")
        val out = File(cacheDir, "dsh-bundled-$version.tar")
        try {
            assetManager.open("dsh/$dshTarball").use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            val sha = if (entries.contains("$dshTarball.sha256")) {
                // Sidecars are "<sha256>  <file>"; take the hash token only, matching
                // the layer verification (BundledRuntimeInstaller / DefaultSandboxManager).
                assetManager.open("dsh/$dshTarball.sha256").use { it.readBytes().decodeToString().trim().split(Regex("\\s+")).firstOrNull() }
            } else {
                null
            }
            when (val r = sandboxManager.updateDsh(out, sha, version)) {
                is AppResult.Success ->
                    if (r.value.changed) Log.i(TAG, "bootstrap: bundled DSH ${r.value.version} installed")
                    else Log.i(TAG, "bootstrap: bundled DSH $version kept (already at ${r.value.version})")
                is AppResult.Failure ->
                    Log.w(TAG, "bootstrap: bundled DSH provision failed: ${r.error.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap: bundled DSH read failed: ${t.message}")
        } finally {
            out.delete()
        }
    }

    private suspend fun startSandbox() {
        sandboxManager.startSandbox()
    }

    private suspend fun stopSandbox() {
        // The terminal login shell lives inside the sandbox rootfs; kill it
        // first so it never outlives the PRoot tree it depends on.
        terminalManager.stopSandboxSession()
        sandboxManager.stopSandbox()
    }

    private suspend fun restartSandbox() {
        if (restartInProgress) return
        restartInProgress = true
        try {
            sandboxManager.restartSandbox()
        } finally {
            restartInProgress = false
        }
    }

    private suspend fun startDsh(): AppResult<com.dshbox.app.sandbox.DshRuntimeStatus> {
        val result = sandboxManager.startDsh()
        if (result is AppResult.Failure) {
            Log.w(TAG, "startDsh: ${result.error.message}")
            showToast(userMessageOf(result.error))
        }
        return result
    }

    private suspend fun restartDsh(): AppResult<com.dshbox.app.sandbox.DshRuntimeStatus> {
        val result = sandboxManager.restartDsh()
        if (result is AppResult.Failure) {
            Log.w(TAG, "restartDsh: ${result.error.message}")
            showToast(userMessageOf(result.error))
        }
        return result
    }

    /** Toast 优先展示可本地化 userMessage（按当前语言），回退 message。 */
    private fun userMessageOf(error: com.dshbox.app.common.AppError): String =
        error.userMessage?.asString(localizedContext()) ?: error.message

    private suspend fun stopDsh() {
        sandboxManager.stopDsh()
    }

    private fun showToast(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground failed due to missing notification permission", e)
        }
    }

    private fun updateNotification() {
        val notification = buildNotification()
        try {
            if (hasNotificationPermission()) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
            } else {
                Log.w(TAG, "updateNotification: POST_NOTIFICATIONS not granted - skipping notify")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "updateNotification: notify threw SecurityException", e)
        }
    }

    /** Android 13+ requires the POST_NOTIFICATIONS runtime permission before notify(). */
    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /**
     * 按应用内所选语言本地化的 Context。Service 不走 AppCompatActivity
     * 链路（API<33 不自动继承应用内语言覆盖），且 base context 在 attach 后无法更换，
     * 因此每次构建通知时用当前语言镜像现取现包。
     */
    private fun localizedContext(): Context {
        val tags = AppLocaleState.currentTag(applicationContext)
        if (tags.isEmpty()) return this
        val config = Configuration(resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tags))
        return createConfigurationContext(config)
    }

    private fun buildNotification(): Notification {
        val ctx = localizedContext()
        val sandboxState = sandboxManager.sandboxState.value
        val dshState = sandboxManager.dshState.value

        val sandboxText = when (sandboxState) {
            SandboxState.RUNNING -> ctx.getString(R.string.notify_sandbox_running)
            SandboxState.STARTING, SandboxState.INITIALIZING -> ctx.getString(R.string.notify_sandbox_starting)
            SandboxState.STOPPED -> ctx.getString(R.string.notify_sandbox_stopped)
            SandboxState.ERROR -> ctx.getString(R.string.notify_sandbox_error)
            else -> ctx.getString(R.string.notify_sandbox_other)
        }
        val dshText = when (dshState) {
            DshState.READY -> ctx.getString(R.string.notify_dsh_ready)
            DshState.RUNNING, DshState.STARTING -> ctx.getString(R.string.notify_dsh_starting)
            DshState.STOPPED -> ctx.getString(R.string.notify_dsh_stopped)
            DshState.ERROR -> ctx.getString(R.string.notify_dsh_error)
            else -> ctx.getString(R.string.notify_dsh_other)
        }
        val contentTitle = "$sandboxText · $dshText"
        val contentText = Constants.DSH_BASE_URL

        val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse(Constants.DSH_BASE_URL))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val startDshIntent = Intent(this, SandboxService::class.java).setAction(ACTION_START_DSH)
        val startDshPending = PendingIntent.getService(
            this,
            1,
            startDshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val restartDshIntent = Intent(this, SandboxService::class.java).setAction(ACTION_RESTART_DSH)
        val restartDshPending = PendingIntent.getService(
            this,
            2,
            restartDshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopSandboxIntent = Intent(this, SandboxService::class.java).setAction(ACTION_STOP_SANDBOX)
        val stopSandboxPending = PendingIntent.getService(
            this,
            3,
            stopSandboxIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF10A37F.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, ctx.getString(R.string.notify_action_open_dsh), openPending)
            .addAction(0, ctx.getString(R.string.notify_action_start_dsh), startDshPending)
            .addAction(0, ctx.getString(R.string.notify_action_restart_dsh), restartDshPending)
            .addAction(0, ctx.getString(R.string.notify_action_stop_sandbox), stopSandboxPending)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 渠道名一经创建即冻结，本地化需换 CHANNEL_ID 导致老用户通知设置重置
            // （1.2.1 决策：保留英文专名风格，不本地化）。
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DSH Sandbox",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "SandboxService"
        private const val CHANNEL_ID = "dsh_sandbox"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START_SANDBOX = "com.dshbox.app.action.START_SANDBOX"
        const val ACTION_STOP_SANDBOX = "com.dshbox.app.action.STOP_SANDBOX"
        const val ACTION_RESTART_SANDBOX = "com.dshbox.app.action.RESTART_SANDBOX"
        const val ACTION_START_DSH = "com.dshbox.app.action.START_DSH"
        const val ACTION_RESTART_DSH = "com.dshbox.app.action.RESTART_DSH"
        const val ACTION_STOP_DSH = "com.dshbox.app.action.STOP_DSH"
        const val ACTION_STOP_ALL = "com.dshbox.app.action.STOP_ALL"
        const val ACTION_REFRESH_NOTIFICATION = "com.dshbox.app.action.REFRESH_NOTIFICATION"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SandboxService::class.java),
            )
        }

        fun startSandbox(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_START_SANDBOX),
            )
        }

        fun stopSandbox(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_STOP_SANDBOX),
            )
        }

        fun restartSandbox(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_RESTART_SANDBOX),
            )
        }

        fun startDsh(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_START_DSH),
            )
        }

        fun restartDsh(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_RESTART_DSH),
            )
        }

        fun stopDsh(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_STOP_DSH),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_STOP_ALL),
            )
        }

        /** 语言切换后重建通知（服务已在前台，普通 startService 即可）。 */
        fun refreshNotification(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_REFRESH_NOTIFICATION),
            )
        }
    }
}
