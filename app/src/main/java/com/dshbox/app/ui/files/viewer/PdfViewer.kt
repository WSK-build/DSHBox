package com.dshbox.app.ui.files.viewer

import android.graphics.Bitmap
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 单页位图像素上限（§6.6：防 "trying to draw too large bitmap" 崩溃）——4M px ≈ 16MB ARGB_8888。 */
private const val PDF_MAX_PAGE_PIXELS = 2_048 * 2_048

/** 双击放大倍率（按系数重渲当前页，超像素上限时自动压回，实际倍率受上限约束）。 */
private const val PDF_ZOOM_FACTOR = 2.5f

/**
 * PDF 查看（1.2.0 §6.6，原生 PdfRenderer，零 .so 零第三方库）。
 *
 * - 纵向分页列表：每页独立渲染，只渲染进入组合的可见页；离屏（离开组合）经
 *   DisposableEffect 释放位图（与 ImageViewer decoder.recycle 同标准，M2 教训固化）；
 * - 渲染串行化：PdfRenderer 同一时刻只允许一个 openPage，全页共享一把锁；
 * - 缩放：双击按 [PDF_ZOOM_FACTOR] 系数重渲当前页，目标位图先过 [PDF_MAX_PAGE_PIXELS]
 *   像素上限（超限按比例压回）；
 * - 加密 PDF：SecurityException 识别；API 35+ 弹密码输入（PdfRenderer.LoadParams，
 *   独立方法隔离高版本 API），低版本提示不支持并引导外部打开（信息卡出口）；
 * - 渲染失败：错误态 + 重试 + 信息卡兜底（onFallback），任何格式不得让文件打不开（§6.1.4）。
 */
@Composable
internal fun PdfViewer(
    file: File,
    onFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(file) { mutableStateOf<PdfUiState>(PdfUiState.Loading) }
    var retryKey by remember(file) { mutableIntStateOf(0) }
    // 加密 PDF 密码尝试：null = 未输入；非空 = 带 LoadParams 打开（仅 API35+）
    var passwordAttempt by remember(file) { mutableStateOf<String?>(null) }

    LaunchedEffect(file, retryKey, passwordAttempt) {
        state = PdfUiState.Loading
        state = withContext(Dispatchers.IO) { openPdf(file, passwordAttempt) }
    }

    when (val s = state) {
        PdfUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.files_viewer_loading),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is PdfUiState.Failed -> PdfNoticePane(
            message = s.message,
            retryTextRes = R.string.files_viewer_retry,
            onFallback = onFallback,
            modifier = modifier,
        )
        is PdfUiState.Encrypted -> {
            if (Build.VERSION.SDK_INT >= 35) {
                PasswordDialog(
                    wrongPassword = s.wrongPassword,
                    onConfirm = { passwordAttempt = it },
                    onDismiss = onFallback,
                )
                PdfNoticePane(
                    message = stringResource(R.string.files_pdf_encrypted),
                    retryTextRes = R.string.files_pdf_show_info,
                    onFallback = onFallback,
                    modifier = modifier,
                )
            } else {
                // 低版本：提示不支持 + 走外部打开（信息卡出口）
                PdfNoticePane(
                    message = stringResource(R.string.files_pdf_encrypted_unsupported),
                    retryTextRes = R.string.files_pdf_show_info,
                    onFallback = onFallback,
                    modifier = modifier,
                )
            }
        }
        is PdfUiState.Ready -> ReadyPane(s, modifier = modifier)
    }
}

@Composable
private fun ReadyPane(state: PdfUiState.Ready, modifier: Modifier = Modifier) {
    // PdfRenderer 同一时刻仅允许一个 openPage：跨页共享锁（渲染全部在 IO 线程）
    val renderLock = remember(state.renderer) { ReentrantLock() }
    // 各页宽高比（首页比例兜底占位，逐页渲染后回填实际值）
    val aspects = remember(state.renderer) { mutableStateMapOf<Int, Float>() }
    val zooms = remember(state.renderer) { mutableStateMapOf<Int, Float>() }
    var viewportWidth by remember { mutableStateOf(0) }

    // 退出即销毁（§6.7/M2 decoder 教训同标准）：renderer.close() 释放 native 资源
    DisposableEffect(state.renderer) {
        onDispose { runCatching { state.renderer.close() } }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.files_pdf_page_hint, state.pageCount),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF3C3C3C))
                .onSizeChanged { viewportWidth = it.width },
        ) {
            items(state.pageCount) { index ->
                PdfPageItem(
                    renderer = state.renderer,
                    index = index,
                    lock = renderLock,
                    aspect = aspects[index] ?: state.firstAspect,
                    zoom = zooms[index] ?: 1f,
                    viewportWidth = viewportWidth,
                    onAspect = { aspects[index] = it },
                    onToggleZoom = {
                        zooms[index] = if ((zooms[index] ?: 1f) > 1f) 1f else PDF_ZOOM_FACTOR
                    },
                )
            }
        }
    }
}

/** 单页当前位图的唯一持有者：换图/离开组合时确定性 recycle，杜绝双重回收与泄漏。 */
private class PdfBitmapHolder {
    private var current: Bitmap? = null
    private var disposed = false

    @Synchronized
    fun set(bitmap: Bitmap) {
        if (disposed) {
            bitmap.recycle() // dispose 已发生（渲染协程竞态后到）：直接回收
            return
        }
        current?.recycle()
        current = bitmap
    }

    @Synchronized
    fun get(): Bitmap? = current?.takeIf { !it.isRecycled }

    @Synchronized
    fun recycle() {
        disposed = true
        current?.recycle()
        current = null
    }
}

