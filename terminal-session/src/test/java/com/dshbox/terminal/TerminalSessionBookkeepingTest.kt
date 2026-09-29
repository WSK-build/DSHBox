package com.dshbox.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionBookkeepingTest {

    @Test
    fun `spawned process is killed and recorded`() {
        assertTrue(TerminalSessionBookkeeping.needsKillAndMark(1))
        assertTrue(TerminalSessionBookkeeping.needsKillAndMark(42_424))
    }

    @Test
    fun `session that never spawned is neither killed nor recorded`() {
        assertFalse(TerminalSessionBookkeeping.needsKillAndMark(0))
    }

    @Test
    fun `finished session is neither killed nor recorded`() {
        assertFalse(TerminalSessionBookkeeping.needsKillAndMark(-1))
    }

    @Test
    fun `pid states map to the documented kill decision`() {
        // 未启动 / 已结束 / 已启动
        assertEquals(
            listOf(false, false, true),
            listOf(0, -1, 7).map(TerminalSessionBookkeeping::needsKillAndMark),
        )
    }
}
