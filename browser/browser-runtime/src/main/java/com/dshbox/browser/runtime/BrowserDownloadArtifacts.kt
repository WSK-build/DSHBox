package com.dshbox.browser.runtime

import java.io.IOException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

/** Outcome of deleting one Browser-owned completed download original. */
enum class BrowserDownloadDeleteResult { DELETED, NOT_FOUND, NOT_COMPLETED, UNSAFE }

/** Anchored metadata for a completed Browser-owned download original. */
data class BrowserDownloadMetadata(val id: String, val name: String, val size: Long) {
    val displayName: String get() = name.substringAfter('_')
}

/**
 * Owns the on-disk identity of Browser download originals.
 *
 * Only files named `<32 hex id>_<safe name>` inside `browser-tools/out/downloads` are recognized.
 * Every lookup, read and deletion runs relative to verified directory handles: metadata is read
 * from the handle and byte access goes through [openCompletedChannel], which pins the inode while
 * the handle is still open. No caller ever receives a path to re-open. User exports are separate
 * copies created through SAF and are never touched here.
 */
internal object BrowserDownloadArtifacts {
    const val RELATIVE_DIRECTORY = "browser-tools/out/downloads"
    private const val MAX_SCAN = BrowserDownloadPolicy.MAX_DIRECTORY_SCAN
    private val OWNED_NAME = Regex("^([a-f0-9]{32})_[A-Za-z0-9_.-]{1,180}$")
    private val ID = Regex("^[a-f0-9]{32}$")
    private val noFollow = LinkOption.NOFOLLOW_LINKS

    /** Returns the owning download id when [name] is a Browser-owned final artifact name. */
    fun ownedIdFor(name: String): String? = OWNED_NAME.matchEntire(name)?.groupValues?.get(1)

    /** Opens the Browser download directory relative to a verified app-private root. */
    fun openDownloads(filesDirectory: Path): SecureDirectoryStream<Path> {
        val anchor = filesDirectory.toRealPath()
        if (!Files.isDirectory(anchor, noFollow)) throw IOException("invalid app directory")
        @Suppress("UNCHECKED_CAST")
        var current = Files.newDirectoryStream(anchor) as? SecureDirectoryStream<Path>
            ?: throw IOException("secure directory operations are unavailable")
        try {
            for (segment in listOf("browser-tools", "out", "downloads")) {
                val next = current.newDirectoryStream(Paths.get(segment), noFollow)
                current.close()
                current = next
            }
            return current
        } catch (error: Throwable) {
            runCatching { current.close() }
            throw IOException("Browser downloads directory is missing or unsafe", error)
        }
    }

    /** Anchored metadata only; byte access must use [openCompletedChannel]. */
    fun completedMetadata(filesDirectory: Path, id: String): BrowserDownloadMetadata? {
        require(ID.matches(id)) { "invalid download id" }
        return runCatching {
            openDownloads(filesDirectory).use { directory ->
                val name = findOwned(directory, id) ?: return@use null
                val attributes = attributes(directory, name) ?: return@use null
                if (!attributes.isRegularFile || attributes.isSymbolicLink) return@use null
                BrowserDownloadMetadata(id, name, attributes.size())
            }
        }.getOrNull()
    }

    /**
     * Opens one completed original as a channel pinned to its inode while the verified directory
     * handle is still open. The leaf is opened with NOFOLLOW and its identity and size are
     * re-checked after the open, so a swapped directory or symlink is never handed to a reader.
     * The caller owns and must close the returned channel.
     */
    fun openCompletedChannel(filesDirectory: Path, id: String): SeekableByteChannel {
        require(ID.matches(id)) { "invalid download id" }
        val directory = openDownloads(filesDirectory)
        try {
            val name = findOwned(directory, id) ?: throw NoSuchFileException(id)
            val before = attributes(directory, name) ?: throw NoSuchFileException(id)
            if (!before.isRegularFile || before.isSymbolicLink) throw IOException("unsafe download original")
            val identity = before.fileKey()?.toString() ?: throw IOException("download identity unavailable")
            val channel = openReadAt(directory, name)
            try {
                val after = attributes(directory, name) ?: throw NoSuchFileException(id)
                if (!after.isRegularFile || after.isSymbolicLink || after.fileKey()?.toString() != identity ||
                    after.size() != before.size() || channel.size() != before.size()
                ) {
                    throw IOException("download changed while opening")
                }
                return channel
            } catch (error: Throwable) {
                runCatching { channel.close() }
                throw error
            }
        } finally {
            runCatching { directory.close() }
        }
    }

    /** Deletes exactly one owned completed original; exported copies and unknown names are untouched. */
    fun deleteCompleted(filesDirectory: Path, id: String): BrowserDownloadDeleteResult {
        require(ID.matches(id)) { "invalid download id" }
        return try {
            openDownloads(filesDirectory).use { directory ->
                val name = findOwned(directory, id) ?: return@use BrowserDownloadDeleteResult.NOT_FOUND
                val attributes = attributes(directory, name)
                if (attributes == null || !attributes.isRegularFile || attributes.isSymbolicLink) {
                    return@use BrowserDownloadDeleteResult.UNSAFE
                }
                directory.deleteFile(Paths.get(name))
                BrowserDownloadDeleteResult.DELETED
            }
        } catch (_: IOException) {
            BrowserDownloadDeleteResult.NOT_FOUND
        } catch (_: SecurityException) {
            BrowserDownloadDeleteResult.UNSAFE
        }
    }

    /** Maps a final artifact name to its MIME type without trusting the URL. */
    fun mimeTypeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "txt", "log" -> "text/plain"
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun openReadAt(directory: SecureDirectoryStream<Path>, name: String): SeekableByteChannel =
        directory.newByteChannel(Paths.get(name), setOf<OpenOption>(StandardOpenOption.READ, noFollow))

    private fun findOwned(directory: SecureDirectoryStream<Path>, id: String): String? {
        var visited = 0
        for (entry in directory) {
            if (++visited > MAX_SCAN) return null
            val name = entry.fileName?.toString() ?: continue
            if (ownedIdFor(name) != id) continue
            val attributes = attributes(directory, name) ?: continue
            if (attributes.isRegularFile && !attributes.isSymbolicLink) return name
        }
        return null
    }

    private fun attributes(directory: SecureDirectoryStream<Path>, name: String): BasicFileAttributes? = runCatching {
        directory.getFileAttributeView(Paths.get(name), BasicFileAttributeView::class.java, noFollow)
            ?.readAttributes()
    }.getOrNull()
}
