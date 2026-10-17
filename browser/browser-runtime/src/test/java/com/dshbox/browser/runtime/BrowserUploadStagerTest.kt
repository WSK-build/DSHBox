package com.dshbox.browser.runtime

import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrowserUploadStagerTest {
    @Test fun stagesOnlyRevalidatedProjectFilesIntoTheBoundBrowserDirectoryAndRevokes() {
        val filesDir = createTempDir(prefix = "upload-stage-")
        try {
            assumeSecureUploadStorage(filesDir)
            val projectFile = File(filesDir, "user-data/reports/report.txt").apply {
                parentFile!!.mkdirs()
                writeText("approved content")
            }
            val source = requireNotNull(BrowserUploadPolicy.inspect(File(filesDir, "user-data"), "reports/report.txt"))
            val stager = BrowserUploadStager(filesDir)
            val grantId = "a".repeat(32)
            val staged = stager.stage(grantId, listOf(source)).single()
            assertEquals("/opt/browser/upload/$grantId/${source.id}_report.txt", staged.guestPath)
            assertEquals("approved content", staged.file.readText())
            assertTrue(staged.file.path.startsWith(File(filesDir, "browser-tools/upload/$grantId").canonicalPath))
            assertTrue(stager.revoke(grantId))
            assertFalse(staged.file.exists())
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun revalidatesPathComponentsAndRejectsSymlinkSwapAfterInspection() {
        val filesDir = createTempDir(prefix = "upload-symlink-")
        try {
            assumeSecureUploadStorage(filesDir)
            val project = File(filesDir, "user-data").apply { mkdirs() }
            val legitimate = File(project, "report.txt").apply { writeText("safe") }
            val outside = File(filesDir, "outside.txt").apply { writeText("outside") }
            val source = requireNotNull(BrowserUploadPolicy.inspect(project, "report.txt"))
            legitimate.delete()
            try { Files.createSymbolicLink(legitimate.toPath(), outside.toPath()) }
            catch (failure: Exception) { Assume.assumeNoException(failure) }
            val stager = BrowserUploadStager(filesDir)
            assertThrows(IllegalArgumentException::class.java) { stager.stage("b".repeat(32), listOf(source)) }
            assertFalse(File(filesDir, "browser-tools/upload/${"b".repeat(32)}").exists())
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun rejectsParentDirectorySwapAndDoesNotPublishOrTouchOutsideTarget() {
        val filesDir = createTempDir(prefix = "upload-parent-race-")
        try {
            assumeSecureUploadStorage(filesDir)
            val userData = File(filesDir, "user-data").apply { mkdirs() }
            val project = File(userData, "reports").apply { mkdirs() }
            val sourceFile = File(project, "report.txt").apply { writeText("approved") }
            val outside = File(filesDir, "outside").apply { mkdirs() }
            val protected = File(outside, "report.txt").apply { writeText("protected") }
            val source = requireNotNull(BrowserUploadPolicy.inspect(userData, "reports/report.txt"))

            assertTrue(sourceFile.delete())
            assertTrue(project.delete())
            try { Files.createSymbolicLink(project.toPath(), outside.toPath()) }
            catch (failure: Exception) { Assume.assumeNoException(failure) }

            val grant = "c".repeat(32)
            val rejection = assertThrows(Exception::class.java) {
                BrowserUploadStager(filesDir).stage(grant, listOf(source))
            }
            assertTrue(rejection is IllegalArgumentException || rejection is java.nio.file.FileSystemException)
            assertEquals("protected", protected.readText())
            assertFalse(File(filesDir, "browser-tools/upload/$grant").exists())
        } finally { filesDir.deleteRecursively() }
    }
}
