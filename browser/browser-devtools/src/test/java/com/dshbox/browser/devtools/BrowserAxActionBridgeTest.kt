package com.dshbox.browser.devtools

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserAxActionBridgeTest {
    @Test
    fun genericFailureAfterDispatchCannotClaimNoExecutionOrReplay() {
        var effects = 0
        val transport = RecordingTransport { method, params, _ ->
            if (method == "Runtime.callFunctionOn" && params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute") {
                effects++
                throw java.io.IOException("BUSY connection lost after dispatch")
            }
            defaultResponse(method, params, "OK", "OK")
        }
        val result = bridge(transport).execute(BrowserAxActionCandidate(ROOT_FRAME, 71), identity,
            BrowserAxAction.Click, DEADLINE, { true })
        assertEquals("UNKNOWN", result.getString("code"))
        assertTrue(result.isNull("executed"))
        assertTrue(result.getJSONObject("data").getBoolean("nonRetryable"))
        assertTrue(result.getJSONObject("data").getBoolean("sideEffectsMayHaveOccurred"))
        assertEquals(1, effects)
        assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
    }

    @Test
    fun cleanupGetsAnIndependentDeadlineAfterActionExpiration() {
        var clock = NOW
        val transport = RecordingTransport { method, params, auth ->
            if (method == "Runtime.releaseObjectGroup") {
                assertTrue(auth())
            } else if (method == "Runtime.callFunctionOn" && params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute") {
                clock = DEADLINE
                throw java.io.IOException("late transport failure")
            }
            defaultResponse(method, params, "OK", "OK")
        }
        val result = BrowserAxActionBridge(transport, { identity.documentIdentity }, { clock })
            .execute(BrowserAxActionCandidate(ROOT_FRAME, 71), identity, BrowserAxAction.Click, DEADLINE, { true })
        assertEquals("UNKNOWN", result.getString("code"))
        val release = transport.requests.single { it.method == "Runtime.releaseObjectGroup" }
        assertEquals(DEADLINE + 250L, release.deadlineElapsedMillis)
        assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
    }

    @Test
    fun cleanupDoesNotDependOnRevokedActionAuthorization() {
        var authorized = true
        val transport = RecordingTransport { method, params, auth ->
            if (method == "Runtime.releaseObjectGroup") assertTrue(auth())
            else if (method == "Runtime.callFunctionOn" && params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute") authorized = false
            defaultResponse(method, params, "OK", "OK")
        }
        val result = bridge(transport).execute(BrowserAxActionCandidate(ROOT_FRAME, 71), identity,
            BrowserAxAction.Click, DEADLINE, { authorized })
        assertEquals("UNKNOWN", result.getString("code"))
        assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
        assertEquals(1, transport.requests.count { it.method == "Runtime.releaseObjectGroup" })
    }

    @Test
    fun clickUsesResolveCallFunctionOnAndReleaseObjectGroupRequests() {
        val transport = RecordingTransport()
        val result = bridge(transport).execute(
            candidate = BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity = identity,
            action = BrowserAxAction.Click,
            deadlineElapsedMillis = DEADLINE,
            stillAuthorized = { true },
        )

        assertEquals("OK", result.getString("code"))
        assertEquals(true, result.getBoolean("executed"))
        assertEquals(
            listOf(
                "Page.getFrameTree",
                "Page.createIsolatedWorld",
                "DOM.resolveNode",
                "Runtime.callFunctionOn",
                "Runtime.callFunctionOn",
                "Runtime.releaseObjectGroup",
            ),
            transport.requests.map { it.method },
        )

        val world = transport.requests[1].params
        assertEquals(ROOT_FRAME, world.getString("frameId"))
        assertEquals("browser-ax-action", world.getString("worldName"))
        assertFalse(world.getBoolean("grantUniveralAccess"))
        val resolve = transport.requests[2].params
        assertEquals(71, resolve.getInt("backendNodeId"))
        assertEquals(ROOT_CONTEXT_ID, resolve.getInt("executionContextId"))
        assertFalse(resolve.has("objectId"))
        assertFalse(resolve.has("frameId"))
        assertFalse(resolve.has("sessionId"))
        assertFalse(resolve.has("targetId"))
        val group = resolve.getString("objectGroup")
        assertTrue(group.startsWith("browser-ax-action-"))

        val validate = transport.requests[3].params
        val execute = transport.requests[4].params
        assertEquals(group, validate.getString("objectGroup"))
        assertEquals(group, execute.getString("objectGroup"))
        assertEquals("obj-target", validate.getString("objectId"))
        assertEquals("validate", validate.getJSONArray("arguments").getJSONObject(0).getString("value"))
        assertEquals("click", validate.getJSONArray("arguments").getJSONObject(1).getString("value"))
        assertEquals("execute", execute.getJSONArray("arguments").getJSONObject(0).getString("value"))
        assertEquals("click", execute.getJSONArray("arguments").getJSONObject(1).getString("value"))
        assertEquals(ROOT_URL, execute.getJSONArray("arguments").getJSONObject(6).getString("value"))
        assertEquals(DOCUMENT_TOKEN, execute.getJSONArray("arguments").getJSONObject(7).getString("value"))
        assertTrue(validate.getBoolean("returnByValue"))
        assertFalse(validate.getBoolean("awaitPromise"))
        assertFalse(validate.getBoolean("userGesture"))

        val release = transport.requests[5].params
        assertEquals(group, release.getString("objectGroup"))
        assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
        assertEquals(
            1,
            transport.requests.count { it.method == "Runtime.releaseObjectGroup" },
        )
        assertTrue(transport.requests.none { it.method == "Runtime.evaluate" })
    }

    @Test
    fun typePassesValueAsJsonCallArgumentWithoutInjectingSource() {
        val transport = RecordingTransport()
        val sensitiveValue = "\"\n);alert(1);//<x>"
        val result = bridge(transport).execute(
            BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity,
            BrowserAxAction.Type(sensitiveValue, replace = false),
            DEADLINE,
            { true },
        )

        assertEquals("OK", result.getString("code"))
        val execute = transport.requests.single {
            it.method == "Runtime.callFunctionOn" &&
                it.params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
        }.params
        val arguments = execute.getJSONArray("arguments")
        assertEquals("type", arguments.getJSONObject(1).getString("value"))
        assertEquals(sensitiveValue, arguments.getJSONObject(2).getString("value"))
        assertEquals(false, arguments.getJSONObject(3).getBoolean("value"))
        assertFalse(execute.getString("functionDeclaration").contains(sensitiveValue))
        assertFalse(execute.getString("functionDeclaration").contains("eval("))
        assertTrue(transport.requests.none { it.method == "Runtime.evaluate" })
    }

    @Test
    fun scrollPassesBoundedDirectionAndSteps() {
        val transport = RecordingTransport { method, params, _ ->
            defaultResponse(method, params, validationCode = "OK", actionCode = "OK")
        }
        val result = bridge(transport).execute(
            BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity,
            BrowserAxAction.Scroll(BrowserAxScrollDirection.UP, steps = 7),
            DEADLINE,
            { true },
        )

        assertEquals("OK", result.getString("code"))
        assertEquals(true, result.getBoolean("executed"))
        assertEquals(120.0, result.getJSONObject("data").getDouble("scrollTop"), 0.0)
        val execute = transport.requests.single {
            it.method == "Runtime.callFunctionOn" &&
                it.params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
        }.params
        val arguments = execute.getJSONArray("arguments")
        assertEquals("scroll", arguments.getJSONObject(1).getString("value"))
        assertEquals("up", arguments.getJSONObject(4).getString("value"))
        assertEquals(7, arguments.getJSONObject(5).getInt("value"))
    }

    @Test
    fun crossOriginParentFrameOwnersAreVerifiedStepwiseBeforeTargetResolution() {
        val transport = RecordingTransport(
            frameTree = nestedFrameTree(
                rootNode(ROOT_FRAME, ROOT_URL),
                childNode(CHILD_FRAME, "https://child.example/frame"),
            ),
        ) { method, params, _ ->
            when (method) {
                "DOM.getFrameOwner" -> JSONObject().put("backendNodeId", 900)
                "DOM.describeNode" -> JSONObject().put(
                    "node",
                    JSONObject()
                        .put("backendNodeId", 900)
                        .put("nodeType", 1)
                        .put("nodeName", "IFRAME")
                        .put("frameId", CHILD_FRAME),
                )
                "DOM.resolveNode" -> resolveResult(
                    if (params.getInt("backendNodeId") == 900) "obj-owner" else "obj-target",
                )
                else -> defaultResponse(method, params, "OK", "OK").also { response ->
                    if (method == "Runtime.callFunctionOn" && params.optString("objectId") == "obj-target") {
                        response.getJSONObject("result").getJSONObject("value")
                            .put("href", "https://child.example/frame")
                    }
                }
            }
        }

        val result = bridge(transport).execute(
            BrowserAxActionCandidate(CHILD_FRAME, 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )

        assertEquals("OK", result.getString("code"))
        assertEquals(
            listOf(
                "Page.getFrameTree",
                "Page.createIsolatedWorld",
                "DOM.getFrameOwner",
                "DOM.describeNode",
                "DOM.resolveNode",
                "Runtime.callFunctionOn",
                "Page.createIsolatedWorld",
                "DOM.resolveNode",
                "Runtime.callFunctionOn",
                "Runtime.callFunctionOn",
                "Runtime.releaseObjectGroup",
            ),
            transport.requests.map { it.method },
        )
        assertEquals(ROOT_FRAME, transport.requests[1].params.getString("frameId"))
        assertEquals(CHILD_FRAME, transport.requests[6].params.getString("frameId"))
        assertEquals(CHILD_FRAME, transport.requests[2].params.getString("frameId"))
        assertEquals(900, transport.requests[3].params.getInt("backendNodeId"))
        assertEquals(ROOT_CONTEXT_ID, transport.requests[4].params.getInt("executionContextId"))
        assertEquals(CHILD_CONTEXT_ID, transport.requests[7].params.getInt("executionContextId"))
        assertEquals(900, transport.requests[4].params.getInt("backendNodeId"))
        assertEquals("obj-owner", transport.requests[5].params.getString("objectId"))
        val ownerArgs = transport.requests[5].params.getJSONArray("arguments")
        assertEquals("frameOwner", ownerArgs.getJSONObject(1).getString("value"))
        assertEquals(71, transport.requests[7].params.getInt("backendNodeId"))
        assertEquals("execute", transport.requests[9].params.getJSONArray("arguments")
            .getJSONObject(0).getString("value"))
        assertTrue(transport.requests[5].params.getString("functionDeclaration")
            .contains("elementFromPoint"))
        assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
    }

    @Test
    fun isolatedWorldFailureIsUnsupportedAndNeverFallsBackToMainWorld() {
        val transport = RecordingTransport { method, _, _ ->
            when {
                method == "Page.createIsolatedWorld" ->
                    throw CdpCommandRejectedException(-32601, "Page.createIsolatedWorld wasn't found")
                method == "Page.getFrameTree" -> defaultResponse(method, JSONObject(), "OK", "OK")
                else -> error("unexpected command after isolated world failure: $method")
            }
        }
        val result = bridge(transport).execute(
            BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )

        assertEquals("UNSUPPORTED", result.getString("code"))
        assertFalse(result.getBoolean("executed"))
        assertEquals("isolated_world_unsupported", result.getJSONObject("data").getString("reason"))
        assertEquals(listOf("Page.getFrameTree", "Page.createIsolatedWorld"),
            transport.requests.map { it.method })
        assertTrue(transport.requests.none { it.method == "DOM.resolveNode" })
        assertTrue(transport.requests.none { it.method == "Runtime.callFunctionOn" })
    }

    @Test
    fun missingOrNonPositiveExecutionContextIdIsUnsupportedBeforeNodeResolution() {
        for (value in listOf(null, 0, -1)) {
            val transport = RecordingTransport { method, params, _ ->
                if (method == "Page.createIsolatedWorld") {
                    val response = JSONObject()
                    value?.let { response.put("executionContextId", it) }
                    response
                } else if (method == "Page.getFrameTree") {
                    defaultResponse(method, params, "OK", "OK")
                } else error("unexpected command after invalid context: $method")
            }
            val result = bridge(transport).execute(
                BrowserAxActionCandidate(ROOT_FRAME, 71),
                identity,
                BrowserAxAction.Click,
                DEADLINE,
                { true },
            )

            assertEquals("UNSUPPORTED", result.getString("code"))
            assertFalse(result.getBoolean("executed"))
            assertEquals("isolated_world_context_invalid",
                result.getJSONObject("data").getString("reason"))
            assertTrue(transport.requests.none { it.method == "DOM.resolveNode" })
        }
    }

    @Test
    fun commandBudgetIsRejectedBeforeOwnerWorkWhenIsolatedWorldsExhaustLimit() {
        var tree = childNode("frame-4", "https://child.example/4")
        for (level in 3 downTo 1) {
            tree = childNode("frame-$level", "https://child.example/$level", tree)
        }
        val transport = RecordingTransport(nestedFrameTree(rootNode(ROOT_FRAME, ROOT_URL), tree))
        val result = bridge(transport).execute(
            BrowserAxActionCandidate("frame-4", 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )

        assertEquals("COMMAND_LIMIT", result.getString("code"))
        assertFalse(result.getBoolean("executed"))
        assertEquals("command_budget_insufficient",
            result.getJSONObject("data").getString("reason"))
        assertEquals(listOf("Page.getFrameTree"), transport.requests.map { it.method })
    }

    @Test
    fun targetNotResolvableInCurrentTargetIsUnsupportedForOopifOrStaleNode() {
        val transport = RecordingTransport(
            frameTree = nestedFrameTree(
                rootNode(ROOT_FRAME, ROOT_URL),
                childNode(CHILD_FRAME, "https://child.example/frame"),
            ),
        ) { method, params, _ ->
            when {
                method == "DOM.getFrameOwner" -> JSONObject().put("backendNodeId", 900)
                method == "DOM.describeNode" -> JSONObject().put(
                    "node",
                    JSONObject()
                        .put("backendNodeId", 900)
                        .put("frameId", CHILD_FRAME),
                )
                method == "DOM.resolveNode" && params.getInt("backendNodeId") == 900 ->
                    resolveResult("obj-owner")
                method == "DOM.resolveNode" ->
                    throw CdpCommandRejectedException(-32602, "Invalid backend node")
                else -> {
                    val response = defaultResponse(method, params, "OK", "OK")
                    if (method == "Runtime.callFunctionOn" && params.optString("objectId") == "obj-target") {
                        response.getJSONObject("result").getJSONObject("value")
                            .put("href", "https://child.example/frame")
                    }
                    response
                }
            }
        }

        val result = bridge(transport).execute(
            BrowserAxActionCandidate(CHILD_FRAME, 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )

        assertEquals("UNSUPPORTED", result.getString("code"))
        assertEquals(false, result.getBoolean("executed"))
        assertEquals("frame_oopif_or_node_unavailable", result.getJSONObject("data").getString("reason"))
        assertTrue(transport.requests.none { request ->
            request.method == "Runtime.callFunctionOn" &&
                request.params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
        })
        assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
    }

    @Test
    fun frameChainBeyondFourLevelsIsUnsupportedBeforeDispatch() {
        var tree = childNode("frame-5", "https://child.example/5")
        for (level in 4 downTo 1) {
            tree = childNode("frame-$level", "https://child.example/$level", tree)
        }
        val transport = RecordingTransport(nestedFrameTree(rootNode(ROOT_FRAME, ROOT_URL), tree))
        val result = bridge(transport).execute(
            BrowserAxActionCandidate("frame-5", 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )

        assertEquals("UNSUPPORTED", result.getString("code"))
        assertEquals(false, result.getBoolean("executed"))
        assertEquals(
            "frame_chain_exceeds_four_levels",
            result.getJSONObject("data").getString("reason"),
        )
        assertEquals(listOf("Page.getFrameTree"), transport.requests.map { it.method })
    }

    @Test
    fun frameTreeParsingRejectsExcessiveDepthBeforeAnyNodeResolution() {
        var child = childNode("deep-12", ROOT_URL)
        for (level in 11 downTo 1) child = childNode("deep-$level", ROOT_URL, child)
        val transport = RecordingTransport(nestedFrameTree(rootNode(ROOT_FRAME, ROOT_URL), child))
        val result = bridge(transport).execute(BrowserAxActionCandidate(ROOT_FRAME, 71), identity,
            BrowserAxAction.Click, DEADLINE, { true })
        assertEquals("UNSUPPORTED", result.getString("code"))
        assertFalse(result.getBoolean("executed"))
        assertEquals("frame_tree_budget_exceeded", result.getJSONObject("data").getString("reason"))
        assertEquals(listOf("Page.getFrameTree"), transport.requests.map { it.method })
    }

    @Test
    fun documentGuardRejectsBeforeDispatchWithoutPretendingAnActionOccurred() {
        val transport = RecordingTransport { method, params, _ ->
            val executed = method == "Runtime.callFunctionOn" &&
                params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
            defaultResponse(method, params, "OK", if (executed) "DOCUMENT_CHANGED_BEFORE_DISPATCH" else "OK")
                .also { response ->
                    if (executed) response.getJSONObject("result").getJSONObject("value")
                        .put("doc", "new-document-time-origin")
                }
        }
        val result = bridge(transport).execute(BrowserAxActionCandidate(ROOT_FRAME, 71), identity,
            BrowserAxAction.Click, DEADLINE, { true })
        assertEquals("IDENTITY_CHANGED", result.getString("code"))
        assertFalse(result.getBoolean("executed"))
        assertEquals("document_changed_before_dispatch", result.getJSONObject("data").getString("reason"))
        assertFalse(result.getJSONObject("data").has("sideEffectsMayHaveOccurred"))
    }

    @Test
    fun remoteObjectIdIsPassedAsAnOpaqueJsonString() {
        val opaque = "{\"injectedScriptId\":1,\"id\":7}"
        val transport = RecordingTransport { method, params, _ ->
            if (method == "DOM.resolveNode") resolveResult(opaque)
            else defaultResponse(method, params, "OK", "OK")
        }
        val result = bridge(transport).execute(BrowserAxActionCandidate(ROOT_FRAME, 71), identity,
            BrowserAxAction.Click, DEADLINE, { true })
        assertEquals("OK", result.getString("code"))
        assertTrue(result.getBoolean("executed"))
        transport.requests.filter { it.method == "Runtime.callFunctionOn" }.forEach {
            assertEquals(opaque, it.params.getString("objectId"))
        }
    }

    @Test
    fun validationRejectsHiddenDisabledReadonlyOutsideViewportAndObscuredTargets() {
        val codes = listOf(
            "HIDDEN",
            "DISABLED",
            "READONLY",
            "OUTSIDE_VIEWPORT",
            "OBSCURED",
            "NOT_SCROLLABLE",
        )
        for (code in codes) {
            val transport = RecordingTransport { method, params, _ ->
                defaultResponse(method, params, validationCode = code, actionCode = "OK")
            }
            val result = bridge(transport).execute(
                BrowserAxActionCandidate(ROOT_FRAME, 71),
                identity,
                BrowserAxAction.Click,
                DEADLINE,
                { true },
            )

            assertEquals(code, result.getString("code"))
            assertEquals(false, result.getBoolean("executed"))
            assertEquals("validation", result.getJSONObject("data").getString("stage"))
            assertTrue(transport.requests.none { request ->
                request.method == "Runtime.callFunctionOn" &&
                    request.params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
            })
            assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
        }
    }

    @Test
    fun fixedTargetSourceContainsRealVisibilityViewportDisabledAndLabelChecks() {
        val transport = RecordingTransport()
        bridge(transport).execute(
            BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )
        val source = transport.requests.first {
            it.method == "Runtime.callFunctionOn"
        }.params.getString("functionDeclaration")

        listOf(
            "getComputedStyle",
            "display === 'none'",
            "visibility === 'hidden'",
            "contentVisibility",
            "pointerEvents",
            ":disabled",
            "readOnly",
            "visualViewport",
            "elementFromPoint",
            "associatedLabel",
            "getAttribute('for')",
            "labels",
        ).forEach { assertTrue("$source must contain $it", source.contains(it)) }
        assertFalse(source.contains("scrollIntoView"))
        assertFalse(source.contains("dispatchMouseEvent"))
        assertFalse(source.contains("force"))
        assertFalse(source.contains("eval("))
    }

    @Test
    fun authorizationRevocationIsDefiniteBeforeDispatchAndUnknownAfterDispatch() {
        val before = revocationResult("before")
        assertEquals("NOT_AUTHORIZED", before.first.getString("code"))
        assertEquals(false, before.first.getBoolean("executed"))
        assertTrue(before.second.isEmpty())

        val duringRead = revocationResult("read")
        assertEquals("NOT_AUTHORIZED", duringRead.first.getString("code"))
        assertEquals(false, duringRead.first.getBoolean("executed"))
        assertEquals(listOf("Page.getFrameTree"), duringRead.second.map { it.method })

        val afterPreflight = revocationResult("preflight")
        assertEquals("NOT_AUTHORIZED", afterPreflight.first.getString("code"))
        assertEquals(false, afterPreflight.first.getBoolean("executed"))
        assertTrue(afterPreflight.second.none { request ->
            request.method == "Runtime.callFunctionOn" &&
                request.params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
        })

        val duringAction = revocationResult("action")
        assertEquals("UNKNOWN", duringAction.first.getString("code"))
        assertTrue(duringAction.first.isNull("executed"))
        assertTrue(duringAction.first.getJSONObject("data").getBoolean("sideEffectsMayHaveOccurred"))
        assertTrue(duringAction.second.any { request ->
            request.method == "Runtime.callFunctionOn" &&
                request.params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
        })
    }

    @Test
    fun deadlineBeforeDispatchIsFalseAndAfterDispatchIsUnknown() {
        val before = deadlineResult("before")
        assertEquals("DEADLINE_EXPIRED", before.first.getString("code"))
        assertEquals(false, before.first.getBoolean("executed"))
        assertTrue(before.second.isEmpty())

        val afterPreflight = deadlineResult("preflight")
        assertEquals("DEADLINE_EXPIRED", afterPreflight.first.getString("code"))
        assertEquals(false, afterPreflight.first.getBoolean("executed"))
        assertTrue(afterPreflight.second.none { request ->
            request.method == "Runtime.callFunctionOn" &&
                request.params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
        })

        val afterAction = deadlineResult("action")
        assertEquals("UNKNOWN", afterAction.first.getString("code"))
        assertTrue(afterAction.first.isNull("executed"))
        assertTrue(afterAction.first.getJSONObject("data").getBoolean("sideEffectsMayHaveOccurred"))
    }

    @Test
    fun documentChangeAfterDispatchIsUnknownEvenWhenFunctionReturnedOk() {
        var document = "document-7"
        val transport = RecordingTransport { method, params, _ ->
            if (method == "Runtime.callFunctionOn" &&
                params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
            ) {
                document = "document-8"
            }
            defaultResponse(method, params, "OK", "OK")
        }
        val result = BrowserAxActionBridge(
            transport = transport,
            documentIdentity = { document },
            nowElapsedMillis = { NOW },
        ).execute(
            BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )

        assertEquals("UNKNOWN", result.getString("code"))
        assertTrue(result.isNull("executed"))
        assertTrue(result.getJSONObject("data").getBoolean("sideEffectsMayHaveOccurred"))
        assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
    }

    @Test
    fun syntheticWriteAfterSendFailureIsUnknownWithNullExecuted() {
        val transport = RecordingTransport { method, params, _ ->
            if (method == "Runtime.callFunctionOn" &&
                params.getJSONArray("arguments").getJSONObject(0).getString("value") == "execute"
            ) {
                throw CdpOutcomeUnknownException(IllegalStateException("response timeout"))
            }
            defaultResponse(method, params, "OK", "OK")
        }
        val result = bridge(transport).execute(
            BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity,
            BrowserAxAction.Type("value"),
            DEADLINE,
            { true },
        )

        assertEquals("UNKNOWN", result.getString("code"))
        assertTrue(result.isNull("executed"))
        assertTrue(result.getJSONObject("data").getBoolean("sideEffectsMayHaveOccurred"))
        assertTrue(result.getJSONObject("data").getBoolean("nonRetryable"))
        assertTrue(result.getJSONObject("data").getBoolean("objectGroupReleased"))
    }

    @Test
    fun writeAfterSendDuringReadOnlyDiscoveryDoesNotBecomeActionUnknown() {
        val transport = RecordingTransport { method, params, _ ->
            if (method == "Page.getFrameTree") {
                throw CdpOutcomeUnknownException(IllegalStateException("read timeout"))
            }
            defaultResponse(method, params, "OK", "OK")
        }
        val result = bridge(transport).execute(
            BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )

        assertEquals("AX_ACTION_FAILED", result.getString("code"))
        assertEquals(false, result.getBoolean("executed"))
        assertFalse(result.getJSONObject("data").has("sideEffectsMayHaveOccurred"))
        assertEquals(listOf("Page.getFrameTree"), transport.requests.map { it.method })
    }

    private fun revocationResult(stage: String): Pair<JSONObject, List<RecordedRequest>> {
        var authorized = stage != "before"
        val transport = RecordingTransport { method, params, stillAuthorized ->
            val args = params.optJSONArray("arguments")
            when {
                stage == "read" && method == "Page.getFrameTree" -> {
                    authorized = false
                    check(stillAuthorized()) { "NOT_AUTHORIZED" }
                    JSONObject()
                }
                stage == "preflight" && method == "Runtime.callFunctionOn" &&
                    args?.getJSONObject(0)?.getString("value") == "validate" -> {
                    val response = defaultResponse(method, params, "OK", "OK")
                    authorized = false
                    response
                }
                stage == "action" && method == "Runtime.callFunctionOn" &&
                    args?.getJSONObject(0)?.getString("value") == "execute" -> {
                    authorized = false
                    defaultResponse(method, params, "OK", "OK")
                }
                else -> defaultResponse(method, params, "OK", "OK")
            }
        }
        val result = bridge(transport)
            .execute(
                BrowserAxActionCandidate(ROOT_FRAME, 71),
                identity,
                BrowserAxAction.Click,
                DEADLINE,
                { authorized },
            )
        return result to transport.requests
    }

    private fun deadlineResult(stage: String): Pair<JSONObject, List<RecordedRequest>> {
        var now = if (stage == "before") DEADLINE else NOW
        val transport = RecordingTransport { method, params, _ ->
            val args = params.optJSONArray("arguments")
            when {
                stage == "preflight" && method == "Runtime.callFunctionOn" &&
                    args?.getJSONObject(0)?.getString("value") == "validate" -> {
                    val response = defaultResponse(method, params, "OK", "OK")
                    now = DEADLINE
                    response
                }
                stage == "action" && method == "Runtime.callFunctionOn" &&
                    args?.getJSONObject(0)?.getString("value") == "execute" -> {
                    now = DEADLINE
                    defaultResponse(method, params, "OK", "OK")
                }
                else -> defaultResponse(method, params, "OK", "OK")
            }
        }
        val result = BrowserAxActionBridge(
            transport = transport,
            documentIdentity = { DOCUMENT },
            nowElapsedMillis = { now },
        ).execute(
            BrowserAxActionCandidate(ROOT_FRAME, 71),
            identity,
            BrowserAxAction.Click,
            DEADLINE,
            { true },
        )
        return result to transport.requests
    }

    private fun bridge(transport: RecordingTransport) = BrowserAxActionBridge(
        transport = transport,
        documentIdentity = { DOCUMENT },
        nowElapsedMillis = { NOW },
    )

    private fun defaultResponse(
        method: String,
        params: JSONObject,
        validationCode: String,
        actionCode: String,
    ): JSONObject = when (method) {
        "Page.getFrameTree" -> JSONObject().put(
            "frameTree",
            JSONObject().put("frame", rootNode(ROOT_FRAME, ROOT_URL)),
        )
        "Page.createIsolatedWorld" -> JSONObject().put(
            "executionContextId",
            if (params.optString("frameId") == ROOT_FRAME) ROOT_CONTEXT_ID else CHILD_CONTEXT_ID,
        )
        "DOM.getFrameOwner" -> JSONObject().put("backendNodeId", 900)
        "DOM.describeNode" -> JSONObject().put(
            "node",
            JSONObject()
                .put("backendNodeId", 900)
                .put("frameId", CHILD_FRAME),
        )
        "DOM.resolveNode" -> resolveResult(
            if (params.optInt("backendNodeId") == 900) "obj-owner" else "obj-target",
        )
        "Runtime.callFunctionOn" -> {
            val args = params.getJSONArray("arguments")
            val mode = args.getJSONObject(0).getString("value")
            val actionCodeArg = args.getJSONObject(1).getString("value")
            val code = when {
                actionCodeArg == "frameOwner" -> "OK"
                mode == "validate" -> validationCode
                else -> actionCode
            }
            val value = JSONObject()
                .put("code", code)
                .put("href", ROOT_URL)
                .put("doc", DOCUMENT_TOKEN)
            if (mode == "execute" && actionCodeArg == "scroll") value.put("scrollTop", 120.0)
            JSONObject().put("result", JSONObject().put("value", value))
        }
        "Runtime.releaseObjectGroup" -> JSONObject()
        else -> error("unexpected method: $method")
    }

    private fun resolveResult(objectId: String): JSONObject = JSONObject()
        .put("object", JSONObject()
            .put("type", "object")
            .put("objectId", objectId))

    private fun nestedFrameTree(root: JSONObject, child: JSONObject): JSONObject = JSONObject()
        .put("frameTree", JSONObject()
            .put("frame", root)
            .put("childFrames", JSONArray().put(child)))

    private fun rootNode(id: String, url: String): JSONObject = JSONObject()
        .put("id", id)
        .put("url", url)

    private fun childNode(id: String, url: String, child: JSONObject? = null): JSONObject {
        val tree = JSONObject().put("frame", JSONObject().put("id", id).put("url", url))
        if (child != null) tree.put("childFrames", JSONArray().put(child))
        return tree
    }

    private data class RecordedRequest(
        val method: String,
        val params: JSONObject,
        val deadlineElapsedMillis: Long,
    )

    private inner class RecordingTransport(
        private val frameTree: JSONObject? = null,
        private val handler: (String, JSONObject, () -> Boolean) -> JSONObject =
            { method, params, _ -> defaultResponse(method, params, "OK", "OK") },
    ) : BrowserAxTreeTransport {
        override val targetId = TARGET
        override var connectionEpoch = EPOCH
        val requests = mutableListOf<RecordedRequest>()

        override fun call(
            method: String,
            params: JSONObject,
            deadlineElapsedMillis: Long,
            stillAuthorized: () -> Boolean,
        ): JSONObject {
            val snapshot = JSONObject(params.toString())
            requests += RecordedRequest(method, JSONObject(snapshot.toString()), deadlineElapsedMillis)
            if (method == "Page.getFrameTree" && frameTree != null) {
                val tree = when {
                    frameTree.has("frameTree") -> frameTree.getJSONObject("frameTree")
                    frameTree.has("frame") -> frameTree
                    else -> JSONObject().put("frame", frameTree)
                }
                return JSONObject().put("frameTree", JSONObject(tree.toString()))
            }
            return handler(method, snapshot, stillAuthorized)
        }
    }

    private companion object {
        const val TARGET = "target-main"
        const val EPOCH = 7L
        const val ROOT_FRAME = "main-frame"
        const val CHILD_FRAME = "child-frame"
        const val ROOT_CONTEXT_ID = 101
        const val CHILD_CONTEXT_ID = 202
        const val ROOT_URL = "https://example.test/start"
        const val DOCUMENT = "document-7"
        const val DOCUMENT_TOKEN = "time-origin-7"
        const val NOW = 100L
        const val DEADLINE = 200L
        val identity = BrowserAxActionReferences.Identity(
            targetId = TARGET,
            connectionEpoch = EPOCH,
            documentIdentity = DOCUMENT,
            authorizationEpoch = 4,
        )
    }
}
