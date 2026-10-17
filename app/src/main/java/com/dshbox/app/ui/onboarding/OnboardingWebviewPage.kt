package com.dshbox.app.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.dshbox.app.R
import com.dshbox.app.ui.theme.Warning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── 与诊断页同源的检测口径 ─────────────────────────────────────────────
//
// DSH 前端依赖一批较新的 CSS 能力：`:has()`、`color-mix()`、`dvh`、`subgrid`、
// `light-dark()`。系统 WebView 过旧时这些特性会**静默失效**，表现为页面加载异常、
// 白屏或工作区无法选择（诊断页有同一份清单）。引导页据此在安装前给出提示。
//
// 与诊断页的差别：本页只查「提供方包名/版本 + 默认 UA 里的 Chromium 主版本」，
// 不实例化内核、不跑 CSS.supports——逐项特性按主版本号推断展示，精确探测仍在
// 「设置 → 诊断与日志」用真实内核完成（页面文案已注明）。

/** 一个探测项：展示用 CSS 令牌与所需最低 Chromium 主版本。 */
internal data class WebviewCssFeature(val label: String, val minChromium: Int)

/**
 * 五项能力与各自最低主版本，数值与诊断页 CSS_PROBES 一致。
 *
 * 同步源：settings/DiagnosticsScreen.kt 的 CSS_PROBES；彼处增改探测项或阈值
 * 必须同步此表。
 */
internal val WEBVIEW_CSS_FEATURES = listOf(
    WebviewCssFeature(":has()", 105),
    WebviewCssFeature("color-mix()", 111),
    WebviewCssFeature("dvh", 108),
    WebviewCssFeature("subgrid", 117),
    WebviewCssFeature("light-dark()", 123),
)

/** 判定线 = 各项所需最低主版本的最大值（缺哪项就卡哪条线）。 */
internal val WebviewRequiredChromium: Int = WEBVIEW_CSS_FEATURES.maxOf { it.minChromium }

/**
 * 从 UA / 版本号取 Chromium 主版本：`Chrome/128.0.6778.200` → `128`。
 *
 * 该正则与 settings/DiagnosticsScreen.kt 的 CHROME_MAJOR 保持一致。
 */
private val CHROME_MAJOR = Regex("""Chrome/(\d+)""")

/** 主版本优先级：默认 UA 的 `Chrome/x` > 提供方包 versionName 首段。 */
internal fun chromiumMajorOf(defaultUserAgent: String?, versionName: String?): Int? {
    defaultUserAgent?.let { ua ->
        CHROME_MAJOR.find(ua)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    }
    return versionName?.substringBefore('.')?.toIntOrNull()
}

/** 按主版本推断：低于该项最低主版本即为缺失（展示顺序同 [WEBVIEW_CSS_FEATURES]）。 */
internal fun missingFeatureLabels(major: Int): List<String> =
    WEBVIEW_CSS_FEATURES.filter { major < it.minChromium }.map { it.label }

/** WebView 检测状态：进行中 / 已测得指纹 / 检测失败。 */
internal sealed interface WebviewProbe {
    /** 正在读取提供方与版本信息。 */
    data object Checking : WebviewProbe

    /** 探测成功：[provider] 为提供方包名，[version] 为 versionName，[major] 为 Chromium 主版本。 */
    data class Measured(val provider: String, val version: String, val major: Int) : WebviewProbe

    /** 检测失败：提供方缺失 / 版本不可解析 / 查询异常。 */
    data object Failed : WebviewProbe
}

/**
 * 由已取得的原始字符串归类检测结果（纯函数，便于 JVM 单测）。
 *
 * 提供方缺失（WebView 未安装或被停用）与主版本不可解析都归为失败：没有版本就无从
 * 与判定线比较，继续展示「偏低/通过」都是臆断。
 */
