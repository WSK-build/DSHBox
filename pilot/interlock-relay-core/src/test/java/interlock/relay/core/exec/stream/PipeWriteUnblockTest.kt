package interlock.relay.core.exec.stream

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 收场机制的 JVM 证明：**解阻塞写的一方必须是关读端的人**。
 *
 * 设备上的 pipe 是内核对象（POSIX 契约：写端阻塞在满管道上时，读端全部关闭会把
 * 该 write 唤醒成 EPIPE）；JVM 的 PipedStream 同一契约 —— 这里用它把这条机制在
 * 无设备环境下钉死：
 * - 负对照：读端活着，堵在满管道上的 writer **不会**自己退；
 * - 正例：关掉读端，阻塞中的 write 立刻失败（IOException），writer 退出。
 *
 * 这就是 TrustedStreamController 的 stop/watchdog 先关 readEnd、
 * RelayShellService 的 stop/watchdog **不碰管道** 的依据：服务端对写端做 close
 * 只会先 flush 再自挂，解不开任何内容。
 */
class PipeWriteUnblockTest {

    private class BlockedWriter(val output: PipedOutputStream) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val thread: Thread = Thread {
            try {
                val chunk = ByteArray(256)
                while (true) {
                    output.write(chunk) // 1024 缓冲第 5 块起阻塞
                }
            } catch (broken: Throwable) {
                failure.set(broken)
                done.countDown()
            }
        }.apply { isDaemon = true }
    }

    private fun blockedWriter(input: PipedInputStream, output: PipedOutputStream): BlockedWriter {
        val writer = BlockedWriter(output)
        writer.thread.start()
        // 等 writer 真的把缓冲写满并阻塞：留出远超缓冲的写循环时间
        assertFalse(writer.done.await(300, TimeUnit.MILLISECONDS))
        return writer
    }

    @Test
    fun `negative control - with the read end alive the blocked writer does not escape`() {
        val input = PipedInputStream(1024)
        val output = PipedOutputStream(input)
        val writer = blockedWriter(input, output)
        // 读端健在：writer 仍堵着（没结束、没失败）
        assertFalse(writer.done.await(500, TimeUnit.MILLISECONDS))
        assertNull(writer.failure.get())
        // 清场：关读端放它走（本用例只证明"不关就不走"）
        input.close()
        assertTrue(writer.done.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun `closing the read end unblocks a writer blocked on the full pipe`() {
        val input = PipedInputStream(1024)
        val output = PipedOutputStream(input)
        val writer = blockedWriter(input, output)
        // 宿主一侧的收场动作：关读端
        input.close()
        // 阻塞中的 write 被唤醒成失败，writer 退出 —— 这就是设备侧 EPIPE 的形状
        assertTrue(writer.done.await(3, TimeUnit.SECONDS))
        val failure = writer.failure.get()
        assertNotNull(failure)
        assertTrue(failure is IOException)
        writer.thread.join(1_000)
        assertFalse(writer.thread.isAlive)
    }

    @Test
    fun `host watchdog delay math - full budget, remainder, and a positive floor`() {
        // 刚开会话：等满 30 分钟
        assertEquals(MAX_STREAM_SESSION_MS, streamWatchdogDelayMs(startedAtMs = 1_000L, nowMs = 1_000L))
        // 快到期：等余量
        assertEquals(5L, streamWatchdogDelayMs(startedAtMs = 0L, nowMs = MAX_STREAM_SESSION_MS - 5L))
        // 已过点（时钟回拨/迟到的 watchdog）：至少再给 1ms，不抛不挂零
        assertEquals(1L, streamWatchdogDelayMs(startedAtMs = 0L, nowMs = MAX_STREAM_SESSION_MS + 10L))
    }
}
