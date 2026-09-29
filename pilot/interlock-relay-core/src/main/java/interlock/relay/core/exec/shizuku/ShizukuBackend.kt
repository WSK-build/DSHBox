package interlock.relay.core.exec.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import interlock.relay.core.protocol.LaunchMediators
import interlock.relay.core.protocol.BackendId
import interlock.relay.core.protocol.ArgErrors
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.exec.FrameEncoder
import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Shizuku 通路。所有命令由本类按能力逐个拼装，**不接受助手侧传入的原始命令行**：
 * 通用 shell 能力的权限并集覆盖其余全部能力，一次确认就等于替所有闸门背书，
 * 因此不提供这一条。
 *
 * 参数一律走白名单校验后再拼进单引号，避免把参数值解释成 shell 语法。
 */
class ShizukuBackend(
    private val state: ShizukuState,
    private val runLog: RunLog,
    context: android.content.Context,
    private val reaper: interlock.relay.core.storage.StorageReaper,
) : RelayBackend {

    private val appContext = context.applicationContext

    /**
     * 阻塞 binder 事务的唯一通道：进程生命周期内的一条单线程执行器。
     *
     * `IBinder.transact` 是阻塞调用且不可安全中断——中断的只是本端线程，递出去的
     * 那半个事务照样在远端跑。散放在共享 IO 池上，一条卡死的事务就会无声占走一个
     * 池线程；收进来之后卡死的至多占住这一条，后续调用在本地按预算放弃等待
     * （见 [transactBoundedBlocking]）。通道本身同一时刻只跑一条请求，这里再串行
     * 一次没有额外代价。线程设为守护：空闲的执行器线程不该拖住进程收尾。
     */
    private val remoteTransactions: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "relay-shizuku-remote").apply { isDaemon = true }
    }

    @Volatile
    private var binder: IBinder? = null

    /** 解绑是不是我们自己发起的：不是，就要把掉的线重连回来，见 [onServiceDisconnected]。 */
    @Volatile
    private var deliberatelyUnbound = true

    @Volatile
    private var lastRebindMs = 0L

    /**
     * 绑定关系变了要说给宿主一声。
     *
     * 助手侧能力清单里的 `usable` 问的是「此刻挑得到后端」，而 `connect()` 是异步的：
     * Shizuku 状态一就绪就重铺能力清单，那一次写的还是「没连上」，于是 `surface.virtual`
     * 与它带起来的那几条后台能力长期写着 usable=false：capabilities.json 只允许调用
     * usable=true 的条目，助手因此永远不会去建那块屏，后台模式在能力清单层面就没有入口。
     */
    @Volatile
    var onBoundChanged: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            runLog.info(LogSubsystem.GATE, LogEvent.SHIZUKU_SERVICE_CONNECTED)
            onBoundChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            // 掉线不重连也不留一行，事后看就像「三个 shell 能力忽然不可用」而无从查起。
            runLog.warn(LogSubsystem.GATE, LogEvent.SHIZUKU_SERVICE_DEAD)
            onBoundChanged?.invoke()
            rebindIfDropped()
        }

        override fun onBindingDied(name: ComponentName?) {
            binder = null
            runLog.warn(LogSubsystem.GATE, LogEvent.SHIZUKU_SERVICE_DEAD, "where" to "bindingDied")
            onBoundChanged?.invoke()
            rebindIfDropped()
        }
    }

    val id: BackendId get() = BackendId.SHIZUKU

    fun connect() {
        deliberatelyUnbound = false
        if (binder == null) state.bindUserService(connection)
    }

    fun disconnect() {
        deliberatelyUnbound = true
        // 收屏排在解绑之前：那块屏上可能正跑着别人的应用，通道停了却留着它，
        // 等于替用户留一个「还在被后台操作」的现场。这里没有调用预算可扣，
        // 给固定上限；等不到就放弃，远端那半个事务自己收尾，解绑照常进行。
        if (displayAlive()) {
            transactBoundedBlocking(CAP_LIFECYCLE, "display", DISPLAY_TXN_WAIT_MS) {
                rawTransactDisplay(ShellProtocol.CODE_DISPLAY_RELEASE)
            }
        }
        displayId = -1
        clearFrameCache()
        state.unbindUserService(connection)
        binder = null
    }

    /** 受限事务的两种结局：拿到回执，或在预算内放弃等待。 */
    private sealed class RemoteTxn<out T> {
        /** 远端已回话。 */
        data class Done<T>(val value: T) : RemoteTxn<T>()

        /**
         * 本地按预算放弃等待。事务本身没有被取消（binder 不可安全中断，排队的也放它
         * 继续）：[submittedBeforeExpiryMs] 非 null 说明它确已递交远端、此后随时可能
         * 完成；为 null 说明它当时还排在本地队列里，之后仍可能被递交执行。
         * 两种都只能按「结局未知」作答。
         */
        data class GaveUp(val waitedMs: Long, val submittedBeforeExpiryMs: Long?) : RemoteTxn<Nothing>()
    }

    /**
     * 把一条阻塞事务交给 [remoteTransactions]，本地最多等 [waitMs]。
     *
     * 超时后放弃的是**本地等待**，不是事务本身：结果被丢弃并记一条运行日志
     * （事后对「客户端超时了、设备上动作却后来完成」这类账全靠它），远端那半个
     * 由它自己跑完。确已递交的给出「递交时刻离本地期限还有多久」，让超时回包
     * 说得出事务提交到了哪一步。
     *
     * [cap] 是这次等待所属的能力（生命周期动作用 [CAP_LIFECYCLE]）：递交与放弃各记一条
     * REMOTE_SUBMITTED / REMOTE_GIVE_UP，两条合起来才能算出「递交之后过了多久才被本地
     * 丢下」，只记其一的话「这条为什么一直没执行」在日志里仍然答不出来。
     */
    private fun <T> transactBoundedBlocking(cap: String, kind: String, waitMs: Long, task: () -> T): RemoteTxn<T> {
        val submittedAt = AtomicLong(0L)
        val waitStartedAt = monotonicNow()
        runLog.info(
            LogSubsystem.BACKEND,
            LogEvent.REMOTE_SUBMITTED,
            "cap" to cap,
            "waitMs" to waitMs.toString(),
        )
        val future = remoteTransactions.submit(Callable {
            // 「已提交远端」的时刻在事务真正起跑时记：排在前面的卡死事务会让起跑
            // 晚于提交，两件事必须分开，超时回包才知道该怎么措辞。
            submittedAt.set(monotonicNow())
            task()
        })
        val outcome = try {
            RemoteTxn.Done(future.get(waitMs, TimeUnit.MILLISECONDS))
        } catch (timeout: TimeoutException) {
            RemoteTxn.GaveUp(waitedMs = waitMs, submittedBeforeExpiryMs = submittedBeforeExpiry(waitStartedAt, waitMs, submittedAt))
        } catch (interrupted: InterruptedException) {
            // 等待被取消（调用整体已放弃）：还原中断位，远端那半个照旧跑完，按放弃作答。
            Thread.currentThread().interrupt()
            RemoteTxn.GaveUp(waitedMs = waitMs, submittedBeforeExpiryMs = submittedBeforeExpiry(waitStartedAt, waitMs, submittedAt))
        } catch (broken: ExecutionException) {
            // 各事务自己兜异常，这里只防「兜漏了」：照原样抛回，由分发层归入内部错误。
            throw broken.cause ?: broken
        }
        if (outcome is RemoteTxn.GaveUp) {
            runLog.warn(
                LogSubsystem.BACKEND,
                LogEvent.REMOTE_RESULT_DROPPED,
                "kind" to kind,
                "waitedMs" to waitMs.toString(),
            )
            // 与上面那条语义互补：那条说「哪一次等待被丢下了」，这条说「递交出去多久之后
            // 才被丢下」。尚未起跑（还排在本地队列里）时按递交那一刻起算，保守取大。
            val submitted = submittedAt.get().let { if (it == 0L) waitStartedAt else it }
            runLog.warn(
                LogSubsystem.BACKEND,
                LogEvent.REMOTE_GIVE_UP,
                "cap" to cap,
                "ago" to (monotonicNow() - submitted).coerceAtLeast(0L).toString(),
            )
        }
        return outcome
    }

    /** 递交时刻离本地期限还有多久；null 表示尚未递交（当时还排在本地队列里）。 */
    private fun submittedBeforeExpiry(waitStartedAt: Long, waitMs: Long, submittedAt: AtomicLong): Long? {
        val submitted = submittedAt.get()
        if (submitted == 0L) return null
        return (waitStartedAt + waitMs - submitted).coerceAtLeast(0L)
    }

    /** 挂起版的受限事务：把阻塞等待挪进 IO 池，不占住分发用的线程。 */
    private suspend fun <T> transactBounded(cap: String, kind: String, waitMs: Long, task: () -> T): RemoteTxn<T> =
        withContext(Dispatchers.IO) { transactBoundedBlocking(cap, kind, waitMs, task) }

    /**
     * 用户服务那条 binder 此刻在不在，只看连接本身（不含 Shizuku 服务端是否活着）。
     * 能力清单里 `backend.shizuku.bound` 报的是它：服务端在跑但用户服务没连上时，
     * 前三条都对、shell 能力照样不可用，这两件事要能分开看。
     */
    /**
     * 「这一条会改系统的动词，用户打开了吗」由装配根供上来：本后端只懂形状与执行，
     * 谁被授权是设备上的用户状态（存在偏好里），写在这里就会让表与界面开关各说一套。
     */
    @Volatile
    var shellOptInOpen: (String) -> Boolean = { false }

    val boundSnapshot: Boolean
        get() = binder != null

    override fun available(): Boolean = state.available && binder != null

    /**
     * 掉线自愈。只由分发处与周期巡检叫，绝不由状态上报叫。
     *
     * 为什么需要显式一次：只靠 ServiceConnection 的掉线回调重绑不够 —— Shizuku 侧按
     * UserServiceArgs 缓存着那条连接，用户服务进程消失后，原样再 bind 一次往往只是命中
     * 缓存、什么也不发生。那样三条 shell 能力会一直回「后端不可用（可重试）」而重试永远
     * 不成功，直到宿主重启，retryable 就成了空话。所以先解绑再绑，绕开那条缓存。
     */
    override fun reconnect() {
        if (binder == null) rebindIfDropped()
    }

    /**
     * 掉线后重新接上：先解绑再绑，绕开 Shizuku 那条缓存；带冷却，避免服务一起来就退时
     * 变成每秒拉起一次。解绑这一步同时让服务端把它持有的那个用户服务收掉，不留残留进程。
     */
    private fun rebindIfDropped() {
        if (deliberatelyUnbound || !state.available) return
        val now = interlock.relay.core.runtime.monotonicNow()
        if (now - lastRebindMs < REBIND_COOLDOWN_MS) return
        lastRebindMs = now
        displayId = -1
        runLog.warn(LogSubsystem.GATE, LogEvent.SHIZUKU_SERVICE_DEAD, "action" to "rebind")
        state.unbindUserService(connection)
        state.bindUserService(connection)
    }

    /**
     * 服务端那块可信屏此刻的编号，-1 表示没有。
     *
     * 这不是「后端连不连得上」的瞬时状态，而是 trusted-display 这个模式的**实现前提**：
     * 屏不在，落在那块屏上的操作就没有落点。写进 [supports] 之后，裁决层会在屏不在时
     * 如实退回前台并带 degradedFrom；缺了这一问，响应会写着 trusted-display 而应用
     * 被弹到用户眼前。
     */
    @Volatile
    var displayId: Int = -1
        private set

    /**
     * 那块虚拟屏此刻在不在，问显示服务而不是问这里的缓存。
     *
     * 建屏时记下编号之后，系统可以因为空闲超时或用户撤销而把这块屏收走；只认 `displayId >= 0`
     * 会让能力清单永远报"屏在"，助手照着它把一条其实已经不通的后台通路当成可用。
     * 显示列表里查不到编号，就是没了。
     */
    fun displayAlive(): Boolean {
        if (displayId < 0) return false
        val manager = appContext.getSystemService(android.hardware.display.DisplayManager::class.java)
            ?: return displayId >= 0
        return manager.getDisplay(displayId) != null
    }

    override fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean = when (capability) {
        CapabilityId.SECURE_SETTINGS,
        CapabilityId.APPOPS_SET,
        CapabilityId.APP_STOP,
        CapabilityId.SYS_SHELL,
        // 安装的文件只认沙盒递交进中转区（`RelayPaths.uploadsDir`，它就在绑定子树**之内**）
        // 的那一个名字：文件名过白名单、逐级过链接检查，路径由本端生成，内容先按 apk
        // 解析过再交给 pm。助手既点不了任意绝对路径，也换不掉核验后的那一个文件。
        CapabilityId.PKG_INSTALL,
        -> true

        // 建屏这条不需要先有屏：它的全部工作就是把屏建出来。
        CapabilityId.SURFACE_VIRTUAL -> surface == SurfaceKind.BACKEND_TRUSTED

        // 落在那块屏上的操作：屏不在就没有落点。
        CapabilityId.APP_LAUNCH,
        CapabilityId.UI_TAP,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        CapabilityId.SCREEN_CAPTURE,
        // 后台录屏：帧只能从这块屏的持有者一帧一帧取，编码在本进程侧做（见 recordOnDisplay）。
        CapabilityId.SCREEN_RECORD,
        -> surface == SurfaceKind.BACKEND_TRUSTED && displayAlive()

        else -> false
    }

    override suspend fun execute(call: BackendCall): BackendResult {
        // 形状判定排在递交件查找与 binder 事务之前：参数写错时这两样的成本一分都不该付。
        argsError(call)?.let { return it }
        // 远端预算的台账从这里起算：审批与排队已在上游扣过，之后的每一次远端往返
        // 都从这份余额里出，装不下一次最小往返的就不发（见 [CallBudget]）。
        val budget = CallBudget(call.budgetMs, call.descriptor.id.wire)
        return when (call.descriptor.id) {
            CapabilityId.SECURE_SETTINGS -> secureSettings(call.args, budget)
            CapabilityId.SYS_SHELL -> shellVerbCall(call, budget)
            CapabilityId.APPOPS_SET -> appOps(call.args, budget)
            CapabilityId.APP_STOP -> forceStop(call.args, budget)
            CapabilityId.PKG_INSTALL -> installApp(call, budget)
            CapabilityId.SURFACE_VIRTUAL -> displayAction(call, budget)
            CapabilityId.APP_LAUNCH -> displayLaunch(call, budget)
            CapabilityId.UI_TAP -> displayTap(call.args, budget)
            CapabilityId.UI_SWIPE -> displaySwipe(call.args, budget)
            CapabilityId.UI_TEXT -> displayText(call.args, budget)
            CapabilityId.UI_KEY -> displayKey(call.args, budget)
            CapabilityId.SCREEN_CAPTURE -> displayCapture(call, budget)
            CapabilityId.SCREEN_RECORD -> recordOnDisplay(call, budget)
            else -> BackendResult.Failed(RelayError.CAPABILITY_NOT_IMPLEMENTED, call.descriptor.id.wire)
        }
    }

    /**
     * 一次调用内远端预算的台账。
     *
     * [BackendCall.budgetMs] 是后端接手那一刻的剩余量，而一次调用往往不止一次远端
     * 往返（写完安全设置要读回、启动完要观察落地再纠一次），前面花掉的要从后面扣：
     * 每次发远端前按单调钟算一次「此刻还剩多少」，再交 [remoteWaitMs] 分配。
     *
     * [cap] 随台账一起往下带：一次调用里的每次远端等待都要在日志上认得出属于哪条能力，
     * 而这些等待散落在十来个私有函数里，各自再收一个 cap 参数只会让调用点各写一遍。
     */
    internal class CallBudget(private val totalMs: Long, val cap: String) {
        private val startedAt = monotonicNow()

        /** 后端接手至今还剩多少预算；上游到点后可能算出负值，按零参与判定。 */
        fun remainingMs(): Long = (totalMs - (monotonicNow() - startedAt)).coerceAtLeast(0L)

        /** 按此刻余额分配本地等待时长；null 表示装不下一次最小往返，不该发远端。 */
        fun remoteWaitMs(defaultMs: Long, marginMs: Long): Long? =
            ShizukuBackend.remoteWaitMs(defaultMs, remainingMs(), marginMs)
    }

    /** 一条能力接受的键集，以及其中不可缺、不可为空的键。 */
    internal data class ArgSpec(val allowed: Set<String> = emptySet(), val required: Set<String> = emptySet())

    /**
     * 本后端承担的能力各自的键表，逐条见 [ARG_SPECS]。null 表示不由本后端实现，
     * 交给分发处回 `E_NOT_IMPLEMENTED`。
     */
    internal fun argSpec(id: CapabilityId): ArgSpec? = ARG_SPECS[id]

    /**
     * 参数来自沙盒，是不可信边界：未知键与空值一律拒，错误码用通道约定的
     * `E_TRANSPORT_MALFORMED`（退出码 1 = 改脚本，不是重试）。
     */
    private fun argsError(call: BackendCall): BackendResult? {
        val spec = argSpec(call.descriptor.id) ?: return null
        val args = call.args
        for (key in args.keys()) {
            if (key !in spec.allowed) {
                return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, ArgErrors.unknown(key, spec.allowed))
            }
        }
        for (key in spec.required) {
            if (args.optString(key).isBlank()) {
                return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, "missing arg: $key")
            }
        }
        return null
    }

    /**
     * `sys.shell`：动词与参数先过 [ShellVerbs] 的封闭表，再由本端拼出命令串。
     *
     * 命令串不是助手给的那一段文本 —— 表外动词不存在，表内动词的参数各自过形状，
     * 拼装时统一单引号包裹。回包带上实际执行的命令：确认卡上要给用户看的就是这一行，
     * 事后审计记录的也是这一行，两处不能各写一套。
     */
    private suspend fun shellVerbCall(call: BackendCall, budget: CallBudget): BackendResult {
        return when (val plan = ShellVerbs.plan(call.args, shellOptInOpen)) {
            is ShellVerbs.Plan.Bad -> malformed(plan.reason)
            is ShellVerbs.Plan.NeedsOptIn -> BackendResult.Failed(
                RelayError.GATE_HOST_DENIED,
                "verb '${plan.verb}' changes the system and is not turned on: open it in the " +
                    "dangerous-capabilities page (shape: ${plan.shape})",
            )
            is ShellVerbs.Plan.Run -> when (plan.verb) {
                "screencap" -> screenshotArtifact(call, budget)
                else -> {
                    val outcome = run(plan.command, budget)
                    if (outcome is BackendResult.Failed) outcome else {
                        (outcome as BackendResult.Ok).data.put("verb", plan.verb).put("command", plan.command)
                        outcome
                    }
                }
            }
        }
    }

    /**
     * `screencap` 那一帧经 fd 取回，落成产物文件 —— 与可信屏取帧同一形状。
     *
     * 回包里不写服务端那个临时路径（它已经不存在）：写路径会让宿主与助手都以为还有一个
     * 可读的文件，而字节只在产物里，由那套配额与回收管着。
     *
     * 服务端那头的 `screencap` 自带 5 秒上限（写死在用户服务里，改不到）；本地等待按
     * 预算收口，预算装不下这个上限时本地先放弃，截图可能稍后才落成——结局只能按
     * 未知作答，不能回「没截到」。
     */
    private suspend fun screenshotArtifact(call: BackendCall, budget: CallBudget): BackendResult {
        val target = binder ?: return BackendResult.Failed(RelayError.BACKEND_SHIZUKU_DEAD, null)
        val waitMs = budget.remoteWaitMs(SCREENSHOT_WAIT_MS, REMOTE_MARGIN_ARTIFACT_MS)
            ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_ARTIFACT_MS)
        var serviceNote = ""
        val bytes = when (val txn = transactBounded(budget.cap, "screencap", waitMs) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                if (!target.transact(ShellProtocol.CODE_SCREENSHOT, data, reply, 0)) {
                    serviceNote = "transaction refused by the shell service"
                    return@transactBounded null
                }
                // 状态整数后面跟着服务端写的那句原因。`screencap` 超时、退出码非零、
                // 文件是空的、开 fd 失败、路径删不掉是五种不同的事——只回一句"没拿到帧"
                // 就得靠人猜，而这一问只有在真机上才答得清。
                val status = reply.readInt()
                serviceNote = reply.readString().orEmpty()
                if (status != 0) return@transactBounded null
                val descriptor = reply.readFileDescriptor() ?: return@transactBounded null
                try {
                    java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }.takeIf { x -> x.isNotEmpty() }
                } finally {
                    runCatching { descriptor.close() }
                }
            } catch (t: Throwable) {
                if (serviceNote.isBlank()) serviceNote = t.javaClass.simpleName
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> return BackendResult.Failed(
                RelayError.TRANSPORT_TIMEOUT,
                remoteGiveUpReason("screencap", txn.waitedMs, txn.submittedBeforeExpiryMs),
            )
        } ?: return BackendResult.Failed(
            RelayError.BACKEND_UNAVAILABLE,
            "no frame from screencap" + if (serviceNote.isBlank()) "" else ": $serviceNote",
        )
        val staged = reaper.newArtifactFile(call.requestId, ARTIFACT_SUFFIX, CAPTURE_ESTIMATE_BYTES)
            ?: return BackendResult.Failed(RelayError.STORAGE_FULL, "quota exhausted")
        val written = runCatching { staged.writeBytes(bytes) }.isSuccess && staged.length() > 0L
        if (!written) {
            reaper.discard(staged)
            return BackendResult.Failed(RelayError.INTERNAL, "frame copy failed")
        }
        return publishArtifact(staged, call, JSONObject().put("bytes", staged.length()).put("source", "screencap"))
    }

    private suspend fun secureSettings(args: JSONObject, budget: CallBudget): BackendResult {
        val key = args.optString(KEY_SETTING)
        if (!KEY_PATTERN.matches(key)) return malformed(KEY_SETTING)
        // 这些键本身就是把下一道门开给别的应用，改它们等于绕过本模块的全部闸门；
        // 无障碍与输入法键同时是系统权限探测的依据，放开会让闸门读到被自己改写的值。
        if (isPrivilegeHandoffKey(key)) return BackendResult.Failed(RelayError.GATE_HOST_DENIED, "key blocked: $key")
        // 取值缺失与空白已在键表里挡掉，这里只剩长度上限。
        val value = args.optString(KEY_VALUE)
        if (value.length > MAX_VALUE_CHARS) return malformed("value too long")
        // 命名空间可以省略，省下来仍是 secure：这条能力最早只写 secure，把默认值改掉
        // 会让照旧写法的老脚本落到另一层去。
        val namespace = args.optString(KEY_NAMESPACE).ifBlank { DEFAULT_NAMESPACE }
        if (namespace !in SETTINGS_NAMESPACES) {
            return malformed(
                "$KEY_NAMESPACE must be one of ${SETTINGS_NAMESPACES.joinToString("|")}, got '$namespace'",
            )
        }
        val write = run("settings put $namespace ${shellQuote(key)} ${shellQuote(value)}", budget)
        if (write is BackendResult.Failed) return write
        // 写完读一次。`settings put` 退出码为零却什么都没落地的场合确实存在，
        // 而只看退出码的话，助手拿到的是一个"改好了"。读回这一问装不进剩余预算时
        // 得到 null（verified:false 如实降级）——写已确认落地，不能为读回把整条
        // 调用改判成超时。
        val readBack = ask("settings get $namespace ${shellQuote(key)}", budget)
        // 一次只写一层。若同一个键名在别的层里也有值，大概率助手要改的是那一层
        // （屏幕亮度、音量都在 system），而这里改出来的是一个从没被谁读过的孤立键：
        // 写是写成功了，生效值一个都没变。
        // 两层探测并成**一条**事务：这条写是用户刚在确认框上批过的，多一次 shell 往返
        // 就多一分预算不够的风险，而预算不够的方向是"写已落地却回一条可重试的超时"——
        // 助手照着重发，就是把同一条已经生效的写再做一遍。
        val shadowedBy = shadowedNamespace(key, namespace, budget)
        return BackendResult.Ok(
            data = JSONObject()
                .put("namespace", namespace)
                .put("key", key)
                .put("requested", value)
                .put("readBack", readBack ?: JSONObject.NULL)
                .put("verified", readBack == value)
                .apply {
                    if (shadowedBy != null) {
                        put(
                            "shadowedBy",
                            shadowedBy,
                        ).put(
                            "note",
                            "this key also has a value in '$shadowedBy' while this call wrote " +
                                "'$namespace': the effective setting probably did not change - " +
                                "pass \"$KEY_NAMESPACE\" for the layer that key actually lives in",
                        )
                    }
                },
        )
    }

    /**
     * 这个键名是否在**没被这次写到的**那几层里也有值。一次事务问两层，
     * 输出按行解析：`settings get` 对不存在的键打印字面量 `null`，那不算"有值"。
     */
    private suspend fun shadowedNamespace(key: String, written: String, budget: CallBudget): String? {
        val quoted = shellQuote(key)
        val others = SETTINGS_NAMESPACES.filterNot { it == written }
        val probe = others.joinToString(" ") { "$it=\$(settings get $it $quoted)" }
        val out = ask("echo $probe", budget) ?: return null
        return others.firstOrNull { namespace ->
            val value = out.substringAfter("$namespace=", "").substringBefore(' ')
            value.isNotEmpty() && value != "null"
        }
    }

    private suspend fun appOps(args: JSONObject, budget: CallBudget): BackendResult {
        val pkg = args.optString(KEY_PACKAGE)
        val op = args.optString(KEY_OP)
        val mode = args.optString(KEY_MODE)
        if (!PACKAGE_PATTERN.matches(pkg)) return malformed(KEY_PACKAGE)
        // `op` 的写法要归一：调用侧常用短名（`CAMERA`），而这张白名单存的是
        // `android:camera`，按原样比对会把合法写法判成越界（`mode` 大写被拒是同一类）。
        // 归一只做大小写与 `android:` 前缀这两件事，不做包含匹配 ——
        // 短名撞进别的操作名就是误放行。
        val wanted = op.removePrefix("android:").lowercase()
        val opName = ALLOWED_OPS.firstOrNull { it.removePrefix("android:").lowercase() == wanted }
            ?: return malformed("op not allowed: $op, accepted: ${ALLOWED_OPS.joinToString()}")
        // 一律小写递给 appops：`appops set` 认 `default`，不认 `DEFAULT`，
        // 而 DEFAULT 在我们自己的白名单里 —— 按大写送过去就等于这条模式永远设不成功。
        val normalized = mode.lowercase()
        if (normalized !in ALLOWED_MODES) return malformed("$KEY_MODE must be one of ${ALLOWED_MODES.joinToString()}")
        val set = run("appops set $pkg $opName $normalized", budget)
        if (set is BackendResult.Failed) return set
        // 回读一次。`appops get` 交回来的不是一行模式名，而是这样一段：
        //   Uid mode: CAMERA: foreground
        //   CAMERA: allow
        // 第一行是这一 uid 的生效模式，后面可能还跟着 duration/限制之类的说明。
        // 原样交回是诚实，但助手还得自己读，所以这里再挑出最后那条 `名字: 模式` 当 effective：
        // 我们递过去的是 `default`，而它回的是 allow —— 这正是"设成了 default、生效值仍是 allow"
        // 那件事的正面证据。挑不出来就不给这个字段（宁缺不猜）。
        val readBack = ask("appops get $pkg $opName", budget)
        val effective = APPOPS_MODE.findAll(readBack.orEmpty()).lastOrNull()?.groupValues?.get(1)
        return BackendResult.Ok(
            data = JSONObject()
                .put("package", pkg)
                .put("op", opName)
                .apply {
                    effective?.let { put("effective", it) }
                }
                .put("requested", normalized)
                .put("readBack", readBack ?: JSONObject.NULL),
        )
    }

    private suspend fun forceStop(args: JSONObject, budget: CallBudget): BackendResult {
        val pkg = args.optString(KEY_PACKAGE)
        if (!PACKAGE_PATTERN.matches(pkg)) return malformed(KEY_PACKAGE)
        // 停宿主自己等于把这条通道连根拔掉：信箱、确认框、正在跑的采集一起没，
        // 而且无障碍服务会因此解绑。与 `app.install` 拒装宿主是同一条理由 ——
        // 助手不该有能力让替它做决定的那个人消失。
        if (pkg == appContext.packageName) {
            return BackendResult.Failed(RelayError.GATE_HOST_DENIED, "refusing to stop the host app")
        }
        return run("am force-stop $pkg", budget)
    }

    /**
     * 安装一个 apk。三道边界缺一不可：
     * - 入口只有「沙盒递交过的上传名」，落点文件名由本端生成 —— 助手报不出任意绝对路径，
     *   也就没有「放一个装一个」；
     * - 递给 shell 侧的是**已核验那个 fd 的副本**，不是路径：guest 与宿主同 uid，
     *   只要还按路径二次解析，核验之后改名换掉这个文件就是它做得到的事；
     * - 先按 apk 解析出包名，解析不出就不是安装包；包名等于本应用时直接拒 ——
     *   让助手从内部替换宿主，超出任何一条能力被授权时的语义。
     *
     * 档位上限是 `ASK_ONLY`：每一次安装都要用户当场确认，没有免弹通道。
     */
    private suspend fun installApp(call: BackendCall, budget: CallBudget): BackendResult {
        val name = call.args.optString(KEY_UPLOAD)
        val upload = call.paths.openVettedUpload(name, MAX_INSTALL_BYTES)
            ?: return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, "no such upload: $name")
        try {
            val pkg = runCatching {
                appContext.packageManager.getPackageArchiveInfo(upload.file.absolutePath, 0)?.packageName
            }.getOrNull()
            if (pkg.isNullOrBlank()) {
                return BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, "not an installable package")
            }
            if (pkg == appContext.packageName) {
                return BackendResult.Failed(RelayError.GATE_HOST_DENIED, "refusing to replace the host app")
            }
            val target = binder ?: return BackendResult.Failed(RelayError.BACKEND_SHIZUKU_DEAD, null)
            // 安装是这条通路里最慢的远端事务（解包、优化 dex），预算装不下就一条都不发：
            // 安装没有"只装了一半"，发不出去与发出去没等到是两种必须分开的结论。
            val waitMs = budget.remoteWaitMs(INSTALL_TIMEOUT_MS, REMOTE_MARGIN_MS)
                ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
            val descriptor = runCatching { upload.openDescriptor() }.getOrNull()
                ?: return BackendResult.Failed(RelayError.BACKEND_SHIZUKU_DEAD, "descriptor or binder unavailable")
            // 远端那条 `pm install` 的超时参数同步夹到同一时长：远端先到点自己收尾，
            // 本地通常等得到回执，不必动用"放弃等待"。
            val outcome = when (val txn = transactBounded(budget.cap, "install", waitMs + REMOTE_SLACK_MS) {
                transactInstall(target, descriptor, stagedName(call.requestId), waitMs)
            }) {
                is RemoteTxn.Done -> txn.value
                is RemoteTxn.GaveUp -> return BackendResult.Failed(
                    RelayError.TRANSPORT_TIMEOUT,
                    remoteGiveUpReason("install", txn.waitedMs, txn.submittedBeforeExpiryMs),
                )
            } ?: return BackendResult.Failed(RelayError.BACKEND_SHIZUKU_DEAD, "descriptor or binder unavailable")
            if (!outcome.success) {
                return BackendResult.Failed(
                    RelayError.GATE_SYSTEM_MISSING,
                    "exit=" + outcome.exitCode + " " + outcome.stderr.trim().take(MAX_ERROR_CHARS),
                )
            }
            // 装成功就收掉递交件：它在绑定子树里，留着等于让沙盒拿同一个包反复重装。
            upload.deleteSource()
            return BackendResult.Ok(
                data = JSONObject()
                    .put("package", pkg)
                    .put("bytes", upload.size)
                    .put("output", outcome.stdout.trim()),
            )
        } finally {
            upload.close()
        }
    }

    /** 落点名由本端拼：请求标识过滤后加时间戳，助手指定不了，也不会互相覆盖。 */
    private fun stagedName(requestId: String): String {
        val base = requestId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(48).ifBlank { "pkg" }
        return "install-" + base + "-" + System.nanoTime().toString(16) + ".apk"
    }

    /** 安装请求：fd 按引用发给服务端（由内核 dup），本端用完立即关掉。 */
    private fun transactInstall(
        target: IBinder,
        descriptor: android.os.ParcelFileDescriptor,
        staged: String,
        timeoutMs: Long,
    ): RelayShellService.Outcome? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            // 单参形式即 solid=true：接收端在调用在途时按 /proc/<pid>/fd 取到同一个文件的副本 fd。
            data.writeFileDescriptor(descriptor.fileDescriptor)
            data.writeString(staged)
            data.writeLong(timeoutMs)
            val sent = target.transact(ShellProtocol.CODE_INSTALL, data, reply, 0)
            if (!sent) {
                null
            } else {
                RelayShellService.Outcome(reply.readInt(), reply.readString() ?: "", reply.readString() ?: "")
            }
        } catch (dead: RemoteException) {
            null
        } catch (t: Throwable) {
            RelayShellService.Outcome(-1, "", t.javaClass.simpleName)
        } finally {
            runCatching { descriptor.close() }
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * 递一条命令并等回执。
     *
     * 本地等待与发给远端的超时参数同步按调用预算收口（见 [remoteWaitMs]）：远端先到点
     * 自己收掉命令，本地通常等得到回执；预算装不下时不发远端，直接按超时作答。
     */
    private suspend fun run(
        command: String,
        budget: CallBudget,
        defaultTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
    ): BackendResult {
        val target = binder ?: return BackendResult.Failed(RelayError.BACKEND_SHIZUKU_DEAD, null)
        val waitMs = budget.remoteWaitMs(defaultTimeoutMs, REMOTE_MARGIN_MS)
            ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
        val outcome = when (val txn = transactBounded(budget.cap, "command", waitMs + REMOTE_SLACK_MS) {
            transact(target, command, waitMs)
        }) {
            is RemoteTxn.Done -> txn.value
                ?: return BackendResult.Failed(RelayError.BACKEND_SHIZUKU_DEAD, "binder unavailable")
            is RemoteTxn.GaveUp -> return BackendResult.Failed(
                RelayError.TRANSPORT_TIMEOUT,
                remoteGiveUpReason("command", txn.waitedMs, txn.submittedBeforeExpiryMs),
            )
        }
        return if (outcome.success) {
            BackendResult.Ok(
                data = JSONObject().put("output", outcome.stdout.trim()).put("exit", outcome.exitCode),
            )
        } else {
            val (error, reason) = commandFailure(outcome.exitCode, outcome.stderr.trim())
            BackendResult.Failed(error, reason)
        }
    }

    /**
     * 预算装不下一次最小远端往返时的标准回答：什么都没发出去。
     *
     * 这条结论与「本地放弃等待、远端可能仍在执行」是**相反**的两个方向，v2 状态链路
     * 要靠 reason 里的这一句区分「没开始」与「结局未知」，所以两者共用错误码、
     * 各说各的事实。
     */
    private fun remoteBudgetExhausted(budget: CallBudget, marginMs: Long): BackendResult =
        BackendResult.Failed(
            RelayError.TRANSPORT_TIMEOUT,
            "remaining call budget (${budget.remainingMs()}ms) minus the ${marginMs}ms close-out " +
                "reserve cannot cover one remote round trip (needs at least " +
                "${MIN_REMOTE_ROUNDTRIP_MS}ms): nothing was sent",
        )

    /**
     * 非零退出码分成三类，因为下一步动作相反。
     *
     * `127` 是**这台机器上根本没有那个命令**（`/system/bin/media` 在部分机型上就不存在）——
     * 用户去开任何开关、授任何权限都不会改变它，报成「去手机上处理」等于把一条设备边界写成待办。
     * 带拒绝字样的才是缺授权那一类。第三类是命令自己失败了（参数合法但系统不认），
     * 它既不是设备边界也不是权限问题，只能如实回 `E_INTERNAL` 并留下原始那一句：
     * 换码只换「下一步做什么」，不该把病历丢掉。
     */
    private fun commandFailure(exitCode: Int, stderr: String): Pair<RelayError, String> {
        val detail = "exit=$exitCode $stderr"
        return when {
            // 设备根本没有这条二进制：不是后端断了，也不是重试能解决的问题，
            // 所以不能用 E_BACKEND_UNAVAILABLE（那条 retryable:true，回包会一边说"再试也没用"
            // 一边让人去重试）。归到"这台设备给不出这个能力"，不可重试。
            exitCode == EXIT_COMMAND_NOT_FOUND || "inaccessible or not found" in stderr ->
                RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE to
                    "$detail: this device has no such command, no switch or grant will add it"
            "Permission Denial" in stderr || "SecurityException" in stderr || "not allowed" in stderr ->
                RelayError.GATE_SYSTEM_MISSING to detail
            else -> RelayError.INTERNAL to detail
        }
    }

    /**
     * 递一条只读命令并取回标准输出；空串、`null` 字样或任何失败都回 null。
     * `settings get` 对不存在的键就是打印 `null` 而退出码为 0——不把这两种分开，
     * "键不存在"就会被当成"读到了一个值叫 null 的键"。
     */
    private suspend fun ask(command: String, budget: CallBudget): String? {
        val output = (run(command, budget) as? BackendResult.Ok)?.data?.optString("output")?.trim().orEmpty()
        return output.takeUnless { it.isEmpty() || it == "null" }
    }

    /**
     * 命令事务的裸收发。返回 null 表示 binder 已不可用，与「命令跑完但退出码非零」
     * 是两类失败，重试价值不同。[timeoutMs] 写进事务参数由远端执行：它是远端自己
     * 杀命令的上限，由调用方按预算算好传入（见 [remoteWaitMs]）。
     */
    private fun transact(target: IBinder, command: String, timeoutMs: Long): RelayShellService.Outcome? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(command)
            data.writeLong(timeoutMs)
            val sent = target.transact(ShellProtocol.CODE_RUN, data, reply, 0)
            if (!sent) null else RelayShellService.Outcome(reply.readInt(), reply.readString() ?: "", reply.readString() ?: "")
        } catch (dead: RemoteException) {
            null
        } catch (t: Throwable) {
            RelayShellService.Outcome(-1, "", t.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun malformed(reason: String) =
        BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, reason)

    // ── 可信虚拟屏：后台操控通路 ──
    //
    // 这里只发已被证实可用的命令：`am start --display <id>` 能把任务落进那块屏、
    // `input -d <id>` 能驱动那块屏上的界面，而 `screencap -d <id>` 对虚拟屏固定失败，
    // 取帧改走持有该屏 Surface 的那条 binder 事务。
    // 屏由服务端进程持有，宿主只缓存它的编号：在这里假设「屏还在」会让服务端被回收后
    // 把命令发给一个不存在的显示，表现为静默无效。

    /** `surface.virtual`：建屏、收屏、问屏。屏的存续期就是一次助手会话。 */
    private suspend fun displayAction(call: BackendCall, budget: CallBudget): BackendResult {
        val args = call.args
        val action = args.optString(KEY_ACTION).ifBlank { ACTION_CREATE }
        if (action == ACTION_CREATE) {
            // 本地缓存说过期就过期：那块屏归服务端进程持有，它可能被系统回收、也可能被
            // 上一次 release 收掉，而宿主这边只会一直留着旧编号。先对一次账再决定复用，
            // 否则「create 返回 kept」指的是一块已经不存在的屏。预算装不下这次对账时
            // 缓存维持原样——「屏在不在」随后由本地 DisplayManager 说了算，不受影响。
            if (displayAlive()) refreshDisplay(budget)
            if (displayAlive()) return BackendResult.Ok(data = displayData("kept"))
            val waitMs = budget.remoteWaitMs(DISPLAY_TXN_WAIT_MS, REMOTE_MARGIN_MS)
                ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
            val created = when (val txn = displayTransaction(budget.cap, ShellProtocol.CODE_DISPLAY_CREATE, waitMs) {
                it.writeInt(args.optInt(KEY_WIDTH, DEFAULT_WIDTH))
                it.writeInt(args.optInt(KEY_HEIGHT, DEFAULT_HEIGHT))
                it.writeInt(args.optInt(KEY_DPI, DEFAULT_DPI))
            }) {
                is RemoteTxn.Done -> txn.value
                is RemoteTxn.GaveUp -> return BackendResult.Failed(
                    RelayError.TRANSPORT_TIMEOUT,
                    remoteGiveUpReason("display create", txn.waitedMs, txn.submittedBeforeExpiryMs),
                )
            }
            if (created.code == RelayShellService.TrustedDisplay.CODE_UNVERIFIED) {
                // 服务端**没说屏不在了**，只说这一问没验成：那块屏可能还在跑。所以既不清缓存的编号
                // （清了就一直说"没有屏"），也不能报"系统拒绝了创建"让用户去动手机 ——
                // 唯一有效的下一步是助手自己 release 再 create，那是可重试的通路问题，不是用户动作。
                return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, created.reason)
            }
            if (created.code < 0) {
                displayId = -1
                // 服务活着、事务有回话，是**系统拒绝建这块屏** —— 不是"后端没连上、等等再来"。
                // 报成可重试会让助手反复建屏，而这条的重试次数由系统决定，不由我们决定。
                return BackendResult.Failed(RelayError.SURFACE_TRUSTED_DENIED, created.reason)
            }
            displayId = created.code
            // 新建即作废缓存：那块屏可能拿回同一个编号却是完全不同的内容，而这份缓存是
            // 按 displayId 认账的 —— 编号复用这一种情况只能靠"建新屏就清掉"来兜。
            frameCache?.clear()
            // 尺寸以服务端为准：请求里的宽高会被夹到允许区间，回原值等于报一个不存在的屏。
            refreshDisplay(budget)
            return BackendResult.Ok(data = displayData(ACTION_CREATE))
        }
        if (action == ACTION_RELEASE) {
            val waitMs = budget.remoteWaitMs(DISPLAY_TXN_WAIT_MS, REMOTE_MARGIN_MS)
                ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
            val released = when (val txn = displayTransaction(budget.cap, ShellProtocol.CODE_DISPLAY_RELEASE, waitMs)) {
                is RemoteTxn.Done -> txn.value
                is RemoteTxn.GaveUp -> {
                    // 收屏的结局未知：本地按「已要求收掉」记账（编号与缓存一并作废），
                    // 回包如实说等待被放弃——屏可能已被收掉，也可能稍后才会。
                    displayId = -1
                    frameCache?.clear()
                    return BackendResult.Failed(
                        RelayError.TRANSPORT_TIMEOUT,
                        remoteGiveUpReason("display release", txn.waitedMs, txn.submittedBeforeExpiryMs),
                    )
                }
            }
            displayId = -1
            // 屏收了，那份"上一帧"就是在替一块不存在的屏说话。
            frameCache?.clear()
            return if (released.code < 0) {
                BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, released.reason)
            } else {
                // 0 = 服务端本来就没有屏（空闲到点自己收过、或上一次已收）。这是一次
                // 无事可做的成功，不是后端不可用。
                BackendResult.Ok(data = JSONObject().put("released", released.code == 1))
            }
        }
        if (action != ACTION_QUERY) {
            return malformed(
                "unknown action: $action, accepted: [$ACTION_CREATE, $ACTION_RELEASE, $ACTION_QUERY]",
            )
        }
        // 与建屏同一句对账：只更新编号会把宽高留成上一块屏的尺寸，收屏之后
        // `query` 就会报出一个既不存在、尺寸又是别人的屏。这一问本身就是这条
        // 能力的远端事务：装不下一次最小往返就如实回超时，不拿旧缓存冒充新账。
        if (!refreshDisplay(budget)) return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
        return BackendResult.Ok(data = displayData(ACTION_QUERY))
    }

    private suspend fun displayLaunch(call: BackendCall, budget: CallBudget): BackendResult {
        val args = call.args
        val packageName = args.optString(KEY_PACKAGE)
        // 组件由本机 PackageManager 解析，命令里只出现解析结果：助手递不进任意命令行。
        // 解析不出来是**参数问题**，不是缺系统权限：递进来一个显示名、或本机没有这个包，
        // 前台那条通路回的是 E_TRANSPORT_MALFORMED（退出码 1 = 改脚本），这里若回成
        // E_GATE_SYSTEM_MISSING（退出码 4 = 去授予什么），同一个错在两块屏上会给出
        // 两条相反的处置建议，而用户那里根本没有可授予的开关。
        val component = appContext.packageManager.getLaunchIntentForPackage(packageName)?.component
            ?.flattenToShortString()
            ?: return malformed("not launchable here: $packageName")
        val display = requireDisplay() ?: return noDisplay()
        // 先记一笔用户眼前那块屏停着谁。没有这一笔，"启动后 display 0 上是这个包"
        // 有两种完全相反的解释：我们的启动把它抢回了真屏，或者用户**本来**就开着它
        // （单实例的 App 最常见）。前者要报给用户，后者报出来就是一次冤枉的"抢屏"告警。
        val before = topPackages(budget)
        val launched = run("am start --display $display -n $component", budget, LAUNCH_TIMEOUT_MS)
        if (launched !is BackendResult.Ok) return launched
        launched.data.put("package", packageName).put("display", display)
        // `am start` 的退出码只说明这个请求被接下，不说明它落在了哪块屏。
        // 观察窗口按通道预算收口：通道同一时刻只跑一条请求，而一次冷启动的 resume
        // 可能远超这里给的几次复查——等不到就如实回"没看到"，不替用户下结论。
        val deadline = interlock.relay.core.runtime.monotonicNow() + call.budgetMs.coerceIn(0L, LANDING_WAIT_MS)
        var tops = topPackages(budget)
        while (!landingSettled(tops, display, packageName, before) && interlock.relay.core.runtime.monotonicNow() < deadline) {
            delay(SETTLE_WAIT_MS)
            tops = topPackages(budget)
        }
        reportLanding(launched.data, tops, display, packageName, before)
        // 起完才发现屏已经没了，与"命令跑失败"是同一条修复动作：让助手重建屏，
        // 而不是拿一条"系统权限缺失"去让用户翻设置。
        if (tops == null) {
            refreshDisplay(budget)
            if (!displayAlive()) return noDisplay()
        }
        if (tops?.get(display) == packageName) return launched
        // 没落地就不回成功。`am start` 的退出码只说明请求被接下，而 ok:true 会让助手接着往
        // 那块屏上操作 —— 它操作的是另一个窗口，回包却一路说顺。先纠一次（同一组件按
        // NEW_TASK 再递一遍：已被占用的显示面上最常见的是任务停在别的屏上），仍不落就明确失败。
        run("am start --display $display -n $component -f 0x10000000", budget, LAUNCH_TIMEOUT_MS)
        val extraDeadline = interlock.relay.core.runtime.monotonicNow() +
            minOf(RETRY_LANDING_WAIT_MS, call.budgetMs.coerceAtLeast(1L))
        var retried = topPackages(budget)
        while (retried?.get(display) != packageName && interlock.relay.core.runtime.monotonicNow() < extraDeadline) {
            delay(SETTLE_WAIT_MS)
            retried = topPackages(budget)
        }
        if (retried?.get(display) == packageName) {
            launched.data.put("retried", true)
            reportLanding(launched.data, retried, display, packageName, before)
            return launched
        }
        // 之后那三档必须分开回：「没看见任何前台窗口」是**观察不到**，「那块屏此刻是别的应用」
        // 才是没落地。把前者说成后者，助手会去改脚本或放弃一条其实已完成的启动；
        // 本次调用的预算被答案框用光时也会落进"观察不到"这一档，而不是失败。
        val seen = retried
            ?: return BackendResult.Failed(
                RelayError.BACKEND_UNAVAILABLE,
                "cannot read any display's top window after the retry: the launch may still have " +
                    "happened - re-check with pkg.query or ui.snapshot instead of re-launching blind",
            )
        if (seen[display] == null) {
            refreshDisplay(budget)
            if (!displayAlive()) return noDisplay()
            return BackendResult.Failed(
                RelayError.BACKEND_UNAVAILABLE,
                "display $display reported no top window yet: still starting, or observation " +
                    "ran out of this call's budget - retry rather than treating it as a refusal",
            )
        }
        val topNow = seen[display]
        val grabbedUserScreen = seen[DEFAULT_DISPLAY] == packageName && before?.get(DEFAULT_DISPLAY) != packageName
        // 停在最前面的是**中介包**时，处置方向与"没落地"相反：那是一个等用户挑图标的系统选择框，
        // 重建虚拟屏收不掉它。这一支单独回，并把唯一看得见它的办法（截帧）写进原因里。
        if (LaunchMediators.isMediator(topNow) || LaunchMediators.isMediator(seen[DEFAULT_DISPLAY])) {
            // 「让用户自己选」与「带屏号去点」都不是万能下一步：落点屏由用户的执行模式偏好决定，
            // ui.tap 不接受 display 那一类参数；选择框停在虚拟屏上时，用户那块屏看不见它。
            // 所以给的是真能走的那条：先把界面带回主屏（切执行模式或收掉这面虚拟屏），再截帧答掉。
            return BackendResult.Failed(
                RelayError.LAUNCH_NOT_LANDED,
                "accepted but a system chooser may hold the front (top on display $display is $topNow, " +
                    "the user's own screen shows ${seen[DEFAULT_DISPLAY]}): the target is waiting for " +
                    "someone to pick an icon. The box may or may not expose a node tree on this ROM, so " +
                    "read ui.snapshot first and fall back to screen.capture: the two icons often carry " +
                    "neither text nor contentDescription, and only pixels tell the original from the " +
                    "clone. " +
                    "ui.tap takes no display argument: which screen a call runs on is the user's " +
                    "execution-mode choice, so bring the interface back to the main screen first " +
                    "(switch the execution mode, or send surface.virtual {\"action\":\"release\"}), " +
                    "then capture, answer the box, and send this same launch again. Re-creating the " +
                    "virtual display dismisses nothing.",
            )
        }
        // 落不到不能只报状态：这一条的下一步是**换参数**而不是重发同一份，
        // 所以把要递的那一串字面写出来。没有可靠办法把已在运行的任务搬到虚拟屏前台
        // （`am start --display` 只表示系统接下了请求），所以这里给的是绕开路的具体入参。
        return BackendResult.Failed(
            RelayError.LAUNCH_NOT_LANDED,
            "accepted but not realised on display $display (top is $topNow)" +
                if (grabbedUserScreen)
                    " - it came up on display $DEFAULT_DISPLAY, the user's own screen: send " +
                    "surface.virtual {\"action\":\"release\"} and then surface.virtual {} to get a " +
                    "fresh display, or leave trusted-display mode and drive the app where it is"
                else
                    " - send surface.virtual {\"action\":\"release\"} then surface.virtual {} to " +
                    "re-create it; retrying this same call will land the same way",
        )
    }

    /** 三处都读到了值就可以收工：再等只会更慢，不会更准。 */
    private fun landingSettled(
        tops: Map<Int, String>?,
        display: Int,
        packageName: String,
        before: Map<Int, String>?,
    ): Boolean {
        val seen = tops ?: return false
        if (seen[display] != null && seen[display] != packageName) return true
        if (seen[display] == packageName) return true
        return seen[DEFAULT_DISPLAY] != before?.get(DEFAULT_DISPLAY)
    }

    /**
     * 把落点如实写进响应。观察结果分开说，因为助手对它们的后续动作各不相同：
     * 落在目标屏 = 成功；落在用户真屏且**是这次启动造成的** = 必须说；
     * 目标屏读得到而上面是别的活动 = 只报"看到的是什么"，不替它编一个结论；
     * 整次观察没成 = `landingCheck:"failed"`，既不是成功也不是失败。
     *
     * 需要 `topOnTarget` 与 `targetMoved` 两个量而不是一个包名判等：路由活动、别名与跨包
     * app link 会让真正 resume 的活动的包名不等于请求里那个包名（例如启动设置主页时
     * resume 的是搜索活动）。只按包名判等时这类启动会永远报 verified:false，
     * 而屏上其实已经换了内容。
     */
    private fun reportLanding(
        data: JSONObject,
        tops: Map<Int, String>?,
        display: Int,
        packageName: String,
        before: Map<Int, String>?,
    ) {
        val seen = tops
        if (seen == null) {
            data.put("verified", false).put("landingCheck", "failed")
            return
        }
        val onTarget = seen[display] == packageName
        val topThere = seen[display]
        data.put("verified", onTarget)
        data.put("landedOn", if (onTarget) display else JSONObject.NULL)
        data.put("topOnTarget", topThere ?: JSONObject.NULL)
        if (onTarget) return
        val moved = topThere != null && before?.get(display) != topThere
        data.put("targetDisplayMoved", moved)
        val onUserScreen = seen[DEFAULT_DISPLAY] == packageName
        if (onUserScreen && before?.get(DEFAULT_DISPLAY) != packageName) {
            data.put("grabbedUserScreen", true).put(
                "note",
                "the app came up on the user's own display: it did not stay on the trusted display",
            )
        } else if (onUserScreen) {
            data.put(
                "note",
                "the user was already on this app before the launch, so this response does not say where it came up",
            )
        } else if (moved) {
            data.put(
                "note",
                "something came up on the target display but its top activity is not the requested package " +
                    "(a router activity, an alias or a cross-package launch looks like this)",
            )
        }
    }

    /**
     * 各显示此刻的顶层活动包名，一次 `dumpsys` 拿全。
     *
     * 不按显示逐个问：通道同一时刻只跑一条请求，两次问法会把一次启动变成六条 shell 事务。
     * 回 null 表示这一问没成 —— 与「问成了，那块屏上不是这个包」必须是两个答案，
     * 否则「查不了」会被读成「两处都看过都没看见」。
     */
    private suspend fun topPackages(budget: CallBudget): Map<Int, String>? {
        val dump = ask(TOP_PACKAGE_COMMAND, budget) ?: return null
        val out = HashMap<Int, String>()
        var display = -1
        for (line in dump.lineSequence()) {
            val trimmed = line.trim()
            DISPLAY_SECTION.find(trimmed)?.let { display = it.groupValues[1].toInt() }
            if (display >= 0 && !out.containsKey(display)) {
                TOP_ACTIVITY.find(trimmed)?.let { out[display] = it.groupValues[1] }
            }
        }
        return out
    }

    /**
     * 坐标类整数键的三条判据，与前台那条通路对齐：缺键点名缺的是哪个键，有键但不是数
     * 说「must be a number」，是数却超出整数范围说越界。
     *
     * 前两条少了任何一条，`args.getInt` 抛出的 JSONException 就会被分发层记成
     * `E_INTERNAL`（退出码 6 读起来像宿主坏了，真相是脚本写错了参数），而同一个错误在
     * `ui.tap` 落前台时回的是退出码 1。第三条是**必须落在整数范围内**这一问不能省：
     * `getInt` 对 Long 走 `Number.intValue()`，即静默取低 32 位 —— `{"x":4294967400}`
     * 会变成 104，一击落在谁都没批过的坐标上，而 `offDisplay` 那道屏内上界是在截断
     * **之后**才问的。`NaN`/`Infinity` 同理：JSON 允许裸写它们，截断后分别是 0 与
     * `Int.MAX_VALUE`。
     */
    private fun intArgsError(args: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!args.has(key)) return "missing arg: $key"
            val number = (args.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
                ?: return ArgErrors.notNumber(key, args.opt(key))
            if (number < Int.MIN_VALUE.toDouble() || number > Int.MAX_VALUE.toDouble()) {
                return "$key is out of range (got ${args.opt(key)})"
            }
        }
        return null
    }

    private suspend fun displayTap(args: JSONObject, budget: CallBudget): BackendResult {
        // 参数判据排在取屏之前：没有那块屏时先回「没有屏」，会让写错的坐标永远量不到，
        // 也要人先建一块屏才知道自己少给了一个键。
        intArgsError(args, KEY_X, KEY_Y)?.let { return malformed(it) }
        val display = requireDisplay() ?: return noDisplay()
        val x = args.getInt(KEY_X)
        val y = args.getInt(KEY_Y)
        offDisplay(x, y)?.let { return malformed(it) }
        return runOnDisplay("input -d $display tap $x $y", display, budget)
    }

    private suspend fun displaySwipe(args: JSONObject, budget: CallBudget): BackendResult {
        intArgsError(args, KEY_FROM_X, KEY_FROM_Y, KEY_TO_X, KEY_TO_Y)?.let { return malformed(it) }
        val display = requireDisplay() ?: return noDisplay()
        val fromX = args.getInt(KEY_FROM_X)
        val fromY = args.getInt(KEY_FROM_Y)
        val toX = args.getInt(KEY_TO_X)
        val toY = args.getInt(KEY_TO_Y)
        (offDisplay(fromX, fromY) ?: offDisplay(toX, toY))?.let { return malformed(it) }
        val duration = args.optInt(KEY_DURATION_MS, DEFAULT_SWIPE_MS).coerceIn(50, MAX_SWIPE_MS)
        return runOnDisplay("input -d $display swipe $fromX $fromY $toX $toY $duration", display, budget)
    }

    private suspend fun displayText(args: JSONObject, budget: CallBudget): BackendResult {
        val display = requireDisplay() ?: return noDisplay()
        val text = args.optString(KEY_TEXT)
        // 文本里可以有空格与引号，但不能有换行：`input text` 的换行会被当成命令结束。
        if (text.any { it == '\n' || it == '\r' }) return malformed("$KEY_TEXT must be single line")
        if (text.length > MAX_TEXT_CHARS) return malformed("$KEY_TEXT too long")
        return runOnDisplay("input -d $display text ${shellQuote(text)}", display, budget)
    }

    private suspend fun displayKey(args: JSONObject, budget: CallBudget): BackendResult {
        val display = requireDisplay() ?: return noDisplay()
        val name = args.optString(KEY_KEY_EVENT)
        val code = KEY_EVENTS[name] ?: return malformed(
            "unknown key: $name, accepted: [${KEY_EVENTS.keys.joinToString(", ")}]",
        )
        return runOnDisplay("input -d $display keyevent $code", display, budget)
    }

    /**
     * 取那块屏停在表面的一帧，落成产物。帧经 fd 回来，不落全局可读的路径。
     *
     * 那块屏停止重绘时服务端给不出新帧，此时把上一次成功取到的那一帧再给一次，并在回包里
     * 带 `stale:true` 与 `frameAgeMs` —— "这不是此刻"要由回包自己说出来，不能留给助手猜。
     * 录屏那条通路对同一件事的处置是重复上一帧，这里与它同口径；从没成功取到过帧的屏
     * （或刚换过一块屏）仍然如实回不可达。
     */
    private suspend fun displayCapture(call: BackendCall, budget: CallBudget): BackendResult {
        val display = requireDisplay() ?: return noDisplay()
        // 取帧要落成产物文件，收尾按带产物那档预留；预算装不下就不取帧——
        // 回「帧可能稍后才到手」比悄悄递一张旧帧诚实。
        val waitMs = budget.remoteWaitMs(CAPTURE_WAIT_MS, REMOTE_MARGIN_ARTIFACT_MS)
            ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_ARTIFACT_MS)
        val frames = frameCacheFor(call)
        val fresh = when (val txn = captureBytes(budget.cap, LOSSLESS, LOSSLESS, waitMs)) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> return BackendResult.Failed(
                RelayError.TRANSPORT_TIMEOUT,
                remoteGiveUpReason("display capture", txn.waitedMs, txn.submittedBeforeExpiryMs),
            )
        }
        val bytes: ByteArray
        val ageMs: Long
        if (fresh != null) {
            // 内容与上一帧逐字节相同时不刷新时刻：那块屏在重绘同一张画面，对调用方来说
            // 与"没出新帧"是同一件事 —— 报告要的 `stale` / `frameAgeMs` 说的都是"这里没变过"。
            val cached = frames.peek(display)
            val unchanged = cached != null && cached.first.contentEquals(fresh)
            bytes = fresh
            ageMs = if (unchanged) cached!!.second else 0L
            if (!unchanged) frames.remember(display, fresh)
        } else {
            val cached = frames.peek(display)
                ?: return BackendResult.Failed(
                    RelayError.BACKEND_UNAVAILABLE,
                    "no frame from the trusted display: retry, or re-create it with surface.virtual",
                )
            bytes = cached.first
            ageMs = cached.second
        }
        val staged = reaper.newArtifactFile(call.requestId, ARTIFACT_SUFFIX, CAPTURE_ESTIMATE_BYTES)
            ?: return BackendResult.Failed(RelayError.STORAGE_FULL, "quota exhausted")
        val written = runCatching { staged.writeBytes(bytes) }.isSuccess && staged.length() > 0L
        if (!written) {
            reaper.discard(staged)
            return BackendResult.Failed(RelayError.INTERNAL, "frame copy failed")
        }
        val data = JSONObject().put("display", display).put("bytes", staged.length()).put("stale", ageMs > 0L)
        if (ageMs > 0L) data.put("frameAgeMs", ageMs)
        return publishArtifact(staged, call, data)
    }

    /**
     * 上一帧的缓存。路径取自调用自己带的 [RelayPaths]：宿主私有目录（宿主目录名 + "-state"），
     * 它在产物配额与回收扫描之外，所以这条缓存不会替用户吃掉录屏与截图的额度。
     *
     * 也正因为不归配额管，它的存活期必须跟着屏走：新建（`surface.virtual` create）、
     * 释放（release）、解绑（[disconnect]）三处都清 —— 里面存的是用户屏幕的像素。
     */
    @Volatile
    private var frameCache: LastFrameCache? = null

    /**
     * 丢掉缓存的那一帧。通道每次起页先调一次：进程被强杀时来不及跑 [disconnect]，
     * 那份屏幕像素既不在产物配额之内、也不在任何外部清理白名单里，只能由下一次起页丢。
     */
    fun clearFrameCache() {
        frameCache?.clear()
        frameCache = null
    }

    private fun frameCacheFor(call: BackendCall): LastFrameCache =
        frameCache ?: LastFrameCache(
            java.io.File(call.paths.privateDir, LAST_FRAME_FILE),
        ).also { frameCache = it }

    /**
     * 那块屏此刻的一帧字节。
     *
     * [waitMs] 同时是远端等帧的上限（写进事务参数）与本地的收口基准，本地再多等一个
     * 回执余量；本地先放弃时回 [RemoteTxn.GaveUp]——帧可能稍后才编码完成，调用方
     * 按「结局未知」作答，不能当成「没有帧」。
     *
     * [jpegQuality] 为 0 时无损 PNG；录屏要的是帧数，那里给一个正数并让服务端顺手缩一道。
     * 帧不落全局可读的路径，只走这一次事务带回来的管道 —— 屏上可能正开着任何一家应用的
     * 任何一页内容，落一条别家读得到的路径等于把用户屏幕抄一份放在外面。
     */
    private suspend fun captureBytes(cap: String, jpegQuality: Int, maxWidth: Int, waitMs: Long): RemoteTxn<ByteArray?> {
        val reply = when (val txn = displayTransaction(
            cap,
            ShellProtocol.CODE_DISPLAY_CAPTURE,
            waitMs + REMOTE_SLACK_MS,
            wantDescriptor = true,
        ) {
            // 服务端按 long 读这个参数：写 int 只是凑巧在小端上读出同一个数，
            // 一旦对上补齐字节就是拿一个随机的等待上限去取帧。
            it.writeLong(waitMs)
            it.writeInt(jpegQuality)
            it.writeInt(maxWidth)
        }) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> return txn
        }
        val descriptor = reply.descriptor ?: return RemoteTxn.Done(null)
        return RemoteTxn.Done(
            try {
                runCatching {
                    java.io.FileInputStream(descriptor.fileDescriptor).use { input -> input.readBytes() }
                }.getOrNull()?.takeIf { it.isNotEmpty() }
            } finally {
                runCatching { descriptor.close() }
            },
        )
    }

    /**
     * 后台录屏：一帧一帧从那块屏取回来，自己编成 mp4。
     *
     * 为什么不走 `screenrecord`：它只认物理屏编号，虚拟屏编号递进去回的是
     * "Invalid physical display ID"。为什么不走 `MediaRecorder`：它要一块能持续往 surface
     * 上画的屏，而这块屏的表面在用户服务那个进程里，帧只能一帧一帧递过来。
     *
     * 回包报的是**实际**帧数与实际覆盖的时间，不报目标帧率：一次取帧要走一趟 binder 加一次
     * PNG 编解码，能给到多少由设备说了算。报一个没兑现的 15fps 会让助手按它排后续任务。
     */
    private suspend fun recordOnDisplay(call: BackendCall, budget: CallBudget): BackendResult {
        val display = requireDisplay() ?: return noDisplay()
        val seconds = call.args.optInt(KEY_SECONDS, DEFAULT_RECORD_SECONDS).coerceIn(1, MAX_RECORD_SECONDS)
        // 录像时长也在调用预算之内：预算给不满就不硬录满——信箱的超时会把整条调用
        // 连回包一起收走，录到的文件反而没人拿得到。装不下一次最小往返时一帧都不取。
        val recordMs = budget.remoteWaitMs(seconds * 1000L, REMOTE_MARGIN_MS)
            ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
        val first = when (val txn = captureBytes(budget.cap, RECORD_JPEG_QUALITY, RECORD_MAX_WIDTH, minOf(CAPTURE_WAIT_MS, recordMs))) {
            is RemoteTxn.Done -> decodeFrame(txn.value ?: return noFrame(display))
            is RemoteTxn.GaveUp -> return BackendResult.Failed(
                RelayError.TRANSPORT_TIMEOUT,
                remoteGiveUpReason("display capture", txn.waitedMs, txn.submittedBeforeExpiryMs),
            )
        }
        if (first == null) return noFrame(display)
        // 编码尺寸按**实际拿到的那一帧**定：服务端缩出来的宽高比与本地推算的屏比例不必完全一致，
        // 而编码器要求偶数边长，尺寸对不上就是一幅斜掉的画面 —— 它不会报任何错。
        val width = first.width.coerceAtLeast(MIN_ENCODE_PX) and 1.inv()
        val height = first.height.coerceAtLeast(MIN_ENCODE_PX) and 1.inv()
        val staged = reaper.newArtifactFile(call.requestId, RECORD_SUFFIX, estimateRecordBytes(seconds))
            ?: run {
                first.recycle()
                return BackendResult.Failed(RelayError.STORAGE_FULL, "quota exhausted")
            }
        val encoder = FrameEncoder(width, height, RECORD_FPS, RECORD_BITS_PER_SECOND)
        if (!encoder.start(staged)) {
            first.recycle()
            reaper.discard(staged)
            return BackendResult.Failed(
                RelayError.BACKEND_UNAVAILABLE,
                // 说的是"没有能接我们这套像素格式的编码器"，不是"这台设备没有 h264"：
                // 前者换一台编码器就有，后者才是设备边界。
                "no NV12 h264 encoder for ${width}x$height on this device",
            )
        }
        val startedAt = monotonicNow()
        val deadlineAt = startedAt + recordMs
        var frames = 0
        var misses = 0
        var written = 0L
        // 屏不刷新就没有新帧可取，而录像要的是**每个时间点上的画面**：静止的屏幕在视频里
        // 就是把上一帧再放一遍。所以取不到新帧时复用上一帧，而不是让整条循环卡在等待上
        // （屏静止时等满预算是八秒只出一帧）。
        var last: android.graphics.Bitmap? = first
        try {
            while (true) {
                val frameAt = monotonicNow()
                if (frameAt >= deadlineAt) break
                // 每一帧都从录像窗口的剩余时间里出：窗口用完就收工，不向预算之外借时间。
                val frameWait = (deadlineAt - frameAt).coerceAtMost(RECORD_FRAME_WAIT_MS)
                val fresh = when (val txn = captureBytes(budget.cap, RECORD_JPEG_QUALITY, RECORD_MAX_WIDTH, frameWait)) {
                    is RemoteTxn.Done -> txn.value?.let { encoded ->
                        val grabbedAt = monotonicNow()
                        decodeFrame(encoded)?.let { it to grabbedAt }
                    }
                    is RemoteTxn.GaveUp -> null
                }
                val grabbedAt = fresh?.second ?: frameAt
                val bitmap = fresh?.first ?: last
                if (bitmap == null) {
                    // 到现在一帧都没有：屏多半已经不在了。
                    misses++
                    if (misses >= MAX_FRAME_MISSES) break
                } else {
                    misses = 0
                    if (encoder.push(bitmap, (grabbedAt - startedAt) * 1000)) frames++
                    if (fresh != null) {
                        last?.recycle()
                        last = fresh.first
                    }
                }
                val pace = frameAt + 1000L / RECORD_FPS - monotonicNow()
                if (pace > 0) delay(pace)
            }
        } finally {
            // finish 任何分支都要走到：容器没结掉的话，盘上那个文件是一段读不出时长的空壳。
            written = encoder.finish()
            runCatching { last?.recycle() }
        }
        if (frames == 0 || written <= 0L) {
            reaper.discard(staged)
            return noFrame(display)
        }
        val elapsedMs = (monotonicNow() - startedAt).coerceAtLeast(1L)
        return publishArtifact(
            staged,
            call,
            JSONObject()
                .put("display", display)
                .put("seconds", seconds)
                // 实际录到的时长单独报：请求被预算夹短时，它就与 seconds 说的不是同一件事。
                .put("recordedMs", elapsedMs)
                .put("frames", frames)
                .put("actualFps", kotlin.math.round(frames * 1000.0 / elapsedMs * 100) / 100.0)
                .put("width", width)
                .put("height", height)
                .put("bytes", written),
        )
    }

    /** 屏在、帧取不到时的同一条结论：先按屏没了处理，重试与重建屏是这里仅有的两条后续。 */
    private fun noFrame(display: Int) = BackendResult.Failed(
        RelayError.BACKEND_UNAVAILABLE,
        "no frame from the trusted display (display=$display): re-create it with surface.virtual",
    )

    private fun decodeFrame(bytes: ByteArray): android.graphics.Bitmap? = runCatching {
        android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply {
                // 硬件位图进不了编码器：它的像素在 GPU 侧，getPixels 会直接抛。
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            },
        )
    }.getOrNull()

    /** 产物落位并换成沙盒侧地址；失败时暂存件由这里收掉，不留半件。 */
    private fun publishArtifact(staged: java.io.File, call: BackendCall, data: JSONObject): BackendResult {
        val bytes = staged.length()
        val artifact = reaper.publishArtifact(staged, call.requestId)
            ?: return BackendResult.Failed(RelayError.STORAGE_FULL, "artifact publish failed")
        val guestPath = interlock.relay.core.storage.RelayPaths.toGuestPath(artifact, call.paths)
            ?: return BackendResult.Failed(RelayError.INTERNAL, "artifact path unmappable").also {
                reaper.discard(artifact)
            }
        return BackendResult.Ok(
            data = data.put("bytes", bytes),
            artifacts = listOf(guestPath),
            artifactBytes = bytes,
        )
    }

    private fun requireDisplay(): Int? = displayId.takeIf { it >= 0 }

    /** 暂存位按码率与时长估：宁可估高，估低了会先扣小额度再补，产物可能半路被自己的额度挡下。 */
    private fun estimateRecordBytes(seconds: Int): Long =
        seconds.toLong() * RECORD_BITS_PER_SECOND / 8L

    /** 屏不见了：这条通路没有落点，回可重试的不可达，让助手重新 `surface.virtual`。 */
    private fun noDisplay() = BackendResult.Failed(
        RelayError.BACKEND_UNAVAILABLE,
        "no trusted display: call surface.virtual with {\"action\":\"create\"} first",
    )

    private fun displayData(state: String): JSONObject {
        val size = displaySize
        return JSONObject()
            .put("state", state)
            // 同一个编号给两个名字是有意为之：`display` 是这条能力一直以来的字段，而能力清单与
            // 我们自己的报错文案（ArgErrors 那句"去读 backend.shizuku.trustedDisplay"）教
            // 助手读 `displayId`。只留一个，另一边的脚本就会读到 null 并以为屏没了。
            .put("display", displayId)
            .put("displayId", displayId)
            .put("width", size.first)
            .put("height", size.second)
    }

    /** 与服务端对一次账（生命周期探测路径，无调用预算可扣）：见 [refreshDisplay]。 */
    suspend fun refreshDisplay(): Boolean = refreshDisplay(budget = null)

    /**
     * 与服务端对一次账：屏可能在服务端那侧被收掉，本地缓存不能一直算数。
     *
     * 带 [budget]（调用链上）按预算给等待，装不下一次最小往返就不发这条对账、缓存
     * 维持原样并回 false——接下来那次真正的远端操作会按同一份预算给出它的答复。
     * 不带预算（生命周期探测）用固定上限；本地放弃等待时同样不动缓存——「没等到」
     * 与「屏没了」是两个结论，只有服务端回话才能下后一个。
     * 回 true 表示拿到了服务端的新账（或确定没有远端可问）。
     */
    internal suspend fun refreshDisplay(budget: CallBudget?): Boolean {
        if (binder == null) {
            // 用户服务都没连上，那块屏就不可能还归它持有。缓存必须跟着清 ——
            // 否则 `trustedDisplayReady()` 会一直为真，界面据此把助手页抢到前台去问确认框，
            // 而能力清单上 `trustedDisplay.alive` 也在替一块不存在的屏说话。
            displayId = -1
            displaySize = 0 to 0
            return true
        }
        val waitMs = when (budget) {
            null -> DISPLAY_TXN_WAIT_MS
            else -> budget.remoteWaitMs(DISPLAY_TXN_WAIT_MS, REMOTE_MARGIN_MS) ?: return false
        }
        val reply = when (val txn = displayTransaction(budget?.cap ?: CAP_LIFECYCLE, ShellProtocol.CODE_DISPLAY_QUERY, waitMs)) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> return false
        }
        displayId = reply.code
        if (reply.code < 0) displaySize = 0 to 0
        else DISPLAY_SIZE.find(reply.reason)?.let {
            displaySize = it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        return true
    }

    @Volatile
    private var displaySize: Pair<Int, Int> = 0 to 0

    private data class DisplayReply(val code: Int, val reason: String, val descriptor: android.os.ParcelFileDescriptor?)

    /** 显示事务统一走受限执行器；本地等待时长由调用方按预算算好传入。 */
    private suspend fun displayTransaction(
        cap: String,
        code: Int,
        waitMs: Long,
        wantDescriptor: Boolean = false,
        write: (Parcel) -> Unit = {},
    ): RemoteTxn<DisplayReply> = transactBounded(cap, "display", waitMs) {
        rawTransactDisplay(code, wantDescriptor, write)
    }

    /**
     * 显示事务的裸收发：在受限执行器线程上跑，不读也不改本地缓存。
     * 回负 code 与原因表示服务端没答上，与「服务端答了、答案是负」分开。
     */
    private fun rawTransactDisplay(
        code: Int,
        wantDescriptor: Boolean = false,
        write: (Parcel) -> Unit = {},
    ): DisplayReply {
        val target = binder ?: return DisplayReply(-1, "shizuku service binder unavailable", null)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            write(data)
            if (!target.transact(code, data, reply, 0)) {
                DisplayReply(-1, "service did not answer", null)
            } else {
                val status = reply.readInt()
                val reason = reply.readString() ?: ""
                val descriptor = if (wantDescriptor && status == 0) reply.readFileDescriptor() else null
                DisplayReply(status, reason, descriptor)
            }
        } catch (dead: RemoteException) {
            DisplayReply(-1, "service dead", null)
        } catch (t: Throwable) {
            DisplayReply(-1, t.javaClass.simpleName, null)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * 递一条落在那块屏上的命令。这里顺手做一件别处做不了的事：
     * 屏可能已被服务端按空闲收掉，而 `input -d <不存在的编号>` 只回一个非零退出码，
     * 会被 [run] 报成 `E_GATE_SYSTEM_MISSING`（不可重试、退出码 4 = 用户得去动手机）——
     * 真相是再建一次屏就能继续。所以失败先对一次账，屏确实没了就换成可重试的那条码。
     */
    private suspend fun runOnDisplay(command: String, display: Int, budget: CallBudget): BackendResult {
        val outcome = run(command, budget)
        if (outcome is BackendResult.Ok) {
            outcome.data.put("display", display)
            return outcome
        }
        refreshDisplay(budget)
        if (!displayAlive()) return noDisplay()
        return outcome
    }

    /**
     * 坐标越出那块屏的像素边框时回一句话，否则回 null。
     *
     * 无障碍那条通路早就按屏幕边界拒越界坐标了：`input` 对越界坐标不回错，系统只是
     * 什么都不做，于是响应写着 ok 而画面零变化。两侧必须同一判据，否则同一份脚本
     * 换一块屏就跑成"成功而什么都没做"。
     * 尺寸没报上来（0）时不拦——宁可不验，也不要用一个错的尺寸去拒合法坐标。
     */
    private fun offDisplay(x: Int, y: Int): String? {
        val (width, height) = displaySize
        if (width <= 0 || height <= 0) return null
        return if (x < 0 || x >= width || y < 0 || y >= height) {
            "target ($x,$y) is outside the trusted display ${width}x$height"
        } else {
            null
        }
    }


    /**
     * 「改完就多了一个能以系统身份操作本机的入口」这一类键。
     *
     * 精确名单挡不住这一类：安全设置的键名由各厂商 ROM 自行增补，逐条列举必然漏项。
     * 因此按名称片段整类封死，再补几条不含片段但同样交出
     * 钩子的键。宁可多挡几个无害键，也不能让一次确认替所有闸门背书。
     */
    private fun isPrivilegeHandoffKey(key: String): Boolean {
        val lower = key.lowercase()
        return lower in BLOCKED_SETTINGS_KEYS || PRIVILEGE_KEY_FRAGMENTS.any { lower.contains(it) }
    }

    /** 单引号包裹并转义内部单引号，杜绝参数值被解释成命令。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        /**
         * 生命周期动作（解绑前的收屏、周期探测的对账）没有所属能力时，`cap` 记这个短名。
         * 留一个可辨认的固定值好过留空：空字段在日志里读起来像「没记」，而这几条等待
         * 恰恰是最容易让人以为「卡住了」的那几条。
         */
        internal const val CAP_LIFECYCLE = "lifecycle"

        /**
         * REMOTE_SUBMITTED / REMOTE_GIVE_UP 的字段名，与两个事件模板的占位符逐个相等。
         * 模板是冻结的，REMOTE_GIVE_UP 的占位符写作 `%{ago}`，故这里的键也必须是 `ago`
         * ——按字面意思写成 `submittedAgoMs` 只会得到一段 `unresolved=`。
         */
        internal val SUBMITTED_FIELDS = setOf("cap", "waitMs")
        internal val GIVE_UP_FIELDS = setOf("cap", "ago")

        /**
         * 本后端承担的能力各自的键表。null 表示不由本后端实现，交给分发处回
         * `E_NOT_IMPLEMENTED`。
         *
         * 一条能力一处判据，而不是各能力自己挑键：没挑中的键会被静默丢掉，助手却以为它生效了，
         * 于是同一次参数拼错在 direct 通路上报「unknown arg」、在 shell 通路上跑成另一件事。
         * 沙盒侧说明书写的是「一律拒」，两后端就得共用同一形状与同一组错误词汇。
         * 表以 internal 暴露：分发前后的两张键表必须逐格对齐，对齐判据要能跨层引用同一份。
         */
        internal val ARG_SPECS: Map<CapabilityId, ArgSpec> = mapOf(
            CapabilityId.SECURE_SETTINGS to
                ArgSpec(setOf(KEY_SETTING, KEY_VALUE, KEY_NAMESPACE), setOf(KEY_SETTING, KEY_VALUE)),
            // 动词必填；args 是数组，形状与取值全由 ShellVerbs 判 —— 顶层键表只管"有没有多余的键"。
            CapabilityId.SYS_SHELL to ArgSpec(setOf(ShellVerbs.KEY_VERB, ShellVerbs.KEY_ARGS), setOf(ShellVerbs.KEY_VERB)),
            CapabilityId.APPOPS_SET to ArgSpec(setOf(KEY_PACKAGE, KEY_OP, KEY_MODE), setOf(KEY_PACKAGE, KEY_OP, KEY_MODE)),
            CapabilityId.APP_STOP to ArgSpec(setOf(KEY_PACKAGE), setOf(KEY_PACKAGE)),
            CapabilityId.PKG_INSTALL to ArgSpec(setOf(KEY_UPLOAD), setOf(KEY_UPLOAD)),
            // 建屏的 action 可省，省下来就是 create：`surface.virtual {}` 要能直接回屏号。
            // 把 action 做成必填会让无参调用吃一条 E_TRANSPORT_MALFORMED，
            // 而它没有任何写错的键名可改。
            CapabilityId.SURFACE_VIRTUAL to ArgSpec(setOf(KEY_ACTION, KEY_WIDTH, KEY_HEIGHT, KEY_DPI), emptySet()),

            CapabilityId.APP_LAUNCH to ArgSpec(setOf(KEY_PACKAGE), setOf(KEY_PACKAGE)),
            CapabilityId.UI_TEXT to ArgSpec(setOf(KEY_TEXT), setOf(KEY_TEXT)),
            CapabilityId.UI_KEY to ArgSpec(setOf(KEY_KEY_EVENT), setOf(KEY_KEY_EVENT)),
            CapabilityId.SCREEN_CAPTURE to ArgSpec(),
            // 与 direct 通路那条 `screen.record` 共用同一组键：同一能力两条通路给两种形状，
            // 助手就要为落点各写一份脚本。
            CapabilityId.SCREEN_RECORD to ArgSpec(setOf(KEY_SECONDS)),

            // 点击与滑动是两条能力、两组键：混用一律以 unknown key 被拒，
            // 而不是像从前那样把滑动键静默丢掉、跑成一次点击。
            CapabilityId.UI_TAP to ArgSpec(setOf(KEY_X, KEY_Y)),
            CapabilityId.UI_SWIPE to
                ArgSpec(setOf(KEY_FROM_X, KEY_FROM_Y, KEY_TO_X, KEY_TO_Y, KEY_DURATION_MS)),
        )

        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_.]{0,127}$")
        private val KEY_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")
        private const val MAX_VALUE_CHARS = 4096
        private const val MAX_ERROR_CHARS = 200
        /** `sh -c` 找不着命令时的约定退出码。 */
        private const val EXIT_COMMAND_NOT_FOUND = 127
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 15_000L

        /**
         * 远端等待的预算分配，本后端所有远端等待点共用的唯一判据。
         *
         * 返回本地愿意等的时长：取 `min(defaultMs, budgetMs - marginMs)`——defaultMs 是
         * 这类调用的常规上限，budgetMs 是调用预算扣掉此前已花部分的余额。marginMs 是
         * 收尾预留：远端回执之后本端还要拼响应、写产物、更新状态记录，这些都花在
         * 同一条预算里；不加预留，顶格调用必然是远端刚回话、预算就见了底。信箱侧已经
         * 为「写响应文件」留过一次余量，这里管的是执行内的另一段。
         *
         * 返回 null 表示扣掉预留后连一次 [MIN_REMOTE_ROUNDTRIP_MS] 的最小往返都装不下：
         * 此刻发远端只会把「还没发出去」伪装成「远端超时」，所以不发，直接按超时作答。
         */
        internal fun remoteWaitMs(defaultMs: Long, budgetMs: Long, marginMs: Long): Long? {
            val available = budgetMs - marginMs
            if (available < MIN_REMOTE_ROUNDTRIP_MS) return null
            return minOf(defaultMs, available)
        }

        /** 是否允许发起一次远端调用：装不下最小往返就是不允许（见 [remoteWaitMs]）。 */
        internal fun canIssueRemote(defaultMs: Long, budgetMs: Long, marginMs: Long): Boolean =
            remoteWaitMs(defaultMs, budgetMs, marginMs) != null

        /**
         * 本地放弃等待后的标准回答。两个事实必须都在场：事务没有被取消，可能仍在执行并
         * 最终完成；因此这条超时**不得**被读成「没有执行」。确已递交的带上递交时刻离
         * 本地期限还有多久；还在本地队列的说明它之后仍可能被递交执行。
         */
        internal fun remoteGiveUpReason(kind: String, waitedMs: Long, submittedBeforeExpiryMs: Long?): String {
            val base = "local wait for the $kind transaction gave up after ${waitedMs}ms; the transaction " +
                "was not cancelled and its outcome is unknown - do not treat this as not executed"
            return if (submittedBeforeExpiryMs != null) {
                "$base (it was submitted ${submittedBeforeExpiryMs}ms before the local budget expired " +
                    "and may still complete)"
            } else {
                "$base (it was still queued locally when the wait expired and may still be submitted " +
                    "and run later)"
            }
        }

        /** 一次最小远端往返（递交 + 回执）至少要留的时长；扣掉收尾预留后低于它就不发远端。 */
        internal const val MIN_REMOTE_ROUNDTRIP_MS = 300L

        /** 普通命令与安装的收尾预留：回执之后还要拼响应、更新状态记录。 */
        internal const val REMOTE_MARGIN_MS = 500L

        /** 带产物调用的收尾预留：帧要落成产物文件，比拼一段 JSON 重一个量级。 */
        internal const val REMOTE_MARGIN_ARTIFACT_MS = 2_000L

        /**
         * 远端自己的超时与本地等待之间留的差：回执从服务端进程回到本进程也要一点时间。
         * 只在「远端超时可调」的事务上用（命令、安装、取帧）——让远端先到点，本地
         * 通常等得到回执，不必动用「放弃等待」。
         */
        internal const val REMOTE_SLACK_MS = 200L

        /**
         * 建屏、收屏与对账三条显示事务的本地等待上限。它们通常毫秒级就能回话，
         * 上限只为服务端卡死时兜底；不带调用预算的路径（生命周期探测、解绑收屏）
         * 也用它当上限。
         */
        private const val DISPLAY_TXN_WAIT_MS = 3_000L

        /**
         * 真屏截图的本地等待上限：服务端 `screencap` 自带 5 秒上限（写死在用户服务里，
         * 本端改不到），这里再多给一个回执余量；预算不够时本地先放弃，结局按未知作答。
         */
        private const val SCREENSHOT_WAIT_MS = 5_200L

        /**
         * `sys.settings.write` 只写 secure 这一层，但同一个键名可能已经在别的层里有值
         * （屏幕亮度、音量都在 system）。那种情况下这里改出来的是一条没人读的孤立键，
         * 写是写成功了、生效值一点没动，所以要把可疑点说出去。
         */
        /** `settings` 的三层。写哪层由 `namespace` 决定，缺省仍是 secure。 */
        private val SETTINGS_NAMESPACES = listOf("secure", "system", "global")
        private const val DEFAULT_NAMESPACE = "secure"

        /** 用户眼前那块屏的编号，写死 0：本模块的手势与节点通路都只认它。 */
        private const val DEFAULT_DISPLAY = 0
        private const val SETTLE_WAIT_MS = 200L

        /**
         * 观察落点的总时间窗上限。冷启动的 resume 常常超过它，超了就如实回"没看到"——
         * 通道同一时刻只跑一条请求，把等待算到这条调用头上会让无关调用一起排队。
         */
        private const val LANDING_WAIT_MS = 1_200L
        /** 纠正那一次之后再给多长的落地观察窗口。 */
        private const val RETRY_LANDING_WAIT_MS = 1_500L

        /** 只取两类行：每个显示的段首，与该段的顶层活动。整棵树的 dumpsys 不该过 binder。 */
        private const val TOP_PACKAGE_COMMAND =
            "dumpsys activity activities | grep -aE 'Display #[0-9]+ \\(|topResumedActivity='"

        /** 段首之后那一行的顶层活动：`u0 包名/组件`，只取包名。 */
        private val DISPLAY_SECTION = Regex("^Display #(\\d+) \\(")

        private val TOP_ACTIVITY = Regex("^topResumedActivity=ActivityRecord\\{\\S+ u\\d+ ([A-Za-z][A-Za-z0-9_.]*)/")

        /**
         * 安装要解包、优化 dex，比一般命令慢一个量级。这是发给远端的上限，
         * 实际给到多少由调用预算夹紧（见 [remoteWaitMs]）。
         */
        private const val INSTALL_TIMEOUT_MS = 120_000L

        /** 递交 apk 的参数名。只接受单个上传名，不接受路径。 */
        private const val KEY_UPLOAD = "upload"

        /** 键表与各能力读取处共用同一批字面量，两处拼写不可能各走各的。 */
        private const val KEY_SETTING = "key"
        private const val KEY_VALUE = "value"
        private const val KEY_NAMESPACE = "namespace"
        private const val KEY_PACKAGE = "package"
        private const val KEY_OP = "op"
        private const val KEY_MODE = "mode"

        // ── 可信屏那五条通路的参数名。与 direct / a11y 两侧同名同义：助手换通路时不该换写法。
        private const val KEY_ACTION = "action"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_DPI = "dpi"
        private const val KEY_TEXT = "text"
        private const val KEY_KEY_EVENT = "key"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_FROM_X = "fromX"
        private const val KEY_FROM_Y = "fromY"
        private const val KEY_TO_X = "toX"
        private const val KEY_TO_Y = "toY"
        private const val KEY_DURATION_MS = "durationMs"

        private const val ACTION_CREATE = "create"
        private const val ACTION_RELEASE = "release"
        private const val ACTION_QUERY = "query"

        /** 掉线重绑的最小间隔，见 [connection]。 */
        private const val REBIND_COOLDOWN_MS = 5_000L

        /** 缺省尺寸：这块屏要装得下真屏上的应用，也要在 `input` 定向到它时换算得开。 */
        private const val DEFAULT_WIDTH = 1080
        private const val DEFAULT_HEIGHT = 1920
        private const val DEFAULT_DPI = 420
        private const val DEFAULT_SWIPE_MS = 300
        private const val MAX_SWIPE_MS = 3_000
        private const val MAX_TEXT_CHARS = 500
        private const val LAUNCH_TIMEOUT_MS = 15_000L
        private const val CAPTURE_WAIT_MS = 3_000L
        private const val CAPTURE_ESTIMATE_BYTES = 12L * 1024 * 1024
        private const val ARTIFACT_SUFFIX = "png"
        /** 可信屏静置时再给一次的那一帧，落在宿主私有目录里。 */
        private const val LAST_FRAME_FILE = "last-frame.png"

        /** 后台录屏的一组参数。目标帧率是上限，实际给到多少由回包里的 actualFps 说。 */
        private const val KEY_SECONDS = "seconds"
        private const val RECORD_SUFFIX = "mp4"
        private const val DEFAULT_RECORD_SECONDS = 5
        private const val MAX_RECORD_SECONDS = 30
        private const val RECORD_FPS = 8
        private const val RECORD_BITS_PER_SECOND = 4_000_000

        /** 半分辨率再往下就没有可读的画面了：录出来的东西要给助手看得清字。 */
        private const val MIN_ENCODE_PX = 320

        /** 连着这么多帧取不到就当屏没了，收掉已经录到的部分。 */
        private const val MAX_FRAME_MISSES = 3

        /** 0 表示"按无损给"：截图那条要的就是这个，录屏那条不要。 */
        private const val LOSSLESS = 0

        /** 录屏一帧的代价是一次 binder 往返加一次压缩，所以帧要给 JPEG 并先缩到这一道宽。 */
        private const val RECORD_JPEG_QUALITY = 60
        private const val RECORD_MAX_WIDTH = 720

        /**
         * 录屏时每帧愿意等多久。
         *
         * 截图那三秒是为了「总要拿到一张画面」；录屏不能照搬：屏静止时等满预算会把整条
         * 取帧循环钉在等待上（八秒只出一帧），这里只等一小段，取不到就复用上一帧。
         */
        private const val RECORD_FRAME_WAIT_MS = 250L

        /** 与无障碍通路同一组按键语义：`input keyevent` 的编号在两侧不能各猜一份。 */
        private val KEY_EVENTS = mapOf(
            "back" to 4,
            "home" to 3,
            "recents" to 187,
            "enter" to 66,
        )

        private val DISPLAY_SIZE = Regex("(\\d+)x(\\d+)")

        /** apk 体积上限：比媒体中转的 32 MiB 宽，但仍是有界的——不设上限等于给填满磁盘留口子。 */
        private const val MAX_INSTALL_BYTES = 128L * 1024L * 1024L

        /** 允许改动的应用操作白名单，避免开放任意 appops 写入。 */
        /** 从 `appops get` 那段输出里挑模式名；只认这六个平台模式词，认不出就不给这个字段。 */
        private val APPOPS_MODE = Regex("[A-Za-z_]+:\\s*(allow|deny|ignore|default|foreground|background)\\b")

        val ALLOWED_OPS = setOf(
            "android:system_alert_window",
            "android:camera",
            "android:record_audio",
            "android:location",
            "android:post_notification",
        )
        val ALLOWED_MODES = setOf("allow", "deny", "ignore", "default")

        /**
         * 交出系统级钩子的键名片段，整类封死。含无障碍、输入法、通知与辅助功能监听、
         * 设备管理、伴侣设备、自动填充、打印、ADB 调试与安装来源校验。
         */
        val PRIVILEGE_KEY_FRAGMENTS = setOf(
            "accessibility",
            "input_method",
            "ime_",
            "_ime",
            "listener",
            "device_admin",
            "companion",
            "autofill",
            "print_",
            "adb",
            "verify",
            "install_",
            "_install",
            "vpn",
            "mock_location",
        )

        /** 不含上述片段、但同样交出控制权或正是闸门判据来源的键。 */
        val BLOCKED_SETTINGS_KEYS = setOf(
            "default_input_method",
            "enabled_input_methods",
            "show_ime_with_hard_keyboard",
            "enabled_accessibility_services",
            "accessibility_enabled",
            "accessibility_button_targets",
            "accessibility_shortcut_target_service",
            "enabled_notification_listeners",
            "enabled_print_services",
            "sms_selected_app_package_name",
            "role_sms",
            "role_dialer",
            "development_settings_enabled",
            "bluetooth_on",
            "wifi_on",
            "nfc_on",
        )
    }
}
