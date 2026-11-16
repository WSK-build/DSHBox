package interlock.relay.core.runtime

import android.content.Context
import interlock.relay.core.protocol.CapabilityArgsSpec
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.spi.RelayCapabilities
import interlock.relay.core.spi.RelayText
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.RelayRequest
import interlock.relay.core.protocol.RelayResponse
import interlock.relay.core.protocol.ProbeResult
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.protocol.SystemGrantState
import interlock.relay.core.protocol.effectStateFor
import interlock.relay.core.protocol.retryPolicyOf
import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.interlock.ApprovalOutcome
import interlock.relay.core.interlock.InterlockTargets
import interlock.relay.core.interlock.GateResult
import interlock.relay.core.interlock.InterlockGate
import interlock.relay.core.interlock.ProtectedAppsStore
import interlock.relay.core.interlock.RateLimiter
import interlock.relay.core.interlock.protectedTarget
import interlock.relay.core.interlock.SystemStateProbe
import interlock.relay.core.log.AuditEntry
import interlock.relay.core.log.AuditLog
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.surface.BackendDispatcher
import interlock.relay.core.surface.LaunchFailureClass
import interlock.relay.core.surface.SurfaceDecision
import interlock.relay.core.surface.SurfacePolicy
import interlock.relay.core.surface.SurfacePreference
import interlock.relay.core.surface.TargetLanding
import interlock.relay.core.surface.TargetLandingTable
import interlock.relay.core.surface.TargetRoute
import interlock.relay.core.surface.resolveLandingTarget
import interlock.relay.core.surface.routeForTarget
import interlock.relay.core.surface.settleLaunchResult
import interlock.relay.core.transport.HandlerResult
import interlock.relay.core.transport.ParkedRequest
import interlock.relay.core.transport.ParkedRequestBridge
import interlock.relay.core.transport.RelayRequestHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 调用主链路：解析 → 自身目标守卫 → 入口防灌 → 公共预校验 → 执行模式裁决 →
 * 按路线探测系统权限 → 授权（含审批） → 执行限流 → 后端分发 → 产物落盘 → 记录。
 *
 * 所有判定集中在此处串起，任何一环失败都返回明确错误码，不返回半成品结果。
 * 排序本身是一条纪律：越便宜、越无副作用的检查越靠前——参数拼错的调用不能先
 * 占掉一次用户确认（六十秒）或一次执行配额，才被 `E_TRANSPORT_MALFORMED` 打回。
 *
 * 耗时一律取单调时钟差；执行模式一律取裁决结果，后端不回报、此处也不二次推断，
 * 否则成功与失败分支会各写一套字面量。
 */
