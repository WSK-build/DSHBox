package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowserWaitScopeDiagnosticsTest {
    @Test
    fun textAndSelectorDeclareTopFrameDomPartialCoverage() {
        val expectedFields = setOf(
            "kind",
            "topFrameOnly",
            "frameTraversal",
            "conditionCoverage",
            "wholePageCoverage",
        )

        for (condition in listOf("text", "selector")) {
            val data = BrowserWaitScopeDiagnostics.data(condition)
            assertEquals(expectedFields, data.keySet())
            assertEquals("top_frame", data.getString("kind"))
            assertEquals(true, data.getBoolean("topFrameOnly"))
            assertEquals("not_attempted", data.getString("frameTraversal"))
            assertEquals("declared_scope", data.getString("conditionCoverage"))
            assertEquals("partial", data.getString("wholePageCoverage"))
        }
    }

    @Test
    fun pageConditionsDeclareNavigationScopeWithoutAssessingDomFrames() {
        val conditions = listOf("load", "url", "domcontentloaded", "networkidle")
        for (condition in conditions) {
            val data = BrowserWaitScopeDiagnostics.data(condition)
            assertEquals("page_navigation", data.getString("kind"))
            assertEquals(true, data.getBoolean("topFrameOnly"))
            assertEquals("not_attempted", data.getString("frameTraversal"))
            assertEquals("declared_scope", data.getString("conditionCoverage"))
            assertEquals("not_assessed", data.getString("wholePageCoverage"))
            assertFalse(data.has("condition"))
        }
    }

    @Test
    fun everyCallReturnsIndependentBoundedDiagnostics() {
        val first = BrowserWaitScopeDiagnostics.data("selector")
        val second = BrowserWaitScopeDiagnostics.data("selector")
        assertNotEquals(first, second)
        assertEquals(5, first.length())
        assertEquals(5, second.length())
    }
}
