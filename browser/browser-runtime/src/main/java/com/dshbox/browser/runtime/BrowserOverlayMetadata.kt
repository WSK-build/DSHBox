package com.dshbox.browser.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Validates bounded overlay observations before publishing executable DOM references. */
internal object BrowserOverlayMetadata {
    private const val MAX_OBSERVATION_BYTES = 24_576
    private const val MAX_PUBLISHED_OVERLAY_BYTES = 8_192
    private const val OBSERVATION_ERROR = "bounded_observation_error"

    fun attach(data: JSONObject, elements: JSONArray) {
        val raw = data.optJSONObject("overlayObservation")
        data.remove("overlayObservation")
        if (raw == null ||
            raw.toString().toByteArray(Charsets.UTF_8).size > MAX_OBSERVATION_BYTES ||
            !exact(raw, setOf("overlays", "scope", "coverage", "action"))
        ) {
            return failed(data)
        }

        val overlays = raw.optJSONArray("overlays") ?: return failed(data)
        val scope = raw.optJSONObject("scope") ?: return failed(data)
        val coverage = raw.optJSONObject("coverage") ?: return failed(data)
        val action = raw.optJSONObject("action") ?: return failed(data)
        if (overlays.length() <= 8 &&
            exact(scope, setOf("topFrameOnly", "includesCrossOriginFrames", "frameCoverage", "complete")) &&
            scope.opt("topFrameOnly") == true &&
            scope.opt("includesCrossOriginFrames") == false &&
            scope.opt("frameCoverage") == "not_inspected" &&
            scope.opt("complete") == false &&
            validCoverage(coverage, overlays.length()) &&
            validAction(action)
        ) {
            // Validation is completed before any observation-local index is published.
            val publications = mutableListOf<Pair<JSONObject, JSONArray>>()
            var sampledTotal = 0
            var dismissTotal = 0
            for (index in 0 until overlays.length()) {
                val overlay = overlays.optJSONObject(index) ?: return failed(data)
                if (!validOverlay(overlay)) return failed(data)
                val occlusion = overlay.getJSONObject("occlusion")
                val dismiss = overlay.getJSONObject("dismiss")
                val candidates = dismiss.getJSONArray("candidates")
                sampledTotal += occlusion.getInt("sampled")
                dismissTotal += candidates.length()
                if (sampledTotal > coverage.getInt("viewportSampleCount") || dismissTotal > 7) {
                    return failed(data)
                }

                val published = JSONArray()
                for (candidateIndex in 0 until candidates.length()) {
                    val candidate = candidates.optJSONObject(candidateIndex) ?: return failed(data)
                    if (!exact(candidate, setOf("refIndex", "tag", "role", "name", "box")) ||
                        !boundedString(candidate.opt("tag"), 32) ||
                        !boundedString(candidate.opt("role"), 48) ||
                        !boundedString(candidate.opt("name"), 160) ||
                        !validBox(candidate.opt("box"))
                    ) {
                        return failed(data)
                    }
                    val refIndex = boundedInteger(candidate.opt("refIndex"), 79) ?: return failed(data)
                    val nodeId = currentFrameNodeId(elements, refIndex) ?: return failed(data)
                    val registry = data.optJSONArray("elements") ?: return failed(data)
                    if (!containsNodeId(registry, nodeId)) return failed(data)
                    published.put(JSONObject()
                        .put("nodeId", nodeId)
                        .put("tag", candidate.getString("tag"))
                        .put("role", candidate.getString("role"))
                        .put("name", candidate.getString("name"))
                        .put("box", candidate.getJSONObject("box")))
                }
                publications.add(dismiss to published)
            }

            for ((dismiss, published) in publications) {
                dismiss.put("candidates", published)
                    .put("status", if (published.length() == 0) "none" else "available")
            }
            coverage.put("overlayOutputCount", overlays.length())
            data.put("overlays", overlays)
                .put("overlayScope", scope)
                .put("overlayCoverage", coverage)
            while (overlays.length() > 0 &&
                overlays.toString().toByteArray(Charsets.UTF_8).size > MAX_PUBLISHED_OVERLAY_BYTES
            ) {
                overlays.remove(overlays.length() - 1)
                coverage.put("overlayOutputCount", overlays.length()).put("outputTruncated", true)
            }
            prune(data, elements)
            return
        }
        failed(data)
    }