class RelayCoordinator(
    context: Context,
    private val gatekeeper: InterlockGate,
    private val policy: SurfacePolicy,
    private val dispatcher: BackendDispatcher,
    /**
     * 能力表与文案取用口：能力登记与确认框的计数词条都从宿主注入的实现取，
     * 缺省为 core 内建全表与系统语言词条。
     */
    private val capabilities: RelayCapabilities,
    private val text: RelayText,
    /**
     * 按本次裁决出的执行路线探测系统权限：同一能力在不同路线上需要的系统条件不同，
     * 先定路线再探测，权限快照才与真实执行同口径。
     */
    private val probe: SystemStateProbe,
    private val paths: RelayPaths,
    private val runLog: RunLog,
    private val auditLog: AuditLog,
    private val now: () -> Long = ::monotonicNow,
    /** 可信屏的有无变了要说一声：助手侧清单里其余五条后台能力的 `usable` 全看它。 */
    private val onSurfaceChanged: () -> Unit = {},
    /**
     * 派发前置守卫：动作提交给后端之前，由调用方把「可能已派发」的边界标记钉进持久介质，
     * 返回 false 即拒绝本次派发 —— 标记没落盘，崩溃恢复就无法判断副作用是否已发生，
     * 宁可不执行。null = 未接 v2 执行链，所有请求照常派发。
     */
    private val dispatchGuard: ((String) -> Boolean)? = null,
    /**
     * 审批等待的挂起面。接上后，等用户答复的请求把等待移出执行槽：审批提交、执行槽
     * 释放，答复（或审批到点）到来时由通道把续行体排上串行执行。null 或满员时走
     * 内联等待，占用本次调用自己的执行窗口。
     */
    private val parkBridge: ParkedRequestBridge? = null,
    /** 挂起等待环的运行作用域；取不到（未启动/已停止）时同样走内联等待。 */
    private val parkScope: (() -> CoroutineScope?)? = null,
    /**
     * 特权通路（`sys.shell.full`）此刻的执行身份 uid，由装配根供 Shizuku 快照值；
     * 授权记录的 `uid` 字段按它署名（shell / root / uid / unknown）。读不到时如实
     * unknown，不冒充本应用身份。
     */
    private val fullShellUid: () -> Int = { -1 },
    /** v2 取消意愿查询：等待环每轮开跑前问一次，答 true 即以「已取消、未执行」收场。 */
    private val isCancelRequested: ((String) -> Boolean)? = null,
    /**
     * 「这一问谁也问不到」时该点名做哪一步。由装配根接队列的裁决结果。
     *
     * 只有这一种失败需要它：`E_GATE_NO_FOREGROUND` 本身不区分「通知被关」与
     * 「自己在后台模式里跑」，而这两种的处置完全相反（一个去系统里开通知，一个退出后台模式）。
     */
    private val approvalDeadEnd: () -> String? = { null },
    /**
     * 这台机器是否**拒绝过** trusted 建屏（装配根从 Shizuku 后端读实时标记）。
     *
     * 调用侧的裁决在清单之前跑：用户被拒后切了前台，`surface.virtual` 的 requiresMode
     * 分支会先答「需要后台模式」——插件便按它把人送回后台再撞一次拒绝，绕成循环。
     * 被拒是这台机器上的固定结论，必须在 requiresMode 分支**之前**分派并优先回答
     * （回 E_SURFACE_TRUSTED_DENIED，reason 写明改前台）。null/缺省 = 未接装配，
     * 这条旁路不生效（单测装配不含它时行为与无标记一致）。
     */
    private val trustedDisplayDenied: () -> Boolean = { false },
    /**
     * 这台设备建可信屏被运行时自检判过「破坏主屏」（装配根从 Shizuku 后端读实时
     * 标记）。它**不进调用侧 fail-fast**（earlyVerdict）：unsafe 只封自动后台路由——
     * 就是下面那段「替用户先建屏再启动」的准备，以及后端 supports 的后台落点真值表；
     * 显式的 surface.virtual create/release/query 不受它拦，标记的解除依赖那条通路。
     */
    private val trustedDisplayUnsafe: () -> Boolean = { false },
    /**
     * 目标落点层（独立于执行偏好裁决的派发前路由）。接上后：
     * - `app.launch` 的核验落点写进登记簿，后续 `ui.*` / `screen.*` 按目标沿用；
     * - 后台面缺失且 `surface.virtual` 为「完全访问」时，先按现有建屏逻辑准备再后台启动；
     * - 后台启动失败按确定性/暂时性/未知分类，只有确定性失败允许一次目标级前台兜底；
     * - 结果未知的启动以参数摘要记幂等标记，相同的重发先去核实；
     * 同一目标被成功核实（pkg.query / ui.snapshot / 核验过的启动）后标记即摘。
     * null/缺省 = 未接路由层：落点沿用、备屏与幂等标记全部不生效，调用按执行偏好
     * 裁决；唯"未核验的启动不是成功"这条与装配无关，无登记簿时同样成立。
     */
    private val landingTable: TargetLandingTable? = null,
    /** 落点重校验用：记录指向的那块可信屏此刻还在不在。 */
    private val trustedDisplayAlive: () -> Boolean = { false },
    /**
     * 落点沿用前的"目标还在不在最前面"核对：按屏号回当前顶层包名，读不到回 null
     * （null 表示核验不了，调用侧不沿用旧落点）。
     */
    private val topPackageOn: suspend (Int) -> String? = { null },
    /** 用户当前的执行偏好读数：落点记录按它判失效。 */
    private val surfacePreference: () -> SurfacePreference = { SurfacePreference.FOREGROUND },
    /**
     * 「受保护应用」名单。null/缺省 = 未接装配，这条旁路不生效（行为与没有名单时
     * 完全一致）。名单优先级高于能力档位：判定发生在进审批之前与派发之前，
     * 不读档位——完全访问也不放行。
     */
    private val protectedApps: ProtectedAppsStore? = null,
) : RelayRequestHandler {

    private val appContext = context.applicationContext
    private val selfPackage = context.applicationContext.packageName

    /** 能力查找索引：以注入表为准，与能力清单、资产重铺同一口径。 */
    private val capabilityIndex: Map<CapabilityId, CapabilityDescriptor> =
        capabilities.all().associateBy { it.id }

    /**
     * 入口防灌的宽上限（执行配额的 2 倍起）：参数还没看就先把洪水挡住，覆盖非法请求。
     * 它**不是执行配额**——正常调用的频度问题归 [execRate]，两条账分开，
     * 措辞才不会把「减速」与「这条能力被限流」混成一句。
     */
    private val entryRate = RateLimiter(now, RateLimiter.ENTRY_LIMITS)

    /** 执行配额：只对真正派发的调用记账，走审批通路的调用不占它（频率已由闸门合并计入）。 */
    private val execRate = RateLimiter(now, RateLimiter.EXECUTION_LIMITS)

    /**
     * 「同一件事已经有一条挂起在等答复」的登记表：键与闸门的审批频率合并表同一形状，
     * 值是不可变记录（写入者序号 + 审批截止）。并发规则见 [ParkedWaitTable]。
     *
     * 这张表挡的是双等待者：队列按「同一件事」复用同一张框，两个等待者会拿到同一份
     * 答复，把一次批准花在两条请求上。执行槽被挂起释放之后，同问的后到请求必须
     * 在这里被挡在审批队列之外。
     */
    private val parkedWaits = ParkedWaitTable(now)

    override suspend fun handle(request: RelayRequest, budgetMs: Long): HandlerResult {
        val startedAt = now()
        // 耗时一律「一次分支一次采样」：授权记录、运行日志与响应里的三个数字必须同源。
        fun elapsed(): Long = now() - startedAt

        val descriptor = CapabilityId.fromWire(request.capability)?.let { capabilityIndex[it] }
            ?: return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.CAPABILITY_NOT_IMPLEMENTED,
                    request.capability,
                    null,
                    elapsed(),
                ),
            )

        // 助手拿本机身份做参数的一律挡下。参数键名不止 `package` 一个，
        // 因此扫全部字符串值而不是逐个键名匹配——漏一个键就等于留一个后门。
        // 唯一的例外是「把宿主带到前台」：任务链的收尾一步，见 isHostBringToFront。
        val bringToFront = isHostBringToFront(request)
        val selfArg = selfTargetArg(request.args, selfPackage)
        if (selfArg != null && !bringToFront) {
            // 回包点名撞在哪个参数上：整条调用指向宿主与被顺带写进过滤条件，是两种处置
            // （换目标 / 去掉那个参数），只说 "self target" 时助手两种都可能去做。
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.GATE_HOST_DENIED,
                    "arg \"$selfArg\" names this app itself, and the assistant cannot read or act on the " +
                        "host app: drop that argument or name another app",
                    descriptor,
                    elapsed(),
                ),
            )
        }

        // 入口防灌排在一切内容判定之前：无论参数对错，先把每分钟的总量掐住。
        // 这道宽上限只回答「量太大，减速」，不消耗也不替代执行配额。
        if (!entryRate.accepted(descriptor.id)) {
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.RATE_LIMITED,
                    RelayError.RATE_LIMITED.code + "/entry_flood",
                    descriptor,
                    elapsed(),
                ),
            )
        }

        // 公共预校验（不读设备、无副作用、不消耗审批与执行限流）：顶层键集/必填/类型
        // 与三张后端键表同源，在这里被打回的调用不会占用户一次确认，也不会占通道一次
        // 等待。涉及运行时对象（节点在不在、文件有多大）的检查仍留在后端已授权之后。
        CapabilityArgsSpec.validate(descriptor.id, request.args)?.let { reason ->
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.TRANSPORT_MALFORMED,
                    reason,
                    descriptor,
                    elapsed(),
                ),
            )
        }

        // 受保护应用（入口层）：参数里明确点名了目标包名、且该包此刻在名单里时，
        // 在进闸门/审批之前直接拒绝——不入审批队列、不弹确认框，也不占一次用户确认。
        // 名单优先级高于能力档位：判定在这里与档位无关，完全访问同样被拒；名单为空时
        // protectedTarget 的读数是空集，blocks 恒 false，行为与没有名单时完全一致。
        // 完全 Shizuku/ADB/root 不在此列：sys.shell / sys.shell.full 不解析命令，原样放行。
        val namedTargetPackage = protectedTarget(descriptor.id, request.args)
        if (namedTargetPackage != null && protectedApps?.blocks(namedTargetPackage) == true) {
            logProtectedBlock(descriptor.id, namedTargetPackage, BLOCKED_AT_ENTRY)
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.PACKAGE_PROTECTED,
                    "package $namedTargetPackage is on the user's protected-apps list: the assistant " +
                        "can neither operate it nor read it; it can run only after the user removes " +
                        "that package from the protected-apps page",
                    descriptor,
                    elapsed(),
                ),
            )
        }

        val (targetLabel, paramDetail) = InterlockTargets.of(descriptor, request.args) { count ->
            String.format(text.string(interlock.relay.core.R.string.relay_approval_char_count), count)
        }
        // 结果未知的启动的幂等缝：同一份参数的上一次启动还没核实结局时，相同的重发先被
        // 挡下去核实。标记带寿命，核实动作本身不受限；没有路由层装配时此缝不存在。
        val launchDigest = if (descriptor.id == CapabilityId.APP_LAUNCH && !bringToFront) {
            auditLog.argsDigestOf(request.args.toString())
        } else {
            null
        }
        if (launchDigest != null && landingTable?.hasUnknownLaunch(launchDigest) == true) {
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.TRANSPORT_UNKNOWN,
                    "an identical launch earlier ended with an unverified outcome: check where the " +
                        "target actually is with pkg.query or ui.snapshot before launching the same " +
                        "target again",
                    descriptor,
                    elapsed(),
                ),
            )
        }
        // 派发前的独立路由层：有目标落点记录时沿用，没有时退回执行偏好裁决。
        // [followedTarget] 非空表示这份裁决来自落点记录，派发前要重新校验。
        var followedTarget: String? = null
        var decision: SurfaceDecision = if (bringToFront) {
            // 收尾回宿主这一形状必须落在用户眼前的屏上：后台优先模式下它会被派去可信虚拟屏，
            // 应用起在那块屏上、用户根本看不到——而这恰恰是这条命令要修的事。
            SurfaceDecision(SurfaceKind.FOREGROUND)
        } else {
            when (val route = routeCall(descriptor, request.args)) {
                is TargetRoute.Follow -> {
                    followedTarget = route.target
                    route.decision
                }
                is TargetRoute.Reject -> return HandlerResult.Done(
                    rejected(
                        request.id,
                        route.error,
                        route.reason,
                        descriptor,
                        elapsed(),
                    ),
                )
                TargetRoute.None -> policy.decide(descriptor)
            }
        }
        // 单一执行面的能力被偏好链卡死时（用户选了前台，而它只认可信屏）在这里点名
        // 「需要哪个模式」：不进闸门、不进后端——伪造的前台落点只会把一次注定失败的
        // 调用先送过用户确认，再换成一条泛化的「够不着」。
        // 顺序里有行为：**用户的 DENIED 档先于机器的拒绝**——档位是用户自己的决定，
        // 把能力拨到禁止时回 GATE_HOST_DENIED（档位语义），不被 ROM 的拒绝标记盖过
        // 说成"这台机器拒绝"；其后被拒（trustedDisplayDenied）才在 requiresMode 解析
        // 之前分派（[earlyVerdict]，id 限 surface.virtual）。用户被拒后切了前台，surface.virtual 的
        // 裁决会先给出 requiresMode——若先答 modeRequired，插件就按"切回后台/建屏"建议，
        // 用户又切回后台再撞一次拒绝，绕成循环。被拒是这台机器上的固定结论，永远优先，
        // 回包 reason 写明改前台。
        // **顺序在这里成一段单一判定**（[earlyVerdict]）：用户的 DENIED 档先于机器的拒绝
        // ——档位是用户自己的决定，被拨禁止时回 GATE_HOST_DENIED（下一步"去档位页解禁"），
        // 不被 ROM 的拒绝标记盖过说成"这台机器拒绝"；其后 surface.virtual 被拒才按
        // E_SURFACE_TRUSTED_DENIED 回（reason 写明改前台）。两项都是在 requiresMode 解析
        // **之前**：如果先答 modeRequired，插件按"切回后台/建屏"建议把人送回后台再撞一次
        // 拒绝，绕成循环。tierDenied 从闸门只读档位、surfaceDenied 从装配根对后端收实时
        // 标记，两读都进同一个纯函数——生产路径就是这里，测试穷举的就是同一份判据。
        // 「把宿主带回前台」这一形状不进这道判定：它不读数据、不改状态，三档的语义都是
        // 同一次 bring-to-front，禁止档也拦下就断了任务链的收尾；机器的 trusted 拒绝
        // 标记只对 surface.virtual 有意义，本就轮不到这条形状。
        when (val verdict = if (bringToFront) {
            null
        } else {
            earlyVerdict(
                id = descriptor.id,
                tierDenied = gatekeeper.tierOf(descriptor.id) == AccessTier.DENIED,
                surfaceDenied = trustedDisplayDenied(),
            )
        }) {
            RelayError.GATE_HOST_DENIED -> return HandlerResult.Done(
                rejected(
                    request.id,
                    verdict,
                    "capability ${descriptor.id.wire} is set to denied by the user's tier; " +
                        "asking the user to lift the tier is the only way through",
                    descriptor,
                    elapsed(),
                ),
            )
            RelayError.SURFACE_TRUSTED_DENIED -> return HandlerResult.Done(
                rejected(
                    request.id,
                    verdict,
                    "this system has refused the trusted-display create for this app; switch the " +
                        "involved operations to foreground mode instead of retrying surface.virtual",
                    descriptor,
                    elapsed(),
                    surface = decision.surface,
                    degradedFrom = decision.degradedFrom,
                ),
            )
            else -> Unit
        }
        // 「单面能力被偏好链卡死」在进闸门前打回（[decision.requiresMode]）。
        // `surface.virtual` 的 create/release/query 是**管理动作**——用户在
        // 前台偏好下被回「切后台再重试」是绕圈：显式 create/release 是 unsafe 与残留屏
        // 的解除通路，恰恰要在前台偏好下可达。判据收在纯函数 [modeRequiredFor]
        // （与 RelayContainer.capabilityRow 共用同一张表）；DENIED 档、系统拒绝的既有
        // 优先级不变（earlyVerdict 在这一问之前分派）。
        modeRequiredFor(descriptor.id, decision.requiresMode)?.let { mode ->
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.SURFACE_MODE_REQUIRED,
                    "capability ${descriptor.id.wire} only runs on the ${mode.wire} execution mode; " +
                        "switch the surface preference or bring that display up, then retry",
                    descriptor,
                    elapsed(),
                    surface = decision.surface,
                    degradedFrom = decision.degradedFrom,
                ),
            )
        }
        // 建屏档位「询问审批」下的后台启动：这次调用**不建屏、不弹卡**，直接回一条
        // 结构化错误，让助手先显式调 `surface.virtual {"action":"create"}`，建好屏再
        // 重试这条启动。
        //
        // 为什么不在这一次调用里等那张建屏卡：等卡要占着这次调用的执行槽，助手拿不到
        // 回包只能干等；而 app.launch 自己还要再问一次——同一次调用里挤两张卡，
        // 用户先点哪张都会让另一张的答复落空。改成显式两步之后，建屏那张卡走既有的
        // 挂起审批（不占执行槽），启动那次重试再问它自己那一张。
        //
        // 判据八条全真才走这一支：启动类能力、非「带回宿主」、用户要的是「优先后台」、
        // 此刻没有可信屏，且建屏档位正是「询问审批」，**并且那块屏此刻建得出来**
        // （[SurfacePolicy.trustedSurfaceReachable]：系统版本与 shell 通路够，且这台
        // 机器没拒绝过建屏），且路由层接上了。
        //
        // 后三条是这一支的**前提**，缺一条就不该提「先建屏再重试」：
        // - 建不出来时（Android 10–12、Shizuku 未授权/掉线、ROM 拒绝过建屏）那条建议
        // 指向一个用户此刻做不到的动作，助手只会白弹一张建不出来的屏的审批卡；此时
        // 落回 [SurfacePolicy] 的前台降级，回包带准确原因（E_SURFACE_API_LEVEL /
        // E_SURFACE_NO_SHELL），与「后台跑不了就退前台并报 degraded」的承诺一致。
        // - 路由层没接时没有「建屏→重试」这套回程，按构造器 KDoc「未接路由层 = 行为
        // 不变」照旧走执行偏好裁决。
        // 屏已存在时后台路由照旧，建屏档位是「完全访问」时由既有备屏路径自动建。
        if (descriptor.id == CapabilityId.APP_LAUNCH &&
            !bringToFront &&
            surfacePreference() == SurfacePreference.BACKGROUND_PREFERRED &&
            !trustedDisplayAlive() &&
            gatekeeper.tierOf(CapabilityId.SURFACE_VIRTUAL) == AccessTier.ASK &&
            // 此刻那块屏建不建得出来：建不出来就别叫助手去建（那张卡弹了也不会成）。
            policy.trustedSurfaceReachable() &&
            // 这台机器拒绝过建屏：再建议建屏是让助手撞同一堵墙。
            !trustedDisplayDenied() &&
            // 未接路由层 = 行为不变：没有落点层就没有「建屏→重试」这套回程。
            landingTable != null
        ) {
            runLog.warn(
                LogSubsystem.GATE,
                LogEvent.SURFACE_PREPARE_SKIPPED,
                "cap" to descriptor.id.wire,
                "cause" to "tier_ask",
            )
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.SURFACE_UNAVAILABLE,
                    "the trusted display is not created; call surface.virtual " +
                        "{\"action\":\"create\"} first (it may ask for approval), then retry this " +
                        "launch — the background intent is kept",
                    descriptor,
                    elapsed(),
                    // 本该后台、屏未就绪：回包按既有 rejected 形状说清这一层。
                    surface = SurfaceKind.FOREGROUND,
                    degradedFrom = SurfaceKind.BACKEND_TRUSTED,
                ),
            )
        }
        // 「谁都没写这条实现」要在问用户之前就说：确认框弹一次要占掉用户六十秒，
        // 而答案随后是 E_NOT_IMPLEMENTED —— 用户替一次根本不会执行的调用背了书。
        // 后端在但此刻连不上（无障碍没开、Shizuku 掉线）不走这一支，那是要不要重试的问题。
        if (!dispatcher.isImplemented(descriptor)) {
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.CAPABILITY_NOT_IMPLEMENTED,
                    descriptor.id.wire,
                    descriptor,
                    elapsed(),
                    surface = decision.surface,
                    degradedFrom = decision.degradedFrom,
                ),
            )
        }
        // 系统权限快照按**本次裁决出的路线**取：先定面、再探测，快照与真实执行才同口径
        // （坐标四条走可信屏就不需要无障碍，截屏落前台就要确认框）。
        val system = probe.probe(descriptor, decision.surface)
        val argsIdentity = auditLog.argsDigestOf(request.args.toString())
        // 同一问题已有一条挂起在等答复时，本条不再进审批队列：队列按「同一件事」复用
        // 同一张框，第二个等待者会拿到同一份答复，等于把一次批准花在两条请求上。
        // 这里如实回「还在等用户」，让重试在原请求落定之后再来。
        if (parkBridge != null && isSameQuestionParked(descriptor.id, argsIdentity)) {
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.GATE_AWAITING_CONSENT,
                    "an identical request is already waiting for the user's approval; its outcome " +
                        "covers this one, ask again after it settles",
                    descriptor,
                    elapsed(),
                ),
            )
        }
        // 目标钉不钉得住同一颗控件（nodeId、或只给 package+class+index 的选择器）
        // 由呈现层同一处规则判，缓冲与重试接框都照它。
        val volatileTarget = InterlockTargets.isVolatileTarget(descriptor, request.args)
        val gateStartedAt = now()
        val gate = gatekeeper.authorize(
            descriptor = descriptor,
            targetLabel = targetLabel,
            paramDetail = paramDetail,
            // 「是不是同一件事」按整份参数判，不按屏上那两行截过的文字判：摘要与授权记录
            // 同源，事后能从记录里对回是同一次调用。
            argsIdentity = argsIdentity,
            volatileTarget = volatileTarget,
            // 确认框只花得起这笔预算的一部分：剩下的要留给回写响应，
            // 否则「用户刚点完允许」正好撞上「宿主放弃这条请求」。
            budgetMs = budgetMs - APPROVAL_REPLY_MARGIN_MS,
            system = system,
            // 收尾回宿主这一形状不走审批：它最常用的时刻正是用户看不到确认框的时刻。
            hostBringToFront = bringToFront,
        )
        // 等人点的那一段不该占着唯一执行窗口：答复会在任意时刻到来，而信箱后面还有无关
        // 请求在排。挂起面可用、预算装得下整段审批窗时，把等待移出执行槽——上面这轮
        // authorize 已经把框挂上，剩下的等待由等待环在通道之外
        // 推进，答复到达后由续行体回到串行上下文走完后半程。
        if (!gate.allowed &&
            gate.error == RelayError.GATE_AWAITING_CONSENT &&
            canPark(budgetMs, gateStartedAt)
        ) {
            // 进挂起面之前先记一行「这一问开始等人」：挂起之后日志里就只剩等待环的
            // 退避与续行了，这一行是唯一能把「框已挂上」与后续等待对上的锚点。
            logWaitingUser(request.id)
            return parkForApproval(
                request = request,
                descriptor = descriptor,
                targetLabel = targetLabel,
                paramDetail = paramDetail,
                argsIdentity = argsIdentity,
                volatileTarget = volatileTarget,
                system = system,
                decision = decision,
                followedTarget = followedTarget,
                bringToFront = bringToFront,
                budgetMs = budgetMs,
                startedAt = startedAt,
                gateStartedAt = gateStartedAt,
                gate = gate,
                namedTargetPackage = namedTargetPackage,
            )
        }
        // 没走挂起面的那一支同样要记：内联窗口到期后这一问还在等用户，而这一次调用
        // 已经把「还在等」原样回给助手了。少了这一行，日志上就是一次凭空出现的等待。
        if (!gate.allowed && gate.error == RelayError.GATE_AWAITING_CONSENT) logWaitingUser(request.id)
        return HandlerResult.Done(
            finishAfterGate(
                request = request,
                descriptor = descriptor,
                targetLabel = targetLabel,
                plannedGate = gate,
                plannedDecision = decision,
                followedTarget = followedTarget,
                bringToFront = bringToFront,
                budgetMs = budgetMs,
                startedAt = startedAt,
            ),
        )
    }

    /**
     * 闸门之后的共用后半程：执行限流、派发守卫、后端分发与响应成稿。
     * 内联路径与挂起续行走的是同一段代码——续行只是把「等用户」从中间摘走了，
     * 裁决之后的每一步判定、记账与回包形状都不容有两份。
     */
    private suspend fun finishAfterGate(
        request: RelayRequest,
        descriptor: CapabilityDescriptor,
        targetLabel: String?,
        plannedGate: GateResult,
        plannedDecision: SurfaceDecision,
        followedTarget: String?,
        bringToFront: Boolean,
        budgetMs: Long,
        startedAt: Long,
    ): RelayResponse {
        // 挂起续行、备屏都可能改写执行面与系统条件：裁决与闸门快照都是可变的，
        // 入参只是当时的快照。
        var decision = plannedDecision
        var gate = plannedGate
        if (!gate.allowed) {
            val error = gate.error ?: RelayError.GATE_HOST_DENIED
            val deniedElapsed = now() - startedAt
            // 「谁也问不到人」最容易变成死循环：助手只看到 E_GATE_NO_FOREGROUND，
            // 而它既不说明通知被关、也不说明自己正在后台模式里跑。错误码不动（那是事实），
            // 会话内的 reason 补一句点名到该做哪一步 —— 两档都成立时两档都写。
            val reason = buildString {
                append(error.code).append('/').append(gate.decisionLabel)
                if (error == RelayError.GATE_NO_FOREGROUND) {
                    approvalDeadEndHint()?.let { append("; ").append(it) }
                }
            }
            record(
                requestId = request.id,
                descriptor = descriptor,
                system = gate.systemState,
                tier = gate.tier,
                decision = gate.decisionLabel,
                outcome = "denied",
                surface = null,
                degradedFrom = null,
                bytes = 0L,
                error = error,
                elapsedMs = deniedElapsed,
                // 被拒的调用不留参数原文，但对象必须留：用户要能事后看出
                // 自己刚刚拒掉的是「对谁做的」。
                target = targetLabel,
                canonicalArgs = null,
            )
            logApprovalVerdict(request.id, gate)
            return rejected(
                request.id,
                error,
                reason,
                descriptor,
                deniedElapsed,
                logRejection = false,
                surface = decision.surface,
                degradedFrom = decision.degradedFrom,
            )
        }
        logApprovalVerdict(request.id, gate)
        // 硬边界：批准不等于放行。审批期间（内联等待或挂起续行都一样）用户可能刚把
        // 目标包加进受保护名单——批准回填与真正派发之间必须再查一次名单。内联路径与
        // 挂起续行共用本函数，这一处就是两条通路的共同关口。
        val preDispatchProtected = protectedTarget(descriptor.id, request.args)
            ?.takeIf { protectedApps?.blocks(it) == true }
        if (preDispatchProtected != null) {
            logProtectedBlock(descriptor.id, preDispatchProtected, BLOCKED_AT_PRE_DISPATCH)
            return rejected(
                request.id,
                RelayError.PACKAGE_PROTECTED,
                "package $preDispatchProtected was added to the user's protected-apps list while " +
                    "this call was pending: the approval no longer carries it and nothing was " +
                    "dispatched",
                descriptor,
                now() - startedAt,
                logRejection = false,
            )
        }
        // 派发前重新校验落点：等人的那段时间里屏可能被收掉、目标可能被停、偏好可能被改。
        // 旧裁决不硬发 —— 落点没了就以结构化错误收场，重试会重新路由，绝不静默改道前台。
        if (followedTarget != null && landingTable != null) {
            val stillThere = landingTable.get(followedTarget)
            if (stillThere == null ||
                (decision.surface == SurfaceKind.BACKEND_TRUSTED && !trustedDisplayAlive())
            ) {
                return rejected(
                    request.id,
                    RelayError.SURFACE_UNAVAILABLE,
                    "the recorded landing for this target was invalidated while the call was pending " +
                        "(display reclaimed, target stopped, or the preference changed): launch the " +
                        "target again or recreate the display, then retry",
                    descriptor,
                    now() - startedAt,
                    logRejection = false,
                    surface = decision.surface,
                    degradedFrom = decision.degradedFrom,
                )
            }
        }
        // 挂起续行的场景可能已经变了（屏被收、偏好被改、目标被停）：派发前按当前事实
        // 重裁 app.launch 的执行面，不拿旧裁决硬发。屏被收是暂时性失败 —— 保持后台意图
        // 回可复查错误，不静默改前台；偏好被用户改了则跟随当前选择（裁决层就是偏好链）。
        if (descriptor.id == CapabilityId.APP_LAUNCH && !bringToFront && landingTable != null) {
            if (decision.surface == SurfaceKind.BACKEND_TRUSTED && !trustedDisplayAlive()) {
                return rejected(
                    request.id,
                    RelayError.BACKEND_UNAVAILABLE,
                    "the trusted display was reclaimed while the call was pending: the background " +
                        "intent is kept - retry rather than switching to foreground",
                    descriptor,
                    now() - startedAt,
                    logRejection = false,
                    surface = decision.surface,
                    degradedFrom = decision.degradedFrom,
                )
            }
            val refreshedDecision = policy.decide(descriptor)
            if (refreshedDecision.surface != decision.surface) decision = refreshedDecision
        }
        // 执行限流在授权之后、派发之前：只对真正执行的调用记账。走审批通路的调用
        // 不占执行配额——它的频率已在闸门里按「同一件事」合并计入，这里再记一次
        // 等于同一次调用记两笔；没人答的确认框也就吃不到执行额度。
        if (!gate.requiresApproval && !execRate.accepted(descriptor.id)) {
            val limitedElapsed = now() - startedAt
            record(
                requestId = request.id,
                descriptor = descriptor,
                system = gate.systemState,
                tier = gate.tier,
                decision = "rate_limited",
                outcome = "denied",
                surface = null,
                degradedFrom = null,
                bytes = 0L,
                error = RelayError.RATE_LIMITED,
                elapsedMs = limitedElapsed,
                target = targetLabel,
                canonicalArgs = null,
            )
            return rejected(
                request.id,
                RelayError.RATE_LIMITED,
                RelayError.RATE_LIMITED.code + "/execution_rate",
                descriptor,
                limitedElapsed,
                logRejection = false,
                surface = decision.surface,
                degradedFrom = decision.degradedFrom,
            )
        }

        // 派发前的取消复查：内联与挂起-续行共用本函数，故只需查这一处。等待期间用户
        // 撤回了这条请求（控制通道 cancel）时，此刻可证未派发、没有任何外部动作，
        // 按「已取消、未执行」收场并把状态记成 CANCELLED——判定与收场和挂起-续行体
        // 的同一检查共用 [cancelledGate]。
        if (isCancelRequested?.invoke(request.id) == true) {
            return finishAfterGate(
                request = request,
                descriptor = descriptor,
                targetLabel = targetLabel,
                plannedGate = cancelledGate(gate),
                plannedDecision = decision,
                followedTarget = followedTarget,
                bringToFront = bringToFront,
                budgetMs = budgetMs,
                startedAt = startedAt,
            )
        }

        // 持久提交边界：任何可能产生外部影响的动作提交之前，先把「已派发」标记钉进持久
        // 介质并确认落盘成功；钉失败即拒绝执行，否则崩溃恢复将无法判断副作用是否已发生，
        // 只能保守地把这条请求记成「结果未知」。v1 请求没有状态记录，守卫恒真。
        if (dispatchGuard != null && !dispatchGuard.invoke(request.id)) {
            return respond(
                request = request,
                descriptor = descriptor,
                target = targetLabel,
                gate = gate,
                decision = decision,
                outcome = "failed",
                data = JSONObject(),
                artifacts = emptyList(),
                bytes = 0L,
                // 守卫只答布尔，两种拒绝在这里说全：「无可执行状态」（记录已终态或已
                // 不存在，重试同一条没有意义）与「边界标记没能落盘」（宿主侧持久化
                // 故障）。具体是哪一种由状态存储按请求号在运行日志里点名。
                detail = "dispatch guard refused this action: the request state is no longer " +
                    "executable (already settled or untracked) or the pre-execution marker " +
                    "was not persisted; submit a fresh request id",
                degradeReason = decision.reason,
                error = RelayError.INTERNAL,
                startedAt = startedAt,
            )
        }
        // 守卫通过即「这一条已经武装到可以派发」。这一行是审批链路的收口：日志里
        // REQUEST_WAITING_USER → APPROVAL_PRESENTER → REQUEST_APPROVAL_DECIDED → 这一行
        // 连起来，就是一次审批的完整来回；断在这里说明是守卫把动作拦下了。
        runLog.info(LogSubsystem.GATE, LogEvent.REQUEST_DISPATCH_ARMED, "id" to request.id)

        // 路由层的建屏准备：闸门放行、守卫边界落盘之后才动 —— 调用还被用户挡着时先建
        // 一面屏，等于替一次可能不发生的启动花了系统资源。走真实闸门与派发（探测、限流、
        // 运行日志与普通调用同一套），并按当前偏好重算 —— 挂起续行的场景里偏好可能已变。
        // 派发前置守卫按请求粒度在上方落盘，本请求的全部派发（含备屏）都在它的覆盖之下。
        // **自动建屏只走「完全访问」一档**：那一档不需要用户点头，问一次就建。
        // 「询问审批」与「禁止」两档都不在这里建屏——前者要占着执行槽等一张卡（助手拿不到
        // 回包，而 app.launch 自己还要再问一次，两张卡挤在同一次调用里走不下去），后者是
        // 用户明说不许建。两档都不静默：各留一条点名原因的日志，降级原因随回包。
        if (landingTable != null && !bringToFront &&
            descriptor.id == CapabilityId.APP_LAUNCH &&
            SurfaceKind.BACKEND_TRUSTED in descriptor.surfaces &&
            surfacePreference() == SurfacePreference.BACKGROUND_PREFERRED &&
            // 复核：unsafe 只封**自动**建屏这条路由——设备被自检判过"建屏破坏主屏"时，
            // 替用户自动再建一面就是把同一破坏再演一遍。显式的 surface.virtual 不受此限
            // （它是复检与解除标记的通路）。探测侧的 trustedDisplayAvailable 同样把 unsafe
            // 算进去，两处口径一致。
            !trustedDisplayUnsafe() &&
            decision.surface == SurfaceKind.FOREGROUND
        ) {
            when (gatekeeper.tierOf(CapabilityId.SURFACE_VIRTUAL)) {
                AccessTier.DENIED -> {
                    // 建屏拨在「禁止」：不建屏、也不弹卡。但「优先后台」没备屏不能静默——
                    // 否则用户会把「自己拨了禁止」读成「设置没生效」。点名原因，启动照
                    // 偏好链回前台，并把降级如实写进回包（本该后台、实际前台）。
                    runLog.warn(
                        LogSubsystem.GATE,
                        LogEvent.SURFACE_PREPARE_SKIPPED,
                        "cap" to descriptor.id.wire,
                        "cause" to "tier_denied",
                    )
                    decision = SurfaceDecision(
                        SurfaceKind.FOREGROUND,
                        degradedFrom = SurfaceKind.BACKEND_TRUSTED,
                        // 降级原因是档位不许建屏，不是「屏此刻够不着」：那一句由日志的
                        // tier_denied 与这一条码一起说清。
                        //
                        // **插件侧的文案可能指向错能力（只记录，不改消费端）**：这一条码
                        // 的通用文案是「你未允许助手使用该能力」，挂在 app.launch 这一行
                        // 上读起来像「app.launch 被禁」，而真正被禁的是 surface.virtual。
                        // 场景可判：这一支只在「优先后台 + 此刻没有可信屏 + 路由层已接」
                        // 时出现——「前台偏好」进不来（不进备屏块），「屏还在」也进不来
                        // （裁决会直接选后台面）。该分支沿用通用错误码，未单独细分。
                        reason = RelayError.GATE_HOST_DENIED.code,
                    )
                }
                AccessTier.ASK -> {
                    // 建屏拨在「询问审批」：这一档不在这里替用户点头，也不在这儿等第二张卡。
                    // 助手要建屏就自己显式调 surface.virtual（那一条自己会弹一张审批卡，
                    // 走既有挂起审批，不占执行槽），建好屏再重试这条启动。
                    // 走到这里说明入口那道 ASK 拒绝没生效：偏好是在审批期间变的，或那块屏
                    // 此刻根本建不出来（SDK 不够、shell 不在、这台机器拒绝过建屏——那一支
                    // 入口处已经不给「先建屏」的提示了）。照旧降级回前台并留一条点名原因的
                    // 日志，不静默。
                    runLog.warn(
                        LogSubsystem.GATE,
                        LogEvent.SURFACE_PREPARE_SKIPPED,
                        "cap" to descriptor.id.wire,
                        "cause" to "tier_ask",
                    )
                    decision = SurfaceDecision(
                        SurfaceKind.FOREGROUND,
                        degradedFrom = SurfaceKind.BACKEND_TRUSTED,
                        // 降级原因取更具体的那条：裁决层已经给出「屏建不出来」的准确理由
                        // （E_SURFACE_API_LEVEL / E_SURFACE_NO_SHELL）时，不把它抹成一句
                        // 泛化的「屏不可用」——用户要能分清「这台机器太旧」与「Shizuku 没开」。
                        reason = decision.reason ?: RelayError.SURFACE_UNAVAILABLE.code,
                    )
                }
                AccessTier.ALWAYS -> if (!trustedDisplayAlive()) {
                    when (val prepared = prepareBackgroundSurface(request, budgetMs, startedAt)) {
                        PreparedSurface.Ready -> {
                            decision = policy.decide(descriptor)
                            // 备屏把执行面换了：系统权限快照按新路线重取，闸门快照同源替换 ——
                            // 后续的授权记录与回包写的是新路线上的事实，不是备屏前的旧读数。
                            val refreshed = probe.probe(descriptor, decision.surface)
                            if (InterlockGate.systemBlocks(refreshed.state)) {
                                return rejected(
                                    request.id,
                                    RelayError.GATE_SYSTEM_MISSING,
                                    "the background surface is ready but this call's system conditions do " +
                                        "not hold on it: resolve the missing condition, then retry",
                                    descriptor,
                                    now() - startedAt,
                                    logRejection = false,
                                    surface = decision.surface,
                                    degradedFrom = decision.degradedFrom,
                                )
                            }
                            gate = gate.copy(systemState = refreshed.state)
                            onSurfaceChanged()
                        }
                        is PreparedSurface.Degraded -> decision = SurfaceDecision(
                            SurfaceKind.FOREGROUND,
                            degradedFrom = SurfaceKind.BACKEND_TRUSTED,
                            reason = prepared.code,
                        )
                        PreparedSurface.Transient -> {
                            // 备屏暂时送不到：留一条运行日志点名因果，再以可复查错误收场。
                            runLog.warn(
                                LogSubsystem.GATE,
                                LogEvent.CALL_FAILED,
                                "id" to request.id,
                                "type" to RelayError.BACKEND_UNAVAILABLE.code,
                                "cause" to "surface-virtual prepare failed transiently",
                            )
                            return rejected(
                                request.id,
                                RelayError.BACKEND_UNAVAILABLE,
                                "the background surface could not be prepared just now (the shell side is " +
                                    "temporarily unreachable): the background intent is kept - retry rather " +
                                    "than switching to foreground",
                                descriptor,
                                now() - startedAt,
                                logRejection = false,
                                surface = decision.surface,
                                degradedFrom = decision.degradedFrom,
                            )
                        }
                    }
                }
            }
        }

        val dispatched = dispatcher.dispatch(
            descriptor = descriptor,
            decision = decision,
            call = BackendCall(
                requestId = request.id,
                descriptor = descriptor,
                args = request.args,
                surface = decision.surface,
                paths = paths,
                // 到这里为止已经花掉的（读参数、问闸门、等确认框——挂起续行时还包括
                // 整段等人时间）都要从预算里扣掉：交给后端的必须是"此刻还剩多少"，
                // 否则会等的能力（ui.waitFor）会按一条早就过期的预算继续等，
                // 最后由信箱判成通道超时。
                budgetMs = budgetMs - (now() - startedAt) - APPROVAL_REPLY_MARGIN_MS,
            ),
        )
        // 那块屏的有无直接改写其余五条后台能力的可用判据。凡是动过屏的调用都要问一次能力清单：
        // 助手刚开出屏，回头读到的若还是「ui.tap usable=false」，它会照着说明里那句
        // 「只有 usable=true 能调」收工走人——门开了却没人知道。内容没变时这一问是免费的，
        // 见 [onSurfaceChanged] 那一侧的「变了才重铺」。
        if (descriptor.id == CapabilityId.SURFACE_VIRTUAL) onSurfaceChanged()
        // 目标落点层的落账与降级处置只对 `app.launch` 生效：它是落点的建立点。
        // 未核验的 Ok 不是成功：这一条与路由层无关，只要是一次 app.launch 就成立。
        val launchTarget = request.args.optString("package").takeIf { it.isNotEmpty() }
            ?.takeIf { descriptor.id == CapabilityId.APP_LAUNCH && !bringToFront }
        val settling = launchTarget?.let { target ->
            when (val result = dispatched.result) {
                is BackendResult.Ok -> settleLaunchResult(
                    dispatched.decision.surface, dispatched.decision.degradedFrom, null, result.data,
                    target, now(), TargetLandingTable.DEFAULT_LANDING_TTL_MS, surfacePreference(),
                )
                is BackendResult.Failed -> settleLaunchResult(
                    dispatched.decision.surface, dispatched.decision.degradedFrom, result.error, result.data,
                    target, now(), TargetLandingTable.DEFAULT_LANDING_TTL_MS, surfacePreference(),
                    dispatched = result.dispatched,
                )
            }
        }
        // 只有带实际证据的落点才落账（settle 已按 verified/grabbed 过滤，这里照单收）。
        settling?.record?.let { landingTable?.record(it) }
        when {
            // 目标被停止：它的落点随停失效，下一次调用重新裁决。
            descriptor.id == CapabilityId.APP_STOP && dispatched.result is BackendResult.Ok ->
                landingTable?.invalidate(request.args.optString("package"))

            // 助手成功读回了一次场景（快照 / 查询）："结果未知"的启动标记就此摘除 ——
            // 标记等的正是这一步核实，读到了就不该再挡同参重发。
            (descriptor.id == CapabilityId.UI_SNAPSHOT || descriptor.id == CapabilityId.PKG_QUERY) &&
                dispatched.result is BackendResult.Ok ->
                landingTable?.clearAllUnknownLaunches()

            // 后台屏被动过（建/收）：所有落点一并作废重算。
            descriptor.id == CapabilityId.SURFACE_VIRTUAL && dispatched.result is BackendResult.Ok ->
                landingTable?.clear()
        }

        return when (val result = dispatched.result) {
            is BackendResult.Ok -> if (settling?.syntheticError != null) {
                // 命令被接受但落点未确认：不是成功。成因可查（observed 在）按确定性回，
                // 不挡重发；观察缺席才是结果未知，记幂等标记 —— 核实（pkg.query /
                // ui.snapshot 成功）即摘，标记另有寿命。
                if (settling.failureClass == LaunchFailureClass.UNKNOWN) {
                    landingTable?.rememberUnknownLaunch(
                        auditLog.argsDigestOf(request.args.toString()),
                        launchTarget ?: "",
                    )
                }
                respond(
                    request = request,
                    descriptor = descriptor,
                    target = targetLabel,
                    gate = gate,
                    decision = dispatched.decision,
                    outcome = "failed",
                    data = result.data,
                    artifacts = emptyList(),
                    bytes = 0L,
                    detail = result.reason ?: "accepted but the landing was not confirmed within " +
                        "this call's budget: re-check with pkg.query or ui.snapshot before " +
                        "launching the same target again",
                    degradeReason = dispatched.decision.reason,
                    error = settling.syntheticError,
                    startedAt = startedAt,
                )
            } else {
                respond(
                    request = request,
                    descriptor = descriptor,
                    target = targetLabel,
                    gate = gate,
                    decision = dispatched.decision,
                    outcome = "success",
                    data = result.data,
                    artifacts = result.artifacts,
                    bytes = result.artifactBytes,
                    // 降级说明与后端诊断各占一个键：让前者顶掉后者，会在"优先后台"落到前台时
                    // 把唯一那条场景信息（此刻哪个包停在最前面）丢掉，调用方只剩一个裸错误码，
                    // 处置建议也就无从给出。
                    detail = result.reason,
                    degradeReason = dispatched.decision.reason,
                    error = null,
                    startedAt = startedAt,
                )
            }

            is BackendResult.Failed -> when {
                // 后台启动把 App 直接起在了用户真屏：目标已经在眼前，不再重发一次前台启动，
                // 按证据把落点记成前台并如实回成功 —— 回包里保留"起在了真屏"的事实。
                settling?.rewriteToOk == true -> respond(
                    request = request,
                    descriptor = descriptor,
                    target = targetLabel,
                    gate = gate,
                    decision = SurfaceDecision(
                        SurfaceKind.FOREGROUND,
                        degradedFrom = dispatched.decision.surface.takeIf { it != SurfaceKind.FOREGROUND }
                            ?: dispatched.decision.degradedFrom,
                        reason = result.error.code,
                    ),
                    outcome = "success",
                    data = result.data,
                    artifacts = emptyList(),
                    bytes = 0L,
                    detail = result.reason,
                    degradeReason = result.error.code,
                    error = null,
                    startedAt = startedAt,
                )

                // 确定性失败才允许这一次目标级前台兜底；兜底结果就是终局，不再二次兜底。
                // 兜底与主派发同账同守卫：执行限流在此再记一笔（限不住就原样回后台失败，
                // 不绕账），派发前置守卫已在上方按请求落盘并覆盖本请求的全部派发。
                settling?.fallbackToForeground == true -> {
                    if (!execRate.accepted(descriptor.id)) {
                        respond(
                            request = request,
                            descriptor = descriptor,
                            target = targetLabel,
                            gate = gate,
                            decision = dispatched.decision,
                            outcome = "failed",
                            data = result.data,
                            artifacts = emptyList(),
                            bytes = 0L,
                            detail = result.reason,
                            degradeReason = dispatched.decision.reason,
                            error = result.error,
                            startedAt = startedAt,
                            backendConsentKind = result.consentKind,
                            backendDispatched = result.dispatched,
                        )
                    } else {
                    val fallbackDecision = SurfaceDecision(
                        SurfaceKind.FOREGROUND,
                        degradedFrom = dispatched.decision.surface.takeIf { it != SurfaceKind.FOREGROUND }
                            ?: dispatched.decision.degradedFrom,
                        reason = result.error.code,
                    )
                    val retried = dispatcher.dispatch(
                        descriptor = descriptor,
                        decision = fallbackDecision,
                        call = BackendCall(
                            requestId = request.id,
                            descriptor = descriptor,
                            args = request.args,
                            surface = fallbackDecision.surface,
                            paths = paths,
                            budgetMs = (budgetMs - (now() - startedAt)).coerceAtLeast(1_000L),
                        ),
                    )
                    // 兜底与主派发同一把尺子：核验过了才记前台落点，未核验不是成功
                    // （成因可查按确定性回，观察缺席按未知记幂等标记），都不再二次兜底。
                    val fallbackSettling = launchTarget?.let { target ->
                        when (val r = retried.result) {
                            is BackendResult.Ok -> settleLaunchResult(
                                fallbackDecision.surface, fallbackDecision.degradedFrom, null,
                                r.data, target, now(), TargetLandingTable.DEFAULT_LANDING_TTL_MS,
                                surfacePreference(),
                            )
                            is BackendResult.Failed -> settleLaunchResult(
                                fallbackDecision.surface, fallbackDecision.degradedFrom, r.error,
                                r.data, target, now(), TargetLandingTable.DEFAULT_LANDING_TTL_MS,
                                surfacePreference(), dispatched = r.dispatched,
                            )
                        }
                    }
                    fallbackSettling?.record?.let { landingTable?.record(it) }
                    when (val fallback = retried.result) {
                        is BackendResult.Ok -> if (fallbackSettling?.syntheticError != null) {
                            if (fallbackSettling.failureClass == LaunchFailureClass.UNKNOWN) {
                                landingTable?.rememberUnknownLaunch(
                                    auditLog.argsDigestOf(request.args.toString()),
                                    launchTarget ?: "",
                                )
                            }
                            respond(
                                request = request,
                                descriptor = descriptor,
                                target = targetLabel,
                                gate = gate,
                                decision = fallbackDecision,
                                outcome = "failed",
                                data = fallback.data,
                                artifacts = emptyList(),
                                bytes = 0L,
                                detail = fallback.reason ?: "accepted but the landing was not " +
                                    "confirmed within this call's budget: re-check with pkg.query " +
                                    "or ui.snapshot before launching the same target again",
                                degradeReason = fallbackDecision.reason,
                                error = fallbackSettling.syntheticError,
                                startedAt = startedAt,
                            )
                        } else {
                            respond(
                                request = request,
                                descriptor = descriptor,
                                target = targetLabel,
                                gate = gate,
                                decision = fallbackDecision,
                                outcome = "success",
                                data = fallback.data,
                                artifacts = fallback.artifacts,
                                bytes = fallback.artifactBytes,
                                detail = fallback.reason,
                                degradeReason = fallbackDecision.reason,
                                error = null,
                                startedAt = startedAt,
                            )
                        }

                        is BackendResult.Failed -> respond(
                            request = request,
                            descriptor = descriptor,
                            target = targetLabel,
                            gate = gate,
                            decision = fallbackDecision,
                            outcome = "failed",
                            data = fallback.data,
                            artifacts = emptyList(),
                            bytes = 0L,
                            detail = fallback.reason,
                            degradeReason = fallbackDecision.reason,
                            error = fallback.error,
                            startedAt = startedAt,
                            backendConsentKind = fallback.consentKind,
                            backendDispatched = fallback.dispatched,
                        )
                    }
                    }
                }

                // 结果未知：不自动重放，也不兜底。幂等标记按参数摘要挡住紧随其后的同参重发。
                settling?.failureClass == LaunchFailureClass.UNKNOWN -> {
                    landingTable?.rememberUnknownLaunch(
                        auditLog.argsDigestOf(request.args.toString()),
                        launchTarget ?: "",
                    )
                    respond(
                        request = request,
                        descriptor = descriptor,
                        target = targetLabel,
                        gate = gate,
                        decision = dispatched.decision,
                        outcome = "failed",
                        data = result.data,
                        artifacts = emptyList(),
                        bytes = 0L,
                        detail = result.reason,
                        degradeReason = dispatched.decision.reason,
                        error = result.error,
                        startedAt = startedAt,
                        backendConsentKind = result.consentKind,
                        backendDispatched = result.dispatched,
                    )
                }

                else -> respond(
                    request = request,
                    descriptor = descriptor,
                    target = targetLabel,
                    gate = gate,
                    decision = dispatched.decision,
                    outcome = "failed",
                    // 失败出口自带的结构化事实（如通知发送的字段口径）原样随回包：机器判读读字段。
                    data = result.data,
                    artifacts = emptyList(),
                    bytes = 0L,
                    detail = result.reason,
                    degradeReason = dispatched.decision.reason,
                    error = result.error,
                    startedAt = startedAt,
                    // 执行面在自己的失败出口标注的许可种类（如系统采集弹框）优先：
                    // 已经走到系统许可那一步，说明本模块的许可已经过了。
                    backendConsentKind = result.consentKind,
                    // 「外部动作有没有真的提交出去」由后端失败出口标注，副作用状态按它判定。
                    backendDispatched = result.dispatched,
                )
            }
        }
    }

    /**
     * 派发前的独立路由层：目标落点记录 → 沿用；没有记录 → 交回执行偏好裁决。
     * 建屏本身与 `app.launch`（落点的建立点）不按记录跟随。
     */
    private suspend fun routeCall(descriptor: CapabilityDescriptor, args: JSONObject): TargetRoute {
        val table = landingTable ?: return TargetRoute.None
        // 建屏与启动是落点的建立点，不按记录跟随；全屏采集/录制按屏级事实与执行偏好
        // 处理，不被"最近一次启动的目标"带走 —— 它们的落点是"这块屏"，不是某个 App。
        if (descriptor.id == CapabilityId.SURFACE_VIRTUAL ||
            descriptor.id == CapabilityId.APP_LAUNCH ||
            descriptor.id == CapabilityId.SCREEN_CAPTURE ||
            descriptor.id == CapabilityId.SCREEN_RECORD
        ) {
            return TargetRoute.None
        }
        if (SurfaceKind.BACKEND_TRUSTED !in descriptor.surfaces) return TargetRoute.None
        val explicitPackage = args.optString("package").takeIf { it.isNotEmpty() }
            ?: args.optJSONObject("selector")?.optString("package")?.takeIf { it.isNotEmpty() }
        val target = explicitPackage
            ?: table.lastTarget()
            ?: return TargetRoute.None
        val record = table.get(target) ?: return TargetRoute.None
        // 沿用前核对目标仍是记录那块面上的当前顶层：用户手动切走后旧落点不作数。
        val topNow = topPackageOn(
            if (record.surface == SurfaceKind.BACKEND_TRUSTED) record.displayId else 0,
        )
        return routeForTarget(
            descriptor.surfaces, target, record, trustedDisplayAlive(), topNow,
            explicitPackage != null, now(), surfacePreference(),
        )
    }

    /** 建屏准备的三种结局。 */
    private sealed interface PreparedSurface {
        /** 屏已建出：按执行偏好重裁，应得后台面。 */
        data object Ready : PreparedSurface

        /** 建不了是重试也改不了的结论：按 degraded 原因回退前台。 */
        data class Degraded(val code: String) : PreparedSurface

        /** 暂时性失败：保持后台意图，返回可复查错误。 */
        data object Transient : PreparedSurface
    }

    /**
     * 按现有建屏逻辑准备后台面。真实边界：走真实闸门与派发（探测、运行日志与普通调用
     * 同一套），建屏派发单独过 [execRate] 的 `surface.virtual` 配额、参数过同一张预校验表；
     * 派发前置守卫按**请求**粒度在调用方落盘，本请求的全部派发都在它覆盖之下，备屏
     * 不再单独落一遍。调用方已把 `surface.virtual` 档位钉在「完全访问」才到这里。
     *
     * 档位是「完全访问」意味着这一问不需要用户点头：[InterlockGate.authorize] 直接放行，
     * 这里一次就问完。**不在这里等用户**：询问审批档的建屏不在这条调用里发生（见
     * [handle] 里 ASK 档的入口拒绝）——那要占着执行槽等一张卡，助手拿不到回包，
     * 而建屏卡之外 app.launch 自己还要再问一次，两张卡挤在同一次调用里谁也走不下去。
     */
    private suspend fun prepareBackgroundSurface(
        request: RelayRequest,
        budgetMs: Long,
        startedAt: Long,
    ): PreparedSurface {
        val virtual = CapabilityRegistry.find(CapabilityId.SURFACE_VIRTUAL)
            ?: return PreparedSurface.Degraded(RelayError.SURFACE_UNAVAILABLE.code)
        // 建屏参数与普通调用同一张预校验表：合成参数写错在这里被打回，而不是进了后端。
        val createArgs = JSONObject().put("action", "create")
        CapabilityArgsSpec.validate(virtual.id, createArgs)?.let {
            return PreparedSurface.Degraded(RelayError.TRANSPORT_MALFORMED.code)
        }
        if (!execRate.accepted(CapabilityId.SURFACE_VIRTUAL)) {
            return PreparedSurface.Degraded(RelayError.RATE_LIMITED.code)
        }
        val remaining = (budgetMs - (now() - startedAt) - APPROVAL_REPLY_MARGIN_MS).coerceAtLeast(1_000L)
        val gate = gatekeeper.authorize(
            descriptor = virtual,
            targetLabel = null,
            paramDetail = null,
            argsIdentity = "surface-virtual-prepare",
            volatileTarget = false,
            budgetMs = remaining,
            system = probe.probe(virtual, SurfaceKind.BACKEND_TRUSTED),
        )
        if (!gate.allowed) {
            // 档位刚被用户改掉、或系统条件不再满足：都不是暂时性抖动，按降级回前台。
            return PreparedSurface.Degraded((gate.error ?: RelayError.GATE_HOST_DENIED).code)
        }
        val dispatched = dispatcher.dispatch(
            descriptor = virtual,
            decision = SurfaceDecision(SurfaceKind.BACKEND_TRUSTED),
            call = BackendCall(
                requestId = request.id,
                descriptor = virtual,
                args = createArgs,
                surface = SurfaceKind.BACKEND_TRUSTED,
                paths = paths,
                budgetMs = remaining,
            ),
        )
        return when (val result = dispatched.result) {
            is BackendResult.Ok -> PreparedSurface.Ready
            is BackendResult.Failed -> when (result.error) {
                // 断连、建屏请求暂时送不到、结局未知：保持后台意图，让调用方退避重试，
                // 而不是替它降级前台。
                RelayError.BACKEND_SHIZUKU_DEAD,
                RelayError.BACKEND_UNAVAILABLE,
                RelayError.TRANSPORT_TIMEOUT,
                RelayError.TRANSPORT_UNKNOWN,
                -> PreparedSurface.Transient

                // 建不了是重试也改不了的结论（无安全尺寸、ROM 拒绝、API 不够）：
                // 按降级原因回前台。
                else -> PreparedSurface.Degraded(result.error.code)
            }
        }
    }

    /**
     * 挂起是否划算且安全：挂起面已接上（通道在跑、登记簿有位），且预算装得下整段
     * 审批窗、走满之后还留得出执行与回写的余量。装不下的短预算不挂——内联等待的
     * 不挂起时会把「还在等」当场回给调用方，比挂满一整段窗再判超时诚实。
     */
    private fun canPark(budgetMs: Long, gateStartedAt: Long): Boolean {
        val bridge = parkBridge ?: return false
        if (!bridge.hasParkCapacity()) return false
        if (parkScope?.invoke() == null) return false
        val afterApproval = budgetMs - (now() - gateStartedAt) - approvalWindowMs(budgetMs) -
            APPROVAL_REPLY_MARGIN_MS
        return afterApproval >= MIN_DISPATCH_AFTER_APPROVAL_MS
    }

    /**
     * 审批窗与裁决层的窗口同口径：预算更短时以预算为准，更长时不越过确认框的缺省窗
     * （常量与 [interlock.relay.core.interlock.InterlockBroker] 的缺省窗一致，两处必须同步改）。
     */
    private fun approvalWindowMs(budgetMs: Long): Long =
        (budgetMs - APPROVAL_REPLY_MARGIN_MS).coerceAtMost(APPROVAL_WINDOW_CAP_MS)

    /** 「同一件事正在等」的键：与闸门审批频率合并表同一形状（能力 + 参数身份）。 */
    private fun parkKey(id: CapabilityId, argsIdentity: String): String = id.wire + ":" + argsIdentity

    private fun isSameQuestionParked(id: CapabilityId, argsIdentity: String): Boolean =
        parkedWaits.isSameQuestionParked(parkKey(id, argsIdentity))

    /**
     * 把「等用户答复」从执行槽里摘出去。
     *
     * 调用前提：第一轮 [InterlockGate.authorize] 已把确认框挂上并回「还没等到人」。
     * 这里登记挂起项、起一个在通道之外的等待环，然后以 [HandlerResult.Parked] 返回——
     * 通道据此释放执行槽去接后面的请求。答复（或审批到点）到达时，等待环把终局裁决
     * 交给续行体，由通道把续行体排回串行上下文执行。
     */
    private fun parkForApproval(
        request: RelayRequest,
        descriptor: CapabilityDescriptor,
        targetLabel: String?,
        paramDetail: String?,
        argsIdentity: String,
        volatileTarget: Boolean,
        system: ProbeResult,
        decision: SurfaceDecision,
        followedTarget: String?,
        bringToFront: Boolean,
        budgetMs: Long,
        startedAt: Long,
        gateStartedAt: Long,
        gate: GateResult,
        namedTargetPackage: String?,
    ): HandlerResult {
        val bridge = parkBridge
        val deadlineAtMs = gateStartedAt + approvalWindowMs(budgetMs)
        val key = parkKey(descriptor.id, argsIdentity)
        // 等待环与续行体之间只隔一个答复：环把终局裁决放进来，续行体在串行上下文里
        // 取走并执行。正常路径答复先于续行就位（await 立即返回）；到点兜底续行等不到
        // 等待环时按审批超时收场——绝不执行一个没有裁决结果的请求。
        val gateDeferred = CompletableDeferred<GateResult>()
        val park = ParkedRequest(
            requestId = request.id,
            resume = {
                // 执行前的取消检查：等待期间用户撤回了这条请求（控制通道 cancel）。
                // 已取消、未执行——不碰任何后端，直接以「已取消」落终局。
                val outcomeGate = if (isCancelRequested?.invoke(request.id) == true) {
                    cancelledGate(gate)
                } else {
                    withTimeoutOrNull(DECISION_HANDOFF_GRACE_MS) { gateDeferred.await() }
                        ?: approvalTimedOutGate(gate)
                }
                finishAfterGate(
                    request = request,
                    descriptor = descriptor,
                    targetLabel = targetLabel,
                    plannedGate = outcomeGate,
                    plannedDecision = decision,
                    followedTarget = followedTarget,
                    bringToFront = bringToFront,
                    budgetMs = budgetMs,
                    startedAt = startedAt,
                )
            },
            approvalDeadlineAtMs = deadlineAtMs,
            executionDeadlineAtMs = startedAt + budgetMs,
            approvalOwner = descriptor.id.wire,
        )
        // 「同一件事」的表位登记先行：先占表、再登记挂起项，顺序不能倒。表位已被一条
        // 未过期的挂起占着时这里直接回「还在等」——此刻既没起等待环、也没占登记簿名额，
        // 与入口的短路面是同一句话；若允许覆写表位，旧环收尾的条件摘除就摘不到自己的
        // 记录，第三个同问会乘虚而入，酿成双等待者。
        val waitEntry = parkedWaits.register(key, deadlineAtMs)
            ?: return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.GATE_AWAITING_CONSENT,
                    "an identical request is already waiting for the user's approval; its outcome " +
                        "covers this one, ask again after it settles",
                    descriptor,
                    now() - startedAt,
                ),
            )
        // 登记簿登记在后：失败（满员/重名）时这里没起环、没建等待，退回内联语义的
        // 「还在等」回包，并把刚占下的表位顺手摘掉——没有等待环会来收尾它。
        if (bridge == null || !bridge.register(park)) {
            parkedWaits.condRemove(key, waitEntry)
            return HandlerResult.Done(
                rejected(
                    request.id,
                    RelayError.GATE_AWAITING_CONSENT,
                    "the approval is on screen but the parked queue is full; ask again shortly",
                    descriptor,
                    now() - startedAt,
                ),
            )
        }
        val waitScope = parkScope?.invoke()
        if (waitScope == null) {
            // 取不到作用域（恰在停止）时不起等待环：没有人会来收尾表位，这里自己摘掉，
            // 别让一条死登记把同问的重试挡满整段审批窗。挂起项由通道的到点兜底推进，
            // 续行体等不到裁决按审批超时收场，挂起项不会悬死。
            parkedWaits.condRemove(key, waitEntry)
            return HandlerResult.Parked(park)
        }
        waitScope.launch {
            approvalWaitRounds(
                request = request,
                descriptor = descriptor,
                targetLabel = targetLabel,
                paramDetail = paramDetail,
                argsIdentity = argsIdentity,
                volatileTarget = volatileTarget,
                system = system,
                gate = gate,
                deadlineAtMs = deadlineAtMs,
                gateDeferred = gateDeferred,
                key = key,
                waitEntry = waitEntry,
                namedTargetPackage = namedTargetPackage,
            )
        }
        return HandlerResult.Parked(park)
    }

    /**
     * 挂起等待环：在执行槽之外反复走既有审批通路，直到拿到终局裁决。
     *
     * 每一轮就是一次普通的 [InterlockGate.authorize]：队列按「同一件事」复用第一轮挂上的
     * 那张框，审批频率按闸门自己的合并表记账（第二轮起并入第一笔），页内、悬浮与通知
     * 三条呈现通路照旧由队列驱动。等待环自己不执行任何动作——裁决交给续行体在串行
     * 上下文里消费，执行槽因此在整个等待期间都是空闲的。
     */
    @Suppress("LongParameterList")
    private suspend fun approvalWaitRounds(
        request: RelayRequest,
        descriptor: CapabilityDescriptor,
        targetLabel: String?,
        paramDetail: String?,
        argsIdentity: String,
        volatileTarget: Boolean,
        system: ProbeResult,
        gate: GateResult,
        deadlineAtMs: Long,
        gateDeferred: CompletableDeferred<GateResult>,
        key: String,
        waitEntry: ParkedWaitTable.Entry,
        namedTargetPackage: String?,
    ) {
        try {
            var outcome = gate
            while (true) {
                // 通道已停（宿主作用域随 stop 置空）：挂起登记与续行托管都已作废，
                // 等待环再问下去只会问出一个没有结局的答案，直接退场。
                if (parkScope?.invoke() == null) return
                val remaining = deadlineAtMs - now()
                // 到点无人答：与框自身的截止判定同源（第一轮的窗口就是按它配的），
                // 落成一次明确的审批超时，而不是无限续问把窗口越拖越长。
                if (remaining <= 0L) {
                    outcome = approvalTimedOutGate(gate)
                    break
                }
                // 等待期间被取消（控制通道 cancel）：终止等待，答复不会再有执行机会。
                if (isCancelRequested?.invoke(request.id) == true) {
                    outcome = cancelledGate(gate)
                    break
                }
                // 等待期间用户可能刚把目标包加进名单。每一轮先问一次名单，命中即按
                // 受保护拒绝收场——这一问不能再拿到执行机会，等在屏上的那张卡也就此作废
                // （卡片本体由队列按截止时刻收走；执行侧的硬边界在 finishAfterGate 的
                // 派发前复查再兜一道，两条通路都过不去）。
                if (namedTargetPackage != null && protectedApps?.blocks(namedTargetPackage) == true) {
                    outcome = protectedGate(gate)
                    break
                }
                // authorize 前再查一次作用域：stop 在别的线程上，可以落在轮首检查之后。
                // 通道已停就退场，不让这一轮把审批卡重新挂到一个不再接活的通道上。
                if (parkScope?.invoke() == null) return
                val round = gatekeeper.authorize(
                    descriptor = descriptor,
                    targetLabel = targetLabel,
                    paramDetail = paramDetail,
                    argsIdentity = argsIdentity,
                    volatileTarget = volatileTarget,
                    budgetMs = remaining,
                    system = system,
                )
                // 「还在等人」与「还没排上位置」都不是终局：框若还挂着，答复在任一轮的
                // 内联窗口里即时送达；框若被收走，迟到的那一下存进了缓冲，下一轮取走。
                if (round.allowed ||
                    (round.error != RelayError.GATE_AWAITING_CONSENT &&
                        round.error != RelayError.GATE_WAITING_TURN)
                ) {
                    outcome = round
                    break
                }
                delay(APPROVAL_ROUND_BACKOFF_MS)
            }
            gateDeferred.complete(outcome)
            parkBridge?.resumeParked(request.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            // 等待环自身故障不能让挂起项悬死：按审批超时给出终局，续行路径负责收尾。
            gateDeferred.complete(approvalTimedOutGate(gate))
            parkBridge?.resumeParked(request.id)
        } finally {
            // 条件摘除：只摘自己写入的那份记录。截止到点与环真正退出之间隔着最长一轮
            // 的退避，这段窗口里同问的重入已经写进了自己的登记——无条件删除会把别人的
            // 登记一并摘走，第三个同问随即能挂到同一张框上，一次批准被执行两遍。
            parkedWaits.condRemove(key, waitEntry)
        }
    }

    /** 挂起期间被取消：还没有任何外部动作，按「已取消、未执行」落终局。 */
    private fun cancelledGate(template: GateResult) = GateResult(
        allowed = false,
        requiresApproval = true,
        error = RelayError.GATE_CANCELLED,
        systemState = template.systemState,
        tier = template.tier,
        decisionLabel = "cancelled_before_execution",
    )

    /**
     * 挂起期间目标包被加进了受保护名单：等待环以这一档收场，续行体拿到的是拒绝，
     * 执行不会再发生。decisionLabel 与 [logApprovalVerdict] 的映射约定一致：
     * 不在已知清单里的标签按 "deny" 记判决。
     */
    private fun protectedGate(template: GateResult) = GateResult(
        allowed = false,
        requiresApproval = true,
        error = RelayError.PACKAGE_PROTECTED,
        systemState = template.systemState,
        tier = template.tier,
        decisionLabel = "package_protected",
    )

    /** 拒绝事件的一条运行日志：能力、目标包与拒绝层级，完整名单不进日志。 */
    private fun logProtectedBlock(capability: CapabilityId, targetPackage: String, where: String) {
        runLog.warn(
            LogSubsystem.GATE,
            LogEvent.PACKAGE_PROTECTED_BLOCKED,
            "cap" to capability.wire,
            "target" to targetPackage,
            "where" to where,
        )
    }

    /**
     * 这一问进入「等人答」时记一行。
     *
     * 只在这一问**第一次**没答上时记：等待环之后每 500ms 还会重问一次同一个框，
     * 每轮都记的话，六十秒的确认窗能刷出一百多行同义的话，日志按行读就废了。
     * 要看「这一问卡在哪、呈现在哪里」，看同一条请求的 APPROVAL_PRESENTER 那几行。
     */
    private fun logWaitingUser(requestId: String) {
        runLog.info(
            LogSubsystem.GATE,
            LogEvent.REQUEST_WAITING_USER,
            "id" to requestId,
            "kind" to WAITING_KIND_RELAY,
        )
    }

    /**
     * 审批判决落地时记一行。这是「谁答了什么」的最终事实，与授权记录里的标签同源。
     *
     * 还在等（`awaiting_consent` / `waiting_turn`）与「谁也问不到」（`no_surface`）都不算判决：
     * 前者还没有结论，后者压根没问到人，记进去会让事后把没被否的事读成被否。
     */
    private fun logApprovalVerdict(requestId: String, gate: GateResult) {
        if (!gate.requiresApproval) return
        val verdict = when (gate.decisionLabel) {
            "approved_once" -> "allow_once"
            "approved_session" -> "allow_session"
            "cancelled_before_execution" -> "cancelled"
            "no_surface", "awaiting_consent", "waiting_turn" -> null
            else -> when {
                gate.decisionLabel.startsWith("expired_") -> "timeout"
                gate.error == RelayError.GATE_CANCELLED -> "cancelled"
                gate.error == RelayError.GATE_APPROVAL_TIMEOUT -> "timeout"
                else -> "deny"
            }
        } ?: return
        runLog.info(
            LogSubsystem.GATE,
            LogEvent.REQUEST_APPROVAL_DECIDED,
            "id" to requestId,
            "verdict" to verdict,
        )
    }

    /** 「谁也问不到」时点名该做哪一步。由装配根提供，回包里只作提示。 */
    private fun approvalDeadEndHint(): String? = approvalDeadEnd()

    /** 审批窗到点仍无人答：与裁决层自己的超时同一结论、同一署名。 */
    private fun approvalTimedOutGate(template: GateResult) = GateResult(
        allowed = false,
        requiresApproval = true,
        error = RelayError.GATE_APPROVAL_TIMEOUT,
        systemState = template.systemState,
        tier = template.tier,
        decisionLabel = InterlockGate.approvalLabel(ApprovalOutcome.TimedOut, RelayError.GATE_APPROVAL_TIMEOUT),
    )

    @Suppress("LongParameterList")
    private fun respond(
        request: RelayRequest,
        descriptor: CapabilityDescriptor,
        target: String?,
        gate: GateResult,
        decision: SurfaceDecision,
        outcome: String,
        data: JSONObject,
        artifacts: List<String>,
        bytes: Long,
        detail: String?,
        degradeReason: String? = null,
        error: RelayError?,
        startedAt: Long,
        /** 执行面在自己的失败出口标注的许可种类；两处都可能给值时以它优先。 */
        backendConsentKind: String? = null,
        /** 后端失败出口标注的「外部动作是否已提交」；副作用状态按它判定，不猜 reason。 */
        backendDispatched: Boolean = false,
    ): RelayResponse {
        // 一次调用只取一次当前时刻：授权记录、运行日志与响应里的耗时必须同源，
        // 分别取会让同一件事出现三个对不上的数字。
        val elapsedMs = now() - startedAt
        record(
            requestId = request.id,
            descriptor = descriptor,
            system = gate.systemState,
            tier = gate.tier,
            decision = gate.decisionLabel,
            outcome = outcome,
            surface = decision.surface,
            degradedFrom = decision.degradedFrom,
            bytes = bytes,
            error = error,
            elapsedMs = elapsedMs,
            target = target,
            canonicalArgs = request.args.toString(),
        )
        return RelayResponse(
            id = request.id,
            ok = error == null,
            data = data,
            artifacts = artifacts,
            surface = decision.surface,
            degradedFrom = decision.degradedFrom,
            reason = detail,
            degradeReason = degradeReason,
            error = error,
            elapsedMs = elapsedMs,
            // 重放语义两键与回包同源：按能力查重放表、按结论查副作用状态。
            // `retryable` 只是错误码属性，不能据此自动重放非幂等动作；
            // 副作用状态以后端标注的「是否已提交」为准，不拿 reason 字符串猜。
            retryPolicy = retryPolicyOf(descriptor.id),
            effectState = effectStateFor(error == null, error, descriptor.id, backendDispatched),
            // 这次卡住的是哪一种许可：本模块的审批与系统的采集同意互不代替，
            // 执行面已经标注的（系统弹框那一步）优先于按错误码推出的归类。
            consentKind = backendConsentKind ?: consentKindFor(error),
        )
    }

    private fun rejected(
        id: String,
        error: RelayError,
        detail: String,
        descriptor: CapabilityDescriptor?,
        elapsedMs: Long,
        logRejection: Boolean = true,
        surface: SurfaceKind? = null,
        degradedFrom: SurfaceKind? = null,
    ): RelayResponse {
        // 走到这里还没探测系统状态，没有可信值可写进授权记录，因此只进运行日志；
        // 已记账的分支传 logRejection=false，避免同一件事出现两条口径不同的记录。
        if (logRejection) {
            runLog.warn(
                LogSubsystem.GATE,
                LogEvent.CALL_FAILED,
                "id" to id,
                "type" to (descriptor?.id?.wire ?: error.code),
            )
        }
        return RelayResponse(
            id = id,
            ok = false,
            data = JSONObject(),
            artifacts = emptyList(),
            // 失败也要说清这一趟落在哪块屏、是不是从后台面降下来的：助手判断"我这次究竟走了
            // 虚拟屏的自有帧源，还是走到了前台投影（于是会弹系统框）"凭的就是这两个字段。
            // 缺它们时同一件事在成功与失败两条路上长成两个样子，实测那条就是这样被读成
            // "离屏取图也会弹投屏框"。
            surface = surface,
            degradedFrom = degradedFrom,
            reason = detail,
            error = error,
            elapsedMs = elapsedMs,
            // 能力未知时没有可查表的对象，retryPolicy 留空：给不出建议就明说给不出，
            // 不能让未论证过的动作借默认值变成「可重放」。
            retryPolicy = descriptor?.let { retryPolicyOf(it.id) },
            effectState = effectStateFor(ok = false, error = error),
            consentKind = consentKindFor(error),
        )
    }

    /** 一次调用一条记录。[requestId] 必须与运行日志、响应信封同一标识，否则事后无法对齐。 */
    @Suppress("LongParameterList")
    private fun record(
        requestId: String,
        descriptor: CapabilityDescriptor,
        system: SystemGrantState,
        tier: AccessTier,
        decision: String,
        outcome: String,
        surface: SurfaceKind?,
        degradedFrom: SurfaceKind?,
        bytes: Long,
        error: RelayError?,
        elapsedMs: Long,
        target: String?,
        canonicalArgs: String?,
    ) {
        auditLog.record(
            AuditEntry(
                requestId = requestId,
                capability = descriptor.id.wire,
                systemState = system.name,
                tier = tier.name,
                decision = decision,
                grantSource = if (system == SystemGrantState.GRANTED) "system_granted" else system.name,
                // 特权通路以 Shizuku 给的身份执行，记录里必须能和「以本应用身份跑」的
                // 普通调用分开——事后问责问的是"当时是谁在跑"。
                uidRole = if (descriptor.id == CapabilityId.SYS_SHELL_FULL) uidRoleOf(fullShellUid()) else "app",
                outcome = outcome,
                surface = surface?.wire,
                degradedFrom = degradedFrom?.wire,
                reason = error?.code,
                latencyMs = elapsedMs,
                artifactBytes = bytes,
                target = target,
                canonicalArgs = canonicalArgs,
            ),
        )
        runLog.event(
            LogSubsystem.GATE,
            LogEvent.CALL_DONE,
            "cap" to descriptor.id.wire,
            "result" to outcome,
            "ms" to elapsedMs.toString(),
            "bytes" to bytes.toString(),
        )
        // 界面上「详细日志」开关的实际落点：默认关闭，开启后每次调用追加一条参数摘要。
        // 只记摘要不记原文 —— 运行日志的目录与宿主日志同处，可读面比授权记录更宽。
        auditLog.argsDigest(canonicalArgs)?.let { digest ->
            runLog.verbose(
                LogSubsystem.GATE,
                LogEvent.CALL_ARGS,
                "cap" to descriptor.id.wire,
                "digest" to digest,
            )
        }
    }

    /** 参数里任一层的字符串值等于本机包名，即视为「以宿主自己为目标」。 */
    /**
     * 「把宿主带到前台」是自身目标规则的唯一例外：只认 app.launch，且参数只有本机包名。
     *
     * 放行的理由是这条不改变任何档位、不读任何数据，效果只是让用户回到自己那页对话；
     * 缺了它任务链收不了尾——助手能跳出去干活，却回不到用户那边，只能等人手动切回。
     * 「操作宿主界面」仍然禁止（无障碍那条路自己挡下宿主的窗口），所以这里放开的不是操作权。
     */
    private fun isHostBringToFront(request: RelayRequest): Boolean =
        isHostBringToFront(request, selfPackage)

    companion object {
        /**
         * 特权通路执行身份在授权记录里的署名。0 = root、2000 = shell（ADB/无线调试启动
         * Shizuku 的常规身份）、其余正数按 uid 报数；读不到（未连接、快照未就绪）如实
         * unknown——冒充任何一个具体身份都会让问责读错场景。
         */
        internal fun uidRoleOf(uid: Int): String = when {
            uid == 0 -> "root"
            uid == 2000 -> "shell"
            uid > 0 -> "uid_$uid"
            else -> "unknown"
        }

        /**
         * 调用侧拒绝分派的判据，纯函数供 JVM 单测：**surface.virtual 被拒 →
         * E_SURFACE_TRUSTED_DENIED，且它排在 requiresMode 解析之前**。顺序写反
         * 让插件按"切回后台/建屏"给建议，用户切回后台再撞一次拒绝，绕成循环；
         * 被拒是这台机器上的固定结论，回包 reason 恒写"改前台"。
         * 其余能力与未被拒的 surface.virtual 回 null（后半边照常决议）。
         */
        /**
         * 调用侧 fail-fast 的完整顺序（纯判据供 JVM 单测穷举两口径的先后）：
         * ①用户把能力拨到**禁止** → GATE_HOST_DENIED（档位语义：下一步是"去档位页解禁"，
         * 用户自己的决定，不被机器侧的拒绝标记盖过说成"这台机器拒绝"）；档位对
         * **任意能力**生效，不看 id。唯一例外是调用方先行识别的"宿主带回前台"形状
         * ——它绕过本判定直接派发（见 handle），本判定不负责那条豁免；
         * ②否则 **只对 surface.virtual**（[id] 必须是它）且这台机器**拒绝过 trusted 建屏**
         * → E_SURFACE_TRUSTED_DENIED（reason 写"改前台"）。少带 id 的判定会把清单里
         * 其它几条后台能力也一起卷进拒绝，"screen 建屏被拒"说成"这台机器对全部能力
         * 都拒绝"——见 BLOCKER 场景；
         * ③其余 → null（照常进 requiresMode/实现/闸门那一套）。
         *
         * **unsafe 不进这道判定**（复核）：「自检判过破坏主屏」只封**自动后台
         * 路由**（见 handle 的建屏准备与后端 supports 的真值表），不封显式的
         * surface.virtual create/release/query——create 是复检与解除标记的唯一活通路，
         * release 是清残留屏的通路；在这里拦下它们，标记就成了不可解除的死锁。
         * unsafe 的失败由后端自检出口自己按 E_SURFACE_TRUSTED_UNSAFE 回。
         * 真装配的 tier 读数经 [InterlockGate.tierOf]，denied 读数经装配根注入后端标记，
         * id 由 handle 的 descriptor 给；判定本体（要不要审批、要不要登场）仍在闸门一处。
         */
        internal fun earlyVerdict(id: CapabilityId, tierDenied: Boolean, surfaceDenied: Boolean): RelayError? =
            when {
                tierDenied -> RelayError.GATE_HOST_DENIED
                surfaceDenied && id == CapabilityId.SURFACE_VIRTUAL -> RelayError.SURFACE_TRUSTED_DENIED
                else -> null
            }

        /**
         * `requiresMode` 分支的执行面映射（纯判据供 JVM 单测；调用侧
         * [RelayCoordinator.handle] 与清单侧 [RelayContainer.capabilityRow] 共用同一张表）：
         * `surface.virtual` 的 create/release/query 是管理动作，不落执行面——仅声明
         * trusted 面的它在「优先后台」缺失时被偏好链卡死，照常回「需要后台模式」就会
         * 让恢复通路在前台偏好下不可达（显式 create/release 正是 unsafe 与残留屏的
         * 解除通路）。对它豁免；其余单一面能力照旧映射。
         */
        internal fun modeRequiredFor(id: CapabilityId, requiresMode: SurfaceKind?): SurfaceKind? =
            if (id == CapabilityId.SURFACE_VIRTUAL) null else requiresMode

        /**
         * 「在等人答」这件事的来源标记。写死成 relay 而不是留空：日志里要能一眼分出
         * 「等审批的人」与「等别的什么事的人」，两类等待的处置完全不同。
         */
        const val WAITING_KIND_RELAY = "relay"

        /**
         * 弹框只花得起预算的一部分：剩下的要留给回写响应，
         * 否则「用户刚点完允许」正好撞上「宿主已放弃这条请求」。
         */
        const val APPROVAL_REPLY_MARGIN_MS = 1_500L

        /**
         * 挂起等待环两轮之间的退避。框还在时答复在任一轮的内联窗口里即时送达；
         * 框被呈现层收走时，迟到的那一下存进了迟到缓冲，这个间隔就是取走它的最迟延迟。
         */
        const val APPROVAL_ROUND_BACKOFF_MS = 500L

        /**
         * 挂起等待环的续行体等裁决让渡的宽限。正常路径答复先于续行就位（立即返回），
         * 这里只为到点兜底续行（等待环失联）留一次握手，等不到按审批超时收场。
         */
        const val DECISION_HANDOFF_GRACE_MS = 2_000L

        /** 挂起划算的最低执行余量：审批窗走满之后，后端至少还有这么多预算可用。 */
        const val MIN_DISPATCH_AFTER_APPROVAL_MS = 2_000L

        /**
         * 确认框缺省窗的上界，与 [interlock.relay.core.interlock.InterlockBroker] 的缺省窗同值：
         * 预算更短时以预算为准。两处常量必须同步改，否则挂起的截止判定与框自身的
         * 截止判定会错开。
         */
        const val APPROVAL_WINDOW_CAP_MS = 60_000L

        /** 拒绝事件的层级标记：调用入口（进审批之前）。 */
        internal const val BLOCKED_AT_ENTRY = "entry"

        /** 拒绝事件的层级标记：批准之后、真正派发之前。 */
        internal const val BLOCKED_AT_PRE_DISPATCH = "pre_dispatch"
    }
}

