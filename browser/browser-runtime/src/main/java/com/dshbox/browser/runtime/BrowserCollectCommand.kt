package com.dshbox.browser.runtime

import android.os.SystemClock
import android.webkit.WebView
import com.dshbox.browser.api.BrowserLifecycle
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Executes one bounded, read-only virtual-list window snapshot and publishes a fresh scroll ref. */
internal class BrowserCollectCommand(
    private val expireIfNeeded: () -> BrowserSnapshot,
    private val lifecycle: () -> BrowserLifecycleController,
    private val automationEnabled: () -> Boolean,
    private val webView: () -> WebView?,
    private val activePageId: () -> String?,
    private val pageState: () -> BrowserPageStateTracker.State?,
    private val documentGeneration: () -> Long,
    private val previousNonce: () -> String?,
    private val beginObservation: (nonce: String, pageId: String, view: WebView, documentGeneration: Long) -> Long?,
    private val isObservationCurrent: (
        initial: BrowserSnapshot,
        pageId: String,
        view: WebView,
        documentGeneration: Long,
        navigationId: Long,
        nonce: String,
        observationId: Long,
    ) -> Boolean,
    private val observationRef: (snapshot: BrowserSnapshot, observationId: Long, nonce: String, index: Int) -> String,
    private val buildScript: (args: JSONObject, nonce: String, previousNonce: String?) -> String,
    private val buildResponse: (
        request: BrowserCommandRequest,
        code: String,
        executed: Boolean?,
        snapshot: BrowserSnapshot,
        data: JSONObject,
    ) -> JSONObject,
    private val callback: (String) -> Unit,
    private val nowElapsedMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    fun execute(request: BrowserCommandRequest) {
        val initial = expireIfNeeded()
        val earlyCode = authorizationError(request, initial)
        if (earlyCode != null) {
            reply(request, earlyCode, false, initial, JSONObject())
            return
        }
        val args = validateArgs(request.args)
        if (args == null) {
            reply(request, "INVALID_ARGUMENT", false, initial, JSONObject())
            return
        }
        val view = webView()
        if (view == null) {
            reply(request, "CLOSED", false, initial, JSONObject())
            return
        }
        val page = pageState()
        val pageId = activePageId()
        val docGeneration = documentGeneration()
        if (page == null || page.loadState != BrowserPageStateTracker.LoadState.FINISHED ||
            page.readyState != "complete" || pageId.isNullOrBlank()
        ) {
            reply(request, "PAGE_NOT_READY", false, initial, JSONObject())
            return
        }
        val navId = page.navigationId
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val oldNonce = previousNonce()
        val observationId = beginObservation(nonce, pageId, view, docGeneration)
        if (observationId == null || observationId <= 0L) {
            reply(request, "STALE_REFERENCE", false, lifecycle().snapshot(), JSONObject())
            return
        }
        val script = runCatching { buildScript(args, nonce, oldNonce) }.getOrElse {
            reply(request, "EXECUTION_FAILED", false, lifecycle().snapshot(), JSONObject())
            return
        }
        view.evaluateJavascript(script) { encoded ->
            val current = expireIfNeeded()
            val authorizationCode = authorizationError(request, current)
            if (authorizationCode != null) {
                reply(request, authorizationCode, false, current, JSONObject())
                return@evaluateJavascript
            }
            val currentPage = pageState()
            if (webView() !== view || activePageId() != pageId || documentGeneration() != docGeneration ||
                currentPage == null || currentPage.navigationId != navId ||
                currentPage.loadState != BrowserPageStateTracker.LoadState.FINISHED ||
                currentPage.readyState != "complete" ||
                !isObservationCurrent(initial, pageId, view, docGeneration, navId, nonce, observationId)
            ) {
                reply(request, "STALE_REFERENCE", null, current, JSONObject())
                return@evaluateJavascript
            }
            val raw = decodeJavascriptString(encoded)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (raw == null) {
                reply(request, "EXECUTION_FAILED", false, current, JSONObject())
                return@evaluateJavascript
            }
            if (raw.optString("code") != "OK") {
                reply(request, "EXECUTION_FAILED", false, current,
                    JSONObject().put("reason", raw.optString("code", "COLLECT_FAILED")))
                return@evaluateJavascript
            }
            val data = BrowserCollectWindowEnvelope.encode(
                raw, args, current, pageId, docGeneration, navId, observationId,
                observationRef(current, observationId, nonce, 0),
            )
            if (data == null) {
                reply(request, "EXECUTION_FAILED", false, current, JSONObject())
                return@evaluateJavascript
            }
            reply(request, "OK", true, current, data)
        }
    }

    private fun reply(
        request: BrowserCommandRequest,
        code: String,
        executed: Boolean?,
        snapshot: BrowserSnapshot,
        data: JSONObject,
    ) {
        val response = buildResponse(request, code, executed, snapshot, data).toString()
        val selected = BrowserCollectOutputPolicy.enforce(response) {
            buildResponse(request, "LIMIT_EXCEEDED", true, snapshot, JSONObject()
                .put("action", "window").put("reason", "RESPONSE_BUDGET_EXCEEDED").put("truncated", true)).toString()
        }
        callback(selected)
    }

    private fun authorizationError(request: BrowserCommandRequest, snapshot: BrowserSnapshot): String? = when {
        nowElapsedMillis() >= request.deadlineAtElapsedMillis -> "DEADLINE_EXPIRED"
        snapshot.lifecycle != BrowserLifecycle.ACTIVE -> "CLOSED"
        !automationEnabled() -> "AUTOMATION_DISABLED"
        snapshot.owner != BrowserOwner.AI -> "USER_CONTROLLED"
        request.owner != "ai" || request.sessionGeneration != snapshot.sessionGeneration ||
            request.controlGeneration != snapshot.controlGeneration -> "STALE_SESSION"
        snapshot.inFlightOperationId != null -> "BUSY_OR_STALE"
        else -> null
    }

    private fun validateArgs(source: JSONObject): JSONObject? = runCatching {
        require(source.optString("action") == "window")
        requireOnlyKeys(source, setOf(
            "action", "containerSelector", "messageSelector", "keyAttribute", "authorSelector", "authorAttribute",
            "contentSelector", "orderAttribute", "totalCountSelector", "totalCountAttribute", "declaredRange",
            "maxRecords", "maxContentChars", "excludeSelector",
        ))
        require(selector(source, "containerSelector", true))
        require(selector(source, "messageSelector", true))
        require(selector(source, "authorSelector", false))
        require(selector(source, "contentSelector", false))
        require(selector(source, "totalCountSelector", false))
        require(selector(source, "excludeSelector", false))
        require(attribute(source, "keyAttribute", true))
        require(attribute(source, "authorAttribute", false))
        require(attribute(source, "orderAttribute", false))
        require(attribute(source, "totalCountAttribute", false))
        val scope = source.optString("declaredRange").trim()
        require(scope.length in 1..128 && scope.none { it.isISOControl() })
        val maxRecords = integer(source, "maxRecords")
        val maxContentChars = integer(source, "maxContentChars")
        require(maxRecords in 1..BrowserCollectOutputPolicy.MAX_RECORDS && maxContentChars in 1..4096)
        JSONObject(source.toString())
    }.getOrNull()

    private fun decodeJavascriptString(encoded: String?): String? = runCatching {
        if (encoded == null || encoded == "null") return null
        JSONArray("[" + encoded + "]").getString(0)
    }.getOrNull()

    private fun selector(source: JSONObject, name: String, required: Boolean): Boolean {
        val value = source.opt(name) as? String ?: return !required && (source.isNull(name) || !source.has(name))
        return value.isNotEmpty() && value.length <= 512 && value.none { it.isISOControl() }
    }

    private fun attribute(source: JSONObject, name: String, required: Boolean): Boolean {
        val value = source.opt(name) as? String ?: return !required && (source.isNull(name) || !source.has(name))
        return if (value.isEmpty()) !required else value.matches(Regex("[A-Za-z_:][A-Za-z0-9_.:-]{0,63}"))
    }

    private fun integer(source: JSONObject, name: String): Int {
        val value = source.opt(name)
        require(value is Int || value is Long && value >= Int.MIN_VALUE.toLong() && value <= Int.MAX_VALUE.toLong())
        return (value as Number).toInt()
    }

    private fun safeNumber(source: JSONObject, name: String): Double {
        val value = source.opt(name)
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite())
        return number
    }

    private fun nullableString(source: JSONObject, name: String, maxLength: Int): String? {
        val value = source.opt(name)
        if (value == null || value == JSONObject.NULL) return null
        require(value is String && value.length <= maxLength && value.none { it.isISOControl() })
        return value
    }

    private fun requireOnlyKeys(source: JSONObject, allowed: Set<String>) {
        val keys = source.keys()
        while (keys.hasNext()) require(keys.next() in allowed)
    }

}

