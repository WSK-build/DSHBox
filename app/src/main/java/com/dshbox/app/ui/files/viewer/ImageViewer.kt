package com.dshbox.app.ui.files.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.dshbox.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图片查看（1.2.0 §6.5，零依赖原生实现，不引 Coil）。
 *
 * - 常规图：BitmapFactory `inSampleSize` 按屏幕尺寸降采样 + Compose 手势自绘
 *   （双指缩放/拖动/双击放大，缩放锚定点击点、位移钳制在画面内——返工修正 #10）；
 * - 超长图：[BitmapRegionDecoder] 按屏幕高度的条带懒解码（LazyColumn 只渲染可见条带，
 *   离开组合经 DisposableEffect [BitmapRegionDecoder.recycle] 释放 native 资源——返工修正 #2）；
 * - GIF / 动态 WebP：`ImageDecoder.decodeDrawable` → `AnimatedImageDrawable` 播放
 *   （API 28+，minSdk 29 天然满足）。**仅动图候选才走整图 drawable 解码**（返工修正 #1：
 *   gif 由 AnimatedImageDrawable 流式解码、单帧内存恒定；动态 WebP 以头部 ANMF 块识别）——
 *   静态图一律先过采样预算，杜绝 7000×5000 级静态大图整图解码 OOM；
 * - AVIF / HEIF：解码能力随系统（AVIF 需 API 31+），解码失败落错误态并可外部打开；
 * - 只读不做内建编辑。
 */
@Composable
internal fun ImageViewer(
    file: File,
    modifier: Modifier = Modifier,
) {
    var state by remember(file) { mutableStateOf<ImageUiState>(ImageUiState.Loading) }
    var retryKey by remember(file) { mutableIntStateOf(0) }

    LaunchedEffect(file, retryKey) {
        state = ImageUiState.Loading
        state = withContext(Dispatchers.IO) { decodeImage(file) }
    }

    when (val s = state) {
        ImageUiState.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.files_viewer_loading),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is ImageUiState.Failed -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.files_image_decode_failed),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { retryKey++ }) {
                    Text(stringResource(R.string.files_viewer_retry))
                }
            }
        }
        is ImageUiState.Animated -> Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        setImageDrawable(s.drawable)
                        (s.drawable as? AnimatedImageDrawable)?.start()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        is ImageUiState.LongImage -> {
            // 返工修正 #2：区域解码器持 native 资源，离开组合/切文件时显式 recycle
            DisposableEffect(s.decoder) {
                onDispose { runCatching { s.decoder.recycle() } }
            }
            LazyColumn(modifier = modifier.fillMaxSize().background(Color.Black)) {
                items(s.stripCount) { index ->
                    StripImage(decoder = s.decoder, stripHeight = s.stripHeight, index = index)
                }
            }
        }
        is ImageUiState.Static -> ZoomableImage(bitmap = s.bitmap, modifier = modifier)
    }
}

/** 双指缩放 / 拖动 / 双击放大（Compose 自绘，§6.5；返工修正 #10：位移钳制 + 双击锚定点击点）。 */
@Composable
private fun ZoomableImage(bitmap: Bitmap, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .onSizeChanged { viewport = it }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 8f)
                    val newOffset = if (newScale > 1f) offset + pan else Offset.Zero
                    scale = newScale
                    offset = clampOffset(newOffset, newScale, bitmap, viewport)
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        // 锚定点击点缩放（graphicsLayer transformOrigin=Center）：
                        // 缩放前后点击点下的内容位置不变
                        val newScale = 2.5f
                        val center = Offset(viewport.width / 2f, viewport.height / 2f)
                        val newOffset = tap - center - (tap - offset - center) * (newScale / scale)
                        scale = newScale
                        offset = clampOffset(newOffset, newScale, bitmap, viewport)
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}

