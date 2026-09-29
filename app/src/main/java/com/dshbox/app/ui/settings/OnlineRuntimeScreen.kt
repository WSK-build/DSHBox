package com.dshbox.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.Icons
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.filled.ArrowBack
// 改为 Tabler 描边图标后不再使用：import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.DebianArchiveSource
import com.dshbox.app.common.DebianSources
import com.dshbox.app.common.NodeDistSource
import com.dshbox.app.common.NodeSources
import com.dshbox.app.runtime.OnlineImportState
import com.dshbox.app.runtime.OnlineSourceProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.dshbox.app.common.R as CommonR

/**
 * 「在线导入运行环境包」模块：入口页 + 两个独立子页。
 *
 * - 入口页（[OnlineImportHubScreen]）：两个子项——「在线导入 Linux（精简 Debian）层」
 *   与「在线导入 node 层」，点击分别进入对应界面。
 * - Debian 页（[OnlineDebianImportScreen]）：并行探测全部镜像源（探测含"是否提供
 *   所需内容"判定），按 **hasTarget → 延迟升序 → 其余** 自动排序供用户选择；顶部
 *   常驻「精简版 Debian」说明区（决策 D6）。
 * - node 页（[OnlineNodeImportScreen]）：同款探测与排序；要求 Linux 层已导入。
 *
 * 探测结果不标注地区：全部源一视同仁，可用性与延迟就是唯一排序依据。
 */

/** 源排序：有目标内容的可达源按延迟升序在前，其余可达源按延迟随后，不可达殿后。 */
private fun sortProbes(
    sources: List<Pair<String, OnlineSourceProbe?>>,
): List<String> = sources
    .sortedWith(
        compareByDescending<Pair<String, OnlineSourceProbe?>> {
            it.second?.let { p -> p.reachable && p.hasTarget } == true
        }.thenByDescending { it.second?.reachable == true }
            .thenBy { it.second?.latencyMs ?: Long.MAX_VALUE },
    )
    .map { it.first }

@Composable
fun OnlineImportHubScreen(
    onBack: () -> Unit,
    onOpenDebian: () -> Unit,
    onOpenNode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(ImageVector.vectorResource(CommonR.drawable.ic_arrow_left), contentDescription = stringResource(R.string.dsh_update_back))
            }
            Text(
                text = stringResource(R.string.online_import_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = stringResource(R.string.online_import_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HubEntryCard(
            title = stringResource(R.string.online_hub_debian),
            desc = stringResource(R.string.online_hub_debian_desc),
            onClick = onOpenDebian,
        )
        HubEntryCard(
            title = stringResource(R.string.online_hub_node),
            desc = stringResource(R.string.online_hub_node_desc),
            onClick = onOpenNode,
        )
    }
}

@Composable
private fun HubEntryCard(title: String, desc: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = ImageVector.vectorResource(CommonR.drawable.ic_arrow_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun OnlineDebianImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as DshApp).container
    val manager = container.onlineRuntimeImportManager
    OnlineLayerImportScreen(
        onBack = onBack,
        title = stringResource(R.string.online_hub_debian),
        intro = stringResource(R.string.online_import_explain),
        state = manager.debianState.collectAsState().value,
        onProbe = { cb -> manager.probeDebianSources(cb) },
        sources = DebianSources.ALL,
        urlOf = { it.url },
        nameOf = { it.name.asString(context) },
        noteOf = { it.note.asString(context) },
        onClearResult = manager::clearDebianResult,
        onCancel = manager::cancelDebianImport,
        onStart = { ordered -> manager.startDebianImport(ordered) },
        checkInstalled = manager::isDebianLayerInstalled,
        reinstallMessage = stringResource(R.string.online_reinstall_debian_msg),
        modifier = modifier,
    )
}

@Composable
fun OnlineNodeImportScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as DshApp).container
    val manager = container.onlineRuntimeImportManager
    // 前置防护：Linux 层未导入时本页禁用启动（并常驻提示）——用户无需先点一次才知道。
    // remember：这是文件系统查询，别在每次重组时重查（本页其余查询已统一走 IO）。
    val debianMissing = remember { !manager.isDebianLayerInstalled() }
    OnlineLayerImportScreen(
        onBack = onBack,
        title = stringResource(R.string.online_hub_node),
        intro = stringResource(R.string.online_node_intro),
        state = manager.nodeState.collectAsState().value,
        onProbe = { cb -> manager.probeNodeSources(cb) },
        sources = NodeSources.ALL,
        urlOf = { it.url },
        nameOf = { it.name.asString(context) },
        noteOf = { it.note.asString(context) },
        onClearResult = manager::clearNodeResult,
        onCancel = manager::cancelNodeImport,
        onStart = { ordered -> manager.startNodeImport(ordered) },
        checkInstalled = manager::isNodeLayerInstalled,
        reinstallMessage = stringResource(R.string.online_reinstall_node_msg),
        blockedReason = if (debianMissing) stringResource(R.string.online_err_need_debian) else null,
        modifier = modifier,
    )
}

