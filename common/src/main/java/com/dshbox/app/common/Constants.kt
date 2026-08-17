package com.dshbox.app.common

object Constants {
    const val DSH_DEFAULT_HOST = "127.0.0.1"
    const val DSH_DEFAULT_PORT = 3080

    /** WebView loads the DSH loopback URL. Both localhost and 127.0.0.1 are allowed by NSC. */
    const val DSH_BASE_URL = "http://$DSH_DEFAULT_HOST:$DSH_DEFAULT_PORT"

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
}
