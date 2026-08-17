package com.dshbox.app.ui.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import kotlin.math.cos
import kotlin.math.sin

/** Brand typeface (Manrope) for the launch title. */
private val BrandFontFamily = FontFamily(Font(R.font.manrope))

/**
 * Brand launch animation: the "DeepSeek Harness" name sits in the center while
 * small DSH whales orbit around it. The sandbox starts silently in the
 * background; this surface is removed automatically once DSH is ready (or the
 * sandbox reports an error), so there is no skip button.
 */
@Composable
fun LaunchScreen(modifier: Modifier = Modifier) {
    // Soft fade-in for the whole surface.
    val fadeIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fadeIn.animateTo(1f, animationSpec = tween(durationMillis = 500))
    }
    val orbitTransition = rememberInfiniteTransition(label = "whale-orbit")

    // One full orbit per 12s; whales counter-rotate so they stay upright.
    val orbitAngle by orbitTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12000, easing = LinearEasing),
        ),
        label = "orbit-angle",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fadeIn.value }
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        // Static center title with the small whales orbiting around it: only
        // the whale track rotates (the title must stay upright).
        Text(
            text = "DeepSeek Harness",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontFamily = BrandFontFamily,
                fontSize = 26.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.5.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer { rotationZ = orbitAngle },
        ) {
            val whaleCount = 6
            val radiusPx = 140.dp.value * 1f
            repeat(whaleCount) { i ->
                val angle = Math.toRadians(i * (360.0 / whaleCount))
                Image(
                    painter = painterResource(R.drawable.dsh_whale),
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(30.dp)
                        .offset(
                            x = (radiusPx * cos(angle)).dp,
                            y = (radiusPx * sin(angle)).dp,
                        )
                        .graphicsLayer { rotationZ = -orbitAngle },
                )
            }
        }
    }
}
