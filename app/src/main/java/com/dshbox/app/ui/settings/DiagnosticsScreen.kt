package com.dshbox.app.ui.settings

import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import java.io.File
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 诊断日志升级——展示全部进程日志条目（DSH / 沙箱 / 访客命令），
 * 每条尾部最多 [TAIL_LINES] 行（页面内直接可滚动查看，DSH 排障重点依赖其启动
 * 过程输出）；导出时合并当前文件与其 `.prev` 轮转文件（策略 A 保留最近两代）。
 */
@Composable
fun DiagnosticsScreen(
    sandboxReady: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val logsDir = File(context.filesDir, "logs")

    data class LogEntry(val titleRes: Int, val fileName: String)

    val entries = remember(logsDir) {
        listOf(
            LogEntry(R.string.diagnostics_log_dsh, "process-dsh.log"),
            LogEntry(R.string.diagnostics_log_sandbox, "process-sandbox.log"),
            LogEntry(R.string.diagnostics_log_guest, "process-guest.log"),
        ).map { e ->
            val file = File(logsDir, e.fileName)
            val lines = runCatching { file.readLines().takeLast(TAIL_LINES) }.getOrDefault(emptyList())
            e to lines
        }
    }

    // 导出 = 全部条目（当前 + .prev 轮转）合并，带分隔头。
    val exportText = remember(entries) {
        buildString {
            for ((entry, lines) in entries) {
                append("\n===== ${entry.fileName} =====\n")
                append(File(logsDir, entry.fileName + ".prev").takeIf { it.isFile }
                    ?.let { runCatching { it.readText() }.getOrNull() } ?: "")
                append(lines.joinToString("\n"))
                append("\n")
            }
        }
    }

    val exportLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(exportText.toByteArray())
            }
            Toast.makeText(context, R.string.diagnostics_export_done, Toast.LENGTH_SHORT).show()
        }
        Unit
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_diagnostics),
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = stringResource(R.string.diagnostics_dsh_address) + "：" + Constants.DSH_BASE_URL,
            style = MaterialTheme.typography.bodyLarge,
        )

        Text(
            text = stringResource(
                if (sandboxReady) R.string.diagnostics_status_running
                else R.string.diagnostics_status_not_ready,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )

        WebViewEngineSection()

        Text(
            text = stringResource(R.string.diagnostics_log_title),
            style = MaterialTheme.typography.titleMedium,
        )

        for ((entry, lines) in entries) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(entry.titleRes) + "（" + entry.fileName + "）",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = lines.joinToString("\n").ifEmpty { stringResource(R.string.diagnostics_empty) },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }

        OutlinedButton(
            shape = MaterialTheme.shapes.medium,
            onClick = { exportLogLauncher.launch("dsh-log.txt") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.diagnostics_export))
        }

        Button(
            shape = MaterialTheme.shapes.medium,
            onClick = onBack,
        ) {
            Text(stringResource(R.string.diagnostics_back))
        }
    }
}

/** 诊断页每条日志显示的尾部行数（页面内直接可滚动查看）。 */
private const val TAIL_LINES = 150

// ── WebView 真实内核指纹 ────────────────────────────────────────────────
//
// DSH 前端依赖一批较新的 CSS 能力：`:has()`（设置面板两步式/汉堡隐藏等选择器）、
// `color-mix()` 与 `light-dark()`（主题色）、`subgrid`（布局）、`dvh`（全屏视口）。
// 系统 WebView 过旧时这些特性**静默失效**——CSS 不报错、JS 不抛异常，表现为
// 「布局莫名错位 / 浮层盖不住 / 主题色发灰 / 高度被地址栏撑爆」，而日志里没有
// 任何线索。因此这里用**真实内核实例**执行 CSS.supports 探测，把设备实际能力
// 与各特性所需的最低 Chromium 主版本直接摆出来。
//
// 判定以「实测」为准而非版本号推断：部分 ROM 的 WebView 版本号与实际能力不一致，
// 只有真跑一次才知道页面为何坏掉。

/** 一个探测项：JS 结果键 / 展示用 CSS 令牌 / 所需最低 Chromium 主版本。 */
private data class CssProbe(val key: String, val label: String, val minChromium: Int)

private val CSS_PROBES = listOf(
    CssProbe("has", ":has()", 105),
    CssProbe("colorMix", "color-mix()", 111),
    CssProbe("subgrid", "subgrid", 117),
    CssProbe("lightDark", "light-dark()", 123),
    CssProbe("dvh", "dvh", 108),
)

