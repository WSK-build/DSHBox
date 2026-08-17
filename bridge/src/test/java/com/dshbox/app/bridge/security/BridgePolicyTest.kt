package com.dshbox.app.bridge.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePolicyTest {
    @Test
    fun publicWebDeniesAll() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.PUBLIC_WEB,
            grantedCapabilities = setOf(BridgeCapability.WORKSPACE),
            userAuthorizedHighRisk = true,
        )
        assertFalse(policy.evaluate(BridgeCapability.WORKSPACE).allowed)
    }

    @Test
    fun highRiskRequiresUserAuthorization() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.TRUSTED_DSH_WEBUI,
            grantedCapabilities = setOf(BridgeCapability.COMMAND),
            userAuthorizedHighRisk = false,
        )
        assertFalse(policy.evaluate(BridgeCapability.COMMAND).allowed)
    }

    @Test
    fun trustedAndAuthorizedAllows() {
        val policy = BridgePolicy(
            trustLevel = TrustLevel.TRUSTED_DSH_WEBUI,
            grantedCapabilities = setOf(BridgeCapability.FILESYSTEM_READ),
            userAuthorizedHighRisk = true,
        )
        assertTrue(policy.evaluate(BridgeCapability.FILESYSTEM_READ).allowed)
    }
}
