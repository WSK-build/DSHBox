package developer.access.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthLimiterTest {

    @Test
    fun `未达阈值时不进入退避`() {
        val limiter = AuthLimiter(threshold = 3, baseBackoffMs = 100L)
        repeat(2) { assertEquals(0L, limiter.recordFailure("peer", 0L)) }
        assertFalse(limiter.isLocked("peer", 0L))
    }

    @Test
    fun `达到阈值后进入退避`() {
        val limiter = AuthLimiter(threshold = 2, baseBackoffMs = 100L)
        limiter.recordFailure("peer", 0L)
        val backoff = limiter.recordFailure("peer", 0L)
        assertEquals(100L, backoff)
        assertTrue(limiter.isLocked("peer", 0L))
    }

    @Test
    fun `退避随失败次数指数增长且有上限`() {
        val limiter = AuthLimiter(threshold = 1, baseBackoffMs = 100L, maxBackoffMs = 400L)
        assertEquals(100L, limiter.recordFailure("p", 0L))
        assertEquals(200L, limiter.recordFailure("p", 0L))
        assertEquals(400L, limiter.recordFailure("p", 0L))
        assertEquals(400L, limiter.recordFailure("p", 0L))
    }

    @Test
    fun `退避窗口过后不再锁定`() {
        val limiter = AuthLimiter(threshold = 1, baseBackoffMs = 100L)
        limiter.recordFailure("peer", 0L)
        assertTrue(limiter.isLocked("peer", 50L))
        assertFalse(limiter.isLocked("peer", 100L))
    }

    @Test
    fun `认证成功后清空失败计数`() {
        val limiter = AuthLimiter(threshold = 2, baseBackoffMs = 100L)
        limiter.recordFailure("peer", 0L)
        limiter.recordFailure("peer", 0L)
        assertTrue(limiter.isLocked("peer", 0L))
        limiter.recordSuccess("peer")
        assertFalse(limiter.isLocked("peer", 0L))
        assertEquals(0L, limiter.recordFailure("peer", 0L))
    }

    @Test
    fun `不同对端的失败计数互不影响`() {
        val limiter = AuthLimiter(threshold = 1, baseBackoffMs = 100L)
        limiter.recordFailure("a", 0L)
        assertTrue(limiter.isLocked("a", 0L))
        assertFalse(limiter.isLocked("b", 0L))
    }

    @Test
    fun `持续失败到达上限后永久拒绝`() {
        val limiter = AuthLimiter(threshold = 1, baseBackoffMs = 100L, lockoutThreshold = 3)
        assertEquals(100L, limiter.recordFailure("p", 0L))
        assertEquals(200L, limiter.recordFailure("p", 0L))
        assertEquals(AuthLimiter.LOCKED_OUT, limiter.recordFailure("p", 0L))
        assertTrue(limiter.isLockedOut())
        // 时间过去不会自动解除：只能重新生成访问码。
        assertTrue(limiter.isLockedOut())
    }

    @Test
    fun `重新生成访问码后解除永久拒绝`() {
        val limiter = AuthLimiter(threshold = 1, baseBackoffMs = 100L, lockoutThreshold = 2)
        limiter.recordFailure("p", 0L)
        limiter.recordFailure("p", 0L)
        assertTrue(limiter.isLockedOut())
        limiter.reset()
        assertFalse(limiter.isLockedOut())
        assertFalse(limiter.isLocked("p", 0L))
    }
}
