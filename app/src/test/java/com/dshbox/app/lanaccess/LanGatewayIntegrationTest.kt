package com.dshbox.app.lanaccess

import com.dshbox.remote.proxy.HttpWire
import com.dshbox.remote.proxy.WsTunnel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 局域网网关集成测试（真跑 socket）：JVM 内嵌一个按 DSH 0.2.1-rc.5 行为
 * 造型的模拟上游（取证口径见 [LanUpstream]）与模拟浏览器客户端，验证：
 *
 * - **token 注入防循环**：`GET /` 首跳注入一次 token → 模拟 DSH 回
 *   303 + `Set-Cookie: dsh-auth-*` + `Location: ./` → 浏览器跟随 → 客户端
 *   已带会话 cookie，网关不再注入 → 模拟 DSH 200。全程 ≤3 跳，Location
 *   永不含 `token=`；首跳确实注入（兑换真发生），带会话 cookie 的后续
 *   请求在上游侧收不到 token（同一客户端会话只兑换一次）。
 * - **失效会话自愈**：带 stale `dsh-auth-*` cookie + 有效 `?k=` → 302 对
 *   该 cookie 名回 `Max-Age=0`；后续首跳重新注入 token 并 200。
 * - **防循环保险**：永不发 Set-Cookie 的模拟 DSH（取证的 cookie 前缀失配
 *   的替代物）下，兑换标记 cookie 让网关最多注入一次即停（401 收场）。
 * - **鉴权矩阵**：无门禁 cookie / 错 cookie / 空 `?k=`（含空值）→ 401；
 *   有效 `?k=` → 302 + `Referrer-Policy: no-referrer`；带门禁 cookie →
 *   转发且门禁值与 `k` 参数不外泄上游。
 * - **上游 socket 释放（含异常路径）**：连续多个请求完成后活跃上游数
 *   归零且集合为空；客户端中途断开、上游中途 RST、响应头超 128KB 三条
 *   异常路径同样归零；计数回写回调在途/归零/停机时序正确（不出现负数）；
 *   stop() 幂等且回收 WS 活跃连接。
 * - **设备计数（按来源 IP 聚合）**：同一来源 IP 的两条连接 → 设备
 *   数 1、连接数 2；关闭一条后仍 1，全部关闭与 stop() 后设备数归 0；不同
 *   来源 IP 的真实多地址用例按「该来源地址本机可绑定」门控（不可绑定即
 *   跳过，不假绿），聚合口径本身由 [LanDeviceTrackerTest] 覆盖。
 * - **密钥轮换（「全部断开并重置密钥」语义）**：以新密钥重建门禁后
 *   旧 cookie 对新密钥必然 401、旧 `?k=` 链接 401、新密钥兑换回到 200。
 * - WS 升级透明透传（101 后双向字节泵、泵结束套接字收口）、chunked 请求
 *   体解块转发 + `X-Forwarded-*`/`TE` 转发剥离、absolute-form 请求行归一、
 *   `/index.html` 路径、网关端到端 413。
 */
class LanGatewayIntegrationTest {

    private val accessKey = "0123456789abcdef0123456789abcdef"
    private val launchToken = "launch-token-0123456789"

    /** 模拟 DSH：按 0.2.1-rc.5 `authorizeIndex` 取证行为响应。 */
    private lateinit var mockDsh: MockDsh

    private val gateways = mutableListOf<LanGateway>()
    private val portCursor = AtomicInteger(0)
    private var dshIssuesSession: Boolean = true

    @Before
    fun setUp() {
        dshIssuesSession = true
        mockDsh = MockDsh(launchToken, issuesSession = { dshIssuesSession })
        mockDsh.start()
    }

    @After
    fun tearDown() {
        gateways.forEach { it.stop() }
        mockDsh.close()
    }

    private fun startGateway(
        onDevicesChanged: (devices: Int, connections: Int) -> Unit = { _, _ -> },
        upstreamProvider: (() -> Pair<LanUpstream.Upstream, String?>)? = null,
        accessKeyValue: String = accessKey,
    ): LanGateway {
        val gateway = LanGateway(
            bindAddress = InetAddress.getByName("127.0.0.10"),
            port = 39877 + (portCursor.incrementAndGet() % 200),
            gate = LanAccessGate(accessKeyValue) { 1_000L },
            upstreamProvider = upstreamProvider ?: {
                LanUpstream.upstreamOf("http://127.0.0.1:${mockDsh.port}") to launchToken
            },
            onDevicesChanged = onDevicesChanged,
        )
        gateway.start()
        gateways.add(gateway)
        return gateway
    }

    /** 网关绑定宿主（网关与它一起构成局域网侧的「本机代表」）。 */
    private val gatewayHost = "127.0.0.10"

    // ---- 核心 1：首次访问防循环（含受限 3 跳内到达 200） ----

    @Test
    fun firstVisitReaches200WithinThreeHopsWithoutTokenInLocation() {
        val gateway = startGateway()
        val browser = FakeBrowser(gatewayHost, gateway.port)

        // 兑换路径入口：302（种门禁 cookie）→ / 注入 token（模拟 DSH 303 +
        // Set-Cookie）→ / 已带会话 cookie → 200。
        val visit = browser.visit("/?k=$accessKey")
        assertEquals(200, visit.status)
        assertTrue("hops=${visit.hops}", visit.hops <= 3)
        assertFalse(
            "Location leak: ${visit.locations}",
            visit.locations.any { it.contains("token=") },
        )
        // 首跳 `/`（无会话 cookie）在上游侧确实注入了 token
        val rootRequests = mockDsh.requests.filter { it.path == "/" }
        assertTrue("no root request seen: ${mockDsh.requests}", rootRequests.isNotEmpty())
        assertTrue(
            "first root request should carry token: ${rootRequests.firstOrNull()?.target}",
            rootRequests.first().target.contains("token=$launchToken"),
        )
        // 兑换后的携带会话 cookie 的请求不再注入 token（单次兑换语义）
        val postCookie = rootRequests.firstOrNull {
            !it.target.contains("token=") && it.hasDshAuthCookie
        }
        assertNotNull("post-cookie root request missing", postCookie)
        // 会话 cookie 由模拟 DSH 签发、客户端确实持有
        assertNotNull(browser.cookies[MockDsh.SESSION_COOKIE_NAME])
    }

