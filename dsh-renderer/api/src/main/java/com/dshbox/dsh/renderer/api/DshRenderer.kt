package com.dshbox.dsh.renderer.api

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import java.io.File

/**
 * Engine-independent factory for an Android View based DSH renderer.
 *
 * The host selects an adapter on the main thread and calls [create] on the main
 * thread. Selection policy belongs to the host; this API only describes the
 * capabilities that can be used to choose between adapters.
 *
 * [create] must not start a navigation. The host calls [DshRenderer.load] once
 * the view is attached and the current navigation/token guard is known.
 */
interface DshRendererAdapter {
    /** Stable, non-localized adapter id, for example "system-webview". */
    val id: String

    /** Optional user-facing engine name. It is not used as an identity key. */
    val displayName: String

    val capabilities: DshRendererCapabilities

    /** Cheap availability probe. It must not create a renderer process. */
    fun isAvailable(context: Context): Boolean = true

    /**
     * Creates a stopped renderer session and its unattached Android View.
     *
     * The returned session is owned by the caller until [DshRenderer.release].
     * Implementations must keep all callbacks on the main thread and must not
     * invoke the host reentrantly from this call.
     */
    fun create(
        context: Context,
        config: DshRendererConfig,
        host: DshRendererHost,
    ): DshRenderer
}

/**
 * One active renderer instance.
 *
 * All members are main-thread operations unless explicitly documented
 * otherwise. After [release], every operation is a safe no-op and returns
 * `false`/a rejected dispatch where applicable. [view] must not be used after
 * release.
 */
/** 页面触发的下载请求（直连模式）：交给宿主按同一套下载目录/下载器落盘。 */
data class DshDownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
)

interface DshRenderer {
    /** The view added to the host hierarchy. Its ownership transfers to the host. */
    val view: View

    /**
     * Identity carried by asynchronous events and Auth attribution.
     *
     * The adapter creates both references. They are unique to this renderer
     * instance/session and must never be reused by a replacement renderer.
     */
    val identity: DshRenderIdentity

    val capabilities: DshRendererCapabilities

    /**
     * Validates the execution guard and dispatches a load.
     *
     * The adapter must reject a request whose non-null [DshRenderLoadRequest.tokenRevision]
     * does not match the session's current non-null revision. It must also reject
     * all loads after release. Validation and dispatch are synchronous, but the
     * adapter must not deliver page callbacks reentrantly from this method.
     *
     * A request with [DshRenderLoadRequest.force] set to `false` may be
     * de-duplicated by the adapter. [DshLoadDispatchResult.Accepted] means the
     * guard was accepted, not that the page has finished loading.
     *
     * The host emits `DshNavigationAuthEvent.NavigationDispatchAccepted` only
     * after this method returns [DshLoadDispatchResult.Accepted].
     */
    fun load(request: DshRenderLoadRequest): DshLoadDispatchResult

    /**
     * Updates the token used by future loads/reloads without navigating.
     *
     * A changed revision may re-arm an engine-specific one-shot 401 recovery.
     * The host remains responsible for the Auth state machine and must call
     * this with the latest token state.
     */
    fun updateToken(token: String?, tokenRevision: Long?)

    /**
     * Pushes the DSH Enter-key "new line" switch into a live page without a
     * reload.
     *
     * The page compatibility layer reads this switch from a mutable page
     * global on every key press; adapters that can reach the document update
     * that global via `evaluateJavascript`, so a settings change lands
     * immediately in established sessions. Default implementation is a
     * no-op: engines without an injected-page channel (gecko's build-time
     * config) keep their as-built behaviour.
     */
    fun setEnterKeyNewline(newline: Boolean) {}

