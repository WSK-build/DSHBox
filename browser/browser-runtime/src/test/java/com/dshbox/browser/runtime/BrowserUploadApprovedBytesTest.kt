package com.dshbox.browser.runtime

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

class BrowserUploadApprovedBytesTest {
    @Test fun stagedContentMustStillMatchApprovalBeforeAnyBytesArePublished() {
        val root = createTempDir(prefix = "upload-bytes-")
        try {
            assumeSecureUploadStorage(root)
            val project = File(root, "user-data").apply { mkdirs() }
            File(project, "report.txt").writeText("approved")
            val source = BrowserUploadPolicy.inspect(project, "report.txt")!!
            val staged = BrowserUploadStager(root).stage("a".repeat(32), listOf(source)).single()
            val identity = BrowserUploadFileAccess.stagedIdentity(root, staged.file.parentFile.name, staged.file.name)!!
            assertEquals(source.sha256, staged.sha256)
            assertEquals("approved", String(BrowserUploadFileAccess.readApprovedStagedBytes(root, staged.file.parentFile.name,
                staged.file.name, staged.size, identity, staged.sha256) { true }))
            staged.file.writeText("replaced") // Same byte count and inode, different approved content.
            assertThrows(IllegalStateException::class.java) {
                BrowserUploadFileAccess.readApprovedStagedBytes(root, staged.file.parentFile.name,
                    staged.file.name, staged.size, identity, staged.sha256) { true }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun quotaRejectsAnotherCopyWithoutPublishingItsGrant() {
        val root = createTempDir(prefix = "upload-quota-")
        try {
            assumeSecureUploadStorage(root)
            val project = File(root, "user-data").apply { mkdirs() }
            File(project, "report.txt").writeText("approved")
            val source = BrowserUploadPolicy.inspect(project, "report.txt")!!
            val occupied = File(root, "browser-tools/upload/${"a".repeat(32)}/${"b".repeat(32)}_existing.bin")
            occupied.parentFile.mkdirs()
            RandomAccessFile(occupied, "rw").use { it.setLength(BrowserUploadFileAccess.MAX_STAGING_BYTES) }
            assertThrows(IllegalArgumentException::class.java) { BrowserUploadStager(root).stage("c".repeat(32), listOf(source)) }
            assertFalse(File(root, "browser-tools/upload/${"c".repeat(32)}").exists())
            assertEquals("approved", File(project, "report.txt").readText())
        } finally { root.deleteRecursively() }
    }
}
