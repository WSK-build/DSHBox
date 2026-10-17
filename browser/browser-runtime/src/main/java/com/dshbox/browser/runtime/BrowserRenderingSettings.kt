package com.dshbox.browser.runtime

/**
 * Immutable page rendering settings with strict key and value validation.
 *
 * The user-agent field stores policy mode metadata only. The default mode keeps the
 * WebView-provided user agent unchanged; this model never stores or returns a full
 * user-agent string. Revision is managed by this model and cannot be written through
 * the settings interface.
 */
internal data class BrowserRenderingSettings(
    val userAgentMode: UserAgentMode = UserAgentMode.DEFAULT,
    val layoutWidthMode: LayoutWidthMode = LayoutWidthMode.DEVICE,
    val overviewMode: Boolean = false,
    val textScalePercent: Int = DEFAULT_TEXT_SCALE_PERCENT,
    val revision: Long = 0L,
) {
    enum class UserAgentMode(val wireValue: String) {
        DEFAULT("default"),
        MOBILE("mobile"),
        DESKTOP("desktop"),
    }

    /** Supported page width policies; arbitrary pixel widths are not represented. */
    enum class LayoutWidthMode(val wireValue: String) {
        DEVICE("device"),
        WIDE("wide"),
    }

    init {
        require(textScalePercent in MIN_TEXT_SCALE_PERCENT..MAX_TEXT_SCALE_PERCENT) {
            "OUT_OF_RANGE"
        }
        require(revision in 0L..MAX_JS_SAFE_INTEGER) { "OUT_OF_RANGE" }
    }

    fun get(key: String): Any = when (key) {
        KEY_USER_AGENT_MODE -> userAgentMode.wireValue
        KEY_LAYOUT_WIDTH_MODE -> layoutWidthMode.wireValue
        KEY_OVERVIEW_MODE -> overviewMode
        KEY_TEXT_SCALE_PERCENT -> textScalePercent
        KEY_REVISION -> revision
        else -> throw IllegalArgumentException("UNKNOWN_KEY")
    }

    /**
     * Validates one canonical setting and returns the updated snapshot plus its
     * consequences. An actual change increments the managed revision; a no-op does not.
     * It performs no reload, replay, persistence, or other side effect.
     */
    fun set(key: String, value: Any?): BrowserRenderingSettingsChange {
        if (key == KEY_REVISION) throw IllegalArgumentException("READ_ONLY_KEY")
        val candidate = when (key) {
            KEY_USER_AGENT_MODE -> copy(userAgentMode = parseUserAgentMode(value))
            KEY_LAYOUT_WIDTH_MODE -> copy(layoutWidthMode = parseLayoutWidthMode(value))
            KEY_OVERVIEW_MODE -> copy(overviewMode = parseBoolean(value))
            KEY_TEXT_SCALE_PERCENT -> copy(
                textScalePercent = parseTextScalePercent(value),
            )
            else -> throw IllegalArgumentException("UNKNOWN_KEY")
        }
        val changed = candidate != this
        if (changed && revision == MAX_JS_SAFE_INTEGER) {
            throw IllegalArgumentException("REVISION_LIMIT")
        }
        val updated = if (changed) candidate.copy(revision = revision + 1L) else candidate
        return BrowserRenderingSettingsChange(
            settings = updated,
            changed = changed,
            reloadRequired = changed && key in RELOAD_REQUIRED_KEYS,
            invalidatesVisualReferences = changed,
        )
    }

    /**
     * Compare-and-set variant for callers that must reject a stale snapshot. The
     * revision argument is an expected service-managed value, not a writable setting.
     */
    fun update(
        key: String,
        value: Any?,
        expectedRevision: Long,
    ): BrowserRenderingSettingsChange {
        if (key == KEY_REVISION) throw IllegalArgumentException("READ_ONLY_KEY")
        if (key !in SETTING_KEYS) throw IllegalArgumentException("UNKNOWN_KEY")
        if (expectedRevision !in 0L..MAX_JS_SAFE_INTEGER) {
            throw IllegalArgumentException("OUT_OF_RANGE")
        }
        if (expectedRevision != revision) {
            throw IllegalArgumentException("STALE_REVISION")
        }
        return set(key, value)
    }

    private fun parseUserAgentMode(value: Any?): UserAgentMode {
        if (value !is String) throw IllegalArgumentException("INVALID_TYPE")
        return UserAgentMode.entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("INVALID_ENUM")
    }

    private fun parseLayoutWidthMode(value: Any?): LayoutWidthMode {
        if (value !is String) throw IllegalArgumentException("INVALID_TYPE")
        return LayoutWidthMode.entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("INVALID_ENUM")
    }

    private fun parseBoolean(value: Any?): Boolean =
        value as? Boolean ?: throw IllegalArgumentException("INVALID_TYPE")

    private fun parseTextScalePercent(value: Any?): Int {
        val parsed = value as? Int ?: throw IllegalArgumentException("INVALID_TYPE")
        if (parsed !in MIN_TEXT_SCALE_PERCENT..MAX_TEXT_SCALE_PERCENT) {
            throw IllegalArgumentException("OUT_OF_RANGE")
        }
        return parsed
    }

    companion object {
        const val KEY_USER_AGENT_MODE = "userAgentMode"
        const val KEY_LAYOUT_WIDTH_MODE = "layoutWidthMode"
        const val KEY_OVERVIEW_MODE = "overviewMode"
        const val KEY_TEXT_SCALE_PERCENT = "textScalePercent"
        const val KEY_REVISION = "revision"

        const val MIN_TEXT_SCALE_PERCENT = 50
        const val MAX_TEXT_SCALE_PERCENT = 200
        const val DEFAULT_TEXT_SCALE_PERCENT = 100
        const val MAX_JS_SAFE_INTEGER = 9_007_199_254_740_991L

        private val SETTING_KEYS = setOf(
            KEY_USER_AGENT_MODE,
            KEY_LAYOUT_WIDTH_MODE,
            KEY_OVERVIEW_MODE,
            KEY_TEXT_SCALE_PERCENT,
        )

        private val RELOAD_REQUIRED_KEYS = setOf(
            KEY_USER_AGENT_MODE,
            KEY_LAYOUT_WIDTH_MODE,
            KEY_OVERVIEW_MODE,
            KEY_TEXT_SCALE_PERCENT,
        )
    }
}

/** Consequences reported after a validated settings change; actions remain caller-owned. */
internal data class BrowserRenderingSettingsChange(
    val settings: BrowserRenderingSettings,
    val changed: Boolean,
    val reloadRequired: Boolean,
    val invalidatesVisualReferences: Boolean,
)
