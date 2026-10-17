package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRequestLedgerTest {
    @Test
    fun duplicatePayloadJoinsPendingRequestAndConflictDoesNotExecuteAgain() {
        val ledger = BrowserRequestLedger<String>(capacity = 2)
        val first = ledger.claim("req-1", "{payload}", 500L, "waiter-1", 100L)
        val entry = (first as BrowserRequestLedger.Claim.New<String>).entry

        val duplicate = ledger.claim("req-1", "{payload}", 500L, "waiter-2", 101L)
        assertSame(entry, (duplicate as BrowserRequestLedger.Claim.ExistingPending<String>).entry)
        assertEquals(listOf("waiter-1", "waiter-2"), entry.waiters)
        assertTrue(ledger.claim("req-1", "{changed}", 500L, "waiter-3", 102L)
            is BrowserRequestLedger.Claim.Conflict)
        for (index in 3..8) {
            assertTrue(ledger.claim("req-1", "{payload}", 500L, "waiter-$index", 102L)
                is BrowserRequestLedger.Claim.ExistingPending)
        }
        assertTrue(ledger.claim("req-1", "{payload}", 500L, "waiter-overflow", 103L)
            is BrowserRequestLedger.Claim.WaitersFull)
        assertEquals(1, ledger.size())
    }

    @Test
    fun fullPendingLedgerNeverEvictsLiveRequest() {
        val ledger = BrowserRequestLedger<String>(capacity = 1)
        val first = ledger.claim("req-1", "one", 500L, "waiter", 100L)
        val entry = (first as BrowserRequestLedger.Claim.New<String>).entry

        assertTrue(ledger.claim("req-2", "two", 600L, "other", 101L)
            is BrowserRequestLedger.Claim.Full)
        assertSame(entry, ledger.find("req-1"))
        assertTrue(ledger.claim("req-1", "one", 500L, "duplicate", 102L)
            is BrowserRequestLedger.Claim.ExistingPending)
    }

    @Test
    fun unknownResultBecomesTombstoneAndFreshDeadlineCannotReplay() {
        val ledger = BrowserRequestLedger<String>(unknownRetentionMillis = 100L)
        val claim = ledger.claim("req-1", "same-payload", 500L, "waiter", 100L)
        val entry = (claim as BrowserRequestLedger.Claim.New<String>).entry

        assertTrue(ledger.expire(entry, 499L, response("req-1", "UNKNOWN", null)).isEmpty())
        assertTrue(ledger.claim("req-expired", "late", 499L, "late-waiter", 499L)
            is BrowserRequestLedger.Claim.Expired)
        assertEquals(listOf("waiter"), ledger.expire(entry, 500L, response("req-1", "UNKNOWN", null)))
        assertEquals(response("req-1", "UNKNOWN", null), entry.responseJson)
        val replay = ledger.claim("req-1", "same-payload", 700L, "replay", 500L)
        assertEquals(response("req-1", "UNKNOWN", null),
            (replay as BrowserRequestLedger.Claim.ExistingComplete<String>).responseJson)
        assertTrue(ledger.claim("req-1", "changed-payload", 700L, "conflict", 501L)
            is BrowserRequestLedger.Claim.Conflict)
        assertFalse(ledger.find("req-1") === null)

        assertTrue(ledger.claim("req-1", "same-payload", 800L, "replay-after-retention", 600L)
            is BrowserRequestLedger.Claim.New)
    }

    @Test
    fun anyUnknownOrMalformedCompletionIsQuarantined() {
        val ledger = BrowserRequestLedger<String>()
        val unknownEntry = newEntry(ledger, "unknown-completion", 100L)
        ledger.complete(unknownEntry, response("unknown-completion", "UNKNOWN", false), 110L)
        assertTrue(ledger.claim("unknown-completion", "unknown-completion", 700L, "retry", 120L)
            is BrowserRequestLedger.Claim.ExistingComplete)

        val indeterminateEntry = newEntry(ledger, "indeterminate", 130L)
        ledger.complete(indeterminateEntry, response("indeterminate", "CANCELLED", null), 140L)
        assertTrue(ledger.claim("indeterminate", "indeterminate", 700L, "retry", 150L)
            is BrowserRequestLedger.Claim.ExistingComplete)

        val malformedEntry = newEntry(ledger, "malformed", 160L)
        ledger.complete(malformedEntry, "not-json", 170L)
        assertTrue(ledger.claim("malformed", "malformed", 700L, "retry", 180L)
            is BrowserRequestLedger.Claim.ExistingComplete)
    }

    @Test
    fun unknownTombstoneCapacityRejectsNewIdsButKeepsExistingUntilRetentionEnds() {
        val ledger = BrowserRequestLedger<String>(unknownCapacity = 1, unknownRetentionMillis = 50L)
        val first = newEntry(ledger, "unknown-1", 100L)
        ledger.complete(first, response("unknown-1", "UNKNOWN", null), 101L)
        assertTrue(ledger.claim("unknown-2", "unknown-2", 900L, "new", 102L)
            is BrowserRequestLedger.Claim.Full)
        assertTrue(ledger.claim("unknown-1", "unknown-1", 900L, "duplicate", 103L)
            is BrowserRequestLedger.Claim.ExistingComplete)
        assertTrue(ledger.claim("unknown-2", "unknown-2", 900L, "after-retention", 151L)
            is BrowserRequestLedger.Claim.New)
    }

    @Test
    fun pendingRequestsReserveUnknownSlotsAndAllCanBecomeRetainedTombstones() {
        val ledger = BrowserRequestLedger<String>(capacity = 512, unknownCapacity = 128,
            unknownRetentionMillis = 10_000L)
        val entries = (0 until 128).map { index ->
            val id = "possibly-unknown-$index"
            (ledger.claim(id, id, 1000L, "waiter-$index", 100L) as BrowserRequestLedger.Claim.New<String>).entry
        }
        assertEquals(128, ledger.size())
        assertTrue(ledger.claim("slot-overflow", "overflow", 1000L, "overflow", 100L)
            is BrowserRequestLedger.Claim.Full)

        entries.forEachIndexed { index, entry ->
            assertEquals(listOf("waiter-$index"), ledger.expire(entry, 1000L,
                response(entry.requestId, "UNKNOWN", null)))
        }
        assertEquals(0, ledger.size())
        entries.forEach { entry ->
            assertTrue(ledger.claim(entry.requestId, entry.rawPayload, 2000L, "duplicate", 1001L)
                is BrowserRequestLedger.Claim.ExistingComplete)
        }
        assertTrue(ledger.claim("slot-overflow", "overflow", 2000L, "overflow", 1001L)
            is BrowserRequestLedger.Claim.Full)
    }

    @Test
    fun ordinaryCompletedCacheIsShortAndDoesNotConsumePendingCapacity() {
        val ledger = BrowserRequestLedger<String>(capacity = 1, completedCapacity = 1,
            completedRetentionMillis = 30L)
        val first = newEntry(ledger, "ordinary-1", 100L)
        ledger.complete(first, response("ordinary-1", "OK", true), 110L)
        assertEquals(0, ledger.size())

        val second = ledger.claim("ordinary-2", "ordinary-2", 900L, "new-operation", 111L)
        val secondEntry = (second as BrowserRequestLedger.Claim.New<String>).entry
        assertTrue(ledger.claim("ordinary-1", "ordinary-1", 900L, "duplicate", 112L)
            is BrowserRequestLedger.Claim.ExistingComplete)
        ledger.complete(secondEntry, response("ordinary-2", "OK", false), 120L)
        assertTrue(ledger.claim("ordinary-1", "ordinary-1", 900L, "after-eviction", 121L)
            is BrowserRequestLedger.Claim.New)
    }

    @Test
    fun completedResultExpiresUsingMonotonicRetentionWindow() {
        val ledger = BrowserRequestLedger<String>(completedRetentionMillis = 30L)
        val entry = newEntry(ledger, "ordinary-expiry", 100L)
        ledger.complete(entry, response("ordinary-expiry", "OK", true), 110L)
        assertTrue(ledger.claim("ordinary-expiry", "ordinary-expiry", 900L, "cached", 139L)
            is BrowserRequestLedger.Claim.ExistingComplete)
        assertTrue(ledger.claim("ordinary-expiry", "ordinary-expiry", 900L, "expired-cache", 140L)
            is BrowserRequestLedger.Claim.New)
    }

    private fun newEntry(ledger: BrowserRequestLedger<String>, id: String, now: Long) =
        (ledger.claim(id, id, now + 1000L, "waiter-$id", now) as BrowserRequestLedger.Claim.New<String>).entry

    private fun response(requestId: String, code: String, executed: Boolean?): String =
        """{"version":1,"requestId":"$requestId","code":"$code","executed":${executed ?: "null"}}"""
}
