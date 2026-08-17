package com.dshbox.app.sandbox

enum class SandboxState {
    UNINITIALIZED,
    INITIALIZING,
    STARTING,
    RUNNING,
    READY,
    ERROR,
    RECOVERING,
    STOPPED,
}

enum class RecoveryLevel {
    WEBVIEW_RELOAD,
    DSH_RESTART,
    SANDBOX_RESTART,
    RUNTIME_ROLLBACK,
    BACKUP_RESTORE,
    USER_RESET,
}
