package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import kotlinx.coroutines.delay

/**
 * Monitors DSH child process health. Implements the recovery policy:
 * minimal-destruction first, limited automatic retries, Safe Mode after
 * repeated failures.
 *
 * Note: sandbox state is tracked separately by [DefaultSandboxManager.sandboxState].
 */
class SandboxSupervisor(
    private val config: SandboxConfig,
    private val healthChecker: SandboxHealthChecker,
) {
    private var consecutiveFailures = 0

    suspend fun supervise(health: SandboxHealth): SandboxHealth {
        return when {
            health.webUiReady -> {
                consecutiveFailures = 0
                health
            }
            consecutiveFailures >= config.maxAutoRestartAttempts -> {
                health.copy(lastError = "max auto-restart attempts reached")
            }
            else -> {
                consecutiveFailures++
                delay(1_000L)
                healthChecker.check()
            }
        }
    }
}

fun interface SandboxHealthChecker {
    suspend fun check(): SandboxHealth
}

fun recoveryError(level: RecoveryLevel, message: String): AppError =
    AppError("RECOVERY_${level.name}", message, recoverable = true)
