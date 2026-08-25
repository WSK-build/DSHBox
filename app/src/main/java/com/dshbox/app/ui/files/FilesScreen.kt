package com.dshbox.app.ui.files

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import com.dshbox.app.util.ArchiveExtractor
import com.dshbox.app.util.ConflictMode
import com.dshbox.app.util.FileEntry
import com.dshbox.app.util.FileOps
import com.dshbox.app.util.GlobalSearch
import com.dshbox.app.util.PathMapper
import com.dshbox.app.util.ProgressListener
import com.dshbox.app.util.RiskLevel
import com.dshbox.app.util.SearchResult
import com.dshbox.app.util.entrySubtitle
import com.dshbox.app.util.formatFileSize
import com.dshbox.app.util.queryDisplayName
import com.dshbox.app.util.resolveConflictName
import com.dshbox.app.util.sanitizeFileName
import com.dshbox.app.util.scanDirectory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Design tokens (spec) ---
private val PrimaryGreen = Color(0xFF10A37F)
private val PageBg = Color(0xFFFFFFFF)
private val CardBg = Color(0xFFF8FAF9)
private val LightGreenCard = Color(0xFFF2F9F6)
private val SelectedRowBg = Color(0xFFEAF8F4)
private val TextPrimary = Color(0xFF1F2937)
private val TextSecondary = Color(0xFF6B7280)
private val TextHint = Color(0xFF9CA3AF)
private val DividerColor = Color(0xFFF3F4F6)
private val ControlBg = Color(0xFFF3F4F6)
private val CardShadow = Color(0x0A000000)

private enum class SortMode { NAME, TIME, SIZE }

private enum class ViewMode { LIST, GRID }

/** rootfs 顶层系统绑定目录（guest 内由 PRoot --bind 提供）。 */
private val SYSTEM_DIR_NAMES = listOf("proc", "sys", "dev", "system", "apex", "tmp")

/** 进度对话框状态。 */
private data class ProgressUi(
    val active: Boolean = false,
    val stage: String = "",
    val done: Long = 0L,
    val total: Long = -1L,
)

/** 待导入的单个文件。 */
private data class PendingImport(val uri: Uri, val targetDir: File, val baseName: String)

/** 待合并的解压结果（已解压到临时目录，冲突确认后并入目标目录）。 */
private data class PendingMerge(val extractedDir: File, val targetDir: File)

