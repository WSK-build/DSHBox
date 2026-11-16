package interlock.relay.core.exec.a11y

import interlock.relay.core.protocol.RelayError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动主循环的回归测试（驱动 [runScrollRounds]，即生产 `NodeActions.scroll` 每轮走的
 * 那条循环）：钉住"每轮 取根 → 名单判据 → 找到节点 → 派发"的**次序**。
 *
 * 会红的验证方式：把 [runScrollRounds] 里的名单判据删掉、或挪到 `dispatchRound` 之后
 * （即挪到 performAction 之后），`首轮名单为空派发一次、轮间把当前根包加进名单` 这条
 * 立即变红——名单变更发生在**两轮之间**（第二轮取根的那一刻才落盘），守卫挪后之后
 * 第二轮会先派发（dispatches == 2）才被查到，`assertEquals(1, dispatches)` 与
 * E_PACKAGE_PROTECTED 断言双双失守。只测纯判据（protectedRootGuardReason）的测试
 * 管不住调用点，这正是上一轮缺陷的所在。
 *
 * `ui.waitFor` 的同形覆盖：waitFor 的每一轮 `evaluate` 都从 `resolve` 重新取根并过一次
 * `protectedRootGuard`（见 NodeActions.waitFor 的注释），等待期间加名单即以
 * E_PACKAGE_PROTECTED 收场；NodeActions 依赖 Android 类型，JVM 侧钉的是这条共用的
 * 判据与本文件的循环次序，waitFor 的逐轮性由"每轮 evaluate→resolve→absoluteTarget
 * 都走 protectedRootGuard"这一点保证。
 */
class ProtectedScrollLoopTest {

    /** 假场景：根就是包名字符串，节点是 Int 编号，派发只计数——不含任何 Android 类型。 */
    private class Scene {
        var rootPackage: String? = "com.bank"
        var packages: Set<String> = emptySet()
        var nodeGone = false
        var dispatches = 0
        var loggedPackage: String? = null
    }

    @Test
    fun `首轮名单为空派发一次、轮间把当前根包加进名单后第二轮不再派发`() = runBlocking {
        // 名单变更落在**两轮之间**（第二轮取根的那一刻才生效）——与生产场景同时序：
        // 用户在途勾名单，循环里的任何回调都不能提前看到它。守卫必须在第二轮的
        // 找节点/派发之前现读名单把它拦下。
        var round = 0
        var packages: Set<String> = emptySet()
        var dispatches = 0
        var loggedPackage: String? = null
        val verdict = runScrollRounds(
            roundLimit = 5,
            rootProvider = {
                round++
                if (round == 2) packages = setOf("com.bank")
                "com.bank"
            },
            packageNameOf = { it },
            packagesProvider = { packages },
            findNode = { 1 },
            onProtectedBlocked = { loggedPackage = it },
            dispatchRound = { _ ->
                dispatches++
                ScrollRoundOutcome.Continue
            },
        )
        // 次序即语义：判据在找节点与派发**之前**，第二轮一次派发都不发生。
        assertEquals(1, dispatches)
        assertTrue("应按名单拒绝收场，实际 $verdict", verdict is ScrollLoopVerdict.Protected)
        assertEquals(RelayError.PACKAGE_PROTECTED, (verdict as ScrollLoopVerdict.Protected).error)
        assertTrue(verdict.reason.contains("com.bank"))
        assertEquals("运行日志记的是命中的包名", "com.bank", loggedPackage)
    }

    @Test
    fun `守卫在派发之前——同一现场守卫先命中就不会走到指纹与动作表`() = runBlocking {
        // 反向钉法：名单从一开始就命中，循环应一次派发都不发生（不是先派再查）。
        val scene = Scene().apply { packages = setOf("com.bank") }
        val verdict = runScrollRounds(
            roundLimit = 3,
            rootProvider = { scene.rootPackage },
            packageNameOf = { it },
            packagesProvider = { scene.packages },
            findNode = { if (scene.nodeGone) null else 1 },
            onProtectedBlocked = { scene.loggedPackage = it },
            dispatchRound = { _ ->
                scene.dispatches++
                ScrollRoundOutcome.Continue
            },
        )
        assertEquals(0, scene.dispatches)
        assertTrue(verdict is ScrollLoopVerdict.Protected)
        assertEquals(RelayError.PACKAGE_PROTECTED, (verdict as ScrollLoopVerdict.Protected).error)
    }

