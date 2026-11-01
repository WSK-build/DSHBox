package developer.access.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 开发者模式面板的主题。
 *
 * ## 为什么自建而不复用宿主主题
 *
 * 本模块不能依赖 app 模块（依赖方向不允许成环），拿不到宿主的设计 token。
 * 故此处按宿主现有取值**逐项对齐**一份：配色、强调色、圆角规范保持一致，
 * 面板与全站视觉无缝。
 *
 * 代价是宿主调整 token 时这里需同步——这是「零侵入」换来的必要成本。
 * 改动时请与宿主的 `ui/theme` 对照，不要凭感觉调色。
 */
object DevTokens {

    // 浅色
    val LightBackground = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceSecondary = Color(0xFFF7F7F8)
    val LightSurfaceTertiary = Color(0xFFF0F0F1)
    val LightTextPrimary = Color(0xFF171717)
    val LightTextSecondary = Color(0xFF6B6B6B)
    val LightTextTertiary = Color(0xFF8F8F8F)
    val LightBorder = Color(0xFFE5E5E5)

    // 深色
    val DarkBackground = Color(0xFF212121)
    val DarkSurface = Color(0xFF2F2F2F)
    val DarkSurfaceSecondary = Color(0xFF2A2A2A)
    val DarkSurfaceElevated = Color(0xFF3A3A3A)
    val DarkTextPrimary = Color(0xFFF5F5F5)
    val DarkTextSecondary = Color(0xFFB4B4B4)
    val DarkTextTertiary = Color(0xFF8A8A8A)
    val DarkBorder = Color(0xFF444444)

    // 强调与状态
    val Accent = Color(0xFF10A37F)
    val Success = Color(0xFF10A37F)
    val Warning = Color(0xFFD97706)
    val Error = Color(0xFFDC2626)
    val Info = Color(0xFF2563EB)

    val LightAccentContainer = Color(0xFFD9F0E9)
    val LightAccentContainerText = Color(0xFF0B6B52)
    val DarkAccentContainer = Color(0xFF123B31)
    val DarkAccentContainerText = Color(0xFF7FD9BC)
}

/** 圆角规范：S=6 / M=10 / L=14 / Dialog=16，与宿主一致。 */
val DevShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

private val LightColors = lightColorScheme(
    primary = DevTokens.Accent,
    onPrimary = Color.White,
    secondaryContainer = DevTokens.LightAccentContainer,
    onSecondaryContainer = DevTokens.LightAccentContainerText,
    background = DevTokens.LightBackground,
    onBackground = DevTokens.LightTextPrimary,
    surface = DevTokens.LightSurface,
    onSurface = DevTokens.LightTextPrimary,
    surfaceVariant = DevTokens.LightSurfaceSecondary,
    surfaceContainerLowest = DevTokens.LightBackground,
    surfaceContainerLow = DevTokens.LightSurfaceSecondary,
    surfaceContainer = DevTokens.LightSurfaceSecondary,
    surfaceContainerHigh = DevTokens.LightSurfaceTertiary,
    surfaceContainerHighest = DevTokens.LightSurfaceTertiary,
    onSurfaceVariant = DevTokens.LightTextSecondary,
    outline = DevTokens.LightBorder,
    outlineVariant = DevTokens.LightBorder,
    error = DevTokens.Error,
)

private val DarkColors = darkColorScheme(
    primary = DevTokens.Accent,
    onPrimary = DevTokens.DarkTextPrimary,
    secondaryContainer = DevTokens.DarkAccentContainer,
    onSecondaryContainer = DevTokens.DarkAccentContainerText,
    background = DevTokens.DarkBackground,
    onBackground = DevTokens.DarkTextPrimary,
    surface = DevTokens.DarkSurface,
    onSurface = DevTokens.DarkTextPrimary,
    surfaceVariant = DevTokens.DarkSurfaceSecondary,
    surfaceContainerLowest = DevTokens.DarkBackground,
    surfaceContainerLow = DevTokens.DarkSurfaceSecondary,
    surfaceContainer = DevTokens.DarkSurfaceSecondary,
    surfaceContainerHigh = DevTokens.DarkSurfaceElevated,
    surfaceContainerHighest = DevTokens.DarkSurfaceElevated,
    onSurfaceVariant = DevTokens.DarkTextSecondary,
    outline = DevTokens.DarkBorder,
    outlineVariant = DevTokens.DarkBorder,
    error = DevTokens.Error,
)

/**
 * 面板主题。
 *
 * 跟随系统深浅色。
 *
 * 与助手模块既有做法一致（该模块整体未接入宿主的应用内明暗偏好）；
 * 本面板不引入第二套偏好源，以免与所在界面不同步。
 */
@Composable
fun DeveloperAccessTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = DevShapes,
        content = content,
    )
}
