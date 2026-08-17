package com.dshbox.app.bridge.security

import android.net.Uri

object OriginVerifier {
    fun classify(url: String): TrustLevel {
        val uri = Uri.parse(url) ?: return TrustLevel.PUBLIC_WEB
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()

        if (scheme == "http" && (host == "127.0.0.1" || host == "localhost")) {
            return TrustLevel.LOCAL_WEB
        }
        return TrustLevel.PUBLIC_WEB
    }

    /**
     * DSH WebUI is only treated as trusted when all checks pass:
     * origin, session token, and user authorization. This method is a
     * placeholder for the complete policy.
     */
    fun isTrustedDshWebUi(
        url: String,
        capabilityToken: String?,
        expectedToken: String?,
    ): Boolean {
        val local = classify(url) == TrustLevel.LOCAL_WEB
        val tokenOk = !capabilityToken.isNullOrBlank() && capabilityToken == expectedToken
        return local && tokenOk
    }
}
