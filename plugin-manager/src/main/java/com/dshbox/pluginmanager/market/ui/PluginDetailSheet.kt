package com.dshbox.pluginmanager.market.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.market.model.HostCompatibility
import com.dshbox.pluginmanager.market.model.RegistryPlugin

/**
 * 插件详情弹层。
 *
 * 上游是一个 Modal，内容分四段：byline（作者/版本/下载/星/分类）、描述、
 * 安装命令、以及两条必须让用户在按下安装前看到的风险提示——第三方代码，
 * 和"构建脚本默认被禁止"。这两条不是装饰：它们决定了用户按下按钮时到底
 * 授权了什么。
 *
 * 截图条与评论不做（前者要加载网络图片，后者要连 GitHub Discussions）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginDetailSheet(
    plugin: RegistryPlugin,
    compatibility: HostCompatibility?,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val language = marketLanguage()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val target = installTargetOf(plugin)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (plugin.deprecated) {
                    Spacer(Modifier.width(6.dp))
                    MarketPill(
                        text = stringResource(R.string.pm_market_deprecated_badge),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.pm_market_close))
                }
            }

            MarketPluginSummary(
                plugin = plugin,
                language = language,
                // 详情页给全文，不再截断——用户点进来就是为了看完整描述。
                maxDescriptionLines = Int.MAX_VALUE,
                modifier = Modifier.padding(top = 4.dp),
            )

            // 截图：详情页给一张大图（目录里没有截图的条目就不占位）。
            MarketImage(
                url = plugin.screenshots.firstOrNull(),
                height = 180,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
            )

            CompatBadge(compatibility, modifier = Modifier.padding(top = 10.dp))
            if (compatibility?.status == HostCompatibility.Status.INCOMPATIBLE) {
                Text(
                    text = stringResource(R.string.pm_market_incompatible_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (plugin.deprecated) {
                Text(
                    text = stringResource(R.string.pm_market_deprecated_warn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
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

            CommandBlock(
                label = stringResource(R.string.pm_market_detail_cmd),
                value = target,
                onCopy = { onCopy(target) },
                modifier = Modifier.padding(top = 12.dp),
            )
            if (plugin.url.isNotBlank()) {
                CommandBlock(
                    label = stringResource(R.string.pm_market_detail_source),
                    value = plugin.url,
                    onCopy = { onCopy(plugin.url) },
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Text(
                text = stringResource(R.string.pm_market_detail_third_party),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                text = stringResource(R.string.pm_market_detail_build_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 14.dp),
            ) {
                Spacer(Modifier.weight(1f))
                if (installed) {
                    Text(
                        text = stringResource(R.string.pm_market_installed_badge),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Button(
                        onClick = {
                            onInstall()
                            onDismiss()
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            text = stringResource(
                                if (compatibility?.status == HostCompatibility.Status.INCOMPATIBLE) {
                                    R.string.pm_market_detail_install_anyway
                                } else {
                                    R.string.pm_market_install
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 等宽命令块 + 复制按钮。命令里有 `#path:` 这类符号，等宽字体更好核对。 */
@Composable
private fun CommandBlock(
    label: String,
    value: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                )
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.pm_copy))
                }
            }
        }
    }
}