/** Debian / node 两页共享的探测-选择-导入骨架。 */
@Composable
private fun <S> OnlineLayerImportScreen(
    onBack: () -> Unit,
    title: String,
    intro: String,
    state: OnlineImportState,
    onProbe: suspend ((S, OnlineSourceProbe) -> Unit) -> Unit,
    sources: List<S>,
    urlOf: (S) -> String,
    nameOf: (S) -> String,
    noteOf: (S) -> String,
    onClearResult: () -> Unit,
    onCancel: () -> Unit,
    onStart: (List<S>) -> Unit,
    /** 检测该层当前是否已安装（「安装状态」检测口）。注入以便两页复用同一套骨架。 */
    checkInstalled: suspend () -> Boolean,
    /**
     * 已安装用户点「获取并安装」时的二次确认正文（由调用方按层给：Debian 层 / node 层）。
     *
     * 已安装与未安装走**不同**弹窗：未安装时的通用确认讲的是"会停沙箱、请保持联网"；
     * 而真正装过一次的用户需要知道的是**这次会替换掉现有那一层**——这正是原版
     * 离线导入运行环境包弹窗承担的角色，故两者样式/模板一致，只是正文按层给。
     */
    reinstallMessage: String,
    /** 非空时展示前置条件警示并禁用启动（如 node 页在 Linux 层未导入时）。 */
    blockedReason: String? = null,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    val probes = remember { mutableStateMapOf<String, OnlineSourceProbe>() }
    var probing by remember { mutableStateOf(false) }
    var selectedUrl by remember { mutableStateOf<String?>(null) }
    var confirmStart by remember { mutableStateOf(false) }

    // 安装状态检测口：null = 尚未检完。进入页面查一次；导入开始/结束、用户点行重检时再查。
    // 查询走 IO 线程——isDirectory/isFile 在主线程上做无意义地掉帧。
    var installed by remember { mutableStateOf<Boolean?>(null) }
    var checkTick by remember { mutableStateOf(0) }
    LaunchedEffect(state.running, state.result, checkTick) {
        installed = withContext(Dispatchers.IO) {
            runCatching { checkInstalled() }.getOrDefault(false)
        }
    }
    // 弹窗语义在**点击那一刻定死**：`installed` 是异步查出来的状态，若在弹窗打开后由
    // "检测中(null)" 变成"已安装(true)"，组合期读取会让用户正在读的对话框**自己换文案**。
    var confirmReinstall by remember { mutableStateOf(false) }

    // 该次导入是否刚刚成功。成功后**停在页面上**（不自动返回），把检测口那一行改成
    // "已安装 · 刚刚更新"，让用户确认后再点返回 —— 若成功后直接 onBack()，
    // 用户看不到任何"完成"的痕迹，还会以为被弹回了原页面。
    var justUpdated by remember { mutableStateOf(false) }

    /**
     * 离开本页：**先清结果与成功态再返回**。
     *
     * 残留的 Success 会让下次进入本页时检测口谎报"刚刚更新"，所以离开是唯一且必要的清理点
     * （返回改由用户触发后，清理随之挪到这里）。
     * 声明为 lambda 而非局部函数：BackHandler/返回键在它之前就要引用。
     */
    val leave: () -> Unit = {
        onClearResult()
        justUpdated = false
        onBack()
    }

    // 返回（手势/返回键）一律走 leave()：先清结果与"刚刚更新"标记再退出。
    BackHandler(onBack = { leave() })

    fun startProbe() {
        if (probing || state.running) return
        probing = true
        probes.clear()
        selectedUrl = null
        scope.launch {
            onProbe { source, probe ->
                probes[urlOf(source)] = probe
                // 预选：延迟最低且含所需内容的可达源（不覆盖用户手选）。
                val cur = selectedUrl?.let(probes::get)
                if (probe.reachable && probe.hasTarget &&
                    (cur == null || !cur.hasTarget || probe.latencyMs < cur.latencyMs)
                ) {
                    selectedUrl = urlOf(source)
                }
            }
            probing = false
        }
    }

    LaunchedEffect(Unit) { startProbe() }
    // 成功：**停在本页**并打上"刚刚更新"标记（见 justUpdated 的说明）。
    // 不再自动 onBack()；结果也保留在 state 里，直到用户离开时由 [leave] 清理。
    //
    // 为什么保留结果不清：检测口的 LaunchedEffect 以 state.result 为 key，结果一到就会
    // 重新探测该层现状 → 那一行随即变成"已安装 · 刚刚更新" ✓ 这就是用户要的成功反馈。
    LaunchedEffect(state.result) {
        if (state.result is AppResult.Success) justUpdated = true
    }



    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { leave() }) {
                Icon(ImageVector.vectorResource(CommonR.drawable.ic_arrow_left), contentDescription = stringResource(R.string.dsh_update_back))
            }
            Text(text = title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onClearResult(); justUpdated = false; startProbe() }, enabled = !state.running) {
                Text(stringResource(R.string.online_import_reprobe))
            }
        }
        // 常驻说明区。
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Text(
                text = intro,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(16.dp),
            )
        }

        if (state.running || (state.result != null && state.result !is AppResult.Success)) {
            OnlineImportProgressView(state = state, onCancel = onCancel, onBackToList = onClearResult)
        } else {
            // 前置条件未满足（如 node 页在 Linux 层未导入时）：常驻警示 + 禁用启动。
            blockedReason?.let {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            // 启动按钮置于镜像源列表上方（先看动作，再挑源）。
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedUrl != null && !probing && blockedReason == null,
                onClick = {
                    // 语义在点击时定死（见 confirmReinstall 注释）。
                    confirmReinstall = installed == true
                    confirmStart = true
                },
            ) {
                Text(stringResource(R.string.online_import_start))
            }
            // 检测口：紧贴「获取并安装」下方。原版界面在已安装状态下与首次安装长得一模一样，
            // 该层现状直接摆在按钮下方：否则用户按下去时无从知道这是一次"重装覆盖"。
            OnlineInstallStatusRow(installed = installed, justUpdated = justUpdated, onRefresh = { checkTick++ })
            // 排序展示：有目标内容的可达源按延迟升序在前。
            val byUrl = sources.associateBy(urlOf)
            val orderedUrls = sortProbes(sources.map { urlOf(it) to probes[urlOf(it)] })
            for (url in orderedUrls) {
                val source = byUrl[url] ?: continue
                OnlineSourceCard(
                    sourceName = nameOf(source),
                    url = url,
                    note = noteOf(source),
                    probe = probes[url],
                    selected = selectedUrl == url,
                    enabled = !probing,
                    onClick = { selectedUrl = url },
                )
            }
            if (probing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.dsh_update_probing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (confirmStart) {
        val ordered = sources.sortedBy { urlOf(it) != selectedUrl }
        // 已安装 → 重装确认（与离线导入运行环境包同款弹窗，正文按层给）；
        // 未安装（或点击时仍在检测中）→ 原有通用确认，语义不变。
        val reinstalling = confirmReinstall
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = {
                Text(
                    stringResource(
                        if (reinstalling) R.string.online_reinstall_title
                        else R.string.online_import_confirm_title,
                    ),
                )
            },
            text = {
                Text(if (reinstalling) reinstallMessage else stringResource(R.string.online_import_confirm_msg))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    justUpdated = false
                    onStart(ordered)
                }) {
                    Text(
                        stringResource(
                            if (reinstalling) R.string.online_reinstall_action
                            else R.string.online_import_confirm_action,
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) {
                    Text(stringResource(R.string.settings_cancel_action))
                }
            },
        )
    }
}

