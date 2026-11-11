package com.dshbox.remote.proxy

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket

/**
 * 回环代理：每端点会话一个实例，仅监听该端点的回环 IP:port。
 *
 * 请求管线（按序）：
 * 1. 门禁校验（缺失/错误 → 404）；
 * 2. 剥离入站全部 Cookie 头（渲染器侧 cookie 一律不入上游，jar 是唯一真相）；
 * 3. [AuthInjector] 注入；
 * 4. 出站头改写（Host/Origin/Referer）+ 按罐注入上游 Cookie；
 * 5. 转发上游（明文 HTTP 直连；HTTPS 走 [TlsTrust] 会话级 SSLContext）；
 * 6. 入站头改写（Location/CSP/Set-Cookie：入罐 + 非敏感双写）；
 * 7. 响应体流式回写（SSE 不缓冲）；WS 升级请求透传，101 后双向字节泵。
 *
 * 线程模型：接收线程 + 每连接一线程（本地会话流量等级下足够；stop 统一收口）。
 * 端口承诺：绑定失败短暂重试后显式报错，禁止静默换端口——回环 origin 与
 * 端点档案的对应关系是 storage 隔离的根基。
 */
class LoopbackProxy(
    /** 本端点绑定的回环 IP（设备槽位，终身不变）。 */
    val loopbackIp: String,
    /** 请求绑定的端口：0 表示首次会话由系统分配（分配结果由会话层写入端点档案）。 */
    val requestedPort: Int,
    /** 上游 origin（端点目标地址的 scheme+authority）。 */
    upstreamOrigin: String,
    /** 设备级 cookie 罐：同设备全部端点共享。 */
    private val jar: CookieJar,
    /** 门禁校验器（期望令牌来自会话层下发约定）。 */
    private val gate: GateCheck,
    /** 鉴权注入器（按插件，默认透明）。 */
    private val injector: AuthInjector = PassThroughInjector,
    /** 上游 TLS 信任（默认系统信任）。 */
    private val tls: TlsTrust = TlsTrust.systemDefault(),
    /**
     * 宿主下载目录（null = 无下载能力）：主框架响应判定为下载时把响应体
     * 落盘到该目录，其余响应照旧透传给渲染器。判定收口在 [DownloadDecision]。
     */
    private val downloadDir: File? = null,
    /** 下载结果页文案（本地化在宿主侧完成；null = 无下载能力，与目录成对提供）。 */
    private val downloadPages: DownloadPageRenderer? = null,
    /** 下载结束回调：true = 已落盘，false = 失败（宿主据此拉起下载器/权限引导）。 */
    private val onDownloadFinished: ((Boolean, String?) -> Unit)? = null,
    /** 会话鉴权过期（401）回调：返回 true 表示已续期，代理在同连接内重发一次 GET/HEAD。 */
    private val onAuthExpired: (() -> Boolean)? = null,
) {
    /** 上游 origin 的归一化文本（出站改写与罐记账的基准）。 */
    val upstreamOriginText: String = normalizeOrigin(upstreamOrigin)

    private val upstreamUri = URI(upstreamOriginText)

    /** start 成功后创建：携带实际绑定端口的入站改写器（Location 重写用）。 */
    private val inboundRewriter = AtomicReference<RequestRewriter?>()

    private val injectorState = InjectorState()
    private val serverSocket = AtomicReference<ServerSocket?>()
    private val executor = AtomicReference<ExecutorService?>()
    private val started = AtomicBoolean(false)
    private val openConnections: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    /** 实际绑定端口；start 成功后有效（首次会话由系统分配）。 */
    @Volatile
    var boundPort: Int = -1
        private set

    /** start 成功后的回环 origin（渲染器加载地址）。 */
    val loopbackOrigin: String
        get() = "http://$loopbackIp:$boundPort"

    private fun rewriter(): RequestRewriter = inboundRewriter.get()
        ?: RequestRewriter(loopbackOrigin, upstreamOriginText).also { inboundRewriter.set(it) }

    /**
     * 启动监听。端口被其它进程占用时短暂重试，仍失败抛 [ProxyStartException]
     * ——不换端口、不静默降级。
     */
    fun start() {
        check(started.compareAndSet(false, true)) { "LoopbackProxy already started" }
        val bindAddress = InetSocketAddress(loopbackIp, requestedPort)
        var lastFailure: IOException? = null
        val server = ServerSocket()
        var attempt = 0
        while (true) {
            try {
                server.bind(bindAddress, BACKLOG)
                boundPort = server.localPort
                serverSocket.set(server)
                inboundRewriter.set(RequestRewriter(loopbackOrigin, upstreamOriginText))
                val pool = Executors.newCachedThreadPool { runnable ->
                    Thread(runnable, "dshbox-remote-proxy").apply { isDaemon = true }
                }
                executor.set(pool)
                pool.execute { acceptLoop(server) }
                return
            } catch (e: IOException) {
                lastFailure = e
                attempt += 1
                if (attempt >= BIND_ATTEMPTS) break
                try {
                    Thread.sleep(BIND_RETRY_DELAY_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        runCatching { server.close() }
        started.set(false)
        throw ProxyStartException(
            "Failed to bind loopback proxy on $loopbackIp:$requestedPort; origin stability is kept, retry later",
            lastFailure,
        )
    }

    /** 停止监听并断开全部在途连接；幂等。 */
    fun stop() {
        serverSocket.getAndSet(null)?.close()
        executor.getAndSet(null)?.shutdownNow()
        synchronized(openConnections) {
            openConnections.toList().forEach { runCatching { it.close() } }
            openConnections.clear()
        }
        started.set(false)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (started.get()) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                return // stop() 关闭监听套接字即退出
            }
            executor.get()?.execute { handleConnection(client) }
        }
    }

    private fun handleConnection(client: Socket) {
        var upstream: Socket? = null
        try {
            openConnections.add(client)
            val input = BufferedInputStream(client.getInputStream())
            val head = HttpWire.readHead(input) ?: return

            // 1. 门禁校验：缺失/错误一律 404（自愈重发由会话层在 404 观测后驱动）
            if (gate.evaluate(head.headerValues("Cookie")) != GateCheck.GateDecision.VALID) {
                HttpWire.writeNotFound(client.getOutputStream())
                return
            }

            // WS 升级判定须在改写前进行（升级请求保留原始 Connection/Upgrade 头透传）
            val wsUpgrade = WsTunnel.isUpgradeRequest(head.headers)

            // 2. 剥离入站全部 Cookie 头（含门禁 cookie，均不转发）
            head.removeHeaders("Cookie")

            // 3. 注入器（在出站改写前，可继续追加/调整头与目标）
            val requestLine = head.startLine.split(' ')
            if (requestLine.size < 2) return
            val method = requestLine[0]
            val target = originFormOf(requestLine[1])
            val request = ProxyRequest(method, target, head.headers)
            injector.transformRequest(request, injectorState)
            val forwardTarget = originFormOf(request.target)
            // 注入器改写后的目标写回请求行：转发以改写结果为准
            if (forwardTarget != target) head.rewriteRequestTarget(forwardTarget)

            // 请求体先于写出读取：chunked 解块后以 Content-Length 转发
            val body = HttpWire.readRequestBody(input, head)
            if (body != null) {
                head.setHeader("Content-Length", body.size.toString())
                head.removeHeaders("Transfer-Encoding")
            }

            // 4. 出站头改写 + jar 注入；普通请求上游与渲染器侧连接都按单请求收口
            //   （WS 升级请求保留原始 Connection 头，升级语义原样透传）
            head.setHeader("Host", rewriter().outboundHost())
            val origin = rewriter().rewriteOrigin(request.firstHeader("Origin"))
            if (origin != null) head.setHeader("Origin", origin) else head.removeHeaders("Origin")
            val referer = rewriter().rewriteReferer(request.firstHeader("Referer"))
            if (referer != null) head.setHeader("Referer", referer) else head.removeHeaders("Referer")
            if (!wsUpgrade) {
                head.setHeader("Connection", "close")
                head.removeHeaders("Keep-Alive")
            }
            injectUpstreamCookies(head, forwardTarget)

            // 5. 转发上游
            var upstreamOut: OutputStream? = null
            var upstreamIn: BufferedInputStream? = null
            var responseHead: HttpWire.HttpHead? = null
            var attempt = 0
            while (true) {
                upstream = connectUpstream()
                openConnections.add(upstream)
                val out = upstream.getOutputStream()
                upstreamOut = out
                out.write(head.serialize())
                if (body != null) out.write(body)
                out.flush()

                val incoming = BufferedInputStream(upstream.getInputStream())
                upstreamIn = incoming
                val response = HttpWire.readHead(incoming) ?: return
                responseHead = response
                val status = response.startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                // 会话过期：先在同一连接线程内续期，再重发同一个请求（方法、
                // 头、body 原样），不把 401 透传给页面；每个请求最多重试一次
                // 只对可安全重放的 GET/HEAD 做内部重试：带 body 的写请求
                // 可能已在上游产生副作用，不能由代理自动再提交一次
                val retryable = method == "GET" || method == "HEAD"
                if (retryable && !wsUpgrade && status == 401 && attempt == 0 &&
                    runCatching { onAuthExpired?.invoke() == true }.getOrDefault(false)
                ) {
                    runCatching { upstream.close() }
                    openConnections.remove(upstream)
                    // 续期后重新注入：新 cookie 进罐、新 CSRF 进注入器；旧
                    // Cookie 头必须先清掉，否则重发仍带过期会话
                    head.removeHeaders("Cookie")
                    injector.transformRequest(request, injectorState)
                    injectUpstreamCookies(head, forwardTarget)
                    attempt += 1
                    continue
                }
                break
            }
            val finalOut = upstreamOut ?: return
            val finalIn = upstreamIn ?: return
            val finalHead = responseHead ?: return

            if (wsUpgrade && isSwitchingProtocols(finalHead)) {
                // 7(WS)：101 与后续帧原样双向透传
                val clientOut = client.getOutputStream()
                clientOut.write(finalHead.serialize())
                clientOut.flush()
                WsTunnel.pumpBidirectional(
                    clientOut = clientOut,
                    clientIn = input,
                    upstreamOut = finalOut,
                    upstreamIn = finalIn,
                )
                return
            }

            // 6. 入站头改写：Location / CSP / Set-Cookie（下载分支同样先入罐与双写）
            rewriteResponseHead(finalHead, forwardTarget)

            // 7. 主框架响应判定为下载 → 响应体落盘到宿主下载目录并回结果页；
            //    其余响应照旧流式回写（不缓冲，SSE 实时可见）
            val statusCode = finalHead.startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            if (downloadDir != null && downloadPages != null && statusCode in 200..299 &&
                DownloadDecision.isNavigationRequest(head.headers) &&
                DownloadDecision.isDownload(responseHead.headers)
            ) {
                handleDownload(client.getOutputStream(), finalIn, finalHead, forwardTarget)
                return
            }

            val clientOut = client.getOutputStream()
            finalHead.setHeader("Connection", "close")
            finalHead.removeHeaders("Keep-Alive")
            clientOut.write(finalHead.serialize())
            clientOut.flush()

            // 7. 响应体流式回写（不缓冲，SSE 实时可见）
            HttpWire.relay(finalIn, clientOut)
        } catch (e: Exception) {
            // 单连接故障（网络/TLS/解析）不影响会话：关闭本连接即可
        } finally {
            upstream?.let { runCatching { it.close() } }
            runCatching { client.close() }
            openConnections.remove(client)
            upstream?.let { openConnections.remove(it) }
        }
    }

    private fun injectUpstreamCookies(head: HttpWire.HttpHead, target: String) {
        val uri = runCatching { URI(upstreamOriginText + target) }.getOrNull() ?: return
        val cookies = jar.cookiesFor(uri)
        if (cookies.isNotEmpty()) {
            head.setHeader("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
        }
    }

    private fun rewriteResponseHead(responseHead: HttpWire.HttpHead, target: String) {
        responseHead.firstHeader("Location")?.let { location ->
            val rewritten = rewriter().rewriteLocation(location)
            if (rewritten != location) responseHead.setHeader("Location", rewritten)
        }
        val cspHeaders = responseHead.headers
            .withIndex()
            .filter { it.value.name.equals("Content-Security-Policy", ignoreCase = true) }
        for ((index, header) in cspHeaders) {
            val stripped = rewriter().stripUpgradeInsecure(header.value)
            if (stripped != header.value) {
                responseHead.headers[index] = header.copy(value = stripped)
            }
        }
        val setCookies = responseHead.headerValues("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            // 注入器先观察原始 Set-Cookie（凭据兑换信号），再入罐与双写
            injector.onResponse(setCookies, injectorState)
            responseHead.removeHeaders("Set-Cookie")
            val uri = runCatching { URI(upstreamOriginText + target) }.getOrNull()
            val dualWrites = if (uri != null) jar.storeAll(uri, setCookies) else emptyList()
            for (header in dualWrites) {
                responseHead.headers.add(ProxyHeader("Set-Cookie", header))
            }
        }
    }

    private fun connectUpstream(): Socket {
        val isHttps = upstreamUri.scheme.equals("https", ignoreCase = true)
        val port = upstreamUri.port.takeIf { it != -1 } ?: if (isHttps) 443 else 80
        val host = upstreamUri.host ?: throw ProxyStartException("Upstream origin has no host")
        if (isHttps) {
            val socket = tls.socketFactory().createSocket() as SSLSocket
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            socket.startHandshake()
            tls.verifyHostname(socket, host)
            return socket
        }
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        return socket
    }

    /** 兼容 absolute-form 请求目标：统一为 origin-form。 */
    private fun originFormOf(target: String): String =
        if (target.startsWith("http://") || target.startsWith("https://")) {
            runCatching {
                val uri = URI(target)
                (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            }.getOrElse { "/" }
        } else {
            target
        }

    private fun isSwitchingProtocols(head: HttpWire.HttpHead): Boolean =
        head.startLine.split(' ').getOrNull(1) == "101"

    /**
     * 下载分支：响应体落盘 `<downloadDir>/<name>` 后向渲染器回极简结果页。
     *
     * 写入纪律（取名与占位原子化，并发下载同名文件不覆盖、不交错）：
     * 1. 目标名占位——循环候选名 `File.createNewFile()`，创建成功即原子占有
     *    该名（已存在则试 ` (n)` 消解），占位文件是本请求私有的空目标；
     * 2. 内容写入 `File.createTempFile` 产出的唯一 `.part` 临时文件（并发请求
     *    的临时文件互不相干，不存在同路径互相覆盖）；
     * 3. 完整后以 `Files.move(REPLACE_EXISTING)` 换名到占位目标——替换的是
     *    本请求自己占下的空文件，原子完成且不留「删占位再改名」的竞态窗口；
     * 4. 任何失败（磁盘/权限/编码/超限）都删掉临时文件与占位空文件，并回
     *    同源错误页说明原因。
     *
     * 下载不产生 cookie 罐之外的任何存档；日志只记文件名与字节数，不打 URL
     * （query 可能携带票据）。落盘前顺手清理同目录超过 24 小时的陈旧 `.part`
     * （历史失败的遗留，一次性、失败忽略）。
     */
    private fun handleDownload(
        clientOut: OutputStream,
        upstreamIn: BufferedInputStream,
        responseHead: HttpWire.HttpHead,
        target: String,
    ) {
        val dir = downloadDir ?: return
        val pages = downloadPages ?: return
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "download dir unavailable: ${dir.name}")
            runCatching { onDownloadFinished?.invoke(false, "dir_unavailable") }
            writeSimplePage(clientOut, pages.failureHtml(pages.dirUnavailableReason()), pages.backLabel())
            return
        }
        cleanupStalePartFiles(dir)
        val name = DownloadFileName.resolve(responseHead.headers, upstreamOriginText + target)
        val targetFile = try {
            claimTarget(dir, name)
        } catch (e: Exception) {
            writeDownloadFailure(clientOut, pages, e, targetFile = null, reclaimPlaceholder = false)
            return
        }
        val part = try {
            File.createTempFile(DOWNLOAD_PART_PREFIX, DOWNLOAD_PART_SUFFIX, dir)
        } catch (e: Exception) {
            // 占位已成功、临时文件未建出：目标仍是未写入的空占位，可安全回收
            writeDownloadFailure(clientOut, pages, e, targetFile, reclaimPlaceholder = true)
            return
        }
        // 目标文件此时仍是本请求占下的空占位；换名成功后转为完成品，失败回收
        // 与否据此布尔判定（不以文件大小猜测）
        var placeholderReclaimed = true
        try {
            // 写入用 use 块：响应体写完即关闭临时文件句柄，不留到方法出口
            val bytes = part.outputStream().use { out ->
                HttpWire.drainBody(upstreamIn, responseHead, out)
            }
            java.nio.file.Files.move(
                part.toPath(),
                targetFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            // 换名完成后 targetFile 已是完成品（可能是合法的 0 字节文件）：
            // 此后的失败不得再回收它
            placeholderReclaimed = false
            Log.i(TAG, "download saved: ${targetFile.name} ($bytes bytes)")
            runCatching { onDownloadFinished?.invoke(true, null) }
            writeSimplePage(clientOut, pages.savedHtml(dir.name, targetFile.name), pages.backLabel(), autoBack = true)
        } catch (e: Exception) {
            runCatching { part.delete() }
            writeDownloadFailure(clientOut, pages, e, targetFile, reclaimPlaceholder = placeholderReclaimed)
        }
    }

    /**
     * 下载失败收口：回失败页。[reclaimPlaceholder] 为显式布尔——仅当调用方
     * 确认目标文件仍是本请求占下的未写入空占位时才回收；换名之后 targetFile
     * 已是完成品，按文件大小猜测会误删合法的 0 字节下载文件。
     */
    private fun writeDownloadFailure(
        clientOut: OutputStream,
        pages: DownloadPageRenderer,
        e: Exception,
        targetFile: File?,
        reclaimPlaceholder: Boolean,
    ) {
        if (targetFile != null && reclaimPlaceholder) runCatching { targetFile.delete() }
        Log.w(TAG, "download failed: ${e.javaClass.simpleName}")
        runCatching { onDownloadFinished?.invoke(false, "io") }
        writeSimplePage(clientOut, pages.failureHtml(e.javaClass.simpleName), pages.backLabel())
    }

    /**
     * 目标名原子占位：候选名 `createNewFile` 成功即占有（失败 = 已被占用，
     * 按 ` (n)` 消解后重试，候选名内部自带字节上限重截）。
     */
    private fun claimTarget(dir: File, name: String): File {
        var candidate = name
        var index = 1
        while (true) {
            val file = File(dir, candidate)
            if (file.createNewFile()) return file
            candidate = DownloadFileName.indexedCandidate(name, index)
            index += 1
        }
    }

    /** 清理超过 [STALE_PART_MILLIS] 未动的 `dl-*.part` 临时文件（失败忽略）。 */
    private fun cleanupStalePartFiles(dir: File) {
        val staleBefore = System.currentTimeMillis() - STALE_PART_MILLIS
        dir.listFiles { file ->
            file.isFile && file.name.startsWith(DOWNLOAD_PART_PREFIX) &&
                file.name.endsWith(DOWNLOAD_PART_SUFFIX)
        }?.forEach { part ->
            if (part.lastModified() < staleBefore) runCatching { part.delete() }
        }
    }

    /**
     * 极简结果页：同源 HTML，渲染器把它当普通文档呈现。成功与失败一律回
     * 200——非 2xx 会被 systemWebView 当主文档失败处理（触发门禁自愈重载，
     * 把页面弹回根页或把会话置失败），成败改由页面文案区分。
     */
    private fun writeSimplePage(
        clientOut: OutputStream,
        message: String,
        backLabel: String? = null,
        autoBack: Boolean = false,
    ) {
        val paragraphs = message.lines().joinToString("") { "<p>${escapeHtml(it)}</p>" }
        val body = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("</head><body>").append(paragraphs)
            if (backLabel != null) {
                append("<p><a href=\"javascript:history.back()\">")
                append(escapeHtml(backLabel))
                append("</a></p>")
            }
            if (autoBack) {
                append("<script>setTimeout(function(){history.back()},900)</script>")
            }
            append("</body></html>")
        }.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        clientOut.write(head.toByteArray(Charsets.ISO_8859_1))
        clientOut.write(body)
        clientOut.flush()
    }

    /** 结果页文案的最小转义：文件名来自上游头，不得携带可执行标记。 */
    private fun escapeHtml(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private companion object {
        const val TAG = "LoopbackProxy"
        const val BIND_ATTEMPTS = 4
        const val BIND_RETRY_DELAY_MILLIS = 250L
        const val BACKLOG = 32
        const val CONNECT_TIMEOUT_MILLIS = 15_000

        /** 下载临时文件的前后缀（`createTempFile` 产出名形如 `dl-<随机>.part`）。 */
        const val DOWNLOAD_PART_PREFIX = "dl-"
        const val DOWNLOAD_PART_SUFFIX = ".part"

        /** 陈旧 `.part` 临时文件的清理门槛（24 小时）。 */
        const val STALE_PART_MILLIS = 24L * 60 * 60 * 1000

        fun normalizeOrigin(value: String): String {
            val uri = runCatching { URI(value.trim()) }.getOrElse {
                throw IllegalArgumentException("Upstream origin is not a valid URI: $value", it)
            }
            val scheme = uri.scheme?.lowercase() ?: ""
            val host = uri.host?.lowercase()
            if ((scheme != "http" && scheme != "https") || host.isNullOrBlank()) {
                throw IllegalArgumentException("Upstream origin must be an absolute http(s) origin: $value")
            }
            val defaultPort = if (scheme == "https") 443 else 80
            val port = uri.port.takeIf { it != -1 && it != defaultPort }
            return buildString {
                append(scheme).append("://").append(host)
                if (port != null) append(':').append(port)
            }
        }
    }
}

/** 代理启动失败：端口被占用（重试后）或上游 origin 非法；会话层据此转 Failed。 */
class ProxyStartException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 下载结果页文案提供者：代理只产出结构，文案与本地化由宿主侧组装（模块的
 * 字符串资源需要 Context，工厂在组装期用宿主上下文实现本接口）。
 */
interface DownloadPageRenderer {

    /** 「已保存」结果页文案：[downloadDirName] 下载目录名，[fileName] 落盘文件名。 */
    fun savedHtml(downloadDirName: String, fileName: String): String

    /** 失败结果页文案：[detail] 为技术细节（目录不可用 / 异常类型名）。 */
    fun failureHtml(detail: String): String

    /** 目录不可用的失败原因文案（[failureHtml] 的 detail 取值）。 */
    fun dirUnavailableReason(): String

    /** 结果页“返回”链接文案。 */
    fun backLabel(): String = "Back"
}
