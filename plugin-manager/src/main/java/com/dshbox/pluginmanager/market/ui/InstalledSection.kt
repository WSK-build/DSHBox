package com.dshbox.pluginmanager.market.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.filled.Close
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.pluginmanager.R
import com.dshbox.pluginmanager.market.model.ActivationState
import com.dshbox.pluginmanager.market.model.InstalledPlugin
import com.dshbox.pluginmanager.market.model.UpdateStatus
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * 「已安装」页签。
 *
 * 行字段与上游一致：包名、版本、安装 spec、我的备注、激活状态行 + 「为什么」
 * 折叠、启停开关、更新、卸载。
 *
 * 三条刻意为之的取舍：
 * 1. **只有可拨的状态才给开关**。`MISSING`（文件都不在）与 `BROKEN`（校验没过）
 *    拨开关没有意义，给了反而误导用户以为拨一下就能修好——所以那两态不渲染
 *    开关，只显示状态徽章。
 * 2. **启停不谎报生效**。数据层只写层、不热重载，所以拨动后如实提示"下次启动
 *    生效"，而不是假装已经生效。
 * 3. **读不出来 ≠ 没有**。已装清单读失败时显示原因 + 重试，不显示"尚未安装
 *    社区插件"（见 [InstalledListState]）。
 */

/** 备注字数上限。与上游 `state.json` 的 200 字限制一致。 */
private const val NOTE_MAX_LENGTH = 200

/**
 * 哪些激活态允许启停。
 *
 * `BROKEN` 也允许：这个状态下插件"装上了但加载不了"，而"停用它"正是最合理的
 * 处置动作（停用后 loader 不会再尝试加载它）。禁用它反而会让用户只能卸载。
 * `LIVE` 不允许是因为它已经生效，改天再说；`MISSING` 没有东西可加载。
 */
private fun ActivationState.isToggleable(): Boolean = when (this) {
    ActivationState.RESTART, ActivationState.DISABLED, ActivationState.INERT,
    ActivationState.BROKEN,
    -> true

    ActivationState.LIVE, ActivationState.MISSING -> false
}