/**
 * 「安装状态」检测口：显示该层**当前**是否已安装，整行可点即重检。
 *
 * 三态：[installed] 为 null 表示首帧尚未检完（显示"检测中"），非"未安装"——
 * 把未检完与确实没装区分开，避免首帧闪一下"未安装"误导用户。
 */
@Composable
private fun OnlineInstallStatusRow(
    installed: Boolean?,
    /** 该会话内刚刚导入成功 → 显示「已安装 · 刚刚更新」，作为成功反馈。 */
    justUpdated: Boolean,
    onRefresh: () -> Unit,
) {
    val label = when {
        installed == true && justUpdated -> stringResource(R.string.online_layer_installed_just_now)
        installed == true -> stringResource(R.string.online_layer_installed)
        installed == false -> stringResource(R.string.online_layer_not_installed)
        else -> stringResource(R.string.online_layer_checking)
    }
    val accent = when (installed) {
        true -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRefresh),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.online_layer_status_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.online_layer_status_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = accent,
            )
        }
    }
}

@Composable
private fun OnlineSourceCard(
    sourceName: String,
    url: String,
    note: String,
    probe: OnlineSourceProbe?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val accent = when {
        probe == null -> MaterialTheme.colorScheme.onSurfaceVariant
        !probe.reachable -> MaterialTheme.colorScheme.error
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (probe?.reachable == true && enabled) Modifier.clickable(onClick = onClick) else Modifier),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = when {
                    probe == null -> stringResource(R.string.online_source_probing)
                    probe.reachable && probe.hasTarget -> stringResource(R.string.online_source_ok_ms, probe.latencyMs)
                    probe.reachable -> stringResource(R.string.online_source_no_target)
                    else -> stringResource(
                        R.string.online_source_unreachable_reason,
                        probe.error?.asString(context) ?: stringResource(R.string.online_source_unreachable),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = accent,
            )
        }
    }
}

@Composable
private fun OnlineImportProgressView(
    state: OnlineImportState,
    onCancel: () -> Unit,
    onBackToList: () -> Unit,
) {
    val context = LocalContext.current
    val result = state.result
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                }
                Text(text = state.stage.asString(context), style = MaterialTheme.typography.titleSmall)
            }
            state.progress?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.logs.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    state.logs.asReversed().forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val failure = result as? AppResult.Failure
            if (failure != null) {
                Text(
                    text = failure.error.userMessage?.asString(context)
                        ?: (failure.error.message ?: stringResource(R.string.error_unknown)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = onBackToList) {
                    Text(stringResource(R.string.online_import_back_to_list))
                }
            } else if (state.running) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.online_import_cancel))
                }
            }
        }
    }
}
