package com.dshbox.browser.runtime

import org.json.JSONObject

/** Distinguishes an accepted destination from native evidence of a committed main document. */
internal object BrowserNavigationMetadata {
    fun addTo(data: JSONObject, state: BrowserPageStateTracker.State?): JSONObject {
        fun url(name: String, raw: String?) {
            val safe = BrowserPageStateTracker.publicUrl(raw)
            data.put(name, safe.value ?: JSONObject.NULL)
                .put(name + "QueryRedacted", safe.queryRedacted)
                .put(name + "FragmentRedacted", safe.fragmentRedacted)
        }
        url("urlBefore", state?.urlBefore)
        url("requestedUrl", state?.requestedUrl)
        url("urlAfter", state?.urlAfter)
        return data.put("committed", state?.committed == true)
    }
}
