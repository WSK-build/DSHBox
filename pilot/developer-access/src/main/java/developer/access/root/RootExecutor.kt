package developer.access.root

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import developer.access.core.ElevationDirs
import developer.access.core.ServerSpec
import developer.access.core.ServerTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root 通道的执行器：监听 socket，接收命令，以 `su -c` 执行并流式回包。
 *
 * ## 传输形态
 *
 * - **unix socket（默认）**：落在开关目录内（guest 可见、同机其他 App 不可达）。
 *   用 `LocalSocket.bind(FILESYSTEM)` 取已绑定 fd 再交给 `LocalServerSocket(FileDescriptor)`
 *   ——`LocalServerSocket(String)` 固定走 abstract，无法监听 filesystem 路径。
 * - **TCP（仅本机 / 局域网）**：`ServerSocket`，认证走 nonce + HMAC 挑战应答。
 *
 * **unix socket 恒定监听，TCP 是叠加**：guest 内的 `android-root` 只会连 unix socket，
 * 若切到 TCP 就停掉它，DSH 手里的命令会当场失效；而 TCP 存在的意义是让外部工具接入。
 *
 * ## 认证
 *
 * - unix socket：`getPeerCredentials()`（即 SO_PEERCRED）校验对端 uid 等于自身 uid。
 *   本 App 与沙箱同 uid，天然通过；其他 App 被拒。
 * - TCP：128 bit 访问码 + HMAC-SHA256 挑战应答（secret 不上网、nonce 每次随机防重放）。
 *   在线猜测由 [AuthLimiter] 限流；离线爆破由密钥长度挡。
 *
 * ## socket 生命周期
 *
 * 启动前**先 unlink 残留文件**：进程被强杀时 socket 文件会留下，新监听会
 * `Address already in use`。关闭时 `LocalServerSocket` 与绑定用的 `LocalSocket`
 * **都要关闭**——fd 构造的监听端 close() 不关闭底层 fd。
 */
