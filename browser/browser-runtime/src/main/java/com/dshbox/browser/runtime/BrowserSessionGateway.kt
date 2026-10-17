package com.dshbox.browser.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.dshbox.browser.api.BrowserCloseReason
import com.dshbox.browser.api.BrowserLifecycleController
import com.dshbox.browser.api.BrowserOperationToken
import com.dshbox.browser.api.BrowserOwner
import com.dshbox.browser.api.BrowserSnapshot
import com.dshbox.browser.api.BrowserTaskPageIdentity
import org.json.JSONObject
import java.io.File

/**
 * Session-scoped command gateway. Owns the single execution layer (lifecycle, page set, page state,
 * action gate, selection, upload grants, advanced controller) so a background session can serve
 * command execution without an activity, and an activity can adopt the same instances on mount
 * instead of creating a second lifecycle or page manager.
 */
internal class BrowserSessionGateway(
    private val context: Context,
    val host: BrowserSessionHost,
    private val pageFactory: BrowserPageFactory,
) : BrowserCommandGateway {
    private val appContext: Context = context.applicationContext ?: context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val actionGate = BrowserActionGate(android.os.SystemClock::elapsedRealtime)
    private val pageManager = BrowserPageManager<WebView>()
    private val pageStateTracker = BrowserPageStateTracker<WebView>()
    private val selectionStore = BrowserSelectionStore(
        appContext.filesDir,
        File(appContext.filesDir, "browser-tools/out/selection"),
    )
    private val taskFeedbackStore = BrowserTaskFeedbackStore(appContext)
    private val statusPublisher by lazy {
        BrowserStatusPublisher(appContext, File(appContext.filesDir, "browser-tools/run"))
    }
    private val uploadGrantStore = BrowserUploadGrantStore()
    private val uploadStager = BrowserUploadStager(appContext.filesDir.canonicalFile)
    private val uploadApprovalNotice = BrowserUploadApprovalNotice(appContext)
    private val ioExecutor = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue(8),
        { runnable -> Thread(runnable, "browser-session-io").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    )
    private var advancedController: BrowserAdvancedController? = null
    private var captureCoordinator: BrowserScreenshotCaptureCoordinator<android.graphics.Bitmap>? = null
    private var executor: BrowserCommandExecutor? = null
    private val pendingDialogDismissals = java.util.IdentityHashMap<WebView, MutableSet<() -> Unit>>()
    private val idleRunnable = Runnable { onIdleTick() }
    @Volatile private var uiHost: BrowserSessionUiHost? = null
    /** Actual window visibility; a fresh session starts background (false). */
    private var sessionVisible = false

    /** Bumped on open/close so stale callbacks can be ignored. */
    @Volatile private var generation = 0L

    private val statusQueries = BrowserStatusQueryResponder(appContext) {
        publishStatus(host.lifecycleController.snapshot())
    }.also { it.start() }

    val lifecycleController: BrowserLifecycleController get() = host.lifecycleController

    /** Constructs (once) and returns the shared executor so an adopting activity reuses it. */
    fun ensureExecutor(): BrowserCommandExecutor = assertMainThen { executor() }

    fun executorOrNull(): BrowserCommandExecutor? = executor

    fun pageManagerOrNull(): BrowserPageManager<WebView> = pageManager

    /** Shared upload grant state; an adopting activity must use this instance, not a new one. */
    fun uploadGrantStoreOrNull(): BrowserUploadGrantStore = uploadGrantStore

    /** Shared selection store used for `selectionSequence` continuity. */
    fun selectionStoreOrNull(): BrowserSelectionStore = selectionStore

    /** Shared task feedback store so selection/task receipts keep one state across UI and session. */
    fun taskFeedbackStoreOrNull(): BrowserTaskFeedbackStore = taskFeedbackStore

    /** Shared page-state tracker so UI callbacks and session execution read one document state. */
    fun pageStateTrackerOrNull(): BrowserPageStateTracker<WebView> = pageStateTracker

    /**
     * Creates a new page surface from the session's application-context factory. An adopting
     * activity must use this for pages it adds so no WebView captures an activity-wrapped context.
     */
    fun createPageSurface(): WebView {
        assertMainThread()
        return pageFactory.create()
    }

    fun advancedControllerOrNull(): BrowserAdvancedController? { executor(); return advancedController }

    internal fun controlApprovalHost(): BrowserControlApprovalCoordinator.Host =
        object : BrowserControlApprovalCoordinator.Host {
            override fun identity() = executor?.currentUploadPageIdentity()
            override val aiEnabled get() = BrowserRuntimeSettings.preferences(appContext)
                .getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
            override val owner get() = lifecycleController.snapshot().owner
            override val lifecycle get() = lifecycleController.snapshot().lifecycle
            override val controlTransferPending get() = uiHost?.controlTransferPending == true
            override fun presentationContext() = appContext
            override fun showForeground(summary: String, onDecision: (Boolean) -> Unit) = false
            override fun grantAfterVerifiedApproval(): Boolean {
                assertMainThread()
                if (lifecycle != com.dshbox.browser.api.BrowserLifecycle.ACTIVE || owner != BrowserOwner.HUMAN ||
                    identity() == null || controlTransferPending) return false
                val preferences = BrowserRuntimeSettings.preferences(appContext)
                val wasEnabled = aiEnabled
                if (!preferences.edit().putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, true).commit()) {
                    preferences.edit().putBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, wasEnabled).apply()
                    return false
                }
                if (!aiEnabled) return false
                executor?.invalidateBrowserEvents()
                val snapshot = lifecycleController.handToAi()
                publishStatus(snapshot)
                scheduleIdle(snapshot)
                return snapshot.owner == BrowserOwner.AI
            }
        }

    private inline fun <T> assertMainThen(block: () -> T): T {
        assertMainThread()
        return block()
    }

    fun setUiHost(host: BrowserSessionUiHost?) {
        uiHost = host
    }

    /** Opens the session on the main thread and creates the initial page in the shared page set. */
    fun open(owner: BrowserOwner): BrowserSnapshot {
        assertMainThread()
        // Reparent must first cancel/complete any in-flight capture on the same surface.
        host.beforeReparent = { prepareReparent() }
        generation += 1
        val wasActive = host.lifecycleController.snapshot().lifecycle ==
            com.dshbox.browser.api.BrowserLifecycle.ACTIVE
        val snapshot = host.open(owner)
        val view = host.surface()
        if (pageManager.all.none { it.content === view }) {
            pageManager.add(view)
            pageStateTracker.begin(view, "about:blank")
            installSessionCallbacks(view)
        }
        executor().attachActionGate(view)
        // A background session (no mounted window) is not visible; this enables idle release and
        // prevents a false visible/running status. An adopting window later sets visible true.
        // Preserve the tracked visibility for an already-active session; only a fresh session is
        // forced background. Never derive visibility from mount state: a mounted-but-stopped window
        // is still background.
        val settled = host.lifecycleController.setVisible(if (wasActive) sessionVisible else false)
        sessionVisible = settled.visible
        publishStatus(settled)
        scheduleIdle(settled)
        return settled
    }

    /** Updates session visibility (window mount/unmount) and reschedules idle release. */
    fun setVisible(visible: Boolean): BrowserSnapshot {
        assertMainThread()
        sessionVisible = visible
        val snapshot = host.lifecycleController.setVisible(visible)
        publishStatus(snapshot)
        scheduleIdle(snapshot)
        return snapshot
    }

    /**
     * Keeps page state, URL and the advanced controller's document identity current while the
     * session runs without an activity, so background CDP targets the right document. When an
     * activity adopts the surface it may replace these clients; the shared controller still
     * receives URL/identity from the session's own callbacks until then.
     */
    private fun installSessionCallbacks(view: WebView) {
        view.setDownloadListener(android.webkit.DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            val scheme = runCatching { android.net.Uri.parse(url).scheme }.getOrNull()?.lowercase(java.util.Locale.ROOT)
            if (scheme != "http" && scheme != "https") return@DownloadListener
            val snapshot = host.lifecycleController.snapshot()
            val aiEnabled = BrowserRuntimeSettings.preferences(appContext)
                .getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
            // Never loosen website-triggered downloads: only an authorized AI session with an
            // expected download event may start one (same claim path as the mounted activity).
            if (!aiEnabled || snapshot.lifecycle != com.dshbox.browser.api.BrowserLifecycle.ACTIVE ||
                snapshot.owner != com.dshbox.browser.api.BrowserOwner.AI
            ) return@DownloadListener
            val pageId = pageManager.all.firstOrNull { it.content === view }?.id ?: return@DownloadListener
            val claim = executor?.claimBrowserEventExpectation(pageId, "download") ?: return@DownloadListener
            val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
            val name = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = BrowserDownloadRequest(url, name, cookie, userAgent = userAgent?.take(1024))
            try {
                ioExecutor.execute {
                    val started = runCatching {
                        claim.executeIfValid { BrowserDownloadsManager.getInstance(appContext).start(request) }
                    }.getOrNull()
                    // Report any real side effect (even a failed start) with the sanitized URL, so
                    // the AI is not left unaware of a download that actually began.
                    if (started != null) {
                        val safe = BrowserPageStateTracker.publicUrl(url)
                        mainHandler.post {
                            executor?.publishBrowserEvent("download", pageId, JSONObject()
                                .put("url", safe.value ?: JSONObject.NULL).put("downloadId", started.id)
                                .put("state", started.state.name.lowercase(java.util.Locale.ROOT)))
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { }
        })
        view.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onJsAlert(target: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                return handleSessionDialog(target, "alert", message, null, result, null)
            }

            override fun onJsConfirm(target: WebView?, url: String?, message: String?, result: android.webkit.JsResult?): Boolean {
                return handleSessionDialog(target, "confirm", message, null, result, null)
            }

            override fun onJsPrompt(
                target: WebView?, url: String?, message: String?, defaultValue: String?,
                result: android.webkit.JsPromptResult?,
            ): Boolean {
                return handleSessionDialog(target, "prompt", message, defaultValue, result, result)
            }

            override fun onJsBeforeUnload(
                target: WebView?, url: String?, message: String?, result: android.webkit.JsResult?,
            ): Boolean {
                return handleSessionDialog(target, "beforeunload", message, null, result, null)
            }

            override fun onCreateWindow(
                target: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean = handleCreateWindow(target, isUserGesture, resultMsg)

            override fun onShowFileChooser(
                target: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>?,
                fileChooserParams: android.webkit.WebChromeClient.FileChooserParams?,
            ): Boolean {
                // Native chooser requires a visible window; the session never fakes a selection.
                filePathCallback?.onReceiveValue(null)
                return true
            }
        }
        view.webViewClient = object : android.webkit.WebViewClient() {
            override fun onRenderProcessGone(
                target: WebView,
                detail: android.webkit.RenderProcessGoneDetail,
            ): Boolean = handleSessionRendererGone(target)

            override fun onPageStarted(target: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // A new document invalidates any pending JS dialog so page JS cannot stay blocked.
                dismissPendingDialogs(target)
                pageStateTracker.pageStarted(target, url, target.url)
                // Only the active page may advance the global executor document generation or the
                // shared CDP/advanced identity; a background page finishing navigation must not
                // invalidate the active page's references or action gate.
                if (host.surfaceOrNull() === target) {
                    executor?.onDocumentStarted()
                    advancedController?.onDocumentStarted(url)
                }
            }

            override fun onPageFinished(target: WebView, url: String?) {
                pageStateTracker.pageFinished(target, url, target.url, "complete")
                pageManager.update(target, target.title.orEmpty(), target.url.orEmpty())
                if (host.surfaceOrNull() === target) advancedController?.updatePageUrl(url)
            }

            override fun onReceivedError(
                target: WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    pageStateTracker.mainFrameError(target, request.url.toString(), target.url, error.errorCode)
                }
            }

            override fun onReceivedHttpError(
                target: WebView,
                request: android.webkit.WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    pageStateTracker.mainFrameHttpStatus(
                        target, request.url.toString(), target.url, errorResponse.statusCode,
                    )
                }
            }
        }
    }

    /**
     * Restores session-owned page callbacks after an adopting activity tears down its window,
     * clearing activity-bound Chrome/download/touch listeners so no destroyed activity is retained.
     */
    fun restoreSessionCallbacks() {
        assertMainThread()
        // Every page that a window may have touched carries activity-bound listeners; restore all
        // of them to session-owned callbacks (including a real download listener, never null).
        pageManager.all.forEach { page ->
            val view = page.content
            runCatching { view.webChromeClient = null }
            runCatching { view.setOnTouchListener(null) }
            installSessionCallbacks(view)
            executor?.attachActionGate(view)
        }
    }

    /** Disposes the affected surface without treating unrelated pages as renderer failures. */
    private fun handleSessionRendererGone(target: WebView): Boolean {
        assertMainThread()
        val page = pageManager.all.firstOrNull { it.content === target }
        if (page == null) {
            runCatching { (target.parent as? android.view.ViewGroup)?.removeView(target) }
            runCatching { target.destroy() }
            return true
        }
        if (pageManager.size > 1) {
            val removed = BrowserPageTransactions.close(pageManager, page.id) { host.setActiveSurface(it) }
            if (removed != null) {
                pageStateTracker.remove(target)
                dismissPendingDialogs(target, page.id)
                executor?.invalidatePageBrowserEvents(page.id)
                runCatching { (target.parent as? android.view.ViewGroup)?.removeView(target) }
                runCatching { target.destroy() }
                if (removed.wasActive) refreshActivePageIdentity(removed.result.active.content)
                publishStatus(host.lifecycleController.snapshot())
                return true
            }
        }
        close(BrowserCloseReason.RENDERER_GONE)
        return true
    }

    /**
     * Settles a JS dialog through the executor's one-use `javascript_dialog` event expectation.
     * Only the active page under AI ownership with a matching expectation publishes; anything else
     * (closed/background/no expectation/no UI publish) cancels immediately so page JS never blocks
     * and the AI never auto-accepts an unexpected dialog.
     */
    private fun handleSessionDialog(
        view: WebView?,
        dialogType: String,
        message: String?,
        defaultValue: String?,
        result: android.webkit.JsResult?,
        promptResult: android.webkit.JsPromptResult?,
    ): Boolean {
        val target = view ?: return false
        val jsResult = result ?: return false
        val pageId = pageManager.all.firstOrNull { it.content === target }?.id
        val aiOwned = host.lifecycleController.snapshot().owner == com.dshbox.browser.api.BrowserOwner.AI
        val expected = pageId != null && executor?.hasBrowserEventExpectation(pageId, "javascript_dialog") == true
        if (!aiOwned || host.surfaceOrNull() !== target || pageId == null || !expected) {
            runCatching { jsResult.cancel() }
            return true
        }
        val settled = java.util.concurrent.atomic.AtomicBoolean(false)
        lateinit var dismissPending: () -> Unit
        val resolver: (String, String?) -> Boolean = { decision, promptText ->
            if (!settled.compareAndSet(false, true)) {
                false
            } else {
                pendingDialogDismissals[target]?.remove(dismissPending)
                runCatching {
                    if (promptResult != null) {
                        if (decision == "prompt") promptResult.confirm(promptText.orEmpty()) else promptResult.cancel()
                    } else if (decision == "accept") {
                        jsResult.confirm()
                    } else {
                        jsResult.cancel()
                    }
                }.isSuccess
            }
        }
        dismissPending = {
            if (settled.compareAndSet(false, true)) {
                runCatching { jsResult.cancel() }
                pendingDialogDismissals[target]?.remove(dismissPending)
            }
        }
        pendingDialogDismissals.getOrPut(target) { linkedSetOf() }.add(dismissPending)
        val payload = JSONObject().put("dialog", JSONObject()
            .put("dialogType", dialogType)
            .put("message", message.orEmpty().take(512))
            .apply { if (dialogType == "prompt") put("promptDefault", defaultValue.orEmpty().take(512)) })
        if (executor?.publishBrowserDialog(pageId, payload, resolver) == null) dismissPending()
        return true
    }

    /**
     * Cancels pending session JS dialog resolvers so page JS is not left blocked. A non-null
     * [target] dismisses only that view's dialogs (ordinary navigation); null dismisses all
     * (page switch, control revoke, session close) so other pages' resolvers are not disturbed.
     */
    /**
     * Shared new-window handler for both the session chrome client and the mounted activity chrome.
     * The active parent page and manager active pointer never change; only an expected AI window or
     * a real human gesture opens a child from the application-context factory.
     *
     * JS auto-open is enabled in BrowserPageFactory so a gesture-less `window.open()` reaches this
     * callback; it is still strictly rejected unless it matches an active AI session with a one-use
     * expectation or a visible, human-owned real user gesture.
     */
    fun handleCreateWindow(
        target: WebView?,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean {
        assertMainThread()
        val parent = target ?: return false
        val message = resultMsg ?: return false
        if (pageManager.size >= BrowserPageManager.MAX_PAGES) return false
        val parentPage = pageManager.all.firstOrNull { it.content === parent } ?: return false
        val snapshot = host.lifecycleController.snapshot()
        if (snapshot.lifecycle != com.dshbox.browser.api.BrowserLifecycle.ACTIVE) return false
        val parentActive = host.surfaceOrNull() === parent
        val automationEnabled = BrowserRuntimeSettings.preferences(appContext)
            .getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        val claim = if (snapshot.owner == com.dshbox.browser.api.BrowserOwner.AI &&
            parentActive && automationEnabled
        ) {
            executor?.claimBrowserEventExpectation(parentPage.id, "new_window")
        } else {
            null
        }
        val decision = BrowserPopupPolicy.decide(
            owner = snapshot.owner,
            automationEnabled = automationEnabled,
            activePage = parentActive,
            hasExpectedClaim = claim != null,
            userGesture = isUserGesture,
            pageCount = pageManager.size,
            maxPages = BrowserPageManager.MAX_PAGES,
            visible = snapshot.visible,
            sessionActive = snapshot.lifecycle == com.dshbox.browser.api.BrowserLifecycle.ACTIVE,
        )
        if (decision == BrowserPopupDecision.REJECT) return false
        val child = runCatching { pageFactory.create() }.getOrNull() ?: return false
        if (runCatching { installSessionCallbacks(child) }.isFailure) {
            runCatching { child.destroy() }
            return false
        }
        val page = pageManager.add(child) ?: run {
            runCatching { child.destroy() }
            return false
        }
        var transportRef: WebView.WebViewTransport? = null
        fun rollback() {
            // Clear the transport first so a queued WebViewTransport message never reads a
            // destroyed child after this rollback.
            runCatching { transportRef?.webView = null }
            pageManager.close(page.id)
            pageStateTracker.remove(child)
            runCatching { child.destroy() }
        }
        // Never steal the active parent surface or manager active pointer for a popup.
        pageManager.switchTo(parentPage.id)
        // While a window is mounted, hand the child the activity's callbacks (creation stays
        // session-owned/app-context; detach restores session callbacks over all pages).
        uiHost?.let { host ->
            if (runCatching { host.installMountedCallbacks(child) }.isFailure) {
                rollback()
                return false
            }
        }
        if (runCatching { executor?.attachActionGate(child) }.isFailure) {
            rollback()
            return false
        }
        val transport = message.obj as? WebView.WebViewTransport
        if (transport == null) {
            rollback()
            return false
        }
        transportRef = transport
        if (runCatching {
                transport.webView = child
                pageStateTracker.begin(child, "about:blank")
            }.isFailure
        ) {
            rollback()
            return false
        }
        // Revalidate the live AI authorization before showing the window: a cancelled/retaken
        // session, disabled automation, expired claim or switched parent must not receive a window.
        if (decision == BrowserPopupDecision.AI_EXPECTED) {
            val now = host.lifecycleController.snapshot()
            val automationNow = BrowserRuntimeSettings.preferences(appContext)
                .getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
            if (now.lifecycle != com.dshbox.browser.api.BrowserLifecycle.ACTIVE ||
                now.owner != com.dshbox.browser.api.BrowserOwner.AI ||
                now.sessionGeneration != snapshot.sessionGeneration ||
                now.controlGeneration != snapshot.controlGeneration ||
                !automationNow || claim?.isValid() != true || host.surfaceOrNull() !== parent
            ) {
                rollback()
                return false
            }
        }
        // Commit the window, then require the event to be announced. A new window that cannot be
        // published (claim expired/revoked, identity changed) is not accepted: roll it back so the
        // AI never sees an eventless window, and a failed send never leaves a false success event.
        if (runCatching { message.sendToTarget() }.isFailure) {
            rollback()
            return false
        }
        if (decision == BrowserPopupDecision.AI_EXPECTED) {
            val published = runCatching {
                executor?.publishBrowserEvent("new_window", parentPage.id, JSONObject().put("newPageId", page.id))
            }.getOrNull()
            if (published == null) {
                rollback()
                return false
            }
        }
        return true
    }

    private fun dismissPendingDialogs(target: WebView? = null, knownPageId: String? = null) {
        val targetPageId = knownPageId ?: target?.let { view ->
            pageManager.all.firstOrNull { it.content === view }?.id
        }
        if (targetPageId != null) {
            executor?.dismissBrowserDialogsForPage(targetPageId)
        } else if (target == null) {
            executor?.dismissAllBrowserDialogs()
        }
        val pending = if (target == null) {
            pendingDialogDismissals.values.flatMap { it.toList() }.also { pendingDialogDismissals.clear() }
        } else {
            pendingDialogDismissals.remove(target)?.toList().orEmpty()
        }
        pending.forEach { dismissal -> runCatching { dismissal.invoke() } }
    }

    fun loadUrl(url: String): Boolean {
        assertMainThread()
        val view = host.surfaceOrNull() ?: return false
        dismissPendingDialogs(view)
        pageStateTracker.begin(view, url, view.url)
        executor().onDocumentStarted()
        advancedController?.onDocumentStarted(url)
        advancedController?.updatePageUrl(url)
        view.loadUrl(url)
        return true
    }

    fun close(reason: BrowserCloseReason = BrowserCloseReason.AI_CLOSE): BrowserSnapshot {
        assertMainThread()
        if (lifecycleController.snapshot().lifecycle == com.dshbox.browser.api.BrowserLifecycle.CLOSED) {
            return lifecycleController.snapshot()
        }
        val closingUiHost = uiHost
        uiHost = null
        statusQueries.close()
        // Explicit close is terminal: cancel/release any active capture. The virtual display is
        // released even when the WebView restore fails, so the surface is then force-disposed (the
        // Host terminal teardown ignores the reparent guard) and a new session can be created.
        runCatching { captureCoordinator?.cancelActiveForReparent() }
        generation += 1
        cancelIdle()
        dismissPendingDialogs()
        runCatching { captureCoordinator?.close() }
        captureCoordinator = null
        runCatching { advancedController?.close() }
        advancedController = null
        // Terminal executor shutdown before dropping the reference: detach the action gate and push
        // a disabled authorization state so pending waits, event resolvers and thumbnail timers are
        // cancelled instead of retaining the old view through late callbacks.
        executor?.let { ex ->
            runCatching { ex.detachActionGate() }
            runCatching { ex.updateAuthorizationState(host.lifecycleController.snapshot(), enabled = false) }
            runCatching { ex.invalidateBrowserEvents() }
        }
        executor = null
        runCatching { ioExecutor.shutdownNow() }
        // Reclaim session-owned transient resources; persistent task/selection history is kept.
        runCatching { uploadApprovalNotice.cancel() }
        runCatching { uploadGrantStore.revokeAll(android.os.SystemClock.elapsedRealtime()) }
        runCatching { taskFeedbackStore.close() }
        // Destroy each page once; the host owns (and destroys) its current surface.
        val currentSurface = host.surfaceOrNull()
        pageManager.all.filter { it.content !== currentSurface }
            .forEach { runCatching { it.content.destroy() } }
        pageManager.clear()
        val snapshot = host.close(reason)
        BrowserSessionGatewayRegistry.clear(this)
        BrowserSessionHostRegistry.clear(host)
        publishStatus(snapshot)
        closingUiHost?.onSessionClosed(reason)
        return snapshot
    }

    override fun execute(requestJson: String, callback: (String) -> Unit) {
        val current = executor
        if (current == null) {
            val id = runCatching { JSONObject(requestJson).optString("requestId") }.getOrDefault("")
            callback(commandResponse(id, "CLOSED", false, "CLOSED").toString())
            return
        }
        current.execute(requestJson, callback)
    }

    override fun cancel(requestId: String, callback: (String) -> Unit) {
        executor?.cancel(requestId, callback)
            ?: callback(commandResponse(requestId, "CLOSED", false, "CLOSED").toString())
    }

    override fun cancelWait(requestId: String, code: String): Boolean =
        executor?.cancelWait(requestId, code) ?: false

    private fun executor(): BrowserCommandExecutor {
        executor?.let { return it }
        val enabled = { BrowserRuntimeSettings.preferences(appContext)
            .getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED) }
        val controller = BrowserAdvancedController(
            context = appContext,
            debugEnabled = BrowserRuntimeSettings.preferences(appContext)
                .getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false),
            networkMutationEnabled = BrowserRuntimeSettings.preferences(appContext)
                .getBoolean(BrowserRuntimeSettings.KEY_NETWORK_MUTATION_ENABLED, false),
        )
        advancedController = controller
        val virtualDisplay = BrowserVirtualDisplayCapture(host.window())
        val coordinator = BrowserScreenshotCaptureCoordinator(
            primary = BrowserScreenshotCaptureSource { deadline, callback ->
                val view = host.surfaceOrNull()
                if (view == null) {
                    callback(BrowserScreenshotCaptureResult(
                        null, BrowserScreenshotCaptureContract.VIRTUAL_DISPLAY_UNAVAILABLE,
                    ))
                    BrowserScreenshotCaptureHandle { false }
                } else {
                    val metrics = appContext.resources.displayMetrics
                    virtualDisplay.capture(
                        view,
                        BrowserVirtualDisplayCapture.Request(
                            width = view.width.takeIf { it > 0 } ?: metrics.widthPixels,
                            height = view.height.takeIf { it > 0 } ?: metrics.heightPixels,
                            densityDpi = metrics.densityDpi,
                            isCurrent = { host.surfaceOrNull() === view },
                        ),
                        deadline,
                        callback,
                    )
                }
            },
            fallback = BrowserScreenshotCaptureSource { deadline, callback ->
                val view = host.surfaceOrNull()
                val debugEnabled = BrowserRuntimeSettings.preferences(appContext)
                    .getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false)
                val identity = controller.currentDocumentIdentity()
                val metrics = appContext.resources.displayMetrics
                // Match the primary's session viewport: background pages have no layout size yet.
                val expectedWidth = view?.width?.takeIf { it > 0 } ?: metrics.widthPixels
                val expectedHeight = view?.height?.takeIf { it > 0 } ?: metrics.heightPixels
                val failureCode = when {
                    !debugEnabled -> BrowserScreenshotCaptureContract.DEBUG_REQUIRED
                    view == null -> "CLOSED"
                    identity == null -> "IDENTITY_CHANGED"
                    expectedWidth <= 0 || expectedHeight <= 0 -> "VIEWPORT_UNAVAILABLE"
                    else -> null
                }
                if (failureCode != null) {
                    val debugRequired = failureCode == BrowserScreenshotCaptureContract.DEBUG_REQUIRED
                    callback(BrowserScreenshotCaptureResult(
                        null,
                        failureCode,
                        if (debugRequired) {
                            BrowserScreenshotOutcome(
                                attempts = listOf(BrowserScreenshotAttempt(
                                    BrowserScreenshotCaptureContract.FALLBACK_BACKEND, failureCode,
                                )),
                                userActionRequired = true,
                            )
                        } else {
                            BrowserScreenshotOutcome()
                        },
                    ))
                    BrowserScreenshotCaptureHandle { false }
                } else {
                    virtualDisplay.captureWithRenderer(
                        view!!,
                        BrowserVirtualDisplayCapture.Request(expectedWidth, expectedHeight, metrics.densityDpi) {
                            host.surfaceOrNull() === view && controller.currentDocumentIdentity() == identity
                        },
                        deadline,
                        BrowserScreenshotCaptureSource { renderDeadline, rendererCallback ->
                    controller.captureBackgroundScreenshot(
                        expectedUrl = view.url.orEmpty(),
                        expectedDocumentIdentity = identity!!,
                        expectedPixelWidth = expectedWidth,
                        expectedPixelHeight = expectedHeight,
                        deadlineElapsedMillis = renderDeadline,
                    ) { result ->
                        // The controller invokes this on its own executor thread; decode, re-verify
                        // and deliver on the main thread where the coordinator/executor expect it.
                        mainHandler.post {
                            val screenshot = result.screenshot
                            if (screenshot == null) {
                                rendererCallback(BrowserScreenshotCaptureResult(null, result.code))
                                return@post
                            }
                            val debugStillOn = BrowserRuntimeSettings.preferences(appContext)
                                .getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false)
                            val expired = android.os.SystemClock.elapsedRealtime() >= deadline
                            val bytes = screenshot.pngBytes
                            var bitmap: android.graphics.Bitmap? = null
                            val code = try {
                                when {
                                    expired -> "DEADLINE_EXPIRED"
                                    !debugStillOn -> BrowserScreenshotCaptureContract.DEBUG_REQUIRED
                                    host.surfaceOrNull() !== view || controller.currentDocumentIdentity() != identity ->
                                        "IDENTITY_CHANGED"
                                    !com.dshbox.browser.devtools.CdpViewportRaster.matchesNativeViewport(
                                        expectedWidth, expectedHeight, screenshot.viewportWidth, screenshot.viewportHeight,
                                        metrics.density) -> "CLIPPED_VIEWPORT"
                                    !BrowserBackgroundCaptureGate.validPng(bytes, screenshot.viewportWidth, screenshot.viewportHeight) ->
                                        "PIXEL_READ_FAILED"
                                    else -> {
                                        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        if (decoded == null || decoded.width != screenshot.viewportWidth ||
                                            decoded.height != screenshot.viewportHeight
                                        ) {
                                            decoded?.recycle()
                                            "PIXEL_READ_FAILED"
                                        } else {
                                            bitmap = decoded
                                            "OK"
                                        }
                                    }
                                }
                            } finally {
                                bytes.fill(0)
                            }
                            rendererCallback(BrowserScreenshotCaptureResult(bitmap, code))
                        }
                    }
                    BrowserScreenshotCaptureHandle { false }
                        },
                        callback,
                    )
                }
            },
            debugEnabled = {
                BrowserRuntimeSettings.preferences(appContext)
                    .getBoolean(BrowserRuntimeSettings.KEY_ADVANCED_ENABLED, false)
            },
            recoverPrimaryRestore = { virtualDisplay.recoverRestore() },
        )
        val created = BrowserCommandExecutor(
            windowHost = host.window(),
            lifecycle = { host.lifecycleController },
            webView = { host.surfaceOrNull() },
            actionGate = actionGate,
            automationEnabled = enabled,
            capture = { deadline, callback -> captureSession(deadline, callback) },
            captureDetailed = BrowserScreenshotRouter(
                foreground = { host.snapshot().visible && host.window().hasWindowFocus() },
                native = BrowserScreenshotCaptureSource { deadline, callback ->
                    captureSession(deadline) { bitmap, code ->
                        callback(BrowserScreenshotCaptureResult(bitmap, code))
                    }
                    BrowserScreenshotCaptureHandle { false }
                },
                background = coordinator,
            ),
            publish = { snapshot -> publishStatus(snapshot) },
            loadUrl = { url -> loadUrl(url) },
            closeSession = { close() },
            executePage = { action, args -> sessionPageCommand(action, args) },
            executeFile = { action, args, deadline, authorized, callback ->
                if (uiHost?.executeFile(action, args, deadline, authorized, callback) != true) {
                    sessionFileCommand(action, args, deadline, authorized, callback)
                }
            },
            executeUpload = { request, identity, token, authorized, callback ->
                if (uiHost?.executeUpload(request, identity, token, authorized, callback) != true) {
                    sessionUploadCommand(request, identity, authorized, callback)
                }
            },
            // Grant state uses the monotonic clock everywhere; never mix in wall time here.
            invalidateUploadTask = { taskId ->
                uploadGrantStore.revokeTask(taskId, android.os.SystemClock.elapsedRealtime())
            },
            executeAdvanced = { family, args, deadline, authorized, callback ->
                if (family == "selection") {
                    // Persistent selection/task feedback is not an advanced-debug capability.
                    callback(sessionSelectionCommand(args, authorized))
                } else if (uiHost?.executeAdvanced(family, args, deadline, authorized, callback) != true) {
                    sessionAdvancedCommand(family, args, deadline, authorized, callback)
                }
            },
            selectionSequence = { selectionStore.currentSequence() },
            pageState = { host.surfaceOrNull()?.let(pageStateTracker::state) },
            scheduleIdle = { snapshot -> scheduleIdle(snapshot) },
            onAiToolActivity = { uiHost?.onAiToolActivity() },
            activePageId = { pageManager.activePageId },
            visualSurfaceAvailable = { host.window().hasWindowFocus() },
            performNavigation = { action -> sessionNavigation(action) },
            pauseMutatingDispatch = { uiHost?.controlTransferPending == true },
            commitSameDocument = { view, before, after -> pageStateTracker.sameDocumentCommitted(view, before, after) },
        )
        captureCoordinator = coordinator
        executor = created
        return created
    }

    private fun sessionPageCommand(action: String, args: JSONObject): BrowserPageCommandResult {
        uiHost?.executePage(action, args)?.let { return it }
        return when (action) {
            "list" -> pageResult("OK", contextChanged = false)
            "new" -> {
                if (pageManager.size >= BrowserPageManager.MAX_PAGES) {
                    return BrowserPageCommandResult("PAGE_LIMIT", false, JSONObject())
                }
                val view = pageFactory.create()
                // Prepare the host reparent before creating callbacks or mutating the page manager.
                if (!host.setActiveSurface(view)) {
                    runCatching { view.destroy() }
                    return BrowserPageCommandResult("PAGE_SWITCH_BUSY", false, JSONObject())
                }
                val page = pageManager.add(view)
                if (page == null) {
                    runCatching { view.destroy() }
                    return BrowserPageCommandResult("PAGE_LIMIT", false, JSONObject())
                }
                val url = args.optString("url").takeIf { it.isNotBlank() }
                installSessionCallbacks(view)
                executor?.attachActionGate(view)
                pageStateTracker.begin(view, url ?: "about:blank")
                refreshActivePageIdentity(view)
                if (url != null) {
                    if (!loadUrl(url)) {
                        return BrowserPageCommandResult("LOAD_FAILED", false, JSONObject())
                    }
                } else {
                    runCatching { view.loadUrl("about:blank") }
                }
                pageResult("OK", contextChanged = true)
            }
            "switch" -> {
                val pageId = args.optString("pageId")
                val page = pageManager.all.firstOrNull { it.id == pageId }
                    ?: return BrowserPageCommandResult("PAGE_NOT_FOUND", false, JSONObject())
                val previous = host.surfaceOrNull()
                if (previous !== page.content &&
                    !BrowserPageTransactions.activate(pageManager, pageId) { host.setActiveSurface(it) }
                ) {
                    return BrowserPageCommandResult("PAGE_SWITCH_BUSY", false, JSONObject())
                }
                if (previous !== page.content) refreshActivePageIdentity(page.content)
                pageResult("OK", contextChanged = true)
            }
            "close" -> {
                val pageId = args.optString("pageId")
                if (pageManager.all.none { it.id == pageId } || pageManager.size <= 1) {
                    return BrowserPageCommandResult("PAGE_CLOSE_REJECTED", false, JSONObject())
                }
                val closed = BrowserPageTransactions.close(pageManager, pageId) { host.setActiveSurface(it) }
                    ?: return BrowserPageCommandResult("PAGE_SWITCH_BUSY", false, JSONObject())
                pageStateTracker.remove(closed.result.removed.content)
                dismissPendingDialogs(closed.result.removed.content, closed.result.removed.id)
                if (closed.wasActive) refreshActivePageIdentity(closed.result.active.content)
                runCatching { closed.result.removed.content.destroy() }
                pageResult("OK", contextChanged = true)
            }
            else -> BrowserPageCommandResult("INVALID_ARGUMENT", false, JSONObject())
        }
    }

    private fun pageResult(code: String, contextChanged: Boolean): BrowserPageCommandResult =
        BrowserPageCommandResult(code, true, JSONObject()
            .put("activePageId", pageManager.activePageId ?: JSONObject.NULL)
            .put("limit", BrowserPageManager.MAX_PAGES)
            .put("pages", org.json.JSONArray().apply {
                pageManager.all.forEach { page ->
                    put(JSONObject()
                        .put("pageId", page.id).put("url", safePageUrl(page.url)).put("title", page.title)
                        .put("active", page.id == pageManager.activePageId))
                }
            }), contextChanged)

    /** Strips query/fragment/userinfo, matching the CLI page-url schema and avoiding leaks. */
    private fun safePageUrl(raw: String): String {
        if (raw == "about:blank") return raw
        return runCatching {
            val uri = java.net.URI(raw)
            val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT) ?: return@runCatching "about:blank"
            val host = uri.host ?: return@runCatching "about:blank"
            if (scheme !in setOf("http", "https")) return@runCatching "about:blank"
            val authorityHost = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
            val defaultPort = (scheme == "http" && uri.port == 80) || (scheme == "https" && uri.port == 443)
            val port = if (uri.port >= 0 && !defaultPort) ":${uri.port}" else ""
            "$scheme://$authorityHost$port/"
        }.getOrDefault("about:blank").take(512)
    }

    /** Rebinds advanced/CDP document identity to the newly active page (same URL must not串页). */
    private fun refreshActivePageIdentity(view: WebView) {
        dismissPendingDialogs()
        val url = view.url.orEmpty()
        // A real target switch (new/switch/active-close): invalidate the old CDP target and update URL.
        advancedController?.onPageChanged(url)
        executor?.onDocumentStarted()
    }

    private fun sessionUploadCommand(
        request: BrowserCommandRequest,
        identity: BrowserTaskPageIdentity,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        when (request.args.optString("action")) {
            "request" -> requestBackgroundUpload(request, identity, authorized, callback)
            "list", "status" -> {
                val approvalId = request.args.optString("approvalId")
                val now = android.os.SystemClock.elapsedRealtime()
                val grant = uploadGrantStore.list(
                    approvalId, request.taskId.orEmpty(), identity, now,
                )
                if (grant == null) {
                    callback(uploadResult("UPLOAD_GRANT_NOT_FOUND", false))
                } else {
                    callback(uploadResult("OK", false, uploadGrantListData(
                        grant = grant,
                        nowMillis = now,
                        includeFiles = request.args.optString("action") == "list",
                    )))
                }
            }
            "cancel" -> {
                val approvalId = request.args.optString("approvalId")
                val grant = uploadGrantStore.cancel(
                    approvalId, request.taskId.orEmpty(), identity, android.os.SystemClock.elapsedRealtime(),
                )
                callback(if (grant?.state == BrowserUploadGrantState.CANCELLED) {
                    uploadResult("OK", true, JSONObject().put("approvalId", approvalId).put("state", "cancelled"))
                } else uploadResult("UPLOAD_GRANT_NOT_CANCELLABLE", false))
            }
            "activate", "submit" -> callback(uploadResult("UPLOAD_REQUIRES_VISIBLE_UI", false))
            else -> callback(uploadResult("INVALID_ARGUMENT", false))
        }
    }

    /** Prepares the upload grant off the main thread and informs the user without a foreground grab. */
    private fun requestBackgroundUpload(
        request: BrowserCommandRequest,
        identity: BrowserTaskPageIdentity,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        val taskId = request.taskId ?: run { callback(uploadResult("INVALID_ARGUMENT", false)); return }
        try {
            ioExecutor.execute {
                val sources = runCatching { inspectUploadSources(request.args) }
                mainHandler.post {
                    if (!authorized() || executor?.currentUploadPageIdentity() != identity) {
                        callback(uploadResult("SESSION_REVOKED", false))
                        return@post
                    }
                    val inspected = sources.getOrNull()
                    if (inspected == null) {
                        val code = sources.exceptionOrNull()?.message?.takeIf {
                            it in setOf("UPLOAD_SOURCE_DENIED", "UPLOAD_SIZE_LIMIT", "UPLOAD_ACCEPT_MISMATCH")
                        }
                            ?: "UPLOAD_SOURCE_DENIED"
                        callback(uploadResult(code, false))
                        return@post
                    }
                    val origin = uploadOrigin(host.surfaceOrNull()?.url) ?: run {
                        callback(uploadResult("UPLOAD_PAGE_UNAVAILABLE", false))
                        return@post
                    }
                    val types = request.args.optJSONArray("acceptTypes")?.let { array ->
                        (0 until array.length()).map { array.getString(it).lowercase(java.util.Locale.ROOT) }
                    }.orEmpty()
                    // One notice surface owns one approval at a time: reject a second concurrent
                    // pending grant instead of overwriting the sole notification and orphaning the
                    // first when it is later cancelled.
                    if (uploadGrantStore.activeWaitingApproval(android.os.SystemClock.elapsedRealtime()) != null) {
                        callback(uploadResult("UPLOAD_BUSY", false))
                        return@post
                    }
                    val grant = uploadGrantStore.create(
                        taskId = taskId,
                        identity = identity,
                        origin = origin,
                        summary = request.args.optString("summary"),
                        acceptTypes = types,
                        multiple = request.args.optBoolean("multiple", false),
                        sources = inspected,
                        nowMillis = android.os.SystemClock.elapsedRealtime(),
                    )
                    if (grant == null) {
                        callback(uploadResult("UPLOAD_BUSY", false))
                        return@post
                    }
                    if (!uploadApprovalNotice.show(grant)) {
                        // No notification surface means the user has no way to learn of the pending
                        // approval; fail closed instead of leaving an invisible grant.
                        uploadGrantStore.deny(
                            grant.id, BrowserUploadGrantState.CANCELLED,
                            android.os.SystemClock.elapsedRealtime(),
                        )
                        callback(uploadResult("UPLOAD_APPROVAL_UNAVAILABLE", false))
                        return@post
                    }
                    callback(uploadResult("OK", true, JSONObject()
                        .put("state", "awaiting_approval").put("approvalId", grant.id)
                        .put("pageId", identity.pageId).put("documentGeneration", identity.documentGeneration)
                        .put("fileCount", inspected.size).put("bytesTotal", inspected.sumOf { it.size })
                        .put("expiresInMs", BrowserUploadPolicy.GRANT_TTL_MILLIS)))
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            callback(uploadResult("UPLOAD_BUSY", false))
        }
    }

    private fun inspectUploadSources(args: JSONObject): List<BrowserUploadSource> {
        val workspace = File(appContext.filesDir.canonicalFile, "user-data")
        val paths = args.getJSONArray("paths")
        require(paths.length() in 1..BrowserUploadPolicy.MAX_FILES) { "INVALID_ARGUMENT" }
        val relativePaths = (0 until paths.length()).map { paths.getString(it) }
        require(relativePaths.distinct().size == relativePaths.size) { "INVALID_ARGUMENT" }
        val types = args.optJSONArray("acceptTypes")?.let { array ->
            (0 until array.length()).map { array.getString(it).lowercase(java.util.Locale.ROOT) }
        }.orEmpty()
        val sources = relativePaths.map { path ->
            val source = BrowserUploadPolicy.inspect(workspace, path) { }
                ?: error("UPLOAD_SOURCE_DENIED")
            require(BrowserUploadPolicy.accepts(source.mimeType, source.name, types)) { "UPLOAD_ACCEPT_MISMATCH" }
            source
        }
        require(sources.sumOf { it.size } <= BrowserUploadPolicy.MAX_TOTAL_BYTES) { "UPLOAD_SIZE_LIMIT" }
        return sources
    }

    private fun uploadOrigin(rawUrl: String?): String? = runCatching {
        val uri = java.net.URI(rawUrl ?: return null)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}".take(512)
    }.getOrNull()

    private fun sessionAdvancedCommand(
        family: String,
        args: JSONObject,
        deadlineElapsedMillis: Long,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        val controller = advancedController
        if (controller == null) {
            callback(failClosed("CLOSED"))
            return
        }
        controller.executeAi(family, args, deadlineElapsedMillis, authorized) { result ->
            if (result.optString("code") == "CLOSED" || result.has("executed")) callback(result)
            else callback(result)
        }
    }

    /** Session-owned selection/comment/task-feedback execution; never debug-gated. */
    private fun sessionSelectionCommand(args: JSONObject, authorized: () -> Boolean): JSONObject {
        fun only(vararg names: String): Boolean = args.keys().asSequence().all { it in names }
        fun fail(code: String) = JSONObject().put("code", code).put("executed", false).put("data", JSONObject())
        val now = android.os.SystemClock.elapsedRealtime()
        if (!authorized()) return fail("SESSION_REVOKED")
        val snapshot = host.lifecycleController.snapshot()
        if (snapshot.lifecycle != com.dshbox.browser.api.BrowserLifecycle.ACTIVE) return fail("CLOSED")
        if (!BrowserRuntimeSettings.preferences(appContext)
                .getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        ) return fail("AUTOMATION_DISABLED")
        val sessionGeneration = snapshot.sessionGeneration
        val action = args.optString("action")
        when (action) {
            "task_pending" -> {
                val cursor = args.opt("afterSequence")
                val destination = args.opt("destinationSessionKey")
                if (!only("action", "afterSequence", "destinationSessionKey") || cursor !is Int && cursor !is Long ||
                    args.optLong("afterSequence") < 0 || destination !is String
                ) return fail("INVALID_ARGUMENT")
                return taskFeedbackStore.pending(args.optLong("afterSequence"), sessionGeneration, destination)
            }
            "task_claim", "task_admit" -> {
                if (args.length() != 5 ||
                    !only("action", "feedbackId", "destinationSessionKey", "requestId", "payloadDigest")
                ) return fail("INVALID_ARGUMENT")
                return if (action == "task_claim") taskFeedbackStore.claim(args, sessionGeneration)
                else taskFeedbackStore.admit(args, sessionGeneration)
            }
            "task_receipt" -> {
                if (args.length() != 5 ||
                    !only("action", "feedbackId", "destinationSessionKey", "requestId", "deliveryCode")
                ) return fail("INVALID_ARGUMENT")
                return taskFeedbackStore.receipt(args, sessionGeneration)
            }
            "claim", "admit" -> {
                if (args.length() != 5 ||
                    !only("action", "selectionId", "destinationSessionKey", "requestId", "payloadDigest")
                ) return fail("INVALID_ARGUMENT")
                return if (action == "claim") selectionStore.claim(args, now) else selectionStore.admit(args, now)
            }
            "receipt" -> {
                if (args.optString("destinationSessionKey").isBlank() ||
                    !only("action", "selectionId", "destinationSessionKey", "deliveryCode", "requestId", "imageIncluded")
                ) return fail("INVALID_ARGUMENT")
                return selectionStore.recordReceipt(args, sessionGeneration, now)
            }
            "pending" -> {
                val cursor = args.opt("afterSequence")
                if (!only("action", "afterSequence", "destinationSessionKey") ||
                    (args.has("destinationSessionKey") && args.opt("destinationSessionKey") !is String) ||
                    (cursor != null && ((cursor !is Int && cursor !is Long) || args.optLong("afterSequence") < 0))
                ) return fail("INVALID_ARGUMENT")
                return selectionStore.pending(args.optLong("afterSequence", 0), sessionGeneration, now,
                    args.optString("destinationSessionKey"))
            }
            "list" -> {
                if (args.length() != 1 || !only("action")) return fail("INVALID_ARGUMENT")
                return selectionStore.list(now)
            }
        }
        if (args.length() != 1 || args.opt("action") !is String) return fail("INVALID_ARGUMENT")
        return selectionStore.execute(action, sessionGeneration, now)
    }

    /** Real background `browser_file` path: identical manager + request shape, no path bypass. */
    private fun sessionFileCommand(
        action: String,
        args: JSONObject,
        deadlineElapsedMillis: Long,
        authorized: () -> Boolean,
        callback: (JSONObject) -> Unit,
    ) {
        val url = args.optString("url")
        // Snapshot WebView state on the main thread before dispatching to the IO thread.
        val userAgent = host.surfaceOrNull()?.settings?.userAgentString
        try {
            ioExecutor.execute {
                // Re-check authorization and deadline on the IO thread right before touching storage.
                if (!authorized() || android.os.SystemClock.elapsedRealtime() >= deadlineElapsedMillis) {
                    callback(fileResult("SESSION_REVOKED", false))
                    return@execute
                }
                callback(runFileCommand(action, url, userAgent, args))
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            callback(fileResult("BUSY", false))
        }
    }

    private fun runFileCommand(action: String, url: String, userAgent: String?, args: JSONObject): JSONObject {
        val manager = BrowserDownloadsManager.getInstance(appContext)
        return runCatching {
            if (action == "list") {
                val page = manager.list(args.optInt("limit", 20), args.optString("beforeId").takeIf { it.isNotBlank() })
                fileResult("OK", false, JSONObject()
                    .put("items", org.json.JSONArray().apply { page.items.forEach { put(downloadJson(it)) } })
                    .put("nextCursor", page.nextCursor ?: JSONObject.NULL)
                    .put("truncated", page.truncated))
            } else {
                if (action !in setOf("start", "cancel", "status")) {
                    return@runCatching fileResult("UNSUPPORTED", false)
                }
                var cancelAccepted = false
                val item = when (action) {
                    "start" -> {
                        val cookie = runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }.getOrNull()
                        manager.start(BrowserDownloadRequest(
                            url = url,
                            suggestedFileName = args.optString("suggestedFileName").takeIf { it.isNotBlank() },
                            cookieHeader = cookie,
                            userAgent = userAgent,
                        ))
                    }
                    "cancel" -> {
                        cancelAccepted = manager.cancel(args.getString("id"))
                        manager.find(args.getString("id"))
                    }
                    else -> manager.find(args.getString("id"))
                }
                when {
                    item == null -> fileResult("NOT_FOUND", false)
                    action == "cancel" && !cancelAccepted ->
                        fileResult("NOT_CANCELLABLE", false, JSONObject().put("item", downloadJson(item)))
                    else -> fileResult(
                        if (action == "start" && item.state == BrowserDownloadState.FAILED) {
                            item.errorCode ?: "DOWNLOAD_FAILED"
                        } else "OK",
                        action == "start" && item.state != BrowserDownloadState.FAILED || action == "cancel",
                        JSONObject().put("item", downloadJson(item)),
                    )
                }
            }
        }.getOrElse { fileResult("DOWNLOAD_FAILED", false) }
    }

    private fun fileResult(code: String, executed: Boolean, data: JSONObject = JSONObject()) = JSONObject()
        .put("code", code).put("executed", executed).put("data", data)

    private fun downloadJson(item: BrowserDownloadItem): JSONObject = JSONObject()
        .put("id", item.id).put("state", item.state.name)
        .put("fileName", item.fileName ?: JSONObject.NULL)
        .put("bytesReceived", item.bytesReceived).put("totalBytes", item.totalBytes ?: JSONObject.NULL)
        .put("mimeType", item.mimeType ?: JSONObject.NULL).put("artifactPath", item.artifactPath ?: JSONObject.NULL)
        .put("errorCode", item.errorCode ?: JSONObject.NULL).put("updatedAtMillis", item.updatedAtMillis)

    private fun sessionNavigation(action: String): Boolean {
        val view = host.surfaceOrNull() ?: return false
        val expected = when (action) {
            "back" -> if (view.canGoBackOrForward(-1)) view.url else return false
            "forward" -> if (view.canGoBackOrForward(1)) view.url else return false
            "reload" -> view.url
            else -> return false
        }
        pageStateTracker.begin(view, expected, view.url)
        executor?.onDocumentStarted()
        when (action) {
            "back" -> view.goBackOrForward(-1)
            "forward" -> view.goBackOrForward(1)
            "reload" -> view.reload()
        }
        return true
    }

    private fun captureSession(deadlineElapsedMillis: Long, callback: (android.graphics.Bitmap?, String) -> Unit) {
        if (uiHost?.capture(deadlineElapsedMillis, callback) == true) return
        callback(null, "CAPTURE_REQUIRES_VISIBLE_UI")
    }

    private fun scheduleIdle(snapshot: BrowserSnapshot) {
        cancelIdle()
        if (snapshot.lifecycle != com.dshbox.browser.api.BrowserLifecycle.ACTIVE || snapshot.visible) return
        val deadline = com.dshbox.browser.api.BrowserIdlePolicy(snapshot.idleTimeoutMillis)
            .closeDeadline(snapshot) ?: return
        mainHandler.postDelayed(idleRunnable, (deadline - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L))
    }

    /**
     * A queued idle runnable re-checks the live snapshot instead of trusting the deadline it was
     * posted with, so a session that became visible or took new activity is not closed by stale
     * work; a genuinely due session closes with the IDLE_TIMEOUT reason.
     */
    private fun onIdleTick() {
        val snapshot = host.lifecycleController.expireIfNeeded()
        if (snapshot.lifecycle == com.dshbox.browser.api.BrowserLifecycle.CLOSING) {
            close(snapshot.closeReason)
        } else {
            scheduleIdle(snapshot)
        }
    }

    private fun cancelIdle() {
        mainHandler.removeCallbacks(idleRunnable)
    }

    private fun publishStatus(snapshot: BrowserSnapshot) {
        val automationEnabled = BrowserRuntimeSettings.preferences(appContext)
            .getBoolean(BrowserRuntimeSettings.KEY_AI_ENABLED, BrowserRuntimeSettings.DEFAULT_AI_ENABLED)
        // Keep the action gate and executor authorization mirror in step with the published status;
        // then publish identically to the mounted activity (mirror + prefs + ACTION_STATUS).
        actionGate.update(snapshot, automationEnabled)
        executor?.updateAuthorizationState(snapshot, automationEnabled)
        statusPublisher.publish(snapshot)
    }

    private fun uploadResult(code: String, executed: Boolean?, data: JSONObject = JSONObject()) = JSONObject()
        .put("code", code).put("executed", executed ?: JSONObject.NULL).put("data", data)

    private fun failClosed(code: String): JSONObject = JSONObject()
        .put("code", code).put("executed", false).put("data", JSONObject())

    private fun assertMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "BrowserSessionGateway must use the main thread" }
    }

    /** Returns false to abort a reparent while a capture still holds the WebView. */
    private fun prepareReparent(): Boolean {
        // Synchronously cancel and restore any active capture before the WebView is reparented.
        // No active capture returns true; a failed restore returns false so the caller aborts.
        return captureCoordinator?.cancelActiveForReparent() ?: true
    }
}

