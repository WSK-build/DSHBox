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
import interlock.relay.core.interlock.InterlockQueue
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

/** 档位选择。「每次询问」上限的能力由裁决层强制，界面一律三档可选。 */
enum class TierSelection(val tier: AccessTier) {
    DENIED(AccessTier.DENIED),
    ASK(AccessTier.ASK),
    ALWAYS(AccessTier.ALWAYS),
}

/** 界面一次渲染所需的全部状态，全部来自装配根；界面不自算任何判定。 */
data class PilotUiState(
    val rows: List<RelayContainer.CapabilityRow> = emptyList(),
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
                // 可信虚拟屏的起停没有回调，只能逐拍比对。它一翻转，审批的呈现通路
                // 就要重算 —— 否则后台模式里留着一张看不见的悬浮卡，或屏已可用时还只
                // 回落到通知栏，用户面前两种情况都没有可点的东西。
                container.onHeartbeat()
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
                    LightSnapshot(
                        rows = container.capabilityRows(),
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
                    )
                }
                // 一律 update { copy }：这一段挂起过两次 IO，期间 pendingApproval 可能已经换了
                // 一张框。先读后写会把挂起之前的 prompt/promptId 盖回状态里，而那张卡已经不再
                // 对应队列里的问题 —— 用户点它没人认，屏上却永远留着这张答不掉的框。
                state.update {
                    it.copy(
                        rows = light.rows,
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
    )

    private data class HeavySnapshot(
        val storage: RelayContainer.StorageSnapshot,
        val diagnostics: RelayContainer.RelayDiagnostics,
        val usageText: String?,
    )

    companion object {
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