    @Test
    fun `根读不到时跳过守卫维持节点已不在的出口`() = runBlocking {
        // 无树/宿主窗口不是"读不到包名"：名单非空也不拦，循环按 findNode 的 null
        // （节点不在）自然收场，由调用方维持既有口径。
        val scene = Scene().apply {
            rootPackage = null
            packages = setOf("com.bank")
            nodeGone = true
        }
        val verdict = runScrollRounds(
            roundLimit = 3,
            rootProvider = { scene.rootPackage },
            packageNameOf = { it },
            packagesProvider = { scene.packages },
            findNode = { root -> if (root == null) null else 1 },
            onProtectedBlocked = { scene.loggedPackage = it },
            dispatchRound = { _ ->
                scene.dispatches++
                ScrollRoundOutcome.Continue
            },
        )
        assertTrue("不按名单拒绝：$verdict", verdict == ScrollLoopVerdict.Completed)
        assertEquals(0, scene.dispatches)
        assertEquals("守卫未触发，不记日志", null, scene.loggedPackage)
    }

    @Test
    fun `名单非空而根包不在名单时照常逐轮派发`() = runBlocking {
        val scene = Scene().apply {
            rootPackage = "com.gallery"
            packages = setOf("com.bank")
        }
        val verdict = runScrollRounds(
            roundLimit = 3,
            rootProvider = { scene.rootPackage },
            packageNameOf = { it },
            packagesProvider = { scene.packages },
            findNode = { if (scene.nodeGone) null else 1 },
            onProtectedBlocked = { scene.loggedPackage = it },
            dispatchRound = { _ ->
                scene.dispatches++
                ScrollRoundOutcome.Continue
            },
        )
        assertEquals(3, scene.dispatches)
        assertTrue(verdict == ScrollLoopVerdict.Completed)
        assertEquals(null, scene.loggedPackage)
    }

    @Test
    fun `判停与步数到点自然收场不撞名单`() = runBlocking {
        val scene = Scene()
        val stopped = runScrollRounds(
            roundLimit = 5,
            rootProvider = { scene.rootPackage },
            packageNameOf = { it },
            packagesProvider = { scene.packages },
            findNode = { if (scene.nodeGone) null else 1 },
            onProtectedBlocked = { scene.loggedPackage = it },
            dispatchRound = { _ ->
                scene.dispatches++
                ScrollRoundOutcome.Stop
            },
        )
        assertTrue(stopped == ScrollLoopVerdict.Completed)
        assertEquals(1, scene.dispatches)
        val exhausted = runScrollRounds(
            roundLimit = 4,
            rootProvider = { scene.rootPackage },
            packageNameOf = { it },
            packagesProvider = { scene.packages },
            findNode = { if (scene.nodeGone) null else 1 },
            onProtectedBlocked = { scene.loggedPackage = it },
            dispatchRound = { _ ->
                scene.dispatches++
                ScrollRoundOutcome.Continue
            },
        )
        assertTrue(exhausted == ScrollLoopVerdict.Completed)
        assertEquals(5, scene.dispatches)
    }

    @Test
    fun `读不到包名按 fail-closed 拒绝`() = runBlocking {
        // 树在、包名读不到（AccessibilityNodeInfo 偶发交回空包名）：名单非空时按保护收场。
        val scene = Scene().apply {
            rootPackage = ""
            packages = setOf("com.bank")
        }
        val verdict = runScrollRounds(
            roundLimit = 2,
            rootProvider = { scene.rootPackage },
            packageNameOf = { it },
            packagesProvider = { scene.packages },
            findNode = { if (scene.nodeGone) null else 1 },
            onProtectedBlocked = { scene.loggedPackage = it },
            dispatchRound = { _ ->
                scene.dispatches++
                ScrollRoundOutcome.Continue
            },
        )
        assertTrue(verdict is ScrollLoopVerdict.Protected)
        assertEquals(0, scene.dispatches)
    }
}
