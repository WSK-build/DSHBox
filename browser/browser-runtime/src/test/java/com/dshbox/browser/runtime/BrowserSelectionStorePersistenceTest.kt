package com.dshbox.browser.runtime

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.SecureDirectoryStream
import java.util.UUID
import java.io.IOException

class BrowserSelectionStorePersistenceTest {
    @Test
    fun consumePersistenceFailureRestoresTheInMemorySelection() {
        withSelectionFixture("browser-selection-consume-failure") { root, directory, id, wall, elapsed, boot ->
            val store = BrowserSelectionStore(root, directory, wallClock = { wall + 1 }, elapsedClock = { elapsed + 1 },
                bootIdentity = { boot }, writeOutbox = { _, _, _ -> throw IOException("injected index write failure") })
            val failed = store.execute(BrowserSelectionStore.ACTION_CONSUME, 4, elapsed + 1)
            assertEquals("PERSIST_FAILED", failed.getString("code"))
            assertEquals("OK", store.execute(BrowserSelectionStore.ACTION_LATEST, 4, elapsed + 1).getString("code"))
            assertTrue(File(directory, "selection-$id.png").isFile)
        }
    }

    @Test
    fun explicitClearFailureReturnsControlledErrorAndKeepsMemoryState() {
        withSelectionFixture("browser-selection-clear-failure") { root, directory, id, wall, elapsed, boot ->
            val store = BrowserSelectionStore(root, directory, wallClock = { wall + 1 }, elapsedClock = { elapsed + 1 },
                bootIdentity = { boot }, clearArtifacts = {
                    File(directory, "selection-$id.png").delete()
                    throw IOException("injected failure after partial deletion")
                })
            val failed = store.execute(BrowserSelectionStore.ACTION_CLEAR, 4, elapsed + 1)
            assertEquals("CLEAR_FAILED", failed.getString("code"))
            assertEquals(false, failed.getJSONObject("data").getBoolean("cleared"))
            assertTrue(File(directory, "outbox.json").isFile)
            assertTrue(File(directory, "outbox.json").readText().contains("failure fixture"))
            assertTrue(!File(directory, "selection-$id.png").exists())
            assertEquals("ARTIFACT_NOT_FOUND", store.execute(BrowserSelectionStore.ACTION_LATEST, 4, elapsed + 1).getString("code"))
        }
    }