/**
 * 「同一件事已经有一条挂起在等答复」的登记表，[RelayCoordinator] 的可测内核。
 *
 * 键与闸门的审批频率合并表同一形状（能力 + 参数身份），值是不可变记录：登记时分配的
 * 写入者序号（身份，只增不重）+ 那条挂起的审批截止。表要挡的是双等待者——审批队列
 * 按「同一件事」复用同一张框，两个等待者会拿到同一份答复，把一次批准花在两条请求上
 * ——两条并发纪律缺一不可：
 *
 * 1. 登记用「占用即让路」语义：键已被未过期记录持有时不覆盖、不排队。若允许覆写，
 *    旧环的截止会被新环换掉；旧环收尾时再无条件摘除，摘走的就成了新环的登记，
 *    第三个同问随即能挂到同一张框上，用户点一次允许、两条续行体都执行。
 * 2. 摘除是条件删除：等待环退出时只摘自己写入的那份。截止到点与环真正退出之间隔着
 *    最长一轮退避，这段窗口里同问的重入是合法的，旧环退出不得碰它。
 *
 * 过期的登记自愈：判活一律按截止（过点即不再挡新请求），不依赖任何清理动作；键上
 * 只剩过期记录时，[register] 自己把它摘掉换上新的。全部读写落在并发映射上，
 * 可在多线程直接使用。
 */
