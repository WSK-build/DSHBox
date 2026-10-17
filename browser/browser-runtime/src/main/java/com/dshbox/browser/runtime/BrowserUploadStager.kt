package com.dshbox.browser.runtime

import java.io.File
import java.nio.channels.SeekableByteChannel

internal data class BrowserUploadStagedFile(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    /** Display/path metadata only; all file I/O goes through BrowserUploadFileAccess descriptors. */
    val file: File,
    val guestPath: String,
    val sha256: String = "",
)

/** Stages validated project files privately, then atomically publishes the grant into /opt/browser/upload. */
internal class BrowserUploadStager(private val filesRoot: File) {
    fun stage(grantId: String, sources: List<BrowserUploadSource>): List<BrowserUploadStagedFile> {
        require(grantId.matches(Regex("[a-f0-9]{32}")))
        require(sources.size in 1..BrowserUploadPolicy.MAX_FILES && sources.sumOf { it.size } <= BrowserUploadPolicy.MAX_TOTAL_BYTES)
        return BrowserUploadFileAccess.stage(filesRoot, grantId, sources)
    }

    fun openForRead(grantId: String, diskName: String, size: Long, identity: String): SeekableByteChannel =
        BrowserUploadFileAccess.openStagedChannel(filesRoot, grantId, diskName, size, identity)

    fun revoke(grantId: String): Boolean = BrowserUploadFileAccess.revoke(filesRoot, grantId)

    fun cleanupExpired(nowMillis: Long = System.currentTimeMillis()) = BrowserUploadFileAccess.cleanupExpired(filesRoot, nowMillis)
}
