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
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
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
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.common.LogRedactor
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.util.ShareArea
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
 *    只读 ime insets 触发重算，**不消费**它：消费掉会让 WebView/Chromium 读不到键盘
 *    状态并主动收键盘（表现为「点输入框键盘闪一下就没」）。
 *  - 缩放：仅 WebView 原生双指缩放（内核自带，不触碰页面）。
 *  - 常驻悬浮球（可拖动）：点击展开五图标快捷条（刷新/首页/文件/终端/浏览器），
 *    长按按当前状态刷新/重试。
 *  - 宿主主题注入只发生在 WebView 环境层（prefers-color-scheme 随宿主主题的
 *    isLightTheme 属性解析、画布底色随宿主主题、显式关闭 algorithmic darkening），
 *    不注入任何 JS、不写 DSH 数据；DSH 自身的显式主题选择优先级始终更高。
 *  - 无服务端注入：不修改 DSH 源码，npx 更新后本容器继续可用。
 */

/** 移动 UA（固定） */
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

/**
 * 上传来源由**宿主自绘面板**决定，网页端不再参与。
 *
 * 早先「来源」靠隐藏 `<input type="file">` 的 `accept` 哨兵 MIME 从页面传到原生，
 * 那条通道依赖 `dsh-mobile-adapt` 插件在页面上打哨兵 —— 插件一旦被拔掉（或
 * 「移动端适配」开关关闭）功能即失效。现在改为**原生无条件接管**：DSH 页面里的
 * 任何文件选择请求（命令面板的「文件」、拖放、粘贴）都在 `onShowFileChooser` 处被
 * 接管并弹出七来源面板（见 [UploadSourcePanel]），与插件无关、与适配开关无关。
 */

/**
 * 相机拍摄输出的暂存目录名（`cacheDir` 之下）。
 *
 * 必须与 `res/xml/file_paths.xml` 的 `camera_capture` 授权根 `path` 一致 ——
 * 不一致时 `FileProvider.getUriForFile` 会直接抛异常，拍摄入口静默不可用。
 * 放 `cacheDir` 而非 `filesDir`：拍完即被上传，属于可回收的中间产物。
 */
internal const val CAPTURE_DIR_NAME = "camera-capture"

/**
 * 释放网页侧输入焦点的脚本。
 *
 * WebView 的 `clearFocus()` 只清 Android 侧的 View 焦点，页面里的 `document.activeElement`
 * 未必随之失焦（各内核行为不一致）；而只要页面仍有聚焦的可编辑元素，Chromium 就会在
 * 窗口重新获得焦点、或自身尺寸变化重排时，再次请求软键盘。故这里显式 blur 一次。
 *
 * IIFE + `try/catch` 收口：调用时机在页签切换 / 覆盖层打开当帧，页面可能正在跳转或销毁。
 */
private const val BLUR_ACTIVE_ELEMENT_JS =
    "(function(){try{var e=document.activeElement;" +
        "if(e&&typeof e.blur==='function')e.blur();}catch(x){}})()"

/**
 * 附件上传来源 —— 与来源面板的七枚方块一一对应。
 *
 * [PHOTO] / [VIDEO] 走系统相机意图（天然单条）；[GALLERY] 走系统相册（含图片与视频，
 * 机器没有系统相册时退到系统文件选择器）；[PHONE] / [AUDIO] 走系统文件选择器；
 * [WORKSPACE] / [SHARE] 走本板块自有的全屏选择器（见 [SandboxFilePickerDialog]）。
 */
internal enum class UploadSource { PHOTO, VIDEO, GALLERY, PHONE, AUDIO, WORKSPACE, SHARE }

/**
 * DSH 悬浮球快捷条的语义动作。
 *
 * 只表达「用户想去哪」，不携带页签下标——内容栈编号是宿主（MainScreen）的私有知识，
 * DSH 页不得感知；宿主收到回调后自行换算成 selectedTab 或浏览器启动。
 * [REFRESH] 是本页内闭环的刷新动作，不经宿主路由（快捷条回调处先行拦截）。
 * 公开可见性是签名要求（public composable 的回调参数不能暴露 internal 类型）。
 */
enum class DshQuickAction { REFRESH, HOME, FILES, TERMINAL, BROWSER }

/**
 * DSH 页返回键策略：处理顺序固定为
 * 收起自绘覆盖层（控制面板/快捷条）→ WebView 后退 → 交还系统默认行为。
 *
 * [webViewBackEnabled] 是 WebView 后退的总开关；[overlaysFirst] 是自绘覆盖层是否优先
 * 消费返回键（关闭时覆盖层不拦返回，直接按 WebView 后退 → 根页面交还处理）。
 * 注：控制面板入口由 `PANEL_BALL_ENABLED` 关闭，故 `panelVisible` 分支当前是休眠路径，
 * 保留是为了面板重新启用时返回键次序不变。
 * [crossOriginHistoryOnly] 为休眠的历史策略开关——开启时仅当回退落点是跨源真实文档
 * （http/https）才消费返回键并按落点距离直达（合成条目 chrome-error/about:blank 与
 * 环回别名不构成落点）。DSH 是单页应用，内部页面切换都会向历史栈压入同源条目（含它
 * 内置的浏览器页），故默认关闭该过滤，canGoBack 时逐条回退。
 * 根页面历史见底时交还应用导航，切回进入 DSH 之前的页签。
 */
private data class DshBackPolicy(
    val overlaysFirst: Boolean = true,
    val webViewBackEnabled: Boolean = true,
    val crossOriginHistoryOnly: Boolean = false,
)

private val DSH_BACK_POLICY = DshBackPolicy()

/**
 * 刷新圈兜底复位时限。
 *
 * isRefreshing 的常规复位点是 onPageFinished 与 onError，但存在两者都不触发的
 * 挂起路径（连接悬挂、服务端不回包等），届时球内进度圈会永久旋转；到时强制归位。
 * 复位只影响视觉反馈，不会中断仍在进行的加载。
 */
private const val REFRESH_SPINNER_TIMEOUT_MS = 20_000L

/** 渲染进程连续崩溃的重建上限：超过即转错误覆盖层，由用户显式重试（防无限重建循环）。 */
private const val RENDERER_REBUILD_LIMIT = 3

/**
 * 系统相册选择器与它的多选上限。
 *
 * 用**字面量**而不是 `MediaStore.ACTION_PICK_IMAGES` / `MediaStore.EXTRA_PICK_IMAGES_MAX`：
 * 那两个是 API 33 才有的字段，本应用 minSdk 29，直接引用会被 API 级别检查拦下。
 * 字符串本身在任何版本上都安全 —— 旧系统解析不到该动作就抛
 * `ActivityNotFoundException`，由调用处退到系统文件选择器。
 */
private const val ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES"
private const val EXTRA_PICK_IMAGES_MAX = "android.provider.extra.PICK_IMAGES_MAX"
private const val PICK_IMAGES_MAX = 100

