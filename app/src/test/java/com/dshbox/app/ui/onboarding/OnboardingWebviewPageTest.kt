package com.dshbox.app.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebView 检测页纯 JVM 测试。
 *
 * 验证三项规则：判定线取五项特性所需最低主版本的最大值（漏一项会让过旧内核被误判
 * 通过）；主版本解析的优先级（真实 UA 里的 `Chrome/x` 优先于包 versionName 首段）；
 * 检测结果归类（提供方缺失或版本不可解析必须落到失败，不能臆断通过/偏低）。
 */
class OnboardingWebviewPageTest {

    @Test
    fun requiredChromiumIsMaxOfAllFeatureMinimums() {
        // 判定线 = 各项最低主版本的最大值（light-dark 的 123）。
        assertEquals(123, WebviewRequiredChromium)
        assertEquals(WebviewRequiredChromium, WEBVIEW_CSS_FEATURES.maxOf { it.minChromium })
    }

    @Test
    fun featureTableMatchesDocumentedGate() {
        // 验证五项能力各自所需的最低主版本。
        val table = WEBVIEW_CSS_FEATURES.associate { it.label to it.minChromium }
        assertEquals(5, table.size)
        assertEquals(105, table[":has()"])
        assertEquals(111, table["color-mix()"])
        assertEquals(108, table["dvh"])
        assertEquals(117, table["subgrid"])
        assertEquals(123, table["light-dark()"])
    }

    @Test
    fun chromiumMajorPrefersDefaultUserAgent() {
        // 默认 UA 内含 Chrome/128 → 128，即便包 versionName 指向别的版本。
        assertEquals(
            128,
            chromiumMajorOf(
                defaultUserAgent = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/128.0.6778.200 Mobile Safari/537.36",
                versionName = "126.0.6478.71",
            ),
        )
    }

    @Test
    fun chromiumMajorFallsBackToVersionNameFirstSegment() {
        // UA 不可用时回落到 versionName 首段。
        assertEquals(118, chromiumMajorOf(defaultUserAgent = null, versionName = "118.0.5993.65"))
        assertEquals(118, chromiumMajorOf(defaultUserAgent = "no chrome token", versionName = "118.0.5993.65"))
    }

    @Test
    fun chromiumMajorNullWhenNothingParsable() {
        assertEquals(null, chromiumMajorOf(defaultUserAgent = null, versionName = null))
        assertEquals(null, chromiumMajorOf(defaultUserAgent = "", versionName = ""))
        assertEquals(null, chromiumMajorOf(defaultUserAgent = null, versionName = "unknown"))
    }

    @Test
    fun missingFeaturesAreVersionInferredAndOrdered() {
        // 低于 123 只缺 light-dark；再低的版本按各自最低主版本逐项补齐。
        assertEquals(listOf("light-dark()"), missingFeatureLabels(118))
        assertEquals(listOf("color-mix()", "subgrid", "light-dark()"), missingFeatureLabels(110))
        assertEquals(
            listOf(":has()", "color-mix()", "dvh", "subgrid", "light-dark()"),
            missingFeatureLabels(96),
        )
        // 达到判定线即无缺失。
        assertTrue(missingFeatureLabels(126).isEmpty())
    }

    @Test
    fun classifySucceedsWithProviderPackageAndParsableMajor() {
        val probe = classifyWebviewProbe(
            providerPackage = "com.google.android.webview",
            providerVersion = "126.0.6478.71",
            defaultUserAgent = "Chrome/126.0.6478.71",
        )
        assertEquals(
            WebviewProbe.Measured(
                provider = "com.google.android.webview",
                version = "126.0.6478.71",
                major = 126,
            ),
            probe,
        )
    }

    @Test
    fun classifyFailsWhenProviderMissing() {
        // 提供方缺失（WebView 未安装/被停用）必须判失败：无从比较版本。
        assertEquals(
            WebviewProbe.Failed,
            classifyWebviewProbe(
                providerPackage = null,
                providerVersion = "126.0.6478.71",
                defaultUserAgent = "Chrome/126.0.6478.71",
            ),
        )
        assertEquals(
            WebviewProbe.Failed,
            classifyWebviewProbe(
                providerPackage = "  ",
                providerVersion = "126.0.6478.71",
                defaultUserAgent = "Chrome/126.0.6478.71",
            ),
        )
    }

    @Test
    fun classifyFailsWhenMajorUnparsable() {
        assertEquals(
            WebviewProbe.Failed,
            classifyWebviewProbe(
                providerPackage = "com.android.webview",
                providerVersion = "unknown",
                defaultUserAgent = null,
            ),
        )
    }
}
