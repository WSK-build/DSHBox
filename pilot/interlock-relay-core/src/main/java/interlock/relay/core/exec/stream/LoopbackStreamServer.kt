package interlock.relay.core.exec.stream

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference


/**
 * 一次流在宿主 loopback 侧结束的方式（诊断读数；不含任何内容字节）。
 *
 * 这几种结局在面板上看起来一样（黑屏/报错），但根因完全不同 ——
 * 读日志的人要靠它区分"设备侧正常收尾"与"客户端自己走了"。
 */
enum class StreamEndReason(
    /** 写进运行日志的结论（result 字段）。 */
    val result: String,
    /** 写进运行日志的短说明（reason 字段）。 */
    val note: String,
) {
    /** 还没结束。 */
    NONE("none", ""),

    /** 设备侧读到 EOF：编码器正常收尾。 */
    EOF("eof", "device stream ended"),

    /** 写 socket 失败：客户端先走了（关页面/换会话）。 */
    CLIENT_GONE("client_gone", "client connection gone"),

    /** 会话已被消费：第二次来取流被 409 挡回。 */
    CONFLICT("conflict", "stream already consumed"),

    /** 读设备侧管道出错：编码器异常退出。 */
    SOURCE_ERROR("device_error", "device stream read failed"),
}

/**
 * 仅 loopback 的实时流 HTTP 服务：`/stream` 回一段 fMP4 字节流，`/health` 回状态。
 * **纯 JVM**（不碰 android.*），token 与单连接契约在 JVM 上穷举测试。
 *
 * 安全与边界的四条硬约束：
 * 1. **只绑 `127.0.0.1`，写死**：没有 bindHost 这类参数 —— "仅本机"是性质不是配置；
 * 2. 一次性 token：由 [newToken] 用 SecureRandom 生成，只在内存里 —— 本类**一行日志
 * 都不写**，也不落任何文件；token 只经 [token] 交给装配方去拼完整播放地址；
 * 3. 无 token / 错 token 一律 403，比较用 [MessageDigest.isEqual]（常数时间）；
 * 鉴权在 accept 线程内联完成（头 1 秒超时、8KB 上界），未鉴权连接从头到尾
 * 不产生任何工作线程 —— 本机任何进程都摸得到这个端口，"每连接一线程"留给它
 * 就是送它一个 DoS 手柄；
 * 4. 单连接：`/stream` 的字节源只能被消费一次（[Source.open] 的实现保证），第二次
 * 请求即便源还在也回 409。
 *
 * start/stop 由宿主控制：[start] 绑端口、起 accept 循环；[stop] 关服务 socket、
 * 掐断在途连接并回调 [Source.onStopped]（装配方在那里停掉设备侧的编码会话）。
 * 响应用 `Connection: close` 且不带 Content-Length：流式正文按「读到对端关闭为止」
 * 语义传输（RFC 7230 §3.3.3），不引入 chunked 框架，播放端按普通 GET 消费即可。
 */
/** 流句柄的最小读数：控制端点的 start 回包与测试替身共用这一面。 */
interface StreamHandle {
    val port: Int
    val token: String
    val endpoint: String
    val metadata: String
}

