package com.dshbox.dsh.renderer.api

/**
 * Pure navigation and authentication observation reducer.
 *
 * The machine owns navigation and recovery admission only. The controller executes
 * returned effects and guards each queued navigation against the current identity,
 * navigation and token revision. Until it sends [DshNavigationAuthEvent.NavigationDispatchAccepted],
 * a token revision change reissues the pending navigation. After acknowledgement,
 * token changes only update availability and recovery eligibility; they never restart
 * the already-started navigation.
 */
class DshNavigationAuthStateMachine(
    identity: DshRenderIdentity,
    initialTokenRevision: Long? = null,
) {
    private var nextNavigationId = 1L

    private var state = DshNavigationAuthSnapshot(
        identity = identity,
        tokenRevision = initialTokenRevision,
        lastValidTokenRevision = initialTokenRevision,
        pendingAuthRecovery = false,
        currentNavigationId = null,
        currentCause = null,
        currentNavigationTokenRevision = null,
        loadUiPhase = DshLoadUiPhase.IDLE,
        documentBinding = null,
        observation = DshDocumentObservation.NOT_OBSERVED,
        lastHttpStatus = null,
        recoveryConsumed = false,
        released = false,
    )

    val snapshot: DshNavigationAuthSnapshot
        get() = state

    fun onEvent(event: DshNavigationAuthEvent): DshNavigationAuthTransition =
        when (event) {
            is DshNavigationAuthEvent.SystemMainFrameHttpError -> onSystemHttpError(event)
            is DshNavigationAuthEvent.InitialLoadRequested -> onInitialLoad(event)
            is DshNavigationAuthEvent.ManualRetryRequested -> onManualRetry(event)
            is DshNavigationAuthEvent.TokenChanged -> onTokenChanged(event)
            is DshNavigationAuthEvent.NavigationDispatchAccepted -> onDispatchAccepted(event)
            is DshNavigationAuthEvent.MainDocumentStatusObserved -> onStatus(event)
            is DshNavigationAuthEvent.PageStopped -> onPageStopped(event)
            is DshNavigationAuthEvent.ObservationUnavailable -> onObservationUnavailable(event)
            is DshNavigationAuthEvent.SessionReleased -> onSessionReleased(event)
        }

    private fun onInitialLoad(
        event: DshNavigationAuthEvent.InitialLoadRequested,
    ): DshNavigationAuthTransition {
        if (!accepts(event.identity) || state.currentNavigationId != null) {
            return unchanged()
        }
        return transition(
            startNavigation(
                cause = DshLoadCause.INITIAL,
                tokenRevision = state.tokenRevision,
            ),
        )
    }

    private fun onManualRetry(
        event: DshNavigationAuthEvent.ManualRetryRequested,
    ): DshNavigationAuthTransition {
        if (!accepts(event.identity)) return unchanged()
        return transition(
            startNavigation(
                cause = DshLoadCause.MANUAL_RETRY,
                tokenRevision = state.tokenRevision,
            ),
        )
    }

    private fun onTokenChanged(
        event: DshNavigationAuthEvent.TokenChanged,
    ): DshNavigationAuthTransition {
        if (!accepts(event.identity)) return unchanged()
        val nextRevision = event.tokenRevision
        val lastValid = state.lastValidTokenRevision

        if (nextRevision == null) {
            if (state.tokenRevision == null) return unchanged()
            state = state.copy(tokenRevision = null)
            if (state.loadUiPhase != DshLoadUiPhase.AWAITING_DISPATCH) {
                return unchanged()
            }
            if (state.pendingAuthRecovery ||
                state.currentCause == DshLoadCause.AUTH_RECOVERY
            ) {
                state = state.copy(pendingAuthRecovery = true)
                return unchanged()
            }
            return transition(
                startNavigation(
                    cause = state.currentCause ?: DshLoadCause.INITIAL,
                    tokenRevision = null,
                ),
            )
        }

        if (lastValid != null && nextRevision < lastValid) {
            return unchanged()
        }
        if (state.tokenRevision == nextRevision) {
            return unchanged()
        }

        val isNewRevision = lastValid == null || nextRevision > lastValid
        state = state.copy(
            tokenRevision = nextRevision,
            lastValidTokenRevision = if (isNewRevision) nextRevision else lastValid,
            recoveryConsumed = if (isNewRevision) false else state.recoveryConsumed,
        )

        if (state.pendingAuthRecovery) {
            return transition(
                startNavigation(
                    cause = DshLoadCause.AUTH_RECOVERY,
                    tokenRevision = nextRevision,
                ),
            )
        }

        if (state.loadUiPhase == DshLoadUiPhase.AWAITING_DISPATCH &&
            state.currentNavigationId != null
        ) {
            return transition(
                startNavigation(
                    cause = state.currentCause ?: DshLoadCause.INITIAL,
                    tokenRevision = nextRevision,
                ),
            )
        }

        if (isNewRevision &&
            state.currentNavigationId != null &&
            state.documentBinding != null &&
            state.observation == DshDocumentObservation.UNAUTHORIZED
        ) {
            return transition(
                startNavigation(
                    cause = DshLoadCause.AUTH_RECOVERY,
                    tokenRevision = nextRevision,
                ),
            )
        }

        return unchanged()
    }

    private fun onDispatchAccepted(
        event: DshNavigationAuthEvent.NavigationDispatchAccepted,
    ): DshNavigationAuthTransition {
        if (!accepts(event.identity) ||
            event.navigationId != state.currentNavigationId ||
            state.loadUiPhase != DshLoadUiPhase.AWAITING_DISPATCH ||
            event.tokenRevision != state.currentNavigationTokenRevision ||
            event.tokenRevision != state.tokenRevision
        ) {
            return unchanged()
        }

        state = state.copy(
            loadUiPhase = DshLoadUiPhase.LOADING,
            pendingAuthRecovery = false,
        )
        return unchanged()
    }

    private fun onStatus(
        event: DshNavigationAuthEvent.MainDocumentStatusObserved,
    ): DshNavigationAuthTransition {
        val attribution = event.attribution
        if (state.released ||
            attribution.identity != state.identity ||
            attribution.navigationId != state.currentNavigationId ||
            state.loadUiPhase == DshLoadUiPhase.AWAITING_DISPATCH ||
            !attribution.isTopLevelMainFrame ||
            !attribution.adapterVerified ||
            !event.httpStatus.isFinalMainDocumentStatus()
        ) {
            return unchanged()
        }

        val navigationId = attribution.navigationId
        val existingBinding = state.documentBinding
        if (existingBinding != null && existingBinding != attribution.binding) {
            return transition(
                DshNavigationAuthEffect.ReportAttributionConflict(
                    identity = state.identity,
                    navigationId = navigationId,
                ),
            )
        }

        val existingStatus = state.lastHttpStatus
        if (state.observation.isTerminal() && existingStatus != event.httpStatus) {
            return transition(
                DshNavigationAuthEffect.ReportAttributionConflict(
                    identity = state.identity,
                    navigationId = navigationId,
                ),
            )
        }

        if (existingStatus == event.httpStatus && state.observation.isTerminal()) {
            return unchanged()
        }

        state = state.copy(
            documentBinding = attribution.binding,
            observation = when {
                event.httpStatus in 200..299 -> DshDocumentObservation.SUCCESS
                event.httpStatus == 401 -> DshDocumentObservation.UNAUTHORIZED
                else -> DshDocumentObservation.OTHER
            },
            lastHttpStatus = event.httpStatus,
        )

        if (event.httpStatus != 401 || state.recoveryConsumed) {
            if (event.httpStatus != 401) {
                state = state.copy(pendingAuthRecovery = false)
            }
            return unchanged()
        }

        return recoverUnauthorized(navigationId)
    }

    private fun onSystemHttpError(
        event: DshNavigationAuthEvent.SystemMainFrameHttpError,
    ): DshNavigationAuthTransition {
        if (!accepts(event.identity) || event.navigationId != state.currentNavigationId ||
            state.loadUiPhase == DshLoadUiPhase.AWAITING_DISPATCH ||
            event.httpStatus != 401 || state.recoveryConsumed
        ) return unchanged()
        state = state.copy(
            observation = DshDocumentObservation.UNAUTHORIZED,
            lastHttpStatus = 401,
        )
        return recoverUnauthorized(event.navigationId)
    }

    private fun recoverUnauthorized(navigationId: Long): DshNavigationAuthTransition {
        state = state.copy(recoveryConsumed = true)
        val tokenRevision = state.tokenRevision
        if (tokenRevision == null) {
            state = state.copy(pendingAuthRecovery = true)
            return transition(
                DshNavigationAuthEffect.AwaitToken(
                    identity = state.identity,
                    navigationId = navigationId,
                ),
            )
        }

        return transition(
            startNavigation(
                cause = DshLoadCause.AUTH_RECOVERY,
                tokenRevision = tokenRevision,
            ),
        )
    }

    private fun onPageStopped(
        event: DshNavigationAuthEvent.PageStopped,
    ): DshNavigationAuthTransition {
        if (!accepts(event.identity) ||
            event.navigationId != state.currentNavigationId ||
            state.loadUiPhase == DshLoadUiPhase.AWAITING_DISPATCH ||
            state.loadUiPhase == DshLoadUiPhase.STOPPED
        ) {
            return unchanged()
        }
        state = state.copy(loadUiPhase = DshLoadUiPhase.STOPPED)
        return unchanged()
    }

    private fun onObservationUnavailable(
        event: DshNavigationAuthEvent.ObservationUnavailable,
    ): DshNavigationAuthTransition {
        if (!accepts(event.identity) ||
            event.navigationId != state.currentNavigationId ||
            state.loadUiPhase == DshLoadUiPhase.AWAITING_DISPATCH ||
            state.observation.isTerminal() ||
            state.observation == DshDocumentObservation.UNKNOWN
        ) {
            return unchanged()
        }

        state = state.copy(
            observation = DshDocumentObservation.UNKNOWN,
            lastHttpStatus = null,
        )
        return transition(
            DshNavigationAuthEffect.ReportUnknown(
                identity = state.identity,
                navigationId = event.navigationId,
                reason = event.reason,
            ),
        )
    }

    private fun onSessionReleased(
        event: DshNavigationAuthEvent.SessionReleased,
    ): DshNavigationAuthTransition {
        if (event.identity != state.identity || state.released) return unchanged()
        state = state.copy(
            pendingAuthRecovery = false,
            currentNavigationId = null,
            currentCause = null,
            currentNavigationTokenRevision = null,
            loadUiPhase = DshLoadUiPhase.IDLE,
            documentBinding = null,
            observation = DshDocumentObservation.NOT_OBSERVED,
            lastHttpStatus = null,
            recoveryConsumed = false,
            released = true,
        )
        return unchanged()
    }

    private fun startNavigation(
        cause: DshLoadCause,
        tokenRevision: Long?,
    ): DshNavigationAuthEffect.StartNavigation {
        val navigationId = nextNavigationId++
        state = state.copy(
            pendingAuthRecovery = false,
            currentNavigationId = navigationId,
            currentCause = cause,
            currentNavigationTokenRevision = tokenRevision,
            loadUiPhase = DshLoadUiPhase.AWAITING_DISPATCH,
            documentBinding = null,
            observation = DshDocumentObservation.NOT_OBSERVED,
            lastHttpStatus = null,
            recoveryConsumed = cause == DshLoadCause.AUTH_RECOVERY,
        )
        return DshNavigationAuthEffect.StartNavigation(
            identity = state.identity,
            navigationId = navigationId,
            cause = cause,
            tokenRevision = tokenRevision,
        )
    }

    private fun accepts(identity: DshRenderIdentity): Boolean =
        !state.released && identity == state.identity

    private fun unchanged(): DshNavigationAuthTransition =
        transition()

    private fun transition(
        vararg effects: DshNavigationAuthEffect,
    ): DshNavigationAuthTransition =
        DshNavigationAuthTransition(
            snapshot = state,
            effects = effects.toList(),
        )

    private fun Int.isFinalMainDocumentStatus(): Boolean =
        this in 200..599 && this !in 300..399

    private fun DshDocumentObservation.isTerminal(): Boolean =
        this == DshDocumentObservation.SUCCESS ||
            this == DshDocumentObservation.UNAUTHORIZED ||
            this == DshDocumentObservation.OTHER
}
