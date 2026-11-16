package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 悬浮通路的**单驱动模型**：present 的悬浮分支、改判进 OVERLAY 的兜底、
 * 等待队列提升后的挂卡，全部走同一个 [InterlockQueue.driveOverlay]，串行在一把互斥量上，
 * 没有令牌、代际或排程账本。本组用例用**真实协程**（launcher 真起 worker、呈现器真等用户）
 * 钉住五条用户可见的承诺：不双卡、不零入口、第一次答复不丢、下一问不等 8 秒、
 * 快速作答也有 via=overlay 日志；外部收口经 cancelCurrent 立即放行互斥门。
 */
class InterlockQueueOverlaySingleDriverTest {

    /**
     * 会「等用户」的悬浮呈现器：挂卡后停在 [answers] 上直到测试递答案；
     * [cancelCurrent] 与真实呈现器同构——外部收口时挂卡等待立即返回。
     */
    private class LiveOverlay : OverlayApprovalChannel {
        val presentCalls = AtomicInteger()
        val shown = Channel<ApprovalPrompt>(Channel.UNLIMITED)
        val answers = Channel<InterlockChoice>(Channel.UNLIMITED)

        /**
         * true = 下一趟 present 挂卡后立即回「还在等」（卡被留下），之后的点击经
         * [onParkedChoice] 回调——与真实呈现器的 parked 路径同构。
         */
        @Volatile
        var parkNext = false

        /** 非空 = 等答案的窗口（模拟真实呈现器的 answerBudget）：到点自回 EXPIRED。 */
        @Volatile
        var answerBudgetMs: Long? = null

        /** park 模式下返回 AWAITING 之前的保持闸：用它构造「收口落在夹缝」的场景。 */
        @Volatile
        var parkHold: CompletableDeferred<Unit>? = null

        /** 非空 = 挂卡前先等它（模拟 dispatch/建窗前的），期间取消即让位、不挂卡。 */
        @Volatile
        var preShowGate: CompletableDeferred<Unit>? = null

        @Volatile
        private var onScreen = false

        @Volatile
        private var cancelSignal: CompletableDeferred<Unit>? = null

        private val parkedCards = Channel<ApprovalPrompt>(Channel.UNLIMITED)

        override fun canShow(): Boolean = true

        override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
            presentCalls.incrementAndGet()
            val cancel = CompletableDeferred<Unit>()
            cancelSignal = cancel
            // 挂卡前：取消即时生效，无窗可收、无 ghost。
            preShowGate?.let { gate ->
                var cancelled = false
                select<Unit> {
                    gate.onAwait { }
                    cancel.onAwait { cancelled = true }
                }
                if (cancelled) return InterlockChoice.UNABLE_TO_SHOW
            }
            onScreen = true
            shown.send(prompt)
            onPresented?.invoke(prompt)
            if (parkNext) {
                parkedCards.send(prompt)
                parkHold?.let { hold ->
                    select<Unit> {
                        hold.onAwait { }
                        cancel.onAwait { }
                    }
                }
                return InterlockChoice.AWAITING
            }
            var cancelled = false
            val budget = answerBudgetMs
            val choice = if (budget == null) {
                select {
                    answers.onReceive { it }
                    cancel.onAwait { cancelled = true; null }
                }
            } else {
                withTimeoutOrNull(budget) {
                    select {
                        answers.onReceive { it }
                        cancel.onAwait { cancelled = true; null }
                    }
                }
            }
            if (cancelSignal === cancel) cancelSignal = null
            onScreen = false
            if (cancelled) return InterlockChoice.UNABLE_TO_SHOW
            // 挂上了却到点无人答：与真实呈现器同回 EXPIRED（不是 UNABLE）。
            return choice ?: InterlockChoice.EXPIRED
        }

        /** 被留下的卡上那一下点击：与真实呈现器同走 onParkedChoice。 */
        fun clickParked(choice: InterlockChoice) {
            val card = parkedCards.tryReceive().getOrNull() ?: error("没有被留下的卡")
            onScreen = false
            onParkedChoice?.invoke(card, choice)
        }

        override fun isPresented(): Boolean = onScreen

        override fun withdraw() {
            onScreen = false
            cancelCurrent()
        }

        override fun cancelCurrent() {
            cancelSignal?.complete(Unit)
        }

