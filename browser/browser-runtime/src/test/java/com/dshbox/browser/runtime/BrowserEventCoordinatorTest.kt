package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.PriorityQueue

class BrowserEventCoordinatorTest {
    @Test
    fun waitCancellationPreservesItsRealCauseAndCompletesOnce() {
        val cases: List<Pair<String, (BrowserEventCoordinator, String) -> Unit>> = listOf(
            "user_cancelled" to { coordinator, id -> coordinator.cancelWait(id) },
            "session_changed" to { coordinator, _ -> coordinator.invalidateAll() },
            "page_changed" to { coordinator, _ -> coordinator.invalidatePage("page_a") },
            "task_changed" to { coordinator, _ -> coordinator.invalidateTask("task_a") },
        )
        cases.forEach { (reason, cancel) ->
            val clock = FakeClock()
            val coordinator = clock.coordinator()
            val sub = (coordinator.arm("task_a", identity(), 7, setOf("javascript_dialog"), 1000)
                as BrowserEventCoordinator.ArmResult.Created).subscription
            val results = mutableListOf<JSONObject>()
            assertTrue(coordinator.await(sub.id, "task_a", identity(), 500, results::add))
            cancel(coordinator, sub.id)
            clock.advanceTo(2000)
            assertEquals(1, results.size)
            assertEquals("EVENT_CANCELLED", results.single().getString("code"))
            assertEquals(reason, results.single().getString("reason"))
        }
    }

    @Test
    fun nonceBindsOnlyOnceAndRejectsWrongBinding() {
        val clock = FakeClock()
        val coordinator = clock.coordinator()
        val armed = coordinator.arm("task_a", identity(), 7, setOf("javascript_dialog"), 500)
            as BrowserEventCoordinator.ArmResult.Created
        val sub = armed.subscription

        assertNull(coordinator.bindAction(sub.id, "wrong", "task_a", identity(), "click_1", setOf("javascript_dialog")))
        assertNull(coordinator.bindAction(sub.id, sub.nonce, "task_b", identity(), "click_1", setOf("javascript_dialog")))
        assertTrue(coordinator.bindAction(sub.id, sub.nonce, "task_a", identity(), "click_1", setOf("javascript_dialog")) != null)
        assertNull(coordinator.bindAction(sub.id, sub.nonce, "task_a", identity(), "click_2", setOf("javascript_dialog")))
        assertNull(coordinator.bindAction(sub.id, sub.nonce, "task_a", identity(), "click_1", setOf("javascript_dialog")))
    }

    @Test
    fun finishedOnlyNavigationAdvancesDocumentWithoutRequiringStartEvent() {
        val clock = FakeClock()
        val coordinator = clock.coordinator()
        val old = identity()
        val next = old.copy(documentGeneration = old.documentGeneration + 1, navigationId = old.navigationId + 1)
        val sub = coordinator.arm("task_a", old, 3, setOf("navigation_finished"), 500)
            .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
        coordinator.bindAction(sub.id, sub.nonce, "task_a", old, "click_nav", setOf("navigation_started", "navigation_finished"))

        assertTrue(coordinator.onMainFrameStarted(old.pageId, next))
        assertTrue(sub.events.isEmpty())
        val event = coordinator.publish(sub.id, "navigation_finished", next, JSONObject().put("loadState", "finished"))
        assertEquals("navigation_finished", event?.optString("eventKind"))
        assertEquals("click_nav", event?.optString("actionRequestId"))
        assertEquals(next.documentGeneration, event?.optLong("documentGeneration"))

        val delivered = mutableListOf<JSONObject>()
        assertTrue(coordinator.await(sub.id, "task_a", next, 100) { delivered.add(it) })
        assertEquals("navigation_finished", delivered.single().optString("eventKind"))
        assertFalse(coordinator.await(sub.id, "task_a", old, 100) {})
    }

