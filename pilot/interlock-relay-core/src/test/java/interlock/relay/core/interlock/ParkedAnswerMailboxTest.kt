package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 悬浮确认卡「被留下」之后那一下点击的去向。
 *
 * 单次调用只为等人点让出几秒，到点先回「还在等」而卡片留在屏上。用户随后点的
 * 「允许 / 拒绝」必须能被下一次同参数的重试取走，否则那次点击等于白点：助手会
 * 再问一遍，而「拒绝」被绕成第二次询问。
 */
class ParkedAnswerMailboxTest {

    private var clock = 1_000L

    private val mailbox = ParkedAnswerMailbox(now = { clock })

    private fun prompt(
        capability: CapabilityId = CapabilityId.SCREEN_CAPTURE,
        target: String? = "com.android.deskclock",
        detail: String? = null,
        identity: String? = null,
        volatile: Boolean = false,
        deadline: Long = 61_000L,
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
        allowsSessionGrant = true,
        deadlineAtMs = deadline,
    )

    @Test
    fun answerSurvivesTheHandoffAndIsTakenOnce() {
        val asked = prompt()
        mailbox.put(asked, InterlockChoice.ALLOW_ONCE, asked.deadlineAtMs)
        assertEquals(InterlockChoice.ALLOW_ONCE, mailbox.take(prompt()))
        assertNull("取走即清，第三条调用不该再吃到同一次同意", mailbox.take(prompt()))
    }

    @Test
    fun aDifferentQuestionDoesNotConsumeTheAnswer() {
        val asked = prompt()
        mailbox.put(asked, InterlockChoice.DENY, asked.deadlineAtMs)
        assertNull(mailbox.take(prompt(capability = CapabilityId.PKG_INSTALL)))
        assertNull(mailbox.take(prompt(target = "com.android.contacts", identity = "args-contacts")))
        assertNull(mailbox.take(prompt(detail = "seconds=5", identity = "args-seconds")))
        assertEquals("问的不是同一件事时原答案要留着", InterlockChoice.DENY, mailbox.take(prompt()))
    }

    @Test
    fun answerExpiresWithTheCardThatTookIt() {
        val asked = prompt()
        mailbox.put(asked, InterlockChoice.ALLOW_SESSION, asked.deadlineAtMs)
        clock = asked.deadlineAtMs
        assertNull("卡片自己的可答窗口过了，答案不能再批新问题", mailbox.take(prompt()))
    }

    @Test
    fun clearingDropsAPendingAnswer() {
        val asked = prompt()
        mailbox.put(asked, InterlockChoice.ALLOW_ONCE, asked.deadlineAtMs)
        mailbox.clear()
        assertNull(mailbox.take(prompt()))
    }

    @Test
    fun concurrentTakesYieldExactlyOneWinner() {
        val asked = prompt()
        mailbox.put(asked, InterlockChoice.ALLOW_ONCE, asked.deadlineAtMs)
        val start = java.util.concurrent.CountDownLatch(1)
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val takers = (1..8).map {
            Thread {
                start.await()
                if (mailbox.take(prompt()) != null) winners.incrementAndGet()
            }.also { it.start() }
        }
        start.countDown()
        takers.forEach { it.join() }
        assertEquals("一次点击不能被两条调用各花一遍", 1, winners.get())
    }
}
