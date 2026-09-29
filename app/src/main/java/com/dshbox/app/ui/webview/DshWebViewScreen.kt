package com.dshbox.app.ui.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.PermissionRequest
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Close
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Refresh
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.common.LogRedactor
import com.dshbox.app.sandbox.DshState
import java.io.File
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

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
 *    innerPadding / Tab 栏 / 导航栏的任何假设（在 content 区内压缩会让 WebView
 *    底边比键盘顶高出「Tab 栏+手势条」，页面表现为空白）。
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

/**
 * 「拍照 / 录像」的 accept 哨兵。
 *
 * 上游**没有**相机入口（其代码里不存在 capture 属性、accept 类型限定或图片 MIME），
 * 这两个值由移动端适配插件打在隐藏 `<input type="file">` 上；原生
 * `onShowFileChooser` 见到它们就改用系统相机意图取图/录像，而**不是**走文件选择器。
 * 拍摄结果由原生按输出 URI 回填给同一个 input，对页面而言仍是一次普通上传。
 *
 * 必须与 `assets/plugins/dsh-mobile-adapt/plugin/lib/client.js` 的
 * `CAMERA_PHOTO_SENTINEL` / `CAMERA_VIDEO_SENTINEL` 保持一致。
 */
internal const val CAMERA_PHOTO_SENTINEL = "application/x-dshbox-camera-photo"
internal const val CAMERA_VIDEO_SENTINEL = "application/x-dshbox-camera-video"

/**
 * 相机拍摄输出的暂存目录名（`cacheDir` 之下）。
 *
 * 必须与 `res/xml/file_paths.xml` 的 `camera_capture` 授权根 `path` 一致 ——
 * 不一致时 `FileProvider.getUriForFile` 会直接抛异常，拍摄入口静默不可用。
 * 放 `cacheDir` 而非 `filesDir`：拍完即被上传，属于可回收的中间产物。
 */
internal const val CAPTURE_DIR_NAME = "camera-capture"

/** 附件上传来源。 */
internal enum class UploadSource { PHONE, SANDBOX, CAMERA_PHOTO, CAMERA_VIDEO }

/**
 * 一次 `onShowFileChooser` 请求的快照。
 *
 * `FileChooserParams` 只在回调栈内可靠，因此立即取出所需字段
 * （含系统选择器 Intent），回调结束后不再持有它。
 *
 * [captureOutputUri] 仅相机来源非空：拍摄结果的落点，取消拍摄时该文件会被清掉。
 */
internal class FileChooserRequest(
    val source: UploadSource,
    val multiple: Boolean,
    val systemIntent: Intent?,
    val captureOutputUri: Uri? = null,
)

/**
 * 网页端 → 原生的自定义 scheme 通道。
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
 * 依据：
 *  1. `DefaultSandboxManager` 给 DSH 的 PRoot role 显式设了
 *     `DSH_HOME = /root/projects/.dsh`；
 *  2. `SandboxFiles` 的 PRoot 参数 `--bind=user-data:/root/projects`
 *     → guest `/root/projects` 就是宿主 `filesDir/user-data`。
 *
 * 而**配置文档 = 当前 profile 的 patch 文件**：上游
 * `<DSH_HOME>/profiles/<profile>/cordis.patch.yml`
 * （源码：`documentPath = profileContext.patchPath`，见 `dsh-config-editor`）。
 *
 * 早期版本这里指向 `<DSH_HOME>/settings.yaml`：那个文件已随上游设置改造废弃
 * （启动时被改名为 `settings.yaml.imported`，内容一次性导入 profile patch），
 * 继续打开它只会看到我们刚建出来的空文件。profile 名取
 * [Constants.DSH_WEB_PROFILE]——与启动参数同一个来源，不各写一份字面量。
 *
 * 物理路径落在工作区内：既可被内置查看器直接读，也在 FileProvider 的
 * `user_data` 授权根之下。
 */
internal const val SETTINGS_DOCUMENT_RELATIVE_PATH =
    "user-data/.dsh/profiles/${Constants.DSH_WEB_PROFILE}/cordis.patch.yml"

/**
 * profile patch 文件缺失时写入的初始正文。
 *
 * **逐字对齐上游**（`dsh-app-boot` 的 `PROFILE_PATCH_TEMPLATE`）：上游在 profile
 * 首次初始化时写的就是这段「注释头 + 空层 `[]`」，我们只是补齐插件拦截后不会
 * 再执行的那一步，不发明自己的格式。
 */
