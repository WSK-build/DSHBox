package com.dshbox.app.service

import com.dshbox.app.sandbox.SandboxState

/**
 * 通知栏动作（按沙箱状态取，最多两个）：运行中给「重启 / 关闭」，启动/恢复中
 * 只给「关闭」，其余（停止/错误/未初始化）给「启动」。
 *
 * Android 标准通知模板最多渲染三个 action，固定四项会把最后一项截掉；动作
 * 数量与顺序收口在这个纯函数里，便于单测锁定。
 */
internal enum class NotificationAction {
    START,
    RESTART,
    STOP,
}

internal fun notificationActions(sandboxState: SandboxState): List<NotificationAction> =
    when (sandboxState) {
        SandboxState.RUNNING -> listOf(
            NotificationAction.RESTART,
            NotificationAction.STOP,
        )
        SandboxState.STARTING,
        SandboxState.INITIALIZING,
        SandboxState.RECOVERING,
        -> listOf(NotificationAction.STOP)
        else -> listOf(NotificationAction.START)
    }
