package com.dshbox.dsh.renderer.system

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewCompat.WebMessageListener
import androidx.webkit.WebViewFeature
import com.dshbox.dsh.renderer.api.DshAsyncRequestId
import com.dshbox.dsh.renderer.api.DshFileChooserRequest
import com.dshbox.dsh.renderer.api.DshMainFrameDecision
import com.dshbox.dsh.renderer.api.resolveMainFrameNavigation
import com.dshbox.dsh.renderer.api.DshLoadDispatchResult
import com.dshbox.dsh.renderer.api.DshLoadRejectionReason
import com.dshbox.dsh.renderer.api.DshNativeRef
import com.dshbox.dsh.renderer.api.DshOriginPolicy
import com.dshbox.dsh.renderer.api.DshRenderFailure
import com.dshbox.dsh.renderer.api.DshRenderFailureKind
import com.dshbox.dsh.renderer.api.DshRenderIdentity
import com.dshbox.dsh.renderer.api.DshRenderLoadRequest
import com.dshbox.dsh.renderer.api.DshRenderer
import com.dshbox.dsh.renderer.api.DshDownloadRequest
import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.api.DshRendererCapabilities
import com.dshbox.dsh.renderer.api.DshRendererConfig
import com.dshbox.dsh.renderer.api.DshRendererEvent
import com.dshbox.dsh.renderer.api.DshRendererFeature
import com.dshbox.dsh.renderer.api.DshRendererHost
import com.dshbox.dsh.renderer.api.DshRendererTheme
import com.dshbox.dsh.renderer.api.DshSelectedFile
import com.dshbox.dsh.renderer.api.DshWebPermission
import com.dshbox.dsh.renderer.api.DshWebPermissionRequest
import com.dshbox.dsh.renderer.api.DshWebCapability
import org.json.JSONObject
import java.net.URI
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/**
 * System WebView implementation of the engine-independent renderer contract.
 *
 * The host owns keyboard insets, page chrome, upload-source UI, permission UI,
 * and Auth policy. This adapter owns only WebView configuration, native engine
 * callbacks, and exactly-once settlement of file/permission callbacks.
 */
class SystemDshRendererAdapter : DshRendererAdapter {
    override val id: String = "system-webview"
    override val displayName: String = "System WebView"

    override val capabilities: DshRendererCapabilities = DshRendererCapabilities(
        features = setOf(
            DshRendererFeature.FILE_CHOOSER,
            DshRendererFeature.WEB_PERMISSIONS,
            DshRendererFeature.CUSTOM_SCHEMES,
            DshRendererFeature.RENDERER_PROCESS_GONE,
            DshRendererFeature.HISTORY_BACK,
            DshRendererFeature.LOCAL_FILE_ACCESS,
            DshRendererFeature.CONTENT_URI_ACCESS,
        ),
    )

    override fun isAvailable(context: Context): Boolean =
        runCatching { WebView.getCurrentWebViewPackage() != null }.getOrDefault(false)

    override fun create(
        context: Context,
        config: DshRendererConfig,
        host: DshRendererHost,
    ): DshRenderer {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "SystemDshRendererAdapter.create must run on the main thread"
        }
        return SystemDshRenderer(
            context = context,
            config = config,
            host = host,
            capabilities = capabilities,
        )
    }
}

