package com.dshbox.browser.runtime

import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** JSON-line host adapter that invokes the production coordinator for the desktop harness. */
internal object BrowserTaskCoordinatorHostAdapter {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = BrowserTaskCoordinatorHostStore()
        val runtime = BrowserTaskCoordinatorHostRuntime(store)
        val coordinator = BrowserTaskCoordinator(runtime)
        coordinator.initialize()
        require(runtime.awaitInitialized(5_000L)) { "coordinator initialization timed out" }

        val input = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val result = try {
                handle(JSONObject(line), coordinator, runtime, store)
            } catch (error: Throwable) {
                JSONObject().put("ok", false).put("error", error.message ?: error.javaClass.simpleName)
            }
            result.put("__id", commandId(line))
            println(result.toString())
            System.out.flush()
        }
        coordinator.close()
        runtime.awaitClosed(5_000L)
    }

    private fun handle(
        command: JSONObject,
        coordinator: BrowserTaskCoordinator,
        runtime: BrowserTaskCoordinatorHostRuntime,
        store: BrowserTaskCoordinatorHostStore,
    ): JSONObject {
        return when (command.optString("op")) {
            "execute" -> {
                val request = BrowserCommandRequest.parse(
                    command.getJSONObject("request").toString(),
                    runtime.elapsedRealtime(),
                ).getOrThrow()
                val before = liveContext(command.optJSONObject("context"))
                val after = liveContext(command.optJSONObject("afterContext") ?: command.optJSONObject("context"))
                var calls = 0
                val response = AtomicReference<String>()
                val completed = CountDownLatch(1)
                coordinator.executeTask(request, before, { value ->
                    response.set(value)
                    completed.countDown()
                }, {
                    calls += 1
                    if (calls == 1) before else after
                })
                require(completed.await(5, TimeUnit.SECONDS)) { "executeTask timed out" }
                JSONObject().put("ok", true).put("response", JSONObject(response.get()))
            }
            "admit" -> {
                val request = BrowserCommandRequest.parse(
                    command.getJSONObject("request").toString(),
                    runtime.elapsedRealtime(),
                ).getOrThrow()
                val context = requireNotNull(liveContext(command.optJSONObject("context")))
                val admission = AtomicReference<BrowserEffectAdmission>()
                val digest = AtomicReference<String>()
                val completed = CountDownLatch(1)
                coordinator.admitOperation(request, context) { value, valueDigest ->
                    admission.set(value)
                    digest.set(valueDigest)
                    completed.countDown()
                }
                require(completed.await(5, TimeUnit.SECONDS)) { "admitOperation timed out" }
                JSONObject()
                    .put("ok", true)
                    .put("admission", admissionName(admission.get()))
                    .put("digest", digest.get())
                    .put("admissionCode", admissionCode(admission.get()))
            }
            "finish" -> {
                val response = AtomicReference<String>()
                val completed = CountDownLatch(1)
                coordinator.finishOperation(
                    command.getString("requestId"),
                    command.getString("digest"),
                    command.getString("response"),
                    { value -> response.set(value); completed.countDown() },
                )
                require(completed.await(5, TimeUnit.SECONDS)) { "finishOperation timed out" }
                JSONObject().put("ok", true).put("response", JSONObject(response.get()))
            }
            "classify" -> {
                val blockInfo = BrowserBlockedPageEvidence.inspect(command.getJSONObject("observation"))
                JSONObject().put("ok", true).put("blockInfo", blockInfo)
            }
            "snapshot" -> {
                JSONObject()
                    .put("ok", true)
                    .put("tasks", JSONArrayFromTasks(store.snapshotTasks()))
                    .put("effects", JSONArrayFromEffects(store.snapshotEffects()))
            }
            "close" -> {
                coordinator.close()
                runtime.awaitClosed(5_000L)
                JSONObject().put("ok", true).put("shutdown", runtime.shutdownCalled)
            }
            else -> JSONObject().put("ok", false).put("error", "unsupported adapter operation")
        }
    }

    private fun liveContext(value: JSONObject?): BrowserTaskLiveContext? {
        value ?: return null
        val identityValue = value.optJSONObject("identity") ?: return null
        val identity = BrowserTaskPageIdentity(
            sessionGeneration = identityValue.optLong("sessionGeneration"),
            controlGeneration = identityValue.optLong("controlGeneration"),
            pageId = identityValue.optString("pageId"),
            documentGeneration = identityValue.optLong("documentGeneration"),
        )
        return BrowserTaskLiveContext(
            active = value.optBoolean("active", true),
            automationEnabled = value.optBoolean("automationEnabled", true),
            ownerIsAi = value.optBoolean("ownerIsAi", true),
            identity = identity,
            state = value.optString("state", "RUNNING"),
        )
    }

    private fun commandId(line: String): String = runCatching {
        JSONObject(line).optString("__id")
    }.getOrDefault("")

    private fun admissionName(value: BrowserEffectAdmission): String = when (value) {
        BrowserEffectAdmission.NewIntent -> "new_intent"
        is BrowserEffectAdmission.Duplicate -> "duplicate"
        BrowserEffectAdmission.RequestIdConflict -> "request_conflict"
        is BrowserEffectAdmission.Full -> "full"
        is BrowserEffectAdmission.TaskRejected -> "task_rejected"
    }

    private fun admissionCode(value: BrowserEffectAdmission): String = when (value) {
        BrowserEffectAdmission.NewIntent -> "OK"
        is BrowserEffectAdmission.Duplicate -> value.phase.wireValue
        BrowserEffectAdmission.RequestIdConflict -> "REQUEST_ID_CONFLICT"
        is BrowserEffectAdmission.Full -> "BUSY"
        is BrowserEffectAdmission.TaskRejected -> value.code
    }

    private fun JSONArrayFromTasks(tasks: List<com.dshbox.browser.api.BrowserTaskRecord>): org.json.JSONArray {
        val array = org.json.JSONArray()
        tasks.forEach { task ->
            array.put(JSONObject()
                .put("taskId", task.taskId)
                .put("revision", task.revision)
                .put("status", task.status.wireValue)
                .put("operationsUsed", task.operationsUsed)
                .put("waitReason", task.waitReason?.wireValue ?: JSONObject.NULL)
                .put("pageIdentity", task.pageIdentity?.let {
                    JSONObject()
                        .put("sessionGeneration", it.sessionGeneration)
                        .put("controlGeneration", it.controlGeneration)
                        .put("pageId", it.pageId)
                        .put("documentGeneration", it.documentGeneration)
                } ?: JSONObject.NULL))
        }
        return array
    }

    private fun JSONArrayFromEffects(
        effects: List<com.dshbox.browser.api.BrowserEffectJournalEntry>,
    ): org.json.JSONArray {
        val array = org.json.JSONArray()
        effects.forEach { effect ->
            array.put(JSONObject()
                .put("requestId", effect.requestId)
                .put("taskId", effect.taskId ?: JSONObject.NULL)
                .put("method", effect.method)
                .put("kind", effect.kind.wireValue)
                .put("phase", effect.phase.wireValue)
                .put("terminalCode", effect.terminalCode ?: JSONObject.NULL)
                .put("terminalExecuted", effect.terminalExecuted ?: JSONObject.NULL))
        }
        return array
    }
}
