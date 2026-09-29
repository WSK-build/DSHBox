package interlock.relay.core.interlock

import interlock.relay.core.runtime.monotonicNow
import java.util.concurrent.atomic.AtomicReference

/**
 * 「用户答了、但那一刻没有调用在等」的那一格缓冲。
 *
 * 单次调用只为等人点让出几秒，到点就先回「还在等」，而卡片留在屏上继续等用户。那时用户
 * 点下的答案如果只交给已经走开的那次调用，就等于白点：下一次同参数的重试只会看到「还在等」，
 * 稍后还要把同一件事再问一遍。这里就是那一格缓冲 —— 答案按问题存下，等重试来取。
 *
 * 存的有两种判决：用户答的那一句（[LateVerdict.Answered]），以及**这一答被丢掉了**
 * （[LateVerdict.Discarded]，见 `terminatesWhenAnswerDropped`）。后者也要占一格，是因为
 * "丢掉"本身是用户的一次抬手：不记下来的话，重试会重新问一张，而那一类能力既没有会话档可以
 * 退出这个环，用户慢一步就会一直"答了但不算数"。
 *
 * 只存一条：同一时刻屏上最多一张卡（[OverlayApprovalPresenter] 不叠窗）。
 */
internal class ParkedAnswerMailbox(
    private val now: () -> Long = ::monotonicNow,
) {

    private class Entry(val prompt: ApprovalPrompt, val verdict: LateVerdict, val expiresAtMs: Long)

    /** 摘取与置空必须是一次操作：两条调用都拿到同一个「允许」，等于一次点击批了两次执行。 */
    private val entry = AtomicReference<Entry?>(null)

    /** 用户在留下的那张卡上答了。[expiresAtMs] 沿用那张卡自己的可答截止时刻。 */
    fun put(prompt: ApprovalPrompt, choice: InterlockChoice, expiresAtMs: Long) {
        put(prompt, LateVerdict.Answered(choice), expiresAtMs)
    }

    /**
     * 用户在留下的那张卡上答了，而这一答按判据不能留给下一次调用 —— 记下"这一问已经答过
     * 并被丢掉"，让下一次重试落成终态而不是重新问一遍。
     */
    fun putDiscarded(prompt: ApprovalPrompt, expiresAtMs: Long) {
        put(prompt, LateVerdict.Discarded, expiresAtMs)
    }

    private fun put(prompt: ApprovalPrompt, verdict: LateVerdict, expiresAtMs: Long) {
        entry.set(Entry(prompt, verdict, expiresAtMs))
    }

    /**
     * 取走与 [prompt] 是同一件事的那一格判决；不是同一件事时**留着不动**（那件事的重试可能
     * 马上就到），那张卡的可答窗口过了才作废。
     *
     * 过点必须作废：判决绑的是那一次确认窗口，窗口过了还拿来批新问题，等于让用户替一件
     * 他早已不再能反悔的事背书。
     */
    fun take(prompt: ApprovalPrompt): LateVerdict? {
        val current = entry.get() ?: return null
        if (current.expiresAtMs - now() <= 0L) {
            entry.compareAndSet(current, null)
            return null
        }
        if (!current.prompt.sameQuestionAs(prompt)) return null
        // 只有把这一格真正摘走的那个人算取到；并发来晚的那次会读到 null。
        return if (entry.compareAndSet(current, null)) current.verdict else null
    }

    /** 撤通道或收掉留下的那张卡时清空：那张卡已经不在了，答案不该再被取走。 */
    fun clear() {
        entry.set(null)
    }
}

/**
 * 一次「没人在等」的抬手留下的判决。
 *
 * 做成两个类型而不是一句可空答案，是为了让"丢掉"这条分支在编译期就被看见：把它当成
 * `null`（= 没有答案）往下传，调用方就会去重新问一张，而这一问恰恰不该再问。
 */
internal sealed interface LateVerdict {
    /** 用户答的那一句，可由下一次同参数重试取走。 */
    data class Answered(val choice: InterlockChoice) : LateVerdict

    /** 用户答了，而这一答按判据不能花到下一次调用上：这一问到此为止。 */
    object Discarded : LateVerdict
}
