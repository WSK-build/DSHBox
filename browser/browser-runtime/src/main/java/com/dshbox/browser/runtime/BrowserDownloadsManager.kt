package com.dshbox.browser.runtime

import android.content.Context
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.net.URI
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class BrowserDownloadState {
    QUEUED,
    DOWNLOADING,
    CANCELLING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class BrowserDownloadRequest(
    val url: String,
    val suggestedFileName: String? = null,
    val cookieHeader: String? = null,
    val authorizationHeader: String? = null,
    val userAgent: String? = null,
    val maxBytes: Long = BrowserDownloadPolicy.MAX_FILE_BYTES,
)

data class BrowserDownloadItem(
    val id: String,
    val state: BrowserDownloadState,
    val fileName: String?,
    val bytesReceived: Long,
    val totalBytes: Long?,
    val mimeType: String?,
    val artifactPath: String?,
    val errorCode: String?,
    val updatedAtMillis: Long,
)

data class BrowserDownloadPage(
    val items: List<BrowserDownloadItem>,
    val nextCursor: String?,
    val truncated: Boolean,
)

/**
 * Owns HTTP(S) downloads in the Browser process. A single process-wide instance bounds workers,
 * stages files relative to secure directory handles, and never removes completed user files.
 */
class BrowserDownloadsManager internal constructor(
    filesDirectory: Path,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val openConnection: (URI) -> HttpURLConnection = { uri ->
        URL(uri.toASCIIString()).openConnection() as HttpURLConnection
    },
) : AutoCloseable {
    private constructor(context: Context) : this(
        context.applicationContext.filesDir.toPath().toAbsolutePath().normalize(),
    )

    private val appFilesDirectory = filesDirectory.toAbsolutePath().normalize()
    private val expectedDirectory = appFilesDirectory
        .resolve("browser-tools").resolve("out").resolve("downloads").toAbsolutePath().normalize()
    private val stateLock = Any()
    private val jobs = LinkedHashMap<String, Job>()
    private val recent = LinkedHashMap<String, BrowserDownloadItem>()
    private val listeners = CopyOnWriteArraySet<(BrowserDownloadItem) -> Unit>()
    private var reservedEntries = 0
    private var reservedBytes = 0L
    private var closed = false

    private val executor = ThreadPoolExecutor(
        BrowserDownloadPolicy.MAX_ACTIVE,
        BrowserDownloadPolicy.MAX_ACTIVE,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(BrowserDownloadPolicy.MAX_QUEUED),
        ThreadFactory { task -> Thread(task, "browser-download").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private data class Job(
        val id: String,
        val request: BrowserDownloadRequest,
        val uri: java.net.URI,
        val safeName: String,
        val markerName: String,
        val partName: String,
        val markerToken: String,
        val deadlineElapsedMillis: Long,
        val cancelRequested: AtomicBoolean = AtomicBoolean(false),
        @Volatile var connection: HttpURLConnection? = null,
        @Volatile var future: Future<*>? = null,
        @Volatile var started: Boolean = false,
        @Volatile var finished: Boolean = false,
        @Volatile var reservationBytes: Long = 0L,
        @Volatile var reservationHeld: Boolean = false,
        @Volatile var item: BrowserDownloadItem,
    )

    init {
        val actual = expectedDirectory.toFile().absoluteFile.toPath().normalize()
        check(actual == expectedDirectory) { "download directory is not canonical" }
        secureDownloadDirectory().use { cleanupStalePartials(it) }
    }

    /** Queues one bounded transfer and immediately returns its initial state. */
    fun start(request: BrowserDownloadRequest): BrowserDownloadItem {
        val id = UUID.randomUUID().toString().replace("-", "")
        val uri = BrowserDownloadPolicy.parseHttpUrl(request.url)
        val invalid = when {
            uri == null -> "INVALID_URL"
            request.maxBytes !in 1..BrowserDownloadPolicy.MAX_FILE_BYTES -> "INVALID_SIZE_LIMIT"
            !BrowserDownloadPolicy.validHeader(request.cookieHeader, BrowserDownloadPolicy.MAX_COOKIE_LENGTH) -> "INVALID_HEADERS"
            !BrowserDownloadPolicy.validHeader(request.authorizationHeader, BrowserDownloadPolicy.MAX_AUTHORIZATION_LENGTH) -> "INVALID_HEADERS"
            !BrowserDownloadPolicy.validHeader(request.userAgent, BrowserDownloadPolicy.MAX_USER_AGENT_LENGTH) -> "INVALID_HEADERS"
            else -> null
        }
        if (invalid != null) return rememberFailure(id, invalid)

        val name = BrowserDownloadPolicy.safeFileName(request.suggestedFileName, uri!!)
        val now = wallClock()
        val initial = BrowserDownloadItem(id, BrowserDownloadState.QUEUED, name, 0L, null, null, null, null, now)
        val job = Job(
            id = id,
            request = request,
            uri = uri,
            safeName = name,
            markerName = ".dshbox-download-$id.owner",
            partName = ".dshbox-download-$id.part",
            markerToken = UUID.randomUUID().toString().replace("-", ""),
            deadlineElapsedMillis = elapsedClock() + BrowserDownloadPolicy.MAX_TRANSFER_DURATION_MS,
            item = initial,
        )
        synchronized(stateLock) {
            if (closed) return rememberFailureLocked(id, "MANAGER_CLOSED")
            if (jobs.size >= BrowserDownloadPolicy.MAX_ACTIVE + BrowserDownloadPolicy.MAX_QUEUED) {
                return rememberFailureLocked(id, "BUSY")
            }
            jobs[id] = job
        }
        // Publish QUEUED before submit so a fast worker cannot publish DOWNLOADING first.
        notifyListeners(initial)
        var terminal: BrowserDownloadItem? = null
        synchronized(stateLock) {
            if (job.finished) return job.item
            if (closed || job.cancelRequested.get()) {
                terminal = job.item
            } else {
                try {
                    job.future = executor.submit { runJob(job) }
                } catch (_: RuntimeException) {
                    jobs.remove(id)
                    job.finished = true
                    job.item = initial.copy(
                        state = BrowserDownloadState.FAILED,
                        errorCode = "BUSY",
                        updatedAtMillis = wallClock(),
                    )
                    rememberLocked(job.item)
                    terminal = job.item
                }
            }
        }
        terminal?.let {
            notifyListeners(it)
            return it
        }
        return initial
    }

    /** Returns a bounded page of completed files and current in-process transfers. */
    fun list(limit: Int = 50, beforeId: String? = null): BrowserDownloadPage {
        require(limit in 1..BrowserDownloadPolicy.MAX_LIST_PAGE) { "limit must be 1..${BrowserDownloadPolicy.MAX_LIST_PAGE}" }
        if (beforeId != null && !ID_PATTERN.matches(beforeId)) throw IllegalArgumentException("invalid download cursor")
        val persistedListing = secureDownloadDirectory().use { listCompleted(it) }
        val persisted = persistedListing.items
        val inMemory = synchronized(stateLock) { (jobs.values.map { it.item } + recent.values).toList() }
        val persistedById = persisted.associateBy { it.id }
        val current = inMemory.mapNotNull { item ->
            if (item.state != BrowserDownloadState.COMPLETED) item
            else persistedById[item.id]?.let { preferCompletedMetadata(item, it) }
        }
        val combined = (persisted + current).associateBy { it.id }.values
            .sortedWith(compareByDescending<BrowserDownloadItem> { it.updatedAtMillis }.thenBy { it.id })
        val startIndex = if (beforeId == null) 0 else combined.indexOfFirst { it.id == beforeId }.let { if (it < 0) 0 else it + 1 }
        val page = combined.drop(startIndex).take(limit + 1)
        val hasMore = page.size > limit
        val items = page.take(limit)
        return BrowserDownloadPage(items, if (hasMore) items.lastOrNull()?.id else null, persistedListing.truncated)
    }

    /** Returns a transfer snapshot only while its completed artifact still exists. */
    fun find(id: String): BrowserDownloadItem? {
        require(ID_PATTERN.matches(id)) { "invalid download id" }
        val cached = synchronized(stateLock) { jobs[id]?.item ?: recent[id] }
        if (cached != null && cached.state != BrowserDownloadState.COMPLETED) return cached
        val listing = secureDownloadDirectory().use { listCompleted(it) }
        val persisted = listing.items.firstOrNull { item -> item.id == id }
        if (persisted == null) {
            if (listing.truncated) throw IOException("Download directory scan limit exceeded")
            return null
        }
        return if (cached == null) persisted else preferCompletedMetadata(cached, persisted)
    }

    private fun preferCompletedMetadata(cached: BrowserDownloadItem, persisted: BrowserDownloadItem): BrowserDownloadItem =
        if (cached.artifactPath == persisted.artifactPath && cached.fileName == persisted.fileName &&
            cached.bytesReceived == persisted.bytesReceived) cached else persisted

    /** Reads anchored metadata for one completed original; never returns a re-openable path. */
    fun completedMetadata(id: String): BrowserDownloadMetadata? =
        BrowserDownloadArtifacts.completedMetadata(appFilesDirectory, id)

    /** Opens one completed original as a NOFOLLOW channel pinned to its verified inode. */
    fun openCompletedChannel(id: String): SeekableByteChannel =
        BrowserDownloadArtifacts.openCompletedChannel(appFilesDirectory, id)

    /**
     * Deletes only the Browser-owned original of one completed item. Active or terminal in-process
     * transfers and foreign names are refused, and user exports outside this directory are untouched.
     */
    fun deleteCompleted(id: String): BrowserDownloadDeleteResult {
        require(ID_PATTERN.matches(id)) { "invalid download id" }
        synchronized(stateLock) {
            val active = jobs[id]
            if (active != null && !active.finished) return BrowserDownloadDeleteResult.NOT_COMPLETED
            val known = recent[id]
            if (known != null && known.state != BrowserDownloadState.COMPLETED) {
                return BrowserDownloadDeleteResult.NOT_COMPLETED
            }
        }
        val result = BrowserDownloadArtifacts.deleteCompleted(appFilesDirectory, id)
        if (result == BrowserDownloadDeleteResult.DELETED) {
            synchronized(stateLock) { recent.remove(id) }
        }
        return result
    }

    /** Cancels a queued or active transfer. Completed and terminal requests are unchanged. */
    fun cancel(id: String): Boolean {
        val job: Job
        var queued = false
        synchronized(stateLock) {
            job = jobs[id] ?: return false
            if (job.finished || !job.cancelRequested.compareAndSet(false, true)) return false
            queued = !job.started
            if (queued) {
                job.future?.cancel(true)
                (job.future as? Runnable)?.let(executor::remove)
                job.finished = true
                job.item = job.item.copy(state = BrowserDownloadState.CANCELLED, errorCode = null, updatedAtMillis = wallClock())
                jobs.remove(id)
                rememberLocked(job.item)
            } else {
                job.item = job.item.copy(state = BrowserDownloadState.CANCELLING, updatedAtMillis = wallClock())
            }
        }
        job.connection?.disconnect()
        job.future?.cancel(true)
        notifyListeners(job.item)
        return true
    }

    /** Listeners receive immutable snapshots on the worker/caller thread that caused the update. */
    fun addListener(listener: (BrowserDownloadItem) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    override fun close() {
        val active: List<Job>
        synchronized(stateLock) {
            if (closed) return
            closed = true
            active = jobs.values.toList()
        }
        active.forEach { cancel(it.id) }
        executor.shutdownNow()
        synchronized(BrowserDownloadsManager::class.java) {
            if (instance === this) instance = null
        }
    }

    private fun runJob(job: Job) {
        synchronized(stateLock) {
            if (job.cancelRequested.get() || job.finished) return
            job.started = true
            updateLocked(job, state = BrowserDownloadState.DOWNLOADING)
        }
        notifyListeners(job.item)
        var directory: SecureDirectoryStream<Path>? = null
        var stageCreated = false
        var completed = false
        try {
            directory = secureDownloadDirectory()
            synchronized(stateLock) {
                checkNotCancelled(job)
                val usage = inspectStorage(directory)
                if (usage.truncated || !BrowserDownloadPolicy.canReserve(
                        usage.entries, usage.bytes, reservedEntries, reservedBytes, job.request.maxBytes,
                    )
                ) throw DownloadFailure("STORAGE_LIMIT")
                reservedEntries += 1
                reservedBytes += job.request.maxBytes
                job.reservationBytes = job.request.maxBytes
                job.reservationHeld = true
            }
            createOwnedStage(directory, job)
            stageCreated = true
            val response = openDownload(job)
            val declaredLength = response.getHeaderFieldLong("Content-Length", -1L)
            if (declaredLength > job.request.maxBytes) throw DownloadFailure("FILE_TOO_LARGE")
            val total = declaredLength.takeIf { it >= 0L }
            var bytesReceived = 0L
            var lastUpdate = System.nanoTime()
            openStageOutput(directory, job.partName).use { output ->
                response.inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        checkNotCancelled(job)
                        val count = input.read(buffer)
                        checkNotCancelled(job)
                        if (count < 0) break
                        if (count == 0) continue
                        if (bytesReceived > job.request.maxBytes - count) throw DownloadFailure("FILE_TOO_LARGE")
                        output.write(buffer, 0, count)
                        bytesReceived += count
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 250_000_000L) {
                            publishProgress(job, bytesReceived, total)
                            lastUpdate = now
                        }
                    }
                }
            }
            response.disconnect()
            job.connection = null
            checkNotCancelled(job)
            val mimeType = response.contentType?.substringBefore(';')?.takeIf { it.length <= 128 } ?: "application/octet-stream"
            val finalName = "${job.id}_${job.safeName}"
            synchronized(stateLock) {
                checkNotCancelled(job)
                if (secureAttributes(directory, finalName) != null) throw DownloadFailure("NAME_COLLISION")
                directory.move(Paths.get(job.partName), directory, Paths.get(finalName))
                deleteMarkerIfOwned(directory, job)
                stageCreated = false
                releaseReservationLocked(job)
                job.finished = true
                job.item = job.item.copy(
                    state = BrowserDownloadState.COMPLETED,
                    fileName = job.safeName,
                    bytesReceived = bytesReceived,
                    totalBytes = total,
                    mimeType = mimeType,
                    artifactPath = "/opt/browser/out/downloads/$finalName",
                    errorCode = null,
                    updatedAtMillis = wallClock(),
                )
                jobs.remove(job.id)
                rememberLocked(job.item)
                completed = true
            }
            notifyListeners(job.item)
        } catch (error: Exception) {
            val cancelled = job.cancelRequested.get() || error is InterruptedException
            val code = if (cancelled) null else (error as? DownloadFailure)?.code ?: "DOWNLOAD_FAILED"
            if (cancelled) Thread.interrupted()
            if (stageCreated && directory != null) removeOwnedStage(directory, job)
            synchronized(stateLock) {
                releaseReservationLocked(job)
                if (!job.finished) {
                    job.finished = true
                    job.item = job.item.copy(
                        state = if (cancelled) BrowserDownloadState.CANCELLED else BrowserDownloadState.FAILED,
                        errorCode = code,
                        updatedAtMillis = wallClock(),
                    )
                    jobs.remove(job.id)
                    rememberLocked(job.item)
                }
            }
            notifyListeners(job.item)
        } finally {
            job.connection?.disconnect()
            job.connection = null
            if (stageCreated && !completed && directory != null) removeOwnedStage(directory, job)
            synchronized(stateLock) { releaseReservationLocked(job) }
            runCatching { directory?.close() }
        }
    }

    private fun openDownload(job: Job): HttpURLConnection {
        var current = job.uri
        var credentials = BrowserDownloadPolicy.Credentials(job.request.cookieHeader, job.request.authorizationHeader)
        var crossedOrigin = false
        for (redirects in 0..BrowserDownloadPolicy.MAX_REDIRECTS) {
            checkNotCancelled(job)
            val connection = openConnection(current)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = BrowserDownloadPolicy.CONNECT_TIMEOUT_MS
            connection.readTimeout = BrowserDownloadPolicy.READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept-Encoding", "identity")
            job.request.userAgent?.let { connection.setRequestProperty("User-Agent", it) }
            if (!crossedOrigin) {
                credentials.cookie?.let { connection.setRequestProperty("Cookie", it) }
                credentials.authorization?.let { connection.setRequestProperty("Authorization", it) }
            }
            job.connection = connection
            val status = try { connection.responseCode } catch (error: Throwable) {
                connection.disconnect()
                job.connection = null
                throw error
            }
            if (status in REDIRECT_CODES) {
                if (redirects == BrowserDownloadPolicy.MAX_REDIRECTS) {
                    connection.disconnect()
                    job.connection = null
                    throw DownloadFailure("REDIRECT_LIMIT")
                }
                val location = connection.getHeaderField("Location")
                    ?: run { connection.disconnect(); job.connection = null; throw DownloadFailure("REDIRECT_INVALID") }
                val next = BrowserDownloadPolicy.parseHttpUrl(current.resolve(location).toString())
                    ?: run { connection.disconnect(); job.connection = null; throw DownloadFailure("REDIRECT_INVALID") }
                val nextCredentials = BrowserDownloadPolicy.credentialsForRedirect(current, next, credentials)
                if (nextCredentials == null) {
                    connection.disconnect()
                    job.connection = null
                    throw DownloadFailure("HTTPS_DOWNGRADE")
                }
                if (!BrowserDownloadPolicy.sameOrigin(current, next)) {
                    crossedOrigin = true
                    credentials = BrowserDownloadPolicy.Credentials(null, null)
                } else credentials = nextCredentials
                connection.disconnect()
                job.connection = null
                current = next
                continue
            }
            if (status !in 200..299) {
                connection.disconnect()
                job.connection = null
                throw DownloadFailure("HTTP_$status")
            }
            return connection
        }
        throw DownloadFailure("REDIRECT_LIMIT")
    }

    private data class StorageUsage(val entries: Int, val bytes: Long, val truncated: Boolean)

    private fun inspectStorage(directory: SecureDirectoryStream<Path>): StorageUsage {
        var entries = 0
        var bytes = 0L
        var visited = 0
        var truncated = false
        for (entry in directory) {
            if (++visited > BrowserDownloadPolicy.MAX_DIRECTORY_SCAN) { truncated = true; break }
            val name = entry.fileName.toString()
            val attributes = secureAttributes(directory, name) ?: continue
            if (!attributes.isRegularFile) continue
            if (isOwnedStageName(name)) {
                if (name.endsWith(".part")) bytes = safeAdd(bytes, attributes.size())
                continue
            }
            if (name.startsWith('.')) continue
            entries += 1
            bytes = safeAdd(bytes, attributes.size())
            if (entries > BrowserDownloadPolicy.MAX_PERSISTENT_ENTRIES || bytes > BrowserDownloadPolicy.MAX_TOTAL_BYTES) {
                truncated = true
                break
            }
        }
        return StorageUsage(entries, bytes, truncated)
    }

    private data class PersistedListing(val items: List<BrowserDownloadItem>, val truncated: Boolean)

    private fun listCompleted(directory: SecureDirectoryStream<Path>): PersistedListing {
        val items = ArrayList<BrowserDownloadItem>()
        var visited = 0
        var truncated = false
        for (entry in directory) {
            if (++visited > BrowserDownloadPolicy.MAX_DIRECTORY_SCAN) { truncated = true; break }
            val name = entry.fileName.toString()
            val id = BrowserDownloadArtifacts.ownedIdFor(name) ?: continue
            val attributes = secureAttributes(directory, name) ?: continue
            if (!attributes.isRegularFile) continue
            items += BrowserDownloadItem(
                id = id,
                state = BrowserDownloadState.COMPLETED,
                fileName = name.substringAfter('_'),
                bytesReceived = attributes.size(),
                totalBytes = attributes.size(),
                mimeType = BrowserDownloadArtifacts.mimeTypeForName(name),
                artifactPath = "/opt/browser/out/downloads/$name",
                errorCode = null,
                updatedAtMillis = attributes.lastModifiedTime().toMillis(),
            )
        }
        return PersistedListing(items, truncated)
    }

    private fun createOwnedStage(directory: SecureDirectoryStream<Path>, job: Job) {
        val marker = directory.newByteChannel(Paths.get(job.markerName), writeCreateNewOptions())
        try {
            Channels.newOutputStream(marker).use { it.write("${job.id}:${job.markerToken}".toByteArray(Charsets.UTF_8)) }
        } catch (error: Throwable) {
            runCatching { marker.close() }
            runCatching { directory.deleteFile(Paths.get(job.markerName)) }
            throw error
        }
        try {
            directory.newByteChannel(Paths.get(job.partName), writeCreateNewOptions()).use { channel ->
                channel.position(0L)
            }
        } catch (error: Throwable) {
            deleteMarkerIfOwned(directory, job)
            throw error
        }
    }

    private fun openStageOutput(directory: SecureDirectoryStream<Path>, name: String) =
        Channels.newOutputStream(directory.newByteChannel(Paths.get(name), setOf<OpenOption>(StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)))

    private fun cleanupStalePartials(directory: SecureDirectoryStream<Path>) {
        val now = wallClock()
        val markers = ArrayList<String>()
        var visited = 0
        for (entry in directory) {
            if (++visited > BrowserDownloadPolicy.MAX_DIRECTORY_SCAN) break
            val name = entry.fileName.toString()
            if (OWNER_MARKER_PATTERN.matches(name)) markers += name
        }
        for (markerName in markers) {
            val markerAttributes = secureAttributes(directory, markerName) ?: continue
            if (!markerAttributes.isRegularFile || now - markerAttributes.lastModifiedTime().toMillis() < BrowserDownloadPolicy.STALE_PART_GRACE_MS) continue
            val id = markerName.removePrefix(".dshbox-download-").removeSuffix(".owner")
            if (!ID_PATTERN.matches(id)) continue
            val markerContent = readSmallRegularFile(directory, markerName) ?: continue
            if (!markerContent.startsWith("$id:")) continue
            val partName = ".dshbox-download-$id.part"
            val partAttributes = secureAttributes(directory, partName)
            if (partAttributes != null && partAttributes.isRegularFile) directory.deleteFile(Paths.get(partName))
            directory.deleteFile(Paths.get(markerName))
        }
    }

    private fun removeOwnedStage(directory: SecureDirectoryStream<Path>, job: Job) {
        val markerContent = readSmallRegularFile(directory, job.markerName)
        if (markerContent != "${job.id}:${job.markerToken}") return
        val partAttributes = secureAttributes(directory, job.partName)
        if (partAttributes != null && partAttributes.isRegularFile) runCatching { directory.deleteFile(Paths.get(job.partName)) }
        runCatching { directory.deleteFile(Paths.get(job.markerName)) }
    }

    private fun deleteMarkerIfOwned(directory: SecureDirectoryStream<Path>, job: Job) {
        if (readSmallRegularFile(directory, job.markerName) == "${job.id}:${job.markerToken}") {
            runCatching { directory.deleteFile(Paths.get(job.markerName)) }
        }
    }

    private fun readSmallRegularFile(directory: SecureDirectoryStream<Path>, name: String): String? {
        val attributes = secureAttributes(directory, name) ?: return null
        if (!attributes.isRegularFile || attributes.size() > 512L) return null
        return runCatching {
            val channel = directory.newByteChannel(Paths.get(name), setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            channel.use {
                val buffer = ByteBuffer.allocate(512)
                val output = ByteArrayOutputStream()
                while (it.read(buffer) > 0) {
                    buffer.flip()
                    val bytes = ByteArray(buffer.remaining())
                    if (output.size() + bytes.size > 512) throw IOException("marker exceeds limit")
                    buffer.get(bytes)
                    output.write(bytes)
                    buffer.clear()
                }
                output.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    private fun secureAttributes(directory: SecureDirectoryStream<Path>, name: String): BasicFileAttributes? =
        runCatching {
            directory.getFileAttributeView(Paths.get(name), BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                ?.readAttributes()
        }.getOrNull()

    private fun secureDownloadDirectory(): SecureDirectoryStream<Path> =
        BrowserDownloadArtifacts.openDownloads(appFilesDirectory)

    private fun writeCreateNewOptions(): Set<OpenOption> = setOf(
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    )

    private fun publishProgress(job: Job, bytes: Long, total: Long?) {
        synchronized(stateLock) {
            if (job.finished || job.cancelRequested.get()) return
            job.item = job.item.copy(bytesReceived = bytes, totalBytes = total, updatedAtMillis = wallClock())
        }
        notifyListeners(job.item)
    }

    private fun updateLocked(job: Job, state: BrowserDownloadState) {
        job.item = job.item.copy(state = state, updatedAtMillis = wallClock())
    }

    private fun checkNotCancelled(job: Job) {
        if (job.cancelRequested.get() || Thread.currentThread().isInterrupted) throw InterruptedException("download cancelled")
        if (BrowserDownloadPolicy.transferDeadlineExpired(job.deadlineElapsedMillis, elapsedClock())) {
            throw DownloadFailure("TIMEOUT")
        }
    }

    private fun releaseReservationLocked(job: Job) {
        if (!job.reservationHeld) return
        job.reservationHeld = false
        reservedEntries = (reservedEntries - 1).coerceAtLeast(0)
        reservedBytes = (reservedBytes - job.reservationBytes).coerceAtLeast(0L)
        job.reservationBytes = 0L
    }

    private fun rememberFailure(id: String, code: String): BrowserDownloadItem = synchronized(stateLock) {
        rememberFailureLocked(id, code)
    }

    private fun rememberFailureLocked(id: String, code: String): BrowserDownloadItem {
        val item = BrowserDownloadItem(id, BrowserDownloadState.FAILED, null, 0L, null, null, null, code, wallClock())
        rememberLocked(item)
        return item
    }

    private fun rememberLocked(item: BrowserDownloadItem) {
        recent[item.id] = item
        while (recent.size > MAX_RECENT_RESULTS) recent.remove(recent.keys.first())
    }

    private fun notifyListeners(item: BrowserDownloadItem) {
        listeners.forEach { listener -> runCatching { listener(item) } }
    }

    private fun safeAdd(left: Long, right: Long): Long = if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun isOwnedStageName(name: String): Boolean = OWNER_MARKER_PATTERN.matches(name) || PART_PATTERN.matches(name)

    private class DownloadFailure(val code: String) : IOException(code)

    companion object {
        private const val MAX_RECENT_RESULTS = 100
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val ID_PATTERN = Regex("^[a-f0-9]{32}$")
        private val OWNER_MARKER_PATTERN = Regex("^\\.dshbox-download-[a-f0-9]{32}\\.owner$")
        private val PART_PATTERN = Regex("^\\.dshbox-download-[a-f0-9]{32}\\.part$")

        @Volatile private var instance: BrowserDownloadsManager? = null

        /** Returns the one Browser-process manager; the download directory must be provisioned by the app. */
        fun getInstance(context: Context): BrowserDownloadsManager = instance ?: synchronized(BrowserDownloadsManager::class.java) {
            instance ?: BrowserDownloadsManager(context.applicationContext).also { instance = it }
        }
    }
}
