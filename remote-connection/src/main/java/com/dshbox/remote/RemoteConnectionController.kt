package com.dshbox.remote

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.api.DshSelectedFile
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.EndpointChoice
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.SessionMode
import com.dshbox.remote.model.SessionState
import com.dshbox.remote.parse.AnonRtParser
import com.dshbox.remote.parse.ChongYepDelimitedParser
import com.dshbox.remote.parse.DsHarnessRemoteParser
import com.dshbox.remote.parse.DshRemoteSchemeParser
import com.dshbox.remote.parse.PairingParseException
import com.dshbox.remote.parse.ParserIds
import com.dshbox.remote.parse.ParserRegistry
import com.dshbox.remote.parse.SayaChJsonParser
import com.dshbox.remote.parse.ZcodeRemoteParser
import com.dshbox.remote.parse.p0UrlParsers
import com.dshbox.remote.model.retentionPolicyFromArchive
import com.dshbox.remote.model.toArchiveValue
import com.dshbox.remote.session.DefaultSessionFactory
import com.dshbox.remote.session.ForegroundElapsedClock
import com.dshbox.remote.session.KeyValueStore
import com.dshbox.remote.session.PairingArchiver
import com.dshbox.remote.session.ProxySession
import com.dshbox.remote.session.RetentionSettings
import com.dshbox.remote.session.SessionFactory
import com.dshbox.remote.session.SharedPreferencesStore
import com.dshbox.remote.session.VolatileKeyValueStore
import com.dshbox.remote.session.loopbackSlotPool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 远程连接模块对外门面：远程面板与 DSH 显示槽共用的唯一状态源。
 *
 * 生命周期随宿主组合（宿主以 remember 创建，随组合销毁）：
 * 设备清单与登录态存档的持久化在模块内部自管，门面重建不丢数据；组合销毁
 * 时宿主须调用 [close] 释放全部会话与代理监听（防止 Activity 重建泄漏代理
 * 线程与端口），close 之后的建立与回填一律拒收。
 *
 * 会话生命周期（Idle → Connecting → Loading → Ready，失败转 Failed 并记录
 * 原因，断连转 Disconnected，策略释放转 Released，删除设备转 Terminated）：
 * [selectEndpoint] 为已建档端点建立代理会话；渲染事件入口（notifyRenderer*）
 * 把渲染器的加载/就绪/失败/断连映射进状态机；切换显示源或离开 DSH 页签时
 * 按 [RetentionPolicy] 消费失活会话——IMMEDIATE 即时释放，Retain 以前台
 * 时钟倒计时到点释放（恢复前台时补偿判定）；FOREVER 保留至进程结束。
 * 生效档取端点覆盖（[retentionFor]），无覆盖时用全局默认（[retention]）。
 *
 * 在途建立的存活判定用「设备作废集合 + 端点代次」而非失效标记：删除设备
 * 按设备标识一次性作废（读时不清账）；端点地址编辑自增代次，登记时的代次
 * 快照与回填时不一致的建立结果一律废弃。两个判据都不依赖读时清账，不存
 * 在「标记先落、登记后清」的孤儿窗口。
 */
/** 一次下载结束通知：[success] = 是否成功落盘，id 只用于触发一次 UI 事件。 */
data class DownloadNotice(
    val id: Long,
    val success: Boolean,
    /** 失败原因码（dir_unavailable/permission/origin/http/io），成功为 null。 */
    val reason: String? = null,
)