class RootExecutor(
    private val dirs: ElevationDirs,
    private val spec: ServerSpec,
    private val accessCode: () -> String,
    private val limiter: AuthLimiter = AuthLimiter(),
    /**
     * `su` 调用构造器。
     *
     * 生产固定为 `["su", "-c", cmd]`（单参数形式，su 只解析一次）；
     * 仪器测试可注入等价的普通 shell，从而在没有 root 的模拟器上验证
     * 协议、分帧、流式输出、二进制与引号透传——只跳过"提权"这一步本身。
     */
    private val suCommand: (String) -> List<String> = { listOf("su", "-c", it) },
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val random = SecureRandom()

    private var localBinder: LocalSocket? = null
    private var localServer: LocalServerSocket? = null
    private var tcpServer: ServerSocket? = null
    private val acceptJobs = mutableListOf<Job>()

    /** 执行器是否在监听。 */
    val isRunning: Boolean get() = running.get()

    /**
     * 启动监听。
     *
     * **unix socket 总是监听**：它是 guest 内 `android-root` 的入口，无论配置成哪种传输
     * 都必须可用——否则切到 TCP 会让 DSH 手里的命令当场失效。TCP 是在此之上的叠加，
     * 只服务外部工具。
     *
     * @return 启动成功；端口/路径被占用或绑定失败时返回 false。
     */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        dirs.ensureLayout()
        return runCatching {
            startUnix()
            when (spec.transport) {
                ServerTransport.UNIX_SOCKET -> Unit
                ServerTransport.TCP_LOCAL -> startTcp(InetAddress.getByName("127.0.0.1"))
                ServerTransport.TCP_LAN -> startTcp(null)
                // Root 只有 unix / 本机 / 局域网三档。自定义 spec 的语义属于 adb，
                // Root 侧没有对应实现——不猜、不降级，直接拒绝启动，
                // 否则面板显示的档位与实际监听方式会对不上。
                ServerTransport.CUSTOM -> error("root channel does not support custom specs")
            }
            true
        }.getOrElse {
            android.util.Log.w(TAG, "root server start failed", it)
            stop()
            false
        }
    }

    /** 停止监听并释放 socket。 */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        acceptJobs.forEach { it.cancel() }
        acceptJobs.clear()
        runCatching { localServer?.close() }
        runCatching { localBinder?.close() }
        runCatching { tcpServer?.close() }
        localServer = null
        localBinder = null
        tcpServer = null
        scope.cancel()
    }

    private fun startUnix(): Boolean {
        val path = File(dirs.elevationDir, ROOT_SOCKET_NAME).absolutePath
        // 残留文件会让新监听绑定失败；先清掉（进程被强杀时文件会留下）。
        File(path).delete()
        val binder = LocalSocket()
        binder.bind(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
        val server = LocalServerSocket(binder.getFileDescriptor())
        localBinder = binder
        localServer = server
        acceptJobs += scope.launch { acceptUnixLoop(server) }
        return true
    }

    private fun startTcp(bindAddress: InetAddress?): Boolean {
        val server = ServerSocket(spec.port, BACKLOG, bindAddress)
        tcpServer = server
        acceptJobs += scope.launch { acceptTcpLoop(server) }
        return true
    }

    private fun acceptUnixLoop(server: LocalServerSocket) {
        while (running.get()) {
            val conn = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { handleUnix(conn) }
        }
    }

    private fun acceptTcpLoop(server: ServerSocket) {
        while (running.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { handleTcp(socket) }
        }
    }

    private fun handleUnix(conn: LocalSocket) {
        try {
            val peer = runCatching { conn.peerCredentials }.getOrNull()
            // 同 uid 才放行：本 App 与沙箱同 uid，其他 App 不同。
            if (peer == null || peer.uid != android.os.Process.myUid()) {
                android.util.Log.w(TAG, "unix peer rejected: uid=${peer?.uid}")
                return
            }
            serve(conn.inputStream, conn.outputStream)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "unix connection failed", t)
        } finally {
            runCatching { conn.close() }
        }
    }

    private fun handleTcp(socket: Socket) {
        val peerKey = socket.inetAddress?.hostAddress ?: "unknown"
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            if (!authenticate(input, output, peerKey)) return
            serve(input, output)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "tcp connection failed", t)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** TCP 认证：发 nonce → 校验客户端 HMAC 应答 → 回结果。 */
    private fun authenticate(input: InputStream, output: OutputStream, peerKey: String): Boolean {
        val now = System.currentTimeMillis()
        // 持续失败后的拒绝态：不再发 nonce，直接告知需重新生成访问码。
        if (limiter.isLockedOut()) {
            RootProtocol.writeFrame(output, RootProtocol.errorFrame(LOCKED_OUT_MESSAGE))
            return false
        }
        if (limiter.isLocked(peerKey, now)) {
            RootProtocol.writeFrame(output, RootProtocol.errorFrame("too many failures"))
            return false
        }
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        RootProtocol.writeFrame(output, RootProtocol.nonceFrame(nonce))

        val reply = runCatching { RootProtocol.readFrame(input) }.getOrNull() ?: return false
        val signature = reply.optString(RootProtocol.KEY_AUTH, "")
        val expected = RootAuthCode.sign(accessCode(), nonce)
        // 定长比较，避免按字符提前返回泄漏位置信息。
        if (!constantTimeEquals(signature, expected)) {
            val backoff = limiter.recordFailure(peerKey, now)
            val frame = when {
                backoff == AuthLimiter.LOCKED_OUT -> RootProtocol.errorFrame(LOCKED_OUT_MESSAGE)
                backoff > 0 -> RootProtocol.errorFrame("locked ${backoff}ms")
                else -> RootProtocol.authResultFrame(false)
            }
            RootProtocol.writeFrame(output, frame)
            return false
        }
        limiter.recordSuccess(peerKey)
        RootProtocol.writeFrame(output, RootProtocol.authResultFrame(true))
        return true
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    /** 服务一次会话：逐条接收请求并流式回包，直到客户端断开。 */
    private fun serve(input: InputStream, output: OutputStream) {
        while (running.get()) {
            val request = runCatching { RootProtocol.readFrame(input) }.getOrNull() ?: return
            val cmd = request.optString(RootProtocol.KEY_CMD, "")
            if (cmd.isBlank()) {
                RootProtocol.writeFrame(output, RootProtocol.errorFrame("missing cmd"))
                continue
            }
            execute(cmd, output)
        }
    }

    /**
     * 以 `su -c` 执行命令并回包。
     *
     * `ProcessBuilder("su", "-c", cmd)` 用**单参数**形式：`su` 只解析一次，
     * guest 侧负责把参数数组拼成命令串并处理引号，避免两级转义叠加。
     */
    private fun execute(cmd: String, output: OutputStream) {
        val process = runCatching {
            ProcessBuilder(suCommand(cmd)).redirectErrorStream(false).start()
        }.getOrNull()
        if (process == null) {
            RootProtocol.writeFrame(output, RootProtocol.errorFrame("su unavailable"))
            return
        }
        // 用守护线程泵输出：本函数在连接线程上同步执行，且要等两个流都读完才算收尾。
        val stdoutPump = Thread { pump(process.inputStream, RootProtocol.STREAM_STDOUT, output) }
            .apply { isDaemon = true; start() }
        val stderrPump = Thread { pump(process.errorStream, RootProtocol.STREAM_STDERR, output) }
            .apply { isDaemon = true; start() }
        val code = runCatching { process.waitFor() }.getOrDefault(-1)
        runCatching { stdoutPump.join() }
        runCatching { stderrPump.join() }
        RootProtocol.writeFrame(output, RootProtocol.endFrame(code))
    }

    /** 把输出流按块编进帧；`data` 用 base64 以无损承载任意二进制。 */
    private fun pump(source: InputStream, stream: String, output: OutputStream) {
        val buffer = ByteArray(PUMP_CHUNK)
        while (true) {
            val read = runCatching { source.read(buffer) }.getOrDefault(-1)
            if (read <= 0) return
            val payload = buffer.copyOf(read)
            runCatching { RootProtocol.writeFrame(output, RootProtocol.outputFrame(stream, payload)) }
                .onFailure { return }
        }
    }

    private companion object {
        const val TAG = "RootExecutor"
        const val ROOT_SOCKET_NAME = "root.sock"
        const val NONCE_BYTES = 16
        const val PUMP_CHUNK = 8 * 1024
        const val BACKLOG = 4

        /** 持续失败后的拒绝文案：外部工具直接看到它，故为英文。 */
        const val LOCKED_OUT_MESSAGE =
            "access code locked after repeated failures; regenerate it in DSHBox"
    }
}
