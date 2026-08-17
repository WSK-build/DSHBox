package com.dshbox.app.common

import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {
    @Test
    fun redactsApiKey() {
        val out = LogRedactor.redact("api_key=sk-abcdef123")
        assertTrue(out, !out.contains("sk-abcdef123"))
    }

    @Test
    fun redactsAuthorizationBearer() {
        val out = LogRedactor.redact("Authorization: Bearer secret-token")
        assertTrue(out, !out.contains("secret-token"))
    }
}
