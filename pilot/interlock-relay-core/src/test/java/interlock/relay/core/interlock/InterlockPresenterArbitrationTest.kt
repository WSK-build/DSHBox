package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唯一活动呈现者的裁决。
 *
 * 这一组用例盯的是用户唯一能感知的承诺：**同一件审批在屏上永远只有一处可点**。
 * 判定落在 [InterlockQueue]，呈现器是假的，因此整条链路能在纯 JVM 下被穷举 ——
 * 真悬浮窗要 Context 与 WindowManager，而「四条路怎么挑、挑错了怎么回落、
 * 改判时先撤谁」与窗口本身无关。
 */
class InterlockPresenterArbitrationTest {

    /** 假的悬浮通路：能报在屏、能报授权、能报失焦、能报被留下那张上的点击。 */
    private class FakeOverlay(var granted: Boolean = true) : OverlayApprovalChannel {
        /** present() 该回什么。UNABLE_TO_SHOW 用来造"窗挂上了却没上屏"那种回落。 */
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

        override var onParkedChoice: ((ApprovalPrompt, InterlockChoice) -> Unit)? = null
        override var onHidden: (() -> Unit)? = null

        /** 窗还挂着却看不见了：被系统跨屏藏掉，或被别的可获焦窗盖过焦点。 */
        fun reportHidden() {
            onScreen = false
            onHidden?.invoke()
        }

        /** 被留下的那张卡上，用户抬手点了一下。 */
        fun parkedClick(prompt: ApprovalPrompt, choice: InterlockChoice) {
            onParkedChoice?.invoke(prompt, choice)
        }
    }

    private var now = 1_000L
    private var foreground = false
    private var blind = false
    private var notifyOk = true
    private val overlay = FakeOverlay()
    private val routing = mutableListOf<InterlockQueue.Presentation>()
    private val revoked = mutableListOf<Int>()

    private fun queue(inlineWaitMs: Long = 1L) = InterlockQueue(
        isForegroundCapable = { foreground },
        onRouting = { routing.add(it) },
        overlay = overlay,
        overlayBlind = { blind },
        canNotify = { notifyOk },
        onRevokePresenters = { revoked.add(it.id) },
        now = { now },
        inlineWaitMs = inlineWaitMs,
    )

    private fun prompt(
        capability: CapabilityId = CapabilityId.SECURE_SETTINGS,
        identity: String? = null,
        ttlMs: Long = 60_000L,
    ) = ApprovalPrompt(
        capability = capability,
        argsIdentity = identity ?: "args-1",
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

    // ---------------------------------------------------------------- 四态裁决

    /**
     * 四条路的优先级一次钉住：页内 > 悬浮 > 通知 > 谁也问不到。
     *
     * 顺序即「离用户正在看的东西最近」：助手页在前台时页内卡最直接，它就在屏幕上，
     * 不需要任何额外动作。两条路同时可点时用户分不清自己点的是哪一件事，助手那边也会
     * 收到两次答复，所以顺序不能是别的。
     */
    @Test
    fun pageWinsOverOverlayWhichWinsOverNotification() {
        val q = queue()
        blind = true
        runBlocking { assertEquals(InterlockChoice.AWAITING, q.present(prompt())) }
        assertEquals("只剩通知这条路时走通知", InterlockChannel.NOTIFICATION, lastVia())
        assertEquals("悬浮有授权且没被盲掉时走悬浮", InterlockChannel.OVERLAY, q.let {
            blind = false
            q.reroute()
            lastVia()
        })
        assertEquals("助手页在前台时页内最直接", InterlockChannel.PAGE, q.let {
            foreground = true
            q.reroute()
            lastVia()
        })
    }

    /**
     * 三条都不成立时如实回"问不了"，并且把挡住路的两条都点名出来。
     *
     * 只回一句"没前台"的话，用户既不知道该去开通知，也不知道自己正开着后台模式。
     */
    @Test
    fun noRouteAtAllNamesBothSwitches() {
        val q = queue()
        foreground = false
        overlay.granted = false
        notifyOk = false
        blind = true
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, runBlocking { q.present(prompt()) })
        val reason = q.lastDeadEndReason()
        assertTrue("应点名通知开关：$reason", reason!!.contains("notifications"))
        assertTrue("应点名后台模式：$reason", reason.contains("background mode"))
        // 没有待决项，因此不发任何呈现指令，也不撤销谁。
        assertTrue(routing.isEmpty())
    }

