package com.dshbox.browser.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.Handler
import android.os.HandlerThread
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.nio.channels.SeekableByteChannel
import java.util.concurrent.Semaphore

/**
 * Read-only bridge for sharing one completed Browser download original.
 *
 * The provider is not exported and only accepts `content://<applicationId>.browser.download/<id>`
 * with a 32-hex download id. Metadata and bytes are resolved through [BrowserDownloadArtifacts]
 * anchored to a verified directory handle, so no caller can address a path outside
 * `browser-tools/out/downloads` and no path is re-opened after validation. Access is granted per
 * share intent through `FLAG_GRANT_READ_URI_PERMISSION`; nothing is written or deleted here.
 */
class BrowserDownloadProvider : ContentProvider() {
    override fun onCreate(): Boolean = context != null

    override fun getType(uri: Uri): String? =
        resolveMetadata(uri)?.let { BrowserDownloadArtifacts.mimeTypeForName(it.name) }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val metadata = resolveMetadata(uri) ?: return null
        val columns = projection?.takeIf { it.size <= 8 }?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> metadata.displayName
                    OpenableColumns.SIZE -> metadata.size
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only download URI")
        val app = context ?: throw FileNotFoundException("Download provider unavailable")
        val id = downloadId(uri) ?: throw FileNotFoundException("Download unavailable")
        val source = try {
            BrowserDownloadArtifacts.openCompletedChannel(app.filesDir.toPath(), id)
        } catch (_: Exception) {
            throw FileNotFoundException("Download unavailable")
        }
        return anchoredReadDescriptor(app, source)
    }

    /**
     * Serves seekable reads from the inode-pinned channel. The final file is never re-opened
     * by path, so a swapped directory or symlink cannot be served after validation.
     */
    private fun anchoredReadDescriptor(app: Context, source: SeekableByteChannel): ParcelFileDescriptor {
        if (!readerSlots.tryAcquire()) {
            runCatching { source.close() }
            throw FileNotFoundException("Download readers busy")
        }
        val lease = BrowserDownloadReadLease(source) { readerSlots.release() }
        var handler: Handler? = null
        val expiry = Runnable { runCatching { lease.close() } }
        val absoluteExpiry = Runnable { runCatching { lease.close() } }
        try {
            val callbacks = readerHandler
            handler = callbacks
            val storage = app.getSystemService(StorageManager::class.java)
                ?: throw FileNotFoundException("Download storage unavailable")
            fun renewIdle() {
                callbacks.removeCallbacks(expiry)
                if (!callbacks.postDelayed(expiry, READER_IDLE_MS)) {
                    runCatching { lease.close() }
                    throw ErrnoException("read", OsConstants.EIO)
                }
            }
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = io("size") { lease.size().also { renewIdle() } }
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int =
                    io("read") { lease.read(offset, size, data).also { renewIdle() } }
                override fun onWrite(offset: Long, size: Int, data: ByteArray): Int =
                    throw ErrnoException("write", OsConstants.EBADF)
                override fun onRelease() {
                    callbacks.removeCallbacks(expiry)
                    callbacks.removeCallbacks(absoluteExpiry)
                    runCatching { lease.close() }
                }
            }
            if (!callbacks.postDelayed(expiry, READER_IDLE_MS) ||
                !callbacks.postDelayed(absoluteExpiry, READER_MAX_LIFETIME_MS)) {
                throw FileNotFoundException("Download reader unavailable")
            }
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, callback, callbacks)
        } catch (error: Exception) {
            handler?.removeCallbacks(expiry)
            handler?.removeCallbacks(absoluteExpiry)
            runCatching { lease.close() }
            throw FileNotFoundException("Download reader unavailable").apply { initCause(error) }
        }
    }

    private inline fun <T> io(operation: String, block: () -> T): T = try { block() }
        catch (_: Exception) { throw ErrnoException(operation, OsConstants.EIO) }

    private fun resolveMetadata(uri: Uri): BrowserDownloadMetadata? {
        val app = context ?: return null
        val id = downloadId(uri) ?: return null
        return BrowserDownloadArtifacts.completedMetadata(app.filesDir.toPath(), id)
    }

    private fun downloadId(uri: Uri): String? {
        val app = context ?: return null
        if (uri.scheme != "content" || uri.authority != "${app.packageName}.browser.download" ||
            uri.query != null || uri.fragment != null || uri.pathSegments.size != 1
        ) return null
        return uri.pathSegments[0].takeIf { ID.matches(it) }
    }

    companion object {
        private val ID = Regex("^[a-f0-9]{32}$")
        private const val READER_IDLE_MS = 2 * 60 * 1000L
        private const val READER_MAX_LIFETIME_MS = 2 * 60 * 60 * 1000L
        private val readerSlots = Semaphore(8)
        private val readerHandler: Handler by lazy {
            Handler(HandlerThread("browser-download-read").apply { start() }.looper)
        }

        /** Builds the read-only URI for one completed Browser download id. */
        fun uriFor(context: Context, id: String): Uri {
            require(ID.matches(id)) { "invalid download id" }
            return Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.browser.download")
                .appendPath(id)
                .build()
        }
    }
}
