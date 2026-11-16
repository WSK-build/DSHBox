package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.spi.RelayCapabilities
import interlock.relay.core.spi.RelayPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 受保护应用名单的存储契约：读写、空名单、包名形状校验（非法整体拒绝）、
 * 升级/重建实例保留、与档位预设互不影响、blocks 的精确匹配语义。
 */
class ProtectedAppsStoreTest {

    private class MemoryPrefs : RelayPrefs {
        val strings = HashMap<String, String>()
        override fun getString(file: String, key: String, defValue: String?): String? =
            strings[key] ?: defValue
        override fun putString(file: String, key: String, value: String) { strings[key] = value }
        override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean = defValue
        override fun putBoolean(file: String, key: String, value: Boolean) {}
    }

    @Test
    fun `新名单为空且 blocks 不命中`() {
        val store = ProtectedAppsStore(MemoryPrefs())
        assertTrue(store.packages().isEmpty())
        assertEquals(0, store.count())
        assertFalse(store.blocks("com.bank"))
        assertFalse(store.blocks(null))
        assertFalse(store.blocks(""))
    }

    @Test
    fun `写入后可读回且 blocks 精确相等`() {
        val prefs = MemoryPrefs()
        val store = ProtectedAppsStore(prefs)
        assertTrue(store.set(setOf("com.bank", "com.wallet")))
        assertEquals(setOf("com.bank", "com.wallet"), store.packages())
        assertEquals(2, store.count())
        assertTrue(store.blocks("com.bank"))
        assertTrue(store.blocks("com.wallet"))
        // 精确相等：前缀/后缀相同的包是两个应用。
        assertFalse(store.blocks("com.bank.extra"))
        assertFalse(store.blocks("xcom.bank"))
        assertFalse(store.blocks("COM.BANK"))
    }

    @Test
    fun `空集清空名单`() {
        val store = ProtectedAppsStore(MemoryPrefs())
        store.set(setOf("com.bank"))
        assertTrue(store.set(emptySet()))
        assertTrue(store.packages().isEmpty())
        assertEquals(0, store.count())
    }

    @Test
    fun `非法包名整体拒绝且不落盘`() {
        val prefs = MemoryPrefs()
        val store = ProtectedAppsStore(prefs)
        store.set(setOf("com.bank"))
        // 一条非法（数字开头）导致整体拒绝，已有的名单保持原样。
        assertFalse(store.set(setOf("com.wallet", "1com.bad")))
        assertEquals(setOf("com.bank"), store.packages())
        assertFalse(store.set(setOf("com bank")))
        assertFalse(store.set(setOf("")))
        // 合法形状：字母开头、点、下划线、数字段都在内（形状校验只挡明显不是包名的输入，
        // 与执行侧 appops/force-stop 的参数判据同一宽松口径，"com." 这种末尾点由系统侧拒绝）。
        assertTrue(store.set(setOf("com.example_2.app1")))
    }

    @Test
    fun `重建实例后名单保留（升级与进程重建不清空）`() {
        val prefs = MemoryPrefs()
        val store = ProtectedAppsStore(prefs)
        store.set(setOf("com.bank", "com.wallet"))
        // 新实例 = 升级/进程重建后的同一份偏好文件：名单原样保留。
        // （卸载重装不在保证之内：allowBackup=false 时私有偏好随卸载清空，
        // 代码无法把名单带过一次完整的卸载。）
        val reborn = ProtectedAppsStore(prefs)
        assertEquals(setOf("com.bank", "com.wallet"), reborn.packages())
        assertTrue(reborn.blocks("com.bank"))
    }

    @Test
    fun `档位预设与全局拨档不碰名单`() {
        val prefs = MemoryPrefs()
        val store = ProtectedAppsStore(prefs)
        store.set(setOf("com.bank"))
        // 档位铺默认、全局拨档、自定义配置保存——同一份偏好文件上的这些写都不动名单键。
        val tiers = TierStore(prefs)
        tiers.applyPreset(TierPresets.DEFAULT)
        tiers.setTier(CapabilityId.APP_LAUNCH, AccessTier.DENIED)
        val custom = CustomPresetStore(prefs)
        val preset = CapabilityRegistry.ordinaryMembers(
            RelayCapabilities.BuiltIn.all(),
        ).associate { it.id to AccessTier.ALWAYS }
        assertTrue(custom.save(preset))
        assertEquals(setOf("com.bank"), store.packages())
        assertTrue(store.blocks("com.bank"))
    }

