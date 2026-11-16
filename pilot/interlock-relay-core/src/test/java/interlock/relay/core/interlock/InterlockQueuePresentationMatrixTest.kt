package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三路呈现矩阵：页内（PAGE）/ 悬浮（OVERLAY）/ 通知（NOTIFICATION）怎么交接，
 * 以及贯穿全部交接的那一条不变量 —— **同一件审批在屏上永远只有一处可答**。
 *
 * 修正后悬浮那条路不再有任何平台级提前跳过（可信虚拟屏在不在跑都不构成跳过理由）：
 * 能不能用只由呈现器自己的事实决定 —— 有授权、本问没试砸过、屏上没有别家的卡；
 * 挂上了却拿不到焦点（被平台藏掉）由呈现器回 [InterlockChoice.UNABLE_TO_SHOW] 当场改判。
 * 测试里没有「可信屏」这个概念可模拟，因为队列里已经没有任何判据会读它。
 */
class InterlockQueuePresentationMatrixTest {

    /** 假的悬浮通路：能报在屏、能报授权、能报失焦、能报被留下那张上的点击。 */
    private class FakeOverlay(var granted: Boolean = true) : OverlayApprovalChannel {
        var answer: InterlockChoice = InterlockChoice.AWAITING
        var presentCalls = 0
        var withdrawCalls = 0
        private var onScreen = false

        override fun canShow(): Boolean = granted

        override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
            presentCalls++
            if (answer == InterlockChoice.UNABLE_TO_SHOW) return InterlockChoice.UNABLE_TO_SHOW
            onScreen = true
            return answer
        }

        override fun isPresented(): Boolean = onScreen

        override fun withdraw() {
            withdrawCalls++
            onScreen = false
        }

        override fun cancelCurrent() {}

        override var onParkedChoice: ((ApprovalPrompt, InterlockChoice) -> Unit)? = null
        override var onHidden: (() -> Unit)? = null
        override var onPresented: ((ApprovalPrompt) -> Unit)? = null

