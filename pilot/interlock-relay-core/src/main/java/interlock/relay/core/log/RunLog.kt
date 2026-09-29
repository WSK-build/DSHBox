package interlock.relay.core.log

import android.util.Log
import interlock.relay.core.spi.RelayRedactor
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.runtime.wallNow
import java.io.File
import java.io.IOException

enum class LogLevel(val tag: String) { INFO("I"), WARN("W"), ERROR("E") }

/** 子系统标签，封闭集合。界面按此过滤并映射为本地化名称，不得由调用方自由填写。 */
enum class LogSubsystem { TRANSPORT, GATE, BACKEND, STORAGE, UI }

/**
 * 运行日志事件字典。正文一律由枚举与受限字段构成，
 * 因此通知正文、剪贴板内容、截图字节、联系人字段等无法被偶然写进日志。
 */
enum class LogEvent(val level: LogLevel, val template: String) {
    CHANNEL_STARTED(LogLevel.INFO, "channel started"),
    CHANNEL_STOPPED(LogLevel.INFO, "channel stopped"),
    CHANNEL_DEGRADED(LogLevel.WARN, "channel degraded dirsAvailable=%{dirs}"),
    // 启动阶段事件与 CHANNEL_* 同邻出现：CHANNEL_* 说信箱这一环此刻在不在，
    // RUNTIME_* 说整条装配序列走到哪了。装配是分步进行的，中途失败时只有带步骤
    // 短名的 DEGRADED 一条能指出断在哪一环（step 取 ledger/assets/shizuku/mailbox/
    // channel_service/watchdog 这类短名，与装配序列里的步骤一一对应）。
    RUNTIME_STARTING(LogLevel.INFO, "runtime starting"),
    RUNTIME_RUNNING(LogLevel.INFO, "runtime running"),
    RUNTIME_DEGRADED(LogLevel.WARN, "runtime degraded step=%{step} cause=%{cause}"),
    RUNTIME_STOPPED(LogLevel.INFO, "runtime stopped"),
    REQUEST_DROPPED(LogLevel.WARN, "request dropped file=%{file} cause=%{cause}"),
    REQUEST_TOO_LARGE(LogLevel.ERROR, "request rejected oversized bytes=%{bytes}"),
    RESPONSE_WRITE_FAILED(LogLevel.ERROR, "response write failed id=%{id}"),
    RESPONSE_OVERWRITTEN(LogLevel.WARN, "pre-existing response replaced id=%{id}"),
    RESPONSE_TOO_LARGE(LogLevel.WARN, "response exceeded size cap id=%{id}"),
    MAILBOX_BACKPRESSURE(LogLevel.WARN, "mailbox backpressure pending=%{pending} evicted=%{evicted} queueDepth=%{queueDepth} oldestAgeMs=%{oldestAgeMs}"),
    // 控制通道与工作循环各自起停、互不遮挡：起停各记一条，读日志的人才能分清
    // 「通道断过」与「工作循环断过」。被拒的控制请求只记操作与受控原因短语，
    // 不透传投递内容。
    CONTROL_STARTED(LogLevel.INFO, "control channel started"),
    CONTROL_STOPPED(LogLevel.INFO, "control channel stopped"),
    CONTROL_REQUEST_REJECTED(LogLevel.WARN, "control request rejected op=%{op} cause=%{cause}"),
    // 取消的两条结论：CANCELLED 只在宿主可证请求尚未执行时给出（请求文件被原样搬走）；
    // 已被认领的请求只能记下取消意愿，最终结论由执行链自己的终态给出。
    REQUEST_CANCELLED(LogLevel.INFO, "request cancelled id=%{id}"),
    REQUEST_CANCEL_REQUESTED(LogLevel.INFO, "request cancel requested id=%{id}"),
    UNSAFE_PATH_REJECTED(LogLevel.ERROR, "unsafe path rejected name=%{name}"),
    APPROVAL_DENIED(LogLevel.INFO, "denied cap=%{cap} code=%{code}"),
    APPROVAL_RESULT(LogLevel.INFO, "approval result cap=%{cap} choice=%{choice}"),
    APPROVAL_TIMEOUT(LogLevel.WARN, "approval timeout cap=%{cap}"),
    OVERLAY_UNAVAILABLE(LogLevel.WARN, "overlay approval unavailable cap=%{cap} cause=%{cause} at=%{at}"),
    NOTIFY_UNAVAILABLE(LogLevel.WARN, "approval notification unavailable cap=%{cap} cause=%{cause}"),
    // 审批呈现路由每次重算（展示位变化与前台切换共用同一条路径）都记录通知通路的
    // 呈现状态：可用≠可见，投递成功最多是「已投递、可见性未证实」。只作诊断读数。
    APPROVAL_ROUTED(LogLevel.INFO, "approval routed cap=%{cap} postState=%{postState}"),
    SHIZUKU_STATE(LogLevel.INFO, "shizuku state binder=%{binder} granted=%{granted}"),
    SHIZUKU_PROBE_FAILED(LogLevel.WARN, "shizuku probe failed where=%{where} cause=%{cause}"),
    SHIZUKU_BIND_FAILED(LogLevel.WARN, "shizuku user service bind failed cause=%{cause}"),
    SHIZUKU_SERVICE_CONNECTED(LogLevel.INFO, "shizuku user service connected"),
    SHIZUKU_SERVICE_DEAD(LogLevel.WARN, "shizuku user service disconnected"),
    PROJECTION_CONSENT_GRANTED(LogLevel.INFO, "screen capture consent granted"),
    PROJECTION_CONSENT_DENIED(LogLevel.INFO, "screen capture consent denied"),
    PROJECTION_CONSENT_TIMEOUT(LogLevel.WARN, "screen capture consent timed out"),
    PROJECTION_CONSENT_PENDING(LogLevel.INFO, "screen capture consent awaiting user"),
    PROJECTION_TOKEN_FAILED(LogLevel.WARN, "media projection token unavailable"),
    PROJECTION_REVOKED(LogLevel.INFO, "screen capture stopped by user"),
    PROJECTION_FRAME_FAILED(LogLevel.WARN, "projection frame failed cause=%{cause}"),
    PROJECTION_NO_UI(LogLevel.WARN, "screen capture needs the assistant page in front"),
    SWEEP_DONE(LogLevel.INFO, "sweep done requests=%{requests} responses=%{responses} artifacts=%{artifacts} freed=%{freed}"),
    QUOTA_EXHAUSTED(LogLevel.ERROR, "quota exhausted want=%{want}"),
    MANUAL_CLEAR(LogLevel.INFO, "manual clear freed=%{freed}"),
    ATOMIC_WRITE_FAILED(LogLevel.WARN, "atomic write failed file=%{file}"),
    DIRS_UNAVAILABLE(LogLevel.ERROR, "working directories unavailable"),
    LEDGER_CORRUPTED(LogLevel.WARN, "quota ledger corrupted %{detail}"),
    BACKEND_FAILURE(LogLevel.WARN, "backend failure cap=%{cap} code=%{code}"),
    // 本地在预算内放弃等待一条远端事务时记：远端那半个没有被取消，会自己跑完并把
    // 回执写进一个再没人读的 Future。事后对「客户端超时了、设备上动作却后来完成了」
    // 这类账，靠的就是这一条。
    REMOTE_RESULT_DROPPED(LogLevel.WARN, "remote result dropped kind=%{kind} waitedMs=%{waitedMs}"),
    CALL_DONE(LogLevel.INFO, "call done cap=%{cap} result=%{result} ms=%{ms} bytes=%{bytes}"),
    CALL_ARGS(LogLevel.INFO, "call args cap=%{cap} digest=%{digest}"),
    CALL_FAILED(LogLevel.ERROR, "call failed id=%{id} type=%{type}"),
    CALL_DEFERRED(LogLevel.WARN, "call deferred id=%{id} cause=%{cause}"),
    // 受理/了结成对出现，圈出一条请求在信箱侧的完整驻留：从文件搬进 processing 起，
    // 到处理走出终态止，ms 从受理的单调时刻起算，覆盖审批与执行全程。异常了结由
    // CALL_FAILED 承担，不落 CALL_SETTLED。字段名与占位符必须与调用点咬合，
    // 对不上的字段会被渲染成 unresolved（见 [RunLog.render] 的标注规则）。
    CALL_ACCEPTED(LogLevel.INFO, "request accepted id=%{id}"),
    CALL_SETTLED(LogLevel.INFO, "request settled ms=%{ms}"),
    ARTIFACT_CREATE_FAILED(LogLevel.ERROR, "artifact create failed cap=%{cap}"),
    AUDIT_WRITE_FAILED(LogLevel.WARN, "audit write failed %{detail}"),
    AUDIT_PURGED(LogLevel.INFO, "audit purged files=%{files}"),
    // 请求状态存储（控制通道的持久层）三段生命周期：建、终、恢复。dispatch guard
    // 是「动作提交前先把已派发标记钉进持久介质」的那一步，它失败时执行必须被拒绝，
    // 属于 ERROR——事后排查「为什么这条一直没执行」全靠这一条。
    REQUEST_STATE_CREATED(LogLevel.INFO, "request state created id=%{id} cap=%{cap}"),
    REQUEST_STATE_TERMINAL(LogLevel.INFO, "request state terminal id=%{id} phase=%{phase}"),
    REQUEST_RECOVERED(LogLevel.WARN, "request recovered id=%{id} phase=%{phase}"),
    REQUEST_DISPATCH_GUARD_FAILED(LogLevel.ERROR, "dispatch guard failed id=%{id}"),
    // 派发前置守卫的另一支拒绝：请求记录此刻无可执行状态（已终态或不存在）。与上一条
    // 的「边界标记没能落盘」分开说：前者重试同一条没有意义，后者是宿主侧持久化故障。
    // 事后排查「为什么这条一直没执行」靠这两条各归各位。
    REQUEST_DISPATCH_NOT_EXECUTABLE(LogLevel.WARN, "dispatch refused not executable id=%{id} phase=%{phase}"),
    // ── 一条请求的阶段事件（从受理到回包，逐步可查）──
    // 这些是「排障时把一条请求的完整旅程串起来」的那组点：哪一步进的、等谁、判了什么、
    // 派发前钉没钉住、最后什么结局、回包多大。字段一律只放标识与状态，参数原文不落。
    REQUEST_ADMITTED(LogLevel.INFO, "request admitted id=%{id}"),
    REQUEST_WAITING_USER(LogLevel.INFO, "request waiting for user id=%{id} kind=%{kind}"),
    REQUEST_APPROVAL_DECIDED(LogLevel.INFO, "approval decided id=%{id} verdict=%{verdict}"),
    REQUEST_DISPATCH_ARMED(LogLevel.INFO, "dispatch armed id=%{id}"),
    REQUEST_COMPLETED(LogLevel.INFO, "request completed id=%{id} state=%{state} ms=%{ms}"),
    RESPONSE_PUBLISHED(LogLevel.INFO, "response published id=%{id} bytes=%{bytes}"),
    // ── 呈现与后端链路 ──
    // 审批最终落在哪个入口（悬浮卡/通知栏/页内卡/无）与当前排队数：实测里「怎么同时弹了
    // 两个」「为什么什么都没看到」都要靠这一条还原现场。
    APPROVAL_PRESENTER(LogLevel.INFO, "approval presented via=%{via} waiting=%{waiting}"),
    // 控制通道每条操作的耗时与结局；后端每次真实调用的耗时与结局。
    CONTROL_OP(LogLevel.INFO, "control op=%{op} result=%{result} ms=%{ms}"),
    // 提问（ask）的两条读数：答到了给所选/驳回与耗时；没答到给原因与耗时。
    // 与 CONTROL_OP 的分工：那条只说「控制循环受理了这一条」，而排障要回答的是
    // 「卡到底上屏了吗、用户答了吗」——只有呈现层回到结论才说得清。
    ASK_ANSWERED(LogLevel.INFO, "ask answered id=%{id} choice=%{choice} ms=%{ms}"),
    ASK_UNAVAILABLE(LogLevel.WARN, "ask unavailable id=%{id} cause=%{cause} ms=%{ms}"),
    BACKEND_CALL(LogLevel.INFO, "backend call cap=%{cap} backend=%{backend} result=%{result} ms=%{ms}"),
    // 远端事务：发出时记等待上限，本地放弃时记「已递交多久」——「客户端超时了、
    // 设备上动作却后来完成了」这类账全靠这两条。
    REMOTE_SUBMITTED(LogLevel.INFO, "remote submitted cap=%{cap} waitMs=%{waitMs}"),
    REMOTE_GIVE_UP(LogLevel.WARN, "remote gave up cap=%{cap} submittedAgoMs=%{ago}"),
    ;
}

