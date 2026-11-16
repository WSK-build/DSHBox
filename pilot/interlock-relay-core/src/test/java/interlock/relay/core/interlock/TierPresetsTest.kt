package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.spi.RelayPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认配置（新用户）的映射表。设计第 14.2 节的三档清单逐条钉死：40 条普通能力
 * 全覆盖、不多一条不少一条；完全 Shizuku（`sys.shell.full`）不在全局应用范围内——
 * 它是独立特权开关，只有自己的两态，任何预设都不得扫动它。
 */
class TierPresetsTest {

    /** 设计表里的「完全访问」行：11 条。 */
    private val expectedAlways = setOf(
        CapabilityId.DEVICE_STATUS,
        CapabilityId.PKG_QUERY,
        CapabilityId.APP_LAUNCH,
        CapabilityId.SURFACE_VIRTUAL,
        CapabilityId.UI_SNAPSHOT,
        CapabilityId.UI_NODE,
        CapabilityId.UI_WAIT_FOR,
        CapabilityId.UI_TAP,
        CapabilityId.UI_CLICK,
        CapabilityId.UI_SWIPE,
        CapabilityId.UI_SCROLL,
    )

    /** 设计表里的「询问审批」行：28 条。 */
    private val expectedAsk = setOf(
        CapabilityId.SCREEN_CAPTURE,
        CapabilityId.SCREEN_OBSERVE,
        CapabilityId.SCREEN_RECORD,
        CapabilityId.NOTIFY_READ,
        CapabilityId.NOTIFY_POST,
        CapabilityId.UI_LONG_CLICK,
        CapabilityId.UI_SELECT,
        CapabilityId.UI_DISMISS,
        CapabilityId.UI_SET_VALUE,
        CapabilityId.UI_SET_PROGRESS,
        CapabilityId.UI_IME_ACTION,
        CapabilityId.UI_TEXT,
        CapabilityId.UI_KEY,
        CapabilityId.APP_STOP,
        CapabilityId.SYS_INTENT,
        CapabilityId.CLIPBOARD_READ,
        CapabilityId.CLIPBOARD_WRITE,
        CapabilityId.CONTACT_READ,
        CapabilityId.CONTACT_WRITE,
        CapabilityId.CALENDAR_READ,
        CapabilityId.CALENDAR_WRITE,
        CapabilityId.LOCATION_READ,
        CapabilityId.MEDIA_READ,
        CapabilityId.MEDIA_WRITE,
        CapabilityId.AUDIO_CAPTURE,
        CapabilityId.PKG_INSTALL,
        CapabilityId.SECURE_SETTINGS,
        CapabilityId.APPOPS_SET,
    )

    /** 设计表里的「禁止」行落在全局范围内的：只有 `sys.shell`（full 不在范围内）。 */
    private val expectedDenied = setOf(CapabilityId.SYS_SHELL)

    @Test
    fun defaultPresetCoversExactlyTheFortyOrdinaryCapabilitiesWithThePlannedTiers() {
        val members = CapabilityRegistry.ordinaryMembers().map { it.id }.toSet()
        assertEquals("普通能力成员集应是 40 条", 40, members.size)
        assertEquals("全局范围应与注册表普通能力成员集完全相等", members, TierPresets.GLOBAL_SCOPE)
        assertEquals(40, TierPresets.DEFAULT.size)

        expectedAlways.forEach { assertEquals("$it 应默认完全访问", AccessTier.ALWAYS, TierPresets.DEFAULT[it]) }
        expectedAsk.forEach { assertEquals("$it 应默认询问审批", AccessTier.ASK, TierPresets.DEFAULT[it]) }
        expectedDenied.forEach { assertEquals("$it 应默认禁止", AccessTier.DENIED, TierPresets.DEFAULT[it]) }
        assertEquals(
            "三档划分应恰好铺满 40 条",
            40,
            expectedAlways.size + expectedAsk.size + expectedDenied.size,
        )
    }

    @Test
    fun fullShellIsOutsideTheGlobalScopeAndKeepsItsOwnDeniedDefault() {
        assertFalse("完全 Shizuku 不随 40 条全局配置应用", CapabilityId.SYS_SHELL_FULL in TierPresets.GLOBAL_SCOPE)
        assertFalse(CapabilityId.SYS_SHELL_FULL in TierPresets.DEFAULT)
        // 它自己的默认态仍是禁止（独立特权开关的出厂位），但这一条不是预设写出来的。
        assertEquals(AccessTier.DENIED, TierStore(MemoryPrefs()).tierOf(CapabilityId.SYS_SHELL_FULL))
    }

