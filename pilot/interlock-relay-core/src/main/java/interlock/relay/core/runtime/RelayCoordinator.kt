package interlock.relay.core.runtime

import android.content.Context
import interlock.relay.core.protocol.CapabilityArgsSpec
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
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
import interlock.relay.core.exec.a11y.NodeSelector
import interlock.relay.core.interlock.ApprovalOutcome
import interlock.relay.core.interlock.InterlockTargets
import interlock.relay.core.interlock.ConsentSurfaces
import interlock.relay.core.interlock.GateResult
import interlock.relay.core.interlock.InterlockGate
import interlock.relay.core.interlock.RateLimiter
import interlock.relay.core.interlock.SystemStateProbe
import interlock.relay.core.log.AuditEntry
import interlock.relay.core.log.AuditLog
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.surface.BackendDispatcher
import interlock.relay.core.surface.SurfaceDecision
import interlock.relay.core.surface.SurfacePolicy
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
    /** 按编号回查节点归属，用于闸门判「目标是不是系统授权界面」。答不出就回 null。 */
    private val nodeOwner: (Int) -> interlock.relay.core.exec.RelayBackend.TargetOwner? = { null },
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
    /** v2 取消意愿查询：等待环每轮开跑前问一次，答 true 即以「已取消、未执行」收场。 */
    private val isCancelRequested: ((String) -> Boolean)? = null,
    /**
     * 「这一问谁也问不到」时该点名做哪一步。由装配根接队列的裁决结果。
     *
     * 只有这一种失败需要它：`E_GATE_NO_FOREGROUND` 本身不区分「通知被关」与
     * 「自己在后台模式里跑」，而这两种的处置完全相反（一个去系统里开通知，一个退出后台模式）。
     */
    private val approvalDeadEnd: () -> String? = { null },
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

        val (targetLabel, paramDetail) = InterlockTargets.of(descriptor, request.args) { count ->
            String.format(text.string(interlock.relay.core.R.string.relay_approval_char_count), count)
        }
        val decision = if (bringToFront) {
            // 收尾回宿主这一形状必须落在用户眼前的屏上：后台优先模式下它会被派去可信虚拟屏，
            // 应用起在那块屏上、用户根本看不到——而这恰恰是这条命令要修的事。
            SurfaceDecision(SurfaceKind.FOREGROUND)
        } else {
            policy.decide(descriptor)
        }
        // 单一执行面的能力被偏好链卡死时（用户选了前台，而它只认可信屏）在这里点名
        // 「需要哪个模式」：不进闸门、不进后端——伪造的前台落点只会把一次注定失败的
        // 调用先送过用户确认，再换成一条泛化的「够不着」。
        decision.requiresMode?.let { mode ->
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
        // 目标是系统授权界面时不认会话授权、也不给「本会话内允许」：AI 点掉别人的
        // 授权框等于替用户答应任意权限申请，这道判据在两层闸门里都排最前。
        val consentScreen = consentTarget(request.args)
        val gateStartedAt = now()
        val gate = gatekeeper.authorize(
            descriptor = descriptor,
            targetLabel = targetLabel,
            paramDetail = paramDetail,
            // 「是不是同一件事」按整份参数判，不按屏上那两行截过的文字判：摘要与授权记录
            // 同源，事后能从记录里对回是同一次调用。
            argsIdentity = argsIdentity,
            volatileTarget = volatileTarget,
            consentScreen = consentScreen,
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
                consentScreen = consentScreen,
                system = system,
                decision = decision,
                budgetMs = budgetMs,
                startedAt = startedAt,
                gateStartedAt = gateStartedAt,
                gate = gate,
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
                gate = gate,
                decision = decision,
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
        gate: GateResult,
        decision: SurfaceDecision,
        budgetMs: Long,
        startedAt: Long,
    ): RelayResponse {
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
                gate = cancelledGate(gate),
                decision = decision,
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
        return when (val result = dispatched.result) {
            is BackendResult.Ok -> respond(
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
                // 把唯一那条现场信息（此刻哪个包停在最前面）丢掉，调用方只剩一个裸错误码，
                // 处置建议也就无从给出。
                detail = result.reason,
                degradeReason = dispatched.decision.reason,
                error = null,
                startedAt = startedAt,
            )

            is BackendResult.Failed -> respond(
                request = request,
                descriptor = descriptor,
                target = targetLabel,
                gate = gate,
                decision = dispatched.decision,
                outcome = "failed",
                data = JSONObject(),
                artifacts = emptyList(),
                bytes = 0L,
                detail = result.reason,
                degradeReason = dispatched.decision.reason,
                error = result.error,
                startedAt = startedAt,
                // 执行面在自己的失败出口标注的许可种类（如系统采集弹框）优先：
                // 已经走到系统许可那一步，说明本模块的许可已经过了。
                backendConsentKind = result.consentKind,
            )
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
        consentScreen: Boolean,
        system: ProbeResult,
        decision: SurfaceDecision,
        budgetMs: Long,
        startedAt: Long,
        gateStartedAt: Long,
        gate: GateResult,
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
                    gate = outcomeGate,
                    decision = decision,
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
                consentScreen = consentScreen,
                system = system,
                gate = gate,
                deadlineAtMs = deadlineAtMs,
                gateDeferred = gateDeferred,
                key = key,
                waitEntry = waitEntry,
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
        consentScreen: Boolean,
        system: ProbeResult,
        gate: GateResult,
        deadlineAtMs: Long,
        gateDeferred: CompletableDeferred<GateResult>,
        key: String,
        waitEntry: ParkedWaitTable.Entry,
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
                // authorize 前再查一次作用域：stop 在别的线程上，可以落在轮首检查之后。
                // 通道已停就退场，不让这一轮把审批卡重新挂到一个不再接活的通道上。
                if (parkScope?.invoke() == null) return
                val round = gatekeeper.authorize(
                    descriptor = descriptor,
                    targetLabel = targetLabel,
                    paramDetail = paramDetail,
                    argsIdentity = argsIdentity,
                    volatileTarget = volatileTarget,
                    consentScreen = consentScreen,
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
            // `retryable` 只是错误码属性，不能据此自动重放非幂等动作。
            retryPolicy = retryPolicyOf(descriptor.id),
            effectState = effectStateFor(error == null, error),
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
                uidRole = "app",
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

    /**
     * 目标是不是系统授权界面。两道判据：
     *
     * 1. 看调用方递来的选择器条件 —— 带 `package` / 权限字样的目标一眼可辨；
     * 2. 只给 `nodeId` 时按当前控件树回查归属 —— 参数里什么线索都没有，只有回查才知道
     *    要点的那颗按钮属于谁。回查不到（服务没连上、节点已换人）按"认不出来"处理：
     *    少一道判据，而不是据此放行或据此拒绝。
     */
    private fun consentTarget(args: JSONObject): Boolean {
        if (ConsentSurfaces.looksLikeConsentFromArgs(args)) return true
        if (!args.has(NodeSelector.KEY_NODE_ID)) return false
        val owner = nodeOwner(args.optInt(NodeSelector.KEY_NODE_ID)) ?: return false
        return ConsentSurfaces.looksLikeConsentNode(owner.packageName, owner.viewId)
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
        request.capability == CapabilityId.APP_LAUNCH.wire &&
            request.args.length() == 1 &&
            request.args.optString("package") == selfPackage

    private companion object {
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
 * 拼一层键路径。空键名要换成占位写法：交回 `arg ""` 时，调用方看不出撞的是哪一个参数，
 * 而这条路径本身就是这个回包唯一的信息量。
 */
private fun childPath(parent: String, key: String): String {
    val name = key.ifEmpty { "<no-name>" }
    return if (parent.isEmpty()) name else "$parent.$name"
}