private const val PROFILE_PATCH_TEMPLATE = """# Your patch layer for this dsh profile, applied after every bundle layer:
# a top-level YAML array of loader patch entries (id-targeted config
# overrides, disables, and insert lists; `!!js` expressions allowed).
[]
"""

/**
 * 清除页面内**全部** `<input type="file">` 的 `accept` 属性（按来源分流的第二道防线）。
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
 * 上游 `openSettingsDocument()` 的第一步是 `settings.prepareDocument()`：
 * `mkdir -p` 后把路径交给外部编辑器；而插件在 `pointerdown` 阶段就拦掉了整个
 * 调用（改走 `dshbox://`），所以这一步在上游永远不会发生 —— 原生侧必须自己补齐，
 * 否则全新安装（或 profile 尚未初始化）点「打开配置文件」只会得到「未找到配置文件」，
 * 功能看起来就是坏的。
 *
 * 物化正文用 [PROFILE_PATCH_TEMPLATE]，与上游 profile 初始化写下的内容逐字一致；
 * 早期版本这里写的是**空文件**，那会让用户打开一份没有任何说明的空白文档。
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
            file.writeText(PROFILE_PATCH_TEMPLATE)
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
 *     兼容 insets 派发不完整/不标准的部分厂商 ROM。
 *
 * 两种机制都实时计算、零写死：换手机/平板/横竖屏都自适应。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class DshWebContainer(
    context: Context,
    /** 基础地址（不含 token）。401 自动重试要在它上面重新拼 token，故提升为属性。 */
    private val url: String,
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
    /**
     * 网页端请求 Web 能力（当前只有语音输入的录音）。与 [onFileChooserRequest] 同理，
     * 首个参数是**发起请求的容器自身**：系统权限弹窗是异步的，结果回来时该容器
     * 可能已离开 Compose 树，回填必须投递给发起者而不是"当前活着的容器"。
     */
    private val onWebPermissionRequest: (DshWebContainer, PermissionRequest) -> Unit,
    private val onDshboxScheme: (Uri) -> Unit,
    /** 创建容器时已知的 launchToken（见下方 [onLaunchTokenChanged] 的修复说明）。 */
    initialLaunchToken: String? = null,
) : FrameLayout(context) {

    val webView: WebView = WebView(context)

    /**
     * 最近一次已知的 DSH `launchToken`，以及「401 自动重试是否已经用掉」。
     *
     * 401 自动重试必须**带当前 token** 重新加载：裸 `reload()` 请求的仍是同一个
     * **不带 token** 的 URL，必然再次 401；此时 `autoRefreshedForAuth` 已置位，
     * 就再没有第二次机会，页面只能靠手动刷新或重启 DSH 恢复。
     * 这是时序竞态而非架构相关：DSH 首次引导/重启期间会**换新 token**，
     * 此刻打开 DSH 页就会落进这个死角。
     *
     * 现在：401 时带**当前 token** 重新加载（完成 token → 签名 cookie 交换）；
     * 并在 token 变化时重新武装这一次重试（新 token 应获得新的一次机会）。
     */
    private var launchToken: String? = initialLaunchToken
    private var autoRefreshedForAuth = false

    /** 挂在 Activity decorView 上的全局布局监听器（见 onDetachedFromWindow 的摘除）。 */
    private var globalLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    /** 最近一次已发起加载的 URL（含 token），用于抑制重复导航。 */
    private var lastLoadedUrl: String? = null

    /** DSH 换新 launchToken 时由 UI 调用：更新 token 并重新武装 401 重试。 */
    fun onLaunchTokenChanged(token: String?) {
        if (token != launchToken) {
            launchToken = token
            autoRefreshedForAuth = false
        }
    }

    /**
     * 手动重新加载（错误覆盖层的"重试"、刷新按钮、面板按钮共用）。
     *
     * 三处入口都必须**带当前 token 加载**：裸 `webView.reload()` 请求的是当前文档地址，
     * 一旦 DSH 换过 token（重启/健康循环拉起），手动刷新拿到的仍是旧（或无）token 的地址，
     * 页面继续白屏，表现为"连手动刷新都不好使"。同时把 401 自动重试重新武装 ——
     * 手动重试应获得新的一次机会。
     *
     * 注意取的是容器自己的 [launchToken]（由 [onLaunchTokenChanged] 持续更新），
     * 而不是组合期捕获的值 —— 点击发生在组合之后，捕获值会是旧的。
     */
    fun reloadWithToken() {
        autoRefreshedForAuth = false
        val token = launchToken
        if (token != null) {
            // 手动刷新/重试：同一地址也要真重新走一次，故 force。
            loadIfNeeded(dshUrlWithToken(url, token), force = true)
        } else {
            webView.reload()
        }
    }

    /**
     * 页面 `<input type="file">` 当前挂起的回调。
     * WebView 要求它「恰好被调用一次」（传 null 表示取消），否则该 input 会被
     * 永久锁死（后续点击不再弹选择器）——因此任何新请求到达前都必须先把旧的
     * 以 null 结清；容器销毁时同理。
     */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 页面发起的、尚未结清的 Web 能力请求（见 [onPermissionRequest] 覆写）。
     *
     * WebView 同样要求它「恰好被调用一次」（`grant` 或 `deny`）：未结清会让该请求
     * 永久悬空，页面侧表现为一直等待。新请求到达前与容器销毁时都必须结清。
     */
    private var pendingWebPermission: PermissionRequest? = null

    /** 请求是否来自 DSH 自身的环回地址——只有它才有资格拿到录音能力。 */
    private fun isDshLoopbackOrigin(origin: Uri?): Boolean = when (origin?.host?.lowercase()) {
        "127.0.0.1", "localhost", "::1" -> true
        else -> false
    }

    /**
     * 把运行时权限结果回填给 WebView。
     *
     * 只按**录音**这一个资源授予（`grant` 传精确资源列表而不是 `request.resources`）：
     * 页面若同时申请了摄像头，那部分应当继续被拒——宿主与页面之间只有 `dshbox://`
     * 一条自研通道，能力面按需开、不按页面要求开。
     */
    fun submitWebPermissionResult(granted: Boolean) {
        val request = pendingWebPermission ?: return
        pendingWebPermission = null
        runCatching {
            if (granted) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else {
                request.deny()
            }
        }
    }

    /**
     * 准备一次相机拍摄：意图 + 输出落点。
     *
     * 相机应用只接受**可写的 content URI** —— 直接给 `file://` 路径会触发
     * `FileUriExposedException`，因此输出经 FileProvider 暴露（授权根
     * [CAPTURE_DIR_NAME]）。文件名按时间戳唯一，避免同一秒内两次拍摄互相覆盖。
     *
     * @return null 表示输出无法准备（FileProvider 未配置、目录建不出来等），
     *   调用方据此以取消结清回调，而不是发起一次注定回填失败的拍摄。
     */
    private fun createCaptureRequest(source: UploadSource): FileChooserRequest? {
        val video = source == UploadSource.CAMERA_VIDEO
        val dir = File(context.cacheDir, CAPTURE_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, "capture-${System.currentTimeMillis()}${if (video) ".mp4" else ".jpg"}")
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return null
        val action = if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE
        val intent = Intent(action)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        return FileChooserRequest(
            source = source,
            multiple = false,
            systemIntent = intent,
            captureOutputUri = uri,
        )
    }

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
        pendingWebPermission?.let { runCatching { it.deny() } }
        pendingWebPermission = null
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
                // 内部 scheme 通道（插件 → 原生）。命中即消费，
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
            // （WebView 显示 ERR_HTTP_RESPONSE_CODE_FAILURE）。
            //
            // 这里必须**带当前 token 重新加载**才能完成
            // token → 签名 cookie 交换：裸 `reload()` 请求的还是同一个不带 token
            // 的 URL，必然再次 401 且本次不再重试（症状：DSH 页白屏 + 进度条卡住，
            // 需手动刷新/重启 DSH 才恢复）。
            // token 变化会经 [onLaunchTokenChanged] 重新武装，所以"新 token 用掉了这次机会"也安全。
            // 仅主框架 401 且本次 token 尚未重试过时触发，防死循环。
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
                    val token = launchToken
                    if (token != null) {
                        // 重试要真重走一次（地址可能相同），故 force。
                        loadIfNeeded(dshUrlWithToken(url, token), force = true)
                    } else {
                        // 还没有 token（DSH 尚未打印启动 URL）：退回兜底加载
                        view?.reload()
                    }
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
        // 就是 <input type="file">；没有这个覆写，点击「上传」不会有任何反应。
        //
        // **按来源分流** —— 网页端「+」菜单通过给隐藏 input 打
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
                // 来源判定：哨兵 MIME 由插件打在隐藏 input 的 accept 上，逐条比对。
                // 顺序无关（四个哨兵互不包含），但保持显式以免将来加来源时漏改。
                val source = when {
                    accepts.any { it.equals(SANDBOX_UPLOAD_SENTINEL, ignoreCase = true) } ->
                        UploadSource.SANDBOX
                    accepts.any { it.equals(CAMERA_PHOTO_SENTINEL, ignoreCase = true) } ->
                        UploadSource.CAMERA_PHOTO
                    accepts.any { it.equals(CAMERA_VIDEO_SENTINEL, ignoreCase = true) } ->
                        UploadSource.CAMERA_VIDEO
                    else -> UploadSource.PHONE
                }
                val multiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                return try {
                    val request = when (source) {
                        // 沙箱侧由 Compose 弹内置选择器，结果经 submitFileChooserResult 回填。
                        UploadSource.SANDBOX -> FileChooserRequest(source, multiple, null)
                        // 相机侧由本容器准备意图与输出落点（需要 Activity 上下文与 FileProvider）。
                        UploadSource.CAMERA_PHOTO, UploadSource.CAMERA_VIDEO ->
                            createCaptureRequest(source)
                        // 手机文件走系统选择器：意图由 FileChooserParams 按 accept 生成。
                        UploadSource.PHONE ->
                            FileChooserRequest(source, multiple, fileChooserParams.createIntent())
                    }
                    if (request == null) {
                        // 相机输出无法准备（FileProvider 未配置等）：以取消结清并走默认失败路径，
                        // 不能把回调永久悬空。
                        this@DshWebContainer.filePathCallback = null
                        filePathCallback.onReceiveValue(null)
                        false
                    } else {
                        onFileChooserRequest(this@DshWebContainer, request)
                        true
                    }
                } catch (t: Throwable) {
                    // 无法起选择器（无可用 Activity 等）：立即以取消结清，返回 false
                    // 让 WebView 走默认失败路径，而不是把回调永久悬空。
                    this@DshWebContainer.filePathCallback = null
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }

            /**
             * 页面请求 Web 能力。DSH 语音输入用 `navigator.mediaDevices.getUserMedia`
             * 采集音频，走的就是这里。
             *
             * WebView 默认**拒绝**一切此类请求，不覆写就没有麦克风（页面拿到
             * `NotAllowedError`，只显示它自己的"权限未开启"提示）。
             *
             * 放行条件从严：只认**录音**资源、且请求来源必须是 DSH 自身的环回地址；
             * 其余（摄像头、非环回来源）一律拒绝。系统运行时权限尚未授予时，
             * 先转交 Compose 层去请求，结果经 [submitWebPermissionResult] 回填。
             */
            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val wantsAudio = request.resources.any {
                    it == PermissionRequest.RESOURCE_AUDIO_CAPTURE
                }
                if (!wantsAudio || !isDshLoopbackOrigin(request.origin)) {
                    runCatching { request.deny() }
                    return
                }
                // 结清上一次未决请求，防其永久悬空。
                pendingWebPermission?.let { runCatching { it.deny() } }
                pendingWebPermission = request
                onWebPermissionRequest(this@DshWebContainer, request)
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
        //    监听器存字段：decorView 比容器活得久，漏摘会让旧容器的回调一直持有已销毁的 WebView。
        val decor = (context as? Activity)?.window?.decorView
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { applyKeyboardHeight() }
        globalLayoutListener = layoutListener
        decor?.viewTreeObserver?.addOnGlobalLayoutListener(layoutListener)

        // ② ime insets 监听（补充，部分 ROM insets 派发及时）。
        //    **不消费** insets：消费掉 ime 会让 WebView/Chromium 读不到键盘状态并主动
        //    hide(ime())，表现为「点输入框键盘闪一下就没」。本 App 是 edge-to-edge，
        //    系统不会为 IME 缩放窗口，因而不存在"内建视口缩放二次压缩"。
        //    弹与收都走同一个 applyKeyboardHeight()：它有恢复 MATCH_PARENT 的分支，
        //    键盘收起必然回满屏（若只在 ime > 0 时调用，高度一旦被压下去就没有恢复入口）。
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, windowInsets ->
            applyKeyboardHeight()
            windowInsets
        }

        // 首次加载不在这里做：AndroidView 的 factory 在布局阶段才被调用，组合期的
        // LaunchedEffect 已经先行；两侧都打会连发 2~3 次。统一由 keys 含 webContainer
        // 的那个 effect 触发（见 DshWebViewScreen）。
    }

    /**
     * 只在目标 URL 变化时加载。     *
     * token/状态流每次发射都无条件 `loadUrl` 会让页面反复重新导航（表现为"一发消息就刷新"）；
     * [force] 供手动刷新与 401 重试使用（同一地址也要重新走一次）。
     */
    fun loadIfNeeded(target: String?, force: Boolean = false) {
        target ?: return
        if (!force && target == lastLoadedUrl) return
        lastLoadedUrl = target
        webView.loadUrl(target)
    }

    override fun onDetachedFromWindow() {
        globalLayoutListener?.let { listener ->
            val vto = (context as? Activity)?.window?.decorView?.viewTreeObserver
            if (vto != null && vto.isAlive) vto.removeOnGlobalLayoutListener(listener)
        }
        globalLayoutListener = null
        super.onDetachedFromWindow()
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

    // Web 能力请求（DSH 语音输入的录音）—— 系统运行时权限的请求与回填。
    // 与文件选择器同一套"记住发起者"的约定：权限弹窗是异步的，结果可能晚于容器摘除。
    val permissionOwner = remember { mutableStateOf<DshWebContainer?>(null) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val owner = permissionOwner.value
        permissionOwner.value = null
        (owner ?: webContainer)?.submitWebPermissionResult(granted)
    }

    // 相机拍摄（拍照 / 录像）—— 需要 CAMERA 运行时权限：本应用已声明 CAMERA，
    // 系统会要求 ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE 的调用方持有该权限。
    // 与文件选择器同一套"记住发起者"的约定：拍摄是异步的，结果可能晚于容器摘除。
    val cameraOwner = remember { mutableStateOf<DshWebContainer?>(null) }
    val pendingCapture = remember { mutableStateOf<FileChooserRequest?>(null) }

    // 拍摄结果：成功回填输出 URI；取消则清掉可能已建出的空文件并以 null 结清。
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val owner = cameraOwner.value
        val request = pendingCapture.value
        cameraOwner.value = null
        pendingCapture.value = null
        val uri = request?.captureOutputUri
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            (owner ?: webContainer)?.submitFileChooserResult(arrayOf(uri))
        } else {
            // 取消/失败：相机应用可能已按 EXTRA_OUTPUT 建出文件，删掉避免缓存堆积。
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            (owner ?: webContainer)?.submitFileChooserResult(null)
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val owner = cameraOwner.value
        val intent = pendingCapture.value?.systemIntent
        if (granted && owner != null && intent != null) {
            captureLauncher.launch(intent)
        } else {
            cameraOwner.value = null
            pendingCapture.value = null
            (owner ?: webContainer)?.submitFileChooserResult(null)
        }
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

    // ── 悬浮球：可拖动；位置按**球心**记录（容器内坐标），夹取以球体自身边界为准。
    //    初值落在左侧、纵向 2/3 处；容器尺寸要等首次布局才知道，故此时才落位。
    val density = LocalDensity.current
    val ballRadius = with(density) { (BALL_SIZE / 2).toPx() }
    val ballStep = with(density) { (BALL_SIZE + 10.dp).toPx() }
    val ballMargin = with(density) { 10.dp.toPx() }
    var boardSize by remember { mutableStateOf(IntSize.Zero) }
    var refreshBallX by rememberSaveable { mutableStateOf(Float.NaN) }
    var refreshBallY by rememberSaveable { mutableStateOf(Float.NaN) }
    var panelBallX by rememberSaveable { mutableStateOf(Float.NaN) }
    var panelBallY by rememberSaveable { mutableStateOf(Float.NaN) }
    LaunchedEffect(boardSize) {
        if (boardSize == IntSize.Zero) return@LaunchedEffect
        val baseX = ballMargin + ballRadius
        val baseY = boardSize.height * 2f / 3f
        if (refreshBallX.isNaN()) {
            val c = clampBallCenter(Offset(baseX, baseY), boardSize, ballRadius)
            refreshBallX = c.x
            refreshBallY = c.y
        }
        if (panelBallX.isNaN()) {
            val c = clampBallCenter(Offset(baseX, baseY + ballStep), boardSize, ballRadius)
            panelBallX = c.x
            panelBallY = c.y
        }
    }

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
    //
    // keys 里必须包含 `webContainer`：`AndroidView` 的 factory 在**布局/挂载阶段**才被调用
    // （晚于组合期），而本 effect 的协程在组合期结束就开始跑 —— 于是存在一个窗口：
    // effect 先跑、`webView` 仍为 null，这次"带 token 加载"被静默跳过；此后 token 与
    // dshState 都不再变化，**再也没有任何事件触发加载** → 白屏 + 进度条卡住，
    // 直到手动刷新/重启 DSH。把 webContainer 纳入 keys 后：容器一旦建好就会重跑本 effect。
    //
    // 加载统一走 `loadIfNeeded`（按 URL 去重）：本 effect 的 keys 里含 dshState，
    // 状态抖动一次就会重跑一次 —— 无条件 loadUrl 会把"状态变化"放大成"页面重新导航"，
    // 用户看到的就是发消息/切页面时页面自己刷新。地址没变就不该重新导航。
    LaunchedEffect(dshLaunchToken, dshState, webContainer) {
        val token = dshLaunchToken
        // token 变化时通知容器重新武装 401 重试（见 DshWebContainer.onLaunchTokenChanged）
        val container = webContainer ?: return@LaunchedEffect
        container.onLaunchTokenChanged(token)
        if (token != null && dshState == DshState.READY) {
            container.loadIfNeeded(dshUrlWithToken(url, token))
        }
    }

        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { boardSize = it },
        ) {
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
                        url = dshUrlWithToken(url, dshLaunchToken),
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
                            when (request.source) {
                                UploadSource.PHONE -> {
                                    // 记录发起者，供异步结果回填（见 chooserOwner 注释）。
                                    chooserOwner.value = owner
                                    val intent = request.systemIntent
                                    if (intent != null) {
                                        fileChooserLauncher.launch(intent)
                                    } else {
                                        chooserOwner.value = null
                                        owner.submitFileChooserResult(null)
                                    }
                                }
                                UploadSource.SANDBOX -> sandboxPick = request.multiple
                                UploadSource.CAMERA_PHOTO, UploadSource.CAMERA_VIDEO -> {
                                    cameraOwner.value = owner
                                    pendingCapture.value = request
                                    val granted = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.CAMERA,
                                    ) == PackageManager.PERMISSION_GRANTED
                                    val intent = request.systemIntent
                                    if (intent == null) {
                                        cameraOwner.value = null
                                        pendingCapture.value = null
                                        owner.submitFileChooserResult(null)
                                    } else if (granted) {
                                        // 已授权就直接拍，避免每次都弹一遍系统权限。
                                        captureLauncher.launch(intent)
                                    } else {
                                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                }
                            }
                        },
                        onWebPermissionRequest = { owner, _ ->
                            // 系统权限已授予就直接放行，避免每次都弹一遍；
                            // 否则转交系统弹窗，结果由 audioPermissionLauncher 回填。
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                owner.submitWebPermissionResult(true)
                            } else {
                                permissionOwner.value = owner
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
                        // 注意：这里**不**再补一次加载。AndroidView 的 factory 在布局阶段被调用，
                        // 而 LaunchedEffect 的 keys 里已经包含 webContainer（见下方），
                        // 容器一确定就会重跑并带 token 加载 —— 在这里再打一次会让同一个 URL
                        // 连发 2~3 次（init + factory + effect），把 onPageFinished/进度状态提前触发。
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
                        // 带 token 重试（裸 reload 在 token 轮换后必然继续失败）
                        webContainer?.reloadWithToken() ?: webView?.reload()
                    },
                )
            }

            // ── 悬浮球（可拖动 · 磨玻璃淡蓝 · 夹取按球体自身边界）──────────
            if (!refreshBallX.isNaN()) {
                GlassBall(
                    center = Offset(refreshBallX, refreshBallY),
                    board = boardSize,
                    radius = ballRadius,
                    onCenterChange = {
                        refreshBallX = it.x
                        refreshBallY = it.y
                    },
                    onClick = {
                        if (!isRefreshing) {
                            isRefreshing = true
                            // 带 token 重载：见 DshWebContainer.reloadWithToken 的修复说明
                            webContainer?.reloadWithToken() ?: webView?.reload()
                        }
                    },
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
                            contentDescription = stringResource(R.string.webview_refresh),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // 控制面板球：造型与拖动实现和刷新球同一套，当前仅"不渲染"（见 PANEL_BALL_ENABLED）。
            if (PANEL_BALL_ENABLED && !panelBallX.isNaN()) {
                GlassBall(
                    center = Offset(panelBallX, panelBallY),
                    board = boardSize,
                    radius = ballRadius,
                    onCenterChange = {
                        panelBallX = it.x
                        panelBallY = it.y
                    },
                    onClick = { panelVisible = true },
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_adjustments_horizontal),
                        contentDescription = stringResource(R.string.webview_page_controls),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
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
                                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
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
                                // 带 token 重载：见 DshWebContainer.reloadWithToken 的修复说明
                                webContainer?.reloadWithToken() ?: webView?.reload()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
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
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.webview_start_sandbox))
                }
                dshError -> Button(onClick = onStartDsh) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.webview_restart_dsh))
                }
                !dshStarting -> Button(onClick = onStartDsh) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
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
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.webview_retry))
            }
        }
    }
}

