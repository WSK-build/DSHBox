package com.dshbox.browser.runtime

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Shared WebView construction for the session layer and the mounted activity. Extracted from the
 * activity so a background session can build an identically configured page before any window
 * exists. Callbacks that require live UI state are installed by the caller after construction.
 */
internal class BrowserPageFactory(
    private val context: Context,
    private val renderingSettings: () -> BrowserRenderingSettings,
    private val themePolicy: () -> BrowserWebThemePolicy,
) {
    fun create(): WebView {
        val view = WebView(context)
        BrowserVisualScript.install(view)
        configureBaseSettings(view)
        applyRendering(view, renderingSettings())
        BrowserWebTheme.apply(view, themePolicy())
        return view
    }

    fun applyRendering(view: WebView, settings: BrowserRenderingSettings) {
        val defaultAgent = WebSettings.getDefaultUserAgent(context)
        view.settings.apply {
            userAgentString = if (settings.userAgentMode == BrowserRenderingSettings.UserAgentMode.DESKTOP) {
                BrowserDesktopUserAgent.from(defaultAgent)
            } else {
                defaultAgent
            }
            useWideViewPort = settings.layoutWidthMode == BrowserRenderingSettings.LayoutWidthMode.WIDE
            loadWithOverviewMode = settings.overviewMode
            textZoom = settings.textScalePercent
        }
    }

    private fun configureBaseSettings(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Popups must reach the WebChromeClient handler so a single policy can accept an
            // expected AI window or a real human gesture, else drop it. Auto-open is enabled so a
            // gesture-less window.open reaches onCreateWindow; the handler then rejects anything
            // without an active AI session + one-use expectation or a real user gesture.
            setSupportMultipleWindows(true)
            setJavaScriptCanOpenWindowsAutomatically(true)
        }
    }
}
