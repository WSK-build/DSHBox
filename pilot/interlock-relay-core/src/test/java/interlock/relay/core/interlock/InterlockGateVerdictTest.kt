package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SystemGrantState
import interlock.relay.core.protocol.TierCeiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 双重校验的纯判定用例。能力清单上的 usable、界面上的红点与真放行必须共用这套结论：
 * 判据在两处各写一遍会产生「能力清单报不可用而宿主其实能执行」，以及「缺系统权限」与
 * 「档位禁止」混成同一个错误码。
 */
class InterlockGateVerdictTest {

    private fun verdict(system: SystemGrantState, tier: AccessTier = AccessTier.ALWAYS) =
        InterlockGate.verdict(system, tier, TierCeiling.ANY)

    @Test
    fun grantedAndSessionConsentBothPassTheSystemCheck() {
        assertNull(verdict(SystemGrantState.GRANTED))
        // SESSION_CONSENT 表示调用可达、只是系统每次要弹自己的框；挡下它等于挡下截屏。
        assertNull(verdict(SystemGrantState.SESSION_CONSENT))
        // 剪贴板那一类没有任何"授予"动作可做（要的是前台与输入焦点），挡下来就是永久拒绝。
        assertNull(verdict(SystemGrantState.FOREGROUND_FOCUS))
    }

    @Test
    fun missingSystemPermissionBlocksAsSystemMissing() {
        assertEquals(RelayError.GATE_SYSTEM_MISSING, verdict(SystemGrantState.MANUAL_ONLY)?.error)
        assertEquals(RelayError.GATE_SYSTEM_MISSING, verdict(SystemGrantState.RUNTIME_ASKABLE)?.error)
    }

    @Test
    fun deviceWithoutTheFeatureSaysSoInsteadOfAskingForPermissions() {
        val result = verdict(SystemGrantState.UNAVAILABLE_ON_DEVICE)
        assertEquals(RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE, result?.error)
        assertEquals("unavailable_on_device", result?.decisionLabel)
    }

    /**
     * 没放行的四种原因是四件不同的事，署名不能都写 `denied`。
     *
     * 尤其这两条：`E_GATE_APPROVAL_TIMEOUT` 是"窗口到点没人答"或"用户答晚了而这一类不认
     * 迟到答案"，写成 denied 会让核对读成用户否决过；`E_GATE_NO_FOREGROUND` 是三条通路都
     * 问不到人，用户根本没见过那张框。
     */
    @Test
    fun eachUnhappyOutcomeIsSignedByWhatActuallyHappened() {
        assertEquals(
            "awaiting_consent",
            InterlockGate.approvalLabel(ApprovalOutcome.AwaitingConsent, RelayError.GATE_AWAITING_CONSENT),
        )
        assertEquals(
            "expired_E_GATE_APPROVAL_TIMEOUT",
            InterlockGate.approvalLabel(ApprovalOutcome.TimedOut, RelayError.GATE_APPROVAL_TIMEOUT),
        )
        assertEquals(
            "no_surface",
            InterlockGate.approvalLabel(ApprovalOutcome.CannotPresent, RelayError.GATE_NO_FOREGROUND),
        )
        assertEquals(
            "denied_" + RelayError.GATE_HOST_DENIED.code,
            InterlockGate.approvalLabel(ApprovalOutcome.Denied, RelayError.GATE_HOST_DENIED),
        )
    }

    @Test
    fun tierDenialIsReportedSeparatelyFromSystemState() {
        val result = verdict(SystemGrantState.GRANTED, tier = AccessTier.DENIED)
        assertEquals(RelayError.GATE_HOST_DENIED, result?.error)
        assertEquals("tier_denied", result?.decisionLabel)
    }

    /**
     * 限流不在纯判定表里：入口防灌与执行配额是调用方的两本账（见 RateLimiter 的两类
     * 阈值来源），权限与档位才是这张表的输入。混进来会让「量太大」盖住「该去开权限」。
     */
    @Test
    fun verdictNeverInvolvesRateLimiting() {
        // 系统权限缺失、档位禁止这些结论不因频率而改变形状——限流拒绝由调用方
        // 以独立的 E_RATE_LIMITED 回答，带自己的 reason，不走这张表。
        assertEquals(RelayError.GATE_SYSTEM_MISSING, verdict(SystemGrantState.MANUAL_ONLY)?.error)
        assertEquals(RelayError.GATE_HOST_DENIED, verdict(SystemGrantState.GRANTED, tier = AccessTier.DENIED)?.error)
    }

    @Test
    fun systemBlocksCoversEveryStateExactlyOnce() {
        SystemGrantState.entries.forEach { state ->
            assertEquals(
                "systemBlocks($state)",
                state == SystemGrantState.RUNTIME_ASKABLE ||
                    state == SystemGrantState.MANUAL_ONLY ||
                    state == SystemGrantState.UNAVAILABLE_ON_DEVICE,
                InterlockGate.systemBlocks(state),
            )
        }
    }

    @Test
    /** 「询问审批」档没有会话授权时必弹；有会话授权后只剩永不免弹那一档还弹。 */
    fun askTierPromptsUntilTheSessionIsGranted() {
        listOf(TierCeiling.ANY, TierCeiling.SESSION_ONLY, TierCeiling.ASK_ONLY).forEach { ceiling ->
            assertTrue(InterlockGate.requiresApproval(AccessTier.ASK, ceiling, sessionGranted = false))
        }
        assertFalse(InterlockGate.requiresApproval(AccessTier.ASK, TierCeiling.ANY, sessionGranted = true))
        assertFalse(InterlockGate.requiresApproval(AccessTier.ASK, TierCeiling.SESSION_ONLY, sessionGranted = true))
        assertTrue(InterlockGate.requiresApproval(AccessTier.ASK, TierCeiling.ASK_ONLY, sessionGranted = true))
    }

    @Test
    fun ceilingDecidesHowLongASessionGrantLasts() {
        assertTrue(InterlockGate.requiresApproval(AccessTier.ALWAYS, TierCeiling.ASK_ONLY, sessionGranted = true))
        assertTrue(InterlockGate.requiresApproval(AccessTier.ALWAYS, TierCeiling.SESSION_ONLY, sessionGranted = false))
        assertFalse(InterlockGate.requiresApproval(AccessTier.ALWAYS, TierCeiling.SESSION_ONLY, sessionGranted = true))
        assertFalse(InterlockGate.requiresApproval(AccessTier.ALWAYS, TierCeiling.ANY, sessionGranted = false))
    }
}
