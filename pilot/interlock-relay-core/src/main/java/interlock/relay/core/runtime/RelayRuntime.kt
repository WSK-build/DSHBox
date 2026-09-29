package interlock.relay.core.runtime

import android.content.Context
import interlock.relay.core.interlock.InterlockChoice
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.spi.RelayCapabilities
import interlock.relay.core.spi.RelayExecutor
import interlock.relay.core.spi.RelayPathPolicy
import interlock.relay.core.spi.RelayPrefs
import interlock.relay.core.spi.RelayRedactor
import interlock.relay.core.spi.RelaySurfaces
import interlock.relay.core.spi.RelayText
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/**
 * 装配参数。[RelayRuntime.start] 的第二参。各项可空：缺省项取 core 内建的默认实现
 * （内建文案与语言、无呈现面、内建能力全表、内建默认路径策略、三个内建执行后端、
 * SharedPreferences 偏好、恒等脱敏）。宿主按需替换其中任意几项。
 */
data class RelayConfig(
    val text: RelayText? = null,
    val surfaces: RelaySurfaces? = null,
    val capabilities: RelayCapabilities? = null,
    val pathPolicy: RelayPathPolicy? = null,
    val executor: RelayExecutor? = null,
    val prefs: RelayPrefs? = null,
    val redactor: RelayRedactor? = null,
)

/**
 * 进程内唯一的装配根。
 *
 * 通道服务端对「同一请求目录只允许一个实例」有硬要求，两个装配根会互相抢文件，
 * 因此实例化只能有这一个入口。
 *
 * **作用域属于进程，不属于任何界面**：通道要在 agent 会话期间一直消费信箱，而界面
 * 开关是常态。用界面的 `lifecycleScope` 启动会让首次离开界面时协程被取消、
 * 通道静默停摆，而启动状态机又挡住了第二次启动。
 */
object RelayRuntime {

    @Volatile
    private var container: RelayContainer? = null

    /** 首次装配时生效的参数；core 内需要呈现面/文案的服务类从这里取生效值。 */
    @Volatile
    internal var activeConfig: RelayConfig = RelayConfig()
        private set

    /**
     * [start] 是否已采纳过一次参数。「先到先装配」以本标志表达，而不是以容器是否已建：
     * 容器可能被 [get] 先用默认配置惰性建出（进程重建时 [RelayConsentActivity] 先
     * get() 就是现实入口），那种「已建」不该吃掉随后到来的宿主配置。
     */
    @Volatile
    private var configApplied = false

    /** core 内取生效文案口：未装配时退回系统语言默认实现。 */
    internal fun effectiveText(context: Context): RelayText =
        activeConfig.text ?: RelayText.Default(context)

    /** core 内取生效呈现面：未装配时即 [RelaySurfaces.NoSurfaces]。 */
    internal fun effectiveSurfaces(): RelaySurfaces =
        activeConfig.surfaces ?: RelaySurfaces.NoSurfaces

    /**
     * 进程级作用域：只随进程结束，不随界面销毁。
     *
     * 末尾那个兜底处理器是必需的：这条作用域上跑着信箱消费、能力清单重铺与服务端对账，
     * 任何一处漏出来的异常在没有处理器时会走线程默认处理器并终止宿主进程，agent 侧只剩
     * 「投了件，再没有回应」。有了它，最坏是当次操作没做成，且运行日志里留得下一行。
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, cause ->
            val log = container?.runLog
            runCatching {
                log?.warn(
                    LogSubsystem.TRANSPORT,
                    LogEvent.CHANNEL_DEGRADED,
                    "where" to "scope",
                    "cause" to (cause.javaClass.simpleName + ": " + (cause.message ?: "no message")),
                )
            }
        },
    )

    fun get(context: Context): RelayContainer = container ?: synchronized(this) {
        container ?: RelayContainer(context.applicationContext, activeConfig).also { container = it }
    }

    /**
     * core 对外的唯一装配入口：带参数拉起装配根与通道。宿主在沙盒启动处调一次即可，
     * 重复调用无副作用；参数采纳「先到先得」（以 [configApplied] 表达）：首个 start
     * 带来的 config 即生效配置，后到的被忽略。
     *
     * 本方法只置位启动阶段就返回：真正的装配在进程级作用域的 IO 协程里逐步进行，
     * 调用方不必也无法在这里等它。何时可服务问 [phase]。
     */
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, config: RelayConfig = RelayConfig()) {
        val target: RelayContainer = synchronized(this) {
            if (!configApplied) {
                activeConfig = config
                configApplied = true
                // 容器若已被 get() 用默认配置惰性建出，它烙的是装配前的默认取值
                // （呈现面、文案与路径都在构造时定型）。唯一启动入口就是本方法、
                // 且采纳先于启动，所以此刻它必然还没启动过：直接换成本次参数
                // 装配的新容器，宿主配置因此一定生效。
                container = RelayContainer(context.applicationContext, activeConfig)
            }
            container ?: RelayContainer(context.applicationContext, activeConfig).also { container = it }
        }
        target.start(scope)
    }

    /**
     * 通道此刻在不在消费信箱。**不构造**装配根。
     *
     * 状态上报若顺手把整套装配建起来（含存储目录 mkdirs 与账本加载），一次读取就有了
     * 副作用。调用方恰恰是尚未持有容器的那些进程：看门狗拉起、或系统重投前台服务的
     * intent 时，答案本来就是「没在跑」，为此构造装配根没有意义。
     */
    fun isChannelRunning(): Boolean = container?.isChannelRunning() == true

    /**
     * 通知栏上那一下答复。与 [isChannelRunning] 同一条约束：**不构造**装配根。
     *
     * 进程被系统回收后通知可能仍然在（通知由系统持有，不随进程消失），用户这时点
     * 「允许」会把进程拉起来，而那一刻没有任何调用在等答复。没有容器就是没人在等：
     * 如实回 false 让接收方收起那条通知，而不是构造装配根去接一个无人等待的答复。
     */
    /**
     * 收掉 [requestId] 那一条待答通知。装配根没起来时什么都不用做：那时不可能挂着通知。
     */
    fun dismissApprovalNotification(requestId: Int) {
        if (isChannelRunning()) container?.dismissApprovalNotification(requestId)
    }

    fun resolveApproval(choice: InterlockChoice, requestId: Int): Boolean =
        container?.resolveApproval(choice, requestId) == true

    /** 宿主在沙盒停止处调用；卸载本模块时删掉这一句即可。 */
    fun stop() {
        synchronized(this) {
            container?.stop()
        }
    }

    /**
     * 运行阶段快照。**不构造**装配根：没有容器就是没在跑，如实返回 [RuntimePhase.STOPPED]。
     * 与 [isChannelRunning] 同一条约束 —— 状态读取不得带副作用。
     *
     * [context] 只为与同对象其他入口保持同形而收下，读取本身用不到它。
     */
    fun phase(context: Context): RuntimePhase = container?.runtimePhase() ?: RuntimePhase.STOPPED

    /**
     * 启动失败的原因；仅 DEGRADED 阶段非空。同样**不构造**装配根，理由同 [phase]。
     */
    fun degradedReason(context: Context): String? = container?.degradedReason
}
