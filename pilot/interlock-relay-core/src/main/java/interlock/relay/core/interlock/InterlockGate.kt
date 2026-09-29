package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.ProbeResult
import interlock.relay.core.protocol.SystemGrantState
import interlock.relay.core.protocol.TierCeiling
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow

/**
 * 判定结果。[decisionLabel] 原样写入授权记录，便于事后核对当时依据哪条规则放行。
 *
 * [requiresApproval] 标记本次调用**走了审批通路**（无论最终是当场允许还是没等到人）：
 * 执行限流只对没走这条通路的调用记账，审批通路的频率由闸门按「同一件事」合并计入
 * （见 [authorize]），两处各记一次会把同一次调用记成两笔。
 */
data class GateResult(
    val allowed: Boolean,
    val requiresApproval: Boolean = false,
    val error: RelayError? = null,
    val systemState: SystemGrantState = SystemGrantState.GRANTED,
    val tier: AccessTier = AccessTier.DENIED,
    val decisionLabel: String = "",
)

/**
 * 双重校验：放行 = 系统权限可达 且 助手授权允许。
 *
 * 返回码区分「缺系统权限」与「被授权档位挡下」，因为两者用户要做的事完全不同：
 * 前者要去系统里开，后者只需在本应用里改档位。
 *
 * [now] 必须是单调时钟：会话免弹窗窗口与限速窗口都是安全判定，
 * 用墙钟的话把系统时间往前调就能绕过限速、往后调就能让「本会话内允许」无限续期。
 *
 * 限流分两类，这里只管**审批频率**一类（按「同一件事」合并计入，见 [authorize]）：
 * 真正执行的配额由调用方在派发前查（见 RateLimiter 的两类阈值来源）。系统权限快照
 * 也不再在这里取——它必须按本次裁决出的执行路线探测，由调用方先定路线再传入。
 */
