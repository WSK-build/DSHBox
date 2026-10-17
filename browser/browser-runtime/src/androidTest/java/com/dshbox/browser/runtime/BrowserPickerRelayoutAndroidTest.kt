package com.dshbox.browser.runtime

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrowserPickerRelayoutAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun selectedNodeSurvivesViewportRelayoutButReplacementAndRemovalAreRejected() {
        lateinit var view: WebView
        val identity = BrowserPickerElementIdentity()
        try {
            instrumentation.runOnMainSync {
                view = WebView(instrumentation.targetContext)
                view.settings.javaScriptEnabled = true
                view.layout(0, 0, 360, 800)
                view.loadDataWithBaseURL("https://picker.example/",
                    "<meta name='viewport' content='width=device-width'><body><h1 id='selected'>Centered title</h1></body>",
                    "text/html", "UTF-8", null)
            }
            val deadline = System.currentTimeMillis() + 10_000
            while (evaluate(view, "!!document.getElementById('selected')") != "true") {
                check(System.currentTimeMillis() < deadline) { "fixture did not load" }
                Thread.sleep(50)
            }
            evaluate(view, identity.rememberScript("document.getElementById('selected')") + "true")
            evaluate(view, "document.getElementById('selected').style.marginTop='300px';true")
            assertEquals("true", evaluate(view, "!!${identity.resolveScript(1f, 1f, true)}"))
            assertEquals("false", evaluate(view, "!!${identity.resolveScript(1f, 1f, false)}"))
            evaluate(view, "document.getElementById('selected').outerHTML='<h1 id=selected>Centered title</h1>';true")
            assertEquals("false", evaluate(view, "!!${identity.resolveScript(1f, 1f, true)}"))
            evaluate(view, identity.rememberScript("document.getElementById('selected')") + "true")
            evaluate(view, "document.getElementById('selected').remove();true")
            assertEquals("false", evaluate(view, "!!${identity.resolveScript(1f, 1f, true)}"))
            evaluate(view, identity.clearScript() + "true")
        } finally {
            instrumentation.runOnMainSync { runCatching { view.destroy() } }
        }
    }

    private fun evaluate(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result = it; latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
