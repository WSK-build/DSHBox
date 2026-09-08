package com.dshbox.app.ui.files.viewer

import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.dshbox.app.R
import com.dshbox.app.ui.files.SegmentedSwitch
import com.dshbox.app.util.viewer.ContentFingerprint
import com.dshbox.app.util.viewer.LargeTextLoader
import com.dshbox.app.util.viewer.TextEncoding
import io.noties.markwon.Markwon
import java.io.ByteArrayInputStream
import java.io.File

/** Markdown 预览字符上限：Markwon 同步解析超大文档会长时间阻塞主线程，超限截断（已知限制）。 */
private const val MARKDOWN_PREVIEW_MAX_CHARS = 512_000

/** WebViewAssetLoader 虚拟域（§6.7：不用 file://，与 DSH 的 127.0.0.1 WebView 完全隔离）。 */
private const val WEB_VIRTUAL_DOMAIN = "appassets.androidplatform.net"

/** 预览类型（由外壳按扩展名决定：md/markdown→MD；html/htm→HTML；svg→SVG）。 */
internal enum class MarkupKind { MD, HTML, SVG }

/**
 * 标记语言查看（1.2.0 §6.7）。
 *
 * - 「预览/编辑」分段切换：编辑复用既有 TextCodeViewer（共享外壳 TextEditController，
 *   未保存拦截/保存链路原样生效）；「编辑」标签走外壳 requestEdit 门禁链（大文件/风险层
 *   强确认），不绕过任何门禁；编辑中强制编辑视图，门禁被取消则回退预览；
 * - **编辑器常驻组合**（预览层覆盖其上）：切换到预览不丢弃草稿与 textProvider，
 *   预览/返回编辑往返与 Tab 切换同语义（§6.4.5 草稿策略）；预览层带主题背景遮挡编辑器；
 * - Markdown 预览：Markwon core（Apache-2.0）原生 Spannable 渲染，内容为当前编辑态
 *   （含未保存修改）；
 * - HTML/SVG 预览：WebView 离线渲染，内容在 WebView 创建时于主线程快照
 *   （Sora Content 非线程安全，不在拦截线程读编辑器），**安全基线逐条落实（§6.7）**：
 *   ① allowFileAccess / allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs 全 false；
 *   ② setBlockNetworkLoads(true) 禁网（离线文档不外传、不加载远程资源）；
 *   ③ 无 addJavascriptInterface 且 JS 关闭（比基线更严：静态渲染不需要脚本）；
 *   ④ WebViewAssetLoader 虚拟域 https://appassets.androidplatform.net/ 承载内容，不用 file://；
 *   ⑤ 与 DSH 的 127.0.0.1 WebView 完全隔离（独立实例、独立虚拟域、外链导航一律拦截）；
 *   ⑥ 退出即销毁（离开组合 stopLoading + destroy，M2 decoder 教训同标准）；
 * - 大文件（>2MB 分块）无法预览（需完整文本），提示后仍可查看/编辑源文。
 */
