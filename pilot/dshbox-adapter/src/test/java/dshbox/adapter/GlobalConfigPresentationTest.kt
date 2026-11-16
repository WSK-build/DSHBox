package dshbox.adapter

import dshbox.adapter.ui.GlobalConfigAction
import dshbox.adapter.ui.globalConfigActionLabel
import dshbox.adapter.ui.globalConfigLabelRes
import interlock.relay.core.interlock.GlobalConfigState
import interlock.relay.core.protocol.AccessTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 五条目全局配置的呈现判据。条目形状与词条都从生产出处（[GlobalConfigAction]、
 * [globalConfigLabelRes]）驱动，不在测试里另抄一份五条目清单。
 */
class GlobalConfigPresentationTest {

    /** 内核判出的六档各有各的词；期望表是断言，映射函数是生产路径。 */
    @Test
    fun everyGlobalConfigStateHasItsOwnLabel() {
        val expected = mapOf(
            GlobalConfigState.ALL_DENIED to R.string.pilot_tier_all_denied,
            GlobalConfigState.ALL_ASK to R.string.pilot_tier_all_ask,
            GlobalConfigState.ALL_ALWAYS to R.string.pilot_tier_all_always,
            GlobalConfigState.DEFAULT to R.string.pilot_config_default,
            GlobalConfigState.CUSTOM to R.string.pilot_config_custom,
            GlobalConfigState.MIXED to R.string.pilot_config_unsaved,
        )
        GlobalConfigState.entries.forEach { state ->
            assertEquals("$state 应有自己的词条", expected[state], globalConfigLabelRes(state))
        }
        val labels = GlobalConfigState.entries.map { globalConfigLabelRes(it) }
        assertEquals("六档词条两两不同，混合不伪装成任何预设", labels.size, labels.distinct().size)
    }

    /** 下拉的五个条目由生产枚举给出：四个直接动作 + 常用自定义预设，词条两两不同。 */
    @Test
    fun selectorEntriesComeFromTheProductionEnumWithDistinctLabels() {
        assertEquals(
            "五条目：默认配置、全部禁止、全部询问审批、全部完全访问、常用自定义预设",
            listOf(
                GlobalConfigAction.DEFAULT,
                GlobalConfigAction.ALL_DENIED,
                GlobalConfigAction.ALL_ASK,
                GlobalConfigAction.ALL_ALWAYS,
                GlobalConfigAction.CUSTOM,
            ),
            GlobalConfigAction.entries.toList(),
        )
        val labels = GlobalConfigAction.entries.map { globalConfigActionLabel(it) }
        assertEquals(5, labels.size)
        assertEquals("五条目词条两两不同", 5, labels.distinct().size)
    }

    /** 三个「全部×」带各自的批量档位（界面按它转发批量拨档），默认/自定义不带。 */
    @Test
    fun uniformActionsCarryTheirTierAndDirectPresetActionsCarryNone() {
        assertEquals(AccessTier.DENIED, GlobalConfigAction.ALL_DENIED.tier)
        assertEquals(AccessTier.ASK, GlobalConfigAction.ALL_ASK.tier)
        assertEquals(AccessTier.ALWAYS, GlobalConfigAction.ALL_ALWAYS.tier)
        assertNull(GlobalConfigAction.DEFAULT.tier)
        assertNull(GlobalConfigAction.CUSTOM.tier)
        // 三个「全部×」与批量档位共用同一套措辞（同源，不各写一份）
        assertEquals(globalConfigLabelRes(GlobalConfigState.ALL_DENIED), globalConfigActionLabel(GlobalConfigAction.ALL_DENIED))
        assertEquals(globalConfigLabelRes(GlobalConfigState.ALL_ASK), globalConfigActionLabel(GlobalConfigAction.ALL_ASK))
        assertEquals(globalConfigLabelRes(GlobalConfigState.ALL_ALWAYS), globalConfigActionLabel(GlobalConfigAction.ALL_ALWAYS))
    }
}