class InterlockGate(
    private val tiers: TierStore,
    private val broker: InterlockBroker,
    private val runLog: RunLog,
    private val now: () -> Long = ::monotonicNow,
) {

    /** 审批频率限速器：与执行配额同一张分档表，但单独实例、单独记账。 */
    private val approvalRate = RateLimiter(now, RateLimiter.EXECUTION_LIMITS)
    private val callCounts = java.util.concurrent.ConcurrentHashMap<CapabilityId, Int>()

    /**
     * 「同一件事」的审批频率合并表：键为 capability+argsIdentity，值为上次计入时刻。
     * 容量封顶 [APPROVAL_IDENTITY_CAPACITY]，超限淘汰最旧——它防的是重复，不承担
     * 记录全部历史的职责。
     */
    private val approvalSeen = java.util.LinkedHashMap<String, Long>()

    /**
     * [budgetMs] 为本次调用在通道里剩下的预算，用于给确认框窗口封顶，见 [InterlockBroker.request]。
     *
     * [system] 是调用方按本次裁决出的执行路线探测出的系统权限快照：同一能力在不同路线
     * 上需要的系统条件不同（坐标四条走可信屏就不需要无障碍），先定路线再探测才不会
     * 用「另一条路」的权限状态放行或挡下这一次调用。
     *
     * [consentScreen]：目标落在系统授权界面上（见 [ConsentSurfaces]）。这一条把会话授权
     * 与「本会话内允许」那颗按钮一起关掉 —— 用户在别处答过一次"读通讯录"，不等于答过
     * "替任意应用批准权限"。档位为「完全访问」时同样强制当场问。
     */
    suspend fun authorize(
        descriptor: CapabilityDescriptor,
        targetLabel: String?,
        paramDetail: String?,
        argsIdentity: String,
        volatileTarget: Boolean,
        budgetMs: Long,
        consentScreen: Boolean = false,
        system: ProbeResult,
        /**
         * 「把宿主自己带到前台」这一形状（`app.launch` 且参数只有本应用包名）。
         *
         * 为什么单给一条豁免：这条命令的整个理由就是"任务收尾把用户带回宿主应用"，
         * 而它最常用的时刻恰恰是助手页不在前台、用户正看着别的应用——那时弹一张确认框，
         * 用户既不知道要答什么，答完之前这一趟也回不去（`E_AWAITING_CONSENT`，三面都
         * 问不到人时还是 `E_GATE_NO_FOREGROUND`）。它不读数据、不改状态、只换前台，
         * 与档位无关，因此在这里放行；授权记录里单独记一条判据名，档位照实记，
         * 而不是把用户的档位设置改掉。
         */
        hostBringToFront: Boolean = false,
    ): GateResult {
        val id = descriptor.id
        val systemState = system.state
        val tier = tiers.tierOf(id)

        if (hostBringToFront) {
            return GateResult(true, false, null, systemState, tier, "host_bring_to_front")
        }

        verdict(systemState, tier, descriptor.ceiling)?.let { return it }

        val sessionGranted = !consentScreen && tiers.sessionGranted(id, now())
        val needsApproval = requiresApproval(tier, descriptor.ceiling, sessionGranted, consentScreen)
        if (!needsApproval) {
            return GateResult(true, false, null, systemState, tier, "auto")
        }

        // 审批频率按「同一件事」合并后计入：同一条确认框（能力+参数身份一致）在滑动窗口内
        // 反复重查只记一次。不合并的话，助手在等人答题的那几十秒里每一次重发都会吃一个
        // 名额，额度吃光后连真正的重试都进不来。合并掉的那几条不再查限速器——它们在
        // 本窗口内已经计过一次。
        if (countsTowardApprovalFrequency(id, argsIdentity) && !approvalRate.accepted(id)) {
            return GateResult(false, true, RelayError.RATE_LIMITED, systemState, tier, "rate_limited")
        }

        val index = callCounts.compute(id) { _, previous -> (previous ?: 0) + 1 } ?: 1
        val outcome = broker.request(
            ApprovalPrompt(
                capability = id,
                argsIdentity = argsIdentity,
                volatileTarget = volatileTarget,
                titleRes = descriptor.titleRes,
                summaryRes = descriptor.summaryRes,
                targetLabel = targetLabel,
                risk = riskOf(id),
                callIndexInSession = index,
                paramDetail = paramDetail,
                allowsSessionGrant = descriptor.ceiling != TierCeiling.ASK_ONLY && !consentScreen,
            ),
            budgetMs = budgetMs,
        )
        return when (outcome) {
            ApprovalOutcome.AllowedOnce ->
                GateResult(true, true, null, systemState, tier, "approved_once")

            ApprovalOutcome.AllowedForSession -> {
                tiers.grantForSession(id, now() + SESSION_GRANT_MS)
                GateResult(true, true, null, systemState, tier, "approved_session")
            }

            else -> {
                val error = broker.errorFor(outcome) ?: RelayError.GATE_HOST_DENIED
                runLog.event(
                    LogSubsystem.GATE,
                    LogEvent.APPROVAL_DENIED,
                    "cap" to id.wire,
                    "code" to error.code,
                )
                // 记录里的标签要按**到底发生了什么**分，不能把非"等用户点"的都写成 denied：
                // 「用户没来得及答」「窗口到点没人答」「三条通路都问不到人」都不是用户拒绝，
                // 混成一词会让事后核对把一件没被否决的事读成被否决。
                val label = approvalLabel(outcome, error)
                GateResult(false, true, error, systemState, tier, label)
            }
        }
    }

    /**
     * 这次审批请求是否要新计一次频率。返回 true=新计一次（并把身份记入合并表），
     * false=与窗口内已有的一次合并、不再记账。
     *
     * 身份键是能力+参数身份（参数摘要，不含原文）。只在窗口内合并：隔着窗口再来一次
     * 同样的请求，说明又有一件新的事要用户确认，照常计。
     */
    private fun countsTowardApprovalFrequency(id: CapabilityId, argsIdentity: String): Boolean {
        val key = id.wire + ":" + argsIdentity
        val windowStart = now() - RateLimiter.WINDOW_MS
        synchronized(approvalSeen) {
            val last = approvalSeen[key]
            if (last != null && last >= windowStart) return false
            // 先删再放，保持插入序与时刻序一致，淘汰时直接摘最旧的表头即可。
            approvalSeen.remove(key)
            approvalSeen[key] = now()
            while (approvalSeen.size > APPROVAL_IDENTITY_CAPACITY) {
                val eldest = approvalSeen.entries.iterator()
                eldest.next()
                eldest.remove()
            }
            return true
        }
    }

    private fun riskOf(id: CapabilityId): RiskLevel = when (id) {
        CapabilityId.PKG_INSTALL,
        CapabilityId.SECURE_SETTINGS,
        CapabilityId.APPOPS_SET,
        CapabilityId.APP_STOP,
        CapabilityId.AUDIO_CAPTURE,
        -> RiskLevel.HIGH

        CapabilityId.SCREEN_CAPTURE,
        CapabilityId.SCREEN_RECORD,
        CapabilityId.SCREEN_OBSERVE,
        CapabilityId.NOTIFY_READ,
        CapabilityId.CLIPBOARD_READ,
        CapabilityId.CONTACT_READ,
        CapabilityId.CONTACT_WRITE,
        CapabilityId.LOCATION_READ,
        CapabilityId.MEDIA_WRITE,
        -> RiskLevel.MEDIUM

        else -> RiskLevel.LOW
    }

    companion object {
        const val SESSION_GRANT_MS = 30L * 60L * 1000L

        /** 合并表的容量上限：同一时刻挂着的「不同待答问题」不会超过这个量级。 */
        private const val APPROVAL_IDENTITY_CAPACITY = 64

        /**
         * 一次没放行的裁决在记录与回包 `reason` 里怎么署名。
         *
         * 四种"没放行"是四件不同的事：用户答了不、这一次没等到人答、窗口到点没人答
         * （含"用户答晚了而这一类能力不认迟到答案"）、以及三条通路都问不到人。
         * 把它们都写成 `denied_*` 会让事后核对读成"用户否决了这件事"。
         */
        internal fun approvalLabel(outcome: ApprovalOutcome, error: RelayError): String = when (outcome) {
            ApprovalOutcome.AwaitingConsent -> "awaiting_consent"
            // 队列满只是「位置没排上」，不是任何一种拒绝；排在前面的是哪几件、还差几个
            // 位置这类细节由回包侧补，这里只给一个不含指控的署名。
            ApprovalOutcome.WaitingTurn -> "waiting_turn"
            ApprovalOutcome.TimedOut -> "expired_${error.code}"
            ApprovalOutcome.CannotPresent -> "no_surface"
            ApprovalOutcome.Denied -> "denied_${error.code}"
            ApprovalOutcome.AllowedOnce,
            ApprovalOutcome.AllowedForSession,
            -> "approved"
        }

        /**
         * 双重校验的纯判定：给足三个输入就能出结论，不碰 Context、不碰系统状态、
         * 也不问限频器。放在伴生对象里是为了能在无设备环境下穷举验证。
         * 返回 null 表示放行。
         *
         * 限流不在这张表里：入口防灌与执行配额都是调用方的账（见 RateLimiter 的两类
         * 阈值来源），把「量太大」与「权限/档位不够」混进同一张判定表，会让前者的
         * 措辞盖住后者——调用方分不清该减速还是该去开权限。
         */
        fun verdict(
            system: SystemGrantState,
            tier: AccessTier,
            ceiling: TierCeiling,
        ): GateResult? = when {
            systemBlocks(system) -> when (system) {
                SystemGrantState.UNAVAILABLE_ON_DEVICE -> GateResult(
                    false,
                    error = RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE,
                    systemState = system,
                    tier = tier,
                    decisionLabel = "unavailable_on_device",
                )

                else -> GateResult(
                    false,
                    error = RelayError.GATE_SYSTEM_MISSING,
                    systemState = system,
                    tier = tier,
                    decisionLabel = "system_missing",
                )
            }

            tier == AccessTier.DENIED -> GateResult(
                false,
                error = RelayError.GATE_HOST_DENIED,
                systemState = system,
                tier = tier,
                decisionLabel = "tier_denied",
            )

            else -> null
        }

        /**
         * 一次调用是否需要弹确认框。三档上限各自的免弹语义：
         * `ASK_ONLY` 永不免弹；`SESSION_ONLY` 的「始终允许」只在本会话内免弹；
         * `ANY` 的「始终允许」长期免弹。缺了 `SESSION_ONLY` 就等于把截屏、
         * 读通知这类升成永久免弹。
         *
         * 会话授权优先于档位判断：界面上那个「本会话内允许」按下去就是答了一次，
         * 若仍按 `tier == ASK` 弹框，那次答话等于白给——按钮成了装饰。
         * `ASK_ONLY` 排在最前，因为它拿不到会话授权（`allowsSessionGrant` 为假），
         * 顺序反过来就会把最高风险那一档也免掉。
         */
        fun requiresApproval(
            tier: AccessTier,
            ceiling: TierCeiling,
            sessionGranted: Boolean,
            /**
             * 目标是系统授权界面（见 [ConsentSurfaces]）。命中即一律当场问：会话授权不认、
             * 「完全访问」这一档也不放过 —— 那一档说的是用户信任这个助手替他操作他自己的
             * 手机，不是信任它替任意应用向他要权限。
             */
            consentScreen: Boolean = false,
        ): Boolean = when {
            consentScreen -> true
            ceiling == TierCeiling.ASK_ONLY -> true
            sessionGranted -> false
            else -> tier == AccessTier.ASK || ceiling == TierCeiling.SESSION_ONLY
        }

        /**
         * 系统权限态是否挡住一次调用。**判定与能力清单必须共用这一处**：
         * 各写一套会让能力清单报「不可用」而宿主其实能执行（或反过来），
         * 助手按能力清单办事就永久调不到那两条能力。
         *
         * `SESSION_CONSENT` 不挡：它表示调用本身可达，只是系统每次要弹自己的确认框。
         */
        fun systemBlocks(state: SystemGrantState): Boolean = when (state) {
            SystemGrantState.RUNTIME_ASKABLE,
            SystemGrantState.MANUAL_ONLY,
            SystemGrantState.UNAVAILABLE_ON_DEVICE,
            -> true

            SystemGrantState.GRANTED,
            SystemGrantState.SESSION_CONSENT,
            // 剪贴板那一类不挡：调用可达，只是要本应用在前台且持有输入焦点。用户没有
            // 任何地方可以"授予"它，挡下来等于永久拒绝一条本来能做的操作。
            SystemGrantState.FOREGROUND_FOCUS,
            -> false
        }
    }
}

