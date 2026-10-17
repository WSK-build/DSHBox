package com.dshbox.browser.runtime

/**
 * Page-set mutations that prepare the host reparent BEFORE changing manager state, so a failed
 * reparent (a capture still owns the WebView) leaves the page manager unchanged — never removing a
 * page or moving the active pointer without a mounted surface. Pure and unit-testable.
 */
internal object BrowserPageTransactions {
    /** Activates [targetId], calling [prepare] first; the manager is untouched if prepare is false. */
    fun <T> activate(
        manager: BrowserPageManager<T>,
        targetId: String,
        prepare: (T) -> Boolean,
    ): Boolean {
        val page = manager.all.firstOrNull { it.id == targetId } ?: return false
        if (manager.activePageId == targetId) return true
        if (!prepare(page.content)) return false
        return manager.switchTo(targetId) != null
    }

    data class Closed<T>(val result: BrowserPageManager.CloseResult<T>, val wasActive: Boolean)

    /**
     * Closes [pageId]; if it is the active page, reparents to the successor first. Returns null and
     * leaves the manager unchanged when the page can't be closed or the reparent is refused.
     */
    fun <T> close(
        manager: BrowserPageManager<T>,
        pageId: String,
        prepare: (T) -> Boolean,
    ): Closed<T>? {
        if (manager.size <= 1) return null
        if (manager.all.none { it.id == pageId }) return null
        val wasActive = manager.activePageId == pageId
        val next = if (wasActive) manager.all.lastOrNull { it.id != pageId } else null
        if (wasActive && next != null && !prepare(next.content)) return null
        val result = manager.close(pageId) ?: return null
        return Closed(result, wasActive)
    }
}
