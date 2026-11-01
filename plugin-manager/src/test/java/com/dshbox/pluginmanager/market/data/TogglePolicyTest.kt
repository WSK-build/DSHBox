package com.dshbox.pluginmanager.market.data

import com.dshbox.pluginmanager.market.model.ActivationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能不能启停的判定。
 *
 * 规则全部来自数据层事实：写不出行、动到宿主基础设施、市场自己、或宿主已拒绝
 * 加载的条目都不给开关——否则界面会出现"一拨就报错"的开关。
 */
class TogglePolicyTest {

    private val host = setOf("@deepseek-ai/dsh-base", "cordis")

    private fun toggleable(
        name: String = "pkg",
        rowIds: List<String> = listOf("pkg-row"),
        activation: ActivationState = ActivationState.LIVE,
        isMarketItself: Boolean = false,
    ) = isToggleablePackage(name, rowIds, host, activation, isMarketItself)

    @Test
    fun ordinaryPluginIsToggleable() {
        assertTrue(toggleable())
    }

    @Test
    fun brokenPluginCanStillBeSwitchedOff() {
        // 校验没过也允许关掉：用户至少能把它停下来。
        assertTrue(toggleable(activation = ActivationState.BROKEN))
    }

    @Test
    fun unresolvedRowsHaveNoSwitch() {
        assertFalse(toggleable(rowIds = emptyList()))
    }

    @Test
    fun hostInfrastructureIsNotToggleable() {
        assertFalse(toggleable(name = "@deepseek-ai/dsh-web-app"))
        assertFalse(toggleable(name = "cordis:timer"))
    }

    @Test
    fun marketItselfIsNotToggleable() {
        assertFalse(toggleable(isMarketItself = true))
    }

    @Test
    fun deniedByHostHasNoSwitch() {
        assertFalse(toggleable(activation = ActivationState.DENIED))
    }

    @Test
    fun libraryHasNoSwitchAndDisabledStaysSwitchable() {
        assertFalse("声明的库让位于 LIBRARY，拨了看不出变化", toggleable(activation = ActivationState.LIBRARY))
        assertTrue("已停用的包要能重新打开", toggleable(activation = ActivationState.DISABLED))
        assertTrue("待停用的包要能撤销", toggleable(activation = ActivationState.DISABLED_PENDING))
    }

    @Test
    fun stopFactsNeverGetOverriddenByHostDenials() {
        // 宿主上次启动拒绝过某个条目时，applyDenial 会把它改判成 DENIED；
        // 停用（含待生效）与声明库必须豁免，否则"刚停用"会被说成"宿主拒绝加载"。
        val stopFacts = ActivationState.entries.filter { isStopFact(it) }.toSet()
        assertEquals(
            setOf(
                ActivationState.DISABLED,
                ActivationState.DISABLED_PENDING,
                ActivationState.LIBRARY,
            ),
            stopFacts,
        )
        assertTrue("待停用必须算停用事实", isStopFact(ActivationState.DISABLED_PENDING))
        assertFalse("已生效不是停用事实", isStopFact(ActivationState.LIVE))
    }
}
