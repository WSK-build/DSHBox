package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.spi.RelayPrefs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户没拨过时的默认档位表。三条特殊默认各钉一条：shell 族默认禁止；
 * 设备状态默认完全访问——它只读一组无害的基础数据，按逐次弹框的默认档走
 * 会把它变成新的弹窗来源；其余能力默认询问审批。用户拨过的档位永远覆盖默认值。
 */
class TierDefaultsTest {

    private class MemoryPrefs : RelayPrefs {
        private val strings = HashMap<String, String>()
        override fun getString(file: String, key: String, defValue: String?): String? =
            strings[key] ?: defValue
        override fun putString(file: String, key: String, value: String) { strings[key] = value }
        override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean = defValue
        override fun putBoolean(file: String, key: String, value: Boolean) {}
    }

    @Test
    fun `设备状态默认完全访问`() {
        assertEquals(AccessTier.ALWAYS, TierStore(MemoryPrefs()).tierOf(CapabilityId.DEVICE_STATUS))
    }

    @Test
    fun `shell 族默认禁止而其余默认询问审批`() {
        val tiers = TierStore(MemoryPrefs())
        assertEquals(AccessTier.DENIED, tiers.tierOf(CapabilityId.SYS_SHELL))
        assertEquals(AccessTier.DENIED, tiers.tierOf(CapabilityId.SYS_SHELL_FULL))
        assertEquals(AccessTier.ASK, tiers.tierOf(CapabilityId.MEDIA_READ))
        assertEquals(AccessTier.ASK, tiers.tierOf(CapabilityId.NOTIFY_POST))
    }

    @Test
    fun `用户拨过的档位覆盖默认值`() {
        val tiers = TierStore(MemoryPrefs())
        tiers.setTier(CapabilityId.DEVICE_STATUS, AccessTier.DENIED)
        assertEquals(AccessTier.DENIED, tiers.tierOf(CapabilityId.DEVICE_STATUS))
        tiers.setTier(CapabilityId.DEVICE_STATUS, AccessTier.ASK)
        assertEquals(AccessTier.ASK, tiers.tierOf(CapabilityId.DEVICE_STATUS))
    }
}