    private fun withSelectionFixture(prefix: String, block: (File, File, String, Long, Long, String) -> Unit) {
        val root = Files.createTempDirectory(prefix).toFile()
        val stream = Files.newDirectoryStream(root.toPath())
        val supported = stream is SecureDirectoryStream<*>
        stream.close()
        assumeTrue("SecureDirectoryStream unavailable on this filesystem", supported)
        try {
            val directory = File(root, "browser-tools/out/selection").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            File(directory, "selection-$id.png").writeBytes(byteArrayOf(1, 2, 3))
            val wall = 1_000_000L
            val elapsed = 50_000L
            val boot = "fixture-boot"
            val record = JSONObject().put("selectionId", id).put("selectionSequence", 1)
                .put("mode", "area").put("comment", "failure fixture")
                .put("artifactPath", "/opt/browser/out/selection/selection-$id.png")
                .put("createdAtWallMillis", wall).put("expiresAtWallMillis", wall + BrowserSelectionStore.TTL_MILLIS)
                .put("createdAtElapsedMillis", elapsed).put("expiresAtElapsedMillis", elapsed + BrowserSelectionStore.TTL_MILLIS)
                .put("bootIdentity", boot).put("deliveryState", "UNBOUND").put("originSessionGeneration", 4)
            File(directory, "outbox.json").writeText(JSONObject().put("version", 2).put("sequence", 1)
                .put("lastWallClockMillis", wall).put("items", JSONArray().put(record)).toString())
            block(root, directory, id, wall, elapsed, boot)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun elapsedDeadlineBlocksAdmissionWhileWallRemainsAbovePersistedBaseline() {
        val root = Files.createTempDirectory("browser-selection-elapsed-ttl").toFile()
        val stream = Files.newDirectoryStream(root.toPath())
        val supported = stream is SecureDirectoryStream<*>
        stream.close()
        assumeTrue("SecureDirectoryStream unavailable on this filesystem", supported)
        try {
            val directory = File(root, "browser-tools/out/selection").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            File(directory, "selection-$id.png").writeBytes(byteArrayOf(1, 2, 3))
            val baselineWall = 1_000_000L
            val baselineElapsed = 50_000L
            val record = JSONObject().put("selectionId", id).put("selectionSequence", 1)
                .put("mode", "area").put("comment", "ttl")
                .put("artifactPath", "/opt/browser/out/selection/selection-$id.png")
                .put("createdAtWallMillis", baselineWall)
                .put("expiresAtWallMillis", baselineWall + BrowserSelectionStore.TTL_MILLIS)
                .put("createdAtElapsedMillis", baselineElapsed)
                .put("expiresAtElapsedMillis", baselineElapsed + BrowserSelectionStore.TTL_MILLIS)
                .put("bootIdentity", "boot-test")
                .put("deliveryState", "CLAIMED").put("destinationSessionKey", "session-a")
                .put("requestId", "a".repeat(32)).put("payloadDigest", "b".repeat(64))
                .put("originSessionGeneration", 4)
            File(directory, "outbox.json").writeText(JSONObject().put("version", 2).put("sequence", 1)
                .put("lastWallClockMillis", baselineWall).put("items", JSONArray().put(record)).toString())

            val rewoundWall = baselineWall + 5 * 60_000L
            val store = BrowserSelectionStore(root, directory, wallClock = { rewoundWall },
                elapsedClock = { baselineElapsed + 31 * 60_000L }, bootIdentity = { "boot-test" })
            val args = JSONObject().put("selectionId", id).put("destinationSessionKey", "session-a")
                .put("requestId", "a".repeat(32)).put("payloadDigest", "b".repeat(64))
            assertEquals("EXPIRED", store.admit(args, baselineElapsed + 31 * 60_000L).getString("code"))
            assertEquals("NOT_FOUND", store.claim(args, baselineElapsed + 31 * 60_000L).getString("code"))
            assertEquals("NOT_FOUND", store.pending(0, 4, baselineElapsed + 31 * 60_000L, "session-a").getString("code"))
            assertEquals("NOT_FOUND", store.recordReceipt(JSONObject().put("selectionId", id)
                .put("destinationSessionKey", "session-a").put("requestId", "a".repeat(32))
                .put("deliveryCode", "UNKNOWN"), 4, baselineElapsed + 31 * 60_000L).getString("code"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun wallClockBelowPersistedBaselineRetainsSelectionForManagementAndBlocksDelivery() {
        val root = Files.createTempDirectory("browser-selection-wall-rollback").toFile()
        val stream = Files.newDirectoryStream(root.toPath())
        val supported = stream is SecureDirectoryStream<*>
        stream.close()
        assumeTrue("Persistent outbox requires SecureDirectoryStream", supported)
        try {
            val directory = File(root, "browser-tools/out/selection").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val image = File(directory, "selection-$id.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val persistedBaseline = 5_000_000L
            val createdElapsed = 50_000L
            val nowElapsed = createdElapsed + 10 * 60_000L
            val record = JSONObject().put("selectionId", id).put("selectionSequence", 1)
                .put("mode", "area").put("comment", "retain during wall rollback")
                .put("artifactPath", "/opt/browser/out/selection/${image.name}")
                .put("createdAtWallMillis", persistedBaseline - 60_000L)
                .put("expiresAtWallMillis", persistedBaseline + BrowserSelectionStore.TTL_MILLIS)
                .put("createdAtElapsedMillis", createdElapsed)
                .put("expiresAtElapsedMillis", createdElapsed + BrowserSelectionStore.TTL_MILLIS)
                .put("bootIdentity", "rollback-boot")
                .put("deliveryState", "CLAIMED").put("destinationSessionKey", "session-a")
                .put("requestId", "a".repeat(32)).put("payloadDigest", "b".repeat(64))
                .put("originSessionGeneration", 4)
            val index = File(directory, "outbox.json")
            index.writeText(JSONObject().put("version", 2).put("sequence", 1)
                .put("lastWallClockMillis", persistedBaseline).put("items", JSONArray().put(record)).toString())

            val store = BrowserSelectionStore(root, directory, wallClock = { persistedBaseline - 5_000L },
                elapsedClock = { nowElapsed }, bootIdentity = { "rollback-boot" })
            val listed = store.list(nowElapsed).getJSONObject("data")
            assertTrue(listed.getBoolean("clockRollback"))
            val item = listed.getJSONArray("items").getJSONObject(0)
            assertEquals("CLOCK_ROLLBACK", item.getString("deliveryBlocked"))
            assertEquals("CLOCK_ROLLBACK", store.pending(0, 4, nowElapsed, "session-a").getString("code"))
            val args = JSONObject().put("selectionId", id).put("destinationSessionKey", "session-a")
                .put("requestId", "a".repeat(32)).put("payloadDigest", "b".repeat(64))
            assertEquals("CLOCK_ROLLBACK", store.claim(args, nowElapsed).getString("code"))
            assertEquals("CLOCK_ROLLBACK", store.admit(args, nowElapsed).getString("code"))
            assertTrue("rollback keeps the image for management", image.isFile)
            assertTrue("rollback keeps the persisted index for management", index.isFile)
            assertTrue(index.readText().contains("\"deliveryState\":\"CLAIMED\""))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun recoveredSelectionWithoutMatchingBootIdentityIsManagementOnly() {
        val root = Files.createTempDirectory("browser-selection-boot-identity").toFile()
        val stream = Files.newDirectoryStream(root.toPath())
        val supported = stream is SecureDirectoryStream<*>
        stream.close()
        assumeTrue("SecureDirectoryStream unavailable on this filesystem", supported)
        try {
            val directory = File(root, "browser-tools/out/selection").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            File(directory, "selection-$id.png").writeBytes(byteArrayOf(1, 2, 3))
            val now = 1_000_000L
            val record = JSONObject().put("selectionId", id).put("selectionSequence", 1)
                .put("mode", "area").put("comment", "manage")
                .put("artifactPath", "/opt/browser/out/selection/selection-$id.png")
                .put("createdAtWallMillis", now).put("expiresAtWallMillis", now + BrowserSelectionStore.TTL_MILLIS)
                .put("createdAtElapsedMillis", 10L).put("expiresAtElapsedMillis", 10L + BrowserSelectionStore.TTL_MILLIS)
                .put("bootIdentity", "old-boot").put("deliveryState", "CLAIMED")
                .put("destinationSessionKey", "session-a").put("requestId", "a".repeat(32))
                .put("payloadDigest", "b".repeat(64)).put("originSessionGeneration", 4)
            File(directory, "outbox.json").writeText(JSONObject().put("version", 2).put("sequence", 1)
                .put("lastWallClockMillis", now).put("items", JSONArray().put(record)).toString())
            val store = BrowserSelectionStore(root, directory, wallClock = { now + 1 },
                elapsedClock = { 20L }, bootIdentity = { "new-boot" })
            val args = JSONObject().put("selectionId", id).put("destinationSessionKey", "session-a")
                .put("requestId", "a".repeat(32)).put("payloadDigest", "b".repeat(64))
            assertEquals("BOOT_IDENTITY_UNAVAILABLE", store.claim(args, 20L).getString("code"))
            assertEquals("BOOT_IDENTITY_UNAVAILABLE", store.admit(args, 20L).getString("code"))
            val listed = store.list(20L).getJSONObject("data").getJSONArray("items").getJSONObject(0)
            assertEquals("CLAIMED", listed.getString("deliveryState"))
            assertEquals("BOOT_IDENTITY_UNAVAILABLE", listed.getString("deliveryBlocked"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun admittingClaimBecomesPersistedUnknownAfterRestart() {
        val root = Files.createTempDirectory("browser-selection-outbox").toFile()
        try {
            val stream = Files.newDirectoryStream(root.toPath())
            val secureStreamsSupported = stream is SecureDirectoryStream<*>
            stream.close()
            assumeTrue("Persistent outbox requires SecureDirectoryStream", secureStreamsSupported)
            val directory = File(root, "browser-tools/out/selection").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            File(directory, "selection-$id.png").writeBytes(byteArrayOf(1, 2, 3))
            val now = System.currentTimeMillis()
            val record = JSONObject()
                .put("selectionId", id)
                .put("selectionSequence", 1)
                .put("mode", "area")
                .put("comment", "keep this context")
                .put("artifactPath", "/opt/browser/out/selection/selection-$id.png")
                .put("createdAtWallMillis", now)
                .put("expiresAtWallMillis", now + BrowserSelectionStore.TTL_MILLIS)
                .put("deliveryState", "ADMITTING")
                .put("destinationSessionKey", "session-a")
                .put("requestId", "a".repeat(32))
                .put("payloadDigest", "b".repeat(64))
                .put("originSessionGeneration", 4)
            File(directory, "outbox.json").writeText(JSONObject()
                .put("version", 1).put("sequence", 1).put("lastWallClockMillis", now)
                .put("items", JSONArray().put(record)).toString())

            val elapsedNow = 123_000L
            val bootNow = "test-boot"
            val recovered = BrowserSelectionStore(root, directory, wallClock = { now + 1 },
                elapsedClock = { elapsedNow }, bootIdentity = { bootNow }).list(elapsedNow)
            val item = recovered.getJSONObject("data").getJSONArray("items").getJSONObject(0)
            assertEquals("UNKNOWN", item.getString("deliveryState"))
            assertEquals("UNKNOWN", item.getJSONObject("deliveryReceipt").getString("deliveryCode"))
            assertTrue(!item.getJSONObject("deliveryReceipt").has("imageIncluded"))

            val secondRestart = BrowserSelectionStore(root, directory, wallClock = { now + 2 },
                elapsedClock = { elapsedNow + 1 }, bootIdentity = { bootNow }).list(elapsedNow + 1)
                .getJSONObject("data").getJSONArray("items").getJSONObject(0)
            assertEquals("UNKNOWN", secondRestart.getString("deliveryState"))
            assertTrue(File(directory, "selection-$id.png").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun explicitClearRemovesPersistedSelectionAndImage() {
        val root = Files.createTempDirectory("browser-selection-clear").toFile()
        val stream = Files.newDirectoryStream(root.toPath())
        val supported = stream is SecureDirectoryStream<*>
        stream.close()
        assumeTrue("SecureDirectoryStream unavailable on this filesystem", supported)
        try {
            val directory = File(root, "browser-tools/out/selection").apply { mkdirs() }
            val id = UUID.randomUUID().toString()
            val image = File(directory, "selection-$id.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val now = System.currentTimeMillis()
            val item = JSONObject().put("selectionId", id).put("selectionSequence", 1)
                .put("mode", "area").put("comment", "clear explicitly")
                .put("artifactPath", "/opt/browser/out/selection/${image.name}")
                .put("createdAtWallMillis", now).put("expiresAtWallMillis", now + BrowserSelectionStore.TTL_MILLIS)
                .put("deliveryState", "UNBOUND").put("originSessionGeneration", 3)
            File(directory, "outbox.json").writeText(JSONObject().put("version", 1).put("sequence", 1)
                .put("lastWallClockMillis", now).put("items", JSONArray().put(item)).toString())

            val store = BrowserSelectionStore(root, directory, wallClock = { now },
                elapsedClock = { 7L }, bootIdentity = { "clear-test-boot" })
            assertTrue(image.isFile)
            assertEquals("OK", store.execute(BrowserSelectionStore.ACTION_CLEAR, 3, 0).getString("code"))
            assertTrue("explicit clear removes the screenshot", !image.exists())
            val index = File(directory, "outbox.json")
            assertTrue("explicit clear removes the persisted index", !index.exists())
            assertTrue("selection directory contains no persisted comment", directory.listFiles().orEmpty().none {
                it.isFile && it.readText().contains("clear explicitly")
            })
            val fresh = BrowserSelectionStore(root, directory, wallClock = { now + 1 },
                elapsedClock = { 8L }, bootIdentity = { "clear-test-boot" }).list(0).getJSONObject("data")
            assertEquals(0, fresh.getJSONArray("items").length())
        } finally {
            root.deleteRecursively()
        }
    }

}
