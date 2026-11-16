package interlock.relay.core.exec.stream

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * loopback 流服务的契约（纯 JVM，真 socket）：
 * - 无 token / 错 token 一律 403，token 在 query 或 `X-Stream-Token` 头都收；
 * - `/stream` 单连接：第一次消费 200 + video/mp4 + 字节原样透传，第二次 409；
 * - `/health` 带 token 回状态 JSON；未知路径 404；非 GET 405；
 * - stop 之后：端口不再接、在途连接被掐断、[LoopbackStreamServer.Source.onStopped]
 * 恰好回调一次；
 * - token 只经构造返回值给出：服务对任何响应体/路径都不回显 token。
 */
class LoopbackStreamServerTest {

    private class FakeSource : LoopbackStreamServer.Source {
        override val metadata = "{\"mime\":\"video/mp4\",\"width\":640,\"height\":360,\"fps\":15}"
        var opened = 0
        var stopped = 0
        var endedCount = 0
        /** true 时字节读完不 EOF、挂到 close/onStopped 为止：给「在途连接」类用例造场景。 */
        @Volatile
        var holdUntilClosed = false
        val streamBytes = ByteArray(64) { (it * 3).toByte() }
        private val latch = CountDownLatch(1)

        override fun open(): InputStream? {
            if (opened > 0) return null
            opened++
            return object : InputStream() {
                private var at = 0
                override fun read(): Int {
                    if (at >= streamBytes.size) {
                        if (!holdUntilClosed) return -1
                        latch.await(5, TimeUnit.SECONDS)
                        return -1
                    }
                    return streamBytes[at++].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (at >= streamBytes.size) {
                        if (!holdUntilClosed) return -1
                        latch.await(5, TimeUnit.SECONDS)
                        return -1
                    }
                    val n = minOf(len, streamBytes.size - at)
                    System.arraycopy(streamBytes, at, b, off, n)
                    at += n
                    return n
                }

                override fun close() {
                    latch.countDown()
                }
            }
        }

        override fun onStopped() {
            stopped++
            latch.countDown()
        }

        override fun onStreamEnded() {
            endedCount++
            latch.countDown()
        }
    }

    private lateinit var source: FakeSource
    private lateinit var server: LoopbackStreamServer

