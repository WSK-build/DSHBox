package com.dshbox.browser.runtime

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import android.os.ParcelFileDescriptor

/** Exercises ContentProvider -> native WebView file chooser -> delayed JS File read end to end. */
@RunWith(AndroidJUnit4::class)
class BrowserUploadProviderWebViewAndroidTest {
    @Test
    fun webViewReadsDelayedApproved19ByteFileThroughNativeChooser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val fixtureId = UUID.randomUUID().toString().replace("-", "")
        val grantId = UUID.randomUUID().toString().replace("-", "")
        val uriToken = UUID.randomUUID().toString().replace("-", "")
        val fixtureDirectory = File(context.filesDir, "user-data/provider-webview-probe-$fixtureId")
        val fixture = File(fixtureDirectory, "approved.txt")
        val expected = "browser upload test".toByteArray(Charsets.UTF_8)
        var staged: List<BrowserUploadStagedFile> = emptyList()
        var activity: BrowserUploadProviderWebViewProbeActivity? = null
        var focusProbe: BrowserApprovalFocusProbeActivity? = null

        try {
            BrowserUploadProviderWebViewProbeActivity.resetProbeState()
            assertTrue("isolated probe fixture directory", fixtureDirectory.mkdirs())
            fixture.writeBytes(expected)
            assertEquals(19L, fixture.length())
            val source = BrowserUploadPolicy.inspect(
                File(context.filesDir, "user-data"),
                "provider-webview-probe-$fixtureId/approved.txt",
            ) ?: error("probe fixture inspection failed")
            staged = BrowserUploadStager(context.filesDir).stage(grantId, listOf(source))
            val uris = BrowserUploadProvider.register(
                context,
                uriToken,
                staged.associateBy { it.fileId },
                System.currentTimeMillis() + 60_000L,
            )

            focusProbe = launchForegroundFocusProbe(instrumentation)
            val launch = Intent(context, BrowserUploadProviderWebViewProbeActivity::class.java)
                .putExtra(BrowserUploadProviderWebViewProbeActivity.EXTRA_URI, uris.single().toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            activity = instrumentation.startActivitySync(launch) as BrowserUploadProviderWebViewProbeActivity
            assertTrue("probe HTML loaded", BrowserUploadProviderWebViewProbeActivity.awaitPageLoaded(10, TimeUnit.SECONDS))
            tapFileInput(instrumentation, activity!!.probeWebView())

            assertTrue("native WebChromeClient chooser was reached",
                BrowserUploadProviderWebViewProbeActivity.awaitChooser(10, TimeUnit.SECONDS))
            assertTrue("delayed WebView File.arrayBuffer read completed",
                BrowserUploadProviderWebViewProbeActivity.awaitResult(15, TimeUnit.SECONDS))
            val result = JSONObject(requireNotNull(BrowserUploadProviderWebViewProbeActivity.result))
            assertEquals("PASS", result.optString("code"))
            assertEquals(19, result.optInt("size"))
            assertEquals(sha256(expected), result.optString("sha256"))
            assertEquals(1, BrowserUploadProviderWebViewProbeActivity.chooserCalls.get())
        } finally {
            instrumentation.runOnMainSync {
                activity?.finish()
                focusProbe?.finish()
            }
            runCatching { BrowserUploadProvider.revoke(uriToken) }
            runCatching { if (staged.isNotEmpty()) BrowserUploadStager(context.filesDir).revoke(grantId) }
            runCatching { fixtureDirectory.deleteRecursively() }
        }
    }

    private fun tapFileInput(instrumentation: android.app.Instrumentation, webView: WebView) {
        val visible = AtomicReference<Boolean>(false)
        instrumentation.runOnMainSync {
            visible.set(webView.isAttachedToWindow && webView.width > 0 && webView.height > 0 && webView.isShown)
        }
        check(visible.get()) { "WebView is not visible for native input" }
        val point = AtomicReference<Pair<Float, Float>?>(null)
        val pointLatch = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                "(() => { const r=document.getElementById('upload').getBoundingClientRect(); return JSON.stringify({x:(r.left+r.width/2)*devicePixelRatio,y:(r.top+r.height/2)*devicePixelRatio}); })()",
            ) { encoded ->
                val value = encoded?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                val json = value?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (json != null) point.set(json.optDouble("x").toFloat() to json.optDouble("y").toFloat())
                pointLatch.countDown()
            }
        }
        check(pointLatch.await(5, TimeUnit.SECONDS)) { "Could not obtain file input bounds" }
        val (x, y) = requireNotNull(point.get())
        require(x >= 0f && y >= 0f && x < webView.width && y < webView.height)
        val location = IntArray(2)
        instrumentation.runOnMainSync { webView.getLocationOnScreen(location) }
        val screenX = location[0] + x
        val screenY = location[1] + y
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, eventTime: Long) = MotionEvent.obtain(
            downTime, eventTime, action, screenX, screenY, 0,
        ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        val down = event(MotionEvent.ACTION_DOWN, downTime)
        try { instrumentation.sendPointerSync(down) } finally { down.recycle() }
        val up = event(MotionEvent.ACTION_UP, SystemClock.uptimeMillis() + 40L)
        try { instrumentation.sendPointerSync(up) } finally { up.recycle() }
    }

    private fun launchForegroundFocusProbe(instrumentation: android.app.Instrumentation): BrowserApprovalFocusProbeActivity {
        val packageName = instrumentation.context.packageName
        val component = "$packageName/${BrowserApprovalFocusProbeActivity::class.java.name}"
        val output = instrumentation.uiAutomation.executeShellCommand("am start -W -n $component")
        val text = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
        check("Status: ok" in text) { "Could not launch test focus probe: $text" }

        val deadline = SystemClock.uptimeMillis() + 10_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val focused = instrumentation.runOnMainSyncValue {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is BrowserApprovalFocusProbeActivity && !it.isFinishing && it.hasWindowFocus() }
            }
            if (focused is BrowserApprovalFocusProbeActivity) return focused
            Thread.sleep(25L)
        }
        error("Test focus probe did not reach RESUMED with window focus")
    }

    private fun <T> android.app.Instrumentation.runOnMainSyncValue(block: () -> T): T {
        val result = AtomicReference<T>()
        runOnMainSync { result.set(block()) }
        return result.get()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
