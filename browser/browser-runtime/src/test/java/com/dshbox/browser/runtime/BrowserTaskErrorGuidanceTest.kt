package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserTaskErrorGuidanceTest {
    @Test fun changedPageRequiresCurrentObservationRatherThanReplayOrNewTask() {
        val advice = BrowserTaskErrorGuidance.forCode("TASK_PAGE_MISMATCH")!!
        assertEquals("task_binding", advice.stage)
        assertEquals("read_task_status_then_observe_and_reconcile", advice.nextAction)
    }

    @Test fun unknownEffectRequiresHumanVerificationAndUnrelatedErrorsRemainUnclassified() {
        assertEquals("inspect_effects_and_request_user_verification",
            BrowserTaskErrorGuidance.forCode("TASK_UNKNOWN_EFFECT_UNVERIFIED")!!.nextAction)
        assertNull(BrowserTaskErrorGuidance.forCode("UNKNOWN"))
        assertNull(BrowserTaskErrorGuidance.forCode("DEADLINE_EXPIRED"))
    }
}
