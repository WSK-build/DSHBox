package com.dshbox.remote.render

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.dshbox.dsh.renderer.api.DshAsyncRequestId
import com.dshbox.dsh.renderer.api.DshLoadCause
import com.dshbox.dsh.renderer.api.DshDownloadRequest
import com.dshbox.dsh.renderer.api.DshLoadDispatchResult
import com.dshbox.dsh.renderer.api.DshMainFrameNavigation
import com.dshbox.dsh.renderer.api.DshRenderLoadRequest
import com.dshbox.dsh.renderer.api.DshRenderFailureKind
import com.dshbox.dsh.renderer.api.DshRenderer
import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.dsh.renderer.api.DshRendererConfig
import com.dshbox.dsh.renderer.api.DshRendererEvent
import com.dshbox.dsh.renderer.api.DshRendererHost
import com.dshbox.dsh.renderer.api.DshRendererTheme
import com.dshbox.dsh.renderer.api.DshFileChooserRequest
import com.dshbox.dsh.renderer.api.DshSelectedFile
import com.dshbox.remote.R
import com.dshbox.remote.proxy.GateCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 远程会话渲染事件的宿主回调：[RemoteSessionSurface] 借此把渲染器事件映射
 * 到会话状态机。全部回调在主线程投递；默认实现均为空，按需覆写。
 */
interface SessionRendererCallbacks {

    /** 加载进度（0-100）。 */
    fun onProgress(percent: Int) {}

    /** 页面开始加载（状态机进入 Loading）。 */
    fun onPageStarted() {}

    /** 页面渲染完成（状态机回到 Ready）；加载未成功完结不会回调。 */
    fun onPageFinished() {}

    /**
     * 主框架加载失败且门禁自愈未果：统一转失败态（覆盖层可重试重连）。
     * [pairingRejected] 表示失败为一次性配对凭据被上游拒绝（覆盖层以专属
     * 标题与重配对引导呈现）。
     */
    fun onLoadFailed(reason: String, pairingRejected: Boolean = false) {}

    /** 渲染引擎进程崩溃且重建超限（转失败态提示重连）。 */
    fun onRendererBroken(reason: String) {}

    /**
     * 页面请求文件选择器：返回 true 表示宿主 UI 将应答此请求（经系统
     * OpenDocument 选择后回填）；默认 false，宿主立即以取消结清该请求。
     */
    fun onFileChooserRequested(request: DshFileChooserRequest): Boolean = false
}

/**
 * 远程会话渲染器宿主：承载一个远程端点会话的渲染引擎视图。
 *
 * 与本机 DSH 容器同构但不携带 DSH 专属逻辑：无启动 token、无鉴权状态机、
 * 会话形态按档案档位选择（代理与直连档都注册 `dshbox` 供页面外链桥使用，
 * 无 `open-settings-document` 等本机专属内部动作）——远程页面经回环代理
 * 呈现，会话门禁由 [SessionGateSequencer]
 * 守护（门禁下发完成后才放行加载，404/主框架失败先自愈一次）。
 *
 * 生命周期：attach/detach 时按视图树生命周期激活/停用渲染器；Activity 级
 * 前后台事件（ON_START/ON_STOP 等）经视图树 LifecycleOwner 观察者转发给
 * 引擎（引擎以这两步切换会话激活状态，只转发 PAUSE/RESUME 会停在未激活）；
 * 键盘高度复刻本机 DSH 容器的 decorView 可见区域推算。渲染引擎进程崩溃时
 * 就地重建（代理与登录态不动），连续超限转失败态。
 */
