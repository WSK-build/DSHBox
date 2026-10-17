package com.dshbox.browser.runtime

import java.net.URI

/** Returns a safe address-field value when a WebView has not reported its current URL yet. */
internal fun browserAddressText(rawUrl: String?): String {
    val candidate = rawUrl?.takeIf { it.isNotBlank() } ?: "about:blank"
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return "about:blank"
    val scheme = uri.scheme?.lowercase() ?: return "about:blank"
    if (scheme == "about") return if (candidate == "about:blank") candidate else "about:blank"
    if (scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return "about:blank"
    return candidate
}
