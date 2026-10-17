package com.dshbox.browser.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserHistoryStoreTest {
    @Test fun legacyRowsSurviveIndependentDecodeAndEditDeleteClear() {
        val raw = """[{"id":"old_id","url":"https://example.test/?q=a#b","title":"旧标题"}]"""
        val old = BrowserHistoryStore.decode(raw)
        assertEquals(1, old.size)
        val edited = old.single().copy(title = "Edited", url = "https://example.test/changed")
        val stored = BrowserHistoryStore.encode(listOf(edited, entry(2)))
        val reopened = BrowserHistoryStore.decode(stored)
        assertEquals(edited, reopened.first())
        val deleted = BrowserHistoryStore.encode(reopened.filterNot { it.id == edited.id })
        assertEquals(listOf(entry(2)), BrowserHistoryStore.decode(deleted))
        assertEquals("[]", BrowserHistoryStore.encode(emptyList()))
        assertTrue(BrowserHistoryStore.decode("[]").isEmpty())
    }

    @Test fun oneCorruptRowDoesNotEraseOtherValidHistory() {
        val rows = JSONArray().put(JSONObject().put("id", "bad").put("url", 12).put("title", true))
            .put(JSONObject().put("id", "valid").put("url", "https://example.test/").put("title", "valid"))
            .put(JSONObject.NULL)
        assertEquals("valid", BrowserHistoryStore.decode(rows.toString()).single().id)
        assertTrue(BrowserHistoryStore.decode("broken json").isEmpty())
        assertTrue(BrowserHistoryStore.decode("x".repeat(4 * 1024 * 1024 + 1)).isEmpty())
    }

    @Test fun limitsAndDuplicateRowsPreserveMostRecentOrder() {
        val rows = listOf(entry(1), entry(1).copy(title = "duplicate"), entry(2).copy(url = entry(1).url)) +
            (3..300).map(::entry)
        val reopened = BrowserHistoryStore.decode(BrowserHistoryStore.encode(rows))
        assertEquals(200, reopened.size)
        assertEquals(entry(1), reopened.first())
        assertEquals(entry(3), reopened[1])
        assertEquals(200, reopened.map { it.id }.toSet().size)
    }

    @Test fun unsafeRowsAreRejectedAndTitlesBounded() {
        val rows = listOf(entry(1).copy(url = "javascript:alert(1)"), entry(2).copy(url = "https://user:secret@example.test/"),
            entry(3).copy(url = "https://example.test/" + "a".repeat(2050)), entry(4).copy(id = "bad/id"),
            entry(5).copy(title = "t".repeat(1000)))
        val reopened = BrowserHistoryStore.decode(BrowserHistoryStore.encode(rows))
        assertEquals(1, reopened.size)
        assertEquals(256, reopened.single().title.length)
        assertEquals("id5", reopened.single().id)
    }

    private fun entry(index: Int) = BrowserHistoryStore.Entry("id$index", "https://example.test/$index", "Title $index")
}
