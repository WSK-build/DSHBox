package com.dshbox.app.ui.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.sandbox.DshState
import kotlin.math.roundToInt

/**
 * 可插拔内嵌 WebView 容器 —— 移动模式 · 原生键盘处理版。
 *
 * 设计原则：
 *  - 专注移动模式：固定移动 UA，不做桌面/移动切换。
 *  - 零注入：不注入任何 JS/CSS、不篡改 viewport、不做任何页面级 hack。
 *  - 滚动修复（已确诊）：setOnTouchListener + requestDisallowInterceptTouchEvent(true)
 *    强制 Compose 父容器不拦截 WebView 触摸滚动。
 *  - 键盘自适应（原生层处理，自适应任何设备/平板）：
 *    把 WebView 放进纯原生 FrameLayout（DshWebContainer），用「屏幕坐标法」
 *    精确对齐：WebView 高度 = 键盘顶(屏幕y) − WebView 顶(屏幕y)。
 *    键盘顶用 decorView 的 getWindowVisibleDisplayFrame 实时测量（挂在
 *    decorView 的 OnGlobalLayoutListener，键盘弹/收必触发），不依赖 Scaffold
 *    innerPadding / Tab 栏 / 导航栏的任何假设（此前多版空白的根源正是
 *    在 content 区内压缩导致 WebView 底边比键盘顶高出「Tab 栏+手势条」）。
 *    同时消费 ime insets 防 Chromium 内建视口缩放二次压缩。
 *  - 缩放：仅 WebView 原生双指缩放（内核自带，不触碰页面）。
 *  - 悬浮双按键（屏幕左侧竖排）：调节器（面板）/ 刷新（转圈）。
 *  - 无服务端注入：不修改 DSH 源码，npx 更新后本容器继续可用。
 */

/** 移动 UA（固定） */
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

