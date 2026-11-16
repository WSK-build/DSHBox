package interlock.relay.core.surface

import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标落点层的纯判据：失败性质分类、落定处置、按目标沿用路由。
 * 启动失败的三分类（确定性/暂时性/未知）钉死在此 —— 分错一类就会把该降级的卡在后台、
 * 把暂时性抖动钉在前台，或把结局未知的启动重放一遍。
 */
class TargetLandingTest {

    private val pref = SurfacePreference.BACKGROUND_PREFERRED

    // ── 失败分类 ──

    @Test
    fun `落到用户真屏的失败单独成类`() {
        val data = JSONObject().put("grabbedUserScreen", true)
        assertEquals(
            LaunchFailureClass.LANDED_ON_USER_SCREEN,
            classifyLaunchFailure(RelayError.LAUNCH_NOT_LANDED, data, dispatched = true),
        )
        // 没有证据的同一错误码不是"已在真屏"：分类回到确定性。
        assertEquals(
            LaunchFailureClass.DETERMINISTIC,
            classifyLaunchFailure(RelayError.LAUNCH_NOT_LANDED, JSONObject(), dispatched = true),
        )
    }

    @Test
    fun `确定性与暂时性与未知各归各类`() {
        for (error in listOf(
            RelayError.SURFACE_API_LEVEL,
            RelayError.SURFACE_TRUSTED_DENIED,
            RelayError.SURFACE_MODE_REQUIRED,
            RelayError.SURFACE_UNAVAILABLE,
        )) {
            assertEquals(
                "$error", LaunchFailureClass.DETERMINISTIC,
                classifyLaunchFailure(error, JSONObject(), dispatched = true),
            )
        }
        assertEquals(
            LaunchFailureClass.TRANSIENT,
            classifyLaunchFailure(RelayError.BACKEND_SHIZUKU_DEAD, JSONObject(), dispatched = true),
        )
        // 屏被回收 / 还没起完：暂时性。
        assertEquals(
            LaunchFailureClass.TRANSIENT,
            classifyLaunchFailure(RelayError.BACKEND_UNAVAILABLE, JSONObject(), dispatched = true),
        )
        // 整屏顶层都读不到：结局未知。
        assertEquals(
            LaunchFailureClass.UNKNOWN,
            classifyLaunchFailure(
                RelayError.BACKEND_UNAVAILABLE,
                JSONObject().put("landingCheck", "failed"),
                dispatched = true,
            ),
        )
    }

    /**
     * 启动前失败的分类矩阵：参数错、未实现、档位拒绝、限流等都是确定性可修复错误，
     * 原样交回 —— 不兜底，也**不记幂等标记挡同参重发**。已递交之后的未编类错误才是未知。
     */
    @Test
    fun `启动前失败原样交回不挡重发`() {
        for (error in listOf(
            RelayError.TRANSPORT_MALFORMED,
            RelayError.CAPABILITY_NOT_IMPLEMENTED,
            RelayError.GATE_HOST_DENIED,
            RelayError.RATE_LIMITED,
            RelayError.INTERNAL,
            RelayError.TRANSPORT_TIMEOUT,
        )) {
            assertEquals(
                "$error 未派发",
                LaunchFailureClass.PRE_LAUNCH,
                classifyLaunchFailure(error, JSONObject(), dispatched = false),
            )
        }
        assertEquals(
            "已递交后的未编类错误是未知",
            LaunchFailureClass.UNKNOWN,
            classifyLaunchFailure(RelayError.INTERNAL, JSONObject(), dispatched = true),
        )
    }

    /**
     * 启动入口自己的两条失败码：即使 run() 因"已收到回执"按已派发交出，
     * 对 app.launch 仍是启动前失败——原样交回，不写幂等标记挡同参重发。
     * （这两码对 full shell 的 effectState 另有口径，不经本判据。）
     */
    @Test
    fun `启动入口的命令失败两码不随已派发推成未知`() {
        for (error in listOf(RelayError.COMMAND_FAILED, RelayError.COMMAND_NOT_PERMITTED)) {
            assertEquals(
                "$error 已派发",
                LaunchFailureClass.PRE_LAUNCH,
                classifyLaunchFailure(error, JSONObject(), dispatched = true),
            )
            assertEquals(
                "$error 未派发",
                LaunchFailureClass.PRE_LAUNCH,
                classifyLaunchFailure(error, JSONObject(), dispatched = false),
            )
        }
    }