@Composable
internal fun MarkupViewer(
    file: File,
    fullBytes: ByteArray?,
    detection: TextEncoding.Detection,
    fingerprint: ContentFingerprint?,
    plan: LargeTextLoader.Plan,
    previewKind: MarkupKind,
    editing: Boolean,
    controller: TextEditController,
    onRequestEdit: () -> Unit,
    onRequestRefresh: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPreview by remember(file, fullBytes != null) { mutableStateOf(fullBytes != null) }
    val showEditor = editing || !showPreview

    // 门禁被取消（editing 回落 false）时不残留只读的「编辑」标签态
    LaunchedEffect(editing) {
        if (!editing && !showPreview) showPreview = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!editing) {
            SegmentedSwitch(
                selected = if (showPreview) 0 else 1,
                options = listOf(
                    stringResource(R.string.files_markup_preview),
                    stringResource(R.string.files_markup_edit),
                ),
                onSelect = { index ->
                    when (index) {
                        0 -> showPreview = true
                        else -> {
                            showPreview = false
                            // 编辑走外壳门禁链（大文件确认/风险层强确认），通过后 editing=true
                            onRequestEdit()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            // 编辑器常驻组合（草稿与 textProvider 不因切预览而丢）
            TextCodeViewer(
                file = file,
                fullBytes = fullBytes,
                detection = detection,
                fingerprint = fingerprint,
                plan = plan,
                highlightLanguage = null,
                editing = editing,
                controller = controller,
                onRequestRefresh = onRequestRefresh,
                onToast = onToast,
                modifier = Modifier.fillMaxSize(),
            )
            if (!showEditor) {
                val text = currentPreviewText(controller, fullBytes, detection)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    when {
                        text == null -> Text(
                            text = stringResource(R.string.files_markup_preview_too_big),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                        previewKind == MarkupKind.MD -> MarkdownPreview(text, Modifier.fillMaxSize())
                        else -> WebPreview(
                            file = file,
                            kind = previewKind,
                            contentProvider = { controller.textProvider?.invoke() ?: text },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 预览文本：**
 * - fullBytes 缺失（>2MB 大文件分块路径）→ 返回 null → UI 显示「文件过大，无法预览」；
 *   （返工：此前先取 textProvider，编辑器常驻组合时 provider 返回空串 → 预览空白无提示）
 * - 有完整文本 → 优先当前编辑文本（textProvider），否则按探测解码已保存内容。
 */
private fun currentPreviewText(
    controller: TextEditController,
    fullBytes: ByteArray?,
    detection: TextEncoding.Detection,
): String? {
    if (fullBytes == null) return null
    controller.textProvider?.let { return it() }
    return TextEncoding.decodeChecked(fullBytes, detection).text
}

/** Markdown 预览（Markwon core + ext-tables → Spannable → TextView，自带滚动；Spanned 按内容缓存）。
 *  返工 #3：① 表格经 `io.noties.markwon:ext-tables` GFM 扩展渲染（Markwon core 不含表格）；
 *  ② movementMethod 用 LinkMovementMethod——链接可点（URLSpan 默认经系统浏览器打开，
 *  与 HTML 外链口径一致）。数学公式/LaTeX 不在 Markwon core 范围，列 1.2.x 评估。 */
@Composable
private fun MarkdownPreview(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
            .build()
    }
    val display = if (text.length > MARKDOWN_PREVIEW_MAX_CHARS) {
        text.take(MARKDOWN_PREVIEW_MAX_CHARS) + "\n\n…（内容过长，预览已截断）"
    } else {
        text
    }
    val spanned = remember(markwon, display) { markwon.toMarkdown(display) }
    val onSurface = MaterialTheme.colorScheme.onSurface
    AndroidView(
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                movementMethod = android.text.method.LinkMovementMethod()
                textSize = 15f
            }
        },
        update = { view ->
            view.setTextColor(onSurface.toArgb())
            markwon.setParsedMarkdown(view, spanned)
        },
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * HTML/SVG 离线渲染（WebView + WebViewAssetLoader 虚拟域）。内容在 WebView 创建时
 * 主线程快照（[contentProvider] 只在 factory 调用一次）；实例随预览进入/离开组合
 * 创建/销毁，重新进入即拿到最新内容。
 *
 * 返工 #3：PathHandler 按请求路径解析**同目录真实文件**（`<img src="x.png">` 等相对
 * 资源可渲染，扩展名 → MIME 映射；越界/不存在回退主页快照）；
 * 返工 #4：虚拟域内导航放行；http/https 外链用系统浏览器打开（用户显式点击才触发，
 * 且仅把 URL 交给系统，本视图不加载、仍禁网）；其余 scheme 一律拦截。
 */
@Composable
private fun WebPreview(
    file: File,
    kind: MarkupKind,
    contentProvider: () -> String,
    modifier: Modifier = Modifier,
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        factory = { ctx ->
            val snapshot = contentProvider() // 主线程快照（不在 WebView 拦截线程读编辑器）
            val mainMime = if (kind == MarkupKind.SVG) "image/svg+xml" else "text/html"
            val assetLoader = WebViewAssetLoader.Builder()
                .setDomain(WEB_VIRTUAL_DOMAIN)
                .addPathHandler("/", WebViewAssetLoader.PathHandler { path ->
                    // #3：相对资源（同目录）解析——仅限 file 同一目录的真实文件，杜绝越界
                    val name = path.substringAfterLast('/')
                    val asset = if (name.isNotEmpty() && name != file.name &&
                        name != "." && name != ".." && !name.contains('/')
                    ) {
                        val candidate = File(file.parentFile, name)
                        if (candidate.isFile) candidate else null
                    } else {
                        null
                    }
                    val assetMime = asset?.let { mimeForAsset(name) }
                    if (asset != null && assetMime != null) {
                        // P1（2026-09-07 审查）：MIME 白名单即**准入**——非白名单扩展名的
                        // 同目录文件不得经 HTML 引用读出（含 .dsh 凭据/id_rsa/config.json 等），
                        // 白名单未命中回退主文档，绝不以 octet-stream 放行任意文件
                        WebResourceResponse(
                            assetMime,
                            null,
                            ByteArrayInputStream(asset.readBytes()),
                        )
                    } else {
                        WebResourceResponse(mainMime, "utf-8", ByteArrayInputStream(snapshot.toByteArray(Charsets.UTF_8)))
                    }
                })
                .build()
            WebView(ctx).apply {
                // ---- §6.7 安全基线（逐条对应计划原文） ----
                settings.javaScriptEnabled = false // ③ 无 bridge、无脚本执行面（比基线更严）
                settings.allowFileAccess = false // ①
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false // ①
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false // ①
                settings.allowContentAccess = false
                settings.setBlockNetworkLoads(true) // ② 禁网：离线文档不外传、不加载远程资源
                settings.domStorageEnabled = false
                settings.mediaPlaybackRequiresUserGesture = true
                setBackgroundColor(android.graphics.Color.WHITE)
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest,
                    ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        // 虚拟域内导航放行；外链一律不在本视图加载
                        if (request.url.host == WEB_VIRTUAL_DOMAIN) return false
                        // #4：http/https 外链交给系统浏览器（用户显式点击；视图本身仍禁网）
                        if (request.url.scheme == "http" || request.url.scheme == "https") {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            }
                            return true
                        }
                        // P4（2026-09-07 审查）：非 http(s) 外链（tel:/mailto:/intent: 等）
                        // 静默拦截曾让用户以为链接坏了——给明确提示
                        android.widget.Toast.makeText(
                            ctx,
                            R.string.files_markup_link_blocked,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                        return true
                    }
                }
                // ③ 无 addJavascriptInterface
                webViewRef = this
                // 返工 D：文件名须路径编码——空格/#/?/中文未编码时 URL 解析异常致预览空白
                //（用 Uri.encode 而非 URLEncoder：后者把空格编成 +，属表单语义非路径语义）
                loadUrl("https://$WEB_VIRTUAL_DOMAIN/dshbox-doc/${android.net.Uri.encode(file.name)}")
            }
        },
        modifier = modifier.fillMaxSize(),
    )

    // ⑥ 退出即销毁（M2 decoder 教训同标准）
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.destroy()
                }
            }
            webViewRef = null
        }
    }
}

/** 同目录资源扩展名 → MIME（#3；未命中返回 null 落 octet-stream）。 */
private fun mimeForAsset(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "bmp" -> "image/bmp"
    "ico" -> "image/x-icon"
    "css" -> "text/css"
    "js", "mjs" -> "text/javascript"
    "json" -> "application/json"
    "html", "htm" -> "text/html"
    "xml" -> "text/xml"
    "txt", "log", "md", "yaml", "yml" -> "text/plain"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    else -> null
}