/**
 * 解析系统选择器 / 系统相册返回的文件。
 *
 * ## 为什么不再用 `FileChooserParams.parseResult`
 *
 * 多选时系统把结果放进返回 Intent 的 `ClipData`（此时 `data` 通常为空）；只认单个
 * `data` 的解析会把整批选择丢成 0 条 —— 现象正是「手机文件选多个回来全部消失、
 * 单选正常」。这里按 **ClipData → data** 依次收全并去重，任何一条通道有结果都不丢。
 *
 * 不再逐条 `grantUriPermission`：系统选择器返回的 URI 已由返回 Intent 携带读授权，
 * 而 WebView 与本应用同进程同 UID，直接可用。反过来对外部 authority（系统媒体库等）
 * 授予自身权限可能抛 `SecurityException`，那不该让有效选择被丢弃。
 *
 * @return null 表示用户取消或没有可用结果 —— 恰好等价于「以取消结清回调」。
 */
internal fun parsePickedUris(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) return null
    val picked = LinkedHashSet<Uri>()
    data.clipData?.let { clip ->
        for (i in 0 until clip.itemCount) {
            clip.getItemAt(i)?.uri?.let(picked::add)
        }
    }
    data.data?.let(picked::add)
    return picked.takeIf { it.isNotEmpty() }?.toTypedArray()
}

/**
 * 系统选择器的「媒体分类根」。
 *
 * 系统文件选择器默认停在上次浏览的位置（首次为「最近」），给出分类根后可直接落到
 * 图片 / 音频分类；系统不采纳该定位时回落默认位置（故是尽力而为，不是保证）。
 * 取值与文件板块的 `MEDIA_ROOT_IMAGES` / `MEDIA_ROOT_AUDIO` 同源同值 —— 在本板块各留
 * 一份字面量，避免板块之间互相牵连（该 URI 是系统 MediaProvider 的文档根 id，
 * 不是本应用自造的口径）。
 */
private const val MEDIA_ROOT_IMAGES = "content://com.android.providers.media.documents/root/images_root"
private const val MEDIA_ROOT_AUDIO = "content://com.android.providers.media.documents/root/audio_root"

/**
 * 「手机文件」的系统选择器意图：`ACTION_GET_CONTENT`。
 *
 * 这一条**保持改造前的行为**：上游 `FileChooserParams.createIntent()` 产生的就是
 * `ACTION_GET_CONTENT`（系统先弹来源/应用选择器，可跨应用取文件）。多选丢失的根因在
 * 结果解析（见 [parsePickedUris]），与意图动作无关，故改回原动作不会让多选失效。
 */
internal fun systemContentIntent(allowMultiple: Boolean): Intent =
    Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
    }

/**
 * 系统文件选择器意图（`ACTION_OPEN_DOCUMENT`）。
 *
 * 自己构造而不是用 `FileChooserParams.createIntent()`：类型过滤、多选与初始位置都要
 * 由本面板的语义决定（来源已不再由页面判定），结果解析也完全自控。
 *
 * [allowMultiple] 取自页面 `<input multiple>`：能返回几个文件仍须尊重页面的声明。
 */