    @Test
    fun sessionCookieNamedRequestsDoNotCarryInjectedToken() {
        val gateway = startGateway()
        val browser = FakeBrowser(gatewayHost, gateway.port)
        browser.cookies[MockDsh.SESSION_COOKIE_NAME] = MockDsh.SESSION_COOKIE_VALUE
        browser.cookies[LanAccessSecrets.COOKIE_NAME] =
            LanAccessSecrets.cookieValueFor(accessKey)
        val response = browser.visit("/")
        assertEquals(200, response.status)
        val seen = mockDsh.requests.filter { it.path == "/" }
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { !it.target.contains("token=") })
    }

    // ---- 核心 2：鉴权矩阵（无 cookie / 错 cookie / 空 ?k） ----

    @Test
    fun authMatrixWithoutCookieWrongCookieAndEmptyKey() {
        val gateway = startGateway()
        assertEquals(401, rawGet(gateway, "/").status)
        assertEquals(
            401,
            rawGet(
                gateway,
                "/",
                listOf("Cookie: ${LanAccessSecrets.COOKIE_NAME}=wrong"),
            ).status,
        )
        assertEquals(401, rawGet(gateway, "/?").status)
        assertEquals(401, rawGet(gateway, "/?k=").status)
    }

    @Test
    fun exchangeRedirectCarriesReferrerPolicyAndCookie() {
        val gateway = startGateway()
        val response = rawGet(gateway, "/?k=$accessKey")
        assertEquals(302, response.status)
        assertEquals("no-referrer", response.header("Referrer-Policy"))
        assertTrue(
            response.header("Set-Cookie").orEmpty()
                .startsWith("${LanAccessSecrets.COOKIE_NAME}="),
        )
    }

    @Test
    fun gateCookieIsStrippedButUpstreamSessionsForwarded() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        val request =
            ("GET /hello HTTP/1.1\r\nHost: x\r\n" +
                "Cookie: ${MockDsh.SESSION_COOKIE_NAME}=${MockDsh.SESSION_COOKIE_VALUE}; " +
                "${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\n" +
                "Connection: close\r\n\r\n")
        val response = parseResponse(rawSendByHost(gatewayHost, gateway.port, request))
        assertEquals(200, response.status)
        val seen = mockDsh.requests.single { it.path == "/hello" }
        // 上游 Cookie 只剩会话段：门禁 cookie 不外泄、会话 cookie 保留
        assertTrue(seen.cookieHeader.contains(MockDsh.SESSION_COOKIE_NAME))
        assertFalse(seen.cookieHeader.contains(LanAccessSecrets.COOKIE_NAME))
        // 已带会话 cookie：不再注入 token
        assertTrue(!seen.target.contains("token="))
    }

    // ---- 自愈：stale 会话 cookie + 有效 ?k → 302 清旧 + 重新注入 token ----

    @Test
    fun exchangeWithStaleSessionCookieClearsItAndReinjectionRecovers() {
        val gateway = startGateway()
        val browser = FakeBrowser(gatewayHost, gateway.port)
        // 预置一枚已失效的 DSH 会话 cookie（同前缀派生命名）与门禁 cookie
        browser.cookies[MockDsh.SESSION_COOKIE_NAME] = "stale-session-value"
        val visit = browser.visit("/?k=$accessKey")

        assertEquals(200, visit.status)
        // 兑换 302 对 stale dsh-auth-* cookie 名回 Max-Age=0（罐里的 stale
        // 值被清除；随后上游 303 重新种下新的会话 cookie）
        assertTrue(
            "stale value should be cleared: ${browser.cookies[MockDsh.SESSION_COOKIE_NAME]}",
            browser.cookies[MockDsh.SESSION_COOKIE_NAME].let { it == null || it != "stale-session-value" },
        )
        // 门禁 cookie 与防循环标记（自愈首跳不受影响，回路复现）
        assertTrue(LanAccessSecrets.COOKIE_NAME in browser.cookies)
        // 注入判定不变：首跳仍注入 token → 303 → 重访 200
        assertTrue(visit.hops <= 4)
        val roots = mockDsh.requests.filter { it.path == "/" }
        assertTrue(
            "first root request should be token-injected: ${roots.firstOrNull()?.target}",
            roots.first().target.contains("token=$launchToken"),
        )
        assertNotNull(
            "cleared-cookie root request should get session again",
            roots.lastOrNull { it.hasDshAuthCookie },
        )
    }

    // ---- 防循环保险：DSH 永不 Set-Cookie 时最多注入一次即停 ----

    @Test
    fun exchangeMarkCookieStopsInjectionAfterOneHopWhenUpstreamIssuesNoCookie() {
        // 落景：上游 DSH 的真实 cookie 前缀与取证不符（这里以「永不发
        // Set-Cookie」替代）——若仍按会话 cookie 单条件判定，浏览器将在
        // 「/ 注入 → 303 → / 再注入」间无限循环；兑换标记迫使一跳即停。
        dshIssuesSession = false
        val gateway = startGateway()
        val browser = FakeBrowser(gatewayHost, gateway.port)
        val visit = browser.visit("/?k=$accessKey")

        // 302 兑换后标记 cookie 种下；后续 / 上游无 cookie → 401（可诊断）
        assertEquals(401, visit.status)
        assertTrue("mark cookie should be planted", "dshbox_lan_exchanged" in browser.cookies)
        assertEquals(
            "upstream must see token exactly once",
            1,
            mockDsh.requests.count { it.target.contains("token=") },
        )
        // 客户端在收到 401 前最多两跳（302 兑换 + 一次注入跳）
        assertFalse(visit.locations.isEmpty())
        // 停住后不再无限注入：标记使后续请求一律不注入
        val postMark = browser.visit("/")
        assertEquals(401, postMark.status)
        assertEquals(
            "still exactly one injection after 401",
            1,
            mockDsh.requests.count { it.target.contains("token=") },
        )
    }

    // ---- 密钥不外泄：转发的目标形不携带 ?k ----

    @Test
    fun exchangedKeyNeverReachesUpstreamUrl() {
        val gateway = startGateway()
        val raw = rawGet(gateway, "/hist?x=1&k=$accessKey")
        assertEquals(302, raw.status)
        // 随后的兑换回合（浏览器镜像路径）：全程无 ?k 流向模拟 DSH
        val browser = FakeBrowser(gatewayHost, gateway.port)
        val visit = browser.visit("/hist?k=$accessKey")
        assertEquals(200, visit.status)
        assertTrue(
            "upstream targets must not carry access key: ${mockDsh.requests.map { it.target }}",
            mockDsh.requests.none { it.target.contains("k=$accessKey") },
        )
        // 兑换回跳标记（dshreload）与 token 都不进客户端 URL；DSH 侧同样
        // 见不到 dshreload
        assertTrue(visit.locations.none { it.contains("token=") || it.contains("k=") })
        assertTrue(
            "dshreload must not reach upstream: ${mockDsh.requests.map { it.target }}",
            mockDsh.requests.none { it.target.contains("dshreload") },
        )
    }

    // ---- 核心 3：absolute-form 请求行归一 ----

    @Test
    fun absoluteFormRequestLineIsNormalizedAndTokenInjected() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        val response = rawSend(
            gateway,
            "GET http://${gateway.boundHostPort}/hello HTTP/1.1\r\n" +
                "Host: ${gateway.boundHostPort}\r\n" +
                "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\n" +
                "Connection: close\r\n\r\n",
        )
        assertEquals(200, response.status)
        val seen = mockDsh.requests.single { it.path == "/hello" }
        assertTrue("target=${seen.target}", seen.target.startsWith("/hello"))
        assertTrue(
            "token should be injected: ${seen.target}",
            seen.target.contains("token=$launchToken"),
        )
    }

    // ---- 核心 4：上游 socket 释放 ----

    @Test
    fun upstreamSocketsAreReleasedAfterRequests() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        repeat(5) {
            val response = rawGet(
                gateway,
                "/alive",
                listOf("Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie"),
            )
            assertEquals(200, response.status)
            assertEquals("hello-from-dsh", response.body.trim())
        }
        awaitUpstreamRelease(gateway)
        assertEquals(0, gateway.observedActiveUpstreamCount)
        assertTrue(gateway.trackedUpstreamSockets.isEmpty())
    }

    /**
     * 异常路径 1：客户端在响应中途断开——模拟 DSH 发一半响应体后客户端
     * 关连接，网关的回写中继抛 IOException，最后仍要收口上游 socket。
     */
    @Test
    fun clientDisconnectMidResponseReleasesUpstreamSocket() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        connectAndSend(
            gatewayHost,
            gateway.port,
            "GET /bigstream HTTP/1.1\r\nHost: x\r\n" +
                "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\nConnection: close\r\n\r\n",
        ).close()
        awaitUpstreamRelease(gateway)
        assertEquals(0, gateway.observedActiveUpstreamCount)
        assertTrue(gateway.trackedUpstreamSockets.isEmpty())
    }

    /**
     * 异常路径 2：上游中途 RST——模拟 DSH 对 `/abrupt` 收完即暴力关 socket
     * （不回响应头），网关读上游头/流抛错，同样收口。
     */
    @Test
    fun upstreamRstMidResponseReleasesUpstreamSocket() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        rawSendByHost(
            gatewayHost,
            gateway.port,
            "GET /abrupt HTTP/1.1\r\nHost: x\r\n" +
                "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\nConnection: close\r\n\r\n",
        )
        awaitUpstreamRelease(gateway)
        assertEquals(0, gateway.observedActiveUpstreamCount)
        assertTrue(gateway.trackedUpstreamSockets.isEmpty())
    }

    /**
     * 异常路径 3：响应头超过 128KB（HttpWire.readHead 的上限）——上游回
     * 一大块头字节冲破上限，readHead 抛 IOException，网关仍要收口。
     */
    @Test
    fun oversizedResponseHeadReleasesUpstreamSocket() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        rawSendByHost(
            gatewayHost,
            gateway.port,
            "GET /hugehead HTTP/1.1\r\nHost: x\r\n" +
                "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\nConnection: close\r\n\r\n",
        )
        awaitUpstreamRelease(gateway)
        assertEquals(0, gateway.observedActiveUpstreamCount)
        assertTrue(gateway.trackedUpstreamSockets.isEmpty())
    }

    @Test
    fun connectionCountTracksOpenAndClose() {
        /// 设备/连接计数回写序列（设备数, 连接数）。
        val observed = mutableListOf<Pair<Int, Int>>()
        val gateway = startGateway(
            onDevicesChanged = { devices, connections ->
                synchronized(observed) { observed.add(devices to connections) }
            },
        )
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        assertEquals(
            200,
            rawGet(gateway, "/alive", listOf("Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie")).status,
        )
        awaitUpstreamRelease(gateway)
        synchronized(observed) {
            // 回写真实发生：在途时非零、终值归零
            assertTrue("observations=$observed", observed.any { it.first > 0 && it.second > 0 })
            assertEquals(0 to 0, observed.last())
        }
        // stop 幂等且把计数收口到 0
        gateway.stop()
        gateway.stop()
        synchronized(observed) {
            assertEquals(0 to 0, observed.last())
            // 计数纪律：任何一次回写都不出现负数（stop 与在途 handler 收尾
            // 的竞态由非负夹紧兜住）
            assertTrue(
                "negative observation in $observed",
                observed.all { it.first >= 0 && it.second >= 0 },
            )
        }
    }

    /** stop 与 WS 活跃连接的回收：泵被套接字关闭唤醒收尾，计数归零不出现负数。 */
    @Test
    fun stopWhileWebSocketActiveRecoversAndCountNeverGoesNegative() {
        val observed = mutableListOf<Pair<Int, Int>>()
        val gateway = startGateway(
            onDevicesChanged = { devices, connections ->
                synchronized(observed) { observed.add(devices to connections) }
            },
        )
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        val socket = connectAndSend(
            gatewayHost,
            gateway.port,
            "GET /ws HTTP/1.1\r\nHost: x\r\n" +
                "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n",
        )
        val head = readHeadBytes(socket.getInputStream())
        assertTrue(String(head, Charsets.ISO_8859_1).startsWith("HTTP/1.1 101"))
        // WS 活跃中（101 已回）直接 stop：在途 handler 与 stop 的计数收口
        // 并发，非负夹紧保证回调不出现负值。
        gateway.stop()
        awaitCountSettle(observed)
        synchronized(observed) {
            assertEquals(0 to 0, observed.last())
            assertTrue(
                "negative observation in $observed",
                observed.all { it.first >= 0 && it.second >= 0 },
            )
        }
        runCatching { socket.close() }
        awaitUpstreamRelease(gateway)
        assertEquals(0, gateway.observedActiveUpstreamCount)
        assertTrue(gateway.trackedUpstreamSockets.isEmpty())
    }

    // ---- 设备计数（按来源 IP 聚合）----

    /**
     * 同一来源 IP 的两条并发连接 → 设备数 1、连接数 2；关闭一条后设备仍
     * 1、连接数 1；两条全部关闭后设备数归 0；stop 幂等收口 0。
     *
     * 并发连接用两枚真实 socket 同时挂在 handler 里（门禁 cookie 通过、
     * 有效期内核通过——两枚 socket 同时处于在途转发/泵阶段，随客户端关闭
     * 收尾出账）。
     */
    @Test
    fun deviceCountAggregatesConnectionsByClientIp() {
        val observed = mutableListOf<Pair<Int, Int>>()
        val gateway = startGateway(
            onDevicesChanged = { devices, connections ->
                synchronized(observed) { observed.add(devices to connections) }
            },
        )
        // 长会话载体选 WS 升级：101 回来后双向泵让两条连接持续挂在网关
        // （普通请求单请求单连接，响应即收尾，等不到出账前的窗口）。
        val sockets = Array(2) {
            WebSocketClient(gatewayHost, gateway.port).apply { open() }
        }
        // 两条连接均来自同一来源（同一环回地址），只要都还挂着就是
        // 1 台设备、2 条连接（设备账按 IP 聚合，连接账逐条平账）。
        try {
            awaitDevices { gateway.connectionCount >= 2 }
            assertEquals(1, gateway.connectedDeviceCount)
            assertEquals(2, gateway.connectionCount)
            assertEquals(mapOf("127.0.0.1" to 2), gateway.trackedDeviceSessions)
            synchronized(observed) {
                assertTrue("observations=$observed", observed.contains(1 to 2))
            }
        } finally {
            // 关一条（另一个仍挂着）→ 设备仍 1、连接 1
            sockets[0].close()
            awaitDevices { gateway.connectionCount == 1 && gateway.connectedDeviceCount == 1 }
            assertEquals(1, gateway.connectedDeviceCount)
            assertEquals(1, gateway.connectionCount)
            assertEquals(mapOf("127.0.0.1" to 1), gateway.trackedDeviceSessions)
            // 全关 → 设备 0、连接 0，账面清空
            sockets[1].close()
        }
        awaitDevices { gateway.connectedDeviceCount == 0 && gateway.connectionCount == 0 }
        assertEquals(0, gateway.connectedDeviceCount)
        assertEquals(0, gateway.connectionCount)
        assertTrue(gateway.trackedDeviceSessions.isEmpty())
        // stop 后仍收口为 0（幂等）
        gateway.stop()
        assertEquals(0, gateway.connectedDeviceCount)
        assertEquals(0, gateway.connectionCount)
    }

    /**
     * 不同来源 IP → 设备数 2（同码连接数平账）：把两枚客户端分别绑定到
     * 环回派生地址 127.0.0.2 / 127.0.0.3（环回网段各地址均可作本机来源，
     * 网关绑定在 127.0.0.10 与来源互不影响）。
     *
     * **来源地址能否绑定取决于平台环回配置**：Windows 只把 127.0.0.1 配在
     * 环回网卡上，把别的 127.x 当来源 bind 会报 "Cannot assign requested
     * address"。因此本用例按「该来源地址在本机真的可绑定」门控——不可绑定
     * 时 JUnit 记 skipped（不是通过），不写假绿断言。「不同 IP 分属不同设备」
     * 的聚合口径由 [LanDeviceTrackerTest] 覆盖（不依赖真实来源地址）。
     */
    @Test
    fun deviceCountIsPerDistinctClientIp() {
        val firstSource = "127.0.0.2"
        val secondSource = "127.0.0.3"
        assumeTrue(
            "本机不可把 $firstSource/$secondSource 作为来源地址绑定，跳过真实多源地址用例",
            canBindSourceAddress(firstSource) && canBindSourceAddress(secondSource),
        )
        val gateway = startGateway()
        val first = WebSocketClient(gatewayHost, gateway.port, sourceIp = firstSource).apply { open() }
        val second = WebSocketClient(gatewayHost, gateway.port, sourceIp = secondSource).apply { open() }
        try {
            awaitDevices { gateway.trackedDeviceSessions.keys.size >= 2 }
            assertEquals(2, gateway.connectedDeviceCount)
            assertEquals(2, gateway.connectionCount)
            val perIp = gateway.trackedDeviceSessions
            assertEquals(setOf(firstSource, secondSource), perIp.keys)
            assertEquals(1, perIp[firstSource])
            assertEquals(1, perIp[secondSource])
            // 最后活跃时刻已刷新（非 0 的观察面）
            assertTrue(
                gateway.trackedDeviceLastActive.values.all { it > 0 },
            )
        } finally {
            listOf(first, second).forEach { runCatching { it.close() } }
        }
        awaitDevices { gateway.connectedDeviceCount == 0 && gateway.connectionCount == 0 }
        assertEquals(0, gateway.connectedDeviceCount)
        assertEquals(0, gateway.connectionCount)
    }

    /**
     * 该来源地址在本机能否真的被绑定（环回派生地址的平台差异探测）：
     * Windows 的环回网卡只配了 127.0.0.1，其它 127.x 作为来源 bind 会失败。
     */
    private fun canBindSourceAddress(ip: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.bind(InetSocketAddress(InetAddress.getByName(ip), 0))
        }
        true
    }.getOrDefault(false)

    /**
     * stop 清设备账与连接账（挂着的 WS 长会话同样收口）：1 台设备的账面
     * 被 stop 拆平——trackedDeviceSessions 观察面直接锁账面。
     */
    @Test
    fun stopClearsDeviceLedger() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        // 一条正常完成的请求后账面归零
        assertEquals(
            200,
            rawGet(gateway, "/alive", listOf("Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie")).status,
        )
        awaitDevices { gateway.connectedDeviceCount == 0 && gateway.connectionCount == 0 }
        assertTrue(gateway.trackedDeviceSessions.isEmpty())
        // 大流量连接挂在文读中帐（WS 长会话）时 stop 清账
        val socket = connectAndSend(
            gatewayHost,
            gateway.port,
            "GET /ws HTTP/1.1\r\nHost: x\r\n" +
                "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n",
        )
        val head = readHeadBytes(socket.getInputStream())
        assertTrue(String(head, Charsets.ISO_8859_1).startsWith("HTTP/1.1 101"))
        awaitDevices { gateway.connectedDeviceCount == 1 && gateway.connectionCount == 1 }
        assertEquals(1, gateway.connectedDeviceCount)
        assertEquals(1, gateway.connectionCount)
        gateway.stop()
        assertEquals(0, gateway.connectedDeviceCount)
        assertTrue(gateway.trackedDeviceSessions.isEmpty())
        runCatching { socket.close() }
    }

    /**
     * 等设备/连接计数到达目标状态（[satisfied] 返回 true 即视为已满足）：
     * 设备账随连接收尾异步出账（stop 与在途 handler 有竞态），轮询至满足；
     * 超时仍未满足即判失败——不等满固定时长，也不静默放过。
     */
    private fun awaitDevices(satisfied: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (System.currentTimeMillis() < deadline) {
            if (satisfied()) return
            Thread.sleep(20)
        }
        fail("device/connection counts did not settle in time")
    }

    /**
     * 测试侧访问密钥文本（与 [accessKey] 同值；嵌套类持有外层实例字段的
     * 引用会让 WS 客户端携带 gateway 引用，固化为常量文本即可）。
     */
    private companion object {
        const val TEST_ACCESS_KEY = "0123456789abcdef0123456789abcdef"
    }

    /**
     * 从指定来源地址发起的 WS 长会话客户端（设备聚合的不同来源构造）。
     *
     * 端口字段刻意不叫 `port`：`Socket` 已有 `getPort()`（Kotlin 侧合成属性
     * `port`，未连接时为 0），同名会在 `Socket.apply {}` 里遮蔽掉真正的目标
     * 端口——连到 0 号端口即 "Cannot assign requested address"。
     */
    private class WebSocketClient(
        private val gatewayHost: String,
        private val gatewayPort: Int,
        private val sourceIp: String? = null,
    ) {
        private var socket: Socket? = null

        fun open() {
            val cookie = "Cookie: ${LanAccessSecrets.COOKIE_NAME}=" +
                LanAccessSecrets.cookieValueFor(TEST_ACCESS_KEY) + "\r\n"
            Socket().apply {
                tcpNoDelay = true
                soTimeout = 15_000
                if (sourceIp != null) {
                    bind(InetSocketAddress(InetAddress.getByName(sourceIp), 0))
                }
                connect(InetSocketAddress(gatewayHost, gatewayPort), 5_000)
                getOutputStream().write(
                    ("GET /ws HTTP/1.1\r\nHost: x\r\n$cookie" +
                        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                        "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                getOutputStream().flush()
                val head = readHeadBytes(inputStream)
                check(String(head, Charsets.ISO_8859_1).startsWith("HTTP/1.1 101")) {
                    "upgrade failed: ${String(head, Charsets.ISO_8859_1)}"
                }
            }.also { socket = it }
        }

        fun close() {
            socket?.let { runCatching { it.close() } }
            socket = null
        }
    }

    // ---- 密钥轮换（「全部断开并重置密钥」：新密钥重开门禁）----

    /**
     * 轮换语义集成：旧密钥下兑换成功（200）→ 以新密钥重建门禁 → 旧 cookie
     * 续访 401（旧签名的 cookie 对新密钥的 HMAC 校验不过）、旧 `?k=` 链接
     * 401、新密钥兑换回到 200。
     */
    @Test
    fun keyRotationInvalidatesOldCookieAndLinkAndAcceptsNewKey() {
        val freshKey = "fedcba9876543210fedcba9876543210"
        val gateway = startGateway()
        val browser = FakeBrowser(gatewayHost, gateway.port)
        assertEquals(200, browser.visit("/?k=$accessKey").status)
        // 轮换：以新密钥重建网关（控制层的 disconnectAll 流程即 stop 按新
        // 密钥重开——测试在网关层做同口径重建）
        gateway.stop()
        val rebuilt = startGateway(accessKeyValue = freshKey)
        // 旧 cookie 续访 → 401（不再注入 token，上游不可达）
        val staleCookie = LanAccessSecrets.cookieValueFor(accessKey)
        assertEquals(
            401,
            rawGet(rebuilt, "/", listOf("Cookie: ${LanAccessSecrets.COOKIE_NAME}=$staleCookie")).status,
        )
        // 旧 `?k=` 链接 → 401
        assertEquals(401, rawGet(rebuilt, "/?k=$accessKey").status)
        // 新密钥兑换 → 回到 200（模拟浏览器换新链接重新进入）
        val newBrowser = FakeBrowser(gatewayHost, rebuilt.port)
        assertEquals(200, newBrowser.visit("/?k=$freshKey").status)
    }

    // ---- 端到端 413 与 /index.html 路径 ----

    @Test
    fun requestBodyOverLimitEndsIn413WithNoUpstreamTouch() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        val declared = "314700672" // 300MB +（网关侧 Content-Length 上限 64MB）
        val response = rawSend(
            gateway,
            "POST /echo HTTP/1.1\r\nHost: x\r\n" +
                "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\n" +
                "Content-Length: $declared\r\nConnection: close\r\n\r\n",
        )
        assertEquals(413, response.status)
        // 413 在体读取阶段终结：上游没有收到这次请求
        assertTrue(mockDsh.requests.none { it.path == "/echo" })
        awaitUpstreamRelease(gateway)
        assertEquals(0, gateway.observedActiveUpstreamCount)
    }

    @Test
    fun indexPathReachesUpstreamAndReleasesSocket() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        val response = rawGet(
            gateway,
            "/index.html",
            listOf("Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie"),
        )
        assertEquals(200, response.status)
        assertEquals(
            "/index.html",
            mockDsh.requests.single { it.path == "/index.html" }.path,
        )
        awaitUpstreamRelease(gateway)
        assertEquals(0, gateway.observedActiveUpstreamCount)
        assertTrue(gateway.trackedUpstreamSockets.isEmpty())
    }

    // ---- 核心 5：WS 升级透传（升级即长连接；泵结束双双收口） ----

    @Test
    fun websocketUpgradeTunnelsBidirectionally() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        Socket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress(gatewayHost, gateway.port), 5_000)
            val out = socket.getOutputStream()
            out.write(
                ("GET /ws HTTP/1.1\r\nHost: x\r\n" +
                    "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\n" +
                    "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray(),
            )
            out.flush()
            val head = readHeadBytes(socket.getInputStream())
            assertTrue(String(head, Charsets.ISO_8859_1).startsWith("HTTP/1.1 101"))
            val payload = "frame-\u0001\u0002\u0003"
            out.write(payload.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val echoed = StringBuilder()
            val input = socket.getInputStream()
            val buffer = ByteArray(1024)
            while (echoed.length < payload.length) {
                val read = input.read(buffer)
                assertTrue(read > 0)
                echoed.append(String(buffer, 0, read, Charsets.ISO_8859_1))
            }
            assertEquals(payload, echoed.toString())
            socket.shutdownOutput()
        }
        // 客户端关泵 → 网关收口两套接字：活跃上游数归零（失败路径同样安全）
        awaitUpstreamRelease(gateway)
        assertEquals(0, gateway.observedActiveUpstreamCount)
    }

    // ---- 核心 6：chunked 请求体解块转发 + 转发剥离 ----

    @Test
    fun chunkedRequestBodyIsDecodedAndForwarded() {
        val gateway = startGateway()
        val gateCookie = LanAccessSecrets.cookieValueFor(accessKey)
        val payload = "chunk-part-1&chunk-part-2"
        val response = rawSend(
            gateway,
            "POST /echo HTTP/1.1\r\nHost: x\r\n" +
                "Cookie: ${LanAccessSecrets.COOKIE_NAME}=$gateCookie\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "X-Forwarded-For: 192.168.7.7\r\n" +
                "TE: trailers\r\nConnection: close\r\n\r\n" +
                payload.length.toString(16) + "\r\n" + payload + "\r\n0\r\n\r\n",
        )
        assertEquals(200, response.status)
        assertEquals("ECHO:$payload", response.body.trim())
        val seen = mockDsh.requests.single { it.path == "/echo" }
        // 请求体按 Content-Length 转发（chunked 帧不在上游重现）
        assertTrue(seen.headerNames.contains("Content-Length"))
        assertFalse(seen.headerNames.contains("Transfer-Encoding"))
        assertFalse(seen.headerNames.contains("X-Forwarded-For"))
        assertFalse(seen.headerNames.contains("TE"))
    }

    // ---- 模拟 DSH（按取证行为造型） ----

    /**
     * 模拟 DSH 0.2.1-rc.5 的 `authorizeIndex` 骨架 + `dsh-host-frontend-static`
     * 的静态分支：
     * - 带 token 的 `GET /` 一律 303（`Set-Cookie` 会话 + `Location: ./`），
     *   **不看是否已认证**（取证核心分支）；
     * - 无 token 的 `GET /`：无会话 cookie → 401；有 `dsh-auth-*` 会话
     *   cookie → 200；
     * - 其余路径一律 200（`/echo` 回显请求体；`/ws` 按 HTTP 101 升级回声）。
     */
    private class MockDsh(
        private val launchToken: String,
        /** 置 false 时永不回 `Set-Cookie: dsh-auth-*`（防循环保险的反面取景）。 */
        private val issuesSession: () -> Boolean = { true },
    ) {

        class RequestRecord(
            val target: String,
            val path: String,
            val cookieHeader: String,
            val headerNames: List<String>,
        ) {
            val hasDshAuthCookie: Boolean =
                LanUpstream.hasUpstreamSessionCookie(listOf(cookieHeader))
        }

        companion object {
            /** 模拟 DSH 的会话 cookie 名（`dsh-auth-` 前缀 + 伪造派生段）。 */
            const val SESSION_COOKIE_NAME = "dsh-auth-mockt90jgGKZm3"
            const val SESSION_COOKIE_VALUE = "v1.mockbody.mocksig"
        }

        /** 上游收到的请求（测试观察面）。 */
        val requests = ConcurrentLinkedQueue<RequestRecord>()

        private val executor = Executors.newCachedThreadPool()
        private var server: ServerSocket? = null
        private val closed = AtomicBoolean(false)

        val port: Int get() = server?.localPort ?: 0

        fun start() {
            val s = ServerSocket()
            s.bind(InetSocketAddress("127.0.0.1", 0), 16)
            server = s
            executor.execute {
                while (!closed.get()) {
                    val socket = try {
                        s.accept()
                    } catch (_: Exception) {
                        return@execute
                    }
                    executor.execute { serve(socket) }
                }
            }
        }

        private fun serve(socket: Socket) {
            try {
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val head = HttpWire.readHead(input) ?: return
                record(head)
                val target = head.startLine.split(' ')[1]
                val path = runCatching { URI(target).path ?: "/" }.getOrDefault("/")
                if (WsTunnel.isUpgradeRequest(head.headers)) {
                    // 升级：101 后双向回声（单一 socket 即客户端）
                    socket.getOutputStream().write(
                        ("HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\nConnection: Upgrade\r\n\r\n")
                            .toByteArray(Charsets.ISO_8859_1),
                    )
                    socket.getOutputStream().flush()
                    WsTunnel.pumpBidirectional(
                        clientOut = socket.getOutputStream(),
                        clientIn = socket.getInputStream(),
                        upstreamOut = socket.getOutputStream(),
                        upstreamIn = socket.getInputStream(),
                    )
                    return
                }
                if (path == "/echo") {
                    val body = HttpWire.readRequestBody(input, head)
                    respond(socket, 200, "ECHO:${body?.decodeToString().orEmpty()}".toByteArray())
                    return
                }
                when (path) {
                    // 一段超长响应体（客户端断开用景：发一半即够）
                    "/bigstream" -> {
                        respondHead(socket, 200, 1 shl 20)
                        val out = socket.getOutputStream()
                        out.write(ByteArray(64 * 1024))
                        out.flush()
                        return // 半途而废：不再继续（单请求单连接口径）
                    }
                    // 收完请求即暴力关 socket（上游 RST 用景）
                    "/abrupt" -> return
                    // 响应头超 128KB 用景：只写头部字节不断流
                    "/hugehead" -> {
                        val out = socket.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.ISO_8859_1))
                        // 单头值约 190KB（大于 HttpWire 的 128KB 头上限）
                        val blob = ByteArray(190 * 1024) { 'Z'.code.toByte() }
                        out.write("X-Blob: ".toByteArray(Charsets.ISO_8859_1))
                        out.write(blob)
                        out.write("\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        out.flush()
                        Thread.sleep(3_000) // 给网关时间读完并抛错
                        return
                    }
                }
                val hasToken = target.contains("token=")
                val hasSession = LanUpstream.hasUpstreamSessionCookie(head.headerValues("Cookie"))
                when {
                    hasToken && path == "/" -> {
                        val sessionHeaders = if (issuesSession()) {
                            listOf(
                                "Set-Cookie: $SESSION_COOKIE_NAME=$SESSION_COOKIE_VALUE; HttpOnly; Path=/",
                            )
                        } else {
                            emptyList()
                        }
                        respond(
                            socket,
                            303,
                            ByteArray(0),
                            listOf(
                                "Location: ./",
                                "Referrer-Policy: no-referrer",
                            ) + sessionHeaders,
                        )
                    }
                    path == "/" && !hasSession -> respond(
                        socket,
                        401,
                        "dsh web authentication required\n".toByteArray(),
                    )
                    else -> respond(socket, 200, "hello-from-dsh".toByteArray())
                }
            } catch (_: Exception) {
                // 单连接终局
            } finally {
                // 单请求单连接语义（Connection: close 由请求头指明）：响应后收口
                runCatching { socket.close() }
            }
        }

        private fun record(head: HttpWire.HttpHead) {
            val target = head.startLine.split(' ')[1]
            requests.add(
                RequestRecord(
                    target = target,
                    path = runCatching { URI(target).path ?: "/" }.getOrDefault("/"),
                    cookieHeader = head.firstHeader("Cookie").orEmpty(),
                    headerNames = head.headers.map { it.name },
                ),
            )
        }

        private fun respond(socket: Socket, status: Int, body: ByteArray, headers: List<String> = emptyList()) {
            val phrase = when (status) {
                303 -> "See Other"
                401 -> "Unauthorized"
                else -> "OK"
            }
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(' ').append(phrase).append("\r\n")
                headers.forEach { append(it).append("\r\n") }
                append("Content-Length: ").append(body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.flush()
        }

        /** 只回响应头（内容长度声明 + 关闭语义），体由调用方另行续写。 */
        private fun respondHead(socket: Socket, status: Int, contentLength: Int) {
            val head = buildString {
                append("HTTP/1.1 ").append(status).append(" OK\r\n")
                append("Content-Length: ").append(contentLength).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
        }

        fun close() {
            closed.set(true)
            runCatching { server?.close() }
            executor.shutdownNow()
        }
    }

    // ---- 原始 socket 请求/响应工具 ----

    private fun rawGet(gateway: LanGateway, path: String, cookies: List<String> = emptyList()): FakeResponse {
        val request = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            cookies.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        return parseResponse(rawSendByHost(gatewayHost, gateway.port, request))
    }

    private fun rawSend(gateway: LanGateway, request: String): FakeResponse =
        parseResponse(rawSendByHost(gatewayHost, gateway.port, request))

    private fun awaitUpstreamRelease(gateway: LanGateway) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5)
        while (System.currentTimeMillis() < deadline) {
            if (gateway.observedActiveUpstreamCount == 0) return
            Thread.sleep(20)
        }
    }

    /** 等计数回写序列静止（连 300ms 无新值即认为收口完成）。 */
    private fun awaitCountSettle(observed: MutableList<Pair<Int, Int>>) {
        var stable = System.currentTimeMillis()
        val deadline = stable + TimeUnit.SECONDS.toMillis(5)
        synchronized(observed) {
            while (System.currentTimeMillis() < deadline) {
                val size = observed.size
                Thread.sleep(50)
                synchronized(observed) {
                    if (observed.size == size) {
                        if (System.currentTimeMillis() - stable >= 300) return
                    } else {
                        stable = System.currentTimeMillis()
                    }
                }
            }
        }
    }
}

