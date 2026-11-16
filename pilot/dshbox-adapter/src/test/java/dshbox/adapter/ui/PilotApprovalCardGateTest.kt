package dshbox.adapter.ui

import interlock.relay.core.interlock.ApprovalPrompt
import interlock.relay.core.interlock.InterlockChannel
import interlock.relay.core.interlock.RiskLevel
import interlock.relay.core.protocol.CapabilityId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页内卡的呈现门禁：`ApprovalDialog` 只在队列裁决把这一问摊到页内
 * （routing.via == PAGE）且编号与裁决里那张待决项一致时才画。悬浮卡在屏（OVERLAY）
 * 或通知存在（NOTIFICATION）的时候，同一件事在屏上只能有一处可点 —— 界面消费裁决的
 * 这半段由 [PilotViewModel.approvalCardVisible] 钉住，路由矩阵本身在 core 的
 * InterlockQueuePresentationMatrixTest 覆盖。
 */
class PilotApprovalCardGateTest {

    private fun prompt() = ApprovalPrompt(
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
        deadlineAtMs = 60_000L,
    )

    /** 裁决摊到页内且编号配对：画。 */
    @Test
    fun pageRouteShowsTheCard() {
        assertTrue(PilotViewModel.approvalCardVisible(prompt(), InterlockChannel.PAGE, 7, 7))
    }

    /** 悬浮卡在屏：页内不画，同一件事不许有两处可点。 */
    @Test
    fun overlayRouteDoesNotShowTheCard() {
        assertFalse(PilotViewModel.approvalCardVisible(prompt(), InterlockChannel.OVERLAY, 7, 7))
    }

    /** 通知存在：页内不画；用户从通知点进本页后，裁决改到 PAGE 这张卡才出现。 */
    @Test
    fun notificationRouteDoesNotShowTheCard() {
        assertFalse(PilotViewModel.approvalCardVisible(prompt(), InterlockChannel.NOTIFICATION, 7, 7))
    }

    /** 谁也问不到（NONE）：没有待决项的呈现，更没有页内卡。 */
    @Test
    fun noneRouteDoesNotShowTheCard() {
        assertFalse(PilotViewModel.approvalCardVisible(prompt(), InterlockChannel.NONE, 7, 7))
    }

    /** 没有待决项：路由无论是什么都不画。 */
    @Test
    fun noPendingPromptShowsNothing() {
        assertFalse(PilotViewModel.approvalCardVisible(null, InterlockChannel.PAGE, null, 7))
    }

    /**
     * pending 与 routing 两条流的更新各自到达，combine 的某一次配对可能是
     * 「新框 + 旧裁决」：编号对不上时那一帧宁可不画，等两条流对齐。
     */
    @Test
    fun unpairedRequestIdDoesNotShowTheCard() {
        assertFalse(PilotViewModel.approvalCardVisible(prompt(), InterlockChannel.PAGE, 7, 8))
        assertFalse(PilotViewModel.approvalCardVisible(prompt(), InterlockChannel.PAGE, null, 7))
    }
}