@Composable
private fun PdfPageItem(
    renderer: PdfRenderer,
    index: Int,
    lock: ReentrantLock,
    aspect: Float,
    zoom: Float,
    viewportWidth: Int,
    onAspect: (Float) -> Unit,
    onToggleZoom: () -> Unit,
) {
    val holder = remember(index) { PdfBitmapHolder() }
    // 版本号仅驱动重组：位图本体由 holder 独占管理
    var renderVersion by remember(index) { mutableIntStateOf(0) }
    var pageFailed by remember(index, zoom) { mutableStateOf(false) }

    // 离屏即回收（§6.6）
    DisposableEffect(holder) {
        onDispose { holder.recycle() }
    }

    LaunchedEffect(renderer, index, zoom, viewportWidth) {
        if (viewportWidth <= 0) return@LaunchedEffect
        pageFailed = false
        val result = withContext(Dispatchers.IO) {
            lock.withLock {
                try {
                    renderer.openPage(index).use { page ->
                        val pageW = page.width.toFloat()
                        val pageH = page.height.toFloat()
                        var w = viewportWidth * zoom
                        var h = w * pageH / pageW
                        // 像素上限：超限按比例压回
                        val pixels = w * h
                        if (pixels > PDF_MAX_PAGE_PIXELS) {
                            val scale = kotlin.math.sqrt(PDF_MAX_PAGE_PIXELS / pixels).toFloat()
                            w *= scale
                            h *= scale
                        }
                        val bitmap = Bitmap.createBitmap(
                            w.toInt().coerceAtLeast(1),
                            h.toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        // 返工：PdfRenderer 对未覆盖区域可能保留 alpha——先填白底，否则
                        // 页面空白区透出下方深灰底，观感为「暗色透明阴影」
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } catch (t: Throwable) {
                            bitmap.recycle()
                            throw t
                        }
                        Pair(bitmap, pageW / pageH)
                    }
                } catch (t: Throwable) {
                    null // 单页失败不拖垮整份文档
                }
            }
        }
        val (bitmap, pageAspect) = result ?: run {
            // 协程取消时不置错误态（组合已离开，置位无意义）
            ensureActive()
            pageFailed = true
            return@LaunchedEffect
        }
        // 返工 C：取消路径显式回收——此前注释声称回收，实际 ensureActive() 抛
        // CancellationException 直接跳过 holder.set，位图泄漏（快速滚动大量离屏页累积）
        if (!isActive) {
            bitmap.recycle()
            return@LaunchedEffect
        }
        onAspect(pageAspect)
        holder.set(bitmap) // set 内部回收上一张，双重回收不可能
        renderVersion++
    }

    val bitmap = if (renderVersion >= 0) holder.get() else null
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .aspectRatio(aspect.coerceIn(0.2f, 8f)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(index) {
                        detectTapGestures(onDoubleTap = { onToggleZoom() })
                    },
            )
        } else if (pageFailed) {
            Text(
                text = stringResource(R.string.files_pdf_page_failed),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = stringResource(R.string.files_pdf_page_loading),
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun PdfNoticePane(
    message: String,
    retryTextRes: Int,
    onFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onFallback) { Text(stringResource(retryTextRes)) }
            Text(
                text = stringResource(R.string.files_pdf_external_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun PasswordDialog(
    wrongPassword: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_pdf_encrypted_title)) },
        text = {
            Column {
                if (wrongPassword) {
                    Text(
                        text = stringResource(R.string.files_pdf_password_wrong),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.files_pdf_password_hint)) },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = { onConfirm(password) },
            ) { Text(stringResource(R.string.files_pdf_password_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.files_cancel)) }
        },
    )
}

private sealed interface PdfUiState {
    data object Loading : PdfUiState
    data class Failed(val message: String) : PdfUiState
    data class Encrypted(val wrongPassword: Boolean) : PdfUiState
    data class Ready(val renderer: PdfRenderer, val pageCount: Int, val firstAspect: Float) : PdfUiState
}

/** 打开 PDF（IO 线程）：SecurityException = 加密；其余异常 = 损坏/不可读。 */
private fun openPdf(file: File, password: String?): PdfUiState {
    val pfd = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (_: Exception) {
        return PdfUiState.Failed("无法读取该 PDF 文件（可能已损坏或不可读）")
    }
    return try {
        val renderer = if (password != null && Build.VERSION.SDK_INT >= 35) {
            openWithPassword(pfd, password)
        } else {
            PdfRenderer(pfd)
        }
        val first = try {
            renderer.openPage(0).use { page -> page.width.toFloat() / page.height.toFloat() }
        } catch (_: Exception) {
            1.414f
        }
        PdfUiState.Ready(renderer, renderer.pageCount, first)
    } catch (e: SecurityException) {
        runCatching { pfd.close() }
        PdfUiState.Encrypted(wrongPassword = password != null)
    } catch (t: Throwable) {
        runCatching { pfd.close() }
        if (password != null) {
            // LoadParams 构造声明抛 IOException：密码路径的打开失败按「密码错误」处理
            PdfUiState.Encrypted(wrongPassword = true)
        } else {
            PdfUiState.Failed("PDF 打开失败（文件可能损坏或格式不受支持）")
        }
    }
}

/** API 35+ 密码打开（android.graphics.pdf.LoadParams，framework-pdf 主线模块 since=35）；
 *  独立方法隔离高版本 API 引用（minSdk 29）。 */
@RequiresApi(35)
private fun openWithPassword(pfd: ParcelFileDescriptor, password: String): PdfRenderer =
    PdfRenderer(pfd, LoadParams.Builder().setPassword(password).build())
