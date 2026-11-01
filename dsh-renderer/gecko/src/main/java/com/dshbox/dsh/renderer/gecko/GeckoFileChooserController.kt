package com.dshbox.dsh.renderer.gecko

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.SystemClock
import android.provider.OpenableColumns
import com.dshbox.dsh.renderer.api.DshAsyncRequestId
import com.dshbox.dsh.renderer.api.DshFileChooserRequest
import com.dshbox.dsh.renderer.api.DshFileStaging
import com.dshbox.dsh.renderer.api.DshRendererEvent
import com.dshbox.dsh.renderer.api.DshSelectedFile
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class GeckoFileChooserController(
    private val context: Context,
    private val mainHandler: Handler,
    private val executor: ExecutorService,
    sessionId: String,
    private val isActive: () -> Boolean,
    private val emit: (DshRendererEvent) -> Boolean,
    private val nextRequestId: () -> DshAsyncRequestId,
) : GeckoSession.PromptDelegate {
    private val stagingDirectory = File(
        context.cacheDir,
        "dsh-gecko-upload-${sessionId.replace("-", "")}",
    )
    private val ownedFiles = ConcurrentHashMap.newKeySet<File>()

    private var current: PendingFilePrompt? = null

    private val promptLifecycle =
        object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(
                prompt: GeckoSession.PromptDelegate.BasePrompt,
            ) {
                requireMainThread("GeckoFileChooserController.onPromptDismiss")
                if (current?.prompt === prompt) cancelPending()
            }
        }

    override fun onFilePrompt(
        session: GeckoSession,
        prompt: GeckoSession.PromptDelegate.FilePrompt,
    ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        requireMainThread("GeckoFileChooserController.onFilePrompt")
        if (!isActive() ||
            (prompt.type != GeckoSession.PromptDelegate.FilePrompt.Type.SINGLE &&
                prompt.type != GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE)
        ) {
            return GeckoResult.fromValue(prompt.dismiss())
        }

        cancelPending()
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val pending = PendingFilePrompt(
            id = nextRequestId(),
            prompt = prompt,
            result = result,
            multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE,
        )
        current = pending
        prompt.setDelegate(promptLifecycle)

        val request = DshFileChooserRequest(
            requestId = pending.id,
            multiple = pending.multiple,
            acceptMimeTypes = prompt.mimeTypes.orEmpty().toList(),
            captureEnabled = prompt.capture != GeckoSession.PromptDelegate.FilePrompt.Capture.NONE,
        )
        if (!emit(DshRendererEvent.FileChooserRequested(request))) {
            cancelPending()
        }
        return result
    }

    fun complete(
        requestId: DshAsyncRequestId,
        files: List<DshSelectedFile>?,
    ): Boolean {
        requireMainThread("GeckoFileChooserController.complete")
        val pending = current?.takeIf {
            it.id == requestId && it.acceptingCompletion
        } ?: return false

        pending.acceptingCompletion = false
        if (files.isNullOrEmpty()) {
            cancelPending()
            return true
        }
        if (!pending.multiple && files.size != 1) {
            cancelPending()
            return true
        }

        val selected = files.toList()
        if (selected.any(DshSelectedFile::requiresExternalPreparation)) {
            pending.preparationActive.set(true)
            emit(
                DshRendererEvent.FilePreparationProgress(
                    requestId = pending.id,
                    bytesPrepared = 0,
                    active = true,
                ),
            )
        }
        try {
            pending.stagingFuture = executor.submit {
                stageAndComplete(pending, selected)
            }
        } catch (_: RejectedExecutionException) {
            cancelPending()
        }
        return true
    }

    fun cancel(requestId: DshAsyncRequestId): Boolean {
        requireMainThread("GeckoFileChooserController.cancel")
        if (current?.id != requestId) return false
        cancelPending()
        return true
    }

    fun cancelPending() {
        requireMainThread("GeckoFileChooserController.cancelPending")
        val pending = current ?: return
        current = null
        pending.cancelled.set(true)
        try {
            runCatching { pending.cancellationSignal.getAndSet(null)?.cancel() }
            runCatching { pending.stagingFuture?.cancel(true) }
            pending.stagingFuture = null
            runCatching { pending.prompt.setDelegate(null) }
            cleanupFiles(pending.stagedFiles)
        } finally {
            settleCancelled(pending)
        }
    }

    fun release() {
        requireMainThread("GeckoFileChooserController.release")
        cancelPending()
        cleanupFiles(ownedFiles.toList())
        ownedFiles.clear()
        stagingDirectory.deleteRecursively()
    }

    private fun stageAndComplete(
        pending: PendingFilePrompt,
        selected: List<DshSelectedFile>,
    ) {
        val stagedForRequest = mutableListOf<File>()
        var preparedExternalBytes = 0L
        var lastProgressPost = 0L
        try {
            val uris = ArrayList<Uri>(selected.size)
            for (file in selected) {
                checkCancelled(pending)

                val localFile = file.localFile
                if (localFile != null) {
                    uris += Uri.fromFile(localFile)
                    continue
                }

                val uri = file.uri
                if (uri.scheme?.equals("file", ignoreCase = true) == true) {
                    uris += uri
                    continue
                }

                var displayName: String? = null
                var mimeType: String? = null
                val staged = withCancellationSignal(pending) { signal ->
                    checkCancelled(pending)
                    displayName = resolveDisplayName(file, signal)
                    mimeType = resolveMimeType(file)
                    checkCancelled(pending)
                    DshFileStaging.copy(
                        directory = stagingDirectory,
                        maxBytes = UNLIMITED_STAGING_BYTES,
                        reserveBytes = STAGING_RESERVE_BYTES,
                        openSource = { openExternalSource(pending, uri, signal) },
                        isCancelled = { pending.cancelled.get() || !isActive() },
                        onProgress = { fileBytes ->
                            val totalBytes = preparedExternalBytes + fileBytes
                            pending.bytesPrepared.set(totalBytes)
                            val now = SystemClock.elapsedRealtime()
                            if (lastProgressPost == 0L ||
                                now - lastProgressPost >= PROGRESS_INTERVAL_MILLIS
                            ) {
                                lastProgressPost = now
                                postPreparationProgress(pending, totalBytes)
                            }
                        },
                        availableBytes = { stagingDirectory.usableSpace },
                    )
                }
                stagedForRequest += staged
                pending.stagedFiles += staged
                checkCancelled(pending)

                val destination = reserveDestination(displayName, mimeType)
                try {
                    if (!staged.renameTo(destination)) {
                        throw IOException("Cannot finalize staged upload")
                    }
                } catch (failure: Throwable) {
                    ownedFiles.remove(destination)
                    destination.delete()
                    staged.delete()
                    throw failure
                }

                stagedForRequest.remove(staged)
                stagedForRequest += destination
                pending.stagedFiles.remove(staged)
                pending.stagedFiles += destination
                preparedExternalBytes += destination.length()
                pending.bytesPrepared.set(preparedExternalBytes)
                uris += Uri.fromFile(destination)
            }

            mainHandler.post {
                if (pending.cancelled.get() || !isActive()) {
                    cleanupFiles(stagedForRequest)
                    settleCancelled(pending)
                    return@post
                }
                completePrompt(pending, uris.toTypedArray())
            }
        } catch (failure: Throwable) {
            cleanupFiles(stagedForRequest)
            mainHandler.post {
                if (current === pending && !pending.cancelled.get()) {
                    emit(DshRendererEvent.FileDeliveryFailed(pending.id))
                    cancelPending()
                } else {
                    cleanupFiles(pending.stagedFiles)
                    settleCancelled(pending)
                }
            }
        }
    }

    private fun completePrompt(pending: PendingFilePrompt, uris: Array<Uri>) {
        requireMainThread("GeckoFileChooserController.completePrompt")
        pending.stagingFuture = null
        if (pending.cancelled.get() || current !== pending) {
            cleanupFiles(pending.stagedFiles)
            settleCancelled(pending)
            return
        }
        current = null
        pending.prompt.setDelegate(null)
        val response = runCatching {
            pending.prompt.confirm(context, uris)
        }.getOrNull()
        if (response != null) {
            completeOnce(pending, response)
        } else {
            cleanupFiles(pending.stagedFiles)
            emit(DshRendererEvent.FileDeliveryFailed(pending.id))
            failOnce(pending)
        }
    }

    private fun <T> withCancellationSignal(
        pending: PendingFilePrompt,
        block: (CancellationSignal) -> T,
    ): T {
        checkCancelled(pending)
        val signal = CancellationSignal()
        pending.cancellationSignal.set(signal)
        if (pending.cancelled.get() || !isActive()) {
            pending.cancellationSignal.compareAndSet(signal, null)
            runCatching { signal.cancel() }
            throw CancellationException("Upload staging cancelled")
        }
        try {
            return block(signal)
        } finally {
            pending.cancellationSignal.compareAndSet(signal, null)
        }
    }

    private fun openExternalSource(
        pending: PendingFilePrompt,
        uri: Uri,
        signal: CancellationSignal,
    ): java.io.InputStream {
        checkCancelled(pending)
        val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", signal)
            ?: throw IOException("Cannot open selected upload source")
        return try {
            descriptor.createInputStream()
        } catch (failure: Throwable) {
            runCatching { descriptor.close() }
            throw failure
        }
    }

    private fun postPreparationProgress(
        pending: PendingFilePrompt,
        bytesPrepared: Long,
    ) {
        mainHandler.post {
            if (!pending.preparationActive.get() ||
                pending.cancelled.get() ||
                current !== pending
            ) {
                return@post
            }
            emit(
                DshRendererEvent.FilePreparationProgress(
                    requestId = pending.id,
                    bytesPrepared = bytesPrepared,
                    active = true,
                ),
            )
        }
    }

    private fun finishPreparationProgress(pending: PendingFilePrompt) {
        requireMainThread("GeckoFileChooserController.finishPreparationProgress")
        if (!pending.preparationActive.compareAndSet(true, false)) return
        emit(
            DshRendererEvent.FilePreparationProgress(
                requestId = pending.id,
                bytesPrepared = pending.bytesPrepared.get(),
                active = false,
            ),
        )
    }

    private fun reserveDestination(displayName: String?, mimeType: String?): File {
        if (!stagingDirectory.isDirectory && !stagingDirectory.mkdirs()) {
            throw IOException("Cannot create upload staging directory")
        }
        val sanitized = sanitizeDisplayName(displayName, mimeType)
        val dot = sanitized.lastIndexOf('.')
        val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
        val extension = if (dot > 0) sanitized.substring(dot) else ""

        var attempt = 0
        while (true) {
            val name = if (attempt == 0) {
                sanitized
            } else {
                "$base ($attempt)$extension"
            }
            val candidate = File(stagingDirectory, name)
            if (ownedFiles.add(candidate)) {
                if (!candidate.exists()) return candidate
                ownedFiles.remove(candidate)
            }
            attempt += 1
        }
    }

    private fun resolveDisplayName(
        file: DshSelectedFile,
        signal: CancellationSignal,
    ): String? {
        file.displayName?.takeIf { it.isNotBlank() }?.let { return it }
        if (file.uri.scheme?.equals("content", ignoreCase = true) == true) {
            runCatching {
                context.contentResolver.query(
                    file.uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                    signal,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0) cursor.getString(column) else null
                    } else {
                        null
                    }
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return file.uri.lastPathSegment?.let { Uri.decode(it) }
    }

    private fun resolveMimeType(file: DshSelectedFile): String? {
        file.mimeType?.takeIf { it.isNotBlank() }?.let { return it }
        return runCatching { context.contentResolver.getType(file.uri) }.getOrNull()
    }

    private fun checkCancelled(pending: PendingFilePrompt) {
        if (pending.cancelled.get() || !isActive()) {
            throw CancellationException("Upload staging cancelled")
        }
    }

    private fun settleCancelled(pending: PendingFilePrompt) {
        if (pending.settled) return
        pending.settled = true
        finishPreparationProgress(pending)
        val response = if (!pending.prompt.isComplete) {
            runCatching { pending.prompt.dismiss() }.getOrNull()
        } else {
            null
        }
        if (response != null) {
            pending.result.complete(response)
        } else {
            pending.result.completeExceptionally(CancellationException("File prompt cancelled"))
        }
    }

    private fun completeOnce(
        pending: PendingFilePrompt,
        response: GeckoSession.PromptDelegate.PromptResponse,
    ) {
        if (pending.settled) return
        pending.settled = true
        finishPreparationProgress(pending)
        pending.result.complete(response)
    }

    private fun failOnce(pending: PendingFilePrompt) {
        if (pending.settled) return
        pending.settled = true
        finishPreparationProgress(pending)
        pending.result.completeExceptionally(IOException("Cannot stage selected upload"))
    }

    private fun cleanupFiles(files: Collection<File>) {
        for (file in files) {
            ownedFiles.remove(file)
            file.delete()
        }
    }

    private class PendingFilePrompt(
        val id: DshAsyncRequestId,
        val prompt: GeckoSession.PromptDelegate.FilePrompt,
        val result: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>,
        val multiple: Boolean,
    ) {
        var acceptingCompletion = true
        var settled = false
        var stagingFuture: Future<*>? = null
        val cancelled = AtomicBoolean(false)
        val preparationActive = AtomicBoolean(false)
        val bytesPrepared = AtomicLong(0)
        val cancellationSignal = AtomicReference<CancellationSignal?>(null)
        val stagedFiles: MutableSet<File> = ConcurrentHashMap.newKeySet()
    }
}

