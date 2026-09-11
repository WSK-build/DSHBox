package com.dshbox.app.ui.webview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.zIndex
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.common.LogRedactor
import com.dshbox.app.sandbox.DshState
import java.io.File
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
 * 上传来源哨兵 MIME —— 网页端与原生之间的唯一可用通道。
 *
 * 本 WebView **没有** `addJavascriptInterface` 桥，JS 无法直接调用原生；
 * 但 `<input type="file">` 的 `accept` 属性会经 `FileChooserParams.getAcceptTypes()`
 * 原样送到 `onShowFileChooser`。因此约定：
 *  - accept 含本哨兵值 → 打开 app 内置的**沙箱文件**选择器；
 *  - 其它（含无 accept）→ 走系统 `ACTION_GET_CONTENT`（手机文件）。
 *
 * 必须与 `assets/plugins/dsh-mobile-adapt/plugin/lib/client.js` 的
 * `UPLOAD_SENTINEL` 保持一致。
 */
internal const val SANDBOX_UPLOAD_SENTINEL = "application/x-dshbox-sandbox-upload"

/** 附件上传来源。 */
internal enum class UploadSource { PHONE, SANDBOX }

/**
 * 一次 `onShowFileChooser` 请求的快照。
 *
 * `FileChooserParams` 只在回调栈内可靠，因此立即取出所需字段
 * （含系统选择器 Intent），回调结束后不再持有它。
 */
internal class FileChooserRequest(
    val source: UploadSource,
    val multiple: Boolean,
    val systemIntent: Intent?,
)

/**
 * 网页端 → 原生的自定义 scheme 通道（1.3.1 M6）。
 *
 * 本 WebView **没有 `addJavascriptInterface`**，插件无法直接调用原生；
 * 但主框架导航一定会经过 `shouldOverrideUrlLoading`，于是约定一个
 * 只在 app 内部流通的 scheme，由 `shouldOverrideUrlLoading` 拦截后处理。
 * 相比加 JS 桥：无需暴露任何 Java 对象、无需额外攻击面，且可精确白名单。
 *
 * 已知动作：
 *  - `dshbox://open-settings-document` —— 用内置查看器打开 DSH 配置文件
 *    （见 [SETTINGS_DOCUMENT_RELATIVE_PATH]）。
 */
internal const val DSHBOX_SCHEME = "dshbox"
internal const val DSHBOX_ACTION_SETTINGS_DOCUMENT = "open-settings-document"

/**
 * DSH 配置文件相对 `filesDir` 的位置。
 *
 * 依据（两条都是实测）：
 *  1. `DefaultSandboxManager.kt:965` 给 DSH 的 PRoot role 显式设了
 *     `DSH_HOME = /root/projects/.dsh`；
 *  2. `SandboxFiles.kt:16` PRoot 参数 `--bind=user-data:/root/projects`
 *     → guest `/root/projects` 就是宿主 `filesDir/user-data`。
 *
 * 而上游 `@deepseek-ai/dsh-settings-file` 的配置文档路径是
 * `<DSH_HOME>/settings.yaml`（源码：`resolve(config.path ??
 * join(resolveDshHome(config.dshHome), "settings.yaml"))`）。
 *
 * 因此物理路径 = `filesDir/user-data/.dsh/settings.yaml` —— 落在工作区内，
 * 既可被内置查看器直接读，也在 FileProvider 的 `user_data` 授权根之下。
 */
internal const val SETTINGS_DOCUMENT_RELATIVE_PATH = "user-data/.dsh/settings.yaml"

/**
 * 清除页面内**全部** `<input type="file">` 的 `accept` 属性（1.3.1 M27 的第二道防线）。
 *
 * 提到顶层常量是为了可单测：这段 JS 用 `querySelectorAll` 全量遍历而非只取第一个，
 * 且**必须** `try/catch` 包住（页面可能已开始跳转/销毁，`evaluateJavascript`
 * 抛错会打断调用方）。两处约定都由 [ClearUploadAcceptScriptTest] 断言。
 *
 * 用 `removeAttribute` 而不是 `accept = ''`：空串属性仍会被 `hasAttribute` 判为存在，
 * 某些实现据此走「有 accept 即按类型过滤」的分支，等于没清。
 */
