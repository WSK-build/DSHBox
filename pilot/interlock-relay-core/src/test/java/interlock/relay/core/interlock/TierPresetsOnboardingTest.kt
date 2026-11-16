package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.spi.RelayPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新老用户分界与自定义配置的保存/使用语义。
 *
 * 新安装：主偏好文件里没有任何既有键 → 一次性把默认配置写进 40 条普通能力；
 * 老用户升级：文件里已有既有键（既有安装见证 / 档位键）→ 一条不写；此后任何时点
 * 都不再自动套预设，新增能力只落自身默认值。自定义配置「保存」只入库不套用，
 * 「使用」才一次性套上；范围不符的表拒绝保存。
 */
class TierPresetsOnboardingTest {

    // ── 新安装 ──

    @Test
    fun freshInstallGetsTheDefaultPresetWrittenExplicitly() {
        val prefs = MemoryPrefs()
        val tiers = TierStore(prefs)
        TierPresets.GLOBAL_SCOPE.forEach { id ->
            assertEquals("$id 新装后应为默认配置档位", TierPresets.DEFAULT.getValue(id), tiers.tierOf(id))
        }
        // 档位是显式写下的键，不是回退默认：后续构造不再重算
        assertTrue(TierPresets.GLOBAL_SCOPE.all { prefs.getString(RelayPrefs.FILE_MAIN, "tier_${it.wire.replace('.', '_')}", null) != null })
        assertEquals("done", prefs.getString(RelayPrefs.FILE_MAIN, "tier_onboarding_v2", null))
    }

    @Test
    fun interruptedOnboardingResumesAndCompletesInsteadOfPassingAsUpgraded() {
        val prefs = MemoryPrefs()
        // 半成品场景：引导状态停在 in_progress，40 条只写出一条就进程被杀。
        prefs.putString(RelayPrefs.FILE_MAIN, "tier_onboarding_v2", "in_progress")
        prefs.putString(RelayPrefs.FILE_MAIN, "tier_ui_tap", AccessTier.ALWAYS.name)
        val reopened = TierStore(prefs)
        // 补齐而不是判成老用户：40 条全部落在默认配置上
        TierPresets.GLOBAL_SCOPE.forEach { id ->
            assertEquals("$id 引导补齐后应为默认配置档位", TierPresets.DEFAULT.getValue(id), reopened.tierOf(id))
        }
        assertEquals("done", prefs.getString(RelayPrefs.FILE_MAIN, "tier_onboarding_v2", null))
    }

    @Test
    fun onboardingMarkerStopsReapplicationOnLaterStarts() {
        val prefs = MemoryPrefs()
        TierStore(prefs)
        // 模拟「档位键被清走、只剩标记」的场景：若引导重跑，默认预设会重新铺满；
        // 判据是引导不再进入，档位落回旧回退默认（pkg.query 是询问审批）。
        TierPresets.GLOBAL_SCOPE.forEach { prefs.strings.remove("tier_${it.wire.replace('.', '_')}") }
        val reopened = TierStore(prefs)
        assertEquals(AccessTier.ASK, reopened.tierOf(CapabilityId.PKG_QUERY))
        assertEquals(AccessTier.ALWAYS, reopened.tierOf(CapabilityId.DEVICE_STATUS))
    }

    // ── 老用户升级 ──

    @Test
    fun upgradedUserWithExistingKeysKeepsEverythingUntouched() {
        val prefs = MemoryPrefs()
        // 既有安装见证：任何一次旧版本装配都会写下审计盐
        prefs.putString(RelayPrefs.FILE_MAIN, RelayPrefs.KEY_DIGEST_SALT, "existing")
        val tiers = TierStore(prefs)
        // 旧回退默认仍在：pkg.query 是询问审批（不因  默认配置变成完全访问）
        assertEquals(AccessTier.ASK, tiers.tierOf(CapabilityId.PKG_QUERY))
        assertEquals(AccessTier.ALWAYS, tiers.tierOf(CapabilityId.DEVICE_STATUS))
        // 老用户只写引导状态（done），一条档位都不铺
        assertEquals("done", prefs.getString(RelayPrefs.FILE_MAIN, "tier_onboarding_v2", null))
        // 用户的既有手动档位原样保留
        tiers.setTier(CapabilityId.MEDIA_READ, AccessTier.DENIED)
        assertEquals(AccessTier.DENIED, TierStore(prefs).tierOf(CapabilityId.MEDIA_READ))
        // 引导没有铺预设：没有任何档位键被批量写出来
        assertFalse(prefs.strings.keys.any { it.startsWith("tier_") && it != "tier_media_read" && it != "tier_onboarding_v2" })
    }

