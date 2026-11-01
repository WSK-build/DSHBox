package com.dshbox.pluginmanager.market.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 极简图片加载器（本模块四处展示：作者头像、店面图标、封面、详情大图）。
 *
 * 刻意**不引入图片库**：项目对第三方依赖有许可清单约束（`THIRD_PARTY_NOTICES.md`
 * 处于冻结状态），为一个头像加一条依赖不划算。代价是只有内存缓存、没有磁盘缓存
 * 与请求取消——目录里的图都很小，先按这个取舍办。
 *
 * 加载失败（无网、图床不可达、格式不支持）时**什么都不画**：宁可少一张图，
 * 也不要显示破图占位或让卡片塌陷。唯一例外是店面图标——失败回落到作者头像
 * （见 [MarketIconImage]），仍取不到才不画。
 */
private const val MAX_CACHE_ENTRIES = 96

/** 图片请求的默认超时；目录里的图都很小，8 秒已宽裕（店面图标另用更短值）。 */
private const val DEFAULT_IMAGE_TIMEOUT_MS = 8_000

private val imageCache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHE_ENTRIES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
        size > MAX_CACHE_ENTRIES
}

/** 缓存监视器独立于缓存实例：读写共用一把锁，实例不被误暴露时锁语义不受牵连。 */
private val imageCacheLock = Any()

/**
 * 取图结果：[failed] 为 true 表示本次组合内不会再有图——无 URL 也算失败
 * （回落链据此立刻发第二请求），尝试失败亦然。超时按调用点固定，不作为状态键。
 */
private data class RemoteImageState(val bitmap: ImageBitmap?, val failed: Boolean)

@Composable
private fun rememberRemoteImageState(url: String?, timeoutMs: Int = DEFAULT_IMAGE_TIMEOUT_MS): RemoteImageState {
    if (url.isNullOrBlank()) return RemoteImageState(null, failed = true)
    // 缓存读写都过锁：LinkedHashMap 非线程安全（access-order 的 get 也改结构），
    // 组合线程读与加载线程写会竞争。
    var bitmap by remember(url) { mutableStateOf(synchronized(imageCacheLock) { imageCache[url] }) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }
                // 取消由 withContext 边界抛出、不会走到 failed = true；此分支为防御性保留。
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
        }
        if (loaded != null) {
            synchronized(imageCacheLock) { imageCache[url] = loaded }
            bitmap = loaded
        } else {
            failed = true
        }
    }
    return RemoteImageState(bitmap, failed)
}

@Composable
private fun rememberRemoteImage(url: String?, timeoutMs: Int = DEFAULT_IMAGE_TIMEOUT_MS): ImageBitmap? =
    rememberRemoteImageState(url, timeoutMs).bitmap

/** GitHub 头像地址：`owner` 是仓库作者名，GitHub 直接按用户名出图（会 302 到 CDN）。 */
internal fun avatarUrl(owner: String, size: Int = 80): String? =
    owner.trim().takeIf { it.isNotEmpty() }?.let { "https://github.com/$it.png?size=$size" }

/** 圆形作者头像；加载不出来就不占位。 */
@Composable
internal fun MarketAvatar(owner: String, size: Int = 18, modifier: Modifier = Modifier) {
    val bitmap = rememberRemoteImage(avatarUrl(owner, size * 8)) ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
    )
}

/** 图标回落决策（纯函数，供单测钉住四态）：图标明确失败后才发头像请求，其余情况不发。 */
internal fun avatarFallbackUrl(iconPresent: Boolean, iconFailed: Boolean, owner: String, sizeDp: Int): String? =
    if (iconPresent || !iconFailed) null else avatarUrl(owner, sizeDp * 8)

/**
 * 目录策展的店面图标；**加载失败之后**才发起作者头像回落（上游 MarketSection 的
 * onError 语义）：不预取、不在加载期先画头像再闪切成图标。两者都取不到才不占位。
 * 形状恒为圆形，与上游 `.av` 的 `border-radius:50%` 一致——图标与头像同形，
 * 不按网络结果切换。
 */
@Composable
internal fun MarketIconImage(url: String, owner: String, size: Int = 18, modifier: Modifier = Modifier) {
    val icon = rememberRemoteImageState(url, timeoutMs = 4_000)
    val fallbackUrl = avatarFallbackUrl(icon.bitmap != null, icon.failed, owner, size)
    val fallback = fallbackUrl?.let { rememberRemoteImage(it) }
    val bitmap = icon.bitmap ?: fallback
    if (bitmap == null) return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape),
    )
}

/**
 * 封面 / 截图。
 *
 * @param height 固定高度：图未加载完时不留空白，加载完按 [ContentScale.Crop] 填满
 */
@Composable
internal fun MarketImage(
    url: String?,
    height: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberRemoteImage(url)
    if (bitmap == null) return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}
