package com.dshbox.dsh.renderer.gecko

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import com.dshbox.dsh.renderer.api.DshAsyncRequestId
import com.dshbox.dsh.renderer.api.DshLoadDispatchResult
import com.dshbox.dsh.renderer.api.DshLoadRejectionReason
import com.dshbox.dsh.renderer.api.DshNativeDocumentAttribution
import com.dshbox.dsh.renderer.api.DshNativeRef
import com.dshbox.dsh.renderer.api.DshOriginPolicy
import com.dshbox.dsh.renderer.api.DshRenderFailure
import com.dshbox.dsh.renderer.api.DshRenderFailureKind
import com.dshbox.dsh.renderer.api.DshRenderIdentity
import com.dshbox.dsh.renderer.api.DshRenderLoadRequest
import com.dshbox.dsh.renderer.api.DshRenderer
import com.dshbox.dsh.renderer.api.DshRendererCapabilities
import com.dshbox.dsh.renderer.api.DshRendererConfig
import com.dshbox.dsh.renderer.api.DshRendererEvent
import com.dshbox.dsh.renderer.api.DshRendererHost
import com.dshbox.dsh.renderer.api.DshRendererTheme
import com.dshbox.dsh.renderer.api.DshSelectedFile
import com.dshbox.dsh.renderer.api.DshWebPermission
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class GeckoDshRenderer(
    hostContext: Context,
    private val config: DshRendererConfig,
    private val host: DshRendererHost,
    private val runtime: GeckoRuntime,
    override val capabilities: DshRendererCapabilities,
) : DshRenderer {
    private val context = hostContext.applicationContext
    private val originPolicy = DshOriginPolicy.create(config.trustedBaseOrigin)
    private val internalSchemes = config.internalSchemes.mapTo(mutableSetOf()) {
        it.lowercase(Locale.ROOT)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val uploadExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "dsh-gecko-upload").apply { isDaemon = true }
    }

    private val geckoView = GeckoView(hostContext)
    private val session = GeckoSession(createSessionSettings(config))
    private val instanceId = UUID.randomUUID().toString()
    private val sessionId = UUID.randomUUID().toString()
    private val capabilityProbe = runCatching {
        context.assets.open("dsh-renderer/capability-probe.js")
            .bufferedReader().use { it.readText() }
    }.getOrNull()

    override val view: View = geckoView
    override val identity: DshRenderIdentity = DshRenderIdentity(
        instanceRef = DshNativeRef.of(instanceId),
        sessionRef = DshNativeRef.of(sessionId),
    )

    private var released = false
    private var rendererGone = false
    private var eventsEnabled = false
    private var dispatchingLoad = false
    private var requestCounter = 0L

    private var token: String? = null
    private var tokenRevision: Long? = null
    private var navigationId: Long? = null
    private var currentDocumentUrl: String? = null
    private var hasDocument = false
    private var historyCanGoBack = false
    private var pendingLoad: PendingGeckoLoad? = null
    private var expectedHostNavigationUri: String? = null
    private var statusBridgeState = StatusBridgeState.INSTALLING
    private val popupSessions = linkedSetOf<GeckoSession>()

    private val statusCorrelator = GeckoNavigationStatusCorrelator(
        isTrustedUrl = originPolicy::isTrusted,
    )

    private val fileChooserController = GeckoFileChooserController(
        context = context,
        mainHandler = mainHandler,
        executor = uploadExecutor,
        sessionId = sessionId,
        isActive = ::isActiveForEngine,
        emit = ::emit,
        nextRequestId = ::nextRequestId,
    )
    private val permissionController = GeckoPermissionController(
        context = context,
        originPolicy = originPolicy,
        currentDocument = { currentDocumentUrl },
        isActive = ::isActiveForEngine,
        emit = ::emit,
        nextRequestId = ::nextRequestId,
    )
    private val promptController = GeckoPromptController(
        context = hostContext,
        fileChooser = fileChooserController,
        isActive = ::isActiveForEngine,
        allowPopup = ::shouldAllowPopup,
    )
    private val documentStatusBridge = GeckoDocumentStatusBridge(
        runtime = runtime,
        session = session,
        originPolicy = originPolicy,
        correlator = statusCorrelator,
        mainHandler = mainHandler,
        isActive = ::isActiveForEngine,
        onReady = ::statusBridgeReady,
        onUnavailable = ::statusBridgeUnavailable,
        onObservation = ::documentStatusObserved,
    )

    init {
        requireMainThread("GeckoDshRenderer.create")
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        view.setBackgroundColor(config.theme.canvasColor)
        installDelegates()
        try {
            session.open(runtime)
            // SurfaceView 后端在 Activity 后台往返时表面销毁重建，表现为回前台瞬间闪屏；
            // TextureView 走普通 View 合成，无独立表面。DSH 是重交互页面，性能代价可接受。
            geckoView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
            geckoView.setSession(session)
            // 单 session 宿主不调用 setActive(false)：主动交出内容会让 tab 进程
            // 在内存紧张时成为首个回收对象，导致回前台必须整体重建。
            session.setFocused(false)
            session.setPriorityHint(GeckoSession.PRIORITY_HIGH)
            eventsEnabled = true
            documentStatusBridge.start()
        } catch (failure: Throwable) {
            runCatching { geckoView.releaseSession() }
            if (session.isOpen) runCatching { session.close() }
            runCatching { uploadExecutor.shutdownNow() }
            throw failure
        }
    }

    private fun installDelegates() {
        session.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                if (!isActiveForEngine()) return GeckoResult.deny()

                val uri = runCatching { Uri.parse(request.uri) }.getOrNull()
                val scheme = uri?.scheme?.lowercase(Locale.ROOT)
                if ((scheme == "http" || scheme == "https") &&
                    !originPolicy.isTrusted(request.uri) &&
                    isTrustedDocument(currentDocumentUrl) &&
                    (request.target ==
                        GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT ||
                        request.target ==
                        GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW)
                ) {
                    emit(DshRendererEvent.ExternalUrlRequested(request.uri))
                    return GeckoResult.deny()
                }
                if (scheme != null && scheme in internalSchemes) {
                    if (request.target ==
                        GeckoSession.NavigationDelegate.TARGET_WINDOW_CURRENT &&
                        isTrustedDocument(currentDocumentUrl) &&
                        isTrustedDocument(request.triggerUri)
                    ) {
                        emit(DshRendererEvent.InternalAction(uri))
                    }
                    return GeckoResult.deny()
                }

                val isExpectedHostNavigation =
                    request.uri == expectedHostNavigationUri
                if (GeckoNavigationRequestPolicy.shouldInvalidate(
                        isRedirect = request.isRedirect,
                        isDirectNavigation = request.isDirectNavigation,
                        requestUri = request.uri,
                        expectedHostNavigationUri = expectedHostNavigationUri,
                    )
                ) {
                    navigationId = null
                    pendingLoad = null
                    expectedHostNavigationUri = null
                    statusCorrelator.invalidate()
                }
                if (isExpectedHostNavigation) expectedHostNavigationUri = null
                return null
            }

            override fun onSubframeLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                if (!isActiveForEngine()) return GeckoResult.deny()
                val scheme = runCatching { Uri.parse(request.uri).scheme }
                    .getOrNull()
                    ?.lowercase(Locale.ROOT)
                return if (scheme != null && scheme in internalSchemes) {
                    GeckoResult.deny()
                } else {
                    null
                }
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                if (!isActiveForEngine()) return null
                emit(
                    DshRendererEvent.LoadFailed(
                        DshRenderFailure(
                            message = String.format(
                                Locale.ROOT,
                                "Gecko load failed (code=0x%x, category=0x%x)",
                                error.code,
                                error.category,
                            ),
                            kind = error.toFailureKind(),
                            isMainFrame = true,
                            url = safeUrl(uri),
                            navigationId = navigationId,
                        ),
                    ),
                )
                return null
            }

            override fun onNewSession(
                session: GeckoSession,
                uri: String,
            ): GeckoResult<GeckoSession>? {
                if (!isActiveForEngine() ||
                    session !== this@GeckoDshRenderer.session ||
                    !isTrustedDocument(currentDocumentUrl)
                ) {
                    return null
                }
                retireExcessPopupSessions()
                val popup = GeckoSession(createSessionSettings(config))
                installPopupDelegates(popup)
                popupSessions.add(popup)
                return GeckoResult.fromValue(popup)
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                if (!isActiveForEngine()) return
                currentDocumentUrl = url
                hasDocument = url != null
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (!isActiveForEngine() || historyCanGoBack == canGoBack) return
                historyCanGoBack = canGoBack
                emit(DshRendererEvent.HistoryChanged(canGoBack))
            }
        })

        session.setProgressDelegate(object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (!isActiveForEngine()) return
                cancelInteractiveRequests()
                currentDocumentUrl = url
                hasDocument = true
                if (url == expectedHostNavigationUri) expectedHostNavigationUri = null
                emit(
                    DshRendererEvent.PageStarted(
                        url = safeUrl(url),
                        navigationId = navigationId,
                    ),
                )
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (!isActiveForEngine()) return
                statusCorrelator.onPageStopped(navigationId)
                observeCapabilities(currentDocumentUrl)
                emit(
                    DshRendererEvent.PageFinished(
                        url = safeUrl(currentDocumentUrl),
                        navigationId = navigationId,
                    ),
                )
                emit(DshRendererEvent.HistoryChanged(historyCanGoBack))
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (!isActiveForEngine()) return
                emit(DshRendererEvent.ProgressChanged(progress.coerceIn(0, 100)))
            }
        })

        session.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                handleRendererGone("content process crashed")
            }

            override fun onKill(session: GeckoSession) {
                handleRendererGone("content process was killed")
            }
        })

        session.setPromptDelegate(promptController)
        session.setPermissionDelegate(permissionController)
    }

    override fun load(request: DshRenderLoadRequest): DshLoadDispatchResult {
        requireMainThread("GeckoDshRenderer.load")
        if (!isActiveForEngine()) {
            return DshLoadDispatchResult.Rejected(DshLoadRejectionReason.RELEASED)
        }
        if (statusBridgeState == StatusBridgeState.UNAVAILABLE) {
            return DshLoadDispatchResult.Rejected(DshLoadRejectionReason.ENGINE_UNAVAILABLE)
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
        hasDocument = false
        currentDocumentUrl = null
        cancelInteractiveRequests()

        val navigation = statusCorrelator.beginNavigation(request.navigationId, request.url)
        pendingLoad = PendingGeckoLoad(request.url, navigation.nonce)
        dispatchingLoad = true
        return try {
            if (dispatchPendingLoad()) {
                DshLoadDispatchResult.Accepted
            } else {
                DshLoadDispatchResult.Rejected(DshLoadRejectionReason.INVALID_REQUEST)
            }
        } finally {
            dispatchingLoad = false
        }
    }

    override fun updateToken(token: String?, tokenRevision: Long?) {
        requireMainThread("GeckoDshRenderer.updateToken")
        if (!isActiveForEngine()) return
        val currentRevision = this.tokenRevision
        if (tokenRevision != null && currentRevision != null && tokenRevision < currentRevision) {
            return
        }
        if (this.token == token && this.tokenRevision == tokenRevision) return
        this.token = token
        this.tokenRevision = tokenRevision
    }

    override fun reload(): Boolean {
        requireMainThread("GeckoDshRenderer.reload")
        if (!isActiveForEngine() || !hasDocument) return false
        if (statusBridgeState != StatusBridgeState.READY) return false
        val documentUrl = currentDocumentUrl ?: return false
        if (!originPolicy.isTrusted(documentUrl)) return false
        runCatching { session.setActive(true) }
        runCatching { session.setFocused(true) }

        val reloadNavigationId = navigationId
        pendingLoad = null
        statusCorrelator.invalidate()
        cancelInteractiveRequests()
        val navigation = statusCorrelator.beginNavigation(reloadNavigationId, documentUrl)
        pendingLoad = PendingGeckoLoad(documentUrl, navigation.nonce)
        dispatchingLoad = true
        return try {
            dispatchPendingLoad()
        } finally {
            dispatchingLoad = false
        }
    }

    override fun applyTheme(theme: DshRendererTheme) {
        requireMainThread("GeckoDshRenderer.applyTheme")
        if (released) return
        view.setBackgroundColor(theme.canvasColor)
    }

    override fun canGoBack(): Boolean {
        requireMainThread("GeckoDshRenderer.canGoBack")
        return isActiveForEngine() && historyCanGoBack
    }


    override fun goBack(): Boolean {
        requireMainThread("GeckoDshRenderer.goBack")
        if (!canGoBack()) return false
        navigationId = null
        pendingLoad = null
        expectedHostNavigationUri = null
        statusCorrelator.invalidate()
        cancelInteractiveRequests()
        return runCatching {
            session.goBack()
            true
        }.getOrDefault(false)
    }

    override fun clearFocusAndHideKeyboard() {
        requireMainThread("GeckoDshRenderer.clearFocusAndHideKeyboard")
        if (released) return
        runCatching { geckoView.clearFocus() }
        runCatching { session.setFocused(false) }
        runCatching {
            val inputMethodManager = geckoView.context.getSystemService(
                InputMethodManager::class.java,
            )
            inputMethodManager?.hideSoftInputFromWindow(geckoView.windowToken, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { geckoView.windowInsetsController?.hide(WindowInsets.Type.ime()) }
        }
    }

    override fun onStart() {
        requireMainThread("GeckoDshRenderer.onStart")
    }

    override fun onResume() {
        requireMainThread("GeckoDshRenderer.onResume")
        if (!isActiveForEngine()) return
        runCatching { session.setActive(true) }
        runCatching { session.setFocused(true) }
        runCatching { session.setPriorityHint(GeckoSession.PRIORITY_HIGH) }
    }

    override fun onPause() {
        requireMainThread("GeckoDshRenderer.onPause")
        if (released) return
        runCatching { session.setFocused(false) }
        // 后台只撤焦点，不交出内容：setActive(false) 会让 tab 进程在内存紧张时
        // 成为首个回收对象。
    }

    override fun onStop() {
        requireMainThread("GeckoDshRenderer.onStop")
        if (released) return
        runCatching { session.setFocused(false) }
        // 后台只撤焦点，不交出内容；同时缓存会话状态，tab 进程被回收后重建
        // 会话时可经 restoreState 快速恢复页面。
        runCatching { session.flushSessionState() }
    }

    override fun completeFileChooser(
        requestId: DshAsyncRequestId,
        files: List<DshSelectedFile>?,
    ): Boolean {
        requireMainThread("GeckoDshRenderer.completeFileChooser")
        if (!isActiveForEngine()) return false
        return fileChooserController.complete(requestId, files)
    }

    override fun cancelFileChooser(requestId: DshAsyncRequestId): Boolean {
        requireMainThread("GeckoDshRenderer.cancelFileChooser")
        return !released && fileChooserController.cancel(requestId)
    }

    override fun completeWebPermission(
        requestId: DshAsyncRequestId,
        grantedPermissions: Set<DshWebPermission>,
    ): Boolean {
        requireMainThread("GeckoDshRenderer.completeWebPermission")
        if (!isActiveForEngine()) return false
        return permissionController.complete(requestId, grantedPermissions)
    }

    override fun release() {
        requireMainThread("GeckoDshRenderer.release")
        if (released) return
        released = true
        eventsEnabled = false
        token = null
        tokenRevision = null
        currentDocumentUrl = null
        navigationId = null
        pendingLoad = null
        expectedHostNavigationUri = null
        statusCorrelator.invalidate()
        documentStatusBridge.release()
        popupSessions.toList().forEach(::retirePopupSession)

        promptController.release()
        fileChooserController.release()
        permissionController.release()

        runCatching { session.stop() }
        runCatching { session.setFocused(false) }
        runCatching { session.setActive(false) }
        runCatching { session.setNavigationDelegate(null) }
        runCatching { session.setProgressDelegate(null) }
        runCatching { session.setContentDelegate(null) }
        runCatching { session.setPromptDelegate(null) }
        runCatching { session.setPermissionDelegate(null) }
        runCatching { geckoView.releaseSession() }
        if (runCatching { session.isOpen }.getOrDefault(false)) {
            runCatching { session.close() }
        }
        (view.parent as? ViewGroup)?.removeView(view)
        uploadExecutor.shutdownNow()
    }

    private fun shouldAllowPopup(targetUri: String?): Boolean {
        if (!isActiveForEngine() || !isTrustedDocument(currentDocumentUrl)) return false
        if (targetUri.isNullOrBlank() || isBlankPopupUri(targetUri)) return true
        val scheme = runCatching { Uri.parse(targetUri).scheme }
            .getOrNull()
            ?.lowercase(Locale.ROOT)
        return scheme == "http" || scheme == "https"
    }

    private fun installPopupDelegates(popup: GeckoSession) {
        popup.setNavigationDelegate(object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest,
            ): GeckoResult<AllowOrDeny>? {
                if (session !== popup || !isActiveForEngine()) return GeckoResult.deny()

                val uri = runCatching { Uri.parse(request.uri) }.getOrNull()
                val scheme = uri?.scheme?.lowercase(Locale.ROOT)
                if (scheme == "http" || scheme == "https") {
                    emit(DshRendererEvent.ExternalUrlRequested(request.uri))
                    retirePopupSession(popup)
                    return GeckoResult.deny()
                }
                if (isBlankPopupUri(request.uri)) return null
                if (scheme != null && scheme in internalSchemes) {
                    if (isTrustedDocument(request.triggerUri)) {
                        emit(DshRendererEvent.InternalAction(uri))
                    }
                    retirePopupSession(popup)
                    return GeckoResult.deny()
                }
                retirePopupSession(popup)
                return GeckoResult.deny()
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError,
            ): GeckoResult<String>? {
                retirePopupSession(popup)
                return null
            }
        })
        popup.setContentDelegate(object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                retirePopupSession(popup)
            }

            override fun onCrash(session: GeckoSession) {
                retirePopupSession(popup)
            }

            override fun onKill(session: GeckoSession) {
                retirePopupSession(popup)
            }
        })
        popup.setPromptDelegate(object : GeckoSession.PromptDelegate {
            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> =
                GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        })
    }

    private fun retireExcessPopupSessions() {
        while (popupSessions.size >= MAX_POPUP_SESSIONS) {
            val oldest = popupSessions.firstOrNull() ?: return
            retirePopupSession(oldest)
        }
    }

    private fun retirePopupSession(popup: GeckoSession) {
        if (!popupSessions.remove(popup)) return
        runCatching { popup.setNavigationDelegate(null) }
        runCatching { popup.setContentDelegate(null) }
        runCatching { popup.setPromptDelegate(null) }
        if (runCatching { popup.isOpen }.getOrDefault(false)) {
            runCatching { popup.close() }
        }
    }

    private fun handleRendererGone(detail: String) {
        requireMainThread("GeckoDshRenderer.rendererGone")
        if (!isActiveForEngine()) return
        rendererGone = true
        promptController.cancelForNavigation()
        fileChooserController.cancelPending()
        permissionController.cancelPending()
        runCatching { session.setFocused(false) }
        runCatching { session.setActive(false) }
        emit(DshRendererEvent.RendererGone(detail))
    }

    private fun cancelInteractiveRequests() {
        promptController.cancelForNavigation()
        fileChooserController.cancelPending()
        permissionController.cancelPending()
    }

    private fun statusBridgeReady() {
        requireMainThread("GeckoDshRenderer.statusBridgeReady")
        if (!isActiveForEngine()) return
        statusBridgeState = StatusBridgeState.READY
        dispatchPendingLoadFromBridge()
    }

    private fun statusBridgeUnavailable() {
        requireMainThread("GeckoDshRenderer.statusBridgeUnavailable")
        if (!isActiveForEngine()) return
        statusBridgeState = StatusBridgeState.UNAVAILABLE
        statusCorrelator.invalidate()
        val pending = pendingLoad ?: return
        pendingLoad = null
        emit(
            DshRendererEvent.LoadFailed(
                DshRenderFailure(
                    message = "Gecko renderer initialization failed",
                    kind = DshRenderFailureKind.INITIALIZATION,
                    isMainFrame = true,
                    url = safeUrl(pending.baseUrl),
                    navigationId = navigationId,
                ),
            ),
        )
    }

    private fun dispatchPendingLoadFromBridge() {
        if (dispatchingLoad) {
            dispatchPendingLoad()
            return
        }
        dispatchingLoad = true
        try {
            dispatchPendingLoad()
        } finally {
            dispatchingLoad = false
        }
    }

    private fun dispatchPendingLoad(): Boolean {
        val pending = pendingLoad ?: return true
        if (statusBridgeState == StatusBridgeState.INSTALLING) return true
        if (statusBridgeState != StatusBridgeState.READY) return false
        pendingLoad = null
        val nonce = pending.nonce
        val target = dshUrlWithToken(pending.baseUrl, token)
        expectedHostNavigationUri = target
        val dispatched = runCatching {
            val loader = GeckoSession.Loader().uri(target)
            loader.additionalHeaders(mapOf(NAVIGATION_HEADER to nonce))
            loader.headerFilter(GeckoSession.HEADER_FILTER_UNRESTRICTED_UNSAFE)
            session.load(loader)
            true
        }.getOrDefault(false)
        if (!dispatched) {
            expectedHostNavigationUri = null
            val failedNavigationId = navigationId
            emit(
                DshRendererEvent.LoadFailed(
                    DshRenderFailure(
                        message = "Gecko navigation dispatch failed",
                        kind = DshRenderFailureKind.ENGINE,
                        isMainFrame = true,
                        url = safeUrl(pending.baseUrl),
                        navigationId = failedNavigationId,
                    ),
                ),
            )
        }
        return dispatched
    }

    private fun documentStatusObserved(
        observation: GeckoNavigationStatusCorrelator.Observation,
    ) {
        if (!isActiveForEngine() ||
            navigationId != observation.navigationId ||
            !statusCorrelator.isCurrent(observation)
        ) {
            return
        }
        emit(
            DshRendererEvent.MainDocumentStatus(
                attribution = DshNativeDocumentAttribution(
                    identity = identity,
                    navigationId = observation.navigationId,
                    binding = observation.binding,
                    isTopLevelMainFrame = true,
                    adapterVerified = true,
                ),
                httpStatus = observation.httpStatus,
            ),
        )
    }

    private fun isActiveForEngine(): Boolean =
        !released && !rendererGone

    private fun isTrustedDocument(url: String?): Boolean =
        url != null && originPolicy.isTrusted(url)

    private fun observeCapabilities(documentUrl: String?) {
        capabilityProbe ?: return
        if (!isActiveForEngine() || !isTrustedDocument(documentUrl)) return
        session.getUserAgent().accept({ userAgent ->
            if (!isActiveForEngine() || currentDocumentUrl != documentUrl) return@accept
            // GeckoView has no evaluateJavascript equivalent; the probe source stays unused
            // until an in-page observation channel is available. Only the real engine UA is
            // reported here so the diagnostics card no longer shows an uncollected UA.
            emit(DshRendererEvent.CapabilitiesObserved(
                native = null,
                effective = emptyMap(),
                userAgent = userAgent?.filter { !it.isISOControl() }?.take(512),
            ))
        })
    }

    private fun nextRequestId(): DshAsyncRequestId {
        requireMainThread("GeckoDshRenderer.nextRequestId")
        requestCounter += 1
        if (requestCounter <= 0) requestCounter = 1
        return DshAsyncRequestId(requestCounter)
    }

    private fun emit(event: DshRendererEvent): Boolean {
        if (!eventsEnabled || released) return false
        if (dispatchingLoad) {
            mainHandler.post { if (!released) emit(event) }
            return true
        }
        return runCatching {
            host.onRendererEvent(this, event)
            true
        }.getOrElse { failure ->
            Log.e(LOG_TAG, "Renderer host callback failed: ${failure.javaClass.simpleName}")
            false
        }
    }
}

