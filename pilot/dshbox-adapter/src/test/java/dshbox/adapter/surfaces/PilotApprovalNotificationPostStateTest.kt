package dshbox.adapter.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 通知呈现状态的状态机。
 *
 * 硬事实只有一条：通知是投递出去的，「有没有被用户看见」应用侧拿不到任何证据。
 * 所以挂成功最多到 POSTED_VISIBILITY_UNVERIFIED，POSTED 只留给能观察到呈现的那一天；
 * 状态机里任何一条转换都不得产出「已证实可见」。
 */
class PilotApprovalNotificationPostStateTest {

    private fun eligibility(
        broken: Boolean = false,
        cooldownElapsed: Boolean = true,
        notificationsEnabled: Boolean = true,
        channelMuted: Boolean = false,
        systemPresent: Boolean = true,
    ) = PostEligibility(
        broken = broken,
        cooldownElapsed = cooldownElapsed,
        notificationsEnabled = notificationsEnabled,
        channelMuted = channelMuted,
        systemPresent = systemPresent,
    )

    /** 全开的渠道：够格挂，但这只是「可以投递」，不是「已经挂上」。 */
    @Test
    fun aFullyOpenChannelIsEligible() {
        assertEquals(
            PilotApprovalNotification.PostState.ELIGIBLE,
            postStateAfterEligibility(eligibility()),
        )
    }

    /** 刚失败过的通路被闩住：退烧间隔没过就不许再当成可用。 */
    @Test
    fun aFreshFailureLatchesThePathShut() {
        assertEquals(
            PilotApprovalNotification.PostState.UNAVAILABLE,
            postStateAfterEligibility(eligibility(broken = true, cooldownElapsed = false)),
        )
    }

    /** 退烧间隔过了，闩放开，资格照常重判。 */
    @Test
    fun theFailureLatchCoolsDown() {
        assertEquals(
            PilotApprovalNotification.PostState.ELIGIBLE,
            postStateAfterEligibility(eligibility(broken = true, cooldownElapsed = true)),
        )
    }

    /** 总开关、单独的渠道静音、系统服务缺失，缺任何一环都问不到人。 */
    @Test
    fun everyMissingGateMeansUnavailable() {
        assertEquals(
            PilotApprovalNotification.PostState.UNAVAILABLE,
            postStateAfterEligibility(eligibility(notificationsEnabled = false)),
        )
        assertEquals(
            PilotApprovalNotification.PostState.UNAVAILABLE,
            postStateAfterEligibility(eligibility(channelMuted = true)),
        )
        assertEquals(
            PilotApprovalNotification.PostState.UNAVAILABLE,
            postStateAfterEligibility(eligibility(systemPresent = false)),
        )
    }

    /** 投递的两种结局：挂上算「已投递、可见性未证实」，挂失败算「问不到人」。 */
    @Test
    fun aSuccessfulPostNeverClaimsVisibility() {
        assertEquals(
            PilotApprovalNotification.PostState.POSTED_VISIBILITY_UNVERIFIED,
            postStateAfterPost(true),
        )
        assertEquals(
            PilotApprovalNotification.PostState.UNAVAILABLE,
            postStateAfterPost(false),
        )
    }

    /**
     * POSTED 是保留档：穷举资格判定的全部组合与投递的两种结局，没有任何一条
     * 转换能产出它。它只留给将来真的拿到可观察呈现证据的那一天。
     */
    @Test
    fun noTransitionEverProducesTheProvenVisibilityState() {
        val states = mutableListOf<PilotApprovalNotification.PostState>()
        for (broken in listOf(false, true)) {
            for (cooldownElapsed in listOf(false, true)) {
                for (notificationsEnabled in listOf(false, true)) {
                    for (channelMuted in listOf(false, true)) {
                        for (systemPresent in listOf(false, true)) {
                            states += postStateAfterEligibility(
                                eligibility(
                                    broken = broken,
                                    cooldownElapsed = cooldownElapsed,
                                    notificationsEnabled = notificationsEnabled,
                                    channelMuted = channelMuted,
                                    systemPresent = systemPresent,
                                ),
                            )
                        }
                    }
                }
            }
        }
        states += postStateAfterPost(true)
        states += postStateAfterPost(false)
        assertNotEquals(emptyList<PilotApprovalNotification.PostState>(), states)
        org.junit.Assert.assertFalse(
            "通知通路到不了「已证实可见」这一档",
            states.contains(PilotApprovalNotification.PostState.POSTED),
        )
    }
}
