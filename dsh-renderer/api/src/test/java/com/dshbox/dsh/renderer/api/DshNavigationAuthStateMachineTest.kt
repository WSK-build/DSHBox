package com.dshbox.dsh.renderer.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DshNavigationAuthStateMachineTest {
    @Test fun systemHttpErrorSharesRecoveryBudgetWithoutInventingDocumentIdentity() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 7L)
        val navigationId = initialNavigation(machine)
        val recovered = machine.onEvent(DshNavigationAuthEvent.SystemMainFrameHttpError(
            identity, navigationId, 401,
        ))
        assertRecovery(recovered, expectedTokenRevision = 7L)
        assertNull(recovered.snapshot.documentBinding)
        val recoveryId = recovered.snapshot.currentNavigationId!!
        machine.onEvent(DshNavigationAuthEvent.NavigationDispatchAccepted(identity, recoveryId, 7L))
        assertTrue(machine.onEvent(DshNavigationAuthEvent.SystemMainFrameHttpError(
            identity, recoveryId, 401,
        )).effects.isEmpty())
    }

    @Test fun staleOrUnsentSystemHttpErrorCannotDispatchRecovery() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 7L)
        val pending = machine.onEvent(DshNavigationAuthEvent.InitialLoadRequested(identity))
        val navigationId = pending.snapshot.currentNavigationId!!
        assertTrue(machine.onEvent(DshNavigationAuthEvent.SystemMainFrameHttpError(
            identity, navigationId, 401,
        )).effects.isEmpty())
        machine.onEvent(DshNavigationAuthEvent.NavigationDispatchAccepted(identity, navigationId, 7L))
        assertTrue(machine.onEvent(DshNavigationAuthEvent.SystemMainFrameHttpError(
            identity, navigationId + 1, 401,
        )).effects.isEmpty())
        assertTrue(machine.onEvent(DshNavigationAuthEvent.SystemMainFrameHttpError(
            identity, navigationId, 500,
        )).effects.isEmpty())
        assertFalse(machine.snapshot.recoveryConsumed)
    }

    private val identity = DshRenderIdentity(
        instanceRef = DshNativeRef.of("instance-raw"),
        sessionRef = DshNativeRef.of("session-raw"),
    )
    private val binding = DshRequestDocumentBinding(
        requestId = DshNativeRef.of("request-raw"),
        documentId = DshNativeRef.of("document-raw"),
    )

    @Test
    fun statusBeforeStopRecoversExactlyOnce() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 7L)
        val navigationId = initialNavigation(machine)

        val recovered = machine.onEvent(status(navigationId, 401))
        assertRecovery(recovered, expectedTokenRevision = 7L)

        val duplicate = machine.onEvent(status(navigationId, 401))
        assertTrue(duplicate.effects.isEmpty())

        val oldStop = machine.onEvent(
            DshNavigationAuthEvent.PageStopped(identity, navigationId),
        )
        assertTrue(oldStop.effects.isEmpty())
        assertEquals(DshLoadUiPhase.AWAITING_DISPATCH, oldStop.snapshot.loadUiPhase)
    }

    @Test
    fun stopBeforeStatusRecoversExactlyOnce() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 7L)
        val navigationId = initialNavigation(machine)

        val stopped = machine.onEvent(DshNavigationAuthEvent.PageStopped(identity, navigationId))
        assertTrue(stopped.effects.isEmpty())
        assertEquals(DshLoadUiPhase.STOPPED, stopped.snapshot.loadUiPhase)

        val recovered = machine.onEvent(status(navigationId, 401))
        assertRecovery(recovered, expectedTokenRevision = 7L)
    }

    @Test
    fun missingTokenDoesNotBlockInitialOrManualCookieNavigation() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = null)

        val initial = machine.onEvent(DshNavigationAuthEvent.InitialLoadRequested(identity))
        val initialStart = initial.effects.single() as DshNavigationAuthEffect.StartNavigation
        assertEquals(DshLoadCause.INITIAL, initialStart.cause)
        assertNull(initialStart.tokenRevision)

        val manual = machine.onEvent(DshNavigationAuthEvent.ManualRetryRequested(identity))
        val manualStart = manual.effects.single() as DshNavigationAuthEffect.StartNavigation
        assertEquals(DshLoadCause.MANUAL_RETRY, manualStart.cause)
        assertNull(manualStart.tokenRevision)
    }

    @Test
    fun unauthorizedWithoutTokenWaitsAndRecoversWithLatestTokenOnce() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = null)
        val navigationId = initialNavigation(machine)

        val unauthorized = machine.onEvent(status(navigationId, 401))
        assertEquals(
            DshNavigationAuthEffect.AwaitToken(identity, navigationId),
            unauthorized.effects.single(),
        )
        assertTrue(unauthorized.snapshot.pendingAuthRecovery)

        val duplicate = machine.onEvent(status(navigationId, 401))
        assertTrue(duplicate.effects.isEmpty())

        val token = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 3L),
        )
        assertRecovery(token, expectedTokenRevision = 3L)
        assertFalse(token.snapshot.pendingAuthRecovery)
    }

    @Test
    fun tokenRotationAllowsOneMoreRecoveryForCurrentDocument() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 1L)
        val firstNavigationId = initialNavigation(machine)
        val firstRecovery = machine.onEvent(status(firstNavigationId, 401))
        val recoveryNavigationId = firstRecovery.snapshot.currentNavigationId!!
        assertRecovery(firstRecovery, expectedTokenRevision = 1L)
        acceptDispatch(machine, firstRecovery)

        val noLoop = machine.onEvent(status(recoveryNavigationId, 401))
        assertTrue(noLoop.effects.isEmpty())

        val recoveredAgain = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 2L),
        )
        assertRecovery(recoveredAgain, expectedTokenRevision = 2L)
        assertTrue(machine.onEvent(status(recoveryNavigationId, 401)).effects.isEmpty())
    }

    @Test
    fun manualRetryUsesLatestRevisionAndRearmsRecovery() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 1L)
        val initialNavigationId = initialNavigation(machine)
        val firstRecovery = machine.onEvent(status(initialNavigationId, 401))
        val recoveryNavigationId = firstRecovery.snapshot.currentNavigationId!!
        acceptDispatch(machine, firstRecovery)

        val exhausted = machine.onEvent(status(recoveryNavigationId, 401))
        assertTrue(exhausted.effects.isEmpty())

        val tokenRotation = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 2L),
        )
        assertRecovery(tokenRotation, expectedTokenRevision = 2L)
        val manual = machine.onEvent(DshNavigationAuthEvent.ManualRetryRequested(identity))
        val manualStart = manual.effects.single() as DshNavigationAuthEffect.StartNavigation
        assertEquals(DshLoadCause.MANUAL_RETRY, manualStart.cause)
        assertEquals(2L, manualStart.tokenRevision)
        acceptDispatch(machine, manual)

        val manualNavigationId = manual.snapshot.currentNavigationId!!
        assertRecovery(machine.onEvent(status(manualNavigationId, 401)), 2L)
    }

    @Test
    fun unknownIsNotTerminalAndLateStatusCanDriveRecovery() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val navigationId = initialNavigation(machine)

        val unknown = machine.onEvent(
            DshNavigationAuthEvent.ObservationUnavailable(
                identity,
                navigationId,
                DshObservationFailure.OBSERVATION_TIMEOUT,
            ),
        )
        assertEquals(
            DshNavigationAuthEffect.ReportUnknown(
                identity,
                navigationId,
                DshObservationFailure.OBSERVATION_TIMEOUT,
            ),
            unknown.effects.single(),
        )
        assertEquals(DshDocumentObservation.UNKNOWN, unknown.snapshot.observation)

        val recovered = machine.onEvent(status(navigationId, 401))
        assertRecovery(recovered, expectedTokenRevision = 5L)
    }

    @Test
    fun unknownBeforeBindingAcceptsLaterCompleteAttribution() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val navigationId = initialNavigation(machine)

        machine.onEvent(
            DshNavigationAuthEvent.ObservationUnavailable(
                identity,
                navigationId,
                DshObservationFailure.MISSING_STATUS,
            ),
        )

        val success = machine.onEvent(status(navigationId, 200))
        assertTrue(success.effects.isEmpty())
        assertEquals(DshDocumentObservation.SUCCESS, success.snapshot.observation)
        assertEquals(binding, success.snapshot.documentBinding)
    }

    @Test
    fun intermediateRedirectDoesNotSealTheDocumentStatus() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val navigationId = initialNavigation(machine)

        val redirect = machine.onEvent(status(navigationId, 302))
        assertTrue(redirect.effects.isEmpty())
        assertNull(redirect.snapshot.documentBinding)
        assertEquals(DshDocumentObservation.NOT_OBSERVED, redirect.snapshot.observation)

        assertRecovery(machine.onEvent(status(navigationId, 401)), expectedTokenRevision = 5L)
    }

    @Test
    fun conflictingBindingOrStatusDoesNotRecover() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val navigationId = initialNavigation(machine)

        machine.onEvent(status(navigationId, 200))
        val conflictingBinding = DshRequestDocumentBinding(
            requestId = DshNativeRef.of("other-request"),
            documentId = DshNativeRef.of("other-document"),
        )
        val conflict = machine.onEvent(
            DshNavigationAuthEvent.MainDocumentStatusObserved(
                attribution = attribution(navigationId, conflictingBinding),
                httpStatus = 401,
            ),
        )
        assertEquals(
            DshNavigationAuthEffect.ReportAttributionConflict(identity, navigationId),
            conflict.effects.single(),
        )

        val conflictingStatus = machine.onEvent(status(navigationId, 401))
        assertEquals(
            DshNavigationAuthEffect.ReportAttributionConflict(identity, navigationId),
            conflictingStatus.effects.single(),
        )
    }

    @Test
    fun lateEventsFromOldNavigationHaveNoEffect() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val oldNavigationId = initialNavigation(machine)
        val recovery = machine.onEvent(status(oldNavigationId, 401))
        val newNavigationId = recovery.snapshot.currentNavigationId!!
        assertEquals(1, recovery.effects.size)

        assertTrue(machine.onEvent(status(oldNavigationId, 200)).effects.isEmpty())
        assertTrue(
            machine.onEvent(
                DshNavigationAuthEvent.PageStopped(identity, oldNavigationId),
            ).effects.isEmpty(),
        )
        assertTrue(
            machine.onEvent(
                DshNavigationAuthEvent.ObservationUnavailable(
                    identity,
                    oldNavigationId,
                    DshObservationFailure.MISSING_STATUS,
                ),
            ).effects.isEmpty(),
        )
        assertEquals(newNavigationId, machine.snapshot.currentNavigationId)
    }

    @Test
    fun releaseRevokesAttributionAndDropsLateEvents() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val navigationId = initialNavigation(machine)
        machine.onEvent(status(navigationId, 401))

        val released = machine.onEvent(DshNavigationAuthEvent.SessionReleased(identity))
        assertTrue(released.snapshot.released)
        assertNull(released.snapshot.currentNavigationId)
        assertNull(released.snapshot.documentBinding)

        assertTrue(machine.onEvent(status(navigationId, 401)).effects.isEmpty())
        assertTrue(
            machine.onEvent(DshNavigationAuthEvent.ManualRetryRequested(identity)).effects.isEmpty(),
        )
        assertTrue(
            machine.onEvent(
                DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 6L),
            ).effects.isEmpty(),
        )
        assertTrue(
            machine.onEvent(DshNavigationAuthEvent.SessionReleased(identity)).effects.isEmpty(),
        )
    }

    @Test
    fun invalidAttributionAndMismatchedIdentityAreIgnored() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val navigationId = initialNavigation(machine)

        val unverified = DshNavigationAuthEvent.MainDocumentStatusObserved(
            attribution = attribution(navigationId, adapterVerified = false),
            httpStatus = 401,
        )
        val nonTopLevel = DshNavigationAuthEvent.MainDocumentStatusObserved(
            attribution = attribution(navigationId, topLevel = false),
            httpStatus = 401,
        )
        val otherIdentity = DshRenderIdentity(
            instanceRef = DshNativeRef.of("other-instance"),
            sessionRef = DshNativeRef.of("other-session"),
        )
        val wrongNavigation = DshNavigationAuthEvent.MainDocumentStatusObserved(
            attribution = attribution(navigationId + 1L),
            httpStatus = 401,
        )

        assertTrue(machine.onEvent(unverified).effects.isEmpty())
        assertTrue(machine.onEvent(nonTopLevel).effects.isEmpty())
        assertTrue(machine.onEvent(wrongNavigation).effects.isEmpty())
        assertTrue(machine.onEvent(status(navigationId, 401, otherIdentity)).effects.isEmpty())
        assertEquals(DshDocumentObservation.NOT_OBSERVED, machine.snapshot.observation)
    }

    @Test
    fun stopNeverInfersSuccessOrClosesObservation() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val navigationId = initialNavigation(machine)

        machine.onEvent(DshNavigationAuthEvent.PageStopped(identity, navigationId))
        assertEquals(DshDocumentObservation.NOT_OBSERVED, machine.snapshot.observation)

        val success = machine.onEvent(status(navigationId, 200))
        assertEquals(DshDocumentObservation.SUCCESS, success.snapshot.observation)
    }

    @Test
    fun startNavigationEffectCarriesExecutionGuardFields() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 9L)
        val initial = machine.onEvent(DshNavigationAuthEvent.InitialLoadRequested(identity))
        val start = initial.effects.single() as DshNavigationAuthEffect.StartNavigation

        assertEquals(identity, start.identity)
        assertEquals(initial.snapshot.currentNavigationId, start.navigationId)
        assertEquals(DshLoadCause.INITIAL, start.cause)
        assertEquals(9L, start.tokenRevision)
    }

    @Test
    fun tokenChangeBeforeDispatchReissuesAndStartedNavigationIsNotReloaded() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 1L)
        val initial = machine.onEvent(DshNavigationAuthEvent.InitialLoadRequested(identity))
        val firstStart = initial.effects.single() as DshNavigationAuthEffect.StartNavigation
        assertEquals(DshLoadUiPhase.AWAITING_DISPATCH, initial.snapshot.loadUiPhase)

        val replacement = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 2L),
        )
        val replacementStart =
            replacement.effects.single() as DshNavigationAuthEffect.StartNavigation
        assertEquals(DshLoadCause.INITIAL, replacementStart.cause)
        assertEquals(2L, replacementStart.tokenRevision)
        assertEquals(DshLoadUiPhase.AWAITING_DISPATCH, replacement.snapshot.loadUiPhase)
        assertTrue(replacementStart.navigationId > firstStart.navigationId)

        val staleAck = machine.onEvent(
            DshNavigationAuthEvent.NavigationDispatchAccepted(
                identity,
                firstStart.navigationId,
                firstStart.tokenRevision,
            ),
        )
        assertTrue(staleAck.effects.isEmpty())
        assertEquals(DshLoadUiPhase.AWAITING_DISPATCH, staleAck.snapshot.loadUiPhase)

        val wrongRevisionAck = machine.onEvent(
            DshNavigationAuthEvent.NavigationDispatchAccepted(
                identity,
                replacementStart.navigationId,
                tokenRevision = 1L,
            ),
        )
        assertTrue(wrongRevisionAck.effects.isEmpty())
        assertEquals(DshLoadUiPhase.AWAITING_DISPATCH, wrongRevisionAck.snapshot.loadUiPhase)

        acceptDispatch(machine, replacement)
        val startedNavigationId = replacementStart.navigationId

        val tokenAfterStart = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 3L),
        )
        assertTrue(tokenAfterStart.effects.isEmpty())
        assertEquals(DshLoadUiPhase.LOADING, tokenAfterStart.snapshot.loadUiPhase)
        assertEquals(startedNavigationId, tokenAfterStart.snapshot.currentNavigationId)

        assertRecovery(
            machine.onEvent(status(startedNavigationId, 401)),
            expectedTokenRevision = 3L,
        )
    }

    @Test
    fun staleOrUnavailableTokenNeverRearmsRecovery() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 1L)
        val initialNavigationId = initialNavigation(machine)
        val firstRecovery = machine.onEvent(status(initialNavigationId, 401))
        val recoveryNavigationId = firstRecovery.snapshot.currentNavigationId!!
        acceptDispatch(machine, firstRecovery)

        val exhausted = machine.onEvent(status(recoveryNavigationId, 401))
        assertTrue(exhausted.effects.isEmpty())
        assertTrue(exhausted.snapshot.recoveryConsumed)

        val stale = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 0L),
        )
        assertTrue(stale.effects.isEmpty())
        assertEquals(1L, stale.snapshot.tokenRevision)
        assertEquals(1L, stale.snapshot.lastValidTokenRevision)
        assertTrue(stale.snapshot.recoveryConsumed)
        assertTrue(machine.onEvent(status(recoveryNavigationId, 401)).effects.isEmpty())

        val unavailable = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = null),
        )
        assertTrue(unavailable.effects.isEmpty())
        assertNull(unavailable.snapshot.tokenRevision)
        assertEquals(1L, unavailable.snapshot.lastValidTokenRevision)
        assertTrue(unavailable.snapshot.recoveryConsumed)
        assertTrue(machine.onEvent(status(recoveryNavigationId, 401)).effects.isEmpty())

        val sameRevisionReturned = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 1L),
        )
        assertTrue(sameRevisionReturned.effects.isEmpty())
        assertEquals(1L, sameRevisionReturned.snapshot.tokenRevision)
        assertTrue(sameRevisionReturned.snapshot.recoveryConsumed)
        assertTrue(machine.onEvent(status(recoveryNavigationId, 401)).effects.isEmpty())

        val newer = machine.onEvent(
            DshNavigationAuthEvent.TokenChanged(identity, tokenRevision = 2L),
        )
        assertRecovery(newer, expectedTokenRevision = 2L)
    }

    @Test
    fun opaqueReferencesAndSnapshotsDoNotExposeRawValues() {
        val machine = DshNavigationAuthStateMachine(identity, initialTokenRevision = 5L)
        val initial = machine.onEvent(DshNavigationAuthEvent.InitialLoadRequested(identity))
        val rendered = listOf(
            identity.toString(),
            binding.toString(),
            initial.snapshot.toString(),
            initial.effects.toString(),
        ).joinToString("\n")

        assertFalse(rendered.contains("instance-raw"))
        assertFalse(rendered.contains("session-raw"))
        assertFalse(rendered.contains("request-raw"))
        assertFalse(rendered.contains("document-raw"))
        assertTrue(rendered.contains("redacted"))
    }

    private fun initialNavigation(machine: DshNavigationAuthStateMachine): Long {
        val transition = machine.onEvent(DshNavigationAuthEvent.InitialLoadRequested(identity))
        val start = transition.effects.single() as DshNavigationAuthEffect.StartNavigation
        acceptDispatch(machine, transition)
        return start.navigationId
    }

    private fun acceptDispatch(
        machine: DshNavigationAuthStateMachine,
        transition: DshNavigationAuthTransition,
    ): DshNavigationAuthTransition {
        val start = transition.effects.single() as DshNavigationAuthEffect.StartNavigation
        val accepted = machine.onEvent(
            DshNavigationAuthEvent.NavigationDispatchAccepted(
                identity = identity,
                navigationId = start.navigationId,
                tokenRevision = start.tokenRevision,
            ),
        )
        assertTrue(accepted.effects.isEmpty())
        assertEquals(DshLoadUiPhase.LOADING, accepted.snapshot.loadUiPhase)
        assertEquals(start.navigationId, accepted.snapshot.currentNavigationId)
        return accepted
    }

    private fun status(
        navigationId: Long,
        httpStatus: Int,
        eventIdentity: DshRenderIdentity = identity,
    ): DshNavigationAuthEvent.MainDocumentStatusObserved =
        DshNavigationAuthEvent.MainDocumentStatusObserved(
            attribution = attribution(navigationId, eventIdentity = eventIdentity),
            httpStatus = httpStatus,
        )

    private fun attribution(
        navigationId: Long,
        eventBinding: DshRequestDocumentBinding = binding,
        eventIdentity: DshRenderIdentity = identity,
        adapterVerified: Boolean = true,
        topLevel: Boolean = true,
    ): DshNativeDocumentAttribution =
        DshNativeDocumentAttribution(
            identity = eventIdentity,
            navigationId = navigationId,
            binding = eventBinding,
            isTopLevelMainFrame = topLevel,
            adapterVerified = adapterVerified,
        )

    private fun assertRecovery(
        transition: DshNavigationAuthTransition,
        expectedTokenRevision: Long,
    ) {
        val start = transition.effects.single() as DshNavigationAuthEffect.StartNavigation
        assertEquals(identity, start.identity)
        assertEquals(DshLoadCause.AUTH_RECOVERY, start.cause)
        assertEquals(expectedTokenRevision, start.tokenRevision)
        assertEquals(transition.snapshot.currentNavigationId, start.navigationId)
        assertTrue(transition.snapshot.recoveryConsumed)
        assertEquals(DshLoadUiPhase.AWAITING_DISPATCH, transition.snapshot.loadUiPhase)
    }
}
