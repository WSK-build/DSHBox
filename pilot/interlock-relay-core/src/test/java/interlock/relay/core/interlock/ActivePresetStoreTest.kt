package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 当前生效预设的身份：单独持久化、读取不按 40 条值反推、老升级场景只迁移一次。
 *
 * 写入路径的五条动作（单独改一条、三个「全部×」、默认配置、常用自定义预设的使用/
 * 保存）的身份判据是纯函数，这里逐条钉死；装配根只是把这些函数接到各自的写入口上。
 */
class ActivePresetStoreTest {

    /** 持久化键本身钉死在测试里：宿主升级迁移与排障都按这个名字找。 */
    private val ACTIVE_PRESET_KEY = "active_preset_v1"

    private fun prefsWith(vararg entries: Pair<String, String>): MemoryPrefs {
        val prefs = MemoryPrefs()
        entries.forEach { (k, v) -> prefs.strings[k] = v }
        return prefs
    }

    private fun tiers(tier: AccessTier) = TierPresets.GLOBAL_SCOPE.associateWith { tier }

    private fun defaultTiers() = TierPresets.GLOBAL_SCOPE.associate { it to TierPresets.DEFAULT.getValue(it) }

    /** 与内置默认配置全等的「自定义」：值反推不出的那个身份，正是这个键要存在的理由。 */
    private fun customEqualToDefault() = defaultTiers()

    /** 与默认配置差一条的自定义。 */
    private fun customDrifted() =
        defaultTiers().toMutableMap().apply { put(CapabilityId.MEDIA_READ, AccessTier.DENIED) }

    // ── 持久化与重启读取 ──

    @Test
    fun identitySurvivesAStoreRebuildOnTheSamePrefs() {
        val prefs = MemoryPrefs()
        val first = ActivePresetStore(prefs)
        first.save(ActivePreset.CUSTOM)
        // 「重启」：同一个偏好文件上新开的 store 实例必须读回同一身份。
        val second = ActivePresetStore(prefs)
        assertEquals(ActivePreset.CUSTOM, second.load())
    }

    @Test
    fun unreadIdentityAndUnparsableValueReadAsNull() {
        assertNull(ActivePresetStore(MemoryPrefs()).load())
        val store = ActivePresetStore(prefsWith(ACTIVE_PRESET_KEY to "NOT_A_STATE"))
        assertNull("解不出的值宁可当没存过，也不猜", store.load())
    }

    // ── 迁移矩阵（无身份的老升级场景） ──

    @Test
    fun migrationMatrixCoversEveryLegacyShape() {
        fun migrate(current: Map<CapabilityId, AccessTier>, custom: Map<CapabilityId, AccessTier>? = null) =
            ActivePresetStore(MemoryPrefs()).resolve(current, custom)

        assertEquals(ActivePreset.ALL_DENIED, migrate(tiers(AccessTier.DENIED)))
        assertEquals(ActivePreset.ALL_ASK, migrate(tiers(AccessTier.ASK)))
        assertEquals(ActivePreset.ALL_ALWAYS, migrate(tiers(AccessTier.ALWAYS)))
        assertEquals(ActivePreset.DEFAULT, migrate(defaultTiers()))
        assertEquals(ActivePreset.CUSTOM, migrate(customDrifted(), customDrifted()))
        // 与任何已知预设都不等（含保存的自定义对不上）→ 混合
        assertEquals(ActivePreset.MIXED, migrate(customDrifted(), null))
        assertEquals(ActivePreset.MIXED, migrate(customDrifted(), customEqualToDefault()))
        // 范围缺条目 → 混合
        val incomplete = defaultTiers().toMutableMap().apply { remove(CapabilityId.UI_TAP) }
        assertEquals(ActivePreset.MIXED, migrate(incomplete))
        // 与默认配置全等的已保存自定义、当前也是均匀档 → 判据次序：均匀档优先，落全部完全访问
        assertEquals(ActivePreset.ALL_ALWAYS, migrate(tiers(AccessTier.ALWAYS), customEqualToDefault()))
    }

