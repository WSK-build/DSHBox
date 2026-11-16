package interlock.relay.core.exec.stream

import interlock.relay.core.exec.shizuku.ShizukuBackend
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import java.io.InputStream

/**
 * 实时流会话的宿主侧装配：把 Shizuku 用户服务的编码会话与 loopback HTTP
 * 服务接成一条通路。
 *
 * [start] 的链路：宿主建 pipe → 写端经 [ShizukuBackend.streamStart] 递给用户服务
 * （那边从可信屏取帧编码、往写端写 fMP4）→ 读端包进 [LoopbackStreamServer.Source]，
 * 由 HTTP 服务按连接转发。token 只在内存里：[StartOutcome.Running] 交出的
 * [LoopbackStreamServer] 就是 token 的唯一出口，拼完整播放地址是调用方（未来的
 * DSH 面板）的事。
 *
 * **收场责任在本类（读端持有方）**：worker 阻塞在 pipe write 上时，设备侧对写端的
 * close/flush 都可能自挂 —— 只有宿主关掉读端，内核才会把阻塞的 write 唤醒成 EPIPE。
 * 因此 [stop] 与宿主 watchdog 的第一步都是关读端；服务端 watchdog 只是可中断段的
 * 补充，**不承担也无法承担解阻塞**。宿主进程死亡时内核自动关 fd，同样走这条通路。
 *
 * 并发与代际：每届会话记一个 [generation]。start 在锁内领号、锁外建（设备事务与
 * 端口绑定都可能慢），落账前复查号 —— 期间发生过 [stop] 就把自己刚建的会话整套
 * 退掉，绝不留下设备侧在编、宿主侧无人消费的流。
 *
 * **诊断埋点**：本类只产出「会话读数」（[StreamSession]），写到哪里由装配方注入的
 * [StreamSessionLog] 决定 —— 控制器自己不碰任何具体的日志实现，也因此可以在纯 JVM
 * 上用假设备/假 log 把开流失败、停流、看门狗、EOF 四条路径穷举一遍。
 */
interface StreamControl {
    sealed class StartOutcome {
        /** 服务已在跑（重复 start 幂等）。token 经 [StreamHandle.token] 取。 */
        data class Running(val stream: StreamHandle) : StartOutcome()

        /** 没开成：设备侧拒绝（reason 来自服务端）、本地绑定失败，或与 stop 竞态后自退。
         * [code] 结构化拒绝码（如 CODE_PROTECTED_APP），控制端点据此映射状态码。 */
        data class Failed(val reason: String, val code: String? = null) : StartOutcome()
    }

    suspend fun start(waitMs: Long): StartOutcome

    /**
     * 停流。回 true 表示收场已确认（设备回执 1/0，或通道已死本来就没会话）；
     * false 表示远端回执丢失（会话在拆除路上）。
     */
    fun stop(): Boolean

    /** 此刻是否有活会话（EOF/stop/watchdog 收场后为 false）。 */
    val running: Boolean

    /**
     * 最近一次会话尝试的**宿主侧会话序号**（"S3" 这样的计数，不是 token）。
     *
     * 控制端点用它把「对浏览器回了什么」与控制器那几条设备侧读数对到同一场会话上。
     * 没有会话（比如别处直接实现本接口）时为 null。
     */
    val sessionLabel: String? get() = null
}

