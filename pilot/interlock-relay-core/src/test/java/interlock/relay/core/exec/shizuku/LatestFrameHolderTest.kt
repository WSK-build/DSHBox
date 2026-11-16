package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 最新帧持有器：读不消费、写入替换并关闭旧帧、读持锁期间写方等待。
 *
 * 设备侧的 `Image` 不能进 JVM，本测用同形的可关闭假体驱动；流循环的
 * “填新帧/复用/黑帧”分支由代码审查与设备预览覆盖（JVM 驱动不了 MediaCodec/Image）。
 */
class LatestFrameHolderTest {

    private class FakeFrame(val id: Int) {
        var closed = false
    }

    private fun holder(): LatestFrameHolder<FakeFrame> =
        LatestFrameHolder { it.closed = true }

    @Test
    fun readDoesNotConsumeTheFrame() {
        val h = holder()
        val frame = FakeFrame(1)
        h.put(frame)

        assertEquals(1, h.with { it.id })
        assertFalse("读不是取走", frame.closed)
        assertEquals("第二次读仍是同一帧", 1, h.with { it.id })
        assertFalse(frame.closed)
    }

    @Test
    fun replacingClosesThePreviousFrame() {
        val h = holder()
        val first = FakeFrame(1)
        val second = FakeFrame(2)
        h.put(first)
        h.put(second)

        assertTrue("旧帧必须关闭", first.closed)
        assertFalse("新帧仍在用", second.closed)
        assertEquals(2, h.with { it.id })
    }

    @Test
    fun dropClosesAndEmpties() {
        val h = holder()
        val frame = FakeFrame(1)
        h.put(frame)
        h.drop()

        assertTrue(frame.closed)
        assertTrue(h.isEmpty())
        assertEquals(null, h.with { it.id })
    }

    @Test
    fun versionChangesOnlyWhenTheFrameIsReplaced() {
        val h = holder()
        h.put(FakeFrame(1))
        val first = h.withVersion { _, version -> version }
        val again = h.withVersion { _, version -> version }
        assertEquals("同一帧的两次读必须是同一代际", first, again)

        h.put(FakeFrame(2))
        val afterReplace = h.withVersion { _, version -> version }
        assertTrue("换帧后代际必须前进", afterReplace != null && afterReplace > first!!)
    }

    @Test
    fun emptyHolderDoesNotInvokeBlock() {
        val h = holder()
        var called = false
        assertEquals(null, h.with { called = true; it.id })
        assertFalse(called)
    }

    @Test
    fun writeWaitsWhileReaderHoldsTheLock() {
        val h = holder()
        val first = FakeFrame(1)
        val second = FakeFrame(2)
        h.put(first)

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val readDone = CountDownLatch(1)
        Thread {
            h.with {
                entered.countDown()
                release.await(5, TimeUnit.SECONDS)
                it.id
            }
            readDone.countDown()
        }.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val writeDone = CountDownLatch(1)
        Thread {
            h.put(second)
            writeDone.countDown()
        }.start()

        // 读方还持着锁：写方必须等，旧帧不能被提前关掉。
        assertFalse("读持锁期间 put 不得完成", writeDone.await(150, TimeUnit.MILLISECONDS))
        assertFalse(first.closed)

        release.countDown()
        assertTrue(readDone.await(5, TimeUnit.SECONDS))
        assertTrue(writeDone.await(5, TimeUnit.SECONDS))
        assertTrue("读完之后才关旧帧", first.closed)
        assertEquals(2, h.with { it.id })
    }
}
