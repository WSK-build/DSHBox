package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserOperationToken
import com.dshbox.browser.api.BrowserSnapshot
import java.util.concurrent.atomic.AtomicBoolean

internal class BrowserUploadOperationFinalizer(
    private val token: BrowserOperationToken,
    private val completeOperation: (BrowserOperationToken, Boolean) -> Boolean,
    private val snapshot: () -> BrowserSnapshot,
    private val publishSnapshot: (BrowserSnapshot) -> Unit,
) {
    private val finished = AtomicBoolean(false)

    fun finish(effective: Boolean, callback: (completed: Boolean, snapshot: BrowserSnapshot) -> Unit): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        val completed = completeOperation(token, effective)
        val current = snapshot()
        publishSnapshot(current)
        callback(completed, current)
        return true
    }
}

internal fun browserUploadOutcome(
    code: String,
    executed: Boolean?,
    authorized: Boolean,
    tokenCompleted: Boolean,
): Pair<String, Boolean?> = if (executed == true && (!authorized || !tokenCompleted)) {
    "UNKNOWN" to null
} else if (executed == null) {
    "UNKNOWN" to null
} else {
    code to executed
}
