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
import com.dshbox.app.MainActivity
import com.dshbox.app.util.BackgroundOps
import com.dshbox.app.R
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.common.DshInstance
import com.dshbox.app.common.coroutineFailureHandler
import com.dshbox.instance.InstancePolicy
import com.dshbox.app.runtime.AptSourceProvisioner
import com.dshbox.app.runtime.DpkgLedgerGuard
import com.dshbox.app.runtime.GuestUserProvisioner
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.sandbox.OfficialPluginOverlay
import com.dshbox.app.sandbox.DshUpdateOutcome
import com.dshbox.app.common.AppError
import com.dshbox.app.instance.InstanceOpState
import com.dshbox.pluginmanager.core.BrowserAdapterAssemblyGate
import com.dshbox.app.ui.theme.AppLocaleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
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

    /** 通知栏「启动」去重：快速双击只跑一次组合启动。 */
    private val startAllInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var browserStageProvisioned = false

    /**
     * 自有插件包装配的串行化锁。
     *
     * 装配有多个触发点（启动期对齐、首次运行补装、DSH 进入 READY），彼此可能重叠；
     * 而安装脚本用 `rm -rf` + `cp -r` 直接改 profile —— 两个装配并行会互相删掉对方
     * 正在复制的目录，在 profile 里留下半装配的包。
     */
    private val pluginAssemblyMutex = Mutex()

    private val sandboxManager
        get() = (application as DshApp).container.sandboxManager

    private val instanceRegistry
        get() = (application as DshApp).container.instanceRegistry

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
        serviceScope.launch { alignPluginsOnDshReady() }
        serviceScope.launch { bootstrap() }
    }

    /**
     * DSH 就绪即把自有插件包与开关对齐。
     *
     * ## 为什么需要这条触发
     *
     * 随 APK 的运行时资产里没有 base / node 层（体积考虑，由「在线导入」装上），
     * 而 [bootstrap] 里两次装配都位于 `isRuntimeInstalled()` 之内 ——
     * 新装用户这条路径上一次都不会执行，于是出现「面板开关亮着、profile 里什么都没有」。
     * DSH 能进入 READY，说明运行环境与 profile 都已就位，这是唯一必然成立的时机；
     * 在线导入的两条链路（Linux 层 / node 层）末尾都会尝试启动 DSH（Linux 层那条
     * 要求 node 层已在位），最终启动成功的那一次都会进入 READY，因此一并覆盖。
     * 若此刻 profile 尚未就位（探测返回 PROFILE_MISSING），本次跳过，等下一次 READY 跳变再对齐。
     *
     * ## 语义要点
     *
     * - 用 [collect] 而不是 `collectLatest`：装配是一条长 guest 命令，`collectLatest`
     *   会在下一次状态发射时把进行中的装配取消在半途。
     * - `dshState` 是 StateFlow，同值不重复发射，因此这里等价于「**进入 READY 的那次跳变**」；
     *   跑到 ERROR 再回到 READY（自动重启成功 / 用户手动重启）会再来一次 —— 这正是想要的，
     *   上一次被跳过或失败的包有机会补上（合并窗口内发生的往返会被 StateFlow 折叠成一次，
     *   那种情况下这次补装推迟到再下一次跳变）。
     * - 装配本身幂等（探测到已是最新即跳过），与 [bootstrap] 的调用重叠由
     *   [pluginAssemblyMutex] 串行化，第二次只会多做一次探测。
     * - 此刻 DSH 正在运行；脚本会改动 profile 内的 node_modules 与 `package.json`，
     *   装配结果在 DSH **下次启动**时生效 —— 与「用户手动装配」同一语义。
     * - 单次装配最长可占住本收集器到 guest 命令超时（10 分钟，见 SandboxProcessRunner）；
     *   期间发生的状态往返会被 StateFlow 合并成一次，代价只是少跑一次幂等探测。
     */
    private suspend fun alignPluginsOnDshReady() {
        var previous: DshState? = null
        sandboxManager.dshState.collect { state ->
            val trigger = PluginAssemblyTrigger.shouldAssemble(previous, state)
            previous = state
            if (trigger) {
                Log.i(TAG, "dsh ready: aligning bundled plugin packages with switches")
                try {
                    ensureOwnPluginsAssembled()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // 这条收集器要活过整个服务生命周期：一次装配失败不能带走触发通道，
                    // 否则此后所有 READY 都不再对齐，退回"开关亮着、profile 里没有"。
                    Log.w(TAG, "dsh ready: plugin alignment failed: ${t.message}", t)
                }
            }
        }
    }

    // ---- 实例生命周期编排（切换 / 创建应急 / 删除应急 / 层修复）----

    /** 实例操作统一门禁：后台操作或另一实例操作进行中拒绝；更新可观察状态。 */
    private suspend fun runInstanceOp(
        kind: InstanceOpState.Kind,
        block: suspend () -> AppResult<Unit>,
    ): AppResult<Unit> {
        // 原子 CAS 进入运行态；拒绝路径不触碰状态（避免清掉进行中操作的 running）。
        if (BackgroundOps.isBusy || !InstanceOpState.tryBegin(kind)) {
            return AppResult.Failure(AppError("INSTANCE_OP_BUSY", "another operation is in progress"))
        }
        return try {
            // 登记后台操作：阻止设置页清理并发删除暂存/换层中间产物。
            val result = BackgroundOps.runTracked { block() }
            InstanceOpState.finish(result is AppResult.Success, (result as? AppResult.Failure)?.error?.message)
            result
        } catch (t: CancellationException) {
            InstanceOpState.finish(false, "cancelled")
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "instance op $kind failed: ${t.message}", t)
            InstanceOpState.finish(false, t.message)
            AppResult.Failure(AppError("INSTANCE_OP_FAILED", t.message ?: "instance operation failed"))
        }
    }

    /** 切换活跃实例：停 DSH → 翻槽 → 沙箱在运行则重启 DSH（不停沙箱）。 */
    private suspend fun switchInstanceInternal(target: DshInstance): AppResult<Unit> {
        if (instanceRegistry.active.value == target) return AppResult.Success(Unit)
        val sandboxWasRunning = sandboxManager.sandboxState.value == SandboxState.RUNNING
        sandboxManager.stopDsh()
        instanceRegistry.switchTo(target)
        if (sandboxWasRunning) {
            val r = sandboxManager.startDsh()
            if (r is AppResult.Failure) return AppResult.Failure(r.error)
        }
        return AppResult.Success(Unit)
    }

    suspend fun switchInstance(target: DshInstance): AppResult<Unit> =
        runInstanceOp(InstanceOpState.Kind.SWITCH) { switchInstanceInternal(target) }

    /**
     * 懒创建应急实例：建 home → 翻槽 → 暂存应急资产 → 首启 DSH 自建 profile →
     * 装配 mobile-adapt → 重启生效。仅在用户确认后触发。
     */
    suspend fun createEmergencyInstance(): AppResult<Unit> =
        runInstanceOp(InstanceOpState.Kind.CREATE_EMERGENCY) {
            val sandboxWasRunning = sandboxManager.sandboxState.value == SandboxState.RUNNING
            sandboxManager.stopDsh()
            when (val r = instanceRegistry.createEmergencyHome()) {
                is AppResult.Failure -> return@runInstanceOp r
                else -> Unit
            }
            instanceRegistry.switchTo(DshInstance.EMERGENCY)
            // 翻槽后 provision 落入应急 home（资产目录随活跃槽解析）。
            setDshboxAssetsWritable(true)
            try {
                pluginAssemblyMutex.withLock {
                    provisionMobileAdaptPlugin()
                    provisionDshCliShim()
                }
                OfficialPluginOverlay.refresh(applicationContext, DshInstance.EMERGENCY)
            } finally {
                hardenDshboxAssets()
            }
            if (sandboxWasRunning) {
                val started = sandboxManager.startDsh()
                if (started is AppResult.Failure) return@runInstanceOp AppResult.Failure(started.error)
                // profile 首启后才存在；装配 mobile-adapt 后重启生效。
                ensureOwnPluginsAssembled()
                val restarted = sandboxManager.restartDsh()
                if (restarted is AppResult.Failure) return@runInstanceOp AppResult.Failure(restarted.error)
            }
            AppResult.Success(Unit)
        }

    /** 删除应急实例：若应急活跃先切回主实例，再删 home（注册表守卫非活跃）。 */
    suspend fun deleteEmergencyInstance(): AppResult<Unit> =
        runInstanceOp(InstanceOpState.Kind.DELETE_EMERGENCY) {
            if (!instanceRegistry.emergencyExists()) return@runInstanceOp AppResult.Success(Unit)
            if (instanceRegistry.active.value == DshInstance.EMERGENCY) {
                when (val r = switchInstanceInternal(DshInstance.MAIN)) {
                    is AppResult.Failure -> return@runInstanceOp r
                    else -> Unit
                }
            }
            // 先解锁应急 home 固化资产（bin 0555）再删，否则只读文件 EACCES 致半毁。
            setDshboxAssetsWritable(true, DshInstance.EMERGENCY)
            try {
                instanceRegistry.deleteEmergencyHome()
            } finally {
                // 删除成功则 home 已不存在（harden 空操作）；失败则回固化保护残留资产。
                hardenDshboxAssets(DshInstance.EMERGENCY)
            }
        }

    /** 层修复：回滚到上一版本（previous/dsh）。 */
    suspend fun repairLayerRollback(): AppResult<Unit> =
        runInstanceOp(InstanceOpState.Kind.REPAIR_LAYER) {
            when (val r = sandboxManager.rollbackDshLayer()) {
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> AppResult.Failure(r.error)
            }
        }

    /** 层修复：恢复随包基线（强制覆盖，可降/平/升；语义由界面按 Versions.compare 提示）。 */
    suspend fun repairLayerRestoreBaseline(): AppResult<Unit> =
        runInstanceOp(InstanceOpState.Kind.REPAIR_LAYER) {
            when (val r = provisionBundledDsh(allowDowngrade = true)) {
                null -> AppResult.Failure(AppError("DSH_NO_BUNDLED", "no bundled DSH baseline in this APK"))
                is AppResult.Success -> AppResult.Success(Unit)
                is AppResult.Failure -> AppResult.Failure(r.error)
            }
        }

    /**
     * 重置主实例：备份或删除主 home → 切回主实例 → 重新暂存资产 → 首启自建 profile →
     * 按开关装配 → 重启生效。仅删 `user-data/.dsh`，不碰用户项目文件与运行层版本。
     */
    suspend fun resetMainInstance(backup: Boolean): AppResult<Unit> =
        runInstanceOp(InstanceOpState.Kind.RESET_MAIN) {
            val active = instanceRegistry.active.value
            val sandboxWasRunning = sandboxManager.sandboxState.value == SandboxState.RUNNING
            // 主实例在运行才停它；应急活跃时主 home 未被占用，无需停应急。
            if (active == DshInstance.MAIN) sandboxManager.stopDsh()
            // 先解锁主 home 资产再删：bin 目录固化 0555，未解锁直接删会 EACCES 半毁 home。
            setDshboxAssetsWritable(true, DshInstance.MAIN)
            val reset = instanceRegistry.resetMainHome(backup)
            if (reset is AppResult.Failure) {
                hardenDshboxAssets(DshInstance.MAIN)
                return@runInstanceOp reset
            }
            // 重置成功才切回主实例（应急活跃时在此停应急），失败则保持原槽位。
            if (active != DshInstance.MAIN) sandboxManager.stopDsh()
            try {
                instanceRegistry.switchTo(DshInstance.MAIN)
                pluginAssemblyMutex.withLock {
                    provisionMobileAdaptPlugin()
                    provisionMobilePilotPlugin()
                    provisionBrowserUsePlugin()
                    provisionDshCliShim()
                }
                OfficialPluginOverlay.refresh(applicationContext, DshInstance.MAIN)
            } finally {
                hardenDshboxAssets(DshInstance.MAIN)
            }
            if (sandboxWasRunning) {
                val started = sandboxManager.startDsh()
                if (started is AppResult.Failure) return@runInstanceOp AppResult.Failure(started.error)
                ensureOwnPluginsAssembled()
                val restarted = sandboxManager.restartDsh()
                if (restarted is AppResult.Failure) return@runInstanceOp AppResult.Failure(restarted.error)
            }
            AppResult.Success(Unit)
        }

    /**
     * 还原指定备份到主实例（复制模式）：空间预检 → 自动备份现状 → 复制选中备份为
     * `.dsh`（原备份保留）→ 切回主实例 → 重新暂存装配 → 重启。原备份不删除。
     */
    suspend fun restoreBackupInstance(name: String): AppResult<Unit> =
        runInstanceOp(InstanceOpState.Kind.RESTORE_BACKUP) {
            val active = instanceRegistry.active.value
            val sandboxWasRunning = sandboxManager.sandboxState.value == SandboxState.RUNNING
            val entry = instanceRegistry.listBackups().firstOrNull { it.name == name }
                ?: return@runInstanceOp AppResult.Failure(AppError("INSTANCE_BACKUP_INVALID", "backup not found: $name"))
            val userDataDir = (application as DshApp).container.sandboxConfig.userDataDir
            val free = runCatching { android.os.StatFs(userDataDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
            if (free < entry.sizeBytes) {
                return@runInstanceOp AppResult.Failure(
                    AppError("INSTANCE_RESTORE_NO_SPACE", "insufficient space: need ${entry.sizeBytes}, free $free"),
                )
            }
            if (active == DshInstance.MAIN) sandboxManager.stopDsh()
            val restored = instanceRegistry.restoreBackup(name)
            if (restored is AppResult.Failure) return@runInstanceOp restored
            if (active != DshInstance.MAIN) sandboxManager.stopDsh()
            setDshboxAssetsWritable(true, DshInstance.MAIN)
            try {
                instanceRegistry.switchTo(DshInstance.MAIN)
                pluginAssemblyMutex.withLock {
                    provisionMobileAdaptPlugin()
                    provisionMobilePilotPlugin()
                    provisionBrowserUsePlugin()
                    provisionDshCliShim()
                }
                OfficialPluginOverlay.refresh(applicationContext, DshInstance.MAIN)
            } finally {
                hardenDshboxAssets(DshInstance.MAIN)
            }
            if (sandboxWasRunning) {
                val started = sandboxManager.startDsh()
                if (started is AppResult.Failure) return@runInstanceOp AppResult.Failure(started.error)
                ensureOwnPluginsAssembled()
                val restarted = sandboxManager.restartDsh()
                if (restarted is AppResult.Failure) return@runInstanceOp AppResult.Failure(restarted.error)
            }
            AppResult.Success(Unit)
        }

    /** 删除指定备份（逐项）。不影响运行中的 DSH；红线守卫在注册表内。 */
    suspend fun deleteBackupInstance(name: String): AppResult<Unit> =
        runInstanceOp(InstanceOpState.Kind.DELETE_BACKUP) {
            instanceRegistry.deleteBackup(name)
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SANDBOX -> serviceScope.launch { startSandbox() }
            ACTION_STOP_SANDBOX -> serviceScope.launch { stopSandbox() }
            ACTION_RESTART_SANDBOX -> serviceScope.launch { restartSandbox() }
            ACTION_START_DSH -> serviceScope.launch { startDsh() }
            // 通知栏「启动」：沙箱停止时先启动沙箱，再启动 DSH（组合动作只服务
            // 通知入口，不改变其它调用方「DSH 需要沙箱先运行」的既有契约）。
            ACTION_START_ALL -> serviceScope.launch { startSandboxAndDsh() }
            ACTION_RESTART_DSH -> serviceScope.launch { restartDsh() }
            ACTION_STOP_DSH -> serviceScope.launch { stopDsh() }
            ACTION_SWITCH_INSTANCE -> {
                val target = DshInstance.fromHomeLeaf(intent?.getStringExtra(EXTRA_INSTANCE))
                serviceScope.launch { switchInstance(target) }
            }
            ACTION_CREATE_EMERGENCY -> serviceScope.launch { createEmergencyInstance() }
            ACTION_DELETE_EMERGENCY -> serviceScope.launch { deleteEmergencyInstance() }
            ACTION_REPAIR_ROLLBACK -> serviceScope.launch { repairLayerRollback() }
            ACTION_REPAIR_BASELINE -> serviceScope.launch { repairLayerRestoreBaseline() }
            ACTION_RESET_MAIN -> {
                val backup = intent?.getBooleanExtra(EXTRA_BACKUP, true) ?: true
                serviceScope.launch { resetMainInstance(backup) }
            }
            ACTION_RESTORE_BACKUP -> {
                val name = intent?.getStringExtra(EXTRA_BACKUP_NAME)
                if (name != null) serviceScope.launch { restoreBackupInstance(name) }
            }
            ACTION_DELETE_BACKUP -> {
                val name = intent?.getStringExtra(EXTRA_BACKUP_NAME)
                if (name != null) serviceScope.launch { deleteBackupInstance(name) }
            }
            ACTION_STOP_ALL -> {
                serviceScope.launch {
                    terminalManager.stopAll()
                    sandboxManager.forceStop()
                    stopSelf()
                }
            }
            // 语言切换后由 MainActivity.onResume 触发，通知按新语言重建。
            ACTION_REFRESH_NOTIFICATION -> updateNotification()
            // 升级瞬间旧通知可能还挂着已移除的 action：记一笔，不静默吞掉。
            else -> if (intent?.action != null) {
                Log.w(TAG, "unknown service action: ${intent.action}")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 通知栏「启动」：沙箱停止时先启动沙箱，再启动 DSH。
     *
     * 与 [startDsh] 的区别只在入口语义：其它调用方（首页等）仍保持「DSH 需要
     * 沙箱先运行」的既有契约，不在这里改动。启动/恢复中只等它自己收敛到
     * RUNNING，不在中途重复拉起 proot；未初始化时先等 bootstrap 的 initialize()
     * 落定，避免两边并发写沙箱状态。
     */
    private suspend fun startSandboxAndDsh() {
        if (!startAllInProgress.compareAndSet(false, true)) return
        try {
            // 即时反馈放在去重之后：快速双击只提示一次、只执行一次。
            showToast(localizedContext().getString(R.string.notify_starting_all))
            when (sandboxManager.sandboxState.value) {
                SandboxState.RUNNING -> Unit
                SandboxState.UNINITIALIZED, SandboxState.INITIALIZING -> {
                    val settled = withTimeoutOrNull(SANDBOX_START_WAIT_MILLIS) {
                        sandboxManager.sandboxState.first {
                            it == SandboxState.RUNNING ||
                                it == SandboxState.STOPPED ||
                                it == SandboxState.ERROR
                        }
                    }
                    if (settled == null) {
                        Log.w(TAG, "startSandboxAndDsh: initialize wait timed out")
                        return
                    }
                    if (sandboxManager.sandboxState.value != SandboxState.RUNNING) {
                        startSandbox()
                    }
                }
                SandboxState.STARTING -> {
                    // 启动中：等它自己收敛；STOPPED/ERROR 说明本次启动失败，
                    // 由本动作再拉一次，避免固定空等 30 秒。
                    val settled = withTimeoutOrNull(SANDBOX_START_WAIT_MILLIS) {
                        sandboxManager.sandboxState.first {
                            it == SandboxState.RUNNING ||
                                it == SandboxState.STOPPED ||
                                it == SandboxState.ERROR
                        }
                    }
                    if (settled == null) {
                        Log.w(TAG, "startSandboxAndDsh: start wait timed out")
                        return
                    }
                    if (sandboxManager.sandboxState.value != SandboxState.RUNNING) {
                        startSandbox()
                    }
                }
                SandboxState.RECOVERING -> {
                    // 恢复链路会经过中间 STOPPED 再到 STARTING，不能按中间态
                    // 提前决策，只等最终 RUNNING。
                    val running = withTimeoutOrNull(SANDBOX_START_WAIT_MILLIS) {
                        sandboxManager.sandboxState.first { it == SandboxState.RUNNING }
                    }
                    if (running == null) {
                        Log.w(TAG, "startSandboxAndDsh: recovery did not reach RUNNING")
                        return
                    }
                }
                else -> startSandbox()
            }
            if (sandboxManager.sandboxState.value == SandboxState.RUNNING) {
                startDsh()
            } else {
                Log.w(TAG, "startSandboxAndDsh: sandbox not running after start attempt")
            }
        } finally {
            startAllInProgress.set(false)
        }
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
            // 暂存内容重写与装配互斥：READY 触发的装配可能与本次重写并发，
            // 那时装配会从写了一半的暂存树里推导指纹（见 stagedPluginFiles）。
            // 两侧的 chmod（解只读 / 固化）只改模式、不删文件，在锁外无碍。
            pluginAssemblyMutex.withLock {
                provisionMobileAdaptPlugin()
                // 应急槽只暂存移动端适配包；pilot/browser-use 不进应急 home。
                if (instanceRegistry.active.value == DshInstance.MAIN) {
                    provisionMobilePilotPlugin()
                    provisionBrowserUsePlugin()
                }
            }
            provisionDshLinkShim()
            provisionDshCliShim()
            provisionXdgOpenShim()
            provisionCorepackCache()
        } finally {
            hardenDshboxAssets()
        }
        provisionPnpmRegistryProbe()
        removeLegacyAssetDirs()
        removeRetiredPilotOverlay()
        migrateDshboxAssetLayout()
        // 开发者模式：开关处于开启态时，运行环境层被替换后需按需补装 ADB 资产。
        // 失败不阻断启动（附加能力，其资产缺失不应影响 DSH 与终端可用性）。
        com.dshbox.app.runtime.DeveloperAccessBootstrap(
            applicationContext,
            sandboxManager,
            ((application as DshApp).container.sandboxConfig).runtimeDir,
        ).ensureAdbReady()
        // 开发者模式面板的控制器装配（面板由手机助手界面导航进入）。
        runCatching {
            com.dshbox.app.runtime.DeveloperAccessBootstrap(
                applicationContext,
                sandboxManager,
                ((application as DshApp).container.sandboxConfig).runtimeDir,
            ).installController()
        }.onFailure { Log.w(TAG, "developer access controller install failed", it) }
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
            // 自有插件包按开关对齐 profile。放在 startDsh 之前：装好当次启动即加载，
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

    private suspend fun provisionBrowserUsePlugin() {
        browserStageProvisioned = BrowserAdapterAssemblyGate.withLock {
            withContext(Dispatchers.IO) {
                val parent = pluginsStageRoot()
                val stageDir = File(parent, "browser-use")
                val staged = File(parent, ".browser-use-stage-${UUID.randomUUID()}")
                val backup = File(parent, ".browser-use-backup-${UUID.randomUUID()}")
                try {
                    check(parent.mkdirs() || parent.isDirectory) { "Browser stage parent is unavailable" }
                    check(staged.mkdir()) { "Could not create Browser stage transaction directory" }
                    copyAssetTree("plugins/browser-use", staged)
                    check(File(staged, "install.sh").isFile && File(staged, "uninstall.sh").isFile &&
                        File(staged, "plugin/browser-use-install.json").isFile) { "Browser adapter assets are incomplete" }
                    if (stageDir.exists()) {
                        check(stageDir.isDirectory && !java.nio.file.Files.isSymbolicLink(stageDir.toPath())) {
                            "Browser stage path is not a regular owned directory"
                        }
                        check(readBrowserStageOwner(File(stageDir, "plugin/browser-use-install.json")) ||
                            readBrowserStageOwner(File(stageDir, "plugin/.browser-use-install.json"))) {
                            "Browser stage is not owned by this adapter"
                        }
                        check(stageDir.renameTo(backup)) { "Could not preserve the previous Browser stage" }
                    }
                    if (!staged.renameTo(stageDir)) {
                        if (backup.exists()) backup.renameTo(stageDir)
                        error("Could not publish Browser stage assets")
                    }
                    backup.deleteRecursively()
                    true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    staged.deleteRecursively()
                    if (backup.exists() && !stageDir.exists()) backup.renameTo(stageDir)
                    throw cancelled
                } catch (t: Throwable) {
                    staged.deleteRecursively()
                    if (backup.exists() && !stageDir.exists()) backup.renameTo(stageDir)
                    Log.w(TAG, "bootstrap: Browser adapter staging failed: ${t.message}")
                    false
                }
            }
        }
    }

    /**
     * 确保自有适配包在 dsh 启动前**装配到位**。
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
        pluginAssemblyMutex.withLock {
            val instance = instanceRegistry.active.value
            if (instance == DshInstance.EMERGENCY) {
                // 应急槽固定装配清单：不读主实例全局开关，失败不写主实例错误位。
                for (label in InstancePolicy.EMERGENCY_ASSEMBLY) {
                    when (label) {
                        "mobile-adapt" -> ensureOwnPlugin(
                            label = label,
                            bundleId = "@local/dsh-mobile-adapt",
                            intentKey = null,
                            errorKey = null,
                            stageName = "mobile-adapt",
                            instance = instance,
                        )
                    }
                }
                return@withLock
            }
            ensureOwnPlugin(
                label = "mobile-adapt",
                bundleId = "@local/dsh-mobile-adapt",
                intentKey = Constants.PREF_MOBILE_ADAPT_INSTALLED,
                errorKey = Constants.PREF_MOBILE_ADAPT_LAST_ERROR,
                stageName = "mobile-adapt",
                instance = instance,
            )
            ensureOwnPlugin(
                label = "mobile-pilot",
                bundleId = "@local/mobile-pilot",
                intentKey = Constants.PREF_MOBILE_PILOT_ENABLED,
                errorKey = Constants.PREF_MOBILE_PILOT_LAST_ERROR,
                stageName = "mobile-pilot",
                instance = instance,
            )
            BrowserAdapterAssemblyGate.withLock {
                if (!browserStageProvisioned) {
                    val error = "Browser adapter assets could not be staged; will retry on next startup"
                    prefs.edit().putString(Constants.PREF_BROWSER_PLUGIN_LAST_ERROR, error).apply()
                    Log.w(TAG, "bootstrap: Browser adapter stage is unavailable; leaving profile untouched")
                    return@withLock
                }
                ensureOwnPlugin(
                    label = "browser-use",
                    bundleId = "@local/browser-use",
                    intentKey = Constants.PREF_BROWSER_PLUGIN_ENABLED,
                    errorKey = Constants.PREF_BROWSER_PLUGIN_LAST_ERROR,
                    stageName = "browser-use",
                    instance = instance,
                )
            }
        }
    }

    /**
     * 单个自有插件包与 profile 对齐：开关为关则不动；为开则按探测结论决定是否跑 `install.sh`。
     *
     * **失败不动开关**：开关表达的是「用户要不要这个包」，装配失败（guest 异常、脚本报错等）
     * 只把原因写进 [errorKey]，下一次对齐（启动期 / DSH 就绪）自动重试。反过来做 ——
     * 失败就把开关落成「关」—— 会让一次瞬时故障变成永久关闭：此后对齐在开关处提前返回，
     * 用户再也等不到补装，而面板还看不出这是自动关的（mobile-pilot 没有错误展示位）。
     *
     * @param intentKey 「用户要不要这个包」的偏好键；null 表示固定装配（应急槽），不读开关
     * @param errorKey 失败原因落盘的位置（面板读取展示）；null 表示不落盘（应急槽不写主实例错误位）
     * @param stageName 随包资产在 guest 暂存目录下的子目录名
     * @param instance 目标实例 home；缺省主实例
     */
    private suspend fun ensureOwnPlugin(
        label: String,
        bundleId: String,
        intentKey: String?,
        errorKey: String?,
        stageName: String,
        instance: DshInstance = DshInstance.MAIN,
    ) {
        // 键没写过时按"默认开启"处理；intentKey 为 null 表示固定装配（应急槽），不读开关。
        if (intentKey != null && !prefs.getBoolean(intentKey, true)) {
            Log.i(TAG, "bootstrap: $label disabled; leaving profile untouched")
            return
        }
        val profile = Constants.dshWebProfileGuestDir(instance)
        val pluginDir = "$profile/node_modules/$bundleId"
        val stage = "${Constants.dshboxPluginsGuestDir(instance)}/$stageName"

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
        // 指纹只覆盖 `plugin/` 内容、不含 install.sh，所以老 profile 可能"看起来已装配"
        // 却仍是游离包（只写了 bundles，没写 dependencies）。这种状态下任何一次 pnpm
        // 安装都会把目录当孤儿清掉，随后启动解析失败。因此声明缺失时**不能 SKIP**，
        // 要补一次装配让 install.sh 写入 file: 依赖。
        if (OwnPluginProbe.actionFor(marker) == OwnPluginProbe.Action.SKIP &&
            profileDeclaresBundleDependency(bundleId, instance)
        ) {
            Log.i(TAG, "bootstrap: $label not assembled (marker=${marker ?: "none"}); leaving profile untouched")
            if (label == "browser-use" && marker == OwnPluginProbe.MARKER_UP_TO_DATE && errorKey != null) {
                prefs.edit().remove(errorKey).apply()
            }
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
                if (errorKey != null) prefs.edit().remove(errorKey).apply()
            }
            is AppResult.Failure -> {
                Log.w(TAG, "bootstrap: $label assembly failed: ${res.error.message}")
                if (errorKey != null) {
                    prefs.edit().putString(errorKey, res.error.message.ifBlank { res.error.code }).apply()
                }
            }
        }
    }

    /**
     * profile 的 package.json 是否已把 [bundleId] 声明为 `file:` 依赖。
     *
     * 只写 `dsh.profile.bundles` 不足以让 pnpm 认它：pnpm 每次按「manifest +
     * lockfile」重建 node_modules 时，未声明为依赖的目录属于游离包会被清理，而
     * bundles 里的条目会被当作模板条目保留——结果就是"清单里还写着、磁盘上已经没了"，
     * 启动时 `cannot resolve profile bundle`。
     *
     * 这里只做最轻的文本检查（不解析 JSON），仅用于决定是否需要补一次装配；真正
     * 写入依赖声明的是插件包自己的 `install.sh`。
     */
    private fun profileDeclaresBundleDependency(bundleId: String, instance: DshInstance): Boolean {
        val config = (application as DshApp).container.sandboxConfig
        val relative = Constants.dshWebProfileGuestDir(instance)
            .removePrefix(Constants.SANDBOX_WORKSPACE)
            .trimStart('/')
        val manifest = File(config.userDataDir, "$relative/package.json")
        val text = runCatching { manifest.readText() }.getOrNull() ?: return false
        return Regex("\"${Regex.escape(bundleId)}\"\\s*:\\s*\"file:").containsMatchIn(text)
    }

    private fun readBrowserStageOwner(file: File): Boolean = runCatching {
        val path = file.toPath()
        val parent = java.nio.file.Files.readAttributes(
            path.parent,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        check(parent.isDirectory && !parent.isSymbolicLink)
        val before = java.nio.file.Files.readAttributes(
            path,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        )
        check(before.isRegularFile && !before.isSymbolicLink)
        java.nio.file.Files.newByteChannel(
            path,
            setOf(java.nio.file.StandardOpenOption.READ, java.nio.file.LinkOption.NOFOLLOW_LINKS),
        ).use { channel ->
            val opened = java.nio.file.Files.readAttributes(
                path,
                java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS,
            )
            check(before.fileKey() != null && opened.isRegularFile && opened.fileKey() == before.fileKey())
            java.nio.channels.Channels.newInputStream(channel).reader().buffered().use { markerReader ->
                android.util.JsonReader(markerReader).use { reader ->
                    var packageName: String? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        if (reader.nextName() == "package") packageName = reader.nextString() else reader.skipValue()
                    }
                    reader.endObject()
                    packageName == "@local/browser-use"
                }
            }
        }
    }.getOrDefault(false)

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
            val binDir = File(
                File(config.userDataDir, Constants.dshboxAssetsRelativeDir(instanceRegistry.active.value)),
                "bin",
            )
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
     * Stage the **xdg-open shim** into [com.dshbox.app.common.Constants.DSH_LINK_SHIM_GUEST_DIR]
     * (the same host dir bound at guest `/opt/dshbox`).
     *
     * DSH 的「打开配置文件」服务器端最终调用 `xdg-open <path>`（openNativeTextFile 的
     * Linux 分支）。Android guest 没有桌面环境，base 层不含 xdg-open，原生 RPC 无法
     * 完成。垫片把目标路径转写成请求文件交给宿主 APP，由内置查看器打开；不修改
     * DSH 源码，System 与 Gecko 两种渲染器行为一致。
     *
     * 每次 bootstrap 覆写一次（与 link-shim 同策略）。失败不阻断 bootstrap：
     * 最坏情况只是该功能回到「服务器报错」，不影响其它链路。
     */
    private suspend fun provisionXdgOpenShim() = withContext(Dispatchers.IO) {
        val config = (application as DshApp).container.sandboxConfig
        try {
            config.dshShimDir.mkdirs()
            val shim = File(config.dshShimDir, Constants.XDG_OPEN_SHIM_NAME)
            applicationContext.assets.open("dshbox/xdg-open").use { input ->
                shim.outputStream().use { output -> input.copyTo(output) }
            }
            shim.setExecutable(true, false)
            Log.i(TAG, "bootstrap: xdg-open shim staged to ${shim.absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap: provision xdg-open shim failed: ${t.message}")
        }
    }

    /**
     * 把 APK 内置的 corepack 缓存（离线可用的 pnpm）释放到「工具链缓存」挂载点。
     *
     * guest 里的 `pnpm` 命令是 Node 自带 corepack 的包装（见 [PNPM_CLI_SHIM]），
     * corepack 在 `COREPACK_HOME` 下按 `v1/<name>/<version>` 组织缓存。随包只带
     * corepack 本体、不带任何 pnpm 版本——首次执行 `corepack pnpm` 会联网下载，
     * 受限网络下首装即失败。这里把构建期 `corepack prepare pnpm@11.1.2 --activate`
     * 的产物（版本与本树 node 层 corepack 的 LKG 一致，零迁移）释放到位，离线即可命中。
     *
     * 幂等：就绪标记内容与资产 SHA-256 一致 → 跳过；缺失或不符 → 重建。
     * 展开前先做**完整性预检**（资产 sha256 对账）并在全量展开**成功后**才写标记，
     * 失败（解析错位、写盘中断）不留标记，下次启动自动重建——不存在"半成品被
     * 标记锁死"的形态。失败只记录、不抛出——缓存缺席时 corepack 退回联网下载，
     * 行为不劣于改造前。
     */
    private suspend fun provisionCorepackCache() = withContext(Dispatchers.IO) {
        val config = (application as DshApp).container.sandboxConfig
        val cacheDir = File(config.guestCacheDir, Constants.COREPACK_CACHE_DIR_LEAF)
        val marker = File(cacheDir, Constants.COREPACK_CACHE_READY_MARKER)
        try {
            val expected = applicationContext.assets
                .open(Constants.TOOLCHAIN_CACHE_ASSET_DIR + "/" +
                    Constants.COREPACK_CACHE_ASSET_NAME + ".sha256")
                .bufferedReader().use { it.readText().trim().substringBefore(' ') }
            if (cacheDir.isDirectory && marker.isFile && marker.readText().trim() == expected) {
                Log.i(TAG, "bootstrap: corepack cache up to date")
                return@withContext
            }
            // 就绪标记是**写入时不变量**：写入成功 = 当时装入的内容与资产 sha256
            // 一致。检查时只比对标记字符串，不重哈希目录现内容——guest 侧
            // （可写 bind）事后篡改/删除 corepack 下文件不会被检测，属接受的
            // 设计属性。改代码/换资产都会因
            // 期望值变化而失效，绝不继承旧标记——坏状态不能跨版本固化。
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
            val staged = File(config.guestCacheDir, "corepack-stage-" + System.currentTimeMillis())
            // 暂存目录必须显式创建：FileOutputStream 不建父目录，父目录缺失会在
            // 拷贝资产时抛 FileNotFoundException，整个释放路径静默失败（历史缺陷）。
            check(staged.mkdirs() || staged.isDirectory) { "corepack stage dir unavailable" }
            try {
                val tarball = File(staged, Constants.COREPACK_CACHE_ASSET_NAME)
                applicationContext.assets
                    .open(Constants.TOOLCHAIN_CACHE_ASSET_DIR + "/" + Constants.COREPACK_CACHE_ASSET_NAME)
                    .use { input -> tarball.outputStream().use { output -> input.copyTo(output) } }
                // 完整性预检：落盘的 tarball 必须与登记 sha256 一致，否则不展开。
                val actual = sha256Of(tarball)
                if (actual != expected) {
                    Log.w(TAG, "bootstrap: corepack cache asset sha256 mismatch; corepack will fetch on demand")
                    return@withContext
                }
                if (!inflateTarZst(tarball, cacheDir)) {
                    Log.w(TAG, "bootstrap: corepack cache inflate failed; corepack will fetch on demand")
                    return@withContext
                }
                marker.writeText(expected)
                Log.i(TAG, "bootstrap: corepack cache provisioned")
            } finally {
                staged.deleteRecursively()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap: provision corepack cache failed: ${t.message}")
        }
    }

    /**
     * pnpm 镜像源的启动期探测（只在覆盖文件缺失或为空时运行一次）。
     *
     * 双源竞速：官方 registry 与国内镜像同时请求 `pnpm/latest`，谁先返回**完整**
     * 的 200 响应体（可解析出 "version"）谁胜——只看响应头会在 captive portal 的
     * 半截响应上误选。超时或双败一律不写：缺席即官方直连，不把探测失败变成镜像钦定。
     *
     * 文件一经写入长期有效（镜像日后失效不会自动回退——自动覆盖会让"用户手改"
     * 与"探测重写"打架；恢复手段是删除文件，删除即触发下次启动重新探测）。
     * 已有内容时本函数整体跳过，绝不覆盖。
     */
    private suspend fun provisionPnpmRegistryProbe() = withContext(Dispatchers.IO) {
        val config = (application as DshApp).container.sandboxConfig
        val file = File(config.guestCacheDir, Constants.PNPM_REGISTRY_FILE_LEAF)
        if (file.isFile && file.readText().trim().isNotEmpty()) return@withContext
        try {
            val winner = raceFirstComplete(
                listOf(
                    Constants.PNPM_OFFICIAL_REGISTRY_BASE to
                        Constants.PNPM_OFFICIAL_REGISTRY_BASE + "/pnpm/latest",
                    Constants.PNPM_CHINA_REGISTRY_BASE to
                        Constants.PNPM_CHINA_REGISTRY_BASE + "/pnpm/latest",
                ),
            )
            if (winner == null || winner == Constants.PNPM_OFFICIAL_REGISTRY_BASE) {
                Log.i(TAG, "bootstrap: pnpm registry probe chose official (or none answered); no override")
                return@withContext
            }
            config.guestCacheDir.mkdirs()
            val tmp = File(config.guestCacheDir, Constants.PNPM_REGISTRY_FILE_LEAF + ".tmp")
            tmp.writeText(winner + "\n")
            if (tmp.renameTo(file)) {
                Log.i(TAG, "bootstrap: pnpm registry probe recorded a mirror")
            } else {
                Log.w(TAG, "bootstrap: pnpm registry probe rename failed; official stays in effect")
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "bootstrap: pnpm registry probe failed: ${t.message}")
        }
    }

    /**
     * 并发请求多个 registry 基址的同一小文档，返回第一个完整成功者的基址。
     *
     * "完整" = HTTP 200 且响应体全部读毕且能解析出 "version" 字段。全员失败或
     * 预算耗尽返回 null。响应体必须读完：镜像站可能先吐响应头再挂起，凭头判定
     * 会把一次半途而废的请求当成可用源。
     *
     * ## 预算是"真总预算"
     *
     * [HttpURLConnection] 的 socket 读不可被协程取消抢占——结构化并发在超时后
     * 仍要 join 阻塞的子协程，慢滴服务器可把 bootstrap 拖到无界。而看门狗若与
     * `withTimeoutOrNull` 同用预算、同受其取消，它的触发点只会落在超时之后
     * （死代码）。因此：
     *  - **看门狗独立于超时作用域**：挂在不受取消的服务级 scope 上，落点严格
     *    早于超时（预算减 250ms 余量），到点 `disconnect()` 全部在册连接——它
     *    会使阻塞中的 socket 读立即抛出，阻塞的子协程随即收尾，`withTimeoutOrNull`
     *    得以在其超时点附近真正返回；
     *  - **胜者即断其余**：`winner.complete()` 后立刻断开其余在册连接，成功路径
     *    不为落败源的超时付费；
     *  - **残留清扫**：看门狗结束时顺带清理进程被杀留下的 `corepack-stage-*`
     *    半成品目录（正常路径 stage 由 finally 即时删除）；
     *  - 单源 `openConnection()` 抛异常也只削弱该源（在各自 try 内），不取消兄弟源。
     */
    private suspend fun raceFirstComplete(candidates: List<Pair<String, String>>): String? {
        // 看门狗的宿主：独立于 withTimeoutOrNull 的取消作用域——超时取消只在
        // 其自身块的 job 树内传播，触不到这里；看门狗与函数返回各走各的生命期，
        // 三条返回路径（胜出/超时/异常）都以显式 cancel 收尾，无协程泄漏。
        val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
        // 落点留 250ms 余量：严格早于 withTimeoutOrNull 的超时点，断开动作有
        // 时间窗先把阻塞读解除，超时才不至于在 join 阻塞子协程上悬空。
        val deadline = System.currentTimeMillis() +
            Constants.PNPM_REGISTRY_PROBE_BUDGET_MS - Constants.PNPM_REGISTRY_WATCHDOG_LEAD_MS
        val watchdog = watchdogScope.launch {
            val remain = deadline - System.currentTimeMillis()
            if (remain > 0) kotlinx.coroutines.delay(remain)
            connections.forEach { runCatching { it.disconnect() } }
            // 顺带清掉历史运行残留的暂存目录（进程被杀时拷贝中途会留下）。
            val config = (application as DshApp).container.sandboxConfig
            config.guestCacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("corepack-stage-") }
                ?.forEach { runCatching { it.deleteRecursively() } }
        }
        return try {
            withTimeoutOrNull(Constants.PNPM_REGISTRY_PROBE_BUDGET_MS.toLong()) {
                coroutineScope {
                    val winner = CompletableDeferred<String>()
                    for ((base, url) in candidates) {
                        launch(Dispatchers.IO) {
                            val conn = try {
                                (URL(url).openConnection() as HttpURLConnection).also { connections += it }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                return@launch
                            }
                            try {
                                conn.connectTimeout = Constants.PNPM_REGISTRY_PROBE_BUDGET_MS
                                conn.readTimeout = Constants.PNPM_REGISTRY_PROBE_BUDGET_MS
                                if (System.currentTimeMillis() >= deadline) return@launch
                                if (conn.responseCode != 200) return@launch
                                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                                if (System.currentTimeMillis() >= deadline) return@launch
                                if (JSONObject(body).optString("version").isNotBlank() && winner.complete(base)) {
                                    // 胜者既定：断开其余连接，落败源不再拖住作用域收尾。
                                    connections.forEach { runCatching { it.disconnect() } }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                // 单源失败（含 disconnect 触发的中断）只削弱该源。
                            } finally {
                                connections -= conn
                                conn.disconnect()
                            }
                        }
                    }
                    val result = winner.await()
                    // 胜者既定后补一次清扫（防御：晚注册的连接不经过胜者 sweep）。
                    connections.forEach { runCatching { it.disconnect() } }
                    result
                }
            }.also {
                // 胜出与超时两条路径都从这里离开：`.also` 对可空结果同样执行。
                // 超时返回 null 时阻塞读已被看门狗先一步断开（落点严格早于
                // 超时点），阻塞子协程随即收尾；这里显式停掉看门狗兜底止损。
                watchdog.cancel()
                watchdogScope.cancel()
            }
        } catch (t: Throwable) {
            watchdog.cancel()
            watchdogScope.cancel()
            throw t
        }
    }

    /** 计算文件 SHA-256（十六进制小写），失败返回 null。 */
    private fun sha256Of(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun inflateTarZst(tarball: File, dest: File): Boolean =
        CorepackCacheUnpacker.unpack(tarball, dest)

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

    /** 我方随包资产目录（`user-data/<实例 home>/dshbox`）；缺省活跃实例。 */
    private fun dshboxAssetsDir(instance: DshInstance = instanceRegistry.active.value): File =
        File(
            (application as DshApp).container.sandboxConfig.userDataDir,
            Constants.dshboxAssetsRelativeDir(instance),
        )

    /**
     * 暂存包里参与一致性比对的文件（相对 `plugin/` 根，已排序）。
     *
     * 由**暂存包的实际内容**推导，而不是写死一份清单：各适配包的组成并不相同
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
     * 把 `.dsh/dshbox`（我方随包资产：dsh/pnpm 包装脚本、自有插件包的装配暂存）
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
     *   **在下次启动就会被还原**；自有插件包还有指纹比对（OwnPluginProbe）兜底。
     *   也就是说：这是"我们拥有 + 自动还原"，不是"用户改不动"。
     *   若哪天真要做到不可改，只能把母本放到工作区之外并让 guest 只读地看 —— 代价是
     *   用户在文件页里根本看不到这个目录。
     */
    private fun hardenDshboxAssets(instance: DshInstance = instanceRegistry.active.value) =
        chmodDshboxAssets(writable = false, instance = instance)

    /** 临时解锁（重写资产前调用）；写完必须调 [hardenDshboxAssets] 固化回去。 */
    private fun setDshboxAssetsWritable(
        writable: Boolean,
        instance: DshInstance = instanceRegistry.active.value,
    ) = chmodDshboxAssets(writable = writable, instance = instance)

    private fun chmodDshboxAssets(
        writable: Boolean,
        instance: DshInstance = instanceRegistry.active.value,
    ) {
        val root = dshboxAssetsDir(instance)
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

    private suspend fun provisionBundledDsh(allowDowngrade: Boolean = false): AppResult<DshUpdateOutcome>? {
        // DshBundler: provision the APK-bundled DSH baseline (assets/dsh/
        // <version>.tar.gz [+ .sha256]) into runtime-current/dsh with version
        // arbitration (installed-newer wins). No-op when the APK carries no DSH
        // baseline (e.g. a dev build that ships DSH separately).
        val assetManager = applicationContext.assets
        val entries = runCatching { assetManager.list("dsh") }.getOrNull() ?: return null
        // zstd baseline (preferred) or a gzip fallback; DshLayer/extractTarGz sniffs
        // the actual compression from the stream magic, not the extension.
        val dshTarball = entries.firstOrNull { it.endsWith(".tar.zst") }
            ?: entries.firstOrNull { it.endsWith(".tar.gz") } ?: return null
        // strip the build-side "-patched" marker from the derived version.
        // The asset is named e.g. "0.1.1-rc.2-patched.tar.zst" but the RELEASE it packs is
        // With the suffix left in, the arbitration below treats a legitimately
        // offline-imported "0.1.1-rc.2" (or a version discovered as "unknown") as OLDER and
        // silently re-provisioned the bundled layer over it on every boot — offline imports
        // appeared to "not stick". Removing the marker makes the comparison exact; existing
        // installs that already recorded "...-patched" still compare as newer (kept, no churn).
        val version = dshTarball.removeSuffix(".tar.zst").removeSuffix(".tar.gz").removeSuffix("-patched")
        val out = File(cacheDir, "dsh-bundled-$version.tar")
        return try {
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
            val result = sandboxManager.updateDsh(out, sha, version, allowDowngrade)
            when (result) {
                is AppResult.Success ->
                    if (result.value.changed) Log.i(TAG, "bundled DSH ${result.value.version} installed")
                    else Log.i(TAG, "bundled DSH kept (already at ${result.value.version})")
                is AppResult.Failure ->
                    Log.w(TAG, "bundled DSH provision failed: ${result.error.message}")
            }
            result
        } catch (t: Throwable) {
            Log.w(TAG, "bundled DSH read failed: ${t.message}")
            AppResult.Failure(AppError("DSH_BUNDLED_READ_FAILED", "bundled DSH read failed: ${t.message}", t))
        } finally {
            out.delete()
        }
    }

    private suspend fun startSandbox() {
        // 助手通道必须在 DSH 起来之前就绪：guest 侧把它当成 /opt/pilot，
        // 通道不在时每一次调用都会等到超时。
        RelayRuntime.start(applicationContext, dshboxRelayConfig(applicationContext))
        ensureDeveloperAccessReady()
        sandboxManager.startSandbox()
    }

    /**
     * 沙箱启动前的开发者模式复检。
     *
     * 运行环境层可能在开关开启之后被整体替换（在线获取/离线导入），换层会带走 adb 与
     * 依赖，而开关状态（wrapper 在位）不变。复检只在开关开启时做实际工作：未开启直接返回，
     * 已安装则是一次文件存在性检查，不产生 guest 进程。
     */
    private suspend fun ensureDeveloperAccessReady() {
        runCatching {
            com.dshbox.app.runtime.DeveloperAccessBootstrap(
                applicationContext,
                sandboxManager,
                ((application as DshApp).container.sandboxConfig).runtimeDir,
            ).ensureAdbReady()
        }.onFailure { Log.w(TAG, "developer access recheck before sandbox start failed", it) }
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
            ensureDeveloperAccessReady()
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
        val instanceSuffix = if (instanceRegistry.active.value == DshInstance.EMERGENCY) {
            " · " + ctx.getString(R.string.home_instance_badge)
        } else {
            ""
        }
        val contentTitle = "$sandboxText · $dshText$instanceSuffix"
        // 运行期地址（端口自适应）：通知正文随确认地址更新，未确证时为默认常量。
        val contentText = com.dshbox.app.ui.theme.AppDshAddressState.baseUrl

        // 点通知主体回 App 首页；不再提供外部浏览器入口（action 位只留控制项）。
        val contentPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // 动作按状态给（策略见 notificationActions），最多两个：运行中
        // 「重启 / 关闭」，启动/恢复中只给「关闭」，已停止「启动」。Android
        // 标准通知模板最多渲染三个 action，固定四项会把最后一项截掉（旧版
        // 「停止沙箱」不可见的根因）。PendingIntent 按实际用到的动作创建，
        // 不预先构造全部三个。
        fun actionPending(requestCode: Int, action: String): PendingIntent =
            PendingIntent.getService(
                this,
                requestCode,
                Intent(this, SandboxService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(contentPending)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF10A37F.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        for (action in notificationActions(sandboxState)) {
            when (action) {
                NotificationAction.START -> builder.addAction(
                    0,
                    ctx.getString(R.string.notify_action_start),
                    actionPending(1, ACTION_START_ALL),
                )
                NotificationAction.RESTART -> builder.addAction(
                    0,
                    ctx.getString(R.string.notify_action_restart),
                    actionPending(2, ACTION_RESTART_DSH),
                )
                NotificationAction.STOP -> builder.addAction(
                    0,
                    ctx.getString(R.string.notify_action_stop),
                    actionPending(3, ACTION_STOP_SANDBOX),
                )
            }
        }
        return builder.build()
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
        const val ACTION_START_ALL = "com.dshbox.app.action.START_ALL"
        const val ACTION_RESTART_DSH = "com.dshbox.app.action.RESTART_DSH"
        const val ACTION_STOP_DSH = "com.dshbox.app.action.STOP_DSH"
        const val ACTION_STOP_ALL = "com.dshbox.app.action.STOP_ALL"
        const val ACTION_SWITCH_INSTANCE = "com.dshbox.app.action.SWITCH_INSTANCE"
        const val ACTION_CREATE_EMERGENCY = "com.dshbox.app.action.CREATE_EMERGENCY"
        const val ACTION_DELETE_EMERGENCY = "com.dshbox.app.action.DELETE_EMERGENCY"
        const val ACTION_REPAIR_ROLLBACK = "com.dshbox.app.action.REPAIR_ROLLBACK"
        const val ACTION_REPAIR_BASELINE = "com.dshbox.app.action.REPAIR_BASELINE"
        const val ACTION_RESET_MAIN = "com.dshbox.app.action.RESET_MAIN"
        const val ACTION_RESTORE_BACKUP = "com.dshbox.app.action.RESTORE_BACKUP"
        const val ACTION_DELETE_BACKUP = "com.dshbox.app.action.DELETE_BACKUP"
        const val EXTRA_INSTANCE = "instance"
        const val EXTRA_BACKUP = "backup"
        const val EXTRA_BACKUP_NAME = "backup_name"

        /** 通知栏「启动」等待沙箱从启动中收敛到 RUNNING 的上限。 */
        private const val SANDBOX_START_WAIT_MILLIS = 30_000L
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
         * `pnpm` 包装脚本。`dsh plugin …` / 插件安装接口都把参数**转发给 pnpm**
         * （在 profile 目录里装/删插件），而设备上没有全局 pnpm —— 只有 Node 自带的
         * corepack。这里用 corepack 代跑，注入三件运行环境必需的东西；registry 刻意
         * 不碰（原因见第 4 节）。
         *
         * ## 1. COREPACK_HOME → 内置缓存
         *
         * `bootstrap` 已把 pnpm 11.1.2 的 corepack 缓存释放到
         * `/opt/dshbox-cache/corepack`（见 [provisionCorepackCache]）。指过去即
         * **离线首装可用**，且**版本被钉死在 11.1.2**——不指的话 corepack 会按自己的
         * LKG 去取，实测取到过 12.x，而 12 在 profile 固定的
         * `autoInstallPeers: false` 配置下有内存暴涨问题，工作单明确排除。
         *
         * ## 2. store 位置：沿用 profile 现有记录，而不是强行换家
         *
         * pnpm 的一致性检查是**纯字符串比对**：`node_modules/.modules.yaml` 里记的
         * `storeDir` 与本次解析出的 store 不同，`add`/`remove` 就直接抛
         * `ERR_PNPM_UNEXPECTED_STORE`（`install` 动词例外，会自动重建）。因此对
         * **存量 profile 不能硬切到新位置**，否则升级用户第一次装插件就失败。
         * 这里读当前目录（DSH 调 pnpm 时 cwd 即 profile 目录）的 `.modules.yaml`：
         *  - 记录存在且目录仍在 → 沿用该 store（剥掉 `/v11` 这类版本尾巴后回填）；
         *  - 无记录（新 profile）/ 目录已不存在 → 用缓存挂载点（可被"清理缓存"回收）。
         *
         * 这样新老 profile 各自与自己的一致性记录对齐，不需要任何迁移动作。
         *
         * ## 3. 导入方式必须是 copy
         *
         * Android 上 pnpm 默认的 hardlink/clone 导入对本地目录依赖会报
         * `Permission denied (os error 13)`（真机实证）。本地插件包以 `file:` 依赖
         * 声明后，任何一次安装都会重新导入它们，所以 copy 必须全局生效。
         *
         * ## 4. 不注入 registry：源由 DSH 面板与市场判定面各自决定
         *
         * 这里**刻意不碰 registry**。原因是 DSH 的插件面板把「默认安装源」表示成
         * `registry: null`（= pnpm 自己配置的源），只有用户显式选源时才传
         * `--registry=<url>`；而 pnpm 在「源来自配置/环境变量」时请求的是**缩写
         * 元数据**（`application/vnd.npm.install-v1+json`），该格式裁掉自定义字段，
         * 安装前 `pnpm view <包> … dsh` 因此取不到 `dsh.bundle`，DSH 会判"这个包
         * 没有声明组合包"（真机实测：同包同源，`--registry=` 参数形式能拿到 `dsh`，
         * 环境变量形式拿不到；缩写响应 12~13 个 key，完整响应 33 个 key）。
         *
         * 更重要的是：垫片若在底下换源，面板上显示"npm 官方源"的那一项实际会去
         * 访问别的源，标签与实际不符。因此源的选择完全交回使用者可见的入口：
         *  - DSH 插件面板：官方源 / 中国大陆镜像源 / 自定义地址；
         *  - 本应用插件市场：自行读 `pnpm-registry` 覆盖文件后**直连 HTTP**
         *    （`Accept: application/json`，拿完整元数据），与 pnpm 无关。
         */
        private val PNPM_CLI_SHIM = """
            #!/bin/sh
            # DSHBox: `pnpm` via Node's bundled corepack (no global pnpm on device).
            #
            # 1) COREPACK_HOME → 内置 pnpm（离线可用 + 锁 11.1.2）
            # 2) store 沿用 profile 现有记录（存量零冲突），无记录才用缓存挂载点
            # 3) 导入方式 copy（Android 上 hardlink/clone 会 EACCES）
            # 4) 不注入 registry：源由 DSH 面板与市场判定面各自决定（见 KDoc）
            if [ -d /opt/dshbox-cache ]; then
              export COREPACK_HOME=/opt/dshbox-cache/corepack
            fi

            STORE=""
            if [ -f ./.modules.yaml ]; then
              RECORDED=$(sed -n 's/.*"storeDir"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' ./.modules.yaml 2>/dev/null | head -n 1)
              if [ -z "${'$'}RECORDED" ]; then
                RECORDED=$(sed -n 's/^storeDir:[[:space:]]*//p' ./.modules.yaml 2>/dev/null | head -n 1)
              fi
              if [ -n "${'$'}RECORDED" ] && [ -d "${'$'}RECORDED" ]; then
                case "${'$'}RECORDED" in
                  */v[0-9]*) STORE=${'$'}{RECORDED%/*} ;;
                  *) STORE="${'$'}RECORDED" ;;
                esac
              fi
            fi
            if [ -z "${'$'}STORE" ]; then
              if [ -d /opt/dshbox-cache ]; then
                STORE=/opt/dshbox-cache/pnpm-store
              else
                STORE=/root/projects/.dsh/pnpm-store
              fi
            fi
            export pnpm_config_store_dir="${'$'}STORE"
            export pnpm_config_package_import_method=copy
            mkdir -p "${'$'}STORE" 2>/dev/null || true

            exec /usr/local/bin/corepack pnpm "${'$'}@"
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

        fun switchInstance(context: Context, instance: DshInstance) {
            context.startService(
                Intent(context, SandboxService::class.java)
                    .setAction(ACTION_SWITCH_INSTANCE)
                    .putExtra(EXTRA_INSTANCE, instance.homeLeaf),
            )
        }

        fun createEmergency(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_CREATE_EMERGENCY),
            )
        }

        fun deleteEmergency(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_DELETE_EMERGENCY),
            )
        }

        fun repairLayerRollback(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_REPAIR_ROLLBACK),
            )
        }

        fun repairLayerRestoreBaseline(context: Context) {
            context.startService(
                Intent(context, SandboxService::class.java).setAction(ACTION_REPAIR_BASELINE),
            )
        }

        fun resetMain(context: Context, backup: Boolean) {
            context.startService(
                Intent(context, SandboxService::class.java)
                    .setAction(ACTION_RESET_MAIN)
                    .putExtra(EXTRA_BACKUP, backup),
            )
        }

        fun restoreBackup(context: Context, name: String) {
            context.startService(
                Intent(context, SandboxService::class.java)
                    .setAction(ACTION_RESTORE_BACKUP)
                    .putExtra(EXTRA_BACKUP_NAME, name),
            )
        }

        fun deleteBackup(context: Context, name: String) {
            context.startService(
                Intent(context, SandboxService::class.java)
                    .setAction(ACTION_DELETE_BACKUP)
                    .putExtra(EXTRA_BACKUP_NAME, name),
            )
        }

        /** APK 内嵌 DSH 基线版本（从 `assets/dsh/<version>.tar.zst` 文件名推导）；无内嵌基线返回 null。 */
        fun bundledDshVersion(context: Context): String? {
            val entries = runCatching { context.assets.list("dsh") }.getOrNull() ?: return null
            val tarball = entries.firstOrNull { it.endsWith(".tar.zst") }
                ?: entries.firstOrNull { it.endsWith(".tar.gz") } ?: return null
            return tarball.removeSuffix(".tar.zst").removeSuffix(".tar.gz").removeSuffix("-patched")
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
