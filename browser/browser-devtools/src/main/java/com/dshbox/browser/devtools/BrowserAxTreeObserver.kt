package com.dshbox.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

internal data class BrowserAxTreeRequest(
    val maxNodes: Int = 64,
    val maxDepth: Int = 4,
    val includeIgnored: Boolean = false,
) {
    init {
        require(maxNodes in 1..MAX_TOTAL_NODES)
        require(maxDepth in 1..MAX_DEPTH)
    }

    companion object {
        const val MAX_TOTAL_NODES = 256
        const val MAX_DEPTH = 8
    }
}

internal data class BrowserAxTargetContext(
    val targetId: String,
    val connectionEpoch: Long,
    val expectedUrl: String,
    val documentIdentity: String,
)

internal data class BrowserAxTreeObservation(
    val code: String,
    val data: JSONObject,
    val capabilities: Map<String, String>,
    val commandCount: Int,
    val actionCandidates: Map<String, BrowserAxActionCandidate> = emptyMap(),
)

internal data class BrowserAxActionCandidate(val frameId: String, val backendNodeId: Int)

/** Current Browser transport only; callers never select a target, session, or frame. */
internal interface BrowserAxTreeTransport {
    val targetId: String
    val connectionEpoch: Long

    fun call(
        method: String,
        params: JSONObject,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
    ): JSONObject

    /** Releases only a group created by this bridge, independently of action authorization. */
    fun releaseObjectGroup(objectGroup: String, deadlineElapsedMillis: Long) {
        call("Runtime.releaseObjectGroup", JSONObject().put("objectGroup", objectGroup), deadlineElapsedMillis, { true })
    }
}

/**
 * Produces a bounded read-only AX observation. AX references are typed as ax:...
 * and are never valid DOM action references.
 */
