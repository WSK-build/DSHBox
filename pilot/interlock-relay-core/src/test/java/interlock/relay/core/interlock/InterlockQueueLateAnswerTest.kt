package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调用让开通道之后，用户才点下的那一下确认。
 *
 * 单次调用只为等人点让出几秒，到点先回「还在等」。那之后页内框、悬浮卡与通知栏三条通路
 * 上的点击都必须能被下一次同参数的重试取走：不然用户的「允许」白点，而「拒绝」会被绕成
 * 第二次询问。
 */
class InterlockQueueLateAnswerTest {

    /** 单调时钟由用例推着走：JVM 里没有 `SystemClock`，而答案的作废时刻要能算得准。 */
    private var clock = 1_000L

    private fun prompt(
        capability: CapabilityId = CapabilityId.CLIPBOARD_READ,
        target: String? = null,
        detail: String? = null,
        identity: String? = null,
        volatile: Boolean = false,
        session: Boolean = false,
    ) = ApprovalPrompt(
        capability = capability,
        // 身份默认与显示文字无关：真要比的是参数。各用例里凡按「对象/明细不同」
        // 断言不接的，都显式给出不同的 identity，否则测的是这个构造器而不是判据。
        argsIdentity = identity ?: SAME_ARGS,
        volatileTarget = volatile,
        titleRes = 0,
        summaryRes = 0,
        targetLabel = target,
        risk = RiskLevel.MEDIUM,
        callIndexInSession = 1,
        paramDetail = detail,
        allowsSessionGrant = session,
        deadlineAtMs = 61_000L,
    )

    /** 三条通路都不可用时的队列：present 会直接回「问不了」，用它来单看这一格缓冲的取用规则。 */
    private fun queue() = InterlockQueue(
        isForegroundCapable = { false },
        canNotify = { false },
        now = { clock },
        inlineWaitMs = 20L,
    )

    @Test
    fun retryTakesTheAnswerGivenAfterTheCallerWalkedAway() = runBlocking {
        val q = queue()
        val asked = prompt()
        q.recordLateAnswer(asked, InterlockChoice.ALLOW_ONCE)
        assertEquals(InterlockChoice.ALLOW_ONCE, q.present(asked))
    }

    @Test
    fun theStoredAnswerIsSpentOnlyOnce() = runBlocking {
        val q = queue()
        val asked = prompt()
        q.recordLateAnswer(asked, InterlockChoice.ALLOW_ONCE)
        assertEquals(InterlockChoice.ALLOW_ONCE, q.present(asked))
        assertEquals("同一条同意不能批两次调用", InterlockChoice.UNABLE_TO_SHOW, q.present(asked))
    }

