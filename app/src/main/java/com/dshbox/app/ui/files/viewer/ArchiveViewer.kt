package com.dshbox.app.ui.files.viewer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import com.dshbox.app.util.formatFileSize
import com.dshbox.app.util.viewer.ArchiveBrowser
import com.dshbox.app.util.viewer.FileTypeClassifier
import com.dshbox.app.util.viewer.LargeTextLoader
import com.dshbox.app.util.viewer.TextEncoding
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 条目预览上限（抽到 cache 临时文件的字节预算；超出拒绝预览引导导出）。 */
private const val ARCHIVE_PREVIEW_MAX_BYTES = 10L * 1024 * 1024

/** cache 临时预览文件前缀（退出即删；残留于查看器打开时清理）。 */
private const val ARCHIVE_PREVIEW_PREFIX = "dshbox-archive-preview-"

/**
 * 压缩包只读浏览（1.2.0 §6.8，数据来自 [ArchiveBrowser] 纯 JVM 枚举）。
 *
 * - 树形列表：名称/大小/时间/层级（目录可折叠）；加密 ZIP 仅列条目名（🔒 标记，
 *   点击提示不支持内建预览）；
 * - 包内文本条目点击预览：流式抽到 cache 临时文件 → 文本嗅探 → 复用 TextCodeViewer
 *   只读态，关闭/退出即删临时文件；非文本条目提示导出查看；
 * - 导出：单条目 / 全部（重新打包为 ZIP）均走既有 SAF；
 * - 全程只读，不解压落盘工作区，无 Zip-Slip 面；打开失败经 onFallback 降级信息卡。
 */
