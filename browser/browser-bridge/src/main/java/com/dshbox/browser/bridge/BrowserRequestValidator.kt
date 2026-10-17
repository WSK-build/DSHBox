package com.dshbox.browser.bridge

import java.net.URI
import org.json.JSONObject

internal object BrowserRequestValidator {
    internal data class ValidationResult(val valid: Boolean, val stage: String)
    private val methods = setOf(
        "status", "open", "observe", "wait", "navigate", "locate", "click", "type", "scroll", "screenshot", "close",
        "debug", "network", "selection", "page", "file", "upload", "task", "collect", "event", "control",
    )
    private val idPattern = Regex("^[A-Za-z0-9_.:-]{1,128}$")
    private val observationRefPattern = Regex("^b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])$")
    private val requestIdPattern = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val requestKeys = setOf(
        "version", "requestId", "method", "sessionGeneration", "controlGeneration",
        "owner", "deadlineAtMillis", "args", "taskId",
    )

    fun validate(request: JSONObject, nowMillis: Long): ValidationResult {
        val valid = isValidRequest(request, nowMillis)
        return ValidationResult(valid, if (valid) "accepted" else "request_schema")
    }

    fun isValid(request: JSONObject, nowMillis: Long): Boolean = validate(request, nowMillis).valid

    private fun isValidRequest(request: JSONObject, nowMillis: Long): Boolean = runCatching {
        if (request.keys().asSequence().any { it !in requestKeys }) return false
        if (integer(request.opt("version")) != 1L) return false
        val method = request.opt("method") as? String ?: return false
        if (method !in methods) return false
        if (request.has("taskId") &&
            ((request.opt("taskId") as? String)?.matches(Regex("^[A-Za-z0-9_-]{1,64}$")) != true || method == "task")) return false
        if (method == "upload" && !request.has("taskId")) return false
        val id = request.opt("requestId") as? String ?: return false
        if (!requestIdPattern.matches(id)) return false
        val deadline = integer(request.opt("deadlineAtMillis")) ?: return false
        if (deadline <= nowMillis || deadline - nowMillis > MAX_BUDGET_MS) return false
        val args = request.optJSONObject("args") ?: return false
        if ((request.has("sessionGeneration") && request.isNull("sessionGeneration")) ||
            (request.has("controlGeneration") && request.isNull("controlGeneration"))) return false
        val hasSession = request.has("sessionGeneration") && !request.isNull("sessionGeneration")
        val hasControl = request.has("controlGeneration") && !request.isNull("controlGeneration")
        if (method == "control") {
            if (hasSession || hasControl || request.has("owner") || request.has("taskId")) return false
            return when (args.opt("action")) {
                "request" -> args.only("action", "summary", "taskId") && validText(args.opt("summary"), 1, 1000) &&
                    (args.opt("summary") as String).isNotBlank() &&
                    (!args.has("taskId") || (args.opt("taskId") as? String)?.matches(Regex("^[A-Za-z0-9_-]{1,64}$")) == true)
                "status", "cancel" -> args.only("action", "approvalId") &&
                    (args.opt("approvalId") as? String)?.matches(Regex("^[a-f0-9]{32}$")) == true
                else -> false
            }
        }
        if (method == "open" && !hasSession && request.has("taskId")) return false
        val action = args.opt("action") as? String
        val pageList = method == "page" && action == "list"
        val fileRead = method == "file" && action in setOf("list", "status")
        if (hasSession != hasControl && method !in setOf("observe", "wait", "selection") && !pageList && !fileRead) return false
        if (method in setOf("observe", "wait", "selection") && !hasSession) return false
        if (hasSession && !isGeneration(request.opt("sessionGeneration"))) return false
        if (hasControl && !isGeneration(request.opt("controlGeneration"))) return false

        when (method) {
            "status" -> {
                if (hasSession || hasControl || request.has("owner") || !args.only()) return false
            }
            "task" -> {
                val taskAction = args.opt("action") as? String ?: return false
                val needsAiControl = taskAction in setOf("begin", "complete", "reconcile", "resume")
                if (needsAiControl) {
                    if (!validActionOwner(request)) return false
                } else if (hasSession || hasControl || request.has("owner")) return false
                when (taskAction) {
                    "begin" -> {
                        if (!args.only("action", "durationMs", "operationLimit", "title", "goal", "notes")) return false
                        if (args.has("durationMs") && integer(args.opt("durationMs"))?.let { it in 1L..14_400_000L } != true) return false
                        if (args.has("operationLimit") && integer(args.opt("operationLimit"))?.let { it in 1L..2000L } != true) return false
                        if (!validTaskText(args)) return false
                    }
                    "list" -> if (!args.only("action", "offset", "limit") ||
                        (args.has("offset") && integer(args.opt("offset"))?.let { it >= 0 } != true) ||
                        (args.has("limit") && integer(args.opt("limit"))?.let { it in 1L..100L } != true)) return false
                    "status", "pause", "wait", "complete", "fail", "reconcile", "resume", "delete" -> {
                        if (!args.only("action", "taskId", "reason", "expectedRevision") ||
                            (args.opt("taskId") as? String)?.matches(Regex("^[A-Za-z0-9_-]{1,64}$")) != true) return false
                        if (taskAction == "delete" && integer(args.opt("expectedRevision"))?.let { it >= 0 } != true) return false
                        if (taskAction != "delete" && args.has("expectedRevision")) return false
                        if (taskAction == "wait") {
                            if ((args.opt("reason") as? String) !in setOf("page_load", "page_condition", "user_action", "user_authentication", "recovery_check")) return false
                        } else if (taskAction == "fail") {
                            if (args.has("reason") && !validTaskFailureReason(args.opt("reason"))) return false
                        } else if (args.has("reason")) return false
                    }
                    "update" -> if (!args.only("action", "taskId", "expectedRevision", "title", "goal", "notes") ||
                        (args.opt("taskId") as? String)?.matches(Regex("^[A-Za-z0-9_-]{1,64}$")) != true ||
                        integer(args.opt("expectedRevision"))?.let { it >= 0 } != true ||
                        !validTaskText(args) || !(args.has("title") || args.has("goal") || args.has("notes"))) return false
                    else -> return false
                }
            }
            "collect" -> {
                if (!validActionOwner(request) || args.opt("action") != "window" ||
                    !args.only("action", "containerSelector", "messageSelector", "keyAttribute", "authorSelector", "authorAttribute",
                        "contentSelector", "orderAttribute", "totalCountSelector", "totalCountAttribute", "declaredRange",
                        "maxRecords", "maxContentChars", "excludeSelector")) return false
                if (!validText(args.opt("containerSelector"), 1, 512) || !validText(args.opt("messageSelector"), 1, 512)) return false
                for (key in listOf("authorSelector", "contentSelector", "totalCountSelector", "excludeSelector")) {
                    if (args.has(key) && !validText(args.opt(key), 1, 512)) return false
                }
                for (key in listOf("keyAttribute", "authorAttribute", "orderAttribute", "totalCountAttribute")) {
                    if (args.has(key) && ((args.opt(key) as? String)?.matches(Regex("[A-Za-z_:][A-Za-z0-9_.:-]{0,63}")) != true)) return false
                }
                val range = args.opt("declaredRange") as? String ?: return false
                if (range.length !in 1..128 || range.any { it.isISOControl() }) return false
                if (integer(args.opt("maxRecords"))?.let { it in 1L..200L } != true ||
                    integer(args.opt("maxContentChars"))?.let { it in 1L..4096L } != true) return false
            }
            "observe" -> {
                if (hasControl || request.has("owner") || !args.only("maxTextChars")) return false
                if (!validGeneration(request.opt("sessionGeneration"))) return false
                if (args.has("maxTextChars")) {
                    val maxTextChars = integer(args.opt("maxTextChars")) ?: return false
                    if (maxTextChars !in 1L..4096L) return false
                }
            }
            "wait" -> {
                if (hasControl || request.has("owner") || !validGeneration(request.opt("sessionGeneration")) ||
                    !args.only("condition", "timeoutMs", "url", "text", "selector", "state", "pollIntervalMs")) return false
                val condition = args.opt("condition") as? String ?: return false
                if (condition !in setOf("load", "url", "text", "selector")) return false
                if (args.has("timeoutMs") && integer(args.opt("timeoutMs"))?.let { it in 1L..30_000L } != true) return false
                if (args.has("pollIntervalMs") && integer(args.opt("pollIntervalMs"))?.let { it in 100L..1000L } != true) return false
                if (args.has("state") && (condition != "selector" || (args.opt("state") as? String) !in setOf("attached", "visible", "hidden"))) return false
                val field = when (condition) { "url" -> "url"; "text" -> "text"; "selector" -> "selector"; else -> null }
                if (field == null) {
                    if (args.has("url") || args.has("text") || args.has("selector") || args.has("state")) return false
                } else {
                    if (!validText(args.opt(field), 1, 2048)) return false
                    if (setOf("url", "text", "selector").any { it != field && args.has(it) }) return false
                    if (field == "url" && !validHttpUrl(args.opt(field), 2048)) return false
                    if (condition != "selector" && args.has("state")) return false
                }
            }
            "navigate" -> {
                if (!validActionOwner(request) || !validActionEventCredentials(args, setOf("action", "thumbnail")) ||
                    (args.opt("action") as? String) !in setOf("back", "forward", "reload")) return false
                if (args.has("thumbnail") && args.opt("thumbnail") !is Boolean) return false
            }
            "event" -> {
                if (!validActionOwner(request) || request.opt("taskId") !is String) return false
                when (args.opt("action")) {
                    "arm" -> {
                        if (!args.only("action", "eventKinds", "ttlMs")) return false
                        val kinds = args.optJSONArray("eventKinds") ?: return false
                        if (kinds.length() !in 1..6) return false
                        val values = (0 until kinds.length()).map { kinds.optString(it, "") }
                        if (values.distinct().size != values.size || values.any { it !in EVENT_KINDS }) return false
                        if (args.has("ttlMs") && integer(args.opt("ttlMs"))?.let { it in 1L..30_000L } != true) return false
                    }
                    "wait" -> if (!args.only("action", "subscriptionId", "timeoutMs") ||
                        !validPattern(args.opt("subscriptionId"), "[a-f0-9]{32}") ||
                        (args.has("timeoutMs") && integer(args.opt("timeoutMs"))?.let { it in 1L..30_000L } != true)) return false
                    "cancel" -> if (!args.only("action", "subscriptionId") ||
                        !validPattern(args.opt("subscriptionId"), "[a-f0-9]{32}")) return false
                    "resolve_dialog" -> {
                        if (!args.only("action", "eventId", "decision", "promptText") ||
                            !validPattern(args.opt("eventId"), "[a-f0-9]{32}") ||
                            (args.opt("decision") as? String) !in setOf("accept", "dismiss", "prompt")) return false
                        if (args.opt("decision") == "prompt") {
                            if (!validText(args.opt("promptText"), 0, 4096)) return false
                        } else if (args.has("promptText")) return false
                    }
                    else -> return false
                }
            }
            "locate" -> {
                if (!validActionOwner(request) || !args.only("strategy", "value", "exact", "name", "scopeRef", "partial")) return false
                val strategy = args.opt("strategy") as? String ?: return false
                if (strategy !in setOf("role", "label", "placeholder", "text", "css") ||
                    !validText(args.opt("value"), 1, 2048) || args.opt("exact") !is Boolean) return false
                if (args.has("partial") && args.opt("partial") !is Boolean) return false
                if (strategy == "role") {
                    if (args.has("name") && !validText(args.opt("name"), 1, 2048)) return false
                } else if (args.has("name")) return false
                if (args.has("scopeRef") && !validObservationReference(args.opt("scopeRef"))) return false
            }
            "open" -> {
                if (request.opt("owner") != "ai" || !args.only("url", "thumbnail")) return false
                if (args.has("thumbnail") && args.opt("thumbnail") !is Boolean) return false
                if (args.opt("thumbnail") == true && !args.has("url")) return false
                if (hasSession && (!validGeneration(request.opt("sessionGeneration")) ||
                        !validGeneration(request.opt("controlGeneration")))) return false
                if (args.has("url")) {
                    val text = args.opt("url") as? String ?: return false
                    if (text.length > 2048) return false
                    val uri = URI(text)
                    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) return false
                }
            }
            "click" -> {
                if (!validActionOwner(request)) return false
                val nodeClick = args.has("nodeId") && !args.has("screenshotId") && !args.has("x") && !args.has("y")
                val visualClick = !args.has("nodeId") && args.has("screenshotId") && args.has("x") && args.has("y")
                if (!nodeClick && !visualClick) return false
                val credentials = setOf("eventSubscriptionId", "eventNonce").filter(args::has).toSet()
                if (credentials.isNotEmpty() && credentials != setOf("eventSubscriptionId", "eventNonce")) return false
                val allowed = (if (nodeClick) setOf("nodeId") else setOf("screenshotId", "x", "y")) + credentials
                if (!args.only(*allowed.toTypedArray())) return false
                if (credentials.isNotEmpty() && (!validPattern(args.opt("eventSubscriptionId"), "[a-f0-9]{32}") ||
                        !validPattern(args.opt("eventNonce"), "[a-f0-9]{64}"))) return false
                if (nodeClick) {
                    if (!validNode(args.opt("nodeId"))) return false
                } else if (
                    (args.opt("screenshotId") as? String)?.matches(Regex("[a-f0-9]{32}")) != true ||
                    !validCoordinate(args.opt("x")) || !validCoordinate(args.opt("y"))) return false
            }
            "type" -> {
                if (!validActionOwner(request) || !args.only("nodeId", "text", "replace")) return false
                if (!validNode(args.opt("nodeId")) || args.opt("text") !is String ||
                    (args.opt("text") as String).length > 4096 ||
                    (args.has("replace") && args.opt("replace") !is Boolean)) return false
            }
            "scroll" -> {
                if (!validActionOwner(request) || !args.only("nodeId", "direction", "steps")) return false
                if (args.has("nodeId") && !validNode(args.opt("nodeId"))) return false
                if ((args.opt("direction") as? String) !in setOf("up", "down")) return false
                if (args.has("steps")) {
                    val steps = integer(args.opt("steps")) ?: return false
                    if (steps !in 1L..10L) return false
                }
            }
            "screenshot", "close" -> {
                if (!validActionOwner(request) || !args.only()) return false
            }
            "debug" -> {
                if (!validAdvancedOwner(request) || !validDebugArgs(args)) return false
            }
            "network" -> {
                if (!validAdvancedOwner(request) || !validNetworkArgs(args)) return false
            }
            "selection" -> {
                if (hasControl || request.has("owner") || !validGeneration(request.opt("sessionGeneration")) ||
                    !validSelectionArgs(args)) return false
            }
            "page" -> {
                when (action) {
                    "list" -> {
                        if (!validGeneration(request.opt("sessionGeneration")) || hasControl || request.has("owner") ||
                            !args.only("action")) return false
                    }
                    "new" -> {
                        if (!validActionOwner(request) || !args.only("action")) return false
                    }
                    "switch", "close" -> {
                        if (!validActionOwner(request) || !args.only("action", "pageId") ||
                            !validPattern(args.opt("pageId"), "pg-[A-Fa-f0-9]{20}")) return false
                    }
                    else -> return false
                }
            }
            "file" -> {
                if (!validFileArgs(args, request, hasControl)) return false
            }
            "upload" -> {
                if (!validActionOwner(request) || !validUploadArgs(args, hasControl)) return false
            }
        }
        true
        }.getOrDefault(false)

    private fun validActionOwner(request: JSONObject): Boolean =
        request.opt("owner") == "ai" &&
            validGeneration(request.opt("sessionGeneration")) &&
            validGeneration(request.opt("controlGeneration"))

    private fun validAdvancedOwner(request: JSONObject): Boolean =
        request.opt("owner") == "ai" &&
            validGeneration(request.opt("sessionGeneration")) &&
            validGeneration(request.opt("controlGeneration"))

    private fun validGeneration(value: Any?): Boolean = isGeneration(value) && integer(value)!! > 0L

    private fun validActionEventCredentials(args: JSONObject, base: Set<String>): Boolean {
        val hasId = args.has("eventSubscriptionId")
        val hasNonce = args.has("eventNonce")
        if (hasId != hasNonce) return false
        val allowed = if (hasId) base + setOf("eventSubscriptionId", "eventNonce") else base
        if (!args.only(*allowed.toTypedArray())) return false
        return !hasId || (validPattern(args.opt("eventSubscriptionId"), "[a-f0-9]{32}") &&
            validPattern(args.opt("eventNonce"), "[a-f0-9]{64}"))
    }

    private fun validTaskText(args: JSONObject): Boolean =
        (!args.has("title") || validText(args.opt("title"), 0, 120)) &&
            (!args.has("goal") || validText(args.opt("goal"), 0, 4000)) &&
            (!args.has("notes") || validText(args.opt("notes"), 0, 2000))

    private fun validTaskFailureReason(value: Any?): Boolean =
        value is String && value.length in 1..1000 && value.isNotBlank()

    private val EVENT_KINDS = setOf("navigation_started", "navigation_finished", "navigation_failed", "new_window", "download", "javascript_dialog")

    private fun validDebugArgs(args: JSONObject): Boolean {
        val action = args.opt("action") as? String ?: return false
        return when (action) {
            "status" -> args.only("action")
            "console" -> args.only("action", "limit") &&
                (!args.has("limit") || integer(args.opt("limit"))?.let { it in 1L..50L } == true)
            "evaluate" -> args.only("action", "expression") &&
                (args.opt("expression") as? String)?.let { it.length in 1..8192 } == true
            "dom" -> args.only("action", "selector") &&
                (!args.has("selector") || validText(args.opt("selector"), 1, 512))
            "emulate" -> {
                args.only("action", "width", "height", "mobile", "clear") &&
                    (!args.has("clear") || args.opt("clear") is Boolean) &&
                    (if (args.opt("clear") == true) {
                        args.only("action", "clear")
                    } else {
                        integer(args.opt("width"))?.let { it in 240L..2560L } == true &&
                            integer(args.opt("height"))?.let { it in 240L..2560L } == true &&
                            args.opt("mobile") is Boolean
                    })
            }
            "interact" -> validInteraction(args)
            else -> false
        }
    }

    private fun validInteraction(args: JSONObject): Boolean {
        val kind = args.opt("kind") as? String ?: return false
        return when (kind) {
            "key" -> args.only("action", "kind", "key") && validText(args.opt("key"), 1, 32)
            "tap" -> when {
                args.has("selector") -> args.only("action", "kind", "selector") && validText(args.opt("selector"), 1, 512)
                else -> args.only("action", "kind", "x", "y") && validCoordinate(args.opt("x")) && validCoordinate(args.opt("y"))
            }
            "drag" -> args.only("action", "kind", "x", "y", "toX", "toY") &&
                validCoordinate(args.opt("x")) && validCoordinate(args.opt("y")) &&
                validCoordinate(args.opt("toX")) && validCoordinate(args.opt("toY"))
            "select" -> args.only("action", "kind", "selector", "value") &&
                validText(args.opt("selector"), 1, 512) && validText(args.opt("value"), 0, 4096)
            else -> false
        }
    }

    private fun validNetworkArgs(args: JSONObject): Boolean {
        val action = args.opt("action") as? String ?: return false
        return when (action) {
            "start" -> args.only("action", "includeBodies", "includeRequestBodies") &&
                (!args.has("includeBodies") || args.opt("includeBodies") is Boolean) &&
                (!args.has("includeRequestBodies") || args.opt("includeRequestBodies") is Boolean)
            "har", "export_frames" -> args.only("action", "includeBodies") &&
                (!args.has("includeBodies") || args.opt("includeBodies") is Boolean)
            "stop", "clear_rules", "paused" -> args.only("action")
            "list" -> args.only("action", "cursor", "limit") &&
                (!args.has("cursor") || integer(args.opt("cursor"))?.let { it >= 0L } == true) &&
                (!args.has("limit") || integer(args.opt("limit"))?.let { it in 1L..20L } == true)
            "frames" -> args.only("action", "cursor", "limit") &&
                (!args.has("cursor") || integer(args.opt("cursor"))?.let { it >= 0L } == true) &&
                (!args.has("limit") || integer(args.opt("limit"))?.let { it in 1L..20L } == true)
            "body", "replay_prepare" -> args.only("action", "entryId") && validIdentifier(args.opt("entryId"))
            "replay_status" -> args.only("action", "requestId") && validPattern(args.opt("requestId"), "[a-f0-9]{32}")
            "replay" -> {
                val readOnly = args.only("action", "entryId")
                val write = args.only("action", "entryId", "riskToken", "captureId")
                validIdentifier(args.opt("entryId")) && (readOnly || (write &&
                    validPattern(args.opt("riskToken"), "[a-f0-9]{32}") &&
                    validPattern(args.opt("captureId"), "cap-[a-f0-9]{20}")))
            }
            "rules" -> {
                if (!args.only("action", "rules")) return false
                val rules = args.optJSONArray("rules") ?: return false
                if (rules.length() > 8) return false
                (0 until rules.length()).all { validRule(rules.opt(it)) }
            }
            "resolve" -> validResolveArgs(args)
            else -> false
        }
    }

    private fun validRule(value: Any?): Boolean {
        val rule = value as? JSONObject ?: return false
        if (!rule.only("urlPattern", "stage", "method", "action", "headers", "url", "postData", "body", "responseCode")) return false
        if (!validText(rule.opt("urlPattern"), 1, 256) ||
            (rule.opt("stage") as? String) !in setOf("request", "response") ||
            (rule.opt("action") as? String) !in setOf("continue", "block", "modify", "fulfill")) return false
        if (rule.has("method") && !validPattern(rule.opt("method"), "[A-Za-z][A-Za-z0-9_-]{0,15}")) return false
        if (rule.has("headers") && !validHeaders(rule.optJSONObject("headers"))) return false
        if (rule.has("url") && !validHttpUrl(rule.opt("url"), 2048)) return false
        if (rule.has("postData") && !validText(rule.opt("postData"), 0, 8192)) return false
        if (rule.has("body") && !validText(rule.opt("body"), 0, 8192)) return false
        if (rule.has("responseCode") && integer(rule.opt("responseCode"))?.let { it in 200L..599L } != true) return false
        if (rule.has("responseCode") && rule.opt("stage") != "response") return false
        val stage = rule.optString("stage")
        return when (rule.optString("action")) {
            "continue", "block" -> rule.only("urlPattern", "stage", "method", "action")
            "modify" -> {
                val allowed = if (stage == "request") {
                    setOf("urlPattern", "stage", "method", "action", "headers", "url", "postData")
                } else setOf("urlPattern", "stage", "method", "action", "headers", "url", "body", "responseCode")
                rule.only(*allowed.toTypedArray()) &&
                    listOf("headers", "url", if (stage == "request") "postData" else "body", "responseCode")
                        .any(rule::has)
            }
            "fulfill" -> stage == "response" &&
                rule.only("urlPattern", "stage", "method", "action", "headers", "body", "responseCode") &&
                (rule.has("body") || rule.has("responseCode"))
            else -> false
        }
    }

    private fun validResolveArgs(args: JSONObject): Boolean {
        if (!args.only("action", "pausedId", "decision", "headers", "url", "method", "postData", "body", "responseCode") ||
            !validIdentifier(args.opt("pausedId")) ||
            (args.opt("decision") as? String) !in setOf("continue", "block", "modify", "fulfill")) return false
        if (args.has("headers") && !validHeaders(args.optJSONObject("headers"))) return false
        if (args.has("url") && !validHttpUrl(args.opt("url"), 2048)) return false
        if (args.has("method") && !validPattern(args.opt("method"), "[A-Za-z][A-Za-z0-9_-]{0,15}")) return false
        if (args.has("postData") && !validText(args.opt("postData"), 0, 8192)) return false
        if (args.has("body") && !validText(args.opt("body"), 0, 8192)) return false
        if (args.has("responseCode") && integer(args.opt("responseCode"))?.let { it in 200L..599L } != true) return false
        return when (args.optString("decision")) {
            "continue", "block" -> args.only("action", "pausedId", "decision")
            "modify" -> args.has("headers") || args.has("url") || args.has("method") || args.has("postData") || args.has("body") || args.has("responseCode")
            "fulfill" -> args.only("action", "pausedId", "decision", "headers", "body", "responseCode") &&
                (args.has("body") || args.has("responseCode"))
            else -> false
        }
    }

    private fun validHeaders(headers: JSONObject?): Boolean {
        if (headers == null || headers.length() > 16) return false
        val keyPattern = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}")
        val keys = headers.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = headers.opt(key) as? String ?: return false
            if (!keyPattern.matches(key) || value.length > 1024 || value.contains('\r') || value.contains('\n')) return false
        }
        return true
    }

    private fun validSelectionArgs(args: JSONObject): Boolean {
        if (args.opt("action") == "task_pending") return args.only("action", "afterSequence", "destinationSessionKey") &&
            isGeneration(args.opt("afterSequence")) && validText(args.opt("destinationSessionKey"), 1, 256)
        if (args.opt("action") == "task_claim" || args.opt("action") == "task_admit") return args.only("action", "feedbackId", "destinationSessionKey", "requestId", "payloadDigest") &&
                validPattern(args.opt("feedbackId"), "[a-f0-9]{32}") &&
                validText(args.opt("destinationSessionKey"), 1, 256) &&
                validPattern(args.opt("requestId"), "[a-f0-9]{32}") && validPattern(args.opt("payloadDigest"), "[a-f0-9]{64}")
        if (args.opt("action") == "task_receipt") return args.only("action", "feedbackId", "destinationSessionKey", "requestId", "deliveryCode") &&
                validPattern(args.opt("feedbackId"), "[a-f0-9]{32}") &&
                validText(args.opt("destinationSessionKey"), 1, 256) && validPattern(args.opt("requestId"), "[a-f0-9]{32}") &&
                (args.opt("deliveryCode") as? String) in setOf("PROMPT_ACCEPTED", "UNKNOWN", "BLOCKED")
        if (args.opt("action") == "pending") return args.only("action", "afterSequence", "destinationSessionKey") &&
            isGeneration(args.opt("afterSequence")) && validText(args.opt("destinationSessionKey"), 1, 256)
        if (args.opt("action") == "claim" || args.opt("action") == "admit") return args.only("action", "selectionId", "destinationSessionKey", "requestId", "payloadDigest") &&
                validPattern(args.opt("selectionId"), "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}") &&
                validText(args.opt("destinationSessionKey"), 1, 256) &&
                validPattern(args.opt("requestId"), "[a-f0-9]{32}") && validPattern(args.opt("payloadDigest"), "[a-f0-9]{64}")
        if (args.opt("action") != "receipt") return args.only("action") &&
            (args.opt("action") as? String) in setOf("latest", "consume", "clear", "list")
        return args.only("action", "selectionId", "destinationSessionKey", "deliveryCode", "requestId", "imageIncluded") &&
            (args.opt("selectionId") as? String)?.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) == true &&
            validText(args.opt("destinationSessionKey"), 1, 256) &&
            (args.opt("deliveryCode") as? String)?.matches(Regex("[A-Z_]{1,64}")) == true &&
            (!args.has("requestId") || (args.opt("requestId") as? String)?.matches(Regex("[a-f0-9]{32}")) == true) &&
            (!args.has("imageIncluded") || args.opt("imageIncluded") is Boolean)
    }

    private fun validFileArgs(args: JSONObject, request: JSONObject, hasControl: Boolean): Boolean {
        return when (args.opt("action") as? String) {
            "list" -> validGeneration(request.opt("sessionGeneration")) && !hasControl && !request.has("owner") &&
                args.only("action", "limit", "beforeId") &&
                (!args.has("limit") || integer(args.opt("limit"))?.let { it in 1L..20L } == true) &&
                (!args.has("beforeId") || validDownloadId(args.opt("beforeId")))
            "status" -> validGeneration(request.opt("sessionGeneration")) && !hasControl && !request.has("owner") &&
                args.only("action", "id") && validDownloadId(args.opt("id"))
            "start" -> validActionOwner(request) && args.only("action", "url", "suggestedFileName") &&
                validHttpUrl(args.opt("url"), 2048) &&
                (!args.has("suggestedFileName") || validSuggestedFileName(args.opt("suggestedFileName")))
            "cancel" -> validActionOwner(request) && args.only("action", "id") && validDownloadId(args.opt("id"))
            else -> false
        }
    }

    private fun validUploadArgs(args: JSONObject, hasControl: Boolean): Boolean {
        if (!hasControl || args.opt("action") !in setOf("request", "list", "submit", "cancel", "status", "activate")) return false
        return when (args.optString("action")) {
            "request" -> {
                if (!args.only("action", "paths", "acceptTypes", "multiple", "summary")) return false
                val paths = args.optJSONArray("paths") ?: return false
                if (paths.length() !in 1..10 || (0 until paths.length()).any {
                        val value = paths.opt(it) as? String ?: return false
                        !safeUploadRelativePath(value)
                    }) return false
                if (args.has("acceptTypes")) {
                    val types = args.optJSONArray("acceptTypes") ?: return false
                    if (types.length() > 20 || (0 until types.length()).any { !validUploadAcceptType(types.opt(it)) }) return false
                }
                if (args.has("multiple") && args.opt("multiple") !is Boolean) return false
                !args.has("summary") || validText(args.opt("summary"), 0, 160) && (args.optString("summary").none { it.isISOControl() })
            }
            "list", "status", "cancel" -> args.only("action", "approvalId") && validUploadId(args.opt("approvalId"))
            "activate" -> args.only("action", "approvalId", "nodeId") && validUploadId(args.opt("approvalId")) &&
                validPattern(args.opt("nodeId"), "b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:(?:[0-9]|[1-7][0-9])")
            "submit" -> {
                if (!args.only("action", "approvalId", "fileIds") || !validUploadId(args.opt("approvalId"))) return false
                val ids = args.optJSONArray("fileIds") ?: return false
                if (ids.length() !in 1..10) return false
                val values = (0 until ids.length()).map { ids.optString(it) }
                values.distinct().size == values.size && values.all(::validUploadId)
            }
            else -> false
        }
    }

    private fun safeUploadRelativePath(value: String): Boolean {
        if (value.isBlank() || value.length > 1024 || value.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(value) ||
            value.any { it == '\\' || it.isISOControl() }) return false
        val segments = value.split('/')
        return segments.isNotEmpty() && segments.all { segment -> segment.isNotEmpty() && segment !in setOf(".", "..") && isSafeUploadName(segment) }
    }

    private fun isSafeUploadName(name: String): Boolean {
        val lower = name.lowercase(java.util.Locale.ROOT)
        val denied = setOf(".dsh", ".git", ".ssh", ".config", ".android", ".gradle", ".npm", ".aws", ".kube",
            ".npmrc", ".pypirc", ".netrc", "user-data", "node_modules", "credentials", "credential", "secrets",
            "secret", "id_rsa", "id_ed25519", "id_ecdsa", "authorized_keys")
        return lower !in denied && !Regex("^\\.env(?:\\.|$)").containsMatchIn(lower) &&
            !Regex("(?:^|[._-])(?:credentials?|secrets?|tokens?)(?:[._-]|$)").containsMatchIn(lower) &&
            lower.substringAfterLast('.', "") !in setOf("pem", "key", "p12", "pfx", "jks", "keystore", "mobileprovision", "asc")
    }

    private fun validUploadId(value: Any?): Boolean = value is String && value.matches(Regex("[a-f0-9]{32}"))

    private fun validUploadAcceptType(value: Any?): Boolean {
        val type = value as? String ?: return false
        if (type.length !in 1..128 || type.any { it.isISOControl() }) return false
        return if (type.startsWith('.')) type.matches(Regex("\\.[A-Za-z0-9]{1,16}"))
            else type == "*/*" || type.matches(Regex("[A-Za-z0-9!#$&^_.+-]+/(?:[A-Za-z0-9!#$&^_.+-]+|\\*)"))
    }

    private fun validSuggestedFileName(value: Any?): Boolean {
        val name = value as? String ?: return false
        return name.length in 1..180 && name.none {
            it < ' ' || it in '\u007f'..'\u009f' || it == '/' || it == '\\' || it == ':'
        }
    }

    private fun validCoordinate(value: Any?): Boolean = (value as? Number)?.toDouble()?.let {
        it.isFinite() && it in 0.0..10_000.0
    } == true

    private fun validText(value: Any?, minLength: Int, maxLength: Int): Boolean =
        value is String && value.length in minLength..maxLength

    private fun validIdentifier(value: Any?): Boolean =
        value is String && idPattern.matches(value)

    private fun validPattern(value: Any?, pattern: String): Boolean =
        value is String && Regex(pattern).matches(value)

    private fun validHttpUrl(value: Any?, maxLength: Int): Boolean {
        val text = value as? String ?: return false
        if (text.length !in 1..maxLength) return false
        val uri = runCatching { URI(text) }.getOrNull() ?: return false
        return uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    private fun isGeneration(value: Any?): Boolean = integer(value)?.let { it in 0L..MAX_SAFE_INTEGER } ?: false

    private fun integer(value: Any?): Long? {
        // org.json represents decimal/exponent tokens as Double. Reject those even
        // when they happen to be mathematically integral (for example 1.0).
        val result = when (value) {
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        return result.takeIf { it in 0L..MAX_SAFE_INTEGER }
    }

    private fun validNode(value: Any?): Boolean = value is String && idPattern.matches(value)

    private fun validObservationReference(value: Any?): Boolean = value is String && observationRefPattern.matches(value)

    private fun validDownloadId(value: Any?): Boolean =
        value is String && DOWNLOAD_ID_PATTERN.matches(value)

    private fun JSONObject.only(vararg allowed: String): Boolean {
        val accepted = allowed.toSet()
        return keys().asSequence().all { it in accepted }
    }

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val MAX_BUDGET_MS = 60_000L
    private val DOWNLOAD_ID_PATTERN = Regex("^[a-f0-9]{32}$")
}