    /**
     * Force-reloads the current session document with the latest token.
     *
     * This is the user-refresh path. It is not an Auth dispatch: a guarded
     * navigation must use [load] with a new navigation id. Returns `false` if
     * there is no document to reload or the renderer has been released.
     */
    /**
     * 安装下载监听（system 与 gecko 渲染器均实现：system 走 WebView
     * setDownloadListener，gecko 走 ContentDelegate.onExternalResponse）。
     * 远程会话直连模式下，页面触发的下载经此回调交给宿主落盘。
     */
    fun setDownloadListener(listener: ((DshDownloadRequest) -> Unit)?) {}

    fun reload(): Boolean

    /** Applies the host canvas theme. Repeated equal values are no-ops. */
    fun applyTheme(theme: DshRendererTheme)

    /** Computed at dispatch time; must be cheap and main-thread safe. */
    fun canGoBack(): Boolean

    /**
     * Navigates one history entry back.
     *
     * Returns `false` when no back entry exists or the renderer is released.
     * History availability must not be cached by the adapter across navigation.
     */
    fun goBack(): Boolean

    /**
     * Clears native input focus, blurs the page's active element, and hides the
     * software keyboard when the engine controls it.
     */
    fun clearFocusAndHideKeyboard()

    fun onStart()

    fun onResume()

    fun onPause()

    fun onStop()

    /**
     * Completes exactly one pending file chooser request.
     *
     * [files] is `null` or empty for cancellation. The host calls this on the
     * main thread after its own picker UI returns. The adapter must:
     *
     * - settle the engine callback exactly once, including when a request is
     *   superseded or the renderer is released;
     * - ignore stale request ids and return `false`;
     * - preserve selection order plus each [DshSelectedFile.displayName] and
     *   [DshSelectedFile.mimeType];
     * - hand a local `file:` URI directly to the engine without copying it;
     * - stage an external/content URI on an IO worker when required, normally
     *   with [DshFileStaging], and delete session-owned staged files in
     *   [release]. Never copy file contents on the main thread.
     */
    fun completeFileChooser(
        requestId: DshAsyncRequestId,
        files: List<DshSelectedFile>?,
    ): Boolean

    /** Cancels a chooser even while its external source is being prepared. */
    fun cancelFileChooser(requestId: DshAsyncRequestId): Boolean =
        completeFileChooser(requestId, null)

    /**
     * Completes exactly one pending web permission request.
     *
     * An empty [grantedPermissions] set denies the request. Only a subset of
     * [DshWebPermissionRequest.permissions] is meaningful. The adapter must
     * settle the engine request exactly once and ignore stale ids.
     */
    fun completeWebPermission(
        requestId: DshAsyncRequestId,
        grantedPermissions: Set<DshWebPermission>,
    ): Boolean

    /**
     * Final, idempotent teardown.
     *
     * Implementations must cancel pending file/permission requests, stop
     * loading, remove the view from its parent, destroy the engine view, and
     * delete session-owned staged upload files. No host event may be delivered
     * after release begins.
     */
    fun release()
}

/**
 * Receives engine events. The renderer argument is the exact instance that
 * produced the event, never a "currently active" renderer.
 */
interface DshRendererHost {
    /** Called on the main thread. Implementations must return quickly. */
    fun onRendererEvent(renderer: DshRenderer, event: DshRendererEvent)
}