    /** 只被通知挡路时只点名那一条，不把不成立的猜测也写进回包。 */
    @Test
    fun deadEndReasonNamesOnlyWhatActuallyBlocks() {
        val q = queue()
        overlay.granted = false
        notifyOk = false
        blind = false
        runBlocking { q.present(prompt()) }
        val reason = q.lastDeadEndReason()!!
        assertTrue(reason.contains("notifications"))
        assertFalse("后台模式没在跑就不该提它：$reason", reason.contains("background mode"))
    }

    // ------------------------------------------------------------ 切换即撤销

    /**
     * 改判离开悬浮通路时先收卡，再发新指令。
     *
     * 顺序反过来就会出现"裁决已经说该走通知了，屏上还压着一张悬浮卡"，那一瞬屏上有两处
     * 可点，正是这一层要消灭的局面。
     */
    @Test
    fun leavingOverlayWithdrawsTheCardFirst() {
        val q = queue()
        runBlocking { q.present(prompt()) }
        assertEquals(InterlockChannel.OVERLAY, lastVia())
        assertTrue(overlay.isPresented())
        val withdrawsBefore = overlay.withdrawCalls

        blind = true
        q.reroute()

        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
        assertEquals("改判时必须先收悬浮卡", withdrawsBefore + 1, overlay.withdrawCalls)
        assertFalse("屏上不许还留着悬浮卡", overlay.isPresented())
    }

    /** 通知 ↔ 页内之间改判不涉及悬浮卡，但呈现者仍然只留一个。 */
    @Test
    fun notificationYieldsToPageAndBack() {
        val q = queue()
        blind = true
        runBlocking { q.present(prompt()) }
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())

        foreground = true
        q.reroute()
        assertEquals(InterlockChannel.PAGE, lastVia())

