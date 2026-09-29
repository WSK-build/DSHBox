package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
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
    private val healthChecker: SandboxHealthChecker = HttpHealthChecker(config.dshHost, config.dshPort),
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

    private val _dshVersion = MutableStateFlow<String?>(null)
    override val dshVersion: StateFlow<String?> = _dshVersion.asStateFlow()

    private val _dshUpdateProgress = MutableStateFlow<String?>(null)
    override val dshUpdateProgress: StateFlow<String?> = _dshUpdateProgress.asStateFlow()

    // DSH 进程级 launchToken（从 `dsh web:` 原始输出解析，仅内存）。
    private val _dshLaunchToken = MutableStateFlow<String?>(null)
    override val dshLaunchToken: StateFlow<String?> = _dshLaunchToken.asStateFlow()

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
                try {
                    ensureRuntimePresent()
                    // 本进程尚未跟踪任何实例，但上一轮 App 进程可能留下仍占着 3080 的孤儿
                    // dsh（句柄随进程回收一并丢失）。先按 cmdline 兜底清扫，否则新实例会因
                    // 端口被占（EADDRINUSE）启动失败——与停机、就地重启两处相同的兜底。
                    runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
                    // Start 前清掉"持有者已不存在"的写者锁：dsh 的原子写只等 2 秒且
                    // 不会清理别人留下的锁，一次强杀就能让之后的启动全部失败。
                    sweepDeadWriterLocks()
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
                    )
                    val prootEnv = buildProotEnv(runtimeDir, "dsh")
                    Log.i(TAG, "starting dsh proot")
                    dshProcess = processRunner.start(command, tag = "dsh", env = prootEnv, onRawLine = ::ingestDshWebLaunchToken)
                    Log.i(TAG, "dsh proot process started")
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "startDsh failed: ${t.message}", t)
                    _dshState.value = DshState.ERROR
                    false
                }
            }
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
        val startedAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startedAt < config.dshReadyTimeoutMs) {
            when (_dshState.value) {
                DshState.READY -> {
                    return AppResult.Success(
                        DshRuntimeStatus(
                            dshVersion = null,
                            pluginApiVersion = null,
                            baseUrl = "http://${config.dshHost}:${config.dshPort}",
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

    override fun isRuntimeInstalled(): Boolean {
        val proot = prootBinary()
        val base = baseRootfs()
        var installed = proot.isFile && base.isDirectory
        val profile = runtimeProfile()
        if (installed && profile != null && profile.assembly.isNotEmpty()) {
            val broken = verifyLayersBroken(profile)
            if (broken.isNotEmpty()) {
                Log.w(TAG, "isRuntimeInstalled=false: layer integrity broken: $broken")
                installed = false
            }
        }
        Log.i(TAG, "isRuntimeInstalled=$installed proot=${proot.absolutePath} base=${base.absolutePath}")
        return installed
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
        File(dir, "runtime-profile.json").writeText(profile)
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
        if (stagedProfile.isFile) stagedProfile.copyTo(File(runtimeDir, "runtime-profile.json"), overwrite = true)
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
            add("--cwd=/root")
            add("--kill-on-exit")
            add("/system/bin/sh"); add("-c")
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
     * 三个独立的层，分开是为了互不覆盖 —— 共用一份文件时，一方的回滚会把另一方的停用行抹掉：
     *  - dsh-official-plugin：预置开启上游默认关闭的官方条目（如侧边栏浏览器）；**只由宿主写**；
     *  - plugin-market：停用行（插件市场与安全模式共写）；**只由该模块写**；
     *  - safe-mode：绝对安全模式独占（关闭 = 删文件）。
     *
     * 顺序即优先级：后追加的层覆盖前层，因此 absolute 排最后。
     *
     * 不存在时**不传**：传一个不存在的 patch 会让 dsh 启动直接失败，那比"少几个条目"严重得多。
     */
    private fun dshOverlayGuestPaths(): List<String> {
        return listOf(
            Constants.DSH_OFFICIAL_PLUGIN_OVERLAY_RELATIVE_PATH to
                Constants.DSH_OFFICIAL_PLUGIN_OVERLAY_GUEST_PATH,
            Constants.DSH_PLUGIN_MARKET_OVERLAY_RELATIVE_PATH to Constants.DSH_PLUGIN_MARKET_OVERLAY_GUEST_PATH,
            Constants.DSH_SAFE_MODE_ABSOLUTE_OVERLAY_RELATIVE_PATH to
                Constants.DSH_SAFE_MODE_ABSOLUTE_OVERLAY_GUEST_PATH,
        ).mapNotNull { (relative, guest) ->
            val overlay = File(config.userDataDir, relative)
            guest.takeIf { overlay.isFile && overlay.length() > 0L }
        }
    }

    private fun linkShimHostDir(): String? =
        config.dshShimDir.absolutePath.takeIf { config.dshShimFile.isFile }

    /** 手机助手暴露给 guest 的宿主目录（`/opt/pilot`）；目录为空只表示 guest 还没拿到入口脚本。 */
    private fun pilotHostDir(): String = config.pilotEntryDir.apply { mkdirs() }.absolutePath

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
            "@PATH@" to "${Constants.DSHBOX_ASSETS_GUEST_DIR}/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "@NODE_BIN@" to "/usr/local/bin/node",
            "@DSH_PERMISSION_MODE@" to "danger-full-access",
            "@DSH_BIN@" to "/opt/dshapp/runtime/node_modules/@deepseek-ai/dsh/lib/bin.js",
            "@DSH_HOME@" to "/root/projects/.dsh",
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
        // DSH (L2) is a separate product not present in runtime-profile.assembly;
        // set its guest env explicitly for the DSH PRoot role. DSH_HOME points at
        // the app-managed user data (bound at /root/projects) so the DSH web server
        // serves the app's WebView / health endpoint (default port 3080).
        if (role == "dsh") {
            base["DSH_HOME"] = "/root/projects/.dsh"
            base["PORT"] = Constants.DSH_DEFAULT_PORT.toString()
            // The node guest inherits TMPDIR from the Android app process (the
            // app cache dir), which does not exist inside this PRoot rootfs; the
            // DSH app's dsh-spill-local does mkdtemp(TMPDIR) on boot and aborts
            // with ENOENT. Point the guest at /tmp so it succeeds.
            base["TMPDIR"] = "/tmp"
            base["TMP"] = "/tmp"
            base["TEMP"] = "/tmp"
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
     * 从 DSH 进程原始输出解析进程级 launchToken。DSH 0.1.2-rc.1
     * 启动打印 `dsh web: http://host:port/?token=<随机值>`；token 仅存在于进程
     * 内存（不落盘），官方 printUrl 输出是宿主集成的唯一入口。在进程内再次启动
     * 时 token 会刷新，但 WebView 的签名 cookie 持久有效，首次交换后无需重取；
     * 仅在本进程尚未持有 token 时解析（DSH 重启更新已过期的场景由 WebView 401
     * 重载配合）。
     */
    private fun ingestDshWebLaunchToken(line: String) {
        val marker = "dsh web: http"
        val idx = line.indexOf(marker)
        if (idx < 0) return
        val tokenAt = line.indexOf("token=", idx)
        if (tokenAt < 0) return
        val start = tokenAt + "token=".length
        val end = line.indexOf('&', start).let { if (it < 0) line.length else it }
        val token = line.substring(start, end).trim().takeIf { it.isNotEmpty() }
        if (token != null) {
            // 修正)：DSH 进程重启会生成新 launchToken，旧值随即失效——
            // 每次捕获都更新（不因已持有旧值而跳过），WebView 侧随 StateFlow 变化
            // 自动用新 token 重载，避免 401 + ERR_HTTP_RESPONSE_CODE_FAILURE。
            _dshLaunchToken.value = token
            Log.i(TAG, "dsh web launch token refreshed")
        }
    }

    /**
     * Kills the current DSH process tree and launches a fresh one. Used ONLY
     * from the health loop so the loop itself is never cancelled. Returns true
     * on success (a new process is running); false when the relaunch failed
     * (state already set to ERROR).
     */
    /**
     * 清理 `.dsh` 下持有者已不存在的 `*.lock`。
     *
     * 判定与纪律见 [DshWriterLockSweeper]；这里只提供真实环境的两件事：
     * `/proc/<pid>` 存活判定，以及该判定本身是否可用（用本进程 PID 自检）。
     */
    private fun sweepDeadWriterLocks() {
        val sweeper = DshWriterLockSweeper(
            workspaceDir = config.userDataDir,
            isProcessAlive = { pid -> File("/proc/$pid").exists() },
            selfCheck = { File("/proc/${android.os.Process.myPid()}").exists() },
        )
        val result = runCatching { sweeper.sweep() }.getOrNull() ?: return
        when {
            result.skipped -> Log.i(TAG, "dsh writer-lock sweep skipped: /proc liveness check unavailable")
            result.removed.isNotEmpty() -> Log.i(
                TAG,
                "dsh writer-lock sweep: removed ${result.removed.size}/${result.scanned} stale lock(s) " +
                    result.removed.joinToString(","),
            )
        }
    }

    private suspend fun restartDshProcessInPlace(): Boolean = lifecycleMutex.withLock {
        dshProcess?.let { processRunner.stop(it) }
        // 健康循环重启同样需要 cmdline 兜底，否则旧树不清时
        // 新进程 EADDRINUSE、循环重启陷入死转。
        runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
        dshProcess = null
        _dshState.value = DshState.STARTING
        try {
            ensureRuntimePresent()
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
            )
            val prootEnv = buildProotEnv(runtimeDir, "dsh")
            dshProcess = processRunner.start(command, tag = "dsh", env = prootEnv, onRawLine = ::ingestDshWebLaunchToken)
            Log.i(TAG, "dsh proot restarted in place")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "dsh restart failed: ${t.message}", t)
            _dshState.value = DshState.ERROR
            false
        }
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
                    is DshHealthDecision.Ready -> _dshState.value = DshState.READY
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
                        _dshState.value = DshState.ERROR
                        return@launch
                    }
                    is DshHealthDecision.Exhausted -> {
                        // 预算用尽前再确认一次：句柄仍存活且端口已恢复应答，说明实例只是
                        // 在判定窗口内不应答，不该销毁一个仍在服务（浏览器在线）的实例。
                        if (alive && healthChecker.check().webUiReady) {
                            Log.w(TAG, "dsh health: max auto-restart attempts reached, instance still answers; keeping it")
                            window = window.copy(
                                wasReady = true,
                                restartAttempts = 0,
                                unresponsiveSinceMs = null,
                                healthyStreak = DshHealthPolicy.HEALTHY_STREAK_TO_CLOSE,
                            )
                            _dshState.value = DshState.READY
                        } else {
                            Log.w(
                                TAG,
                                "dsh health: reached max auto-restart attempts, giving up, " +
                                    "alive=$alive portOpen=${health.portOpen}",
                            )
                            teardownDshOnGiveUp()
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
        fun logSafe(message: String) = LogRedactor.redact(message)
    }
}