private fun DshSelectedFile.requiresExternalPreparation(): Boolean =
    localFile == null && !uri.scheme.equals("file", ignoreCase = true)

private fun sanitizeDisplayName(displayName: String?, mimeType: String?): String {
    val leaf = displayName
        ?.replace('\\', '/')
        ?.substringAfterLast('/')
        ?.replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        ?.trim()
        ?.trim('.', ' ')
        ?.takeIf { it.isNotEmpty() && it != "." && it != ".." }
        ?: "upload"

    val dot = leaf.lastIndexOf('.')
    val rawBase = if (dot > 0) leaf.substring(0, dot) else leaf
    val rawExtension = if (dot > 0) leaf.substring(dot + 1) else ""
    val extension = rawExtension
        .takeIf { it.length in 1..16 && it.all { character -> character.isLetterOrDigit() } }
        ?: extensionForMime(mimeType)
    val base = rawBase
        .trim('.', ' ')
        .take(96)
        .ifBlank { "upload" }
    return if (extension.isNullOrEmpty()) base else "$base.$extension"
}

private fun extensionForMime(mimeType: String?): String? = when (
    mimeType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
) {
    "application/pdf" -> "pdf"
    "application/json" -> "json"
    "application/zip" -> "zip"
    "application/octet-stream" -> "bin"
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "text/csv" -> "csv"
    "text/html" -> "html"
    "text/plain" -> "txt"
    "video/mp4" -> "mp4"
    "audio/mpeg" -> "mp3"
    else -> null
}

private const val UNLIMITED_STAGING_BYTES = Long.MAX_VALUE
private const val STAGING_RESERVE_BYTES = 32L * 1024 * 1024
private const val PROGRESS_INTERVAL_MILLIS = 100L