    @Test
    fun migrationPersistsOnceAndStopsDerivingFromValues() {
        val prefs = MemoryPrefs()
        val store = ActivePresetStore(prefs)
        assertEquals(ActivePreset.DEFAULT, store.resolve(defaultTiers(), null))
        assertTrue("迁移结果要落盘", ACTIVE_PRESET_KEY in prefs.strings)
        // 迁移后档位再变（哪怕变成另一个预设的形状），身份不再按值反推：
        // 这是「身份单独持久化」存在的全部意义。
        assertEquals(ActivePreset.DEFAULT, store.resolve(tiers(AccessTier.DENIED), null))
        // 重启后同样读的是已持久化的身份，不是重新推导。
        assertEquals(ActivePreset.DEFAULT, ActivePresetStore(prefs).resolve(tiers(AccessTier.ALWAYS), null))
    }

    // ── 写入路径的五条动作 ──

    @Test
    fun singleTierChangeInsideGlobalScopeFallsToMixedAndNeverBackToAPreset() {
        assertEquals(
            "默认配置下单独改一条 → 混合",
            ActivePreset.MIXED,
            ActivePreset.afterTierChange(ActivePreset.DEFAULT, CapabilityId.UI_TAP),
        )
        assertEquals(
            "常用自定义预设下单独改一条 → 混合",
            ActivePreset.MIXED,
            ActivePreset.afterTierChange(ActivePreset.CUSTOM, CapabilityId.MEDIA_READ),
        )
        assertEquals(
            "改回与预设相同的值也不自动回预设：身份只认写入动作",
            ActivePreset.MIXED,
            ActivePreset.afterTierChange(ActivePreset.MIXED, CapabilityId.SYS_SHELL),
        )
    }

    @Test
    fun privilegedSwitchOutsideGlobalScopeLeavesTheIdentityAlone() {
        assertEquals(
            "sys.shell.full 不在 GLOBAL_SCOPE：拨它不动预设身份",
            ActivePreset.DEFAULT,
            ActivePreset.afterTierChange(ActivePreset.DEFAULT, CapabilityId.SYS_SHELL_FULL),
        )
        assertEquals(ActivePreset.CUSTOM, ActivePreset.afterTierChange(ActivePreset.CUSTOM, CapabilityId.SYS_SHELL_FULL))
    }

    @Test
    fun theThreeUniformActionsCarryTheirOwnIdentity() {
        assertEquals(ActivePreset.ALL_DENIED, ActivePreset.afterBulkTier(AccessTier.DENIED))
        assertEquals(ActivePreset.ALL_ASK, ActivePreset.afterBulkTier(AccessTier.ASK))
        assertEquals(ActivePreset.ALL_ALWAYS, ActivePreset.afterBulkTier(AccessTier.ALWAYS))
    }

    @Test
    fun defaultPresetApplyLandsOnDefaultIdentity() {
        assertEquals(ActivePreset.DEFAULT, ActivePreset.afterDefaultApply())
    }

    @Test
    fun customApplyLandsOnCustomIdentityEvenWhenTheSavedPresetEqualsTheDefaultOne() {
        // 用户点的是「常用自定义预设 → 使用」：即使保存的那份与默认配置全等，
        // 标签也锁在常用自定义预设上，不被 40 条值改判成默认配置。
        assertEquals(ActivePreset.CUSTOM, ActivePreset.afterCustomApply())
    }

    @Test
    fun savingWithoutApplyingKeepsTheIdentityWhenNothingChangedElseFallsToMixed() {
        assertEquals(
            "保存后的集合与当前 40 条全等：当前身份原样保留",
            ActivePreset.DEFAULT,
            ActivePreset.afterCustomSave(ActivePreset.DEFAULT, savedEqualsApplied = true),
        )
        assertEquals(
            "保存不套用：生效档位与保存前不同即与任何身份对不上 → 混合",
            ActivePreset.MIXED,
            ActivePreset.afterCustomSave(ActivePreset.DEFAULT, savedEqualsApplied = false),
        )
        assertEquals(ActivePreset.CUSTOM, ActivePreset.afterCustomSave(ActivePreset.CUSTOM, savedEqualsApplied = true))
    }

    // ── 身份 ↔ 选择器状态 ──

    @Test
    fun identityAndSelectorStateMapOneToOne() {
        GlobalConfigState.entries.forEach { state ->
            assertEquals(
                "$state 经迁移判据落入的身份要能映射回同一个选择器状态",
                state,
                ActivePreset.fromGlobalState(state).toGlobalConfigState(),
            )
        }
        assertEquals(GlobalConfigState.entries.size, ActivePreset.entries.size)
    }
}
