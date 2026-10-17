package com.dshbox.browser.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Paths
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** All upload file access is relative to pinned SecureDirectoryStreams; no path I/O fallback exists. */
internal object BrowserUploadFileAccess {
    private const val PRIVATE_ROOT = ".browser-upload-private"
    private const val BROWSER_TOOLS = "browser-tools"
    private const val UPLOAD_ROOT = "upload"
    const val MAX_STAGING_BYTES = 128L * 1024 * 1024
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    fun inspectProjectFile(
        filesRoot: File,
        relativePath: String,
        onFailure: (BrowserUploadInspectStage) -> Unit = {},
    ): BrowserUploadSource? {
        var stage = BrowserUploadInspectStage.RELATIVE_VALIDATION
        fun denied(at: BrowserUploadInspectStage = stage): BrowserUploadSource? {
            runCatching { onFailure(at) }
            return null
        }

        return try {
            val parts = BrowserUploadPolicy.validateRelativePath(relativePath)
                ?: return denied(BrowserUploadInspectStage.RELATIVE_VALIDATION)
            stage = BrowserUploadInspectStage.ROOT_OPEN
            val root = openRoot(filesRoot)
            try {
                stage = BrowserUploadInspectStage.PARENT_WALK
                withDirectoryChain(root, listOf("user-data") + parts.dropLast(1)) { parent ->
                    val name = parts.last()
                    stage = BrowserUploadInspectStage.SOURCE_ATTRIBUTES
                    val before = attrsAt(parent, name)
                        ?: return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_ATTRIBUTES)
                    if (!before.isRegularFile || before.size() > BrowserUploadPolicy.MAX_FILE_BYTES) {
                        return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_ATTRIBUTES)
                    }

                    stage = BrowserUploadInspectStage.SOURCE_IDENTITY
                    val key = runCatching { identity(before) }.getOrNull()
                        ?: return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_IDENTITY)

                    stage = BrowserUploadInspectStage.SOURCE_OPEN
                    val channel = runCatching { openReadAt(parent, name) }.getOrNull()
                        ?: return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_OPEN)
                    try {
                        stage = BrowserUploadInspectStage.SOURCE_SNAPSHOT
                        val opened = attrsAt(parent, name)
                            ?: return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_SNAPSHOT)
                        val openedKey = runCatching { identity(opened) }.getOrNull()
                            ?: return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_IDENTITY)
                        if (!opened.isRegularFile || openedKey != key || !sameMetadataSnapshot(before, opened) ||
                            runCatching { channel.size() }.getOrNull() != opened.size()
                        ) return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_SNAPSHOT)

                        stage = BrowserUploadInspectStage.SOURCE_HASH
                        val digest = runCatching { hashChannel(channel, BrowserUploadPolicy.MAX_FILE_BYTES) }.getOrNull()
                            ?: return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_HASH)
                        if (digest.first != opened.size()) {
                            return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_HASH)
                        }

                        stage = BrowserUploadInspectStage.FINAL_SNAPSHOT
                        val after = attrsAt(parent, name)
                            ?: return@withDirectoryChain denied(BrowserUploadInspectStage.FINAL_SNAPSHOT)
                        val afterKey = runCatching { identity(after) }.getOrNull()
                            ?: return@withDirectoryChain denied(BrowserUploadInspectStage.SOURCE_IDENTITY)
                        if (afterKey != key || !sameMetadataSnapshot(opened, after)) {
                            return@withDirectoryChain denied(BrowserUploadInspectStage.FINAL_SNAPSHOT)
                        }

                        val file = File(filesRoot, "user-data/$relativePath")
                        BrowserUploadSource(UUID.randomUUID().toString().replace("-", ""), relativePath, name,
                            BrowserUploadPolicy.mimeTypeForExtension(name.substringAfterLast('.', "")), opened.size(), file,
                            opened.lastModifiedTime().toMillis(), digest.second, key)
                    } finally {
                        channel.close()
                    }
                }
            } finally {
                root.close()
            }
        } catch (_: Throwable) {
            denied()
        }
    }

    /** Reopens the chosen path through pinned directory handles and publishes no bytes before hash verification. */
    @Synchronized fun stage(filesRoot: File, grantId: String, sources: List<BrowserUploadSource>): List<BrowserUploadStagedFile> {
        require(validId(grantId) && sources.size in 1..BrowserUploadPolicy.MAX_FILES)
        require(sources.sumOf { it.size } <= BrowserUploadPolicy.MAX_TOTAL_BYTES)
        val canonical = canonicalRoot(filesRoot)
        ensureDirectory(canonical.toPath(), PRIVATE_ROOT)
        return withRoot(filesRoot) { root ->
            withDirectoryChain(root, listOf(PRIVATE_ROOT)) { privateRoot ->
                val privatePath = canonical.toPath().resolve(PRIVATE_ROOT)
                initializePublicDirectory(root, privateRoot, privatePath, BROWSER_TOOLS)
                withDirectoryChain(root, listOf(BROWSER_TOOLS)) { tools ->
                    initializePublicDirectory(tools, privateRoot, privatePath, UPLOAD_ROOT)
                }
                withDirectoryChain(root, listOf(BROWSER_TOOLS, UPLOAD_ROOT)) { publicRoot ->
                    require(stagedUsage(privateRoot) + stagedUsage(publicRoot) + sources.sumOf { it.size } <= MAX_STAGING_BYTES) {
                        "Browser upload staging quota exceeded"
                    }
                    require(attrsAt(publicRoot, grantId) == null)
                    val privatePath = canonical.toPath().resolve(PRIVATE_ROOT).resolve(grantId)
                    Files.createDirectory(privatePath)
                    val staged = try {
                        withDirectoryChain(privateRoot, listOf(grantId)) { grantDirectory ->
                            val output = sources.map { source ->
                                require(source.id.matches(Regex("[a-f0-9]{32}")) && BrowserUploadPolicy.isSafeName(source.name))
                                val diskName = "${source.id}_${source.name}"
                                val tempName = ".tmp-${UUID.randomUUID().toString().replace("-", "") }"
                                copyApprovedSource(filesRoot, source, grantDirectory, tempName)
                                grantDirectory.move(Paths.get(tempName), grantDirectory, Paths.get(diskName))
                                BrowserUploadStagedFile(
                                    source.id, source.name, source.mimeType, source.size,
                                    File(canonical, "$BROWSER_TOOLS/$UPLOAD_ROOT/$grantId/$diskName"),
                                    "/opt/browser/upload/$grantId/$diskName",
                                    source.sha256,
                                )
                            }
                            require(output.sumOf { it.size } <= BrowserUploadPolicy.MAX_TOTAL_BYTES)
                            output
                        }
                    } catch (failure: Throwable) {
                        runCatching { deleteEntry(privateRoot, grantId) }
                        throw failure
                    }
                    try {
                        privateRoot.move(Paths.get(grantId), publicRoot, Paths.get(grantId))
                    } catch (failure: Throwable) {
                        runCatching { deleteEntry(privateRoot, grantId) }
                        throw failure
                    }
                    staged
                }
            }
        }
    }

    fun stagedSize(filesRoot: File, grantId: String, diskName: String): Long? = runCatching {
        require(validId(grantId) && safeLeaf(diskName))
        withRoot(filesRoot) { root ->
            withDirectoryChain(root, listOf(BROWSER_TOOLS, UPLOAD_ROOT, grantId)) { grant ->
                val attrs = attrsAt(grant, diskName) ?: return@withDirectoryChain null
                require(attrs.isRegularFile && attrs.size() in 0..BrowserUploadPolicy.MAX_FILE_BYTES)
                attrs.size()
            }
        }
    }.getOrNull()

    fun stagedIdentity(filesRoot: File, grantId: String, diskName: String): String? = runCatching {
        require(validId(grantId) && safeLeaf(diskName))
        withRoot(filesRoot) { root -> withDirectoryChain(root, listOf(BROWSER_TOOLS, UPLOAD_ROOT, grantId)) { grant ->
            val attrs = attrsAt(grant, diskName) ?: return@withDirectoryChain null
            require(attrs.isRegularFile && attrs.size() <= BrowserUploadPolicy.MAX_FILE_BYTES)
            identity(attrs)
        } }
    }.getOrNull()


    /** Returns an already-open channel pinned to the selected inode; callers never reopen a display path. */
    fun openStagedChannel(filesRoot: File, grantId: String, diskName: String, expectedSize: Long, expectedIdentity: String): SeekableByteChannel {
        require(validId(grantId) && safeLeaf(diskName) && expectedSize in 0..BrowserUploadPolicy.MAX_FILE_BYTES)
        val root = openRoot(filesRoot)
        val streams = ArrayList<SecureDirectoryStream<Path>>()
        try {
            var current = root
            for (segment in listOf(BROWSER_TOOLS, UPLOAD_ROOT, grantId)) {
                current = openDirectoryAt(current, segment).also(streams::add)
            }
            val before = attrsAt(current, diskName) ?: throw java.io.FileNotFoundException()
            if (!before.isRegularFile || before.size() != expectedSize || identity(before) != expectedIdentity) throw java.io.FileNotFoundException()
            val channel = openReadAt(current, diskName)
            val after = attrsAt(current, diskName)
            if (after == null || !after.isRegularFile || identity(after) != expectedIdentity || !sameSnapshot(before, after) || channel.size() != expectedSize) {
                channel.close()
                throw java.io.FileNotFoundException()
            }
            return channel
        } finally { streams.asReversed().forEach { runCatching { it.close() } }; root.close() }
    }

    /** Authenticate all approved bytes before a provider publishes even the first byte. */
    fun readApprovedStagedBytes(filesRoot: File, grantId: String, diskName: String, size: Long, identity: String,
        expectedHash: String, stillAllowed: () -> Boolean): ByteArray {
        require(size in 0..BrowserUploadPolicy.MAX_FILE_BYTES && expectedHash.matches(Regex("[a-f0-9]{64}")))
        return openStagedChannel(filesRoot, grantId, diskName, size, identity).use { channel ->
            val bytes = ByteArray(size.toInt())
            var authenticated = false
            try {
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = 0
            while (offset < bytes.size) {
                check(stillAllowed()) { "Upload grant expired" }
                val count = channel.read(ByteBuffer.wrap(bytes, offset, minOf(16 * 1024, bytes.size - offset)))
                require(count > 0) { "Upload content was truncated" }
                digest.update(bytes, offset, count)
                offset += count
            }
            check(stillAllowed() && channel.size() == size)
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            check(actual == expectedHash) { "Upload content changed after approval" }
            authenticated = true
            bytes
            } finally { if (!authenticated) bytes.fill(0) }
        }
    }

    fun revoke(filesRoot: File, grantId: String): Boolean {
        if (!validId(grantId)) return false
        return runCatching {
            withRoot(filesRoot) { root ->
                var removed = false
                withDirectoryChainOrNull(root, listOf(BROWSER_TOOLS, UPLOAD_ROOT)) { removed = deleteEntry(it, grantId) || removed }
                withDirectoryChainOrNull(root, listOf(PRIVATE_ROOT)) { removed = deleteEntry(it, grantId) || removed }
                removed
            }
        }.getOrDefault(false)
    }

    fun cleanupExpired(filesRoot: File, nowMillis: Long) {
        runCatching {
            withRoot(filesRoot) { root ->
                withDirectoryChainOrNull(root, listOf(PRIVATE_ROOT)) { cleanupDirectory(it, nowMillis) }
                withDirectoryChainOrNull(root, listOf(BROWSER_TOOLS, UPLOAD_ROOT)) { cleanupDirectory(it, nowMillis) }
            }
        }
    }

    private fun stagedUsage(directory: SecureDirectoryStream<Path>): Long {
        var total = 0L
        var grants = 0
        for (entry in directory) {
            val name = entry.fileName.toString()
            if (!validId(name)) continue
            require(++grants <= 64) { "Browser upload grant quota exceeded" }
            val attrs = attrsAt(directory, name) ?: continue
            if (!attrs.isDirectory || attrs.isSymbolicLink) continue
            openDirectoryAt(directory, name).use { child ->
                var files = 0
                for (file in child) {
                    require(++files <= 64) { "Browser upload file quota exceeded" }
                    val data = attrsAt(child, file.fileName.toString()) ?: continue
                    require(!data.isDirectory) { "Unexpected nested upload directory" }
                    if (data.isRegularFile) {
                        require(data.size() <= MAX_STAGING_BYTES - total) { "Browser upload staging quota exceeded" }
                        total += data.size()
                    }
                }
            }
        }
        return total
    }

    private fun cleanupDirectory(directory: SecureDirectoryStream<Path>, nowMillis: Long) {
        var visited = 0
        for (entry in directory) {
            if (++visited > 256) break
            val name = entry.fileName.toString()
            if (!validId(name) && !name.matches(Regex("\\.init-[a-f0-9]{32}"))) continue
            val attrs = attrsAt(directory, name) ?: continue
            if (nowMillis - attrs.lastModifiedTime().toMillis() >= BrowserUploadPolicy.ARTIFACT_TTL_MILLIS) deleteEntry(directory, name)
        }
    }

    private fun copyApprovedSource(filesRoot: File, source: BrowserUploadSource, targetDirectory: SecureDirectoryStream<Path>, tempName: String) {
        val parts = BrowserUploadPolicy.validateRelativePath(source.relativePath) ?: error("Unsafe upload path")
        require(parts.last() == source.name && safeLeaf(tempName))
        withRoot(filesRoot) { root ->
            withDirectoryChain(root, listOf("user-data") + parts.dropLast(1)) { parent ->
                val before = attrsAt(parent, source.name) ?: error("Upload source disappeared")
                require(before.isRegularFile && identity(before) == source.fileIdentity && sameMetadata(before, source))
                val input = openReadAt(parent, source.name)
                try {
                    val opened = attrsAt(parent, source.name) ?: error("Upload source disappeared")
                    require(opened.isRegularFile && identity(opened) == source.fileIdentity && sameSnapshot(before, opened) && input.size() == source.size)
                    val output = targetDirectory.newByteChannel(Paths.get(tempName), setOf<OpenOption>(
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW,
                    ))
                    var outputReady = false
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        input.position(0)
                        var total = 0L
                        val buffer = ByteBuffer.allocate(16 * 1024)
                        while (true) {
                            buffer.clear()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            require(total <= BrowserUploadPolicy.MAX_FILE_BYTES && total <= source.size)
                            buffer.flip()
                            val digestBytes = ByteArray(count)
                            buffer.get(digestBytes)
                            digest.update(digestBytes)
                            buffer.rewind()
                            while (buffer.hasRemaining()) output.write(buffer)
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        val after = attrsAt(parent, source.name) ?: error("Upload source disappeared")
                        require(total == source.size && hash == source.sha256 && identity(after) == source.fileIdentity &&
                            sameSnapshot(opened, after) && input.size() == source.size && output.size() == source.size)
                        if (output is java.nio.channels.FileChannel) output.force(true)
                        outputReady = true
                    } finally {
                        output.close()
                        if (!outputReady) runCatching { targetDirectory.deleteFile(Paths.get(tempName)) }
                    }
                } finally { input.close() }
            }
        }
    }

    private fun hashChannel(channel: SeekableByteChannel, maxBytes: Long): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        channel.position(0)
        var total = 0L
        val buffer = ByteBuffer.allocate(16 * 1024)
        while (true) {
            buffer.clear()
            val count = channel.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            require(total <= maxBytes)
            buffer.flip()
            digest.update(buffer)
        }
        return total to digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** New public directories are built in the non-guest private root, then published relative to pinned handles. */
    private fun initializePublicDirectory(parent: SecureDirectoryStream<Path>, privateRoot: SecureDirectoryStream<Path>,
        privatePath: Path, name: String) {
        val existing = attrsAt(parent, name)
        if (existing != null) { require(existing.isDirectory && !existing.isSymbolicLink); return }
        val temporary = ".init-${UUID.randomUUID().toString().replace("-", "") }"
        Files.createDirectory(privatePath.resolve(temporary))
        try { privateRoot.move(Paths.get(temporary), parent, Paths.get(name)) }
        finally { runCatching { privateRoot.deleteDirectory(Paths.get(temporary)) } }
    }

    /** The context-owned files root and this private child are not exposed by the sandbox bind. */
    private fun ensureDirectory(parent: Path, name: String) {
        require(safeLeaf(name))
        val path = parent.resolve(name)
        if (!Files.exists(path, NOFOLLOW)) runCatching { Files.createDirectory(path) }
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path, NOFOLLOW))
    }

    private fun canonicalRoot(filesRoot: File): File {
        val absolute = filesRoot.absoluteFile.toPath().normalize().toFile()
        val canonical = filesRoot.canonicalFile
        require(canonical == absolute && Files.isDirectory(canonical.toPath(), NOFOLLOW))
        return canonical
    }

    private fun openRoot(filesRoot: File): SecureDirectoryStream<Path> {
        val stream = Files.newDirectoryStream(canonicalRoot(filesRoot).toPath())
        if (stream !is SecureDirectoryStream<*>) {
            stream.close()
            throw UnsupportedOperationException("SecureDirectoryStream is required for Browser upload files")
        }
        @Suppress("UNCHECKED_CAST")
        return stream as SecureDirectoryStream<Path>
    }

    private inline fun <T> withRoot(filesRoot: File, block: (SecureDirectoryStream<Path>) -> T): T {
        val root = openRoot(filesRoot)
        return try { block(root) } finally { root.close() }
    }

    private inline fun <T> withDirectoryChain(root: SecureDirectoryStream<Path>, segments: List<String>, block: (SecureDirectoryStream<Path>) -> T): T {
        val opened = ArrayList<SecureDirectoryStream<Path>>()
        var current = root
        try {
            segments.forEach { current = openDirectoryAt(current, it).also(opened::add) }
            return block(current)
        } finally { opened.asReversed().forEach { runCatching { it.close() } } }
    }

    private inline fun withDirectoryChainOrNull(root: SecureDirectoryStream<Path>, segments: List<String>, block: (SecureDirectoryStream<Path>) -> Unit) {
        runCatching { withDirectoryChain(root, segments, block) }
    }

    private fun openDirectoryAt(parent: SecureDirectoryStream<Path>, name: String): SecureDirectoryStream<Path> {
        require(safeLeaf(name))
        val stream = parent.newDirectoryStream(Paths.get(name), NOFOLLOW)
        if (stream !is SecureDirectoryStream<*>) {
            stream.close()
            throw UnsupportedOperationException("SecureDirectoryStream is required for Browser upload files")
        }
        @Suppress("UNCHECKED_CAST")
        return stream as SecureDirectoryStream<Path>
    }

    private fun openReadAt(parent: SecureDirectoryStream<Path>, name: String): SeekableByteChannel =
        parent.newByteChannel(Paths.get(name), setOf<OpenOption>(StandardOpenOption.READ, NOFOLLOW))

    private fun attrsAt(directory: SecureDirectoryStream<Path>, name: String): BasicFileAttributes? = runCatching {
        directory.getFileAttributeView(Paths.get(name), BasicFileAttributeView::class.java, NOFOLLOW).readAttributes()
    }.getOrNull()

    private fun deleteEntry(parent: SecureDirectoryStream<Path>, name: String, depth: Int = 0, budget: IntArray = intArrayOf(512)): Boolean {
        require(depth <= 8 && budget[0]-- > 0) { "Browser upload cleanup limit exceeded" }
        if (!safeLeaf(name)) return false
        val attrs = attrsAt(parent, name) ?: return true
        val path = Paths.get(name)
        if (attrs.isDirectory && !attrs.isSymbolicLink) {
            val child = parent.newDirectoryStream(path, NOFOLLOW)
            try {
                val secure = child as? SecureDirectoryStream<Path>
                    ?: throw UnsupportedOperationException("SecureDirectoryStream is required for Browser upload cleanup")
                for (entry in secure) deleteEntry(secure, entry.fileName.toString(), depth + 1, budget)
            } finally { child.close() }
            parent.deleteDirectory(path)
        } else {
            parent.deleteFile(path)
        }
        return true
    }

    private fun identity(attrs: BasicFileAttributes): String = attrs.fileKey()?.toString()
        ?: throw UnsupportedOperationException("Filesystem file identity is required for Browser uploads")

    private fun sameSnapshot(first: BasicFileAttributes, second: BasicFileAttributes): Boolean =
        identity(first) == identity(second) && first.isRegularFile == second.isRegularFile && first.size() == second.size() &&
            first.lastModifiedTime() == second.lastModifiedTime()

    private fun sameMetadataSnapshot(first: BasicFileAttributes, second: BasicFileAttributes): Boolean =
        first.isRegularFile == second.isRegularFile && first.size() == second.size() &&
            first.lastModifiedTime() == second.lastModifiedTime()

    private fun sameMetadata(attrs: BasicFileAttributes, source: BrowserUploadSource): Boolean =
        attrs.size() == source.size && attrs.lastModifiedTime().toMillis() == source.modifiedAtMillis

    private fun validId(value: String): Boolean = value.matches(Regex("[a-f0-9]{32}"))
    private fun safeLeaf(value: String): Boolean = value.isNotEmpty() && value.length <= 255 && value !in setOf(".", "..") &&
        !value.contains('/') && !value.contains('\\') && value.none { it.isISOControl() }
}
