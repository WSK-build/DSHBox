package com.dshbox.app.ui.onboarding

import com.dshbox.app.dsh.DshRendererEngine
import com.dshbox.app.dsh.DshRendererPageMeaning
import com.dshbox.app.dsh.DshRendererPhase
import com.dshbox.app.dsh.DshRendererReport
import com.dshbox.app.dsh.DshSystemWebViewProvider
import com.dshbox.app.dsh.pageMeaning
import com.dshbox.app.dsh.safeUserAgent
import com.dshbox.app.dsh.systemWebViewProvider
import com.dshbox.dsh.renderer.api.DshWebCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure report tests shared with onboarding.
 *
 * Provider parsing remains useful as fingerprint information, but neither UA
 * nor a complete CSS/JS capability map is allowed to declare a DSH page ready.
 */
class OnboardingWebviewPageTest {

    @Test
    fun providerParsingKeepsActualPackageAndVersion() {
        assertEquals(
            DshSystemWebViewProvider(
                packageName = "com.google.android.webview",
                versionName = "126.0.6478.71",
            ),
            systemWebViewProvider(
                packageName = " com.google.android.webview ",
                versionName = " 126.0.6478.71 ",
            ),
        )
    }

    @Test
    fun providerParsingRejectsMissingPackageWithoutInventingOne() {
        assertNull(systemWebViewProvider(null, "126.0.6478.71"))
        assertNull(systemWebViewProvider("  ", "126.0.6478.71"))
        assertEquals(
            DshSystemWebViewProvider("com.android.webview", null),
            systemWebViewProvider("com.android.webview", " "),
        )
    }

    @Test
    fun safeUserAgentIsBoundedPrintableText() {
        val sanitized = safeUserAgent("Mozilla\nChrome/128\u0000\uD83D\uDE00 Mobile")
        assertEquals("Mozilla Chrome/128 Mobile", sanitized)

        val bounded = safeUserAgent("A".repeat(700))
        assertEquals(512, bounded?.length)
        assertNull(safeUserAgent("\u0000\n\t"))
    }

    @Test
    fun allCapabilitiesAndStoppedPageStillDoNotEstablishReady() {
        val allObserved = DshWebCapability.entries.associateWith { true }
        val report = DshRendererReport(
            engine = DshRendererEngine.SYSTEM_WEBVIEW,
            engineBuild = null,
            phase = DshRendererPhase.PAGE_STOPPED,
            provider = DshSystemWebViewProvider(
                packageName = "com.google.android.webview",
                versionName = "128.0.0.0",
            ),
            userAgent = "Mozilla/5.0 Chrome/128.0.0.0 Mobile",
            nativeCapabilities = allObserved,
            effectiveCapabilities = allObserved,
            capabilitiesObserved = true,
        )

        assertEquals(DshRendererPageMeaning.STOPPED_BUT_NOT_READY, report.pageMeaning)
        assertTrue(report.capabilitiesObserved)
    }

    @Test
    fun uncollectedPageIsNotPromotedByAChromeUserAgent() {
        val allObserved = DshWebCapability.entries.associateWith { true }
        val report = DshRendererReport(
            engine = DshRendererEngine.GECKO,
            engineBuild = "158.0",
            phase = DshRendererPhase.NOT_COLLECTED,
            provider = null,
            userAgent = "Mozilla/5.0 Chrome/128.0.0.0 Mobile",
            nativeCapabilities = null,
            effectiveCapabilities = allObserved,
            capabilitiesObserved = true,
        )

        assertEquals(DshRendererPageMeaning.NOT_COLLECTED, report.pageMeaning)
    }

    @Test
    fun uncollectedCapabilitiesRemainAbsentInsteadOfBeingInventedAsFalse() {
        val report = DshRendererReport(
            engine = DshRendererEngine.GECKO,
            engineBuild = "158.0",
            phase = DshRendererPhase.PAGE_STARTED,
            provider = null,
            userAgent = null,
            nativeCapabilities = null,
            effectiveCapabilities = emptyMap(),
            capabilitiesObserved = false,
        )

        assertNull(report.nativeCapabilities)
        assertTrue(report.effectiveCapabilities.isEmpty())
        assertFalse(report.capabilitiesObserved)
    }

    @Test
    fun releasedReportKeepsLastObservationsButIsMarkedReleased() {
        val observed = mapOf(DshWebCapability.CSS_HAS to true)
        val report = DshRendererReport(
            engine = DshRendererEngine.SYSTEM_WEBVIEW,
            engineBuild = null,
            phase = DshRendererPhase.RELEASED,
            provider = DshSystemWebViewProvider("com.google.android.webview", "128.0.0.0"),
            userAgent = "Mozilla/5.0 Chrome/128.0.0.0 Mobile",
            nativeCapabilities = observed,
            effectiveCapabilities = observed,
            capabilitiesObserved = true,
        )

        assertEquals(DshRendererPageMeaning.RELEASED, report.pageMeaning)
        assertEquals(true, report.effectiveCapabilities[DshWebCapability.CSS_HAS])
    }

    @Test
    fun workerDiagnosticsRemainObservationsAndDoNotEstablishReady() {
        val report = DshRendererReport(
            engine = DshRendererEngine.SYSTEM_WEBVIEW,
            engineBuild = null,
            phase = DshRendererPhase.PAGE_STOPPED,
            provider = DshSystemWebViewProvider(
                packageName = "com.android.webview",
                versionName = "fingerprint-only",
            ),
            userAgent = null,
            nativeCapabilities = mapOf(DshWebCapability.WORKER to true),
            effectiveCapabilities = mapOf(DshWebCapability.WORKER to true),
            capabilitiesObserved = true,
            workerCapabilities = mapOf(
                DshWebCapability.WORKER_CLASSIC to true,
                DshWebCapability.WORKER_MODULE to true,
                DshWebCapability.PDF_WORKER_API to false,
            ),
            workerDetails = mapOf("PROMISE_WITH_RESOLVERS" to true),
        )

        assertEquals(true, report.workerCapabilities?.get(DshWebCapability.WORKER_CLASSIC))
        assertEquals(false, report.workerCapabilities?.get(DshWebCapability.PDF_WORKER_API))
        assertEquals(DshRendererPageMeaning.STOPPED_BUT_NOT_READY, report.pageMeaning)
    }
}
