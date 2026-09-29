package interlock.relay.core.interlock

import interlock.relay.core.protocol.RelayExitCode

/**
 * ask 的终止码与退出码的**单源**。
 *
 * 为什么要单源：失败分类要同时出现在三个地方——回包的原因串、入口 CLI 的退出码、
 * 说明文档的叙述。三处各写一份时，改一个前缀而漏了另一处就会把"忙"报成"内部故障"。
 * 宿主在这里定码与退出码，回包带上 `error.exitCode`，入口按它取值（只在拿不到时才
 * 退回按前缀猜）。
 *
 * 退出码沿用 [RelayExitCode] 的既有档位：超时 3、需要用户动手 4、稍后重试 5、宿主内部 6。
 */
object PromptCodes {

    /** 等满窗口无人作答。窗口由调用方给。 */
    const val TIMEOUT = "E_ASK_TIMEOUT"

    /** 屏上已经有一张卡/另一问在等，先答那一张。 */
    const val BUSY = "E_ASK_BUSY"

    /** 此刻没有可用的呈现面：没授权 / 后台模式 / 前台应用把系统浮窗藏了。**需要用户动手**。 */
    const val NO_SURFACE = "E_ASK_NO_SURFACE"

    /** 宿主侧问题：装配缺件、叠加状态没有登记等。调用方改不了，也不该让用户去掏设置。 */
    const val INTERNAL = "E_INTERNAL"

    /**
     * 原因串：前缀是上面四个之一，斜杠后是给人和日志看的具体原因。
     * 入口只认前缀，具体原因可以随时改词。
     */
    const val NO_SURFACE_BLIND = "$NO_SURFACE/background mode hides floating cards"
    const val NO_SURFACE_NOT_GRANTED = "$NO_SURFACE/overlay permission is not granted"
    const val NO_SURFACE_HIDDEN = "$NO_SURFACE/hidden-by-platform"
    const val BUSY_ANOTHER_QUESTION = "$BUSY/another question is already waiting on screen"
    const val BUSY_APPROVAL_CARD = "$BUSY/an approval card is on screen"
    /** 通道在等待期间被收束（用户把开关关了/运行时停了）：卡已撤，稍后重开通道再问即可。 */
    const val BUSY_CHANNEL_STOPPED = "$BUSY/the channel stopped while the card was up"
    const val INTERNAL_NO_CHANNEL = "$INTERNAL/this host has no ask channel wired"
    const val INTERNAL_DUPLICATE = "$INTERNAL/an ask with this id is already in flight"
    const val TIMEOUT_NO_ANSWER = "$TIMEOUT/no answer within the window"
    const val TIMEOUT_COVERED = "$TIMEOUT/the card lost window focus while waiting (likely hidden)"
    const val TIMEOUT_EXPIRED = "$TIMEOUT/the request sat in the inbox past its own window"

    /** 前缀 → 退出码。未知前缀一律按宿主内部故障（宁可让调用方来问，也不要教人去改设置）。 */
    fun exitCodeFor(cause: String): Int = when (codeOf(cause)) {
        TIMEOUT -> RelayExitCode.TIMEOUT
        NO_SURFACE -> RelayExitCode.NEEDS_USER_ACTION
        BUSY -> RelayExitCode.RETRY_LATER
        else -> RelayExitCode.INTERNAL
    }

    /** 原因串的前缀码；不认识的前缀按 [INTERNAL] 收口。 */
    fun codeOf(cause: String): String = when (val head = cause.substringBefore('/')) {
        TIMEOUT, BUSY, NO_SURFACE, INTERNAL -> head
        else -> INTERNAL
    }

    /** 再试一次有可能变好的：等一等（忙/超时）与用户动手之后（无面）。 */
    fun retryable(cause: String): Boolean = codeOf(cause) != INTERNAL
}

/**
 * 提问的结局。五支各自对应调用方不同的下一步：
 * - [Chosen]：用户选了某个选项，原样交回；
 * - [RejectedAll]：「全部驳回」是**合法答案**——这一组选项都不合适，助手据此重新整理选项再问；
 * - [Reasked]：「重新提问」也是**合法答案**——用户要的不是换选项，而是把问题本身换个法子
 *   重新组织。两者的区别在这里：驳回否的是选项，重新提问否的是问题；
 * - [TimedOut]：等满窗口无人作答（窗口由调用方给，下限由宿主侧校验夹紧）；
 *   [TimedOut.covered] 说这张卡在等待期间丢过窗口焦点（多半被别的窗盖住）——结论仍是
 *   「没人答」，但原因不同，回包与日志要把这条区分带出去：被盖住的卡用户根本没看见，
 *   和看见了不答，是两件要修的事。
 * - [Unavailable]：此刻没有可用的呈现面（无悬浮窗授权 / 后台模式隐藏浮窗 / 已有一张卡在屏）。
 */
sealed interface PromptOutcome {
    data class Chosen(val index: Int, val label: String) : PromptOutcome
    object RejectedAll : PromptOutcome
    object Reasked : PromptOutcome
    data class TimedOut(val covered: Boolean) : PromptOutcome
    data class Unavailable(val cause: String) : PromptOutcome
}

/**
 * 控制通道侧的提问契约：实现负责把问题摆到用户眼前并等答复，绝不触碰执行链，
 * 也绝不替用户作答。返回 [PromptOutcome.Unavailable] 表示这一趟没有问出口。
 */
fun interface InterlockPrompt {
    suspend fun ask(question: String, options: List<String>, timeoutMs: Long): PromptOutcome
}

/**
 * 提问呈现面的宿主侧管理契约：在 [InterlockPrompt] 的呈现与等答复之外，补两条
 * core 需要的管理动作——裁决要随时能查这张卡在不在屏上，通道收束时要能撤卡。
 * 悬浮卡实现由宿主提供；缺省无呈现面时 core 用 [Unavailable] 兜底。
 */
interface RelayAskSurface : InterlockPrompt {

    /** 屏上此刻是否有本呈现器的卡。审批那条路据此让位，裁决随时可以查。 */
    fun isPresented(): Boolean

    /**
     * 收掉屏上那张卡并把等待当场收场。幂等，可在任意线程调用。
     *
     * 什么时候用：通道/会话已经收束（用户把开关关了、运行时停了）而卡还挂在屏上。
     */
    fun withdraw(cause: String = PromptCodes.BUSY_CHANNEL_STOPPED)

    companion object {
        /**
         * 上屏判定的总预算上限。宿主提问卡的实现（悬浮窗从挂上到拿到窗口焦点）
         * 必须把这一段控制在入口 CLI 等待余量（入口脚本的 ASK_BUDGET_MARGIN_MS）之内，
         * 且余量还要装得下控制通道的认领延迟——两端的数各写一份时没有编译器拦得住，
         * 所以契约常量落在 core，AskContractTest 直接引用它与入口脚本对账。
         */
        const val ON_SCREEN_BUDGET_MS = 4000L

        /** 缺省呈现面：永远问不出口，回「宿主没有接提问通道」。 */
        val Unavailable: RelayAskSurface = object : RelayAskSurface {
            override fun isPresented(): Boolean = false
            override fun withdraw(cause: String) {}
            override suspend fun ask(
                question: String,
                options: List<String>,
                timeoutMs: Long,
            ): PromptOutcome = PromptOutcome.Unavailable(PromptCodes.INTERNAL_NO_CHANNEL)
        }
    }
}
