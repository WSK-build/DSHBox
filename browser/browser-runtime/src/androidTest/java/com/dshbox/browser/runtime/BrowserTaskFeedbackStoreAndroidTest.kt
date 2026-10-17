package com.dshbox.browser.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserTaskFeedbackStoreAndroidTest {
    @Test
    fun failureReasonIsIncludedInFeedbackAndInvalidReasonsAreRejected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        val store = BrowserTaskFeedbackStore(context, dbName)
        try {
            val draft = recoveryDraft().put("status", "failed").put("failureReason", "Website rejected submission")
            assertTrue(store.enqueue(draft.toString(), 17L))
            val item = store.pending(0, 17L, "agent-session-a").getJSONObject("data").getJSONArray("items").getJSONObject(0)
            assertTrue(item.getString("text").contains("Failure reason: Website rejected submission"))
            for (reason in listOf("", "  ", "x".repeat(1001))) {
                assertFalse(store.enqueue(recoveryDraft().put("status", "failed").put("failureReason", reason).toString(), 17L))
            }
            assertFalse(store.enqueue(recoveryDraft().put("status", "running").put("failureReason", "Unexpected").toString(), 17L))
        } finally {
            store.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun deduplicatesAndReopensAdmittedFeedbackAsUncertainWithoutRedelivery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        try {
            var store = BrowserTaskFeedbackStore(context, dbName)
            val draft = recoveryDraft()
            assertTrue(store.enqueue(draft.toString(), 17L))
            assertTrue(store.enqueue(draft.toString(), 17L))
            assertEquals(1, store.pending(0, 17L, "agent-session-a").getJSONObject("data").getJSONArray("items").length())
            assertEquals(0, store.pending(0, 18L, "agent-session-a").getJSONObject("data").getJSONArray("items").length())

            val item = store.pending(0, 17L, "agent-session-a").getJSONObject("data").getJSONArray("items").getJSONObject(0)
            val feedbackId = item.getString("feedbackId")
            val claim = actionArgs("task_claim", feedbackId)
            assertEquals("DESTINATION_MISMATCH", store.admit(actionArgs("task_admit", feedbackId, "other-agent"), 17L).getString("code"))
            assertEquals("OK", store.claim(claim, 17L).getString("code"))
            assertEquals("OK", store.admit(actionArgs("task_admit", feedbackId), 17L).getString("code"))
            store.close()

            store = BrowserTaskFeedbackStore(context, dbName)
            val pending = store.pending(0, 17L, "agent-session-a").getJSONObject("data").getJSONArray("items")
            assertEquals(0, pending.length())
            assertFalse(store.enqueue(draft.toString(), 17L))
            val retry = store.admit(actionArgs("task_admit", feedbackId), 17L)
            assertEquals("FEEDBACK_ALREADY_ADMITTED", retry.getString("code"))
            assertEquals("unknown", retry.getJSONObject("data").getString("state"))
            val wrongDestination = actionArgs("task_receipt", feedbackId, "other-agent")
                .put("deliveryCode", "PROMPT_ACCEPTED")
            assertEquals("DESTINATION_MISMATCH", store.receipt(wrongDestination, 17L).getString("code"))
            val wrongRequest = actionArgs("task_receipt", feedbackId)
                .put("requestId", "e".repeat(32)).put("deliveryCode", "PROMPT_ACCEPTED")
            assertEquals("DESTINATION_MISMATCH", store.receipt(wrongRequest, 17L).getString("code"))
            assertEquals("unknown", store.admit(actionArgs("task_admit", feedbackId), 17L)
                .getJSONObject("data").getString("state"))

            val matchingLateAck = actionArgs("task_receipt", feedbackId).put("deliveryCode", "PROMPT_ACCEPTED")
            assertEquals("prompt_accepted", store.receipt(matchingLateAck, 17L).getJSONObject("data").getString("state"))
            assertEquals("prompt_accepted", store.receipt(matchingLateAck, 17L).getJSONObject("data").getString("state"))
            assertEquals(0, store.pending(0, 17L, "agent-session-a").getJSONObject("data").getJSONArray("items").length())
            assertFalse(store.enqueue(draft.toString(), 17L))
            val retainedBinding = store.readableDatabase.rawQuery(
                "SELECT request_id,payload_digest,state FROM feedback WHERE feedback_id=?", arrayOf(feedbackId),
            ).use { cursor -> assertTrue(cursor.moveToFirst()); listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2)) }
            assertEquals("c".repeat(32), retainedBinding[0])
            assertEquals("d".repeat(64), retainedBinding[1])
            assertEquals("PROMPT_ACCEPTED", retainedBinding[2])
            store.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun acceptedReceiptIsTerminalAndTextNeverReturnsPathsOrUrlQueries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        try {
            val store = BrowserTaskFeedbackStore(context, dbName)
            val draft = recoveryDraft().put("goal", "Visit https://example.test/private?token=secret and inspect reports/source.md from /data/user/0/private token=credential-value")
            assertTrue(store.enqueue(draft.toString(), 23L))
            val data = store.pending(0, 23L, "agent-session-b").getJSONObject("data")
            val item = data.getJSONArray("items").getJSONObject(0)
            val feedbackId = item.getString("feedbackId")
            assertTrue(item.getString("text").contains("https://example.test/private"))
            assertTrue(item.getString("text").contains("reports/source.md"))
            assertFalse(item.getString("text").contains("token=secret"))
            assertFalse(item.getString("text").contains("/data/user"))
            assertFalse(item.getString("text").contains("credential-value"))
            assertTrue(item.getString("text").contains("browser_task status"))
            assertTrue(item.getString("text").contains("Task id: ${item.getString("taskId")}"))
            assertTrue(item.getString("text").contains("Revision: ${item.getLong("revision")}"))
            assertTrue(item.getString("text").contains("Title: ${item.getString("title")}"))
            assertTrue(item.getString("text").contains("Created wall time: 100"))
            assertTrue(item.getString("text").contains("If the task is missing or any value differs from this draft, stop"))
            assertTrue(item.getString("text").contains("prior results do not prove success"))
            assertTrue(item.getString("text").contains("Never replay an UNKNOWN effect"))
            val args = actionArgs("task_claim", feedbackId, "agent-session-b")
            assertEquals("OK", store.claim(args, 23L).getString("code"))
            assertEquals("OK", store.admit(actionArgs("task_admit", feedbackId, "agent-session-b"), 23L).getString("code"))
            val receipt = actionArgs("task_receipt", feedbackId, "agent-session-b").put("deliveryCode", "PROMPT_ACCEPTED")
            assertEquals("prompt_accepted", store.receipt(receipt, 23L).getJSONObject("data").getString("state"))
            assertEquals(0, store.pending(0, 23L, "agent-session-b").getJSONObject("data").getJSONArray("items").length())
            store.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun boundsUnresolvedQueueAtSixtyFourEntries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        try {
            val store = BrowserTaskFeedbackStore(context, dbName)
            repeat(BrowserTaskFeedbackStore.MAX_PENDING) { revision ->
                assertTrue(store.enqueue(recoveryDraft()
                    .put("taskId", "task_${revision.toString(16).padStart(32, '0')}")
                    .put("revision", revision).toString(), 31L))
            }
            assertFalse(store.enqueue(recoveryDraft()
                .put("taskId", "task_${"f".repeat(32)}").put("revision", 65).toString(), 31L))
            store.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun acceptsMaximumCjkGoalAndNotesWithoutTruncation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        try {
            val store = BrowserTaskFeedbackStore(context, dbName)
            val goal = "目標".repeat(2_000)
            val notes = "备注".repeat(1_000)
            assertTrue(store.enqueue(recoveryDraft().put("goal", goal).put("notes", notes).toString(), 41L))
            val item = store.pending(0, 41L, "agent-session-c").getJSONObject("data").getJSONArray("items").getJSONObject(0)
            assertTrue(item.getString("text").contains(goal))
            assertTrue(item.getString("text").contains(notes))
            store.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun duplicateEnqueueOnlyReportsEligibleQueueStatesAsQueued() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        try {
            val store = BrowserTaskFeedbackStore(context, dbName)
            val draft = recoveryDraft()
            assertTrue(store.enqueue(draft.toString(), 51L))
            assertTrue(store.enqueue(draft.toString(), 51L))
            val item = store.pending(0, 51L, "agent-session-e").getJSONObject("data").getJSONArray("items").getJSONObject(0)
            val feedbackId = item.getString("feedbackId")
            val claimArgs = actionArgs("task_claim", feedbackId, "agent-session-e")
            assertEquals("OK", store.claim(claimArgs, 51L).getString("code"))
            assertTrue(store.enqueue(draft.toString(), 51L))
            assertEquals("OK", store.admit(actionArgs("task_admit", feedbackId, "agent-session-e"), 51L).getString("code"))
            assertFalse(store.enqueue(draft.toString(), 51L))
            store.close()

            val reopened = BrowserTaskFeedbackStore(context, dbName)
            assertFalse(reopened.enqueue(draft.toString(), 51L))
            reopened.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun confirmedTerminalLedgerIsBoundedWithoutDeletingUnknownFeedback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        val generation = 61L
        val destination = "agent-ledger-bound"
        try {
            val store = BrowserTaskFeedbackStore(context, dbName)
            var oldestConfirmedId: String? = null
            var newestConfirmedArgs: JSONObject? = null
            repeat(BrowserTaskFeedbackStore.MAX_RETAINED_TERMINAL + 6) { index ->
                val draft = recoveryDraft()
                    .put("taskId", "task_${index.toString(16).padStart(32, '0')}")
                    .put("revision", index)
                assertTrue(store.enqueue(draft.toString(), generation))
                val items = store.pending(0, generation, destination).getJSONObject("data").getJSONArray("items")
                assertEquals(1, items.length())
                val feedbackId = items.getJSONObject(0).getString("feedbackId")
                if (oldestConfirmedId == null) oldestConfirmedId = feedbackId
                val claim = actionArgs("task_claim", feedbackId, destination)
                assertEquals("OK", store.claim(claim, generation).getString("code"))
                assertEquals("OK", store.admit(actionArgs("task_admit", feedbackId, destination), generation).getString("code"))
                val receipt = actionArgs("task_receipt", feedbackId, destination).put("deliveryCode", "PROMPT_ACCEPTED")
                assertEquals("OK", store.receipt(receipt, generation).getString("code"))
                newestConfirmedArgs = receipt
            }
            assertEquals("FEEDBACK_NOT_FOUND", store.receipt(
                actionArgs("task_receipt", requireNotNull(oldestConfirmedId), destination).put("deliveryCode", "PROMPT_ACCEPTED"),
                generation,
            ).getString("code"))
            assertEquals("OK", store.receipt(requireNotNull(newestConfirmedArgs), generation).getString("code"))

            var firstUnknownId: String? = null
            var firstUnknownReceipt: JSONObject? = null
            repeat(BrowserTaskFeedbackStore.MAX_PENDING) { index ->
                val draft = recoveryDraft()
                    .put("taskId", "task_${(1_000 + index).toString(16).padStart(32, '0')}")
                    .put("revision", index)
                assertTrue(store.enqueue(draft.toString(), generation))
                val items = store.pending(0, generation, destination).getJSONObject("data").getJSONArray("items")
                assertEquals(1, items.length())
                val feedbackId = items.getJSONObject(0).getString("feedbackId")
                val claim = actionArgs("task_claim", feedbackId, destination)
                assertEquals("OK", store.claim(claim, generation).getString("code"))
                assertEquals("OK", store.admit(actionArgs("task_admit", feedbackId, destination), generation).getString("code"))
                if (firstUnknownId == null) {
                    firstUnknownId = feedbackId
                    firstUnknownReceipt = actionArgs("task_receipt", feedbackId, destination).put("deliveryCode", "UNKNOWN")
                }
            }
            assertFalse(store.enqueue(recoveryDraft()
                .put("taskId", "task_${"e".repeat(32)}").put("revision", 999).toString(), generation))
            store.close()

            val reopened = BrowserTaskFeedbackStore(context, dbName)
            val db = reopened.readableDatabase
            val totalRows = db.rawQuery("SELECT COUNT(*) FROM feedback", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            val terminalRows = db.rawQuery("SELECT COUNT(*) FROM feedback WHERE state IN ('PROMPT_ACCEPTED','BLOCKED')", null)
                .use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
            val unknownRow = db.rawQuery("SELECT state,title,text FROM feedback WHERE feedback_id=?", arrayOf(requireNotNull(firstUnknownId)))
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                }
            assertEquals(BrowserTaskFeedbackStore.MAX_LEDGER_ROWS, totalRows)
            assertEquals(BrowserTaskFeedbackStore.MAX_RETAINED_TERMINAL, terminalRows)
            assertEquals("UNKNOWN", unknownRow[0])
            assertTrue(unknownRow[1].isNotBlank())
            assertTrue(unknownRow[2].isNotBlank())
            assertEquals("OK", reopened.receipt(requireNotNull(firstUnknownReceipt), generation).getString("code"))
            assertEquals(0, reopened.pending(0, generation, destination).getJSONObject("data").getJSONArray("items").length())
            assertFalse(reopened.enqueue(recoveryDraft()
                .put("taskId", "task_${(1_000).toString(16).padStart(32, '0')}").put("revision", 0).toString(), generation))
            reopened.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun newGenerationRetiresOnlyOldUnadmittedFeedbackToBlocked() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        val oldGeneration = 71L
        val newGeneration = 72L
        val destination = "agent-generation-retire"
        try {
            val store = BrowserTaskFeedbackStore(context, dbName)
            var claimedId: String? = null
            var claimedArgs: JSONObject? = null
            repeat(BrowserTaskFeedbackStore.MAX_PENDING) { index ->
                val draft = recoveryDraft()
                    .put("taskId", "task_${(2_000 + index).toString(16).padStart(32, '0')}")
                    .put("revision", index)
                assertTrue(store.enqueue(draft.toString(), oldGeneration))
                if (index == 0) {
                    val item = store.pending(0, oldGeneration, destination).getJSONObject("data").getJSONArray("items").getJSONObject(0)
                    claimedId = item.getString("feedbackId")
                    claimedArgs = actionArgs("task_claim", claimedId!!, destination)
                    assertEquals("OK", store.claim(requireNotNull(claimedArgs), oldGeneration).getString("code"))
                }
            }
            assertEquals(BrowserTaskFeedbackStore.MAX_PENDING,
                store.pending(0, oldGeneration, destination).getJSONObject("data").getJSONArray("items").length())

            assertTrue(store.enqueue(recoveryDraft().put("taskId", "task_${"c".repeat(32)}")
                .put("revision", 500).toString(), newGeneration))
            val oldRows = store.readableDatabase.rawQuery(
                "SELECT state,title,text FROM feedback WHERE session_generation=? AND state='BLOCKED'",
                arrayOf(oldGeneration.toString()),
            ).use { cursor ->
                val rows = mutableListOf<List<String>>()
                while (cursor.moveToNext()) rows += listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                rows
            }
            assertEquals(BrowserTaskFeedbackStore.MAX_PENDING, oldRows.size)
            assertTrue(oldRows.all { it[0] == "BLOCKED" && it[1].isEmpty() && it[2].isEmpty() })
            assertEquals(0, store.pending(0, oldGeneration, destination).getJSONObject("data").getJSONArray("items").length())
            val newItems = store.pending(0, newGeneration, destination).getJSONObject("data").getJSONArray("items")
            assertEquals(1, newItems.length())
            val retiredClaim = store.admit(actionArgs("task_admit", requireNotNull(claimedId), destination), oldGeneration)
            assertEquals("FEEDBACK_ALREADY_ADMITTED", retiredClaim.getString("code"))
            assertEquals("blocked", retiredClaim.getJSONObject("data").getString("state"))
            store.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun oldAdmittedFeedbackBecomesUnknownAndStaysUnsendableAcrossGenerationChange() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        val oldGeneration = 81L
        val newGeneration = 82L
        val destination = "agent-generation-admitted"
        val oldDraft = recoveryDraft().put("taskId", "task_${"8".repeat(32)}").put("revision", 808)
        try {
            var store = BrowserTaskFeedbackStore(context, dbName)
            assertTrue(store.enqueue(oldDraft.toString(), oldGeneration))
            val oldItem = store.pending(0, oldGeneration, destination).getJSONObject("data").getJSONArray("items").getJSONObject(0)
            val oldId = oldItem.getString("feedbackId")
            assertEquals("OK", store.claim(actionArgs("task_claim", oldId, destination), oldGeneration).getString("code"))
            assertEquals("OK", store.admit(actionArgs("task_admit", oldId, destination), oldGeneration).getString("code"))
            assertTrue(store.enqueue(recoveryDraft().put("taskId", "task_${"9".repeat(32)}")
                .put("revision", 909).toString(), newGeneration))
            assertEquals(0, store.pending(0, oldGeneration, destination).getJSONObject("data").getJSONArray("items").length())
            assertEquals(1, store.pending(0, newGeneration, destination).getJSONObject("data").getJSONArray("items").length())
            store.close()

            store = BrowserTaskFeedbackStore(context, dbName)
            val oldState = store.readableDatabase.rawQuery("SELECT state FROM feedback WHERE feedback_id=?", arrayOf(oldId))
                .use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
            assertEquals("UNKNOWN", oldState)
            assertFalse(store.enqueue(oldDraft.toString(), oldGeneration))
            val retry = store.admit(actionArgs("task_admit", oldId, destination), oldGeneration)
            assertEquals("FEEDBACK_ALREADY_ADMITTED", retry.getString("code"))
            assertEquals("unknown", retry.getJSONObject("data").getString("state"))
            assertEquals(0, store.pending(0, oldGeneration, destination).getJSONObject("data").getJSONArray("items").length())
            store.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun rejectsDraftPayloadOverSixtyFourKibibytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        try {
            val store = BrowserTaskFeedbackStore(context, dbName)
            val oversized = recoveryDraft().toString() + " ".repeat(BrowserTaskFeedbackStore.MAX_MESSAGE_BYTES)
            assertTrue(oversized.toByteArray(Charsets.UTF_8).size > BrowserTaskFeedbackStore.MAX_MESSAGE_BYTES)
            assertFalse(store.enqueue(oversized, 42L))
            store.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun pendingPagesRespectResponseBudgetAndAdvanceOnlyThroughIncludedItems() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbName = "feedback-test-${UUID.randomUUID()}.db"
        try {
            val store = BrowserTaskFeedbackStore(context, dbName)
            repeat(BrowserTaskFeedbackStore.MAX_PENDING) { index ->
                val taskId = "task_${index.toString(16).padStart(32, '0')}"
                assertTrue(store.enqueue(recoveryDraft().put("taskId", taskId)
                    .put("goal", "g".repeat(4_000)).put("notes", "n".repeat(2_000)).toString(), 43L))
            }
            var cursor = 0L
            val seen = mutableListOf<String>()
            while (seen.size < BrowserTaskFeedbackStore.MAX_PENDING) {
                val response = store.pending(cursor, 43L, "agent-session-d")
                assertTrue(response.toString().toByteArray(Charsets.UTF_8).size <= BrowserTaskFeedbackStore.MAX_PENDING_PAGE_BYTES)
                val data = response.getJSONObject("data")
                val items = data.getJSONArray("items")
                assertTrue("pending pagination ended before all feedback was returned", items.length() > 0)
                for (index in 0 until items.length()) seen += items.getJSONObject(index).getString("feedbackId")
                val previousCursor = cursor
                cursor = data.getLong("nextSequence")
                assertTrue(cursor > previousCursor)
            }
            assertEquals(64, seen.distinct().size)
            assertEquals(64L, cursor)
            store.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun recoveryDraft() = JSONObject()
        .put("kind", "task_recovery")
        .put("taskId", "task_${"a".repeat(32)}")
        .put("title", "Resume review")
        .put("goal", "Review current state")
        .put("notes", "Check the current page before continuing")
        .put("status", "needs_reconciliation")
        .put("revision", 4)
        .put("unknownEffects", JSONArray().put(JSONObject()
            .put("method", "click").put("requestId", "b".repeat(32))
            .put("phase", "unknown").put("lastUserChoice", JSONObject.NULL)))
        .put("unknownEffectsTotal", 1)
        .put("effectsTruncated", false)
        .put("createdAtWallMillis", 100L)

    private fun actionArgs(action: String, feedbackId: String, destination: String = "agent-session-a") = JSONObject()
        .put("action", action)
        .put("feedbackId", feedbackId)
        .put("destinationSessionKey", destination)
        .put("requestId", "c".repeat(32))
        .apply { if (action != "task_receipt") put("payloadDigest", "d".repeat(64)) }
}
