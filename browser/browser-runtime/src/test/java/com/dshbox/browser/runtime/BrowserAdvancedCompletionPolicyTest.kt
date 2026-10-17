package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdvancedCompletionPolicyTest {
    @Test fun axReadsAreDefiniteFalseFailuresWithStageReason() {
        for (action in listOf("ax_tree", "ax_observe")) {
            for (nativeExecuted in listOf(false, true, null)) {
                val outcome = respond(BrowserAdvancedCompletionPolicy.contextLost(action, nativeExecuted))
                assertEquals("EXECUTION_FAILED", outcome.code)
                assertEquals(false, outcome.executed)
                assertEquals(setOf("stage", "reason"), outcome.data.keys().asSequence().toSet())
                assertEquals("completion", outcome.data.getString("stage"))
                assertEquals("context_lost", outcome.data.getString("reason"))
                assertFalse(outcome.data.has("sideEffectsMayHaveOccurred"))
            }
        }
    }

    @Test fun axWritesPreserveNativeDefiniteNoExecution() {
        for (action in listOf("ax_click", "ax_type", "ax_scroll")) {
            val outcome = BrowserAdvancedCompletionPolicy.contextLost(action, false)
            assertSame(BrowserAdvancedCompletionPolicy.Outcome.PreserveNative, outcome)
        }
    }

    @Test fun axWritesWithTrueOrUnknownExecutionBecomeFlaggedUnknown() {
        for (action in listOf("ax_click", "ax_type", "ax_scroll")) {
            for (nativeExecuted in listOf(true, null)) {
                val outcome = respond(BrowserAdvancedCompletionPolicy.contextLost(action, nativeExecuted))
                assertEquals("UNKNOWN", outcome.code)
                assertNull(outcome.executed)
                assertEquals(
                    setOf("nonRetryable", "sideEffectsMayHaveOccurred", "reason"),
                    outcome.data.keys().asSequence().toSet(),
                )
                assertTrue(outcome.data.getBoolean("nonRetryable"))
                assertTrue(outcome.data.getBoolean("sideEffectsMayHaveOccurred"))
                assertEquals("context_lost", outcome.data.getString("reason"))
            }
        }
    }

    @Test fun nonAxContextLossKeepsExistingUnknownNullShape() {
        for (action in listOf("", "evaluate", "emulate", "start")) {
            val outcome = respond(BrowserAdvancedCompletionPolicy.contextLost(action, true))
            assertEquals("UNKNOWN", outcome.code)
            assertNull(outcome.executed)
            assertEquals(0, outcome.data.length())
        }
    }

    @Test fun synchronousFailuresSeparateReadsWritesAndOtherMethods() {
        for (action in listOf("ax_tree", "ax_observe")) {
            val read = respond(BrowserAdvancedCompletionPolicy.synchronousFailure(action))
            assertEquals("EXECUTION_FAILED", read.code)
            assertEquals(false, read.executed)
            assertEquals("dispatch", read.data.getString("stage"))
            assertEquals("synchronous_exception", read.data.getString("reason"))
        }

        for (action in listOf("ax_click", "ax_type", "ax_scroll")) {
            val write = respond(BrowserAdvancedCompletionPolicy.synchronousFailure(action))
            assertEquals("UNKNOWN", write.code)
            assertNull(write.executed)
            assertTrue(write.data.getBoolean("nonRetryable"))
            assertTrue(write.data.getBoolean("sideEffectsMayHaveOccurred"))
            assertEquals("synchronous_exception", write.data.getString("reason"))
        }

        for (action in listOf("", "evaluate", "emulate", "start")) {
            val other = respond(BrowserAdvancedCompletionPolicy.synchronousFailure(action))
            assertEquals("UNKNOWN", other.code)
            assertNull(other.executed)
            assertEquals(0, other.data.length())
        }
    }

    private fun respond(outcome: BrowserAdvancedCompletionPolicy.Outcome): BrowserAdvancedCompletionPolicy.Outcome.Respond =
        outcome as BrowserAdvancedCompletionPolicy.Outcome.Respond
}
