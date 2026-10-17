package com.dshbox.browser.bridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.ActivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.dshbox.browser.api.BrowserContract
import com.dshbox.browser.api.BrowserUiSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

private const val MAX_STATUS_IDLE_REMAINING_MILLIS = 960_000L

/** Main-process bridge between the PRoot CLI and the app-owned Browser command service. */
class BrowserCliBroker(
    context: Context,
    val entryDir: File = File(context.applicationContext.filesDir, "browser-tools"),
) : AutoCloseable {
    private val appContext = context.applicationContext
    init {
        val expected = File(appContext.filesDir, "browser-tools").absoluteFile.toPath().normalize()
        val actual = entryDir.absoluteFile.toPath().normalize()
        require(actual == expected) { "Browser CLI assets must stay in the app-owned browser-tools directory" }
    }
    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val latestStatus = AtomicReference(defaultClosedStatus())
    private val liveStatus = AtomicReference<JSONObject?>(null)
    private val activeRequests = BrowserCliActiveRequestRegistry<BrowserCliActiveRequest>()
    private val earlyCancellations = BrowserCliEarlyCancellation(android.os.SystemClock::elapsedRealtime)
    private val acceptedPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<LocalSocket>()
    private val scheduler = BrowserCliRunScheduler()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private var socketName: String? = null
    private var channelToken: String? = null
    @Volatile private var lifecycleGeneration: Long = 0L
    @Volatile private var permanentlyClosed: Boolean = false
    private var receiverRegistered = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BrowserContract.ACTION_STATUS) return
            intent.getStringExtra(BrowserContract.EXTRA_STATUS_JSON)
                ?.let(::publishStatusJson)
        }
    }

    /** Starts the private abstract socket and publishes its per-lifecycle guest rendezvous file. */
    fun start() = synchronized(lock) {
        check(!permanentlyClosed) { "Browser CLI broker is closed" }
        if (running.get()) return@synchronized
        liveStatus.set(null)
        prepareAssets()
        val nextName = "dshbox.browser.${UUID.randomUUID().toString().replace("-", "")}".take(96)
        val nextToken = ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val nextServer = LocalServerSocket(nextName)
        try {
            publishChannelFile(nextName, nextToken)
            val filter = IntentFilter(BrowserContract.ACTION_STATUS)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                appContext.registerReceiver(
                    statusReceiver, filter, BrowserContract.internalPermission(appContext.packageName),
                    mainHandler, Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(
                    statusReceiver, filter, BrowserContract.internalPermission(appContext.packageName), mainHandler,
                )
            }
            receiverRegistered = true
            socketName = nextName
            channelToken = nextToken
            lifecycleGeneration += 1L
            server = nextServer
            latestStatus.set(readStatusSnapshot())
            running.set(true)
            android.util.Log.i("BrowserTools", "CLI listener ready generation=$lifecycleGeneration")
            acceptThread = Thread({ acceptLoop(nextServer, nextToken) }, "browser-cli-accept").apply {
                isDaemon = true
                start()
            }
            appContext.sendBroadcast(
                Intent(BrowserContract.ACTION_QUERY_STATUS)
                    .setPackage(appContext.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY),
                BrowserContract.internalPermission(appContext.packageName),
            )
        } catch (error: Throwable) {
            runCatching { nextServer.close() }
            if (receiverRegistered) runCatching { appContext.unregisterReceiver(statusReceiver) }
            receiverRegistered = false
            File(entryDir, "run/channel.json").delete()
            throw error
        }
    }

    /** Publishes only the CLI assets and managed directories; it creates no socket or receiver. */
    fun prepareAssets() = synchronized(lock) { materializeAssets() }

    /** Stops accepting commands and invalidates the channel token without deleting browser artifacts. */
    fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        if (!running.getAndSet(false)) {
            // A prior process may have died without deleting its rendezvous file.
            // The lock excludes start(), so removing it cannot revoke a live run.
            File(entryDir, "run/channel.json").delete()
            return
        }
        val stoppedIdentity = BrowserCliRunIdentity(lifecycleGeneration, channelToken.orEmpty())
        lifecycleGeneration += 1L
        android.util.Log.i("BrowserTools", "CLI listener stopped generation=$lifecycleGeneration")
        channelToken = null
        acceptedPeers.toList().forEach { runCatching { it.close() } }
        acceptedPeers.clear()
        transferredPeers.clear()
        scheduler.cancelQueuedCommands(stoppedIdentity)
        activeRequests.finishRun(stoppedIdentity) { requestId, active ->
            active.cancelForRunStop(requestId, ::unknownResponse, ::cancelledResponse)
        }
        earlyCancellations.finishRun(stoppedIdentity)
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        socketName = null
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(statusReceiver) }
        receiverRegistered = false
        File(entryDir, "run/channel.json").delete()
        latestStatus.set(defaultClosedStatus())
        liveStatus.set(null)
    }

    /** Accepts only the runtime's bounded status broadcast; no service bind is performed. */
    fun publishStatusJson(serialized: String) {
        parseStatus(serialized)?.let { incoming ->
            val selected = liveStatus.updateAndGet { current -> selectBrowserStatus(incoming, current ?: incoming) }
            selected?.let(latestStatus::set)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (permanentlyClosed) return
            stopLocked()
            permanentlyClosed = true
            scheduler.close()
        }
    }

    private fun acceptLoop(localServer: LocalServerSocket, expectedToken: String) {
        while (running.get()) {
            val peer = try {
                localServer.accept().apply { soTimeout = 3_000 }
            } catch (_: IOException) {
                if (!running.get()) return
                continue
            }
            val identity = synchronized(lock) {
                if (!running.get() || channelToken != expectedToken) null
                else BrowserCliRunIdentity(lifecycleGeneration, expectedToken).also { acceptedPeers.add(peer) }
            }
            if (identity == null) {
                runCatching { peer.close() }
                continue
            }
            try {
                if (!scheduler.executeIntake { handlePeer(peer, identity) }) {
                    acceptedPeers.remove(peer)
                    runCatching { peer.close() }
                }
            } catch (_: RuntimeException) {
                acceptedPeers.remove(peer)
                runCatching { peer.close() }
            }
        }
    }

    private fun handlePeer(peer: LocalSocket, identity: BrowserCliRunIdentity) {
        val socket = peer
        try {
            try {
                val uid = runCatching { socket.peerCredentials.uid }.getOrNull()
                if (uid != Process.myUid() || !isCurrentRun(identity)) return
                val line = readLineBounded(socket) ?: return
                val frame = runCatching { JSONObject(line) }.getOrNull() ?: return
                if (!constantTimeEquals(frame.opt("token") as? String ?: return, identity.token)) return
                if (!isCurrentRun(identity) || safeInteger(frame.opt("version")) != 1L) return
                val op = frame.opt("op") as? String ?: return
                val allowedFrameKeys = if (op == "request") {
                    setOf("version", "token", "op", "request")
                } else if (op == "cancel") {
                    setOf("version", "token", "op", "requestId")
                } else return
                if (frame.keys().asSequence().any { it !in allowedFrameKeys }) return
                if (!isCurrentRun(identity)) return
                val requestId = if (op == "request") frame.optJSONObject("request")?.opt("requestId") as? String
                    else frame.opt("requestId") as? String
                if (requestId == null || !REQUEST_ID.matches(requestId)) return
                val request = if (op == "request") frame.optJSONObject("request") ?: return else null
                val validation = request?.let { BrowserRequestValidator.validate(it, System.currentTimeMillis()) }
                if (validation != null && !validation.valid) {
                    val rejected = response(requestId, "INVALID_REQUEST", "REJECTED", false)
                    rejected.put("data", JSONObject()
                        .put("cancelStage", "admission")
                        .put("reason", "invalid_request")
                        .put("validationStage", validation.stage))
                    writePeerResponse(socket, rejected.toString())
                    return
                }
                if (request != null && synchronized(lock) {
                        isCurrentRunLocked(identity) && earlyCancellations.contains(BrowserCliRequestKey(identity, requestId))
                    }) {
                    writePeerResponse(socket, cancelledResponse(requestId))
                    return
                }
                if (request?.optString("method") == "status" && !request.has("taskId")) {
                    writePeerResponse(socket, cachedStatusResponse(requestId).toString())
                    return
                }
                val cancellationTarget = if (op == "cancel") {
                    var earlyAccepted = false
                    val cancellation = synchronized(lock) {
                        if (!isCurrentRunLocked(identity)) return
                        val key = BrowserCliRequestKey(identity, requestId)
                        val target = activeRequests.get(key)
                        if (target == null) { earlyAccepted = earlyCancellations.remember(key); null }
                        else {
                            var disposition: BrowserCliCancelDisposition? = null
                            earlyCancellations.rememberWhen(key) {
                                disposition = target.requestCancel()
                                disposition != BrowserCliCancelDisposition.FINISHED
                            }
                            disposition?.let { target to it }
                        }
                    }
                    if (cancellation == null) {
                        writePeerResponse(socket, if (earlyAccepted) {
                            cancelReceiptResponse(requestId, "queued", "cancel_before_admission")
                        } else {
                            cancelReceiptResponse(requestId, "transport", "transport_unavailable", "BUSY")
                        })
                        return
                    }
                    val (target, disposition) = cancellation
                    when (disposition) {
                        BrowserCliCancelDisposition.CANCELLED_BEFORE_DISPATCH -> {
                            target.result.complete(cancelledResponse(requestId))
                            activeRequests.remove(BrowserCliRequestKey(identity, requestId), target)
                            target.finishQueuedCancellation()
                            writePeerResponse(socket, cancelReceiptResponse(
                                requestId, "queued", "cancel_before_admission",
                            ))
                            return
                        }
                        BrowserCliCancelDisposition.RUNNING -> target
                        BrowserCliCancelDisposition.ALREADY_HANDLED -> {
                            writePeerResponse(socket, cancelReceiptResponse(
                                requestId, "dispatch", "cancel_accepted",
                            ))
                            return
                        }
                        BrowserCliCancelDisposition.FINISHED -> {
                            writePeerResponse(socket, cancelReceiptResponse(
                                requestId, "dispatch", "request_finished", "NOT_FOUND",
                            ))
                            return
                        }
                    }
                } else null
                var reserved: BrowserCliActiveRequest? = null
                var duplicateRequest: BrowserCliActiveRequest? = null
                var cancelledBeforeAdmission = false
                val action = Runnable {
                    try {
                        val response = when (op) {
                            "request" -> {
                                val candidate = reserved
                                if (candidate == null) {
                                    val existing = duplicateRequest
                                    if (existing == null) response(requestId, "BUSY", "REJECTED", false).toString()
                                    else if (existing.fingerprint != request!!.toString()) {
                                        response(requestId, "REQUEST_ID_CONFLICT", null, false).toString()
                                    } else awaitDuplicate(existing, request)
                                } else executeAdmittedRequest(request!!, identity, candidate)
                            }
                        else -> dispatchCancel(requestId, identity) ?: cancelReceiptResponse(
                            requestId, "transport", "transport_unavailable", "UNKNOWN",
                        )
                        }
                        if (response != null && isCurrentRun(identity)) writePeerResponse(socket, response)
                    } finally {
                        transferredPeers.remove(socket)
                        acceptedPeers.remove(socket)
                        runCatching { socket.close() }
                    }
                }
                val admitted = synchronized(lock) {
                    if (!isCurrentRunLocked(identity)) return
                    transferredPeers.add(socket)
                    if (op == "cancel") {
                        scheduler.executeCancellation(identity, action)
                    } else {
                        val key = BrowserCliRequestKey(identity, requestId)
                        if (earlyCancellations.contains(key)) {
                            cancelledBeforeAdmission = true
                            false
                        } else {
                        val candidate = BrowserCliActiveRequest(
                            identity, request!!.toString(), java.util.concurrent.CompletableFuture(),
                        )
                        candidate.queuedCancelAction.set {
                            activeRequests.remove(key, candidate)
                            candidate.result.complete(cancelledResponse(requestId))
                            writePeerResponse(socket, cancelledResponse(requestId))
                            transferredPeers.remove(socket)
                            acceptedPeers.remove(socket)
                            runCatching { socket.close() }
                        }
                        val previous = activeRequests.register(key, candidate)
                        if (previous == null) {
                            reserved = candidate
                            if (candidate.schedule(scheduler, action)) true
                            else {
                                activeRequests.remove(key, candidate)
                                false
                            }
                        } else {
                            reserved = null
                            duplicateRequest = previous
                            scheduler.executeCommand(identity, action)
                        }
                        }
                    }
                }
                if (!admitted) {
                    transferredPeers.remove(socket)
                    cancellationTarget?.cancelDispatchRejected()
                    writePeerResponse(socket, if (cancelledBeforeAdmission) cancelledResponse(requestId)
                        else response(requestId, "BUSY", "REJECTED", false).toString())
                    acceptedPeers.remove(socket)
                    runCatching { socket.close() }
                }
            } finally {
                if (!isPeerTransferred(socket)) acceptedPeers.remove(socket)
            }
        } finally {
            if (!isPeerTransferred(socket)) runCatching { socket.close() }
        }
    }

    private val transferredPeers = java.util.concurrent.ConcurrentHashMap.newKeySet<LocalSocket>()

    private fun isPeerTransferred(peer: LocalSocket): Boolean = transferredPeers.contains(peer)

    private fun writePeerResponse(socket: LocalSocket, response: String) {
        val bytes = response.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME_BYTES) return
        runCatching {
            socket.outputStream.write(bytes)
            socket.outputStream.write('\n'.code)
            socket.outputStream.flush()
        }
    }

    private fun executeAdmittedRequest(
        request: JSONObject,
        identity: BrowserCliRunIdentity,
        active: BrowserCliActiveRequest,
    ): String? {
        if (!isCurrentRun(identity)) return null
        if (!active.beginDispatch()) {
            val requestId = request.getString("requestId")
            return if (active.wasCancelledBeforeDispatch()) cancelledResponse(requestId) else unknownResponse(requestId)
        }
        val method = request.optString("method")
        val requestId = request.getString("requestId")
        val key = BrowserCliRequestKey(identity, requestId)
        var result: String? = null
        return try {
            if (!isCurrentRun(identity)) return null
            val status = currentStatusSnapshot()
            val rejection = browserCliAdmissionRejection(request, status)
            if (rejection != null) {
                val rejected = response(requestId, rejection, "REJECTED", false).toString()
                result = rejected
                return rejected
            }
            dispatch(request, identity, active).also { result = it }
        } finally {
            active.result.complete(result ?: unknownResponse(requestId))
            active.finish()
            activeRequests.remove(key, active)
        }
    }

    private fun awaitDuplicate(active: BrowserCliActiveRequest, request: JSONObject): String {
        val requestId = request.getString("requestId")
        val remaining = request.getLong("deadlineAtMillis") - System.currentTimeMillis()
        if (remaining <= 0L) return unknownResponse(requestId)
        return runCatching { active.result.get(remaining, TimeUnit.MILLISECONDS) }
            .getOrNull()
            ?.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_FRAME_BYTES }
            ?: unknownResponse(requestId)
    }

    private fun cachedStatusResponse(requestId: String): JSONObject {
        return browserCachedStatusResponse(requestId, currentStatusSnapshot())
    }

    private fun currentStatusSnapshot(): JSONObject {
        val fileStatus = selectBrowserStatus(liveStatus.get(), readStatusSnapshot())
        val state = fileStatus.optString("state", BrowserContract.STATUS_CLOSED)
        if (state == BrowserContract.STATUS_CLOSED || browserProcessAlive()) {
            latestStatus.set(fileStatus)
            return fileStatus
        }
        // A runtime process can die without delivering a final broadcast. Keep its
        // generation values only as invalidated identifiers, while exposing closed.
        return JSONObject()
            .put("state", BrowserContract.STATUS_CLOSED)
            .put("automationEnabled", fileStatus.optBoolean("automationEnabled", false))
            .put("sessionGeneration", fileStatus.optLong("sessionGeneration", 0L))
            .put("controlGeneration", fileStatus.optLong("controlGeneration", 0L))
            .put("owner", "none")
            .put("visible", false)
            .put("idleRemainingMs", JSONObject.NULL)
            .put("lastCloseReason", "unknown")
            .also(latestStatus::set)
    }

    private fun readStatusSnapshot(): JSONObject {
        val statusFile = File(entryDir, "run/status.json")
        if (java.nio.file.Files.isSymbolicLink(statusFile.toPath()) ||
            !statusFile.isFile || statusFile.length() > MAX_STATUS_BYTES) return defaultClosedStatus()
        return parseStatus(runCatching { statusFile.readText(Charsets.UTF_8) }.getOrNull())
            ?: defaultClosedStatus()
    }

    private fun parseStatus(serialized: String?): JSONObject? {
        if (serialized == null || serialized.toByteArray(Charsets.UTF_8).size > MAX_STATUS_BYTES) return null
        return parseBrowserStatusSnapshot(serialized)
    }

    private fun browserProcessAlive(): Boolean {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val expectedProcessName = "${appContext.packageName}:browser"
        return runCatching {
            manager.runningAppProcesses?.any { it.processName == expectedProcessName } == true
        }.getOrDefault(false)
    }

    private fun dispatch(request: JSONObject, identity: BrowserCliRunIdentity, active: BrowserCliActiveRequest): String? {
        if (!isCurrentRun(identity)) return unknownResponse(request.getString("requestId"))
        val budget = request.getLong("deadlineAtMillis") - System.currentTimeMillis()
        if (budget <= 0L || budget > 60_000L) return unknownResponse(request.getString("requestId"))
        val deadlineElapsed = android.os.SystemClock.elapsedRealtime() + budget
        return dispatchToCommandService(request.getString("requestId"), deadlineElapsed, identity, active) { replyTo ->
            Message.obtain(null, BrowserContract.MESSAGE_EXECUTE).apply {
                this.replyTo = replyTo
                data = Bundle().apply {
                    putString(BrowserContract.KEY_REQUEST_JSON, request.toString())
                    val ui = currentUiSettings()
                    putString(BrowserContract.EXTRA_LOCALE_TAG, ui.localeTag)
                    putString(BrowserContract.EXTRA_THEME_MODE, ui.themeMode)
                }
            }
        }
    }

    private fun currentUiSettings(): BrowserUiSettings {
        val prefs = appContext.getSharedPreferences(BrowserUiSettings.APP_PREFERENCES_NAME, Context.MODE_PRIVATE)
        return BrowserUiSettings(
            localeTag = BrowserUiSettings.normalizeLocaleTag(
                prefs.getString(BrowserUiSettings.APP_LOCALE_KEY, null).orEmpty(),
            ),
            themeMode = BrowserUiSettings.normalizeThemeMode(
                prefs.getString(BrowserUiSettings.APP_THEME_KEY, null),
            ),
        )
    }

    private fun dispatchCancel(requestId: String, identity: BrowserCliRunIdentity): String? =
        dispatchToCommandService(requestId, android.os.SystemClock.elapsedRealtime() + CANCEL_BUDGET_MS, identity, null) { replyTo ->
            Message.obtain(null, BrowserContract.MESSAGE_CANCEL).apply {
                this.replyTo = replyTo
                data = Bundle().apply { putString(BrowserContract.KEY_REQUEST_ID, requestId) }
            }
        }

    private fun dispatchToCommandService(
        requestId: String,
        deadlineElapsedRealtime: Long,
        identity: BrowserCliRunIdentity,
        active: BrowserCliActiveRequest?,
        messageFactory: (Messenger) -> Message,
    ): String? {
        if (!isCurrentRun(identity)) return unknownResponse(requestId)
        val remaining = deadlineElapsedRealtime - android.os.SystemClock.elapsedRealtime()
        if (remaining <= 0L) return unknownResponse(requestId)
        val boundLatch = CountDownLatch(1)
        val result = java.util.concurrent.CompletableFuture<String>()
        val cancelWait = {
            boundLatch.countDown()
            result.complete(null)
            Unit
        }
        if (active != null && !active.installCancelWait(cancelWait)) return unknownResponse(requestId)
        val remote = AtomicReference<Messenger?>()
        val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { incoming ->
            if (incoming.what == BrowserContract.MESSAGE_RESULT) {
                result.complete(incoming.data?.getString(BrowserContract.KEY_RESPONSE_JSON))
            }
            true
        })
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: android.os.IBinder) {
                remote.set(Messenger(service))
                boundLatch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                latestStatus.set(defaultClosedStatus())
                liveStatus.set(null)
                boundLatch.countDown()
                result.complete(null)
            }

            override fun onBindingDied(name: ComponentName) {
                latestStatus.set(defaultClosedStatus())
                liveStatus.set(null)
                boundLatch.countDown()
                result.complete(null)
            }

            override fun onNullBinding(name: ComponentName) {
                boundLatch.countDown()
                result.complete(null)
            }
        }
        val intent = Intent().setComponent(
            ComponentName(appContext.packageName, BrowserContract.COMMAND_SERVICE_CLASS),
        )
        val bindFlags = Context.BIND_AUTO_CREATE or
            if (android.os.Build.VERSION.SDK_INT >= 34) Context.BIND_ALLOW_ACTIVITY_STARTS else 0
        val bound = runCatching {
            appContext.bindService(intent, connection, bindFlags)
        }.getOrDefault(false)
        if (!bound) {
            active?.clearCancelWait(cancelWait)
            return unknownResponse(requestId)
        }
        return try {
            val bindWait = minOf(remaining, BIND_BUDGET_MS)
            if (!boundLatch.await(bindWait, TimeUnit.MILLISECONDS)) return unknownResponse(requestId)
            val messenger = remote.get() ?: return unknownResponse(requestId)
            synchronized(lock) {
                if (!isCurrentRunLocked(identity)) return unknownResponse(requestId)
                messenger.send(messageFactory(replyMessenger))
            }
            val waitMs = maxOf(1L, deadlineElapsedRealtime - android.os.SystemClock.elapsedRealtime())
            val reply = runCatching { result.get(waitMs, TimeUnit.MILLISECONDS) }.getOrNull()
            if (reply == null || reply.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) {
                return unknownResponse(requestId)
            }
            val parsed = runCatching { JSONObject(reply) }.getOrNull() ?: return unknownResponse(requestId)
            if (parsed.opt("requestId") != requestId) return unknownResponse(requestId)
            reply
        } catch (_: Throwable) {
            unknownResponse(requestId)
        } finally {
            active?.clearCancelWait(cancelWait)
            runCatching { appContext.unbindService(connection) }
        }
    }

    private fun isCurrentRun(identity: BrowserCliRunIdentity): Boolean = synchronized(lock) {
        isCurrentRunLocked(identity)
    }

    private fun isCurrentRunLocked(identity: BrowserCliRunIdentity): Boolean =
        running.get() && lifecycleGeneration == identity.generation && channelToken == identity.token

    private fun materializeAssets() {
        val libDir = ensureManagedDirectory("lib")
        val binDir = ensureManagedDirectory("bin")
        ensureManagedDirectory("run")
        ensureManagedDirectory("out")
        ensureManagedDirectory("out/shot")
        ensureManagedDirectory("out/selection")
        ensureManagedDirectory("out/downloads")
        publishAsset("browser/bin/browser", File(binDir, "browser"), executable = true)
        publishAsset("browser/lib/browser-cli.cjs", File(libDir, "browser-cli.cjs"), executable = false)
        publishAsset("browser/lib/browser-serve.cjs", File(libDir, "browser-serve.cjs"), executable = false)
    }

    private fun ensureManagedDirectory(relative: String): File {
        val base = entryDir.absoluteFile.toPath().normalize()
        val target = File(entryDir, relative).absoluteFile.toPath().normalize()
        require(target.startsWith(base)) { "browser entry path escapes its managed directory" }
        var current = base
        if (java.nio.file.Files.isSymbolicLink(current)) error("browser entry path contains a symbolic link")
        if (!java.nio.file.Files.exists(current)) java.nio.file.Files.createDirectory(current)
        for (segment in base.relativize(target)) {
            current = current.resolve(segment)
            if (java.nio.file.Files.isSymbolicLink(current)) error("browser entry path contains a symbolic link")
            if (!java.nio.file.Files.exists(current)) java.nio.file.Files.createDirectory(current)
            check(java.nio.file.Files.isDirectory(current)) { "browser entry path is not a directory" }
        }
        return target.toFile()
    }

    private fun publishAsset(assetPath: String, target: File, executable: Boolean) {
        val parent = target.parentFile ?: error("asset target has no parent")
        val bytes = appContext.assets.open(assetPath).use { it.readBytes() }
        val temporary = File.createTempFile(".browser-asset-", ".tmp", parent)
        try {
            temporary.writeBytes(bytes)
            if (executable) check(temporary.setExecutable(true, false)) { "could not mark Browser CLI executable" }
            java.nio.file.Files.move(
                temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            temporary.delete()
        }
    }

    private fun publishChannelFile(name: String, token: String) {
        val runDir = File(entryDir, "run").apply { mkdirs() }
        val channelFile = File(runDir, "channel.json")
        val staging = File.createTempFile(".channel-", ".tmp", runDir)
        try {
            staging.writeText(JSONObject()
                .put("version", 1)
                .put("socketName", name)
                .put("token", token)
                .toString())
            java.nio.file.Files.move(
                staging.toPath(), channelFile.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            staging.delete()
        }
    }

    private fun readLineBounded(socket: LocalSocket): String? =
        readBoundedLine(socket.inputStream, MAX_REQUEST_FRAME_BYTES)

    private fun response(
        requestId: String,
        code: String,
        state: String?,
        executed: Boolean?,
        status: JSONObject = currentStatusSnapshot(),
    ): JSONObject {
        val allowedStates = setOf("CLOSED", "IDLE", "RUNNING")
        val currentState = status.optString("state", BrowserContract.STATUS_CLOSED)
            .uppercase(java.util.Locale.ROOT)
            .takeIf { it in allowedStates } ?: "CLOSED"
        val responseState = state?.uppercase(java.util.Locale.ROOT)
            ?.takeIf { it in allowedStates } ?: currentState
        val owner = status.opt("owner") as? String
        return JSONObject()
            .put("version", 1)
            .put("requestId", requestId.take(64))
            .put("code", code)
            .put("executed", executed ?: JSONObject.NULL)
            .put("sessionGeneration", safeInteger(status.opt("sessionGeneration")) ?: JSONObject.NULL)
            .put("controlGeneration", safeInteger(status.opt("controlGeneration")) ?: JSONObject.NULL)
            .put("state", responseState)
            .put("owner", owner?.takeIf { it in setOf("none", "human", "ai") } ?: JSONObject.NULL)
            .put("data", JSONObject())
    }

    private fun unknownResponse(requestId: String): String =
        response(requestId, "UNKNOWN", null, null).toString()

    private fun cancelledResponse(requestId: String): String =
        response(requestId, "CANCELLED", null, false)
            .put("data", BrowserCliCancellationContract.preDispatchTerminal())
            .toString()

    private fun cancelReceiptResponse(
        requestId: String,
        stage: String,
        reason: String,
        code: String = "CANCELLED",
    ): String = response(requestId, code, null, null)
        .put("data", BrowserCliCancellationContract.receipt(stage, reason))
        .toString()

    private fun defaultClosedStatus(): JSONObject = JSONObject()
        .put("state", BrowserContract.STATUS_CLOSED)
        .put("automationEnabled", false)
        .put("sessionGeneration", 0)
        .put("controlGeneration", 0)
        .put("owner", "none")

    private fun constantTimeEquals(actual: String, expected: String): Boolean =
        MessageDigest.isEqual(actual.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))

    private fun safeInteger(value: Any?): Long? {
        val integer = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        return integer.takeIf { it in 0L..MAX_SAFE_INTEGER }
    }

    companion object {
        internal const val MAX_REQUEST_FRAME_BYTES = 16 * 1024
        private const val MAX_STATUS_BYTES = 16 * 1024
        private const val MAX_FRAME_BYTES = 256 * 1024
        private const val BIND_BUDGET_MS = 2_000L
        private const val CANCEL_BUDGET_MS = 1_500L
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private val REQUEST_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}

internal fun readBoundedLine(input: InputStream, maxBytes: Int): String? {
    if (maxBytes <= 0) return null
    val output = ByteArrayOutputStream()
    var frameBytes = 0
    while (frameBytes <= maxBytes) {
        val next = input.read()
        if (next < 0) return null
        if (next == '\n'.code) return output.toString(Charsets.UTF_8.name())
        frameBytes++
        if (frameBytes > maxBytes) return null
        if (next != '\r'.code) output.write(next)
    }
    return null
}

/** Several command calls must progress concurrently (for example click + event.wait). */
internal fun createBrowserCliWorkerPool(): ThreadPoolExecutor = ThreadPoolExecutor(
    4, 4, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(8),
    { task -> Thread(task, "browser-cli-worker").apply { isDaemon = true } },
    ThreadPoolExecutor.AbortPolicy(),
)

/** Runs after schema validation; approval management never grants webpage automation itself. */
internal fun browserCliAdmissionRejection(request: JSONObject, status: JSONObject): String? {
    val method = request.optString("method")
    val closed = status.optString("state") == BrowserContract.STATUS_CLOSED
    return when {
        browserTaskOfflineAction(request) -> null
        method == "control" && request.optJSONObject("args")?.optString("action") in
            setOf("request", "status", "cancel") -> null
        !status.optBoolean("automationEnabled", false) -> "AUTOMATION_DISABLED"
        method == "open" && closed && request.has("sessionGeneration") -> "STALE_SESSION"
        method == "open" && !closed && !request.has("sessionGeneration") -> "SESSION_REQUIRED"
        method != "open" && closed -> "NO_ACTIVE_SESSION"
        else -> null
    }
}

/** Live state wins within the same identity; a newer persisted generation can supersede it. */
internal fun selectBrowserStatus(live: JSONObject?, persisted: JSONObject): JSONObject {
    if (live == null) return persisted
    val liveSession = live.getLong("sessionGeneration")
    val persistedSession = persisted.getLong("sessionGeneration")
    if (liveSession != persistedSession) return if (liveSession > persistedSession) live else persisted
    return if (live.getLong("controlGeneration") >= persisted.getLong("controlGeneration")) live else persisted
}

/** Parses the status mirror's stable fields and known optional diagnostics. */
internal fun parseBrowserStatusSnapshot(serialized: String?): JSONObject? {
    if (serialized == null || serialized.toByteArray(Charsets.UTF_8).size > 16 * 1024) return null
    val status = runCatching { JSONObject(serialized) }.getOrNull() ?: return null
    val requiredKeys = setOf("state", "automationEnabled", "sessionGeneration", "controlGeneration", "owner")
    val optionalKeys = setOf("visible", "idleRemainingMs", "lastCloseReason")
    val allowedKeys = requiredKeys + optionalKeys
    if (status.keys().asSequence().any { it !in allowedKeys } ||
        requiredKeys.any { !status.has(it) || status.isNull(it) }) return null
    val state = status.opt("state") as? String ?: return null
    if (state !in setOf("closed", "idle", "running")) return null
    val session = statusSafeInteger(status.opt("sessionGeneration")) ?: return null
    val control = statusSafeInteger(status.opt("controlGeneration")) ?: return null
    val automationEnabled = status.opt("automationEnabled") as? Boolean ?: return null
    val owner = status.opt("owner") as? String ?: return null
    if (owner !in setOf("none", "human", "ai")) return null
    if (status.has("visible") && status.opt("visible") !is Boolean) return null
    if (status.has("idleRemainingMs") && !status.isNull("idleRemainingMs")) {
        val remaining = statusSafeInteger(status.opt("idleRemainingMs")) ?: return null
        if (remaining > MAX_STATUS_IDLE_REMAINING_MILLIS) return null
    }
    if (status.has("lastCloseReason") &&
        status.opt("lastCloseReason") !in setOf(
            "idle_timeout", "user_close", "ai_close", "renderer_gone", "activity_destroyed", "unknown",
        )
    ) return null

    return JSONObject()
        .put("state", state)
        .put("automationEnabled", automationEnabled)
        .put("sessionGeneration", session)
        .put("controlGeneration", control)
        .put("owner", owner)
        .apply {
            if (status.has("visible")) put("visible", status.get("visible"))
            if (status.has("idleRemainingMs")) put("idleRemainingMs", status.get("idleRemainingMs"))
            if (status.has("lastCloseReason")) put("lastCloseReason", status.get("lastCloseReason"))
        }
}

internal fun browserCachedStatusResponse(requestId: String, status: JSONObject): JSONObject {
    val data = JSONObject()
        .put("automationEnabled", status.optBoolean("automationEnabled", false))
        .put("owner", status.optString("owner", "none"))
    for (key in listOf("visible", "idleRemainingMs", "lastCloseReason")) {
        if (status.has(key)) data.put(key, status.opt(key))
    }
    val state = status.optString("state", "closed")
    return JSONObject()
        .put("version", 1)
        .put("requestId", requestId.take(64))
        .put("code", "OK")
        .put("executed", false)
        .put("sessionGeneration", status.opt("sessionGeneration"))
        .put("controlGeneration", status.opt("controlGeneration"))
        .put("state", state.uppercase(Locale.ROOT))
        .put("owner", status.optString("owner", "none"))
        .put("data", data)
}

/** Called only after the full request validator; these actions never dispatch a webpage action. */
internal fun browserTaskOfflineAction(request: JSONObject): Boolean =
    request.optString("method") == "task" && request.optJSONObject("args")?.optString("action") in
        setOf("status", "list", "pause", "wait", "fail", "update", "delete")

private fun statusSafeInteger(value: Any?): Long? {
    val integer = when (value) {
        is Int -> value.toLong()
        is Long -> value
        else -> return null
    }
    return integer.takeIf { it in 0L..9_007_199_254_740_991L }
}