    @Test
    fun anotherQuestionCannotSpendIt() = runBlocking {
        val q = queue()
        val asked = prompt()
        q.recordLateAnswer(asked, InterlockChoice.DENY)
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, q.present(prompt(capability = CapabilityId.PKG_INSTALL)))
        assertEquals(InterlockChoice.DENY, q.present(asked))
    }

    /**
     * 一张没人答、自己到点收掉的卡，不能替用户认下这一次超时。
     *
     * EXPIRED / AWAITING / UNABLE_TO_SHOW 是通路状态不是答复：存进缓冲之后，下一次同参数
     * 重试会立刻拿到一个"用户从未按过的超时"，而那时屏上根本没有过一张框。
     */
    @Test
    fun aCardThatExpiredUnansweredDoesNotAnswerTheNextRetry() = runBlocking {
        val q = queue()
        val asked = prompt()
        q.recordLateAnswer(asked, InterlockChoice.EXPIRED)
        q.recordLateAnswer(asked, InterlockChoice.AWAITING)
        q.recordLateAnswer(asked, InterlockChoice.UNABLE_TO_SHOW)
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, q.present(asked))
    }

    @Test
    fun cancelAllDropsAPendingAnswer() = runBlocking {
        val q = queue()
        val asked = prompt()
        q.recordLateAnswer(asked, InterlockChoice.ALLOW_ONCE)
        q.cancelAll()
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, q.present(asked))
    }

    @Test
    fun aSessionGrantCannotBeSpentOnACallThatMustAskEveryTime() = runBlocking {
        val q = queue()
        val asked = prompt(session = true)
        q.recordLateAnswer(asked, InterlockChoice.ALLOW_SESSION)
        // 落在系统授权界面上的调用没有会话档，也不该吃到别人给的那一份
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, q.present(prompt(session = false)))
        assertEquals(InterlockChoice.ALLOW_SESSION, q.present(asked))
    }

    @Test
    fun aSessionGrantStoredForAnAskOnlyTargetIsDowngradedOnTheSpot() = runBlocking {
        val q = queue()
        val asked = prompt(session = false)
        q.recordLateAnswer(asked, InterlockChoice.ALLOW_SESSION)
        assertEquals("呈现侧漏判时，落缓冲这一步也要降级", InterlockChoice.ALLOW_ONCE, q.present(asked))
    }

    /**
     * 目标换一次屏就指别人的那种调用，迟到那一答不进缓冲。
     *
     * `nodeId` 是快照里的前序下标，下一次调用时它可能已经是别的控件。
     * 但这一条**有会话档**可退（用户点「本会话内允许」就不会再被问），所以丢掉那一答之后
     * 下一次仍然按"重新问一张"走 —— 把它落成终态会让一次正常的慢抬手变成硬失败。
     */
    @Test
    fun anAnswerAboutANodeIdTargetIsNotBufferedButCanStillBeAskedAgain() = runBlocking {
        val q = queue()
        val asked = prompt(target = "nodeId=8", volatile = true, session = true)
        q.recordLateAnswer(asked, InterlockChoice.ALLOW_ONCE)
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, q.present(asked))
    }

    /**
     * 既不认迟到答案、又没有会话档可退的那几条（`clip.read`/`loc.read`/`audio.capture`/
     * `media.write`/`app.install` 与落在系统授权界面上的调用）：丢掉那一答之后，下一次重试
     * 要拿到**终态**，而不是重新问一张。
     *
     * 不这么做就会形成一个没有上界的问环：抬手慢的人每一轮都答了、每一轮都不算数，而助手
     * 看到 `E_AWAITING_CONSENT` 是"可重试"，会一直重试下去。
     */
    @Test
    fun aDiscardedAnswerOnAnAskOnlyVolatileTargetEndsTheQuestion() = runBlocking {
        val q = queue()
        val asked = prompt(volatile = true, session = false)
        q.recordLateAnswer(asked, InterlockChoice.ALLOW_ONCE)
        assertEquals("这一问要到此为止，交回用户重新发起", InterlockChoice.EXPIRED, q.present(asked))
        assertEquals("终态只花一次：再往后的调用该正常重新问", InterlockChoice.UNABLE_TO_SHOW, q.present(asked))
    }

    /** 用户答的是「拒绝」也一样：这一问结束，不能靠重试把它绕成第二次询问。 */
    @Test
    fun aDiscardedDenyEndsTheQuestionToo() = runBlocking {
        val q = queue()
        val asked = prompt(volatile = true, session = false)
        q.recordLateAnswer(asked, InterlockChoice.DENY)
        assertEquals(InterlockChoice.EXPIRED, q.present(asked))
    }

    /** 到点自己收的那张卡没有抬手，不该留下任何判决。 */
    @Test
    fun anUnansweredExpiryLeavesNoTerminalVerdict() = runBlocking {
        val q = queue()
        val asked = prompt(volatile = true, session = false)
        q.recordLateAnswer(asked, InterlockChoice.EXPIRED)
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, q.present(asked))
    }

    /**
     * 有人在等的时候答上，答案只归那一次调用。
     *
     * 「有没有人在等」这个判断与「挂上待决项」必须在同一步里完成，否则点击正好落在两者之间
     * 会既唤醒这次调用、又往缓冲里存一份 —— 一次点击批了两次执行。
     */
    @Test
    fun aLiveAwaitIsNotAlsoStoredForTheNextCall() = runBlocking {
        val shown = java.util.concurrent.CountDownLatch(1)
        val q = InterlockQueue(
            isForegroundCapable = { true },
            onRouting = { if (it.request != null) shown.countDown() },
            canNotify = { false },
            now = { clock },
            inlineWaitMs = 1_500L,
        )
        val asked = prompt()
        val first = async(start = CoroutineStart.UNDISPATCHED) { q.present(asked) }
        assertTrue("确认框没挂上", shown.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE))
        assertEquals(InterlockChoice.ALLOW_ONCE, first.await())
        assertEquals(
            "唤醒等的那一次之后，同一答不该再被下一次调用花掉",
            InterlockChoice.AWAITING,
            q.present(asked),
        )
    }
}