internal class BrowserAxTreeObserver(
    private val transport: BrowserAxTreeTransport,
    private val documentIdentity: () -> String?,
    private val nowElapsedMillis: () -> Long,
) {
    private data class AxFailure(val code: String) : Exception(code)
    private data class AxIdentity(val url: String, val documentToken: String)
    private data class FrameInventory(
        val frames: MutableList<FrameDraft>,
        val truncated: Boolean,
    )

    private data class FrameDraft(
        val frameId: String,
        val url: String,
        val relation: String,
        var coverage: String = "not_attempted",
        var reason: String = "not_read",
        var nodeCount: Int = 0,
    )

    private inner class Session(
        private val context: BrowserAxTargetContext,
        private val deadlineElapsedMillis: Long,
        private val stillAuthorized: () -> Boolean,
        val capabilities: MutableMap<String, String>,
    ) {
        var commandCount: Int = 0
            private set

        fun authorized(): Boolean =
            nowElapsedMillis() < deadlineElapsedMillis &&
                runCatching(stillAuthorized).getOrDefault(false)

        fun bound(): Boolean =
            transport.targetId == context.targetId &&
                transport.connectionEpoch == context.connectionEpoch &&
                runCatching(documentIdentity).getOrNull() == context.documentIdentity

        fun call(method: String, params: JSONObject, capabilityKey: String? = null): JSONObject {
            if (nowElapsedMillis() >= deadlineElapsedMillis) throw AxFailure("DEADLINE_EXPIRED")
            if (!bound()) throw AxFailure("IDENTITY_CHANGED")
            if (!authorized()) throw AxFailure("NOT_AUTHORIZED")
            if (commandCount >= MAX_COMMANDS) throw AxFailure("COMMAND_LIMIT")

            commandCount++
            val authorizedAndBound = { authorized() && bound() }
            val response = try {
                transport.call(method, params, deadlineElapsedMillis, authorizedAndBound)
            } catch (error: AxFailure) {
                throw error
            } catch (error: Exception) {
                if (nowElapsedMillis() >= deadlineElapsedMillis) throw AxFailure("DEADLINE_EXPIRED")
                if (!bound()) throw AxFailure("IDENTITY_CHANGED")
                if (!authorized()) throw AxFailure("NOT_AUTHORIZED")
                throw AxFailure(mapFailure(error))
            }

            if (nowElapsedMillis() >= deadlineElapsedMillis) throw AxFailure("DEADLINE_EXPIRED")
            if (!bound()) throw AxFailure("IDENTITY_CHANGED")
            if (!authorized()) throw AxFailure("NOT_AUTHORIZED")
            capabilityKey?.let { capabilities[it] = "supported" }
            return response
        }
    }

    fun observe(
        context: BrowserAxTargetContext,
        request: BrowserAxTreeRequest,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
    ): BrowserAxTreeObservation {
        val capabilities = mutableMapOf(
            "Page.getFrameTree" to "unknown",
            "Accessibility.enable" to "unknown",
            "Accessibility.getFullAXTree" to "unknown",
            "Accessibility.getFullAXTree.frameId" to "unknown",
            "Accessibility.frameCoverage" to "unknown",
        )
        val session = Session(context, deadlineElapsedMillis, stillAuthorized, capabilities)

        if (!validContext(context)) return failure("INVALID_ARGUMENT", session)
        if (nowElapsedMillis() >= deadlineElapsedMillis) return failure("DEADLINE_EXPIRED", session)
        if (!session.bound()) return failure("IDENTITY_CHANGED", session)
        if (!session.authorized()) return failure("NOT_AUTHORIZED", session)

        return try {
            val before = queryIdentity(session)
            val inventory = readFrameInventory(session)
            val frames = inventory.frames
            session.call("Accessibility.enable", JSONObject(), "Accessibility.enable")

            val rootResult = session.call(
                "Accessibility.getFullAXTree",
                JSONObject().put("depth", request.maxDepth),
                "Accessibility.getFullAXTree",
            )
            val nodes = mutableListOf<JSONObject>()
            val representedFrames = mutableSetOf<String>()
            val seenNodes = mutableMapOf<String, String>()
            val actionCandidates = mutableMapOf<String, BrowserAxActionCandidate>()
            var invalidNodes = 0
            var duplicateNodes = 0

            mergeNodes(
                rootResult.optJSONArray("nodes"),
                frames,
                request,
                nodes,
                representedFrames,
                seenNodes,
                actionCandidates,
                frames.first().frameId,
                { invalidNodes++ },
                { duplicateNodes++ },
            )
            markRepresented(frames, representedFrames)

            retryMissingFrames(
                session,
                frames,
                request,
                nodes,
                representedFrames,
                seenNodes,
                actionCandidates,
                { invalidNodes++ },
                { duplicateNodes++ },
            )

            val after = queryIdentity(session)
            if (!identityMatches(context, before, after)) {
                throw AxFailure("IDENTITY_CHANGED")
            }

            val payload = buildPayload(
                frames = frames,
                nodes = nodes,
                request = request,
                invalidNodes = invalidNodes,
                duplicateNodes = duplicateNodes,
                inventoryTruncated = inventory.truncated,
                commandCount = session.commandCount,
            )
            val retainedRefs = (0 until payload.getJSONArray("nodes").length()).map {
                payload.getJSONArray("nodes").getJSONObject(it).getString("axRef")
            }.toSet()
            BrowserAxTreeObservation("OK", payload, capabilities.toMap(), session.commandCount,
                actionCandidates.filterKeys { it in retainedRefs })
        } catch (error: AxFailure) {
            failure(error.code, session)
        } catch (error: Exception) {
            failure(mapFailure(error), session)
        }
    }

    private fun queryIdentity(session: Session): AxIdentity {
        val params = JSONObject()
            .put("expression", IDENTITY_EXPRESSION)
            .put("returnByValue", true)
            .put("awaitPromise", false)
        val result = session.call("Runtime.evaluate", params)
            .optJSONObject("result")
            ?.opt("value")
            ?: throw AxFailure("TARGET_UNAVAILABLE")
        val json = when (result) {
            is String -> runCatching { JSONObject(result) }.getOrNull()
            is JSONObject -> result
            else -> null
        } ?: throw AxFailure("TARGET_UNAVAILABLE")

        val url = json.optString("href")
        val token = json.optString("doc")
        if (url.isBlank() || token.isBlank()) throw AxFailure("TARGET_UNAVAILABLE")
        return AxIdentity(url, token)
    }

    private fun readFrameInventory(session: Session): FrameInventory {
        val tree = session.call(
            "Page.getFrameTree",
            JSONObject(),
            "Page.getFrameTree",
        ).optJSONObject("frameTree") ?: throw AxFailure("TARGET_UNAVAILABLE")
        val root = tree.optJSONObject("frame") ?: throw AxFailure("TARGET_UNAVAILABLE")
        val rootId = safeId(root.optString("id")) ?: throw AxFailure("TARGET_UNAVAILABLE")
        val frames = mutableListOf<FrameDraft>()
        var truncated = false

        fun visit(treeNode: JSONObject, relation: String, depth: Int) {
            if (truncated) return
            if (depth > MAX_FRAME_DEPTH) {
                truncated = true
                return
            }
            val node = treeNode.optJSONObject("frame") ?: return
            val id = safeId(node.optString("id")) ?: return
            if (frames.any { it.frameId == id }) return
            if (frames.size >= MAX_FRAMES) {
                truncated = true
                return
            }
            frames += FrameDraft(id, node.optString("url"), relation)
            val children = treeNode.optJSONArray("childFrames") ?: return
            for (index in 0 until children.length()) {
                val child = children.optJSONObject(index) ?: continue
                visit(child, "child", depth + 1)
            }
        }

        visit(tree, "main", 0)
        frames.forEach { frame ->
            frame.coverage = if (frame.frameId == rootId) "not_covered" else "not_attempted"
            frame.reason = "not_read"
        }
        return FrameInventory(frames, truncated)
    }

    private fun retryMissingFrames(
        session: Session,
        frames: List<FrameDraft>,
        request: BrowserAxTreeRequest,
        nodes: MutableList<JSONObject>,
        representedFrames: MutableSet<String>,
        seenNodes: MutableMap<String, String>,
        actionCandidates: MutableMap<String, BrowserAxActionCandidate>,
        onInvalidNode: () -> Unit,
        onDuplicateNode: () -> Unit,
    ) {
        for (frame in frames.filter { it.coverage != "observed" }) {
            // One command is always reserved for the closing identity query.
            if (session.commandCount >= MAX_COMMANDS - 1) {
                frame.coverage = "not_attempted"
                frame.reason = "command_budget"
                continue
            }

            try {
                val response = session.call(
                    "Accessibility.getFullAXTree",
                    JSONObject().put("depth", request.maxDepth).put("frameId", frame.frameId),
                    "Accessibility.getFullAXTree.frameId",
                )
                val beforeCount = nodes.size
                mergeNodes(
                    response.optJSONArray("nodes"),
                    frames,
                    request,
                    nodes,
                    representedFrames,
                    seenNodes,
                    actionCandidates,
                    frame.frameId,
                    onInvalidNode,
                    onDuplicateNode,
                )
                val observed = representedFrames.contains(frame.frameId)
                frame.coverage = if (observed) "observed" else "not_covered"
                frame.reason = if (observed) "ok" else "no_matching_nodes"
                frame.nodeCount = nodes.drop(beforeCount).count {
                    !it.isNull("frameId") && it.optString("frameId") == frame.frameId
                }
            } catch (error: AxFailure) {
                if (error.code == "UNSUPPORTED") {
                    session.capabilities["Accessibility.getFullAXTree.frameId"] = "unsupported"
                    frame.coverage = "unsupported"
                    frame.reason = "frame_id_unsupported"
                    frames.filter { it.coverage == "not_attempted" }.forEach {
                        it.coverage = "unsupported"
                        it.reason = "frame_id_unsupported"
                    }
                    return
                }
                if (error.code in FATAL_CODES) throw error
                frame.coverage = "not_covered"
                frame.reason = "cdp_failed"
            }
        }
    }

    private fun mergeNodes(
        rawNodes: JSONArray?,
        frames: List<FrameDraft>,
        request: BrowserAxTreeRequest,
        output: MutableList<JSONObject>,
        representedFrames: MutableSet<String>,
        seenNodes: MutableMap<String, String>,
        actionCandidates: MutableMap<String, BrowserAxActionCandidate>,
        queriedFrameId: String,
        onInvalidNode: () -> Unit,
        onDuplicateNode: () -> Unit,
    ) {
        if (rawNodes == null) return
        val validFrames = frames.mapTo(mutableSetOf()) { it.frameId }

        for (index in 0 until rawNodes.length()) {
            val raw = rawNodes.optJSONObject(index)
            if (raw == null) {
                onInvalidNode()
                continue
            }
            if (raw.optBoolean("ignored", false) && !request.includeIgnored) continue

            val rawFrameId = raw.optString("frameId").ifBlank { queriedFrameId }
            if (rawFrameId.isNotBlank()) {
                if (rawFrameId !in validFrames) {
                    onInvalidNode()
                    continue
                }
            }

            val rawNodeId = safeId(raw.optString("nodeId"))
            if (rawNodeId == null) {
                onInvalidNode()
                continue
            }
            val frameScope = rawFrameId.takeIf { it.isNotBlank() } ?: UNATTRIBUTED_FRAME_SCOPE
            val childRefs = childRefs(raw, frameScope)
            val candidate = JSONObject()
                .put("axRef", scopedAxRef(rawNodeId, frameScope))
                .put("parentAxRef", scopedAxRef(raw.optString("parentId"), frameScope) ?: JSONObject.NULL)
                .put("frameId", rawFrameId.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                .put(
                    "frameAttribution",
                    if (rawFrameId.isNotBlank()) "known" else "unknown",
                )
                .put(
                    "role",
                    axValueString(raw.opt("role"))?.let { safeText(it, MAX_ROLE_CHARS) }
                        ?: JSONObject.NULL,
                )
                .put("name", safeText(axName(raw.opt("name")), MAX_NAME_CHARS))
                .put("states", allowedStates(raw))
                .put("childAxRefs", childRefs.refs)
                .put("childRefsTruncated", childRefs.truncated)

            val nodeKey = "$frameScope\u0000$rawNodeId"
            val backendValue = raw.opt("backendDOMNodeId")
            val backendId = when (backendValue) {
                is Int -> backendValue.takeIf { it > 0 }
                is Long -> backendValue.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
                else -> null
            }
            val signature = candidate.toString() + "\u0000" + backendId
            val previous = seenNodes[nodeKey]
            if (previous != null) {
                if (previous != signature) throw AxFailure("AX_NODE_CONFLICT")
                onDuplicateNode()
                continue
            }
            if (output.size >= request.maxNodes ||
                output.size >= BrowserAxTreeRequest.MAX_TOTAL_NODES
            ) {
                onInvalidNode()
                continue
            }

            seenNodes[nodeKey] = signature
            output += candidate
            if (rawFrameId.isNotBlank()) representedFrames += rawFrameId
            if (backendId != null && rawFrameId.isNotBlank() && !raw.optBoolean("ignored", false)) {
                actionCandidates[candidate.getString("axRef")] = BrowserAxActionCandidate(rawFrameId, backendId)
            }
        }
    }

    private data class ChildRefs(val refs: JSONArray, val truncated: Boolean)

    private fun childRefs(raw: JSONObject, frameScope: String): ChildRefs {
        val result = JSONArray()
        val children = raw.optJSONArray("childIds") ?: return ChildRefs(result, false)
        var invalidChildRef = false
        for (index in 0 until children.length()) {
            if (result.length() >= MAX_CHILD_REFS) break
            val childRef = scopedAxRef(children.optString(index), frameScope)
            if (childRef == null) {
                invalidChildRef = true
            } else {
                result.put(childRef)
            }
        }
        return ChildRefs(result, children.length() > MAX_CHILD_REFS || invalidChildRef)
    }

    private fun allowedStates(raw: JSONObject): JSONArray {
        val states = JSONArray()
        val properties = raw.optJSONArray("properties") ?: JSONArray()

        for (index in 0 until properties.length()) {
            val property = properties.optJSONObject(index) ?: continue
            val name = property.optString("name")
            if (name !in STATE_NAMES) continue

            val propertyValue = property.opt("value")
            val stateValue = if (propertyValue is JSONObject) {
                propertyValue.opt("value")
            } else {
                propertyValue
            }
            when {
                name == "checked" && (stateValue == true || stateValue == "true") ->
                    states.put("checked")
                name == "checked" && stateValue == "mixed" -> states.put("mixed")
                name != "checked" && stateValue == true -> states.put(name)
            }
            if (states.length() >= MAX_STATES) break
        }
        return states
    }

    private fun buildPayload(
        frames: MutableList<FrameDraft>,
        nodes: MutableList<JSONObject>,
        request: BrowserAxTreeRequest,
        invalidNodes: Int,
        duplicateNodes: Int,
        inventoryTruncated: Boolean,
        commandCount: Int,
    ): JSONObject {
        var droppedNodes = invalidNodes
        var droppedFrames = 0
        var truncated = false

        fun payload(): JSONObject {
            val frameNodeCounts = nodes.groupingBy { it.optString("frameId") }.eachCount()
            val rootOrigin = frames.firstOrNull()?.url.orEmpty()
            val frameArray = JSONArray(frames.map { frame ->
                JSONObject()
                    .put("frameId", frame.frameId)
                    .put("relation", frame.relation)
                    .put("origin", originClass(frame.url, rootOrigin))
                    .put("coverage", frame.coverage)
                    .put("reason", frame.reason)
                    .put("nodeCount", frameNodeCounts[frame.frameId] ?: 0)
            })
            val unattributed = nodes.count { it.isNull("frameId") }
            val childReferencesTruncated = nodes.any {
                it.optBoolean("childRefsTruncated", false)
            }
            val depthLimited = request.maxDepth < BrowserAxTreeRequest.MAX_DEPTH
            val returnedRefs = nodes.mapTo(mutableSetOf()) { it.getString("axRef") }
            val unresolvedChildReferences = nodes.sumOf { node ->
                val children = node.getJSONArray("childAxRefs")
                (0 until children.length()).count { children.getString(it) !in returnedRefs }
            }
            val limited = truncated || inventoryTruncated || droppedNodes > 0 ||
                droppedFrames > 0 || unattributed > 0 ||
                childReferencesTruncated || depthLimited || unresolvedChildReferences > 0
            val complete = !limited &&
                frames.isNotEmpty() &&
                frames.all { it.coverage == "observed" }

            return JSONObject()
                .put("coverage", if (complete) "complete" else "partial")
                .put("coverageScope", "inventoried_frames_only")
                .put("fullPageAxCovered", false)
                .put("frames", frameArray)
                .put("nodes", JSONArray(nodes))
                .put("uniqueAxRefs", nodes.size)
                .put("requestedNodes", request.maxNodes)
                .put("maxDepth", request.maxDepth)
                .put("truncated", limited)
                .put("inventoryTruncated", inventoryTruncated)
                .put(
                    "inventoryTruncatedReason",
                    if (inventoryTruncated) "frame_limit_or_depth" else JSONObject.NULL,
                )
                .put("droppedNodes", droppedNodes)
                .put("duplicateNodes", duplicateNodes)
                .put("unattributedNodes", unattributed)
                .put("childReferencesTruncated", childReferencesTruncated)
                .put("unresolvedChildReferences", unresolvedChildReferences)
                .put("depthLimited", depthLimited)
                .put("droppedFrames", droppedFrames)
                .put("commandCount", commandCount)
                .put("budgets", JSONObject()
                    .put("maxCommands", MAX_COMMANDS)
                    .put("usedCommands", commandCount)
                    .put("maxOutputBytes", MAX_OUTPUT_BYTES)
                    .put("maxTotalNodes", BrowserAxTreeRequest.MAX_TOTAL_NODES))
        }

        while (payload().toString().toByteArray(StandardCharsets.UTF_8).size > MAX_OUTPUT_BYTES) {
            if (nodes.isNotEmpty()) {
                nodes.removeAt(nodes.lastIndex)
                droppedNodes++
                truncated = true
            } else if (frames.size > 1) {
                frames.removeAt(frames.lastIndex)
                droppedFrames++
                truncated = true
            } else {
                break
            }
        }
        return payload()
    }

    private fun markRepresented(frames: List<FrameDraft>, represented: Set<String>) {
        frames.forEach { frame ->
            if (frame.frameId in represented) {
                frame.coverage = "observed"
                frame.reason = "ok"
            }
        }
    }

    private fun axName(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> value.opt("value") as? String ?: ""
        else -> ""
    }

    private fun axValueString(value: Any?): String? = when (value) {
        is String -> value
        is JSONObject -> value.opt("value") as? String
        else -> null
    }

    private fun safeText(value: String, limit: Int): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\p{Cntrl}"), "")
        .replace(Regex("(https?://[^\\s?#]+)\\?[^\\s]*"), "\$1?[redacted]")
        .replace(Regex("(https?://[^\\s?#]+)#[^\\s]*"), "\$1#fragment")
        .replace(Regex("(?i)\\bBearer\\s+[^\\s,;]+"), "Bearer [redacted]")
        .replace(
            Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"),
            "[redacted]",
        )
        .replace(
            Regex(
                "(?i)(authorization|cookie|set-cookie|(?:x[-_])?api[-_]?key|" +
                    "access[_-]?token|refresh[_-]?token|token|password|secret|csrf|key)" +
                    "\\s*[:=]\\s*[^\\s,;]+",
            ),
            "\$1=[redacted]",
        )
        .take(limit)

    private fun scopedAxRef(value: String, frameScope: String): String? {
        val id = safeId(value) ?: return null
        return if (AX_ID.matches(id) && AX_SCOPE.matches(frameScope)) {
            "ax:$frameScope:$id"
        } else {
            null
        }
    }

    private fun identityMatches(
        context: BrowserAxTargetContext,
        before: AxIdentity,
        after: AxIdentity,
    ): Boolean = before.url == context.expectedUrl &&
        after.url == context.expectedUrl &&
        before.documentToken == after.documentToken &&
        transport.targetId == context.targetId &&
        transport.connectionEpoch == context.connectionEpoch &&
        runCatching(documentIdentity).getOrNull() == context.documentIdentity

    private fun validContext(context: BrowserAxTargetContext): Boolean =
        SAFE_ID.matches(context.targetId) &&
            context.connectionEpoch >= 0 &&
            context.documentIdentity.isNotBlank() &&
            safeUrl(context.expectedUrl)

    private fun safeUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }.getOrDefault(false)

    private fun safeId(value: String): String? = value.takeIf { SAFE_ID.matches(it) }

    private fun originClass(childUrl: String, rootUrl: String): String {
        val childOrigin = origin(childUrl)
        val rootOrigin = origin(rootUrl)
        return when {
            childOrigin == null || rootOrigin == null -> "opaque"
            childOrigin == rootOrigin -> "same"
            else -> "cross"
        }
    }

    private fun origin(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            null
        } else {
            val scheme = uri.scheme.lowercase()
            val port = when (uri.port) {
                -1 -> if (scheme == "https") 443 else 80
                else -> uri.port
            }
            "$scheme://${uri.host.lowercase()}:$port"
        }
    }.getOrNull()

    private fun failure(code: String, session: Session) = BrowserAxTreeObservation(
        code = code,
        data = JSONObject().put("reason", code),
        capabilities = session.capabilities.toMap(),
        commandCount = session.commandCount,
    )

    private fun mapFailure(error: Throwable): String {
        if (error is TimeoutException || error.message?.contains("DEADLINE_EXPIRED") == true) {
            return "DEADLINE_EXPIRED"
        }
        if (error.message?.contains("NOT_AUTHORIZED") == true) return "NOT_AUTHORIZED"
        if (error.message?.contains("IDENTITY_CHANGED") == true) return "IDENTITY_CHANGED"
        if (error.message?.contains("CLOSED") == true ||
            error.message?.contains("TARGET_UNAVAILABLE") == true
        ) {
            return "TARGET_UNAVAILABLE"
        }
        if (error is CdpCommandRejectedException &&
            isUnsupportedCdpMethod(error.protocolCode, error.protocolMessage)
        ) {
            return "UNSUPPORTED"
        }
        return "AX_OBSERVATION_FAILED"
    }

    companion object {
        // before identity + inventory + enable + root + max 7 frame retries + after identity
        private const val MAX_COMMANDS = 12
        private const val MAX_OUTPUT_BYTES = 12 * 1024
        private const val MAX_FRAMES = 32
        private const val MAX_FRAME_DEPTH = 16
        private const val MAX_CHILD_REFS = 16
        private const val MAX_STATES = 12
        private const val MAX_ROLE_CHARS = 64
        private const val MAX_NAME_CHARS = 512
        private val SAFE_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val AX_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val AX_SCOPE = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private const val UNATTRIBUTED_FRAME_SCOPE = "unattributed"
        private val STATE_NAMES = setOf(
            "focused", "disabled", "required", "readonly", "busy",
            "modal", "multiselectable", "expanded", "selected", "checked",
        )
        private val FATAL_CODES = setOf(
            "NOT_AUTHORIZED",
            "DEADLINE_EXPIRED",
            "IDENTITY_CHANGED",
            "COMMAND_LIMIT",
            "AX_NODE_CONFLICT",
        )
        private const val IDENTITY_EXPRESSION =
            "JSON.stringify({href:location.href,doc:String(performance.timeOrigin)})"
    }
}
