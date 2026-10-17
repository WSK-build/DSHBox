package com.dshbox.browser.api

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream

class BrowserArtifactFilesTest {
    @Test fun uploadCacheClassificationAndAnchoredCleanupNeverTouchOriginals() {
        val grant = "a".repeat(32)
        val name = "${"b".repeat(32)}_report.txt"
        assertTrue(BrowserArtifactFiles.isTemporaryPath("browser-tools/upload/$grant/$name"))
        assertTrue(BrowserArtifactFiles.isTemporaryPath(".browser-upload-private/$grant/.tmp-${"c".repeat(32)}"))
        assertFalse(BrowserArtifactFiles.isTemporaryPath("user-data/report.txt"))
        assertFalse(BrowserArtifactFiles.isTemporaryPath("browser-tools/upload/$grant/../../user-data/report.txt"))
        withSecureRoot { root ->
            val original = File(root, "user-data/report.txt").apply { parentFile.mkdirs(); writeText("original") }
            val cache = File(root, "browser-tools/upload/$grant/$name").apply { parentFile.mkdirs(); writeText("copied") }
            val now = System.currentTimeMillis()
            assertEquals(0L, BrowserArtifactFiles.removeForCleanup(root, now) { it.length() })
            cache.setLastModified(now - 120_000)
            cache.parentFile.setLastModified(now - 120_000)
            assertEquals(6L, BrowserArtifactFiles.removeForCleanup(root, now) { it.length() })
            assertFalse(cache.exists())
            assertEquals("original", original.readText())
        }
    }

    @Test fun websocketJsonUsesTheSameAnchoredQuotaAndRejectsOtherJsonPaths() {
        withSecureRoot { root ->
            val bytes = "{\"version\":1,\"frames\":[]}".toByteArray()
            val name = "net-test.ws.json"
            BrowserArtifactFiles.writeNetworkArtifactAtomically(root, name, bytes, 128)
            assertArrayEquals(bytes, BrowserArtifactFiles.readNetworkArtifact(root, name, 128))
            for (invalid in listOf("net-test.json", "../net-test.ws.json", "net-test.ws.json/child")) {
                assertThrows(IllegalArgumentException::class.java) {
                    BrowserArtifactFiles.writeNetworkArtifactAtomically(root, invalid, bytes, 128)
                }
            }
            BrowserArtifactFiles.deleteNetworkArtifact(root, name)
            assertFalse(BrowserArtifactFiles.networkArtifactExists(root, name))
        }
    }