internal fun classifyWebviewProbe(
    providerPackage: String?,
    providerVersion: String?,
    defaultUserAgent: String?,
): WebviewProbe {
    if (providerPackage.isNullOrBlank()) return WebviewProbe.Failed
    val major = chromiumMajorOf(defaultUserAgent, providerVersion) ?: return WebviewProbe.Failed
    return WebviewProbe.Measured(
        provider = providerPackage,
        version = providerVersion.orEmpty(),
        major = major,
    )
}

/** 引导页中 WebView 检测所在的页序号（第 4 页，五点指示的第 4 点高亮）。 */
private const val WebviewStepIndex = 3

/** 检测未完成时信息卡的值占位。 */
private const val WebviewUnknownValue = "—"

/**
 * 新手引导的 WebView 检测页：只检测与告知，不做任何系统操作。
 *
 * 进页自动探测一次，协程挂在页面生命周期上，离开即取消；探测只做包查询与
 * [WebSettings.getDefaultUserAgent]（不实例化内核），结果驱动通过 / 偏低 / 失败
 * 三分支：偏低给出升级建议与可复制的咨询文本，失败指引去诊断页看内核指纹。
 * 底部三键与安装页同构，但本页无安装语义，「继续」始终可点。
 */
@Composable
internal fun OnboardingWebviewPage(
    onBack: () -> Unit,
    onNext: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val context = LocalContext.current
    var probe by remember { mutableStateOf<WebviewProbe>(WebviewProbe.Checking) }

    // 探测在 IO 线程执行（包查询与 UA 读取均为系统调用）；离开本页时组合被释放，
    // 该协程随之取消，取消必须原样抛出，不能并入 Failed。其余异常一律落到 Failed，
    // 页面上仍能给出可操作的指引。
    LaunchedEffect(Unit) {
        probe = WebviewProbe.Checking
        probe = withContext(Dispatchers.IO) {
            try {
                val pkg = WebView.getCurrentWebViewPackage()
                val defaultUa = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
                classifyWebviewProbe(pkg?.packageName, pkg?.versionName, defaultUa)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WebviewProbe.Failed
            }
        }
    }

    val measured = probe as? WebviewProbe.Measured
    val passed = measured != null && measured.major >= WebviewRequiredChromium
    val missing = measured?.let { missingFeatureLabels(it.major) }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnboardingStepDots(currentIndex = WebviewStepIndex)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onSkip) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = OnboardingContentMaxWidth)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_step_eyebrow, WebviewStepIndex, OnboardingTotalSteps),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.onboarding_webview_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.onboarding_webview_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                CheckCard(probe = probe, passed = passed)

                if (measured != null && !passed) {
                    StatusCard(
                        title = stringResource(R.string.onboarding_webview_low),
                        body = stringResource(
                            R.string.onboarding_webview_low_hint,
                            measured.version.ifEmpty { WebviewUnknownValue },
                            WebviewRequiredChromium,
                        ),
                        accent = Warning,
                    )
                    AdviceSection(
                        provider = measured.provider,
                        version = measured.version,
                        major = measured.major,
                        missing = missing,
                    )
                } else if (probe is WebviewProbe.Failed) {
                    StatusCard(
                        title = stringResource(R.string.onboarding_webview_failed),
                        body = stringResource(R.string.onboarding_webview_failed_hint),
                        accent = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_back))
            }
            OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_next))
            }
            // 本页只做检测不做安装，故「继续」始终可点。
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_continue))
            }
        }
    }
}

