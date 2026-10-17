package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserTaskPageIdentity

internal data class BrowserControlApprovalHostState(
    val identity: BrowserTaskPageIdentity?,
    val enabled: Boolean,
    val owner: BrowserOwner,
    val lifecycle: BrowserLifecycle,
)

/** Pure admission and authority checks for one-shot Browser control approval. */
internal object BrowserControlApprovalPolicy {
    fun cancelError(wasPending: Boolean, status: BrowserControlApprovalStatus): String? = when {
        wasPending && status == BrowserControlApprovalStatus.CANCELLED -> null
        status == BrowserControlApprovalStatus.EXPIRED -> "APPROVAL_EXPIRED"
        else -> "APPROVAL_NOT_PENDING"
    }

    fun requestError(state: BrowserControlApprovalHostState): String? = when {
        state.lifecycle != BrowserLifecycle.ACTIVE || state.identity == null -> "NO_ACTIVE_SESSION"
        state.enabled && state.owner == BrowserOwner.AI -> "AI_CONTROL_ALREADY_ENABLED"
        state.owner == BrowserOwner.HUMAN -> null
        state.owner == BrowserOwner.AI -> "NO_ACTIVE_SESSION"
        else -> "NO_ACTIVE_SESSION"
    }

    fun mayGrant(approval: BrowserControlApproval, nonce: String, state: BrowserControlApprovalHostState,
        nowElapsedMillis: Long): Boolean = approval.status == BrowserControlApprovalStatus.PENDING &&
        approval.nonce == nonce && nowElapsedMillis < approval.expiresAtElapsedMillis &&
        approval.identity == state.identity && state.lifecycle == BrowserLifecycle.ACTIVE &&
        state.owner == BrowserOwner.HUMAN
}
