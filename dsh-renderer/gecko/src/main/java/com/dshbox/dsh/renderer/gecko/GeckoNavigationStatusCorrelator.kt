package com.dshbox.dsh.renderer.gecko

import com.dshbox.dsh.renderer.api.DshNativeRef
import com.dshbox.dsh.renderer.api.DshRequestDocumentBinding
import java.util.UUID

/**
 * Main-thread owner of the current Gecko navigation generation.
 *
 * Extension messages carry a nonce that is injected into the top-level request.
 * A result is usable only while that exact nonce still belongs to the active
 * generation. This intentionally has no dependency on page-stop ordering.
 */
class GeckoNavigationStatusCorrelator(
    private val isTrustedUrl: (String) -> Boolean,
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() },
) {
    enum class MessageKind {
        HEADERS,
        DOCUMENT_STATUS,
    }

    data class Navigation(
        val generation: Long,
        val navigationId: Long?,
        val nonce: String,
    )

    data class Message(
        val kind: MessageKind,
        val requestId: String,
        val nonce: String?,
        val url: String,
        val httpStatus: Int?,
        val documentId: String?,
        val isTopLevelMainFrame: Boolean,
    )

    data class Observation(
        val generation: Long,
        val navigationId: Long,
        val nonce: String,
        val binding: DshRequestDocumentBinding,
        val httpStatus: Int,
    )

    private data class Headers(
        val requestId: String,
        val httpStatus: Int,
    )

    private data class Document(
        val requestId: String,
        val documentId: String,
    )

    private var generation = 0L
    private var active: Navigation? = null
    private var headers: Headers? = null
    private var document: Document? = null
    private var lastObservation: Observation? = null

    val current: Navigation?
        get() = active

    fun beginNavigation(navigationId: Long?, url: String): Navigation {
        require(isTrustedUrl(url)) { "Navigation URL is not trusted" }
        generation += 1
        val navigation = Navigation(
            generation = generation,
            navigationId = navigationId,
            nonce = nonceFactory(),
        )
        active = navigation
        headers = null
        document = null
        lastObservation = null
        return navigation
    }

    /** Invalidates late extension results for unguarded navigation such as history changes. */
    fun invalidate() {
        generation += 1
        active = null
        headers = null
        document = null
        lastObservation = null
    }

    /**
     * Page-stop is deliberately observation-neutral. It documents that callers
     * must not use stop ordering as a close condition for status attribution.
     */
    fun onPageStopped(@Suppress("UNUSED_PARAMETER") navigationId: Long?) {
        // Intentionally empty.
    }

    fun onHeadersReceived(message: Message): Observation? {
        if (message.kind != MessageKind.HEADERS ||
            !accepts(message) ||
            (document != null && document?.requestId != message.requestId) ||
            message.httpStatus?.isFinalMainDocumentStatus() != true
        ) {
            return null
        }
        headers = Headers(message.requestId, message.httpStatus)
        return emitIfComplete()
    }

    fun onDocumentStatus(message: Message): Observation? {
        if (message.kind != MessageKind.DOCUMENT_STATUS ||
            !accepts(message) ||
            (headers != null && headers?.requestId != message.requestId) ||
            message.documentId.isNullOrBlank()
        ) {
            return null
        }
        document = Document(message.requestId, message.documentId)
        // 状态码的唯一权威来源是 headers 通道。document-status 只提供
        // documentId 绑定；它携带的 status 是 headers 事件的回显，直接忽略，
        // 避免两来源冲突时互相覆盖（headers=401 被 document=200 掩盖）。
        return emitIfComplete()
    }

    fun isCurrent(observation: Observation): Boolean {
        val navigation = active ?: return false
        return navigation.generation == observation.generation &&
            navigation.nonce == observation.nonce &&
            navigation.navigationId == observation.navigationId
    }

    private fun accepts(message: Message): Boolean {
        val navigation = active ?: return false
        if (!message.isTopLevelMainFrame ||
            message.requestId.isBlank() ||
            message.nonce.isNullOrBlank() ||
            message.nonce != navigation.nonce ||
            !isTrustedUrl(message.url)
        ) {
            return false
        }
        return true
    }

    private fun emitIfComplete(): Observation? {
        val navigation = active ?: return null
        val navigationId = navigation.navigationId ?: return null
        val headers = headers ?: return null
        val document = document ?: return null
        if (headers.requestId != document.requestId) return null

        val observation = Observation(
            generation = navigation.generation,
            navigationId = navigationId,
            nonce = navigation.nonce,
            binding = DshRequestDocumentBinding(
                requestId = DshNativeRef.of(headers.requestId),
                documentId = DshNativeRef.of(document.documentId),
            ),
            httpStatus = headers.httpStatus,
        )
        if (observation == lastObservation) return null
        lastObservation = observation
        return observation
    }

    private fun Int.isFinalMainDocumentStatus(): Boolean =
        this in 200..599 && this !in 300..399
}
