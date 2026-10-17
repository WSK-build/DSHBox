package com.dshbox.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

internal sealed interface BrowserAxAction {
    data object Click : BrowserAxAction

    data class Type(
        val text: String,
        val replace: Boolean = true,
    ) : BrowserAxAction

    data class Scroll(
        val direction: BrowserAxScrollDirection,
        val steps: Int = 1,
    ) : BrowserAxAction
}

internal enum class BrowserAxScrollDirection {
    UP,
    DOWN,
}

/**
 * Executes private AX candidates over the current Browser-owned CDP transport.
 * Callers must resolve candidates and identities before invoking this bridge.
 */
internal class BrowserAxActionBridge(
    private val transport: BrowserAxTreeTransport,
    private val documentIdentity: () -> String?,
    private val nowElapsedMillis: () -> Long,
) {
    private inner class Session(
        private val identity: BrowserAxActionReferences.Identity,
        private val deadlineElapsedMillis: Long,
        private val stillAuthorized: () -> Boolean,
    ) {
        var commandCount: Int = 0
            private set

        private fun authorized(): Boolean =
            nowElapsedMillis() < deadlineElapsedMillis &&
                runCatching(stillAuthorized).getOrDefault(false)

        private fun bound(): Boolean =
            transport.targetId == identity.targetId &&
                transport.connectionEpoch == identity.connectionEpoch &&
                runCatching(documentIdentity).getOrNull() == identity.documentIdentity

        fun stateFailure(requireBound: Boolean, sideEffectPossible: Boolean): BridgeFailure? = when {
            nowElapsedMillis() >= deadlineElapsedMillis ->
                BridgeFailure("DEADLINE_EXPIRED", sideEffectPossible, "deadline_elapsed")
            requireBound && !bound() ->
                BridgeFailure("IDENTITY_CHANGED", sideEffectPossible, "binding_changed")
            !authorized() ->
                BridgeFailure("NOT_AUTHORIZED", sideEffectPossible, "authorization_revoked")
            else -> null
        }

        fun call(
            method: String,
            params: JSONObject,
            requireBound: Boolean = true,
            sideEffectPossible: Boolean = false,
        ): JSONObject {
            stateFailure(requireBound, false)?.let { throw it }
            val normalCommandLimit = MAX_COMMANDS - 1
            val commandLimit = if (method == RELEASE_METHOD) MAX_COMMANDS else normalCommandLimit
            if (commandCount >= commandLimit) {
                throw BridgeFailure("COMMAND_LIMIT", false, "command_budget_exhausted")
            }

            commandCount++
            val response = try {
                transport.call(
                    method,
                    params,
                    deadlineElapsedMillis,
                    { authorized() && (!requireBound || bound()) },
                )
            } catch (error: BridgeFailure) {
                throw error
            } catch (error: CdpOutcomeUnknownException) {
                if (sideEffectPossible) {
                    throw BridgeFailure("UNKNOWN", true, "cdp_outcome_unknown")
                }
                throw BridgeFailure("AX_ACTION_FAILED", false, "read_outcome_unknown")
            } catch (error: CdpCommandRejectedException) {
                val unsupported = isUnsupportedCdpMethod(error.protocolCode, error.protocolMessage)
                throw BridgeFailure(
                    if (unsupported) "UNSUPPORTED" else "CDP_REJECTED",
                    false,
                    if (unsupported) "cdp_method_unsupported" else "cdp_command_rejected",
                )
            } catch (error: Exception) {
                stateFailure(requireBound, sideEffectPossible)?.let { throw it }
                if (sideEffectPossible) throw BridgeFailure("UNKNOWN", true, "dispatch_transport_failure")
                throw BridgeFailure(mapTransportFailure(error), false, "transport_failure")
            }

            stateFailure(requireBound, sideEffectPossible)?.let { throw it }
            return response
        }

        fun releaseObjectGroup(objectGroup: String): Boolean {
            if (commandCount >= MAX_COMMANDS) return false
            commandCount++
            val cleanupDeadline = nowElapsedMillis().coerceAtMost(Long.MAX_VALUE - 250L) + 250L
            return runCatching { transport.releaseObjectGroup(objectGroup, cleanupDeadline) }.isSuccess
        }

        fun requireNormalCommandBudget(requiredCommands: Int) {
            val normalCommandLimit = MAX_COMMANDS - 1
            if (requiredCommands < 0 || commandCount > normalCommandLimit - requiredCommands) {
                throw BridgeFailure("COMMAND_LIMIT", false, "command_budget_insufficient")
            }
        }
    }

    private data class BridgeFailure(
        val code: String,
        val sideEffectPossible: Boolean,
        val reason: String,
    ) : Exception(code)

    private data class Outcome(
        val code: String,
        val executed: Boolean?,
        val data: JSONObject = JSONObject(),
    )

    private data class FrameNode(
        val frameId: String,
        val url: String,
        val children: List<FrameNode>,
    )

    private data class FrameLookup(
        val chain: List<FrameNode>?,
        val deeperThanLimit: Boolean,
    )

    private data class FunctionResult(
        val code: String,
        val href: String,
        val documentToken: String,
        val raw: JSONObject,
    )

    fun execute(
        candidate: BrowserAxActionCandidate,
        identity: BrowserAxActionReferences.Identity,
        action: BrowserAxAction,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
    ): JSONObject {
        validateInputs(candidate, identity, action, deadlineElapsedMillis, stillAuthorized)
            ?.let { return it }

        val session = Session(identity, deadlineElapsedMillis, stillAuthorized)
        val objectGroup = "${OBJECT_GROUP_PREFIX}${GROUP_SEQUENCE.incrementAndGet()}"
        var objectGroupUsed = false
        val outcome = try {
            executeChecked(candidate, identity, action, deadlineElapsedMillis, stillAuthorized,
                session, objectGroup) { objectGroupUsed = true }
        } catch (error: BridgeFailure) {
            if (error.code == "UNKNOWN" || error.sideEffectPossible) unknown(error.reason) else {
                Outcome(error.code, false, JSONObject().put("reason", error.reason))
            }
        } catch (error: Exception) {
            val reason = transportFailureReason(error)
            if (reason != null) Outcome(reason, false, JSONObject().put("reason", reason.lowercase()))
            else unknown("unexpected_action_failure")
        }

        val released = if (objectGroupUsed) {
            releaseObjectGroup(session, objectGroup)
        } else {
            null
        }
        val data = JSONObject(outcome.data.toString())
        released?.let { data.put("objectGroupReleased", it) }
        if (outcome.executed == null) {
            data.put("nonRetryable", true)
                .put("sideEffectsMayHaveOccurred", true)
        }
        return JSONObject()
            .put("code", outcome.code)
            .put("executed", outcome.executed ?: JSONObject.NULL)
            .put("data", data)
    }

    private fun executeChecked(
        candidate: BrowserAxActionCandidate,
        identity: BrowserAxActionReferences.Identity,
        action: BrowserAxAction,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
        session: Session,
        objectGroup: String,
        markObjectGroupUsed: () -> Unit,
    ): Outcome {
        session.stateFailure(true, false)?.let { throw it }
        val frameTree = session.call("Page.getFrameTree", JSONObject())
        val root = readFrameTree(frameTree)
        val lookup = findFrameChain(root, candidate.frameId)
        val chain = lookup.chain ?: throw if (lookup.deeperThanLimit) {
            BridgeFailure("UNSUPPORTED", false, "frame_chain_exceeds_four_levels")
        } else {
            BridgeFailure("STALE_REFERENCE", false, "candidate_frame_not_current")
        }
        val requiredCommands = (chain.size - 1) * FRAME_OWNER_NORMAL_COMMANDS +
            TARGET_NORMAL_COMMANDS
        session.requireNormalCommandBudget(requiredCommands)
        val executionContexts = mutableMapOf<String, Int>()

        verifyFrameOwners(
            chain = chain,
            identity = identity,
            deadlineElapsedMillis = deadlineElapsedMillis,
            stillAuthorized = stillAuthorized,
            session = session,
            objectGroup = objectGroup,
            markObjectGroupUsed = markObjectGroupUsed,
            executionContexts = executionContexts,
        )

        val targetExecutionContextId = isolatedWorldContext(
            session = session,
            frameId = chain.last().frameId,
            executionContexts = executionContexts,
        )
        markObjectGroupUsed()
        val resolveParams = JSONObject()
            .put("backendNodeId", candidate.backendNodeId)
            .put("objectGroup", objectGroup)
            .put("executionContextId", targetExecutionContextId)
        val targetObject = try {
            session.call("DOM.resolveNode", resolveParams)
        } catch (error: BridgeFailure) {
            if (error.code == "IDENTITY_CHANGED" || error.code == "NOT_AUTHORIZED" ||
                error.code == "DEADLINE_EXPIRED" || error.code == "COMMAND_LIMIT"
            ) {
                throw error
            }
            throw BridgeFailure("UNSUPPORTED", false, "frame_oopif_or_node_unavailable")
        }
        val targetObjectId = safeObjectId(
            targetObject.optJSONObject("object")?.optString("objectId").orEmpty(),
        )
            ?: throw BridgeFailure("UNSUPPORTED", false, "frame_oopif_or_node_unavailable")

        val targetFrame = chain.last()
        val before = callTargetFunction(
            session = session,
            objectId = targetObjectId,
            objectGroup = objectGroup,
            mode = VALIDATE_MODE,
            action = action,
            sideEffectPossible = false,
        )
        if (before.code != "OK") {
            return Outcome(
                before.code,
                false,
                JSONObject().put("reason", before.code.lowercase()).put("stage", "validation"),
            )
        }
        if (!sameFrameLocation(before.href, targetFrame.url)) {
            return Outcome("IDENTITY_CHANGED", false,
                JSONObject().put("reason", "frame_document_changed"))
        }
        session.stateFailure(true, false)?.let { throw it }

        var actionResponse: FunctionResult? = null
        try {
            actionResponse = callTargetFunction(
                session = session,
                objectId = targetObjectId,
                objectGroup = objectGroup,
                mode = EXECUTE_MODE,
                action = action,
                sideEffectPossible = true,
                expectedHref = before.href,
                expectedDocumentToken = before.documentToken,
            )
        } catch (error: BridgeFailure) {
            if (error.code == "CDP_REJECTED") {
                return Outcome("ACTION_REJECTED", false,
                    JSONObject().put("reason", "cdp_command_rejected").put("stage", "dispatch"))
            }
            if (error.code == "UNSUPPORTED") {
                return Outcome("UNSUPPORTED", false,
                    JSONObject().put("reason", error.reason).put("stage", "dispatch"))
            }
            throw error
        }
        val response = actionResponse

        if (response.code == "DOCUMENT_CHANGED_BEFORE_DISPATCH") {
            return Outcome("IDENTITY_CHANGED", false, JSONObject()
                .put("reason", "document_changed_before_dispatch").put("stage", "validation"))
        }
        session.stateFailure(true, true)?.let { throw it }
        if (response.href != before.href || response.documentToken != before.documentToken) {
            return unknown("document_changed_after_dispatch")
        }
        if (response.code == "OK") {
            val data = JSONObject().put("action", actionName(action))
            response.raw.optDouble("scrollTop", Double.NaN).takeIf { it.isFinite() }?.let {
                data.put("scrollTop", it)
            }
            return Outcome("OK", true, data)
        }
        if (response.code in DEFINITE_REJECTION_CODES) {
            return Outcome(response.code, false,
                JSONObject().put("reason", response.code.lowercase()).put("stage", "dispatch"))
        }
        if (response.code == "NO_EFFECT") {
            return Outcome("NO_EFFECT", true,
                JSONObject().put("action", actionName(action)))
        }
        return unknown("side_effect_outcome_unknown")
    }

    private fun verifyFrameOwners(
        chain: List<FrameNode>,
        identity: BrowserAxActionReferences.Identity,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
        session: Session,
        objectGroup: String,
        markObjectGroupUsed: () -> Unit,
        executionContexts: MutableMap<String, Int>,
    ) {
        if (chain.size <= 1) return

        for (index in 1 until chain.size) {
            val child = chain[index]
            val parent = chain[index - 1]
            val parentExecutionContextId = isolatedWorldContext(
                session = session,
                frameId = parent.frameId,
                executionContexts = executionContexts,
            )
            val owner = try {
                session.call(
                    "DOM.getFrameOwner",
                    JSONObject().put("frameId", child.frameId),
                )
            } catch (error: BridgeFailure) {
                if (error.sideEffectPossible || error.code == "IDENTITY_CHANGED" ||
                    error.code == "NOT_AUTHORIZED" || error.code == "DEADLINE_EXPIRED"
                ) {
                    throw error
                }
                throw BridgeFailure("UNSUPPORTED", false, "oo_pif_frame_owner_unavailable")
            }
            val ownerBackendNodeId = owner.optInt("backendNodeId", -1)
            if (ownerBackendNodeId <= 0) {
                throw BridgeFailure("UNSUPPORTED", false, "oo_pif_frame_owner_unavailable")
            }

            val described = try {
                session.call(
                    "DOM.describeNode",
                    JSONObject().put("backendNodeId", ownerBackendNodeId).put("depth", 1),
                )
            } catch (error: BridgeFailure) {
                if (error.sideEffectPossible || error.code == "IDENTITY_CHANGED" ||
                    error.code == "NOT_AUTHORIZED" || error.code == "DEADLINE_EXPIRED"
                ) {
                    throw error
                }
                throw BridgeFailure("UNSUPPORTED", false, "frame_owner_not_visible_in_current_target")
            }
            val ownerNode = described.optJSONObject("node")
            if (ownerNode == null ||
                ownerNode.optInt("backendNodeId", -1) != ownerBackendNodeId ||
                ownerNode.optString("frameId") != child.frameId
            ) {
                throw BridgeFailure("UNSUPPORTED", false, "oo_pif_frame_owner_unverified")
            }

            markObjectGroupUsed()
            val resolved = try {
                session.call(
                    "DOM.resolveNode",
                    JSONObject()
                        .put("backendNodeId", ownerBackendNodeId)
                        .put("objectGroup", objectGroup)
                        .put("executionContextId", parentExecutionContextId),
                )
            } catch (error: BridgeFailure) {
                if (error.sideEffectPossible || error.code == "IDENTITY_CHANGED" ||
                    error.code == "NOT_AUTHORIZED" || error.code == "DEADLINE_EXPIRED"
                ) {
                    throw error
                }
                throw BridgeFailure("UNSUPPORTED", false, "oo_pif_frame_owner_unresolved")
            }
            val ownerObjectId = safeObjectId(
                resolved.optJSONObject("object")?.optString("objectId").orEmpty(),
            )
                ?: throw BridgeFailure("UNSUPPORTED", false, "oo_pif_frame_owner_unresolved")

            val args = JSONArray()
                .put(callArgument(VALIDATE_MODE))
                .put(callArgument(FRAME_OWNER_CODE))
                .put(callArgument(""))
                .put(callArgument(true))
                .put(callArgument(""))
                .put(callArgument(1))
            val params = functionParams(ownerObjectId, objectGroup, FRAME_OWNER_FUNCTION, args)
            val verification = session.call(
                "Runtime.callFunctionOn",
                params,
                sideEffectPossible = false,
            )
            val result = parseFunctionResult(verification)
                ?: throw BridgeFailure("UNSUPPORTED", false, "frame_owner_verification_failed")
            if (result.code != "OK") {
                throw BridgeFailure(
                    result.code,
                    false,
                    "frame_owner_${result.code.lowercase()}",
                )
            }
            if (!sameFrameLocation(result.href, parent.url)) {
                throw BridgeFailure("IDENTITY_CHANGED", false, "parent_frame_document_changed")
            }
            session.stateFailure(true, false)?.let { throw it }
        }
    }

    private fun isolatedWorldContext(
        session: Session,
        frameId: String,
        executionContexts: MutableMap<String, Int>,
    ): Int {
        executionContexts[frameId]?.let { return it }
        val response = try {
            session.call(
                "Page.createIsolatedWorld",
                JSONObject()
                    .put("frameId", frameId)
                    .put("worldName", ISOLATED_WORLD_NAME)
                    .put("grantUniveralAccess", false),
            )
        } catch (error: BridgeFailure) {
            when (error.code) {
                "IDENTITY_CHANGED", "NOT_AUTHORIZED", "DEADLINE_EXPIRED", "COMMAND_LIMIT" -> throw error
                "UNSUPPORTED", "CDP_REJECTED" ->
                    throw BridgeFailure("UNSUPPORTED", false, "isolated_world_unsupported")
                else -> throw error
            }
        }
        val executionContextId = response.opt("executionContextId") as? Int
        if (executionContextId == null || executionContextId <= 0) {
            throw BridgeFailure("UNSUPPORTED", false, "isolated_world_context_invalid")
        }
        executionContexts[frameId] = executionContextId
        return executionContextId
    }

    private fun callTargetFunction(
        session: Session,
        objectId: String,
        objectGroup: String,
        mode: String,
        action: BrowserAxAction,
        sideEffectPossible: Boolean,
        expectedHref: String = "",
        expectedDocumentToken: String = "",
    ): FunctionResult {
        val (code, text, replace, direction, steps) = actionArguments(action)
        val args = JSONArray()
            .put(callArgument(mode))
            .put(callArgument(code))
            .put(callArgument(text))
            .put(callArgument(replace))
            .put(callArgument(direction))
            .put(callArgument(steps))
            .put(callArgument(expectedHref))
            .put(callArgument(expectedDocumentToken))
        val response = session.call(
            "Runtime.callFunctionOn",
            functionParams(objectId, objectGroup, TARGET_FUNCTION, args),
            sideEffectPossible = sideEffectPossible,
        )
        if (response.has("exceptionDetails")) {
            if (sideEffectPossible) throw BridgeFailure("UNKNOWN", true, "target_function_exception")
            throw BridgeFailure("ACTION_REJECTED", false, "target_validation_exception")
        }
        return parseFunctionResult(response)
            ?: if (sideEffectPossible) {
                throw BridgeFailure("UNKNOWN", true, "target_result_missing")
            } else {
                throw BridgeFailure("ACTION_REJECTED", false, "target_result_missing")
            }
    }

    private fun parseFunctionResult(response: JSONObject): FunctionResult? {
        val value = response.optJSONObject("result")?.opt("value") as? JSONObject ?: return null
        return FunctionResult(
            code = value.optString("code"),
            href = value.optString("href"),
            documentToken = value.optString("doc"),
            raw = value,
        ).takeIf { it.code.isNotBlank() }
    }

    private fun releaseObjectGroup(session: Session, objectGroup: String): Boolean =
        session.releaseObjectGroup(objectGroup)

    private fun validateInputs(
        candidate: BrowserAxActionCandidate,
        identity: BrowserAxActionReferences.Identity,
        action: BrowserAxAction,
        deadlineElapsedMillis: Long,
        stillAuthorized: () -> Boolean,
    ): JSONObject? {
        val invalidReason = when {
            candidate.backendNodeId <= 0 -> "invalid_backend_node"
            !FRAME_ID.matches(candidate.frameId) -> "invalid_frame_identity"
            identity.targetId.isBlank() || identity.documentIdentity.isBlank() ||
                identity.connectionEpoch < 0 || identity.authorizationEpoch < 0 ->
                "invalid_binding_identity"
            action is BrowserAxAction.Type && action.text.length > MAX_TYPE_CHARACTERS ->
                "type_value_too_large"
            action is BrowserAxAction.Scroll && action.steps !in 1..MAX_SCROLL_STEPS ->
                "scroll_steps_out_of_range"
            else -> null
        }
        if (invalidReason != null) {
            return failure("INVALID_ARGUMENT", invalidReason)
        }
        if (nowElapsedMillis() >= deadlineElapsedMillis) {
            return failure("DEADLINE_EXPIRED", "deadline_elapsed")
        }
        if (runCatching(stillAuthorized).getOrDefault(false)) return null
        return failure("NOT_AUTHORIZED", "authorization_revoked")
    }

    private fun failure(code: String, reason: String): JSONObject = JSONObject()
        .put("code", code)
        .put("executed", false)
        .put("data", JSONObject().put("reason", reason))

    private fun unknown(reason: String): Outcome = Outcome(
        "UNKNOWN",
        null,
        JSONObject().put("reason", reason),
    )

    private fun transportFailureReason(error: Throwable): String? {
        val message = generateSequence(error as Throwable?) { it.cause }
            .take(8)
            .joinToString("|") { it.message.orEmpty() }
        return when {
            message.contains("NOT_AUTHORIZED") -> "NOT_AUTHORIZED"
            message.contains("DEADLINE_EXPIRED") -> "DEADLINE_EXPIRED"
            message.contains("IDENTITY_CHANGED") -> "IDENTITY_CHANGED"
            message.contains("CDP_CLOSED") || message.contains("TARGET_UNAVAILABLE") ->
                "TARGET_UNAVAILABLE"
            message.contains("BUSY") || message.contains("FRAME_TOO_LARGE") ||
                message.contains("INVALID_SESSION") -> "ACTION_REJECTED"
            else -> null
        }
    }

    private fun mapTransportFailure(error: Throwable): String =
        transportFailureReason(error) ?: "AX_ACTION_FAILED"

    private fun readFrameTree(response: JSONObject): FrameNode {
        val root = response.optJSONObject("frameTree")?.optJSONObject("frame")
            ?: throw BridgeFailure("TARGET_UNAVAILABLE", false, "frame_tree_missing")
        val rootId = safeFrameId(root.optString("id"))
            ?: throw BridgeFailure("TARGET_UNAVAILABLE", false, "root_frame_missing")
        return FrameNode(rootId, root.optString("url"), readChildFrames(response.optJSONObject("frameTree"), 0, intArrayOf(1)))
    }

    private fun readChildFrames(tree: JSONObject?, depth: Int, count: IntArray): List<FrameNode> {
        val children = tree?.optJSONArray("childFrames") ?: return emptyList()
        if ((depth >= 8 && children.length() > 0) || children.length() > 128 - count[0]) {
            throw BridgeFailure("UNSUPPORTED", false, "frame_tree_budget_exceeded")
        }
        return (0 until children.length()).mapNotNull { index ->
            if (++count[0] > 128) throw BridgeFailure("UNSUPPORTED", false, "frame_tree_budget_exceeded")
            val childTree = children.optJSONObject(index) ?: return@mapNotNull null
            val child = childTree.optJSONObject("frame") ?: return@mapNotNull null
            val childId = safeFrameId(child.optString("id")) ?: return@mapNotNull null
            FrameNode(childId, child.optString("url"), readChildFrames(childTree, depth + 1, count))
        }
    }

    private fun findFrameChain(root: FrameNode, targetFrameId: String): FrameLookup {
        var deeperThanLimit = false
        val visited = mutableSetOf<String>()

        fun visit(node: FrameNode, parents: List<FrameNode>, depth: Int): List<FrameNode>? {
            if (!visited.add(node.frameId)) return null
            val current = parents + node
            if (node.frameId == targetFrameId) return current
            if (depth >= MAX_FRAME_CHAIN_LEVELS) {
                if (node.children.isNotEmpty()) deeperThanLimit = true
                return null
            }
            node.children.forEach { child ->
                visit(child, current, depth + 1)?.let { return it }
            }
            return null
        }

        val chain = visit(root, emptyList(), 0)
        return FrameLookup(chain, deeperThanLimit && chain == null)
    }

    private fun actionArguments(action: BrowserAxAction): ActionArguments = when (action) {
        BrowserAxAction.Click -> ActionArguments(CLICK_CODE, "", true, "", 1)
        is BrowserAxAction.Type -> ActionArguments(TYPE_CODE, action.text, action.replace, "", 1)
        is BrowserAxAction.Scroll -> ActionArguments(
            SCROLL_CODE,
            "",
            true,
            action.direction.name.lowercase(),
            action.steps,
        )
    }

    private data class ActionArguments(
        val code: String,
        val text: String,
        val replace: Boolean,
        val direction: String,
        val steps: Int,
    )

    private fun functionParams(
        objectId: String,
        objectGroup: String,
        functionDeclaration: String,
        arguments: JSONArray,
    ): JSONObject = JSONObject()
        .put("objectId", objectId)
        .put("functionDeclaration", functionDeclaration)
        .put("arguments", arguments)
        .put("returnByValue", true)
        .put("awaitPromise", false)
        .put("userGesture", false)
        .put("objectGroup", objectGroup)

    private fun callArgument(value: Any?): JSONObject = JSONObject().put("value", value)

    private fun safeObjectId(value: String): String? =
        value.takeIf { it.isNotBlank() && it.length <= 512 && '\u0000' !in it }

    private fun safeFrameId(value: String): String? =
        value.takeIf { FRAME_ID.matches(it) }

    private fun sameFrameLocation(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true
        val leftBase = left.substringBefore('#')
        val rightBase = right.substringBefore('#')
        if (leftBase == rightBase) return true
        return runCatching {
            val a = java.net.URI(leftBase)
            val b = java.net.URI(rightBase)
            val leftSecure = a.scheme.equals("https", true)
            val rightSecure = b.scheme.equals("https", true)
            a.scheme.equals(b.scheme, true) &&
                a.host.equals(b.host, true) &&
                effectivePort(a.scheme, a.port, leftSecure) == effectivePort(b.scheme, b.port, rightSecure) &&
                a.rawPath == b.rawPath &&
                a.rawQuery == b.rawQuery
        }.getOrDefault(false)
    }

    private fun effectivePort(scheme: String?, port: Int, secure: Boolean): Int = when {
        port != -1 -> port
        secure -> 443
        scheme.equals("http", true) -> 80
        else -> -1
    }

    private fun actionName(action: BrowserAxAction): String = when (action) {
        BrowserAxAction.Click -> CLICK_CODE
        is BrowserAxAction.Type -> TYPE_CODE
        is BrowserAxAction.Scroll -> SCROLL_CODE
    }

    private companion object {
        const val MAX_COMMANDS = 24
        const val FRAME_OWNER_NORMAL_COMMANDS = 5
        const val TARGET_NORMAL_COMMANDS = 4
        const val MAX_FRAME_CHAIN_LEVELS = 4
        const val MAX_TYPE_CHARACTERS = 4096
        const val MAX_SCROLL_STEPS = 10
        const val RELEASE_METHOD = "Runtime.releaseObjectGroup"
        const val OBJECT_GROUP_PREFIX = "browser-ax-action-"
        const val ISOLATED_WORLD_NAME = "browser-ax-action"
        const val VALIDATE_MODE = "validate"
        const val EXECUTE_MODE = "execute"
        const val FRAME_OWNER_CODE = "frameOwner"
        const val CLICK_CODE = "click"
        const val TYPE_CODE = "type"
        const val SCROLL_CODE = "scroll"
        val GROUP_SEQUENCE = AtomicLong(0L)
        val DEFINITE_REJECTION_CODES = setOf(
            "ACTION_REJECTED",
            "DISABLED",
            "HIDDEN",
            "NOT_ACTIONABLE",
            "NOT_VISIBLE",
            "OBSCURED",
            "OUTSIDE_VIEWPORT",
            "READONLY",
            "NOT_SCROLLABLE",
            "UNSUPPORTED",
        )
        val FRAME_ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")

        val FRAME_OWNER_FUNCTION = """
            function() {
              const parent = node => node && node.parentElement
                ? node.parentElement
                : (node && node.getRootNode && node.getRootNode().host) || null;
              const composedContains = (root, node) => {
                let depth = 0;
                for (let current = node; current && depth++ < 64; current = parent(current)) {
                  if (current === root) return true;
                }
                return false;
              };
              try {
                if (!this || this.nodeType !== 1 ||
                    !['IFRAME', 'FRAME'].includes(String(this.tagName).toUpperCase())) {
                  return {code: 'UNSUPPORTED'};
                }
                const doc = this.ownerDocument;
                const win = doc && doc.defaultView;
                if (!doc || !win || this.getRootNode({composed: true}) !== doc || !this.isConnected) {
                  return {code: 'HIDDEN'};
                }
                let depth = 0;
                for (let node = this; node; node = parent(node)) {
                  if (depth++ >= 64 || node.getRootNode().mode === 'closed') return {code: 'UNSUPPORTED'};
                  if (!node.isConnected || node.hidden ||
                      node.getAttribute('aria-hidden') === 'true') return {code: 'HIDDEN'};
                  const style = win.getComputedStyle(node);
                  if (style.display === 'none' || style.visibility === 'hidden' ||
                      style.visibility === 'collapse' || style.contentVisibility === 'hidden' ||
                      Number(style.opacity) === 0 || style.pointerEvents === 'none') {
                    return {code: 'NOT_VISIBLE'};
                  }
                }
                const rect = this.getBoundingClientRect();
                if (!(rect.width > 0) || !(rect.height > 0) || this.getClientRects().length === 0) {
                  return {code: 'NOT_VISIBLE'};
                }
                const viewport = win.visualViewport;
                const left = viewport ? viewport.offsetLeft : 0;
                const top = viewport ? viewport.offsetTop : 0;
                const width = viewport ? viewport.width : win.innerWidth;
                const height = viewport ? viewport.height : win.innerHeight;
                const x = rect.left + rect.width / 2;
                const y = rect.top + rect.height / 2;
                if (!Number.isFinite(x) || !Number.isFinite(y) || x < left || y < top ||
                    x >= left + width || y >= top + height) {
                  return {code: 'OUTSIDE_VIEWPORT'};
                }
                let hit = doc.elementFromPoint(x, y);
                for (let hops = 0; hit && hit.shadowRoot && hops < 8; hops++) {
                  const next = hit.shadowRoot.elementFromPoint(x, y);
                  if (!next || next === hit) break;
                  hit = next;
                }
                if (!(hit === this || composedContains(this, hit))) {
                  return {code: 'OBSCURED'};
                }
                return {
                  code: 'OK',
                  href: String(win.location.href),
                  doc: String(win.performance.timeOrigin)
                };
              } catch (_) {
                return {code: 'ACTION_REJECTED'};
              }
            }
        """.trimIndent()

        val TARGET_FUNCTION = """
            function(mode, actionCode, value, replace, direction, steps, expectedHref, expectedDocumentToken) {
              const parent = node => node && node.parentElement
                ? node.parentElement
                : (node && node.getRootNode && node.getRootNode().host) || null;
              const composedContains = (root, node) => {
                let depth = 0;
                for (let current = node; current && depth++ < 64; current = parent(current)) {
                  if (current === root) return true;
                }
                return false;
              };
              const associatedLabel = (target, hit) => {
                if (!target || !hit || hit.ownerDocument !== target.ownerDocument ||
                    String(hit.tagName).toUpperCase() !== 'LABEL') return false;
                try {
                  if (hit.control === target) return true;
                  if (!target.id || hit.getAttribute('for') !== target.id) return false;
                  const labels = target.labels;
                  for (let index = 0; labels && index < Math.min(labels.length, 16); index++) {
                    if (labels[index] === hit) return true;
                  }
                  return false;
                } catch (_) {
                  return false;
                }
              };
              const reject = code => ({
                code,
                href: String(window.location.href),
                doc: String(performance.timeOrigin)
              });
              try {
                if (!this || this.nodeType !== 1 || !this.isConnected) return reject('NOT_ACTIONABLE');
                const doc = this.ownerDocument;
                const win = doc && doc.defaultView;
                if (!doc || !win || this.getRootNode({composed: true}) !== doc) return reject('NOT_VISIBLE');
                if (mode === 'execute' &&
                    (!expectedHref || !expectedDocumentToken || String(win.location.href) !== expectedHref ||
                     String(win.performance.timeOrigin) !== expectedDocumentToken)) {
                  return reject('DOCUMENT_CHANGED_BEFORE_DISPATCH');
                }

                let depth = 0;
                for (let node = this; node; node = parent(node)) {
                  if (depth++ >= 64 || node.getRootNode().mode === 'closed') return reject('UNSUPPORTED');
                  if (!node.isConnected || node.hidden ||
                      node.getAttribute('aria-hidden') === 'true') return reject('HIDDEN');
                  const style = win.getComputedStyle(node);
                  if (style.display === 'none' || style.visibility === 'hidden' ||
                      style.visibility === 'collapse' || style.contentVisibility === 'hidden' ||
                      Number(style.opacity) === 0 || style.pointerEvents === 'none') {
                    return reject('NOT_VISIBLE');
                  }
                }
                if (this.matches(':disabled') || this.closest('[aria-disabled="true"]')) {
                  return reject('DISABLED');
                }
                const rect = this.getBoundingClientRect();
                if (!(rect.width > 0) || !(rect.height > 0) || this.getClientRects().length === 0) {
                  return reject('NOT_VISIBLE');
                }
                const viewport = win.visualViewport;
                const left = viewport ? viewport.offsetLeft : 0;
                const top = viewport ? viewport.offsetTop : 0;
                const width = viewport ? viewport.width : win.innerWidth;
                const height = viewport ? viewport.height : win.innerHeight;
                const x = rect.left + rect.width / 2;
                const y = rect.top + rect.height / 2;
                if (!Number.isFinite(x) || !Number.isFinite(y) || x < left || y < top ||
                    x >= left + width || y >= top + height) {
                  return reject('OUTSIDE_VIEWPORT');
                }
                let hit = doc.elementFromPoint(x, y);
                for (let hops = 0; hit && hit.shadowRoot && hops < 8; hops++) {
                  const next = hit.shadowRoot.elementFromPoint(x, y);
                  if (!next || next === hit) break;
                  hit = next;
                }
                if (!(hit === this || composedContains(this, hit) || associatedLabel(this, hit))) {
                  return reject('OBSCURED');
                }

                const interactive = this.matches(
                  'a[href],button,label,input,textarea,select,summary,option,' +
                  '[contenteditable],[onclick],[tabindex]:not([tabindex="-1"]),' +
                  '[role="button"],[role="link"],[role="checkbox"],[role="radio"],' +
                  '[role="menuitem"],[role="tab"],[role="option"],[role="switch"],' +
                  '[role="slider"],[role="spinbutton"],[role="combobox"],[role="textbox"]'
                );
                if (actionCode === 'click' && !interactive && !this.isContentEditable) {
                  return reject('NOT_ACTIONABLE');
                }

                const tag = String(this.tagName).toUpperCase();
                const inputType = tag === 'INPUT' ? String(this.type || 'text').toLowerCase() : '';
                const textEditable = this.isContentEditable ||
                  (tag === 'TEXTAREA') ||
                  (tag === 'INPUT' && ['text', 'search', 'email', 'url', 'tel'].includes(inputType));
                if (actionCode === 'type') {
                  if (this.readOnly || this.matches(':read-only') ||
                      this.closest('[aria-readonly="true"]')) return reject('READONLY');
                  if (!textEditable) return reject('NOT_ACTIONABLE');
                  if (!this.isContentEditable) {
                    const prototype = tag === 'TEXTAREA'
                      ? win.HTMLTextAreaElement.prototype
                      : win.HTMLInputElement.prototype;
                    const descriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
                    if (!descriptor || !descriptor.set) return reject('NOT_ACTIONABLE');
                  }
                }
                if (actionCode === 'scroll') {
                  const style = win.getComputedStyle(this);
                  if (!['auto', 'scroll', 'overlay'].includes(style.overflowY) ||
                      this.scrollHeight <= this.clientHeight || this.clientHeight <= 0) {
                    return reject('NOT_SCROLLABLE');
                  }
                }
                if (mode === 'validate') {
                  return {
                    code: 'OK',
                    href: String(win.location.href),
                    doc: String(win.performance.timeOrigin)
                  };
                }

                if (actionCode === 'click') {
                  this.click();
                } else if (actionCode === 'type') {
                  if (this.isContentEditable) {
                    this.textContent = replace ? value : String(this.textContent || '') + value;
                  } else {
                    const prototype = tag === 'TEXTAREA'
                      ? win.HTMLTextAreaElement.prototype
                      : win.HTMLInputElement.prototype;
                    const descriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
                    const nextValue = replace ? value : String(this.value || '') + value;
                    descriptor.set.call(this, nextValue);
                  }
                  this.focus();
                  this.dispatchEvent(new win.InputEvent('input', {
                    bubbles: true,
                    inputType: 'insertText',
                    data: value
                  }));
                  this.dispatchEvent(new win.Event('change', {bubbles: true}));
                } else if (actionCode === 'scroll') {
                  const before = this.scrollTop;
                  const amount = Math.max(120, Math.floor(this.clientHeight * 0.8)) *
                    steps * (direction === 'up' ? -1 : 1);
                  this.scrollBy({top: amount, behavior: 'instant'});
                  if (before === this.scrollTop) return reject('NO_EFFECT');
                } else {
                  return reject('UNSUPPORTED');
                }
                const result = {
                  code: 'OK',
                  href: String(win.location.href),
                  doc: String(win.performance.timeOrigin)
                };
                if (actionCode === 'scroll') result.scrollTop = Number(this.scrollTop);
                return result;
              } catch (_) {
                return {
                  code: 'UNKNOWN',
                  href: String(window.location.href),
                  doc: String(performance.timeOrigin)
                };
              }
            }
        """.trimIndent()
    }
}
