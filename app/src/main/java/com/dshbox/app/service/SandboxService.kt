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
import android.system.Os
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
import com.dshbox.app.common.coroutineFailureHandler
import com.dshbox.app.runtime.AptSourceProvisioner
import com.dshbox.app.runtime.DpkgLedgerGuard
import com.dshbox.app.runtime.GuestUserProvisioner
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.ui.theme.AppLocaleState
import com.dshbox.app.ui.webview.dshBrowserChooser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import dshbox.adapter.dshboxRelayConfig
import interlock.relay.core.runtime.RelayRuntime

/**
 * Foreground service that owns the sandbox and DSH lifecycles.
 *
 * The sandbox and DSH are now decoupled:
 * - Sandbox (Debian/PRoot) can run independently.
 * - DSH requires the sandbox to be running; if not, the user is told to wake it.
 * - First launch auto-starts both; afterwards the user controls each separately.
 */
class SandboxService : Service() {

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + coroutineFailureHandler(TAG),
    )

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
            // 通知栏「打开 DSH」：**点击时**现取 token 再开浏览器。
            // 不能把带 token 的 URL 烤进 PendingIntent —— token 随 DSH 重启轮换，
            // 而通知只在状态变化时重建，烤进去的迟早是过期值（点了停在鉴权页）。
            // 交给 Service 承接这个动作，每次点击都读当前 token，永远是最新的。
            ACTION_OPEN_DSH -> openDshInBrowser()
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

    /**
     * 用外部浏览器打开 DSH（通知栏入口）。token 在此刻现取，因此总是最新值。
     *
     * 与首页入口同一套意图构造（[dshBrowserChooser]）：URL 必须带 token，且套 chooser
     * ——隐式 http 意图可被任何声明了 127.0.0.1 的应用抢走，而 token 等同沙箱控制权。
     * 失败（无浏览器/无 token）只提示不崩：这是可预期的环境问题。
     */
    private fun openDshInBrowser() {
        val token = sandboxManager.dshLaunchToken.value
        if (token.isNullOrEmpty()) {
            Toast.makeText(this, R.string.home_open_no_token, Toast.LENGTH_SHORT).show()
            return
        }
        val opened = runCatching {
            startActivity(
                dshBrowserChooser(
                    Constants.DSH_BASE_URL,
                    token,
                    getString(R.string.home_open),
                ),
            )
        }.onFailure { t -> Log.w(TAG, "open DSH in browser failed: ${t.message}") }.isSuccess
        if (!opened) Toast.makeText(this, R.string.home_open_browser_failed, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        terminalManager.stopAll()
        // 助手通道消费的是宿主私有目录里的信箱；不在这里停掉，下次启动会留下两个
        // 服务端抢同一个收件箱。
        RelayRuntime.stop()
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
        // 我方资产目录（.dsh/dshbox）平时只读（0555/0444），重写前先临时解锁、写完立刻固化回去
        // —— 见 hardenDshboxAssets 的 KDoc（为什么这样做、以及它挡得住什么挡不住什么）。
        setDshboxAssetsWritable(true)
        try {
            provisionMobileAdaptPlugin()
            provisionMobilePilotPlugin()
            provisionDshLinkShim()
            provisionDshCliShim()
        } finally {
            hardenDshboxAssets()
        }
        removeLegacyAssetDirs()
        removeRetiredPilotOverlay()
        migrateDshboxAssetLayout()
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
            // apt 源幂等校验：随包层的出厂源已失效，而离线导入路径没有对应写入点，在此补齐。
            // 只改 base 层里的一个文本文件；用户自配的可用源保持不变。
            val aptSourcesRewritten = runCatching {
                AptSourceProvisioner.provision(File(container.sandboxConfig.runtimeDir, "runtime-current/base"))
            }.getOrDefault(false)
            if (aptSourcesRewritten) Log.i(TAG, "bootstrap: apt sources rewritten (bundled mirror unreachable)")
            // guest passwd 条目：PRoot 以应用真实 uid 运行，而 guest /etc/passwd 里没有它 ——
            // 终端提示符会退化成 "I have no name!"（bash 查不到用户名），id/whoami/属主显示同样缺失。
            // uid/gid 运行时读取，不写死（换设备或换安装都会变）。
            val passwdOutcome = runCatching {
                GuestUserProvisioner.provision(File(container.sandboxConfig.runtimeDir, "runtime-current/base"))
            }.getOrNull()
            when (passwdOutcome) {
                GuestUserProvisioner.Outcome.UPDATED ->
                    Log.i(TAG, "bootstrap: guest passwd entry written for runtime uid")
                GuestUserProvisioner.Outcome.ALREADY_PRESENT ->
                    Log.i(TAG, "bootstrap: guest passwd entry already present")
                else -> Log.w(TAG, "bootstrap: guest passwd entry skipped (base layer unavailable)")
            }
            // dpkg 账本守卫：干净时留快照、未闭合时恢复到上一次一致状态（快照在宿主侧，不进层）。
            val ledger = runCatching {
                DpkgLedgerGuard.guard(
                    baseRoot = File(container.sandboxConfig.runtimeDir, "runtime-current/base"),
                    snapshotDir = File(container.sandboxConfig.appFilesDir, "dpkg-snapshot"),
                )
            }.getOrNull()
            when (ledger) {
                DpkgLedgerGuard.Outcome.RESTORED -> Log.w(TAG, "bootstrap: dpkg ledger restored from snapshot")
                DpkgLedgerGuard.Outcome.SNAPSHOTTED -> Log.i(TAG, "bootstrap: dpkg ledger snapshot taken")
                DpkgLedgerGuard.Outcome.SKIPPED -> Log.i(TAG, "bootstrap: dpkg ledger guard skipped (package tool running)")
                else -> Unit
            }
            provisionBundledDsh()
            // 两个自有插件包按开关对齐 profile。放在 startDsh 之前：装好当次启动即加载，
            // 用户不必手动移除再装配。
            ensureOwnPluginsAssembled()
            val firstRun = !prefs.getBoolean(Constants.PREF_FIRST_RUN_COMPLETED, false)
            if (firstRun) {
                Log.i(TAG, "bootstrap: first run, starting sandbox and dsh")
                sandboxManager.startSandbox()
                if (sandboxManager.sandboxState.value == SandboxState.RUNNING) {
                    val dshResult = startDsh()
                    if (dshResult is AppResult.Success) {
                        prefs.edit().putBoolean(Constants.PREF_FIRST_RUN_COMPLETED, true).apply()
                        Log.i(TAG, "bootstrap: first run complete")
                        // 全新安装时上面那次 ensure 必然落空：profile 目录要等 dsh 首次运行才存在，
                        // 而那之前我们不会替它造家目录。dsh 起来后 profile 已就位，这里补一次，
                        // 新用户这次会话就把包装配好（DSH 下次启动时生效，与手动装配同一语义）。
                        ensureOwnPluginsAssembled()
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
     * area (user-data/.dsh/dshbox/dshbox-plugins/mobile-adapt), fully overwritten each install, so the
     * user-triggered 装配 step can assemble it into the DSH profile via guest
     * command injection (method B).
     */
    private suspend fun provisionMobileAdaptPlugin() = withContext(Dispatchers.IO) {
        val assetDir = "plugins/dsh-mobile-adapt"
        val stageDir = File(pluginsStageRoot(), "mobile-adapt")
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
     * 与 [provisionMobileAdaptPlugin] 同形：把随包的 mobile-pilot（DSH连接手机）插件
     * 复制到 `user-data/.dsh/dshbox/dshbox-plugins/mobile-pilot` 暂存区，供「装配 / 卸载」的 guest 命令调用。
     *
     * 插件本体为完整版（14 个文件，含 `install.sh` / `uninstall.sh` 与 14 个 `phone_*` 工具的
     * 定义）。装配时 `install.sh` 只把包内的 `plugin/` 子目录 `cp -r` 进 profile，包里的
     * `test/` 与 `README.md` 因此只留在本暂存区，不进 DSH 的加载范围。
     *
     * 不固化权限（`chmodDshboxAssets` 只固化 `bin/`）：install.sh 的 `cp -r` 会按源模式建目标，
     * 只读源会把只读带进 profile 的 node_modules，使「移除 / 重装」从第二次起必然失败。
     */
    private suspend fun provisionMobilePilotPlugin() = withContext(Dispatchers.IO) {
        val assetDir = "plugins/mobile-pilot"
        val stageDir = File(pluginsStageRoot(), "mobile-pilot")
        try {
            if (stageDir.exists()) stageDir.deleteRecursively()
            stageDir.mkdirs()
            copyAssetTree(assetDir, stageDir)
            Log.i(TAG, "bootstrap: mobile-pilot plugin staged to ${stageDir.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap: provision mobile-pilot plugin failed: ${t.message}")
        }
    }

    /**
     * 确保两个自有插件包（移动端适配 / DSH连接手机）在 dsh 启动前**装配到位**。
     *
     * ## 开关为开 = 真的装进去
     *
     * 面板上的开关表达的是「用户要不要这个包」，所以启动期按它对齐 profile 的实际状态：
     * 缺就装、旧就刷新、一致就跳过。这样新装用户第一次进 DSH 就有适配包，
     * 而不是"开关亮着、profile 里什么都没有"。
     *
     * 开关为**关**时这里一个字节都不动 —— 用户关掉之后，之后任何一次启动都不会把它装回来。
     *
     * ## 判定只在一次 guest 命令里完成
     *
     * 每次 `runGuestCommand` 都要 spawn 一个 proot 进程，拆成多条会让"已最新"这个最常见的
     * 路径也重复付费。脚本拼接与标记解析收敛在 [OwnPluginProbe]（纯函数 + 单测）：
     * 手工拼接的 shell 少一个 `&&` 就会静默改变语义，编译器看不出来。
     *
     * 唯一允许动用户 profile 的结论是 `NEEDS_INSTALL`；`PROFILE_MISSING`（DSH 从没跑过，
     * 不替他造家目录）、`STAGE_MISSING`（随包资产没复制成功，装也装不成）与"没拿到标记"
     * （proot 失败 / guest 异常 / 脚本被中断）一律跳过，下次启动再试。
     */
    private suspend fun ensureOwnPluginsAssembled() {
        ensureOwnPlugin(
            label = "mobile-adapt",
            bundleId = "@local/dsh-mobile-adapt",
            intentKey = Constants.PREF_MOBILE_ADAPT_INSTALLED,
            errorKey = Constants.PREF_MOBILE_ADAPT_LAST_ERROR,
            stageName = "mobile-adapt",
        )
        ensureOwnPlugin(
            label = "mobile-pilot",
            bundleId = "@local/mobile-pilot",
            intentKey = Constants.PREF_MOBILE_PILOT_ENABLED,
            errorKey = Constants.PREF_MOBILE_PILOT_LAST_ERROR,
            stageName = "mobile-pilot",
        )
    }

    /**
     * 单个自有插件包与 profile 对齐：开关为关则不动；为开则按探测结论决定是否跑 `install.sh`。
     *
     * 失败时把开关**回退为 false** 并把原因写进偏好：启动期没有前台 UI 可弹提示，
     * 但页面上必须能看出"没装成"——否则开关继续显示为开，用户以为装好了，
     * 实际跑的是旧插件或根本没有插件。
     *
     * @param intentKey 「用户要不要这个包」的偏好键
     * @param errorKey 失败原因落盘的位置（面板读取展示）
     * @param stageName 随包资产在 guest 暂存目录下的子目录名
     */
    private suspend fun ensureOwnPlugin(
        label: String,
        bundleId: String,
        intentKey: String,
        errorKey: String,
        stageName: String,
    ) {
        // 键没写过时按"默认开启"处理：面板首次运行会把默认值真正落盘（见 AdapterPluginController）。
        if (!prefs.getBoolean(intentKey, true)) {
            Log.i(TAG, "bootstrap: $label disabled; leaving profile untouched")
            return
        }
        val profile = Constants.DSH_WEB_PROFILE_GUEST_DIR
        val pluginDir = "$profile/node_modules/$bundleId"
        val stage = "${Constants.DSHBOX_PLUGINS_GUEST_DIR}/$stageName"

        val probeScript = OwnPluginProbe.buildScript(
            profileDir = profile,
            pluginDir = pluginDir,
            stageInstall = "$stage/install.sh",
            stagePlugin = "$stage/plugin",
            profilePackageJson = "$profile/package.json",
            bundleId = bundleId,
            fingerprintFiles = stagedPluginFiles(stageName),
        )

        // 输出行由后台读流线程回调（见 SandboxProcessRunner），用 AtomicReference 承接，
        // 避免跨线程可见性问题。
        val probeOutcome = java.util.concurrent.atomic.AtomicReference<String?>(null)
        sandboxManager.runGuestCommand(probeScript, onLine = { line ->
            OwnPluginProbe.markerFrom(line)?.let { probeOutcome.set(it) }
        })
        val marker = probeOutcome.get()
        if (OwnPluginProbe.actionFor(marker) == OwnPluginProbe.Action.SKIP) {
            Log.i(TAG, "bootstrap: $label not assembled (marker=${marker ?: "none"}); leaving profile untouched")
            return
        }

        // `chmod -R u+w` 先解除可能的只读：早期版本把暂存树固化成了 0555/0444，`cp -r` 会把
        // 只读带进 profile 副本，导致 install.sh 里的 `rm -rf` 失败。幂等、失败忽略。
        val res = sandboxManager.runGuestCommand(
            "chmod -R u+w $pluginDir 2>/dev/null || true; bash $stage/install.sh $profile",
            onLine = {},
        )
        when (res) {
            is AppResult.Success -> {
                Log.i(TAG, "bootstrap: $label assembled from bundled version")
                prefs.edit().remove(errorKey).apply()
            }
            is AppResult.Failure -> {
                Log.w(TAG, "bootstrap: $label assembly failed: ${res.error.message}")
                // 回退开关：让面板显示真实状态（未装配），用户可手动重装并看到失败原因。
                prefs.edit()
                    .putBoolean(intentKey, false)
                    .putString(errorKey, res.error.message.ifBlank { res.error.code })
                    .apply()
                Log.w(TAG, "bootstrap: cleared $label flag so the UI reflects the failed assembly")
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
            // apt/dpkg 用的硬链接垫片（aarch64 .so）。宿主机侧本目录会被 bind 到 guest 的
            // /opt/dshbox，层替换也不会丢；是否需要注入由包装脚本决定
            // （源码与构建脚本见 plugin-manager/dshbox-plugins/tools/link-shim）。
            applicationContext.assets.open("dshbox/libdshbox-link.so").use { input ->
                File(config.dshShimDir, "libdshbox-link.so").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "bootstrap: dsh link shim staged to ${config.dshShimFile.absolutePath}")
        } catch (t: Throwable) {
            // 垫片缺失只会让硬链接兼容失效（DSH 仍可启动，见 linkShimHostDir 的说明），
            // 不该阻断 bootstrap，因此这里只记录。
            Log.w(TAG, "bootstrap: provision dsh link shim failed: ${t.message}")
        }
    }

    /**
     * 把 `dsh` 命令装进工作区（`user-data/.dsh/dshbox/bin/dsh`），供**终端**与 guest 命令使用。
     *
     * ## 为什么需要它
     *
     * DSH 官方 CLI 的入口是 `node_modules/@deepseek-ai/dsh/lib/bin.js`（`package.json`
     * 的 `bin.dsh`），设备上**没有 npm 全局 bin**，所以终端里敲 `dsh` 必然 command not found。
     * 而这个 CLI 很有用：`dsh --profile headless "…"` 一次性任务、`dsh --profile tui` 交互式
     * 会话、`dsh plugin add …` 管理插件、`dsh --dump-config` 查看合成后的 profile。
     *
     * ## 为什么写在工作区而不是运行环境层里
     *
     * 运行环境三层（base/node/dsh）是 app 管理的只读资产：往里面写文件既会破坏"层内容 = 设计
     * 原样"的前提，也会在下次导入/更新时被覆盖。工作区（`user-data`）是用户数据区，随 APK
     * 升级与运行环境替换都不动，且已经有 `.dsh` 目录（profile 所在），放这里最自然。
     * 代价是需要把 `${Constants.DSHBOX_ASSETS_GUEST_DIR}/bin` 加进 PATH —— 终端环境与 guest 命令环境都已加。
     *
     * ## 为什么必须带 `--import` 垫片
     *
     * DSH 用 `link()` 作为"不可覆盖地发布文件"的原语（会话持久化 / 写工具 / 附件发布 /
     * 插件安装都会走到），而 Android app 数据分区**拒绝硬链接**；app 启动网页端 DSH 时
     * 一直预加载 `/opt/dshbox/link-shim.mjs`。CLI 若不带垫片，这些链路会以 EPERM/EACCES 失败
     * （且失败点很分散，表现为"会话存不下来"这类怪现象），所以这里保持一致。
     *
     * ## `dsh web` 的限制
     *
     * `dsh web` 会再起一个网页服务，与 app 已在跑的 3080 端口冲突。脚本注释里写明了，
     * 引导用户用 headless / tui / plugin 这些子命令。
     *
     * 每次 bootstrap 覆写一次（与垫片、移动端适配插件同一策略），保证 APK 升级后脚本同步更新。
     */
    private suspend fun provisionDshCliShim() = withContext(Dispatchers.IO) {
        val config = (application as DshApp).container.sandboxConfig
        try {
            val binDir = File(File(config.userDataDir, Constants.DSHBOX_ASSETS_RELATIVE_DIR), "bin")
            binDir.mkdirs()
            val scripts = buildList {
                add("dsh" to DSH_CLI_SHIM)
                add("pnpm" to PNPM_CLI_SHIM)
                // 包管理器也走包装脚本：注入硬链接垫片 + fakeroot(tcp)，否则带维护脚本的包
                // 必然半途而废（link(2) 与 chown 在本环境都不可用）。
                for (tool in listOf("apt", "apt-get", "dpkg")) add(tool to pkgToolWrapper(tool))
                // 终端 Agent：包装脚本常驻，本体按需安装（安装态探测另看安装目录）。
                add(Constants.OPENCODE_SHIM_NAME to OPENCODE_CLI_SHIM)
            }
            for ((name, body) in scripts) {
                val script = File(binDir, name)
                script.writeText(body)
                // 必须可执行：guest 里是直接 exec 这个文件（不是 `bash dsh`）。
                // readOnly=false 让 x 位对所有用户生效（guest 侧是 proot 假 root，实为 app uid）。
                script.setExecutable(true, false)
            }
            Log.i(TAG, "bootstrap: dsh/pnpm/pkg CLI shims staged to ${binDir.absolutePath}")
        } catch (t: Throwable) {
            // 只是"终端里没有 dsh/pnpm 命令"，不影响 app 任何既有功能，故不阻断 bootstrap。
            Log.w(TAG, "bootstrap: provision dsh CLI shim failed: ${t.message}")
        }
    }

    /**
     * `apt` / `apt-get` / `dpkg` 的包装脚本正文（装到工作区 `.dsh/dshbox/bin`，PATH 首位）。
     *
     * 两处注入，缺一不可：
     *  - **硬链接垫片**：dpkg 在事务末尾创建账本备份时调用 `link(2)`，而本环境（Android 应用数据
     *    文件系统）不支持硬链接，垫片把它退化为复制；
     *  - **fakeroot（tcp 变体）**：伪造 root 下维护脚本的 `chown` / `useradd` 等会被内核拒绝，
     *    fakeroot 在虚拟层完成这些操作后事务才能走完。
     *
     * 两者可共存：fakeroot 的启动脚本会**保留**既有的 `LD_PRELOAD`（把我们的垫片追加在其库之后）。
     * 只在对应文件存在时才注入，缺失即退化为直接执行原命令，不影响原有能力。
     */
    private fun pkgToolWrapper(tool: String): String =
        "#!/bin/sh\n" +
            "# DSHBox: 包管理器包装——硬链接垫片（link(2) 退化为复制） + fakeroot 属主虚拟化。\n" +
            "SHIM=${Constants.DSH_LINK_SHIM_SO_GUEST_PATH}\n" +
            "if [ -f \"\$SHIM\" ]; then LD_PRELOAD=\"\$SHIM\${LD_PRELOAD:+:\$LD_PRELOAD}\"; export LD_PRELOAD; fi\n" +
            "if [ -x /usr/bin/fakeroot-tcp ]; then exec /usr/bin/fakeroot-tcp -- /usr/bin/$tool \"\$@\"; fi\n" +
            "exec /usr/bin/$tool \"\$@\"\n"

    /**
     * opencode 终端 Agent 的命令包装脚本（常驻于 CLI 垫片目录，终端 PATH 首项）。
     *
     * 本体装在「工具链缓存」挂载点内（不在工作区、也不在运行层），因此这里只做两件事：
     * 未安装时给出引导并退出（而不是让终端报"命令不存在"）；已安装时把配置与用户数据一并
     * 引到本体所在目录，使"删除"只需删那一个目录。
     */
    private val OPENCODE_CLI_SHIM: String = buildString {
        val dir = Constants.OPENCODE_GUEST_DIR
        append("#!/bin/sh\n")
        append("# DSHBox: opencode terminal agent wrapper (the tool itself is installed on demand).\n")
        append("LAUNCHER=\"${Constants.OPENCODE_LAUNCHER}\"\n")
        append("if [ ! -x \"\$LAUNCHER\" ]; then\n")
        append("  echo \"opencode is not installed yet. Install it from DSHBox > plugin panel.\" >&2\n")
        append("  exit 127\n")
        append("fi\n")
        // 配置与用户数据一并引到本体所在目录：这样"删除"只需删那一个目录。
        for ((variable, sub) in listOf(
            "XDG_CONFIG_HOME" to "config",
            "XDG_DATA_HOME" to "data",
            "XDG_CACHE_HOME" to "cache",
            "XDG_STATE_HOME" to "state",
        )) {
            append("export $variable=\"$dir/$sub\"\n")
        }
        append("export HOME=\"$dir/home\"\n")
        append("exec \"\$LAUNCHER\" \"\$@\"\n")
    }

    /**
     * 清掉资产收拢前的旧位置（`.dsh/bin`、`.dsh/mobile-adapt`、`.dsh/pilot`）。
     *
     * 这三个目录是我们早期版本散放资产的地方，现在统一到 `.dsh/dshbox`。老用户设备上会留下
     * 空壳/旧副本会留在老设备上，留着只会让人分不清哪份是现役 —— 一次性删掉。
     * 只删这三个**我们自己**的目录，绝不碰 DSH 的数据（profiles/sessions/storages/…）。
     */
    private fun removeLegacyAssetDirs() {
        // 真正一次性：`.dsh/bin` 之类目录名没有命名空间（对比 `dshbox`），而 `.dsh` 是用户
        // 唯一能放东西的地方之一 —— 每次启动都无条件删会静默吃掉用户自己建的 `~/.dsh/bin`。
        if (prefs.getBoolean(Constants.PREF_LEGACY_ASSETS_PURGED, false)) return
        val dshDir = File((application as DshApp).container.sandboxConfig.userDataDir, ".dsh")
        for (name in listOf("bin", "mobile-adapt", "pilot")) {
            val legacy = File(dshDir, name)
            if (!legacy.exists()) continue
            val ok = runCatching { legacy.deleteRecursively() }.getOrDefault(false)
            Log.i(TAG, "bootstrap: legacy asset dir .dsh/$name removed=$ok")
        }
        prefs.edit().putBoolean(Constants.PREF_LEGACY_ASSETS_PURGED, true).apply()
    }

    /**
     * 清掉已退役的 pilot 覆盖层（`.dsh/dshbox/pilot/pilot-overlay.yml`）。
     *
     * 那一层曾作为 DSH 接入层以 `--patch` 注入，现已取消：自有插件改走 profile 装配，
     * 不再需要它。老用户设备上会留下这个文件，清掉以免让人以为还有一层在生效。
     *
     * 只删这一份**我们自己生成的**文件；目录空了才顺带删掉 —— 里面另有东西就留着。
     */
    private fun removeRetiredPilotOverlay() {
        val dir = File(dshboxAssetsDir(), "pilot")
        val overlay = File(dir, "pilot-overlay.yml")
        if (overlay.isFile) {
            val ok = runCatching { overlay.delete() }.getOrDefault(false)
            Log.i(TAG, "bootstrap: retired pilot overlay removed=$ok")
        }
        runCatching { dir.delete() }
    }

    /**
     * 资产目录布局的一次性迁移：旧名 / 旧位置 → 新名（见 [DshboxLayoutMigration]）。
     *
     * 主调用点在应用启动时（`DshApp.onCreate`，**早于任何写新路径的代码**）；这里是启动期的
     * 重试兜底 —— 文件被占等一次性失败可在这里补做。整段幂等，成功后每次都是空跑。
     *
     * 位置要求：必须在 dsh 启动之前 —— 三份层的路径都写在 dsh 启动命令行的 `--patch` 里。
     */
    private fun migrateDshboxAssetLayout() {
        val report = DshboxLayoutMigration.run(dshboxAssetsDir())
        if (report.changed) {
            Log.i(
                TAG,
                "bootstrap: dshbox layout migrated moved=${report.moved} " +
                    "discarded=${report.discarded} divergent=${report.keptDivergent} " +
                    "removed=${report.removedDirs} failed=${report.failed}",
            )
        }
    }

    /** 自有插件包的装配暂存根（`user-data/.dsh/dshbox/dshbox-plugins`）。 */
    private fun pluginsStageRoot(): File =
        File(dshboxAssetsDir(), Constants.DSHBOX_PLUGINS_LEAF)

    /** 我方随包资产目录（`user-data/.dsh/dshbox`）。 */
    private fun dshboxAssetsDir(): File =
        File((application as DshApp).container.sandboxConfig.userDataDir, Constants.DSHBOX_ASSETS_RELATIVE_DIR)

    /**
     * 暂存包里参与一致性比对的文件（相对 `plugin/` 根，已排序）。
     *
     * 由**暂存包的实际内容**推导，而不是写死一份清单：两个包的组成并不相同
     * （适配包有 `lib/client.js`，mobile-pilot 空壳只有 `lib/index.js`）。
     * 写死的清单一旦包含包里并不存在的文件，探测就永远判不出「已最新」——
     * 表现为每次启动都重装一遍，而日志只像是"又更新了"。
     */
    private fun stagedPluginFiles(stageName: String): List<String> {
        val pluginDir = File(File(pluginsStageRoot(), stageName), "plugin")
        if (!pluginDir.isDirectory) return emptyList()
        return pluginDir.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(pluginDir).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    /**
     * 把 `.dsh/dshbox`（我方随包资产：dsh/pnpm 包装脚本、两个自有插件包的装配暂存）
     * **固化或临时解锁**。
     *
     * ## 为什么
     *
     * 这个目录里的一切都是我们发布的固定资产，不该被用户或 DSH 改坏：包装脚本被改会让
     * `dsh` 命令行为失控，插件暂存被改会让装配结果不可控。做法是权限固化：
     * 目录 `0555`、普通文件 `0444`、脚本 `0555`（`bin` 目录下的脚本与 `*.sh` 保留执行位）。
     *
     * ## 挡得住什么、挡不住什么（**不构成安全边界**）
     *
     * - **挡得住**：app 自己的文件操作（Files 页普通读写/删除/重命名都不经 PRoot，
     *   内核按真实 uid 检查 → 0555/0444 直接 EACCES），外加 Files 页的 UI 层硬拦截；
     *   权限被改坏时**每次 bootstrap 会重新固化**。
     * - **挡不住 guest（终端 / DSH / agent 工具）**：在 guest 里对 0555 的
     *   `.dsh/dshbox` 执行 `touch x` 与 `rm -rf bin` **都成功**了 —— PRoot 的 `-0`
     *   假 root 会替调用方"越过"权限位（宿主侧确认 `bin/` 被删、`x` 被创建）。
     *   同一 uid 下我们也拿不到 `chattr +i`（需 CAP_LINUX_IMMUTABLE）或换 owner（需 root）。
     * - **真正的保障是"每次都重铺"**：`.dsh/dshbox` 里的内容每次启动都由 APK 资产重新覆盖
     *   （`provisionMobileAdaptPlugin` / `provisionDshCliShim`），所以 guest 侧的任何篡改
     *   **在下次启动就会被还原**；两个自有插件包还有指纹比对（OwnPluginProbe）兜底。
     *   也就是说：这是"我们拥有 + 自动还原"，不是"用户改不动"。
     *   若哪天真要做到不可改，只能把母本放到工作区之外并让 guest 只读地看 —— 代价是
     *   用户在文件页里根本看不到这个目录。
     */
    private fun hardenDshboxAssets() = chmodDshboxAssets(writable = false)

    /** 临时解锁（重写资产前调用）；写完必须调 [hardenDshboxAssets] 固化回去。 */
    private fun setDshboxAssetsWritable(writable: Boolean) = chmodDshboxAssets(writable = writable)

    private fun chmodDshboxAssets(writable: Boolean) {
        val root = dshboxAssetsDir()
        if (!root.isDirectory) return
        if (writable) {
            // **解锁走整棵树**：既为重写开路，也负责把历史版本误固化过的地方修回可写
            // （`mobile-adapt/` 等暂存目录曾被一起设为 0555/0444）—— 否则升级后那些文件
            // 仍是只读，`install.sh` 的 `cp -r` 会失败。
            runCatching { Os.chmod(root.absolutePath, MODE_DIR_RW) }
            root.walkTopDown().forEach { f ->
                val isScript = f.name.endsWith(".sh") || f.parentFile?.name == "bin"
                val mode = when {
                    f.isDirectory -> MODE_DIR_RW
                    isScript -> MODE_EXEC_RW
                    else -> MODE_FILE_RW
                }
                runCatching { Os.chmod(f.absolutePath, mode) }
                    .onFailure { Log.w(TAG, "dshbox unlock failed: ${f.absolutePath} (${it.message})") }
            }
            return
        }
        // ⚠️ **固化只作用于 `bin/`**（dsh / pnpm 包装脚本）——下面这条不能固化：
        //  · `mobile-adapt/`、`mobile-pilot/` 是各自 `install.sh` 里 `cp -r` 的**源**，
        //    GNU cp 会按源模式建目标 → 只读会被带进 profile 的 node_modules，
        //    使"移除/重装"从第二次起必然失败。
        // 其余文件保持默认可写，让它们各自的写者（app / guest）能正常工作。
        val bin = File(root, "bin")
        if (!bin.isDirectory) return
        runCatching { Os.chmod(bin.absolutePath, MODE_DIR_RO) }
        bin.listFiles()?.forEach { f ->
            val mode = if (f.isDirectory) MODE_DIR_RO else MODE_EXEC_RO
            runCatching { Os.chmod(f.absolutePath, mode) }
                .onFailure { Log.w(TAG, "dshbox chmod failed: ${f.absolutePath} (${it.message})") }
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
        // 助手通道必须在 DSH 起来之前就绪：guest 侧把它当成 /opt/pilot，
        // 通道不在时每一次调用都会等到超时。
        RelayRuntime.start(applicationContext, dshboxRelayConfig(applicationContext))
        sandboxManager.startSandbox()
    }

    private suspend fun stopSandbox() {
        // The terminal login shell lives inside the sandbox rootfs; kill it
        // first so it never outlives the PRoot tree it depends on.
        terminalManager.stopSandboxSession()
        RelayRuntime.stop()
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

        // 打开动作交给 Service（见 openDshInBrowser）：token 在点击那一刻现取。
        val openPending = PendingIntent.getService(
            this,
            0,
            Intent(this, SandboxService::class.java).setAction(ACTION_OPEN_DSH),
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
        const val ACTION_OPEN_DSH = "com.dshbox.app.action.OPEN_DSH"
        const val ACTION_REFRESH_NOTIFICATION = "com.dshbox.app.action.REFRESH_NOTIFICATION"

        /**
         * `dsh` 包装脚本正文（装到工作区 `.dsh/dshbox/bin/dsh`，由 [provisionDshCliShim] 落盘）。
         *
         * 里面的路径都是 **guest 侧**路径（脚本在沙箱内执行），因此是字面量；
         * 设计取舍见 [provisionDshCliShim] 的 KDoc。
         */
        /** chmod 模式字面量（Os.chmod 用十进制；这里写明对应八进制，免得读者自己换算）。 */
        private const val MODE_DIR_RW = 493    // 0755
        private const val MODE_DIR_RO = 365    // 0555
        private const val MODE_FILE_RW = 420   // 0644
        private const val MODE_FILE_RO = 292   // 0444
        private const val MODE_EXEC_RW = 493   // 0755（脚本）
        private const val MODE_EXEC_RO = 365   // 0555（脚本，保留执行位）

        private val DSH_CLI_SHIM = """
            #!/bin/sh
            # DSHBox: point `dsh` at the bundled DSH layer (official CLI entry).
            #
            # 不用 npm 全局安装的 dsh：设备上没有 npm 全局 bin，DSH 是 app 管理的独立层
            # （runtime-current/dsh），必须走 /opt/dshapp/runtime 这份，才能与网页端
            # 同版本、同 profile（${'$'}DSH_HOME/profiles）。
            #
            # 必须预加载硬链接垫片：Android app 数据分区不支持硬链接，而 DSH 用 link()
            # 作为"不可覆盖地发布文件"的原语（会话持久化 / 写工具 / 附件发布 / 插件安装
            # 都会走到），不加载垫片这些链路会以 EPERM/EACCES 失败。
            #
            # 提示：`dsh web` 会再起一个网页服务，与 app 已在跑的 3080 端口冲突。
            # 终端里建议用：dsh --profile headless "<任务>" / dsh --profile tui /
            # dsh plugin ... / dsh --dump-config
            [ -n "${'$'}DSH_HOME" ] || DSH_HOME=/root/projects/.dsh
            export DSH_HOME
            BIN="${'$'}{DSH_BIN:-/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js}"
            SHIM=""
            [ -f /opt/dshbox/link-shim.mjs ] && SHIM="--import /opt/dshbox/link-shim.mjs"
            exec /usr/local/bin/node --expose-internals ${'$'}SHIM "${'$'}BIN" "${'$'}@"
        """.trimIndent()

        /**
         * `pnpm` 包装脚本。`dsh plugin …` 会把参数**转发给 pnpm**（在 profile 目录里装/删插件），
         * 而设备上没有全局 pnpm —— 只有 Node 自带的 corepack。这里用 corepack 代跑。
         *
         * ## 缓存位置是刻意指定的
         *
         * pnpm 的 store 与 corepack 的缓存**默认都往 `$HOME` 写**，在 guest 里即 `/root`：
         *  - corepack 缓存 → 会落进工作区（约 20 MB 量级，用户可见）；
         *  - pnpm store（内容寻址，最大头）→ 会落进 **base 层**的 `/root/.local/share/pnpm`，
         *    既不在 Files 页可见、也不在清理规则覆盖范围内（与 npm 缓存膨胀同类，可达数百 MB）。
         * 因此两者都指到 `/opt/dshbox-cache` 挂载点（宿主 `filesDir/guest-cache`，不进用户数据）。
         * 挂载点不存在时（例如只导入了 Linux 层）回落到工作区，保证脚本仍能跑。
         */
        private val PNPM_CLI_SHIM = """
            #!/bin/sh
            # DSHBox: `pnpm` via Node's bundled corepack (no global pnpm on device).
            #
            # 只做一件事：把 pnpm 的 store 指到缓存挂载点。
            # **不动 COREPACK_HOME** —— 运行时自带的 corepack 缓存里可能已经预激活了 pnpm
            # （离线/内网场景靠它），覆盖成新目录会必然 miss、被迫重新联网下载。
            # 缓存挂载点不存在时（理论上绑定时就 mkdirs 了，属兜底）回落到工作区。
            if [ -d /opt/dshbox-cache ]; then
              STORE=/opt/dshbox-cache/pnpm-store
            else
              STORE=/root/projects/.dsh/pnpm-store
            fi
            mkdir -p "${'$'}STORE"
            exec /usr/local/bin/corepack pnpm --store-dir="${'$'}STORE" "${'$'}@"
        """.trimIndent()

        /**
         * 尽力启动前台服务，返回是否真的启动了。
         *
         * **为什么不能直接让异常抛出去**：本方法由 `DshApp.onCreate` 调用，而进程**不一定**是被用户打开的 ——
         * 系统为绑定本应用的组件（前台服务 / FileProvider 等）也会在后台把本进程拉起来。这种情况下（Android 12+）
         * `startForegroundService` 会被拒绝并抛 `ForegroundServiceStartNotAllowedException`；
         * 异常穿透 `Application.onCreate` 会让**整个进程启动即崩** —— 无障碍服务因此永远绑不上，
         * 用户看到的是「已授权但助手看不了屏幕」。
         *
         * 这不算错误状态：后台被拉起时没有界面，本来也不需要跑沙盒；等用户真正打开界面时，
         * 这条路径在**前台**调用，系统允许，行为与以前完全一致。
         *
         * 注：`ForegroundServiceStartNotAllowedException` 继承自 `IllegalStateException`，
         * 按父类捕获即可同时兼容 minSdk 29。
         */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SandboxService::class.java),
            )
            true
        } catch (e: IllegalStateException) {
            Log.w(
                TAG,
                "后台启动前台服务被系统拒绝，改为等界面出现再启动（正常路径）：" +
                    "${e.javaClass.simpleName}: ${e.message}",
            )
            false
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
