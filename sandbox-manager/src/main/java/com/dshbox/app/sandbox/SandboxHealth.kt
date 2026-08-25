package com.dshbox.app.sandbox

/**
 * DSH 运行时健康快照。沙箱状态由 [SandboxManager.sandboxState] 单独暴露，
 * 此处仅描述 DSH 进程/端口/WebUI 状态。
 */
data class SandboxHealth(
    val dshProcessRunning: Boolean,
    val portOpen: Boolean,
    val webUiReady: Boolean,
    val lastError: String? = null,
)

data class DshRuntimeStatus(
    val dshVersion: String?,
    val pluginApiVersion: String?,
    val baseUrl: String,
    val ready: Boolean,
)
