package com.dshbox.browser.runtime

import org.json.JSONObject

/** Reduces tool responses to user-facing status and safe scalar fields; raw JSON stays opt-in. */
internal object BrowserAdvancedResultFormatter {
    data class Field(val key: String, val value: String)
    data class Formatted(
        val success: Boolean,
        val code: String,
        val fields: List<Field>,
        val rawJson: String,
    )

    private val visibleFields = listOf(
        "message", "reason", "url", "title", "owner", "connected", "enabled", "state",
        "count", "matched", "width", "height", "mobile", "durationMillis", "entryCount",
        "requestCount", "pausedCount", "mimeType", "error",
    )

    fun format(result: JSONObject): Formatted {
        val code = result.optString("code", "UNKNOWN").ifBlank { "UNKNOWN" }
        val data = result.optJSONObject("data") ?: JSONObject()
        val fields = visibleFields.mapNotNull { key ->
            if (!data.has(key) || data.isNull(key)) return@mapNotNull null
            val value = data.opt(key)
            if (value is JSONObject || value is org.json.JSONArray) return@mapNotNull null
            Field(key, value.toString().take(240))
        }
        val resultMessage = result.optString("message").ifBlank { result.optString("error") }
        val finalFields = if (resultMessage.isBlank()) fields else listOf(Field("message", resultMessage.take(240))) + fields
        return Formatted(
            success = code == "OK",
            code = code,
            fields = finalFields.distinctBy { it.key },
            rawJson = runCatching { result.toString(2) }.getOrDefault(result.toString()),
        )
    }
}