        foreground = false
        q.reroute()
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
    }

    /** 悬浮卡在屏期间（含被留下）不许同时挂通知：两处可点就是两次答复。 */
    @Test
    fun overlayOnScreenMeansNoNotificationWasEverAsked() {
        val q = queue()
        runBlocking { q.present(prompt()) }
        assertEquals(InterlockChannel.OVERLAY, lastVia())
        assertTrue(
            "悬浮在场期间不该发过任何通知裁决",
            routing.none { it.via == InterlockChannel.NOTIFICATION },
        )
    }

    // ------------------------------------------------------- 悬浮失败与失焦回落

    /**
     * 窗挂上了却没上屏：这一问仍是同一张 Request，不重开授权，改走通知栏。
     *
     * "优先"不等于"只此一条"。原地重试只会再挂一张同样看不见的窗。
     */
    @Test
    fun overlayFailureFallsBackToNotificationWithoutReprompting() {
        val q = queue()
        overlay.answer = InterlockChoice.UNABLE_TO_SHOW
        runBlocking { assertEquals(InterlockChoice.AWAITING, q.present(prompt())) }
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
        assertEquals("同一问不该因为回落而另开一张", 1, overlay.presentCalls)

        // 试砸过一次之后不该又回头找悬浮：条件没变，裁决也得稳。
        blind = false
        q.reroute()
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
        assertEquals(1, overlay.presentCalls)
    }

    /**
     * 失焦改判幂等：同一条信号报几次，裁决只迁移一次。
     *
     * 界面失焦在屏幕上会发生很多次（切应用、弹输入法、来电）。每次都发一次呈现指令，
     * 通知就会被反复重投，而"没迁移"这件事在日志里也成了噪音。
     */
    @Test
    fun repeatedHiddenReportsRerouteOnlyOnce() {
        val q = queue()
        runBlocking { q.present(prompt()) }
        assertEquals(InterlockChannel.OVERLAY, lastVia())

        overlay.reportHidden()
        val emissionsAfterFirst = routing.size
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
        assertTrue("改判必须先收卡", !overlay.isPresented())

        repeat(5) { overlay.reportHidden() }
        q.reroute()
        assertEquals("同一条信号不产生重复迁移", emissionsAfterFirst, routing.size)
        assertEquals(InterlockChannel.NOTIFICATION, lastVia())
    }

    // ------------------------------------------------------------ 答复终局性

    /**
     * 任何入口的答复都是终局：撤销全部呈现者，之后第二处点不出第二个结论。
     */
    @Test
    fun answerRevokesEveryPresenterAndOnlyCountsOnce() {
        val q = queue()
        blind = true
        runBlocking { q.present(prompt()) }
        val id = q.pendingSnapshot().displayed!!.id

        assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, id))
        assertEquals(listOf(id), revoked)
        assertNull("定案后待决项清空", routing.last().request)
        assertFalse("第二次点同一件事不算数", q.resolveCurrent(InterlockChoice.DENY, id))
        assertEquals(1, revoked.size)
    }

    /**
     * 定案之后，被留下又被收掉的悬浮卡上那次慢抬手，不许进迟到缓冲。
     *
     * 进了缓冲，下一次同参数重试会替用户认下一个他从未点过的结论 —— "拒绝"尤其危险。
     */
    @Test
    fun aParkedClickAfterTheDecisionIsNotBuffered() {
        val q = queue()
        blind = true
        val asked = prompt()
        runBlocking { q.present(asked) }
        assertTrue(q.resolveCurrent(InterlockChoice.DENY, q.pendingSnapshot().displayed!!.id))

        overlay.parkedClick(asked, InterlockChoice.ALLOW_ONCE)

        // 重试看到的是一张全新的框（回"还在等"），不是上一次那个被偷偷记下的同意。
        assertEquals(InterlockChoice.AWAITING, runBlocking { q.present(asked) })
    }

    /**
     * 悬浮卡被留下时那一下点击要真的唤回正在等的那一次调用，不能蒸发。
     *
     * 这条修复前只走缓冲：等待环让开通道之后，用户的这一答就没人认了。
     */
    @Test
    fun aParkedClickReachesTheCallerStillWaiting() = runBlocking {
        val q = queue(inlineWaitMs = 5_000L)
        // 走通知通路：调用方这一趟真的在等（悬浮通路回的是"卡被留下"，不占通道）。
        blind = true
        val asked = prompt()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { q.present(asked) }
        while (q.pendingSnapshot().displayed == null) yield()
        // 被留下那张悬浮卡上的点击：呈现器把它交回队列，再由队列交给还在等的那一次调用。
        overlay.parkedClick(asked, InterlockChoice.ALLOW_ONCE)
        assertEquals(InterlockChoice.ALLOW_ONCE, waiting.await())
    }

    // ------------------------------------------------------------ 通知不重投

    /**
     * 等待环每 500ms 重发同一问，那几轮不许再投递一次通知。
     *
     * 每次重投用户刚要划掉就又弹回来。而"让那条通知重新出现"是裁决迁移事件的责任。
     */
    @Test
    fun theSameQuestionIsNeverPostedTwice() {
        val q = queue(inlineWaitMs = 1L)
        blind = true
        val asked = prompt()
        repeat(4) { runBlocking { assertEquals(InterlockChoice.AWAITING, q.present(asked)) } }
        assertEquals(
            "同一问的呈现指令只发一次",
            1,
            routing.count { it.via == InterlockChannel.NOTIFICATION },
        )
    }

    /** 用户划掉通知之后没有新的迁移事件，就不该再自动弹回来。 */
    @Test
    fun swipingTheNotificationAwayDoesNotBringItBack() {
        val q = queue()
        blind = true
        runBlocking { q.present(prompt()) }
        val before = routing.size
        repeat(3) { q.reroute() }
        assertEquals("划掉之后没有迁移就不重投", before, routing.size)
    }

    // ---------------------------------------------------------------- 到点收敛

    /**
     * 通知到点 = 实体关闭 + 撤销全部呈现者，而不是只把通知收掉。
     *
     * 只收通知而留着待决项的话，后面每一件不同的调用都会被挡成"还在等"，而屏上没有任何
     * 可答的东西。
     */
    @Test
    fun expiryClosesTheEntityAndRevokesEveryone() = runBlocking {
        val q = queue(inlineWaitMs = 5_000L)
        // 走通知通路，调用方这一趟真的在等：通知到点要能把这一趟一起收成 EXPIRED。
        blind = true
        val asked = prompt(ttlMs = 5_000L)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { q.present(asked) }
        while (q.pendingSnapshot().displayed == null) yield()
        val id = q.pendingSnapshot().displayed!!.id

        now += 6_000L
        assertTrue(q.expireIfCurrent(id))
        assertEquals(listOf(id), revoked)
        assertNull(q.pendingSnapshot().displayed)
        assertNull(routing.last().request)
        assertEquals(InterlockChoice.EXPIRED, waiting.await())
    }

    /** 过点的那张不能挡住后面的问题：到点收掉之后展示位让给下一件。 */
    @Test
    fun anExpiredRequestDoesNotBlockTheNext() {
        val q = queue()
        val first = prompt(identity = "args-1", ttlMs = 100L)
        runBlocking { q.present(first) }
        val firstId = q.pendingSnapshot().displayed!!.id

        now += 5_000L
        runBlocking { q.present(prompt(identity = "args-2")) }
        assertNotEquals(firstId, q.pendingSnapshot().displayed!!.id)
    }

    // ------------------------------------------------------------ 排队可见性

    /** 界面要读的那一格：展示位上那一张，加上排在它后面的件数。 */
    @Test
    fun snapshotCountsTheWaitingQueue() {
        val q = queue()
        runBlocking { q.present(prompt(identity = "args-1")) }
        assertEquals(0, q.pendingSnapshot().waitingCount)
        runBlocking { q.present(prompt(identity = "args-2")) }
        runBlocking { q.present(prompt(identity = "args-3")) }
        val snapshot = q.pendingSnapshot()
        assertEquals("args-1", snapshot.displayed!!.prompt.argsIdentity)
        assertEquals(2, snapshot.waitingCount)
        assertEquals(2, routing.last().waitingCount)

        // 展示位上的答完，下一件顶上来，排队少一个。
        assertTrue(q.resolveCurrent(InterlockChoice.DENY, snapshot.displayed!!.id))
        val after = q.pendingSnapshot()
        assertEquals("args-2", after.displayed!!.prompt.argsIdentity)
        assertEquals(1, after.waitingCount)
    }

    /** 队列满时当场回"还没轮到"，不无限积压。 */
    @Test
    fun aFullQueueAsksTheCallerToComeBack() {
        val q = queue()
        runBlocking { q.present(prompt(identity = "args-1")) }
        runBlocking { q.present(prompt(identity = "args-2")) }
        runBlocking { q.present(prompt(identity = "args-3")) }
        assertEquals(InterlockChoice.WAITING_TURN, runBlocking { q.present(prompt(identity = "args-4")) })
        assertEquals(2, q.pendingSnapshot().waitingCount)
    }

    /** 全体清场：待决项与等待项一起收，且都记成"已有结论"，免得迟到点击又活过来。 */
    @Test
    fun cancelAllClosesTheDisplaySlotAndTheQueue() {
        val q = queue()
        val first = prompt(identity = "args-1")
        runBlocking { q.present(first) }
        runBlocking { q.present(prompt(identity = "args-2")) }
        q.cancelAll()
        assertNull(q.pendingSnapshot().displayed)
        assertEquals(0, q.pendingSnapshot().waitingCount)
        assertSame(null, routing.last().request)
        assertTrue(revoked.size >= 2)
        assertFalse(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, 1))
    }
}
