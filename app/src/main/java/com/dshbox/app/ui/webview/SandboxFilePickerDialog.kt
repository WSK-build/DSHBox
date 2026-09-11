package com.dshbox.app.ui.webview

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.dshbox.app.R
import com.dshbox.app.ui.files.Breadcrumb
import com.dshbox.app.ui.files.DividerColor
import com.dshbox.app.ui.files.PageBg
import com.dshbox.app.ui.files.SelectedRowBg
import com.dshbox.app.ui.files.TextHint
import com.dshbox.app.ui.files.TextPrimary
import com.dshbox.app.ui.files.TextSecondary
import com.dshbox.app.util.formatFileSize
import com.dshbox.app.util.viewer.ExternalOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 沙箱文件选择器（1.3.1 M5）。
 *
 * ## 为什么需要它
 *
 * 网页端 `<input type="file">` 走系统 `ACTION_GET_CONTENT` 时，**只能列出手机侧内容来源**；
 * PRoot 沙箱（guest `/root/projects`）不是 Android 的 DocumentsProvider，因此永远选不到。
 * 要让「上传沙箱文件」成立，必须在 app 侧自带一个选择器。
 *
 * ## 为什么不需要新增 Manifest / Provider
 *
 * PRoot 启动参数为 `--bind=user-data:/root/projects`，即
 * **guest `/root/projects` 与宿主 `filesDir/user-data` 是同一份数据**（见 [com.dshbox.app.util.PathMapper]）。
 * 而 `AndroidManifest.xml` 的 FileProvider 已经声明了
 * `<files-path name="user_data" path="user-data/" />`，
 * 所以本选择器选中的文件可以**直接**铸成
 * `content://com.dshbox.app.fileprovider/user_data/<相对路径>`
 * 交给 WebView —— 零清单改动、零新增 Provider。
 *
 * 又因为 WebView 与 FileProvider 同进程同 UID，`exported=false` 不会造成读取障碍。
 * 但**同 UID 不等于读得到**：content URI 的读取资格仍需显式授予，故 [mintUris]
 * 铸完 URI 后逐条 `grantUriPermission` 读权限（与 [ExternalOpener] 的既有范式一致）。
 *
 * @param multiple 网页端 `<input multiple>` 是否为多选；false 时单选。
 * @param onDismiss 用户取消（未选择）；调用方必须以 `null` 结清 WebView 回调。
 * @param onConfirm 用户确认；回调选中的 `content://` URI 数组。
 */
@Composable
internal fun SandboxFilePickerDialog(
    multiple: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Array<Uri>) -> Unit,
) {
    val context = LocalContext.current
    // 逻辑根 = 工作区（guest /root/projects）。物理位置就是 filesDir/user-data ——
    // 也正是 FileProvider 已授权的 user_data 根，两者必须是同一个 File 实例。
    val root = remember { File(context.filesDir, "user-data") }
    // 越界判定用的 canonical 根：字符串前缀比较会被 `user-data-backup` 之类的
    // 兄弟目录骗过（`…/user-data-backup` 以 `…/user-data` 开头）。
    val canonicalRoot = remember(root) {
        runCatching { root.canonicalFile }.getOrDefault(root)
    }

    var currentDir by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<File>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failed by remember { mutableStateOf(false) }

    // 目录列表在 IO 线程读取；切目录时重置为 null 触发 loading。
    LaunchedEffect(currentDir) {
        entries = null
        failed = false
        val list = withContext(Dispatchers.IO) {
            runCatching {
                (currentDir.listFiles() ?: emptyArray())
                    .filter { it.exists() }
                    .sortedWith(
                        compareByDescending<File> { it.isDirectory }
                            .thenBy { it.name.lowercase() },
                    )
            }.getOrNull()
        }
        if (list == null) {
            failed = true
            entries = emptyList()
        } else {
            entries = list
        }
    }

    // 进入子目录后，选择集合保留（跨目录多选是常见诉求），但父目录的同名文件不会冲突
    // —— 选择集合按绝对路径记录，天然唯一。
    //
    // `isFile` 是一次 stat（磁盘 IO），此前直接在组合体里 map/filter —— 每次重组
    // （滚动、勾选连带的重组）都会对每个选中项重新 stat 一遍。改为随 selected 变化
    // 在 IO 线程算一次，与本文件目录列举（LaunchedEffect(currentDir)）口径一致。
    //
    // 注意：这份列表**只用于铸造 URI**。计数与「添加」可用性直接取 [selected]
    // （勾选集合本身，零 IO、与复选框状态严格一致），免得 UI 落后于用户操作
    // 等一次协程。铸造时仍然要过滤：文件可能在勾选后被删除，给 WebView 一个
    // 指向已消失文件的 URI 会让附件静默损坏。
    var selectedFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(selected) {
        selectedFiles = withContext(Dispatchers.IO) {
            selected.map(::File).filter { it.isFile }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = PageBg(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ── 头部：标题 + 返回上级 + 关闭 ──────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val parent = currentDir.parentFile
                            // 不允许越过工作区根（user-data 之上的 runtime 层不是上传目标）。
                            // 判定对象是**目标父目录**而非当前目录，并用 canonical 路径段比较：
                            //   · 字符串前缀会被 `…/user-data-backup` 这类兄弟目录骗过；
                            //   · 若当前目录是越界符号链接（canonical 在根外），比对当前目录
                            //     会直接关掉对话框，比对父目录则能正常退回根内。
                            if (parent != null && isWithinRoot(parent, canonicalRoot)) {
                                currentDir = parent
                            } else {
                                onDismiss()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.webview_upload_up),
                            tint = TextPrimary(),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.webview_upload_sandbox_title),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary(),
                        )
                        Text(
                            text = stringResource(R.string.webview_upload_sandbox_hint),
                            fontSize = 12.sp,
                            color = TextHint(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.files_close),
                            tint = TextPrimary(),
                        )
                    }
                }

                // ── 面包屑 ───────────────────────────────────
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Breadcrumb(
                        root = root,
                        rootLabel = stringResource(R.string.files_root_workspace),
                        currentDir = currentDir,
                        onNavigate = { target ->
                            if (target.isDirectory) currentDir = target
                        },
                    )
                }

                HorizontalDivider(color = DividerColor())

                // ── 列表 ─────────────────────────────────────
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val list = entries
                    when {
                        list == null -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }

                        list.isEmpty() -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(
                                    if (failed) R.string.files_viewer_read_failed
                                    else R.string.files_empty_hint,
                                ),
                                fontSize = 14.sp,
                                color = TextHint(),
                            )
                        }

                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(list, key = { it.absolutePath }) { file ->
                                PickerRow(
                                    file = file,
                                    checked = selected.contains(file.absolutePath),
                                    onToggle = {
                                        val path = file.absolutePath
                                        selected = if (selected.contains(path)) {
                                            selected - path
                                        } else if (multiple) {
                                            selected + path
                                        } else {
                                            setOf(path)
                                        }
                                    },
                                    onEnter = { currentDir = file },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = DividerColor())

                // ── 底部：已选数量 + 取消 / 添加 ──────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.files_selected_count,
                            selected.size,
                            selected.size,
                        ),
                        fontSize = 13.sp,
                        color = TextSecondary(),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.files_cancel))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val uris = mintUris(context, selectedFiles)
                            // 分母取勾选数（用户以为选了几个），分子取「勾选数 − 实际铸成数」：
                            // 消失的文件与铸造失败的文件都算在内，口径与复选框一致。
                            val skipped = selected.size - uris.size
                            when {
                                uris.isEmpty() -> {
                                    // 全部失败（文件被删 / 越出授权根）：明确告知而非静默无反应
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.webview_upload_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onDismiss()
                                }

                                skipped > 0 -> {
                                    // 部分失败：不能静默少交——用户会以为全都上传了。
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.webview_upload_partial_failed,
                                            skipped,
                                            selected.size,
                                        ),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    onConfirm(uris.toTypedArray())
                                }

                                else -> onConfirm(uris.toTypedArray())
                            }
                        },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.webview_upload_add))
                    }
                }
            }
        }
    }
}