internal class ParkedWaitTable(private val now: () -> Long) {

    /** 一条登记：登记时确定的写入者序号与审批截止（单调时刻）。相等按引用判——每条登记各自唯一。 */
    internal class Entry(val writerSeq: Long, val deadlineAtMs: Long)

    private val waits = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val writerSeq = java.util.concurrent.atomic.AtomicLong()

    /**
     * 登记一条同问挂起。键已被**未过期**记录持有时返回 null（不覆盖），调用方据此走
     * 「已有同问在等」的短路；否则写入本次的记录并返回。键上只剩过期记录时先摘掉它
     * 再写入——摘除本身也是条件的，恰逢原环自己收尾摘走时，下一轮写入照常成功。
     */
    internal fun register(key: String, deadlineAtMs: Long): Entry? {
        while (true) {
            val fresh = Entry(writerSeq.incrementAndGet(), deadlineAtMs)
            val incumbent = waits.putIfAbsent(key, fresh) ?: return fresh
            if (incumbent.deadlineAtMs - now() > 0L) return null
            waits.remove(key, incumbent)
        }
    }

    /** 条件摘除：只摘 [entry] 自己写入的那份，返回是否真的摘掉了。别人的登记动不得。 */
    internal fun condRemove(key: String, entry: Entry): Boolean = waits.remove(key, entry)

