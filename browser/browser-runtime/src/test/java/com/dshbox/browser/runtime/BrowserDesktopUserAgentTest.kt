package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDesktopUserAgentTest {
    private val webViewDefault =
        "Mozilla/5.0 (Linux; Android 15; TestPhone Build/TEST; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/151.0.7922.199 Mobile Safari/537.36"

    @Test
    fun desktopProfileDropsWebViewMarkersAndKeepsChromeVersion() {
        val desktop = BrowserDesktopUserAgent.from(webViewDefault)

        assertEquals(
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/151.0.7922.199 Safari/537.36",
            desktop,
        )
        assertFalse("wv must not survive in the desktop profile", desktop.contains("wv"))
        assertFalse("Version/4.0 is an Android WebView artifact", desktop.contains("Version/4.0"))
        assertFalse("the Mobile token must be removed", desktop.contains(" Mobile"))
        assertTrue("the real Chrome version must be preserved", desktop.contains("Chrome/151.0.7922.199"))
        assertTrue("Safari/537.36 compatibility token is expected on desktop Chrome", desktop.contains("Safari/537.36"))
        assertFalse("no doubled separators", desktop.contains("  "))
    }

    @Test
    fun desktopProfilePreservesOtherChromeVersionsAndIsStable() {
        val default = webViewDefault.replace("151.0.7922.199", "126.0.6478.122")
        val desktop = BrowserDesktopUserAgent.from(default)

        assertTrue(desktop.contains("Chrome/126.0.6478.122"))
        assertFalse(desktop.contains("Mobile"))
        assertEquals(desktop, BrowserDesktopUserAgent.from(desktop))
    }
}
