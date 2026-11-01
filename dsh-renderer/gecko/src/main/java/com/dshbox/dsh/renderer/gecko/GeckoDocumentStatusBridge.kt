package com.dshbox.dsh.renderer.gecko

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dshbox.dsh.renderer.api.DshOriginPolicy
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Owns the bundled Gecko extension. Its isolated content script reports final
 * main-document status, while its MAIN-world content script installs the APP
 * page compatibility resource at document_start.
 *
 * Raw messages and URLs are never logged. A document-status message must come
 * from this exact session and top-level frame; background header messages are
 * accepted only when their navigation nonce still identifies this bridge.
 */
internal class GeckoDocumentStatusBridge(
    private val runtime: GeckoRuntime,
    private val session: GeckoSession,
    private val originPolicy: DshOriginPolicy,
    private val correlator: GeckoNavigationStatusCorrelator,
    private val mainHandler: Handler,
    private val isActive: () -> Boolean,
    private val onReady: () -> Unit,
    private val onUnavailable: () -> Unit,
    private val onObservation: (GeckoNavigationStatusCorrelator.Observation) -> Unit,
) {
    private var released = false
    private var starting = false
    private var extension: WebExtension? = null
    private var ready = false
    private var failed = false
    private val startupTimeout = Runnable {
        if (released || ready || failed) return@Runnable
        starting = false
        failed = true
        Log.w(LOG_TAG, "Status extension startup timed out")
        onUnavailable()
    }
    private val dispatcher = GeckoStatusMessageDispatcher(
        correlator = correlator,
        postToMain = { task -> mainHandler.post(task) },
        isActive = isActive,
        onObservation = onObservation,
    )

    val isReady: Boolean
        get() = !released && ready

    val isUnavailable: Boolean
        get() = !released && failed

    fun start() {
        if (released || starting || extension != null) return
        starting = true
        mainHandler.postDelayed(startupTimeout, STARTUP_TIMEOUT_MILLIS)
        runtime.webExtensionController.ensureBuiltIn(
            EXTENSION_BASE_URL,
            EXTENSION_ID,
        ).accept({ installed ->
            dispatchOnMain {
                starting = false
                if (released) return@dispatchOnMain
                val installedExtension = installed ?: run {
                    failWithoutExtension("Status extension was not returned")
                    return@dispatchOnMain
                }
                mainHandler.removeCallbacks(startupTimeout)
                extension = installedExtension
                val delegate = Delegate()
                installedExtension.setMessageDelegate(delegate, NATIVE_APPLICATION)
                session.webExtensionController.setMessageDelegate(
                    installedExtension,
                    delegate,
                    NATIVE_APPLICATION,
                )
                ready = true
                onReady()
            }
        }, { failure ->
            dispatchOnMain {
                starting = false
                if (released) return@dispatchOnMain
                failWithoutExtension(
                    "Status extension unavailable: " +
                        (failure?.javaClass?.simpleName ?: "unknown"),
                )
            }
        })
    }

    fun release() {
        released = true
        starting = false
        ready = false
        extension = null
        mainHandler.removeCallbacks(startupTimeout)
    }

    private fun failWithoutExtension(message: String) {
        if (released || ready || failed) return
        mainHandler.removeCallbacks(startupTimeout)
        starting = false
        failed = true
        Log.w(LOG_TAG, message)
        onUnavailable()
    }

    private fun dispatchOnMain(task: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
        } else {
            mainHandler.post(task)
        }
    }

    private inner class Delegate : WebExtension.MessageDelegate {
        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: WebExtension.MessageSender,
        ): GeckoResult<Any>? {
            if (released || nativeApp != NATIVE_APPLICATION) return null
            val payload = message.asJsonObject() ?: return null
            when (payload.optString("kind")) {
                "headers" -> acceptHeaders(payload, sender)
                "document-status" -> acceptDocumentStatus(payload, sender)
            }
            return ACK
        }
    }

    private fun acceptHeaders(payload: JSONObject, sender: WebExtension.MessageSender) {
        val senderSession = sender.session
        if (senderSession != null && senderSession !== session) return
        // background 页面来源的消息 sender.session 为 null，无法做顶层判定，
        // 此时只能依赖 nonce + 可信 URL 校验；若带有 session 则必须为顶层，
        // 防止子框架消息冒充主文档。
        if (senderSession != null && !sender.isTopLevel()) return
        val url = payload.optString("url")
        if (!originPolicy.isTrusted(url)) return
        dispatcher.submitHeaders(
            GeckoNavigationStatusCorrelator.Message(
                kind = GeckoNavigationStatusCorrelator.MessageKind.HEADERS,
                requestId = payload.optString("requestId"),
                nonce = payload.optString("nonce").takeIf { it.isNotBlank() && it != "null" },
                url = url,
                httpStatus = payload.optInt("status", -1).takeIf { it in 100..599 },
                documentId = null,
                isTopLevelMainFrame = payload.optInt("frameId", -1) == 0,
            ),
        )
    }

    private fun acceptDocumentStatus(
        payload: JSONObject,
        sender: WebExtension.MessageSender,
    ) {
        if (sender.session !== session || !sender.isTopLevel()) return
        val senderUrl = sender.url
        if (!originPolicy.isTrusted(senderUrl)) return
        val url = payload.optString("url")
        if (!originPolicy.isTrusted(url)) return
        dispatcher.submitDocumentStatus(
            GeckoNavigationStatusCorrelator.Message(
                kind = GeckoNavigationStatusCorrelator.MessageKind.DOCUMENT_STATUS,
                requestId = payload.optString("requestId"),
                nonce = payload.optString("nonce").takeIf { it.isNotBlank() && it != "null" },
                url = url,
                httpStatus = payload.optInt("status", -1).takeIf { it in 100..599 },
                documentId = payload.optString("documentId")
                    .takeIf { it.isNotBlank() && it != "null" },
                isTopLevelMainFrame = payload.optInt("frameId", -1) == 0,
            ),
        )
    }

}

private fun Any?.asJsonObject(): JSONObject? = when (this) {
    is JSONObject -> this
    is String -> runCatching { JSONObject(this) }.getOrNull()
    is Map<*, *> -> runCatching { JSONObject(this) }.getOrNull()
    else -> null
}

private val ACK = GeckoResult.fromValue<Any>("ack")

private const val EXTENSION_ID = "dshbox-status-bridge@dshbox.invalid"
private const val EXTENSION_BASE_URL = "resource://android/assets/dsh-status-bridge/"
private const val NATIVE_APPLICATION = "dshbox-status"
private const val LOG_TAG = "DshGeckoStatus"
private const val STARTUP_TIMEOUT_MILLIS = 10_000L