/**
 * 原始 socket 发送请求后**保持连接打开**返回（客户端中途断开用景：
 * 网关在回写响应途中对端才关）。
 */
private fun connectAndSend(host: String, port: Int, request: String): Socket =
    Socket().apply {
        tcpNoDelay = true
        soTimeout = 15_000
        connect(InetSocketAddress(host, port), 5_000)
        getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        getOutputStream().flush()
    }

/** 逐字节读 socket InputStream 至 EOF。 */
private fun rawSendByHost(host: String, port: Int, request: String): ByteArray {
    Socket().use { socket ->
        socket.tcpNoDelay = true
        socket.soTimeout = 15_000
        socket.connect(InetSocketAddress(host, port), 5_000)
        socket.getOutputStream().write(request.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        return readStreamToEnd(socket.getInputStream())
    }
}

/** 读 InputStream 至 EOF。 */
private fun readStreamToEnd(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
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
    return out.toByteArray()
}

/** 解析原始响应字节为 [FakeResponse]。 */
private fun parseResponse(raw: ByteArray): FakeResponse {
    val text = String(raw, Charsets.ISO_8859_1)
    val headEnd = text.indexOf("\r\n\r\n")
    val headerBlock = if (headEnd >= 0) text.substring(0, headEnd) else text
    val body = if (headEnd >= 0) text.substring(headEnd + 4) else ""
    val lines = headerBlock.split("\r\n")
    val status = lines.first().substringAfter(' ').substringBefore(' ').trim().toIntOrNull() ?: 0
    val headerMap = HashMap<String, List<String>>()
    for (line in lines.drop(1)) {
        if (line.isBlank()) continue
        val name = line.substringBefore(':').trim()
        val value = line.substringAfter(':', "")
        headerMap.merge(name, listOf(value.trim())) { a, b -> a + b }
    }
    return FakeResponse(status, headerMap, body)
}

// ---- 模拟浏览器（cookie 罐 + 自动跟随 302/303；≤3 跳封顶由调用方断言） ----

internal class FakeResponse(
    val status: Int,
    private val headerMap: Map<String, List<String>>,
    val body: String,
) {
    fun header(name: String): String? = headerMap[name]?.firstOrNull()

    /** 某名字段的全部值（Set-Cookie 类多头字段的取值口径）。 */
    fun headersOf(name: String): List<String> = headerMap[name].orEmpty()

    val setCookieHeaders: List<String> get() = headersOf("Set-Cookie")
}

private class FakeBrowserVisit(
    val status: Int,
    val hops: Int,
    val locations: List<String>,
)

private class FakeBrowser(host: String, port: Int) {

    private val gatewayOrigin = "http://$host:$port"
    private val hostPort = port
    val cookies = LinkedHashMap<String, String>()

    /** 发起一次导航：跟随 3xx 重定向，任一响应里的 Set-Cookie 都回写罐。 */
    fun visit(target: String): FakeBrowserVisit {
        var path = target
        val locations = mutableListOf<String>()
        var hops = 0
        while (true) {
            hops += 1
            check(hops <= 8) { "too many hops" }
            val cookieHeader = if (cookies.isEmpty()) {
                ""
            } else {
                "Cookie: " + cookies.entries.joinToString("; ") {
                    "${it.key}=${it.value}"
                } + "\r\n"
            }
            val request =
                "GET $path HTTP/1.1\r\nHost: 127.0.0.10:$hostPort\r\n$cookieHeader" +
                    "Connection: close\r\n\r\n"
            val response = parseResponse(rawSendByHost("127.0.0.10", hostPort, request))
            response.setCookieHeaders.forEach { raw ->
                val name = raw.substringBefore('=').trim()
                if (name.isEmpty()) return@forEach
                val attributes = raw.substringAfter(';', "")
                val expire = attributes.contains("Max-Age=0")
                if (expire) {
                    cookies.remove(name)
                } else {
                    val value = raw.substringAfter('=', "").substringBefore(';').trim()
                    if (value.isNotEmpty()) cookies[name] = value
                }
            }
            val location = response.header("Location")
            if (location != null && response.status in listOf(301, 302, 303, 307, 308)) {
                locations.add(location)
                val resolved = URI(gatewayOrigin).resolve(location)
                path = (resolved.rawPath ?: "/") + (resolved.rawQuery?.let { "?$it" } ?: "")
                continue
            }
            return FakeBrowserVisit(response.status, hops, locations)
        }
    }
}

/** 读到头终止符（CRLFCRLF / LFLF）为止的字节。 */
private fun readHeadBytes(input: InputStream): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(1)
    while (true) {
        val read = input.read(buffer)
        check(read > 0) { "EOF while reading head" }
        out.write(buffer, 0, read)
        val bytes = out.toByteArray()
        val n = bytes.size
        if ((n >= 4 && bytes[n - 4] == '\r'.code.toByte() && bytes[n - 3] == '\n'.code.toByte() &&
                bytes[n - 2] == '\r'.code.toByte() && bytes[n - 1] == '\n'.code.toByte()) ||
            (n >= 2 && bytes[n - 2] == '\n'.code.toByte() && bytes[n - 1] == '\n'.code.toByte())
        ) {
            return bytes
        }
    }
}
