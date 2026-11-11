package com.dshbox.app.ui.remote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.dshbox.app.R
import com.dshbox.app.ui.configurationAwareResources
import com.dshbox.app.ui.files.MEDIA_ROOT_AUDIO
import com.dshbox.app.ui.files.MEDIA_ROOT_IMAGES
import com.dshbox.app.ui.webview.SandboxFilePickerDialog
import com.dshbox.app.ui.webview.UploadPickRoot
import com.dshbox.app.ui.webview.UploadSource
import com.dshbox.app.ui.webview.UploadSourcePanel
import com.dshbox.app.ui.webview.createCaptureIntent
import com.dshbox.app.ui.webview.parsePickedUris
import com.dshbox.app.ui.webview.systemContentIntent
import com.dshbox.app.ui.webview.systemFilePickerIntent
import com.dshbox.app.util.ShareArea
import com.dshbox.dsh.renderer.api.DshSelectedFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * 系统相册选择器与它的多选上限（字面量而非 `MediaStore` API 33 字段：minSdk 29
 * 直接引用会被 API 级别检查拦下；旧系统解析不到该动作就抛
 * ActivityNotFoundException，由 [launchSystemPicker] 退到系统文件选择器）。
 * 与本地 DSH 页各留一份字面量，避免板块之间互相牵连。
 */
private const val ACTION_PICK_IMAGES = "android.provider.action.PICK_IMAGES"
private const val EXTRA_PICK_IMAGES_MAX = "android.provider.extra.PICK_IMAGES_MAX"
private const val PICK_IMAGES_MAX = 100

/**
 * 远程页附件下载目录名：宿主下载能力落点为公共存储 `DSHBox/download-remote`
 * （与共享区同一访问模型，见 [ShareArea]；目录在代理首次落盘时创建）。
 */
internal const val REMOTE_DOWNLOAD_DIR_NAME = "download-remote"

/**
 * 远程页附件上传协调器：把远程会话页面的 `<input type="file">` 请求接到
 * 宿主自绘的七来源面板（与本地 DSH 页同一个 [UploadSourcePanel]）。
 *
 * ## 三态结清约定（与 RemoteConnectionHost.pickUploadFiles 对齐）
 *
 *  - null：本协调器不产出（宿主能力判定不经过这里）；
 *  - 空列表 = 用户取消（关面板 / 关选择器 / 拍摄取消 / 授权被拒）；
 *  - 非空列表 = 选中文件，直接回填给页面。
 *
 * ## 通道与本地 DSH 页同源
 *
 * 各来源的取文件通道与 [com.dshbox.app.ui.webview.DshWebViewScreen] 完全一致：
 * helper（[createCaptureIntent] / [parsePickedUris] / [systemContentIntent] /
 * [systemFilePickerIntent]）与选择器（[SandboxFilePickerDialog]）直接复用同模块
 * internal 实现，不复制行为差异。差别只有交付端：本地页把结果交回 WebView 容器，
 * 本协调器以挂起调用把 `List<DshSelectedFile>` 交回渲染宿主。
 *
 * ## 一次一个请求
 *
 * 渲染宿主保证同刻只有一次挂起请求（新请求顶替旧请求时旧请求先以取消结清），
 * 本协调器同样只持一个 [request]：新 [awaitRequest] 到来时把旧请求结清为取消。
 */
internal class RemoteUploadCoordinator {

    /** 一次挂起中的上传请求。[settled] 防止重复 resume。 */
    internal class UploadRequest(
        val multiple: Boolean,
        internal val continuation: CancellableContinuation<List<DshSelectedFile>?>,
    ) {
        var settled = false
    }

    /** 当前挂起请求；null = 无请求（面板与各通道覆盖层均不渲染）。 */
    var request by mutableStateOf<UploadRequest?>(null)
        internal set

    /** 非 null = 本板块全屏选择器（工作区 / 共享区）已打开。 */
    var localPickRoot by mutableStateOf<UploadPickRoot?>(null)
        internal set

    /** 非 null = 已进入面板之外的系统 UI（拍摄 / 系统选择器 / 授权页），面板暂不渲染。 */
    var auxiliaryUiOpen by mutableStateOf(false)
        internal set

    // 拍摄在途状态：仅拍摄回调链路使用，不驱动重组。
    internal var pendingCaptureIntent: Intent? = null
    internal var pendingCaptureUri: Uri? = null
    internal var pendingCaptureFile: File? = null

    /**
     * 挂起一次上传请求，直到用户完成选择或取消。
     * 返回空列表 = 取消；非空 = 选中文件（三态约定见类注释）。
     */
    suspend fun awaitRequest(multiple: Boolean, mimeTypes: List<String>): List<DshSelectedFile>? =
        suspendCancellableCoroutine { continuation ->
            // 一次只挂起一个请求：新请求顶替旧请求时先以取消结清旧请求
            request?.let { previous -> settle(previous, emptyList()) }
            val created = UploadRequest(multiple, continuation)
            request = created
            continuation.invokeOnCancellation {
                // 渲染宿主释放（release 取消协程）：就地清场，不再回填任何 UI
                created.settled = true
                if (request === created) clearAll()
            }
        }

