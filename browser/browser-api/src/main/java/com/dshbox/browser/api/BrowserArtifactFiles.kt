package com.dshbox.browser.api

import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import org.json.JSONObject

/** Signals that the bounded screenshot set still contains protected recent artifacts. */
class ScreenshotQuotaExceededException :
    IllegalStateException("Browser screenshot quota exceeded")

/** Owns disposable Browser output files without touching downloads or runtime state. */
object BrowserArtifactFiles {
    data class WrittenArtifact(val file: File, val sizeBytes: Long, internal val fileKey: String? = null)
    data class NetworkArtifact(val name: String, val sizeBytes: Long, val lastModifiedMillis: Long)
    const val TTL_MILLIS = 30L * 60 * 1000
    const val READ_GRACE_MILLIS = 30L * 1000
    const val SCREENSHOT_MAX_BYTES = 32L * 1024 * 1024
    const val SCREENSHOT_MAX_COUNT = 8
    private val names = linkedMapOf("shot" to "shot-", "selection" to "selection-", "network" to "")
    private val noFollow = LinkOption.NOFOLLOW_LINKS

    /** Creates only the Browser-owned network directory below the canonical app-private root. */
    @Synchronized
    fun ensureNetworkDirectory(filesRoot: File): File {
        val anchor = canonicalFilesRoot(filesRoot)
        var current = anchor.toFile()
        for (segment in listOf("browser-tools", "out", "network")) {
            current = File(current, segment)
            require(!Files.isSymbolicLink(current.toPath())) { "Unsafe Browser artifact directory" }
            if (!current.exists()) Files.createDirectory(current.toPath())
            require(Files.isDirectory(current.toPath(), noFollow)) { "Unsafe Browser artifact directory" }
            require(current.canonicalFile.parentFile == current.parentFile.canonicalFile) { "Unsafe Browser artifact directory" }
        }
        return current.canonicalFile
    }

