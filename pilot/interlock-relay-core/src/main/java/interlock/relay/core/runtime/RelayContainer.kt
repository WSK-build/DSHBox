package interlock.relay.core.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import interlock.relay.core.protocol.BackendId
import interlock.relay.core.protocol.RELAY_PROTOCOL_VERSION
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.CapabilityState
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.GrantGap
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.protocol.SystemGrantState
import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.exec.a11y.A11yBackend
import interlock.relay.core.exec.a11y.RelayAccessibilityService
import interlock.relay.core.exec.direct.DirectBackend
import interlock.relay.core.exec.direct.RelayService
import interlock.relay.core.exec.direct.RelayNotificationListener
import interlock.relay.core.exec.direct.ScreenProjection
import interlock.relay.core.exec.shizuku.ShizukuBackend
import interlock.relay.core.exec.shizuku.ShizukuState
import interlock.relay.core.interlock.InterlockBroker
import interlock.relay.core.interlock.InterlockPrompt
import interlock.relay.core.interlock.RelayAskSurface
import interlock.relay.core.interlock.AccessibilityGap
import interlock.relay.core.interlock.InterlockChoice
import interlock.relay.core.interlock.InterlockQueue
import interlock.relay.core.interlock.InterlockChannel
import interlock.relay.core.interlock.InterlockGate
import interlock.relay.core.interlock.RelayA11yWatchdog
import interlock.relay.core.interlock.SettingsIntents
import interlock.relay.core.interlock.SystemStateProbe
import interlock.relay.core.interlock.ShellOptIns
import interlock.relay.core.interlock.ActivePreset
import interlock.relay.core.interlock.ActivePresetStore
import interlock.relay.core.interlock.CustomPresetStore
import interlock.relay.core.interlock.ProtectedAppsStore
import interlock.relay.core.interlock.TierPresets
import interlock.relay.core.interlock.GlobalConfigState
import interlock.relay.core.interlock.TierStore
import interlock.relay.core.log.AuditLog
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.storage.MediaLibraryUsage
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.storage.QuotaLedger
import interlock.relay.core.storage.StorageReaper
import interlock.relay.core.surface.BackendDispatcher
import interlock.relay.core.surface.SurfacePolicy
import interlock.relay.core.surface.TargetLandingTable
import interlock.relay.core.surface.SurfacePreference
import interlock.relay.core.surface.SurfacePreferences
import interlock.relay.core.transport.GuestAssets
import interlock.relay.core.transport.MailboxServer
import interlock.relay.core.transport.ParkedRequest
import interlock.relay.core.transport.ParkedRequestBridge
import interlock.relay.core.transport.RequestStateStore
import interlock.relay.core.spi.RelayCapabilities
import interlock.relay.core.spi.RelayChannelRole
import interlock.relay.core.spi.RelayExecutor
import interlock.relay.core.spi.RelayNotificationChannelSpec
import interlock.relay.core.spi.RelayPathPolicy
import interlock.relay.core.spi.RelayPrefs
import interlock.relay.core.spi.RelayRedactor
import interlock.relay.core.spi.RelaySurfaces
import interlock.relay.core.spi.RelayText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * 记录本应用是否可见，用于两处「要用户在场」的判定：剪贴板读写，和界面内那张确认框
 * 此刻有没有人看得见。
 *
 * 可见性按**所有者**记账而不是单个布尔：助手独立页（PilotActivity）与宿主页签内嵌的
 * 助手页是两个互不知情的写入方，生命周期会交错——宿主 Activity 的 ON_STOP 完全可能
 * 落在新 Activity 的 onResume 之后，单布尔会被后到的那一写把前者的 true 覆盖成 false，
 * 独立页实际在前台，呈现裁决却按后台走。所有者集合下各记各的，任一所有者声称可见
 * 即为前台，两个都退才真正不可见。
 *
 * 已知限制：分屏/多窗口下两个所有者同时可见时，「前台」按真处理，页内卡会在每个
 * 可见的宿主窗口各画一张（当前没有「用户正在交互的那个窗口」判据可依）。两处画的
 * 是同一件事、答案只收一次，不算双入口裁决被绕过；触屏设备上单交互窗口是常态。
 */
class ForegroundTracker {

    private val owners = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 一个所有者的可见性写入口：false 即撤掉该所有者的在席记录，不影响别的所有者。 */
    fun setOwnerVisible(owner: String, visible: Boolean) {
        if (visible) owners.add(owner) else owners.remove(owner)
    }

    /** 任一所有者可见即为前台。 */
    val visible: Boolean
        get() = owners.isNotEmpty()
}

/**
 * agent 通道模块的装配根。所有组件在此组装，模块对外的可插拔面就是这一个对象，
 * 拔除时宿主只需移除对它的引用与运行环境的两条绑定参数。
 *
 * 呈现件、文案、路径、能力表、偏好与脱敏一律走 [RelayConfig] 注入的 SPI，
 * 缺省取 core 内建默认实现（无呈现面、系统语言、内建默认路径、内建全表、SharedPreferences、恒等脱敏）。
 *
 * 生命周期由宿主按沙盒状态驱动，不依赖任何组件的 onDestroy：
 * 该回调不保证执行，而通道与采集资源必须被确定性释放。
 */
class RelayContainer(context: Context, config: RelayConfig = RelayConfig()) {

    private val appContext = context.applicationContext

    /** 文案与语言取用口；core 内默认词条之外的用法一律经它。 */
    val text: RelayText = config.text ?: RelayText.Default(appContext)

    /** 呈现面装配；缺省 [RelaySurfaces.NoSurfaces] 时确认类调用如实回「问不到人」。 */
    val surfaces: RelaySurfaces = config.surfaces ?: RelaySurfaces.NoSurfaces

    private val capabilities: RelayCapabilities = config.capabilities ?: RelayCapabilities.BuiltIn
    private val pathPolicy: RelayPathPolicy = config.pathPolicy ?: RelayPathPolicy.BuiltIn
    private val prefsApi: RelayPrefs = config.prefs ?: RelayPrefs.Default(appContext)
    private val redactor: RelayRedactor = config.redactor ?: RelayRedactor.IDENTITY

    val paths = RelayPaths(appContext.filesDir, pathPolicy)
    val foreground = ForegroundTracker()

    private val verbosePref = { prefsApi.getBoolean(RelayPrefs.FILE_MAIN, KEY_VERBOSE, false) }
    val runLog = RunLog(paths.logsDir, verboseEnabled = verbosePref, redactor = redactor)

    private val ledger = QuotaLedger(paths.quotaFile)
    private val reaper = StorageReaper(paths, ledger, runLog)
    // v2 请求状态：控制通道提交的请求在这里留阶段与终态记录。目录不在沙盒绑定子树内，
    // 里面存着「同一件事」的摘要与可重取的终态回包，不能让沙盒侧改写。
    private val requestStateStore = RequestStateStore(paths.requestStateDir, runLog = runLog)
    private val tiers = TierStore(prefsApi)

    /** 「常用自定义预设」的存储：编辑器保存与「使用」都经由它，保存与套用分两步。 */
    private val customPreset = CustomPresetStore(prefsApi)

    /**
     * 当前生效预设的身份：与 40 条档位、已保存自定义分开持久化。选择器按它显示
     * 当前条目，写入路径各自维护身份（40 条值不再反推）；没有身份的老升级场景
     * 在 [activePresetIdentity] 里按旧判据迁移一次。
     */
    private val activePreset = ActivePresetStore(prefsApi)

    /**
     * 「受保护应用」名单：用户自选、无预设，独立于档位与任何预设，升级不得清空。
     * 调用入口、无障碍与 Shizuku 执行场景、屏幕采集与实时流都从这里取实时读数。
     */
    private val protectedApps = ProtectedAppsStore(prefsApi)

    /** `sys.shell` 里"会改系统"那几条动词的逐条授权，与档位分开存。 */
    private val shellOptIns = ShellOptIns(prefsApi)
    private val surfacePreferences = SurfacePreferences(prefsApi)

    val shizuku = ShizukuState(appContext, runLog)
    private val auditLog = AuditLog(
        auditDir = paths.auditDir,
        digestSalt = digestSalt(),
        reportFailure = { detail ->
            runLog.warn(LogSubsystem.STORAGE, LogEvent.AUDIT_WRITE_FAILED, "detail" to detail)
        },
    )

    private val probe = SystemStateProbe(
        appContext,
        // 三个拆开的读数给 shell 族探测的"三环齐→GRANTED"用：在跑、已授权、用户服务
        // 绑上——三环齐就是就绪（GRANTED），缺哪环按环缺口点名（启动 / 放行 / 重连）。
        // 不再需要"在跑且已授权"的合成值：合成后的一句会把"没授权"说成"没跑"。
        available = { shizuku.available },
        grantedVerified = { shizuku.granted },
        // 那块可信屏在不在，决定 `ui.tap/text/key` 是否还需要无障碍服务。
        trustedDisplayAvailable = { trustedDisplayReady() },
        // 执行侧用户服务的绑定状态：授权齐了还差这一环时，缺口说"点重连"，不说缺授权。
        userBound = { shizukuBackend.boundSnapshot },
    )

    /** 待决确认框。界面订阅它，不必轮询队列。 */
    private val pendingApprovalState = MutableStateFlow<InterlockQueue.Request?>(null)
    val pendingApproval: StateFlow<InterlockQueue.Request?> = pendingApprovalState

    /**
     * 待决项被摊到了哪里、后面还排着几项。
     *
     * 状态区要能写「待确认：当前 1 项（另有 N 项排队）」—— 只知道"有一件在等"的话，
     * 用户答完才发现还有下一张，节奏就从"一次问清楚"退化成"一次一次挤"。
     * 呈现位置同时是页内卡的门禁：界面只在 `via == PAGE` 时画页内卡
     * （判据见 PilotViewModel.approvalCardVisible），悬浮/通知在屏时页内不出现第二处可点。
     */
    private val approvalRoutingState = MutableStateFlow(
        InterlockQueue.Presentation(null, InterlockChannel.NONE, 0),
    )
    val approvalRouting: StateFlow<InterlockQueue.Presentation> = approvalRoutingState

    /**
     * 上一条「谁也问不到」的点名文案。回包侧引用它，让助手知道该去开通知还是先答屏上的卡。
     */
    fun lastApprovalDeadEndReason(): String? = approvalQueue.lastDeadEndReason()

    /**
     * 一次调用被「缺无障碍」挡下时缺的是哪一环。界面订阅它并弹一张说明框：
     * 缺这一条时信箱里只多了一条错误码，用户屏幕上没有任何反馈。
     */
    data class AccessibilityNotice(val gap: AccessibilityGap, val capability: CapabilityId)

    private val accessibilityNoticeState = MutableStateFlow<AccessibilityNotice?>(null)
    val accessibilityNotice: StateFlow<AccessibilityNotice?> = accessibilityNoticeState

    /**
     * 用户已经处理过的那一档缺口。同一档不重复弹：能力清单每秒复查一次，少了这一条，
     * 关掉一次弹框就会在下一秒被重新弹出来。
     * 绑定关系一变化即作废（见 init 里的 onBoundChanged）——条件变了，提示才该再出现。
     */
    @Volatile
    private var silencedGap: AccessibilityGap? = null

    /**
     * 界面能力清单所在的那个线程。能力清单与闸门判定共用同一个探测入口，
     * 少了这个标记，界面每秒一次的复查就会被当成「助手被无障碍挡下」而弹框。
     * [capabilityRows] 全程不挂起，同一线程上不会夹进别处的探测，故按线程标记即准确。
     */
    private val listingThread = ThreadLocal<Boolean>()

    /** 宿主注入的悬浮确认窗通路（含审批悬浮卡与授权入口意图）；无则 null。 */
    private val approvalOverlay get() = surfaces.approvalOverlay()

    /** 宿主注入的审批通知栏呈现器；无则 null（裁决不再考虑通知这条路）。 */
    private val approvalNotifications get() = surfaces.approvalNotification()