private class SystemDshRenderer(
    context: Context,
    private val config: DshRendererConfig,
    private val host: DshRendererHost,
    override val capabilities: DshRendererCapabilities,
) : DshRenderer {
    private val originPolicy =
        DshOriginPolicy.create(config.trustedBaseOrigin, config.mainFrameNavigation)
    private val internalSchemes = config.internalSchemes.mapTo(mutableSetOf()) {
        it.lowercase(Locale.ROOT)
    }
    private val webView = WebView(context)
    private val instanceId = UUID.randomUUID().toString()
    private val sessionId = UUID.randomUUID().toString()

    override val view: View = webView
    override val identity: DshRenderIdentity = DshRenderIdentity(
        instanceRef = DshNativeRef.of(instanceId),
        sessionRef = DshNativeRef.of(sessionId),
    )

    private var released = false
    private var rendererGone = false
    private var documentGeneration = 0L
    private val capabilityProbe = runCatching {
        context.assets.open("dsh-renderer/capability-probe.js")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
    private val workerCapabilityProbe = runCatching {
        context.assets.open(WORKER_PROBE_ASSET)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()
    private var dispatchingLoad = false
    private var flushScheduled = false
    private var nextRequestId = 0L
    private val deferredEvents = ArrayDeque<DshRendererEvent>()

    private var token: String? = null
    private var tokenRevision: Long? = null
    private var navigationId: Long? = null
    private var lastBaseUrl: String? = null
    private var currentRequestedUrl: String? = null

    private var pendingFileChooser: PendingFileChooser? = null
    private var pendingPermission: PendingPermission? = null
    private var documentStartScriptHandler: ScriptHandler? = null
    private var compatibilityInstalled = false
    private var workerProbeId = 0L

    init {
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        configureWebView()
        webView.setBackgroundColor(config.theme.canvasColor)
        installClients()
        webView.setOnTouchListener { current, _ ->
            current.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        compatibilityInstalled = installCompatibilityScript(context)
        if (!compatibilityInstalled) {
            Log.e(
                WEB_LOG_TAG,
                "DSH compatibility injection unavailable; refusing navigation",
            )
        }
        installExternalMessageListener(config.hostMessageChannelEnabled)
        Log.i(WEB_LOG_TAG, "System WebView UA: ${webView.settings.userAgentString}")
        WebView.setWebContentsDebuggingEnabled(config.debuggingEnabled)
    }

    /**
     * 页面外链的宿主消息通道：页面侧兼容脚本在可信 origin 顶层文档内通过
     * `dshboxHost` 对象递交外部 http(s) 目标（JSON 帧），文档自身不导航。
     * 注册以配置开关为前提（代理与直连远程会话声明启用；本机 DSH 容器保持
     * 默认关闭，页面行为零变化——注册会把同源 `_blank` 从「静默失败」变为
     * 可递交）。gecko 远程会话不注册该通道：其内置扩展 content script 只
     * 注入固定的本机 origin 注入范围，远程页面拿不到页面桥。注册失败不阻碍
     * 会话，影响仅限页面外链不可递交。
     */
    private fun installExternalMessageListener(enabled: Boolean) {
        if (!enabled) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.w(WEB_LOG_TAG, "WEB_MESSAGE_LISTENER unavailable; page external bridge stays unregistered")
            return
        }
        val originRule = runCatching { trustedOriginRule(config.trustedBaseOrigin) }.getOrNull()
        if (originRule == null) {
            Log.w(WEB_LOG_TAG, "External message channel unavailable: invalid trusted origin")
            return
        }
        runCatching {
            WebViewCompat.addWebMessageListener(
                webView,
                DSH_HOST_MESSAGE_NAME,
                setOf(originRule),
                WebMessageListener { _, webMessage, sourceOrigin, isMainFrame, _ ->
                    handleExternalHostMessage(webMessage, sourceOrigin, isMainFrame, originRule)
                },
            )
        }.onFailure { failure ->
            Log.w(
                WEB_LOG_TAG,
                "Cannot register external message channel: ${redact(failure.message.orEmpty())}",
            )
        }
    }

    /**
     * 外链消息受理校验：仅主框架文档、源 origin 与可信规则一致、JSON 结构
     * 合法且地址为无内嵌凭据的绝对 http(s) 短地址。通过则上报外链事件
     * （[DshRendererEvent.ExternalUrlRequested]，forced = true——页面递交的
     * 打开请求一律强制外放，宿主不得就地加载目标）；校验失败只记一条不含
     * 地址的告警，不抛异常（消息源是页面自身，异常无恢复价值）。
     */
    private fun handleExternalHostMessage(
        webMessage: WebMessageCompat?,
        sourceOrigin: Uri?,
        isMainFrame: Boolean,
        originRule: String,
    ) {
        if (released) return
        if (!isMainFrame) return
        val messageOrigin = sourceOrigin?.toString() ?: return
        if (messageOrigin != originRule) return
        val url = DshExternalMessage.parse(webMessage?.data)
        if (url == null) {
            Log.w(WEB_LOG_TAG, "External bridge message rejected (structure or guard)")
            return
        }
        emit(DshRendererEvent.ExternalUrlRequested(url, forced = true))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = config.allowFileAccess
            allowContentAccess = config.allowContentAccess
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            useWideViewPort = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
            textZoom = 100
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            // Keep the installed WebView's real system UA (already mobile) instead
            // of pinning a stale Chrome version. A host may still override it.
            config.userAgent?.let { userAgentString = it }
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
    }

    private fun installClients() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest,
            ): Boolean {
                if (released) return true
                val uri = request.url
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                if (request.isForMainFrame) {
                    // 主框架导航判定收口在 api 纯函数（默认策略 = 既有行为，
                    // AnyHttps 仅供直连会话放宽口径）
                    when (
                        resolveMainFrameNavigation(
                            mainFrameNavigation = config.mainFrameNavigation,
                            scheme = scheme,
                            targetTrusted = originPolicy.isTrusted(uri.toString()),
                            currentDocumentTrusted = isTrustedDocument(view?.url),
                        )
                    ) {
                        DshMainFrameDecision.Allow -> Unit
                        DshMainFrameDecision.Cancel -> return true
                        DshMainFrameDecision.External -> {
                            emit(DshRendererEvent.ExternalUrlRequested(uri.toString()))
                            return true
                        }
                    }
                }
                if (scheme !in internalSchemes) return false

                if (request.isForMainFrame && isTrustedDocument(view?.url)) {
                    emit(DshRendererEvent.InternalAction(uri))
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (released) return
                documentGeneration += 1
                emit(
                    DshRendererEvent.PageStarted(
                        url = safeUrl(url),
                        navigationId = navigationId,
                    ),
                )
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (released) return
                emit(
                    DshRendererEvent.PageFinished(
                        url = safeUrl(url),
                        navigationId = navigationId,
                    ),
                )
                emit(DshRendererEvent.HistoryChanged(view?.canGoBack() == true))
                observeCapabilities(url)
                observeWorkerCapabilities(url, documentGeneration)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (released || request?.isForMainFrame != true) return
                emit(
                    DshRendererEvent.LoadFailed(
                        DshRenderFailure(
                            message = redact(error?.description?.toString().orEmpty()),
                            kind = DshRenderFailureKind.UNKNOWN,
                            isMainFrame = true,
                            url = safeUrl(request.url.toString()),
                            navigationId = navigationId,
                        ),
                    ),
                )
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                if (released || request?.isForMainFrame != true || errorResponse == null) return
                val statusCode = errorResponse.statusCode
                if (statusCode !in 100..599) return
                val requestUrl = request.url.toString()
                val requestedUrl = currentRequestedUrl
                val exactCurrentRequest =
                    requestedUrl != null &&
                        requestUrl == requestedUrl &&
                        originPolicy.isTrusted(requestedUrl)
                if (statusCode == 401 && exactCurrentRequest) {
                    // WebView may deliver a late callback for a reused URL. Exact
                    // URL matching plus the navigation id is the strongest guard
                    // the engine exposes; it still cannot prove callback age.
                    emit(
                        DshRendererEvent.SystemNativeHttpError(
                            url = safeUrl(requestUrl),
                            statusCode = statusCode,
                            isMainFrame = true,
                            navigationId = navigationId,
                        ),
                    )
                } else {
                    emit(
                        DshRendererEvent.LoadFailed(
                            DshRenderFailure(
                                message = "HTTP $statusCode",
                                kind = DshRenderFailureKind.HTTP,
                                isMainFrame = true,
                                url = safeUrl(requestUrl),
                                httpStatus = statusCode,
                                navigationId = navigationId,
                            ),
                        ),
                    )
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                if (released) return
                emit(DshRendererEvent.HistoryChanged(view?.canGoBack() == true))
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                if (!released && !rendererGone) {
                    rendererGone = true
                    emit(
                        DshRendererEvent.RendererGone(
                            detail = "didCrash=${detail?.didCrash() == true}",
                        ),
                    )
                }
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (released) return
                emit(DshRendererEvent.ProgressChanged(newProgress.coerceIn(0, 100)))
            }

            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                message ?: return false
                val level = message.messageLevel()
                if (level != ConsoleMessage.MessageLevel.WARNING &&
                    level != ConsoleMessage.MessageLevel.ERROR
                ) {
                    return true
                }
                Log.w(
                    WEB_LOG_TAG,
                    "[$level] ${redact(message.message())} " +
                        "(${redact(message.sourceId().orEmpty())}:${message.lineNumber()})",
                )
                return true
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: WebChromeClient.FileChooserParams?,
            ): Boolean {
                if (released || rendererGone || callback == null || params == null) return false

                cancelPendingFileChooser()
                val pending = PendingFileChooser(nextRequestId(), callback)
                pendingFileChooser = pending
                val request = DshFileChooserRequest(
                    requestId = pending.requestId,
                    multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
                    acceptMimeTypes = params.acceptTypes.orEmpty().toList(),
                    captureEnabled = params.isCaptureEnabled,
                )
                if (!emit(DshRendererEvent.FileChooserRequested(request))) {
                    if (pendingFileChooser === pending) pendingFileChooser = null
                    settleFileChooser(pending, null)
                    return false
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                if (released || rendererGone) {
                    deny(request)
                    return
                }
                val trusted = originPolicy.isTrusted(request.origin?.toString().orEmpty())
                val wantsAudio = request.resources.any {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (!trusted || !wantsAudio) {
                    deny(request)
                    return
                }

                cancelPendingPermission()
                val pending = PendingPermission(nextRequestId(), request)
                pendingPermission = pending
                val event = DshWebPermissionRequest(
                    requestId = pending.requestId,
                    origin = request.origin,
                    permissions = request.resources.mapTo(mutableSetOf()) { toApiPermission(it) },
                )
                if (!emit(DshRendererEvent.WebPermissionRequested(event))) {
                    if (pendingPermission === pending) pendingPermission = null
                    deny(request)
                }
            }
        }
    }

    override fun load(request: DshRenderLoadRequest): DshLoadDispatchResult {
        if (released || rendererGone) {
            return DshLoadDispatchResult.Rejected(DshLoadRejectionReason.RELEASED)
        }
        if (!compatibilityInstalled) {
            return DshLoadDispatchResult.Rejected(
                DshLoadRejectionReason.COMPATIBILITY_UNAVAILABLE,
            )
        }
        if (!originPolicy.isTrusted(request.url)) {
            return DshLoadDispatchResult.Rejected(DshLoadRejectionReason.INVALID_REQUEST)
        }
        if (request.tokenRevision != null &&
            tokenRevision != null &&
            request.tokenRevision != tokenRevision
        ) {
            return DshLoadDispatchResult.Rejected(DshLoadRejectionReason.STALE_TOKEN_REVISION)
        }

        token = request.token
        tokenRevision = request.tokenRevision
        navigationId = request.navigationId
        lastBaseUrl = request.url

        val target = dshUrlWithToken(request.url, request.token)
        if (!request.force && target == currentRequestedUrl) {
            return DshLoadDispatchResult.Accepted
        }
        val previousRequestedUrl = currentRequestedUrl
        currentRequestedUrl = target
        return runCatching {
            dispatchUrl(target)
            DshLoadDispatchResult.Accepted
        }.getOrElse {
            currentRequestedUrl = previousRequestedUrl
            DshLoadDispatchResult.Rejected(DshLoadRejectionReason.INVALID_REQUEST)
        }
    }

    override fun updateToken(token: String?, tokenRevision: Long?) {
        if (released || rendererGone) return
        val currentRevision = this.tokenRevision
        if (tokenRevision != null && currentRevision != null && tokenRevision < currentRevision) {
            return
        }
        if (this.token == token && this.tokenRevision == tokenRevision) return
        this.token = token
        this.tokenRevision = tokenRevision
    }

    override fun reload(): Boolean {
        if (released || rendererGone) return false
        val base = lastBaseUrl ?: run {
            webView.reload()
            return true
        }
        if (!originPolicy.isTrusted(base)) return false
        val target = dshUrlWithToken(base, token)
        val previousRequestedUrl = currentRequestedUrl
        currentRequestedUrl = target
        return runCatching {
            dispatchUrl(target)
            true
        }.getOrElse {
            currentRequestedUrl = previousRequestedUrl
            false
        }
    }

    override fun applyTheme(theme: DshRendererTheme) {
        if (released) return
        webView.setBackgroundColor(theme.canvasColor)
    }

    override fun setDownloadListener(listener: ((DshDownloadRequest) -> Unit)?) {
        if (released) return
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            listener?.invoke(
                DshDownloadRequest(
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                ),
            )
        }
    }

    override fun setEnterKeyNewline(newline: Boolean) {
        if (released || rendererGone) return
        // 推送不改注入配置、不落盘：页面兼容层每次按键读取可变全局
        // `__dshboxEnterNewline`，这里只翻那个全局——设置拨动即刻落到已
        // 建立的页面，无需重载。失败静默：最坏是本页保持旧档，宿主下次
        // 装配（重建/新会话）自然按新值注入。
        runCatching {
            webView.evaluateJavascript(buildEnterNewlinePushSource(newline), null)
        }
    }

    override fun canGoBack(): Boolean =
        !released && !rendererGone && webView.canGoBack()

    override fun goBack(): Boolean {
        if (!canGoBack()) return false
        webView.goBack()
        return true
    }

    override fun clearFocusAndHideKeyboard() {
        if (released) return
        runCatching { webView.clearFocus() }
        runCatching { webView.evaluateJavascript(BLUR_ACTIVE_ELEMENT_JS, null) }
        runCatching {
            val inputMethodManager = webView.context.getSystemService(
                InputMethodManager::class.java,
            )
            inputMethodManager?.hideSoftInputFromWindow(webView.windowToken, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                webView.windowInsetsController?.hide(WindowInsets.Type.ime())
            }
        }
    }

    override fun onStart() = Unit

    override fun onResume() {
        if (released) return
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        if (released) return
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onStop() = Unit

    override fun completeFileChooser(
        requestId: DshAsyncRequestId,
        files: List<DshSelectedFile>?,
    ): Boolean {
        if (released) return false
        val pending = pendingFileChooser?.takeIf { it.requestId == requestId } ?: return false
        pendingFileChooser = null
        val values = files
            ?.takeIf { it.isNotEmpty() }
            ?.map { it.uri }
            ?.toTypedArray()
        settleFileChooser(pending, values)
        return true
    }

    override fun completeWebPermission(
        requestId: DshAsyncRequestId,
        grantedPermissions: Set<DshWebPermission>,
    ): Boolean {
        if (released) return false
        val pending = pendingPermission?.takeIf { it.requestId == requestId } ?: return false
        pendingPermission = null
        val grantedResources = pending.request.resources.filter {
            toApiPermission(it) in grantedPermissions
        }
        runCatching {
            if (grantedResources.isEmpty()) {
                pending.request.deny()
            } else {
                pending.request.grant(grantedResources.toTypedArray())
            }
        }
        return true
    }

    override fun release() {
        if (released) return
        released = true
        token = null
        tokenRevision = null
        currentRequestedUrl = null
        lastBaseUrl = null
        deferredEvents.clear()
        runCatching { documentStartScriptHandler?.remove() }
        documentStartScriptHandler = null
        cancelPendingFileChooser()
        cancelPendingPermission()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun emit(event: DshRendererEvent): Boolean {
        if (released) return false
        if (dispatchingLoad) {
            deferredEvents.addLast(event)
            scheduleDeferredEvents()
            return true
        }
        return deliver(event)
    }

    private fun deliver(event: DshRendererEvent): Boolean {
        if (released) return false
        return runCatching {
            host.onRendererEvent(this, event)
            true
        }.getOrElse { failure ->
            Log.e(
                WEB_LOG_TAG,
                "Renderer host event failed: ${redact(failure.message.orEmpty())}",
            )
            false
        }
    }

    private fun scheduleDeferredEvents() {
        if (flushScheduled) return
        flushScheduled = true
        if (!webView.post(::flushDeferredEvents)) {
            flushScheduled = false
            deferredEvents.clear()
        }
    }

    private fun flushDeferredEvents() {
        flushScheduled = false
        if (released) {
            deferredEvents.clear()
            return
        }
        while (deferredEvents.isNotEmpty()) {
            deliver(deferredEvents.removeFirst())
        }
    }

    private fun dispatchUrl(target: String) {
        dispatchingLoad = true
        try {
            webView.loadUrl(target)
        } finally {
            dispatchingLoad = false
        }
    }

    private fun observeCapabilities(documentUrl: String?) {
        val source = capabilityProbe ?: return
        if (released || !isTrustedDocument(documentUrl) || webView.url != documentUrl) return
        val generation = documentGeneration
        runCatching {
            webView.evaluateJavascript(source) { raw ->
                if (released || generation != documentGeneration || webView.url != documentUrl ||
                    raw == null || raw.length > 8192 || !isTrustedDocument(webView.url)
                ) return@evaluateJavascript
                val report = runCatching { JSONObject(raw) }.getOrNull() ?: return@evaluateJavascript
                fun readFlags(value: JSONObject): Map<DshWebCapability, Boolean> =
                    DshWebCapability.entries.mapNotNull { capability ->
                        (value.opt(capability.name) as? Boolean)?.let { capability to it }
                    }.toMap()
                val effective = report.optJSONObject("effective") ?: return@evaluateJavascript
                emit(DshRendererEvent.CapabilitiesObserved(
                    native = report.optJSONObject("native")?.let(::readFlags),
                    effective = readFlags(effective),
                    userAgent = webView.settings.userAgentString
                        .filter { !it.isISOControl() }.take(512),
                ))
            }
        }.onFailure { Log.w(WEB_LOG_TAG, "DSH capability observation unavailable") }
    }

    private fun observeWorkerCapabilities(documentUrl: String?, generation: Long) {
        val source = workerCapabilityProbe ?: return
        if (released || generation != documentGeneration || webView.url != documentUrl ||
            !isTrustedDocument(documentUrl)
        ) return
        val probeId = ++workerProbeId
        val probeToken = UUID.randomUUID().toString()
        val configuredSource = buildWorkerProbeSource(source, probeToken)
        runCatching {
            webView.evaluateJavascript(configuredSource) {
                readWorkerCapabilityResult(documentUrl, generation, probeId, probeToken, 0)
            }
        }.onFailure { Log.w(WEB_LOG_TAG, "DSH Worker capability probe unavailable") }
    }

    private fun readWorkerCapabilityResult(
        documentUrl: String?,
        generation: Long,
        probeId: Long,
        probeToken: String,
        attempt: Int,
    ) {
        webView.postDelayed({
            if (released || generation != documentGeneration || probeId != workerProbeId ||
                webView.url != documentUrl || !isTrustedDocument(documentUrl)
            ) return@postDelayed
            runCatching {
                webView.evaluateJavascript(WORKER_PROBE_READ_EXPRESSION) { raw ->
                    if (released || generation != documentGeneration ||
                        probeId != workerProbeId || webView.url != documentUrl ||
                        raw == null || raw == "null" || raw.length > MAX_WORKER_PROBE_RESULT
                    ) return@evaluateJavascript
                    val report = runCatching { JSONObject(raw) }.getOrNull()
                        ?: return@evaluateJavascript
                    if (report.optString("probeToken") != probeToken) {
                        return@evaluateJavascript
                    }
                    if (report.optString("status") != "complete") {
                        if (attempt < MAX_WORKER_PROBE_ATTEMPTS) {
                            readWorkerCapabilityResult(
                                documentUrl,
                                generation,
                                probeId,
                                probeToken,
                                attempt + 1,
                            )
                        } else {
                            Log.w(WEB_LOG_TAG, "DSH Worker capability probe timed out")
                        }
                        return@evaluateJavascript
                    }
                    val capabilities = report.optJSONObject("capabilities")
                        ?: return@evaluateJavascript
                    val details = report.optJSONObject("details")
                    emit(DshRendererEvent.WorkerCapabilitiesObserved(
                        capabilities = DshWebCapability.entries.mapNotNull { capability ->
                            (capabilities.opt(capability.name) as? Boolean)?.let {
                                capability to it
                            }
                        }.toMap(),
                        details = details?.let { value ->
                            value.keys().asSequence().mapNotNull { name ->
                                (value.opt(name) as? Boolean)?.let { name to it }
                            }.toMap()
                        }.orEmpty(),
                    ))
                }
            }.onFailure {
                Log.w(WEB_LOG_TAG, "DSH Worker capability read unavailable")
            }
        }, if (attempt == 0) INITIAL_WORKER_PROBE_DELAY_MS else WORKER_PROBE_RETRY_MS)
    }

    private fun buildWorkerProbeSource(source: String, probeToken: String): String =
        "(function(){var k=Symbol.for('com.dshbox.renderer.worker-capabilities-config.v1');" +
            "Object.defineProperty(globalThis,k,{configurable:true,enumerable:false," +
            "value:Object.freeze({probeToken:'$probeToken'})});return true}());\n" +
            source

    private fun installCompatibilityScript(context: Context): Boolean {
        fun readAsset(path: String): String? = runCatching {
            context.assets.open(path)
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        }.getOrElse { failure ->
            Log.w(
                WEB_LOG_TAG,
                "Cannot read DSH compatibility resource: ${redact(failure.message.orEmpty())}",
            )
            null
        }

        val bootstrap = readAsset(COMPAT_BOOTSTRAP_ASSET)
        if (bootstrap.isNullOrBlank()) {
            Log.w(WEB_LOG_TAG, "DSH compatibility bootstrap is unavailable")
            return false
        }
        val originRule = trustedOriginRule(config.trustedBaseOrigin)
        val pageCompat = readAsset(PAGE_COMPAT_ASSET)
        if (pageCompat.isNullOrBlank()) {
            Log.w(WEB_LOG_TAG, "DSH page compatibility resource is unavailable")
            return false
        }
        val pageConfig = buildPageCompatConfig(originRule, config.enterKeyNewline)
        // Exactly one document-start registration owns both the capability
        // bootstrap and the APP page bridge. They are not separate entries.
        val source = listOf(
            bootstrap,
            pageConfig,
            pageCompat,
        ).filter { it.isNotBlank() }.joinToString("\n;\n")
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(
                WEB_LOG_TAG,
                "DOCUMENT_START_SCRIPT is unavailable in the active WebView provider",
            )
            return false
        }
        documentStartScriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                source,
                setOf(originRule),
            )
        }.onFailure { failure ->
            Log.w(
                WEB_LOG_TAG,
                "Cannot register DSH compatibility resources: ${redact(failure.message.orEmpty())}",
            )
        }.getOrNull()
        return documentStartScriptHandler != null
    }

    private fun isTrustedDocument(url: String?): Boolean =
        url != null && originPolicy.isTrusted(url)

    private fun nextRequestId(): DshAsyncRequestId =
        DshAsyncRequestId(++nextRequestId)

    private fun cancelPendingFileChooser() {
        val pending = pendingFileChooser ?: return
        pendingFileChooser = null
        settleFileChooser(pending, null)
    }

    private fun settleFileChooser(
        pending: PendingFileChooser,
        uris: Array<Uri>?,
    ) {
        runCatching { pending.callback.onReceiveValue(uris) }
    }

    private fun cancelPendingPermission() {
        val pending = pendingPermission ?: return
        pendingPermission = null
        deny(pending.request)
    }

    private fun deny(request: PermissionRequest) {
        runCatching { request.deny() }
    }

    private data class PendingFileChooser(
        val requestId: DshAsyncRequestId,
        val callback: ValueCallback<Array<Uri>>,
    )

    private data class PendingPermission(
        val requestId: DshAsyncRequestId,
        val request: PermissionRequest,
    )
}

