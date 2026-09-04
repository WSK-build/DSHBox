package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import com.dshbox.app.common.LogRedactor
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
) : SandboxManager {

    private val bundleManager = BundleManager(config)
    private val processRunner = SandboxProcessRunner(config)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _sandboxState = MutableStateFlow(SandboxState.UNINITIALIZED)
    override val sandboxState: StateFlow<SandboxState> = _sandboxState.asStateFlow()

    private val _dshState = MutableStateFlow(DshState.UNINITIALIZED)
    override val dshState: StateFlow<DshState> = _dshState.asStateFlow()

    private val _dshVersion = MutableStateFlow<String?>(null)
    override val dshVersion: StateFlow<String?> = _dshVersion.asStateFlow()

    private val _dshUpdateProgress = MutableStateFlow<String?>(null)
    override val dshUpdateProgress: StateFlow<String?> = _dshUpdateProgress.asStateFlow()

    // 1.1.1 (M10)：DSH 进程级 launchToken（从 `dsh web:` 原始输出解析，仅内存）。
    private val _dshLaunchToken = MutableStateFlow<String?>(null)
    override val dshLaunchToken: StateFlow<String?> = _dshLaunchToken.asStateFlow()

    private val dshLayer = DshLayer(runtimeCurrentDir(), bundleManager)

    private val lifecycleMutex = Mutex()
    @Volatile
    private var dshHealthLoopJob: Job? = null
    @Volatile
    private var restartAttempts = 0
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
        // 1.1.1 (M5)：一次性迁移——旧实现把 npm 下载缓存留在 base/root/.npm
        // （运行环境本体红线区，清理功能清不到，实测膨胀 446MB）。此后 npm 缓存
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
                // 1.1.1 (M8)：同上——句柄丢失时按 cmdline marker 兜底清扫 DSH 树。
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
                    message = "沙箱未运行，请先启动 Debian 沙箱",
                    recoverable = true,
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
                    val runtimeDir = runtimeCurrentDir()
                    val command = processRunner.buildProotDshCommand(
                        prootBinary = prootBinary().absolutePath,
                        rootfsDir = baseRootfs().absolutePath,
                        workspaceBind = config.userDataDir.absolutePath,
                        nodeDir = nodeLayerDir().takeIf { it.isDirectory }?.absolutePath,
                        dshDir = dshLayerDir().takeIf { it.isDirectory }?.absolutePath,
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
            restartAttempts = 0
            // Fresh process: always (re)create the health loop. Any stale loop
            // from a previous session is cancelled here.
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
                    return AppResult.Failure(AppError("DSH_NOT_READY", "DSH 未能在限定时间内就绪"))
                }
                DshState.STOPPED -> {
                    return AppResult.Failure(
                        AppError(
                            "DSH_STOPPED",
                            "DSH 已被停止，请稍后重试",
                            recoverable = true,
                        ),
                    )
                }
                else -> Unit
            }
            delay(500L)
        }
        return AppResult.Failure(AppError("DSH_NOT_READY", "DSH 就绪超时"))
    }

    override suspend fun stopDsh() = lifecycleMutex.withLock { stopDshLocked() }

    /** Body of [stopDsh] for callers that already hold [lifecycleMutex] (avoids re-entrant lock). */
    private suspend fun stopDshLocked() {
        Log.i(TAG, "stopDsh(): dshProcess=${dshProcess != null}")
        dshHealthLoopJob?.cancel()
        dshHealthLoopJob = null
        dshProcess?.let { processRunner.stop(it) }
        // 1.1.1 (M8)：仅靠 dshProcess 句柄不可靠——真机实证句柄为 null 时旧 DSH
        // proot 继续存活、占着 3080，换层后新 DSH 反复 EADDRINUSE 起不来。
        // 按 cmdline marker（@deepseek-ai/dsh/lib/bin.js）兜底清扫旧 DSH 进程树，
        // 保证停机路径 3080 必然释放（安装/重启用，含句柄丢失场景）。
        runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
        dshProcess = null
        _dshState.value = DshState.STOPPED
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
     * Offline-import a layered runtime bundle (per plan §2.3) as EITHER:
     *  - a ZIP holding the body layer archives (base/node/android-side <layer>.tar.*
     *    + .sha256 sidecars + runtime-profile.json), flat or under ONE common
     *    top-level folder (Windows 右键压缩文件夹会产生该前缀), OR
     *  - a single tar-family outer package (.tar.gz / .tar.zst / .tar / .tar.bz2 /
     *    .tar.xz by magic) whose contents are EITHER the layered body snapshot
     *    (base/, node/, android-side/, runtime-profile.json) OR — 1.1.0 M12.4 —
     *    the layer archives themselves (tar of archives: base.tar.* etc.), which
     *    is then processed through the same archive-validation pipeline as a zip.
     *
     * 1.1.0 fixes / hardening (MODIFICATION_LOG.md M1/M2):
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
                        return@withLock AppResult.Failure(AppError("BUNDLE_EMPTY", "压缩包内没有文件"))
                    }
                    // Name-level analysis (exact layer matching, common folder prefix,
                    // traversal rejection) lives in the pure, unit-tested RuntimeBundleLayout.
                    val layout = when (val parsed = RuntimeBundleLayout.analyze(fileEntries.map { it.name })) {
                        is RuntimeBundleLayout.Result.Unsafe -> return@withLock AppResult.Failure(
                            AppError("BUNDLE_UNSAFE_PATH", "压缩包包含非法路径（..），已拦截：${parsed.entryName}"),
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
                                AppError("BUNDLE_UNSAFE_PATH", "压缩包路径越界，已拦截：${entry.name}"),
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
                        AppError("BUNDLE_NO_PROFILE", "运行环境包缺少 runtime-profile.json（无法校验层完整性，已拒绝导入）"),
                    ),
                )) {
                    is AppResult.Failure -> return@withLock r
                    is AppResult.Success -> Unit
                }
            } else {
                // 单 tar/gz/zst/裸 tar 外层包：支持两种内容布局（M12.4）——
                //  A) 快照布局：直接是 base/、node/、android-side/ 目录 + runtime-profile.json；
                //  B) 层归档布局（tar of archives）：把官方 zip 的内容（base.tar.* 等层归档 +
                //     profile）原样打成 tar —— 自动按 zip 同款逻辑识别层归档并解压，
                //     消除「tar 里装的是归档文件就报缺少 base 层」的坑。
                when (val r = bundleManager.extractTarGz(source, staging)) {
                    is AppResult.Failure -> return@withLock r
                    is AppResult.Success -> Unit
                }
                if (!File(staging, "runtime-profile.json").isFile) {
                    return@withLock AppResult.Failure(
                        AppError("BUNDLE_NO_PROFILE", "运行环境包缺少 runtime-profile.json（无法校验层完整性，已拒绝导入）"),
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
                                "运行环境包缺少 base 层（快照布局需 base/ 目录；层归档布局需 <layer>.tar[.zst/.gz/.bz2/.xz]）",
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
                return@withLock AppResult.Failure(AppError("BUNDLE_NO_BASE", "运行环境包缺少 base 层"))
            }
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
            Log.i(TAG, "importRuntimeBundle: layered body replaced (base/node/android-side + profile), dsh & user-data untouched")
            return@withLock AppResult.Success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "importRuntimeBundle failed: ${t.message}", t)
            return@withLock AppResult.Failure(AppError("BUNDLE_IMPORT_FAILED", "import failed: ${t.message}"))
        } finally {
            staging.deleteRecursively()
        }
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
     * 共享的「层归档 → 校验 → 解压 → sentinel」步骤（1.1.0 M12.4 从 zip 分支抽出，
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
                AppError("BUNDLE_NO_PROFILE", "运行环境包缺少 runtime-profile.json（无法校验层完整性，已拒绝导入）"),
            )
        }
        val parsedProfile = RuntimeProfile.parse(profileFile)
        if (parsedProfile == null) {
            return AppResult.Failure(AppError("BUNDLE_BAD_PROFILE", "runtime-profile.json 无法解析"))
        }
        for (layer in listOf("base", "node", "android-side")) {
            val arch = archives[layer]
                ?: return AppResult.Failure(
                    AppError("BUNDLE_MISSING_LAYER", "运行环境包缺少 $layer 层归档（<layer>.tar[.zst/.gz/.bz2/.xz]）"),
                )
            val inProfile = parsedProfile.layer(layer)?.sha256?.takeIf { it.isNotBlank() }
            val inSidecar = sidecars[layer]?.takeIf { it.isNotBlank() }
            if (inProfile != null && inSidecar != null && !inProfile.equals(inSidecar, ignoreCase = true)) {
                return AppResult.Failure(
                    AppError("BUNDLE_SHA256_MISMATCH", "层 $layer 的 .sha256 侧车与 runtime-profile.json 声明不一致"),
                )
            }
            val expected = inSidecar ?: inProfile
            if (expected != null && !bundleManager.verifySha256(arch, expected)) {
                return AppResult.Failure(AppError("BUNDLE_SHA256_MISMATCH", "层 $layer SHA-256 校验失败"))
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
        // 1.1.1 (M5)：npm 的默认缓存位置是 ~/.npm（guest HOME=/root）。把它 bind 到宿主
        // cacheDir/npm-cache，下载中间产物不再落 base/root/.npm（运行环境本体红线区、
        // 清理功能清不到，实测曾膨胀 446MB）；缓存归 cacheDir 后随「应用缓存」可一键清理。
        // bind 目标必须是已存在目录（proot 对不存在的 bind 目标会报错）。
        config.npmCacheDir.mkdirs()
        val proot = prootBinary().absolutePath
        val cmd = buildList {
            add(proot)
            add("--rootfs=${baseRootfs().absolutePath}")
            add("--bind=/system"); add("--bind=/apex"); add("--bind=/proc"); add("--bind=/dev")
            add("--bind=${nodeLayerDir().absolutePath}:/usr/local")
            dshLayerDir().takeIf { it.isDirectory }?.let { add("--bind=${it.absolutePath}:/opt/dshapp/runtime") }
            add("--bind=${config.userDataDir.absolutePath}:/root/projects")
            add("--bind=${config.npmCacheDir.absolutePath}:/root/.npm")
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
                // 1.1.1 (M2)：旧条件 `== DshState.RUNNING` 是死代码——状态机只有
                // STARTING/READY/ERROR/STOPPED，RUNNING 从不被赋值，导致换层前
                // 旧 DSH 进程从未被主动停掉（其 proot 树仍持有旧层句柄，一直跑
                // 到 phase 2 restartDsh() 才被终结）。改为停掉全部「在线」态；
                // 1.1.1 (M9)：并纳入 ERROR——误判（健康检查 401 等）遗留的存活
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
     * 1.1.0 (M7): build + install a fresh DSH layer from an npm registry by running
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
        onStage: (String) -> Unit,
        onLog: (String) -> Unit,
        onProcess: (java.lang.Process) -> Unit,
        shouldAbort: () -> Boolean,
    ): AppResult<DshUpdateOutcome> = withContext(Dispatchers.IO) {
        // Both values end up inside `sh -c` — allow only a strict safe charset.
        if (!Regex("^https?://[A-Za-z0-9.:/_%~#?=&+-]+$").matches(registryUrl)) {
            return@withContext AppResult.Failure(AppError("DSH_NPM_BAD_REGISTRY", "registry 地址不合法：$registryUrl"))
        }
        if (!Regex("^[A-Za-z0-9.+-]+$").matches(version)) {
            return@withContext AppResult.Failure(AppError("DSH_NPM_BAD_VERSION", "版本号不合法：$version"))
        }
        val freeBytes = runCatching {
            android.os.StatFs(config.runtimeDir.absolutePath).availableBytes
        }.getOrDefault(Long.MAX_VALUE)
        if (freeBytes < Constants.DSH_INSTALL_MIN_FREE_BYTES) {
            return@withContext AppResult.Failure(
                AppError(
                    "DSH_NPM_LOW_STORAGE",
                    "存储空间不足（需约 1GB 可用，当前仅 ${freeBytes / (1024 * 1024)}MB）",
                ),
            )
        }
        // The guest must be alive for npm.
        if (_sandboxState.value != SandboxState.RUNNING) {
            onStage("正在启动沙箱…")
            startSandbox()
            if (_sandboxState.value != SandboxState.RUNNING) {
                return@withContext AppResult.Failure(AppError("SANDBOX_NOT_RUNNING", "沙箱启动失败，无法执行 npm 安装"))
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
            onStage("正在从 $registryUrl 拉取 @deepseek-ai/dsh $version（含完整依赖，需要几分钟）…")
            when (val r = runGuestCommand(npmScript, onLog, onProcess, shouldAbort)) {
                is AppResult.Failure -> return@withContext AppResult.Failure(
                    AppError("DSH_NPM_INSTALL_FAILED", "npm 安装失败：${r.error.message}（详见日志）"),
                )
                is AppResult.Success -> Unit
            }
            val stagedBin = File(baseRootfs(), "tmp/dsh-stage/node_modules/@deepseek-ai/dsh/lib/bin.js")
            if (!stagedBin.isFile) {
                return@withContext AppResult.Failure(
                    AppError("DSH_NPM_VERIFY_FAILED", "npm 安装结果缺少 @deepseek-ai/dsh/lib/bin.js，无法继续"),
                )
            }
            onStage("正在打包 DSH 层…")
            when (val r = runGuestCommand("tar -C '$stage' -czf '$tarPath' .", onLog, onProcess, shouldAbort)) {
                is AppResult.Failure -> return@withContext AppResult.Failure(
                    AppError("DSH_NPM_PACK_FAILED", "打包 DSH 层失败：${r.error.message}"),
                )
                is AppResult.Success -> Unit
            }
            val tarFile = File(baseRootfs(), "tmp/dsh-stage.tar.gz")
            if (!tarFile.isFile || tarFile.length() < 1024) {
                return@withContext AppResult.Failure(AppError("DSH_NPM_PACK_FAILED", "打包结果异常，无法继续"))
            }
            onStage("正在安装 DSH $version…")
            val result = updateDsh(tarFile, null, version, allowDowngrade)
            if (result is AppResult.Success) onStage("安装完成")
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
            "@PATH@" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
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
     * 1.1.1 (M10)：从 DSH 进程原始输出解析进程级 launchToken。DSH 0.1.2-rc.1
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
            // 1.1.1 (T2 修正)：DSH 进程重启会生成新 launchToken，旧值随即失效——
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
    private suspend fun restartDshProcessInPlace(): Boolean = lifecycleMutex.withLock {
        dshProcess?.let { processRunner.stop(it) }
        // 1.1.1 (M8)：健康循环重启同样需要 cmdline 兜底，否则旧树不清时
        // 新进程 EADDRINUSE、循环重启陷入死转（真机 17:15~17:22 实证）。
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
     */
    private fun startDshHealthLoop() {
        dshHealthLoopJob?.cancel()
        dshHealthLoopJob = scope.launch {
            var startedAt = System.currentTimeMillis()
            var wasReady = false
            while (_dshState.value == DshState.STARTING ||
                _dshState.value == DshState.RUNNING ||
                _dshState.value == DshState.READY
            ) {
                val health = healthChecker.check()
                if (health.webUiReady) {
                    _dshState.value = DshState.READY
                    restartAttempts = 0
                    wasReady = true
                } else if (wasReady || !isDshProcessAlive()) {
                    // DSH dropped after being ready, or the process died before
                    // becoming ready. Bounded auto-restart.
                    restartAttempts++
                    if (restartAttempts >= Constants.MAX_AUTO_RESTART_ATTEMPTS) {
                        Log.w(TAG, "dsh health: reached max auto-restart attempts")
                        // 1.1.1 (M9)：健康循环退场前清理 DSH 进程（可能占着 3080），
                        // 否则 ERROR 状态下真实进程存活，后续启动全部 EADDRINUSE。
                        dshProcess?.let { processRunner.stop(it) }
                        runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
                        dshProcess = null
                        _dshState.value = DshState.ERROR
                        return@launch
                    }
                    Log.i(TAG, "dsh health: auto-restart attempt $restartAttempts")
                    if (restartDshProcessInPlace()) {
                        wasReady = false
                        startedAt = System.currentTimeMillis()
                    } else {
                        return@launch
                    }
                } else if (System.currentTimeMillis() - startedAt > config.dshReadyTimeoutMs) {
                    // Initial startup gets the full configured timeout; do not
                    // give up after only a few fast probe failures.
                    Log.w(TAG, "dsh health: initial start timed out")
                    // 1.1.1 (M9)：同上——退场前清掉仍存活（误判为不健康）的 DSH 进程。
                    dshProcess?.let { processRunner.stop(it) }
                    runCatching { processRunner.killAll(Constants.DSH_START_SCRIPT) }
                    dshProcess = null
                    _dshState.value = DshState.ERROR
                    return@launch
                }
                delay(2_000L)
            }
        }
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