    /** 宿主注入的提问呈现面；无则用 core 的兜底实现（问不出口，回「未接线」）。 */
    private val askSurface: RelayAskSurface get() = surfaces.askSurface() ?: RelayAskSurface.Unavailable

    /**
     * 屏幕采集会话（MediaProjection）。direct 后端拿它的帧与录像——系统授权只能由 Activity
     * 发起，采集却来自信箱，这个对象就是两边之间的那一次握手；发起那一趟由
     * [RelayConsentActivity] 跑。
     */
    val screenProjection = ScreenProjection(appContext, reaper, runLog, ::startConsentHost)

    /**
     * 带起那页只用来发系统弹框的透明界面。
     *
     * 一律另起一页而不是复用助手主页：主页在前台时直接发弹框会把用户钉在助手页，
     * 点完确认回不到他原来的界面。
     * Android 10 起后台启动 Activity 默认被系统拦下，持有「显示在其他应用上层」是少数
     * 被放开的例外之一；两条都不满足时直接返回 false，让采集如实失败。
     */
    private fun startConsentHost(): Boolean {
        if (!surfaces.uiInForeground() && approvalOverlay?.canShow() != true) return false
        return runCatching {
            appContext.startActivity(RelayConsentActivity.intent(appContext))
            true
        }.getOrDefault(false)
    }

    /** 按角色取宿主注入的通知渠道配置；缺省回 core 默认值。 */
    private fun notifyChannelSpec(role: RelayChannelRole): RelayNotificationChannelSpec =
        surfaces.notificationChannels().firstOrNull { it.role == role }
            ?: RelayNotificationChannelSpec.defaults().first { it.role == role }

    /** 悬浮窗授权页的宿主入口意图；宿主未提供时为 null，界面退化为文字指引。 */
    fun overlayPermissionIntent(): Intent? = surfaces.overlayPermissionIntent()

    private fun appInForeground(): Boolean = runCatching {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        am.getRunningAppProcesses()?.any { process ->
            process.processName == appContext.packageName &&
                process.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } == true
    }.getOrDefault(false)

    /**
     * 提问悬浮卡这一条此刻必然看不见。
     *
     * 判据是「可信虚拟屏在跑」：那块屏上的应用带着 HIDE_NON_SYSTEM_OVERLAY_WINDOWS，
     * 平台会因此跨屏隐藏本应用的悬浮窗。只服务**提问卡**：它没有第二条通路（没有通知兜底、
     * 也没有页内卡），与其挂一张注定看不见的窗、白等一次焦点判定再回「hidden-by-platform」，
     * 不如直接让位，把可行动的原因交回给助手。
     *
     * 审批悬浮卡**不再使用**这一判据（修正）：审批有通知与页内两条后备通路，且
     * 「屏在」不等于「窗必藏」——是否真被藏掉只有挂上之后拿焦点才验证得了，提前跳过
     * 会把一条看得见的通路整条跳过，用户只能去通知栏里找。审批那条路由
     * [InterlockQueue] 按真实呈现结果裁决（挂不上、拿不到焦点才降级，呈现器留失败日志）。
     *
     * 仅供宿主呈现面（提问卡）装配使用，不得作为绕过队列纪律的入口。
     */
    fun overlayBlind(): Boolean = trustedDisplayReady()

    /** Shizuku 状态。字段是易失变量，界面只有订阅这条流才会在授权回来后刷新。 */
    private val shizukuSnapshot = MutableStateFlow(shizuku.snapshot())
    val shizukuState: StateFlow<ShizukuState.Snapshot> = shizukuSnapshot

    private val approvalQueue = InterlockQueue(
        isForegroundCapable = surfaces::uiInForeground,
        onRouting = ::rerouteApprovalPresentation,
        overlay = approvalOverlay,
        // 改判进悬浮时的兜底挂卡：裁决在队列锁内算出，挂卡是挂起操作，交给进程级
        // 作用域去跑。重复排程无害——串行在队列的互斥门上，后到的进门发现请求已换/
        // 卡已在屏，空手而回。作用域未接或已取消时不执行（通道没在跑，本也没有审批可答；
        // 单驱动模型下没有「在途」标记可卡死）。
        overlayEnsureLauncher = { work -> scope?.launch { work() } },
        canNotify = { approvalNotifications?.canShow() == true },
        // 悬浮被跳过时的点名：授权缺席、本问试砸过、提问卡占屏、
        // 呈现面缺件，每个"为什么没尝试悬浮"都要在运行日志里查得到。
        onOverlaySkipped = { prompt, at, reasons ->
            runLog.warn(
                LogSubsystem.GATE,
                LogEvent.OVERLAY_UNAVAILABLE,
                "cap" to prompt.capability.wire,
                "cause" to reasons,
                "at" to at,
            )
        },
        // 提问卡在屏时审批不去叠第二张卡（提问卡没有第二条通路，被盖住就只剩等到超时）；
        // 反方向由提问呈现器自己判（审批卡/页内卡在屏时它回忙），两条合起来才是"同屏只允许一张卡"。
        otherCardOnScreen = { askSurface.isPresented() },
        onRevokePresenters = ::revokeApprovalPresenters,
    )

    private val broker = InterlockBroker(approvalQueue, runLog)
    private val gatekeeper = InterlockGate(tiers, broker, runLog, onSessionGrant = ::scheduleRefresh)

    private val directBackend = DirectBackend(
        appContext,
        reaper,
        // 剪贴板可用性由应用界面可见性或自身进程前台状态判断，不限于助手页。
        clipboardUsable = { surfaces.uiInForeground() || appInForeground() },
        projection = screenProjection,
        // 自指启动（收尾回宿主页）的判据：已经在里面就别再启动一次，否则 singleTask
        // 会把助手页顶掉，用户反而被赶回主界面。
        hostInFront = { surfaces.uiInForeground() || appInForeground() },
        notifyChannel = notifyChannelSpec(RelayChannelRole.NOTIFY_POST),
        text = text,
        // 媒体库写入落点与占用对账（MediaLibraryUsage）共用同一个相册目录名：策略是唯一事实源。
        mediaAlbumDir = pathPolicy.mediaAlbumDir,
        // 前台启动与屏幕采集下发前的受保护名单判定，读实时名单。
        protectedPackages = { protectedApps.packages() },
        // 本后端执行场景的名单拒绝记一条运行日志（事件与字段同 Shizuku 后端）。
        runLog = runLog,
    )

    private val a11yBackend = A11yBackend(
        appContext,
        reaper,
        // 节点级那十一条要能在可信虚拟屏上既读又写，就得知道那块屏此刻的编号。
        // 后端之间互不可见是本模块的既有约束，这个数只能由装配根递进来。
        trustedDisplayId = { if (trustedDisplayReady()) shizukuBackend.displayId else -1 },
        // 控件树读写与坐标/截图下发前的受保护名单判定，读实时名单。
        protectedPackages = { protectedApps.packages() },
        // 本后端执行场景（含滚动逐轮守卫）的名单拒绝记一条运行日志。
        runLog = runLog,
    )
    private val shizukuBackend = ShizukuBackend(
        shizuku,
        runLog,
        appContext,
        reaper,
        // 命令/取帧下发之前的受保护名单双保险，读实时名单。
        protectedPackages = { protectedApps.packages() },
    )

    /**
     * 流会话诊断读数的写入口：把「一次预览会话走到哪一步、结论如何、已经写出多少
     * 字节」写进运行日志（手机助手「运行日志」页可见）。
     *
     * 控制器与控制端点**共用这一个实例**：两侧的读数因此带同一个宿主侧会话序号，
     * 能对到同一场会话上。写日志这一侧由装配方注入，控制器与端点都不直接写日志。
     */
    private val streamSessionLog = interlock.relay.core.exec.stream.RunLogStreamSessionLog(runLog)

    /**
     * 实时流的宿主侧装配：编码在 Shizuku 用户服务进程，loopback HTTP 服务在
     * 本进程。start/stop 由宿主（后续的面板半边）调用；通道停机时随 [stop] 收掉，
     * 用户服务死亡则由 pipe 断流与设备侧自身的退出路径兜底。
     */
    val trustedStream = interlock.relay.core.exec.stream.TrustedStreamController(
        interlock.relay.core.exec.stream.ShizukuStreamDevice(shizukuBackend),
        // 数据面守卫（**恢复**）：受保护应用在虚拟屏前台时不提供预览/输入。
        // 面板豁免 仅限能力路径的 UI 操作，不延伸到流/输入入口——tabToken 只是
        // 会话归属凭据（多 tab 不串），不是沙盒隔离边界；要放行需宿主侧用户确认或
        // 认证中继（未实现）。
        protectedPackages = { protectedApps.packages() },
        topPackage = { shizukuBackend.trustedTopPackageBlocking() },
        // 开流与流中的名单拒绝记一条运行日志。
        runLog = runLog,
        // 流会话诊断读数：开流成败、收场原因与已写出的字节数。
        sessionLog = streamSessionLog,
    )

    /**
     * 实时流的**控制端点**：仅 loopback + 随机控制 token，start/stop/status
     * 与 D 的 /input；DSH 的 Node 半经 rendezvous 文件（通道 run/ 下）拿到端口与
     * token 后调用。端点随通道起停；绑定失败只降级（记 STREAM_CONTROL_UNAVAILABLE、
     * 不写 rendezvous），不拖垮整条通道。
     */
    /** D：面板输入专用工人池；通道 stop 后下一次输入自动重建（容器可重启复用）。 */
    private val panelInputWorkers = interlock.relay.core.exec.stream.PanelInputWorkers(2)

    private val streamControl = interlock.relay.core.exec.stream.TrustedStreamControlEndpoint(
        trustedStream,
        sessionLog = streamSessionLog,
        // D：面板输入（tap/swipe/back/home）落到 Shizuku 后端的低层 input 事务，
        // 不走 gate/档位/审批（鉴权在控制 token + 会话归属 tabToken + 会话存活）。
        // D：阻塞事务跑在**专用 2 线程池**上，执行口用 suspendCancellableCoroutine
        // 挂起——端点的 withTimeoutOrNull 到点能真返回（回"结局未知"），**不中断远端事务**；
        // worker 继续跑完、结果丢弃。注意：端点侧的 runBlocking 仍占端点 serve 池的一个
        // 工人，并发输入由本池限到 2 条、其余在池里排队。
        panelInput = { request ->
            panelInputWorkers.execute {
                // 专用池工人上 runBlocking 是有意的：阻塞事务只占本池工人，
                // 端点的超时取消的是"等待"（suspendCancellableCoroutine 到点返回），
                // 这里的 runBlocking 跑完即止，结果因协程已取消而被丢弃。
                try {
                    kotlinx.coroutines.runBlocking { shizukuBackend.panelInput(request) }
                } catch (broken: Throwable) {
                    false to ("panel input error: " + broken.javaClass.simpleName)
                }
            }
        },
        // 面板任务面（列任务/搬迁/释放）落 Shizuku 后端的低层事务；停流由端点
        // 自己走 [trustedStream] 的既有收场通路，再进这里的 release 收屏。
        // 搬迁方向决定 source/target：main = 虚拟屏→主屏（source 列虚拟屏认根），
        // virtual = 主屏→虚拟屏（source 列 display 0 认根，"移回"不再恒失败）。
        panelTasks = object : interlock.relay.core.exec.stream.TrustedStreamControlEndpoint.PanelTaskOps {
            override suspend fun list(sourceIsVirtual: Boolean): Pair<Boolean, String> =
                shizukuBackend.panelTaskList(sourceIsVirtual)

            override suspend fun move(taskId: Int, direction: String): Pair<Boolean, String> {
                val moveDirection =
                    if (direction == interlock.relay.core.exec.stream.TrustedStreamControlEndpoint.DIRECTION_MAIN) {
                        ShizukuBackend.PanelMoveDirection.MAIN
                    } else {
                        ShizukuBackend.PanelMoveDirection.VIRTUAL
                    }
                return shizukuBackend.panelTaskMove(taskId, moveDirection)
            }

            override suspend fun release(): Pair<Boolean, String> =
                shizukuBackend.panelTaskRelease()

            // 旋转 = 宽高交换走 resize（不 release/不重建，屏上任务保留）。
            override suspend fun rotate(): Pair<Boolean, String> =
                shizukuBackend.panelRotate()
        },
    )

