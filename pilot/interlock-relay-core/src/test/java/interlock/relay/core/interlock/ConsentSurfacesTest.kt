package interlock.relay.core.interlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统授权界面的判据。
 *
 * 这条判据决定"AI 能不能替用户答应别的应用的权限申请"，所以两侧都要钉住：
 * 漏判等于放开授权框，误判等于把普通设置页也拦成每次必问。
 */
class ConsentSurfacesTest {

    @Test
    fun permissionHostsAreConsentSurfaces() {
        listOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.android.providers.media.module",
            "com.miui.permcenter",
            "com.vivo.permissionmanager",
        ).forEach {
            assertTrue(it, ConsentSurfaces.isConsentPackage(it))
        }
    }

    @Test
    fun ordinaryAppsAreNotConsentSurfaces() {
        listOf(
            "com.android.settings",
            "com.example.app",
            "com.tencent.mm",
            "com.android.chrome",
            null,
            "",
        ).forEach {
            assertFalse(it.toString(), ConsentSurfaces.isConsentPackage(it))
        }
    }

    /** 大小写与首尾空格不能改变判据：包名在参数里是用户/助手写的字符串。 */
    @Test
    fun packageCheckIgnoresCaseAndPadding() {
        assertTrue(ConsentSurfaces.isConsentPackage("  com.android.permissionController  "))
    }

    @Test
    fun permissionButtonIdsAreConsentSurfaces() {
        assertTrue(
            ConsentSurfaces.looksLikeConsentNode(
                "com.example.browser",
                "com.android.permissioncontroller:id/permission_allow_button",
            ),
        )
    }

    /** 归属包名认不出时，view id 是兜底；两者都不命中就不该拦普通按钮。 */
    @Test
    fun ordinaryButtonIsNotAConsentSurface() {
        assertFalse(
            ConsentSurfaces.looksLikeConsentNode(
                "com.android.settings",
                "android:id/button1",
            ),
        )
        assertFalse(ConsentSurfaces.looksLikeConsentNode(null, null))
    }

    /**
     * 执行时那道用的是窄判据：`isSystemOwned` 不参与"直接拒"。
     *
     * 设置应用在很多版本上就跑在 `android.uid.system` 里，把它当授权界面会把用户要调的
     * 音量、亮度滑杆一并拒掉；闸门用宽判据只是多问一次人，执行时这一问没有商量余地。
     */
    @Test
    fun systemOwnedSettingsControlIsNotRefusedAtExecutionTime() {
        val previous = ConsentSurfaces.uidResolver
        ConsentSurfaces.uidResolver = { 1000 }
        try {
            assertTrue(ConsentSurfaces.looksLikeConsentNode("com.android.settings", "android:id/seekbar"))
            assertFalse(ConsentSurfaces.looksLikeConsentControl("com.android.settings", "android:id/seekbar"))
        } finally {
            ConsentSurfaces.uidResolver = previous
        }
    }

    /** 两条硬特征任一命中就要拒：授权组件族的包名，或授权框架的 view 资源 id。 */
    @Test
    fun consentHostOrPermissionViewIdIsRefusedAtExecutionTime() {
        assertTrue(ConsentSurfaces.looksLikeConsentControl("com.android.permissioncontroller", null))
        assertTrue(ConsentSurfaces.looksLikeConsentControl("com.vivo.permissionmanager.sub", null))
        assertTrue(
            ConsentSurfaces.looksLikeConsentControl(
                "com.example.browser",
                "com.android.permissioncontroller:id/permission_allow_button",
            ),
        )
        assertFalse(ConsentSurfaces.looksLikeConsentControl("com.example.app", "android:id/text1"))
    }
}