class TrustedStreamController(
    /** 设备侧会话的后端面：控制器只认这一面，设备依赖（binder / pipe）不在这里。 */
    private val device: StreamDevice,
    /**
     * 「受保护应用」名单的实时读数（**数据面守卫**）：start 之前
     * 先认那块屏此刻停在谁家；读不到按 fail-closed 拒。名单为空时零查询。
     * 面板豁免 仅限能力路径的 UI 操作，不延伸到流/输入入口——控制 token 随
     * 通道 bind 给沙盒的 run/ 目录（同 uid 可读），tabToken 只是会话归属凭据、
     * 不是沙盒隔离边界；要放行需宿主侧用户确认或认证中继（未实现）。
     */
    private val protectedPackages: () -> Set<String> = { emptySet() },
    /** 可信屏此刻顶层包名的阻塞读数（看门狗线程没有协程上下文，只能用阻塞版）。 */
    private val topPackage: () -> String? = { null },
    /** 运行日志。开流/流中拒绝在这里记一条；可空只为装配与测试的装配自由。 */
    private val runLog: RunLog? = null,
    /**
     * 会话诊断读数的写入口。宿主侧由装配方注入写运行日志的那一支；
     * 缺省则不写 —— 控制器自己不碰 android.util.Log。
     */
    private val sessionLog: StreamSessionLog? = null,
    /** 单调时钟（毫秒）：会话时长与看门狗余量都按它算。 */
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /**
     * 看门狗等多久（会话起点、当前时刻 → 等待量）。生产用 [streamWatchdogDelayMs]；
     * 测试注入短预算，把「到点收场」这条路径在毫秒级走完。
     */
    private val watchdogDelayMs: (Long, Long) -> Long = { startedAtMs, nowMs ->
        streamWatchdogDelayMs(startedAtMs, nowMs)
    },
) : StreamControl {

    private class Active(
        val server: LoopbackStreamServer,
        val readEnd: StreamReadEnd,
        val startedAtMs: Long,
        val label: String,
    )

    private var session: Active? = null

    /** 会话代际：每次 start 落账与每次 stop/EOF 清账都递增；start 落账前复查。 */
    private var generation = 0L

    /** 宿主侧会话序号源：只增计数，产出 "S1" 这样的序号（不是 token）。 */
    private val sessionSeq = java.util.concurrent.atomic.AtomicLong(0)

    /** 最近一次会话尝试的序号（成功或失败都留痕，控制端点据此对账）。 */
    @Volatile
    private var lastLabel: String? = null

    override val sessionLabel: String? get() = lastLabel

    override val running: Boolean
        get() = synchronized(this) { session != null }

    /**
     * 开一条流。同一时刻至多一条：已在跑返回现有会话（设备侧本来就是单会话，
     * 宿主先挡掉重复的 start 事务）。
     */
    override suspend fun start(waitMs: Long): StreamControl.StartOutcome {
        val effectiveWait = if (waitMs <= 0) DEFAULT_WAIT_MS else waitMs
        val label = "S" + sessionSeq.incrementAndGet()
        lastLabel = label
        val startedAtMs = now()
        // 数据面守卫（**恢复**）：开流之前先认那块屏此刻停在谁家。名单
        // 非空而顶层包读不到时按 fail-closed 拒——「无法确认顶层应用」不能当成「不在
        // 名单里」。拒绝带 code=protected_app，控制端点据此回 403（不建屏、不发
        // stream token）；面板豁免  仅限能力路径的 UI 操作，不延伸到流/输入入口
        // （tabToken 不是沙盒隔离边界，见构造函数注）。
        val protected = protectedPackages()
        if (protected.isNotEmpty()) {
            val top = topPackage()
                ?: run {
                    logProtectedBlock(BLOCKED_AT_STREAM_START, null)
                    val reason = "cannot confirm the app in front of the trusted display; " +
                        "the stream was not started"
                    logSession(label, StreamSessionPhase.START, "failed", SHORT_BLOCKED, startedAtMs)
                    return StreamControl.StartOutcome.Failed(reason, CODE_PROTECTED_APP)
                }
            if (top in protected) {
                logProtectedBlock(BLOCKED_AT_STREAM_START, top)
                val reason = "the app in front of the trusted display (" + top + ") is on the user's " +
                    "protected-apps list: panel preview and input are not available while it is in front"
                logSession(label, StreamSessionPhase.START, "failed", SHORT_BLOCKED, startedAtMs)
                return StreamControl.StartOutcome.Failed(reason, CODE_PROTECTED_APP)
            }
        }
        val myGeneration: Long
        synchronized(this) {
            session?.let { return StreamControl.StartOutcome.Running(it.server) }
            generation += 1
            myGeneration = generation
        }
        var opened = device.open(effectiveWait)
        // 沙箱/通道重启后，宿主已无会话，但 Shizuku 服务里可能还留着上一届流会话
        // （旧 Node 随沙箱死了，没人发 /stop）。此时服务端回 "already running"，
        // 不处理就会让面板永久 502，只能重启整个 App。宿主侧无会话 ⇒ 这条是孤儿，
        // 停掉再开一次；若服务端确有别的活消费者，它不会走到这里（宿主 session 非空）。
        if (opened is StreamDevice.OpenResult.Failed && isStaleStreamConflict(opened.reason)) {
            device.close(STALE_STREAM_STOP_WAIT_MS)
            kotlinx.coroutines.delay(STALE_STREAM_RETRY_DELAY_MS)
            opened = device.open(effectiveWait)
        }
        if (opened is StreamDevice.OpenResult.Failed) {
            logSession(label, StreamSessionPhase.START, "failed", opened.reason, startedAtMs)
            return StreamControl.StartOutcome.Failed(opened.reason)
        }
        val open = opened as StreamDevice.OpenResult.Opened
        val readEnd = open.readEnd
        val source = object : LoopbackStreamServer.Source {
            override val metadata: String = open.metadata

            override fun open(): InputStream? = readEnd.openStream()

            override fun onStopped() {
                releaseLocal(readEnd)
            }

            override fun onStreamEnded() {
                endOfStream(readEnd)
            }
        }
        val created = LoopbackStreamServer(source)
        if (!created.start()) {
            // 绑不上端口：设备侧会话原样退掉，不留一条没有出口的流。
            runCatching { readEnd.close() }
            device.close(STOP_WAIT_MS)
            logSession(label, StreamSessionPhase.START, "failed", "loopback bind failed", startedAtMs)
            return StreamControl.StartOutcome.Failed("loopback bind failed; the device-side session was stopped")
        }
        synchronized(this) {
            if (generation != myGeneration) {
                // 锁外建会话的这段时间里发生过 stop：整套退掉自己刚建的会话，
                // 不把设备侧留在"在编码、没人消费"的悬空态。
                runCatching { created.stop() }
                runCatching { readEnd.close() }
                device.close(STOP_WAIT_MS)
                logSession(label, StreamSessionPhase.START, "failed", "start raced with stop", startedAtMs)
                return StreamControl.StartOutcome.Failed("stream start raced with stop; the new session was stopped")
            }
            session?.let { existing ->
                runCatching { created.stop() }
                runCatching { readEnd.close() }
                device.close(STOP_WAIT_MS)
                return StreamControl.StartOutcome.Running(existing.server)
            }
            val active = Active(created, readEnd, startedAtMs, label)
            session = active
            // 宿主 watchdog 在落账之后起：到点关读端（解服务端阻塞写）→ 停服务 → 清账。
            Thread({ hostWatchdog(active) }, "relay-stream-watchdog").apply {
                isDaemon = true
                start()
            }
            logSession(label, StreamSessionPhase.START, "ok", "", startedAtMs, dims = StreamSession.dimensionsFrom(open.metadata))
            return StreamControl.StartOutcome.Running(created)
        }
    }

    /** 停流：**先关读端**（解服务端阻塞写的唯一手段）→ 停服务 → 远端 stop。幂等。 */
    override fun stop(): Boolean {
        val current = synchronized(this) {
            generation += 1
            val existing = session
            session = null
            existing
        } ?: return true
        runCatching { current.readEnd.close() }
        current.server.stop()
        val acknowledged = device.close(STOP_WAIT_MS)
        logSession(
            current.label,
            StreamSessionPhase.STOP,
            if (acknowledged) "confirmed" else "unknown",
            "",
            current.startedAtMs,
            current.server.bytesWritten,
        )
        return acknowledged
    }

    /**
     * 宿主 watchdog：到点按 [stop] 的同一顺序收场。等待期间会话被 stop/EOF 收走就
     * 静默退出 —— 动作的归属要能对账，watchdog 只收自己看到的那一届。
     *
     * （恢复）：流期间每 [PROTECTED_RECHECK_MS] 复查一次顶层包——顶层
     * 切到受保护应用立即停流；连续 [STREAM_PROTECTED_MISS_LIMIT] 次读不到也按
     * fail-closed 停（不能把"读不到"当成"不在名单里"继续播）。
     */
    private fun hostWatchdog(watch: Active) {
        val waitMs = watchdogDelayMs(watch.startedAtMs, now())
        var waited = 0L
        var sinceProtectedCheck = PROTECTED_RECHECK_MS
        var topReadMisses = 0
        while (waited < waitMs) {
            val step = minOf(WATCHDOG_POLL_MS, waitMs - waited)
            if (synchronized(this) { session !== watch }) return
            Thread.sleep(step)
            waited += step
            // 数据面守卫（恢复）：会话中途切到受保护应用，2 秒内停流；
            // 之后的 /input 因无会话一律 409，落不到屏上。
            sinceProtectedCheck += step
            if (sinceProtectedCheck >= PROTECTED_RECHECK_MS) {
                sinceProtectedCheck = 0
                val protected = protectedPackages()
                if (protected.isNotEmpty()) {
                    val observed = topPackage()
                    val verdict = protectedStreamWatchdogStop(
                        protected, observed, topReadMisses,
                    )
                    topReadMisses = verdict.second
                    if (verdict.first) {
                        logProtectedBlock(BLOCKED_AT_STREAM_WATCHDOG, observed)
                        teardown(watch, SHORT_BLOCKED, "failed")
                        return
                    }
                }
            }
        }
        teardown(watch, "host watchdog expired", "timeout")
    }

    /**
     * 现有收场通路的复用点：先关读端（解服务端阻塞写的唯一手段）→ 停服务 → 远端 stop。
     * 到点收场与 stop 走的是同一段，不另造第二条拆除路径。
     */
    private fun teardown(watch: Active, reason: String, result: String) {
        val current = synchronized(this) {
            if (session !== watch) return
            generation += 1
            session = null
            watch
        }
        runCatching { current.readEnd.close() }
        current.server.stop()
        device.close(STOP_WAIT_MS)
        logSession(
            current.label,
            StreamSessionPhase.WATCHDOG,
            result,
            reason,
            current.startedAtMs,
            current.server.bytesWritten,
        )
    }

    /** 转发线程报的 EOF：会话已无源可播，清账并停掉只剩 409 的服务（读端已关）。 */
    private fun endOfStream(readEnd: StreamReadEnd) {
        val current = synchronized(this) {
            val existing = session
            if (existing?.readEnd === readEnd) {
                session = null
                generation += 1
                existing
            } else null
        } ?: return
        current.server.stop()
        // 收场原因由转发线程定（设备侧 EOF / 客户端走了 / 会话已被消费）：
        // 这几种在面板上看起来一样，日志里必须能分开。
        logSession(
            current.label,
            StreamSessionPhase.EOF,
            current.server.endReason.result,
            current.server.endReason.note,
            current.startedAtMs,
            current.server.bytesWritten,
        )
    }

    /** 服务停了：只关属于自己的那份读端（世代不对的描述符不是自己的账）。 */
    private fun releaseLocal(readEnd: StreamReadEnd) {
        synchronized(this) {
            if (session?.readEnd === readEnd) session = null
        }
        runCatching { readEnd.close() }
    }

    /**
     * 记一条会话读数（写口缺省则不写）。
     *
     * 序号用宿主侧会话序号（不是 token、不是端口）：日志要能复制出去而不带凭据。
     */
    private fun logSession(
        label: String,
        phase: String,
        result: String,
        reason: String = "",
        startedAtMs: Long = 0L,
        bytes: Long? = null,
        dims: StreamSessionDims? = null,
    ) {
        val sink = sessionLog ?: return
        sink.write(
            StreamSession(
                session = label,
                phase = phase,
                result = result,
                reason = reason,
                bytes = bytes,
                durationMs = now() - startedAtMs,
                sourceW = dims?.sourceW,
                sourceH = dims?.sourceH,
                encodeW = dims?.encodeW,
                encodeH = dims?.encodeH,
            ),
        )
    }

    /**
     * 拒绝的一条运行日志：目标包与层级（[BLOCKED_AT_STREAM_START] /
     * [BLOCKED_AT_STREAM_WATCHDOG]），字段与各后端同事件同口径；读不到顶层包时目标记
     * unknown。完整名单不进日志。流没有 CapabilityId，cap 固定记 [STREAM_BLOCK_CAP]。
     */
    private fun logProtectedBlock(where: String, targetPackage: String?) {
        runLog?.warn(
            LogSubsystem.BACKEND,
            LogEvent.PACKAGE_PROTECTED_BLOCKED,
            "cap" to STREAM_BLOCK_CAP,
            "target" to (targetPackage ?: ShizukuBackend.UNKNOWN_FIELD),
            "where" to where,
        )
    }

    private companion object {
        const val DEFAULT_WAIT_MS = 3_000L
        const val STOP_WAIT_MS = 3_000L

        /** 宿主 watchdog 的睡眠步长：会话清账后最多这么久退出。 */
        private const val WATCHDOG_POLL_MS = 500L
    }
}

