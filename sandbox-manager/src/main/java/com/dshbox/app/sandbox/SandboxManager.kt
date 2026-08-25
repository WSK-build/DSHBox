package com.dshbox.app.sandbox

import com.dshbox.app.common.AppResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade for the Linux Sandbox and DSH runtime.
 *
 * The UI never starts PRoot/DSH directly; it talks only to this manager.
 * Sandbox (Debian) and DSH are intentionally decoupled:
 * - [sandboxState] tracks only the Debian/PRoot lifecycle.
 * - [dshState] tracks only the DSH web service lifecycle.
 * - Starting/stopping/restarting one must not affect the other.
 */
interface SandboxManager {
    val sandboxState: StateFlow<SandboxState>
    val dshState: StateFlow<DshState>

    /** Currently installed DSH layer version (runtime-current/dsh), or null. */
    val dshVersion: StateFlow<String?>

    /** Monotonic in-progress flag / message for DSH update. */
    val dshUpdateProgress: StateFlow<String?>

    /** One-time directory initialization. Idempotent. */
    suspend fun initialize()

    /** Start the Debian sandbox (PRoot keepalive). Independent of DSH. */
    suspend fun startSandbox()

    /** Stop the Debian sandbox. DSH keeps running if it was started separately. */
    suspend fun stopSandbox()

    /** Restart the Debian sandbox. */
    suspend fun restartSandbox()

    /** Force-stop both sandbox and DSH. */
    suspend fun forceStop()

    /** Health snapshot combining sandbox state and DSH port checks. */
    suspend fun healthCheck(): AppResult<SandboxHealth>

    /**
     * Start DSH. Requires the sandbox to be [SandboxState.RUNNING];
     * otherwise returns a recoverable error telling the user to wake the sandbox first.
     */
    suspend fun startDsh(): AppResult<DshRuntimeStatus>

    /** Stop DSH only. The sandbox keeps running. */
    suspend fun stopDsh()

    /** Restart DSH only. The sandbox keeps running. */
    suspend fun restartDsh(): AppResult<DshRuntimeStatus>

    /** Recover according to [level]. */
    suspend fun recover(level: RecoveryLevel): AppResult<Unit>

    /** Enter safe mode: stop everything and stay stopped. */
    suspend fun enterSafeMode()

    /** Returns true when runtime-current contains both PRoot and the Debian rootfs. */
    fun isRuntimeInstalled(): Boolean

    /** Scans the updates dir for the first .tar.gz with a valid .sha256 sidecar and installs it. */
    suspend fun installFirstAvailableBundle(): AppResult<java.io.File>

    /** Installs a verified Runtime Bundle into runtime-new (does not switch). */
    suspend fun installRuntimeBundle(bundleFile: java.io.File, expectedSha256: String): AppResult<java.io.File>

    /** Switches runtime-new to runtime-current after the sandbox is stopped. */
    suspend fun promoteRuntimeBundle(): AppResult<Unit>

    /** Restores the previous Runtime slot after stopping the sandbox. */
    suspend fun rollbackRuntime(): AppResult<Unit>

    /**
     * Updates the standalone DSH layer (runtime-current/dsh) from a DSH bundle
     * (tar.gz) with version arbitration: installed-newer wins, incoming-newer
     * replaces (old -> previous/dsh). Does not touch user-data/.dsh.
     */
    suspend fun updateDsh(
        bundle: java.io.File,
        expectedSha256: String?,
        newVersion: String?,
    ): AppResult<DshUpdateOutcome>

    /**
     * Offline-import a runtime bundle (a zip holding the layered body:
     * base/node/android-side <layer>.tar.* + .sha256 sidecars + runtime-profile.json).
     * Cleanly replaces the runtime body (old body -> previous/, single copy) while
     * PROTECTING the DSH layer (runtime-current/dsh) and the user data
     * (user-data / user-data/.dsh). Sandbox must be stopped first.
     */
    suspend fun importRuntimeBundle(source: java.io.File): AppResult<Unit>

    /**
     * Inject a one-off command into the DSH guest (fresh PRoot process) and
     * stream each output line to [onLine]. Used for 指令注入 — e.g. running a
     * plugin's install.sh inside the guest. Does NOT require the sandbox
     * keepalive to be running.
     */
    suspend fun runGuestCommand(command: String, onLine: (String) -> Unit = {}): AppResult<Unit>
}
