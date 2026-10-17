package com.dshbox.browser.runtime

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BrowserDownloadsManagerTest {
    private lateinit var fixture: DownloadFixture

    @Before
    fun createFixture() {
        fixture = DownloadFixture()
    }

    @After
    fun closeFixture() {
        fixture.close()
    }

    @Test
    fun queuedAndDownloadingCancellationReachTerminalAndRemoveOwnedStages() {
        fixture.assumeSecure()
        val startedIds = CopyOnWriteArrayList<String>()
        val gates = mapOf("/one" to CountDownLatch(1), "/two" to CountDownLatch(1))
        val connections = ConcurrentHashMap<String, FakeHttpConnection>()
        val connectionsCreated = CountDownLatch(2)
        val watcher = StateWatcher()
        val factory: (URI) -> HttpURLConnection = { uri ->
            FakeHttpConnection(
                url = URL(uri.toASCIIString()),
                body = ByteArray(64),
                gate = gates.getValue(uri.path),
            ).also { connections[uri.path] = it; connectionsCreated.countDown() }
        }
        val manager = fixture.newManager(factory)
        val listener = manager.addListener(watcher)
        try {
            val first = manager.start(downloadRequest("http://one.test/one"))
            val second = manager.start(downloadRequest("http://two.test/two"))
            val queued = manager.start(downloadRequest("http://three.test/three"))
            startedIds += listOf(first.id, second.id, queued.id)

            watcher.await(first.id, BrowserDownloadState.DOWNLOADING)
            watcher.await(second.id, BrowserDownloadState.DOWNLOADING)
            watcher.await(queued.id, BrowserDownloadState.QUEUED)
            assertTrue("active connections were not created", connectionsCreated.await(5, TimeUnit.SECONDS))
            awaitOpened(connections.getValue("/one"))
            awaitOpened(connections.getValue("/two"))

            assertTrue(manager.cancel(queued.id))
            assertEquals(BrowserDownloadState.CANCELLED, watcher.await(
                queued.id,
                BrowserDownloadState.CANCELLED,
            ).state)
            assertNull(connections["/three"])

            assertTrue(manager.cancel(first.id))
            watcher.await(first.id, BrowserDownloadState.CANCELLING)
            assertEquals(BrowserDownloadState.CANCELLED, watcher.await(
                first.id,
                BrowserDownloadState.CANCELLED,
            ).state)
            awaitDisconnects(connections.getValue("/one"), 2)

            assertTrue(manager.cancel(second.id))
            watcher.await(second.id, BrowserDownloadState.CANCELLING)
            assertEquals(BrowserDownloadState.CANCELLED, watcher.await(
                second.id,
                BrowserDownloadState.CANCELLED,
            ).state)
            awaitDisconnects(connections.getValue("/two"), 2)

            assertEquals(emptySet<String>(), fixture.ownedStageNames())
        } finally {
            startedIds.forEach(manager::cancel)
            manager.close()
            startedIds.forEach { watcher.awaitTerminal(it) }
            connections.values.forEach { awaitDisconnects(it, 2) }
            awaitDownloadWorkersClosed()
            listener.close()
        }
    }

    @Test
    fun knownAndStreamingOversizeFailuresRemoveMarkerAndPartialFile() {
        fixture.assumeSecure()
        val startedIds = CopyOnWriteArrayList<String>()
        val watcher = StateWatcher()
        val connections = CopyOnWriteArrayList<FakeHttpConnection>()
        val factory: (URI) -> HttpURLConnection = { uri ->
            val declared = if (uri.path == "/declared") 65L else -1L
            FakeHttpConnection(
                url = URL(uri.toASCIIString()),
                headers = if (declared >= 0) mapOf("Content-Length" to declared.toString()) else emptyMap(),
                body = ByteArray(65),
            ).also(connections::add)
        }
        val manager = fixture.newManager(factory)
        val listener = manager.addListener(watcher)
        try {
            val declared = manager.start(
                downloadRequest("http://size.test/declared").copy(maxBytes = 64L),
            )
            startedIds += declared.id
            val failedDeclared = watcher.await(declared.id, BrowserDownloadState.FAILED)
            assertEquals("FILE_TOO_LARGE", failedDeclared.errorCode)
            awaitDisconnects(connections[0], 1)

            val streamed = manager.start(
                downloadRequest("http://size.test/streamed").copy(maxBytes = 64L),
            )
            startedIds += streamed.id
            val failedStream = watcher.await(streamed.id, BrowserDownloadState.FAILED)
            assertEquals("FILE_TOO_LARGE", failedStream.errorCode)
            awaitDisconnects(connections[1], 1)

            assertEquals(emptySet<String>(), fixture.ownedStageNames())
            assertTrue(manager.list().items.none { it.state == BrowserDownloadState.COMPLETED })
        } finally {
            startedIds.forEach(manager::cancel)
            manager.close()
            startedIds.forEach { watcher.awaitTerminal(it) }
            connections.forEach { awaitDisconnects(it, 1) }
            awaitDownloadWorkersClosed()
            listener.close()
        }
    }

    @Test
    fun crossOriginRedirectChainNeverRestoresCredentials() {
        fixture.assumeSecure()
        val startedIds = CopyOnWriteArrayList<String>()
        val watcher = StateWatcher()
        val connections = CopyOnWriteArrayList<FakeHttpConnection>()
        val factory: (URI) -> HttpURLConnection = { uri ->
            val response = when (uri.host) {
                "a.test" -> if (uri.path == "/start") {
                    FakeHttpConnection(
                        url = URL(uri.toASCIIString()),
                        status = 302,
                        headers = mapOf("Location" to "http://b.test/mid"),
                    )
                } else {
                    FakeHttpConnection(
                        url = URL(uri.toASCIIString()),
                        body = "completed".toByteArray(),
                        contentType = "text/plain",
                    )
                }
                "b.test" -> FakeHttpConnection(
                    url = URL(uri.toASCIIString()),
                    status = 302,
                    headers = mapOf("Location" to "http://a.test/final"),
                )
                else -> error("unexpected redirect host: ${uri.host}")
            }
            response.also(connections::add)
        }
        val manager = fixture.newManager(factory)
        val listener = manager.addListener(watcher)
        try {
            val started = manager.start(BrowserDownloadRequest(
                url = "http://a.test/start",
                suggestedFileName = "redirect.txt",
                cookieHeader = "sid=secret",
                authorizationHeader = "Bearer secret",
            ))
            startedIds += started.id
            val completed = watcher.await(started.id, BrowserDownloadState.COMPLETED)
            assertEquals("redirect.txt", completed.fileName)
            assertEquals("completed", fixture.readArtifact(completed.artifactPath))
            connections.forEach { awaitDisconnects(it, 1) }

            assertEquals(listOf("a.test", "b.test", "a.test"), connections.map { it.url.host })
            assertEquals("sid=secret", connections[0].requestHeaders["Cookie"])
            assertEquals("Bearer secret", connections[0].requestHeaders["Authorization"])
            assertNull(connections[1].requestHeaders["Cookie"])
            assertNull(connections[1].requestHeaders["Authorization"])
            assertNull(connections[2].requestHeaders["Cookie"])
            assertNull(connections[2].requestHeaders["Authorization"])
        } finally {
            startedIds.forEach(manager::cancel)
            manager.close()
            startedIds.forEach { watcher.awaitTerminal(it) }
            connections.forEach { awaitDisconnects(it, 1) }
            awaitDownloadWorkersClosed()
            listener.close()
        }
    }

    @Test
    fun completedFilePersistsAcrossInstancesUntilSecurelyDeleted() {
        fixture.assumeSecure()
        val startedIds = CopyOnWriteArrayList<String>()
        val watcher = StateWatcher()
        val factory: (URI) -> HttpURLConnection = { uri ->
            FakeHttpConnection(
                url = URL(uri.toASCIIString()),
                body = "persistent".toByteArray(),
                contentType = "text/plain",
            )
        }
        val firstManager = fixture.newManager(factory)
        val firstListener = firstManager.addListener(watcher)
        val completed: BrowserDownloadItem
        try {
            val started = firstManager.start(BrowserDownloadRequest(
                url = "http://persist.test/file.txt",
                suggestedFileName = "file.txt",
            ))
            startedIds += started.id
            completed = watcher.await(started.id, BrowserDownloadState.COMPLETED)
            assertEquals("persistent", fixture.readArtifact(completed.artifactPath))
        } finally {
            startedIds.forEach(firstManager::cancel)
            firstManager.close()
            startedIds.forEach { watcher.awaitTerminal(it) }
            awaitDownloadWorkersClosed()
            firstListener.close()
        }

        val secondManager = fixture.newManager { error("completed persistence must not reconnect") }
        try {
            assertEquals(completed.id, secondManager.find(completed.id)?.id)
            assertTrue(secondManager.list().items.any { it.id == completed.id })
            assertTrue(fixture.deletePersistedFixture(completed.id))
            assertNull(secondManager.find(completed.id))
            assertTrue(secondManager.list().items.none { it.id == completed.id })
        } finally {
            secondManager.close()
            awaitDownloadWorkersClosed()
        }
    }

    @Test
    fun externallyDeletedCompletedFileIsNotReportedBySameManager() {
        fixture.assumeSecure()
        val watcher = StateWatcher()
        val manager = fixture.newManager { uri ->
            FakeHttpConnection(URL(uri.toASCIIString()), body = "owned download".toByteArray())
        }
        val listener = manager.addListener(watcher)
        try {
            val item = manager.start(BrowserDownloadRequest("http://deleted.test/file.txt"))
            val completed = watcher.await(item.id, BrowserDownloadState.COMPLETED)
            assertEquals(completed, manager.find(item.id))
            assertEquals(completed, manager.list().items.single { it.id == item.id })
            assertTrue(fixture.deletePersistedFixture(item.id))
            assertNull(manager.find(item.id))
            assertTrue(manager.list().items.none { it.id == item.id })
        } finally {
            manager.close()
            awaitDownloadWorkersClosed()
            listener.close()
        }
    }

    @Test
    fun truncatedDirectoryCannotConfirmMissingDownload() {
        fixture.assumeSecure()
        fixture.populateForeignEntries(BrowserDownloadPolicy.MAX_DIRECTORY_SCAN + 1)
        val manager = fixture.newManager { error("lookup must not connect") }
        try {
            assertTrue(manager.list().truncated)
            var rejected = false
            try {
                manager.find("0".repeat(32))
            } catch (error: IOException) {
                assertEquals("Download directory scan limit exceeded", error.message)
                rejected = true
            }
            assertTrue("incomplete enumeration must not confirm absence", rejected)
            assertEquals(BrowserDownloadPolicy.MAX_DIRECTORY_SCAN + 1, fixture.foreignEntryCount())
        } finally {
            manager.close()
            awaitDownloadWorkersClosed()
        }
    }

    @Test
    fun realHttpDefaultFactoryFollowsHeadersRedirectLengthAndDiskContract() {
        fixture.assumeSecure()
        val payload = "real local http download\n".toByteArray(Charsets.UTF_8)
        val requests = ConcurrentHashMap<String, Map<String, String>>()
        val http = LocalHttpFixture { request ->
            val path = request.path
            requests[path] = mapOf(
                "Accept-Encoding" to request.headers["accept-encoding"].orEmpty(),
                "User-Agent" to request.headers["user-agent"].orEmpty(),
                "Cookie" to request.headers["cookie"].orEmpty(),
                "Authorization" to request.headers["authorization"].orEmpty(),
            )
            when (path) {
                "/start" -> {
                    LocalHttpFixture.Response(
                        status = 302,
                        headers = mapOf("Location" to "/file"),
                        contentLength = 0,
                    ) {}
                }
                "/file" -> {
                    LocalHttpFixture.Response(
                        status = 200,
                        headers = mapOf(
                            "Content-Type" to "text/plain; charset=utf-8",
                            "Content-Disposition" to "attachment; filename=\"real-http.txt\"",
                            "X-Browser-Fixture" to "real-http",
                        ),
                        contentLength = payload.size.toLong(),
                    ) { output -> output.write(payload) }
                }
                else -> {
                    LocalHttpFixture.Response(404, emptyMap(), 0) {}
                }
            }
        }
        val watcher = StateWatcher()
        val manager = fixture.newDefaultManager()
        val listener = manager.addListener(watcher)
        val started = CopyOnWriteArrayList<String>()
        try {
            val item = manager.start(BrowserDownloadRequest(
                url = http.url("/start"),
                suggestedFileName = "real-http.txt",
                cookieHeader = "sid=fixture",
                authorizationHeader = "Bearer fixture",
                userAgent = "Browser-Real-HTTP-Fixture/1",
                maxBytes = 1024L,
            ))
            started += item.id
            val completed = watcher.await(item.id, BrowserDownloadState.COMPLETED)
            assertEquals("real-http.txt", completed.fileName)
            assertEquals(payload.size.toLong(), completed.totalBytes)
            assertEquals(payload.size.toLong(), completed.bytesReceived)
            assertEquals("text/plain", completed.mimeType)
            assertArrayEquals(payload, fixture.readArtifactBytes(completed.artifactPath))
            assertEquals(
                "/opt/browser/out/downloads/${completed.id}_real-http.txt",
                completed.artifactPath,
            )

            for (path in listOf("/start", "/file")) {
                val headers = requireNotNull(requests[path])
                assertEquals("identity", headers["Accept-Encoding"])
                assertEquals("Browser-Real-HTTP-Fixture/1", headers["User-Agent"])
                assertEquals("sid=fixture", headers["Cookie"])
                assertEquals("Bearer fixture", headers["Authorization"])
            }
            assertEquals(emptySet<String>(), fixture.ownedStageNames())
        } finally {
            started.forEach(manager::cancel)
            manager.close()
            started.forEach(watcher::awaitTerminal)
            awaitDownloadWorkersClosed()
            listener.close()
            http.close()
        }
    }

    @Test
    fun realHttpDefaultFactoryFailureAndCancellationRemoveOwnedStages() {
        fixture.assumeSecure()
        val bodyStarted = CountDownLatch(1)
        val releaseSlowResponse = CountDownLatch(1)
        val http = LocalHttpFixture { request ->
            when (request.path) {
                "/fail" -> {
                    LocalHttpFixture.Response(
                        status = 503,
                        headers = mapOf("Content-Type" to "text/plain"),
                        contentLength = 0,
                    ) {}
                }
                "/slow" -> {
                    LocalHttpFixture.Response(
                        status = 200,
                        headers = emptyMap(),
                        contentLength = 8_192L,
                    ) { output ->
                        output.write(ByteArray(1024) { 7 })
                        output.flush()
                        bodyStarted.countDown()
                        releaseSlowResponse.await(5, TimeUnit.SECONDS)
                        output.write(ByteArray(7_168) { 8 })
                        output.flush()
                    }
                }
                else -> {
                    LocalHttpFixture.Response(404, emptyMap(), 0) {}
                }
            }
        }
        val watcher = StateWatcher()
        val manager = fixture.newDefaultManager()
        val listener = manager.addListener(watcher)
        val started = CopyOnWriteArrayList<String>()
        try {
            val failedItem = manager.start(BrowserDownloadRequest(
                url = http.url("/fail"),
                suggestedFileName = "failure.txt",
                maxBytes = 1024L,
            ))
            started += failedItem.id
            val failed = watcher.await(failedItem.id, BrowserDownloadState.FAILED)
            assertEquals("HTTP_503", failed.errorCode)
            assertNull(failed.artifactPath)
            assertFalse(fixture.hasPersisted(failedItem.id))
            assertEquals(emptySet<String>(), fixture.ownedStageNames())

            val slowItem = manager.start(BrowserDownloadRequest(
                url = http.url("/slow"),
                suggestedFileName = "cancelled.bin",
                maxBytes = 16_384L,
            ))
            started += slowItem.id
            assertTrue("real response body did not start", bodyStarted.await(5, TimeUnit.SECONDS))
            watcher.await(slowItem.id, BrowserDownloadState.DOWNLOADING)
            assertTrue(manager.cancel(slowItem.id))
            assertEquals(BrowserDownloadState.CANCELLED, watcher.await(
                slowItem.id,
                BrowserDownloadState.CANCELLED,
            ).state)
            assertEquals(emptySet<String>(), fixture.ownedStageNames())
            assertFalse(fixture.hasPersisted(slowItem.id))
            assertTrue(manager.list().items.none { it.state == BrowserDownloadState.COMPLETED })
        } finally {
            releaseSlowResponse.countDown()
            started.forEach(manager::cancel)
            manager.close()
            started.forEach(watcher::awaitTerminal)
            awaitDownloadWorkersClosed()
            listener.close()
            http.close()
        }
    }

    @Test
    fun completedFileResolutionAndDeletionStayInsideOwnedArtifacts() {
        fixture.assumeSecure()
        val id = "e".repeat(32)
        val directory = fixture.root.resolve("browser-tools/out/downloads")
        Files.createDirectories(directory)
        val owned = directory.resolve("${id}_notes.txt")
        Files.write(owned, "notes".toByteArray(Charsets.UTF_8))
        val foreign = directory.resolve("foreign.txt")
        Files.write(foreign, byteArrayOf(1))
        val manager = fixture.newDefaultManager()
        try {
            val metadata = manager.completedMetadata(id)
            assertTrue(metadata != null)
            assertEquals("notes.txt", requireNotNull(metadata).displayName)
            val bytes = manager.openCompletedChannel(id).use { channel ->
                java.nio.channels.Channels.newInputStream(channel).readBytes()
            }
            assertEquals("notes", String(bytes, Charsets.UTF_8))

            val item = manager.find(id)
            assertTrue(item != null)
            assertEquals(BrowserDownloadState.COMPLETED, requireNotNull(item).state)
            assertEquals("notes.txt", item.fileName)

            assertEquals(
                BrowserDownloadDeleteResult.NOT_FOUND,
                manager.deleteCompleted("f".repeat(32)),
            )
            assertTrue(Files.exists(foreign))

            assertEquals(BrowserDownloadDeleteResult.DELETED, manager.deleteCompleted(id))
            assertFalse(Files.exists(owned))
            assertNull(manager.find(id))
            assertTrue("foreign files must never be deleted", Files.exists(foreign))
        } finally {
            manager.close()
            awaitDownloadWorkersClosed()
        }
    }

    private fun downloadRequest(url: String) = BrowserDownloadRequest(
        url = url,
        suggestedFileName = "payload.bin",
        maxBytes = 1024L,
    )

    private fun awaitDisconnects(connection: FakeHttpConnection, expected: Int) {
        connection.awaitDisconnects(expected)
    }

    private fun awaitOpened(connection: FakeHttpConnection) {
        assertTrue(
            "connection did not reach response dispatch",
            connection.bodyEntered.await(5, TimeUnit.SECONDS),
        )
    }

    private fun awaitDownloadWorkersClosed(timeoutMillis: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        val workers = Thread.getAllStackTraces().keys.filter { it.name == "browser-download" }
        for (worker in workers) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) throw AssertionError("download worker did not close")
            worker.join(remaining)
            assertFalse("download worker is still running", worker.isAlive)
        }
    }

    private class DownloadFixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("browser-downloads-manager-")
        private val downloads: Path = root.resolve("browser-tools").resolve("out").resolve("downloads")

        init {
            Files.createDirectories(downloads)
        }

        fun assumeSecure() {
            Files.newDirectoryStream(root).use { stream ->
                assumeTrue(
                    "SecureDirectoryStream unavailable; run the Linux storage suite",
                    stream is SecureDirectoryStream<*>,
                )
            }
        }

        fun newManager(factory: (URI) -> HttpURLConnection) = BrowserDownloadsManager(
            filesDirectory = root,
            wallClock = { 1_000_000L },
            elapsedClock = { 1_000L },
            openConnection = factory,
        )

        fun newDefaultManager() = BrowserDownloadsManager(
            filesDirectory = root,
            wallClock = { 1_000_000L },
            elapsedClock = { 1_000L },
        )

        fun populateForeignEntries(count: Int) {
            repeat(count) { Files.write(downloads.resolve("foreign-$it.txt"), byteArrayOf(1)) }
        }

        fun foreignEntryCount(): Int = Files.newDirectoryStream(downloads).use { stream ->
            stream.count { it.fileName.toString().startsWith("foreign-") }
        }

        fun ownedStageNames(): Set<String> {
            val names = mutableSetOf<String>()
            Files.newDirectoryStream(downloads).use { stream ->
                for (path in stream) {
                    val name = path.fileName.toString()
                    if (name.startsWith(".dshbox-download-") &&
                        (name.endsWith(".owner") || name.endsWith(".part"))
                    ) names += name
                }
            }
            return names
        }

        fun readArtifact(artifactPath: String?): String {
            val path = requireNotNull(artifactPath)
            require(path.startsWith("/opt/browser/out/downloads/"))
            return String(Files.readAllBytes(downloads.resolve(path.substringAfterLast('/'))), Charsets.UTF_8)
        }

        fun readArtifactBytes(artifactPath: String?): ByteArray {
            val path = requireNotNull(artifactPath)
            require(path.startsWith("/opt/browser/out/downloads/"))
            return Files.readAllBytes(downloads.resolve(path.substringAfterLast('/')))
        }

        fun hasPersisted(id: String): Boolean = Files.newDirectoryStream(downloads).use { stream ->
            stream.any { it.fileName.toString().startsWith("${id}_") }
        }

        fun deletePersistedFixture(id: String): Boolean {
            var deleted = false
            val opened = Files.newDirectoryStream(downloads)
            assertTrue(opened is SecureDirectoryStream<*>)
            @Suppress("UNCHECKED_CAST")
            (opened as SecureDirectoryStream<Path>).use { secure ->
                for (path in secure) {
                    val name = path.fileName.toString()
                    if (name.startsWith("${id}_") &&
                        name.matches(Regex("^[a-f0-9]{32}_[A-Za-z0-9_.-]{1,180}$"))
                    ) {
                        secure.deleteFile(path.fileName)
                        deleted = true
                        break
                    }
                }
            }
            return deleted
        }

        override fun close() {
            if (!Files.exists(root)) return
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private class LocalHttpFixture(
        private val handler: (Request) -> Response,
    ) : AutoCloseable {
        data class Request(val method: String, val path: String, val headers: Map<String, String>)
        data class Response(
            val status: Int,
            val headers: Map<String, String>,
            val contentLength: Long,
            val writeBody: (OutputStream) -> Unit,
        )

        private val executor: ExecutorService = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "browser-download-http-fixture").apply { isDaemon = true }
        }
        private val server = ServerSocket().apply {
            bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        }
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        private val acceptThread = Thread({
            while (!closed.get()) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    return@Thread
                }
                try {
                    executor.execute { handle(client) }
                } catch (_: RuntimeException) {
                    runCatching { client.close() }
                }
            }
        }, "browser-download-http-accept").apply {
            isDaemon = true
            start()
        }

        fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { server.close() }
            acceptThread.join(2_000)
            executor.shutdownNow()
            assertTrue("HTTP fixture executor did not stop", executor.awaitTermination(2, TimeUnit.SECONDS))
        }

        private fun handle(client: Socket) {
            try {
                client.use { socket ->
                    val request = readRequest(socket.getInputStream()) ?: return
                    val response = try {
                        handler(request)
                    } catch (error: InterruptedException) {
                        Thread.currentThread().interrupt()
                        Response(500, emptyMap(), 0) {}
                    } catch (_: Throwable) {
                        Response(500, emptyMap(), 0) {}
                    }
                    val output = socket.getOutputStream().buffered()
                    writeResponse(output, response)
                    output.flush()
                }
            } catch (_: IOException) {
                // A cancelled client can disconnect after headers while the fixture is still writing.
            }
        }

        private fun readRequest(input: InputStream): Request? {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < MAX_REQUEST_BYTES) {
                val value = input.read()
                if (value < 0) break
                bytes.write(value)
                val encoded = bytes.toByteArray()
                if (encoded.size >= 4 && encoded.copyOfRange(encoded.size - 4, encoded.size)
                        .contentEquals(byteArrayOf(13, 10, 13, 10))
                ) break
            }
            val lines = bytes.toString(Charsets.ISO_8859_1.name()).split("\r\n")
            val requestLine = lines.firstOrNull()?.split(' ') ?: return null
            if (requestLine.size < 3) return null
            val headers = lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null
                else line.substring(0, separator).trim().lowercase() to
                    line.substring(separator + 1).trim()
            }.toMap()
            return Request(requestLine[0], requestLine[1], headers)
        }

        private fun writeResponse(output: OutputStream, response: Response) {
            val reason = when (response.status) {
                200 -> "OK"
                302 -> "Found"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                503 -> "Service Unavailable"
                else -> "Status"
            }
            val headerText = buildString {
                append("HTTP/1.1 ${response.status} $reason\r\n")
                append("Connection: close\r\n")
                append("Content-Length: ${response.contentLength}\r\n")
                response.headers.forEach { (name, value) ->
                    if (!name.equals("Content-Length", ignoreCase = true) &&
                        !name.equals("Connection", ignoreCase = true)
                    ) append("$name: $value\r\n")
                }
                append("\r\n")
            }
            output.write(headerText.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            response.writeBody(output)
            output.flush()
        }

        private companion object {
            const val MAX_REQUEST_BYTES = 64 * 1024
        }
    }

    private class StateWatcher : (BrowserDownloadItem) -> Unit {
        private val lock = java.lang.Object()
        private val history = mutableMapOf<String, MutableList<BrowserDownloadItem>>()

        override fun invoke(item: BrowserDownloadItem) {
            synchronized(lock) {
                history.getOrPut(item.id) { mutableListOf() }.add(item)
                lock.notifyAll()
            }
        }

        fun await(id: String, state: BrowserDownloadState, timeoutMillis: Long = 5_000L): BrowserDownloadItem {
            val deadline = System.currentTimeMillis() + timeoutMillis
            synchronized(lock) {
                while (true) {
                    history[id]?.lastOrNull { it.state == state }?.let { return it }
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        throw AssertionError("timed out waiting for $id in $state; history=${history[id]}")
                    }
                    lock.wait(remaining)
                }
            }
        }

        fun awaitTerminal(id: String, timeoutMillis: Long = 5_000L): BrowserDownloadItem {
            val deadline = System.currentTimeMillis() + timeoutMillis
            val terminal = setOf(
                BrowserDownloadState.COMPLETED,
                BrowserDownloadState.FAILED,
                BrowserDownloadState.CANCELLED,
            )
            synchronized(lock) {
                while (true) {
                    history[id]?.lastOrNull { it.state in terminal }?.let { return it }
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        throw AssertionError("timed out waiting for terminal $id; history=${history[id]}")
                    }
                    lock.wait(remaining)
                }
            }
        }
    }

    private class FakeHttpConnection(
        url: URL,
        private val status: Int = 200,
        private val headers: Map<String, String> = emptyMap(),
        private val body: ByteArray = ByteArray(0),
        private val contentType: String = "application/octet-stream",
        private val gate: CountDownLatch? = null,
    ) : HttpURLConnection(url) {
        val requestHeaders = ConcurrentHashMap<String, String>()
        val opened = CountDownLatch(1)
        val bodyEntered = CountDownLatch(1)
        private val disconnectLock = java.lang.Object()
        private var disconnectCount = 0

        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null && value != null) requestHeaders[key] = value
        }

        override fun getResponseCode(): Int {
            opened.countDown()
            return status
        }

        override fun getHeaderField(name: String?): String? {
            if (name == null) return null
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        override fun getContentType(): String = contentType

        override fun getInputStream(): InputStream {
            bodyEntered.countDown()
            if (gate != null && !gate.await(5, TimeUnit.SECONDS)) throw IOException("gate timeout")
            return ByteArrayInputStream(body)
        }

        override fun connect() = Unit

        override fun disconnect() {
            gate?.countDown()
            synchronized(disconnectLock) {
                disconnectCount++
                disconnectLock.notifyAll()
            }
        }

        override fun usingProxy(): Boolean = false

        fun awaitDisconnects(expected: Int, timeoutMillis: Long = 5_000L) {
            val deadline = System.currentTimeMillis() + timeoutMillis
            synchronized(disconnectLock) {
                while (disconnectCount < expected) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0L) {
                        throw AssertionError("timed out at $disconnectCount disconnects; expected $expected")
                    }
                    disconnectLock.wait(remaining)
                }
            }
        }
    }
}
