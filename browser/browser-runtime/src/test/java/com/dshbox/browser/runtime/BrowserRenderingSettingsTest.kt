package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserRenderingSettingsTest {
    @Test
    fun defaultsExposeCanonicalValuesWithoutAUserAgentString() {
        val settings = BrowserRenderingSettings()

        assertEquals(BrowserRenderingSettings.UserAgentMode.DEFAULT, settings.userAgentMode)
        assertEquals("default", settings.get(BrowserRenderingSettings.KEY_USER_AGENT_MODE))
        assertEquals(BrowserRenderingSettings.LayoutWidthMode.DEVICE, settings.layoutWidthMode)
        assertEquals("device", settings.get(BrowserRenderingSettings.KEY_LAYOUT_WIDTH_MODE))
        assertEquals(false, settings.get(BrowserRenderingSettings.KEY_OVERVIEW_MODE))
        assertEquals(100, settings.get(BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT))
        assertEquals(0L, settings.get(BrowserRenderingSettings.KEY_REVISION))
        assertFalse(settings.toString().contains("userAgentString"))
    }

    @Test
    fun independentChangesReportTheirOwnEffects() {
        val initial = BrowserRenderingSettings()

        val userAgent = initial.set(BrowserRenderingSettings.KEY_USER_AGENT_MODE, "mobile")
        assertTrue(userAgent.changed)
        assertTrue(userAgent.reloadRequired)
        assertTrue(userAgent.invalidatesVisualReferences)
        assertEquals(1L, userAgent.settings.revision)
        assertEquals(
            BrowserRenderingSettings.UserAgentMode.MOBILE,
            userAgent.settings.userAgentMode,
        )

        val layout = initial.set(BrowserRenderingSettings.KEY_LAYOUT_WIDTH_MODE, "wide")
        assertTrue(layout.changed)
        assertTrue(layout.reloadRequired)
        assertTrue(layout.invalidatesVisualReferences)
        assertEquals(1L, layout.settings.revision)
        assertEquals(
            BrowserRenderingSettings.LayoutWidthMode.WIDE,
            layout.settings.layoutWidthMode,
        )

        val overview = initial.set(BrowserRenderingSettings.KEY_OVERVIEW_MODE, true)
        assertTrue(overview.changed)
        assertTrue(overview.reloadRequired)
        assertTrue(overview.invalidatesVisualReferences)
        assertEquals(1L, overview.settings.revision)
        assertTrue(overview.settings.overviewMode)

        val textScale = initial.set(BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, 150)
        assertTrue(textScale.changed)
        assertTrue(textScale.reloadRequired)
        assertTrue(textScale.invalidatesVisualReferences)
        assertEquals(1L, textScale.settings.revision)
        assertEquals(150, textScale.settings.textScalePercent)
    }

    @Test
    fun aNoOpSetReportsNoConsequencesAndDoesNotMutateTheReceiver() {
        val initial = BrowserRenderingSettings()

        val result = initial.set(BrowserRenderingSettings.KEY_OVERVIEW_MODE, false)

        assertEquals(initial, result.settings)
        assertFalse(result.changed)
        assertFalse(result.reloadRequired)
        assertFalse(result.invalidatesVisualReferences)
        assertEquals(false, initial.overviewMode)
    }

    @Test
    fun getAndSetRejectUnknownKeysWithoutEchoingTheirInput() {
        val secret = "opaque-input-value"
        val settings = BrowserRenderingSettings()

        val getFailure = assertThrows(IllegalArgumentException::class.java) {
            settings.get(secret)
        }
        val setFailure = assertThrows(IllegalArgumentException::class.java) {
            settings.set(secret, true)
        }

        assertEquals("UNKNOWN_KEY", getFailure.message)
        assertEquals("UNKNOWN_KEY", setFailure.message)
        assertFalse(getFailure.message.orEmpty().contains(secret))
        assertFalse(setFailure.message.orEmpty().contains(secret))
    }

    @Test
    fun modesAreExactCanonicalStringsAndRejectTypeCoercion() {
        val settings = BrowserRenderingSettings()

        assertEquals("default", settings.get(BrowserRenderingSettings.KEY_USER_AGENT_MODE))
        assertEquals("mobile", settings.set(
            BrowserRenderingSettings.KEY_USER_AGENT_MODE,
            "mobile",
        ).settings.userAgentMode.wireValue)
        assertEquals("desktop", settings.set(
            BrowserRenderingSettings.KEY_USER_AGENT_MODE,
            "desktop",
        ).settings.userAgentMode.wireValue)
        assertEquals("wide", settings.set(
            BrowserRenderingSettings.KEY_LAYOUT_WIDTH_MODE,
            "wide",
        ).settings.layoutWidthMode.wireValue)

        assertInvalidType(settings, BrowserRenderingSettings.KEY_USER_AGENT_MODE, null)
        assertInvalidEnum(settings, BrowserRenderingSettings.KEY_USER_AGENT_MODE, "DEFAULT")
        assertInvalidEnum(settings, BrowserRenderingSettings.KEY_USER_AGENT_MODE, "custom")
        assertInvalidType(settings, BrowserRenderingSettings.KEY_USER_AGENT_MODE, 1)
        assertInvalidType(settings, BrowserRenderingSettings.KEY_LAYOUT_WIDTH_MODE, true)
        assertInvalidEnum(settings, BrowserRenderingSettings.KEY_LAYOUT_WIDTH_MODE, "custom")
        assertInvalidType(settings, BrowserRenderingSettings.KEY_OVERVIEW_MODE, "true")
        assertInvalidType(settings, BrowserRenderingSettings.KEY_OVERVIEW_MODE, 1)
        assertInvalidType(settings, BrowserRenderingSettings.KEY_OVERVIEW_MODE, null)
    }

    @Test
    fun textScaleAcceptsOnlyIntegerValuesInsideItsBounds() {
        val settings = BrowserRenderingSettings()

        val minimum = settings.set(BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, 50)
        assertEquals(50, minimum.settings.textScalePercent)
        assertTrue(minimum.reloadRequired)
        assertEquals(200, settings.set(
            BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT,
            200,
        ).settings.textScalePercent)

        assertOutOfRange(settings, BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, 49)
        assertOutOfRange(settings, BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, 201)
        assertInvalidType(settings, BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, 100L)
        assertInvalidType(settings, BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, 100.0)
        assertInvalidType(
            settings,
            BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT,
            Double.NaN,
        )
        assertInvalidType(settings, BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, "100")
        assertInvalidType(settings, BrowserRenderingSettings.KEY_TEXT_SCALE_PERCENT, null)
    }

    @Test
    fun revisionIsReadableButCannotBeWrittenThroughSettings() {
        val settings = BrowserRenderingSettings()

        assertEquals(0L, settings.get(BrowserRenderingSettings.KEY_REVISION))

        for (value in listOf(1L, -1L, 1.0, Double.NaN, "1", null)) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                settings.set(BrowserRenderingSettings.KEY_REVISION, value)
            }
            assertEquals("READ_ONLY_KEY", failure.message)
        }
        assertEquals(0L, settings.revision)
    }

    @Test
    fun revisionAtTheJsSafeLimitRejectsActualChangesButAllowsNoOps() {
        val settings = BrowserRenderingSettings(
            revision = BrowserRenderingSettings.MAX_JS_SAFE_INTEGER,
        )

        val noOp = settings.set(BrowserRenderingSettings.KEY_OVERVIEW_MODE, false)
        assertFalse(noOp.changed)
        assertEquals(
            BrowserRenderingSettings.MAX_JS_SAFE_INTEGER,
            noOp.settings.revision,
        )

        val failure = assertThrows(IllegalArgumentException::class.java) {
            settings.set(BrowserRenderingSettings.KEY_OVERVIEW_MODE, true)
        }
        assertEquals("REVISION_LIMIT", failure.message)
        assertEquals(
            BrowserRenderingSettings.MAX_JS_SAFE_INTEGER,
            settings.revision,
        )
    }

    @Test
    fun compareAndSetUsesExpectedRevisionWithoutChangingTheSetContract() {
        val settings = BrowserRenderingSettings(revision = 7L)

        val changed = settings.update(
            BrowserRenderingSettings.KEY_OVERVIEW_MODE,
            true,
            expectedRevision = 7L,
        )
        assertTrue(changed.changed)
        assertTrue(changed.reloadRequired)
        assertEquals(8L, changed.settings.revision)

        val stale = assertThrows(IllegalArgumentException::class.java) {
            settings.update(
                BrowserRenderingSettings.KEY_OVERVIEW_MODE,
                true,
                expectedRevision = 6L,
            )
        }
        assertEquals("STALE_REVISION", stale.message)

        val readOnly = assertThrows(IllegalArgumentException::class.java) {
            settings.update(
                BrowserRenderingSettings.KEY_REVISION,
                8L,
                expectedRevision = 7L,
            )
        }
        assertEquals("READ_ONLY_KEY", readOnly.message)

        val unknown = assertThrows(IllegalArgumentException::class.java) {
            settings.update("unknown", true, expectedRevision = 6L)
        }
        assertEquals("UNKNOWN_KEY", unknown.message)

        val outOfRange = assertThrows(IllegalArgumentException::class.java) {
            settings.update(
                BrowserRenderingSettings.KEY_OVERVIEW_MODE,
                true,
                expectedRevision = -1L,
            )
        }
        assertEquals("OUT_OF_RANGE", outOfRange.message)
    }

    @Test
    fun constructorEnforcesTheSameNumericBounds() {
        assertThrows(IllegalArgumentException::class.java) {
            BrowserRenderingSettings(textScalePercent = 49)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserRenderingSettings(textScalePercent = 201)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserRenderingSettings(revision = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BrowserRenderingSettings(
                revision = BrowserRenderingSettings.MAX_JS_SAFE_INTEGER + 1L,
            )
        }
    }

    private fun assertInvalidType(
        settings: BrowserRenderingSettings,
        key: String,
        value: Any?,
    ) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            settings.set(key, value)
        }
        assertEquals("INVALID_TYPE", failure.message)
        assertNotEchoed(failure, value)
    }

    private fun assertInvalidEnum(
        settings: BrowserRenderingSettings,
        key: String,
        value: Any?,
    ) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            settings.set(key, value)
        }
        assertEquals("INVALID_ENUM", failure.message)
        assertNotEchoed(failure, value)
    }

    private fun assertOutOfRange(
        settings: BrowserRenderingSettings,
        key: String,
        value: Any?,
    ) {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            settings.set(key, value)
        }
        assertEquals("OUT_OF_RANGE", failure.message)
        assertNotEchoed(failure, value)
    }

    private fun assertNotEchoed(failure: Throwable, value: Any?) {
        if (value != null) {
            assertFalse(failure.message.orEmpty().contains(value.toString()))
        }
    }
}