/** 检测信息卡：状态点 + 状态文案 + 提供方/版本/主版本/所需版本四行。 */
@Composable
private fun CheckCard(probe: WebviewProbe, passed: Boolean) {
    val (accent, stateText) = when {
        probe is WebviewProbe.Checking ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.onboarding_webview_detecting)
        passed -> MaterialTheme.colorScheme.primary to stringResource(R.string.onboarding_webview_ok)
        probe is WebviewProbe.Measured ->
            Warning to stringResource(R.string.onboarding_webview_low)
        else -> MaterialTheme.colorScheme.error to stringResource(R.string.onboarding_webview_failed)
    }
    val measured = probe as? WebviewProbe.Measured

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(accent, CircleShape),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stateText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                FingerprintRow(
                    label = stringResource(R.string.onboarding_webview_row_provider),
                    value = measured?.provider ?: WebviewUnknownValue,
                )
                FingerprintRow(
                    label = stringResource(R.string.onboarding_webview_row_version),
                    value = measured?.version?.ifEmpty { WebviewUnknownValue } ?: WebviewUnknownValue,
                )
                FingerprintRow(
                    label = stringResource(R.string.onboarding_webview_row_major),
                    value = measured?.major?.toString() ?: WebviewUnknownValue,
                )
                FingerprintRow(
                    label = stringResource(R.string.onboarding_webview_row_required_label),
                    // 判定线是固定值，检测中/失败也照常给出，供用户对照。
                    value = stringResource(
                        R.string.onboarding_webview_row_required,
                        WebviewRequiredChromium,
                    ),
                )
            }
            // 特性逐项按主版本推断；检测中 / 失败无版本可比，不展示。
            if (measured != null) {
                Text(
                    text = stringResource(R.string.onboarding_webview_features_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                for (feature in WEBVIEW_CSS_FEATURES) {
                    val ok = measured.major >= feature.minChromium
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
                            text = stringResource(
                                R.string.onboarding_webview_feature_ok,
                                feature.label,
                                feature.minChromium,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ok) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 一行指纹：标签在左、值在右（值等宽字体并右对齐，长版本号可换行）。 */
@Composable
private fun FingerprintRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 结论卡：偏低为橙色警示、失败为红色错误（标题与建议/指引同卡）。 */
@Composable
private fun StatusCard(title: String, body: String, accent: Color) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accent),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = accent,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 升级建议：三条编号建议 + 可复制的咨询文本（按检测结果填充）。 */
@Composable
private fun AdviceSection(
    provider: String,
    version: String,
    major: Int,
    missing: List<String>,
) {
    val context = LocalContext.current
    val separator = stringResource(R.string.onboarding_list_separator)
    val consultText = stringResource(
        R.string.onboarding_webview_consult_fmt,
        provider,
        version.ifEmpty { WebviewUnknownValue },
        major,
        WebviewRequiredChromium,
        missing.joinToString(separator),
        Build.MODEL,
    )
    val copiedText = stringResource(R.string.onboarding_webview_copied)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_webview_advice_title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            AdviceItem(
                number = 1,
                title = stringResource(R.string.onboarding_webview_advice_store_title),
                desc = stringResource(R.string.onboarding_webview_advice_store_desc),
            )
            AdviceItem(
                number = 2,
                title = stringResource(R.string.onboarding_webview_advice_dev_title),
                desc = stringResource(R.string.onboarding_webview_advice_dev_desc),
            )
            AdviceItem(
                number = 3,
                title = stringResource(R.string.onboarding_webview_advice_consult_title),
                desc = stringResource(R.string.onboarding_webview_advice_consult_desc),
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = consultText,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    CopyButton(
                        onClick = {
                            // 剪贴板服务可能缺失：置入失败与成功都反馈同一句「已复制」，
                            // 不因平台差异中断交互。
                            context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                                runCatching {
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            context.getString(R.string.onboarding_webview_title),
                                            consultText,
                                        ),
                                    )
                                }
                            }
                            Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
        }
    }
}

/** 一条编号建议：圆形序号 + 标题 + 说明。 */
@Composable
private fun AdviceItem(number: Int, title: String, desc: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 浅色描边复制按钮（与诊断日志条目的复制按钮同风格，取主题主色）。 */
@Composable
private fun CopyButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraSmall,
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.semantics { role = Role.Button },
    ) {
        Text(
            text = stringResource(R.string.onboarding_webview_copy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
