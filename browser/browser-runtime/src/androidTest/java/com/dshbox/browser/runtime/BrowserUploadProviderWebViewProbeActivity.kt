package com.dshbox.browser.runtime

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Test-only real WebView upload reader. It never launches the system picker. */
class BrowserUploadProviderWebViewProbeActivity : Activity() {
    private lateinit var webView: WebView

    fun probeWebView(): WebView = webView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.addJavascriptInterface(ProbeJavascriptBridge(), "UploadProbe")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                chooserCalls.incrementAndGet()
                chooserLatch.countDown()
                val uri = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
                if (view !== webView || filePathCallback == null || fileChooserParams == null || uri == null) {
                    filePathCallback?.onReceiveValue(null)
                    return true
                }
                filePathCallback.onReceiveValue(arrayOf(uri))
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (view === webView) pageLoadedLatch.countDown()
            }
        }
        setContentView(webView)
        webView.loadDataWithBaseURL(
            "https://upload-probe.invalid/",
            HTML,
            "text/html",
            "UTF-8",
            null,
        )
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("UploadProbe")
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private class ProbeJavascriptBridge {
        @JavascriptInterface
        fun complete(json: String) {
            result = json
            resultLatch.countDown()
        }
    }

    companion object {
        const val EXTRA_URI = "com.dshbox.browser.runtime.test.UPLOAD_URI"
        private const val HTML = """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;width:100%;height:100%;background:#fff}#upload{position:absolute;left:32px;top:64px;width:240px;height:80px}</style>
            <input id="upload" type="file" accept="text/plain">
            <script>
              document.getElementById('upload').addEventListener('change', async event => {
                try {
                  const file = event.target.files && event.target.files[0];
                  if (!file) throw new Error('no_file');
                  await new Promise(resolve => setTimeout(resolve, 1700));
                  const bytes = await file.arrayBuffer();
                  const digest = await crypto.subtle.digest('SHA-256', bytes);
                  const sha256 = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
                  UploadProbe.complete(JSON.stringify({code:file.size===19?'PASS':'FAIL',size:file.size,sha256}));
                } catch (error) {
                  UploadProbe.complete(JSON.stringify({code:'ERROR',message:String(error)}));
                }
              });
            </script>
        """
        @Volatile var result: String? = null
            private set
        val chooserCalls = AtomicInteger()
        @Volatile private var pageLoadedLatch = CountDownLatch(1)
        @Volatile private var chooserLatch = CountDownLatch(1)
        @Volatile private var resultLatch = CountDownLatch(1)

        fun resetProbeState() {
            result = null
            chooserCalls.set(0)
            pageLoadedLatch = CountDownLatch(1)
            chooserLatch = CountDownLatch(1)
            resultLatch = CountDownLatch(1)
        }

        fun awaitPageLoaded(timeout: Long, unit: TimeUnit) = pageLoadedLatch.await(timeout, unit)
        fun awaitChooser(timeout: Long, unit: TimeUnit) = chooserLatch.await(timeout, unit)
        fun awaitResult(timeout: Long, unit: TimeUnit) = resultLatch.await(timeout, unit)
    }
}
