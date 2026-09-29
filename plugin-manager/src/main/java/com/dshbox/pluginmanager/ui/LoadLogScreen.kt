package com.dshbox.pluginmanager.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.dshbox.app.sandbox.BootSegmentedLog
import com.dshbox.pluginmanager.R

/**
 * 插件加载记录。
 *
 * 展示 DSH 启动日志的**全文**：保留最近若干次启动，每段的开头是一条**红色时间戳标记行**
 * （由写入侧写下，见 [BootSegmentedLog]），因此"哪段是哪次启动"一眼可辨。
 *
 * 正文不做二次筛选：DSH 并没有逐插件的"加载成功"日志，我们能确定的只有它自己的输出。
 * （原「本次启动 / 上次启动」两栏已取消：「上次启动」随安全模式一并封存，
 * 而"本次启动"的命名本来就不准 —— 那时取的是共享日志的尾部，一个窗口里往往混着多次启动。）
 */
@Composable
fun LoadLogScreen(
    text: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val markerColor = MaterialTheme.colorScheme.error
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 超长日志只画末尾：整段渲染会拖慢首帧（写入侧已限制到 10 段 × 单段 50KB 以内）。
    val truncated = text.length > RENDER_LIMIT_CHARS
    val shown = if (truncated) text.takeLast(RENDER_LIMIT_CHARS) else text
    val annotated = remember(shown, markerColor, bodyColor) {
        bootLogAnnotated(shown, markerColor, bodyColor)
    }

    Column(Modifier.fillMaxSize()) {
        PanelTopBar(title = stringResource(R.string.pm_load_log_title), onBack = onBack)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.pm_load_log_hint, BootSegmentedLog.MAX_SEGMENTS),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.pm_copy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(enabled = text.isNotBlank()) {
                        copyToClipboard(context, text)
                        Toast.makeText(context, R.string.pm_copied, Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (truncated) {
            Text(
                text = stringResource(R.string.pm_load_log_too_long),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (shown.isBlank()) {
                Text(
                    text = stringResource(R.string.pm_load_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

/** 面板一次最多渲染的字符数（200KB）；超出只画末尾，并在上方提示去诊断页导出全量。 */
private const val RENDER_LIMIT_CHARS = 200 * 1024

/**
 * 把日志正文按行着色：**启动标记行**用强调色（红），其余用正文色。
 *
 * 纯函数（只吃文本与两个颜色），便于单测断言"只有标记行被着色"。
 */
internal fun bootLogAnnotated(text: String, markerColor: Color, bodyColor: Color): AnnotatedString =
    buildAnnotatedString {
        text.split("\n").forEachIndexed { index, line ->
            if (index > 0) append("\n")
            if (BootSegmentedLog.isMarker(line)) {
                withStyle(SpanStyle(color = markerColor, fontWeight = FontWeight.SemiBold)) {
                    append(line)
                }
            } else {
                withStyle(SpanStyle(color = bodyColor)) { append(line) }
            }
        }
    }

/*
 * 段切换胶囊按钮随「本次/上次」两栏一并取消（现已单栏展示，无需切换）。
 * 保留代码以备将来恢复分栏视图。
 *
 * @Composable
 * private fun SegmentChip(label: String, selected: Boolean, onClick: () -> Unit) { ... }
 */

/** 复制到系统剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("dsh-load-log", text))
}
