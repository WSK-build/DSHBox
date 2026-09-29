package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog
import interlock.relay.core.runtime.monotonicNow
import interlock.relay.core.R

enum class InterlockChoice {
    ALLOW_ONCE,
    ALLOW_SESSION,
    DENY,
    UNABLE_TO_SHOW,

    /** 确认框挂到了自己的截止时刻仍无人应答。与 [DENY] 不是一回事，见 [ApprovalOutcome.TimedOut]。 */
    EXPIRED,

    /**
     * 确认框还挂在用户面前、只是这一次没等到答复。
     *
     * 与 [DENY] 必须分开：等答复的那段时间里整条通道不该被一次调用占住（信箱按序消费，
     * 一条待答的框会把后面的无关调用一起拖到各自的超时）。调用方拿
     * [ApprovalOutcome.AwaitingConsent] 先走开，框留着，重试会接上同一张框。
     */
    AWAITING,

    /**
     * 等待队列也满了，这一问连挂上的位置都没有。
     *
     * 与 [AWAITING] 分开：那条是「问题已挂上、还没轮到被答」，这一条是「此刻连挂上的
     * 位置都没有」，重试接不上任何一张框，只能退避一阵再问。
     */
    WAITING_TURN,
}

/** 确认框的默认窗口。预算更短时以预算为准，收敛点在 [InterlockBroker.request]。 */
private const val DEFAULT_APPROVAL_WINDOW_MS = 60_000L

/**
 * 确认框内容。界面侧只负责渲染，判定一律在裁决层完成。
 *
 * [targetLabel] 与 [paramDetail] 是用户能否做出判断的关键：只写「要改安全设置」
 * 等于让用户对任意键背书，必须给出改的是哪个键、改成什么。
 *
 * [deadlineAtMs] 是这一次确认停止可答的单调时刻（不是时长），由 [InterlockBroker.request] 算一次。
 * 界面只照它到点前的剩余量画倒计时：确认框可能排队后才挂上，照时长画会承诺一段自己没有的时间。
 */
data class ApprovalPrompt(
    val capability: CapabilityId,
    /**
     * 这一次询问的**身份**：整份调用参数的摘要，与屏上那两行显示文字无关。
     *
     * 判据不能落在 [targetLabel] / [paramDetail] 上：那两条是为呈现而截过长的
     * （逐值 24、整串 80），共用前缀的两个不同目标会截成同一句人话。用截断后的文字
     * 认「同一件事」，后果是把用户对前一个目标的同意算给后一个。
     *
     * 只参与比对，一律不显示：呈现层取的是那两条截过的文字。
     */
    val argsIdentity: String,
    /**
     * 目标是不是「换一次屏就指别人」的那种 —— 调用参数里给的是 `nodeId`。
     *
     * 由参数直接判定，不去认屏上那行文字的开头：那句式是呈现层拼出来的，改一次格式
     * 就会让这道判据静默失效，而失效的后果是把用户对某个节点的同意算到下一个占同一个
     * 编号的控件上。判据与身份同源，都只看参数。
     */
    val volatileTarget: Boolean,
    val titleRes: Int,
    val summaryRes: Int,
    val targetLabel: String?,
    val risk: RiskLevel,
    val callIndexInSession: Int,
    val paramDetail: String?,
    val allowsSessionGrant: Boolean,
    val deadlineAtMs: Long = monotonicNow() + DEFAULT_APPROVAL_WINDOW_MS,
)

enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * 两次询问是不是同一件事：同一条能力、同一个对象、同一份参数明细。
 *
 * 重试要不要接上屏上那张框、留下的那张卡上答案能不能给下一次调用用，判据必须同一条；
 * 两处各写一遍就会有一处放宽，而放宽的后果是把用户对「读通讯录」的同意算到「装应用」上。
 */