@Composable
internal fun InstalledSection(
    plugins: List<InstalledPlugin>,
    updates: Map<String, UpdateStatus>,
    /** 插件名 → 一次性提示（启停结果 / 失败原因）。 */
    notices: Map<String, String>,
    /** 正在更新中的插件名：按钮换成"更新中…"，让用户看出在忙哪一条。 */
    updatingNames: Set<String>,
    busy: Boolean,
    loading: Boolean,
    /** 更新检测**正在跑**：行内更新按钮要禁用并显示"更新检测中"，否则用户拨一次
     *  开关会看到几十秒的"按钮点了没反应"。纯本地重载（复用上一份更新结果）
     *  时为 false——那时根本没有检测在跑。 */
    checkingUpdates: Boolean,
    /** 已装清单读不出来的原因；null 表示读成功。 */
    loadFailure: String?,
    /** 更新检测失败的原因；null 表示检测成功。 */
    updatesFailure: String?,
    onRetry: () -> Unit,
    onDismissNotice: (InstalledPlugin) -> Unit,
    onSetEnabled: (InstalledPlugin, Boolean) -> Unit,
    onUpdate: (InstalledPlugin) -> Unit,
    onUninstall: (InstalledPlugin) -> Unit,
    onSaveNote: (InstalledPlugin, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = marketLanguage()
    val listState = installedListState(loading = loading, failed = loadFailure != null, count = plugins.size)

    Column(modifier = modifier.fillMaxWidth()) {
        // 读不出来就说读不出来 + 重试。**不能**显示"尚未安装社区插件"：
        // 那是在告诉用户"你的插件没了"，而真实原因是清单读不出来。
        // 横幅只由 loadFailure 驱动（与正文分支无关）：手里还有旧列表时也要说明
        // 这次刷新失败了。
        if (loadFailure != null) {
            MarketFailureBanner(
                title = stringResource(R.string.pm_market_installed_failed_title),
                message = loadFailure,
                onRetry = onRetry,
            )
        }
        // 更新检测失败同样要说出来：否则每一行都会掉进兜底分支，
        // 看起来像"全都已是最新"。
        if (updatesFailure != null) {
            MarketFailureBanner(
                title = stringResource(R.string.pm_market_updates_failed_title),
                message = updatesFailure,
                onRetry = onRetry,
            )
        }

        // 没有数据又读不出来：只显示"读不出来 + 重试"，**不显示**空态。
        if (listState == InstalledListState.FAILED) return@Column
        // 加载中本分区**不再自画进度条**：上方 MarketScreen 的全局加载横幅（进度条 +
        // "正在加载插件目录…"）看的是 `loading`，而 LOADING 只是 `loading` 的一个子集
        // （`loading && 列表为空 && 未失败`），所以这里再画一条必然与它同屏出现两条
        // 进度条（用户实测：发现页一条、已安装页两条）。
        // 这条分区进度条是历史遗留：全局横幅原先还要求 `registry == null &&
        // installed.isEmpty()`，那时"只重载已装列表"没有全局反馈，才需要它兜底
        // （见 MarketScreen 里那段注释）；横幅条件放宽成只看 loading 后它就完全冗余了。
        // 提前返回必须保留：加载中不能落到空态，否则等于把"还没读完"说成"尚未安装"。
        if (listState == InstalledListState.LOADING) return@Column
        if (listState == InstalledListState.EMPTY) {
            MarketEmptyState(
                text = stringResource(R.string.pm_market_installed_empty),
                hint = stringResource(R.string.pm_market_installed_empty_hint),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(plugins, key = { plugin -> registryItemKey(plugin.name) }) { plugin ->
                InstalledPluginCard(
                    plugin = plugin,
                    update = updates[plugin.name],
                    notice = notices[plugin.name],
                    updating = plugin.name in updatingNames,
                    busy = busy,
                    checking = checkingUpdates,
                    language = language,
                    onSetEnabled = { enabled -> onSetEnabled(plugin, enabled) },
                    onUpdate = { onUpdate(plugin) },
                    onUninstall = { onUninstall(plugin) },
                    onSaveNote = { note -> onSaveNote(plugin, note) },
                    onDismissNotice = { onDismissNotice(plugin) },
                )
            }
        }
    }
}

@Composable
private fun InstalledPluginCard(
    plugin: InstalledPlugin,
    update: UpdateStatus?,
    notice: String?,
    updating: Boolean,
    busy: Boolean,
    checking: Boolean,
    language: String,
    onSetEnabled: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onSaveNote: (String) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var whyExpanded by rememberSaveable(plugin.name) { mutableStateOf(false) }
    var noteEditing by rememberSaveable(plugin.name) { mutableStateOf(false) }
    var noteDraft by rememberSaveable(plugin.name) { mutableStateOf(plugin.note.orEmpty()) }
    // 卸载是两步确认：第一次点击只是把这一行切到"确认"态，避免误触直接删掉插件。
    var confirmingUninstall by rememberSaveable(plugin.name) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                plugin.version?.takeIf { it.isNotBlank() }?.let {
                    MarketPill(text = stringResource(R.string.pm_market_version_value, it))
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                ActivationBadge(plugin.activation)
                if (plugin.thirdParty) {
                    MarketPill(text = stringResource(R.string.pm_market_third_party_badge))
                }
                if (isLocalDevelopment(plugin.spec)) {
                    MarketPill(
                        text = stringResource(R.string.pm_market_local_dev_badge),
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        content = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (plugin.deprecated) {
                    MarketPill(
                        text = stringResource(R.string.pm_market_deprecated_badge),
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            plugin.spec?.takeIf { it.isNotBlank() }?.let { spec ->
                Text(
                    text = stringResource(R.string.pm_market_spec_label) + ": " + spec,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // 「为什么」：把 reasons 摊开。reasons 是双语串（`中文 / english`），
            // 逐条择半后用分号拼接——用 ` / ` 拼会让两条理由看起来像一条双语。
            // 「详情」用可点击文本而非按钮：Material3 按钮带 48dp 最小触摸目标，
            // 短文本会被塞进该宽度里居中（视觉上比同列文字右缩进约两个字）。
            // 文本 + clickable 没有这层最小尺寸，左右边界与上方文字严格一致。
            Text(
                text = if (whyExpanded) {
                    stringResource(R.string.pm_market_ops_close)
                } else {
                    stringResource(R.string.pm_market_act_why)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { whyExpanded = !whyExpanded }
                    .padding(vertical = 6.dp),
            )
            if (whyExpanded) {
                if (plugin.reasons.isEmpty()) {
                    Text(
                        text = stringResource(R.string.pm_market_act_why_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = localizeBilingualList(plugin.reasons, language),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            NoteRow(
                plugin = plugin,
                editing = noteEditing,
                draft = noteDraft,
                onDraftChange = { noteDraft = it },
                onToggleEdit = {
                    noteEditing = !noteEditing
                    if (noteEditing) noteDraft = plugin.note.orEmpty()
                },
                onSave = {
                    onSaveNote(noteDraft.take(NOTE_MAX_LENGTH))
                    noteEditing = false
                },
                onClear = {
                    noteDraft = ""
                    onSaveNote("")
                    noteEditing = false
                },
            )

            // 一次性提示：给一个显式的关闭入口。没有它的话"已停用，重启后
            // 生效"会一直挂在这一行上，直到用户碰巧触发下一次操作。
            notice?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .padding(top = 4.dp),
                    )
                    IconButton(onClick = onDismissNotice) {
                        Icon(
                            imageVector = ImageVector.vectorResource(CommonR.drawable.ic_x),
                            contentDescription = stringResource(R.string.pm_market_notice_dismiss),
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                if (plugin.activation.isToggleable()) {
                    Text(
                        text = stringResource(
                            if (plugin.activation == ActivationState.DISABLED) {
                                R.string.pm_market_toggle_off
                            } else {
                                R.string.pm_market_toggle_on
                            },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = plugin.activation != ActivationState.DISABLED,
                        enabled = !busy,
                        onCheckedChange = onSetEnabled,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (confirmingUninstall) {
                    TextButton(onClick = { confirmingUninstall = false }) {
                        Text(stringResource(R.string.pm_market_cancel))
                    }
                    Button(
                        onClick = {
                            confirmingUninstall = false
                            onUninstall()
                        },
                        enabled = !busy,
                    ) {
                        // 第二步的按钮是"确认"，与第一步的"卸载"分开，避免连点两次
                        // 同一个位置的按钮就把插件删了。
                        Text(stringResource(R.string.pm_market_confirm))
                    }
                } else {
                    // 卸载在前、更新在后，两者同一框型（OutlinedButton）。
                    OutlinedButton(onClick = { confirmingUninstall = true }, enabled = !busy) {
                        Text(stringResource(R.string.pm_market_uninstall))
                    }
                    if (update?.updateAvailable == true && !updating && !checking) {
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(onClick = onUpdate, enabled = !busy) {
                            Text(stringResource(R.string.pm_market_update))
                        }
                    }
                }
            }

            if (confirmingUninstall) {
                Text(
                    text = stringResource(R.string.pm_market_uninstall_confirm) + " " +
                        stringResource(R.string.pm_market_uninstall_confirm_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            UpdateRow(update = update, updating = updating, checking = checking)
        }
    }
}

/**
 * 更新行。
 *
 * 五档，**顺序即优先级**：
 * 0. `checking` → 「更新检测中…」，更新按钮禁用（否则用户在检测跑完前点了按钮
 *    也只会卡住，看起来像没反应）；
 * 1. `channelSwitch` 非空 → 只提示，不给更新按钮（切通道不是升级，点了会降级）；
 * 2. 有更新 → 版本号 + 更新按钮；
 * 3. `checked` → 「已是最新」（这是**唯一**能这么说的分支：真的比过了）；
 * 4. `!checked` → 「未检测（原因：…）」。
 *
 * 第 4 档是这一版必须守住的一条：非 npm 来源（`link:` / `file:` / `github:`）
 * 与 npm 查询失败都没有完成比较，绝不能渲染成「已是最新」——那是在替一个
 * 我们没做过的检查下结论。
 */
@Composable
private fun UpdateRow(
    update: UpdateStatus?,
    updating: Boolean,
    checking: Boolean,
) {
    // "更新中"先判：正在更新时即使没有更新状态也要显示，否则这一行会突然空掉。
    if (updating) {
        Text(
            text = stringResource(R.string.pm_market_updating),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    if (checking) {
        Text(
            text = stringResource(R.string.pm_market_update_checking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    if (update == null) return
    val channelSwitch = update.channelSwitch?.takeIf { it.isNotBlank() }
    when {
        channelSwitch != null -> Text(
            text = stringResource(R.string.pm_market_update_channel_switch, channelSwitch),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        update.updateAvailable -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            update.latest?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.pm_market_version_value, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        update.checked -> Text(
            text = stringResource(R.string.pm_market_up_to_date),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        else -> Text(
            text = stringResource(
                R.string.pm_market_update_unchecked,
                stringResource(
                    when (updateCheckGap(update.kind)) {
                        UpdateCheckGap.SOURCE_UNSUPPORTED -> R.string.pm_market_update_unchecked_source
                        UpdateCheckGap.QUERY_FAILED -> R.string.pm_market_update_unchecked_query
                    },
                ),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 备注区：平时一行文字 + 编辑入口；编辑时是限长输入框。 */
@Composable
private fun NoteRow(
    plugin: InstalledPlugin,
    editing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.padding(top = 6.dp)) {
        if (!editing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pm_market_note_mine) + ": " +
                        (plugin.note?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.pm_market_note_add)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleEdit) {
                    Icon(
                        imageVector = ImageVector.vectorResource(CommonR.drawable.ic_pencil),
                        contentDescription = stringResource(R.string.pm_market_note_edit),
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = draft,
                // 直接截断而不是让用户写到 201 字再报错：上限是数据层的硬约束。
                onValueChange = { if (it.length <= NOTE_MAX_LENGTH) onDraftChange(it) },
                placeholder = { Text(stringResource(R.string.pm_market_note_ph)) },
                supportingText = { Text("${draft.length}/$NOTE_MAX_LENGTH") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onSave) { Text(stringResource(R.string.pm_market_note_save)) }
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.pm_market_note_clear))
                }
                TextButton(onClick = onToggleEdit) {
                    Text(stringResource(R.string.pm_market_cancel))
                }
            }
            Text(
                text = stringResource(R.string.pm_market_note_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 激活状态徽章。六态各有自己的文案，颜色按"好 / 待重启 / 中性 / 坏"四档收敛。 */
@Composable
internal fun ActivationBadge(state: ActivationState, modifier: Modifier = Modifier) {
    val label = stringResource(
        when (state) {
            ActivationState.LIVE -> R.string.pm_market_state_live
            ActivationState.RESTART -> R.string.pm_market_state_restart
            ActivationState.INERT -> R.string.pm_market_state_inert
            ActivationState.BROKEN -> R.string.pm_market_state_broken
            ActivationState.MISSING -> R.string.pm_market_state_missing
            ActivationState.DISABLED -> R.string.pm_market_state_disabled
        },
    )
    val container = when (state) {
        ActivationState.LIVE -> MaterialTheme.colorScheme.primaryContainer
        ActivationState.RESTART -> MaterialTheme.colorScheme.tertiaryContainer
        ActivationState.INERT, ActivationState.DISABLED -> MaterialTheme.colorScheme.surface
        ActivationState.BROKEN, ActivationState.MISSING -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (state) {
        ActivationState.LIVE -> MaterialTheme.colorScheme.onPrimaryContainer
        ActivationState.RESTART -> MaterialTheme.colorScheme.onTertiaryContainer
        ActivationState.INERT, ActivationState.DISABLED -> MaterialTheme.colorScheme.onSurfaceVariant
        ActivationState.BROKEN, ActivationState.MISSING -> MaterialTheme.colorScheme.onErrorContainer
    }
    MarketPill(text = label, container = container, content = content, modifier = modifier)
}

/** `link:` / `file:` 装上来的是本地开发件，跟 npm 装的要区分开。 */
private fun isLocalDevelopment(spec: String?): Boolean {
    val value = spec?.trim().orEmpty()
    return value.startsWith("link:") || value.startsWith("file:")
}