    /** Atomically stores bounded HTTP bodies, HAR or independent WebSocket JSON in the anchored directory. */
    fun writeNetworkArtifactAtomically(filesRoot: File, name: String, bytes: ByteArray, maxBytes: Int): File {
        require(validNetworkName(name) && maxBytes > 0 && bytes.size <= maxBytes)
        ensureNetworkDirectory(filesRoot)
        return withOpenedChain(filesRoot, listOf("browser-tools", "out", "network")) { dir ->
            val staging = ".net-${UUID.randomUUID()}.tmp"
            try {
                dir.newByteChannel(Paths.get(staging), setOf<OpenOption>(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, noFollow)).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    if (channel is FileChannel) channel.force(true)
                }
                val staged = attributes(dir, staging) ?: error("Network artifact staging file disappeared")
                check(staged.isRegularFile && staged.size() == bytes.size.toLong())
                val existing = attributes(dir, name)
                if (existing != null) {
                    require(existing.isRegularFile) { "Unsafe network artifact target" }
                }
                dir.move(Paths.get(staging), dir, Paths.get(name))
                File(canonicalFilesRoot(filesRoot).toFile(), "browser-tools/out/network/$name")
            } finally { runCatching { dir.deleteFile(Paths.get(staging)) } }
        }
    }

    /** Appends only to an existing regular network artifact and enforces its per-file size bound. */
    fun appendNetworkArtifact(filesRoot: File, name: String, bytes: ByteArray, maxBytes: Int): Long {
        require(validNetworkName(name) && maxBytes > 0)
        return withOpenedChain(filesRoot, listOf("browser-tools", "out", "network")) { dir ->
            val before = attributes(dir, name) ?: error("Network artifact is missing")
            require(before.isRegularFile && before.size() + bytes.size <= maxBytes) { "Unsafe network artifact target" }
            dir.newByteChannel(Paths.get(name), setOf<OpenOption>(StandardOpenOption.WRITE,
                StandardOpenOption.APPEND, noFollow)).use { channel ->
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                if (channel is FileChannel) channel.force(true)
                val size = channel.size()
                require(size <= maxBytes && size >= before.size())
                size
            }
        }
    }

    /** Reads one bounded network artifact relative to its verified directory handle. */
    fun readNetworkArtifact(filesRoot: File, name: String, maxBytes: Int): ByteArray? {
        require(validNetworkName(name) && maxBytes > 0)
        return withOpenedChain(filesRoot, listOf("browser-tools", "out", "network"), onMissing = { null }) { dir ->
            val attrs = attributes(dir, name) ?: return@withOpenedChain null
            require(attrs.isRegularFile && attrs.size() <= maxBytes)
            dir.newByteChannel(Paths.get(name), setOf<OpenOption>(StandardOpenOption.READ, noFollow)).use { channel ->
                val expected = attrs.size().toInt()
                val buffer = java.nio.ByteBuffer.allocate(expected)
                while (buffer.hasRemaining()) require(channel.read(buffer) >= 0)
                require(channel.size() == expected.toLong())
                buffer.array()
            }
        }
    }

    fun networkArtifactExists(filesRoot: File, name: String): Boolean {
        require(validNetworkName(name))
        return withOpenedChain(filesRoot, listOf("browser-tools", "out", "network"), onMissing = { false }) { dir ->
            attributes(dir, name)?.isRegularFile == true
        }
    }

    /** Lists and removes only network-owned files through the directory handle. */
    fun listNetworkArtifacts(filesRoot: File): List<NetworkArtifact> {
        ensureNetworkDirectory(filesRoot)
        return withOpenedChain(filesRoot, listOf("browser-tools", "out", "network")) { dir ->
            dir.mapNotNull { entry ->
                val name = entry.fileName?.toString() ?: return@mapNotNull null
                if (name.startsWith(".net-") && name.endsWith(".tmp")) {
                    runCatching { dir.deleteFile(Paths.get(name)) }
                    return@mapNotNull null
                }
                if (!validNetworkName(name)) return@mapNotNull null
                val attrs = attributes(dir, name) ?: return@mapNotNull null
                if (!attrs.isRegularFile) return@mapNotNull null
                NetworkArtifact(name, attrs.size(), attrs.lastModifiedTime().toMillis())
            }
        }
    }

    fun deleteNetworkArtifact(filesRoot: File, name: String) {
        require(validNetworkName(name))
        withOpenedChain(filesRoot, listOf("browser-tools", "out", "network"), onMissing = { Unit }) { dir ->
            val attrs = attributes(dir, name) ?: return@withOpenedChain
            require(attrs.isRegularFile) { "Unsafe network artifact target" }
            dir.deleteFile(Paths.get(name))
        }
    }

    fun clearNetworkArtifacts(filesRoot: File) {
        withOpenedChain(filesRoot, listOf("browser-tools", "out", "network"), onMissing = { Unit }) { dir ->
            for (artifact in dir.mapNotNull { it.fileName?.toString() }.filter(::validNetworkName)) {
                val attrs = attributes(dir, artifact) ?: continue
                require(attrs.isRegularFile) { "Unsafe network artifact target" }
                dir.deleteFile(Paths.get(artifact))
            }
            for (temp in dir.mapNotNull { it.fileName?.toString() }.filter { it.startsWith(".net-") && it.endsWith(".tmp") }) {
                dir.deleteFile(Paths.get(temp))
            }
        }
    }

    fun isTemporaryPath(path: String): Boolean {
        val parts = path.replace('\\', '/').split('/')
        if (parts.any { it == "." || it == ".." }) return false
        val uploadParts = when {
            parts.size == 4 && parts[0] == "browser-tools" && parts[1] == "upload" -> parts.drop(2)
            parts.size == 3 && parts[0] == ".browser-upload-private" -> parts.drop(1)
            else -> null
        }
        if (uploadParts != null) return uploadParts[0].matches(Regex("[a-f0-9]{32}")) &&
            uploadParts[1].matches(Regex("(?:[a-f0-9]{32}_.{1,180}|\\.tmp-[a-f0-9]{32})"))
        return parts.size == 4 && parts[0] == "browser-tools" && parts[1] == "out" &&
            names.containsKey(parts[2]) && parts[3].isNotEmpty() &&
            (names[parts[2]].isNullOrEmpty() || parts[3].startsWith(names.getValue(parts[2])) ||
                parts[3].startsWith("." + names.getValue(parts[2]))) &&
            parts.none { it == "." || it == ".." }
    }

    /** Whole-second mtime plus a one-second safety margin matches Android lstat scan precision. */
    fun eligibleForCleanup(lastModifiedMillis: Long, nowMillis: Long, active: Boolean): Boolean {
        val retention = if (active) TTL_MILLIS else READ_GRACE_MILLIS
        val mtimeSecond = Math.floorDiv(lastModifiedMillis, 1000L) * 1000L
        return mtimeSecond <= nowMillis - retention - 1000L
    }

    fun temporaryFiles(filesRoot: File): List<File> = withArtifactDirs(filesRoot) { dirs ->
        dirs.flatMap { (category, dir) ->
            listRegular(dir, category).map { File(filesRoot, "browser-tools/out/$category/$it") }
        }
    }

    fun removeExpired(filesRoot: File, nowMillis: Long = System.currentTimeMillis()): Long =
        removeOlderThan(filesRoot, nowMillis - TTL_MILLIS - 1000L)

    /** Securely delete Browser temporary artifacts old enough for the given cutoff. */
    fun removeOlderThan(filesRoot: File, cutoffMillis: Long): Long =
        removeEligible(filesRoot, nowMillis = cutoffMillis + TTL_MILLIS + 1000L, active = true, measure = { it.length() })

    /** Used by the sandbox cleaner; accounting runs immediately before anchored relative deletion. */
    fun removeForCleanup(
        filesRoot: File,
        nowMillis: Long,
        allocatedBytes: (File) -> Long,
    ): Long = removeEligible(
        filesRoot, nowMillis, active = false, measure = allocatedBytes,
        protectedSelections = protectedSelectionNames(filesRoot, nowMillis),
    )

    private fun removeEligible(
        filesRoot: File,
        nowMillis: Long,
        active: Boolean,
        measure: (File) -> Long,
        protectedSelections: Set<String> = emptySet(),
    ): Long {
        val removed = withArtifactDirs(filesRoot) { dirs ->
            var total = 0L
            for ((category, dir) in dirs) {
                for (name in listRegular(dir, category)) {
                    val attrs = attributes(dir, name) ?: continue
                    if (category == "selection" && name in protectedSelections) continue
                    if (!eligibleForCleanup(attrs.lastModifiedTime().toMillis(), nowMillis, active)) continue
                    val bytes = runCatching { measure(File(filesRoot, "browser-tools/out/$category/$name")) }.getOrDefault(0L)
                    dir.deleteFile(Paths.get(name))
                    total += bytes
                }
            }
            total
        }
        return removed + removeUploadTemporaries(filesRoot, nowMillis, active, measure)
    }

    /** Flat upload grants only; unexpected nested directories are never followed or recursively removed. */
    private fun removeUploadTemporaries(filesRoot: File, nowMillis: Long, active: Boolean, measure: (File) -> Long): Long {
        var removed = 0L
        for (segments in listOf(listOf("browser-tools", "upload"), listOf(".browser-upload-private"))) {
            withOpenedChain(filesRoot, segments, onMissing = { Unit }) { parent ->
                var visited = 0
                for (entry in parent) {
                    if (++visited > 256) break
                    val grant = entry.fileName.toString()
                    if (!grant.matches(Regex("[a-f0-9]{32}"))) continue
                    val attrs = attributes(parent, grant) ?: continue
                    if (!attrs.isDirectory || attrs.isSymbolicLink) continue
                    val retention = if (active) 10L * 60 * 1000 else READ_GRACE_MILLIS
                    if (attrs.lastModifiedTime().toMillis() > nowMillis - retention - 1000L) continue
                    secure(parent.newDirectoryStream(Paths.get(grant), noFollow)).use { child ->
                        var filesVisited = 0
                        for (file in child) {
                            if (++filesVisited > 256) break
                            val name = file.fileName.toString()
                            val relative = (segments + grant + name).joinToString("/")
                            if (!isTemporaryPath(relative)) continue
                            val fileAttrs = attributes(child, name) ?: continue
                            if (!fileAttrs.isRegularFile) continue
                            val bytes = runCatching { measure(File(filesRoot, relative)) }.getOrDefault(0L)
                            child.deleteFile(Paths.get(name))
                            removed += bytes
                        }
                    }
                    runCatching { parent.deleteDirectory(Paths.get(grant)) }
                }
            }
        }
        return removed
    }

    /** Reads only the Browser outbox index; a corrupt index protects recent images for their TTL. */
    private fun protectedSelectionNames(filesRoot: File, nowMillis: Long): Set<String> {
        val directory = File(filesRoot, "browser-tools/out/selection")
        val names = listRegularSelectionNames(filesRoot)
        return runCatching {
            val raw = readSelectionOutbox(filesRoot, 256 * 1024) ?: return emptySet()
            val root = JSONObject(String(raw, Charsets.UTF_8))
            require(root.optInt("version") in setOf(1, 2))
            val rollback = root.optLong("lastWallClockMillis") > nowMillis
            val items = root.optJSONArray("items") ?: error("Missing outbox items")
            buildSet {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: error("Invalid outbox item")
                    val name = File(item.optString("artifactPath")).name
                    val state = item.optString("deliveryState", "UNBOUND")
                    val expiry = item.optLong("expiresAtWallMillis", 0)
                    require(name.matches(Regex("selection-[A-Za-z0-9._-]{1,120}\\.png")) && name in names && expiry > 0)
                    require(state in setOf("UNBOUND", "CLAIMED", "ADMITTING", "UNKNOWN", "TERMINAL"))
                    if (state in setOf("UNBOUND", "CLAIMED", "ADMITTING", "UNKNOWN") &&
                        (rollback || expiry > nowMillis)) add(name)
                }
            }
        }.getOrElse { recentSelectionNames(directory, names, nowMillis) }
    }

    private fun listRegularSelectionNames(filesRoot: File): Set<String> = withArtifactDirs(filesRoot) { dirs ->
        dirs.firstOrNull { it.first == "selection" }?.second?.let { listRegular(it, "selection").toSet() }.orEmpty()
    }

    private fun recentSelectionNames(directory: File, names: Set<String>, nowMillis: Long): Set<String> =
        names.filterTo(mutableSetOf()) { name ->
            val modified = File(directory, name).lastModified()
            modified >= nowMillis - TTL_MILLIS
        }

    /**
     * Writes a screenshot through an anchored SecureDirectoryStream and atomically renames it.
     * No path-based write/delete fallback is used when the filesystem lacks secure streams.
     */
    fun writeScreenshotAtomically(
        filesRoot: File,
        requestedName: String,
        nowMillis: Long = System.currentTimeMillis(),
        maxBytes: Long = 8L * 1024 * 1024,
        writer: (OutputStream) -> Unit,
    ): WrittenArtifact = writeImageAtomically(filesRoot, "shot", requestedName, nowMillis, maxBytes, writer)

    /** Deletes only the exact regular screenshot file returned by this write, through its anchored directory. */
    fun removeScreenshot(filesRoot: File, artifact: WrittenArtifact): Boolean {
        val name = artifact.file.name
        require(name.matches(Regex("shot-[A-Za-z0-9._-]{1,120}\\.png")))
        val expected = File(filesRoot, "browser-tools/out/shot/$name").absoluteFile.toPath().normalize()
        require(artifact.file.absoluteFile.toPath().normalize() == expected) { "Screenshot is outside Browser output" }
        val key = artifact.fileKey ?: return false
        return withOpenedChain(filesRoot, listOf("browser-tools", "out", "shot"), onMissing = { false }) { dir ->
            val attrs = attributes(dir, name) ?: return@withOpenedChain false
            if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() != artifact.sizeBytes || attrs.fileKey()?.toString() != key) {
                return@withOpenedChain false
            }
            dir.deleteFile(Paths.get(name))
            true
        }
    }

    fun writeSelectionAtomically(
        filesRoot: File,
        requestedName: String,
        nowMillis: Long = System.currentTimeMillis(),
        maxBytes: Long = 8L * 1024 * 1024,
        protectedNames: Set<String> = emptySet(),
        writer: (OutputStream) -> Unit,
    ): WrittenArtifact {
        require(maxBytes in 1..8L * 1024 * 1024) { "Invalid selection image byte limit" }
        require(protectedNames.size <= SCREENSHOT_MAX_COUNT && protectedNames.all {
            it.matches(Regex("selection-[A-Za-z0-9._-]{1,120}\\.png"))
        })
        return writeImageAtomically(filesRoot, "selection", requestedName, nowMillis, maxBytes, writer, protectedNames)
    }

    private fun writeImageAtomically(
        filesRoot: File,
        category: String,
        requestedName: String,
        nowMillis: Long,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
        protectedNames: Set<String> = emptySet(),
    ): WrittenArtifact = withOpenedChain(filesRoot, listOf("browser-tools", "out", category)) { dir ->
        require(category == "shot" || category == "selection")
        require(requestedName.matches(Regex("$category-[A-Za-z0-9._-]{1,120}\\.png")))
        require(maxBytes > 0)
        val name = requestedName
        val staging = ".${name}.${UUID.randomUUID()}.tmp"
        var finalCreated = false
        try {
            val options = setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, noFollow)
            dir.newByteChannel(Paths.get(staging), options).use { channel ->
                val destination = Channels.newOutputStream(channel)
                val output = object : OutputStream() {
                    private var bytesWritten = 0L
                    override fun write(value: Int) {
                        check(bytesWritten < maxBytes) { "Screenshot exceeds storage limit" }
                        destination.write(value)
                        bytesWritten++
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                        check(length.toLong() <= maxBytes - bytesWritten) { "Screenshot exceeds storage limit" }
                        destination.write(bytes, offset, length)
                        bytesWritten += length
                    }
                    override fun flush() = destination.flush()
                    override fun close() = destination.close()
                }
                try {
                    writer(output)
                    output.flush()
                    if (channel is FileChannel) channel.force(true)
                } finally { output.close() }
            }
            val staged = attributes(dir, staging) ?: error("Screenshot staging file disappeared")
            check(staged.isRegularFile && staged.size() in 1..maxBytes) { "Screenshot exceeds storage limit" }
            dir.move(Paths.get(staging), dir, Paths.get(name))
            finalCreated = true
            trimScreenshotsInDirectory(dir, name, nowMillis, category = category, protectedNames = protectedNames)
            val finalAttrs = attributes(dir, name) ?: error("Screenshot disappeared after commit")
            WrittenArtifact(File(filesRoot, "browser-tools/out/$category/$name"), finalAttrs.size(), finalAttrs.fileKey()?.toString())
        } catch (error: Exception) {
            runCatching { dir.deleteFile(Paths.get(staging)) }
            if (finalCreated) runCatching { dir.deleteFile(Paths.get(name)) }
            throw error
        }
    }

    fun selectionExists(filesRoot: File, name: String): Boolean {
        require(name.matches(Regex("selection-[A-Za-z0-9._-]{1,120}\\.png")))
        return withOpenedChain(filesRoot, listOf("browser-tools", "out", "selection"), onMissing = { false }) {
            attributes(it, name)?.let { attrs -> attrs.isRegularFile && attrs.size() in 1..8L * 1024 * 1024 } == true
        }
    }

    /** Explicit discard or expiry removes only the named selection through its directory handle. */
    fun removeSelection(filesRoot: File, name: String) {
        require(name.matches(Regex("selection-[A-Za-z0-9._-]{1,120}\\.png")))
        withOpenedChain(filesRoot, listOf("browser-tools", "out", "selection"), onMissing = { Unit }) {
            try { it.deleteFile(Paths.get(name)) } catch (_: NoSuchFileException) { }
        }
    }

    /** Explicit Browser data clearing removes indexed and orphaned selection images only. */
    fun clearSelections(filesRoot: File) = withOpenedChain(
        filesRoot, listOf("browser-tools", "out", "selection"), onMissing = { Unit },
    ) { dir ->
        for (name in listRegular(dir, "selection")) dir.deleteFile(Paths.get(name))
        try { dir.deleteFile(Paths.get("outbox.json")) } catch (_: NoSuchFileException) { }
    }

    /** Reads the bounded outbox index relative to its verified selection directory handle. */
    fun readSelectionOutbox(filesRoot: File, maxBytes: Int): ByteArray? {
        require(maxBytes in 1..256 * 1024)
        return withOpenedChain(filesRoot, listOf("browser-tools", "out", "selection"), onMissing = { null }) { dir ->
            val attrs = attributes(dir, "outbox.json") ?: return@withOpenedChain null
            require(attrs.isRegularFile && attrs.size() in 1..maxBytes.toLong())
            dir.newByteChannel(Paths.get("outbox.json"), setOf<OpenOption>(StandardOpenOption.READ, noFollow)).use { channel ->
                val expected = attrs.size().toInt()
                val buffer = java.nio.ByteBuffer.allocate(expected)
                while (buffer.hasRemaining()) require(channel.read(buffer) >= 0)
                require(channel.size() == expected.toLong())
                buffer.array()
            }
        }
    }

    /** Commits the outbox index with a no-follow temporary file and directory-relative rename. */
    fun writeSelectionOutboxAtomically(filesRoot: File, bytes: ByteArray, maxBytes: Int) {
        require(maxBytes in 1..256 * 1024 && bytes.size in 1..maxBytes)
        withOpenedChain(filesRoot, listOf("browser-tools", "out", "selection")) { dir ->
            val staging = ".outbox-${UUID.randomUUID()}.tmp"
            try {
                dir.newByteChannel(Paths.get(staging), setOf<OpenOption>(StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, noFollow)).use { channel ->
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    if (channel is FileChannel) channel.force(true)
                }
                val attrs = attributes(dir, staging) ?: error("Outbox staging file disappeared")
                check(attrs.isRegularFile && attrs.size() == bytes.size.toLong())
                dir.move(Paths.get(staging), dir, Paths.get("outbox.json"))
            } finally { runCatching { dir.deleteFile(Paths.get(staging)) } }
        }
    }

    /** Preserve recent delivered screenshots; only files past the 30-second read grace are candidates. */
    fun trimScreenshots(
        filesRoot: File,
        keep: File,
        maxBytes: Long = SCREENSHOT_MAX_BYTES,
        maxCount: Int = SCREENSHOT_MAX_COUNT,
        nowMillis: Long = System.currentTimeMillis(),
    ) = withShotDir(filesRoot) { dir ->
        require(maxBytes > 0 && maxCount > 0)
        val keepName = keep.name
        trimScreenshotsInDirectory(dir, keepName, nowMillis, maxBytes, maxCount)
    }

    private fun trimScreenshotsInDirectory(
        dir: SecureDirectoryStream<Path>,
        keepName: String,
        nowMillis: Long,
        maxBytes: Long = SCREENSHOT_MAX_BYTES,
        maxCount: Int = SCREENSHOT_MAX_COUNT,
        category: String = "shot",
        protectedNames: Set<String> = emptySet(),
    ) {
        val files = listRegular(dir, category).mapNotNull { name ->
            attributes(dir, name)?.let { name to it }
        }
        val keep = files.firstOrNull { it.first == keepName } ?: error("Current screenshot missing")
        check(keep.second.size() in 1..maxBytes) { "Screenshot exceeds storage limit" }
        var bytes = files.sumOf { it.second.size() }
        var count = files.size
        val candidates = files.filter { it.first != keepName && it.first !in protectedNames &&
            eligibleForCleanup(it.second.lastModifiedTime().toMillis(), nowMillis, active = false) }
            .sortedBy { it.second.lastModifiedTime().toMillis() }
        val toDelete = mutableListOf<Pair<String, BasicFileAttributes>>()
        for (candidate in candidates) {
            if (bytes <= maxBytes && count <= maxCount) break
            toDelete += candidate
            bytes -= candidate.second.size()
            count--
        }
        if (bytes > maxBytes || count > maxCount) throw ScreenshotQuotaExceededException()
        for ((name, _) in toDelete) dir.deleteFile(Paths.get(name))
    }

    private fun listRegular(dir: SecureDirectoryStream<Path>, category: String): List<String> {
        val prefix = names.getValue(category)
        return dir.mapNotNull { entry ->
            val name = entry.fileName?.toString() ?: return@mapNotNull null
            if (name == "." || name == ".." ||
                !(prefix.isEmpty() || name.startsWith(prefix) || name.startsWith(".$prefix"))) return@mapNotNull null
            val attrs = attributes(dir, name) ?: return@mapNotNull null
            if (attrs.isRegularFile) name else null
        }
    }

    private fun attributes(dir: SecureDirectoryStream<Path>, name: String): BasicFileAttributes? = runCatching {
        dir.getFileAttributeView(Paths.get(name), BasicFileAttributeView::class.java, noFollow)
            .readAttributes()
    }.getOrNull()

    private fun canonicalFilesRoot(filesRoot: File): Path {
        val root = filesRoot.canonicalFile.toPath()
        require(Files.isDirectory(root, noFollow)) { "Invalid app-private Browser artifact root" }
        return root
    }

    private fun validNetworkName(name: String): Boolean =
        name.matches(Regex("(?:cap|net)-[A-Za-z0-9_-]{1,64}(?:\\.body|\\.har|\\.ws\\.json)"))

    private fun <T> withArtifactDirs(filesRoot: File, block: (List<Pair<String, SecureDirectoryStream<Path>>>) -> T): T =
        withOpenedChain(
            filesRoot,
            listOf("browser-tools", "out"),
            onMissing = { block(emptyList()) },
        ) { out ->
            val opened = mutableListOf<DirectoryStream<Path>>()
            try {
                val dirs = names.keys.mapNotNull { name ->
                    val raw = try { out.newDirectoryStream(Paths.get(name), noFollow) }
                    catch (_: NoSuchFileException) { return@mapNotNull null }
                    val stream = secure(raw)
                    opened += stream
                    name to stream
                }
                block(dirs)
            } finally { opened.asReversed().forEach { runCatching { it.close() } } }
        }

    private fun <T> withShotDir(filesRoot: File, block: (SecureDirectoryStream<Path>) -> T): T =
        withOpenedChain(filesRoot, listOf("browser-tools", "out", "shot"),
            onMissing = { error("Browser screenshot output directory is not provisioned") }, block = block)

    private fun <T> withOpenedChain(
        filesRoot: File,
        segments: List<String>,
        onMissing: (() -> T)? = null,
        block: (SecureDirectoryStream<Path>) -> T,
    ): T {
        val rootPath = canonicalFilesRoot(filesRoot)
        val rootStream = secure(Files.newDirectoryStream(rootPath))
        val opened = mutableListOf<DirectoryStream<Path>>(rootStream)
        try {
            var current = rootStream
            for (segment in segments) {
                require(segment != "." && segment != ".." && '/' !in segment && '\\' !in segment)
                val child = try { current.newDirectoryStream(Paths.get(segment), noFollow) }
                catch (missing: NoSuchFileException) {
                    if (onMissing != null) return onMissing()
                    throw missing
                }
                current = secure(child)
                opened += current
            }
            return block(current)
        } finally { opened.asReversed().forEach { runCatching { it.close() } } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun secure(stream: DirectoryStream<Path>): SecureDirectoryStream<Path> {
        if (stream !is SecureDirectoryStream<*>) {
            stream.close()
            throw UnsupportedOperationException("SecureDirectoryStream is required for Browser artifacts")
        }
        return stream as SecureDirectoryStream<Path>
    }
}
