package com.dshbox.browser.runtime

import android.content.Context

internal object BrowserRuntimeSettings {
    const val PREFERENCES_NAME = "browser_runtime_settings"
    const val KEY_IDLE_MINUTES = "idle_minutes"
    const val KEY_AI_ENABLED = "ai_enabled"
    const val KEY_ADVANCED_ENABLED = "advanced_enabled"
    const val KEY_NETWORK_MUTATION_ENABLED = "network_mutation_enabled"
    const val KEY_SEARCH_ENGINE = "search_engine"
    const val KEY_LOCALE_TAG = "locale_tag"
    const val KEY_THEME_MODE = "theme_mode"
    const val KEY_NEXT_SESSION_GENERATION = "next_session_generation"
    const val KEY_LAST_STATUS_JSON = "last_status_json"
    const val KEY_LAST_SESSION_GENERATION = "last_session_generation"
    const val KEY_LAST_CONTROL_GENERATION = "last_control_generation"
    const val DEFAULT_IDLE_MINUTES = 5
    const val DEFAULT_SEARCH_ENGINE = "bing"
    const val DEFAULT_AI_ENABLED = true
    const val DEFAULT_ADVANCED_ENABLED = false
    const val DEFAULT_NETWORK_MUTATION_ENABLED = false
    const val SEARCH_BING = "bing"
    const val SEARCH_GOOGLE = "google"
    const val SEARCH_BAIDU = "baidu"
    const val SESSION_GENERATION_RESERVATION = 1024L
    const val MAX_SAFE_GENERATION = 9_007_199_254_740_991L
    const val EXTRA_START_AS_AI = "com.dshbox.browser.runtime.extra.START_AS_AI"
    const val EXTRA_OPEN_REQUEST_ID = "com.dshbox.browser.runtime.extra.OPEN_REQUEST_ID"
    const val EXTRA_OPEN_URL = "com.dshbox.browser.runtime.extra.OPEN_URL"
    const val EXTRA_OPEN_DEADLINE_ELAPSED = "com.dshbox.browser.runtime.extra.OPEN_DEADLINE_ELAPSED"
    const val EXTRA_UPLOAD_APPROVAL_ONLY = "com.dshbox.browser.runtime.extra.UPLOAD_APPROVAL_ONLY"
    const val EXTRA_UPLOAD_APPROVAL_ID = "com.dshbox.browser.runtime.extra.UPLOAD_APPROVAL_ID"

    fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