/** 悬浮球直径。 */
private val BALL_SIZE = 40.dp

/**
 * 控制面板球当前**不启用**：实现与造型和刷新球完全同一套，仅由本开关决定是否渲染。
 * 置 false 表示"当前不显示这个入口"，而不是删除它。
 */
private const val PANEL_BALL_ENABLED = false

/**
 * 悬浮球底色 —— DSH 页内淡蓝的磨玻璃版：DSH UI 的品牌蓝压到低透明度铺在页面上，
 * 再叠一道白色细描边勾出边界，透出下方内容即为磨玻璃观感。
 *
 * 注意：Compose 的 blur 只作用于**自身内容**，无法对身后背景实时模糊，
 * 因此磨玻璃只能靠"低透明度 + 描边"实现；透明度必须足够低，否则会糊住页面内容。
 */
private val DshGlassBlue = Color(0xFF7C8FF0)
private const val DSH_GLASS_ALPHA = 0.38f

/** 磨玻璃边界描边（半透明白细线）。 */
private val DshGlassBorder = Color(0x66FFFFFF)

/**
 * 把球心夹回容器内，**以球体自身边界为准**：球心可用范围是
 * `[radius, 边长 - radius]`，即球体一碰到容器边就停住，而不是球心到达容器边才停。
 */