    @Test fun networkArtifactsUseBoundedAnchoredReadAppendReplaceAndDelete() {
        withSecureRoot { root ->
            val directory = BrowserArtifactFiles.ensureNetworkDirectory(root)
            val name = "net-test.body"
            val written = BrowserArtifactFiles.writeNetworkArtifactAtomically(root, name, byteArrayOf(1, 2), 16)
            assertEquals(directory.canonicalFile, written.parentFile.canonicalFile)
            assertArrayEquals(byteArrayOf(1, 2), BrowserArtifactFiles.readNetworkArtifact(root, name, 16))
            assertEquals(4L, BrowserArtifactFiles.appendNetworkArtifact(root, name, byteArrayOf(3, 4), 16))
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), BrowserArtifactFiles.readNetworkArtifact(root, name, 16))
            assertEquals(1, BrowserArtifactFiles.listNetworkArtifacts(root).size)
            assertThrows(IllegalArgumentException::class.java) {
                BrowserArtifactFiles.appendNetworkArtifact(root, name, ByteArray(13), 16)
            }
            BrowserArtifactFiles.writeNetworkArtifactAtomically(root, name, byteArrayOf(9), 16)
            assertArrayEquals(byteArrayOf(9), BrowserArtifactFiles.readNetworkArtifact(root, name, 16))
            BrowserArtifactFiles.deleteNetworkArtifact(root, name)
            assertFalse(BrowserArtifactFiles.networkArtifactExists(root, name))
        }
    }

    @Test fun selectionOutboxReadsAndReplacesIndexThroughSecureDirectoryHandle() {
        withSecureRoot { root ->
            File(root, "browser-tools/out/selection").mkdirs()
            val first = "{\"version\":2,\"items\":[]}".toByteArray()
            BrowserArtifactFiles.writeSelectionOutboxAtomically(root, first, 256 * 1024)
            assertArrayEquals(first, BrowserArtifactFiles.readSelectionOutbox(root, 256 * 1024))
            val second = "{\"version\":2,\"sequence\":1}".toByteArray()
            BrowserArtifactFiles.writeSelectionOutboxAtomically(root, second, 256 * 1024)
            assertArrayEquals(second, BrowserArtifactFiles.readSelectionOutbox(root, 256 * 1024))
        }
    }

    @Test fun oversizedSelectionStopsWriterAndRemovesStage() {
        withSecureRoot { root ->
            val directory = File(root, "browser-tools/out/selection").apply { mkdirs() }
            var reachedAfterOverflow = false
            assertThrows(IllegalStateException::class.java) {
                BrowserArtifactFiles.writeSelectionAtomically(root, "selection-too-large.png", maxBytes = 2) {
                    it.write(byteArrayOf(1, 2))
                    it.write(3)
                    reachedAfterOverflow = true
                }
            }
            assertFalse(reachedAfterOverflow)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun selectionWritesAndDiscardsStayWithinSecureDirectory() {
        withSecureRoot { root ->
            File(root, "browser-tools/out/selection").mkdirs()
            val artifact = BrowserArtifactFiles.writeSelectionAtomically(root, "selection-test.png") {
                it.write(byteArrayOf(1, 2, 3))
            }
            assertEquals(3L, artifact.sizeBytes)
            assertTrue(BrowserArtifactFiles.selectionExists(root, artifact.file.name))
            val victim = write(root, "browser-tools/out/downloads/keep.txt", 1L)
            BrowserArtifactFiles.removeSelection(root, artifact.file.name)
            assertFalse(BrowserArtifactFiles.selectionExists(root, artifact.file.name))
            assertTrue(victim.exists())
            assertThrows(IllegalArgumentException::class.java) {
                BrowserArtifactFiles.removeSelection(root, "../downloads/keep.txt")
            }
        }
    }

    @Test fun screenshotDeleteRemovesOnlyTheExactWrittenArtifact() {
        withSecureRoot { root ->
            val other = write(root, "browser-tools/out/shot/shot-other.png", System.currentTimeMillis())
            val artifact = BrowserArtifactFiles.writeScreenshotAtomically(root, "shot-this.png") {
                it.write(byteArrayOf(1, 2, 3))
            }
            assertTrue(BrowserArtifactFiles.removeScreenshot(root, artifact))
            assertFalse(artifact.file.exists())
            assertTrue(other.exists())
            assertFalse(BrowserArtifactFiles.removeScreenshot(root, artifact))
        }
    }

    @Test fun screenshotDeleteDoesNotRemoveAReplacementOrFollowSymlinks() {
        withSecureRoot { root ->
            val outside = Files.createTempFile("browser-shot-outside", ".png").toFile().apply { writeBytes(byteArrayOf(8, 9)) }
            try {
                val artifact = BrowserArtifactFiles.writeScreenshotAtomically(root, "shot-replaced.png") {
                    it.write(byteArrayOf(1, 2, 3))
                }
                Files.delete(artifact.file.toPath())
                Files.copy(outside.toPath(), artifact.file.toPath())
                assertFalse(BrowserArtifactFiles.removeScreenshot(root, artifact))
                assertTrue(artifact.file.exists())

                Files.delete(artifact.file.toPath())
                try { Files.createSymbolicLink(artifact.file.toPath(), outside.toPath()) }
                catch (_: Exception) { assumeTrue("Symlink unavailable", false) }
                assertFalse(BrowserArtifactFiles.removeScreenshot(root, artifact))
                assertTrue(outside.exists())
            } finally { outside.delete() }
        }
    }

    @Test fun replacingSelectionKeepsRecentReaderArtifact() {
        withSecureRoot { root ->
            File(root, "browser-tools/out/selection").mkdirs()
            val first = BrowserArtifactFiles.writeSelectionAtomically(root, "selection-first.png") { it.write(1) }
            BrowserArtifactFiles.writeSelectionAtomically(root, "selection-second.png") { it.write(2) }
            assertTrue(BrowserArtifactFiles.selectionExists(root, first.file.name))
        }
    }

    @Test fun expirationLeavesDownloadsAndRuntimeStateUntouched() {
        withSecureRoot { root ->
            val stale = write(root, "browser-tools/out/shot/shot-old.png", 1L)
            val recent = write(root, "browser-tools/out/selection/selection-new.png", 99_999_999L)
            val download = write(root, "browser-tools/out/downloads/report.pdf", 1L)
            val state = write(root, "browser-tools/run/status.json", 1L)
            assertEquals(1L, BrowserArtifactFiles.removeOlderThan(root, 100L))
            assertFalse(stale.exists())
            assertTrue(recent.exists()); assertTrue(download.exists()); assertTrue(state.exists())
        }
    }

    @Test fun symlinkedOutputCannotRemoveOutsideFiles() {
        val root = Files.createTempDirectory("browser-artifacts").toFile()
        val outside = Files.createTempDirectory("browser-outside").toFile()
        try {
            assumeSecureRoot(root)
            val victim = write(outside, "shot/shot-outside.png", 1L)
            File(root, "browser-tools").mkdirs()
            try { Files.createSymbolicLink(File(root, "browser-tools/out").toPath(), outside.toPath()) }
            catch (_: Exception) { assumeTrue("Symlink unavailable", false) }
            assertThrows(Exception::class.java) { BrowserArtifactFiles.removeOlderThan(root, Long.MAX_VALUE) }
            assertTrue(victim.exists())
        } finally {
            Files.deleteIfExists(File(root, "browser-tools/out").toPath())
            root.deleteRecursively(); outside.deleteRecursively()
        }
    }

    @Test fun quotaEvictsExpiredOnlyAndKeepsCurrentScreenshot() {
        withSecureRoot { root ->
            val old = write(root, "browser-tools/out/shot/shot-old.png", 1L)
            val keep = write(root, "browser-tools/out/shot/shot-new.png", 2L)
            BrowserArtifactFiles.trimScreenshots(root, keep, maxBytes = 1L, maxCount = 1, nowMillis = 100_000L)
            assertFalse(old.exists()); assertTrue(keep.exists())
            assertFalse(BrowserArtifactFiles.isTemporaryPath("browser-tools/out/shot/../run/status.json"))
        }
    }

    @Test fun fullRecentQuotaRejectsAndRollsBackNewScreenshot() {
        withSecureRoot { root ->
            val recent = (1..BrowserArtifactFiles.SCREENSHOT_MAX_COUNT).map { index ->
                write(root, "browser-tools/out/shot/shot-recent-$index.png", System.currentTimeMillis())
            }
            val name = "shot-new-${System.nanoTime()}.png"
            val failure = assertThrows(ScreenshotQuotaExceededException::class.java) {
                BrowserArtifactFiles.writeScreenshotAtomically(root, name) { it.write(byteArrayOf(1, 2)) }
            }
            assertFalse(failure.message.orEmpty().contains("retry after 30 seconds"))
            assertTrue("delivered artifacts must remain", recent.all { it.exists() })
            assertFalse(File(root, "browser-tools/out/shot/$name").exists())
            assertEquals(BrowserArtifactFiles.SCREENSHOT_MAX_COUNT, File(root, "browser-tools/out/shot").list()?.size ?: -1)
        }
    }

    @Test fun cleanupAgeUsesSecondPrecisionAndThirtySecondReadGrace() {
        val now = 100_000L
        assertTrue(BrowserArtifactFiles.eligibleForCleanup(69_999L, now, active = false))
        assertFalse(BrowserArtifactFiles.eligibleForCleanup(70_000L, now, active = false))
        assertFalse(BrowserArtifactFiles.eligibleForCleanup(1L, now, active = true))
        assertTrue(BrowserArtifactFiles.eligibleForCleanup(now - BrowserArtifactFiles.TTL_MILLIS - 2_000L, now, active = true))
    }

    @Test fun secureCleanupUsesTheSameRealFilesystemSecondBoundary() {
        withSecureRoot { root ->
            val atSecondBoundary = write(root, "browser-tools/out/network/at-boundary.har", 69_000L)
            val sameSecondTail = write(root, "browser-tools/out/network/same-second-tail.har", 69_999L)
            val nextSecond = write(root, "browser-tools/out/network/next-second.har", 70_000L)
            assertEquals(2L, BrowserArtifactFiles.removeForCleanup(root, nowMillis = 100_000L) { it.length() })
            assertFalse(atSecondBoundary.exists())
            assertFalse(sameSecondTail.exists())
            assertTrue(nextSecond.exists())
        }
    }

    @Test fun cacheCleanupPreservesOutboxSelectionUntilExplicitlyCleared() {
        withSecureRoot { root ->
            val now = System.currentTimeMillis()
            val selection = write(root, "browser-tools/out/selection/selection-pending.png", now - 120_000L)
            val orphan = write(root, "browser-tools/out/selection/selection-orphan.png", now - 120_000L)
            val outbox = File(root, "browser-tools/out/selection/outbox.json")
            outbox.writeText(JSONObject()
                .put("version", 1).put("sequence", 1).put("lastWallClockMillis", now)
                .put("items", JSONArray().put(JSONObject()
                    .put("selectionId", "00000000-0000-4000-8000-000000000001")
                    .put("selectionSequence", 1)
                    .put("artifactPath", "/opt/browser/out/selection/selection-pending.png")
                    .put("createdAtWallMillis", now - 120_000L)
                    .put("expiresAtWallMillis", now + 1_600_000L)
                    .put("deliveryState", "UNBOUND"))).toString())

            assertEquals(1L, BrowserArtifactFiles.removeForCleanup(root, now) { it.length() })
            assertTrue("unbound context remains readable", selection.isFile)
            assertFalse("expired orphan image is cleaned", orphan.exists())

            BrowserArtifactFiles.clearSelections(root)
            assertFalse("explicit clear removes the screenshot", selection.exists())
            assertFalse("explicit clear removes the outbox index", outbox.exists())
            assertEquals(0L, BrowserArtifactFiles.removeForCleanup(root, now) { it.length() })
        }
    }

    private fun withSecureRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("browser-artifacts").toFile()
        assumeSecureRoot(root)
        try {
            listOf("shot", "selection", "network").forEach { File(root, "browser-tools/out/$it").mkdirs() }
            block(root)
        } finally { root.deleteRecursively() }
    }

    private fun assumeSecureRoot(root: File) {
        val stream = Files.newDirectoryStream(root.toPath())
        val supported = stream is SecureDirectoryStream<*>
        stream.close()
        assumeTrue("SecureDirectoryStream unavailable on this filesystem", supported)
    }

    private fun write(root: File, path: String, modified: Long): File = File(root, path).also {
        requireNotNull(it.parentFile).mkdirs(); it.writeBytes(byteArrayOf(1)); check(it.setLastModified(modified))
    }
}