@Composable
fun FilesScreen(modifier: Modifier = Modifier, isActiveTab: Boolean = true) {
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
    // L1 node / L2 dsh layers are bound into the guest at /usr/local and
    // /opt/dshapp/runtime; mirror those binds so the file manager shows the
    // complete virtual-system tree (base + node + dsh + user-data).
    val nodeLayer = remember { File(context.filesDir, "runtime/runtime-current/node").takeIf { it.isDirectory } }
    val dshLayer = remember { File(context.filesDir, "runtime/runtime-current/dsh").takeIf { it.isDirectory } }
    val mapper = remember { PathMapper(sandboxRoot, workspaceRoot, nodeLayer, dshLayer) }

    var rootMode by remember { mutableIntStateOf(0) }
    val root = if (rootMode == 0) sandboxRoot else workspaceRoot
    val isWorkspaceView = rootMode == 1
    val rootLabel = if (rootMode == 0) {
        stringResource(R.string.files_root_sandbox)
    } else {
        stringResource(R.string.files_root_workspace) + "  /root/projects"
    }

    var currentDir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    /** 切换 rootMode 时携带的目标目录（搜索结果跨根跳转等场景），避免被根重置覆盖。 */
    var pendingNavigateDir by remember { mutableStateOf<File?>(null) }

    // 全局搜索
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    // 对话框状态
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var renameName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var showImportMenu by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showExportChoice by remember { mutableStateOf(false) }
    var showConflictDialog by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<PendingImport?>(null) }
    var pendingMerge by remember { mutableStateOf<PendingMerge?>(null) }
    var showRiskDialog by remember { mutableStateOf(false) }
    var pendingRiskEntry by remember { mutableStateOf<FileEntry?>(null) }
    var riskAction by remember { mutableStateOf("") }
    var showPreviewDialog by remember { mutableStateOf(false) }
    var previewName by remember { mutableStateOf("") }
    var previewText by remember { mutableStateOf("") }
    var showErrorDialog by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(ProgressUi()) }
    var progressJob by remember { mutableStateOf<Job?>(null) }

    // 导入参数（launcher 回调前暂存）
    var importMode by remember { mutableStateOf("file") } // file | extract
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

    fun showProgress(stage: String, done: Long = 0L, total: Long = -1L) {
        progress = ProgressUi(active = true, stage = stage, done = done, total = total)
    }

    fun clearProgress() {
        progress = ProgressUi()
    }

    fun cancelProgressJob() {
        progressJob?.cancel()
        progressJob = null
    }

    fun refreshEntries() {
        val dir = currentDir
        val isTop = rootMode == 0 && dir.absolutePath == sandboxRoot.absolutePath
        scope.launch {
            val all = withContext(Dispatchers.IO) { scanDirectory(dir, mapper, isTop) }
            if (dir.absolutePath != currentDir.absolutePath) return@launch
            entries = all.sortedWith(
                when (sortMode) {
                    SortMode.NAME -> compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() }
                    SortMode.TIME -> compareBy<FileEntry> { !it.isDirectory }.thenByDescending { it.lastModified }
                    SortMode.SIZE -> compareBy<FileEntry> { !it.isDirectory }
                        .thenByDescending { if (!it.isDirectory) it.size else 0L }
                },
            )
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

    fun isRiskEntry(entry: FileEntry): Boolean = entry.risk != RiskLevel.NORMAL

    /** 当前目录的风险级别（系统目录 / 运行环境层 / DSH 内部数据目录），无风险返回 null。基于物理路径判断（M4）。 */
    fun protectedRiskLevel(dir: File): RiskLevel? {
        val physical = mapper.resolvePhysical(dir)
        val pp = physical.absolutePath
        if (!isWorkspaceView) {
            // 运行环境层（L1 node -> /usr/local、L2 dsh -> /opt/dshapp/runtime）属于
            // 内部产物：改动/删除会破坏运行环境，故视为高风险（SYSTEM_DIR）。
            if (nodeLayer != null && (pp == nodeLayer.absolutePath || pp.startsWith("${nodeLayer.absolutePath}${File.separator}"))) return RiskLevel.SYSTEM_DIR
            if (dshLayer != null && (pp == dshLayer.absolutePath || pp.startsWith("${dshLayer.absolutePath}${File.separator}"))) return RiskLevel.SYSTEM_DIR
            val rel = pp.removePrefix(sandboxRoot.absolutePath).trimStart('/')
            if (rel.isEmpty()) return null
            val first = rel.substringBefore('/')
            if (first in SYSTEM_DIR_NAMES) return RiskLevel.SYSTEM_DIR
        }
        val relW = pp.removePrefix(workspaceRoot.absolutePath).trimStart('/')
        return if (relW == ".dsh" || relW.startsWith(".dsh/")) RiskLevel.DSH_DATA else null
    }

    fun showRisk(entry: FileEntry, action: String) {
        pendingRiskEntry = entry
        riskAction = action
        showRiskDialog = true
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
            "write" -> showNewFolderDialog = true
            "import" -> showImportMenu = true
        }
        pendingRiskEntry = null
        riskAction = ""
        // P2: 必须关闭风险弹窗，否则确认后弹窗残留导致屏幕锁死、二级弹窗叠加
        showRiskDialog = false
    }

    fun cancelRisk() {
        pendingRiskEntry = null
        riskAction = ""
        showRiskDialog = false
    }

    /**
     * 导航到任意逻辑目录（含搜索结果跨根跳转）：按目标所在根自动切换视图，
     * 若需切换 rootMode 则携带 [pendingNavigateDir]，避免被根重置逻辑覆盖。
     */
    fun navigateToDir(logicalDir: File) {
        val targetRoot = if (logicalDir.absolutePath.startsWith(workspaceRoot.absolutePath)) {
            workspaceRoot
        } else {
            sandboxRoot
        }
        val wantWorkspace = targetRoot == workspaceRoot
        if ((wantWorkspace && rootMode == 1) || (!wantWorkspace && rootMode == 0)) {
            currentDir = logicalDir
        } else {
            pendingNavigateDir = logicalDir
            rootMode = if (wantWorkspace) 1 else 0
        }
        searchQuery = ""
        searchResults = emptyList()
        refreshEntries()
    }

    // ---------------- 文件预览 ----------------

    fun openFilePreview(entry: FileEntry) {
        val physical = mapper.resolvePhysical(File(entry.logicalPath))
        if (entry.isDirectory || !isTextFile(entry.name)) {
            showToastRes(R.string.files_preview_not_supported)
            return
        }
        // L1: 读文件移到 IO 线程，避免主线程卡顿
        previewName = entry.name
        previewText = context.getString(R.string.files_preview_loading)
        showPreviewDialog = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    physical.inputStream().buffered().use { ins ->
                        val len = minOf(64 * 1024, physical.length()).toInt().coerceAtLeast(1)
                        val bytes = ByteArray(len)
                        val read = ins.read(bytes)
                        String(bytes, 0, read.coerceAtLeast(0), Charsets.UTF_8)
                    }
                }.getOrElse { context.getString(R.string.files_preview_read_failed, it.message ?: "unknown") }
            }
            if (previewName == entry.name) previewText = text
        }
    }

    // ---------------- 新建 / 重命名 / 删除 ----------------

    fun createNewFolder() {
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
                // M3: 清空输入便于重输，弹窗保留
                newFolderName = ""
                return
            }
            target.mkdirs()
        }
        created.onFailure { showError(it.message ?: "创建失败") }
        newFolderName = ""
        showNewFolderDialog = false
        refreshEntries()
    }

    fun doRename() {
        val entry = renameTarget ?: return
        val safeNewName = sanitizeFileName(renameName)
        if (safeNewName == null) {
            showToastRes(R.string.files_name_invalid)
            renameTarget = null
            renameName = ""
            showRenameDialog = false
            return
        }
        val src = mapper.resolvePhysical(File(entry.logicalPath))
        val dest = File(src.parentFile, safeNewName)
        if (dest.exists()) {
            showToastRes(R.string.files_rename_conflict)
            renameTarget = null
            renameName = ""
            showRenameDialog = false
            return
        }
        val ok = runCatching { src.renameTo(dest) }.getOrDefault(false)
        if (!ok) {
            showError(context.getString(R.string.files_rename_failed, safeNewName))
        } else {
            showToast(context.getString(R.string.files_rename_done, entry.name, safeNewName))
        }
        renameTarget = null
        renameName = ""
        showRenameDialog = false
        exitSelection()
        refreshEntries()
    }

    fun doDelete() {
        val single = pendingDeleteTarget
        val targets = if (single != null) listOf(single) else entries.filter { it.logicalPath in selectedPaths }
        if (targets.isEmpty()) return
        cancelProgressJob()
        progressJob = scope.launch {
            val self = currentCoroutineContext()[Job]
            showProgress(context.getString(R.string.files_progress_deleting))
            try {
                withContext(Dispatchers.IO) {
                    targets.forEach { t ->
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
                showError(e.message ?: "删除失败")
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
            showProgress(context.getString(R.string.files_progress_importing, baseName), 0, -1)
            try {
                if (finalName == null) {
                    clearProgress()
                    showToastRes(R.string.files_conflict_skipped)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    FileOps.importFromUri(
                        context, uri, physicalTarget, finalName,
                        listener = ProgressListener { done, total, _ ->
                            setProgress(ProgressUi(true, context.getString(R.string.files_progress_importing, finalName), done, total))
                        },
                    )
                }
                clearProgress()
                refreshEntries()
                showToast(context.getString(R.string.files_import_done_name, finalName))
            } catch (e: CancellationException) {
                // S1: 取消时清理半成品文件
                FileOps.deleteQuietly(File(physicalTarget, finalName ?: baseName))
                clearProgress()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                FileOps.deleteQuietly(File(physicalTarget, finalName ?: baseName))
                clearProgress()
                showError(context.getString(R.string.files_import_failed, e.message ?: "未知错误"))
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
            showProgress(context.getString(R.string.files_progress_merging))
            val self = currentCoroutineContext()[Job]
            try {
                val physicalTarget = mapper.resolvePhysical(targetDir)
                withContext(Dispatchers.IO) {
                    // S4: 递归逐文件统一冲突策略，避免覆盖同名目录时静默丢弃其子文件
                    mergeTree(extractedDir, physicalTarget, mode)
                    extractedDir.deleteRecursively()
                }
                clearProgress()
                refreshEntries()
                showToastRes(R.string.files_extract_done)
            } catch (e: CancellationException) {
                FileOps.deleteQuietly(extractedDir)
                clearProgress()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                FileOps.deleteQuietly(extractedDir)
                clearProgress()
                showError(context.getString(R.string.files_merge_failed, e.message ?: "未知错误"))
            }
        }
    }

    /** 导入压缩包并解压：复制到缓存 → 检测格式 → 解压到临时目录 → 冲突确认 → 合并。 */
    fun startExtractImport(uri: Uri, targetDir: File) {
        cancelProgressJob()
        progressJob = scope.launch {
            val self = currentCoroutineContext()[Job]
            val physicalTarget = mapper.resolvePhysical(targetDir)
            var tmpArchive: File? = null
            var tmpExtract: File? = null
            showProgress(context.getString(R.string.files_progress_prepare))
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
                    return@launch
                }
                tmpExtract = File(context.cacheDir, "extract_${System.currentTimeMillis()}")
                withContext(Dispatchers.IO) {
                    ArchiveExtractor.extract(
                        tmpArchive!!, tmpExtract!!,
                        listener = ProgressListener { done, total, _ ->
                            setProgress(ProgressUi(true, context.getString(R.string.files_progress_extracting), done, total))
                        },
                    )
                }
                withContext(Dispatchers.IO) { tmpArchive!!.delete() }
                tmpArchive = null
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
                // S1/M6: 取消时清理缓存临时文件与目录
                FileOps.deleteQuietly(tmpArchive)
                FileOps.deleteQuietly(tmpExtract)
                clearProgress()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                FileOps.deleteQuietly(tmpArchive)
                FileOps.deleteQuietly(tmpExtract)
                clearProgress()
                showError(context.getString(R.string.files_extract_failed, e.message ?: "未知错误"))
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
            showProgress(context.getString(R.string.files_progress_exporting))
            try {
                val count = withContext(Dispatchers.IO) {
                    FileOps.exportToTree(
                        context, treeUri, physicalSelected,
                        listener = ProgressListener { done, total, _ ->
                            setProgress(ProgressUi(true, context.getString(R.string.files_progress_exporting), done, total))
                        },
                    )
                }
                clearProgress()
                exitSelection()
                refreshEntries()
                showToast(context.getString(R.string.files_export_done_count, count))
            } catch (e: CancellationException) {
                clearProgress()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                clearProgress()
                showError(context.getString(R.string.files_export_failed, e.message ?: "未知错误"))
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
            showProgress(context.getString(R.string.files_progress_zipping))
            try {
                val count = withContext(Dispatchers.IO) {
                    FileOps.exportToZip(
                        context, zipUri, physicalSelected,
                        listener = ProgressListener { done, total, _ ->
                            setProgress(ProgressUi(true, context.getString(R.string.files_progress_zipping), done, total))
                        },
                    )
                }
                clearProgress()
                exitSelection()
                refreshEntries()
                showToast(context.getString(R.string.files_zip_done_count, count))
            } catch (e: CancellationException) {
                clearProgress()
                if (progressJob == self) showToastRes(R.string.files_progress_cancelled)
            } catch (e: Exception) {
                clearProgress()
                showError(context.getString(R.string.files_export_failed, e.message ?: "未知错误"))
            }
        }
    }

    // ---------------- Launchers ----------------

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetDir = importTargetDir
        importTargetDir = null
        if (uri != null && targetDir != null) {
            if (importMode == "extract") {
                startExtractImport(uri, targetDir)
            } else {
                val displayName = queryDisplayName(context, uri) ?: "imported-file"
                val safeName = sanitizeFileName(displayName) ?: "imported-file"
                startFileImport(uri, targetDir, safeName)
            }
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

    // ---------------- Effects ----------------

    LaunchedEffect(rootMode) {
        val nav = pendingNavigateDir
        pendingNavigateDir = null
        currentDir = nav ?: root
        exitSelection()
        searchQuery = ""
        searchResults = emptyList()
        refreshEntries()
    }

    LaunchedEffect(isActiveTab) {
        if (isActiveTab) {
            withContext(Dispatchers.IO) { mapper.ensureRoots() }
            refreshEntries()
        }
    }

    // P1: 目录导航（列表/网格点击、面包屑、返回键、风险弹窗确认打开）只更新 currentDir，
    // 统一在此按 currentDir 变化刷新列表，修复"文件夹打不开"。
    LaunchedEffect(currentDir) {
        refreshEntries()
    }

    // 全局搜索（防抖）：搜索进度与文件操作进度（progress）完全分离（M2）
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
            else -> currentDir = currentDir.parentFile ?: root
        }
    }

    // ---------------- UI ----------------

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(PageBg)) {
            // ---------- 1. 顶部分段切换 + 全局图标操作 ----------
            if (selectionMode) {
                SelectionActionBar(
                    count = selectedPaths.size,
                    canRename = selectedPaths.size == 1,
                    canExport = selectedPaths.isNotEmpty(),
                    onRename = {
                        selectedPaths.firstOrNull()?.let { path ->
                            entries.firstOrNull { it.logicalPath == path }?.let { entry ->
                                renameTarget = entry
                                renameName = entry.name
                                if (isRiskEntry(entry)) showRisk(entry, "rename") else showRenameDialog = true
                            }
                        }
                    },
                    onDelete = {
                        pendingDeleteTarget = null
                        val risky = entries.firstOrNull { it.logicalPath in selectedPaths && isRiskEntry(it) }
                        if (risky != null) showRisk(risky, "delete") else showDeleteConfirm = true
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
                    SegmentedSwitch(
                        selected = rootMode,
                        options = listOf(
                            stringResource(R.string.files_root_sandbox),
                            stringResource(R.string.files_root_workspace),
                        ),
                        onSelect = { rootMode = it },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { refreshEntries() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.files_refresh),
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
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
                            imageVector = Icons.Outlined.Sort,
                            contentDescription = stringResource(R.string.files_sort),
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (viewMode == ViewMode.LIST) Icons.Outlined.GridView else Icons.Outlined.ViewList,
                            contentDescription = stringResource(
                                if (viewMode == ViewMode.LIST) R.string.files_view_grid else R.string.files_view_list,
                            ),
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            // ---------- 2. 面包屑 + 常驻全局搜索框 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (searchQuery.isEmpty()) {
                    Breadcrumb(
                        root = root,
                        rootLabel = rootLabel,
                        currentDir = currentDir,
                        onNavigate = { currentDir = it },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.files_search_results),
                        fontSize = 13.sp,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.width(8.dp))
                GlobalSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClear = { searchQuery = "" },
                    // L2: 相对宽度自适应，窄屏不与面包屑重叠
                    modifier = Modifier.fillMaxWidth(0.38f),
                )
            }

            // ---------- 3. 内容区 ----------
            if (searchQuery.isNotBlank()) {
                SearchResultsContent(
                    searching = searching,
                    results = searchResults,
                    onOpenResult = { result ->
                        // F3: 目录命中进入目录本身；文件命中进入其所在目录
                        val file = File(result.logicalPath)
                        val target = if (result.isDirectory) file else (file.parentFile ?: root)
                        navigateToDir(target)
                    },
                )
            } else if (entries.isEmpty()) {
                EmptyState(
                    // M1: 空态导入同样过风险检查，与悬浮胶囊行为一致
                    onImport = {
                        val risk = protectedRiskLevel(currentDir)
                        if (risk != null) {
                            showRisk(
                                FileEntry(currentDir.name, currentDir.absolutePath, true, 0, 0, risk),
                                "import",
                            )
                        } else {
                            showImportMenu = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (viewMode == ViewMode.LIST) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(entries, key = { it.logicalPath }) { entry ->
                        FileListRow(
                            entry = entry,
                            selected = entry.logicalPath in selectedPaths,
                            onClick = {
                                if (selectionMode) {
                                    toggleSelect(entry)
                                } else if (entry.isDirectory) {
                                    if (isRiskEntry(entry)) showRisk(entry, "open") else currentDir = File(entry.logicalPath)
                                } else {
                                    openFilePreview(entry)
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
                            onRename = {
                                renameTarget = entry
                                renameName = entry.name
                                if (isRiskEntry(entry)) showRisk(entry, "rename") else showRenameDialog = true
                            },
                            onDelete = {
                                pendingDeleteTarget = entry
                                if (isRiskEntry(entry)) showRisk(entry, "delete") else showDeleteConfirm = true
                            },
                            onExport = {
                                selectedPaths = setOf(entry.logicalPath)
                                selectionMode = true
                                showExportChoice = true
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
                            onClick = {
                                if (selectionMode) {
                                    toggleSelect(entry)
                                } else if (entry.isDirectory) {
                                    if (isRiskEntry(entry)) showRisk(entry, "open") else currentDir = File(entry.logicalPath)
                                } else {
                                    openFilePreview(entry)
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

        // ---------- 悬浮胶囊框（右上区域） ----------
        FloatingCapsule(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 56.dp, end = 16.dp),
            onNewFolder = {
                val risk = protectedRiskLevel(currentDir)
                if (risk != null) {
                    showRisk(
                        FileEntry(currentDir.name, currentDir.absolutePath, true, 0, 0, risk),
                        "write",
                    )
                } else {
                    showNewFolderDialog = true
                }
            },
            onImport = {
                val risk = protectedRiskLevel(currentDir)
                if (risk != null) {
                    showRisk(
                        FileEntry(currentDir.name, currentDir.absolutePath, true, 0, 0, risk),
                        "import",
                    )
                } else {
                    showImportMenu = true
                }
            },
            onExport = {
                // L3: 无选择时仅一次提示，不强行进入选择模式/开菜单
                if (selectedPaths.isEmpty()) {
                    showToastRes(R.string.files_select_hint)
                } else {
                    showExportMenu = true
                }
            },
        )
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

    if (showImportMenu) {
        MenuListDialog(
            title = stringResource(R.string.files_import),
            items = listOf(
                MenuItemSpec(R.string.files_import_file, Icons.Outlined.InsertDriveFile) {
                    showImportMenu = false
                    importMode = "file"
                    importTargetDir = currentDir
                    importLauncher.launch(arrayOf("*/*"))
                },
                MenuItemSpec(R.string.files_import_archive, Icons.Outlined.Archive) {
                    showImportMenu = false
                    importMode = "extract"
                    importTargetDir = currentDir
                    importLauncher.launch(arrayOf("*/*"))
                },
            ),
            onDismiss = { showImportMenu = false },
        )
    }

    if (showExportMenu) {
        MenuListDialog(
            title = stringResource(R.string.files_export),
            items = listOf(
                MenuItemSpec(R.string.files_export_files, Icons.Outlined.Download) {
                    showExportMenu = false
                    if (selectedPaths.isEmpty()) {
                        showToastRes(R.string.files_select_hint)
                    } else {
                        treeExportLauncher.launch(null)
                    }
                },
                MenuItemSpec(R.string.files_export_zip, Icons.Outlined.FolderZip) {
                    showExportMenu = false
                    if (selectedPaths.isEmpty()) {
                        showToastRes(R.string.files_select_hint)
                    } else {
                        zipExportLauncher.launch("${currentDir.name}_export_${System.currentTimeMillis()}.zip")
                    }
                },
            ),
            onDismiss = { showExportMenu = false },
        )
    }

    if (showExportChoice) {
        MenuListDialog(
            title = stringResource(R.string.files_export_choice_title),
            items = listOf(
                MenuItemSpec(R.string.files_export_files, Icons.Outlined.Download) {
                    showExportChoice = false
                    treeExportLauncher.launch(null)
                },
                MenuItemSpec(R.string.files_export_zip, Icons.Outlined.FolderZip) {
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
                    color = TextSecondary,
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
                    }
                }) {
                    Text(stringResource(R.string.files_conflict_skip))
                }
            },
        )
    }

    if (showRiskDialog) {
        val entry = pendingRiskEntry
        val isSystem = entry?.risk == RiskLevel.SYSTEM_DIR
        AlertDialog(
            onDismissRequest = { cancelRisk() },
            title = { Text(stringResource(R.string.files_risk_title)) },
            text = {
                Text(
                    text = entry?.let {
                        if (isSystem) stringResource(R.string.files_risk_system, it.name)
                        else stringResource(R.string.files_risk_dsh, it.name)
                    } ?: stringResource(R.string.files_risk_generic),
                    fontSize = 14.sp,
                    color = TextSecondary,
                )
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
                    else stringResource(R.string.files_delete_confirm_msg, selectedPaths.size),
                    fontSize = 14.sp,
                    color = TextSecondary,
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

    if (showPreviewDialog) {
        AlertDialog(
            onDismissRequest = { showPreviewDialog = false },
            title = {
                Text(
                    text = previewName,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Text(
                    text = previewText,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary,
                    maxLines = 18,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            confirmButton = {
                TextButton(onClick = { showPreviewDialog = false }) {
                    Text(stringResource(R.string.files_close))
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
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text(stringResource(R.string.files_close))
                }
            },
        )
    }

    if (progress.active) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.files_progress_title)) },
            text = {
                Column {
                    Text(
                        text = progress.stage,
                        fontSize = 14.sp,
                        color = TextSecondary,
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
                            color = TextHint,
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

/**
 * 递归合并解压结果到目标目录（S4）：
 * - 目标不存在：直接移动（renameTo 失败则 copy+delete）；
 * - 目标同名目录 + 源目录：按 [mode] 递归合并（不整体删除目标目录，避免静默丢弃其子文件）；
 * - 目标同名文件：OVERWRITE 覆盖 / RENAME 改名 / SKIP 跳过。
 * 支持协程取消检查。
 */
private suspend fun mergeTree(src: File, destParent: File, mode: ConflictMode) {
    currentCoroutineContext().ensureActive()
    src.listFiles()?.forEach { child ->
        currentCoroutineContext().ensureActive()
        val dest = File(destParent, child.name)
        if (!dest.exists()) {
            moveNode(child, dest)
        } else {
            when (mode) {
                ConflictMode.SKIP -> Unit
                ConflictMode.OVERWRITE -> {
                    if (child.isDirectory && dest.isDirectory) {
                        mergeTree(child, dest, mode)
                        child.deleteRecursively()
                    } else {
                        dest.deleteRecursively()
                        moveNode(child, dest)
                    }
                }
                ConflictMode.RENAME -> {
                    val newName = resolveConflictName(destParent, child.name, ConflictMode.RENAME)
                        ?: return@forEach
                    moveNode(child, File(destParent, newName))
                }
            }
        }
    }
}

/** 移动文件/目录到 [dest]（renameTo 失败时 copy+delete 兜底）。 */
private fun moveNode(src: File, dest: File) {
    if (src.isDirectory) {
        if (!src.renameTo(dest)) {
            dest.mkdirs()
            src.walkTopDown().forEach { f ->
                val rel = f.relativeTo(src).path
                if (f.isDirectory) {
                    File(dest, rel).mkdirs()
                } else {
                    File(dest, rel).parentFile?.mkdirs()
                    f.copyTo(File(dest, rel), overwrite = true)
                }
            }
            src.deleteRecursively()
        }
    } else {
        if (!src.renameTo(dest)) {
            src.copyTo(dest, overwrite = true)
            src.delete()
        }
    }
}

/** 判断文件是否为可预览的文本文件（供文件预览与全局搜索内容命中使用）。 */
private fun isTextFile(name: String): Boolean {
    val lower = name.lowercase()
    val dot = lower.lastIndexOf('.')
    val ext = if (dot > 0) lower.substring(dot + 1) else lower
    return ext in TEXT_FILE_EXTENSIONS || name == ".env" || name == "Dockerfile"
}

private val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "yaml", "yml", "xml", "html", "htm",
    "css", "js", "ts", "py", "sh", "bash", "log", "csv", "conf", "ini",
    "properties", "toml", "cfg", "env", "gitignore", "dockerfile", "gradle",
    "kts", "java", "kt", "c", "h", "cpp", "hpp", "go", "rs", "sql", "rb", "php",
)

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
                            color = TextPrimary,
                        )
                    }
                    if (index < items.lastIndex) {
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
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

// ---------- 顶部：胶囊分段切换 ----------

@Composable
private fun SegmentedSwitch(
    selected: Int,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ControlBg)
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) PrimaryGreen else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.White else TextSecondary,
                )
            }
        }
    }
}

// ---------- 选择操作栏 ----------

@Composable
private fun SelectionActionBar(
    count: Int,
    canRename: Boolean,
    canExport: Boolean,
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
            text = stringResource(R.string.files_selected_count, count),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        SelectionChip(Icons.Outlined.Download, stringResource(R.string.files_export_to_dir), enabled = canExport, onClick = onExportDir)
        SelectionChip(Icons.Outlined.FolderZip, stringResource(R.string.files_export_zip_short), enabled = canExport, onClick = onExportZip)
        SelectionChip(Icons.Outlined.InsertDriveFile, stringResource(R.string.files_rename), enabled = canRename, onClick = onRename)
        SelectionChip(Icons.Filled.Delete, stringResource(R.string.files_delete), enabled = count > 0, onClick = onDelete)
        SelectionChip(Icons.Filled.Close, stringResource(R.string.files_cancel), enabled = true, onClick = onCancel)
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
            tint = if (enabled) PrimaryGreen else TextHint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ---------- 面包屑 ----------

@Composable
private fun Breadcrumb(
    root: File,
    rootLabel: String,
    currentDir: File,
    onNavigate: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var cursor: File? = currentDir
        val crumbs = mutableListOf<Pair<String, File>>()
        while (cursor != null && cursor.absolutePath.startsWith(root.absolutePath)) {
            if (cursor == root) break
            crumbs.add(0, cursor.name to cursor)
            cursor = cursor.parentFile
        }
        val segments = buildList {
            add(rootLabel to root)
            addAll(crumbs)
        }
        segments.forEachIndexed { index, (label, target) ->
            if (index > 0) {
                Text(text = " / ", fontSize = 13.sp, color = TextHint)
            }
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (index == segments.lastIndex) TextSecondary else TextHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (index == segments.lastIndex) {
                    Modifier.weight(1f).clickable { onNavigate(target) }
                } else {
                    Modifier.clickable { onNavigate(target) }
                },
            )
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
            .border(1.dp, DividerColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.files_search_hint),
                    fontSize = 12.sp,
                    color = TextHint,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, color = TextPrimary),
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.files_clear_search),
                    tint = TextHint,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

// ---------- 悬浮胶囊框（右上角，高透明） ----------

@Composable
private fun FloatingCapsule(
    onNewFolder: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(26.dp), ambientColor = CardShadow, spotColor = CardShadow)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.82f))
            .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(26.dp))
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CapsuleIconButton(Icons.Outlined.CreateNewFolder, stringResource(R.string.files_new_folder), onNewFolder)
        CapsuleIconButton(Icons.Outlined.Download, stringResource(R.string.files_import), onImport)
        CapsuleIconButton(Icons.Outlined.Upload, stringResource(R.string.files_export), onExport)
    }
}

@Composable
private fun CapsuleIconButton(
    icon: ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(21.dp))
            .clickable(onClick = onClick)
            .then(
                Modifier.background(Color.Transparent),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = PrimaryGreen,
            modifier = Modifier.size(22.dp),
        )
    }
}

// ---------- 选择方框（P3） ----------

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
                color = if (selected) PrimaryGreen else TextHint,
                shape = RoundedCornerShape(5.dp),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(if (selected) SelectedRowBg else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 24.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // P3: 选择方框前置，点击直接切换选择
        SelectionCheckbox(
            selected = selected,
            onClick = onToggleSelect,
        )
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) PrimaryGreen else TextHint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entrySubtitle(entry),
                fontSize = 12.sp,
                color = TextHint,
                maxLines = 1,
            )
        }
        Box {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.files_more),
                tint = TextSecondary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { menuOpen = true }
                    .padding(4.dp),
            )
            androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_share)) },
                    onClick = {
                        menuOpen = false
                        onExport()
                    },
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        thickness = 0.5.dp,
        color = DividerColor,
    )
}

