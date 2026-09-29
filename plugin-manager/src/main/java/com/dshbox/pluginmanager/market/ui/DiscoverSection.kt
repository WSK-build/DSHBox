package com.dshbox.pluginmanager.market.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.CheckCircle
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.HelpOutline
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.market.data.CatalogSource
import com.dshbox.pluginmanager.market.model.HostCompatibility
import com.dshbox.pluginmanager.market.model.RegistryPlugin
import java.util.Locale
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * 「发现」页签：目录浏览。
 *
 * 搜索框由外层（[MarketScreen]）持有，因为它同时也服务于「收藏」页签；本文件
 * 负责分类筛选、排序、宿主筛选、分页与卡片渲染。
 *
 * 卡片字段与上游一致：名称、作者、版本、下载量、星数、描述、分类、宿主兼容
 * 徽章、弃用徽章、安装按钮、已装标记。**截图条不做**——本轮不加载网络图片。
 */

/** 排序字段。上游三档：下载量 / 星标 / 新增。 */
internal enum class MarketSortField { DOWNLOADS, STARS, ADDED }

/** 每页条数，与上游分页下限一致。 */
private const val PAGE_SIZE = 24

@Composable
internal fun DiscoverSection(
    plugins: List<RegistryPlugin>,
    /** 分类键 → 本地化标签。 */
    categoryLabels: Map<String, String>,
    query: String,
    compatibility: Map<String, HostCompatibility>,
    installedNames: Set<String>,
    /** 正在安装中的目录条目名——按钮换成"安装中…"，而不是把整页按钮都置灰。 */
    installingNames: Set<String>,
    busy: Boolean,
    onInstall: (RegistryPlugin) -> Unit,
    onOpenDetail: (RegistryPlugin) -> Unit,
    /** 当前数据源 —— 显示在筛选区第三行的方框里。 */
    catalogSource: CatalogSource,
    /** 打开数据源选择页；切换入口只有这一个（方框本身不响应点击）。 */
    onOpenCatalogSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = marketLanguage()

    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var sortOrdinal by rememberSaveable { mutableStateOf(MarketSortField.DOWNLOADS.ordinal) }
    var ascending by rememberSaveable { mutableStateOf(false) }
    var hostOnly by rememberSaveable { mutableStateOf(false) }
    var visibleCount by rememberSaveable { mutableStateOf(PAGE_SIZE) }

    val sortField = MarketSortField.entries[sortOrdinal]

    // 条件一变就回到第一页，否则用户筛完看到的还是上一轮的"已加载 96 条"。
    LaunchedEffect(query, category, sortOrdinal, ascending, hostOnly) {
        visibleCount = PAGE_SIZE
    }

    val filtered = remember(plugins, query, category, sortField, ascending, hostOnly, compatibility) {
        filterPlugins(plugins, query, category, sortField, ascending, hostOnly, compatibility)
    }
    val shown = filtered.take(visibleCount)

    Column(modifier = modifier) {
        CategoryRow(
            labels = categoryLabels,
            selected = category,
            onSelect = { category = it },
        )
        SortRow(
            sortField = sortField,
            ascending = ascending,
            hostOnly = hostOnly,
            onSortFieldChange = { sortOrdinal = it.ordinal },
            onToggleDirection = { ascending = !ascending },
            onToggleHostOnly = { hostOnly = !hostOnly },
        )
        MarketSourceRow(
            source = catalogSource,
            onOpen = onOpenCatalogSource,
        )
        if (hostOnly) {
            Text(
                text = stringResource(R.string.pm_market_filter_host_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        // 「筛出 / 总数」两个数都取自当前目录，**不是常量**：
        // 前者是筛选后的条数，后者是这份目录（当前数据源）的总条数 —— 换源后自动跟着变。
        // 只显示前者会让人以为"插件少了很多"：任何筛选看起来都像插件丢了。
        Text(
            text = stringResource(R.string.pm_market_result_count, filtered.size, plugins.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        if (filtered.isEmpty()) {
            MarketEmptyState(stringResource(R.string.pm_market_empty))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // key 用显示身份：解析层已按显示身份去重，不会重复；带下标反而会
                // 在列表顺序变化时丢掉整行状态。
                items(
                    shown,
                    key = { plugin -> registryItemKey(plugin.displayName) },
                ) { plugin ->
                    MarketPluginCard(
                        plugin = plugin,
                        categoryLabels = categoryLabels,
                        compatibility = compatibilityFor(plugin, compatibility),
                        installed = isInstalledInRegistry(plugin, installedNames),
                        installing = plugin.displayName in installingNames,
                        busy = busy,
                        language = language,
                        onInstall = { onInstall(plugin) },
                        onOpenDetail = { onOpenDetail(plugin) },
                    )
                }
                if (shown.size < filtered.size) {
                    item {
                        OutlinedButton(
                            onClick = { visibleCount += PAGE_SIZE },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.pm_market_load_more))
                        }
                    }
                }
            }
        }
    }
}

/** 分类 Pill 行：吸顶由外层布局负责，这里只做横向滚动。 */
@Composable
private fun CategoryRow(
    labels: Map<String, String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            // 三行筛选之间的纵向间距：原来是每行 4dp（相邻两行相距 8dp），
            // 加上「数据源」这第三行后收到 3dp（相距 6dp），不让筛选区吃掉列表高度。
            .padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MarketFilterPill(
            selected = selected == null,
            label = stringResource(R.string.pm_market_category_all),
            onClick = { onSelect(null) },
        )
        labels.forEach { (key, label) ->
            MarketFilterPill(
                selected = selected == key,
                label = label,
                onClick = { onSelect(if (selected == key) null else key) },
            )
        }
    }
}

@Composable
private fun SortRow(
    sortField: MarketSortField,
    ascending: Boolean,
    hostOnly: Boolean,
    onSortFieldChange: (MarketSortField) -> Unit,
    onToggleDirection: () -> Unit,
    onToggleHostOnly: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.pm_market_sort_field),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MarketFilterPill(
            selected = sortField == MarketSortField.DOWNLOADS,
            label = stringResource(R.string.pm_market_sort_downloads),
            onClick = { onSortFieldChange(MarketSortField.DOWNLOADS) },
        )
        MarketFilterPill(
            selected = sortField == MarketSortField.STARS,
            label = stringResource(R.string.pm_market_sort_stars),
            onClick = { onSortFieldChange(MarketSortField.STARS) },
        )
        MarketFilterPill(
            selected = sortField == MarketSortField.ADDED,
            label = stringResource(R.string.pm_market_sort_added),
            onClick = { onSortFieldChange(MarketSortField.ADDED) },
        )
        MarketFilterPill(
            selected = ascending,
            label = stringResource(
                if (ascending) R.string.pm_market_sort_asc else R.string.pm_market_sort_desc,
            ),
            onClick = onToggleDirection,
        )
        MarketFilterPill(
            selected = hostOnly,
            label = stringResource(R.string.pm_market_filter_host),
            onClick = onToggleHostOnly,
        )
    }
}