    @Test
    fun userWithStoredTierKeysButNoSaltIsStillAnUpgradedUser() {
        val prefs = MemoryPrefs()
        prefs.putString(RelayPrefs.FILE_MAIN, "tier_ui_tap", AccessTier.ALWAYS.name)
        val tiers = TierStore(prefs)
        assertEquals(AccessTier.ALWAYS, tiers.tierOf(CapabilityId.UI_TAP))
        // 其余条目不因  被扫成默认配置
        assertEquals(AccessTier.ASK, tiers.tierOf(CapabilityId.PKG_QUERY))
        assertNull(prefs.getString(RelayPrefs.FILE_MAIN, "tier_pkg_query", null))
    }

    // ── 自定义配置：保存 / 使用 / 丢弃 ──

    @Test
    fun savingACustomPresetDoesNotApplyIt() {
        val prefs = MemoryPrefs()
        val tiers = TierStore(prefs)
        val store = CustomPresetStore(prefs)
        tiers.applyPreset(TierPresets.DEFAULT)
        val custom = TierPresets.DEFAULT.toMutableMap().apply {
            put(CapabilityId.MEDIA_READ, AccessTier.DENIED)
            put(CapabilityId.UI_TAP, AccessTier.DENIED)
        }
        assertTrue(store.save(custom))
        // 保存只入库：已应用的档位纹丝不动（默认配置里 media.read 是询问审批、ui.tap 是完全访问）
        assertEquals(AccessTier.ASK, tiers.tierOf(CapabilityId.MEDIA_READ))
        assertEquals(AccessTier.ALWAYS, tiers.tierOf(CapabilityId.UI_TAP))
        // 「使用」一次性套上
        tiers.applyPreset(store.load()!!)
        assertEquals(AccessTier.DENIED, tiers.tierOf(CapabilityId.MEDIA_READ))
        assertEquals(AccessTier.DENIED, tiers.tierOf(CapabilityId.UI_TAP))
    }

    @Test
    fun usingWithoutASavedCustomIsUnavailable() {
        val prefs = MemoryPrefs()
        val store = CustomPresetStore(prefs)
        val tiers = TierStore(prefs)
        assertFalse(store.hasSaved())
        assertNull(store.load())
        // 「使用」入口的纯函数等价（装配根 applyCustomPreset 的判据同款）：
        // 没保存过返回 false，档位保持已应用的原样，不是空操作成功也不是异常。
        tiers.applyPreset(TierPresets.DEFAULT)
        val saved = store.load()
        val applied = saved?.let { tiers.applyPreset(it); true } ?: false
        assertFalse(applied)
        assertEquals(TierPresets.DEFAULT.getValue(CapabilityId.MEDIA_READ), tiers.tierOf(CapabilityId.MEDIA_READ))
    }

    @Test
    fun partialCustomPresetIsRejectedAndHalfWrittenConfigLoadsAsNothing() {
        val prefs = MemoryPrefs()
        val store = CustomPresetStore(prefs)
        val partial = TierPresets.DEFAULT.toMutableMap().apply { remove(CapabilityId.UI_TAP) }
        assertFalse("范围不齐的表拒绝保存", store.save(partial))
        assertNull(store.load())
        // 存进去之后被截断/改坏的串也宁可当没存过，不猜缺的那几条
        prefs.putString(RelayPrefs.FILE_MAIN, "custom_tier_preset", "ui.tap=ALWAYS;screen.capture=ASK")
        assertNull(store.load())
    }

    @Test
    fun customPresetRoundTripsThroughStorage() {
        val prefs = MemoryPrefs()
        val store = CustomPresetStore(prefs)
        val custom = TierPresets.GLOBAL_SCOPE.associate {
            it to when (it) {
                CapabilityId.SYS_SHELL -> AccessTier.ALWAYS
                CapabilityId.MEDIA_READ -> AccessTier.DENIED
                else -> AccessTier.ASK
            }
        }
        assertTrue(store.save(custom))
        assertEquals(custom, store.load())
        assertTrue(store.hasSaved())
    }
}
