package com.dshbox.pluginmanager.market.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dshbox.pluginmanager.R

/**
 * 任务面板（上游 `OperationsPanel` 的等价物）。
 *
 * 上游把进度记录放在服务端内存里、由客户端轮询；我们没有跨进程边界，
 * 所以记录直接由 UI 持有——数据层只提供 `onLine` 回调，面板把它逐行落到
 * 这里。这样「安装/卸载/更新正在进行」这件事在界面上永远有落点，用户切页
 * 签、离开列表都不会丢掉进度。
 *
 * 记录是**不可持久化**的：进程重启后正在进行的操作本来就没有意义。
 */

/** 操作种类。取值与上游 `opKind_*` 三档一致。 */
internal enum class MarketOperationKind { INSTALL, UPDATE, UNINSTALL }

/**
 * 一条操作记录的状态。
 *
 * 只有三态：进行中 / 已完成 / 失败。上游还有 `needs-choice`（冲突决策）
 * 与 `queued`，那两者依赖数据层暴露冲突信息与队列位置，本层暂不提供，
 * 因此不假装有这两态。
 */
internal enum class MarketOperationState { RUNNING, DONE, FAILED }

/**
 * 一条操作记录。
 *
 * 用可变属性而不是不可变 data class：`onLine` 会在操作过程中被反复调用，
 * 每来一行就复制整个对象既浪费又会让列表失去滚动位置。
 */
internal class MarketOperation(
    val kind: MarketOperationKind,
    val pluginName: String,
    /** 安装目标；重试时要原样重放，不能重新从目录推导。 */
    val installTarget: String? = null,
) {
    var state by mutableStateOf(MarketOperationState.RUNNING)
        private set

    /** 失败原因（用户可见文案）；成功时为 null。 */
    var reason: String? by mutableStateOf(null)
        private set

    /** 逐行进度输出。**有上限**：一次安装的输出可能上千行，全留着会白占内存。 */
    val lines = mutableStateListOf<String>()

    /** 失败即"需要用户处理"，面板据此把它排在最前并染成告警色。 */
    val needsUser: Boolean get() = state == MarketOperationState.FAILED

    fun append(line: String) {
        val trimmed = line.trimEnd()
        if (trimmed.isEmpty()) return
        lines.add(trimmed)
        // 只保留最近 MAX_LINES 行：面板本来就只展示尾部若干行，但底层的
        // 记录若无限增长，长安装（pnpm 输出上千行）会持续占内存。
        if (lines.size > MAX_LINES) {
            lines.removeRange(0, lines.size - MAX_LINES)
        }
    }

    /** 重试前把记录恢复成"进行中"，避免同一行既显示失败又显示进度。 */
    fun restart() {
        lines.clear()
        reason = null
        state = MarketOperationState.RUNNING
    }

    fun succeed() {
        state = MarketOperationState.DONE
    }

    fun fail(message: String?) {
        reason = message
        state = MarketOperationState.FAILED
    }
}

/**
 * 任务面板入口：常驻在返回栏右侧。
 *
 * 三态与上游一致——忙碌时显示已完成/总数，有待处理时显示待处理条数并染成
 * 告警色，都没有时只是一个安静的入口（**不留红点**）。
 */