/**
 * 目录卡片。
 *
 * `installed` 为真时安装按钮变成不可点的「✓ 已安装」——上游也是这个语义：装了
 * 就不给重复入口，而不是让按钮点下去报错。
 */
@Composable
internal fun MarketPluginCard(
    plugin: RegistryPlugin,
    categoryLabels: Map<String, String>,
    compatibility: HostCompatibility?,
    installed: Boolean,
    installing: Boolean,
    busy: Boolean,
    language: String,
    onInstall: () -> Unit,
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail),
    ) {
        Column(Modifier.padding(12.dp)) {
            // 有策展截图就先给一张封面：列表里最能说明"这是什么"的就是它。
            // 目录里多数条目没有截图，这时不占位、不留空框。
            MarketImage(
                url = plugin.screenshots.firstOrNull(),
                height = 120,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarketAvatar(
                    owner = plugin.owner,
                    size = 18,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (installed) {
                    Spacer(Modifier.width(6.dp))
                    MarketPill(
                        text = stringResource(R.string.pm_market_installed_badge),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                if (plugin.deprecated) {
                    Spacer(Modifier.width(6.dp))
                    MarketPill(
                        text = stringResource(R.string.pm_market_deprecated_badge),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text(
                    text = stringResource(R.string.pm_market_author_value, plugin.owner),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                plugin.version?.takeIf { it.isNotBlank() }?.let { version ->
                    Text(
                        text = stringResource(R.string.pm_market_version_value, version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // downloads 缺失表示"没有 npm 包"，不是 0——所以整段省略而不是显示 0。
                plugin.downloads?.let { downloads ->
                    Text(
                        text = stringResource(
                            R.string.pm_market_downloads_value,
                            formatCount(downloads),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                plugin.stars?.let { stars ->
                    Text(
                        text = stringResource(R.string.pm_market_stars_value, formatCount(stars)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val description = plugin.descriptionFor(language)
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (plugin.categories.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    plugin.categories.forEach { key ->
                        MarketPill(text = categoryLabels[key] ?: key)
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                CompatBadge(compatibility)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onOpenDetail) {
                    Text(stringResource(R.string.pm_market_detail_title))
                }
                if (installed) {
                    Text(
                        text = stringResource(R.string.pm_market_installed_badge),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                } else if (installing) {
                    Text(
                        text = stringResource(R.string.pm_market_installing),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                } else {
                    Button(onClick = onInstall, enabled = !busy) {
                        Text(stringResource(R.string.pm_market_install))
                    }
                }
            }

            if (plugin.deprecated) {
                Text(
                    text = stringResource(R.string.pm_market_deprecated_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            plugin.replacement?.takeIf { it.isNotBlank() }?.let { replacement ->
                Text(
                    text = stringResource(R.string.pm_market_replacement_hint, replacement),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 详情弹层与安装确认弹窗共用的摘要块：byline + 描述。
 *
 * 两处都要「作者/版本/下载/星/分类 + 描述」，抽出来是为了让用户在按下确认前
 * 看到的东西与详情页逐字一致——上游也是同一份数据渲染两处。
 */
@Composable
internal fun MarketPluginSummary(
    plugin: RegistryPlugin,
    language: String,
    maxDescriptionLines: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // 窄屏上「作者 · 版本 · 下载 · 星」四项一行放不下，让它横向滚动而不是被裁掉。
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.pm_market_author_value, plugin.owner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plugin.version?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.pm_market_version_value, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plugin.downloads?.let {
                Text(
                    text = stringResource(R.string.pm_market_downloads_value, formatCount(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            plugin.stars?.let {
                Text(
                    text = stringResource(R.string.pm_market_stars_value, formatCount(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (plugin.categories.isNotEmpty()) {
            Text(
                text = stringResource(R.string.pm_market_categories_label) +
                    ": " + plugin.categories.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (plugin.added.isNotBlank()) {
            Text(
                text = stringResource(R.string.pm_market_detail_published, plugin.added),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val description = plugin.descriptionFor(language)
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = maxDescriptionLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

// ────────────────────────────── 共享小组件 ──────────────────────────────

/** 徽章的视觉配方。用语义色而不是硬编码色值，浅色/暗色主题都会跟着走。 */
internal data class MarketBadgeStyle(
    val container: Color,
    val content: Color,
    val icon: ImageVector,
    val label: String,
)

/** 带图标的徽章。 */
@Composable
internal fun MarketBadge(style: MarketBadgeStyle, modifier: Modifier = Modifier) {
    Surface(
        color = style.container,
        contentColor = style.content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Icon(
                imageVector = style.icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(text = style.label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 无图标的纯文本标签（分类、已装标记等）。 */
@Composable
internal fun MarketPill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surface,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 「数据源」筛选行：标签 + 当前值方框 + 切换按钮。
 *
 * 方框与上面两行的筛选药丸**同形**（`shapes.small` 6dp 圆角、32dp 高、1dp `outline` 描边），
 * 区别只在于里面装的是"当前值"而不是可点的选项 —— 所以它**不响应点击**，
 * 免得有人以为它是下拉框。切换入口只有右边那个按钮。
 *
 * 源名用等宽字体：它是标识符（仓库名），等宽让它读起来像"值"，
 * 与旁边那些可点标签在视觉上分开。
 */
@Composable
private fun MarketSourceRow(
    source: CatalogSource,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp),
        // 间距收到 6dp、方框内边距收到 8dp、按钮内边距收到 10dp：三者合起来给源名多让出
        // 20dp 左右 —— 等宽字体下 `awesome-dsh-mobile-plugins` 需要约 187dp，
        // 上一版只剩 180dp，于是尾巴被省略号截掉（真机反馈）。
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.pm_market_source_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            shape = MaterialTheme.shapes.small,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = source.id,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        OutlinedButton(
            onClick = onOpen,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Text(
                text = stringResource(R.string.pm_market_source_switch),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 可点的筛选 Pill。 */
@Composable
internal fun MarketFilterPill(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

/**
 * 宿主兼容徽章。
 *
 * 三态必须**一眼可分**，尤其 `unknown` 不能长得像"不兼容"：所以 unknown 用
 * 中性容器色 + 问号图标 + "仍可安装试用"的文案，而 incompatible 用错误容器色
 * + 警告图标。
 */
@Composable
internal fun CompatBadge(compatibility: HostCompatibility?, modifier: Modifier = Modifier) {
    // 取不到宿主要求（多数是"该插件没装、我们拿不到它的清单"）时**不显示徽章**：
    // 每张卡片都挂一句"无法获取"既是噪声，也容易被读成"这插件有问题"。
    // 只有真正有判定依据（有声明、或确证不满足）时才给用户看结论。
    if (compatibility == null) return
    if (compatibility.status == HostCompatibility.Status.UNKNOWN &&
        compatibility.basis == HostCompatibility.Basis.UNAVAILABLE
    ) {
        return
    }
    val style = compatBadgeStyle(compatibility)
    MarketBadge(style = style, modifier = modifier)
}

@Composable
private fun compatBadgeStyle(compatibility: HostCompatibility?): MarketBadgeStyle {
    val requirement = compatibility?.requirement?.takeIf { it.isNotBlank() }
    val base = when (compatibility?.status) {
        HostCompatibility.Status.COMPATIBLE -> MarketBadgeStyle(
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            icon = ImageVector.vectorResource(CommonR.drawable.ic_circle_check),
            label = stringResource(R.string.pm_market_compat_compatible),
        )
        HostCompatibility.Status.INCOMPATIBLE -> MarketBadgeStyle(
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            icon = ImageVector.vectorResource(CommonR.drawable.ic_alert_triangle),
            label = stringResource(R.string.pm_market_compat_incompatible),
        )
        // 未判定 / 取不到：中性色 + 问号。区分 UNDECLARED 与 UNAVAILABLE 只是
        // 为了让用户知道"是插件没说"还是"我们没读到"，两者都不是不兼容。
        else -> {
            val label = when (compatibility?.basis) {
                HostCompatibility.Basis.UNDECLARED -> stringResource(R.string.pm_market_compat_undeclared)
                HostCompatibility.Basis.UNAVAILABLE -> stringResource(R.string.pm_market_compat_unavailable)
                else -> stringResource(R.string.pm_market_compat_unknown)
            }
            MarketBadgeStyle(
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
                icon = ImageVector.vectorResource(CommonR.drawable.ic_help_circle),
                label = label,
            )
        }
    }
    if (requirement == null) return base
    return base.copy(
        label = base.label + " · " + stringResource(R.string.pm_market_compat_requirement, requirement),
    )
}

/** 空态：一行说明，居中。 */
@Composable
internal fun MarketEmptyState(
    text: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ────────────────────────────── 纯函数 ──────────────────────────────

/**
 * 过滤 + 排序。
 *
 * 宿主筛选**只隐藏已确证不兼容的**：未声明与无法确认的仍然显示（上游同样
 * 如此）——把"不知道"当成"不行"会让目录凭空少掉一大半。
 */
private fun filterPlugins(
    plugins: List<RegistryPlugin>,
    query: String,
    category: String?,
    sortField: MarketSortField,
    ascending: Boolean,
    hostOnly: Boolean,
    compatibility: Map<String, HostCompatibility>,
): List<RegistryPlugin> {
    val trimmed = query.trim()
    val matched = plugins.filter { plugin ->
        if (category != null && category !in plugin.categories) return@filter false
        if (hostOnly && compatibilityFor(plugin, compatibility)?.status == HostCompatibility.Status.INCOMPATIBLE) {
            return@filter false
        }
        if (trimmed.isEmpty()) return@filter true
        plugin.matches(trimmed)
    }
    return sortPlugins(matched, sortField, ascending)
}

/**
 * 排序。
 *
 * 缺失值（没有 npm 包 → 没有下载量）一律排在**最后**，升降序都一样：否则降序
 * 会把"没有数据"顶到最前面，看起来像最热门。
 */
private fun sortPlugins(
    plugins: List<RegistryPlugin>,
    field: MarketSortField,
    ascending: Boolean,
): List<RegistryPlugin> {
    val hasValue: (RegistryPlugin) -> Boolean = when (field) {
        MarketSortField.DOWNLOADS -> { plugin -> plugin.downloads != null }
        MarketSortField.STARS -> { plugin -> plugin.stars != null }
        MarketSortField.ADDED -> { plugin -> plugin.added.isNotBlank() }
    }
    val (withValue, without) = plugins.partition(hasValue)
    val comparator = when (field) {
        MarketSortField.DOWNLOADS -> compareBy<RegistryPlugin, Int?>(nullsLast<Int>()) { it.downloads }
        MarketSortField.STARS -> compareBy<RegistryPlugin, Int?>(nullsLast<Int>()) { it.stars }
        MarketSortField.ADDED -> compareBy<RegistryPlugin, String?>(nullsLast<String>()) {
            it.added.ifBlank { null }
        }
    }
    val sorted = withValue.sortedWith(comparator)
    val ordered = if (ascending) sorted else sorted.reversed()
    return ordered + without
}

/** 兼容性查表：目录身份可能用 npm 名，也可能用 `owner/name`，两种都认。 */
internal fun compatibilityFor(
    plugin: RegistryPlugin,
    compatibility: Map<String, HostCompatibility>,
): HostCompatibility? = compatibility[plugin.displayName] ?: compatibility[plugin.name]

/**
 * 目录条目是否已在已装清单里。
 *
 * 目录身份是 npm 名（`displayName`），已装清单读的是 profile manifest 里的包名，
 * 两者对同一插件可能不同名——所以两种都认。
 */
internal fun isInstalledInRegistry(plugin: RegistryPlugin, installedNames: Set<String>): Boolean =
    plugin.displayName in installedNames || plugin.name in installedNames

/**
 * 安装目标。
 *
 * 目录的 `install` 字段是权威来源；缺省时才按 npm 名 → 仓库地址 → 名字回退。
 * 详情页展示的命令与实际传给数据层的目标**必须是同一个**，否则用户核对的
 * 就是另一条命令。
 */
/**
 * 目录条目 → 交给 guest 的安装目标。
 *
 * 目录里的 `install` 字段是**一整条命令**（`dsh plugin --profile web add <目标>`），
 * 不是裸目标——直接把它当目标会被字符白名单拒掉（真机上就踩过）。
 * 所以优先用 `npm` 名；没有 npm 名时从那条命令里取 `add` 之后的**最后一个**词，
 * 那才是真正的目标（`github:owner/repo`、tarball URL 等）。
 */
internal fun installTargetOf(plugin: RegistryPlugin): String {
    plugin.npm?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    val command = plugin.install.trim()
    if (command.isNotEmpty()) {
        val afterAdd = command.substringAfter(" add ", missingDelimiterValue = "")
        val token = (if (afterAdd.isNotEmpty()) afterAdd else command)
            .trim()
            .split(Regex("\\s+"))
            .lastOrNull()
            ?.trim('\'', '"')
        if (!token.isNullOrBlank()) return token
    }
    return plugin.url.ifBlank { plugin.name }
}

/** 名称、作者、描述命中即算匹配。 */
private fun RegistryPlugin.matches(query: String): Boolean {
    val needle = query.lowercase(Locale.ROOT)
    return displayName.lowercase(Locale.ROOT).contains(needle) ||
        owner.lowercase(Locale.ROOT).contains(needle) ||
        name.lowercase(Locale.ROOT).contains(needle) ||
        description.values.any { it.lowercase(Locale.ROOT).contains(needle) }
}

/** 千分位；下载量动辄六位数，不分组读不出来。 */
private fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)