    @Test
    fun applyingTheDefaultPresetNeverTouchesThePrivilegedSwitch() {
        val prefs = MemoryPrefs()
        val tiers = TierStore(prefs)
        tiers.setTier(CapabilityId.SYS_SHELL_FULL, AccessTier.ALWAYS)
        // 输入里**带着**特权条目（试图把它拨成禁止）也要被过滤：过滤判据真跑，不靠预设碰巧不含它
        val input = TierPresets.DEFAULT + (CapabilityId.SYS_SHELL_FULL to AccessTier.DENIED)
        tiers.applyPreset(input)
        assertEquals("预设应用后特权开关应保持用户拨的状态", AccessTier.ALWAYS, tiers.tierOf(CapabilityId.SYS_SHELL_FULL))
        assertEquals(AccessTier.ALWAYS, tiers.tierOf(CapabilityId.PKG_QUERY))
    }

    @Test
    fun globalStateNamesEachUniformTierFirst() {
        fun current(tier: AccessTier) = TierPresets.GLOBAL_SCOPE.associateWith { tier }
        assertEquals(GlobalConfigState.ALL_DENIED, TierPresets.globalState(current(AccessTier.DENIED), null))
        assertEquals(GlobalConfigState.ALL_ASK, TierPresets.globalState(current(AccessTier.ASK), null))
        assertEquals(GlobalConfigState.ALL_ALWAYS, TierPresets.globalState(current(AccessTier.ALWAYS), null))
    }

    @Test
    fun globalStateNamesDefaultCustomAndMixed() {
        fun current() = TierPresets.GLOBAL_SCOPE.associate { it to TierPresets.DEFAULT.getValue(it) }
        // 等于默认配置
        assertEquals(GlobalConfigState.DEFAULT, TierPresets.globalState(current(), null))
        // 等于已保存自定义
        val custom = current().toMutableMap().apply { put(CapabilityId.MEDIA_READ, AccessTier.DENIED) }
        assertEquals(GlobalConfigState.CUSTOM, TierPresets.globalState(custom, custom))
        // 保存了自定义、但当前档位仍是默认 → 显示默认配置（当前态说了算，不预支「使用」）
        assertEquals(GlobalConfigState.DEFAULT, TierPresets.globalState(current(), custom))
        // 手动改过、既不等于默认也不等于自定义 → 混合
        val drifted = current().toMutableMap().apply { put(CapabilityId.UI_TAP, AccessTier.ASK) }
        assertEquals(GlobalConfigState.MIXED, TierPresets.globalState(drifted, custom))
        assertEquals(GlobalConfigState.MIXED, TierPresets.globalState(drifted, null))
        // 保存的自定义恰好等于默认配置且当前也等于 → 按判据次序报默认配置
        assertEquals(GlobalConfigState.DEFAULT, TierPresets.globalState(current(), current()))
        // 范围缺条目的场景宁可报混合，也不猜
        val incomplete = current().toMutableMap().apply { remove(CapabilityId.UI_TAP) }
        assertEquals(GlobalConfigState.MIXED, TierPresets.globalState(incomplete, custom))
    }

    @Test
    fun savedCustomSurvivesPresetApplyAndUniformBeatsCustom() {
        val custom = TierPresets.DEFAULT.toMutableMap().apply { put(CapabilityId.MEDIA_READ, AccessTier.ALWAYS) }
        // 「使用」之后：当前档位与已保存自定义全等 → 自定义配置
        assertEquals(GlobalConfigState.CUSTOM, TierPresets.globalState(custom, custom))
        // 之后用户又点了「全部禁止」：均匀档优先于自定义条目
        val uniform = custom.toMutableMap().apply { keys.forEach { put(it, AccessTier.DENIED) } }
        assertEquals(GlobalConfigState.ALL_DENIED, TierPresets.globalState(uniform, custom))
    }
}

/** 与 TierDefaultsTest 同款的主文件内存偏好；档位键与布尔键可读出供断言。 */
internal class MemoryPrefs : RelayPrefs {
    val strings = HashMap<String, String>()
    val booleans = HashMap<String, Boolean>()
    override fun getString(file: String, key: String, defValue: String?): String? =
        strings[key] ?: defValue
    override fun putString(file: String, key: String, value: String) { strings[key] = value }
    override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean =
        booleans[key] ?: defValue
    override fun putBoolean(file: String, key: String, value: Boolean) { booleans[key] = value }
}
