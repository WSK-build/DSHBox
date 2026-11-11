package com.dshbox.remote.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * 代理管线集成测试：JVM 内嵌 mock 上游，覆盖普通转发、Set-Cookie 入罐与
 * 注入、302 Location 重写、SSE 流式透传、WS 升级字节泵、门禁 404 负例、
 * 端口占用显式报错与 HTTPS 上游（自签证书 + 会话级 TlsTrust）。
 */
class LoopbackProxyIntegrationTest {

    private val gateToken = "0123456789abcdef0123456789abcdef"
    private val gate = GateCheck(gateToken)

    private lateinit var upstream: HttpServer
    private val capturedHeaders = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val jar = CookieJar()
    private val proxies = mutableListOf<LoopbackProxy>()

    /** SSE 第二段事件的放行闩：客户端读到第一段后由测试放开。 */
    private val sseSecondChunkGate = CountDownLatch(1)

    /** 同名下载竞态：上游等两个连接都到齐再发响应体。 */
    private val raceArrivalLatch = CountDownLatch(2)
    private val raceArrivals = java.util.concurrent.atomic.AtomicInteger()

    companion object {
        private val RACE_BODY_FIRST = "RACE-ONE-BODY-AAAAAAAA".toByteArray()
        private val RACE_BODY_SECOND = "RACE-TWO-BODY-BBBBBBBB".toByteArray()
    }

    private fun gzipOf(raw: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        java.util.zip.GZIPOutputStream(out).use { it.write(raw) }
    }.toByteArray()

