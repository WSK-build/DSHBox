package com.dshbox.browser.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserOverlayMetadataTest {
    @Test fun actualRendererProductionOutputPublishesWithoutDowngradingToAnError() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/browser-overlay-production-fixture.json"))
        val data = JSONObject(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        val elements = data.getJSONArray("elements")
        for (index in 0 until elements.length()) {
            val element = elements.getJSONObject(index)
            assertEquals(index, element.getInt("index"))
            element.put("nodeId", nodeId(index)).remove("index")
        }
        BrowserOverlayMetadata.attach(data, elements)
        assertFalse(data.has("overlayObservation"))
        assertEquals(0, data.getJSONObject("overlayCoverage").getJSONArray("errors").length())
        assertTrue(data.getJSONArray("overlays").length() > 0)
        assertTrue(publishedDismissCount(data) > 0)
        val close = (0 until elements.length()).map { elements.getJSONObject(it) }
            .single { it.optString("accessibleName") == "Close dialog" }.getString("nodeId")
        val overlays = data.getJSONArray("overlays")
        assertTrue((0 until overlays.length()).any { index ->
            val candidates = overlays.getJSONObject(index).getJSONObject("dismiss").getJSONArray("candidates")
            (0 until candidates.length()).any { candidates.getJSONObject(it).getString("nodeId") == close }
        })
        assertFalse(data.toString().contains("refIndex"))
    }

    @Test fun publishesCompleteProductionObservationAndRewritesIndexes() {
        val elements = elements(1)
        val data = JSONObject().put("title", "Retained").put("elements", elements)
            .put("overlayObservation", observation())

        BrowserOverlayMetadata.attach(data, elements)

        assertEquals("Retained", data.getString("title"))
        assertEquals(elements.toString(), data.getJSONArray("elements").toString())
        assertFalse(data.has("overlayObservation"))
        assertEquals(13, data.getJSONObject("overlayCoverage").keys().asSequence().count())
        assertEquals(1, data.getJSONArray("overlays").length())
        assertFalse(data.has("action"))
        val overlay = data.getJSONArray("overlays").getJSONObject(0)
        assertEquals(11, overlay.keys().asSequence().count())
        val candidate = overlay.getJSONObject("dismiss").getJSONArray("candidates").getJSONObject(0)
        assertEquals(5, candidate.keys().asSequence().count())
        assertEquals(nodeId(0), candidate.getString("nodeId"))
        assertFalse(data.toString().contains("refIndex"))
    }

    @Test fun malformedEnvelopeScopeCoverageAndOverlayRowsFailWholeObservation() {
        val mutations = listOf<(JSONObject) -> Unit>(
            { raw -> raw.put("unsupported", true) },
            { raw -> raw.getJSONObject("scope").put("complete", true) },
            { raw -> raw.getJSONObject("scope").put("extra", "value") },
            { raw -> raw.getJSONObject("action").put("performed", true) },
            { raw -> raw.getJSONObject("action").put("extra", false) },
            { raw -> raw.getJSONObject("coverage").put("candidateScan", "256") },
            { raw -> raw.getJSONObject("coverage").put("partial", true) },
            { raw -> raw.getJSONObject("coverage").put("errors", JSONArray().put("raw_ref")) },
            { raw -> raw.getJSONObject("coverage").put("viewportSampleLimit", 26) },
            { raw -> raw.getJSONArray("overlays").getJSONObject(0).put("unsupported", false) },
            { raw -> raw.getJSONArray("overlays").getJSONObject(0).getJSONObject("box").put("width", 0) },
            { raw -> raw.getJSONArray("overlays").getJSONObject(0).put("viewportCoverageRatio", 1.01) },
            { raw -> raw.getJSONArray("overlays").getJSONObject(0).getJSONObject("evidence").remove("zIndex") },
            { raw -> raw.getJSONArray("overlays").getJSONObject(0).getJSONArray("pseudo")
                .getJSONObject(0).put("source", "side") },
            { raw -> raw.getJSONArray("overlays").getJSONObject(0).getJSONObject("dismiss")
                .put("status", "none") }
        )

        for (mutate in mutations) {
            val elements = elements(1)
            val observation = observation()
            mutate(observation)
            val data = JSONObject().put("title", "Retained").put("elements", elements)
                .put("overlayObservation", observation)
            BrowserOverlayMetadata.attach(data, elements)
            assertFixedFailure(data, elements)
        }
    }

    @Test fun candidateAndSamplingBudgetsAcceptExactBoundariesAndRejectOverflow() {
        val candidateElements = elements(7)
        val candidateBoundary = observation(
            dismissPerOverlay = listOf(1, 1, 1, 1, 1, 1, 1),
            sampledPerOverlay = listOf(3, 3, 3, 3, 3, 3, 0),
            coverageSampleCount = 25
        )
        val candidateData = JSONObject().put("elements", candidateElements)
            .put("overlayObservation", candidateBoundary)
        BrowserOverlayMetadata.attach(candidateData, candidateElements)
        assertEquals(7, publishedDismissCount(candidateData))

        val sampleElements = elements(2)
        val sampleBoundary = observation(
            dismissPerOverlay = listOf(0, 0),
            sampledPerOverlay = listOf(13, 12),
            coverageSampleCount = 25
        )
        val sampleData = JSONObject().put("elements", sampleElements)
            .put("overlayObservation", sampleBoundary)
        BrowserOverlayMetadata.attach(sampleData, sampleElements)
        assertEquals(25, publishedSampleCount(sampleData))

        val tooManyCandidates = observation(
            dismissPerOverlay = listOf(1, 1, 1, 1, 1, 1, 1, 1),
            sampledPerOverlay = listOf(1, 1, 0, 0, 0, 0, 0, 0),
            coverageSampleCount = 2
        )
        val overflowElements = elements(8)
        val overflowData = JSONObject().put("elements", overflowElements)
            .put("overlayObservation", tooManyCandidates)
        BrowserOverlayMetadata.attach(overflowData, overflowElements)
        assertFixedFailure(overflowData, overflowElements)

        val tooManySamples = observation(
            dismissPerOverlay = listOf(0, 0),
            sampledPerOverlay = listOf(13, 13),
            coverageSampleCount = 25
        )
        val sampleOverflowElements = elements(2)
        val sampleOverflowData = JSONObject().put("elements", sampleOverflowElements)
            .put("overlayObservation", tooManySamples)
        BrowserOverlayMetadata.attach(sampleOverflowData, sampleOverflowElements)
        assertFixedFailure(sampleOverflowData, sampleOverflowElements)
    }

    @Test fun coverageUpperBoundariesRemainValidOnlyWithConsistentCounts() {
        val elements = elements(1)
        val observation = observation()
        observation.getJSONObject("coverage")
            .put("candidateScan", 256)
            .put("candidateLimitReached", true)
            .put("viewportSampleCount", 25)
            .put("textNodesVisited", 512)
            .put("textChars", 8192)
            .put("textLimitReached", true)
        val data = JSONObject().put("elements", elements).put("overlayObservation", observation)
        BrowserOverlayMetadata.attach(data, elements)
        assertFalse(data.getJSONObject("overlayCoverage").getBoolean("partial"))

        val inconsistent = observation()
        inconsistent.getJSONObject("coverage")
            .put("candidateScan", 256)
            .put("candidateLimitReached", false)
        val inconsistentElements = elements(1)
        val inconsistentData = JSONObject().put("elements", inconsistentElements)
            .put("overlayObservation", inconsistent)
        BrowserOverlayMetadata.attach(inconsistentData, inconsistentElements)
        assertFixedFailure(inconsistentData, inconsistentElements)
    }

    @Test fun malformedRegistryReferencesFailInsteadOfBecomingSilentNone() {
        val elementCases = listOf(
            JSONObject().put("nodeId", nodeId(0)).put("frameOrdinal", 1),
            JSONObject().put("nodeId", 123).put("frameOrdinal", 0),
            JSONObject().put("nodeId", "123").put("frameOrdinal", 0),
            JSONObject().put("nodeId", nodeId(0)).put("frameOrdinal", "0"),
            JSONObject().put("nodeId", nodeId(0)).put("frameOrdinal", 0.0)
        )
        for (element in elementCases) {
            val elements = JSONArray().put(element)
            val data = JSONObject().put("elements", elements)
                .put("overlayObservation", observation())
            BrowserOverlayMetadata.attach(data, elements)
            assertFixedFailure(data, elements)
        }

        val stringIndexElements = elements(1)
        val stringIndexObservation = observation()
        stringIndexObservation.getJSONArray("overlays").getJSONObject(0)
            .getJSONObject("dismiss").getJSONArray("candidates").getJSONObject(0)
            .put("refIndex", "0")
        val stringIndexData = JSONObject().put("elements", stringIndexElements)
            .put("overlayObservation", stringIndexObservation)
        BrowserOverlayMetadata.attach(stringIndexData, stringIndexElements)
        assertFixedFailure(stringIndexData, stringIndexElements)

        val missingRegistryElements = elements(1)
        val missingRegistryData = JSONObject().put("overlayObservation", observation())
        BrowserOverlayMetadata.attach(missingRegistryData, missingRegistryElements)
        assertFixedFailure(missingRegistryData, null)

        val passedElements = elements(1)
        val desyncedElements = JSONArray().put(JSONObject()
            .put("nodeId", nodeId(1)).put("frameOrdinal", 0))
        val desyncedData = JSONObject().put("elements", desyncedElements)
            .put("overlayObservation", observation())
        BrowserOverlayMetadata.attach(desyncedData, passedElements)
        assertFixedFailure(desyncedData, desyncedElements)
    }

    @Test fun observationIndexesMustMatchThePrivateRegistryOrder() {
        assertTrue(BrowserDomScopeMetadata.observationIndexValid(JSONObject().put("index", 2), 2))
        assertFalse(BrowserDomScopeMetadata.observationIndexValid(JSONObject(), 0))
        assertFalse(BrowserDomScopeMetadata.observationIndexValid(JSONObject().put("index", "0"), 0))
        assertFalse(BrowserDomScopeMetadata.observationIndexValid(JSONObject().put("index", 0), 1))
        assertFalse(BrowserDomScopeMetadata.observationIndexValid(JSONObject().put("index", 0.5), 0))
    }

    @Test fun publishesOnlyCurrentTopFrameReferencesAndPrunesAfterTruncation() {
        val elements = elements(1)
        val data = JSONObject().put("elements", elements).put("overlayObservation", observation())
        BrowserOverlayMetadata.attach(data, elements)
        val dismiss = data.getJSONArray("overlays").getJSONObject(0).getJSONObject("dismiss")
        assertEquals(nodeId(0), dismiss.getJSONArray("candidates").getJSONObject(0).getString("nodeId"))
        assertFalse(data.toString().contains("refIndex"))

        elements.remove(0)
        BrowserOverlayMetadata.prune(data, elements)
        assertEquals("none", dismiss.getString("status"))
        assertEquals(0, dismiss.getJSONArray("candidates").length())
    }

    @Test fun missingAndCrossFrameIndexesNeverPublishAnActionReference() {
        val crossFrameElements = JSONArray().put(JSONObject()
            .put("nodeId", nodeId(0)).put("frameOrdinal", 1))
        val crossFrameData = JSONObject().put("elements", crossFrameElements)
            .put("overlayObservation", observation())
        BrowserOverlayMetadata.attach(crossFrameData, crossFrameElements)
        assertFixedFailure(crossFrameData, crossFrameElements)

        val shortRegistry = elements(1)
        val missingIndexObservation = observation()
        missingIndexObservation.getJSONArray("overlays").getJSONObject(0)
            .getJSONObject("dismiss").getJSONArray("candidates").getJSONObject(0)
            .put("refIndex", 99)
        val missingIndexData = JSONObject().put("elements", shortRegistry)
            .put("overlayObservation", missingIndexObservation)
        BrowserOverlayMetadata.attach(missingIndexData, shortRegistry)
        assertFixedFailure(missingIndexData, shortRegistry)
    }

    @Test fun helperFailureReturnsExplicitPartialWithoutRemovingDomElements() {
        val elements = elements(1)
        val data = JSONObject().put("title", "Retained").put("elements", elements)
            .put("overlayObservation", JSONObject.NULL)
        BrowserOverlayMetadata.attach(data, elements)
        assertFixedFailure(data, elements)
    }

    private fun assertFixedFailure(data: JSONObject, elements: JSONArray?) {
        assertFalse(data.has("overlayObservation"))
        assertEquals("Retained", data.optString("title", "Retained"))
        if (elements != null) assertEquals(elements.toString(), data.getJSONArray("elements").toString())
        assertEquals(0, data.getJSONArray("overlays").length())
        val scope = data.getJSONObject("overlayScope")
        assertEquals(4, scope.keys().asSequence().count())
        assertTrue(scope.getBoolean("topFrameOnly"))
        assertFalse(scope.getBoolean("includesCrossOriginFrames"))
        assertEquals("not_inspected", scope.getString("frameCoverage"))
        assertFalse(scope.getBoolean("complete"))
        val coverage = data.getJSONObject("overlayCoverage")
        assertEquals(13, coverage.keys().asSequence().count())
        assertTrue(coverage.getBoolean("partial"))
        assertEquals(0, coverage.getInt("candidateScan"))
        assertEquals(256, coverage.getInt("candidateScanLimit"))
        assertEquals(0, coverage.getInt("viewportSampleCount"))
        assertEquals(25, coverage.getInt("viewportSampleLimit"))
        assertEquals(0, coverage.getInt("overlayOutputCount"))
        assertEquals(8, coverage.getInt("overlayOutputLimit"))
        assertEquals(1, coverage.getJSONArray("errors").length())
        assertEquals("bounded_observation_error", coverage.getJSONArray("errors").getString(0))
        assertFalse(data.toString().contains("refIndex"))
    }

    private fun publishedDismissCount(data: JSONObject): Int {
        var total = 0
        val overlays = data.getJSONArray("overlays")
        for (index in 0 until overlays.length()) {
            total += overlays.getJSONObject(index).getJSONObject("dismiss")
                .getJSONArray("candidates").length()
        }
        return total
    }

    private fun publishedSampleCount(data: JSONObject): Int {
        var total = 0
        val overlays = data.getJSONArray("overlays")
        for (index in 0 until overlays.length()) {
            total += overlays.getJSONObject(index).getJSONObject("occlusion").getInt("sampled")
        }
        return total
    }

    private fun elements(count: Int): JSONArray {
        val result = JSONArray()
        for (index in 0 until count) {
            result.put(JSONObject().put("nodeId", nodeId(index)).put("frameOrdinal", 0))
        }
        return result
    }

    private fun nodeId(index: Int): String =
        "b:4:9:3:7:0123456789abcdef0123456789abcdef:$index"

    private fun observation(
        dismissPerOverlay: List<Int> = listOf(1),
        sampledPerOverlay: List<Int> = dismissPerOverlay.map { 1 },
        coverageSampleCount: Int = sampledPerOverlay.sum() + dismissPerOverlay.sum()
    ): JSONObject {
        require(dismissPerOverlay.size == sampledPerOverlay.size)
        val overlays = JSONArray()
        var nextReference = 0
        for (index in dismissPerOverlay.indices) {
            val candidates = JSONArray()
            repeat(dismissPerOverlay[index]) {
                candidates.put(candidate(nextReference++))
            }
            overlays.put(overlay(candidates, sampledPerOverlay[index]))
        }
        val coverage = JSONObject()
            .put("partial", false)
            .put("candidateScan", overlays.length())
            .put("candidateScanLimit", 256)
            .put("candidateLimitReached", false)
            .put("viewportSampleCount", coverageSampleCount)
            .put("viewportSampleLimit", 25)
            .put("overlayOutputCount", overlays.length())
            .put("overlayOutputLimit", 8)
            .put("outputTruncated", false)
            .put("textNodesVisited", 0)
            .put("textChars", 0)
            .put("textLimitReached", false)
            .put("errors", JSONArray())
        return JSONObject()
            .put("overlays", overlays)
            .put("action", JSONObject()
                .put("performed", false)
                .put("automaticDismiss", false)
                .put("captchaSolved", false))
            .put("scope", JSONObject()
                .put("topFrameOnly", true)
                .put("includesCrossOriginFrames", false)
                .put("frameCoverage", "not_inspected")
                .put("complete", false))
            .put("coverage", coverage)
    }

    private fun overlay(candidates: JSONArray, sampled: Int): JSONObject {
        val dismiss = JSONObject()
            .put("status", if (candidates.length() == 0) "none" else "available")
            .put("candidates", candidates)
        val covered = if (sampled == 0) 0 else sampled
        val occlusion = JSONObject()
            .put("sampled", sampled)
            .put("covered", covered)
            .put("ratio", if (sampled == 0) JSONObject.NULL else 1.0)
        val box = JSONObject().put("x", 0.0).put("y", 0.0).put("width", 100.0).put("height", 100.0)
        return JSONObject()
            .put("kind", "modal")
            .put("tag", "div")
            .put("role", "dialog")
            .put("name", "Dialog")
            .put("box", box)
            .put("viewportBox", box)
            .put("viewportCoverageRatio", 0.5)
            .put("evidence", JSONObject()
                .put("fixed", true)
                .put("sticky", false)
                .put("dialog", false)
                .put("modal", true)
                .put("zIndex", 100)
                .put("semanticHint", "modal"))
            .put("occlusion", occlusion)
            .put("pseudo", JSONArray().put(JSONObject()
                .put("source", "before")
                .put("boundedContent", "Prompt")))
            .put("dismiss", dismiss)
    }

    private fun candidate(refIndex: Int): JSONObject = JSONObject()
        .put("refIndex", refIndex)
        .put("tag", "button")
        .put("role", "button")
        .put("name", "Close")
        .put("box", JSONObject().put("x", 80.0).put("y", 8.0).put("width", 16.0).put("height", 16.0))
}
