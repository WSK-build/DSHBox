package com.dshbox.browser.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAdvancedDocumentPolicyTest {
    @Test
    fun documentBoundCommandsAreSeparatedFromSameTargetReadsAndStop() {
        assertTrue(isAdvancedRequestDocumentBound("debug", "console"))
        assertTrue(isAdvancedRequestDocumentBound("debug", "evaluate"))
        assertFalse(isAdvancedRequestDocumentBound("debug", "status"))
        assertTrue(isAdvancedRequestDocumentBound("network", "start"))
        assertTrue(isAdvancedRequestDocumentBound("network", "rules"))
        assertTrue(isAdvancedRequestDocumentBound("network", "resolve"))
        assertTrue(isAdvancedRequestDocumentBound("network", "replay"))
        assertTrue(isAdvancedRequestDocumentBound("network", "replay_prepare"))
        assertTrue(isAdvancedRequestDocumentBound("network", "replay_status"))
        assertTrue(isAdvancedRequestDocumentBound("network", "clear_rules"))
        assertFalse(isAdvancedRequestDocumentBound("network", "list"))
        assertFalse(isAdvancedRequestDocumentBound("network", "body"))
        assertFalse(isAdvancedRequestDocumentBound("network", "har"))
        assertFalse(isAdvancedRequestDocumentBound("network", "paused"))
        assertFalse(isAdvancedRequestDocumentBound("network", "stop"))
    }
}
