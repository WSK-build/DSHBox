package com.dshbox.browser.runtime

import java.util.UUID

/** Owns the bounded set of page identities and keeps one stable active page reference. */
internal class BrowserPageManager<T>(private val maximumPages: Int = MAX_PAGES) {
    data class Page<T>(
        val id: String,
        val content: T,
        var title: String = "",
        var url: String = "about:blank",
    )

    data class CloseResult<T>(val removed: Page<T>, val active: Page<T>)

    private val pages = LinkedHashMap<String, Page<T>>()
    var activePageId: String? = null
        private set

    val size: Int get() = pages.size
    val active: Page<T>? get() = activePageId?.let(pages::get)
    val all: List<Page<T>> get() = pages.values.toList()

    fun add(content: T, title: String = "", url: String = "about:blank"): Page<T>? {
        if (pages.size >= maximumPages) return null
        val id = newPageId()
        return Page(id, content, title, url).also {
            pages[id] = it
            activePageId = id
        }
    }

    fun switchTo(id: String): Page<T>? = pages[id]?.also { activePageId = it.id }

    fun page(id: String): Page<T>? = pages[id]

    fun update(content: T, title: String, url: String) {
        pages.values.firstOrNull { it.content == content }?.apply {
            this.title = title
            this.url = url
        }
    }

    fun close(id: String): CloseResult<T>? {
        if (pages.size <= 1) return null
        val removed = pages.remove(id) ?: return null
        if (activePageId == id) activePageId = pages.values.last().id
        val current = active ?: return null
        return CloseResult(removed, current)
    }

    fun remove(content: T): CloseResult<T>? {
        val id = pages.values.firstOrNull { it.content == content }?.id ?: return null
        return close(id)
    }

    fun clear(): List<Page<T>> {
        val removed = pages.values.toList()
        pages.clear()
        activePageId = null
        return removed
    }

    private fun newPageId(): String {
        var id: String
        do {
            id = "pg-" + UUID.randomUUID().toString().replace("-", "").take(20)
        } while (pages.containsKey(id))
        return id
    }

    companion object {
        const val MAX_PAGES = 4
    }
}

internal data class BrowserPageCommandResult(
    val code: String,
    val executed: Boolean?,
    val data: org.json.JSONObject,
    val contextChanged: Boolean = false,
)
