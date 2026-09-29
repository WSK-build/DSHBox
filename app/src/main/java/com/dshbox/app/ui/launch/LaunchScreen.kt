package com.dshbox.app.ui.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dshbox.app.R
import kotlin.math.min

/**
 * 品牌启动页：自家标记 + 柔影 + 背景暗晕，**无任何文字**。
 *
 * 标记**恒定尺寸**，不做缩放或呼吸——特效是静态的（立体感由标记素材本身承担）。
 * 整屏只保留一次 500ms 淡入，它只改透明度、不改尺寸。
 *
 * 撤销条件是 `showLaunch && dshState != DshState.READY`，展示时长不可控（可能仅一两秒），
 * 因此不能依赖任何"播完才好看"的动画：任何时刻截停都必须是完整画面。
 */
@Composable
fun LaunchScreen(modifier: Modifier = Modifier) {
    val fadeIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fadeIn.animateTo(1f, animationSpec = tween(durationMillis = 500))
    }

    val markSide = markSize()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val vignetteAlpha = if (dark) 0.35f else 0.18f
    // 阴影强度取"剪影本身的深色"，靠模糊自然衰减；不要再乘一个很小的 alpha，
    // 否则柔影会淡到看不见（真机实测 0.20 时几乎无影，与设计稿不符）。
    val shadowAlpha = if (dark) 0.78f else 0.55f
    val density = LocalDensity.current
    val shadowOffset = with(density) { 8.dp.toPx() }
    val shadowBlur = with(density) { 7.dp.toPx() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeIn.value }
            .background(MaterialTheme.colorScheme.background)
            // 背景暗晕：中心透明、边缘压暗，把标记托起来
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = vignetteAlpha)),
                        center = center,
                        radius = size.maxDimension * 0.62f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 柔影：同一张标记整体染黑成剪影，向下轻移后模糊。
        // 承载模糊的图层**必须比标记大一圈**：模糊作用在图层纹理上，图层与内容同尺寸时
        // 越界只能拉边缘像素，阴影会被切平；偏移与模糊半径都取小值，阴影才会贴着标记，
        // 而不是摊成一片。API 31 以下 BlurEffect 不生效（Compose 静默忽略），退化为
        // "下移的半透明剪影"，仍可读。
        Box(
            modifier = Modifier
                .size(markSide * SHADOW_ROOM)
                .graphicsLayer {
                    alpha = shadowAlpha
                    translationY = shadowOffset
                    renderEffect = BlurEffect(shadowBlur, shadowBlur, TileMode.Clamp)
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_splash_mark),
                contentDescription = null,
                colorFilter = ColorFilter.tint(Color.Black, BlendMode.SrcAtop),
                modifier = Modifier.size(markSide),
            )
        }
        // 标记本体：不加运行时光效，立体感来自素材本身。
        Image(
            painter = painterResource(R.drawable.ic_splash_mark),
            contentDescription = null,
            modifier = Modifier.size(markSide),
        )
    }
}

/** 标记边长：屏幕短边 ×36%，夹在 110–150dp。 */
@Composable
private fun markSize(): Dp {
    val cfg = LocalConfiguration.current
    val shortSide = min(cfg.screenWidthDp, cfg.screenHeightDp)
    return (shortSide * 0.36f).dp.coerceIn(110.dp, 150.dp)
}

/** 阴影层相对标记的放大倍数：给模糊留出采样空间（见柔影处注释）。 */
private const val SHADOW_ROOM = 1.35f