data class DshRendererConfig(
    /**
     * Exact trusted loopback origin used for token-bearing loads, web
     * permissions, internal actions, and 401 recovery. It is configured by the
     * host and never inferred from an arbitrary first navigation.
     */
    val trustedBaseOrigin: String,
    /** Schemes consumed as internal actions instead of engine navigation. */
    val internalSchemes: Set<String> = emptySet(),
    val theme: DshRendererTheme = DshRendererTheme(canvasColor = Color.WHITE),
    val allowFileAccess: Boolean = true,
    val allowContentAccess: Boolean = true,
    /** `null` lets the adapter choose its default user agent. */
    val userAgent: String? = null,
    val debuggingEnabled: Boolean = false,
    /**
     * 主框架导航策略（默认 [DshMainFrameNavigation.TrustedOriginOnly]，即既有
     * 行为）：仅显式选择 [DshMainFrameNavigation.AnyHttps] 的直连会话才放宽
     * 主框架导航口径，既有调用方不传即走原逻辑。
     */
    val mainFrameNavigation: DshMainFrameNavigation = DshMainFrameNavigation.TrustedOriginOnly,

    /**
     * 页面外链宿主消息通道开关（默认 false）：仅声明启用时，systemWebView
     * 适配器才注册 `dshboxHost` 消息监听器并把页面递交的外链帧转成
     * [DshRendererEvent.ExternalUrlRequested]（forced = true）。默认关闭保持既有
     * 会话（本机 DSH 容器）零变化——注册会让同源 `_blank` 从「静默失败」变
     * 为可递交，属可观察行为变化，必须由远程会话显式选择。
     */
    val hostMessageChannelEnabled: Boolean = false,

    /**
     * **DSH 回车键 = 换行档**（默认 true）：systemWebView 适配器把该值写进
     * 页面注入配置，页面兼容层据此在捕获阶段把可编辑区（contenteditable /
     * textarea）中的裸 Enter 拦截并补发带 `shiftKey` 的 keydown——DSH 自身
     * 的 Shift+Enter 换行绑定随之生效；false（发送档）不安装任何拦截，保持
     * 页面既有发送行为。gecko 变体的注入配置在构建期生成、不携带本开关，
     * 生效行为固定为发送档——该变体暂不支持此开关（已知限制，DSH 升级后
     * 复核 DSH 页面键位绑定时一并评估）。
     */
    val enterKeyNewline: Boolean = true,

    /**
     * 导航型下载判定（默认 null = 不启用）：命中时渲染器取消该导航并把
     * URL 交给下载监听。跨源 `<a download>` 在引擎里是普通导航（download
     * 属性只对同源生效），大文件响应会让页面停留在加载态且不产生外部响应；
     * 直连会话用它把已批准的文件 CDN 收进宿主下载器。
     */
    val downloadUrlPredicate: ((String) -> Boolean)? = null,
)

data class DshRendererTheme(
    /** Opaque ARGB canvas color, primarily for loading/blank frames. */
    val canvasColor: Int,
)

enum class DshRendererFeature {
    FILE_CHOOSER,
    WEB_PERMISSIONS,
    CUSTOM_SCHEMES,
    /**
     * Only advertise this when the engine can supply a real native
     * request/document binding for the final main-document response. Synthetic
     * ids are not acceptable.
     */
    FINAL_MAIN_DOCUMENT_STATUS,
    RENDERER_PROCESS_GONE,
    HISTORY_BACK,
    LOCAL_FILE_ACCESS,
    CONTENT_URI_ACCESS,
}

data class DshRendererCapabilities(
    val features: Set<DshRendererFeature> = emptySet(),
) {
    fun supports(feature: DshRendererFeature): Boolean = feature in features
}

/**
 * One guarded top-level load.
 *
 * [token] is secret and must never be logged. [tokenRevision] is the
 * non-secret Auth execution guard. A null navigation id is allowed for
 * cookie-only/manual navigation that the Auth state machine did not originate.
 */
data class DshRenderLoadRequest(
    val url: String,
    val token: String? = null,
    val tokenRevision: Long? = null,
    val navigationId: Long? = null,
    val cause: DshLoadCause = DshLoadCause.INITIAL,
    val force: Boolean = false,
) {
    init {
        require(url.isNotBlank()) { "Renderer URL must not be blank" }
        require(tokenRevision == null || tokenRevision >= 0) {
            "Token revision must not be negative"
        }
        require(navigationId == null || navigationId > 0) {
            "Navigation id must be positive"
        }
    }

    /** Never include the URL or token: either may carry a secret in its query. */
    override fun toString(): String =
        "DshRenderLoadRequest(navigationId=$navigationId, cause=$cause, force=$force)"
}

sealed interface DshLoadDispatchResult {
    /** The guard passed and the engine accepted the dispatch. */
    data object Accepted : DshLoadDispatchResult

