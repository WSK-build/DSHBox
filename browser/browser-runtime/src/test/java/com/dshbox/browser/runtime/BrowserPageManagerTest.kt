package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserPageManagerTest {
    @Test
    fun newPagesHaveStableUniqueIdsAndRespectTheFourPageLimit() {
        val manager = BrowserPageManager<String>()
        val pages = (1..BrowserPageManager.MAX_PAGES).mapNotNull { manager.add("page-$it") }

        assertEquals(BrowserPageManager.MAX_PAGES, pages.size)
        assertEquals(pages.size, pages.map { it.id }.toSet().size)
        assertSame(pages.last(), manager.active)
        assertNull(manager.add("overflow"))
        assertEquals(pages.map { it.id }, manager.all.map { it.id })
    }

    @Test
    fun switchingAndClosingAnActivePageSelectsAnotherPageWithoutReusingIds() {
        val manager = BrowserPageManager<String>()
        val first = requireNotNull(manager.add("first"))
        val second = requireNotNull(manager.add("second"))
        val third = requireNotNull(manager.add("third"))

        assertSame(first, manager.switchTo(first.id))
        val closed = requireNotNull(manager.close(first.id))
        assertEquals(first.id, closed.removed.id)
        assertSame(third, closed.active)
        assertNotEquals(first.id, closed.active.id)
        assertTrue(manager.all.none { it.id == first.id })
        assertSame(second, manager.switchTo(second.id))
        requireNotNull(manager.close(second.id))
        assertNull(manager.close(third.id))
        assertEquals(1, manager.size)
    }
}
