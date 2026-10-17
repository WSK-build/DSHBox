package com.dshbox.pluginmanager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
// 单卡内两条封存块（安全模式开关 / 失败隔离清单）已整块注释，本导入暂未使用；
// 恢复那两段时同步取消注释。
// import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.Color
import com.dshbox.pluginmanager.core.AdapterPluginController
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
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
) {
    val dimmed = state.absoluteMode
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 与宿主首页壳同一份页面账：顶部 12dp 呼吸、底部 24dp 滚动到底的留白。
                // 水平出血 16dp 由标题块与各卡片自带（适配/官方插件卡在自己文件里也带
                // 同值 16dp），容器不再叠加，避免双层出血。内嵌时上方壳层已让出状态栏、
                // 底部已由 bottomBar 让位，这里不再叠任何系统栏分量。
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 根页文字标题：随内容滚动（固定标题栏移除，PanelTopBar 保留给子页）。
            // 字级与 Pilot 根页同档（titleMedium）；原说明行已移除，字符串保留在资源里备用。
            Text(
                text = stringResource(R.string.pm_root_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            )
            // 第一块：DSHBox 专属适配插件。标题**移到卡外**，套用 Pilot 分组标签的版式
            // （labelLarge + 次要色 + 相对卡片左缘再缩进 4dp）；卡内只留开关行。
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.pm_adapt_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 16.dp),
                )
                AdapterPluginsCard(controller = adapter, dimmed = busy)
            }

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
                        // 与「装配DSH移动端适配包」开关完全同款：打开=品牌绿，
                        // 关闭/禁用态取主题描边与内容色，深浅主题各自成立。
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Color(0xFF10A37F),
                            uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                            checkedThumbColor = Color.White,
                            uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            checkedBorderColor = Color(0xFF10A37F),
                            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                            disabledCheckedTrackColor = Color(0x6610A37F),
                            disabledUncheckedTrackColor =
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
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
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            // 最后一块：插件加载记录（旧「安全模式」开关与失败隔离清单入口已整块注释封存，见卡内）
            Card(
                // 这一块**不再整卡变灰**：里面既有随封存失效的开关，也有不该受影响、
                // 只读的诊断入口（见下）。灰化交给各行的 enabled 自己表达。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column {
                    // 旧「安全模式」开关：已整块注释封存（不删代码，恢复时取消注释即可）。
                    // 封存依据见 PluginSafetySeal——新版 DSH 会把加载失败的条目记为未激活
                    // 并继续启动，「跳过出错条目后重启」不再有触发场景；该模式的独占层
                    // 与启动前的写者锁清理已由下面这张卡之外的通路覆盖。
                    // if (!com.dshbox.pluginmanager.core.PluginSafetySeal.SEALED) {
                    //     Row(
                    //         modifier = Modifier
                    //             .fillMaxWidth()
                    //             .padding(horizontal = 16.dp, vertical = 14.dp),
                    //         verticalAlignment = Alignment.CenterVertically,
                    //     ) {
                    //         Column(Modifier.weight(1f)) {
                    //             Text(
                    //                 text = stringResource(R.string.pm_safety_mode),
                    //                 style = MaterialTheme.typography.titleMedium,
                    //             )
                    //             Text(
                    //                 text = stringResource(R.string.pm_safety_mode_desc),
                    //                 style = MaterialTheme.typography.bodySmall,
                    //                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                    //             )
                    //         }
                    //         Switch(
                    //             checked = state.safetyMode,
                    //             enabled = !dimmed && !busy,
                    //             onCheckedChange = onToggleSafety,
                    //         )
                    //     }
                    //     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // }
                    // 只读诊断入口，**不受安全模式影响**：那正是"插件出问题"时最需要看的东西。
                    // （同一屏里「插件市场」被禁是合理的 —— 该模式下装/卸载第三方插件没有意义。）
                    ThinRow(
                        label = stringResource(R.string.pm_load_log_row),
                        enabled = true,
                        onClick = onOpenLoadLog,
                    )
                    // 失败隔离清单入口与上面同属封存块：整块注释保留，隔离清单子页与
                    // 控制器都还在源码里，恢复时取消注释即可。
                    // if (!com.dshbox.pluginmanager.core.PluginSafetySeal.SEALED) {
                    //     HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    //     ThinRow(
                    //         label = stringResource(R.string.pm_isolation_row),
                    //         enabled = !dimmed,
                    //         onClick = onOpenIsolation,
                    //     )
                    // }
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
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
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

/**
 * 宿主是否已把面板区域提升为整屏层（盖过首页品牌栏/页签行/底部导航）。
 * 由宿主（app 的 Cordis 页签内容）提供；为真时子页顶栏自行让出状态栏、
 * 面板容器自行让出手势条。未提供（独立使用）时保持内嵌语义。
 */
val LocalPluginPanelFullscreen = compositionLocalOf { false }

/** 子页面统一的顶部返回栏。 */
@Composable
fun PanelTopBar(title: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 内嵌于 Cordis 页签时宿主壳层已让出状态栏；子页被提升为整屏层时
                // （LocalPluginPanelFullscreen）壳层不可见，顶栏自行让位。
                .then(
                    if (LocalPluginPanelFullscreen.current) {
                        Modifier.windowInsetsPadding(WindowInsets.statusBars)
                    } else {
                        Modifier
                    },
                )
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
