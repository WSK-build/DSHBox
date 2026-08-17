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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.text.BasicTextField
import com.dshbox.app.R
import com.dshbox.app.util.formatFileSize
import com.dshbox.app.util.queryDisplayName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
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
private val CardShadow = Color(0x0A000000) // rgba(0,0,0,0.04)

private enum class SortMode { NAME, TIME, SIZE }

private enum class ViewMode { LIST, GRID }

private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

@Composable
fun FilesScreen(modifier: Modifier = Modifier, isActiveTab: Boolean = true) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sandboxRoot = remember { File(context.filesDir, "runtime/runtime-current/debian") }
    val workspaceRoot = remember { File(context.filesDir, "user-data") }
    var rootMode by remember { mutableIntStateOf(0) }
    val root = if (rootMode == 0) sandboxRoot else workspaceRoot
    // Breadcrumb root label carries the guest-view location in workspace mode;
    // the segmented control keeps the plain resource strings (see options below).
    val rootLabel = if (rootMode == 0) {
        stringResource(R.string.files_root_sandbox)
    } else {
        stringResource(R.string.files_root_workspace) + "  /root/projects"
    }
    var currentDir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<File>>(emptySet()) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<File?>(null) }
    var renameName by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.NAME) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var searchQuery by remember { mutableStateOf("") }
    var rawEntryCount by remember { mutableIntStateOf(0) }
    var rawHasFiles by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteTarget by remember { mutableStateOf<File?>(null) }
    var showImportConfirm by remember { mutableStateOf(false) }
    var showExportPicker by remember { mutableStateOf(false) }
    var zipTargetDir by remember { mutableStateOf<File?>(null) }

    fun refreshEntries() {
        scope.launch {
            val query = searchQuery.trim()
            // Directory listing + sort can be slow on huge rootfs dirs; keep it
            // off the main thread (state updates hop back via the snapshot).
            val all = withContext(Dispatchers.IO) {
                currentDir.listFiles()?.toList() ?: emptyList()
            }
            rawEntryCount = all.size
            rawHasFiles = all.any { it.isFile }
            entries = all
                .filter { file ->
                    // DSH's internal data dir stays hidden inside the workspace view.
                    if (rootMode == 1 && file.name == ".dsh") return@filter false
                    query.isEmpty() || file.name.contains(query, ignoreCase = true)
                }
                .sortedWith(
                    when (sortMode) {
                        SortMode.NAME -> compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
                        SortMode.TIME -> compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() }
                        SortMode.SIZE -> compareBy<File> { !it.isDirectory }
                            .thenByDescending { if (it.isFile) it.length() else 0L }
                    },
                )
        }
    }

    fun toggleSelect(file: File) {
        selectedFiles = if (file in selectedFiles) selectedFiles - file else selectedFiles + file
        if (selectedFiles.isEmpty()) selectionMode = false
    }

    fun exitSelection() {
        selectionMode = false
        selectedFiles = emptySet()
    }

    // Only the visible tab owns the back key; selection mode exits first so
    // the back key never silently navigates a hidden Files directory.
    BackHandler(enabled = isActiveTab && (selectionMode || currentDir != root)) {
        if (selectionMode) exitSelection() else currentDir = currentDir.parentFile ?: root
    }

    var importTargetDir by remember { mutableStateOf<File?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetDir = importTargetDir
        importTargetDir = null
        if (uri != null && targetDir != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { copyUriToSandbox(context, uri, targetDir) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            refreshEntries()
                            Toast.makeText(
                                context,
                                context.getString(R.string.files_import_done_to, targetDir.name),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.files_operation_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val file = pendingExportFile
        if (uri != null && file != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { copyFileToUri(context, file, uri) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.files_export_done_name, file.name),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.files_operation_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
        pendingExportFile = null
    }

    val zipExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { zipDirectoryToUri(context, zipTargetDir ?: currentDir, uri) }
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.files_export_done_name, currentDir.name),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    .onFailure {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, R.string.files_operation_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
            }
        }
    }

    LaunchedEffect(rootMode) {
        currentDir = root
        exitSelection()
        searchQuery = ""
        sortMode = SortMode.NAME
        refreshEntries()
    }

    LaunchedEffect(currentDir, sortMode, searchQuery) {
        exitSelection()
        refreshEntries()
    }

    Column(modifier = modifier.fillMaxSize().background(PageBg)) {
        val dirProtected = isProtectedDir(currentDir, root)
        // ---------- 1. Top: segmented switch + global icon actions ----------
        if (selectionMode) {
            SelectionActionBar(
                count = selectedFiles.size,
                canRename = selectedFiles.size == 1 && !isProtected(selectedFiles.first(), root),
                onRename = {
                    selectedFiles.firstOrNull()?.let { file ->
                        renameTarget = file
                        renameName = file.name
                        showRenameDialog = true
                    }
                },
                onDelete = { showDeleteConfirm = true },
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
                IconButton(
                    onClick = { refreshEntries() },
                    modifier = Modifier.size(36.dp),
                ) {
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
                        Toast.makeText(
                            context,
                            when (sortMode) {
                                SortMode.NAME -> R.string.files_sort_name
                                SortMode.TIME -> R.string.files_sort_time
                                SortMode.SIZE -> R.string.files_sort_size
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
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
                    onClick = {
                        viewMode = if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
                    },
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

        // ---------- 2. Path breadcrumb + mini search ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Breadcrumb(
                root = root,
                rootLabel = rootLabel,
                currentDir = currentDir,
                onNavigate = { currentDir = it },
                modifier = Modifier.weight(1f),
            )
            if (rawEntryCount >= 15 || searchQuery.isNotEmpty()) {
                MiniSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClear = { searchQuery = "" },
                    modifier = Modifier.width(180.dp),
                )
            }
        }

        // ---------- 3. Action card group ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionCard(
                icon = Icons.Outlined.Download,
                label = stringResource(R.string.files_import),
                modifier = Modifier.weight(1f),
                enabled = !dirProtected,
                onClick = { showImportConfirm = true },
            )
            ActionCard(
                icon = Icons.Outlined.CreateNewFolder,
                label = stringResource(R.string.files_new_folder),
                modifier = Modifier.weight(1f),
                enabled = !dirProtected,
                onClick = { showNewFolderDialog = true },
            )
            ActionCard(
                icon = Icons.Outlined.Upload,
                label = stringResource(R.string.files_export),
                modifier = Modifier.weight(1f),
                enabled = rawHasFiles,
                onClick = { showExportPicker = true },
            )
            ActionCard(
                icon = Icons.Outlined.FolderZip,
                label = stringResource(R.string.files_export_dir),
                modifier = Modifier.weight(1f),
                enabled = !dirProtected,
                onClick = {
                    zipTargetDir = currentDir
                    zipExportLauncher.launch("${currentDir.name}.zip")
                },
            )
        }

        // ---------- 4. File list / grid / empty ----------
        if (entries.isEmpty() && searchQuery.isNotBlank()) {
            NoMatchState(modifier = Modifier.fillMaxSize())
        } else if (entries.isEmpty()) {
            EmptyState(
                onImport = { showImportConfirm = true },
                importEnabled = !dirProtected,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (viewMode == ViewMode.LIST) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(entries, key = { it.absolutePath }) { file ->
                    FileListRow(
                        file = file,
                        selected = file in selectedFiles,
                        readOnly = isProtected(file, root),
                        onClick = {
                            if (selectionMode) {
                                toggleSelect(file)
                            } else if (file.isDirectory) {
                                currentDir = file
                            } else {
                                pendingExportFile = file
                                exportLauncher.launch(file.name)
                            }
                        },
                        onLongClick = {
                            selectionMode = true
                            toggleSelect(file)
                        },
                        onRename = {
                            if (!isProtected(file, root)) {
                                renameTarget = file
                                renameName = file.name
                                showRenameDialog = true
                            }
                        },
                        onDelete = {
                            if (!isProtected(file, root)) {
                                pendingDeleteTarget = file
                                showDeleteConfirm = true
                            }
                        },
                        onExport = {
                            if (file.isFile) {
                                pendingExportFile = file
                                exportLauncher.launch(file.name)
                            }
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
                items(entries, key = { it.absolutePath }) { file ->
                    FileGridCell(
                        file = file,
                        selected = file in selectedFiles,
                        readOnly = isProtected(file, root),
                        onClick = {
                            if (selectionMode) {
                                toggleSelect(file)
                            } else if (file.isDirectory) {
                                currentDir = file
                            } else {
                                pendingExportFile = file
                                exportLauncher.launch(file.name)
                            }
                        },
                        onLongClick = {
                            selectionMode = true
                            toggleSelect(file)
                        },
                    )
                }
            }
        }
    }

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
                    onClick = {
                        val safeName = sanitizeFileName(newFolderName)
                        if (safeName != null) {
                            File(currentDir, safeName).mkdirs()
                            refreshEntries()
                        }
                        newFolderName = ""
                        showNewFolderDialog = false
                    },
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

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(stringResource(R.string.files_import_confirm_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.files_import_confirm_msg),
                        fontSize = 14.sp,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = guestDisplayPath(currentDir, root, rootMode == 1),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.files_import_confirm_hint),
                        fontSize = 12.sp,
                        color = TextHint,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showImportConfirm = false
                        importTargetDir = currentDir
                        importLauncher.launch(arrayOf("*/*"))
                    },
                ) {
                    Text(stringResource(R.string.files_import_confirm_action), color = PrimaryGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    if (showExportPicker) {
        val exportableFiles = remember(currentDir) {
            currentDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() } ?: emptyList()
        }
        AlertDialog(
            onDismissRequest = { showExportPicker = false },
            title = { Text(stringResource(R.string.files_export_picker_title)) },
            text = {
                if (exportableFiles.isEmpty()) {
                    Text(
                        text = stringResource(R.string.files_export_picker_empty),
                        fontSize = 14.sp,
                        color = TextHint,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(exportableFiles, key = { it.absolutePath }) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        showExportPicker = false
                                        pendingExportFile = file
                                        exportLauncher.launch(file.name)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.InsertDriveFile,
                                    contentDescription = null,
                                    tint = TextHint,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        fontSize = 14.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = formatFileSize(file.length()),
                                        fontSize = 12.sp,
                                        color = TextHint,
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = null,
                                    tint = TextHint,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showExportPicker = false }) {
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
                if (single != null) {
                    Text(stringResource(R.string.files_delete_confirm_single, single.name))
                } else {
                    Text(stringResource(R.string.files_delete_confirm_msg, selectedFiles.size))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targets = if (single != null) {
                            pendingDeleteTarget = null
                            listOf(single)
                        } else {
                            selectedFiles.toList()
                        }
                        showDeleteConfirm = false
                        exitSelection()
                        // Large trees delete on IO; the list refresh follows.
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                targets.filterNot { isProtected(it, root) }
                                    .forEach { it.deleteRecursively() }
                            }
                            refreshEntries()
                        }
                    },
                ) {
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
        val target = renameTarget
        if (target != null) {
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
                        onClick = {
                            val safeNewName = sanitizeFileName(renameName)
                            if (safeNewName != null && !isProtected(target, root)) {
                                val destination = File(target.parentFile, safeNewName)
                                val conflict = destination.exists() && destination != target
                                if (conflict) {
                                    Toast.makeText(
                                        context,
                                        R.string.files_rename_conflict,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else if (!target.renameTo(destination)) {
                                    Toast.makeText(
                                        context,
                                        R.string.files_operation_failed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    renameTarget = null
                                    renameName = ""
                                    showRenameDialog = false
                                    refreshEntries()
                                    exitSelection()
                                }
                            } else {
                                if (safeNewName == null) {
                                    Toast.makeText(
                                        context,
                                        R.string.files_name_invalid,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                renameTarget = null
                                renameName = ""
                                showRenameDialog = false
                                refreshEntries()
                                exitSelection()
                            }
                        },
                        enabled = renameName.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.files_rename_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        shape = MaterialTheme.shapes.medium,
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
    }
}

// ---------- Top: capsule segmented switch ----------

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

// ---------- Selection action bar (replaces the top row in selection mode) ----------

@Composable
private fun SelectionActionBar(
    count: Int,
    canRename: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.files_selected_count, count),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRename, enabled = canRename) {
            Text(stringResource(R.string.files_rename))
        }
        TextButton(onClick = onDelete) {
            Text(stringResource(R.string.files_delete))
        }
        TextButton(onClick = onCancel) {
            Text(stringResource(R.string.files_cancel))
        }
    }
}

// ---------- Breadcrumb path ----------

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
                Text(
                    text = " / ",
                    fontSize = 13.sp,
                    color = TextHint,
                )
            }
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (index == segments.lastIndex) TextSecondary else TextHint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Only the trailing crumb takes the flexible width; weight(0f)
                // is illegal in Compose, so leading crumbs use natural width.
                modifier = if (index == segments.lastIndex) {
                    Modifier.weight(1f).clickable { onNavigate(target) }
                } else {
                    Modifier.clickable { onNavigate(target) }
                },
            )
        }
    }
}

// ---------- Mini search field ----------

@Composable
private fun MiniSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(32.dp)
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
                    fontSize = 13.sp,
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

// ---------- Action cards ----------

@Composable
private fun ActionCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = when {
        !enabled -> CardBg
        primary -> PrimaryGreen
        else -> LightGreenCard
    }
    Column(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(12.dp), ambientColor = CardShadow, spotColor = CardShadow)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (primary) Color.White else if (enabled) PrimaryGreen else TextHint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = if (primary) Color.White else TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---------- File list row ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListRow(
    file: File,
    selected: Boolean,
    readOnly: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
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
        Icon(
            imageVector = if (file.isDirectory) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) PrimaryGreen else TextHint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = fileSubtitle(file),
                fontSize = 12.sp,
                color = TextHint,
                maxLines = 1,
            )
        }
        if (readOnly) {
            Text(
                text = stringResource(R.string.files_read_only),
                fontSize = 11.sp,
                color = TextHint,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Box {
            // Plain clickable Icon instead of IconButton: keeps the touch
            // target small inside the 56dp row and renders reliably.
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
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                    enabled = !readOnly,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    enabled = !readOnly,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.files_share)) },
                    onClick = {
                        menuOpen = false
                        onExport()
                    },
                    enabled = file.isFile,
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