internal fun ApprovalPrompt.sameQuestionAs(other: ApprovalPrompt): Boolean =
    capability == other.capability &&
        argsIdentity == other.argsIdentity &&
        // 「能不能给会话档」也在判据里：它等于「这次是不是系统授权界面」。少了这一项，
        // 一次普通能力上点的「本会话内允许」会被一次落在授权框上的重试原样花掉，
        // 而授权框的规矩是不认会话授权、每次当场问。
        allowsSessionGrant == other.allowsSessionGrant

/**
 * 用户真的答过的那三种。
 *
 * 枚举里其余的值是**通路状态**而不是答复：EXPIRED 是那张卡自己到点收了，AWAITING 是
 * 这一次没等到，UNABLE_TO_SHOW 是这一条通路问不到人。把它们当成答复往下传，就等于
 * 让下一次重试拿到一个"没人按过的超时"。
 */
internal val InterlockChoice.isAnswer: Boolean
    get() = this == InterlockChoice.ALLOW_ONCE ||
        this == InterlockChoice.ALLOW_SESSION ||
        this == InterlockChoice.DENY

/**
 * 这一答被丢掉之后，要不要顺手给这一问一个终点。
 *
 * 判据是「既不认迟到答案，又没有会话档可退」：`volatileTarget` 的那 28 条里，用户点一次
 * 「本会话内允许」就退出了反复问的环；剩下这几条（档位上限是「每次询问」的那些 ——
 * `app.install`、`audio.capture`、`clip.read`、`loc.read`、`media.write`）没有那颗按钮，
 * 于是抬手慢一步就会一直"答了但不算数"：助手按可重试去重试，每一轮都重新问一张，
 * 而每一张都被同一只慢手答晚。落成终态，让这一问到此为止，改由用户重新发起。
 *
 * 落在系统授权界面上的调用也走这一支（它同样拿不到会话档），这是有意的：那一类本来就
 * 每次当场问，更不该被一次迟到的抬手反复重问。
 */
internal val ApprovalPrompt.terminatesWhenAnswerDropped: Boolean
    get() = volatileTarget && !allowsSessionGrant

/**
 * 上限为「每次询问」或落在系统授权界面上的调用没有「本会话内允许」这个选项：
 * 呈现侧漏判时必须降级为「仅本次」，否则一次误点就把每次询问升成永久免弹。
 */
internal fun InterlockChoice.downgradedFor(prompt: ApprovalPrompt): InterlockChoice =
    if (this == InterlockChoice.ALLOW_SESSION && !prompt.allowsSessionGrant) InterlockChoice.ALLOW_ONCE else this

/**
 * 风险那一行的字串。放在枚举旁边而不是界面文件里：确认卡与界面两处都要显示它，
 * 两处各写一份 `when` 就会有一处漏掉某档风险。
 */
internal fun RiskLevel.labelRes(): Int = when (this) {
    RiskLevel.LOW -> R.string.relay_risk_low
    RiskLevel.MEDIUM -> R.string.relay_risk_medium
    RiskLevel.HIGH -> R.string.relay_risk_high
}

/** 由界面层实现的展示契约。返回 [InterlockChoice.UNABLE_TO_SHOW] 表示当前无法呈现确认框。 */
fun interface InterlockPresenter {
    suspend fun present(prompt: ApprovalPrompt): InterlockChoice
}

sealed class ApprovalOutcome {
    data object AllowedOnce : ApprovalOutcome()
    data object AllowedForSession : ApprovalOutcome()
    data object Denied : ApprovalOutcome()
    data object TimedOut : ApprovalOutcome()

    /** 确认框还挂在用户面前，只是这一次调用的预算先用完了。与「没人应答」不是一回事。 */
    data object AwaitingConsent : ApprovalOutcome()

    /** 确认队列已满，这一问没有拿到排队的位置。与「挂在屏上等人答」是两件事。 */
    data object WaitingTurn : ApprovalOutcome()
    data object CannotPresent : ApprovalOutcome()
}

/**
 * 确认框调度。超时按拒绝处理而不是挂起等待：助手在等一个明确回音，
 * 挂起会让它误判为仍可继续执行。
 */