    @Before
    fun setUp() {
        source = FakeSource()
        server = LoopbackStreamServer(source, token = "test-token-123", port = 0)
        assertTrue(server.start())
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun get(target: String, headerToken: String? = null): Triple<Int, String, String> {
        val socket = Socket(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), server.port)
        socket.soTimeout = 5_000
        socket.getInputStream().use { input ->
            socket.getOutputStream().use { output ->
                val request = buildString {
                    append("GET ").append(target).append(" HTTP/1.1\r\n")
                    if (headerToken != null) append("X-Stream-Token: ").append(headerToken).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(request.toByteArray(Charsets.ISO_8859_1))
                output.flush()
                val raw = input.readBytes()
                val text = String(raw, Charsets.ISO_8859_1)
                val status = text.substringAfter(" ").substringBefore(" ").toInt()
                return Triple(status, text, String(raw.copyOfRange(text.indexOf("\r\n\r\n") + 4, raw.size), Charsets.ISO_8859_1))
            }
        }
    }

    @Test
    fun `missing token is refused on every route`() {
        assertEquals(403, get(LoopbackStreamServer.PATH_STREAM + "?nothing=1").first)
        assertEquals(403, get(LoopbackStreamServer.PATH_HEALTH).first)
    }

    @Test
    fun `a wrong token is refused with the same status as a missing one`() {
        assertEquals(403, get(LoopbackStreamServer.PATH_STREAM + "?token=wrong").first)
        assertEquals(403, get(LoopbackStreamServer.PATH_HEALTH + "?token=wrong").first)
    }

    @Test
    fun `token is accepted from the query or the header`() {
        assertEquals(200, get(LoopbackStreamServer.PATH_HEALTH + "?token=test-token-123").first)
        assertEquals(200, get(LoopbackStreamServer.PATH_HEALTH, headerToken = "test-token-123").first)
    }

    @Test
    fun `stream serves the source bytes as video-mp4 exactly once`() {
        val (status, head, body) = get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        assertEquals(200, status)
        assertTrue(head.contains("video/mp4"))
        assertEquals(source.streamBytes.toList(), body.toByteArray(Charsets.ISO_8859_1).toList())
    }

    @Test
    fun `a second stream request gets 409 even before the first client disconnects`() {
        source.holdUntilClosed = true
        // 第一个客户端存在不读完：源阻塞在 read 上
        val first = Socket(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), server.port)
        first.soTimeout = 5_000
        first.getOutputStream().apply {
            write("GET ${LoopbackStreamServer.PATH_STREAM}?token=test-token-123 HTTP/1.1\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        // 等第一个连接进流式（源已 open）
        val deadline = System.currentTimeMillis() + 5_000
        while (source.opened == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(1, source.opened)

        val second = get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        assertEquals(409, second.first)
        // 断开第一个，让线程收尾
        first.close()
    }

    @Test
    fun `health reports connected and consumed state without echoing the token`() {
        val health = get(LoopbackStreamServer.PATH_HEALTH + "?token=test-token-123")
        assertEquals(200, health.first)
        assertTrue(health.second.contains("application/json"))
        assertTrue(health.third.contains("\"consumed\":false"))
        assertFalse("token must never appear in a response", health.second.contains("test-token-123"))
        assertFalse(health.third.contains("test-token-123"))
    }

    @Test
    fun `unknown paths are 404 and non-GET is 405`() {
        assertEquals(404, get(LoopbackStreamServer.PATH_STREAM + "x?token=test-token-123").first)
        val socket = Socket(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), server.port)
        socket.soTimeout = 5_000
        socket.use {
            it.getOutputStream().write("POST ${LoopbackStreamServer.PATH_HEALTH}?token=test-token-123 HTTP/1.1\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            val text = String(it.getInputStream().readBytes(), Charsets.ISO_8859_1)
            assertEquals(405, text.substringAfter(" ").substringBefore(" ").toInt())
        }
    }

    @Test
    fun `a slow-drip header is cut by the total deadline, not strung along forever`() {
        // 每 900ms 送 1 字节：任何"每次 read 超时"都拦不住它（每次都在 1s 内到位），
        // 只有总期限能 —— 连接必须在 HEAD_TOTAL_MS 量级内被关掉。
        val socket = Socket(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), server.port)
        socket.soTimeout = 8_000
        val startedAt = System.currentTimeMillis()
        val drip = Thread {
            runCatching {
                val out = socket.getOutputStream()
                for (i in 1..8) {
                    out.write('G'.code)
                    out.flush()
                    Thread.sleep(900)
                }
            }
        }.apply { isDaemon = true; start() }
        val closed = runCatching {
            val input = socket.getInputStream()
            // 读到连接被关（EOF/异常）为止；总期限 2s + 处理余量
            while (true) {
                if (input.read() == -1) return@runCatching true
            }
            @Suppress("UNREACHABLE_CODE") false
        }.getOrDefault(false)
        val elapsed = System.currentTimeMillis() - startedAt
        drip.join(1_000)
        assertTrue("the slow-drip connection must be cut by the total deadline", closed)
        assertTrue("cut must happen in the order of HEAD_TOTAL_MS, took " + elapsed + "ms", elapsed < 8_000)
        socket.close()
    }

    @Test
    fun `oversized request head is answered with 400 instead of hanging the connection`() {
        val socket = Socket(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), server.port)
        socket.soTimeout = 10_000
        socket.use {
            it.getOutputStream().write(ByteArray(64 * 1024) { 'A'.code.toByte() })
            it.getOutputStream().flush()
            val text = String(it.getInputStream().readBytes(), Charsets.ISO_8859_1)
            assertTrue(text.startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun `stop closes the port, cuts the active connection and fires onStopped exactly once`() {
        source.holdUntilClosed = true
        // 建立一个在途流连接
        val active = Socket(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), server.port)
        active.soTimeout = 5_000
        active.getOutputStream().apply {
            write("GET ${LoopbackStreamServer.PATH_STREAM}?token=test-token-123 HTTP/1.1\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (source.opened == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)

        server.stop()
        assertEquals("onStopped fires once per stop", 1, source.stopped)
        server.stop()
        assertEquals("a repeated stop must not fire onStopped again", 1, source.stopped)

        // 在途连接被掐断：onStopped 放开了源，转发循环读到 EOF 关连接 —— 客户端这头
        // 必须在有限时间内见到 EOF，而不是一直存在。
        active.soTimeout = 5_000
        val sawEof = runCatching {
            val input = active.getInputStream()
            while (true) {
                if (input.read() == -1) return@runCatching true
            }
            @Suppress("UNREACHABLE_CODE") false
        }.getOrDefault(false)
        assertTrue("the active connection must end (EOF) once the server stops", sawEof)
        active.close()

        // 端口不再接新连接
        val refused = runCatching {
            val probe = Socket()
            probe.connect(java.net.InetSocketAddress(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), server.port), 1_000)
            probe.close()
            false
        }.getOrDefault(true)
        assertTrue("a stopped server must refuse new connections", refused)
    }

    @Test
    fun `an exhausted source answers 409 with a readable reason`() {
        // 第一次消费并正常结束
        get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        val deadline = System.currentTimeMillis() + 5_000
        while (source.opened == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        // 源已经 open 过一次：第二次即便闩位允许也拿不到流
        server = LoopbackStreamServer(source, token = "test-token-123", port = 0)
        server.start()
        val again = get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        assertEquals(409, again.first)
        assertTrue(again.second.contains("no longer available"))
    }

    @Test
    fun `the server is bound to 127_0_0_1 and nothing else`() {
        // boundAddress / boundLoopback 记录 ServerSocket.inetAddress 的实际值：
        // 两者一致地指向 loopback，才算是"真绑在 127.0.0.1"。
        assertEquals("127.0.0.1", server.boundAddress)
        assertEquals(true, server.boundLoopback)
        // endpoint 也只讲 loopback：没有第二条出网路径。
        assertTrue(server.endpoint.startsWith("http://127.0.0.1:"))
        // 实连验证：按 boundAddress 报告的地址真连得上这个端口。
        val probe = Socket(InetAddress.getByName(server.boundAddress), server.port)
        assertTrue(probe.inetAddress.isLoopbackAddress)
        probe.close()
        // stop 之后如实清空（不是回到某个常量）
        server.stop()
        assertNull(server.boundAddress)
        assertNull(server.boundLoopback)
    }

    @Test
    fun `a silent unauthenticated connection is closed within the head deadline without threads`() {
        val silent = Socket(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), server.port)
        // 不发任何字节：读头 1 秒超时后连接被就地关掉 —— 客户端这头见 EOF/异常。
        silent.soTimeout = 5_000
        val closed = runCatching {
            val first = silent.getInputStream().read()
            first == -1
        }.getOrDefault(false)
        assertTrue("a silent connection must be cut by the head deadline", closed)
        silent.close()
        // 服务器还活着，且未鉴权场景没有留下任何已消费状态。
        assertEquals(200, get(LoopbackStreamServer.PATH_HEALTH + "?token=test-token-123").first)
        assertEquals(0, source.opened)
    }

    @Test
    fun `several concurrent wrong-token connections all get 403 and none of them opens the source`() {
        val statuses = java.util.concurrent.atomic.AtomicIntegerArray(5)
        val probes = (0..4).map { index ->
            Thread {
                runCatching {
                    statuses.set(index, get(LoopbackStreamServer.PATH_STREAM + "?token=wrong-" + index).first)
                }
            }
        }
        probes.forEach { it.start() }
        probes.forEach { it.join(5_000) }
        probes.forEach { assertTrue("each wrong-token probe must finish", !it.isAlive) }
        for (index in 0..4) {
            assertEquals("wrong token " + index + " must be answered 403", 403, statuses.get(index))
        }
        assertEquals("wrong tokens must never reach the source", 0, source.opened)
        assertEquals(200, get(LoopbackStreamServer.PATH_HEALTH + "?token=test-token-123").first)
    }

    @Test
    fun `a naturally ended stream reports consumed and marks the source ended`() {
        get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        val deadline = System.currentTimeMillis() + 5_000
        while (source.endedCount == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("the relay must report EOF through onStreamEnded", 1, source.endedCount)
        val health = get(LoopbackStreamServer.PATH_HEALTH + "?token=test-token-123")
        assertTrue(health.third.contains("\"consumed\":true"))
        // 服务还开着：EOF 不等于停机；stop 仍归宿主。
        assertEquals(200, health.first)
    }

    @Test
    fun `endpoint never carries the token and the port is on loopback only`() {
        assertFalse(server.endpoint.contains("test-token-123"))
        val address = java.net.InetAddress.getByName(LoopbackStreamServer.LOOPBACK)
        val probe = Socket()
        probe.connect(java.net.InetSocketAddress(address, server.port), 1_000)
        probe.close()
    }

    @Test
    fun `the bytes already written are counted for the host-side diagnostics`() {
        assertEquals("nothing written before a client shows up", 0L, server.bytesWritten)
        val (status, _, body) = get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        assertEquals(200, status)
        // 客户端拿到的字节数与计数器一致：面板黑屏时靠它判断"到底有没有收到数据"。
        assertEquals(source.streamBytes.size.toLong(), server.bytesWritten)
        assertEquals(source.streamBytes.size, body.toByteArray(Charsets.ISO_8859_1).size)
    }

    @Test
    fun `a naturally ended stream is recorded as eof`() {
        get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        val deadline = System.currentTimeMillis() + 5_000
        while (server.endReason == StreamEndReason.NONE && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(StreamEndReason.EOF, server.endReason)
        assertEquals("eof", server.endReason.result)
    }

    @Test
    fun `a client that goes away is recorded as client gone, not as a device error`() {
        // 大数据源：客户端在流还没写完时就断开 → 写不出去 → 客户端走了
        // （不是设备侧读失败）。源大到能撑过 socket 缓冲，断开一定会撞上一次写失败。
        val big = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val bigSource = object : LoopbackStreamServer.Source {
            override val metadata = "{}"
            private val opened = java.util.concurrent.atomic.AtomicBoolean(false)
            override fun open(): InputStream? {
                if (!opened.compareAndSet(false, true)) return null
                return ByteArrayInputStream(big)
            }

            override fun onStopped() = Unit
            override fun onStreamEnded() = Unit
        }
        val bigServer = LoopbackStreamServer(bigSource, token = "big-token", port = 0)
        assertTrue(bigServer.start())
        try {
            val client = Socket(InetAddress.getByName(LoopbackStreamServer.LOOPBACK), bigServer.port)
            client.soTimeout = 5_000
            client.getOutputStream().apply {
                write("GET ${LoopbackStreamServer.PATH_STREAM}?token=big-token HTTP/1.1\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            // 读到有数据为止，随后立刻断开（不等它写完）。
            client.getInputStream().read(ByteArray(4096))
            client.close()

            val end = System.currentTimeMillis() + 10_000
            while (bigServer.endReason == StreamEndReason.NONE && System.currentTimeMillis() < end) {
                Thread.sleep(20)
            }
            assertEquals(
                "客户端走了必须与设备侧 EOF 分开",
                StreamEndReason.CLIENT_GONE,
                bigServer.endReason,
            )
        } finally {
            bigServer.stop()
        }
    }

    @Test
    fun `a second consumer is recorded as a conflict`() {
        // 源只可消费一次：第二次来取流 → 409，且这一届会话的收场原因是 conflict。
        get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        val deadline = System.currentTimeMillis() + 5_000
        while (source.opened == 0 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        server = LoopbackStreamServer(source, token = "test-token-123", port = 0)
        assertTrue(server.start())
        val again = get(LoopbackStreamServer.PATH_STREAM + "?token=test-token-123")
        assertEquals(409, again.first)
        assertEquals("会话被占用与客户端走了必须能分开", StreamEndReason.CONFLICT, server.endReason)
    }
}