    /** 「同一件事是否已有一条未过期的挂起在等」。过点的登记在这里不再挡同问的重试。 */
    internal fun isSameQuestionParked(key: String): Boolean {
        val entry = waits[key] ?: return false
        return entry.deadlineAtMs - now() > 0L
    }
}

/**
 * 哪些失败卡在本模块自己的许可上。这四条都出自闸门与挂起-续行路径：要不要做这件事
 * 由用户对本模块的授权回答，与 Android 系统的采集同意（android_projection，由执行面
 * 在真正走到系统弹框的那一侧标注）互不代替。执行面已标注时以执行面为准——
 * 已经走到系统许可那一步，说明本模块的许可已经过了。
 *
 * 顶层纯函数：回包成稿的两个出口（[RelayCoordinator.respond] 与 rejected）共用这一张表，
 * 可在无设备环境下穷举验证。
 */
internal fun consentKindFor(error: RelayError?): String? = when (error) {
    RelayError.GATE_AWAITING_CONSENT,
    RelayError.GATE_NO_FOREGROUND,
    RelayError.GATE_WAITING_TURN,
    RelayError.GATE_CANCELLED,
    -> RelayResponse.CONSENT_KIND_RELAY_POLICY

    else -> null
}

/**
 * 参数树里第一处等于宿主自身包名的位置，以键路径报出（`selector.package`、`args[0]` 这样的嵌套也给全）。
 *
 * 递归扫全部字符串值而不是逐个键名匹配：新增一个能带包名的参数时，按键名写的守卫会
 * 静默漏掉它，而漏一个键就等于留一个后门。返回路径而不是布尔值，是为了让回包说清该改哪一个参数。
 * 放在类外、显式收 `selfPackage`，是为了让这条安全判据能被单测直接驱动。
 */