    @Test
    fun expirySettlesWaiterAndRemovesSubscriptionAndTimer() {
        val clock = FakeClock()
        val coordinator = clock.coordinator()
        val sub = coordinator.arm("task_a", identity(), 1, setOf("javascript_dialog"), 100)
            .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
        coordinator.bindAction(sub.id, sub.nonce, "task_a", identity(), "click_1", setOf("javascript_dialog"))
        val delivered = mutableListOf<JSONObject>()
        assertTrue(coordinator.await(sub.id, "task_a", identity(), 500) { delivered.add(it) })

        clock.advanceTo(100)

        assertEquals("EVENT_TIMEOUT", delivered.single().optString("code"))
        assertNull(coordinator.subscription(sub.id))
        assertNull(coordinator.publish(sub.id, "javascript_dialog", identity()))
        assertEquals(0, clock.pendingCount())
    }

    @Test
    fun dialogResolverIsBoundToEventTaskAndConsumedExactlyOnce() {
        val coordinator = FakeClock().coordinator()
        val current = identity()
        val sub = coordinator.arm("task_a", current, 1, setOf("javascript_dialog"), 500)
            .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
        coordinator.bindAction(sub.id, sub.nonce, "task_a", current, "click_1", setOf("javascript_dialog"))
        val event = requireNotNull(coordinator.publish(sub.id, "javascript_dialog", current, JSONObject().put("type", "confirm")))
        val resolutions = mutableListOf<Pair<String, String?>>()
        assertTrue(coordinator.registerDialogResolver(event.getString("eventId")) { decision, text ->
            resolutions += decision to text
            true
        })

        assertFalse(coordinator.resolveDialog(event.getString("eventId"), "task_b", current, "accept", null))
        assertTrue(coordinator.resolveDialog(event.getString("eventId"), "task_a", current, "prompt", "ok"))
        assertFalse(coordinator.resolveDialog(event.getString("eventId"), "task_a", current, "accept", null))
        assertEquals(listOf("prompt" to "ok"), resolutions)
    }

    @Test
    fun cancellationDismissesPendingDialogOnceAndRemovesTimer() {
        val clock = FakeClock()
        val coordinator = clock.coordinator()
        val current = identity()
        val sub = coordinator.arm("task_a", current, 1, setOf("javascript_dialog"), 500)
            .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
        coordinator.bindAction(sub.id, sub.nonce, "task_a", current, "click_1", setOf("javascript_dialog"))
        val event = requireNotNull(coordinator.publish(sub.id, "javascript_dialog", current))
        val resolutions = mutableListOf<Pair<String, String?>>()
        coordinator.registerDialogResolver(event.getString("eventId")) { decision, text ->
            resolutions += decision to text
            true
        }

        assertFalse(coordinator.cancel(sub.id, "task_b"))
        assertTrue(coordinator.cancel(sub.id, "task_a"))
        assertFalse(coordinator.cancel(sub.id, "task_a"))
        assertEquals(listOf("dismiss" to null), resolutions)
        assertEquals(0, clock.pendingCount())
        assertNull(coordinator.subscriptionForDialog(event.getString("eventId")))
    }

