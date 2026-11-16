package interlock.relay.core.surface

import interlock.relay.core.protocol.SurfaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标落点登记簿：会话内按目标一条记录，TTL 与偏好变更读时即删，
 * "结果未知"标记按参数摘要挡同参重发。
 */
class TargetLandingTableTest {

    private var clock = 1_000L
    private var pref = SurfacePreference.BACKGROUND_PREFERRED

    private fun table(ttl: Long = 60_000L, unknownTtl: Long = 5_000L) =
        TargetLandingTable(now = { clock }, preference = { pref }, ttlMs = ttl, unknownTtlMs = unknownTtl)

    private fun landing(
        target: String = "com.target",
        surface: SurfaceKind = SurfaceKind.BACKEND_TRUSTED,
    ) = TargetLanding(
        targetPackage = target,
        displayId = if (surface == SurfaceKind.BACKEND_TRUSTED) 7 else 0,
        surface = surface,
        reason = null,
        verified = true,
        evidence = LandingEvidence.LANDED_ON_TARGET,
        evidenceAtMs = clock,
        expiresAtMs = clock + 60_000L,
        preferenceAtCreation = pref,
    )

    @Test
    fun `记录按目标存取且最近目标可查`() {
        val t = table()
        t.record(landing("com.a"))
        t.record(landing("com.b", SurfaceKind.FOREGROUND))
        assertEquals(SurfaceKind.FOREGROUND, t.get("com.b")?.surface)
        assertEquals("com.b", t.lastTarget())
        assertEquals("com.a", t.get("com.a")?.targetPackage)
    }

    @Test
    fun `过期记录读时即摘`() {
        val t = table()
        t.record(landing())
        clock += 60_000L
        assertNull(t.get("com.target"))
        assertNull("最近目标也随过期失效", t.lastTarget())
    }

    @Test
    fun `偏好变更读时即摘`() {
        val t = table()
        t.record(landing())
        pref = SurfacePreference.FOREGROUND
        assertNull(t.get("com.target"))
    }

    @Test
    fun `目标停止只摘自己不影响其他目标`() {
        val t = table()
        t.record(landing("com.a"))
        t.record(landing("com.b"))
        t.invalidate("com.a")
        assertNull(t.get("com.a"))
        assertEquals("com.b", t.get("com.b")?.targetPackage)
        assertEquals("com.b", t.lastTarget())
    }

    @Test
    fun `后台屏被动过全部作废`() {
        val t = table()
        t.record(landing("com.a"))
        t.record(landing("com.b"))
        t.clear()
        assertNull(t.get("com.a"))
        assertNull(t.get("com.b"))
        assertNull(t.lastTarget())
    }

    @Test
    fun `未知启动标记在寿命内挡同参重发`() {
        val t = table()
        t.rememberUnknownLaunch("digest-1", "com.target")
        assertTrue(t.hasUnknownLaunch("digest-1"))
        assertFalse("不同摘要互不影响", t.hasUnknownLaunch("digest-2"))
        // 同一目标被成功核实即摘：摘掉后同参重发照常走。
        t.clearUnknownLaunch("com.target")
        assertFalse(t.hasUnknownLaunch("digest-1"))
    }

    @Test
    fun `未知启动标记到点自动失效`() {
        val t = table()
        t.rememberUnknownLaunch("digest-1", "com.target")
        clock += 5_000L
        assertFalse("标记到点失效，核实动作不受限", t.hasUnknownLaunch("digest-1"))
    }
}
