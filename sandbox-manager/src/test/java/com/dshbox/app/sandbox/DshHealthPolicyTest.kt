package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 健康判定策略的时序回归。
 *
 * 验证零星成功不清零窗口、间歇失响应最终触发重启、采样中断与休眠不推进判定，
 * 以及预算边界返回 Exhausted。
 */
class DshHealthPolicyTest {

    private val readyTimeoutMs = 120_000L
    private val graceMs = 120_000L
    private val maxAttempts = 3

    private fun decide(
        state: DshWindowState,
        nowMs: Long,
        ready: Boolean,
        alive: Boolean = true,
        interactive: Boolean = true,
        gapMs: Long = 2_000L,
        confirmed: Boolean = true,
    ): DshHealthDecision = decideDshHealth(
        state = state,
        probe = DshHealthProbe(
            nowMs = nowMs,
            webUiReady = ready,
            processAlive = alive,
            deviceInteractive = interactive,
            probeGapMs = gapMs,
            addressConfirmed = confirmed,
        ),
        readyTimeoutMs = readyTimeoutMs,
        unresponsiveGraceMs = graceMs,
        maxRestartAttempts = maxAttempts,
    )

    private fun readyState(
        attempts: Int = 0,
        since: Long? = null,
        startedAt: Long = 0,
        streak: Int = 0,
    ) = DshWindowState(
        wasReady = true,
        restartAttempts = attempts,
        unresponsiveSinceMs = since,
        startedAtMs = startedAt,
        healthyStreak = streak,
    )

    @Test
    fun `fresh instance becomes ready on first successful probe`() {
        val d = decide(DshWindowState.fresh(0), nowMs = 1_000, ready = true)
        assertTrue(d is DshHealthDecision.Ready)
        assertTrue(d.next.wasReady)
        assertEquals(1, d.next.healthyStreak)
        assertNull(d.next.unresponsiveSinceMs)
    }

    @Test
    fun `window and budget close only after sustained success`() {
        val d = decide(readyState(attempts = 2, since = 1_000, streak = 2), nowMs = 200_000, ready = true)
        assertTrue(d is DshHealthDecision.Ready)
        assertEquals(DshHealthPolicy.HEALTHY_STREAK_TO_CLOSE, d.next.healthyStreak)
        assertNull(d.next.unresponsiveSinceMs)
        assertEquals(0, d.next.restartAttempts)
    }

    @Test
    fun `a lone success does not clear window or budget`() {
        val d = decide(readyState(attempts = 2, since = 1_000), nowMs = 100_000, ready = true)
        assertTrue(d is DshHealthDecision.Ready)
        assertEquals(1_000L, d.next.unresponsiveSinceMs)
        assertEquals(2, d.next.restartAttempts)
    }

    @Test
    fun `half-dead instance still gets restarted across stray successes`() {
        var s = readyState(streak = DshHealthPolicy.HEALTHY_STREAK_TO_CLOSE)
        s = decide(s, nowMs = 0, ready = false).next
        s = decide(s, nowMs = 40_000, ready = true).next
        s = decide(s, nowMs = 60_000, ready = false).next
        s = decide(s, nowMs = 100_000, ready = true).next
        val d = decide(s, nowMs = 121_000, ready = false)
        assertTrue(d is DshHealthDecision.Restart)
        assertEquals(1, d.next.restartAttempts)
        assertFalse(d.next.wasReady)
        assertNull(d.next.unresponsiveSinceMs)
    }

    @Test
    fun `unresponsive instance restarts when the window elapses`() {
        val s = readyState(since = 1_000, startedAt = 1_000)
        assertTrue(decide(s, nowMs = 100_000, ready = false) is DshHealthDecision.Observe)
        assertTrue(decide(s, nowMs = 121_000, ready = false) is DshHealthDecision.Restart)
    }

    @Test
    fun `dead handle restarts immediately`() {
        val s = readyState(streak = DshHealthPolicy.HEALTHY_STREAK_TO_CLOSE)
        val d = decide(s, nowMs = 3_000, ready = false, alive = false)
        assertTrue(d is DshHealthDecision.Restart)
        assertFalse(d.next.wasReady)
    }

    @Test
    fun `initial start fails when readiness timeout elapses`() {
        val s = DshWindowState.fresh(0)
        assertTrue(decide(s, nowMs = 60_000, ready = false) is DshHealthDecision.Observe)
        assertTrue(decide(s, nowMs = 120_001, ready = false) is DshHealthDecision.InitialTimeout)
    }

    @Test
    fun `exhausted budget is reported to the caller`() {
        val s = readyState(attempts = 2, since = 0)
        val d = decide(s, nowMs = 120_000, ready = false)
        assertTrue(d is DshHealthDecision.Exhausted)
        assertEquals(maxAttempts, d.next.restartAttempts)
    }

    @Test
    fun `sampling interruption re-arms instead of firing on a stale delta`() {
        val s = readyState(since = 0, startedAt = 0)
        val d = decide(s, nowMs = 600_000, ready = false, gapMs = DshHealthPolicy.PROBE_GAP_REARM_MS + 1)
        assertTrue(d is DshHealthDecision.Observe)
        assertEquals(600_000L, d.next.unresponsiveSinceMs)
        assertEquals(600_000L, d.next.startedAtMs)
    }

    @Test
    fun `sleeping device neither opens nor advances the window`() {
        val s = readyState(since = 0, startedAt = 0)
        val d = decide(s, nowMs = 600_000, ready = false, interactive = false)
        assertTrue(d is DshHealthDecision.Observe)
        assertNull(d.next.unresponsiveSinceMs)
        assertEquals(600_000L, d.next.startedAtMs)
    }

