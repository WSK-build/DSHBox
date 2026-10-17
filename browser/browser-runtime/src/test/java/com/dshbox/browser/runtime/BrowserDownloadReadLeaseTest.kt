package com.dshbox.browser.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.channels.SeekableByteChannel

class BrowserDownloadReadLeaseTest {
    @Test fun closeFailureStillReleasesSlotExactlyOnce() {
        val path = Files.createTempFile("browser-read-lease", ".bin")
        try {
            val underlying = Files.newByteChannel(path, StandardOpenOption.READ)
            val source = object : SeekableByteChannel by underlying {
                override fun close() {
                    underlying.close()
                    throw IOException("close fixture")
                }
            }
            var releases = 0
            val lease = BrowserDownloadReadLease(source) { releases++ }
            assertThrows(IOException::class.java) { lease.close() }
            lease.close()
            assertEquals(1, releases)
            assertFalse(underlying.isOpen)
            assertThrows(IOException::class.java) { lease.size() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun randomReadsAndEofUseRequestedOffsets() {
        val path = Files.createTempFile("browser-read-lease", ".bin")
        try {
            Files.write(path, byteArrayOf(1, 2, 3, 4, 5))
            var releases = 0
            val source = Files.newByteChannel(path, StandardOpenOption.READ)
            val lease = BrowserDownloadReadLease(source) { releases++ }
            assertEquals(5L, lease.size())
            val data = ByteArray(3)
            assertEquals(3, lease.read(2, 3, data))
            assertArrayEquals(byteArrayOf(3, 4, 5), data)
            assertEquals(1, lease.read(4, 3, data))
            assertEquals(0, lease.read(5, 3, data))
            assertEquals(3, lease.read(0, 3, data))
            assertArrayEquals(byteArrayOf(1, 2, 3), data)
            lease.close()
            lease.close()
            assertEquals(1, releases)
            assertFalse(source.isOpen)
            assertThrows(IOException::class.java) { lease.read(0, 1, data) }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun invalidRangesDoNotMoveChannelOrReleaseOwnership() {
        val path = Files.createTempFile("browser-read-lease", ".bin")
        try {
            Files.write(path, byteArrayOf(7))
            val source = Files.newByteChannel(path, StandardOpenOption.READ)
            var releases = 0
            val lease = BrowserDownloadReadLease(source) { releases++ }
            try {
                assertThrows(IllegalArgumentException::class.java) { lease.read(-1, 1, ByteArray(1)) }
                assertThrows(IllegalArgumentException::class.java) { lease.read(0, 2, ByteArray(1)) }
                assertEquals(0L, source.position())
                assertEquals(0, releases)
                assertEquals(0, lease.read(0, 0, ByteArray(0)))
            } finally { lease.close() }
            assertEquals(1, releases)
        } finally { Files.deleteIfExists(path) }
    }
}