/**
 * 运行日志，面向问题定位，不参与任何判定，也不作为授权依据。
 *
 * 落点 `logs` 目录只为了借用系统的日志清理白名单（该目录按 `.log` 后缀整目录清理）；
 * 工程既有的诊断页导出**只列固定几个文件名，不会自动带上本模块的日志**，
 * 因此本模块必须自己提供查看与导出出口，不能假设日志能被别处取走。
 *
 * 全进程只允许一个实例：轮转按文件改名，多实例会互相滚掉对方的内容。
 */
class RunLog(
    logsDir: File,
    private val verboseEnabled: () -> Boolean = { false },
    /** 字段脱敏口；缺省恒等。宿主可注入自己的脱敏规则。 */
    private val redactor: interlock.relay.core.spi.RelayRedactor = interlock.relay.core.spi.RelayRedactor.IDENTITY,
) {

    init {
        // 本类按「全进程只允许一个实例」设计（轮转按文件改名，多实例会互相滚掉对方内容），
        // 因此这里登记的就是全进程那一个。存在的理由只有一处：[interlock.relay.core.surface.BackendDispatcher]
        // 由装配根构造时并不接收日志（它那时只被问「能不能服务」），而「这次调用落到哪个后端、
        // 多久、什么结局」这条 BACKEND_CALL 是排障时唯一一条能回答「为什么这条一直没执行」的
        // 读数 —— 拿不到日志就等于这条读数不存在。取用点在派发那一刻解析，不在构造时。
        current = this
    }

    private val lock = Any()
    private val logFile = File(logsDir, FILE_NAME)
    private val previousFile = File(logsDir, PREV_FILE_NAME)
    private val ring = ArrayDeque<Entry>()

    fun info(subsystem: LogSubsystem, event: LogEvent, vararg fields: Pair<String, String>) =
        write(subsystem, event, fields)

    fun warn(subsystem: LogSubsystem, event: LogEvent, vararg fields: Pair<String, String>) =
        write(subsystem, event, fields)

    fun error(subsystem: LogSubsystem, event: LogEvent, vararg fields: Pair<String, String>) =
        write(subsystem, event, fields)

    /** 详细日志含参数摘要，默认关闭；开启后仅追加一次同事件的详版。 */
    fun verbose(subsystem: LogSubsystem, event: LogEvent, vararg fields: Pair<String, String>) {
        if (verboseEnabled()) write(subsystem, event, fields, verbose = true)
    }

    /** 按事件自带级别写入，调用方不必逐处判断级别。 */
    fun event(subsystem: LogSubsystem, event: LogEvent, vararg fields: Pair<String, String>) =
        write(subsystem, event, fields)

    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }

    fun usageBytes(): Long = runCatching { logFile.length() + previousFile.length() }.getOrDefault(0L)

    private fun write(
        subsystem: LogSubsystem,
        event: LogEvent,
        fields: Array<out Pair<String, String>>,
        verbose: Boolean = false,
    ) {
        val body = render(event, fields)
        val entry = Entry(
            atWallMs = wallNow(),
            atMonotonicMs = monotonicNow(),
            level = event.level,
            subsystem = subsystem,
            event = event,
            text = body,
            verbose = verbose,
        )
        synchronized(lock) {
            ring.addLast(entry)
            while (ring.size > RING_CAPACITY) ring.removeFirst()
            appendToFile("$LINE_PREFIX ${event.level.tag}/${subsystem.name.lowercase()} $body")
        }
        when (event.level) {
            LogLevel.ERROR -> Log.e(TAG, "relay: $body")
            LogLevel.WARN -> Log.w(TAG, "relay: $body")
            LogLevel.INFO -> if (verbose) Log.i(TAG, "relay: $body")
        }
    }

    /**
     * 字段名与模板占位符不一致时显式标注 unresolved：漏替换会把 `%{foo}` 原样留在正文里，
     * 读日志的人会以为那是消息内容的一部分。
     */
    private fun render(event: LogEvent, fields: Array<out Pair<String, String>>): String {
        var text = event.template
        fields.forEach { (key, value) ->
            text = text.replace("%{$key}", safeValue(value))
        }
        if (text.contains("%{")) text += " unresolved=${UNRESOLVED.find(text)?.groupValues?.get(1)}"
        return text.take(MAX_MESSAGE_CHARS)
    }

    private fun safeValue(value: String): String {
        val filtered = value.filter { !it.isISOControl() }.take(MAX_FIELD_CHARS)
        return redactor.redact(filtered).ifBlank { "-" }
    }

    private fun appendToFile(line: String) {
        try {
            logFile.parentFile?.mkdirs()
            if (logFile.length() > MAX_FILE_BYTES) {
                if (previousFile.exists()) previousFile.delete()
                logFile.renameTo(previousFile)
            }
            logFile.appendText(line + "\n")
        } catch (_: IOException) {
            // 日志写失败不得影响能力调用，也不在此处再记一条造成递归。
        }
    }

    /** 一条结构化日志。界面按 [event] 取字符串资源渲染，不直接显示 [text]。 */
    data class Entry(
        val atWallMs: Long,
        val atMonotonicMs: Long,
        val level: LogLevel,
        val subsystem: LogSubsystem,
        val event: LogEvent,
        val text: String,
        val verbose: Boolean,
    )

    companion object {
        private const val TAG = "RelayRunLog"
        private const val LINE_PREFIX = "relay"

        /**
         * 全进程那一个 [RunLog]。由构造函数登记（见 [init]），不在这里做初始化顺序的假设：
         * 登记发生在实例建出来的那一刻，取用发生在派发那一刻，两者之间隔着整个装配序列。
         */
        @Volatile
        private var current: RunLog? = null

        /**
         * 取当前那一个运行日志；宿主还没建出日志实例时返回 null。
         * 只有「本来就不该有日志」的场合（未装配的容器、单测）才允许它为 null。
         */
        @JvmStatic
        fun currentOrNull(): RunLog? = current

        /** 轮转文件仍以 `.log` 结尾，否则会被按后缀过滤的清理逻辑漏掉。 */
        private const val FILE_NAME = "relay.log"
        private const val PREV_FILE_NAME = "relay-prev.log"
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private const val RING_CAPACITY = 512
        private const val MAX_MESSAGE_CHARS = 2000
        private const val MAX_FIELD_CHARS = 64
        /**
         * 收尾的 `}` 必须转义。Android 的 `java.util.regex` 由 ICU 实现，未配对的 `}`
         * 在 `Pattern.compile` 阶段即抛 `PatternSyntaxException`（JVM 侧的实现容忍它），
         * 而本正则在类初始化时求值，故会让整个模块的界面在启动时崩溃。
         */
        private val UNRESOLVED = Regex("%\\{([A-Za-z]+)\\}")
    }
}
