package com.dshbox.browser.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BrowserPageStateTrackerTest {
    @Test fun callbackInstallationPreservesExistingNavigationAndInitializesOnlyNewPages() {
        val tracker = BrowserPageStateTracker<Any>()
        val existing = Any()
        val state = tracker.begin(existing, "https://example.test/live")
        state.loadState = BrowserPageStateTracker.LoadState.FINISHED
        state.currentUrl = "https://example.test/live"
        assertSame(state, tracker.ensure(existing, "about:blank"))
        assertEquals(BrowserPageStateTracker.LoadState.FINISHED, tracker.state(existing)?.loadState)
        assertEquals("https://example.test/live", tracker.state(existing)?.currentUrl)
        val fresh = tracker.ensure(Any(), "about:blank")
        assertTrue(fresh.navigationId > state.navigationId)
        assertEquals("about:blank", fresh.requestedUrl)
    }

    @Test
    fun verifiedSameDocumentBackHasFreshCommittedMetadata() {
        val tracker = BrowserPageStateTracker<Any>()
        val target = Any()
        val original = tracker.begin(target, "https://example.test/a#two")
        val committed = tracker.sameDocumentCommitted(target, "https://example.test/a#two", "https://example.test/a#one")!!
        assertTrue(committed.navigationId > original.navigationId)
        assertTrue(committed.committed)
        assertEquals("https://example.test/a#two", committed.urlBefore)
        assertEquals("https://example.test/a#one", committed.urlAfter)
        assertEquals(BrowserPageStateTracker.LoadState.FINISHED, committed.loadState)
        assertNull(tracker.sameDocumentCommitted(Any(), "before", "after"))
    }

    @Test
    fun mainFrameFailureBeforePageStartedIsRecordedWithoutAcceptingPreviousNavigationErrors() {
        listOf(null, "about:blank", "chrome-error://chromewebdata/", "https://missing.invalid/").forEach { actualUrl ->
            val tracker = BrowserPageStateTracker<Any>()
            val page = Any()
            tracker.begin(page, "https://previous.test/")
            val accepted = tracker.begin(page, "https://missing.invalid/", "https://previous.test/")
            assertNull(tracker.mainFrameError(page, "https://previous.test/", actualUrl, -2))
            val failed = tracker.mainFrameError(page, "https://missing.invalid/", actualUrl, -2)!!
            assertEquals(accepted.navigationId, failed.navigationId)
            assertEquals(BrowserPageStateTracker.LoadState.FAILED, failed.loadState)
            assertEquals(-2, failed.mainFrameError)
            assertEquals("https://missing.invalid/", failed.currentUrl)
            assertEquals("https://previous.test/", failed.urlBefore)
            assertFalse(failed.committed)
            assertNull(failed.urlAfter)
        }
    }

    @Test
    fun pageStartedAndPageFinishedDoNotFabricateCommitEvidence() {
        val tracker = BrowserPageStateTracker<Any>()
        val page = Any()
        val accepted = tracker.begin(page, "https://example.test/new", "https://example.test/old")

        val started = tracker.pageStarted(page, "https://example.test/new", "https://example.test/new")!!
        assertSame(accepted, started)
        assertEquals("https://example.test/old", started.urlBefore)
        assertFalse(started.committed)
        assertNull(started.urlAfter)

        val finished = tracker.pageFinished(
            page,
            "https://example.test/new",
            "https://example.test/new",
            "complete",
        )!!
        assertEquals(BrowserPageStateTracker.LoadState.FINISHED, finished.loadState)
        assertFalse(finished.committed)
        assertNull(finished.urlAfter)
        assertEquals("https://example.test/old", finished.urlBefore)
    }

    @Test
    fun pageCommittedVisibleRecordsActualUrlAndKeepsItAcrossDocumentFinish() {
        val tracker = BrowserPageStateTracker<Any>()
        val page = Any()
        val accepted = tracker.begin(page, "https://example.test/start", "https://example.test/old")
        tracker.pageStarted(page, "https://example.test/final", "https://example.test/final")

        assertNull(tracker.pageCommittedVisible(page, "https://example.test/start", "https://example.test/final"))
        val committed = tracker.pageCommittedVisible(
            page,
            "https://example.test/final",
            "https://example.test/final",
        )!!
        assertSame(accepted, committed)
        assertTrue(committed.committed)
        assertEquals("https://example.test/final", committed.urlAfter)
        assertEquals("https://example.test/old", committed.urlBefore)
        assertEquals(accepted.navigationId, committed.navigationId)

        tracker.pageFinished(page, "https://example.test/final", "https://example.test/final", "complete")
        val finished = tracker.state(page)!!
        assertTrue(finished.committed)
        assertEquals("https://example.test/final", finished.urlAfter)
    }

    @Test
    fun subsequentPageStartMovesCommittedUrlIntoUrlBeforeWithoutKeepingCommit() {
        val tracker = BrowserPageStateTracker<Any>()
        val page = Any()
        val attempt = tracker.begin(page, "https://example.test/a", "https://example.test/old")
        tracker.pageStarted(page, "https://example.test/a", "https://example.test/a")
        tracker.pageCommittedVisible(page, "https://example.test/a", "https://example.test/a")

        val next = tracker.pageStarted(page, "https://example.test/b", "https://example.test/b")!!
        assertSame(attempt, next)
        assertEquals("https://example.test/a", next.urlBefore)
        assertFalse(next.committed)
        assertNull(next.urlAfter)
    }

    @Test
    fun concurrentBeginsReserveUniqueNavigationIds() {
        val tracker = BrowserPageStateTracker<Any>()
        val page = Any()
        val workerCount = 8
        val attemptsPerWorker = 100
        val ready = CountDownLatch(workerCount)
        val start = CountDownLatch(1)
        val ids = ConcurrentLinkedQueue<Long>()
        val executor = Executors.newFixedThreadPool(workerCount)
        try {
            val tasks = (0 until workerCount).map { worker ->
                executor.submit {
                    ready.countDown()
                    start.await()
                    repeat(attemptsPerWorker) {
                        ids.add(tracker.begin(page, "https://example.test/$worker").navigationId)
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            tasks.forEach { task -> task.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(workerCount * attemptsPerWorker, ids.size)
        assertEquals(workerCount * attemptsPerWorker, ids.toSet().size)
    }

    @Test
    fun acceptedNavigationIsNotReportedAsLoadedAndOldUrlCallbacksAreRejected() {
        val tracker = BrowserPageStateTracker<Any>()
        val firstPage = Any()
        val secondPage = Any()
        val first = tracker.begin(firstPage, "https://example.test/a?token=first")
        assertEquals(BrowserPageStateTracker.LoadState.ACCEPTED, tracker.state(firstPage)?.loadState)
        assertNull(tracker.pageStarted(firstPage, "https://example.test/a?token=first", "https://example.test/other"))
        val second = tracker.begin(firstPage, "https://example.test/b")
        // A delayed callback for the old document can arrive after WebView has already advanced.
        assertNull(tracker.pageStarted(
            firstPage,
            "https://example.test/a?token=first",
            "https://example.test/b",
        ))
        assertSame(second, tracker.pageStarted(firstPage, "https://example.test/b", "https://example.test/b"))

        val independent = tracker.begin(secondPage, "https://example.test/c")
        assertSame(independent, tracker.pageStarted(secondPage, "https://example.test/c", "https://example.test/c"))
        assertEquals(BrowserPageStateTracker.LoadState.LOADING, tracker.state(secondPage)?.loadState)
        assertEquals(1L, first.navigationId)
        assertEquals(2L, second.navigationId)
    }

    @Test
    fun committedMainFrameFailureRetainsActualUrlAndHttpStatusAfterPageFinished() {
        val tracker = BrowserPageStateTracker<Any>()
        val page = Any()
        tracker.begin(page, "https://example.test/path")
        tracker.pageStarted(page, "https://example.test/path", "https://example.test/path")
        tracker.pageCommittedVisible(page, "https://example.test/path", "https://example.test/path")
        tracker.mainFrameHttpStatus(page, "https://example.test/path", "https://example.test/path", 503)
        tracker.mainFrameError(page, "https://example.test/path", "https://example.test/path", -2)
        tracker.pageFinished(page, "https://example.test/path", "https://example.test/path", "complete")

        val state = tracker.state(page)!!
        assertEquals(BrowserPageStateTracker.LoadState.FAILED, state.loadState)
        assertEquals(-2, state.mainFrameError)
        assertEquals(503, state.httpStatus)
        assertNull(state.readyState)
        assertTrue(state.committed)
        assertEquals("https://example.test/path", state.urlAfter)
    }

    @Test
    fun redirectKeepsAcceptedNavigationIdentityAndImplicitReloadGetsFreshIdentity() {
        val tracker = BrowserPageStateTracker<Any>()
        val page = Any()
        val accepted = tracker.begin(page, "https://example.test/start")
        assertSame(accepted, tracker.pageStarted(page, "https://example.test/final", "https://example.test/final"))
        assertEquals("https://example.test/start", tracker.state(page)?.requestedUrl)
        assertEquals("https://example.test/final", tracker.state(page)?.currentUrl)
        assertFalse(tracker.state(page)!!.committed)
        assertNull(tracker.state(page)!!.urlAfter)
        assertSame(
            accepted,
            tracker.pageCommittedVisible(page, "https://example.test/final", "https://example.test/final"),
        )
        assertEquals("https://example.test/final", tracker.state(page)?.urlAfter)
        tracker.pageFinished(page, "https://example.test/final", "https://example.test/final", "complete")

        val reloaded = tracker.pageStarted(page, "https://example.test/final", "https://example.test/final")!!
        assertTrue(reloaded.navigationId > accepted.navigationId)
        assertEquals(BrowserPageStateTracker.LoadState.LOADING, reloaded.loadState)
        assertEquals("https://example.test/final", reloaded.requestedUrl)
        assertEquals("https://example.test/final", reloaded.urlBefore)
        assertFalse(reloaded.committed)
        assertNull(reloaded.urlAfter)
    }

    @Test
    fun urlOutputPreservesPathAndExplicitlyMarksRedactedComponents() {
        val public = BrowserPageStateTracker.publicUrl("https://user:secret@example.test/a/b?q=private#part")
        assertEquals("https://example.test/a/b", public.value)
        assertTrue(public.queryRedacted)
        assertTrue(public.fragmentRedacted)

        val opaque = BrowserPageStateTracker.publicUrl("about:blank")
        assertEquals("about:blank", opaque.value)
        assertFalse(opaque.queryRedacted)
        assertFalse(opaque.fragmentRedacted)

        assertNull(BrowserPageStateTracker.publicUrl("data:text/html,private-body").value)
        assertNull(BrowserPageStateTracker.publicUrl("javascript:void(0)").value)
        assertNull(BrowserPageStateTracker.publicUrl("mailto:user@example.test").value)
        assertNull(BrowserPageStateTracker.publicUrl("about:srcdoc").value)
    }
}
