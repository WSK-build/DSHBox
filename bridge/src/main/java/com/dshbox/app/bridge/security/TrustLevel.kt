package com.dshbox.app.bridge.security

enum class TrustLevel {
    /** Untrusted internet page. No bridge access. */
    PUBLIC_WEB,

    /** Local web page that is not the trusted DSH WebUI. Web-only access. */
    LOCAL_WEB,

    /** Verified DSH WebUI. Can be granted capability-scoped bridge access. */
    TRUSTED_DSH_WEBUI,
}

data class WebOrigin(
    val url: String,
    val scheme: String,
    val host: String,
    val port: Int?,
)
