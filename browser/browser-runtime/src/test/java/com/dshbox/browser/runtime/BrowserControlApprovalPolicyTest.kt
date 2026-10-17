package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserControlApprovalPolicyTest {
    private val identity = BrowserTaskPageIdentity(7, 3, "page_a", 9)
    private val activeHuman = BrowserControlApprovalHostState(identity, false, BrowserOwner.HUMAN, BrowserLifecycle.ACTIVE)

    @Test
    fun cancelDistinguishesSuccessfulCancellationFromTerminalRequests() {
        assertNull(BrowserControlApprovalPolicy.cancelError(true, BrowserControlApprovalStatus.CANCELLED))
        assertEquals("APPROVAL_NOT_PENDING", BrowserControlApprovalPolicy.cancelError(false,
            BrowserControlApprovalStatus.CANCELLED))
        assertEquals("APPROVAL_NOT_PENDING", BrowserControlApprovalPolicy.cancelError(false,
            BrowserControlApprovalStatus.APPROVED))
        assertEquals("APPROVAL_EXPIRED", BrowserControlApprovalPolicy.cancelError(false,
            BrowserControlApprovalStatus.EXPIRED))
    }

    @Test
    fun requestsRequireAnActiveHumanOwnedBrowserWithAiDisabled() {
        assertNull(BrowserControlApprovalPolicy.requestError(activeHuman))
        assertEquals("NO_ACTIVE_SESSION", BrowserControlApprovalPolicy.requestError(
            activeHuman.copy(identity = null, lifecycle = BrowserLifecycle.CLOSED)))
        assertNull(BrowserControlApprovalPolicy.requestError(activeHuman.copy(enabled = true)))
        assertEquals("AI_CONTROL_ALREADY_ENABLED", BrowserControlApprovalPolicy.requestError(
            activeHuman.copy(enabled = true, owner = BrowserOwner.AI)))
        assertEquals("NO_ACTIVE_SESSION", BrowserControlApprovalPolicy.requestError(
            activeHuman.copy(owner = BrowserOwner.AI)))
        assertEquals("NO_ACTIVE_SESSION", BrowserControlApprovalPolicy.requestError(
            activeHuman.copy(owner = BrowserOwner.NONE)))
    }

    @Test
    fun identicalRequestsCoalesceButDifferentRequestsConflict() {
        val store = BrowserControlApprovalStore()
        val first = store.start(id('1'), "nonce_a", "Allow the browser to proceed", "task_a", identity, 100, 1_000)
        assertTrue(first is BrowserControlApprovalStore.Start.Created)
        val existing = (first as BrowserControlApprovalStore.Start.Created).approval
        val duplicate = store.start(id('2'), "nonce_b", existing.summary, existing.taskId, identity, 150, 1_050)
        assertTrue(duplicate is BrowserControlApprovalStore.Start.Coalesced)
        assertEquals(existing.id, (duplicate as BrowserControlApprovalStore.Start.Coalesced).approval.id)
        assertEquals(BrowserControlApprovalStore.Start.PendingConflict,
            store.start(id('3'), "nonce_c", existing.summary, "other_task", identity, 160, 1_060))
        assertEquals(BrowserControlApprovalStore.Start.PendingConflict,
            store.start(id('4'), "nonce_d", "Different summary", existing.taskId, identity, 170, 1_070))
    }

    @Test
    fun pendingApprovalStoreSurvivesShortLivedServiceConnections() {
        val processStore = ProcessScopedInstance<BrowserControlApprovalStore>()
        val firstConnectionStore = processStore.getOrCreate { BrowserControlApprovalStore() }
        val pending = (firstConnectionStore.start(id('a'), "nonce_a", "Approve", "task_a", identity,
            100, 1_000) as BrowserControlApprovalStore.Start.Created).approval

        // A later bound Service obtains the process-scoped store instead of creating a new one.
        val nextConnectionStore = processStore.getOrCreate { error("must reuse process-scoped state") }
        assertTrue(firstConnectionStore === nextConnectionStore)
        assertEquals(pending.id, nextConnectionStore.pending(101)?.id)
        assertEquals(BrowserControlApprovalStatus.PENDING, nextConnectionStore.get(pending.id, 101)?.status)
    }

    @Test
    fun approvalRequiresNonceIdentityAndUnexpiredMonotonicDeadline() {
        val store = BrowserControlApprovalStore()
        val pending = (store.start(id('5'), "stored_nonce", "Approve", null, identity, 1_000, 20_000)
            as BrowserControlApprovalStore.Start.Created).approval
        assertTrue(BrowserControlApprovalPolicy.mayGrant(pending, "stored_nonce", activeHuman, 1_001))
        assertTrue(BrowserControlApprovalPolicy.mayGrant(pending, "stored_nonce", activeHuman.copy(enabled = true), 1_001))
        assertFalse(BrowserControlApprovalPolicy.mayGrant(pending, "late_nonce", activeHuman, 1_001))
        assertNull(store.settle(pending.id, "late_nonce", BrowserControlApprovalStatus.CANCELLED, 1_001))
        assertEquals(BrowserControlApprovalStatus.PENDING, store.get(pending.id, 1_001)?.status)
        assertFalse(BrowserControlApprovalPolicy.mayGrant(pending, "stored_nonce", activeHuman.copy(
            identity = identity.copy(documentGeneration = 10)), 1_001))
        assertFalse(BrowserControlApprovalPolicy.mayGrant(pending, "stored_nonce", activeHuman, pending.expiresAtElapsedMillis))
        val expired = store.expireIfNeeded(pending.expiresAtElapsedMillis)
        assertNotNull(expired)
        assertEquals(BrowserControlApprovalStatus.EXPIRED, expired!!.status)
        assertNull(store.settle(pending.id, pending.nonce, BrowserControlApprovalStatus.APPROVED,
            pending.expiresAtElapsedMillis + 1))
        assertEquals(BrowserControlApprovalStatus.EXPIRED, store.get(pending.id, pending.expiresAtElapsedMillis + 1)?.status)
    }

    @Test
    fun identityChangeAndServiceCloseSettlePendingOnce() {
        val store = BrowserControlApprovalStore()
        val pending = (store.start(id('6'), "nonce", "Approve", null, identity, 100, 1_000)
            as BrowserControlApprovalStore.Start.Created).approval
        assertEquals(BrowserControlApprovalStatus.CANCELLED,
            store.cancelForIdentityChange(identity.copy(controlGeneration = 4), 101)?.status)
        assertNull(store.settle(pending.id, pending.nonce, BrowserControlApprovalStatus.DENIED, 102))

        val second = (store.start(id('7'), "nonce2", "Approve", null, identity, 200, 1_100)
            as BrowserControlApprovalStore.Start.Created).approval
        assertEquals(BrowserControlApprovalStatus.CANCELLED, store.close(201)?.status)
        assertNull(store.close(202))
        assertEquals(BrowserControlApprovalStatus.CANCELLED, store.get(second.id, 203)?.status)
    }

    @Test
    fun terminalHistoryIsBoundedToSixteenRecords() {
        val store = BrowserControlApprovalStore()
        val ids = (0..16).map { index ->
            val approvalId = index.toString(16).padStart(32, '0')
            val pending = (store.start(approvalId, "nonce_$index", "Approve $index", null, identity,
                index * 10L, 10_000L + index * 10L) as BrowserControlApprovalStore.Start.Created).approval
            assertNotNull(store.close(index * 10L + 1))
            pending.id
        }
        assertNull(store.get(ids.first(), 1_000))
        ids.drop(1).forEach { assertNotNull(store.get(it, 1_000)) }
    }

    private fun id(char: Char) = char.toString().repeat(32)
}