private fun clampBallCenter(c: Offset, board: IntSize, radius: Float): Offset {
    if (board.width <= 0 || board.height <= 0) return c
    val maxX = (board.width - radius).coerceAtLeast(radius)
    val maxY = (board.height - radius).coerceAtLeast(radius)
    return Offset(c.x.coerceIn(radius, maxX), c.y.coerceIn(radius, maxY))
}

/**
 * 可拖动的磨玻璃悬浮球。[center] 为球心（容器内坐标），拖动经 [onCenterChange] 回写。
 *
 * 拖动与点击共存：`detectDragGestures` 越过触摸阈值才接管手势，轻点仍命中 [onClick]。
 * 球体绘制在 WebView 之上，命中球体的指针事件不会再落到页面里，页面不会被误滚动。
 */
@Composable
private fun GlassBall(
    center: Offset,
    board: IntSize,
    radius: Float,
    onClick: () -> Unit,
    onCenterChange: (Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    // 手势块内要读**当前**球心，不能用捕获到的快照：pointerInput 的块只在 key 变化时重启，
    // 捕获值会停留在手势开始之前 —— 表现为每帧都从旧位置重算，拖动"拖一下就弹回去"。
    val currentCenter by rememberUpdatedState(center)

    Surface(
        modifier = Modifier
            .offset { IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()) }
            .size(BALL_SIZE),
        shape = CircleShape,
        color = DshGlassBlue.copy(alpha = DSH_GLASS_ALPHA),
        border = BorderStroke(1.dp, DshGlassBorder),
        shadowElevation = 0.dp,
    ) {
        // 点击与拖动都放在 Surface **内部**：Surface 只按 shape 裁自己的内容，
        // 挂在外层 modifier 上的 clickable 涟漪会按**矩形**边界铺开，点/拖时球四周就多出一个方框。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .pointerInput(board) {
                    var acc = Offset.Zero
                    detectDragGestures(
                        onDragStart = { acc = currentCenter },
                        onDrag = { _, drag ->
                            acc += drag
                            onCenterChange(clampBallCenter(acc, board, radius))
                        },
                    )
                }
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}
