package com.dshbox.app.ui.webview

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dshbox.app.R
import com.dshbox.app.ui.configurationAwareResources
import com.dshbox.app.common.Constants
import com.dshbox.app.DshApp
import com.dshbox.app.common.DshInstance
import com.dshbox.app.sandbox.DshState
import com.dshbox.app.ui.theme.AppDshAddressState
import com.dshbox.app.ui.theme.AppDshBehaviorState
import com.dshbox.app.util.ShareArea
import com.dshbox.dsh.renderer.api.DshRenderer
import com.dshbox.dsh.renderer.api.DshSelectedFile
import com.dshbox.dsh.renderer.api.DshAsyncRequestId
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * DSH 页 —— 可插拔渲染引擎 · 移动模式 · 原生键盘处理版。
 *
 * 设计原则：
 *  - 引擎无关：本文件只依赖 `dsh-renderer:api` 的 [DshRenderer] 契约，
 *    具体引擎（系统 WebView / GeckoView）由 build flavor 选定；页面层不感知，
 *    也不直接触碰任何引擎专有类型。
 *  - 零注入：不注入任何 JS/CSS、不篡改 viewport、不做任何页面级 hack。
 *  - 滚动修复（已确诊）：渲染层用 setOnTouchListener +
 *    requestDisallowInterceptTouchEvent(true) 强制 Compose 父容器不拦截触摸滚动。
 *  - 键盘自适应（原生层处理，自适应任何设备/平板）：
 *    把引擎视图放进纯原生 FrameLayout（DshWebContainer），用「屏幕坐标法」
 *    精确对齐：视图高度 = 键盘顶(屏幕y) − 视图顶(屏幕y)。
 *    键盘顶用 decorView 的 getWindowVisibleDisplayFrame 实时测量（挂在
 *    decorView 的 OnGlobalLayoutListener，键盘弹/收必触发），不依赖 Scaffold
 *    innerPadding / Tab 栏 / 导航栏的任何假设（在 content 区内压缩会让视图
 *    底边比键盘顶高出「Tab 栏+手势条」，页面表现为空白）。
 *    只读 ime insets 触发重算，**不消费**它：消费掉会让引擎读不到键盘
 *    状态并主动收键盘（表现为「点输入框键盘闪一下就没」）。
 *  - 缩放：仅引擎原生双指缩放（内核自带，不触碰页面）。
 *  - 常驻悬浮球（可拖动）：点击展开五图标快捷条（刷新/首页/文件/终端/浏览器），
 *    长按按当前状态刷新/重试。
 *  - 宿主主题注入只发生在渲染环境层（画布底色随宿主主题），不注入任何 JS、
 *    不写 DSH 数据；DSH 自身的显式主题选择优先级始终更高。
 *  - 无服务端注入：不修改 DSH 源码，npx 更新后本容器继续可用。
 */

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
 * 收起自绘覆盖层（控制面板/快捷条）→ 渲染器后退 → 交还系统默认行为。
 *
 * [webViewBackEnabled] 是渲染器后退的总开关；[overlaysFirst] 是自绘覆盖层是否优先
 * 消费返回键（关闭时覆盖层不拦返回，直接按渲染器后退 → 根页面交还处理）。
 * 注：控制面板入口由 `PANEL_BALL_ENABLED` 关闭，故 `panelVisible` 分支当前是休眠路径，
 * 保留是为了面板重新启用时返回键次序不变。
 * [crossOriginHistoryOnly] 为休眠的历史策略开关——开启时仅当回退落点是跨源真实文档
 * （http/https）才消费返回键并按落点距离直达（合成条目 chrome-error/about:blank 与
 * 环回别名不构成落点）。DSH 是单页应用，内部页面切换都会向历史栈压入同源条目（含它
 * 内置的浏览器页），故默认关闭该过滤，canGoBack 时逐条回退。该过滤依赖引擎的历史
 * 条目列表，属引擎专有能力，迁移后随 WebView 专有辅助函数一并移除。
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

/** 本机 DSH 页面的默认加载地址（未确证运行期地址时的 key 兜底，与
 *  `AppDshAddressState` 未确证时的实际加载地址一致；单一事实源在
 *  [Constants.DSH_BASE_URL]，此处不重复字面量）。 */
private val DEFAULT_DSH_WEB_URL = Constants.DSH_BASE_URL

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
 * 而渲染引擎与本应用同进程同 UID，直接可用。反过来对外部 authority（系统媒体库等）
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
internal fun createCaptureIntent(context: Context, video: Boolean): Triple<Intent, Uri, File>? {
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
    return Triple(intent, uri, file)
}

