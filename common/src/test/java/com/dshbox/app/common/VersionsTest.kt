package com.dshbox.app.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the shared version comparator (1.1.0, M6 — previously duplicated in
 * DshLayer and RuntimeUpdateManager). Covers the exact strings that flow through
 * the DSH update arbitration.
 */
class VersionsTest {

    @Test
    fun numericComparison() {
        assertTrue(Versions.isNewer("0.2.0", "0.1.1"))
        assertTrue(Versions.isNewer("1.0.0", "0.9.9"))
        assertFalse(Versions.isNewer("0.1.1", "0.1.1"))
        assertEquals(0, Versions.compare("0.1.1", "0.1.1"))
    }

    @Test
    fun preReleaseMarkerBreaksTies() {
        assertTrue(Versions.isNewer("0.1.1-rc.2", "0.1.1-rc.1"))
        assertTrue(Versions.isNewer("0.1.1", "0.1.1-rc.2"))
        assertTrue(Versions.isNewer("0.1.2-alpha.2", "0.1.1-rc.2"))
    }

    @Test
    fun vPrefixIsIgnored() {
        assertEquals(0, Versions.compare("v0.1.1", "0.1.1"))
        assertTrue(Versions.isNewer("v0.2.0", "0.1.1"))
    }

    @Test
    fun patchedSuffixComparesGreaterThanBare() {
        // The bundled asset name derives version "0.1.1-rc.2-patched"; bare npm
        // releases are recorded WITHOUT the marker after the 1.1.0 fix.
        assertTrue(Versions.isNewer("0.1.1-rc.2-patched", "0.1.1-rc.2"))
    }

    @Test
    fun unknownVersionComparesOlderThanAnyRealRelease() {
        // "unknown" has no numeric segments: every segment compares 0 vs real
        // numbers, so any real release wins. This is why a failed version
        // discovery used to let the bundled provision overwrite an offline import.
        assertTrue(Versions.isNewer("0.1.1-rc.2", "unknown"))
        assertTrue(Versions.isNewer("0.0.1", "unknown"))
    }
}
