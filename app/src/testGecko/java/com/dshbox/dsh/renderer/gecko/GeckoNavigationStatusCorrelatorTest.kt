package com.dshbox.dsh.renderer.gecko

import com.dshbox.dsh.renderer.api.DshNativeRef
import com.dshbox.dsh.renderer.api.DshRequestDocumentBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class GeckoNavigationStatusCorrelatorTest {
    @Test
    fun navigationGenerationAssociatesResultsAndDropsLateMessages() {
        val fixture = Fixture()
        val first = fixture.begin(11L)
        val second = fixture.begin(12L)

        assertNull(fixture.headers(first, requestId = "old-request"))
        assertNull(fixture.document(
            first,
            requestId = "old-request",
            documentId = "old-document",
        ))

        fixture.headers(second, requestId = "new-request")
        val observation = fixture.document(
            second,
            requestId = "new-request",
            documentId = "new-document",
        )

        assertEquals(12L, observation?.navigationId)
        assertEquals(second.generation, observation?.generation)
        assertEquals(
            DshRequestDocumentBinding(
                requestId = DshNativeRef.of("new-request"),
                documentId = DshNativeRef.of("new-document"),
            ),
            observation?.binding,
        )
        assertTrue(fixture.correlator.isCurrent(observation!!))
    }

    @Test
    fun documentStatusBeforePageStopRecoversExactlyOnce() {
        val fixture = Fixture()
        val navigation = fixture.begin(21L)

        fixture.headers(navigation, requestId = "request-before")
        val observation = fixture.document(
            navigation,
            requestId = "request-before",
            documentId = "document-before",
            httpStatus = 401,
        )
        fixture.correlator.onPageStopped(navigation.navigationId)

        assertEquals(401, observation?.httpStatus)
        assertNull(fixture.document(
            navigation,
            requestId = "request-before",
            documentId = "document-before",
            httpStatus = 401,
        ))
        assertNull(fixture.headers(navigation, requestId = "request-before", httpStatus = 401))
    }

    @Test
    fun pageStopBeforeDocumentStatusDoesNotCloseObservation() {
        val fixture = Fixture()
        val navigation = fixture.begin(31L)

        fixture.correlator.onPageStopped(navigation.navigationId)
        fixture.headers(navigation, requestId = "request-after", httpStatus = 401)
        val observation = fixture.document(
            navigation,
            requestId = "request-after",
            documentId = "document-after",
            httpStatus = 401,
        )

        assertEquals(31L, observation?.navigationId)
        assertEquals(401, observation?.httpStatus)
        assertTrue(fixture.correlator.isCurrent(observation!!))
    }

    @Test
    fun duplicate401DoesNotEmitAgain() {
        val fixture = Fixture()
        val navigation = fixture.begin(41L)

        fixture.headers(navigation, requestId = "request", httpStatus = 401)
        assertEquals(
            401,
            fixture.document(
                navigation,
                requestId = "request",
                documentId = "document",
                httpStatus = 401,
            )?.httpStatus,
        )

        assertNull(fixture.headers(navigation, requestId = "request", httpStatus = 401))
        assertNull(fixture.document(
            navigation,
            requestId = "request",
            documentId = "document",
            httpStatus = 401,
        ))
    }

    @Test
    fun headersStatusIsAuthoritativeOverConflictingDocumentStatus() {
        val fixture = Fixture()
        val navigation = fixture.begin(61L)

        fixture.headers(navigation, requestId = "conflict", httpStatus = 401)
        val observation = fixture.document(
            navigation,
            requestId = "conflict",
            documentId = "conflict-document",
            httpStatus = 200,
        )

        // headers=401 是权威来源，document-status=200 不得掩盖 401。
        assertEquals(401, observation?.httpStatus)
    }

    @Test
    fun lateHeadersStatusOverridesDocumentStatusFallback() {
        val fixture = Fixture()
        val navigation = fixture.begin(62L)

        assertNull(fixture.document(
            navigation,
            requestId = "late-headers",
            documentId = "late-headers-document",
            httpStatus = 401,
        ))
        val observation = fixture.headers(
            navigation,
            requestId = "late-headers",
            httpStatus = 200,
        )

        // headers 后到时即成为权威值（document-status 仅在其缺席时兜底）。
        assertEquals(200, observation?.httpStatus)
    }

    @Test
    fun staleGenerationIsNotCurrentEvenBeforeNextResultArrives() {
        val fixture = Fixture()
        val first = fixture.begin(51L)
        fixture.headers(first, requestId = "first-request")
        val observation = fixture.document(
            first,
            requestId = "first-request",
            documentId = "first-document",
            httpStatus = 401,
        )!!

        val second = fixture.begin(52L)

        assertFalse(fixture.correlator.isCurrent(observation))
        assertEquals(second, fixture.correlator.current)
    }

    @Test
    fun reloadKeepsNavigationIdAndDropsThePreviousGeneration() {
        val fixture = Fixture()
        val first = fixture.begin(71L)
        fixture.headers(first, requestId = "first-reload-request")
        assertEquals(
            401,
            fixture.document(
                first,
                requestId = "first-reload-request",
                documentId = "first-reload-document",
            )?.httpStatus,
        )

        val reload = fixture.begin(71L)
        assertNull(fixture.document(
            first,
            requestId = "first-reload-request",
            documentId = "first-reload-document",
            httpStatus = 200,
        ))
        fixture.headers(reload, requestId = "reload-request", httpStatus = 401)
        val observation = fixture.document(
            reload,
            requestId = "reload-request",
            documentId = "reload-document",
            httpStatus = 401,
        )

        assertEquals(71L, observation?.navigationId)
        assertEquals(reload.generation, observation?.generation)
    }

    @Test
    fun wrongNonceIsRejected() {
        val fixture = Fixture()
        val navigation = fixture.begin(81L)
        val wrongNonce = "wrong-nonce"

        assertNull(fixture.headers(navigation, "wrong-nonce", nonce = wrongNonce))
        assertNull(fixture.document(
            navigation,
            "wrong-nonce",
            "wrong-nonce-document",
            nonce = wrongNonce,
        ))
    }

    @Test
    fun nonTopLevelMessagesAreRejected() {
        val fixture = Fixture()
        val navigation = fixture.begin(82L)

        assertNull(fixture.headers(navigation, "child-request", topLevel = false))
        assertNull(fixture.document(
            navigation,
            "child-request",
            "child-document",
            topLevel = false,
        ))
    }

    @Test
    fun emptyDocumentIdAndUntrustedUrlAreRejected() {
        val fixture = Fixture()
        val navigation = fixture.begin(83L)
        fixture.headers(navigation, "empty-document-request")

        assertNull(fixture.document(
            navigation,
            "empty-document-request",
            documentId = "",
        ))
        assertNull(fixture.headers(
            navigation,
            "untrusted-request",
            url = "http://127.0.0.1:3081/untrusted",
        ))
        assertNull(fixture.document(
            navigation,
            "untrusted-request",
            "untrusted-document",
            url = "http://127.0.0.1:3081/untrusted",
        ))
    }

    @Test
    fun mismatchedRequestIdsAreRejected() {
        val fixture = Fixture()
        val navigation = fixture.begin(84L)

        fixture.headers(navigation, "request-a")
        assertNull(fixture.document(
            navigation,
            "request-b",
            "document-b",
        ))
    }

    @Test
    fun invalidationAndNullNavigationIdProduceNoObservation() {
        val fixture = Fixture()
        val navigation = fixture.begin(86L)
        fixture.headers(navigation, "invalidated-request")
        fixture.correlator.invalidate()
        assertNull(fixture.document(
            navigation,
            "invalidated-request",
            "invalidated-document",
        ))

        val noNavigationId = fixture.begin(null)
        fixture.headers(noNavigationId, "anonymous-request")
        assertNull(fixture.document(
            noNavigationId,
            "anonymous-request",
            "anonymous-document",
        ))
    }

    @Test
    fun dispatcherSerializesMessagesAndRechecksOwnershipAtExecutionTime() {
        val fixture = Fixture()
        val navigation = fixture.begin(91L)
        val queue = ArrayDeque<() -> Unit>()
        var observation: GeckoNavigationStatusCorrelator.Observation? = null
        val dispatcher = GeckoStatusMessageDispatcher(
            correlator = fixture.correlator,
            postToMain = { task -> queue.addLast(task) },
            isActive = { true },
            onObservation = { observation = it },
        )

        dispatcher.submitHeaders(fixture.headersMessage(navigation, "queued-request"))
        dispatcher.submitDocumentStatus(
            fixture.documentMessage(navigation, "queued-request", "queued-document"),
        )
        assertNull(observation)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(91L, observation?.navigationId)

        val stale = fixture.begin(92L)
        dispatcher.submitDocumentStatus(
            fixture.documentMessage(stale, "stale-request", "stale-document"),
        )
        fixture.begin(93L)
        while (queue.isNotEmpty()) queue.removeFirst()()
        assertEquals(91L, observation?.navigationId)
    }

    @Test
    fun directLoaderAndRedirectRequestsDoNotInvalidateAttribution() {
        assertFalse(GeckoNavigationRequestPolicy.shouldInvalidate(
            isRedirect = false,
            isDirectNavigation = true,
            requestUri = "http://127.0.0.1:3080/page",
            expectedHostNavigationUri = null,
        ))
        assertFalse(GeckoNavigationRequestPolicy.shouldInvalidate(
            isRedirect = true,
            isDirectNavigation = false,
            requestUri = "http://127.0.0.1:3080/redirected",
            expectedHostNavigationUri = null,
        ))
        assertFalse(GeckoNavigationRequestPolicy.shouldInvalidate(
            isRedirect = false,
            isDirectNavigation = false,
            requestUri = "http://127.0.0.1:3080/expected",
            expectedHostNavigationUri = "http://127.0.0.1:3080/expected",
        ))
        assertTrue(GeckoNavigationRequestPolicy.shouldInvalidate(
            isRedirect = false,
            isDirectNavigation = false,
            requestUri = "http://127.0.0.1:3080/clicked",
            expectedHostNavigationUri = null,
        ))
    }

    private class Fixture {
        private var nonce = 0L
        val correlator = GeckoNavigationStatusCorrelator(
            isTrustedUrl = { it.startsWith("http://127.0.0.1:3080/") },
            nonceFactory = { "nonce-${++nonce}" },
        )

        fun begin(navigationId: Long?): GeckoNavigationStatusCorrelator.Navigation =
            correlator.beginNavigation(navigationId, "http://127.0.0.1:3080/")

        fun headers(
            navigation: GeckoNavigationStatusCorrelator.Navigation,
            requestId: String,
            httpStatus: Int = 401,
            nonce: String = navigation.nonce,
            topLevel: Boolean = true,
            url: String = "http://127.0.0.1:3080/",
        ): GeckoNavigationStatusCorrelator.Observation? =
            correlator.onHeadersReceived(
                message(
                    navigation = navigation,
                    kind = GeckoNavigationStatusCorrelator.MessageKind.HEADERS,
                    requestId = requestId,
                    httpStatus = httpStatus,
                    documentId = null,
                    nonce = nonce,
                    topLevel = topLevel,
                    url = url,
                ),
            )

        fun document(
            navigation: GeckoNavigationStatusCorrelator.Navigation,
            requestId: String,
            documentId: String,
            httpStatus: Int = 401,
            nonce: String = navigation.nonce,
            topLevel: Boolean = true,
            url: String = "http://127.0.0.1:3080/",
        ): GeckoNavigationStatusCorrelator.Observation? =
            correlator.onDocumentStatus(
                message(
                    navigation = navigation,
                    kind = GeckoNavigationStatusCorrelator.MessageKind.DOCUMENT_STATUS,
                    requestId = requestId,
                    httpStatus = httpStatus,
                    documentId = documentId,
                    nonce = nonce,
                    topLevel = topLevel,
                    url = url,
                ),
            )

        fun headersMessage(
            navigation: GeckoNavigationStatusCorrelator.Navigation,
            requestId: String,
            httpStatus: Int = 401,
        ): GeckoNavigationStatusCorrelator.Message = message(
            navigation = navigation,
            kind = GeckoNavigationStatusCorrelator.MessageKind.HEADERS,
            requestId = requestId,
            httpStatus = httpStatus,
            documentId = null,
        )

        fun documentMessage(
            navigation: GeckoNavigationStatusCorrelator.Navigation,
            requestId: String,
            documentId: String,
            httpStatus: Int = 401,
        ): GeckoNavigationStatusCorrelator.Message = message(
            navigation = navigation,
            kind = GeckoNavigationStatusCorrelator.MessageKind.DOCUMENT_STATUS,
            requestId = requestId,
            httpStatus = httpStatus,
            documentId = documentId,
        )

        private fun message(
            navigation: GeckoNavigationStatusCorrelator.Navigation,
            kind: GeckoNavigationStatusCorrelator.MessageKind,
            requestId: String,
            httpStatus: Int,
            documentId: String?,
            nonce: String = navigation.nonce,
            topLevel: Boolean = true,
            url: String = "http://127.0.0.1:3080/",
        ) = GeckoNavigationStatusCorrelator.Message(
            kind = kind,
            requestId = requestId,
            nonce = nonce,
            url = url,
            httpStatus = httpStatus,
            documentId = documentId,
            isTopLevelMainFrame = topLevel,
        )
    }
}
