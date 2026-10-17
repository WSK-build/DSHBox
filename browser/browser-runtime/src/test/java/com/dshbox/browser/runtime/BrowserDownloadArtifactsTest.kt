package com.dshbox.browser.runtime

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

class BrowserDownloadArtifactsTest {
    private lateinit var root: Path

    @Before
    fun createRoot() {
        root = Files.createTempDirectory("browser-download-artifacts-")
        Files.createDirectories(downloads())
    }

    @After
    fun removeRoot() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun ownedNameRuleAcceptsOnlyBrowserFinalArtifactNames() {
        val id = "a".repeat(32)

        assertEquals(id, BrowserDownloadArtifacts.ownedIdFor("${id}_report.pdf"))
        assertEquals(id, BrowserDownloadArtifacts.ownedIdFor("${id}_archive.tar.gz"))
        assertNull(BrowserDownloadArtifacts.ownedIdFor("foreign.txt"))
        assertNull(BrowserDownloadArtifacts.ownedIdFor(id))
        assertNull(BrowserDownloadArtifacts.ownedIdFor("${id}_"))
        assertNull(BrowserDownloadArtifacts.ownedIdFor("${id}_/escape"))
        assertNull(BrowserDownloadArtifacts.ownedIdFor("${id}_name/../escape"))
        assertNull(BrowserDownloadArtifacts.ownedIdFor("${"a".repeat(31)}_report.pdf"))
        assertNull(BrowserDownloadArtifacts.ownedIdFor("${"G".repeat(32)}_report.pdf"))
        assertNull(BrowserDownloadArtifacts.ownedIdFor("${id}_" + "a".repeat(181)))
    }

    @Test
    fun resolvesAndDeletesOnlyTheOwnedOriginal() {
        assumeSecure()
        val id = "b".repeat(32)
        val payload = "browser-owned-payload".toByteArray()
        val owned = downloads().resolve("${id}_report.pdf")
        Files.write(owned, payload)
        val foreign = downloads().resolve("foreign.txt")
        Files.write(foreign, byteArrayOf(9))
        val export = root.resolve("user-export").resolve("report.pdf")
        Files.createDirectories(export.parent)
        Files.write(export, payload)

        val metadata = BrowserDownloadArtifacts.completedMetadata(root, id)
        assertNotNull(metadata)
        assertEquals("report.pdf", metadata!!.displayName)
        assertEquals(payload.size.toLong(), metadata.size)
        assertEquals("application/pdf", BrowserDownloadArtifacts.mimeTypeForName(metadata.name))
        val channelBytes = BrowserDownloadArtifacts.openCompletedChannel(root, id).use { channel ->
            java.nio.channels.Channels.newInputStream(channel).readBytes()
        }
        assertArrayEquals(payload, channelBytes)

        assertEquals(
            BrowserDownloadDeleteResult.NOT_FOUND,
            BrowserDownloadArtifacts.deleteCompleted(root, "c".repeat(32)),
        )
        assertTrue(Files.exists(foreign))
        assertTrue("user export must never be touched", Files.exists(export))

        assertEquals(BrowserDownloadDeleteResult.DELETED, BrowserDownloadArtifacts.deleteCompleted(root, id))
        assertFalse(Files.exists(owned))
        assertNull(BrowserDownloadArtifacts.completedMetadata(root, id))
        assertTrue("user export must survive deletion of the Browser copy", Files.exists(export))
    }

    @Test
    fun refusesSymlinkedOwnedNameWithoutDeletingItsTarget() {
        assumeSecure()
        val id = "d".repeat(32)
        val target = root.resolve("outside.txt")
        Files.write(target, byteArrayOf(1, 2, 3))
        val link = downloads().resolve("${id}_report.txt")
        try {
            Files.createSymbolicLink(link, target)
        } catch (_: Exception) {
            assumeTrue("symbolic links are unavailable", false)
            return
        }

        assertNull(BrowserDownloadArtifacts.completedMetadata(root, id))
        assertFalse(runCatching { BrowserDownloadArtifacts.openCompletedChannel(root, id) }.isSuccess)
        assertEquals(BrowserDownloadDeleteResult.NOT_FOUND, BrowserDownloadArtifacts.deleteCompleted(root, id))
        assertTrue("symlink target must be untouched", Files.exists(target))
        assertTrue("symlink itself must be untouched", Files.exists(link, LinkOption.NOFOLLOW_LINKS))
    }

    private fun downloads() = root.resolve(BrowserDownloadArtifacts.RELATIVE_DIRECTORY)

    private fun assumeSecure() {
        Files.newDirectoryStream(root).use { stream ->
            assumeTrue(
                "SecureDirectoryStream unavailable; run the Linux storage suite",
                stream is SecureDirectoryStream<*>,
            )
        }
    }
}
