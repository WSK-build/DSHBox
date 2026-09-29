package interlock.relay.core.transport

import android.os.FileObserver
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.RelayRequest
import interlock.relay.core.protocol.RelayResponse
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
import java.nio.charset.StandardCharsets
import kotlin.coroutines.cancellation.CancellationException

/**
 * 能力调用的处理契约。入参已通过形状校验，出参必须是完整结果。
 *
 * [budgetMs] 是这条请求在宿主侧还能用掉的时间（客户端 ttl 扣掉已耗与回写余量）。
 * 把它交给处理方，需要用户在场的能力才能在预算用尽前自己回一句「还在等人」，
 * 而不是被这里判成一次通道超时。
 */
fun interface RelayRequestHandler {
    suspend fun handle(request: RelayRequest, budgetMs: Long): HandlerResult
}

/**
 * 一次能力调用的处理结果。
 *
 * [Done] 是既有语义：响应已得出，按完结路径提交终态并回写。[Parked] 表示调用正把
 * 通道时间让给「等人点」：审批已经提交、答复还没有，处理方把续行体交回通道托管——
 * 通道不写响应、不删请求文件，登记挂起项后释放执行槽去接后面的请求；答复（或审批
 * 到点）到来时由 [ParkedRequestBridge.resumeParked] 推进原请求的续行。
 */
sealed interface HandlerResult {
    /** 处理已完成：按既有路径提交终态并回写响应。 */
    data class Done(val response: RelayResponse) : HandlerResult

    /** 等用户答复中：登记挂起项后释放执行槽，响应由续行体在答复之后给出。 */
    data class Parked(val park: ParkedRequest) : HandlerResult
}

/**
 * 一条挂起中的请求，由处理方在返回 [HandlerResult.Parked] 前构造。续行体捕获答复
 * 之后要走的全部上下文（裁决结果、参数、预算起点），通道只负责在答复到达时把它
 * 排上串行执行——执行槽在等待期间是空闲的。
 *
 * 挂起登记全部在内存：进程死后登记随进程消失，processing 文件由既有孤儿清扫按
 * 保留期回收，v2 状态存储由通道启动时的崩溃恢复按持久化边界收尾。重启后
 * QUEUED/WAITING_* 一律不自动恢复、也不沿用旧批准，这里无需任何额外动作。
 */
class ParkedRequest(
    val requestId: String,
    /** 答复（或审批到点）之后的续行体：取答复、按结论执行，产出最终响应。在串行上下文里执行。 */
    val resume: suspend () -> RelayResponse,
    /** 审批截止的单调时刻：过点之后由登记簿的到点兜底触发一次超时续行（先到者赢）。 */
    val approvalDeadlineAtMs: Long,
    /** 续行整体的单调截止时刻：客户端有效期扣到执行为止，超限按通道超时收场。 */
    val executionDeadlineAtMs: Long,
    /** 谁的审批在等（能力线名）。只进日志，不参与任何判定。 */
    val approvalOwner: String,
)

/**
 * 挂起-续行的对接面：处理方用它登记挂起项、在答复到达时触发续行，由通道服务端实现。
 * 处理方拿不到它（未接入）时走内联等待。
 */
interface ParkedRequestBridge {
    /** 还有挂起名额吗。满员时处理方退回内联等待，不放宽容量。 */
    fun hasParkCapacity(): Boolean

    /** 登记一个挂起项。同一 requestId 只登记一次；登记成功后 [resumeParked] 才会对它生效。 */
    fun register(park: ParkedRequest): Boolean

    /**
     * 触发一次续行。按 requestId 去重、先到者赢：答复事件与到点兜底竞速、或同一事件
     * 被投递两次时，重复触发是空操作。续行体在本通道的串行执行上下文里运行。
     */
    fun resumeParked(requestId: String)
}

/**
 * 挂起审批的登记簿：并发安全、容量有界。登记只发生在串行处理路径上，摘取（续行
 * 触发）可能来自多个线程（答复事件、到点兜底、主循环），因此读改一律走并发映射。
 */
internal class ParkedRegistry(
    private val capacity: Int,
    /**
     * 到点兜底的宽限：审批过点之后再等这么久才触发超时续行。宽限盖过等待环一轮的
     * 内联窗口，让等待环自己的到点判定（更精确）正常先到，兜底只接住等待环失联的残局。
     */
    private val backstopGraceMs: Long,
) {

    private val parks = java.util.concurrent.ConcurrentHashMap<String, ParkedRequest>()

    fun register(park: ParkedRequest): Boolean = synchronized(this) {
        if (parks.size >= capacity) return@synchronized false
        parks.putIfAbsent(park.requestId, park) == null
    }

    /** 摘取即去重：第二次触发同一 requestId 时这里已经拿不到东西。 */
    fun take(requestId: String): ParkedRequest? = parks.remove(requestId)

    fun size(): Int = parks.size

    /**
     * 审批已过点（含兜底宽限）的挂起项，逐个触发一次超时续行。
     * 按截止时刻先后返回：并发映射本身没有稳定次序，最早到点的先续行才是可读的次序。
     */
    fun expired(nowMs: Long): List<String> = synchronized(this) {
        parks.values
            .filter { nowMs - it.approvalDeadlineAtMs >= backstopGraceMs }
            .sortedBy { it.approvalDeadlineAtMs }
            .map { it.requestId }
    }

    /** 通道停止时清空：等待环已随作用域取消，残留登记只会让下一次会话收到幽灵续行。 */
    fun clear() = parks.clear()
}

/**
 * 通道服务端：消费请求目录、串行处理、写回响应目录。
 *
 * 使用前置条件（违反任一条都会造成静默失效）：
 * 1. 同一请求目录在进程内只允许一个本类实例；不得有其它组件向请求目录改名投递。
 * 2. 启动前必须完成账本加载与首轮清扫，否则配额从 0 起算。
 * 3. [start] 与 [stop] 只从同一线程调用。
 * 4. [handler] 若要展示界面，必须自行切主线程；本类只在 [Dispatchers.IO] 上跑，
 *    并发度限制只覆盖续体投递，不保证全程同一个线程。
 * 5. 助手侧写入必须先写分片再改名；未这样做的助手由「首次见到 + 静默窗口」兜住，
 *    该窗口不读文件时间戳（见 [SETTLE_MS] 说明）。
 * 6. 清扫与长耗时执行不得并发（如把入站文件复制进媒体库）。真实的保证是两条：
 *    周期清扫只发生在 [Dispatchers.IO.limitedParallelism(1)] 的本循环上下文里；
 *    另一次清扫发生在装配根 `start()` 内，而那时本类尚未 [start]，循环还不存在。
 *    `StorageReaper` 自己不查在途调用 —— 新增清扫调用点必须落在前两者之一，
 *    否则要改的就是这条约定本身。
 *
 * 串行处理是有意为之：既保证「共享同一采集会话的能力不同时在途」，
 * 也让授权记录的顺序与实际执行顺序一致。
 *
 * [stateStore] 接上 v2 执行链后，本类承担三件额外的事，全部围绕状态记录展开，
 * 不改变任何执行判定：启动时崩溃恢复未完成请求、随行启动控制通道
 * （[ControlChannelServer]，与工作循环完全隔离）、在认领与了结两个点上把阶段
 * 写进状态存储。为 null 时（未接 v2）行为与没有任何状态存储时逐字节一致。
 *
 * 本类同时是 [ParkedRequestBridge] 的实现方：等用户答复的请求由处理方在这里登记挂起、
 * 释放执行槽，续行体在答复（或审批到点）之后回到与本循环同一条串行上下文上执行——
 * 在途名额、清扫互斥与配额记账因此照旧成立，并发度仍是一。
 */