/**
 * 滑动窗口限速。防止助手在逻辑死循环里以极高频率重复调用同一能力，
 * 熔断器只覆盖「调用报错」，覆盖不了「调用成功但行为失控」。
 *
 * 阈值不在类里写死：入口防灌与执行配额是两本不同的账（一个宽、一个严），
 * 各自实例化时带上自己的阈值来源，同一个类服务两种口径。
 */
internal class RateLimiter(
    private val now: () -> Long,
    private val limitOf: (CapabilityId) -> Int,
) {

    private val events = java.util.concurrent.ConcurrentHashMap<CapabilityId, ArrayDeque<Long>>()

    fun accepted(id: CapabilityId): Boolean {
        val windowStart = now() - WINDOW_MS
        val queue = events.getOrPut(id) { ArrayDeque() }
        synchronized(queue) {
            while (queue.isNotEmpty() && queue.first() < windowStart) queue.removeFirst()
            if (queue.size >= limitOf(id)) return false
            queue.addLast(now())
            return true
        }
    }

    companion object {
        internal const val WINDOW_MS = 60_000L

        /**
         * 执行配额的分档（每分钟）：观测类读多但廉价，控制类每条都是真实动作，
         * 其余按最低档保守。只对**真正派发**的调用记账。
         */
        val EXECUTION_LIMITS: (CapabilityId) -> Int = { id ->
            when (id.category) {
                interlock.relay.core.protocol.CapabilityCategory.OBSERVE -> 30
                interlock.relay.core.protocol.CapabilityCategory.CONTROL -> 60
                else -> 20
            }
        }

        /**
         * 入口防灌的宽上限：执行配额的 2 倍起。它是通道入口的洪水挡板——参数还没看
         * 就先把量掐住，连非法洪泛一起覆盖；**不是执行配额**，别拿它去解释
         * 「正常调用为什么被限」，那个问题归 [EXECUTION_LIMITS]。
         */
        val ENTRY_LIMITS: (CapabilityId) -> Int = { id -> 2 * EXECUTION_LIMITS(id) }
    }
}
