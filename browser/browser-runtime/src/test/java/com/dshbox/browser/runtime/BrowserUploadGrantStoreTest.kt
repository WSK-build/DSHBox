package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BrowserUploadGrantStoreTest {
    private val identity = BrowserTaskPageIdentity(4, 9, "pg-0123456789abcdefabcd", 12)
    private val staleIdentity = identity.copy(documentGeneration = 13)

    @Test fun onlyApprovedPageBoundFileIdsCanBeConsumedOnce() {
        val store = BrowserUploadGrantStore()
        val now = 1_000L
        val source = source("a.txt", "text/plain", 3)
        val grant = requireNotNull(store.create("task_01", identity, "https://example.test", "upload report",
            listOf("text/plain"), false, listOf(source), now))
        assertEquals(BrowserUploadGrantState.WAITING_USER, grant.state)
        assertTrue(requireNotNull(store.list(grant.id, "task_01", identity, now)).staged.isEmpty())
        assertNull(store.list(grant.id, "other_task", identity, now))
        assertNull(store.list(grant.id, "task_01", staleIdentity, now))

        val stagedFile = BrowserUploadStagedFile(source.id, source.name, source.mimeType, source.size,
            File("/private/upload/a.txt"), "/opt/browser/upload/a.txt", source.sha256)
        assertNotNull(store.approve(grant.id, listOf(stagedFile), now + 1))
        assertNull(store.attachChooser(grant.id, staleIdentity, listOf("text/plain"), false, now + 2))
        assertNull(store.attachChooser(grant.id, identity, listOf("image/*"), false, now + 2))
        assertNotNull(store.attachChooser(grant.id, identity, listOf("text/plain"), false, now + 3))
        assertNull(store.consume(grant.id, "task_01", identity, listOf("f".repeat(32)), now + 4))
        assertEquals(listOf(stagedFile), store.consume(grant.id, "task_01", identity, listOf(source.id), now + 4))
        assertNull(store.consume(grant.id, "task_01", identity, listOf(source.id), now + 5))
        assertEquals(BrowserUploadGrantState.CHOOSER_DELIVERED, store.get(grant.id, now + 5)?.state)
        assertEquals(grant.id, store.revokeIdentity(identity, now + 6).single().id)
    }

    @Test fun rejectsExpiredCancelledAndWrongMultiplicityGrants() {
        val store = BrowserUploadGrantStore()
        val source = source("a.txt", "text/plain", 3)
        val now = 10_000L
        val expired = requireNotNull(store.create("task_01", identity, "https://example.test", "", emptyList(), false, listOf(source), now))
        assertEquals(BrowserUploadGrantState.EXPIRED, store.get(expired.id, expired.expiresAtMillis)?.state)
        assertNull(store.approve(expired.id, emptyList(), expired.expiresAtMillis))
        assertNull(store.consume(expired.id, "task_01", identity, listOf(source.id), expired.expiresAtMillis))
        assertEquals(BrowserUploadGrantState.EXPIRED, store.cancel(expired.id, "task_01", identity, expired.expiresAtMillis)?.state)

        val grant = requireNotNull(store.create("task_01", identity, "https://example.test", "", emptyList(), false, listOf(source), now + 1))
        assertNotNull(store.approve(grant.id, listOf(BrowserUploadStagedFile(source.id, source.name, source.mimeType,
            source.size, File("/private/upload/a.txt"), "/opt/browser/upload/a.txt", source.sha256)), now + 2))
        assertNotNull(store.attachChooser(grant.id, identity, emptyList(), true, now + 3))
        assertNull(store.consume(grant.id, "task_01", identity, listOf(source.id, source.id), now + 3))
        assertEquals(BrowserUploadGrantState.CANCELLED,
            store.cancel(grant.id, "task_01", identity, now + 4)?.state)
    }

    @Test fun rejectsStagedBytesOrMetadataThatDifferFromTheApprovedSource() {
        val store = BrowserUploadGrantStore()
        val source = source("a.txt", "text/plain", 3)
        val grant = store.create("task_01", identity, "https://example.test", "", emptyList(), false, listOf(source), 100L)!!
        val staged = BrowserUploadStagedFile(source.id, source.name, source.mimeType, source.size,
            File("/private/upload/a.txt"), "/opt/browser/upload/a.txt", source.sha256)
        for (changed in listOf(staged.copy(sha256 = "c".repeat(64)), staged.copy(name = "other.txt"),
            staged.copy(size = 4), staged.copy(mimeType = "image/png"))) assertNull(store.approve(grant.id, listOf(changed), 101L))
        assertEquals(BrowserUploadGrantState.WAITING_USER, store.get(grant.id, 102L)?.state)
        assertNotNull(store.approve(grant.id, listOf(staged), 103L))
    }

    private fun source(name: String, mime: String, size: Long) = BrowserUploadSource(
        "a".repeat(32), name, name, mime, size, File("/project/$name"), 1L, "b".repeat(64),
    )
}
