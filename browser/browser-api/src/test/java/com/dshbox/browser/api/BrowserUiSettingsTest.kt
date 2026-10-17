package com.dshbox.browser.api

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUiSettingsTest {
    @Test
    fun themeModeAcceptsOnlySupportedValuesAndDefaultsToSystem() {
        assertEquals(BrowserUiSettings.THEME_SYSTEM, BrowserUiSettings.normalizeThemeMode(null))
        assertEquals(BrowserUiSettings.THEME_SYSTEM, BrowserUiSettings.normalizeThemeMode("auto"))
        assertEquals(BrowserUiSettings.THEME_SYSTEM, BrowserUiSettings.normalizeThemeMode("SYSTEM"))
        assertEquals(BrowserUiSettings.THEME_LIGHT, BrowserUiSettings.normalizeThemeMode("LIGHT"))
        assertEquals(BrowserUiSettings.THEME_DARK, BrowserUiSettings.normalizeThemeMode("dark"))
    }

    @Test
    fun localeTagTrimsWhitespaceAndUsesFirstConfiguredLocale() {
        assertEquals("zh-Hans-CN", BrowserUiSettings.normalizeLocaleTag(" zh-Hans-CN, en-US "))
        assertEquals("", BrowserUiSettings.normalizeLocaleTag(null))
        assertEquals("", BrowserUiSettings.normalizeLocaleTag("  "))
    }

    @Test
    fun emptyLocaleSnapshotRemainsDistinctFromAnExplicitLocale() {
        val followSystem = BrowserUiSettings(
            localeTag = BrowserUiSettings.normalizeLocaleTag(""),
            themeMode = BrowserUiSettings.THEME_SYSTEM,
        )
        val explicit = BrowserUiSettings(
            localeTag = BrowserUiSettings.normalizeLocaleTag("en-US"),
            themeMode = BrowserUiSettings.THEME_SYSTEM,
        )

        assertEquals("", followSystem.localeTag)
        assertEquals("en-US", explicit.localeTag)
        org.junit.Assert.assertNotEquals(followSystem.localeTag, explicit.localeTag)
    }

    @Test
    fun localeTagRejectsControlCharactersAndValuesLongerThanLimit() {
        assertEquals("", BrowserUiSettings.normalizeLocaleTag("en\nUS"))
        assertEquals("", BrowserUiSettings.normalizeLocaleTag("en\u007fUS"))
        assertEquals("", BrowserUiSettings.normalizeLocaleTag("a".repeat(129)))
        assertEquals("a".repeat(128), BrowserUiSettings.normalizeLocaleTag("a".repeat(128)))
    }
}