    private fun validCoverage(coverage: JSONObject, overlayCount: Int): Boolean {
        val fields = setOf(
            "partial", "candidateScan", "candidateScanLimit", "candidateLimitReached",
            "viewportSampleCount", "viewportSampleLimit", "overlayOutputCount", "overlayOutputLimit",
            "outputTruncated", "textNodesVisited", "textChars", "textLimitReached", "errors"
        )
        if (!exact(coverage, fields)) return false
        val partial = coverage.opt("partial") as? Boolean ?: return false
        val candidateLimitReached = coverage.opt("candidateLimitReached") as? Boolean ?: return false
        val outputTruncated = coverage.opt("outputTruncated") as? Boolean ?: return false
        val textLimitReached = coverage.opt("textLimitReached") as? Boolean ?: return false
        val candidateScan = boundedInteger(coverage.opt("candidateScan"), 256) ?: return false
        val viewportSampleCount = boundedInteger(coverage.opt("viewportSampleCount"), 25) ?: return false
        val overlayOutputCount = boundedInteger(coverage.opt("overlayOutputCount"), 8) ?: return false
        val textNodesVisited = boundedInteger(coverage.opt("textNodesVisited"), 512) ?: return false
        val textChars = boundedInteger(coverage.opt("textChars"), 8_192) ?: return false
        val errors = coverage.optJSONArray("errors") ?: return false
        val errorsValid = (0 until errors.length()).all { errors.opt(it) == OBSERVATION_ERROR }
        if (coverage.opt("candidateScanLimit") != 256 ||
            coverage.opt("viewportSampleLimit") != 25 ||
            coverage.opt("overlayOutputLimit") != 8 ||
            overlayOutputCount != overlayCount ||
            candidateLimitReached != (candidateScan == 256) ||
            outputTruncated && overlayCount == 0 ||
            errors.length() > 4 ||
            !errorsValid ||
            partial != (errors.length() > 0)
        ) {
            return false
        }
        return true
    }

    private fun validOverlay(overlay: JSONObject): Boolean {
        val fields = setOf(
            "kind", "tag", "role", "name", "box", "viewportBox", "viewportCoverageRatio",
            "evidence", "occlusion", "pseudo", "dismiss"
        )
        if (!exact(overlay, fields) ||
            overlay.optString("kind") !in setOf("dialog", "modal", "fixed", "sticky", "layer") ||
            !boundedString(overlay.opt("tag"), 32) ||
            !boundedString(overlay.opt("role"), 48) ||
            !boundedString(overlay.opt("name"), 160)
        ) {
            return false
        }
        if (!validBox(overlay.opt("box")) || !validBox(overlay.opt("viewportBox"))) return false
        val ratio = overlay.opt("viewportCoverageRatio") as? Number ?: return false
        if (!finiteNumber(ratio, 1_000_000_000.0) || ratio.toDouble() !in 0.0..1.0) return false
        if (!validEvidence(overlay.opt("evidence")) ||
            !validOcclusion(overlay.opt("occlusion")) ||
            !validPseudo(overlay.opt("pseudo")) ||
            !validDismiss(overlay.opt("dismiss"))
        ) {
            return false
        }
        return true
    }

    private fun validEvidence(value: Any?): Boolean {
        if (value !is JSONObject ||
            !exact(value, setOf("fixed", "sticky", "dialog", "modal", "zIndex", "semanticHint"))
        ) {
            return false
        }
        if (listOf("fixed", "sticky", "dialog", "modal").any { value.opt(it) !is Boolean }) return false
        val zIndex = value.opt("zIndex")
        if (zIndex !== JSONObject.NULL && !finiteNumber(zIndex, 1_000_000_000.0)) return false
        return boundedString(value.opt("semanticHint"), 160)
    }

    private fun validOcclusion(value: Any?): Boolean {
        if (value !is JSONObject ||
            !exact(value, setOf("sampled", "covered", "ratio"))
        ) {
            return false
        }
        val sampled = boundedInteger(value.opt("sampled"), 25) ?: return false
        val covered = boundedInteger(value.opt("covered"), sampled) ?: return false
        val ratio = value.opt("ratio")
        return if (sampled == 0) {
            ratio === JSONObject.NULL
        } else {
            val numericRatio = ratio as? Number ?: return false
            finiteNumber(numericRatio, 1_000_000_000.0) &&
                Math.abs(numericRatio.toDouble() - covered.toDouble() / sampled) <= 0.000051
        }
    }

    private fun validPseudo(value: Any?): Boolean {
        if (value !is JSONArray || value.length() > 2) return false
        for (index in 0 until value.length()) {
            val item = value.optJSONObject(index) ?: return false
            if (!exact(item, setOf("source", "boundedContent")) ||
                item.opt("source") !in setOf("before", "after") ||
                !boundedString(item.opt("boundedContent"), 160)
            ) {
                return false
            }
        }
        return true
    }

