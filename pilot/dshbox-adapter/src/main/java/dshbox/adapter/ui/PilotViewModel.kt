package dshbox.adapter.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import interlock.relay.core.runtime.RelayContainer
import interlock.relay.core.runtime.RuntimePhase
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.exec.shizuku.ShizukuState
import interlock.relay.core.interlock.AccessibilityGap
import interlock.relay.core.interlock.InterlockChoice
import interlock.relay.core.interlock.ApprovalPrompt
import interlock.relay.core.interlock.InterlockChannel
import interlock.relay.core.interlock.InterlockQueue
import interlock.relay.core.interlock.GlobalConfigState
import interlock.relay.core.interlock.TierPresets
import interlock.relay.core.log.RunLog
import interlock.relay.core.surface.SurfacePreference
import interlock.relay.core.transport.MailboxServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 档位选择。三档行为由裁决层按用户档位判定（旧上限元数据不参与），界面一律三档可选。 */
enum class TierSelection(val tier: AccessTier) {
    DENIED(AccessTier.DENIED),
    ASK(AccessTier.ASK),
    ALWAYS(AccessTier.ALWAYS),
}

/** 界面一次渲染所需的全部状态，全部来自装配根；界面不自算任何判定。 */
data class PilotUiState(
    /**
     * 普通能力行：特权能力（完全 Shizuku）由装配根的成员过滤排除在外，
     * 不进能力列表与首页「可用能力」计数。装配根是过滤的唯一判据，界面不再自查。
     */
    val rows: List<RelayContainer.CapabilityRow> = emptyList(),

    /**
     * 首页「可用能力」的分子，随 [rows] 同次快照给出（内核判据 `readyUsableCount`
     * 的读数）：首页按状态里的这一个数呈现，不再在组合期重算行——重算就是把整套
     * 路线裁决+系统探测又跑一遍。
     */
    val usableCount: Int = 0,

    /** 完全 Shizuku 特权开关的档位：开=ALWAYS，关=DENIED。判定在内核，这里只呈现。 */
    val fullShellTier: AccessTier = AccessTier.DENIED,

    /**
     * 五条目全局配置的当前条目（默认/全部×/自定义/未保存）。判据在内核
     * （TierPresets.globalState，含已保存自定义比对），这里只呈现。
     */
    val globalConfig: GlobalConfigState = GlobalConfigState.DEFAULT,

    /** 有没有保存过「自定义配置」：决定全局选择器里「使用」是否可用。 */
    val hasSavedCustomPreset: Boolean = false,
    val storage: RelayContainer.StorageSnapshot = RelayContainer.StorageSnapshot(
        0, 0, 0, 0, 0, 0, 0, 0, 0,
        outBytes = 0, outFiles = 0, outByKindBytes = emptyMap(),
        accessibilityOn = false, shizukuOn = false,
    ),
    val surfacePreference: SurfacePreference = SurfacePreference.FOREGROUND,
    val verboseLog: Boolean = false,
    val channelRunning: Boolean = false,

    /** 工作循环探活：null=通道未运行；false=循环活着但无进展（执行器停滞，见状态卡说明行）。 */
    val workLoopLive: Boolean? = null,
    val workQueueDepth: Int = 0,
    val recentEvents: List<RunLogEntry> = emptyList(),
    val recentAudits: List<String> = emptyList(),
    val usageText: String? = null,
    val prompt: ApprovalPrompt? = null,
    /** 屏上那张确认框的编号：回填时只认这一张，不让旧一问的答复答到别的事上。 */
    val promptId: Int = InterlockQueue.ANY_REQUEST,
    /**
     * 排在当前这一问之后、还没轮到的问题数。
     *
     * 状态区要能写「待确认：当前 1 项（另有 N 项排队）」。只写"有一件在等"的话，
     * 用户答完才发现后面还有，节奏就从"一次问清楚"退化成"一次一次挤" ——
     * 而那正是这套排队设计要避免的观感。
     */
    val approvalWaiting: Int = 0,

    /**
     * 队列裁决出的呈现位置。页内卡只认 [InterlockChannel.PAGE]：悬浮卡或通知存在的时候，
     * 同一件事在屏上只能有一处可点，页内这张不出现（判据见 [approvalCardVisible]）。
     */
    val approvalVia: InterlockChannel = InterlockChannel.NONE,

    /**
     * 裁决里那张待决项的编号。与 [promptId] 配对（见 [approvalCardVisible]）：
     * pending 与 routing 两条流的更新各自到达，combine 的某一次配对可能是「新框 + 旧裁决」，
     * 编号对不上时那一帧宁可不画，等两条流对齐。
     */
    val approvalRequestId: Int? = null,
    val shizuku: ShizukuState.Snapshot = ShizukuState.Snapshot(false, false, -1, -1),
    val overlayGranted: Boolean = false,
    val permissions: List<RelayContainer.PermissionState> = emptyList(),
    val accessibilityOn: Boolean = false,

    /** 无障碍此刻缺在哪一档（撤销 / 只是没绑上），无障碍页与提示框都读它。 */
    val accessibilityGap: AccessibilityGap? = null,

    /**
     * 选了「优先后台」而后台屏此刻不可用时缺的那一环；null 表示后台通路正常，
     * 或用户本来就没选后台。首页状态卡按它决定要不要说明「实际仍走前台」。
     */
    val backgroundSurfaceBlock: RelayContainer.BackgroundSurfaceBlock? = null,

    /** 电池优化是否已豁免本应用。 */
    val batteryIgnored: Boolean = false,
    val shizukuInstalled: Boolean = false,

    /** 一次调用被缺无障碍挡下的现场；非空即弹框。 */
    val accessibilityNotice: RelayContainer.AccessibilityNotice? = null,

    /**
     * 受保护应用：名单条数（根页入口行右侧的「未设置 / 已保护 N 个」读它）。
     * 读数在内核（[RelayContainer.protectedAppCount]），这里只转发。
     */
    val protectedAppCount: Int = 0,

    /**
     * 名单当前内容（子页勾选状态的正本）。勾选失败回滚不需要界面记旧值：
     * 写不进去名单就不变，这里随下一次刷新原样读回，勾选框自然弹回。
     */
    val protectedPackages: Set<String> = emptySet(),
)

