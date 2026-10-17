package com.dshbox.app.ui.webview

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.ui.files.Breadcrumb
import com.dshbox.app.ui.files.DividerColor
import com.dshbox.app.ui.files.PageBg
import com.dshbox.app.ui.files.SelectedRowBg
import com.dshbox.app.ui.files.TextHint
import com.dshbox.app.ui.files.TextPrimary
import com.dshbox.app.ui.files.TextSecondary
import com.dshbox.app.util.MAX_DIRECTORY_FILES
import com.dshbox.app.util.capDirectoryFiles
import com.dshbox.app.util.formatFileSize
import com.dshbox.app.util.shareBinds
import com.dshbox.app.util.shareCrumbsOf
import com.dshbox.app.util.shareNamespaceParentOf
import com.dshbox.app.util.viewer.ExternalOpener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * 本板块（DSH）自有的上传取文件根：工作区 / 共享区。
 *
 * 网页端 `<input type="file">` 走系统选择器只能列出手机侧内容来源；PRoot 沙箱不是
 * Android 的 DocumentsProvider，因此**工作区与共享区永远选不到** —— 必须由本板块
 * 自带选择器。两条根的通路不同：
 *
 *  - [WORKSPACE]：宿主 `filesDir/user-data`（guest `/root/projects`），真实目录树，
 *    上溯靠 `parentFile` 并在根处截断。
 *  - [SHARE]：guest `/root/share` 是**虚拟命名空间**（宿主无此路径），其下才是
 *    内建 `DSHBox`（公共存储）与用户挂载目录；因此列举、面包屑与上溯都必须走
 *    挂载集合，不能 `parentFile` 上溯（否则会逃到公共存储根）。
 */
internal enum class UploadPickRoot { WORKSPACE, SHARE }

/**
 * 上传取文件选择器（工作区 / 共享区）。
 *
 * ## 为什么不需要新增 Manifest / Provider
 *
 * PRoot 启动参数为 `--bind=user-data:/root/projects`，即 guest `/root/projects` 与宿主
 * `filesDir/user-data` 是同一份数据（见 [com.dshbox.app.util.PathMapper]）；共享区
 * 同理绑定到公共存储 `DSHBox`。`AndroidManifest.xml` 的 FileProvider 已声明
 * `user_data`（`files-path user-data/`）与 `share_area` / `share_mounts`
 * （`external-path`），因此两个根下的文件都能**直接**铸成
 * `content://com.dshbox.app.fileprovider/…` 交给 WebView —— 零清单改动、零新增 Provider。
 *
 * 又因为 WebView 与 FileProvider 同进程同 UID，`exported=false` 不会造成读取障碍。
 * 但**同 UID 不等于读得到**：content URI 的读取资格仍需显式授予，故 [mintUris]
 * 铸完 URI 后逐条 `grantUriPermission` 读权限（与 [ExternalOpener] 的既有范式一致）。
 *
 * ## 覆盖层形态与 inset 归属
 *
 * **不用 `Dialog`，是本板块页签内容树内的全屏覆盖层**（与文件板块的目录选择器、内置文件
 * 查看器同一种形态）。原因：`Dialog` 是**独立窗口**，它的 inset 行为由 ROM 决定 ——
 * 实测在目标机上平台只把窗口位置下移了状态栏高度、却不把内容的高度约束压到可见区，
 * 于是内容按整屏排版、底部那一行（已选数量 / 取消 / 添加）被推出可见区裁掉；
 * 而 `WindowInsets` 在该独立窗口里读回来是 0，任何"自适应补回来"的写法都是空操作。
 * 结论：**依赖对话框窗口让位在设计上就不自适应** —— 换设备 / 平板 / 横屏各是一套结果。
 *
 * 覆盖层则长在 Activity 窗口里，而 inset 正是派发给这个窗口的：DSH 页签的
 * `Scaffold(contentWindowInsets = WindowInsets.navigationBars)` 已把底部让位下发成
 * `innerPadding`，覆盖层继承它即可**按设备实际 inset 自适应**（带导航栏自动上抬、
 * 无导航栏贴底、平板与横屏同一套规则），不需要任何写死的补偿值。输入法另由
 * [imePadding] 处理：DSH 页签的 `contentWindowInsets` 不含 ime，而 Activity 窗口里的
 * `WindowInsets.ime` 可靠，且不会与上面的让位重复计算。
 *
 * 底部的确认按钮也从品牌绿实心改为与首页「启动 / 重启 / 关闭」同款的描边方框
 * （[OutlinedButton] + `MaterialTheme.shapes.medium`），与整机风格统一。
 *
 * 命名说明：文件与函数名沿用历史命名（原实现确为 `Dialog`），现为内容树内覆盖层。
 *
 * @param root 取文件根（工作区 / 共享区）。
 * @param multiple 是否多选（由来源面板按页面 `<input multiple>` 决定）。
 * @param backEnabled 当前页签是否活跃；非活跃时不消费返回键（与内置查看器同约定）。
 * @param onDismiss 用户取消（未选择）；调用方必须以 `null` 结清 WebView 回调。
 * @param onConfirm 用户确认；回调选中的 `content://` URI 数组。
 */