@Composable
internal fun ArchiveViewer(
    file: File,
    onFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var format by remember(file) { mutableStateOf<ArchiveBrowser.Format?>(null) }
    var snapshot by remember(file) { mutableStateOf<ArchiveBrowser.Snapshot?>(null) }
    var collapsed by remember(file) { mutableStateOf(emptySet<String>()) }
    var previewFile by remember(file) { mutableStateOf<File?>(null) }
    var previewBytes by remember(file) { mutableStateOf<ByteArray?>(null) }
    var previewDetection by remember(file) { mutableStateOf<TextEncoding.Detection?>(null) }
    var pendingExportEntry by remember { mutableStateOf<ArchiveBrowser.Entry?>(null) }
    var toastText by remember { mutableStateOf<String?>(null) }

    fun toast(text: String) {
        toastText = text
        scope.launch {
            delay(2000)
            if (toastText == text) toastText = null
        }
    }

    fun deleteTemp(target: File?) {
        if (target != null) {
            scope.launch(NonCancellable + Dispatchers.IO) { target.delete() }
        }
    }

    // 分类 + 枚举全部在 IO（「不在组合期做磁盘 IO」约定）
    LaunchedEffect(file) {
        snapshot = null
        val fmt = withContext(Dispatchers.IO) {
            FileTypeClassifier.classify(file).let { ArchiveBrowser.formatOf(it.extension, it.subType) }
        }
        format = fmt
        if (fmt == null) {
            onFallback()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) { ArchiveBrowser.browse(file, fmt) }
        when (result) {
            is ArchiveBrowser.Result.Ok -> snapshot = result.snapshot
            is ArchiveBrowser.Result.Error -> onFallback()
        }
    }

    // 打开时清理历史预览残留（进程被杀场景；best-effort）
    LaunchedEffect(file) {
        withContext(Dispatchers.IO) {
            context.cacheDir.listFiles { f -> f.name.startsWith(ARCHIVE_PREVIEW_PREFIX) }
                ?.forEach { it.delete() }
        }
    }

    // 查看器退出：清理当前预览临时文件（§6.8「退出即删」）
    DisposableEffect(Unit) {
        onDispose {
            deleteTemp(previewFile)
            previewFile = null
        }
    }

    // ---- SAF 出口（§6.8：单条目 / 全部导出复用既有 SAF） ----
    val exportEntryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val entry = pendingExportEntry
        pendingExportEntry = null
        val snap = snapshot
        val fmt = format
        if (uri != null && entry != null && fmt != null && snap != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            ArchiveBrowser.openEntryStream(file, fmt, entry.path, snap.charsetLabel)?.use { ins ->
                                ins.copyTo(out, 64 * 1024)
                            } ?: error(context.getString(R.string.viewer_entry_stream_unavailable))
                        } ?: error(context.getString(R.string.viewer_out_stream_unavailable))
                    }.isSuccess
                }
                toast(context.getString(if (ok) R.string.files_export_done else R.string.files_export_failed_generic))
            }
        }
    }
    val exportAllLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val snap = snapshot
        val fmt = format
        if (uri != null && fmt != null && snap != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()[Job]
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            ZipOutputStream(out.buffered()).use { zip ->
                                ArchiveBrowser.exportAllToZip(file, fmt, zip, { job?.ensureActive() }, snap.charsetLabel)
                            }
                        } ?: error(context.getString(R.string.viewer_out_stream_unavailable))
                    }.isSuccess
                }
                toast(context.getString(if (ok) R.string.files_export_done else R.string.files_export_failed_generic))
            }
        }
    }

    // ---- 条目点击（IO）：加密拒绝 → 抽临时文件 → 文本嗅探 → 预览 ----
    fun openEntry(entry: ArchiveBrowser.Entry) {
        val fmt = format ?: return
        if (entry.isEncrypted) {
            toast(context.getString(R.string.files_archive_encrypted_entry))
            return
        }
        scope.launch {
            // 换预览前先删上一份临时文件
            deleteTemp(previewFile)
            previewFile = null
            previewBytes = null
            previewDetection = null
            val tmp = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(
                        context.cacheDir,
                        "$ARCHIVE_PREVIEW_PREFIX${System.currentTimeMillis()}-${entry.name.ifEmpty { "entry" }}",
                    )
                    ArchiveBrowser.openEntryStream(file, fmt, entry.path, snapshot?.charsetLabel ?: "UTF-8")?.use { ins ->
                        target.outputStream().buffered().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > ARCHIVE_PREVIEW_MAX_BYTES) error(context.getString(R.string.viewer_entry_too_large))
                                out.write(buf, 0, n)
                            }
                        }
                    } ?: error(context.getString(R.string.viewer_entry_unreadable))
                    target
                }.getOrNull()
            }
            if (tmp == null) {
                toast(context.getString(R.string.files_archive_preview_failed))
                return@launch
            }
            // 文本嗅探：非文本条目不进文本预览（删临时文件，引导导出查看）
            val isText = withContext(Dispatchers.IO) {
                val head = FileTypeClassifier.readHead(tmp, FileTypeClassifier.SNIFF_LIMIT)
                FileTypeClassifier.isTextCandidate(entry.name, head)
            }
            if (!isText) {
                withContext(NonCancellable + Dispatchers.IO) { tmp.delete() }
                toast(context.getString(R.string.files_archive_binary_entry))
                return@launch
            }
            val det = withContext(Dispatchers.IO) {
                val head = FileTypeClassifier.readHead(tmp, FileTypeClassifier.SNIFF_LIMIT)
                TextEncoding.detect(head.copyOf(minOf(head.size, 8192)))
            }
            val bytes = withContext(Dispatchers.IO) { tmp.readBytes() }
            previewFile = tmp
            previewBytes = bytes
            previewDetection = det
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        snapshot?.let { snap ->
            Column(modifier = Modifier.fillMaxSize()) {
                // 头部：格式 · 条目数 · 加密提示 · 全部导出
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.files_archive_entries,
                            snap.entries.size,
                            ArchiveBrowser.formatLabel(snap.format),
                            snap.entries.size,
                        ) + if (snap.truncated) stringResource(R.string.files_archive_truncated) else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (snap.hasEncrypted) {
                        Text(
                            text = stringResource(R.string.files_archive_encrypted_hint),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    TextButton(onClick = {
                        exportAllLauncher.launch(file.nameWithoutExtension.ifEmpty { "archive" } + "-export.zip")
                    }) {
                        Text(stringResource(R.string.files_archive_export_all), fontSize = 13.sp)
                    }
                }
                HorizontalDivider()
                if (snap.entries.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.files_archive_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val visible = visibleEntries(snap.entries, collapsed)
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // 不用 path 作 key：病态包内同名路径会撞 key 崩溃
                        items(visible) { entry ->
                            ArchiveEntryRow(
                                entry = entry,
                                collapsed = entry.isDirectory && entry.path in collapsed,
                                onClick = {
                                    if (entry.isDirectory) {
                                        collapsed = if (entry.path in collapsed) {
                                            collapsed - entry.path
                                        } else {
                                            collapsed + entry.path
                                        }
                                    } else {
                                        openEntry(entry)
                                    }
                                },
                                onExport = {
                                    pendingExportEntry = entry
                                    exportEntryLauncher.launch(entry.name.ifEmpty { "entry" })
                                },
                            )
                        }
                    }
                }
            }
        }

        // 条目预览覆盖层（复用 TextCodeViewer 只读态；关闭即删临时文件）
        val pFile = previewFile
        val pBytes = previewBytes
        val pDet = previewDetection
        if (pFile != null && pBytes != null && pDet != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        deleteTemp(pFile)
                        previewFile = null
                        previewBytes = null
                        previewDetection = null
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.files_close),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = pFile.name.substringAfter(ARCHIVE_PREVIEW_PREFIX).substringAfter('-'),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider()
                val entryController = remember { TextEditController() }
                TextCodeViewer(
                    file = pFile,
                    fullBytes = pBytes,
                    detection = pDet,
                    fingerprint = null,
                    plan = LargeTextLoader.planFor(pBytes.size.toLong()),
                    highlightLanguage = FileTypeClassifier.highlightLanguageOf(pFile.name.substringAfterLast('.')),
                    editing = false,
                    controller = entryController,
                    onRequestRefresh = {},
                    onToast = { toast(it) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        toastText?.let { text ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = text,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ArchiveEntryRow(
    entry: ArchiveBrowser.Entry,
    collapsed: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (16 + entry.depth * 18).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = when {
                    entry.isDirectory -> Icons.Filled.Folder
                    entry.isEncrypted -> Icons.Outlined.Lock
                    else -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = if (entry.isDirectory) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.name,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.isDirectory) {
                Text(
                    text = if (collapsed) "+" else "−",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = if (entry.size >= 0) formatFileSize(entry.size) else "—",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.size >= 0 && !entry.isEncrypted) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.files_archive_entry_export),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onExport),
                    )
                }
            }
        }
        if (!entry.isDirectory && entry.lastModified > 0) {
            Text(
                text = archiveTimeFmt.format(Date(entry.lastModified).toInstant()),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp, top = 1.dp),
            )
        }
    }
}

/** 可见条目：折叠目录的后代隐藏（保持归档顺序）。 */
private fun visibleEntries(
    entries: List<ArchiveBrowser.Entry>,
    collapsed: Set<String>,
): List<ArchiveBrowser.Entry> {
    if (collapsed.isEmpty()) return entries
    return entries.filter { entry ->
        var parent = entry.parentPath
        var visible = true
        while (parent.isNotEmpty()) {
            if (parent in collapsed) {
                visible = false
                break
            }
            parent = parent.substringBeforeLast('/', "")
        }
        visible
    }
}

// 固定 Locale.US——文件时间戳保持 ISO 风格西文数字，
    // 且不缓存系统 Locale（应用内切语言后仍按西文数字渲染）。
    private val archiveTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)
        .withZone(ZoneId.systemDefault())
