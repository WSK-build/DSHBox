package com.dshbox.remote.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 前台累计时钟测试：前台推进、后台冻结、恢复补偿判定。 */
class ForegroundElapsedClockTest {

    @Test fun elapsedOnlyAdvancesWhileForeground() {
        var wall = 1_000L
        val clock = ForegroundElapsedClock { wall }
        assertEquals(0L, clock.foregroundElapsedMillis())
        assertFalse(clock.isForeground)

        clock.onForeground()
        assertTrue(clock.isForeground)
        wall += 5_000
        assertEquals(5_000L, clock.foregroundElapsedMillis())

        // 后台期间时钟冻结：退后台结清片段，墙钟推进不改变累计值
        clock.onBackground()
        assertFalse(clock.isForeground)
        val frozen = clock.foregroundElapsedMillis()
        wall += 600_000
        assertEquals(frozen, clock.foregroundElapsedMillis())

        // 恢复前台：从冻结值继续累计
        clock.onForeground()
        wall += 2_000
        assertEquals(frozen + 2_000, clock.foregroundElapsedMillis())
    }

    @Test fun repeatedTransitionsAreIdempotent() {
        var wall = 0L
        val clock = ForegroundElapsedClock { wall }
        clock.onForeground()
        clock.onForeground() // 重复进入前台：片段起点不被覆盖
        wall += 1_000
        clock.onBackground()
        clock.onBackground() // 重复退后台：片段不被重复结清
        assertEquals(1_000L, clock.foregroundElapsedMillis())
    }

    @Test fun backgroundBeforeForegroundKeepsZero() {
        var wall = 0L
        val clock = ForegroundElapsedClock { wall }
        clock.onBackground()
        wall += 10_000
        assertEquals(0L, clock.foregroundElapsedMillis())
    }
}
