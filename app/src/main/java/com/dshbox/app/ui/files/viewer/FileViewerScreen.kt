package com.dshbox.app.ui.files.viewer

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import com.dshbox.app.common.UiText
import com.dshbox.app.ui.asString
import com.dshbox.app.ui.files.riskDialogTextRes
import com.dshbox.app.ui.files.riskLevelOfLayer
import com.dshbox.app.util.Layer
import com.dshbox.app.util.PathMapper
import com.dshbox.app.util.LayerRoots
import com.dshbox.app.util.viewer.ExternalOpener
import com.dshbox.app.util.viewer.ArchiveBrowser
import com.dshbox.app.util.viewer.ContentFingerprint
import com.dshbox.app.util.viewer.FileTypeClassifier
import com.dshbox.app.util.viewer.LargeTextLoader
import com.dshbox.app.util.viewer.OfficeTextExtractor
import com.dshbox.app.util.viewer.TextEncoding
import com.dshbox.app.util.viewer.TextFileStore
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通用文件查看器外壳（1.2.0 §6.1，覆盖式全屏二级页）。
 *
 * **路径契约（实现硬约束）**：
 * 1. 只接收 `logicalPath: String`，不持有 FileEntry 引用——Tab 切回时列表重建不影响查看器状态
 *    （编辑脏状态安全）；
 * 2. 所有 IO 前统一 [PathMapper.resolvePhysical]；resolve 后立即 [com.dshbox.app.util.layerOf]
 *    判层，写入门禁在 resolve 之后执行（§6.1.2）；
 * 3. 失败态：不可读/已删/IO 错误 → 统一错误态 UI（信息 + 重试 + 导出/外部打开出口，§6.1.3）；
 * 4. 分类失败/未知类型 → Hex + 信息卡，**无「不支持预览」死路**（§6.1.4）。
 */
