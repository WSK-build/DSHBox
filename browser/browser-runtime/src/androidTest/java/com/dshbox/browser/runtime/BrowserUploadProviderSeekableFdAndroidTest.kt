package com.dshbox.browser.runtime

import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserUploadProviderSeekableFdAndroidTest {
    @Test
    fun approvedSnapshotIsSeekableAndContainsOnlyHashVerifiedBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folderId = UUID.randomUUID().toString().replace("-", "")
        val grantId = UUID.randomUUID().toString().replace("-", "")
        val userData = File(context.filesDir, "user-data")
        val fixtureDirectory = File(userData, "provider-test-$folderId")
        val fixture = File(fixtureDirectory, "qa-report.txt")
        val expected = "browser upload test".toByteArray(Charsets.UTF_8)
        var staged: List<BrowserUploadStagedFile> = emptyList()
        var descriptor: ParcelFileDescriptor? = null
        var token: String? = null
        var secondToken: String? = null
        var expiryToken: String? = null
        var registerMarker: File? = null
        val openReaders = mutableListOf<ParcelFileDescriptor>()
        var duplicatedReader: ParcelFileDescriptor? = null
        try {
            assertTrue(fixtureDirectory.mkdirs())
            fixture.writeBytes(expected)
            val source = BrowserUploadPolicy.inspect(userData, fixture.relativeTo(userData).invariantSeparatorsPath)
            assertNotNull(source)
            staged = BrowserUploadStager(context.filesDir).stage(grantId, listOf(source!!))
            val uriToken = UUID.randomUUID().toString().replace("-", "")
            token = uriToken
            val uris = BrowserUploadProvider.register(context, uriToken, staged.associateBy { it.fileId },
                System.currentTimeMillis() + 60_000L)
            val uri: Uri = uris.single()

            val provider = BrowserUploadProvider()
            provider.attachInfo(context, ProviderInfo().apply { authority = "${context.packageName}.browser.upload" })
            val snapshotDirectory = File(context.cacheDir, "browser-upload-provider")
            val snapshotsBeforeOpen = snapshotDirectory.listFiles().orEmpty()
                .filter { it.name.matches(Regex("verified-[A-Za-z0-9]+\\.bin")) }.toSet()
            descriptor = provider.openFile(uri, "r")
            val createdSnapshots = snapshotDirectory.listFiles().orEmpty()
                .filter { it.name.matches(Regex("verified-[A-Za-z0-9]+\\.bin")) }.toSet() - snapshotsBeforeOpen
            assertEquals("first open creates one stable snapshot", 1, createdSnapshots.size)
            val sharedSnapshot = createdSnapshots.single()
            val marker = File(snapshotDirectory, "verified-register-marker.bin").apply { writeText("orphan marker") }
            registerMarker = marker
            val additionalToken = UUID.randomUUID().toString().replace("-", "")
            secondToken = additionalToken
            BrowserUploadProvider.register(context, additionalToken, staged.associateBy { it.fileId },
                System.currentTimeMillis() + 60_000L)
            assertTrue("second register must not rescan a live cache", sharedSnapshot.isFile && marker.isFile)
            BrowserUploadProvider.revoke(additionalToken)
            marker.delete()
            registerMarker = null
            assertEquals(19L, descriptor!!.statSize)
            assertEquals(19L, Os.lseek(descriptor!!.fileDescriptor, 0L, OsConstants.SEEK_END))
            assertEquals(0L, Os.lseek(descriptor!!.fileDescriptor, 0L, OsConstants.SEEK_SET))
            val firstStat = Os.fstat(descriptor!!.fileDescriptor)
            val firstMtime = firstStat.st_mtime
            val firstInode = firstStat.st_ino
            duplicatedReader = ParcelFileDescriptor.dup(descriptor!!.fileDescriptor)
            descriptor!!.close()
            descriptor = null
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals("normal close preserves renderer duplicate", 19L, duplicatedReader!!.statSize)
            Os.lseek(duplicatedReader!!.fileDescriptor, 0L, OsConstants.SEEK_SET)
            val bytes = readUsingTemporaryDuplicate(duplicatedReader!!)
            assertEquals(expected.size, bytes.size)
            assertEquals(sha256(expected), sha256(bytes))
            Os.lseek(duplicatedReader!!.fileDescriptor, 0L, OsConstants.SEEK_SET)

            descriptor = provider.openFile(uri, "r")
            val reopenedStat = Os.fstat(descriptor!!.fileDescriptor)
            val reopenedMtime = reopenedStat.st_mtime
            assertEquals("every open has stable upload mtime",
                BrowserUploadProvider.STABLE_SNAPSHOT_MTIME_MILLIS / 1_000L, firstMtime)
            assertEquals(firstMtime, reopenedMtime)
            assertEquals("same grant/file uses one controlled snapshot inode", firstInode, reopenedStat.st_ino)
            Os.lseek(descriptor!!.fileDescriptor, 0L, OsConstants.SEEK_SET)
            val concurrentReader = provider.openFile(uri, "r")
            Os.lseek(descriptor!!.fileDescriptor, 5L, OsConstants.SEEK_SET)
            assertEquals("each open has an independent read offset", 0L,
                Os.lseek(concurrentReader.fileDescriptor, 0L, OsConstants.SEEK_CUR))
            concurrentReader.close()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Os.lseek(descriptor!!.fileDescriptor, 0L, OsConstants.SEEK_SET)
            val reopenedBytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor!!).use { it.readBytes() }
            descriptor = null
            assertEquals(sha256(expected), sha256(reopenedBytes))

            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            repeat(8) { openReaders += provider.openFile(uri, "r") }
            assertTrue(runCatching { provider.openFile(uri, "r").close(); false }
                .exceptionOrNull() is FileNotFoundException)
            openReaders.removeAt(0).close()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            openReaders += provider.openFile(uri, "r") // reader close released its reservation
            BrowserUploadProvider.revoke(uriToken)
            assertEquals(0L, duplicatedReader!!.statSize)
            assertEquals(0, readUsingTemporaryDuplicate(duplicatedReader!!).size)
            duplicatedReader!!.close()
            duplicatedReader = null

            val shortToken = UUID.randomUUID().toString().replace("-", "")
            expiryToken = shortToken
            val shortUri = BrowserUploadProvider.register(context, shortToken, staged.associateBy { it.fileId },
                System.currentTimeMillis() + 300L).single()
            descriptor = provider.openFile(shortUri, "r")
            val expiryReader = ParcelFileDescriptor.dup(descriptor!!.fileDescriptor)
            descriptor!!.close()
            descriptor = null
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val expiryBytes = readUsingTemporaryDuplicate(expiryReader)
            assertEquals(19, expiryBytes.size)
            assertEquals(sha256(expected), sha256(expiryBytes))
            Os.lseek(expiryReader.fileDescriptor, 0L, OsConstants.SEEK_SET)
            val expireBy = SystemClock.elapsedRealtime() + 3_000L
            while (expiryReader.statSize != 0L && SystemClock.elapsedRealtime() < expireBy) Thread.sleep(25L)
            assertEquals("TTL revokes bytes from retained renderer duplicate", 0L, expiryReader.statSize)
            assertEquals(0, readUsingTemporaryDuplicate(expiryReader).size)
            expiryReader.close()
            BrowserUploadProvider.revoke(shortToken)
            assertTrue(BrowserUploadStager(context.filesDir).revoke(grantId))
        } finally {
            runCatching { token?.let(BrowserUploadProvider::revoke) }
            runCatching { secondToken?.let(BrowserUploadProvider::revoke) }
            runCatching { expiryToken?.let(BrowserUploadProvider::revoke) }
            runCatching { registerMarker?.delete() }
            openReaders.forEach { runCatching { it.close() } }
            runCatching { duplicatedReader?.close() }
            runCatching { descriptor?.close() }
            runCatching { if (staged.isNotEmpty()) BrowserUploadStager(context.filesDir).revoke(grantId) }
            runCatching { fixtureDirectory.deleteRecursively() }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun readUsingTemporaryDuplicate(reader: ParcelFileDescriptor): ByteArray {
        val duplicate = ParcelFileDescriptor.dup(reader.fileDescriptor)
        return ParcelFileDescriptor.AutoCloseInputStream(duplicate).use { it.readBytes() }
    }
}
