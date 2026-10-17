package com.dshbox.browser.runtime

import android.content.SharedPreferences

/** Stores only rendering policy metadata; user-agent strings and page content are never persisted. */
internal object BrowserRenderingPreferences {
    fun read(preferences: SharedPreferences): BrowserRenderingSettings {
        val values = preferences.all
        val ua = BrowserRenderingSettings.UserAgentMode.entries.firstOrNull {
            it.wireValue == values["render_user_agent_mode"]
        } ?: BrowserRenderingSettings.UserAgentMode.DEFAULT
        val width = BrowserRenderingSettings.LayoutWidthMode.entries.firstOrNull {
            it.wireValue == values["render_layout_width_mode"]
        } ?: BrowserRenderingSettings.LayoutWidthMode.DEVICE
        val text = (values["render_text_scale_percent"] as? Int)?.takeIf { it in 50..200 } ?: 100
        val revision = (values["render_revision"] as? Long)?.takeIf {
            it in 0L..BrowserRenderingSettings.MAX_JS_SAFE_INTEGER
        } ?: 0L
        return BrowserRenderingSettings(ua, width, values["render_overview_mode"] as? Boolean ?: false, text, revision)
    }

    fun write(preferences: SharedPreferences, settings: BrowserRenderingSettings): Boolean = preferences.edit()
        .putString("render_user_agent_mode", settings.userAgentMode.wireValue)
        .putString("render_layout_width_mode", settings.layoutWidthMode.wireValue)
        .putBoolean("render_overview_mode", settings.overviewMode)
        .putInt("render_text_scale_percent", settings.textScalePercent)
        .putLong("render_revision", settings.revision)
        .commit()
}
