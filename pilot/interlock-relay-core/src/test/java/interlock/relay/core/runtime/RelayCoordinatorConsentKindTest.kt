package interlock.relay.core.runtime

import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.RelayResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回包「卡在哪一种许可」的归类表。本模块自己的审批（档位与确认框、含等答复与挂起取消）
 * 与 Android 系统的采集同意是两种许可，混在一句错误文案里，调用方就分不清下一步该提示
 * 「助手规则不许」还是「系统弹框还没点」。这张表是回包成稿两个出口共用的唯一事实源，
 * 穷举验证防止后来新增的闸门错误码漏标。
 */
class RelayCoordinatorConsentKindTest {

    @Test
    fun consentKindLiteralsAreProtocolStable() {
        assertEquals("relay_policy", RelayResponse.CONSENT_KIND_RELAY_POLICY)
        assertEquals("android_projection", RelayResponse.CONSENT_KIND_ANDROID_PROJECTION)
    }

    @Test
    fun gateAndParkFailuresAreRelayPolicyConsent() {
        listOf(
            // 确认框还挂着、这一次没等到人
            RelayError.GATE_AWAITING_CONSENT,
            // 三条呈现通路都问不到人
            RelayError.GATE_NO_FOREGROUND,
            // 等待队列也满了，这一问没排上位置
            RelayError.GATE_WAITING_TURN,
            // 挂起等待期间被取消：可证未执行，但拦下的仍是本模块的这道许可
            RelayError.GATE_CANCELLED,
        ).forEach { error ->
            assertEquals(
                "$error 应归类为本模块自己的许可",
                RelayResponse.CONSENT_KIND_RELAY_POLICY,
                consentKindFor(error),
            )
        }
    }

    @Test
    fun otherFailuresStayUnclassified() {
        assertNull(consentKindFor(null))
        // 用户明确拒绝：不是「等许可」而是「不许」，不该混进等许可的归类
        assertNull(consentKindFor(RelayError.GATE_HOST_DENIED))
        // 系统采集弹框那一步由执行面在自己的失败出口标注 android_projection，
        // 错误码本身不预标：执行面没走到那一步时这里不能替它说话
        assertNull(consentKindFor(RelayError.SURFACE_SYSTEM_CONSENT_REQUIRED))
        assertNull(consentKindFor(RelayError.INTERNAL))
        assertNull(consentKindFor(RelayError.RATE_LIMITED))
        assertNull(consentKindFor(RelayError.TRANSPORT_TIMEOUT))
        assertNull(consentKindFor(RelayError.QUEUE_FULL))
    }
}
