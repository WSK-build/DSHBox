package com.dshbox.app.ui.files.viewer

import android.graphics.Typeface
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dshbox.app.R
import com.dshbox.app.util.viewer.ContentFingerprint
import com.dshbox.app.util.viewer.LargeTextLoader
import com.dshbox.app.util.viewer.TextEncoding
import com.dshbox.app.util.viewer.TextFileStore
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 分块只读窗口的渲染字符上限（返工 #1：超长行整窗读入时防 Compose Text 布局卡死；显示截断，翻页不丢内容）。 */
private const val CHUNK_RENDER_MAX_CHARS = 200_000

/** 文本编辑控制器：FileViewerScreen（返回拦截/保存/菜单入口）与 TextCodeViewer（实现）的桥。 */
internal class TextEditController {
    var dirty by mutableStateOf(false)

    /** 「未保存弹窗 → 保存」信号。 */
    var saveSignal by mutableIntStateOf(0)

    /** 「不保存」信号：丢弃修改回到已保存内容。 */
    var discardSignal by mutableIntStateOf(0)

    /** 外壳菜单「编码切换」触发信号。 */
    var encodingSignal by mutableIntStateOf(0)

    /** 换行目标：null = 保存时按原文件风格还原（§6.4 默认）；用户显式转换后为具体风格。 */
    var newlineTarget by mutableStateOf<TextEncoding.NewlineStyle?>(null)

    /** 返工 P1：有损解码标记（存在无法解码的字节）——UI 禁编辑并常驻警告。 */
    var lossy by mutableStateOf(false)

    /**
     * M3（§6.7）：当前编辑文本读取桥——MarkupViewer 预览用它反映未保存的编辑；
     * 由 TextCodeViewer 进入组合时挂载、离开时清除。null = 无活动文本视图。
     */
    var textProvider: (() -> String)? by mutableStateOf(null)
}

/**
 * 文本/代码查看与编辑（1.2.0 §6.4）。
 *
 * - 编辑态：Sora `CodeEditor`（AndroidView 嵌入，撤销/行号/搜索自带）+ 自研高亮；
 *   工厂异常降级 BasicTextField——第三方组件崩溃不得让文件打不开；
 * - 只读态：≤2MB 全文；2–10MB / >10MB 分块窗口（[LargeTextLoader]），>10MB 附尾部模式；
 * - 编码：探测结果解码，菜单手动切换重新解码；换行保存时默认原样保留（[newlineTarget]）；
 * - 保存：[TextFileStore] 原子写 + 权限位/时间戳复制 + 外部变更检测三选一拦截。
 */