@Composable
internal fun SandboxFilePickerDialog(
    root: UploadPickRoot,
    multiple: Boolean,
    backEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Array<Uri>) -> Unit,
) {
    val context = LocalContext.current
    // 工作区逻辑根 = guest /root/projects。物理位置就是 filesDir/user-data ——
    // 也正是 FileProvider 已授权的 user_data 根，两者必须是同一个 File 实例。
    val workspaceRoot = remember { File(context.filesDir, "user-data") }
    // 越界判定用的 canonical 根：字符串前缀比较会被 `user-data-backup` 之类的
    // 兄弟目录骗过（`…/user-data-backup` 以 `…/user-data` 开头）。
    val canonicalRoot = remember(workspaceRoot) {
        runCatching { workspaceRoot.canonicalFile }.getOrDefault(workspaceRoot)
    }
    // 共享区命名空间根：虚拟目录，宿主侧不存在这个路径，只用于列举与面包屑。
    val shareNamespace = remember { File(Constants.SHARE_AREA_GUEST_DIR) }
    // 共享区绑定清单（内建 DSHBox 在前、用户挂载在后）。授权被撤销时为空。
    // 组合期不可做磁盘 IO：清单读取与逐条 isDirectory 都下沉到 IO 线程异步加载，
    // 加载完成前保持空值（渲染为「加载中」，与既有的异步目录列举表现一致）。
    //
    // 按 [root] 键控：只有共享区视图需要这份清单，工作区视图直接给空列表 —— 避免在
    // 工作区下也跑一遍清单读取与逐条 stat，也让切换根时重新加载最新清单。
    // 取消（切根 / 离开组合）必须继续上抛：CancellationException 被当作失败吞掉时，
    // producer 会在取消后仍给已废弃的 state 赋值；其余异常按空清单降级。
    val shareEntriesState by produceState<Result<List<Pair<String, File>>>?>(
        initialValue = null,
        key1 = root,
    ) {
        value = if (root != UploadPickRoot.SHARE) {
            Result.success(emptyList<Pair<String, File>>())
        } else {
            try {
                Result.success(withContext(Dispatchers.IO) { shareBinds(context) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    // 命名空间层条目即挂载清单：加载成功用清单，失败按空清单处理（授权撤销 / 清单不可读）。
    val shareEntries = shareEntriesState?.getOrDefault(emptyList()).orEmpty()
    // 仅共享区根随清单加载状态刷新列举（加载中 → 加载完成）；工作区根不受清单影响。
    val shareListKey = if (root == UploadPickRoot.SHARE) shareEntriesState else null

    val startDir = if (root == UploadPickRoot.SHARE) shareNamespace else workspaceRoot
    var currentDir by remember(root) { mutableStateOf(startDir) }
    var entries by remember { mutableStateOf<List<PickEntry>?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var failed by remember { mutableStateOf(false) }
    /** 文件数超过上限被截断（目录不受限）；仅在真的截断时提示。 */
    var truncated by remember { mutableStateOf(false) }

    // 目录列表在 IO 线程读取；切目录时重置为 null 触发 loading。
    // shareListKey 并入 key：共享区根需在清单加载完成后重建命名空间层条目
    // （含「空清单」也要从 loading 落到空态，故用加载状态对象本身而非清单内容）。
    LaunchedEffect(currentDir, root, shareListKey) {
        entries = null
        failed = false
        truncated = false
        val atShareNamespace =
            root == UploadPickRoot.SHARE && currentDir.absolutePath == shareNamespace.absolutePath
        if (atShareNamespace) {
            // 命名空间层是虚拟目录：条目即挂载清单，不读磁盘。
            // 清单尚未加载完成时保持 null（渲染 loading），避免先闪一屏空列表。
            if (shareEntriesState == null) return@LaunchedEffect
            entries = shareEntries.map { (name, target) -> PickEntry(name, target, isDirectory = true) }
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val children = (currentDir.listFiles() ?: emptyArray()).filter { it.exists() }
                val dirs = children.filter { it.isDirectory }
                val files = children.filter { !it.isDirectory }
                // 与文件页同口径：目录全保留，文件按名称取前 N 条（复用同一封顶判据）。
                val (keptFiles, over) = capDirectoryFiles(files)
                Pair(
                    (dirs + keptFiles).sortedWith(
                        compareByDescending<File> { it.isDirectory }
                            .thenBy { it.name.lowercase() },
                    ),
                    over,
                )
            }.getOrNull()
        }
        if (result == null) {
            failed = true
            entries = emptyList()
        } else {
            truncated = result.second
            entries = result.first.map { PickEntry(it.name, it, it.isDirectory) }
        }
    }

    // 进入子目录后，选择集合保留（跨目录多选是常见诉求），但父目录的同名文件不会冲突
    // —— 选择集合按绝对路径记录，天然唯一。
    //
    // 勾选集合本身（[selected]）就是「用户选了哪些」的唯一真相：计数与「添加」可用性
    // 直接取它（零 IO、与复选框严格一致），铸造 URI 时也**在点击那一刻**由它现算
    // ——不再维护一份随 selected 异步刷新的缓存：勾选后立刻点「添加」时缓存可能还没刷新，
    // 那会把「已选 N 个」误判成「全部失败」并关闭选择器，用户的勾选白做。
    // 现算的代价只是每次点击逐项一次 stat（数量＝用户勾选数）。

    // 返回键：与「返回上级」同一套规则 —— 先退一级，到根再以取消结清。
    // enabled 交给调用方（页签活跃时才消费），与内置查看器的约定一致。
    BackHandler(enabled = backEnabled) {
        val up = upTargetOf(
            root = root,
            currentDir = currentDir,
            workspaceRoot = workspaceRoot,
            canonicalRoot = canonicalRoot,
            shareNamespace = shareNamespace,
            shareEntries = shareEntries,
        )
        if (up != null) currentDir = up else onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 层级：悬浮球（10f）之上、内置文件查看器（50f）之下。
            .zIndex(40f)
            // 覆盖层吞噬点击：空白处不穿透到下层 WebView（与既有覆盖层同范式）。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* 有意为空：只用于吞掉点击 */ },
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = PageBg(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 输入法让位：DSH 页签的 contentWindowInsets 不含 ime，故在此单独补；
                    // 但要**排除导航栏分量** —— 底部那部分已由父级 Scaffold 的 innerPadding
                    // 让过一次，直接 imePadding() 会在键盘与导航栏同时可见时叠成两份底距。
                    .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars)),
            ) {
                // ── 头部：标题 + 返回上级 + 关闭 ──────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = {
                            val up = upTargetOf(
                                root = root,
                                currentDir = currentDir,
                                workspaceRoot = workspaceRoot,
                                canonicalRoot = canonicalRoot,
                                shareNamespace = shareNamespace,
                                shareEntries = shareEntries,
                            )
                            if (up != null) currentDir = up else onDismiss()
                        },
                    ) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.webview_upload_up),
                            tint = TextPrimary(),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (root == UploadPickRoot.SHARE) R.string.webview_upload_share_title
                                else R.string.webview_upload_workspace_title,
                            ),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary(),
                        )
                        Text(
                            text = stringResource(
                                if (root == UploadPickRoot.SHARE) R.string.webview_upload_share_hint
                                else R.string.webview_upload_workspace_hint,
                            ),
                            fontSize = 12.sp,
                            color = TextHint(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
                            contentDescription = stringResource(R.string.files_close),
                            tint = TextPrimary(),
                        )
                    }
                }

                // ── 面包屑 ───────────────────────────────────
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    if (root == UploadPickRoot.SHARE) {
                        // 共享区是虚拟命名空间：parentFile 回溯走不通，用挂载集合自建段。
                        Breadcrumb(
                            root = shareNamespace,
                            rootLabel = stringResource(R.string.files_root_share),
                            currentDir = currentDir,
                            onNavigate = { target -> currentDir = target },
                            segmentsOverride = shareCrumbsOf(
                                currentDir,
                                shareNamespace,
                                stringResource(R.string.files_root_share),
                                shareEntries,
                            ),
                        )
                    } else {
                        Breadcrumb(
                            root = workspaceRoot,
                            rootLabel = stringResource(R.string.files_root_workspace),
                            currentDir = currentDir,
                            onNavigate = { target ->
                                if (target.isDirectory) currentDir = target
                            },
                        )
                    }
                }

                HorizontalDivider(color = DividerColor())

                // 文件数超限提示：目录始终完整，只有文件被截断才出现。
                if (truncated) {
                    Text(
                        text = stringResource(R.string.files_list_truncated, MAX_DIRECTORY_FILES),
                        fontSize = 12.sp,
                        color = TextHint(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

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
                            items(list, key = { it.target.absolutePath }) { entry ->
                                PickerRow(
                                    entry = entry,
                                    checked = selected.contains(entry.target.absolutePath),
                                    onToggle = {
                                        val path = entry.target.absolutePath
                                        selected = if (selected.contains(path)) {
                                            selected - path
                                        } else if (multiple) {
                                            selected + path
                                        } else {
                                            setOf(path)
                                        }
                                    },
                                    onEnter = { currentDir = entry.target },
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
                    OutlinedButton(
                        // 与首页「启动 / 重启 / 关闭」同款：描边方框、透明底、强调色文字。
                        shape = MaterialTheme.shapes.medium,
                        onClick = {
                            // 点击这一刻现算：勾选后立刻点「添加」也不会因缓存未刷新而误判
                            // 「全部失败」。过滤是必要的——文件可能在勾选后被删除，给 WebView
                            // 一个指向已消失文件的 URI 会让附件静默损坏。
                            val files = selected.map(::File).filter { it.isFile }
                            val uris = mintUris(context, files)
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

/** 选择器列表条目：命名空间层的虚拟条目与真实目录共用同一形状。 */
private data class PickEntry(
    val name: String,
    val target: File,
    val isDirectory: Boolean,
)

/**
 * 「返回上级」的目标；null 表示已到根，调用方据此关闭选择器。
 *
 * 两个根的规则不同，故集中在这里，避免点击处与面包屑两套口径分叉：
 *  - 工作区：取父目录，但**不允许越过工作区根**（user-data 之上的运行层不是上传目标）。
 *    判定对象是**目标父目录**而非当前目录，并用 canonical 路径段比较：
 *    字符串前缀会被 `…/user-data-backup` 这类兄弟目录骗过；若当前目录是越界符号链接
 *    （canonical 在根外），比对当前目录会直接关掉对话框，比对父目录则能正常退回根内。
 *  - 共享区：命名空间是虚拟根，`parentFile` 会逃到公共存储根，故走挂载集合判定；
 *    到达命名空间即视为到根（返回 null）。
 */
private fun upTargetOf(
    root: UploadPickRoot,
    currentDir: File,
    workspaceRoot: File,
    canonicalRoot: File,
    shareNamespace: File,
    shareEntries: List<Pair<String, File>>,
): File? = if (root == UploadPickRoot.SHARE) {
    val parent = shareNamespaceParentOf(currentDir, shareNamespace, shareEntries)
    parent.takeIf { it.absolutePath != currentDir.absolutePath }
} else {
    currentDir.parentFile?.takeIf { isWithinRoot(it, canonicalRoot) }
}

/**
 * 把选中的工作区 / 共享区文件铸成 FileProvider `content://` URI。
 *
 * 只有位于已声明授权根（`files-path user-data/`、`external-path DSHBox/` 与公共存储根）
 * 下的文件才可铸造；`getUriForFile` 对越界路径抛 [IllegalArgumentException]，
 * 此处逐项容错跳过，避免一个坏条目让整批选择失败。
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
    entry: PickEntry,
    checked: Boolean,
    onToggle: () -> Unit,
    onEnter: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (checked) SelectedRowBg() else PageBg())
            .clickable { if (entry.isDirectory) onEnter() else onToggle() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) ImageVector.vectorResource(CommonR.drawable.ic_folder) else ImageVector.vectorResource(CommonR.drawable.ic_file),
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else TextHint(),
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                fontSize = 15.sp,
                color = TextPrimary(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (entry.isDirectory) {
                    stringResource(R.string.files_entry_directory)
                } else {
                    formatFileSize(entry.target.length())
                },
                fontSize = 12.sp,
                color = TextHint(),
            )
        }
        if (!entry.isDirectory) {
            Spacer(modifier = Modifier.width(8.dp))
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
            )
        }
    }
}
