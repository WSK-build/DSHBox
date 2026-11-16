package interlock.relay.core.exec.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滚动每一轮（及一切取根后的动作/回读）的名单复查判据（纯函数穷举）。
 *
 * `NodeActions` 的伴生对象存在框架类常量，JVM 单测里类初始化过不去，所以判据收在
 * 文件顶层的 [protectedRootGuardReason]，生产循环与这里的用例走同一份函数：
 * scroll 的每一轮在 `findByIdPreorder`/派发之前、回读取根在交出节点之前调它。
 */
class ProtectedRootGuardTest {

    @Test
    fun `名单为空恒放行（读得到与读不到包名都一样）`() {
        assertNull(protectedRootGuardReason("com.gallery", emptySet()))
        assertNull(protectedRootGuardReason(null, emptySet()))
    }

    /**
     * 回归：模拟"首轮名单为空、下一轮根包名变成受保护应用"。
     *
     * 复刻生产 scroll 循环的每轮判定形状：名单是实时 provider（与装配根
     * `{ protectedApps.packages() }` 同形），每一轮现读一遍——用户在途把刚切进的
     * 应用加进名单后，第二轮的同一判定立即翻成拒绝，该轮在派发之前收场。
     */
    @Test
    fun `首轮名单为空放行、下一轮根变成受保护应用立即拒`() {
        var list: Set<String> = emptySet()
        val listNow = { list } // 生产侧的实时读数形状：每轮调一次取当下名单
        val rootPackage = "com.bank" // 用户中途切到的应用，树根就挂它的包名

        // 第一轮：名单为空 → 放行，循环照常派发。
        assertNull(protectedRootGuardReason(rootPackage, listNow()))

        // 用户在途把当前应用加进名单；第二轮取到同一个根包名。
        list = setOf("com.bank")

        // 第二轮：命中 → 拒绝，循环在这里收场，一次派发都不会再发生。
        val reason = protectedRootGuardReason(rootPackage, listNow())
        assertEquals(
            "package com.bank is on the user's protected-apps list: the assistant " +
                "can neither operate it nor read it",
            reason,
        )
    }

    @Test
    fun `读不到包名按 fail-closed 拒绝`() {
        val reason = protectedRootGuardReason(null, setOf("com.bank"))
        assertEquals(
            "cannot confirm the app in front (the refreshed window carries no package), and the " +
                "protected-apps list is not empty: refusing to proceed",
            reason,
        )
        // 空串同样算"读不到"（AccessibilityNodeInfo 偶发交回空包名）。
        assertNotNull(protectedRootGuardReason("", setOf("com.bank")))
    }

    @Test
    fun `名单非空而根不在名单时放行`() {
        assertNull(protectedRootGuardReason("com.gallery", setOf("com.bank")))
    }

    @Test
    fun `精确相等才命中不认前缀`() {
        assertNull(protectedRootGuardReason("com.bank.extra", setOf("com.bank")))
        assertNotNull(protectedRootGuardReason("com.bank", setOf("com.bank")))
    }

    @Test
    fun `命中原因只点名受保护包不出现完整名单`() {
        val reason = protectedRootGuardReason("com.bank", setOf("com.bank", "com.wallet"))
        assertTrue(reason!!.contains("com.bank"))
        assertTrue(!reason.contains("com.wallet"))
    }
}
