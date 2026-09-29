package com.dshbox.pluginmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.filled.ArrowBack
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import com.dshbox.pluginmanager.core.AdapterPluginController
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.safety.GuardState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * 插件管理面板页（模块入口）。
 *
 * 版式依次为：DSHBox 专属适配插件、绝对安全模式、DSH官方插件、插件市场，
 * 之后是两个各自独立成条的只读入口（插件加载记录、插件崩溃修复辅助）。
 * 绝对安全模式开启时「插件市场」置灰——该模式下装/卸载第三方插件没有意义；
 * 两个只读入口**不受它影响**：那正是"插件出问题"时最需要看的东西。
 */
@Composable
fun PluginPanelScreen(
    state: GuardState,
    busy: Boolean,
    statusLine: String?,
    onToggleSafety: (Boolean) -> Unit,
    onToggleAbsolute: (Boolean) -> Unit,
    adapter: AdapterPluginController,
    onOpenOfficial: () -> Unit,
    onOpenLoadLog: () -> Unit,
    onOpenRepair: () -> Unit,
    onOpenIsolation: () -> Unit,
    onOpenMarket: () -> Unit,
    onBack: () -> Unit,
) {
    val dimmed = state.absoluteMode
    Column(modifier = Modifier.fillMaxSize()) {
        PanelTopBar(title = stringResource(R.string.pm_panel_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 第一块：DSHBox 专属适配插件（小字标题 + 两个开关）
            AdapterPluginsCard(controller = adapter, dimmed = busy)

            // 第二块：绝对安全模式
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (dimmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.pm_absolute_mode),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.pm_absolute_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.absoluteMode,
                        enabled = !busy,
                        onCheckedChange = onToggleAbsolute,
                        // 与「装配DSH移动端适配包」开关完全同款：打开=品牌绿、关闭=灰白。
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFF10A37F),
                            uncheckedTrackColor = Color(0xFFD5D5D5),
                            checkedThumbColor = Color.White,
                            uncheckedThumbColor = Color(0xFF9E9E9E),
                            checkedBorderColor = Color(0xFF10A37F),
                            uncheckedBorderColor = Color(0xFFBDBDBD),
                            disabledCheckedTrackColor = Color(0x6610A37F),
                            disabledUncheckedTrackColor = Color(0xFFE3E3E3),
                        ),
                    )
                }
            }

            // 第三块：DSH官方插件（上游默认关闭的官方能力开关），紧邻插件市场之上。
            // 与适配卡同理，dimmed 只跟"忙"有关 —— **不受绝对安全模式影响**：
            // 该模式的目的是不加载第三方插件，而这里开关的是上游官方内置能力。
            OfficialPluginsCard(dimmed = busy, onClick = onOpenOfficial)

            // 第四块：插件市场
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .alpha(if (dimmed) DIMMED_ALPHA else 1f)
                    .clickable(enabled = !dimmed && !busy) { onOpenMarket() },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.pm_market_row),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (dimmed) {
                Text(
                    text = stringResource(R.string.pm_absolute_on_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            // 最后一块：插件加载记录（安全模式封存期间只渲染这一行）
            Card(
                // 这一块**不再整卡变灰**：里面既有受绝对安全模式影响的开关，也有不该受影响、
                // 只读的诊断入口（见下）。灰化交给各行的 enabled 自己表达。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column {
                    // 安全模式封存期间不显示开关与隔离清单：见 [PluginSafetySeal]。
                    if (!com.dshbox.pluginmanager.core.PluginSafetySeal.SEALED) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.pm_safety_mode),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.pm_safety_mode_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = state.safetyMode,
                                enabled = !dimmed && !busy,
                                onCheckedChange = onToggleSafety,
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    // 只读诊断入口，**不受绝对安全模式影响**：那正是"插件出问题"时最需要看的东西。
                    // （同一屏里「插件市场」被禁是合理的 —— 该模式下装/卸载第三方插件没有意义。）
                    ThinRow(
                        label = stringResource(R.string.pm_load_log_row),
                        enabled = true,
                        onClick = onOpenLoadLog,
                    )
                    if (!com.dshbox.pluginmanager.core.PluginSafetySeal.SEALED) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        ThinRow(
                            label = stringResource(R.string.pm_isolation_row),
                            enabled = !dimmed,
                            onClick = onOpenIsolation,
                        )
                    }
                }
            }

            // 第五块：插件崩溃修复辅助。与「插件加载记录」**各自独立成条** ——
            // 两者定位不同（一个看日志，一个装工具去修），并进同一张卡会让人以为
            // 它是加载记录里的一个子项。版式与「插件市场」同款（标题 + 右箭头）。
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable(enabled = !busy) { onOpenRepair() },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.pm_repair_row),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_right),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!statusLine.isNullOrBlank()) {
                Text(
                    text = statusLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** 方框内的细横行：左标题 + 右箭头，高度与设置页的行一致。 */
@Composable
private fun ThinRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_chevron_right),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 子页面统一的顶部返回栏。 */
@Composable
fun PanelTopBar(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 本模块是叠在 Tab 之上的整页覆盖（不在 Scaffold 里），而应用开了
                // edge-to-edge：不给状态栏让位，标题与返回箭头会被系统时间/图标压住。
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.pm_back),
                modifier = Modifier
                    .clickable { onBack() }
                    .padding(12.dp)
                    .size(22.dp),
            )
            Text(
                text = title,
                // 与面板内各卡片标题（titleMedium）同号：子页标题曾用 titleLarge，
                // 与"打开前"看到的标题大小不一致，观感突兀。
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** 绝对安全模式开启时，被停用区域的不透明度。 */
const val DIMMED_ALPHA = 0.38f
