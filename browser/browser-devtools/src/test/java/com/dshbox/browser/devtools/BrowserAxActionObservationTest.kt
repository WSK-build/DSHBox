package com.dshbox.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserAxActionObservationTest {
    private val identity = BrowserAxActionReferences.Identity("target", 4, "document", 2)
    private val candidate = BrowserAxActionCandidate("frame", 71)

    @Test fun observationFailurePreservesActualDeadlineAuthorizationAndIdentityCause() {
        assertEquals("DEADLINE_EXPIRED", axObservationFailure(true, false, false))
        assertEquals("NOT_AUTHORIZED", axObservationFailure(false, false, true))
        assertEquals("IDENTITY_CHANGED", axObservationFailure(false, true, false))
        assertNull(axObservationFailure(false, true, true))
    }

    @Test fun onlyRetainedPublicNodesReceiveOpaqueResolvableReferences() {
        val references = BrowserAxActionReferences { 100L }
        val tree = JSONObject().put("nodes", JSONArray().put(JSONObject().put("axRef", "ax:frame:kept")))
        val result = BrowserAxActionObservation.publish(observation(tree,
            mapOf("ax:frame:kept" to candidate, "ax:frame:removed" to candidate)), identity, references)
        assertEquals(tree.toString(), result.getJSONObject("tree").toString())
        val rows = result.getJSONArray("actionRefs")
        assertEquals(1, rows.length())
        val row = rows.getJSONObject(0)
        assertEquals("ax:frame:kept", row.getString("axRef"))
        assertEquals(candidate, references.resolve(row.getString("actionRef"), identity))
        assertEquals(30_000L, result.getLong("actionRefTtlMs"))
        assertFalse(result.toString().contains("backendNodeId"))
        assertFalse(result.toString().contains("authorizationEpoch"))
    }

    @Test fun byteBudgetReportsReferenceTruncationWithoutTruncatingTreeOrLeavingOldReferencesValid() {
        val nodes = JSONArray()
        val candidates = linkedMapOf<String, BrowserAxActionCandidate>()
        repeat(100) {
            val ref = "ax:frame:$it"
            nodes.put(JSONObject().put("axRef", ref))
            candidates[ref] = candidate
        }
        val tree = JSONObject().put("nodes", nodes).put("text", "界".repeat(3000))
        val references = BrowserAxActionReferences { 0L }
        val old = references.replace(identity, mapOf("ax:old" to candidate)).values.single()
        val result = BrowserAxActionObservation.publish(observation(tree, candidates), identity, references)
        assertTrue(result.getBoolean("actionRefsTruncated"))
        assertTrue(result.getJSONArray("actionRefs").length() in 1..99)
        assertEquals(tree.toString(), result.getJSONObject("tree").toString())
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size <= BrowserAxActionObservation.MAX_BYTES)
        assertNull(references.resolve(old, identity))
    }

    private fun observation(tree: JSONObject, candidates: Map<String, BrowserAxActionCandidate>) =
        BrowserAxTreeObservation("OK", tree, emptyMap(), 1, candidates)
}
