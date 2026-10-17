package com.dshbox.browser.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserTaskListResponseBudgetInstrumentedTest {
    @Test
    fun unicodeRecordsPageBySerializedUtf8BytesWithoutLossOrDuplication() {
        val records = (0 until 9).map { index ->
            JSONObject().put("taskId", "unicode_$index")
                .put("title", "标题 $index")
                .put("goal", "界面测试🙂".repeat(10_000))
                .put("notes", "备注🙂".repeat(500))
                .put("status", "expired").put("revision", index)
        }
        val found = mutableListOf<String>()
        var offset = 0
        while (offset < records.size) {
            val page = BrowserTaskListResponseBudget.encode("req_page", "CLOSED", records.drop(offset), records.size, offset)
            assertNull(page.requiredBytesForOversizedTask)
            assertTrue(page.includedCount > 0)
            val serialized = page.response.toString()
            assertTrue(serialized.toByteArray(Charsets.UTF_8).size <= BrowserTaskListResponseBudget.HARD_BYTES)
            val data = page.response.getJSONObject("data")
            val tasks = data.getJSONArray("tasks")
            repeat(tasks.length()) { index -> found.add(tasks.getJSONObject(index).getString("taskId")) }
            val next = data.opt("nextOffset")
            if (next !is Number) break
            val nextOffset = next.toInt()
            assertTrue(nextOffset > offset)
            offset = nextOffset
        }
        assertEquals(records.map { it.getString("taskId") }, found)
        assertEquals(found.size, found.toSet().size)
    }

    @Test
    fun oneLegalUnicodeRecordAboveOldSoftBudgetIsReturnedInFull() {
        val oversized = JSONObject().put("taskId", "unicode_oversized")
            .put("goal", "界".repeat(4_000)).put("notes", "备".repeat(2_000))
        val page = BrowserTaskListResponseBudget.encode("req_big", "CLOSED", listOf(oversized), 1, 0)
        assertNull(page.requiredBytesForOversizedTask)
        assertEquals(1, page.includedCount)
        assertEquals(oversized.getString("goal"), page.response.getJSONObject("data").getJSONArray("tasks").getJSONObject(0).getString("goal"))
        assertTrue(page.response.toString().toByteArray(Charsets.UTF_8).size > 12 * 1024)
        assertTrue(page.response.toString().toByteArray(Charsets.UTF_8).size <= BrowserTaskListResponseBudget.HARD_BYTES)
    }

    @Test
    fun recordAboveHardFrameIsReportedWithoutTruncatingOrAdvancing() {
        val oversized = JSONObject().put("taskId", "unicode_oversized")
            .put("goal", "界".repeat(100_000))
        val page = BrowserTaskListResponseBudget.encode("req_big", "CLOSED", listOf(oversized), 1, 0)
        assertEquals(0, page.includedCount)
        assertEquals("unicode_oversized", page.oversizedTaskId)
        assertTrue(page.requiredBytesForOversizedTask!! > BrowserTaskListResponseBudget.SOFT_BYTES)
        assertTrue(page.requiredBytesForOversizedTask!! > BrowserTaskListResponseBudget.HARD_BYTES)
        assertEquals(0, page.response.getJSONObject("data").getInt("nextOffset"))
    }
}
