package com.dshbox.app.common

import org.junit.Assert.assertEquals
import org.junit.Test

/** 主 home 常量与实例参数化函数的一致性锁：常量必须恒等于函数(MAIN)。 */
class ConstantsInstancePathsTest {

    @Test
    fun mainConstants_equalParameterizedFunctions() {
        val main = DshInstance.MAIN
        assertEquals(Constants.DSHBOX_ASSETS_RELATIVE_DIR, Constants.dshboxAssetsRelativeDir(main))
        assertEquals(Constants.DSHBOX_ASSETS_GUEST_DIR, Constants.dshboxAssetsGuestDir(main))
        assertEquals(Constants.DSHBOX_PLUGINS_RELATIVE_DIR, Constants.dshboxPluginsRelativeDir(main))
        assertEquals(Constants.DSHBOX_PLUGINS_GUEST_DIR, Constants.dshboxPluginsGuestDir(main))
        assertEquals(Constants.DSH_WEB_PROFILE_GUEST_DIR, Constants.dshWebProfileGuestDir(main))
        assertEquals(
            Constants.DSH_PLUGIN_MARKET_OVERLAY_RELATIVE_PATH,
            Constants.dshPluginMarketOverlayRelativePath(main),
        )
        assertEquals(
            Constants.DSH_PLUGIN_MARKET_OVERLAY_GUEST_PATH,
            Constants.dshPluginMarketOverlayGuestPath(main),
        )
        assertEquals(
            Constants.DSH_SAFE_MODE_ABSOLUTE_OVERLAY_RELATIVE_PATH,
            Constants.dshSafeModeAbsoluteOverlayRelativePath(main),
        )
        assertEquals(
            Constants.DSH_SAFE_MODE_ABSOLUTE_OVERLAY_GUEST_PATH,
            Constants.dshSafeModeAbsoluteOverlayGuestPath(main),
        )
        assertEquals(
            Constants.DSH_OFFICIAL_PLUGIN_OVERLAY_RELATIVE_PATH,
            Constants.dshOfficialPluginOverlayRelativePath(main),
        )
        assertEquals(
            Constants.DSH_OFFICIAL_PLUGIN_OVERLAY_GUEST_PATH,
            Constants.dshOfficialPluginOverlayGuestPath(main),
        )
        assertEquals(
            Constants.DSH_WEBSERVER_OVERLAY_RELATIVE_PATH,
            Constants.dshWebserverOverlayRelativePath(main),
        )
        assertEquals(
            Constants.DSH_WEBSERVER_OVERLAY_GUEST_PATH,
            Constants.dshWebserverOverlayGuestPath(main),
        )
    }

    @Test
    fun emergencyPaths_resolveUnderEmergencyHome() {
        val emergency = DshInstance.EMERGENCY
        assertEquals(".dsh-emergency/dshbox", Constants.dshboxAssetsRelativeDir(emergency))
        assertEquals(
            "/root/projects/.dsh-emergency/profiles/web",
            Constants.dshWebProfileGuestDir(emergency),
        )
        assertEquals(
            "/root/projects/.dsh-emergency/dshbox/webserver-port/webserver-port-overlay.yml",
            Constants.dshWebserverOverlayGuestPath(emergency),
        )
    }
}