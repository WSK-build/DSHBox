package com.dshbox.app.sandbox

import java.io.File

data class SandboxConfig(
    val appFilesDir: File,
    val nativeLibraryDir: String? = null,
    val dshHost: String = "127.0.0.1",
    val dshPort: Int = 3080,
    val healthPath: String = "/",
    val maxAutoRestartAttempts: Int = 3,
    val dshReadyTimeoutMs: Long = 120_000L,
) {
    val runtimeDir: File = File(appFilesDir, "runtime")
    val sandboxDir: File = File(appFilesDir, "sandbox")
    val userDataDir: File = File(appFilesDir, "user-data")
    val logsDir: File = File(appFilesDir, "logs")
    val backupsDir: File = File(appFilesDir, "backups")
    val updatesDir: File = File(appFilesDir, "updates")
}