    /** 以选中文件结清当前请求；null 视为取消（空列表）。 */
    fun onPickedFiles(files: List<DshSelectedFile>?) {
        val current = request ?: return
        settle(current, files ?: emptyList())
    }

    /** 系统选择器 / 系统相册返回：null = 用户取消。 */
    fun onSystemPicked(uris: Array<Uri>?) {
        onPickedFiles(uris?.map { DshSelectedFile(it) })
    }

    /** 用户放弃（关面板 / 关选择器 / 授权被拒 / 拍摄取消）：以取消结清。 */
    fun cancelCurrent() {
        request?.let { current -> settle(current, emptyList()) }
    }

    /** 拍摄输出无法准备或 launch 失败：清掉在途拍摄状态后由调用方结清。 */
    fun clearCaptureState() {
        pendingCaptureIntent = null
        pendingCaptureUri = null
        pendingCaptureFile = null
    }

    /** 全面清场：请求终止（结清或被顶替）后把所有覆盖层状态归零。 */
    private fun clearAll() {
        request = null
        localPickRoot = null
        auxiliaryUiOpen = false
        clearCaptureState()
    }

    private fun settle(target: UploadRequest, value: List<DshSelectedFile>?) {
        if (target.settled) return
        target.settled = true
        if (request === target) clearAll()
        target.continuation.resume(value)
    }
}

/**
 * 协调器的 Compose 宿主：注册各来源通道的 ActivityResult launcher（必须常驻
 * 组合，不能条件挂载），并在请求挂起时渲染来源面板与工作区 / 共享区选择器。
 *
 * 挂载位置由调用方决定（首页壳层的内容 Box 内、页签内容之外）：面板与选择器
 * 是覆盖层，不属于任何页签的内容树。
 */
