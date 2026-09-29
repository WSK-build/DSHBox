package com.dshbox.pluginmanager.market.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
 * 极简图片加载器（只为本模块的三处展示：作者头像、封面、截图条）。
 *
 * 刻意**不引入图片库**：项目对第三方依赖有许可清单约束（`THIRD_PARTY_NOTICES.md`
 * 处于冻结状态），为一个头像加一条依赖不划算。代价是只有内存缓存、没有磁盘缓存
 * 与请求取消——目录里的图都很小，先按这个取舍办。
 *
 * 加载失败（无网、图床不可达、格式不支持）时**什么都不画**：宁可少一张图，
 * 也不要显示破图占位或让卡片塌陷。
 */
private const val MAX_CACHE_ENTRIES = 96

private val imageCache = object : LinkedHashMap<String, ImageBitmap>(MAX_CACHE_ENTRIES, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
        size > MAX_CACHE_ENTRIES
}

@Composable
private fun rememberRemoteImage(url: String?): ImageBitmap? {
    if (url.isNullOrBlank()) return null
    var bitmap by remember(url) { mutableStateOf(imageCache[url]) }
    LaunchedEffect(url) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
            }.getOrNull()
        }
        if (loaded != null) {
            synchronized(imageCache) { imageCache[url] = loaded }
            bitmap = loaded
        }
    }
    return bitmap
}

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
