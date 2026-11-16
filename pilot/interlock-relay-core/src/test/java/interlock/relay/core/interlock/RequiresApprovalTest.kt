package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三档档位的审批语义与「本会话内允许」的免弹效果：档位是唯一判定输入，
 * 描述符上的上限元数据不参与。会话授权若排在 `tier == ASK` 之后判，按钮答完
 * 下一次照样弹，那个按钮就无效了。
 */
class RequiresApprovalTest {

    private fun ask(tier: AccessTier, session: Boolean) =
        InterlockGate.requiresApproval(tier, session)

    @Test
    fun `session grant silences ask tier for the rest of the session`() {
        assertFalse(ask(AccessTier.ASK, true))
    }

    @Test
    fun `without a session grant the ask tier asks and full access does not`() {
        assertTrue(ask(AccessTier.ASK, false))
        assertFalse(ask(AccessTier.ALWAYS, false))
        assertFalse(ask(AccessTier.ALWAYS, true))
    }

    /**
     * 弹不弹只由档位与会话授权两个输入决定：三档 × 有无会话授权的逐格期望。
     * 审批没有档位之外的例外输入——多一个输入就多一条绕开档位的路。
     */
    @Test
    fun `tier and session are the only approval inputs`() {
        for (tier in AccessTier.values()) {
            for (session in listOf(false, true)) {
                assertEquals(
                    "$tier/$session",
                    tier == AccessTier.ASK && !session,
                    InterlockGate.requiresApproval(tier, session),
                )
            }
        }
    }

    /** 审批策略与判定同表派生：三档各对应一个稳定的机器值，供能力清单输出。 */
    @Test
    fun approvalPolicyFollowsTheTierAlone() {
        assertEquals("denied", InterlockGate.approvalPolicy(AccessTier.DENIED))
        assertEquals("ask_each_call", InterlockGate.approvalPolicy(AccessTier.ASK))
        assertEquals("none", InterlockGate.approvalPolicy(AccessTier.ALWAYS))
    }
}