    @Test
    fun pageDialogDismissalReleasesResolverButPreservesNavigationSubscriptionAndOtherPages() {
        val coordinator = FakeClock().coordinator()
        val pageA = identity()
        val pageB = pageA.copy(pageId = "page_b")
        fun subscribe(identity: BrowserEventCoordinator.Identity, actionId: String) =
            coordinator.arm("task_a", identity, 1, setOf("javascript_dialog", "navigation_finished"), 500)
                .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
                .also { coordinator.bindAction(it.id, it.nonce, "task_a", identity, actionId,
                    setOf("javascript_dialog", "navigation_finished")) }

        val subA = subscribe(pageA, "click_a")
        val subB = subscribe(pageB, "click_b")
        val dismissed = mutableListOf<String>()
        val eventA = requireNotNull(coordinator.publish(subA.id, "javascript_dialog", pageA))
        val eventB = requireNotNull(coordinator.publish(subB.id, "javascript_dialog", pageB))
        coordinator.registerDialogResolver(eventA.getString("eventId")) { decision, _ ->
            dismissed += "a:$decision"
            true
        }
        coordinator.registerDialogResolver(eventB.getString("eventId")) { decision, _ ->
            dismissed += "b:$decision"
            true
        }

        assertEquals(1, coordinator.dismissDialogsForPage(pageA.pageId))
        assertEquals(listOf("a:dismiss"), dismissed)
        assertNull(coordinator.subscriptionForDialog(eventA.getString("eventId")))
        assertFalse(coordinator.resolveDialog(eventA.getString("eventId"), "task_a", pageA, "accept", null))
        assertEquals(0, coordinator.dismissDialogsForPage(pageA.pageId))
        assertEquals("navigation_finished", coordinator.publish(
            subA.id,
            "navigation_finished",
            pageA,
        )?.optString("eventKind"))

        assertEquals(subB, coordinator.subscriptionForDialog(eventB.getString("eventId")))
        assertTrue(coordinator.resolveDialog(eventB.getString("eventId"), "task_a", pageB, "accept", null))
        assertEquals(listOf("a:dismiss", "b:accept"), dismissed)
    }

    @Test
    fun dismissAllDialogsSettlesEventsWithoutRemovingSubscriptions() {
        val coordinator = FakeClock().coordinator()
        val current = identity()
        val sub = coordinator.arm("task_a", current, 1, setOf("javascript_dialog"), 500)
            .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
        coordinator.bindAction(sub.id, sub.nonce, "task_a", current, "click_1", setOf("javascript_dialog"))
        val event = requireNotNull(coordinator.publish(sub.id, "javascript_dialog", current))
        var dismissed = 0
        coordinator.registerDialogResolver(event.getString("eventId")) { decision, _ ->
            if (decision == "dismiss") dismissed++
            true
        }

        assertEquals(1, coordinator.dismissAllDialogs())
        assertEquals(1, dismissed)
        assertNull(coordinator.subscriptionForDialog(event.getString("eventId")))
        assertTrue(coordinator.subscription(sub.id) != null)
        assertFalse(coordinator.resolveDialog(event.getString("eventId"), "task_a", current, "accept", null))
    }

    @Test
    fun eventQueueAndPayloadAreBoundedAndIdentityMismatchFailsClosed() {
        val coordinator = FakeClock().coordinator()
        val current = identity()
        val sub = coordinator.arm("task_a", current, 1, setOf("navigation_finished"), 1000)
            .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
        coordinator.bindAction(sub.id, sub.nonce, "task_a", current, "click_1", setOf("navigation_finished"))
        val wrongPage = current.copy(pageId = "other-page")
        assertNull(coordinator.publish(sub.id, "navigation_finished", wrongPage, JSONObject().put("index", -1)))
        assertNull(coordinator.publish(sub.id, "navigation_finished", current, JSONObject().put("large", "x".repeat(9000))))

        repeat(10) { index ->
            assertTrue(coordinator.publish(sub.id, "navigation_finished", current, JSONObject().put("index", index)) != null)
        }
        assertEquals(BrowserEventCoordinator.MAX_QUEUED_EVENTS, sub.events.size)
        val wrongIdentityDelivered = mutableListOf<JSONObject>()
        assertFalse(coordinator.await(sub.id, "task_a", wrongPage, 100) { wrongIdentityDelivered.add(it) })
        assertTrue(wrongIdentityDelivered.isEmpty())

        val delivered = mutableListOf<JSONObject>()
        repeat(BrowserEventCoordinator.MAX_QUEUED_EVENTS) {
            assertTrue(coordinator.await(sub.id, "task_a", current, 100) { delivered.add(it) })
        }
        assertEquals((2..9).toList(), delivered.map { it.optInt("index") })
    }

