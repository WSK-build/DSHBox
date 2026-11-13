package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.common.DshInstance
import com.dshbox.app.common.UiText
import com.dshbox.app.common.LogRedactor
import com.dshbox.app.common.coroutineFailureHandler
import com.dshbox.app.sandbox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import android.util.Log
import java.util.zip.ZipFile

/**
 * Default SandboxManager state machine.
 *
 * Sandbox (Debian/PRoot) and DSH are intentionally decoupled:
 * - [sandboxState] tracks only the Debian sandbox.
 * - [dshState] tracks only the DSH web service.
 * - They run as two independent PRoot processes sharing the same rootfs.
 *
 * The DSH health loop owns auto-restart in-place: on failure it kills and
 * re-launches the DSH PRoot WITHOUT touching the health-loop job, so no
 * self-cancellation occurs. Manual stop/start/restart (via UI) go through
 * [stopDsh]/[startDsh]/[restartDsh].
 */
class DefaultSandboxManager(
    private val config: SandboxConfig,
    configuredHealthChecker: SandboxHealthChecker = HttpHealthChecker(config.dshHost, config.dshPort),
    /**
     * 设备是否处于交互状态（唤醒且亮屏）。健康循环据此区分探测失败的性质：设备休眠
     * 时 guest 会被整体冻结，此时的失败是暂时状态，不该判为故障。
     */
    private val isDeviceInteractive: () -> Boolean = { true },
) : SandboxManager {

    private val bundleManager = BundleManager(config)
    private val processRunner = SandboxProcessRunner(config)

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + coroutineFailureHandler(TAG),
    )

    private val _sandboxState = MutableStateFlow(SandboxState.UNINITIALIZED)
    override val sandboxState: StateFlow<SandboxState> = _sandboxState.asStateFlow()

    private val _dshState = MutableStateFlow(DshState.UNINITIALIZED)
    override val dshState: StateFlow<DshState> = _dshState.asStateFlow()

    private val _dshUnresponsive = MutableStateFlow(false)
    override val dshUnresponsive: StateFlow<Boolean> = _dshUnresponsive.asStateFlow()
    private val _dshRecoverySuggested = MutableStateFlow(false)
    override val dshRecoverySuggested: StateFlow<Boolean> = _dshRecoverySuggested.asStateFlow()

    private val _dshVersion = MutableStateFlow<String?>(null)
    override val dshVersion: StateFlow<String?> = _dshVersion.asStateFlow()

    private val _dshUpdateProgress = MutableStateFlow<String?>(null)
    override val dshUpdateProgress: StateFlow<String?> = _dshUpdateProgress.asStateFlow()

    // DSH 进程级 launchToken（从 `dsh web:` 原始输出解析，仅内存）。
    private val _dshLaunchToken = MutableStateFlow<String?>(null)
    override val dshLaunchToken: StateFlow<String?> = _dshLaunchToken.asStateFlow()

    /**
     * 运行期 DSH 地址（实际端口以启动输出为准）：供界面、WebView 与健康检查
     * 使用。null = 尚未从输出确证地址（未启动过 / 输出不可解析），消费方回退
     * [Constants.DSH_BASE_URL]。重启（含换端口重试）开始时清空，回落值永远
     * 指向**本次请求**的端口，而不是上一轮的旧端口。
     */
    private val _dshBaseUrl = MutableStateFlow<String?>(null)
    override val dshBaseUrl: StateFlow<String?> = _dshBaseUrl.asStateFlow()

    /**
     * 本进程内的启动代次计数（[launchGeneration] 的自增源）：每开始一轮
     * startDsh / 就地重启递增一次，仅由持有 [lifecycleMutex] 的启动路径推进，
     * Long 单调计数在进程生命周期内不会回绕到歧义。
     */
    private var launchGenerationCounter: Long = 0L

    /**
     * 当前轮启动的代次：确证门用它区分「旧进程输出的迟到行」与「本轮进程的
     * 输出行」。每轮启动（手动 startDsh、就地重启、健康循环触发的重启）开始
     * 时自增并清空地址 / token；输出解析 [ingestDshWebLaunchLine] 写入前校验
     * 代次——不属于当前轮的行只能作废，不得写入地址。`addressConfirmed`（=
     * `_dshBaseUrl != null`）因此在任何重启之后都恒等于「本轮启动之后解析到
     * 的值」，旧进程在停机窗口里吐出的最后一行不能再通过确证门。
     *
     * @Volatile internal var（setter 同 internal）：仅为 JVM 单测可直接构造
     * 代次时序；生产改动仍走启动路径的自增。
     */
    @Volatile
    internal var launchGeneration: Long = -1L

    /** 构造时注入的默认检查器（3080 口径，保留可测注入口）；每轮启动复位回它。 */
    private val fallbackHealthChecker: SandboxHealthChecker = configuredHealthChecker

    /**
     * 当前健康检查器（跟随实际端口换新）。启动前是默认 3080 检查器
     * （[fallbackHealthChecker]）；每次确证新端口（探测下发或输出解析）都重建
     * 一次，健康循环与手动 healthCheck 均读本引用。
     */
    @Volatile
    private var healthChecker: SandboxHealthChecker = configuredHealthChecker

    /**
     * 本次 startDsh 在互斥锁内的启动中止原因（非空 = 拒绝发车）。锁体只回传
     * Boolean，这里把"写覆盖层失败"等中止细节传出锁外，由 startDsh 据此
     * 返回起步失败（可诊断错误）而不是误报"已在使用中"。
     */
    @Volatile
    private var startAbortReason: String? = null

    /** 测试注入口：仅暴露端口读取（生产代码不触碰）。 */
    internal val healthCheckerTestable: HttpHealthChecker? get() = healthChecker as? HttpHealthChecker

    /** 端口探测区间上界日志用（3080..3099）。 */
    private val PROBE_PORT_RANGE_END = Constants.DSH_DEFAULT_PORT + DshPortProbe.PORT_COUNT - 1

    /**
     * 本次启动的请求端口（探测结果），供输出解析日志对照与重试序列（用户
     * 手动重启即重申：下一轮 startDsh 重新探测，竞争失败落在输出判断）。
     */
    @Volatile
    private var nextDshLaunchPass: Int? = null

    /**
     * 跨重启的端口探测游标：上一轮启动的请求端口（
     * [nextDshLaunchPass]）即游标；自动重启前把游标推进到「上轮请求端口 +
     * 1」（纯计算收口在 [DshPortProbe.cursorFirstPort]），不回绕、顶到上界
     * 3099 后不再回走。
     *
     * 若重启从 3080 重探且先杀旧进程使原端口可绑，自动重启会反复拿回同一个
     * 请求端口，换端口重试形同虚设。
     *
     * 语义：
     *  - 用户**手动**重启 DSH（stopDsh → startDsh）视为全新意图：手动
     *    startDsh 恒从 3080 重探（= 规范「直到下次用户手动重启 DSH 才回到
     *    3080」），不读游标；
     *  - 自动路径（就地重启、健康循环重启）：进程退出或持续不应答触发重启时**推进**
     *    （起点 = 上轮请求端口 + 1 + attempt），探测窗口沿剩余区间裁剪；
     *  - 成功确证后游标随 [nextDshLaunchPass] 保留，不被任何清理路径回走，
     *    下一次自动重启仍从它的下一个起探。
     */
    private fun advancePortCursorFromLastRequest(): Int {
        val lastRequested = nextDshLaunchPass ?: Constants.DSH_DEFAULT_PORT
        val cursor = DshPortProbe.cursorFirstPort(lastRequested, attempt = 0)
        Log.i(TAG, "dsh port cursor advanced: lastRequested=$lastRequested cursor=$cursor")
        return cursor
    }

    /** JVM 单测代次时序专用：复位确证地址与 token（生产重启路径的同款动作）。 */
    internal fun resetConfirmedAddressForTest() {
        _dshBaseUrl.value = null
        _dshLaunchToken.value = null
    }

    private val dshLayer = DshLayer(runtimeCurrentDir(), bundleManager)

    private val lifecycleMutex = Mutex()
    @Volatile
    private var dshHealthLoopJob: Job? = null
    @Volatile
    private var sandboxProcess: SandboxProcessRunner.RunningProcess? = null
    @Volatile
    private var dshProcess: SandboxProcessRunner.RunningProcess? = null

    override suspend fun initialize() {
        if (_sandboxState.value != SandboxState.UNINITIALIZED) return
        _sandboxState.value = SandboxState.INITIALIZING
        try {
            createDirectories()
        } catch (t: Throwable) {
            _sandboxState.value = SandboxState.ERROR
            _dshState.value = DshState.ERROR
            return
        }
        // 一次性迁移——旧实现把 npm 下载缓存留在 base/root/.npm
        // （运行环境本体红线区，清理功能清不到，可膨胀至数百 MB）。随后 npm 缓存
        // 由 runGuestCommand 的 bind 指向宿主 cacheDir/npm-cache，base 内不再写入；
        // 这里幂等删除旧残留以释放空间（缓存无状态，删除安全；无残留时为空操作）。
        runCatching { File(baseRootfs(), "root/.npm").deleteRecursively() }
        _sandboxState.value = SandboxState.STOPPED
        _dshState.value = DshState.STOPPED
        _dshVersion.value = dshLayer.installedVersion()
    }

    override suspend fun startSandbox() = lifecycleMutex.withLock {
        if (_sandboxState.value == SandboxState.RUNNING) return@withLock
        _sandboxState.value = SandboxState.STARTING
        try {
            ensureRuntimePresent()
            val runtimeDir = runtimeCurrentDir()
            val command = processRunner.buildProotSandboxCommand(
                prootBinary = prootBinary().absolutePath,
                rootfsDir = baseRootfs().absolutePath,
                workspaceBind = config.userDataDir.absolutePath,
                nodeDir = nodeLayerDir().takeIf { it.isDirectory }?.absolutePath,
                dshDir = dshLayerDir().takeIf { it.isDirectory }?.absolutePath,
                shareBinds = shareAreaBinds(),
            )
            val prootEnv = buildProotEnv(runtimeDir, "sandbox")
            Log.i(TAG, "starting sandbox proot")
            sandboxProcess = processRunner.start(command, tag = "sandbox", env = prootEnv)
            Log.i(TAG, "sandbox proot process started")
        } catch (t: Throwable) {
            Log.e(TAG, "startSandbox failed: ${t.message}", t)
            _sandboxState.value = SandboxState.ERROR
            return@withLock
        }
        _sandboxState.value = SandboxState.RUNNING
    }

    override suspend fun stopSandbox() {
        lifecycleMutex.withLock {
            Log.i(TAG, "stopSandbox(): cancelling dsh health loop, sandboxProcess=${sandboxProcess != null}")
            dshHealthLoopJob?.cancel()
            dshHealthLoopJob = null
            sandboxProcess?.let { processRunner.stop(it) }
            sandboxProcess = null
            _sandboxState.value = SandboxState.STOPPED
            // DSH runs inside the same Debian rootfs; stopping the sandbox
            // tears down its apps too. The caller can restart DSH later after
            // restarting the sandbox.
            if (_dshState.value != DshState.STOPPED && _dshState.value != DshState.ERROR) {
                dshProcess?.let { processRunner.stop(it) }
                // 同上——句柄丢失时按 cmdline marker 兜底清扫 DSH 树。
                runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
                dshProcess = null
                _dshState.value = DshState.STOPPED
            }
            Log.i(TAG, "stopSandbox(): sandbox=STOPPED")
        }
    }

    override suspend fun restartSandbox() {
        stopSandbox()
        delay(200L)
        startSandbox()
    }

    override suspend fun forceStop() {
        stopDsh()
        stopSandbox()
        // Phase C: last-resort process-tree sweep so no PRoot/DSH orphan
        // survives a force stop even if a tracked pid escaped its group.
        runCatching { processRunner.killAll("proot") }
        runCatching { processRunner.killAll("dshapp") }
    }

    override suspend fun healthCheck(): AppResult<SandboxHealth> {
        val health = healthChecker.check()
        return health.toAppResult()
    }

    override suspend fun sandboxCpuMillis(): Long? = withContext(Dispatchers.IO) {
        // 两个句柄都没了就没什么可统计的：返回 null，让调用方明确知道「当前无沙箱占用」。
        if (sandboxProcess == null && dshProcess == null) return@withContext null
        // 一次 /proc 表遍历，两种标记的进程树取并集（DSH 若挂在沙箱树下不会被算两遍）。
        processRunner.cpuMillisForMarkers(
            listOf(Constants.SANDBOX_KEEPALIVE_MARKER, Constants.DSH_START_SCRIPT),
        )
    }

    override suspend fun startDsh(): AppResult<DshRuntimeStatus> {
        // Fast path: if DSH is already active (starting/running/ready) we do
        // not require the sandbox to be online again.
        val alreadyActive =
            _dshState.value == DshState.RUNNING ||
                _dshState.value == DshState.READY ||
                _dshState.value == DshState.STARTING
        if (!alreadyActive && _sandboxState.value != SandboxState.RUNNING) {
            return AppResult.Failure(
                AppError(
                    code = "SANDBOX_NOT_RUNNING",
                    message = "Sandbox not running; please start Debian sandbox first",
                    recoverable = true,
                    userMessage = UiText.Res(R.string.sandbox_not_running_debian),
                ),
            )
        }
        // 在线导入拆分后 base/node 是两步：仅装 Linux 层时快速拒绝并指向 node 层导入，
        // 避免落到 proot 层报"找不到 /usr/local/bin/node"的难懂错误。
        if (!isNodeLayerInstalled()) {
            return AppResult.Failure(
                AppError(
                    code = "NODE_LAYER_MISSING",
                    message = "node layer not installed; import it before starting DSH",
                    recoverable = true,
                    userMessage = UiText.Res(R.string.dsh_start_need_node),
                ),
            )
        }

        val shouldStart = lifecycleMutex.withLock {
            startAbortReason = null
            val activeNow =
                _dshState.value == DshState.RUNNING ||
                    _dshState.value == DshState.READY ||
                    _dshState.value == DshState.STARTING
            if (activeNow) {
                // Another startDsh is already in progress or DSH is up:
                // do NOT launch a second process.
                false
            } else {
                _dshState.value = DshState.STARTING
                // 本轮启动开始：递增代次并清空上一轮的确证地址与 token，让
                // "回落值"只指向本次请求的端口（旧端口可能已易主、或本轮 DSH
                // 尚未绑定回它）。代次同时闭合确证门：旧进程在停机窗口里吐出
                // 的 `dsh web:` 行不再能通过 addressConfirmed。
                launchGeneration = ++launchGenerationCounter
                _dshBaseUrl.value = null
                _dshLaunchToken.value = null
                healthChecker = fallbackHealthChecker
                // 本进程尚未跟踪任何实例，但上一轮 App 进程可能留下仍占着 3080 的孤儿
                // dsh（句柄随进程回收一并丢失）。**先**按 cmdline 兜底清扫再探端口：
                // 探测必须看不见自己上一轮的监听，否则孤儿清不掉（或 TIME_WAIT 残留
                // 未落定时）端口会被判占用、每次启动都被往后挤——与停机、就地重启
                // 两处相同的兜底，顺序固定"先杀后探"。
                runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
                // killAll 发出 SIGKILL 后垂死的监听不会瞬间释放：强杀到端口
                // 真正可绑之间存在内核收尾窗口，期间探测会把该端口判占用、
                // 启动被偶发地往后挤一格。给一道有界等待：轮询至多约 500ms，
                // 首个探测端口（3080）可绑或相关进程消失即提前结束（都在
                // 后台协程 + lifecycleMutex 内，不阻塞主线程）。
                awaitPortsBindableAfterKill(Constants.DSH_DEFAULT_PORT)
                // Start 前清掉"持有者已不存在"的写者锁：dsh 的原子写只等 2 秒且
                // 不会清理别人留下的锁，一次强杀就能让之后的启动全部失败。
                sweepDeadWriterLocks()
                // 端口自适应：启用时每次手动启动都从 3080 重新探（不持久化）——
                // 手动启动是用户意志介入的全新意图：「下次用户手动重启
                // DSH 才回到 3080」；自动路径的游标推进只活在就地重启内部
                // （[advancePortCursorFromLastRequest]），与手动路径互不影响。
                // 取第一个空闲端口写入 webserver 覆盖层；全占用才放弃
                // （ERROR 由探测失败直接置）。
                // 探测与真正绑定之间存在竞争，真正生效的端口以启动输出为准
                // （ingestDshWebLaunchLine）。
                // gecko 变体不启用端口自适应（其状态桥扩展与页面兼容配置在构建期
                // 固化 3080，见 [config.dshPortAdaptationEnabled]）：固定请求 3080
                // 跳过探测——3080 被占时照常由输出解析/健康检查暴露故障，不静默挪端口。
                val probedPort = if (config.dshPortAdaptationEnabled) {
                    DshPortProbe.probe()
                } else {
                    Constants.DSH_DEFAULT_PORT
                }
                if (probedPort == null) {
                    Log.e(TAG, "startDsh: no free port in ${Constants.DSH_DEFAULT_PORT}..$PROBE_PORT_RANGE_END")
                    _dshState.value = DshState.ERROR
                    false
                } else {
                    try {
                        ensureRuntimePresent()
                        if (config.dshPortAdaptationEnabled) {
                            // 探测端口写进 webserver 覆盖层（user-data 下，与既有三层同一
                            // 命名空间、文件级隔离，合并保留既有行）。写失败不能静默继续：
                            // 自适应启用且 3080 被占时，写失败意味着 DSH 必然按 3080 起并
                            // 撞上占用者；判启动失败并给出可诊断错误比"必然失败"更明确。
                            val wroteOverlay = DshWebserverOverlay.refresh(
                                config.userDataDir, Constants.DSH_DEFAULT_HOST, probedPort, activeDshInstance(),
                            )
                            if (!wroteOverlay) {
                                startAbortReason =
                                    "dsh webserver overlay write failed (userDataDir=${config.userDataDir.absolutePath})"
                                Log.e(TAG, "startDsh: $startAbortReason")
                                _dshState.value = DshState.ERROR
                                return@withLock false
                            }
                        }
                        Log.i(
                            TAG,
                            "startDsh: requestedPort=$probedPort (adaptation=${config.dshPortAdaptationEnabled})",
                        )
                        // 请求端口先按它重建健康检查器（避免拿旧端口探新进程）；
                        // 输出确证实际端口后再各自换新。
                        if (config.dshPortAdaptationEnabled && healthCheckerTestable?.port != probedPort) {
                            healthChecker = HttpHealthChecker(Constants.DSH_DEFAULT_HOST, probedPort)
                        }
                        // 请求端口即为本次 launch 的下发值：环境变量兜底（旧版 DSH 只看
                        // env）与覆盖层双通道，输出解析仍是唯一真值。
                        nextDshLaunchPass = probedPort
                        startDshProotProcess()
                        true
                    } catch (t: Throwable) {
                        Log.e(TAG, "startDsh failed: ${t.message}", t)
                        _dshState.value = DshState.ERROR
                        false
                    }
                }
            }
        }

        // 锁内的启动中止（覆盖层写失败等）在此转为起步失败：状态已置 ERROR，
        // 等待循环会立即退出，这里直接给出可诊断错误而非"已在用"误报。
        startAbortReason?.let { reason ->
            return AppResult.Failure(AppError("DSH_START_ABORTED", reason))
        }

        if (shouldStart) {
            // Fresh process: always (re)create the health loop with fresh window state.
            // Any stale loop from a previous session is cancelled here.
            startDshHealthLoop()
        } else if (dshHealthLoopJob?.isActive != true) {
            // Already active path: only start a loop when none is watching.
            startDshHealthLoop()
        }

        // Wait for DSH to settle into a terminal state, bounded by timeout.
        // 上限比健康循环的首次启动超时多留一个余量：循环按 2 秒节拍探测、在超时后的
        // 下一拍才给出 READY 或 ERROR，等待若与超时同时结束会抢在循环的结论之前返回。
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < config.dshReadyTimeoutMs + READY_WAIT_MARGIN_MS) {
            when (_dshState.value) {
                DshState.READY -> {
                    return AppResult.Success(
                        DshRuntimeStatus(
                            dshVersion = null,
                            pluginApiVersion = null,
                            // READY 以地址确证为前提；确证值缺位时回落**本次请求**
                            // 的端口（不是固定 3080，也不是上一轮旧端口）。
                            baseUrl = _dshBaseUrl.value
                                ?: "http://${config.dshHost}:${nextDshLaunchPass ?: config.dshPort}",
                            ready = true,
                        ),
                    )
                }
                DshState.ERROR -> {
                    return AppResult.Failure(AppError("DSH_NOT_READY", "DSH did not become ready within the time limit", userMessage = UiText.Res(R.string.dsh_not_ready_timeout)))
                }
                DshState.STOPPED -> {
                    return AppResult.Failure(
                        AppError(
                            "DSH_STOPPED",
                            "DSH has been stopped, please try again later",
                            recoverable = true,
                            userMessage = UiText.Res(R.string.dsh_stopped_retry),
                        ),
                    )
                }
                else -> Unit
            }
            delay(500L)
        }
        return AppResult.Failure(AppError("DSH_NOT_READY", "DSH ready timeout", userMessage = UiText.Res(R.string.dsh_ready_timeout)))
    }

    /**
     * 以 `nextDshLaunchPass`（当前请求端口）带参数拉起 DSH PRoot 进程：
     * startDsh 与就地重启共用同一构造路径（`--patch` 层清单、角色 env、
     * 输出监听全部一致），端口差异只体现在请求值上。
     * 调用方持有 [lifecycleMutex]。
     */
    private fun startDshProotProcess() {
        val runtimeDir = runtimeCurrentDir()
        val command = processRunner.buildProotDshCommand(
            prootBinary = prootBinary().absolutePath,
            rootfsDir = baseRootfs().absolutePath,
            workspaceBind = config.userDataDir.absolutePath,
            nodeDir = nodeLayerDir().takeIf { it.isDirectory }?.absolutePath,
            dshDir = dshLayerDir().takeIf { it.isDirectory }?.absolutePath,
            shimHostDir = linkShimHostDir(),
            dshPatchGuestPaths = dshOverlayGuestPaths(),
            pilotHostDir = pilotHostDir(),
            browserHostDir = browserHostDir(),
            shareBinds = shareAreaBinds(),
            guestCacheHostDir = config.guestCacheDir.absolutePath,
            extraBinds = config.extraGuestBindsProvider(),
        )
        val prootEnv = buildProotEnv(runtimeDir, "dsh")
        Log.i(TAG, "starting dsh proot")
        // P1-1：本进程输出的行随它所属的启动代次一起校验——监听器在进程起
        // 动时快照当时的 launchGeneration，行到达时若当前代次已经推进（另一
        // 轮启动已开始），这行必是旧进程的迟到输出，不得复位地址确证门。
        val generationAtLaunch = launchGeneration
        dshProcess = processRunner.start(
            command, tag = "dsh", env = prootEnv,
            onRawLine = { line -> ingestDshWebLaunchLine(line, generationAtLaunch) },
        )
        Log.i(TAG, "dsh proot process started")
    }

    override suspend fun stopDsh() = lifecycleMutex.withLock { stopDshLocked() }

    /** Body of [stopDsh] for callers that already hold [lifecycleMutex] (avoids re-entrant lock). */
    private suspend fun stopDshLocked() {
        Log.i(TAG, "stopDsh(): dshProcess=${dshProcess != null}")
        dshHealthLoopJob?.cancel()
        dshHealthLoopJob = null
        dshProcess?.let { processRunner.stop(it) }
        // 仅靠 dshProcess 句柄不可靠——句柄为 null 时旧 DSH
        // proot 继续存活、占着 3080，换层后新 DSH 反复 EADDRINUSE 起不来。
        // 按 cmdline marker（@deepseek-ai/dsh/lib/bin.js）兜底清扫旧 DSH 进程树，
        // 保证停机路径 3080 必然释放（安装/重启用，含句柄丢失场景）。
        runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
        dshProcess = null
        _dshState.value = DshState.STOPPED
        _dshUnresponsive.value = false
        _dshRecoverySuggested.value = false
        Log.i(TAG, "stopDsh(): dsh=STOPPED")
    }

    override suspend fun restartDsh(): AppResult<DshRuntimeStatus> {
        stopDsh()
        delay(200L)
        return startDsh()
    }

    override suspend fun recover(level: RecoveryLevel): AppResult<Unit> {
        return when (level) {
            RecoveryLevel.DSH_RESTART -> restartDsh().map { }
            RecoveryLevel.SANDBOX_RESTART -> {
                _sandboxState.value = SandboxState.RECOVERING
                restartSandbox()
                AppResult.Success(Unit)
            }
            else -> AppResult.Failure(AppError("RECOVERY_UNSUPPORTED", "Recovery level not implemented yet"))
        }
    }

    override suspend fun enterSafeMode() {
        forceStop()
        _sandboxState.value = SandboxState.STOPPED
        _dshState.value = DshState.STOPPED
    }

    override fun isNodeLayerInstalled(): Boolean = File(nodeLayerDir(), "bin/node").isFile

    /**
     * 运行环境是否可用：PRoot 与 base 目录在位**且** runtime-profile.json 存在、可解析、
     * assembly 非空**且**逐层完整性校验通过。
     *
     * profile 是层完整性的唯一依据：body 替换时层目录先就位、profile 后落盘，进程若在
     * 两者之间终止会留下「base 目录存在但 profile 缺失」的半成品；此时无法证明环境可用，
     * 一律判未安装——调用方据此走重新安装（半成品会被 body 替换覆盖），而非把半成品
     * 当作已安装放行。
     */
    override fun isRuntimeInstalled(): Boolean {
        val proot = prootBinary()
        val base = baseRootfs()
        val installed = proot.isFile && base.isDirectory
        val profile = runtimeProfile()
        if (!installed) {
            Log.i(TAG, "isRuntimeInstalled=false (proot/base missing) proot=${proot.absolutePath} base=${base.absolutePath}")
            return false
        }
        if (profile == null || profile.assembly.isEmpty()) {
            Log.w(TAG, "isRuntimeInstalled=false (profile missing/empty: cannot prove layer integrity)")
            return false
        }
        val broken = verifyLayersBroken(profile)
        if (broken.isNotEmpty()) {
            Log.w(TAG, "isRuntimeInstalled=false: layer integrity broken: $broken")
            return false
        }
        Log.i(TAG, "isRuntimeInstalled=true proot=${proot.absolutePath} base=${base.absolutePath}")
        return true
    }

    /**
     * Phase C: verify every layered runtime component (base/node/android-side)
     * against runtime-profile.json. Shallow, cheap integrity used on every
     * launch: for each layer the declared checksum must match the sentinel that
     * was recorded at install time. Missing sentinels (runtimes installed before
     * Phase C) are self-healed by recording the declared checksum, so a fresh
     * import does not loop into a reinstall. Returns [true] when all layers are
     * intact AND the PRoot/base requirements hold.
     */
    fun ensureRuntimeComponents(): Boolean {
        ensureGuestResolvConf()
        val proot = prootBinary()
        val base = baseRootfs()
        if (!proot.isFile || !base.isDirectory) return false
        val profile = runtimeProfile()
        if (profile != null && profile.assembly.isNotEmpty()) {
            return verifyLayersBroken(profile).isEmpty()
        }
        return true
    }

    private fun verifyLayersBroken(profile: RuntimeProfile): List<String> {
        val broken = mutableListOf<String>()
        for (name in profile.assembly) {
            val dir = layerDir(name)
            if (dir == null || !dir.isDirectory) {
                broken += "$name:dir-missing"
                continue
            }
            val expected = profile.layer(name)?.sha256?.takeIf { it.isNotBlank() }
            if (expected == null) {
                broken += "$name:profile-has-no-sha"
                continue
            }
            val sentinel = layerSentinel(name)
            if (sentinel.isFile) {
                if (!sentinel.readText().trim().equals(expected, ignoreCase = true)) {
                    broken += "$name:sha-mismatch"
                }
            } else {
                // self-heal: a layered runtime installed before sentinels existed
                // has none; record the declared checksum so we don't reinstall
                // unnecessarily (a real tamper is caught on the next install).
                runCatching { sentinel.parentFile?.mkdirs(); sentinel.writeText(expected) }
                    .onFailure { Log.w(TAG, "write layer sentinel $name failed: ${it.message}") }
            }
        }
        return broken
    }

    private fun layerDir(name: String): File? = when (name) {
        "base" -> baseRootfs()
        "node" -> nodeLayerDir()
        "android-side" -> prootSideDir()
        else -> File(runtimeCurrentDir(), name)
    }

    private fun layerSentinel(name: String): File {
        val dir = layerDir(name) ?: return File(runtimeCurrentDir(), ".missing-$name")
        return File(File(dir, ".dshbox"), "layer-$name.sha256")
    }

    override suspend fun installFirstAvailableBundle(): AppResult<java.io.File> {
        val updates = config.updatesDir
        val bundles = updates.listFiles { file ->
            file.isFile && file.name.endsWith(".tar.gz")
        }?.sortedBy { it.name }

        if (bundles.isNullOrEmpty()) {
            return AppResult.Failure(AppError("NO_BUNDLE_FOUND", "no .tar.gz bundle found in ${updates.absolutePath}"))
        }

        for (bundle in bundles) {
            val sidecar = File(updates, bundle.name + ".sha256")
            if (!sidecar.isFile) continue
            val expected = sidecar.readText().trim().split(Regex("\\s+")).firstOrNull()
            if (expected.isNullOrBlank()) continue
            val installed = bundleManager.installToNewSlot(bundle, expected)
            if (installed is AppResult.Success) {
                Log.i(TAG, "installed bundle ${bundle.name} into runtime-new")
                return installed
            }
            Log.w(TAG, "bundle ${bundle.name} rejected: ${(installed as AppResult.Failure).error.message}")
        }
        return AppResult.Failure(AppError("NO_INSTALLABLE_BUNDLE", "no bundle with valid .sha256 sidecar in ${updates.absolutePath}"))
    }

    override suspend fun installRuntimeBundle(bundleFile: java.io.File, expectedSha256: String): AppResult<java.io.File> {
        if (_sandboxState.value == SandboxState.RUNNING) {
            return AppResult.Failure(AppError("SANDBOX_RUNNING", "stop the sandbox before installing a Runtime Bundle"))
        }
        return bundleManager.installToNewSlot(bundleFile, expectedSha256)
    }

    override suspend fun promoteRuntimeBundle(): AppResult<Unit> {
        if (_sandboxState.value == SandboxState.RUNNING) {
            return AppResult.Failure(AppError("SANDBOX_RUNNING", "stop the sandbox before switching Runtime slots"))
        }
        return bundleManager.promoteNewSlotToCurrent()
    }

    override suspend fun rollbackRuntime(): AppResult<Unit> {
        if (_sandboxState.value == SandboxState.RUNNING) {
            stopSandbox()
        }
        return bundleManager.rollback()
    }

    /**
     * Offline-import a layered runtime bundle as EITHER:
     *  - a ZIP holding the body layer archives (base/node/android-side <layer>.tar.*
     *    + .sha256 sidecars + runtime-profile.json), flat or under ONE common
     *    top-level folder (Windows 右键压缩文件夹会产生该前缀), OR
     *  - a single tar-family outer package (.tar.gz / .tar.zst / .tar / .tar.bz2 /
     *    .tar.xz by magic) whose contents are EITHER the layered body snapshot
     * (base/, node/, android-side/, runtime-profile.json) OR — —
     *    the layer archives themselves (tar of archives: base.tar.* etc.), which
     *    is then processed through the same archive-validation pipeline as a zip.
     *
     * / hardening :
     *  - layer archives are matched EXACTLY (<layer>.tar.<ext>). 1.0.0 used
     *    startsWith("<layer>.tar."), which ALSO matched the "<layer>.tar.zst.sha256"
     *    sidecar; ZipInputStream walks entries in archive order, so the 92-byte
     *    sidecar overwrote the real archive in the map and EVERY official zip
     *    import then died with "Not in GZIP format";
     *  - runtime-profile.json is REQUIRED. Without it the old profile stayed in
     *    place, its declared layer checksums then failed verifyLayersBroken() on
     *    the next launch and the bundled runtime was silently reinstalled OVER
     *    the user's import;
     *  - when both the sidecar and the profile declare a layer checksum they must
     *    agree; the archive is verified against the declared checksum;
     *  - every zip destination is validated against path traversal (Zip-Slip)
     *    before a single byte is written.
     *
     * Cleanly replaces the runtime body moving the old body -> previous/ (single copy).
     * **Never** touches `runtime-current/dsh` (DSH layer) nor `user-data` /
     * `user-data/.dsh`. Sandbox must be stopped first.
     */
    override suspend fun importRuntimeBundle(source: java.io.File): AppResult<Unit> = lifecycleMutex.withLock {
        if (_sandboxState.value == SandboxState.RUNNING) {
            return@withLock AppResult.Failure(AppError("SANDBOX_RUNNING", "stop the sandbox before importing a Runtime Bundle"))
        }
        val layerNames = listOf("base", "node", "android-side")
        val staging = File(config.appFilesDir, "runtime-bundle-staging").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        try {
            if (isZip(source)) {
                val archives = mutableMapOf<String, File>() // layer -> staged <layer>.tar.<ext>
                val sidecars = mutableMapOf<String, String>() // layer -> declared sha256
                var profileFile: File? = null
                ZipFile(source).use { zip ->
                    val fileEntries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
                    if (fileEntries.isEmpty()) {
                        return@withLock AppResult.Failure(AppError("BUNDLE_EMPTY", "Archive contains no files", userMessage = UiText.Res(R.string.bundle_empty)))
                    }
                    // Name-level analysis (exact layer matching, common folder prefix,
                    // traversal rejection) lives in the pure, unit-tested RuntimeBundleLayout.
                    val layout = when (val parsed = RuntimeBundleLayout.analyze(fileEntries.map { it.name })) {
                        is RuntimeBundleLayout.Result.Unsafe -> return@withLock AppResult.Failure(
                            AppError("BUNDLE_UNSAFE_PATH", "Archive contains illegal path (..), blocked: ${parsed.entryName}",
                                userMessage = UiText.Res(R.string.bundle_unsafe_path_rel, listOf(parsed.entryName))),
                        )
                        is RuntimeBundleLayout.Result.Ok -> parsed
                    }
                    // Zip-Slip guard: validate every canonical destination BEFORE writing anything.
                    val targets = LinkedHashMap<java.util.zip.ZipEntry, File>()
                    for (entry in fileEntries) {
                        val norm = layout.targets[entry.name] ?: continue
                        val target = File(staging, norm).canonicalFile
                        if (!isWithinDir(target, staging)) {
                            return@withLock AppResult.Failure(
                                AppError("BUNDLE_UNSAFE_PATH", "Archive path escapes target directory, blocked: ${entry.name}",
                                    userMessage = UiText.Res(R.string.bundle_unsafe_path_escape, listOf(entry.name))),
                            )
                        }
                        targets[entry] = target
                    }
                    for ((entry, target) in targets) {
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    // Materialize the analyzed layout into staged files.
                    for ((layer, archiveName) in layout.archives) {
                        archives[layer] = File(staging, archiveName)
                        val sidecarName = layout.sidecars[layer]
                        if (sidecarName != null) {
                            sidecars[layer] = File(staging, sidecarName).readText().trim()
                                .split(Regex("\\s+")).firstOrNull().orEmpty()
                        }
                    }
                    layout.profilePath?.let { profileFile = File(staging, it) }
                }
                when (val r = extractStagedLayers(staging, archives, sidecars,
                    profileFile ?: return@withLock AppResult.Failure(
                        AppError("BUNDLE_NO_PROFILE", "Runtime bundle missing runtime-profile.json (cannot verify layer integrity, import rejected)",
                            userMessage = UiText.Res(R.string.bundle_no_profile)),
                    ),
                )) {
                    is AppResult.Failure -> return@withLock r
                    is AppResult.Success -> Unit
                }
            } else {
                // 单 tar/gz/zst/裸 tar 外层包：支持两种内容布局——
                //  A) 快照布局：直接是 base/、node/、android-side/ 目录 + runtime-profile.json；
                //  B) 层归档布局（tar of archives）：把官方 zip 的内容（base.tar.* 等层归档 +
                //     profile）原样打成 tar —— 自动按 zip 同款逻辑识别层归档并解压，
                //     消除「tar 里装的是归档文件就报缺少 base 层」的误报。
                when (val r = bundleManager.extractTarGz(source, staging)) {
                    is AppResult.Failure -> return@withLock r
                    is AppResult.Success -> Unit
                }
                if (!File(staging, "runtime-profile.json").isFile) {
                    return@withLock AppResult.Failure(
                        AppError("BUNDLE_NO_PROFILE", "Runtime bundle missing runtime-profile.json (cannot verify layer integrity, import rejected)",
                            userMessage = UiText.Res(R.string.bundle_no_profile)),
                    )
                }
                if (!File(staging, "base").isDirectory) {
                    // 布局 B：根目录没有 base/ 目录 → 递归识别 <layer>.tar[.ext] 层归档。
                    val archives = mutableMapOf<String, File>()
                    val sidecars = mutableMapOf<String, String>()
                    staging.walkTopDown().forEach { f ->
                        if (!f.isFile) return@forEach
                        val rel = f.relativeTo(staging).path.replace('\\', '/')
                        val layer = RuntimeBundleLayout.layerOfArchiveName(rel)
                        if (layer != null) {
                            archives[layer] = f
                            val sidecar = File(f.path + ".sha256")
                            if (sidecar.isFile) {
                                sidecars[layer] = sidecar.readText().trim()
                                    .split(Regex("\\s+")).firstOrNull().orEmpty()
                            }
                        }
                    }
                    if (archives.isEmpty()) {
                        return@withLock AppResult.Failure(
                            AppError(
                                "BUNDLE_NO_BASE",
                                "Runtime bundle missing base layer (snapshot layout requires base/ directory; layer archive layout requires <layer>.tar[.zst/.gz/.bz2/.xz])",
                                userMessage = UiText.Res(R.string.bundle_no_base_snapshot),
                            ),
                        )
                    }
                    when (val r = extractStagedLayers(staging, archives, sidecars, File(staging, "runtime-profile.json"))) {
                        is AppResult.Failure -> return@withLock r
                        is AppResult.Success -> Unit
                    }
                }
            }
            if (!File(staging, "base").isDirectory) {
                return@withLock AppResult.Failure(AppError("BUNDLE_NO_BASE", "Runtime bundle missing base layer",
                    userMessage = UiText.Res(R.string.bundle_no_base)))
            }
            swapRuntimeBody(staging, layerNames)
            Log.i(TAG, "importRuntimeBundle: layered body replaced (base/node/android-side + profile), dsh & user-data untouched")
            return@withLock AppResult.Success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "importRuntimeBundle failed: ${t.message}", t)
            return@withLock AppResult.Failure(AppError("BUNDLE_IMPORT_FAILED", "import failed: ${t.message}"))
        } finally {
            staging.deleteRecursively()
        }
    }

    override suspend fun installAssembledRuntime(staging: File): AppResult<Unit> = lifecycleMutex.withLock {
        if (_sandboxState.value == SandboxState.RUNNING) {
            return@withLock AppResult.Failure(
                AppError("SANDBOX_RUNNING", "stop the sandbox before installing the assembled runtime",
                    userMessage = UiText.Res(R.string.bundle_sandbox_running)),
            )
        }
        // 必需层 = base + android-side；node 可选（由独立入口导入）——重装 Linux 层时
        // **保留**已装 node（swapRuntimeBody 只搬 layerNames 内的层，node 不在清单即不动）。
        val layerNames = buildList {
            if (File(staging, "base").isDirectory) add("base")
            if (File(staging, "node").isDirectory) add("node")
            if (File(staging, "android-side").isDirectory) add("android-side")
        }
        if (!layerNames.containsAll(listOf("base", "android-side"))) {
            return@withLock AppResult.Failure(
                AppError("BUNDLE_INCOMPLETE", "assembled runtime staging is missing base/android-side"),
            )
        }
        swapRuntimeBody(staging, layerNames)
        // profile 与磁盘现状对齐：staging 不含 node 而已装 node 时，重建的 profile 会把
        // 保留的 node 一并声明（hash 现算 + 哨兵自愈），verifyLayersBroken 语义不破。
        regenerateProfile()
        Log.i(TAG, "installAssembledRuntime: assembled body installed (layers=$layerNames), dsh & user-data untouched")
        AppResult.Success(Unit)
    }

    override suspend fun installNodeLayer(stagingNodeDir: File): AppResult<Unit> = lifecycleMutex.withLock {
        if (_sandboxState.value == SandboxState.RUNNING) {
            return@withLock AppResult.Failure(
                AppError("SANDBOX_RUNNING", "stop the sandbox before installing the node layer",
                    userMessage = UiText.Res(R.string.bundle_sandbox_running)),
            )
        }
        if (!stagingNodeDir.isDirectory || !File(stagingNodeDir, "bin/node").isFile) {
            return@withLock AppResult.Failure(
                AppError("BUNDLE_INCOMPLETE", "staging node layer is missing bin/node"),
            )
        }
        val runtimeDir = runtimeCurrentDir()
        if (!File(runtimeDir, "base").isDirectory) {
            return@withLock AppResult.Failure(
                AppError("NODE_NO_BASE", "base layer not installed; import the Linux layer first"),
            )
        }
        // 旧 node → previous/node（单副本），新 node 上位。
        val nodeDir = File(runtimeDir, "node")
        val prevNode = File(runtimeDir, "previous/node")
        if (prevNode.exists()) prevNode.deleteRecursively()
        if (nodeDir.exists() && !nodeDir.renameTo(prevNode)) nodeDir.deleteRecursively()
        if (!stagingNodeDir.renameTo(nodeDir)) {
            stagingNodeDir.copyRecursively(nodeDir, overwrite = true)
            stagingNodeDir.deleteRecursively()
        }
        regenerateProfile()
        Log.i(TAG, "installNodeLayer: node layer installed, profile regenerated, dsh & user-data untouched")
        AppResult.Success(Unit)
    }

    /**
     * 按当前实际在位的层目录（base/node/android-side 按规范序）重建
     * runtime-profile.json 并补写哨兵——在线分步导入（先 Linux 层后 node 层）后
     * profile 必须与磁盘现状一致，否则 verifyLayersBroken 会误判损坏。
     * 调用方持有 lifecycleMutex。
     */
    private fun regenerateProfile() {
        val dir = runtimeCurrentDir()
        val base = File(dir, "base")
        val node = File(dir, "node")
        val aside = File(dir, "android-side")
        if (!base.isDirectory || !aside.isDirectory) return
        val hasNode = node.isDirectory && File(node, "bin/node").isFile
        val baseSha = com.dshbox.app.sandbox.online.DirHash.sha256(base)
        val asideSha = com.dshbox.app.sandbox.online.DirHash.sha256(aside)
        val nodeSha = if (hasNode) com.dshbox.app.sandbox.online.DirHash.sha256(node) else null
        val nodeVersion = if (hasNode) {
            // node 版本从既有 profile 声明继承（重建不改版本基线）；无 profile 时用常量。
            val declared = runtimeProfile()?.layer("node")?.version
                ?: com.dshbox.app.common.NodeSources.NODE_VERSION
            declared
        } else null
        // arch 从既有 profile 继承（x86_64 模拟器装 amd64 层时不得被硬编码回 arm64）。
        val arch = runtimeProfile()?.arch?.takeIf { it.isNotBlank() } ?: "arm64"
        val builtAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val profile = com.dshbox.app.sandbox.online.RuntimeProfileWriter.build(
            bundleVersion = "0.1.0", arch = arch,
            baseVersion = runtimeProfile()?.layer("base")?.version ?: "0.1.0",
            baseSha256 = baseSha, baseSizeBytes = com.dshbox.app.sandbox.online.DirHash.sizeBytes(base),
            nodeVersion = nodeVersion, nodeSha256 = nodeSha,
            nodeSizeBytes = if (hasNode) com.dshbox.app.sandbox.online.DirHash.sizeBytes(node) else null,
            asideVersion = runtimeProfile()?.layer("android-side")?.version ?: "0.1.0",
            asideSha256 = asideSha, asideSizeBytes = com.dshbox.app.sandbox.online.DirHash.sizeBytes(aside),
            builtAtIsoUtc = builtAt,
        )
        writeFileAtomically(content = profile, target = File(dir, "runtime-profile.json"))
        // 哨兵与重建后的声明保持一致（自愈式写回，缺则补）。
        runCatching {
            File(File(base, ".dshbox"), "layer-base.sha256").parentFile?.mkdirs()
            File(File(base, ".dshbox"), "layer-base.sha256").writeText(baseSha)
            File(File(aside, ".dshbox"), "layer-android-side.sha256").parentFile?.mkdirs()
            File(File(aside, ".dshbox"), "layer-android-side.sha256").writeText(asideSha)
            if (hasNode && nodeSha != null) {
                File(File(node, ".dshbox"), "layer-node.sha256").parentFile?.mkdirs()
                File(File(node, ".dshbox"), "layer-node.sha256").writeText(nodeSha)
            }
        }
        Log.i(TAG, "regenerateProfile: base=${baseSha.take(12)} node=${nodeSha?.take(12)} aside=${asideSha.take(12)} hasNode=$hasNode")
    }

    /**
     * 分层 body 替换（[importRuntimeBundle] 与 [installAssembledRuntime] 共用）：
     * 旧 body（base/node/android-side + profile）整体搬 runtime-current/previous/
     * （单副本，先删旧 previous），staging 的新 body 搬进 runtime-current。
     * dsh 层与 user-data 全程不碰。调用方持有 lifecycleMutex。
     */
    private fun swapRuntimeBody(staging: File, layerNames: List<String>) {
        val runtimeDir = runtimeCurrentDir()
        val previous = File(runtimeDir, "previous")
        // Move CURRENT body -> previous/ (single copy). DSH layer + user-data untouched.
        for (layer in layerNames) {
            val curLayer = File(runtimeDir, layer)
            val prevLayer = File(previous, layer)
            if (prevLayer.exists()) prevLayer.deleteRecursively()
            if (curLayer.exists() && !curLayer.renameTo(prevLayer)) curLayer.deleteRecursively()
        }
        val curProfile = File(runtimeDir, "runtime-profile.json")
        val prevProfile = File(previous, "runtime-profile.json")
        if (prevProfile.exists()) prevProfile.delete()
        if (curProfile.exists()) curProfile.renameTo(prevProfile)
        // Move the NEW body from staging into runtime-current.
        for (layer in layerNames) {
            val srcLayer = File(staging, layer)
            if (srcLayer.isDirectory) {
                val destLayer = File(runtimeDir, layer)
                if (!srcLayer.renameTo(destLayer)) srcLayer.copyRecursively(destLayer, overwrite = true)
            }
        }
        val stagedProfile = File(staging, "runtime-profile.json")
        val targetProfile = File(runtimeDir, "runtime-profile.json")
        if (stagedProfile.isFile) writeFileAtomically(stagedProfile.readText(), targetProfile)
    }

    /**
     * 原子写入 [target]：内容先落同目录临时文件并 fsync，再 rename 覆盖目标。
     *
     * profile 是「环境是否装好」的唯一凭据，直接覆写半途被杀会留下截断的 JSON（解析
     * 失败即半成品）。同目录 rename 在同一文件系统内是原子的，因此目标要么是旧内容、
     * 要么是完整新内容；[File.renameTo] 覆盖已存在的目标在部分文件系统上可能失败，
     * 此时回退为直接复制（保持与既有行为等价的可用性）。临时文件在失败路径清理。
     */
    private fun writeFileAtomically(content: String, target: File) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        val wrote = runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }.onFailure {
            Log.w(TAG, "atomic write failed for ${target.name}: ${it.message}")
        }.isSuccess
        // 写入失败时不 rename：临时文件可能已被截断，宁可用旧的直接覆写路径。
        if (wrote && tmp.renameTo(target)) return
        tmp.delete()
        runCatching { target.writeText(content) }
            .onFailure { Log.w(TAG, "fallback profile write failed for ${target.name}: ${it.message}") }
    }

    /** True when [path] (canonical) equals or lives under [dir] (canonical). */
    private fun isWithinDir(path: File, dir: File): Boolean {
        val rootPath = dir.canonicalFile.absolutePath.trimEnd(File.separatorChar)
        val pathPath = path.canonicalFile.absolutePath
        return pathPath == rootPath || pathPath.startsWith("$rootPath${File.separator}")
    }

    /** True when [file] is a ZIP (PK magic). Non-zip is treated as a single tar snapshot. */
    private fun isZip(file: File): Boolean = try {
        file.inputStream().use { input ->
            val b1 = input.read()
            val b2 = input.read()
            b1 == 0x50 && b2 == 0x4B
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * 共享的「层归档 → 校验 → 解压 → sentinel」步骤（从 zip 分支抽出，
     * zip 布局与 tar-of-archives 布局共用）：要求 runtime-profile.json 存在且可解析；
     * 逐层做 .sha256 侧车与 profile 声明的交叉核对 + SHA-256 校验；解压到
     * staging/<layer> 并记录 sentinel；每层解压完成后立即删除层归档以压低峰值磁盘。
     * 解压失败/校验失败时 staging 由调用方 finally 清理，现有层不受影响。
     */
    private suspend fun extractStagedLayers(
        staging: File,
        archives: Map<String, File>,
        sidecars: Map<String, String>,
        profileFile: File,
    ): AppResult<Unit> {
        if (!profileFile.isFile) {
            return AppResult.Failure(
                AppError("BUNDLE_NO_PROFILE", "Runtime bundle missing runtime-profile.json (cannot verify layer integrity, import rejected)",
                    userMessage = UiText.Res(R.string.bundle_no_profile)),
            )
        }
        val parsedProfile = RuntimeProfile.parse(profileFile)
        if (parsedProfile == null) {
            return AppResult.Failure(AppError("BUNDLE_BAD_PROFILE", "runtime-profile.json cannot be parsed",
                userMessage = UiText.Res(R.string.bundle_bad_profile)))
        }
        for (layer in listOf("base", "node", "android-side")) {
            val arch = archives[layer]
                ?: return AppResult.Failure(
                    AppError("BUNDLE_MISSING_LAYER", "Runtime bundle missing $layer layer archive (<layer>.tar[.zst/.gz/.bz2/.xz])",
                        userMessage = UiText.Res(R.string.bundle_missing_layer, listOf(layer))),
                )
            val inProfile = parsedProfile.layer(layer)?.sha256?.takeIf { it.isNotBlank() }
            val inSidecar = sidecars[layer]?.takeIf { it.isNotBlank() }
            if (inProfile != null && inSidecar != null && !inProfile.equals(inSidecar, ignoreCase = true)) {
                return AppResult.Failure(
                    AppError("BUNDLE_SHA256_MISMATCH", "Layer $layer .sha256 sidecar does not match runtime-profile.json declaration",
                        userMessage = UiText.Res(R.string.bundle_sha_sidecar_mismatch, listOf(layer))),
                )
            }
            val expected = inSidecar ?: inProfile
            if (expected != null && !bundleManager.verifySha256(arch, expected)) {
                return AppResult.Failure(AppError("BUNDLE_SHA256_MISMATCH", "Layer $layer SHA-256 verification failed",
                    userMessage = UiText.Res(R.string.bundle_sha_mismatch, listOf(layer))))
            }
            val dest = File(staging, layer)
            when (val r = bundleManager.extractTarGz(arch, dest)) {
                is AppResult.Failure -> return r
                is AppResult.Success -> {
                    val recorded = expected ?: inProfile
                    if (recorded != null) {
                        runCatching {
                            val sentinel = File(dest, ".dshbox/layer-$layer.sha256")
                            sentinel.parentFile?.mkdirs()
                            sentinel.writeText(recorded)
                        }
                    }
                }
            }
            // The archive is no longer needed once its layer is extracted; drop it
            // right away so staging never holds both archives AND extracted layers.
            arch.delete()
        }
        return AppResult.Success(Unit)
    }

    override suspend fun runGuestCommand(
        command: String,
        onLine: (String) -> Unit,
        onProcess: (java.lang.Process) -> Unit,
        shouldAbort: () -> Boolean,
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        // npm 的默认缓存位置是 ~/.npm（guest HOME=/root）。把它 bind 到宿主
        // cacheDir/npm-cache，下载中间产物不再落 base/root/.npm（运行环境本体红线区、
        // 清理功能清不到，可膨胀至数百 MB）；缓存归 cacheDir 后随「应用缓存」可一键清理。
        // bind 目标必须是已存在目录（proot 对不存在的 bind 目标会报错）。
        config.npmCacheDir.mkdirs()
        val proot = prootBinary().absolutePath
        val cmd = buildList {
            add(proot)
            add("--rootfs=${baseRootfs().absolutePath}")
            add("--bind=/system"); add("--bind=/apex"); add("--bind=/proc"); add("--bind=/dev")
            // node 层可缺（仅装 Linux 层的在线导入形态）——bind 目标必须是已存在目录
            // （proot 对不存在的 bind 目标会报错），缺层时跳过绑定。
            nodeLayerDir().takeIf { it.isDirectory }?.let { add("--bind=${it.absolutePath}:/usr/local") }
            dshLayerDir().takeIf { it.isDirectory }?.let { add("--bind=${it.absolutePath}:/opt/dshapp/runtime") }
            // 硬链接垫片：guest 命令若要用 `dsh`（工作区里的包装脚本会 --import 它），
            // 这个挂载点必须在位。app 启动网页端 DSH 一直绑它，这里保持同口径。
            config.dshShimDir.takeIf { it.isDirectory }
                ?.let { add("--bind=${it.absolutePath}:${Constants.DSH_LINK_SHIM_GUEST_DIR}") }
            // 工具链缓存（pnpm store / corepack 缓存）：`dsh plugin` 走 guest 命令时同样需要，
            // 否则 pnpm 会按默认位置把 store 写进 base 层、把 corepack 缓存写进工作区。
            config.guestCacheDir.apply { mkdirs() }
                .let { add("--bind=${it.absolutePath}:${Constants.DSHBOX_GUEST_CACHE_DIR}") }
            add("--bind=${config.userDataDir.absolutePath}:/root/projects")
            add("--bind=${config.npmCacheDir.absolutePath}:/root/.npm")
            // 手机助手通道：guest 里跑的 `dsh plugin` 之类命令也要能调宿主能力。
            add("--bind=${pilotHostDir()}:/opt/pilot")
            browserHostDir()?.let { add("--bind=$it:/opt/browser") }
            // 共享区：内建 DSHBox + 用户挂载 → guest /root/share/<别名>；目录缺失的跳过。
            shareAreaBinds().forEach { (name, dir) ->
                add("--bind=${dir.absolutePath}:${Constants.SHARE_AREA_GUEST_DIR}/$name")
            }
            // 能力模块追加的绑定（宿主注入，本类不解释语义）。
            addAll(config.extraGuestBindsProvider())
            add("--cwd=/root")
            add("--kill-on-exit")
            // Guest entry: the rootfs's own bash instead of the host
            // /system/bin/sh — see SandboxProcessRunner.buildProotDshCommand for
            // the vendor-kernel EPERM this avoids. The command strings are
            // Debian-side scripts (npm/tar/chmod/dsh), so bash is the native
            // interpreter for them anyway.
            add("/" + GUEST_BASH_RELATIVE); add("-c")
            add(command)
        }
        processRunner.runGuestCommand(cmd, buildProotEnv(runtimeCurrentDir(), "guest"), onLine, onProcess, shouldAbort)
    }

    override suspend fun updateDsh(
        bundle: File,
        expectedSha256: String?,
        newVersion: String?,
        allowDowngrade: Boolean,
    ): AppResult<DshUpdateOutcome> {
        // Phase 1: stop DSH + install the layer, serialized under the lock, but using the
        // LOCK-FREE stopDshLocked() (calling public stopDsh() here would re-enter the same
        // non-reentrant Mutex and deadlock; also do not hold the lock for the DSH ready wait).
        val outcome = lifecycleMutex.withLock {
            _dshUpdateProgress.value = "installing DSH ${newVersion ?: ""}"
            try {
                // 旧条件 `== DshState.RUNNING` 是死代码——状态机只有
                // STARTING/READY/ERROR/STOPPED，RUNNING 从不被赋值，导致换层前
                // 旧 DSH 进程从未被主动停掉（其 proot 树仍持有旧层句柄，一直跑
                // 到 phase 2 restartDsh() 才被终结）。改为停掉全部「在线」态；
                // 并纳入 ERROR——误判（健康检查 401 等）遗留的存活
                // DSH 进程同样必须清掉，否则换层后重启 EADDRINUSE。
                val dshActive = _dshState.value != DshState.STOPPED &&
                    _dshState.value != DshState.UNINITIALIZED
                if (dshActive) stopDshLocked()
                when (val r = dshLayer.installFromBundle(bundle, expectedSha256, newVersion, allowDowngrade)) {
                    is AppResult.Success -> r.value
                    is AppResult.Failure -> return r
                }
            } finally {
                _dshUpdateProgress.value = null
            }
        }
        _dshVersion.value = dshLayer.installedVersion()
        // Phase 2: restart DSH outside the lock (public restartDsh locks briefly itself).
        if (outcome.changed && _sandboxState.value == SandboxState.RUNNING) {
            _dshUpdateProgress.value = "restarting DSH"
            try {
                restartDsh()
            } finally {
                _dshUpdateProgress.value = null
            }
        }
        return AppResult.Success(outcome)
    }

    override suspend fun rollbackDshLayer(): AppResult<DshUpdateOutcome> {
        // 先校验备份存在，避免无备份时停掉健康实例却不重启。
        if (!dshPreviousLayerAvailable()) {
            return AppResult.Failure(AppError("DSH_NO_PREVIOUS", "no valid previous DSH layer to roll back to"))
        }
        // 与 updateDsh 同构：锁内停 DSH + 换层，锁外按需重启。
        val outcome = lifecycleMutex.withLock {
            _dshUpdateProgress.value = "rolling back DSH"
            try {
                val dshActive = _dshState.value != DshState.STOPPED &&
                    _dshState.value != DshState.UNINITIALIZED
                if (dshActive) stopDshLocked()
                when (val r = dshLayer.rollbackToPrevious()) {
                    is AppResult.Success -> r.value
                    is AppResult.Failure -> return r
                }
            } finally {
                _dshUpdateProgress.value = null
            }
        }
        _dshVersion.value = dshLayer.installedVersion()
        if (outcome.changed && _sandboxState.value == SandboxState.RUNNING) {
            restartDsh()
        }
        return AppResult.Success(outcome)
    }

    override fun dshPreviousLayerAvailable(): Boolean =
        File(dshLayer.previousDshDir(), DshLayer.DSHPK_GUEST_PATH).isFile

    override fun installedDshVersion(): String? = dshLayer.installedVersion()

    /**
     * build + install a fresh DSH layer from an npm registry by running
     * npm INSIDE the guest Debian — replicating runtime-bundle/scripts/install_dsh.sh,
     * the exact way the bundled layer is produced. Flow:
     *   1. storage preflight (~1 GB free) + shell-injection guard on both params;
     *   2. ensure the sandbox is running (npm needs the guest);
     *   3. guest: stage /tmp/dsh-stage, write the layer stub package.json, then
     *      `npm install --prefix /tmp/dsh-stage @deepseek-ai/dsh@<version> --registry <url>`
     *      with output streamed to [onLog];
     *   4. host: verify the staged tree holds bin.js (guest /tmp IS host
     *      runtime-current/base/tmp — the same directory through PRoot);
     *   5. guest: pack the stage into /tmp/dsh-stage.tar.gz (base has GNU tar+gzip;
     *      BundleManager re-extracts by magic, symlinks included);
     *   6. install through [updateDsh] (staging -> validate -> previous/dsh ->
     *      Android patch -> version record -> auto restart when the sandbox runs);
     *   7. clean the guest stage in every path.
     */
    override suspend fun installDshFromNpm(
        registryUrl: String,
        version: String,
        allowDowngrade: Boolean,
        onStage: (UiText) -> Unit,
        onLog: (String) -> Unit,
        onProcess: (java.lang.Process) -> Unit,
        shouldAbort: () -> Boolean,
    ): AppResult<DshUpdateOutcome> = withContext(Dispatchers.IO) {
        // Both values end up inside `sh -c` — allow only a strict safe charset.
        if (!Regex("^https?://[A-Za-z0-9.:/_%~#?=&+-]+$").matches(registryUrl)) {
            return@withContext AppResult.Failure(AppError("DSH_NPM_BAD_REGISTRY", "Invalid registry URL: $registryUrl",
                userMessage = UiText.Res(R.string.npm_bad_registry, listOf(registryUrl))))
        }
        if (!Regex("^[A-Za-z0-9.+-]+$").matches(version)) {
            return@withContext AppResult.Failure(AppError("DSH_NPM_BAD_VERSION", "Invalid version: $version",
                userMessage = UiText.Res(R.string.npm_bad_version, listOf(version))))
        }
        val freeBytes = runCatching {
            android.os.StatFs(config.runtimeDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        if (freeBytes < Constants.DSH_INSTALL_MIN_FREE_BYTES) {
            return@withContext AppResult.Failure(
                AppError(
                    "DSH_NPM_LOW_STORAGE",
                    "Insufficient storage (need ~1GB available, currently only ${freeBytes / (1024 * 1024)}MB)",
                    userMessage = UiText.Res(R.string.npm_low_storage, listOf((freeBytes / (1024 * 1024)).toInt())),
                ),
            )
        }
        // The guest must be alive for npm.
        if (_sandboxState.value != SandboxState.RUNNING) {
            onStage(UiText.Res(R.string.npm_stage_starting_sandbox))
            startSandbox()
            if (_sandboxState.value != SandboxState.RUNNING) {
                return@withContext AppResult.Failure(AppError("SANDBOX_NOT_RUNNING", "Sandbox failed to start, cannot run npm install",
                    userMessage = UiText.Res(R.string.npm_sandbox_start_failed)))
            }
        }

        val stage = "/tmp/dsh-stage"
        val tarPath = "/tmp/dsh-stage.tar.gz"
        val pkgSpec = "@deepseek-ai/dsh@$version"
        // Layer-root stub package.json (matches the bundled layer's shape). Single-quoted
        // in the shell script; version/registry are charset-validated above.
        val pkgJson = "{\"name\":\"dsh-layer\",\"version\":\"$version\"}"
        // TMPDIR must point INSIDE the guest: the guest process inherits the Android
        // app's cache dir which does not exist in the rootfs (same trap as the DSH
        // role in buildProotEnv — npm does mkdtemp on it too).
        val npmScript = buildString {
            append("export TMPDIR=/tmp TMP=/tmp TEMP=/tmp; ")
            append("rm -rf '$stage' '$tarPath'; ")
            append("mkdir -p '$stage'; ")
            append("printf '%s' '$pkgJson' > '$stage/package.json'; ")
            append("npm install --prefix '$stage' '$pkgSpec' --registry '$registryUrl' --no-audit --no-fund --loglevel=notice")
        }
        try {
            onStage(UiText.Res(R.string.npm_stage_pulling, listOf(version, registryUrl)))
            when (val r = runGuestCommand(npmScript, onLog, onProcess, shouldAbort)) {
                is AppResult.Failure -> return@withContext AppResult.Failure(
                    AppError("DSH_NPM_INSTALL_FAILED", "npm install failed: ${r.error.message} (see log)",
                        userMessage = UiText.Res(R.string.npm_install_failed, listOf(r.error.message))),
                )
                is AppResult.Success -> Unit
            }
            val stagedBin = File(baseRootfs(), "tmp/dsh-stage/node_modules/@deepseek-ai/dsh/lib/bin.js")
            if (!stagedBin.isFile) {
                return@withContext AppResult.Failure(
                    AppError("DSH_NPM_VERIFY_FAILED", "npm install result missing @deepseek-ai/dsh/lib/bin.js, cannot continue",
                        userMessage = UiText.Res(R.string.npm_verify_failed)),
                )
            }
            onStage(UiText.Res(R.string.npm_stage_packing))
            when (val r = runGuestCommand("tar -C '$stage' -czf '$tarPath' .", onLog, onProcess, shouldAbort)) {
                is AppResult.Failure -> return@withContext AppResult.Failure(
                    AppError("DSH_NPM_PACK_FAILED", "Failed to pack DSH layer: ${r.error.message}",
                        userMessage = UiText.Res(R.string.npm_pack_failed, listOf(r.error.message))),
                )
                is AppResult.Success -> Unit
            }
            val tarFile = File(baseRootfs(), "tmp/dsh-stage.tar.gz")
            if (!tarFile.isFile || tarFile.length() < 1024) {
                return@withContext AppResult.Failure(AppError("DSH_NPM_PACK_FAILED", "Pack result is abnormal, cannot continue",
                    userMessage = UiText.Res(R.string.npm_pack_abnormal)))
            }
            onStage(UiText.Res(R.string.npm_stage_installing, listOf(version)))
            val result = updateDsh(tarFile, null, version, allowDowngrade)
            if (result is AppResult.Success) onStage(UiText.Res(R.string.npm_stage_done))
            return@withContext result
        } finally {
            // Best-effort cleanup on every path (host side + guest side).
            runCatching {
                File(baseRootfs(), "tmp/dsh-stage").deleteRecursively()
                File(baseRootfs(), "tmp/dsh-stage.tar.gz").deleteRecursively()
            }
            runCatching { runGuestCommand("rm -rf '$stage' '$tarPath'", onLine = {}) }
        }
    }

    private fun createDirectories() {
        listOf(
            config.runtimeDir,
            config.sandboxDir,
            config.userDataDir,
            config.logsDir,
            config.backupsDir,
            config.updatesDir,
        ).forEach { it.mkdirs() }
    }

    private fun runtimeCurrentDir(): File = File(config.runtimeDir, "runtime-current")

    private fun prootBinary(): File {
        val bundled = config.nativeLibraryDir?.let { File(it, "libproot.so") }
        if (bundled?.isFile == true) return bundled
        return File(runtimeCurrentDir(), "android-side/bin/proot")
    }

    private fun prootLibDir(): File {
        val bundled = config.nativeLibraryDir?.let { File(it) }
        if (bundled != null && File(bundled, "libandroid-shmem.so").isFile) return bundled
        return File(runtimeCurrentDir(), "android-side/lib")
    }

    private fun prootLoaderFile(): File {
        val bundled = config.nativeLibraryDir?.let { File(it, "libproot-loader.so") }
        if (bundled?.isFile == true) return bundled
        return File(runtimeCurrentDir(), "android-side/libexec/proot/loader")
    }

    private fun baseRootfs(): File = File(runtimeCurrentDir(), "base")
    private fun nodeLayerDir(): File = File(runtimeCurrentDir(), "node")
    private fun dshLayerDir(): File = File(runtimeCurrentDir(), "dsh")

    /**
     * 宿主侧垫片目录，bind 到 guest `/opt/dshbox` 供 DSH 以 `--import` 预加载。
     *
     * 仅当垫片文件**确实存在**时返回路径：`--import` 指向不存在的文件会让 Node
     * 直接报错退出，那会把「垫片缺失」放大成「DSH 起不来」。返回 null 时命令里
     * 不带绑定与预加载，DSH 退化为上游原始行为（硬链接失败的老问题会复现，
     * 但服务本身可启动）——这比彻底启动失败更可取。
     */
    /**
     * 传给 dsh 的 `--patch` 层：逐个按"文件存在且非空"追加（`--patch` 可重复）。
     *
     * 四个独立的层，分开是为了互不覆盖 —— 共用一份文件时，一方的回滚会把另一方的停用行抹掉：
     *  - dsh-official-plugin：预置开启上游默认关闭的官方条目（如侧边栏浏览器）；**只由宿主写**；
     *  - plugin-market：停用行（插件市场与安全模式共写）；**只由该模块写**；
     *  - safe-mode：绝对安全模式独占（关闭 = 删文件）；
     *  - webserver-port：本机 DSH 端口自适应（host/port）；**只由宿主写**，落在
     *    user-data 下 `.dsh/dshbox/webserver-port/`（guest 绑定事务一映射，见
     *    [DshWebserverOverlay] 类 KDoc）。四个层各用一份文件，文件级隔离，
     *    谁也不会挤掉谁。
     *
     * 顺序即优先级：后追加的层覆盖前层，因此 absolute 排最后；webserver-port
     * 层只覆盖它自己的 host/port 键（唯一写 host/port 的层），位置与三层互不干扰。
     *
     * 不存在时**不传**：传一个不存在的 patch 会让 dsh 启动直接失败，那比"少几个条目"严重得多。
     */
    private fun dshOverlayGuestPaths(): List<String> {
        // webserver-port 层与既有三层一样在 user-data 下（.dsh/dshbox/webserver-port/），
        // guest 侧 `/root/projects` 绑定的正是这一目录，路径一映射可达。
        //
        // P2-1：gecko 变体（端口自适应关闭）**不消费**遗留层——同一 applicationId
        // 从 systemWebView 切装 gecko 后，user-data 里残留的非 3080 覆盖层会把
        // DSH 绑到非 3080 而 gecko 侧固化 3080。准入判定收口在伴生对象的
        // [webserverOverlayConsumable]；关闭时不但不传，还清理宿主侧旧文件
        // （清理失败不阻塞启动，overlay 已不传入即不会消费）。
        val instance = activeDshInstance()
        val webserverFile = DshWebserverOverlay.hostFile(config.userDataDir, instance)
        val webserverPresent = webserverFile.isFile && webserverFile.length() > 0L
        val webserverGuest = Constants.dshWebserverOverlayGuestPath(instance)
            .takeIf { webserverOverlayConsumable(config.dshPortAdaptationEnabled, webserverPresent) }
        if (webserverGuest == null && !config.dshPortAdaptationEnabled && webserverFile.isFile) {
            runCatching {
                webserverFile.delete()
                Log.i(TAG, "stale webserver-port overlay removed (port adaptation disabled)")
            }.onFailure { Log.w(TAG, "stale webserver-port overlay delete failed: ${it.message}") }
        }
        return listOf(
            Constants.dshOfficialPluginOverlayRelativePath(instance) to
                Constants.dshOfficialPluginOverlayGuestPath(instance),
            Constants.dshPluginMarketOverlayRelativePath(instance) to
                Constants.dshPluginMarketOverlayGuestPath(instance),
            Constants.dshSafeModeAbsoluteOverlayRelativePath(instance) to
                Constants.dshSafeModeAbsoluteOverlayGuestPath(instance),
        ).mapNotNull { (relative, guest) ->
            val overlay = File(config.userDataDir, relative)
            guest.takeIf { overlay.isFile && overlay.length() > 0L }
        } + listOfNotNull(webserverGuest)
    }

    private fun linkShimHostDir(): String? =
        config.dshShimDir.absolutePath.takeIf { config.dshShimFile.isFile }

    /** 手机助手暴露给 guest 的宿主目录（`/opt/pilot`）；目录为空只表示 guest 还没拿到入口脚本。 */
    private fun pilotHostDir(): String = config.pilotEntryDir.apply { mkdirs() }.absolutePath

    /** Optional Browser CLI tree; the app bridge owns its contents and lifecycle. */
    private fun browserHostDir(): String? = config.browserEntryDir
        .takeIf { it.isDirectory }
        ?.absolutePath

    /**
     * 共享区绑定清单（guest 别名 → 宿主目录）：内建 DSHBox + 用户挂载，目录缺失的跳过；
     * 三处 PRoot 启动与 guest 命令共用同一取值口径。
     *
     * 同时预建 base 镜像挂载点 `<base>/root/share/<别名>`：PRoot 对多级新挂载点的
     * 建目录行为不作依赖（内建与挂载目录的父级 `/root/share` 同理）。
     */
    private fun shareAreaBinds(): List<Pair<String, File>> {
        val binds = config.shareBindsProvider()
            .filter { (name, dir) -> name.isNotBlank() && dir.isDirectory }
        if (binds.isNotEmpty()) {
            val guestShareRoot = File(File(baseRootfs(), "root"), "share").apply { mkdirs() }
            // 清理不在当前清单内的空别名目录（取消挂载后的残留，避免 guest 里留下空壳）。
            val keep = binds.mapTo(HashSet()) { it.first }
            guestShareRoot.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name !in keep && child.listFiles()?.isEmpty() == true) {
                    child.delete()
                }
            }
            binds.forEach { (name, _) -> File(guestShareRoot, name).mkdirs() }
        }
        return binds
    }

    /** 活跃 DSH 实例：启动期现取，DSH_HOME 与 home 相对路径随槽切换。 */
    private fun activeDshInstance(): DshInstance = config.activeDshInstanceProvider()

    /**
     * 构造网页端 DSH 与 guest 命令的 guest PATH。
     *
     * 能力模块追加的目录在最前（能力命令优先命中）；
     * CLI 包装脚本目录其后（`dsh` 等命令由此命中）。
     */
    private fun buildGuestPathForDsh(): String {
        val head = "${Constants.dshboxAssetsGuestDir(activeDshInstance())}/bin"
        val extra = config.extraPathEntriesProvider().filter { it.isNotBlank() }
        val tail = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        // CLI 包装脚本目录保持首位（android-root 等 shim 在此，不被能力目录遮蔽），
        // 能力模块追加的目录其后。
        return (listOf(head) + extra).joinToString(":") + ":" + tail
    }

    /**
     * Assembles the host-process env for a proot role by sourcing the layered
     * `.dshbox/env.d/<layer>.sh` fragments in profile assembly order (L0 base ->
     * L1 node -> L3 android-side) and substituting the runtime path placeholders.
     *
     * Android-side declares LD_LIBRARY_PATH / PROOT_LOADER / PROOT_TMP_DIR (host
     * vars proot needs); base declares guest vars (HOME/TERM/LANG/PATH/DSH_
     * PERMISSION_MODE) that the process inherits and the guest start scripts
     * already re-export. DSH (L2) is a separate product installed at
     * runtime-current/dsh and contributes its env only when present.
     */
    private fun buildProotEnv(runtimeDir: File, role: String): Map<String, String> {
        val tmpDir = File(runtimeDir, "tmp/$role").apply { mkdirs() }
        val base = mutableMapOf(
            "LD_LIBRARY_PATH" to prootLibDir().absolutePath,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "PROOT_LOADER" to prootLoaderFile().absolutePath,
        )
        val profile = runtimeProfile() ?: return base
        val substitutions = mapOf(
            "@PROOT_LIB@" to prootLibDir().absolutePath,
            "@PROOT_LOADER@" to prootLoaderFile().absolutePath,
            "@PROOT_TMP_DIR@" to tmpDir.absolutePath,
            "@HOME@" to "/root",
            "@TERM@" to "xterm-256color",
            "@PATH@" to buildGuestPathForDsh(),
            "@NODE_BIN@" to "/usr/local/bin/node",
            "@DSH_PERMISSION_MODE@" to "danger-full-access",
            "@DSH_BIN@" to "/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "@DSH_HOME@" to activeDshInstance().guestHome,
        )
        for (layerName in profile.assembly) {
            val layer = profile.layer(layerName) ?: continue
            val layerDir = when (layer.name) {
                "base" -> baseRootfs()
                "node" -> nodeLayerDir()
                "android-side" -> prootSideDir()
                "dsh" -> dshLayerDir()
                else -> continue
            }
            val envFile = File(layerDir, layer.envFile)
            if (!envFile.isFile) continue
            val src = try {
                envFile.readText()
            } catch (t: Throwable) {
                continue
            }
            val resolved = substitutionExports(src, substitutions)
            parseEnvExports(resolved).forEach { (k, v) -> base[k] = v }
        }
        // 宿主进程的 TMPDIR 指向 Android 缓存目录，该路径在 guest 内不存在：
        // DSH 的 dsh-spill-local 会在启动时 mkdtemp(TMPDIR) 失败；adb 客户端也会把
        // server 日志写到 `$TMPDIR/adb.<uid>.log`，写入失败会导致 server 无法启动
        // （输出为 cannot connect to daemon）。统一指向 guest 的 /tmp，对所有角色生效。
        base["TMPDIR"] = "/tmp"
        base["TMP"] = "/tmp"
        base["TEMP"] = "/tmp"
        // DSH (L2) is a separate product not present in runtime-profile.assembly;
        // set its guest env explicitly for the DSH PRoot role. DSH_HOME points at
        // the app-managed user data (bound at /root/projects) so the DSH web server
        // serves the app's WebView / health endpoint (default port 3080).
        if (role == "dsh") {
            base["DSH_HOME"] = activeDshInstance().guestHome
            // PORT 仅作旧版 DSH 的兜底通道：真实端口以 webserver 覆盖层请求 +
            // 启动输出确证为准。本次请求值尚未确证时保持默认。
            base["PORT"] = (nextDshLaunchPass ?: Constants.DSH_DEFAULT_PORT).toString()
            // xdg-open 垫片挂载在 /opt/dshbox（与硬链接垫片同目录）。DSH 的
            // openNativeTextFile 按 PATH 查找 xdg-open，base 层没有它——把挂载点
            // 放到 PATH 首位，垫片即可被找到；其余查找顺序保持 base 层默认。
            // dsh 自身的子进程也要能找到我们的命令垫片（pnpm/dsh/pkg，见
            // `stagedBinDir`，bootstrap 落在 `/root/projects/.dsh/dshbox/bin`）：
            // 插件管理器 execa("pnpm") 只按 PATH 盲找，PATH 里没有它就是
            // spawn ENOENT。挂次位既不放它压过 xdg-open，也不重建 base 层。
            //
            // 能力模块追加的目录（`/opt/elevation/bin`）必须**在此处**并入：base 层
            // env 不提供 PATH（已在设备上确认），`@PATH@` 的替换值未被消费，本分支实际
            // 走的是下面的兜底串。只改替换表（buildGuestPathForDsh）会让 DSH 里
            // `command -v adb` 为空。
            val extraPath = config.extraPathEntriesProvider().filter { it.isNotBlank() }
            // CLI 包装脚本目录（pnpm/dsh/pkg）在能力目录之前：能力命令不遮蔽既有 shim。
            val ambientPath = base["PATH"]?.takeIf { it.isNotBlank() }
                ?: "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            base["PATH"] = (listOf(
                Constants.DSH_LINK_SHIM_GUEST_DIR,
                "${Constants.dshboxAssetsGuestDir(activeDshInstance())}/bin",
            ) + extraPath + ambientPath.split(':'))
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(":")
            // The guest /etc/passwd has no entry for the runtime uid (PRoot runs
            // as the Android app uid), so Node's os.userInfo() fails with ENOENT
            // from libuv getpwuid_r. The DSH web terminal resolves its shell via
            // `process.env.SHELL || userInfo()`, so a non-empty SHELL short-
            // circuits the lookup and the terminal starts without touching
            // /etc/passwd.
            base["SHELL"] = "/bin/bash"
        }
        return base
    }

    /** Resolves @PLACEHOLDER@ tokens with [substitutions]. */
    private fun substitutionExports(source: String, substitutions: Map<String, String>): String {
        var out = source
        for ((k, v) in substitutions) out = out.replace(k, v)
        return out
    }

    /** Extracts KEY=VALUE from a bash env.d fragment ("export KEY=VALUE" or "KEY=VALUE"). */
    private fun parseEnvExports(source: String): Map<String, String> =
        source.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val body = line.removePrefix("export ").trim()
                val eq = body.indexOf('=')
                if (eq > 0) {
                    val k = body.substring(0, eq).trim()
                    val v = body.substring(eq + 1).trim().trim('"').trim('\'')
                    if (k.isNotEmpty()) k to v else null
                } else null
            }
            .toMap()

    private fun runtimeProfile(): RuntimeProfile? {
        val f = File(runtimeCurrentDir(), "runtime-profile.json")
        return if (f.isFile) RuntimeProfile.parse(f) else null
    }

    /** Android-side proot host dir (L3). */
    private fun prootSideDir(): File = File(runtimeCurrentDir(), "android-side")

    private fun ensureRuntimePresent() {
        check(prootBinary().isFile) { "PRoot binary not found: ${prootBinary().absolutePath}" }
        check(baseRootfs().isDirectory) { "base layer not found: ${baseRootfs().absolutePath}" }
        // 所有 proot guest 入口现在都直接 exec rootfs 内的 bash（见
        // SandboxProcessRunner.buildProotDshCommand），不再经宿主 /system/bin/sh。
        // 若 base 损坏或解包不完整导致 bash 缺失，proot 会在 execve 时立即退出，
        // 而 startSandbox() 随后仍会把状态置为 RUNNING——UI 显示运行中但进程已死。
        // 这里提前失败，让状态落到 ERROR 并给出明确原因。
        //
        // 判定必须与入口使用的路径完全一致：入口由 GUEST_BASH_RELATIVE 派生，
        // 校验使用同一个常量。接受 bin/bash 会造成「校验放行、启动即死」的错位。
        //
        // x 位先尝试自愈再判失败：层的落盘若走了 copyRecursively 兜底（不保留 POSIX
        // 权限），整层会丢 x 位，但那只是权限问题、文件本身完好。这里补一次
        // setExecutable 能救回这类既有安装，不至于让用户因一个 chmod 而从零重装。
        val guestBash = File(baseRootfs(), GUEST_BASH_RELATIVE)
        if (guestBash.isFile && !guestBash.canExecute()) {
            runCatching { guestBash.setExecutable(true, false) }
        }
        check(guestBash.isFile && guestBash.canExecute()) {
            "guest bash not usable under base layer: ${guestBash.absolutePath} " +
                "(expected $GUEST_BASH_RELATIVE, must be an executable regular file)"
        }
        ensureGuestResolvConf()
    }

    /**
     * Rootfs images built inside WSL ship a WSL-generated /etc/resolv.conf
     * (nameserver 10.255.255.254) that is unreachable on Android, so DSH
     * cannot resolve api.deepseek.com and every model request fails with
     * "DeepSeek API request ... failed". Rewrite it with public resolvers
     * when it is missing, points at an unreachable address, or contains WSL
     * markers; re-running an import restores the broken file, hence this is
     * checked on every relevant start.
     */
    private fun ensureGuestResolvConf() {
        val resolv = File(baseRootfs(), "etc/resolv.conf")
        val broken = !resolv.isFile || runCatching { resolv.readText() }.getOrDefault("")
            .let { it.contains("10.255.255.254") || it.contains("wsl") || it.contains("nameserver") && !it.contains("114.114.114.114") && !it.contains("8.8.8.8") && !it.contains("223.5.5.5") }
        if (broken) {
            runCatching {
                resolv.parentFile?.mkdirs()
                resolv.writeText(
                    "# Rewritten by DSHapp: WSL-generated resolv.conf is unreachable on Android.\n" +
                        "nameserver 114.114.114.114\n" +
                        "nameserver 8.8.8.8\n" +
                        "nameserver 223.5.5.5\n",
                )
                Log.i(TAG, "guest /etc/resolv.conf rewritten for Android networking")
            }.onFailure { Log.w(TAG, "rewrite resolv.conf failed: ${it.message}") }
        }
    }

    private fun isDshProcessAlive(): Boolean = dshProcess?.process?.isAlive == true

    /**
     * 从 DSH 进程原始输出同时解析进程级 launchToken 与**实际端口**
     * （以输出为准，是 READY 确证的前提）；该行必须属于代次 [lineGeneration]
     * 的那轮启动。DSH 0.1.2-rc.1 启动打印 `dsh web: http://host:port/?token=…`；
     * token 仅存在于进程内存（不落盘），官方 printUrl 输出是宿主集成的唯一
     * 入口。在进程内再次启动时 token 会刷新，但 WebView 的签名 cookie 持久
     * 有效，首次交换后无需重取；仅在本进程尚未持有 token 时解析（DSH 重启
     * 更新已过期的场景由 WebView 401 重载配合）。端口则**每次确证都更新**：
     * 输出与请求值不一致以输出为准，端口变化经 [dshBaseUrl] 流式送出，
     * WebView/健康检查/界面按现值跟随。
     *
     * 输出确证的地址优先于探测请求值（`DshWebserverOverlay` 只决定 DSH 侧
     * bind 的"请求"，竞争或旧版 DSH 忽略 patch 时输出仍为真值）。**解出该行
     * 之前 DSH 不进入 READY**：健康循环在地址未确证时只按「不就绪」观察
     * （与 [decideDshHealth] 的 Ready 分支要求 `addressConfirmed` 一致）。
     *
     * 代次校验（P1-1）：[lineGeneration] 由进程起动时的监听器闭包快照
     * （[startDshProotProcess] 捕获 launchGeneration）携带；行到达时与**当前
     * 代次**比对：
     *  - 当前代次 < 0（尚未进入任何轮启动）→ 丢弃；
     *  - 当前代次 != [lineGeneration]（另一轮启动已推进代次）→ 该行是旧
     *    进程停机窗口的迟到输出，丢弃——不得重新填上旧地址/旧 token；
     *  - 相等则放行（本轮进程自己的输出）。
     *
     * internal 仅为 JVM 单测直接构造代次时序；生产走 onRawLine 闭包注入。
     */
    internal fun ingestDshWebLaunchLine(line: String, lineGeneration: Long) {
        val parsed = DshWebLaunchLine.parse(line) ?: return
        val current = launchGeneration
        if (lineGeneration < 0L || lineGeneration != current) {
            Log.w(TAG, "dsh web launch line dropped (stale generation=$lineGeneration current=$current)")
            return
        }

        // 修正)：DSH 进程重启会生成新 launchToken，旧值随即失效——
        // 每次捕获都更新（不因已持有旧值而跳过），WebView 侧随 StateFlow 变化
        // 自动用新 token 重载，避免 401 + ERR_HTTP_RESPONSE_CODE_FAILURE。
        _dshLaunchToken.value = parsed.token

        val baseUrl = "http://${Constants.DSH_DEFAULT_HOST}:${parsed.port}"
        if (_dshBaseUrl.value != baseUrl) {
            Log.i(TAG, "dsh web address confirmed from output: $baseUrl (requested=${nextDshLaunchPass ?: "n/a"})")
            _dshBaseUrl.value = baseUrl
        }
        // 健康检查跟随实际端口换新——健康循环与手动 healthCheck 都读本引用。
        if (healthCheckerTestable?.port != parsed.port) {
            healthChecker = HttpHealthChecker(Constants.DSH_DEFAULT_HOST, parsed.port)
        }
    }

    /** 空锁恢复由宿主原生助手持有内核写租约执行，不依据写者暂停时间。 */
    private fun sweepDeadWriterLocks() {
        val sweeper = DshWriterLockSweeper(
            workspaceDir = config.userDataDir,
            homeRelativeDir = activeDshInstance().homeLeaf,
            isProcessAlive = { pid -> File("/proc/$pid").exists() },
            selfCheck = { File("/proc/${android.os.Process.myPid()}").exists() },
            recoverEmpty = { lock ->
                val helper = config.nativeLibraryDir?.let { File(it, "libdshbox-lock.so") }
                if (helper?.isFile != true) false else runCatching {
                    val process = ProcessBuilder(helper.absolutePath, "recover-empty", lock.absolutePath).start()
                    if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.exitValue() == 0
                    else { process.destroyForcibly(); false }
                }.getOrDefault(false)
            },
        )
        val result = runCatching { sweeper.sweep() }.getOrNull() ?: return
        val removed = result.removed
        when {
            result.skipped -> Log.i(TAG, "dsh writer-lock sweep skipped: /proc liveness check unavailable")
            removed.isNotEmpty() -> Log.i(
                TAG,
                "dsh writer-lock sweep: removed ${removed.size}/${result.scanned} stale lock(s) " +
                    removed.joinToString(","),
            )
        }
    }

    /**
     * killAll 之后的有界等待：SIGKILL 命中进程到内核真正释放监听端口之间
     * 有一段收尾窗口，期间端口探测会误判占用、启动被偶发地往后挤一格。
     * 轮询 [pollMs]（48ms × 至多 ~10 轮，总体 ≤ 500ms）：
     *  - 首探端口 [firstPort] 立即可绑 → 无需等待；
     *  - /proc 扫描发现**没有任何** DSH 启动脚本进程存活（本轮关心的是
     *    "上一轮孤儿"，节点上不存在其它监听者）→ 提前结束；
     *  - 超时照常返回，探测本身仍以真实 bind（reuse 开）为准。
     *
     * 挂起实现（`delay` 轮询），调用方在后台协程 + [lifecycleMutex] 内，
     * 不阻塞主线程。仅当 [SandboxProcessRunner.killAll] 真的清扫过一次
     * （进入本函数的调用点都刚做过兜底清扫）时才有意义。
     */
    private suspend fun awaitPortsBindableAfterKill(firstPort: Int, pollMs: Long = 48L) {
        if (DshPortProbe.canBindLoopback(firstPort)) {
            // 首个目标端口立即可绑——强杀收尾已完成，无需等待。
            return
        }
        val budgetEndMs = monotonicMs() + 500L
        var waited = 0L
        while (monotonicMs() < budgetEndMs) {
            delay(pollMs)
            waited += pollMs
            if (DshPortProbe.canBindLoopback(firstPort)) {
                Log.i(TAG, "dsh await after kill: port $firstPort bindable after ${waited}ms")
                return
            }
            if (!processRunner.hasAliveProcess(Constants.DSH_START_SCRIPT)) {
                Log.i(TAG, "dsh await after kill: no live dsh process after ${waited}ms")
                return
            }
        }
        Log.i(TAG, "dsh await after kill: budget exhausted (~${waited}ms), proceeding to probe")
    }

    /**
     * Kills the current DSH process tree and launches a fresh one. Used ONLY
     * from the health loop so the loop itself is never cancelled. Returns true
     * on success (a new process is running); false when the relaunch failed
     * (state already set to ERROR).
     */
    private suspend fun restartDshProcessInPlace(): Boolean = lifecycleMutex.withLock {
        dshProcess?.let { processRunner.stop(it) }
        // 健康循环重启同样需要 cmdline 兜底，否则旧树不清时
        // 新进程 EADDRINUSE、循环重启陷入死转。
        runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
        // 与 startDsh 同款：强杀后给内核收尾留一条有界等待（≤~500ms），
        // 垂死监听消失后再探测，避免偶发后挤一格。后台协程 + 锁内，
        // 不阻塞主线程。
        awaitPortsBindableAfterKill(Constants.DSH_DEFAULT_PORT)
        dshProcess = null
        _dshState.value = DshState.STARTING
        // 就地重开放新一轮启动。代次先递增、地址与 token 同步清空——
        // 健康循环以 `_dshBaseUrl != null` 计 addressConfirmed，若只置 STARTING
        // 而不清地址，重启后会拿旧进程留下的确证值直接放行 READY；清空后确
        // 证门只认「本轮启动之后解析到的值」（旧进程停机窗口吐出的最后一行
        // 也因此作废，见 [ingestDshWebLaunchLine] 的代次校验）。
        launchGeneration = ++launchGenerationCounter
        _dshBaseUrl.value = null
        _dshLaunchToken.value = null
        // 就地重启也重新确定端口：跨重启游标在此处推进——上一轮请求
        // 的端口已被证明不可用（进程退出或持续不应答），下一轮探测必须从它的下一个端口
        // 起探。顺序同样"先杀后探"（上面先 stop + killAll 再到下面的 probe）：
        // 探测看不见自己上一轮的监听；探测 reuse 开（与 Node 同口径），
        // TIME_WAIT 残留不再误判占用，被怀疑的端口也不会被钉死。窗口沿剩余
        // 区间裁剪、止于 3099 不回绕。自适应关闭（gecko）固定 3080。
        // 全失败置 ERROR 并返回 false。
        val restartStartPort = advancePortCursorFromLastRequest()
        var launched = false
        try {
            ensureRuntimePresent()
            for (attempt in 0 until Constants.MAX_AUTO_RESTART_ATTEMPTS) {
                // 起始端口推进但不越过探测区间上界（起点 + attempt 再裁上界）：
                // 起点本身就裁到上界以内，窗口也按剩余区间裁剪。
                val firstPort = DshPortProbe.cursorFirstPort(restartStartPort - 1, attempt)
                val firstPortCount = (PROBE_PORT_RANGE_END - firstPort + 1)
                    .coerceAtMost(DshPortProbe.PORT_COUNT)
                val requestedPort = if (config.dshPortAdaptationEnabled) {
                    DshPortProbe.probe(firstPort = firstPort, count = firstPortCount) ?: run {
                        Log.e(TAG, "dsh restart: no free port from $firstPort on attempt $attempt")
                        null
                    }
                } else {
                    Constants.DSH_DEFAULT_PORT
                } ?: continue
                if (config.dshPortAdaptationEnabled) {
                    val wroteOverlay = DshWebserverOverlay.refresh(
                        config.userDataDir, Constants.DSH_DEFAULT_HOST, requestedPort, activeDshInstance(),
                    )
                    if (!wroteOverlay) {
                        Log.e(TAG, "dsh restart attempt=$attempt: webserver overlay write failed")
                        continue
                    }
                }
                nextDshLaunchPass = requestedPort
                if (config.dshPortAdaptationEnabled && healthCheckerTestable?.port != requestedPort) {
                    healthChecker = HttpHealthChecker(Constants.DSH_DEFAULT_HOST, requestedPort)
                }
                Log.i(TAG, "dsh restart attempt=$attempt requestedPort=$requestedPort")
                startDshProotProcess()
                Log.i(TAG, "dsh proot restarted in place (port=$requestedPort)")
                launched = true
                break
            }
            if (!launched) _dshState.value = DshState.ERROR
        } catch (t: Throwable) {
            Log.e(TAG, "dsh restart failed: ${t.message}", t)
            _dshState.value = DshState.ERROR
            launched = false
        }
        launched
    }

    /**
     * Monitors DSH until it leaves STARTING/RUNNING/READY. Handles both the
     * initial readiness wait and post-ready crash recovery with a bounded
     * auto-restart policy ([Constants.MAX_AUTO_RESTART_ATTEMPTS]). Restarts are
     * performed in-place so this loop is never cancelled by its own recovery.
     *
     * 判定规则本身在 [decideDshHealth] 里（纯函数，便于单测覆盖各种时序组合）；本循环只负责
     * 探测、执行动作与记录日志。计时一律用单调时钟，避免系统时间被校正时产生错误差值。
     */
    private fun startDshHealthLoop() {
        dshHealthLoopJob?.cancel()
        dshHealthLoopJob = scope.launch {
            var window = DshWindowState.fresh(monotonicMs())
            // 上一轮迭代结束时刻，用于判断采样是否被中断（循环被冻结或长时间未获调度）。
            var lastIterationEndedAtMs = 0L
            while (_dshState.value == DshState.STARTING ||
                _dshState.value == DshState.RUNNING ||
                _dshState.value == DshState.READY
            ) {
                val iterationStartMs = monotonicMs()
                val health = healthChecker.check()
                val nowMs = monotonicMs()
                val alive = isDshProcessAlive()
                // 就绪必须以"实际地址确证"为前提：从启动输出解析到 `dsh web:`
                // 行（同时拿到端口与 token）之前不进入 READY——端口上有 HTTP
                // 响应还可能是别的服务。未确证期间按首次启动超时等待输出行，
                // 进程退出则由判定策略立即重启（启用端口自适应时换端口）。
                val addressConfirmed = _dshBaseUrl.value != null
                val decision = decideDshHealth(
                    state = window,
                    probe = DshHealthProbe(
                        nowMs = nowMs,
                        webUiReady = health.webUiReady,
                        processAlive = alive,
                        // 设备交互状态取不到时按「交互中」处理：宁可照旧判定，也不让外部回调
                        // 的异常中断整个监测循环——循环一旦退出，DSH 之后就再没有恢复路径了。
                        deviceInteractive = runCatching { isDeviceInteractive() }.getOrDefault(true),
                        probeGapMs = if (lastIterationEndedAtMs == 0L) {
                            0L
                        } else {
                            iterationStartMs - lastIterationEndedAtMs
                        },
                        addressConfirmed = addressConfirmed,
                    ),
                    readyTimeoutMs = config.dshReadyTimeoutMs,
                    unresponsiveGraceMs = config.dshUnresponsiveGraceMs,
                    maxRestartAttempts = Constants.MAX_AUTO_RESTART_ATTEMPTS,
                )
                // 触发时的窗口时长（迁移前取值）：重启日志要报告「它不应答了多久」，
                // 迁移后窗口已清空，取值会变成 0。
                val unresponsiveForMsBefore = window.unresponsiveSinceMs?.let { nowMs - it } ?: 0L
                window = decision.next
                val unresponsiveForMs = window.unresponsiveSinceMs?.let { nowMs - it } ?: 0L
                // 展示层提示：连续不应答达阈值才置位，任何一次成功立即复位（与重启判定无关）。
                _dshUnresponsive.value = !health.webUiReady &&
                    unresponsiveForMs >= DshHealthPolicy.UNRESPONSIVE_HINT_MS
                when (decision) {
                    is DshHealthDecision.Ready -> {
                        _dshRecoverySuggested.value = false
                        _dshState.value = DshState.READY
                    }
                    is DshHealthDecision.Observe -> Unit
                    is DshHealthDecision.Restart -> {
                        Log.i(
                            TAG,
                            "dsh health: auto-restart attempt ${window.restartAttempts}, " +
                                "alive=$alive portOpen=${health.portOpen} unresponsiveFor=${unresponsiveForMsBefore}ms",
                        )
                        if (!restartDshProcessInPlace()) return@launch
                    }
                    is DshHealthDecision.InitialTimeout -> {
                        // Initial startup gets the full configured timeout; do not
                        // give up after only a few fast probe failures.
                        Log.w(TAG, "dsh health: initial start timed out, alive=$alive portOpen=${health.portOpen}")
                        teardownDshOnGiveUp()
                        _dshRecoverySuggested.value = true
                        _dshState.value = DshState.ERROR
                        return@launch
                    }
                    is DshHealthDecision.Exhausted -> {
                        // 预算用尽前再确认一次：句柄仍存活且端口已恢复应答、**且本轮
                        // 地址已确证**——三者同时成立才说明实例真的只是判定窗口内
                        // 短暂不应答，不该销毁一个仍在服务（浏览器在线）的实例。
                        // （P1-2）缺 addressConfirmed 复核的旧口径：重启后地址未重新
                        // 确证（错换端口 / 启动输出未撤离管理面）也能借 alive 复
                        // 位 READY——端口上的 200 可能是**别的服务**应答。确证不成立的
                        // 保留 ERROR 并给出可诊断原因，由用户显式重试。
                        if (alive && healthChecker.check().webUiReady && addressConfirmed) {
                            Log.w(TAG, "dsh health: max auto-restart attempts reached, instance still answers; keeping it")
                            window = window.copy(
                                wasReady = true,
                                restartAttempts = 0,
                                unresponsiveSinceMs = null,
                                healthyStreak = DshHealthPolicy.HEALTHY_STREAK_TO_CLOSE,
                            )
                            _dshRecoverySuggested.value = false
                            _dshState.value = DshState.READY
                        } else {
                            Log.w(
                                TAG,
                                "dsh health: reached max auto-restart attempts, giving up, " +
                                    "alive=$alive portOpen=${health.portOpen} addressConfirmed=$addressConfirmed",
                            )
                            teardownDshOnGiveUp()
                            _dshRecoverySuggested.value = true
                            _dshState.value = DshState.ERROR
                            return@launch
                        }
                    }
                }
                lastIterationEndedAtMs = monotonicMs()
                delay(2_000L)
            }
        }
    }

    /** 单调毫秒：不受系统时间校正影响（设备休眠期间不推进）。 */
    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L

    /**
     * 放弃或超时退场前清理 DSH 进程（可能占着 3080）；否则 ERROR 状态下真实进程存活，
     * 后续启动会因端口被占而反复失败。
     */
    private fun teardownDshOnGiveUp() {
        dshProcess?.let { processRunner.stop(it) }
        runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
        dshProcess = null
        _dshUnresponsive.value = false
    }

    private inline fun <T> AppResult<T>.map(block: (T) -> Unit): AppResult<Unit> =
        when (this) {
            is AppResult.Success -> {
                block(value); AppResult.Success(Unit)
            }
            is AppResult.Failure -> AppResult.Failure(error)
        }

    companion object {
        private const val TAG = "SandboxManager"

        /**
         * startDsh 等待就绪的额外余量：用于健康循环首次启动超时之后的一个探测节拍
         * （节拍间隔加一轮端口检查、连接与读取超时）及状态发布，使等待通常在循环
         * 给出结论之后才结束。
         */
        private const val READY_WAIT_MARGIN_MS = 15_000L

        /**
         * guest bash 在 base 层内的相对路径（== guest 内绝对路径去前导斜杠）。
         * 启动校验与 proot guest 入口必须共用同一个值，避免出现
         * 「校验放行但入口 execve 失败」的口径分叉。
         */
        const val GUEST_BASH_RELATIVE = "usr/bin/bash"

        fun logSafe(message: String) = LogRedactor.redact(message)

        /**
         * webserver-port 覆盖层是否可消费（P2-1 口径，纯函数便于单测）：
         * 端口自适应启用且文件存在非空时**传入**（可消费）；自适应关闭时无论
         * 文件是否存在一律**不传**——gecko 变体固化 3080，遗留的非 3080 层会
         * 把 DSH 静默绑到别的端口。宿主侧文件清理由调用方另行处理。
         */
        @JvmStatic
        fun webserverOverlayConsumable(dshPortAdaptationEnabled: Boolean, overlayFileExists: Boolean): Boolean =
            dshPortAdaptationEnabled && overlayFileExists
    }
}
