package com.dshbox.browser.runtime

import org.json.JSONObject
import java.util.Locale

internal data class BrowserNavigationThumbnailBudget(
    val waitUntilElapsedMillis: Long,
    val captureUntilElapsedMillis: Long,
)

internal enum class BrowserNavigationThumbnailReadiness {
    WAIT,
    READY,
    IDENTITY_CHANGED,
    NAVIGATION_FAILED,
    TIMEOUT,
}

/** Pure timing, readiness, scaling, and response policy for optional navigation previews. */
internal object BrowserNavigationThumbnailPolicy {
    const val MAX_EDGE_PIXELS = 384
    const val MAX_BYTES = 819_200L
    const val MAX_WAIT_MILLIS = 5_000L
    const val RETURN_RESERVE_MILLIS = 200L
    const val POLL_MILLIS = 100L

    fun budget(nowElapsedMillis: Long, requestDeadlineElapsedMillis: Long): BrowserNavigationThumbnailBudget? {
        val captureUntil = requestDeadlineElapsedMillis - RETURN_RESERVE_MILLIS
        if (captureUntil <= nowElapsedMillis) return null
        return BrowserNavigationThumbnailBudget(
            waitUntilElapsedMillis = minOf(nowElapsedMillis + MAX_WAIT_MILLIS, captureUntil),
            captureUntilElapsedMillis = captureUntil,
        )
    }

    fun readiness(
        budget: BrowserNavigationThumbnailBudget,
        nowElapsedMillis: Long,
        baselineNavigationId: Long?,
        baselineDocumentGeneration: Long,
        expectedNavigationId: Long,
        expectedRequestedUrl: String?,
        currentDocumentGeneration: Long,
        state: BrowserPageStateTracker.State?,
    ): BrowserNavigationThumbnailReadiness {
        if (state == null ||
            expectedNavigationId <= 0 ||
            expectedNavigationId == baselineNavigationId ||
            state.navigationId != expectedNavigationId ||
            state.requestedUrl != expectedRequestedUrl ||
            currentDocumentGeneration < baselineDocumentGeneration
        ) return BrowserNavigationThumbnailReadiness.IDENTITY_CHANGED
        if (state.loadState == BrowserPageStateTracker.LoadState.FAILED) {
            return BrowserNavigationThumbnailReadiness.NAVIGATION_FAILED
        }
        return if (state.committed && state.loadState == BrowserPageStateTracker.LoadState.FINISHED) {
            BrowserNavigationThumbnailReadiness.READY
        } else if (nowElapsedMillis >= budget.waitUntilElapsedMillis) {
            BrowserNavigationThumbnailReadiness.TIMEOUT
        } else {
            BrowserNavigationThumbnailReadiness.WAIT
        }
    }

    fun scaledSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE_PIXELS): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxEdge > 0)
        if (width <= maxEdge && height <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / maxOf(width, height)
        val scaledWidth = Math.max(1, Math.round(width * scale).toInt())
        val scaledHeight = Math.max(1, Math.round(height * scale).toInt())
        require(scaledWidth <= maxEdge && scaledHeight <= maxEdge)
        return scaledWidth to scaledHeight
    }

    fun normalizeCode(code: String?): String {
        val normalized = code?.trim()?.uppercase(Locale.ROOT).orEmpty()
        return when {
            normalized.isEmpty() -> "CAPTURE_FAILED"
            normalized == "DEADLINE_EXPIRED" -> "TIMEOUT"
            normalized.matches(Regex("^[A-Z][A-Z0-9_]{0,63}$")) -> normalized
            else -> "CAPTURE_FAILED"
        }
    }

    fun failureData(code: String?): JSONObject = JSONObject()
        .put("code", normalizeCode(code))
        .put("captured", false)

    fun successData(
        artifactPath: String,
        width: Int,
        height: Int,
        bytes: Long,
        pageId: String,
        documentGeneration: Long,
        navigationId: Long,
    ): JSONObject {
        require(artifactPath.startsWith("/opt/browser/out/shot/") && artifactPath.endsWith(".png"))
        require(width in 1..MAX_EDGE_PIXELS && height in 1..MAX_EDGE_PIXELS)
        require(bytes in 1..MAX_BYTES)
        require(pageId.isNotBlank() && documentGeneration >= 0 && navigationId >= 0)
        return JSONObject()
            .put("code", "OK")
            .put("captured", true)
            .put("artifactPath", artifactPath)
            .put("format", "png")
            .put("width", width)
            .put("height", height)
            .put("bytes", bytes)
            .put("pageId", pageId)
            .put("documentGeneration", documentGeneration)
            .put("navigationId", navigationId)
    }
}
