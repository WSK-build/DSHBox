package interlock.relay.core.exec.stream

import java.io.InputStream
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流控制端点的契约（纯 JVM，真 socket + 假 StreamControl）：
 * - 无/错 token 一律 403，token 在 query 或 `X-Pilot-Token` 头都收；
 * - /start 成功回 stream {port, token, endpoint, metadata}，失败回 503 + reason；
 * - /start 幂等（已在跑回 alreadyRunning + 端口）；/stop 调底层并清状态；/status 如实；
 * - CORS 只对 loopback 的 http(s) Origin 回显 ACAO；
 * - 绑定元数据来自实际 ServerSocket（127.0.0.1 / loopback）。
 */
class TrustedStreamControlEndpointTest {

    /** 可编程的假控制通路：记录调用次数，按脚本回结果。 */
    private class FakeControl : StreamControl {
        var outcome: StreamControl.StartOutcome =
            StreamControl.StartOutcome.Failed("not configured")
        private var runningFlag = false
        var stopAcknowledged = true
        val starts = AtomicInteger(0)
        val stops = AtomicInteger(0)

        override suspend fun start(waitMs: Long): StreamControl.StartOutcome {
            starts.incrementAndGet()
            if (outcome is StreamControl.StartOutcome.Running) runningFlag = true
            return outcome
        }

        override fun stop(): Boolean {
            stops.incrementAndGet()
            runningFlag = false
            return stopAcknowledged
        }

        override val running: Boolean
            get() = runningFlag

        /** 宿主侧会话序号（与控制器同一口径的计数，不是 token）。 */
        override val sessionLabel: String? = "S7"

        /** 测试驱动：模拟会话自然结束（EOF/宿主 watchdog 收场后 controller.running=false）。 */
        fun simulateSessionEnded() {
            runningFlag = false
        }
    }

    /** 假会话日志：把控制面这一侧的读数收进列表。 */
    private class FakeSessionLog : StreamSessionLog {
        val records = java.util.Collections.synchronizedList(mutableListOf<StreamSession>())

        override fun write(record: StreamSession) {
            records.add(record)
        }

        fun find(phase: String): StreamSession? = records.firstOrNull { it.phase == phase }

        /** 取一条读数（没有就是断言失败，不给 null 继续走下去）。 */
        fun require(phase: String): StreamSession =
            find(phase) ?: throw AssertionError("缺少 phase=$phase 的会话读数")
    }

    private lateinit var control: FakeControl
    private lateinit var endpoint: TrustedStreamControlEndpoint
    private var sessionLog: FakeSessionLog? = null

    @After
    fun tearDown() {
        endpoint.stop()
    }

    private fun startEndpoint(): TrustedStreamControlEndpoint {
        val log = FakeSessionLog()
        sessionLog = log
        endpoint = TrustedStreamControlEndpoint(control, token = "control-token-1", sessionLog = log)
        assertTrue(endpoint.start())
        return endpoint
    }

    private fun runningServer(): FakeControl {
        control = FakeControl()
        control.outcome = StreamControl.StartOutcome.Running(
            FakeStreamHandle(port = 41000, token = "stream-token-9", metadata = "{\"mime\":\"video/mp4\"}"),
        )
        startEndpoint()
        return control
    }

