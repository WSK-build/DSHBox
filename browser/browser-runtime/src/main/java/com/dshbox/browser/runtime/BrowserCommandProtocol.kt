package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserTaskBudget
import com.dshbox.browser.api.BrowserTaskWaitReason
import android.os.SystemClock
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.net.URI

internal enum class BrowserCommandMethod {
    STATUS,
    OPEN,
    OBSERVE,
    LOCATE,
    CLICK,
    TYPE,
    SCROLL,
    SCREENSHOT,
    CLOSE,
    DEBUG,
    NETWORK,
    SELECTION,
    PAGE,
    FILE,
    UPLOAD,
    WAIT,
    NAVIGATE,
    TASK,
    COLLECT,
    EVENT,
    CONTROL,
}

internal data class BrowserCommandRequest(
    val requestId: String,
    val method: BrowserCommandMethod,
    val deadlineAtElapsedMillis: Long,
    val sessionGeneration: Long?,
    val controlGeneration: Long?,
    val owner: String?,
    val taskId: String?,
    val args: JSONObject,
    val rawJson: String,
) {
    companion object {
        const val MAX_JSON_BYTES = 16 * 1024
        const val MAX_RESPONSE_JSON_BYTES = 256 * 1024
        const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        const val MAX_DEADLINE_BUDGET_MILLIS = 60_000L
        private const val MAX_UPLOAD_ACCEPT_TYPES = 20
        private const val MAX_UPLOAD_SUMMARY_CHARS = 160

        fun parse(
            json: String,
            nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
        ): Result<BrowserCommandRequest> = runCatching {
            require(json.toByteArray(StandardCharsets.UTF_8).size <= MAX_JSON_BYTES)
            val root = JSONObject(json)
            requireOnlyKeys(
                root,
                setOf(
                    "version", "requestId", "method", "sessionGeneration", "controlGeneration",
                    "owner", "deadlineAtMillis", "args", "taskId",
                ),
            )
            val version = root.opt("version")
            require((version is Int || version is Long) && (version as Number).toLong() == 1L) { "INVALID_VERSION" }
            val requestId = root.opt("requestId") as? String ?: error("INVALID_REQUEST_ID")
            require(REQUEST_ID_PATTERN.matches(requestId))
            val methodName = root.opt("method") as? String ?: error("INVALID_METHOD")
            val method = when (methodName) {
                "status" -> BrowserCommandMethod.STATUS
                "open" -> BrowserCommandMethod.OPEN
                "observe" -> BrowserCommandMethod.OBSERVE
                "locate" -> BrowserCommandMethod.LOCATE
                "click" -> BrowserCommandMethod.CLICK
                "type" -> BrowserCommandMethod.TYPE
                "scroll" -> BrowserCommandMethod.SCROLL
                "screenshot" -> BrowserCommandMethod.SCREENSHOT
                "close" -> BrowserCommandMethod.CLOSE
                "debug" -> BrowserCommandMethod.DEBUG
                "network" -> BrowserCommandMethod.NETWORK
                "selection" -> BrowserCommandMethod.SELECTION
                "page" -> BrowserCommandMethod.PAGE
                "file" -> BrowserCommandMethod.FILE
                "upload" -> BrowserCommandMethod.UPLOAD
                "wait" -> BrowserCommandMethod.WAIT
                "navigate" -> BrowserCommandMethod.NAVIGATE
                "task" -> BrowserCommandMethod.TASK
                "collect" -> BrowserCommandMethod.COLLECT
                "event" -> BrowserCommandMethod.EVENT
                "control" -> BrowserCommandMethod.CONTROL
                else -> error("UNSUPPORTED_METHOD")
            }
            val deadlineAtWallMillis = safeLong(root, "deadlineAtMillis")
            val budgetMillis = deadlineAtWallMillis - System.currentTimeMillis()
            require(budgetMillis in 1..MAX_DEADLINE_BUDGET_MILLIS) { "DEADLINE_INVALID" }
            require(!root.has("sessionGeneration") || !root.isNull("sessionGeneration"))
            require(!root.has("controlGeneration") || !root.isNull("controlGeneration"))
            val session = optionalSafeLong(root, "sessionGeneration")
            val control = optionalSafeLong(root, "controlGeneration")
            val owner = if (root.has("owner")) root.opt("owner") as? String ?: error("INVALID_OWNER") else null
            val taskId = if (root.has("taskId")) root.opt("taskId") as? String ?: error("INVALID_TASK_ID") else null
            require(taskId == null || taskId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "INVALID_TASK_ID" }
            require(method != BrowserCommandMethod.UPLOAD || taskId != null) { "TASK_ID_REQUIRED" }
            require(method != BrowserCommandMethod.OPEN || session != null || taskId == null) { "TASK_PAGE_MISMATCH" }
            val args = root.opt("args") as? JSONObject ?: error("INVALID_ARGUMENTS")
            if (method == BrowserCommandMethod.CONTROL) {
                require(session == null && control == null && owner == null) { "INVALID_ARGUMENT" }
                require(taskId == null) { "INVALID_ARGUMENT" }
                when (args.optString("action")) {
                    "request" -> {
                        requireOnlyKeys(args, setOf("action", "summary", "taskId"))
                        require(text(args.opt("summary"), 1, 1000) && args.getString("summary").isNotBlank()) { "INVALID_ARGUMENT" }
                        if (args.has("taskId")) require((args.opt("taskId") as? String)?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true) { "INVALID_ARGUMENT" }
                    }
                    "status", "cancel" -> {
                        require(taskId == null) { "INVALID_ARGUMENT" }
                        requireOnlyKeys(args, setOf("action", "approvalId"))
                        require((args.opt("approvalId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true) { "INVALID_ARGUMENT" }
                    }
                    else -> error("INVALID_ARGUMENT")
                }
                return@runCatching BrowserCommandRequest(requestId, method, nowElapsedMillis + budgetMillis, null, null, null, taskId, args, root.toString())
            }
            when (method) {
                BrowserCommandMethod.STATUS -> {
                    require(session == null && control == null && owner == null)
                    requireOnlyKeys(args, emptySet())
                }
                BrowserCommandMethod.WAIT -> {
                    require(session != null && session > 0 && control == null && owner == null)
                    val condition = args.opt("condition") as? String ?: error("INVALID_ARGUMENT")
                    require(condition in setOf("load", "url", "text", "selector"))
                    requireOnlyKeys(args, setOf("condition", "timeoutMs", "url", "text", "selector", "state", "pollIntervalMs"))
                    if (args.has("timeoutMs")) require(integer(args, "timeoutMs") in 1..30_000)
                    if (args.has("pollIntervalMs")) require(integer(args, "pollIntervalMs") in 100..1_000)
                    when (condition) {
                        "load" -> require(!args.has("url") && !args.has("text") && !args.has("selector") && !args.has("state"))
                        "url" -> {
                            require(args.has("url") && text(args.opt("url"), 1, 2048) && !args.has("text") && !args.has("selector") && !args.has("state"))
                            val uri = URI(args.getString("url"))
                            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
                        }
                        "text" -> require(args.has("text") && text(args.opt("text"), 1, 2048) && !args.has("url") && !args.has("selector") && !args.has("state"))
                        "selector" -> {
                            require(args.has("selector") && text(args.opt("selector"), 1, 2048) && !args.has("url") && !args.has("text"))
                            if (args.has("state")) require(args.opt("state") in setOf("attached", "visible", "hidden"))
                        }
                    }
                }
                BrowserCommandMethod.NAVIGATE -> {
                    require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    validateActionEventCredentials(args, setOf("action", "thumbnail"))
                    require(args.opt("action") in setOf("back", "forward", "reload"))
                    require(!args.has("thumbnail") || args.opt("thumbnail") is Boolean)
                }
                BrowserCommandMethod.TASK -> {
                    require(taskId == null)
                    val action = args.opt("action") as? String ?: error("INVALID_ARGUMENT")
                    val needsAiControl = action in setOf("begin", "complete", "reconcile", "resume")
                    if (needsAiControl) require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    else require(session == null && control == null && owner == null)
                    when (action) {
                        "begin" -> {
                            requireOnlyKeys(args, setOf("action", "durationMs", "operationLimit", "title", "goal", "notes"))
                            if (args.has("durationMs")) require(integer(args, "durationMs").toLong() in 1L..BrowserTaskBudget.MAX_DURATION_MILLIS)
                            if (args.has("operationLimit")) require(integer(args, "operationLimit") in 1..BrowserTaskBudget.MAX_OPERATION_LIMIT)
                            validateTaskText(args)
                        }
                        "list" -> {
                            requireOnlyKeys(args, setOf("action", "offset", "limit"))
                            if (args.has("offset")) require(integer(args, "offset") >= 0)
                            if (args.has("limit")) require(integer(args, "limit") in 1..100)
                        }
                        "status", "pause", "wait", "complete", "fail", "reconcile", "resume", "delete" -> {
                            requireOnlyKeys(args, setOf("action", "taskId", "reason", "expectedRevision"))
                            require((args.opt("taskId") as? String)?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true)
                            if (action == "wait") require(args.opt("reason") in BrowserTaskWaitReason.entries.map { it.wireValue })
                            else if (action == "fail") require(!args.has("reason") ||
                                (text(args.opt("reason"), 1, 1000) && args.getString("reason").isNotBlank()))
                            else require(!args.has("reason"))
                            if (action == "delete") safeLong(args, "expectedRevision")
                            else require(!args.has("expectedRevision"))
                        }
                        "update" -> {
                            requireOnlyKeys(args, setOf("action", "taskId", "expectedRevision", "title", "goal", "notes"))
                            require((args.opt("taskId") as? String)?.matches(Regex("[A-Za-z0-9_-]{1,64}")) == true)
                            safeLong(args, "expectedRevision")
                            validateTaskText(args)
                            require(args.has("title") || args.has("goal") || args.has("notes"))
                        }
                        else -> error("INVALID_ARGUMENT")
                    }
                }
                BrowserCommandMethod.COLLECT -> {
                    require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    validateCollectArgs(args)
                }
                BrowserCommandMethod.EVENT -> {
                    require(taskId != null && session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    when (args.optString("action")) {
                        "arm" -> {
                            requireOnlyKeys(args, setOf("action", "eventKinds", "ttlMs"))
                            val kinds = args.optJSONArray("eventKinds") ?: error("INVALID_ARGUMENT")
                            require(kinds.length() in 1..6)
                            val values = (0 until kinds.length()).map { kinds.optString(it, "") }
                            require(values.distinct().size == values.size && values.all { it in BrowserEventCoordinator.EVENT_KINDS })
                            if (args.has("ttlMs")) require(integer(args, "ttlMs") in 1..30_000)
                        }
                        "wait" -> {
                            requireOnlyKeys(args, setOf("action", "subscriptionId", "timeoutMs"))
                            require(validEventId(args.opt("subscriptionId")))
                            if (args.has("timeoutMs")) require(integer(args, "timeoutMs") in 1..30_000)
                        }
                        "cancel" -> {
                            requireOnlyKeys(args, setOf("action", "subscriptionId"))
                            require(validEventId(args.opt("subscriptionId")))
                        }
                        "resolve_dialog" -> {
                            requireOnlyKeys(args, setOf("action", "eventId", "decision", "promptText"))
                            require(validEventId(args.opt("eventId")))
                            val decision = args.optString("decision")
                            require(decision in setOf("accept", "dismiss", "prompt"))
                            if (decision == "prompt") require(text(args.opt("promptText"), 0, 4096))
                            else require(!args.has("promptText"))
                        }
                        else -> error("INVALID_ARGUMENT")
                    }
                }
                BrowserCommandMethod.CONTROL -> error("Control was validated before the action schema")
                BrowserCommandMethod.OBSERVE -> {
                    require(session != null && session > 0 && control == null && owner == null)
                    requireOnlyKeys(args, setOf("maxTextChars"))
                    if (args.has("maxTextChars")) require(integer(args, "maxTextChars") in 1..4096)
                }
                BrowserCommandMethod.LOCATE -> {
                    require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    requireOnlyKeys(args, setOf("strategy", "value", "exact", "name", "scopeRef", "partial"))
                    val strategy = args.opt("strategy") as? String ?: error("INVALID_ARGUMENT")
                    require(strategy in setOf("role", "label", "placeholder", "text", "css"))
                    require(text(args.opt("value"), 1, 2048))
                    require(args.opt("exact") is Boolean)
                    require(!args.has("partial") || args.opt("partial") is Boolean)
                    if (strategy == "role") {
                        if (args.has("name")) require(text(args.opt("name"), 0, 2048))
                    } else require(!args.has("name"))
                    if (args.has("scopeRef")) require(validNode(args.opt("scopeRef")))
                }
                BrowserCommandMethod.OPEN -> {
                    require(owner == "ai")
                    require((session == null) == (control == null))
                    if (session != null) require(session > 0 && control != null && control > 0)
                    requireOnlyKeys(args, setOf("url", "thumbnail"))
                    require(!args.has("thumbnail") || args.opt("thumbnail") is Boolean)
                    require(args.opt("thumbnail") != true || args.has("url"))
                    if (args.has("url")) {
                        val rawUrl = args.opt("url") as? String ?: error("INVALID_ARGUMENT")
                        require(rawUrl.length <= 2048)
                        val uri = URI(rawUrl)
                        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null)
                    }
                }
                BrowserCommandMethod.CLICK,
                BrowserCommandMethod.TYPE,
                BrowserCommandMethod.SCROLL,
                BrowserCommandMethod.SCREENSHOT,
                BrowserCommandMethod.CLOSE -> {
                    require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    when (method) {
                        BrowserCommandMethod.CLICK -> {
                            val nodeClick = args.has("nodeId") && !args.has("screenshotId") && !args.has("x") && !args.has("y")
                            val visualClick = !args.has("nodeId") && args.has("screenshotId") && args.has("x") && args.has("y")
                            require(nodeClick || visualClick)
                            validateActionEventCredentials(args, if (nodeClick) setOf("nodeId") else setOf("screenshotId", "x", "y"))
                            if (nodeClick) require(validNode(args.opt("nodeId"))) else {
                                require((args.opt("screenshotId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                                require(finiteNumber(args.opt("x")) && finiteNumber(args.opt("y")))
                            }
                        }
                        BrowserCommandMethod.TYPE -> {
                            requireOnlyKeys(args, setOf("nodeId", "text", "replace"))
                            require(validNode(args.opt("nodeId")))
                            val text = args.opt("text") as? String ?: error("INVALID_ARGUMENT")
                            require(text.length <= 4096)
                            if (args.has("replace")) require(args.opt("replace") is Boolean)
                        }
                        BrowserCommandMethod.SCROLL -> {
                            requireOnlyKeys(args, setOf("nodeId", "direction", "steps"))
                            if (args.has("nodeId")) require(validNode(args.opt("nodeId")))
                            require(args.opt("direction") in setOf("up", "down"))
                            if (args.has("steps")) require(integer(args, "steps") in 1..10)
                        }
                        BrowserCommandMethod.SCREENSHOT, BrowserCommandMethod.CLOSE -> requireOnlyKeys(args, emptySet())
                        else -> Unit
                    }
                }
                BrowserCommandMethod.DEBUG -> {
                    require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    validateDebugArgs(args)
                }
                BrowserCommandMethod.NETWORK -> {
                    require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    validateNetworkArgs(args)
                }
                BrowserCommandMethod.SELECTION -> {
                    require(session != null && session > 0 && control == null && owner == null)
                    if (args.opt("action") == "task_pending") {
                        requireOnlyKeys(args, setOf("action", "afterSequence", "destinationSessionKey"))
                        require(args.has("afterSequence"))
                        safeLong(args, "afterSequence")
                        require((args.opt("destinationSessionKey") as? String)?.matches(Regex("[^\\u0000-\\u001f\\u007f]{1,256}")) == true)
                    } else if (args.opt("action") == "task_claim" || args.opt("action") == "task_admit") {
                        requireOnlyKeys(args, setOf("action", "feedbackId", "destinationSessionKey", "requestId", "payloadDigest"))
                        require((args.opt("feedbackId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                        require((args.opt("destinationSessionKey") as? String)?.matches(Regex("[^\\u0000-\\u001f\\u007f]{1,256}")) == true)
                        require((args.opt("requestId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                        require((args.opt("payloadDigest") as? String)?.matches(Regex("[a-f0-9]{64}")) == true)
                    } else if (args.opt("action") == "task_receipt") {
                        requireOnlyKeys(args, setOf("action", "feedbackId", "destinationSessionKey", "requestId", "deliveryCode"))
                        require((args.opt("feedbackId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                        require((args.opt("destinationSessionKey") as? String)?.matches(Regex("[^\\u0000-\\u001f\\u007f]{1,256}")) == true)
                        require((args.opt("requestId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                        require(args.opt("deliveryCode") in setOf("PROMPT_ACCEPTED", "UNKNOWN", "BLOCKED"))
                    } else if (args.opt("action") == "pending") {
                        requireOnlyKeys(args, setOf("action", "afterSequence", "destinationSessionKey"))
                        require(args.has("afterSequence"))
                        safeLong(args, "afterSequence")
                        require((args.opt("destinationSessionKey") as? String)?.matches(Regex("[^\\u0000-\\u001f\\u007f]{1,256}")) == true)
                    } else if (args.opt("action") == "claim" || args.opt("action") == "admit") {
                        requireOnlyKeys(args, setOf("action", "selectionId", "destinationSessionKey", "requestId", "payloadDigest"))
                        require((args.opt("selectionId") as? String)?.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) == true)
                        require((args.opt("destinationSessionKey") as? String)?.matches(Regex("[^\\u0000-\\u001f\\u007f]{1,256}")) == true)
                        require((args.opt("requestId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                        require((args.opt("payloadDigest") as? String)?.matches(Regex("[a-f0-9]{64}")) == true)
                    } else if (args.opt("action") == "receipt") {
                        requireOnlyKeys(args, setOf("action", "selectionId", "destinationSessionKey", "deliveryCode", "requestId", "imageIncluded"))
                        require((args.opt("selectionId") as? String)?.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) == true)
                        require((args.opt("destinationSessionKey") as? String)?.matches(Regex("[^\\u0000-\\u001f\\u007f]{1,256}")) == true)
                        require((args.opt("deliveryCode") as? String)?.matches(Regex("[A-Z_]{1,64}")) == true)
                        require(!args.has("requestId") || (args.opt("requestId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                        require(!args.has("imageIncluded") || args.opt("imageIncluded") is Boolean)
                    } else {
                        requireOnlyKeys(args, setOf("action"))
                        require(args.opt("action") in setOf("latest", "consume", "clear", "list"))
                    }
                }
                BrowserCommandMethod.PAGE -> {
                    val action = args.opt("action") as? String ?: error("INVALID_ARGUMENT")
                    when (action) {
                        "list" -> {
                            require(session != null && session > 0 && control == null && owner == null)
                            requireOnlyKeys(args, setOf("action"))
                        }
                        "new" -> {
                            require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                            requireOnlyKeys(args, setOf("action"))
                        }
                        "switch", "close" -> {
                            require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                            requireOnlyKeys(args, setOf("action", "pageId"))
                            require(validPageId(args.opt("pageId")))
                        }
                        else -> error("INVALID_ARGUMENT")
                    }
                }
                BrowserCommandMethod.FILE -> validateFileArgs(args, session, control, owner)
                BrowserCommandMethod.UPLOAD -> validateUploadArgs(args, session, control, owner)
            }
            BrowserCommandRequest(
                requestId = requestId,
                method = method,
                deadlineAtElapsedMillis = nowElapsedMillis + budgetMillis,
                sessionGeneration = session,
                controlGeneration = control,
                owner = owner,
                taskId = taskId,
                args = args,
                rawJson = json,
            )
        }

        private fun optionalSafeLong(root: JSONObject, key: String): Long? =
            if (root.has(key) && !root.isNull(key)) safeLong(root, key) else null

        private fun safeLong(root: JSONObject, key: String): Long {
            val number = root.get(key) as? Number ?: error("INVALID_INTEGER")
            require(number is Int || number is Long) { "INVALID_INTEGER" }
            val value = number.toDouble()
            require(value.isFinite() && value >= 0.0 && value <= MAX_SAFE_INTEGER.toDouble())
            require(value % 1.0 == 0.0)
            return value.toLong()
        }

        private fun integer(root: JSONObject, key: String): Int {
            val number = root.opt(key)
            require(number is Int || number is Long) { "INVALID_INTEGER" }
            val value = (number as Number).toLong()
            require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "INVALID_INTEGER" }
            return value.toInt()
        }

        private fun validNode(value: Any?): Boolean =
            value is String && NODE_ID_PATTERN.matches(value)

        private fun finiteNumber(value: Any?): Boolean =
            (value as? Number)?.toDouble()?.isFinite() == true

        private fun validPageId(value: Any?): Boolean =
            value is String && PAGE_ID_PATTERN.matches(value)

        private fun validDownloadId(value: Any?): Boolean =
            value is String && DOWNLOAD_ID_PATTERN.matches(value)

        private fun validateFileArgs(args: JSONObject, session: Long?, control: Long?, owner: String?) {
            val action = args.opt("action") as? String ?: error("INVALID_ARGUMENT")
            when (action) {
                "list" -> {
                    require(session != null && session > 0 && control == null && owner == null)
                    requireOnlyKeys(args, setOf("action", "limit", "beforeId"))
                    if (args.has("limit")) require(integer(args, "limit") in 1..20)
                    if (args.has("beforeId")) require(validDownloadId(args.opt("beforeId")))
                }
                "status" -> {
                    require(session != null && session > 0 && control == null && owner == null)
                    requireOnlyKeys(args, setOf("action", "id"))
                    require(validDownloadId(args.opt("id")))
                }
                "start" -> {
                    require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    requireOnlyKeys(args, setOf("action", "url", "suggestedFileName"))
                    require(httpUrl(args.opt("url"), 2048))
                    if (args.has("suggestedFileName")) {
                        val name = args.opt("suggestedFileName") as? String ?: error("INVALID_ARGUMENT")
                        require(name.length in 1..180 && name.none { it.code < 32 || it.code in 127..159 || it in "/\\:" })
                    }
                }
                "cancel" -> {
                    require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
                    requireOnlyKeys(args, setOf("action", "id"))
                    require(validDownloadId(args.opt("id")))
                }
                else -> error("INVALID_ARGUMENT")
            }
        }

        private fun validateUploadArgs(args: JSONObject, session: Long?, control: Long?, owner: String?) {
            require(session != null && session > 0 && control != null && control > 0 && owner == "ai")
            val action = args.opt("action") as? String ?: error("INVALID_ARGUMENT")
            when (action) {
                "request" -> {
                    requireOnlyKeys(args, setOf("action", "paths", "acceptTypes", "multiple", "summary"))
                    val paths = args.optJSONArray("paths") ?: error("INVALID_ARGUMENT")
                    require(paths.length() in 1..BrowserUploadPolicy.MAX_FILES)
                    val pathValues = (0 until paths.length()).map { paths.opt(it) as? String ?: error("INVALID_ARGUMENT") }
                    require(pathValues.distinct().size == pathValues.size && pathValues.all { BrowserUploadPolicy.validateRelativePath(it) != null })
                    if (args.has("acceptTypes")) {
                        val types = args.optJSONArray("acceptTypes") ?: error("INVALID_ARGUMENT")
                        require(types.length() <= MAX_UPLOAD_ACCEPT_TYPES)
                        require((0 until types.length()).all { validUploadAcceptType(types.opt(it)) })
                    }
                    if (args.has("multiple")) require(args.opt("multiple") is Boolean)
                    if (args.has("summary")) {
                        val summary = args.opt("summary") as? String ?: error("INVALID_ARGUMENT")
                        require(summary.length <= MAX_UPLOAD_SUMMARY_CHARS && summary.none { it.isISOControl() })
                    }
                }
                "list", "status", "cancel" -> {
                    requireOnlyKeys(args, setOf("action", "approvalId"))
                    require(validUploadId(args.opt("approvalId")))
                }
                "activate" -> {
                    requireOnlyKeys(args, setOf("action", "approvalId", "nodeId"))
                    require(validUploadId(args.opt("approvalId")))
                    require(args.opt("nodeId") is String && Regex("b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])").matches(args.getString("nodeId")))
                }
                "submit" -> {
                    requireOnlyKeys(args, setOf("action", "approvalId", "fileIds"))
                    require(validUploadId(args.opt("approvalId")))
                    val ids = args.optJSONArray("fileIds") ?: error("INVALID_ARGUMENT")
                    require(ids.length() in 1..BrowserUploadPolicy.MAX_FILES)
                    val values = (0 until ids.length()).map { ids.optString(it) }
                    require(values.distinct().size == values.size && values.all(::validUploadId))
                }
                else -> error("INVALID_ARGUMENT")
            }
        }

        private fun validUploadAcceptType(value: Any?): Boolean {
            val type = value as? String ?: return false
            if (type.length !in 1..128 || type.any { it.isISOControl() }) return false
            if (type.startsWith(".")) return type.matches(Regex("\\.[A-Za-z0-9]{1,16}"))
            return type == "*/*" || type.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/(?:[A-Za-z0-9!#$&^_.+-]+|\\*)"))
        }

        private fun validUploadId(value: Any?): Boolean = value is String && value.matches(Regex("[a-f0-9]{32}"))

        private fun validateDebugArgs(args: JSONObject) {
            val action = args.optString("action")
            when (action) {
                "rendering" -> {
                    when (args.opt("operation")) {
                        "get" -> requireOnlyKeys(args, setOf("action", "operation"))
                        "set" -> {
                            requireOnlyKeys(args, setOf("action", "operation", "key", "value", "expectedRevision"))
                            safeLong(args, "expectedRevision")
                            when (args.opt("key")) {
                                "userAgentMode" -> require(args.opt("value") in setOf("default", "mobile", "desktop"))
                                "layoutWidthMode" -> require(args.opt("value") in setOf("device", "wide"))
                                "overviewMode" -> require(args.opt("value") is Boolean)
                                "textScalePercent" -> require(integer(args, "value") in 50..200)
                                else -> error("INVALID_ARGUMENT")
                            }
                        }
                        else -> error("INVALID_ARGUMENT")
                    }
                }
                "status" -> requireOnlyKeys(args, setOf("action"))
                "console" -> {
                    requireOnlyKeys(args, setOf("action", "limit"))
                    if (args.has("limit")) require(integer(args, "limit") in 1..50)
                }
                "evaluate" -> {
                    requireOnlyKeys(args, setOf("action", "expression"))
                    val expression = args.opt("expression") as? String ?: error("INVALID_ARGUMENT")
                    require(expression.length in 1..8192)
                }
                "dom" -> {
                    requireOnlyKeys(args, setOf("action", "selector"))
                    if (args.has("selector")) require(text(args.opt("selector"), 1, 512))
                }
                "ax_tree", "ax_observe" -> {
                    requireOnlyKeys(args, setOf("action", "maxNodes", "maxDepth", "includeIgnored"))
                    if (args.has("maxNodes")) require(integer(args, "maxNodes") in 1..256)
                    if (args.has("maxDepth")) require(integer(args, "maxDepth") in 1..8)
                    if (args.has("includeIgnored")) require(args.opt("includeIgnored") is Boolean)
                }
                "ax_click", "ax_type", "ax_scroll" -> {
                    val fields = when (action) {
                        "ax_click" -> setOf("action", "actionRef")
                        "ax_type" -> setOf("action", "actionRef", "text", "replace")
                        else -> setOf("action", "actionRef", "direction", "steps")
                    }
                    requireOnlyKeys(args, fields)
                    require(pattern(args.opt("actionRef"), "axa:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}:[0-9]{1,3}"))
                    require(args.getString("actionRef").substringAfterLast(':').toInt() in 0..255)
                    if (action == "ax_type") {
                        require(text(args.opt("text"), 0, 4096))
                        if (args.has("replace")) require(args.opt("replace") is Boolean)
                    }
                    if (action == "ax_scroll") {
                        require(args.opt("direction") in setOf("up", "down"))
                        if (args.has("steps")) require(integer(args, "steps") in 1..10)
                    }
                }
                "emulate" -> {
                    requireOnlyKeys(args, setOf("action", "width", "height", "mobile", "clear"))
                    if (args.has("clear")) require(args.opt("clear") is Boolean)
                    if (args.opt("clear") == true) {
                        requireOnlyKeys(args, setOf("action", "clear"))
                    } else {
                        require(integer(args, "width") in 240..2560 && integer(args, "height") in 240..2560)
                        require(args.opt("mobile") is Boolean)
                    }
                }
                "interact" -> validateInteraction(args)
                else -> error("INVALID_ARGUMENT")
            }
        }

        private fun validateCollectArgs(args: JSONObject) {
            require(args.opt("action") == "window")
            requireOnlyKeys(args, setOf(
                "action", "containerSelector", "messageSelector", "keyAttribute", "authorSelector", "authorAttribute",
                "contentSelector", "orderAttribute", "totalCountSelector", "totalCountAttribute", "declaredRange",
                "maxRecords", "maxContentChars", "excludeSelector",
            ))
            for (key in setOf("containerSelector", "messageSelector")) require(text(args.opt(key), 1, 512))
            for (key in setOf("authorSelector", "contentSelector", "totalCountSelector", "excludeSelector")) {
                if (args.has(key)) require(text(args.opt(key), 1, 512))
            }
            for (key in setOf("keyAttribute", "authorAttribute", "orderAttribute", "totalCountAttribute")) {
                if (args.has(key)) require(pattern(args.opt(key), "[A-Za-z_:][A-Za-z0-9_.:-]{0,63}"))
            }
            require(text(args.opt("declaredRange"), 1, 128))
            require((args.getString("declaredRange")).none { it.isISOControl() })
            require(integer(args, "maxRecords") in 1..200)
            require(integer(args, "maxContentChars") in 1..4096)
        }

        private fun validateInteraction(args: JSONObject) {
            when (args.optString("kind")) {
                "key" -> {
                    requireOnlyKeys(args, setOf("action", "kind", "key"))
                    require(text(args.opt("key"), 1, 32))
                }
                "tap" -> if (args.has("selector")) {
                    requireOnlyKeys(args, setOf("action", "kind", "selector"))
                    require(text(args.opt("selector"), 1, 512))
                } else {
                    requireOnlyKeys(args, setOf("action", "kind", "x", "y"))
                    require(coordinate(args.opt("x")) && coordinate(args.opt("y")))
                }
                "drag" -> {
                    requireOnlyKeys(args, setOf("action", "kind", "x", "y", "toX", "toY"))
                    require(listOf("x", "y", "toX", "toY").all { coordinate(args.opt(it)) })
                }
                "select" -> {
                    requireOnlyKeys(args, setOf("action", "kind", "selector", "value"))
                    require(text(args.opt("selector"), 1, 512) && text(args.opt("value"), 0, 4096))
                }
                else -> error("INVALID_ARGUMENT")
            }
        }

        private fun validateNetworkArgs(args: JSONObject) {
            when (args.optString("action")) {
                "start" -> {
                    requireOnlyKeys(args, setOf("action", "includeBodies", "includeRequestBodies"))
                    if (args.has("includeBodies")) require(args.opt("includeBodies") is Boolean)
                    if (args.has("includeRequestBodies")) require(args.opt("includeRequestBodies") is Boolean)
                }
                "har", "export_frames" -> {
                    requireOnlyKeys(args, setOf("action", "includeBodies"))
                    if (args.has("includeBodies")) require(args.opt("includeBodies") is Boolean)
                }
                "stop", "clear_rules", "paused" -> requireOnlyKeys(args, setOf("action"))
                "list" -> {
                    requireOnlyKeys(args, setOf("action", "cursor", "limit"))
                    if (args.has("cursor")) require(integer(args, "cursor") >= 0)
                    if (args.has("limit")) require(integer(args, "limit") in 1..20)
                }
                "frames" -> {
                    requireOnlyKeys(args, setOf("action", "cursor", "limit"))
                    if (args.has("cursor")) require(integer(args, "cursor") >= 0)
                    if (args.has("limit")) require(integer(args, "limit") in 1..20)
                }
                "body" -> {
                    requireOnlyKeys(args, setOf("action", "entryId"))
                    require(identifier(args.opt("entryId")))
                }
                "replay_prepare" -> {
                    requireOnlyKeys(args, setOf("action", "entryId"))
                    require(identifier(args.opt("entryId")))
                }
                "replay_status" -> {
                    requireOnlyKeys(args, setOf("action", "requestId"))
                    require((args.opt("requestId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                }
                "replay" -> {
                    val keys = args.keys().asSequence().toSet()
                    val readOnly = keys == setOf("action", "entryId")
                    val write = keys == setOf("action", "entryId", "riskToken", "captureId")
                    require(readOnly || write)
                    require(identifier(args.opt("entryId")))
                    if (write) {
                        require((args.opt("riskToken") as? String)?.matches(Regex("[a-f0-9]{32}")) == true)
                        require((args.opt("captureId") as? String)?.matches(Regex("cap-[a-f0-9]{20}")) == true)
                    }
                }
                "rules" -> {
                    requireOnlyKeys(args, setOf("action", "rules"))
                    val rules = args.optJSONArray("rules") ?: error("INVALID_ARGUMENT")
                    require(rules.length() <= 8)
                    for (i in 0 until rules.length()) validateRule(rules.optJSONObject(i) ?: error("INVALID_ARGUMENT"))
                }
                "resolve" -> validateResolve(args)
                else -> error("INVALID_ARGUMENT")
            }
        }

        private fun validateRule(rule: JSONObject) {
            requireOnlyKeys(rule, setOf("urlPattern", "stage", "method", "action", "headers", "url", "postData", "body", "responseCode"))
            require(text(rule.opt("urlPattern"), 1, 256))
            val stage = rule.optString("stage")
            val action = rule.optString("action")
            require(stage in setOf("request", "response") && action in setOf("continue", "block", "modify", "fulfill"))
            if (rule.has("method")) require(pattern(rule.opt("method"), "[A-Za-z][A-Za-z0-9_-]{0,15}"))
            if (rule.has("headers")) validateHeaders(rule.optJSONObject("headers") ?: error("INVALID_ARGUMENT"))
            if (rule.has("url")) require(httpUrl(rule.opt("url"), 2048))
            if (rule.has("postData")) require(text(rule.opt("postData"), 0, 8192))
            if (rule.has("body")) require(text(rule.opt("body"), 0, 8192))
            if (rule.has("responseCode")) require(integer(rule, "responseCode") in 200..599 && stage == "response")
            when (action) {
                "continue", "block" -> requireOnlyKeys(rule, setOf("urlPattern", "stage", "method", "action"))
                "modify" -> {
                    val allowed = if (stage == "request") setOf("urlPattern", "stage", "method", "action", "headers", "url", "postData")
                        else setOf("urlPattern", "stage", "method", "action", "headers", "url", "body", "responseCode")
                    requireOnlyKeys(rule, allowed)
                    require(listOf("headers", "url", if (stage == "request") "postData" else "body", "responseCode").any(rule::has))
                }
                "fulfill" -> {
                    require(stage == "response")
                    requireOnlyKeys(rule, setOf("urlPattern", "stage", "method", "action", "headers", "body", "responseCode"))
                    require(rule.has("body") || rule.has("responseCode"))
                }
            }
        }

        private fun validateResolve(args: JSONObject) {
            requireOnlyKeys(args, setOf("action", "pausedId", "decision", "headers", "url", "method", "postData", "body", "responseCode"))
            require(identifier(args.opt("pausedId")))
            val decision = args.optString("decision")
            require(decision in setOf("continue", "block", "modify", "fulfill"))
            if (args.has("headers")) validateHeaders(args.optJSONObject("headers") ?: error("INVALID_ARGUMENT"))
            if (args.has("url")) require(httpUrl(args.opt("url"), 2048))
            if (args.has("method")) require(pattern(args.opt("method"), "[A-Za-z][A-Za-z0-9_-]{0,15}"))
            if (args.has("postData")) require(text(args.opt("postData"), 0, 8192))
            if (args.has("body")) require(text(args.opt("body"), 0, 8192))
            if (args.has("responseCode")) require(integer(args, "responseCode") in 200..599)
            when (decision) {
                "continue", "block" -> requireOnlyKeys(args, setOf("action", "pausedId", "decision"))
                "modify" -> require(listOf("headers", "url", "method", "postData", "body", "responseCode").any(args::has))
                "fulfill" -> {
                    requireOnlyKeys(args, setOf("action", "pausedId", "decision", "headers", "body", "responseCode"))
                    require(args.has("body") || args.has("responseCode"))
                }
            }
        }

        private fun validateHeaders(headers: JSONObject) {
            require(headers.length() <= 16)
            val keyPattern = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
            val keys = headers.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = headers.opt(key) as? String ?: error("INVALID_ARGUMENT")
                require(keyPattern.matches(key) && value.length <= 1024 && !value.contains('\r') && !value.contains('\n'))
            }
        }

        private fun text(value: Any?, min: Int, max: Int): Boolean = value is String && value.length in min..max
        private fun coordinate(value: Any?): Boolean = (value as? Number)?.toDouble()?.let { it.isFinite() && it in 0.0..10_000.0 } == true
        private fun identifier(value: Any?): Boolean = value is String && NODE_ID_PATTERN.matches(value)
        private fun pattern(value: Any?, regex: String): Boolean = value is String && Regex(regex).matches(value)
        private fun validEventId(value: Any?): Boolean = pattern(value, "[a-f0-9]{32}")
        private fun validateActionEventCredentials(args: JSONObject, base: Set<String>) {
            val hasId = args.has("eventSubscriptionId")
            val hasNonce = args.has("eventNonce")
            require(hasId == hasNonce)
            val allowed = if (hasId) base + setOf("eventSubscriptionId", "eventNonce") else base
            requireOnlyKeys(args, allowed)
            if (hasId) {
                require(validEventId(args.opt("eventSubscriptionId")))
                require(pattern(args.opt("eventNonce"), "[a-f0-9]{64}"))
            }
        }
        private fun httpUrl(value: Any?, max: Int): Boolean {
            if (value !is String || value.length !in 1..max) return false
            val uri = runCatching { URI(value) }.getOrNull() ?: return false
            return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }

        private fun requireOnlyKeys(value: JSONObject, allowed: Set<String>) {
            val keys = value.keys()
            while (keys.hasNext()) require(keys.next() in allowed) { "UNKNOWN_FIELD" }
        }

        private fun validateTaskText(args: JSONObject) {
            listOf("title" to 120, "goal" to 4000, "notes" to 2000).forEach { (key, max) ->
                if (args.has(key)) require(args.opt(key) is String && args.getString(key).length <= max)
            }
        }

        private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        private val NODE_ID_PATTERN = Regex("[A-Za-z0-9_.:-]{1,128}")
        private val PAGE_ID_PATTERN = Regex("pg-[A-Fa-f0-9]{20}")
        private val DOWNLOAD_ID_PATTERN = Regex("[a-f0-9]{32}")
    }

}

internal fun commandResponse(
    requestId: String,
    code: String,
    executed: Boolean?,
    state: String? = null,
    sessionGeneration: Long? = null,
    controlGeneration: Long? = null,
    owner: String? = null,
    data: JSONObject = JSONObject(),
): JSONObject = JSONObject()
    .put("version", 1)
    .put("requestId", requestId)
    .put("code", code)
    .put("executed", executed ?: JSONObject.NULL)
    .put("state", state ?: JSONObject.NULL)
    .put("sessionGeneration", sessionGeneration ?: JSONObject.NULL)
    .put("controlGeneration", controlGeneration ?: JSONObject.NULL)
    .put("owner", owner ?: JSONObject.NULL)
    .put("data", data)

/** Distinguishes a cancellation-request receipt from the original action's terminal result. */
internal object BrowserCancellationContract {
    val stages = setOf(
        "queued", "transport", "capture_wait", "event_wait", "condition_wait", "dispatch",
    )
    val reasons = setOf(
        "cancel_accepted", "cancel_before_admission", "request_in_flight", "request_not_found",
        "request_finished", "transport_unavailable", "invalid_runtime_receipt",
    )

    fun receipt(stage: String, reason: String): JSONObject {
        require(stage in stages) { "invalid cancellation stage" }
        require(reason in reasons) { "invalid cancellation reason" }
        return JSONObject()
            .put("receiptKind", "cancel_request")
            .put("cancelStage", stage)
            .put("reason", reason)
            .put("actionOutcome", "unknown")
            .put("nextAction", "read_original_request_result")
            .put("automaticRetryAllowed", false)
    }
}

internal fun truncateUtf8(value: String, maxBytes: Int): Pair<String, Boolean> {
    if (value.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return value to false
    val result = StringBuilder()
    var used = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        val encodedBytes = when {
            codePoint <= 0x7f -> 1
            codePoint <= 0x7ff -> 2
            codePoint <= 0xffff -> 3
            else -> 4
        }
        if (used + encodedBytes > maxBytes) break
        result.appendCodePoint(codePoint)
        used += encodedBytes
        index += Character.charCount(codePoint)
    }
    return result.toString() to true
}
