package com.dshbox.browser.runtime

import android.content.Context
import android.content.res.Configuration
import com.dshbox.browser.api.BrowserUiSettings

/** Native Browser chrome colors resolved from the app's selected appearance mode. */
internal data class BrowserRuntimePalette(
    val paper: Int,
    val ink: Int,
    val muted: Int,
    val soft: Int,
    val toolbar: Int,
    val line: Int,
    val strongLine: Int,
    val blue: Int,
    val pickerBlueSoft: Int,
    val dark: Boolean,
) {
    companion object {
        fun resolve(context: Context, mode: String? = null): BrowserRuntimePalette {
            val stored = mode ?: context.getSharedPreferences(
                BrowserRuntimeSettings.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ).getString(BrowserRuntimeSettings.KEY_THEME_MODE, null)
            val selectedMode = BrowserUiSettings.normalizeThemeMode(stored)
            val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val dark = when (selectedMode) {
                BrowserUiSettings.THEME_DARK -> true
                BrowserUiSettings.THEME_LIGHT -> false
                else -> systemDark
            }
            return create(dark)
        }

        /** Safe before an Activity receives its Android base Context. */
        fun create(dark: Boolean): BrowserRuntimePalette = if (dark) BrowserRuntimePalette(
                paper = rgb(27, 28, 31),
                ink = rgb(240, 241, 244),
                muted = rgb(164, 167, 174),
                soft = rgb(39, 41, 45),
                toolbar = rgb(32, 33, 37),
                line = rgb(62, 64, 69),
                strongLine = rgb(79, 82, 88),
                blue = rgb(123, 169, 255),
                pickerBlueSoft = rgb(39, 53, 75),
                dark = true,
            ) else BrowserRuntimePalette(
                paper = rgb(255, 255, 255),
                ink = rgb(42, 43, 47),
                muted = rgb(124, 125, 131),
                soft = rgb(247, 247, 248),
                toolbar = rgb(253, 253, 253),
                line = rgb(222, 223, 226),
                strongLine = rgb(205, 206, 210),
                blue = rgb(52, 131, 245),
                pickerBlueSoft = rgb(232, 242, 255),
                dark = false,
            )
        private fun rgb(red: Int, green: Int, blue: Int): Int =
            (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }
}
