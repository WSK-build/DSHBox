package com.dshbox.app.ui.webview

import android.app.Activity
import android.content.Context
import android.graphics.Rect
import android.net.Uri
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.dsh.DshRendererFactory
import com.dshbox.app.dsh.DshRendererReportStore
import com.dshbox.app.ui.theme.AppDshBehaviorState
import com.dshbox.dsh.renderer.api.*

/** Shared Android host; the selected build flavor supplies exactly one DSH engine. */
internal class DshWebContainer(
    context: Context,
    private val url: String,
    private val onProgress: (Int) -> Unit,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onError: (String) -> Unit,
    private val onRendererGone: () -> Unit = {},
    private val onFileChooserRequest: (DshWebContainer, Boolean) -> Unit,
    private val onWebPermissionRequest: (DshWebContainer, DshWebPermissionRequest) -> Unit,
    private val onDshboxScheme: (Uri) -> Unit,
    private val onExternalUrlRequested: (String) -> Unit,
    private val onFilePreparation: (DshWebContainer, DshAsyncRequestId, Long, Boolean) -> Unit = { _, _, _, _ -> },
    initialLaunchToken: String? = null,
) : FrameLayout(context), DshRendererHost {
    private var released = false
    private val tokens = DshTokenRevisionTracker(initialLaunchToken)
    private val launchToken: String? get() = tokens.token
    private var lastTarget: String? = null
    private var fileRequest: DshAsyncRequestId? = null
    private var permissionRequest: DshAsyncRequestId? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var rendererActive = false

    private val adapter = DshRendererFactory.adapter()
    val renderer: DshRenderer = adapter.create(
        context,
        DshRendererConfig(
            trustedBaseOrigin = url,
            internalSchemes = setOf("dshbox", "dsh-resource"),
            debuggingEnabled = BuildConfig.ENABLE_WEBVIEW_DEBUGGING,
            enterKeyNewline = AppDshBehaviorState.enterKeyNewline,
        ),
        this,
    )
    private val auth = DshNavigationAuthStateMachine(renderer.identity)
    val hasNavigation: Boolean get() = auth.snapshot.currentNavigationId != null

    init {
        DshRendererReportStore.begin(adapter.id, renderer.identity)
        renderer.view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(renderer.view)
        renderer.updateToken(launchToken, currentRevision())
        auth.onEvent(DshNavigationAuthEvent.TokenChanged(renderer.identity, currentRevision()))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyKeyboardHeight()
            insets
        }
    }

    private fun currentRevision(): Long? = tokens.revision

    fun onLaunchTokenChanged(token: String?) {
        if (released || !tokens.update(token)) return
        renderer.updateToken(launchToken, currentRevision())
        execute(auth.onEvent(DshNavigationAuthEvent.TokenChanged(renderer.identity, currentRevision())))
    }

    fun loadIfNeeded(target: String?, force: Boolean = false) {
        if (released || target == null || (!force && target == lastTarget)) return
        lastTarget = target
        val event = if (auth.snapshot.currentNavigationId == null) {
            DshNavigationAuthEvent.InitialLoadRequested(renderer.identity)
        } else {
            DshNavigationAuthEvent.ManualRetryRequested(renderer.identity)
        }
        execute(auth.onEvent(event))
    }

    fun reloadWithToken() {
        if (!released) execute(auth.onEvent(DshNavigationAuthEvent.ManualRetryRequested(renderer.identity)))
    }

    private fun execute(transition: DshNavigationAuthTransition) {
        for (effect in transition.effects) {
            if (released || effect !is DshNavigationAuthEffect.StartNavigation) continue
            if (effect.identity != renderer.identity || effect.tokenRevision != currentRevision()) continue
            val snapshot = auth.snapshot
            if (snapshot.currentNavigationId != effect.navigationId) continue
            // Both calls are synchronous on the UI thread; adapters defer callbacks until return.
            val result = renderer.load(DshRenderLoadRequest(
                url = dshUrlWithToken(url, launchToken), token = launchToken,
                tokenRevision = effect.tokenRevision, navigationId = effect.navigationId,
                cause = effect.cause, force = true,
            ))
            if (result == DshLoadDispatchResult.Accepted) {
                lastTarget = dshUrlWithToken(url, launchToken)
                auth.onEvent(DshNavigationAuthEvent.NavigationDispatchAccepted(
                    renderer.identity, effect.navigationId, effect.tokenRevision,
                ))
            } else {
                lastTarget = null
                DshRendererReportStore.failed(renderer.identity)
                val message = when (
                    (result as? DshLoadDispatchResult.Rejected)?.reason
                ) {
                    DshLoadRejectionReason.ENGINE_UNAVAILABLE ->
                        context.getString(R.string.dsh_renderer_engine_unavailable)
                    DshLoadRejectionReason.COMPATIBILITY_UNAVAILABLE ->
                        context.getString(R.string.webview_compatibility_unavailable)
                    else -> context.getString(R.string.webview_load_failed)
                }
                onError(message)
            }
        }
    }

    override fun onRendererEvent(renderer: DshRenderer, event: DshRendererEvent) {
        if (released || renderer !== this.renderer) return
        when (event) {
            is DshRendererEvent.FilePreparationProgress -> onFilePreparation(
                this, event.requestId, event.bytesPrepared, event.active,
            )
            is DshRendererEvent.CapabilitiesObserved -> DshRendererReportStore.observeCapabilities(
                renderer.identity, event.native, event.effective, event.userAgent,
            )
            is DshRendererEvent.WorkerCapabilitiesObserved ->
                DshRendererReportStore.observeWorkerCapabilities(
                    renderer.identity,
                    event.capabilities,
                    event.details,
                )
            is DshRendererEvent.FileDeliveryFailed -> Toast.makeText(
                context, R.string.webview_upload_failed, Toast.LENGTH_LONG,
            ).show()
            is DshRendererEvent.ProgressChanged -> onProgress(event.percent)
            is DshRendererEvent.PageStarted -> {
                DshRendererReportStore.pageStarted(renderer.identity)
                submitFileChooserResult(null)
                submitWebPermissionResult(false)
                onPageStarted()
            }
            is DshRendererEvent.PageFinished -> {
                DshRendererReportStore.pageStopped(renderer.identity)
                event.navigationId?.let {
                    auth.onEvent(DshNavigationAuthEvent.PageStopped(renderer.identity, it))
                }
                onPageFinished()
            }
            is DshRendererEvent.LoadFailed -> if (event.failure.isMainFrame) {
                DshRendererReportStore.failed(renderer.identity)
                onError(
                    if (event.failure.kind == DshRenderFailureKind.INITIALIZATION) {
                        context.getString(R.string.dsh_renderer_engine_unavailable)
                    } else {
                        event.failure.message.orEmpty()
                    },
                )
            }
            is DshRendererEvent.RendererGone -> {
                DshRendererReportStore.failed(renderer.identity)
                onRendererGone()
            }
            is DshRendererEvent.FileChooserRequested -> {
                fileRequest = event.request.requestId
                onFileChooserRequest(this, event.request.multiple)
            }
            is DshRendererEvent.WebPermissionRequested -> {
                permissionRequest = event.request.requestId
                onWebPermissionRequest(this, event.request)
            }
            is DshRendererEvent.InternalAction -> onDshboxScheme(event.uri)
            is DshRendererEvent.ExternalUrlRequested -> onExternalUrlRequested(event.url)
            is DshRendererEvent.MainDocumentStatus -> execute(auth.onEvent(
                DshNavigationAuthEvent.MainDocumentStatusObserved(event.attribution, event.httpStatus),
            ))
            is DshRendererEvent.SystemNativeHttpError -> {
                val navigationId = event.navigationId
                if (event.isMainFrame && navigationId != null) {
                    execute(auth.onEvent(DshNavigationAuthEvent.SystemMainFrameHttpError(
                        renderer.identity, navigationId, event.statusCode,
                    )))
                }
            }
            is DshRendererEvent.HistoryChanged -> Unit
        }
    }

    fun submitFileChooserResult(uris: Array<Uri>?) {
        submitSelectedFileChooserResult(uris?.map { DshSelectedFile(it) })
    }

    fun submitSelectedFileChooserResult(files: List<DshSelectedFile>?) {
        val request = fileRequest ?: return
        completeCapturedFile(request, files)
    }

    fun captureFileChooserOwner(): DshFileChooserOwner? =
        fileRequest?.let { DshFileChooserOwner(this, it) }

    fun capturePermissionOwner(): DshPermissionOwner? =
        permissionRequest?.let { DshPermissionOwner(this, it) }

    internal fun completeCapturedFile(request: DshAsyncRequestId, files: List<DshSelectedFile>?) {
        if (released || fileRequest != request) return
        fileRequest = null
        renderer.completeFileChooser(request, files)
    }

    fun submitWebPermissionResult(granted: Boolean) {
        val request = permissionRequest ?: return
        completeCapturedPermission(request, granted)
    }

    internal fun completeCapturedPermission(request: DshAsyncRequestId, granted: Boolean) {
        if (released || permissionRequest != request) return
        permissionRequest = null
        renderer.completeWebPermission(request,
            if (granted) setOf(DshWebPermission.AUDIO_CAPTURE) else emptySet())
    }

    fun applyAppTheme(canvasColor: Int) {
        if (!released) renderer.applyTheme(DshRendererTheme(canvasColor))
    }

    /**
     * 把回车键「换行」开关推送到已建立的本机容器（设置页拨动即时生效）：
     * 仅转发给渲染器的推送 API；注入配置不变，页面靠每次按键读取的
     * 可变全局接住新值。这是本容器为该项设置新增的唯一调用。
     */
    fun setEnterKeyNewline(newline: Boolean) {
        if (!released) renderer.setEnterKeyNewline(newline)
    }

    fun cancelFilePreparation(requestId: DshAsyncRequestId) {
        if (!released) renderer.cancelFileChooser(requestId)
    }

    fun release() {
        if (released) return
        released = true
        DshRendererReportStore.released(renderer.identity)
        detachLayoutListener()
        auth.onEvent(DshNavigationAuthEvent.SessionReleased(renderer.identity))
        tokens.clearForRelease()
        lastTarget = null
        renderer.release()
        fileRequest = null
        permissionRequest = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (released || layoutListener != null) return
        val lifecycleState = findViewTreeLifecycleOwner()?.lifecycle?.currentState
        if (lifecycleState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            setRendererActive(true)
        } else if (lifecycleState?.isAtLeast(Lifecycle.State.STARTED) == true) {
            renderer.onStart()
            rendererActive = true
        }
        layoutListener = ViewTreeObserver.OnGlobalLayoutListener { applyKeyboardHeight() }
            .also { (context as? Activity)?.window?.decorView?.viewTreeObserver?.addOnGlobalLayoutListener(it) }
    }

    /**
     * 保活页签从零尺寸放置恢复时补一次激活回调。
     *
     * keepAliveHidden 的 layout(0,0) 在部分设备上不会触发 detach/attach 对，
     * Gecko 会话因此停在 setActive(false)；对非激活会话 reload 无效，表现为
     * 白屏且刷新无反应。仅在实际处于停用态时生效，正常 attach 路径不受影响。
     */
    fun onAttachedToWindowActive() {
        if (released) return
        val lifecycleState = findViewTreeLifecycleOwner()?.lifecycle?.currentState
        if (lifecycleState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            if (rendererActive) renderer.onResume() else setRendererActive(true)
        }
    }

    override fun onDetachedFromWindow() {
        if (!released) {
            setRendererActive(false)
        }
        detachLayoutListener()
        super.onDetachedFromWindow()
    }

    private fun setRendererActive(active: Boolean) {
        if (released || rendererActive == active) return
        rendererActive = active
        if (active) {
            renderer.onStart()
            renderer.onResume()
            applyKeyboardHeight()
        } else {
            renderer.onPause()
            renderer.onStop()
        }
    }

    private fun detachLayoutListener() {
        val observer = (context as? Activity)?.window?.decorView?.viewTreeObserver
        layoutListener?.let { if (observer?.isAlive == true) observer.removeOnGlobalLayoutListener(it) }
        layoutListener = null
    }

    private fun applyKeyboardHeight() {
        if (released) return
        val decor = (context as? Activity)?.window?.decorView ?: return
        val visible = Rect()
        decor.getWindowVisibleDisplayFrame(visible)
        val keyboardHeight = if (decor.height - visible.bottom > decor.height / 4) {
            val location = IntArray(2)
            renderer.view.getLocationOnScreen(location)
            (visible.bottom - location[1]).coerceAtLeast(0)
        } else LayoutParams.MATCH_PARENT
        // 键盘真实占据视口时收缩视图高度，避免内容被键盘遮挡；
        // 阈值 1/4 屏高用于排除导航栏等小差值。
        val height = when (keyboardHeight) {
            LayoutParams.MATCH_PARENT -> LayoutParams.MATCH_PARENT
            else -> (keyboardHeight - 20).coerceAtLeast(0)
        }
        renderer.view.layoutParams?.let {
            if (it.height != height) { it.height = height; renderer.view.layoutParams = it }
        }
    }
}

/** Captured request identity remains stable while Android activities run asynchronously. */
internal class DshFileChooserOwner(
    private val container: DshWebContainer,
    private val request: DshAsyncRequestId,
) {
    fun submitFileChooserResult(uris: Array<Uri>?) =
        submitSelectedFileChooserResult(uris?.map { DshSelectedFile(it) })

    fun submitSelectedFileChooserResult(files: List<DshSelectedFile>?) =
        container.completeCapturedFile(request, files)
}

internal class DshPermissionOwner(
    private val container: DshWebContainer,
    private val request: DshAsyncRequestId,
) {
    fun submitWebPermissionResult(granted: Boolean) =
        container.completeCapturedPermission(request, granted)
}
