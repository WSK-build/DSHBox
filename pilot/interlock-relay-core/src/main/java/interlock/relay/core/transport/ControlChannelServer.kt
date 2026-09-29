package interlock.relay.core.transport

import android.os.FileObserver
import interlock.relay.core.protocol.RELAY_PROTOCOL_VERSION
import interlock.relay.core.interlock.PromptCodes
import interlock.relay.core.interlock.PromptOutcome
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.storage.StorageReaper
import interlock.relay.core.runtime.wallNow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** 控制请求的操作字面量。响应里的 `op` 按它回显，字面量只在此处出现一次。 */
internal object ControlOps {
    const val SUBMIT = "submit"
    const val STATUS = "status"
    const val CANCEL = "cancel"
    const val HEALTH = "health"
    const val ASK = "ask"

    /** op 无法可信回显（文件名不合规、正文在解码前就坏掉）时的占位。 */
    const val UNKNOWN = "unknown"
}

/**
 * [LogEvent.CONTROL_OP] 的 result 字面量，封闭集合。
 *
 * 每条控制操作都要落一条「多久、什么结局」：真机排障里最难答的是「我 submit 了，
 * 通道到底接没接」——回包写失败与「宿主压根没看到这条」在助手那边长得一模一样。
 * 取值只许从这里取：op 与 result 都是本侧的判据，不透传投递内容（沿用
 * [interlock.relay.core.log.AuditLog] 的参数不落盘纪律与 RunLog 的 redaction）。
 */
internal object ControlResults {
    /** 处理走完，按回包 ok 答复。 */
    const val OK = "ok"

    /** 形状/判据不过，走了 [ControlChannelServer.reject]：重发同一条没有意义。 */
    const val REJECTED = "rejected"

    /** 宿主侧故障（落盘失败、异常）：与调用方写错参数的「拒绝」分档。 */
    const val FAILED = "failed"

    /** 取消目标不存在：无从取消，也不断言它做过什么。 */
    const val NOT_FOUND = "not_found"

    /** 请求文件还在收件箱，宿主可证未认领，已撤销。 */
    const val CANCELLED = "cancelled"

    /** 取消为时已晚，按既有终态答复。 */
    const val ALREADY_SETTLED = "already_settled"

    /** 请求已被认领，只记下取消意愿，结论由执行链自己的终态给出。 */
    const val CANCEL_REQUESTED = "cancel_requested"

    /** ask 已交给呈现层去问：回包要等用户答复或窗口到点，这一条只记"已受理抛出"。 */
    const val ACCEPTED = "accepted"

    /**
     * 全部合法取值，供读日志的人一眼看全，也供调用点登记新取值时对照。
     * （没有"表外字面量"的自动检查：那需要静态扫描源码，代价与收益不成比例。）
     */
    val LITERALS: Set<String> = setOf(
        OK, REJECTED, FAILED, NOT_FOUND, CANCELLED, ALREADY_SETTLED, CANCEL_REQUESTED, ACCEPTED,
    )
}

/** 取消请求的纯判定结果。五支决策表见 [ControlChannelLogic.cancelPlan]。 */
sealed interface CancelPlan {
    /** 状态存储里没有这条请求：无从取消，也不断言它做过什么。 */
    object NotFound : CancelPlan

    /** 已是终态：取消为时已晚，按既有终态答复。 */
    data class AlreadySettled(val phase: RequestPhase) : CancelPlan

    /** 请求文件仍在收件箱：宿主可证尚未认领它，可以给出「已取消」。 */
    object RemoveUnclaimed : CancelPlan

    /** 请求已被认领：只能记下取消意愿，结论由执行链自己的终态给出。 */
    object MarkRequested : CancelPlan
}

/**
 * 控制通道的可测核心：转写、取消判定、status 映射与 health 读数全部是无副作用的纯函数，
 * 消费循环只负责把文件与落盘效果接到这些判定上。这一层只有三类效果 —— 写控制回包、
 * 改状态记录、搬收件箱文件 —— 没有任何一条通向后端：控制通道绝不执行能力动作，
 * 「优先服务」才不会变成新的权限绕过口。
 */
internal object ControlChannelLogic {

    /**
     * 把一条 submit 转写成工作信箱的 v1 信封。执行仍走既有串行工作循环与全部闸门，
     * 转写只换信封形状：`ts` 用墙钟作展示值，有效期用宿主侧夹过的 [ControlRequest.Submit.hostTtlMs]。
     */
    fun submitEnvelopeV1(submit: ControlRequest.Submit, tsMs: Long): String = JSONObject().apply {
        put("v", RELAY_PROTOCOL_VERSION)
        put("id", submit.id)
        put("ts", tsMs)
        put("capability", submit.capability.wire)
        put("args", submit.args)
        put("ttlMs", submit.hostTtlMs)
    }.toString()