        /** 窗还存在却看不见了（被平台跨屏藏掉 / 焦点被夺走）：4 秒无焦点的同一场景。 */
        fun reportHidden() {
            onScreen = false
            onHidden?.invoke()
        }
    }

    private var now = 1_000L
    private var foreground = false
    private var notifyOk = true
    private var otherCard = false
    private val overlay = FakeOverlay()

    /** 模拟装配根消费者（rerouteApprovalPresentation 的通知半边）：非 NOTIFICATION 一律收。 */
    private var notificationShown = false

    /** 贯穿全部用例的不变量违反记录；每个用例结束断言为空。 */
    private val violations = mutableListOf<String>()

    /** 悬浮被跳过时的点名记录（at: reasons）， 的断言对象。 */
    private val skipped = mutableListOf<String>()

    private val routing = mutableListOf<InterlockQueue.Presentation>()

    /** 兜底挂卡的排程记录（接受时记下工作体，供用例手动驱动或断言排程发生）。 */
    private val ensureWorks = mutableListOf<suspend () -> Unit>()

    private fun queue(
        inlineWaitMs: Long = 1L,
        /** false = launcher 拒收（作用域不在的模拟）：排出的兜底工作无人执行。 */
        acceptEnsure: Boolean = true,
    ) = InterlockQueue(
        isForegroundCapable = { foreground },
        onRouting = ::applyPresentation,
        overlay = overlay,
        overlayEnsureLauncher = { work -> if (acceptEnsure) ensureWorks.add(work) },
        canNotify = { notifyOk },
        otherCardOnScreen = { otherCard },
        onOverlaySkipped = { _, at, reasons -> skipped.add("$at: $reasons") },
        now = { now },
        inlineWaitMs = inlineWaitMs,
    )

    /**
     * 消费者语义 + 唯一可答入口不变量。与 RelayContainer.rerouteApprovalPresentation 的
     * 通知半边同规：NOTIFICATION 挂，PAGE / OVERLAY / NONE 收。每来一次裁决就查一遍：
     * 悬浮在场、通知存在、页内卡（via=PAGE）三者不得同时有多于一个成立。
     */
    private fun applyPresentation(p: InterlockQueue.Presentation) {
        routing.add(p)
        notificationShown = p.via == InterlockChannel.NOTIFICATION
        val entries = listOf(overlay.isPresented(), notificationShown) +
            listOf(p.via == InterlockChannel.PAGE)
        if (entries.count { it } > 1) {
            violations.add("via=${p.via} 时出现多个可答入口：overlay=${overlay.isPresented()} notify=$notificationShown")
        }
    }

    private fun prompt(ttlMs: Long = 60_000L) = ApprovalPrompt(
        capability = CapabilityId.SECURE_SETTINGS,
        argsIdentity = "args-1",
        volatileTarget = false,
        titleRes = 0,
        summaryRes = 0,
        targetLabel = "secure/window_animation_scale",
        risk = RiskLevel.HIGH,
        callIndexInSession = 1,
        paramDetail = null,
        allowsSessionGrant = true,
        deadlineAtMs = now + ttlMs,
    )

    private fun lastVia() = routing.last().via

    /**
     * 全链路交接：通知 → 悬浮 → 页内 → 通知，每一步只留一个可答入口。
     *
     * 「悬浮接管时撤通知」在队列这层的契约是：改判的裁决原样发出去，消费者按它收通知；
     * 这里把消费者语义一并模拟，交接的每一帧都过一遍不变量。
     */
    @Test
    fun routeHandoversKeepExactlyOneAnswerableEntry() = runBlocking {
        val q = queue()
        overlay.granted = false
        val asked = prompt()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { q.present(asked) }
        while (routing.isEmpty()) yield()
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())

        // 悬浮拿到授权：改判 OVERLAY，消费者收通知；等待环的下一次重发把卡真挂上。
        overlay.granted = true
        q.reroute()
        assertEquals(InterlockChannel.OVERLAY, lastVia())
        assertEquals(InterlockChoice.AWAITING, q.present(asked))
        assertTrue("悬浮接管后卡在屏上", overlay.isPresented())

        // 助手页到前台：改判 PAGE，悬浮卡先收（消费者收通知的同一帧），页内卡接手。
        foreground = true
        q.reroute()
        assertEquals(InterlockChannel.PAGE, lastVia())
        assertFalse("改判页内时悬浮卡必须已收", overlay.isPresented())

        // 回到后台且悬浮授权被撤：改判通知，页内卡随之消失（via 不再是 PAGE）。
        foreground = false
        overlay.granted = false
        q.reroute()
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())

        assertEquals(InterlockChoice.AWAITING, waiting.await())
        assertTrue("交接全程不得出现双入口：$violations", violations.isEmpty())
    }

    /**
     * 悬浮只要自己够格就该被尝试：授权在、没试砸过、屏上没有别家的卡，判据到此为止。
     *
     * 修正前装配根会以「可信虚拟屏在跑」为由整条跳过悬浮，把一条可能看得见的通路
     * 换成通知栏 —— 用户只能去通知栏里找。现在队列里不存在任何这样的入口。
     */
    @Test
    fun overlayIsAttemptedOnItsOwnMeritsWithoutAnyPlatformSkip() = runBlocking {
        val q = queue()
        assertEquals(InterlockChoice.AWAITING, q.present(prompt()))
        assertEquals(InterlockChannel.OVERLAY, lastVia())
        assertEquals("悬浮真的被挂了一次", 1, overlay.presentCalls)
        assertTrue(overlay.isPresented())
        assertEquals("悬浮在场期间通知一条都不该有", 0, routing.count { it.via == InterlockChannel.NOTIFICATION })
        assertTrue(violations.isEmpty())
    }

    /**
     * 挂上了却没上屏（UNABLE_TO_SHOW，含 4 秒无焦点）：同一张 Request 原地改判通知，
     * 不重开授权、不重问一遍。
     */
    @Test
    fun unableToShowDegradesToNotificationForTheSameRequest() = runBlocking {
        val q = queue()
        overlay.answer = InterlockChoice.UNABLE_TO_SHOW
        val asked = prompt()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { q.present(asked) }
        while (routing.size < 2) yield()
        assertEquals(InterlockChannel.OVERLAY, routing[0].via)
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
        assertEquals("回落不改建档：还是同一张 Request", routing[0].request, routing[1].request)
        assertEquals("悬浮只试一次", 1, overlay.presentCalls)
        assertFalse("试砸的卡不许留在屏上", overlay.isPresented())
        assertTrue(violations.isEmpty())
        assertEquals(InterlockChoice.AWAITING, waiting.await())
    }

    /** 焦点丢失（4 秒无焦点的另一场景）走的是同一条改判：收卡、走通知。 */
    @Test
    fun focusLossDegradesToNotificationAndWithdrawsTheCard() = runBlocking {
        val q = queue()
        val asked = prompt()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { q.present(asked) }
        while (routing.isEmpty()) yield()
        assertEquals(InterlockChannel.OVERLAY, lastVia())
        val withdrawsBefore = overlay.withdrawCalls

        overlay.reportHidden()

        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
        // 事件侧无条件收卡一次 + 改判对账再收一次（幂等）：不能依赖回调时 isPresented 已被清。
        assertEquals("改判前先收悬浮卡", withdrawsBefore + 2, overlay.withdrawCalls)
        assertTrue(violations.isEmpty())
        assertEquals(InterlockChoice.AWAITING, waiting.await())
    }

    /**
     * 复用展示位的重试把卡挂上悬浮后，旧通知必须撤（BLOCKER-1）。
     *
     * 提问卡占屏时首问走通知；提问卡撤走后等待环重发同一问——复用 pending 不发新裁决，
     * 若挂上悬浮后不对账，装配根还记着通知裁决：通知不撤，页内改判时悬浮也不撤，
     * 同一件事两处可点。
     */
    @Test
    fun reusedPendingHungOnOverlayWithdrawsTheStaleNotification() = runBlocking {
        val q = queue()
        otherCard = true
        val asked = prompt()
        assertEquals(InterlockChoice.AWAITING, q.present(asked))
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
        assertTrue(notificationShown)

        // 提问卡撤走；等待环重发同一问：复用 pending，acquire 不发裁决。
        otherCard = false
        assertEquals(InterlockChoice.AWAITING, q.present(asked))

        assertEquals("挂上悬浮后实际呈现要对账", InterlockChannel.OVERLAY, lastVia())
        assertTrue(overlay.isPresented())
        assertFalse("悬浮接管后旧通知必须撤", notificationShown)
        assertTrue(violations.isEmpty())
    }

    /**
     * 页内改判悬浮的交接（BLOCKER-2）：reroute 只发裁决不够，兜底挂卡必须当场排上，
     * 跑完卡真的在屏、再发一次对账裁决——用户面前不出现"裁决说在悬浮、屏上却没有卡"
     * 的空窗，也不等下一次重试。
     */
    @Test
    fun pageToOverlayHandoverHangsTheCardWithoutWaitingForARetry() = runBlocking {
        val q = queue()
        foreground = true
        assertEquals(InterlockChoice.AWAITING, q.present(prompt()))
        assertEquals(InterlockChannel.PAGE, lastVia())

        // 用户离开助手页：裁决改到悬浮。
        foreground = false
        q.reroute()
        assertEquals(InterlockChannel.OVERLAY, lastVia())
        assertFalse("裁决先行的瞬间卡还没挂", overlay.isPresented())
        assertEquals("兜底挂卡必须当场排上", 1, ensureWorks.size)

        // 兜底执行：卡挂上，带「悬浮在屏」真值的对账裁决随之发出。
        ensureWorks.single()()
        assertTrue("兜底把卡真的挂上了", overlay.isPresented())
        assertEquals(InterlockChannel.OVERLAY, lastVia())
        assertTrue(
            "对账裁决带在屏真值",
            routing.last().overlayPresented,
        )
        assertFalse("交接全程通知是撤着的", notificationShown)
        assertTrue(violations.isEmpty())
    }

    /**
     * parked 稳态的重发不刷对账（-2）：等待环每 500ms 重发同一问，
     * 每次都会走一遍悬浮通路并拿到「还在等」——裁决键含「悬浮在屏」维后，
     * 只有裁决先行（在屏=false）与卡真的挂上（在屏=true）各发一次，重发被去重滤掉。
     */
    @Test
    fun parkedReRepresentsDoNotRepostThePresenterEmission() = runBlocking {
        val q = queue()
        val asked = prompt()
        assertEquals(InterlockChoice.AWAITING, q.present(asked))
        assertEquals(
            "裁决先行 + 挂上对账各一次",
            1,
            routing.count { it.via == InterlockChannel.OVERLAY && it.overlayPresented },
        )

        repeat(4) { assertEquals(InterlockChoice.AWAITING, q.present(asked)) }
        assertEquals(
            "parked 重发不重复发对账裁决（重复的 via=overlay 日志由此而来）",
            1,
            routing.count { it.via == InterlockChannel.OVERLAY && it.overlayPresented },
        )
        assertTrue(violations.isEmpty())
    }

    /**
     * launcher 拒收（作用域不在的模拟）：排出的兜底工作无人执行，调用方驱动的挂卡
     * 路径照常可用，且后续每一问都能再次排程——单驱动模型下没有「在途」标记可卡死。
     */
    @Test
    fun refusedEnsureDoesNotStallCallerDrivenPresentation() = runBlocking {
        val q = queue(acceptEnsure = false)
        assertEquals(InterlockChoice.AWAITING, q.present(prompt()))
        assertEquals(InterlockChannel.OVERLAY, lastVia())
        assertTrue(overlay.isPresented())

        assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE))
        assertEquals(InterlockChoice.AWAITING, q.present(prompt()))
        assertEquals("第二问的悬浮照常尝试", InterlockChannel.OVERLAY, lastVia())
        assertEquals(2, overlay.presentCalls)
        assertTrue(violations.isEmpty())
    }

    /**
     * 悬浮被跳过时必须留 readable 的原因：路由改走通知要写"为什么没尝试
     * 悬浮"，三条路全断连建档都没有的那一次也要留痕。
     */
    @Test
    fun overlaySkipsLeaveAReadableReasonOnTheRecord() = runBlocking {
        val q = queue()
        overlay.granted = false
        assertEquals(InterlockChoice.AWAITING, q.present(prompt()))
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
        assertTrue(
            "路由层要点名悬浮让位的原因：$skipped",
            skipped.any { it.startsWith("route:") && it.contains("not granted") },
        )

        notifyOk = false
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, q.present(prompt()))
        assertTrue(
            "谁也问不到的那一次也要留痕：$skipped",
            skipped.any { it.startsWith("no-route:") && it.contains("not granted") },
        )
    }

    /** 答复是终局：全部呈现者一起收，第二处点不出第二个结论。 */
    @Test
    fun anAnswerRevokesEveryEntryAtOnce() = runBlocking {
        val q = queue(inlineWaitMs = 5_000L)
        // 走通知通路：调用方这一趟真的在等（悬浮通路的「卡被留下」不占通道）。
        overlay.granted = false
        val asked = prompt()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { q.present(asked) }
        while (q.pendingSnapshot().displayed == null) yield()
        val id = q.pendingSnapshot().displayed!!.id

        assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, id))
        assertEquals("定案后屏上没有任何可点的东西", InterlockChannel.NONE, lastVia())
        assertFalse(overlay.isPresented())
        assertFalse("定案后通知也必须收", notificationShown)
        assertSame(null, routing.last().request)
        assertEquals(InterlockChoice.ALLOW_ONCE, waiting.await())
        assertTrue(violations.isEmpty())
    }
}
