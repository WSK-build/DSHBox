package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserUiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserWebThemeTest {
    @Test
    fun explicitModesResolveCanvasAndAlgorithmicDarkeningTogether() {
        val light = BrowserWebThemePolicy.resolve(BrowserUiSettings.THEME_LIGHT, systemDark = true)
        assertFalse(light.dark)
        assertEquals(BrowserRuntimePalette.create(dark = false).paper, light.backgroundColor)
        assertFalse(light.algorithmicDarkeningAllowed)

        val dark = BrowserWebThemePolicy.resolve(BrowserUiSettings.THEME_DARK, systemDark = false)
        assertTrue(dark.dark)
        assertEquals(BrowserRuntimePalette.create(dark = true).paper, dark.backgroundColor)
        assertTrue(dark.algorithmicDarkeningAllowed)
    }

    @Test
    fun systemModeTracksNewSystemAppearanceWhenReapplied() {
        val light = BrowserWebThemePolicy.resolve(BrowserUiSettings.THEME_SYSTEM, systemDark = false)
        val dark = BrowserWebThemePolicy.resolve(BrowserUiSettings.THEME_SYSTEM, systemDark = true)
        assertFalse(light.dark)
        assertTrue(dark.dark)
        assertTrue(light.backgroundColor != dark.backgroundColor)
    }

    @Test
    fun olderAndroidCanUseNewerProviderFeatureWhenSupported() {
        val target = RecordingTarget()
        val policy = BrowserWebThemePolicy.resolve(BrowserUiSettings.THEME_DARK, systemDark = false)

        BrowserWebTheme.apply(target, policy, supportsAlgorithmicDarkening = true)

        assertEquals(policy.backgroundColor, target.backgroundColor)
        assertEquals(true, target.algorithmicDarkeningAllowed)
    }

    @Test
    fun unsupportedProviderGetsCanvasColorWithoutCallingFeatureApi() {
        val target = RecordingTarget()
        val policy = BrowserWebThemePolicy.resolve(BrowserUiSettings.THEME_DARK, systemDark = false)

        BrowserWebTheme.apply(target, policy, supportsAlgorithmicDarkening = false)

        assertEquals(policy.backgroundColor, target.backgroundColor)
        assertNull(target.algorithmicDarkeningAllowed)
    }

    @Test
    fun supportedProviderExplicitlyDisablesDarkeningWhenThemeReturnsToLight() {
        val target = RecordingTarget()
        val dark = BrowserWebThemePolicy.resolve(BrowserUiSettings.THEME_DARK, systemDark = false)
        val light = BrowserWebThemePolicy.resolve(BrowserUiSettings.THEME_LIGHT, systemDark = true)

        BrowserWebTheme.apply(target, dark, supportsAlgorithmicDarkening = true)
        BrowserWebTheme.apply(target, light, supportsAlgorithmicDarkening = true)

        assertEquals(light.backgroundColor, target.backgroundColor)
        assertEquals(false, target.algorithmicDarkeningAllowed)
    }

    private class RecordingTarget : BrowserWebThemeTarget {
        var backgroundColor: Int? = null
        var algorithmicDarkeningAllowed: Boolean? = null
        override fun setBackgroundColor(color: Int) { backgroundColor = color }
        override fun setAlgorithmicDarkeningAllowed(allowed: Boolean) {
            algorithmicDarkeningAllowed = allowed
        }
    }
}
