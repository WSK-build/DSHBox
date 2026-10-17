package com.dshbox.browser.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserRenderingCommandTest {
    @Test fun staleRevisionDoesNotWriteOrApply() {
        var writes = 0
        var applies = 0
        val command = BrowserRenderingCommand({ BrowserRenderingSettings(revision = 2) },
            { writes++; true }, { applies++ })
        val result = command.execute(set(1), { true })
        assertEquals("STALE_REVISION", result.getString("code"))
        assertEquals(false, result.getBoolean("executed"))
        assertEquals(0, writes)
        assertEquals(0, applies)
    }

    @Test fun persistedChangeIncrementsRevisionWithoutReloading() {
        var stored = BrowserRenderingSettings()
        var applied: BrowserRenderingSettings? = null
        val command = BrowserRenderingCommand({ stored }, { stored = it; true }, { applied = it })
        val result = command.execute(set(0), { true })
        assertEquals("OK", result.getString("code"))
        assertTrue(result.getBoolean("executed"))
        assertEquals(1L, stored.revision)
        assertEquals(stored, applied)
        assertTrue(result.getJSONObject("data").getBoolean("reloadRequired"))
    }

    @Test fun failedCommitDoesNotClaimDurableSuccessOrAllowBlindRetry() {
        var memory = BrowserRenderingSettings()
        val command = BrowserRenderingCommand({ memory }, { memory = it; false }, {})
        val result = command.execute(set(0), { true })
        assertEquals("SETTINGS_PERSISTENCE_FAILED", result.getString("code"))
        assertTrue(result.isNull("executed"))
        assertFalse(result.getJSONObject("data").getBoolean("persisted"))
        assertEquals(1L, result.getJSONObject("data").getJSONObject("settings").getLong("revision"))
        assertEquals("STALE_REVISION", command.execute(set(0), { true }).getString("code"))
    }

    @Test fun authorizationLossBeforeWriteHasNoEffect() {
        var checks = 0
        var writes = 0
        val command = BrowserRenderingCommand({ BrowserRenderingSettings() }, { writes++; true }, {})
        assertEquals("SESSION_REVOKED", command.execute(set(0), { ++checks == 1 }).getString("code"))
        assertEquals(0, writes)
    }

    @Test fun getAndNoOpDoNotWriteOrInvalidate() {
        var writes = 0
        var applies = 0
        val stored = BrowserRenderingSettings(textScalePercent = 110)
        val command = BrowserRenderingCommand({ stored }, { writes++; true }, { applies++ })
        val read = command.execute(JSONObject().put("action", "rendering").put("operation", "get"), { true })
        assertFalse(read.getBoolean("executed"))
        val noOp = command.execute(set(0), { true })
        assertFalse(noOp.getJSONObject("data").getBoolean("changed"))
        assertEquals(0, writes)
        assertEquals(0, applies)
    }

    @Test fun cleanCommitFailureDoesNotInvalidateUnchangedPolicy() {
        var applies = 0
        val command = BrowserRenderingCommand({ BrowserRenderingSettings() }, { false }, { applies++ })
        val result = command.execute(set(0), { true })
        assertEquals("SETTINGS_PERSISTENCE_FAILED", result.getString("code"))
        assertFalse(result.getJSONObject("data").getBoolean("changed"))
        assertFalse(result.getJSONObject("data").getBoolean("reloadRequired"))
        assertEquals(0, applies)
    }

    @Test fun applyFailureAfterCommitIsUnknownAndNotReplayable() {
        var stored = BrowserRenderingSettings()
        val command = BrowserRenderingCommand({ stored }, { stored = it; true }, { error("failed apply") })
        val result = command.execute(set(0), { true })
        assertEquals("UNKNOWN", result.getString("code"))
        assertTrue(result.isNull("executed"))
        assertEquals("rendering_apply", result.getJSONObject("data").getString("stage"))
        assertFalse(result.getJSONObject("data").getBoolean("automaticRetryAllowed"))
    }

    private fun set(revision: Long) = JSONObject().put("action", "rendering").put("operation", "set")
        .put("key", "textScalePercent").put("value", 110).put("expectedRevision", revision)
}
