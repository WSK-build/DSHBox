package interlock.relay.core.runtime

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityState
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.protocol.SystemGrantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「trusted 建屏被系统拒过」在能力清单上的判定（[RelayContainer.trustedDisplayRefusal]）
 * 与该判定对 **degradeReason 顺序**的作用（[RelayContainer.degradeReasonFor]）各场景的
 * 穷举：拒绝只作用于建屏那一条能力、且它**优先于**"模式够不着"——优先级写反，用户被拒后
 * 切了前台，清单就按"切回后台/建屏"给建议，把人送回后台再撞一次拒绝，绕成循环。
 *
 * unsafe（自检判过"建屏破坏主屏"）**不在**本表的任何一格里（复核）：它不降清单行、
 * 不进调用侧 fail-fast，其调用面语义的完整矩阵在 [TrustedDisplayUnsafeTest]。
 */
class TrustedDisplayRefusalTest {

    /** 建屏那条：拒绝标记在即算不可用；标记被清除（建屏成功 / 重连对账屏活着）即恢复。 */
    @Test
    fun refusalAppliesOnlyToTheMarkedSurfaceCapability() {
        assertTrue(RelayContainer.trustedDisplayRefusal(CapabilityId.SURFACE_VIRTUAL, true))
        assertFalse("拒绝标记被清除后能力应恢复可用判据", RelayContainer.trustedDisplayRefusal(CapabilityId.SURFACE_VIRTUAL, false))
    }

    /** 其余能力不建屏：同样的拒绝标记与它们的落地无关。 */
    @Test
    fun otherCapabilitiesAreNeverRefusedByTheDisplayMark() {
        listOf(
            CapabilityId.UI_TAP,
            CapabilityId.SCREEN_CAPTURE,
            CapabilityId.APP_LAUNCH,
            CapabilityId.SCREEN_RECORD,
            CapabilityId.SYS_SHELL,
        ).forEach { id ->
            assertFalse(RelayContainer.trustedDisplayRefusal(id, true))
        }
    }

    /** 被拒时拒绝优先于"模式够不着"：切前台后清单仍说被拒，不再按缺模式回绕。 */
    @Test
    fun refusalBeatsModeRequired() {
        assertEquals(
            RelayError.SURFACE_TRUSTED_DENIED.code,
            RelayContainer.degradeReasonFor(
                systemRefused = true,
                modeRequired = true,
                decisionReason = RelayError.SURFACE_MODE_REQUIRED.code,
            ),
        )
        assertEquals(
            RelayError.SURFACE_TRUSTED_DENIED.code,
            RelayContainer.degradeReasonFor(
                systemRefused = true,
                modeRequired = false,
                decisionReason = null,
            ),
        )
    }

    /** 没被拒时维持原序：缺模式说缺模式，其余沿用裁决给出的原因。 */
    @Test
    fun withoutRefusalThePreviousOrderStands() {
        assertEquals(
            RelayError.SURFACE_MODE_REQUIRED.code,
            RelayContainer.degradeReasonFor(
                systemRefused = false,
                modeRequired = true,
                decisionReason = RelayError.SURFACE_MODE_REQUIRED.code,
            ),
        )
        assertEquals(
            RelayError.SURFACE_UNAVAILABLE.code,
            RelayContainer.degradeReasonFor(
                systemRefused = false,
                modeRequired = false,
                decisionReason = RelayError.SURFACE_UNAVAILABLE.code,
            ),
        )
        assertEquals(
            null,
            RelayContainer.degradeReasonFor(
                systemRefused = false,
                modeRequired = false,
                decisionReason = null,
            ),
        )
    }

