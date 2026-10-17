package com.dshbox.browser.runtime

import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrowserUploadPolicyTest {
    @Test fun rejectsTraversalAbsolutePathsAndProtectedNames() {
        listOf("../secret.txt", "project/../../secret.txt", "/etc/passwd", "C:/secret.txt", "a\\b.txt",
            ".dsh/config.json", "project/.git/config", ".env.local", "keys/id_rsa", "private.pem").forEach {
            assertNull(it, BrowserUploadPolicy.validateRelativePath(it))
        }
        assertEquals(listOf("project", "report.pdf"), BrowserUploadPolicy.validateRelativePath("project/report.pdf"))
    }

    @Test fun inspectsOnlyRegularFilesWithinWorkspaceAndAppliesSizeLimit() {
        val filesDir = createTempDir(prefix = "upload-policy-")
        try {
            assumeSecureUploadStorage(filesDir)
            val root = File(filesDir, "user-data").apply { mkdirs() }
            val nested = File(root, "project").apply { mkdir() }
            val file = File(nested, "report.pdf").apply { writeText("pdf data") }
            val inspected = BrowserUploadPolicy.inspect(root, "project/report.pdf")
            assertNotNull(inspected)
            assertEquals("application/pdf", inspected!!.mimeType)
            assertEquals(file.length(), inspected.size)
            assertTrue(inspected.sha256.matches(Regex("[a-f0-9]{64}")))
            assertNull(BrowserUploadPolicy.inspect(root, "missing.txt"))
            assertNull(BrowserUploadPolicy.inspect(root, "project"))
            assertNull(BrowserUploadPolicy.inspect(filesDir, "report.pdf"))
            val oversized = File(nested, "large.bin")
            oversized.outputStream().use { out -> out.write(ByteArray((BrowserUploadPolicy.MAX_FILE_BYTES + 1).toInt())) }
            assertNull(BrowserUploadPolicy.inspect(root, "project/large.bin"))
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun rejectsSymlinkedFile() {
        val filesDir = createTempDir(prefix = "upload-link-")
        try {
            assumeSecureUploadStorage(filesDir)
            val root = File(filesDir, "user-data").apply { mkdirs() }
            File(root, "secret.txt").writeText("private")
            try { Files.createSymbolicLink(File(root, "linked.txt").toPath(), File(root, "secret.txt").toPath()) }
            catch (failure: Exception) { Assume.assumeNoException(failure) }
            assertNull(BrowserUploadPolicy.inspect(root, "linked.txt"))
        } finally { filesDir.deleteRecursively() }
    }

    @Test fun acceptTypesMatchExactMimeWildcardAndExtension() {
        assertTrue(BrowserUploadPolicy.accepts("image/png", "photo.png", listOf("image/*")))
        assertTrue(BrowserUploadPolicy.accepts("application/pdf", "report.pdf", listOf(".pdf")))
        assertFalse(BrowserUploadPolicy.accepts("application/pdf", "report.pdf", listOf("image/*")))
        assertFalse(BrowserUploadPolicy.accepts("application/pdf", "report.pdf", listOf(".png")))
        assertTrue(BrowserUploadPolicy.accepts("application/pdf", "report.pdf", emptyList()))
    }
}
