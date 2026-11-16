package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 建屏失败回包的三态判定（[ShizukuBackend.createRejection]）：**只有系统当场拒绝**
 * 置 trustedDisplayDenied 并回 E_SURFACE_TRUSTED_DENIED；"旧屏收不掉""建屏内部异常"这类
 * 瞬时失败必须保持可重试错误、不写拒绝标记——把瞬时的当成机器的固定结论，一次服务端
 * 内部抖动就把 surface.virtual 永久标死。
 */
class DisplayCreateVerdictTest {

    /** 屏"问不成"（CODE_UNVERIFIED）：屏可能还在跑，按可重试处理，不写拒绝标记。 */
    @Test
    fun unverifiableReplyIsRetriableAndNeverDenies() {
        assertNull(
            ShizukuBackend.createRejection(RelayShellService.TrustedDisplay.CODE_UNVERIFIED),
        )
    }

    /** 系统当场拒绝：唯一的拒绝来源，调用方据此置标记并回 E_SURFACE_TRUSTED_DENIED。 */
    @Test
    fun systemDeniedIsTheOnlyDenialSource() {
        val verdict = ShizukuBackend.createRejection(RelayShellService.TrustedDisplay.CODE_SYSTEM_DENIED)
        requireNotNull(verdict)
        assertTrue(verdict.systemDenied)
    }

    /** 其余负值（旧屏收不掉 `stale display ... could not be cleared`、内部异常 `IllegalStateException: ...`）：瞬时失败，可重试。 */
    @Test
    fun transientFailuresStayRetriableWithoutDenial() {
        // 服务端对「旧屏收不掉」与「内部异常」都回 -1：两者都不该被读成"系统拒绝"。
        val stale = ShizukuBackend.createRejection(-1)
        requireNotNull(stale)
        assertFalse(stale.systemDenied)

        // 任意其它负值同口径：拒绝只认 CODE_SYSTEM_DENIED 一个码。
        assertFalse(ShizukuBackend.createRejection(-4)!!.systemDenied)
    }

    /** 建成（code>=0）不进这判定：positive path 另行处理，这里只穷举失败回包。 */
    @Test
    fun successCodesNeverReachThisVerdict() {
        // 0 与正编号是建成后的屏号——判定表只接受负值，正数也按"其余失败"答 false 不为拒。
        // 该行为只作为穷举备案：真实现里成功路径先于这段判定返回。
        assertEquals(false, ShizukuBackend.createRejection(0)!!.systemDenied)
        assertEquals(false, ShizukuBackend.createRejection(42)!!.systemDenied)
    }

    /** 服务端异常类型 → 结果码（RelayShellService.TrustedDisplay.createFailureReply）的映射：拒绝只认 SecurityException。 */
    @Test
    fun exceptionKindsMapToTheirOwnResultCodesOnTheServiceSide() {
        val service = RelayShellService.TrustedDisplay
        // 拆掉 InvocationTargetException 信封后是 SecurityException → 系统拒绝。
        val denied = java.lang.reflect.InvocationTargetException(
            SecurityException("packageName must match the calling uid"),
        )
        assertEquals(service.CODE_SYSTEM_DENIED, service.createFailureReply(denied).first)

        // 裸 SecurityException（不经反射信封）同样按拒绝答。
        assertEquals(service.CODE_SYSTEM_DENIED, service.createFailureReply(SecurityException("nope")).first)

        // 其它场内异常（IllegalStateException 等）→ -1 瞬时失败，不写「拒绝」。
        assertEquals(-1, service.createFailureReply(IllegalStateException("created display has no id")).first)
        assertEquals(-1, service.createFailureReply(IllegalStateException("no display service")).first)

        // **只拆一种信封**：SecurityException 自己带 cause 时，拒绝本身就在顶层——
        // 顶层名字仍按拒绝答，且 reason 里点名的是顶层的 SecurityException 段。
        val withCause = java.lang.reflect.InvocationTargetException(
            SecurityException("system denied the create", RuntimeException("details")),
        )
        assertEquals(service.CODE_SYSTEM_DENIED, service.createFailureReply(withCause).first)
        assertTrue(service.createFailureReply(withCause).second.contains("SecurityException"))

        // 普通 wrapper 用RuntimeException 包 SecurityException：不是反射信封，不拆——
        // 顶层读出来是 RuntimeException → -1 瞬时失败，不写「拒绝」。
        val plainWrapper = RuntimeException("wrapper", SecurityException("denied inside"))
        assertEquals(-1, service.createFailureReply(plainWrapper).first)
        assertTrue(service.createFailureReply(plainWrapper).second.contains("RuntimeException"))

        // 信封一层照常拆出（ITE→SE）。
        val ite = java.lang.reflect.InvocationTargetException(
            SecurityException("packageName must match the calling uid"),
        )
        assertEquals(service.CODE_SYSTEM_DENIED, service.createFailureReply(ite).first)
        assertTrue(service.createFailureReply(ite).second.contains("SecurityException"))
    }
}