    private val backends: Map<BackendId, RelayBackend> = configuredBackends(
        config.executor,
        mapOf(
            BackendId.DIRECT to directBackend,
            BackendId.A11Y to a11yBackend,
            BackendId.SHIZUKU to shizukuBackend,
        ),
    )

    private val dispatcher = BackendDispatcher(backends)
    private val policy = SurfacePolicy(
        preference = { surfacePreferences.preference() },
        // 裁决里的「后台通路在不在」问的是 shell 身份能不能真执行：服务在跑但没授权时
        // shell 族能力一条都调不动，按「在跑」放行会让裁决挑一条注定失败的后台路，
        // 再让调用以 engine 级错误绕回前台。与探测（上方 [SystemStateProbe]）同一判据。
        shellAvailable = { shizuku.available && shizuku.granted },
        backendCanServe = dispatcher::canServe,
    )

    /**
     * 审批挂起-续行的对接面。协调器在装配根里先于信箱构造（信箱要拿它当处理器），
     * 而挂起登记簿与续行入口都在信箱上，所以这里先给占位、信箱装配好后再接上真身；
     * 未接上（未启动）时协调器一律走内联等待。
     */
    private class ParkBridgeHolder : ParkedRequestBridge {
        @Volatile
        var delegate: ParkedRequestBridge? = null

        override fun hasParkCapacity(): Boolean = delegate?.hasParkCapacity() ?: false

        override fun register(park: ParkedRequest): Boolean = delegate?.register(park) ?: false

        override fun resumeParked(requestId: String) {
            delegate?.resumeParked(requestId)
        }
    }

    private val parkBridge = ParkBridgeHolder()

    /**
     * 目标落点登记簿：`app.launch` 的核验落点记在这里，后续界面/取帧调用按目标沿用。
     * 随装配根存在 —— 会话重启即换新表，记录不跨会话；stop 时随通道一并清空。
     */
    private val landingTable = TargetLandingTable(
        now = ::monotonicNow,
        preference = { surfacePreferences.preference() },
    )

    private val coordinator = RelayCoordinator(
        context = appContext,
        gatekeeper = gatekeeper,
        policy = policy,
        dispatcher = dispatcher,
        capabilities = capabilities,
        text = text,
        // 系统权限快照由调用方按裁决出的路线探测：闸门不再自带探测入口，
        // 能力清单与真调用读同一处口径。
        probe = probe,
        // 目标落点层：启动核验落点、界面调用按目标沿用、后台启动按性质分类兜底。
        landingTable = landingTable,
        trustedDisplayAlive = { shizukuBackend.displayAlive() },
        topPackageOn = { display ->
            if (display == 0) directBackend.userScreenTopPackage() else shizukuBackend.trustedTopPackage()
        },
        surfacePreference = { surfacePreferences.preference() },
        paths = paths,
        runLog = runLog,
        auditLog = auditLog,
        // 建屏/收屏改写的就是能力清单里那五条后台能力的可用判据，跟着这一次调用核对一次；
        // 内容没变时不写盘，所以反复 query 也不会变成每分钟一次的三连写。
        onSurfaceChanged = { scope?.launch(Dispatchers.IO) { refreshAssetsIfChanged() } },
        // 派发前置守卫：v2 请求在动作提交前必须先把「已派发」边界钉进持久介质并确认落盘，
        // 钉失败即拒绝执行；v1 请求没有状态记录，守卫恒真，行为不变。
        dispatchGuard = { id ->
            if (requestStateStore.get(id) == null) true else requestStateStore.markExecDispatchGuard(id)
        },
        // 特权通路的执行身份随 Shizuku 快照走：授权记录的 uid 字段按它署名。
        fullShellUid = { shizukuUid() },
        // 审批挂起-续行：等用户答复的请求在这里登记挂起、释放执行槽，答复或审批到点
        // 之后由信箱把续行体排回串行上下文。等待环跑在进程级作用域上，不占信箱并发名额。
        parkBridge = parkBridge,
        parkScope = { scope },
        isCancelRequested = { id -> requestStateStore.get(id)?.cancelRequested == true },
        // 「谁也问不到人」时回包里点名该开哪个开关。判据在队列的裁决里（它才知道
        // 是通知被关、还是提问卡占着屏、还是两者都成立）。
        approvalDeadEnd = { approvalQueue.lastDeadEndReason() },
        // 调用侧裁决要看到"这台机器拒绝过建屏"这一结论：requiresMode 分支之前先问它，
        // 用户被拒后切了前台也不会变成"请切回后台"（那是死循环）。实时标记产后端。
        trustedDisplayDenied = { shizukuBackend.trustedDisplayDenied },
        // 「运行时自检判过可信屏破坏主屏」走同一段裁决：清单一并如实降级，
        // 调用侧在 requiresMode 之前按 E_SURFACE_TRUSTED_UNSAFE 回"改前台"。
        trustedDisplayUnsafe = { shizukuBackend.trustedDisplayUnsafe },
        // 进审批之前与派发之前按名单拒绝；挂起等待环每轮复查，批准不再是放行。
        protectedApps = protectedApps,
    )

    private val assets = GuestAssets(
        assetLoader = { name -> appContext.assets.open(name) },
        paths = paths,
        runLog = runLog,
    )

    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

    private val mailbox = MailboxServer(
        paths = paths,
        reaper = reaper,
        runLog = runLog,
        handler = coordinator,
        // v2 请求状态接上后，信箱在启动时做崩溃恢复、随行启动控制通道，并在认领与
        // 了结两个点上把阶段写进状态存储；执行判定与闸门完全不变。
        stateStore = requestStateStore,
        askPresenter = askSurface,
        // 判据是「设备是否醒着」，不是「本应用是否在前台」：沙盒里的助手多半在后台跑，
        // 以前台为判据会让通道在最常用的场景下完全不工作。
        isDeviceInteractive = { powerManager.isInteractive },
        onMaintenance = {
            reportAuditPurge(auditLog.purgeExpired())
            // 通道在跑就把那条前台服务再要一次。它是唯一会自己走开的一方：信箱目录被 guest
            // 侧长时间换掉时 RelayService 会按空窗计数自撤，而 RelayRuntime 的
            // started 标记挡住了常规起点、看门狗只在无障碍没绑时才 start()。
            // 不补这一句，那次自撤就是不可逆的：此后既没有前台档保护，也没有 CPU 租期。
            // 判据在 start() 自己那一侧（它问的就是通道此刻消不消费），这里不再引一次，
            // 免得在这台信箱自己的构造参数里回头引用它。
            RelayService.start(appContext)
            // 巡检先问两次账，再核对能力清单：
            // 用户服务掉线后不显式先解后绑就接不回来，而那块屏归服务端进程持有、
            // 可能被系统回收 —— 不对一次账，能力清单上的 trustedDisplay.alive 就在替一块
            // 已经不存在的屏说话，助手照着它把应用送进一个不存在的显示。
            // 系统门禁能在应用之外被改动（无障碍被关、Shizuku 掉线、权限被撤），
            // 变了才重铺，没变就一次写盘都不做。
            shizukuBackend.reconnect()
            scope?.launch(Dispatchers.IO) {
                shizuku.reprobe()
                shizukuBackend.refreshDisplay()
                refreshAssetsIfChanged()
            }
        },
    )

    init {
        // 无障碍的绑与解绑由用户在系统页面里决定，本应用收不到系统回调；
        // 这条口子是能力清单与界面知道它变了的唯一途径。
        RelayAccessibilityService.onBoundChanged = {
            // 绑定关系一动，先前的「已经看过」就作废：条件变了，提示才该再有下一次。
            silencedGap = null
            scheduleRefresh()
        }
        // 通知使用权同理：系统放行时绑上本服务，收权时解绑，这两个时刻只有服务自己知道。
        RelayNotificationListener.Holder.onBoundChanged = ::scheduleRefresh
        // Shizuku 的用户服务是异步绑上的：就绪那一声不在 Shizuku 状态里，而在连接回调里。
        shizukuBackend.onBoundChanged = ::scheduleRefresh
        // 建屏被系统拒的那一刻清单就得跟着改：继续显示可用会让助手把「再建一次屏」
        // 当成下一步，反复撞同一堵墙。拒绝标记清掉（建屏成功 / 重连对账屏活着）时
        // 同一回调也让清单回到真实状态。
        shizukuBackend.deniedListener = ::scheduleRefresh
        // 自检判「可信屏不安全」（或一次建屏+自检通过把它解除）同样要立刻重铺清单：
        // surface.virtual 的 usable/degradeReason 与 manifest 里的 unsafe 同一份事实。
        shizukuBackend.unsafeListener = ::scheduleRefresh
        // 缺无障碍的这一次要说给界面：判定只有探测这一处，错误码有两处（闸门的
        // E_GATE_SYSTEM_MISSING 与执行期 A11yBackend 的 E_BACKEND_UNAVAILABLE），
        // 挂在判定上才不会漏掉其中一条。
        probe.onAccessibilityGap = { id, gap -> publishAccessibilityGap(id, gap) }
        // 会改系统的动词能不能跑，取决于用户有没有在界面上把它打开：判据在偏好里，
        // 而偏好只由界面上的开关写，助手侧没有任何路径能替它写。
        shizukuBackend.shellOptInOpen = { verb -> shellOptIns.isOpen(verb) }
        // 信箱已装配，挂起-续行的对接面接上真身：协调器从此能把「等用户答复」移出执行槽。
        parkBridge.delegate = mailbox
    }

    /**
     * 把一次「被缺无障碍挡下」摊到界面上。
     *
     * 界面能力清单那一趟探测不算调用被挡下（见 [listingThread]）：能力清单每秒复查一次，
     * 不分开的话用户一打开宿主界面就会被弹一张框，而助手什么都没试过。
     */
    private fun publishAccessibilityGap(id: CapabilityId, gap: AccessibilityGap) {
        if (listingThread.get() == true) return
        // 有确认框挂着时不再弹这张提示：同屏两张框用户只看到后到的那张，而助手在等前一张。
        // 无障碍缺失不会因此被漏说：审批收口后的下一次探测还会再提醒一次。
        if (pendingApprovalState.value != null) return
        if (silencedGap == gap) return
        accessibilityNoticeState.value = AccessibilityNotice(gap, id)
    }

    /** 用户看过这张框：同一档现场不再重复喊，直到绑定关系真的动了。 */
    fun dismissAccessibilityNotice() {
        accessibilityNoticeState.value?.let { silencedGap = it.gap }
        accessibilityNoticeState.value = null
    }

    @Volatile
    private var scope: CoroutineScope? = null

    /** 在途装配协程：stop 与重试前都要叫停它，否则旧序列的收尾会踩进新一轮的现场。 */
    @Volatile
    private var initJob: Job? = null

    /** 对外的启动阶段流。状态机的每次迁移都会同步进来，界面订阅即可，不必轮询。 */
    private val phaseState = MutableStateFlow(RuntimePhase.STOPPED)
    val phase: StateFlow<RuntimePhase> = phaseState

    /**
     * 启动状态机：阶段与失败原因的唯一事实源。迁移回调只做一次流写入 ——
     * 回调在状态机内部锁内执行，必须轻量，也不得再进状态机（会重入死锁）。
     */
    private val stateMachine = RuntimeStateMachine { phase -> phaseState.value = phase }

    /** 当前阶段的即时读数。与 [phase] 流同源，供不订阅流的调用方按时刻取值。 */
    fun runtimePhase(): RuntimePhase = stateMachine.phase

    /** 启动失败的原因；仅 DEGRADED 阶段有值，重试成功后清空。 */
    val degradedReason: String? get() = stateMachine.degradedReason

    private fun reportAuditPurge(removed: Int) {
        if (removed > 0) runLog.info(LogSubsystem.STORAGE, LogEvent.AUDIT_PURGED, "files" to removed.toString())
    }

