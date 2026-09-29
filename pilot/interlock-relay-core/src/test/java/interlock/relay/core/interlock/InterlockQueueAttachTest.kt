package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一次待答的确认框能不能被下一次调用接上。
 *
 * 确认框不再占用调用方之后，重试必须接上**同一件事**的那张框：接错一张等于把用户对
 * 「读通讯录」的同意当成对「装应用」的同意，因此要按能力、对象与参数明细三者匹配。
 */
class InterlockQueueAttachTest {

    private fun prompt(
        capability: CapabilityId = CapabilityId.CONTACT_READ,
        target: String? = "3 个联系人",
        detail: String? = null,
        identity: String? = null,
        volatile: Boolean = false,
    ) = ApprovalPrompt(
        capability = capability,
        // 身份默认与显示文字无关：真要比的是参数。各用例里凡按「对象/明细不同」
        // 断言不接的，都显式给出不同的 identity，否则测的是这个构造器而不是判据。
        argsIdentity = identity ?: SAME_ARGS,
        volatileTarget = volatile,
        titleRes = 0,
        summaryRes = 0,
        targetLabel = target,
        risk = RiskLevel.LOW,
        callIndexInSession = 1,
        paramDetail = detail,
        allowsSessionGrant = false,
        deadlineAtMs = 60_000L,
    )

    private fun requestOf(prompt: ApprovalPrompt) =
        InterlockQueue.Request(1, prompt, CompletableDeferred())

    @Test
    fun sameCapabilityTargetAndDetailAttaches() {
        val pending = requestOf(prompt())
        assertTrue(pending.matches(prompt()))
    }

    @Test
    fun differentCapabilityNeverAttaches() {
        val pending = requestOf(prompt(capability = CapabilityId.CONTACT_READ))
        assertFalse(pending.matches(prompt(capability = CapabilityId.CONTACT_WRITE)))
    }

    @Test
    fun differentTargetNeverAttaches() {
        val pending = requestOf(prompt(target = "3 个联系人", identity = "args-3"))
        assertFalse(pending.matches(prompt(target = "全部联系人", identity = "args-all")))
    }

    @Test
    fun differentParamDetailNeverAttaches() {
        val pending = requestOf(prompt(detail = "key=brightness value=5", identity = "args-5"))
        assertFalse(pending.matches(prompt(detail = "key=brightness value=9", identity = "args-9")))
    }

    /** 空值也要分得开：一边有明细、一边没有，是两件事。 */
    @Test
    fun nullAndEmptyDetailAreDifferentRequests() {
        val pending = requestOf(prompt(detail = null, identity = "args-none"))
        assertFalse(pending.matches(prompt(detail = "text=abc", identity = "args-text")))
        assertTrue(
            requestOf(prompt(detail = null, identity = "args-none"))
                .matches(prompt(detail = null, identity = "args-none")),
        )
    }

    /**
     * 屏上那两行字相同，不代表问的是同一件事。
     *
     * 目标与明细为呈现截过长度（逐值 24、整串 80），只差在尾巴上的两个长目标会截成
     * 同一句人话。判据取整份参数的摘要，截断不会把两件事并成一件。
     */
    @Test
    fun sameTruncatedLabelButDifferentArgsNeverAttaches() {
        val shown = "com.example." + "a".repeat(90)
        val pending = requestOf(prompt(target = shown, identity = "args-first"))
        assertFalse(pending.matches(prompt(target = shown, identity = "args-second")))
    }

    // 通路本身（悬浮窗 → 通知栏 → 界面内确认框的挑选顺序）在 InterlockQueueRoutingTest 覆盖：
    // 那一段要读单调时钟，由测试注入时钟与等待上限。
}