/**
 * 服务端“已有一条流会话”的拒绝是不是**孤儿会话**冲突。宿主在调用
 * 前已确认自己没有会话，所以这类拒绝只可能来自沙箱重启后残留的旧会话；停掉
 * 再重试一次即可，不能丢给面板变成永久 502。
 */
internal fun isStaleStreamConflict(reason: String): Boolean =
    reason.contains("already running", ignoreCase = true) ||
        reason.contains("still draining", ignoreCase = true)

/** 停孤儿流会话的等待上限；服务端 stop 通常很快，超时也继续重试一次。 */
internal const val STALE_STREAM_STOP_WAIT_MS = 2_000L

/** 停孤儿会话后等它把编码器/显示释放干净，再开新会话。 */
internal const val STALE_STREAM_RETRY_DELAY_MS = 200L

/**
 * 宿主会话 watchdog 的等待量（纯逻辑，JVM 可测）：等余量、到点即收、
 * 时钟回拨/迟到钳到 ≥1ms。对"堵在 pipe write 上"的服务端会话，宿主到点
 * 关读端是**唯一**有效的收场 —— 阻塞 write 只认读端消失（EPIPE）。
 */
internal fun streamWatchdogDelayMs(startedAtMs: Long, nowMs: Long): Long =
    (startedAtMs + MAX_STREAM_SESSION_MS - nowMs).coerceIn(1L, MAX_STREAM_SESSION_MS)

