package interlock.relay.core.exec.stream

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时流的宿主**控制端点**：仅 loopback、随机端口、随机控制 token，
 * 供 DSH 的 Node 半（读 rendezvous 文件拿到端口与 token）或本机调用：
 *
 * - `GET /status?token=` → `{"running":…,"connected":…,"stopped":…,"streamPort":…}`
 * - `GET /start?token=`  → 调 [TrustedStreamController.start]，回
 * `{"ok":true,"stream":{"port":…,"token":…,"endpoint":…,"metadata":…}}`（失败 503 + 原因）
 * - `GET /stop?token=`   → 调 [TrustedStreamController.stop]
 * - `GET /tasks?token=`  → 列当前虚拟屏上的任务（409 = 无会话）
 * - `POST /input?token=` → 面板输入（tap/swipe/back/home）
 * - `POST /move?token=`  → 任务搬迁到主屏/虚拟屏（409/503 + 原因）
 * - `POST /release?token=` → 先停流再释放虚拟屏（屏上应用会被关闭）
 *
 * 安全边界（与 [LoopbackStreamServer] 同一组铁律）：
 * 1. 只绑 127.0.0.1（写死）；2. 控制 token 只在内存，本类零日志；3. 无/错 token 一律
 * 403（常数时间比较）；4. **CORS 只对 loopback 的 http(s) Origin 回显** —— DSH 的
 * Web UI（浏览器半）从它自己的 origin 用 fetch 调本端点时需要它；非 loopback origin
 * 一律不给 ACAO，浏览器侧读不到任何响应体。控制 token 与流 token（start 返回的那枚
 * 一次性 token）分离：控制 token 管"谁能开关"，流 token 管"谁能读这一段字节"。
 *
 * 线程模型：accept 线程内联处理。`/start` 可能阻塞数秒（设备事务），期间控制端口
 * 不接新连接 —— 单用户控制面，这是可接受的取舍（流字节走 [LoopbackStreamServer]
 * 自己的端口，不占这里）。
 */
