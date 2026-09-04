package com.dshbox.app.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/**
 * 1.1.1 (M9)：健康检查对 DSH 0.1.2-rc.1 认证行为的兼容性回归。
 * DSH 新版 webserver 对无 token 请求返回 401/302——任何 HTTP 响应都说明
 * webserver 存活，只有连不上/超时才判不健康（修复前只认 200..299，
 * 401 导致 DSH 被误判未就绪、120s 超时、进程残留占端口）。
 */
class HttpHealthCheckerTest {

    /** 起一个只回固定状态码的单请求 HTTP 服务，跑完即关。 */
    private suspend fun withResponse(
        statusLine: String,
        block: suspend (HttpHealthChecker) -> Unit,
    ) {
        val server = ServerSocket(0)
        val thread = Thread {
            try {
                // 循环 accept：健康检查会先做一次端口探测连接（无请求、直接关闭），
                // 再发起真正的 HTTP 请求——每个连接都要被消费。
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    try {
                        socket.use { s ->
                            val reader = s.getInputStream().bufferedReader()
                            var gotRequest = false
                            while (true) {
                                val line = reader.readLine() ?: break
                                gotRequest = true
                                if (line.isEmpty()) break
                            }
                            if (gotRequest) {
                                s.getOutputStream().write(
                                    "$statusLine\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // per-connection failure: keep serving
                    }
                }
            } catch (_: Exception) {
                // server closed
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            val checker = HttpHealthChecker(
                port = server.localPort,
                connectTimeoutMs = 1_000,
                readTimeoutMs = 2_000,
            )
            block(checker)
        } finally {
            server.close()
            thread.join(2_000)
        }
    }

    @Test
    fun `HTTP 401（认证要求）视为 webserver 存活`() = runBlocking {
        withResponse("HTTP/1.1 401 Unauthorized") { checker ->
            assertTrue(checker.check().webUiReady)
        }
    }

    @Test
    fun `HTTP 302（重定向）视为 webserver 存活`() = runBlocking {
        withResponse("HTTP/1.1 302 Found") { checker ->
            assertTrue(checker.check().webUiReady)
        }
    }

    @Test
    fun `HTTP 200 视为存活`() = runBlocking {
        withResponse("HTTP/1.1 200 OK") { checker ->
            assertTrue(checker.check().webUiReady)
        }
    }

    @Test
    fun `HTTP 500 视为存活（服务在处理请求即算活着）`() = runBlocking {
        withResponse("HTTP/1.1 500 Internal Server Error") { checker ->
            assertTrue(checker.check().webUiReady)
        }
    }

    @Test
    fun `端口未监听视为不健康`() = runBlocking {
        val checker = HttpHealthChecker(
            port = 1, // 无服务监听的端口
            connectTimeoutMs = 500,
            readTimeoutMs = 500,
        )
        assertFalse(checker.check().webUiReady)
    }

    @Test
    fun `404 路由缺失视为存活（SPA 路由由前端处理）`() = runBlocking {
        withResponse("HTTP/1.1 404 Not Found") { checker ->
            assertTrue(checker.check().webUiReady)
        }
    }
}