/** 从 UA / 版本号里取 Chromium 主版本，例如 `Chrome/128.0.6778.200` → `128`。 */
private val CHROME_MAJOR = Regex("""Chrome/(\d+)""")

private const val PROBE_HTML = "<!doctype html><html><body></body></html>"

/** 在真实内核里跑一次能力探测，返回对象（由 evaluateJavascript 转成 JSON 回传）。 */
private val PROBE_JS = """
(function () {
  function sup(prop, value) { try { return CSS.supports(prop, value); } catch (e) { return false; } }
  function sel(cond) { try { return CSS.supports(cond); } catch (e) { return false; } }
  return {
    has: sel('selector(:has(*))'),
    colorMix: sup('color', 'color-mix(in srgb, red, blue)'),
    subgrid: sup('grid-template-columns', 'subgrid'),
    lightDark: sup('color', 'light-dark(red, blue)'),
    dvh: sup('height', '100dvh'),
    ua: navigator.userAgent
  };
})()
""".trimIndent()

/** 解析 evaluateJavascript 回传的 JSON；非法/未就绪返回 null。 */
private fun parseProbeResult(raw: String?): Pair<Map<String, Boolean>, String>? {
    if (raw.isNullOrBlank() || raw == "null") return null
    return runCatching {
        val obj = JSONObject(raw)
        val supported = CSS_PROBES.associate { it.key to obj.optBoolean(it.key, false) }
        supported to obj.optString("ua")
    }.getOrNull()
}

/**
 * 探测超时（ms）。`evaluateJavascript` 不回调（页面加载失败、内核半死）或回传
 * 非法 JSON 时，结果永远不会回填——没有这个兜底，UI 会永久停在「Detecting…」，
 * 而诊断页恰恰是用来判断「是不是内核坏了」的地方。
 */
private const val PROBE_TIMEOUT_MS = 5_000L

/** WebView 真实内核探测的状态机。 */
private sealed interface KernelProbe {
    /** 探测进行中。 */
    data object Detecting : KernelProbe

    /** 探测成功：[features] 为各探测项结果，[ua] 为真实内核 UA。 */
    data class Measured(val features: Map<String, Boolean>, val ua: String) : KernelProbe

    /** 探测失败：内核不可用 / 无回传 / 回传非法 / 超时。 */
    data object Failed : KernelProbe
}

/**
 * 探测用内核实例的持有者。
 *
 * 刻意**不用** Compose 状态：WebView 实例不需要参与重组，而 AndroidView 的 factory
 * 会在组合/布局期被调用，在那里写状态并不合适；这里只需要一个可释放的引用。
 */
private class ProbeViewHolder {
    var view: WebView? = null
}

/**
 * WebView 内核指纹面板：提供者包名/版本 + Chromium 主版本 + 真实内核的 CSS 能力实测。
 * 供排障判断「页面坏掉是不是内核太旧」——这是日志无法回答的问题。
 */
