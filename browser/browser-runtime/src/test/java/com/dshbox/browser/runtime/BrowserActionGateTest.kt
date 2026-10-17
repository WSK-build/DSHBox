package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserOperationToken
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserActionGateTest {
    private var now = 100L
    private val gate = BrowserActionGate { now }
    private val token = BrowserOperationToken(
        operationId = "ai-request-1",
        sessionGeneration = 10L,
        controlGeneration = 20L,
        operationGeneration = 30L,
        owner = BrowserOwner.AI,
        deadlineAtMillis = 500L,
    )

    @Test
    fun matchingGrantIsOneUse() {
        gate.update(activeSnapshot(), automationEnabled = true)

        assertTrue(gate.register("random-once", token))
        assertTrue(gate.authorize("random-once"))
        assertFalse(gate.authorize("random-once"))
    }

    @Test
    fun headlessSessionCanAuthorizeOneDomActionWithOneUseGrant() {
        gate.update(activeSnapshot().copy(visible = false), automationEnabled = true)

        assertTrue(gate.register("headless-dom", token))
        assertTrue(gate.authorize("headless-dom"))
        assertFalse(gate.authorize("headless-dom"))
    }

    @Test
    fun takeoverInvalidatesQueuedGrant() {
        gate.update(activeSnapshot(), automationEnabled = true)
        assertTrue(gate.register("random-once", token))

        gate.update(activeSnapshot(
            controlGeneration = 21L,
            operationGeneration = 31L,
            owner = BrowserOwner.HUMAN,
            operationId = null,
            operationDeadlineAtMillis = null,
        ), automationEnabled = true)

        assertFalse(gate.authorize("random-once"))
    }

    @Test
    fun disablingAutomationAndDeadlineBothDenyGrant() {
        gate.update(activeSnapshot(), automationEnabled = true)
        assertTrue(gate.register("disable-test", token))
        gate.update(activeSnapshot(), automationEnabled = false)
        assertFalse(gate.authorize("disable-test"))

        gate.update(activeSnapshot(), automationEnabled = true)
        assertTrue(gate.register("deadline-test", token))
        now = token.deadlineAtMillis
        assertFalse(gate.authorize("deadline-test"))
    }

    @Test
    fun invalidAndWrongNoncesCannotConsumeValidGrant() {
        gate.update(activeSnapshot(), automationEnabled = true)
        assertTrue(gate.register("expected", token))

        assertFalse(gate.authorize("x".repeat(65)))
        assertFalse(gate.authorize("other"))
        assertTrue(gate.authorize("expected"))
    }

    private fun activeSnapshot(
        controlGeneration: Long = token.controlGeneration,
        operationGeneration: Long = token.operationGeneration,
        owner: BrowserOwner = BrowserOwner.AI,
        operationId: String? = token.operationId,
        operationDeadlineAtMillis: Long? = token.deadlineAtMillis,
    ) = BrowserSnapshot(
        sessionGeneration = token.sessionGeneration,
        controlGeneration = controlGeneration,
        operationGeneration = operationGeneration,
        lifecycle = BrowserLifecycle.ACTIVE,
        owner = owner,
        visible = true,
        inFlightOperationId = operationId,
        hiddenAtMillis = null,
        lastEffectiveOperationCompletedAtMillis = null,
        idleTimeoutMillis = 300_000L,
        operationDeadlineAtMillis = operationDeadlineAtMillis,
    )
}