    data class Rejected(
        val reason: DshLoadRejectionReason,
    ) : DshLoadDispatchResult
}

enum class DshLoadRejectionReason {
    RELEASED,
    ENGINE_UNAVAILABLE,
    COMPATIBILITY_UNAVAILABLE,
    STALE_TOKEN_REVISION,
    INVALID_REQUEST,
}

/** Opaque id generated by an adapter for one asynchronous host interaction. */
data class DshAsyncRequestId(val value: Long) {
    init {
        require(value > 0) { "Async request id must be positive" }
    }
}

data class DshFileChooserRequest(
    val requestId: DshAsyncRequestId,
    val multiple: Boolean,
    val acceptMimeTypes: List<String> = emptyList(),
    val captureEnabled: Boolean = false,
)

/**
 * A host picker result. Metadata is retained so an adapter can give a staged
 * browser file the original name/MIME instead of its temporary `.part` name.
 */
data class DshSelectedFile(
    val uri: Uri,
    val displayName: String? = null,
    val mimeType: String? = null,
    /**
     * Original local file supplied directly by the host picker, if it has one.
     * Never derive this by probing an arbitrary content URI. System WebView uses
     * [uri]; engines that need a local path may prefer this field to avoid a copy.
     */
    val localFile: File? = null,
)

enum class DshWebPermission {
    AUDIO_CAPTURE,
    VIDEO_CAPTURE,
    GEOLOCATION,
    NOTIFICATIONS,
    CLIPBOARD_READ,
    PROTECTED_MEDIA_ID,
    MIDI_SYSEX,
    OTHER,
}

data class DshWebPermissionRequest(
    val requestId: DshAsyncRequestId,
    val origin: Uri?,
    val permissions: Set<DshWebPermission>,
) {
    init {
        require(permissions.isNotEmpty()) { "Permission request must not be empty" }
    }
}

sealed interface DshRendererEvent {
    /** Local preparation progress, never network upload or conversation completion. */
    data class FilePreparationProgress(
        val requestId: DshAsyncRequestId,
        val bytesPrepared: Long,
        val active: Boolean,
    ) : DshRendererEvent {
        init { require(bytesPrepared >= 0) }
    }

    /** Read-only capability observation in the actual trusted DSH document, not readiness. */
    data class CapabilitiesObserved(
        val native: Map<DshWebCapability, Boolean>?,
        val effective: Map<DshWebCapability, Boolean>,
        val userAgent: String?,
    ) : DshRendererEvent

    /** Asynchronous worker-global observations. Never page readiness. */
    data class WorkerCapabilitiesObserved(
        val capabilities: Map<DshWebCapability, Boolean>,
        val details: Map<String, Boolean>,
    ) : DshRendererEvent

    /** Upload preparation failed; the document itself remains usable. */
    data class FileDeliveryFailed(val requestId: DshAsyncRequestId) : DshRendererEvent

    data class ProgressChanged(
        val percent: Int,
    ) : DshRendererEvent {
        init {
            require(percent in 0..100) { "Progress must be in 0..100" }
        }
    }

    data class PageStarted(
        val url: String?,
        val navigationId: Long?,
    ) : DshRendererEvent

    /**
     * The main document finished or stopped. Auth maps this to
     * `DshNavigationAuthEvent.PageStopped` when appropriate.
     */
    data class PageFinished(
        val url: String?,
        val navigationId: Long?,
        val success: Boolean = true,
    ) : DshRendererEvent

    data class LoadFailed(
        val failure: DshRenderFailure,
    ) : DshRendererEvent

    /**
     * The engine process is unusable. The host should release this renderer and
     * create a replacement subject to its own rebuild limit.
     */
    data class RendererGone(
        val detail: String? = null,
    ) : DshRendererEvent

    /**
     * The host owns the picker UI. It must eventually call
     * [DshRenderer.completeFileChooser] for this id, or treat a newer request as
     * superseding it.
     */
    data class FileChooserRequested(
        val request: DshFileChooserRequest,
    ) : DshRendererEvent