internal fun selfTargetArg(value: Any?, selfPackage: String, path: String = ""): String? = when (value) {
    is String -> value.takeIf { it == selfPackage }?.let { path }
    is JSONObject -> value.keys().asSequence().mapNotNull { key ->
        selfTargetArg(value.opt(key), selfPackage, childPath(path, key))
    }.firstOrNull()

    is JSONArray -> (0 until value.length()).mapNotNull { index ->
        selfTargetArg(value.opt(index), selfPackage, "$path[$index]")
    }.firstOrNull()

    else -> null
}

/**
 * 「把宿主带到前台」的形状判定：只认 `app.launch`，且参数恰好只有本机包名一个键。
 *
 * 形状之外的任何组合（多带一个参数、换了能力、写了别的包名）都不算：这条豁免的
 * 理由只覆盖"任务收尾把用户带回宿主"这一件事，放宽到别的形状等于放开宿主操作权。
 * 放在类外、显式收 `selfPackage`，是为了让这条安全判据能被单测直接驱动。
 */
internal fun isHostBringToFront(request: RelayRequest, selfPackage: String): Boolean =
    request.capability == CapabilityId.APP_LAUNCH.wire &&
        request.args.length() == 1 &&
        request.args.optString("package") == selfPackage

/**
 * 拼一层键路径。空键名要换成占位写法：交回 `arg ""` 时，调用方看不出撞的是哪一个参数，
 * 而这条路径本身就是这个回包唯一的信息量。
 */
private fun childPath(parent: String, key: String): String {
    val name = key.ifEmpty { "<no-name>" }
    return if (parent.isEmpty()) name else "$parent.$name"
}
