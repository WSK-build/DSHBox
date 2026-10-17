package com.dshbox.app.ui.files

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Close
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Folder
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.CreateNewFolder
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.util.FileEntry
import com.dshbox.app.util.Layer
import com.dshbox.app.util.LayerRoots
import com.dshbox.app.util.MovePlanner
import com.dshbox.app.util.PathMapper
import com.dshbox.app.util.layerOf
import com.dshbox.app.util.sanitizeFileName
import com.dshbox.app.util.scanDirectory
import com.dshbox.app.util.shareCrumbsOf
import com.dshbox.app.util.shareEntriesOf
import com.dshbox.app.util.shareNamespaceParentOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * 移动目标选择器（覆盖式全屏二级页）。同一页面兼作外部分享导入的落点选择器，
 * 两处只有标题与确认文案不同（由 [titleRes] / [confirmPluralRes] 传入）。
 *
 * - 顶栏：根视图控件（[RootPicker] + 下拉面板，与文件页左上角同一套）+ 面包屑（[Breadcrumb]）；
 * - 主体：仅显示文件夹（文件隐藏），点击进入；
 * - 源自身/源子孙目录：置灰、不可进入（防环可视化）；系统绑定目录同样置灰（禁止作为目标）；
 * - 底部主按钮「移动到当前文件夹（N 项）」/「导入到当前文件夹（N 项）」+ 次按钮「新建文件夹」（名称消毒 + 风险检查）；
 * - 目标落点经 PathMapper 重定向时，确认按钮下方小字提示真实落点。
 */