@SuppressLint("ViewConstructor")
class SessionRendererHost(
    context: Context,
    private val adapter: DshRendererAdapter,
    /** 本端点会话的回环 origin（渲染器唯一可信加载地址）。 */
    val loopbackOrigin: String,
    /** 会话门禁令牌；null = 无门禁（bypass）模式，加载直接放行。 */
    private val gateToken: String?,
    private val callbacks: SessionRendererCallbacks,
    /** 首次加载的完整地址；null = 加载回环 origin 根（fragment 携带配对 secret 的插件）。 */
    initialLoadUrl: String? = null,
    /** 代理转发的上游真实 origin（页内链接就地打开判定依据）；null = 一律外放浏览器。 */
    private val upstreamOrigin: String?,
    /** 端点是否持有未消费的一次性配对凭据（显示槽随清单状态刷新后经 update 推进）。 */
    var pairingCredentialPending: () -> Boolean = { false },

    /**
     * saya-ch 会话是否已兑换（vault 持有兑换产物、票已消费）：会话 8 小时
     * 过期后上游 401 时据此给出「会话已过期，请重新配对」的明确失败态
     * （未消费票的重配对引导在兑换后不再命中）。
     */
    var sayaSessionExchanged: () -> Boolean = { false },

    /** 首载地址是否携带未消费一次性票据（仅票据型首载参与首帧后降级）。 */
    private val initialLoadCarriesTicket: Boolean,
    /** 直连模式页面触发的下载：交给宿主按同一套下载目录落盘（代理模式走代理）。 */
    private val onDownloadRequest: ((DshDownloadRequest) -> Unit)? = null,

    /**
     * 导航型下载判定（直连模式）：跨源 `<a download>` 被引擎当普通导航时，
     * 命中此判定的 URL 取消导航并转 [onDownloadRequest]；null = 不启用。
     */
    private val downloadUrlPredicate: ((String) -> Boolean)? = null,

    /** 一次性凭据兑换完成（首个干净首帧）的消费提交回调。 */
    var onPairingConsumed: () -> Unit = {},

    /** 会话是否以一次性凭据启动（gateway 首载 / full-remote 注入 / generic 票据）。 */
    private val sessionUsesConsumableCredential: Boolean,

    /**
     * 直连会话模式（[com.dshbox.remote.session.DirectSession] 语义）：渲染器
     * 直接加载真实上游 origin——可信基准 origin 取上游值、主框架导航按
     * AnyHttps 放行（危险 scheme 由适配器取消）；代理承载会话保持既有
     * TrustedOriginOnly 口径。直连无门禁（gateToken null 即既有 bypass 路径）。
     * 直连携带 `dshbox` 内部 scheme 承接页面外链桥（页面的 window.open 拦截
     * 跳 `dshbox://open-external?url=…`，适配器把它转 InternalAction 事件，
     * 宿主取 url 参数按既有外链决策分流）；同上游站外跳与 http 外链仍会以
     * ExternalUrlRequested 到达（AnyHttps 只放行 https 主框架，http 与跨域
     * http(s) 沿用既有外放判定）。
     */
    private val directMode: Boolean,

    /**
     * 宿主附件来源面板选择器（结果三态约定见
     * [com.dshbox.remote.RemoteConnectionHost.pickUploadFiles]）：非 null 时
     * 页面的文件选择请求改走宿主面板，null（宿主未提供）时保持既有系统
     * OpenDocument 路径。结果分派判定收口在 [UploadRequestRouting]。
     */
    private val uploadPicker: (suspend (Boolean, List<String>) -> List<DshSelectedFile>?)? = null,

    /**
     * DSH 回车键「换行」档的读取口（默认恒 true，与渲染器配置默认一致）：
     * 渲染器装配期读现值写进注入配置；宿主（app 侧）读设置传入，使远程会话
     * 与本机 DSH 同开关同步。gecko 变体的注入配置在构建期生成，该值对其中性。
     */
    private val enterKeyNewlineProvider: () -> Boolean = { true },
) : FrameLayout(context), DshRendererHost {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 宿主面板通道的协程作用域：面板选择是长挂起操作，从渲染事件（主线程）
     * 切入；release 时统一取消，避免已释放渲染器收到迟到回填。
     */
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 门禁时序器：systemWebView 走 CookieManager 下发；无门禁会话直接放行。 */
    private val gateSequencer = SessionGateSequencer(
        cookieWriter = gateToken?.let { SystemWebViewGateCookieWriter() },
        scheduler = SessionGateSequencer.Scheduler { delayMillis, action ->
            mainHandler.postDelayed(action, delayMillis)
        },
    )

    private var released = false
    private var rendererActive = false
    private var initialLoadDispatched = false

    /**
     * 表面是否可见（由 [RemoteSessionSurface] 随组合同步）：隐藏表面（热池
     * 驻留）不发起系统级副作用——文件选择器不拉起系统选择器（请求就地取消
     * 结清）、外链不拉起系统浏览器；判定收口在 [shouldHandleRendererSideEffects]。
     */
    var visible: Boolean = true

    /** 当前加载目标：首帧成功后从带凭据地址降级为裸 origin（刷新/自愈/重建不再携带票据）。 */
    private var loadTarget: String = initialLoadUrl?.takeIf { it.isNotBlank() } ?: loopbackOrigin

    /**
     * 加载目标是否已被页内链接的就地打开接管：接管后首帧降级不再回退裸 origin。
     * 与降级条件里的 `loadTarget != loopbackOrigin` 在常规取值下冗余（就地目
     * 标总携带路径/query，异于裸 origin）——仅「同 host 根路径链接」会让就地
     * 目标与裸 origin 只差一个结尾斜杠。仍显式保留接管记录：降级判定不依赖
     * 字符串巧合，回环 origin 形态变化时不静默改变行为。
     */
    private var loadTargetExternallySet = false

    /** 本页导航是否出现过主框架失败（PageStarted 复位）：错误页也会发成功完结，不可作首帧依据。 */
    private var pageNavigationFailed = false

    /** 一次性凭据消费门控：首个干净首帧提交一次（前提 = 会话以一次性凭据启动）。 */
    private val consumptionGate = PairingConsumptionGate(usesConsumableCredential = sessionUsesConsumableCredential)

    /** 首载地址是否携带未消费一次性票据（刷新与崩溃重建回落的分流依据）：
     *  已消费的 generic 路径型首载与无首载（full-remote 注入式）均不参与降级
     *  ——刷新走引擎重载保留当前文档，重建按既有目标重载。 */
    private val rebuildPolicy = RendererRebuildPolicy()
    private var pendingFileSelection: PendingFileChooser? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var lifecycleObserver: LifecycleEventObserver? = null
    private var attachedLifecycleOwner: LifecycleOwner? = null
    private var canvasColor: Int = DEFAULT_CANVAS_COLOR

    /** 一次挂起中的文件选择请求（请求 id 与多选标记配对记账）。 */
    private data class PendingFileChooser(val requestId: DshAsyncRequestId, val multiple: Boolean)

    private fun createRenderer(): DshRenderer =
        runCatching {
            adapter.create(
                context,
                buildRendererConfig(
                    directMode = directMode,
                    upstreamOrigin = upstreamOrigin,
                    loopbackOrigin = loopbackOrigin,
                    canvasColor = canvasColor,
                    enterKeyNewline = enterKeyNewlineProvider(),
                    downloadUrlPredicate = downloadUrlPredicate,
                ),
                this,
            )
        }.getOrElse { failure ->
            // 构造期异常不再冒泡给 Compose 组合（视图属性初始化抛出会导致整
            // 页崩溃）：统一转失败态回调，由覆盖层呈现可重试的错误；日志只
            // 记异常摘要，不含 URL 与凭据。
            Log.w(TAG, "renderer construction failed: ${failure.javaClass.simpleName}")
            callbacks.onLoadFailed(failure.message?.takeIf { it.isNotBlank() } ?: failure.javaClass.simpleName)
            // 空壳视图由宿主 context 构造（context 为构造参数必然非空），不加
            // 子视图、不触发任何渲染初始化：兜底路径自身不再抛异常，每个宿主
            // 实例各持一份（共享单例会让第二个失败宿主的 addView 撞「child
            // already has a parent」）
            FailedRenderer(FrameLayout(context))
        }

    /**
     * 直连会话才安装下载监听：代理会话的下载由回环代理判拦截落盘，WebView
     * 侧再装会把回环门禁/404 当文件拉取，形成双重下载。
     */
    private fun installDownloadListener(target: DshRenderer) {
        if (!directMode) return
        target.setDownloadListener(onDownloadRequest)
    }

    private var renderer: DshRenderer = createRenderer()

    init {
        installDownloadListener(renderer)
        renderer.view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(renderer.view)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyKeyboardHeight()
            insets
        }
    }

    /**
     * 门禁下发完成后放行首次加载；无门禁模式直接加载。时序约束在
     * [SessionGateSequencer] 内守护（setCookie 完成回调之前不放行）。
     */
    private fun loadInitial() {
        if (released || initialLoadDispatched) return
        initialLoadDispatched = true
        val host = loopbackHost()
        gateSequencer.dispatchFirstLoad(host, gateToken) { loadOrigin(cause = DshLoadCause.INITIAL) }
    }

    /**
     * 用户刷新：无凭据会话恒走引擎内重载（保留当前文档与页内状态）；带凭据
     * 启动的会话——票据未消费时重放合理（引擎自持地址），已降级为裸 origin
     * 后必须显式重新发起加载（引擎重载会重放含消费过票据的初始地址）。
     */
    fun refresh() {
        if (released) return
        if (refreshUsesReload(initialLoadCarriesTicket, loadTarget, loopbackOrigin) && renderer.reload()) return
        loadOrigin(cause = DshLoadCause.MANUAL_RETRY)
    }

    /**
     * 释放渲染器输入焦点并收起软键盘（表面隐藏时调用，收口下沉到渲染层）；
     * 渲染器已释放则忽略。release 路径不经此处置（宿主 release 自行收尾）。
     */
    fun clearFocusAndHideKeyboard() {
        if (!released) renderer.clearFocusAndHideKeyboard()
    }

    /**
     * 同步渲染器视图可见性（与本机终端页同口径）：隐藏置 INVISIBLE 停绘制
     * （引擎会话保活，切回不重载页面），恢复回 VISIBLE。
     */
    fun setRendererViewVisible(value: Boolean) {
        if (released) return
        renderer.view.visibility = if (value) View.VISIBLE else View.INVISIBLE
    }

    fun applyAppTheme(canvasColor: Int) {
        if (released || this.canvasColor == canvasColor) return
        this.canvasColor = canvasColor
        renderer.applyTheme(DshRendererTheme(canvasColor))
    }

    /**
     * 把回车键「换行」开关推送到已建立的远程会话（开关实时生效）。
     * 渲染器侧经 evaluateJavascript 翻转页面可变全局 `__dshboxEnterNewline`，
     * 已建立的页面拨动即刻生效、不重载；默认实现（gecko 构建期配置）为 no-op，
     * 该变体保持既有行为。
     */
    fun setEnterKeyNewline(newline: Boolean) {
        if (released) return
        renderer.setEnterKeyNewline(newline)
    }

    fun release() {
        if (released) return
        released = true
        uploadScope.cancel()
        detachLifecycleObserver()
        detachLayoutListener()
        renderer.release()
        pendingFileSelection = null
    }

    private fun loadOrigin(cause: DshLoadCause) {
        if (released) return
        val result = renderer.load(
            DshRenderLoadRequest(url = loadTarget, cause = cause, force = true),
        )
        if (result is DshLoadDispatchResult.Rejected && !released) {
            callbacks.onLoadFailed(result.reason.name)
        }
    }

    /**
     * 页内外链处理：按 [resolveExternalNavigation] 的决策执行。同一上游 host
     * 的绝对地址改写为回环地址就地加载（同步接管加载目标，后续刷新/重建走
     * 它）；其它 http(s) 域交系统浏览器打开（失败以本地化提示呈现——宿主可能
     * 未装浏览器）；非 http(s)、解析失败、带内嵌凭据（user:pass@host）与超
     * 长地址一律忽略不打开（守卫判定收口在决策函数内，与本机浏览器外链口
     * 径一致）。
     */
    private fun handleExternalNavigation(url: String) {
        when (val decision = resolveExternalNavigation(url, upstreamOriginHost(upstreamOrigin), loopbackOrigin)) {
            is ExternalNavDecision.Internal -> {
                loadTarget = decision.targetUrl
                loadTargetExternallySet = true
                loadOrigin(cause = DshLoadCause.MANUAL_RETRY)
            }
            is ExternalNavDecision.ExternalBrowser -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(decision.url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }.onFailure {
                    Log.w(TAG, "external link open failed", it)
                    Toast.makeText(context, R.string.remote_link_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
            ExternalNavDecision.Ignore -> Unit
        }
    }

    /**
     * 页面打开请求的强制外放执行（宿主消息通道帧与 `dshbox://open-external`
     * 内部动作共用）：目标解析收口在 [resolveForcedExternalTarget]——一律交
     * 系统浏览器开新窗口，绝不就地加载（就地加载会卸载远程文档），也不比对
     * 目标与上游是否同站；代理会话的回环目标先还原真实上游 origin，没有上游
     * origin 无从还原，丢弃；被守卫拒绝的地址（非 http(s)、解析失败、嵌凭据
     * 与超长）静默丢弃。浏览器拉起失败以本地化提示呈现（宿主可能未装浏览器）。
     */
    private fun openExternalTargetForced(url: String) {
        val target = resolveForcedExternalTarget(url, loopbackOrigin, upstreamOrigin) ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Log.w(TAG, "external link open failed", it)
            Toast.makeText(context, R.string.remote_link_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * `dshbox://` 内部动作：只消费 `dshbox://open-external?url=…` 形态（页面
     * 外链桥），url 参数走与宿主消息通道同一强制外放路径（一律系统浏览器开
     * 新窗口、回环目标还原上游 origin、被守卫拒绝的地址丢弃）；其余内部动作
     * 形态与本模块无关，忽略（导航已被适配器取消，无副作用缺失）。
     */
    private fun handleInternalAction(uri: Uri) {
        if (!uri.scheme.equals(INTERNAL_SCHEME_DSHBOX, ignoreCase = true)) return
        // 本桥只消费 open-external 动作（页面外链）；非该 host 的内部动作
        // 形态与本模块无关，直接忽略（导航已被适配器取消，无副作用缺失）
        if (!uri.host.equals("open-external", ignoreCase = true)) return
        // 渲染层未把本 origin 纳入可信文档时会发不到这里；此处不做可信判定
        val requested = runCatching { uri.getQueryParameter("url") }.getOrNull() ?: return
        if (requested.isBlank()) return
        openExternalTargetForced(requested)
    }

    /** 门禁 cookie 的下发 host（回环 IP，覆盖该设备全部端点端口）；按 URI
     *  的 host 段解析（回环 origin 恒形如 `http://127.x.y.z[:port]`，不再依
     *  赖字符串前缀剥离——https 形态的 origin 也不会把 scheme 误当 host）。
     *  仅门禁会话调用（gateToken 恒 null 的 bypass 会话不进入下发路径）。 */
    private fun loopbackHost(): String =
        runCatching { java.net.URI(loopbackOrigin).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: loopbackOrigin.removePrefix("http://").removePrefix("https://").substringBefore(':')

    override fun onRendererEvent(renderer: DshRenderer, event: DshRendererEvent) {
        if (released || renderer !== this.renderer) return
        when (event) {
            is DshRendererEvent.ProgressChanged -> callbacks.onProgress(event.percent)
            is DshRendererEvent.PageStarted -> {
                pageNavigationFailed = false
                settlePendingFileSelection()
                callbacks.onPageStarted()
            }
            is DshRendererEvent.PageFinished -> {
                // 只有成功完结才算「页面已渲染」：失败终态不重置重建连续计数、
                // 不映射就绪态。gecko 引擎不产出失败终态（恒 success=true），
                // 其失败以 LoadFailed 呈现。
                if (event.success) {
                    // 回车键「换行」档在页面重载后回落到装配期初值（安装期
                    // document-start 注入只生效一次）：宿主在每次成功 PageFinished
                    // 后按当前档位重推一次（与本机 DSH 容器 onPageFinished 的同款
                    // 推送一致；evaluateJavascript 幂等写，开销可忽略）。
                    setEnterKeyNewline(enterKeyNewlineProvider())
                    // 首帧成功且本页导航无主框架失败才降级加载目标：system 引擎
                    // 对加载失败也发成功完结（渲染错误页），凭失败页降级会让
                    // 自愈重试丢掉尚未消费的票据；仅票据型首载参与降级——
                    // 已消费的 generic 路径型首载保持应用入口不变。加载目标已被
                    // 页内链接的就地打开接管时不回退（后续刷新/重建沿用该目标）
                    if (initialLoadCarriesTicket && !loadTargetExternallySet &&
                        !pageNavigationFailed && loadTarget != loopbackOrigin
                    ) {
                        loadTarget = loopbackOrigin
                    }
                    // 一次性凭据的消费提交：首个干净首帧（兑换完成的可靠信号）
                    if (consumptionGate.onPageFinished(event.success, pageNavigationFailed)) {
                        onPairingConsumed()
                    }
                    rebuildPolicy.onPageLoaded()
                    callbacks.onPageFinished()
                }
            }
            is DshRendererEvent.LoadFailed -> if (event.failure.isMainFrame) {
                pageNavigationFailed = true
                onMainDocumentFailure(
                    event.failure.message ?: event.failure.kind.name,
                    event.failure.httpStatus,
                )
            }
            is DshRendererEvent.RendererGone -> rebuildRenderer()
            is DshRendererEvent.FileChooserRequested -> {
                // 隐藏表面（热池驻留）不拉起系统选择器：请求以取消就地结清，
                // 页面 input 不被挂起
                if (!shouldHandleRendererSideEffects(visible)) {
                    Log.i(TAG, "file chooser suppressed while surface hidden")
                    renderer.completeFileChooser(event.request.requestId, null)
                    return
                }
                // 一次只挂起一个请求：新请求顶替旧请求时先以取消结清旧请求
                settlePendingFileSelection()
                pendingFileSelection =
                    PendingFileChooser(event.request.requestId, event.request.multiple)
                Log.i(TAG, "file chooser requested (multiple=${event.request.multiple})")
                val picker = uploadPicker
                if (picker != null) {
                    // 宿主附件来源面板：与本地 DSH 页同一套七来源通道。面板取消
                    // （空列表）以取消结清；宿主中途收回能力（null）退回系统路径。
                    uploadScope.launch {
                        val picked = runCatching {
                            picker(event.request.multiple, event.request.acceptMimeTypes)
                        }.onFailure { Log.w(TAG, "upload panel failed", it) }.getOrNull()
                        if (released) return@launch
                        when (UploadRequestRouting.outcomeOf(picked)) {
                            UploadPanelOutcome.FALLBACK_TO_SYSTEM_PICKER ->
                                dispatchSystemPicker(event.request)
                            UploadPanelOutcome.CANCEL ->
                                settleFileChooser(event.request.requestId)
                            UploadPanelOutcome.COMPLETE ->
                                completeFileChooser(event.request.requestId, picked.orEmpty())
                        }
                    }
                } else {
                    dispatchSystemPicker(event.request)
                }
            }
            is DshRendererEvent.WebPermissionRequested -> {
                // 远程页面的 Web 权限一律拒绝（本版无授权 UI）
                Log.i(TAG, "web permission denied (origin=${event.request.origin})")
                renderer.completeWebPermission(event.request.requestId, emptySet())
            }
            // 页内外链按 forced 分流：假 = 主框架导航产生，按既有决策分流
            // （适配器已取消该导航，跳转由宿主接管执行）；真 = 页面“打开新
            // 窗口”语义（宿主消息通道帧），一律强制外放：目标交系统浏览器开
            // 新窗口，不比对其与上游是否同站、绝不就地加载（就地加载会卸载远
            // 程文档；代理会话的回环目标先还原真实上游 origin）。隐藏表面不适
            // 合发起跳转，均不发起：不可见表面不得拉起系统浏览器
            is DshRendererEvent.ExternalUrlRequested ->
                if (shouldHandleRendererSideEffects(visible)) {
                    if (event.forced) openExternalTargetForced(event.url)
                    else handleExternalNavigation(event.url)
                }
            // 两种会话形态都注册了 `dshbox` 内部 scheme：页面外链桥（window.open
            // 拦截）经它递交目标地址，宿主取 url 参数与宿主消息通道同一强制外
            // 放路径分流（直连与代理；systemWebView 的消息通道优先，scheme 路
            // 径是通道未注册时的页面侧回退）。
            is DshRendererEvent.InternalAction ->
                if (shouldHandleRendererSideEffects(visible)) {
                    handleInternalAction(event.uri)
                }
            // 门禁自愈触发源：systemWebView 主框架 404 以 LoadFailed(HTTP) 呈现，
            // gecko 的主文档终态经 MainDocumentStatus 判定；401/403 是一次性
            // 凭据被拒的信号（重配对引导），同样进入自愈与失败收敛
            is DshRendererEvent.MainDocumentStatus -> if (event.httpStatus in listOf(404) || event.httpStatus in 401..403) {
                pageNavigationFailed = true
                onMainDocumentFailure("HTTP ${event.httpStatus}", event.httpStatus)
            }
            is DshRendererEvent.SystemNativeHttpError -> if (event.isMainFrame && event.statusCode in 401..403) {
                // system 引擎的主框架 401 走此事件（不经 LoadFailed）
                pageNavigationFailed = true
                onMainDocumentFailure("HTTP ${event.statusCode}", event.statusCode)
            }
            is DshRendererEvent.HistoryChanged -> Unit
            is DshRendererEvent.FilePreparationProgress -> Log.d(
                TAG,
                "file preparation progress (requestId=${event.requestId.value}, active=${event.active})",
            )
            is DshRendererEvent.FileDeliveryFailed -> Log.w(
                TAG,
                "file delivery failed (requestId=${event.requestId.value})",
            )
            is DshRendererEvent.CapabilitiesObserved -> Unit
            is DshRendererEvent.WorkerCapabilitiesObserved -> Unit
        }
    }

    /**
     * 门禁自愈：首次 404/主框架失败时重发门禁并延迟重载一次；自愈机会已
     * 用尽时按「连续失败」口径统一转失败态（覆盖层可重试，重走建立流程即
     * 等效重连）。带凭据的首次加载被上游 401/403 拒绝时，失败原因给出一
     * 次性凭据失效的重配对引导（精确过期时钟不判定，见设备卡重配对入口）。
     */
    private fun onMainDocumentFailure(reason: String, httpStatus: Int? = null) {
        if (released) return
        Log.i(TAG, "gate self-heal path entered")
        gateSequencer.onMainDocumentFailure(
            host = loopbackHost(),
            token = gateToken,
            retryLoad = {
                if (!released) loadOrigin(cause = DshLoadCause.MANUAL_RETRY)
            },
            onGiveUp = {
                // 一次性凭据被拒的判定收口在策略函数：首载带凭据 + 401/403 +
                // 端点在显示槽的未消费凭据集合内（三处失败事件源共用同一
                // 两条件判定：401/403 + 凭据未消费）。saya-ch 会话已兑换
                // （票已消费）后的 401 是会话过期——同样给出「重新配对」
                // 的明确失败态，不呈现含义不明的通用认证失败。
                val pairingRejected = PairingFailurePolicy.isPairingRejected(
                    httpStatus = httpStatus,
                    credentialPending = pairingCredentialPending(),
                ) || PairingFailurePolicy.isSayaSessionExpired(
                    httpStatus = httpStatus,
                    sessionExchanged = sayaSessionExchanged(),
                )
                if (pairingRejected) {
                    callbacks.onLoadFailed(
                        context.getString(R.string.remote_pairing_rejected),
                        pairingRejected = true,
                    )
                } else {
                    callbacks.onLoadFailed(reason)
                }
            },
        )
    }

    /**
     * 渲染引擎进程崩溃的就地重建：代理与登录态不动，重建渲染器并重新加载；
     * 连续超限转失败态提示重连。挂起的文件选择请求随旧渲染器一并结清。
     */
    private fun rebuildRenderer() {
        if (released) return
        if (!rebuildPolicy.onCrash()) {
            Log.w(TAG, "renderer rebuild limit reached, giving up")
            callbacks.onRendererBroken("Renderer process kept crashing")
            return
        }
        Log.w(TAG, "renderer process gone, rebuilding in place")
        settlePendingFileSelection()
        val old = renderer
        removeView(old.view)
        old.release()
        renderer = createRenderer()
        installDownloadListener(renderer)
        renderer.view.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        // 重建视图沿用当前表面可见性（隐藏表面的崩溃重建不得漏置 INVISIBLE）
        renderer.view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        addView(renderer.view)
        if (rendererActive) {
            renderer.onStart()
            renderer.onResume()
        }
        loadOrigin(cause = DshLoadCause.INITIAL)
    }

    /**
     * 回填文件选择结果（发起请求的请求 id 配对校验，晚到的结果安全作废）：
     * 由显示槽的系统选择器回调调用，主线程。
     */
    @MainThread
    fun completeFileChooser(requestId: DshAsyncRequestId, files: List<DshSelectedFile>?) {
        if (released || pendingFileSelection?.requestId != requestId) return
        pendingFileSelection = null
        renderer.completeFileChooser(requestId, files)
    }

    private fun settlePendingFileSelection() {
        val pending = pendingFileSelection ?: return
        pendingFileSelection = null
        Log.i(TAG, "file chooser cancelled")
        renderer.completeFileChooser(pending.requestId, null)
    }

    /**
     * 按请求 id 结清为取消（判定收口在 [UploadRequestRouting.shouldSettle]）：
     * 面板与系统选择器都是长挂起操作，期间新请求可能已顶替旧请求——id 不
     * 匹配（已顶替/已回填/无挂起）一律忽略，避免把新请求误结清（页面
     * `<input>` 被锁死）。
     */
    private fun settleFileChooser(requestId: DshAsyncRequestId) {
        if (!UploadRequestRouting.shouldSettle(pendingFileSelection?.requestId, requestId)) return
        pendingFileSelection = null
        Log.i(TAG, "file chooser cancelled")
        renderer.completeFileChooser(requestId, null)
    }

    /**
     * 既有系统选择器路径：宿主回调（显示槽在处发起 OpenDocument）认领该请求；
     * 无宿主接手时以取消结清。分派前先做请求 id 配对——面板通道退回系统路径
     * 的分派是迟到的，期间请求可能已被顶替或结清，此时不再外拉系统选择器。
     */
    private fun dispatchSystemPicker(request: DshFileChooserRequest) {
        if (!UploadRequestRouting.shouldSettle(pendingFileSelection?.requestId, request.requestId)) return
        if (!callbacks.onFileChooserRequested(request)) {
            settleFileChooser(request.requestId)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (released) return
        // 首次挂载后放行加载（factory 只建视图不导航；门禁时序由时序器守护）
        loadInitial()
        if (layoutListener != null) return
        val lifecycleOwner = findViewTreeLifecycleOwner()
        // 挂载期的 owner 引用保存下来：注销统一走该引用，不在 detach/release
        // 时重新 findViewTreeLifecycleOwner（视图摘除后查找已不可靠）。
        attachedLifecycleOwner = lifecycleOwner
        val lifecycleState = lifecycleOwner?.lifecycle?.currentState
        if (lifecycleState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            setRendererActive(true)
        } else if (lifecycleState?.isAtLeast(Lifecycle.State.STARTED) == true) {
            renderer.onStart()
            rendererActive = true
        }
        // Activity 级前后台事件转发：引擎（GeckoView）以 START/STOP 切换会话
        // 激活状态，窗口未 detach 的退后台也必须让引擎停摆。
        if (lifecycleObserver == null && lifecycleOwner != null) {
            val observer = LifecycleEventObserver { _, event ->
                if (released) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> renderer.onStart()
                    Lifecycle.Event.ON_RESUME -> renderer.onResume()
                    Lifecycle.Event.ON_PAUSE -> renderer.onPause()
                    Lifecycle.Event.ON_STOP -> renderer.onStop()
                    else -> Unit
                }
            }
            lifecycleObserver = observer
            lifecycleOwner.lifecycle.addObserver(observer)
        }
        layoutListener = ViewTreeObserver.OnGlobalLayoutListener { applyKeyboardHeight() }
            .also { (context as? Activity)?.window?.decorView?.viewTreeObserver?.addOnGlobalLayoutListener(it) }
    }

    /**
     * 零尺寸保活放置恢复时补一次激活回调：部分设备上 layout(0,0) 不会触发
     * detach/attach 对，引擎会话因此停在停用态，表现为空白页且刷新无反应。
     * 仅在实际处于停用态时生效，正常 attach 路径不受影响。
     */
    fun onAttachedToWindowActive() {
        if (released) return
        val lifecycleState = findViewTreeLifecycleOwner()?.lifecycle?.currentState
        if (lifecycleState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
            if (rendererActive) renderer.onResume() else setRendererActive(true)
        }
    }

    override fun onDetachedFromWindow() {
        detachLifecycleObserver()
        if (!released) {
            setRendererActive(false)
        }
        detachLayoutListener()
        super.onDetachedFromWindow()
    }

    /** 统一的生命周期观察者注销：使用挂载期保存的 owner 引用，幂等。 */
    private fun detachLifecycleObserver() {
        val observer = lifecycleObserver
        val owner = attachedLifecycleOwner
        if (observer != null && owner != null) {
            runCatching { owner.lifecycle.removeObserver(observer) }
        }
        lifecycleObserver = null
        attachedLifecycleOwner = null
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

    private companion object {
        const val TAG = "RemoteSession"

        const val DEFAULT_CANVAS_COLOR = android.graphics.Color.WHITE
    }
}

/**
 * 一次性配对凭据被拒的判定策略：失败状态为 401/403 且端点仍持有未消费
 * 凭据——两条件同时成立才以「重新配对」引导替代原始错误（未消费集合仅
 * 收录消费型解析器的一次性票端点；长期令牌端点与已消费票据的 401 均为
 * 普通上游认证失败，引导重配对会误导）。
 */
internal object PairingFailurePolicy {

    fun isPairingRejected(httpStatus: Int?, credentialPending: Boolean): Boolean =
        (httpStatus == 401 || httpStatus == 403) && credentialPending

    /**
     * saya-ch 会话过期判定：票已兑换（会话曾成功建立）后的 401 是会话到期
     * ——明确转入「重新配对」失败态，不做自动续期（已知限制）。
     */
    fun isSayaSessionExpired(httpStatus: Int?, sessionExchanged: Boolean): Boolean =
        (httpStatus == 401 || httpStatus == 403) && sessionExchanged
}

/**
 * 隐藏表面的渲染器副作用门控：表面不可见（热池驻留）时，文件选择器与外链
 * 等系统级副作用一律不发起（隐藏页面不应在用户眼前外拉系统界面）。UI 与
 * 系统回调链路无法在 JVM 覆盖，判定抽出为纯函数锁定口径。
 */
internal fun shouldHandleRendererSideEffects(visible: Boolean): Boolean = visible

/**
 * 渲染器配置组装（纯函数，JVM 契约测试锁定）：直连会话以真实上游 origin
 * 为可信基准并按 AnyHttps 放行主框架，同时注册 `dshbox` 内部 scheme 承接
 * 页面外链桥（文档级注入脚本把 window.open 外跳改写为
 * `dshbox://open-external?url=…`，宿主在 InternalAction 事件里分流）；代理
 * scheme。两档都声明启用宿主消息通道（systemWebView 适配器注册
 * `dshboxHost` 监听器，页面外链经 ExternalUrlRequested 事件（forced = true）
 * 强制外放）——
 * 远程会话必须启用；本机 DSH 容器不声明（默认关，页面行为零变化）。gecko
 * 变体不存在该通道注册路径（页面桥只注入本机 origin），配置声明对它是
 * 中性值。回车键「换行」开关两档都透传配置（宿主读设置传入，页面注入层
 * 按其决定裸 Enter 是否映射换行；gecko 构建期配置不携带该字段，页面保
 * 持发送档为已知限制）。
 */
internal fun buildRendererConfig(
    directMode: Boolean,
    upstreamOrigin: String?,
    loopbackOrigin: String,
    canvasColor: Int = android.graphics.Color.WHITE,
    enterKeyNewline: Boolean = true,
    downloadUrlPredicate: ((String) -> Boolean)? = null,
): DshRendererConfig = if (directMode) {
    DshRendererConfig(
        // 直连会话的可信基准即真实上游 origin（DirectSession 的 loopbackOrigin
        // 与 upstreamOrigin 同值，null 不会出现——工厂构造期已校验；兜底回
        // 环值保证配置仍可组装）
        trustedBaseOrigin = upstreamOrigin ?: loopbackOrigin,
        mainFrameNavigation = DshMainFrameNavigation.AnyHttps,
        internalSchemes = setOf(INTERNAL_SCHEME_DSHBOX),
        hostMessageChannelEnabled = true,
        enterKeyNewline = enterKeyNewline,
        downloadUrlPredicate = downloadUrlPredicate,
        theme = DshRendererTheme(canvasColor),
        debuggingEnabled = false,
    )
} else {
    DshRendererConfig(
        trustedBaseOrigin = loopbackOrigin,
        mainFrameNavigation = DshMainFrameNavigation.TrustedOriginOnly,
        // 代理会话同样注册 `dshbox` 内部 scheme：systemWebView 代理页的外链
        // 优先走宿主消息通道（dshboxHost）；通道未注册时页面侧回退到
        // `dshbox://open-external` scheme 路径——未注册 scheme 会让该导航在
        // 提交前无人取消，未知 scheme 直接加载导致文档卸载（远程会话连接
        // 失败的实证形态）。注册后该 scheme 只在可信文档内转 InternalAction
        // 事件，宿主的 open-external 校验与隐藏表面门控不受影响。
        internalSchemes = setOf(INTERNAL_SCHEME_DSHBOX),
        hostMessageChannelEnabled = true,
        enterKeyNewline = enterKeyNewline,
        theme = DshRendererTheme(canvasColor),
        debuggingEnabled = false,
    )
}

/** 直连页外链桥的内部 scheme（与宿主 InternalAction 分流的消费口径一致）。 */
internal const val INTERNAL_SCHEME_DSHBOX = "dshbox"

/**
 * 渲染器构造失败的空壳会话：每个宿主实例私有（不共享视图），空壳视图由
 * 宿主 context 构造，构造期不做任何渲染初始化，因此兜底路径自身不会抛
 * 异常。全部操作安全无副作用（release 幂等），不承载真实渲染能力——宿主
 * 已经以失败态收口，覆盖层呈现重试。
 */
internal class FailedRenderer(
    /** 宿主 context 构造的空壳视图（无子视图，MATCH_PARENT 布局安全）。 */
    override val view: View,
) : DshRenderer {
    // 失败空壳不承载真实引擎；标识按实例唯一（DshRenderer 契约要求 identity
    // 的实例引用每次实例唯一，多实例共存的记账键不得串档——属性初始化中的
    // this 为该实例自身）
    override val identity: com.dshbox.dsh.renderer.api.DshRenderIdentity =
        com.dshbox.dsh.renderer.api.DshRenderIdentity(
            instanceRef = com.dshbox.dsh.renderer.api.DshNativeRef.of(
                "failed-renderer-${System.identityHashCode(this)}",
            ),
            sessionRef = com.dshbox.dsh.renderer.api.DshNativeRef.of(
                "failed-renderer-${System.identityHashCode(this)}",
            ),
        )
    override val capabilities: com.dshbox.dsh.renderer.api.DshRendererCapabilities =
        com.dshbox.dsh.renderer.api.DshRendererCapabilities()

    override fun load(request: com.dshbox.dsh.renderer.api.DshRenderLoadRequest): com.dshbox.dsh.renderer.api.DshLoadDispatchResult =
        com.dshbox.dsh.renderer.api.DshLoadDispatchResult.Rejected(
            com.dshbox.dsh.renderer.api.DshLoadRejectionReason.RELEASED,
        )

    override fun updateToken(token: String?, tokenRevision: Long?) = Unit
    override fun reload(): Boolean = false
    override fun applyTheme(theme: DshRendererTheme) = Unit
    override fun canGoBack(): Boolean = false
    override fun goBack(): Boolean = false
    override fun clearFocusAndHideKeyboard() = Unit
    override fun onStart() = Unit
    override fun onResume() = Unit
    override fun onPause() = Unit
    override fun onStop() = Unit
    override fun completeFileChooser(
        requestId: com.dshbox.dsh.renderer.api.DshAsyncRequestId,
        files: List<com.dshbox.dsh.renderer.api.DshSelectedFile>?,
    ): Boolean = false
    override fun completeWebPermission(
        requestId: com.dshbox.dsh.renderer.api.DshAsyncRequestId,
        grantedPermissions: Set<com.dshbox.dsh.renderer.api.DshWebPermission>,
    ): Boolean = false
    override fun release() = Unit
}

/**
 * 刷新走引擎内重载的判定：首载不携带未消费票据的会话恒走引擎重载（保留
 * 当前文档与页内状态——含 full-remote 的注入式凭据会话与已消费的 generic
 * 路径型首载，引擎自持地址不含未消费票据）；票据型首载的会话在加载目标
 * 已降级为裸 origin 后必须显式重新发起加载——引擎重载按其记录的初始地址
 * （含一次性票据）重放，消费过的票回配对页只会失败。
 */
internal fun refreshUsesReload(
    initialLoadCarriesTicket: Boolean,
    loadTarget: String,
    loopbackOrigin: String,
): Boolean =
    !initialLoadCarriesTicket || loadTarget != loopbackOrigin

/**
 * systemWebView 变体的门禁 cookie 通道：进程级 CookieManager 按回环 host
 * 下发（host 级 cookie 覆盖该设备全部端点端口）。gecko 变体的 cookie 注入
 * 路径暂不可用，其会话为无门禁模式，不构造本通道。
 */
private class SystemWebViewGateCookieWriter : GateCookieWriter {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun write(host: String, value: String, onResult: (Boolean) -> Unit) {
        val cookie = "${GateCheck.COOKIE_NAME}=$value; Path=/; HttpOnly"
        CookieManager.getInstance().setCookie("http://$host/", cookie) { result ->
            mainHandler.post { onResult(result) }
        }
    }
}
