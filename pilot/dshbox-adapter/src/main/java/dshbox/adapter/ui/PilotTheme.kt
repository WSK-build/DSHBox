package dshbox.adapter.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/**
 * 手机助手界面的取值。与宿主应用同值，因为两块界面要在同一次导航里互相进入，
 * 色差会让「换了一个应用」的错觉。
 *
 * 这里是复制而不是引用：依赖方向是宿主应用模块指向本模块，本模块反向 import 会成环，
 * 也会破坏「拔掉本模块宿主照常运行」的可插拔前提。改值时必须与宿主同步改。
 */
private val LightBackground = Color(0xFFFFFFFF)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceSecondary = Color(0xFFF7F7F8)
private val LightSurfaceTertiary = Color(0xFFF0F0F1)
private val LightTextPrimary = Color(0xFF171717)
private val LightTextSecondary = Color(0xFF6B6B6B)
private val LightTextTertiary = Color(0xFF8F8F8F)
private val LightBorder = Color(0xFFE5E5E5)

private val DarkBackground = Color(0xFF212121)
private val DarkSurface = Color(0xFF2F2F2F)
private val DarkSurfaceSecondary = Color(0xFF2A2A2A)
private val DarkSurfaceElevated = Color(0xFF3A3A3A)
private val DarkTextPrimary = Color(0xFFF5F5F5)
private val DarkTextSecondary = Color(0xFFB4B4B4)
private val DarkTextTertiary = Color(0xFF8A8A8A)
private val DarkBorder = Color(0xFF444444)

private val Accent = Color(0xFF10A37F)
private val ErrorRed = Color(0xFFDC2626)
private val LightAccentContainer = Color(0xFFD9F0E9)
private val LightAccentContainerText = Color(0xFF0B6B52)
private val DarkAccentContainer = Color(0xFF123B31)
private val DarkAccentContainerText = Color(0xFF7FD9BC)

/**
 * 三档文字色里最浅的那一档。宿主把它留在配色方案之外，本模块的门控状态与计数要用它，
 * 因此单独经 CompositionLocal 提供，不去占用标准槽位。
 */
data class PilotExtraColors(val tertiaryText: Color)

private val LightExtraColors = PilotExtraColors(LightTextTertiary)
private val DarkExtraColors = PilotExtraColors(DarkTextTertiary)

val LocalPilotExtraColors = staticCompositionLocalOf { LightExtraColors }

private val PilotLightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceSecondary,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainer = LightSurfaceSecondary,
    surfaceContainerHigh = LightSurfaceTertiary,
    outline = LightBorder,
    outlineVariant = LightBorder,
    secondaryContainer = LightAccentContainer,
    onSecondaryContainer = LightAccentContainerText,
    error = ErrorRed,
)

private val PilotDarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = DarkTextPrimary,
    background = DarkBackground,
    onBackground = DarkTextPrimary,
    surface = DarkSurface,
    onSurface = DarkTextPrimary,
    surfaceVariant = DarkSurfaceSecondary,
    onSurfaceVariant = DarkTextSecondary,
    surfaceContainer = DarkSurfaceSecondary,
    surfaceContainerHigh = DarkSurfaceElevated,
    outline = DarkBorder,
    outlineVariant = DarkBorder,
    secondaryContainer = DarkAccentContainer,
    onSecondaryContainer = DarkAccentContainerText,
    error = ErrorRed,
)

/** 圆角档位与宿主同源：S=6、M=10、L=14、对话框=16。 */
private val PilotShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * 本模块界面的主题入口。排版不覆盖，走 Material 3 默认档位，
 * 与宿主一致——宿主那份自建排版从未接进主题。
 *
 * 布局方向固定为从左到右，与宿主同口径；本模块内部一律用起始/结束而非左/右，
 * 将来真要跟随阿拉伯语翻转时只改这一处。
 */
@Composable
fun PilotTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr,
        LocalPilotExtraColors provides if (dark) DarkExtraColors else LightExtraColors,
    ) {
        MaterialTheme(
            colorScheme = if (dark) PilotDarkScheme else PilotLightScheme,
            shapes = PilotShapes,
            content = content,
        )
    }
}