/**
 * 原生 WebView 容器：FrameLayout + WebView + 键盘自适应。
 *
 * 键盘处理完全在原生 View 层（确定性）：
 *  ① ViewCompat.setOnApplyWindowInsetsListener —— 实时读 ime insets，
 *     并消费 ime（防 Chromium M139+ 内建视口缩放造成二次压缩）；
 *  ② OnGlobalLayoutListener —— 直接量窗口可见区域差值兜底，
 *     兼容 insets 派发不完整/不标准的 ROM（vivo 等）。
 *
 * 两种机制都实时计算、零写死：换手机/平板/横竖屏都自适应。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class DshWebContainer(
    context: Context,
    url: String,
    private val onProgress: (Int) -> Unit,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onError: (String) -> Unit,
) : FrameLayout(context) {

    val webView: WebView = WebView(context)

    init {
        // ── WebView 基础配置 ──────────────────────────────
        webView.layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
        )
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.databaseEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.mixedContentMode =
            WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
        webView.settings.textZoom = 100
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.setSupportMultipleWindows(false)
        webView.settings.javaScriptCanOpenWindowsAutomatically = false

        // ── 移动 UA（固定）───────────────────────────────
        webView.settings.userAgentString = MOBILE_UA

        // ── 原生滚动 + 双指缩放（内核自己管）──────────────
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false

        // ── WebViewClient：内部消化跳转 ───────────────────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: Bitmap?,
            ) {
                onPageStarted()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onPageFinished()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onError(error?.description?.toString() ?: "")
                }
            }

            // 1.1.1 (T2)：DSH 重启换新 launchToken 后，旧 token 的首次访问返回 401
            // （WebView 显示 ERR_HTTP_RESPONSE_CODE_FAILURE）。此时签名 cookie 通常
            // 已生效（或即将种入）——自动刷新一次即可恢复，无需用户手动刷新。
            // 仅主框架 401 且尚未自动刷新过时触发，防死循环。
            private var autoRefreshedForAuth = false

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?,
            ) {
                if (!autoRefreshedForAuth &&
                    request?.isForMainFrame == true &&
                    errorResponse?.statusCode == 401
                ) {
                    autoRefreshedForAuth = true
                    view?.reload()
                }
            }
        }

        // ── 滚动修复（关键）：强制父容器不拦截触摸 ─────────
        webView.setOnTouchListener { v, event ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }

        // ── WebChromeClient：进度 ────────────────────────
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }
        }

        WebView.setWebContentsDebuggingEnabled(
            BuildConfig.ENABLE_WEBVIEW_DEBUGGING,
        )

        addView(webView)

        // ── 键盘自适应（原生层 · 屏幕坐标法）────────────────
        // 核心公式：WebView 高度 = 键盘顶(屏幕坐标) − WebView 顶(屏幕坐标)。
        // 直接量两个屏幕坐标相减，不依赖 Scaffold innerPadding / Tab 栏 /
        // 导航栏的任何假设 —— 任何设备、任何 ROM、任何导航模式都精确。
        //
        // ① OnGlobalLayoutListener（主力）：挂在 decorView 上 —— 键盘弹/收
        //    必触发窗口/视图全局布局；直接量窗口可见区域（不依赖 insets 派发）。
        val decor = (context as? Activity)?.window?.decorView
        decor?.viewTreeObserver?.addOnGlobalLayoutListener {
            applyKeyboardHeight()
        }

        // ② ime insets 监听（补充，部分 ROM insets 派发及时）：
        //    同时消费 ime insets，防 Chromium M139+ 内建视口缩放二次压缩。
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            if (ime > 0) applyKeyboardHeight()
            // 消费 ime：WebView 不再收到键盘 insets，不做内建 viewport resize
            WindowInsetsCompat.Builder(windowInsets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .build()
        }

        webView.loadUrl(url)
    }

    /**
     * 键盘弹出时把 WebView 底边精确对齐到键盘顶。
     * 公式：newHeight = 键盘顶(屏幕y) − WebView 顶(屏幕y)。
     */
    private fun applyKeyboardHeight() {
        val decor = (context as? Activity)?.window?.decorView ?: return
        val rect = Rect()
        decor.getWindowVisibleDisplayFrame(rect)
        val diff = decor.height - rect.bottom
        if (diff > decor.height / 4) {
            // 键盘出现：WebView 底边 = 键盘顶
            val loc = IntArray(2)
            webView.getLocationOnScreen(loc)
            val newHeight = (rect.bottom - loc[1]).coerceAtLeast(0)
            setWebViewHeight(newHeight)
        } else {
            // 键盘收起：恢复占满
            setWebViewHeight(LayoutParams.MATCH_PARENT)
        }
    }

    private fun setWebViewHeight(h: Int) {
        val lp = webView.layoutParams
        if (lp.height != h) {
            lp.height = h
            webView.layoutParams = lp
        }
    }
}

/**
 * 1.1.1 (M10)：在 [base] 上追加 DSH launchToken 查询参数（`?token=<值>`）。
 * 旧版 DSH / token 未就绪时原样返回；token 为 base64url 字符集（A-Za-z0-9_-），
 * 无需 URL 编码。
 */