/**
 * 手机助手界面的状态容器。
 *
 * 刷新分两类来源：
 * 1. 事件——改档位、改执行模式、Shizuku 回调、回填一次确认结果，当场刷；
 * 2. 界面外改动——系统权限、悬浮窗授权、通道自己停、存储被写、日志在长。
 *    这些没有任何回调可挂，只靠「进页面时读一次」就会停在进来的那一刻，
 *    因此界面可见期间按 [TICK_MS] 复查轻判据，每 [HEAVY_EVERY_TICKS] 次复查一次重判据。
 *
 * 轻重分开是因为成本不同：运行日志、授权记录与存储占用要读文件、还要问媒体库，
 * 每秒一遍没有必要；档位可用性与权限授予是内存与少量 binder 调用，秒级可接受。
 * 界面不可见时不刷：那时用户在宿主的其他页面，读到什么值都不影响任何一帧。
 */
class PilotViewModel(
    private val container: RelayContainer,
) : ViewModel() {

    private val state = MutableStateFlow(
        PilotUiState(
            prompt = container.pendingApproval.value?.prompt,
            promptId = container.pendingApproval.value?.id ?: InterlockQueue.ANY_REQUEST,
            approvalWaiting = container.approvalRouting.value.waitingCount,
            approvalVia = container.approvalRouting.value.via,
            approvalRequestId = container.approvalRouting.value.request?.id,
        ),
    )

    val uiState: StateFlow<PilotUiState> = state.asStateFlow()

    /**
     * 通道启动阶段流：装配根状态机的直通转发。它不进 [PilotUiState]、不走节拍复查 ——
     * 「正在启动/未完成」要随迁移当场出现，等下一秒的轻判据就迟了。
     */
    val runtimePhase: StateFlow<RuntimePhase> = container.phase

    /** 启动失败的原因；仅 DEGRADED 阶段非空。原因在阶段迁移前已写定，随取随准。 */
    fun degradedReason(): String? = container.degradedReason

    /** 两次刷新可能同时在 IO 上跑，各自基于旧值 copy 会互相覆盖字段，故串行。 */
    private val refreshLock = Mutex()

    private var ticker: Job? = null

    init {
        viewModelScope.launch {
            // 展示位与排队数同源：两者都在裁决里定，分开订阅会出现「框已经是下一张了、
            // 计数还是上一轮的」，那行字就会说一件不存在的事。
            combine(container.pendingApproval, container.approvalRouting) { request, routing ->
                state.value = state.value.copy(
                    prompt = request?.prompt,
                    promptId = request?.id ?: InterlockQueue.ANY_REQUEST,
                    approvalWaiting = routing.waitingCount,
                    approvalVia = routing.via,
                    approvalRequestId = routing.request?.id,
                )
            }.collect { }
        }
        // Shizuku 的绑定与授权都在本模块界面之外完成；不订阅这条流，
        // 用户授权回来看到的仍是「未检测到」，因为那几个字段是易失变量而不是 State。
        viewModelScope.launch {
            container.shizukuState.collect { refresh() }
        }
        // 一次调用被缺无障碍挡下同样没有节拍可等：信箱里那条错误码到点了，
        // 拿着手机的人要当场看见，而不是下一次刷新才发现。
        viewModelScope.launch {
            container.accessibilityNotice.collect { notice ->
                state.value = state.value.copy(accessibilityNotice = notice)
            }
        }
        refresh()
    }

    /** 界面进入组合：立刻补齐一次，之后按节拍复查。 */
    fun onScreenShown() {
        if (ticker?.isActive == true) return
        refresh()
        ticker = viewModelScope.launch {
            var tick = 0
            var wasVisible = container.foreground.visible
            while (true) {
                delay(TICK_MS)
                if (!container.foreground.visible) {
                    wasVisible = false
                    continue
                }
                tick++
                // 从系统设置页回来时不等下一个节拍的余量：可见性一翻转就补一次全量，
                // 用户在那边改的正是这一屏要显示的东西。
                refreshNow(heavy = !wasVisible || tick % HEAVY_EVERY_TICKS == 0)
                wasVisible = true
            }
        }
    }

    /** 界面离开组合：节拍必须停，否则后台只剩无人的轮询。 */
    fun onScreenHidden() {
        ticker?.cancel()
        ticker = null
    }

    /** 全量刷新：事件之后与进入界面时用，重判据也要跟上。 */
    fun refresh() {
        refreshNow(heavy = true)
    }

    private fun refreshNow(heavy: Boolean) {
        viewModelScope.launch {
            refreshLock.withLock {
                val light = withContext(Dispatchers.IO) {
                    // 能力行只取**一次**：口径判据 readyUsableCount 吃的是同一份 rows，
                    // 不再第二次跑全表探测（第二次就是"路线裁决+系统探测"整探一遍）。
                    val rows = container.ordinaryCapabilityRows()
                    LightSnapshot(
                        rows = rows,
                        usableCount = RelayContainer.readyUsableCount(rows),
                        fullShellTier = container.fullShellTier(),
                        globalConfig = container.globalConfigState(),
                        hasSavedCustomPreset = container.hasSavedCustomPreset(),
                        surfacePreference = container.surfacePreference(),
                        verboseLog = container.isVerboseLog(),
                        channelRunning = container.isChannelRunning(),
                        workLoop = container.workLoopHealth(),
                        shizuku = container.shizukuState.value,
                        overlayGranted = container.overlayGranted(),
                        permissions = container.runtimePermissionStates(),
                        accessibilityOn = container.accessibilityEnabled(),
                        accessibilityGap = container.accessibilityGap(),
                        backgroundSurfaceBlock = container.backgroundSurfaceBlock(),
                        batteryIgnored = container.batteryOptimizationIgnored(),
                        shizukuInstalled = container.shizukuInstalled(),
                        protectedAppCount = container.protectedAppCount(),
                        protectedPackages = container.protectedPackages(),
                    )
                }
                // 一律 update { copy }：这一段挂起过两次 IO，期间 pendingApproval 可能已经换了
                // 一张框。先读后写会把挂起之前的 prompt/promptId 盖回状态里，而那张卡已经不再
                // 对应队列里的问题 —— 用户点它没人认，屏上却永远留着这张答不掉的框。
                state.update {
                    it.copy(
                        rows = light.rows,
                        usableCount = light.usableCount,
                        fullShellTier = light.fullShellTier,
                        globalConfig = light.globalConfig,
                        hasSavedCustomPreset = light.hasSavedCustomPreset,
                        surfacePreference = light.surfacePreference,
                        verboseLog = light.verboseLog,
                        channelRunning = light.channelRunning,
                        workLoopLive = light.workLoop?.channelLive,
                        workQueueDepth = light.workLoop?.inboxDepth ?: 0,
                        shizuku = light.shizuku,
                        overlayGranted = light.overlayGranted,
                        permissions = light.permissions,
                        accessibilityOn = light.accessibilityOn,
                        accessibilityGap = light.accessibilityGap,
                        backgroundSurfaceBlock = light.backgroundSurfaceBlock,
                        batteryIgnored = light.batteryIgnored,
                        shizukuInstalled = light.shizukuInstalled,
                        protectedAppCount = light.protectedAppCount,
                        protectedPackages = light.protectedPackages,
                    )
                }
                if (!heavy) return@withLock
                val heavySnapshot = withContext(Dispatchers.IO) {
                    HeavySnapshot(
                        storage = container.storageSnapshot(),
                        diagnostics = container.diagnostics(),
                        usageText = container.usageText(),
                    )
                }
                state.update {
                    it.copy(
                        storage = heavySnapshot.storage,
                        recentEvents = heavySnapshot.diagnostics.events.map { RunLogEntry(it.level, it.subsystem, it.text, it.atWallMs) },
                        recentAudits = heavySnapshot.diagnostics.auditLines,
                        usageText = heavySnapshot.usageText,
                    )
                }
            }
        }
    }

    fun setTier(id: CapabilityId, tier: TierSelection) {
        container.setTier(id, tier.tier)
        refresh()
    }

    /** 全局档位：一次拨全部能力。批量设置，不授予任何系统权限。五条目里的三个「全部×」。 */
    fun setAllTiers(tier: TierSelection) {
        container.setAllTiers(tier.tier)
        refresh()
    }

    /** 五条目里的「默认配置」：把内置默认配置写进普通能力，显式点选才到这一步。 */
    fun applyDefaultPreset() {
        container.applyDefaultPreset()
        refresh()
    }

    /** 五条目里自定义的「使用」：把已保存自定义一次性套上；没保存过则不动。 */
    fun applyCustomPreset() {
        container.applyCustomPreset()
        refresh()
    }

    /**
     * 编辑器的「保存」：只入库为自定义配置，不套用（套用必须显式点「使用」）。
     * 返回是否写入成功；失败时草稿留在编辑页，由页面给出可读提示。
     */
    fun saveCustomPreset(tiers: Map<CapabilityId, AccessTier>): Boolean {
        val saved = container.saveCustomPreset(tiers)
        refresh()
        return saved
    }

    /**
     * 编辑器的初始表：有已保存自定义则加载它，没有则拿内置默认配置当模板。
     * 退出不保存即丢弃草稿——这里读到的只是一份副本，写不进任何持久状态。
     */
    fun customPresetDraft(): Map<CapabilityId, AccessTier> =
        container.savedCustomPreset() ?: TierPresets.DEFAULT

    /**
     * 完全 Shizuku 特权开关：开=ALWAYS，关=DENIED，两态直写，不弹确认。
     * 档位如何生效（放行、拒绝）由内核闸门判定；这里只转发用户的选择并刷新呈现。
     */
    fun setFullShellEnabled(open: Boolean) {
        container.setFullShellEnabled(open)
        refresh()
    }

    /**
     * 受保护应用：勾选/取消一个包，写入即生效（无保存按钮，集合语义见
     * [toggledProtectedPackages]）。写入失败（包名形状被内核整体拒绝）时返回 false
     * 且名单一字不动——勾选状态的正本在内核，界面随后跟着刷新自然弹回，
     * 页面据此给出可读提示，不假装成功。
     */
    fun setPackageProtected(packageName: String, protect: Boolean): Boolean {
        val next = toggledProtectedPackages(container.protectedPackages(), packageName, protect)
        val saved = container.setProtectedPackages(next)
        refresh()
        return saved
    }

    fun setSurfacePreference(value: SurfacePreference) {
        container.setSurfacePreference(value)
        refresh()
    }

    fun setVerboseLog(enabled: Boolean) {
        container.setVerboseLog(enabled)
        refresh()
    }

    /** 返回释放的字节数；null 表示有调用在途、此刻不清。 */
    fun clearArtifacts(): Long? {
        val freed = container.clearArtifacts()
        refresh()
        return freed
    }

    /**
     * 回填一次答复。[requestId] 由界面从**画出来那一帧**的状态里取，不在这里现读：
     * 状态已经换到下一张框、画面还挂着上一张时，现读会把用户对上一张的「拒绝」答到
     * 下一张上去 —— 那是一条他从未给过的同意或拒绝。
     */
    fun resolveApproval(choice: InterlockChoice, requestId: Int) {
        container.resolveApproval(choice, requestId)
        refresh()
    }

    /** 用户看过「缺无障碍」那张框：收掉它，同一档现场不再重复弹。 */
    fun dismissAccessibilityNotice() {
        container.dismissAccessibilityNotice()
        refresh()
    }

    /** 一次申请全部：递给系统的列表与界面读到的列表同源。 */
    fun requestablePermissions(): List<String> = container.requestablePermissions()

    private data class LightSnapshot(
        val rows: List<RelayContainer.CapabilityRow>,
        val usableCount: Int,
        val fullShellTier: AccessTier,
        val globalConfig: GlobalConfigState,
        val hasSavedCustomPreset: Boolean,
        val surfacePreference: SurfacePreference,
        val verboseLog: Boolean,
        val channelRunning: Boolean,
        val workLoop: MailboxServer.WorkLoopHealth?,
        val shizuku: ShizukuState.Snapshot,
        val overlayGranted: Boolean,
        val permissions: List<RelayContainer.PermissionState>,
        val accessibilityOn: Boolean,
        val accessibilityGap: AccessibilityGap?,
        val backgroundSurfaceBlock: RelayContainer.BackgroundSurfaceBlock?,
        val batteryIgnored: Boolean,
        val shizukuInstalled: Boolean,
        val protectedAppCount: Int,
        val protectedPackages: Set<String>,
    )

    private data class HeavySnapshot(
        val storage: RelayContainer.StorageSnapshot,
        val diagnostics: RelayContainer.RelayDiagnostics,
        val usageText: String?,
    )

    companion object {
        /**
         * 页内卡此刻该不该画。判据有二：
         * - 裁决把这一问摊到了页内（[InterlockChannel.PAGE]）——悬浮卡在屏（OVERLAY）或
         * 通知存在（NOTIFICATION）的时候再画一张，同一件事就有两处可点，用户点哪、
         * 助手收到哪一次就说不清了；
         * - 裁决里那张待决项与即将作答的编号一致（[routingRequestId] == [renderedId]）——
         * pending 与 routing 两条流的更新各自到达，combine 的某一次配对可能是
         * 「新框 + 旧裁决」，编号对不上时那一帧宁可不画。
         * 没有待决项时自然也不画。
         *
         * 抽成纯判据是给 JVM 单测穷举用的：路由矩阵本身在 core 测，
         * 这里钉住的是「界面消费裁决」这半段不跑偏。
         */
        fun approvalCardVisible(
            prompt: ApprovalPrompt?,
            via: InterlockChannel,
            routingRequestId: Int?,
            renderedId: Int,
        ): Boolean = prompt != null && via == InterlockChannel.PAGE && routingRequestId == renderedId

        /** 轻判据的复查节拍：与「用户还在看着这一屏」的时间尺度对齐。 */
        private const val TICK_MS = 1_000L

        /** 每 N 次节拍做一次全量：日志、记录与存储占用按 3 秒一档更新。 */
        private const val HEAVY_EVERY_TICKS = 3

        fun factory(container: RelayContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PilotViewModel(container) as T
            }
    }
}