    @Test
    fun `dead handle is restarted even while sleeping`() {
        val s = readyState(since = 0, startedAt = 0)
        val d = decide(s, nowMs = 600_000, ready = false, alive = false, interactive = false)
        assertTrue(d is DshHealthDecision.Restart)
    }

    /**
     * 就绪必须以启动输出行确证为前提：端口上即便有应答（可能是别的服务）也不判 READY；
     * 进程存活时按首次启动超时等待输出行，不在中途重启仍在初始化的实例。
     */
    @Test
    fun `unconfirmed but alive instance waits for ready timeout instead of restarting`() {
        val start = DshWindowState.fresh(0)
        for (ready in listOf(true, false)) {
            var s = start
            var t = 2_000L
            while (t <= readyTimeoutMs) {
                val d = decide(s, nowMs = t, ready = ready, confirmed = false)
                assertTrue("t=$t ready=$ready", d is DshHealthDecision.Observe)
                s = d.next
                t += 2_000L
            }
            val timeout = decide(s, nowMs = readyTimeoutMs + 1, ready = ready, confirmed = false)
            assertTrue(timeout is DshHealthDecision.InitialTimeout)
            assertEquals(0, timeout.next.restartAttempts)
        }
    }

    /** 未确证且进程句柄已消失：属确定性宕机，立即换端口重启，不等超时。 */
    @Test
    fun `unconfirmed and dead instance restarts immediately`() {
        val start = DshWindowState.fresh(0)
        for (ready in listOf(true, false)) {
            val d = decide(start, nowMs = 2_000, ready = ready, alive = false, confirmed = false)
            assertTrue("ready=$ready", d is DshHealthDecision.Restart)
            assertEquals(1, d.next.restartAttempts)
        }
    }

    /** 重启后的新实例同样适用：未确证但存活则观察，直至首次启动超时。 */
    @Test
    fun `unconfirmed instance after a restart observes until ready timeout`() {
        val restarted = decide(DshWindowState.fresh(0), nowMs = 10_000, ready = false, alive = false, confirmed = false)
        assertTrue(restarted is DshHealthDecision.Restart)
        val afterRestart = restarted.next
        val inside = decide(afterRestart, nowMs = 10_000 + readyTimeoutMs, ready = true, confirmed = false)
        assertTrue(inside is DshHealthDecision.Observe)
        val outside = decide(afterRestart, nowMs = 10_001 + readyTimeoutMs, ready = true, confirmed = false)
        assertTrue(outside is DshHealthDecision.InitialTimeout)
    }

    /** 未确证且进程句柄消失时同样受重试预算约束，预算用尽交由调用方复查。 */
    @Test
    fun `unconfirmed and dead instance reports exhausted at budget boundary`() {
        val s = DshWindowState.fresh(0).copy(restartAttempts = maxAttempts - 1)
        val d = decide(s, nowMs = 2_000, ready = true, alive = false, confirmed = false)
        assertTrue(d is DshHealthDecision.Exhausted)
        assertEquals(maxAttempts, d.next.restartAttempts)
    }

    /** 不变式：任意输入组合下，未确证的地址都不会判为 READY。 */
    @Test
    fun `unconfirmed address never yields ready under any input`() {
        val bools = listOf(true, false)
        for (wasReady in bools) for (ready in bools) for (alive in bools) for (interactive in bools)
            for (gap in listOf(0L, 2_000L, DshHealthPolicy.PROBE_GAP_REARM_MS + 1))
                for (attempts in 0 until maxAttempts)
                    for (now in listOf(1_000L, 60_000L, readyTimeoutMs + 1, 600_000L)) {
                        val s = DshWindowState(
                            wasReady = wasReady,
                            restartAttempts = attempts,
                            unresponsiveSinceMs = if (wasReady) 0L else null,
                            startedAtMs = 0L,
                            healthyStreak = DshHealthPolicy.HEALTHY_STREAK_TO_CLOSE - 1,
                        )
                        val d = decide(
                            s,
                            nowMs = now,
                            ready = ready,
                            alive = alive,
                            interactive = interactive,
                            gapMs = gap,
                            confirmed = false,
                        )
                        assertFalse(
                            "wasReady=$wasReady ready=$ready alive=$alive interactive=$interactive " +
                                "gap=$gap attempts=$attempts now=$now",
                            d is DshHealthDecision.Ready,
                        )
                        assertEquals(0, d.next.healthyStreak)
                    }
    }

    /** 未确证期间的端口应答不累计连续成功次数，确证后从零起算。 */
    @Test
    fun `unconfirmed answers do not count toward the healthy streak`() {
        var s = DshWindowState.fresh(0)
        repeat(5) { i ->
            s = decide(s, nowMs = 2_000L * (i + 1), ready = true, confirmed = false).next
        }
        assertEquals(0, s.healthyStreak)
        assertFalse(s.wasReady)
        val d = decide(s, nowMs = 12_000, ready = true, confirmed = true)
        assertTrue(d is DshHealthDecision.Ready)
        assertEquals(1, d.next.healthyStreak)
    }

    /** 已确证的地址不受该门影响：原有就绪路径照常生效。 */
    @Test
    fun `confirmed address keeps normal ready path`() {
        val start = DshWindowState.fresh(0)
        val d = decide(start, nowMs = 1_000, ready = true, confirmed = true)
        assertTrue(d is DshHealthDecision.Ready)
    }
}
