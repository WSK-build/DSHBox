package com.dshbox.app.bridge.security

enum class BridgeCapability {
    WORKSPACE,
    FILESYSTEM_READ,
    FILESYSTEM_WRITE,
    COMMAND,
    PROCESS,
    ANDROID_NOTIFICATION,
    ANDROID_CLIPBOARD,
    ANDROID_FILE_PICKER,
    ANDROID_SHARE,
}

data class BridgePolicyDecision(
    val allowed: Boolean,
    val reason: String? = null,
)

class BridgePolicy(
    private val trustLevel: TrustLevel,
    private val grantedCapabilities: Set<BridgeCapability>,
    private val userAuthorizedHighRisk: Boolean,
) {
    fun evaluate(capability: BridgeCapability): BridgePolicyDecision {
        if (trustLevel != TrustLevel.TRUSTED_DSH_WEBUI) {
            return BridgePolicyDecision(false, "untrusted origin")
        }
        if (capability !in grantedCapabilities) {
            return BridgePolicyDecision(false, "capability not granted")
        }
        if (capability in HIGH_RISK && !userAuthorizedHighRisk) {
            return BridgePolicyDecision(false, "user authorization required")
        }
        return BridgePolicyDecision(true)
    }

    companion object {
        val HIGH_RISK = setOf(
            BridgeCapability.COMMAND,
            BridgeCapability.FILESYSTEM_WRITE,
            BridgeCapability.PROCESS,
        )
    }
}
