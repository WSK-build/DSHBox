package com.dshbox.browser.runtime

import org.json.JSONObject

/** Declares the actual condition-wait scope without implying whole-page DOM coverage. */
internal object BrowserWaitScopeDiagnostics {
    fun data(condition: String): JSONObject {
        val topFrameDom = condition == "selector" || condition == "text"
        return JSONObject()
            .put("kind", if (topFrameDom) "top_frame" else "page_navigation")
            .put("topFrameOnly", true)
            .put("frameTraversal", "not_attempted")
            .put("conditionCoverage", "declared_scope")
            .put("wholePageCoverage", if (topFrameDom) "partial" else "not_assessed")
    }
}
