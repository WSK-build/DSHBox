package com.dshbox.dsh.renderer.api

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/** Session-owned external-file copy. Local files must be handed to the engine directly. */
object DshFileStaging {
    /**
     * Call on an IO worker. [openSource] must open the selected URI through ContentResolver.
     * The returned file belongs to the caller until the owning renderer session is destroyed.
     * A failure removes only this operation's temporary file, never the original source.
     */
    fun copy(
        directory: File,
        maxBytes: Long,
        reserveBytes: Long,
        openSource: () -> InputStream,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
        availableBytes: () -> Long = { directory.usableSpace },
    ): File {
        require(maxBytes > 0)
        require(reserveBytes >= 0)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create upload staging directory")
        }
        val staged = File.createTempFile("upload-", ".part", directory)
        try {
            openSource().use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        if (isCancelled()) throw CancellationException("Upload staging cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (count.toLong() > maxBytes - copied) {
                            throw IOException("Upload staging byte limit exceeded")
                        }
                        val available = availableBytes()
                        if (available < reserveBytes || count.toLong() > available - reserveBytes) {
                            throw IOException("Insufficient upload staging space")
                        }
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied)
                    }
                    if (isCancelled()) throw CancellationException("Upload staging cancelled")
                }
            }
            return staged
        } catch (failure: Throwable) {
            if (!staged.delete() && staged.exists()) {
                failure.addSuppressed(IOException("Cannot remove failed upload staging file"))
            }
            throw failure
        }
    }
}
