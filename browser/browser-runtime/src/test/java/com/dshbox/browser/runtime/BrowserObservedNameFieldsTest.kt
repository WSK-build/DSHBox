package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserObservedNameFieldsTest {
    @Test
    fun acceptsEmptyNamesAndIndependentPlaceholderWithoutCoercingOrTruncatingInvalidValues() {
        val fields = JSONObject().put("accessibleName", "").put("placeholder", "输入关键词")
        assertTrue(BrowserDomScopeMetadata.nameFieldsValid(fields))
        assertFalse(BrowserDomScopeMetadata.nameFieldsValid(JSONObject(fields.toString()).put("accessibleName", 12)))
        assertFalse(BrowserDomScopeMetadata.nameFieldsValid(JSONObject(fields.toString()).put("placeholder", "x".repeat(161))))
        assertFalse(BrowserDomScopeMetadata.nameFieldsValid(JSONObject().put("accessibleName", "name")))
    }
}