    @Test
    fun popupClaimIsOneShotAndExpiresWithSubscription() {
        val clock = FakeClock()
        val coordinator = clock.coordinator()
        val current = identity()
        val sub = coordinator.arm("task_a", current, 1, setOf("new_window"), 100)
            .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
        coordinator.bindAction(sub.id, sub.nonce, "task_a", current, "click_1", setOf("new_window"))

        val claim = coordinator.claimOneShotExpectation(current.pageId, "new_window", current)
        assertTrue(claim?.isValid() == true)
        assertNull(coordinator.claimOneShotExpectation(current.pageId, "new_window", current))
        assertTrue(coordinator.publish(sub.id, "new_window", current) != null)
        assertNull(coordinator.publish(sub.id, "new_window", current))
        assertNull(coordinator.claimOneShotExpectation(current.pageId, "new_window", current))

        clock.advanceTo(100)
        assertFalse(claim?.isValid() == true)
    }

    @Test
    fun popupClaimRequiresTheExactLiveIdentityAndTaskRevocationExpiresIt() {
        val coordinator = FakeClock().coordinator()
        val current = identity()
        val sub = coordinator.arm("task_a", current, 1, setOf("new_window"), 500)
            .let { (it as BrowserEventCoordinator.ArmResult.Created).subscription }
        coordinator.bindAction(sub.id, sub.nonce, "task_a", current, "click_1", setOf("new_window"))

        assertNull(coordinator.claimOneShotExpectation(current.pageId, "new_window", current.copy(documentGeneration = 29)))
        assertNull(coordinator.claimOneShotExpectation(current.pageId, "new_window", current.copy(navigationId = 39)))
        assertNull(coordinator.claimOneShotExpectation(current.pageId, "new_window", current.copy(controlGeneration = 19)))
        assertNull(coordinator.claimOneShotExpectation(current.pageId, "new_window", current.copy(sessionGeneration = 9)))
        assertNull(coordinator.claimOneShotExpectation(current.pageId, "new_window", current.copy(pageId = "page_b")))
        assertNull(coordinator.claimOneShotExpectation("page_b", "new_window", current))
        val claim = coordinator.claimOneShotExpectation(current.pageId, "new_window", current)
        assertTrue(claim?.isValid() == true)

        coordinator.invalidateTask("task_a")
        assertFalse(claim?.isValid() == true)
        assertNull(coordinator.claimOneShotExpectation(current.pageId, "new_window", current))
    }

    @Test
    fun subscriptionRegistryHasHardCapacity() {
        val coordinator = FakeClock().coordinator()
        repeat(BrowserEventCoordinator.MAX_SUBSCRIPTIONS) {
            assertTrue(coordinator.arm("task_a", identity(), 1, setOf("javascript_dialog"), 1000)
                is BrowserEventCoordinator.ArmResult.Created)
        }
        assertTrue(coordinator.arm("task_a", identity(), 1, setOf("javascript_dialog"), 1000)
            is BrowserEventCoordinator.ArmResult.Full)
    }

    private fun identity() = BrowserEventCoordinator.Identity(
        sessionGeneration = 10,
        controlGeneration = 20,
        pageId = "page_a",
        documentGeneration = 30,
        navigationId = 40,
    )

    private class FakeClock(var now: Long = 0) {
        private data class Scheduled(val at: Long, val sequence: Long, val runnable: Runnable)
        private val queue = PriorityQueue<Scheduled>(compareBy<Scheduled> { it.at }.thenBy { it.sequence })
        private var sequence = 0L

        fun coordinator() = BrowserEventCoordinator(object : BrowserEventScheduler {
            override fun postDelayed(runnable: Runnable, delayMillis: Long) {
                queue += Scheduled(now + delayMillis, ++sequence, runnable)
            }

            override fun removeCallbacks(runnable: Runnable) {
                queue.removeAll { it.runnable === runnable }
            }
        }) { now }

        fun pendingCount(): Int = queue.size

        fun advanceTo(target: Long) {
            while (queue.peek()?.at?.let { it <= target } == true) {
                val item = queue.remove()
                now = item.at
                item.runnable.run()
            }
            now = target
        }
    }
}