/** 长图条带：可见时经 [BitmapRegionDecoder] 解码该条带（IO）。 */
@Composable
private fun StripImage(decoder: BitmapRegionDecoder, stripHeight: Int, index: Int) {
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }.toInt()
    var bitmap by remember(index, screenWidthPx) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(decoder, index, screenWidthPx) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(decoder.width, screenWidthPx) }
                decoder.decodeRegion(Rect(0, index * stripHeight, decoder.width, (index + 1) * stripHeight), opts)
            }.getOrNull()
        }
    }
    val bmp = bitmap
    // 占位高度 = 条带按屏幕宽度等比缩放后的显示高度（px → dp，避免单位混用留大空隙）
    val placeholderHeight = with(density) {
        (stripHeight.toLong() * screenWidthPx / decoder.width.coerceAtLeast(1)).toInt().toDp()
    }
    Box(modifier = Modifier.fillMaxWidth().height(placeholderHeight)) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
    }
}

private sealed interface ImageUiState {
    data object Loading : ImageUiState
    data object Failed : ImageUiState
    data class Static(val bitmap: Bitmap) : ImageUiState
    data class LongImage(val decoder: BitmapRegionDecoder, val stripHeight: Int, val stripCount: Int) : ImageUiState
    data class Animated(val drawable: android.graphics.drawable.Drawable) : ImageUiState
}

/** 2 的幂采样：把 [width] 降到 ≤ [target] 的最大 inSampleSize。 */
private fun sampleSizeFor(width: Int, target: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= target) sample *= 2
    return sample
}

/** 位移钳制：缩放后的内容始终至少部分留在视口内（返工修正 #10①）。 */
private fun clampOffset(offset: Offset, scale: Float, bitmap: Bitmap, viewport: IntSize): Offset {
    if (viewport == IntSize.Zero || scale <= 1f) return Offset.Zero
    val fit = minOf(
        viewport.width / bitmap.width.toFloat(),
        viewport.height / bitmap.height.toFloat(),
    )
    val maxX = ((bitmap.width * fit * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((bitmap.height * fit * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** 全量解码（IO 线程调用）：动图 → 长图 → 降采样静态图三级分流。 */
private fun decodeImage(file: File): ImageUiState {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) {
        return ImageUiState.Failed
    }
    // 返工修正 #1：仅动图候选才尝试整图 drawable 解码——
    // gif 经 AnimatedImageDrawable 流式解码（单帧内存，大小无关）；
    // 动态 WebP 以头部 ANMF 块识别；静态图（含静态 WebP）一律走下方采样/区域路径，
    // 绝不绕过采样预算整图解码（7000×5000 ≈ 140MB 位图）。
    val mime = bounds.outMimeType
    val animatedCandidate = mime == "image/gif" ||
        (mime == "image/webp" && isAnimatedWebp(file))
    if (animatedCandidate) {
        runCatching {
            val source = ImageDecoder.createSource(file)
            val drawable = ImageDecoder.decodeDrawable(source)
            if (drawable is AnimatedImageDrawable) return ImageUiState.Animated(drawable)
        }
    }
    // 超长图（高度 > 8192）：区域解码条带化
    if (height > 8192) {
        runCatching {
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath, false)
            val stripHeight = 4096
            return ImageUiState.LongImage(decoder, stripHeight, (height + stripHeight - 1) / stripHeight)
        }
    }
    // 常规图：降采样到长边 ≤ 2048（≤ 2048×2048×4 ≈ 16MB 位图内存）
    val targetMax = 2048
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(maxOf(width, height), targetMax)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return ImageUiState.Failed
    return ImageUiState.Static(bitmap)
}

/** 动态 WebP 探测：头部含 ANMF 块标记（读前 4KB 足够，VP8X 后紧跟帧块）。 */
private fun isAnimatedWebp(file: File): Boolean = runCatching {
    file.inputStream().buffered().use { ins ->
        val buf = ByteArray(4096)
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        String(buf, 0, off, Charsets.US_ASCII).contains("ANMF")
    }
}.getOrDefault(false)
