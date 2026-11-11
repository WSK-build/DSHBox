package com.dshbox.app.lanaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 鉴权决策与限速（JVM）：首次兑换、有效 cookie 续访、无效拒绝、
 * 连续 10 次失败落 60s 锁定窗口、成功清零计数。
 */
class LanAccessGateTest {

    private val key = "0123456789abcdef0123456789abcdef"
    private var now = 1_000L

    private fun gate(): LanAccessGate = LanAccessGate(key) { now }

    @Test
    fun validKeyQueryLeadsToLogin() {
        val decision = gate().decide("192.168.1.7", key, emptyList())
        assertTrue(decision is LanAccessGate.Decision.Login)
        val cookie = (decision as LanAccessGate.Decision.Login).cookieValue
        // 下发的 cookie 即闭环可续访
        assertEquals(
            LanAccessGate.Decision.Pass::class,
            gate().decide("192.168.1.7", null, listOf(cookie)).javaClass.kotlin,
        )
    }

    @Test
    fun wrongKeyQueryIsRejectedAndCounted() {
        val g = gate()
        assertEquals(LanAccessGate.Decision.Rejected, g.decide("ip", "wrong", emptyList()))
        assertEquals(LanAccessGate.Decision.Rejected, g.decide("ip", null, listOf("bad")))
    }

    @Test
    fun missingCookieRejected() {
        assertEquals(
            LanAccessGate.Decision.Rejected,
            gate().decide("ip", null, emptyList()),
        )
    }

    @Test
    fun validCookiePasses() {
        val good = LanAccessSecrets.cookieValueFor(key)
        assertEquals(
            LanAccessGate.Decision.Pass,
            gate().decide("ip", null, listOf(good)),
        )
    }

    @Test
    fun tenConsecutiveFailuresLockSixtySeconds() {
        val g = gate()
        val ip = "10.0.0.2"
        repeat(LanAccessGate.MAX_CONSECUTIVE_FAILURES - 1) {
            assertEquals(LanAccessGate.Decision.Rejected, g.decide(ip, "wrong-$it", emptyList()))
        }
        // 第 10 次失败仍按拒绝落地，同时进入锁定。
        assertEquals(LanAccessGate.Decision.Rejected, g.decide(ip, "wrong-final", emptyList()))
        // 锁定窗口内任何请求（正确的密钥也一样）都被限速。
        assertEquals(LanAccessGate.Decision.RateLimited, g.decide(ip, key, emptyList()))
        now += 1_000
        assertEquals(LanAccessGate.Decision.RateLimited, g.decide(ip, key, emptyList()))
        // 60s 窗口过后恢复重新计账：正确密钥直接通过。
        now += LanAccessGate.RATE_LOCK_MILLIS + 1
        assertEquals(
            LanAccessGate.Decision.Login::class,
            g.decide(ip, key, emptyList()).javaClass.kotlin,
        )
    }

    @Test
    fun successClearsFailureCounter() {
        val g = gate()
        val ip = "10.0.0.3"
        repeat(LanAccessGate.MAX_CONSECUTIVE_FAILURES - 1) {
            g.decide(ip, "wrong", emptyList())
        }
        // 成功清零：随后再累计一轮并未达到阈值也不会锁。
        assertEquals(LanAccessGate.Decision.Pass, g.decide(ip, null, listOf(LanAccessSecrets.cookieValueFor(key))))
        repeat(LanAccessGate.MAX_CONSECUTIVE_FAILURES - 1) {
            g.decide(ip, "wrong-again", emptyList())
        }
        assertEquals(LanAccessGate.Decision.Rejected, g.decide(ip, "wrong-end", emptyList()))
    }

    @Test
    fun rateLimitIsPerIp() {
        val g = gate()
        val locked = "10.0.0.9"
        repeat(LanAccessGate.MAX_CONSECUTIVE_FAILURES) {
            g.decide(locked, "wrong", emptyList())
        }
        assertEquals(LanAccessGate.Decision.RateLimited, g.decide(locked, key, emptyList()))
        // 其它 IP 不受牵连。
        assertEquals(
            LanAccessGate.Decision.Login::class,
            g.decide("10.0.0.10", key, emptyList()).javaClass.kotlin,
        )
    }

    @Test
    fun lockedIpCountReflectsWindows() {
        val g = gate()
        repeat(LanAccessGate.MAX_CONSECUTIVE_FAILURES) {
            g.decide("a", "wrong", emptyList())
        }
        assertEquals(1, g.lockedIpCount)
        now += LanAccessGate.RATE_LOCK_MILLIS + 1
        assertEquals(0, g.lockedIpCount)
    }
}