/**
 * 网页端 → 原生的自定义 scheme 通道。
 *
 * 本容器**没有 `addJavascriptInterface`**，插件无法直接调用原生；
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
internal const val DSHBOX_ACTION_OPEN_EXTERNAL = "open-external"

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

/** 按活跃实例 home 解析配置文件相对路径（主实例即 [SETTINGS_DOCUMENT_RELATIVE_PATH]）。 */
internal fun settingsDocumentRelativePath(instance: DshInstance): String =
    "user-data/${instance.homeLeaf}/profiles/${Constants.DSH_WEB_PROFILE}/cordis.patch.yml"

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
internal fun prepareSettingsDocument(filesDir: File, instance: DshInstance = DshInstance.MAIN): String? {
    val file = File(filesDir, settingsDocumentRelativePath(instance))
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
     * 根页面返回：渲染器历史见底时由宿主接手——宿主把它换算成「切回进入 DSH 之前的
     * 页签」。不再交还系统兜底，避免任务退后台把独立任务的浏览器顶到前台。
     */
    onBackAtRoot: () -> Unit = {},
) {
    val context = LocalContext.current
    val resources = configurationAwareResources()
    val configuration = LocalConfiguration.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // DSH 进程级 launchToken（从 `dsh web:` 原始输出解析）——
    // 首次加载携带它完成 token→签名 cookie 交换，此后渲染器凭持久 cookie 访问。
    val dshLaunchToken by (context.applicationContext as com.dshbox.app.DshApp)
        .container.sandboxManager.dshLaunchToken.collectAsState()

    var renderer by remember { mutableStateOf<DshRenderer?>(null) }
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
    val chooserOwner = remember { mutableStateOf<DshFileChooserOwner?>(null) }
    val systemPickerOwner = remember { mutableStateOf<DshFileChooserOwner?>(null) }
    val shareAccessOwner = remember { mutableStateOf<DshFileChooserOwner?>(null) }

    val systemPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val owner = systemPickerOwner.value
        systemPickerOwner.value = null
        if (chooserOwner.value === owner) chooserOwner.value = null
        // 结果自己解析（见 parsePickedUris）：多选在 ClipData 里；取消时为 null，
        // 恰好等价于「以取消结清回调」。
        val uris = parsePickedUris(result.resultCode, result.data)
        // **只投递给发起者**。发起者已不可达即视为该请求被放弃：不退回当前容器 ——
        // 当前容器是另一次请求的宿主，把旧结果塞给它会让一个 `<input>` 收到两次回填，
        // 而真正发起者那边的回调则永远悬空（该 input 后续点击不再弹选择器）。
        owner?.submitFileChooserResult(uris)
    }

    // Web 能力请求（DSH 语音输入的录音）—— 系统运行时权限的请求与回填。
    // 与文件选择器同一套"记住发起者"的约定：权限弹窗是异步的，结果可能晚于容器摘除。
    val permissionOwner = remember { mutableStateOf<DshPermissionOwner?>(null) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val owner = permissionOwner.value
        permissionOwner.value = null
        // 同上：只投递给发起者，发起者不可达即放弃，不退回当前容器。
        owner?.submitWebPermissionResult(granted)
    }

    // 相机拍摄（拍照 / 录像）—— 需要 CAMERA 运行时权限：本应用已声明 CAMERA，
    // 系统会要求 ACTION_IMAGE_CAPTURE / ACTION_VIDEO_CAPTURE 的调用方持有该权限。
    // 与文件选择器同一套"记住发起者"的约定：拍摄是异步的，结果可能晚于容器摘除。
    val cameraOwner = remember { mutableStateOf<DshFileChooserOwner?>(null) }
    val pendingCaptureIntent = remember { mutableStateOf<Intent?>(null) }
    val pendingCaptureUri = remember { mutableStateOf<Uri?>(null) }
    val pendingCaptureFile = remember { mutableStateOf<File?>(null) }

    // 拍摄结果：成功回填输出 URI；取消则清掉可能已建出的空文件并以 null 结清。
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val owner = cameraOwner.value
        val uri = pendingCaptureUri.value
        val file = pendingCaptureFile.value
        cameraOwner.value = null
        pendingCaptureIntent.value = null
        pendingCaptureUri.value = null
        pendingCaptureFile.value = null
        // 本次上传已终结，发起者引用一并清掉（与 settleUpload 口径一致）。
        if (chooserOwner.value === owner) chooserOwner.value = null
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            owner?.submitSelectedFileChooserResult(listOf(DshSelectedFile(
                uri = uri, displayName = file?.name, localFile = file,
            )))
        } else {
            // 取消/失败：相机应用可能已按 EXTRA_OUTPUT 建出文件，删掉避免缓存堆积。
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            owner?.submitFileChooserResult(null)
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
                pendingCaptureFile.value = null
                if (chooserOwner.value === owner) chooserOwner.value = null
                // 相机未起，按 EXTRA_OUTPUT 建出的空文件要清掉。
                runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
                owner?.submitFileChooserResult(null)
            }
        } else {
            val uri = pendingCaptureUri.value
            cameraOwner.value = null
            pendingCaptureIntent.value = null
            pendingCaptureUri.value = null
            pendingCaptureFile.value = null
            if (chooserOwner.value === owner) chooserOwner.value = null
            // 权限被拒：createCaptureIntent 只铸 URI 不落盘，通常无文件可删；
            // 仍统一走一次删除，与其余收尾分支口径一致（对不存在的 URI 是 no-op）。
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            owner?.submitFileChooserResult(null)
        }
    }

    // ── 上传来源面板 / 本板块选择器的可见状态 ──────────────────
    // 面板与选择器互斥：面板选中「工作区 / 共享区」后关闭，换成本板块的全屏选择器。
    var uploadPanelVisible by remember { mutableStateOf(false) }
    // 本次请求「页面是否允许多选」（来自 <input multiple>）：各通道据此决定能回填几个。
    var uploadMultiple by remember { mutableStateOf(false) }
    // 非 null = 本板块全屏选择器已打开（工作区 / 共享区）。
    var localPickRoot by remember { mutableStateOf<UploadPickRoot?>(null) }
    data class Preparation(val owner: DshWebContainer, val requestId: DshAsyncRequestId, val bytes: Long)
    var preparing by remember { mutableStateOf<Preparation?>(null) }

    fun cancelPreparation() {
        val current = preparing ?: return
        current.owner.cancelFilePreparation(current.requestId)
        if (preparing?.owner === current.owner && preparing?.requestId == current.requestId) preparing = null
    }

    /**
     * 以「取消」结清当前上传回调，并收起所有上传 UI。
     *
     * **所有放弃路径都必须经过它**：关面板、关选择器、找不到可用选择器、授权未通过。
     * 引擎要求回调恰好被调用一次，遗留未结清会让那个 `<input type="file">`
     * 永久锁死（后续点击再也不弹选择器）。
     */
    fun settleUpload(uris: Array<Uri>?) {
        uploadPanelVisible = false
        localPickRoot = null
        val owner = chooserOwner.value
        chooserOwner.value = null
        owner?.submitFileChooserResult(uris)
    }

    /**
     * 以**带本地文件元数据**的选择结果结清当前上传回调，并收起所有上传 UI。
     *
     * 与 [settleUpload] 同口径，只是回填载荷不同：本板块自有的选择器（工作区 / 共享区）
     * 直接持有本地 `File`，可连同原始文件名、MIME 一起交给渲染层 —— 引擎若需要本地路径
     * 可直接用，不必先把文件复制一份（GeckoView 的 GB 复制正是这么来的）。
     *
     * 同样**只投递给发起者**：发起者不可达即放弃，不退回当前容器。
     */
    fun settleSelectedUpload(files: List<DshSelectedFile>?) {
        uploadPanelVisible = false
        localPickRoot = null
        val owner = chooserOwner.value
        chooserOwner.value = null
        owner?.submitSelectedFileChooserResult(files)
    }

    /**
     * 从系统设置页 / 权限弹窗返回：重判共享区授权，通过即进入选择器。
     *
     * 此处直开选择器时**不再单独释放焦点**：进入本函数前，打开面板 / 取文件通道那一步
     * 已经释放过。将来若新增从本分支直入选择器的路径，需自行补一次 `releaseDshInputFocus()`。
     */
    fun onShareAccessReturned() {
        val owner = shareAccessOwner.value
        shareAccessOwner.value = null
        if (owner == null || chooserOwner.value !== owner) {
            owner?.submitFileChooserResult(null)
            return
        }
        if (ShareArea.hasAccess(context)) {
            runCatching { ShareArea.ensureReady() }
            uploadPanelVisible = false
            localPickRoot = UploadPickRoot.SHARE
        } else {
            Toast.makeText(
                context,
                resources.getString(R.string.webview_upload_share_denied),
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
     * 窗口）或页面重排（键盘收起后恢复满高）都会让键盘回来。
     *
     * 焦点与键盘的收口**全部下沉到渲染层**：[DshRenderer.clearFocusAndHideKeyboard]
     * 负责清原生焦点、blur 页面侧 `activeElement` 并收起键盘 —— 各引擎的实现不同
     * （系统 WebView 需要额外注入一次 blur 脚本），页面层不再自己拼这一套。
     *
     * 渲染器尚未就绪时直接返回：首帧落在非 DSH 页签时本函数会被调用一次，
     * 若此刻放行会把**其它页签**（如终端）的键盘误收。
     *
     * 副作用：回到 DSH 页签后 composer 处于失焦态，需用户重新点一下输入区。有意**不**自动
     * 恢复焦点：自动聚焦会连带把键盘弹起，与「离开即落下」相悖。
     */
    fun releaseDshInputFocus() {
        renderer?.clearFocusAndHideKeyboard()
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
    fun launchSystemPicker(intent: Intent, fallback: Intent? = null) {
        uploadPanelVisible = false
        if (systemPickerOwner.value != null) {
            settleUpload(null)
            return
        }
        val owner = chooserOwner.value ?: return
        systemPickerOwner.value = owner
        val first = runCatching { systemPickLauncher.launch(intent) }
        val launched = if (first.isFailure && fallback != null) {
            runCatching { systemPickLauncher.launch(fallback) }
        } else first
        launched.onFailure {
                systemPickerOwner.value = null
                if (chooserOwner.value === owner) settleUpload(null)
                else owner.submitFileChooserResult(null)
            }
    }

    /** 进入某个来源的取文件通道。 */
    fun startUploadSource(source: UploadSource) {
        // 与「打开面板前」那次释放不重复：这段间隔里页面可能被重新聚焦（面板开着时 DSH
        // 自身逻辑仍会回焦 composer），且系统选择器返回时窗口也会重获焦点。
        releaseDshInputFocus()
        when (source) {
            UploadSource.PHOTO, UploadSource.VIDEO -> {
                if (cameraOwner.value != null) {
                    settleUpload(null)
                    return
                }
                val prepared = createCaptureIntent(context, video = source == UploadSource.VIDEO)
                if (prepared == null) {
                    // 输出无法准备（FileProvider 未配置等）：以取消结清，不发注定失败的一枪。
                    settleUpload(null)
                    return
                }
                val (intent, uri, file) = prepared
                cameraOwner.value = chooserOwner.value
                pendingCaptureIntent.value = intent
                pendingCaptureUri.value = uri
                pendingCaptureFile.value = file
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
                    pendingCaptureFile.value = null
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
                launchSystemPicker(gallery, fallback)
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
                    if (shareAccessOwner.value != null) {
                        settleUpload(null)
                        return
                    }
                    shareAccessOwner.value = chooserOwner.value
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
                        shareAccessOwner.value = null
                        Toast.makeText(
                            context,
                            resources.getString(R.string.webview_upload_share_denied),
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
    // 重建全新容器（渲染进程死亡后旧实例不可靠，仅 reload 不能保证恢复）。
    var webViewEpoch by remember { mutableIntStateOf(0) }
    // 容器重建（渲染进程崩溃 → webViewEpoch 自增）会由 onRelease 结清挂起的上传回调；
    // 此时必须一并收起上传 UI —— 否则面板/选择器还开着却指向已释放的容器，
    // 用户再选来源只会静默丢结果（不会锁死 input，但观感是"点了没反应"）。
    LaunchedEffect(webViewEpoch) {
        uploadPanelVisible = false
        localPickRoot = null
    }
    // xdg-open 垫片请求消费：DSH 服务器端「打开配置文件」最终调用 /opt/dshbox/xdg-open，
    // 垫片把目标路径写进请求文件。这里轮询消费（1s 一次 stat，命中才读），映射回宿主
    // 路径后交给内置查看器——System 与 Gecko 共用同一链路，不依赖页面注入。
    val xdgRequestFile = remember {
        File(File(context.filesDir, "dshbox"), Constants.XDG_OPEN_REQUEST_NAME)
    }
    LaunchedEffect(xdgRequestFile) {
        var lastTs = 0L
        while (true) {
            if (xdgRequestFile.isFile) {
                val parsed = runCatching {
                    val text = xdgRequestFile.readText().trim()
                    val pathMatch = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(text)
                    val tsMatch = Regex("\"ts\"\\s*:\\s*(\\d+)").find(text)
                    pathMatch?.groupValues?.get(1) to (tsMatch?.groupValues?.get(1)?.toLongOrNull() ?: 0L)
                }.getOrNull()
                val (guestPath, ts) = parsed ?: (null to 0L)
                if (guestPath != null && ts > lastTs) {
                    lastTs = ts
                    // guest /root/projects/ ↔ 宿主 user-data；其余前缀交由查看器
                    // 的白名单校验拒绝（与点击 dshbox:// 同一套安全边界）。
                    val workspacePrefix = "/root/projects/"
                    val hostPath = if (guestPath.startsWith(workspacePrefix)) {
                        File(context.filesDir, "user-data/" + guestPath.removePrefix(workspacePrefix)).absolutePath
                    } else {
                        guestPath
                    }
                    if (File(hostPath).isFile) viewerPath = hostPath
                }
            }
            delay(1_000L)
        }
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
        } else if (dshState == DshState.READY) {
            // 保活页签从零尺寸放置恢复时，View 可能不会重发 attach；显式补一次
            // 生命周期回调，确保 Gecko 会话回到激活态（否则 reload 对非激活会话无效）。
            webContainer?.onAttachedToWindowActive()
        }
    }
    // 防转圈卡死：未就绪时触发重试会置 isRefreshing=true，但没有 onPageFinished 来
    // 复位（复位点只在 READY 的页面回调里），状态离开 READY 时必须主动清掉。
    LaunchedEffect(dshState) {
        if (dshState != DshState.READY) {
            isRefreshing = false
            // 离开 READY 时渲染分支被移除、容器经 onRelease 结清挂起的上传回调；
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

    // 渲染画布底色快照（宿主 background）：主题切换必然触发本组合重组，
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

    // ── 悬浮球自动收边（空闲隐藏）───────────────────────────────────────────
    // 球在无交互约三秒后自动滑向最近的左/右边缘，只露出约四分之一球体宽度
    // （可见宽度 = 直径的 1/4，球心出界半个半径，见 retractedCenterX 的几何
    // 口径）。"占用"是**派生态**：
    // 按住球的指针（全部指针抬起才解除）、拖动中的球（起点到抬指止）或展开的
    // 快捷条，任一在期即占用、球保持完整显示并等待下一次空闲。
    //
    // 计时口径：`ballIdleArmed` 只在空闲计时跑满后才置 true —— 任何占用、页签
    // 失活或容器尺寸变化都会取消 delay 并把 armed 清回 false，下一拍从零再凑
    // 三秒。"手指离开球再等三秒""快捷条收起后重新计时""首次进入页面三秒后才
    // 收边""页签失活立即回完整"全部由派生 + 重启天然达成（详见各分支注释）。
    //
    // 占用与滑行解耦：占用只翻转"要不要保持完整显示"，球的滑行/回弹交给动画
    // 层自己消化目标差分（见下），快捷条展开/收起不打断正在进行的动画。
    var ballPointerBusy by remember { mutableStateOf(false) }
    // 拖动在阈值前的短暂按下已置占用；拖动本身不需要单独的占用位 —— 一路上
    // 指针都按着，抬指时经 pressSettle 归位，会计线闭合。快捷条展开同样让球
    // 保持完整显示（也免得条随球滑到边缘被裁掉）。
    val ballBusy = ballPointerBusy || quickBarVisible

    // ── 松手即收：拖动结束（抬指/取消）时球心贴边，立即进入收边态 ──────────
    // 与空闲计时（ballIdleArmed）互不相干、同样只表达"应当收边"的意图，两者
    // 任一为真即收；占用（ballBusy）与页签失活仍然优先压平收边 —— 松手后再次
    // 按下/展开快捷条马上恢复完整显示，与空闲收边共用同一条恢复路径。
    // 写回时机：新的一局拖动在 onDragStart 清掉（上次"贴边松手"的意图随这次
    // 主动拿球作废），松手判定命中时再翻起；旋转/分屏后球心坐标已按新边界
    // 回夹（rememberSaveable 存的是球心不是收边态），本标志不持久化也不需重算。
    var ballReleaseRetract by remember { mutableStateOf(false) }
    // 阈值口径：BALL_EDGE_SNAP_THRESHOLD 是 24dp，与容器 px 的换算只在
    // density 变化时重做一次（px 随容器布局取整无影响）。
    val ballSnapThresholdPx = with(density) { BALL_EDGE_SNAP_THRESHOLD.toPx() }

    // 空闲约三秒后武装收边：**进 effect 第一拍先解除武装**，wait 期间任一占用
    // 翻转都会取消 delay（key 变化重启 effect）并经此清回 false，下一拍从零再
    // 凑三秒。armed 在 delay 完成后才置 true —— 保证"满三秒才收边"，修掉此前
    // "armed 立即为 true、球在布局摆放完成前就提前收边"的计时常量错误。
    // 容器尺寸变化（boardSize 进 keys）：重启同时**先清 armed**再从当下凑三秒
    // —— 不清的话上一轮已凑满的 true 会残留，旋转/分屏后球在重新布局完成的
    // 瞬间直接二次收边（尺寸变化与满计时叠放）。
    // 页签失活/回到前台经 isActiveTab 重启本 effect 重新计时；除此之外失活还
    // 会作废"松手即收"的残留（ballReleaseRetract 一并清掉），切回后球保持完整
    // 显示、重新等满三秒，而不是带着上一次的贴边意图立刻滑回边缘。
    var ballIdleArmed by remember { mutableStateOf(false) }
    LaunchedEffect(ballBusy, isActiveTab, boardSize, refreshBallX.isNaN()) {
        // 任何 key 变化都先解除武装：占用/失活/尺寸变化/球心定位完成全都算
        // 一次"从头计时"的边界。
        ballIdleArmed = false
        if (!isActiveTab) {
            // 页签失活：作废松手收边意图，切回后重新等满三秒。
            ballReleaseRetract = false
        }
        if (ballBusy || !isActiveTab) return@LaunchedEffect
        if (boardSize == IntSize.Zero || refreshBallX.isNaN()) return@LaunchedEffect
        // 完整空闲布局后开始凑三秒。
        delay(BALL_IDLE_RETRACT_MS)
        ballIdleArmed = true
    }

    // ── 收边滑行的动画值 ─────────────────────────────────────────────────
    // 球心坐标 `refreshBallX/Y` 记录的永远是"完整可见"时的位置（对收边无感知），
    // 收边偏移与体态单独各一根 Animatable、渲染时叠加，两条状态流互不污染：
    // 恢复后拖动写回的是原始完整坐标、快捷条锚点（球心）取的也是原始完整坐标，
    // 语义不变；收边渲染只是视觉遮蔽，无障碍语义不变（见 GlassBall 处说明）。
    //
    // X 偏移 = 完整球心到收边球心的带符号距离（拖到哪边就近滑向哪边）。弹簧
    // 目标只在"应当收边"或"应当恢复"两个状态间切换，恢复时回 0 不瞬跳；容器
    // 尺寸/球心在收边保持期间变化时目标随之重算（弹簧继续从当前位置滑向新
    // 目标，不会跳出屏幕外 —— 收边目标直接按"可见宽度 = 直径的 1/4"反解
    // （球心可以出界半个半径，那是收边态的本意）。
    //
    // 体态：收边时缩放至 ~0.96 并带回弹（轻微收缩 + 阴影变小，边缘圆润不生硬，
    // 避免"贴住即死"的观感）；恢复时同规格回 1。缩放与偏移共用一只 effect 驱动，
    // 两个动画天然同步启停。
    var ballIdle by remember { mutableStateOf(false) }
    val retractedOffsetX = remember { Animatable(0f) }
    val retractedScale = remember { Animatable(1f) }
    // 收边态直接由"忙/闲 + 计时已满 + 贴边松手"三个派生量合成：任何交互（含
    // 快捷条展开）或页签切走都会先翻转 busy/active 并解除 armed，随即这里立即
    // 回完整显示；只有凑满三秒的 armed、或拖动松手时贴边的 ballReleaseRetract
    // 才允许滑向边缘。页签切回/交互结束重新凑三秒。
    LaunchedEffect(ballBusy, ballIdleArmed, ballReleaseRetract, isActiveTab) {
        ballIdle = !ballBusy && isActiveTab && (ballIdleArmed || ballReleaseRetract)
    }
    LaunchedEffect(ballIdle, boardSize, refreshBallX, ballRadius) {
        if (boardSize == IntSize.Zero || refreshBallX.isNaN()) return@LaunchedEffect
        val leftEdge = nearestHorizontalEdge(refreshBallX, boardSize.width)
        val offsetTarget = if (ballIdle) {
            // 反解的口径按收边**终态缩放**（BALL_RETRACT_SCALE，见 retractedCenterX
            // 的 scale 参数说明）计入：0.96 体态会让视觉球心稍浅一丁点才能把
            // 可见宽保持回「直径的 1/4」。动画期间的瞬时缩放由弹簧顶着滑行，
            // 峰值误差 ≤ r·(1 − s) ≈ 0.8dp，观感无感。
            retractedCenterX(
                leftEdge,
                boardSize.width,
                ballRadius,
                scale = BALL_RETRACT_SCALE,
            ) - refreshBallX
        } else {
            0f
        }
        val scaleTarget = if (ballIdle) BALL_RETRACT_SCALE else 1f
        // 两根值一起发起，规格一致，观感上是一次"滑过去收住/弹回来展开"。
        launch {
            retractedOffsetX.animateTo(offsetTarget, BALL_RETRACT_SPRING)
        }
        launch {
            retractedScale.animateTo(scaleTarget, BALL_RETRACT_SPRING)
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
        if (dshState == DshState.READY && webContainer != null) {
            refreshTick += 1
            isRefreshing = true
            webContainer?.reloadWithToken()
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

    // 渲染器是否具备后退条件（策略见 DshBackPolicy）：容器未就绪或无历史时返回 false。
    // 只在返回键**派发期**（回调体内）调用：canGoBack() 不是反应式状态，历史变化不触发
    // 重组，放进 BackHandler 的 enabled 会滞后一帧——表现为「偶发一次返回没反应」。
    fun rendererBackAvailable(): Boolean {
        if (!DSH_BACK_POLICY.webViewBackEnabled) return false
        val r = renderer ?: return false
        return r.canGoBack()
    }

    // 返回键的单一条目：三个层级在同一个回调体里按次序表达（次序见 DshBackPolicy）——
    //   自绘覆盖层（控制面板 / 快捷条）→ 渲染器逐条回退 → 根页面交还宿主。
    // 为什么不拆成多条 BackHandler：拆开就必须把 canGoBack() 写进 enabled，而它不是
    // 反应式状态（见 rendererBackAvailable 注释），历史变化后 enabled 会滞后一帧，在
    // 判定已失效的 handler 里空转吞键。判定因此全部挪到派发期现算，enabled 只依赖
    // 真反应式状态（激活态与上传选择器让位）。
    // 根页面（历史见底）不再交还系统兜底：由宿主经 [onBackAtRoot] 切回进入 DSH 之前的
    // 页签——交还系统兜底是把任务整体退后台，既像「退出 App」，又会把独立任务的浏览器
    // 顶到前台。
    // 上传选择器（内容树内覆盖层）开着时，它自己注册的 BackHandler 负责「先退一级、
    // 到根再取消」，本条目以 localPickRoot == null 整体让位。
    BackHandler(enabled = isActiveTab && localPickRoot == null) {
        when {
            preparing != null -> cancelPreparation()
            DSH_BACK_POLICY.overlaysFirst && panelVisible -> panelVisible = false
            DSH_BACK_POLICY.overlaysFirst && quickBarVisible -> quickBarVisible = false
            rendererBackAvailable() -> renderer?.goBack()
            else -> onBackAtRoot()
        }
    }

    // 生命周期绑定：把 Activity 生命周期转成渲染器的生命周期回调。
    //
    // ON_START / ON_STOP 也在这里转发：引擎（GeckoView）用这两步切换会话的激活状态，
    // 只转发 ON_PAUSE/ON_RESUME 会让它停在"未激活"。
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val r = renderer ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> r.onStart()
                Lifecycle.Event.ON_RESUME -> r.onResume()
                Lifecycle.Event.ON_PAUSE -> r.onPause()
                Lifecycle.Event.ON_STOP -> r.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    webContainer?.release()
                    renderer = null
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
    // effect 先跑、`webContainer` 仍为 null，这次"带 token 加载"被静默跳过；此后 token 与
    // dshState 都不再变化，**再也没有任何事件触发加载** → 白屏 + 进度条卡住，
    // 直到手动刷新/重启 DSH。把 webContainer 纳入 keys 后：容器一旦建好就会重跑本 effect。
    //
    // 加载统一走 `loadIfNeeded`（按 URL 去重）：本 effect 的 keys 里含 dshState，
    // 状态抖动一次就会重跑一次 —— 无条件导航会把"状态变化"放大成"页面重新导航"，
    // 用户看到的就是发消息/切页面时页面自己刷新。地址没变就不该重新导航。
    // 回车键「换行」设置并入 keys：拨动的重组即时把新档推给本机容器。
    val enterKeyNewline = AppDshBehaviorState.enterKeyNewline
    LaunchedEffect(dshLaunchToken, dshState, enterKeyNewline, webContainer) {
        val token = dshLaunchToken?.takeIf(String::isNotEmpty)
        // token 变化时通知容器重新武装 401 重试（见 DshWebContainer.onLaunchTokenChanged）
        val container = webContainer ?: return@LaunchedEffect
        container.onLaunchTokenChanged(token)
        container.setEnterKeyNewline(enterKeyNewline)
        if (dshState == DshState.READY && (token != null || !container.hasNavigation)) {
            // 这里传带 token 的地址仅作为**去重键**：token 轮换后地址变了，才不会
            // 被容器的"同地址不重复导航"规则吞掉。真正下发给引擎的地址由容器
            // 自己用**当前** token 拼（见 DshWebContainer）。
            container.loadIfNeeded(dshUrlWithToken(url, token))
        }
    }

        Box(
            modifier = modifier
                .fillMaxSize()
                .onSizeChanged { boardSize = it },
        ) {
        val preparation = preparing
        if (preparation != null && isActiveTab) {
            AlertDialog(
                onDismissRequest = { cancelPreparation() },
                title = { Text(stringResource(R.string.dsh_renderer_preparing_attachment)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.dsh_renderer_prepared_bytes,
                            String.format(configuration.locales[0], "%.1f", preparation.bytes / 1048576.0)))
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { cancelPreparation() }) {
                        Text(stringResource(R.string.files_cancel))
                    }
                },
            )
        }
        if (dshState != DshState.READY) {
            WaitingState(
                dshState = dshState,
                sandboxRunning = sandboxRunning,
                onStartDsh = onStartDsh,
                onStartSandbox = onStartSandbox,
            )
        } else {
            // ── 原生渲染容器（键盘处理在原生层，自适应）──
            // 容器重建代数作为 key：渲染进程崩溃后自增，强制 AndroidView 丢弃旧节点、
            // 走 factory 重建全新容器（渲染进程死亡后旧实例不可靠，仅 reload 不能
            // 保证恢复）；重建后的带 token 加载由下方 LaunchedEffect 自动完成。
            //
            // 确证地址并入 key：本机容器 url 是构造期 val（烤进可信 origin 与后续
            // 导航目标），确证地址（端口自适应换端口/重启落新端口）变化时必须换新
            // 容器——只带 token 重载改变不了构造期 url（见 DshWebContainer）。
            // 确证值仍指向默认 3080 时 key 不变、既有容器保留（零行为变化）。
            val confirmedWebUrl = AppDshAddressState.confirmedBaseUrl ?: DEFAULT_DSH_WEB_URL
            key(confirmedWebUrl, webViewEpoch) {
                AndroidView(
                    factory = { ctx ->
                        try {
                        DshWebContainer(
                            context = ctx,
                            // **裸基础地址**：token 由容器自己管理（构造时给一份初值，
                            // 之后经 onLaunchTokenChanged 更新）。地址在这里拼一次
                            // 会把建容器那一刻的 token 烤死进信任源与后续导航里。
                            url = url,
                            initialLaunchToken = dshLaunchToken,
                            onFilePreparation = { owner, request, bytes, active ->
                                if (active && owner === webContainer) {
                                    preparing = Preparation(owner, request, bytes)
                                } else if (!active && preparing?.owner === owner && preparing?.requestId == request) {
                                    preparing = null
                                }
                            },
                            onProgress = { loadProgress = it },
                            onPageStarted = {
                                loadProgress = 0
                                pageError = null
                                chooserOwner.value = null
                                uploadPanelVisible = false
                                localPickRoot = null
                                preparing = null
                            },
                            onPageFinished = {
                                loadProgress = 100
                                isRefreshing = false
                                rendererCrashes = 0
                                // 回车键「换行」档在页面重载后回退：安装期注入配置
                                // 只在 document-start 生效一次，重载后的页面回到注入
                                // 配置里的初值——这里按当前档位重推（推送是
                                // evaluateJavascript 幂等写，拨动的档位不丢）。
                                webContainer?.setEnterKeyNewline(AppDshBehaviorState.enterKeyNewline)
                            },
                            onError = {
                                // 错误路径同样要复位刷新圈与进度条：主框架加载失败不触发
                                // onPageFinished，漏掉它会让刷新圈/进度条恒挂。
                                isRefreshing = false
                                loadProgress = 0
                                pageError = it.ifEmpty { resources.getString(R.string.webview_load_failed) }
                            },
                            onRendererGone = {
                                // 渲染进程崩溃：限额内由 key 重建链路销毁旧容器并建新
                                // 容器，页面恢复走重建后的带 token 加载（不重启 DSH）；
                                // 连续超限转错误覆盖层，由用户显式重试（防无限重建循环）。
                                pageError = null
                                isRefreshing = false
                                loadProgress = 0
                                if (rendererCrashes >= RENDERER_REBUILD_LIMIT) {
                                    pageError = resources.getString(R.string.webview_load_failed)
                                } else {
                                    rendererCrashes += 1
                                    webViewEpoch += 1
                                }
                            },
                            onFileChooserRequest = { owner, multiple ->
                                // 记下发起者：来源面板与后续各条取文件通道都是异步的，
                                // 结果可能晚于容器摘除（见 chooserOwner 注释）。
                                chooserOwner.value = owner.captureFileChooserOwner()
                                uploadMultiple = multiple
                                // 先释放页面输入焦点再弹面板：只收键盘不释放焦点时，
                                // 面板（独立窗口）关闭后窗口重获焦点会把键盘叫回来。
                                releaseDshInputFocus()
                                uploadPanelVisible = true
                            },
                            onWebPermissionRequest = { owner, request ->
                                // 系统权限已授予就直接放行，避免每次都弹一遍；
                                // 否则转交系统弹窗，结果由 audioPermissionLauncher 回填。
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO,
                                ) == PackageManager.PERMISSION_GRANTED
                                if (com.dshbox.dsh.renderer.api.DshWebPermission.AUDIO_CAPTURE !in request.permissions) {
                                    owner.submitWebPermissionResult(false)
                                } else if (granted) {
                                    owner.submitWebPermissionResult(true)
                                } else if (permissionOwner.value != null) {
                                    owner.submitWebPermissionResult(false)
                                } else {
                                    // 仅在 RECORD_AUDIO **未授权**这一支释放焦点：多数情况下会
                                    // 弹系统权限对话框，而它是独立窗口，关闭后本窗口重获焦点会把
                                    // 键盘叫回。已授权分支放行时不动焦点，避免每次用语音都把
                                    // composer 弄失焦。
                                    releaseDshInputFocus()
                                    val pending = owner.capturePermissionOwner()
                                    permissionOwner.value = pending
                                    if (pending != null) {
                                        runCatching { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                                            .onFailure {
                                                if (permissionOwner.value === pending) permissionOwner.value = null
                                                pending.submitWebPermissionResult(false)
                                            }
                                    }
                                }
                            },
                            onDshboxScheme = { actionUri ->
                                when (actionUri.host?.lowercase()) {
                                    DSHBOX_ACTION_SETTINGS_DOCUMENT -> {
                                        // 补齐上游 prepareDocument()（插件拦截后它不会执行）：
                                        // 文件缺失时按上游语义建一个空文件再打开。落点随活跃实例。
                                        val activeInstance =
                                            (context.applicationContext as DshApp).container.instanceRegistry.active.value
                                        val path = prepareSettingsDocument(context.filesDir, activeInstance)
                                        if (path != null) {
                                            viewerPath = path
                                        } else {
                                            Toast.makeText(
                                                context,
                                                // 路径用常量传入而不是写进资源：profile 名只在
                                                // 一处定义，资源里不重复一份会漂移的字面量。
                                                resources.getString(
                                                    R.string.webview_settings_doc_missing,
                                                    settingsDocumentRelativePath(activeInstance),
                                                ),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    DSHBOX_ACTION_OPEN_EXTERNAL -> {
                                        val externalUrl = actionUri.getQueryParameter("url")
                                        if (externalUrl == null ||
                                            !openExternalInDefaultBrowser(context, externalUrl, url)
                                        ) {
                                            Toast.makeText(
                                                context,
                                                R.string.home_open_browser_failed,
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                    else -> Unit
                                }
                            },
                            onExternalUrlRequested = { externalUrl ->
                                if (!openExternalInDefaultBrowser(context, externalUrl, url)) {
                                    Toast.makeText(
                                        context,
                                        R.string.home_open_browser_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            },
                        ).also { container ->
                            renderer = container.renderer
                            webContainer = container
                            // 注意：这里**不**再补一次加载。AndroidView 的 factory 在布局阶段被调用，
                            // 而 LaunchedEffect 的 keys 里已经包含 webContainer（见下方），
                            // 容器一确定就会重跑并带 token 加载 —— 在这里再打一次会让同一个 URL
                            // 连发 2~3 次（init + factory + effect），把 onPageFinished/进度状态提前触发。
                        }
                        } catch (failure: Exception) {
                            com.dshbox.app.dsh.DshRendererReportStore.failed()
                            rendererCrashes = RENDERER_REBUILD_LIMIT
                            pageError = resources.getString(R.string.webview_engine_failed)
                            android.util.Log.e("DshRenderer", "Initialization failed", failure)
                            android.widget.FrameLayout(ctx)
                        } catch (failure: LinkageError) {
                            com.dshbox.app.dsh.DshRendererReportStore.failed()
                            rendererCrashes = RENDERER_REBUILD_LIMIT
                            pageError = resources.getString(R.string.webview_engine_failed)
                            android.util.Log.e("DshRenderer", "Engine unavailable", failure)
                            android.widget.FrameLayout(ctx)
                        }
                    },
                    update = { container ->
                        // 宿主主题注入入口：主题切换时本组合重组，update 随之重跑。
                        (container as? DshWebContainer)?.applyAppTheme(webCanvasColor)
                    },
                    onRelease = { container ->
                        // 组合节点被永久移除（区别于切页签的保活停用）时同步销毁容器，
                        // 防止 DSH 重启往返中遗弃带渲染进程的实例；同时清空状态引用，
                        // 已销毁实例不可再被 canGoBack/load 触碰。
                        (container as? DshWebContainer)?.release()
                        if (preparing?.owner === container) preparing = null
                        renderer = null
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
                    // 本板块的选择器持有本地 `File`，连同原始文件名/MIME 一起回填 ——
                    // 引擎若需要本地路径可直接用，不必先复制一份（GeckoView 的 GB 复制）。
                    onConfirm = { files -> settleSelectedUpload(files) },
                )
            }

            // ── 内置文件查看器 ──────────
            // 承接 DSH 页内发起的配置文件打开请求：路径经请求文件消费段映射后传入，
            // 查看器内置 YAML 高亮（util/viewer/highlight），不依赖外部应用。
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
        // 层序：球的裁剪层（fillMaxSize + clipToBounds 的 Box，zIndex 10f）浮在
        // WebView/进度条/错误层（默认 0）之上、内置文件查看器（zIndex 50f）之下。
        // 与底部控制面板同层时球会浮在面板之上（面板当前不渲染，见
        // PANEL_BALL_ENABLED）；面板若恢复入口，需补「面板可见时球心避让面板矩形」。
        // 点击=展开/收起快捷条；长按=按当前状态刷新/重试（未就绪时最显眼的重试入口
        // 仍是 WaitingState 的大按钮）。无障碍名称用中性的「快捷操作」：
        // 单击行为是展开快捷条，长按动作随状态变化，「刷新」只描述次要动作。
        //
        // 空闲收边：渲染位置在球心基础上叠加收边偏移与体态（见前文动画段），收边
        // 期间点击/拖动的命中区域跟随当前显示位置走（offset 驱动的矩形边界随之
        // 移动），无障碍语义与点击行为保持不变。按下→抬指全程占用（pressFeedback/
        // pressSettle 一对回写 ballPointerBusy），快捷条展开期间沿用 quickBarVisible
        // 占用，三条占用线独立记账、互不踩踏。松手即收：拖动结束贴边直接置
        // ballReleaseRetract（不走空闲计时，吸附动画共用同一根 spring）；
        // 再次按下/拿球则作废收边意图，恢复完整显示从头再凑。
        if (!refreshBallX.isNaN()) {
            // ── 球的裁剪层：fillMaxSize + clipToBounds 只包住球 ────────────
            // 收边球心**有意**出界半个半径（见 retractedCenterX 口径），若这一层
            // 无裁剪：竖屏窗口边缘恰与容器缘重合时"看起来"是 1/4 是视觉巧合；
            // 横屏侧边栏/状态栏 insets 让 WebView 容器内缩后，容器不再被窗口边
            // 裁住，整球（含出界的部分）会完全露在面板上。给球单独一层物理裁剪，
            // 球所在区域可见部分恒为容器的四分之一、且可见部分仍可点击（命中
            // 区域跟随 offset 驱动的矩形边界，被裁掉的部分不可见也不可点）。
            // 层序：本剪层占 zIndex(10)，容器内的 WebView/进度条/错误层（0）在其
            // 下、快捷条（11）在其上。快捷条**不**包进来：条的贴合夹取逻辑自己
            // 保证完整可见（条全量在 board 内），包进剪层会让球的 clip 边缘吃掉
            // 条贴边滑行的过冲帧（scaleIn spring 过冲会短暂画出 board），拖动期
            // 条随球淡出的退场动画也别在剪层里被截断。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .zIndex(10f),
            ) {
                GlassBall(
                    modifier = Modifier,
                    center = Offset(refreshBallX, refreshBallY),
                    board = boardSize,
                    radius = ballRadius,
                    onClick = { quickBarVisible = !quickBarVisible },
                    onLongClick = ballAction,
                    onDragStart = {
                        quickBarVisible = false
                        // 上一局"贴边松手"的收边意图随这次主动拿球作废；是否再次
                        // 收边由本局松手位置重新判定（见下方 onDragEnd）。
                        ballReleaseRetract = false
                    },
                    onDragEnd = { releasePoint ->
                        // 松手即收：球心贴边（或在边界外）立即进入收边态，不等空闲
                        // 三秒。收向哪一侧在动画层由 nearestHorizontalEdge 按落点
                        // 就近判定（界外球心自然落入近侧），吸附用空闲收边同一条
                        // spring —— 见收边动画段的口径注释。
                        if (shouldSnapOnRelease(
                                centerX = releasePoint.x,
                                boardWidth = boardSize.width.toFloat(),
                                threshold = ballSnapThresholdPx,
                            )
                        ) {
                            ballReleaseRetract = true
                        }
                    },
                    onCenterChange = {
                        refreshBallX = it.x
                        refreshBallY = it.y
                    },
                    pressFeedback = {
                        // 按下只做两件事：恢复球完整显示 + 暂停空闲计时（经
                        // ballPointerBusy 触发重启）。**不要**在这里动
                        // quickBarVisible：快捷条展开状态下先按下、抬指处
                        // onClick 是「切换」，若这里顺手关条，点击就会变成
                        // 「关了又开」，快捷条收起交给 onClick 的切换与
                        // onDragStart 的拖动收起两条路径。
                        // 对"贴边松手"的球同样成立：按下即恢复完整显示，上次
                        // 的松手收边意图一并作废（拾起重新判定，不再自动贴回）。
                        ballPointerBusy = true
                        ballReleaseRetract = false
                    },
                    pressSettle = { ballPointerBusy = false },
                    visualOffsetX = { retractedOffsetX.value },
                    visualScale = { retractedScale.value },
                ) {
                    // 转圈反馈：刷新中，或页面正在加载（白屏/加载中有可见反馈）。
                    if (isRefreshing || (isActiveTab && loadProgress in 1..99)) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = resources.getString(R.string.webview_refresh) },
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
        }

        // ── 快捷条展开时的空白点击层：点球与条之外的任意位置=收起快捷条，并吞掉该次
        //    点击（不落到网页，防止关条的第一击误触网页控件）。层序在球剪层(10)/条(11)
        //    之下、WebView(0) 之上；页签失活随整树零尺寸放置，不会拦截任何事件。
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

// ── 悬浮球收边（空闲隐藏）几何 ──────────────────────────────────────────────
// 以下两个纯函数只依赖入参，不触碰任何组合状态，放本文件顶层以便 JVM 单测直接覆盖。

/** 空闲多久后自动收边。 */
private const val BALL_IDLE_RETRACT_MS = 3_000L

/**
 * 收边时球的缩放（轻微收缩让贴边看起来圆润，不生硬停靠）。收边终态体态：
 * 该值同时作为 retractedCenterX 反解的 `scale` 参数进入几何（可见宽四分之
 * 一的口径按这个缩放终值反解，收边后该比例不受体态影响），internal 是口径
 * 契约的测试要求（BallRetractionGeometryTest 锁定原值）。
 */
internal const val BALL_RETRACT_SCALE = 0.96f

/** 收边/恢复共用的弹簧规格：中等刚性 + 偏欠阻尼，滑到位后有一次自然回弹。 */
private val BALL_RETRACT_SPRING = spring<Float>(
    dampingRatio = 0.9f,
    stiffness = Spring.StiffnessLow,
)

/** 收边时露在容器内的可见宽度占**直径**的比例：约四分之一球体露在边缘外。 */
internal const val RETRACT_VISIBLE_FRACTION = 0.25f

/**
 * 松手即收判定：球心距左缘或右缘的水平距离小于 [threshold] 即视为"贴边松手"，
 * 拖动结束后应**立即**进入收边态（不等空闲三秒计时）。
 *
 * 判定口径：
 *  - 距离按**球心**到容器左/右缘计（与拖动落点、球体边界无关），两条取更近者；
 *  - 球心越界（负值/超宽）时距离为负，自然落入判定 —— 与"已在边界外同样按收边
 *    处理"的松手语义一致；
 *  - **严格小于**：球心距缘恰好等于阈值不算贴边（口径固定，测试锁定）；
 *  - 容器宽 <= 0（页签失活的零尺寸放置）没有"边缘"可言，返回 false；
 *  - 阈值 <= 0 且球心在界内时永不命中，只有界外球心（负距离）仍会命中。
 *
 * 命中后收向哪一侧由 [nearestHorizontalEdge] 单独判定，本函数只回答"收不收"。
 */
internal fun shouldSnapOnRelease(centerX: Float, boardWidth: Float, threshold: Float): Boolean {
    if (boardWidth <= 0f) return false
    return minOf(centerX, boardWidth - centerX) < threshold
}

/**
 * 松手即收的贴边阈值：球心距容器左/右缘小于 24dp 即在松手时立即收边。
 * 单位是 dp（密度无关，按容器 px 换算后比较）；取值覆盖"拖满贴边"的口径 ——
 * 拖动受 [clampBallCenter] 约束，贴死边缘时球心距缘恰为半径（40dp 球 = 20dp），
 * 20dp < 24dp 必然命中，而松在离缘一个球身（约 40dp）外则不收。
 */
internal val BALL_EDGE_SNAP_THRESHOLD = 24.dp

/**
 * 判断球心离左缘还是右缘更近（按**球心**到两缘的水平距离，与拖动落点无关）。
 * 正好处于容器中缝时取左缘（与快捷条 `expandRight` 的口径相反但互不干扰：
 * 一个管球贴合哪侧、一个管条向剩余空间更大的一侧展开）。
 *
 * 容器宽度为 0（页签失活后的零尺寸放置）没有"边缘"可言，返回左侧作为稳定默认，
 * 调用方不应在该状态下渲染球，也不会用到该结果。
 */
internal fun nearestHorizontalEdge(centerX: Float, boardWidth: Int): Boolean {
    // 返回 true = 靠左缘，false = 靠右缘。
    if (boardWidth <= 0) return true
    return centerX <= boardWidth / 2f
}

/**
 * 收边后的球心 X：球的**可见宽度**（容器内被裁剩的部分）等于
 * [visibleFraction] × 直径 —— 露出约四分之一球体的宽度。
 *
 * 反解口径（左缘，不缩放）：可见宽度 = 球体右缘 − 容器左缘 = 球心 + r，令它
 * 等于直径 × f = 2r·f，得收边球心 = r·(2f − 1)。f = 0.25 时球心 = −0.5r ——
 * 即球心悬在容器边界**之外**半个半径；右缘镜像 = 宽 − r·(2f − 1) = 宽 + 0.5r。
 * 收边坐标因此**有意**落在容器逻辑坐标系之外（与 clampBallCenter 只管完整
 * 态回夹、不掺收边不同），调用方（动画/渲染层）直接用该值做收边 offset，
 * 无需再夹回容器内。
 *
 * 缩放补偿（[scale]）：收边体态会缩放到 ~0.96（[BALL_RETRACT_SCALE]，见体态
 * 动画段说明），渲染层 graphicsLayer 以球**左上角**为锚点，offset 又先抵扣了
 * 半径·缩放，两次叠加下视觉球心 = `球心 + 收边 offset`（缩放不挪心），可见宽
 * = 视觉球心 + 缩放半径 r·s − 容器缘。令它仍等于 2r·f 得球心 = r·(2f − s)——
 * 缩放缺省 1 即原口径；s = 0.96 时球心 = −0.46r，比不补偿浅 0.04r。调用方
 * 把收边**终态**缩放（常量）传进来：滑行期间瞬时缩放由弹簧驱动（1 → s），
 * 球心目标按终态反解，可见宽在终态恰为直径的 f；过渡期偏差 ≤ r·(1 − s)
 * ≈ 0.8dp、观感无感。选择**补偿几何而非收边不缩放**：0.96 的微缩是刻意设计
 * 的体态反馈（边缘圆润、避免贴住即死），去掉会让贴边观感生硬。
 *
 * 边界口径：`radius <= 0`（无意义）或 `width <= 0`（零尺寸放置）退化为完整
 * 贴边（球心 = 0，与容器边界对齐）；f = 0 时完全藏起（球心 = −r（s = 1），
 * 含缩放口径为 −r·s），f = 1 时球心贴边内侧 = 完整可见位置 = r。半径大到球心
 * 绝对值超过容器宽（极端小窗/分屏）仍按公式取值 —— 滑行目标与容器边界无关。
 */
internal fun retractedCenterX(
    leftEdge: Boolean,
    boardWidth: Int,
    radius: Float,
    visibleFraction: Float = RETRACT_VISIBLE_FRACTION,
    scale: Float = 1f,
): Float {
    if (boardWidth <= 0 || radius <= 0f) return 0f
    // 收边球心 = ±r·(2f − s)：f = 0.25、s = 1 → −0.5r（球心出界半半径），
    // f = 0.25、s = 0.96（收边终态体态缩放）→ −0.46r（比不补偿浅 0.04r）；
    // s = 1 时该式退化为原始口径 r·(2f − 1)。
    val signedOffset = radius * (2f * visibleFraction - scale)
    return if (leftEdge) {
        signedOffset
    } else {
        boardWidth - signedOffset
    }
}


/**
 * 可拖动的悬浮球。[center] 为球心（容器内坐标），拖动经 [onCenterChange] 回写；
 * 轻点经 [onClick]（展开/收起快捷条），长按经 [onLongClick]（按当前状态刷新/重试）。
 * [pressFeedback] 在按下时回调（恢复完整显示），[pressSettle] 在全部指针抬起时
 * 回调（解除占用，空闲计时从头再凑）。[onDragEnd] 在拖动收尾（抬指或取消）时
 * 回调，入参是松手时的球心（含 clamp，与最后一帧 onCenterChange 写回的值相同）。
 * 手势分工：`detectDragGestures` 越过触摸阈值才接管（[onDragStart] 同时收起快捷条；
 * 条在退场动画期间会短暂跟随球心移动，随淡出消失），轻点/长按落到 combinedClickable——与 clickable 同底座，
 * 保留涟漪与 OnClick/OnLongClick 两条无障碍语义（长按刷新在 TalkBack 可达）。
 * 手势层挂在球体**内部**的圆形 Box 上：挂在外层矩形边界上涟漪会按矩形铺开。
 * 球体绘制在 WebView 之上，命中球体的指针事件不会再落到页面里，页面不会被误滚动。
 *
 * 收边形态由 [visualOffsetX]/[visualScale] 表达：调用方在渲染外层
 * 单独算好"期望显示位置相对球心的偏移"与体态，球对收边无语义感知 —— 拖动写回
 * 的还是原始球心、无障碍名称与点击行为完全不变，收下看起来只是"球滚到边上"。
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
    // 拖动终结回调：入参是松手位置（球心坐标，含 clamp）。只在真拖动（越过
    // 触摸阈值）后触发 —— 短按/轻点走不到这里，点按语义不受影响。
    onDragEnd: (Offset) -> Unit = {},
    onCenterChange: (Offset) -> Unit,
    pressFeedback: () -> Unit = {},
    pressSettle: () -> Unit = {},
    visualOffsetX: () -> Float = { 0f },
    visualScale: () -> Float = { 1f },
    content: @Composable () -> Unit,
) {
    // 手势块内要读**当前**球心，不能用捕获到的快照：pointerInput 的块只在 key 变化时重启，
    // 捕获值会停留在手势开始之前 —— 表现为每帧都从旧位置重算，拖动"拖一下就弹回去"。
    val currentCenter by rememberUpdatedState(center)
    val ballFill = MaterialTheme.colorScheme.surface
    val ballRim = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            // 收边偏移乘在**偏移**上：视口偏移与球心(几何)各自独立，消费方用 visual
            // lambda 表达"这一帧应该显示在哪"，球完全不必知道收边策略。
            .offset {
                val scale = visualScale()
                IntOffset(
                    ((center.x + visualOffsetX() - radius * scale)).roundToInt(),
                    (center.y - radius * scale).roundToInt(),
                )
            }
            .graphicsLayer {
                // 体态缩放锚点 = 左上角（同 offset 口径），收边时整体轻缩并保持
                // 圆形；恢复时用同规格回 1，观感与偏移合流成"滑过去收住/弹回来"。
                scaleX = visualScale()
                scaleY = visualScale()
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .size(BALL_SIZE)
            // shadow 在 clip/background 之前且不裁切：投影按圆形轮廓铺开，
            // 球体叠在任意网页内容上都有轮廓分离度。收边时随体态略降，让
            // 半隐进边缘的球看起来"贴进去了"而不是浮在边上。
            .shadow(
                elevation = 6.dp * visualScale(),
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
                        // 拖动终结（抬指或取消）把"松手位置"交给判定层：夹回后的
                        // 最终球心与帧上刚写回的球心严格相等（onDrag 每帧写
                        // clampBallCenter(acc)），判定输入与最后渲染位之间零跳变。
                        onDragEnd = { onDragEnd(clampBallCenter(acc, board, radius)) },
                        onDragCancel = { onDragEnd(clampBallCenter(acc, board, radius)) },
                    )
                }
                .pointerInput(Unit) {
                    // 按下→抬指全程视为"占用球"：按下即恢复完整显示并重新记账，
                    // 全部指针离开后解除占用（空闲计时随后从头再凑三秒）。与拖动/
                    // 轻点/长按的判定完全正交：本观察器只记账不消费、不抢事件，
                    // 影响不到原有手势语义。
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        pressFeedback()
                        // 等手势收尾：所有指针都抬起（手势被取消也是事件收敛的
                        // 一种，长等同样意味着重置）。手写等待循环：foundation 内
                        // 部同款函数在当前版本下不可直接引用。
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.fastAny { it.pressed }) continue
                            pressSettle()
                            break
                        }
                    }
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
