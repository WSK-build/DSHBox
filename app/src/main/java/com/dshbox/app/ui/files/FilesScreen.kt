package com.dshbox.app.ui.files

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.outlined.NoteAdd
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Check
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Close
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Delete
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Folder
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.MoreVert
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.Archive
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.CreateNewFolder
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.DriveFileMove
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.Download
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.FolderOpen
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.FolderZip
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.GridView
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.InsertDriveFile
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.Refresh
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.Search
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.Sort
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.Upload
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.common.UiText
import com.dshbox.app.common.Constants
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.ui.asString
import com.dshbox.app.runtime.DebUnpackInstaller
import com.dshbox.app.util.ArchiveExtractor
import com.dshbox.app.util.BackgroundOps
import com.dshbox.app.util.ConflictMode
import com.dshbox.app.util.DirectoryListing
import com.dshbox.app.util.FileEntry
import com.dshbox.app.util.FileOps
import com.dshbox.app.util.ImportBatchState
import com.dshbox.app.util.MAX_DIRECTORY_FILES
import com.dshbox.app.util.MoveEngine
import com.dshbox.app.util.GlobalSearch
import com.dshbox.app.util.Layer
import com.dshbox.app.util.isAppManagedAsset
import com.dshbox.app.util.LayerRoots
import com.dshbox.app.util.MoveTask
import com.dshbox.app.util.PathMapper
import com.dshbox.app.util.ProgressListener
import com.dshbox.app.util.RiskLevel
import com.dshbox.app.util.SearchResult
import com.dshbox.app.util.ShareArea
import com.dshbox.app.util.ShareMount
import com.dshbox.app.util.ShareMountsCodec
import com.dshbox.app.util.ShareMountsStore
import com.dshbox.app.util.isUnderRoot
import com.dshbox.app.util.shareAliasOf
import com.dshbox.app.util.shareBaseDirectoryOf
import com.dshbox.app.util.shareBindsOf
import com.dshbox.app.util.shareCrumbsOf
import com.dshbox.app.util.shareNamespaceEntriesOf
import com.dshbox.app.util.shareNamespaceParentOf
import com.dshbox.app.util.normalizedSharePath
import com.dshbox.app.util.entrySubtitle
import com.dshbox.app.util.formatFileSize
import com.dshbox.app.util.guestPathOf
import com.dshbox.app.util.layerOf
import com.dshbox.app.util.queryDisplayName
import com.dshbox.app.util.resolveConflictName
import com.dshbox.app.util.sanitizeFileName
import com.dshbox.app.util.scanDirectory
import com.dshbox.app.ui.files.viewer.FileViewerScreen
import com.dshbox.app.util.viewer.ExternalOpener
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

// 顶部入口合并（加号面板、根视图选择）与拍摄所需的系统交互与组件
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// --- Design tokens 在 FilesCommon.kt（同包直接使用） ---

private enum class SortMode { NAME, TIME, SIZE }

private enum class ViewMode { LIST, GRID }

/**
 * 共享区列表层的条目菜单收敛：挂载目录只留「取消挂载 + 导出」，
 * 内建 DSHBox 只留「导出」；更深的层级按普通文件菜单处理。
 *
 * 该菜单只在列表行上呈现（[FileListRow]）；网格单元没有行菜单，网格视图下的取消挂载
 * 依赖多选操作栏的删除入口（共享区列表层保持可用，见 [SelectionActionBar] 调用处）。
 */
private enum class ShareEntryMenu { MOUNT, BUILT_IN }

/**
 * 顶部加号面板的动作名。集中定义，避免同一个字符串在「风险确认回跳」与
 * 「免确认直通」两条路径上各自拼写而漂移（漂移时表现为点了没反应且无报错）。
 */
internal const val PANEL_ACTION_IMPORT_FILE = "import_file"
internal const val PANEL_ACTION_IMPORT_EXTRACT = "import_extract"
internal const val PANEL_ACTION_IMPORT_IMAGE = "import_image"
internal const val PANEL_ACTION_IMPORT_AUDIO = "import_audio"
internal const val PANEL_ACTION_CAPTURE_PHOTO = "capture_photo"
internal const val PANEL_ACTION_CAPTURE_VIDEO = "capture_video"

/** 面板导入出口的选择器参数：MIME 集合 + 导入模式（普通导入 / 解压导入）。 */
internal data class PanelImportSpec(
    val mimeTypes: List<String>,
    val mode: String,
    /** 选择器初始位置提示；null 表示沿用选择器上次位置（非媒体类入口）。 */
    val initialUri: String? = null,
)

/**
 * 系统选择器可见的媒体分类根。选择器默认停在上次浏览的位置（首次为「最近」），
 * 媒体类入口给出分类根后可直接落到图片 / 音频分类；提示不被接受时回落默认位置。
 */
internal const val MEDIA_ROOT_IMAGES = "content://com.android.providers.media.documents/root/images_root"
internal const val MEDIA_ROOT_AUDIO = "content://com.android.providers.media.documents/root/audio_root"

/**
 * 面板导入动作名 → 选择器参数。风险确认回跳与免确认直通共用这一张映射，
 * 新增导入出口只需在此登记一次。非导入动作返回 null。
 */
internal fun panelImportSpecOf(action: String): PanelImportSpec? = when (action) {
    PANEL_ACTION_IMPORT_FILE -> PanelImportSpec(listOf("*/*"), "file")
    PANEL_ACTION_IMPORT_EXTRACT -> PanelImportSpec(listOf("*/*"), "extract")
    PANEL_ACTION_IMPORT_IMAGE -> PanelImportSpec(listOf("image/*", "video/*"), "file", MEDIA_ROOT_IMAGES)
    PANEL_ACTION_IMPORT_AUDIO -> PanelImportSpec(listOf("audio/*"), "file", MEDIA_ROOT_AUDIO)
    else -> null
}

/** 刷新按钮的最短旋转时长：目录扫描通常只需几毫秒，不设下限则旋转态看不到。 */
private const val REFRESH_MIN_SPIN_MS = 700L

/** 共享区命名空间列表的静默自愈轮询间隔（外部增删目录 / 改清单后免手动刷新）。 */
private const val SHARE_LIST_POLL_MS = 3000L

/** 内容类型 → 文件后缀；仅用于给兜底文件名补后缀，取不到就不补。 */
private fun extensionForMime(mime: String?): String? = mime
    ?.substringBefore(';')
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }

/** 分享用的 MIME 类型：按扩展名粗分，未知一律二进制流。 */
internal fun shareMimeTypeOf(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> "image/*"
        "mp4", "mkv", "mov", "webm", "3gp" -> "video/*"
        "mp3", "wav", "ogg", "m4a", "flac", "aac" -> "audio/*"
        "pdf" -> "application/pdf"
        "txt", "log", "md", "json", "xml", "kt", "java", "js", "sh", "py", "yml", "yaml" -> "text/plain"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

/** 媒体选择器入参：MIME 过滤 + 初始位置提示。 */
internal class MediaPickRequest(val mimeTypes: List<String>, val initialUri: String?)

/**
 * 带初始位置提示的多选文件选择器。除多带一个初始位置外，与
 * [ActivityResultContracts.OpenMultipleDocuments] 的意图构造与结果解析口径一致。
 */
private class OpenMediaDocuments : ActivityResultContract<MediaPickRequest, List<Uri>>() {

    override fun createIntent(context: Context, input: MediaPickRequest): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, input.mimeTypes.toTypedArray())
            .apply {
                input.initialUri?.let {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
                }
            }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        return if (clip != null) {
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        } else {
            listOfNotNull(intent.data)
        }
    }
}

/**
 * 带初始位置的目录选择器（挂载系统文件夹）。与 [ActivityResultContracts.OpenDocumentTree]
 * 的意图构造与结果解析口径一致，额外给出初始位置：部分 ROM 不带该 extra 时会落到空列表。
 * 初始位置指向内部存储树，用户取消时 [parseResult] 返回 null 由调用方静默处理。
 */
private class OpenTreeAtInitialLocation : ActivityResultContract<Uri?, Uri?>() {

    override fun createIntent(context: Context, input: Uri?): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (input != null) {
                // EXTRA_INITIAL_URI 是 DocumentsContract 上的公开常量（Intent 上无此名字）。
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input)
            }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * 目录选择器的初始位置：内部存储树（`primary:`）。
 *
 * [DocumentsContract.buildTreeDocumentUri] 只做字符串拼接，并不校验 authority 是否
 * 被系统识别，故 [runCatching] 实际几乎不触发；该包裹仅为防御个别 ROM 的
 * 契约实现抛出异常，返回 null 时选择器退回系统默认落点。
 */
private fun internalStorageTreeUri(): Uri? = runCatching {
    DocumentsContract.buildTreeDocumentUri(ShareArea.EXTERNAL_STORAGE_AUTHORITY, "primary:")
}.getOrNull()

/** 待导入的单个文件。 */
private data class PendingImport(val uri: Uri, val targetDir: File, val baseName: String)

/** 待合并的解压结果（已解压到临时目录，冲突确认后并入目标目录）。 */
private data class PendingMerge(val extractedDir: File, val targetDir: File)

/**
 * 挂载系统文件夹的校验结果：磁盘校验在 IO 线程完成后回传，通过时给出可挂载的宿主目录，
 * 未通过时给出既有提示文案 key。清单的读改写与落盘统一交给 [saveMounts] 在互斥锁内完成。
 */
private sealed interface MountValidation {
    data class Valid(val dir: File) : MountValidation

    data class Failure(val messageRes: Int) : MountValidation
}

/**
 * 挂载清单的修改意图（纯函数返回，便于在 IO 线程与互斥锁内计算）：
 * [Save] 携带要落盘的新清单，[Reject] 用既有文案 key 说明本次修改被拒的原因。
 */
private sealed interface MountUpdate {
    data class Save(val mounts: List<ShareMount>) : MountUpdate

    data class Reject(val messageRes: Int) : MountUpdate
}

/**
 * 把 [dir] 加入 [current] 清单的纯计算：生成不冲突别名，并拒绝与内建 DSHBox 或既有挂载
 * 「相等 / 互相包含」的目录（避免落点遮蔽，以及在父挂载视图内删除子挂载宿主目录这类跨挂载误删）。
 * 用规范化路径比较（软链 / 大小写别名归一），物理同一性才作数。
 */
private fun mountUpdateFor(dir: File, current: List<ShareMount>): MountUpdate {
    val target = normalizedSharePath(dir)
    val builtIn = normalizedSharePath(ShareArea.hostRoot())
    val exact = target == builtIn || current.any { normalizedSharePath(File(it.path)) == target }
    val overlaps = exact ||
        containOrEqual(builtIn, target) || containOrEqual(target, builtIn) ||
        current.any { mount ->
            val existing = normalizedSharePath(File(mount.path))
            containOrEqual(existing, target) || containOrEqual(target, existing)
        }
    if (overlaps) {
        return MountUpdate.Reject(
            if (exact) R.string.files_mount_duplicate else R.string.files_mount_overlap,
        )
    }
    // 卷根别名：主存储根用 "Storage"，避免显示为 "0" 这类无意义名称。
    val rawName = if (dir.absolutePath == ShareArea.primaryRootPath()) "Storage" else dir.name
    val name = ShareMountsCodec.uniqueName(rawName, current)
    return MountUpdate.Save(current + ShareMount(name, dir.absolutePath))
}

/**
 * Layer → RiskLevel 映射：NODE/DSH 运行环境层按系统目录级强确认；
 * DSH_DATA 走 DSH 数据文案；WORKSPACE/BASE 直接放行。
 */
internal fun riskLevelOfLayer(layer: Layer): RiskLevel? = when (layer) {
    Layer.SYSTEM_DIR, Layer.NODE, Layer.DSH -> RiskLevel.SYSTEM_DIR
    Layer.DSH_DATA -> RiskLevel.DSH_DATA
    Layer.WORKSPACE, Layer.BASE -> null
}

