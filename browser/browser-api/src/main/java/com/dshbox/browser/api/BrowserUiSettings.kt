package com.dshbox.browser.api

import java.util.Locale

/** Process-safe snapshot of app preferences that shape Browser chrome. */
data class BrowserUiSettings(
    val localeTag: String,
    val themeMode: String,
) {
    companion object {
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        const val APP_PREFERENCES_NAME = "app_settings"
        const val APP_THEME_KEY = "theme_mode"
        const val APP_LOCALE_KEY = "app_locale_tag"

        fun normalizeThemeMode(value: String?): String = when (value?.lowercase(Locale.ROOT)) {
            THEME_LIGHT -> THEME_LIGHT
            THEME_DARK -> THEME_DARK
            else -> THEME_SYSTEM
        }

        fun normalizeLocaleTag(value: String?): String = value
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { tag ->
                tag.isNotEmpty() && tag.length <= 128 &&
                    tag.none { character -> character.code < 0x20 || character.code in 0x7f..0x9f }
            }
            .orEmpty()
    }
}
