package interlock.relay.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 重放分类表是「断回包后同参数重发安全吗」的唯一判据。一格填错，要么把一次
 * 已发生的点击放行成可重放（重复执行），要么把一条纯查询拦下来问人（可用性损失）。
 * 这里抽查每一类，并钉住「表外一律 USER_DECIDE」这条兜底。
 */
class EffectSemanticsTest {

    @Test
    fun pureQueriesAreSafeToReplay() {
        assertEquals(RetryPolicy.SAFE_RETRY, retryPolicyOf(CapabilityId.PKG_QUERY))
        assertEquals(RetryPolicy.SAFE_RETRY, retryPolicyOf(CapabilityId.UI_SNAPSHOT))
        assertEquals(RetryPolicy.SAFE_RETRY, retryPolicyOf(CapabilityId.UI_NODE))
        // 等待本身没有动作：条件没等到就再等一轮，不会改变任何状态。
        assertEquals(RetryPolicy.SAFE_RETRY, retryPolicyOf(CapabilityId.UI_WAIT_FOR))
    }

    @Test
    fun resourceHeavyReadsAndCheckableWritesQueryFirst() {
        // 重复采集会重复弹系统许可、重复占采集会话。
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.SCREEN_CAPTURE))
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.SCREEN_RECORD))
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.AUDIO_CAPTURE))
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.NOTIFY_READ))
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.LOCATION_READ))
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.MEDIA_READ))
        // 可检查的状态设置：写没写成功可以从读回的状态上分辨。
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.UI_SET_VALUE))
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.SECURE_SETTINGS))
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.APPOPS_SET))
        assertEquals(RetryPolicy.QUERY_FIRST, retryPolicyOf(CapabilityId.CLIPBOARD_WRITE))
    }

    @Test
    fun nonIdempotentCreationsAndHandoffsGoToTheUser() {
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.CONTACT_WRITE))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.CALENDAR_WRITE))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.MEDIA_WRITE))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.PKG_INSTALL))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.SYS_INTENT))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.APP_LAUNCH))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.APP_STOP))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.SYS_SHELL))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.NOTIFY_POST))
        // UI 动作是一次性操控：重发就是再点一次，无法证实第一次没生效。
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.UI_TAP))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.UI_CLICK))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.UI_TEXT))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.UI_SET_PROGRESS))
    }

    /** 兜底格：没有逐条论证过重放安全的能力一律交用户，不许落进更宽松的档位。 */
    @Test
    fun unlistedCapabilitiesDefaultToUserDecide() {
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.CLIPBOARD_READ))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.CONTACT_READ))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.CALENDAR_READ))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.SURFACE_VIRTUAL))
    }

    /** 兜底档必须比宽松档严：UI 动作这类一次性操控不许因表项遗漏而变成可重放。 */
    @Test
    fun fallbackIsNeverMorePermissiveThanTheExplicitRows() {
        // 兜底是 USER_DECIDE，而表内显式行里最宽的档是 SAFE_RETRY；
        // 抽一条显式 SAFE_RETRY 行与兜底行对照，钉住两者的宽严次序。
        assertEquals(RetryPolicy.SAFE_RETRY, retryPolicyOf(CapabilityId.PKG_QUERY))
        assertEquals(RetryPolicy.USER_DECIDE, retryPolicyOf(CapabilityId.SURFACE_VIRTUAL))
    }

    @Test
    fun effectStateFollowsTheOutcome() {
        // 成功即核实。
        assertEquals(EffectState.VERIFIED, effectStateFor(ok = true, error = null))
        // 超时三兄弟：动作可能已经派发，只能答未知。
        assertEquals(EffectState.UNKNOWN, effectStateFor(ok = false, error = RelayError.TRANSPORT_TIMEOUT))
        assertEquals(EffectState.UNKNOWN, effectStateFor(ok = false, error = RelayError.WAIT_TIMEOUT))
        assertEquals(EffectState.UNKNOWN, effectStateFor(ok = false, error = RelayError.GATE_APPROVAL_TIMEOUT))
        // 其余失败都发生在派发之前。
        assertEquals(EffectState.NOT_STARTED, effectStateFor(ok = false, error = RelayError.NODE_NOT_FOUND))
        assertEquals(EffectState.NOT_STARTED, effectStateFor(ok = false, error = RelayError.GATE_HOST_DENIED))
        // 等确认不算超时类失败：确认框还没人答，动作没开始。
        assertEquals(EffectState.NOT_STARTED, effectStateFor(ok = false, error = RelayError.GATE_AWAITING_CONSENT))
    }
}