    private fun get(
        target: String,
        origin: String? = null,
        headerToken: String? = null,
        tab: String? = "panel-tab-1",
        session: String? = null,
    ): Triple<Int, String, String> {
        val socket = Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
        socket.soTimeout = 5_000
        socket.getInputStream().use { input ->
            socket.getOutputStream().use { output ->
                val request = buildString {
                    append("GET ").append(target).append(" HTTP/1.1\r\n")
                    if (origin != null) append("Origin: ").append(origin).append("\r\n")
                    if (headerToken != null) append("X-Pilot-Token: ").append(headerToken).append("\r\n")
                    if (tab != null) append("X-Pilot-Tab: ").append(tab).append("\r\n")
                    if (session != null) append("X-Pilot-Session: ").append(session).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(request.toByteArray(Charsets.ISO_8859_1))
                output.flush()
                val raw = input.readBytes()
                val text = String(raw, Charsets.ISO_8859_1)
                val status = text.substringAfter(" ").substringBefore(" ").toInt()
                val bodyStart = text.indexOf("\r\n\r\n") + 4
                return Triple(status, text, String(raw.copyOfRange(bodyStart, raw.size), Charsets.UTF_8))
            }
        }
    }

    @Test
    fun `missing and wrong tokens are refused on every route`() {
        runningServer()
        assertEquals(403, get(TrustedStreamControlEndpoint.PATH_STATUS).first)
        assertEquals(403, get(TrustedStreamControlEndpoint.PATH_STATUS + "?token=wrong").first)
        assertEquals(403, get(TrustedStreamControlEndpoint.PATH_START + "?token=wrong").first)
        assertEquals(403, get(TrustedStreamControlEndpoint.PATH_STOP).first)
        assertEquals("wrong tokens never reach the delegate", 0, control.starts.get())
        assertEquals(0, control.stops.get())
    }

    @Test
    fun `token is accepted from the query or the header`() {
        runningServer()
        assertEquals(200, get(TrustedStreamControlEndpoint.PATH_STATUS + "?token=control-token-1").first)
        assertEquals(200, get(TrustedStreamControlEndpoint.PATH_STATUS, headerToken = "control-token-1").first)
    }

    @Test
    fun `start returns the stream endpoint bundle and status reflects it`() {
        runningServer()
        val (status, head, body) = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        assertEquals(200, status)
        assertTrue(body.contains("\"ok\":true"))
        assertTrue(body.contains("\"port\":41000"))
        assertTrue(body.contains("\"token\":\"stream-token-9\""))
        assertTrue(body.contains("\"metadata\":{\"mime\":\"video/mp4\"}"))
        assertEquals(1, control.starts.get())
        val status2 = get(TrustedStreamControlEndpoint.PATH_STATUS + "?token=control-token-1")
        assertTrue(status2.third.contains("\"running\":true"))
        // 幂等：再 start 不再打底层，回 alreadyRunning
        val again = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        assertEquals(200, again.first)
        assertTrue(again.third.contains("\"alreadyRunning\":true"))
        assertEquals(1, control.starts.get())
    }

    @Test
    fun `a failed start is a 503 with the device-side reason`() {
        control = FakeControl()
        control.outcome = StreamControl.StartOutcome.Failed("no trusted display")
        startEndpoint()
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        assertEquals(503, status)
        assertTrue(body.contains("\"ok\":false"))
        assertTrue(body.contains("no trusted display"))
        val statusAfter = get(TrustedStreamControlEndpoint.PATH_STATUS + "?token=control-token-1")
        assertTrue(statusAfter.third.contains("\"running\":false"))
    }

    @Test
    fun `stop calls the delegate and clears the running state`() {
        val control = runningServer()
        get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1")
        assertEquals(200, status)
        assertTrue(body.contains("\"ok\":true"))
        assertEquals(1, control.stops.get())
        val after = get(TrustedStreamControlEndpoint.PATH_STATUS + "?token=control-token-1")
        assertTrue(after.third.contains("\"running\":false"))
    }

    @Test
    fun `cors is echoed only for loopback origins`() {
        runningServer()
        // loopback origin：回显
        val (_, loopbackHead, _) = get(
            TrustedStreamControlEndpoint.PATH_STATUS + "?token=control-token-1",
            origin = "http://127.0.0.1:3080",
        )
        assertTrue(loopbackHead.contains("Access-Control-Allow-Origin: http://127.0.0.1:3080"))
        // 非 loopback origin：不给 ACAO —— 浏览器同源策略挡住跨站读
        val (_, externalHead, _) = get(
            TrustedStreamControlEndpoint.PATH_STATUS + "?token=control-token-1",
            origin = "http://evil.example",
        )
        assertFalse(externalHead.contains("Access-Control-Allow-Origin"))
        // 无 Origin（同源 fetch/非浏览器）：也没有 ACAO
        val (_, plainHead, _) = get(TrustedStreamControlEndpoint.PATH_STATUS + "?token=control-token-1")
        assertFalse(plainHead.contains("Access-Control-Allow-Origin"))
    }

    @Test
    fun `unknown paths are 404 and non-GET is 405`() {
        runningServer()
        assertEquals(404, get(TrustedStreamControlEndpoint.PATH_STATUS + "x?token=control-token-1").first)
        val socket = Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
        socket.soTimeout = 5_000
        socket.use {
            it.getOutputStream().write("POST ${TrustedStreamControlEndpoint.PATH_STATUS}?token=control-token-1 HTTP/1.1\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            val text = String(it.getInputStream().readBytes(), Charsets.ISO_8859_1)
            assertEquals(405, text.substringAfter(" ").substringBefore(" ").toInt())
        }
    }

    @Test
    fun `stop with a lost device reply is a 503 with the reason, not a lying ok`() {
        val control = runningServer()
        control.outcome = StreamControl.StartOutcome.Running(
            FakeStreamHandle(port = 41001, token = "t", metadata = "{}"),
        )
        get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        control.stopAcknowledged = false
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1")
        assertEquals(503, status)
        assertTrue(body.contains("\"ok\":false"))
        assertTrue(body.contains("tearing down"))
        // 设备侧 stop 仍被请求过
        assertEquals(1, control.stops.get())
    }

    @Test
    fun `idempotent start returns the full stream bundle so the caller can attach`() {
        runningServer()
        get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        assertEquals(200, status)
        // alreadyRunning 也必须回完整 bundle：port/token/endpoint/metadata 一个不少
        assertTrue(body.contains("\"alreadyRunning\":true"))
        assertTrue(body.contains("\"port\":41000"))
        assertTrue(body.contains("\"token\":\"stream-token-9\""))
        assertTrue(body.contains("\"endpoint\""))
        assertTrue(body.contains("\"metadata\""))
    }

    @Test
    fun `stop with a mismatched sessionId is a 409 and does not stop the encoder`() {
        val control = runningServer()
        get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        assertEquals(1, control.starts.get())
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1&sessionId=s-other-9")
        assertEquals(409, status)
        assertTrue(body.contains("mismatch"))
        assertEquals("不匹配的 stop 不停编码器", 0, control.stops.get())
    }

    @Test
    fun `stop without a sessionId stops the current session (missing-id compat)`() {
        val control = runningServer()
        get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1")
        assertEquals(200, status)
        assertTrue(body.contains("\"stopped\":true"))
        assertEquals(1, control.stops.get())
    }

    @Test
    fun `matching-ended - a stop for the same sessionId after natural end is 200 stopped-false`() {
        val control = runningServer()
        val start = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        val sessionId = extractSessionId(start.third)
        // 会话自然结束（EOF/宿主 watchdog 收场后 controller.running=false），但端点的
        // bundle 快照尚未被 /stop 清掉
        control.simulateSessionEnded()
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1&sessionId=" + sessionId)
        assertEquals(200, status)
        assertTrue(body.contains("\"stopped\":false"))
        assertEquals("自然结束的会话不再重打 stop", 0, control.stops.get())
    }

    @Test
    fun `stop with an unknown sessionId and no active session is a 409`() {
        runningServer()
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1&sessionId=s-unknown")
        assertEquals(409, status)
        assertTrue(body.contains("mismatch"))
    }

    @Test
    fun `stop without a sessionId and without an active session is 200 stopped-false`() {
        runningServer()
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1")
        assertEquals(200, status)
        assertTrue(body.contains("\"stopped\":false"))
    }

    @Test
    fun `session ids are unique across starts and across endpoint instances`() {
        // 同端点两次 start（stop 间隔）→ 两枚不同 id
        val control = runningServer()
        val start1 = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        val id1 = extractSessionId(start1.third)
        runStopFor(control, "control-token-1", id1)
        val start2 = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        val id2 = extractSessionId(start2.third)
        assertNotNull(id1)
        assertNotNull(id2)
        assertTrue("同端点的两枚 sessionId 必须不同", id1 != id2)
        // 不同端点实例 → 不同 id（跨 RelayContainer 重启不碰撞）
        val otherControl = FakeControl()
        otherControl.outcome = StreamControl.StartOutcome.Running(
            FakeStreamHandle(port = 41001, token = "t", metadata = "{}"),
        )
        val other = TrustedStreamControlEndpoint(otherControl, token = "ctl-2")
        assertTrue(other.start())
        val otherStart = get(TrustedStreamControlEndpoint.PATH_START + "?token=ctl-2")
        val idOther = extractSessionId(otherStart.third)
        assertNotNull(idOther)
        assertTrue("跨实例 sessionId 不碰撞", idOther != id1 && idOther != id2)
        other.stop()
    }

    /** 从 start 回包里取 stream.sessionId（JSON 形状固定，逐段解析避开转义）。 */
    private fun extractSessionId(body: String): String? {
        val marker = "\"sessionId\":\""
        val at = body.indexOf(marker) ?: return null
        val from = at + marker.length
        val to = body.indexOf('"', from)
        if (to < 0) return null
        return body.substring(from, to)
    }

    private fun runStopFor(control: FakeControl, token: String, sessionId: String?) {
        val suffix = if (sessionId != null) "&sessionId=" + sessionId else ""
        get(TrustedStreamControlEndpoint.PATH_STOP + "?token=" + token + suffix)
    }

    @Test
    fun `binding metadata comes from the actual server socket`() {
        runningServer()
        assertEquals("127.0.0.1", endpoint.boundAddress)
        assertEquals(true, endpoint.boundLoopback)
        assertTrue(endpoint.port > 0)
    }

    @Test
    fun `stop resets the binding metadata`() {
        runningServer()
        endpoint.stop()
        assertNull(endpoint.boundAddress)
        assertNull(endpoint.boundLoopback)
        assertEquals(-1, endpoint.port)
    }

    @Test
    fun `a failed start is recorded with the reason the caller saw`() {
        control = FakeControl()
        control.outcome = StreamControl.StartOutcome.Failed("no trusted display")
        startEndpoint()
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        assertEquals(503, status)
        val record = sessionLog!!.require(StreamSessionPhase.START)
        assertEquals("failed", record.result)
        assertEquals("no trusted display", record.reason)
        assertTrue("控制面读数带同一场会话的序号", record.session.startsWith("S7"))
    }

    // ── 面板输入（/input）──

    /** POST 助手：/input 是端点唯一的 POST 路由。回 (status, body)。 */
    private var lastSessionId: String? = null

    /** JSON 双引号的 Kotlin 转义简写（测试体里拼 body 用）。 */
    private val Q = "\""


    /** 开一场会话并把 sessionId 记下（/input 的会话对账用）。 */
    private fun startInputSession(tab: String? = "panel-tab-1") {
        val (_, _, startBody) = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1", tab = tab)
        lastSessionId = Regex("\"sessionId\":\\s*\"([^\"]+)\"").find(startBody)?.groupValues?.get(1)
    }

    /** POST /input：默认带面板 tab 头与本会话 sessionId（可用参数覆盖以测负路径）。 */
    private fun postInput(
        body: String,
        target: String = TrustedStreamControlEndpoint.PATH_INPUT + "?token=control-token-1",
        tab: String? = "panel-tab-1",
        sessionId: String? = lastSessionId,
        raw: Boolean = false,
    ): Pair<Int, String> {
        val payloadBody = if (!raw && sessionId != null && body.startsWith("{")) {
            "{\"sessionId\": \"" + sessionId + "\"," + body.substring(1)
        } else {
            body
        }
        val socket = Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
        socket.soTimeout = 5_000
        socket.getInputStream().use { input ->
            socket.getOutputStream().use { output ->
                val payload = payloadBody.toByteArray(Charsets.UTF_8)
                val request = buildString {
                    append("POST ").append(target).append(" HTTP/1.1\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(payload.size).append("\r\n")
                    if (tab != null) append("X-Pilot-Tab: ").append(tab).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(request.toByteArray(Charsets.ISO_8859_1))
                output.write(payload)
                output.flush()
                val raw = input.readBytes()
                val text = String(raw, Charsets.ISO_8859_1)
                val status = text.substringAfter(" ").substringBefore(" ").toInt()
                val bodyStart = text.indexOf("\r\n\r\n") + 4
                return status to String(raw.copyOfRange(bodyStart, raw.size), Charsets.UTF_8)
            }
        }
    }

    /** 起一个带 /input 执行口、metadata 带源尺寸的端点；记录执行面收到的命令。 */
    private fun runningInputServer(
        received: MutableList<PanelInputPolicy.Command>,
        inputTimeoutMs: Long = 6_000L,
        executor: TrustedStreamControlEndpoint.PanelInputExecutor? = null,
    ): FakeControl {
        val control = FakeControl()
        control.outcome = StreamControl.StartOutcome.Running(
            FakeStreamHandle(
                port = 41003,
                token = "stream-token-input",
                metadata = "{\"mime\":\"video/mp4\",\"width\":720,\"height\":580," +
                    "\"sourceWidth\":2560,\"sourceHeight\":2064}",
            ),
        )
        val log = FakeSessionLog()
        sessionLog = log
        endpoint = TrustedStreamControlEndpoint(
            control,
            token = "control-token-1",
            sessionLog = log,
            // 执行口假体：面板输入**不经任何 gate/审批面** —— 端点直接把映射后的命令
            // 交给执行口；这里的假体就是"到达执行口"的全部判据。
            panelInput = executor ?: TrustedStreamControlEndpoint.PanelInputExecutor { request -> received.add(request); true to "" },
            inputTimeoutMs = inputTimeoutMs,
        )
        assertTrue(endpoint.start())
        return control
    }

    @Test
    fun `start and input without the panel tab credential are 403 with zero executor calls`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        // /start 只有控制 token、没有 tab 头 → 403，会话根本不开
        val (status, _) = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1", tab = null)
        assertEquals(403, status)
        assertEquals("无凭据不开流", 0, received.size)
        // 有会话的场景：tab 缺失/错值都在会话检查之后仍按 403 拒（不泄露原因差别）
        startInputSession()
        val (inputStatus, _) = postInput("{\"action\":\"tap\",\"x\":0.5,\"y\":0.5}", tab = null)
        assertEquals(403, inputStatus)
        val (wrongTabStatus, wrongTabBody) = postInput("{\"action\":\"tap\",\"x\":0.5,\"y\":0.5}", tab = "other-panel")
        assertEquals(403, wrongTabStatus)
        assertTrue(wrongTabBody.contains("forbidden"))
        assertEquals("错 tab 零执行", 0, received.size)
    }

    @Test
    fun `idempotent start with a different panel tab is a 409, not a new session`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        startInputSession()
        assertEquals(200, get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1").first)
        val (status, body) = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1", tab = "another-panel")
        assertEquals(409, status)
        assertTrue(body.contains("another panel"))
    }

    @Test
    fun `input with a mismatched sessionId is a 409 like stop`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        startInputSession()
        val (status, body) = postInput(
            "{\"action\":\"tap\",\"x\":0.5,\"y\":0.5}",
            sessionId = "s-someone-else",
        )
        assertEquals(409, status)
        assertTrue(body.contains("session id mismatch"))
        assertEquals("会话错配零执行", 0, received.size)
    }

    @Test
    fun `slow executor reports unknown outcome while status and stop stay responsive`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val inputWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
        runningInputServer(
            received,
            inputTimeoutMs = 300L,
            executor = { request: PanelInputPolicy.Command ->
                received.add(request)
                // 镜像生产形态：latch 挂住**专用池 worker**，执行口真挂起（不在端点
                // 协程线程上阻塞）——withTimeoutOrNull 到点才真的能返回。
                kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                    inputWorker.execute {
                        latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
                        if (cont.isActive) cont.resumeWith(kotlin.Result.success(true to ""))
                    }
                }
            },
        )
        startInputSession()
        val sid = lastSessionId
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val inputFuture = pool.submit<Pair<Int, String>> {
                postInput("{" + Q + "action" + Q + ":" + Q + "tap" + Q + "," + Q + "x" + Q + ":0.5," + Q + "y" + Q + ":0.5}", sessionId = sid)
            }
            Thread.sleep(100)  // 输入已在执行口里挂着
            val statusAt = System.nanoTime()
            assertEquals(200, get(TrustedStreamControlEndpoint.PATH_STATUS + "?token=control-token-1").first)
            val statusMs = (System.nanoTime() - statusAt) / 1_000_000
            assertTrue("/status 应在 500ms 内回来（实际 ${statusMs}ms）", statusMs < 500)
            val stopAt = System.nanoTime()
            val (_, _, stopBody) = get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1")
            val stopMs = (System.nanoTime() - stopAt) / 1_000_000
            assertTrue("/stop 不被输入阻塞（实际 ${stopMs}ms）", stopMs < 1000)
            assertTrue("/stop 回 stopped", stopBody.contains("stopped"))
            val (status, body) = inputFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(504, status)
            assertTrue("回话带 outcome unknown：$body", body.contains("outcome"))
            assertTrue("文案带实际超时毫秒", body.contains("300"))
            assertEquals("执行口被调过一次（结局未知但仍执行）", 1, received.size)
            latch.countDown()  // 放 worker 退出：不再阻塞、池不泄漏
            inputWorker.shutdown()
            inputWorker.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            pool.shutdown()
        } finally {
            latch.countDown()
            inputWorker.shutdownNow()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a protected-app start refusal is a 403 with the structured code`() {
        control = FakeControl()
        control.outcome = StreamControl.StartOutcome.Failed(
            "the app in front of the trusted display (com.bank) is on the user's protected-apps list",
            code = CODE_PROTECTED_APP,
        )
        startEndpoint()
        val (status, _, body) = get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        assertEquals(403, status)
        assertTrue(body.contains("\"code\":\"protected_app\""))
        assertTrue(body.contains("protected-apps list"))
        // 拒绝路径不落任何会话：没有 stream token 可发（/input 无会话 → 409，另有专测）
    }

    @Test
    fun `truncated input body is reported as truncated, not too large`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        startInputSession()
        // Content-Length 声明 100 字节，只发 10 字节后 shutdownOutput：读端拿到 EOF
        val crlf = "\r\n"
        val socket = Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
        socket.soTimeout = 5_000
        socket.getOutputStream().use { output ->
            val head = "POST " + TrustedStreamControlEndpoint.PATH_INPUT + "?token=control-token-1 HTTP/1.1" + crlf +
                "Content-Type: application/json" + crlf +
                "Content-Length: 100" + crlf +
                "X-Pilot-Tab: panel-tab-1" + crlf + crlf
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(ByteArray(10))
            output.flush()
            socket.shutdownOutput()
            val raw = socket.getInputStream().readBytes()
            val text = String(raw, Charsets.ISO_8859_1)
            val status = text.substringAfter(" ").substringBefore(" ").toInt()
            val bodyStart = text.indexOf("\r\n\r\n") + 4
            val body = String(raw.copyOfRange(bodyStart, raw.size), Charsets.UTF_8)
            assertEquals(400, status)
            assertTrue(body.contains("truncated"))
        }
        assertEquals("截断的 body 不执行", 0, received.size)
    }

    @Test
    fun `every executed input leaves an input phase session log without tokens`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        startInputSession()
        assertEquals(200, postInput("{" + Q + "action" + Q + ":" + Q + "tap" + Q + "," + Q + "x" + Q + ":0.5," + Q + "y" + Q + ":0.5}").first)
        val record = sessionLog!!.records.firstOrNull { it.phase == StreamSessionPhase.INPUT }
        assertTrue("输入要留痕（phase=input）", record != null)
        assertEquals("ok", record!!.result)
        assertTrue(record.reason.contains("tap"))
        assertTrue(record.reason.contains("(1280,1032)"))
        assertTrue("无 token", !record.reason.contains("token"))
    }

    @Test
    fun `input without a live session is a 409`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        val (status, body) = postInput("{\"action\":\"tap\",\"x\":0.5,\"y\":0.5}")
        assertEquals(409, status)
        assertTrue(body.contains("no stream session"))
        assertEquals("无会话零执行", 0, received.size)
    }

    @Test
    fun `input validates action coordinates and duration into 400 without executing`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        startInputSession()
        // GET /input → 405（/input 只认 POST）
        assertEquals(405, get(TrustedStreamControlEndpoint.PATH_INPUT + "?token=control-token-1").first)
        // 未知动作
        assertEquals(400, postInput("{\"action\":\"text\",\"x\":0.5,\"y\":0.5}").first)
        // 坐标越界
        assertEquals(400, postInput("{\"action\":\"tap\",\"x\":1.5,\"y\":0.5}").first)
        // swipe 缺 duration
        assertEquals(400, postInput("{\"action\":\"swipe\",\"x1\":0,\"y1\":0,\"x2\":1,\"y2\":1}").first)
        // body 不成形
        assertEquals(400, postInput("not json").first)
        assertEquals("400 路径零执行", 0, received.size)
    }

    @Test
    fun `input maps normalized coordinates onto the source dimensions and executes once`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        startInputSession()
        val (status, body) = postInput("{\"action\":\"tap\",\"x\":0.5,\"y\":0.5}")
        assertEquals(200, status)
        assertTrue(body.contains("\"ok\":true"))
        // 面板输入不触发 gate/审批：到达执行口的命令就是全部事实 —— 源尺寸 2560×2064
        // 下的 (0.5,0.5) 应该已经映射成像素 (1280,1032)。
        assertEquals(1, received.size)
        val command = received.single()
        assertEquals("tap", command.action)
        assertEquals(1280, command.x)
        assertEquals(1032, command.y)
    }

    @Test
    fun `input is rate limited per session`() {
        val received = mutableListOf<PanelInputPolicy.Command>()
        runningInputServer(received)
        startInputSession()
        repeat(PanelInputPolicy.RATE_LIMIT_PER_SECOND) { i ->
            val x = (i.toDouble() / (PanelInputPolicy.RATE_LIMIT_PER_SECOND * 10)).toString()
            assertEquals(200, postInput("{\"action\":\"tap\",\"x\":$x,\"y\":0.5}").first)
        }
        val (status, body) = postInput("{\"action\":\"tap\",\"x\":0.9,\"y\":0.5}")
        assertEquals(429, status)
        assertTrue(body.contains("rate limit"))
        assertEquals("超限的请求不执行", PanelInputPolicy.RATE_LIMIT_PER_SECOND, received.size)
    }

    @Test
    fun `a successful start is recorded with the metadata source and encode dimensions`() {
        control = FakeControl()
        control.outcome = StreamControl.StartOutcome.Running(
            FakeStreamHandle(
                port = 41002,
                token = "stream-token-dims",
                // 720 回退场景：源 2560×2064，实际只编到 720×580 —— 两组要都能对上。
                metadata = "{\"mime\":\"video/mp4\",\"width\":720,\"height\":580," +
                    "\"sourceWidth\":2560,\"sourceHeight\":2064}",
            ),
        )
        startEndpoint()
        startInputSession()
        val record = sessionLog!!.require(StreamSessionPhase.START)
        assertEquals("ok", record.result)
        assertEquals(2560, record.sourceW)
        assertEquals(2064, record.sourceH)
        assertEquals(720, record.encodeW)
        assertEquals(580, record.encodeH)
    }


    @Test
    fun `a confirmed stop is recorded as confirmed`() {
        runningServer()
        get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1")
        val record = sessionLog!!.require(StreamSessionPhase.STOP)
        assertEquals("confirmed", record.result)
    }

    @Test
    fun `a stop with a lost device reply is recorded as unknown`() {
        val control = runningServer()
        get(TrustedStreamControlEndpoint.PATH_START + "?token=control-token-1")
        control.stopAcknowledged = false
        get(TrustedStreamControlEndpoint.PATH_STOP + "?token=control-token-1")
        val record = sessionLog!!.require(StreamSessionPhase.STOP)
        assertEquals("unknown", record.result)
        assertTrue("未知结局要带一句可读原因", record.reason.isNotEmpty())
    }

    // ──  面板任务面（/tasks、/move、/release）──

    /** 假任务执行口：记录调用、按脚本回结果（list 记来源方向）。 */
    private class FakeTaskOps : TrustedStreamControlEndpoint.PanelTaskOps {
        val moves = mutableListOf<Pair<Int, String>>()
        val listSources = mutableListOf<Boolean>()
        var releases = 0
        var lists = 0
        var listOutcome: Pair<Boolean, String> = true to
            "{\"displayId\":45,\"tasks\":[{\"taskId\":703,\"package\":\"com.demo\",\"label\":\"com.demo\",\"activity\":\".Main\"}]}"
        var moveOutcome: Pair<Boolean, String> = true to ""
        var releaseOutcome: Pair<Boolean, String> = true to ""

        override suspend fun list(sourceIsVirtual: Boolean): Pair<Boolean, String> {
            lists += 1
            listSources.add(sourceIsVirtual)
            return listOutcome
        }

        override suspend fun move(taskId: Int, direction: String): Pair<Boolean, String> {
            moves.add(taskId to direction)
            return moveOutcome
        }

        override suspend fun release(): Pair<Boolean, String> {
            releases += 1
            return releaseOutcome
        }

        var rotates = 0
        var rotateOutcome: Pair<Boolean, String> = true to "{\"width\":1032,\"height\":720}"

        override suspend fun rotate(): Pair<Boolean, String> {
            rotates += 1
            return rotateOutcome
        }
    }

    /** 起一个带任务执行口的端点，回 control（taskOps 由调用方持有）。 */
    private fun runningTaskServer(taskOps: FakeTaskOps): FakeControl {
        val control = FakeControl()
        control.outcome = StreamControl.StartOutcome.Running(
            FakeStreamHandle(port = 41009, token = "stream-token-tasks", metadata = "{\"mime\":\"video/mp4\"}"),
        )
        val log = FakeSessionLog()
        sessionLog = log
        endpoint = TrustedStreamControlEndpoint(
            control,
            token = "control-token-1",
            sessionLog = log,
            panelTasks = taskOps,
        )
        assertTrue(endpoint.start())
        return control
    }

    /** 最小 POST 助手：任意路由 + JSON body + tab 头。 */
    private fun postRaw(target: String, body: String, tab: String? = "panel-tab-1"): Pair<Int, String> {
        val socket = Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
        socket.soTimeout = 5_000
        socket.getInputStream().use { input ->
            socket.getOutputStream().use { output ->
                val payload = body.toByteArray(Charsets.UTF_8)
                val request = buildString {
                    append("POST ").append(target).append(" HTTP/1.1\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(payload.size).append("\r\n")
                    if (tab != null) append("X-Pilot-Tab: ").append(tab).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                output.write(request.toByteArray(Charsets.ISO_8859_1))
                output.write(payload)
                output.flush()
                val raw = input.readBytes()
                val text = String(raw, Charsets.ISO_8859_1)
                val status = text.substringAfter(" ").substringBefore(" ").toInt()
                val bodyStart = text.indexOf("\r\n\r\n") + 4
                return status to String(raw.copyOfRange(bodyStart, raw.size), Charsets.UTF_8)
            }
        }
    }

    @Test
    fun `tasks move release without a session are 409 with zero executor calls`() {
        val taskOps = FakeTaskOps()
        runningTaskServer(taskOps)
        val (tasksStatus, tasksBody) = get(TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1")
        assertEquals(409, tasksStatus)
        assertTrue(tasksBody.contains("no stream session"))
        val (moveStatus, moveBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
            "{\"sessionId\":\"s-1\",\"taskId\":1,\"direction\":\"main\"}",
        )
        assertEquals(409, moveStatus)
        assertTrue(moveBody.contains("no stream session"))
        val (releaseStatus, releaseBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_RELEASE + "?token=control-token-1",
            "{\"sessionId\":\"s-1\"}",
        )
        assertEquals(409, releaseStatus)
        assertTrue(releaseBody.contains("no stream session"))
        assertTrue("无会话零执行", taskOps.lists == 0 && taskOps.moves.isEmpty() && taskOps.releases == 0)
    }

    @Test
    fun `tasks move release with a wrong tab are 403 with zero executor calls`() {
        val taskOps = FakeTaskOps()
        runningTaskServer(taskOps)
        startInputSession()
        val sid = lastSessionId
        assertEquals(403, get(TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1", tab = "other").first)
        val (moveStatus, moveBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
            "{\"sessionId\":\"$sid\",\"taskId\":703,\"direction\":\"main\"}",
            tab = "other",
        )
        assertEquals(403, moveStatus)
        assertTrue(moveBody.contains("forbidden"))
        val (releaseStatus, _) = postRaw(
            TrustedStreamControlEndpoint.PATH_RELEASE + "?token=control-token-1",
            "{\"sessionId\":\"$sid\"}",
            tab = "other",
        )
        assertEquals(403, releaseStatus)
        assertTrue("错 tab 零执行", taskOps.lists == 0 && taskOps.moves.isEmpty() && taskOps.releases == 0)
    }

    @Test
    fun `tasks require the session header and a mismatch is a 409 (input-strict) and GET is 405`() {
        val taskOps = FakeTaskOps()
        runningTaskServer(taskOps)
        startInputSession()
        // /tasks 只认 GET：POST 过去 405（head 已排干语义对 GET 无 body 无关）。
        assertEquals(405, postRaw(TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1", "{}").first)
        // 无 sessionId 头 → 409（/input 严格口径，缺与错同码）。
        val (missing, missingBody) = get(TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1")
        assertEquals(409, missing)
        assertTrue(missingBody.contains("session id mismatch"))
        // 错 sessionId → 409。
        val (wrong, wrongBody) = get(
            TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1",
            session = "s-someone-else",
        )
        assertEquals(409, wrong)
        assertTrue(wrongBody.contains("session id mismatch"))
        assertTrue("缺/错 session 零执行", taskOps.lists == 0)
        // 对的 sessionId → 200，list 以虚拟屏为源。
        val (ok, _) = get(
            TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1",
            session = lastSessionId,
        )
        assertEquals(200, ok)
        assertEquals(1, taskOps.lists)
        assertEquals("/tasks 列的是虚拟屏", listOf(true), taskOps.listSources)
    }

    @Test
    fun `move with a mismatched sessionId is a 409 and malformed bodies are 400`() {
        val taskOps = FakeTaskOps()
        runningTaskServer(taskOps)
        startInputSession()
        val (bad, badBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
            "{\"sessionId\":\"s-someone-else\",\"taskId\":703,\"direction\":\"main\"}",
        )
        assertEquals(409, bad)
        assertTrue(badBody.contains("session id mismatch"))
        // body 缺 sessionId → 409（严格口径，不再 missing-id 兼容）。
        val (absent, absentBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
            "{\"taskId\":703,\"direction\":\"main\"}",
        )
        assertEquals(409, absent)
        assertTrue(absentBody.contains("session id mismatch"))
        // 与 /input 同序：session 对账在 body 解析之前 —— 无 sessionId 的 "not json" 也是 409。
        val (notJson, notJsonBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
            "not json",
        )
        assertEquals(409, notJson)
        assertTrue(notJsonBody.contains("session id mismatch"))
        val prefix = "{\"sessionId\":\"" + lastSessionId + "\","
        for (body in listOf("{}", "{\"taskId\":\"703\",\"direction\":\"main\"}", "{\"taskId\":-1,\"direction\":\"main\"}", "{\"taskId\":703,\"direction\":\"up\"}")) {
            val payload = prefix + body.substring(1)
            val (status, _) = postRaw(
                TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
                payload,
            )
            assertEquals("bad body: " + body, 400, status)
        }
        assertEquals("400 路径只走校验", 0, taskOps.moves.size)
        val (ok, okBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
            prefix + "\"taskId\":703,\"direction\":\"main\"}",
        )
        assertEquals(200, ok)
        assertTrue(okBody.contains("\"ok\":true"))
        assertEquals(1, taskOps.moves.size)
        assertEquals(703 to "main", taskOps.moves.single())
    }

    @Test
    fun `move accepts the virtual direction and forwards it to the executor`() {
        val taskOps = FakeTaskOps()
        runningTaskServer(taskOps)
        startInputSession()
        val prefix = "{\"sessionId\":\"" + lastSessionId + "\","
        // virtual 方向合法：原样转发给执行口（认根/目标选择在执行口背后，端点只校验白名单）。
        val (ok, okBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
            prefix + "\"taskId\":703,\"direction\":\"virtual\"}",
        )
        assertEquals(200, ok)
        assertTrue(okBody.contains("\"ok\":true"))
        assertEquals(703 to "virtual", taskOps.moves.single())
    }

    @Test
    fun `an oversized declared body is drained and answered 400 without RST, a truncated one is 400`() {
        val taskOps = FakeTaskOps()
        runningTaskServer(taskOps)
        startInputSession()
        val prefix = "{\"sessionId\":\"" + lastSessionId + "\","
        // 声明 100KB、真的发 100KB：宿主有界排干后回 400，连接上仍能读到响应（不 RST）。
        val big = prefix + "\"taskId\":1,\"direction\":\"main\",\"pad\":\"" + "x".repeat(100 * 1024) + "\"}"
        val bigSocket = Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
        bigSocket.soTimeout = 8_000
        val bigPayload = big.toByteArray(Charsets.UTF_8)
        val bigHead = "POST " + TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1 HTTP/1.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + bigPayload.size + "\r\n" +
            "X-Pilot-Tab: panel-tab-1\r\nConnection: close\r\n\r\n"
        bigSocket.getOutputStream().write(bigHead.toByteArray(Charsets.ISO_8859_1))
        bigSocket.getOutputStream().write(bigPayload)
        bigSocket.getOutputStream().flush()
        val bigRaw = bigSocket.getInputStream().readBytes()
        val bigText = String(bigRaw, Charsets.ISO_8859_1)
        assertEquals(400, bigText.substringAfter(" ").substringBefore(" ").toInt())
        assertTrue(bigText.contains("too large"))
        assertTrue("超大 body 不执行", taskOps.moves.isEmpty())
        // 截断：声明 100 字节只发 10 字节 → 400 truncated。
        val crlf = "\r\n"
        val truncated = Socket(InetAddress.getByName("127.0.0.1"), endpoint.port)
        truncated.soTimeout = 5_000
        truncated.getOutputStream().use { output ->
            val head = "POST " + TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1 HTTP/1.1" + crlf +
                "Content-Type: application/json" + crlf +
                "Content-Length: 100" + crlf +
                "X-Pilot-Tab: panel-tab-1" + crlf + crlf
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.write(ByteArray(10))
            output.flush()
            truncated.shutdownOutput()
            val raw = truncated.getInputStream().readBytes()
            val text = String(raw, Charsets.ISO_8859_1)
            assertEquals(400, text.substringAfter(" ").substringBefore(" ").toInt())
            assertTrue(text.contains("truncated"))
        }
    }

    @Test
    fun `move failure surfaces as 503 with the reason and tasks without an executor are 404`() {
        val taskOps = FakeTaskOps()
        taskOps.moveOutcome = false to "panel task move: task 99 is not on display 45"
        runningTaskServer(taskOps)
        startInputSession()
        val (status, body) = postRaw(
            TrustedStreamControlEndpoint.PATH_MOVE + "?token=control-token-1",
            "{\"sessionId\":\"" + lastSessionId + "\",\"taskId\":99,\"direction\":\"main\"}",
        )
        assertEquals(503, status)
        assertTrue(body.contains("not on display 45"))
        // 执行口缺席：按 404 降级，不砸控制面。
        endpoint.stop()
        val log = FakeSessionLog()
        sessionLog = log
        endpoint = TrustedStreamControlEndpoint(FakeControl(), token = "control-token-1", sessionLog = log)
        assertTrue(endpoint.start())
        assertEquals(404, get(TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1").first)
    }

    @Test
    fun `tasks lists the trusted display and never leaks the control token`() {
        val taskOps = FakeTaskOps()
        runningTaskServer(taskOps)
        startInputSession()
        val (status, body) = get(
            TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1",
            session = lastSessionId,
        )
        assertEquals(200, status)
        assertTrue(body.contains("\"displayId\":45"))
        assertTrue(body.contains("\"taskId\":703"))
        assertFalse("控制 token 不回给任何调用方", body.contains("control-token-1"))
        assertFalse(body.contains("stream-token-tasks"))
    }

    @Test
    fun `release stops the stream first and only then releases the display`() {
        val taskOps = FakeTaskOps()
        val control = runningTaskServer(taskOps)
        startInputSession()
        assertEquals(0, control.stops.get())
        assertEquals(0, taskOps.releases)
        val (status, body) = postRaw(
            TrustedStreamControlEndpoint.PATH_RELEASE + "?token=control-token-1",
            "{\"sessionId\":\"" + lastSessionId + "\"}",
        )
        assertEquals(200, status)
        assertTrue(body.contains("\"released\":true"))
        // 顺序铁律：先停流（stop 已被请求过），再收屏（release 执行口被调）。
        assertEquals("先停流", 1, control.stops.get())
        assertEquals("再收屏", 1, taskOps.releases)
        // 会话已清：再打 /tasks 按无会话 409。
        assertEquals(409, get(TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1").first)
        // release 失败：503 + 原因，不谎报 ok。
        startInputSession()
        taskOps.releaseOutcome = false to "display release failed: remote said no"
        val (failedStatus, failedBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_RELEASE + "?token=control-token-1",
            "{\"sessionId\":\"" + lastSessionId + "\"}",
        )
        assertEquals(503, failedStatus)
        assertTrue(failedBody.contains("remote said no"))
    }

    @Test
    fun `release with a lost stream-stop reply keeps the session retryable and does not release the display`() {
        val taskOps = FakeTaskOps()
        val control = runningTaskServer(taskOps)
        startInputSession()
        control.stopAcknowledged = false
        val (status, body) = postRaw(
            TrustedStreamControlEndpoint.PATH_RELEASE + "?token=control-token-1",
            "{\"sessionId\":\"" + lastSessionId + "\"}",
        )
        assertEquals(503, status)
        assertTrue(body.contains("was not released, retry release"))
        // 回执丢失：屏未收（release 执行口零调用），账保留可重试 —— 不进"两端都 409"死局。
        assertEquals("屏未收", 0, taskOps.releases)
        assertEquals(1, control.stops.get())
        // 会话快照仍在：重试 release 走同一条路（这次回执到了），屏被收掉。
        control.stopAcknowledged = true
        taskOps.releaseOutcome = true to ""
        val (retryStatus, retryBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_RELEASE + "?token=control-token-1",
            "{\"sessionId\":\"" + lastSessionId + "\"}",
        )
        assertEquals(200, retryStatus)
        assertTrue(retryBody.contains("\"released\":true"))
        assertEquals("重试后收屏", 1, taskOps.releases)
    }

    // ── /rotate（先停流后 resize，不 release/不重建）──

    @Test
    fun `rotate without a session or with a wrong tab is refused with zero executor calls`() {
        val taskOps = FakeTaskOps()
        runningTaskServer(taskOps)
        // 无会话 409
        val (noSession, noSessionBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_ROTATE + "?token=control-token-1",
            "{\"sessionId\":\"s-1\"}",
        )
        assertEquals(409, noSession)
        assertTrue(noSessionBody.contains("no stream session"))
        // 有会话、错 tab 403
        startInputSession()
        val sid = lastSessionId
        val (wrongTab, _) = postRaw(
            TrustedStreamControlEndpoint.PATH_ROTATE + "?token=control-token-1",
            "{\"sessionId\":\"$sid\"}",
            tab = "other",
        )
        assertEquals(403, wrongTab)
        // sessionId 错配 409
        val (mismatch, mismatchBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_ROTATE + "?token=control-token-1",
            "{\"sessionId\":\"s-someone-else\"}",
        )
        assertEquals(409, mismatch)
        assertTrue(mismatchBody.contains("session id mismatch"))
        // GET 405
        assertEquals(405, get(TrustedStreamControlEndpoint.PATH_ROTATE + "?token=control-token-1").first)
        // body 畸形 → 409（与 /move 同序：session 对账在 body 解析之前）
        val (malformed, malformedBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_ROTATE + "?token=control-token-1",
            "not json",
        )
        assertEquals(409, malformed)
        assertTrue(malformedBody.contains("session id mismatch"))
        assertTrue("全部拒绝路径零执行", taskOps.rotates == 0)
    }

    @Test
    fun `rotate stops the stream first and only then resizes without any release`() {
        val taskOps = FakeTaskOps()
        val control = runningTaskServer(taskOps)
        startInputSession()
        assertEquals(0, control.stops.get())
        assertEquals(0, taskOps.rotates)
        assertEquals(0, taskOps.releases)
        val (status, body) = postRaw(
            TrustedStreamControlEndpoint.PATH_ROTATE + "?token=control-token-1",
            "{\"sessionId\":\"" + lastSessionId + "\"}",
        )
        assertEquals(200, status)
        assertTrue(body.contains("\"rotated\":true"))
        assertTrue(body.contains("\"dimensions\""))
        // 顺序铁律：先停流（stop 已请求），再旋转（rotate 执行口被调）。
        assertEquals("先停流", 1, control.stops.get())
        assertEquals("再旋转", 1, taskOps.rotates)
        // 不收屏：release 执行口零调用（旋转不销毁任务）。
        assertEquals("不 release", 0, taskOps.releases)
        // 会话已清：面板据此复位（再打 /tasks 按无会话 409）。
        assertEquals(409, get(
            TrustedStreamControlEndpoint.PATH_TASKS + "?token=control-token-1",
            session = lastSessionId,
        ).first)
        // 旋转失败：503 + 原因，不谎报 ok。
        startInputSession()
        taskOps.rotateOutcome = false to "resize failed: UnsupportedOperationException: not supported"
        val (failedStatus, failedBody) = postRaw(
            TrustedStreamControlEndpoint.PATH_ROTATE + "?token=control-token-1",
            "{\"sessionId\":\"" + lastSessionId + "\"}",
        )
        assertEquals(503, failedStatus)
        assertTrue(failedBody.contains("resize failed"))
    }

    @Test
    fun `rotate with a lost stream-stop reply does not touch the display`() {
        val taskOps = FakeTaskOps()
        val control = runningTaskServer(taskOps)
        startInputSession()
        control.stopAcknowledged = false
        val (status, body) = postRaw(
            TrustedStreamControlEndpoint.PATH_ROTATE + "?token=control-token-1",
            "{\"sessionId\":\"" + lastSessionId + "\"}",
        )
        assertEquals(503, status)
        assertTrue(body.contains("was not rotated, retry rotate"))
        // 屏不动：rotate/release 执行口都零调用。
        assertEquals("屏未旋转", 0, taskOps.rotates)
        assertEquals("屏未收", 0, taskOps.releases)
    }
}

/** 端点测试用的最小流句柄替身（真 LoopbackStreamServer 不在 JVM 上起会话）。 */
private class FakeStreamHandle(
    override val port: Int,
    override val token: String,
    override val metadata: String,
) : StreamHandle {
    override val endpoint: String = "http://127.0.0.1:$port/stream"
}
