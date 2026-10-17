package com.dshbox.browser.runtime

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import com.dshbox.browser.api.BrowserContract
import com.dshbox.browser.api.BrowserHomeStatus
import com.dshbox.browser.api.BrowserIdlePolicy
import com.dshbox.browser.api.BrowserSnapshot
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Builds the canonical Browser status JSON. Pure so the wire shape (state/owner/visible/idle/
 * closeReason) is unit-testable and identical for the background session and the mounted window.
 */
internal fun browserStatusJson(snapshot: BrowserSnapshot, automationEnabled: Boolean, nowMillis: Long): String {
    val status = when (snapshot.homeStatus()) {
        BrowserHomeStatus.RUNNING -> BrowserContract.STATUS_RUNNING
        BrowserHomeStatus.IDLE -> BrowserContract.STATUS_IDLE
        null -> BrowserContract.STATUS_CLOSED
    }
    return JSONObject()
        .put("state", status)
        .put("automationEnabled", automationEnabled)
        .put("sessionGeneration", snapshot.sessionGeneration)
        .put("controlGeneration", snapshot.controlGeneration)
        .put("owner", snapshot.owner.name.lowercase(Locale.ROOT))
        .put("visible", snapshot.visible)
        .put(
            "idleRemainingMs",
            BrowserIdlePolicy(snapshot.idleTimeoutMillis).remainingMillis(snapshot, nowMillis) ?: JSONObject.NULL,
        )
        .put("lastCloseReason", snapshot.closeReason.name.lowercase(Locale.ROOT))
        .toString()
}

/**
 * Single status publisher shared by the background session and the mounted activity: writes the
 * atomic run/status.json mirror (which the broker reads), preferences, and the ACTION_STATUS
 * broadcast. Neither side keeps a second divergent implementation.
 */
internal class BrowserStatusPublisher(
    private val context: Context,
    private val runDirectory: File,
    private val elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val preferences = BrowserRuntimeSettings.preferences(context)

    fun publish(snapshot: BrowserSnapshot, openRequestId: String? = null): String {
        val automationEnabled = preferences.getBoolean(
            BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED,
        )
        val statusJson = browserStatusJson(snapshot, automationEnabled, elapsedRealtime())
        preferences.edit()
            .putString(BrowserRuntimeSettings.KEY_LAST_STATUS_JSON, statusJson)
            .putLong(BrowserRuntimeSettings.KEY_LAST_SESSION_GENERATION, snapshot.sessionGeneration)
            .putLong(BrowserRuntimeSettings.KEY_LAST_CONTROL_GENERATION, snapshot.controlGeneration)
            .apply()
        val status = runCatching { JSONObject(statusJson).optString("state") }
            .getOrDefault(BrowserContract.STATUS_CLOSED)
        val intent = Intent(BrowserContract.ACTION_STATUS)
            .setPackage(context.packageName)
            .putExtra(BrowserContract.EXTRA_STATUS, status)
            .putExtra(BrowserContract.EXTRA_STATUS_JSON, statusJson)
        openRequestId?.let { intent.putExtra(BrowserRuntimeSettings.EXTRA_OPEN_REQUEST_ID, it) }
        publishBrowserStatusChannels(
            statusJson,
            writeMirror = ::writeMirror,
            broadcast = { context.sendBroadcast(intent, BrowserContract.internalPermission(context.packageName)) },
        )
        return statusJson
    }

    private fun writeMirror(json: String): Boolean {
        if (!runDirectory.exists() && !runDirectory.mkdirs()) return false
        val atomicFile = AtomicFile(File(runDirectory, "status.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(json.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            return true
        } catch (_: Exception) {
            stream?.let { atomicFile.failWrite(it) }
            return false
        }
    }
}

/** The persistent mirror is a fallback; its failure must not suppress the live status channel. */
internal fun publishBrowserStatusChannels(
    json: String,
    writeMirror: (String) -> Boolean,
    broadcast: (String) -> Unit,
): Boolean {
    val persisted = runCatching { writeMirror(json) }.getOrDefault(false)
    broadcast(json)
    return persisted
}