class InterlockBroker(
    private val presenter: InterlockPresenter,
    private val runLog: RunLog,
    private val timeoutMs: Long = DEFAULT_APPROVAL_WINDOW_MS,
) {

    /**
     * [budgetMs] 是这一次调用在通道里剩下的预算。确认框窗口不能长过它：
     * 预算先到点时信箱会把整条请求判成 `E_TRANSPORT_TIMEOUT`，而用户只是还没点确认 ——
     * 助手据此以为后端挂了并原样重试，每次都撞在同一处。宁可自己先回一条可重试的
     * [ApprovalOutcome.AwaitingConsent]，让「等用户」这件事有独立的名字。
     */
    suspend fun request(prompt: ApprovalPrompt, budgetMs: Long): ApprovalOutcome {
        val window = minOf(timeoutMs, budgetMs)
        if (window < MIN_PROMPT_WINDOW_MS) {
            runLog.event(
                LogSubsystem.GATE,
                LogEvent.APPROVAL_TIMEOUT,
                "cap" to prompt.capability.wire,
                "cause" to "budget",
            )
            return ApprovalOutcome.AwaitingConsent
        }
        // 窗口与截止时刻都只在这里算一次：随 prompt 交给呈现器的只有截止时刻，界面不再自己配时长。
        val deadlineAtMs = monotonicNow() + window
        val choice = kotlinx.coroutines.withTimeoutOrNull(window) {
            presenter.present(prompt.copy(deadlineAtMs = deadlineAtMs))
        }
        return when {
            choice == null -> {
                // 窗口被预算截短 ≠ 用户读完了没表态：前者要重试，后者是本机给出的确定拒绝。
                val cutShortByBudget = window < timeoutMs
                runLog.event(
                    LogSubsystem.GATE,
                    LogEvent.APPROVAL_TIMEOUT,
                    "cap" to prompt.capability.wire,
                    "cause" to if (cutShortByBudget) "budget" else "window",
                )
                if (cutShortByBudget) ApprovalOutcome.AwaitingConsent else ApprovalOutcome.TimedOut
            }

            else -> {
                runLog.event(
                    LogSubsystem.GATE,
                    LogEvent.APPROVAL_RESULT,
                    "cap" to prompt.capability.wire,
                    "choice" to choice.name,
                )
                when (choice) {
                    InterlockChoice.ALLOW_ONCE -> ApprovalOutcome.AllowedOnce
                    InterlockChoice.ALLOW_SESSION -> ApprovalOutcome.AllowedForSession
                    InterlockChoice.DENY -> ApprovalOutcome.Denied
                    InterlockChoice.UNABLE_TO_SHOW -> ApprovalOutcome.CannotPresent
                    // 呈现层自己判的「还没人答」：框留着，不算一次拒绝，也不占用调用方。
                    InterlockChoice.AWAITING -> ApprovalOutcome.AwaitingConsent
                    // 等待队列也满了：这一问连位置都没拿到，与「挂在屏上等人答」分开。
                    InterlockChoice.WAITING_TURN -> ApprovalOutcome.WaitingTurn
                    // 框到点自己收：这一次是真的没人答，与「调用方先走开」分开。
                    InterlockChoice.EXPIRED -> ApprovalOutcome.TimedOut
                }
            }
        }
    }

    fun errorFor(outcome: ApprovalOutcome): RelayError? = when (outcome) {
        ApprovalOutcome.Denied -> RelayError.GATE_HOST_DENIED
        ApprovalOutcome.TimedOut -> RelayError.GATE_APPROVAL_TIMEOUT
        ApprovalOutcome.AwaitingConsent -> RelayError.GATE_AWAITING_CONSENT
        ApprovalOutcome.WaitingTurn -> RelayError.GATE_WAITING_TURN
        ApprovalOutcome.CannotPresent -> RelayError.GATE_NO_FOREGROUND
        else -> null
    }

    private companion object {
        /**
         * 低于这个窗口的确认框不如不弹：用户读完标题就要抬手，弹了也答不完，
         * 而一次没人答完的弹框比一次明确的失败更消耗信任。
         */
        const val MIN_PROMPT_WINDOW_MS = 5_000L
    }
}
