package com.dshbox.app.bridge

import com.dshbox.app.bridge.api.BridgeApi
import com.dshbox.app.bridge.security.BridgeCapability
import com.dshbox.app.bridge.security.BridgePolicy
import com.dshbox.app.bridge.security.OriginVerifier
import com.dshbox.app.bridge.security.TrustLevel

/**
 * Entry point for WebView JS bridge calls. Every capability call must pass
 * through this router. The router does not trust localhost by itself.
 */
class BridgeRouter(
    private val delegate: BridgeApi,
    private val expectedDshToken: String,
) {
    private val sessionCapabilities = mutableSetOf<BridgeCapability>()
    private var userAuthorizedHighRisk = false

    fun classify(url: String): TrustLevel = OriginVerifier.classify(url)

    fun buildPolicy(url: String): BridgePolicy {
        val trust = classify(url)
        return BridgePolicy(
            trustLevel = trust,
            grantedCapabilities = sessionCapabilities.toSet(),
            userAuthorizedHighRisk = userAuthorizedHighRisk,
        )
    }

    fun grant(capabilities: Set<BridgeCapability>, userAuthorizedHighRisk: Boolean) {
        sessionCapabilities += capabilities
        this.userAuthorizedHighRisk = userAuthorizedHighRisk
    }

    fun revokeAll() {
        sessionCapabilities.clear()
        userAuthorizedHighRisk = false
    }

    val api: BridgeApi = delegate
}
