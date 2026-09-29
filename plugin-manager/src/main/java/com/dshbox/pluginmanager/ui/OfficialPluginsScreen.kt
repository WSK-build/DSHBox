package com.dshbox.pluginmanager.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dshbox.app.sandbox.DshOfficialPlugins
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.core.OfficialPluginController

/**
 * 「DSH官方插件」子页：每个条目一个开关 + 底部一行说明。
 *
 * 条目直接由 [DshOfficialPlugins.ENTRIES] 驱动，标题用**上游 id 原文**（不翻译）：
 * 一是与上游一一对应、排查时一眼看出改的是哪一行，二是省掉每条 6 条六语资源。
 * 描述与说明行仍需六语。
 *
 * 底部说明点明改动落在哪个文件，是刻意的 —— 让用户看到我们写的是自己的开关层，
 * 而不是他的配置文件（profile 的 `cordis.patch.yml` 全程不被触碰）。
 */
@Composable
fun OfficialPluginsScreen(
    controller: OfficialPluginController,
    onBack: () -> Unit,
) {
    val enabled by controller.enabled.collectAsState()
    val busy by controller.busy.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        PanelTopBar(title = stringResource(R.string.pm_official_section), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(Modifier.padding(vertical = 4.dp)) {
                    DshOfficialPlugins.ENTRIES.forEachIndexed { index, entry ->
                        if (index > 0) EntryDivider()
                        ToggleRow(
                            label = entry.id,
                            description = descriptionOf(entry.id)?.let { stringResource(it) },
                            checked = entry.id in enabled,
                            enabled = !busy,
                            onToggle = { controller.setEnabled(entry.id, entry.id !in enabled) },
                        )
                    }
                    // 说明行与条目之间同样有分界线，视觉上与条目并列而不是附着在最后一条上。
                    EntryDivider()
                    Text(
                        text = stringResource(R.string.pm_official_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 条目之间的分界线：左右各留 16dp，与行内容左边界对齐，**不顶到卡片边缘**。
 */
@Composable
private fun EntryDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/**
 * 条目 id → 描述文案。
 *
 * 映射放在界面模块：资源 id 属于本模块，条目表在 `sandbox-manager`，把资源 id 塞进条目表
 * 会让底层模块反向依赖界面资源。未知 id 返回 null（不渲染描述），不猜文案。
 */
@StringRes
private fun descriptionOf(id: String): Int? = when (id) {
    "ui-sidebar-browser" -> R.string.pm_official_desc_ui_sidebar_browser
    else -> null
}
