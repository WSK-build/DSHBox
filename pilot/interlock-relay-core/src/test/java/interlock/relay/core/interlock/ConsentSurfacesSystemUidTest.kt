package interlock.relay.core.interlock

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 结构判据（窗口属主是不是系统 uid）与两张名单的关系：结构判据只**加**覆盖面，
 * 不摘掉任何今天拦得住的东西；解析器没接上时判定退回名单。
 */
class ConsentSurfacesSystemUidTest {

    @After
    fun restoreResolver() {
        ConsentSurfaces.uidResolver = { null }
    }

    @Test
    fun `a system-owned window from an unknown rom counts as a consent surface`() {
        ConsentSurfaces.uidResolver = { pkg -> if (pkg == "com.rom.unknownperms") 1000 else 10_234 }
        assertTrue(ConsentSurfaces.isConsentPackage("com.rom.unknownperms"))
    }

    @Test
    fun `an ordinary app's window is not turned into a consent surface by the uid rule`() {
        ConsentSurfaces.uidResolver = { 10_234 }
        assertFalse(ConsentSurfaces.isConsentPackage("com.example.notepad"))
    }

    @Test
    fun `the uid lookup never loosens what the lists already catch`() {
        ConsentSurfaces.uidResolver = { 10_234 }
        assertTrue(ConsentSurfaces.isConsentPackage("com.android.permissioncontroller"))
        assertTrue(ConsentSurfaces.isConsentPackage("com.miui.permcenter.grant"))
    }

    @Test
    fun `no resolver attached means the structural rule simply does not fire`() {
        ConsentSurfaces.uidResolver = { null }
        assertFalse(ConsentSurfaces.isSystemOwned("com.rom.unknownperms"))
        // 结构判据不成立不等于整条判据不成立：名单里的那条仍然认得出来。
        assertTrue(ConsentSurfaces.isConsentPackage("com.android.permissioncontroller"))
    }

    @Test
    fun `a resolver that throws is treated as unknown instead of failing the gate`() {
        ConsentSurfaces.uidResolver = { error("package manager is having a day") }
        assertFalse(ConsentSurfaces.isSystemOwned("com.android.permissioncontroller"))
        assertTrue(ConsentSurfaces.isConsentPackage("com.android.permissioncontroller"))
    }
}