private fun toApiPermission(resource: String): DshWebPermission = when (resource) {
    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> DshWebPermission.AUDIO_CAPTURE
    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> DshWebPermission.VIDEO_CAPTURE
    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> DshWebPermission.PROTECTED_MEDIA_ID
    PermissionRequest.RESOURCE_MIDI_SYSEX -> DshWebPermission.MIDI_SYSEX
    else -> DshWebPermission.OTHER
}

private fun safeUrl(raw: String?): String? {
    raw ?: return null
    return runCatching {
        Uri.parse(raw)
            .buildUpon()
            .clearQuery()
            .fragment(null)
            .build()
            .toString()
    }.getOrNull()
}

private fun redact(text: String): String =
    WEB_LOG_REDACTIONS.fold(text) { current, (regex, replacement) ->
        regex.replace(current, replacement)
    }

private fun dshUrlWithToken(base: String, token: String?): String {
    if (token.isNullOrEmpty()) return base
    val encoded = encodeQueryValue(token)
    val queryStart = base.indexOf('?')
    if (queryStart < 0) return "$base?token=$encoded"
    val path = base.substring(0, queryStart)
    val kept = base.substring(queryStart + 1)
        .split('&')
        .filter { it.isNotEmpty() && !it.startsWith("token=") }
    return "$path?" + (kept + "token=$encoded").joinToString("&")
}

