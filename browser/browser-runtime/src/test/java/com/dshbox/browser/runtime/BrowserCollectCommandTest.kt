package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserClock
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOwner
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserCollectCommandTest {
    private fun args(maxRecords: Int = 2, maxContentChars: Int = 32) = JSONObject()
        .put("action", "window").put("declaredRange", "current conversation")
        .put("maxRecords", maxRecords).put("maxContentChars", maxContentChars)

    private fun snapshot() = BrowserLifecycleController(BrowserClock { 100L })
        .open(BrowserOwner.AI)

    private fun row(key: String, order: Int, content: String = "hello") = JSONObject()
        .put("key", key).put("author", "speaker").put("content", content).put("order", order)

    private fun raw(records: JSONArray, truncated: Boolean = false) = JSONObject()
        .put("code", "OK").put("containerRefIndex", 0)
        .put("container", JSONObject().put("scrollTop", 0).put("scrollHeight", 400)
            .put("clientHeight", 100).put("startReached", true).put("endReached", false)
            .put("totalCount", 2))
        .put("records", records).put("truncated", truncated).put("excludedCount", 0)

    private fun encode(raw: JSONObject, args: JSONObject = args()) = BrowserCollectWindowEnvelope.encode(
        raw = raw,
        args = args,
        snapshot = snapshot(),
        pageId = "pg-0123456789abcdef0123",
        documentGeneration = 4,
        navigationId = 8,
        observationId = 3,
        containerNodeId = "b:1:1:4:3:${"a".repeat(32)}:0",
    )

    @Test fun nativeEnvelopeInjectsFreshIdentityAndReferenceAroundRawWindow() {
        val envelope = encode(raw(JSONArray().put(row("m1", 0)).put(row("m2", 1))))
        assertNotNull(envelope)
        assertEquals("window", envelope!!.getString("action"))
        assertEquals("current conversation", envelope.getString("declaredRange"))
        assertEquals("b:1:1:4:3:${"a".repeat(32)}:0", envelope.getString("containerNodeId"))
        val identity = envelope.getJSONObject("identity")
        assertEquals("pg-0123456789abcdef0123", identity.getString("pageId"))
        assertEquals(4, identity.getInt("documentGeneration"))
        assertEquals(8, identity.getInt("navigationId"))
        assertEquals(3, identity.getInt("observationId"))
        assertEquals(1, identity.getInt("sessionGeneration"))
        assertEquals(1, identity.getInt("controlGeneration"))
        assertEquals(2, envelope.getJSONArray("records").length())
    }

    @Test fun rawWindowMustStayWithinRequestedAndHardRowLimits() {
        assertNull(encode(raw(JSONArray().put(row("m1", 0)).put(row("m2", 1)).put(row("m3", 2)))))
        val tooMany = JSONArray()
        repeat(BrowserCollectOutputPolicy.MAX_RECORDS + 1) { index -> tooMany.put(row("m$index", index)) }
        assertNull(encode(raw(tooMany), args(maxRecords = BrowserCollectOutputPolicy.MAX_RECORDS)))
        assertTrue(BrowserCollectOutputPolicy.withinRecordBudget(2, 2))
        assertFalse(BrowserCollectOutputPolicy.withinRecordBudget(3, 2))
        assertFalse(BrowserCollectOutputPolicy.withinRecordBudget(201, 201))
    }

    @Test fun oversizedResponseUsesSmallExplicitLimitFailureAndUtf8Cap() {
        val atLimit = "😀".repeat(4095)
        val overLimit = "😀".repeat(4096)
        assertTrue(BrowserCollectOutputPolicy.withinResponseBudget(atLimit))
        assertFalse(BrowserCollectOutputPolicy.withinResponseBudget(overLimit))
        var overflowCalled = false
        val selected = BrowserCollectOutputPolicy.enforce(overLimit) {
            overflowCalled = true
            "{\"code\":\"LIMIT_EXCEEDED\",\"data\":{\"truncated\":true}}"
        }
        assertTrue(overflowCalled)
        assertEquals("{\"code\":\"LIMIT_EXCEEDED\",\"data\":{\"truncated\":true}}", selected)
        assertTrue(BrowserCollectOutputPolicy.withinResponseBudget(selected))
    }

    @Test fun unknownReferenceIndexOrUnexpectedRawFieldsAreRejected() {
        val wrongIndex = raw(JSONArray().put(row("m1", 0))).put("containerRefIndex", 1)
        assertNull(encode(wrongIndex))
        val unexpected = raw(JSONArray().put(row("m1", 0))).put("pageId", "page-claimed-by-page")
        assertNull(encode(unexpected))
    }

    @Test fun containerThatFitsInOneWindowCanBeCollectedWithoutScrolling() {
        val value = raw(JSONArray().put(row("m1", 0)))
        value.getJSONObject("container").put("scrollHeight", 100).put("endReached", true).put("totalCount", 1)
        assertNotNull(encode(value))
        value.getJSONObject("container").put("scrollHeight", 99)
        assertNull(encode(value))
    }

    @Test fun excludedRowsAreCountedWithoutAcceptingSerializedRowFields() {
        val args = args().put("excludeSelector", ".draft")
        val result = encode(raw(JSONArray().put(row("visible", 0)), truncated = false)
            .put("excludedCount", 2), args)
        assertNotNull(result)
        assertEquals(2, result!!.getInt("excludedCount"))
        assertFalse(result.toString().contains("draft"))
        assertNull(encode(raw(JSONArray().put(row("visible", 0))).put("leakedExcludedRow", JSONObject()), args))
        assertNull(encode(raw(JSONArray().put(row("visible", 0))).put("excludedCount", -1), args))
    }
}
