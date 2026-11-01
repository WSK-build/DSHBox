package com.dshbox.dsh.renderer.api

import org.junit.Assert.*
import org.junit.Test

class DshTokenRevisionTrackerTest {
    @Test fun temporaryUnavailabilityDoesNotCreateANewTokenRevision() {
        val tracker = DshTokenRevisionTracker("token-a")
        val revision = tracker.revision
        assertTrue(tracker.update(null))
        assertNull(tracker.revision)
        assertTrue(tracker.update("token-a"))
        assertEquals(revision, tracker.revision)
    }

    @Test fun genuinelyChangedTokenGetsANewRevisionAfterUnavailability() {
        val tracker = DshTokenRevisionTracker("token-a")
        val revision = tracker.revision!!
        tracker.update(null)
        tracker.update("token-b")
        assertEquals(revision + 1, tracker.revision)
        tracker.update("token-a")
        assertEquals(revision + 2, tracker.revision)
    }

    @Test fun emptyTokenIsUnavailableAndDoesNotRearmTheSameToken() {
        val tracker = DshTokenRevisionTracker("")
        assertNull(tracker.token)
        assertNull(tracker.revision)
        assertFalse(tracker.update(null))
        tracker.update("token-a")
        val revision = tracker.revision
        tracker.update("")
        assertNull(tracker.token)
        tracker.update("token-a")
        assertEquals(revision, tracker.revision)
    }

    @Test fun repeatedEmissionIsIdempotentAndStringRepresentationIsRedacted() {
        val tracker = DshTokenRevisionTracker("secret-value")
        assertFalse(tracker.update("secret-value"))
        assertEquals(1L, tracker.revision)
        assertFalse(tracker.toString().contains("secret-value"))
    }

    @Test fun teardownDropsAvailabilityAndRememberedValue() {
        val tracker = DshTokenRevisionTracker("secret-value")
        tracker.clearForRelease()
        assertNull(tracker.token)
        assertNull(tracker.revision)
        assertFalse(tracker.toString().contains("secret-value"))
        tracker.update("secret-value")
        assertEquals(1L, tracker.revision)
    }
}
