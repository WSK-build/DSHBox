package com.dshbox.browser.runtime

/** Read-only recovery instructions never authorize replay of a browser effect. */
internal object BrowserTaskErrorGuidance {
    data class Advice(val stage: String, val reason: String, val nextAction: String)

    fun forCode(code: String): Advice? = when (code) {
        "TASK_PAGE_MISMATCH" -> Advice("task_binding", "PAGE_IDENTITY_CHANGED",
            "read_task_status_then_observe_and_reconcile")
        "TASK_UNKNOWN_EFFECT_UNVERIFIED" -> Advice("effect_verification", "UNKNOWN_EFFECT_REQUIRES_VERIFICATION",
            "inspect_effects_and_request_user_verification")
        "TASK_STALE" -> Advice("task_revision", "REVISION_CHANGED", "read_task_status_before_updating")
        else -> null
    }
}
