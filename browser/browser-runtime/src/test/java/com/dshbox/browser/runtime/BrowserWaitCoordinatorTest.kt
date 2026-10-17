package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

class BrowserWaitCoordinatorTest {
    @Test
    fun selectorProbeUsesBoundedTreeWalkerAndPerElementMatches() {
        val script = BrowserWaitScript.condition("selector", org.json.JSONObject()
            .put("selector", ".result")
            .put("state", "hidden"))

        assertTrue(script.contains("document.createTreeWalker(document, NodeFilter.SHOW_ELEMENT)"))
        assertTrue(script.contains("element.matches(selectorText)"))
        assertTrue(script.contains("hasScopePseudo(selectorText)"))
        assertTrue(script.contains("consumeEscape"))
        assertTrue(script.contains("return 'INVALID_SELECTOR'"))
        assertTrue(script.contains("visited > ${BrowserWaitScript.SELECTOR_SCAN_LIMIT}"))
        assertTrue(script.contains("return 'WAIT_PROBE_LIMIT'"))
        assertTrue(script.contains("catch (_) { return 'INVALID_SELECTOR'; }"))
        assertFalse(script.contains("querySelectorAll"))
    }

    @Test
    fun matchedConditionFinishesOnceAndRemovesItsDeadline() {
        val clock = FakeClock()
        val coordinator = clock.coordinator()
        val results = mutableListOf<BrowserWaitCoordinator.Result>()
        assertTrue(coordinator.start("a", 500, 100, { true }, { it(true, null) }, { results.add(it) }))
        assertEquals(listOf("OK"), results.map { it.code })
        assertEquals(0, coordinator.size())
        clock.advanceTo(500)
        assertEquals(1, results.size)
    }

    @Test
    fun timeoutAndLateProbeCallbackCannotCompleteTwice() {
        val clock = FakeClock()
        val coordinator = clock.coordinator()
        val results = mutableListOf<BrowserWaitCoordinator.Result>()
        var late: ((Boolean?, String?) -> Unit)? = null
        assertTrue(coordinator.start("a", 300, 100, { true }, { late = it }, { results.add(it) }))
        clock.advanceTo(300)
        assertEquals(listOf("WAIT_TIMEOUT"), results.map { it.code })
        late?.invoke(true, null)
        assertEquals(1, results.size)
        assertEquals(0, coordinator.size())
    }

    @Test
    fun cancelAndControlRevocationReleasePendingWaits() {
        val clock = FakeClock()
        val coordinator = clock.coordinator(max = 2)
        val cancelled = mutableListOf<BrowserWaitCoordinator.Result>()
        val revoked = mutableListOf<BrowserWaitCoordinator.Result>()
        assertTrue(coordinator.start("cancel", 500, 100, { true }, { _ -> }, { cancelled.add(it) }))
        assertTrue(coordinator.cancel("cancel"))
        assertEquals("CANCELLED", cancelled.single().code)
        assertTrue(coordinator.start("revoke", 500, 100, { true }, { _ -> }, { revoked.add(it) }))
        coordinator.cancelAll()
        assertEquals("SESSION_REVOKED", revoked.single().code)
        assertEquals(0, coordinator.size())
    }

    @Test
    fun pageSwitchFailsValidationAndConcurrencyIsBounded() {
        val clock = FakeClock()
        val coordinator = clock.coordinator(max = 1)
        var samePage = true
        val results = mutableListOf<BrowserWaitCoordinator.Result>()
        assertTrue(coordinator.start("one", 500, 100, { samePage }, { done -> done(false, null) }, { results.add(it) }))
        assertFalse(coordinator.start("two", 500, 100, { true }, { _ -> }, { }))
        samePage = false
        clock.advanceTo(100)
        assertEquals("SESSION_REVOKED", results.single().code)
        assertEquals(0, coordinator.size())
    }

    @Test
    fun revalidationRevokesHungProbeWithoutWaitingForItsCallback() {
        val clock = FakeClock()
        val coordinator = clock.coordinator()
        var samePage = true
        var late: ((Boolean?, String?) -> Unit)? = null
        val results = mutableListOf<BrowserWaitCoordinator.Result>()
        assertTrue(coordinator.start("page", 500, 100, { samePage }, { late = it }, { results.add(it) }))
        samePage = false
        coordinator.revalidate()
        assertEquals("SESSION_REVOKED", results.single().code)
        late?.invoke(true, null)
        assertEquals(1, results.size)
        assertEquals(0, coordinator.size())
    }

    private class FakeClock(var now: Long = 0) {
        private data class Item(val at: Long, val sequence: Long, val handle: FakeHandle, val task: () -> Unit)
        private data class FakeHandle(val sequence: Long) : BrowserWaitCoordinator.Handle
        private val queue = PriorityQueue<Item>(compareBy<Item> { it.at }.thenBy { it.sequence })
        private var sequence = 0L

        fun coordinator(max: Int = BrowserWaitCoordinator.DEFAULT_MAX_CONCURRENT) = BrowserWaitCoordinator(
            clock = { now },
            scheduler = object : BrowserWaitCoordinator.Scheduler {
                override fun postDelayed(delayMillis: Long, task: () -> Unit): BrowserWaitCoordinator.Handle {
                    val handle = FakeHandle(++sequence)
                    queue += Item(now + delayMillis, sequence, handle, task)
                    return handle
                }
                override fun remove(handle: BrowserWaitCoordinator.Handle) {
                    queue.removeAll { it.handle == handle }
                }
            },
            maxConcurrent = max,
        )

        fun advanceTo(target: Long) {
            while (queue.peek()?.at?.let { it <= target } == true) {
                val item = queue.remove()
                now = item.at
                item.task()
            }
            now = target
        }
    }
}
