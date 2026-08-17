package com.dshbox.app.sandbox

import com.dshbox.app.common.AppResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Facade for the whole Linux Sandbox. The UI never starts PRoot/DSH directly;
 * it talks only to this manager.
 */
interface SandboxManager {
    val state: StateFlow<SandboxState>

    suspend fun initialize()
    suspend fun start()
    suspend fun stop()
    suspend fun restart()
    suspend fun forceStop()
    suspend fun healthCheck(): AppResult<SandboxHealth>
    suspend fun startDsh(): AppResult<DshRuntimeStatus>
    suspend fun stopDsh()
    suspend fun recover(level: RecoveryLevel): AppResult<Unit>
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
}
