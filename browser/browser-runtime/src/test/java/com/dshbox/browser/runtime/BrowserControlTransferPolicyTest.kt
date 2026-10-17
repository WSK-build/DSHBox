package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserControlTransferPolicyTest {
    @Test
    fun cancelClearsOnlyPendingRequestAndDoesNotAuthorizeTransfer() {
        val policy = BrowserControlTransferPolicy()
        val pending = requireNotNull(policy.begin(BrowserControlTransferTarget.HUMAN))

        assertNull(policy.resolve(pending.id, confirmed = false))
        assertFalse(policy.hasPending)
        assertNull(policy.resolve(pending.id, confirmed = true))
    }

    @Test
    fun confirmationResolvesOnceAndRejectsRepeatedClicks() {
        val policy = BrowserControlTransferPolicy()
        val pending = requireNotNull(policy.begin(BrowserControlTransferTarget.AI))

        assertEquals(BrowserControlTransferTarget.AI, policy.resolve(pending.id, confirmed = true))
        assertNull(policy.resolve(pending.id, confirmed = true))
        assertFalse(policy.hasPending)
    }

    @Test
    fun onlyOneDialogCanBePendingAndDestroyInvalidatesIt() {
        val policy = BrowserControlTransferPolicy()
        val pending = requireNotNull(policy.begin(BrowserControlTransferTarget.HUMAN))

        assertNull(policy.begin(BrowserControlTransferTarget.AI))
        policy.destroy()

        assertNull(policy.resolve(pending.id, confirmed = true))
        assertNull(policy.begin(BrowserControlTransferTarget.AI))
        assertFalse(policy.hasPending)
        assertTrue(policy.cancel(pending.id).not())
    }

    @Test
    fun oldConfirmationCannotTransferAcrossSessionOrControlGeneration() {
        val policy = BrowserControlTransferPolicy()
        val pending = requireNotNull(policy.begin(BrowserControlTransferTarget.HUMAN, 7L, 12L))

        assertNull(policy.resolve(pending.id, confirmed = true, sessionGeneration = 8L, controlGeneration = 12L))
        assertFalse(policy.hasPending)

        val next = requireNotNull(policy.begin(BrowserControlTransferTarget.HUMAN, 8L, 12L))
        assertNull(policy.resolve(next.id, confirmed = true, sessionGeneration = 8L, controlGeneration = 13L))
        assertFalse(policy.hasPending)
    }
}