@Composable
private fun WebViewEngineSection() {
    val context = LocalContext.current

    // 静态信息：当前 WebView 提供者包（不需要实例化内核）。
    val webViewPackage = remember { runCatching { WebView.getCurrentWebViewPackage() }.getOrNull() }
    val fallbackUa = remember {
        runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull().orEmpty()
    }
    val fallbackMajor = remember(fallbackUa) {
        CHROME_MAJOR.find(fallbackUa)?.groupValues?.getOrNull(1)
    }

    // 动态信息：真实内核探测结果（Detecting = 尚未回填）。
    //
    // 提供者为 null 表示 WebView 未安装/被停用 —— 此时构造实例**必抛**
    // MissingWebViewPackageException，直接用 Failed 起始，不去冒这个险。
    val kernelHostAvailable = webViewPackage != null
    var kernelProbe by remember {
        mutableStateOf<KernelProbe>(
            if (kernelHostAvailable) KernelProbe.Detecting else KernelProbe.Failed,
        )
    }
    val probeHolder = remember { ProbeViewHolder() }

    // 释放探测实例：先从 view 树上摘除再 destroy（WebView 实例很重，不能靠 GC）。
    fun releaseProbeView() {
        probeHolder.view?.let { wv ->
            runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
            runCatching { wv.destroy() }
        }
        probeHolder.view = null
    }

    // 探测完成（成功/失败/超时）即释放内核实例，不必等到离开页面。
    LaunchedEffect(kernelProbe) {
        if (kernelProbe !is KernelProbe.Detecting) releaseProbeView()
    }

    // 超时兜底：无回传 / 非法回传时切到失败态，避免永久「Detecting…」。
    LaunchedEffect(kernelHostAvailable) {
        if (!kernelHostAvailable) return@LaunchedEffect
        delay(PROBE_TIMEOUT_MS)
        if (kernelProbe is KernelProbe.Detecting) kernelProbe = KernelProbe.Failed
    }

    // 离开诊断页的最终兜底（超时前离开 / 组合被提前释放）。
    DisposableEffect(Unit) {
        onDispose { releaseProbeView() }
    }

    val measured = kernelProbe as? KernelProbe.Measured

    // 主版本优先级：真实内核 UA > 系统默认 UA > 提供者包 versionName 首段。
    val chromiumMajor = measured?.ua?.let { CHROME_MAJOR.find(it)?.groupValues?.getOrNull(1) }
        ?: fallbackMajor
        ?: webViewPackage?.versionName?.substringBefore('.')
    val unknown = stringResource(R.string.diagnostics_webview_unknown)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.diagnostics_webview_title),
                style = MaterialTheme.typography.labelLarge,
            )
            FingerprintRow(
                stringResource(R.string.diagnostics_webview_package),
                webViewPackage?.packageName ?: unknown,
            )
            FingerprintRow(
                stringResource(R.string.diagnostics_webview_version),
                webViewPackage?.versionName ?: unknown,
            )
            FingerprintRow(
                stringResource(R.string.diagnostics_webview_chromium),
                chromiumMajor ?: unknown,
            )

            Text(
                text = stringResource(R.string.diagnostics_webview_features),
                style = MaterialTheme.typography.labelLarge,
            )
            when (val state = kernelProbe) {
                KernelProbe.Detecting -> Text(
                    text = stringResource(R.string.diagnostics_webview_detecting),
                    style = MaterialTheme.typography.bodySmall,
                )

                // 失败态必须显式呈现（不能停在 Detecting…）：失败本身就是要诊断的结论
                // ——「内核不可用」或「内核无响应」。
                KernelProbe.Failed -> Text(
                    text = stringResource(R.string.diagnostics_webview_probe_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )

                is KernelProbe.Measured -> for (probe in CSS_PROBES) {
                    val ok = state.features[probe.key] == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (ok) "[OK] " else "[--] ",
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                            color = if (ok) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            text = probe.label,
                            style = MaterialTheme.typography.bodySmall
                                .copy(fontFamily = FontFamily.Monospace),
                        )
                        if (!ok) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(
                                    R.string.diagnostics_webview_feature_unsupported,
                                    probe.minChromium.toString(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            val ua = measured?.ua ?: fallbackUa.ifEmpty { null }
            if (ua != null) {
                FingerprintRow(stringResource(R.string.diagnostics_webview_ua), ua)
            }

            // 1dp 的离屏内核实例：只用于上面这一次探测，拿到结果后立即释放
            // （见 releaseProbeView）。探测期间才存在，故用状态门控。
            if (kernelProbe is KernelProbe.Detecting) {
                AndroidView(
                    modifier = Modifier.size(1.dp),
                    factory = { ctx ->
                        // 构造 WebView 在提供者缺失/被停用时会抛
                        // MissingWebViewPackageException —— 本页的功能正是「诊断 WebView
                        // 到底能不能用」，所以这里绝不允许它把诊断页本身带崩：
                        // 构造失败退化为占位 View，由 PROBE_TIMEOUT_MS 兜底切失败态。
                        val wv = runCatching { WebView(ctx) }.getOrNull()
                            ?: return@AndroidView android.view.View(ctx)
                        wv.apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    view?.evaluateJavascript(PROBE_JS) { raw ->
                                        val parsed = parseProbeResult(raw)
                                        kernelProbe = if (parsed != null) {
                                            KernelProbe.Measured(parsed.first, parsed.second)
                                        } else {
                                            KernelProbe.Failed
                                        }
                                    }
                                }
                            }
                            loadDataWithBaseURL(null, PROBE_HTML, "text/html", "utf-8", null)
                            probeHolder.view = this
                        }
                    },
                )
            }
        }
    }
}

/** 一行指纹：标签在上、值在下（值用等宽字体并自动换行，UA 很长也不会溢出）。 */
@Composable
private fun FingerprintRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}