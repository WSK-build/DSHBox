package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserCloseReason
import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Locks the canonical status shape both the session and the mounted window must publish. */
class BrowserStatusPublisherTest {
    @Test fun failedMirrorStillBroadcastsExactLiveStatus() {
        val received = mutableListOf<String>()
        assertFalse(publishBrowserStatusChannels("fresh", { false }, received::add))
        assertEquals(listOf("fresh"), received)
    }

    @Test fun mirrorExceptionStillBroadcastsOnce() {
        val received = mutableListOf<String>()
        assertFalse(publishBrowserStatusChannels("fresh", { throw java.io.IOException("disk") }, received::add))
        assertEquals(listOf("fresh"), received)
    }

    @Test fun successfulMirrorPrecedesLivePublication() {
        val events = mutableListOf<String>()
        assertTrue(publishBrowserStatusChannels("fresh", { events.add("mirror:$it"); true }, { events.add("live:$it") }))
        assertEquals(listOf("mirror:fresh", "live:fresh"), events)
    }
    private fun snapshot(
        lifecycle: BrowserLifecycle,
        owner: BrowserOwner,
        visible: Boolean,
        closeReason: BrowserCloseReason = BrowserCloseReason.UNKNOWN,
    ) = BrowserSnapshot(
        sessionGeneration = 206_849,
        controlGeneration = 12,
        operationGeneration = 0,
        lifecycle = lifecycle,
        owner = owner,
        visible = visible,
        inFlightOperationId = null,
        hiddenAtMillis = 0,
        lastEffectiveOperationCompletedAtMillis = 0,
        idleTimeoutMillis = 300_000,
        operationDeadlineAtMillis = null,
        closeReason = closeReason,
    )

    @Test fun runningStatusCarriesFreshGenerationOwnerVisibilityAndIdle() {
        val json = JSONObject(browserStatusJson(
            snapshot(BrowserLifecycle.ACTIVE, BrowserOwner.AI, visible = false),
            automationEnabled = true,
            nowMillis = 0,
        ))
        assertEquals("idle", json.getString("state"))
        assertEquals(206_849L, json.getLong("sessionGeneration"))
        assertEquals(12L, json.getLong("controlGeneration"))
        assertEquals("ai", json.getString("owner"))
        assertFalse(json.getBoolean("visible"))
        assertTrue(json.getBoolean("automationEnabled"))
        assertTrue(json.has("idleRemainingMs"))
        assertTrue(json.has("lastCloseReason"))
    }

    @Test fun closedStatusCarriesCloseReason() {
        val json = JSONObject(browserStatusJson(
            snapshot(BrowserLifecycle.CLOSED, BrowserOwner.NONE, visible = false, closeReason = BrowserCloseReason.IDLE_TIMEOUT),
            automationEnabled = true,
            nowMillis = 0,
        ))
        assertEquals("closed", json.getString("state"))
        assertEquals("idle_timeout", json.getString("lastCloseReason"))
        assertEquals("none", json.getString("owner"))
    }
}
