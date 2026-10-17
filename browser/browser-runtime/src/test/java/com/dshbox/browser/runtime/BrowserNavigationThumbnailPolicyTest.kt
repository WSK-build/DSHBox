package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationThumbnailPolicyTest {
    @Test
    fun budgetLeavesTwoHundredMillisAndNeverWaitsPastFiveSeconds() {
        val ordinary = BrowserNavigationThumbnailPolicy.budget(1_000L, 10_000L)!!
        assertEquals(6_000L, ordinary.waitUntilElapsedMillis)
        assertEquals(9_800L, ordinary.captureUntilElapsedMillis)

        val tight = BrowserNavigationThumbnailPolicy.budget(9_000L, 9_300L)!!
        assertEquals(9_100L, tight.waitUntilElapsedMillis)
        assertEquals(9_100L, tight.captureUntilElapsedMillis)

        assertNull(BrowserNavigationThumbnailPolicy.budget(9_100L, 9_300L))
    }

    @Test
    fun readinessRequiresAProgressedCommittedFinishedNavigation() {
        val budget = BrowserNavigationThumbnailPolicy.budget(1_000L, 10_000L)!!
        val accepted = BrowserPageStateTracker.State(
            navigationId = 5,
            requestedUrl = "https://example.test/",
            loadState = BrowserPageStateTracker.LoadState.ACCEPTED,
        )
        assertEquals(
            BrowserNavigationThumbnailReadiness.WAIT,
            BrowserNavigationThumbnailPolicy.readiness(budget, 1_100L, 4, 7, 5, "https://example.test/", 7, accepted),
        )

        val progressed = accepted.copy(
            navigationId = 5,
            loadState = BrowserPageStateTracker.LoadState.FINISHED,
            committed = true,
        )
        assertEquals(
            BrowserNavigationThumbnailReadiness.READY,
            BrowserNavigationThumbnailPolicy.readiness(budget, 1_100L, 4, 7, 5, "https://example.test/", 7, progressed),
        )
        assertEquals(
            BrowserNavigationThumbnailReadiness.READY,
            BrowserNavigationThumbnailPolicy.readiness(budget, 1_100L, 4, 7, 5, "https://example.test/", 8, progressed),
        )
    }

    @Test
    fun readinessReportsFailureTimeoutAndMissingIdentityWithoutCapturing() {
        val budget = BrowserNavigationThumbnailPolicy.budget(1_000L, 10_000L)!!
        val failed = BrowserPageStateTracker.State(
            navigationId = 5,
            requestedUrl = "https://example.test/",
            loadState = BrowserPageStateTracker.LoadState.FAILED,
        )
        assertEquals(
            BrowserNavigationThumbnailReadiness.NAVIGATION_FAILED,
            BrowserNavigationThumbnailPolicy.readiness(budget, 1_100L, 4, 7, 5, "https://example.test/", 7, failed),
        )
        val pending = BrowserPageStateTracker.State(
            navigationId = 5,
            requestedUrl = "https://example.test/",
            loadState = BrowserPageStateTracker.LoadState.ACCEPTED,
        )
        assertEquals(
            BrowserNavigationThumbnailReadiness.TIMEOUT,
            BrowserNavigationThumbnailPolicy.readiness(budget, 6_000L, 4, 7, 5, "https://example.test/", 7, pending),
        )
        assertEquals(
            BrowserNavigationThumbnailReadiness.IDENTITY_CHANGED,
            BrowserNavigationThumbnailPolicy.readiness(budget, 1_100L, 4, 7, 5, "https://example.test/", 7, null),
        )
    }

    @Test
    fun laterNavigationWithDifferentIdentityIsNeverCapturedAsAcceptedNavigation() {
        val budget = BrowserNavigationThumbnailPolicy.budget(1_000L, 10_000L)!!
        val laterNavigation = BrowserPageStateTracker.State(
            navigationId = 6,
            requestedUrl = "https://example.test/later",
            loadState = BrowserPageStateTracker.LoadState.FINISHED,
            committed = true,
        )
        assertEquals(
            BrowserNavigationThumbnailReadiness.IDENTITY_CHANGED,
            BrowserNavigationThumbnailPolicy.readiness(
                budget, 1_100L, 4, 7, 5, "https://example.test/accepted", 8, laterNavigation,
            ),
        )
        assertEquals(
            BrowserNavigationThumbnailReadiness.IDENTITY_CHANGED,
            BrowserNavigationThumbnailPolicy.readiness(
                budget, 1_100L, 4, 7, 5, "https://example.test/redirected", 7, laterNavigation,
            ),
        )
    }

    @Test
    fun scalingPreservesAspectRatioAndBoundsBothEdges() {
        assertEquals(384 to 216, BrowserNavigationThumbnailPolicy.scaledSize(1920, 1080))
        assertEquals(216 to 384, BrowserNavigationThumbnailPolicy.scaledSize(1080, 1920))
        assertEquals(120 to 80, BrowserNavigationThumbnailPolicy.scaledSize(120, 80))
        assertEquals(1 to 384, BrowserNavigationThumbnailPolicy.scaledSize(1, 10_000))
    }

    @Test
    fun successAndFailureShapesAreExactAndNeverExposePixelCoordinates() {
        val success = BrowserNavigationThumbnailPolicy.successData(
            artifactPath = "/opt/browser/out/shot/shot-nav.png",
            width = 384,
            height = 216,
            bytes = 4_096L,
            pageId = "page-1",
            documentGeneration = 7,
            navigationId = 9,
        )
        assertEquals(
            setOf(
                "code", "captured", "artifactPath", "format", "width", "height", "bytes",
                "pageId", "documentGeneration", "navigationId",
            ),
            success.keySet(),
        )
        assertEquals("OK", success.getString("code"))
        assertTrue(success.getBoolean("captured"))
        assertEquals(819_200L, BrowserNavigationThumbnailPolicy.MAX_BYTES)
        assertTrue(success.getInt("width") <= BrowserNavigationThumbnailPolicy.MAX_EDGE_PIXELS)
        assertTrue(success.getInt("height") <= BrowserNavigationThumbnailPolicy.MAX_EDGE_PIXELS)
        assertTrue(success.getLong("bytes") <= BrowserNavigationThumbnailPolicy.MAX_BYTES)
        assertEquals(10, success.length())
        assertFalse(success.has("screenshotId"))
        assertFalse(success.has("actionToken"))

        for (code in listOf("timeout", "cancelled", "identity_changed", null)) {
            val failure = BrowserNavigationThumbnailPolicy.failureData(code)
            assertEquals(setOf("code", "captured"), failure.keySet())
            assertEquals(false, failure.getBoolean("captured"))
            assertEquals(
                BrowserNavigationThumbnailPolicy.normalizeCode(code),
                failure.getString("code"),
            )
        }
        assertEquals("TIMEOUT", BrowserNavigationThumbnailPolicy.failureData("deadline_expired").getString("code"))
    }
}
