package com.dshbox.browser.runtime

import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.dshbox.browser.api.BrowserUiSettings

/** Resolved appearance for the WebView canvas and WebView's native darkening capability. */
internal data class BrowserWebThemePolicy(
    val dark: Boolean,
    val backgroundColor: Int,
) {
    val algorithmicDarkeningAllowed: Boolean get() = dark

    companion object {
        fun resolve(mode: String?, systemDark: Boolean): BrowserWebThemePolicy {
            val selected = when (BrowserUiSettings.normalizeThemeMode(mode)) {
                BrowserUiSettings.THEME_DARK -> true
                BrowserUiSettings.THEME_LIGHT -> false
                else -> systemDark
            }
            return BrowserWebThemePolicy(
                dark = selected,
                backgroundColor = BrowserRuntimePalette.create(selected).paper,
            )
        }
    }
}

/** Narrow seam for testing policy application without constructing an Android WebView. */
internal interface BrowserWebThemeTarget {
    fun setBackgroundColor(color: Int)
    fun setAlgorithmicDarkeningAllowed(allowed: Boolean)
}

/**
 * Applies native WebView darkening only when the installed WebView provider advertises support.
 * No page-wide CSS filters are injected: WebView respects author dark styles and uses its own
 * algorithm otherwise.
 */
internal object BrowserWebTheme {
    fun apply(view: WebView, policy: BrowserWebThemePolicy) {
        val settings = view.settings
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)
        }.getOrDefault(false)
        apply(object : BrowserWebThemeTarget {
            override fun setBackgroundColor(color: Int) = view.setBackgroundColor(color)
            override fun setAlgorithmicDarkeningAllowed(allowed: Boolean) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, allowed)
            }
        }, policy, supportsAlgorithmicDarkening = supported)
    }

    internal fun apply(
        target: BrowserWebThemeTarget,
        policy: BrowserWebThemePolicy,
        supportsAlgorithmicDarkening: Boolean,
    ) {
        target.setBackgroundColor(policy.backgroundColor)
        if (supportsAlgorithmicDarkening) {
            target.setAlgorithmicDarkeningAllowed(policy.algorithmicDarkeningAllowed)
        }
    }
}