    /**
     * 收掉通知栏那条待答的通知。答复没被任何一张待决请求接住时由广播侧叫：
     * 只按编号擦通知会让呈现对象以为还挂着一条，那条到点回收也会一直空跑。
     */
    fun dismissApprovalNotification(requestId: Int) {
        approvalNotifications?.cancelFor(requestId)
    }

    /**
     * 通知栏上那一下带着请求编号回来：只认那一张。
     * 用户过了一分钟才下拉时，队列里挂着的可能是另一件事，不能拿旧的那一问的答复去答它。
     */
    fun resolveApproval(choice: InterlockChoice, requestId: Int): Boolean =
        approvalQueue.resolveCurrent(choice, requestId)

    /**
     * 宿主呈现面上那张卡到了自己的截止时刻：把这一问按到点收掉（关实体并撤全部呈现者）。
     * 宿主呈现面装配在核外，审批到点收尾由宿主经这一条接回队列，
     * 与页内卡/悬浮卡到点走同一条收尾。
     *
     * 仅供宿主呈现面装配使用，不得作为绕过队列纪律的入口。
     */
    fun expireApproval(requestId: Int): Boolean = approvalQueue.expireIfCurrent(requestId)

    /**
     * 前台状态变化是审批呈现路由的输入之一：进出助手页都要请队列重算一次裁决。
     *
     * 挂/撤不再由这里决定 —— 那是队列的事（[InterlockQueue.reroute]）。悬浮通路
     * 能不能用由队列按真实呈现结果判定（修正后不再有可信虚拟屏层面的提前跳过），
     * 这里没有队列之外的判据要补。
     */
    fun onForegroundChanged() {
        approvalQueue.reroute()
    }

    /**
     * 一个待决项有了终局结论：把**全部**呈现者都收掉。
     *
     * 「点哪都算数、只算一次」的另一半：定案之后，屏上不许还留着别处的入口。
     * 页内那张由 [pendingApprovalState] 的下一次裁决自然消失，这里收的是屏外的两处。
     */
    private fun revokeApprovalPresenters(request: InterlockQueue.Request) {
        approvalOverlay?.withdraw()
        approvalNotifications?.cancelFor(request.id)
    }

    /**
     * 按队列的裁决把待决项摊到**唯一**一处，并记一行它被摊到了哪里。
     *
     * 以前这里是「界面在前台就撤通知、否则挂通知」，等于挂不挂通知由前台可见性说了算。
     * 那样会出现悬浮卡在屏的同时通知栏里也挂着一件同一件事的两处可点：用户点哪都算数，
     * 但两边各记一次，助手收到的答复与用户以为的那一次可能不是同一次。
     * 裁决交给队列之后这里只执行：PAGE / OVERLAY / NONE 撤通知，NOTIFICATION 挂或更新一次。
     *
     * 队列只在状态迁移时调它，等待环每 500ms 重发同一问不会走到这里 ——
     * 否则用户刚要划掉通知它就又弹回来。
     */
    private fun rerouteApprovalPresentation(routing: InterlockQueue.Presentation) {
        val pending = routing.request
        pendingApprovalState.value = pending
        approvalRoutingState.value = routing
        // 审批挂上时收起无障碍提示框：同屏两张框用户只看得到后到的那张，而助手在等
        // 前一张，结果是屏幕上没有可点的东西、调用只能等到超时。
        // 这里只清状态、不记 silencedGap：无障碍仍然缺着，下一次探测照原样再提醒。
        if (pending != null) accessibilityNoticeState.value = null
        when (routing.via) {
            InterlockChannel.NOTIFICATION ->
                approvalNotifications?.show(pending!!, routing.waitingCount)

            InterlockChannel.PAGE,
            InterlockChannel.OVERLAY,
            InterlockChannel.NONE,
            -> approvalNotifications?.cancel()
        }
        if (pending != null) {
            // 本次呈现在哪里、还排着几项：事后只看得见这一行就能知道用户当时该去哪答。
            // 悬浮这一条只有卡真的挂在屏上才写：裁决先行的那一刻卡可能还在挂的路上
            // （兜底挂卡跑在协程里），提前写会让日志说「在悬浮」而屏上什么都没有；
            // 卡挂上的那一刻队列会再发一次带 overlayPresented=true 的裁决，日志落在这里。
            if (routing.via != InterlockChannel.OVERLAY || routing.overlayPresented) {
                runLog.info(
                    LogSubsystem.GATE,
                    LogEvent.APPROVAL_PRESENTER,
                    "via" to routing.via.wire,
                    "waiting" to routing.waitingCount.toString(),
                )
                // 通知这条通路最近一次的呈现状态进运行日志：可用≠可见，投递成功最多是
                // 「已投递、可见性未证实」。只作诊断读数，不参与判定，也不接界面。
                runLog.info(
                    LogSubsystem.GATE,
                    LogEvent.APPROVAL_ROUTED,
                    "cap" to pending.prompt.capability.wire,
                    "postState" to (approvalNotifications?.lastPostState() ?: "none"),
                )
            }
        }
    }

    /**
     * 启动装配。[scope] 必须是宿主的进程级作用域。
     *
     * 本方法只做状态置位就返回，真正的装配（账本加载、清扫、资产重铺、Shizuku 连接、
     * 信箱与前台服务）搬进该作用域的 IO 协程逐步执行 —— 这些全是磁盘与 binder 操作，
     * 原先在调用线程（往往是主线程）上同步跑完，首启卡顿都从这里来。因此调用方
     * 不必也无法在这里「等它跑完」：何时可服务由 [phase] 流说。
     *
     * 账本必须先加载再对账：产物落在缓存目录，系统会在空间压力下随手删文件，
     * 只信持久化值会让已消失的字节永久占额。对账成本是目录内的几次 stat。
     *
     * 加锁是因为调用方有两个线程：宿主的沙盒协程与宿主界面的主线程。
     * 「先看后写」的判定在两个线程上能同时通过，结果是两个 mailbox loop 加两个
     * FileObserver 盯同一批请求目录，彼此删掉对方在途的文件 —— 请求静默消失，
     * 且这条前置条件写死在 [MailboxServer] 的类注释里。现在互斥由两层共同保证：
     * 状态机原子地裁决能否进入 STARTING，本监视器再串行化 start/stop 与装配的每一步。
     *
     * 装配中途任一步失败都必须能重试：失败时按已执行步骤逆序清理、阶段落 DEGRADED
     * （见 [initSequence]），下一次 start() 从 DEGRADED 重新进入。若只用一个置位即
     * 回不去的布尔标记，失败后既挡着重试，也让对外分不清「在跑」与「没跑起来」。
     */
    @Synchronized
    fun start(scope: CoroutineScope) {
        if (!stateMachine.beginStart()) return
        this.scope = scope
        runLog.info(LogSubsystem.TRANSPORT, LogEvent.RUNTIME_STARTING)
        initJob = scope.launch(Dispatchers.IO) {
            initSequence(job = coroutineContext.job, scope = scope)
        }
    }

    /** 装配序列被叫停：stop 已接管或阶段已易主，序列应静默退出、不再碰任何资源。 */
    private class InitAborted : RuntimeException()

    /** 装配某一步失败。带步骤短名，降级记录才说得出断在哪一环。 */
    private class InitStepFailed(val step: String, cause: Throwable) : RuntimeException(cause)

    /**
     * 通道装配的完整序列，在 IO 协程上执行。顺序约束不变：资产先铺、通道后起 ——
     * 异步重铺会让沙盒侧在入口脚本尚不存在或只写了一半时就去 exec，拿到的退出码
     * 与「用法错误」无法区分。重铺本身已是暂存+改名，这里要的是「通道开始接活之前，
     * 入口文件一定已经就位」这个顺序。
     *
     * 每一步都持装配根监视器执行（见 [initStep]）：与 start/stop 互斥，任何一步的
     * 内部不会与停止序列交错。全部就绪才提交 RUNNING —— 中途任一步失败都不算
     * 「已启动」，对外状态与真实进度一致。
     */
    private fun initSequence(job: Job, scope: CoroutineScope) {
        // 已执行步骤的逆序清理表：失败时统一倒着执行，成功则随序列结束一并丢弃。
        val undo = ArrayDeque<() -> Unit>()
        try {
            initStep(job, undo, "ledger") {
                ledger.load { detail ->
                    runLog.warn(LogSubsystem.STORAGE, LogEvent.LEDGER_CORRUPTED, "detail" to detail)
                }
            }
            initStep(job, undo, "ledger") { ledger.reconcileFromDisk(paths.artifactsDir) }
            initStep(job, undo, "ledger") { reaper.sweep() }
            initStep(job, undo, "ledger") { reportAuditPurge(auditLog.purgeExpired()) }
            // 装配这一趟不走单飞门：它不能被「正在铺」合并掉——「资产先铺、通道后起」
            // 的次序靠的就是这里同步铺完。此刻若真有一趟事件驱动的重铺在跑，两趟逐份
            // 交错可能留下几份不同批的文件、提交标记也对不上；这个混版由提交标记兜住：
            // [GuestAssets.intact] 比对失配即判「不完整」，下一次复查整体从头重铺自愈，
            // 混版不会被当成最新状态长期留在盘上。
            initStep(job, undo, "assets") { republishAssets(capabilitiesJson()) }
            initStep(job, undo, "shizuku", undoStep = { shizuku.unregister(shizukuListener) }) {
                shizuku.register(shizukuListener)
            }
            // 先丢掉上一世可能残留的"上一帧"（进程被强杀时跑不到 disconnect），再接用户服务。
            initStep(job, undo, "shizuku") { shizukuBackend.clearFrameCache() }
            initStep(job, undo, "shizuku", undoStep = { shizukuBackend.disconnect() }) {
                shizukuBackend.connect()
            }
            initStep(job, undo, "mailbox", undoStep = { mailbox.stop() }) { mailbox.start(scope) }
            // 通道在跑了才挂那条常驻通知：反过来会出现"通知说有助手在等、其实没在接活"。
            initStep(job, undo, "channel_service", undoStep = { RelayService.stop(appContext) }) {
                RelayService.start(appContext)
            }
            // 看门狗跟着通道：通道都停了还在周期把我们拉回前台，就纯属打扰。
            initStep(job, undo, "watchdog", undoStep = { RelayA11yWatchdog.cancel(appContext) }) {
                RelayA11yWatchdog.schedule(appContext)
            }
            // 流控制端点跟着通道：起不来只降级（该能力为增强项），不拖垮装配。
            initStep(
                job, undo, "stream_control",
                undoStep = {
                    interlock.relay.core.exec.stream.StreamRendezvous.delete(paths.entryDir)
                    streamControl.stop()
                },
            ) {
                val started = runCatching { streamControl.start() }.getOrDefault(false)
                if (started) {
                    interlock.relay.core.exec.stream.StreamRendezvous
                        .write(paths.entryDir, streamControl.port, streamControl.token)
                        .onFailure {
                            runLog.warn(
                                LogSubsystem.TRANSPORT, LogEvent.STREAM_CONTROL_UNAVAILABLE,
                                "cause" to (it.message ?: it.javaClass.simpleName),
                            )
                        }
                    runLog.info(LogSubsystem.TRANSPORT, LogEvent.STREAM_CONTROL_STARTED)
                } else {
                    runLog.warn(LogSubsystem.TRANSPORT, LogEvent.STREAM_CONTROL_UNAVAILABLE, "cause" to "bind failed")
                }
            }
            synchronized(this) {
                if (!job.isActive || stateMachine.phase != RuntimePhase.STARTING) throw InitAborted()
                stateMachine.commitRunning()
                runLog.info(LogSubsystem.TRANSPORT, LogEvent.RUNTIME_RUNNING)
            }
        } catch (aborted: InitAborted) {
            // 叫停方（stop 或新一轮序列）已接管清理与阶段：该分支只做退出，不再改动阶段。
        } catch (failed: Throwable) {
            initFailed(job, undo, failed)
        }
    }

