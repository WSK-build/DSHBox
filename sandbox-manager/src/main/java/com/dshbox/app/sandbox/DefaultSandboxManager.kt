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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import android.util.Log

/**
 * Default SandboxManager state machine. Owns the state transitions, directory
 * initialization, PRoot process launching, health-check polling and bounded
 * recovery policy.
 */
class DefaultSandboxManager(
    private val config: SandboxConfig,
    private val healthChecker: SandboxHealthChecker = HttpHealthChecker(config.dshHost, config.dshPort),
) : SandboxManager {

    private val bundleManager = BundleManager(config)
    private val processRunner = SandboxProcessRunner(config)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SandboxState.UNINITIALIZED)
    override val state: StateFlow<SandboxState> = _state.asStateFlow()

    private val lifecycleMutex = Mutex()
    private var healthLoopJob: Job? = null
    private var restartAttempts = 0
    private var runningProcess: SandboxProcessRunner.RunningProcess? = null

    override suspend fun initialize() {
        if (_state.value != SandboxState.UNINITIALIZED) return
        _state.value = SandboxState.INITIALIZING
        try {
            createDirectories()
        } catch (t: Throwable) {
            _state.value = SandboxState.ERROR
            return
        }
        _state.value = SandboxState.STOPPED
    }

    override suspend fun start() = lifecycleMutex.withLock {
        if (_state.value == SandboxState.RUNNING || _state.value == SandboxState.READY) return@withLock
        _state.value = SandboxState.STARTING
        try {
            ensureRuntimePresent()
            val runtimeDir = runtimeCurrentDir()
            val command = processRunner.buildProotStartCommand(
                prootBinary = prootBinary().absolutePath,
                rootfsDir = debianRootfs().absolutePath,
                workspaceBind = config.userDataDir.absolutePath,
            )
            val prootEnv = mapOf(
                "LD_LIBRARY_PATH" to prootLibDir().absolutePath,
                "PROOT_TMP_DIR" to File(runtimeDir, "tmp").apply { mkdirs() }.absolutePath,
                "PROOT_LOADER" to prootLoaderFile().absolutePath,
            )
            Log.i(TAG, "starting proot: ${command.take(4)}")
            runningProcess = processRunner.start(command, tag = "proot", env = prootEnv)
            Log.i(TAG, "proot process started")
        } catch (t: Throwable) {
            Log.e(TAG, "start failed: ${t.message}", t)
            _state.value = SandboxState.ERROR
            return@withLock
        }
        _state.value = SandboxState.RUNNING
        restartAttempts = 0
        startHealthLoop()
    }

    override suspend fun stop() {
        lifecycleMutex.withLock {
            Log.i(TAG, "stop(): cancelling health loop, process=${runningProcess != null}")
            Log.d(TAG, "stop() caller:", Throwable("stop() call stack"))
            healthLoopJob?.cancel()
            healthLoopJob = null
            runningProcess?.let { processRunner.stop(it) }
            runningProcess = null
            _state.value = SandboxState.STOPPED
            Log.i(TAG, "stop(): state=STOPPED")
        }
    }

    override suspend fun restart() {
        stop()
        delay(200L)
        start()
    }

    override suspend fun forceStop() {
        stop()
    }

    override suspend fun healthCheck(): AppResult<SandboxHealth> {
        val health = healthChecker.check()
        return health.toAppResult()
    }

    override suspend fun startDsh(): AppResult<DshRuntimeStatus> {
        if (_state.value != SandboxState.RUNNING && _state.value != SandboxState.READY) {
            return AppResult.Failure(AppError("SANDBOX_NOT_RUNNING", "Sandbox is not running"))
        }
        // TODO(phase-1): execute /opt/dshapp/start_dsh.sh inside the Debian
        // sandbox and parse DSH_VERSION/DSH_PLUGIN_API_VERSION.
        val health = healthChecker.check()
        return if (health.webUiReady) {
            _state.value = SandboxState.READY
            AppResult.Success(
                DshRuntimeStatus(
                    dshVersion = null,
                    pluginApiVersion = null,
                    baseUrl = "http://${config.dshHost}:${config.dshPort}",
                    ready = true,
                ),
            )
        } else {
            AppResult.Failure(AppError("DSH_NOT_READY", "DSH did not become ready in time"))
        }
    }

    override suspend fun stopDsh() {
        // TODO(phase-1): terminate DSH child process only, not the whole sandbox.
    }

    override suspend fun recover(level: RecoveryLevel): AppResult<Unit> {
        _state.value = SandboxState.RECOVERING
        return when (level) {
            RecoveryLevel.DSH_RESTART -> {
                stopDsh()
                startDsh().map { }
            }
            RecoveryLevel.SANDBOX_RESTART -> {
                restart()
                AppResult.Success(Unit)
            }
            else -> AppResult.Failure(AppError("RECOVERY_UNSUPPORTED", "Recovery level not implemented yet"))
        }.also { result ->
            _state.value = if (result is AppResult.Success) SandboxState.RUNNING else SandboxState.ERROR
        }
    }

    override suspend fun enterSafeMode() {
        stop()
        _state.value = SandboxState.STOPPED
    }

    override fun isRuntimeInstalled(): Boolean {
        val proot = prootBinary()
        val debian = debianRootfs()
        val installed = proot.isFile && debian.isDirectory
        Log.i(TAG, "isRuntimeInstalled=$installed proot=${proot.absolutePath} exists=${proot.exists()} debian=${debian.absolutePath} exists=${debian.exists()}")
        return installed
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
        if (_state.value == SandboxState.RUNNING || _state.value == SandboxState.READY) {
            return AppResult.Failure(AppError("SANDBOX_RUNNING", "stop the sandbox before installing a Runtime Bundle"))
        }
        return bundleManager.installToNewSlot(bundleFile, expectedSha256)
    }

    override suspend fun promoteRuntimeBundle(): AppResult<Unit> {
        if (_state.value == SandboxState.RUNNING || _state.value == SandboxState.READY) {
            return AppResult.Failure(AppError("SANDBOX_RUNNING", "stop the sandbox before switching Runtime slots"))
        }
        return bundleManager.promoteNewSlotToCurrent()
    }

    override suspend fun rollbackRuntime(): AppResult<Unit> {
        if (_state.value == SandboxState.RUNNING || _state.value == SandboxState.READY) {
            stop()
        }
        return bundleManager.rollback()
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

    private fun debianRootfs(): File = File(runtimeCurrentDir(), "debian")

    private fun ensureRuntimePresent() {
        check(prootBinary().isFile) { "PRoot binary not found: ${prootBinary().absolutePath}" }
        check(debianRootfs().isDirectory) { "Debian rootfs not found: ${debianRootfs().absolutePath}" }
        ensureGuestResolvConf()
    }

    /**
     * Rootfs images built inside WSL ship a WSL-generated /etc/resolv.conf
     * (nameserver 10.255.255.254) that is unreachable on Android, so DSH
     * cannot resolve api.deepseek.com and every model request fails with
     * "DeepSeek API request ... failed". Rewrite it with public resolvers
     * when it is missing, points at an unreachable address, or contains WSL
     * markers; re-running an import restores the broken file, hence this is
     * checked on every sandbox start.
     */
    private fun ensureGuestResolvConf() {
        val resolv = File(debianRootfs(), "etc/resolv.conf")
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

    private fun startHealthLoop() {
        healthLoopJob?.cancel()
        healthLoopJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            var wasReady = false
            while (_state.value == SandboxState.RUNNING || _state.value == SandboxState.READY) {
                val health = healthChecker.check()
                if (health.webUiReady) {
                    _state.value = SandboxState.READY
                    restartAttempts = 0
                    wasReady = true
                } else if (wasReady) {
                    // Once DSH has been ready, a later failure uses the bounded
                    // auto-restart policy.
                    restartAttempts++
                    if (restartAttempts >= Constants.MAX_AUTO_RESTART_ATTEMPTS) {
                        _state.value = SandboxState.ERROR
                        return@launch
                    }
                    restart()
                    return@launch
                } else if (System.currentTimeMillis() - startedAt > config.dshReadyTimeoutMs) {
                    // Initial startup gets the full configured timeout; do not
                    // give up after only a few fast probe failures.
                    _state.value = SandboxState.ERROR
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
