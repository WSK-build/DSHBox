package com.dshbox.browser.runtime

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Owns an inode-pinned read channel and releases its admission slot exactly once. */
internal class BrowserDownloadReadLease(
    private val source: SeekableByteChannel,
    private val releaseSlot: () -> Unit,
) : Closeable {
    private var closed = false

    @Synchronized
    fun size(): Long {
        checkOpen()
        return source.size()
    }

    @Synchronized
    fun read(offset: Long, size: Int, data: ByteArray): Int {
        checkOpen()
        require(offset >= 0 && size >= 0 && size <= data.size)
        source.position(offset)
        val buffer = ByteBuffer.wrap(data, 0, size)
        var total = 0
        while (buffer.hasRemaining()) {
            val count = source.read(buffer)
            if (count < 0) break
            if (count == 0) throw IOException("Download channel made no progress")
            total += count
        }
        return total
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try { source.close() } finally { releaseSlot() }
    }

    private fun checkOpen() {
        if (closed) throw IOException("Download reader closed")
    }
}
