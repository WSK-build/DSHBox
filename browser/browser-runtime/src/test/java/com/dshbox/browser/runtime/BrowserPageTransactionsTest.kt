package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A failed reparent must never change page-manager state (no lost/orphaned page). The fixture
 * captures the real generated page ids — `add` returns a random id, not the content string.
 */
class BrowserPageTransactionsTest {
    private class Fixture {
        val manager = BrowserPageManager<String>()
        val aId: String
        val bId: String

        init {
            aId = requireNotNull(manager.add("a")).id
            bId = requireNotNull(manager.add("b")).id // active == bId
        }
    }

    @Test fun activateAbortsWithoutMutatingManager() {
        val fixture = Fixture()
        assertFalse(BrowserPageTransactions.activate(fixture.manager, fixture.aId) { false })
        assertEquals(fixture.bId, fixture.manager.activePageId)
        assertNotNull(fixture.manager.page(fixture.aId))
    }

    @Test fun activateSwitchesWhenPrepareAllows() {
        val fixture = Fixture()
        val prepares = AtomicInteger()
        assertTrue(BrowserPageTransactions.activate(fixture.manager, fixture.aId) { prepares.incrementAndGet(); true })
        assertEquals(fixture.aId, fixture.manager.activePageId)
        assertEquals(1, prepares.get())
    }

    @Test fun activateSameActiveIsNoOpWithoutPrepare() {
        val fixture = Fixture()
        assertTrue(BrowserPageTransactions.activate(fixture.manager, fixture.bId) { error("must not reparent") })
        assertEquals(fixture.bId, fixture.manager.activePageId)
    }

    @Test fun closeActiveAbortsWithoutLosingPage() {
        val fixture = Fixture()
        assertNull(BrowserPageTransactions.close(fixture.manager, fixture.bId) { false })
        assertEquals(fixture.bId, fixture.manager.activePageId)
        assertNotNull(fixture.manager.page(fixture.bId))
        assertEquals(2, fixture.manager.size)
    }

    @Test fun closeActiveSucceedsWhenPrepareAllows() {
        val fixture = Fixture()
        val closed = BrowserPageTransactions.close(fixture.manager, fixture.bId) { true }
        assertNotNull(closed)
        assertTrue(closed!!.wasActive)
        assertEquals(fixture.aId, fixture.manager.activePageId)
        assertNull(fixture.manager.page(fixture.bId))
        assertEquals(1, fixture.manager.size)
    }

    @Test fun closeInactiveNeedsNoReparent() {
        val fixture = Fixture()
        val closed = BrowserPageTransactions.close(fixture.manager, fixture.aId) { error("must not reparent") }
        assertNotNull(closed)
        assertFalse(closed!!.wasActive)
        assertEquals(fixture.bId, fixture.manager.activePageId)
        assertEquals(1, fixture.manager.size)
    }

    @Test fun lastPageCloseIsRejected() {
        val manager = BrowserPageManager<String>()
        val onlyId = requireNotNull(manager.add("only")).id
        assertNull(BrowserPageTransactions.close(manager, onlyId) { true })
        assertEquals(onlyId, manager.activePageId)
        assertEquals(1, manager.size)
    }

    @Test fun unknownPageActivateReturnsFalse() {
        val fixture = Fixture()
        assertFalse(BrowserPageTransactions.activate(fixture.manager, "does-not-exist") { error("no prepare") })
        assertEquals(fixture.bId, fixture.manager.activePageId)
    }
}
