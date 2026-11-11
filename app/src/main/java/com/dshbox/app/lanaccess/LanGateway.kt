package com.dshbox.app.lanaccess

import com.dshbox.remote.proxy.HttpWire
import com.dshbox.remote.proxy.WsTunnel
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 局域网访问网关（DSH 的反向代理）：绑定 Wi-Fi 侧私网 IPv4 的固定端口，
 * 把局域网来访者（门禁 cookie 或入口兑换 `?k=`）转接到
 * `127.0.0.1:<当前 DSH 端口>`。
 *
 * 复用 remote-connection 既有代理管线构件（不做复制）：
 * [HttpWire]/[WsTunnel]/代理头容器。管线本身是本网关的局域网语义
 * （门禁 cookie、上游 token 注入、单请求单连接），与回环代理的设备会话
 * 语义（cookie jar / 门禁注入器 / 下载判定）不互相搭接。
 *
 * **DSH 会话 token 的注入条件（取证见 [LanUpstream]）**：请求未携带
 * DSH 会话 cookie（名称以 `dsh-auth-` 开头）**且未带网关兑换标记
 * cookie（[EXCHANGE_MARK_COOKIE_NAME]，或本跳是兑换回跳 `?dshreload=1`）**
 * 才注入 `?token=`。主判定是按会话 cookie 收敛；保险标记堵死回收后的
 * 重复注入——若上游 DSH 的真实 cookie 前缀与取证不符（会话 cookie
 * 判定失效），兑换回跳注入一次后上游依旧不种会话，继而为无会话无标记
 * 豁免的请求一律 401 收场（可诊断），而不是无限 303 循环。依据（DSH
 * 0.2.1-rc.5，`@deepseek-ai/dsh-client-connection` `authorizeIndex`）：
 * 带 token 的 `GET /` 无论是否已认证都 303 `./`，只在未认证的首跳里顺带
 * `Set-Cookie: dsh-auth-*`。逐请求无条件注入会让 `/` 在
 * 「注入 → 303 ./（无 token）→ 注入」间死循环页面打不开；按会话 cookie
 * 收敛后，每个客户端会话只在首跳兑换一次。失效会话自愈同理：兑换成功
 * 的 302 会对请求携带的每个 `dsh-auth-*` cookie 名回 `Max-Age=0`，DSH
 * 重启/换 token 后旧会话 cookie 一轮兑换即被清除，重新兑换即可恢复。
 *
 * 已知限制（转由状态层驱动，本类只做转发与门禁）：
 * - 上游地址每次转发现取（端口自适应即跟随，网关不解绑）；
 * - 单请求单连接（Connection: close 看 rewriteOutbound），SSE 实时可见；
 * - 该端口重启需要拒尽 [start]，其失败属显式报错（[LanGatewayException]）。
 *
 * **在线计数按客户端 IP 聚合为设备**：浏览器对同一页面会并行开
 * 多条连接、且本网关每请求一连接，直接数连接会一台设备报多条——回调与
 * [connectedDeviceCount] 报 distinct 活跃来源 IP 数（设备数），连接数
 * [connectionCount] 保留为诊断口径。两个计数同刻更新（连接受理时 IP 入
 * 账、收尾出账；[stop] 清零），回写非负。聚合口径由 [LanDeviceTracker]
 * 承担（纯账目、可单测），本网关只负责在受理/收尾两处调用它。
 */