    /**
     * The host owns the permission UI. It must eventually call
     * [DshRenderer.completeWebPermission] for this id.
     */
    data class WebPermissionRequested(
        val request: DshWebPermissionRequest,
    ) : DshRendererEvent

    /**
     * A configured internal scheme was consumed by the adapter. The engine did
     * not navigate to [uri].
     */
    data class InternalAction(
        val uri: Uri,
    ) : DshRendererEvent

    /**
     * The trusted DSH document requested an HTTP(S) destination outside its own
     * origin. The adapter must cancel that page navigation; the host owns opening
     * the destination in the platform browser.
     */
    data class ExternalUrlRequested(
        val url: String,
        /** 真 = 页面“打开新窗口”语义（宿主消息通道 / `dshbox://open-external`
         *  递交）：一律强制外放、不得就地加载；假 = 主框架导航产生，宿主可
         *  自行决定是否接管。 */
        val forced: Boolean = false,
    ) : DshRendererEvent

    /**
     * Raw System WebView HTTP error.
     *
     * This carries no request/document binding and must not be converted into
     * `DshNativeDocumentAttribution`. The host may use it to drive Auth policy,
     * but must keep the missing native identity explicit. [url] must be stripped
     * of query and fragment before delivery.
     *
     * System WebView cannot distinguish a late callback for a reused URL from a
     * callback for the current request. Consumers must therefore keep the
     * navigation id guard and treat same-URL attribution as advisory.
     */
    data class SystemNativeHttpError(
        val url: String?,
        val statusCode: Int,
        val isMainFrame: Boolean,
        val navigationId: Long?,
    ) : DshRendererEvent {
        init {
            require(statusCode in 100..599) { "HTTP status must be in 100..599" }
        }
    }

    /**
     * Final top-level main-document status, never an intermediate 1xx/3xx
     * response or a subresource response.
     *
     * The adapter supplies [attribution] with `adapterVerified = true` after
     * checking session ownership, navigation id, top-level frame, and trusted
     * origin. The host feeds this into `DshNavigationAuthEvent.MainDocumentStatusObserved`.
     */
    data class MainDocumentStatus(
        val attribution: DshNativeDocumentAttribution,
        val httpStatus: Int,
    ) : DshRendererEvent {
        init {
            require(httpStatus in 100..599) { "HTTP status must be final" }
            require(attribution.adapterVerified) { "Attribution must be adapter-verified" }
        }
    }

    data class HistoryChanged(
        val canGoBack: Boolean,
    ) : DshRendererEvent
}

enum class DshWebCapability {
    PROMISE_WITH_RESOLVERS, PROMISE_TRY, ABORT_SIGNAL_ANY,
    ARRAY_TO_SORTED, ARRAY_FIND_LAST, ARRAY_TO_REVERSED,
    STRUCTURED_CLONE, URL_PARSE, URL_CAN_PARSE, REGEXP_ESCAPE,
    MAP_GET_OR_INSERT, MAP_GET_OR_INSERT_COMPUTED,
    RESIZE_OBSERVER, WORKER, READABLE_STREAM, WEBSOCKET,
    WORKER_CLASSIC, WORKER_MODULE, WORKER_TRANSFERABLE_STREAM,
    WORKER_FETCH_STREAM_BODY, PDF_WORKER_API,
    CSS_HAS, CSS_COLOR_MIX, CSS_DVH, CSS_SUBGRID, CSS_LIGHT_DARK,
}

enum class DshRenderFailureKind {
    INITIALIZATION,
    NETWORK,
    HTTP,
    SSL,
    ENGINE,
    UNKNOWN,
}

data class DshRenderFailure(
    val message: String?,
    val kind: DshRenderFailureKind = DshRenderFailureKind.UNKNOWN,
    val isMainFrame: Boolean = true,
    val url: String? = null,
    val httpStatus: Int? = null,
    val navigationId: Long? = null,
)
