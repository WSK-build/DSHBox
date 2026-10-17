package com.dshbox.browser.runtime

import java.security.SecureRandom
import java.util.LinkedHashMap

/** Provenance for one screenshot, including the exact bitmap-to-WebView viewport mapping. */
internal data class BrowserVisualIdentity(
    val pageId: String,
    val documentGeneration: Long,
    val documentToken: String,
    val navigationId: Long,
    val sessionGeneration: Long,
    val controlGeneration: Long,
    val viewInstanceGeneration: Long,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val webViewWidth: Int,
    val webViewHeight: Int,
    val scrollX: Int,
    val scrollY: Int,
    val scale: Float,
    val density: Float,
    val rotation: Int,
    val viewportGeneration: Long,
    val mutationGeneration: Long,
    val visualViewportWidth: Double,
    val visualViewportHeight: Double,
    val visualViewportOffsetX: Double,
    val visualViewportOffsetY: Double,
    val devicePixelRatio: Double,
    val visualViewportScale: Double,
    val animationsActive: Boolean,
) {
    fun isValid(): Boolean = pageId.isNotBlank() && pageId.length <= 128 &&
        documentGeneration >= 0 && documentToken.matches(Regex("[a-f0-9]{32}")) && navigationId >= 0 &&
        sessionGeneration > 0 && controlGeneration > 0 &&
        viewInstanceGeneration > 0 && bitmapWidth > 0 && bitmapHeight > 0 &&
        webViewWidth > 0 && webViewHeight > 0 && scrollX >= 0 && scrollY >= 0 &&
        scale.isFinite() && scale > 0f && density.isFinite() && density > 0f &&
        rotation in setOf(0, 90, 180, 270) && viewportGeneration >= 0 && mutationGeneration >= 0 &&
        visualViewportWidth.isFinite() && visualViewportWidth > 0.0 &&
        visualViewportHeight.isFinite() && visualViewportHeight > 0.0 &&
        visualViewportOffsetX.isFinite() && visualViewportOffsetX >= 0.0 &&
        visualViewportOffsetY.isFinite() && visualViewportOffsetY >= 0.0 &&
        kotlin.math.abs(visualViewportOffsetX) <= 0.01 && kotlin.math.abs(visualViewportOffsetY) <= 0.01 &&
        devicePixelRatio.isFinite() && devicePixelRatio > 0.0 &&
        visualViewportScale.isFinite() && visualViewportScale > 0.0 && !animationsActive
}

internal data class BrowserVisualPoint(val bitmapX: Double, val bitmapY: Double)
internal data class BrowserWebViewPoint(val x: Double, val y: Double)

internal enum class BrowserVisualActionCode {
    OK,
    TOKEN_NOT_FOUND,
    TOKEN_EXPIRED,
    STALE_SCREENSHOT,
    VIEWPORT_CHANGED,
    COORDINATE_OUT_OF_BOUNDS,
}

internal data class BrowserVisualActionValidation(
    val code: BrowserVisualActionCode,
    val webViewPoint: BrowserWebViewPoint? = null,
    val pixelDigest: String? = null,
)

