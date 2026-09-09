package com.dshbox.app.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.R
import com.dshbox.app.ui.theme.AppThemeState
import com.dshbox.app.ui.theme.DarkAccentContainer
import com.dshbox.app.ui.theme.DarkBackground
import com.dshbox.app.ui.theme.DarkBorder
import com.dshbox.app.ui.theme.DarkSurface
import com.dshbox.app.ui.theme.DarkTextPrimary
import com.dshbox.app.ui.theme.DarkTextSecondary
import com.dshbox.app.ui.theme.DarkTextTertiary
import com.dshbox.app.ui.theme.ThemeMode
import com.dshbox.app.util.Layer
import java.io.File

// --- 共享设计 token（供 FilesScreen / FolderPickerScreen 等文件页组件复用） ---
internal val PrimaryGreen = Color(0xFF10A37F)
internal val DangerRed = Color(0xFFDC2626)

/** 文件页是否处于深色：与应用主题开关（浅色/深色/跟随系统）联动。 */
@Composable
internal fun filesUseDarkTheme(): Boolean = when (AppThemeState.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

// 深色模式下自动切换到应用深色调色板（浅色值保持历史基线不变）。
@Composable
internal fun PageBg(): Color = if (filesUseDarkTheme()) DarkBackground else Color(0xFFFFFFFF)
@Composable
internal fun CardBg(): Color = if (filesUseDarkTheme()) DarkSurface else Color(0xFFF8FAF9)
@Composable
internal fun LightGreenCard(): Color = if (filesUseDarkTheme()) DarkAccentContainer else Color(0xFFF2F9F6)
@Composable
internal fun SelectedRowBg(): Color = if (filesUseDarkTheme()) DarkAccentContainer else Color(0xFFEAF8F4)
@Composable
internal fun TextPrimary(): Color = if (filesUseDarkTheme()) DarkTextPrimary else Color(0xFF1F2937)
@Composable
internal fun TextSecondary(): Color = if (filesUseDarkTheme()) DarkTextSecondary else Color(0xFF6B7280)
@Composable
internal fun TextHint(): Color = if (filesUseDarkTheme()) DarkTextTertiary else Color(0xFF9CA3AF)
@Composable
internal fun DividerColor(): Color = if (filesUseDarkTheme()) DarkBorder else Color(0xFFF3F4F6)
@Composable
internal fun ControlBg(): Color = if (filesUseDarkTheme()) DarkSurface else Color(0xFFF3F4F6)
@Composable
internal fun CardShadow(): Color = if (filesUseDarkTheme()) Color(0x33000000) else Color(0x0A000000)

/** 顶部胶囊分段切换（沙盒 / 工作区视图选择）。1.2.0 §4.3 由 FilesScreen 迁出提权。 */
@Composable
internal fun SegmentedSwitch(
    selected: Int,
    options: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ControlBg())
            .padding(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSelected) PrimaryGreen else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Color.White else TextSecondary(),
                )
            }
        }
    }
}

/**
 * 风险弹窗文案选择（1.2.0 §4.2，纯函数供 JVM 单测）。
 * 判定必须与入口筛选同源（都基于 [Layer]）——复查修正：此前移动文案用 entry.risk
 * （名称口径），层内文件命中 NORMAL 落到错误兜底，node/dsh 层文件显示 DSH 数据文案。
 */
internal fun riskDialogTextRes(layer: Layer, isMoveAction: Boolean, sandboxRunning: Boolean): Int = when {
    isMoveAction && layer == Layer.DSH_DATA -> R.string.files_risk_move_dsh
    isMoveAction && (layer == Layer.NODE || layer == Layer.DSH) ->
        if (sandboxRunning) R.string.files_risk_move_layer_running else R.string.files_risk_move_layer
    // 移动入口已在源侧拒绝系统目录，此处仅为兜底
    isMoveAction -> R.string.files_risk_system
    layer == Layer.NODE || layer == Layer.DSH ->
        if (sandboxRunning) R.string.files_risk_layer_running else R.string.files_risk_layer
    layer == Layer.SYSTEM_DIR -> R.string.files_risk_system
    // 复查第八轮修正：DSH_DATA 用 §4.2 定稿措辞的文件向文案（旧 key 把文件称作
    // 「目录」且措辞弱于定稿），delete/rename 与 move 同一信息强度
    layer == Layer.DSH_DATA -> R.string.files_risk_dsh_data
    else -> R.string.files_risk_generic
}

/** 面包屑导航：root → currentDir 逐级可点击。1.2.0 §4.3 由 FilesScreen 迁出提权。 */
@Composable
internal fun Breadcrumb(
    root: File,
    rootLabel: String,
    currentDir: File,
    onNavigate: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var cursor: File? = currentDir
        val crumbs = mutableListOf<Pair<String, File>>()
        while (cursor != null && cursor.absolutePath.startsWith(root.absolutePath)) {
            if (cursor == root) break
            crumbs.add(0, cursor.name to cursor)
            cursor = cursor.parentFile
        }
        val segments = buildList {
            add(rootLabel to root)
            addAll(crumbs)
        }
        segments.forEachIndexed { index, (label, target) ->
            if (index > 0) {
                Text(text = " / ", fontSize = 13.sp, color = TextHint())
            }
            Text(
                text = label,
                fontSize = 13.sp,
                color = if (index == segments.lastIndex) TextSecondary() else TextHint(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (index == segments.lastIndex) {
                    Modifier.weight(1f).clickable { onNavigate(target) }
                } else {
                    Modifier.clickable { onNavigate(target) }
                },
            )
        }
    }
}

/**
 * 覆盖页/非活跃 tab 的「组合保留 + 零尺寸不命中」修饰符（自 MainScreen 提权共享，
 * 2026-09-07 返工批次）：覆盖式二级页（FileViewerScreen / FolderPickerScreen）发射在
 * FilesScreen 根 Box 之外、与 MainScreen 各 tab 同级且 zIndex(2f)——若不加本修饰符，
 * 查看器/选择器打开期间切换 tab 会被这块「透明桌布」压住（底部导航永远点不动），
 * 且它在 Files tab 不活跃时仍绘制在其它 tab 之上。沿用 keepAliveHidden：非活跃时
 * 参与测量但零尺寸不放置（不绘制、不命中），tab 切回时组合原样恢复（编辑草稿保留）。
 */
internal fun Modifier.keepAliveHidden(): Modifier = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(0, 0) { /* intentionally not placed */ }
}