class TrustedStreamControlEndpoint(
    private val stream: StreamControl,
    val token: String = LoopbackStreamServer.newToken(),
    port: Int = 0,
    /**
     * 会话诊断读数的写入口（宿主装配方注入写运行日志的那一支）。
     * 控制端点自己只管产出读数，不碰任何具体的日志实现。
     */
    private val sessionLog: StreamSessionLog? = null,
    /**
     * 面板输入的执行口（宿主装配方注入）：端点只负责鉴权/校验/限速，真正落到
     * 可信屏的注入在 Shizuku 后端（`input -d <displayId> …`，不走 gate/档位/审批）。
     * **挂起**语义：实现把阻塞事务放到专用池；端点用 withTimeoutOrNull 包它，
     * 到点只放弃**等待**——事务可能仍在跑，按"结局未知"回话。
     */
    private val panelInput: PanelInputExecutor? = null,
    /** /input 的等待上限（backstop）：默认 6s > Shizuku 侧 5s 预算；测试注入短值。 */
    private val inputTimeoutMs: Long = 6_000L,
    /**
     * 面板任务面（移到主屏/移回虚拟屏/释放虚拟屏 + 任务列表）的执行口（宿主装配方
     * 注入，落 ShizukuBackend）：端点只管鉴权/校验/排干 body，设备事务在这里背后。
     * **挂起**语义与 [PanelInputExecutor] 同款；null 时三条路由按 404 答。
     */
    private val panelTasks: PanelTaskOps? = null,
    /** /tasks /move /release 的等待上限（backstop）：默认 12s；测试注入短值。 */
    private val taskTimeoutMs: Long = 12_000L,
) {

    /** 面板输入的执行面（装配方接 [interlock.relay.core.exec.shizuku.ShizukuBackend.panelInput]）。 */
    fun interface PanelInputExecutor {
        /** 回 (ok, reason)；reason 只在 !ok 时有人读。阻塞实现自己放到专用池，执行口只挂起等待。 */
        suspend fun execute(command: PanelInputPolicy.Command): Pair<Boolean, String>
    }

    /**
     * 面板任务面的执行面（装配方接 ShizukuBackend.panelTaskList/panelTaskMove 与
     * trustedStream 的停流+release 组合）。回 (ok, reason)；reason 只在 !ok 时有人读。
     */
    interface PanelTaskOps {
        /**
         * 列任务：[sourceIsVirtual]=true 列虚拟屏（预览面板的"虚拟屏任务"列表），
         * false 列主屏（搬回虚拟屏前要在主屏上认根）。回包正文是 `{"displayId":…,"tasks":[…]}`。
         */
        suspend fun list(sourceIsVirtual: Boolean): Pair<Boolean, String>

        /**
         * 搬一个任务：direction=main → 虚拟屏 → 主屏；virtual → 主屏 → 虚拟屏。
         * 回 (ok, reason)；reason 只在 !ok 时有人读。
         */
        suspend fun move(taskId: Int, direction: String): Pair<Boolean, String>

        /** 释放虚拟屏（停流由端点先做；这里只收屏）。回 (ok, reason)。 */
        suspend fun release(): Pair<Boolean, String>

        /**
         * 旋转虚拟屏（宽高交换走 resize，屏上任务保留）。回
         * (ok, payload)：ok=true 时 payload 是 `{"width":…,"height":…}`。
         */
        suspend fun rotate(): Pair<Boolean, String>
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** 连接服务池：accept 只收连接，/input 的慢执行不占 accept 线程。 */
    @Volatile
    private var servePool: java.util.concurrent.ExecutorService? = null

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var boundPort = -1

    /** 实际绑定的地址与 loopback 判定（`ServerSocket.inetAddress` 原值，不回读常量）。 */
    @Volatile
    var boundAddress: String? = null
        private set

    @Volatile
    var boundLoopback: Boolean? = null
        private set

    /** 构造时请求的端口（0 = 由系统挑）；成员里的 `port` 属性会遮住同名参数，绑定时用它。 */
    private val requestedPort: Int = port

    val port: Int get() = boundPort

    /** 最近一次成功 start 的会话快照（含一次性流 token 与 sessionId）：幂等 start 原样回给调用方。 */
    @Volatile
    private var lastStream: LastStream? = null

    /** 最近一次收场的会话 id：/stop 带它且无活会话时按幂等空过（200 stopped:false）。 */
    @Volatile
    private var lastEndedSessionId: String? = null

    /**
     * release 停流回执丢失时记下的会话 id（屏未收、账保留）。重试 release
     * 认它放行 —— 控制器 stop 幂等，第二发会把屏收干净，不进「两端都 409」死局。
     */
    @Volatile
    private var releasePendingSessionId: String? = null

    /**
     * rotate 停流回执丢失时记下的会话 id（屏未旋转、账保留）。
     * 重试 rotate 认它放行 —— 与 release 的 [releasePendingSessionId] 同一支旁路，
     * 不进「屏还在、两端都 409」死局。
     */
    @Volatile
    private var rotatePendingSessionId: String? = null

    @Volatile
    private var lastStartReason: String? = null

    /** 面板输入的每会话限速器：新会话开成时清账，从零计数。 */
    private val inputLimiter = PanelInputPolicy.RateLimiter()

    private val sessionSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 进程内一次性随机段：跨宿主/RelayContainer 重启后 `s-1` 这类序号会重复，靠它保证全局唯一。 */
    private val bootNonce = java.util.UUID.randomUUID().toString().substring(0, 8)

    private fun nextSessionId(): String = "s-" + bootNonce + "-" + sessionSeq.incrementAndGet()

    private class LastStream(
        val sessionId: String,
        val port: Int,
        val token: String,
        val endpoint: String,
        val metadataJson: String,
        /** D：浏览器侧面板凭据（Node 生成、只在 Node 内存与浏览器），/input 与幂等 /start 复查。 */
        val tabToken: String,
    )

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val bound = runCatching {
            ServerSocket(requestedPort, ACCEPT_BACKLOG, InetAddress.getByName(LoopbackStreamServer.LOOPBACK))
        }.getOrElse {
            running.set(false)
            return false
        }
        serverSocket = bound
        boundPort = bound.localPort
        boundAddress = bound.inetAddress?.hostAddress
        boundLoopback = bound.inetAddress?.isLoopbackAddress
        servePool = java.util.concurrent.Executors.newFixedThreadPool(SERVE_POOL_SIZE) { runnable ->
            Thread(runnable, "relay-stream-serve").apply { isDaemon = true }
        }
        acceptThread = Thread({ acceptLoop(bound) }, "relay-stream-control").apply {
            isDaemon = true
            start()
        }
        return true
    }

    /** 停端点：关服务 socket 与服务池。已开的流会话归 [TrustedStreamController.stop]，这里不代管。 */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        servePool?.shutdownNow()  // 关面板后排队的输入不再落屏（N3D 返工2）
        servePool = null
        val accepting = acceptThread
        acceptThread = null
        if (accepting != null && accepting !== Thread.currentThread()) {
            runCatching { accepting.join(STOP_JOIN_MS.toLong()) }
        }
        boundPort = -1
        boundAddress = null
        boundLoopback = null
    }

    /**
     * accept 线程只 accept：每条连接丢进固定小池处理。/input 的执行不再占住唯一
     * 的 accept 线程——/status、/stop 在一次慢输入进行中也能立刻进。
     */
    private fun acceptLoop(bound: ServerSocket) {
        while (running.get()) {
            val socket = runCatching { bound.accept() }.getOrElse { break }
            if (!running.get()) {
                runCatching { socket.close() }
                break
            }
            val pool = servePool
            if (pool == null) {
                runCatching { socket.close() }
                break
            }
            pool.execute {
                try {
                    // 每次阻塞 read 1 秒：控制请求都极小，慢客户端占住池内一个工人的代价有界。
                    socket.soTimeout = READ_DEADLINE_MS
                    serve(socket)
                } catch (_: Exception) {
                    // 头读不到、对端先走、写响应失败：关连接即全部善后。
                } finally {
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        if (!running.get()) {  // 关面板后排队的连接直接关（N3D 返工2）
            return
        }
        val head = readHead(socket.getInputStream()) ?: return
        if (head.size >= MAX_HEAD_BYTES) {
            write(socket, head, STATUS_BAD_REQUEST, "{\"error\":\"bad request\"}")
            return
        }
        val requestLine = String(head, Charsets.ISO_8859_1).lineSequence().firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 2) {
            write(socket, head, STATUS_BAD_REQUEST, "{\"error\":\"bad request\"}")
            return
        }
        if (parts[0] == "POST" && (parts[1].substringBefore('?') == PATH_INPUT ||
                parts[1].substringBefore('?') == PATH_MOVE ||
                parts[1].substringBefore('?') == PATH_RELEASE ||
                parts[1].substringBefore('?') == PATH_ROTATE)
        ) {
            // /input 与 /move /release /rotate 是仅有的 POST：控制 token 照查
            // （query 或头）；校验失败也要先把 body 排干再关，避免 RST 让 Node 半看到
            // 网络错误而不是状态码。
            val query = parts[1].substringAfter('?', "")
            if (!tokenGiven(query, head)) {
                runCatching { readBody(socket, head) }
                write(socket, head, STATUS_FORBIDDEN, "{\"error\":\"forbidden\"}")
                return
            }
            when (parts[1].substringBefore('?')) {
                PATH_INPUT -> handleInput(socket, head)
                PATH_MOVE -> handleMove(socket, head)
                PATH_ROTATE -> handleRotate(socket, head)
                else -> handleRelease(socket, head)
            }
            return
        }
        if (parts[0] != "GET") {
            write(socket, head, STATUS_METHOD_NOT_ALLOWED, "{\"error\":\"GET only\"}")
            return
        }
        val path = parts[1].substringBefore('?')
        val query = parts[1].substringAfter('?', "")
        if (!tokenGiven(query, head)) {
            write(socket, head, STATUS_FORBIDDEN, "{\"error\":\"forbidden\"}")
            return
        }
        when (path) {
            PATH_STATUS -> write(socket, head, STATUS_OK, statusBody())
            PATH_START -> handleStart(socket, head)
            PATH_STOP -> handleStop(socket, head, query)
            PATH_TASKS -> handleTasks(socket, head)
            // 405 早退也要排干 body：POST 打到 GET-only 路由（/tasks）、
            // GET 打到 POST-only 路由（/move /release /rotate）时可能带着未消费的
            // body —— 早退不 RST 让调用方读到状态码。
            PATH_INPUT -> {
                runCatching { readBody(socket, head) }
                write(socket, head, STATUS_METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}")
            }
            PATH_MOVE, PATH_RELEASE, PATH_ROTATE -> {
                runCatching { readBody(socket, head) }
                write(socket, head, STATUS_METHOD_NOT_ALLOWED, "{\"error\":\"POST only\"}")
            }
            else -> write(socket, head, STATUS_NOT_FOUND, "{\"error\":\"not found\"}")
        }
    }

    private fun statusBody(): String {
        // running 问 controller（EOF/宿主 watchdog 收场后立即为 false），不问本端快照。
        val running = stream.running
        return "{\"running\":$running,\"stopped\":${!running}," +
            "\"sessionId\":" + jsonString(lastStream?.sessionId) + "," +
            "\"lastStartReason\":" + jsonString(lastStartReason) + "}"
    }

    /**
     * 面板输入：`POST /input?token=`，body 为归一化坐标的 JSON（Node 半代理面板）。
     *
     * 判据链：body 先读（排干，避免早退 RST）→ 控制 token（serve 已查）
     * → 浏览器侧 tabToken（头 `x-pilot-tab`，常量时间比对；缺/错 403——它是**会话归属**
     * 凭据（多 tab 不串），不是沙盒隔离边界；数据面由  守卫把守，受保护前台无会话
     * 时这里自然 409，会话中途切入则看门狗 2 秒内停流）→
     * 会话存活 + body 的 sessionId 与本会话一致（否则 409，/stop 同口径）→ body 可解析/
     * 动作白名单/坐标 0..1/duration 50..1000（否则 400）→ 每会话限速 20/s（429）→
     * 映射到**虚拟屏源尺寸**（会话 metadata 的 sourceWidth/sourceHeight，start 时由
     * 服务端 `TrustedDisplay.size()` 写入）并夹到 0..w-1/0..h-1 → 交给 [panelInput] 执行。
     * 执行超时按**结局未知**回 504（事务可能已落，与 /stop 的 outcome unknown 同口径），
     * 不说"失败"。
     */
    private fun handleInput(socket: Socket, head: ByteArray) {
        val executor = panelInput
        if (executor == null) {
            runCatching { readBody(socket, head) }
            write(socket, head, STATUS_NOT_FOUND, "{\"error\":\"not found\"}")
            return
        }
        // 先把 body 读掉（排干）：后面的 403/409 早退不能再带着没消费的 body 关连接。
        val body = when (val read = readBody(socket, head)) {
            is BodyRead.TooLarge -> {
                write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"input body too large (max 4KB)\"}")
                return
            }
            is BodyRead.Truncated -> {
                write(socket, head, STATUS_BAD_REQUEST,
                    "{\"ok\":false,\"reason\":\"input body truncated; the connection closed early\"}")
                return
            }
            is BodyRead.Body -> read.text
        }
        val current = lastStream
        if (current == null || !stream.running) {
            write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"no stream session; POST /start first\"}")
            return
        }
        // 会话归属：浏览器侧 tabToken（Node 转发）。缺/错一律 403、同一句回复，
        // 不给枚举空间；常量时间比较。它不是沙盒隔离边界（由 stream.start 的
        // 受保护名单守卫把守）。
        val presentedTab = headerValue(head, TAB_HEADER)
        if (presentedTab.isNullOrEmpty() || !constantTimeEquals(presentedTab, current.tabToken)) {
            write(socket, head, STATUS_FORBIDDEN, "{\"ok\":false,\"reason\":\"forbidden\"}")
            return
        }
        val request = PanelInputPolicy.parse(body)
        if (request == null) {
            write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"malformed input body: no action\"}")
            return
        }
        // 会话对账：body 必须带 Node 抓住的 sessionId，与本会话不一致按 /stop 同口径 409。
        if (request.sessionId != current.sessionId) {
            write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"session id mismatch\"}")
            return
        }
        val invalid = PanelInputPolicy.validate(request)
        if (invalid != null) {
            write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"" + jsonString(invalid) + "\"}")
            return
        }
        if (!inputLimiter.tryAcquire(System.currentTimeMillis())) {
            write(socket, head, STATUS_TOO_MANY_REQUESTS, "{\"ok\":false,\"reason\":\"panel input rate limit exceeded " +
                "(max " + PanelInputPolicy.RATE_LIMIT_PER_SECOND + "/s per session)\"}")
            return
        }
        val dims = StreamSession.dimensionsFrom(current.metadataJson)
        val command = PanelInputPolicy.map(request, dims.sourceW ?: 0, dims.sourceH ?: 0)
        if (command == null) {
            write(socket, head, STATUS_BAD_REQUEST,
                "{\"ok\":false,\"reason\":\"trusted display size unknown; cannot map normalized input\"}")
            return
        }
        // 执行口是挂起的；这里的 runBlocking 是 accept/serve 池工人自己的，withTimeoutOrNull
        // 到点只放弃**等待**——事务可能仍在跑，按"结局未知"回话，不说"失败"。
        val outcome = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(inputTimeoutMs) { executor.execute(command) }
        }
        val result = when {
            outcome == null -> "unknown" to
                ("panel input outcome unknown: execution did not reply within " + inputTimeoutMs + "ms; " +
                    "the " + command.action + " may still have landed")
            outcome.first -> "ok" to ""
            // Shizuku 侧 5s 预算先放弃本地等待（远端可能已提交）：与端点 6s 超时同口径。
            outcome.second.startsWith("outcome unknown") -> "unknown" to outcome.second
            else -> "failed" to outcome.second
        }
        // 输入留痕：sessionId + 动作@源像素 + 成败/outcome，无 token。
        val coords = when (command.action) {
            "tap" -> "(" + command.x + "," + command.y + ")"
            "swipe" -> "(" + command.x1 + "," + command.y1 + ")->(" + command.x2 + "," + command.y2 + ")"
            else -> ""
        }
        logSession(
            StreamSessionPhase.INPUT,
            result.first,
            command.action + coords + if (result.second.isEmpty()) "" else ": " + result.second,
            dims = StreamSession.dimensionsFrom(current.metadataJson),
        )
        when (result.first) {
            "ok" -> write(socket, head, STATUS_OK, "{\"ok\":true}")
            "unknown" -> write(socket, head, STATUS_GATEWAY_TIMEOUT,
                "{\"ok\":false,\"outcome\":\"unknown\",\"reason\":\"" + jsonString(result.second) + "\"}")
            else -> write(socket, head, STATUS_SERVICE_UNAVAILABLE,
                "{\"ok\":false,\"reason\":\"" + jsonString(result.second) + "\"}")
        }
    }

    /** body 读取三态：过大 / 对端截断（区别于过大）/ 正常（含空 body）。 */
    private sealed class BodyRead {
        object TooLarge : BodyRead()
        object Truncated : BodyRead()
        data class Body(val text: String) : BodyRead()
    }

    /**
     * 头之后的请求体（Content-Length 定长）。无 body 回空串。
     *
     * 声明长度超上限（TooLarge）时**有界排干**再回话：最多再读
     * [MAX_DRAIN_BYTES]，读不完就放弃——早退带着没消费的 body 关连接会 RST，
     * 让 Node 半看到网络错误而不是状态码；排干有界是为了不让恶意的大
     * Content-Length 拖住池工人。
     */
    private fun readBody(socket: Socket, head: ByteArray): BodyRead {
        val headers = String(head, Charsets.ISO_8859_1)
        val contentLength = headers.lineSequence()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (contentLength <= 0) return BodyRead.Body("")
        val input = socket.getInputStream()
        if (contentLength > MAX_BODY_BYTES) {
            val skipped = runCatching { input.skip(contentLength.toLong()) }.getOrDefault(0L)
            if (skipped < contentLength) {
                // skip 对 socket 只跳过已就绪的部分：剩下的有界读掉（对端还在发的话）。
                val leftover = ByteArray(MAX_DRAIN_BYTES)
                var drained = 0
                while (drained < MAX_DRAIN_BYTES) {
                    val read = runCatching { input.read(leftover, drained, MAX_DRAIN_BYTES - drained) }
                        .getOrDefault(-1)
                    if (read < 0) break
                    drained += read
                }
            }
            return BodyRead.TooLarge
        }
        val buffer = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val chunk = input.read(buffer, read, contentLength - read)
            if (chunk < 0) return BodyRead.Truncated
            read += chunk
        }
        return BodyRead.Body(String(buffer, Charsets.UTF_8))
    }

    private fun handleStart(socket: Socket, head: ByteArray) {
        // /start 要求浏览器侧 tabToken（头 x-pilot-tab，Node 半转发）：**会话归属**
        // 凭据，多 tab 不串——不是沙盒隔离边界（数据面由  守卫把守，D）。
        // 缺/错一律 403 不泄露原因。
        val tabToken = headerValue(head, TAB_HEADER)
        if (tabToken.isNullOrEmpty()) {
            write(socket, head, STATUS_FORBIDDEN, "{\"ok\":false,\"reason\":\"forbidden\"}")
            return
        }
        // 幂等：会话还活着就回**完整** bundle（port/token/endpoint/metadata 一个不少），
        // Node 半拿到它才能接上流 —— 只回一个端口等于让它对着已消费的 token 空转。
        // 流 token 是一次性的：若已被消费，取流会 409，Node 半按 stop → start 重开。
        if (stream.running && lastStream != null) {
            val last = lastStream!!
            if (!constantTimeEquals(tabToken, last.tabToken)) {
                // 别的 tab 来重放：不是同一面板的幂等，也不是新会话 —— 409。
                write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"session belongs to another panel\"}")
                return
            }
            write(socket, head, STATUS_OK, "{\"ok\":true,\"alreadyRunning\":true,\"stream\":{" +
                "\"sessionId\":" + jsonString(last.sessionId) +
                ",\"port\":" + last.port + ",\"token\":" + jsonString(last.token) +
                ",\"endpoint\":" + jsonString(last.endpoint) + ",\"metadata\":" + last.metadataJson + "}}")
            // 幂等重放也带一条维度读数：面板重连时日志里能对上同一场会话的 source/encode。
            logSession(StreamSessionPhase.START, "ok", dims = StreamSession.dimensionsFrom(last.metadataJson))
            return
        }
        lastStream = null
        val started: StreamControl.StartOutcome = try {
            kotlinx.coroutines.runBlocking { stream.start(DEFAULT_WAIT_MS) }
        } catch (broken: Throwable) {
            lastStartReason = broken.javaClass.simpleName
            write(socket, head, STATUS_SERVICE_UNAVAILABLE, "{\"ok\":false,\"reason\":\"" + broken.javaClass.simpleName + "\"}")
            // 控制面这一侧也留一条：设备侧抛异常时控制器的 start 回调没跑过，
            // 这里补上「对调用方回了什么」。phase=error —— 与设备侧「拒绝开流」
            // （phase=start）分开说：一个是抛了，一个是明确拒了。
            logSession(StreamSessionPhase.ERROR, "failed", broken.javaClass.simpleName)
            return
        }
        when (started) {
            is StreamControl.StartOutcome.Running -> {
                lastStartReason = null
                val bundle = LastStream(
                    nextSessionId(),
                    started.stream.port, started.stream.token,
                    started.stream.endpoint, started.stream.metadata,
                    tabToken,
                )
                lastStream = bundle
                // 新会话从零计数：限速是「每会话」的，上一届的账不作数。
                inputLimiter.reset()
                val body = "{\"ok\":true,\"stream\":{\"sessionId\":" + jsonString(bundle.sessionId) +
                    ",\"port\":" + bundle.port +
                    ",\"token\":" + jsonString(bundle.token) +
                    ",\"endpoint\":" + jsonString(bundle.endpoint) +
                    ",\"metadata\":" + bundle.metadataJson + "}}"
                write(socket, head, STATUS_OK, body)
                // 控制面这一侧也留一条成功读数：metadata 里的 source/encode 维度
                // 进日志（1280 回退 720 时两组尺寸可不同，事后排查要能分开看）。
                logSession(StreamSessionPhase.START, "ok", dims = StreamSession.dimensionsFrom(bundle.metadataJson))
            }
            is StreamControl.StartOutcome.Failed -> {
                lastStartReason = started.reason
                // 数据面守卫：受保护应用前台时明确回 403 + code=protected_app，
                // 不建屏、不发 stream token；面板按 code 停止自动开流。
                if (started.code == CODE_PROTECTED_APP) {
                    write(socket, head, STATUS_FORBIDDEN,
                        "{\"ok\":false,\"code\":\"protected_app\",\"reason\":" + jsonString(started.reason) + "}")
                } else {
                    write(socket, head, STATUS_SERVICE_UNAVAILABLE, "{\"ok\":false,\"reason\":" + jsonString(started.reason) + "}")
                }
                logSession(StreamSessionPhase.START, "failed", started.reason)
            }
        }
    }

    private fun handleStop(socket: Socket, head: ByteArray, query: String) {
        // /stop 的三态（与会话归属对齐）：
        // - 带当前 sessionId（或不带 id）→ 停当前会话，按回执回 200 stopped:true / 503；
        // - 无活会话且带的是刚结束那届的 id（或未带 id）→ 200 stopped:false（幂等空过）；
        // - 带别人的/未知 sessionId → 409，绝不停掉不属于它的会话。
        val requested = sessionIdParam(query, head)
        val current = lastStream?.sessionId
        if (current != null) {
            if (requested != null && requested != current) {
                write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"session id mismatch\"}")
                return
            }
            // matching-ended：bundle 还在但会话已自然结束（EOF/宿主 watchdog 收场
            // 后 controller.running=false）→ 200 stopped:false，不重打 stop。
            if (!stream.running) {
                lastEndedSessionId = current
                lastStream = null
                lastStartReason = null
                write(socket, head, STATUS_OK, "{\"ok\":true,\"stopped\":false,\"sessionId\":" + jsonString(current) + "}")
                return
            }
            val acknowledged = try {
                kotlinx.coroutines.runBlocking { stream.stop() }
            } catch (broken: Throwable) {
                write(socket, head, STATUS_SERVICE_UNAVAILABLE, "{\"ok\":false,\"reason\":\"stop failed: " + broken.javaClass.simpleName + "\"}")
                return
            }
            lastEndedSessionId = current
            lastStream = null
            lastStartReason = null
            if (acknowledged) {
                write(socket, head, STATUS_OK, "{\"ok\":true,\"stopped\":true,\"sessionId\":" + jsonString(current) + "}")
                logSession(StreamSessionPhase.STOP, "confirmed")
            } else {
                // 回执丢失不吞：会话在拆除路上，但调用方要知道这次 stop 的结局未知。
                write(socket, head, STATUS_SERVICE_UNAVAILABLE,
                    "{\"ok\":false,\"reason\":\"stop reply lost; the session is tearing down\"}")
                logSession(StreamSessionPhase.STOP, "unknown", "stop reply lost; the session is tearing down")
            }
            return
        }
        // 无活会话：能对上"刚结束的那一届"（或未带 id）→ 幂等空过；其余按错配 409。
        val endedMatch = requested == null || requested == lastEndedSessionId
        if (endedMatch) {
            write(socket, head, STATUS_OK, "{\"ok\":true,\"stopped\":false,\"sessionId\":" + jsonString(lastEndedSessionId) + "}")
        } else {
            write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"session id mismatch\"}")
        }
    }

    // ──  面板任务面（/tasks、/move、/release）──

    /**
     * 会话与归属凭据的对账（三条任务路由共用）：
     * 执行口缺席 → 404（面板侧按"路由不可用"降级，不砸控制面）；
     * 会话不在跑 → 409（/input 同口径）；
     * tabToken（头 `x-pilot-tab`）缺/错 → 403，同一句回复不给枚举空间；
     * sessionId（body 或头 `x-pilot-session`）缺/错 → 409（/stop 同口径——三条任务
     * 路由与 /input 一样严格对账，Node 半三条都转发）。
     * 回 null = 通过；否则已是排干 body 后写好的响应。
     */
    private fun taskSessionOrRespond(
        socket: Socket,
        head: ByteArray,
        sessionId: String?,
    ): LastStream? {
        val executor = panelTasks
        if (executor == null) {
            write(socket, head, STATUS_NOT_FOUND, "{\"error\":\"not found\"}")
            return null
        }
        val current = lastStream
        if (current == null || !stream.running) {
            write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"no stream session; POST /start first\"}")
            return null
        }
        val presentedTab = headerValue(head, TAB_HEADER)
        if (presentedTab.isNullOrEmpty() || !constantTimeEquals(presentedTab, current.tabToken)) {
            write(socket, head, STATUS_FORBIDDEN, "{\"ok\":false,\"reason\":\"forbidden\"}")
            return null
        }
        // sessionId 严格对账（/input 口径）：缺（null/空）与错一律 409。GET /tasks 的
        // body 是空的，sessionId 从 `x-pilot-session` 头来（Node 半三条都转发）。
        if (sessionId.isNullOrEmpty() || sessionId != current.sessionId) {
            write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"session id mismatch\"}")
            return null
        }
        return current
    }

    /** body 里按白名单取整数：缺/非数回 null（调用方回 400）。 */
    private fun bodyInt(body: String, key: String): Int? {
        val value = runCatching { org.json.JSONObject(body).opt(key) }.getOrNull() ?: return null
        return when (value) {
            is Int -> value
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
            is Number -> value.toInt()
            else -> null
        }
    }

    /** body 里按白名单取字符串：缺/非串回 null。 */
    private fun bodyString(body: String, key: String): String? {
        val value = runCatching { org.json.JSONObject(body).opt(key) }.getOrNull() ?: return null
        return (value as? String)?.takeIf { it.isNotEmpty() }
    }

    /** body 的 sessionId，缺了再看 `x-pilot-session` 头（GET /tasks 走头）。 */
    private fun sessionIdFromBodyOrHeader(body: String, head: ByteArray): String? {
        val fromBody = bodyString(body, "sessionId")
        return fromBody ?: headerValue(head, "x-pilot-session").takeIf { it.isNotEmpty() }
    }

    /** 执行口的常见失败 → 响应（409/503 由 reason 的 outcome unknown 前缀分流）。 */
    private fun writePanelTaskOutcome(socket: Socket, head: ByteArray, ok: Boolean, reason: String) {
        when {
            ok -> write(socket, head, STATUS_OK, "{\"ok\":true}")
            reason.startsWith("outcome unknown") -> write(
                socket,
                head,
                STATUS_GATEWAY_TIMEOUT,
                "{\"ok\":false,\"outcome\":\"unknown\",\"reason\":" + jsonString(reason) + "}",
            )
            else -> write(
                socket,
                head,
                STATUS_SERVICE_UNAVAILABLE,
                "{\"ok\":false,\"reason\":" + jsonString(reason) + "}",
            )
        }
    }

    /**
     * `GET /tasks?token=` → 只列当前虚拟屏上的任务（面板的"虚拟屏任务"列表）。
     * 判据链：控制 token（serve 已查）→ 执行口在（否则 404）→ 会话存活 → tabToken 对账
     * → sessionId 对账（`x-pilot-session` 头，/input 严格口径）。
     * 回包只透传服务端任务 JSON（displayId/tasks，不携带任何 token）。
     */
    private fun handleTasks(socket: Socket, head: ByteArray) {
        val executor = panelTasks
        if (executor == null) {
            write(socket, head, STATUS_NOT_FOUND, "{\"error\":\"not found\"}")
            return
        }
        val current = lastStream
        if (current == null || !stream.running) {
            write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"no stream session; POST /start first\"}")
            return
        }
        val presentedTab = headerValue(head, TAB_HEADER)
        if (presentedTab.isNullOrEmpty() || !constantTimeEquals(presentedTab, current.tabToken)) {
            write(socket, head, STATUS_FORBIDDEN, "{\"ok\":false,\"reason\":\"forbidden\"}")
            return
        }
        val sessionId = headerValue(head, "x-pilot-session").takeIf { it.isNotEmpty() }
        if (sessionId.isNullOrEmpty() || sessionId != current.sessionId) {
            write(socket, head, STATUS_CONFLICT, "{\"ok\":false,\"reason\":\"session id mismatch\"}")
            return
        }
        val outcome = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(taskTimeoutMs) { executor.list(sourceIsVirtual = true) }
        }
        if (outcome == null) {
            write(socket, head, STATUS_GATEWAY_TIMEOUT,
                "{\"ok\":false,\"outcome\":\"unknown\",\"reason\":\"panel tasks outcome unknown: " +
                    "the listing did not reply within " + taskTimeoutMs + "ms\"}")
            return
        }
        if (!outcome.first) {
            writePanelTaskOutcome(socket, head, false, outcome.second)
            return
        }
        write(socket, head, STATUS_OK, outcome.second)
    }

    /**
     * `POST /move?token=` body `{"sessionId":"…","taskId":703,"direction":"main"|"virtual"}`。
     * 判据链：body 排干（三态）→ 执行口在/会话存活（404/409）→ tabToken（403）→ sessionId
     * 对账（409）→ body 可解析 + taskId 是整数 + direction ∈ {main, virtual}（400）→
     * 交给执行口；source/target display 由执行口按方向落（main：虚拟屏→0；virtual：0→虚拟屏）。
     */
    private fun handleMove(socket: Socket, head: ByteArray) {
        val body = when (val read = readBody(socket, head)) {
            is BodyRead.TooLarge -> {
                write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"move body too large (max 4KB)\"}")
                return
            }
            is BodyRead.Truncated -> {
                write(socket, head, STATUS_BAD_REQUEST,
                    "{\"ok\":false,\"reason\":\"move body truncated; the connection closed early\"}")
                return
            }
            is BodyRead.Body -> read.text
        }
        val parsed = runCatching { org.json.JSONObject(body) }.getOrNull()
        val sessionId = parsed?.let { sessionIdFromBodyOrHeader(body, head) }
        val current = taskSessionOrRespond(socket, head, sessionId) ?: return
        if (parsed == null) {
            write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"malformed move body: no action\"}")
            return
        }
        val taskId = bodyInt(body, "taskId")
        val direction = bodyString(body, "direction")
        if (taskId == null || taskId < 0) {
            write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"taskId must be a non-negative integer\"}")
            return
        }
        if (direction != DIRECTION_MAIN && direction != DIRECTION_VIRTUAL) {
            write(socket, head, STATUS_BAD_REQUEST,
                "{\"ok\":false,\"reason\":\"direction must be \\\"main\\\" or \\\"virtual\\\"\"}")
            return
        }
        val outcome = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(taskTimeoutMs) { panelTasks!!.move(taskId, direction) }
        }
        if (outcome == null) {
            write(socket, head, STATUS_GATEWAY_TIMEOUT,
                "{\"ok\":false,\"outcome\":\"unknown\",\"reason\":\"panel task move outcome unknown: " +
                    "the move did not reply within " + taskTimeoutMs + "ms; the task may still have moved\"}")
            return
        }
        writePanelTaskOutcome(socket, head, outcome.first, outcome.second)
    }

    /**
     * `POST /release?token=` body `{"sessionId":"…"}`。
     * 判据链同 /move（无 taskId/direction），通过后**先停流**（走 [stream] 的既有收场
     * 通路），再交执行口收屏；屏幕上任务会随 `DESTROY_CONTENT_ON_REMOVAL` 关闭 ——
     * UI 已二次确认，这里只如实回话。
     *
     * **回执丢失不清账**：停流回执丢了（会话在拆、屏没收）时保留
     * [lastStream] 快照可重试入口，第二次 release 走同一条路把屏收干净 ——
     * 不制造「屏还在、两端都 409」的死局。
     */
    private fun handleRelease(socket: Socket, head: ByteArray) {
        val body = when (val read = readBody(socket, head)) {
            is BodyRead.TooLarge -> {
                write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"release body too large (max 4KB)\"}")
                return
            }
            is BodyRead.Truncated -> {
                write(socket, head, STATUS_BAD_REQUEST,
                    "{\"ok\":false,\"reason\":\"release body truncated; the connection closed early\"}")
                return
            }
            is BodyRead.Body -> read.text
        }
        val parsed = runCatching { org.json.JSONObject(body) }.getOrNull()
        val sessionId = parsed?.let { sessionIdFromBodyOrHeader(body, head) }
        // 回执丢失的重试优先：活会话不在（上一发 stop 已把控制器打"不在跑"）但
        // "上一发 release 欠着的"那届对得上（tab + sessionId）→ 走同一条收屏路。
        // stop 幂等，第二发把屏收干净，不进「屏还在、两端都 409」死局。
        val pendingRetry = run {
            val pending = releasePendingSessionId
            val snapshot = lastStream
            val tab = headerValue(head, TAB_HEADER)
            pending != null && parsed != null && sessionId == pending && snapshot != null &&
                !tab.isNullOrEmpty() && constantTimeEquals(tab, snapshot.tabToken)
        }
        val current = if (pendingRetry) lastStream!! else taskSessionOrRespond(socket, head, sessionId) ?: return
        if (parsed == null) {
            write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"malformed release body\"}")
            return
        }
        // 先停流：照 /stop 的同一条通路收会话（回执丢失/失败都如实回，不在这块账上赌收屏）。
        val acknowledged = try {
            kotlinx.coroutines.runBlocking { stream.stop() }
        } catch (broken: Throwable) {
            // 账不动：会话状态未知但屏未收，重试 release 仍走同一条路。
            releasePendingSessionId = current.sessionId
            write(socket, head, STATUS_SERVICE_UNAVAILABLE,
                "{\"ok\":false,\"reason\":\"release failed at stream stop: " + broken.javaClass.simpleName + "\"}")
            return
        }
        if (!acknowledged) {
            // 回执丢失：会话在拆除路上、屏**未收**。账保留（可重试），下一步是重发 release；
            // 重试时 stop 幂等空过（无活会话），屏照常收掉。
            releasePendingSessionId = current.sessionId
            write(socket, head, STATUS_SERVICE_UNAVAILABLE,
                "{\"ok\":false,\"reason\":\"stream stop reply lost; the display was not released, retry release\"}")
            logSession(StreamSessionPhase.STOP, "unknown", "stream stop reply lost before display release")
            return
        }
        // 停流确认后才清账：此刻起面板可再 start 新会话。
        releasePendingSessionId = null
        lastEndedSessionId = current.sessionId
        lastStream = null
        lastStartReason = null
        logSession(StreamSessionPhase.STOP, "confirmed")
        val outcome = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(taskTimeoutMs) { panelTasks!!.release() }
        }
        if (outcome == null) {
            write(socket, head, STATUS_GATEWAY_TIMEOUT,
                "{\"ok\":false,\"outcome\":\"unknown\",\"reason\":\"display release outcome unknown: " +
                    "the release did not reply within " + taskTimeoutMs + "ms\"}")
            return
        }
        if (!outcome.first) {
            writePanelTaskOutcome(socket, head, false, outcome.second)
            return
        }
        write(socket, head, STATUS_OK, "{\"ok\":true,\"released\":true}")
    }

    /**
     * `POST /rotate?token=` body `{"sessionId":"…"}`。
     * 旋转虚拟屏（宽高交换走 resize，**不 release/不重建**，屏上任务保留）。
     * 判据链同 /release（无 taskId/direction），通过后**先停流**（换 reader 会中断
     * 取帧，流会话先按既有通路收干净；回执丢失按 /release 同口径 503，屏不动、
     * 不旋转），确认后清会话账，再交执行口旋转；成功回 `{"ok":true,"width":…,"height":…}`。
     * 面板语义：旋转可逆、不破坏任务，无需二次确认；失败 503/409 + reason。
     */
    private fun handleRotate(socket: Socket, head: ByteArray) {
        val body = when (val read = readBody(socket, head)) {
            is BodyRead.TooLarge -> {
                write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"rotate body too large (max 4KB)\"}")
                return
            }
            is BodyRead.Truncated -> {
                write(socket, head, STATUS_BAD_REQUEST,
                    "{\"ok\":false,\"reason\":\"rotate body truncated; the connection closed early\"}")
                return
            }
            is BodyRead.Body -> read.text
        }
        val parsed = runCatching { org.json.JSONObject(body) }.getOrNull()
        val sessionId = parsed?.let { sessionIdFromBodyOrHeader(body, head) }
        // 回执丢失的重试优先（同 release 旁路）：活会话不在但"上一发 rotate
        // 欠着的"那届对得上（tab + sessionId）→ 走同一条旋转路，stop 幂等。
        val snapshot = lastStream
        val presentedTab = headerValue(head, TAB_HEADER)
        val pendingRetry = parsed != null && rotatePendingSessionId != null &&
            sessionId == rotatePendingSessionId && snapshot != null &&
            !presentedTab.isNullOrEmpty() && constantTimeEquals(presentedTab, snapshot.tabToken)
        val current = if (pendingRetry) lastStream!! else taskSessionOrRespond(socket, head, sessionId) ?: return
        if (parsed == null) {
            write(socket, head, STATUS_BAD_REQUEST, "{\"ok\":false,\"reason\":\"malformed rotate body\"}")
            return
        }
        // 先停流：照 /stop 的同一条通路收会话（换 reader 前必须让取帧停干净；
        // 回执丢失/失败都如实回，屏不动、不旋转 —— 不在未知状态上赌设备事务）。
        val acknowledged = try {
            kotlinx.coroutines.runBlocking { stream.stop() }
        } catch (broken: Throwable) {
            rotatePendingSessionId = current.sessionId
            write(socket, head, STATUS_SERVICE_UNAVAILABLE,
                "{\"ok\":false,\"reason\":\"rotate failed at stream stop: " + broken.javaClass.simpleName + "\"}")
            return
        }
        if (!acknowledged) {
            rotatePendingSessionId = current.sessionId
            write(socket, head, STATUS_SERVICE_UNAVAILABLE,
                "{\"ok\":false,\"reason\":\"stream stop reply lost; the display was not rotated, retry rotate\"}")
            logSession(StreamSessionPhase.STOP, "unknown", "stream stop reply lost before rotate")
            return
        }
        // 停流确认后才清账：面板据此复位到可再 start。
        rotatePendingSessionId = null
        releasePendingSessionId = null
        lastEndedSessionId = current.sessionId
        lastStream = null
        lastStartReason = null
        logSession(StreamSessionPhase.STOP, "confirmed")
        val outcome = kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(taskTimeoutMs) { panelTasks!!.rotate() }
        }
        if (outcome == null) {
            write(socket, head, STATUS_GATEWAY_TIMEOUT,
                "{\"ok\":false,\"outcome\":\"unknown\",\"reason\":\"panel rotate outcome unknown: " +
                    "the resize did not reply within " + taskTimeoutMs + "ms\"}")
            return
        }
        if (!outcome.first) {
            writePanelTaskOutcome(socket, head, false, outcome.second)
            return
        }
        // 成功：payload 是 {"width":…,"height":…}，原样透传给面板复位提示。
        write(socket, head, STATUS_OK, "{\"ok\":true,\"rotated\":true," +
            "\"dimensions\":" + outcome.second + "}")
    }

    /**
     * 记一条控制面这一侧的会话读数。
     *
     * 序号用控制器的宿主侧会话序号（不是 token、不是端口）：日志要能被复制出去
     * 而不带凭据；控制器那几条设备侧读数用的是同一个号，两边因此对得上。
     *
     * 序号后缀 [CTL_SUFFIX] 标出这是**控制面**对调用方的回话 —— 同一场会话在设备侧
     * （控制器）也会记一条，两条都以同一序号成对出现：一条说"设备侧走到哪一步"，
     * 一条说"对浏览器回了什么"。
     */
    private fun logSession(phase: String, result: String, reason: String = "", dims: StreamSessionDims? = null) {
        val sink = sessionLog ?: return
        val label = stream.sessionLabel
        sink.write(
            StreamSession(
                session = if (label == null) "-" else label + CTL_SUFFIX,
                phase = phase,
                result = result,
                reason = reason,
                sourceW = dims?.sourceW,
                sourceH = dims?.sourceH,
                encodeW = dims?.encodeW,
                encodeH = dims?.encodeH,
            ),
        )
    }

    /** query 的 sessionId 参数，缺席回 null（调用方按 400 处理）。 */
    private fun sessionIdParam(query: String, head: ByteArray): String? {
        val fromQuery = query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == "sessionId" }?.second
        val candidate = fromQuery ?: headerValue(head, "x-pilot-session")
        return candidate?.takeIf { it.isNotEmpty() }
    }

    // ── 头、token 与 CORS ──

    private fun tokenGiven(query: String, head: ByteArray): Boolean {
        val fromQuery = query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == "token" }?.second
        val candidate = fromQuery ?: headerValue(head, "x-pilot-token")
        if (candidate.isEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
    }

    private fun headerValue(head: ByteArray, lowerName: String): String =
        String(head, Charsets.ISO_8859_1).lineSequence()
            .firstOrNull { it.substringBefore(':').trim().lowercase() == lowerName }
            ?.substringAfter(':')?.trim().orEmpty()

    /**
     * 只对 **loopback 的 http(s) origin** 回显 `Access-Control-Allow-Origin`：DSH 的
     * Web UI（浏览器半）从它自己的 origin 用 fetch 读本端点需要它。其余 origin 一律
     * 不给 ACAO —— 浏览器同源策略替本模块挡住跨站读取；非浏览器调用方不受影响。
     */
    private fun corsOrigin(head: ByteArray): String? {
        val origin = headerValue(head, "origin")
        return if (ORIGIN_LOOPBACK.matches(origin)) origin else null
    }

    /** 读到 `\r\n\r\n` 为止；上界与总期限与流服务同款（控制请求更小）。 */
    private fun readHead(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val one = ByteArray(1)
        val deadlineNs = System.nanoTime() + HEAD_TOTAL_MS * 1_000_000L // HEAD_TOTAL_MS: Long
        while (buffer.size() < MAX_HEAD_BYTES) {
            if (System.nanoTime() > deadlineNs) return null
            val read = input.read(one)
            if (read < 0) return null
            buffer.write(one, 0, read)
            val bytes = buffer.toByteArray()
            if (bytes.size >= 4 && bytes[bytes.size - 4] == '\r'.code.toByte() &&
                bytes[bytes.size - 3] == '\n'.code.toByte() &&
                bytes[bytes.size - 2] == '\r'.code.toByte() &&
                bytes[bytes.size - 1] == '\n'.code.toByte()
            ) return bytes
        }
        return buffer.toByteArray()
    }

    private fun write(socket: Socket, requestHead: ByteArray, status: Int, body: String) {
        runCatching {
            val output = socket.getOutputStream()
            val bytes = body.toByteArray(Charsets.UTF_8)
            val origin = corsOrigin(requestHead)
            output.write(
                ("HTTP/1.1 $status ${reasonOf(status)}\r\n" +
                    "Content-Type: application/json\r\n" +
                    (origin?.let { "Access-Control-Allow-Origin: $it\r\nVary: Origin\r\n" } ?: "") +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n" +
                    "Content-Length: ${bytes.size}\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            output.write(bytes)
            output.flush()
        }
    }

    private fun reasonOf(status: Int): String = when (status) {
        STATUS_OK -> "OK"
        STATUS_BAD_REQUEST -> "Bad Request"
        STATUS_FORBIDDEN -> "Forbidden"
        STATUS_NOT_FOUND -> "Not Found"
        STATUS_METHOD_NOT_ALLOWED -> "Method Not Allowed"
        STATUS_CONFLICT -> "Conflict"
        STATUS_SERVICE_UNAVAILABLE -> "Service Unavailable"
        STATUS_GATEWAY_TIMEOUT -> "Gateway Timeout"
        else -> "Error"
    }

    private fun jsonString(value: String?): String {
        value ?: return "null"
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    companion object {
        private const val DEFAULT_WAIT_MS = 3_000L

        /** 控制面读数的序号后缀：与设备侧（控制器）的读数成对出现。 */
        private const val CTL_SUFFIX = "/ctl"

        const val PATH_STATUS = "/status"
        const val PATH_START = "/start"
        const val PATH_STOP = "/stop"
        const val PATH_INPUT = "/input"
        /** 面板任务面：列当前虚拟屏任务 / 搬迁 / 释放 / 旋转。 */
        const val PATH_TASKS = "/tasks"
        const val PATH_MOVE = "/move"
        const val PATH_RELEASE = "/release"
        const val PATH_ROTATE = "/rotate"
        /** /move 的 direction 白名单：主屏（displayId 0）或当前虚拟屏。 */
        const val DIRECTION_MAIN = "main"
        const val DIRECTION_VIRTUAL = "virtual"
        const val STATUS_OK = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_FORBIDDEN = 403
        const val STATUS_NOT_FOUND = 404
        const val STATUS_METHOD_NOT_ALLOWED = 405
        const val STATUS_CONFLICT = 409
        const val STATUS_TOO_MANY_REQUESTS = 429
        const val STATUS_GATEWAY_TIMEOUT = 504
        const val STATUS_SERVICE_UNAVAILABLE = 503

        /** 常数时间比较（面板凭据对账用）：不等长也走完全程，不给时序侧信道。 */
        fun constantTimeEquals(a: String, b: String): Boolean =
            java.security.MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        /** 仅回显给 loopback 的 http(s) origin（DSH 的 Web UI 全家都在本机回环上）。 */
        internal val ORIGIN_LOOPBACK = Regex("^https?://(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?$", RegexOption.IGNORE_CASE)

        private const val ACCEPT_BACKLOG = 4
        private const val MAX_HEAD_BYTES = 8 * 1024

        /** /input 的 body 上限：几个归一化数字的扁平 JSON，4KB 绰绰有余（与 Node 半同值）。 */
        private const val MAX_BODY_BYTES = 4 * 1024

        /** TooLarge 时的有界排干上限：不 RST 对端，也不被恶意大声明拖住。 */
        private const val MAX_DRAIN_BYTES = 64 * 1024

        /** 连接服务池大小：accept 只收连接；/input 的慢执行不占 accept 线程。 */
        private const val SERVE_POOL_SIZE = 4

        /** 浏览器侧面板凭据的请求头（Node 半转发，只进内存，零日志）。 */
        const val TAB_HEADER = "x-pilot-tab"
        private const val READ_DEADLINE_MS = 1_000
        private const val HEAD_TOTAL_MS = 2_000L
        private const val STOP_JOIN_MS = 1_000
    }
}
