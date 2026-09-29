package interlock.relay.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 启动状态机的全迁移矩阵。
 *
 * 这台机器是 start/stop 与失败重试的唯一裁决点，每条边都要有人证明：允许的迁移
 * 放行、非法的迁移被拒而不是被覆盖 —— 少一条证明，「失败后无法重试」就会以新的
 * 形状回来。机器是纯 JVM 的，这里也不沾任何 Android 类型。
 */
class RelayRuntimePhaseTest {

    @Test
    fun `STOPPED 经 STARTING 到 RUNNING`() {
        val machine = RuntimeStateMachine()
        assertTrue(machine.beginStart())
        assertEquals(RuntimePhase.STARTING, machine.phase)
        machine.commitRunning()
        assertEquals(RuntimePhase.RUNNING, machine.phase)
        assertNull(machine.degradedReason)
    }

    @Test
    fun `STARTING 中失败落入 DEGRADED 并记录原因`() {
        val machine = RuntimeStateMachine()
        assertTrue(machine.beginStart())
        assertTrue(machine.fail("mailbox IllegalStateException: x"))
        assertEquals(RuntimePhase.DEGRADED, machine.phase)
        assertEquals("mailbox IllegalStateException: x", machine.degradedReason)
    }

    @Test
    fun `DEGRADED 允许重试再次进入 STARTING`() {
        val machine = RuntimeStateMachine()
        machine.beginStart()
        machine.fail("ledger IOException: slow disk")
        assertTrue(machine.beginStart())
        assertEquals(RuntimePhase.STARTING, machine.phase)
        // 重试成功后旧原因清空：RUNNING 的机器不该还背着上一场失败的账。
        machine.commitRunning()
        assertEquals(RuntimePhase.RUNNING, machine.phase)
        assertNull(machine.degradedReason)
    }

    @Test
    fun `RUNNING 中重复 start 被拒`() {
        val machine = RuntimeStateMachine()
        machine.beginStart()
        machine.commitRunning()
        assertFalse(machine.beginStart())
        assertEquals(RuntimePhase.RUNNING, machine.phase)
    }

    @Test
    fun `STOPPED 中 stop 被拒`() {
        val machine = RuntimeStateMachine()
        assertFalse(machine.beginStop())
        assertEquals(RuntimePhase.STOPPED, machine.phase)
    }

    @Test
    fun `STARTING 与 RUNNING 都能停回 STOPPED`() {
        val starting = RuntimeStateMachine()
        assertTrue(starting.beginStart())
        assertTrue(starting.beginStop())
        assertEquals(RuntimePhase.STOPPED, starting.phase)

        val running = RuntimeStateMachine()
        running.beginStart()
        running.commitRunning()
        assertTrue(running.beginStop())
        assertEquals(RuntimePhase.STOPPED, running.phase)
    }

    @Test
    fun `非 STARTING 阶段 fail 被拒`() {
        val machine = RuntimeStateMachine()
        assertFalse(machine.fail("nowhere"))
        machine.beginStart()
        machine.commitRunning()
        assertFalse(machine.fail("too late"))
        assertEquals(RuntimePhase.RUNNING, machine.phase)
    }

    @Test
    fun `迟到的 commitRunning 是空操作`() {
        val machine = RuntimeStateMachine()
        machine.beginStart()
        assertTrue(machine.beginStop())
        machine.commitRunning()
        assertEquals(RuntimePhase.STOPPED, machine.phase)
    }

    @Test
    fun `迁移按顺序通知回调`() {
        val seen = mutableListOf<RuntimePhase>()
        val machine = RuntimeStateMachine { seen.add(it) }
        machine.beginStart()
        machine.commitRunning()
        machine.beginStop()
        machine.beginStart()
        machine.fail("assets boom")
        assertEquals(
            listOf(
                RuntimePhase.STARTING,
                RuntimePhase.RUNNING,
                RuntimePhase.STOPPED,
                RuntimePhase.STARTING,
                RuntimePhase.DEGRADED,
            ),
            seen,
        )
    }

    @Test
    fun `并发 beginStart 只有一个成功`() {
        val machine = RuntimeStateMachine()
        val threads = 8
        val barrier = CyclicBarrier(threads)
        val successes = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threads)
        val done = CountDownLatch(threads)
        repeat(threads) {
            pool.execute {
                barrier.await()
                if (machine.beginStart()) successes.incrementAndGet()
                done.countDown()
            }
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, successes.get())
        assertEquals(RuntimePhase.STARTING, machine.phase)
    }
}
