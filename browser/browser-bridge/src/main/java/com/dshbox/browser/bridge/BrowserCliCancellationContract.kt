package com.dshbox.browser.bridge

import org.json.JSONObject

/** Owns the Broker's cancellation receipt and pre-dispatch terminal shapes. */
internal object BrowserCliCancellationContract {
    private val stages = setOf(
        "queued", "transport", "capture_wait", "event_wait", "condition_wait", "dispatch",
    )
    private val receiptReasons = setOf(
        "cancel_accepted", "cancel_before_admission", "request_in_flight", "request_not_found",
        "request_finished", "transport_unavailable", "invalid_runtime_receipt",
    )

    fun receipt(stage: String, reason: String): JSONObject {
        require(stage in stages) { "invalid cancellation stage" }
        require(reason in receiptReasons) { "invalid cancellation reason" }
        return JSONObject()
            .put("receiptKind", "cancel_request")
            .put("cancelStage", stage)
            .put("reason", reason)
            .put("actionOutcome", "unknown")
            .put("nextAction", "read_original_request_result")
            .put("automaticRetryAllowed", false)
    }

    fun preDispatchTerminal(): JSONObject = JSONObject()
        .put("cancelStage", "queued")
        .put("reason", "cancel_before_dispatch")
        .put("actionOutcome", "not_dispatched")
        .put("automaticRetryAllowed", false)
}
