package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdvancedResultFormatterTest {
    @Test fun successfulResponseShowsReadableScalarFieldsAndKeepsRawJsonSeparate() {
        val formatted = BrowserAdvancedResultFormatter.format(
            JSONObject("""{"code":"OK","executed":true,"data":{"count":3,"url":"https://example.test","entries":[{"secret":"x"}]}}"""),
        )
        assertTrue(formatted.success)
        assertEquals("OK", formatted.code)
        assertEquals(mapOf("count" to "3", "url" to "https://example.test"),
            formatted.fields.associate { it.key to it.value })
        assertTrue(formatted.rawJson.contains("secret"))
    }

    @Test fun rejectedResponseIsReportedAsFailure() {
        assertFalse(BrowserAdvancedResultFormatter.format(JSONObject("""{"code":"PERMISSION_DENIED","executed":false}""")).success)
    }

    @Test fun successfulReadDoesNotRequireASideEffect() {
        assertTrue(BrowserAdvancedResultFormatter.format(
            JSONObject("""{"code":"OK","executed":false,"data":{"connected":true}}"""),
        ).success)
    }
}