    /**
     * 模式门禁表（[RelayCoordinator.modeRequiredFor]）：`surface.virtual`
     * 的 create/release/query 是管理动作**不假模西**——前台偏好下被回「切后台再重试」
     * 会把 unsafe/残留屏的解除通路封掉（先有屏才能建屏刷新/收污/复检）；其余单面能力
     * 照旧映射（豁免不溢出）。DENIED 档、系统拒绝的既有优先级不变（表外的两支不动）。
     */
    @Test
    fun theModeGateExemptsTheSurfaceVirtualManagementActions() {
        // surface.virtual：requiresMode 置 null（不清不拦）。
        assertNull(RelayCoordinator.modeRequiredFor(CapabilityId.SURFACE_VIRTUAL, SurfaceKind.BACKEND_TRUSTED))
        // 其余单面能力照旧映射（含 trusted 面、前台面与缺省 null 三档）。
        assertEquals(SurfaceKind.BACKEND_TRUSTED, RelayCoordinator.modeRequiredFor(CapabilityId.APP_LAUNCH, SurfaceKind.BACKEND_TRUSTED))
        assertEquals(SurfaceKind.FOREGROUND, RelayCoordinator.modeRequiredFor(CapabilityId.APP_LAUNCH, SurfaceKind.FOREGROUND))
        assertNull(RelayCoordinator.modeRequiredFor(CapabilityId.APP_LAUNCH, null))
        assertEquals(SurfaceKind.BACKEND_TRUSTED, RelayCoordinator.modeRequiredFor(CapabilityId.UI_TAP, SurfaceKind.BACKEND_TRUSTED))
        // 与拒绝/档位判据的顺序独立：先按 earlyVerdict 分派后按本表打回（调用侧顺序）。
        assertEquals(
            RelayError.SURFACE_TRUSTED_DENIED,
            RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = true),
        )
    }


    /** 首页可用计数（[RelayContainer.readyUsableCount]）：只数 usable，enabled 不再喂这个数。 */
    @Test
    fun readyCountsOnlyUsableRows() {
        val rows = listOf(
            row(usable = true, enabled = false),
            row(usable = false, enabled = true), // "已实现且档位开着"但此刻调不动：不入分子
            row(usable = false, enabled = false),
            row(usable = true, enabled = true),
        )
        assertEquals(2, RelayContainer.readyUsableCount(rows))
        assertEquals(rows.size, 4)
    }

    /**
     * 调用侧（RelayCoordinator）的拒绝分派：被拒的 surface.virtual 在 requiresMode 解析
     * **之前**按 E_SURFACE_TRUSTED_DENIED 出——用户被拒后切了前台，裁决会先给出
     * requiresMode，调用侧若先答 modeRequired，插件便按"切回后台"建议把人送回后台
     * 再撞一次拒绝（BLOCKER 循环场景）。其余能力与未拒的 surface.virtual 不入此分派。
     */
    @org.junit.Test
    fun callSideVerdictDeniesTheMarkerBeforeModeRequired() {
        // 生产入口（handle 实调 [RelayCoordinator.earlyVerdict]，id 作参）穷举：
        // 被拒的 surface.virtual → E_SURFACE_TRUSTED_DENIED；未被拒 → null 照常进
        // requiresMode；**其它能力即使标记在场也不入此分派**——拒绝只属于建屏那一条，
        // 少带 id 的判定会把 ui.tap / screen.capture 等后台能力也一并说成"被 ROM 拒绝"。
        assertEquals(
            RelayError.SURFACE_TRUSTED_DENIED,
            RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = true),
        )
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = false))
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.UI_TAP, tierDenied = false, surfaceDenied = true))
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.SCREEN_CAPTURE, tierDenied = false, surfaceDenied = true))
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.SCREEN_RECORD, tierDenied = false, surfaceDenied = true))
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.APP_LAUNCH, tierDenied = false, surfaceDenied = true))
    }

    /**
     * 调用侧的分派顺序（生产入口 [RelayCoordinator.earlyVerdict]）：**用户的 DENIED 档
     * 先于机器的拒绝**。档位语义（去档位页解禁）对**任意 id** 生效——把 surface.virtual
     * 拨到禁止时回 GATE_HOST_DENIED，ROM 的拒绝标记在场也不改写它；拒绝语义只属于
     * surface.virtual 且档位未禁。tier/surface 两读项由 handle() 实取（tier 经闸门只读、
     * denied 经注入后端的实时标记、id 由 descriptor 给），本测试穷举的就是 handle 实跑的
     * 那一份。
     */
    @org.junit.Test
    fun userTierDenialBeatsTheRomRefusalInNaming() {
        // BLOCKER 三场景：
        // ①档位禁止（任意 id）→ GATE_HOST_DENIED，即使清单说被拒。
        assertEquals(
            RelayError.GATE_HOST_DENIED,
            RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = true, surfaceDenied = true),
        )
        assertEquals(
            RelayError.GATE_HOST_DENIED,
            RelayCoordinator.earlyVerdict(CapabilityId.UI_TAP, tierDenied = true, surfaceDenied = false),
        )
        // ②被拒且档位未禁（仅 surface.virtual）→ E_SURFACE_TRUSTED_DENIED。
        assertEquals(
            RelayError.SURFACE_TRUSTED_DENIED,
            RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = true),
        )
        // ③都不在场 → null。
        assertNull(RelayCoordinator.earlyVerdict(CapabilityId.SURFACE_VIRTUAL, tierDenied = false, surfaceDenied = false))
    }

    private fun row(usable: Boolean, enabled: Boolean): RelayContainer.CapabilityRow {
        val descriptor = interlock.relay.core.protocol.CapabilityRegistry.find(CapabilityId.APP_LAUNCH)!!
        return RelayContainer.CapabilityRow(
            descriptor = descriptor,
            state = CapabilityState(descriptor, SystemGrantState.GRANTED, AccessTier.ALWAYS),
            implemented = true,
            usable = usable,
            enabled = enabled,
            gaps = emptyList(),
            surface = null,
            degradeReason = null,
            sessionGranted = false,
        )
    }
}