internal fun systemFilePickerIntent(
    mimeTypes: Array<String>,
    allowMultiple: Boolean,
    initialUri: Uri?,
): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
    // 初始位置用 DocumentsContract 的常量：Intent 上没有 EXTRA_INITIAL_URI 这个名字。
    if (initialUri != null) putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
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
internal fun createCaptureIntent(context: Context, video: Boolean): Pair<Intent, Uri>? {
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
    return intent to uri
}

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
 *  ① ViewCompat.setOnApplyWindowInsetsListener —— 实时读 ime insets 触发重算，
 *     **不消费**（消费会让 Chromium 读不到键盘状态并主动收键盘）；
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
    /** 渲染进程崩溃通知：宿主按此销毁并重建容器（渲染进程死亡后旧实例不可靠）。 */
    private val onRendererGone: () -> Unit = {},
    /**
     * 网页端发起文件选择请求。首个参数是**发起请求的容器自身**：来源面板与后续各条
     * 取文件通道都是异步的，结果可能在该容器离开 Compose 树之后才回来，调用方需要
     * 知道「该回填给谁」，而不能依赖「当前活着的容器」这一共享状态。
     *
     * 第二个参数是页面声明的**是否允许多选**（`<input multiple>` 经 `mode` 送到）：
     * 来源由宿主面板决定，但「能返回几个文件」仍须尊重页面。
     */
    private val onFileChooserRequest: (DshWebContainer, Boolean) -> Unit,
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
     * 应用宿主主题到 WebView 画布（背景色）。
     *
     * prefers-color-scheme 不在此处理：它由主题的 isLightTheme 属性解析、随
     * AppCompatDelegate 夜模式自动更新（见 init 的主题注入说明）。这里只负责
     * 底色——WebView 默认纯白，深色主题下加载间隙/白屏期会突兀闪烁。
     *
     * 由 DshWebViewScreen 经 AndroidView.update 调用：宿主主题切换必然触发该
     * 组合重组，本方法随之以最新值执行；同值重复调用等价于无操作。
     */
    fun applyAppTheme(canvasColor: Int) {
        // 组合释放竞态守卫：ON_DESTROY/onRelease 之后 update 仍可能到达一次，
        // 对已销毁 WebView 调 View 方法属未定义行为，直接跳过。
        if (released) return
        webView.setBackgroundColor(canvasColor)
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
     * 把选择器结果回填给 WebView（[uris] 为 null 表示用户取消）。
     *
     * 包 `runCatching`：结果可能晚于容器销毁才回来，此时底层 WebView 已 destroy，
     * 回填本身可能抛异常——但那已经是收尾阶段，不该把异常抛回 ActivityResult 回调。
     *
     * 这是**唯一的交付出口**：七来源面板、两条自有选择器与三个系统通道最终都汇聚到这里，
     * 对页面而言始终是同一个 `<input type="file">` 的一次普通上传 —— 附件照旧落在
     * DSH 的 composer 里，交付方式与改造前完全一致。
     */
    fun submitFileChooserResult(uris: Array<Uri>?) {
        val callback = filePathCallback ?: return
        filePathCallback = null
        runCatching { callback.onReceiveValue(uris) }
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

    /**
     * 销毁容器持有的 WebView：先结清挂起的回调（WebView 要求恰好被调用一次），
     * 再停载、清历史、摘出视图树并销毁。宿主 ON_DESTROY 与组合节点释放（onRelease）
     * 共用本方法，两处收尾保持一致、避免漂移。
     */
    private var released = false

    fun release() {
        // 幂等：Activity ON_DESTROY 与组合 onRelease 可能先后到达，二次销毁未定义。
        if (released) return
        released = true
        releasePendingResult()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.clearHistory()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
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

        // ── 宿主主题注入（全部在 WebView 环境层：零 JS、零 DSH 数据写入）──
        // prefers-color-scheme：WebView 按应用主题的 isLightTheme 属性解析该查询
        // （true → light，false → dark）。本应用主题是 AppCompat DayNight——明暗
        // 两套变体分别声明 isLightTheme，变体由 AppCompatDelegate 的夜模式决定
        // （含用户在设置里手动选择的浅色/深色），WebView 经创建它的 Activity 上下文
        // 取到同一配置。因此 DSH 页读到的系统主题即宿主主题，无需任何设置或脚本；
        // DSH 自己显式选择的主题在其页面逻辑里优先于该查询，不会被宿主覆盖。
        // algorithmic darkening：显式关闭，防止系统在深色态对 DSH 自有配色再叠加
        // 一次强制变暗（DSH 有完整主题体系，二次着色会破坏其配色）。
        // 画布底色：随宿主主题更新，见 [applyAppTheme]（由 DshWebViewScreen 在
        // 主题变化时经 AndroidView.update 调用），消除加载间隙/白屏期的突兀纯白底。
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
        }
        webView.setBackgroundColor(Color.White.toArgb())

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

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                // 渲染进程崩溃：旧 WebView 不可靠（裸 reload 不能保证恢复），通知宿主
                // 销毁并重建容器，重建后的带 token 加载自动完成页面恢复（不重启 DSH）。
                onRendererGone()
                return true
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
        // **来源由宿主决定**：这里**无条件**接管页面的每一次文件选择请求，弹宿主自绘的
        // 七来源面板（见 UploadSourcePanel）—— 不再读 accept，也不再依赖插件打哨兵。
        // 唯一沿用页面的声明是「是否允许多选」，见 onShowFileChooser 内的注释。
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
                // 结清上一次未决回调，防 input 被锁死。与 submitFileChooserResult 同口径：
                // 回填可能晚于 WebView 销毁，容错吞掉，不让异常从 WebView 回调里冒出去。
                runCatching { this@DshWebContainer.filePathCallback?.onReceiveValue(null) }
                this@DshWebContainer.filePathCallback = filePathCallback
                // **无条件接管**：来源不再由页面决定（accept 哨兵通道已随插件段一并删除），
                // 一律弹宿主自绘的来源面板（见 UploadSourcePanel）。不读 acceptTypes ——
                // DSH 的隐藏 input 本来就没有 accept，且「无条件」正是为了与插件解耦。
                //
                // 唯一保留的页面声明是「是否允许多选」：它由 `multiple` 属性经 mode 送到，
                // 决定后面各条通道最多能回填几个文件。**已核验** DSH 0.2.0-rc.2 的 composer
                // input 是 `type="file" multiple`（解包 conversation 客户端可见），故五条
                // 多选来源实际都允许多选。保留这道门控是为了不向页面超额返回：页面若是单值
                // input，返回多条会被浏览器截断，反而重现「选多个只回来一个」。
                val multiple = fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                return try {
                    onFileChooserRequest(this@DshWebContainer, multiple)
                    true
                } catch (t: Throwable) {
                    // 派发失败：立即以取消结清并返回 false 让 WebView 走默认失败路径，
                    // 而不是把回调永久悬空。
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
    /** 悬浮球快捷条的语义路由；由宿主换算成页签切换或浏览器启动。 */
    onQuickAction: (DshQuickAction) -> Unit = {},
    /**
     * 根页面返回：WebView 历史见底时由宿主接手——宿主把它换算成「切回进入 DSH 之前的
     * 页签」。不再交还系统兜底，避免任务退后台把独立任务的浏览器顶到前台。
     */
    onBackAtRoot: () -> Unit = {},
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

    // 系统文件选择器 / 系统相册 —— 相册（GALLERY）、手机文件（PHONE）、音频（AUDIO）共用。
    // 用与 Activity 生命周期绑定的 ActivityResult 契约：结果经 ActivityResultRegistry
    // 投递，无需在 MainActivity 手写 onActivityResult，也不会因配置变更丢失注册。
    //
    // 发起请求的容器单独留引用（[chooserOwner]）：选择器是异步的，结果可能在
    // 容器已从 Compose 树摘除（乃至 `webContainer` 已被 ON_DESTROY 置空）之后才回来。
    // 只认 `webContainer` 会让这种晚到的结果被 `?.` 静默丢弃，发起请求的那个
    // `filePathCallback` 就此永远收不到值。因此结果一律投递给**发起者**。
    val chooserOwner = remember { mutableStateOf<DshWebContainer?>(null) }

    val systemPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val owner = chooserOwner.value
        chooserOwner.value = null
        // 结果自己解析（见 parsePickedUris）：多选在 ClipData 里；取消时为 null，
        // 恰好等价于「以取消结清回调」。
        val uris = parsePickedUris(result.resultCode, result.data)
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
    val pendingCaptureIntent = remember { mutableStateOf<Intent?>(null) }
    val pendingCaptureUri = remember { mutableStateOf<Uri?>(null) }

    // 拍摄结果：成功回填输出 URI；取消则清掉可能已建出的空文件并以 null 结清。
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val owner = cameraOwner.value
        val uri = pendingCaptureUri.value
        cameraOwner.value = null
        pendingCaptureIntent.value = null
        pendingCaptureUri.value = null
        // 本次上传已终结，发起者引用一并清掉（与 settleUpload 口径一致）。
        chooserOwner.value = null
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
        val intent = pendingCaptureIntent.value
        if (granted && owner != null && intent != null) {
            // 与 startUploadSource 的直发分支同口径：设备没有相机 App 时 launch 会抛
            // ActivityNotFoundException —— 这里不接住就是崩溃 + 回调悬空锁死 input。
            // 不能调用 settleUpload（局部函数不可前置引用），故就地收口。
            if (runCatching { captureLauncher.launch(intent) }.isFailure) {
                val uri = pendingCaptureUri.value
                cameraOwner.value = null
                pendingCaptureIntent.value = null
                pendingCaptureUri.value = null
                chooserOwner.value = null
                // 相机未起，按 EXTRA_OUTPUT 建出的空文件要清掉。
                runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
                (owner ?: webContainer)?.submitFileChooserResult(null)
            }
        } else {
            val uri = pendingCaptureUri.value
            cameraOwner.value = null
            pendingCaptureIntent.value = null
            pendingCaptureUri.value = null
            chooserOwner.value = null
            // 权限被拒：createCaptureIntent 只铸 URI 不落盘，通常无文件可删；
            // 仍统一走一次删除，与其余收尾分支口径一致（对不存在的 URI 是 no-op）。
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            (owner ?: webContainer)?.submitFileChooserResult(null)
        }
    }

    // ── 上传来源面板 / 本板块选择器的可见状态 ──────────────────
    // 面板与选择器互斥：面板选中「工作区 / 共享区」后关闭，换成本板块的全屏选择器。
    var uploadPanelVisible by remember { mutableStateOf(false) }
    // 本次请求「页面是否允许多选」（来自 <input multiple>）：各通道据此决定能回填几个。
    var uploadMultiple by remember { mutableStateOf(false) }
    // 非 null = 本板块全屏选择器已打开（工作区 / 共享区）。
    var localPickRoot by remember { mutableStateOf<UploadPickRoot?>(null) }

    /**
     * 以「取消」结清当前上传回调，并收起所有上传 UI。
     *
     * **所有放弃路径都必须经过它**：关面板、关选择器、找不到可用选择器、授权未通过。
     * WebView 要求回调恰好被调用一次，遗留未结清会让那个 `<input type="file">`
     * 永久锁死（后续点击再也不弹选择器）。
     */
    fun settleUpload(uris: Array<Uri>?) {
        uploadPanelVisible = false
        localPickRoot = null
        val owner = chooserOwner.value
        chooserOwner.value = null
        (owner ?: webContainer)?.submitFileChooserResult(uris)
    }

    /**
     * 从系统设置页 / 权限弹窗返回：重判共享区授权，通过即进入选择器。
     *
     * 此处直开选择器时**不再单独释放焦点**：进入本函数前，打开面板 / 取文件通道那一步
     * 已经释放过。将来若新增从本分支直入选择器的路径，需自行补一次 `releaseDshInputFocus()`。
     */
    fun onShareAccessReturned() {
        if (ShareArea.hasAccess(context)) {
            runCatching { ShareArea.ensureReady() }
            uploadPanelVisible = false
            localPickRoot = UploadPickRoot.SHARE
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.webview_upload_share_denied),
                Toast.LENGTH_SHORT,
            ).show()
            settleUpload(null)
        }
    }

    /**
     * 释放 DSH 页面的输入焦点并收起软键盘。
     *
     * 四种时机：① **离开 DSH 页签时**；② **打开上传来源面板前**；③ **进入某个来源的
     * 取文件通道前**；④ **RECORD_AUDIO 未授权、即将弹系统权限对话框前**。②③④ 的必要性：
     * 只收键盘不释放焦点时，窗口重新获得焦点（来源面板、系统选择器与系统权限对话框都是独立
     * 窗口）或 WebView 重排（键盘收起后恢复满高）都会让键盘回来。
     *
     * 三个职责缺一不可：
     *  - `clearFocus()`：清 Android 侧的 View 焦点，并令 Chromium 收起 IME —— **承重步骤**，
     *    删掉它本函数即失效；
     *  - `hideSoftInputFromWindow` 与 `hide(ime())`：显式收起键盘，这一组互为冗余，
     *    删任一条安全；
     *  - [BLUR_ACTIVE_ELEMENT_JS]：清页面侧 `activeElement` —— `clearFocus()` 不保证它失焦。
     *
     * `webView` 为空时直接返回，这同时挡住了窗口级的 `hide(ime())`：首帧落在非 DSH 页签时
     * 本函数会被调用一次，若此刻放行会把**其它页签**（如终端）的键盘误收。
     *
     * 副作用：回到 DSH 页签后 composer 处于失焦态，需用户重新点一下输入区。有意**不**自动
     * 恢复焦点：自动聚焦会连带把键盘弹起，与「离开即落下」相悖。
     */
    fun releaseDshInputFocus() {
        val view = webView ?: return
        runCatching { view.clearFocus() }
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        runCatching {
            val window = (context as? Activity)?.window ?: return@runCatching
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
        runCatching { view.evaluateJavascript(BLUR_ACTIVE_ELEMENT_JS, null) }
    }

    // 共享区取文件需要公共存储的完整读写能力（Android 11+ 的「所有文件访问」，
    // Android 10 的旧式存储权限）。未授权时引导去系统设置页授权，返回后**重判授权状态**
    // （见 onShareAccessReturned）：已授权就直接进入共享区选择器，未授权则给出可见反馈
    // 并以取消结清，避免静默失败。
    //
    // 声明位置在 onShareAccessReturned 之后：Kotlin 局部函数不能前置引用，
    // 而两个 launcher 的回调都要调它。
    val shareAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onShareAccessReturned() }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onShareAccessReturned() }

    /** 起一个系统选择器意图；找不到可用 Activity 时以取消结清，不留悬空回调。 */
    fun launchSystemPicker(intent: Intent) {
        uploadPanelVisible = false
        runCatching { systemPickLauncher.launch(intent) }
            .onFailure { settleUpload(null) }
    }

    /** 进入某个来源的取文件通道。 */
    fun startUploadSource(source: UploadSource) {
        // 与「打开面板前」那次释放不重复：这段间隔里页面可能被重新聚焦（面板开着时 DSH
        // 自身逻辑仍会回焦 composer），且系统选择器返回时窗口也会重获焦点。
        releaseDshInputFocus()
        when (source) {
            UploadSource.PHOTO, UploadSource.VIDEO -> {
                val prepared = createCaptureIntent(context, video = source == UploadSource.VIDEO)
                if (prepared == null) {
                    // 输出无法准备（FileProvider 未配置等）：以取消结清，不发注定失败的一枪。
                    settleUpload(null)
                    return
                }
                val (intent, uri) = prepared
                cameraOwner.value = chooserOwner.value
                pendingCaptureIntent.value = intent
                pendingCaptureUri.value = uri
                uploadPanelVisible = false
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) == PackageManager.PERMISSION_GRANTED
                // 两条 launch 都要兜住：设备没有相机 App（部分平板/模拟器）时 launch 会抛
                // ActivityNotFoundException —— 它发生在点击回调里，不接住就是直接崩溃，
                // 且回调没被结清 → 该 <input> 永久锁死。口径与 launchSystemPicker 一致。
                val launched = if (granted) {
                    // 已授权就直接拍，避免每次都弹一遍系统权限。
                    runCatching { captureLauncher.launch(intent) }
                } else {
                    runCatching { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                }
                if (launched.isFailure) {
                    cameraOwner.value = null
                    pendingCaptureIntent.value = null
                    pendingCaptureUri.value = null
                    // 相机未起，按 EXTRA_OUTPUT 建出的空文件要清掉。
                    runCatching { context.contentResolver.delete(uri, null, null) }
                    settleUpload(null)
                }
            }

            UploadSource.GALLERY -> {
                // 相册 = 系统自带的相册选择（图片 + 视频）。机器没有系统相册时退到
                // 系统文件选择器，并尽力停在图片位置（EXTRA_INITIAL_URI 是否被采纳由系统决定）。
                val fallback = systemFilePickerIntent(
                    mimeTypes = arrayOf("image/*", "video/*"),
                    allowMultiple = uploadMultiple,
                    initialUri = Uri.parse(MEDIA_ROOT_IMAGES),
                )
                val gallery = Intent(ACTION_PICK_IMAGES).apply {
                    type = "*/*"
                    putExtra(EXTRA_PICK_IMAGES_MAX, if (uploadMultiple) PICK_IMAGES_MAX else 1)
                }
                uploadPanelVisible = false
                // 旧系统解析不到该动作会抛 ActivityNotFoundException（Android 13 之前，
                // 或未回移相册选择器的设备）—— 此时改用文件选择器。ActivityResultRegistry
                // 在 onLaunch 抛异常时会撤销本次登记，故紧接着换意图重试是安全的。
                runCatching { systemPickLauncher.launch(gallery) }
                    .onFailure { launchSystemPicker(fallback) }
            }

            UploadSource.PHONE ->
                // 保持改造前的行为：系统「来源/应用选择器」（ACTION_GET_CONTENT）。
                launchSystemPicker(systemContentIntent(uploadMultiple))

            UploadSource.AUDIO ->
                // 落点：系统文件选择器的「音频」分类（尽力而为，见 MEDIA_ROOT_AUDIO）。
                launchSystemPicker(
                    systemFilePickerIntent(
                        mimeTypes = arrayOf("audio/*"),
                        allowMultiple = uploadMultiple,
                        initialUri = Uri.parse(MEDIA_ROOT_AUDIO),
                    ),
                )

            UploadSource.WORKSPACE -> {
                uploadPanelVisible = false
                localPickRoot = UploadPickRoot.WORKSPACE
            }

            UploadSource.SHARE -> {
                if (ShareArea.hasAccess(context)) {
                    runCatching { ShareArea.ensureReady() }
                    uploadPanelVisible = false
                    localPickRoot = UploadPickRoot.SHARE
                } else {
                    // 未授权：引导去系统设置页（Android 11+ 的「所有文件访问」；Android 10
                    // 走旧式存储运行时权限）。返回后由 onShareAccessReturned 收口。
                    val launched = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            shareAccessLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.fromParts("package", context.packageName, null),
                                ),
                            )
                        }.isSuccess || runCatching {
                            shareAccessLauncher.launch(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                            )
                        }.isSuccess
                    } else {
                        runCatching {
                            legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }.isSuccess
                    }
                    if (!launched) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.webview_upload_share_denied),
                            Toast.LENGTH_SHORT,
                        ).show()
                        settleUpload(null)
                    }
                }
            }
        }
    }

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
    // 刷新请求计数：超时兜底计时器的 key，连续触发时计时器重启、不残留上一轮剩余时长。
    var refreshTick by remember { mutableIntStateOf(0) }
    // 容器重建代数：渲染进程崩溃后自增，作为 AndroidView 的 key 强制丢弃旧节点、
    // 重建全新容器（渲染进程死亡后旧 WebView 不可靠，仅 reload 不能保证恢复）。
    var webViewEpoch by remember { mutableIntStateOf(0) }
    // 容器重建（渲染进程崩溃 → webViewEpoch 自增）会由 onRelease 结清挂起的上传回调；
    // 此时必须一并收起上传 UI —— 否则面板/选择器还开着却指向已释放的容器，
    // 用户再选来源只会静默丢结果（不会锁死 input，但观感是"点了没反应"）。
    LaunchedEffect(webViewEpoch) {
        uploadPanelVisible = false
        localPickRoot = null
    }
    // 渲染进程连续崩溃计数：onPageFinished 即清零（含主框架加载失败触发错误页的
    // 宽松场景），超过上限不再自动重建、转错误覆盖层由用户显式重试（防无限重建循环）。
    var rendererCrashes by remember { mutableIntStateOf(0) }
    // 悬浮球快捷条（点击球展开/再点收起）：球常驻于未就绪态，条也两态皆可用。
    var quickBarVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isActiveTab) {
        if (!isActiveTab) {
            quickBarVisible = false
            // 非活跃页签若不释放焦点，键盘会跟随到其它页签（IME 是窗口级状态）。
            releaseDshInputFocus()
            // 来源面板是独立窗口，不随页签切换消失；离开本页签时按取消结清并收起，以免它
            // 继续覆盖其它页签、并使该隐藏 <input> 的回调悬空。此处必须显式结清：本页签是
            // 保活态，容器不会走 release() 那条收尾路径。
            // （工作区/共享区选择器是内容树内覆盖层，随本页签一起隐藏，无需在此处理。）
            if (uploadPanelVisible) settleUpload(null)
        }
    }
    // 防转圈卡死：未就绪时触发重试会置 isRefreshing=true，但没有 onPageFinished 来
    // 复位（复位点只在 READY 的页面回调里），状态离开 READY 时必须主动清掉。
    LaunchedEffect(dshState) {
        if (dshState != DshState.READY) {
            isRefreshing = false
            // 离开 READY 时 WebView 分支被移除、容器经 onRelease 结清挂起的上传回调；
            // 上传 UI 必须一并收起，否则回到 READY 后会重现一个指向已释放容器的陈旧面板
            // （再选来源只会被静默丢弃）。与 webViewEpoch 的处理同口径。
            uploadPanelVisible = false
            localPickRoot = null
        }
    }
    // 超时兜底：onPageFinished/onError 都不触发的挂起路径（连接悬挂、服务端不回包）
    // 会让球内进度圈永久旋转，到时强制归位（复位不中断加载本身）。
    LaunchedEffect(refreshTick) {
        if (refreshTick == 0) return@LaunchedEffect
        delay(REFRESH_SPINNER_TIMEOUT_MS)
        isRefreshing = false
        loadProgress = 0
    }

    // WebView 画布底色快照（宿主 background）：主题切换必然触发本组合重组，
    // AndroidView.update 随之把最新值推进容器（见 applyAppTheme）。
    val webCanvasColor = MaterialTheme.colorScheme.background.toArgb()

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
        } else {
            // 容器尺寸变化（旋转/分屏）后 rememberSaveable 恢复的旧坐标可能越界：
            // 按新边界回夹，防止球停在屏幕外需要盲拖找回。
            val c = clampBallCenter(Offset(refreshBallX, refreshBallY), boardSize, ballRadius)
            refreshBallX = c.x
            refreshBallY = c.y
        }
        if (panelBallX.isNaN()) {
            val c = clampBallCenter(Offset(baseX, baseY + ballStep), boardSize, ballRadius)
            panelBallX = c.x
            panelBallY = c.y
        } else {
            val c = clampBallCenter(Offset(panelBallX, panelBallY), boardSize, ballRadius)
            panelBallX = c.x
            panelBallY = c.y
        }
    }

    // 未就绪（含 DSH 已退出 Ready）时的上下文动作：启动沙箱或 DSH，
    // 与 WaitingState 大按钮的分支语义一致。
    val startOrRetryAction: () -> Unit = {
        when {
            !sandboxRunning -> onStartSandbox()
            dshState == DshState.STARTING -> Unit
            else -> onStartDsh()
        }
    }

    // 统一刷新入口：快捷条 REFRESH 项、长按（READY）、面板按钮、错误覆盖层重试共用。
    // 只做页面重载，与 DSH 生命周期无关联（绝不触发 startDsh/startSandbox）——
    // READY 且页面容器存在时带 token 强制重载（裸 reload 在 token 轮换后必然继续
    // 失败，见 reloadWithToken）；其余状态为安全空操作。转圈只是视觉反馈，复位由
    // onPageFinished/onError/超时兜底负责。
    val refreshAction: () -> Unit = {
        if (dshState == DshState.READY && (webContainer != null || webView != null)) {
            refreshTick += 1
            isRefreshing = true
            webContainer?.reloadWithToken() ?: webView?.reload()
        }
    }

    // 悬浮球长按动作（点击=展开快捷条，重试入口的主位仍是 WaitingState 的大按钮）：
    // READY 时走统一刷新入口；其余状态给启动/重试语义。
    val ballAction: () -> Unit = {
        when {
            refreshBallX.isNaN() -> Unit
            dshState == DshState.READY -> refreshAction()
            else -> startOrRetryAction()
        }
    }

    // WebView 是否具备后退条件（策略见 DshBackPolicy）：容器未就绪或无历史时返回 false。
    // 只在返回键**派发期**（回调体内）调用：canGoBack() 不是反应式状态，历史变化不触发
    // 重组，放进 BackHandler 的 enabled 会滞后一帧——表现为「偶发一次返回没反应」。
    fun webViewBackAvailable(): Boolean {
        if (!DSH_BACK_POLICY.webViewBackEnabled) return false
        val wv = webView ?: return false
        return wv.canGoBack()
    }

    // 返回键的单一条目：三个层级在同一个回调体里按次序表达（次序见 DshBackPolicy）——
    //   自绘覆盖层（控制面板 / 快捷条）→ WebView 逐条回退 → 根页面交还宿主。
    // 为什么不拆成多条 BackHandler：拆开就必须把 canGoBack() 写进 enabled，而它不是
    // 反应式状态（见 webViewBackAvailable 注释），历史变化后 enabled 会滞后一帧，在
    // 判定已失效的 handler 里空转吞键。判定因此全部挪到派发期现算，enabled 只依赖
    // 真反应式状态（激活态与上传选择器让位）。
    // 根页面（历史见底）不再交还系统兜底：由宿主经 [onBackAtRoot] 切回进入 DSH 之前的
    // 页签——交还系统兜底是把任务整体退后台，既像「退出 App」，又会把独立任务的浏览器
    // 顶到前台。
    // 上传选择器（内容树内覆盖层）开着时，它自己注册的 BackHandler 负责「先退一级、
    // 到根再取消」，本条目以 localPickRoot == null 整体让位。
    BackHandler(enabled = isActiveTab && localPickRoot == null) {
        when {
            DSH_BACK_POLICY.overlaysFirst && panelVisible -> panelVisible = false
            DSH_BACK_POLICY.overlaysFirst && quickBarVisible -> quickBarVisible = false
            webViewBackAvailable() -> webView?.goBack()
            else -> onBackAtRoot()
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
                    webContainer?.release()
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
            // 容器重建代数作为 key：渲染进程崩溃后自增，强制 AndroidView 丢弃旧节点、
            // 走 factory 重建全新容器（渲染进程死亡后旧 WebView 不可靠，仅 reload 不能
            // 保证恢复）；重建后的带 token 加载由下方 LaunchedEffect 自动完成。
            key(webViewEpoch) {
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
                                rendererCrashes = 0
                            },
                            onError = {
                                // 错误路径同样要复位刷新圈与进度条：主框架加载失败不触发
                                // onPageFinished，漏掉它会让刷新圈/进度条恒挂。
                                isRefreshing = false
                                loadProgress = 0
                                pageError = it.ifEmpty { context.getString(R.string.webview_load_failed) }
                            },
                            onRendererGone = {
                                // 渲染进程崩溃：限额内由 key 重建链路销毁旧容器并建新
                                // 容器，页面恢复走重建后的带 token 加载（不重启 DSH）；
                                // 连续超限转错误覆盖层，由用户显式重试（防无限重建循环）。
                                pageError = null
                                isRefreshing = false
                                loadProgress = 0
                                if (rendererCrashes < RENDERER_REBUILD_LIMIT) {
                                    rendererCrashes += 1
                                    webViewEpoch += 1
                                } else {
                                    pageError = context.getString(R.string.webview_load_failed)
                                }
                            },
                            onFileChooserRequest = { owner, multiple ->
                                // 记下发起者：来源面板与后续各条取文件通道都是异步的，
                                // 结果可能晚于容器摘除（见 chooserOwner 注释）。
                                chooserOwner.value = owner
                                uploadMultiple = multiple
                                // 先释放页面输入焦点再弹面板：只收键盘不释放焦点时，
                                // 面板（独立窗口）关闭后窗口重获焦点会把键盘叫回来。
                                releaseDshInputFocus()
                                uploadPanelVisible = true
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
                                    // 仅在 RECORD_AUDIO **未授权**这一支释放焦点：多数情况下会
                                    // 弹系统权限对话框，而它是独立窗口，关闭后本窗口重获焦点会把
                                    // 键盘叫回。已授权分支放行时不动焦点，避免每次用语音都把
                                    // composer 弄失焦。
                                    releaseDshInputFocus()
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
                                                // 路径用常量传入而不是写进资源：profile 名只在
                                                // 一处定义，资源里不重复一份会漂移的字面量。
                                                context.getString(
                                                    R.string.webview_settings_doc_missing,
                                                    SETTINGS_DOCUMENT_RELATIVE_PATH,
                                                ),
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
                    update = { container ->
                        // 宿主主题注入入口：主题切换时本组合重组，update 随之重跑。
                        container.applyAppTheme(webCanvasColor)
                    },
                    onRelease = { container ->
                        // 组合节点被永久移除（区别于切页签的保活停用）时同步销毁容器，
                        // 防止 DSH 重启往返中遗弃带渲染进程的 WebView；同时清空状态引用，
                        // 已销毁实例不可再被 canGoBack/loadUrl 触碰。
                        container.release()
                        webView = null
                        webContainer = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

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
                        if (rendererCrashes >= RENDERER_REBUILD_LIMIT) {
                            // 渲染进程已死的重试：走重建链路（对死亡实例 loadUrl 属未定义行为），
                            // 重建后的带 token 加载自动恢复页面。refreshTick 武装 20s 兜底，
                            // 重建后加载若挂起，转圈/进度条仍能归位。
                            rendererCrashes = 0
                            refreshTick += 1
                            isRefreshing = true
                            webViewEpoch += 1
                        } else {
                            // 常规加载失败：统一刷新入口带 token 重试，复位语义与其余入口一致。
                            refreshAction()
                        }
                    },
                )
            }

            // 刷新球已常驻上提到外层 Box（未就绪态也要有重试入口），不再在此分支渲染。
            // 控制面板球：造型与拖动实现和刷新球同一套，当前仅"不渲染"（见 PANEL_BALL_ENABLED）。
            // 留在 READY 分支内不跟随上提：它与 panelVisible 强耦合（面板只存在于 READY 态），
            // 且当前开关为 false 不渲染，移出无收益。
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
                        // 与球体配色同源：填充取 surface，前景取反色的 onSurface。
                        tint = MaterialTheme.colorScheme.onSurface,
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
                            onClick = refreshAction,
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

            // ── 上传来源面板：DSH 页面点「文件」后由原生**无条件**接管 ──
            // 与插件无关、与「移动端适配」开关无关：这是宿主能力，插件拔掉也在。
            if (uploadPanelVisible) {
                UploadSourcePanel(
                    onDismiss = { settleUpload(null) },
                    onPick = { source -> startUploadSource(source) },
                )
            }

            // ── 本板块的取文件选择器（工作区 / 共享区）──
            // 内容树内覆盖层（不是 Dialog 窗口）：inset 由父级 Scaffold 的 innerPadding
            // 自适应下发，见 SandboxFilePickerDialog 的段首说明。
            val pickRoot = localPickRoot
            if (pickRoot != null) {
                SandboxFilePickerDialog(
                    root = pickRoot,
                    multiple = uploadMultiple,
                    backEnabled = isActiveTab,
                    onDismiss = { settleUpload(null) },
                    onConfirm = { uris -> settleUpload(uris) },
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
                    backEnabled = isActiveTab,
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(50f),
                )
            }
        }

        // ── 悬浮球（常驻）：从 READY 分支上提到外层，未就绪/加载失败时也渲染 ──
        // 层序：zIndex(10) 浮在 WebView/进度条/错误层（默认 0）之上、内置文件查看器
        // （zIndex 50f）之下。与底部控制面板同层时球会浮在面板之上（面板当前不渲染，
        // 见 PANEL_BALL_ENABLED）；面板若恢复入口，需补「面板可见时球心避让面板矩形」。
        // 点击=展开/收起快捷条；长按=按当前状态刷新/重试（未就绪时最显眼的重试入口
        // 仍是 WaitingState 的大按钮）。无障碍名称用中性的「快捷操作」：
        // 单击行为是展开快捷条，长按动作随状态变化，「刷新」只描述次要动作。
        if (!refreshBallX.isNaN()) {
            GlassBall(
                modifier = Modifier.zIndex(10f),
                center = Offset(refreshBallX, refreshBallY),
                board = boardSize,
                radius = ballRadius,
                onClick = { quickBarVisible = !quickBarVisible },
                onLongClick = ballAction,
                onDragStart = { quickBarVisible = false },
                onCenterChange = {
                    refreshBallX = it.x
                    refreshBallY = it.y
                },
            ) {
                // 转圈反馈：刷新中，或页面正在加载（白屏/加载中有可见反馈）。
                if (isRefreshing || (isActiveTab && loadProgress in 1..99)) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = context.getString(R.string.webview_refresh) },
                        strokeWidth = 2.dp,
                        // 球体填充为 surface，进度圈取反色的 onSurface 才可见。
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_layout_dashboard),
                        contentDescription = stringResource(R.string.webview_quick_actions),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        // ── 快捷条展开时的空白点击层：点球与条之外的任意位置=收起快捷条，并吞掉该次
        //    点击（不落到网页，防止关条的第一击误触网页控件）。层序在球(10)/条(11)之下、
        //    WebView(0) 之上；页签失活随整树零尺寸放置，不会拦截任何事件。
        if (quickBarVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(9f)
                    .pointerInput(Unit) {
                        detectTapGestures { quickBarVisible = false }
                    },
            )
        }

        // ── 快捷条：悬浮球点击展开，图标复用底部导航/工具条同款资源，语义经
        //    DshQuickAction 交给宿主路由（本页不感知页签下标；REFRESH 本页闭环）。
        //    锚点=球心；拖球会立即收起条，条在约 180ms 的退场期间会随移动的球心
        //    平移并淡出（瞬态观感，可接受）。
        if (!refreshBallX.isNaN()) {
            QuickActionBar(
                visible = quickBarVisible,
                ballCenter = Offset(refreshBallX, refreshBallY),
                board = boardSize,
                radius = ballRadius,
                // 展开方向取剩余空间较大的一侧：两侧所需跨度相同（radius+gap+条宽+margin）。
                expandRight = refreshBallX <= boardSize.width / 2f,
                onAction = { action ->
                    quickBarVisible = false
                    // REFRESH 在本页闭环（统一刷新入口，不走宿主路由）。
                    if (action == DshQuickAction.REFRESH) {
                        refreshAction()
                    } else {
                        onQuickAction(action)
                    }
                },
            )
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
 * 提取 URI 的 origin（scheme + host + port，统一小写）：判定历史条目是否离开
 * DSH 同源的基准。token 等查询参数不参与，同源 SPA 的 pushState 条目不会被误判。
 */
private fun uriOrigin(uri: Uri): String {
    val scheme = uri.scheme?.lowercase() ?: return ""
    val host = uri.host?.lowercase() ?: return ""
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://$host$port"
}

/**
 * 环回别名归一：DSH 同时接受 127.0.0.1 / localhost / [::1]（与鉴权口径一致），
 * 同端口的环回主机互为同源。
 */
private fun isSameDshOrigin(origin: String, dshOrigin: String): Boolean {
    if (origin == dshOrigin) return true
    val left = Uri.parse(origin)
    val right = Uri.parse(dshOrigin)
    val loopback = setOf("127.0.0.1", "localhost", "::1")
    val leftHost = left.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
    val rightHost = right.host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
    return leftHost in loopback && rightHost in loopback && left.port == right.port
}

/**
 * 返回键在历史栈上的跨源落点距离：从当前条目的前一位向前找最近的跨源真实文档
 * （http/https）条目，返回需回退的步数；无可落点返回 null。
 *
 * 以「返回落点」而非「栈中曾存在跨源条目」为准：goBack 只回退一步，若仅判定栈中
 * 存在跨源条目，落点仍可能停在 DSH 同源的 SPA 条目上（跳到内置浏览器页的复发路径）。
 * 合成条目（chrome-error 错误页、about:blank）不作为落点——主框架加载失败会以错误页
 * 入栈，误判为跨源会让返回键被长期占用。环回别名按 [isSameDshOrigin] 归一。
 *
 * 休眠策略助手：当前返回策略为逐条回退（crossOriginHistoryOnly=false），本助手与
 * [isSameDshOrigin] 保留备用——如需恢复按落点过滤，先以 uriOrigin(Uri.parse(url))
 * 计算 DSH origin，再在 webViewBackAvailable 中调用本助手。
 */
private fun WebView.crossOriginBackSteps(dshOrigin: String): Int? {
    val list = copyBackForwardList()
    var steps = 0
    for (index in list.currentIndex - 1 downTo 0) {
        steps++
        val entryUrl = list.getItemAtIndex(index)?.url ?: continue
        val parsed = Uri.parse(entryUrl)
        val scheme = parsed.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") continue
        val origin = uriOrigin(parsed)
        if (origin.isEmpty() || isSameDshOrigin(origin, dshOrigin)) continue
        return steps
    }
    return null
}

/**
 * 可拖动的悬浮球。[center] 为球心（容器内坐标），拖动经 [onCenterChange] 回写；
 * 轻点经 [onClick]（展开/收起快捷条），长按经 [onLongClick]（按当前状态刷新/重试）。
 *
 * 手势分工：`detectDragGestures` 越过触摸阈值才接管（[onDragStart] 同时收起快捷条；
 * 条在退场动画期间会短暂跟随球心移动，随淡出消失），轻点/长按落到 combinedClickable——与 clickable 同底座，
 * 保留涟漪与 OnClick/OnLongClick 两条无障碍语义（长按刷新在 TalkBack 可达）。
 * 手势层挂在球体**内部**的圆形 Box 上：挂在外层矩形边界上涟漪会按矩形铺开。
 * 球体绘制在 WebView 之上，命中球体的指针事件不会再落到页面里，页面不会被误滚动。
 *
 * 视觉方案（实心自适应底色 + 反色描边 + 投影）：填充取 surface——浅色主题下白、
 * 深色主题下深灰，与页面主体同浅深（DSH 页面主题随宿主 isLightTheme 走，
 * 未就绪等待页/加载失败覆盖层用主题 surface，四类背景同源）；描边与前景取
 * onSurface，与填充互为反色，即使页面背景与主题错位（DSH 内显式选择相反主题），
 * 球缘仍有一圈完整轮廓可辨。不使用渐变笔刷：径向渐变在部分设备上可能产生
 * 带状/多边形着色伪影，实心填充的渲染结果完全确定。
 * 层序见调用处说明（zIndex 10，查看器 50 之下）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassBall(
    modifier: Modifier = Modifier,
    center: Offset,
    board: IntSize,
    radius: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDragStart: () -> Unit = {},
    onCenterChange: (Offset) -> Unit,
    content: @Composable () -> Unit,
) {
    // 手势块内要读**当前**球心，不能用捕获到的快照：pointerInput 的块只在 key 变化时重启，
    // 捕获值会停留在手势开始之前 —— 表现为每帧都从旧位置重算，拖动"拖一下就弹回去"。
    val currentCenter by rememberUpdatedState(center)
    val ballFill = MaterialTheme.colorScheme.surface
    val ballRim = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .offset { IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()) }
            .size(BALL_SIZE)
            // shadow 在 clip/background 之前且不裁切：投影按圆形轮廓铺开，
            // 球体叠在任意网页内容上都有轮廓分离度。
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color.Black.copy(alpha = 0.22f),
                spotColor = Color.Black.copy(alpha = 0.30f),
            )
            .clip(CircleShape)
            .background(ballFill)
            .border(
                width = 1.dp,
                color = ballRim,
                shape = CircleShape,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .pointerInput(board) {
                    var acc = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            acc = currentCenter
                            onDragStart()
                        },
                        onDrag = { _, drag ->
                            acc += drag
                            onCenterChange(clampBallCenter(acc, board, radius))
                        },
                    )
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

/** 快捷条条目：图标与文案复用底部导航/工具条同款资源，语义动作由 [DshQuickAction] 表达。 */
private class QuickAction(val labelRes: Int, val iconRes: Int, val action: DshQuickAction)

/**
 * 悬浮球快捷条：贴着球的外侧横向展开的五个圆形图标（刷新/首页/文件/终端/浏览器）。
 *
 * 几何（条宽 240dp = 5×40 + 4×6 + 2×8；条高 56dp = 40 + 2×8）：无论球拖到哪个角，
 * 整条按 margin=8dp 夹回 board 内、绝不被裁切。已知边角：条比球宽得多，球心落在
 * 容器中部一段带内时夹取会把整条压在球上方（条面自身不可点、球仍可点，功能不受损，
 * 纯观感取舍）；极窄窗口（分屏）下两侧都可能放不下，此时夹取把条压进可用区间，
 * 同样只影响观感。
 *
 * 动画：容器宽度用 tween（贴边时弹簧过冲会画出屏幕），错落与弹性交给子项的
 * spring scaleIn；展开约 275ms、收起约 180ms，均可从半程安全折返。
 * 层序：zIndex(11) 浮在悬浮球（10）之上、内置文件查看器（50f）之下。
 */
@Composable
private fun QuickActionBar(
    visible: Boolean,
    ballCenter: Offset,
    board: IntSize,
    radius: Float,
    expandRight: Boolean,
    onAction: (DshQuickAction) -> Unit,
) {
    val density = LocalDensity.current
    val itemSize = 40.dp
    val itemGap = 6.dp
    val barPadding = 8.dp
    val barHeight = itemSize + barPadding * 2
    val barWidth = itemSize * 5 + itemGap * 4 + barPadding * 2
    val barHeightPx = with(density) { barHeight.toPx() }
    val barWidthPx = with(density) { barWidth.toPx() }
    val gapPx = with(density) { itemGap.toPx() }
    val marginPx = with(density) { barPadding.toPx() }

    val x = (if (expandRight) ballCenter.x + radius + gapPx else ballCenter.x - radius - gapPx - barWidthPx)
        .coerceIn(marginPx, (board.width - barWidthPx - marginPx).coerceAtLeast(marginPx))
    val y = (ballCenter.y - barHeightPx / 2f)
        .coerceIn(marginPx, (board.height - barHeightPx - marginPx).coerceAtLeast(marginPx))

    val items = remember {
        listOf(
            QuickAction(R.string.webview_refresh, CommonR.drawable.ic_refresh, DshQuickAction.REFRESH),
            QuickAction(R.string.tab_home, R.drawable.ic_nav_home, DshQuickAction.HOME),
            QuickAction(R.string.tab_files, R.drawable.ic_nav_folder, DshQuickAction.FILES),
            QuickAction(R.string.tab_terminal, R.drawable.ic_nav_terminal, DshQuickAction.TERMINAL),
            QuickAction(R.string.browser_entry_title, R.drawable.ic_browser_safari, DshQuickAction.BROWSER),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(
            expandFrom = if (expandRight) Alignment.Start else Alignment.End,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        ) + fadeIn(tween(durationMillis = 120)),
        exit = shrinkHorizontally(
            shrinkTowards = if (expandRight) Alignment.Start else Alignment.End,
            animationSpec = tween(durationMillis = 160, easing = FastOutLinearInEasing),
        ) + fadeOut(tween(durationMillis = 100)),
        modifier = Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .zIndex(11f),
    ) {
        Surface(
            shape = RoundedCornerShape(barHeight / 2),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
            Row(
                modifier = Modifier.padding(barPadding),
                horizontalArrangement = Arrangement.spacedBy(itemGap),
            ) {
                items.forEachIndexed { index, item ->
                    // 错落次序=「从球侧往外依次弹出」：条在球右侧时首项靠球，左侧时末项靠球。
                    // 收起反向递增（靠球侧最后收、远侧先退），与容器向球侧收拢的裁切方向一致。
                    val orderFromBall = if (expandRight) index else items.lastIndex - index
                    val interactionSource = remember { MutableInteractionSource() }
                    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    Box(
                        modifier = Modifier
                            .animateEnterExit(
                                enter = scaleIn(
                                    animationSpec = spring(
                                        dampingRatio = 0.55f,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                ) + fadeIn(tween(durationMillis = 140, delayMillis = orderFromBall * 45)),
                                exit = scaleOut(tween(durationMillis = 90, delayMillis = (items.lastIndex - orderFromBall) * 25)) +
                                    fadeOut(tween(durationMillis = 80)),
                            )
                            .size(itemSize)
                            .clip(CircleShape)
                            // 整项不画矩形波纹：波纹限定在圆形内（与底部导航药丸同一分工）。
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(bounded = true, color = iconTint),
                            ) {
                                onAction(item.action)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(item.iconRes),
                            contentDescription = stringResource(item.labelRes),
                            tint = iconTint,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}
