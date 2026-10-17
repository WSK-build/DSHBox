package com.dshbox.browser.runtime

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import org.json.JSONObject
import com.dshbox.browser.api.BrowserArtifactFiles
import java.io.File
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import java.util.UUID
import android.os.SystemClock

/** Stores one explicit, expiring Browser selection for the automation reader. */
internal class BrowserSelectionStore(
    private val filesRoot: File,
    private val directory: File,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val bootIdentity: () -> String? = { readBrowserBootIdentity() },
    private val clearArtifacts: (File) -> Unit = { BrowserArtifactFiles.clearSelections(it) },
    private val writeOutbox: (File, ByteArray, Int) -> Unit = { root, bytes, limit ->
        BrowserArtifactFiles.writeSelectionOutboxAtomically(root, bytes, limit)
    },
) {
    private data class Saved(
        val id: String,
        val sessionGeneration: Long,
        val createdAtElapsedMillis: Long,
        val expiresAtElapsedMillis: Long,
        val data: JSONObject,
        val screenshot: File,
    )

    private var latest: Saved? = null
    private val selections = LinkedHashMap<String, Saved>()
    private var sequence = 0L
    private var lastWallClockMillis = 0L
    private var clockRollback = false
    private var indexDamaged = false

    init { restore() }

    @Synchronized
    fun currentSequence(): Long = sequence

    @Synchronized
    fun recordReceipt(args: JSONObject, sessionGeneration: Long, nowElapsedMillis: Long): JSONObject {
        refreshClock()
        val deliveryCode = args.optString("deliveryCode")
        if (deliveryCode !in setOf("DELIVERED", "DELIVERED_TEXT_ONLY", "UNKNOWN")) return result("INVALID_ARGUMENT", false)
        if (deliveryCode in setOf("DELIVERED", "DELIVERED_TEXT_ONLY") &&
            (!args.optString("requestId").matches(Regex("[a-f0-9]{32}")) ||
                args.opt("imageIncluded") !is Boolean ||
                args.optBoolean("imageIncluded") != (deliveryCode == "DELIVERED"))) {
            return result("INVALID_ARGUMENT", false)
        }
        val saved = selections[args.optString("selectionId")] ?: return result("NOT_FOUND", false)
        if (saved.id != args.optString("selectionId")) return result("STALE_SELECTION", false)
        if (isExpired(saved, nowElapsedMillis)) return result("NOT_FOUND", false)
        if (clockRollback) return result("CLOCK_ROLLBACK", false)
        if (!canAutomate(saved)) return result("BOOT_IDENTITY_UNAVAILABLE", false)
        val target = args.optString("destinationSessionKey")
        if (target.isBlank() || target != saved.data.optString("destinationSessionKey")) return result("SESSION_BINDING_MISMATCH", false)
        if (args.optString("requestId") != saved.data.optString("requestId")) return result("RECEIPT_CONFLICT", false)
        val receipt = JSONObject()
            .put("deliveryCode", args.getString("deliveryCode"))
            .put("requestId", args.optString("requestId"))
        if (args.opt("imageIncluded") is Boolean) receipt.put("imageIncluded", args.optBoolean("imageIncluded"))
        val previous = saved.data.optJSONObject("deliveryReceipt")
        if (previous != null) return if (previous.toString() == receipt.toString())
            result("OK", false, JSONObject().put("receiptRecorded", true)) else result("RECEIPT_CONFLICT", false)
        if (saved.data.optString("deliveryState") != "ADMITTING") return result("NOT_ADMITTING", false)
        val before = JSONObject(saved.data.toString())
        saved.data.put("deliveryReceipt", receipt)
        saved.data.put("deliveryState", if (deliveryCode == "UNKNOWN") "UNKNOWN" else "TERMINAL")
        try { persist() } catch (error: Exception) { restoreObject(saved.data, before); throw error }
        return result("OK", true, JSONObject().put("receiptRecorded", true))
    }

    @Synchronized
    fun claim(args: JSONObject, nowElapsedMillis: Long): JSONObject {
        refreshClock()
        val saved = selections[args.optString("selectionId")] ?: return result("NOT_FOUND", false)
        if (isExpired(saved, nowElapsedMillis)) return result("NOT_FOUND", false)
        if (clockRollback) return result("CLOCK_ROLLBACK", false)
        if (!canAutomate(saved)) return result("BOOT_IDENTITY_UNAVAILABLE", false)
        val target = args.optString("destinationSessionKey")
        val requestId = args.optString("requestId")
        val digest = args.optString("payloadDigest")
        if (!target.matches(Regex("[^\\u0000-\\u001f\\u007f]{1,256}")) ||
            !requestId.matches(Regex("[a-f0-9]{32}")) || !digest.matches(Regex("[a-f0-9]{64}"))) {
            return result("INVALID_ARGUMENT", false)
        }
        val priorState = saved.data.optString("deliveryState", "UNBOUND")
        if (priorState != "UNBOUND") {
            val same = saved.data.optString("destinationSessionKey") == target &&
                saved.data.optString("requestId") == requestId && saved.data.optString("payloadDigest") == digest
            return if (same && priorState == "CLAIMED") result("CLAIMED", false,
                JSONObject(saved.data.toString())) else if (same) result(if (priorState == "ADMITTING") "UNKNOWN" else priorState,
                false, JSONObject(saved.data.toString())) else result("SELECTION_CONFLICT", false)
        }
        if (clockRollback) return result("CLOCK_ROLLBACK", false)
        val before = JSONObject(saved.data.toString())
        saved.data.put("destinationSessionKey", target)
            .put("requestId", requestId)
            .put("payloadDigest", digest)
            .put("deliveryState", "CLAIMED")
        try { persist() } catch (error: Exception) { restoreObject(saved.data, before); throw error }
        return result("CLAIMED", true, JSONObject(saved.data.toString()))
    }

    @Synchronized
    fun admit(args: JSONObject, nowElapsedMillis: Long): JSONObject {
        val admissionClock = wallClock()
        refreshClock(admissionClock)
        val saved = selections[args.optString("selectionId")] ?: return result("NOT_FOUND", false)
        val same = args.optString("destinationSessionKey") == saved.data.optString("destinationSessionKey") &&
            args.optString("requestId") == saved.data.optString("requestId") &&
            args.optString("payloadDigest") == saved.data.optString("payloadDigest")
        if (!same) return result("SESSION_BINDING_MISMATCH", false)
        if (isExpired(saved, nowElapsedMillis)) return result("EXPIRED", false)
        if (clockRollback) return result("CLOCK_ROLLBACK", false)
        if (!canAutomate(saved)) return result("BOOT_IDENTITY_UNAVAILABLE", false)
        if (saved.data.optString("deliveryState") == "ADMITTING") return result("UNKNOWN", false)
        if (saved.data.optString("deliveryState") != "CLAIMED") return result("NOT_CLAIMED", false)
        val before = JSONObject(saved.data.toString())
        saved.data.put("deliveryState", "ADMITTING")
        try { persist(admissionClock) } catch (error: Exception) { restoreObject(saved.data, before); throw error }
        return result("ADMITTING", true)
    }

    @Synchronized
    fun receipt(selectionId: String, sessionGeneration: Long): JSONObject? {
        val saved = selections[selectionId] ?: return null
        if (saved.id != selectionId || saved.sessionGeneration != sessionGeneration) return null
        return saved.data.optJSONObject("deliveryReceipt")?.let { JSONObject(it.toString()) }
    }

    @Synchronized
    fun save(
        sessionGeneration: Long,
        nowElapsedMillis: Long,
        mode: String,
        comment: String,
        pageUrl: String,
        pageTitle: String,
        selection: JSONObject,
        provenance: JSONObject,
        bounds: RectF,
        bitmap: Bitmap,
    ): JSONObject {
        require(sessionGeneration >= 0L)
        require(mode == MODE_ELEMENT || mode == MODE_AREA)
        require(comment.length <= MAX_COMMENT_CHARS)
        require(selection.toString().length <= MAX_SELECTION_JSON_CHARS)
        require(!bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0)
        refreshClock()
        val beforeSelections = LinkedHashMap(selections)
        val beforeLatest = latest
        val beforeSequence = sequence
        var newScreenshot: File? = null
        try {
        val expired = selections.values.filter { isExpired(it, nowElapsedMillis) }
        expired.forEach { saved ->
            selections.remove(saved.id)
            if (latest?.id == saved.id) latest = null
        }
        check(selections.values.count { !it.data.has("deliveryReceipt") } < MAX_RETAINED_SCREENSHOTS) {
            "Browser selection queue is full; wait for delivery or clear saved selections"
        }
        while (selections.size >= MAX_RETAINED_SCREENSHOTS * 2) {
            val acknowledged = selections.values.firstOrNull { it.data.has("deliveryReceipt") }
                ?: error("Browser selection queue is full")
            selections.remove(acknowledged.id)
        }
        check(sequence < 9_007_199_254_740_991L) { "Browser selection sequence exhausted" }
        require(directory.absoluteFile.toPath().normalize() ==
            File(filesRoot, "browser-tools/out/selection").absoluteFile.toPath().normalize())
        val id = UUID.randomUUID().toString()
        val written = BrowserArtifactFiles.writeSelectionAtomically(
            filesRoot, "selection-$id.png", maxBytes = MAX_SCREENSHOT_BYTES,
            protectedNames = selections.values.filter { !it.data.has("deliveryReceipt") }.map { it.screenshot.name }.toSet(),
        ) { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val screenshot = written.file
        newScreenshot = screenshot

        val safePageUrl = safeUrl(pageUrl)
        val safeProvenance = JSONObject(provenance.toString())
            .put("pageUrl", safeUrl(provenance.optString("pageUrl")))
        val data = JSONObject()
            .put("selectionId", id)
            .put("selectionSequence", sequence + 1)
            .put("mode", mode)
            .put("comment", comment.trim())
            .put("pageUrl", safePageUrl)
            .put("pageTitle", pageTitle.take(MAX_TITLE_CHARS))
            .put("selection", JSONObject(selection.toString()))
            .put("provenance", safeProvenance)
            .put("bounds", JSONObject()
                .put("left", bounds.left.toInt().coerceAtLeast(0))
                .put("top", bounds.top.toInt().coerceAtLeast(0))
                .put("right", bounds.right.toInt().coerceAtLeast(0))
                .put("bottom", bounds.bottom.toInt().coerceAtLeast(0)))
            .put("artifactPath", "/opt/browser/out/selection/${screenshot.name}")
            .put("mimeType", "image/png")
            .put("width", bitmap.width)
            .put("height", bitmap.height)
            .put("bytes", written.sizeBytes)
            .put("notice", "AI-readable Browser context; not sent to a chat.")
            .put("createdAtWallMillis", wallClock())
            .put("expiresAtWallMillis", wallClock() + TTL_MILLIS)
            .put("createdAtElapsedMillis", nowElapsedMillis)
            .put("expiresAtElapsedMillis", nowElapsedMillis + TTL_MILLIS)
            .put("bootIdentity", bootIdentity() ?: "")
            .put("deliveryState", "UNBOUND")

        require(data.toString().toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_DATA_BYTES) {
            "selection context exceeds response limit"
        }

        val saved = Saved(
            id = id,
            sessionGeneration = sessionGeneration,
            createdAtElapsedMillis = nowElapsedMillis,
            expiresAtElapsedMillis = nowElapsedMillis + TTL_MILLIS,
            data = data,
            screenshot = screenshot,
        )
        sequence++
        selections[id] = saved
        latest = saved
        persist()
        expired.forEach { runCatching { BrowserArtifactFiles.removeSelection(filesRoot, it.screenshot.name) } }
        beforeSelections.values.filter { old -> old.data.has("deliveryReceipt") && selections[old.id] == null }
            .forEach { runCatching { BrowserArtifactFiles.removeSelection(filesRoot, it.screenshot.name) } }
        // Atomic writer enforces count/byte quotas and keeps recently delivered images readable.
        return JSONObject().put("code", "OK").put("executed", true).put("data", JSONObject(data.toString()))
        } catch (error: Exception) {
            selections.clear()
            selections.putAll(beforeSelections)
            latest = beforeLatest
            sequence = beforeSequence
            newScreenshot?.let { runCatching { BrowserArtifactFiles.removeSelection(filesRoot, it.name) } }
            throw error
        }
    }

    @Synchronized
    fun execute(action: String, sessionGeneration: Long, nowElapsedMillis: Long): JSONObject {
        if (action == ACTION_CLEAR) {
            try {
                clearArtifacts(filesRoot)
            } catch (_: Exception) {
                return result("CLEAR_FAILED", false, JSONObject().put("cleared", false))
            }
            selections.clear()
            latest = null
            return result("OK", false, JSONObject().put("cleared", true))
        }
        if (action != ACTION_LATEST && action != ACTION_CONSUME) {
            return result("INVALID_ARGUMENT", false)
        }
        val current = latest ?: return result("NOT_FOUND", false)
        if (current.sessionGeneration != sessionGeneration) return result("STALE_SESSION", false)
        if (isExpired(current, nowElapsedMillis)) {
            BrowserArtifactFiles.removeSelection(filesRoot, current.screenshot.name)
            selections.remove(current.id)
            latest = null
            return result("NOT_FOUND", false)
        }
        if (!BrowserArtifactFiles.selectionExists(filesRoot, current.screenshot.name)) {
            latest = null
            return result("ARTIFACT_NOT_FOUND", false)
        }
        if (action == ACTION_CONSUME) {
            val beforeSelections = LinkedHashMap(selections)
            val previousLatest = latest
            latest = null
            selections.remove(current.id)
            try {
                persist()
            } catch (_: Exception) {
                selections.clear()
                selections.putAll(beforeSelections)
                latest = previousLatest
                return result("PERSIST_FAILED", false)
            }
        }
        val data = JSONObject(current.data.toString())
            .put("createdAtElapsedMillis", current.createdAtElapsedMillis)
            .put("expiresInMillis", current.expiresAtElapsedMillis - nowElapsedMillis)
        return result("OK", false, data)
    }

    @Synchronized
    fun pending(afterSequence: Long, sessionGeneration: Long, nowElapsedMillis: Long): JSONObject {
        return pending(afterSequence, sessionGeneration, nowElapsedMillis, "")
    }

    @Synchronized
    fun pending(afterSequence: Long, sessionGeneration: Long, nowElapsedMillis: Long, destinationSessionKey: String): JSONObject {
        refreshClock()
        if (clockRollback) return result("CLOCK_ROLLBACK", false)
        val saved = selections.values.firstOrNull {
            !it.data.has("deliveryReceipt") && !isExpired(it, nowElapsedMillis) && canAutomate(it) && when (it.data.optString("deliveryState", "UNBOUND")) {
                "UNBOUND" -> it.data.optLong("selectionSequence") > afterSequence
                "CLAIMED" -> destinationSessionKey.isNotBlank() &&
                    it.data.optString("destinationSessionKey") == destinationSessionKey
                else -> false
            }
        } ?: return result("NOT_FOUND", false)
        if (!BrowserArtifactFiles.selectionExists(filesRoot, saved.screenshot.name)) {
            return result("ARTIFACT_NOT_FOUND", false)
        }
        return result("OK", false, JSONObject(saved.data.toString())
            .put("createdAtElapsedMillis", saved.createdAtElapsedMillis)
            .put("expiresInMillis", remaining(saved))
            .put("deliveryState", saved.data.optString("deliveryState", "UNBOUND")))
    }

    @Synchronized
    fun list(nowElapsedMillis: Long): JSONObject {
        refreshClock()
        val items = org.json.JSONArray()
        selections.values.filter { !isExpired(it, nowElapsedMillis) }.take(MAX_RETAINED_SCREENSHOTS * 2).forEach { saved ->
            val item = JSONObject()
                .put("selectionId", saved.id)
                .put("selectionSequence", saved.data.optLong("selectionSequence"))
                .put("mode", saved.data.optString("mode"))
                .put("comment", saved.data.optString("comment").take(256))
                .put("pageTitle", saved.data.optString("pageTitle").take(128))
                .put("deliveryState", saved.data.optString("deliveryState", "UNBOUND"))
                .put("destinationSessionKey", saved.data.optString("destinationSessionKey"))
                .put("deliveryReceipt", saved.data.optJSONObject("deliveryReceipt"))
                .put("artifactPath", saved.data.optString("artifactPath"))
                .put("expiresInMillis", remaining(saved))
            if (clockRollback) item.put("deliveryBlocked", "CLOCK_ROLLBACK")
            else if (!canAutomate(saved)) item.put("deliveryBlocked", "BOOT_IDENTITY_UNAVAILABLE")
            items.put(item)
        }
        return result("OK", false, JSONObject().put("items", items).put("clockRollback", clockRollback)
            .put("indexDamaged", indexDamaged))
    }

    private fun isExpired(saved: Saved, nowElapsedMillis: Long = elapsedClock()): Boolean {
        refreshClock()
        if (clockRollback) return false
        val wallExpired = wallClock() >= saved.data.optLong("expiresAtWallMillis", Long.MAX_VALUE)
        if (wallExpired) return true
        if (!canAutomate(saved)) return false
        return nowElapsedMillis >= saved.expiresAtElapsedMillis
    }

    private fun canAutomate(saved: Saved): Boolean = !clockRollback &&
        saved.expiresAtElapsedMillis > saved.createdAtElapsedMillis &&
        saved.data.optString("bootIdentity").isNotBlank() && saved.data.optString("bootIdentity") == bootIdentity()

    private fun refreshClock(now: Long = wallClock()) {
        if (lastWallClockMillis > 0 && now < lastWallClockMillis) clockRollback = true
    }

    private fun restoreObject(target: JSONObject, source: JSONObject) {
        val keys = target.keys().asSequence().toList()
        keys.forEach { target.remove(it) }
        val sourceKeys = source.keys()
        while (sourceKeys.hasNext()) {
            val key = sourceKeys.next()
            target.put(key, source.get(key))
        }
    }

    private fun remaining(saved: Saved): Long = if (clockRollback || !canAutomate(saved)) TTL_MILLIS else
        (saved.expiresAtElapsedMillis - elapsedClock()).coerceAtLeast(0)

    private fun restore() {
        if (!safeDirectoryChain()) return
        runCatching {
            val rawIndex = BrowserArtifactFiles.readSelectionOutbox(filesRoot, MAX_INDEX_BYTES) ?: return
            val root = JSONObject(String(rawIndex, StandardCharsets.UTF_8))
            require(root.optInt("version") in setOf(1, 2))
            require(root.optLong("sequence") in 0L..9_007_199_254_740_991L)
            sequence = root.optLong("sequence").coerceAtLeast(0)
            val now = wallClock()
            lastWallClockMillis = root.optLong("lastWallClockMillis")
            clockRollback = lastWallClockMillis > 0 && now < lastWallClockMillis
            val currentBoot = bootIdentity()
            val array = root.optJSONArray("items") ?: error("Missing outbox entries")
            require(array.length() <= MAX_RETAINED_SCREENSHOTS * 2)
            var recoveredAdmission = false
            val recoveredIds = mutableSetOf<String>()
            val recoveredSequences = mutableSetOf<Long>()
            for (i in 0 until array.length()) {
                val data = array.optJSONObject(i) ?: continue
                val id = data.optString("selectionId")
                require(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
                require(recoveredIds.add(id))
                val name = File(data.optString("artifactPath")).name
                val image = File(directory, name)
                if (name != "selection-$id.png" || data.optString("artifactPath") != "/opt/browser/out/selection/$name" ||
                    !image.isFile || Files.isSymbolicLink(image.toPath()) || image.length() !in 1..MAX_SCREENSHOT_BYTES) continue
                if (data.optString("deliveryState") == "ADMITTING") {
                    recoveredAdmission = true
                    data.put("deliveryState", "UNKNOWN")
                        .put("deliveryReceipt", JSONObject().put("deliveryCode", "UNKNOWN")
                            .put("requestId", data.optString("requestId")))
                }
                val created = data.optLong("createdAtWallMillis")
                val expires = data.optLong("expiresAtWallMillis")
                val itemSequence = data.optLong("selectionSequence")
                require(itemSequence in 1L..sequence && recoveredSequences.add(itemSequence))
                require(data.optString("deliveryState") in setOf("UNBOUND", "CLAIMED", "ADMITTING", "UNKNOWN", "TERMINAL"))
                if (data.optString("deliveryState") in setOf("CLAIMED", "ADMITTING", "UNKNOWN", "TERMINAL")) {
                    require(data.optString("destinationSessionKey").matches(Regex("[^\\u0000-\\u001f\\u007f]{1,256}")))
                    require(data.optString("requestId").matches(Regex("[a-f0-9]{32}")))
                    require(data.optString("payloadDigest").matches(Regex("[a-f0-9]{64}")))
                }
                if (created <= 0 || expires <= created || (!clockRollback && now >= expires)) {
                    if (!clockRollback) BrowserArtifactFiles.removeSelection(filesRoot, name)
                    continue
                }
                val sameBoot = currentBoot != null && data.optString("bootIdentity") == currentBoot
                val createdElapsed = if (sameBoot) data.optLong("createdAtElapsedMillis", -1) else 0L
                val expiresElapsed = if (sameBoot) data.optLong("expiresAtElapsedMillis", -1) else Long.MAX_VALUE
                val saved = Saved(id, data.optLong("originSessionGeneration", 0), createdElapsed, expiresElapsed,
                    JSONObject(data.toString()), File(directory, name))
                selections[id] = saved
                latest = saved
            }
            if (recoveredAdmission) persist()
        }.onFailure {
            // A damaged index is retained for diagnosis; no recovered entry is sent automatically.
            selections.clear()
            latest = null
            sequence = 0
            clockRollback = true
            indexDamaged = true
        }
    }

    private fun persist(now: Long = wallClock()) {
        check(safeDirectoryChain()) { "Browser selection output directory unavailable or unsafe" }
        check(selections.size <= MAX_RETAINED_SCREENSHOTS * 2) { "Browser selection outbox capacity exceeded" }
        refreshClock(now)
        lastWallClockMillis = maxOf(lastWallClockMillis, now)
        val array = org.json.JSONArray()
        selections.values.forEach { array.put(JSONObject(it.data.toString()).put("originSessionGeneration", it.sessionGeneration)) }
        val bytes = JSONObject().put("version", 2).put("sequence", sequence)
            .put("lastWallClockMillis", lastWallClockMillis).put("items", array)
            .toString().toByteArray(StandardCharsets.UTF_8)
        check(bytes.size in 1..MAX_INDEX_BYTES) { "Browser selection outbox exceeds its storage limit" }
        writeOutbox(filesRoot, bytes, MAX_INDEX_BYTES)
    }

    private fun safeDirectoryChain(): Boolean {
        if (directory.absoluteFile.toPath().normalize() !=
            File(filesRoot, "browser-tools/out/selection").absoluteFile.toPath().normalize()) return false
        return listOf(filesRoot, File(filesRoot, "browser-tools"), File(filesRoot, "browser-tools/out"), directory)
            .all { it.isDirectory && !Files.isSymbolicLink(it.toPath()) && Files.isDirectory(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
    }

    private fun safeUrl(raw: String): String = runCatching {
        val uri = android.net.Uri.parse(raw)
        if ((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
            Uri.Builder()
                .scheme(uri.scheme)
                .authority(uri.host)
                .encodedPath(uri.encodedPath)
                .build()
                .toString()
                .take(MAX_URL_CHARS)
        } else ""
    }.getOrDefault("")

    private fun result(code: String, executed: Boolean?, data: JSONObject = JSONObject()) =
        JSONObject().put("code", code).put("executed", executed).put("data", data)

    internal companion object {
        const val MODE_ELEMENT = "element"
        const val MODE_AREA = "area"
        const val ACTION_LATEST = "latest"
        const val ACTION_CONSUME = "consume"
        const val ACTION_CLEAR = "clear"
        const val MAX_COMMENT_CHARS = 2_048
        const val MAX_SELECTION_JSON_CHARS = 8_192
        const val MAX_RESPONSE_DATA_BYTES = 12 * 1_024
        const val MAX_RETAINED_SCREENSHOTS = 8
        const val MAX_SCREENSHOT_BYTES = 8L * 1_024 * 1_024
        const val MAX_TOTAL_SCREENSHOT_BYTES = 32L * 1_024 * 1_024
        const val MAX_INDEX_BYTES = 256 * 1_024
        const val MAX_TITLE_CHARS = 256
        const val MAX_URL_CHARS = 2_048
        const val TTL_MILLIS = 30 * 60_000L
    }
}

private fun readBrowserBootIdentity(): String? = runCatching {
    File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf {
        it.matches(Regex("[0-9a-fA-F-]{36}"))
    }
}.getOrNull()