private fun encodeQueryValue(value: String): String = buildString {
    for (character in value) {
        when {
            character.isLetterOrDigit() || character in "-_.~" -> append(character)
            character.code < 0x80 -> append('%').append("%02X".format(character.code))
            else -> append(character)
        }
    }
}

private const val WEB_LOG_TAG = "DshWebConsole"

/** 页面侧外链通道的注入对象名（页面经它 postMessage 递交外部目标）。 */
internal const val DSH_HOST_MESSAGE_NAME = "dshboxHost"

private const val COMPAT_BOOTSTRAP_ASSET = "dsh-compat/standard-bootstrap.js"
private const val PAGE_COMPAT_ASSET = "dsh-renderer/dsh-page-compat.js"
private const val WORKER_PROBE_ASSET = "dsh-compat/worker-capability-probe.js"
private const val WORKER_PROBE_READ_EXPRESSION =
    "(function(){return globalThis[Symbol.for('com.dshbox.renderer.worker-capabilities.v1')]" +
        "||null}())"
private const val INITIAL_WORKER_PROBE_DELAY_MS = 1_800L
private const val WORKER_PROBE_RETRY_MS = 1_500L
private const val MAX_WORKER_PROBE_ATTEMPTS = 3
private const val MAX_WORKER_PROBE_RESULT = 16_384

