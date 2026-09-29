package interlock.relay.core.surface

import interlock.relay.core.protocol.BackendId
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.GuideClass
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SettingsTarget
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.protocol.TierCeiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 执行模式裁决的回归用例，覆盖两类失效：响应写着 trusted-display 而动作
 * 发生在真屏上，以及「后端在这个模式下根本跑不了」被当成可用模式选出去。
 */
class SurfacePolicyTest {

    private fun descriptor(
        surfaces: Set<SurfaceKind>,
        backends: List<BackendId> = listOf(BackendId.DIRECT),
    ) = CapabilityDescriptor(
        id = CapabilityId.APP_LAUNCH,
        titleRes = 0,
        summaryRes = 0,
        backends = backends,
        surfaces = surfaces,
        guideClass = GuideClass.DEEP_LINK_OK,
        settingsTarget = SettingsTarget.APP_DETAILS,
        ceiling = TierCeiling.ANY,
        runtimePermissions = emptyList(),
    )

    private val trusted = setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED)

    private fun policy(
        preference: SurfacePreference,
        shell: Boolean = true,
        canServe: (SurfaceKind) -> Boolean = { true },
    ) = SurfacePolicy(
        preference = { preference },
        shellAvailable = { shell },
        backendCanServe = { _, surface -> canServe(surface) },
        sdkInt = SDK_NEW,
    )

    @Test
    fun backgroundPreferenceUsesTrustedWhenItCanBeServed() {
        val decision = policy(SurfacePreference.BACKGROUND_PREFERRED).decide(descriptor(trusted))
        assertEquals(SurfaceKind.BACKEND_TRUSTED, decision.surface)
        assertNull(decision.degradedFrom)
    }

    @Test
    fun trustedIsAbandonedWhenNoBackendServesThatMode() {
        val decision = policy(SurfacePreference.BACKGROUND_PREFERRED, canServe = { it != SurfaceKind.BACKEND_TRUSTED })
            .decide(descriptor(trusted))
        assertEquals(SurfaceKind.FOREGROUND, decision.surface)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, decision.degradedFrom)
        assertEquals(RelayError.SURFACE_UNAVAILABLE.code, decision.reason)
    }

    @Test
    fun missingShellDegradesWithItsOwnReason() {
        val decision = policy(SurfacePreference.BACKGROUND_PREFERRED, shell = false).decide(descriptor(trusted))
        assertEquals(SurfaceKind.FOREGROUND, decision.surface)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, decision.degradedFrom)
        assertEquals(RelayError.SURFACE_NO_SHELL.code, decision.reason)
    }

    @Test
    fun oldSdkDegradesWithItsOwnReason() {
        val policy = SurfacePolicy(
            preference = { SurfacePreference.BACKGROUND_PREFERRED },
            shellAvailable = { true },
            sdkInt = SurfacePolicy.MIN_BACKEND_SDK - 1,
        )
        val decision = policy.decide(descriptor(trusted))
        assertEquals(SurfaceKind.FOREGROUND, decision.surface)
        assertEquals(RelayError.SURFACE_API_LEVEL.code, decision.reason)
    }

    @Test
    fun foregroundPreferenceNeverReportsADegradation() {
        val decision = policy(SurfacePreference.FOREGROUND).decide(descriptor(trusted))
        assertEquals(SurfaceKind.FOREGROUND, decision.surface)
        assertNull(decision.degradedFrom)
        assertNull(decision.reason)
    }

    @Test
    fun modeAgnosticCapabilitySkipsTheWholeDegradationPath() {
        val decision = policy(SurfacePreference.BACKGROUND_PREFERRED, shell = false).decide(descriptor(emptySet()))
        assertEquals(SurfaceKind.FOREGROUND, decision.surface)
        assertNull(decision.degradedFrom)
    }

    /**
     * 仅观测那条面不注入输入、也不碰用户眼前这块屏，所以「它没有后台面」不是降级。
     * 降级判据若只看「有没有声明 BACKEND_TRUSTED」，`screen.observe` 的每次成功都会带上
     * degraded:true，而调用方拿 degraded 判断的正是后台计划有没有落空。
     */
    @Test
    fun observeOnlyLandingIsNotADegradation() {
        val decision = policy(SurfacePreference.BACKGROUND_PREFERRED, shell = false)
            .decide(descriptor(setOf(SurfaceKind.OBSERVE_ONLY)))
        assertEquals(SurfaceKind.OBSERVE_ONLY, decision.surface)
        assertNull(decision.degradedFrom)
        assertNull(decision.reason)
    }

    @Test
    fun nothingServableStillReportsWhatWasWanted() {
        val decision = policy(SurfacePreference.BACKGROUND_PREFERRED, canServe = { false })
            .decide(descriptor(trusted))
        assertEquals(SurfaceKind.BACKEND_TRUSTED, decision.degradedFrom)
        assertEquals(RelayError.SURFACE_UNAVAILABLE.code, decision.reason)
    }

    /**
     * 单一执行面的能力（只声明 trusted-display，如 `surface.virtual`）在用户选了前台时
     * 到不了自己唯一的面。裁决必须显式说出「需要哪个模式」，而不是伪造一个前台落点——
     * 伪造的落点会先过门禁、再在执行期换成一条泛化的「够不着」。
     */
    @Test
    fun singleSurfaceCapabilityNamesTheRequiredModeInsteadOfForgingForeground() {
        val decision = policy(SurfacePreference.FOREGROUND)
            .decide(descriptor(setOf(SurfaceKind.BACKEND_TRUSTED)))
        assertEquals(SurfaceKind.BACKEND_TRUSTED, decision.surface)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, decision.requiresMode)
        assertEquals(RelayError.SURFACE_MODE_REQUIRED.code, decision.reason)
        assertNull(decision.degradedFrom)
    }

    /** 同一条规则与偏好无关：优先后台但那块屏起不来时，同样点名「需要该模式」而非落前台。 */
    @Test
    fun singleSurfaceCapabilityStaysHonestUnderBackgroundPreferenceToo() {
        val decision = policy(SurfacePreference.BACKGROUND_PREFERRED, shell = false)
            .decide(descriptor(setOf(SurfaceKind.BACKEND_TRUSTED)))
        assertEquals(SurfaceKind.BACKEND_TRUSTED, decision.requiresMode)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, decision.surface)
    }

    /** 单一「仅观测」面且它本来可达时走的是正常选中路径，不该被 requiresMode 误伤。 */
    @Test
    fun reachableSingleSurfaceStillLandsNormally() {
        val decision = policy(SurfacePreference.FOREGROUND)
            .decide(descriptor(setOf(SurfaceKind.OBSERVE_ONLY)))
        assertEquals(SurfaceKind.OBSERVE_ONLY, decision.surface)
        assertNull(decision.requiresMode)
    }

    private companion object {
        const val SDK_NEW = 36
    }
}