/** Process-level handle to the background session gateway, scoped to the :browser process. */
internal object BrowserSessionGatewayRegistry {
    @Volatile
    private var active: BrowserSessionGateway? = null

    fun install(gateway: BrowserSessionGateway) {
        active = gateway
    }

    fun current(): BrowserSessionGateway? = active

    fun clear(gateway: BrowserSessionGateway) {
        if (active === gateway) active = null
    }
}

/**
 * Shared upload grant list/status payload: all client-required fields plus TTL, and the approved
 * fileIds once the grant is ready. Kept pure so the wire shape is unit-testable.
 */
internal fun uploadGrantListData(
    grant: BrowserUploadGrant,
    nowMillis: Long,
    includeFiles: Boolean,
): JSONObject {
    val data = JSONObject()
        .put("approvalId", grant.id)
        .put("state", grant.state.wireName)
        .put("expiresInMs", (grant.expiresAtMillis - nowMillis).coerceAtLeast(0L))
    if (includeFiles &&
        grant.state in setOf(BrowserUploadGrantState.READY, BrowserUploadGrantState.CHOOSER_PENDING)
    ) {
        data.put("files", org.json.JSONArray().apply {
            grant.staged.forEach { file ->
                put(JSONObject()
                    .put("fileId", file.fileId).put("name", file.name)
                    .put("mimeType", file.mimeType).put("size", file.size))
            }
        })
    }
    return data
}
