package interlock.relay.core.exec.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实时流的看门狗停流判据（纯函数穷举）。命中名单立即停；
 * 读不到连续两次才按 fail-closed 停（单次 dumpsys 抖动不拆一条正在播的流）；
 * 名单为空与读到了且不在名单都不停。
 */
class ProtectedStreamWatchdogTest {

    @Test
    fun `命中名单立即停流`() {
        val verdict = protectedStreamWatchdogStop(setOf("com.bank"), "com.bank", 0)
        assertTrue(verdict.first)
    }

    @Test
    fun `读到了且不在名单不停并归零计数`() {
        val verdict = protectedStreamWatchdogStop(setOf("com.bank"), "com.other", 1)
        assertFalse(verdict.first)
        assertEquals(0, verdict.second)
    }

    @Test
    fun `单次读不到不停累计一次`() {
        val verdict = protectedStreamWatchdogStop(setOf("com.bank"), null, 0)
        assertFalse("第一次读不到不停", verdict.first)
        assertEquals(1, verdict.second)
    }

    @Test
    fun `连续两次读不到按 fail-closed 停流`() {
        val first = protectedStreamWatchdogStop(setOf("com.bank"), null, 0)
        val second = protectedStreamWatchdogStop(setOf("com.bank"), null, first.second)
        assertTrue(second.first)
    }

    @Test
    fun `名单为空恒不停`() {
        assertFalse(protectedStreamWatchdogStop(emptySet(), "com.bank", 0).first)
        assertFalse(protectedStreamWatchdogStop(emptySet(), null, 5).first)
    }

    @Test
    fun `复查间隔与既有的会话上限各是各的常量`() {
        assertEquals(2_000L, PROTECTED_RECHECK_MS)
        assertEquals(2, STREAM_PROTECTED_MISS_LIMIT)
        assertTrue(PROTECTED_RECHECK_MS > 500L)
    }
}
