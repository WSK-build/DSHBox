package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserVisualActionIdentityTest {
    private fun identity() = BrowserVisualIdentity(
        pageId = "page-A", documentGeneration = 4, documentToken = "c".repeat(32), navigationId = 9,
        sessionGeneration = 3, controlGeneration = 2, viewInstanceGeneration = 11,
        bitmapWidth = 540, bitmapHeight = 960, webViewWidth = 540, webViewHeight = 960,
        scrollX = 0, scrollY = 150, scale = 2.0f, density = 2.0f,
        rotation = 0, viewportGeneration = 7,
        mutationGeneration = 17, visualViewportWidth = 270.0, visualViewportHeight = 480.0,
        visualViewportOffsetX = 0.0, visualViewportOffsetY = 0.0, devicePixelRatio = 2.0,
        visualViewportScale = 1.0,
        animationsActive = false,
    )

    private class Harness {
        var now = 100L
        var serial = 0
        val registry = BrowserVisualActionTokenRegistry(
            nowElapsedMillis = { now },
            randomToken = { (++serial).toString(16).padStart(32, '0') },
        )
    }

    @Test fun pixelDigestIsBoundToTheSingleUseScreenshotAndInvalidDigestCannotIssue() {
        val h = Harness()
        val captured = identity()
        assertNull(h.registry.issue(captured, captured, "not-a-digest"))
        val digest = "a".repeat(64)
        val token = h.registry.issue(captured, captured, digest)!!
        assertEquals(digest, h.registry.consume(token, captured, BrowserVisualPoint(1.0, 1.0)).pixelDigest)
        assertEquals(BrowserVisualActionCode.TOKEN_NOT_FOUND, h.registry.consume(token, captured, BrowserVisualPoint(1.0, 1.0)).code)
    }

    @Test fun renderingSettingChangeRevokesPreviouslyIssuedScreenshotTokens() {
        val h = Harness()
        val page = identity()
        val token = h.registry.issue(page, page)!!
        h.registry.invalidateAll()
        assertEquals(BrowserVisualActionCode.TOKEN_NOT_FOUND,
            h.registry.consume(token, page, BrowserVisualPoint(1.0, 1.0)).code)
        val fresh = h.registry.issue(page, page)!!
        assertEquals(BrowserVisualActionCode.OK, h.registry.consume(fresh, page, BrowserVisualPoint(1.0, 1.0)).code)
    }

    @Test fun screenshotIsRejectedIfViewportChangesDuringCapture() {
        val h = Harness()
        val start = identity()
        assertNull(h.registry.issue(start, start.copy(scrollY = 151)))
        assertEquals(0, h.registry.size())
    }

    @Test fun validBitmapPixelMapsOnlyByCapturedDimensionsAndTokenIsOneShot() {
        val h = Harness()
        val captured = identity()
        val token = h.registry.issue(captured, captured)!!
        val result = h.registry.consume(token, captured, BrowserVisualPoint(270.0, 960.0 - 0.5))
        assertEquals(BrowserVisualActionCode.OK, result.code)
        assertEquals(135.0, result.webViewPoint!!.x, 0.0001)
        assertEquals(479.75, result.webViewPoint.y, 0.0001)
        assertEquals(BrowserVisualActionCode.TOKEN_NOT_FOUND,
            h.registry.consume(token, captured, BrowserVisualPoint(1.0, 1.0)).code)
    }

    @Test fun rejectsEachPageSessionAndViewportChange() {
        fun assertConsumedFailure(changed: BrowserVisualIdentity, expected: BrowserVisualActionCode) {
            val h = Harness(); val token = h.registry.issue(identity(), identity())!!
            assertEquals("changed identity: $changed", expected,
                h.registry.consume(token, changed, BrowserVisualPoint(1.0, 1.0)).code)
            assertEquals("failed validation consumes token", BrowserVisualActionCode.TOKEN_NOT_FOUND,
                h.registry.consume(token, identity(), BrowserVisualPoint(1.0, 1.0)).code)
        }
        val staleChanges = listOf(
            identity().copy(pageId = "page-B"), identity().copy(documentGeneration = 5),
            identity().copy(documentToken = "d".repeat(32)),
            identity().copy(navigationId = 10), identity().copy(sessionGeneration = 4),
            identity().copy(controlGeneration = 3), identity().copy(viewInstanceGeneration = 12),
        )
        for (changed in staleChanges) {
            assertConsumedFailure(changed, BrowserVisualActionCode.STALE_SCREENSHOT)
        }
        val viewportChanges = listOf(
            identity().copy(bitmapWidth = 1000), identity().copy(bitmapHeight = 1800),
            identity().copy(webViewWidth = 500), identity().copy(webViewHeight = 900),
            identity().copy(scrollX = 1), identity().copy(scrollY = 151),
            identity().copy(scale = 1.1f), identity().copy(density = 2.1f),
            identity().copy(rotation = 90), identity().copy(viewportGeneration = 8),
            identity().copy(mutationGeneration = 18), identity().copy(visualViewportWidth = 269.0),
            identity().copy(devicePixelRatio = 2.1),
        )
        for (changed in viewportChanges) {
            assertConsumedFailure(changed, BrowserVisualActionCode.VIEWPORT_CHANGED)
        }
        val invalidChanges = listOf(
            identity().copy(visualViewportOffsetX = 0.1),
            identity().copy(visualViewportOffsetY = 0.1),
            identity().copy(animationsActive = true),
        )
        for (changed in invalidChanges) {
            assertConsumedFailure(changed, BrowserVisualActionCode.STALE_SCREENSHOT)
        }
    }

    @Test fun expiryAndOutOfBoundsAreExplicitAndConsumeToken() {
        val h = Harness()
        val expired = h.registry.issue(identity(), identity())!!
        h.now += BrowserVisualActionTokenRegistry.DEFAULT_TOKEN_TTL_MILLIS
        assertEquals(BrowserVisualActionCode.TOKEN_EXPIRED,
            h.registry.consume(expired, identity(), BrowserVisualPoint(1.0, 1.0)).code)

        h.now = 200
        val token = h.registry.issue(identity(), identity())!!
        assertEquals(BrowserVisualActionCode.COORDINATE_OUT_OF_BOUNDS,
            h.registry.consume(token, identity(), BrowserVisualPoint(540.0, 1.0)).code)
        assertEquals(BrowserVisualActionCode.TOKEN_NOT_FOUND,
            h.registry.consume(token, identity(), BrowserVisualPoint(1.0, 1.0)).code)
    }

    @Test fun mappingUsesScaleWithoutMultiplyingDensityAgainAndRejectsInconsistentZoom() {
        for ((density, zoom) in listOf(2.0 to 1.0, 3.0 to 1.25)) {
            val physicalScale = density * zoom
            val width = 600
            val height = 900
            val measured = identity().copy(
                bitmapWidth = width, bitmapHeight = height, webViewWidth = width, webViewHeight = height,
                scale = physicalScale.toFloat(), density = density.toFloat(),
                visualViewportWidth = width / physicalScale, visualViewportHeight = height / physicalScale,
                devicePixelRatio = density, visualViewportScale = zoom,
            )
            val h = Harness()
            val token = h.registry.issue(measured, measured)!!
            val result = h.registry.consume(token, measured, BrowserVisualPoint(300.0, 450.0))
            assertEquals(BrowserVisualActionCode.OK, result.code)
            assertEquals(300.0 / physicalScale, result.webViewPoint!!.x, 0.0001)
            assertEquals(450.0 / physicalScale, result.webViewPoint.y, 0.0001)
        }
        val inconsistent = identity()
        val h = Harness()
        val token = h.registry.issue(inconsistent, inconsistent)!!
        assertEquals(BrowserVisualActionCode.VIEWPORT_CHANGED,
            h.registry.consume(token, inconsistent.copy(scale = 3.0f), BrowserVisualPoint(20.0, 20.0)).code)
    }

    @Test fun registryRetainsOnlyEightRecentTokens() {
        val h = Harness()
        val tokens = (0 until 10).map { h.registry.issue(identity(), identity())!! }
        assertEquals(BrowserVisualActionTokenRegistry.MAX_TOKENS, h.registry.size())
        assertEquals(BrowserVisualActionCode.TOKEN_NOT_FOUND,
            h.registry.consume(tokens.first(), identity(), BrowserVisualPoint(1.0, 1.0)).code)
        assertEquals(BrowserVisualActionCode.OK,
            h.registry.consume(tokens.last(), identity(), BrowserVisualPoint(1.0, 1.0)).code)
        assertTrue(h.registry.size() < BrowserVisualActionTokenRegistry.MAX_TOKENS)
    }

    @Test fun invalidOrRepeatedRandomTokenProviderFailsAfterBoundedAttempts() {
        var calls = 0
        val registry = BrowserVisualActionTokenRegistry(
            nowElapsedMillis = { 1L },
            randomToken = { calls++; "not-a-token" },
        )
        assertNull(registry.issue(identity(), identity()))
        assertEquals(BrowserVisualActionTokenRegistry.MAX_TOKEN_ATTEMPTS, calls)

        var fixedCalls = 0
        val repeated = BrowserVisualActionTokenRegistry(
            nowElapsedMillis = { 1L },
            randomToken = { fixedCalls++; "a".repeat(32) },
        )
        assertTrue(repeated.issue(identity(), identity()) != null)
        assertNull(repeated.issue(identity(), identity()))
        assertEquals(1 + BrowserVisualActionTokenRegistry.MAX_TOKEN_ATTEMPTS, fixedCalls)
    }

    @Test fun negativeElapsedTimeCannotAuthorizeAVisualAction() {
        var now = 10L
        val registry = BrowserVisualActionTokenRegistry(
            nowElapsedMillis = { now },
            randomToken = { "b".repeat(32) },
        )
        val token = registry.issue(identity(), identity())!!
        now = -1L
        assertEquals(BrowserVisualActionCode.TOKEN_EXPIRED,
            registry.consume(token, identity(), BrowserVisualPoint(1.0, 1.0)).code)
    }
}