    /**
     * status 的读数映射：只转述状态存储此刻的快照，不等待审批与后端。
     * `result` 是终态完整回包 JSON，仅终态且未过期时有；过期不当未执行，阶段名如实是 EXPIRED。
     */
    fun statusPayload(state: RequestState?, targetId: String): JSONObject = JSONObject().apply {
        put("targetId", targetId)
        put("found", state != null)
        if (state != null) {
            put("state", state.phase.name)
            put("result", state.resultJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            put("mayHaveDispatched", state.mayHaveDispatched)
            put("cancelRequested", state.cancelRequested)
        }
    }

    /**
     * health 的读数映射：全部来自调用方的内存标记与目录计数，这里不再做任何观测。
     * `channelLive` 语义见 `MailboxServer.workLoopHealth`：空闲不是故障，只有
     * 「收件箱有积压而循环不推进」才是；`stalled` 把这一事实单独给出，读的人不必自己推。
     */
    fun healthPayload(
        channelLive: Boolean,
        queueDepth: Int,
        oldestQueuedAgeMs: Long?,
        inboxDepth: Int = 0,
        stalled: Boolean = false,
    ): JSONObject =
        JSONObject().apply {
            put("channelLive", channelLive)
            put("queueDepth", queueDepth)
            put("oldestQueuedAgeMs", oldestQueuedAgeMs ?: JSONObject.NULL)
            put("inboxDepth", inboxDepth)
            put("stalled", stalled)
        }

    /**
     * 取消的纯判定。输入是取消时刻的全部事实：状态存不存在、到没到终态、
     * 请求文件是否还在收件箱（未认领）。判定本身不做 IO，执行侧据此行动。
     */
    fun cancelPlan(state: RequestState?, fileInInbox: Boolean): CancelPlan = when {
        state == null -> CancelPlan.NotFound
        state.isTerminal -> CancelPlan.AlreadySettled(state.phase)
        fileInInbox -> CancelPlan.RemoveUnclaimed
        else -> CancelPlan.MarkRequested
    }
}

/**
 * 控制通道服务端：消费控制请求目录，服务提交回执、状态查询、取消与探活。
 *
 * 它与能力请求的工作循环完全隔离 —— 自己的消费协程、自己的目录观察者，
 * 一次长执行、一次等人的审批都挡不住 status 与 cancel。反过来也一样：
 * 这里的每个操作都只读状态存储或只做转发（submit 转写成 v1 信封投进工作信箱），
 * **没有任何路径能从这里执行能力动作**；执行永远由工作循环按既有流程与全部闸门完成。
 *
 * 使用前置条件与 [MailboxServer] 同规：同一目录只允许一个实例、start/stop 同线程调用、
 * 沙盒侧写入必须分片加改名。回包落在 `control-outbox/<控制请求id>.json`，
 * 同名原子覆盖 —— submit 先写 QUEUED 回执，终态结果由工作循环完成时覆盖同一文件。
 */
class ControlChannelServer(
    private val paths: RelayPaths,
    private val reaper: StorageReaper,
    private val stateStore: RequestStateStore,
    private val runLog: RunLog,
    /**
     * 工作循环的只读读数（探活用）。这是两条循环之间唯一的内存共享，
     * 控制侧只读它，绝不向工作循环投递任何信号。
     */
    private val health: () -> MailboxServer.WorkLoopHealth,
    /**
     * 提问呈现器（自制悬浮卡）。为 null 表示这台宿主没有接提问通路：ask 请求会得到
     * 「无可用呈现面」的明确拒绝，而不是无限等待。
     * 它只把问题摆给用户并把答复带回来，**不执行任何能力动作**——控制通道的纪律不变。
     */
    private val askPresenter: interlock.relay.core.interlock.InterlockPrompt? = null,
    private val now: () -> Long = ::monotonicNow,
) {

    /** 在途提问的 id：同一 id 重复投递时不覆盖前一件的回包（<id>.json 是同一个文件名）。 */
    private val inFlightAskIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val signals = Channel<Unit>(CAPACITY)

    /** FileObserver 被回收即停止投递，必须由本类持强引用。 */
    private var observer: FileObserver? = null
    private var job: Job? = null
    private var scope: CoroutineScope? = null

    @Volatile
    private var dirsOk = false

    val isRunning: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        if (!paths.ensureMailboxDirs()) {
            dirsOk = false
            runLog.error(LogSubsystem.STORAGE, LogEvent.DIRS_UNAVAILABLE)
            return
        }
        dirsOk = true
        // 提问要在控制循环之外等答复（最长两分钟），因此留一份外层作用域的引用：
        // 问答复的协程不能占住这条 limitedParallelism(1)，否则 status/cancel 全被堵住。
        this.scope = scope
        job = scope.launch(Dispatchers.IO.limitedParallelism(1)) {
            runLog.info(LogSubsystem.TRANSPORT, LogEvent.CONTROL_STARTED)
            try {
                loop()
            } finally {
                stopWatching()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        // 在途的提问不在这里取消：问答复的协程挂在装配根的作用域上（最长两分钟），
        // 取消它会把「用户还没答」记成通道故障。收场由装配根做两件事——
        // 撤掉屏上那张卡（AskOverlayPresenter.withdraw）并把等待以"通道已停"了结，
        // 回包照旧写进控制出站口，调用方拿到的是一个明确结局而不是无限悬空。
        scope = null
        runLog.info(LogSubsystem.TRANSPORT, LogEvent.CONTROL_STOPPED)
    }

    /** 与 [MailboxServer] 同规：无条件替换观察者，任一时刻最多一个观察者在跑且属于当前循环。 */
    private fun restartWatching() {
        observer?.stopWatching()
        observer = object : FileObserver(paths.controlInboxDir, MASK) {
            override fun onEvent(event: Int, path: String?) {
                signals.trySend(Unit)
            }
        }.also {
            runCatching { it.startWatching() }
                .onFailure { cause ->
                    runLog.warn(
                        LogSubsystem.TRANSPORT,
                        LogEvent.CHANNEL_DEGRADED,
                        "where" to "control.startWatching",
                        "cause" to cause.javaClass.simpleName,
                    )
                }
        }
    }

    private fun stopWatching() {
        observer?.stopWatching()
        observer = null
    }

    /**
     * 目录被沙盒侧换掉时自行复位；挂在旧目录上的观察者盯的是已删除的 inode，
     * 按周期整条重建监听，与工作循环同一套自愈。
     */
    private fun ensureDirs(stamp: Long, lastRebuild: Long): Long {
        val healthy = paths.ensureMailboxDirs()
        if (healthy != dirsOk) {
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.CHANNEL_DEGRADED, "dirs" to healthy.toString())
            dirsOk = healthy
        }
        if (!healthy) return lastRebuild
        if (stamp - lastRebuild < OBSERVER_REBUILD_MS) return lastRebuild
        restartWatching()
        return stamp
    }

    private suspend fun loop() {
        var lastRebuild = 0L
        restartWatching()
        while (currentCoroutineContext().isActive) {
            lastRebuild = ensureDirs(now(), lastRebuild)
            if (!dirsOk) {
                delay(IDLE_POLL_MS)
                continue
            }
            withTimeoutOrNull(IDLE_POLL_MS) { signals.receive() }
            drain()
        }
    }

    private suspend fun drain() {
        val files = paths.controlInboxDir.listFiles()?.filter { it.isFile } ?: return
        val (valid, malformed) = files.partition { EnvelopeCodec.isRequestFileName(it.name) }
        // 在途分片不是畸形名（见 [MailboxServer.isIncomingPartName]）：入口 CLI 落在
        // 控制收件箱里的 `.<id>.json.part` 只等一次改名，畸形回包碰了它就会撞掉改名。
        malformed.filterNot { MailboxServer.isIncomingPartName(it.name) }
            .take(MAX_MALFORMED_PER_ROUND)
            .forEach { rejectMalformed(it) }
        var handled = 0
        for (source in valid.sortedBy { it.name }) {
            // stop() 之后本循环可能还在收尾：被取消的循环不再认领新请求。
            if (!currentCoroutineContext().isActive) return
            if (handled >= MAX_PER_ROUND) break
            handled++
            serve(source)
        }
    }

    /**
     * 读一条控制请求并分派。读完即认领（删除源文件），操作之间互不等待。
     *
     * 每条操作无论走哪条分支都落一条 [LogEvent.CONTROL_OP]（op / 结局 / 耗时），
     * 且异常不外逃：外逃会带走整条控制循环，而控制循环正是「status 与 cancel 查不到答案」
     * 这类故障唯一还能被回答的地方。
     */
    private fun serve(source: File) {
        val startedAt = now()
        // 认领时间要在删除之前取：ask 靠它判断这一件是不是已经躺过整个调用方预算的旧件。
        val submittedAtWall = source.lastModified()
        var op = ControlOps.UNKNOWN
        var result = ControlResults.FAILED
        try {
            val text = runCatching { source.readText() }.getOrElse {
                rejectMalformed(source, cause = "unreadable")
                result = ControlResults.REJECTED
                return
            }
            source.delete()
            when (val decoded = EnvelopeCodec.decodeControl(text, source.name)) {
                is ControlDecodeResult.Rejected -> {
                    reject(decoded.id, ControlOps.UNKNOWN, "${decoded.error.code}/${decoded.cause}")
                    result = ControlResults.REJECTED
                }

                is ControlDecodeResult.Valid -> when (val control = decoded.control) {
                    is ControlRequest.Submit -> {
                        op = ControlOps.SUBMIT
                        result = handleSubmit(control)
                    }

                    is ControlRequest.Status -> {
                        op = ControlOps.STATUS
                        result = handleStatus(control)
                    }

                    is ControlRequest.Cancel -> {
                        op = ControlOps.CANCEL
                        result = handleCancel(control)
                    }

                    is ControlRequest.Health -> {
                        op = ControlOps.HEALTH
                        result = handleHealth(control)
                    }

                    is ControlRequest.Ask -> {
                        op = ControlOps.ASK
                        result = handleAsk(control, submittedAtWall)
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // 异常分支同样要留下一条结局读数：控制回包多半已经没写出去，
            // 提交方只会对着一个永远不出现的文件轮询到超时。
            result = ControlResults.FAILED
        } finally {
            logOp(op, result, startedAt)
        }
    }

    /** 一条控制操作的读数。ms 是本条从读文件到出结论的耗时，不含它在队列里等的那段。 */
    private fun logOp(op: String, result: String, startedAt: Long) {
        runLog.info(
            LogSubsystem.TRANSPORT, LogEvent.CONTROL_OP,
            "op" to op,
            "result" to result,
            "ms" to (now() - startedAt).coerceAtLeast(0L).toString(),
        )
    }

    /** 形状不合规的控制文件同样要回话：控制通道没有「放在原地等轮询」的语义。 */
    private fun rejectMalformed(source: File, cause: String = "malformed request file name ${source.name.take(48)}") {
        val stem = source.name.removeSuffix(EnvelopeCodec.JSON_SUFFIX)
        val id = stem.map { if (it in LEGAL_ID_CHARS) it else '_' }.joinToString("").take(64)
            .ifEmpty { "unnamed" }
        reject(id, ControlOps.UNKNOWN, cause)
        // 回包按原文件名落，让按自己文件名轮询的那一侧拿得到（与 v1 处置同规）。
        val reply = EnvelopeCodec.encodeControl(id, ControlOps.UNKNOWN, ok = false, payload = JSONObject().put("cause", cause))
        reaper.writeTextAtomic(File(paths.controlOutboxDir, source.name), reply)
        source.delete()
    }

    private fun reject(id: String, op: String, cause: String) {
        runLog.warn(LogSubsystem.TRANSPORT, LogEvent.CONTROL_REQUEST_REJECTED, "op" to op, "cause" to cause)
        reply(id, op, ok = false) { JSONObject().put("cause", cause) }
    }

    /**
     * 提交：登记状态，通过后转写成 v1 信封投进工作信箱，回 QUEUED 回执。
     * 执行从这里开始就不归控制通道管 —— 工作循环按既有流程认领、过闸门、派发。
     *
     * @return [ControlResults] 字面量，供 [serve] 落一条操作读数。
     */
    private fun handleSubmit(submit: ControlRequest.Submit): String {
        when (val created = stateStore.create(submit.id, submit.payloadDigest, submit.capability.wire, submit.hostTtlMs)) {
            is CreateOutcome.Created -> {
                if (!enqueue(submit)) {
                    // 入队没成功就不能当受理：状态收成可证未执行的终态，回包说清失败，让提交方重投。
                    // 带宿主故障码前缀：客户端按 E_INTERNAL 归 exit 6（宿主侧故障），不与调用方写错参数的用法错混档。
                    stateStore.recordTerminal(submit.id, RequestPhase.INTERRUPTED_BEFORE_EXECUTION)
                    reject(submit.id, ControlOps.SUBMIT, "E_INTERNAL/enqueue failed (mailbox write failed)")
                    return ControlResults.FAILED
                }
                reply(submit.id, ControlOps.SUBMIT, ok = true) {
                    JSONObject()
                        .put("state", RequestPhase.QUEUED.name)
                        .put("targetId", submit.id)
                        .put("digest", submit.payloadDigest)
                }
                return ControlResults.OK
            }

            is CreateOutcome.Existing -> {
                // 幂等回执：同一件事重复提交，按既有阶段答复；终态连同可重取结果一并给出。
                val state = created.state
                reply(submit.id, ControlOps.SUBMIT, ok = true) {
                    JSONObject()
                        .put("state", state.phase.name)
                        .put("targetId", submit.id)
                        .put("digest", submit.payloadDigest)
                        .also { payload ->
                            state.resultJson?.let {
                                payload.put("result", runCatching { JSONObject(it) }.getOrNull() ?: JSONObject.NULL)
                            }
                        }
                }
                return ControlResults.OK
            }

            is CreateOutcome.DigestConflict -> {
                // 同 id 异摘要必须拒绝：控制命令不得携带「替换参数」偷换已获授权的请求。
                reject(submit.id, ControlOps.SUBMIT, "digest conflict")
                return ControlResults.REJECTED
            }

            is CreateOutcome.PersistFailed -> {
                // 同为宿主故障码前缀：状态落盘失败不是调用方能改对的错，客户端按 exit 6 归类。
                reject(submit.id, ControlOps.SUBMIT, "E_INTERNAL/state persist failed")
                return ControlResults.FAILED
            }
        }
    }

    /**
     * 把转写出的 v1 请求投进工作信箱：先写分片再改名，与助手侧同一安全形态。
     * 目标被预先占位时覆盖之 —— 这份转写与状态存储里的摘要绑定，是权威请求。
     *
     * 分片落盘走 [StorageReaper.writeTextAtomic] 而不是直接写字节：分片名由提交方
     * 自选，沙盒预放一个同名符号链接就能让直写跟随链接、把宿主的正文写进绑定子树
     * 之外的私有文件。选原子写而不是「写前查一遍链接」，是因为检查与打开之间存在
     * 同 uid 对手换名的窗口，而原子写先落暂存件、再用不跟随末级链接的 rename 落地，
     * 与控制回包、v1 回包的写入同一防护形态，窗口不存在。
     */
    private fun enqueue(submit: ControlRequest.Submit): Boolean = runCatching {
        val part = File(paths.inboxDir, submit.id + PART_SUFFIX)
        if (!reaper.writeTextAtomic(part, ControlChannelLogic.submitEnvelopeV1(submit, wallNow()))) {
            return@runCatching false
        }
        val target = File(paths.inboxDir, submit.id + EnvelopeCodec.JSON_SUFFIX)
        if (part.renameTo(target)) return@runCatching true
        target.delete()
        part.renameTo(target)
    }.getOrDefault(false)

    private fun handleStatus(status: ControlRequest.Status): String {
        val state = stateStore.get(status.targetId)
        reply(status.id, ControlOps.STATUS, ok = true) { ControlChannelLogic.statusPayload(state, status.targetId) }
        // 查不到不是一次失败：status 的职责就是如实说"没有这条"，found=false 已经是答案。
        return ControlResults.OK
    }

    private fun handleCancel(cancel: ControlRequest.Cancel): String {
        val state = stateStore.get(cancel.targetId)
        val fileInInbox = File(paths.inboxDir, cancel.targetId + EnvelopeCodec.JSON_SUFFIX).isFile
        return when (val plan = ControlChannelLogic.cancelPlan(state, fileInInbox)) {
            is CancelPlan.NotFound ->
                settled(cancel, outcome = "NOT_FOUND", phase = null, result = ControlResults.NOT_FOUND)

            is CancelPlan.AlreadySettled ->
                settled(
                    cancel, outcome = "COMPLETED", phase = plan.phase.name,
                    result = ControlResults.ALREADY_SETTLED,
                )

            is CancelPlan.RemoveUnclaimed -> cancelUnclaimed(cancel)
            is CancelPlan.MarkRequested -> cancelInFlight(cancel)
        }
    }

    /**
     * 请求文件还在收件箱：宿主可证尚未认领它。改名搬进 processing 再删 ——
     * 改名失败即说明工作循环恰好抢先认领，转为记取消意愿，绝不因竞争失败而误报「已撤销」。
     */
    private fun cancelUnclaimed(cancel: ControlRequest.Cancel): String {
        val source = File(paths.inboxDir, cancel.targetId + EnvelopeCodec.JSON_SUFFIX)
        val parked = File(paths.processingDir, cancel.targetId + EnvelopeCodec.JSON_SUFFIX)
        if (!source.renameTo(parked)) {
            return cancelInFlight(cancel)
        }
        if (!parked.delete()) {
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.REQUEST_DROPPED, "file" to parked.name, "cause" to "cancel leftover")
        }
        val recorded = stateStore.recordTerminal(cancel.targetId, RequestPhase.CANCELLED)
        if (recorded?.phase == RequestPhase.CANCELLED) {
            runLog.info(LogSubsystem.TRANSPORT, LogEvent.REQUEST_CANCELLED, "id" to cancel.targetId)
            reply(cancel.id, ControlOps.CANCEL, ok = true) {
                JSONObject().put("targetId", cancel.targetId).put("outcome", "CANCELLED")
            }
            return ControlResults.CANCELLED
        }
        // 状态先一步有了别的终态（同 id 此前已完成）或没能落盘：如实按现状答复。
        return settled(
            cancel, outcome = if (recorded == null) "UNKNOWN" else "COMPLETED", phase = recorded?.phase?.name,
            result = if (recorded == null) ControlResults.FAILED else ControlResults.ALREADY_SETTLED,
        )
    }

    /**
     * 请求已被工作循环认领：此刻只能记下取消意愿 —— 动作可能已经发生，
     * 结论必须等执行链自己的终态。可证未派发与否由状态存储的边界标记说。
     */
    private fun cancelInFlight(cancel: ControlRequest.Cancel): String {
        return when (val outcome = stateStore.requestCancel(cancel.targetId)) {
            is CancelOutcome.Requested -> {
                runLog.info(LogSubsystem.TRANSPORT, LogEvent.REQUEST_CANCEL_REQUESTED, "id" to cancel.targetId)
                reply(cancel.id, ControlOps.CANCEL, ok = true) {
                    JSONObject()
                        .put("targetId", cancel.targetId)
                        .put("outcome", "CANCEL_REQUESTED")
                        .put("provablyUndispatched", outcome.provablyUndispatched)
                }
                ControlResults.CANCEL_REQUESTED
            }

            is CancelOutcome.AlreadyTerminal ->
                settled(
                    cancel, outcome = "COMPLETED", phase = outcome.state.phase.name,
                    result = ControlResults.ALREADY_SETTLED,
                )

            CancelOutcome.NotRecorded ->
                settled(cancel, outcome = "UNKNOWN", phase = null, result = ControlResults.FAILED)

            CancelOutcome.NotFound ->
                settled(cancel, outcome = "NOT_FOUND", phase = null, result = ControlResults.NOT_FOUND)
        }
    }

    private fun settled(cancel: ControlRequest.Cancel, outcome: String, phase: String?, result: String): String {
        reply(cancel.id, ControlOps.CANCEL, ok = true) {
            JSONObject()
                .put("targetId", cancel.targetId)
                .put("outcome", outcome)
                .put("state", phase ?: JSONObject.NULL)
        }
        return result
    }

    /** 探活只读工作循环的内存标记与目录计数，不做诊断重活，也绝不等待工作循环。 */
    private fun handleHealth(request: ControlRequest.Health): String {
        val snapshot = health()
        reply(request.id, ControlOps.HEALTH, ok = true) {
            ControlChannelLogic.healthPayload(
                snapshot.channelLive,
                snapshot.queueDepth,
                snapshot.oldestQueuedAgeMs,
                snapshot.inboxDepth,
                snapshot.stalled,
            )
        }
        return ControlResults.OK
    }

    /**
     * 提问：把问题交给呈现层当面问用户，答复回来才写回包。
     *
     * 关键纪律：等待发生在**本循环之外**（另起协程跑呈现器），控制循环立刻返回——
     * 用户思考的一分钟里 status/cancel/health 照常应答。呈现器给出四支结局：
     * 选了一项 / 全部驳回（合法答案）/ 超时 / 无呈现面；后三支 ok=false 并带
     * `E_ASK_*` 前缀的原因，回包同时给出 `error.{code,retryable,exitCode}`，
     * 由入口按它取退出码，不再由入口自己按前缀猜（分类单源在 [PromptCodes]）。
     *
     * 两条闸在呈现之前，**顺序是重号在前、旧件在后**：
     * - **同 id 只问一次**：回包槽位（`<id>.json`）只有一个。在飞的那一件优先于一切回包：
     *   若旧件闸跑在它前面，一件躺过期的同 id 请求会写出一条"请求早就过期"的假回包，
     *   把屏上那张活卡的真答案盖掉。
     * - **旧件不呈现**：文件在收件箱里躺过了调用方自己的预算（宿主机进程死过一次、
     *   沙盒重投），此刻再摆到用户眼前只会让人对着一个早就没人等的问题作答。
     */
    private fun handleAsk(ask: ControlRequest.Ask, submittedAtWall: Long): String {
        val presenter = askPresenter
        val running = scope
        val startedAt = now()
        if (presenter == null || running == null) {
            return askRejected(ask.id, PromptCodes.INTERNAL_NO_CHANNEL, startedAt)
        }
        if (!inFlightAskIds.add(ask.id)) {
            // 同一 id 的第二件：回包槽位（<id>.json）只有一个，此刻写下去就会把第一件
            // 的答案盖掉——丢的是用户真答过的那一份。这里**不回包**，让这件按"没有回包"
            // 收场（调用方报 E_ASK_NO_REPLY，事实正是"你没收到答复"），第一件照常拿到答案。
            // 这条不落在"控制文件必答"的既有纪律里，是因为答它必然伤到另一件更重要的。
            runLog.warn(
                LogSubsystem.TRANSPORT, LogEvent.ASK_UNAVAILABLE,
                "id" to ask.id,
                "cause" to PromptCodes.INTERNAL_DUPLICATE,
                "ms" to (now() - startedAt).coerceAtLeast(0L).toString(),
            )
            return ControlResults.FAILED
        }
        val staleMs = askStaleMs(submittedAtWall, ask.timeoutMs)
        if (staleMs > 0) {
            // 用户一次都没看见过它，报"超时"是事实（窗口在文件躺在收件箱里时已经流走）。
            // 这一件不会呈现，把 id 让回去，免得它一直被当成"在飞"。
            inFlightAskIds.remove(ask.id)
            return askRejected(ask.id, PromptCodes.TIMEOUT_EXPIRED, startedAt)
        }
        running.launch {
            val outcome = try {
                presenter.ask(ask.question, ask.options, ask.timeoutMs)
            } catch (cancellation: CancellationException) {
                // 取消不是结论：把 id 放回去，回包留给真正能给出结局的一方（重抛）。
                inFlightAskIds.remove(ask.id)
                throw cancellation
            } catch (t: Throwable) {
                PromptOutcome.Unavailable("${PromptCodes.INTERNAL}/presenter threw ${t.javaClass.simpleName}")
            }
            inFlightAskIds.remove(ask.id)
            val ms = (now() - startedAt).coerceAtLeast(0L)
            when (outcome) {
                is PromptOutcome.Chosen -> {
                    runLog.info(
                        LogSubsystem.TRANSPORT, LogEvent.ASK_ANSWERED,
                        "id" to ask.id, "choice" to "option[${outcome.index}]", "ms" to ms.toString(),
                    )
                    replyAsk(ask.id, ok = true) { askChosenPayload(outcome.index, outcome.label) }
                }

                PromptOutcome.RejectedAll -> {
                    runLog.info(
                        LogSubsystem.TRANSPORT, LogEvent.ASK_ANSWERED,
                        "id" to ask.id, "choice" to "reject_all", "ms" to ms.toString(),
                    )
                    replyAsk(ask.id, ok = true) { askRejectAllPayload() }
                }

                PromptOutcome.Reasked -> {
                    // 「重新提问」否的是**问题**本身：与驳回（否的是选项）分开记，日志里一眼可分。
                    runLog.info(
                        LogSubsystem.TRANSPORT, LogEvent.ASK_ANSWERED,
                        "id" to ask.id, "choice" to "reask", "ms" to ms.toString(),
                    )
                    replyAsk(ask.id, ok = true) { askReaskPayload() }
                }

                is PromptOutcome.TimedOut -> {
                    val cause = if (outcome.covered) PromptCodes.TIMEOUT_COVERED else PromptCodes.TIMEOUT_NO_ANSWER
                    runLog.warn(
                        LogSubsystem.TRANSPORT, LogEvent.ASK_UNAVAILABLE,
                        "id" to ask.id, "cause" to cause, "ms" to ms.toString(),
                    )
                    replyAsk(ask.id, ok = false) { askFailurePayload(cause) }
                }

                is PromptOutcome.Unavailable -> {
                    runLog.warn(
                        LogSubsystem.TRANSPORT, LogEvent.ASK_UNAVAILABLE,
                        "id" to ask.id, "cause" to outcome.cause, "ms" to ms.toString(),
                    )
                    replyAsk(ask.id, ok = false) { askFailurePayload(outcome.cause) }
                }
            }
        }
        return ControlResults.ACCEPTED
    }

    /** 呈现之前就否掉的提问：同样要写回包并留一条与"用户没答"可区分的读数。 */
    private fun askRejected(id: String, cause: String, startedAt: Long): String {
        runLog.warn(
            LogSubsystem.TRANSPORT, LogEvent.ASK_UNAVAILABLE,
            "id" to id, "cause" to cause, "ms" to (now() - startedAt).coerceAtLeast(0L).toString(),
        )
        replyAsk(id, ok = false) { askFailurePayload(cause) }
        return ControlResults.FAILED
    }

    /**
     * 这件 ask 已经在收件箱里躺了多久（毫秒，0 表示还算新鲜）。
     *
     * 判据用文件的落盘时刻（挂载同一内核，宿主与沙盒同一个钟）：
     * 躺过 `timeoutMs + ASK_STALE_GRACE_MS` 的请求，调用方的本地预算必然已经走完，
     * 用户此刻作答也送不回去。时钟异常（文件时间在未来）按新鲜处理，宁多问一次。
     */
    private fun askStaleMs(submittedAtWall: Long, timeoutMs: Long): Long =
        askStaleMs(submittedAtWall, timeoutMs, System.currentTimeMillis())

    private fun replyAsk(id: String, ok: Boolean, payload: () -> JSONObject) {
        reply(id, ControlOps.ASK, ok, payload)
    }

    private fun reply(id: String, op: String, ok: Boolean, payload: () -> JSONObject) {
        val text = EnvelopeCodec.encodeControl(id, op, ok, payload())
        // 回包写失败必须留痕：提交方只能对着一个永远不出现的文件轮询到超时，
        // 事后排查「为什么 status/cancel 没有回音」全靠这一条。
        if (!reaper.writeTextAtomic(File(paths.controlOutboxDir, id + EnvelopeCodec.JSON_SUFFIX), text)) {
            runLog.error(LogSubsystem.TRANSPORT, LogEvent.RESPONSE_WRITE_FAILED, "id" to id)
        }
    }

    companion object {
        /**
         * [logOp] 填的字段名，与 [LogEvent.CONTROL_OP] 的占位符逐个相等
         * （见 [RequestStateStore.ADMITTED_FIELDS] 的同条纪律）。
         */
        internal val OP_FIELDS = setOf("op", "result", "ms")

        /**
         * ask 两条读数的字段名，与 [LogEvent.ASK_ANSWERED] / [LogEvent.ASK_UNAVAILABLE]
         * 的占位符逐个相等（字典契约见 StageEventDictionaryTest）。
         */
        internal val ASK_FIELDS = setOf("id", "choice", "ms")
        internal val ASK_FAIL_FIELDS = setOf("id", "cause", "ms")

        /**
         * 旧件的宽限：与入口 CLI 的本地等待余量取同一个数（`ASK_BUDGET_MARGIN_MS` = 12000：
         * 认领上界 5000 + 上屏预算 4000 + 收尾）。躺过"窗口 + 余量"的 ask，调用方的预算
         * 必然已经走完——此刻再呈现只会让人对着一个没人等的窗口作答。
         */
        internal const val ASK_STALE_GRACE_MS = 12_000L

        /**
         * 这件 ask 在收件箱里躺了多久（毫秒，0 = 还算新鲜）。抽成纯函数是为了能在
         * 纯 JVM 上钉住边界（时钟异常、刚好压线两种），呈现链路的其余部分需要设备。
         */
        internal fun askStaleMs(submittedAtWall: Long, timeoutMs: Long, nowWall: Long): Long {
            if (submittedAtWall <= 0L) return 0L
            val age = nowWall - submittedAtWall
            return if (age > timeoutMs + ASK_STALE_GRACE_MS) age else 0L
        }

        /**
         * 失败回包：原因串 + 结构化的 `error`。入口按 `error.exitCode` 取值，
         * 取不到时才退回按前缀猜；`retryable` 与宿主错误表同一口径。
         * 单源在 [interlock.relay.core.interlock.PromptCodes]，这里只做形状。
         */
        internal fun askFailurePayload(cause: String): JSONObject = JSONObject()
            .put("cause", cause)
            .put(
                "error",
                JSONObject()
                    .put("code", interlock.relay.core.interlock.PromptCodes.codeOf(cause))
                    .put("retryable", interlock.relay.core.interlock.PromptCodes.retryable(cause))
                    .put("exitCode", interlock.relay.core.interlock.PromptCodes.exitCodeFor(cause)),
            )

        /** 选了一项的成功帧。抽成纯函数是为了让契约测试断言真实拼装（不是测试自造的键）。 */
        internal fun askChosenPayload(index: Int, label: String): JSONObject = JSONObject()
            .put(
                "choice",
                JSONObject().put("kind", "option").put("index", index).put("label", label),
            )

        /** 全部驳回的成功帧：「驳回」是合法答案，与选中同为一支。 */
        internal fun askRejectAllPayload(): JSONObject = JSONObject()
            .put("choice", JSONObject().put("kind", "reject_all"))

        /** 「重新提问」的成功帧：用户要的是把问题本身换个法子再问，而不是换一组选项。 */
        internal fun askReaskPayload(): JSONObject = JSONObject()
            .put("choice", JSONObject().put("kind", "reask"))

        private const val MASK = FileObserver.MOVED_TO
        private const val CAPACITY = 16
        /** 观察者失效时的兜底轮询间隔；入口的等待余量必须装得下它（见 AskContractTest）。 */
        internal const val IDLE_POLL_MS = 5_000L

        /** 单轮最多服务这么多条控制请求，其余等下一轮：控制请求便宜，但也不该被灌到无限拉长。 */
        private const val MAX_PER_ROUND = 8
        private const val MAX_MALFORMED_PER_ROUND = 8
        private val LEGAL_ID_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + charArrayOf('_', '-')

        /** 控制回包文件名之外的占位后缀，不落在请求名白名单内。 */
        private const val PART_SUFFIX = ".part"

        /** FileObserver 盯的是 inode，目录被重建后不会自动跟过去，按周期整条重建监听。 */
        private const val OBSERVER_REBUILD_MS = 30_000L
    }
}
