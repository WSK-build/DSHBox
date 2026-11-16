package interlock.relay.core.exec.stream

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 面板输入工人池的生命周期：容器 stop→start 复用同一个 RelayContainer，
 * 池被 stop 关掉后下一次输入必须自动重建（否则重启沙箱后每次 `/input` 都
 * RejectedExecutionException，面板永久 502）。
 */
class PanelInputWorkersTest {

    @Test
    fun `a request runs on the worker pool and returns its result`() = runBlocking {
        val workers = PanelInputWorkers(1)
        try {
            assertEquals(true to "ok", workers.execute { true to "ok" })
        } finally {
            workers.shutdown()
        }
    }

    @Test
    fun `after shutdown the next request recreates the pool`() = runBlocking {
        val workers = PanelInputWorkers(1)
        try {
            assertEquals(true to "first", workers.execute { true to "first" })
            workers.shutdown()
            assertEquals("a container restart must not kill panel input", true to "second",
                workers.execute { true to "second" })
        } finally {
            workers.shutdown()
        }
    }

    @Test
    fun `a throwing block becomes a failed result, not a crashed continuation`() = runBlocking {
        val workers = PanelInputWorkers(1)
        try {
            val result = workers.execute { error("boom") }
            assertEquals(false, result.first)
            assertTrue(result.second.startsWith("panel input error"))
        } finally {
            workers.shutdown()
        }
    }

    @Test
    fun `shutdown is idempotent`() {
        val workers = PanelInputWorkers(1)
        workers.shutdown()
        workers.shutdown()
    }
}
