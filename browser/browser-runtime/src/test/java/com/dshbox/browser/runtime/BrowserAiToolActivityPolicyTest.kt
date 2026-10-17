package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserClock
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOwner
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAiToolActivityPolicyTest {
    private class FakeClock : BrowserClock {
        override fun nowMillis(): Long = 0L
    }

    private fun request(
        method: BrowserCommandMethod,
        session: Long?,
        control: Long? = null,
        owner: String? = null,
        args: JSONObject = JSONObject(),
        deadline: Long = 100L,
    ) = BrowserCommandRequest("req-activity", method, deadline, session, control, owner, null, args, "")

    @Test
    fun acceptsCurrentExplicitAiCallsAndSessionlessStatusButRejectsBackgroundAndStaleCalls() {
        val lifecycle = BrowserLifecycleController(FakeClock())
        val ai = lifecycle.open(BrowserOwner.AI)
        val generation = ai.sessionGeneration
        val control = ai.controlGeneration

        assertTrue(isEligibleForAiToolAnimation(
            request(BrowserCommandMethod.OBSERVE, generation), ai, true, nowElapsedMillis = 1L,
        ))
        assertTrue(isEligibleForAiToolAnimation(
            request(BrowserCommandMethod.STATUS, null), ai, true, nowElapsedMillis = 1L,
        ))
        assertFalse(isEligibleForAiToolAnimation(
            request(BrowserCommandMethod.OBSERVE, generation - 1), ai, true, nowElapsedMillis = 1L,
        ))
        assertFalse(isEligibleForAiToolAnimation(
            request(BrowserCommandMethod.CLICK, generation, control - 1, "ai"), ai, true, nowElapsedMillis = 1L,
        ))
        assertFalse(isEligibleForAiToolAnimation(
            request(BrowserCommandMethod.OBSERVE, generation), ai, false, nowElapsedMillis = 1L,
        ))
        assertFalse(isEligibleForAiToolAnimation(
            request(BrowserCommandMethod.OBSERVE, generation, deadline = 1L), ai, true, nowElapsedMillis = 1L,
        ))
    }

    @Test
    fun humanOwnerAndSelectionRelayPollingDoNotTriggerTheAiIndicator() {
        val lifecycle = BrowserLifecycleController(FakeClock())
        lifecycle.open(BrowserOwner.AI)
        val human = lifecycle.takeOver()
        assertFalse(isEligibleForAiToolAnimation(
            request(BrowserCommandMethod.OBSERVE, human.sessionGeneration), human, true, nowElapsedMillis = 1L,
        ))

        val current = lifecycle.handToAi()
        for (action in listOf("pending", "receipt")) {
            assertFalse(isEligibleForAiToolAnimation(
                request(
                    BrowserCommandMethod.SELECTION,
                    current.sessionGeneration,
                    args = JSONObject().put("action", action),
                ),
                current,
                true,
                nowElapsedMillis = 1L,
            ))
        }
    }
}
