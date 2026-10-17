package com.dshbox.browser.devtools

import org.junit.Assert.*
import org.junit.Test

class BrowserAxActionReferencesTest {
    private val identity = BrowserAxActionReferences.Identity("target", 4, "document", 2)
    private val candidate = BrowserAxActionCandidate("frame", 71)

    @Test fun referencesAreOpaqueAndExpireAtDeadline() {
        var time = 100L
        val store = BrowserAxActionReferences { time }
        val ref = store.replace(identity, mapOf("ax:frame:node" to candidate)).getValue("ax:frame:node")
        assertTrue(ref.startsWith("axa:"))
        assertFalse(ref.contains("frame"))
        assertEquals(candidate, store.resolve(ref, identity))
        assertNull(store.resolve("ax:frame:node", identity))
        time += BrowserAxActionReferences.TTL_MILLIS
        assertNull(store.resolve(ref, identity))
    }

    @Test fun eachIdentityDimensionRejectsOldReference() {
        val variants = listOf(identity.copy(targetId="other"), identity.copy(connectionEpoch=5),
            identity.copy(documentIdentity="other"), identity.copy(authorizationEpoch=3))
        variants.forEach { changed ->
            val store = BrowserAxActionReferences { 0L }
            val ref = store.replace(identity, mapOf("ax:frame:node" to candidate)).values.single()
            assertNull(store.resolve(ref, changed))
            assertNull(store.resolve(ref, identity))
        }
    }

    @Test fun newObservationAndExplicitRevocationInvalidateReferences() {
        val store = BrowserAxActionReferences { 0L }
        val old = store.replace(identity, mapOf("ax:frame:node" to candidate)).values.single()
        val fresh = store.replace(identity, mapOf("ax:frame:node" to candidate)).values.single()
        assertNotEquals(old, fresh)
        assertNull(store.resolve(old, identity))
        assertEquals(candidate, store.resolve(fresh, identity))
        store.clear()
        assertNull(store.resolve(fresh, identity))
    }
}