private fun webUrlWithToken(base: String, token: String?): String {
    if (token == null || token.isEmpty() || base.contains("token=")) return base
    return base + (if (base.contains('?')) "&" else "?") + "token=" + token
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DshWebViewScreen(
    modifier: Modifier = Modifier,
    url: String,
    dshState: DshState,
    sandboxRunning: Boolean,
    isActiveTab: Boolean = true,
    onStartDsh: () -> Unit = {},
    onStartSandbox: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 1.1.1 (M10)：DSH 进程级 launchToken（从 `dsh web:` 原始输出解析）——
    // 首次加载携带它完成 token→签名 cookie 交换，此后 WebView 凭持久 cookie 访问。
    val dshLaunchToken by (context.applicationContext as com.dshbox.app.DshApp)
        .container.sandboxManager.dshLaunchToken.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }

    // ── 交互状态 ──────────────────────────────────────────
    var panelVisible by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // 悬浮按键列：屏幕左侧竖排（调节器 / 刷新）
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenH = with(density) { config.screenHeightDp.dp.toPx() }
    val btnColHeight = with(density) { (40.dp + 10.dp + 40.dp).toPx() }
    val btnColTop = screenH * 2f / 3f - btnColHeight / 2f

    // 返回键：面板优先关闭，其次 WebView 后退
    BackHandler(enabled = isActiveTab && panelVisible) {
        panelVisible = false
    }
    BackHandler(enabled = isActiveTab && !panelVisible) {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        }
    }

    // 生命周期绑定
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val wv = webView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wv.onPause()
                    wv.pauseTimers()
                }
                Lifecycle.Event.ON_RESUME -> {
                    wv.onResume()
                    wv.resumeTimers()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                    webView = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 1.1.1 (M10)：launchToken 就绪后带 token 加载（首次完成 cookie 交换；
        // DSH 重启 token 变化时再次触发，幂等）。401 认证页也会被覆盖为带 token 重载。
        LaunchedEffect(dshLaunchToken, dshState) {
            val token = dshLaunchToken
            if (token != null && dshState == DshState.READY) {
                val wv = webView
                if (wv != null) {
                    wv.loadUrl(webUrlWithToken(url, token))
                }
            }
        }

        Box(modifier = modifier.fillMaxSize()) {
        if (dshState != DshState.READY) {
            WaitingState(
                dshState = dshState,
                sandboxRunning = sandboxRunning,
                onStartDsh = onStartDsh,
                onStartSandbox = onStartSandbox,
            )
        } else {
            // ── 原生 WebView 容器（键盘处理在原生层，自适应）──
            AndroidView(
                factory = { ctx ->
                    DshWebContainer(
                        context = ctx,
                        url = webUrlWithToken(url, dshLaunchToken),
                        onProgress = { loadProgress = it },
                        onPageStarted = {
                            loadProgress = 0
                            pageError = null
                        },
                        onPageFinished = {
                            loadProgress = 100
                            isRefreshing = false
                        },
                        onError = {
                            pageError = it.ifEmpty { context.getString(R.string.webview_load_failed) }
                        },
                    ).also { container ->
                        webView = container.webView
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // 顶部加载进度条
            if (loadProgress in 1..99) {
                LinearProgressIndicator(
                    progress = { loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                )
            }

            // 页面加载失败覆盖层
            if (pageError != null) {
                ErrorOverlay(
                    message = pageError ?: "",
                    onRetry = {
                        pageError = null
                        webView?.reload()
                    },
                )
            }

            // ── 悬浮双按键（左侧竖排 · 40dp · 无阴影）──────────
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(0, btnColTop.roundToInt()) }
                    .padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 调节器 → 打开面板
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { panelVisible = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = "页面控制",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // 刷新 → 点击转圈
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            if (!isRefreshing) {
                                isRefreshing = true
                                webView?.reload()
                            }
                        },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                    shadowElevation = 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "刷新",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // ── 底部控制面板（移动模式说明 + 刷新）────────────
            AnimatedVisibility(
                visible = panelVisible,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                    shadowElevation = 8.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "页面控制",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { panelVisible = false },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "关闭",
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        // 面板内容：后续在此扩展新功能

                        Button(
                            onClick = {
                                isRefreshing = true
                                webView?.reload()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("刷新页面")
                        }
                    }
                }
            }
        }
    }
}

/** DSH 未就绪时的等待状态 */
@Composable
private fun WaitingState(
    dshState: DshState,
    sandboxRunning: Boolean,
    onStartDsh: () -> Unit,
    onStartSandbox: () -> Unit,
) {
    val sandboxOffline = !sandboxRunning
    val dshError = dshState == DshState.ERROR
    val dshStarting = dshState == DshState.STARTING

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (sandboxOffline || dshError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                        CircleShape,
                    ),
            )
            Text(
                text = stringResource(
                    when {
                        sandboxOffline -> R.string.webview_sandbox_offline
                        dshError -> R.string.webview_dsh_error
                        dshStarting -> R.string.webview_waiting_dsh
                        else -> R.string.webview_dsh_stopped
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    when {
                        sandboxOffline -> R.string.webview_sandbox_offline_hint
                        dshStarting -> R.string.webview_waiting_hint
                        else -> R.string.webview_dsh_stopped_hint
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                sandboxOffline -> Button(onClick = onStartSandbox) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.webview_start_sandbox))
                }
                dshError -> Button(onClick = onStartDsh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.webview_restart_dsh))
                }
                !dshStarting -> Button(onClick = onStartDsh) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.webview_start_dsh))
                }
            }
        }
    }
}

/** 页面加载失败覆盖层 */
@Composable
private fun ErrorOverlay(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.webview_load_failed_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.webview_retry))
            }
        }
    }
}
