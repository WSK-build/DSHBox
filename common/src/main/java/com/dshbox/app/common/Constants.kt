package com.dshbox.app.common

object Constants {
    const val DSH_DEFAULT_HOST = "127.0.0.1"
    const val DSH_DEFAULT_PORT = 3080

    /** WebView loads the DSH loopback URL. Both localhost and 127.0.0.1 are allowed by NSC. */
    const val DSH_BASE_URL = "http://$DSH_DEFAULT_HOST:$DSH_DEFAULT_PORT"

    // 1.1.0：旧的 DSH_MIRRORS / DSH_LAYER_BASE_URL（预构建 dsh_layer.tar.zst 下载源）已废弃——
    // 该下载源从未存在，在线更新改为「探测 npm 源 + guest 内 npm 拉包」，
    // 见 common/DshSources.kt 与 SandboxManager.installDshFromNpm。

    /** Online-update guard: minimum free bytes on the app storage before a guest npm install starts. */
    const val DSH_INSTALL_MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024 // 1 GiB

    const val MIN_SUPPORTED_SDK = 29

    /** Default Linux workspace inside the Debian sandbox. */
    const val SANDBOX_WORKSPACE = "/root/projects"

    /** Android-side sandbox directory names (App-specific storage). */
    const val DIR_RUNTIME = "runtime"
    const val DIR_SANDBOX = "sandbox"
    const val DIR_USER_DATA = "user-data"
    const val DIR_LOGS = "logs"
    const val DIR_BACKUPS = "backups"
    const val DIR_UPDATES = "updates"

    const val MAX_AUTO_RESTART_ATTEMPTS = 3

    const val HEALTHCHECK_TIMEOUT_MS = 5_000L
    const val DSH_READY_TIMEOUT_MS = 120_000L

    /** SharedPreferences key: whether the app has completed the first-run bootstrap. */
    const val PREFS_NAME = "dshapp_prefs"
    const val PREF_FIRST_RUN_COMPLETED = "first_run_completed"

    /** Marker embedded in the sandbox keepalive command to distinguish the PRoot process. */
    const val SANDBOX_KEEPALIVE_MARKER = "dshapp-sandbox-keepalive"

    /**
     * Marker used to locate the DSH PRoot process at stop time. The DSH layer is
     * mounted at /opt/dshapp/runtime (bound by BOTH sandbox and DSH proot), so
     * "/opt/dshapp/runtime" would also match the sandbox keepalive cmdline and
     * stopDsh() would kill the whole sandbox tree. Instead match the DSH-ONLY
     * entry token `@deepseek-ai/dsh/lib/bin.js` (present only in the DSH PRoot
     * cmdline: `node --expose-internals .../@deepseek-ai/dsh/lib/bin.js --profile web`).
     */
    const val DSH_START_SCRIPT = "@deepseek-ai/dsh/lib/bin.js"
}