    // ── 落定处置 ──

    private fun settle(
        surface: SurfaceKind,
        error: RelayError?,
        data: JSONObject,
        degradedFrom: SurfaceKind? = null,
    ) = settleLaunchResult(
        surface, degradedFrom, error, data, "com.target", 1_000L, 60_000L, pref,
    )

    @Test
    fun `核验过的后台落点才落账`() {
        val ok = settle(
            SurfaceKind.BACKEND_TRUSTED, null,
            JSONObject().put("verified", true).put("display", 7),
        )
        val record = checkNotNull(ok.record) { "expected a record" }
        assertEquals("com.target", record.targetPackage)
        assertEquals(7, record.displayId)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, record.surface)
        assertEquals(LandingEvidence.LANDED_ON_TARGET, record.evidence)
        assertEquals(61_000L, record.expiresAtMs)
        assertFalse(ok.fallbackToForeground)
        assertFalse(ok.rewriteToOk)

        // verified:false 的"成功"只是命令被接下：不落账、不兜底、按结果未知回可复查错误。
        val unverified = settle(
            SurfaceKind.BACKEND_TRUSTED, null,
            JSONObject().put("verified", false),
        )
        assertNull(unverified.record)
        assertFalse(unverified.fallbackToForeground)
        assertEquals(RelayError.LAUNCH_NOT_LANDED, unverified.syntheticError)
        assertEquals(LaunchFailureClass.UNKNOWN, unverified.failureClass)
    }

    @Test
    fun `核验过的前台落点带降级原因`() {
        val ok = settle(
            SurfaceKind.FOREGROUND, null,
            JSONObject().put("verified", true),
            degradedFrom = SurfaceKind.BACKEND_TRUSTED,
        )
        val record = checkNotNull(ok.record) { "expected a record" }
        assertEquals(SurfaceKind.FOREGROUND, record.surface)
        assertEquals(0, record.displayId)
        assertEquals(RelayError.SURFACE_UNAVAILABLE.code, record.reason)
        assertEquals(LandingEvidence.FOREGROUND_VERIFIED, record.evidence)
    }

    @Test
    fun `grabbedUserScreen 记前台且不再重发前台启动`() {
        val failed = settle(
            SurfaceKind.BACKEND_TRUSTED, RelayError.LAUNCH_NOT_LANDED,
            JSONObject().put("grabbedUserScreen", true).put("verified", false),
        )
        val record = checkNotNull(failed.record) { "expected a record" }
        assertEquals(SurfaceKind.FOREGROUND, record.surface)
        assertEquals(LandingEvidence.GRABBED_USER_SCREEN, record.evidence)
        assertEquals(RelayError.LAUNCH_NOT_LANDED.code, record.reason)
        assertFalse("已在用户眼前，不再重发前台启动", failed.fallbackToForeground)
        assertTrue(failed.rewriteToOk)
    }

    @Test
    fun `确定性失败给一次前台兜底且不落账`() {
        val failed = settle(
            SurfaceKind.BACKEND_TRUSTED, RelayError.LAUNCH_NOT_LANDED, JSONObject(),
        )
        assertNull(failed.record)
        assertTrue(failed.fallbackToForeground)
        assertFalse(failed.rewriteToOk)
    }

    @Test
    fun `暂时性与未知失败既不兜底也不落账`() {
        for (error in listOf(RelayError.BACKEND_SHIZUKU_DEAD, RelayError.INTERNAL)) {
            val failed = settle(SurfaceKind.BACKEND_TRUSTED, error, JSONObject())
            assertNull("$error", failed.record)
            assertFalse("$error", failed.fallbackToForeground)
            assertFalse("$error", failed.rewriteToOk)
        }
    }

    // ── 目标落定 ──

    @Test
    fun `目标按点名到选择器到最近启动的次序落定`() {
        assertEquals("com.a", resolveLandingTarget("com.a", "com.b", "com.c"))
        assertEquals("com.b", resolveLandingTarget(null, "com.b", "com.c"))
        assertEquals("com.c", resolveLandingTarget(null, null, "com.c"))
        assertNull(resolveLandingTarget(null, null, null))
    }

    // ── 按目标沿用 ──

    private fun landing(
        surface: SurfaceKind,
        evidenceAtMs: Long = 1_000L,
        ttl: Long = 60_000L,
        preference: SurfacePreference = pref,
    ) = TargetLanding(
        targetPackage = "com.target",
        displayId = if (surface == SurfaceKind.BACKEND_TRUSTED) 7 else 0,
        surface = surface,
        reason = null,
        verified = true,
        evidence = LandingEvidence.LANDED_ON_TARGET,
        evidenceAtMs = evidenceAtMs,
        expiresAtMs = evidenceAtMs + ttl,
        preferenceAtCreation = preference,
    )

    @Test
    fun `屏还在且目标仍在最前面时沿用后台落点`() {
        val route = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.BACKEND_TRUSTED), true, "com.target", false, 2_000L, pref,
        )
        val follow = checkNotNull(route as? TargetRoute.Follow) { "expected Follow" }
        assertEquals(SurfaceKind.BACKEND_TRUSTED, follow.decision.surface)
        assertEquals("com.target", follow.target)
    }

    @Test
    fun `屏没了给结构化错误而不是静默改前台`() {
        val route = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.BACKEND_TRUSTED), false, "com.target", false, 2_000L, pref,
        )
        val reject = checkNotNull(route as? TargetRoute.Reject) { "expected Reject" }
        assertEquals(RelayError.SURFACE_UNAVAILABLE, reject.error)
    }

    @Test
    fun `前台落点沿用为前台`() {
        val route = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.FOREGROUND), true, "com.target", false, 2_000L, pref,
        )
        val follow = checkNotNull(route as? TargetRoute.Follow) { "expected Follow" }
        assertEquals(SurfaceKind.FOREGROUND, follow.decision.surface)
    }

    @Test
    fun `目标已不在记录的面上时不沿用`() {
        // 用户手动切走了：猜测目标（lastTarget）退回偏好裁决，不把旧落点当权威。
        val guessed = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.BACKEND_TRUSTED), true, "com.other", false, 2_000L, pref,
        )
        assertTrue(guessed is TargetRoute.None)
        // 调用点名了目标而目标不在：结构化错误，不代为改道。
        val explicit = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.BACKEND_TRUSTED), true, "com.other", true, 2_000L, pref,
        )
        val reject = checkNotNull(explicit as? TargetRoute.Reject) { "expected Reject" }
        assertEquals(RelayError.SURFACE_UNAVAILABLE, reject.error)
        // 前台记录同样按当前顶层核对。
        val foregroundMoved = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.FOREGROUND), true, "com.other", false, 2_000L, pref,
        )
        assertTrue(foregroundMoved is TargetRoute.None)
    }

    @Test
    fun `当前顶层读不到时不沿用退回偏好裁决`() {
        val route = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.BACKEND_TRUSTED), true, null, false, 2_000L, pref,
        )
        assertTrue(route is TargetRoute.None)
    }

    @Test
    fun `过期与偏好变更与不声明后台面的能力都不沿用`() {
        // 过期
        val expired = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.BACKEND_TRUSTED, ttl = 100L), true, "com.target", false, 2_000L, pref,
        )
        assertTrue(expired is TargetRoute.None)
        // 偏好已变
        val switched = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", landing(SurfaceKind.BACKEND_TRUSTED, preference = SurfacePreference.FOREGROUND),
            true, "com.target", false, 2_000L, pref,
        )
        assertTrue(switched is TargetRoute.None)
        // 能力本身没有后台面（仅前台/仅观测）不参与落点沿用
        val foregroundOnly = routeForTarget(
            setOf(SurfaceKind.FOREGROUND),
            "com.target", landing(SurfaceKind.BACKEND_TRUSTED), true, "com.target", false, 2_000L, pref,
        )
        assertTrue(foregroundOnly is TargetRoute.None)
        // 没有记录
        val none = routeForTarget(
            setOf(SurfaceKind.FOREGROUND, SurfaceKind.BACKEND_TRUSTED),
            "com.target", null, true, "com.target", false, 2_000L, pref,
        )
        assertTrue(none is TargetRoute.None)
    }
}
