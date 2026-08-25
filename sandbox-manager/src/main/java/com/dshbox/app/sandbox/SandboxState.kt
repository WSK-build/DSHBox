package com.dshbox.app.sandbox

/**
 * Debian 沙箱生命周期状态。仅描述 PRoot/Debian 是否在线，
 * 不再与 DSH 就绪状态耦合。
 */
enum class SandboxState {
    UNINITIALIZED,
    INITIALIZING,
    STARTING,
    RUNNING,
    ERROR,
    RECOVERING,
    STOPPED,
}

/**
 * DSH 运行时生命周期状态。独立于沙箱状态，便于分别控制。
 */
enum class DshState {
    UNINITIALIZED,
    STARTING,
    RUNNING,
    READY,
    ERROR,
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
