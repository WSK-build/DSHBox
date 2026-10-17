package com.dshbox.browser.runtime

import android.graphics.Bitmap
import com.dshbox.browser.api.BrowserOperationToken
import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.json.JSONObject

/**
 * Execution callbacks that genuinely need a visible Browser window or user input. The mounted
 * activity registers one so the background session can reconnect to the full, existing handlers
 * (upload activate/submit/chooser, page UI, visual input) without duplicating them. When no host
 * is mounted these operations must fail closed with an explicit "requires visible UI" code rather
 * than starting the activity or faking success.
 */
internal interface BrowserSessionUiHost {
    /** Ends the window after its session-owned surfaces have been disposed. */
    fun onSessionClosed(reason: com.dshbox.browser.api.BrowserCloseReason)

    /** True while a user control-transfer confirmation is pending: mutating tools must pause. */
    val controlTransferPending: Boolean

    /** Notes AI tool activity for the on-screen indicator while a window is mounted. */
    fun onAiToolActivity()

    /** Installs the mounted window's page callbacks on a surface (keeps app-context creation). */
    fun installMountedCallbacks(view: android.webkit.WebView)

    /** Full page command handling when a window is mounted; null if the host cannot serve it. */
    fun executePage(action: String, args: JSONObject): BrowserPageCommandResult?

    /** Full `browser_file` handling when a window is mounted; false if not served. */
    fun executeFile(
        action: String,
        args: JSONObject,
        deadlineElapsedMillis: Long,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ): Boolean

    /** Full `browser_upload` handling when a window is mounted; false if not served. */
    fun executeUpload(
        request: BrowserCommandRequest,
        identity: BrowserTaskPageIdentity,
        token: BrowserOperationToken,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ): Boolean

    /** Full advanced debug/network handling when a window is mounted; false if not served. */
    fun executeAdvanced(
        family: String,
        args: JSONObject,
        deadlineElapsedMillis: Long,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ): Boolean

    /** Human-visible screenshot capture when a window is mounted; false if not served. */
    fun capture(deadlineElapsedMillis: Long, callback: (Bitmap?, String) -> Unit): Boolean
}
