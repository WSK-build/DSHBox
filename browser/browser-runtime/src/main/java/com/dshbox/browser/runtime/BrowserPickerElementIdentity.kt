package com.dshbox.browser.runtime

import org.json.JSONObject
import java.util.UUID

/** Retains the selected DOM node while the comment keyboard changes viewport geometry. */
internal class BrowserPickerElementIdentity {
    private val key = JSONObject.quote("__dshPicker_" + UUID.randomUUID().toString().replace("-", ""))

    fun rememberScript(element: String): String = "window[$key]=$element;"

    fun resolveScript(x: Float, y: Float, allowRelayout: Boolean): String {
        val hit = if (allowRelayout) "n" else
            "document.elementFromPoint(${x.toDouble()},${y.toDouble()})"
        return "(() => {const n=window[$key];return n&&n.isConnected&&n.ownerDocument===document&&($hit)===n?n:null;})()"
    }

    fun clearScript(): String = "delete window[$key];"
}
