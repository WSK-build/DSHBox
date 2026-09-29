package interlock.relay.core.exec.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空闲授权到期的时间判定。
 *
 * token 换到之后如果没有人来用，60 秒后必须结清：挂着的是一块还在投放的虚拟屏和一条
 * 常驻的前台服务通知。这里的判定从「token 到手的时刻」起算，到点为非正数即收场。
 */
class ScreenProjectionIdleExpiryTest {

    /** 到手的瞬间：还剩完整的空闲窗口。 */
    @Test
    fun theWindowIsFullTheMomentTheTokenArrives() {
        assertEquals(60_000L, idleTimeLeftMs(idleSinceMs = 1_000L, nowMs = 1_000L, idleExpiryMs = 60_000L))
    }

    /** 时间流逝吃掉窗口，剩下的量照实扣。 */
    @Test
    fun elapsedClockTimeEatsTheWindow() {
        assertEquals(10_000L, idleTimeLeftMs(idleSinceMs = 1_000L, nowMs = 51_000L, idleExpiryMs = 60_000L))
    }

    /** 过点交回非正数（不夹到零）：唤醒本来就只可能迟到，判定宁可再来一次也不含糊。 */
    @Test
    fun anExpiredWindowIsNonPositive() {
        assertTrue(idleTimeLeftMs(idleSinceMs = 1_000L, nowMs = 61_000L, idleExpiryMs = 60_000L) <= 0L)
        assertEquals(-9_000L, idleTimeLeftMs(idleSinceMs = 1_000L, nowMs = 70_000L, idleExpiryMs = 60_000L))
    }
}