/** Bounded, expiring, single-use screenshot tokens. It does not perform or authorize DOM actions. */
internal class BrowserVisualActionTokenRegistry(
    private val nowElapsedMillis: () -> Long,
    private val randomToken: () -> String = ::secureToken,
    private val tokenTtlMillis: Long = DEFAULT_TOKEN_TTL_MILLIS,
    private val capacity: Int = MAX_TOKENS,
) {
    @Synchronized
    fun invalidateAll() { entries.clear() }

    private data class Entry(val identity: BrowserVisualIdentity, val expiresAt: Long, val pixelDigest: String?)
    private val entries = LinkedHashMap<String, Entry>()

    init {
        require(tokenTtlMillis in 1..MAX_TOKEN_TTL_MILLIS)
        require(capacity in 1..MAX_TOKENS)
    }

    /** Issue only if capture began and ended on the same valid page and viewport. */
    @Synchronized
    fun issue(beforeCapture: BrowserVisualIdentity, afterCapture: BrowserVisualIdentity, pixelDigest: String? = null): String? {
        pruneExpired()
        if (pixelDigest != null && !pixelDigest.matches(Regex("[a-f0-9]{64}"))) return null
        if (!beforeCapture.isValid() || beforeCapture != afterCapture || !afterCapture.isValid()) return null
        val now = nowElapsedMillis()
        if (now < 0 || now > Long.MAX_VALUE - tokenTtlMillis) return null
        var token: String? = null
        var attempts = 0
        while (attempts < MAX_TOKEN_ATTEMPTS) {
            attempts++
            val candidate = randomToken()
            if (candidate.matches(TOKEN_PATTERN) && !entries.containsKey(candidate)) {
                token = candidate
                break
            }
        }
        val uniqueToken = token ?: return null
        entries[uniqueToken] = Entry(afterCapture, now + tokenTtlMillis, pixelDigest)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return uniqueToken
    }

    /** Consume immediately before dispatch; validation failure also consumes the attempted token. */
    @Synchronized
    fun consume(
        token: String,
        current: BrowserVisualIdentity,
        point: BrowserVisualPoint,
    ): BrowserVisualActionValidation {
        val entry = entries.remove(token) ?: return BrowserVisualActionValidation(BrowserVisualActionCode.TOKEN_NOT_FOUND)
        val now = nowElapsedMillis()
        if (now < 0 || now >= entry.expiresAt) return BrowserVisualActionValidation(BrowserVisualActionCode.TOKEN_EXPIRED)
        if (!current.isValid() || !entry.identity.isValid()) {
            return BrowserVisualActionValidation(BrowserVisualActionCode.STALE_SCREENSHOT)
        }
        val saved = entry.identity
        if (saved.pageId != current.pageId || saved.documentGeneration != current.documentGeneration ||
            saved.documentToken != current.documentToken ||
            saved.navigationId != current.navigationId || saved.sessionGeneration != current.sessionGeneration ||
            saved.controlGeneration != current.controlGeneration || saved.viewInstanceGeneration != current.viewInstanceGeneration
        ) return BrowserVisualActionValidation(BrowserVisualActionCode.STALE_SCREENSHOT)
        if (saved != current) return BrowserVisualActionValidation(BrowserVisualActionCode.VIEWPORT_CHANGED)
        if (!point.bitmapX.isFinite() || !point.bitmapY.isFinite() ||
            point.bitmapX < 0.0 || point.bitmapY < 0.0 ||
            point.bitmapX >= current.bitmapWidth || point.bitmapY >= current.bitmapHeight
        ) return BrowserVisualActionValidation(BrowserVisualActionCode.COORDINATE_OUT_OF_BOUNDS)
        // The mapping is explicit and dimension-derived; callers must use this point for WebView hit testing.
        if (current.bitmapWidth != current.webViewWidth || current.bitmapHeight != current.webViewHeight) {
            return BrowserVisualActionValidation(BrowserVisualActionCode.VIEWPORT_CHANGED)
        }
        val ratioX = current.bitmapWidth / current.visualViewportWidth
        val ratioY = current.bitmapHeight / current.visualViewportHeight
        val expected = current.scale.toDouble()
        val viewportScale = current.devicePixelRatio * current.visualViewportScale
        if (!ratioX.isFinite() || !ratioY.isFinite() || kotlin.math.abs(ratioX - expected) > 0.02 ||
            kotlin.math.abs(ratioY - expected) > 0.02 || kotlin.math.abs(viewportScale - expected) > 0.02
        ) return BrowserVisualActionValidation(BrowserVisualActionCode.VIEWPORT_CHANGED)
        val mapped = BrowserWebViewPoint(
            point.bitmapX * current.visualViewportWidth / current.bitmapWidth + current.visualViewportOffsetX,
            point.bitmapY * current.visualViewportHeight / current.bitmapHeight + current.visualViewportOffsetY,
        )
        return BrowserVisualActionValidation(BrowserVisualActionCode.OK, mapped, entry.pixelDigest)
    }

    @Synchronized
    fun size(): Int { pruneExpired(); return entries.size }

    private fun pruneExpired() {
        val now = nowElapsedMillis()
        val expired = entries.filterValues { now >= it.expiresAt }.keys.toList()
        expired.forEach(entries::remove)
    }

    companion object {
        const val MAX_TOKENS = 8
        const val DEFAULT_TOKEN_TTL_MILLIS = 15_000L
        const val MAX_TOKEN_TTL_MILLIS = 60_000L
        const val MAX_TOKEN_ATTEMPTS = 32
        private val TOKEN_PATTERN = Regex("[a-f0-9]{32}")
        private val random = SecureRandom()

        private fun secureToken(): String = ByteArray(16).also(random::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