class LanGateway(
    /** 绑定地址（Wi-Fi 侧私网 IPv4，由 [LanBindAddress] 选出）。 */
    private val bindAddress: InetAddress,
    /** 绑定端口（固定 39876）。 */
    internal val port: Int,
    /** 门禁（密钥/限速/兑换裁决）。 */
    private val gate: LanAccessGate,
    /**
     * 每次转发请求时现取上游视图：当前 DSH 基础地址（含端口）与
     * launch token —— 端口跟随由此生效，网关不因端口/重启变化而解绑。
     */
    private val upstreamProvider: () -> Pair<LanUpstream.Upstream, String?>,
    /**
     * 在线变化回调（设备数与连接数在每次真实增减后的现值）：由控制层
     * 回写到可观察状态，UI 显示「N 台设备在线」与「全部断开」可用性；
     * 连接数保留为诊断口径。
     */
    private val onDevicesChanged: (Int, Int) -> Unit = { _, _ -> },
) {

    private val serverSocket = AtomicReference<ServerSocket?>()
    private val executor = AtomicReference<ExecutorService?>()
    private val started = AtomicBoolean(false)

    /**
     * 并发上限的原子受理计数（先增再核，超界即回拨）：只服务「是否受理」
     * 这一件事——受理与 handler 收尾配套增减。观测口径的设备/连接两本账
     * 在 [tracker] 里（那两本账的计数构造上恒非负）。
     */
    private val openCount = AtomicInteger(0)

    /** 是否已回写过在线数（[stop] 据此决定要不要补发一次归零）。 */
    private val reportedAny = AtomicBoolean(false)
    private val pooledConnections: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    /** 在途上游 socket 的独立观察集合（测试口径：活跃上游数应归零）。 */
    private val upstreamSockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())

    /** 在线设备账（按来源 IP 聚合）：设备数与连接数的唯一账目。 */
    private val tracker = LanDeviceTracker()

    /** 当前在线连接数（诊断口径）。 */
    val connectionCount: Int get() = tracker.connectionCount

    /** 在线设备数（distinct 活跃来源 IP 数；UI 口径）。 */
    val connectedDeviceCount: Int get() = tracker.deviceCount

    /** 设备账快照（观察面：IP → 活跃连接数，测试断言聚合口径）。 */
    internal val trackedDeviceSessions: Map<String, Int>
        get() = tracker.sessionCounts

    /** 设备账的最后活跃时刻快照（观察面：诊断/测试）。 */
    internal val trackedDeviceLastActive: Map<String, Long>
        get() = tracker.lastActiveMillisByIp

    /** 已绑定地址文本（展示用，`<ip>:<port>`）。 */
    val boundHostPort: String get() = "${bindAddress.hostAddress}:$port"

    /** 活跃上游 socket 数（观察面：正常完成后必须归零，供测试断言）。 */
    internal val observedActiveUpstreamCount: Int
        get() = synchronized(upstreamSockets) { upstreamSockets.size }

    /** 当前登记在册的上游 socket 快照（观察面：完成后的集合应为空）。 */
    internal val trackedUpstreamSockets: Set<Socket>
        get() = synchronized(upstreamSockets) { upstreamSockets.toSet() }

    /**
     * 启动监听。绑定失败（地址/端口不可用）抛 [LanGatewayException]，由
     * 调用方显式报错——本网关不换端口不静默降级，「地址:端口」是局域网
     * 访客的稳定入口。
     */
    fun start() {
        check(started.compareAndSet(false, true)) { "LanGateway already started" }
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, port), BACKLOG)
            }
        } catch (e: IOException) {
            started.set(false)
            throw LanGatewayException(
                "Failed to bind LAN gateway on ${bindAddress.hostAddress}:$port",
                e,
            )
        }
        serverSocket.set(server)
        val pool = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "dshbox-lan-gateway").apply { isDaemon = true }
        }
        executor.set(pool)
        pool.execute {
            try {
                acceptLoop(server)
            } catch (_: Throwable) {
                // stop() 关闭监听套接字（SocketException）即正常退出
            }
        }
    }

    /** 停止监听并断开全部在途连接（含 WS 隧道）；设备账与计数清零；幂等。 */
    fun stop() {
        serverSocket.getAndSet(null)?.close()
        executor.getAndSet(null)?.shutdownNow()
        synchronized(pooledConnections) {
            pooledConnections.toList().forEach { runCatching { it.close() } }
            pooledConnections.clear()
        }
        synchronized(upstreamSockets) {
            upstreamSockets.toList().forEach { runCatching { it.close() } }
            upstreamSockets.clear()
        }
        tracker.clear()
        openCount.getAndSet(0)
        if (reportedAny.getAndSet(false)) {
            onDevicesChanged(0, 0)
        }
        started.set(false)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (started.get()) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            // 并发上限原子口径：检查+自增在同一原子段（先增再核，超界即回拨）
            // ——不存在「检查过、自增晚于另一线程回拨」的竞态；计数只在
            // 增/减配套的线程里变动，不可能为负。
            val current = openCount.incrementAndGet()
            if (current > MAX_CONNECTIONS) {
                openCount.decrementAndGet()
                runCatching {
                    writeSimpleResponse(client.getOutputStream(), 429, "Too many connections")
                }
                runCatching { client.close() }
                continue
            }
            // 设备账入账在**受理时**（不等 handler 真正开跑）：设备与连接
            // 两本账同刻入账，回写的 (设备, 连接) 对始终自洽。
            val clientIp = client.inetAddress?.hostAddress ?: ""
            tracker.onConnect(clientIp)
            reportChanges()
            val pool = executor.get()
            if (pool == null) {
                // 停机竞态：没人接手本连接 → 立刻收口（socket 与两本账一并回退）
                tracker.onDisconnect(clientIp)
                openCount.decrementAndGet()
                reportChanges()
                runCatching { client.close() }
                continue
            }
            pool.execute {
                try {
                    handleConnection(client, clientIp)
                } finally {
                    // 设备账出账（与入账配对；[stop] 清账后的迟到出账是空操作）
                    tracker.onDisconnect(clientIp)
                    openCount.decrementAndGet()
                    reportChanges()
                    runCatching { client.close() }
                    pooledConnections.remove(client)
                }
            }
        }
    }

    /**
     * 设备/连接计数上报（非负夹紧）：两本账都由 [LanDeviceTracker] 维护，
     * 其计数构造上即非负；stop() 与在途 handler 的收尾竞态里 handler 的
     * 出账可能晚于 stop 的清账——出账对未在册 IP 是空操作，回写因此永远
     * 不会出现负数。
     */
    private fun reportChanges() {
        reportedAny.set(true)
        val counts = tracker.snapshot()
        onDevicesChanged(counts.devices, counts.connections)
    }

    private fun handleConnection(client: Socket, clientIp: String) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = IDLE_TIMEOUT_MILLIS
            pooledConnections.add(client)
            val input = BufferedInputStream(client.getInputStream())
            val head = HttpWire.readHead(input) ?: return

            // 1. 门禁段：未通过时回按响应头（302 兑换 / 401 简单页 / 429 限速
            //    拖延 1s），绝不把上游与 DSH 的内容洩漏给未认证方。
            if (!serveAuth(head, clientIp, client.getOutputStream())) return

            // 2. 升级判定先于改写：升级请求保留原始 Connection/Upgrade 头。
            val wsUpgrade = WsTunnel.isUpgradeRequest(head.headers)

            // 3. 网关自有 cookie 判定与剥离：防回环标记的判定取自原始
            //    Cookie 头（剥离会把它一并带走）；随后剥除门禁 cookie 与
            //    兑换标记（局域网语义只在本跳有意义），其余 cookie（上游
            //    种下的会话 cookie）原样转发。
            val exchangedFinally =
                LanUpstream.hasCookieNamed(head.headerValues("Cookie"), EXCHANGE_MARK_COOKIE_NAME)
            stripGatewayCookies(head)
            // token 注入条件：仅当请求未带 DSH 会话 cookie（见类 KDoc 取证
            // 与 [LanUpstream.originFormWithToken] 的注入依据），且未带
            // 兑换标记（防循环保险，见类 KDoc）。
            val hasUpstreamSession =
                LanUpstream.hasUpstreamSessionCookie(head.headerValues("Cookie"))

            // 4. 转发前剥离逐跳协议头与代理转发头：TE/Trailer（逐跳帧头，
            //    转发语义失真）、Proxy-Authorization（本代理无出站代理凭据，
            //    不应上送）、X-Forwarded-*（访客地址不宜继续上泄）。
            stripForwardedHeaders(head)

            // 5. 请求体先读（chunked 解块后按 Content-Length 转发，与回环代理
            //    同口径）；WS 升级请求不带请求体，跳过。超限（与
            //    Content-Length 同一 64MB 上限）直接 413 并终结连接。
            val body = if (!wsUpgrade) {
                try {
                    HttpWire.readRequestBody(input, head)
                } catch (e: HttpWire.RequestBodyTooLargeException) {
                    writeSimpleResponse(client.getOutputStream(), 413, "413 Payload Too Large")
                    return
                }
            } else null
            if (body != null) {
                head.setHeader("Content-Length", body.size.toString())
                head.removeHeaders("Transfer-Encoding")
            }

            // 6. 出站改写 + 上游 token 注入 + 转发。兑换回跳的
            //    `?dshreload=1` 与门禁密钥不进 DSH 请求 URL/日志：两者在
            //    转发前剥除；`?k=` 若仍挂在已放行的请求上（含空值形态）也
            //    一并收敛。
            val (target, token) = upstreamProvider()
            val (method, targetForm) = requestLineParts(head)
                ?: return
            val freshExchangeHop = queryValueOf(targetForm, EXCHANGE_RELOAD_PARAM) == "1"
            val cleanedTarget =
                LanUpstream.stripParams(
                    targetForm,
                    setOf(LanAccessSecrets.KEY_PARAM, EXCHANGE_RELOAD_PARAM),
                )
            // 注入条件（见类 KDoc 取证）：无 DSH 会话 cookie，且无保险
            // 标记或本跳是兑换回跳（首跳即本次会话唯一注入点；随后的无会
            // 话请求一律不再注入——上游不发会话 cookie 的场面停 401）。
            val injected = if (token != null && !hasUpstreamSession &&
                (!exchangedFinally || freshExchangeHop)
            ) {
                LanUpstream.originFormWithToken(cleanedTarget, token)
            } else {
                cleanedTarget
            }
            rewriteOutbound(head, target)
            if (injected != targetForm) head.rewriteRequestTarget(injected)
            // 上游 socket 的创建与两集合登记在本方法内完成（任何异常路径的
            // finally 都能关闭并移出）——[forwardAndRelay] 只负责收发与泵。
            upstream = connectUpstream(target)
            forwardAndRelay(client, input, head, body, upstream, target, wsUpgrade)
        } catch (_: Exception) {
            // 单连接故障（网络/解析/超时）不影响网关：关闭本连接即可
        } finally {
            // 上游 socket 与 client 一并收口（正常转发、WS 泵与故障路径
            // 都回到这里）：每个请求不留存活的 FD。幂等——stop() 并行
            // 清集合/关闭 socket 也安全（remove 与 close 均可重复）。
            upstream?.let { socket ->
                runCatching { socket.close() }
                pooledConnections.remove(socket)
                upstreamSockets.remove(socket)
            }
        }
    }

    /**
     * 创建上游 socket（connect 成功后才登记进 [pooledConnections] 与
     * [upstreamSockets]）：connect 失败的半成品 socket 由本处自行关闭，
     * 不占用观察面。返回的 socket 交给 [handleConnection] 的 finally 统一收口。
     */
    private fun connectUpstream(target: LanUpstream.Upstream): Socket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MILLIS)
        } catch (e: Exception) {
            // connect 抛出：半成品 FD 不外漏，注册面也无该 socket。
            runCatching { socket.close() }
            throw e
        }
        pooledConnections.add(socket)
        upstreamSockets.add(socket)
        return socket
    }

    /**
     * 门禁段：解析 `?k=` 与门禁 cookie，按 [LanAccessGate] 裁决。返回 false
     * 表示已回完响应、连接终结（不再转发）。
     */
    private fun serveAuth(head: HttpWire.HttpHead, clientIp: String, out: OutputStream): Boolean {
        val (_, targetForm) = requestLineParts(head) ?: return false
        val queryKey = queryValueOf(targetForm, LanAccessSecrets.KEY_PARAM)
        val cookies = head.headerValues("Cookie")
            .flatMap { it.split(';') }
            .mapNotNull { item ->
                val trimmed = item.trim()
                if (trimmed.startsWith("${LanAccessSecrets.COOKIE_NAME}=")) {
                    trimmed.substringAfter('=', "").takeIf { it.isNotEmpty() }
                } else null
            }
        return when (val decision = gate.decide(clientIp, queryKey, cookies)) {
            is LanAccessGate.Decision.Pass -> true
            is LanAccessGate.Decision.Login -> {
                val body = HTML_EMPTY_BODY.toByteArray(Charsets.UTF_8)
                val headText = buildString {
                    append("HTTP/1.1 302 Found\r\n")
                    // `?dshreload=1` 兑换回跳的一次性标记：紧随其后的一跳
                    // 是 token 注入的那一跳（不受保险标记闸限制）；它在转发
                    // 前被剥除，绝不流向 DSH。见类 KDoc 防循环保险。
                    append("Location: /?$EXCHANGE_RELOAD_PARAM=1\r\n")
                    append("Referrer-Policy: no-referrer\r\n")
                    append("Set-Cookie: ${LanAccessSecrets.COOKIE_NAME}=${decision.cookieValue}; HttpOnly; Path=/; SameSite=Lax; Max-Age=$COOKIE_MAX_AGE_SECONDS\r\n")
                    // 失效会话自愈：对请求携带的每个 dsh-auth-* cookie 名回
                    // Max-Age=0 清除——DSH 重启/换 token 后旧会话 cookie 已不
                    // 可信，一轮兑换即恢复（再走首跳 token 注入）。
                    LanUpstream.dshSessionCookieNames(head.headerValues("Cookie"))
                        .forEach { name ->
                            append("Set-Cookie: $name=; HttpOnly; Path=/; Max-Age=0\r\n")
                        }
                    // 防循环保险：兑换成功即下发标记 cookie（见类 KDoc）——
                    // 上游 cookie 前缀与取证不符时最多注入一次即停。
                    append("Set-Cookie: $EXCHANGE_MARK_COOKIE_NAME=$EXCHANGE_MARK_COOKIE_VALUE; HttpOnly; Path=/; SameSite=Lax; Max-Age=$COOKIE_MAX_AGE_SECONDS\r\n")
                    append("Cache-Control: no-store\r\n")
                    append("Content-Type: text/html; charset=utf-8\r\n")
                    append("Content-Length: ").append(body.size).append("\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                out.write(headText.toByteArray(Charsets.ISO_8859_1))
                out.write(body)
                out.flush()
                false
            }
            is LanAccessGate.Decision.Rejected -> {
                writeSimpleResponse(out, 401, "401 Unauthorized")
                false
            }
            is LanAccessGate.Decision.RateLimited -> {
                // 锁定窗口的延迟感：拖延约 1s 再拒绝（不给即时反馈帮撞库计时）
                try {
                    Thread.sleep(RATE_DELAY_MILLIS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                writeSimpleResponse(out, 429, "429 Too Many Requests")
                false
            }
        }
    }

    private fun requestLineParts(head: HttpWire.HttpHead): Pair<String, String>? {
        val parts = head.startLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        var target = parts[1]
        if (target.startsWith("http://") || target.startsWith("https://")) {
            // absolute-form 兼容（与回环代理同口径）：统一为 origin-form
            target = runCatching {
                val uri = URI(target)
                (uri.rawPath ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            }.getOrElse { "/" }
        }
        if (!target.startsWith("/")) return null
        return method to target
    }

    /**
     * 转发前剥离的逐跳/代理头：TE、Trailer（逐跳分帧语义，转发必失真）、
     * Proxy-Authorization（无出站代理，凭据不得上送）、X-Forwarded-*
     * （访客来源不宜继续向本机服务扩散）。
     */
    private fun stripForwardedHeaders(head: HttpWire.HttpHead) {
        head.removeHeaders("TE")
        head.removeHeaders("Trailer")
        head.removeHeaders("Proxy-Authorization")
        head.headers.map { it.name }
            .filter { it.startsWith("X-Forwarded-", ignoreCase = true) }
            .distinct()
            .forEach { head.removeHeaders(it) }
    }

    /** 出站改写：Host/Origin/Referer 指向上游；普通请求收口为单请求单连接。 */
    private fun rewriteOutbound(head: HttpWire.HttpHead, target: LanUpstream.Upstream) {
        head.setHeader("Host", target.hostHeader)
        head.firstHeader("Origin")?.let { head.setHeader("Origin", target.originText) }
        head.firstHeader("Referer")?.let { value ->
            runCatching { URI(value) }.getOrNull()
                ?.takeIf { it.scheme != null && it.host != null }
                ?.let { uri ->
                    head.setHeader(
                        "Referer",
                        target.originText +
                            (uri.rawPath ?: "") +
                            (uri.rawQuery?.let { "?$it" } ?: ""),
                    )
                }
        }
        if (!WsTunnel.isUpgradeRequest(head.headers)) {
            head.setHeader("Connection", "close")
            head.removeHeaders("Keep-Alive")
            head.removeHeaders("Proxy-Connection")
        }
    }

    /** 网关自有 cookie（门禁值与兑换标记）剥离；DSH 会话 cookie 保留。 */
    private fun stripGatewayCookies(head: HttpWire.HttpHead) {
        val cookieIndices = head.headers.withIndex()
            .filter { it.value.name.equals("Cookie", ignoreCase = true) }
            .map { it.index }
        if (cookieIndices.isEmpty()) return
        val merged = cookieIndices.flatMap { index ->
            head.headers[index].value.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
        head.removeHeaders("Cookie")
        val kept = LanUpstream.stripGatewayCookie(
            merged,
            setOf(LanAccessSecrets.COOKIE_NAME, EXCHANGE_MARK_COOKIE_NAME),
        ).filter { it.isNotEmpty() }
        if (kept.isNotEmpty()) {
            head.setHeader("Cookie", kept.joinToString("; "))
        }
    }

    /**
     * 转发上游并回传（socket 已由 [connectUpstream] 创建并登记）：
     * 普通响应流式回写；101 升级后双向字节泵。任何异常（客户端写失败、
     * 响应头超限、WS 泵故障）都冲出调用方 try — socket 的关闭与移出集合
     * 由 [handleConnection] 的 finally 统一收口。
     */
    private fun forwardAndRelay(
        client: Socket,
        clientIn: InputStream,
        head: HttpWire.HttpHead,
        body: ByteArray?,
        upstreamSocket: Socket,
        target: LanUpstream.Upstream,
        wsUpgrade: Boolean,
    ) {
        val upstreamOut = BufferedOutputStream(upstreamSocket.getOutputStream())
        upstreamOut.write(head.serialize())
        if (body != null) upstreamOut.write(body)
        upstreamOut.flush()
        val upstreamIn = BufferedInputStream(upstreamSocket.getInputStream())
        val responseHead = HttpWire.readHead(upstreamIn) ?: return

        if (wsUpgrade && responseHead.startLine.split(' ').getOrNull(1) == "101") {
            val clientOut = client.getOutputStream()
            clientOut.write(responseHead.serialize())
            clientOut.flush()
            // 升级即长连接：解除客户端 60s 空闲超时（0 = 无限，避免长会话
            // 掐断）；上游读超时给防卡死的上界（帧久未到也算终局）。
            client.soTimeout = 0
            upstreamSocket.soTimeout = WS_UPSTREAM_IDLE_TIMEOUT_MILLIS
            WsTunnel.pumpBidirectional(
                clientOut = clientOut,
                clientIn = clientIn,
                upstreamOut = upstreamOut,
                upstreamIn = upstreamIn,
            )
            // 泵结束（任一方向 EOF/故障）：套接字由 handleConnection 的
            // finally 统一关闭并移出集合。
            return
        }

        rewriteInboundHead(responseHead, target)
        val clientOut = client.getOutputStream()
        if (!wsUpgrade) {
            responseHead.setHeader("Connection", "close")
            responseHead.removeHeaders("Keep-Alive")
        }
        clientOut.write(responseHead.serialize())
        clientOut.flush()
        HttpWire.relay(upstreamIn, clientOut)
    }

    /** 入站改写：Location 绝对地址回指局域网 origin；CSP 剥 upgrade-insecure-requests。 */
    private fun rewriteInboundHead(responseHead: HttpWire.HttpHead, target: LanUpstream.Upstream) {
        val lanOrigin = "http://${bindAddress.hostAddress}:$port"
        val upstreamOrigin = "http://${target.hostHeader}"
        responseHead.firstHeader("Location")?.let { location ->
            val rewritten = rewriteLocation(location, upstreamOrigin, lanOrigin)
            if (rewritten != location) responseHead.setHeader("Location", rewritten)
        }
        val cspHeaders = responseHead.headers.withIndex()
            .filter { it.value.name.equals("Content-Security-Policy", ignoreCase = true) }
        for ((index, header) in cspHeaders) {
            val stripped = header.value.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.substringBefore(' ').equals("upgrade-insecure-requests", ignoreCase = true) }
                .joinToString("; ")
            if (stripped != header.value) {
                responseHead.headers[index] = header.copy(value = stripped)
            }
        }
    }

    /** 极简响应（鉴权拒绝/限速/满载/超限）。内容不含上游与 DSH 信息。 */
    private fun writeSimpleResponse(out: OutputStream, status: Int, text: String) {
        val phrase = when (status) {
            401 -> "Unauthorized"
            413 -> "Payload Too Large"
            429 -> "Too Many Requests"
            else -> "Service Unavailable"
        }
        val payload = simpleBody(text).toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(phrase).append("\r\n")
            append("Content-Type: text/html; charset=utf-8\r\n")
            append("Cache-Control: no-store\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        out.flush()
    }

    private fun simpleBody(text: String): String =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "</head><body><p>${text}</p></body></html>"

    /** 请求行 query 单键取值。 */
    private fun queryValueOf(target: String, name: String): String? =
        target.substringAfter('?', "").substringBefore('#')
            .split('&')
            .firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }

    private companion object {
        const val BACKLOG = 32
        const val CONNECT_TIMEOUT_MILLIS = 15_000

        /** 空闲在途连接超时（毫秒，60s；门禁后的长连接由该窗收口）。 */
        const val IDLE_TIMEOUT_MILLIS = 60_000

        /** WS 隧道建立后的上游读超时（毫秒，5 分钟）：帧久未到也按终局收口，防泵线程卡死。 */
        const val WS_UPSTREAM_IDLE_TIMEOUT_MILLIS = 5 * 60_000

        const val MAX_CONNECTIONS = 32

        /** 兑换 cookie 有效期（秒）。密钥轮换即失效。 */
        const val COOKIE_MAX_AGE_SECONDS = 7 * 24 * 3600

        /** 防循环标记 cookie 名：兑换成功后由网关种下，注入判定「无该标记」。 */
        const val EXCHANGE_MARK_COOKIE_NAME = "dshbox_lan_exchanged"

        /** 兑换回跳的一次性 query 参数（见 serveAuth Login 分支）。 */
        const val EXCHANGE_RELOAD_PARAM = "dshreload"

        /** 防循环标记 cookie 值（仅作存在性语义，不看内容）。 */
        const val EXCHANGE_MARK_COOKIE_VALUE = "1"

        /** 限速拖延。 */
        const val RATE_DELAY_MILLIS = 1_000L

        private const val HTML_EMPTY_BODY =
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
    }
}

/** 网关启动失败：绑定地址/端口不可用。调用方显式报错并保持关闭。 */
class LanGatewayException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Location 重写的纯函数（JVM 单测锁上游改写口径）：回写前先剥除 query 里
 * 的 `token` 与 `k` 参数（硬化——网关注入的令牌与门禁密钥不得回到客户端
 * URL），再把同上游的绝对地址回指局域网 origin。
 */
internal fun rewriteLocation(location: String, upstreamOrigin: String, lanOrigin: String): String {
    val stripped = LanUpstream.stripParams(
        location,
        setOf(LanUpstream.TOKEN_PARAM, LanAccessSecrets.KEY_PARAM),
    )
    val uri = runCatching { URI(stripped) }.getOrNull() ?: return stripped
    val scheme = uri.scheme?.lowercase() ?: return stripped
    if (scheme != "http" && scheme != "https") return stripped
    val host = uri.host ?: return stripped
    val defaultPort = if (scheme == "https") 443 else 80
    val port = uri.port
    val originText = buildString {
        append(scheme).append("://").append(host.lowercase())
        if (port != -1 && port != defaultPort) append(':').append(port)
    }
    if (originText != upstreamOrigin) return stripped
    val path = uri.rawPath ?: ""
    val query = uri.rawQuery?.let { "?$it" } ?: ""
    val fragment = uri.rawFragment?.let { "#$it" } ?: ""
    return lanOrigin + path + query + fragment
}
