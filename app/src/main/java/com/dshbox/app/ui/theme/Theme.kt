package com.dshbox.app.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Theme preference with lightweight SharedPreferences persistence.
 */
object AppThemeState {
    private const val PREFS_NAME = "app_settings"
    private const val KEY_THEME_MODE = "theme_mode"

    var mode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mode = runCatching {
            ThemeMode.valueOf(prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
        }.getOrDefault(ThemeMode.SYSTEM)
    }

    fun setMode(context: Context, newMode: ThemeMode) {
        mode = newMode
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, newMode.name)
            .apply()
    }
}

private val LightColors = lightColorScheme(
    primary = Accent,
    // Accent-filled CTAs use white text per UI spec (7.8②).
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
    // Accent-tinted containers: selected tab indicator, chips, etc. must not
    // fall back to the Material3 baseline purple (spec: accent for selection).
    secondaryContainer = LightAccentContainer,
    onSecondaryContainer = LightAccentContainerText,
    error = Error,
)

private val DarkColors = darkColorScheme(
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
    error = Error,
)

/** Shape tokens per UI spec: S=6, M=10, L=14, Dialog=16. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun DshAppTheme(
    content: @Composable () -> Unit,
) {
    val darkTheme = when (AppThemeState.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        shapes = AppShapes,
        content = content,
    )
}
