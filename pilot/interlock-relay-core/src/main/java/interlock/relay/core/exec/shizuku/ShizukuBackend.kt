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
import interlock.relay.core.exec.namesHostPackage
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.exec.FrameEncoder
import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.exec.shizuku.RelayShellService.CommandTxn
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
    /**
     * 「受保护应用」名单的实时读数，由装配根递进来。与调用入口形成双保险：
     * 真正下发 `am start`/`am force-stop`/`appops`/`pm install`/`input`/取帧之前再查一次。
     * 完全 Shizuku 的 `sys.shell`/`sys.shell.full` 不做任何命令解析，原样放行。
     * 名单为空时零判定零查询。
     */
    private val protectedPackages: () -> Set<String> = { emptySet() },
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

    /**
     * 建屏被系统拒绝时说给装配根一声：能力清单要立刻把「不该再建屏」写出去，
     * 不能等下一分钟的巡检才把这份事实落到助手读的那份文件里。任意线程触发。
     */
    @Volatile
    var deniedListener: (() -> Unit)? = null

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

    /**
     * 建屏尺寸的三档处置（[Companion.safeDisplaySize] 的结果）。
     * 拆成类型而不是返回 null 与布尔，三路调用点各自方向明确，不容混淆。
     */
    internal sealed class SafeDisplaySize {
        /** 物理两侧都在可建区间（或读不到）：沿用请求/缺省，行为与判据引入前一致。 */
        object KEEP_EXISTING : SafeDisplaySize()

        /** 长边越界、短边合建：按给出的数值建屏，覆盖请求里的宽高。 */
        data class Override(val width: Int, val height: Int) : SafeDisplaySize()

        /** 物理两侧都越界：这台机器给不出安全尺寸，建屏不出本地，直接报。 */
        object NONE_SAFE : SafeDisplaySize()
    }

    /**
     * release 回包的判据三档（[Companion.releaseInputState] 的返回值）。
     * RESTORED_FALSE 是唯一"可确认"档（布尔 false：收屏失败，屏还在）；
     * UNCONFIRMED_ACKNOWLEDGED（服务端答话 0/1）与 UNCONFIRMED_GAVE_UP（本地放弃等待）
     * 都落字符串 "unknown"，两支的回包形状不同——前者成功+按回复分档的 note，
     * 后者 E_TRANSPORT_TIMEOUT+放弃原因——判据只分档，回包形状由 releaseOutcome 出。
     * 判据表里没有 true 这一档——没有任何出口会宣称"已恢复"。
     */
    internal enum class ReleaseInputState { RESTORED_FALSE, UNCONFIRMED_ACKNOWLEDGED, UNCONFIRMED_GAVE_UP }

    fun connect() {
        deliberatelyUnbound = false
        if (binder == null) state.bindUserService(connection)
    }

    fun disconnect() {
        deliberatelyUnbound = true
        // 流会话随通道收摊：用户服务一旦解绑，那头的编码器与 pipe 写端就没了归宿。
        // 这里没有调用预算可扣，给固定上限；等不到就放弃——远端在宿主进程死亡或
        // 读端关闭后自会沿 pipe 写失败退出（见 StreamSession 的退出路径）。
        if (streamSessionLikely) {
            transactBoundedBlocking(CAP_STREAM, "stream stop", DISPLAY_TXN_WAIT_MS) {
                rawTransactDisplay(ShellProtocol.CODE_STREAM_STOP)
            }
            streamSessionLikely = false
        }
        // 收屏排在解绑之前：那块屏上可能正跑着别人的应用，通道停了却留着它，
        // 等于替用户留一个「还在被后台操作」的现场。这里没有调用预算可扣，
        // 给固定上限；等不到就放弃，远端那半个事务自己收尾，解绑照常进行。
        //
        // unsafe 在场时**不能**按本地 `displayAlive()` 分流——本地屏号
        // 在 release 失败后已经归零，而服务端可能仍持有那块残留屏；按本地判会跳过 release
        // 直接清标记。只要 unsafe 或本地屏还在，就发一次显式 release，只有回执可确认
        // （0/1，[releaseClearsUnsafe]：服务端确实不持有任何屏）才清 unsafe；失败/放弃等待
        // 保留标记，等下一次 create 的 release→create 或下一次收摊再处理。两者都为假时
        // 本来就没有可清的场景。
        if (trustedDisplayUnsafe || displayAlive()) {
            val released = transactBoundedBlocking(CAP_LIFECYCLE, "display", DISPLAY_TXN_WAIT_MS) {
                rawTransactDisplay(ShellProtocol.CODE_DISPLAY_RELEASE)
            }
            if (released is RemoteTxn.Done && releaseClearsUnsafe(released.value.code) && trustedDisplayUnsafe) {
                trustedDisplayUnsafe = false
            }
        }
        // 解绑无论 release 成败都照常进行（不再因收屏失败而拒绝解绑）。
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

    /**
     * 可信屏此刻的顶层包名，给落点沿用的"目标还在不在最前面"一问用。
     *
     * 预算给小值：这一问只是核对，不该挤占调用的预算；读不到（屏没了、shell 断了、
     * dumpsys 没答）如实回 null —— null 表示"核验不了"，调用方据此不沿用旧落点。
     */
    suspend fun trustedTopPackage(): String? {
        if (!displayAlive()) return null
        val display = requireDisplay() ?: return null
        return topPackages(CallBudget(TRUSTED_TOP_CHECK_BUDGET_MS, "landing-check"))?.get(display)
    }

    /**
     * 可信屏顶层包名的**阻塞**读数（数据面守卫）：流看门狗是普通
     * 线程、没有协程上下文，流期间的复查走这一版。读不到（屏没了、服务没答上、本地
     * 放弃等待）如实回 null——null 由调用方按 fail-closed 处置，不当成"不在名单里"。
     */
    fun trustedTopPackageBlocking(): String? {
        if (!displayAlive()) return null
        val display = displayId
        val target = binder ?: return null
        val outcome = transactBoundedBlocking<RelayShellService.CommandTxn>(
            CAP_STREAM, "top-check", DISPLAY_TXN_WAIT_MS,
        ) {
            transact(target, TOP_PACKAGE_COMMAND, TRUSTED_TOP_CHECK_BUDGET_MS)
        }
        val dump = (outcome as? RemoteTxn.Done)
            ?.value?.let { it as? RelayShellService.CommandTxn.Replied }
            ?.outcome
            ?.takeIf { it.success }
            ?.stdout
            ?.trim()
            .orEmpty()
        return topPackageFromDump(dump, display)
    }

    /**
     * 建屏请求被系统拒绝过的标记（`trusted` 建屏在个别 ROM 上会被整个拒回去）。
     *
     * 这不是「连不上」那样的瞬时状态：系统说不行就是这一台机器上的固定结论，
     * 助手按能力清单办事时必须先看到它，否则每条后台能力都会把「再建一次屏」
     * 当成下一步，反复撞同一堵墙。
     *
     * 清除只有两个时刻：一次建屏真的成功（换个会话、换个 ROM 状态由用户操作带来），
     * 或用户服务重连后对账确认屏活着——那说明原有的拒绝不再是当前事实。
     */
    @Volatile
    var trustedDisplayDenied: Boolean = false
        private set

    /**
     * 「这台设备建可信屏会破坏主屏」的运行时自检结论。
     *
     * 与 [trustedDisplayDenied] 分开：denied 是系统当场拒绝建屏（屏没建成），unsafe 是
     * 屏**建成了**、但建屏后的主屏自检观察到破坏（launcher 被搬走 / 主屏 topTask 变了 /
     * 主屏焦点被清空 / IME 被拽到主屏）——服务端已当场释放那块屏。
     *
     * unsafe **只封自动后台路由，不封显式恢复**：它挡的是「替用户自动建屏/继续用坏屏」
     * （[supportedOnTrusted] 对五条后台能力回 false，装配根的 ready 判据也把它算进去），
     * 而 `surface.virtual` 的 create/release/query 始终可达——create 是复检与解除的通路，
     * release 是清残留屏的通路。解除点有四个：一次建屏+自检完整通过、release 成功
     * （[releaseClearsUnsafe]，且 disconnect 也是同一判据——**仅当收屏回执可确认
     * （0/1）才清**，收屏失败/放弃等待一律保留标记）、宿主进程重启
     * （标记跟着本实例走）。
     */
    @Volatile
    var trustedDisplayUnsafe: Boolean = false
        private set

    /** unsafe 标记变化时说给装配根一声：能力清单立即重铺。任意线程触发。 */
    @Volatile
    var unsafeListener: (() -> Unit)? = null

    override fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean = when (capability) {
        CapabilityId.SECURE_SETTINGS,
        CapabilityId.APPOPS_SET,
        CapabilityId.APP_STOP,
        CapabilityId.SYS_SHELL,
        // 任意命令那条特权通路同样由这条用户服务承担：执行身份跟着 Shizuku 走。
        CapabilityId.SYS_SHELL_FULL,
        // 安装的文件只认沙盒递交进中转区（`RelayPaths.uploadsDir`，它就在绑定子树**之内**）
        // 的那一个名字：文件名过白名单、逐级过链接检查，路径由本端生成，内容先按 apk
        // 解析过再交给 pm。助手既点不了任意绝对路径，也换不掉核验后的那一个文件。
        CapabilityId.PKG_INSTALL,
        -> true

        // trusted 屏上的落点：建/收/问与五条后台操作共用同一张真值表（[trustedSurfaceSupported]）。
        CapabilityId.SURFACE_VIRTUAL,
        CapabilityId.APP_LAUNCH,
        CapabilityId.UI_TAP,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        CapabilityId.SCREEN_CAPTURE,
        // 后台录屏：帧只能从这块屏的持有者一帧一帧取，编码在本进程侧做（见 recordOnDisplay）。
        CapabilityId.SCREEN_RECORD,
        -> trustedSurfaceSupported(capability, surface, displayAlive(), trustedDisplayUnsafe)

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
            CapabilityId.SYS_SHELL_FULL -> fullShellCall(call, budget)
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
     * `sys.shell.full`：特权开关打开后，助手给的命令串原样递给用户服务按 `/system/bin/sh -c`
     * 执行 —— 不经过 [ShellVerbs] 的封闭动词表，不经过参数白名单，也没有助手审批可弹
     * （档位只有「完全访问」与「禁止」两态，禁止在闸门处已被拒，走不到这里）。
     *
     * 这里仍然收口的只有稳定性边界：命令长度、超时上下限与输出长度（后者在服务端）。
     * 它们不是审批的替身，回包也不会因为「风险高」而变慢或变少。
     *
     * 关闭时的行为是**拒绝**，不是回退去跑 `sys.shell` 的封闭表：那是两条语义不同的
     * 通路，悄悄换一条会把用户没批过的命令跑出来。
     */
    private suspend fun fullShellCall(call: BackendCall, budget: CallBudget): BackendResult {
        // 类型判据与预校验同源（预校验表只锁形状，这里防绕过闸门的直通货）：显式 null
        // 会被 optString 读成字面量 "null"，按字符串放行等于把 "null" 当命令跑。
        if (call.args.opt(KEY_COMMAND) !is String) {
            return malformed("$KEY_COMMAND must be a string, got ${describe(call.args.opt(KEY_COMMAND))}")
        }
        val command = call.args.optString(KEY_COMMAND)
        fullShellCommandError(command)?.let { return malformed(it) }
        if (call.args.has(KEY_TIMEOUT_MS) && call.args.opt(KEY_TIMEOUT_MS) !is Number) {
            return malformed("$KEY_TIMEOUT_MS must be a number, got ${describe(call.args.opt(KEY_TIMEOUT_MS))}")
        }
        val timeoutMs = call.args.optLong(KEY_TIMEOUT_MS, DEFAULT_FULL_COMMAND_TIMEOUT_MS)
        // 超时范围与预校验同一张表：越界拒绝并点名怎么改，不在这里夹紧——两处各说一套
        // 边界时，预校验拒掉的调用会从后端这里复活。原始值先查小数：optLong 会把
        // 120000.5 静默截成 120000，那等于替调用方改参数。
        fullShellTimeoutError(
            call.args.opt(KEY_TIMEOUT_MS) as? Number,
            timeoutMs,
        )?.let { return malformed(it) }
        val outcome = run(command, budget, timeoutMs, ::fullShellCommandFailure, markDispatched = true)
        if (outcome is BackendResult.Failed) return outcome
        (outcome as BackendResult.Ok).data
            .put("command", command)
            .put("timeoutMs", timeoutMs)
        return outcome
    }

    /** 参数值在报错里的一句话形状：避免把显式 null 读成字面量 "null"。 */
    private fun describe(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "nothing"
        value is String -> "\"${value.take(40)}\" (string)"
        else -> "$value (${value.javaClass.simpleName})"
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
        // 名单里的包不允许改它的应用操作——与入口层双保险。
        protectedBlocked(pkg)?.let { return it }
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
        if (namesHostPackage(pkg, appContext.packageName)) {
            return BackendResult.Failed(RelayError.GATE_HOST_DENIED, "refusing to stop the host app")
        }
        // 名单里的包不允许停止——与入口层双保险。
        protectedBlocked(pkg)?.let { return it }
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
     * 弹不弹由用户档位决定（旧上限元数据不参与判定）；这里不变的是目标侧的约束：
     * 系统安装确认不归本模块管，档位再高它照样出现。
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
            if (namesHostPackage(pkg, appContext.packageName)) {
                return BackendResult.Failed(RelayError.GATE_HOST_DENIED, "refusing to replace the host app")
            }
            // APK 解析出目标包名之后、`pm install` 之前查一次名单——替换受保护应用
            // 等同于改写它，一样被挡。
            protectedBlocked(pkg)?.let { return it }
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
     *
     * [markDispatched] 只由特权通路（`sys.shell.full`）传入 true：只有那一条把失败出口
     * 的副作用状态与错误码交给「是否已提交（或可能被提交）远端事务」判定。其余调用方
     * 保持 false——错误码与原因落在旧口径（`E_BACKEND_SHIZUKU_DEAD` / "binder
     * unavailable"），`sys.shell` 等既有能力的行为不因本标记改变。
     */
    private suspend fun run(
        command: String,
        budget: CallBudget,
        defaultTimeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        mapFailure: (exitCode: Int, stderr: String) -> Pair<RelayError, String> = ::commandFailure,
        markDispatched: Boolean = false,
    ): BackendResult {
        val target = binder ?: return BackendResult.Failed(RelayError.BACKEND_SHIZUKU_DEAD, null)
        val waitMs = budget.remoteWaitMs(defaultTimeoutMs, REMOTE_MARGIN_MS)
            ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
        val outcome = when (val txn = transactBounded<CommandTxn>(budget.cap, "command", waitMs + REMOTE_SLACK_MS) {
            transact(target, command, waitMs)
        }) {
            is RemoteTxn.Done -> when (val submitted = txn.value) {
                is CommandTxn.Replied -> submitted.outcome
                is CommandTxn.NotSent -> return BackendResult.Failed(
                    // 拒收对两种调用方都答「后端不可达」：重试价值（retryable）不变，
                    // 特权通路额外说明「什么都没发出去」，副作用状态按未提交。
                    RelayError.BACKEND_SHIZUKU_DEAD,
                    if (markDispatched) {
                        "the shell service refused the transaction; nothing was sent"
                    } else {
                        LEGACY_BINDER_UNAVAILABLE_REASON
                    },
                    dispatched = dispatchedOnTransaction(markDispatched, submitted),
                )
                is CommandTxn.Submitted -> return if (markDispatched) {
                    // 事务已递交但回执未到（binder 死亡或递交结果无法证实）：错误码不再用
                    // 可重试的「后端不可达」去背「结果未知」——结局只能先查后动。
                    BackendResult.Failed(
                        RelayError.TRANSPORT_UNKNOWN,
                        "the command transaction was submitted but its reply never arrived" +
                            if (submitted.note.isBlank()) "" else " (${submitted.note})",
                        dispatched = dispatchedOnTransaction(markDispatched, submitted),
                    )
                } else {
                    BackendResult.Failed(RelayError.BACKEND_SHIZUKU_DEAD, LEGACY_BINDER_UNAVAILABLE_REASON)
                }
            }
            is RemoteTxn.GaveUp -> return BackendResult.Failed(
                RelayError.TRANSPORT_TIMEOUT,
                remoteGiveUpReason("command", txn.waitedMs, txn.submittedBeforeExpiryMs),
                // 本地放弃的是等待，不是事务：事务未被取消，无论此刻已递交还是仍排在
                // 本地队列，之后都可能被执行——「可能稍后执行」不得说成「可证尚未开始」。
                dispatched = dispatchedAfterGiveUp(markDispatched),
            )
        }
        return if (outcome.success) {
            BackendResult.Ok(data = commandResultData(outcome))
        } else {
            val (error, reason) = mapFailure(outcome.exitCode, outcome.stderr.trim())
            // 非零退出也交回三份原始事实：stdout（管道提前关闭时是已产出的那部分）、
            // stderr、exit。命令失败不冒充宿主内部错误，调用方靠输出判断下一步。
            // 走到这里必然已收到回执，命令（至少 sh 本身）已经跑过：命令失败类按已派发
            // 计，effectState 答 UNKNOWN——与「命令已经跑过」一致；未递交与超时未知
            // 走不到这一支，不受影响。
            val commandRan = error == RelayError.COMMAND_FAILED || error == RelayError.COMMAND_NOT_PERMITTED
            BackendResult.Failed(
                error,
                reason,
                data = commandResultData(outcome),
                dispatched = markDispatched || commandRan,
            )
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
     * 权限拒绝按平台的明确签名（Permission Denial / SecurityException）单独归类，
     * 不与内部错误混装；裸的 "not allowed" 不算——普通命令自己也常把这类话打进
     * stderr。其余是命令自己失败了（参数合法但系统不认）：它既不是设备边界也不是
     * 权限问题，回 `E_COMMAND_FAILED` 并随回包交回 stdout / stderr / exit 三份原始
     * 事实——换码只换「下一步做什么」，不该把病历丢掉。
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
            "Permission Denial" in stderr || "SecurityException" in stderr ->
                RelayError.COMMAND_NOT_PERMITTED to detail
            else -> RelayError.COMMAND_FAILED to detail
        }
    }


    /**
     * 特权通路自己的失败解释。命令串只要过了参数与闸门就整条递给了 `/system/bin/sh -c`，
     * shell 本身必然已经跑过：非零退出（含 127「内层命令不存在」）说的是"这条命令没成"，
     * 不是"还没执行"，也不能解释成"这台设备没有这个能力"——sh 在每台设备上都在，
     * 127 的内层命令换一条就是了。副作用状态由 [BackendResult.Failed.dispatched]=true
     * 驱动 effectStateFor 标 UNKNOWN，两处口径互为印证。
     */
    private fun fullShellCommandFailure(exitCode: Int, stderr: String): Pair<RelayError, String> =
        fullShellCommandFailureOf(exitCode, stderr)

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
     * 命令事务的裸收发。三种结局必须分开：「没送出去」（服务端拒收）与「送出之后才死」
     * 是副作用状态相反的两件事——后者里命令可能已经在远端跑起来，只有前者才答得出
     * 「未开始」。[timeoutMs] 写进事务参数由远端执行：它是远端自己杀命令的上限，
     * 由调用方按预算算好传入（见 [remoteWaitMs]）。
     */
    private fun transact(target: IBinder, command: String, timeoutMs: Long): CommandTxn {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(command)
            data.writeLong(timeoutMs)
            val sent = target.transact(ShellProtocol.CODE_RUN, data, reply, 0)
            if (!sent) {
                CommandTxn.NotSent
            } else {
                CommandTxn.Replied(RelayShellService.Outcome(reply.readInt(), reply.readString() ?: "", reply.readString() ?: ""))
            }
        } catch (dead: RemoteException) {
            // binder 死在递交之后：那半个事务可能已经到了远端，不得当「没发出去」。
            CommandTxn.Submitted("binder died after the transaction was submitted")
        } catch (t: Throwable) {
            // 递交结果无法证实（本端在收发两侧都可能抛）：按「可能已送出」保守归类。
            CommandTxn.Submitted(t.javaClass.simpleName)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun malformed(reason: String) =
        BackendResult.Failed(RelayError.TRANSPORT_MALFORMED, reason)

    // ──  受保护应用：执行场景的双保险 ──
    //
    // 调用入口（RelayCoordinator）在进审批之前已经按参数里的包名拒过一次；这里的每一问
    // 是在真正下发命令/取帧之前再查一次——审批期间用户可能刚把目标包加进名单。

    /** 目标包名命中名单时的一致拒绝。回包只说明该包受保护，不暴露完整名单。 */
    private fun protectedBlocked(targetPackage: String): BackendResult? =
        if (targetPackage in protectedPackages()) {
            BackendResult.Failed(
                RelayError.PACKAGE_PROTECTED,
                "package $targetPackage is on the user's protected-apps list: the assistant can " +
                    "neither operate it nor read it",
            )
        } else {
            null
        }

    /**
     * 落在某个显示上的操作（input/取帧/录制）下发前的名单判定：读那块屏此刻的顶层包名，
     * 命中即拒；读不到按 fail-closed 拒（reason 写清"无法确认顶层应用"）。
     * 名单为空时一次 dumpsys 都不发。
     */
    private suspend fun protectedDisplayGuard(display: Int, budget: CallBudget): BackendResult? {
        val packages = protectedPackages()
        if (packages.isEmpty()) return null
        val top = topPackages(budget)?.get(display) ?: run {
            runLog.warn(
                LogSubsystem.BACKEND,
                LogEvent.PACKAGE_PROTECTED_BLOCKED,
                "cap" to budget.cap,
                "target" to UNKNOWN_FIELD,
                "where" to BLOCKED_AT_EXECUTION,
            )
            return BackendResult.Failed(
                RelayError.PACKAGE_PROTECTED,
                "cannot confirm the top app on display $display (no readable top window), and the " +
                    "protected-apps list is not empty: refusing to proceed",
            )
        }
        protectedBlocked(top)?.let {
            runLog.warn(
                LogSubsystem.BACKEND,
                LogEvent.PACKAGE_PROTECTED_BLOCKED,
                "cap" to budget.cap,
                "target" to top,
                "where" to BLOCKED_AT_EXECUTION,
            )
            return it
        }
        return null
    }

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
            // orientation 形状校验先于一切远端事务 —— 非白名单值是参数问题，
            // malformed（退出码 1 = 改脚本），不在设备侧赌一个“能建成什么样”。
            orientationError(args)?.let { return malformed(it) }
            // 大屏建屏的尺寸与“这一台机器可不可建”都收在同一个纯判据里（实际场景：
            // 物理长边越界时按 短边×2560 建屏，用户可恢复手势；两边都越界时干脆
            // 不建）。屏已经活着时不动它，也不问物理尺寸——复用优先于改尺寸。
            // 本地缓存说过期就过期：那块屏归服务端进程持有，它可能被系统回收、也可能被
            // 上一次 release 收掉，而宿主这边只会一直留着旧编号。先对一次账再决定复用，
            // 否则「create 返回 kept」指的是一块已经不存在的屏。预算装不下这次对账时
            // 缓存维持原样——「屏在不在」随后由本地 DisplayManager 说了算，不受影响。
            if (displayAlive()) refreshDisplay(budget)
            if (!trustedDisplayUnsafe && displayAlive()) {
                // 非 unsafe 的 "display already exists → kept" 路径保持原语义：不新增相对自检。
                return BackendResult.Ok(data = displayData("kept"))
            }
            // unsafe 在场时**不问本地屏号**，一律先走显式 release→create。
            // 本地 `displayId` 在 release 失败后已被置 -1（那只是"本地不再认为它可用"），
            // 服务端却仍持有那块残留屏；若在这里按 `displayAlive()` 分流，就会直接进
            // `createDisplay`，服务端回 "already exists"，而 PREPARE 的 before 是带着残留屏
            // 抓的——相对自检又变成"坏状态对坏状态"。release 事务本身才是权威：它按服务端
            // 的真实持有关系回答 0/1/-1，0/1 才继续新建（PREPARE 时已无虚拟屏）。
            //
            // **安全尺寸判据先于恢复路径**——`recoverUnsafeResidualDisplay`
            // 与普通 `createDisplay` 必须按同一套 `safeDisplaySize` 的结果定宽高。原有 unsafe
            // 分支在整个判据之前 return，恢复路径建得出 2560×2560（物理短边越界那种机器
            // 普通 create 会拒建）、也会按原始请求重造出 Override 档要避免的变形屏。
            val physical = physicalScreenSides()
            // orientation 大小写不敏感地归一到白名单小写（orientSize 判等用）。
            val requestedOrientation = args.optString(KEY_ORIENTATION)
                .takeIf { it.isNotBlank() }?.lowercase()
            when (val sizing = safeDisplaySize(physical.first, physical.second)) {
                SafeDisplaySize.NONE_SAFE -> {
                    // 两边都越界：不送建屏事务、不反复重试，也不写"系统拒绝"那种机器级
                    // 标记（denied 语义与清单可用性都不因它改变——那是 ROM 拒绝专用）。
                    // unsafe 在场的场景（残留屏正破坏主屏）先尝试一次显式收屏
                    // ——这台机器确实建不出任何安全尺寸，把残留屏留着只会一直破坏下去；
                    // 收屏被确认就按原来的 NONE_SAFE 失败回（残留场景已排除，unsafe 由
                    // releaseClearsUnsafe 解除），收不掉则按残留恢复失败回。两种结局都
                    // **不得建屏**。
                    if (trustedDisplayUnsafe) {
                        val released = releaseDisplay(budget)
                        if (released !is BackendResult.Ok) return residualRecoveryFailure(released as BackendResult.Failed)
                        // 收掉后 unsafe 可能已被 releaseClearsUnsafe 清除；继续按
                        // NONE_SAFE 的既有结论失败——这台机器建屏依然没有安全尺寸。
                    }
                    return BackendResult.Failed(
                        RelayError.SURFACE_UNAVAILABLE,
                        "physical display is ${physical.first}x${physical.second}: no safe trusted-display " +
                            "size fits (each side must be at most $MAX_SAFE_SIDE px), so the display is " +
                            "not created; do not retry surface.virtual - run the involved operations in foreground mode",
                        data = noneSafeData(physical.first, physical.second),
                    )
                }
                is SafeDisplaySize.Override -> {
                    // 安全尺寸覆盖请求里的宽高：两侧差异过大等于让 ROM 当场拒绝（或像 实际
                    // 场景那样毁掉手势），判据是按物理短边×2560 建。dpi 仍按请求或缺省。
                    // 恢复路径与普通 create 同一套判据的同一份结果。
                    // orientation 只决定横竖（对**最终**宽高交换），不改大小。
                    val oriented = orientSize(sizing.width, sizing.height, requestedOrientation)
                    return dispatchCreate(
                        budget,
                        width = oriented.first,
                        height = oriented.second,
                        requireHealthProof = trustedDisplayUnsafe,
                        dpi = args.optInt(KEY_DPI, DEFAULT_DPI),
                    )
                }
                SafeDisplaySize.KEEP_EXISTING -> {}
            }
            // 两侧都在可建区间（或读不到物理尺寸）：沿用请求里的宽高与缺省值，
            // 与本判定引入之前的行为一致。
            // orientation 只决定横竖（对**最终**宽高交换），不改大小。
            val oriented = orientSize(
                args.optInt(KEY_WIDTH, DEFAULT_WIDTH),
                args.optInt(KEY_HEIGHT, DEFAULT_HEIGHT),
                requestedOrientation,
            )
            return dispatchCreate(
                budget,
                width = oriented.first,
                height = oriented.second,
                requireHealthProof = trustedDisplayUnsafe,
                dpi = args.optInt(KEY_DPI, DEFAULT_DPI),
            )
        }
        if (action == ACTION_RELEASE) {
            return releaseDisplay(budget)
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

    /**
     * create 的最后一步（分派点）：unsafe 在场时走残留屏恢复
     * （[recoverUnsafeResidualDisplay]，先显式 release 再按已定尺寸新建，新屏必须交出
     * 健康证明）；否则按普通 create（[createDisplay]，`requireHealthProof=false`，
     * `unverified` 语义不变）。
     */
    private suspend fun dispatchCreate(
        budget: CallBudget,
        width: Int,
        height: Int,
        dpi: Int,
        requireHealthProof: Boolean,
    ): BackendResult =
        if (requireHealthProof) {
            recoverUnsafeResidualDisplay(budget, width, height, dpi)
        } else {
            createDisplay(budget, width, height, dpi, requireHealthProof = false)
        }

    /**
     * 一次显式 release 的完整通路（`surface.virtual` release 与  的残留屏恢复共用；
     * 判据收口在 [releaseClearsUnsafe]）。回 [BackendResult]：0/1 → 成功 data（此时残留
     * 场景已排除，unsafe 解除并清单重铺）；负值/本地放弃 → 失败，**unsafe 不动**。
     *
     * 本地归账（编号作废、帧缓存清空）在**回包成稿前**做，但它不再
     * 兼任"屏确实没了"的证据——本地账是缓存，判据读服务端回执；release 失败时 displayId
     * 照样置 -1 只是"本地不再认为这块屏可用"的意思，supports 真值表对 unsafe 的拦挡由
     * [trustedDisplayUnsafe] 承担。
     */
    private suspend fun releaseDisplay(
        budget: CallBudget? = null,
    ): BackendResult {
        val waitMs = if (budget == null) {
            DISPLAY_TXN_WAIT_MS
        } else {
            // 带调用预算时"装不下最小往返"仍按既有口味回结构化超时。
            budget.remoteWaitMs(DISPLAY_TXN_WAIT_MS, REMOTE_MARGIN_MS)
                ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
        }
        val cap = budget?.cap ?: CAP_LIFECYCLE
        val released = when (val txn = displayTransaction(cap, ShellProtocol.CODE_DISPLAY_RELEASE, waitMs)) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> {
                // 收屏的结局未知：本地按「已要求收掉」记账（编号与缓存一并作废），
                // 回包如实说等待被放弃——屏可能已被收掉，也可能稍后才会。
                displayId = -1
                frameCache?.clear()
                return releaseOutcome(
                    repliedCode = RELAY_CODE_GAVE_UP,
                    replyReason = remoteGiveUpReason("display release", txn.waitedMs, txn.submittedBeforeExpiryMs),
                )
            }
        }
        displayId = -1
        // 屏收了，那份"上一帧"就是在替一块不存在的屏说话。
        frameCache?.clear()
        // 复核  +：屏被确认收掉（0=本无屏 / 1=收掉）才解除 unsafe——
        // 0 现在服务端确实不持有任何屏（release 失败的场景引用未清、回枚不会被当成 0，
        // 见 RelayShellService.TrustedDisplay.release）。收屏失败/放弃等待不解除。
        if (releaseClearsUnsafe(released.code) && trustedDisplayUnsafe) {
            trustedDisplayUnsafe = false
            unsafeListener?.invoke()
        }
        return releaseOutcome(repliedCode = released.code, replyReason = released.reason)
    }

    /**
     * 残留屏的显式恢复通路（收紧）：unsafe 在场——
     * **不问本地屏号/屏是否活着，一律走这里**（本地 `displayId` 在 release 失败后已置
     * -1，`displayAlive()` 只反映本地 DisplayManager，能见不代表服务端不持有残留屏）。
     * 先一次显式 release（同一事务通路 [releaseDisplay]），收屏被服务端**确认**（0/1）
     * 才继续新建；新建走完整 createDisplay（PREPARE→CREATE→SELF_CHECK，before 在
     * "没有虚拟屏"状态下抓）。宽高与 dpi 由调用方按 [safeDisplaySize] 判据**先定好**
     * 传入（恢复路径与普通 create 共用同一份判据结果，不再各自算），
     * 并且 `requireHealthProof=true`。
     *
     * **健康证明要求**：刚确认收掉过一块已破坏主屏的残留屏，此刻建出
     * 的新屏必须交出一份完整的健康证明——自检事务拿不到（预算装不下/放弃等待/服务端
     * 没答上）、正文不是 JSON、或服务端写 `unverified` 时，一律按**失败**收场
     * （[unverifiedIsFailure] 纯判据与 JVM 测试同走，reason 写清"刚清掉一块 unsafe 残留
     * 屏，新建的屏没有健康证明"）。失败收场前先尝试一次 [releaseDisplay] 收掉这块新屏；
     * 收不掉也照样置 unsafe、`displayId=-1`——下一次 create 会走 release-first。
     * [releaseDisplay] 可能已把 unsafe 清成 false，随后的失败出口（[selfCheckFailed]）
     * 重新置 true，两个出口的先后顺序保证了标记不会开着跑。
     *
     * 收不掉（释放回负码、本地放弃等待、预算装不下）就保持 unsafe、直接回结构化失败
     * （[residualRecoveryFailure]），**不得进入建屏**。恢复判据与 JVM 测试同走。
     */
    private suspend fun recoverUnsafeResidualDisplay(
        budget: CallBudget,
        width: Int,
        height: Int,
        dpi: Int,
    ): BackendResult {
        val released = releaseDisplay(budget)
        if (released is BackendResult.Ok) {
            return createDisplay(budget, width, height, dpi, requireHealthProof = true)
        }
        // 收不掉：保持 unsafe（[releaseDisplay] 对失败/放弃不动标记），reason 写清
        // "残留屏收不掉，重启通道或再 release"。
        return residualRecoveryFailure(released as BackendResult.Failed)
    }

    /**
     * 残留屏收不掉时的失败回包（结构化 + reason 说清下一步），纯判据与
     * 生产出口同走：unsafe 原样保持（[releaseDisplay] 对失败/放弃不动标记），错误码按
     * 失败性质分档——服务端收屏失败 → E_SURFACE_TRUSTED_UNSAFE（"这台机器带坏主屏的
     * 残留屏收不掉"）；本地放弃等待 → E_TRANSPORT_TIMEOUT（"结局未知"）。
     */
    private fun residualRecoveryFailure(released: BackendResult.Failed): BackendResult.Failed =
        BackendResult.Failed(
            when (released.error) {
                RelayError.TRANSPORT_TIMEOUT -> RelayError.TRANSPORT_TIMEOUT
                else -> RelayError.SURFACE_TRUSTED_UNSAFE
            },
            "the leftover trusted display could not be confirmed released " +
                "(${released.reason ?: "no reason"}): create will not rebuild over a residual " +
                "display that already broke the main screen once - send {\"action\":\"release\"} " +
                "again or restart the Shizuku channel, then create; keep the involved operations " +
                "in foreground mode meanwhile",
            data = released.data,
        )


    /**
     * 一次建屏发出去并收账：判据后的唯一发送点，安全尺寸覆盖与“沿用请求/缺省”
     * 两路都在这里汇合。回成功前的对账同时把被拒标记一并清了（建成即拒不再成立），
     * 失败的三态判定（拒绝/瞬时/问不成）留在前面各出口。
     *
     * [requireHealthProof]：true（残留屏恢复新建）时自检验不出
     * 主屏健康（`unverified`/没读到/正文畸形）按失败收场，判据在
     * [unverifiedIsFailure]（纯函数，与 JVM 测试同走）；false（普通 create）时
     * `unverified` 语义不变——屏保留、不清标记、不伪装 pass。
     */
    private suspend fun createDisplay(
        budget: CallBudget,
        width: Int,
        height: Int,
        dpi: Int,
        requireHealthProof: Boolean,
    ): BackendResult {
        // 自检的 before：建屏**之前**让用户服务抓一份主屏状态，等自检回来对账。
        // 预算装不下这条往返就跳过：before 按 unknown 处理，自检只做不依赖 before 的那几条。
        // 本地放弃等待也无妨——快照在服务端仍会完成并入账。
        budget.remoteWaitMs(DISPLAY_TXN_WAIT_MS, REMOTE_MARGIN_MS)?.let { prepareWait ->
            displayTransaction(budget.cap, ShellProtocol.CODE_DISPLAY_PREPARE, prepareWait)
        }
        val waitMs = budget.remoteWaitMs(DISPLAY_TXN_WAIT_MS, REMOTE_MARGIN_MS)
            ?: return remoteBudgetExhausted(budget, REMOTE_MARGIN_MS)
        val created = when (val txn = displayTransaction(budget.cap, ShellProtocol.CODE_DISPLAY_CREATE, waitMs) {
            it.writeInt(width)
            it.writeInt(height)
            it.writeInt(dpi)
        }) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> return BackendResult.Failed(
                RelayError.TRANSPORT_TIMEOUT,
                remoteGiveUpReason("display create", txn.waitedMs, txn.submittedBeforeExpiryMs),
            )
        }
        if (created.code < 0) {
            // 建屏失败回包的三态判定收在伴生纯函数里（JVM 单测穷举各场景）：
            val verdict = createRejection(created.code)
            if (verdict == null) {
                // 屏「问不成」（CODE_UNVERIFIED）：那块屏可能还在跑。既不清缓存的编号
                // （清了就一直说"没有屏"），也不能报"系统拒绝了创建"让用户去动手机 ——
                // 唯一有效的下一步是助手自己 release 再 create，那是可重试的通路问题，
                // 不是用户动作。
                return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, created.reason)
            }
            displayId = -1
            if (verdict.systemDenied) {
                // 服务活着、事务有回话，且服务端点名**系统当场拒绝建这块屏** —— 不是
                // "后端没连上、等等再来"，也不是"旧屏收不掉"那种瞬时失败。报成可重试
                // 会让助手反复建屏，而这条的重试次数由系统决定，不由本模块决定。拒绝结论
                // 同时留给能力清单：`surface.virtual` 要在能力列表里如实标不可用并写明
                // 原因，助手的下一步才会是"改前台模式"而不是"再建一次屏"。只有这一个
                // 码置拒绝标记；旧屏清理失败与内部异常保持可重试错误，不把瞬时失败
                // 永久标死。
                trustedDisplayDenied = true
                deniedListener?.invoke()
                return BackendResult.Failed(RelayError.SURFACE_TRUSTED_DENIED, created.reason)
            }
            // 其余负值（旧屏收不掉、建屏内部异常等）：服务没说是拒绝，就按通路级
            // 失败如实报错，下一趟还能再试；不写拒绝标记。
            return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, created.reason)
        }
        displayId = created.code
        // 建成了：「上次被拒」就此作废。那块屏可能拿回同一个编号却是完全不同的内容，
        // 而这份缓存是按 displayId 认账的 —— 编号复用这一种情况只能靠"建新屏就清掉"来兜。
        trustedDisplayDenied = false
        frameCache?.clear()
        // 尺寸以服务端为准：请求里的宽高会被夹到允许区间，回原值等于报一个不存在的屏。
        refreshDisplay(budget)
        // 建屏后的兼容与主屏自检：全屏窗口/IME 兜底在服务端做，这里等
        // 结论、落四条运行日志并维护「可信屏不安全」标记。观察到破坏时那块屏已在服务端
        // 当场释放，这次建屏按结构化失败收场（改前台，不可重试）。恢复路径
        // （requireHealthProof=true）连「unverified」都按失败收场——判据见
        // [selfCheckUnverifiedFails] / [unverifiedIsFailure]。
        selfCheckAfterCreate(budget, created.code, requireHealthProof)?.let { return it }
        return BackendResult.Ok(data = displayData(ACTION_CREATE))
    }

    /**
     * 建屏后的自检与兼容收尾。
     *
     * 收场**只认服务端回包的 `selfCheck` 字段**（判据纯函数
     * [selfCheckClearsUnsafe] 与 JVM 测试同走）：
     * - `pass`（且 violations 为空）：一次完整通过——解除 unsafe 并继续；
     * - `unsafe`（或 violations 非空）：按失败收场，置 unsafe、结构化回错；
     * - `unverified` / 自检事务拿不到 / 正文不是 JSON：由 [unverifiedIsFailure]（纯函数，
     * JVM 测试同走）按当前场景两头分流——普通建屏（[requireHealthProof]=false，本来
     * 没有 unsafe）保持既有语义——屏保留、标记不动，回包里如实写 unverified
     * （**不伪装 pass**）；残留屏恢复新建（`requireHealthProof=true`）一律按失败收场
     * （[selfCheckUnverifiedFails]）。
     * 判据从「读实例字段 trustedDisplayUnsafe」（那时恢复路径已被
     * release 清成 false，fail-closed 分支不可达）改为显式的 `requireHealthProof` 入参
     * 一路传进来；失败前的收屏也由 [selfCheckUnverifiedFails] 自己发一次
     * [releaseDisplay]——收不掉则由 [selfCheckFailed] 置 unsafe、`displayId=-1`，
     * 下一次 create 走 release-first。
     */
    private suspend fun selfCheckAfterCreate(
        budget: CallBudget,
        display: Int,
        requireHealthProof: Boolean,
    ): BackendResult? {
        val waitMs = budget.remoteWaitMs(SELF_CHECK_TXN_WAIT_MS, REMOTE_MARGIN_MS)
        if (waitMs == null) {
            logDisplayFlags(display, "unknown", "unknown")
            return selfCheckUnverifiedFails(display, emptyList(), requireHealthProof = requireHealthProof)
        }
        val reply = when (val txn = displayTransaction(budget.cap, ShellProtocol.CODE_DISPLAY_SELF_CHECK, waitMs)) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> {
                logDisplayFlags(display, "unknown", "unknown")
                return selfCheckUnverifiedFails(display, emptyList(), requireHealthProof = requireHealthProof)
            }
        }
        if (reply.code < 0) {
            logDisplayFlags(display, "unknown", "unknown")
            return selfCheckUnverifiedFails(display, emptyList(), requireHealthProof = requireHealthProof)
        }
        val check = runCatching { JSONObject(reply.reason) }.getOrNull() ?: run {
            logDisplayFlags(display, "unknown", "unknown")
            return selfCheckUnverifiedFails(display, emptyList(), requireHealthProof = requireHealthProof)
        }
        logDisplayFlags(display, check.boolText("windowingOk"), check.boolText("imePolicyOk"))
        logMainState(display, check)
        // 首轮见到、复采已恢复：只记录。它不是失败，也绝不因此动标记。
        val transient = check.optJSONArray("transientRecovered")?.let { array ->
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty()
        if (transient.isNotEmpty()) {
            runLog.info(
                LogSubsystem.BACKEND,
                LogEvent.DISPLAY_SELF_CHECK_TRANSIENT,
                "display" to display.toString(),
                "recovered" to transient.joinToString(","),
            )
        }
        val violations = check.optJSONArray("violations")?.let { array ->
            (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }
        }.orEmpty()
        // 收场判据读服务端的 `selfCheck` 字段（[selfCheckClearsUnsafe] 纯判据与
        // JVM 测试同走）。只有 pass（selfCheck=pass 且 violations 为空）才允许清 unsafe；
        // 其余（unverified/unknown/畸形 + violations 非空）由 [unverifiedIsFailure] 分流：
        // - 普通建屏且全 unverifiable：屏保留、标记不动，回包如实写 unverified，不伪装
        // pass（[selfCheckUnverifiedFails] 的 null 收场）；
        // - 残留屏恢复新建（requireHealthProof=true）或 violations 非空：按失败收场。
        if (selfCheckClearsUnsafe(check.stringOrNull("selfCheck"), violations.isEmpty())) {
            // 复核：建屏 + 自检完整通过是 unsafe 的解除点之一（其余：release 确认、
            // 通道收摊、宿主进程重启）。清单要在这一刻回到真实状态。
            if (trustedDisplayUnsafe) {
                trustedDisplayUnsafe = false
                unsafeListener?.invoke()
            }
            return null
        }
        return selfCheckUnverifiedFails(
            display,
            violations,
            requireHealthProof = requireHealthProof,
            unverified = check.stringOrNull("selfCheck") != SELF_CHECK_UNSAFE,
            check = check,
        )
    }

    /**
     * `unverified`/非 pass 收场的分流出口（第 2 条 +）：
     * - 判据 [unverifiedIsFailure]=false（普通建屏、全 unverifiable、无 violations）：
     * 保持「unknown 不判死」的既有语义——屏保留、标记不动、null 收场；
     * 回包不伪装 pass（回包由服务端自己写 unverified）；**不得清 unsafe**。
     * - 判据 true（服务端写 unsafe、violations 非空、或 requireHealthProof=true 即
     * 残留屏恢复新建没有拿到健康证明）：先**尝试一次 release** 收这块新屏（收掉才算
     * 尽责；收不掉也照样进 [selfCheckFailed] 置 unsafe、`displayId=-1`），再按失败
     * 收场。恢复路径的收屏失败出口由 [selfCheckFailed] 重新置 true（release 已把
     * unsafe 清成 false 时）——flag 不允许开着跑。
     */
    private suspend fun selfCheckUnverifiedFails(
        display: Int,
        violations: List<String>,
        requireHealthProof: Boolean,
        unverified: Boolean = true,
        check: JSONObject = JSONObject(),
    ): BackendResult? {
        if (!unverifiedIsFailure(requireHealthProof, unverified, violations)) {
            // 普通建屏的 unverified：语义不变，屏保留。
            return null
        }
        // fail-closed：先尝试一次 release 收掉这块没交健康证明的新屏（同一事务通路；
        // 预算装不下/放弃等待也照走失败收场——收屏只是尽力而为，标记的置位不依赖它，
        // [selfCheckFailed] 无条件置 unsafe）。
        if (displayId >= 0) {
            releaseDisplay()
        }
        return selfCheckFailed(display, violations, check)
    }

    /**
     * unsafe 收场的统一出口：标记置位、本地账归零（编号与帧缓存指向的可能是仍在破坏
     * 主屏的屏）、落 DISPLAY_SELF_CHECK_FAILED / DISPLAY_RELEASE_STATE 两行日志、
     * 回结构化失败。**无条件置 unsafe**：失败收场前的释放尝试（[releaseDisplay]）可能
     * 已把标记清成 false（回执可确认时），这里的置位把它重新立起来——恢复路径没有
     * 健康证明的场景不允许标记开着跑。
     */
    private fun selfCheckFailed(display: Int, violations: List<String>, check: JSONObject): BackendResult {
        // 不管服务端那次收屏成没成，本地账先归零：supports 对 unsafe 的后台落点回 false，
        // 调用从此回前台。
        trustedDisplayUnsafe = true
        displayId = -1
        frameCache?.clear()
        unsafeListener?.invoke()
        runLog.warn(
            LogSubsystem.BACKEND,
            LogEvent.DISPLAY_SELF_CHECK_FAILED,
            "display" to display.toString(),
            "violations" to violations.joinToString(","),
        )
        // 复核「afterRelease null 也写一条」：释放后的读数缺席（快照失败）时逐项 unknown。
        val afterRelease = check.optJSONObject("afterRelease")
        runLog.info(
            LogSubsystem.BACKEND,
            LogEvent.DISPLAY_RELEASE_STATE,
            "display" to display.toString(),
            "released" to check.boolText("released"),
            "afterTop" to (afterRelease?.stringOrUnknown("top0") ?: UNKNOWN_FIELD),
            "afterFocus" to (afterRelease?.stringOrUnknown("focus") ?: UNKNOWN_FIELD),
            "afterIme" to (afterRelease?.imeText() ?: UNKNOWN_FIELD),
        )
        return BackendResult.Failed(
            RelayError.SURFACE_TRUSTED_UNSAFE,
            "the trusted display was created but the main-screen self-check could not confirm " +
                "the main screen stayed healthy (${violations.ifEmpty { listOf(UNVERIFIED_MARK) } .joinToString(", ")}): " +
                "the display has been released or kept without a health proof - switch to foreground " +
                "mode. surface.virtual stays available: send {\"action\":\"release\"} to clear a " +
                "leftover display or re-create after the release is confirmed",
            data = unsafeFailureData(display, violations),
        )
    }

    /** DISPLAY_CREATE_FLAGS：flags 值与服务端建屏同一函数同一入参，两处口径一致。 */
    private fun logDisplayFlags(display: Int, windowingOk: String, imePolicyOk: String) {
        runLog.info(
            LogSubsystem.BACKEND,
            LogEvent.DISPLAY_CREATE_FLAGS,
            "display" to display.toString(),
            "sdk" to android.os.Build.VERSION.SDK_INT.toString(),
            "trusted" to "true",
            "flags" to "0x" + Integer.toHexString(
                RelayShellService.TrustedDisplay.createFlags(android.os.Build.VERSION.SDK_INT, trusted = true),
            ),
            "windowingOk" to windowingOk,
            "imePolicyOk" to imePolicyOk,
        )
    }

    /** DISPLAY_MAIN_STATE：建屏前后主屏读数成对出现，读不到的项一律 unknown。 */
    private fun logMainState(display: Int, check: JSONObject) {
        val before = check.optJSONObject("before")
        val after = check.optJSONObject("after")
        runLog.info(
            LogSubsystem.BACKEND,
            LogEvent.DISPLAY_MAIN_STATE,
            "display" to display.toString(),
            "beforeTop" to (before?.stringOrUnknown("top0") ?: UNKNOWN_FIELD),
            "afterTop" to (after?.stringOrUnknown("top0") ?: UNKNOWN_FIELD),
            "beforeTask" to (before?.stringOrUnknown("topTask0") ?: UNKNOWN_FIELD),
            "afterTask" to (after?.stringOrUnknown("topTask0") ?: UNKNOWN_FIELD),
            "beforeFocus" to (before?.focusText() ?: UNKNOWN_FIELD),
            "afterFocus" to (after?.focusText() ?: UNKNOWN_FIELD),
            "beforeIme" to (before?.imeText() ?: UNKNOWN_FIELD),
            "afterIme" to (after?.imeText() ?: UNKNOWN_FIELD),
            "launcher" to ((before ?: after)?.stringOrUnknown("launcher") ?: UNKNOWN_FIELD),
            "displayGroup" to (after?.stringOrUnknown("groupVirtual") ?: UNKNOWN_FIELD),
            "coverage" to check.stringOrUnknown("coverage"),
        )
    }

    /**
     * 用户眼前那块物理屏的（短边， 长边）。读不到（无从测量）时报 (0, 0)，
     * 判据对它按"未知物理尺寸"放行——沿用既有请求，不在没有证据处替设备下结论。
     */
    private fun physicalScreenSides(): Pair<Int, Int> = runCatching {
        val windowManager = appContext.getSystemService(android.view.WindowManager::class.java)
            ?: return 0 to 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            minOf(bounds.width(), bounds.height()) to maxOf(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION") // 30 以下没有窗口度量接口，真实尺寸只能从 Display 取
            val display = windowManager.defaultDisplay ?: return 0 to 0
            val metrics = android.util.DisplayMetrics().also { display.getRealMetrics(it) }
            minOf(metrics.widthPixels, metrics.heightPixels) to
                maxOf(metrics.widthPixels, metrics.heightPixels)
        }
    }.getOrDefault(0 to 0)

    private suspend fun displayLaunch(call: BackendCall, budget: CallBudget): BackendResult {
        val args = call.args
        val packageName = args.optString(KEY_PACKAGE)
        // `am start` 下发之前按目标包名查一次名单——与入口层双保险。
        protectedBlocked(packageName)?.let { return it }
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
                // 结局未知的结构化标注：调用侧据此把它与"屏被回收"那类暂时性失败分开。
                data = JSONObject().put("landingCheck", "failed").put("display", display),
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
                // 观察到的场景随失败回包走：调用侧的落点裁决读结构化字段，不解析 reason。
                data = launched.data,
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
            // 观察到的场景（含 grabbedUserScreen）随失败回包走：落点裁决读结构化字段。
            data = launched.data,
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
    ) = writeLandingReport(data, tops, display, packageName, before)

    /**
     * 各显示此刻的顶层活动包名，一次 `dumpsys` 拿全。
     *
     * 不按显示逐个问：通道同一时刻只跑一条请求，两次问法会把一次启动变成六条 shell 事务。
     * 回 null 表示这一问没成 —— 与「问成了，那块屏上不是这个包」必须是两个答案，
     * 否则「查不了」会被读成「两处都看过都没看见」。
     */

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
        protectedDisplayGuard(display, budget)?.let { return it }
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
        protectedDisplayGuard(display, budget)?.let { return it }
        val duration = args.optInt(KEY_DURATION_MS, DEFAULT_SWIPE_MS).coerceIn(50, MAX_SWIPE_MS)
        return runOnDisplay("input -d $display swipe $fromX $fromY $toX $toY $duration", display, budget)
    }

    private suspend fun displayText(args: JSONObject, budget: CallBudget): BackendResult {
        val display = requireDisplay() ?: return noDisplay()
        val text = args.optString(KEY_TEXT)
        // 文本里可以有空格与引号，但不能有换行：`input text` 的换行会被当成命令结束。
        if (text.any { it == '\n' || it == '\r' }) return malformed("$KEY_TEXT must be single line")
        if (text.length > MAX_TEXT_CHARS) return malformed("$KEY_TEXT too long")
        protectedDisplayGuard(display, budget)?.let { return it }
        return runOnDisplay("input -d $display text ${shellQuote(text)}", display, budget)
    }

    private suspend fun displayKey(args: JSONObject, budget: CallBudget): BackendResult {
        val display = requireDisplay() ?: return noDisplay()
        val name = args.optString(KEY_KEY_EVENT)
        val code = KEY_EVENTS[name] ?: return malformed(
            "unknown key: $name, accepted: [${KEY_EVENTS.keys.joinToString(", ")}]",
        )
        protectedDisplayGuard(display, budget)?.let { return it }
        return runOnDisplay("input -d $display keyevent $code", display, budget)
    }

    /**
     * D：面板输入 —— 用户自己预览面板的远程操作（tap/swipe/back/home 四种）。
     *
     * **不进 gate/档位/审批，也不做 [protectedDisplayGuard] 的  复查**：面板是用户
     * 对自己虚拟屏的操作，鉴权在宿主控制 token + Node 半 tab token + 会话存活
     * （TrustedStreamControlEndpoint /input 已判）；动作面收窄到四种，没有文字、
     * 组合键与复杂手势。坐标已是像素 —— 端点按会话 metadata 里的**源尺寸**
     * （服务端 `TrustedDisplay.size()` 在 start 时写入）映射并夹取，这里只对
     * [offDisplay] 的屏内边界再核一次，然后落成与 [displayTap]/[displaySwipe]/
     * [displayKey] 同一条 `input -d <displayId> …` 低层事务。
     */
    suspend fun panelInput(command: interlock.relay.core.exec.stream.PanelInputPolicy.Command): Pair<Boolean, String> {
        requireDisplay() ?: return false to (noDisplay().reason ?: "no trusted display")
        val budget = CallBudget(PANEL_INPUT_BUDGET_MS, CAP_PANEL_INPUT)
        // 先对一次账：端点按会话 metadata 的源尺寸映射坐标，而那块屏可能在上一刻被
        // 收掉/换掉 —— 用服务端的当前读数再核一遍，尺寸拿不到就拒，不 0 尺寸放行
        // （"回 ok 但没动"比回错更糟）。
        refreshDisplay(budget)
        // 对账可能换了一块屏：`input -d` 必须用刷新后的屏号，不能拿刷新前的旧号。
        val currentDisplay = requireDisplay() ?: return false to (noDisplay().reason ?: "no trusted display")
        val args = buildPanelInputArgs(command) ?: return false to "unknown panel input action: " + command.action
        panelInputOutOfBounds(command, displaySize.first, displaySize.second)?.let { return false to it }
        val outcome = runOnDisplay("input -d $currentDisplay $args", currentDisplay, budget)
        return when (outcome) {
            is BackendResult.Ok -> true to ""
            is BackendResult.Failed -> {
                val reason = outcome.reason ?: outcome.error.name
                // 本地放弃等待 != 事务失败：远端可能已提交/仍会提交。端点据此回
                // 504 + outcome unknown，不把"不知道"说成"没执行"。
                if (reason.contains("outcome is unknown")) false to ("outcome unknown: " + reason)
                else false to reason
            }
        }
    }

    /**
     * 面板任务面 —— 三件事，鉴权在控制端点（控制 token + tabToken + 会话存活），
     * 这里只管设备事务：
     *
     * - [panelTaskList]：问服务端列**指定显示**上的任务（虚拟屏方向列可信屏、主屏方向
     * 列 0），把回包正文（`{"displayId":…,"tasks":[…]}`，displayId 是服务端实际解析
     * 的号）原样递回；label 由本进程 PackageManager 现映射，映射不到的保持 package 兜底；
     * - [panelTaskMove]：从**任务当前所在的 source 显示**枚举认根（叶子先解析 RootTask
     * 号），再 `am display move-stack <root> <target>`；任务当前已在目标屏上才回
     * "already"，任务不在 source 屏上回 409 语义；
     * - [panelTaskRelease]：走既有 release 事务**单次收屏**（
     * `DESTROY_CONTENT_ON_REMOVAL` 会关闭屏上任务，调用方 UI 负责二次确认；停流由
     * 控制端点先做；不做 move-first —— 见 [panelTaskRelease] 注释的 Android 17 禁令）。
     *
     * 与 [panelInput] 同一豁免口径：不进 gate/档位/审批，也不做 [protectedDisplayGuard]
     * 的  复查 —— 搬迁是用户自己面板对虚拟屏任务的收纳整理，动作面收窄到"主屏与
     * 当前虚拟屏两点之间"，没有任意 display 号可以指。
     */

    /** 搬迁方向：main = 搬去主屏（source 虚拟屏 → target 0），virtual = 搬回虚拟屏（source 0 → target 虚拟屏）。 */
    enum class PanelMoveDirection(val sourceIsVirtual: Boolean) {
        MAIN(sourceIsVirtual = true),
        VIRTUAL(sourceIsVirtual = false),
    }

    suspend fun panelTaskList(sourceIsVirtual: Boolean): Pair<Boolean, String> {
        // 主屏方向不需要本地屏活着：列 display 0 的任务与可信屏无关。
        if (sourceIsVirtual) {
            requireDisplay() ?: return false to (noDisplay().reason ?: "no trusted display")
        }
        val budget = CallBudget(PANEL_TASK_BUDGET_MS, CAP_PANEL_TASK)
        // 虚拟屏方向先对账：屏可能在上一刻被收掉/换掉，旧屏号上的任务列表毫无意义。
        if (sourceIsVirtual) refreshDisplay(budget)
        val requested = if (sourceIsVirtual) {
            requireDisplay() ?: return false to (noDisplay().reason ?: "no trusted display")
        } else {
            ShellProtocol.MAIN_DISPLAY_ID
        }
        val reply = when (val txn = displayTransaction(
            budget.cap,
            ShellProtocol.CODE_TASKS_LIST,
            budget.remoteWaitMs(TASKS_DUMP_WAIT_MS, REMOTE_MARGIN_MS)
                ?: return false to "panel tasks: local budget cannot fit one roundtrip",
        ) {
            it.writeInt(requested)
        }) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> return false to ("outcome unknown: " +
                remoteGiveUpReason("panel tasks list", txn.waitedMs, txn.submittedBeforeExpiryMs))
        }
        if (reply.code < 0) return false to reply.reason.ifEmpty { "panel tasks: dumpsys failed" }
        // displayId 不再由宿主覆盖：回包里的号是服务端实际解析的（换屏竞态下可对账）。
        return true to labelTasksJson(reply.reason)
    }

    /**
     * 把服务端任务 JSON 里的 `package` 逐条映上本机应用标签（`label` 原是 package 兜底值）。
     * PackageManager 抛/查不到的条目保持原值 —— label 只是显示名，不构成任何判定。
     */
    private fun labelTasksJson(tasksJson: String): String {
        val parsed = runCatching { JSONObject(tasksJson) }.getOrNull() ?: return tasksJson
        val tasks = parsed.optJSONArray("tasks") ?: return tasksJson
        val pm = appContext.packageManager
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            val pkg = task.optString("package")
            if (pkg.isEmpty()) continue
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
            if (!label.isNullOrBlank()) task.put("label", label)
        }
        return parsed.toString()
    }

    /**
     * 在两个显示之间搬一个任务（0 = 用户主屏 ↔ 当前虚拟屏）。
     *
     * source = 任务当前所在显示（main 方向是虚拟屏，virtual 方向是主屏）；
     * target = 对面的那块。**从 source 列表认根**：任务已被搬走时不在 source 列表里，
     * 回 409 语义而不是对着另一块的列表误判。任务当前已在 target 上（同一屏的重复
     * 请求）如实回 "already"，不发 move。回 (ok, reason)：409/503 语义由调用方分流。
     */
    suspend fun panelTaskMove(taskId: Int, direction: PanelMoveDirection): Pair<Boolean, String> {
        // virtual 方向的 target 是本地虚拟屏：屏不在就先回「没有屏」，不去动主屏的任务。
        requireDisplay() ?: return false to (noDisplay().reason ?: "no trusted display")
        val budget = CallBudget(PANEL_TASK_BUDGET_MS, CAP_PANEL_TASK)
        refreshDisplay(budget)
        val virtualDisplay = requireDisplay() ?: return false to (noDisplay().reason ?: "no trusted display")
        // source = 任务当前所在屏，target = 对面那块：main 方向 source 虚拟屏 → target 0；
        // virtual 方向 source 0 → target 虚拟屏。映射抽成纯函数，JVM 可测。
        val (source, target) = panelMoveEndpoints(direction.sourceIsVirtual, virtualDisplay)
        val listOutcome = panelTaskList(direction.sourceIsVirtual)
        if (!listOutcome.first) return false to listOutcome.second
        val entries = runCatching { RelayShellService.TrustedTasks.parseTasksJson(listOutcome.second) }
            .getOrElse { return false to "panel task move: task list was unreadable (${it.javaClass.simpleName})" }
        val rootTaskId = RelayShellService.TrustedTasks.rootOf(taskId, entries)
            ?: return false to "panel task move: task $taskId is not on display $source"
        // 任务当前已在目标屏上：source 屏列表里却有它，说明枚举落在搬迁之后（竞态）
        // —— 这一支只在 source==target 的病态场景出现，正常两屏搬迁不会走到。
        if (source == target) {
            return false to "panel task move: task $taskId is already on display $target"
        }
        val reply = when (val txn = displayTransaction(
            budget.cap,
            ShellProtocol.CODE_TASKS_MOVE,
            budget.remoteWaitMs(TASKS_MOVE_WAIT_MS, REMOTE_MARGIN_MS)
                ?: return false to "panel task move: local budget cannot fit one roundtrip",
        ) {
            it.writeInt(rootTaskId)
            it.writeInt(target)
        }) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> return false to ("outcome unknown: " +
                remoteGiveUpReason("panel task move", txn.waitedMs, txn.submittedBeforeExpiryMs))
        }
        if (reply.code != 0) return false to reply.reason.ifEmpty { "panel task move failed (exit=${reply.code})" }
        // main 方向成功后的 windowing mode 校正（best-effort，不影响回包；
        // 校正不进 release 路径 —— release 是单次收屏，见 [panelTaskRelease]）。
        if (direction == PanelMoveDirection.MAIN) correctWindowingAfterMoveToMain(rootTaskId)
        return true to ""
    }

    /**
     * main 方向搬迁成功后的 windowing mode 校正（best-effort）：
     * `am display move-stack` 把整棵栈搬回主屏后，栈可能仍带着虚拟屏侧的 windowingMode
     * （freeform/多窗形状原样落在主屏上）。命令候选见
     * [RelayShellService.TrustedTasks.windowingFixCommands]：优先 `am start
     * --windowingMode 1 --display 0 --task <root>`（对既有任务归位），部分平台不接受
     * 无 `-n` 的形式，兜底 `wm set-display-windowing-mode -d 0 1`。
     *
     * **best-effort 的边界**：两条候选都失败只记一条 run log，不重试、不改搬迁回包；
     * 预算单独收紧（[PANEL_WINDOWING_FIX_BUDGET_MS]，健康时两条都是亚秒命令）——
     * 校正在搬迁回包**之前**内联跑，预算装不下就如实放弃校正，绝不让它挤爆端点
     * /move 的等待上限把"已成功"的回包等丢。也不在回包前用 dumpsys 复核实际
     * windowingMode —— 校正只是把最常见的不一致（freeform 形状跟回主屏）推回标准
     * 模式，是否校正成功可由下一次任务枚举或用户观察确认，不值得为此增加一次 dumpsys 开销。
     * virtual 方向不校正（目标屏就是要保持的虚拟屏）。
     */
    private suspend fun correctWindowingAfterMoveToMain(rootTaskId: Int) {
        val budget = CallBudget(PANEL_WINDOWING_FIX_BUDGET_MS, CAP_PANEL_TASK)
        for (command in RelayShellService.TrustedTasks.windowingFixCommands(rootTaskId)) {
            if (run(command, budget) is BackendResult.Ok) return
        }
        runLog.info(
            LogSubsystem.BACKEND,
            LogEvent.REMOTE_SUBMITTED,
            "cap" to CAP_PANEL_TASK,
            "waitMs" to ("move windowing fix: task=$rootTaskId all candidates failed"),
        )
    }

    /**
     * 释放虚拟屏（面板三动作之三）。**停流不在这里**：流会话归 [TrustedStreamController]，
     * 控制端点的 /release 先经它把会话按既有通路收干净（读端、loopback 服务、远端 stop、
     * 诊断读数一个不少），再走这里；这里只收屏 —— 一次显式 release（[releaseDisplay]，
     * 服务端做 `VirtualDisplay.release` 并关 reader/停泵），屏上任务随
     * `DESTROY_CONTENT_ON_REMOVAL` 关闭（createFlags 不变），DSHBox 保持前台。
     *
     * **不要恢复"枚举任务 → move-stack → release → force-stop"
     * 的 move-first 顺序** —— Android 17 上 move 与 release 的 BLAST 过渡重叠会把
     * system_server 打出 NPE；单次 release 由 AOSP 在收屏时 finish 屏上 Activity/移除
     * task，语义就是"关闭虚拟屏上的应用"。失败如实回 reason（可重试）。
     */
    suspend fun panelTaskRelease(): Pair<Boolean, String> {
        // 单次 release（PanelReleaseSemanticsTest 对这段函数体做结构钉：只有这一次调用）。
        return panelReleaseOutcome(releaseDisplay(budget = null))
    }

    /** 面板旋转的调用预算：一次对账 + 一次 resize 事务，按秒级给。 */
    internal fun panelRotateSize(width: Int, height: Int): Pair<Int, Int> =
        height to width  // 90°：宽高交换；dpi 由服务端按记下的当前值原样带上。

    /**
     * 旋转虚拟屏（面板三动作之外的第四动作）：交换宽高走 [ShellProtocol.CODE_DISPLAY_RESIZE]
     * —— 服务端只 `VirtualDisplay.resize` + 换 Surface，**不 release/不重建**，屏上任务
     * 保留。dpi 用建屏时记下的当前值（服务端 `TrustedDisplay.currentDpi`，服务端自己夹）。
     * 流会话由控制端点在调用前先停（换 reader 会中断取帧），这里只管屏本体。
     *
     * 回 (ok, payload)：ok=true 时 payload 是 `{"width":…,"height":…}` JSON（新尺寸）。
     */
    suspend fun panelRotate(): Pair<Boolean, String> {
        requireDisplay() ?: return false to (noDisplay().reason ?: "no trusted display")
        val budget = CallBudget(PANEL_TASK_BUDGET_MS, CAP_PANEL_TASK)
        // 先对账：屏可能在上一刻被收掉/换掉，旧宽高旋转出一个不存在的屏毫无意义。
        // 对账失败（本地放弃等待）不用旧缓存发 resize —— 旧尺寸可能已经不对。
        if (!refreshDisplay(budget)) {
            return false to "panel rotate: display reconciliation failed, retry later"
        }
        val currentDisplay = requireDisplay() ?: return false to (noDisplay().reason ?: "no trusted display")
        val (width, height) = displaySize
        if (width <= 0 || height <= 0) {
            return false to "panel rotate: current display size is unknown ($width x $height); recreate the display first"
        }
        // 90°：宽高交换。dpi 不由宿主给 —— 服务端用建屏时记下的当前值（currentDpi）。
        val newWidth = height
        val newHeight = width
        val reply = when (val txn = displayTransaction(
            budget.cap,
            ShellProtocol.CODE_DISPLAY_RESIZE,
            budget.remoteWaitMs(TASKS_MOVE_WAIT_MS, REMOTE_MARGIN_MS)
                ?: return false to "panel rotate: local budget cannot fit one roundtrip",
        ) {
            it.writeInt(newWidth)
            it.writeInt(newHeight)
            it.writeInt(-1)  // dpi 占位：服务端用 currentDpi（-1 时夹取不改语义）
        }) {
            is RemoteTxn.Done -> txn.value
            is RemoteTxn.GaveUp -> return false to ("outcome unknown: " +
                remoteGiveUpReason("panel rotate", txn.waitedMs, txn.submittedBeforeExpiryMs))
        }
        if (reply.code != 0) return false to reply.reason.ifEmpty { "panel rotate failed (exit=${reply.code})" }
        // 回执不带新尺寸正文（服务端只回原因）；新尺寸就是交换后的这两个数。
        return true to JSONObject().put("width", newWidth).put("height", newHeight).toString()
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
        // 取帧之前认一次那块屏此刻停在谁家；读不到按 fail-closed 拒。
        protectedDisplayGuard(display, budget)?.let { return it }
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
        // 录屏之前认一次那块屏此刻停在谁家；读不到按 fail-closed 拒。
        protectedDisplayGuard(display, budget)?.let { return it }
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
        // 对账的两端都有资格作废那条拒绝记录：屏查出来活着，说明拒绝已不是当前事实
        // （重连后的用户服务里屏还在）；屏查出来没了，说明这块屏确实是建不出来的。
        // 清除与否由对账后的真值变化说了算：true→false 时说给装配根一声，能力清单
        // 立即回到真实状态，不等下一次巡检。
        if (reply.code >= 0 && trustedDisplayDenied) {
            trustedDisplayDenied = false
            deniedListener?.invoke()
        }
        if (reply.code < 0) displaySize = 0 to 0
        else DISPLAY_SIZE.find(reply.reason)?.let {
            displaySize = it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        return true
    }

    @Volatile
    private var displaySize: Pair<Int, Int> = 0 to 0

    private data class DisplayReply(val code: Int, val reason: String, val descriptor: android.os.ParcelFileDescriptor?)

    // ── 实时流（只读预览）的两条宿主侧事务 ──
    //
    // 管道由宿主创建：写端经 binder dup 给用户服务（编码器往里写 fMP4），读端留给
    // 本进程的 loopback HTTP 服务按连接转发。不落任何文件、不走文本通道 —— 与截图
    // 同一条铁律：屏上的内容不进任何一条别家读得到的路径。

    /** 流事务的回执三态，与 [CommandTxn] 同一口径：没送出去 / 送出后结局未知 / 拿到回执。 */
    sealed class StreamTxn {
        object NotSent : StreamTxn()

        /** 递交了但没等到回执（或本地放弃等待）：会话可能已开，也可能没有。 */
        data class Submitted(val note: String) : StreamTxn()

        /** 远端回话：status<0 时 note 是原因；status==0 时 note 是元数据 JSON（start）。 */
        data class Replied(val status: Int, val note: String) : StreamTxn()
    }

    /** 有没有一条"可能还活着"的流会话。回执丢失时保守按有算，stop 是幂等的。 */
    @Volatile
    private var streamSessionLikely = false

    /**
     * 开流：宿主建 pipe，把写端递给用户服务，回执带元数据；读端回给调用方。
     *
     * 回执丢失（[StreamTxn.Submitted]）或本地放弃等待时**两端都关**：若远端已收到
     * 写端并开跑，本模块这头读端一关，它的下一次 write 就 fail（EPIPE），会话沿自己的
     * 退出路径释放 —— 管道的两端各自死，不会留半开的流。状态机里那笔
     * [streamSessionLikely] 只在拿到 0 回执时记 true。
     */
    suspend fun streamStart(waitMs: Long, cap: String = CAP_STREAM): Pair<StreamTxn, android.os.ParcelFileDescriptor?> {
        // 连 pipe 都建不出来时什么都没发生：按 NotSent 答，不冒充"递交了结局未知"。
        val pipe = runCatching { android.os.ParcelFileDescriptor.createPipe() }.getOrElse {
            return StreamTxn.NotSent to null
        }
        val (readEnd, writeEnd) = pipe
        val reply = transactBounded(cap, "stream start", waitMs) {
            rawTransactDisplay(ShellProtocol.CODE_STREAM_START) { data ->
                data.writeFileDescriptor(writeEnd.fileDescriptor)
            }
        }
        // 本端写端副本用完即关（binder dup 给了远端一份）：漏了它，回执失败路径上
        // 就多一个悬空的 fd。
        runCatching { writeEnd.close() }
        return when (reply) {
            is RemoteTxn.Done -> {
                if (reply.value.code == 0) {
                    streamSessionLikely = true
                    StreamTxn.Replied(0, reply.value.reason) to readEnd
                } else {
                    runCatching { readEnd.close() }
                    StreamTxn.Replied(reply.value.code, reply.value.reason) to null
                }
            }
            is RemoteTxn.GaveUp -> {
                runCatching { readEnd.close() }
                StreamTxn.Submitted(remoteGiveUpReason("stream start", reply.waitedMs, reply.submittedBeforeExpiryMs)) to null
            }
        }
    }

    /** 停流（挂起版，走受限执行器）。回执三态同 [streamStart]。 */
    suspend fun streamStop(waitMs: Long, cap: String = CAP_STREAM): StreamTxn {
        if (binder == null) return StreamTxn.NotSent
        val reply = transactBounded(cap, "stream stop", waitMs) {
            rawTransactDisplay(ShellProtocol.CODE_STREAM_STOP)
        }
        return streamTxnOf(reply).also {
            if (it is StreamTxn.Replied && it.status >= 0) streamSessionLikely = false
        }
    }

    /** 停流（阻塞版）：通道收摊/控制器 stop 这类没有协程上下文的路径用。幂等。 */
    fun streamStopBlocking(waitMs: Long, cap: String = CAP_STREAM): StreamTxn {
        if (binder == null) return StreamTxn.NotSent
        val reply = transactBoundedBlocking(cap, "stream stop", waitMs) {
            rawTransactDisplay(ShellProtocol.CODE_STREAM_STOP)
        }
        return streamTxnOf(reply).also {
            if (it is StreamTxn.Replied && it.status >= 0) streamSessionLikely = false
        }
    }

    private fun streamTxnOf(reply: RemoteTxn<DisplayReply>): StreamTxn = when (reply) {
        is RemoteTxn.Done -> StreamTxn.Replied(reply.value.code, reply.value.reason)
        is RemoteTxn.GaveUp ->
            StreamTxn.Submitted(remoteGiveUpReason("stream stop", reply.waitedMs, reply.submittedBeforeExpiryMs))
    }

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

        /** 实时流两条事务在受限等待日志里的 cap 短名（REMOTE_SUBMITTED/REMOTE_GIVE_UP）。 */
        internal const val CAP_STREAM = "stream"

        /** 面板输入的 cap 短名（非 CapabilityId 调用，等待记账/日志用）。 */
        internal const val CAP_PANEL_INPUT = "panel_input"

        /** 面板任务面（列任务/搬迁）的 cap 短名。 */
        internal const val CAP_PANEL_TASK = "panel_task"

        /**
         * 搬迁的 (source, target) 显示映射（纯函数，JVM 可测）：
         * main 方向 source=虚拟屏、target=0；virtual 方向 source=0、target=虚拟屏。
         */
        /**
         * orientation → 最终宽高的**纯映射**（JVM 可测）。
         * `landscape` 且 w&lt;h 交换、`portrait` 且 w&gt;h 交换、null（缺省）不交换 ——
         * orientation 只决定横竖，不改大小；安全尺寸判据仍优先于它。
         */
        internal fun orientSize(width: Int, height: Int, orientation: String?): Pair<Int, Int> = when {
            orientation == null -> width to height
            orientation.equals("landscape", ignoreCase = true) && width < height -> height to width
            orientation.equals("portrait", ignoreCase = true) && width > height -> height to width
            else -> width to height
        }

        /** orientation 参数的形状校验：缺省（无键/空）合法；非白名单值回错误短句。 */
        internal fun orientationError(args: org.json.JSONObject): String? {
            if (!args.has(KEY_ORIENTATION)) return null
            val value = args.optString(KEY_ORIENTATION)
            if (value.isBlank()) return null
            if (ORIENTATIONS.none { it.equals(value, ignoreCase = true) }) {
                return "$KEY_ORIENTATION must be \"portrait\" or \"landscape\" (case-insensitive), got \"$value\""
            }
            return null
        }

        internal fun panelMoveEndpoints(sourceIsVirtual: Boolean, virtualDisplayId: Int): Pair<Int, Int> =
            if (sourceIsVirtual) {
                virtualDisplayId to ShellProtocol.MAIN_DISPLAY_ID
            } else {
                ShellProtocol.MAIN_DISPLAY_ID to virtualDisplayId
            }

        /**
         * panelTaskRelease 的回包映射（纯判据，JVM 直测）—— 单次 release
         * 成功（0/1 回执）如实回 true，失败把服务端 reason 原样带回（没有 reason 才落
         * 错误码名），调用方（端点）按它答 502/200，不加工、不吞。
         */
        internal fun panelReleaseOutcome(released: BackendResult): Pair<Boolean, String> = when (released) {
            is BackendResult.Ok -> true to ""
            is BackendResult.Failed -> false to (released.reason ?: released.error.name)
        }

        /** 面板任务面的调用预算：一问 dumpsys + 可能的一问 move-stack，两条都按秒级给。 */
        private const val PANEL_TASK_BUDGET_MS = 10_000L

        /**
         * main 搬迁后 windowing 校正的专用预算（best-effort，健康时两条候选
         * 都是亚秒命令）。独立于 [PANEL_TASK_BUDGET_MS]：校正内联跑在搬迁回包之前，
         * 预算收紧才不会把端点 /move 的等待上限挤爆。
         */
        private const val PANEL_WINDOWING_FIX_BUDGET_MS = 4_000L

        /** 任务列表事务的远端等待上限（dumpsys 输出秒级）。 */
        private const val TASKS_DUMP_WAIT_MS = 8_000L

        /** 搬迁事务的远端等待上限（同屏搬迁亚秒级，给足余量）。 */
        private const val TASKS_MOVE_WAIT_MS = 8_000L

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
            // 特权通路的键表：command 必填，timeoutMs 可选（缺失走默认；越界由
            // fullShellTimeoutError 与预校验同一张表拒绝，不夹紧，两处同说「拒」）。
            CapabilityId.SYS_SHELL_FULL to ArgSpec(setOf(KEY_COMMAND, KEY_TIMEOUT_MS), setOf(KEY_COMMAND)),
            CapabilityId.APPOPS_SET to ArgSpec(setOf(KEY_PACKAGE, KEY_OP, KEY_MODE), setOf(KEY_PACKAGE, KEY_OP, KEY_MODE)),
            CapabilityId.APP_STOP to ArgSpec(setOf(KEY_PACKAGE), setOf(KEY_PACKAGE)),
            CapabilityId.PKG_INSTALL to ArgSpec(setOf(KEY_UPLOAD), setOf(KEY_UPLOAD)),
            // 建屏的 action 可省，省下来就是 create：`surface.virtual {}` 要能直接回屏号。
            // 把 action 做成必填会让无参调用吃一条 E_TRANSPORT_MALFORMED，
            // 而它没有任何写错的键名可改。
            CapabilityId.SURFACE_VIRTUAL to ArgSpec(setOf(KEY_ACTION, KEY_WIDTH, KEY_HEIGHT, KEY_DPI, KEY_ORIENTATION), emptySet()),

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

        // ── sys.shell.full 的稳定性边界 ──
        /** 特权通路的参数名。command 必填；timeoutMs 可选，越界拒绝（与预校验同表）。 */
        private const val KEY_COMMAND = "command"
        private const val KEY_TIMEOUT_MS = "timeoutMs"

        /** 命令长度上限：脚本与管道都装得下，同时挡住用一段超长正文占住执行通道。 */
        internal const val MAX_FULL_COMMAND_CHARS = 4096

        /** 超时上下限与缺省值。缺省沿用普通命令那一条（15 秒），同样在合法区间之内。 */
        internal const val MIN_FULL_COMMAND_TIMEOUT_MS = 1_000L
        internal const val MAX_FULL_COMMAND_TIMEOUT_MS = 120_000L
        private const val DEFAULT_FULL_COMMAND_TIMEOUT_MS = 15_000L

        /**
         * 一次 shell 执行的原始事实，成功与失败两路同构：output（stdout，管道提前关闭时
         * 是已产出的那部分）、stderr（成功路径也保留，可能是告警）、exit。截断只在服务端
         * 按既有长度上限发生，这里不静默清空。没有 `verified` 之类的语义字段：raw 通路
         * 不宣称任何"写成了"。
         */
        internal fun commandResultData(outcome: RelayShellService.Outcome): JSONObject = JSONObject()
            .put("output", outcome.stdout.trim())
            .put("stderr", outcome.stderr.trim())
            .put("exit", outcome.exitCode)

        /**
         * 特权通路非零退出的纯判据：命令已经跑过、没跑成，回 `E_COMMAND_FAILED`，
         * reason 写明命令串已执行、效果未知或可能部分发生；stderr 带权限拒绝字样时
         * 单独归 `E_COMMAND_NOT_PERMITTED`——那是执行身份与服务侧权限的事，不是宿主
         * 内部错误，也不随重试消失。前置条件是 `exitCode != 0`：`run()` 只在失败出口
         * 调用它，0 不会到达。JVM 可测，真执行路径不可达。
         */
        internal fun fullShellCommandFailureOf(exitCode: Int, stderr: String): Pair<RelayError, String> {
            val detail = "exit=$exitCode $stderr"
            // 只认平台的两处明确签名。裸的 "not allowed" 不算：普通命令自己也常把这句话
            // 打进 stderr（比如 "operation not allowed from shell" 之外的任意拒绝语），
            // 按它分类会把命令自身的失败说成权限问题。
            val refused = "Permission Denial" in stderr || "SecurityException" in stderr
            val error = if (refused) RelayError.COMMAND_NOT_PERMITTED else RelayError.COMMAND_FAILED
            val why = if (refused) {
                ": the command line was handed to /system/bin/sh under the Shizuku identity and " +
                    "the Android service side refused it (Permission Denial / SecurityException): " +
                    "this identity cannot do that - retrying as-is will be refused again"
            } else {
                ": the command line was handed to /system/bin/sh under the " +
                    "Shizuku identity and the shell itself ran; a non-zero exit (127 means the inner command was " +
                    "not found) does not say the command did not run - the effect is unknown or partially done, " +
                    "so check the device state instead of re-running blindly"
            }
            return error to (detail + why)
        }

        /**
         * 超时范围的纯判据：先查原始数字的小数位（optLong 会把 120000.5 静默截成
         * 120000，等于替调用方改参数），再查区间。与预校验（CapabilityArgsSpec）同一张
         * 表——越界拒绝并点名怎么改，不夹紧；后端再夹一次会把预校验拒掉的调用复活。
         */
        internal fun fullShellTimeoutError(raw: Number?, clampedMs: Long): String? = when {
            raw != null && raw.toDouble() % 1.0 != 0.0 ->
                "timeoutMs must be a whole number of milliseconds, got $raw"
            clampedMs !in MIN_FULL_COMMAND_TIMEOUT_MS..MAX_FULL_COMMAND_TIMEOUT_MS ->
                "timeoutMs must be between $MIN_FULL_COMMAND_TIMEOUT_MS and " +
                    "$MAX_FULL_COMMAND_TIMEOUT_MS ms, got $clampedMs"
            else -> null
        }

        /**
         * 「放弃等待后算不算已提交」的历史判据占位：早期曾按"确已递交远端的才算"
         * 设计这一问，现改为 GaveUp 一律按已提交（事务未被取消，之后仍可能
         * 被递交执行），两参判据在生产里退役、也无测试使用——保留签名只作语义沿革
         * 备注，不参与任何当前判定（见 [dispatchedAfterGiveUp]）。
         */
        @Suppress("unused")
        internal fun dispatchedAfterGiveUpLegacy(markDispatched: Boolean, submittedBeforeExpiryMs: Long?): Boolean =
            markDispatched && submittedBeforeExpiryMs != null

        /**
         * 事务结局到「已提交」的映射：拒收不算，递交后死亡、结果无法证实与拿到回执都算。
         * [run] 的 Done 分支在生产路径调用它；GaveUp 分支不走这张表——队列未取消的
         * 事务之后仍可能被递交执行，特权通路一律按已提交（UNKNOWN）。
         */
        internal fun dispatchedOnTransaction(markDispatched: Boolean, submitted: CommandTxn?): Boolean =
            when (submitted) {
                null, is CommandTxn.NotSent -> false
                is CommandTxn.Submitted, is CommandTxn.Replied -> markDispatched
            }

        /** 非特权调用方的既有回答：错误码与原因都保持特权通路接入之前的口径。 */
        private const val LEGACY_BINDER_UNAVAILABLE_REASON = "binder unavailable"

        /** GaveUp 的「已提交」口径：事务未被取消，之后仍可能被递交执行——一律按已提交。 */
        internal fun dispatchedAfterGiveUp(markDispatched: Boolean): Boolean = markDispatched

        /**
         * 命令串的纯判据：空白即缺参，超出长度上限即拒。抽出成纯函数是为了能在
         * 无设备环境下把边界穷举掉——真执行路径依赖 Shizuku 用户服务，JVM 上跑不了。
         */
        internal fun fullShellCommandError(command: String): String? = when {
            command.isBlank() -> "missing arg: $KEY_COMMAND"
            command.length > MAX_FULL_COMMAND_CHARS ->
                "command too long: limit is $MAX_FULL_COMMAND_CHARS characters, got ${command.length}"
            else -> null
        }

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
         * 建屏失败回包（code&lt;0）的三态判定，纯判据供 JVM 单测穷举：
         *
         * - 屏「**问不成**」（[RelayShellService.TrustedDisplay.CODE_UNVERIFIED]）：屏可能还在跑，
         * 返回 null——调用方按可重试的后端不可用处理，既不清编号也不报拒绝；
         * - **系统当场拒绝**（[RelayShellService.TrustedDisplay.CODE_SYSTEM_DENIED]）：返回
         * `systemDenied=true`——这台机器上的稳定结论，调用方置拒绝标记并回
         * E_SURFACE_TRUSTED_DENIED；
         * - **其余负值**（旧屏收不掉、建屏内部异常等瞬时失败）：返回
         * `systemDenied=false`——按通路级错误如实报，下一趟还能再试，不写拒绝标记。
         */
        internal fun createRejection(repliedCode: Int): CreateVerdict? = when (repliedCode) {
            RelayShellService.TrustedDisplay.CODE_UNVERIFIED -> null
            RelayShellService.TrustedDisplay.CODE_SYSTEM_DENIED -> CreateVerdict(systemDenied = true)
            else -> CreateVerdict(systemDenied = false)
        }

        /** 建屏失败回包的处置档，见 [createRejection]。 */
        internal data class CreateVerdict(val systemDenied: Boolean)

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

        /** 面板输入的调用预算：`input` 命令通常 <100ms，给足一次往返的余量。 */
        private const val PANEL_INPUT_BUDGET_MS = 5_000L

        /**
         * 建屏后自检事务的本地等待上限：服务端在事务里串着窗口模式与
         * IME 兜底加两轮主屏快照（各是几条 dumpsys），秒级到十秒级都可能。预算装不下
         * 这一问时自检按 unknown 记录、不把建屏判死（见 [selfCheckAfterCreate]）。
         */
        private const val SELF_CHECK_TXN_WAIT_MS = 15_000L

        /** 运行日志里「没读到」的统一字面量；真读数不可能是它。 */
        internal const val UNKNOWN_FIELD = "unknown"

        /** 拒绝事件的层级标记：执行场景（下发之前）。 */
        internal const val BLOCKED_AT_EXECUTION = "execution"

        /**
         * 从 `dumpsys activity activities` 的输出里取**指定显示**的顶层活动包名。
         * 只认段首（`Display #N`）之后的第一条 `topResumedActivity` 行，与逐显示解析
         * 同一套正则（[DISPLAY_SECTION]/[TOP_ACTIVITY]）。读不到回 null。
         */
        internal fun topPackageFromDump(dump: String, display: Int): String? {
            var section = -1
            for (line in dump.lineSequence()) {
                val trimmed = line.trim()
                DISPLAY_SECTION.find(trimmed)?.let { section = it.groupValues[1].toInt() }
                if (section == display) {
                    TOP_ACTIVITY.find(trimmed)?.let { return it.groupValues[1] }
                }
            }
            return null
        }

        /**
         * trusted 屏上各能力的支持真值表（复核，纯判据供 JVM 穷举）。
         *
         * unsafe **只封后台落点，不封显式恢复**：`surface.virtual`（create/release/query）
         * 始终可达——create 是复检与解除 unsafe 的唯一活通路，release 是清残留屏的通路；
         * 封了它， 里的死锁就成立（unsafe 永远清不掉）。五条后台操作在 unsafe 时一律
         * false——含「release 失败、屏还残留」的场景（[displayAlive] 仍 true，unsafe 是
         * 唯一能把它们摁回前台的闸）。
         */
        internal fun trustedSurfaceSupported(
            capability: CapabilityId,
            surface: SurfaceKind,
            displayAlive: Boolean,
            unsafeMarked: Boolean,
        ): Boolean {
            if (surface != SurfaceKind.BACKEND_TRUSTED) return false
            return when (capability) {
                CapabilityId.SURFACE_VIRTUAL -> true
                CapabilityId.APP_LAUNCH,
                CapabilityId.UI_TAP,
                CapabilityId.UI_SWIPE,
                CapabilityId.UI_TEXT,
                CapabilityId.UI_KEY,
                CapabilityId.SCREEN_CAPTURE,
                CapabilityId.SCREEN_RECORD,
                -> displayAlive && !unsafeMarked
                else -> false
            }
        }

        /**
         * release 回执是否解除 unsafe（复核，  后语义收紧为"服务端可
         * 确认没有残留屏才清"，纯判据）：服务端答 0/1 = 屏已确认收掉（或本无屏——服务端
         * [RelayShellService.TrustedDisplay.release] 失败时保留引用并回负码，不会冒充 0），
         * 残留场景排除，标记解除；负值（收屏失败）与本地放弃等待（占位码
         * [RELAY_CODE_GAVE_UP]）都**不解除**——屏可能还在，unsafe 继续挡着后台落点。
         *
         * 宿主侧不猜：0/1 只在服务端回包是收屏事实时到达；如果未来需要进一步区分
         * "确实没收"与"没等到回执"，由服务端回包显式字段承担，宿主不再从码位推断。
         */
        internal fun releaseClearsUnsafe(repliedCode: Int): Boolean = repliedCode >= 0

        /** 服务端 selfCheck 回包的 `selfCheck` 字段三档字面量（协议的一部分，宿主按它分支）。 */
        internal const val SELF_CHECK_PASS = "pass"
        internal const val SELF_CHECK_UNSAFE = "unsafe"
        internal const val SELF_CHECK_UNVERIFIED = "unverified"

        /** 自检没读到证据时的占位标记：violations 里至少要有它，不伪装成空。 */
        internal const val UNVERIFIED_MARK = "UNVERIFIED"

        /**
         * 建屏后自检能否解除 unsafe（纯判据 JVM 测试穷举）：**只有
         * `selfCheck == "pass"` 且 `violations` 为空**才允许清 `trustedDisplayUnsafe`。
         * `unverified`（无任何判定证据）一律不清——它在普通建屏场景只是"屏保留、标记
         * 动不了 pass 的场景"，在残留屏恢复场景按 [unverifiedIsFailure] 判失败收场
         * 没读到/畸形的 `selfCheck` 值一并按 `unverified` 口径处理，
         * 不给伪装 pass 的口子。
         */
        internal fun selfCheckClearsUnsafe(level: String?, noViolations: Boolean): Boolean =
            level == SELF_CHECK_PASS && noViolations

        /**
         * 自检验不出主屏健康时**算不算失败**（纯判据 JVM 测试穷举，
         * 宿主 [ShizukuBackend.selfCheckUnverifiedFails] 与 JVM 测试同走）：
         *
         * - `requireHealthProof=true`（残留屏恢复新建：刚确认收掉过一块 unsafe 残留屏）：
         * **恒 true**——无 violations 的 `unverified`（服务端写 unverified、自检事务
         * 拿不到、正文畸形）一律按失败收场（E_SURFACE_TRUSTED_UNSAFE，reason 写清
         * "刚清掉一块 unsafe 残留屏，新建的屏没有健康证明"）；violations 非空 /
         * selfCheck=unsafe 本来就按失败收场（[selfCheckUnverifiedFails] 之外的那一支）。
         * - `requireHealthProof=false`（普通 create）：`unverified`（unverified=true 且
         * violations 为空）**不算失败**——屏保留、不动标记、不伪装 pass，语义与
         * 之前一致；violations 非空或 selfCheck=unsafe 仍然算失败。
         */
        internal fun unverifiedIsFailure(
            requireHealthProof: Boolean,
            unverified: Boolean,
            violations: List<String>,
        ): Boolean = when {
            requireHealthProof -> true
            !unverified -> true // selfCheck 写 unsafe：失败。
            violations.isNotEmpty() -> true // 复采仍在 / RESAMPLE_UNVERIFIED：失败。
            else -> false
        }

        /**
         * unsafe 失败回包 data 的纯构造（生产出口与 JVM 测试同走）：`selfCheckFailed` 是
         * 机器判读位（与 E_SURFACE_TRUSTED_DENIED 的拒绝场景分开），display/violations
         * 供调用侧与用户对账。
         */
        internal fun unsafeFailureData(display: Int, violations: List<String>): JSONObject = JSONObject()
            .put("selfCheckFailed", true)
            .put("display", display)
            .put("violations", org.json.JSONArray(violations))

        /**
         * 四条显示事件的字段名集合，与各模板占位符逐个相等
         * （StageEventDictionaryTest 拿它们对账，字段漂移会当场报出）。
         */
        internal val DISPLAY_FLAGS_FIELDS = setOf("display", "sdk", "trusted", "flags", "windowingOk", "imePolicyOk")

        internal val DISPLAY_MAIN_STATE_FIELDS = setOf(
            "display", "beforeTop", "afterTop", "beforeTask", "afterTask",
            "beforeFocus", "afterFocus", "beforeIme", "afterIme", "launcher", "displayGroup", "coverage",
        )

        internal val DISPLAY_SELF_CHECK_FIELDS = setOf("display", "violations")

        internal val DISPLAY_TRANSIENT_FIELDS = setOf("display", "recovered")

        internal val DISPLAY_RELEASE_FIELDS = setOf("display", "released", "afterTop", "afterFocus", "afterIme")

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
        /** 建屏方向（portrait/landscape，大小写不敏感；缺省不交换）。 */
        private const val KEY_ORIENTATION = "orientation"
        /** orientation 的白名单（orientSize/形状校验共用；大小写不敏感比对）。 */
        internal val ORIENTATIONS = setOf("portrait", "landscape")
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

        /**
         * 安全建屏尺寸判据的单边上限，与显示服务端的夹紧上限（`RelayShellService`
         * 的 `MAX_SIDE`）同值：跨层两处各写一个字面量，只以注释里的这句对齐声明背书，
         * 不跨模块引用各自的私有常量。
         */
        internal const val MAX_SAFE_SIDE = 2560

        /**
         * 收屏回包里 `inputStateRestored` 的三档口径：布尔 `true`/`false` 是**可确认**
         * 的两档真值，字符串 [INPUT_STATE_UNKNOWN] 是"证据不支持任何结论"的一档，
         * 不借布尔给一个没证实的答案。字面量是协议的一部分，助手脚本按它分支。
         */
        internal const val INPUT_STATE_UNKNOWN = "unknown"

        /**
         * 建屏拒绝回包里 `sizePolicy` 的"无安全尺寸"取值。data 的结构化判据
         * （配 `physicalShortSide`/`physicalLongSide` 两枚整数）是机器读的第一来源，
         * 同义结论在 reason 里的那句只作人读补充，机器判读不得以 reason 正则替代它。
         */
        internal const val SIZE_POLICY_NONE_SAFE = "none_safe"

        /**
         * release 本地放弃等待时传入 [releaseInputState] 的占位码：它不是服务端回执，
         * 只表示"这一次的结局未知"。取负数避免与服务端正编号回执冲突。
         */
        internal const val RELAY_CODE_GAVE_UP = -99

        /**
         * release unknown 档的 note 原文，按服务端回执分两支：`RELEASE_NOTE_RELEASED`
         * （回执 1，真收掉了——但收屏回执证实不了手势是否回到用户屏上）与
         * `RELEASE_NOTE_NOT_PRESENT`（回执 0，本无屏——空闲到点或上一次已收过，
         * 不写"已释放"，无事可做）。两者都不宣称"已恢复"，重启都是唯一兜底。
         */
        internal const val RELEASE_NOTE_RELEASED =
            "the trusted display is released, but whether the system gestures on the " +
                "user's own screen are back cannot be confirmed from this reply; if gestures " +
                "or the back button is still broken, reboot the device once"

        internal const val RELEASE_NOTE_NOT_PRESENT =
            "the host had no trusted display to release (it was freed earlier or by an earlier " +
                "release), so nothing was torn down now; whether the system gestures on the " +
                "user's own screen are back cannot be confirmed from this reply; if gestures " +
                "or the back button is still broken, reboot the device once"

        /**
         * release 失败三档的**纯判据**（生产 [releaseOutcome] 与 JVM 测试同走）：
         * [RELAY_CODE_GAVE_UP]（本地放弃等待）→ [ReleaseInputState.UNCONFIRMED_GAVE_UP]；
         * 0/1（服务端没事可做/真收掉）→ [ReleaseInputState.UNCONFIRMED_ACKNOWLEDGED]；
         * 其余负值（收屏失败、旧屏收不掉）→ [ReleaseInputState.RESTORED_FALSE]——
         * 屏还在持有输入焦点。回话的两档输入状态都**不可确认**。
         */
        internal fun releaseInputState(repliedCode: Int): ReleaseInputState = when {
            repliedCode == RELAY_CODE_GAVE_UP -> ReleaseInputState.UNCONFIRMED_GAVE_UP
            repliedCode < 0 -> ReleaseInputState.RESTORED_FALSE
            else -> ReleaseInputState.UNCONFIRMED_ACKNOWLEDGED
        }

        /**
         * release 三个出口到回包的**唯一映射**（生产与测试同走，internal 供 JVM 直接
         * 断言生产 data/错误码，测试不再自造 JSON）：本地放弃等待
         * （[RELAY_CODE_GAVE_UP] 占位码）回 **失败**（E_TRANSPORT_TIMEOUT + 调用方带入
         * 的放弃原因，data 只带 unknown——事务没有被取消，屏可能已被收掉也可能稍后
         * 才会，无 released 也无 note，更不是"已释放"）；服务端回复非零（收屏失败）
         * 回**布尔 false**；回复 0/1（本无屏/真收掉）回成功 + unknown + 按回复分档的
         * note（0 档不写"已释放"）。三档的字段类型与取值都没有 true 这一档。
         */
        internal fun releaseOutcome(repliedCode: Int, replyReason: String): BackendResult =
            when (releaseInputState(repliedCode)) {
                ReleaseInputState.RESTORED_FALSE -> BackendResult.Failed(
                    RelayError.BACKEND_UNAVAILABLE,
                    replyReason,
                    data = JSONObject().put("inputStateRestored", false),
                )
                ReleaseInputState.UNCONFIRMED_GAVE_UP -> BackendResult.Failed(
                    RelayError.TRANSPORT_TIMEOUT,
                    replyReason,
                    data = JSONObject().put("inputStateRestored", INPUT_STATE_UNKNOWN),
                )
                ReleaseInputState.UNCONFIRMED_ACKNOWLEDGED -> BackendResult.Ok(
                    data = JSONObject()
                        .put("released", repliedCode == 1)
                        .put("inputStateRestored", INPUT_STATE_UNKNOWN)
                        .put(
                            "note",
                            if (repliedCode == 1) RELEASE_NOTE_RELEASED else RELEASE_NOTE_NOT_PRESENT,
                        ),
                )
            }

        /**
         * 无安全尺寸失败回包 data 的**纯构造**（生产 deny 出口与 JVM 测试同走）：
         * 三枚键逐字段写着——`sizePolicy`（[SIZE_POLICY_NONE_SAFE]）与按短/长边
         * 命名的两枚物理边长，测试断言的是生产 data 本身这三枚的字段名与值。
         */
        internal fun noneSafeData(physicalShortSide: Int, physicalLongSide: Int): JSONObject = JSONObject()
            .put("sizePolicy", SIZE_POLICY_NONE_SAFE)
            .put("physicalShortSide", physicalShortSide)
            .put("physicalLongSide", physicalLongSide)

        /**
         * 大屏/平板的安全建屏尺寸，纯判据供 JVM 单测穷举。大屏场景（物理 3200×2136 的
         * 平板）是依据：按完整两边递进后服务端把短边夹到区间内，建出的屏比例变形，
         * 而宽 2560×高 2136 建屏后释放可恢复系统手势。方向不写死：两参内部按大小
         * 归一，调用方传 (长, 短) 或 (短, 长) 落同一判据，竖用、横用都一致。
         *
         * - 两侧都在上限之内（含读不到的 0）：[SafeDisplaySize.KEEP_EXISTING]——
         * 没有证据不替设备改尺寸，旧行为不动；
         * - 长边越上限、短边不越：宽取长边截到上限、高取短边——两数都落在显示
         * 服务端的夹紧区间内，不会再变形；
         * - 短边本身也越上限：任何截法都把一边截得不成比例，[SafeDisplaySize.NONE_SAFE]。
         */
        internal fun safeDisplaySize(firstSide: Int, secondSide: Int): SafeDisplaySize {
            val shortSide = minOf(firstSide, secondSide)
            val longSide = maxOf(firstSide, secondSide)
            return when {
                shortSide <= 0 || (shortSide <= MAX_SAFE_SIDE && longSide <= MAX_SAFE_SIDE) ->
                    SafeDisplaySize.KEEP_EXISTING
                shortSide <= MAX_SAFE_SIDE && longSide > MAX_SAFE_SIDE ->
                    SafeDisplaySize.Override(width = MAX_SAFE_SIDE, height = shortSide)
                else -> SafeDisplaySize.NONE_SAFE
            }
        }
        private const val DEFAULT_SWIPE_MS = 300
        private const val MAX_SWIPE_MS = 3_000
        private const val MAX_TEXT_CHARS = 500
        private const val LAUNCH_TIMEOUT_MS = 15_000L

        /** 落点沿用的顶层核对预算：一次 dumpsys 的量，不挤占调用的执行预算。 */
        private const val TRUSTED_TOP_CHECK_BUDGET_MS = 2_000L
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

/**
 * 把一次启动的落点观察如实写进回包数据（纯函数）。
 *
 * 观察结果分开说，因为助手对它们的后续动作各不相同：落在目标屏 = 成功；目标屏上
 * 停着别的活动 = 只报"看到的是什么"，不替它编一个结论；**目标包停在用户真屏上 =
 * `grabbedUserScreen`，无论启动前用户是否已经在它上面** —— 前者是这次启动造成的抢屏，
 * 后者是用户本来就在：两种都要标真，否则"目标就在用户眼前"会被误读成
 * "后台面给不了这个落点"，调用侧就可能再发一次前台启动。整次观察没成 =
 * `landingCheck:"failed"`，既不是成功也不是失败。
 *
 * 需要 `topOnTarget` 与 `targetDisplayMoved` 两个量而不是一个包名判等：路由活动、
 * 别名与跨包 app link 会让真正 resume 的活动的包名不等于请求里那个包名。
 */
internal fun writeLandingReport(
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
    // 0 号屏是用户自己的那块（ShizukuBackend.DEFAULT_DISPLAY 的顶层口径）。
    val onUserScreen = seen[0] == packageName
    if (onUserScreen) {
        data.put("grabbedUserScreen", true)
        if (before?.get(0) != packageName) {
            data.put(
                "note",
                "the app came up on the user's own display: it did not stay on the trusted display",
            )
        } else {
            data.put(
                "note",
                "the user was already on this app before the launch, so the trusted display did not take it",
            )
        }
    } else if (moved) {
        data.put(
            "note",
            "something came up on the target display but its top activity is not the requested package " +
                "(a router activity, an alias or a cross-package launch looks like this)",
        )
    }
}

/**
 * JSON 字段的三态读取：缺失或 NULL → null（调用侧落 unknown）；其余取原串。
 *
 * 两种 "null" 必须分开：JSON 的 NULL 是「没读到」，而字符串 `"null"` 是一条真读数
 * ——服务端用它表示主屏焦点被清空（`mFocusedWindow=null`）。前者不是读数，
 * 后者丢了就把「焦点被清」这个破坏读成 unknown。
 */
private fun JSONObject.stringOrNull(key: String): String? {
    val value = opt(key) ?: return null
    if (value === JSONObject.NULL) return null
    return value.toString().takeIf { it.isNotBlank() }
}

/** 字段读不到就落 unknown 字面量：运行日志里「unknown」与真实值永远可直接区分。 */
private fun JSONObject.stringOrUnknown(key: String): String = stringOrNull(key) ?: "unknown"

/** 焦点一问压成 `token@display`：归属读不到时写 @unknown，不假设它属于主屏。 */
private fun JSONObject.focusText(): String {
    val token = stringOrNull("focus") ?: return "unknown"
    return "$token@${stringOrNull("focusDisplay") ?: "unknown"}"
}

/** 布尔字段按文本取：缺失/NULL → unknown（正常只有 true/false，防御性归一）。 */
private fun JSONObject.boolText(key: String): String {
    val value = opt(key)
    return if (value == null || value === JSONObject.NULL) "unknown" else value.toString()
}

/** IME 一问压成 `display/shown` 两半，读不到的那半写 unknown。 */
private fun JSONObject.imeText(): String =
    (stringOrNull("imeDisplay") ?: "unknown") + "/" + (stringOrNull("imeShown") ?: "unknown")

/**
 * 面板输入的**命令串拼装**（纯函数，JVM 可测）：把端点映射好的像素命令拼成
 * `input -d` 的参数段。动作面收窄到 tap/swipe/back/home；back/home 无坐标；
 * swipe 的 duration 缺省用 [interlock.relay.core.exec.stream.PanelInputPolicy.DEFAULT_DURATION_MS]。
 * 未知动作回 null（调用方按失败回话），绝不过 shell 解析。
 */
internal fun buildPanelInputArgs(command: interlock.relay.core.exec.stream.PanelInputPolicy.Command): String? = when (command.action) {
    "tap" -> {
        val x = command.x ?: return null
        val y = command.y ?: return null
        "tap $x $y"
    }
    "swipe" -> {
        val x1 = command.x1 ?: return null
        val y1 = command.y1 ?: return null
        val x2 = command.x2 ?: return null
        val y2 = command.y2 ?: return null
        "swipe $x1 $y1 $x2 $y2 " + (command.durationMs
            ?: interlock.relay.core.exec.stream.PanelInputPolicy.DEFAULT_DURATION_MS)
    }
    "back" -> "keyevent 4"
    "home" -> "keyevent 3"
    else -> null
}

/**
 * 面板输入的**屏内边界复核**（纯函数，JVM 可测）：端点按 metadata 源尺寸映射，
 * 这里对当前 `displaySize` 再核一遍。尺寸未知（≤0）直接拒——"回 ok 但没动"比回错更糟
 * （D：不再 0 尺寸放行）。
 */
internal fun panelInputOutOfBounds(
    command: interlock.relay.core.exec.stream.PanelInputPolicy.Command,
    width: Int,
    height: Int,
): String? {
    if (width <= 0 || height <= 0) return "trusted display size unknown; cannot verify input bounds"
    fun off(x: Int?, y: Int?): String? {
        if (x == null || y == null) return "missing coordinate"
        return if (x < 0 || x >= width || y < 0 || y >= height) {
            "target ($x,$y) is outside the trusted display ${width}x$height"
        } else {
            null
        }
    }
    return when (command.action) {
        "tap" -> off(command.x, command.y)
        "swipe" -> off(command.x1, command.y1) ?: off(command.x2, command.y2)
        "back", "home" -> null
        else -> "unknown panel input action: " + command.action
    }
}
