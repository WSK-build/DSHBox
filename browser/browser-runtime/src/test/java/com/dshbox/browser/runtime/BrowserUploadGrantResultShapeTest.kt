package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Locks the background upload list/status wire shape the CLI/client validator requires. */
class BrowserUploadGrantResultShapeTest {
    private fun grant(
        state: BrowserUploadGrantState,
        staged: List<BrowserUploadStagedFile> = emptyList(),
    ) = BrowserUploadGrant(
        id = "a".repeat(32),
        taskId = "task-1",
        identity = BrowserTaskPageIdentity(1, 1, "pg-1", 0),
        origin = "https://example.com",
        summary = "",
        acceptTypes = emptyList(),
        multiple = false,
        createdAtMillis = 0,
        expiresAtMillis = 2_000,
        sources = emptyList(),
        state = state,
        staged = staged,
    )

    @Test fun statusIncludesTtlAndStateWithoutFiles() {
        val data = uploadGrantListData(
            grant(BrowserUploadGrantState.WAITING_USER), nowMillis = 500, includeFiles = false,
        )
        assertEquals("a".repeat(32), data.getString("approvalId"))
        assertEquals("pending", data.getString("state"))
        assertEquals(1_500L, data.getLong("expiresInMs"))
        assertFalse(data.has("files"))
    }

    @Test fun listReadyIncludesApprovedFileIds() {
        val file = BrowserUploadStagedFile("f1", "note.txt", "text/plain", 12, File("x"), "/opt/browser/upload/f1")
        val data = uploadGrantListData(
            grant(BrowserUploadGrantState.READY, listOf(file)), nowMillis = 0, includeFiles = true,
        )
        assertEquals("ready", data.getString("state"))
        val files = data.getJSONArray("files")
        assertEquals(1, files.length())
        assertEquals("f1", files.getJSONObject(0).getString("fileId"))
    }

    @Test fun listPendingOmitsFiles() {
        val data = uploadGrantListData(
            grant(BrowserUploadGrantState.WAITING_USER), nowMillis = 0, includeFiles = true,
        )
        assertFalse(data.has("files"))
    }
}
