package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserClock
import com.dshbox.browser.api.BrowserIdlePolicy
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Background DOM actions authorize through the action gate with a one-shot token and never consult
 * window focus; only visual/native input (through the separate `authorizeVisual` path) is
 * focus-gated. This locks the capability boundary a headless session depends on.
 */
class BrowserSessionDomAuthorizationTest {
    @Test fun headlessDomActionAuthorizesWithOneShotTokenWithoutWindowFocus() {
        val now = AtomicLong(1_000)
        val lifecycle = BrowserLifecycleController(
            clock = BrowserClock { now.get() },
            initialIdleTimeoutMillis = BrowserIdlePolicy.DEFAULT_IDLE_TIMEOUT_MILLIS,
            initialSessionGeneration = 0L,
        )
        val snapshot = lifecycle.open(BrowserOwner.AI)
        val token = requireNotNull(
            lifecycle.beginOperation(
                operationId = "op-dom",
                context = snapshot.commandContext(),
                requestedDeadlineAtMillis = now.get() + 5_000,
            ),
        )
        val gate = BrowserActionGate { now.get() }
        gate.update(lifecycle.snapshot(), true)
        assertTrue(gate.register("nonce-dom", token))
        assertTrue("DOM authorization must not depend on window focus", gate.authorize("nonce-dom"))
        assertFalse("token is one-shot", gate.authorize("nonce-dom"))
    }

    @Test fun automationDisabledDeniesDomAuthorization() {
        val now = AtomicLong(2_000)
        val lifecycle = BrowserLifecycleController(
            clock = BrowserClock { now.get() },
            initialIdleTimeoutMillis = BrowserIdlePolicy.DEFAULT_IDLE_TIMEOUT_MILLIS,
            initialSessionGeneration = 0L,
        )
        val snapshot = lifecycle.open(BrowserOwner.AI)
        val token = requireNotNull(
            lifecycle.beginOperation(
                operationId = "op-off",
                context = snapshot.commandContext(),
                requestedDeadlineAtMillis = now.get() + 5_000,
            ),
        )
        val gate = BrowserActionGate { now.get() }
        gate.update(lifecycle.snapshot(), automationEnabled = false)
        gate.register("nonce-off", token)
        assertFalse("disabled automation must not authorize", gate.authorize("nonce-off"))
    }
}