@Composable
internal fun FolderPickerScreen(
    mapper: PathMapper,
    sandboxRoot: File,
    workspaceRoot: File,
    /** 共享区命名空间根（虚拟目录；自身不是落点，只有其条目可作目标）。 */
    shareNamespace: File,
    /** 共享区条目（内建 DSHBox + 用户挂载；与文件页 / guest 绑定同口径）。 */
    shareEntries: List<Pair<String, File>>,
    /** 共享区是否就绪（未授权 / 未创建时不列出该根视图）。 */
    shareAvailable: Boolean,
    layerRoots: LayerRoots,
    /** 待处理条目数（移动与外部导入共用，只用于计数的复数文案）。 */
    moveCount: Int,
    /** 源中为目录的物理路径（防环置灰用）。 */
    sourceDirs: List<File>,
    /** 沙盒运行中（NODE/DSH 层内新建文件夹的风险文案追加停机建议）。 */
    sandboxRunning: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (targetLogical: File) -> Unit,
    /** 宿主页签是否活跃：二级页随页签常驻组合，失活时交还返回键给当前活跃页/系统。 */
    backEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** 标题文案：默认移动语义，外部分享导入传导入语义。 */
    titleRes: Int = R.string.files_picker_title,
    /** 确认按钮文案：默认移动语义，外部分享导入传导入语义。 */
    confirmPluralRes: Int = R.plurals.files_picker_confirm,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var rootMode by remember { mutableIntStateOf(0) }
    /** 根视图下拉面板显隐（与文件页左上角控件同一套交互）。 */
    var showRootMenu by remember { mutableStateOf(false) }
    val root = when (rootMode) {
        1 -> workspaceRoot
        2 -> shareNamespace
        else -> sandboxRoot
    }
    val rootLabel = when (rootMode) {
        1 -> stringResource(R.string.files_root_workspace) + "  /root/projects"
        2 -> stringResource(R.string.files_root_share) + "  " + Constants.SHARE_AREA_GUEST_DIR
        else -> stringResource(R.string.files_root_sandbox)
    }
    val rootName = when (rootMode) {
        1 -> stringResource(R.string.files_root_workspace)
        2 -> stringResource(R.string.files_root_share)
        else -> stringResource(R.string.files_root_sandbox)
    }
    var currentDir by remember { mutableStateOf(root) }
    /** 命名空间层不是真实落点：既不能作为目标，也不允许在此新建文件夹。 */
    val atNamespaceRoot = rootMode == 2 && currentDir.absolutePath == shareNamespace.absolutePath
    var dirs by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    // 新建文件夹前的风险强确认（风险目录内创建：名称消毒 + 风险检查）。
    // 复查修正：保存 Layer 而非 RiskLevel，文案与层判定同源（NODE/DSH 显示运行环境层语义）
    var pendingRiskForNewFolder by remember { mutableStateOf<Layer?>(null) }

    // 切换视图时目录重置到对应根
    LaunchedEffect(rootMode) {
        currentDir = when (rootMode) {
            1 -> workspaceRoot
            2 -> shareNamespace
            else -> sandboxRoot
        }
    }

    // 共享区在进入后失效（如授权被撤销）：退回沙盒视图，避免停留在空的虚拟根。
    LaunchedEffect(shareAvailable) {
        if (!shareAvailable && rootMode == 2) rootMode = 0
    }

    fun refresh() {
        val dir = currentDir
        val isTop = rootMode == 0 && dir.absolutePath == sandboxRoot.absolutePath
        scope.launch {
            val all = withContext(Dispatchers.IO) {
                // 命名空间层是虚拟目录：只按路径判定（不依赖 rootMode 的瞬时一致性），列出条目集合。
                if (dir.absolutePath == shareNamespace.absolutePath) {
                    shareEntriesOf(shareEntries)
                } else {
                    scanDirectory(dir, mapper, isTop).entries
                }
            }
            if (dir.absolutePath != currentDir.absolutePath) return@launch
            // 本页只列目录；目录不受条数上限影响（见 scanDirectory），故无需提示截断。
            dirs = all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        }
    }

    // 复查修正：仅以 currentDir 为 key——rootMode 切换会先改 currentDir，
    // 叠加 rootMode key 会导致每次切视图先用旧目录多做一次被丢弃的全量扫描
    LaunchedEffect(currentDir) { refresh() }

    BackHandler(enabled = backEnabled) {
        if (currentDir.absolutePath != root.absolutePath) {
            // 共享区是虚拟命名空间：落点根回到命名空间，不能直接 parentFile 上溯。
            currentDir = if (rootMode == 2) {
                shareNamespaceParentOf(currentDir, shareNamespace, shareEntries)
            } else {
                currentDir.parentFile ?: root
            }
        } else {
            onDismiss()
        }
    }

    /** 目标目录是否可选中：不能是源目录本身/位于源目录内部，也不能是系统绑定目录。
     *  纯路径字符串比较（不在组合期做 canonicalFile 等磁盘 IO；防环以 planner 的 canonical 校验兜底）。 */
    fun isDisabled(physical: File): Pair<Boolean, String?> {
        for (src in sourceDirs) {
            if (physical == src || MovePlanner.isWithin(physical, src)) {
                return true to context.getString(R.string.files_picker_source_locked)
            }
        }
        if (layerOf(physical.absolutePath, layerRoots) == Layer.SYSTEM_DIR) {
            return true to context.getString(R.string.files_picker_system_locked)
        }
        return false to null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg())
            // 覆盖页消费点击，防止空白处触发底层文件列表。
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- 顶栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(titleRes),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary(),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
                        contentDescription = stringResource(R.string.files_cancel),
                        tint = TextSecondary(),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 与文件页左上角同一套根视图控件（单控件 + 无尾箭头 + 下拉面板）
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
                        if (shareAvailable) {
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
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Breadcrumb(
                    root = root,
                    rootLabel = rootLabel,
                    currentDir = currentDir,
                    onNavigate = { currentDir = it },
                    // 共享区是虚拟命名空间根：parentFile 回溯走不通，用条目集合自建面包屑。
                    segmentsOverride = if (rootMode == 2) {
                        shareCrumbsOf(currentDir, shareNamespace, rootLabel, shareEntries)
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )
            }

            // ---------- 目录列表（仅文件夹） ----------
            if (dirs.isEmpty()) {
                // 空态用 weight(1f) 而非 fillMaxSize：fillMaxSize 会把底部
                // 「移动到当前文件夹」按钮挤出可视区（无子目录的文件夹正是最该可落地的目标，
                // 「点进去落地不了、下方没有按钮」即由此而来）
                Column(
                    // weight 仅控制高度；填满可用宽度以使空状态内容水平居中。
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_folder_open),
                        contentDescription = null,
                        tint = Color(0xFFE5E7EB),
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.files_picker_empty),
                        fontSize = 13.sp,
                        color = TextSecondary(),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(dirs, key = { it.logicalPath }) { entry ->
                        val physical = remember(entry.logicalPath) {
                            mapper.resolvePhysical(File(entry.logicalPath))
                        }
                        val (disabled, reason) = isDisabled(physical)
                        PickerDirRow(
                            name = entry.name,
                            subtitle = reason,
                            enabled = !disabled,
                            onClick = { currentDir = File(entry.logicalPath) },
                        )
                    }
                }
            }

            // ---------- 底部：落点提示 + 操作按钮 ----------
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardBg())
                    .border(0.5.dp, DividerColor())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                pickerTargetHint(mapper, currentDir)?.let { hint ->
                    Text(
                        text = hint,
                        fontSize = 11.sp,
                        color = TextHint(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 次按钮：新建文件夹
                    IconButton(
                        enabled = !atNamespaceRoot,
                        onClick = {
                            val layer = layerOf(mapper.resolvePhysical(currentDir).absolutePath, layerRoots)
                            if (riskLevelOfLayer(layer) != null) {
                                pendingRiskForNewFolder = layer
                            } else {
                                showNewFolderDialog = true
                            }
                        },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_folder_plus),
                            contentDescription = stringResource(R.string.files_new_folder),
                            tint = PrimaryGreen,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    // 主按钮：移动到当前文件夹
                    TextButton(
                        shape = MaterialTheme.shapes.medium,
                        enabled = !atNamespaceRoot,
                        onClick = { onConfirm(currentDir) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            text = pluralStringResource(confirmPluralRes, moveCount, moveCount),
                            color = PrimaryGreen,
                        )
                    }
                }
            }
        }
    }

    // ---------- 新建文件夹对话框 ----------
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
                        if (safeName == null) {
                            Toast.makeText(context, R.string.files_name_invalid, Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        val physical = mapper.resolvePhysical(currentDir)
                        val created = runCatching {
                            val target = File(physical, safeName)
                            if (target.exists()) {
                                Toast.makeText(context, R.string.files_rename_conflict, Toast.LENGTH_SHORT).show()
                                return@TextButton
                            }
                            target.mkdirs()
                        }
                        created.onFailure {
                            Toast.makeText(context, it.message ?: context.getString(R.string.files_create_failed), Toast.LENGTH_SHORT).show()
                        }
                        if (created.isSuccess) {
                            newFolderName = ""
                            showNewFolderDialog = false
                            // 建出目录后立即进入（currentDir 变化触发 LaunchedEffect 重扫）
                            currentDir = File(currentDir, safeName)
                        }
                    },
                    enabled = newFolderName.isNotBlank(),
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

    // ---------- 风险目录内新建文件夹的强确认（处置语义与 Layer 文案同源） ----------
    if (pendingRiskForNewFolder != null) {
        val layer = pendingRiskForNewFolder!!
        AlertDialog(
            onDismissRequest = { pendingRiskForNewFolder = null },
            title = { Text(stringResource(R.string.files_risk_title)) },
            text = {
                Text(
                    text = stringResource(
                        riskDialogTextRes(layer, isMoveAction = false, sandboxRunning = sandboxRunning),
                        currentDir.name,
                    ),
                    fontSize = 14.sp,
                    color = TextSecondary(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRiskForNewFolder = null
                    showNewFolderDialog = true
                }) {
                    Text(stringResource(R.string.files_risk_continue), color = PrimaryGreen)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRiskForNewFolder = null }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }
}

@Composable
private fun PickerDirRow(
    name: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (subtitle != null) 62.dp else 52.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_folder),
            contentDescription = null,
            tint = if (enabled) PrimaryGreen else TextHint(),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontSize = 14.sp,
                color = if (enabled) TextPrimary() else TextHint(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = TextHint(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        thickness = 0.5.dp,
        color = DividerColor(),
    )
}

/** 挂载点遮蔽提示：逻辑落点经重定向时，小字提示真实物理落点。 */
@Composable
private fun pickerTargetHint(mapper: PathMapper, logical: File): String? {
    val lp = logical.absolutePath.trimEnd('/')
    val projects = mapper.rootfsProjects.absolutePath.trimEnd('/')
    val usrLocal = mapper.rootfsUsrLocal.absolutePath.trimEnd('/')
    val optDsh = mapper.rootfsOptDshRuntime.absolutePath.trimEnd('/')
    return when {
        lp == usrLocal || lp.startsWith("$usrLocal/") ->
            stringResource(R.string.files_picker_hint_node)
        lp == optDsh || lp.startsWith("$optDsh/") ->
            stringResource(R.string.files_picker_hint_dsh)
        lp == projects || lp.startsWith("$projects/") ->
            stringResource(R.string.files_picker_hint_workspace)
        else -> null
    }
}