    /**
     * 在装配根监视器内执行一步装配。
     *
     * 进入前复查在途协程与阶段：被 stop 叫停（协程已取消）或阶段已易主时抛
     * [InitAborted] 静默退出。步骤本体也在同一监视器内执行，起与停不交错。
     *
     * [undoStep] 是这一步的逆操作，**先入表再执行**：步骤本体半途而废时逆操作
     * 也已在表内，失败清理不会漏掉它。
     */
    private fun initStep(
        job: Job,
        undo: ArrayDeque<() -> Unit>,
        step: String,
        undoStep: (() -> Unit)? = null,
        body: () -> Unit,
    ) {
        synchronized(this) {
            if (!job.isActive || stateMachine.phase != RuntimePhase.STARTING) throw InitAborted()
            undoStep?.let { undo.addLast(it) }
            try {
                body()
            } catch (cause: Throwable) {
                throw InitStepFailed(step, cause)
            }
        }
    }

    /**
     * 装配失败的收尾：仍处于本次 STARTING 名下时，按已执行步骤逆序尽力清理，
     * 再落进 DEGRADED 并记录断在哪一步 —— 对外从此看得出「没跑起来」，且下一次
     * start() 允许重试。协程已被取消或阶段已易主时什么都不做：清理与阶段都归
     * 接管方，再动手就会拆掉别人的现场。任何一步的失败都必须收进 DEGRADED：
     * 卡在 STARTING 比降级更糟，重试入口会永远打不开。
     */
    private fun initFailed(job: Job, undo: ArrayDeque<() -> Unit>, failed: Throwable) {
        val step = (failed as? InitStepFailed)?.step ?: "unknown"
        val cause = failed.cause ?: failed
        val detail = cause.javaClass.simpleName + ": " + (cause.message ?: "no message")
        synchronized(this) {
            if (!job.isActive || stateMachine.phase != RuntimePhase.STARTING) return
            while (undo.isNotEmpty()) {
                runCatching { undo.removeLast().invoke() }
            }
            stateMachine.fail("$step $detail")
            runLog.warn(LogSubsystem.TRANSPORT, LogEvent.RUNTIME_DEGRADED, "step" to step, "cause" to detail)
        }
    }

    /**
     * 停止装配。DEGRADED 下无事可停（失败收尾已清理过），状态机会拒绝这里进入。
     */
    @Synchronized
    fun stop() {
        if (!stateMachine.beginStop()) return
        // 先叫停在途装配再拆：序列在下一道步前检查就会自行退出。每一步都持本监视器，
        // 此刻不可能有步骤在半途 —— 叫停是把「拆到一半又续上」的门关死，
        // 尤其挡住「stop 后立刻 start 重试」时旧序列踩进新一轮的现场。
        val job = initJob
        initJob = null
        job?.cancel()
        scope = null
        shizuku.unregister(shizukuListener)
        mailbox.stop()
        // 流会话先收：它压着一条远端编码会话与一条 loopback 端口，别拖到解绑之后。
        runCatching { interlock.relay.core.exec.stream.StreamRendezvous.delete(paths.entryDir) }
        panelInputWorkers.shutdown()  // 通道停机：排队中的输入不再落屏；重启后自动重建
        streamControl.stop()
        runLog.info(LogSubsystem.TRANSPORT, LogEvent.STREAM_CONTROL_STOPPED)
        trustedStream.stop()
        shizukuBackend.disconnect()
        // 会话收尾：落点记录不跨会话，登记簿随通道一并清空。
        landingTable.clear()
        approvalQueue.cancelAll()
        // 悬浮窗不归界面管：通道停了还留着一个没人应答的浮窗，等于把用户钉在原地。
        approvalOverlay?.withdraw()
        // 提问卡同理，而且它比审批卡更"沉默"：没有通知栏兜底、也没有页内卡接手。
        // 通道收束之后还挂在屏上，用户点它只会把答案送进一个已经没人等的等待。
        askSurface.withdraw()
        // 采集会话与那条前台服务通知同理：通道停了还挂着，等于替用户留一个「正在录屏」的错觉。
        screenProjection.release()
        // 常驻通知跟着通道走：通道都停了还挂着，就是白打扰用户，
        // 而且它挡的那次清理本来也不是我们要替用户决定的事。
        RelayService.stop(appContext)
        RelayA11yWatchdog.cancel(appContext)
        // 会话边界的落点：通道停一次即一个助手会话结束，「本会话内允许」到此作废。
        tiers.clearSession()
        runLog.info(LogSubsystem.GATE, LogEvent.CHANNEL_STOPPED)
        runLog.info(LogSubsystem.TRANSPORT, LogEvent.RUNTIME_STOPPED)
    }

    private val shizukuListener = object : ShizukuState.StateListener {
        override fun onStateChanged(state: ShizukuState.Snapshot) {
            // 未启动与失败降级后不认账：前者监听器根本没注册（这里是进程内迟到回调的
            // 最后一道闸），后者资源已随失败收尾清理。装配在途（STARTING）要放行 ——
            // 那正是绑定状态真实在变化的窗口，丢掉会让快照与授权页停在启动前的样子。
            if (stateMachine.phase == RuntimePhase.STOPPED ||
                stateMachine.phase == RuntimePhase.DEGRADED
            ) {
                return
            }
            shizukuSnapshot.value = state
            // 绑定只能在「已连接且已授权」之后成功，而这两者都可能在 start() 之后才满足：
            // 只在那一刻试一次，三个 shell 能力会永久连不上。
            if (state.usable) {
                shizukuBackend.connect()
                // 服务端换了进程就是新的一屏：本地缓存的编号要跟一次账，
                // 否则裁决层会以为 trusted-display 还可用，命令发给一个不存在的显示。
                scope?.launch(Dispatchers.IO) { shizukuBackend.refreshDisplay() }
            }
            runLog.event(
                LogSubsystem.GATE,
                LogEvent.SHIZUKU_STATE,
                "binder" to state.running.toString(),
                "granted" to state.granted.toString(),
            )
            // 授权状态变化后能力清单与说明要重铺，助手读到的才是当前值。
            scheduleRefresh()
        }
    }

    /**
     * 向 Shizuku 发起授权请求。系统级弹窗由 Shizuku 呈现，本模块只负责把请求递过去；
     * 结果经 [shizukuListener] 回来，界面靠 [shizukuState] 刷新，不在这里读结果。
     */
    fun requestShizukuAuthorization() {
        shizuku.requestAuthorization()
    }

    /**
     * 执行侧用户服务的绑定状态。独立于 [shizukuState] 的运行和授权状态，
     * 用于判断 shell 与虚拟屏执行所依赖的用户服务是否已连接。
     */
    fun shizukuBound(): Boolean = shizukuBackend.boundSnapshot

    /**
     * 立即重连用户服务。周期巡检每分钟已会自动尝试一次；这个入口给用户当场重试用。
     * 先解后绑（见 [BackendShizuku] 的 reconnect）：只靠原样再 bind 会命中 Shizuku 侧
     * 的连接缓存，服务进程已死时什么也不会发生。
     */
    fun reconnectShizuku() {
        shizukuBackend.reconnect()
    }

    /**
     * 助手侧资产重铺。由 [capabilityRows] 单源生成清单，界面与助手读到的口径一致。
     *
     * 触发源很多（启动序列、每次门禁变化、建屏/收屏、每分钟巡检），随时可能撞在
     * 同一时刻：重铺要连写多个文件，让它们各铺各的等于把同一趟重铺做好几遍、还让
     * 「内存已最新」与「磁盘铺到一半」的状态在多线程上交错。单飞门把执行体收进
     * 同一临界区，并发触发直接并进正在跑的那一趟。
     */
    fun refreshAssets() {
        // 停机后的在途回调仍可能把这里叫进来：stop 置空 scope 与回调真正执行之间没有
        // 原子边界。通道已停就不再重铺 —— 此刻铺出的清单描述的是一个不再接活的通道，
        // 沙盒侧照它发起的调用只会无人应答；重铺留给下一次 start() 的装配序列。
        val phase = runtimePhase()
        if (phase != RuntimePhase.RUNNING && phase != RuntimePhase.STARTING) return
        refreshSerialized { republishAssets(capabilitiesJson()) }
    }

    /**
     * 能力清单内容真的变了、或者那几份文件不再是同批发布的完整一套，才重铺。
     *
     * 系统门禁能在应用之外被改动（无障碍被系统关掉、Shizuku 掉线、权限被撤），而沙盒侧
     * 读的是那份文件，所以必须复查内容，否则文件里的 `usable` 会长期与设备实际不符。
     * 比内容而不是按时写：重铺是多个文件的连续写，内容没变时每分钟重写没有意义。
     *
     * 但只比清单那份不够：入口脚本与手册是另外两份文件，清单可以整场会话一个字不变
     * （没人改档位、也没接 Shizuku），而沙盒同 uid 能直接把那两份删掉、换成指向宿主
     * 别处的链接，或改掉其中一份的内容；上一趟重铺半途而废也会留下几份不同批的文件。
     * [GuestAssets.intact] 连提交标记一起核——三份加标记有一项对不上就重铺，铺完
     * 标记才重新落成同批的哈希。
     */
    fun refreshAssetsIfChanged() {
        refreshSerialized {
            val json = capabilitiesJson()
            if (json == lastCapabilitiesJson && assets.intact()) return@refreshSerialized
            republishAssets(json)
        }
    }

    /**
     * 资产重铺的单飞门。门被占着说明有一趟重铺正在跑，本次触发直接并入它：门内一趟
     * 收尾时会复检一次内容是否仍新，落在在飞趟生成清单之后的状态变化由复检接住，
     * 被跳过的触发因此不会「漏铺自己那一份」。
     */
    private val refreshGate = Mutex()

    private fun refreshSerialized(work: () -> Unit) {
        if (!refreshGate.tryLock()) return
        try {
            work()
            // 门内复检：work 生成清单到铺完之间，门禁状态仍可能在别的线程上再变。
            // 重新算一次清单，与「已完整铺出」的内存基准对不上（或三份文件不再是
            // 同一批）就再铺一趟；复检之后又变的极小窗口由下一次巡检复查兜住，
            // 这里不追成循环。
            val fresh = capabilitiesJson()
            if (fresh != lastCapabilitiesJson || !assets.intact()) {
                republishAssets(fresh)
            }
        } finally {
            refreshGate.unlock()
        }
    }

    /**
     * 铺一次资产，成功才前移「已发布」的内存记录。
     *
     * 次序不能反：[lastCapabilitiesJson] 若在铺之前更新，一次失败的重铺就会在内存里
     * 留下「已是最新」的假象，此后每分钟的复查都看到「内容没变、文件也在」而不再重试，
     * 磁盘上的混版就成了永久状态。失败保留旧值，下一次门禁变化或下一分钟的复查再来。
     */
    private fun republishAssets(json: String) {
        if (assets.materialize(json)) {
            lastCapabilitiesJson = json
        }
    }

    /** 上一次**完整**铺出去的清单内容，[refreshAssetsIfChanged] 的比较基准。 */
    @Volatile
    private var lastCapabilitiesJson: String? = null

    /**
     * 重铺是连续文件写。系统回调与档位改动都落在主线程上，不能在那里连写多个文件，
     * 因此一律切到 IO。启动序列自己会铺一次；这里的复查与它或其他触发重叠时，
     * 由 [refreshGate] 并成一趟，不会重复写。
     */
    private fun scheduleRefresh() {
        val running = scope ?: return
        running.launch(Dispatchers.IO) { refreshAssets() }
    }

