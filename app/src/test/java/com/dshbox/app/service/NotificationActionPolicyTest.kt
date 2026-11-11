package com.dshbox.app.service

import com.dshbox.app.sandbox.SandboxState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知栏动作策略：运行中只给「重启/关闭」，启动/恢复中只给「关闭」，停止/
 * 错误/未初始化给「启动」；最多两个，避免 Android 标准模板截掉第三个。
 */
class NotificationActionPolicyTest {

    @Test
    fun runningShowsRestartAndStop() {
        assertEquals(
            listOf(NotificationAction.RESTART, NotificationAction.STOP),
            notificationActions(SandboxState.RUNNING),
        )
    }

    @Test
    fun transitionalStatesOnlyShowStop() {
        for (state in listOf(
            SandboxState.STARTING,
            SandboxState.INITIALIZING,
            SandboxState.RECOVERING,
        )) {
            assertEquals(listOf(NotificationAction.STOP), notificationActions(state))
        }
    }

    @Test
    fun stoppedOrFailedStatesShowStart() {
        for (state in listOf(
            SandboxState.STOPPED,
            SandboxState.ERROR,
            SandboxState.UNINITIALIZED,
        )) {
            assertEquals(listOf(NotificationAction.START), notificationActions(state))
        }
    }

    @Test
    fun everyStateStaysWithinThreeActions() {
        for (state in SandboxState.entries) {
            val actions = notificationActions(state)
            assertEquals(true, actions.size in 1..3)
            assertEquals(actions.size, actions.distinct().size)
        }
    }
}
