package com.dshbox.browser.devtools

import org.json.JSONArray
import org.json.JSONObject

/** Publishes bounded opaque action references without changing the read-only AX tree contract. */
internal object BrowserAxActionObservation {
    const val MAX_BYTES = 14 * 1024

    fun publish(observation: BrowserAxTreeObservation, identity: BrowserAxActionReferences.Identity,
        references: BrowserAxActionReferences): JSONObject {
        references.clear()
        require(observation.code == "OK")
        val tree = JSONObject(observation.data.toString())
        val nodes = requireNotNull(tree.optJSONArray("nodes"))
        val publicRefs = (0 until nodes.length()).mapNotNull {
            nodes.optJSONObject(it)?.opt("axRef") as? String
        }.toSet()
        val base = JSONObject().put("tree", tree).put("actionRefs", JSONArray())
            .put("actionRefTtlMs", BrowserAxActionReferences.TTL_MILLIS).put("actionRefsTruncated", false)
        var used = base.toString().toByteArray(Charsets.UTF_8).size
        require(used <= MAX_BYTES)
        val retained = linkedMapOf<String, BrowserAxActionCandidate>()
        val eligible = observation.actionCandidates.filterKeys { it in publicRefs }
        for ((ref, candidate) in eligible) {
            val row = JSONObject().put("axRef", ref)
                .put("actionRef", "axa:ffffffff-ffff-ffff-ffff-ffffffffffff:255")
            val bytes = row.toString().toByteArray(Charsets.UTF_8).size + if (retained.isEmpty()) 0 else 1
            if (used + bytes > MAX_BYTES || retained.size >= 256) break
            used += bytes
            retained[ref] = candidate
        }
        val published = references.replace(identity, retained)
        val rows = JSONArray()
        published.forEach { (axRef, actionRef) -> rows.put(JSONObject().put("axRef", axRef).put("actionRef", actionRef)) }
        base.put("actionRefs", rows).put("actionRefsTruncated", published.size < eligible.size)
        if (base.toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            references.clear()
            error("AX_OUTPUT_BUDGET_EXCEEDED")
        }
        return base
    }
}