internal object BrowserCollectOutputPolicy {
    const val MAX_RECORDS = 200
    const val MAX_RESPONSE_BYTES = 16 * 1024 - 1

    fun withinRecordBudget(count: Int, configuredMaximum: Int): Boolean =
        count >= 0 && configuredMaximum in 1..MAX_RECORDS && count <= configuredMaximum

    fun withinResponseBudget(response: String): Boolean =
        response.toByteArray(StandardCharsets.UTF_8).size <= MAX_RESPONSE_BYTES

    fun enforce(response: String, overflow: () -> String): String {
        if (withinResponseBudget(response)) return response
        val fallback = overflow()
        require(withinResponseBudget(fallback)) { "minimal collect error response exceeds response budget" }
        return fallback
    }
}

internal object BrowserCollectWindowEnvelope {
    fun encode(
        raw: JSONObject,
        args: JSONObject,
        snapshot: BrowserSnapshot,
        pageId: String,
        documentGeneration: Long,
        navigationId: Long,
        observationId: Long,
        containerNodeId: String,
    ): JSONObject? = runCatching {
        requireOnlyKeys(raw, setOf("code", "container", "records", "truncated", "excludedCount", "containerRefIndex"))
        require(raw.opt("code") == "OK" && raw.opt("containerRefIndex") == 0)
        require(containerNodeId.matches(Regex("b:[1-9][0-9]{0,15}:[1-9][0-9]{0,15}:[0-9]{1,16}:[1-9][0-9]{0,15}:[a-f0-9]{32}:0")))
        val container = raw.optJSONObject("container") ?: error("container")
        requireOnlyKeys(container, setOf("scrollTop", "scrollHeight", "clientHeight", "startReached", "endReached", "totalCount"))
        val top = safeNumber(container, "scrollTop")
        val scrollHeight = safeNumber(container, "scrollHeight")
        val clientHeight = safeNumber(container, "clientHeight")
        require(scrollHeight >= clientHeight && clientHeight > 0 && top >= 0 && top <= scrollHeight)
        require(container.opt("startReached") is Boolean && container.opt("endReached") is Boolean)
        val totalRaw = container.opt("totalCount")
        val totalValid = totalRaw == JSONObject.NULL || totalRaw is Int && totalRaw >= 0 ||
            totalRaw is Long && totalRaw >= 0 && totalRaw <= BrowserCommandRequest.MAX_SAFE_INTEGER
        val total = if (totalValid) totalRaw else JSONObject.NULL
        var sanitizedTruncated = false
        val maxRecords = integer(args, "maxRecords")
        val maxContentChars = integer(args, "maxContentChars")
        require(maxRecords in 1..BrowserCollectOutputPolicy.MAX_RECORDS && maxContentChars in 1..4096)
        val sourceRecords = raw.optJSONArray("records") ?: error("records")
        require(BrowserCollectOutputPolicy.withinRecordBudget(sourceRecords.length(), maxRecords))
        val records = JSONArray()
        for (index in 0 until sourceRecords.length()) {
            val source = sourceRecords.optJSONObject(index) ?: error("record")
            requireOnlyKeys(source, setOf("key", "author", "content", "order"))
            val key = nullableString(source, "key", 256)
            val author = nullableString(source, "author", 128)
            val content = source.opt("content") as? String ?: error("content")
            require(content.length <= maxContentChars)
            val orderRaw = source.opt("order")
            val order = if (orderRaw == JSONObject.NULL || orderRaw is Int ||
                orderRaw is Long && orderRaw in -BrowserCommandRequest.MAX_SAFE_INTEGER..BrowserCommandRequest.MAX_SAFE_INTEGER
            ) orderRaw else JSONObject.NULL
            if (order != orderRaw) sanitizedTruncated = true
            records.put(JSONObject().put("key", key ?: JSONObject.NULL).put("author", author ?: JSONObject.NULL)
                .put("content", content).put("order", order ?: JSONObject.NULL))
        }
        require(raw.opt("truncated") is Boolean)
        val excludedCount = integer(raw, "excludedCount")
        require(excludedCount >= 0 && excludedCount <= 5000)
        JSONObject().put("action", "window")
            .put("identity", JSONObject().put("pageId", pageId)
                .put("documentGeneration", documentGeneration).put("navigationId", navigationId)
                .put("observationId", observationId).put("sessionGeneration", snapshot.sessionGeneration)
                .put("controlGeneration", snapshot.controlGeneration))
            .put("containerNodeId", containerNodeId)
            .put("container", JSONObject().put("scrollTop", top).put("scrollHeight", scrollHeight)
                .put("clientHeight", clientHeight).put("startReached", container.getBoolean("startReached"))
                .put("endReached", container.getBoolean("endReached")).put("totalCount", total))
            .put("records", records).put("truncated", raw.getBoolean("truncated") || !totalValid || sanitizedTruncated)
            .put("excludedCount", excludedCount)
            .put("declaredRange", args.getString("declaredRange"))
    }.getOrNull()

    private fun safeNumber(source: JSONObject, name: String): Double {
        val value = source.opt(name)
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite())
        return number
    }

    private fun nullableString(source: JSONObject, name: String, maxLength: Int): String? {
        val value = source.opt(name)
        if (value == null || value == JSONObject.NULL) return null
        require(value is String && value.length <= maxLength && value.none { it.isISOControl() })
        return value
    }

    private fun integer(source: JSONObject, name: String): Int {
        val value = source.opt(name)
        require(value is Int || value is Long && value >= Int.MIN_VALUE.toLong() && value <= Int.MAX_VALUE.toLong())
        return (value as Number).toInt()
    }

    private fun requireOnlyKeys(source: JSONObject, allowed: Set<String>) {
        val keys = source.keys()
        while (keys.hasNext()) require(keys.next() in allowed)
    }
}
