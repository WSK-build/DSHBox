package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.TierCeiling
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话授权的免弹语义：界面上的「本会话内允许」必须真的换来一次免弹。
 * 会话授权若排在 `tier == ASK` 之后判，按钮答完下一次照样弹，这个按钮就是无效的。
 */
class RequiresApprovalTest {

    private fun ask(ceiling: TierCeiling, tier: AccessTier, session: Boolean) =
        InterlockGate.requiresApproval(tier, ceiling, session)

    @Test
    fun `ask only ceiling always asks even with a session grant`() {
        assertTrue(ask(TierCeiling.ASK_ONLY, AccessTier.ASK, false))
        assertTrue(ask(TierCeiling.ASK_ONLY, AccessTier.ALWAYS, true))
    }

    @Test
    fun `session grant silences ask tier for the rest of the session`() {
        assertFalse(ask(TierCeiling.SESSION_ONLY, AccessTier.ASK, true))
        assertFalse(ask(TierCeiling.ANY, AccessTier.ASK, true))
    }

    @Test
    fun `without a session grant the tier decides`() {
        assertTrue(ask(TierCeiling.ANY, AccessTier.ASK, false))
        assertFalse(ask(TierCeiling.ANY, AccessTier.ALWAYS, false))
    }

    /**
     * 系统授权界面一律当场问：会话授权压不住它，「完全访问」这一档也压不住。
     *
     * 这条判据决定助手能否替用户答复第三方应用的权限申请，三种档位都要钉住：
     * 少钉一种，就留下「档位是完全访问于是授权框被静默点掉」这条路。
     */
    @Test
    fun `consent screen always asks whatever the tier and the session say`() {
        for (ceiling in TierCeiling.values()) {
            for (tier in AccessTier.values()) {
                for (session in listOf(false, true)) {
                    assertTrue(
                        "$ceiling/$tier/$session",
                        InterlockGate.requiresApproval(tier, ceiling, session, consentScreen = true),
                    )
                }
            }
        }
        // 反向也要钉住：不命中这条判据时，上面几条规则不该被它带偏。
        assertFalse(InterlockGate.requiresApproval(AccessTier.ALWAYS, TierCeiling.ANY, true, consentScreen = false))
    }

    /** 上限为 SESSION_ONLY 时连档位「始终允许」也只能换到本会话，缺授权就要弹一次。 */
    @Test
    fun `session only ceiling cannot be permanently silenced`() {
        assertTrue(ask(TierCeiling.SESSION_ONLY, AccessTier.ALWAYS, false))
        assertFalse(ask(TierCeiling.SESSION_ONLY, AccessTier.ALWAYS, true))
    }
}