internal const val CLEAR_UPLOAD_ACCEPT_JS: String =
    "(function(){try{" +
        "var els=document.querySelectorAll('input[type=\"file\"]');" +
        "for(var i=0;i<els.length;i++)els[i].removeAttribute('accept');" +
        "}catch(e){}})()"

/**
 * 解析 DSH 配置文件路径，必要时**按上游语义把它物化出来**。
 *
 * 上游 `openSettingsDocument()` 的第一步是 `settings.prepareDocument()`
 * （`dsh-api-settings-controller/lib/index.js:485`）：`mkdir -p` 后以 `"wx"`
 * 独占建一个**空文件**（file mode 0600 / dir 0700），然后才把路径交给外部编辑器。
 * 而插件在 `pointerdown` 阶段就拦掉了整个调用（改走 `dshbox://`），所以这一步
 * 在上游永远不会发生 —— 原生侧必须自己补齐，否则全新安装（或从未写过设置的设备）
 * 点「打开配置文件」只会得到「未找到配置文件」，功能看起来就是坏的。
 *
 * 已存在则**原样保留**（绝不覆盖用户配置，也不动权限位）。
 *
 * @return 可用于内置查看器的绝对路径；创建失败/路径不可用时返回 null。
 */
internal fun prepareSettingsDocument(filesDir: File): String? {
    val file = File(filesDir, SETTINGS_DOCUMENT_RELATIVE_PATH)
    if (!file.isFile) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText("")
            // 与上游对齐：上游 `prepareDocument()` 用 open(path, "wx", 0o600) 创建，
            // 而 Kotlin 的 writeText 权限受 umask 影响（通常 0644）。
            //
            // ⚠️ 收紧权限必须**先清后设**，两步都不能省：
            // `File.setReadable(true, ownerOnly=true)` 的语义是「**为属主开启**读」，
            // 它只会 OR 上 S_IRUSR，**不会**清除 group/other 位（JDK 的
            // UnixFileSystem.setPermission 就是 `mode |= S_IRUSR`）。
            // 因此单独调用它和 setWritable 对 0644 的文件**完全无效**——
            // 这一处曾被误写成"会自动拒绝 group/others"，注释与行为不符，
            // 由 CI 的 POSIX 断言抓出（Windows 跳过该断言，故本地未能发现）。
            // 先 (false, false) 清掉全部读/写位，再 (true, true) 只给属主，
            // 结果才是 0o600（可执行位自始至终未设置）。
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }
    return file.absolutePath.takeIf { file.isFile }
}

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
    /**
     * 网页端发起文件选择请求。首个参数是**发起请求的容器自身**：选择器是异步的，
     * 结果可能在该容器离开 Compose 树之后才回来，调用方需要知道「该回填给谁」，
     * 而不能依赖「当前活着的容器」这一共享状态。
     */
    private val onFileChooserRequest: (DshWebContainer, FileChooserRequest) -> Unit,
    private val onDshboxScheme: (Uri) -> Unit,
) : FrameLayout(context) {

    val webView: WebView = WebView(context)

    /**
     * 页面 `<input type="file">` 当前挂起的回调。
     * WebView 要求它「恰好被调用一次」（传 null 表示取消），否则该 input 会被
     * 永久锁死（后续点击不再弹选择器）——因此任何新请求到达前都必须先把旧的
     * 以 null 结清；容器销毁时同理。
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 把选择器结果回填给 WebView（[uris] 为 null 表示用户取消）。
     *
     * 包 `runCatching`：结果可能晚于容器销毁才回来，此时底层 WebView 已 destroy，
     * 回填本身可能抛异常——但那已经是收尾阶段，不该把异常抛回 ActivityResult 回调。
     *
     * 回填后**显式清理 `<input>` 的 accept 属性**（见 [clearUploadAccept]）。
     */
    fun submitFileChooserResult(uris: Array<Uri>?) {
        val callback = filePathCallback ?: return
        filePathCallback = null
        runCatching { callback.onReceiveValue(uris) }
        clearUploadAccept()
    }

    /**
     * 清掉网页端隐藏 `<input type="file">` 上的 accept 残留。
     *
     * ## 为什么需要它（1.3.1 复查）
     *
     * 上传来源靠 input 的 `accept` 哨兵值传给原生。网页端插件已在三条路径复位
     * （change / cancel / 捕获阶段 click 守卫），但**沙箱路径是例外**：
     * 沙箱选择器是 app 自绘的 Compose 对话框，不经过系统文件选择器，
     * `<input>` 的 value 从未改变 → **不触发 change**，哨兵值会一直留在元素上。
     *
     * 危害场景（插件侧守卫已能兜住，但那是"第二道防线"）：
     * 用户走 dsh 自有上传入口时，若守卫因 DOM 结构变化而失效，
     * 残留的哨兵会把系统选择器误判成沙箱上传。
     * 这里在**结果回填的确切时刻**主动复位，与插件侧守卫形成双保险。
     *
     * 实现说明：清除页面内**全部** `<input type="file">` 的 `accept` 属性
     * （`querySelectorAll` 全量遍历，而非只取第一个）。多 input 的页面上，
     * 任何一个残留都可能污染后续判定，全清最省心，也免去"该清哪一个"的取舍。
     *
     * 仍是**尽量而为**：这只是一次即时快照——此调用之后新插入的 input 不在覆盖范围内，
     * 且 `evaluateJavascript` 在页面跳转/销毁时可能不执行。插件侧的守卫才是主防线，
     * 这里是与它互补的第二道防线。
     */
    private fun clearUploadAccept() {
        runCatching {
            webView.evaluateJavascript(CLEAR_UPLOAD_ACCEPT_JS, null)
        }
    }

    /**
     * 以「取消」结清仍然挂起的回调。
     *
     * 容器销毁时调用：WebView 要求回调**恰好被调用一次**，遗留未结清会让该
     * `<input type="file">` 永久锁死（后续点击再也不弹选择器）。
     */
    fun releasePendingResult() {
        val callback = filePathCallback ?: return
        filePathCallback = null
        runCatching { callback.onReceiveValue(null) }
    }

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
            ): Boolean {
                // 1.3.1 M6：内部 scheme 通道（插件 → 原生）。命中即消费，
                //    绝不让 WebView 真的去加载它（否则会 ERR_UNKNOWN_URL_SCHEME）。
                val url = request.url ?: return false
                if (url.scheme.equals(DSHBOX_SCHEME, ignoreCase = true)) {
                    onDshboxScheme(url)
                    return true
                }
                return false
            }

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

            // DSH 重启换新 launchToken 后，旧 token 的首次访问返回 401
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

        // ── WebChromeClient：进度 + 文件选择 ───────────────
        // onShowFileChooser：WebView 默认**不会**处理 <input type="file">，必须由
        // 宿主 Activity 起选择器并把结果回填。DSH 网页端的图片/附件上传入口
        // 就是 <input type="file">，此前无此覆写 → 点击「上传」无任何反应。
        //
        // 1.3.1 M5：改为**按来源分流** —— 网页端「+」菜单通过给隐藏 input 打
        // accept 哨兵值指定来源，这里据此决定开「手机文件（系统选择器）」还是
        // 「沙箱文件（app 内置选择器）」。
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                onProgress(newProgress)
            }

            /**
             * 把网页端 console 的 warn/error 转发到 logcat。
             *
             * 排障必需：DSH 前端把「文件资源服务不可用」这类故障只写在浏览器 console，
             * 页面不抛异常、宿主进程无感知——没有这条转发，release 包上此类问题只能靠猜。
             * 仅记录 warn/error（info/log 噪音太大），内容走统一打码。
             */
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                msg ?: return false
                val level = msg.messageLevel()
                if (level == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                    level == android.webkit.ConsoleMessage.MessageLevel.WARNING
                ) {
                    Log.w(
                        "DshWebConsole",
                        "[$level] ${LogRedactor.redact(msg.message())} (${msg.sourceId()}:${msg.lineNumber()})",
                    )
                }
                return true
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?,
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                // 结清上一次未决回调，防 input 被锁死。
                this@DshWebContainer.filePathCallback?.onReceiveValue(null)
                this@DshWebContainer.filePathCallback = filePathCallback
                val accepts = fileChooserParams.acceptTypes ?: emptyArray()
                val wantsSandbox = accepts.any {
                    it.equals(SANDBOX_UPLOAD_SENTINEL, ignoreCase = true)
                }
                val multiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                return try {
                    if (wantsSandbox) {
                        // 沙箱侧由 Compose 弹内置选择器，结果经 submitFileChooserResult 回填。
                        onFileChooserRequest(
                            this@DshWebContainer,
                            FileChooserRequest(UploadSource.SANDBOX, multiple, null),
                        )
                    } else {
                        onFileChooserRequest(
                            this@DshWebContainer,
                            FileChooserRequest(
                                UploadSource.PHONE,
                                multiple,
                                fileChooserParams.createIntent(),
                            ),
                        )
                    }
                    true
                } catch (t: Throwable) {
                    // 无法起选择器（无可用 Activity 等）：立即以取消结清，返回 false
                    // 让 WebView 走默认失败路径，而不是把回调永久悬空。
                    this@DshWebContainer.filePathCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
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
 * 在 [base] 上追加 DSH launchToken 查询参数（`?token=<值>`）。
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

    // DSH 进程级 launchToken（从 `dsh web:` 原始输出解析）——
    // 首次加载携带它完成 token→签名 cookie 交换，此后 WebView 凭持久 cookie 访问。
    val dshLaunchToken by (context.applicationContext as com.dshbox.app.DshApp)
        .container.sandboxManager.dshLaunchToken.collectAsState()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var webContainer by remember { mutableStateOf<DshWebContainer?>(null) }
    var loadProgress by remember { mutableIntStateOf(0) }
    var pageError by remember { mutableStateOf<String?>(null) }

    // 系统文件选择器 —— DSH 网页端 <input type="file"> 的回填通道。
    // 用与 Activity 生命周期绑定的 ActivityResult 契约：结果经 ActivityResultRegistry
    // 投递，无需在 MainActivity 手写 onActivityResult，也不会因配置变更丢失注册。
    //
    // 发起请求的容器单独留引用（[chooserOwner]）：选择器是异步的，结果可能在
    // 容器已从 Compose 树摘除（乃至 `webContainer` 已被 ON_DESTROY 置空）之后才回来。
    // 只认 `webContainer` 会让这种晚到的结果被 `?.` 静默丢弃，发起请求的那个
    // `filePathCallback` 就此永远收不到值。因此结果一律投递给**发起者**。
    val chooserOwner = remember { mutableStateOf<DshWebContainer?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val owner = chooserOwner.value
        chooserOwner.value = null
        // parseResult 在用户取消时返回 null，恰好等价于「以取消结清回调」。
        val uris = FileChooserParams.parseResult(result.resultCode, result.data)
        // 优先投递给发起者；发起者已不可达时退回当前容器（同样是 null 才是真丢弃）。
        (owner ?: webContainer)?.submitFileChooserResult(uris)
    }

    // 沙箱文件选择请求（非 null = 内置选择器已打开）。
    var sandboxPick by remember { mutableStateOf<Boolean?>(null) }

    // 内置文件查看器当前打开的「逻辑路径」（非 null = 查看器已打开）。
    var viewerPath by remember { mutableStateOf<String?>(null) }

    // 内置查看器需要的路径映射/层判定根（与文件页同一套规则，见 SandboxFiles.kt）。
    val viewerMapper = remember {
        val base = File(context.filesDir, "runtime/runtime-current/base").takeIf { it.isDirectory }
            ?: File(context.filesDir, "runtime/runtime-current/debian")
        val workspace = File(context.filesDir, "user-data")
        com.dshbox.app.util.PathMapper(
            sandboxRoot = base,
            workspaceRoot = workspace,
            nodeLayer = File(context.filesDir, "runtime/runtime-current/node").takeIf { it.isDirectory },
            dshLayer = File(context.filesDir, "runtime/runtime-current/dsh").takeIf { it.isDirectory },
        )
    }
    val viewerLayerRoots = remember { com.dshbox.app.util.LayerRoots(viewerMapper) }

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
                    // 先结清挂起的选择器回调：WebView 要求它恰好被调用一次，
                    // 未结清会让该 <input type="file"> 永久锁死。
                    webContainer?.releasePendingResult()
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    (wv.parent as? ViewGroup)?.removeView(wv)
                    wv.destroy()
                    webView = null
                    webContainer = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // launchToken 就绪后带 token 加载（首次完成 cookie 交换；
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
                        onFileChooserRequest = { owner, request ->
                            // 记录发起者，供异步结果回填（见 chooserOwner 注释）。
                            chooserOwner.value = owner
                            when (request.source) {
                                UploadSource.PHONE -> {
                                    val intent = request.systemIntent
                                    if (intent != null) {
                                        fileChooserLauncher.launch(intent)
                                    } else {
                                        chooserOwner.value = null
                                        owner.submitFileChooserResult(null)
                                    }
                                }
                                UploadSource.SANDBOX -> sandboxPick = request.multiple
                            }
                        },
                        onDshboxScheme = { url ->
                            when (url.host?.lowercase()) {
                                DSHBOX_ACTION_SETTINGS_DOCUMENT -> {
                                    // 补齐上游 prepareDocument()（插件拦截后它不会执行）：
                                    // 文件缺失时按上游语义建一个空文件再打开。
                                    val path = prepareSettingsDocument(context.filesDir)
                                    if (path != null) {
                                        viewerPath = path
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.webview_settings_doc_missing),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                                else -> Unit
                            }
                        },
                    ).also { container ->
                        webView = container.webView
                        webContainer = container
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
                            contentDescription = stringResource(R.string.webview_page_controls),
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
                                contentDescription = stringResource(R.string.webview_refresh),
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
                                text = stringResource(R.string.webview_page_controls),
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
                                contentDescription = stringResource(R.string.files_close),
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
                            Text(stringResource(R.string.webview_refresh_page))
                        }
                    }
                }
            }

            // ── 沙箱文件选择器（上传来源 = 沙箱时由网页端触发）──
            // 与网页端「+」菜单的 accept 哨兵值配套：原生侧唯一需要新增的 UI。
            val pickMultiple = sandboxPick
            if (pickMultiple != null) {
                SandboxFilePickerDialog(
                    multiple = pickMultiple,
                    onDismiss = {
                        sandboxPick = null
                        // 以取消结清回调，否则 input 会被 WebView 永久锁死。
                        val owner = chooserOwner.value
                        chooserOwner.value = null
                        (owner ?: webContainer)?.submitFileChooserResult(null)
                    },
                    onConfirm = { uris ->
                        sandboxPick = null
                        val owner = chooserOwner.value
                        chooserOwner.value = null
                        (owner ?: webContainer)?.submitFileChooserResult(uris)
                    },
                )
            }

            // ── 内置文件查看器（网页端 dshbox:// 通道唤起）──────────
            // 典型用途：「打开配置文件」——DSH 原生走宿主的 OS 默认应用打开，
            // 而宿主跑在 PRoot 里（无 xdg-open / 无默认应用），必然失败并弹
            // 「无法打开配置文件」。这里改为用 app 自带查看器打开，
            // 且查看器内置 YAML 高亮（util/viewer/highlight），完全不依赖外部 app。
            val path = viewerPath
            if (path != null) {
                com.dshbox.app.ui.files.viewer.FileViewerScreen(
                    logicalPath = path,
                    mapper = viewerMapper,
                    layerRoots = viewerLayerRoots,
                    sandboxRunning = sandboxRunning,
                    onDismiss = { viewerPath = null },
                    onRequestRefresh = { /* 查看器关闭后无需刷新 WebView */ },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(50f),
                )
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
