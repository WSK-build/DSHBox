package com.dshbox.browser.runtime

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.UUID

internal data class BrowserBookmark(
    val id: String,
    val title: String,
    val url: String,
    val updatedAtMillis: Long,
)

/** Persists the user's Browser bookmarks independently from transient page state and history. */
internal class BrowserBookmarksStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun all(): List<BrowserBookmark> = readAll().orEmpty().sortedWith(
        compareByDescending<BrowserBookmark> { it.updatedAtMillis }.thenBy { it.title.lowercase() },
    )

    @Synchronized
    fun findByUrl(url: String): BrowserBookmark? = normalizeUrl(url)?.let { safeUrl ->
        readAll()?.firstOrNull { it.url == safeUrl }
    }

    fun supportsUrl(url: String): Boolean = normalizeUrl(url) != null

    @Synchronized
    fun save(url: String, title: String, id: String? = null): BrowserBookmark? {
        val safeUrl = normalizeUrl(url) ?: return null
        val safeTitle = title.filterNot { it <= '\u001f' || it == '\u007f' }
            .trim().take(MAX_TITLE_CHARS).ifBlank { safeUrl }
        val items = readAll()?.toMutableList() ?: return null
        val existingIndex = if (id == null) items.indexOfFirst { it.url == safeUrl }
        else items.indexOfFirst { it.id == id }
        if (id != null && existingIndex < 0) return null
        val now = System.currentTimeMillis()
        val bookmark = BrowserBookmark(
            id = if (existingIndex >= 0) items[existingIndex].id else UUID.randomUUID().toString().replace("-", ""),
            title = safeTitle,
            url = safeUrl,
            updatedAtMillis = now,
        )
        if (existingIndex >= 0) items[existingIndex] = bookmark
        else {
            if (items.size >= MAX_BOOKMARKS) return null
            items.add(bookmark)
        }
        return bookmark.takeIf { writeAll(items) }
    }

    @Synchronized
    fun delete(id: String): Boolean {
        if (!ID_PATTERN.matches(id)) return false
        val items = readAll()?.toMutableList() ?: return false
        if (!items.removeAll { it.id == id }) return false
        return writeAll(items)
    }

    private fun readAll(): List<BrowserBookmark>? {
        val raw = preferences.getString(KEY_BOOKMARKS, null) ?: return emptyList()
        if (raw.length > MAX_JSON_CHARS) return null
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return null
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val result = ArrayList<BrowserBookmark>(minOf(array.length(), MAX_BOOKMARKS))
        for (index in 0 until minOf(array.length(), MAX_BOOKMARKS)) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val title = item.optString("title").take(MAX_TITLE_CHARS)
            val url = normalizeUrl(item.optString("url")) ?: continue
            val updated = item.optLong("updatedAtMillis", 0L).coerceAtLeast(0L)
            if (ID_PATTERN.matches(id)) result.add(BrowserBookmark(id, title.ifBlank { url }, url, updated))
        }
        return result.distinctBy { it.id }.take(MAX_BOOKMARKS)
    }

    private fun writeAll(items: List<BrowserBookmark>): Boolean {
        val array = JSONArray()
        items.take(MAX_BOOKMARKS).forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("title", item.title.take(MAX_TITLE_CHARS))
                .put("url", item.url)
                .put("updatedAtMillis", item.updatedAtMillis))
        }
        val serialized = array.toString()
        if (serialized.length > MAX_JSON_CHARS) return false
        if (serialized.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return false
        return preferences.edit().putString(KEY_BOOKMARKS, serialized).commit()
    }

    private fun normalizeUrl(value: String): String? {
        if (value.isBlank() || value.length > MAX_URL_CHARS || value.any { it <= '\u001f' || it == '\u007f' }) return null
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        val encoded = URI(uri.toASCIIString())
        val authority = encoded.rawAuthority
        val normalizedAuthority = authority.substringBefore('%').lowercase(Locale.ROOT) +
            if ('%' in authority) "%${authority.substringAfter('%')}" else ""
        val ascii = buildString {
            append(encoded.scheme.lowercase(Locale.ROOT))
            append("://")
            append(normalizedAuthority)
            append(encoded.rawPath.orEmpty())
            encoded.rawQuery?.let { append('?'); append(it) }
            encoded.rawFragment?.let { append('#'); append(it) }
        }
        if (ascii.toByteArray(Charsets.UTF_8).size > MAX_URL_BYTES) return null
        return ascii
    }

    private companion object {
        const val PREFS_NAME = "browser_bookmarks"
        const val KEY_BOOKMARKS = "entries_v1"
        const val MAX_BOOKMARKS = 500
        const val MAX_TITLE_CHARS = 256
        const val MAX_URL_CHARS = 2048
        const val MAX_URL_BYTES = 2048
        const val MAX_JSON_CHARS = 2 * 1024 * 1024
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        val ID_PATTERN = Regex("^[a-f0-9]{32}$")
    }
}
