package interlock.relay.core.transport

import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.runtime.wallNow
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.storage.QuotaLedger
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import org.json.JSONObject

/**
 * 一条已提交请求在宿主侧的生命周期阶段。
 *
 * 流转主干：`QUEUED → ADMITTED → WAITING_RELAY_USER | WAITING_ANDROID_USER →
 * READY → EXECUTING → SUCCEEDED | FAILED | UNKNOWN`。`CANCELLED` 只能在证实
 * 尚未提交副作用时给出；执行中收到取消只记 [CANCEL_REQUESTED] 标记，最终结论
 * 仍落进主干终态。重启后按持久化边界收尾：可证未派发的以
 * [INTERRUPTED_BEFORE_EXECUTION] 终结，不能证实的转 [UNKNOWN]；
 * [EXPIRED] 是终态记录过了保留期之后的形态，过期不当未执行。
 */
enum class RequestPhase {
    QUEUED,
    ADMITTED,
    WAITING_RELAY_USER,
    WAITING_ANDROID_USER,
    READY,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    CANCEL_REQUESTED,
    INTERRUPTED_BEFORE_EXECUTION,
    UNKNOWN,
    EXPIRED,
}

/** 一条请求的持久化状态。不含请求参数：参数里可能有敏感内容，重启后不恢复未完成请求。 */
data class RequestState(
    val id: String,
    /** 规范化参数摘要（助手侧算出、随提交带来），「同一件事」的比对依据。 */
    val digest: String,
    val capability: String,
    val phase: RequestPhase,
    /** 单调钟。保留期与阶段计时全部用它，墙钟只进文件作展示。 */
    val createdAtMs: Long,
    val admittedAtMs: Long? = null,
    val ttlMs: Long,
    /**
     * 副作用边界标记：任何可能产生外部影响的调用之前先置位并确认落盘成功，
     * 否则拒绝执行。崩溃恢复据此保守判断这条请求到底动没动过世界。
     */
    val mayHaveDispatched: Boolean = false,
    val terminalAtMs: Long? = null,
    /** 终态完整回包 JSON，供客户端超时后重取。仅终态且未过期时有值。 */
    val resultJson: String? = null,
    /** 客户端在等待到点后请求过取消。能否肯定已撤销，由控制服务结合边界标记判定。 */
    val cancelRequested: Boolean = false,
) {
    /** 终态判定。[CANCEL_REQUESTED] 不算终态：请求仍在飞行，结论未出。 */
    val isTerminal: Boolean
        get() = phase in TERMINAL_PHASES

    companion object {
        val TERMINAL_PHASES: Set<RequestPhase> = setOf(
            RequestPhase.SUCCEEDED,
            RequestPhase.FAILED,
            RequestPhase.CANCELLED,
            RequestPhase.INTERRUPTED_BEFORE_EXECUTION,
            RequestPhase.UNKNOWN,
            RequestPhase.EXPIRED,
        )
    }
}

/** [RequestStateStore.create] 的结果。冲突与失败都走返回值，不抛异常。 */
sealed interface CreateOutcome {
    /** 新建成功。 */
    data class Created(val state: RequestState) : CreateOutcome

    /** 同 id 同摘要：幂等命中，返回既有状态。调用方按原请求继续答复。 */
    data class Existing(val state: RequestState) : CreateOutcome

    /** 同 id 异摘要：拒绝。控制命令不得携带「替换参数」偷换已获授权的请求。 */
    data class DigestConflict(val existing: RequestState) : CreateOutcome

    /** 状态没能落盘。调用方不得把它当成已受理。 */
    object PersistFailed : CreateOutcome
}

/** [RequestStateStore.requestCancel] 的结果。 */
sealed interface CancelOutcome {
    /**
     * 取消意愿已记下并落盘。[provablyUndispatched] 表示按持久化边界可证这条请求
     * 尚未提交任何动作；最终是否回「已取消」仍由调用方结合信箱文件（请求是否真的
     * 还在收件箱里）判定，存储层只持这一份标记。
     */
    data class Requested(val state: RequestState, val provablyUndispatched: Boolean) : CancelOutcome

