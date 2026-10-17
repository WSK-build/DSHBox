package com.dshbox.browser.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Validates scope diagnostics before native code publishes executable DOM references. */
internal object BrowserDomScopeMetadata {
    fun observationIndexValid(element: JSONObject, expected: Int): Boolean {
        val value = element.opt("index") as? Number ?: return false
        return expected in 0..79 && value.toDouble() == expected.toDouble()
    }

    fun nameFieldsValid(element: JSONObject): Boolean = listOf("accessibleName", "placeholder").all { key ->
        val value = element.opt(key)
        value is String && value.length <= 160
    }

    private val fields = setOf("complete", "scannedElements", "frameCount", "shadowRootCount",
        "inaccessibleFrameCount", "frameLimitReached", "shadowLimitReached", "elementLimitReached",
        "textNodesVisited", "textLimitReached")

    fun valid(data: JSONObject): Boolean {
        val coverage = data.optJSONObject("scopeCoverage") ?: return false
        val scopes = data.optJSONArray("frameScopes") ?: return false
        if (coverage.keys().asSequence().toSet() != fields) return false
        fun integer(obj: JSONObject, key: String, max: Int): Int? {
            val value = obj.opt(key)
            if (value !is Number || value.toDouble() != value.toInt().toDouble()) return null
            return value.toInt().takeIf { it in 0..max }
        }
        fun flag(key: String): Boolean? = coverage.opt(key) as? Boolean
        integer(coverage, "scannedElements", 5000) ?: return false
        integer(coverage, "shadowRootCount", 32) ?: return false
        integer(coverage, "textNodesVisited", 20000) ?: return false
        val count = integer(coverage, "frameCount", 16) ?: return false
        val inaccessible = integer(coverage, "inaccessibleFrameCount", 16) ?: return false
        val frameLimit = flag("frameLimitReached") ?: return false
        val shadowLimit = flag("shadowLimitReached") ?: return false
        val elementLimit = flag("elementLimitReached") ?: return false
        flag("textLimitReached") ?: return false
        if (flag("complete") != (inaccessible == 0 && !frameLimit && !shadowLimit && !elementLimit)) return false
        if (scopes.length() != count) return false
        var inaccessibleCount = 0
        for (index in 0 until count) {
            val frame = scopes.optJSONObject(index) ?: return false
            if (frame.keys().asSequence().toSet() != setOf("ordinal", "parentOrdinal", "depth", "status", "reason")) return false
            if (integer(frame, "ordinal", 16) != index + 1) return false
            val parent = integer(frame, "parentOrdinal", index) ?: return false
            if (parent != 0 && scopes.getJSONObject(parent - 1).optString("status") != "accessible") return false
            val depth = integer(frame, "depth", 4) ?: return false
            val parentDepth = if (parent == 0) 0 else scopes.getJSONObject(parent - 1).getInt("depth")
            if (depth != parentDepth + 1) return false
            when (frame.opt("status")) {
                "accessible" -> if (frame.opt("reason") !== JSONObject.NULL) return false
                "inaccessible" -> {
                    inaccessibleCount++
                    when (frame.opt("reason")) {
                        "CROSS_ORIGIN_OR_OPAQUE" -> Unit
                        "FRAME_LIMIT" -> if (!frameLimit) return false
                        else -> return false
                    }
                }
                else -> return false
            }
        }
        return inaccessibleCount == inaccessible
    }

    fun targetValid(target: JSONObject, scopes: JSONArray): Boolean {
        val ordinal = target.opt("frameOrdinal") as? Number ?: return false
        val depth = target.opt("shadowDepth") as? Number ?: return false
        val frame = ordinal.toInt()
        val shadow = depth.toInt()
        if (ordinal.toDouble() != frame.toDouble() || frame !in 0..scopes.length()) return false
        if (depth.toDouble() != shadow.toDouble() || shadow !in 0..32) return false
        return frame == 0 || scopes.getJSONObject(frame - 1).optString("status") == "accessible"
    }

    fun copy(source: JSONObject, destination: JSONObject) {
        destination.put("scopeCoverage", source.getJSONObject("scopeCoverage"))
            .put("frameScopes", source.getJSONArray("frameScopes"))
    }
}
