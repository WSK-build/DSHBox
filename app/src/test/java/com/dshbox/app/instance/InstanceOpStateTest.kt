package com.dshbox.app.instance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 实例操作门禁的原子进入与拒绝不毁状态语义。 */
class InstanceOpStateTest {

    @Test
    fun tryBegin_firstWinsSecondRejected() {
        assertTrue(InstanceOpState.tryBegin(InstanceOpState.Kind.SWITCH))
        assertFalse(InstanceOpState.tryBegin(InstanceOpState.Kind.CREATE_EMERGENCY))
        assertTrue(InstanceOpState.state.value.running)
        assertEquals(InstanceOpState.Kind.SWITCH, InstanceOpState.state.value.kind)
        InstanceOpState.finish(true)
    }

    @Test
    fun rejectedBegin_doesNotClearRunningState() {
        InstanceOpState.tryBegin(InstanceOpState.Kind.DELETE_EMERGENCY)
        // 拒绝路径不得改动进行中操作的状态。
        assertFalse(InstanceOpState.tryBegin(InstanceOpState.Kind.SWITCH))
        assertTrue(InstanceOpState.state.value.running)
        assertEquals(InstanceOpState.Kind.DELETE_EMERGENCY, InstanceOpState.state.value.kind)
        InstanceOpState.finish(true)
        assertFalse(InstanceOpState.state.value.running)
    }

    @Test
    fun finish_recordsResultAndAllowsNext() {
        InstanceOpState.tryBegin(InstanceOpState.Kind.REPAIR_LAYER)
        InstanceOpState.finish(false, "boom")
        assertFalse(InstanceOpState.state.value.running)
        assertEquals(false, InstanceOpState.state.value.lastSuccess)
        assertEquals("boom", InstanceOpState.state.value.error)
        assertTrue(InstanceOpState.tryBegin(InstanceOpState.Kind.SWITCH))
        InstanceOpState.finish(true)
        assertNull(InstanceOpState.state.value.error)
    }
}