private fun buildPageCompatConfig(trustedOrigin: String, enterKeyNewline: Boolean): String =
    "(function(){try{" +
        "var k=Symbol.for('com.dshbox.renderer.dsh-page-compat-config.v1');" +
        "Object.defineProperty(globalThis,k,{configurable:true,enumerable:false," +
        "writable:false,value:Object.freeze({trustedOrigin:" +
        JSONObject.quote(trustedOrigin) +
        ",enterKeyNewline:" + enterKeyNewline +
        "})});" +
        "}catch(_){}})();"

/**
 * 回车「换行」开关的**运行期推送**源：只翻页面可变全局
 * `__dshboxEnterNewline`（页面兼容层每次按键读取），不动 document-start
 * 注入的安装期配置。与 [buildPageCompatConfig] 的分工：安装期定初值，
 * 推送管后来的拨动。
 */
internal const val ENTER_NEWLINE_GLOBAL = "__dshboxEnterNewline"

internal fun buildEnterNewlinePushSource(newline: Boolean): String =
    "(function(){try{window.$ENTER_NEWLINE_GLOBAL=" + newline + ";}catch(_){}})();"

private const val BLUR_ACTIVE_ELEMENT_JS =
    "(function(){try{var e=document.activeElement;" +
        "if(e&&typeof e.blur==='function')e.blur();}catch(x){}})()"

private val WEB_LOG_REDACTIONS = listOf(
    Regex("(?i)(api[_-]?key\\s*[:=]\\s*)[A-Za-z0-9_\\-]+") to "$1***",
    Regex("(?i)(authorization\\s*[:=]\\s*)Bearer\\s+[A-Za-z0-9_\\-.]+") to "$1***",
    Regex("(?i)(cookie\\s*[:=]\\s*)[^\\s;]+") to "$1***",
    Regex("(?i)(password\\s*[:=]\\s*)\\S+") to "$1***",
    Regex("(?i)(token\\s*[:=]\\s*)\\S+") to "$1***",
    Regex("sk-[A-Za-z0-9_\\-]+") to "sk-***",
)

private fun trustedOriginRule(base: String): String {
    val uri = URI(base)
    val scheme = (uri.scheme ?: error("Trusted base origin has no scheme"))
        .lowercase(Locale.ROOT)
    val host = (uri.host ?: error("Trusted base origin has no host"))
        .removeSurrounding("[", "]")
    val authorityHost = if (host.contains(':')) "[$host]" else host
    return if (uri.port == -1) {
        "$scheme://$authorityHost"
    } else {
        "$scheme://$authorityHost:${uri.port}"
    }
}
