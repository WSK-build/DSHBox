package com.dshbox.browser.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.net.URI
import java.util.UUID

/** Private, durable handoff for Native-approved task recovery drafts. */
internal class BrowserTaskFeedbackStore(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE feedback (
            sequence INTEGER PRIMARY KEY AUTOINCREMENT,
            feedback_id TEXT NOT NULL UNIQUE,
            task_id TEXT NOT NULL,
            revision INTEGER NOT NULL,
            session_generation INTEGER NOT NULL,
            kind TEXT NOT NULL,
            title TEXT NOT NULL,
            text TEXT NOT NULL,
            state TEXT NOT NULL,
            destination_session_key TEXT,
            request_id TEXT,
            payload_digest TEXT,
            delivery_code TEXT,
            created_at_wall INTEGER NOT NULL,
            UNIQUE(task_id, revision, session_generation)
        )""")
        db.execSQL("CREATE INDEX feedback_state_sequence ON feedback(state, sequence)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Unsupported task feedback database version")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.beginTransaction()
        try {
            db.execSQL("UPDATE feedback SET state='UNKNOWN', delivery_code='UNKNOWN' WHERE state='ADMITTED'")
            pruneConfirmedTerminalRows(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun enqueue(draftJson: String, sessionGeneration: Long, nowWallMillis: Long = System.currentTimeMillis()): Boolean {
        if (sessionGeneration <= 0 || draftJson.toByteArray(StandardCharsets.UTF_8).size > MAX_DRAFT_JSON_BYTES) return false
        val draft = runCatching { JSONObject(draftJson) }.getOrNull() ?: return false
        val normalized = validateAndFormatDraft(draft) ?: return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            retirePreviousGenerationUnadmittedRows(db, sessionGeneration)
            val existing = db.rawQuery(
                "SELECT state FROM feedback WHERE task_id=? AND revision=? AND session_generation=? LIMIT 1",
                arrayOf(normalized.taskId, normalized.revision.toString(), sessionGeneration.toString()),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (existing != null) {
                db.setTransactionSuccessful()
                return existing in setOf(STATE_PENDING, STATE_CLAIMED)
            }
            pruneConfirmedTerminalRows(db)
            val pendingCount = db.rawQuery(
                "SELECT COUNT(*) FROM feedback WHERE state IN ('PENDING','CLAIMED','ADMITTED','UNKNOWN')", null,
            ).use { if (it.moveToFirst()) it.getInt(0) else MAX_PENDING }
            if (pendingCount >= MAX_PENDING) return false
            val values = ContentValues().apply {
                put("feedback_id", UUID.randomUUID().toString().replace("-", ""))
                put("task_id", normalized.taskId)
                put("revision", normalized.revision)
                put("session_generation", sessionGeneration)
                put("kind", KIND_TASK_RECOVERY)
                put("title", normalized.title)
                put("text", normalized.text)
                put("state", STATE_PENDING)
                put("created_at_wall", nowWallMillis.coerceAtLeast(0L))
            }
            if (db.insertOrThrow("feedback", null, values) < 0) return false
            db.setTransactionSuccessful()
            return true
        } catch (_: Exception) {
            return false
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun pending(afterSequence: Long, sessionGeneration: Long, destinationSessionKey: String): JSONObject {
        if (afterSequence < 0 || sessionGeneration <= 0 || !validDestination(destinationSessionKey)) return result("INVALID_ARGUMENT")
        val items = JSONArray()
        var nextSequence = afterSequence
        readableDatabase.rawQuery(
            "SELECT sequence,feedback_id,task_id,revision,session_generation,kind,title,text,state " +
                "FROM feedback WHERE sequence>? AND session_generation=? AND " +
                "(state='PENDING' OR (state='CLAIMED' AND destination_session_key=?)) ORDER BY sequence LIMIT ?",
            arrayOf(afterSequence.toString(), sessionGeneration.toString(), destinationSessionKey, MAX_PENDING.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val sequence = cursor.getLong(0)
                val item = JSONObject()
                    .put("feedbackId", cursor.getString(1))
                    .put("kind", cursor.getString(5))
                    .put("taskId", cursor.getString(2))
                    .put("revision", cursor.getLong(3))
                    .put("title", cursor.getString(6))
                    .put("text", cursor.getString(7))
                    .put("sequence", sequence)
                    .put("state", cursor.getString(8).lowercase())
                    .put("sessionGeneration", cursor.getLong(4))
                items.put(item)
                val candidate = pendingResult(afterSequence, sequence, items)
                if (candidate.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_PENDING_PAGE_BYTES) {
                    items.remove(items.length() - 1)
                    if (items.length() == 0) return result("LIMIT_EXCEEDED", false,
                        JSONObject().put("afterSequence", afterSequence).put("nextSequence", afterSequence).put("items", items))
                    break
                }
                nextSequence = sequence
            }
        }
        return pendingResult(afterSequence, nextSequence, items)
    }

    @Synchronized
    fun claim(args: JSONObject, sessionGeneration: Long): JSONObject {
        val row = find(args.optString("feedbackId"), sessionGeneration) ?: return result("FEEDBACK_NOT_FOUND")
        val target = args.optString("destinationSessionKey")
        val requestId = args.optString("requestId")
        val digest = args.optString("payloadDigest")
        if (!validDestination(target) || !requestId.matches(REQUEST_ID) || !digest.matches(PAYLOAD_DIGEST)) return result("INVALID_ARGUMENT")
        if (row.destination != null && row.destination != target) return result("DESTINATION_MISMATCH")
        if (row.state == STATE_CLAIMED) {
            return if (row.destination == target && row.requestId == requestId && row.digest == digest)
                result("OK", true, claimData(row, STATE_CLAIMED)) else result("FEEDBACK_CONFLICT")
        }
        if (row.state != STATE_PENDING) return result("FEEDBACK_ALREADY_ADMITTED", false, claimData(row, row.state))
        val values = ContentValues().apply {
            put("state", STATE_CLAIMED); put("destination_session_key", target)
            put("request_id", requestId); put("payload_digest", digest)
        }
        if (writableDatabase.update("feedback", values, "feedback_id=? AND state='PENDING' AND session_generation=?",
                arrayOf(row.id, sessionGeneration.toString())) != 1) return result("FEEDBACK_CONFLICT")
        return result("OK", true, claimData(row.copy(state = STATE_CLAIMED, destination = target, requestId = requestId, digest = digest), STATE_CLAIMED))
    }

    @Synchronized
    fun admit(args: JSONObject, sessionGeneration: Long): JSONObject {
        val row = find(args.optString("feedbackId"), sessionGeneration) ?: return result("FEEDBACK_NOT_FOUND")
        if (!matchesClaim(row, args)) return result("DESTINATION_MISMATCH")
        if (row.state != STATE_CLAIMED) return result("FEEDBACK_ALREADY_ADMITTED", false, claimData(row, row.state))
        val values = ContentValues().apply { put("state", STATE_ADMITTED) }
        if (writableDatabase.update("feedback", values, "feedback_id=? AND state='CLAIMED' AND session_generation=?",
                arrayOf(row.id, sessionGeneration.toString())) != 1) return result("FEEDBACK_CONFLICT")
        return result("OK", true, claimData(row.copy(state = STATE_ADMITTED), STATE_ADMITTED))
    }

    @Synchronized
    fun receipt(args: JSONObject, sessionGeneration: Long): JSONObject {
        val row = find(args.optString("feedbackId"), sessionGeneration) ?: return result("FEEDBACK_NOT_FOUND")
        if (!validDestination(args.optString("destinationSessionKey")) ||
            args.optString("destinationSessionKey") != row.destination || args.optString("requestId") != row.requestId
        ) return result("DESTINATION_MISMATCH")
        val deliveryCode = args.optString("deliveryCode")
        if (deliveryCode !in DELIVERY_CODES) return result("INVALID_ARGUMENT")
        val nextState = when (deliveryCode) {
            "PROMPT_ACCEPTED" -> STATE_PROMPT_ACCEPTED
            "UNKNOWN" -> STATE_UNKNOWN
            else -> STATE_BLOCKED
        }
        if (row.state == nextState && row.deliveryCode == deliveryCode) return result("OK", true, receiptData(row, nextState, deliveryCode))
        if (row.state != STATE_ADMITTED && row.state != STATE_UNKNOWN) return result("FEEDBACK_NOT_ADMITTED")
        if (row.state == STATE_UNKNOWN && deliveryCode != "PROMPT_ACCEPTED") return result("FEEDBACK_CONFLICT")
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply { put("state", nextState); put("delivery_code", deliveryCode) }
            if (nextState in CONFIRMED_TERMINAL_STATES) {
                values.put("title", "")
                values.put("text", "")
            }
            if (db.update("feedback", values, "feedback_id=? AND state IN ('ADMITTED','UNKNOWN') AND session_generation=?",
                    arrayOf(row.id, sessionGeneration.toString())) != 1) return result("FEEDBACK_CONFLICT")
            if (nextState in CONFIRMED_TERMINAL_STATES) pruneConfirmedTerminalRows(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return result("OK", true, receiptData(row, nextState, deliveryCode))
    }

    private fun pruneConfirmedTerminalRows(db: SQLiteDatabase) {
        db.execSQL("UPDATE feedback SET title='',text='' WHERE state IN ('PROMPT_ACCEPTED','BLOCKED')")
        db.execSQL("""DELETE FROM feedback
            WHERE state IN ('PROMPT_ACCEPTED','BLOCKED')
              AND sequence NOT IN (
                SELECT sequence FROM feedback
                WHERE state IN ('PROMPT_ACCEPTED','BLOCKED')
                ORDER BY sequence DESC LIMIT $MAX_RETAINED_TERMINAL
              )""".trimIndent())
    }

    private fun retirePreviousGenerationUnadmittedRows(db: SQLiteDatabase, currentGeneration: Long) {
        db.execSQL(
            "UPDATE feedback SET state='BLOCKED',delivery_code='BLOCKED',title='',text='' " +
                "WHERE session_generation<>? AND state IN ('PENDING','CLAIMED')",
            arrayOf<Any?>(currentGeneration),
        )
        pruneConfirmedTerminalRows(db)
    }

    private fun find(id: String, sessionGeneration: Long): FeedbackRow? {
        if (!id.matches(FEEDBACK_ID) || sessionGeneration <= 0) return null
        return readableDatabase.rawQuery(
            "SELECT feedback_id,state,destination_session_key,request_id,payload_digest,delivery_code " +
                "FROM feedback WHERE feedback_id=? AND session_generation=? LIMIT 1",
            arrayOf(id, sessionGeneration.toString()),
        ).use { cursor -> if (!cursor.moveToFirst()) null else FeedbackRow(
            cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5),
        ) }
    }

    private fun matchesClaim(row: FeedbackRow, args: JSONObject): Boolean =
        validDestination(args.optString("destinationSessionKey")) &&
            args.optString("destinationSessionKey") == row.destination &&
            args.optString("requestId").matches(REQUEST_ID) && args.optString("requestId") == row.requestId &&
            args.optString("payloadDigest").matches(PAYLOAD_DIGEST) && args.optString("payloadDigest") == row.digest

    private fun claimData(row: FeedbackRow, state: String) = JSONObject()
        .put("feedbackId", row.id).put("state", state.lowercase())
        .put("requestId", row.requestId ?: JSONObject.NULL).put("payloadDigest", row.digest ?: JSONObject.NULL)

    private fun receiptData(row: FeedbackRow, state: String, deliveryCode: String) = JSONObject()
        .put("feedbackId", row.id).put("state", state.lowercase())
        .put("deliveryCode", deliveryCode)

    private fun result(code: String, executed: Boolean = false, data: JSONObject = JSONObject()) =
        JSONObject().put("code", code).put("executed", executed).put("data", data)

    private data class FeedbackRow(
        val id: String,
        val state: String,
        val destination: String?,
        val requestId: String?,
        val digest: String?,
        val deliveryCode: String?,
    )

    private data class Draft(val taskId: String, val revision: Long, val title: String, val text: String)

    private fun validateAndFormatDraft(draft: JSONObject): Draft? = runCatching {
        val expectedKeys = if (draft.has("failureReason")) DRAFT_KEYS + "failureReason" else DRAFT_KEYS
        require(draft.keys().asSequence().toSet() == expectedKeys)
        require(draft.opt("kind") is String && draft.optString("kind") == KIND_TASK_RECOVERY)
        require(draft.opt("taskId") is String && draft.opt("title") is String && draft.opt("goal") is String &&
            draft.opt("notes") is String && draft.opt("status") is String)
        val taskId = draft.optString("taskId")
        require(taskId.matches(TASK_ID))
        val revision = nonNegativeInteger(draft.opt("revision")) ?: error("invalid revision")
        require(revision <= MAX_SAFE_INTEGER && nonNegativeInteger(draft.opt("createdAtWallMillis")) != null)
        val status = draft.optString("status")
        require(status in TASK_STATUSES)
        val title = sanitizeText(draft.optString("title"), 120)
        val goal = sanitizeText(draft.optString("goal"), 4000)
        val notes = sanitizeText(draft.optString("notes"), 2000)
        val failureReason = if (!draft.has("failureReason") || draft.isNull("failureReason")) null else {
            val rawReason = draft.opt("failureReason") as? String ?: error("invalid failure reason")
            require(status == "failed" && rawReason.isNotBlank() && rawReason.length <= 1000)
            sanitizeText(rawReason, 1000)
        }
        val createdAtWallMillis = nonNegativeInteger(draft.opt("createdAtWallMillis")) ?: error("invalid created time")
        val effects = draft.optJSONArray("unknownEffects") ?: error("invalid effects")
        val effectsTotal = nonNegativeInteger(draft.opt("unknownEffectsTotal")) ?: error("invalid effect count")
        val effectsTruncated = draft.opt("effectsTruncated") as? Boolean ?: error("invalid truncation marker")
        require(effects.length() <= MAX_UNKNOWN_EFFECTS && effectsTotal >= effects.length() &&
            effectsTruncated == (effectsTotal > effects.length()))
        val lines = buildList {
            add("Recovery guidance: call browser_task status with this Task id before acting to locate this task and confirm its current revision, title, goal, and status. If the task is missing or any value differs from this draft, stop and do not continue business actions. This draft may be stale; prior results do not prove success. Never replay an UNKNOWN effect. Make fresh Browser observations before deciding what happened.")
            add("Task id: $taskId")
            add("Revision: $revision")
            add("Title: $title")
            add("Created wall time: $createdAtWallMillis")
            add("Task status: $status")
            add("Goal: $goal")
            if (notes.isNotBlank()) add("Notes: $notes")
            if (failureReason != null) add("Failure reason: $failureReason")
            if (effects.length() > 0) {
                add("Uncertain effects ($effectsTotal total):")
                for (index in 0 until effects.length()) {
                    val effect = effects.getJSONObject(index)
                    require(effect.keys().asSequence().toSet() == EFFECT_KEYS)
                    val method = effect.optString("method")
                    val requestId = effect.optString("requestId")
                    val phase = effect.optString("phase")
                    val choice = effect.opt("lastUserChoice")
                    require(method in EFFECT_METHODS && requestId.matches(REQUEST_ID))
                    require(phase in EFFECT_PHASES)
                    require(choice == JSONObject.NULL || choice is String && choice in USER_CHOICES)
                    add("- ${method} request ${requestId}; phase $phase; user report ${if (choice == JSONObject.NULL) "none" else choice}.")
                }
            }
            if (effectsTotal > effects.length()) {
                add("Only ${effects.length()} uncertain effect entries are shown here; inspect the Browser task recovery panel for the complete unknown-effect records before deciding what happened.")
            }
        }
        val text = lines.joinToString("\n")
        require(text.toByteArray(StandardCharsets.UTF_8).size <= MAX_MESSAGE_BYTES)
        Draft(taskId, revision, title, text)
    }.getOrNull()

    private fun nonNegativeInteger(value: Any?): Long? = when (value) {
        is Int -> value.toLong().takeIf { it >= 0L }
        is Long -> value.takeIf { it >= 0L }
        else -> null
    }

    private fun validDestination(value: String): Boolean = value.length in 1..256 &&
        value.none { it in '\u0000'..'\u001f' || it == '\u007f' }

    private fun sanitizeText(value: String, maxChars: Int): String {
        require(value.length <= maxChars)
        return value
            .replace(URL_PATTERN) { match -> safeUrlTarget(match.value) }
            .replace(WINDOWS_PATH_PATTERN, "[path omitted]")
            .replace(UNIX_PATH_PATTERN, "[path omitted]")
            .replace(CREDENTIAL_PATTERN, "$1=[redacted]")
            .replace(BEARER_PATTERN, "[credential redacted]")
            .replace(CONTROL_PATTERN, " ")
            .trim()
    }

    private fun safeUrlTarget(raw: String): String {
        val suffix = raw.takeLastWhile { it in TRAILING_URL_PUNCTUATION }
        val candidate = raw.dropLast(suffix.length)
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return "[URL target omitted]$suffix"
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return "[URL target omitted]$suffix"
        val port = if (uri.port in 0..65535) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().take(2_048)
        val queryMarker = if (uri.rawQuery != null || uri.rawFragment != null) " (query and fragment omitted)" else ""
        return "${uri.scheme.lowercase()}://$host$port$path$queryMarker$suffix"
    }

    private fun pendingResult(afterSequence: Long, nextSequence: Long, items: JSONArray): JSONObject =
        result("OK", false, JSONObject().put("afterSequence", afterSequence)
            .put("nextSequence", nextSequence).put("items", items))

    companion object {
        internal const val DATABASE_NAME = "browser_task_feedback.db"
        private const val DATABASE_VERSION = 1
        const val MAX_PENDING = 64
        const val MAX_RETAINED_TERMINAL = 64
        const val MAX_LEDGER_ROWS = MAX_PENDING + MAX_RETAINED_TERMINAL
        const val MAX_MESSAGE_BYTES = 64 * 1024
        private const val MAX_DRAFT_JSON_BYTES = 64 * 1024
        internal const val MAX_PENDING_PAGE_BYTES = 240 * 1024
        private const val MAX_UNKNOWN_EFFECTS = 32
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private const val KIND_TASK_RECOVERY = "task_recovery"
        private const val STATE_PENDING = "PENDING"
        private const val STATE_CLAIMED = "CLAIMED"
        private const val STATE_ADMITTED = "ADMITTED"
        private const val STATE_UNKNOWN = "UNKNOWN"
        private const val STATE_BLOCKED = "BLOCKED"
        private const val STATE_PROMPT_ACCEPTED = "PROMPT_ACCEPTED"
        private val DELIVERY_CODES = setOf("PROMPT_ACCEPTED", "UNKNOWN", "BLOCKED")
        private val CONFIRMED_TERMINAL_STATES = setOf(STATE_PROMPT_ACCEPTED, STATE_BLOCKED)
        private val DRAFT_KEYS = setOf("kind", "taskId", "title", "goal", "notes", "status", "revision", "unknownEffects", "unknownEffectsTotal", "effectsTruncated", "createdAtWallMillis")
        private val EFFECT_KEYS = setOf("method", "requestId", "phase", "lastUserChoice")
        private val TASK_STATUSES = setOf("running", "waiting_page", "waiting_user", "paused", "needs_reconciliation", "budget_exhausted", "completed", "failed", "expired")
        private val EFFECT_METHODS = setOf("open", "observe", "locate", "click", "type", "scroll", "screenshot", "close", "debug", "network", "selection", "page", "file", "upload", "wait", "navigate", "task", "collect", "event")
        private val EFFECT_PHASES = setOf("intent", "confirmed", "unknown", "reconciled")
        private val USER_CHOICES = setOf("occurred", "not_occurred", "still_unknown")
        private val FEEDBACK_ID = Regex("[a-f0-9]{32}")
        private val TASK_ID = Regex("task_[a-f0-9]{32}")
        private val REQUEST_ID = Regex("[a-f0-9]{32}")
        private val PAYLOAD_DIGEST = Regex("[a-f0-9]{64}")
        private val URL_PATTERN = Regex("(?i)https?://\\S+")
        private val TRAILING_URL_PUNCTUATION = setOf('.', ',', ';', ')', ']', '}', '!')
        private val WINDOWS_PATH_PATTERN = Regex("(?i)(?<![\\w])[a-z]:\\\\[^\\s,;]+")
        private val UNIX_PATH_PATTERN = Regex("(?i)(?<![\\w])/(?:root|home|data|storage|sdcard|mnt|tmp|var|etc|opt|usr|system|private|Users)/[^\\s,;]+")
        private val CREDENTIAL_PATTERN = Regex("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|secret|authorization|cookie|credential)\\s*[:=]\\s*[^\\s,;]+")
        private val BEARER_PATTERN = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
        private val CONTROL_PATTERN = Regex("[\\u0000-\\u001f\\u007f]")
    }
}