@Composable
fun FilesScreen(
    modifier: Modifier = Modifier,
    isActiveTab: Boolean = true,
    /** 外部应用分享/打开进来的待导入清单：非空即弹目标目录选择器决定落点。 */
    inboundShareItems: List<InboundShareItem>? = null,
    onInboundShareConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Layered runtime: the L0 base layer is the sandbox rootfs (the terminal also
    // resolves it this way). A legacy single-bundle "debian" dir is only a
    // fallback for old installs.
    val legacyDebian = File(context.filesDir, "runtime/runtime-current/debian")
    val sandboxRoot = remember {
        File(context.filesDir, "runtime/runtime-current/base").takeIf { it.isDirectory }
            ?: legacyDebian.also { it.parentFile?.mkdirs() }
    }
    val workspaceRoot = remember { File(context.filesDir, "user-data") }
    // 共享区内建目录宿主根（Android 公共存储 DSHBox）；读写能力经 ShareArea 判定。
    val shareRoot = remember { ShareArea.hostRoot() }
    // 共享区命名空间根（虚拟目录，不对应宿主路径）：其下为内建 DSHBox 与用户挂载目录。
    val shareNamespace = remember { File(Constants.SHARE_AREA_GUEST_DIR) }
    // L1 node / L2 dsh layers are bound into the guest at /usr/local and
    // /opt/dshapp/runtime; mirror those binds so the file manager shows the
    // complete virtual-system tree (base + node + dsh + user-data).
    val nodeLayer = remember { File(context.filesDir, "runtime/runtime-current/node").takeIf { it.isDirectory } }
    val dshLayer = remember { File(context.filesDir, "runtime/runtime-current/dsh").takeIf { it.isDirectory } }
    val mapper = remember { PathMapper(sandboxRoot, workspaceRoot, nodeLayer, dshLayer, shareRoot) }
    /** 统一层判定的物理根。 */
    val layerRoots = remember { LayerRoots(mapper) }
    // 沙盒运行中标记：NODE/DSH 层移动文案追加停机建议
    val app = LocalContext.current.applicationContext as DshApp
    val sandboxState by app.container.sandboxManager.sandboxState.collectAsState()
    val sandboxRunning = sandboxState == SandboxState.RUNNING

    var rootMode by remember { mutableIntStateOf(0) }
    val root = when (rootMode) {
        1 -> workspaceRoot
        2 -> shareNamespace
        else -> sandboxRoot
    }
    val rootName = when (rootMode) {
        1 -> stringResource(R.string.files_root_workspace)
        2 -> stringResource(R.string.files_root_share)
        else -> stringResource(R.string.files_root_sandbox)
    }
    /** 面包屑根名：工作区与共享区均附注 guest 侧路径（样式一致）。 */
    val rootLabel = when (rootMode) {
        1 -> rootName + "  /root/projects"
        2 -> rootName + "  " + Constants.SHARE_AREA_GUEST_DIR
        else -> rootName
    }
    /**
     * 共享区就绪（已授权且 DSHBox 目录可用）；未就绪时共享区视图显示开启面板。
     * 初值取保守的 false：判定需要磁盘 stat，不能在组合期做；由 [syncShareReady]
     * 在进入共享区视图 / 页签回到前台时异步校正。
     */
    var shareReady by remember { mutableStateOf(false) }
    /** 用户挂载的共享区目录（guest 别名 ↔ 宿主路径）；命名空间层列在内建 DSHBox 之后。 */
    var shareMounts by remember { mutableStateOf<List<ShareMount>>(emptyList()) }
    /**
     * 挂载清单「读 → 改 → 写」的互斥锁：四类写操作（新增挂载 / 取消挂载 / 多选删除分流 /
     * 清空共享区）共用，避免快速连续操作时各自读到同一份旧清单而互相覆盖（丢更新）。
     */
    val mountsLock = remember { Mutex() }
    /**
     * 共享区命名空间条目（内建 DSHBox + 用户挂载；与 guest 绑定同口径）：
     * 由列表刷新与授权同步时更新，界面列举、面包屑、落点判定、guest 路径映射共用同一份，
     * 避免「列表有、面包屑解析不到」这类口径分叉。
     */
    var shareEntries by remember { mutableStateOf<List<Pair<String, File>>>(emptyList()) }
    /** 待确认取消挂载的条目：展示名 → 挂载别名。 */
    var pendingUnmount by remember { mutableStateOf<Pair<String, String>?>(null) }
    /** 待确认「清空共享区」（删除内建 DSHBox 内容）标记。 */
    var pendingShareClear by remember { mutableStateOf(false) }
    /**
     * 应用是否处于前台（ON_RESUME 之后、ON_PAUSE 之前）。共享区列表轮询据此门控：
     * 退到后台时停止唤醒，回到前台由 effect 重启恢复轮询。
     */
    val resumedAtComposition =
        LocalLifecycleOwner.current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    var appResumed by remember { mutableStateOf(resumedAtComposition) }

    var currentDir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    /** 当前目录的文件数是否超过上限被截断（目录不受限）；仅在真的截断时提示。 */
    var listTruncated by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    /** 切换 rootMode 时携带的目标目录（搜索结果跨根跳转等场景），避免被根重置覆盖。 */
    var pendingNavigateDir by remember { mutableStateOf<File?>(null) }

    // 通用文件查看器（只持 logicalPath 字符串，Tab 切回不影响查看器状态）
    var viewerLogicalPath by remember { mutableStateOf<String?>(null) }

    // 全局搜索
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // 对话框状态
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    /** 风险确认后回跳的新建类型（folder / file），避免 confirmRisk 写死回文件夹弹窗。 */
    var pendingNewKind by remember { mutableStateOf("folder") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showExportChoice by remember { mutableStateOf(false) }
    /** 顶部加号唤出的底部面板：新建、导入、导出与拍摄的统一入口。 */
    var showPanel by remember { mutableStateOf(false) }
    /** 左上角根视图选择面板（沙盒文件 / 工作区 / 共享区）。 */
    var showRootMenu by remember { mutableStateOf(false) }
    /** 仅点击刷新按钮时置位的旋转态，目录扫描结束即复位。 */
    var refreshing by remember { mutableStateOf(false) }
    /** 拍摄的二级选择框（拍照 / 录像）。 */
    var showCaptureChoice by remember { mutableStateOf(false) }
    /** 相机输出的落点物理文件与待启动意图：取消拍摄时按落点清理可能已建出的空文件。 */
    var pendingCaptureFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureIntent by remember { mutableStateOf<Intent?>(null) }
    /** 风险确认通过后待执行的面板出口（launcher 声明在后方，故经状态转交）。 */
    var pendingPanelAction by remember { mutableStateOf<String?>(null) }
    /** 外部分享/打开进来：待导入批次、选择器显隐，以及门禁通过后的待执行单元。 */
    var showInboundPicker by remember { mutableStateOf(false) }
    var pendingInboundItems by remember { mutableStateOf<List<InboundShareItem>>(emptyList()) }
    var pendingInboundCommit by remember { mutableStateOf<InboundCommit?>(null) }
    /** 落点门禁通过后的写入触发（自增即执行；入口函数声明在后方，故经状态转交）。 */
    var inboundWriteTrigger by remember { mutableIntStateOf(0) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var pendingMerge by remember { mutableStateOf<PendingMerge?>(null) }
    var showRiskDialog by remember { mutableStateOf(false) }
    var pendingRiskEntry by remember { mutableStateOf<FileEntry?>(null) }
    var riskAction by remember { mutableStateOf("") }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(ProgressUi()) }
    var progressJob by remember { mutableStateOf<Job?>(null) }

    // 导入参数（launcher 回调前暂存）
    var importMode by remember { mutableStateOf("file") } // file | extract
    // 收尾（用户需求）：「从安卓导入」多选——OpenMultipleDocuments 批处理。
    // 队列逐件串行导入（冲突弹窗逐件决策），全部完成或取消后清理；取消=中止整个批。
    var importQueue by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var importQueueTarget by remember { mutableStateOf<File?>(null) }
    var importQueueMode by remember { mutableStateOf("file") }
    // 计数/取消/汇总判定收敛为纯状态机（util/ImportBatchState，含单测）
    var importBatchState by remember { mutableStateOf(ImportBatchState(total = 0)) }
    /** 批驱动器触发（launcher 回调 +1）；导出出口经 [importBatchDone] 回传单件完成信号。 */
    var importTrigger by remember { mutableIntStateOf(0) }
    var importBatchDone by remember { mutableStateOf<kotlinx.coroutines.CompletableDeferred<Boolean>?>(null) }
    var importTargetDir by remember { mutableStateOf<File?>(null) }

    // ---------------- 基础操作 ----------------

    fun showToast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun showToastRes(resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()

    fun showError(msg: String) {
        errorMessage = msg
        showErrorDialog = true
    }

    fun setProgress(ui: ProgressUi) {
        progress = ui
    }

    fun showProgress(stage: UiText, done: Long = 0L, total: Long = -1L) {
        progress = ProgressUi(active = true, stage = stage, done = done, total = total)
    }

    fun clearProgress() {
        progress = ProgressUi()
    }

    fun cancelProgressJob() {
        progressJob?.cancel()
        progressJob = null
    }

    /** 共享区落点根（内建 DSHBox 或某个挂载目录）；[dir] 不在任何落点下时返回 null。 */
    fun shareBaseOf(dir: File): File? = shareBaseDirectoryOf(dir, shareEntries)

    /** 共享区物理路径 → guest 路径（内建 DSHBox 与用户挂载同一口径）；不在共享区返回 null。 */
    fun shareGuestPathOf(physical: File): String? {
        val base = shareBaseOf(physical) ?: return null
        // 别名反查与落点判定同口径（最深匹配），见 [shareAliasOf]。
        val alias = shareAliasOf(base, shareEntries) ?: return null
        val rel = physical.absolutePath
            .removePrefix(base.absolutePath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
        return if (rel.isEmpty()) {
            "${Constants.SHARE_AREA_GUEST_DIR}/$alias"
        } else {
            "${Constants.SHARE_AREA_GUEST_DIR}/$alias/$rel"
        }
    }

    /** 共享区列表层：该层在 Debian 内没有真实目录，写入类入口整行不显示（仅共享区列表层生效）。 */
    val atShareListing = rootMode == 2 && currentDir.absolutePath == shareNamespace.absolutePath

    /** 命名空间层不是真实落点；写入入口保持可用，落点仍是当前目录（不隐藏、不改道、不置灰）。 */

    /** 命名空间层的挂载条目 → 挂载别名（把「删除」转义为「取消挂载」）；非挂载条目返回 null。 */
    fun namespaceMountNameOf(entry: FileEntry): String? {
        if (rootMode != 2 || currentDir.absolutePath != shareNamespace.absolutePath) return null
        return shareMounts.firstOrNull { it.name == entry.name }?.name
    }

    /** 命名空间层的内建 DSHBox 条目（删除需走「清空共享区」确认）。 */
    fun namespaceShareEntry(entry: FileEntry): Boolean =
        rootMode == 2 && currentDir.absolutePath == shareNamespace.absolutePath &&
            entry.logicalPath == shareRoot.absolutePath

    /** 命名空间层条目不可重命名/移动：会改到宿主目录本身，并让挂载/内建映射悬空。 */
    fun namespaceEntryLocked(entry: FileEntry): Boolean =
        rootMode == 2 && currentDir.absolutePath == shareNamespace.absolutePath

    /** 返回键的上溯目标：共享区在落点根处回到命名空间，不会逃出去看到整个公共存储。 */
    fun parentDirOf(dir: File): File {
        if (rootMode != 2) return dir.parentFile ?: root
        return shareNamespaceParentOf(dir, shareNamespace, shareEntries)
    }

    /**
     * 重新扫描当前目录。[onDone] 供「点击刷新按钮」这一条路径复位旋转态，
     * 其余调用方不传，行为与原先一致。
     */
    fun refreshEntries(onDone: (() -> Unit)? = null) {
        val dir = currentDir
        val isTop = rootMode == 0 && dir.absolutePath == sandboxRoot.absolutePath
        scope.launch {
            try {
                val all = if (dir.absolutePath == shareNamespace.absolutePath) {
                    // 命名空间层是虚拟目录（该路径不可能是宿主真实目录）：只按路径判定，
                    // 列举现取（挂载清单与宿主目录存在性可能被外部改变），并同步给面包屑与落点判定。
                    // 清单读取、宿主目录 stat 与两套组装都是磁盘 IO，合并成一个 IO 段在后台线程完成。
                    val snapshot = withContext(Dispatchers.IO) {
                        val mounts = ShareMountsStore.load(context)
                        val builtIn = ShareArea.hostRoot().takeIf { it.isDirectory }
                        Triple(mounts, shareBindsOf(builtIn, mounts), shareNamespaceEntriesOf(builtIn, mounts))
                    }
                    // 展示列表含清单中宿主目录已失效的挂载（unavailable）：仍可见、仍可取消挂载；
                    // shareEntries（供面包屑 / 落点 / 选择器用）保持只含可用条目。
                    val (mounts, fresh, listing) = snapshot
                    // 与文件其余部分口径一致：先过陈旧性检查，再回写状态，避免把已切走
                    // 目录时的旧结果写进 shareMounts / shareEntries。
                    if (dir.absolutePath != currentDir.absolutePath) return@launch
                    shareMounts = mounts
                    shareEntries = fresh
                    DirectoryListing(listing.map { it.first }, truncated = false)
                } else {
                    withContext(Dispatchers.IO) { scanDirectory(dir, mapper, isTop) }
                }
                if (dir.absolutePath != currentDir.absolutePath) return@launch
                listTruncated = all.truncated
                entries = all.entries.sortedWith(
                    when (sortMode) {
                        SortMode.NAME -> compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
                        SortMode.TIME -> compareBy<FileEntry> { !it.isDirectory }.thenByDescending { it.lastModified }
                        SortMode.SIZE -> compareBy<FileEntry> { !it.isDirectory }
                            .thenByDescending { if (!it.isDirectory) it.size else 0L }
                    },
                )
            } finally {
                onDone?.invoke()
            }
        }
    }

    /**
     * 挂载清单统一写入口：在互斥锁内完成「读最新清单 → 计算修改 → 落盘 → 状态回写」。
     *
     * 四类写操作（新增挂载、取消挂载、多选删除分流、清空共享区）共用本入口，
     * 消除快速连续操作时各读各的旧清单造成的丢更新；磁盘读写与计算都在 IO 线程，
     * 状态回写与提示在主线程。落盘失败时不动内存状态，避免「界面已改、重启后回退」。
     *
     * 约束：`build` 必须是**纯函数**（只做清单数据计算，不做磁盘 IO、不发提示、不改界面状态），
     * 且**禁止在其内部再次调用 [saveMounts]** —— 本入口在互斥锁内执行 `build`，`Mutex` 不可重入，
     * 嵌套调用会自锁住当前协程（表现为该次操作永久无响应）。需要串行化的多步写入应合并进一次
     * `build`，或在锁外串行编排。
     *
     * @param build 以磁盘最新清单为输入，返回本次修改：[MountUpdate.Save] 落盘，
     *   [MountUpdate.Reject] 按既有文案 key 提示且不改状态。
     * @param onSaved 落盘成功后回调（携带新清单），供各入口补充自己的成功提示。
     */
    fun saveMounts(build: (List<ShareMount>) -> MountUpdate, onSaved: (List<ShareMount>) -> Unit = {}) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                mountsLock.withLock {
                    // 取消必须继续上抛：被当作失败吞掉时，取消会退化成一句「保存失败」提示，
                    // 且取消后仍会回写内存状态并刷新列表。只兜底 build 的异常，
                    // 落盘失败（返回 false）仍按既有口径提示。
                    val update = try {
                        build(ShareMountsStore.load(context))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        return@withLock MountUpdate.Reject(R.string.files_mount_save_failed)
                    }
                    when (update) {
                        is MountUpdate.Reject -> update
                        is MountUpdate.Save -> if (ShareMountsStore.save(context, update.mounts)) {
                            update
                        } else {
                            MountUpdate.Reject(R.string.files_mount_save_failed)
                        }
                    }
                }
            }
            when (outcome) {
                is MountUpdate.Reject -> showToastRes(outcome.messageRes)
                is MountUpdate.Save -> {
                    shareMounts = outcome.mounts
                    onSaved(outcome.mounts)
                    refreshEntries()
                }
            }
        }
    }

    fun toggleSelect(entry: FileEntry) {
        selectedPaths = if (entry.logicalPath in selectedPaths) selectedPaths - entry.logicalPath
        else selectedPaths + entry.logicalPath
        selectionMode = selectedPaths.isNotEmpty()
    }

    fun exitSelection() {
        selectionMode = false
        selectedPaths = emptySet()
        pendingDeleteTarget = null
    }

    /** 清空共享区：只删内建 DSHBox 的内容，保留目录本身（命名空间条目不会短暂消失）。 */
    fun clearShareArea() {
        scope.launch {
            showProgress(UiText.raw(context.getString(R.string.files_progress_deleting)))
            try {
                withContext(Dispatchers.IO) {
                    shareRoot.listFiles()
                        ?.filterNot { Files.isSymbolicLink(it.toPath()) }
                        ?.forEach { it.deleteRecursively() }
                }
                clearProgress()
                refreshEntries()
            } catch (e: CancellationException) {
                // 取消必须继续上抛：吞掉会让本协程把取消当作正常收尾，清空进度条后
                // 刷新出一个与用户预期不符的列表。
                clearProgress()
                throw e
            }
        }
    }

    /**
     * 同步共享区就绪状态（进入共享区视图 / 授权返回时调用）：
     * 已授权则确保 DSHBox 目录存在（已存在即沿用，绝不清理），就绪后刷新列举。
     *
     * 授权判定、目录就绪与清单加载都是磁盘 IO（`isDirectory` 需要 stat），整段下沉到
     * IO 线程；状态回写前做陈旧性检查（与 [refreshEntries] 同口径），避免并发调用
     * （页签活跃 / 回前台 / 授权返回）时旧快照覆盖新视图、列表短暂回退。
     */
    fun syncShareReady() {
        val expectedDir = currentDir
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val ready = ShareArea.hasAccess(context) && ShareArea.ensureReady()
                val mounts = ShareMountsStore.load(context)
                val builtIn = ShareArea.hostRoot().takeIf { it.isDirectory }
                Triple(ready, mounts, shareBindsOf(builtIn, mounts))
            }
            shareReady = snapshot.first
            if (expectedDir.absolutePath == currentDir.absolutePath) {
                shareMounts = snapshot.second
                shareEntries = snapshot.third
            }
            if (rootMode == 2) refreshEntries()
        }
    }

    // 共享区授权返回（系统「所有文件访问」页 / Android 10 旧式存储弹窗）：同一处收口。
    val shareAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { syncShareReady() }
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { syncShareReady() }

    // 在系统设置页直接撤销授权后回到前台（非经授权 launcher）：共享区视图重新判定就绪态。
    // 同时维护 appResumed：轮询据此在退到后台时停机，避免无谓唤醒。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    appResumed = true
                    if (rootMode == 2) syncShareReady()
                }
                Lifecycle.Event.ON_PAUSE -> appResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** 发起共享区授权：Android 11+ 跳系统「所有文件访问」页；Android 10 走旧式存储权限。 */
    fun requestShareAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${context.packageName}"))
            // 个别 ROM 没有应用专属页面：回退到「所有文件访问」总列表。
            runCatching { shareAccessLauncher.launch(intent) }.onFailure {
                runCatching {
                    shareAccessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            legacyStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    // 「挂载系统文件夹」：SAF 目录选择 → 公共存储真实路径 → 追加挂载清单（别名重名自动加序号）。
    // 取消（resultCode 非 OK 或未选目录）一律静默返回，不误报为「不支持挂载」。
    // 文档 id 解析、合法性 / 存在性 / 可写性校验都是磁盘 IO，整段下沉到 IO 线程；
    // 清单的读改写与落盘交给 saveMounts（互斥锁内完成），提示与状态回写留在主线程。
    val mountTreeLauncher = rememberLauncherForActivityResult(
        OpenTreeAtInitialLocation(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 挂载前先确认公共存储读写能力：未授权时直接引导授权，否则「挂载成功」后读写全静默失败。
        if (!ShareArea.hasAccess(context)) {
            showToastRes(R.string.files_mount_no_access)
            requestShareAccess()
            return@rememberLauncherForActivityResult
        }
        // 只接受系统外部存储 DocumentsProvider 的树：第三方提供方的 id 语义不同，
        // 强行按卷解析会得到错误路径。
        if (uri.authority != ShareArea.EXTERNAL_STORAGE_AUTHORITY) {
            showToastRes(R.string.files_mount_unsupported)
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val validation = withContext(Dispatchers.IO) {
                // 取消（页面销毁等）必须继续上抛：被当作解析失败吞掉时，用户会收到
                // 一句与操作无关的「不支持挂载」提示。
                val hostPath = try {
                    ShareArea.hostPathOfDocumentId(context, DocumentsContract.getTreeDocumentId(uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val dir = hostPath?.let(::File)
                when {
                    dir == null || !dir.isDirectory ->
                        MountValidation.Failure(R.string.files_mount_unsupported)
                    // 合法性是纯字符串判定，先于有磁盘 IO 的可写性探测执行，省一次无用探测。
                    !ShareMountsCodec.isPlausibleMountPath(dir.absolutePath) ->
                        MountValidation.Failure(R.string.files_mount_unsupported)
                    // 可写性探测：只读目录（含授权缺失、只读卷）提前拦下，避免挂载后写入静默失败。
                    !ShareArea.isWritableDir(dir) ->
                        MountValidation.Failure(R.string.files_mount_not_writable)
                    else -> MountValidation.Valid(dir)
                }
            }
            when (validation) {
                is MountValidation.Failure -> showToastRes(validation.messageRes)
                is MountValidation.Valid -> saveMounts(
                    build = { current -> mountUpdateFor(validation.dir, current) },
                    // 绑定在 PRoot 启动时固定：已运行的 DSH/终端需重启一次才会看到该挂载。
                    onSaved = { showToastRes(R.string.files_mount_added) },
                )
            }
        }
    }

    /** 面板入口：调起系统目录选择器（挂载系统文件夹），初始位置指向内部存储。 */
    fun mountSystemFolder() {
        mountTreeLauncher.launch(internalStorageTreeUri())
    }

    /** 取消挂载：只删记录，绝不触碰宿主目录内容。落盘经 [saveMounts] 走互斥锁与 IO 线程。 */
    fun unmount(alias: String) {
        pendingUnmount = null
        saveMounts(build = { current -> MountUpdate.Save(current.filterNot { it.name == alias }) })
    }

    fun isRiskEntry(entry: FileEntry): Boolean = entry.risk != RiskLevel.NORMAL

    /**
     * 逻辑路径 → 物理层判定的唯一入口（筛选、弹窗、风险级别共用同一判定，
     * 避免两套口径分叉导致文案错配）。
     */
    fun pathLayer(logical: File): Layer = layerOf(mapper.resolvePhysical(logical).absolutePath, layerRoots)

    fun entryLayer(entry: FileEntry): Layer = pathLayer(File(entry.logicalPath))

    /**
     * 条目级「删除/重命名」的操作门禁：按 [entryLayer] 物理层判定——
     * node/dsh 层内条目、嵌套 `.dsh` 内部文件均命中（与全域语义对齐）；
     * 不用 [isRiskEntry] 的名称口径（层内文件返回 NORMAL 会完全静默）。
     * 「打开」仍用名称口径：层内逐目录弹窗会使导航不可用。
     */
    fun isGatedEntry(entry: FileEntry): Boolean = riskLevelOfLayer(entryLayer(entry)) != null

    /**
     * 当前目录的风险级别（系统目录 / 运行环境层 / DSH 内部数据目录），无风险返回 null。
     * 统一收敛到 [layerOf]（段匹配优先 + 前缀归属），删除原视图分支——
     * 行为变化（有意收紧）：工作区下嵌套 `.dsh`（如 foo/.dsh）现在也会命中 DSH_DATA。
     */
    fun protectedRiskLevel(dir: File): RiskLevel? = riskLevelOfLayer(pathLayer(dir))

    fun showRisk(entry: FileEntry, action: String) {
        pendingRiskEntry = entry
        riskAction = action
        showRiskDialog = true
    }

    // 移动流程编排（状态与编排函数在 MoveFlow.kt，行为不变）
    val moveFlow = remember {
        MoveFlow(
            context = context,
            scope = scope,
            mapper = mapper,
            layerRoots = layerRoots,
            sandboxRunning = { sandboxRunning },
            currentDir = { currentDir },
            exitSelection = { exitSelection() },
            clearSearch = {
                searchQuery = ""
                searchResults = emptyList()
            },
            refreshEntries = { refreshEntries() },
            showError = { showError(it) },
            showToastRes = { showToastRes(it) },
            showRisk = { entry, action -> showRisk(entry, action) },
            cancelProgressJob = { cancelProgressJob() },
            registerProgressJob = { progressJob = it },
            currentProgressJob = { progressJob },
            showProgressUi = { progress = it },
            clearProgress = { progress = ProgressUi() },
        )
    }

    fun confirmRisk() {
        val entry = pendingRiskEntry
        when (riskAction) {
            "open" -> if (entry != null) {
                currentDir = File(entry.logicalPath)
                searchQuery = ""
                searchResults = emptyList()
            }
            "delete" -> showDeleteConfirm = true
            "rename" -> if (renameTarget != null) showRenameDialog = true
            "write" -> if (pendingNewKind == "file") showNewFileDialog = true else showNewFolderDialog = true
            // 面板各导入与拍摄出口：风险确认通过后转交下方统一执行
            PANEL_ACTION_IMPORT_FILE, PANEL_ACTION_IMPORT_EXTRACT, PANEL_ACTION_IMPORT_IMAGE,
            PANEL_ACTION_IMPORT_AUDIO, PANEL_ACTION_CAPTURE_PHOTO, PANEL_ACTION_CAPTURE_VIDEO ->
                pendingPanelAction = riskAction
            // 外部分享/打开的落点确认：通过后触发出入口执行
            "inbound" -> inboundWriteTrigger++
            // 源侧风险强确认通过后进入目标选择器
            "move" -> moveFlow.onMoveRiskConfirmed()
        }
        pendingRiskEntry = null
        riskAction = ""
        // 必须关闭风险弹窗，否则确认后弹窗残留导致屏幕锁死、二级弹窗叠加
        showRiskDialog = false
    }

    fun cancelRisk() {
        // 外部分享导入的强确认被取消：只丢这一份待执行单元，并明确告知未导入；
        // 弹窗期间新到的批次仍在待选里，重新弹选择器让它继续处理。
        if (riskAction == "inbound") {
            pendingInboundCommit = null
            showToastRes(R.string.files_progress_cancelled)
            if (pendingInboundItems.isNotEmpty()) showInboundPicker = true
        }
        pendingRiskEntry = null
        riskAction = ""
        showRiskDialog = false
    }

    /** 共享区面包屑（与落点选择器共用同一构造）；样式与工作区一致。 */
    fun shareCrumbs(): List<Pair<String, File>> =
        shareCrumbsOf(currentDir, shareNamespace, rootLabel, shareEntries)

    /**
     * 导航到任意逻辑目录（含搜索结果跨根跳转）：按目标所在根自动切换视图，
     * 若需切换 rootMode 则携带 [pendingNavigateDir]，避免被根重置逻辑覆盖。
     */
    fun navigateToDir(logicalDir: File) {
        val path = logicalDir.absolutePath
        val targetMode = when {
            isUnderRoot(workspaceRoot, path) -> 1
            shareBaseOf(logicalDir) != null -> 2
            else -> 0
        }
        if (rootMode == targetMode) {
            currentDir = logicalDir
        } else {
            pendingNavigateDir = logicalDir
            rootMode = targetMode
        }
        searchQuery = ""
        searchResults = emptyList()
        refreshEntries()
    }

    // ---------------- 新建 / 重命名 / 删除 ----------------

    /** 物理路径是否属于我方只读资产（`.dsh/dshbox`）。 */
    fun isProtectedAsset(logicalPath: String): Boolean =
        isAppManagedAsset(mapper.resolvePhysical(File(logicalPath)).absolutePath, mapper.workspaceRoot)

    fun createNewFolder() {
        // 我方固定资产目录（.dsh/dshbox）对用户只读：写操作在 UI 层直接挡掉，
        // 给一句明确提示（否则只会看到内核权限失败后的通用报错）。
        if (isProtectedAsset(currentDir.absolutePath)) {
            showToastRes(R.string.files_dshbox_readonly)
            newFolderName = ""
            showNewFolderDialog = false
            return
        }
        val safeName = sanitizeFileName(newFolderName)
        if (safeName == null) {
            showToastRes(R.string.files_name_invalid)
            newFolderName = ""
            showNewFolderDialog = false
            return
        }
        val physical = mapper.resolvePhysical(currentDir)
        val created = runCatching {
            val target = File(physical, safeName)
            if (target.exists()) {
                showToastRes(R.string.files_rename_conflict)
                // 清空输入便于重输，弹窗保留
                newFolderName = ""
                return
            }
            target.mkdirs()
        }
        created.onFailure { showError(it.message ?: context.getString(R.string.files_create_failed)) }
        // mkdirs 失败返回 false 而不抛异常（如目标不可写）：补一条与异常同口径的提示。
        if (created.getOrNull() == false) showToastRes(R.string.files_create_failed)
        newFolderName = ""
        showNewFolderDialog = false
        refreshEntries()
    }

    fun createNewFile() {
        // 与新建文件夹同门禁链：只读资产直接拦截，给明确提示。
        if (isProtectedAsset(currentDir.absolutePath)) {
            showToastRes(R.string.files_dshbox_readonly)
            newFileName = ""
            showNewFileDialog = false
            return
        }
        val safeName = sanitizeFileName(newFileName)
        if (safeName == null) {
            showToastRes(R.string.files_name_invalid)
            newFileName = ""
            showNewFileDialog = false
            return
        }
        // 后缀不约束：用户输入含后缀原样生成；无后缀（或以点结尾）统一补 .txt。
        val finalName = if ('.' !in safeName || safeName.endsWith(".")) {
            safeName.trimEnd('.') + ".txt"
        } else {
            safeName
        }
        val physical = mapper.resolvePhysical(currentDir)
        val created = runCatching {
            val target = File(physical, finalName)
            if (target.exists()) {
                showToastRes(R.string.files_rename_conflict)
                // 清空输入便于重输，弹窗保留
                newFileName = ""
                return
            }
            target.createNewFile()
        }
        created.onFailure { showError(it.message ?: context.getString(R.string.files_create_failed)) }
        // createNewFile 失败返回 false 而不抛异常（如目标不可写）：补一条与异常同口径的提示。
        if (created.getOrNull() == false) showToastRes(R.string.files_create_failed)
        newFileName = ""
        showNewFileDialog = false
        refreshEntries()
    }

    fun doRename() {
        val entry = renameTarget ?: return
        if (isProtectedAsset(entry.logicalPath)) {
            showToastRes(R.string.files_dshbox_readonly)
            renameTarget = null
            renameName = ""
            showRenameDialog = false
            return
        }
        val safeNewName = sanitizeFileName(renameName)
        if (safeNewName == null) {
            showToastRes(R.string.files_name_invalid)
            renameTarget = null
            renameName = ""
            showRenameDialog = false
            return
        }
        val src = mapper.resolvePhysical(File(entry.logicalPath))
        val parent = src.parentFile
        if (parent == null) {
            showError(context.getString(R.string.files_rename_failed, context.getString(R.string.files_path_error)))
            renameTarget = null
            renameName = ""
            showRenameDialog = false
            return
        }
        val dest = File(parent, safeNewName)
        if (dest.exists()) {
            showToastRes(R.string.files_rename_conflict)
            renameTarget = null
            renameName = ""
            showRenameDialog = false
            return
        }
        // 重命名收敛为「同目录移动」统一走 moveWithin，
        // renameTo 失败自动走复制兜底（含 rwx/时间戳同步），修复 1.1.1 失败无兜底问题
        cancelProgressJob()
        progressJob = scope.launch {
            val self = currentCoroutineContext()[Job]
            showProgress(UiText.raw(context.getString(R.string.files_progress_moving, entry.name)))
            try {
                val result = withContext(Dispatchers.IO) {
                    MoveEngine.moveWithin(listOf(MoveTask(src, dest)), listener = null)
                }
                clearProgress()
                val failure = result.failed.firstOrNull()
                // moveWithin 取消时返回 cancelled=true 且 failed 为空，
                // 必须先判取消，否则取消会被误报为「重命名成功」
                when {
                    result.cancelled -> showToastRes(R.string.files_progress_cancelled)
                    failure != null -> showError(context.getString(R.string.files_rename_failed, failure.message.asString(context)))
                    else -> showToast(context.getString(R.string.files_rename_done, entry.name, safeNewName))
                }
            } catch (e: CancellationException) {
                clearProgress()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                clearProgress()
                showError(context.getString(R.string.files_rename_failed, e.message ?: context.getString(R.string.error_unknown)))
            } finally {
                renameTarget = null
                renameName = ""
                showRenameDialog = false
                exitSelection()
                refreshEntries()
            }
        }
    }

    fun doDelete() {
        val single = pendingDeleteTarget
        val targets = if (single != null) listOf(single) else entries.filter { it.logicalPath in selectedPaths }
        if (targets.isEmpty()) return
        // 命名空间层的挂载条目：删除 = 取消挂载（只删记录），绝不递归删除宿主目录；
        // 该分流覆盖列表行菜单、多选操作栏与网格勾选三条入口（不依赖单一入口的转义）。
        val mountTargets = targets.filter { namespaceMountNameOf(it) != null }
        if (mountTargets.isNotEmpty()) {
            val names = mountTargets.mapNotNull { namespaceMountNameOf(it) }.toSet()
            // 取消挂载只删记录，落盘经统一写入口（互斥锁 + IO 线程）；失败时入口内提示且不改内存状态。
            saveMounts(build = { current -> MountUpdate.Save(current.filterNot { it.name in names }) })
        }
        val fileTargets = targets - mountTargets.toSet()
        if (fileTargets.isEmpty()) {
            showDeleteConfirm = false
            pendingDeleteTarget = null
            exitSelection()
            refreshEntries()
            return
        }
        if (fileTargets.any { isProtectedAsset(it.logicalPath) }) {
            showToastRes(R.string.files_dshbox_readonly)
            showDeleteConfirm = false
            pendingDeleteTarget = null
            exitSelection()
            refreshEntries()
            return
        }
        cancelProgressJob()
        progressJob = scope.launch {
            val self = currentCoroutineContext()[Job]
            showProgress(UiText.raw(context.getString(R.string.files_progress_deleting)))
            try {
                withContext(Dispatchers.IO) {
                    fileTargets.forEach { t ->
                        mapper.resolvePhysical(File(t.logicalPath)).deleteRecursively()
                    }
                }
                clearProgress()
                showDeleteConfirm = false
                pendingDeleteTarget = null
                exitSelection()
                refreshEntries()
            } catch (e: CancellationException) {
                clearProgress()
                // L2: 取消后刷新，反映可能已删除的部分
                refreshEntries()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                clearProgress()
                showDeleteConfirm = false
                pendingDeleteTarget = null
                exitSelection()
                // 删除异常——FileOpException 优先展示可本地化 uiText。
                val detail = if (e is com.dshbox.app.util.FileOpException) {
                    e.uiText?.asString(context)
                        ?: e.message?.let { com.dshbox.app.common.UiText.raw(it).asString(context) }
                } else {
                    e.message
                }
                showError(detail ?: context.getString(R.string.files_delete_failed))
            }
        }
    }

    // ---------------- 导入 ----------------

    fun runImport(uri: Uri, targetDir: File, baseName: String, mode: ConflictMode) {
        cancelProgressJob()
        progressJob = scope.launch {
            val self = currentCoroutineContext()[Job]
            val physicalTarget = mapper.resolvePhysical(targetDir)
            val finalName = resolveConflictName(physicalTarget, baseName, mode)
            showProgress(UiText.raw(context.getString(R.string.files_progress_importing, baseName)), 0, -1)
            try {
                if (finalName == null) {
                    clearProgress()
                    showToastRes(R.string.files_conflict_skipped)
                    importBatchDone?.complete(true)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    FileOps.importFromUri(
                        context, uri, physicalTarget, finalName,
                        listener = ProgressListener { done, total, _ ->
                            setProgress(ProgressUi(true, UiText.raw(context.getString(R.string.files_progress_importing, finalName)), done, total))
                        },
                    )
                }
                clearProgress()
                refreshEntries()
                showToast(context.getString(R.string.files_import_done_name, finalName))
                importBatchDone?.complete(true)
            } catch (e: CancellationException) {
                // S1: 取消时清理半成品文件；取消=中止整个批
                FileOps.deleteQuietly(File(physicalTarget, finalName ?: baseName))
                clearProgress()
                importQueue = emptyList(); importBatchState = importBatchState.cancel(); importBatchDone?.complete(false)
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                FileOps.deleteQuietly(File(physicalTarget, finalName ?: baseName))
                clearProgress()
                showError(context.getString(R.string.files_import_failed, e.message ?: context.getString(R.string.error_unknown)))
                importBatchDone?.complete(false)
            }
        }
    }

    fun startFileImport(uri: Uri, targetDir: File, baseName: String) {
        val physicalTarget = mapper.resolvePhysical(targetDir)
        if (File(physicalTarget, baseName).exists()) {
            pendingImport = PendingImport(uri, targetDir, baseName)
            showConflictDialog = true
            return
        }
        runImport(uri, targetDir, baseName, ConflictMode.OVERWRITE)
    }

    fun mergeExtracted(extractedDir: File, targetDir: File, mode: ConflictMode) {
        cancelProgressJob()
        progressJob = scope.launch {
            // 合并期间 extractedDir 仍在 cacheDir，登记后台操作
            // 阻止设置页清理并发删除它。begin→end 跨越整个协程（含取消路径）。
            BackgroundOps.begin()
            showProgress(UiText.raw(context.getString(R.string.files_progress_merging)))
            val self = currentCoroutineContext()[Job]
            try {
                val physicalTarget = mapper.resolvePhysical(targetDir)
                withContext(Dispatchers.IO) {
                    // S4: 递归逐文件统一冲突策略，避免覆盖同名目录时静默丢弃其子文件
                    // mergeTree 下沉到 FileOps（叶子节点统一走移动引擎单项逻辑）
                    MoveEngine.mergeTree(extractedDir, physicalTarget, mode)
                    extractedDir.deleteRecursively()
                }
                clearProgress()
                refreshEntries()
                showToastRes(R.string.files_extract_done)
                importBatchDone?.complete(true)
            } catch (e: CancellationException) {
                FileOps.deleteQuietly(extractedDir)
                clearProgress()
                importQueue = emptyList(); importBatchState = importBatchState.cancel(); importBatchDone?.complete(false)
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                FileOps.deleteQuietly(extractedDir)
                clearProgress()
                showError(context.getString(R.string.files_merge_failed, e.message ?: context.getString(R.string.error_unknown)))
                importBatchDone?.complete(false)
            } finally {
                BackgroundOps.end()
            }
        }
    }

    /** 导入压缩包并解压：复制到缓存 → 检测格式 → 解压到临时目录 → 冲突确认 → 合并。 */
    fun startExtractImport(uri: Uri, targetDir: File) {
        cancelProgressJob()
        progressJob = scope.launch {
            // import_*/extract_* 都在 cacheDir，登记后台操作
            // 阻止设置页清理并发删除（切走页后协程仍存活但进度 UI 不可见）。
            BackgroundOps.begin()
            val self = currentCoroutineContext()[Job]
            val physicalTarget = mapper.resolvePhysical(targetDir)
            var tmpArchive: File? = null
            var tmpExtract: File? = null
            showProgress(UiText.raw(context.getString(R.string.files_progress_prepare)))
            try {
                tmpArchive = File(context.cacheDir, "import_${System.currentTimeMillis()}")
                withContext(Dispatchers.IO) {
                    FileOps.importFromUri(context, uri, context.cacheDir, tmpArchive!!.name, null)
                }
                val format = withContext(Dispatchers.IO) { ArchiveExtractor.detectFormat(tmpArchive!!) }
                if (format == null) {
                    // 非压缩包：按普通文件导入到目标目录
                    clearProgress()
                    val displayName = queryDisplayName(context, uri) ?: tmpArchive!!.name
                    val safeName = sanitizeFileName(displayName) ?: "imported-file"
                    val dest = File(physicalTarget, safeName)
                    if (dest.exists()) {
                        pendingImport = PendingImport(uri, targetDir, safeName)
                        withContext(Dispatchers.IO) { tmpArchive!!.delete() }
                        tmpArchive = null
                        showConflictDialog = true
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        val moved = tmpArchive!!.renameTo(dest)
                        if (!moved) {
                            dest.outputStream().use { out -> tmpArchive!!.inputStream().use { it.copyTo(out) } }
                            tmpArchive!!.delete()
                        }
                    }
                    tmpArchive = null
                    refreshEntries()
                    showToast(context.getString(R.string.files_import_done_name, safeName))
                    importBatchDone?.complete(true)
                    return@launch
                }
                tmpExtract = File(context.cacheDir, "extract_${System.currentTimeMillis()}")
                withContext(Dispatchers.IO) {
                    ArchiveExtractor.extract(
                        tmpArchive!!, tmpExtract!!,
                        listener = ProgressListener { done, total, _ ->
                            setProgress(ProgressUi(true, UiText.raw(context.getString(R.string.files_progress_extracting)), done, total))
                        },
                    )
                }
                withContext(Dispatchers.IO) { tmpArchive!!.delete() }
                tmpArchive = null
                // 包内没有唯一顶层目录时（系统压缩软件常如此），
                // 顶层多个条目会直接平铺进目标目录、表现为「全部散开」。
                // 与 7-Zip「解压到文件夹」同口径：顶层不唯一 → 在解压缓存内包一层
                // 以包名命名的目录；后续冲突/合并流程原样复用（tmpExtract 顶层现在唯一）。
                withContext(Dispatchers.IO) {
                    val topLevel = tmpExtract!!.listFiles() ?: emptyArray()
                    if (topLevel.size != 1) {
                        val packageName = queryDisplayName(context, uri)
                            ?.substringBeforeLast('.', "")
                            ?.let { sanitizeFileName(it) }
                            ?: "extracted"
                        val wrapper = File(tmpExtract!!, packageName)
                        wrapper.mkdirs()
                        topLevel.forEach { child ->
                            val dest = File(wrapper, child.name)
                            if (!child.renameTo(dest)) {
                                runCatching {
                                    if (child.isDirectory) child.copyRecursively(dest, overwrite = true)
                                    else child.copyTo(dest, overwrite = true)
                                    child.deleteRecursively()
                                }
                            }
                        }
                    }
                }
                val conflicts = withContext(Dispatchers.IO) {
                    tmpExtract!!.listFiles()?.mapNotNull { child ->
                        if (File(physicalTarget, child.name).exists()) child.name else null
                    } ?: emptyList()
                }
                if (conflicts.isNotEmpty()) {
                    pendingMerge = PendingMerge(tmpExtract!!, targetDir)
                    showConflictDialog = true
                } else {
                    mergeExtracted(tmpExtract!!, targetDir, ConflictMode.OVERWRITE)
                }
            } catch (e: CancellationException) {
                // 取消时清理缓存临时文件与目录；取消=中止整个批
                FileOps.deleteQuietly(tmpArchive)
                FileOps.deleteQuietly(tmpExtract)
                clearProgress()
                importQueue = emptyList(); importBatchState = importBatchState.cancel(); importBatchDone?.complete(false)
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                FileOps.deleteQuietly(tmpArchive)
                FileOps.deleteQuietly(tmpExtract)
                clearProgress()
                showError(context.getString(R.string.files_extract_failed, e.message ?: context.getString(R.string.error_unknown)))
                importBatchDone?.complete(false)
            } finally {
                BackgroundOps.end()
            }
        }
    }

    // ---------------- 导出 ----------------

    fun exportSelectionToTree(treeUri: Uri) {
        if (selectedPaths.isEmpty()) {
            showToastRes(R.string.files_export_none)
            return
        }
        val physicalSelected = selectedPaths.map { mapper.resolvePhysical(File(it)) }
        cancelProgressJob()
        progressJob = scope.launch {
            val self = currentCoroutineContext()[Job]
            showProgress(UiText.raw(context.getString(R.string.files_progress_exporting)))
            try {
                val count = withContext(Dispatchers.IO) {
                    FileOps.exportToTree(
                        context, treeUri, physicalSelected,
                        listener = ProgressListener { done, total, _ ->
                            setProgress(ProgressUi(true, UiText.raw(context.getString(R.string.files_progress_exporting)), done, total))
                        },
                    )
                }
                clearProgress()
                exitSelection()
                refreshEntries()
                showToast(context.resources.getQuantityString(R.plurals.files_export_done_count, count, count))
            } catch (e: CancellationException) {
                clearProgress()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                clearProgress()
                showError(context.getString(R.string.files_export_failed, e.message ?: context.getString(R.string.error_unknown)))
            }
        }
    }

    fun exportSelectionToZip(zipUri: Uri) {
        if (selectedPaths.isEmpty()) {
            showToastRes(R.string.files_export_none)
            return
        }
        val physicalSelected = selectedPaths.map { mapper.resolvePhysical(File(it)) }
        cancelProgressJob()
        progressJob = scope.launch {
            val self = currentCoroutineContext()[Job]
            showProgress(UiText.raw(context.getString(R.string.files_progress_zipping)))
            try {
                val count = withContext(Dispatchers.IO) {
                    FileOps.exportToZip(
                        context, zipUri, physicalSelected,
                        listener = ProgressListener { done, total, _ ->
                            setProgress(ProgressUi(true, UiText.raw(context.getString(R.string.files_progress_zipping)), done, total))
                        },
                    )
                }
                clearProgress()
                exitSelection()
                refreshEntries()
                showToast(context.resources.getQuantityString(R.plurals.files_zip_done_count, count, count))
            } catch (e: CancellationException) {
                clearProgress()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                clearProgress()
                showError(context.getString(R.string.files_export_failed, e.message ?: context.getString(R.string.error_unknown)))
            }
        }
    }


    // ---------------- Launchers ----------------

    /** 选择器回调共用：把选中结果交给逐件串行导入批处理驱动器。 */
    fun onImportPicked(uris: List<Uri>) {
        val targetDir = importTargetDir
        importTargetDir = null
        if (uris.isEmpty() || targetDir == null) return
        importQueue = uris
        importQueueTarget = targetDir
        importQueueMode = importMode
        importBatchState = ImportBatchState(total = uris.size)
        importTrigger++
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> onImportPicked(uris) }

    // 媒体类入口（图像 / 音频）：额外带初始位置提示，直接落到对应媒体分类
    val mediaImportLauncher = rememberLauncherForActivityResult(
        OpenMediaDocuments()
    ) { uris -> onImportPicked(uris) }

    // 批导入驱动器：逐件串行（出口经 importBatchDone 回传），完成后汇总 toast。
    // 用协程等待而非函数引用链——避免局部函数环（runImport→finish→advance→startFileImport）。
    LaunchedEffect(importTrigger) {
        if (importTrigger == 0 || importQueue.isEmpty()) return@LaunchedEffect
        while (importQueue.isNotEmpty() && !importBatchState.cancelled) {
            val uri = importQueue.first()
            importQueue = importQueue.drop(1)
            val mode = importQueueMode
            val target = importQueueTarget
            if (target == null) {
                importBatchState = importBatchState.cancel()
                break
            }
            val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
            importBatchDone = done
            when (mode) {
                "extract" -> startExtractImport(uri, target)
                else -> {
                    val displayName = queryDisplayName(context, uri) ?: "imported-file"
                    val safeName = sanitizeFileName(displayName) ?: "imported-file"
                    startFileImport(uri, target, safeName)
                }
            }
            val success = done.await()
            if (importBatchState.cancelled) break
            importBatchState = importBatchState.itemDone(success)
        }
        importBatchDone = null
        if (importBatchState.wantsSummary()) {
            val (ok, failed) = importBatchState.summaryArgs()
            showToast(
                if (failed == 0) context.resources.getQuantityString(R.plurals.files_import_batch_done, ok, ok)
                else context.resources.getQuantityString(R.plurals.files_import_batch_done_fail, ok, ok, failed),
            )
        }
    }

    val treeExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) exportSelectionToTree(uri)
    }

    val zipExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) exportSelectionToZip(uri)
    }

    // 拍摄结果：成功即刷新列表；取消时清掉相机可能已按 EXTRA_OUTPUT 建出的空文件。
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val file = pendingCaptureFile
        pendingCaptureFile = null
        pendingCaptureIntent = null
        if (result.resultCode != Activity.RESULT_OK) file?.delete()
        refreshEntries()
    }

    // 相机运行时权限：本应用已声明 CAMERA，系统要求拍摄调用方持有该权限。
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val intent = pendingCaptureIntent
        if (granted && intent != null) {
            captureLauncher.launch(intent)
        } else {
            pendingCaptureFile?.delete()
            pendingCaptureFile = null
            pendingCaptureIntent = null
            showToastRes(R.string.files_capture_failed)
        }
    }

    // ---------------- 面板入口（导入 / 拍摄） ----------------

    /**
     * 面板内写入型入口的统一门禁：先拦我方固定资产目录（工作区 `.dsh/dshbox`
     * 对用户只读，与新建/重命名/删除同一条链），再走危险目录强确认；
     * 两道都通过后由 [onAllowed] 执行出口。
     */
    fun gateWrite(action: String, onAllowed: () -> Unit) {
        if (isProtectedAsset(currentDir.absolutePath)) {
            showToastRes(R.string.files_dshbox_readonly)
            return
        }
        val risk = protectedRiskLevel(currentDir)
        if (risk != null) {
            showRisk(
                FileEntry(currentDir.name, currentDir.absolutePath, true, 0, 0, risk),
                action,
            )
        } else {
            onAllowed()
        }
    }

    /** 调起系统选择器：落点恒为当前目录；带初始位置提示的入口走媒体选择器。 */
    fun launchImport(spec: PanelImportSpec) {
        importMode = spec.mode
        importTargetDir = currentDir
        val initialUri = spec.initialUri
        if (initialUri != null) {
            mediaImportLauncher.launch(MediaPickRequest(spec.mimeTypes, initialUri))
        } else {
            importLauncher.launch(spec.mimeTypes.toTypedArray())
        }
    }

    /**
     * 刷新按钮入口：图标至少旋转 [REFRESH_MIN_SPIN_MS] 再停（目录扫描通常几毫秒，
     * 不设下限则旋转态看不到），旋转期间不重复受理点击。只有这条路径点亮旋转态。
     */
    fun refreshWithFeedback() {
        if (refreshing) return
        refreshing = true
        val startedAt = System.currentTimeMillis()
        refreshEntries {
            scope.launch {
                val elapsed = System.currentTimeMillis() - startedAt
                if (elapsed < REFRESH_MIN_SPIN_MS) delay(REFRESH_MIN_SPIN_MS - elapsed)
                refreshing = false
            }
        }
    }

    /**
     * 分享单个文件：经 FileProvider 取可读 URI 交给系统分享面板，不走导出通道。
     * 目录无法经分享意图传递，故入口只在文件行提供。
     */
    fun shareEntry(entry: FileEntry) {
        val physical = mapper.resolvePhysical(File(entry.logicalPath))
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", physical)
        }.getOrNull()
        if (uri == null) {
            showToastRes(R.string.files_share_unavailable)
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(shareMimeTypeOf(entry.name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 自身被排除后可能一个目标都不剩，此时与"无应用可处理"同口径提示，而不是弹空选择器
        if (!ExternalOpener.hasExternalTarget(context, send)) {
            showToastRes(R.string.files_exit_no_app)
            return
        }
        val chooser = ExternalOpener.chooserExcludingSelf(context, send, context.getString(R.string.files_share))
        val opened = runCatching { context.startActivity(chooser) }.isSuccess
        if (!opened) showToastRes(R.string.files_share_unavailable)
    }

    /** 面板导入出口：门禁通过后按动作名取映射并调起选择器。 */
    fun importPanelEntry(action: String) {
        gateWrite(action) {
            panelImportSpecOf(action)?.let { launchImport(it) }
        }
    }

    /**
     * 外部分享/打开：用户选定落点后的门禁。命中固定只读资产直接拒绝；
     * 命中运行环境层等危险目录先走既有强确认，确认后由触发状态执行写入。
     *
     * 目标与批次在此绑成一个不可分割的待执行单元：风险弹窗可能停留，期间可能再来一批
     * 分享，绑定后不会出现"确认时读到另一批内容"的串批。
     */
    fun requestInboundTarget(target: File) {
        showInboundPicker = false
        val items = pendingInboundItems
        if (items.isEmpty()) return
        if (isProtectedAsset(target.absolutePath)) {
            showToastRes(R.string.files_dshbox_readonly)
            pendingInboundItems = emptyList()
            return
        }
        pendingInboundCommit = InboundCommit(target, items)
        // 这一批已交给待执行单元：从待选批次里移出，弹窗停留期间新到的批次因此不受影响
        pendingInboundItems = pendingInboundItems - items.toSet()
        val risk = protectedRiskLevel(target)
        if (risk != null) {
            showRisk(
                FileEntry(target.name, target.absolutePath, true, 0, 0, risk),
                "inbound",
            )
        } else {
            inboundWriteTrigger++
        }
    }

    /**
     * 把外部分享/打开进来的内容写入用户选定目录。
     *
     * 重名一律自动改名（[ConflictMode.RENAME]）：进来的文件是给用户干活的，不做逐件冲突
     * 裁决——既不覆盖已有文件、也不丢内容，且不需要用户逐个点。纯文字按 .txt 落盘。
     * 单项失败只计失败数并清掉半成品；取消按既有口径向上抛，由外层统一收尾。
     */
    fun startInboundImport(target: File, items: List<InboundShareItem>) {
        pendingInboundCommit = null
        if (items.isEmpty()) return
        // 排队而不是打断：上一个导入 / 移动任务仍在跑时等它收尾再写本批，
        // 避免"新一次导入取消进行中的批次、其剩余项无声消失"。
        val previous = progressJob
        progressJob = scope.launch {
            if (previous?.isActive == true) previous.join()
            val self = currentCoroutineContext()[Job]
            val physical = mapper.resolvePhysical(target)
            var ok = 0
            var failed = 0
            BackgroundOps.begin()
            showProgress(UiText.raw(context.getString(R.string.files_progress_prepare)))
            try {
                withContext(Dispatchers.IO) {
                    items.forEachIndexed { index, item ->
                        currentCoroutineContext().ensureActive()
                        val now = System.currentTimeMillis()
                        val proposed = when (item) {
                            is InboundShareItem.FileUri -> inboundFileName(
                                displayName = queryDisplayName(context, item.uri),
                                fallbackExtension = extensionForMime(
                                    runCatching { context.contentResolver.getType(item.uri) }.getOrNull(),
                                ),
                                timestampMillis = now,
                            )
                            is InboundShareItem.PlainText -> inboundTextFileName(item.subject, now)
                        }
                        val fallbackName = if (item is InboundShareItem.PlainText) {
                            "shared-$now.txt"
                        } else {
                            "shared-$now"
                        }
                        val baseName = sanitizeFileName(proposed) ?: fallbackName
                        val finalName =
                            resolveConflictName(physical, baseName, ConflictMode.RENAME) ?: baseName
                        val dest = File(physical, finalName)
                        setProgress(
                            ProgressUi(
                                true,
                                UiText.raw(context.getString(R.string.files_progress_importing, finalName)),
                                index.toLong(),
                                items.size.toLong(),
                            ),
                        )
                        try {
                            when (item) {
                                is InboundShareItem.FileUri -> FileOps.importFromUri(
                                    context, item.uri, physical, finalName,
                                    listener = ProgressListener { done, total, _ ->
                                        setProgress(
                                            ProgressUi(
                                                true,
                                                UiText.raw(
                                                    context.getString(
                                                        R.string.files_progress_importing,
                                                        finalName,
                                                    ),
                                                ),
                                                done,
                                                total,
                                            ),
                                        )
                                    },
                                )
                                is InboundShareItem.PlainText -> dest.writeText(item.text)
                            }
                            ok++
                        } catch (e: CancellationException) {
                            // 取消：清掉半成品再向上抛，由外层统一收尾
                            FileOps.deleteQuietly(dest)
                            throw e
                        } catch (e: Exception) {
                            FileOps.deleteQuietly(dest)
                            failed++
                        }
                    }
                }
                clearProgress()
                refreshEntries()
                showToast(
                    if (failed == 0) {
                        context.resources.getQuantityString(R.plurals.files_import_batch_done, ok, ok)
                    } else {
                        context.resources.getQuantityString(
                            R.plurals.files_import_batch_done_fail, ok, ok, failed,
                        )
                    },
                )
            } catch (e: CancellationException) {
                clearProgress()
                refreshEntries()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } finally {
                BackgroundOps.end()
                // 写入期间又到了新批次：重新弹出选择器，让它继续选落点而不是滞留在内存里
                if (pendingInboundItems.isNotEmpty()) showInboundPicker = true
            }
        }
    }

    // 落点门禁通过后的写入执行点（入口函数与 launcher 均已声明完毕）。
    LaunchedEffect(inboundWriteTrigger) {
        if (inboundWriteTrigger == 0) return@LaunchedEffect
        val commit = pendingInboundCommit ?: return@LaunchedEffect
        startInboundImport(commit.target, commit.items)
    }

    /**
     * 生成相机输出落点并发起拍摄。输出直接落在当前目录（与新建文件同一条路），
     * 不设中转缓存：相机应用只接受可写的 content URI，故经 FileProvider 暴露该落点。
     * 已授权的物理根为沙盒根 / node 层 / dsh 层 / 工作区 / 共享区五类（见 file_paths.xml），
     * 当前目录必落在其中；唯一例外是 base 缺失时的旧式单层回退形态，此时不建文件、直接给提示。
     */
    fun launchCaptureIntent(video: Boolean) {
        val physical = mapper.resolvePhysical(currentDir)
        val suffix = if (video) "mp4" else "jpg"
        val target = File(
            physical,
            (if (video) "video-" else "photo-") + System.currentTimeMillis() + "." + suffix,
        )
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
        }.getOrNull()
        if (uri == null) {
            // 旧式单层安装形态下当前目录不在授权范围内：不建文件，直接给提示。
            showToastRes(R.string.files_capture_failed)
            return
        }
        pendingCaptureFile = target
        val intent = Intent(
            if (video) MediaStore.ACTION_VIDEO_CAPTURE else MediaStore.ACTION_IMAGE_CAPTURE,
        )
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        pendingCaptureIntent = intent
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            captureLauncher.launch(intent)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** 拍摄入口：先过面板统一门禁，再按类型进相机。 */
    fun startCapture(video: Boolean) {
        gateWrite(if (video) PANEL_ACTION_CAPTURE_VIDEO else PANEL_ACTION_CAPTURE_PHOTO) {
            launchCaptureIntent(video)
        }
    }

    // 风险确认通过后的面板出口在此统一执行（launcher 与上述辅助函数均已声明）。
    LaunchedEffect(pendingPanelAction) {
        val action = pendingPanelAction ?: return@LaunchedEffect
        pendingPanelAction = null
        val spec = panelImportSpecOf(action)
        if (spec != null) {
            launchImport(spec)
        } else if (action == PANEL_ACTION_CAPTURE_PHOTO || action == PANEL_ACTION_CAPTURE_VIDEO) {
            launchCaptureIntent(video = action == PANEL_ACTION_CAPTURE_VIDEO)
        }
    }

    // ---------------- Effects ----------------

    LaunchedEffect(rootMode) {
        val nav = pendingNavigateDir
        pendingNavigateDir = null
        currentDir = nav ?: root
        exitSelection()
        searchQuery = ""
        searchResults = emptyList()
        // 切到共享区时重新同步授权与目录状态（授权可能在系统设置页被外部更改）。
        if (rootMode == 2) syncShareReady() else refreshEntries()
    }

    LaunchedEffect(isActiveTab) {
        if (isActiveTab) {
            withContext(Dispatchers.IO) { mapper.ensureRoots() }
            // 共享区视图回到前台时重新同步授权与目录状态（授权可能在系统设置页被撤销）。
            if (rootMode == 2) syncShareReady() else refreshEntries()
        } else {
            // 面板下拉与拍摄选择框都是独立窗口，页签失活必须收起，
            // 否则切走后仍浮在其它页签之上（覆盖页同一口径）。
            showRootMenu = false
            showPanel = false
            showCaptureChoice = false
        }
    }

    // 外部分享/打开进来：待文件页活跃后弹目标目录选择器（选择器是覆盖式二级页，
    // 失活期间随 keepAliveHidden 零尺寸放置，不会浮在其它页签之上）。
    LaunchedEffect(inboundShareItems, isActiveTab) {
        val items = inboundShareItems
        if (items.isNullOrEmpty() || !isActiveTab) return@LaunchedEffect
        // 已有待决批次时追加而不是覆盖：连续两次分享不丢前一批内容
        pendingInboundItems = pendingInboundItems + items
        showInboundPicker = true
        onInboundShareConsumed()
    }

    // 目录导航（列表/网格点击、面包屑、返回键、风险弹窗确认打开）只更新 currentDir，
    // 统一在此按 currentDir 变化刷新列表，保证"文件夹可打开"。
    LaunchedEffect(currentDir) {
        refreshEntries()
    }

    /*
     * 共享区列表自愈：停留期间外部对挂载清单或宿主目录的改动（新增 / 删除 / 卷卸载）
     * 不再需要手动刷新。仅在共享区命名空间列表层生效，静默重读（refreshEntries 不传
     * onDone 即不点亮 spinner）；effect 重启或退出时循环随协程取消。
     * appResumed 并入 key：应用退到后台时 effect 重启并直接返回，停止轮询唤醒；
     * 回到前台（appResumed 转 true）后重启恢复。
     */
    LaunchedEffect(isActiveTab, rootMode, currentDir.absolutePath, shareReady, appResumed) {
        val dir = currentDir
        if (!isActiveTab || !appResumed || rootMode != 2) return@LaunchedEffect
        if (dir.absolutePath != shareNamespace.absolutePath) return@LaunchedEffect
        // 未就绪（未授权 / 内建目录不可用）时不轮询：宿主目录读不到，轮询只会空转。
        if (!shareReady) return@LaunchedEffect
        while (true) {
            delay(SHARE_LIST_POLL_MS)
            // 清单读取与宿主目录 stat 都是磁盘 IO，整段下沉（含 isDirectory 判定）。
            val snapshot = withContext(Dispatchers.IO) {
                val mounts = ShareMountsStore.load(context)
                val builtIn = ShareArea.hostRoot().takeIf { it.isDirectory }
                mounts to shareBindsOf(builtIn, mounts)
            }
            if (snapshot.first != shareMounts || snapshot.second != shareEntries) {
                refreshEntries()
            }
        }
    }

    // 全局搜索（防抖）：搜索进度与文件操作进度（progress）完全分离
    LaunchedEffect(searchQuery) {
        searchJob?.cancel()
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            searchResults = emptyList()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        searchJob = scope.launch {
            delay(400)
            try {
                val results = withContext(Dispatchers.IO) {
                    GlobalSearch.search(
                        roots = listOf(sandboxRoot, workspaceRoot),
                        mapper = mapper,
                        query = query,
                        listener = null,
                    )
                }
                searchResults = results
            } catch (_: CancellationException) {
            } finally {
                searching = false
            }
        }
    }

    BackHandler(
        enabled = isActiveTab && (selectionMode || currentDir != root || searchQuery.isNotEmpty()),
    ) {
        when {
            selectionMode -> exitSelection()
            searchQuery.isNotEmpty() -> {
                searchQuery = ""
                searchResults = emptyList()
            }
            else -> currentDir = parentDirOf(currentDir)
        }
    }

    // ---------------- UI ----------------

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(PageBg())) {
            // ---------- 1. 顶部分段切换 + 全局图标操作 ----------
            if (selectionMode) {
                SelectionActionBar(
                    count = selectedPaths.size,
                    // 共享区列表层不提供改名 / 移动：挂载目录与内建目录的语义由行内菜单表达。
                    canMove = !atShareListing,
                    canRename = selectedPaths.size == 1 && !atShareListing,
                    // 删除入口在共享区列表层保持可用：该层没有真实目录可删，doDelete 会把挂载
                    // 条目分流为「只删记录」的取消挂载（内建 DSHBox 走清空共享区强确认），
                    // 且这是网格视图下唯一的取消挂载入口（网格单元没有行菜单）。
                    canDelete = true,
                    // 导出需排除宿主目录已失效的挂载（unavailable）：与行菜单同口径，
                    // 这类条目的物理路径不存在，导出必然失败。
                    canExport = selectedPaths.isNotEmpty() &&
                        entries.none { it.logicalPath in selectedPaths && it.unavailable },
                    // 多选栏移动入口
                    onMove = {
                        val picked = entries.filter { it.logicalPath in selectedPaths }
                        // 只读资产：只挡源侧；目标侧由 0555 权限兜底
                        if (picked.any { isProtectedAsset(it.logicalPath) }) {
                            showToastRes(R.string.files_dshbox_readonly)
                        } else if (picked.any { namespaceEntryLocked(it) }) {
                            showToastRes(R.string.files_share_entry_locked)
                        } else {
                            moveFlow.startMove(picked)
                        }
                    },
                    onRename = {
                        selectedPaths.firstOrNull()?.let { path ->
                            entries.firstOrNull { it.logicalPath == path }?.let { entry ->
                                if (namespaceEntryLocked(entry)) {
                                    showToastRes(R.string.files_share_entry_locked)
                                } else {
                                    renameTarget = entry
                                    renameName = entry.name
                                    if (isGatedEntry(entry)) showRisk(entry, "rename") else showRenameDialog = true
                                }
                            }
                        }
                    },
                    onDelete = {
                        pendingDeleteTarget = null
                        val picked = entries.filter { it.logicalPath in selectedPaths }
                        val mountNames = picked.mapNotNull { namespaceMountNameOf(it) }
                        when {
                            // 单个挂载条目 → 取消挂载确认；含内建 DSHBox → 清空共享区确认；
                            // 其余（含混合选择）走通用确认，doDelete 内部会先分流挂载条目。
                            mountNames.size == 1 && picked.size == 1 ->
                                pendingUnmount = picked.first().name to mountNames.first()
                            picked.any { namespaceShareEntry(it) } -> pendingShareClear = true
                            else -> {
                                val risky = picked.firstOrNull { isGatedEntry(it) }
                                if (risky != null) showRisk(risky, "delete") else showDeleteConfirm = true
                            }
                        }
                    },
                    onExportDir = { treeExportLauncher.launch(null) },
                    onExportZip = {
                        zipExportLauncher.launch("${currentDir.name}_export_${System.currentTimeMillis()}.zip")
                    },
                    onCancel = { exitSelection() },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        RootPicker(
                            label = rootName,
                            expanded = showRootMenu,
                            onClick = { showRootMenu = !showRootMenu },
                        )
                        DropdownMenu(
                            expanded = showRootMenu,
                            onDismissRequest = { showRootMenu = false },
                        ) {
                            RootMenuItem(
                                label = stringResource(R.string.files_root_sandbox),
                                selected = rootMode == 0,
                                onClick = {
                                    showRootMenu = false
                                    rootMode = 0
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                thickness = 0.5.dp,
                                color = DividerColor(),
                            )
                            RootMenuItem(
                                label = stringResource(R.string.files_root_workspace),
                                selected = rootMode == 1,
                                onClick = {
                                    showRootMenu = false
                                    rootMode = 1
                                },
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                thickness = 0.5.dp,
                                color = DividerColor(),
                            )
                            RootMenuItem(
                                label = stringResource(R.string.files_root_share),
                                selected = rootMode == 2,
                                onClick = {
                                    showRootMenu = false
                                    rootMode = 2
                                },
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { refreshWithFeedback() },
                        modifier = Modifier.size(36.dp),
                    ) {
                        RefreshIcon(spinning = refreshing)
                    }
                    IconButton(
                        onClick = {
                            sortMode = when (sortMode) {
                                SortMode.NAME -> SortMode.TIME
                                SortMode.TIME -> SortMode.SIZE
                                SortMode.SIZE -> SortMode.NAME
                            }
                            showToastRes(
                                when (sortMode) {
                                    SortMode.NAME -> R.string.files_sort_name
                                    SortMode.TIME -> R.string.files_sort_time
                                    SortMode.SIZE -> R.string.files_sort_size
                                },
                            )
                        },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrows_sort),
                            contentDescription = stringResource(R.string.files_sort),
                            tint = TextSecondary(),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (viewMode == ViewMode.LIST) ImageVector.vectorResource(CommonR.drawable.ic_layout_grid) else ImageVector.vectorResource(CommonR.drawable.ic_list),
                            contentDescription = stringResource(
                                if (viewMode == ViewMode.LIST) R.string.files_view_grid else R.string.files_view_list,
                            ),
                            tint = TextSecondary(),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ---------- 2. 常驻全局搜索框（左）+ 操作按键组（右） ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlobalSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClear = { searchQuery = "" },
                    // 搜索框独占该行余宽：新建、导入、导出统一收进右侧加号入口
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { showPanel = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_plus),
                        contentDescription = stringResource(R.string.files_panel_open),
                        tint = TextSecondary(),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // ---------- 3. 面包屑（独占一行，常驻；搜索态也不让位，位置感不丢） ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Breadcrumb(
                    root = root,
                    rootLabel = rootLabel,
                    currentDir = currentDir,
                    onNavigate = { currentDir = it },
                    // 共享区是虚拟命名空间根：parentFile 回溯走不通，用挂载集合自建面包屑。
                    segmentsOverride = if (rootMode == 2) shareCrumbs() else null,
                    modifier = Modifier.weight(1f),
                )
                // 搜索态只在右侧补一个结果标记，不再顶掉面包屑本身。
                if (searchQuery.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.files_search_results),
                        fontSize = 13.sp,
                        color = TextSecondary(),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            // 文件数超限时的提示：目录始终完整，只有文件被截断才出现（搜索态不适用）。
            if (listTruncated && searchQuery.isBlank()) {
                Text(
                    text = stringResource(R.string.files_list_truncated, MAX_DIRECTORY_FILES),
                    fontSize = 12.sp,
                    color = TextSecondary(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 2.dp),
                )
            }

            // ---------- 3. 内容区 ----------
            if (searchQuery.isNotBlank()) {
                SearchResultsContent(
                    searching = searching,
                    results = searchResults,
                    onOpenResult = { result ->
                        // 目录命中进入目录本身；文件命中进入其所在目录
                        val file = File(result.logicalPath)
                        val target = if (result.isDirectory) file else (file.parentFile ?: root)
                        navigateToDir(target)
                    },
                )
            } else if (rootMode == 2 && !shareReady) {
                // 共享区未就绪（未授权 / 目录不可用）：说明与开启入口。
                ShareEnablePanel(onEnable = { requestShareAccess() })
            } else if (entries.isEmpty()) {
                // 空目录不再放占位提示与导入按钮：导入入口统一在顶部加号面板。
                Spacer(Modifier.fillMaxSize())
            } else if (viewMode == ViewMode.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(entries, key = { it.logicalPath }) { entry ->
                        FileListRow(
                            entry = entry,
                            selected = entry.logicalPath in selectedPaths,
                            selectionVisible = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelect(entry)
                                } else if (entry.unavailable) {
                                    // 宿主目录已失效：不进入，只提示；行菜单仍可取消挂载。
                                    showToastRes(R.string.files_share_path_unavailable)
                                } else if (entry.isDirectory) {
                                    if (isRiskEntry(entry)) showRisk(entry, "open") else currentDir = File(entry.logicalPath)
                                } else {
                                    viewerLogicalPath = entry.logicalPath
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                toggleSelect(entry)
                            },
                            onToggleSelect = {
                                if (!selectionMode) selectionMode = true
                                toggleSelect(entry)
                            },
                            onMove = {
                                if (isProtectedAsset(entry.logicalPath)) {
                                    showToastRes(R.string.files_dshbox_readonly)
                                } else if (namespaceEntryLocked(entry)) {
                                    showToastRes(R.string.files_share_entry_locked)
                                } else {
                                    moveFlow.startMove(listOf(entry))
                                }
                            },
                            onRename = {
                                if (namespaceEntryLocked(entry)) {
                                    showToastRes(R.string.files_share_entry_locked)
                                } else {
                                    renameTarget = entry
                                    renameName = entry.name
                                    if (isGatedEntry(entry)) showRisk(entry, "rename") else showRenameDialog = true
                                }
                            },
                            onDelete = {
                                val alias = namespaceMountNameOf(entry)
                                when {
                                    // 命名空间层的挂载目录：「删除」即取消挂载，绝不删文件夹内容。
                                    alias != null -> pendingUnmount = entry.name to alias
                                    // 内建 DSHBox：清空共享区需单独确认。
                                    namespaceShareEntry(entry) -> pendingShareClear = true
                                    else -> {
                                        pendingDeleteTarget = entry
                                        if (isGatedEntry(entry)) showRisk(entry, "delete") else showDeleteConfirm = true
                                    }
                                }
                            },
                            onExport = {
                                selectedPaths = setOf(entry.logicalPath)
                                selectionMode = true
                                showExportChoice = true
                            },
                            onShare = { shareEntry(entry) },
                            // 共享区列表层的条目菜单收敛：挂载目录只留「取消挂载 + 导出」，
                            // 内建 DSHBox 只留「导出」；更深的层级按普通文件菜单处理。
                            menuKind = when {
                                namespaceMountNameOf(entry) != null -> ShareEntryMenu.MOUNT
                                namespaceShareEntry(entry) -> ShareEntryMenu.BUILT_IN
                                else -> null
                            },
                            onUnmount = namespaceMountNameOf(entry)?.let { alias ->
                                { pendingUnmount = entry.name to alias }
                            },
                            entryUnavailable = entry.unavailable,
                            onInstall = if (DebUnpackInstaller.isDeb(entry.name)) {
                                {
                                    // 解包安装只摊数据部分、不写 dpkg 账本（语义边界见
                                    // DebUnpackInstaller 的说明），因此不弹风险确认，
                                    // 结果仍走文件页既有的 toast 反馈。
                                    // 共享区（内建 DSHBox 或用户挂载）走统一 guest 路径映射；
                                    // 其余视图仍用 PathMapper 的既有口径。
                                    val physical = mapper.resolvePhysical(File(entry.logicalPath))
                                    val guestPath = shareGuestPathOf(physical) ?: guestPathOf(mapper, physical)
                                    scope.launch {
                                        val info = withContext(Dispatchers.IO) {
                                            DebUnpackInstaller.install(
                                                sandboxManager = app.container.sandboxManager,
                                                debGuestPath = guestPath,
                                                workspaceDir = app.container.sandboxConfig.userDataDir,
                                                inventoryDir = File(app.container.sandboxConfig.appFilesDir, "deb-installs"),
                                            )
                                        }
                                        // 用了维护脚本或需要注册替代名的包，解包后仍需自行处理；
                                        // 这里只如实回报「数据部分是否摊开」。
                                        showToastRes(
                                            if (info?.unpacked == true) R.string.files_install_deb_done
                                            else R.string.files_install_deb_failed,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.logicalPath }) { entry ->
                        FileGridCell(
                            entry = entry,
                            selected = entry.logicalPath in selectedPaths,
                            selectionVisible = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelect(entry)
                                } else if (entry.unavailable) {
                                    // 宿主目录已失效：不进入，只提示；行菜单仍可取消挂载。
                                    showToastRes(R.string.files_share_path_unavailable)
                                } else if (entry.isDirectory) {
                                    if (isRiskEntry(entry)) showRisk(entry, "open") else currentDir = File(entry.logicalPath)
                                } else {
                                    viewerLogicalPath = entry.logicalPath
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                toggleSelect(entry)
                            },
                            onToggleSelect = {
                                if (!selectionMode) selectionMode = true
                                toggleSelect(entry)
                            },
                        )
                    }
                }
            }
        }

    }

    // ---------------- 对话框 ----------------

    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.files_new_folder)) },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.files_new_folder_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = { createNewFolder() },
                ) {
                    Text(stringResource(R.string.files_new_folder_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text(stringResource(R.string.files_new_file)) },
            text = {
                OutlinedTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.files_new_file_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = { createNewFile() },
                ) {
                    Text(stringResource(R.string.files_new_folder_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    // ---------- 顶部加号唤出的底部面板（拍摄 / 图像 / 音频 / 手机文件 + 五项文件操作） ----------
    if (showPanel) {
        FileActionPanel(
            onDismiss = { showPanel = false },
            writeEntriesVisible = !atShareListing,
            // 「挂载系统文件夹」只在共享区列表层出现：进入 DSHBox / 挂载目录后不再显示（避免嵌套语义）。
            showMountFolder = atShareListing,
            onMountFolder = {
                showPanel = false
                mountSystemFolder()
            },
            onCapture = {
                showPanel = false
                showCaptureChoice = true
            },
            onImage = {
                showPanel = false
                importPanelEntry(PANEL_ACTION_IMPORT_IMAGE)
            },
            onAudio = {
                showPanel = false
                importPanelEntry(PANEL_ACTION_IMPORT_AUDIO)
            },
            onPhone = {
                showPanel = false
                importPanelEntry(PANEL_ACTION_IMPORT_FILE)
            },
            onNewFile = {
                showPanel = false
                pendingNewKind = "file"
                gateWrite("write") { showNewFileDialog = true }
            },
            onNewFolder = {
                showPanel = false
                pendingNewKind = "folder"
                gateWrite("write") { showNewFolderDialog = true }
            },
            onExtract = {
                showPanel = false
                importPanelEntry(PANEL_ACTION_IMPORT_EXTRACT)
            },
            onExportFiles = {
                showPanel = false
                // 无选择时仅一次提示，保持既有导出通路口径
                if (selectedPaths.isEmpty()) {
                    showToastRes(R.string.files_select_hint)
                } else {
                    treeExportLauncher.launch(null)
                }
            },
            onExportZip = {
                showPanel = false
                if (selectedPaths.isEmpty()) {
                    showToastRes(R.string.files_select_hint)
                } else {
                    zipExportLauncher.launch("${currentDir.name}_export_${System.currentTimeMillis()}.zip")
                }
            },
        )
    }

    // ---------- 拍摄的二级选择框（拍照 / 录像） ----------
    if (showCaptureChoice) {
        AlertDialog(
            onDismissRequest = { showCaptureChoice = false },
            title = {
                Text(
                    text = stringResource(R.string.files_panel_capture),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                Column {
                    CaptureChoiceRow(R.string.files_capture_photo) {
                        showCaptureChoice = false
                        startCapture(video = false)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 6.dp),
                        thickness = 0.5.dp,
                        color = DividerColor(),
                    )
                    CaptureChoiceRow(R.string.files_capture_video) {
                        showCaptureChoice = false
                        startCapture(video = true)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }

    if (showExportChoice) {
        MenuListDialog(
            title = stringResource(R.string.files_export_choice_title),
            items = listOf(
                MenuItemSpec(R.string.files_export_files, ImageVector.vectorResource(CommonR.drawable.ic_upload)) {
                    showExportChoice = false
                    treeExportLauncher.launch(null)
                },
                MenuItemSpec(R.string.files_export_zip, ImageVector.vectorResource(CommonR.drawable.ic_file_zip)) {
                    showExportChoice = false
                    zipExportLauncher.launch("${currentDir.name}_export_${System.currentTimeMillis()}.zip")
                },
            ),
            onDismiss = { showExportChoice = false },
        )
    }

    if (showConflictDialog) {
        val single = pendingImport
        val merge = pendingMerge
        val conflictName = single?.baseName
        AlertDialog(
            onDismissRequest = {
                showConflictDialog = false
                pendingImport = null
                pendingMerge = null
                // 弹窗被点外部/返回键关闭后没有任何出口会 complete——
                // 批处理驱动器会在 done.await() 永久挂起（剩余文件静默不再导入）。
                // 口径与「取消=中止整个批」一致：放弃剩余并给提示。
                if (importBatchState.total > 0) {
                    importQueue = emptyList()
                    importBatchState = importBatchState.cancel()
                    importBatchDone?.complete(false)
                    showToastRes(R.string.files_progress_cancelled)
                }
            },
            title = { Text(stringResource(R.string.files_conflict_title)) },
            text = {
                Text(
                    text = if (conflictName != null) {
                        stringResource(R.string.files_conflict_msg, conflictName)
                    } else {
                        stringResource(R.string.files_conflict_msg_dir)
                    },
                    fontSize = 14.sp,
                    color = TextSecondary(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConflictDialog = false
                        val pi = pendingImport
                        val pm = pendingMerge
                        pendingImport = null
                        pendingMerge = null
                        if (pi != null) {
                            runImport(pi.uri, pi.targetDir, pi.baseName, ConflictMode.OVERWRITE)
                        } else if (pm != null) {
                            mergeExtracted(pm.extractedDir, pm.targetDir, ConflictMode.OVERWRITE)
                        }
                    },
                ) {
                    Text(stringResource(R.string.files_conflict_overwrite), color = PrimaryGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showConflictDialog = false
                    val pi = pendingImport
                    val pm = pendingMerge
                    pendingImport = null
                    pendingMerge = null
                    if (pi != null) {
                        runImport(pi.uri, pi.targetDir, pi.baseName, ConflictMode.RENAME)
                    } else if (pm != null) {
                        mergeExtracted(pm.extractedDir, pm.targetDir, ConflictMode.RENAME)
                    }
                }) {
                    Text(stringResource(R.string.files_conflict_rename))
                }
                TextButton(onClick = {
                    showConflictDialog = false
                    val pm = pendingMerge
                    pendingImport = null
                    pendingMerge = null
                    if (pm != null) {
                        mergeExtracted(pm.extractedDir, pm.targetDir, ConflictMode.SKIP)
                    } else {
                        showToastRes(R.string.files_conflict_skipped)
                        importBatchDone?.complete(true) // 单文件导入跳过 = 已处理，继续批
                    }
                }) {
                    Text(stringResource(R.string.files_conflict_skip))
                }
            },
        )
    }

    if (showRiskDialog) {
        val entry = pendingRiskEntry
        val isMoveAction = riskAction == "move"
        AlertDialog(
            onDismissRequest = { cancelRisk() },
            title = { Text(stringResource(R.string.files_risk_title)) },
            text = {
                Column {
                    // 文案判定与入口筛选同源（entryLayer → layerOf 物理层判定），
                    // 不用 entry.risk（名称口径，层内文件返回 NORMAL 会落到错误兜底）
                    val layer = entry?.let { entryLayer(it) } ?: Layer.BASE
                    Text(
                        text = entry?.let {
                            stringResource(riskDialogTextRes(layer, isMoveAction, sandboxRunning), it.name)
                        } ?: stringResource(R.string.files_risk_generic),
                        fontSize = 14.sp,
                        color = TextSecondary(),
                    )
                    if (isMoveAction && moveFlow.state.moveRiskCount > 1 && entry != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.files_risk_move_multi, selectedPaths.size, moveFlow.state.moveRiskCount),
                            fontSize = 12.sp,
                            color = TextHint(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmRisk() },
                ) {
                    Text(stringResource(R.string.files_risk_continue), color = PrimaryGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelRisk() }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    if (showDeleteConfirm) {
        val single = pendingDeleteTarget
        AlertDialog(
            onDismissRequest = {
                showDeleteConfirm = false
                pendingDeleteTarget = null
            },
            title = { Text(stringResource(R.string.files_delete_confirm_title)) },
            text = {
                Text(
                    if (single != null) stringResource(R.string.files_delete_confirm_single, single.name)
                    else pluralStringResource(R.plurals.files_delete_confirm_msg, selectedPaths.size, selectedPaths.size),
                    fontSize = 14.sp,
                    color = TextSecondary(),
                )
            },
            confirmButton = {
                TextButton(onClick = { doDelete() }) {
                    Text(stringResource(R.string.files_delete), color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    pendingDeleteTarget = null
                }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    // 取消挂载（命名空间层的挂载目录）：只删记录，不触碰宿主目录内容。
    if (pendingUnmount != null) {
        val (displayName, alias) = pendingUnmount!!
        AlertDialog(
            onDismissRequest = { pendingUnmount = null },
            title = { Text(stringResource(R.string.files_unmount)) },
            text = {
                Text(
                    stringResource(R.string.files_unmount_confirm, displayName),
                    fontSize = 14.sp,
                    color = TextSecondary(),
                )
            },
            confirmButton = {
                TextButton(onClick = { unmount(alias) }) {
                    Text(stringResource(R.string.files_unmount), color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnmount = null }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    // 清空共享区（命名空间层内建 DSHBox 的删除）：单独强确认，区别于取消挂载。
    if (pendingShareClear) {
        AlertDialog(
            onDismissRequest = { pendingShareClear = false },
            title = { Text(stringResource(R.string.files_root_share)) },
            text = {
                Text(
                    stringResource(R.string.files_share_clear_confirm),
                    fontSize = 14.sp,
                    color = TextSecondary(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingShareClear = false
                    // 选中项里的挂载条目按「取消挂载」处理（只删记录），再清空 DSHBox 内容。
                    val picked = entries.filter { it.logicalPath in selectedPaths }
                    val aliases = picked.mapNotNull { namespaceMountNameOf(it) }.toSet()
                    if (aliases.isNotEmpty()) {
                        // 挂载记录落盘统一走写入口；保存失败时入口内提示，内建内容清空仍照常执行。
                        saveMounts(build = { current -> MountUpdate.Save(current.filterNot { it.name in aliases }) })
                    }
                    exitSelection()
                    clearShareArea()
                }) {
                    Text(stringResource(R.string.files_delete), color = Color(0xFFDC2626))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingShareClear = false }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = {
                showRenameDialog = false
                renameTarget = null
                renameName = ""
            },
            title = { Text(stringResource(R.string.files_rename)) },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.files_rename_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = { doRename() },
                    enabled = renameName.isNotBlank(),
                ) {
                    Text(stringResource(R.string.files_rename_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRenameDialog = false
                        renameTarget = null
                        renameName = ""
                    },
                ) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text(stringResource(R.string.files_error_title)) },
            text = {
                Text(
                    text = errorMessage,
                    fontSize = 14.sp,
                    color = TextSecondary(),
                )
            },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text(stringResource(R.string.files_close))
                }
            },
        )
    }

    // ---------- 通用文件查看器（覆盖式二级页） ----------
    // zIndex(2f)：本函数的覆盖页发射在根 Box 之外（与 MainScreen 各 tab 同层），
    // 而根 Box 由 MainScreen 设了 zIndex(1f) 且背景不透明——覆盖页必须显式压过它，
    // 否则被遮挡（选择器已组合但不可见，表现为「移动到无反应」）。
    viewerLogicalPath?.let { path ->
        FileViewerScreen(
            logicalPath = path,
            mapper = mapper,
            layerRoots = layerRoots,
            sandboxRunning = sandboxRunning,
            onDismiss = { viewerLogicalPath = null },
            onRequestRefresh = { refreshEntries() },
            backEnabled = isActiveTab,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
                // 覆盖页必须随 tab 活跃性走 keepAliveHidden——
                // 否则切换 tab 后它仍全屏绘制在其它 tab 之上（底部导航「点了没反应」），
                // 且切回时组合原样恢复（编辑草稿保留）。
                .then(if (isActiveTab) Modifier else Modifier.keepAliveHidden()),
        )
    }

    // ---------- 外部分享/打开：目标目录选择器（覆盖式二级页，复用移动流程的选择器） ----------
    if (showInboundPicker) {
        FolderPickerScreen(
            mapper = mapper,
            sandboxRoot = sandboxRoot,
            workspaceRoot = workspaceRoot,
            shareNamespace = shareNamespace,
            shareEntries = shareEntries,
            shareAvailable = shareReady,
            layerRoots = layerRoots,
            moveCount = pendingInboundItems.size,
            // 导入没有源侧目录，无防环置灰需求
            sourceDirs = emptyList(),
            sandboxRunning = sandboxRunning,
            onDismiss = {
                showInboundPicker = false
                pendingInboundItems = emptyList()
                pendingInboundCommit = null
            },
            onConfirm = { requestInboundTarget(it) },
            backEnabled = isActiveTab,
            titleRes = R.string.files_picker_import_title,
            confirmPluralRes = R.plurals.files_picker_import_confirm,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
                // 同移动选择器：覆盖页随页签活跃性隐藏，否则切页签后仍压住其它页
                .then(if (isActiveTab) Modifier else Modifier.keepAliveHidden()),
        )
    }

    // ---------- 移动目标选择器（覆盖式二级页） ----------
    if (moveFlow.state.showFolderPicker) {
        FolderPickerScreen(
            mapper = mapper,
            sandboxRoot = sandboxRoot,
            workspaceRoot = workspaceRoot,
            shareNamespace = shareNamespace,
            shareEntries = shareEntries,
            shareAvailable = shareReady,
            layerRoots = layerRoots,
            moveCount = moveFlow.state.moveSources.size,
            sourceDirs = moveFlow.state.moveSources.filter { it.isDirectory },
            sandboxRunning = sandboxRunning,
            onDismiss = { moveFlow.dismissPicker() },
            onConfirm = { moveFlow.onMoveTargetPicked(it) },
            backEnabled = isActiveTab,
            modifier = Modifier
                .fillMaxSize()
                .zIndex(2f)
                // 同 FileViewerScreen：覆盖页随 tab 活跃性隐藏，
                // 否则切 tab 后移动选择器仍全屏压住其它 tab（底部导航「点了没反应」）。
                .then(if (isActiveTab) Modifier else Modifier.keepAliveHidden()),
        )
    }

    // ---------- 移动冲突对话框（覆盖 / 跳过 / 自动改名 + 应用到其余全部） ----------
    moveFlow.state.pendingMove?.let { moveState ->
        if (moveState.conflicts.isNotEmpty()) {
            MoveConflictDialog(
                pending = moveState,
                applyToAll = moveFlow.state.applyConflictsToAll,
                onApplyToAllChange = { moveFlow.setApplyConflictsToAll(it) },
                onDecide = { moveFlow.resolveMoveConflict(it) },
                onCancel = { moveFlow.cancelMoveConflicts() },
            )
        }
    }

    // ---------- 阶段二：跨层移动强确认（消费 layerRisks） ----------
    moveFlow.state.pendingMatrixConfirm?.let { confirm ->
        MoveMatrixConfirmDialog(
            message = confirm.message,
            onConfirm = { moveFlow.confirmMatrix() },
            onDismiss = { moveFlow.dismissMatrix() },
        )
    }

    // ---------- 移动结果对话框 ----------
    if (moveFlow.state.showMoveResultDialog) {
        MoveResultDialog(
            result = moveFlow.state.lastMoveResult,
            skippedByDecision = moveFlow.state.lastMoveSkippedByDecision,
            crossViewHint = moveFlow.state.moveCrossViewHint,
            onDismiss = { moveFlow.dismissResult() },
        )
    }

    if (progress.active) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.files_progress_title)) },
            text = {
                Column {
                    Text(
                        text = progress.stage.asString(),
                        fontSize = 14.sp,
                        color = TextSecondary(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (progress.total > 0L) {
                        LinearProgressIndicator(
                            progress = { (progress.done.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${progress.done.formatSize()} / ${progress.total.formatSize()}",
                            fontSize = 12.sp,
                            color = TextHint(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { cancelProgressJob() }) {
                    Text(stringResource(R.string.files_progress_cancel), color = Color(0xFFDC2626))
                }
            },
        )
    }
}

private fun Long.formatSize(): String = formatFileSize(this)

// ---------- 菜单项规格 ----------

private data class MenuItemSpec(
    val labelRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@Composable
private fun MenuListDialog(
    title: String,
    items: List<MenuItemSpec>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        text = {
            Column {
                items.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { item.onClick() }
                            .padding(horizontal = 8.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = PrimaryGreen,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(item.labelRes),
                            fontSize = 14.sp,
                            color = TextPrimary(),
                        )
                    }
                    if (index < items.lastIndex) {
                        HorizontalDivider(color = DividerColor(), thickness = 0.5.dp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.files_cancel))
            }
        },
    )
}

// ---------- 选择操作栏 ----------

@Composable
private fun SelectionActionBar(
    count: Int,
    canMove: Boolean,
    canRename: Boolean,
    canDelete: Boolean,
    canExport: Boolean,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExportDir: () -> Unit,
    onExportZip: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = pluralStringResource(R.plurals.files_selected_count, count, count),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary(),
            modifier = Modifier.weight(1f),
        )
        SelectionChip(ImageVector.vectorResource(CommonR.drawable.ic_file_arrow_right), stringResource(R.string.files_move), enabled = canMove, onClick = onMove)
        SelectionChip(ImageVector.vectorResource(CommonR.drawable.ic_download), stringResource(R.string.files_export_to_dir), enabled = canExport, onClick = onExportDir)
        SelectionChip(ImageVector.vectorResource(CommonR.drawable.ic_file_zip), stringResource(R.string.files_export_zip_short), enabled = canExport, onClick = onExportZip)
        SelectionChip(ImageVector.vectorResource(CommonR.drawable.ic_file), stringResource(R.string.files_rename), enabled = canRename, onClick = onRename)
        SelectionChip(ImageVector.vectorResource(CommonR.drawable.ic_trash), stringResource(R.string.files_delete), enabled = canDelete, onClick = onDelete)
        SelectionChip(ImageVector.vectorResource(CommonR.drawable.ic_x), stringResource(R.string.files_cancel), enabled = true, onClick = onCancel)
    }
}

@Composable
private fun SelectionChip(
    icon: ImageVector,
    contentDesc: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(34.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = if (enabled) PrimaryGreen else TextHint(),
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 规范化路径上的「相等或包含」判定（带分隔符边界，避免 `DSHBox2` 命中 `DSHBox`）。 */
private fun containOrEqual(container: String, other: String): Boolean =
    container.isNotEmpty() && (other == container || other.startsWith("$container/"))

/** 共享区未就绪时的开启面板：说明文案 + 授权按钮。 */
@Composable
private fun ShareEnablePanel(onEnable: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.files_share_enable_hint),
            fontSize = 13.sp,
            color = TextSecondary(),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onEnable) {
            Text(stringResource(R.string.files_share_enable))
        }
    }
}

// ---------- 常驻全局搜索框 ----------

@Composable
private fun GlobalSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, DividerColor(), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_search),
            contentDescription = null,
            tint = TextHint(),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.files_search_hint),
                    fontSize = 12.sp,
                    color = TextHint(),
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = TextPrimary()),
                modifier = Modifier.fillMaxSize(),
                // 输入区占满整框高度（整框可点即聚焦），但文本须靠 decorationBox 垂直居中：
                // 单行文本在高于自身的高度里默认顶部对齐，视觉上明显偏高。
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        innerTextField()
                    }
                },
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
                    contentDescription = stringResource(R.string.files_clear_search),
                    tint = TextHint(),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ---------- 根视图选择 ----------
// 控件与面板条目（RootPicker / RootMenuItem）已提到 FilesCommon.kt：文件页与目录选择器共用同一套样式。

// ---------- 刷新按键（点击后旋转，扫描结束停止） ----------

@Composable
private fun RefreshIcon(spinning: Boolean) {
    val icon = ImageVector.vectorResource(CommonR.drawable.ic_refresh)
    if (!spinning) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.files_refresh),
            tint = TextSecondary(),
            modifier = Modifier.size(20.dp),
        )
        return
    }
    // 仅在旋转期间挂载无限动画，静止时不保留任何持续帧回调。
    val transition = rememberInfiniteTransition(label = "refreshSpin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "refreshAngle",
    )
    Icon(
        imageVector = icon,
        contentDescription = stringResource(R.string.files_refresh),
        tint = TextSecondary(),
        modifier = Modifier
            .size(20.dp)
            .graphicsLayer { rotationZ = angle },
    )
}

// ---------- 顶部加号面板 ----------

/**
 * 新建与导入的统一面板：封顶半屏（内容超出时面板内部滚动），上方页面变暗但保持
 * 可见，点面板外或右上角 X 均收起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionPanel(
    onDismiss: () -> Unit,
    /** 写入类入口是否显示：共享区列表层没有真实目录，该层的写入入口整行不显示。 */
    writeEntriesVisible: Boolean,
    /** 「挂载系统文件夹」仅在共享区列表层出现（进入子目录后隐藏，面板其余布局不变）。 */
    showMountFolder: Boolean,
    onMountFolder: () -> Unit,
    onCapture: () -> Unit,
    onImage: () -> Unit,
    onAudio: () -> Unit,
    onPhone: () -> Unit,
    onNewFile: () -> Unit,
    onNewFolder: () -> Unit,
    onExtract: () -> Unit,
    onExportFiles: () -> Unit,
    onExportZip: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 面板无标题：不画拖拽把手，关闭只留右上角 X
        dragHandle = null,
        containerColor = PageBg(),
        scrimColor = Color.Black.copy(alpha = if (filesUseDarkTheme()) 0.52f else 0.32f),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 封顶半屏：超高时由面板内部滚动，不占满整屏
                    .heightIn(max = maxHeight * 0.5f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
                            contentDescription = stringResource(R.string.files_close),
                            tint = TextPrimary(),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (writeEntriesVisible) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PanelTile(R.drawable.ic_camera, R.string.files_panel_capture, onCapture, Modifier.weight(1f))
                        PanelTile(R.drawable.ic_photo, R.string.files_panel_image, onImage, Modifier.weight(1f))
                        PanelTile(R.drawable.ic_music, R.string.files_panel_audio, onAudio, Modifier.weight(1f))
                        PanelTile(R.drawable.ic_paperclip, R.string.files_panel_phone, onPhone, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (showMountFolder) {
                    PanelRow(CommonR.drawable.ic_external_link, R.string.files_mount_folder, onMountFolder)
                    PanelDivider()
                }
                if (writeEntriesVisible) {
                    PanelRow(CommonR.drawable.ic_file_plus, R.string.files_new_file, onNewFile)
                    PanelDivider()
                    PanelRow(CommonR.drawable.ic_folder_plus, R.string.files_new_folder, onNewFolder)
                    PanelDivider()
                    PanelRow(CommonR.drawable.ic_archive, R.string.files_import_archive, onExtract)
                    PanelDivider()
                }
                PanelRow(CommonR.drawable.ic_upload, R.string.files_export_files, onExportFiles)
                PanelDivider()
                PanelRow(CommonR.drawable.ic_file_zip, R.string.files_export_zip, onExportZip)
            }
        }
    }
}

/** 面板横条之间的分界：直线，不随条目圆角弯曲。 */
@Composable
private fun PanelDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 6.dp),
        thickness = 0.5.dp,
        color = DividerColor(),
    )
}

/** 面板上排的媒体来源磁贴。 */
@Composable
private fun PanelTile(
    iconRes: Int,
    labelRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(92.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ControlBg())
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(iconRes),
            contentDescription = null,
            // 面板图标统一取导航键灰色，不用品牌绿
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(26.dp),
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = stringResource(labelRes),
            fontSize = 12.sp,
            color = TextPrimary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 面板下排的文件操作横条。 */
@Composable
private fun PanelRow(
    iconRes: Int,
    labelRes: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(labelRes),
            fontSize = 14.sp,
            color = TextPrimary(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 拍摄二级选择框中的单个选项。 */
@Composable
private fun CaptureChoiceRow(labelRes: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(labelRes),
            fontSize = 14.sp,
            color = TextPrimary(),
        )
    }
}

// -------- 选择方框 ----------

/** 选择状态方框：未选中为空心边框，选中为绿色底 + 白色对号；点击直接切换选择。 */
@Composable
private fun SelectionCheckbox(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) PrimaryGreen else Color.Transparent)
            .border(
                width = 1.5.dp,
                color = if (selected) PrimaryGreen else TextHint(),
                shape = RoundedCornerShape(5.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_check),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// ---------- 文件列表行 ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRow(
    entry: FileEntry,
    selected: Boolean,
    selectionVisible: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    /** 系统分享（仅文件行提供；目录不显示该条目）。 */
    onShare: () -> Unit,
    /** 非空时该行是共享区列表层的条目：按 [ShareEntryMenu] 收敛菜单项（见枚举说明）。 */
    menuKind: ShareEntryMenu? = null,
    /** [ShareEntryMenu.MOUNT] 时的取消挂载动作。 */
    onUnmount: (() -> Unit)? = null,
    /** 非空时在菜单里提供「安装到沙箱」（仅 `.deb`）。 */
    onInstall: (() -> Unit)? = null,
    /** 该行宿主目录已失效：只保留取消挂载，不提供导出（导出必然失败）。 */
    entryUnavailable: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (selected) SelectedRowBg() else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 选择方框仅多选模式显示：长按唤出，随模式淡入/收回（直接重排，图标随之移动）
        AnimatedVisibility(
            visible = selectionVisible,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionCheckbox(
                    selected = selected,
                    onClick = onToggleSelect,
                )
                Spacer(Modifier.width(12.dp))
            }
        }
        Icon(
            imageVector = if (entry.isDirectory) ImageVector.vectorResource(CommonR.drawable.ic_folder) else ImageVector.vectorResource(CommonR.drawable.ic_file),
            contentDescription = null,
            tint = if (entry.isDirectory) PrimaryGreen else TextHint(),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entrySubtitle(entry).asString(),
                fontSize = 12.sp,
                color = TextHint(),
                maxLines = 1,
            )
        }
        Box {
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_dots_vertical),
                contentDescription = stringResource(R.string.files_more),
                tint = TextSecondary(),
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { menuOpen = true }
                    .padding(4.dp),
            )
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                when (menuKind) {
                    ShareEntryMenu.MOUNT -> androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_unmount)) },
                        onClick = {
                            menuOpen = false
                            onUnmount?.invoke()
                        },
                    )
                    // 内建 DSHBox：只剩「导出」（不提供取消挂载）。
                    ShareEntryMenu.BUILT_IN -> Unit
                    null -> {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_move)) },
                            onClick = {
                                menuOpen = false
                                onMove()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_rename)) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_delete)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
                // 不可达条目只保留取消挂载：宿主目录不存在，导出必然失败。
                if (!entryUnavailable) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_export)) },
                        onClick = {
                            menuOpen = false
                            onExport()
                        },
                    )
                }
                // 分享与导出分开列条目：分享交系统分享面板处理；
                // 目录无法经分享意图传递，故只在文件行提供该入口。
                if (menuKind == null && !entry.isDirectory) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_share)) },
                        onClick = {
                            menuOpen = false
                            onShare()
                        },
                    )
                }
                if (menuKind == null) onInstall?.let { install ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_install_deb)) },
                        onClick = {
                            menuOpen = false
                            install()
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        thickness = 0.5.dp,
        color = DividerColor(),
    )
}

