package com.dshbox.app.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon set is centralized here. Four product tabs follow the final product
 * model: home / folder / terminal / settings.
 */
val AppIconsFolder: ImageVector by lazy {
    materialIcon(name = "Folder") {
        materialPath {
            moveTo(10.0f, 4.0f)
            lineTo(2.0f, 4.0f)
            curveTo(0.9f, 4.0f, 0.01f, 4.9f, 0.01f, 6.0f)
            lineTo(0.0f, 18.0f)
            curveTo(0.0f, 19.1f, 0.9f, 20.0f, 2.0f, 20.0f)
            lineTo(22.0f, 20.0f)
            curveTo(23.1f, 20.0f, 24.0f, 19.1f, 24.0f, 18.0f)
            lineTo(24.0f, 8.0f)
            curveTo(24.0f, 6.9f, 23.1f, 6.0f, 22.0f, 6.0f)
            lineTo(12.0f, 6.0f)
            lineTo(10.0f, 4.0f)
            close()
        }
    }
}

val AppIconsTerminal: ImageVector by lazy {
    materialIcon(name = "Terminal") {
        materialPath {
            moveTo(20.0f, 4.0f)
            lineTo(4.0f, 4.0f)
            curveTo(2.89f, 4.0f, 2.0f, 4.89f, 2.0f, 6.0f)
            lineTo(2.0f, 18.0f)
            curveTo(2.0f, 19.11f, 2.89f, 20.0f, 4.0f, 20.0f)
            lineTo(20.0f, 20.0f)
            curveTo(21.11f, 20.0f, 22.0f, 19.11f, 22.0f, 18.0f)
            lineTo(22.0f, 6.0f)
            curveTo(22.0f, 4.89f, 21.11f, 4.0f, 20.0f, 4.0f)
            close()
            moveTo(10.0f, 15.17f)
            lineTo(8.83f, 16.34f)
            lineTo(5.0f, 12.5f)
            lineTo(8.83f, 8.66f)
            lineTo(10.0f, 9.83f)
            lineTo(7.83f, 12.0f)
            lineTo(10.0f, 15.17f)
            close()
            moveTo(16.0f, 16.0f)
            lineTo(12.0f, 16.0f)
            lineTo(12.0f, 14.0f)
            lineTo(16.0f, 14.0f)
            lineTo(16.0f, 16.0f)
            close()
        }
    }
}

val AppIconsContentCopy: ImageVector by lazy {
    materialIcon(name = "ContentCopy") {
        materialPath {
            moveTo(16.0f, 1.0f)
            lineTo(4.0f, 1.0f)
            curveTo(2.9f, 1.0f, 2.0f, 1.9f, 2.0f, 3.0f)
            lineTo(2.0f, 21.0f)
            lineTo(4.0f, 21.0f)
            lineTo(4.0f, 3.0f)
            lineTo(16.0f, 3.0f)
            lineTo(16.0f, 1.0f)
            close()
            moveTo(19.0f, 5.0f)
            lineTo(8.0f, 5.0f)
            curveTo(6.9f, 5.0f, 6.0f, 5.9f, 6.0f, 7.0f)
            lineTo(6.0f, 21.0f)
            curveTo(6.0f, 22.1f, 6.9f, 23.0f, 8.0f, 23.0f)
            lineTo(19.0f, 23.0f)
            curveTo(20.1f, 23.0f, 21.0f, 22.1f, 21.0f, 21.0f)
            lineTo(21.0f, 7.0f)
            curveTo(21.0f, 5.9f, 20.1f, 5.0f, 19.0f, 5.0f)
            close()
            moveTo(19.0f, 21.0f)
            lineTo(8.0f, 21.0f)
            lineTo(8.0f, 7.0f)
            lineTo(19.0f, 7.0f)
            lineTo(19.0f, 21.0f)
            close()
        }
    }
}

val AppIconsStop: ImageVector by lazy {
    materialIcon(name = "Stop") {
        materialPath {
            moveTo(6.0f, 6.0f)
            horizontalLineTo(18.0f)
            verticalLineTo(18.0f)
            horizontalLineTo(6.0f)
            close()
        }
    }
}

enum class AppIcons(val imageVector: ImageVector) {
    Home(Icons.Filled.Home),
    Files(AppIconsFolder),
    Sandbox(AppIconsTerminal),
    Settings(Icons.Filled.Settings);

    @Composable
    fun Content() = androidx.compose.material3.Icon(
        imageVector = imageVector,
        contentDescription = name,
    )
}
