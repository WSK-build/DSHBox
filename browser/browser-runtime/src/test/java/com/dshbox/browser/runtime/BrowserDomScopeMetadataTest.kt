package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDomScopeMetadataTest {
    private fun data() = JSONObject("""{
        "scopeCoverage":{"complete":true,"scannedElements":8,"frameCount":2,
        "shadowRootCount":1,"inaccessibleFrameCount":0,"frameLimitReached":false,
        "shadowLimitReached":false,"elementLimitReached":false,"textNodesVisited":9,"textLimitReached":false},
        "frameScopes":[{"ordinal":1,"parentOrdinal":0,"depth":1,"status":"accessible","reason":null},
        {"ordinal":2,"parentOrdinal":1,"depth":2,"status":"accessible","reason":null}]
    }""")

    @Test fun nestedAccessibleTargetIsValid() {
        val data = data()
        assertTrue(BrowserDomScopeMetadata.valid(data))
        assertTrue(BrowserDomScopeMetadata.targetValid(JSONObject("""{"frameOrdinal":2,"shadowDepth":1}"""), data.getJSONArray("frameScopes")))
    }

    @Test fun rejectsMalformedCountsDepthAndExtraFields() {
        data().also { it.getJSONObject("scopeCoverage").put("frameCount", 1); assertFalse(BrowserDomScopeMetadata.valid(it)) }
        data().also { it.getJSONArray("frameScopes").getJSONObject(1).put("depth", 1); assertFalse(BrowserDomScopeMetadata.valid(it)) }
        data().also { it.getJSONObject("scopeCoverage").put("other", true); assertFalse(BrowserDomScopeMetadata.valid(it)) }
        data().also { it.getJSONObject("scopeCoverage").put("scannedElements", 8.5); assertFalse(BrowserDomScopeMetadata.valid(it)) }
        data().also {
            it.getJSONArray("frameScopes").getJSONObject(0).put("status", "inaccessible").put("reason", "CROSS_ORIGIN_OR_OPAQUE")
            it.getJSONObject("scopeCoverage").put("inaccessibleFrameCount", 1).put("complete", false)
            assertFalse(BrowserDomScopeMetadata.valid(it))
        }
    }

    @Test fun inaccessibleFrameCannotBeExecutableTarget() {
        val data = data()
        data.getJSONArray("frameScopes").getJSONObject(1).put("status", "inaccessible").put("reason", "CROSS_ORIGIN_OR_OPAQUE")
        data.getJSONObject("scopeCoverage").put("complete", false).put("inaccessibleFrameCount", 1)
        assertTrue(BrowserDomScopeMetadata.valid(data))
        assertFalse(BrowserDomScopeMetadata.targetValid(JSONObject("""{"frameOrdinal":2,"shadowDepth":0}"""), data.getJSONArray("frameScopes")))
        data.getJSONObject("scopeCoverage").put("complete", true)
        assertFalse(BrowserDomScopeMetadata.valid(data))
    }
}
