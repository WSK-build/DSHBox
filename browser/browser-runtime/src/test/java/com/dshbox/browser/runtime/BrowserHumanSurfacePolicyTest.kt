package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class BrowserHumanSurfacePolicyTest {
    @Test fun oldDialogDismissalDoesNotRevokeReplacementApprovalPanel() {
        val generation = AtomicLong(10)
        val oldPanel = generation.get()
        val replacement = generation.incrementAndGet()
        assertFalse(closeOwnedHumanSurface(generation, oldPanel))
        assertEquals(replacement, generation.get())
        assertTrue(closeOwnedHumanSurface(generation, replacement))
        assertEquals(replacement + 1, generation.get())
        assertFalse(closeOwnedHumanSurface(generation, replacement))
    }

    @Test fun globalRevocationCannotBeUndoneByOldPanelDismissal() {
        val generation = AtomicLong(3)
        val panel = generation.get()
        val revoked = generation.incrementAndGet()
        assertFalse(closeOwnedHumanSurface(generation, panel))
        assertEquals(revoked, generation.get())
    }
}