// ---------- File grid cell ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridCell(
    file: File,
    selected: Boolean,
    readOnly: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) SelectedRowBg else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (file.isDirectory) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) PrimaryGreen else TextHint,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = file.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = fileSubtitle(file),
            fontSize = 11.sp,
            color = TextHint,
            maxLines = 1,
        )
        if (readOnly) {
            Text(
                text = stringResource(R.string.files_read_only),
                fontSize = 10.sp,
                color = TextHint,
            )
        }
    }
}

// ---------- No search matches ----------

@Composable
private fun NoMatchState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
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
            text = stringResource(R.string.files_no_match),
            fontSize = 14.sp,
            color = TextSecondary,
        )
    }
}

// ---------- Empty state ----------

@Composable
private fun EmptyState(
    onImport: () -> Unit,
    importEnabled: Boolean = true,
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
            enabled = importEnabled,
        ) {
            Text(
                text = stringResource(R.string.files_import),
                fontSize = 13.sp,
                color = PrimaryGreen,
            )
        }
    }
}

// ---------- Helpers ----------

@Composable
private fun fileSubtitle(file: File): String = if (file.isDirectory) {
    androidx.compose.ui.res.stringResource(R.string.files_directory)
} else {
    "${formatFileSize(file.length())} · ${timeFmt.format(Date(file.lastModified()))}"
}

