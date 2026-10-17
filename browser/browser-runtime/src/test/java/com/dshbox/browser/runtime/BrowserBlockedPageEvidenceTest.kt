package com.dshbox.browser.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserBlockedPageEvidenceTest {
    @Test fun keywordMatchesAreHintsWithoutExposingPageText() {
        val result = BrowserBlockedPageEvidence.inspect(JSONObject().put("title", "Verify you are human")
            .put("text", "CAPTCHA article; private-token-example"))
        assertEquals("possible", result.getString("assessment"))
        assertFalse(result.getBoolean("automaticResolutionAllowed"))
        assertFalse(result.toString().contains("private-token-example"))
        assertEquals("keyword_match", result.getJSONArray("signals").getJSONObject(0).getString("evidence"))
    }
    @Test fun absenceOfHintsDoesNotClaimThePageIsUnblocked() {
        val result = BrowserBlockedPageEvidence.inspect(JSONObject().put("text", "Products"))
        assertEquals("not_detected", result.getString("assessment"))
        assertEquals(0, result.getJSONArray("signals").length())
        assertEquals("continue_observing", result.getString("nextAction"))
    }
    @Test fun observedPasswordFieldProducesAuthenticationHintWithoutReadingValue() {
        val data = JSONObject().put("elements", JSONArray().put(JSONObject().put("tag", "input")
            .put("type", "password").put("value", "never-export-this")))
        val result = BrowserBlockedPageEvidence.inspect(data)
        assertEquals("authentication", result.getJSONArray("signals").getJSONObject(0).getString("kind"))
        assertFalse(result.toString().contains("never-export-this"))
    }
    @Test fun overlayPseudoHintsAndDuplicatesStayBounded() {
        val overlay = JSONObject().put("name", "安全验证").put("pseudo", JSONArray()
            .put(JSONObject().put("boundedContent", "访问受限")))
        val result = BrowserBlockedPageEvidence.inspect(JSONObject().put("overlays", JSONArray(List(30) { overlay })))
        assertEquals(2, result.getJSONArray("signals").length())
    }
}