/**
 * 把选中的工作区文件铸成 FileProvider `content://` URI。
 *
 * 只有位于已声明授权根（`files-path user-data/`）下的文件才可铸造；
 * `getUriForFile` 对越界路径抛 [IllegalArgumentException]，此处逐项容错跳过，
 * 避免一个坏条目让整批选择失败。
 *
 * authority 取 [ExternalOpener.FILE_PROVIDER_AUTHORITY]（与 Manifest 声明、
 * 与外发路径同一口径），不在此处另拼字符串。
 *
 * **显式授予读权限**：`grantUriPermission` 是 WebView 读 `content://` 的前置条件。
 * 既有外发路径（[ExternalOpener]）每次都带 `FLAG_GRANT_READ_URI_PERMISSION`，
 * 这里没有 Intent 可挂 flag，因此直接对目标包授权——与既有范式对齐，
 * 不依赖「同 UID 应该能读」这一未定义的 ROM 行为。
 */
private fun mintUris(context: android.content.Context, files: List<File>): List<Uri> {
    val authority = ExternalOpener.FILE_PROVIDER_AUTHORITY
    return files.mapNotNull { file ->
        runCatching {
            FileProvider.getUriForFile(context, authority, file).also { uri ->
                runCatching {
                    context.grantUriPermission(
                        context.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }.getOrNull()
    }
}

/**
 * [path] 是否位于 [canonicalRoot] 之内（含根自身）。
 *
 * 用 canonical 绝对路径做**路径段**比较：`startsWith(根字符串)` 的朴素写法会被
 * `…/user-data-backup`、`…/user-data2` 这类同前缀的兄弟目录骗过 —— 它们的路径
 * 字符串确实以 `…/user-data` 开头，但并不在根内。
 */
internal fun isWithinRoot(path: File, canonicalRoot: File): Boolean {
    val rootPath = canonicalRoot.absolutePath.trimEnd(File.separatorChar)
    val pathPath = runCatching { path.canonicalFile }.getOrDefault(path)
        .absolutePath
        .trimEnd(File.separatorChar)
    return pathPath == rootPath || pathPath.startsWith("$rootPath${File.separator}")
}

/** 单行：目录可进入；文件可勾选。 */
@Composable
private fun PickerRow(
    file: File,
    checked: Boolean,
    onToggle: () -> Unit,
    onEnter: () -> Unit,
) {
    val isDir = file.isDirectory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) SelectedRowBg() else PageBg())
            .clickable { if (isDir) onEnter() else onToggle() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isDir) Icons.Filled.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (isDir) MaterialTheme.colorScheme.primary else TextHint(),
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                fontSize = 15.sp,
                color = TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (isDir) {
                    stringResource(R.string.files_entry_directory)
                } else {
                    formatFileSize(file.length())
                },
                fontSize = 12.sp,
                color = TextHint(),
            )
        }
        if (!isDir) {
            Spacer(modifier = Modifier.width(8.dp))
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
            )
        }
    }
}