    @Before
    fun setUp() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.executor = Executors.newCachedThreadPool()
        registerHandlers(upstream)
        upstream.start()
    }

    @After
    fun tearDown() {
        proxies.forEach { it.stop() }
        upstream.stop(0)
    }

    private fun registerHandlers(server: HttpServer) {
        server.createContext("/hello") { exchange ->
            capture(exchange)
            respond(exchange, 200, "hello world")
        }
        // 下载分支的上游：附件（Content-Disposition）与纯二进制（octet-stream）
        server.createContext("/report") { exchange ->
            capture(exchange)
            val body = "PDF-BYTES-0123456789".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"report.pdf\"")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.createContext("/report-chunked") { exchange ->
            capture(exchange)
            val body = "CHUNKED-PDF-BYTES".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''%E6%8A%A5%E5%91%8A.pdf")
            // length 0 = chunked 输出：下载分支必须能流式解块落盘
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        // gzip + Content-Length：压缩响应的 CL 是压缩字节数，落盘须解压到尾块
        server.createContext("/report-gzip") { exchange ->
            capture(exchange)
            val compressed = gzipOf("GZIP-PDF-BYTES-0123456789".toByteArray())
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"report.pdf\"")
            exchange.sendResponseHeaders(200, compressed.size.toLong())
            exchange.responseBody.use { it.write(compressed) }
            exchange.close()
        }
        // chunked + gzip：先在原始流上解块再解压（顺序颠倒必失败）
        server.createContext("/report-gzip-chunked") { exchange ->
            capture(exchange)
            val compressed = gzipOf("GZIP-CHUNKED-PDF".toByteArray())
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"report.pdf\"")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(compressed) }
            exchange.close()
        }
        // br 编码不在支持范围：显式失败页，不留半包
        server.createContext("/report-br") { exchange ->
            capture(exchange)
            val body = "BROTLI-RAW-BYTES".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Encoding", "br")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"report.pdf\"")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        // 同名下载竞态：两连接都到齐上游才写响应体，迫使两个下载并发抢名
        server.createContext("/report-race") { exchange ->
            capture(exchange)
            val index = raceArrivals.incrementAndGet()
            raceArrivalLatch.countDown()
            assertTrue(raceArrivalLatch.await(10, TimeUnit.SECONDS))
            val body = if (index == 1) RACE_BODY_FIRST else RACE_BODY_SECOND
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"race.bin\"")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        // 零字节附件（上游合法产出，如空导出）：落盘后不得按「空占位」误删
        server.createContext("/report-zero") { exchange ->
            capture(exchange)
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.responseHeaders.add("Content-Disposition", "attachment; filename=\"empty.bin\"")
            exchange.sendResponseHeaders(200, -1)
            exchange.responseBody.use { /* 零字节响应体 */ }
            exchange.close()
        }
        server.createContext("/binary") { exchange ->
            capture(exchange)
            val body = "RAW-BINARY".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/octet-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.createContext("/login") { exchange ->
            capture(exchange)
            exchange.responseHeaders.add("Set-Cookie", "sid=abc123; Path=/; HttpOnly")
            exchange.responseHeaders.add("Set-Cookie", "pref=dark; Path=/")
            respond(exchange, 200, "logged in")
        }
        server.createContext("/page") { exchange ->
            capture(exchange)
            respond(exchange, 200, "page")
        }
        server.createContext("/redirect") { exchange ->
            capture(exchange)
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${upstream.address.port}/landing?next=2")
            respond(exchange, 302, emptyList(), null)
        }
        server.createContext("/csp") { exchange ->
            capture(exchange)
            exchange.responseHeaders.add("Content-Security-Policy", "default-src 'self'; upgrade-insecure-requests; img-src *")
            respond(exchange, 200, "csp")
        }
        server.createContext("/sse") { exchange ->
            capture(exchange)
            val out = exchange.responseBody
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            out.write("data: one\n\n".toByteArray())
            out.flush()
            // 等测试确认第一段已到达客户端，再发第二段：证明代理未缓冲
            assertTrue(sseSecondChunkGate.await(10, TimeUnit.SECONDS))
            out.write("data: two\n\n".toByteArray())
            out.flush()
            out.close()
        }
        server.createContext("/echo") { exchange ->
            capture(exchange)
            val body = exchange.requestBody.readBytes()
            exchange.requestHeaders.forEach { (name, values) ->
                if (name.equals("Content-Type", true)) {
                    exchange.responseHeaders.add("Content-Type", values.first())
                }
            }
            respond(exchange, 200, body)
        }
    }

    private fun capture(exchange: HttpExchange) {
        capturedHeaders[exchange.requestURI.path] = exchange.requestHeaders
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) =
        respond(exchange, status, listOf("text/plain; charset=utf-8"), body.toByteArray())

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) =
        respond(exchange, status, listOf("application/octet-stream"), body)

    private fun respond(exchange: HttpExchange, status: Int, contentTypes: List<String>, body: ByteArray?) {
        for (contentType in contentTypes) {
            exchange.responseHeaders.add("Content-Type", contentType)
        }
        exchange.sendResponseHeaders(status, body?.size?.toLong() ?: -1)
        if (body != null && body.isNotEmpty()) {
            exchange.responseBody.write(body)
        }
        exchange.close()
    }

    private fun startProxy(
        loopbackIp: String,
        requestedPort: Int = 0,
        jar: CookieJar = this.jar,
        injector: AuthInjector = PassThroughInjector,
        tls: TlsTrust = TlsTrust.systemDefault(),
        downloadDir: File? = null,
        downloadPages: DownloadPageRenderer? = fakeDownloadPages(),
    ): LoopbackProxy {
        val proxy = LoopbackProxy(
            loopbackIp = loopbackIp,
            requestedPort = requestedPort,
            upstreamOrigin = "http://127.0.0.1:${upstream.address.port}",
            jar = jar,
            gate = gate,
            injector = injector,
            tls = tls,
            downloadDir = downloadDir,
            downloadPages = downloadPages,
        )
        proxy.start()
        proxies.add(proxy)
        return proxy
    }

    /** 下载结果页文案的测试桩：断言页面内容与文件名透传。 */
    private fun fakeDownloadPages() = object : DownloadPageRenderer {
        override fun savedHtml(downloadDirName: String, fileName: String): String =
            "SAVED:$downloadDirName/$fileName"

        override fun failureHtml(detail: String): String = "FAILED:$detail"

        override fun dirUnavailableReason(): String = "DIR-UNAVAILABLE"
    }

    /** 原始 socket 客户端：发送请求字节并读完全部响应（至 EOF）。 */
    private fun readFullResponse(proxy: LoopbackProxy, request: String): String {
        Socket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(proxy.loopbackIp, proxy.boundPort), 5_000)
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            return readUntilEof(socket).toString(Charsets.ISO_8859_1)
        }
    }

    private fun readUntilEof(socket: Socket): ByteArray {
        val out = ByteArrayOutputStream()
        val input = socket.getInputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (e: IOException) {
                break
            }
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun gateCookie(): String = "Cookie: dshbox_gate=$gateToken"

    @Test fun plainGetIsForwardedWithRewrittenHeaders() {
        val proxy = startProxy("127.0.0.2")
        val response = readFullResponse(
            proxy,
            "GET /hello HTTP/1.1\r\nHost: 127.0.0.2:${proxy.boundPort}\r\n${gateCookie()}\r\nConnection: close\r\n\r\n",
        )
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("hello world"))
        val seen = capturedHeaders.getValue("/hello")
        assertEquals("127.0.0.1:${upstream.address.port}", seen["Host"]!!.single())
        // 门禁 cookie 不外泄上游
        assertFalse(seen["Cookie"].orEmpty().joinToString().contains("dshbox_gate"))
    }

    @Test fun setCookiesEnterJarAndNonSensitiveOnesDualWrite() {
        val proxy = startProxy("127.0.0.3")
        val first = readFullResponse(
            proxy,
            "GET /login HTTP/1.1\r\nHost: 127.0.0.3\r\n${gateCookie()}\r\nConnection: close\r\n\r\n",
        )
        assertTrue(first.startsWith("HTTP/1.1 200"))
        // 渲染器只看到非 HttpOnly 的双写（Domain/Secure 已剥）
        val setCookies = headerValues(first, "Set-Cookie")
        assertTrue(setCookies.any { it.startsWith("pref=dark") && !it.contains("Domain") })
        assertFalse(setCookies.any { it.startsWith("sid=") })

        // jar 已持有两条 cookie，后续请求注入上游
        readFullResponse(proxy, "GET /page HTTP/1.1\r\nHost: 127.0.0.3\r\n${gateCookie()}\r\nConnection: close\r\n\r\n")
        val pageHeaders = capturedHeaders.getValue("/page")
        val cookieHeader = pageHeaders["Cookie"]?.joinToString("; ") ?: ""
        assertTrue(cookieHeader.contains("sid=abc123"))
        assertTrue(cookieHeader.contains("pref=dark"))
        assertFalse(cookieHeader.contains("dshbox_gate"))
    }

    @Test fun absoluteRedirectLocationIsRewrittenToLoopback() {
        val proxy = startProxy("127.0.0.4")
        val response = readFullResponse(
            proxy,
            "GET /redirect HTTP/1.1\r\nHost: 127.0.0.4\r\n${gateCookie()}\r\nConnection: close\r\n\r\n",
        )
        assertTrue(response.startsWith("HTTP/1.1 302"))
        val location = headerValues(response, "Location").single()
        assertEquals("http://127.0.0.4:${proxy.boundPort}/landing?next=2", location)
    }

    @Test fun cspUpgradeInsecureIsStripped() {
        val proxy = startProxy("127.0.0.2")
        val response = readFullResponse(proxy, "GET /csp HTTP/1.1\r\nHost: x\r\n${gateCookie()}\r\n\r\n")
        val csp = headerValues(response, "Content-Security-Policy").single()
        assertFalse(csp.contains("upgrade-insecure-requests"))
        assertTrue(csp.contains("default-src 'self'"))
    }

    @Test fun postBodyIsForwarded() {
        val proxy = startProxy("127.0.0.2")
        val payload = "field=value&extra=1"
        val response = readFullResponse(
            proxy,
            "POST /echo HTTP/1.1\r\nHost: x\r\n${gateCookie()}\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${payload.length}\r\nConnection: close\r\n\r\n$payload",
        )
        assertTrue(response.contains("field=value&extra=1"))
    }

    @Test fun sseStreamsProgressivelyWithoutBuffering() {
        val proxy = startProxy("127.0.0.5")
        Socket().use { socket ->
            socket.soTimeout = 20_000
            socket.connect(InetSocketAddress(proxy.loopbackIp, proxy.boundPort), 5_000)
            socket.getOutputStream().write(
                "GET /sse HTTP/1.1\r\nHost: x\r\n${gateCookie()}\r\n\r\n".toByteArray(),
            )
            val input = socket.getInputStream()
            val seen = StringBuilder()
            val buffer = ByteArray(1024)
            var firstSeen = false
            while (!seen.contains("data: two")) {
                val read = input.read(buffer)
                if (read < 0) break
                seen.append(String(buffer, 0, read, Charsets.ISO_8859_1))
                if (!firstSeen && seen.contains("data: one")) {
                    firstSeen = true
                    // 第一段已实时到达客户端，放行服务端发第二段
                    sseSecondChunkGate.countDown()
                }
            }
            assertTrue(firstSeen)
            assertTrue(seen.contains("data: two"))
        }
    }

    @Test fun websocketUpgradeTunnelsBytesBidirectionally() {
        val wsServer = ServerSocket()
        wsServer.bind(InetSocketAddress("127.0.0.1", 0), 8)
        val wsThread = Thread {
            wsServer.accept().use { client ->
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
                while (reader.readLine()?.isNotEmpty() == true) { /* 读掉握手头 */ }
                client.getOutputStream().write(
                    ("HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                client.getOutputStream().flush()
                val buffer = ByteArray(1024)
                while (true) {
                    val read = client.getInputStream().read(buffer)
                    if (read < 0) break
                    client.getOutputStream().write(buffer, 0, read)
                    client.getOutputStream().flush()
                }
            }
        }
        wsThread.isDaemon = true
        wsThread.start()

        // 经 HTTPS 风格无关：WS 上游同样是明文端口，这里验证透传与字节泵
        val proxy = LoopbackProxy(
            loopbackIp = "127.0.0.6",
            requestedPort = 0,
            upstreamOrigin = "http://127.0.0.1:${wsServer.localPort}",
            jar = jar,
            gate = gate,
        )
        proxy.start()
        proxies.add(proxy)

        Socket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(proxy.loopbackIp, proxy.boundPort), 5_000)
            val out = socket.getOutputStream()
            out.write(
                ("GET /ws HTTP/1.1\r\nHost: x\r\n${gateCookie()}\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
            )
            out.flush()
            // 读到 101 头结束
            val head = readHeadBytes(socket.getInputStream())
            assertTrue(String(head, Charsets.ISO_8859_1).startsWith("HTTP/1.1 101"))
            // 双向字节泵：echo
            val payload = "frame-bytes-\u0001\u0002\u0003"
            out.write(payload.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val input = socket.getInputStream()
            val echoed = StringBuilder()
            val buffer = ByteArray(1024)
            while (echoed.length < payload.length) {
                val read = input.read(buffer)
                assertTrue(read > 0)
                echoed.append(String(buffer, 0, read, Charsets.ISO_8859_1))
            }
            assertEquals(payload, echoed.toString())
            socket.shutdownOutput()
        }
        wsServer.close()
    }

    @Test fun missingGateCookieReturns404() {
        val proxy = startProxy("127.0.0.2")
        val response = readFullResponse(proxy, "GET /hello HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 404"))
    }

    @Test fun wrongGateValueReturns404() {
        val proxy = startProxy("127.0.0.2")
        val response = readFullResponse(proxy, "GET /hello HTTP/1.1\r\nHost: x\r\nCookie: dshbox_gate=wrong\r\n\r\n")
        assertTrue(response.startsWith("HTTP/1.1 404"))
    }

    @Test fun occupiedPortFailsExplicitlyWithoutSilentChange() {
        val blocker = ServerSocket()
        blocker.bind(InetSocketAddress("127.0.0.7", 0))
        val occupiedPort = blocker.localPort
        try {
            val proxy = LoopbackProxy(
                loopbackIp = "127.0.0.7",
                requestedPort = occupiedPort,
                upstreamOrigin = "http://127.0.0.1:${upstream.address.port}",
                jar = jar,
                gate = gate,
            )
            val exception = assertThrows(ProxyStartException::class.java) { proxy.start() }
            assertTrue(exception.message!!.contains("127.0.0.7:$occupiedPort"))
        } finally {
            blocker.close()
        }
    }

    @Test fun selfSignedHttpsUpstreamWorksWithSessionTrust() {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            LoopbackProxyIntegrationTest::class.java.getResourceAsStream(
                "/com/dshbox/remote/proxy/mock-upstream.p12",
            )!!.use { load(it, "dshbox-mock".toCharArray()) }
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "dshbox-mock".toCharArray())
        val serverSslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }
        val httpsUpstream = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpsUpstream.httpsConfigurator = HttpsConfigurator(serverSslContext)
        httpsUpstream.executor = Executors.newCachedThreadPool()
        httpsUpstream.createContext("/secure") { exchange ->
            respond(exchange, 200, "secure hello")
        }
        httpsUpstream.start()

        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            LoopbackProxyIntegrationTest::class.java.getResourceAsStream(
                "/com/dshbox/remote/proxy/mock-upstream.crt",
            )!!,
        ) as java.security.cert.X509Certificate
        val sessionTrust = TlsTrust.custom(listOf(certificate))

        val proxy = LoopbackProxy(
            loopbackIp = "127.0.0.8",
            requestedPort = 0,
            upstreamOrigin = "https://127.0.0.1:${httpsUpstream.address.port}",
            jar = jar,
            gate = gate,
            tls = sessionTrust,
        )
        proxy.start()
        proxies.add(proxy)

        val response = readFullResponse(proxy, "GET /secure HTTP/1.1\r\nHost: x\r\n${gateCookie()}\r\n\r\n")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue(response.contains("secure hello"))
        httpsUpstream.stop(0)
    }

    @Test fun systemTrustRejectsSelfSignedUpstream() {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            LoopbackProxyIntegrationTest::class.java.getResourceAsStream(
                "/com/dshbox/remote/proxy/mock-upstream.p12",
            )!!.use { load(it, "dshbox-mock".toCharArray()) }
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, "dshbox-mock".toCharArray())
        val serverSslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, null, null)
        }
        val httpsUpstream = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpsUpstream.httpsConfigurator = HttpsConfigurator(serverSslContext)
        httpsUpstream.executor = Executors.newCachedThreadPool()
        httpsUpstream.createContext("/secure") { respond(it, 200, "should not pass") }
        httpsUpstream.start()

        val proxy = LoopbackProxy(
            loopbackIp = "127.0.0.9",
            requestedPort = 0,
            upstreamOrigin = "https://127.0.0.1:${httpsUpstream.address.port}",
            jar = jar,
            gate = gate,
            tls = TlsTrust.systemDefault(),
        )
        proxy.start()
        proxies.add(proxy)

        // 系统信任不认自签证书：连接层失败（连接被关闭），绝不返回 200
        val response = readFullResponse(proxy, "GET /secure HTTP/1.1\r\nHost: x\r\n${gateCookie()}\r\n\r\n")
        assertFalse(response.contains("should not pass"))
        httpsUpstream.stop(0)
    }

    @Test fun stopClosesServerAndReleasesPort() {
        val proxy = startProxy("127.0.0.2")
        val port = proxy.boundPort
        proxy.stop()
        // 端口释放后可重新绑定
        val rebound = ServerSocket()
        rebound.bind(InetSocketAddress("127.0.0.2", port))
        rebound.close()
        // stop 幂等
        proxy.stop()
    }

    // ---- 下载分支（主框架响应判定为下载 → 落盘 + 结果页）----

    @Rule
    @JvmField
    val temp = org.junit.rules.TemporaryFolder()

    private fun navigationRequest(proxy: LoopbackProxy, target: String) =
        readFullResponse(
            proxy,
            "GET $target HTTP/1.1\r\nHost: x\r\n${gateCookie()}\r\n" +
                "Accept: text/html,application/xhtml+xml,*/*;q=0.8\r\nConnection: close\r\n\r\n",
        )

    private fun filesOf(dir: File): List<File> =
        dir.listFiles().orEmpty().sortedBy { it.name }

    @Test
    fun attachmentResponseIsSavedAndAnsweredWithResultPage() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val response = navigationRequest(proxy, "/report")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", headerValues(response, "Content-Type").firstOrNull()?.startsWith("text/html") == true)
        assertTrue("response=[$response]", response.contains("SAVED:${dir.name}/report.pdf"))
        assertFalse("response=[$response]", response.contains("PDF-BYTES"))
        // 落盘字节与上游逐位一致，且不留 .part 半包
        val files = filesOf(dir)
        assertEquals(listOf("report.pdf"), files.map { it.name })
        assertEquals("PDF-BYTES-0123456789", files.single().readText())
    }

    @Test
    fun chunkedAttachmentIsDrainedAndRfc5987NameDecoded() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val response = navigationRequest(proxy, "/report-chunked")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        // 结果页正文是 UTF-8，而 readFullResponse 按 ISO-8859-1 逐字节读入：
        // 断言前按字节回转一次，避免把解码正确性误判成文件名错误。
        val text = response.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
        assertTrue("text=[$text]", text.contains("SAVED:${dir.name}/报告.pdf"))
        val files = filesOf(dir)
        assertEquals(listOf("报告.pdf"), files.map { it.name })
        assertEquals("CHUNKED-PDF-BYTES", files.single().readText())
    }

    @Test
    fun duplicateAttachmentNamesAreIndexedNotOverwritten() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        navigationRequest(proxy, "/report")
        val second = navigationRequest(proxy, "/report")
        assertTrue("response=[$second]", second.contains("SAVED:${dir.name}/report (1).pdf"))
        assertEquals(listOf("report (1).pdf", "report.pdf"), filesOf(dir).map { it.name })
    }

    @Test
    fun subresourceBinaryRequestsAreRelayedNotDownloaded() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        // 无 Accept text/html（子资源签名）：octet-stream 照旧透传给渲染器，不落盘
        val response = readFullResponse(
            proxy,
            "GET /binary HTTP/1.1\r\nHost: x\r\n${gateCookie()}\r\nConnection: close\r\n\r\n",
        )
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", response.contains("RAW-BINARY"))
        assertTrue(filesOf(dir).isEmpty())
    }

    @Test
    fun htmlMainFrameResponseIsRelayedEvenFromNavigation() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val response = navigationRequest(proxy, "/page")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", response.contains("page"))
        assertTrue(filesOf(dir).isEmpty())
    }

    @Test
    fun unavailableDownloadDirAnswersWithFailurePage() {
        // 占位文件挡住 mkdirs：目录建不出来 → 失败页说明原因（结果页恒 200，
        // 成败由页面文案区分），且无半包可留
        val blocked = temp.newFolder().resolve("download").apply { createNewFile() }
        val proxy = startProxy("127.0.0.2", downloadDir = blocked)
        val response = navigationRequest(proxy, "/report")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", response.contains("FAILED:DIR-UNAVAILABLE"))
        assertFalse(blocked.isDirectory)
    }

    @Test
    fun withoutDownloadCapabilityResponsesAreRelayedAsBefore() {
        // downloadDir 缺省 null（宿主未提供下载能力）：下载判定不再分流，行为与
        // 改造前完全一致（attachment 响应原样透传给渲染器）
        val proxy = startProxy("127.0.0.2")
        val response = navigationRequest(proxy, "/report")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", response.contains("PDF-BYTES-0123456789"))
        assertTrue("response=[$response]", headerValues(response, "Content-Disposition").isNotEmpty())
    }

    @Test
    fun gzipAttachmentWithContentLengthIsSavedDecompressed() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val response = navigationRequest(proxy, "/report-gzip")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", response.contains("SAVED:${dir.name}/report.pdf"))
        // 落盘内容是解压后的原文（Content-Length 是压缩字节数，不得截断解压流）
        val files = filesOf(dir)
        assertEquals(listOf("report.pdf"), files.map { it.name })
        assertEquals("GZIP-PDF-BYTES-0123456789", files.single().readText())
    }

    @Test
    fun gzipChunkedAttachmentIsSavedDecompressed() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val response = navigationRequest(proxy, "/report-gzip-chunked")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", response.contains("SAVED:${dir.name}/report.pdf"))
        val files = filesOf(dir)
        assertEquals(listOf("report.pdf"), files.map { it.name })
        assertEquals("GZIP-CHUNKED-PDF", files.single().readText())
    }

    @Test
    fun unsupportedEncodingDownloadFailsWithFailurePageAndLeavesNoFile() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val response = navigationRequest(proxy, "/report-br")
        // br 编码显式失败：失败页说明原因（结果页恒 200），无落盘文件、无半包、
        // 无占位残留
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", response.contains("FAILED:"))
        assertTrue(filesOf(dir).isEmpty())
    }

    @Test
    fun concurrentSameNameDownloadsKeepBothFilesIntact() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val responses = pool.invokeAll(
                listOf(
                    java.util.concurrent.Callable { navigationRequest(proxy, "/report-race") },
                    java.util.concurrent.Callable { navigationRequest(proxy, "/report-race") },
                ),
            ).map { it.get(30, TimeUnit.SECONDS) }
            // 两个请求都成功，且拿到不同的候选名（createNewFile 原子占位）
            val savedNames = responses.map { response ->
                assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
                assertTrue("response=[$response]", response.contains("SAVED:${dir.name}/"))
                response.toByteArray(Charsets.ISO_8859_1).toString(Charsets.UTF_8)
                    .substringAfter("SAVED:${dir.name}/").substringBefore("<").trim()
            }
            assertEquals(setOf("race.bin", "race (1).bin"), savedNames.toSet())
            // 两个文件都在、内容各自完整（不覆盖、不交错）
            val contents = savedNames.map { java.io.File(dir, it).readText() }.sorted()
            assertEquals(
                listOf(RACE_BODY_FIRST, RACE_BODY_SECOND).map { String(it, Charsets.UTF_8) }.sorted(),
                contents,
            )
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun zeroByteAttachmentIsSavedAndNotReclaimed() {
        val dir = temp.newFolder()
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val response = navigationRequest(proxy, "/report-zero")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue("response=[$response]", response.contains("SAVED:${dir.name}/empty.bin"))
        // 零字节文件是合法完成品：落盘后不被失败回收路径误删，也无 .part 残留
        val files = filesOf(dir)
        assertEquals(listOf("empty.bin"), files.map { it.name })
        assertEquals(0L, files.single().length())
    }

    @Test
    fun stalePartFilesAreCleanedBeforeSaving() {
        val dir = temp.newFolder()
        val stale = java.io.File(dir, "dl-stale.part").apply {
            writeText("stale")
            setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1000)
        }
        val fresh = java.io.File(dir, "dl-fresh.part").apply { writeText("fresh") }
        val proxy = startProxy("127.0.0.2", downloadDir = dir)
        val response = navigationRequest(proxy, "/report")
        assertTrue("response=[$response]", response.startsWith("HTTP/1.1 200"))
        assertTrue(java.io.File(dir, "report.pdf").exists())
        // 超 24 小时的陈旧 .part 一次性清理；新鲜的与本次下载的产物不受影响
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
    }

    private fun headerValues(response: String, name: String): List<String> =
        response.split("\r\n")
            .drop(1)
            .takeWhile { it.isNotEmpty() }
            .filter { it.startsWith("$name:", ignoreCase = true) }
            .map { it.substringAfter(':').trim() }

    private fun readHeadBytes(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            val bytes = out.toByteArray()
            if (bytes.size >= 4 &&
                bytes[bytes.size - 4] == '\r'.code.toByte() && bytes[bytes.size - 3] == '\n'.code.toByte() &&
                bytes[bytes.size - 2] == '\r'.code.toByte() && bytes[bytes.size - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return out.toByteArray()
    }
}