@Composable
internal fun MarketTaskEntry(
    operations: List<MarketOperation>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = operations.count { it.state == MarketOperationState.RUNNING }
    val attention = operations.count { it.needsUser }
    val busy = running > 0
    val label = when {
        busy -> stringResource(R.string.pm_market_ops_busy, operations.size - running, operations.size)
        attention > 0 -> stringResource(R.string.pm_market_ops_attention, attention)
        else -> stringResource(R.string.pm_market_ops_title)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        TextButton(onClick = onClick) {
            Text(
                text = label,
                color = if (attention > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
        if (attention > 0) {
            Badge { Text(attention.toString()) }
        }
    }
}

/**
 * 任务面板本体。聚合进度 + 「可离开本页」提示 + 按需用户 → 进行中 → 已结束
 * 排序的记录列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarketTaskPanel(
    operations: List<MarketOperation>,
    onRetry: (MarketOperation) -> Unit,
    onClearSettled: () -> Unit,
    onDismiss: () -> Unit,
) {
    val language = marketLanguage()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settledCount = operations.count { it.state == MarketOperationState.DONE }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.pm_market_ops_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.weight(1f))
                if (settledCount > 0) {
                    TextButton(onClick = onClearSettled) {
                        Text(stringResource(R.string.pm_market_ops_clear))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.pm_market_ops_close))
                }
            }

            if (operations.isEmpty()) {
                Text(
                    text = stringResource(R.string.pm_market_ops_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(R.string.pm_market_ops_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                val progressed = operations.count { it.state != MarketOperationState.RUNNING }
                Text(
                    text = stringResource(
                        R.string.pm_market_ops_progress,
                        progressed,
                        operations.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                // 必须传 progress：不传就是**不确定态**（无限动画），任务做完之后
                // 进度条还在转，看起来像永远没结束（真机上就是这样）。
                LinearProgressIndicator(
                    progress = {
                        if (operations.isEmpty()) {
                            0f
                        } else {
                            (progressed.toFloat() / operations.size).coerceIn(0f, 1f)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
                Text(
                    text = stringResource(R.string.pm_market_ops_leave_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )

                val ordered = operations.sortedBy {
                    when (it.state) {
                        MarketOperationState.FAILED -> 0
                        MarketOperationState.RUNNING -> 1
                        MarketOperationState.DONE -> 2
                    }
                }
                // 用普通 Column + 滚动而不是 LazyColumn：记录数是个位数（一次只跑
                // 一个操作），而嵌套在弹层里再套一层惰性列表没有收益、只多一层
                // 嵌套滚动的坑。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ordered.forEach { operation ->
                        MarketOperationRow(
                            operation = operation,
                            language = language,
                            onRetry = { onRetry(operation) },
                        )
                    }
                }
            }

            // 诚实交代本轮拿不到的两项行操作：取消与"放行构建脚本并重试"都需要
            // 数据层暴露运行中的操作句柄，接口还没有，所以不画按钮。
            Text(
                text = stringResource(R.string.pm_market_ops_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/** 单条记录：三色分组 + 失败原因 + 输出 + 重试。 */
@Composable
private fun MarketOperationRow(
    operation: MarketOperation,
    language: String,
    onRetry: () -> Unit,
) {
    val container = when (operation.state) {
        MarketOperationState.FAILED -> MaterialTheme.colorScheme.errorContainer
        MarketOperationState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        MarketOperationState.DONE -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (operation.state) {
        MarketOperationState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        MarketOperationState.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        MarketOperationState.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val status = when (operation.state) {
        MarketOperationState.FAILED -> stringResource(R.string.pm_market_ops_failed)
        MarketOperationState.RUNNING -> stringResource(R.string.pm_market_ops_running)
        MarketOperationState.DONE -> stringResource(R.string.pm_market_ops_done)
    }
    val verb = when (operation.kind) {
        MarketOperationKind.INSTALL -> stringResource(R.string.pm_market_ops_kind_install)
        MarketOperationKind.UPDATE -> stringResource(R.string.pm_market_ops_kind_update)
        MarketOperationKind.UNINSTALL -> stringResource(R.string.pm_market_ops_kind_uninstall)
    }

    Surface(
        color = container,
        contentColor = onContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$verb · $status",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (operation.state == MarketOperationState.FAILED) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.pm_market_ops_retry))
                    }
                }
            }
            Text(
                text = operation.pluginName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (operation.state == MarketOperationState.RUNNING) {
                // 这一条**只在运行中**才渲染，所以用不确定态是合理的（数据层只给
                // 逐行输出，给不出百分比）。真正的问题在汇总条：那里的不确定态会
                // 在任务全部结束之后继续转，看起来像永远没完成。
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
            operation.reason?.let { reason ->
                Text(
                    text = "${stringResource(R.string.pm_market_ops_reason)}: ${localizeBilingual(reason, language)}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (operation.lines.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.pm_market_ops_output),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                ) {
                    Text(
                        text = operation.lines.takeLast(MAX_OUTPUT_LINES).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                }
            } else if (operation.state == MarketOperationState.RUNNING) {
                Text(
                    text = stringResource(R.string.pm_market_ops_no_output),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 输出只展示尾部若干行：终端输出动辄上百行，全量渲染会让面板不可用。 */
private const val MAX_OUTPUT_LINES = 40

/**
 * 单条操作**记录**的输出行数上限。
 *
 * 与 [MAX_OUTPUT_LINES]（渲染时取尾部）不同：这是底层记录本身的硬上限，
 * 防止一次长安装把上千行输出一直留在内存里。
 */
internal const val MAX_LINES = 500

// ────────────────────────────── 文本工具 ──────────────────────────────

/**
 * 当前界面语言（只要 `zh` / 其它两档）。
 *
 * 读的是**资源配置**而不是 `Locale.getDefault()`：用户在系统里给应用单独设了
 * 语言时，两者会不一致，而界面文案跟的是前者。
 */
@Composable
internal fun marketLanguage(): String {
    val context = LocalContext.current
    return context.resources.configuration.locales[0]?.language ?: "en"
}

/**
 * 从 `中文 / English`（或反向）里择一半。
 *
 * 沿用上游的判定：以 ` / ` 切分，取**中日韩字符数差异最大**的那一刀，差异为 0
 * 时认为原文不是双语对，原样返回。这样英文正文里出现的 ` / ` 不会被误切。
 */
internal fun localizeBilingual(text: String, language: String): String {
    if (text.contains('\n')) {
        return text.split('\n').joinToString("\n") { localizeBilingual(it, language) }
    }
    val parts = text.split(" / ")
    if (parts.size < 2) return text

    var bestLeft = parts[0]
    var bestRight = parts.drop(1).joinToString(" / ")
    var bestScore = kotlin.math.abs(cjkCount(bestLeft) - cjkCount(bestRight))
    for (index in 1 until parts.size - 1) {
        val left = parts.take(index + 1).joinToString(" / ")
        val right = parts.drop(index + 1).joinToString(" / ")
        val score = kotlin.math.abs(cjkCount(left) - cjkCount(right))
        if (score > bestScore) {
            bestScore = score
            bestLeft = left
            bestRight = right
        }
    }
    if (bestScore == 0) return text
    val leftIsChinese = cjkCount(bestLeft) > cjkCount(bestRight)
    val chinese = if (leftIsChinese) bestLeft else bestRight
    val english = if (leftIsChinese) bestRight else bestLeft
    return if (language == "zh") chinese else english
}

/**
 * 逐条本地化后拼接。理由之间用分号而不是 ` / `——后者是双语分隔符，用它拼
 * 会让两条理由看起来像一条双语的。
 */
internal fun localizeBilingualList(parts: List<String>, language: String): String {
    val separator = if (language == "zh") "；" else "; "
    return parts
        .map { localizeBilingual(it, language) }
        .filter { it.isNotEmpty() }
        .joinToString(separator)
}

private fun cjkCount(text: String): Int =
    text.count { it.code in 0x4E00..0x9FFF || it.code in 0x3400..0x4DBF }