@Composable
internal fun RemoteUploadCoordinatorHost(coordinator: RemoteUploadCoordinator) {
    val context = LocalContext.current
    val resources = configurationAwareResources()

    // 系统文件选择器 / 系统相册 —— 相册（GALLERY）、手机文件（PHONE）、音频（AUDIO）
    // 共用。解析与「只投递给发起者」的口径与本地 DSH 页一致：本协调器同时只有
    // 一个请求，结果只可能属于它。
    val systemPickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        coordinator.onSystemPicked(parsePickedUris(result.resultCode, result.data))
    }

    // 相机拍摄（拍照 / 录像）：成功回填输出 URI（连同本地 File 元数据）；取消则
    // 清掉可能已建出的空文件并以取消结清（与本地 DSH 页同口径）。
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = coordinator.pendingCaptureUri
        val file = coordinator.pendingCaptureFile
        coordinator.clearCaptureState()
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            coordinator.onPickedFiles(listOf(DshSelectedFile(
                uri = uri, displayName = file?.name, localFile = file,
            )))
        } else {
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            coordinator.cancelCurrent()
        }
    }

    // CAMERA 运行时权限：授权后直接拍（与本地 DSH 页同口径）；被拒以取消结清。
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val intent = coordinator.pendingCaptureIntent
        val uri = coordinator.pendingCaptureUri
        if (granted && intent != null) {
            // 设备没有相机 App 时 launch 会抛 ActivityNotFoundException：接住并以
            // 取消结清，不留悬空请求（与本地 DSH 页同口径）。
            if (runCatching { captureLauncher.launch(intent) }.isFailure) {
                coordinator.clearCaptureState()
                runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
                coordinator.cancelCurrent()
            }
        } else {
            coordinator.clearCaptureState()
            runCatching { uri?.let { context.contentResolver.delete(it, null, null) } }
            coordinator.cancelCurrent()
        }
    }

    // 共享区取文件需要公共存储的完整读写能力：未授权时引导去系统设置页，
    // 返回后重判授权状态（与本地 DSH 页 onShareAccessReturned 同口径）。
    // 请求已不在（被顶替 / 渲染宿主已释放）时忽略晚到的授权返回。
    val onShareAccessReturned: () -> Unit = {
        when {
            coordinator.request == null -> Unit
            ShareArea.hasAccess(context) -> {
                runCatching { ShareArea.ensureReady() }
                coordinator.localPickRoot = UploadPickRoot.SHARE
            }
            else -> {
                Toast.makeText(
                    context,
                    resources.getString(R.string.webview_upload_share_denied),
                    Toast.LENGTH_SHORT,
                ).show()
                coordinator.cancelCurrent()
            }
        }
    }
    val shareAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { onShareAccessReturned() }
    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { onShareAccessReturned() }

    /** 起一个系统选择器意图；找不到可用 Activity 时以取消结清。 */
    val launchSystemPicker: (Intent, Intent?) -> Unit = { intent, fallback ->
        coordinator.auxiliaryUiOpen = true
        val first = runCatching { systemPickLauncher.launch(intent) }
        val launched = if (first.isFailure && fallback != null) {
            runCatching { systemPickLauncher.launch(fallback) }
        } else first
        if (launched.isFailure) coordinator.cancelCurrent()
    }

    /** 进入某个来源的取文件通道（与本地 DSH 页 startUploadSource 同一套分流）。 */
    val startUploadSource: (UploadSource) -> Unit = { source ->
        val multiple = coordinator.request?.multiple ?: false
        when (source) {
            UploadSource.PHOTO, UploadSource.VIDEO -> {
                if (coordinator.pendingCaptureIntent != null) {
                    coordinator.cancelCurrent()
                } else {
                    val prepared = createCaptureIntent(context, video = source == UploadSource.VIDEO)
                    if (prepared == null) {
                        // 输出无法准备（FileProvider 未配置等）：以取消结清
                        coordinator.cancelCurrent()
                    } else {
                        val (intent, uri, file) = prepared
                        coordinator.pendingCaptureIntent = intent
                        coordinator.pendingCaptureUri = uri
                        coordinator.pendingCaptureFile = file
                        coordinator.auxiliaryUiOpen = true
                        val granted = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                        val launched = if (granted) {
                            runCatching { captureLauncher.launch(intent) }
                        } else {
                            runCatching { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) }
                        }
                        if (launched.isFailure) {
                            coordinator.clearCaptureState()
                            runCatching { context.contentResolver.delete(uri, null, null) }
                            coordinator.cancelCurrent()
                        }
                    }
                }
            }

            UploadSource.GALLERY -> {
                // 相册 = 系统自带相册选择（图片 + 视频）；没有系统相册的机器退到
                // 系统文件选择器并尽力停在图片位置（与本地 DSH 页同口径）。
                val fallback = systemFilePickerIntent(
                    mimeTypes = arrayOf("image/*", "video/*"),
                    allowMultiple = multiple,
                    initialUri = Uri.parse(MEDIA_ROOT_IMAGES),
                )
                val gallery = Intent(ACTION_PICK_IMAGES).apply {
                    type = "*/*"
                    putExtra(EXTRA_PICK_IMAGES_MAX, if (multiple) PICK_IMAGES_MAX else 1)
                }
                launchSystemPicker(gallery, fallback)
            }

            UploadSource.PHONE ->
                launchSystemPicker(systemContentIntent(multiple), null)

            UploadSource.AUDIO ->
                launchSystemPicker(
                    systemFilePickerIntent(
                        mimeTypes = arrayOf("audio/*"),
                        allowMultiple = multiple,
                        initialUri = Uri.parse(MEDIA_ROOT_AUDIO),
                    ),
                    null,
                )

            UploadSource.WORKSPACE -> {
                coordinator.auxiliaryUiOpen = true
                coordinator.localPickRoot = UploadPickRoot.WORKSPACE
            }

            UploadSource.SHARE -> {
                if (ShareArea.hasAccess(context)) {
                    runCatching { ShareArea.ensureReady() }
                    coordinator.auxiliaryUiOpen = true
                    coordinator.localPickRoot = UploadPickRoot.SHARE
                } else {
                    coordinator.auxiliaryUiOpen = true
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
                            resources.getString(R.string.webview_upload_share_denied),
                            Toast.LENGTH_SHORT,
                        ).show()
                        coordinator.cancelCurrent()
                    }
                }
            }
        }
    }

    // ── 来源面板：请求挂起且未进入其它通道 UI 时渲染 ──
    // 关闭即结清为取消（空列表）：渲染宿主据此以「取消」回填，`<input>` 不锁死。
    if (coordinator.request != null &&
        coordinator.localPickRoot == null &&
        !coordinator.auxiliaryUiOpen
    ) {
        UploadSourcePanel(
            onDismiss = { coordinator.cancelCurrent() },
            onPick = { source -> startUploadSource(source) },
        )
    }

    // ── 工作区 / 共享区选择器：内容树内覆盖层（inset 由父容器自适应下发）──
    val pickRoot = coordinator.localPickRoot
    if (pickRoot != null) {
        SandboxFilePickerDialog(
            root = pickRoot,
            multiple = coordinator.request?.multiple ?: false,
            backEnabled = true,
            onDismiss = { coordinator.cancelCurrent() },
            // 选择器直接持有本地 File（uri + localFile 齐备），原样上抛
            onConfirm = { files -> coordinator.onPickedFiles(files) },
        )
    }
}
