package com.dshbox.app.service

import com.dshbox.app.sandbox.DshState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证插件仅在进入 READY 时触发装配，同一次 READY 期间的重复状态不重新装配。
 */
class PluginAssemblyTriggerTest {

    @Test
    fun firstEmissionOfReadyTriggers() {
        // 服务重启时 DSH 已在运行：首发射即 READY 必须触发，否则这次对齐永久丢失。
        assertTrue(PluginAssemblyTrigger.shouldAssemble(previous = null, current = DshState.READY))
    }

    @Test
    fun readyAfterReadyDoesNotRetrigger() {
        assertFalse(
            PluginAssemblyTrigger.shouldAssemble(
                previous = DshState.READY,
                current = DshState.READY,
            ),
        )
    }

    @Test
    fun startingToReadyTriggers() {
        assertTrue(
            PluginAssemblyTrigger.shouldAssemble(
                previous = DshState.STARTING,
                current = DshState.READY,
            ),
        )
    }

    @Test
    fun everyNonReadyStateIsInert() {
        for (state in listOf(DshState.UNINITIALIZED, DshState.STARTING, DshState.RUNNING, DshState.ERROR, DshState.STOPPED)) {
            assertFalse(
                "state=$state 不应触发装配",
                PluginAssemblyTrigger.shouldAssemble(previous = DshState.READY, current = state),
            )
            assertFalse(
                "state=$state 不应触发装配",
                PluginAssemblyTrigger.shouldAssemble(previous = null, current = state),
            )
        }
    }

    @Test
    fun recoveryBackToReadyTriggersAgain() {
        // 自动重启成功或用户手动重启后回到 READY：借这次机会补上上次被跳过/失败的包。
        assertFalse(
            PluginAssemblyTrigger.shouldAssemble(previous = DshState.READY, current = DshState.ERROR),
        )
        assertTrue(
            PluginAssemblyTrigger.shouldAssemble(previous = DshState.ERROR, current = DshState.READY),
        )
        assertTrue(
            PluginAssemblyTrigger.shouldAssemble(previous = DshState.STOPPED, current = DshState.READY),
        )
    }
}
