package com.dshbox.browser.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserBookmarksStoreAndroidTest {
    @Test
    fun scopedIpv6PreservesInterfaceNameCase() {
        val store = BrowserBookmarksStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val suffix = "/bookmark-${UUID.randomUUID()}?Key=Value"
        val bookmark = requireNotNull(store.save("HTTPS://[FE80::1%25Eth0]:8080$suffix", "Scope test"))
        try {
            assertEquals("https://[fe80::1%25Eth0]:8080$suffix", bookmark.url)
            assertEquals(bookmark.id, store.findByUrl(bookmark.url)?.id)
            assertNull(store.findByUrl("https://[fe80::1%25eth0]:8080$suffix"))
        } finally {
            store.delete(bookmark.id)
        }
    }

    @Test
    fun schemeAndHostCaseShareBookmarkWithoutChangingPathOrQuery() {
        val store = BrowserBookmarksStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val suffix = "/bookmark-${UUID.randomUUID()}/CaseSensitive?Key=Value#Section"
        val bookmark = requireNotNull(store.save("HTTPS://EXAMPLE.COM$suffix", "Case test"))
        try {
            assertEquals("https://example.com$suffix", bookmark.url)
            assertEquals(bookmark.id, store.findByUrl("https://example.com$suffix")?.id)
            assertEquals(bookmark.id, store.save("https://example.com$suffix", "Updated")?.id)
            assertNull(store.findByUrl("https://example.com${suffix.replace("CaseSensitive", "casesensitive")}"))
            assertTrue(store.delete(bookmark.id))
        } finally {
            store.delete(bookmark.id)
        }
    }

    @Test
    fun normalizedLookupAndDeleteReflectCommittedBookmarkState() {
        val store = BrowserBookmarksStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val url = "https://example.com/bookmark-${UUID.randomUUID()}/café"
        val saved = store.save(url, "Bookmark test")
        assertNotNull(saved)
        val bookmark = requireNotNull(saved)
        try {
            assertNotNull(store.findByUrl(url))
            assertNotNull(store.findByUrl(URI(url).toASCIIString()))
            assertTrue(store.delete(bookmark.id))
            assertNull(store.findByUrl(url))
            assertFalse(store.delete(bookmark.id))
        } finally {
            store.delete(bookmark.id)
        }
    }
}
