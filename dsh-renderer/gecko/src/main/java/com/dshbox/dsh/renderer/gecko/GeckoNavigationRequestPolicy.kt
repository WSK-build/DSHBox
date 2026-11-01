package com.dshbox.dsh.renderer.gecko

/**
 * Separates host-initiated loads from page-initiated navigations.
 *
 * GeckoView 158 marks Loader loads as direct and redirects with isRedirect.
 * The expected URI is retained as a defensive guard for a callback that omits
 * those flags in a future GeckoView revision.
 */
object GeckoNavigationRequestPolicy {
    fun shouldInvalidate(
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        requestUri: String,
        expectedHostNavigationUri: String?,
    ): Boolean {
        if (isRedirect || isDirectNavigation) return false
        return expectedHostNavigationUri == null || requestUri != expectedHostNavigationUri
    }
}