private fun createSessionSettings(config: DshRendererConfig): GeckoSessionSettings {
    val builder = GeckoSessionSettings.Builder()
        .allowJavascript(true)
        .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        .displayMode(GeckoSessionSettings.DISPLAY_MODE_BROWSER)
    config.userAgent?.takeIf { it.isNotBlank() }?.let(builder::userAgentOverride)
    return builder.build()
}

internal fun safeUrl(raw: String?): String? {
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

private fun isBlankPopupUri(uri: String): Boolean =
    uri == "about:blank" ||
        uri.startsWith("about:blank?") ||
        uri.startsWith("about:blank#")

private fun encodeQueryValue(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val unsigned = byte.toInt() and 0xff
        val character = unsigned.toChar()
        when {
            unsigned in 'A'.code..'Z'.code ||
                unsigned in 'a'.code..'z'.code ||
                unsigned in '0'.code..'9'.code ||
                character in "-_.~" -> append(character)

            else -> append('%').append(HEX[unsigned ushr 4]).append(HEX[unsigned and 0x0f])
        }
    }
}

private fun WebRequestError.toFailureKind(): DshRenderFailureKind = when (category) {
    WebRequestError.ERROR_CATEGORY_SECURITY -> DshRenderFailureKind.SSL
    WebRequestError.ERROR_CATEGORY_NETWORK,
    WebRequestError.ERROR_CATEGORY_PROXY,
    -> DshRenderFailureKind.NETWORK

    WebRequestError.ERROR_CATEGORY_CONTENT,
    WebRequestError.ERROR_CATEGORY_URI,
    -> DshRenderFailureKind.ENGINE

    else -> DshRenderFailureKind.UNKNOWN
}

private const val LOG_TAG = "DshGeckoRenderer"
private const val HEX = "0123456789ABCDEF"
private const val NAVIGATION_HEADER = "X-DSHBox-Navigation"
private const val MAX_POPUP_SESSIONS = 4

private data class PendingGeckoLoad(
    val baseUrl: String,
    val nonce: String,
)

private enum class StatusBridgeState {
    INSTALLING,
    READY,
    UNAVAILABLE,
}
