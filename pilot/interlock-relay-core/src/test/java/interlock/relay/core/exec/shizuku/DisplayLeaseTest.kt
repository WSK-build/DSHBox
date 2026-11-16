package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 屏租约簿：流会话持租约期间空闲计时器不得收屏；会话结束归还；
 * 收屏（或新屏）清空旧租约，不让上一块屏的引用计数漏到下一块。
 *
 * 纯 JVM 类，不碰 Android 类型；空闲计时器侧的判据只读 [RelayShellService.TrustedDisplay.hasLeases]。
 */
class DisplayLeaseTest {

    private val leases = RelayShellService.TrustedDisplay.DisplayLeases()

    @Test
    fun acquireMakesTheBookNonEmptyAndReturnsDistinctIds() {
        assertTrue(leases.isEmpty())
        val first = leases.acquire()
        val second = leases.acquire()
        assertTrue(first != second)
        assertFalse(leases.isEmpty())
    }

    @Test
    fun releaseDropsOnlyThatLease() {
        val first = leases.acquire()
        val second = leases.acquire()
        assertTrue(leases.release(first))
        assertFalse("还有另一条租约在场", leases.isEmpty())
        assertTrue(leases.release(second))
        assertTrue(leases.isEmpty())
    }

    @Test
    fun doubleReleaseIsHarmlessAndDoesNotAffectOthers() {
        val first = leases.acquire()
        val second = leases.acquire()
        assertTrue(leases.release(first))
        assertFalse("重复归还同一张租约不是成功", leases.release(first))
        assertFalse("第二张租约不应被误删", leases.isEmpty())
        assertTrue(leases.release(second))
        assertTrue(leases.isEmpty())
    }

    @Test
    fun unknownLeaseIdDoesNotChangeTheBook() {
        val only = leases.acquire()
        assertFalse(leases.release(only + 1000))
        assertFalse(leases.isEmpty())
        assertTrue(leases.release(only))
    }

    @Test
    fun clearDropsEveryLeaseForTheNextDisplay() {
        leases.acquire()
        leases.acquire()
        leases.clear()
        assertTrue(leases.isEmpty())
        // 清空后旧 id 的迟到归还不影响新租约。
        val fresh = leases.acquire()
        assertFalse(leases.isEmpty())
        assertTrue(leases.release(fresh))
        assertTrue(leases.isEmpty())
    }

    @Test
    fun idsAreNotReusedAfterClear() {
        val first = leases.acquire()
        leases.clear()
        val second = leases.acquire()
        assertEquals("清空只清现场，不倒退发号（迟到归还不能误删新租约）", first + 1, second)
        // 直接断言：旧 id 的迟到归还是个空操作，新租约不被误删。
        assertFalse(leases.release(first))
        assertFalse("新租约仍在", leases.isEmpty())
        assertTrue(leases.release(second))
        assertTrue(leases.isEmpty())
    }
}