/**
 * Guest-view path for display: hides the Android app-data storage architecture.
 * Sandbox mode shows the path inside the rootfs ("/"), workspace mode shows
 * the proot-bound workspace root ("/root/projects").
 */
private fun guestDisplayPath(file: File, root: File, isWorkspace: Boolean): String {
    val rel = file.absolutePath.removePrefix(root.absolutePath).trimStart('/')
    val prefix = if (isWorkspace) "/root/projects" else ""
    return when {
        rel.isEmpty() -> prefix.ifEmpty { "/" }
        else -> "$prefix/$rel"
    }
}

/**
 * True when [file] may not be renamed/deleted: the root itself, system bind
 * directories (sandbox view) and DSH's internal data dir. Component prefixes
 * are matched on whole path segments so "tmp" cannot shadow "tmp2".
 */
private fun isProtected(file: File, sandboxRoot: File): Boolean {
    val rootPath = sandboxRoot.absolutePath
    if (file.absolutePath == rootPath) return true
    val rel = file.absolutePath.removePrefix(rootPath).trimStart('/')
    if (rel.isEmpty()) return true
    val first = rel.substringBefore('/')
    val systemDirs = if (sandboxRoot.name == "user-data") emptyList() else
        listOf("proc", "sys", "dev", "system", "apex", "tmp")
    return first in systemDirs || first == ".dsh"
}