// ---------- 文件网格单元 ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    entry: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) SelectedRowBg else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // P3: 左上角选择方框，点击直接切换选择
        Box(modifier = Modifier.fillMaxWidth()) {
            SelectionCheckbox(
                selected = selected,
                onClick = onToggleSelect,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        Spacer(Modifier.height(4.dp))
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (entry.isDirectory) PrimaryGreen else TextHint,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = entry.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = entrySubtitle(entry),
            fontSize = 11.sp,
            color = TextHint,
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
                color = TextSecondary,
            )
        }
    } else if (results.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = Color(0xFFE5E7EB),
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.files_search_empty),
                fontSize = 14.sp,
                color = TextSecondary,
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
                imageVector = if (result.isDirectory) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = if (result.isDirectory) PrimaryGreen else TextHint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = result.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
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
            color = TextHint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 28.dp),
        )
        if (result.snippet != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = result.snippet,
                fontSize = 12.sp,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 28.dp),
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 24.dp), thickness = 0.5.dp, color = DividerColor)
}

// ---------- 空态 ----------

@Composable
private fun EmptyState(
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.FolderOpen,
            contentDescription = null,
            tint = Color(0xFFE5E7EB),
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.files_empty_title),
            fontSize = 14.sp,
            color = TextSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.files_empty_hint),
            fontSize = 12.sp,
            color = TextHint,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(
            shape = RoundedCornerShape(8.dp),
            onClick = onImport,
        ) {
            Text(
                text = stringResource(R.string.files_import),
                fontSize = 13.sp,
                color = PrimaryGreen,
            )
        }
    }
}
