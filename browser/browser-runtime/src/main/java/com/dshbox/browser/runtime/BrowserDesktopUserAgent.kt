package com.dshbox.browser.runtime

/**
 * Derives the desktop profile user-agent from the WebView default string.
 *
 * Only the Android/WebView markers are rewritten: the platform token becomes a desktop token, the
 * `wv` and `Version/4.0` artifacts are dropped, and the `Mobile` token is removed. The real Chrome
 * version and the `Safari/537.36` compatibility token are preserved, so no version is invented.
 */
internal object BrowserDesktopUserAgent {
    private const val DESKTOP_PLATFORM = "(X11; Linux x86_64)"
    private val ANDROID_PLATFORM = Regex("\\(Linux; Android[^)]*\\)")
    private val PLATFORM_WV = Regex(";\\s*wv(?=[;)])")
    private val WEBVIEW_VERSION = Regex("\\s+Version/4\\.0")
    private val MOBILE = Regex("\\s+Mobile(?=\\s)")
    private val REPEATED_WHITESPACE = Regex("\\s{2,}")

    fun from(defaultUserAgent: String): String {
        var value = ANDROID_PLATFORM.replace(defaultUserAgent, DESKTOP_PLATFORM)
        value = PLATFORM_WV.replace(value, "")
        value = WEBVIEW_VERSION.replace(value, "")
        value = MOBILE.replace(value, "")
        return REPEATED_WHITESPACE.replace(value, " ").trim()
    }
}