    /**
     * 每一项能力的当前状态。界面与助手侧能力清单都从这里取，判定逻辑不在别处再算一遍。
     *
     * [usable] 是**唯一**的"此刻能不能调"口径（后端支持、系统权限可达、档位不是「禁止」、
     * 执行面可达）：首页的可用计数读它，能力详情与对外清单也读它——任何一处的数字或
     * 红点另算一套，就会出现「首页说可用、点进去缺权限/缺授权」的两套话。
     */
    data class CapabilityRow(
        val descriptor: CapabilityDescriptor,
        val state: CapabilityState,
        val implemented: Boolean,
        val usable: Boolean,
        /**
         * 历史口径（已实现+档位开着+系统那侧不挡），**不参与任何可用性表达**：它不含
         * "此刻够不够得着"（后端连接、授权、受拒标记），首页与清单都不再消费——留着
         * 只为不便动到的旧接线不炸，新代码一律读 [usable]。
         */
        val enabled: Boolean,
        val gaps: List<GrantGap>,
        val surface: SurfaceKind?,
        val degradeReason: String?,
        /** 本会话授权此刻是否在效。与档位一起决定下一条。 */
        val sessionGranted: Boolean,
    )

    fun capabilityRows(): List<CapabilityRow> {
        // 这一段里的每一次探测都只是「把当前状态铺给能力清单」，不是一次调用被挡下；
        // 标记按线程打，见 [listingThread] 的理由。
        listingThread.set(true)
        try {
            return capabilities.all().map(::capabilityRow)
        } finally {
            listingThread.set(false)
        }
    }

    /**
     * 界面消费的普通能力行：成员过滤在内核（[CapabilityRegistry.ordinaryMembers]），
     * 特权能力不进三档列表，也不进首页「可用能力」的分子分母——它由特权模式页的
     * 独立开关管理，混进普通列表会让一次全局拨档看起来也管到了它。
     * 对外能力清单（[capabilitiesJson]）照常包含特权能力，助手要按它知道这条能力存在。
     */
    fun ordinaryCapabilityRows(): List<CapabilityRow> {
        listingThread.set(true)
        try {
            return CapabilityRegistry.ordinaryMembers(capabilities.all()).map(::capabilityRow)
        } finally {
            listingThread.set(false)
        }
    }

    private fun capabilityRow(descriptor: CapabilityDescriptor): CapabilityRow {
        return run {
            // 先裁决路线，再按**这条路线**探测系统权限：同一能力在不同路线上需要的
            // 系统条件不同（坐标四条走可信屏就不需要无障碍）。先探测后裁决会拿
            // 「另一条路」的权限状态写清单，与真调用对不上。
            val decision = policy.decide(descriptor)
            val probed = probe.probe(descriptor, decision.surface)
            val systemState = probed.state
            val tier = tiers.tierOf(descriptor.id)
            // 「有没有实现」必须按**裁决出的那个执行模式**问，不能按「任一模式的并集」问：
            // 并集判据会让能力清单报可用、真调用却挑不到后端。
            val backend = dispatcher.pick(descriptor, decision.surface)
            // 只有一个执行面的能力被偏好链卡死时（requiresMode 非空），落点不再写成
            // 那个还没达成条件的模式：清单里 surface 置空、原因写成「需要该模式」，
            // 助手读到的是「先切偏好/建屏」，而不是一个此刻跑不通的落点。
            // `surface.virtual` 豁免——create/release/query 是管理动作
            // 不是执行落点，前台偏好下也要可达（显式 create/release 是 unsafe 与残留屏的
            // 解除通路）。判据与调用侧同一张表（[RelayCoordinator.modeRequiredFor]）：
            // 两处各说一套时清单写"可调"、实调却回 E_SURFACE_MODE_REQUIRED。
            val modeRequired = RelayCoordinator.modeRequiredFor(descriptor.id, decision.requiresMode) != null
            // trusted 建屏被系统拒绝是这台机器上的固定结论：这一结论不解除，建屏调用
            // 每次都会回同一个拒绝。能力清单必须先说出来——这条继续写可用，助手的
            // 下一步就会是「再建一次屏」，循环撞同一堵墙。拒绝由建屏成功或重连后
            // 对账屏活着来解除（见 ShizukuBackend 的 trustedDisplayDenied）。
            // 判据收在纯函数 [trustedDisplayRefusal] 里，JVM 单测可直接穷举各组合。
            val systemRefusedTrustedDisplay = trustedDisplayRefusal(
                descriptor.id, shizukuBackend.trustedDisplayDenied,
            )
            // 复核：unsafe（自检判过"建屏破坏主屏"）**不降 surface.virtual 这一行**——
            // 清单 usable=false 会把 create/release/query 一起封死，标记就成了不可解除的
            // 死锁。unsafe 对清单的表达在 manifest 的 trustedDisplay.unsafe 与后台各行的
            // 后端 supports 真值表里（见 ShizukuBackend.trustedSurfaceSupported）。
            // 该模式下执行面尚未达成，今天调不动：usable 若仍为 true，使用说明就会承诺
            // 「usable=true 就能调」，而实调回的却是 E_SURFACE_MODE_REQUIRED ——
            // 清单口径必须与实调一致，卡死的这一行一并算不可用。
            val usable = backend != null &&
                !InterlockGate.systemBlocks(systemState) && tier != AccessTier.DENIED &&
                !modeRequired &&
                !systemRefusedTrustedDisplay &&
                capabilities.isUsable(descriptor.id)
            // 审批口径的派生（含 requiresApproval/sessionGrantable）统一在
            // [CapabilityManifestEntries]：这里只供「会话授权此刻是否在效」这一项原始状态。
            val sessionGranted = tiers.sessionGranted(descriptor.id, monotonicNow())
            // degradeReason 的顺序在纯函数 [degradeReasonFor] 里：被拒优先于"模式够不着"。
            // 用户被拒后切了前台，`surface.virtual` 的裁决会先说 E_SURFACE_MODE_REQUIRED
            // ——若它先报，清单与插件就按"切回后台/建屏"给建议，用户又切回后台再撞一次
            // 拒绝，绕成循环。拒绝是这台机器上的固定结论，永远优先。
            val degrade = degradeReasonFor(systemRefusedTrustedDisplay, modeRequired, decision.reason)
            CapabilityRow(
                descriptor = descriptor,
                state = CapabilityState(descriptor, systemState, tier),
                // 「有没有实现」问的是代码，不是后端此刻连不连得上：把两者混在一起，
                // Shizuku 掉线时 `app.install` 会在能力清单里写成 not_implemented，
                // 而它只差一次重新授权。此刻可达与否由 usable 与系统权限态各自说。
                implemented = servedSomewhere(descriptor),
                usable = usable,
                enabled = servedSomewhere(descriptor) &&
                    tier != AccessTier.DENIED &&
                    !InterlockGate.systemBlocks(systemState) &&
                    !modeRequired,
                gaps = probed.gaps,
                surface = if (!modeRequired && usable) decision.surface else null,
                degradeReason = degrade,
                sessionGranted = sessionGranted,
            )
        }
    }

    /**
     * 注册表里有没有后端实现这条能力（不看后端此刻连不连得上）。
     *
     * 面板那个统计数必须含这一项：只按「档位开着 且 系统权限已给」算，会把没有后端实现
     * 的能力也计进去，出现「全部已启用」与「若干条未实现」互相矛盾的两个数。
     */
    private fun servedSomewhere(descriptor: CapabilityDescriptor): Boolean =
        dispatcher.isImplemented(descriptor)

    /** 无障碍服务此刻是否连着。无障碍模式页的状态行读它。 */
    fun accessibilityEnabled(): Boolean = probe.accessibilityEnabled()

    /**
     * 无障碍此刻缺在哪一档，供无障碍页决定要不要说「服务没绑上」。
     * 判定只此一处：界面自己比绑定与名单会算出第三种口径。
     */
    fun accessibilityGap(): AccessibilityGap? = probe.accessibilityGap()

    /**
     * 本应用是否已被排除在电池优化之外。只读系统事实，不代用户改：
     * 那一格开关归用户决定，本模块只负责把它现在是什么样子说清楚。
     */
    fun batteryOptimizationIgnored(): Boolean = runCatching {
        powerManager.isIgnoringBatteryOptimizations(appContext.packageName)
    }.getOrDefault(false)

    /**
     * 「优先后后台」落不到后台屏时缺的那一环。
     *
     * 各环的下一步互不相同：装 Shizuku、启动它、在它里面放行本应用、让助手建屏，
     * 是四件不同的事，合成一条「后台不可用」等于什么都没说。本机 Android 版本不够时
     * 这四件事全做对了也不会有后台屏，所以那一档要单独说，不能拿「没装 Shizuku」去解释。
     * 「系统拒绝建屏」也不能与「屏还没建」同档：前者的结论是这台机器给不出可信屏、
     * 建再多次也一样（下一步是改前台/前台兜底），后者只差一次成功建屏。
     */
    enum class BackgroundSurfaceBlock {
        ANDROID_VERSION,
        SHIZUKU_NOT_INSTALLED,
        SHIZUKU_NOT_RUNNING,
        SHIZUKU_NOT_GRANTED,
        DISPLAY_REFUSED,
        /** 运行时自检判过「可信屏破坏主屏」：屏被释放、设备被标记，改前台是唯一出路。 */
        DISPLAY_UNSAFE,
        DISPLAY_NOT_CREATED,
    }

    /**
     * 后台操控的落点是否就绪：Shizuku 在跑**且已授权**，用户服务连着，那块可信屏已经建起来。
     *
     * 授权必须在这一问里：执行侧用户服务是由 shell 身份跑的独立进程，没授权时绑定
     * 进不去（[ShizukuState.bindUserService] 直接拒绝），屏的存续判据会让退出
     * Shizuku 管理器授权的那台机器继续报「屏在」。
     */
    /**
     * 后台操控的落点是否就绪：Shizuku 在跑**且已授权**，用户服务连着，那块可信屏已经建起来。
     *
     * unsafe（自检判过"建屏破坏主屏"）也算不就绪（复核）：屏可能因 release
     * 失败而残留，"屏在"不能被读成健康——探测侧据此把后台路由降回前台。
     */
    private fun trustedDisplayReady(): Boolean =
        shizuku.available && shizuku.granted && shizukuBackend.displayAlive() &&
            !shizukuBackend.trustedDisplayUnsafe

    /**
     * 用户选了「优先后台」，而此刻的后台屏在不在；不在的话缺哪一环。就绪或与执行模式
     * 无关时为 null。
     *
     * 判据只在这一处：降级本身只记在通道响应的 `degradedFrom` 与错误码里，用户屏幕上没有
     * 一处说出来的话，「优先后台」就会被读成「设置没生效」。屏在不在只有装配根问得到
     * （[trustedDisplayReady]），所以这一档从这里出，界面按档取词。
     */
    fun backgroundSurfaceBlock(): BackgroundSurfaceBlock? =
        backgroundBlockFor(
            backgroundPreferred = surfacePreferences.preference() == SurfacePreference.BACKGROUND_PREFERRED,
            versionOk = Build.VERSION.SDK_INT >= SurfacePolicy.MIN_BACKEND_SDK,
            unsafe = shizukuBackend.trustedDisplayUnsafe,
            ready = trustedDisplayReady(),
            installed = shizukuInstalled(),
            shizukuAvailable = shizuku.available,
            granted = shizuku.granted,
            denied = shizukuBackend.trustedDisplayDenied,
        )


    /** `sys.shell` 一条动词在界面上的样子：字面命令形状 + 此刻是否可用。 */
    /**
     * 一条 shell 动词在界面上的样子。[supported] 说这条命令的二进制在不在这台机器上：
     * 不在的那条开关点不动，界面另给一句"系统不支持此权限"，不让用户去开一个开不动的东西。
     */
    data class ShellVerbState(
        val verb: String,
        val shape: String,
        val enabled: Boolean,
        val supported: Boolean = true,
    )

    /** 只读那一组：随能力本身开合，不给逐条开关，界面上显示为固定可用。 */
    fun shellReadOnlyVerbs(): List<ShellVerbState> =
        interlock.relay.core.exec.shizuku.ShellVerbs.readOnlyShapes
            .map { ShellVerbState(it.first, it.second, true) }

    /** 会改系统那一组：每条一个开关，默认全关。表外的名字在这里根本不存在。 */
    fun shellOptInVerbs(): List<ShellVerbState> =
        interlock.relay.core.exec.shizuku.ShellVerbs.gatedShapes
            .map {
                ShellVerbState(
                    verb = it.first,
                    shape = it.second,
                    enabled = shellOptIns.isOpen(it.first),
                    supported = binaryPresent(it.first),
                )
            }

