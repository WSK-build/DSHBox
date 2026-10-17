package com.dshbox.pluginmanager.market.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.market.data.CatalogSource
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * 插件目录数据源选择页，展示各源信息并通过 onPick 通知选择。
 * 标题、正文和按钮采用主题字号；卡片与仓库地址分别使用 surface 和 surfaceVariant。
 * 源标识与仓库地址使用等宽字体，选中状态使用主题主色描边。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CatalogSourceScreen(
    current: CatalogSource,
    onPick: (CatalogSource) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val openFailed = stringResource(R.string.pm_market_source_open_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.pm_market_source_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.pm_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.pm_market_source_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            CatalogSource.entries.forEach { source ->
                CatalogSourceCard(
                    source = source,
                    selected = source == current,
                    onPick = { onPick(source) },
                    onOpenRepository = {
                        openRepository(context, source.repoUrl) {
                            // 无应用可处理仓库链接时显示打开失败提示。
                            scope.launch { snackbarHostState.showSnackbar(openFailed) }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Text(
                text = stringResource(R.string.pm_market_source_foot),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun CatalogSourceCard(
    source: CatalogSource,
    selected: Boolean,
    onPick: () -> Unit,
    onOpenRepository: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // 所有卡片保留边界，选中时使用主题主色描边。
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.id,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = source.owner,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                if (selected) {
                    // 当前数据源显示状态标签，不提供重复选择按钮。
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.pm_market_source_current),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onPick,
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier.height(40.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.pm_market_source_pick),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
                    }
                }
            }

            // 仓库地址：整行可点，跳到系统浏览器。
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenRepository),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = source.repoUrl.removePrefix("https://"),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_external_link),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            Text(
                text = stringResource(sourceDescription(source)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 通过穷尽枚举映射取得数据源说明资源。 */
@StringRes
private fun sourceDescription(source: CatalogSource): Int = when (source) {
    CatalogSource.MOBILE -> R.string.pm_market_source_mobile_desc
    CatalogSource.OFFICIAL -> R.string.pm_market_source_official_desc
}

/**
 * 用系统浏览器打开仓库页。
 *
 * 目标仅取自 [CatalogSource.repoUrl] 常量，不使用外部目录数据提供的跳转地址。
 * 无法启动处理应用时由调用方显示失败提示。
 */
private fun openRepository(context: Context, url: String, onFailure: () -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { onFailure() }
}
