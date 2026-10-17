package com.dshbox.browser.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import android.system.Os
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class BrowserUploadProviderFile(
    val grantId: String, val diskName: String, val displayName: String, val mimeType: String,
    val size: Long, val identity: String, val sha256: String,
)

/** Only completely authenticated approved bytes enter a bounded, expiring, read-only snapshot. */
class BrowserUploadProvider : ContentProvider() {
    override fun onCreate(): Boolean = context?.let { ensureInitialized(it.cacheDir.canonicalFile) } ?: false
    override fun getType(uri: Uri): String? = lookupEntry(uri)?.third?.mimeType
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val file = lookupEntry(uri)?.third ?: return null
        val columns = projection?.takeIf { it.size <= 8 }?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> file.displayName
            OpenableColumns.SIZE -> file.size
            else -> null
        } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only upload URI")
        val (token, entry, file) = lookupEntry(uri) ?: throw FileNotFoundException("Upload grant expired")
        val app = context ?: throw FileNotFoundException("Upload provider unavailable")
        if (!reserveReader(entry, file.size)) throw FileNotFoundException("Upload reader limit reached")
        var bytes: ByteArray? = null
        var descriptor: ParcelFileDescriptor? = null
        var reservedReader = true
        val readerRef = java.util.concurrent.atomic.AtomicReference<Reader?>()
        try {
            val fileId = uri.pathSegments[1]
            val (snapshot, approvedBytes) = authenticatedSnapshot(token, entry, fileId, file,
                app.filesDir.canonicalFile, app.cacheDir)
            bytes = approvedBytes
            checkSnapshotPathMatchesControl(snapshot)
            lateinit var reader: Reader
            descriptor = ParcelFileDescriptor.open(snapshot.file, ParcelFileDescriptor.MODE_READ_ONLY, closeHandler,
                ParcelFileDescriptor.OnCloseListener { readerRef.get()?.let { removeReader(entry, it) } })
            check(sameInode(snapshot, Os.fstat(descriptor!!.fileDescriptor)))
            reader = Reader(snapshot, file.size, entry, descriptor!!)
            readerRef.set(reader)
            synchronized(entry) {
                if (!isLive(token, entry) || entry.snapshots[fileId] !== snapshot) {
                    throw FileNotFoundException("Upload grant expired")
                }
                entry.readers.add(reader)
            }
            reservedReader = false
            return descriptor!!
        } catch (_: Exception) {
            readerRef.get()?.let {
                cleanupReader(it, closeDescriptor = true)
                reservedReader = false
            }
            runCatching { descriptor?.close() }
            if (reservedReader) releaseReader(entry, file.size)
            throw FileNotFoundException("Approved upload bytes unavailable")
        } finally {
            bytes?.fill(0)
        }
    }

    private fun lookupEntry(uri: Uri): Triple<String, Entry, BrowserUploadProviderFile>? {
        val app = context ?: return null
        if (uri.scheme != "content" || uri.authority != "${app.packageName}.browser.upload" || uri.query != null ||
            uri.fragment != null || uri.pathSegments.size != 2) return null
        val token = uri.pathSegments[0]
        val fileId = uri.pathSegments[1]
        if (!token.matches(ID) || !fileId.matches(ID)) return null
        val entry = entries[token] ?: return null
        if (!isLive(token, entry)) { revoke(token); return null }
        return entry.files[fileId]?.let { Triple(token, entry, it) }
    }
    private class SharedSnapshot(
        val file: File,
        val size: Long,
        val controlDescriptor: ParcelFileDescriptor,
        val device: Long,
        val inode: Long,
    )
    private class Reader(
        val snapshot: SharedSnapshot,
        val size: Long,
        val entry: Entry,
        val descriptor: ParcelFileDescriptor,
    ) {
        val cleaned = AtomicBoolean(false)
    }
    private data class Entry(val files: Map<String, BrowserUploadProviderFile>, val expiresAtElapsedMillis: Long,
        val snapshots: MutableMap<String, SharedSnapshot> = HashMap(),
        val readers: MutableSet<Reader> = ConcurrentHashMap.newKeySet(), var expiryTask: ScheduledFuture<*>? = null,
        var snapshotCount: Int = 0, var snapshotBytes: Long = 0L,
        var readerCount: Int = 0, var readerBytes: Long = 0L)

    companion object {
        private val ID = Regex("[a-f0-9]{32}")
        private val SNAPSHOT_NAME = Regex("verified-[A-Za-z0-9]+\\.bin")
        private const val SNAPSHOT_DIRECTORY = "browser-upload-provider"
        private val entries = ConcurrentHashMap<String, Entry>()
        private val initializationLock = Any()
        @Volatile private var initialized = false
        private val transferLock = Any()
        private val closeHandler = Handler(Looper.getMainLooper())
        private var activeReaderCount = 0
        private var activeReaderBytes = 0L
        private var activeSnapshotCount = 0
        private var activeSnapshotBytes = 0L
        private const val MAX_OPEN_READERS = 8
        private const val MAX_ACTIVE_SNAPSHOTS = 16
        private const val MAX_ACTIVE_SNAPSHOT_BYTES = 32L * 1024 * 1024
        internal const val STABLE_SNAPSHOT_MTIME_MILLIS = 1_600_000_000_000L
        private val expiryScheduler = ScheduledThreadPoolExecutor(1) { Thread(it, "browser-upload-expiry").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
        private fun isLive(token: String, entry: Entry) = entries[token] === entry && SystemClock.elapsedRealtime() < entry.expiresAtElapsedMillis

        private fun ensureInitialized(cacheDir: File): Boolean = synchronized(initializationLock) {
            if (initialized) return@synchronized true
            val hasEntries = entries.isNotEmpty()
            val hasBuildingOrLiveSnapshots = synchronized(transferLock) { activeSnapshotCount > 0 }
            if (hasEntries || hasBuildingOrLiveSnapshots) {
                initialized = true
                return@synchronized true
            }
            if (!cleanupOrphanSnapshots(cacheDir)) return@synchronized false
            initialized = true
            true
        }

        /** Test seam uses this exact cleaner against a disposable cache root; policy stays unchanged. */
        internal fun cleanOrphanSnapshotsForTest(cacheDir: File): Boolean = cleanupOrphanSnapshots(cacheDir)

        private fun cleanupOrphanSnapshots(cacheDir: File): Boolean = runCatching {
            val absoluteCache = cacheDir.absoluteFile.toPath().normalize().toFile()
            val canonicalCache = cacheDir.canonicalFile
            if (absoluteCache != canonicalCache || !Files.isDirectory(canonicalCache.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                return@runCatching false
            }
            val root = File(canonicalCache, SNAPSHOT_DIRECTORY)
            val rootPath = root.toPath()
            if (!Files.exists(rootPath, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(rootPath)
            if (Files.isSymbolicLink(rootPath) || !Files.isDirectory(rootPath, LinkOption.NOFOLLOW_LINKS) ||
                root.canonicalFile.parentFile != canonicalCache) return@runCatching false
            Files.newDirectoryStream(rootPath).use { children ->
                for (child in children) {
                    if (!SNAPSHOT_NAME.matches(child.fileName.toString()) ||
                        !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) continue
                    Files.delete(child)
                }
            }
            true
        }.getOrDefault(false)

        private fun authenticatedSnapshot(token: String, entry: Entry, fileId: String, file: BrowserUploadProviderFile,
            filesRoot: File, cacheDir: File): Pair<SharedSnapshot, ByteArray> = synchronized(entry) {
            if (!isLive(token, entry)) throw FileNotFoundException("Upload grant expired")
            val existing = entry.snapshots[fileId]
            val needsSnapshot = existing == null
            if (needsSnapshot && !reserveSnapshot(entry, file.size)) throw FileNotFoundException("Upload snapshot limit reached")
            var bytes: ByteArray? = null
            var created: SharedSnapshot? = null
            try {
                val approved = BrowserUploadFileAccess.readApprovedStagedBytes(filesRoot, file.grantId, file.diskName,
                    file.size, file.identity, file.sha256) { isLive(token, entry) }
                bytes = approved
                check(approved.size.toLong() == file.size && isLive(token, entry))
                val snapshot = existing ?: createSnapshot(file, approved, cacheDir).also { created = it }
                if (existing == null) {
                    check(isLive(token, entry))
                    entry.snapshots[fileId] = snapshot
                }
                checkSnapshotPathMatchesControl(snapshot)
                snapshot to approved
            } catch (failure: Exception) {
                bytes?.fill(0)
                if (needsSnapshot) {
                    created?.let { cleanupSnapshot(it, truncate = true) }
                    entry.snapshots.remove(fileId)
                    releaseSnapshot(entry, file.size)
                }
                throw failure
            }
        }

        private fun createSnapshot(file: BrowserUploadProviderFile, bytes: ByteArray, cacheDir: File): SharedSnapshot {
            val cacheRoot = File(cacheDir, "browser-upload-provider")
            if (!cacheRoot.isDirectory && !cacheRoot.mkdirs()) throw FileNotFoundException("Upload cache unavailable")
            val snapshot = File.createTempFile("verified-", ".bin", cacheRoot)
            var control: ParcelFileDescriptor? = null
            try {
                FileOutputStream(snapshot).use { output ->
                    output.write(bytes)
                    output.fd.sync()
                }
                check(snapshot.setLastModified(STABLE_SNAPSHOT_MTIME_MILLIS) &&
                    snapshot.lastModified() / 1_000L == STABLE_SNAPSHOT_MTIME_MILLIS / 1_000L)
                control = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_WRITE)
                val stat = Os.fstat(control!!.fileDescriptor)
                val shared = SharedSnapshot(snapshot, file.size, control!!, stat.st_dev, stat.st_ino)
                checkSnapshotPathMatchesControl(shared)
                return shared
            } catch (failure: Exception) {
                runCatching { control?.close() }
                runCatching { snapshot.delete() }
                throw failure
            }
        }

        private fun checkSnapshotPathMatchesControl(snapshot: SharedSnapshot) {
            check(sameInode(snapshot, Os.stat(snapshot.file.absolutePath)))
        }

        private fun sameInode(snapshot: SharedSnapshot, stat: android.system.StructStat): Boolean =
            stat.st_dev == snapshot.device && stat.st_ino == snapshot.inode

        private fun reserveSnapshot(entry: Entry, size: Long): Boolean {
            synchronized(entry) {
                if (!isLiveEntry(entry) || size < 0 || size > BrowserUploadPolicy.MAX_TOTAL_BYTES ||
                    entry.snapshotCount >= entry.files.size || entry.snapshotBytes + size > BrowserUploadPolicy.MAX_TOTAL_BYTES) return false
                synchronized(transferLock) {
                    if (activeSnapshotCount >= MAX_ACTIVE_SNAPSHOTS ||
                        activeSnapshotBytes + size > MAX_ACTIVE_SNAPSHOT_BYTES) return false
                    activeSnapshotCount++
                    activeSnapshotBytes += size
                }
                entry.snapshotCount++
                entry.snapshotBytes += size
                return true
            }
        }

        private fun releaseSnapshot(entry: Entry, size: Long) {
            synchronized(entry) {
                entry.snapshotCount = (entry.snapshotCount - 1).coerceAtLeast(0)
                entry.snapshotBytes = (entry.snapshotBytes - size).coerceAtLeast(0L)
            }
            synchronized(transferLock) {
                activeSnapshotCount = (activeSnapshotCount - 1).coerceAtLeast(0)
                activeSnapshotBytes = (activeSnapshotBytes - size).coerceAtLeast(0L)
            }
        }

        private fun reserveReader(entry: Entry, size: Long): Boolean {
            synchronized(entry) {
                if (!isLiveEntry(entry) || size < 0 || size > BrowserUploadPolicy.MAX_TOTAL_BYTES ||
                    entry.readerCount >= MAX_OPEN_READERS || entry.readerBytes + size > BrowserUploadPolicy.MAX_TOTAL_BYTES) return false
                synchronized(transferLock) {
                    if (activeReaderCount >= MAX_OPEN_READERS ||
                        activeReaderBytes + size > BrowserUploadPolicy.MAX_TOTAL_BYTES) return false
                    activeReaderCount++
                    activeReaderBytes += size
                }
                entry.readerCount++
                entry.readerBytes += size
                return true
            }
        }

        private fun releaseReader(entry: Entry, size: Long) {
            synchronized(entry) {
                entry.readerCount = (entry.readerCount - 1).coerceAtLeast(0)
                entry.readerBytes = (entry.readerBytes - size).coerceAtLeast(0L)
            }
            synchronized(transferLock) {
                activeReaderCount = (activeReaderCount - 1).coerceAtLeast(0)
                activeReaderBytes = (activeReaderBytes - size).coerceAtLeast(0L)
            }
        }

        private fun isLiveEntry(entry: Entry) = entries.values.any { it === entry } &&
            SystemClock.elapsedRealtime() < entry.expiresAtElapsedMillis

        private fun removeReader(entry: Entry, reader: Reader) {
            synchronized(entry) { entry.readers.remove(reader) }
            cleanupReader(reader, closeDescriptor = true)
        }

        private fun cleanupReader(reader: Reader, closeDescriptor: Boolean) {
            if (!reader.cleaned.compareAndSet(false, true)) return
            if (closeDescriptor) runCatching { reader.descriptor.close() }
            releaseReader(reader.entry, reader.size)
        }

        private fun cleanupSnapshot(snapshot: SharedSnapshot, truncate: Boolean) {
            if (truncate) runCatching { Os.ftruncate(snapshot.controlDescriptor.fileDescriptor, 0L) }
            runCatching { snapshot.controlDescriptor.close() }
            runCatching { snapshot.file.delete() }
        }

        @Synchronized internal fun register(context: android.content.Context, token: String,
            files: Map<String, BrowserUploadStagedFile>, expiresAtWallMillis: Long): List<Uri> {
            prune()
            require(ensureInitialized(context.cacheDir.canonicalFile)) { "Browser upload cache unavailable" }
            require(token.matches(ID) && files.size in 1..BrowserUploadPolicy.MAX_FILES && entries.size < 16)
            val filesRoot = context.filesDir.canonicalFile
            val mapped = files.mapValues { (id, value) ->
                require(id.matches(ID) && id == value.fileId && value.sha256.matches(Regex("[a-f0-9]{64}")))
                val grant = value.file.parentFile?.name.orEmpty()
                val disk = value.file.name
                val size = BrowserUploadFileAccess.stagedSize(filesRoot, grant, disk) ?: error("Missing staged file")
                val identity = BrowserUploadFileAccess.stagedIdentity(filesRoot, grant, disk) ?: error("Missing staged identity")
                require(size == value.size)
                BrowserUploadProviderFile(grant, disk, value.name, value.mimeType, size, identity, value.sha256)
            }
            require(mapped.values.sumOf { it.size } <= BrowserUploadPolicy.MAX_TOTAL_BYTES)
            val remaining = expiresAtWallMillis - System.currentTimeMillis()
            require(remaining > 0)
            val ttl = remaining.coerceAtMost(BrowserUploadPolicy.URI_TTL_MILLIS)
            val entry = Entry(mapped, SystemClock.elapsedRealtime() + ttl)
            require(entries.putIfAbsent(token, entry) == null)
            entry.expiryTask = expiryScheduler.schedule({ revoke(token) }, ttl, TimeUnit.MILLISECONDS)
            return files.keys.map { Uri.Builder().scheme("content").authority("${context.packageName}.browser.upload")
                .appendPath(token).appendPath(it).build() }
        }

        internal fun revoke(token: String) {
            val entry = entries.remove(token) ?: return
            entry.expiryTask?.cancel(false)
            val (readers, snapshots) = synchronized(entry) {
                entry.readers.toList().also { entry.readers.clear() } to
                    entry.snapshots.values.toList().also { entry.snapshots.clear() }
            }
            readers.forEach { cleanupReader(it, closeDescriptor = true) }
            snapshots.forEach {
                cleanupSnapshot(it, truncate = true)
                releaseSnapshot(entry, it.size)
            }
        }
        internal fun prune(nowElapsedMillis: Long = SystemClock.elapsedRealtime()) {
            entries.entries.filter { nowElapsedMillis >= it.value.expiresAtElapsedMillis }.forEach { revoke(it.key) }
        }
    }
}
