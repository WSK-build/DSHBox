package com.dshbox.browser.runtime

import android.app.Dialog
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises destructive Browser actions only inside the independent test application's data. */
@RunWith(AndroidJUnit4::class)
class BrowserNativeCleanupAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun nativeHistoryClearAndBrowsingCleanupPreserveBookmarksAndDownloadsAndKeepPageOpen() {
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".test"))
        val preferences = BrowserRuntimeSettings.preferences(context)
        preferences.edit().putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, false).commit()
        val output = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${BrowserActivity::class.java.name}",
        )
        val started = ParcelFileDescriptor.AutoCloseInputStream(output).bufferedReader().use { it.readText() }
        assertTrue(started, "Status: ok" in started)
        var activity: BrowserActivity? = null
        val bookmarks = BrowserBookmarksStore(context)
        val bookmark = requireNotNull(bookmarks.save("https://cleanup.example/keep", "Keep bookmark"))
        val download = File(context.filesDir, "browser-tools/out/downloads/cleanup-keep.txt")
        download.parentFile!!.mkdirs()
        download.writeText("Keep downloaded file")
        try {
            await {
                instrumentation.runOnMainSync {
                    activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<BrowserActivity>().firstOrNull()
                }
                activity != null && onMain { field(activity!!, "webView") is WebView }
            }
            val browser = requireNotNull(activity)
            fun seedHistory() {
                assertTrue(preferences.edit().putString("history_entries", BrowserHistoryStore.encode(listOf(
                    BrowserHistoryStore.Entry("cleanup-one", "https://cleanup.example/one", "One"),
                    BrowserHistoryStore.Entry("cleanup-two", "https://cleanup.example/two", "Two"),
                ))).commit())
            }
            seedHistory()
            onMain {
                call(browser, "showHistory")
                val dialog = field(browser, "historyDialog") as Dialog
                val clear = find(dialog.window!!.decorView) {
                    it.contentDescription?.toString() == browser.getString(R.string.browser_history_clear_all)
                }
                requireNotNull(clear).performClick()
            }
            await {
                val root = instrumentation.uiAutomation.rootInActiveWindow ?: return@await false
                val button = root.findAccessibilityNodeInfosByText(browser.getString(R.string.browser_history_clear_all))
                    .firstOrNull { it.className?.toString() == "android.widget.Button" }
                button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            await { BrowserHistoryStore.decode(preferences.getString("history_entries", null)).isEmpty() }
            assertNotNull(bookmarks.findByUrl(bookmark.url))
            assertEquals("Keep downloaded file", download.readText())
            seedHistory()
            onMain {
                (field(browser, "historyDialog") as? Dialog)?.dismiss()
                CookieManager.getInstance().setCookie("https://cleanup.example", "cleanup_cookie=yes")
            }
            await { onMain { CookieManager.getInstance().getCookie("https://cleanup.example")?.contains("cleanup_cookie=yes") == true } }
            onMain { call(browser, "clearBrowsingData") }
            await { BrowserHistoryStore.decode(preferences.getString("history_entries", null)).isEmpty() }
            await { onMain { CookieManager.getInstance().getCookie("https://cleanup.example").isNullOrBlank() } }
            assertNotNull(bookmarks.findByUrl(bookmark.url))
            assertEquals("Keep downloaded file", download.readText())
            onMain {
                assertFalse(browser.isFinishing)
                assertFalse(browser.isDestroyed)
                assertEquals("about:blank", (field(browser, "webView") as WebView).url)
            }
        } finally {
            onMain { activity?.finish() }
            bookmarks.delete(bookmark.id)
            download.delete()
        }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
    private fun call(target: Any, name: String) = target.javaClass.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(target)
    private fun find(view: View, predicate: (View) -> Boolean): View? {
        if (predicate(view)) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index), predicate)?.let { return it }
        return null
    }
    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            assertTrue("native cleanup did not settle", SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(50)
        }
    }
}
