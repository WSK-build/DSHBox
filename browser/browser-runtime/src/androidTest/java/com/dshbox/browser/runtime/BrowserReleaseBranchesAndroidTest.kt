package com.dshbox.browser.runtime

import android.graphics.Bitmap
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dshbox.browser.api.BrowserOwner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Device probes run in the independent test application, without changing the user's settings. */
@RunWith(AndroidJUnit4::class)
class BrowserReleaseBranchesAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun rendererPopupRequiresOneUseAuthorizationAndDoesNotReplaceParent() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".test"))
        lateinit var gateway: BrowserSessionGateway
        lateinit var parent: WebView
        try {
            instrumentation.runOnMainSync {
                BrowserRuntimeSettings.preferences(context).edit()
                    .putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, true).commit()
                gateway = requireNotNull(BrowserSessionFactory.createOrReuse(context))
                gateway.open(BrowserOwner.AI)
                parent = requireNotNull(gateway.host.surfaceOrNull())
                // Only this probe enables JS-auto, so Chromium reaches the actual authorization
                // callback without a physical tap. Production keeps JS-auto disabled.
                parent.settings.javaScriptCanOpenWindowsAutomatically = true
                gateway.loadUrl("https://example.com/")
            }
            awaitMain { parent.progress == 100 && !parent.title.isNullOrBlank() }
            evaluate(parent, "window.open('https://example.org/','blocked'); 'done'")
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(1, gateway.pageManagerOrNull().size)
                assertSame(parent, gateway.host.surfaceOrNull())
                val executor = gateway.ensureExecutor()
                val identity = executor.javaClass.getDeclaredMethod("currentEventIdentity", String::class.java)
                    .apply { isAccessible = true }.invoke(executor, null) as BrowserEventCoordinator.Identity
                val events = executor.javaClass.getDeclaredField("eventCoordinator")
                    .apply { isAccessible = true }.get(executor) as BrowserEventCoordinator
                val sub = (events.arm("popup_probe", identity, 1, setOf("new_window"), 15_000)
                    as BrowserEventCoordinator.ArmResult.Created).subscription
                assertNotNull(events.bindAction(sub.id, sub.nonce, "popup_probe", identity, "probe_click", setOf("new_window")))
                parent.evaluateJavascript("window.open('https://example.org/','allowed');", null)
            }
            awaitMain { gateway.pageManagerOrNull().size == 2 }
            instrumentation.runOnMainSync {
                assertSame(parent, gateway.host.surfaceOrNull())
                assertEquals("https://example.com/", parent.url)
                parent.evaluateJavascript("window.open('https://example.net/','reused');", null)
            }
            evaluate(parent, "'barrier'")
            instrumentation.runOnMainSync {
                assertEquals("authorization must be consumed once", 2, gateway.pageManagerOrNull().size)
                assertFalse(gateway.lifecycleController.snapshot().visible)
            }
        } finally {
            instrumentation.runOnMainSync { BrowserSessionGatewayRegistry.current()?.close() }
        }
    }

    @Test(timeout = 90_000) fun forcedPrimaryFailureRequiresDebugThenCallsActualCdpSerially() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".test"))
        lateinit var gateway: BrowserSessionGateway
        lateinit var view: WebView
        lateinit var realFallback: BrowserScreenshotCaptureSource<Bitmap>
        lateinit var realCapture: BrowserScreenshotCaptureSource<Bitmap>
        val trace = mutableListOf<String>()
        try {
            instrumentation.runOnMainSync {
                BrowserRuntimeSettings.preferences(context).edit()
                    .putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, true)
                    .putBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, true).commit()
                gateway = requireNotNull(BrowserSessionFactory.createOrReuse(context))
                gateway.open(BrowserOwner.AI)
                gateway.loadUrl("https://example.com/")
                view = requireNotNull(gateway.host.surfaceOrNull())
                val coordinator = gateway.javaClass.getDeclaredField("captureCoordinator")
                    .apply { isAccessible = true }.get(gateway)!!
                @Suppress("UNCHECKED_CAST")
                val capture = coordinator as BrowserScreenshotCaptureSource<Bitmap>
                realCapture = capture
                @Suppress("UNCHECKED_CAST")
                val fallback = coordinator.javaClass.getDeclaredField("fallback")
                    .apply { isAccessible = true }.get(coordinator) as BrowserScreenshotCaptureSource<Bitmap>
                realFallback = fallback
            }
            awaitMain { view.progress == 100 && !view.title.isNullOrBlank() }
            stage("page_ready")
            // Establish a real compositor frame before injecting a later primary failure.
            // This models a live session rather than an unpainted instrumentation-only WebView.
            val painted = CountDownLatch(1)
            val initialFrame = AtomicReference<BrowserScreenshotCaptureResult<Bitmap>>()
            instrumentation.runOnMainSync {
                realCapture.capture(SystemClock.elapsedRealtime() + 12_000) {
                    initialFrame.set(it)
                    painted.countDown()
                }
            }
            assertTrue("initial frame timeout", painted.await(15, TimeUnit.SECONDS))
            assertEquals("initial frame: ${initialFrame.get().code}", "OK", initialFrame.get().code)
            stage("real_primary_frame_ok")
            initialFrame.get().value?.recycle()
            android.util.Log.i("BrowserReleaseProbe", "page_loaded_before_debug")
            instrumentation.runOnMainSync { WebView.setWebContentsDebuggingEnabled(true) }
            android.util.Log.i("BrowserReleaseProbe", "debug_enabled")
            val primary = BrowserScreenshotCaptureSource<Bitmap> { _, callback ->
                trace += "primary_failed"
                callback(BrowserScreenshotCaptureResult(null, BrowserScreenshotCaptureContract.VIRTUAL_DISPLAY_UNAVAILABLE))
                BrowserScreenshotCaptureHandle { false }
            }
            var debug = false
            val coordinator = BrowserScreenshotCaptureCoordinator(
                primary, BrowserScreenshotCaptureSource<Bitmap> { deadline, callback ->
                    trace += "cdp_started"
                    stage("cdp_started")
                    realFallback.capture(deadline) {
                        stage("cdp_callback:${it.code}")
                        callback(it)
                    }
                }, { debug },
            )
            try {
                var disabled: BrowserScreenshotCaptureResult<Bitmap>? = null
                instrumentation.runOnMainSync {
                    coordinator.capture(SystemClock.elapsedRealtime() + 20_000) { disabled = it }
                }
                assertEquals(BrowserScreenshotCaptureContract.DEBUG_REQUIRED, disabled?.code)
                val data = BrowserScreenshotCaptureContract.failureData(disabled!!.code, disabled!!.outcome)
                assertTrue(data.getBoolean("userActionRequired"))
                assertFalse(data.getBoolean("debugEnabled"))
                assertEquals(listOf("primary_failed"), trace)
                stage("debug_disabled_fields_ok")
                android.util.Log.i("BrowserReleaseProbe", "disabled_debug_failure_fields_passed")
                trace.clear()
                debug = true
                val done = CountDownLatch(1)
                val result = AtomicReference<BrowserScreenshotCaptureResult<Bitmap>>()
                instrumentation.runOnMainSync {
                    coordinator.capture(SystemClock.elapsedRealtime() + 25_000) { result.set(it); done.countDown() }
                }
                val returned = done.await(30, TimeUnit.SECONDS)
                if (!returned) {
                    val stacks = Thread.getAllStackTraces().entries
                        .filter { it.key.name.startsWith("browser-") || it.key.name == "main" }
                        .joinToString("\n") { (thread, frames) ->
                            thread.name + " " + thread.state + "\n" + frames.take(18).joinToString("\n")
                        }
                    instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", stacks) })
                }
                assertTrue("CDP callback timeout", returned)
                android.util.Log.i("BrowserReleaseProbe", "cdp_result=${result.get().code}")
                assertEquals(listOf("primary_failed", "cdp_started"), trace)
                val actual = result.get()
                assertEquals("actual CDP result: ${actual.outcome}", "OK", actual.code)
                assertEquals("cdp", actual.outcome.backend)
                assertNotNull(actual.value)
                assertTrue(actual.value!!.width > 0 && actual.value!!.height > 0)
                val pixels = mutableSetOf<Int>()
                val image = actual.value!!
                for (y in 0 until image.height step 8) for (x in 0 until image.width step 8) {
                    pixels.add(image.getPixel(x, y))
                }
                assertTrue("CDP returned a blank image", pixels.size >= 4)
                java.io.File(context.getExternalFilesDir(null), "browser-cdp-fallback.png").outputStream().use {
                    assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                actual.value!!.recycle()
                instrumentation.runOnMainSync { assertFalse(gateway.lifecycleController.snapshot().visible) }
                val commandDone = CountDownLatch(1)
                val commandResult = AtomicReference<String>()
                instrumentation.runOnMainSync {
                    // The production command service initializes the journal before dispatch.
                    BrowserTaskCoordinatorRegistry.getOrCreate(context)
                    // The app's tool installer owns these directories; the isolated package has no installer.
                    for (category in listOf("shot", "selection", "network")) {
                        val directory = java.io.File(context.filesDir, "browser-tools/out/$category")
                        check(directory.isDirectory || directory.mkdirs())
                    }
                    val executor = gateway.ensureExecutor()
                    executor.javaClass.getDeclaredField("captureDetailed").apply { isAccessible = true }
                        .set(executor, coordinator)
                    val state = gateway.lifecycleController.snapshot()
                    val request = org.json.JSONObject().put("version", 1)
                        .put("requestId", "cdp_exec_" + java.util.UUID.randomUUID().toString())
                        .put("method", "screenshot").put("sessionGeneration", state.sessionGeneration)
                        .put("controlGeneration", state.controlGeneration).put("owner", "ai")
                        .put("deadlineAtMillis", System.currentTimeMillis() + 25_000).put("args", org.json.JSONObject())
                    gateway.execute(request.toString()) { commandResult.set(it); commandDone.countDown() }
                }
                assertTrue("executor screenshot callback timeout", commandDone.await(30, TimeUnit.SECONDS))
                val delivered = org.json.JSONObject(commandResult.get())
                stage("executor_screenshot:${delivered}")
                assertEquals(commandResult.get(), "OK", delivered.getString("code"))
                val artifact = delivered.getJSONObject("data")
                assertEquals("cdp", artifact.getString("backend"))
                assertFalse("hidden or raster-rounded screenshots cannot authorize pixel input", artifact.has("screenshotId"))
                instrumentation.runOnMainSync {
                    assertNull(view.parent)
                    assertFalse(gateway.host.isMounted())
                }
            } finally { coordinator.close() }
        } finally {
            instrumentation.runOnMainSync {
                BrowserSessionGatewayRegistry.current()?.close()
                WebView.setWebContentsDebuggingEnabled(false)
            }
        }
    }

    @Test fun sharedSelectionCanBeClaimedAdmittedAndReceiptedWhileHumanOwnsPage() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".test"))
        lateinit var gateway: BrowserSessionGateway
        var generation = 0L
        var selectionId = ""
        try {
            instrumentation.runOnMainSync {
                BrowserRuntimeSettings.preferences(context).edit()
                    .putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, true).commit()
                for (category in listOf("shot", "selection", "network")) {
                    val directory = java.io.File(context.filesDir, "browser-tools/out/$category")
                    check(directory.isDirectory || directory.mkdirs())
                }
                BrowserTaskCoordinatorRegistry.getOrCreate(context)
                gateway = requireNotNull(BrowserSessionFactory.createOrReuse(context))
                generation = gateway.open(BrowserOwner.HUMAN).sessionGeneration
                val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try {
                    val saved = gateway.selectionStoreOrNull().save(generation, SystemClock.elapsedRealtime(),
                        "area", "feedback permission probe", "https://example.com/", "Probe",
                        org.json.JSONObject(), org.json.JSONObject(), android.graphics.RectF(0f, 0f, 32f, 32f), bitmap)
                    selectionId = saved.getJSONObject("data").getString("selectionId")
                } finally { bitmap.recycle() }
            }
            val deliveryId = java.util.UUID.randomUUID().toString().replace("-", "")
            for ((action, expected) in listOf("claim" to "CLAIMED", "admit" to "ADMITTING", "receipt" to "OK")) {
                val done = CountDownLatch(1)
                val response = AtomicReference<String>()
                instrumentation.runOnMainSync {
                    val args = org.json.JSONObject().put("action", action).put("selectionId", selectionId)
                        .put("destinationSessionKey", "independent-feedback-probe").put("requestId", deliveryId)
                    if (action == "receipt") args.put("deliveryCode", "DELIVERED").put("imageIncluded", true)
                    else args.put("payloadDigest", "a".repeat(64))
                    val request = org.json.JSONObject().put("version", 1)
                        .put("requestId", java.util.UUID.randomUUID().toString()).put("method", "selection")
                        .put("sessionGeneration", generation).put("deadlineAtMillis", System.currentTimeMillis() + 10_000)
                        .put("args", args)
                    gateway.execute(request.toString()) { response.set(it); done.countDown() }
                }
                assertTrue("$action callback timeout", done.await(12, TimeUnit.SECONDS))
                val result = org.json.JSONObject(response.get())
                stage("human_feedback_$action:${result.optString("code")}")
                assertEquals(response.get(), expected, result.getString("code"))
                assertTrue(result.getBoolean("executed"))
                instrumentation.runOnMainSync { assertEquals(BrowserOwner.HUMAN, gateway.lifecycleController.snapshot().owner) }
            }
        } finally {
            instrumentation.runOnMainSync { BrowserSessionGatewayRegistry.current()?.close() }
        }
    }

    private fun evaluate(view: WebView, js: String) {
        val done = CountDownLatch(1)
        instrumentation.runOnMainSync { view.evaluateJavascript(js) { done.countDown() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun stage(value: String) {
        instrumentation.sendStatus(2, android.os.Bundle().apply { putString("stream", "probe:$value\n") })
    }

    private fun awaitMain(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("page or popup did not settle within device probe budget")
    }
}