    /** 已是终态：取消为时已晚，调用方按当前终态答复。 */
    data class AlreadyTerminal(val state: RequestState) : CancelOutcome

    /** 取消标记没能落盘：不能宣称已受理，调用方只能按未知处置。 */
    object NotRecorded : CancelOutcome

    /** 没有这条请求的状态记录。 */
    object NotFound : CancelOutcome
}

/**
 * [RequestStateStore.armExecDispatchGuard] 的细分结论。放行之外的两种拒绝必须分开：
 * 「无可执行状态」重试同一条没有意义（记录已终态、已不存在或已过期遗留），
 * 「标记没落盘」是宿主侧的持久化故障，稍后重试同一条仍有意义。调用方据此分别
 * 成稿或在日志里各归各位。
 */
sealed interface DispatchGuardOutcome {
    /** 边界标记已落盘：可以派发。 */
    data class Armed(val state: RequestState) : DispatchGuardOutcome

    /** 此刻无可执行状态：记录不存在（null）、已终态或已过期遗留（携带读到的现状）。 */
    data class NotExecutable(val state: RequestState?) : DispatchGuardOutcome

    /** 边界标记没能落盘：磁盘上仍是旧的未派发状态，恢复扫描据此收尾。 */
    object PersistFailed : DispatchGuardOutcome
}

/**
 * 请求状态存储：宿主私有目录下每条请求一个 `<id>.json`，原子写、有限保留。
 *
 * 放置纪律：状态目录不在绑定子树内。里面存着「同一件事」的摘要与可重取的终态
 * 回包，沙盒可写就等于能偷换重放判据、伪造终态结论。
 *
 * 不持久化请求参数：参数规范化原文含敏感内容，按设计重启后不恢复未完成请求，
 * 只以 [RequestState.mayHaveDispatched] 为界把结局收窄到两档可证的终态。
 *
 * 终态可重取性以**文件**为准：读不到、读坏了按不存在处理，内存里没有可信副本。
 * id 前置条件：调用方传入的 id 必须已过通道白名单（[EnvelopeCodec.isValidId]），
 * 存储层不再复验——文件名由 id 直接拼出，这条前置一旦失守就是路径穿越。
 */