class RemoteConnectionController(
    /** 门面内部协程作用域：会话状态机与保留倒计时的驱动入口。 */
    private val scope: CoroutineScope,
    /** 宿主注入：渲染引擎适配器（显示槽经它创建渲染器）。 */
    private val host: RemoteConnectionHost,
    /** 会话工厂覆写（测试注入）：null = 按宿主上下文创建生产工厂。 */
    sessionFactoryOverride: SessionFactory? = null,
    /**
     * 配对解析注册表：扫码与手动输入共用的两级路由入口。
     *
     * 接入口径：网关、邀请、通用票据与 anonRT 四类插件走 URL 签名解析器
     * （anonRT 的票据生命周期同通用通道）；ZCode 工作区与 DSH Remote
     * 以账号型直连 URL 签名解析器参与（注册在通用兜底之前）；dsh-Remote
     * 的自定义 scheme、ChongYep 的竖线分隔文本与 saya-ch 的 JSON 配对描述
     * 符各注册专用解析器。SnowfallC 与 mrRisega 不注册专用解析器——其 URL
     * 载荷由通用 URL 通道承载（透明代理、无凭据注入；mrRisega 仅自托管形
     * 态可用）。
     */
    private val registry: ParserRegistry = ParserRegistry(
        urlParsers = p0UrlParsers() + listOf(
            AnonRtParser(),
            ZcodeRemoteParser(),
            DsHarnessRemoteParser(),
            SayaChJsonParser(),
        ),
        schemeParsers = listOf(DshRemoteSchemeParser()),
        jsonParsers = listOf(SayaChJsonParser()),
        delimitedParsers = listOf(ChongYepDelimitedParser()),
    ),
    /** 会话建立线程：代理启动为阻塞操作，避免占用调用方线程。 */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** 前台累计时钟：保留档倒计时只在前台推进，时间源可注入供单测驱动。 */
    private val foregroundClock: ForegroundElapsedClock = ForegroundElapsedClock(),
    /**
     * 全局默认保留档的设置存储：null 时按宿主上下文构建生产偏好存储，仍无
     * 出口（JVM 单测/骨架宿主）则退化为内存兜底（重启回默认档）。
     */
    settingsStore: KeyValueStore? = null,
    /**
     * DSH 回车键「换行」档读取口（默认恒 true = 换行，与渲染器配置默认一
     * 致）：显示槽装配渲染器时经 [RemoteSessionSurface] 读现值传入渲染器
     * 配置，页面注入层据此把裸 Enter 映射为换行（换行档）或保持页面既有
     * 发送行为（发送档）。宿主（app 侧）读设置传入。
     */
    val enterKeyNewlineProvider: () -> Boolean = { true },
) {
    /** 下载结束事件（true = 落盘成功）：远程会话据此拉起底部下载器。 */
    private val downloadEventSeq = java.util.concurrent.atomic.AtomicLong()
    private val downloadEventsFlow = MutableStateFlow(DownloadNotice(0L, false))
    val downloadEvents: StateFlow<DownloadNotice> = downloadEventsFlow.asStateFlow()

    /** 宿主下载目录（null = 无下载能力）：下载器列表与路径展示共用。 */
    val downloadDirectory: java.io.File? = host.downloadDirectory()

    /** 下载落点所需权限是否已授予（下载器据此显示引导）。 */
    fun hasDownloadPermission(): Boolean = host.hasDownloadPermission()

    /** 打开系统存储权限页（下载器的「去开启」动作）。 */
    fun requestDownloadPermission() = host.requestDownloadPermission()

    /**
     * 打开下载目录（宿主接线，如跳转文件页并定位共享区下载目录）：false =
     * 宿主无可用入口，下载器保持现状。
     */
    fun openDownloadsFolder(): Boolean = host.openDownloadsFolder()

    /** 下载目录内的文件，最新修改时间在前（目录即账本，不另做数据库）。 */
    fun downloadedFiles(): List<java.io.File> = downloadDirectory
        ?.listFiles()
        ?.filter { it.isFile }
        ?.sortedByDescending { it.lastModified() }
        .orEmpty()

    /** 会话工厂：覆写优先，否则按宿主上下文创建生产工厂（无上下文 = 骨架）。 */
    private val sessionFactory: SessionFactory? by lazy {
        sessionFactoryOverride ?: host.appContext()?.let {
            // 门禁 cookie 依赖渲染引擎的 CookieManager 注入路径：gecko 变体
            // 的该路径暂不可用，其会话启用代理侧 bypass 门禁（无门禁运行，
            // 回环监听本身仍仅本机可达）。
            DefaultSessionFactory(
                it,
                gateDeliveryEnabled = host.rendererAdapter().id == GATE_CAPABLE_ADAPTER_ID,
                // 下载目录在宿主处解析（默认 null = 无下载能力，响应照旧透传）
                downloadDir = host.downloadDirectory(),
                onDownloadFinished = { success, reason ->
                    downloadEventsFlow.value = DownloadNotice(
                        id = downloadEventSeq.incrementAndGet(),
                        success = success,
                        reason = reason,
                    )
                },
            )
        }
    }

    private val devicesFlow = MutableStateFlow(emptyList<Device>())

    /** 已配对设备清单（清单顶部的「本机」固定项由面板自绘，不入清单）。 */
    val devices: StateFlow<List<Device>> = devicesFlow.asStateFlow()

    private val activeDisplayFlow = MutableStateFlow<DisplayTarget>(DisplayTarget.Local)

    /** 当前显示指向：DSH 页签据此决定呈现本机页面还是远程会话。 */
    val activeDisplay: StateFlow<DisplayTarget> = activeDisplayFlow.asStateFlow()

    /** 全局默认保留档的设置存储（构造期读初值，重启不丢）。 */
    private val retentionSettings = RetentionSettings(
        settingsStore
            ?: host.appContext()?.let {
                SharedPreferencesStore.of(it, RetentionSettings.PREFERENCES_NAME)
            }
            ?: VolatileKeyValueStore(),
    )

    private val retentionFlow = MutableStateFlow(retentionSettings.default())

    /** 全局默认保留策略：失活会话（渲染器与代理）的保留时机；端点可单独覆盖。 */
    val retention: StateFlow<RetentionPolicy> = retentionFlow.asStateFlow()

    private val hotEndpointsFlow = MutableStateFlow<Set<EndpointId>>(emptySet())

    /**
     * 页面热池：渲染器表面需要驻留组合的端点集合（在册会话 + 当前显示目标）。
     * 保留档非 IMMEDIATE 的失活会话留在池内（切走不重载页面）；释放/删除/
     * 保留到期/改档 IMMEDIATE 即移出——对应表面离开组合，渲染器随既有
     * DisposableEffect 释放。
     */
    val hotEndpoints: StateFlow<Set<EndpointId>> = hotEndpointsFlow.asStateFlow()

    private val sessionStatesFlow =
        MutableStateFlow<Map<EndpointId, SessionState>>(emptyMap())

    /** 各端点的会话状态（按端点标识记账）。 */
    val sessionStates: StateFlow<Map<EndpointId, SessionState>> = sessionStatesFlow.asStateFlow()

    private val sessionFailureReasonsFlow =
        MutableStateFlow<Map<EndpointId, String>>(emptyMap())

    /** Failed/断连会话的具体原因（不可达/超时等），供连接态覆盖层呈现。 */
    val sessionFailureReasons: StateFlow<Map<EndpointId, String>> =
        sessionFailureReasonsFlow.asStateFlow()

    private val sessionOriginsFlow = MutableStateFlow<Map<EndpointId, String>>(emptyMap())

    /** 已就绪会话的回环 origin（端点标识 → 渲染器加载地址）。 */
    val sessionOrigins: StateFlow<Map<EndpointId, String>> = sessionOriginsFlow.asStateFlow()

    private val sessionUpstreamOriginsFlow =
        MutableStateFlow<Map<EndpointId, String>>(emptyMap())

    /**
     * 已就绪会话的上游真实 origin（端点标识 → 代理转发目标）：显示槽据此把
     * 页内链接按「同一上游站点就地打开 / 其它域交系统浏览器」分流。随会话
     * 落账写入、随会话停止移除（与 [sessionOrigins] 同口径）。
     */
    val sessionUpstreamOrigins: StateFlow<Map<EndpointId, String>> =
        sessionUpstreamOriginsFlow.asStateFlow()

    private val rendererProgressFlow = MutableStateFlow<Map<EndpointId, Int>>(emptyMap())

    /** 渲染器加载进度（0-100，按端点记账）：顶部进度条呈现 1..99。 */
    val rendererProgress: StateFlow<Map<EndpointId, Int>> = rendererProgressFlow.asStateFlow()

    private val pendingCredentialFlow = MutableStateFlow<Set<EndpointId>>(emptySet())

    /**
     * 未消费凭据集合的专用锁：消费提交（监听摘除）、整集刷新、设备删除与
     * 异步初值合并的读-改-写经同一把锁串行化，防止整集赋值覆盖并发的单点
     * 摘除。声明位置早于 init 块——Unconfined 调度下 eager 执行不得读取
     * 未初始化字段。
     */
    private val pendingCredentialLock = Any()

    private val pairingRejectedFlow = MutableStateFlow<Set<EndpointId>>(emptySet())

    /**
     * 最近一次失败为「一次性凭据被拒」的端点集合：覆盖层据此以专属标题
     * 呈现（布尔驱动，不走失败文案判等）。
     */
    val pairingRejected: StateFlow<Set<EndpointId>> = pairingRejectedFlow.asStateFlow()

    /**
     * 当前持有未消费一次性凭据的端点集合：显示槽据此把 401/403 失败翻译成
     * 「重新配对」引导（已消费票据的 401 是普通认证失败，不误导）。
     */
    val pendingCredential: StateFlow<Set<EndpointId>> = pendingCredentialFlow.asStateFlow()

    private val showDshRequestsFlow = MutableStateFlow(0)

    /**
     * 显示请求计数：用户主动选择端点且建立受理时自增，宿主观察后把界面切
     * 到 DSH 页签（面板页签隐藏时选择端点，是「想看到它」的语义动作）。
     */
    val showDshRequests: StateFlow<Int> = showDshRequestsFlow.asStateFlow()

    /** DSH 页签是否选中：在途建立回填落账时据此对会话执行失活处置。
     *  与豁免标记同在会话表锁内读写（豁免消费要基于同锁下的一致视图）。 */
    private var dshTabVisible: Boolean = true

    /**
     * 显示豁免（端点标识）：用户主动选择产生的回填豁免一次失活处置——
     * 显示请求与页签切换生效之间存在时序差，豁免防止落账会话被立即释放；
     * 消费于本次回填或下一次页签状态事件。与 [dshTabVisible] 同锁读写。
     */
    private var displayExemption: EndpointId? = null

    /** 门面是否已关闭（组合销毁）：关闭后的建立与回填一律拒收。 */
    @Volatile
    private var closed: Boolean = false

    /**
     * 运行中的代理会话（端点标识 → 会话句柄）。
     *
     * 全部读写统一经 [liveSessions] 对象锁串行化：[selectEndpoint] 的
     * 「已在运行则免重建」检查、保留倒计时与释放路径、异步 start 完成后的
     * 回填互斥，避免读-改-写交错产生重复会话或漏停。
     */
    private val liveSessions = HashMap<EndpointId, ProxySession>()

    /** 在途建立中的端点（start 尚未完成）：同一端点的并发/重复选择只发起一次建立。 */
    private val pendingStarts = HashSet<EndpointId>()

    /**
     * 已删除设备的一次性作废登记（按设备标识，读时不清账）：删除时刻在途
     * 的建立完成回填前据此拒收，防止已销毁设备复活为孤儿会话。设备标识
     * 全局唯一不复用，集合无清空需求（增长以删除次数为界）。
     */
    private val voidedDevices = HashSet<DeviceId>()

    /** 保留档失活会话的倒计时任务（端点标识 → 任务），随 liveSessions 锁记账。 */
    private val retentionJobs = HashMap<EndpointId, Job>()

    /** 保留档失活会话的释放期限（前台时钟口径），随 liveSessions 锁记账。 */
    private val retentionDeadlines = HashMap<EndpointId, Long>()

    /** 保留档失活时刻（前台时钟口径）：策略改档时按原失活时刻重算期限。 */
    private val retentionInactiveAt = HashMap<EndpointId, Long>()

    /**
     * 保留设置落盘的写锁：默认档与端点覆盖两个入口的存档写入经同一把锁
     * 串行化，快速连改时后写覆盖前写（落盘顺序与生效顺序一致）。
     */
    private val persistenceWriteMutex = Mutex()

    /** 热池超容量的一次性告警标记：只在首次触发时记日志，不刷屏。 */
    private var hotPoolCapWarned = false

    /** 页面热池容量上限：回环槽位池的规模（每端点独占一个回环地址，天然上限）。 */
    val hotPoolCapacity = loopbackSlotPool.size

    /** 配对建档出口（生产会话工厂实现）；不可用时配对入口保持不可用呈现。 */
    private val pairingArchiver: PairingArchiver? by lazy { sessionFactory as? PairingArchiver }

    init {
        // 会话工厂构造失败（Keystore/SharedPreferences 损坏等）不得让整个
        // Controller 创建崩溃：降级为骨架（无会话能力），配对入口按不可用呈现
        val factory = runCatching { sessionFactory }.onFailure {
            android.util.Log.w("RemoteSession", "session factory init failed", it)
        }.getOrNull()
        if (factory != null) {
            devicesFlow.value = sortDevices(factory.devices())
        }
        // 一次性凭据消费事件（代理观测到兑换完成）驱动未消费集合刷新
        factory?.credentialConsumedListener = { endpointId ->
            synchronized(pendingCredentialLock) {
                pendingCredentialFlow.value = pendingCredentialFlow.value - endpointId
            }
        }
        // 初值异步加载（Keystore/文件扫描不得占用主线程）：初值空集，加载
        // 完成后按锁合并——加载期间发生的摘除/新增在锁内顺序生效，整集替换
        // 以读档时刻的存档为准（删除先于读取完成的设备不会复活）。放在全部
        // 属性初始化之后：Unconfined 调度下 eager 执行不得读取未初始化字段。
        scope.launch(ioDispatcher) {
            val loaded = runCatching {
                pairingArchiver?.pendingCredentialEndpoints().orEmpty()
            }.onFailure {
                android.util.Log.w("RemoteSession", "pendingCredential initial load failed", it)
            }.getOrDefault(emptyList())
            synchronized(pendingCredentialLock) {
                pendingCredentialFlow.value = pendingCredentialFlow.value + loaded
            }
        }
    }

    /**
     * 解析配对码/链接并纳入设备清单：注册表两级路由解析 → 建档（生成全局
     * 唯一标识、代次归零）→ 端点凭据入加密存档 → 清单刷新。解析失败与建档
     * 失败都以失败返回（错误文案已经脱敏），面板据此呈现原因。
     *
     * [target] 为确认卡收集的归属目标（新建设备 / 并入既有设备 / 并入无分
     * 组）：建档出口按目标落档，并入目标设备不在档时失败返回（确认期间被
     * 删除）。
     *
     * [choices] 为确认卡收集的每端点选择（确认前用户对会话模式与保留档
     * 的落档意向）：先经 [com.dshbox.remote.model.reconcileConfirmChoices]
     * 与解析端点核对——已提供前缀逐条生效、缺项按解析默认值补齐、多余项
     * 丢弃（null 选择即全部默认）；核对后的选择随建档一次写入端点档案。
     * 直连口径的落档收口在建档出口二次兜底（同一口径函数），两道防线同源。
     */
    suspend fun addFromPayload(
        raw: String,
        displayName: String? = null,
        choices: List<EndpointChoice>? = null,
        target: com.dshbox.remote.model.PairingTarget = com.dshbox.remote.model.PairingTarget.NewGroup(null),
    ): Result<Device> {
        val archiver = pairingArchiver
            ?: return Result.failure(UnsupportedOperationException("Remote pairing is not available yet"))
        val pairing = registry.parse(raw).getOrElse { error ->
            return Result.failure(
                if (error is PairingParseException) {
                    error
                } else {
                    PairingParseException(error.message ?: "Pairing failed")
                },
            )
        }
        // 选择核对在解析后、落档前完成（与预填同源推导）：生产与测试的
        // 默认值口径统一收口到 defaultConfirmChoices
        val reconciled = com.dshbox.remote.model.reconcileConfirmChoices(
            parsed = pairing,
            choices = choices?.let { com.dshbox.remote.model.PairingConfirmChoices(it) },
        )
        return runCatching {
            // 建档涉及 Keystore 与文件 IO：移出调用方线程
            withContext(ioDispatcher) { archiver.archivePairing(pairing, displayName, reconciled, target) }
        }.onSuccess {
            reloadDevices()
            refreshPendingCredential()
        }
    }

    /**
     * 从建档出口重读设备清单并入账：清单按「命名分组在前、无分组（名称为
     * 空串的档案条目）垫底、组内按建档时间」排序（无分组条目无论建档先后
     * 都渲染在所有分组之后）。
     */
    private fun reloadDevices() {
        devicesFlow.value = sortDevices(sessionFactory?.devices().orEmpty())
    }

    /**
     * 设备清单的展示排序：命名分组在前（组内按建档时间），无分组（名称为
     * 空串）恒垫底——初值装载与档案变更后的入账共用同一口径。组内排序在
     * 此显式保证（建档出口的存档顺序即按建档时间，此处兜底不依赖出口口径）。
     */
    private fun sortDevices(loaded: List<Device>): List<Device> {
        val (grouped, loose) = loaded.partition { it.name.isNotEmpty() }
        return grouped.sortedBy { it.addedAtMillis } + loose
    }

    /** 端点编辑/重新配对的结果。 */
    enum class EndpointUpdateResult {

        /** 档案已更新（调用方应释放该端点在用会话）。 */
        UPDATED,

        /** 地址与凭据均未变化：无变更，不是错误。 */
        UNCHANGED,

        /** 载荷解析失败（非法或非 http(s) 地址）。 */
        INVALID,
    }

    /**
     * 配对预览（扫码/手动输入的建档前确认卡数据）：解析成功后的展示快照。
     */
    data class PairingPreview(
        /** 命中的解析器标识（确认卡据此呈现插件类型）。 */
        val parserId: String,
        /** 设备显示名（载荷自带或解析器默认名）。 */
        val deviceName: String,
        /** 首条端点的目标地址（已剥凭据）。 */
        val targetUrl: String,
        /** 载荷携带的端点数量。 */
        val endpointCount: Int,
        /** 是否通用网页通道（确认卡追加按普通网页代理的提示）。 */
        val isGeneric: Boolean,
        /**
         * 各端点的确认卡展示数据（数组序与落档顺序一致）：确认卡的逐项
         * 选择（通道/地址摘要 + 会话模式 chips）按此渲染。空端点载荷不在
         * 预览产出（解析期失败返回）。
         */
        val endpoints: List<EndpointPreview> = emptyList(),
    )

    /** 确认卡单端点的展示行（不含任何凭据）。 */
    data class EndpointPreview(
        /** 通道标签（LAN/PUBLIC/TUNNEL/URL 枚举名或解析器备注）。 */
        val channelLabel: String,
        /** 目标地址摘要（解析层已剥凭据）。 */
        val targetUrl: String,
        /**
         * 解析默认会话模式（载荷声明经直连口径收口后的存储枚举名）：
         * 确认卡会话模式 chips 的预填值。
         */
        val defaultSessionMode: String,
    )

    /**
     * 解析配对码/链接但只解析不建档（建档仍走 [addFromPayload]）：注册表两
     * 级路由解析后提炼确认卡展示数据。解析失败与空端点载荷都以脱敏后的
     * failure 返回（文案不携带载荷与凭据；空端点不把 NoSuchElementException
     * 抛在 Result.map 内）。
     */
    fun previewPairing(raw: String): Result<PairingPreview> = registry.parse(raw).mapCatching { pairing ->
        val endpoint = pairing.endpoints.firstOrNull()
            ?: throw PairingParseException("Pairing payload carries no endpoints")
        // 默认会话模式经纯函数收口（非 https 目标回落代理档）：确认卡 chips
        // 预填与建档落档共用同一推导（defaultConfirmChoices）
        val defaults = com.dshbox.remote.model.defaultConfirmChoices(pairing)
        PairingPreview(
            parserId = pairing.parserId,
            deviceName = pairing.deviceName,
            targetUrl = endpoint.targetUrl,
            endpointCount = pairing.endpoints.size,
            isGeneric = pairing.parserId == ParserIds.GENERIC,
            endpoints = pairing.endpoints.mapIndexed { index, parsed ->
                EndpointPreview(
                    channelLabel = parsed.channelLabel,
                    targetUrl = parsed.targetUrl,
                    defaultSessionMode = defaults.endpoints[index].sessionMode,
                )
            },
        )
    }

    /**
     * 编辑/重新配对端点：粘贴的新配对载荷经注册表解析归一——剥离凭据的
     * 地址入清单、解析器/通道对齐新载荷、端点代次自增（在途建立按漂移废
     * 弃）。载荷带凭据时替换进存档并清除消费标记（重新配对）；裸地址编辑
     * 保留原凭据与消费标记。地址与凭据均未变化返回 [EndpointUpdateResult.
     * UNCHANGED]（无变更，不是错误）。
     * 会话模式取值随 [sessionMode]：非空为用户显式选择（优先于载荷声明，
     * 只作用于触发端点），null 走载荷/既有口径；对齐结果一律经会话模式
     * 口径函数收口（直连要求 https），存量 `非 https + 直连` 死档由重配
     * 对一并修复为代理档。
     * 更新成功且端点会话在用：主动释放（Released），由用户重选或回页签重建
     * ——旧会话的目标已过期，不静默沿用。档案发生变更的端点不止触发端点
     * （多端点载荷按 host 同步更新兄弟端点）时，全部在用会话一并释放：兄
     * 弟会话仍持有旧令牌/旧 CA，静默沿用无效。
     */
    suspend fun updateEndpointTarget(
        deviceId: DeviceId,
        endpointId: EndpointId,
        rawUrl: String,
        sessionMode: String? = null,
    ): EndpointUpdateResult {
        val archiver = pairingArchiver ?: return EndpointUpdateResult.INVALID
        val pairing = registry.parse(rawUrl).getOrElse { return EndpointUpdateResult.INVALID }
        val changed = withContext(ioDispatcher) {
            archiver.rePairEndpoint(deviceId, endpointId, pairing, sessionMode)
        }
        if (changed.isEmpty()) return EndpointUpdateResult.UNCHANGED
        reloadDevices()
        refreshPendingCredential()
        // 档案已变更的在用会话统一释放（含同步更新的兄弟端点）；在途建立由
        // 回填的代次漂移判定废弃，不经此路径
        val liveChanged = synchronized(liveSessions) { changed.filter { it in liveSessions } }
        liveChanged.forEach { releaseEndpoint(it) }
        return EndpointUpdateResult.UPDATED
    }

    /** 显示指向切回本机 DSH；失活的远程会话按各自生效保留档处置（即时释放、倒计时或永久保留）。 */
    fun selectLocal() {
        activeDisplayFlow.value = DisplayTarget.Local
        deactivateInactiveSessions(keepEndpoint = null)
        refreshHotPool()
    }

    /**
     * 显示指向切到指定设备端点：为已建档端点建立代理会话（异步，状态经
     * [sessionStates] 呈现）；池满/端口占用等失败转 Failed 并记录原因。
     *
     * 同一端点的并发/重复选择经在途标记去重，只产出一个会话；会话已在
     * 运行（保留期内切回）则免重建：撤销其保留倒计时并沿用现有代理。
     * 建立请求捕获端点代次：登记时设备已作废或门面已关闭则拒收；回填时
     * 设备作废、门面关闭、端点缺失或代次漂移（地址编辑使本次建立基于过期
     * 目标）、显示切走的建立结果一律不落账，代理在锁外停止。
     */
    fun selectEndpoint(deviceId: DeviceId, endpointId: EndpointId) {
        if (closed) return // 门面已关闭：不再发起建立
        val factory = sessionFactory ?: return // 骨架阶段：无会话能力，保持本机指向
        activeDisplayFlow.value = DisplayTarget.Session(endpointId)
        deactivateInactiveSessions(keepEndpoint = endpointId)
        refreshHotPool()
        val endpoint = devicesFlow.value
            .find { it.id == deviceId }?.endpoints?.find { it.id == endpointId }
        if (endpoint == null) {
            // 端点不在档：该端点的既有会话先行停止，避免 Failed 态下代理仍在跑
            stopSession(endpointId)
            setSessionState(endpointId, SessionState.Failed, "Endpoint is not archived")
            return
        }
        val generation = endpoint.generation
        synchronized(liveSessions) {
            if (deviceId in voidedDevices) return // 设备已删除：拒收（不作显示请求）
            // 用户选择即「想看到它」：显示请求对受理与重选路径都生效（保留档
            // 重选同样要把界面切到 DSH 页签），仅设备作废跳过
            showDshRequestsFlow.update { it + 1 }
            displayExemption = endpointId
            if (endpointId in pendingStarts) return // 建立中：登记方负责建立与置态
            if (endpointId in liveSessions) {
                cancelRetentionLocked(endpointId) // 保留期内切回：撤销倒计时，免重建
                return
            }
        }
        scope.launch(ioDispatcher) {
            val registered = synchronized(liveSessions) {
                deviceId !in voidedDevices &&
                    !closed &&
                    endpointId !in liveSessions &&
                    endpointId !in pendingStarts &&
                    pendingStarts.add(endpointId)
            }
            if (!registered) return@launch // 作废/关闭/并发重复选择：登记方负责建立与置态
            // 登记成功即置 Connecting：仍须复查作废与关闭（可能落在登记判定
            // 与置态之间）——命中则撤回登记并放弃，不留指向已删设备的会话。
            synchronized(liveSessions) {
                if (deviceId in voidedDevices || closed) {
                    pendingStarts.remove(endpointId)
                    return@launch
                }
                setSessionState(endpointId, SessionState.Connecting, null)
            }
            try {
                try {
                    val session = factory.createSession(deviceId, endpoint)
                    session.start()
                    var staleSession: ProxySession? = null
                    synchronized(liveSessions) {
                        when {
                            // 回填前重查：设备已作废（删除时刻覆盖在途建立）时
                            // 不得复活——状态转 Terminated，代理在锁外停。
                            deviceId in voidedDevices -> {
                                setSessionState(endpointId, SessionState.Terminated, null)
                                staleSession = session
                            }
                            // 门面已关闭：按释放口径收尾，不落账。
                            closed -> {
                                setSessionState(endpointId, SessionState.Released, null)
                                staleSession = session
                            }
                            // 端点已不在清单，或代次漂移（地址编辑使本次建立
                            // 基于过期目标）：废弃本次建立，代理停止且状态清理。
                            archivedGeneration(deviceId, endpointId)?.let { it != generation } ?: true -> {
                                clearSessionState(endpointId)
                                staleSession = session
                            }
                            // 显示已切走：按策略释放口径收尾，不回填、不置 Ready。
                            activeDisplayFlow.value != DisplayTarget.Session(endpointId) -> {
                                setSessionState(endpointId, SessionState.Released, null)
                                staleSession = session
                            }
                            else -> {
                                liveSessions[endpointId] = session
                                sessionOriginsFlow.value =
                                    sessionOriginsFlow.value + (endpointId to session.loopbackOrigin)
                                // 上游 origin 随会话落账写入（页内链接分流判定用）
                                session.upstreamOrigin?.let { upstreamOrigin ->
                                    sessionUpstreamOriginsFlow.value =
                                        sessionUpstreamOriginsFlow.value + (endpointId to upstreamOrigin)
                                }
                                setSessionState(endpointId, SessionState.Ready, null)
                                // 落账收敛即清除拒绝标记（与渲染事件路径的 Ready 同口径）
                                pairingRejectedFlow.update { it - endpointId }
                                // DSH 页签不在场时落账的会话不得常驻：按当前
                                // 保留策略执行失活处置（即时释放或武装倒计时）；
                                // 用户主动选择的豁免消费一次，显示请求会随后把
                                // 页签切到 DSH。
                                if (!dshTabVisible) {
                                    if (displayExemption == endpointId) {
                                        displayExemption = null
                                    } else {
                                        applyDeactivationPolicy(endpointId)
                                    }
                                }
                                refreshHotPool()
                            }
                        }
                    }
                    // 代理停止涉及 IO，移出会话表锁执行
                    staleSession?.stop()
                } catch (error: Throwable) {
                    // 建立失败：设备已作废或门面已关闭时状态随其收敛，不得把
                    // Failed 写回；清单缺失或代次漂移时残留的登记态一并清理，
                    // 不留无主的 Connecting。
                    val voided = synchronized(liveSessions) { deviceId in voidedDevices }
                    val archivedGeneration = archivedGeneration(deviceId, endpointId)
                    when {
                        voided -> setSessionState(endpointId, SessionState.Terminated, null)
                        closed -> setSessionState(endpointId, SessionState.Released, null)
                        archivedGeneration?.let { it != generation } ?: true ->
                            clearSessionState(endpointId)
                        else -> setSessionState(
                            endpointId,
                            SessionState.Failed,
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
                }
            } finally {
                synchronized(liveSessions) { pendingStarts.remove(endpointId) }
            }
        }
    }

    /**
     * 重试当前端点会话（Failed/Disconnected 覆盖层的重试按钮）：停止既有
     * 会话残留后重走建立流程，状态经 Connecting 收敛。显示槽观测到
     * Connecting 即自行重建渲染器宿主（旧宿主的自愈记账与页面状态作废）。
     */
    fun retryEndpoint(endpointId: EndpointId) {
        val device = devicesFlow.value
            .firstOrNull { it.endpoints.any { endpoint -> endpoint.id == endpointId } } ?: return
        stopSession(endpointId)
        selectEndpoint(device.id, endpointId)
    }

    /**
     * DSH 页签离开（宿主切走页签；会话表面随保活组合继续存在，激活/失活
     * 必须由状态驱动而非组合生命周期）：当前显示的会话按保留策略失活——
     * IMMEDIATE 即时释放，Retain 起倒计时（重复失活沿用原失活时刻，不重算
     * 期限）。显示指向本机时无远程会话可失活；在途建立的端点由回填按本
     * 状态处置。
     */
    fun onDshTabHidden() {
        synchronized(liveSessions) {
            dshTabVisible = false
            displayExemption = null
        }
        val endpointId = (activeDisplayFlow.value as? DisplayTarget.Session)?.endpointId ?: return
        applyDeactivationPolicy(endpointId)
    }

    /**
     * DSH 页签回到前台：显示中的会话撤销保留倒计时（期内回场免代理重建，
     * 渲染器在既有回环 origin 上重载）；已按策略释放的会话重新建立
     * （Released → Connecting）。失败态不自动重建——留给用户显式重试
     * （失败原因可能是端点目标不可达，静默重建只会循环报错）。
     */
    fun onDshTabVisible() {
        synchronized(liveSessions) {
            dshTabVisible = true
            displayExemption = null
        }
        val endpointId = (activeDisplayFlow.value as? DisplayTarget.Session)?.endpointId ?: return
        synchronized(liveSessions) {
            if (endpointId in pendingStarts) return // 建立中：登记方负责建立与置态
            if (endpointId in liveSessions) {
                cancelRetentionLocked(endpointId) // 回场：撤销倒计时，沿用现有代理
                return
            }
        }
        val state = sessionStatesFlow.value[endpointId]
        if (state == null || state == SessionState.Released) {
            retryEndpoint(endpointId)
        }
    }

    /**
     * 删除设备条目：销毁其全部会话、登录态存档（cookie jar/凭据）与槽位
     * 绑定，并从清单移除。端点状态转 Terminated（不可逆终态）；设备按标识
     * 一次性作废（读时不清账），删除时刻在途的建立完成回填前被拒收，删除
     * 后的会话不会复活。显示槽正指向被删端点时回退本机。
     */
    fun removeDevice(deviceId: DeviceId) {
        val device = devicesFlow.value.find { it.id == deviceId } ?: return
        synchronized(liveSessions) { voidedDevices.add(deviceId) }
        for (endpoint in device.endpoints) {
            releaseEndpoint(endpoint.id, releaseState = SessionState.Terminated)
        }
        sessionFactory?.removeDevice(deviceId)
        reloadDevices()
        synchronized(pendingCredentialLock) {
            val removed = device.endpoints.map { endpoint -> endpoint.id }.toSet()
            pendingCredentialFlow.value = pendingCredentialFlow.value - removed
        }
        pairingRejectedFlow.update { it - device.endpoints.map { endpoint -> endpoint.id }.toSet() }
        val active = activeDisplayFlow.value
        if (active is DisplayTarget.Session && device.endpoints.any { it.id == active.endpointId }) {
            activeDisplayFlow.value = DisplayTarget.Local
        }
        refreshHotPool()
    }

    /**
     * 端点移动到另一台设备（更多菜单的「移动到设备」，目标可为无分组）：
     * 本地档案重排（端点身份不变、凭据随迁），在用会话按既有释放语义处置
     * ——移动后的端点目标归属已变，旧会话不得静默沿用（与端点编辑同口径
     * 的 Released）。源设备因此无端点时转删除设备口径（销毁整档与槽位，
     * 空设备不再入清单）。存档 IO 挂起在 [ioDispatcher]，不占调用方线程。
     *
     * 取消窗口收口：写入与清单入账同包在 `NonCancellable` 内——磁盘已改
     * 而入账被打断会让「磁盘与内存」分叉（清单缺条目直至下次入账），两段
     * 必须原子完成；会话释放/显示复位等内存侧动作在入账后照常响应取消。
     */
    suspend fun moveEndpointTo(fromDeviceId: DeviceId, endpointId: EndpointId, toDeviceId: DeviceId) {
        val archiver = pairingArchiver ?: return
        val moved = withContext(NonCancellable + ioDispatcher) {
            runCatching { archiver.moveEndpoint(fromDeviceId, endpointId, toDeviceId) }.getOrDefault(false)
        }
        if (!moved) return
        releaseEndpoint(endpointId)
        reloadDevices()
        refreshHotPool()
        // 源设备端点移空：删除该设备（登录态存档与槽位一并回收）
        if (devicesFlow.value.find { it.id == fromDeviceId }?.endpoints?.isEmpty() == true) {
            removeDevice(fromDeviceId)
        }
    }

    /**
     * 把端点移入新建分组（名称 [name]）：新建目标设备、迁移凭据、释放端点
     * 会话并刷新清单；源设备移空按既有口径删除。
     */
    suspend fun moveEndpointToNewGroup(
        fromDeviceId: DeviceId,
        endpointId: EndpointId,
        name: String,
    ) {
        val archiver = pairingArchiver ?: return
        val moved = withContext(NonCancellable + ioDispatcher) {
            runCatching { archiver.moveEndpointToNewDevice(fromDeviceId, endpointId, name) }
                .getOrDefault(false)
        }
        if (!moved) return
        releaseEndpoint(endpointId)
        reloadDevices()
        refreshHotPool()
        if (devicesFlow.value.find { it.id == fromDeviceId }?.endpoints?.isEmpty() == true) {
            removeDevice(fromDeviceId)
        }
    }

    /**
     * 删除端点（不删设备）：档案移除条目并清除端点凭据，会话转 Terminated
     * （不可逆终态）。设备因此无端点时转删除设备口径（销毁整档与槽位），
     * 与「最后一条端点删除即设备消失」的行为一致。被删除端点正是当前显示
     * 目标时显示指向复位本机（悬空的显示目标只会留下空白页与错误微条文
     * 案）。存档 IO 挂起在 [ioDispatcher]，不占调用方线程。
     *
     * 取消窗口收口与 [moveEndpointTo] 同口径：写入与入账原子完成，不因调
     * 用方协程在两段之间取消而分叉。
     */
    suspend fun removeEndpoint(deviceId: DeviceId, endpointId: EndpointId) {
        val archiver = pairingArchiver ?: return
        val removed = withContext(NonCancellable + ioDispatcher) {
            runCatching { archiver.removeEndpoint(deviceId, endpointId) }.getOrDefault(false)
        }
        if (!removed) return
        releaseEndpoint(endpointId, releaseState = SessionState.Terminated)
        synchronized(pendingCredentialLock) {
            pendingCredentialFlow.value = pendingCredentialFlow.value - endpointId
        }
        pairingRejectedFlow.update { it - endpointId }
        // 先入账（档案已少一条），再按入账后的清单判定设备是否已空——空设备
        // 转删除设备口径（销毁整档与槽位）
        reloadDevices()
        refreshHotPool()
        // 显示目标悬空先于设备删除复位：removeDevice 内有同口径兜底，这里
        // 显式处理端点级删除（设备未删、显示仍指向已删端点的窗口）
        if (activeDisplayFlow.value == DisplayTarget.Session(endpointId)) {
            activeDisplayFlow.value = DisplayTarget.Local
            refreshHotPool()
        }
        if (devicesFlow.value.find { it.id == deviceId }?.endpoints?.isEmpty() == true) {
            removeDevice(deviceId)
        }
    }

    /**
     * 设备重命名：接口只改显示名（空串回退「无分组」），端点/凭据/槽位不动；
     * 存档 IO 挂起在 [ioDispatcher]，入账后刷新清单。
     */
    suspend fun renameDevice(deviceId: DeviceId, name: String) {
        val archiver = pairingArchiver ?: return
        val renamed = withContext(NonCancellable + ioDispatcher) {
            runCatching { archiver.renameDevice(deviceId, name) }.getOrDefault(false)
        }
        if (renamed) reloadDevices()
    }

    /**
     * 切换端点会话模式（端点操作行的代理/直连段选）：复用端点更新链路以
     * 档案地址重新确认——模式随更新原子落盘并自增代次（在途建立按漂移废
     * 弃），在用会话按既有释放语义释放。直连要求 https 的口径由更新链路
     * 收口（非 https 目标的直连选择回落代理档）。
     */
    fun setEndpointSessionMode(deviceId: DeviceId, endpointId: EndpointId, mode: SessionMode) {
        val endpoint = devicesFlow.value
            .find { it.id == deviceId }?.endpoints?.find { it.id == endpointId } ?: return
        scope.launch {
            updateEndpointTarget(deviceId, endpointId, endpoint.targetUrl, mode.name)
        }
    }

    /**
     * 门面随组合销毁收尾：置关闭标记（建立与回填一律拒收）并释放全部会话
     * 与代理监听（登录态存档与设备条目保留）。宿主在组合销毁时调用，防止
     * Activity 重建后旧门面的代理线程与端口被持有不放。显示指向一并回本机
     * ——指向已释放会话的显示目标没有意义。
     */
    fun close() {
        closed = true
        val all = synchronized(liveSessions) { liveSessions.keys.toList() }
        all.forEach { releaseEndpoint(it) }
        activeDisplayFlow.value = DisplayTarget.Local
        refreshHotPool()
        // 工厂生命周期收尾：释放工厂持有的系统资源。工厂随门面同期构造、
        // 生命周期一致，close 后不再建立会话（closed 拦截已生效）。幂等：
        // 无工厂（骨架宿主）或工厂无资源（默认空实现）或重复 close 均安全。
        runCatching { sessionFactory?.close() }
            .onFailure {
                android.util.Log.w("RemoteSession", "session factory close failed (${it.javaClass.simpleName})")
            }
    }

    /**
     * 更新全局默认保留策略（持久化，重启不丢）：只对**继承默认**（无端点
     * 覆盖）的在册会话重算释放时机；端点级覆盖（[setEndpointRetention]）
     * 的会话不受全局改档影响。重评估候选取全部在册端点且非当前显示目标
     * （仅按释放期限筛选会漏掉 FOREVER 期间失活、无期限在册的继承会话），
     * 当前显示端点不动（正在呈现的会话由页签激活/失活上报处置）。
     */
    fun setRetentionDefault(policy: RetentionPolicy) {
        retentionFlow.value = policy
        // 设置落盘移出调用方线程并与其他保留设置写入串行（快速连改时后写
        // 覆盖前写）；失败记日志（进程内已生效，重启回落旧档）
        scope.launch(ioDispatcher) {
            persistenceWriteMutex.withLock {
                runCatching { retentionSettings.setDefault(policy) }
                    .onFailure { android.util.Log.w("RemoteSession", "retention default persist failed", it) }
            }
        }
        val activeEndpoint = (activeDisplayFlow.value as? DisplayTarget.Session)?.endpointId
        val inherited = synchronized(liveSessions) {
            liveSessions.keys.filter { it != activeEndpoint && overrideFor(it) == null }
        }
        inherited.forEach { reevaluateRetentionFor(it, policy) }
    }

    /**
     * 按给定生效保留档对单个在册端点执行改档重评估（全局默认改档与端点
     * 覆盖变更共用）：当前显示目标只改档不动会话；其余端点 IMMEDIATE 即时
     * 释放，Retain 按原失活时刻武装倒计时（无失活记录按当前时刻起算），
     * FOREVER 撤销既有倒计时（若有）。
     */
    private fun reevaluateRetentionFor(endpointId: EndpointId, policy: RetentionPolicy) {
        if (activeDisplayFlow.value == DisplayTarget.Session(endpointId)) return
        val live = synchronized(liveSessions) { endpointId in liveSessions }
        if (!live) return
        when (policy) {
            RetentionPolicy.IMMEDIATE -> releaseEndpoint(endpointId)
            is RetentionPolicy.Retain -> {
                val inactiveAt = synchronized(liveSessions) { retentionInactiveAt[endpointId] }
                    ?: foregroundClock.foregroundElapsedMillis()
                armRetention(endpointId, policy, inactiveAt)
            }
            RetentionPolicy.FOREVER -> synchronized(liveSessions) { cancelRetentionLocked(endpointId) }
        }
    }

    /**
     * 端点的生效保留档：端点级覆盖优先（[setEndpointRetention] 写入），
     * 无覆盖继承全局默认。
     */
    fun retentionFor(endpointId: EndpointId): RetentionPolicy =
        overrideFor(endpointId) ?: retentionFlow.value

    /** 端点级保留档覆盖（null = 继承全局默认）。 */
    private fun overrideFor(endpointId: EndpointId): RetentionPolicy? =
        devicesFlow.value
            .firstOrNull { device -> device.endpoints.any { it.id == endpointId } }
            ?.endpoints?.firstOrNull { it.id == endpointId }
            ?.retentionOverride
            ?.let(::retentionPolicyFromArchive)

    /**
     * 写入端点级保留档覆盖（null = 清除覆盖、继承默认）：设备档案即时更新
     * 并落盘（v3 字段），随后按**生效档**（覆盖已写入档案，null 即继承全局
     * 默认）重评估该端点——IMMEDIATE 释放在册会话；Retain 武装倒计时；
     * FOREVER 撤销既有倒计时。当前显示目标只改档不动会话（正在呈现的会话
     * 由页签激活/失活上报处置），不制造无主 Released 态。
     */
    fun setEndpointRetention(deviceId: DeviceId, endpointId: EndpointId, policy: RetentionPolicy?) {
        devicesFlow.value = devicesFlow.value.map { device ->
            if (device.id != deviceId) {
                device
            } else {
                device.copy(
                    endpoints = device.endpoints.map { endpoint ->
                        if (endpoint.id == endpointId) {
                            endpoint.copy(retentionOverride = policy?.toArchiveValue())
                        } else {
                            endpoint
                        }
                    },
                )
            }
        }
        // 存档落盘移出调用方线程并与默认档写入串行（快速连改时后写覆盖前写）；
        // 无出口（骨架工厂）时保持内存生效
        scope.launch(ioDispatcher) {
            persistenceWriteMutex.withLock {
                runCatching {
                    pairingArchiver?.setEndpointRetentionOverride(deviceId, endpointId, policy?.toArchiveValue())
                }.onFailure { android.util.Log.w("RemoteSession", "retention override persist failed", it) }
            }
        }
        reevaluateRetentionFor(endpointId, retentionFor(endpointId))
        refreshHotPool()
    }

    /**
     * 显式释放（「立即释放」/「全部释放」入口）：移出热池、停代理、置
     * Released。释放的是当前显示端点时，显示指向一并切回本机——Released
     * 表面无内容可渲染，且页签回场不会把已显式释放的会话自动重建。
     */
    fun releaseNow(endpointId: EndpointId) {
        releaseEndpoint(endpointId)
        if (activeDisplayFlow.value == DisplayTarget.Session(endpointId)) {
            activeDisplayFlow.value = DisplayTarget.Local
            refreshHotPool()
        }
    }

    /**
     * 热池刷新：在册会话 + 当前显示目标（在途建立/失败态的覆盖层也需表面
     * 承载）。带容量保护：候选超出回环槽位池规模（每端点独占一个回环地址，
     * 池外无会话可建）时不再扩容——当前显示目标优先保留，其余截断，超池的
     * 在册会话仍保留（由释放/删除回收），只记一次日志。
     */
    private fun refreshHotPool() {
        val active = (activeDisplayFlow.value as? DisplayTarget.Session)?.endpointId
        val hot = synchronized(liveSessions) {
            val candidates = if (active == null) liveSessions.keys.toSet() else liveSessions.keys + active
            if (candidates.size <= hotPoolCapacity) {
                candidates
            } else {
                if (!hotPoolCapWarned) {
                    hotPoolCapWarned = true
                    android.util.Log.w(
                        "RemoteSession",
                        "hot pool exceeds capacity (candidates=${candidates.size}, cap=$hotPoolCapacity)",
                    )
                }
                val others = when (active) {
                    null -> candidates
                    else -> candidates - active
                }
                (listOfNotNull(active) + others).take(hotPoolCapacity).toSet()
            }
        }
        hotEndpointsFlow.value = hot
    }

    // ---- 渲染事件入口（显示槽 → 会话状态机映射） ----

    /** 渲染器开始加载页面：代理就绪（Connecting/Ready）后进入 Loading。 */
    fun notifyRendererLoading(endpointId: EndpointId) {
        transitionEndpointState(endpointId, SessionState.Loading, CONNECTING_OR_PRESENTING)
    }

    /** 渲染器加载进度（0-100）。 */
    fun notifyRendererProgress(endpointId: EndpointId, percent: Int) {
        if (percent !in 0..100) return
        rendererProgressFlow.value = rendererProgressFlow.value + (endpointId to percent)
    }

    /** 页面渲染完成（主文档加载成功）：Loading 回到 Ready，拒绝标记清除。 */
    fun notifyRendererReady(endpointId: EndpointId) {
        if (transitionEndpointState(endpointId, SessionState.Ready, CONNECTING_OR_PRESENTING)) {
            pairingRejectedFlow.update { it - endpointId }
        }
    }

    /** 渲染器主框架加载失败且自愈未果：转 Failed 并记录原因（覆盖层可重试）。 */
    fun notifyRendererFailed(endpointId: EndpointId, reason: String, pairingRejected: Boolean = false) {
        val transitioned = transitionEndpointState(
            endpointId,
            SessionState.Failed,
            CONNECTING_OR_PRESENTING + SessionState.Disconnected,
            reason,
        )
        if (transitioned) {
            pairingRejectedFlow.update {
                if (pairingRejected) it + endpointId else it - endpointId
            }
        }
    }

    /**
     * 曾就绪的会话连接中断：转 Disconnected（覆盖层可重连）。原因沿用失败
     * 记录或取本次上报值，不因迁移清空——断连覆盖层需呈现原因。
     */
    fun notifySessionDisconnected(endpointId: EndpointId, reason: String? = null) {
        val effectiveReason = reason
            ?: sessionFailureReasonsFlow.value[endpointId]
            ?: "Connection lost"
        transitionEndpointState(
            endpointId,
            SessionState.Disconnected,
            setOf(SessionState.Loading, SessionState.Ready, SessionState.Disconnected),
            effectiveReason,
        )
    }

    // ---- 宿主前后台上报（保留档前台时钟） ----

    /**
     * 宿主进入前台：启动前台计时片段，并对保留期内的失活会话做补偿判定
     * （后台不计时，锁屏期到点的会话在前台恢复时按累计值释放）。
     */
    fun onHostResumed() {
        foregroundClock.onForeground()
        val due = synchronized(liveSessions) {
            val elapsed = foregroundClock.foregroundElapsedMillis()
            retentionDeadlines.filterValues { it <= elapsed }.keys.toList()
        }
        due.forEach { releaseEndpoint(it) }
    }

    /** 宿主退到后台：结清前台计时片段（保留倒计时随之停走）。 */
    fun onHostPaused() {
        foregroundClock.onBackground()
    }

    /** 当前端点会话的门禁令牌；null = 无门禁（bypass）或会话未就绪。 */
    fun sessionGateToken(endpointId: EndpointId): String? =
        synchronized(liveSessions) { liveSessions[endpointId]?.gateToken }

    /**
     * 端点是否直连会话（档案 [SessionMode.DIRECT]）：显示槽据此以真实上游
     * origin 为可信基准、按 AnyHttps 口径组装渲染器主框架导航。
     */
    fun sessionIsDirect(endpointId: EndpointId): Boolean =
        archivedEndpoint(endpointId)?.sessionMode == SessionMode.DIRECT.name

    /** 端点当前档案条目；设备或端点不在档返回 null。 */
    private fun archivedEndpoint(endpointId: EndpointId): Endpoint? =
        devicesFlow.value
            .firstOrNull { device -> device.endpoints.any { it.id == endpointId } }
            ?.endpoints?.find { it.id == endpointId }

    /** 会话首次加载的完整地址（fragment 携带配对 secret 的插件）；null = 加载 origin 根。 */
    fun sessionInitialLoadUrl(endpointId: EndpointId): String? =
        synchronized(liveSessions) { liveSessions[endpointId]?.initialLoadUrl }

    /**
     * 端点的 saya-ch 会话是否已兑换（工厂在 vault 持有兑换产物）：会话 8
     * 小时到期后上游 401 时，渲染层据此给出「会话已过期，请重新配对」的
     * 明确失败态（票已消费，未消费票的重配对引导不再命中）。
     */
    fun sayaSessionExchanged(endpointId: EndpointId): Boolean {
        val endpoint = archivedEndpoint(endpointId) ?: return false
        if (endpoint.parserId != ParserIds.SAYA_CH) return false
        return (sessionFactory as? DefaultSessionFactory)?.sayaSessionExchanged(endpointId) == true
    }

    /**
     * 在途会话是否以一次性凭据启动（gateway 首载 / full-remote 注入 /
     * generic 票据）：显示槽据此挂接消费提交门控（full-remote 不经首载
     * 地址，凭 URL 推断会漏）。会话参数在显示槽组装时取快照。
     */
    fun sessionUsesConsumableCredential(endpointId: EndpointId): Boolean {
        val inRegister = synchronized(liveSessions) { endpointId in liveSessions }
        if (!inRegister) return false
        // 取当前快照（初值异步加载完成前为空集）：该窗口内的会话按无凭据
        // 呈现；其消费提交由代次校验的迟到路径兜底回写，不依赖加载完成
        if (endpointId !in pendingCredential.value) return false
        val parserId = devicesFlow.value
            .firstOrNull { it.endpoints.any { endpoint -> endpoint.id == endpointId } }
            ?.endpoints?.find { it.id == endpointId }?.parserId
            ?: return false
        // 消费型解析器权威集合收敛在 ParserIds：未消费集合与门控共用同一口径
        return parserId in com.dshbox.remote.parse.ParserIds.CONSUMABLE_CREDENTIAL_PARSER_IDS
    }

    /**
     * 渲染器导航型下载判定：跨源 `<a download>` 在引擎里退化为普通导航，
     * 这里只把已批准的文件 CDN 判为下载（与 [downloadDirect] 同一白名单），
     * 其它跨源导航保持原行为。
     */
    fun isDownloadNavigationUrl(url: String): Boolean {
        val origin = originOf(url) ?: return false
        return isApprovedDownloadCdn(origin)
    }

    /**
     * 补扩展名：名字已有合理扩展名（1~16 字符）原样返回；否则按响应
     * Content-Type 补一个，避免对象存储哈希名落盘后无法用系统应用打开。
     */
    private fun ensureFileExtension(name: String, contentType: String?): String {
        val dot = name.lastIndexOf('.')
        if (dot > 0 && name.length - dot in 2..17) return name
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return name
        val extension = android.webkit.MimeTypeMap.getSingleton()
            .getExtensionFromMimeType(mime)
            ?.takeIf { it.isNotBlank() }
            ?: return name
        return "$name.$extension"
    }

    /**
     * 已知文件下载 CDN：WorkBuddy 的下载落点是腾讯云对象存储
     * `smhwb-gz-<节点>.data-tencentsmh.com`。只放行该租户前缀，避免把
     * 整个第三方 CDN 域当作可下载来源；仅 https、非回环/私网。
     */
    private fun isApprovedDownloadCdn(origin: String): Boolean {
        val uri = runCatching { java.net.URI(origin) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost" || host.endsWith(".localhost")) return false
        if (com.dshbox.remote.parse.UrlParserSupport.isPrivateHost(host)) return false
        return APPROVED_DOWNLOAD_CDN_HOSTS.any { pattern -> pattern.matches(host) }
    }

    /**
     * 请求地址的 origin 归一化（scheme+host+port，默认端口省略，host 小写）：
     * 直连下载的「与上游同源」判定基准；非法或非 http(s) 返回 null。
     */
    private fun originOf(url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val defaultPort = if (scheme == "https") 443 else 80
        return buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1 && uri.port != defaultPort) append(':').append(uri.port)
        }
    }

    /**
     * 直连模式下载：渲染器 DownloadListener 给出 URL，由宿主按同一套下载
     * 目录落盘（Cookie/UA 取自 WebView），成功后抛同一 [DownloadNotice]，
     * 下载器对代理与直连一视同仁。
     *
     * 同源校验按完整 origin（scheme+host+port，默认端口归一化）与
     * [sessionUpstreamOrigins] 比对，不只比 host：同 host 的 http/https、
     * 异端口同样是跨源目标，放行会把非会话流量（含明文与非常见端口）当
     * 文件拉取。重定向禁用自动跟随（[java.net.HttpURLConnection].
     * instanceFollowRedirects = false）：自动跟随会在连接层静默跳到任意
     * 3xx 目标，绕过入口的 origin 校验；3xx 在响应码判定处显式失败——
     * 会话上游如需重定向，由页面/引擎层完成，下载器只落最终地址。
     */
    fun downloadDirect(
        endpointId: EndpointId,
        request: com.dshbox.dsh.renderer.api.DshDownloadRequest,
    ) {
        val expectedOrigin = sessionUpstreamOriginsFlow.value[endpointId]
        val requestOrigin = originOf(request.url)
        // 只允许当前会话上游同 origin 的 URL：防页面把回环门禁/任意地址当文件拉取。
        // 例外：已知网页 Agent 的文件 CDN（WorkBuddy 的腾讯云对象存储节点），
        // 精确到租户前缀，不放开整个 CDN 域；仍只允许 https 公网主机。
        val approvedCdn = requestOrigin != null && isApprovedDownloadCdn(requestOrigin)
        if (requestOrigin == null || expectedOrigin == null ||
            (!requestOrigin.equals(expectedOrigin, ignoreCase = true) && !approvedCdn)
        ) {
            // 只记录 origin（不含路径/query/token）：用于确定下载被哪个
            // CDN 域名拒绝，再按同站/精确 host 白名单收口
            android.util.Log.w(
                "RemoteDownload",
                "origin rejected: requestOrigin=$requestOrigin expectedOrigin=$expectedOrigin",
            )
            downloadEventsFlow.value = DownloadNotice(
                downloadEventSeq.incrementAndGet(), false, "origin",
            )
            return
        }
        scope.launch(ioDispatcher) {
            val dir = downloadDirectory
            if (dir == null) {
                downloadEventsFlow.value = DownloadNotice(
                    downloadEventSeq.incrementAndGet(), false, "dir_unavailable",
                )
                return@launch
            }
            if (!dir.isDirectory && !dir.mkdirs()) {
                downloadEventsFlow.value = DownloadNotice(
                    downloadEventSeq.incrementAndGet(), false, "permission",
                )
                return@launch
            }
            var target: java.io.File? = null
            var connection: java.net.HttpURLConnection? = null
            try {
                val opened = java.net.URL(request.url).openConnection() as java.net.HttpURLConnection
                connection = opened
                opened.connectTimeout = 15_000
                opened.readTimeout = 30_000
                // 禁用自动重定向：跟随会在连接层静默跳到任意 3xx 目标，绕过
                // 入口的 origin 校验；3xx 在下方响应码判定处显式失败（KDoc）
                opened.instanceFollowRedirects = false
                request.userAgent?.let { opened.setRequestProperty("User-Agent", it) }
                // Cookie 尽力附加（WebView 进程级 CookieManager）：读不到不失败
                // 下载——同源校验已经收紧了目标，缺 Cookie 只影响需登录态的
                // 下载（引擎侧自持登录态的直连场景本就常见公开文件下载）
                runCatching {
                    android.webkit.CookieManager.getInstance().getCookie(request.url)
                }.getOrNull()?.let { opened.setRequestProperty("Cookie", it) }
                val code = opened.responseCode
                if (code !in 200..299) throw java.io.IOException("HTTP $code")
                // 文件名以响应头为准（与 WebView 下载同口径）：导航型下载的
                // URL 末段常是对象存储哈希，只有 Content-Disposition 带原始
                // 文件名；响应头缺失时回退到 URL 查询参数与末段
                val resolvedName = runCatching {
                    com.dshbox.remote.proxy.DownloadFileName.resolve(
                        responseHeaders = listOfNotNull(
                            (opened.getHeaderField("Content-Disposition")
                                ?: request.contentDisposition)?.let {
                                com.dshbox.remote.proxy.ProxyHeader("Content-Disposition", it)
                            },
                        ),
                        url = request.url,
                    )
                }.getOrElse { "download-${System.currentTimeMillis()}" }
                val name = ensureFileExtension(
                    resolvedName,
                    opened.getHeaderField("Content-Type"),
                )
                var candidate = name
                var index = 1
                // 原子占位：并发同名下载不互相截断（与代理侧 claimTarget 同口径）；
                // 上限防止异常磁盘状态下无限循环
                while (true) {
                    val file = java.io.File(dir, candidate)
                    if (file.createNewFile()) {
                        target = file
                        break
                    }
                    if (index > MAX_DUPLICATE_NAME_CANDIDATES) {
                        throw java.io.IOException("too many duplicate names")
                    }
                    val dot = name.lastIndexOf('.')
                    candidate = if (dot > 0) {
                        name.substring(0, dot) + " ($index)" + name.substring(dot)
                    } else {
                        "$name ($index)"
                    }
                    index += 1
                }
                val claimed = target ?: throw java.io.IOException("download target unavailable")
                opened.inputStream.use { input ->
                    claimed.outputStream().use { output -> input.copyTo(output) }
                }
                downloadEventsFlow.value = DownloadNotice(downloadEventSeq.incrementAndGet(), true)
            } catch (error: Exception) {
                target?.let { runCatching { it.delete() } }
                val reason = if (error is java.io.IOException &&
                    error.message?.startsWith("HTTP ") == true
                ) {
                    "http"
                } else {
                    "io"
                }
                downloadEventsFlow.value = DownloadNotice(
                    downloadEventSeq.incrementAndGet(), false, reason,
                )
            } finally {
                runCatching { connection?.disconnect() }
            }
        }
    }

    /**
     * 提交一次性凭据消费（渲染器宿主干净首帧触发）：携带提交时的端点代次
     * 快照——工厂置位前校验代次未漂移（期间发生过重新配对则静默忽略，防
     * 旧会话的迟到提交误标新凭据）；存档 IO 移出主线程。
     */
    fun markSessionCredentialConsumed(endpointId: EndpointId) {
        val generation = devicesFlow.value
            .firstOrNull { it.endpoints.any { endpoint -> endpoint.id == endpointId } }
            ?.endpoints?.find { it.id == endpointId }?.generation ?: return
        scope.launch(ioDispatcher) {
            sessionFactory?.markCredentialConsumed(endpointId, generation)
        }
    }

    /** 当前 flavor 的渲染引擎适配器（显示槽创建渲染器用）。 */
    fun rendererAdapter(): DshRendererAdapter = host.rendererAdapter()

    /**
     * 宿主附件来源面板选择器（转接宿主 [RemoteConnectionHost.pickUploadFiles]）：
     * 显示槽创建渲染宿主时注入，页面文件选择请求据此改走宿主面板。结果三态
     * （null=未提供 / 空=取消 / 非空=选中）的分派判定收口在
     * [com.dshbox.remote.render.UploadRequestRouting]。
     */
    val uploadPicker: suspend (Boolean, List<String>) -> List<DshSelectedFile>? =
        { multiple, mimeTypes -> host.pickUploadFiles(multiple, mimeTypes) }

    // ---- 内部：存活判定与保留策略 ----

    /** 从建档出口重读未消费一次性凭据的端点集合（解密扫描在 IO 线程）。 */
    private suspend fun refreshPendingCredential() {
        // 读档涉及 Keystore/文件解密扫描：与初值加载同口径移出调用方线程；
        // 失败记日志并保留现值（消费门控与 401 引导不因读档故障被清空）
        val endpoints = withContext(ioDispatcher) {
            runCatching { pairingArchiver?.pendingCredentialEndpoints() }
                .onFailure { android.util.Log.w("RemoteSession", "pendingCredential refresh failed", it) }
                .getOrNull()
        } ?: return
        synchronized(pendingCredentialLock) {
            pendingCredentialFlow.value = endpoints
        }
    }

    /** 端点当前档案代次；设备或端点不在档返回 null。 */
    private fun archivedGeneration(deviceId: DeviceId, endpointId: EndpointId): Long? =
        devicesFlow.value
            .find { it.id == deviceId }?.endpoints?.find { it.id == endpointId }?.generation

    /**
     * 按端点生效保留档对单个端点执行失活处置：IMMEDIATE 即时释放，Retain
     * 武装倒计时（重复失活沿用原失活时刻），FOREVER 保留至进程结束。页签
     * 离场与「页签离场期间的在途建立落账」共用此入口。
     */
    private fun applyDeactivationPolicy(endpointId: EndpointId) {
        val inactiveAt = synchronized(liveSessions) {
            if (endpointId !in liveSessions) return
            retentionInactiveAt[endpointId]
        } ?: foregroundClock.foregroundElapsedMillis()
        when (val policy = retentionFor(endpointId)) {
            RetentionPolicy.IMMEDIATE -> releaseEndpoint(endpointId)
            is RetentionPolicy.Retain -> armRetention(endpointId, policy, inactiveAt)
            RetentionPolicy.FOREVER -> Unit // 代理与渲染器保留，永不自动释放
        }
    }

    /**
     * 显示切换后的失活处置（逐端点按各自生效档）：IMMEDIATE 即时释放；
     * Retain 保留代理与热池（切回不重载页面），以前台时钟倒计时到点释放；
     * FOREVER 保留不释放。重复失活沿用原失活时刻（期限不因重复失活而顺延）。
     */
    private fun deactivateInactiveSessions(keepEndpoint: EndpointId?) {
        val inactive = synchronized(liveSessions) {
            liveSessions.keys.filter { it != keepEndpoint }.toList()
        }
        if (inactive.isEmpty()) return
        inactive.forEach { endpointId ->
            val inactiveAt = synchronized(liveSessions) { retentionInactiveAt[endpointId] }
                ?: foregroundClock.foregroundElapsedMillis()
            when (val policy = retentionFor(endpointId)) {
                RetentionPolicy.IMMEDIATE -> releaseEndpoint(endpointId)
                is RetentionPolicy.Retain -> armRetention(endpointId, policy, inactiveAt)
                RetentionPolicy.FOREVER -> Unit
            }
        }
    }

    /**
     * 武装保留倒计时：期限按失活时刻 + 保留分钟数计算（前台时钟口径）。
     * 到点且该端点仍处失活时释放；期内切回（重新选择）会撤销武装。
     * 任务登记与武装记账同在锁内，重复武装先撤销旧任务。倒计时按小周期
     * 分片睡眠——后台冻结期醒来重睡整段会造成迟释放，分片保证回前台后
     * 最多一个周期内到点释放。
     */
    private fun armRetention(
        endpointId: EndpointId,
        policy: RetentionPolicy.Retain,
        inactiveAtMillis: Long,
    ) {
        val deadline = inactiveAtMillis + policy.minutes * 60_000L
        synchronized(liveSessions) {
            retentionJobs.remove(endpointId)?.cancel()
            retentionInactiveAt[endpointId] = inactiveAtMillis
            retentionDeadlines[endpointId] = deadline
        }
        val job = scope.launch {
            while (true) {
                val remaining = deadline - foregroundClock.foregroundElapsedMillis()
                if (remaining <= 0) break
                delay(remaining.coerceAtMost(RETENTION_TICK_MILLIS))
            }
            if (synchronized(liveSessions) { retentionDeadlines[endpointId] == deadline } &&
                foregroundClock.foregroundElapsedMillis() >= deadline
            ) {
                releaseEndpoint(endpointId)
            }
        }
        val registered = synchronized(liveSessions) {
            if (retentionDeadlines[endpointId] == deadline) {
                retentionJobs[endpointId] = job
                true
            } else {
                false
            }
        }
        if (!registered) job.cancel() // 武装已被更新（改档/切回/释放）：丢弃旧任务
    }

    /** 撤销端点的保留倒计时（保留期内切回时调用），须持 liveSessions 锁。 */
    private fun cancelRetentionLocked(endpointId: EndpointId) {
        retentionJobs.remove(endpointId)?.cancel()
        retentionDeadlines.remove(endpointId)
        retentionInactiveAt.remove(endpointId)
    }

    /**
     * 释放端点会话：热池移出（表面离开组合，渲染器随既有 DisposableEffect
     * 释放），关闭代理监听并保留槽位/端口与登录态存档；条目保留，可重建。
     */
    private fun releaseEndpoint(endpointId: EndpointId, releaseState: SessionState = SessionState.Released) {
        synchronized(liveSessions) { cancelRetentionLocked(endpointId) }
        stopSession(endpointId)
        setSessionState(endpointId, releaseState, null)
        rendererProgressFlow.value = rendererProgressFlow.value - endpointId
        pairingRejectedFlow.update { it - endpointId }
        refreshHotPool()
    }

    private fun stopSession(endpointId: EndpointId) {
        val session = synchronized(liveSessions) { liveSessions.remove(endpointId) }
        session?.stop()
        sessionOriginsFlow.value = sessionOriginsFlow.value - endpointId
        sessionUpstreamOriginsFlow.value = sessionUpstreamOriginsFlow.value - endpointId
    }

    /** 状态迁移守卫：仅当端点会话在册且当前态在允许集合内才落新态；返回是否落账。 */
    private fun transitionEndpointState(
        endpointId: EndpointId,
        to: SessionState,
        allowedFrom: Set<SessionState>,
        reason: String? = null,
    ): Boolean {
        val inRegister = synchronized(liveSessions) {
            endpointId in liveSessions || endpointId in pendingStarts
        }
        if (!inRegister) return false
        val current = sessionStatesFlow.value[endpointId] ?: return false
        if (current in allowedFrom) {
            setSessionState(endpointId, to, reason)
            return true
        }
        return false
    }

    private fun setSessionState(endpointId: EndpointId, state: SessionState, reason: String?) {
        sessionStatesFlow.value = sessionStatesFlow.value + (endpointId to state)
        if (reason != null) {
            sessionFailureReasonsFlow.value = sessionFailureReasonsFlow.value + (endpointId to reason)
        } else {
            sessionFailureReasonsFlow.value = sessionFailureReasonsFlow.value - endpointId
        }
    }

    private fun clearSessionState(endpointId: EndpointId) {
        sessionStatesFlow.value = sessionStatesFlow.value - endpointId
        sessionFailureReasonsFlow.value = sessionFailureReasonsFlow.value - endpointId
    }

    /** 仅供单元测试驱动带指定代次的消费提交（迟到提交竞态回归用）。 */
    @androidx.annotation.VisibleForTesting
    internal fun markSessionCredentialConsumedWithGenerationForTest(endpointId: EndpointId, generation: Long) {
        sessionFactory?.markCredentialConsumed(endpointId, generation)
    }

    /** 仅供单元测试注入设备清单快照（覆盖回填重查的清单分支）。 */
    @androidx.annotation.VisibleForTesting
    internal fun applyDevicesSnapshotForTest(devices: List<Device>) {
        devicesFlow.value = devices
    }

    /** 仅供单元测试观察在途建立标记数（scope 取消等异常路径的泄漏防护回归）。 */
    @androidx.annotation.VisibleForTesting
    internal fun pendingStartCountForTest(): Int = synchronized(liveSessions) { pendingStarts.size }

    internal companion object {
        /** WorkBuddy 文件 CDN 节点（租户前缀 + 腾讯云对象存储域）。 */
        private val APPROVED_DOWNLOAD_CDN_HOSTS = listOf(
            Regex("^smhwb-[a-z0-9-]+\\.data-tencentsmh\\.com$"),
        )
        /** 门禁 cookie 注入路径可用的渲染引擎：systemWebView 变体的进程级 CookieManager。 */
        private const val GATE_CAPABLE_ADAPTER_ID = "system-webview"

        /**
         * 直连下载同名占位候选上限：`name (n)` 消解的重试次数上界。达到上限
         * 视为异常磁盘状态（目录不可写或同名文件异常堆积），显式失败——不放
         * 行无界循环；100 对人眼可见的重复名已远超需要。
         */
        private const val MAX_DUPLICATE_NAME_CANDIDATES = 100

        /** 保留倒计时的分片睡眠周期（毫秒）：后台冻结恢复后的释放迟滞上限。 */
        const val RETENTION_TICK_MILLIS = 1_000L

        /** 状态迁移允许集合：代理在途或已就绪的呈现态。 */
        val CONNECTING_OR_PRESENTING =
            setOf(SessionState.Connecting, SessionState.Loading, SessionState.Ready)
    }
}