/**
 * True when [dir] (as the CURRENT directory) must not accept writes. Unlike
 * [isProtected] the root itself is writable - only system bind dirs and DSH's
 * internal data dir are blocked, so imports/new folders/ZIP are disabled there.
 */
private fun isProtectedDir(dir: File, sandboxRoot: File): Boolean {
    val rootPath = sandboxRoot.absolutePath
    if (dir.absolutePath == rootPath) return false
    val rel = dir.absolutePath.removePrefix(rootPath).trimStart('/')
    if (rel.isEmpty()) return false
    val first = rel.substringBefore('/')
    val systemDirs = if (sandboxRoot.name == "user-data") emptyList() else
        listOf("proc", "sys", "dev", "system", "apex", "tmp")
    return first in systemDirs || first == ".dsh"
}

private fun sanitizeFileName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.contains('/') || trimmed.contains('\\')) return null
    // A ".dsh" entry would be hidden and write-protected in the workspace view.
    if (trimmed == ".dsh" || trimmed.startsWith(".dsh")) return null
    return trimmed
}

private fun copyUriToSandbox(context: Context, uri: Uri, targetDir: File) {
    val displayName = queryDisplayName(context, uri) ?: "imported-file"
    val safeName = sanitizeFileName(displayName) ?: "imported-file"
    // Never silently overwrite an existing file: append -1/-2/... like
    // typical file managers, mirroring the rename conflict guard.
    var target = File(targetDir, safeName)
    if (target.exists()) {
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var counter = 1
        do {
            target = File(targetDir, "${base}-${counter}$ext")
            counter++
        } while (target.exists())
    }
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    }
}

private fun copyFileToUri(context: Context, file: File, uri: Uri) {
    context.contentResolver.openOutputStream(uri)?.use { output ->
        file.inputStream().use { input -> input.copyTo(output) }
    }
}

private fun zipDirectoryToUri(context: Context, dir: File, uri: Uri) {
    context.contentResolver.openOutputStream(uri)?.use { output ->
        ZipOutputStream(output).use { zip ->
            dir.walkTopDown()
                .filter { it.isFile && ".dsh" !in it.path.split('/') }
                .forEach { file ->
                val relative = file.relativeTo(dir).path
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
