package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationMetadataTest {
    @Test fun acceptedDestinationDoesNotClaimCommitAndEveryUrlIsRedacted() {
        val state = BrowserPageStateTracker.State(1, "https://new.test/path?q=secret#token",
            urlBefore = "https://user:password@old.test/previous?session=secret")
        val data = BrowserNavigationMetadata.addTo(JSONObject(), state)
        assertFalse(data.getBoolean("committed"))
        assertTrue(data.isNull("urlAfter"))
        assertEquals("https://old.test/previous", data.getString("urlBefore"))
        assertEquals("https://new.test/path", data.getString("requestedUrl"))
        assertTrue(data.getBoolean("requestedUrlQueryRedacted"))
        assertTrue(data.getBoolean("requestedUrlFragmentRedacted"))
        assertFalse(data.toString().contains("secret"))
        assertFalse(data.toString().contains("password"))
    }

    @Test fun committedRedirectKeepsRequestedAndActualDestinationDistinct() {
        val state = BrowserPageStateTracker.State(2, "https://first.test/start",
            urlBefore = "https://old.test/", urlAfter = "https://final.test/end?secret=1", committed = true)
        val data = BrowserNavigationMetadata.addTo(JSONObject().put("event", "navigation_finished"), state)
        assertTrue(data.getBoolean("committed"))
        assertEquals("https://final.test/end", data.getString("urlAfter"))
        assertEquals("https://first.test/start", data.getString("requestedUrl"))
        assertEquals("navigation_finished", data.getString("event"))
    }
}