internal const val MAX_STREAM_SESSION_MS = 30L * 60L * 1_000L

/** 数据面守卫的拒绝码：控制端点据此回 403（面板按 code 停止自动开流）。 */
internal const val CODE_PROTECTED_APP = "protected_app"

/** 命中名单时的会话读数短原因：只说"被受保护名单挡住"，不写包名。 */
internal const val SHORT_BLOCKED = "blocked by the protected-apps list"

/** 流期间顶层包复查的间隔（看门狗线程内，低频率）。 */
internal const val PROTECTED_RECHECK_MS = 2_000L

/** 流的拒绝日志里 cap 字段的取值——实时流没有 CapabilityId，用这一固定名。 */
internal const val STREAM_BLOCK_CAP = "stream"

/** 流拒绝日志的 where 取值——开流前与流中看门狗两个层级。 */
internal const val BLOCKED_AT_STREAM_START = "stream_start"
internal const val BLOCKED_AT_STREAM_WATCHDOG = "stream_watchdog"

/**
 * 流看门狗复查的停流判据（纯函数，JVM 可测）。回（是否停流，新的连续未读到次数）。
 *
 * - 名单为空：不停，计数归零（调用方在名单非空时才会问）；
 * - 顶层包命中名单：立即停——「不能操作但能预览」不允许出现；
 * - 读不到（null）：连续 [STREAM_PROTECTED_MISS_LIMIT] 次未读到才停。单次 dumpsys
 * 抖动不拆一条正在播的流；连续两次（约 4 秒）都确认不了顶层是谁，就按 fail-closed
 * 停流——不能拿「读不到」当成「不在名单里」继续播。
 * - 读到了且不在名单：不停，计数归零。
 */
internal fun protectedStreamWatchdogStop(
    packages: Set<String>,
    observed: String?,
    priorMisses: Int,
): Pair<Boolean, Int> = when {
    packages.isEmpty() -> false to 0
    observed != null && observed in packages -> true to priorMisses
    observed == null -> {
        val misses = priorMisses + 1
        (misses >= STREAM_PROTECTED_MISS_LIMIT) to misses
    }
    else -> false to 0
}

/** 连续未读到顶层包多少次才按 fail-closed 停流。 */
internal const val STREAM_PROTECTED_MISS_LIMIT = 2
