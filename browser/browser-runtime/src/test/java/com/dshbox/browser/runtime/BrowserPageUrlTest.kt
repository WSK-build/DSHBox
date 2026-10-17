package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserPageUrlTest {
    @Test
    fun missingOrInvalidWebViewUrlFallsBackToAboutBlank() {
        assertEquals("about:blank", browserAddressText(null))
        assertEquals("about:blank", browserAddressText(""))
        assertEquals("about:blank", browserAddressText("javascript:alert(1)"))
        assertEquals("about:blank", browserAddressText("https://user:pass@example.test/"))
    }

    @Test
    fun supportedPageUrlIsPreservedAndAboutBlankRemainsValid() {
        assertEquals("about:blank", browserAddressText("about:blank"))
        assertEquals("https://example.test/path?q=1", browserAddressText("https://example.test/path?q=1"))
    }
}