class LoopbackStreamServer(
    private val source: Source,
    override val token: String = newToken(),
    port: Int = 0,
) : StreamHandle {

    /** 流字节源的抽象。实现方（控制器）把管道读端包成 [open] 的返回值。 */
    interface Source {
        /**
         * 流会话的描述（JSON：mime、宽高、帧率）。`/health` 原样带出。
         * **不得包含 token** —— 这份字符串会进响应体。
         */
        val metadata: String

        /**
         * 打开流字节源。源只能消费一次：第二次调用回 null（服务随即回 409）。
         * 返回的流由服务侧负责关（连接结束或 [stop] 时）。
         */
        fun open(): InputStream?

        /** 服务停了：释放设备侧的流会话（编码器、pipe、线程归装配方管）。 */
        fun onStopped()

        /**
         * 流式转发自然结束（源 EOF 或转发出错）：装配方在这里把会话记成"已终"，
         * 下一次 start 才不会把一个已死的源当活会话交出去。
         */
        fun onStreamEnded()
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val running = AtomicBoolean(false)

    /** `/stream` 的单连接闩：第一次消费就落下，之后一律 409。 */
    private val consumed = AtomicBoolean(false)

    /** 在途的流连接：stop 时从这里掐断。 */
    private val activeConnection = AtomicReference<Socket?>(null)

    /** 已写给客户端的字节数（诊断读数）：面板黑屏时用它判断"到底有没有收到数据"。 */
    private val writtenBytes = AtomicLong(0)

    /** 转发怎么结束的（诊断读数）：谁先结束的。 */
    @Volatile
    var endReason: StreamEndReason = StreamEndReason.NONE
        private set

    /** 已写给客户端的字节数。 */
    val bytesWritten: Long get() = writtenBytes.get()


    @Volatile
    private var boundPort = -1

    /** 构造时请求的端口（0 = 由系统挑）；成员里的 `port` 属性会遮住同名参数，绑定时用它。 */
    private val requestedPort: Int = port

    override val port: Int get() = boundPort

    /** 实际绑定的地址（`ServerSocket.inetAddress` 的原值；运行中恒为 "127.0.0.1"，停后为 null）。 */
    @Volatile
    var boundAddress: String? = null
        private set

    /** 实际绑定地址是否 loopback（`inetAddress.isLoopbackAddress` 的原值，不回读常量）。 */
    @Volatile
    var boundLoopback: Boolean? = null
        private set

    /** 播放端地址（**不带 token**；完整地址由装配方拼，不进日志）。 */
    override val endpoint: String get() = "http://$LOOPBACK:$boundPort/stream"

    /** 源的会话描述（mime/宽高/帧率的 JSON），控制端点的 start 回包原样带出。 */
    override val metadata: String get() = source.metadata

    /** 绑端口并起 accept 循环。已在跑时重复调用按成功处理；绑定失败回 false。 */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val bound = runCatching {
            ServerSocket(requestedPort, ACCEPT_BACKLOG, InetAddress.getByName(LOOPBACK))
        }.getOrElse {
            running.set(false)
            return false
        }
        serverSocket = bound
        boundPort = bound.localPort
        // 只记 ServerSocket.inetAddress 的**实际值**（null 就如实 null，不给常量兜底）：
        // 测试据此断言"真绑在 127.0.0.1 且是 loopback"，而不是回读一个写死的字符串。
        boundAddress = bound.inetAddress?.hostAddress
        boundLoopback = bound.inetAddress?.isLoopbackAddress
        val thread = Thread({ acceptLoop(bound) }, "relay-stream-accept").apply {
            isDaemon = true
            start()
        }
        acceptThread = thread
        return true
    }

    /**
     * 停服务：关服务 socket、掐在途连接，并回调 [Source.onStopped]（设备侧的编码
     * 会话在那里收）。幂等：重复调用是空操作，[Source.onStopped] 恰好回调一次。
     */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeConnection.getAndSet(null)?.let { runCatching { it.close() } }
        runCatching { source.onStopped() }
        // 从转发线程里调 stop（EOF 清场）时不能等自己：join 自身是白等一个超时。
        val accepting = acceptThread
        acceptThread = null
        if (accepting != null && accepting !== Thread.currentThread()) {
            runCatching { accepting.join(STOP_JOIN_MS.toLong()) }
        }
        // 单连接闩与端口账随 stop 复位：/health 不得替一个已收场的会话继续报状态。
        consumed.set(false)
        boundPort = -1
        boundAddress = null
        boundLoopback = null
    }

    private fun acceptLoop(bound: ServerSocket) {
        while (running.get()) {
            val socket = runCatching { bound.accept() }.getOrElse { break }
            if (!running.get()) {
                runCatching { socket.close() }
                break
            }
            // 鉴权与路由**内联**完成：未鉴权/坏请求的连接在 accept 线程上就地关掉，
            // 不为它起任何线程。读头受双重期限约束 —— 单次 read 1 秒（HEAD_DEADLINE_MS）、
            // 总量 2 秒（HEAD_TOTAL_MS）：慢速发头（每次 1 字节）最多占住 accept 两秒，
            // 这是本机恶意进程能造成的全部代价。
            try {
                socket.soTimeout = HEAD_DEADLINE_MS
                when (val request = inspect(socket)) {
                    is Inspection.Reject -> {
                        writeSimple(socket, request.status, request.reason)
                        runCatching { socket.close() }
                    }
                    is Inspection.Health -> {
                        writeSimple(socket, STATUS_OK, healthBody(), CONTENT_TYPE_JSON)
                        runCatching { socket.close() }
                    }
                    is Inspection.Stream -> {
                        // 真正的字节搬运才有线程，且单连接闩保证了它同时至多一条。
                        relayAuthorized(socket)
                    }
                    is Inspection.Close -> runCatching { socket.close() }
                }
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    /** 内联检查的结果：拒绝（带状态码）、健康查询、放行流、或静默关连接。 */
    private sealed class Inspection {
        class Reject(val status: Int, val reason: String) : Inspection()
        object Health : Inspection()
        object Stream : Inspection()
        object Close : Inspection()
    }

    private fun inspect(socket: Socket): Inspection {
        val head = readHead(socket.getInputStream()) ?: return Inspection.Close
        if (head.size >= MAX_HEAD_BYTES) return Inspection.Reject(STATUS_BAD_REQUEST, "bad request")
        val requestLine = String(head, Charsets.ISO_8859_1).lineSequence().firstOrNull().orEmpty()
        val parts = requestLine.split(" ")
        if (parts.size < 2) return Inspection.Reject(STATUS_BAD_REQUEST, "bad request")
        if (parts[0] != "GET") return Inspection.Reject(STATUS_METHOD_NOT_ALLOWED, "GET only")
        val (path, query) = splitTarget(parts[1])
        if (!tokenGiven(query, head)) return Inspection.Reject(STATUS_FORBIDDEN, "forbidden")
        return when (path) {
            PATH_STREAM -> Inspection.Stream
            PATH_HEALTH -> Inspection.Health
            else -> Inspection.Reject(STATUS_NOT_FOUND, "not found")
        }
    }

    /** 已鉴权的 /stream：单连接闩 → 开源 → 回头 → 起唯一的搬运线程。 */
    private fun relayAuthorized(socket: Socket) {
        if (!consumed.compareAndSet(false, true)) {
            writeSimple(socket, STATUS_CONFLICT, "the stream is already being consumed by another client")
            runCatching { socket.close() }
            return
        }
        val stream = source.open() ?: run {
            writeSimple(socket, STATUS_CONFLICT, "the stream source is no longer available")
            runCatching { socket.close() }
            // 闩已落、源没开成：这届会话已经到头，让装配方把账清了，
            // 下一次 start 才不会把一个死源当活会话。
            endReason = StreamEndReason.CONFLICT
            runCatching { source.onStreamEnded() }
            return
        }
        if (!writeHead(socket, STATUS_OK)) {
            // 头没写出去客户端就没了：源必须就地关，否则设备侧会话对着一个死连接写。
            runCatching { stream.close() }
            runCatching { socket.close() }
            endReason = StreamEndReason.CLIENT_GONE
            runCatching { source.onStreamEnded() }
            return
        }
        activeConnection.set(socket)
        Thread({
            // 收场原因在这一条线上定：**读**失败是设备侧的事，**写**失败是客户端走了。
            // 这两种在面板上都是"黑屏/报错"，但根因不同，必须能分开。
            var reason = StreamEndReason.EOF
            try {
                // 进入流式撤掉头超时：空闲的直播不该被读超时掐断。
                socket.soTimeout = 0
                val output = socket.getOutputStream()
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val read = try {
                        stream.read(buffer)
                    } catch (broken: Exception) {
                        // 读端报错/管道断了：设备侧会话没了。
                        reason = StreamEndReason.SOURCE_ERROR
                        break
                    }
                    if (read < 0) break
                    try {
                        output.write(buffer, 0, read)
                        output.flush()
                    } catch (broken: Exception) {
                        // 写不出去：客户端先走了（关页面/换会话）。
                        reason = StreamEndReason.CLIENT_GONE
                        break
                    }
                    // 写出去多少算多少：面板黑屏时用它判断"到底有没有收到数据"。
                    writtenBytes.addAndGet(read.toLong())
                }
            } catch (broken: Exception) {
                // 兜底：取流/建流本身失败（连接已断等），按客户端走了记。
                reason = StreamEndReason.CLIENT_GONE
            } finally {
                endReason = reason
                activeConnection.compareAndSet(socket, null)
                runCatching { stream.close() }
                runCatching { socket.close() }
                runCatching { source.onStreamEnded() }
            }
        }, "relay-stream-relay").apply { isDaemon = true; start() }
    }

    private fun healthBody(): String {
        val meta = source.metadata.trim()
        val inner = if (meta.startsWith("{") && meta.endsWith("}")) meta.substring(1, meta.length - 1) else ""
        val connected = activeConnection.get() != null
        return "{\"stream\":{" + inner + "},\"connected\":" + connected +
            ",\"consumed\":" + consumed.get() + ",\"stopped\":" + !running.get() + "}"
    }

    // ── 请求头与 token ──

    /** query 的 `token` 参数或 `X-Stream-Token` 头，二选一命中即可。 */
    private fun tokenGiven(query: String, head: ByteArray): Boolean {
        val fromQuery = query.split('&')
            .map { it.substringBefore('=') to it.substringAfter('=', "") }
            .firstOrNull { it.first == "token" }?.second
        val candidate = fromQuery ?: headerValue(head, "x-stream-token")
        if (candidate.isEmpty()) return false
        return MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8),
        )
    }

    private fun headerValue(head: ByteArray, lowerName: String): String =
        String(head, Charsets.ISO_8859_1).lineSequence()
            .firstOrNull { it.substringBefore(':').trim().lowercase() == lowerName }
            ?.substringAfter(':')?.trim().orEmpty()

    private fun splitTarget(target: String): Pair<String, String> =
        target.substringBefore('?') to target.substringAfter('?', "")

    /**
     * 读到 `\r\n\r\n` 为止；上界 [MAX_HEAD_BYTES]，总期限 [HEAD_TOTAL_MS] —— 慢速发头的客户端占住
     * 的是 accept 线程，期限必须是**总量**而不是每次 read 的超时（否则每次恰好送
     * 1 字节就能把 accept 挂住八千秒）。
     */
    private fun readHead(input: InputStream): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val one = ByteArray(1)
        val deadlineNs = System.nanoTime() + HEAD_TOTAL_MS * 1_000_000L
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
        return buffer.toByteArray() // 到上界：调用侧按大小判定坏请求
    }

    // ── 响应 ──

    private fun writeHead(socket: Socket, status: Int): Boolean = runCatching {
        val output = socket.getOutputStream()
        output.write(
            ("HTTP/1.1 $status ${reasonOf(status)}\r\n" +
                "Content-Type: $CONTENT_TYPE_MP4\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        output.flush()
        true
    }.getOrDefault(false)

    private fun writeSimple(socket: Socket, status: Int, body: String, contentType: String = CONTENT_TYPE_TEXT) {
        runCatching {
            val output = socket.getOutputStream()
            val bytes = body.toByteArray(Charsets.UTF_8)
            output.write(
                ("HTTP/1.1 $status ${reasonOf(status)}\r\n" +
                    "Content-Type: $contentType\r\n" +
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
        else -> "Error"
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PATH_STREAM = "/stream"
        const val PATH_HEALTH = "/health"
        const val CONTENT_TYPE_MP4 = "video/mp4"
        const val CONTENT_TYPE_JSON = "application/json"
        const val CONTENT_TYPE_TEXT = "text/plain; charset=utf-8"
        const val STATUS_OK = 200
        const val STATUS_BAD_REQUEST = 400
        const val STATUS_FORBIDDEN = 403
        const val STATUS_NOT_FOUND = 404
        const val STATUS_METHOD_NOT_ALLOWED = 405
        const val STATUS_CONFLICT = 409

        // 未鉴权连接在 accept 线程内联处理（串行），backlog 只需接住短时并发 ——
        // 1 会让本机的并发探测被内核直接拒掉（连接重建即成功，看着像端口闪烁）。
        private const val ACCEPT_BACKLOG = 8

        /** 未鉴权连接读头的时间上限：一条挂起连接最多占 accept 这么久。 */
        /** 每次阻塞 read 的 socket 超时；总期限由 [HEAD_TOTAL_MS] 把守。 */
        private const val HEAD_DEADLINE_MS = 1_000

        /** 读头的**总**期限：慢速发头（每次 1 字节）最多占 accept 这么久。 */
        private const val HEAD_TOTAL_MS = 2_000
        private const val MAX_HEAD_BYTES = 8 * 1024
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private const val STOP_JOIN_MS = 1_000
        private const val TOKEN_CHARS = 32

        /**
         * 一次性 token：SecureRandom 字节按无偏映射压进 URL 安全字母表
         * （256 % 64 == 0，每字符恰好 4 种字节来源），32 字符 ≈ 192 位熵。
         */
        fun newToken(): String {
            val raw = ByteArray(TOKEN_CHARS)
            SecureRandom().nextBytes(raw)
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            return buildString(TOKEN_CHARS) {
                for (b in raw) append(alphabet[(b.toInt() and 0xFF) ushr 2])
            }
        }
    }
}
