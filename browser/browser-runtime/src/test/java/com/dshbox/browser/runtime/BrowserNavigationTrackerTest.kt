package com.dshbox.browser.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationTrackerTest {
    @Test
    fun callbacksFromPreviousPageCannotCompleteTheCurrentAttempt() {
        val tracker = BrowserNavigationTracker<String, String>()
        val firstPage = String(charArrayOf('a'))
        val secondPage = String(charArrayOf('b'))
        val first = tracker.begin(firstPage, "token-a", "https://a.test/")
        tracker.bindStart(firstPage, "https://a.test/", "https://a.test/")
        val second = tracker.begin(secondPage, "token-b", "https://b.test/")
        tracker.bindStart(secondPage, "https://b.test/", "https://b.test/")

        assertNotSame(first, second)
        assertFalse(tracker.mayComplete(first, firstPage, "https://a.test/", "https://a.test/", 100, "complete"))
        assertFalse(tracker.mayFail(first, firstPage, "https://a.test/", "https://a.test/"))
        assertTrue(tracker.mayComplete(second, secondPage, "https://b.test/", "https://b.test/", 100, "complete"))
    }

    @Test
    fun sameUrlOlderCallbackCannotFinishWhileCurrentDocumentIsLoading() {
        val tracker = BrowserNavigationTracker<String, String>()
        val page = String(charArrayOf('p'))
        val first = tracker.begin(page, "token-a", "https://same.test/")
        tracker.bindStart(page, "https://same.test/", "https://same.test/")
        val second = tracker.begin(page, "token-b", "https://same.test/")
        tracker.bindStart(page, "https://same.test/", "https://same.test/")

        assertFalse(tracker.mayFail(second, page, "https://same.test/", "https://same.test/"))
        assertFalse(tracker.mayComplete(second, page, "https://same.test/", "https://same.test/", 40, "loading"))
        assertTrue(tracker.mayComplete(second, page, "https://same.test/", "https://same.test/", 100, "complete"))
        assertTrue(tracker.clear(second))
        assertFalse(tracker.clear(first))
        assertSame(null, tracker.current())
    }
}
