package com.dshbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesPlainVersions() {
        assertTrue(compareVersions("1.3.0", "1.2.0") > 0)
        assertTrue(compareVersions("1.2.0", "1.3.0") < 0)
        assertEquals(0, compareVersions("1.2.0", "1.2.0"))
    }

    @Test
    fun toleratesVPrefixAndExtraSegments() {
        assertEquals(0, compareVersions("v1.3.0", "1.3.0"))
        assertEquals(0, compareVersions("1.3.0.0", "1.3.0"))
        assertTrue(compareVersions("v1.10.0", "v1.9.9") > 0)
    }

    @Test
    fun ignoresPrereleaseAndBuildSuffix() {
        assertEquals(0, compareVersions("1.3.0-rc.2", "1.3.0"))
        assertEquals(0, compareVersions("1.3.0+build5", "1.3.0"))
        assertTrue(compareVersions("1.3.0", "1.2.9-rc.1") > 0)
    }

    @Test
    fun handlesShortForms() {
        assertEquals(0, compareVersions("1", "1.0.0"))
        assertTrue(compareVersions("1.1", "1.0.5") > 0)
    }
}
