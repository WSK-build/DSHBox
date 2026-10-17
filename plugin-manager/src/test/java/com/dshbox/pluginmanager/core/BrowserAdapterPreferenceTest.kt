package com.dshbox.pluginmanager.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdapterPreferenceTest {
    @Test
    fun missingPreferenceDefaultsEnabled() {
        assertTrue(browserPluginEnabledFromPreference(hasStoredValue = false, storedValue = false))
    }

    @Test
    fun explicitDisableSurvivesDefaultSeeding() {
        assertFalse(browserPluginEnabledFromPreference(hasStoredValue = true, storedValue = false))
    }

    @Test
    fun explicitEnableRemainsEnabled() {
        assertTrue(browserPluginEnabledFromPreference(hasStoredValue = true, storedValue = true))
    }
}
