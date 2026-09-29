package interlock.relay.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 「同一件事正在等」登记表的并发内核测试。表要挡的是双等待者（一次批准被执行两遍），
 * 三条并发纪律在这里钉死：登记不覆盖未过期的占用、摘除只摘自己那份、过期登记不再
 * 短路新请求。全部可用注入时钟在无设备环境下穷举验证。
 */
class ParkedWaitTableTest {

    private var nowMs = 10_000L
    private val table = ParkedWaitTable { nowMs }

    @Test
    fun unexpiredHolderShortCircuitsWithoutBeingOverwritten() {
        val first = table.register("k", deadlineAtMs = 20_000L)
        assertNotNull(first)
        assertTrue(table.isSameQuestionParked("k"))

        // 未过期的占用不让位：后到者拿不到登记，走「已有同问在等」的短路，
        // 第一条的截止与身份保持原样。
        assertNull("未过期占用不覆盖", table.register("k", deadlineAtMs = 25_000L))
        assertTrue(table.isSameQuestionParked("k"))

        // 收尾只摘自己那份：摘完键位空出，下一条同问能正常登记。
        assertTrue(table.condRemove("k", first!!))
        assertFalse(table.isSameQuestionParked("k"))
        assertNotNull(table.register("k", deadlineAtMs = 25_000L))
    }

    /** 同问重入竞态的正面场景：截止过期窗口内同问重入不覆盖旧环、旧环退出只摘自己。 */
    @Test
    fun reentryInsideTheExpiryWindowDoesNotStealTheNewRegistration() {
        val stale = table.register("k", deadlineAtMs = 11_000L)
        assertNotNull(stale)
        // 旧环已到点、还卡在最长一轮退避里：登记仍在表上，但不再短路新请求。
        nowMs = 12_000L
        assertFalse("过期登记不再挡同问的重试", table.isSameQuestionParked("k"))

        // 同问重入：登记成功，旧的过期记录被换下。
        val fresh = table.register("k", deadlineAtMs = 60_000L)
        assertNotNull(fresh)
        assertTrue(table.isSameQuestionParked("k"))

        // 旧环此刻才收尾：条件摘除摘不到东西，新环的登记安然无恙——
        // 无条件删除在这里会把新环摘掉，第三个同问随即能挂上同一张框。
        assertFalse("旧环退出不得摘走新环的登记", table.condRemove("k", stale!!))
        assertTrue(table.isSameQuestionParked("k"))

        // 新环自己的收尾照常生效，且恰好摘一次。
        assertTrue(table.condRemove("k", fresh!!))
        assertFalse(table.condRemove("k", fresh))
        assertFalse(table.isSameQuestionParked("k"))
    }

    /** 两个等待环并发退出：各自的登记恰好被摘一次，谁也不碰谁的。 */
    @Test
    fun concurrentExitsRemoveEachRegistrationExactlyOnce() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val stale = table.register("k", deadlineAtMs = 11_000L)!!
                nowMs = 12_000L
                val fresh = table.register("k", deadlineAtMs = 60_000L)!!

                val start = CountDownLatch(1)
                val staleExit = pool.submit<Boolean> {
                    start.await()
                    table.condRemove("k", stale)
                }
                val freshExit = pool.submit<Boolean> {
                    start.await()
                    table.condRemove("k", fresh)
                }
                start.countDown()
                val staleRemoved = staleExit.get(5, TimeUnit.SECONDS)
                val freshRemoved = freshExit.get(5, TimeUnit.SECONDS)

                // 旧环（登记已被换下）永远摘不到东西；新环的登记恰好被摘一次。
                assertFalse("被换下的旧登记不得摘除成功", staleRemoved)
                assertTrue("新登记必须被自己的收尾摘掉", freshRemoved)
                assertFalse(table.isSameQuestionParked("k"))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 同一条登记被两个线程同时收尾：条件删除保证恰好一次成功。 */
    @Test
    fun doubleExitOfTheSameEntrySucceedsExactlyOnce() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(200) {
                val entry = table.register("k", deadlineAtMs = 20_000L)!!
                val start = CountDownLatch(1)
                val first = pool.submit<Boolean> { start.await(); table.condRemove("k", entry) }
                val second = pool.submit<Boolean> { start.await(); table.condRemove("k", entry) }
                start.countDown()
                val successes = listOf(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS))
                    .count { it }
                assertEquals("同一条登记只能被摘一次", 1, successes)
                assertFalse(table.isSameQuestionParked("k"))
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 判活按截止：过点的登记不再短路新请求；键上只剩过期记录时登记能直接换新。 */
    @Test
    fun expiredEntriesStopShortCircuitingAndYieldToFreshRegistration() {
        assertNotNull(table.register("k", deadlineAtMs = 10_500L))
        nowMs = 10_400L
        assertTrue(table.isSameQuestionParked("k"))
        nowMs = 10_500L
        assertFalse("截止到点即不再短路", table.isSameQuestionParked("k"))
        // 到点与真正收尾之间的窗口里，同问登记直接成功（旧的过期记录被换下）。
        assertNotNull(table.register("k", deadlineAtMs = 30_000L))
        assertTrue(table.isSameQuestionParked("k"))
    }

    /** 不同的键互不干扰：表按「同一件事」分格，一格的生死不影响另一格。 */
    @Test
    fun distinctKeysAreIndependent() {
        val a = table.register("capA:args-a", deadlineAtMs = 20_000L)
        val b = table.register("capB:args-b", deadlineAtMs = 20_000L)
        assertNotNull(a)
        assertNotNull(b)
        nowMs = 25_000L
        assertFalse(table.isSameQuestionParked("capA:args-a"))
        assertFalse(table.isSameQuestionParked("capB:args-b"))
        // 两条都过期后，各自的登记互不妨碍对方被换下。
        assertNotNull(table.register("capA:args-a", deadlineAtMs = 40_000L))
        assertTrue(table.condRemove("capB:args-b", b!!))
        assertTrue(table.isSameQuestionParked("capA:args-a"))
    }
}
