package com.dshbox.dsh.renderer.api

/**
 * Opaque reference supplied by a renderer adapter.
 *
 * Raw values participate in equality but are never exposed through [toString].
 */
class DshNativeRef private constructor(private val raw: String) {
    init {
        require(raw.isNotEmpty()) { "Native reference must not be empty" }
    }

    override fun equals(other: Any?): Boolean =
        other is DshNativeRef && raw == other.raw

    override fun hashCode(): Int = raw.hashCode()

    override fun toString(): String = "DshNativeRef(redacted)"

    companion object {
        fun of(value: String): DshNativeRef = DshNativeRef(value)
    }
}

/** Instance and native Session identity carried by every asynchronous event. */
data class DshRenderIdentity(
    val instanceRef: DshNativeRef,
    val sessionRef: DshNativeRef,
)

/** Native request and document identity for one final top-level main-document response. */
data class DshRequestDocumentBinding(
    val requestId: DshNativeRef,
    val documentId: DshNativeRef,
)

/**
 * Attribution produced only after an adapter verifies native Session ownership,
 * top-level status and the trusted origin. URL is deliberately absent.
 */
data class DshNativeDocumentAttribution(
    val identity: DshRenderIdentity,
    val navigationId: Long,
    val binding: DshRequestDocumentBinding,
    val isTopLevelMainFrame: Boolean,
    val adapterVerified: Boolean,
)

enum class DshLoadCause {
    INITIAL,
    MANUAL_RETRY,
    AUTH_RECOVERY,
}

enum class DshLoadUiPhase {
    IDLE,
    /** StartNavigation was emitted but the controller has not accepted its execution guard yet. */
    AWAITING_DISPATCH,
    LOADING,
    STOPPED,
}

enum class DshDocumentObservation {
    NOT_OBSERVED,
    SUCCESS,
    UNAUTHORIZED,
    OTHER,
    /** Observation is missing or timed out; a later valid status for the same navigation may replace it. */
    UNKNOWN,
}

enum class DshObservationFailure {
    MISSING_STATUS,
    OBSERVATION_TIMEOUT,
    ADAPTER_UNAVAILABLE,
}

sealed interface DshNavigationAuthEvent {
    /**
     * System WebView main-frame HTTP error only. The adapter must validate the
     * configured origin and active requested URL; this does not assert native
     * request/document identity, which WebView does not expose.
     */
    data class SystemMainFrameHttpError(
        val identity: DshRenderIdentity,
        val navigationId: Long,
        val httpStatus: Int,
    ) : DshNavigationAuthEvent

    data class InitialLoadRequested(
        val identity: DshRenderIdentity,
    ) : DshNavigationAuthEvent

    data class ManualRetryRequested(
        val identity: DshRenderIdentity,
    ) : DshNavigationAuthEvent

    data class TokenChanged(
        val identity: DshRenderIdentity,
        /**
         * `null` means unavailable. A revision lower than the last valid revision is
         * ignored and can never reset recovery eligibility.
         */
        val tokenRevision: Long?,
    ) : DshNavigationAuthEvent

    /**
     * Controller acknowledgement emitted after it verifies a [DshNavigationAuthEffect.StartNavigation]
     * guard and immediately before starting that navigation.
     */
    data class NavigationDispatchAccepted(
        val identity: DshRenderIdentity,
        val navigationId: Long,
        val tokenRevision: Long?,
    ) : DshNavigationAuthEvent

    /**
     * [httpStatus] must be the final main-document response. Intermediate 1xx/3xx
     * responses are ignored and do not seal the observation.
     */
    data class MainDocumentStatusObserved(
        val attribution: DshNativeDocumentAttribution,
        val httpStatus: Int,
    ) : DshNavigationAuthEvent

    data class PageStopped(
        val identity: DshRenderIdentity,
        val navigationId: Long,
    ) : DshNavigationAuthEvent

    data class ObservationUnavailable(
        val identity: DshRenderIdentity,
        val navigationId: Long,
        val reason: DshObservationFailure,
    ) : DshNavigationAuthEvent

    data class SessionReleased(
        val identity: DshRenderIdentity,
    ) : DshNavigationAuthEvent
}

/**
 * [StartNavigation.tokenRevision] is a non-secret execution guard. Before loading,
 * the controller must match identity, navigation and current token revision, and
 * discard the effect if the revision has changed.
 */
sealed interface DshNavigationAuthEffect {
    data class StartNavigation(
        val identity: DshRenderIdentity,
        val navigationId: Long,
        val cause: DshLoadCause,
        val tokenRevision: Long?,
    ) : DshNavigationAuthEffect

    data class AwaitToken(
        val identity: DshRenderIdentity,
        val navigationId: Long,
    ) : DshNavigationAuthEffect

    data class ReportUnknown(
        val identity: DshRenderIdentity,
        val navigationId: Long,
        val reason: DshObservationFailure,
    ) : DshNavigationAuthEffect

    data class ReportAttributionConflict(
        val identity: DshRenderIdentity,
        val navigationId: Long,
    ) : DshNavigationAuthEffect
}

data class DshNavigationAuthSnapshot(
    val identity: DshRenderIdentity,
    /** Currently available revision; `null` means temporarily unavailable. */
    val tokenRevision: Long?,
    /** Highest revision ever observed; never moves backwards. */
    val lastValidTokenRevision: Long?,
    val pendingAuthRecovery: Boolean,
    val currentNavigationId: Long?,
    val currentCause: DshLoadCause?,
    val currentNavigationTokenRevision: Long?,
    val loadUiPhase: DshLoadUiPhase,
    val documentBinding: DshRequestDocumentBinding?,
    val observation: DshDocumentObservation,
    val lastHttpStatus: Int?,
    val recoveryConsumed: Boolean,
    val released: Boolean,
)

data class DshNavigationAuthTransition(
    val snapshot: DshNavigationAuthSnapshot,
    val effects: List<DshNavigationAuthEffect>,
)
