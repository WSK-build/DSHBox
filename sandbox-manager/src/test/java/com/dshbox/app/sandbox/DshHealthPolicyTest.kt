package com.dshbox.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 健康判定策略的时序回归。
 *
 * 这些行为原本只能靠真机造场景才能覆盖（真机上制造「进程存活但不应答」需要对 PRoot 进程
 * 发信号，且用例不可重复）；抽成纯函数后用普通单测即可钉住每种组合：
 * 零星成功不清零窗口、半死震荡最终仍会重启、采样中断与休眠都不推进判定、预算边界给 Exhausted。
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
    ): DshHealthDecision = decideDshHealth(
        state = state,
        probe = DshHealthProbe(
            nowMs = nowMs,
            webUiReady = ready,
            processAlive = alive,
            deviceInteractive = interactive,
            probeGapMs = gapMs,
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
}