class RequestStateStore(
    private val stateDir: File,
    private val now: () -> Long = ::monotonicNow,
    private val wallNow: () -> Long = ::wallNow,
    private val runLog: RunLog? = null,
) {

    private val lock = Any()

    /**
     * 登记一条新请求。幂等：同 id 同摘要返回既有状态（[CreateOutcome.Existing]）；
     * 同 id 异摘要返回 [CreateOutcome.DigestConflict]，绝不覆盖——否则重放判据
     * 会被后来者改写。
     */
    fun create(id: String, digest: String, capability: String, ttlMs: Long): CreateOutcome = synchronized(lock) {
        val existing = readState(target(id))
        if (existing != null) {
            return@synchronized if (existing.digest == digest) {
                CreateOutcome.Existing(existing)
            } else {
                CreateOutcome.DigestConflict(existing)
            }
        }
        // 防灌：目录超过上限先强制清理一轮。只清终态与过期条目，
        // 清完仍超限也照样登记——限流的职责在入口，这里只保证不无界膨胀。
        if (trackedCount() > MAX_TRACKED) purgeExpiredLocked(now())
        val state = RequestState(
            id = id,
            digest = digest,
            capability = capability,
            phase = RequestPhase.QUEUED,
            createdAtMs = now(),
            ttlMs = ttlMs,
        )
        if (!persist(state, force = false)) return@synchronized CreateOutcome.PersistFailed
        runLog?.info(LogSubsystem.TRANSPORT, LogEvent.REQUEST_STATE_CREATED, "id" to id, "cap" to capability)
        CreateOutcome.Created(state)
    }

    /** 读一条状态。文件缺失或损坏一律返回 null：终态可重取性以文件为准。 */
    fun get(id: String): RequestState? = readState(target(id))

    /** 记入已认领。已终态的状态不再改写。 */
    fun markAdmitted(id: String): RequestState? = synchronized(lock) {
        val move = transition(id) { state ->
            state.copy(phase = RequestPhase.ADMITTED, admittedAtMs = state.admittedAtMs ?: now())
        }
        logEntry(move, id)
        move.after
    }

    /** 记入等待用户（[relayUser] 区分等本模块确认还是等系统授权框）。已终态不再改写。 */
    fun markWaiting(id: String, relayUser: Boolean): RequestState? = synchronized(lock) {
        val target =
            if (relayUser) RequestPhase.WAITING_RELAY_USER else RequestPhase.WAITING_ANDROID_USER
        val move = transition(id) { state -> state.copy(phase = target) }
        logEntry(move, id)
        move.after
    }

    /**
     * 执行前的持久提交边界：置 `EXECUTING` + `mayHaveDispatched=true` 并**同步落盘，
     * force 到物理介质**。返回 false 表示持久化失败或无可执行状态——调用方**必须
     * 拒绝执行**，否则崩溃恢复将无法判断副作用是否已发生。
     *
     * 失败时不改写任何状态：磁盘上仍是旧的未派发状态，恢复扫描据此得出
     * `INTERRUPTED_BEFORE_EXECUTION`（动作确实没发生），而不是保守的 UNKNOWN。
     * 要区分两种拒绝（无可执行状态 vs 落盘失败）用 [armExecDispatchGuard]。
     */
    fun markExecDispatchGuard(id: String): Boolean = armExecDispatchGuard(id) is DispatchGuardOutcome.Armed

    /**
     * 带细分结论的执行前置守卫，语义与 [markExecDispatchGuard] 完全一致，只是把
     * 拒绝的原因分成「无可执行状态」与「落盘失败」两支。失败时同样不改写任何状态。
     */
    fun armExecDispatchGuard(id: String): DispatchGuardOutcome = synchronized(lock) {
        val current = readState(target(id))
        if (current == null || current.isTerminal) {
            // 与落盘失败分开记：这一支重试同一条没有意义，日志要点名此刻的阶段。
            runLog?.warn(
                LogSubsystem.TRANSPORT,
                LogEvent.REQUEST_DISPATCH_NOT_EXECUTABLE,
                "id" to id,
                "phase" to (current?.phase?.name ?: "absent"),
            )
            return@synchronized DispatchGuardOutcome.NotExecutable(current)
        }
        // 过期遗留的记录同样不可执行：它对应的请求文件早被按龄回收，放行等于让一个
        // 失去文件归宿的请求把动作做出去。按「无可执行状态」同一支拒绝。
        if (nonTerminalRecordExpired(current, now())) {
            runLog?.warn(
                LogSubsystem.TRANSPORT,
                LogEvent.REQUEST_DISPATCH_NOT_EXECUTABLE,
                "id" to id,
                "phase" to current.phase.name,
            )
            return@synchronized DispatchGuardOutcome.NotExecutable(current)
        }
        val guarded = current.copy(phase = RequestPhase.EXECUTING, mayHaveDispatched = true)
        if (!persist(guarded, force = true)) {
            runLog?.error(LogSubsystem.TRANSPORT, LogEvent.REQUEST_DISPATCH_GUARD_FAILED, "id" to id)
            return@synchronized DispatchGuardOutcome.PersistFailed
        }
        DispatchGuardOutcome.Armed(guarded)
    }

    /**
     * 提交终态。已终态的请求不再改写：先提交的那份结论是唯一结论，后到的覆盖
     * 会让两次读取得到两个不同的「最终结果」。终态写入同样 force：设计要求
     * 「先提交终态，再向回包目录发布」，这份记录必须先于回包站得住。
     */
    fun recordTerminal(id: String, phase: RequestPhase, resultJson: String? = null): RequestState? =
        synchronized(lock) {
            if (phase !in RequestState.TERMINAL_PHASES) return@synchronized null
            val current = readState(target(id)) ?: return@synchronized null
            if (current.isTerminal) return@synchronized current
            val terminal = current.copy(phase = phase, resultJson = resultJson, terminalAtMs = now())
            if (!persist(terminal, force = true)) return@synchronized null
            runLog?.info(LogSubsystem.TRANSPORT, LogEvent.REQUEST_STATE_TERMINAL, "id" to id, "phase" to phase.name)
            terminal
        }

    /** 记下取消意愿。只置标记，不给「已撤销」的结论——那需要调用方核对边界与信箱。 */
    fun requestCancel(id: String): CancelOutcome = synchronized(lock) {
        val current = readState(target(id)) ?: return@synchronized CancelOutcome.NotFound
        if (current.isTerminal) return@synchronized CancelOutcome.AlreadyTerminal(current)
        val marked = current.copy(cancelRequested = true)
        if (!persist(marked, force = false)) return@synchronized CancelOutcome.NotRecorded
        CancelOutcome.Requested(marked, provablyUndispatched = !marked.mayHaveDispatched)
    }

    /**
     * 保留期清理，两段都从终态时刻起算（不引入第二个时间戳）：
     * 终态满 [RESULT_RETENTION_MS] 后把回包置空并标 `EXPIRED`（过期不当未执行，
     * 客户端查询仍能得到明确答复）；终态满 [STATE_RETENTION_MS] 后删除文件，
     * 即 `EXPIRED` 形态本身再保留约 24 小时。
     *
     * 过期遗留的非终态记录（见 [nonTerminalRecordExpired]）在巡检里先按持久化边界
     * 收尾成终态：可证未派发的以 `INTERRUPTED_BEFORE_EXECUTION` 终结，否则保守转
     * `UNKNOWN`——与启动恢复同一套规则，收尾后交给上面的两段保留期正常退役。
     */
    fun purgeExpired(nowMs: Long) = synchronized(lock) { purgeExpiredLocked(nowMs) }

    /**
     * 崩溃恢复扫描：把所有非终态请求按持久化边界收尾。可证未派发的以
     * [INTERRUPTED_BEFORE_EXECUTION] 终结（动作确实没发生），否则保守转
     * [UNKNOWN]。只提供扫描与收尾，是否、何时调用由上层决定。
     */
    fun recoverUnfinished(): List<RequestState> = synchronized(lock) {
        val recovered = mutableListOf<RequestState>()
        for (file in stateFiles()) {
            val state = readState(file) ?: continue
            if (state.isTerminal) continue
            val phase = if (state.mayHaveDispatched) {
                RequestPhase.UNKNOWN
            } else {
                RequestPhase.INTERRUPTED_BEFORE_EXECUTION
            }
            val settled = state.copy(phase = phase, terminalAtMs = now())
            if (!persist(settled, force = false)) continue
            runLog?.warn(LogSubsystem.TRANSPORT, LogEvent.REQUEST_RECOVERED, "id" to state.id, "phase" to phase.name)
            recovered.add(settled)
        }
        recovered
    }

    private fun purgeExpiredLocked(nowMs: Long) {
        for (file in stateFiles()) {
            val state = readState(file) ?: continue
            // 过期遗留的非终态记录先收尾成终态：它没有 terminalAt 可计保留期，
            // 不收尾就会永久留在目录里；判定依据与启动恢复同一套持久化边界。
            if (nonTerminalRecordExpired(state, nowMs)) {
                val phase = if (state.mayHaveDispatched) {
                    RequestPhase.UNKNOWN
                } else {
                    RequestPhase.INTERRUPTED_BEFORE_EXECUTION
                }
                persist(state.copy(phase = phase, terminalAtMs = nowMs), force = false)
                continue
            }
            val terminalAt = state.terminalAtMs ?: continue
            when {
                // 先判删除再判过期：EXPIRED 自身也是终态，次序颠倒会互相覆盖。
                state.phase == RequestPhase.EXPIRED && nowMs - terminalAt >= STATE_RETENTION_MS -> {
                    runCatching { file.delete() }
                }

                state.phase != RequestPhase.EXPIRED && nowMs - terminalAt >= RESULT_RETENTION_MS -> {
                    persist(
                        state.copy(phase = RequestPhase.EXPIRED, resultJson = null),
                        force = false,
                    )
                }
            }
        }
    }

    /** 通用状态改写：已终态不动，改完落盘失败按 null 处理（不给内存假象）。 */
    private fun transition(id: String, apply: (RequestState) -> RequestState): Move = move(id) {
        Transition(it, apply(it))
    }

    /**
     * 一次改写的前后两头。[before] 为 null 表示记录读不到（等同于不存在），
     * [after] 为 null 表示改写没落住 —— 两种都没有「迁移进入」可言，也都不记事件。
     * 调用方已持锁（[synchronized] 可重入，这里不再重复取锁）。
     */
    private fun move(id: String, apply: (RequestState) -> Transition): Move {
        val current = readState(target(id)) ?: return Move(null, null)
        if (current.isTerminal) return Move(current, current)
        val step = apply(current)
        if (!persist(step.candidate, force = false)) return Move(current, null)
        return Move(current, step.candidate)
    }

    private class Move(val before: RequestState?, val after: RequestState?)

    private class Transition(val before: RequestState, val candidate: RequestState)

    /**
     * 阶段进入事件。本类是这两条迁移的权威点（认领与等人只有这里改阶段），因此事件也只在这里出：
     * 任何下游读状态的组件都不重复记 —— 同一件事有两处报，就分不清哪一处才是发生过的那一处。
     *
     * 阶段没换就是没进入，**不记**。反复触碰同一条记录（重入的认领、同一处等待被两次走到）
     * 不得把受理与「等人」各刷一遍：刷一遍就看不出「这条请求被受理过几次」，而那正是要问的问题。
     */
    private fun logEntry(move: Move, id: String) {
        val event = stageEntryEvent(move.before?.phase, move.after?.phase) ?: return
        val log = runLog ?: return
        when (event) {
            LogEvent.REQUEST_ADMITTED -> log.info(LogSubsystem.TRANSPORT, event, "id" to id)
            LogEvent.REQUEST_WAITING_USER -> log.info(
                LogSubsystem.TRANSPORT, event,
                "id" to id, "kind" to (waitingKindOf(move.after?.phase) ?: "unknown"),
            )

            else -> Unit
        }
    }

    private fun target(id: String): File = File(stateDir, "$id.json")

    private fun stateFiles(): List<File> =
        stateDir.listFiles { file -> file.isFile && file.name.endsWith(JSON_SUFFIX) }?.toList() ?: emptyList()

    private fun trackedCount(): Int = stateFiles().size

    /**
     * 原子写：临时文件 + 改名。[force] 为真时在改名前对文件内容做
     * [FileChannel.force]。目录项同步与断电注入验证是后续执行链路的课题，
     * 本层只承诺文件内容级持久化。
     */
    private fun persist(state: RequestState, force: Boolean): Boolean = runCatching {
        stateDir.mkdirs()
        val temp = File.createTempFile("reqstate-", ".tmp", stateDir)
        try {
            FileChannel.open(
                temp.toPath(),
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                val bytes = toJson(state).toString().toByteArray(StandardCharsets.UTF_8)
                channel.write(ByteBuffer.wrap(bytes))
                if (force) channel.force(true)
            }
            if (!temp.renameTo(target(state.id))) {
                // 改名失败退到直接覆写兜底，保持账本与既有写路径同一策略。
                target(state.id).writeText(toJson(state).toString(), StandardCharsets.UTF_8)
            }
            true
        } finally {
            temp.delete()
        }
    }.getOrDefault(false)

    private fun toJson(state: RequestState): JSONObject = JSONObject().apply {
        put("id", state.id)
        put("digest", state.digest)
        put("capability", state.capability)
        put("phase", state.phase.name)
        put("createdAtMs", state.createdAtMs)
        state.admittedAtMs?.let { put("admittedAtMs", it) }
        put("ttlMs", state.ttlMs)
        put("mayHaveDispatched", state.mayHaveDispatched)
        state.terminalAtMs?.let { put("terminalAtMs", it) }
        state.resultJson?.let { put("resultJson", it) }
        put("cancelRequested", state.cancelRequested)
        // 墙钟只进文件作展示，读回时不消费：寿命判定一律走单调钟字段。
        put("updatedAtWallMs", wallNow())
    }

    private fun readState(file: File): RequestState? = runCatching {
        val json = JSONObject(file.readText(StandardCharsets.UTF_8))
        RequestState(
            id = json.getString("id"),
            digest = json.getString("digest"),
            capability = json.getString("capability"),
            phase = RequestPhase.valueOf(json.getString("phase")),
            createdAtMs = json.getLong("createdAtMs"),
            admittedAtMs = optionalLong(json, "admittedAtMs"),
            ttlMs = json.getLong("ttlMs"),
            mayHaveDispatched = json.optBoolean("mayHaveDispatched", false),
            terminalAtMs = optionalLong(json, "terminalAtMs"),
            resultJson = json.optString("resultJson").takeIf { json.has("resultJson") },
            cancelRequested = json.optBoolean("cancelRequested", false),
        )
    }.getOrNull()

    private fun optionalLong(json: JSONObject, key: String): Long? =
        if (json.has(key)) json.getLong(key) else null

    companion object {
        /** 目录内状态文件上限：超过即先强制清理，防灌不靠入口一处。 */
        const val MAX_TRACKED = 512

        /** [LogEvent.REQUEST_WAITING_USER] 的 kind 取值，封闭两值（不落任何参数原文）。 */
        const val WAITING_KIND_RELAY = "relay"
        const val WAITING_KIND_ANDROID = "android"

        /**
         * 两条阶段事件在调用点填的字段名，与模板占位符必须逐个相等：
         * 多一个少一个都会让 [interlock.relay.core.log.RunLog] 把残留的 `%{…}` 落成
         * `unresolved=…`，读日志的人会把模板残片当成消息内容。测试按这两组钉死。
         */
        internal val ADMITTED_FIELDS = setOf("id")
        internal val WAITING_FIELDS = setOf("id", "kind")

        /**
         * 阶段进入该记哪一条（纯函数，桌面 JVM 可直接判；日志本身在 JVM 上不可写）：
         * 阶段没换不记、只认这两处迁移，其余阶段由各自的调用点自己报。
         */
        internal fun stageEntryEvent(before: RequestPhase?, after: RequestPhase?): LogEvent? = when {
            before == null || after == null || before == after -> null
            after == RequestPhase.ADMITTED -> LogEvent.REQUEST_ADMITTED
            after == RequestPhase.WAITING_RELAY_USER || after == RequestPhase.WAITING_ANDROID_USER ->
                LogEvent.REQUEST_WAITING_USER

            else -> null
        }

        /** 等待阶段 → kind 字面量。两个封闭值之外一律 null（不猜、不透传）。 */
        internal fun waitingKindOf(phase: RequestPhase?): String? = when (phase) {
            RequestPhase.WAITING_RELAY_USER -> WAITING_KIND_RELAY
            RequestPhase.WAITING_ANDROID_USER -> WAITING_KIND_ANDROID
            else -> null
        }

        /** 终态回包的可重取窗口，与既有响应保留期对齐。 */
        const val RESULT_RETENTION_MS = 30L * 60L * 1000L

        /** 过期记录（含明确 EXPIRED 答复）的整体保留期。 */
        const val STATE_RETENTION_MS = 24L * 60L * 60L * 1000L

        /**
         * 非终态记录的过期判定（纯函数）：记录超过孤儿请求的寿命仍非终态即视为遗留。
         * 请求文件按同一寿命被回收，而活请求的最长寿命（客户端有效期上限两分钟）远短于
         * 这个寿命，不存在「活请求被误判」的窗口。遗留记录不再挡同 id 重投、不再放行
         * 执行，巡检按持久化边界把它收尾成终态，交给既有保留期清理。
         */
        fun nonTerminalRecordExpired(state: RequestState, nowMs: Long): Boolean =
            !state.isTerminal && nowMs - state.createdAtMs >= QuotaLedger.ORPHAN_REQUEST_TTL_MS

        private const val JSON_SUFFIX = ".json"
    }
}
