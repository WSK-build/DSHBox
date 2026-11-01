package com.dshbox.dsh.renderer.gecko

/**
 * Serializes extension status ingestion onto the main thread.
 *
 * Gecko's message callbacks are not treated as an ownership boundary. Every
 * correlator read/write and observation callback runs in the posted task.
 */
class GeckoStatusMessageDispatcher(
    private val correlator: GeckoNavigationStatusCorrelator,
    private val postToMain: (() -> Unit) -> Unit,
    private val isActive: () -> Boolean,
    private val onObservation: (GeckoNavigationStatusCorrelator.Observation) -> Unit,
) {
    fun submitHeaders(message: GeckoNavigationStatusCorrelator.Message) {
        submit { correlator.onHeadersReceived(message) }
    }

    fun submitDocumentStatus(message: GeckoNavigationStatusCorrelator.Message) {
        submit { correlator.onDocumentStatus(message) }
    }

    private fun submit(observation: () -> GeckoNavigationStatusCorrelator.Observation?) {
        postToMain {
            if (!isActive()) return@postToMain
            val current = observation() ?: return@postToMain
            if (correlator.isCurrent(current)) onObservation(current)
        }
    }
}