    /**
     * 该动词用的那个二进制在不在本机。只查路径存在性：应用进程读 `/system/bin` 不需要任何授权，
     * 所以这一问不依赖 Shizuku 用户服务，装好就能判。查不到的名字一律当在（不给误判成"不支持"的机会）。
     */
    private fun binaryPresent(verb: String): Boolean = when (verb) {
        "media" -> java.io.File("/system/bin/media").exists()
        "svc" -> java.io.File("/system/bin/svc").exists()
        "input" -> java.io.File("/system/bin/input").exists()
        else -> true
    }

    /**
     * 打开或关掉某一条会改系统的动词。
     *
     * 改完要重铺助手侧资产：清单里那条能力的形状说明与这里的判据同源，不重铺就会出现
     * 「界面已打开、助手拿到的还是旧形状」。表外的动词一律不写 —— 那意味着界面上
     * 出现了一个表里不存在的名，而不是用户批准了一条新命令。
     */
    fun setShellOptIn(verb: String, open: Boolean) {
        if (verb !in interlock.relay.core.exec.shizuku.ShellVerbs.gatedNames) return
        shellOptIns.set(verb, open)
        scheduleRefresh()
    }

    /**
     * 单独改一条档位。全局范围内的能力一旦手改，生效预设身份即落**混合**——
     * 即使改完的 40 条恰好又与某个预设全等，身份也不自动回预设（改回某预设请显式
     * 点选那个条目）。范围外的能力（完全 Shizuku 开关等）不动身份。
     */
    fun setTier(id: CapabilityId, tier: AccessTier) {
        tiers.setTier(id, tier)
        updateActivePreset { ActivePreset.afterTierChange(it, id) }
        scheduleRefresh()
    }

    /**
     * 一次把普通能力成员集拨到同一档。成员资格由注册表的 `privileged` 位决定（见
     * [CapabilityRegistry.ordinaryMembers]，全局拨档、能力列表与首页计数消费的是同一个
     * 成员集）：特权能力不进这个集合，也不被批量拨档波及。它只改助手审批档位：
     * 系统权限、系统弹框、封闭动词表与逐条开关都不随之变动。
     *
     * 它就是五条目全局配置里「全部禁止 / 全部询问审批 / 全部完全访问」三个直接动作；
     * 「默认配置」「常用自定义预设（配置/使用）」各走下面自己的入口。
     */
    fun setAllTiers(tier: AccessTier) {
        CapabilityRegistry.ordinaryMembers(capabilities.all()).forEach { tiers.setTier(it.id, tier) }
        updateActivePreset { ActivePreset.afterBulkTier(tier) }
        scheduleRefresh()
    }

    /**
     * 五条目里的「默认配置」直接动作：把内置默认配置写进 40 条普通能力。用户显式
     * 点选才到这里—— 引导之外的任何时点都不批量覆盖既有档位。身份落默认配置。
     */
    fun applyDefaultPreset() {
        tiers.applyPreset(TierPresets.DEFAULT)
        updateActivePreset { ActivePreset.afterDefaultApply() }
        scheduleRefresh()
    }

    /**
     * 当前生效预设的身份：读单独持久化的身份（[ActivePresetStore.resolve]）；老升级
     * 场景没有身份时按  旧判据从 40 条现值迁移一次并持久化，此后不再按值反推。
     */
    private fun activePresetIdentity(): ActivePreset {
        val current = CapabilityRegistry.ordinaryMembers(capabilities.all())
            .associate { it.id to tiers.tierOf(it.id) }
        return activePreset.resolve(current, customPreset.load())
    }

    /** 写入路径上的身份维护：迁移先保证身份存在，再按动作落新身份（不变则不写盘）。 */
    private fun updateActivePreset(next: (ActivePreset) -> ActivePreset) {
        val current = activePresetIdentity()
        val updated = next(current)
        if (updated != current) activePreset.save(updated)
    }

    /**
     * 全局选择器此刻该显示的条目：读单独持久化的身份，界面只按结果取词，不自算任何
     * 权限结论，也不按 40 条值反推（身份与值分开存的理由见 [ActivePresetStore]）。
     */
    fun globalConfigState(): GlobalConfigState = activePresetIdentity().toGlobalConfigState()

    /** 已保存的自定义配置；没保存过为 null。编辑器拿它当初始模板的优先来源。 */
    fun savedCustomPreset(): Map<CapabilityId, AccessTier>? = customPreset.load()

    fun hasSavedCustomPreset(): Boolean = customPreset.hasSaved()

    /**
     * 保存常用自定义预设：**只入库，不套用**——已应用的档位保持原样，套用必须走
     * [applyCustomPreset]。范围不符（不是 40 条普通能力全覆盖）的表拒绝写入。
     * 保存后的集合与当前 40 条全等时身份原样保留；否则落混合（入库的这份没有套用，
     * 生效档位与任何预设身份都不再对应——它不是「使用」，不冒充任何条目）。
     */
    fun saveCustomPreset(tiers: Map<CapabilityId, AccessTier>): Boolean {
        val saved = customPreset.save(tiers)
        if (saved) {
            val applied = CapabilityRegistry.ordinaryMembers(capabilities.all())
                .associate { it.id to this.tiers.tierOf(it.id) }
            updateActivePreset { ActivePreset.afterCustomSave(it, applied == tiers) }
            scheduleRefresh()
        }
        return saved
    }

    /** 五条目里常用自定义预设的「使用」：把已保存的一次性套到 40 条上。没保存过返回 false。 */
    fun applyCustomPreset(): Boolean {
        val saved = customPreset.load() ?: return false
        tiers.applyPreset(saved)
        updateActivePreset { ActivePreset.afterCustomApply() }
        scheduleRefresh()
        return true
    }

    /**
     * 完全 Shizuku 特权开关的当前档位：开=ALWAYS，关=DENIED，没有第三态。
     * 判定仍在闸门（档位决定放行与否）；这里只是把用户已选的那一档读给界面。
     */
    fun fullShellTier(): AccessTier = tiers.tierOf(CapabilityId.SYS_SHELL_FULL)

    // ──  受保护应用：界面的名单读写口 ──
    //
    // 名单不进能力清单、不进任何 manifest：助手侧看不到名单内容，只有拒绝回包里
    // 点名的那个包。UI 的入口状态（未设置 / 已保护 N 个）读 [protectedAppCount]。

    /** 名单条数：入口右侧的「已保护 N 个」读这一处。0 即「未设置」。 */
    fun protectedAppCount(): Int = protectedApps.count()

    /** 名单当前内容（UI 的勾选状态以它为准）。空集 = 未保护任何应用。 */
    fun protectedPackages(): Set<String> = protectedApps.packages()

    /**
     * 整体写入名单（勾选即生效，无保存按钮）。非法包名整体拒绝、什么都不写，
     * 返回 false 交由界面提示；成功返回 true。
     */
    fun setProtectedPackages(packages: Set<String>): Boolean = protectedApps.set(packages)

    /**
     * 拨特权开关。开即完全访问（不再有助手审批），关即禁止（闸门直接拒绝，
     * 不回退到 `sys.shell` 的封闭表）。直接生效，不弹确认；改完重铺能力清单，
     * 助手读到的 usable 才跟得上。
     */
    fun setFullShellEnabled(open: Boolean) {
        tiers.setTier(CapabilityId.SYS_SHELL_FULL, if (open) AccessTier.ALWAYS else AccessTier.DENIED)
        scheduleRefresh()
    }

    /** 特权通路的执行身份（Shizuku 服务端 uid）；读不到时为 -1，由界面如实说「未知」。 */
    fun shizukuUid(): Int = shizukuSnapshot.value.uid

    fun surfacePreference(): SurfacePreference = surfacePreferences.preference()

    fun setSurfacePreference(value: SurfacePreference) {
        surfacePreferences.setPreference(value)
        scheduleRefresh()
    }

    /** 系统权限的引导跳转。返回 null 表示本机没有可直达的设置页，界面必须退化为文字步骤。 */
    fun settingsIntent(descriptor: CapabilityDescriptor): android.content.Intent? =
        SettingsIntents.build(descriptor.settingsTarget, appContext)

    /** 悬浮窗授权是否已给。这条开关能在应用外被改动，界面读快照而不是自己问系统。 */
    fun overlayGranted(): Boolean = approvalOverlay?.canShow() == true

    /**
     * Shizuku 管理器自己的启动意图；没装 Shizuku 时为 null。
     *
     * 特权那三条能力的服务端就在这个应用里手动启停，缺一键跳转时用户只能自己去
     * 桌面找图标。未安装时界面不给这一行——一个点了没反应的入口比没有入口更糟。
     */
    fun shizukuLaunchIntent(): android.content.Intent? =
        appContext.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    fun shizukuInstalled(): Boolean = shizukuLaunchIntent() != null

    /**
     * 本模块声明的运行期权限及其授予情况。
     *
     * 列表放在装配根而不是界面里：它与能力清单里的 `uses-permission` 一一对应，
     * 是模块的事实而非渲染细节；界面只按权限名贴标签，避免两处各持一份而漂移。
     */
    fun runtimePermissionStates(): List<PermissionState> =
        requiredRuntimePermissions().map { PermissionState(it, isGranted(it)) }

    /** 一次申请全部时递给系统的列表。 */
    fun requestablePermissions(): List<String> = requiredRuntimePermissions()

    data class PermissionState(val permission: String, val granted: Boolean)