@Composable
internal fun TextCodeViewer(
    file: File,
    fullBytes: ByteArray?,
    detection: TextEncoding.Detection,
    fingerprint: ContentFingerprint?,
    plan: LargeTextLoader.Plan,
    highlightLanguage: String?,
    editing: Boolean,
    controller: TextEditController,
    onRequestRefresh: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 文本状态（LF 归一编辑表示；保存时按 newlineTarget 还原；fullBytes 实例变化 = 重新初始化） ----
    var originalBytes by remember(file, fullBytes) { mutableStateOf(fullBytes) }
    var savedFingerprint by remember(file, fullBytes) { mutableStateOf(fingerprint) }
    var charset by remember(file, fullBytes) { mutableStateOf(detection.charset) }
    var bomLength by remember(file, fullBytes) { mutableStateOf(detection.bomLength) }
    // 返工 P1：带损解码——REPORT 试解失败 = 存在无法解码的字节（典型：探测兜底判
    // UTF-8 的未知编码文件）。lossy 时禁编辑（编辑保存会用 U+FFFD 永久覆盖原字节）。
    var decodedFull by remember(file, fullBytes) {
        mutableStateOf(
            fullBytes?.let { TextEncoding.decodeChecked(it, detection) }
                ?: TextEncoding.DecodedText("", lossy = false),
        )
    }
    var lossy by remember(file, fullBytes) { mutableStateOf(decodedFull.lossy) }
    var editedText by remember(file, fullBytes) {
        mutableStateOf(TextEncoding.normalizeToLf(decodedFull.text))
    }
    var savedText by remember(file, fullBytes) { mutableStateOf(editedText) }
    var originalNewline by remember(file, fullBytes) {
        mutableStateOf(TextEncoding.detectNewlines(decodedFull.text).style)
    }
    // 换行保存目标：用户未显式转换时按原文件风格还原（§6.4 默认原样保留）
    val newlineTarget = controller.newlineTarget ?: originalNewline
    var editorInstance by remember(file, fullBytes) { mutableStateOf<CodeEditor?>(null) }
    var soraFailed by remember(file, fullBytes) { mutableStateOf(false) }
    var window by remember(file, fullBytes) { mutableStateOf<LargeTextLoader.TextWindow?>(null) }
    var showEncodingDialog by remember { mutableStateOf(false) }
    // 返工修正 #6：脏状态下选择编码需二次确认（原实现提示后仍静默丢弃）
    var pendingCharset by remember { mutableStateOf<TextEncoding.TextCharset?>(null) }
    var pendingExternalChange by remember { mutableStateOf<ContentFingerprint?>(null) }

    val useHighlight = highlightLanguage != null
    // Sora 承载：编辑态始终；只读态仅高亮预览（其余用轻量 BasicTextField）。
    // 返工 P1：有损解码时强制只读（禁编辑禁保存，仅放行编码切换与另存）。
    val effectiveEditing = editing && !lossy
    val useSora = !soraFailed && (effectiveEditing || (useHighlight && fullBytes != null))
    val hasFullText = fullBytes != null

    // 同步有损标记到控制器（外壳据此拦截「编辑」入口并回落 editing）
    LaunchedEffect(lossy) { controller.lossy = lossy }

    // 分块窗口加载（仅未全量载入时）
    LaunchedEffect(file, fullBytes) {
        if (!hasFullText) {
            window = withContext(Dispatchers.IO) {
                LargeTextLoader.readAlignedWindow(file, 0L, detection)
            }
        }
    }

    fun currentText(): String {
        val editor = editorInstance
        return if (editor != null && hasFullText) editor.text.toString() else editedText
    }

    fun setEditorText(newText: String) {
        editorInstance?.setText(newText)
        editedText = newText
        savedText = newText
        controller.dirty = false
    }

    // M3（§6.7）：向 MarkupViewer 暴露当前文本（预览反映未保存编辑）；离开组合即拆除
    DisposableEffect(file, fullBytes) {
        val provider: () -> String = { currentText() }
        controller.textProvider = provider
        onDispose { if (controller.textProvider === provider) controller.textProvider = null }
    }

    /** 应用编码切换：从原始字节按新编码重新解码（丢弃未保存修改）。 */
    fun applyCharset(option: TextEncoding.TextCharset) {
        charset = option
        originalBytes?.let { bytes ->
            val decoded = TextEncoding.decodeChecked(bytes, option, bomLength)
            decodedFull = decoded
            lossy = decoded.lossy
            setEditorText(TextEncoding.normalizeToLf(decoded.text))
        }
    }

    fun encodeCurrent(): ByteArray {
        val finalText = TextEncoding.applyNewlineStyle(currentText(), newlineTarget)
        return TextEncoding.encode(finalText, charset, bomLength > 0 && charset in BOM_CAPABLE)
    }

    fun doSave(force: Boolean) {
        val text = currentText()
        val out = encodeCurrent()
        scope.launch {
            when (val result = withContext(Dispatchers.IO) {
                TextFileStore.save(file, out, savedFingerprint, force)
            }) {
                is TextFileStore.SaveOutcome.Success -> {
                    savedFingerprint = result.fingerprint
                    savedText = text
                    controller.dirty = false
                    // 返工修正：元数据（rwx/时间戳）恢复失败以警告透传，不得静默
                    onToast(result.warning ?: context.getString(R.string.files_save_done))
                    onRequestRefresh()
                }
                is TextFileStore.SaveOutcome.ExternalChanged -> pendingExternalChange = result.current
                is TextFileStore.SaveOutcome.Failed -> onToast(
                    context.getString(R.string.files_save_failed, result.message),
                )
            }
        }
    }

    // 外壳信号：保存 / 丢弃 / 编码切换弹窗
    LaunchedEffect(controller.saveSignal) {
        if (controller.saveSignal > 0) doSave(force = false)
    }
    LaunchedEffect(controller.discardSignal) {
        if (controller.discardSignal > 0) setEditorText(savedText)
    }
    LaunchedEffect(controller.encodingSignal) {
        if (controller.encodingSignal > 0) showEncodingDialog = true
    }

    // 「另存」出口（外部变更三选一之一）：SAF 落盘
    val saveAsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val target = uri ?: return@rememberLauncherForActivityResult
        val out = encodeCurrent()
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(target)?.use { it.write(out) }
                        ?: error("输出流不可用")
                }.isSuccess
            }
            onToast(context.getString(if (ok) R.string.files_saveas_done else R.string.files_save_failed_generic))
        }
    }

    // ---- 渲染 ----
    Column(modifier = modifier.fillMaxSize()) {
        // 返工 P1：有损解码常驻警告（编辑已禁用；编码切换/另存仍可用）
        if (lossy && hasFullText) {
            Text(
                text = stringResource(R.string.files_lossy_warning),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (hasFullText) {
            if (useSora) {
                SoraEditorView(
                    initialText = editedText,
                    highlightLanguage = if (useHighlight) highlightLanguage else null,
                    editable = effectiveEditing,
                    onEditorReady = { editor ->
                        editorInstance = editor
                        editor.subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
                            if (event.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                                controller.dirty = true
                            }
                        }
                    },
                    onFactoryFailed = { soraFailed = true },
                )
            } else {
                BasicTextField(
                    value = editedText,
                    onValueChange = { if (effectiveEditing) {
                        editedText = it
                        controller.dirty = it != savedText
                    } },
                    readOnly = !effectiveEditing,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        } else {
            // 大文件分块只读（§6.4 大文件策略）。
            // 返工修正 #7：SelectionContainer + Text——只读内容可长按选择/复制
            //（BasicTextField 只读态在部分版本选择行为不可靠），并加纵向滚动。
            window?.let { win ->
                // 返工 #1：分块窗口行尾对齐可能把超长行（big.md ≈738KB/行）整行读入，
                // Compose Text 一次布局数十万字符 → 主线程卡死「锁屏很久」。显示端截断：
                // 下一块起点仍从真实行尾推进，被截断的尾部会在下一块开头重现 → 内容不丢。
                val renderText = if (win.text.length > CHUNK_RENDER_MAX_CHARS) {
                    win.text.take(CHUNK_RENDER_MAX_CHARS) +
                        "\n…（该块行过长，仅显示前 $CHUNK_RENDER_MAX_CHARS 字符，翻页继续）"
                } else {
                    win.text
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (win.lossy) {
                        // 返工 P1：分块窗口的有损提示（只读态无保存路径，仅告知内容不完整）
                        Text(
                            text = stringResource(R.string.files_lossy_warning),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        )
                    }
                    SelectionContainer {
                        Text(
                            text = renderText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = win.hasPrev,
                        onClick = {
                            scope.launch {
                                window = withContext(Dispatchers.IO) {
                                    LargeTextLoader.readAlignedWindow(
                                        file,
                                        (win.startByte - LargeTextLoader.CHUNK_BYTES).coerceAtLeast(0L),
                                        detection,
                                    )
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.files_chunk_prev)) }
                    Text(
                        text = stringResource(
                            R.string.files_chunk_position,
                            win.startByte, win.endByte, plan.sizeBytes,
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(
                            enabled = win.hasNext,
                            onClick = {
                                scope.launch {
                                    window = withContext(Dispatchers.IO) {
                                        LargeTextLoader.readAlignedWindow(file, win.endByte, detection)
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.files_chunk_next)) }
                        if (plan.policy == LargeTextLoader.Policy.FORCE_READONLY_TAIL) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        window = withContext(Dispatchers.IO) {
                                            LargeTextLoader.readTailWindow(file, detection)
                                        }
                                    }
                                },
                            ) { Text(stringResource(R.string.files_chunk_tail)) }
                        }
                    }
                }
            }
        }
    }

    // ---- 编码切换对话框 ----
    if (showEncodingDialog) {
        AlertDialog(
            onDismissRequest = { showEncodingDialog = false },
            title = { Text(stringResource(R.string.files_encoding_title)) },
            text = {
                Column {
                    if (controller.dirty) {
                        Text(
                            text = stringResource(R.string.files_encoding_dirty_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    TextEncoding.TextCharset.entries.forEach { option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            RadioButton(
                                selected = option == charset,
                                onClick = {
                                    if (controller.dirty && option != charset) {
                                        // 返工修正 #6：有未保存修改时切换编码需二次确认
                                        pendingCharset = option
                                    } else {
                                        applyCharset(option)
                                        showEncodingDialog = false
                                    }
                                },
                            )
                            Text(
                                text = option.label +
                                    if (option == detection.charset) stringResource(R.string.files_encoding_detected) else "",
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showEncodingDialog = false }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    // ---- 编码切换的丢弃确认（返工修正 #6：脏状态下二次确认，不静默丢弃） ----
    pendingCharset?.let { option ->
        AlertDialog(
            onDismissRequest = { pendingCharset = null },
            title = { Text(stringResource(R.string.files_encoding_discard_title)) },
            text = {
                Text(
                    text = stringResource(R.string.files_encoding_discard_msg, charset.label, option.label),
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    applyCharset(option)
                    pendingCharset = null
                    showEncodingDialog = false
                }) { Text(stringResource(R.string.files_encoding_discard_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCharset = null }) {
                    Text(stringResource(R.string.files_cancel))
                }
            },
        )
    }

    // ---- 外部变更三选一（§6.4.3：用我的覆盖 / 重新载入 / 另存） ----
    pendingExternalChange?.let { _ ->
        AlertDialog(
            onDismissRequest = { pendingExternalChange = null },
            title = { Text(stringResource(R.string.files_ext_changed_title)) },
            text = { Text(stringResource(R.string.files_ext_changed_msg), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    pendingExternalChange = null
                    doSave(force = true)
                }) { Text(stringResource(R.string.files_ext_changed_overwrite), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                Column {
                    TextButton(onClick = {
                        pendingExternalChange = null
                        // 重新载入：从磁盘读最新内容
                        scope.launch {
                            val loaded = withContext(Dispatchers.IO) { TextFileStore.load(file) }
                            if (loaded != null) {
                                originalBytes = loaded.bytes
                                savedFingerprint = loaded.fingerprint
                                val det = withContext(Dispatchers.IO) {
                                    TextEncoding.detect(loaded.bytes.copyOf(minOf(loaded.bytes.size, 8 * 1024)))
                                }
                                charset = det.charset
                                bomLength = det.bomLength
                                val decoded = TextEncoding.decodeChecked(loaded.bytes, det)
                                decodedFull = decoded
                                lossy = decoded.lossy
                                setEditorText(TextEncoding.normalizeToLf(decoded.text))
                            } else {
                                onToast(context.getString(R.string.files_viewer_read_failed))
                            }
                        }
                    }) { Text(stringResource(R.string.files_ext_changed_reload)) }
                    TextButton(onClick = {
                        pendingExternalChange = null
                        saveAsLauncher.launch(file.name)
                    }) { Text(stringResource(R.string.files_ext_changed_saveas)) }
                    TextButton(onClick = { pendingExternalChange = null }) {
                        Text(stringResource(R.string.files_cancel))
                    }
                }
            },
        )
    }
}

/**
 * Sora 编辑器嵌入（§6.4：AndroidView）。工厂异常不向外抛——返回空占位 View 并通知
 * 调用方降级 BasicTextField，保证文件一定能打开。
 */
@Composable
private fun SoraEditorView(
    initialText: String,
    highlightLanguage: String?,
    editable: Boolean,
    onEditorReady: (CodeEditor) -> Unit,
    onFactoryFailed: () -> Unit,
) {
    AndroidView(
        factory = { ctx ->
            try {
                CodeEditor(ctx).apply {
                    typefaceText = Typeface.MONOSPACE
                    isLineNumberEnabled = true
                    isWordwrap = false
                    isEditable = editable
                    colorScheme = EditorColorScheme()
                    setEditorLanguage(
                        if (highlightLanguage != null) RulesLanguage(highlightLanguage) else EmptyLanguage(),
                    )
                    setText(initialText)
                    onEditorReady(this)
                }
            } catch (t: Throwable) {
                onFactoryFailed()
                View(ctx)
            }
        },
        update = { view ->
            (view as? CodeEditor)?.isEditable = editable
        },
        onRelease = { view ->
            (view as? CodeEditor)?.release()
        },
    )
}

/** 保存时允许写 BOM 的字符集（GBK/GB18030 无 BOM）。 */
private val BOM_CAPABLE = setOf(
    TextEncoding.TextCharset.UTF_8,
    TextEncoding.TextCharset.UTF_16LE,
    TextEncoding.TextCharset.UTF_16BE,
)
