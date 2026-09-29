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
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.filled.ArrowBack
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
 * 「数据源」选择页。
 *
 * 两个源各自的目录是**两份独立**的列表，切换就是整份换掉：不降级、不混源。
 *
 * ## 字号为什么这样取（真机反馈返工）
 *
 * 上一版整页偏小。现在按应用自己的字号梯度取：
 * - 页标题 = `titleMedium`(18sp/SemiBold) —— 与 Cordis 插件面板那一页的标题一致；
 * - 源名 = `titleMedium`(18sp)，作者/地址/描述/说明 = `bodyMedium`(14sp)；
 * - 按钮 = `labelLarge`(14sp) + 40dp 高（M3 默认高度，不再压到 32dp）。
 *
 * ## 底色与描边（对齐设计稿）
 *
 * 卡片 = `surface`（白）+ 1dp `outline` 描边（选中时换主色描边）；
 * 卡片里的地址框 = `surfaceVariant`（灰底）+ 1dp 描边。
 * 上一版把两者弄反了（灰卡片里套白框），看着和稿子不是一回事。
 *
 * 源名与仓库地址用等宽字体：它们是标识符，等宽让"这是什么"和"现在选的是哪个"
 * 在视觉上分得开。
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
                            // 设备上没有能处理 http(s) 的应用时说清楚，
                            // 而不是点了之后什么都不发生。
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
        // 未选中也要有一圈描边：没有它，白卡片和页面底色糊在一起，卡片边界就没了。
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
                    // 「当前」是状态不是动作：用容器色标签表示，而不是一个点下去
                    // 什么都不发生的空按钮。
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

/** 每个源的一段介绍；`when` 穷尽枚举，将来加源时编译器会提醒补文案。 */
@StringRes
private fun sourceDescription(source: CatalogSource): Int = when (source) {
    CatalogSource.MOBILE -> R.string.pm_market_source_mobile_desc
    CatalogSource.OFFICIAL -> R.string.pm_market_source_official_desc
}

/**
 * 用系统浏览器打开仓库页。
 *
 * 这是市场模块唯一一处"往外跳"的出口。跳转目标只取 [CatalogSource.repoUrl]
 * —— 我们自己代码里的常量，不是目录里的字段：目录是外部输入，不让它决定
 * 用户会被送到哪里去。
 *
 * 没有应用能处理该意图时**不抛异常给用户看**，交给调用方提示一句。
 */
private fun openRepository(context: Context, url: String, onFailure: () -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure { onFailure() }
}
