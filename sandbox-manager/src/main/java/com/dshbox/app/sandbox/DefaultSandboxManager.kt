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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

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
                    dshProcess = processRunner.start(command, tag = "dsh", env = prootEnv)
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
     *    + .sha256 sidecars + runtime-profile.json), OR
     *  - a single .tar.gz / .tar.zst snapshot whose contents are the layered body
     *    (base/, node/, android-side/, runtime-profile.json).
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
                // ZIP: layer archives + sidecars + runtime-profile.json.
                val layerArchives = mutableMapOf<String, File>()
                ZipInputStream(source.inputStream().buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.removePrefix("./")
                            val out = File(staging, name)
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                            val layer = layerNames.firstOrNull { name.startsWith("$it.tar.") }
                            if (layer != null) layerArchives[layer] = out
                        }
                        entry = zip.nextEntry
                    }
                }
                if (layerArchives["base"] == null) {
                    return@withLock AppResult.Failure(AppError("BUNDLE_NO_BASE", "运行环境包缺少 base 层（应为 zip，内含 base/node/android-side .tar.* + runtime-profile.json）"))
                }
                // Verify SHA-256 (if sidecar present) + extract each layer into staging/<layer>.
                for ((name, arch) in layerArchives) {
                    val sidecar = File(staging, "${arch.name}.sha256")
                    val expected = if (sidecar.isFile) sidecar.readText().trim().split(Regex("\\s+")).firstOrNull() else null
                    if (!expected.isNullOrBlank() && !bundleManager.verifySha256(arch, expected)) {
                        return@withLock AppResult.Failure(AppError("BUNDLE_SHA256_MISMATCH", "层 $name SHA-256 校验失败"))
                    }
                    val dest = File(staging, name)
                    when (val r = bundleManager.extractTarGz(arch, dest)) {
                        is AppResult.Failure -> return@withLock r
                        is AppResult.Success -> {
                            if (!expected.isNullOrBlank()) {
                                runCatching {
                                    val sentinel = File(dest, ".dshbox/layer-$name.sha256")
                                    sentinel.parentFile?.mkdirs()
                                    sentinel.writeText(expected)
                                }
                            }
                        }
                    }
                }
            } else {
                // Single .tar.gz / .tar.zst snapshot: extract directly; staging holds base/, node/, android-side/.
                when (val r = bundleManager.extractTarGz(source, staging)) {
                    is AppResult.Failure -> return@withLock r
                    is AppResult.Success -> Unit
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
            return@withLock AppResult.Success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "importRuntimeBundle failed: ${t.message}", t)
            return@withLock AppResult.Failure(AppError("BUNDLE_IMPORT_FAILED", "import failed: ${t.message}"))
        } finally {
            staging.deleteRecursively()
        }
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

    override suspend fun runGuestCommand(command: String, onLine: (String) -> Unit): AppResult<Unit> = withContext(Dispatchers.IO) {
        val proot = prootBinary().absolutePath
        val cmd = buildList {
            add(proot)
            add("--rootfs=${baseRootfs().absolutePath}")
            add("--bind=/system"); add("--bind=/apex"); add("--bind=/proc"); add("--bind=/dev")
            add("--bind=${nodeLayerDir().absolutePath}:/usr/local")
            dshLayerDir().takeIf { it.isDirectory }?.let { add("--bind=${it.absolutePath}:/opt/dshapp/runtime") }
            add("--bind=${config.userDataDir.absolutePath}:/root/projects")
            add("--cwd=/root")
            add("--kill-on-exit")
            add("/system/bin/sh"); add("-c")
            add(command)
        }
        processRunner.runGuestCommand(cmd, buildProotEnv(runtimeCurrentDir(), "guest"), onLine)
    }

    override suspend fun updateDsh(
        bundle: File,
        expectedSha256: String?,
        newVersion: String?,
    ): AppResult<DshUpdateOutcome> {
        // Phase 1: stop DSH + install the layer, serialized under the lock, but using the
        // LOCK-FREE stopDshLocked() (calling public stopDsh() here would re-enter the same
        // non-reentrant Mutex and deadlock; also do not hold the lock for the DSH ready wait).
        val outcome = lifecycleMutex.withLock {
            _dshUpdateProgress.value = "installing DSH ${newVersion ?: ""}"
            try {
                if (_dshState.value == DshState.RUNNING) stopDshLocked()
                when (val r = dshLayer.installFromBundle(bundle, expectedSha256, newVersion)) {
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
     * Kills the current DSH process tree and launches a fresh one. Used ONLY
     * from the health loop so the loop itself is never cancelled. Returns true
     * on success (a new process is running); false when the relaunch failed
     * (state already set to ERROR).
     */
    private suspend fun restartDshProcessInPlace(): Boolean = lifecycleMutex.withLock {
        dshProcess?.let { processRunner.stop(it) }
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
            dshProcess = processRunner.start(command, tag = "dsh", env = prootEnv)
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
