package com.dshbox.remote.proxy

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网关/邀请/bridge/pocket 四类插件的注入器链路集成测试：JVM 内嵌 mock 上游按各插件的认证行为
 * 应答，验证「首跳携带凭据 → 兑换 → 会话 cookie 代持与自动注入 → 非
 * HttpOnly 双写对渲染器可见、HttpOnly 只代持」的全链路。
 */
class P0PluginInjectorsIntegrationTest {

    private lateinit var upstream: HttpServer

    /** 上游观测：请求目标与 Cookie 头（按到达顺序记账）。 */
    private val seenRequests = ConcurrentHashMap<Int, Pair<String, String?>>()
    private val requestOrder = AtomicInteger(0)

    private val proxies = mutableListOf<LoopbackProxy>()

    @Before
    fun setUp() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.executor = Executors.newCachedThreadPool()
        upstream.createContext("/") { exchange ->
            val index = requestOrder.getAndIncrement()
            val cookie = exchange.requestHeaders["Cookie"]?.joinToString("; ")
            seenRequests[index] = exchange.requestURI.toString() to cookie
            when {
                // dsh-bridge 认证页：?auth= 正确即种会话 cookie（注入器把首跳改写到此）
                exchange.requestURI.rawQuery == "auth=$BRIDGE_TOKEN" -> {
                    exchange.responseHeaders.add("Set-Cookie", "dsh_bridge_auth=b-sess; Path=/")
                    respond(exchange, 200, "bridge page")
                }
                exchange.requestURI.path.startsWith("/_dsh_reverse_proxy/login") -> {
                    if (exchange.requestURI.rawQuery == "invite=$INVITE") {
                        exchange.responseHeaders.add("Set-Cookie", "dsid=remote-1; Path=/; HttpOnly; SameSite=Strict")
                    }
                    respond(exchange, 200, "full remote page")
                }
                exchange.requestURI.path.startsWith("/pair") -> {
                    exchange.responseHeaders.add("Set-Cookie", "DeviceSession=gw-1; Path=/; HttpOnly")
                    exchange.responseHeaders.add("Set-Cookie", "mobile_ui=dark; Path=/")
                    respond(exchange, 200, "gateway pairing page")
                }
                exchange.requestURI.path == "/pin-login" -> {
                    val body = exchange.requestBody.readBytes().decodeToString()
                    if (body == "pin=12345678") {
                        exchange.responseHeaders.add("Set-Cookie", "pocket_session=pk-1; Path=/; HttpOnly")
                        exchange.responseHeaders.add("Set-Cookie", "pocket_theme=light; Path=/")
                        respond(exchange, 200, "pin ok")
                    } else {
                        respond(exchange, 403, "bad pin")
                    }
                }
                else -> respond(exchange, 200, "page: ${exchange.requestURI.path}")
            }
        }
        upstream.start()
    }

    @After
    fun tearDown() {
        proxies.forEach { it.stop() }
        upstream.stop(0)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.sendResponseHeaders(status, body.length.toLong())
        exchange.responseBody.write(body.toByteArray())
        exchange.close()
    }

    private fun startProxy(injector: AuthInjector, jar: CookieJar = CookieJar()): LoopbackProxy {
        val proxy = LoopbackProxy(
            loopbackIp = "127.0.0.9",
            requestedPort = 0,
            upstreamOrigin = "http://127.0.0.1:${upstream.address.port}",
            jar = jar,
            gate = GateCheck(GATE_TOKEN),
            injector = injector,
        )
        proxy.start()
        proxies.add(proxy)
        return proxy
    }

    private fun readFullResponse(proxy: LoopbackProxy, request: String): String {
        Socket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(proxy.loopbackIp, proxy.boundPort), 5_000)
            socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val out = ByteArrayOutputStream()
            val input = socket.getInputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val read = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.ISO_8859_1)
        }
    }

    private fun gateCookie() = "Cookie: dshbox_gate=$GATE_TOKEN"

    private fun headerValues(response: String, name: String): List<String> =
        response.split("\r\n")
            .filter { it.startsWith("$name:", ignoreCase = true) }
            .map { it.substringAfter(':').trim() }

    private fun seen(index: Int) = seenRequests.getValue(index)

    // ---- dsh-bridge：?auth 兑换 → 会话 cookie 代持 + 双写 ----

    @Test fun bridgeFirstHopCarriesAuthThenCookieTakesOver() {
        val jar = CookieJar()
        val proxy = startProxy(DshBridgeAuthInjector(BRIDGE_TOKEN), jar)
        val origin = "http://127.0.0.9:${proxy.boundPort}"

        val first = readFullResponse(proxy, "GET / HTTP/1.1\r\nHost: h\r\n${gateCookie()}\r\nConnection: close\r\n\r\n")
        // 首跳带 auth 到上游
        assertEquals("/?auth=$BRIDGE_TOKEN", seen(0).first)
        // 渲染器侧双写可见（非 HttpOnly、Domain/Secure 已剥）
        val dualWrites = headerValues(first, "Set-Cookie")
        assertTrue(dualWrites.any { it.startsWith("dsh_bridge_auth=") && !it.contains("Domain") })

        // 兑换完成后停止追加 auth，会话 cookie 由罐自动注入
        val second = readFullResponse(proxy, "GET /app.js HTTP/1.1\r\nHost: h\r\n${gateCookie()}\r\nConnection: close\r\n\r\n")
        assertEquals("/app.js", seen(1).first)
        // 上游只见到罐注入的会话 cookie（凭据参数不再出现，门禁已剥离）
        assertEquals("dsh_bridge_auth=b-sess", seen(1).second)
    }

    // ---- dsh-full-remote：invite URL 首跳改写 → HttpOnly 代持 ----

    @Test fun fullRemoteFirstHopUsesArchivedInviteUrl() {
        val jar = CookieJar()
        val inviteUrl = "http://127.0.0.1:${upstream.address.port}/_dsh_reverse_proxy/login?invite=$INVITE"
        val proxy = startProxy(DshFullRemoteInviteInjector(inviteUrl), jar)

        val first = readFullResponse(proxy, "GET / HTTP/1.1\r\nHost: h\r\n${gateCookie()}\r\nConnection: close\r\n\r\n")
        // 首个文档请求改用存档邀请 URL
        assertEquals("/_dsh_reverse_proxy/login?invite=$INVITE", seen(0).first)
        // HttpOnly SameSite=Strict 只代持：不透传渲染器
        assertFalse(headerValues(first, "Set-Cookie").any { it.startsWith("dsid=") })

        val second = readFullResponse(proxy, "GET /dashboard HTTP/1.1\r\nHost: h\r\n${gateCookie()}\r\nConnection: close\r\n\r\n")
        assertEquals("/dashboard", seen(1).first)
        assertTrue(seen(1).second.orEmpty().contains("dsid=remote-1"))
    }

    // ---- dsh-remote-web-gateway：/pair 首跳 + 双写可见性 ----

    @Test fun gatewayFirstHopHitsPairPathAndDualWriteKeepsSecretOutOfUpstream() {
        val jar = CookieJar()
        val proxy = startProxy(DshRemoteWebGatewayPairingInjector("http://x.example/pair#s3cret"), jar)

        val first = readFullResponse(proxy, "GET / HTTP/1.1\r\nHost: h\r\n${gateCookie()}\r\nConnection: close\r\n\r\n")
        // 首跳改写为配对页路径；fragment 不入上游
        assertEquals("/pair", seen(0).first)
        // Device Session（HttpOnly）只代持；非敏感 cookie 双写渲染器
        val dualWrites = headerValues(first, "Set-Cookie")
        assertFalse(dualWrites.any { it.startsWith("DeviceSession=") })
        assertTrue(dualWrites.any { it.startsWith("mobile_ui=dark") && !it.contains("Domain") })

        val second = readFullResponse(proxy, "GET /assets/app.js HTTP/1.1\r\nHost: h\r\n${gateCookie()}\r\nConnection: close\r\n\r\n")
        assertEquals("/assets/app.js", seen(1).first)
        val cookieHeader = seen(1).second.orEmpty()
        assertTrue(cookieHeader.contains("DeviceSession=gw-1"))
        assertTrue(cookieHeader.contains("mobile_ui=dark"))
    }

    // ---- dsh-pocket：PIN 表单兑换 → 罐代持 + 双写 ----

    @Test fun pocketPinExchangeIsHeldByJarAndDualWritten() {
        val jar = CookieJar()
        val proxy = startProxy(PassThroughInjector, jar)
        val body = "pin=12345678"

        val login = readFullResponse(
            proxy,
            "POST /pin-login HTTP/1.1\r\nHost: h\r\n${gateCookie()}\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body",
        )
        assertEquals("/pin-login", seen(0).first)
        // 无凭据注入：首跳不带任何注入参数（PIN 表单由页面驱动）
        val dualWrites = headerValues(login, "Set-Cookie")
        assertFalse(dualWrites.any { it.startsWith("pocket_session=") })
        assertTrue(dualWrites.any { it.startsWith("pocket_theme=light") })

        val home = readFullResponse(proxy, "GET /home HTTP/1.1\r\nHost: h\r\n${gateCookie()}\r\nConnection: close\r\n\r\n")
        assertEquals("/home", seen(1).first)
        val cookieHeader = seen(1).second.orEmpty()
        assertTrue(cookieHeader.contains("pocket_session=pk-1"))
        assertTrue(cookieHeader.contains("pocket_theme=light"))
    }

    private companion object {
        const val GATE_TOKEN = "0123456789abcdef0123456789abcdef"
        const val BRIDGE_TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef01"
        const val INVITE = "Ab12Cd34Ef56"
    }
}