        override var onParkedChoice: ((ApprovalPrompt, InterlockChoice) -> Unit)? = null
        override var onHidden: (() -> Unit)? = null
        override var onPresented: ((ApprovalPrompt) -> Unit)? = null
    }

    private fun prompt(argsIdentity: String) = ApprovalPrompt(
        capability = CapabilityId.SECURE_SETTINGS,
        argsIdentity = argsIdentity,
        volatileTarget = false,
        titleRes = 0,
        summaryRes = 0,
        targetLabel = "secure/window_animation_scale",
        risk = RiskLevel.HIGH,
        callIndexInSession = 1,
        paramDetail = null,
        allowsSessionGrant = true,
        deadlineAtMs = 61_000L,
    )

    /** 与 RelayContainer 的撤销广播同构：收口必撤呈现者（撤卡即取消在途等待）。 */
    private fun queue(
        overlay: LiveOverlay,
        routing: MutableList<InterlockQueue.Presentation>? = null,
        inlineWaitMs: Long = 1L,
        runEnsure: Boolean = true,
        notifyOk: () -> Boolean = { true },
        otherCard: () -> Boolean = { false },
        scope: CoroutineScope,
    ) = InterlockQueue(
        isForegroundCapable = { false },
        onRouting = { routing?.add(it) },
        overlay = overlay,
        overlayEnsureLauncher = { work -> if (runEnsure) scope.launch { work() } },
        canNotify = notifyOk,
        otherCardOnScreen = otherCard,
        onRevokePresenters = { overlay.withdraw() },
        now = { 1_000L },
        inlineWaitMs = inlineWaitMs,
    )

    /**
     * BLOCKER：兜底先赢（卡已挂上、正在等用户）+ 后台 + 通知被关时，
     * 调用方的重试**不得**把「门被占」当成无路而关掉请求——它搭同一张卡，
     * 用户的第一次点击当场唤醒它。
     */
    @Test
    fun ensureWinsAndNotifyBlockedCallerJoinsTheSameCard() = runBlocking {
        val overlay = LiveOverlay()
        var otherCard = true
        var notifyOk = true
        val q = queue(overlay, notifyOk = { notifyOk }, otherCard = { otherCard }, scope = this)
        val asked = prompt("args-1")

        // 首问走通知（提问卡占屏），调用方让开。
        assertEquals(InterlockChoice.AWAITING, q.present(asked))
        // 提问卡撤走、通知被用户关掉：改判悬浮，兜底真实执行把卡挂上（先赢）。
        otherCard = false
        notifyOk = false
        q.reroute()
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })

        // 调用方（等待环重试）随后进入：只能搭兜底那张卡——门在兜底手里，绝不另挂/关请求。
        val caller = async(start = CoroutineStart.UNDISPATCHED) { q.present(asked) }
        yield()
        assertEquals("兜底先赢后，调用方不得再挂第二张", 1, overlay.presentCalls.get())

        // 用户在兜底卡上点允许：第一次也是唯一一次答复，当场唤醒调用方。
        overlay.answers.send(InterlockChoice.ALLOW_ONCE)
        assertEquals("第一次答复生效，不回「问不了」", InterlockChoice.ALLOW_ONCE, caller.await())
        assertEquals(1, overlay.presentCalls.get())
        assertNull("定案后展示位清空", q.pendingSnapshot().displayed)
    }

    /**
     * 调用方驱动收答后提升下一问：下一问的卡**当场**挂上（互斥门释放后新驱动自然接上，
     * 不需要令牌/账本），上一问的调用方拿到自己的答案。
     */
    @Test
    fun callerDrivenAnswerPromotesTheNextQuestionImmediately() = runBlocking {
        val overlay = LiveOverlay()
        val q = queue(overlay, scope = this)
        val r1 = prompt("args-1")
        val r2 = prompt("args-2")

        val first = async(start = CoroutineStart.UNDISPATCHED) { q.present(r1) }
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        // 排队（等待环重发的场景：同一问重发回「还在等」）。
        assertEquals(InterlockChoice.AWAITING, q.present(r2))

        // 用户在  卡上点允许： 收口、 提升，兜底立即把  的卡挂上。
        overlay.answers.send(InterlockChoice.ALLOW_ONCE)
        assertEquals(InterlockChoice.ALLOW_ONCE, first.await())
        assertEquals("args-2", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        coroutineContext.cancelChildren()
    }

    /**
     * 外部收口（通知按钮那一下）让在途的悬浮等待**立即**取消：互斥门不被拖满确认窗口，
     * 提升的下一问不被拖住；被收口那一问的调用方拿到收口方 resolve 的答案。
     */
    @Test
    fun externalSettlementCancelsTheInFlightWaitPromptly() = runBlocking {
        val overlay = LiveOverlay()
        val q = queue(overlay, scope = this)
        val r1 = prompt("args-1")
        val r2 = prompt("args-2")

        val first = async(start = CoroutineStart.UNDISPATCHED) { q.present(r1) }
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        assertEquals(InterlockChoice.AWAITING, q.present(r2))

        // 通知按钮那一下：收口 （撤卡 → 取消在途等待）、提升 、 的卡当场挂上。
        assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, q.pendingSnapshot().displayed!!.id))
        assertEquals("args-2", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        assertEquals("被收口一问的调用方拿到答案而非干等确认窗口", InterlockChoice.ALLOW_ONCE, first.await())
        assertEquals(2, overlay.presentCalls.get())
        coroutineContext.cancelChildren()
    }

    /** 快速作答也留得下 via=overlay：上屏回调先于用户点击，对账裁决带在屏真值。 */
    @Test
    fun fastAnswerStillLogsTheViaOverlayEmission() = runBlocking {
        val overlay = LiveOverlay()
        val routing = mutableListOf<InterlockQueue.Presentation>()
        val q = queue(overlay, routing = routing, scope = this)
        val first = async(start = CoroutineStart.UNDISPATCHED) { q.present(prompt("args-1")) }
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })

        overlay.answers.send(InterlockChoice.ALLOW_ONCE)
        assertEquals(InterlockChoice.ALLOW_ONCE, first.await())
        assertEquals(
            "上屏对账裁决一次（在屏真值），快速作答也不缺 via=overlay",
            1,
            routing.count { it.via == InterlockChannel.OVERLAY && it.overlayPresented },
        )
    }

    /**
     * 被留下的卡上迟到的点击：答案进缓冲供重试取走，同时槽位收口、下一问提升并挂卡
     * ——「卡已消失但槽位占着」的窗口不存在。
     */
    @Test
    fun parkedClickClosesTheSlotAndPromotesTheNextQuestion() = runBlocking {
        val overlay = LiveOverlay()
        var otherCard = true
        val q = queue(overlay, otherCard = { otherCard }, scope = this)
        val r1 = prompt("args-1")
        val r2 = prompt("args-2")

        // 走通知（提问卡占屏），调用方让开；提问卡撤走后改判悬浮，兜底把卡挂上。
        assertEquals(InterlockChoice.AWAITING, q.present(r1))
        otherCard = false
        q.reroute()
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        // 排队。
        assertEquals(InterlockChoice.AWAITING, q.present(r2))

        // 用户在  卡上点允许：迟到一答进缓冲，槽位收口， 提升并当场挂卡。
        overlay.answers.send(InterlockChoice.ALLOW_ONCE)
        assertEquals("args-2", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        // 的重试直接取缓冲里的答案，不重挂卡。
        assertEquals(InterlockChoice.ALLOW_ONCE, q.present(r1))
        assertEquals(2, overlay.presentCalls.get())
        coroutineContext.cancelChildren()
    }

    /** 清场（stop 路径） resolves 仍在等的调用方并撤卡；之后的新呈现照常可用，无残留。 */
    @Test
    fun cancelAllResolvesTheCallerAndLaterPresentStillWorks() = runBlocking {
        val overlay = LiveOverlay()
        val q = queue(overlay, scope = this)
        val first = async(start = CoroutineStart.UNDISPATCHED) { q.present(prompt("args-1")) }
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })

        q.cancelAll()
        assertEquals("清场对仍在等的调用方是 DENY", InterlockChoice.DENY, first.await())
        assertFalse(overlay.isPresented())

        // 清场后的新呈现照常走悬浮。
        val second = async(start = CoroutineStart.UNDISPATCHED) { q.present(prompt("args-2")) }
        assertEquals("args-2", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        overlay.answers.send(InterlockChoice.ALLOW_ONCE)
        assertEquals(InterlockChoice.ALLOW_ONCE, second.await())
    }

    /**
     * BLOCKER：兜底先挂卡、调用方在门上等； 被答完并提升  后，
     * 的调用方进门只认领**自己那一问**的答案（peekAnswered）， 的卡由它自己的
     * 兜底排程挂上—— 的调用方绝不替  挂卡/等答案，一次点击只答一问。
     */
    @Test
    fun callerClaimsOnlyItsOwnAnswerAndNeverPresentsTheNextQuestion() = runBlocking {
        val overlay = LiveOverlay()
        var otherCard = true
        var notifyOk = true
        val q = queue(overlay, notifyOk = { notifyOk }, otherCard = { otherCard }, scope = this)
        val r1 = prompt("args-1")
        val r2 = prompt("args-2")

        // 走通知（提问卡占屏），调用方让开；提问卡撤走、通知被关，改判悬浮。
        assertEquals(InterlockChoice.AWAITING, q.present(r1))
        otherCard = false
        notifyOk = false
        q.reroute()
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })

        // 排队； 的重试调用方在门上等兜底。
        assertEquals(InterlockChoice.AWAITING, q.present(r2))
        val caller = async(start = CoroutineStart.UNDISPATCHED) { q.present(r1) }
        yield()
        assertEquals(1, overlay.presentCalls.get())

        // 被答完并提升： 的卡由它自己的兜底排程挂上。
        overlay.answers.send(InterlockChoice.ALLOW_ONCE)
        assertEquals("R1 调用方只拿到 R1 的答案", InterlockChoice.ALLOW_ONCE, caller.await())
        assertEquals("args-2", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        assertEquals("R1 调用方不替 R2 挂卡", 2, overlay.presentCalls.get())

        // 仍是独立可答的一问：没有被 调用方的点击收口。
        assertTrue(q.resolveCurrent(InterlockChoice.DENY, q.pendingSnapshot().displayed!!.id))
        assertNull(q.pendingSnapshot().displayed)
        coroutineContext.cancelChildren()
    }

    /**
     * 后台 + 通知被关时，外部收口 resolve 的答案必须原样到达调用方
     * （present 返回 UNABLE 后先 peekAnswered），而不是被 `E_GATE_NO_FOREGROUND` 覆盖。
     */
    @Test
    fun externallyResolvedAnswerReachesTheCallerInsteadOfNoSurface() = runBlocking {
        val overlay = LiveOverlay()
        val q = queue(overlay, notifyOk = { false }, scope = this)
        val r1 = prompt("args-1")

        // 通知被关：悬浮仍可用（单驱动），调用方挂卡等答案。
        val caller = async(start = CoroutineStart.UNDISPATCHED) { q.present(r1) }
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })

        // 外部收口（页内卡那一下）：resolve + 撤卡 → 在途挂卡等待被取消返回 UNABLE，
        // 但 resolve 的答案必须先被认领，不被 UNABLE/E_GATE_NO_FOREGROUND 覆盖。
        assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, q.pendingSnapshot().displayed!!.id))
        assertEquals(InterlockChoice.ALLOW_ONCE, caller.await())
        assertNull(q.pendingSnapshot().displayed)
    }

    /** 同上，过期路径：expireIfCurrent resolve 的 EXPIRED 原样到达调用方。 */
    @Test
    fun externallyExpiredAnswerReachesTheCallerInsteadOfNoSurface() = runBlocking {
        val overlay = LiveOverlay()
        val q = queue(overlay, notifyOk = { false }, scope = this)
        val r1 = prompt("args-1")

        val caller = async(start = CoroutineStart.UNDISPATCHED) { q.present(r1) }
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })

        val id = q.pendingSnapshot().displayed!!.id
        assertTrue(q.expireIfCurrent(id))
        assertEquals("过期结论原样到达调用方", InterlockChoice.EXPIRED, caller.await())
        assertNull(q.pendingSnapshot().displayed)
    }

    /**
     * 悬浮卡自己的 answerBudget 到点自回 **EXPIRED**——真实结局原样
     * 交回调用方（broker 映射 TimedOut / E_GATE_APPROVAL_TIMEOUT），不得被改写成
     * UNABLE / E_GATE_NO_FOREGROUND。外部 `expireIfCurrent` 仍走原路（另一用例覆盖）。
     */
    @Test
    fun overlayBudgetExpiryReturnsExpiredInsteadOfNoSurface() = runBlocking {
        val overlay = LiveOverlay()
        overlay.answerBudgetMs = 100L
        val q = queue(overlay, scope = this)
        assertEquals(
            "悬浮到点是 TimedOut 语义，不是无路",
            InterlockChoice.EXPIRED,
            q.present(prompt("args-1")),
        )
        assertNull(q.pendingSnapshot().displayed)
    }

    /** AWAITING 出口同样先认领——外部收口恰落在 park 夹缝时答案不被覆盖。 */
    @Test
    fun awaitingExitStillClaimsAnAnswerResolvedInTheGap() = runBlocking {
        val overlay = LiveOverlay()
        overlay.parkNext = true
        val hold = CompletableDeferred<Unit>()
        overlay.parkHold = hold
        val q = queue(overlay, scope = this)
        val r1 = prompt("args-1")

        val caller = async(start = CoroutineStart.UNDISPATCHED) { q.present(r1) }
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        // 外部收口恰落在「present 即将回还在等」的夹缝里：先 resolve，再放行 fake。
        assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, q.pendingSnapshot().displayed!!.id))
        hold.complete(Unit)
        assertEquals(
            "夹缝里 resolve 的答案不被 AWAITING 覆盖",
            InterlockChoice.ALLOW_ONCE,
            caller.await(),
        )
        coroutineContext.cancelChildren()
    }

    /**
     * 兜底先挂卡、调用方在门上等；卡自己的 budget 到点自回 EXPIRED。
     * EXPIRED 必须落到 Request 上让门上的调用方经 peekAnswered 认领（broker 映射
     * TimedOut / E_GATE_APPROVAL_TIMEOUT），而不是让它拿到 UNABLE / NO_FOREGROUND；
     * 调用方也绝不替任何后续问题挂卡。
     */
    @Test
    fun ensureDrivenBudgetExpiryReachesTheGateWaitingCaller() = runBlocking {
        val overlay = LiveOverlay()
        var otherCard = true
        var notifyOk = true
        val q = queue(overlay, notifyOk = { notifyOk }, otherCard = { otherCard }, scope = this)
        val r1 = prompt("args-1")

        // 走通知（提问卡占屏），调用方让开；随后撤走提问卡、关通知，改判悬浮——
        // 兜底把卡挂上，卡自己的窗口只有 100ms（到点自回 EXPIRED 的场景）。
        assertEquals(InterlockChoice.AWAITING, q.present(r1))
        otherCard = false
        notifyOk = false
        overlay.answerBudgetMs = 100L
        q.reroute()
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })

        // 重试调用方在门上等兜底（此刻卡还在 budget 窗口内）。
        val caller = async(start = CoroutineStart.UNDISPATCHED) { q.present(r1) }
        yield()
        assertEquals(1, overlay.presentCalls.get())

        // budget 到点：卡自回 EXPIRED，结论落到 Request——门上的调用方经 peekAnswered
        // 认领到 EXPIRED（broker 映射 TimedOut / E_GATE_APPROVAL_TIMEOUT），
        // 而不是 UNABLE / E_GATE_NO_FOREGROUND；也不替任何后续问题挂卡。
        assertEquals(
            "EXPIRED 原样到达调用方（TimedOut 语义）",
            InterlockChoice.EXPIRED,
            withTimeout(5_000) { caller.await() },
        )
        assertEquals(1, overlay.presentCalls.get())
        assertNull(q.pendingSnapshot().displayed)
        coroutineContext.cancelChildren()
    }

    /**
     * 挂卡前（dispatch/建窗之前）被外部收口——不挂 ghost 卡，
     * 调用方按改判结果落回通知/页内等待。
     */
    @Test
    fun cancelledBeforeShowLeavesNoGhostCard() = runBlocking {
        val overlay = LiveOverlay()
        val gate = CompletableDeferred<Unit>()
        overlay.preShowGate = gate
        val q = queue(overlay, scope = this)
        val r1 = prompt("args-1")

        val caller = async(start = CoroutineStart.UNDISPATCHED) { q.present(r1) }
        yield()
        // 调用方停在挂卡前：此刻外部收口（撤通道/清场同路）取消。
        overlay.cancelCurrent()
        assertEquals("挂卡前取消：无卡可收", InterlockChoice.AWAITING, caller.await())
        assertFalse("无 ghost 卡", overlay.isPresented())
        assertTrue(overlay.shown.tryReceive().isFailure)
        coroutineContext.cancelChildren()
    }

    /**
     * 真实 parked 路径（present 回「还在等」、点击走 onParkedChoice 回调）：
     * awaiting=false 的迟到一答进缓冲，槽位收口、下一问提升并当场挂卡。
     */
    @Test
    fun parkedChoiceCallbackClosesTheSlotAndBuffersTheAnswer() = runBlocking {
        val overlay = LiveOverlay()
        var otherCard = true
        val q = queue(overlay, otherCard = { otherCard }, scope = this)
        val r1 = prompt("args-1")
        val r2 = prompt("args-2")

        overlay.parkNext = true
        // 走通知（提问卡占屏）；提问卡撤走后改判悬浮，兜底把卡挂上并被「留下」。
        assertEquals(InterlockChoice.AWAITING, q.present(r1))
        otherCard = false
        q.reroute()
        assertEquals("args-1", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        assertEquals(InterlockChoice.AWAITING, q.present(r2))

        // 被留下的卡上点击：迟到一答进缓冲，槽位收口， 提升并当场挂卡。
        overlay.clickParked(InterlockChoice.ALLOW_ONCE)
        assertEquals("args-2", withTimeout(5_000) { overlay.shown.receive().argsIdentity })
        assertEquals("重试直接取缓冲答案，不重挂卡", InterlockChoice.ALLOW_ONCE, q.present(r1))
        assertEquals(2, overlay.presentCalls.get())
        coroutineContext.cancelChildren()
    }
}
