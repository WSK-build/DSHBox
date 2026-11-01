package com.dshbox.dsh.renderer.api

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException

class DshFileStagingTest {
    private fun withDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("dsh-staging-test").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun unknownLengthStreamsInChunksAndClosesSource() = withDirectory { directory ->
        val bytes = ByteArray(160_000) { (it % 251).toByte() }
        var closed = false
        var lastProgress = 0L
        val source = object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        }
        val staged = DshFileStaging.copy(directory, 200_000, 100, { source }, { false }, {
            assertTrue(it > lastProgress)
            lastProgress = it
        }, { 1_000_000 })
        assertArrayEquals(bytes, staged.readBytes())
        assertEquals(160_000L, lastProgress)
        assertTrue(closed)
    }

    @Test fun actualBytesCannotExceedLimitAndPartialFileIsRemoved() = withDirectory { directory ->
        assertThrows(IOException::class.java) {
            DshFileStaging.copy(directory, 10, 0,
                { ByteArrayInputStream(ByteArray(11)) }, { false }, {}, { Long.MAX_VALUE })
        }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun cancellationClosesInputAndPreservesOtherFiles() = withDirectory { directory ->
        val retained = File(directory, "another-session").apply { writeText("keep") }
        var cancelled = false
        var closed = false
        val input = object : ByteArrayInputStream(ByteArray(160_000)) {
            override fun close() { closed = true; super.close() }
        }
        assertThrows(CancellationException::class.java) {
            DshFileStaging.copy(directory, 200_000, 0, { input }, { cancelled },
                { cancelled = true }, { Long.MAX_VALUE })
        }
        assertTrue(closed)
        assertEquals(listOf(retained.name), directory.list()!!.toList())
        assertEquals("keep", retained.readText())
    }

    @Test fun insufficientSpaceFailsBeforeWritingAndRemovesPartialFile() = withDirectory { directory ->
        assertThrows(IOException::class.java) {
            DshFileStaging.copy(directory, 100, 50,
                { ByteArrayInputStream(ByteArray(60)) }, { false }, {}, { 100 })
        }
        assertEquals(0, directory.listFiles()!!.size)
    }
}