class MailboxServer(
    private val paths: RelayPaths,
    private val reaper: StorageReaper,
    private val runLog: RunLog,
    private val handler: RelayRequestHandler,
    /**
     * 设备此刻醒着与否，只决定空转轮询的节奏，不决定取不取件。
     *
     * 判据是「设备是否醒着」而不是「本应用是否在前台」：沙盒里的助手多半在后台跑。
     */
    private val isDeviceInteractive: () -> Boolean = { true },
    private val onMaintenance: () -> Unit = {},
    private val now: () -> Long = ::monotonicNow,
    /** v2 请求状态存储；null = 未接 v2 执行链，全部 v2 钩子静默跳过。 */
    private val stateStore: RequestStateStore? = null,
    /**
     * 提问呈现器：控制通道的 ask 用它把助手的问题当面摆给用户。
     * null = 本宿主没接提问通路（ask 会得到明确的「无呈现面」拒绝）。
     */
    private val askPresenter: interlock.relay.core.interlock.InterlockPrompt? = null,
) : ParkedRequestBridge {

    private val signals = Channel<Unit>(CAPACITY)

    /**
     * 全链的串行执行上下文。本循环与续行体共用同一个受限视图是硬要求：
     * 每次调用 `Dispatchers.IO.limitedParallelism(1)` 都会产生一个**新的**限流视图，
     * 两个视图各自允许一个在途，等于把「同时只有一个在途」拆掉。
     */
    private val workContext = Dispatchers.IO.limitedParallelism(1)

    /** 挂起等答复的请求。登记只在串行处理路径上发生，摘取（续行触发）来自多方。 */
    private val parks = ParkedRegistry(PARKED_CAPACITY, PARKED_BACKSTOP_GRACE_MS)

    /** 续行要投递的作用域：与工作循环同一个进程级作用域，停止时一并作废。 */
    @Volatile
    private var loopScope: CoroutineScope? = null

    /** 请求文件被宿主首次看到的单调时刻，用于静默期判定。只存在于内存。 */
    private val firstSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** 请求被认领进 processing 的单调时刻，供探活报「最老待办」。只存在于内存。 */
    private val admittedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 经改名到达（MOVED_TO 携带名字）的请求文件：分片写完才落地，可免静默期。
     * 观察者线程写入、消费循环读写，末尾与目录实况对齐清理。
     */
    private val arrivalMarks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 独立的控制通道：与工作循环隔离的轻量消费循环，探活/查状态/取消不被长执行挡住。 */
    private var control: ControlChannelServer? = null

    /** FileObserver 被回收即停止投递，必须由本类持强引用。 */
    private var observer: FileObserver? = null
    private var job: Job? = null

    @Volatile
    private var lastWorkAtMs = 0L

    /** 目录可用性由每轮自行确认；被沙盒侧删掉的信箱目录不能让界面一直显示"运行中"。 */
    @Volatile
    private var dirsOk = false

    val isRunning: Boolean get() = job?.isActive == true && dirsOk

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        // 全量目录尽力建一次（暂存/账本/日志各有自己的失败痕迹），但通道的生死
        // 只由收发用到的四个目录决定：日志目录被宿主自己的清理规则碰掉，
        // 不该让界面显示"通道不可用"。
        paths.ensureWritableDirs()
        if (!paths.ensureMailboxDirs()) {
            dirsOk = false
            runLog.error(LogSubsystem.STORAGE, LogEvent.DIRS_UNAVAILABLE)
            return
        }
        dirsOk = true
        // 崩溃恢复先于一切消费：上一世的未完成请求按持久化边界收尾（可证未派发的以
        // INTERRUPTED_BEFORE_EXECUTION 终结，否则保守转 UNKNOWN），恢复必须发生在
        // 控制服务起来之前，否则 status 会把已收尾的请求读成还在排队。
        stateStore?.recoverUnfinished()
        val store = stateStore
        if (store != null) {
            control = ControlChannelServer(
                paths = paths,
                reaper = reaper,
                stateStore = store,
                runLog = runLog,
                health = { workLoopHealth() },
                askPresenter = askPresenter,
                now = now,
            ).also { it.start(scope) }
        }
        // 全链是阻塞文件 IO，固定在 IO 线程并以并发度 1 运行，
        // 既防止误用主线程，也把「同时只有一个在途」写进执行环境而不是靠约定。
        loopScope = scope
        job = scope.launch(workContext) {
            runLog.info(LogSubsystem.TRANSPORT, LogEvent.CHANNEL_STARTED)
            try {
                loop()
            } finally {
                stopWatching()
            }
        }
    }

    fun stop() {
        control?.stop()
        control = null
        job?.cancel()
        job = null
        // 挂起登记与等待环一起作废：等待环挂在同一个进程级作用域上，作用域一停就没人
        // 再推进答复，残留登记只会让下一次 start 收到幽灵续行。挂起请求的文件留在
        // processing 里，交给既有孤儿清扫与状态存储的启动恢复分流收尾。
        loopScope = null
        parks.clear()
        runLog.info(LogSubsystem.TRANSPORT, LogEvent.CHANNEL_STOPPED)
    }

    /**
     * 装上唯一的监听器，先把前一个停掉。
     *
     * 必须无条件替换，不能「已有监听器就直接 return」：那样会留下两个问题 ——
     * 停不掉的旧观察者随字段覆盖而泄漏；残留的旧循环与新循环交替时，新循环会因为字段非空
     * 而不装监听，旧循环结束时又把新循环依赖的那个停掉。替换之后任一时刻最多一个
     * 观察者在跑，且它属于当前循环。
     */
    private fun restartWatching() {
        observer?.stopWatching()
        observer = object : FileObserver(paths.inboxDir, MASK) {
            override fun onEvent(event: Int, path: String?) {
                // MOVED_TO 的 path 是改名进入的那个名字：它经改名到达，分片已写完，
                // 消费时不必再等静默期。标记集合由观察者线程写入，对齐清理在消费循环里。
                if (path != null) arrivalMarks.add(path)
                signals.trySend(Unit)
            }
        }.also {
            // 起监听失败不该要了整条通道的命：这条循环之外没有第二次安装点，
            // 抛出去就是信箱从此只等轮询、日志里也查不到是哪一步断的。
            runCatching { it.startWatching() }
                .onFailure { cause ->
                    runLog.warn(
                        LogSubsystem.TRANSPORT,
                        LogEvent.CHANNEL_DEGRADED,
                        "where" to "startWatching",
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
     * 信箱目录被沙盒侧删掉或换成普通文件时自行复位（见 [RelayPaths.ensureMailboxDirs]）。
     *
     * 挂在旧目录上的 FileObserver 不会随新目录复活（它盯的是已被删除的那个 inode），
     * 而 inode 身份在 Java 侧不可观测，所以按时间整条重建监听 —— 比猜"目录还是不是原来那个"
     * 更便宜，也保证最坏 [OBSERVER_REBUILD_MS] 内自愈。
     */
    private fun ensureChannelDirs(now: Long, lastRebuild: Long): Long {
        val healthy = paths.ensureMailboxDirs()
        if (healthy != dirsOk) {
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.CHANNEL_DEGRADED, "dirs" to healthy.toString())
            dirsOk = healthy
        }
        if (!healthy) return lastRebuild
        if (now - lastRebuild < OBSERVER_REBUILD_MS) return lastRebuild
        restartWatching()
        return now
    }

    private suspend fun loop() {
        var lastSweep = 0L
        var lastMaintenance = 0L
        var lastRebuild = 0L
        restartWatching()
        while (currentCoroutineContext().isActive) {
            val ts = now()
            lastRebuild = ensureChannelDirs(ts, lastRebuild)
            if (!dirsOk) {
                delay(IDLE_POLL_MS)
                continue
            }
            // 息屏期间投进来的件必须有人取：只按热态节奏轮询，件会一直躺在信箱里没人取，
            // 助手侧表现为「投了但永远没有回包」。因此 CPU 由通道那条前台服务按租期留住
            // （见 [interlock.relay.core.exec.direct.RelayService]），休眠时照常取件，
            // 只是不再抢热态节奏——醒着才有意义，休眠期间落地的件由 FileObserver 即时送达。
            val waitMs = if (isDeviceInteractive()) pollIntervalMs() else IDLE_POLL_MS
            withTimeoutOrNull(waitMs) { signals.receive() }
            drain()
            // 挂起审批的到点兜底：等待环正常收场时会先一步给出终局，这里只接住等待环
            // 失联的挂起项（作用域被取消一类的残局），到点推进一次超时续行。幂等由
            // 登记簿的摘取去重保证，先到者赢。
            parks.expired(now()).forEach { resumeParked(it) }
            val after = now()
            // 巡检与回收各有各的节奏，因为两者要回答的问题不同：回收是几十分钟一次的
            // 多目录文件遍历，巡检是一次只读若干系统状态、变了才写盘的轻量核对。
            // 挂在一起时，用户在系统设置里刚给出的授权要等满回收周期才会反映到能力清单上，
            // 助手读着那份滞后的能力清单就不会去调用那条已经可用的能力。
            if (after - lastMaintenance >= MAINTENANCE_INTERVAL_MS) {
                lastMaintenance = after
                // 授权记录落在任何外部清理白名单之外，保留期回收只能由本模块自己驱动。
                // 它失败只该让本次维护没做成：异常逃出会终止整条消费循环。
                runCatching(onMaintenance)
                    .onFailure { cause ->
                        runLog.warn(
                            LogSubsystem.TRANSPORT,
                            LogEvent.CHANNEL_DEGRADED,
                            "where" to "maintenance",
                            "cause" to cause.javaClass.simpleName,
                        )
                    }
                // 请求状态存储的保留期清理随巡检节拍走：终态回包的可重取窗与 EXPIRED
                // 形态的整体留存在这里收敛，不再只靠登记时的顺手清理（登记停了，
                // 清理也就停了，目录会朝上限无界涨）。
                stateStore?.let { store ->
                    runCatching { store.purgeExpired(now()) }
                        .onFailure { cause ->
                            runLog.warn(
                                LogSubsystem.TRANSPORT,
                                LogEvent.CHANNEL_DEGRADED,
                                "where" to "state-purge",
                                "cause" to cause.javaClass.simpleName,
                            )
                        }
                }
            }
            if (after - lastSweep >= SWEEP_INTERVAL_MS) {
                lastSweep = after
                // 回收要遍历多个目录，它失败只该让这一轮回收没做成。异常逃到这里会带走
                // 整条消费循环：进程还活着、界面还开着，信箱却不再取件 ——
                // 外部表现是「投递了但永远没有回应」。
                runCatching { reaper.sweep() }
                    .onFailure { cause ->
                        runLog.warn(
                            LogSubsystem.TRANSPORT,
                            LogEvent.CHANNEL_DEGRADED,
                            "where" to "sweep",
                            "cause" to cause.javaClass.simpleName,
                        )
                    }
            }
        }
    }

    private fun pollIntervalMs(): Long =
        if (now() - lastWorkAtMs < HOT_WINDOW_MS) HOT_POLL_MS else IDLE_POLL_MS

    private suspend fun drain() {
        val all = paths.inboxDir.listFiles()?.filter { it.isFile } ?: return
        // 在途分片不是畸形名：它们是写入方落了一半的正文，畸形回包碰了就会撞掉
        // 写入方随后的改名一步。候选集先把它们摘出去，剩下的才按畸形回话。
        answerMalformedNames(all.filterNot { EnvelopeCodec.isRequestFileName(it.name) || isIncomingPartName(it.name) })
        // 先给本轮见到的新文件记账（真实到达序），再排序：文件名顺序与到达顺序无关，
        // 按名字排队会让「来得早、名字大」的请求永远排在后来者之后。
        val pendingNames = all.filter { EnvelopeCodec.isRequestFileName(it.name) }.map { it.name }
        pendingNames.forEach { name -> firstSeen.putIfAbsent(name, now()) }
        val ordered = orderPending(pendingNames.map { it to firstSeen[it] })
        val victims = evictionVictims(ordered, MAX_PENDING_FILES)
        var queue = ordered
        if (victims.isNotEmpty()) {
            // 超出上限的部分按到达序把最晚到的改成可重试失败回给助手：留在原地只会让每轮
            // listFiles+排序越来越贵。但**驱逐之后必须继续处理留下的那些** ——
            // 驱逐完就 return 等于让灌信箱的一方获得"让通道对所有人停工"的能力。
            queue = ordered.take(ordered.size - victims.size)
            val counts = queueCounts(
                pendingNames,
                paths.processingDir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList(),
                firstSeen, admittedAt, now(),
            )
            runLog.warn(
                LogSubsystem.TRANSPORT, LogEvent.MAILBOX_BACKPRESSURE,
                "pending" to pendingNames.size.toString(), "evicted" to victims.size.toString(),
                "queueDepth" to counts.depth.toString(),
                "oldestAgeMs" to (counts.oldestAgeMs ?: 0L).toString(),
            )
            val reason = "retryAfterMs=$RETRY_AFTER_MS queueDepth=${counts.depth} oldestAgeMs=${counts.oldestAgeMs ?: 0L}"
            victims.forEach { name -> rejectAtSource(File(paths.inboxDir, name), RelayError.QUEUE_FULL, reason) }
        }
        var settling = false
        var handled = 0
        for (name in queue) {
            // stop() 之后本循环可能还在跑最后一条：被取消的循环不得再认领新请求。
            // 否则两个循环（旧的没退干净、新的已起来）会交替搬走同一批 inbox 文件，
            // 彼此删掉对方在途的那一个，请求静默消失且没有任何错误码。
            if (!currentCoroutineContext().isActive) return
            if (handled >= MAX_PER_ROUND) {
                // 有界处理：剩下的一律等下一轮，避免一次 drain 长到让维护巡检与回收全部滞后。
                lastWorkAtMs = now()
                break
            }
            handled++
            val source = File(paths.inboxDir, name)
            // 静默期：本类只认「分片写入 + 改名」，但没有强制手段拦住直接写的助手。
            // 经改名到达的件（观察者携名字记下的）已写完，免去等待；其余按「宿主首次见到
            // 它的单调时刻」等满窗口 —— 判据不用文件时间戳：mtime 由同 uid 的助手随意可改，
            // 也是墙钟，用它要么让回调系统时间把通道冻住，要么让预置的未来 mtime 绕过静默期。
            val seenAt = firstSeen.getOrPut(name) { now() }
            if (needsSettle(seenAt, now(), name in arrivalMarks)) {
                settling = true
                continue
            }
            firstSeen.remove(name)
            arrivalMarks.remove(name)
            val working = File(paths.processingDir, name)
            // 同名顶位之前先问状态存储：同 id 仍非终态说明有一条在飞。此刻删旧件顶位，
            // 在飞那条会丢掉文件归宿（文件与状态记录从此对不上），新件认领后等于同一
            // id 二次完整执行。「在飞」的判据是 processing 区确有同 id 文件、或记录已
            // 越过受理——控制通道为这份文件自己建的 QUEUED 受理记录不挡（见纯函数注释）。
            // v1（无状态存储）与已终态的记录不挡，走既有路径。
            val store = stateStore
            if (store != null) {
                val incumbent = store.get(requestId(source))
                if (claimBlockedByInFlight(incumbent, now(), working.exists())) {
                    runLog.warn(
                        LogSubsystem.TRANSPORT, LogEvent.REQUEST_DROPPED,
                        "file" to source.name, "cause" to "duplicate id still in flight",
                    )
                    // 不走 settleStateful：这份状态记录属于在飞的那条请求，终态必须留给它。
                    // 回包走 v1 出站口；在飞那条收尾时会照旧同名覆盖，提交方最终拿到真结局。
                    // 消费掉一份文件就是一次推进：健康读数不得因这条分支绕过而谎报停摆。
                    lastWorkAtMs = now()
                    writeResponse(
                        failure(
                            requestId(source),
                            RelayError.INTERNAL,
                            now(),
                            "a request with this id is already in flight; wait for it to settle " +
                                "and resubmit to get the recorded outcome, or use a fresh id",
                        ),
                    )
                    if (!source.delete()) {
                        runLog.warn(
                            LogSubsystem.TRANSPORT, LogEvent.REQUEST_DROPPED,
                            "file" to source.name, "cause" to "conflict delete",
                        )
                    }
                    continue
                }
            }
            if (working.exists() && !working.delete()) {
                runLog.warn(LogSubsystem.TRANSPORT, LogEvent.REQUEST_DROPPED, "file" to working.name, "cause" to "stale target")
                continue
            }
            if (!source.renameTo(working)) continue
            reaper.noteProcessing(working.name)
            admittedAt[working.name] = now()
            lastWorkAtMs = now()
            val acceptedAt = lastWorkAtMs
            // 受理点：请求文件此刻搬进 processing。与 [logSettled] 配对，让一条请求在
            // 运行日志里有「何时受理、何时了结、耗时多少」可对账的三个点。
            // id 用裸请求号（去 .json 后缀）：响应与失败回包里的 id 都是这一形状。
            runLog.info(LogSubsystem.TRANSPORT, LogEvent.CALL_ACCEPTED, "id" to requestId(working))
            var parked = false
            try {
                if (!awaitCallSlot(working, acceptedAt)) continue
                try {
                    // 挂起分支在 process 内部先行归还执行槽（等待发生在通道之外），
                    // 这里只给真正走完或失败的调用配对 endCall。
                    parked = process(working, acceptedAt)
                } finally {
                    if (!parked) reaper.endCall()
                }
                // 挂起的请求此刻没有终点：了结点由续行路径（[resumeOne]）补上。
                if (!parked) logSettled(acceptedAt)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                runLog.error(LogSubsystem.TRANSPORT, LogEvent.CALL_FAILED, "id" to working.name, "type" to t.javaClass.simpleName)
                working.delete()
            } finally {
                // 挂起的请求要活着等续行：processing 文件、清扫钉与认领记账全部保留，
                // 由续行的收尾统一清；文件被外部拿走时由本轮末尾的对账兜住。
                if (!parked) {
                    reaper.clearProcessing(working.name)
                    admittedAt.remove(working.name)
                }
            }
        }
        // 记账只跟此刻真正还在的文件对齐：被认领、被驱逐、被沙盒侧删掉的都清出去，
        // 免得垃圾名与消失的文件把三张内存表撑满。
        val remainingInbox = paths.inboxDir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()
        val remainingProcessing = paths.processingDir.listFiles()?.filter { it.isFile }?.map { it.name }?.toSet() ?: emptySet()
        firstSeen.keys.retainAll(remainingInbox)
        arrivalMarks.retainAll(remainingInbox)
        admittedAt.keys.retainAll(remainingProcessing)
        // 有文件还在静默窗口里：把轮询留在热态，否则冷态首请求要白等一个 IDLE_POLL_MS。
        if (settling) lastWorkAtMs = now()
    }

    /** 还没搬进 processing 就决定拒绝：先原子搬走，再回一条失败，绝不留文件在原地。 */
    /**
     * 把形状不合规的请求文件回成一条可诊断的失败，而不是留在原地永远无人应答。
     *
     * 入口自己生成的 id 一定合规，所以这一类只会来自手写投递或脚本灌信箱：含非 ASCII 字符的、
     * 名字里带点号的、超过 64 个字符的。原先它们被白名单整个跳过 —— 不报错、不作答、不清理，
     * 投件方只能看见自己的超时，学不到"要改的是文件名"这一件真正要改的事。
     * 响应落回原文件名，让按自己文件名轮询的那一侧拿得到；正文里的 id 换成合规形状。
     */
    private fun answerMalformedNames(rejected: List<File>) {
        rejected.take(MAX_MALFORMED_PER_ROUND).forEach { source ->
            val stem = source.name.removeSuffix(EnvelopeCodec.JSON_SUFFIX)
            val id = stem.map { if (it in LEGAL_ID_CHARS) it else '_' }.joinToString("").take(64)
                .ifEmpty { "unnamed" }
            runLog.warn(
                LogSubsystem.TRANSPORT, LogEvent.REQUEST_DROPPED,
                "id" to id,
                "detail" to "malformed request file name ${source.name.take(80)}",
            )
            // 落回原文件名：投件的那一侧按自己写的名字轮询，换成消毒过的名字等于又回到
            // "它那边永远等不到"。JSON 里的 id 是消毒过的，因为那才是通道内可用的请求号。
            // 落盘走原子改名：目标名由投件方任意决定，直写会跟随预先放在同名的末级符号
            // 链接、替沙盒改写绑定子树外的宿主私有文件；改名只替换链接本身，不跟随。
            val reply = failure(id, RelayError.TRANSPORT_MALFORMED, now())
            val target = File(paths.outboxDir, source.name)
            if (!reaper.writeTextAtomic(target, EnvelopeCodec.encode(reply))) {
                writeResponse(reply)
            }
            source.delete()
        }
    }

    private fun rejectAtSource(source: File, error: RelayError, reason: String) {
        val working = File(paths.processingDir, source.name)
        if (working.exists() && !working.delete()) return
        if (!source.renameTo(working)) return
        val id = requestId(working)
        val reply = failure(id, error, now(), reason)
        settleStateful(reply)
        writeResponse(reply)
        working.delete()
        firstSeen.remove(source.name)
        arrivalMarks.remove(source.name)
    }

    /** 请求文件名已由 [EnvelopeCodec.isRequestFileName] 白名单校验，去后缀即合法 id。 */
    private fun requestId(working: File): String = working.name.removeSuffix(EnvelopeCodec.JSON_SUFFIX)

    /**
     * 这条请求的受理时刻（单调钟）。[admittedAt] 以 processing 区的**文件名**（带 `.json`）为键，
     * 而这里给的是裸 id，因此两个形状都问一遍：挂起-续行那条路径传进来的正是裸 id。
     *
     * 查不到即从未受理（[rejectAtSource] 的驱逐回包就是这种），此时耗时记 0：
     * 「没被受理过」不是「受理了 0 毫秒」，而这条读数只有在这条请求确实走过认领时才有意义。
     */
    private fun admittedSince(id: String): Long? = admittedAt[id + EnvelopeCodec.JSON_SUFFIX] ?: admittedAt[id]

    /** 从受理起算的毫秒数；未受理过记 0（见 [admittedSince]）。 */
    private fun elapsedSinceAdmitted(id: String): Long {
        val admitted = admittedSince(id) ?: return 0L
        return (now() - admitted).coerceAtLeast(0L)
    }

    /**
     * 了结点：一条请求在本消费循环内的终点，ms 从受理的单调时刻起算，与 [LogEvent.CALL_ACCEPTED]
     * 配成可对账的一对。终态的具体结果由周边各自的事件写明；异常路径由 CALL_FAILED 单独标出，
     * 不落这条，避免同一请求出现两个互相矛盾的终点。
     */
    private fun logSettled(acceptedAt: Long) {
        runLog.info(LogSubsystem.TRANSPORT, LogEvent.CALL_SETTLED, "ms" to (now() - acceptedAt).toString())
    }

    /**
     * 排队等一个在途名额。清空存储与清扫都会短暂独占，此时让路而不是硬闯。
     * 让路到底时把请求回成可重试的失败——留在原地不动会让助手干等自己的超时，
     * 什么也学不到。错误码是「满队」而不是「限流」：让路的真因是存放与在途名额，
     * 不是调用频率，reason 里给出重试所需的三个数。
     */
    private suspend fun awaitCallSlot(working: File, acceptedAt: Long): Boolean {
        repeat(CALL_SLOT_ATTEMPTS) {
            if (reaper.beginCall()) return true
            delay(CALL_SLOT_RETRY_MS)
        }
        runLog.warn(LogSubsystem.TRANSPORT, LogEvent.CALL_DEFERRED, "id" to working.name, "cause" to "sweep busy")
        val reply = failure(requestId(working), RelayError.QUEUE_FULL, acceptedAt, queueFullReason())
        settleStateful(reply)
        writeResponse(reply)
        working.delete()
        // 让路到底并以失败回包收场也是一次了结：不补这条，受理点就悬着没有配对。
        logSettled(acceptedAt)
        return false
    }

    /**
     * 消费一条已认领的请求。
     *
     * @return true = 请求已挂起等用户答复：processing 文件保留、不写响应，执行槽已归还，
     * 结局由续行体在答复（或审批到点）之后给出；false = 已按响应收场。
     */
    private suspend fun process(working: File, acceptedAt: Long): Boolean {
        val id = requestId(working)
        // v2 钩子：带状态记录的请求在认领后先记 ADMITTED。v1 请求没有记录，一问即过。
        val store = stateStore
        if (store != null && store.get(id) != null) {
            store.markAdmitted(id)
        }
        if (working.length() > EnvelopeCodec.MAX_REQUEST_BYTES) {
            // 必须回一条失败：只删文件的话助手分不出「请求超限」与「宿主没在运行」，
            // 只会按自身超时拿到一个误导性的 E_TRANSPORT_TIMEOUT。
            val reply = failure(id, RelayError.REQUEST_TOO_LARGE, acceptedAt)
            settleStateful(reply)
            writeResponse(reply)
            // 字节数在删除前取：删完再读 length() 只会得到 0，日志里那条「超限多少」就成了空话。
            val bytes = working.length()
            working.delete()
            runLog.error(LogSubsystem.TRANSPORT, LogEvent.REQUEST_TOO_LARGE, "bytes" to bytes.toString())
            return false
        }
        val text = runCatching { working.readText() }.getOrElse {
            working.delete()
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.REQUEST_DROPPED, "file" to working.name, "cause" to "unreadable")
            // 带状态记录的请求不能悬在已认领：如实收一条失败终态，status 才答得出结局。
            settleStateful(failure(id, RelayError.TRANSPORT_MALFORMED, acceptedAt, "unreadable"))
            return false
        }
        val decoded = EnvelopeCodec.decodeRequest(text, working.name)
        if (decoded is DecodeResult.Rejected) {
            val reply = failure(decoded.id, decoded.error, acceptedAt)
            settleStateful(reply)
            working.delete()
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.REQUEST_DROPPED, "file" to working.name, "cause" to decoded.cause)
            // 形状类拒绝同样要回话：回 exit 1（用法错）而不是让客户端自己撞成 exit 3（超时）。
            writeResponse(reply)
            return false
        }
        val request = (decoded as DecodeResult.Valid).request
        // 客户端的 ttlMs 从它写文件那刻起算，宿主这个预算从**取走文件**起算，
        // 且还要留出回写时间。不扣这两项的话，顶格请求必然是宿主先放弃、
        // 客户端还在等，响应成了没人认领的孤儿并把产物一起钉住。
        // 回写余量按比例给：`MIN_TTL_MS` 是 1 秒，而固定余量 1.5 秒，
        // 直接相减会让「合法但很短的 ttl」必然被判成超时 —— 报 E_TRANSPORT_TIMEOUT，
        // 真因却是"你给的 ttl 不够我回话"，助手据此重试只会再失败一次。
        val reserve = minOf(WRITE_RESERVE_MS, request.timeoutMs / 4)
        val budget = request.timeoutMs - (now() - acceptedAt) - reserve
        val result = if (budget <= 0L) {
            HandlerResult.Done(failure(id, RelayError.TRANSPORT_TIMEOUT, acceptedAt))
        } else {
            try {
                withTimeoutOrNull(budget) { handler.handle(request, budget) }
                    ?: HandlerResult.Done(failure(id, RelayError.TRANSPORT_TIMEOUT, acceptedAt))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                HandlerResult.Done(failure(id, RelayError.INTERNAL, acceptedAt, t.javaClass.simpleName))
            }
        }
        return when (result) {
            is HandlerResult.Done -> {
                val response = result.response
                // 先提交终态、再写回包（v2 钩子）：终态记录必须先于回包站得住，
                // 崩溃后才不会出现「回包在、结论丢」。v1 请求在这里不做任何事。
                settleStateful(response)
                // 处理已完成，回不去也删：重投会二次执行副作用（再点一次、再写一条通讯录）。
                // 失败只作为宿主侧故障进日志，退出码 6 由客户端自己的超时给出。
                if (!writeResponse(response)) {
                    runLog.error(LogSubsystem.TRANSPORT, LogEvent.RESPONSE_WRITE_FAILED, "id" to request.id)
                }
                working.delete()
                false
            }

            is HandlerResult.Parked -> {
                // 挂起不写响应、不删请求文件：请求留在 processing 里等续行，谁也不许
                // 把它的位置让出去。v2：转入 WAITING_RELAY_USER，控制通道的 status 与
                // cancel 据此答复；v1 没有状态记录，跳过。
                val store = stateStore
                if (store != null && store.get(id) != null) {
                    store.markWaiting(id, relayUser = true)
                }
                // 执行槽先还：等人的这段时间不该占在途名额，后面的无关请求照常消费。
                reaper.endCall()
                runLog.warn(
                    LogSubsystem.TRANSPORT,
                    LogEvent.CALL_DEFERRED,
                    "id" to id,
                    "cause" to "awaiting_approval",
                )
                true
            }
        }
    }

    /**
     * 挂起请求的续行：把答复之后的收尾工作排上与主循环同一条串行上下文。
     *
     * 触发方有两类——答复到达（等待环/呈现回调一侧）与审批到点的兜底定时——都在
     * [ParkedRegistry] 的摘取处去重，先到者赢；登记已在处理方挂起时完成，这里摘不到
     * 就是别人已经续行过。
     */
    override fun resumeParked(requestId: String) {
        val park = parks.take(requestId) ?: return
        val scope = loopScope ?: return
        scope.launch(workContext) { resumeOne(park) }
    }

    override fun hasParkCapacity(): Boolean = parks.size() < PARKED_CAPACITY

    override fun register(park: ParkedRequest): Boolean = parks.register(park)

    /**
     * 一条挂起请求的续行执行与收尾，与 [drain] 的单请求路径同一套配对纪律：
     * 先重新拿在途名额（清扫可能正占着，让路到底就以满队回包了结），再执行续行体，
     * 完结路径与内联 settle 逐项对齐——先提交终态、再写回包、删请求文件、补了结点。
     */
    private suspend fun resumeOne(park: ParkedRequest) {
        // 通道已停：续行随托管一并作废。登记已被摘走，请求文件留给孤儿清扫，
        // 状态存储在下一次启动的恢复扫描里按「可证未派发」收尾——绝不在停机之后执行。
        if (loopScope == null) return
        val working = File(paths.processingDir, park.requestId + EnvelopeCodec.JSON_SUFFIX)
        // 受理时刻按 [admittedSince] 取：记账以 processing 区的文件名为键（带 `.json`），
        // 裸 id 直查恒为 null——那会让续行路径上的超时回包与了结耗时全按"此刻"起算。
        val acceptedAt = admittedSince(park.requestId)
        try {
            if (!awaitResumeSlot(park, acceptedAt)) return
            try {
                finishParked(working, computeResumedResponse(park, acceptedAt), acceptedAt)
            } finally {
                reaper.endCall()
            }
        } catch (cancellation: CancellationException) {
            // 通道正在停止：请求文件留在原地，交给下一世的孤儿清扫与状态恢复分流。
            throw cancellation
        } catch (t: Throwable) {
            runLog.error(LogSubsystem.TRANSPORT, LogEvent.CALL_FAILED, "id" to working.name, "type" to t.javaClass.simpleName)
            runCatching {
                finishParked(
                    working,
                    failure(park.requestId, RelayError.INTERNAL, acceptedAt ?: now(), t.javaClass.simpleName),
                    acceptedAt,
                )
            }
        } finally {
            reaper.clearProcessing(working.name)
            // 与 [admittedSince] 同一口径清账：两种键型都摘，裸 id 单摘会漏掉带 `.json` 的那份。
            admittedAt.remove(park.requestId)
            admittedAt.remove(park.requestId + EnvelopeCodec.JSON_SUFFIX)
        }
    }

    /** 续行的在途名额。让路到底时把挂起请求回成可重试的满队失败——文件必须收场，不能悬着。 */
    private suspend fun awaitResumeSlot(park: ParkedRequest, acceptedAt: Long?): Boolean {
        repeat(CALL_SLOT_ATTEMPTS) {
            if (reaper.beginCall()) return true
            delay(CALL_SLOT_RETRY_MS)
        }
        runLog.warn(LogSubsystem.TRANSPORT, LogEvent.CALL_DEFERRED, "id" to park.requestId, "cause" to "resume slot busy")
        val reply = failure(park.requestId, RelayError.QUEUE_FULL, acceptedAt ?: now(), queueFullReason())
        finishParked(File(paths.processingDir, park.requestId + EnvelopeCodec.JSON_SUFFIX), reply, acceptedAt)
        return false
    }

    /**
     * 续行的响应产出：客户端有效期扣到执行为止，与内联路径的 budget 同源；
     * 超限按通道超时收场（动作是否已派发由续行体与状态存储的边界标记说，这里不猜）。
     */
    private suspend fun computeResumedResponse(park: ParkedRequest, acceptedAt: Long?): RelayResponse {
        val remaining = park.executionDeadlineAtMs - now()
        if (remaining <= 0L) {
            return failure(park.requestId, RelayError.TRANSPORT_TIMEOUT, acceptedAt ?: now())
        }
        return withTimeoutOrNull(remaining) { park.resume() }
            ?: failure(park.requestId, RelayError.TRANSPORT_TIMEOUT, acceptedAt ?: now())
    }

    /** 挂起请求的完结路径：先提交终态、再写回包、删请求文件、补了结点，与内联路径逐项对齐。 */
    private fun finishParked(working: File, response: RelayResponse, acceptedAt: Long?) {
        settleStateful(response)
        if (!writeResponse(response)) {
            runLog.error(LogSubsystem.TRANSPORT, LogEvent.RESPONSE_WRITE_FAILED, "id" to response.id)
        }
        if (working.exists() && !working.delete()) {
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.REQUEST_DROPPED, "file" to working.name, "cause" to "settle delete")
        }
        logSettled(acceptedAt ?: now())
    }

    /**
     * v2 钩子：请求带状态记录时，先把终态提交进状态存储，再把终态回包补写进控制回包目录
     * （同名原子覆盖顶掉入队时的 QUEUED 回执，提交方按 control-outbox/<id>.json 轮询拿终态）。
     * 两步都尽力而为：状态记录缺失或已先有终态时按现状收场，不阻塞 v1 回包路径。
     *
     * 控制帧只按落盘结果说话：`recordTerminal` 交回了终态记录，帧转述记录的阶段与结果
     * （与 status 查询读的是同一份记录，两口径天然一致）；交回 null（记录缺失或终态没
     * 落住）时帧改报 UNKNOWN——绝不让控制帧宣称一个状态查询答不出的结论。
     *
     * `E_GATE_CANCELLED` 是唯一的例外归类：它只在续行入口、任何外部动作提交之前产出
     * （挂起等待中被控制通道取消），此时可证未派发，按生命周期的本义记 [RequestPhase.CANCELLED]
     * 而不是泛化的 FAILED。
     */
    private fun settleStateful(response: RelayResponse) {
        val store = stateStore ?: return
        if (store.get(response.id) == null) return
        val encoded = EnvelopeCodec.encode(response)
        val phase = when {
            response.ok -> RequestPhase.SUCCEEDED
            response.error == RelayError.GATE_CANCELLED -> RequestPhase.CANCELLED
            else -> RequestPhase.FAILED
        }
        val recorded = store.recordTerminal(response.id, phase, resultJson = encoded)
        // 了结读数：终态记进状态存储的那一刻就把结论与耗时写下来，ms 从受理起算。
        // 「为什么这条一直没执行」的答案通常就落在这条与 REQUEST_ADMITTED 之间的差里。
        // 终态没落住时按 UNKNOWN 记，与下方控制帧同一口径 —— 绝不宣称一个 status 查不出的结论。
        runLog.info(
            LogSubsystem.TRANSPORT, LogEvent.REQUEST_COMPLETED,
            "id" to response.id,
            "state" to (recorded?.phase?.name ?: RequestPhase.UNKNOWN.name),
            "ms" to elapsedSinceAdmitted(response.id).toString(),
        )
        val frame = EnvelopeCodec.encodeControl(
            id = response.id,
            op = ControlOps.SUBMIT,
            // 终态没能落住时不能宣称成功：提交方拿不到可重取的结果，一律按未知收场。
            ok = response.ok && recorded != null,
            payload = terminalFramePayload(response.id, recorded),
        )
        if (!reaper.writeTextAtomic(File(paths.controlOutboxDir, response.id + EnvelopeCodec.JSON_SUFFIX), frame)) {
            runLog.error(LogSubsystem.TRANSPORT, LogEvent.RESPONSE_WRITE_FAILED, "id" to response.id)
        }
    }

    /** 满队回包的 reason：给足重试所需的三个数，让助手知道等多久、队伍多长。 */
    private fun queueFullReason(): String {
        val inbox = paths.inboxDir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        val processing = paths.processingDir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        val counts = queueCounts(inbox, processing, firstSeen, admittedAt, now())
        return "retryAfterMs=$RETRY_AFTER_MS queueDepth=${counts.depth} oldestAgeMs=${counts.oldestAgeMs ?: 0L}"
    }

    /**
     * 控制通道探活用的只读读数。只数文件、只读内存标记，不含任何会推进工作的动作：
     * 探活因此可以随时被服务，哪怕工作循环正被一次长执行占住。
     *
     * 存活语义（真机复验教训）：`channelLive` 曾经的判据是「[HOT_WINDOW_MS] 内有推进」，
     * 空闲与停滞同形——执行器完全健康、只是几分钟没有请求时会报 false，把验收门变成
     * 假红灯；而 panel 显示又要求它翻绿。真正的故障形状只有一种：**收件箱有积压而循环
     * 长时间不推进**（实测事故：77 条请求在收件箱里躺 40 分钟）。所以：
     * - `stalled` = 收件箱非空 且 [HOT_WINDOW_MS] 内无推进；
     * - `channelLive` = 非 stalled（空闲且无积压为 true；有积压无人取为 false）。
     * 长执行（processing 区非空、收件箱为空）不是停滞：它的文件已越过认领。
     */
    internal fun workLoopHealth(): WorkLoopHealth {
        val stamp = now()
        val inbox = paths.inboxDir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        val processing = paths.processingDir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        val counts = queueCounts(inbox, processing, firstSeen, admittedAt, stamp)
        val inboxDepth = inbox.size
        val stalled = inboxDepth > 0 && stamp - lastWorkAtMs >= HOT_WINDOW_MS
        return WorkLoopHealth(
            channelLive = !stalled,
            queueDepth = counts.depth,
            oldestQueuedAgeMs = counts.oldestAgeMs,
            inboxDepth = inboxDepth,
            stalled = stalled,
        )
    }

    private fun writeResponse(response: RelayResponse): Boolean {
        val target = File(paths.outboxDir, "${response.id}${EnvelopeCodec.JSON_SUFFIX}")
        if (target.exists()) {
            // 沙盒与宿主同 uid 且通道无来源认证，guest 可以预先在 outbox 放一个同名
            // 假响应。宿主的答复才是权威结果，直接覆盖而不是丢弃 —— 丢弃等于让
            // "先占位的人"决定助手看到什么。落盘走原子改名，符号链接只会被替换不被跟随。
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.RESPONSE_OVERWRITTEN, "id" to response.id)
        }
        val encoded = EnvelopeCodec.encode(response)
        // 响应侧必须有上限：请求有 256 KiB 封顶而响应没有的话，一次未收敛的读取能让宿主
        // 构造几十 MB 的 JSON 写进绑定目录 —— 宿主先 OOM，客户端则在 JSON.parse 处失败。
        // 超限就整体换成一条失败回给助手（保留原耗时），让它改小 limit 重发，
        // 而不是发半截或什么都不发。已落盘的产物由钉的到期时间与清扫兜住。
        if (encoded.length > EnvelopeCodec.MAX_RESPONSE_CHARS) {
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.RESPONSE_TOO_LARGE, "id" to response.id)
            val tooLarge = response.copy(
                ok = false,
                data = JSONObject(),
                artifacts = emptyList(),
                reason = "response exceeds ${EnvelopeCodec.MAX_RESPONSE_CHARS} chars",
                error = RelayError.REQUEST_TOO_LARGE,
            )
            val shrunk = EnvelopeCodec.encode(tooLarge)
            if (!reaper.writeTextAtomic(target, shrunk)) return false
            logResponsePublished(response.id, shrunk)
            return true
        }
        if (!reaper.writeTextAtomic(target, encoded)) return false
        logResponsePublished(response.id, encoded)
        return true
    }

    /**
     * 回包落地读数：正文与它有多大。它是「答复到底送出去了没有」的最后一条 ——
     * 写失败有 [LogEvent.RESPONSE_WRITE_FAILED]，两者合起来才答得出「助手为什么干等」。
     * bytes 取实际写入那一份正文的 UTF-8 字节数（超限分支写的是缩小后的那一份，不是原件）。
     */
    private fun logResponsePublished(id: String, writtenBody: String) {
        runLog.info(
            LogSubsystem.TRANSPORT, LogEvent.RESPONSE_PUBLISHED,
            "id" to id,
            "bytes" to writtenBody.toByteArray(StandardCharsets.UTF_8).size.toString(),
        )
    }

    private fun failure(id: String, error: RelayError, startedAt: Long, reason: String? = null) = RelayResponse(
        id = id,
        ok = false,
        data = JSONObject(),
        artifacts = emptyList(),
        surface = null,
        degradedFrom = null,
        reason = reason,
        error = error,
        elapsedMs = now() - startedAt,
    )

    /**
     * 控制通道探活的只读读数：三条循环健康线各说各的 —— 工作循环最近有没有进展、
     * 待办积了多少、最老的待办等了多久。全部来自内存标记与目录计数。
     */
    data class WorkLoopHealth(
        /** 非 stalled。空闲且无积压为 true——空闲不是故障，只有积压无人取才是。 */
        val channelLive: Boolean,
        val queueDepth: Int,
        val oldestQueuedAgeMs: Long?,
        /**
         * 仅收件箱的待办数。`queueDepth` 含 processing 区——一条合法的长执行（如 30 秒
         * 录屏）会让它长时间非零，把「忙」与「停滞」区分开必须看收件箱：真停滞的形状是
         * 「收件箱有积压而循环无推进」，单纯无推进只是空闲。
         */
        val inboxDepth: Int,
        /** 收件箱有积压且 [HOT_WINDOW_MS] 内无推进：执行器停滞的唯一判据。 */
        val stalled: Boolean,
    )

    /** 探活计数的读数：深度按文件数，最老待办按内存里的单调记账。 */
    data class QueueCounts(val depth: Int, val oldestAgeMs: Long?)

    companion object {

        /**
         * 两条阶段事件在调用点填的字段名，与模板占位符必须逐个相等（对不上会被 RunLog
         * 落成 `unresolved=…`）。见 [RequestStateStore.ADMITTED_FIELDS] 的同条纪律。
         */
        internal val COMPLETED_FIELDS = setOf("id", "state", "ms")
        internal val PUBLISHED_FIELDS = setOf("id", "bytes")

        /**
         * 待办排序：按宿主首次见到的单调时刻（真实先来后到），没有记账的排最后再按名字。
         * 文件名顺序与到达顺序无关，按名字排队会让「来得早、名字大」的请求被后来的插队。
         */
        internal fun orderPending(entries: List<Pair<String, Long?>>): List<String> =
            entries.sortedWith(
                compareBy<Pair<String, Long?>> { it.second == null }
                    .thenBy { it.second ?: Long.MAX_VALUE }
                    .thenBy { it.first },
            ).map { it.first }

        /** 驱逐选择：容量超限时最晚到的让位 —— 先来的先受理，后来的拿可重试失败。 */
        internal fun evictionVictims(orderedNames: List<String>, capacity: Int): List<String> =
            if (orderedNames.size <= capacity) emptyList() else orderedNames.drop(capacity)

        /**
         * 静默期判定：经改名到达的件分片已写完，免去等待；其余按首次见到的时刻等满
         * [SETTLE_MS]。判据不用文件时间戳 —— mtime 由同 uid 的写入方随意可改。
         */
        internal fun needsSettle(seenAtMs: Long, nowMs: Long, arrivedByRename: Boolean): Boolean =
            !arrivedByRename && nowMs - seenAtMs < SETTLE_MS

        /**
         * 终态帧决策（纯函数）：状态存储交回的落盘结果决定控制终态帧说什么。
         * 交回了终态记录，帧如实转述记录的阶段与结果——status 查询读的是同一份记录，
         * 两口径天然一致；交回 null（记录缺失或终态没落住）时帧改报 UNKNOWN，result
         * 省略、附 cause——控制帧绝不能宣称一个状态查询答不出的结论。cause 按
         * 「错误码/原因」的既有形状给，客户端据此对齐退出码。
         */
        internal fun terminalFramePayload(id: String, recorded: RequestState?): JSONObject = JSONObject().apply {
            put("state", (recorded?.phase ?: RequestPhase.UNKNOWN).name)
            put("targetId", id)
            if (recorded == null) {
                put("cause", "E_INTERNAL/terminal state not persisted; outcome unknown")
            } else {
                put("result", recorded.resultJson?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            }
        }

        /**
         * 认领冲突判定（纯函数）：同名 processing 顶位删除之前的闸。「真的有一条在飞」
         * 由两个互相独立的信号给出，任一命中即挡：
         * 1. 同 id 文件已在 processing 区（被认领、可能正挂起等待审批）——文件是归宿的
         *    权威信号，此刻顶位删除会让在飞那条丢掉文件、让同一 id 二次完整执行；
         * 2. 状态记录已越过受理（ADMITTED、WAITING、EXECUTING、已记取消意愿）——同 id 的
         *    另一份投递此刻到达，记录属于先到的那条。
         *
         * **QUEUED 不挡**：控制通道受理 submit 时先建 QUEUED 记录、随后才把转写件投进
         * 收件箱——工作循环认领这份文件时读到的正是它自己的受理记录。按记录非终态一刀切
         * 会把 v2 的每一条正常入队全部拦成「已在执行中」（实测事故：执行器看似活着、
         * 77 条请求全部饿死在 QUEUED），故受理前记录必须放行；若确有同 id 的第二份投递，
         * 先到那份认领后记录已推进到 ADMITTED，第二份在此处照挡。
         *
         * 记录缺失（v1 路径）、已终态（含 EXPIRED）、超过孤儿请求寿命仍非终态的遗留记录
         * 都不挡：前两者走既有认领路径，最后一种的请求文件早被按龄回收，活请求的最长
         * 寿命远短于这个寿命，继续挡只会让同 id 的重投被一条死记录永久拦下——放行后由
         * 派发守卫与巡检收尾各自兜底。
         */
        internal fun claimBlockedByInFlight(
            existing: RequestState?,
            nowMs: Long,
            processingFileExists: Boolean,
        ): Boolean = when {
            processingFileExists -> true
            existing == null || existing.isTerminal -> false
            existing.phase == RequestPhase.QUEUED -> false
            RequestStateStore.nonTerminalRecordExpired(existing, nowMs) -> false
            else -> true
        }

        /**
         * 在途分片判定（纯函数）：写入方落分片用的几类名字 —— 入口 CLI 的
         * `.<id>.json.part`、控制通道的 `<id>.part`，以及原子写落在收件箱里等改名的
         * `.tmp` 暂存件 —— 都不在请求名白名单内，但它们是写入中的正文而不是畸形名，
         * 由静默期与观察者机制兜住。畸形回包绝不能碰它们：回包加删除的窗口正好
         * 撞上写入方的改名一步，客户端会拿到一个改不出来的名字。
         */
        internal fun isIncomingPartName(name: String): Boolean =
            name.startsWith(".") || name.endsWith(PART_SUFFIX) || name.endsWith(TEMP_SUFFIX)

        /**
         * 探活计数：深度是收件箱与 processing 的文件数之和；最老待办取「宿主最早知道
         * 它」的单调时刻 —— 收件箱里看首次见到的记账，processing 里看认领的记账，
         * 两处都有时取更早的（一条请求的等待从它到达那刻起算，不因被认领而重新计时）。
         * 没有记账的件（重启前的残留）只计深度 —— 内存里没有可信的时刻，
         * 不拿可被改写的 mtime 充数。
         */
        internal fun queueCounts(
            inbox: Collection<String>,
            processing: Collection<String>,
            seenAt: Map<String, Long>,
            admittedAt: Map<String, Long>,
            nowMs: Long,
        ): QueueCounts {
            fun earliestStamp(name: String): Long? {
                val seen = seenAt[name]
                val admitted = admittedAt[name]
                return when {
                    seen != null && admitted != null -> minOf(seen, admitted)
                    seen != null -> seen
                    else -> admitted
                }
            }
            val oldest = (inbox.asSequence() + processing.asSequence())
                .mapNotNull { earliestStamp(it) }
                .minOrNull()
            return QueueCounts(inbox.size + processing.size, oldest?.let { (nowMs - it).coerceAtLeast(0L) })
        }

        /**
         * 只认改名事件。监听 CREATE 会在助手仍持有 fd 写入时把文件改名走，
         * rename 不改 inode，尾部字节继续写进 processing，宿主读到截断 JSON，
         * 于是这条请求会随机消失。分片写入 + 改名是唯一安全形态；
         * [SETTLE_MS] 静默期是给不守这条约定的助手留的兜底，不是替代。
         */
        const val MASK = FileObserver.MOVED_TO
        const val CAPACITY = 16
        const val HOT_POLL_MS = 120L
        const val IDLE_POLL_MS = 5_000L
        const val HOT_WINDOW_MS = 30_000L
        const val SWEEP_INTERVAL_MS = 30L * 60L * 1000L

        /**
         * 巡检节奏。系统门禁能在应用之外被改动，而沙盒侧读的是能力清单文件：这个间隔就是
         * 「用户刚给的授权最迟多久出现在能力清单上」的上限。比回收短，因为巡检只读系统状态，
         * 内容没变时一个字节都不写。
         */
        const val MAINTENANCE_INTERVAL_MS = 60_000L

        /** 大于两个热态轮询间隔，够一次小文件写完。 */
        const val SETTLE_MS = 300L
        const val CALL_SLOT_ATTEMPTS = 4
        const val CALL_SLOT_RETRY_MS = 80L

        /**
         * 可同时挂起等答复的请求上界。审批是给一个人看的：同时挂起七八件没人答的事，
         * 说明该有人去看一眼了，再积压只会把「等哪件」搅成一团。满员时处理方退回
         * 内联等待，不放宽容量。
         */
        const val PARKED_CAPACITY = 8

        /** 审批过点到触发兜底续行之间的宽限，盖过等待环一轮的内联窗口（8 秒）加余量。 */
        const val PARKED_BACKSTOP_GRACE_MS = 12_000L

        /** 单轮最多处理这么多条，其余等下一轮，保证一轮 drain 不会无限拉长。 */
        private const val MAX_PER_ROUND = 8

        /** 一轮处理多少个不合规文件名：见 [answerMalformedNames]。 */
        private const val MAX_MALFORMED_PER_ROUND = 8
        private val LEGAL_ID_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + charArrayOf('_', '-')
        /** 待处理文件数硬上限：超出即把最晚到的回成可重试失败，防信箱被灌满。 */
        private const val MAX_PENDING_FILES = 256

        /** 满队回包建议的重试间隔：队列消化得比这快，按它等不会白等。 */
        private const val RETRY_AFTER_MS = 1_000L

        /** 写入方分片名的后缀：与控制通道的投件分片同一形状。 */
        private const val PART_SUFFIX = ".part"

        /** 原子写暂存件的后缀：落在收件箱里只等一次改名，不是畸形名。 */
        private const val TEMP_SUFFIX = ".tmp"

        /** 宿主回写响应要留的余量，从客户端的 ttlMs 里扣。 */
        private const val WRITE_RESERVE_MS = 1_500L

        /** FileObserver 盯的是 inode，目录被重建后不会自动跟过去，按周期整条重建监听。 */
        private const val OBSERVER_REBUILD_MS = 30_000L
    }
}
