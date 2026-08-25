package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxStateTest {
    @Test
    fun recoveryLevelsAreOrdered() {
        assertEquals(RecoveryLevel.WEBVIEW_RELOAD, RecoveryLevel.entries[0])
        assertEquals(RecoveryLevel.USER_RESET, RecoveryLevel.entries[RecoveryLevel.entries.size - 1])
    }

    @Test
    fun sandboxStateCoversLifecycle() {
        assertTrue(SandboxState.entries.contains(SandboxState.RUNNING))
        assertTrue(SandboxState.entries.contains(SandboxState.STOPPED))
        assertTrue(SandboxState.entries.contains(SandboxState.RECOVERING))
    }

    @Test
    fun dshStateIsSeparate() {
        assertTrue(DshState.entries.contains(DshState.READY))
        assertTrue(DshState.entries.contains(DshState.RUNNING))
        assertTrue(DshState.entries.contains(DshState.STOPPED))
    }
}
