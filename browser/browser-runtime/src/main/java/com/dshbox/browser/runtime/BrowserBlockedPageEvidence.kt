package com.dshbox.browser.runtime

import org.json.JSONArray
import org.json.JSONObject

/** Reports bounded hints from observed content; absence of hints never proves access is unrestricted. */
internal object BrowserBlockedPageEvidence {
    private val verification = Regex("captcha|人机(?:验证|校验)|安全验证|verify you are human|human verification|robot check|vérification humaine|verificación humana|menschliche überprüfung|проверка.*человек", RegexOption.IGNORE_CASE)
    private val restricted = Regex("access denied|403 forbidden|访问受限|访问被拒绝|accès refusé|acceso denegado|zugriff verweigert|доступ запрещ", RegexOption.IGNORE_CASE)

    fun inspect(data: JSONObject): JSONObject {
        val signals = JSONArray()
        val seen = mutableSetOf<String>()
        fun add(kind: String, source: String, evidence: String) {
            if (signals.length() < 8 && seen.add("$kind:$source")) {
                signals.put(JSONObject().put("kind", kind).put("source", source).put("evidence", evidence))
            }
        }
        fun check(source: String, text: String) {
            val bounded = text.take(4096)
            if (verification.containsMatchIn(bounded)) add("verification", source, "keyword_match")
            if (restricted.containsMatchIn(bounded)) add("access_restricted", source, "keyword_match")
        }
        check("title", data.optString("title").take(256))
        check("text", data.optString("text"))
        val overlays = data.optJSONArray("overlays") ?: JSONArray()
        for (index in 0 until minOf(overlays.length(), 8)) {
            val overlay = overlays.optJSONObject(index) ?: continue
            check("overlay", overlay.optString("name").take(160))
            val pseudo = overlay.optJSONArray("pseudo") ?: JSONArray()
            for (pseudoIndex in 0 until minOf(pseudo.length(), 2)) {
                check("overlay", pseudo.optJSONObject(pseudoIndex)?.optString("boundedContent").orEmpty().take(160))
            }
        }
        val elements = data.optJSONArray("elements") ?: JSONArray()
        for (index in 0 until minOf(elements.length(), 80)) {
            val element = elements.optJSONObject(index) ?: continue
            if (element.optString("tag") == "input" && element.optString("type") == "password") {
                add("authentication", "elements", "password_field_present")
                break
            }
        }
        return JSONObject().put("assessment", if (signals.length() > 0) "possible" else "not_detected")
            .put("signals", signals).put("automaticResolutionAllowed", false)
            .put("nextAction", if (signals.length() > 0) "inspect_and_request_user_if_needed" else "continue_observing")
    }
}