    private fun validDismiss(value: Any?): Boolean {
        if (value !is JSONObject ||
            !exact(value, setOf("status", "candidates"))
        ) {
            return false
        }
        val candidates = value.optJSONArray("candidates") ?: return false
        if (candidates.length() > 4) return false
        val expectedStatus = if (candidates.length() == 0) "none" else "available"
        return value.opt("status") == expectedStatus
    }

    private fun validBox(value: Any?): Boolean {
        if (value !is JSONObject || !exact(value, setOf("x", "y", "width", "height"))) return false
        if (!listOf("x", "y", "width", "height").all {
                finiteNumber(value.opt(it), 1_000_000_000.0)
            }
        ) {
            return false
        }
        return value.getDouble("width") > 0.0 && value.getDouble("height") > 0.0
    }

    private fun currentFrameNodeId(elements: JSONArray, refIndex: Int): String? {
        if (refIndex !in 0 until elements.length()) return null
        val element = elements.optJSONObject(refIndex) ?: return null
        val frameOrdinal = boundedInteger(element.opt("frameOrdinal"), 16) ?: return null
        if (frameOrdinal != 0) return null
        val nodeId = element.opt("nodeId")
        if (nodeId !is String || !observationReference(nodeId, refIndex)) return null
        return nodeId
    }

    private fun observationReference(value: String, refIndex: Int): Boolean {
        val parts = value.split(":")
        if (parts.size != 7 || parts[0] != "b" || parts[5].isEmpty()) return false
        if (parts.drop(1).take(4).any { it.toLongOrNull() == null }) return false
        return parts[6].toIntOrNull() == refIndex
    }

    private fun validAction(action: JSONObject): Boolean =
        exact(action, setOf("performed", "automaticDismiss", "captchaSolved")) &&
            action.opt("performed") == false &&
            action.opt("automaticDismiss") == false &&
            action.opt("captchaSolved") == false

    private fun containsNodeId(elements: JSONArray, nodeId: String): Boolean {
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            if (boundedInteger(element.opt("frameOrdinal"), 16) == 0 &&
                element.opt("nodeId") == nodeId
            ) {
                return true
            }
        }
        return false
    }

    private fun exact(value: JSONObject, fields: Set<String>): Boolean =
        value.keys().asSequence().toSet() == fields

    private fun boundedInteger(value: Any?, max: Int): Int? {
        val long = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        return if (long in 0..max.toLong()) long.toInt() else null
    }

    private fun boundedString(value: Any?, max: Int): Boolean = value is String && value.length <= max

    private fun finiteNumber(value: Any?, maxMagnitude: Double): Boolean =
        value is Number && value.toDouble().isFinite() && Math.abs(value.toDouble()) <= maxMagnitude

    private fun failed(data: JSONObject) {
        data.put("overlays", JSONArray()).put("overlayScope", JSONObject()
            .put("topFrameOnly", true).put("includesCrossOriginFrames", false)
            .put("frameCoverage", "not_inspected").put("complete", false))
            .put("overlayCoverage", JSONObject().put("partial", true)
                .put("candidateScan", 0).put("candidateScanLimit", 256).put("candidateLimitReached", false)
                .put("viewportSampleCount", 0).put("viewportSampleLimit", 25)
                .put("overlayOutputCount", 0).put("overlayOutputLimit", 8).put("outputTruncated", false)
                .put("textNodesVisited", 0).put("textChars", 0).put("textLimitReached", false)
                .put("errors", JSONArray().put(OBSERVATION_ERROR)))
    }

    fun prune(data: JSONObject, elements: JSONArray) {
        val refs = (0 until elements.length()).mapNotNull { index ->
            val element = elements.optJSONObject(index)
            val ordinal = element?.opt("frameOrdinal") as? Number
            (element?.opt("nodeId") as? String)?.takeIf { it.isNotBlank() && ordinal?.toDouble() == 0.0 }
        }.toSet()
        val overlays = data.optJSONArray("overlays") ?: return
        for (index in 0 until overlays.length()) {
            val dismiss = overlays.optJSONObject(index)?.optJSONObject("dismiss") ?: continue
            val candidates = dismiss.optJSONArray("candidates") ?: continue
            val retained = JSONArray()
            for (candidateIndex in 0 until candidates.length()) {
                val candidate = candidates.optJSONObject(candidateIndex) ?: continue
                if (candidate.optString("nodeId") in refs) retained.put(candidate)
            }
            dismiss.put("candidates", retained).put("status", if (retained.length() == 0) "none" else "available")
        }
    }
}