@Composable
internal fun FileViewerScreen(
    logicalPath: String,
    mapper: PathMapper,
    layerRoots: LayerRoots,
    sandboxRunning: Boolean,
    onDismiss: () -> Unit,
    onRequestRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var load by remember { mutableStateOf<ViewerLoad>(ViewerLoad.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // 查看模式覆盖（AUTO = 按分类分发；按文本/按 Hex 强制切换）
    var viewMode by remember(logicalPath) { mutableStateOf(ViewerMode.AUTO) }
    // 编辑开关（文本类；gated 层经强确认后才置 true，§6.1.6/§6.4）
    var editing by remember(logicalPath) { mutableStateOf(false) }
    val textCtrl = remember { TextEditController() }
    var showMenu by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }
    var showNewline by remember { mutableStateOf(false) }
    var dismissAfterSave by remember { mutableStateOf(false) }
    // 编辑进入门禁链：大文件确认 → 风险层强确认 → editing
    var pendingEditGate by remember { mutableStateOf<EditGate?>(null) }
    // 失败态下风险层文件的「外部打开」强确认（文件对象 + 层）
    var pendingFailedOpen by remember { mutableStateOf<Pair<File, Layer>?>(null) }
    // 导出目标暂存（launcher 回调按此拷贝，避免读错文件）
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    // 外部编辑返回后的权限位恢复记录（§6.11.2 rootfs 权限位保护）
    var editReturnFile by remember { mutableStateOf<File?>(null) }
    var editReturnMeta by remember { mutableStateOf<EditReturnMeta?>(null) }

    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    fun toastRes(resId: Int) = Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()

    // ---------------- 装载 ----------------

    LaunchedEffect(logicalPath, reloadKey) {
        load = ViewerLoad.Loading
        load = withContext(Dispatchers.IO) { loadViewerFile(logicalPath, mapper, layerRoots) }
    }

    val ready = load as? ViewerLoad.Ready
    val isTextKind = ready != null && ready.type.kind in TEXT_KINDS
    // 路由决策抽至 ViewerRouting.kt 纯函数（可单测锁定）：OFFICE 抽取成功
    // 以只读文本承接；抽取失败/不承接格式落信息卡，任何格式不得让文件打不开。
    val markupKind = ready?.let { markupKindOf(it.type.kind, it.type.extension) }
    val bodyMode = resolveBodyMode(
        viewMode = viewMode,
        kind = ready?.type?.kind,
        extension = ready?.type?.extension ?: "",
        subType = ready?.type?.subType,
        hasFullBytes = ready?.fullBytes != null,
    )

    // ---------------- 返回与未保存拦截（§6.4.4） ----------------

    BackHandler {
        when {
            textCtrl.dirty -> showUnsaved = true
            else -> onDismiss()
        }
    }

    // 「保存后自动退出」：保存成功使 dirty 归 false
    LaunchedEffect(textCtrl.dirty, dismissAfterSave) {
        if (dismissAfterSave && !textCtrl.dirty) {
            dismissAfterSave = false
            onDismiss()
        }
    }

    // 返工 P1：有损解码（存在无法解码的字节）时回落编辑态——编辑保存会以 U+FFFD
    // 覆盖原字节造成不可逆损坏；用户切换正确编码后 lossy 归 false 可再编辑
    LaunchedEffect(textCtrl.lossy) {
        if (textCtrl.lossy) editing = false
    }

    // ---------------- 外部调用（§6.11） ----------------

    val editLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        val file = editReturnFile
        val meta = editReturnMeta
        editReturnFile = null
        editReturnMeta = null
        if (file != null && meta != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        file.setReadable(meta.readable)
                        file.setWritable(meta.writable)
                        file.setExecutable(meta.executable)
                        file.setLastModified(meta.mtime)
                    }
                }
                // EDIT 写回变更检测（§6.11.2）：内容指纹变化 → 重载查看器
                val fp = withContext(Dispatchers.IO) { ContentFingerprint.of(file) }
                val changed = fp != null && !fp.matches(meta.fingerprint)
                if (changed) toastRes(R.string.files_ext_edit_changed)
                if (changed || fp != null) reloadKey++
                onRequestRefresh()
            }
        }
    }


    fun launchExternalOpenInternal(file: File, mime: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ExternalOpener.openView(context, file, mime) }
            if (!ok) toastRes(R.string.files_exit_no_app)
        }
    }

    fun externalOpen() {
        val r = ready ?: return
        // 返工修正 #5：脏状态下外部打开会看到磁盘旧内容（与编辑态不一致）——同样拦截
        if (textCtrl.dirty) {
            toastRes(R.string.files_exit_dirty_block)
            return
        }
        launchExternalOpenInternal(r.file, ExternalOpener.mimeFor(r.file.name, r.type))
    }

    /** 失败态出口：文件仍存在时按层确认后外部打开（mime 按扩展名兜底 octet-stream）。 */
    fun externalOpenFile(file: File, layer: Layer) {
        if (riskLevelOfLayer(layer) != null) {
            pendingFailedOpen = file to layer
            return
        }
        launchExternalOpenInternal(file, ExternalOpener.mimeFor(file.name, null))
    }

    fun launchExternalEditInternal() {
        val r = ready ?: return
        val mime = ExternalOpener.mimeFor(r.file.name, r.type)
        val intent = ExternalOpener.buildEditIntent(context, r.file, mime)
            ?: ExternalOpener.buildViewIntent(context, r.file, mime)
        if (intent == null) {
            toastRes(R.string.files_exit_no_app)
            return
        }
        // rootfs 权限位保护：发起前记录 rwx/mtime，返回后恢复（§6.11.2）
        editReturnMeta = EditReturnMeta(
            readable = r.file.canRead(),
            writable = r.file.canWrite(),
            executable = r.file.canExecute(),
            mtime = r.file.lastModified(),
            fingerprint = r.fingerprint,
        )
        editReturnFile = r.file
        editLauncher.launch(intent)
    }

    fun externalEdit() {
        val r = ready ?: return
        // §6.11：内建编辑有未保存修改时禁用外部编辑（同一时刻只允许一个写入口）
        if (textCtrl.dirty) {
            toastRes(R.string.files_exit_dirty_block)
            return
        }
        // §4.2/§6.11：风险层强确认后放行
        if (riskLevelOfLayer(r.layer) != null) {
            pendingEditGate = EditGate.EXTERNAL_RISK
            return
        }
        launchExternalEditInternal()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val source = pendingExportFile
        pendingExportFile = null
        val target = uri
        if (source != null && target != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { ExternalOpener.exportSingleFile(context, source, target) }.isSuccess
                }
                toastRes(if (ok) R.string.files_export_done else R.string.files_export_failed)
            }
        }
    }

    /** 导出（Ready 与失败态共用：暂存源文件再启动 SAF，回调按暂存拷贝）。 */
    fun exportFile(file: File) {
        pendingExportFile = file
        exportLauncher.launch(file.name)
    }

    fun share() {
        val r = ready ?: return
        // 返工修正 #5：脏状态下分享外发的是磁盘旧内容——与外部打开同口径拦截
        if (textCtrl.dirty) {
            toastRes(R.string.files_exit_dirty_block)
            return
        }
        val mime = ExternalOpener.mimeFor(r.file.name, r.type)
        val intent = ExternalOpener.buildShareIntent(context, listOf(r.file), mime)
        if (intent == null) {
            toastRes(R.string.files_exit_no_app)
            return
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, r.file.name))
        }.onFailure { toastRes(R.string.files_exit_no_app) }
    }

    // ---------------- 编辑进入门禁（§6.4：大文件分级 + 层强确认） ----------------

    fun requestEdit() {
        val r = ready ?: return
        if (!isTextKind) return
        // 返工 P1：有损解码禁编辑（防 U+FFFD 覆盖原字节）；先切换正确编码再编辑
        if (textCtrl.lossy) {
            toastRes(R.string.files_edit_lossy_block)
            return
        }
        when (r.plan?.policy) {
            LargeTextLoader.Policy.FORCE_READONLY_TAIL -> {
                toastRes(R.string.files_edit_disabled_big)
                return
            }
            LargeTextLoader.Policy.READONLY_CHUNKS -> {
                pendingEditGate = EditGate.BIG_FILE
                return
            }
            else -> {}
        }
        if (riskLevelOfLayer(r.layer) != null) {
            pendingEditGate = EditGate.RISK
            return
        }
        editing = true
    }

    pendingEditGate?.let { gate ->
        when (gate) {
            EditGate.BIG_FILE -> AlertDialog(
                onDismissRequest = { pendingEditGate = null },
                title = { Text(stringResource(R.string.files_edit_big_title)) },
                text = { Text(stringResource(R.string.files_edit_big_msg), fontSize = 14.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        val r = ready
                        pendingEditGate = null
                        if (r != null) {
                            scope.launch {
                                val loaded = withContext(Dispatchers.IO) { com.dshbox.app.util.viewer.TextFileStore.load(r.file) }
                                if (loaded != null) {
                                    load = r.copy(fullBytes = loaded.bytes, fingerprint = loaded.fingerprint)
                                    if (riskLevelOfLayer(r.layer) != null) {
                                        pendingEditGate = EditGate.RISK
                                    } else {
                                        editing = true
                                    }
                                } else {
                                    toastRes(R.string.files_viewer_read_failed)
                                }
                            }
                        }
                    }) { Text(stringResource(R.string.files_edit_big_confirm), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingEditGate = null }) {
                        Text(stringResource(R.string.files_cancel))
                    }
                },
            )
            // 风险层编辑强确认（§4.2：文案含完整性失配与停机建议）与外发确认（§6.11）
            EditGate.RISK, EditGate.EXTERNAL_RISK -> AlertDialog(
                onDismissRequest = { pendingEditGate = null },
                title = { Text(stringResource(R.string.files_risk_title)) },
                text = {
                    Text(
                        text = stringResource(
                            riskDialogTextRes(ready?.layer ?: Layer.BASE, isMoveAction = false, sandboxRunning = sandboxRunning),
                            ready?.file?.name ?: "",
                        ) + if (gate == EditGate.EXTERNAL_RISK) {
                            stringResource(R.string.files_exit_external_warning)
                        } else {
                            ""
                        },
                        fontSize = 14.sp,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val g = pendingEditGate
                        pendingEditGate = null
                        if (g == EditGate.RISK) editing = true else launchExternalEditInternal()
                    }) { Text(stringResource(R.string.files_risk_continue), color = MaterialTheme.colorScheme.primary) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingEditGate = null }) {
                        Text(stringResource(R.string.files_cancel))
                    }
                },
            )
        }
    }

    // ---------------- 失败态风险层「外部打开」强确认（§4.2/§6.11） ----------------

    pendingFailedOpen?.let { (file, layer) ->
        AlertDialog(
            onDismissRequest = { pendingFailedOpen = null },
            title = { Text(stringResource(R.string.files_risk_title)) },
            text = {
                Text(
                    text = stringResource(
                        riskDialogTextRes(layer, isMoveAction = false, sandboxRunning = sandboxRunning),
                        file.name,
                    ) + stringResource(R.string.files_exit_external_warning),
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = file
                    pendingFailedOpen = null
                    launchExternalOpenInternal(target, ExternalOpener.mimeFor(target.name, null))
                }) { Text(stringResource(R.string.files_risk_continue), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFailedOpen = null }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    // ---------------- 未保存三选一（§6.4.4） ----------------

    if (showUnsaved) {
        AlertDialog(
            onDismissRequest = { showUnsaved = false },
            title = { Text(stringResource(R.string.files_unsaved_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.files_unsaved_msg,
                        ready?.file?.name ?: File(logicalPath).name,
                    ) + stringResource(R.string.files_unsaved_draft_hint),
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showUnsaved = false
                    dismissAfterSave = true
                    textCtrl.saveSignal++
                }) { Text(stringResource(R.string.files_unsaved_save), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsaved = false
                    textCtrl.discardSignal++
                    onDismiss()
                }) { Text(stringResource(R.string.files_unsaved_discard)) }
                TextButton(onClick = { showUnsaved = false }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    // ---------------- 换行转换入口（§6.4：保存时默认还原原风格，另提供转换） ----------------

    if (showNewline) {
        AlertDialog(
            onDismissRequest = { showNewline = false },
            title = { Text(stringResource(R.string.files_menu_newline)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.files_newline_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    TextEncoding.NewlineStyle.entries.forEach { style ->
                        DropdownMenuItem(
                            text = { Text(style.label + if (textCtrl.newlineTarget == style) " ✓" else "") },
                            onClick = {
                                textCtrl.newlineTarget = style
                                showNewline = false
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_newline_follow)) },
                        onClick = {
                            textCtrl.newlineTarget = null
                            showNewline = false
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showNewline = false }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    // ---------------- 信息卡（FallbackPanel 复用） ----------------

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            title = { Text(stringResource(R.string.files_info_title)) },
            text = {
                ready?.let { r ->
                    FallbackPanel(
                        name = r.file.name,
                        logicalPath = logicalPath,
                        physicalPath = r.physicalPath,
                        sizeText = com.dshbox.app.util.formatFileSize(r.size),
                        permissionText = r.permissionText,
                        typeText = r.type.subType ?: r.type.kind.name,
                        modifiedText = timeFmt.format(Date(r.lastModified).toInstant()),
                        onExternalOpen = { showInfo = false; externalOpen() },
                        onExternalEdit = { showInfo = false; externalEdit() },
                        onShare = { showInfo = false; share() },
                        onExport = { showInfo = false; exportFile(r.file) },
                        onOpenAsText = if (!isTextKind) ({ showInfo = false; viewMode = ViewerMode.TEXT }) else null,
                        onOpenAsHex = if (bodyMode != ViewerMode.HEX) ({ showInfo = false; viewMode = ViewerMode.HEX }) else null,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.files_close))
                }
            },
        )
    }

    // ---------------- 页面 ----------------
    // 返工：覆盖式二级页必须有衬底——此前根 Column 无背景，透明透出底层
    // （文件列表/首页 DSH 背景），视觉表现为「PDF 显示在首页背景里」（缺陷 2）。
    // 返工 #7：根级 clickable 空动作**吞噬点击**——覆盖页空白区若无 pointer 消费，
    // Compose 命中测试会继续下探到 FilesScreen 列表项 → 点文件内空白处跳转到别的文件。
    // （indication=null 无涟漪；子级滚动/点按不受影响——tap 由上层消费后子级不再触发）
    val rootInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = rootInteraction, indication = null) { },
    ) {
        // ---- 顶栏：返回 · 文件名/逻辑路径 · 编辑切换 · 更多菜单（§6.1） ----
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (textCtrl.dirty) showUnsaved = true else onDismiss()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.files_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = ready?.file?.name ?: File(logicalPath).name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = logicalPath,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isTextKind && (ready?.plan?.policy != LargeTextLoader.Policy.FORCE_READONLY_TAIL)) {
                // 返工 #2：编辑态提供显式「保存」按钮（此前只能退出时经未保存弹窗保存）
                if (editing) {
                    IconButton(onClick = { textCtrl.saveSignal++ }) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = stringResource(R.string.files_menu_save),
                            tint = if (textCtrl.dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = {
                    if (editing) {
                        editing = false
                    } else {
                        requestEdit()
                    }
                }) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.files_edit_toggle),
                        tint = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.files_more),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_menu_info)) },
                        onClick = { showMenu = false; showInfo = true },
                    )
                    if (bodyMode != ViewerMode.TEXT) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_exit_open_as_text)) },
                            onClick = { showMenu = false; viewMode = ViewerMode.TEXT },
                        )
                    }
                    if (bodyMode != ViewerMode.HEX) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_exit_open_as_hex)) },
                            onClick = { showMenu = false; viewMode = ViewerMode.HEX },
                        )
                    }
                    if (isTextKind && hasFullText(load)) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_menu_encoding)) },
                            onClick = { showMenu = false; textCtrl.encodingSignal++ },
                        )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_menu_newline)) },
                        onClick = { showMenu = false; showNewline = true },
                    )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_exit_external_open)) },
                        onClick = { showMenu = false; externalOpen() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_exit_external_edit)) },
                        onClick = { showMenu = false; externalEdit() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_exit_share)) },
                        onClick = { showMenu = false; share() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_exit_export)) },
                        onClick = {
                            showMenu = false
                            ready?.let { exportFile(it.file) }
                        },
                    )
                }
            }
        }

        // ---- 内容分发 ----
        when {
            load is ViewerLoad.Loading -> CenterHint(stringResource(R.string.files_viewer_loading))
            load is ViewerLoad.Failed -> {
                val f = load as ViewerLoad.Failed
                // §6.1.3 统一失败态：信息 + 重试 + 导出/外部打开出口（返工修正 #3：
                // 文件仍存在时给出导出与外部打开出口，不再只有信息卡）
                FallbackPanel(
                    name = File(logicalPath).name,
                    logicalPath = logicalPath,
                    physicalPath = f.physicalPath,
                    sizeText = if (f.size > 0) com.dshbox.app.util.formatFileSize(f.size) else stringResource(R.string.files_info_unknown),
                    permissionText = f.permissionText.ifEmpty { stringResource(R.string.files_info_unknown) },
                    typeText = stringResource(R.string.files_info_unknown),
                    modifiedText = if (f.lastModified > 0) timeFmt.format(Date(f.lastModified).toInstant()) else stringResource(R.string.files_info_unknown),
                    onExternalOpen = f.file?.let { file -> ({ externalOpenFile(file, f.layer) }) },
                    onExternalEdit = null,
                    onShare = null,
                    onExport = f.file?.let { file -> ({ exportFile(file) }) },
                    onOpenAsText = null,
                    onOpenAsHex = null,
                )
                Text(
                    text = f.message?.asString() ?: stringResource(R.string.files_viewer_read_failed),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                TextButton(
                    onClick = { reloadKey++ },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text(stringResource(R.string.files_viewer_retry)) }
            }
            ready != null && bodyMode == ViewerMode.INFO -> {
                // 信息卡兜底：不承接类型（音视频/7z/rar/纯压缩流）、
                // OFFICE 抽取失败/空文本、渲染器 onFallback 降级落点；全部出口保留（§6.3/§6.1.4）
                val r = ready
                // 返工 #5：OFFICE 未抽出文本时给解释（此前用户易误点「按文本打开」看 ZIP 字节乱码；
                // 有损警告虽正确但仍把抽取失败当缺陷报）——隐藏按文本出口并说明原因
                val officeExtractFailed = r.type.kind == FileTypeClassifier.FileKind.OFFICE && r.fullBytes == null
                if (officeExtractFailed) {
                    Text(
                        text = stringResource(R.string.files_office_no_text),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                FallbackPanel(
                    name = r.file.name,
                    logicalPath = logicalPath,
                    physicalPath = r.physicalPath,
                    sizeText = com.dshbox.app.util.formatFileSize(r.size),
                    permissionText = r.permissionText,
                    typeText = r.type.subType ?: r.type.kind.name,
                    modifiedText = timeFmt.format(Date(r.lastModified).toInstant()),
                    onExternalOpen = { externalOpen() },
                    onExternalEdit = { externalEdit() },
                    onShare = { share() },
                    onExport = { exportFile(r.file) },
                    onOpenAsText = if (officeExtractFailed) null else ({ viewMode = ViewerMode.TEXT }),
                    onOpenAsHex = { viewMode = ViewerMode.HEX },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
            }
            ready != null && bodyMode == ViewerMode.TEXT -> TextCodeViewer(
                file = ready.file,
                fullBytes = ready.fullBytes,
                detection = ready.detection ?: TextEncoding.Detection(TextEncoding.TextCharset.UTF_8, 0),
                fingerprint = ready.fingerprint,
                plan = ready.plan ?: LargeTextLoader.planFor(ready.size),
                highlightLanguage = FileTypeClassifier.highlightLanguageOf(ready.type.extension),
                editing = editing,
                controller = textCtrl,
                onRequestRefresh = onRequestRefresh,
                onToast = { toast(it) },
                modifier = Modifier.weight(1f),
            )
            ready != null && bodyMode == ViewerMode.IMAGE -> ImageViewer(
                file = ready.file,
                modifier = Modifier.weight(1f),
            )
            ready != null && bodyMode == ViewerMode.PDF -> PdfViewer(
                file = ready.file,
                // 渲染异常降级信息卡（§6.6/任务要求：任何格式不得让文件打不开）
                onFallback = { viewMode = ViewerMode.INFO },
                modifier = Modifier.weight(1f),
            )
            ready != null && bodyMode == ViewerMode.ARCHIVE -> ArchiveViewer(
                file = ready.file,
                onFallback = { viewMode = ViewerMode.INFO },
                modifier = Modifier.weight(1f),
            )
            ready != null && bodyMode == ViewerMode.MARKUP && markupKind != null -> MarkupViewer(
                file = ready.file,
                fullBytes = ready.fullBytes,
                detection = ready.detection ?: TextEncoding.Detection(TextEncoding.TextCharset.UTF_8, 0),
                fingerprint = ready.fingerprint,
                plan = ready.plan ?: LargeTextLoader.planFor(ready.size),
                previewKind = markupKind,
                editing = editing,
                controller = textCtrl,
                // 「编辑」标签与顶栏铅笔走同一条门禁链（大文件/风险层强确认，§6.4/§4.2）
                onRequestEdit = { requestEdit() },
                onRequestRefresh = onRequestRefresh,
                onToast = { toast(it) },
                modifier = Modifier.weight(1f),
            )
            ready != null && bodyMode == ViewerMode.HEX -> HexViewer(
                file = ready.file,
                magicLabel = ready.type.subType?.let { "Magic: $it" },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun hasFullText(load: ViewerLoad): Boolean = (load as? ViewerLoad.Ready)?.fullBytes != null

// 固定 Locale.US——文件时间戳保持 ISO 风格西文数字，
    // 且不缓存系统 Locale（应用内切语言后仍按西文数字渲染）。
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())

/** 查看模式枚举与路由决策已抽至 ViewerRouting.kt。 */

/** 编辑进入门禁链。 */
private enum class EditGate { BIG_FILE, RISK, EXTERNAL_RISK }

/** 外部编辑发起前记录的元数据（返回后恢复权限位 + 变更检测，§6.11.2）。 */
private data class EditReturnMeta(
    val readable: Boolean,
    val writable: Boolean,
    val executable: Boolean,
    val mtime: Long,
    val fingerprint: ContentFingerprint?,
)

private sealed interface ViewerLoad {
    data object Loading : ViewerLoad

    /** 统一失败态（§6.1.3；file 非空 = 文件仍存在，出口提供导出/外部打开）。 */
    data class Failed(
        val message: UiText?,
        val file: File?,
        val layer: Layer,
        val size: Long,
        val lastModified: Long,
        val permissionText: String,
        val physicalPath: String,
    ) : ViewerLoad

    data class Ready(
        val file: File,
        val layer: Layer,
        val type: FileTypeClassifier.FileType,
        val size: Long,
        val lastModified: Long,
        val permissionText: String,
        val physicalPath: String,
        val fingerprint: ContentFingerprint?,
        /** 文本全量字节：FULL_EDITABLE 直接载入；分块只读为 null（显式确认编辑后补载）。 */
        val fullBytes: ByteArray?,
        val detection: TextEncoding.Detection?,
        val plan: LargeTextLoader.Plan?,
    ) : ViewerLoad
}

/** 装载入口（IO 线程调用）：resolve → 立即判层（§6.1.2 硬约束）→ 分类 → 分级载入。 */
private fun loadViewerFile(logicalPath: String, mapper: PathMapper, layerRoots: LayerRoots): ViewerLoad {
    val logical = File(logicalPath)
    val physical = runCatching { mapper.resolvePhysical(logical) }.getOrDefault(logical)
    // resolve 后立即 layerOf：写入门禁必须基于物理路径（逻辑路径不携带层信息）
    val layer = com.dshbox.app.util.layerOf(physical.absolutePath, layerRoots)
    val perm = runCatching {
        (if (physical.isDirectory) "d" else "-") +
            (if (physical.canRead()) "r" else "-") +
            (if (physical.canWrite()) "w" else "-") +
            (if (physical.canExecute()) "x" else "-")
    }.getOrDefault("")
    val size = runCatching { physical.length() }.getOrDefault(0L)
    val mtime = runCatching { physical.lastModified() }.getOrDefault(0L)
    if (!physical.isFile) {
        return ViewerLoad.Failed(
            message = if (physical.isDirectory) UiText.Res(R.string.viewer_is_folder) else null,
            file = physical.takeIf { it.isFile },
            layer = layer,
            size = size, lastModified = mtime, permissionText = perm,
            physicalPath = physical.absolutePath,
        )
    }
    val head = FileTypeClassifier.readHead(physical, 8192 + 512)
    if (head.isEmpty() && size > 0) {
        return ViewerLoad.Failed(null, physical, layer, size, mtime, perm, physical.absolutePath)
    }
    val type = FileTypeClassifier.classify(physical.name, head, size)
    var detection = TextEncoding.detect(head.copyOf(minOf(head.size, 8192)))
    val plan = LargeTextLoader.planFor(size)
    var fullBytes: ByteArray? = null
    var fingerprint: ContentFingerprint? = null
    if (type.kind in TEXT_KINDS && plan.editable) {
        val loaded = TextFileStore.load(physical)
            ?: return ViewerLoad.Failed(null, physical, layer, size, mtime, perm, physical.absolutePath)
        fullBytes = loaded.bytes
        fingerprint = loaded.fingerprint
    } else {
        fingerprint = ContentFingerprint.of(physical)
        if (type.kind == FileTypeClassifier.FileKind.OFFICE) {
            // §6.10：docx/xlsx 抽取为只读纯文本（固定 UTF-8 检测；编辑入口被 isTextKind 门禁
            // 关闭，绝不写回原文档）。抽取失败/空文本保持 null → AUTO 落信息卡兜底；
            // pptx 不支持抽取（计划 §6.10），同样落信息卡 + 外部打开。
            val extracted = when (type.extension) {
                "docx" -> OfficeTextExtractor.extractDocx(physical)
                "xlsx" -> OfficeTextExtractor.extractXlsx(physical)
                else -> null
            }
            if (extracted != null && extracted.text.isNotEmpty()) {
                fullBytes = extracted.text.toByteArray(Charsets.UTF_8)
                detection = TextEncoding.Detection(TextEncoding.TextCharset.UTF_8, 0)
            }
        }
    }
    return ViewerLoad.Ready(
        file = physical,
        layer = layer,
        type = type,
        size = size,
        lastModified = mtime,
        permissionText = perm,
        physicalPath = physical.absolutePath,
        fingerprint = fingerprint,
        fullBytes = fullBytes,
        detection = detection,
        plan = plan,
    )
}

@Composable
private fun CenterHint(text: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