    @Test
    fun `名单写入触发监听`() {
        val store = ProtectedAppsStore(MemoryPrefs())
        var notified = 0
        store.setListener { notified++ }
        store.set(setOf("com.bank"))
        assertEquals(1, notified)
        store.set(emptySet())
        assertEquals(2, notified)
        // 非法写入（整体拒绝）不触发。
        assertFalse(store.set(setOf("bad name")))
        assertEquals(2, notified)
        store.setListener(null)
        store.set(setOf("com.wallet"))
        assertEquals(2, notified)
    }

    @Test
    fun `重复写同一集合返回 true 但不触发监听不落盘`() {
        val prefs = MemoryPrefs()
        val store = ProtectedAppsStore(prefs)
        assertTrue(store.set(setOf("com.bank", "com.wallet")))
        var notified = 0
        store.setListener { notified++ }
        // 同一批包名的重复提交（界面重渲染、状态同步）照常成功，但不刷新监听。
        assertTrue(store.set(setOf("com.wallet", "com.bank")))
        assertEquals(0, notified)
        // 内容真的变了才触发。
        assertTrue(store.set(setOf("com.bank")))
        assertEquals(1, notified)
        // 落盘的值保持最后一次真实写入的内容。
        assertEquals("com.bank", prefs.strings[ProtectedAppsStore.KEY])
    }

    @Test
    fun `同集重复提交时顺手清掉原始键里的非法脏段`() {
        val prefs = MemoryPrefs()
        // 异常写脏的原始值：合法项后面跟着非法段（数字开头、空段）。
        prefs.strings[ProtectedAppsStore.KEY] = "com.bank;1com.bad;;"
        val store = ProtectedAppsStore(prefs)
        assertEquals(setOf("com.bank"), store.packages())
        var notified = 0
        store.setListener { notified++ }
        // 同一集合的重复提交：生效名单没变 → 返回 true、不触发监听，
        // 但原始键里的脏段被清成规范形式（返回值语义不变）。
        assertTrue(store.set(setOf("com.bank")))
        assertEquals(0, notified)
        assertEquals("com.bank", prefs.strings[ProtectedAppsStore.KEY])
        // 脏段全非法时同样收口：空名单落成规范形式，而不是留着坏原文。
        prefs.strings[ProtectedAppsStore.KEY] = "1com.bad;;"
        val store2 = ProtectedAppsStore(prefs)
        assertTrue(store2.set(emptySet()))
        assertEquals("", prefs.strings[ProtectedAppsStore.KEY])
    }

    @Test
    fun `读侧按写入口径校验包名非法项丢弃`() {
        val prefs = MemoryPrefs()
        // 直接在存储键上放一份"异常写脏"的原始值：合法项保留、非法项（数字开头、
        // 带空格、空段）丢弃——与 set() 的形状校验同一口径。
        prefs.strings[ProtectedAppsStore.KEY] = "com.bank;1com.bad;com.wallet;with space;;"
        val store = ProtectedAppsStore(prefs)
        assertEquals(setOf("com.bank", "com.wallet"), store.packages())
        assertTrue(store.blocks("com.bank"))
        assertFalse(store.blocks("1com.bad"))
        // 全部非法 = 当空集（名单未启用），而不是整体作废后抛错。
        prefs.strings[ProtectedAppsStore.KEY] = "1com.bad;with space"
        assertTrue(store.packages().isEmpty())
        assertEquals(0, store.count())
    }

    @Test
    fun `store 构造不依赖任何 Android 类型（JVM 纯净）`() {
        // 与 TierDefaultsTest 同一构造法：只喂 RelayPrefs 假体即可完整驱动。
        val store = ProtectedAppsStore(MemoryPrefs())
        assertNull(store.packages().firstOrNull())
    }

    @Test
    fun `包名形状判据直接可穷举`() {
        assertTrue(ProtectedAppsStore.PACKAGE_SHAPE.matches("a.b"))
        assertTrue(ProtectedAppsStore.PACKAGE_SHAPE.matches("Com.Example.APP_9"))
        assertFalse(ProtectedAppsStore.PACKAGE_SHAPE.matches(".hidden"))
        assertFalse(ProtectedAppsStore.PACKAGE_SHAPE.matches("9app"))
        assertFalse(ProtectedAppsStore.PACKAGE_SHAPE.matches("with space"))
        assertFalse(ProtectedAppsStore.PACKAGE_SHAPE.matches("a".repeat(129)))
        assertTrue(ProtectedAppsStore.PACKAGE_SHAPE.matches("a".repeat(128)))
    }

    @Test
    fun `临时目录不被名单存储触碰`() {
        // 名单只写宿主私有偏好文件；这里只钉一个事实：构造与读写不创建任何文件。
        val dir = Files.createTempDirectory("protected-store")
        val store = ProtectedAppsStore(MemoryPrefs())
        store.set(setOf("com.bank"))
        assertEquals(0, dir.toFile().listFiles()?.size ?: 0)
    }
}