// ---------- 文件网格单元 ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    entry: FileEntry,
    selected: Boolean,
    selectionVisible: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) SelectedRowBg() else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 选择方框仅多选模式显示：长按唤出，随模式淡入/收回
        AnimatedVisibility(
            visible = selectionVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                SelectionCheckbox(
                    selected = selected,
                    onClick = onToggleSelect,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Icon(
            imageVector = if (entry.isDirectory) ImageVector.vectorResource(CommonR.drawable.ic_folder) else ImageVector.vectorResource(CommonR.drawable.ic_file),
            contentDescription = null,
            tint = if (entry.isDirectory) PrimaryGreen else TextHint(),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = entry.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = entrySubtitle(entry).asString(),
            fontSize = 11.sp,
            color = TextHint(),
            maxLines = 1,
        )
    }
}

// ---------- 搜索结果 ----------

@Composable
private fun SearchResultsContent(
    searching: Boolean,
    results: List<SearchResult>,
    onOpenResult: (SearchResult) -> Unit,
) {
    if (searching) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LinearProgressIndicator(modifier = Modifier.width(200.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.files_search_scanning),
                fontSize = 13.sp,
                color = TextSecondary(),
            )
        }
    } else if (results.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_search),
                contentDescription = null,
                tint = Color(0xFFE5E7EB),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.files_search_empty),
                fontSize = 14.sp,
                color = TextSecondary(),
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(results.size, key = { results[it].logicalPath }) { i ->
                val r = results[i]
                SearchResultRow(result = r, onClick = { onOpenResult(r) })
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResult,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (result.isDirectory) ImageVector.vectorResource(CommonR.drawable.ic_folder) else ImageVector.vectorResource(CommonR.drawable.ic_file),
                contentDescription = null,
                tint = if (result.isDirectory) PrimaryGreen else TextHint(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = result.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (result.matchedInContent) {
                Text(
                    text = stringResource(R.string.files_search_content_hit),
                    fontSize = 11.sp,
                    color = PrimaryGreen,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = result.logicalPath,
            fontSize = 11.sp,
            color = TextHint(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 28.dp),
        )
        if (result.snippet != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = result.snippet,
                fontSize = 12.sp,
                color = TextSecondary(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp),
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 24.dp), thickness = 0.5.dp, color = DividerColor())
}
