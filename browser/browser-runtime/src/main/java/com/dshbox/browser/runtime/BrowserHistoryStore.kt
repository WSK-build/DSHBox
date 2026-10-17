package com.dshbox.browser.runtime

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Bounded history codec preserving the existing id/url/title storage format and order. */
internal object BrowserHistoryStore {
    data class Entry(val id: String, val url: String, val title: String)
    const val MAX_ENTRIES = 200
    private const val MAX_INPUT_CHARS = 4 * 1024 * 1024

    fun decode(raw: String?): MutableList<Entry> {
        if (raw == null || raw.length > MAX_INPUT_CHARS) return mutableListOf()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return mutableListOf()
        val entries = (0 until minOf(array.length(), MAX_ENTRIES * 2)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.opt("id") as? String ?: return@mapNotNull null
            val url = item.opt("url") as? String ?: return@mapNotNull null
            val title = item.opt("title") as? String ?: return@mapNotNull null
            Entry(id, url, title)
        }
        return normalize(entries).toMutableList()
    }

    fun encode(entries: List<Entry>): String = JSONArray(normalize(entries).map { entry ->
        JSONObject().put("id", entry.id).put("url", entry.url).put("title", entry.title)
    }).toString()

    private fun normalize(entries: List<Entry>): List<Entry> {
        val ids = mutableSetOf<String>()
        val urls = mutableSetOf<String>()
        val result = mutableListOf<Entry>()
        for (entry in entries.take(MAX_ENTRIES * 2)) {
            if (!entry.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) ||
                entry.url.length !in 1..2048 || entry.url.any { it.isISOControl() }) continue
            val uri = runCatching { URI(entry.url) }.getOrNull() ?: continue
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) continue
            if (entry.id in ids || entry.url in urls) continue
            ids += entry.id
            urls += entry.url
            result += entry.copy(title = entry.title.take(256))
            if (result.size == MAX_ENTRIES) break
        }
        return result
    }
}
