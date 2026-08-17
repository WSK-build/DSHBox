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
    fun stateCoversStartupAndRecovery() {
        assertTrue(SandboxState.entries.contains(SandboxState.READY))
        assertTrue(SandboxState.entries.contains(SandboxState.RECOVERING))
    }
}