    /** 授予情况按 [Context] 的当前值现取：系统设置页改完之后这里就是最终口径。 */
    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            @Suppress("DEPRECATION")
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        // Android 14 起「只允许访问选定的照片」是一条独立权限，模块能力清单里早已声明；
        // 不进这张应用权限列表，用户在界面上就看不到也管不了它。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }
    }

    /** 助手侧能力清单。与 [capabilityRows] 同源生成，避免两份口径漂移。 */
    fun capabilitiesJson(): String {
        val array = JSONArray()
        capabilityRows().forEach { row ->
            val descriptor = row.descriptor
            // 条目的派生与序列化在 [CapabilityManifestEntries]：纯函数，与真调用共用
            // 同一张判定表，单测可直接对账，容器只负责喂已判定的状态。
            array.put(
                CapabilityManifestEntries.entry(
                    id = descriptor.id.wire,
                    category = descriptor.id.category.name.lowercase(),
                    systemPermission = row.state.system.name,
                    tier = row.state.tier,
                    sessionGranted = row.sessionGranted,
                    usable = row.usable,
                    implemented = row.implemented,
                    minSdk = descriptor.minSdk,
                    surfaces = descriptor.surfaces.map(SurfaceKind::wire),
                    guide = descriptor.guideClass.name,
                    surface = row.surface?.wire,
                    degradeReason = row.degradeReason,
                ),
            )
        }
        // 信封（协议版本、入口、宿主包名、core 标识）的拼装在 [CapabilityManifestEntries]：
        // 纯函数，core 标识可直接对账。这里只补挂装配根才知道的后端链路实时状态。
        // 这里不放生成时刻：这份串同时是「变了才重铺」的比较对象，带一个每次都变的
        // 字段等于每次都说内容变了，文件 mtime 会被无意义推进。助手要看新旧直接看 mtime。
        // 后端链路分四个环节给出：只有一个 usable 布尔时看不出断在哪一环（服务端在跑、
        // 已授权，而用户服务没连上时前三条都成立、shell 那几条照样全部不可用），
        // 各环节独立可读，才能定位断链环节。
        val envelope = CapabilityManifestEntries.envelope(
            protocol = RELAY_PROTOCOL_VERSION,
            entryScript = paths.guestScript,
            hostPackage = appContext.packageName,
            coreVersion = hostVersionName(),
            coreBuild = hostVersionCode(),
            capabilities = array,
        )
        envelope.put(
            "backend",
            JSONObject().apply {
                put("shizuku", JSONObject().apply {
                    val snapshot = shizukuSnapshot.value
                    put("running", snapshot.running)
                    put("authorized", snapshot.granted)
                    put("bound", shizukuBackend.boundSnapshot)
                    put("shellUid", snapshot.uid)
                    put("version", snapshot.version)
                    put("trustedDisplay", JSONObject().apply {
                        // 那块屏归用户服务进程持有：线都断了，缓存里的编号再像是在说话也是谎。
                        // 两个条件并一个，是为了让这段 JSON 里不出现「前三条都断了而屏还在」
                        // 这种自相矛盾的组合——助手按它既不会去重绑也不会去建屏。
                        put("alive", shizukuBackend.boundSnapshot && shizukuBackend.displayAlive())
                        // 这台机器拒绝过可信建屏：助手读清单就该看见它，而不是把「再建一次屏」
                        // 当下一步。拒绝由建屏成功或重连对账解除——两个端点都在这里同时
                        // 反映到 surface.virtual 条目的 usable/degradeReason，同一份事实两处同口径。
                        put("denied", shizukuBackend.trustedDisplayDenied)
                        // 运行时自检判过「可信屏破坏主屏」：屏已当场释放（或 release 失败残留）、
                        // 这台设备被标记。与 denied 各是各的病历。它**只封后台落点**——本能力
                        // 的 create/release/query 仍可调：create 会对屏复检并在通过后解除标记，
                        // release 清掉残留屏也解除；给助手的下一步是改前台模式，而不是把这条
                        // 能力整行封死。
                        put("unsafe", shizukuBackend.trustedDisplayUnsafe)
                        put("displayId", shizukuBackend.displayId)
                        // 节点级那十一条也声明在这块屏上，但"屏在"不等于"读得到那棵树的窗口"：
                        // 无障碍给不给第二块屏的窗口是平台的事，我们只能按上一次真取到的结果说。
                        // 助手在规划阶段就该看见这个词，而不是跑到一半才撞上一条报错。
                        put("nodeTree", a11yBackend.trustedNodeTreeState(shizukuBackend.displayId))
                    })
                })
            },
        )
        return envelope.toString(2) + "\n"
    }

    /** 系统媒体库里的落点：不受产物配额约束，必须单独报数。 */
    private val mediaLibrary = MediaLibraryUsage(appContext, pathPolicy.mediaAlbumDir)

    fun storageSnapshot(): StorageSnapshot {
        val out = reaper.outUsage()
        return StorageSnapshot(
            usedBytes = ledger.totalBytes,
            maxBytes = ledger.maxBytes,
            artifacts = ledger.artifactCount,
            runLogBytes = runLog.usageBytes(),
            auditBytes = auditLog.usageBytes(),
            uploadsBytes = reaper.uploadsBytes(),
            mailboxBytes = reaper.mailboxBytes(),
            galleryBytes = mediaLibrary.galleryBytes(),
            audioBytes = mediaLibrary.audioBytes(),
            outBytes = out.bytes,
            outFiles = out.files,
            outByKindBytes = out.byKind,
            accessibilityOn = RelayAccessibilityService.current() != null,
            shizukuOn = shizuku.available,
        )
    }

    data class StorageSnapshot(
        val usedBytes: Long,
        val maxBytes: Long,
        val artifacts: Int,
        /** 运行日志与授权记录分开报：合并成一行会让用户以为记录文件是日志的一部分。 */
        val runLogBytes: Long,
        val auditBytes: Long,
        val uploadsBytes: Long,
        val mailboxBytes: Long,
        /** 系统媒体库里的图片与视频（相册能看到的那两类）。 */
        val galleryBytes: Long,
        /** 系统媒体库里的音频（相册看不到，由音乐应用消费）。 */
        val audioBytes: Long,
        /**
         * 交付目录（`out/`）的用量与四类分解。单独一行的理由：它是助手与用户真正消费的产物，
         * 「立即清理」清的就是这里——不报出来，用户只会看到"产物 0 B 而磁盘在涨"。
         */
        val outBytes: Long,
        val outFiles: Int,
        val outByKindBytes: Map<String, Long>,
        val accessibilityOn: Boolean,
        val shizukuOn: Boolean,
    ) {
        /**
         * 占用页上统计到的**每一项**之和，供概览页那一行读一个总数用。
         *
         * 逐项相加而不是另取一个"总"读数：页面上看到哪几行，这里就加哪几行，两处不会各自漂移。
         * 注意它包含不受「立即清理」管辖的项（日志、授权记录、媒体库），
         * 所以这个数**不是**"清理能释放多少"。
         */
        val totalBytes: Long
            get() = usedBytes + outBytes + uploadsBytes + mailboxBytes +
                runLogBytes + auditBytes + galleryBytes + audioBytes
    }

    fun clearArtifacts(): Long? = reaper.clearAllArtifacts()

    /** 本安装包的版本名与版本号：能力清单的构建标识从这两个值取，缺省如实给空。 */
    private fun hostVersionName(): String =
        runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }
            .getOrNull().orEmpty()

    private fun hostVersionCode(): Long =
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            }
        }.getOrDefault(0L)

    /** 界面用的状态快照。含文件读取，必须在非界面线程调用。 */
    fun diagnostics(): RelayDiagnostics = RelayDiagnostics(
        events = runLog.snapshot(),
        auditLines = auditLog.recent(DIAGNOSTIC_AUDIT_LINES),
    )

    fun isVerboseLog(): Boolean = verbosePref()

    fun setVerboseLog(enabled: Boolean) {
        prefsApi.putBoolean(RelayPrefs.FILE_MAIN, KEY_VERBOSE, enabled)
    }

    fun isChannelRunning(): Boolean = mailbox.isRunning

    /**
     * 工作循环探活读数。`isChannelRunning` 只回答「消费协程还在不在」，回答不了
     * 「它有没有在推进」——实测事故里循环活着而执行器停滞，界面却照常写着运行中。
     * 通道未运行时返回 null：调用方把「已停止」与「运行但无进展」分开说，
     * 后者才指向真正要查的那一环。
     */
    fun workLoopHealth(): MailboxServer.WorkLoopHealth? =
        if (mailbox.isRunning) mailbox.workLoopHealth() else null

    /** 沙盒侧那份使用说明的宿主副本。界面直接呈现同一份文本，不另写一套。 */
    fun usageText(): String? = runCatching {
        if (paths.usageFile.isFile) paths.usageFile.readText() else null
    }.getOrNull()

    /** 一次刷新要读的日志与记录。分开取会让界面拿到两个时刻的状态。 */
    data class RelayDiagnostics(val events: List<RunLog.Entry>, val auditLines: List<String>)

    /** 摘要盐：低熵参数不落原文也不落可字典枚举的裸摘要。 */
    private fun digestSalt(): String {
        prefsApi.getString(RelayPrefs.FILE_MAIN, KEY_SALT, null)?.let { return it }
        val generated = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefsApi.putString(RelayPrefs.FILE_MAIN, KEY_SALT, generated)
        return generated
    }

    companion object {
        internal const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** 前台所有者名：助手独立页（PilotActivity）。 */
        const val FOREGROUND_OWNER_PILOT = "pilot_activity"

        /** 前台所有者名：宿主页签内嵌的助手页（MainScreen）。 */
        const val FOREGROUND_OWNER_HOST = "host_main"

        /**
         * 「trusted 建屏被这台机器拒过」对这一条能力此刻还算不算数，纯判据供 JVM 单测：
         * 只作用于建屏那条能力（其余能力不建屏，拒绝与它们的落地无关），标记为真即命中；
         * 清除只有两个时刻——建屏真的成功、或重连后对账确认屏活着（标记由后端写回）。
         */
        /**
         * 「优先后台」缺环判定的纯表（生产 [backgroundSurfaceBlock] 与 JVM
         * 测试同走）。unsafe 排在 ready 之前：残留屏（release 失败）也必须显示
         * DISPLAY_UNSAFE，不让"屏在"被读成健康——与 [trustedDisplayReady] 的 unsafe
         * 因子同一份事实两处表达。
         */
        internal fun backgroundBlockFor(
            backgroundPreferred: Boolean,
            versionOk: Boolean,
            unsafe: Boolean,
            ready: Boolean,
            installed: Boolean,
            shizukuAvailable: Boolean,
            granted: Boolean,
            denied: Boolean,
        ): BackgroundSurfaceBlock? = when {
            !backgroundPreferred -> null
            !versionOk -> BackgroundSurfaceBlock.ANDROID_VERSION
            unsafe -> BackgroundSurfaceBlock.DISPLAY_UNSAFE
            ready -> null
            !installed -> BackgroundSurfaceBlock.SHIZUKU_NOT_INSTALLED
            !shizukuAvailable -> BackgroundSurfaceBlock.SHIZUKU_NOT_RUNNING
            !granted -> BackgroundSurfaceBlock.SHIZUKU_NOT_GRANTED
            // 前三环都对：屏有两个独立的缺席原因。系统拒绝过建屏（这台机器给不出可信屏）
            // 与"屏还没建出来"是两件事——把拒绝说成"还没建"，界面就会一直让人去建，
            // 而建再多次也都是那个拒绝。
            denied -> BackgroundSurfaceBlock.DISPLAY_REFUSED
            else -> BackgroundSurfaceBlock.DISPLAY_NOT_CREATED
        }

        internal fun trustedDisplayRefusal(id: CapabilityId, denialMarked: Boolean): Boolean =
            id == CapabilityId.SURFACE_VIRTUAL && denialMarked

        /**
         * 能力行 degradeReason 的顺序，纯判据供 JVM 单测穷举：被拒优先于"模式够不着"。
         * 顺序写反会让调用方回绕：用户被拒后切了前台，surface.virtual 的裁决会先给出
         * E_SURFACE_MODE_REQUIRED（前台不在允许集），清单与插件就按"切回后台/建屏"建议，
         * 用户又切回后台再撞一次拒绝，绕成循环——拒绝是这台机器上的固定结论，永远优先。
         *
         * unsafe 不在这一表里（复核）：「自检判过破坏主屏」不降任何清单行的
         * usable/degradeReason——surface.virtual 必须保持可调，create/release/query 是
         * 复检与解除标记的通路；unsafe 在清单上的表达是 manifest 的
         * `trustedDisplay.unsafe` 字段与后台各行的 supports 真值表，两处读的都是
         * ShizukuBackend 的同一个标记。
         */
        internal fun degradeReasonFor(
            systemRefused: Boolean,
            modeRequired: Boolean,
            decisionReason: String?,
        ): String? = when {
            systemRefused -> RelayError.SURFACE_TRUSTED_DENIED.code
            modeRequired -> RelayError.SURFACE_MODE_REQUIRED.code
            else -> decisionReason
        }

        /**
         * 首页「可用能力」的计数判据，纯函数供 JVM 单测：**只数 usable**。历史口径
         * [CapabilityRow.enabled] 说的是"已实现且档位开着且有实现"，含不了"此刻够不够得着"
         * ——按它数会出现"首页说 N 条可用、点进去缺授权/缺后端"的两套话。分母是普通能力
         * 条数（成员过滤在 [CapabilityRegistry.ordinaryMembers]，特权能力不进）。
         */
        fun readyUsableCount(rows: List<CapabilityRow>): Int = rows.count { it.usable }

        internal const val KEY_SALT = RelayPrefs.KEY_DIGEST_SALT
        private const val KEY_VERBOSE = "verbose_log"
        private const val SALT_BYTES = 16
        private const val DIAGNOSTIC_AUDIT_LINES = 40
    }
}

/**
 * 内建后端表经宿主的执行装配口（[RelayExecutor]）过一道，得到分发器实际可用的集合。
 *
 * 抽成独立函数是为了让这条接线能在纯 JVM 单测里被覆盖：容器本身要 Context，装不起来，
 * 而接线漏掉时既没有编译错误也没有运行期信号——宿主传进来的装配口会被默默丢弃。
 */
internal fun configuredBackends(
    executor: RelayExecutor?,
    builtIn: Map<BackendId, RelayBackend>,
): Map<BackendId, RelayBackend> = (executor ?: RelayExecutor.BUILT_IN).configure(builtIn)
