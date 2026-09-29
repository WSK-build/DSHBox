package com.dshbox.pluginmanager.market.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 失败文案分类。
 *
 * 重点是**顺序**：`patch: entry X not found` 里也含 `not found`，如果通用
 * "找不到"先匹配，用户就会拿到一句与真实原因无关的提示。无法归类时必须
 * 返回 null，让调用方原样展示 pnpm 的话，而不是编一句可能误导的总结。
 */
class PnpmFailureTextTest {

    @Test
    fun recognizesMissingPnpm() {
        val verdict = PnpmFailureText.classify("dsh: pnpm failed in profile directory\npnpm not found on PATH")
        assertEquals(PluginFailureVerdict.Kind.PNPM_MISSING, verdict?.kind)
    }

    @Test
    fun recognizesIgnoredBuildScriptsByBothShapes() {
        assertEquals(
            PluginFailureVerdict.Kind.IGNORED_BUILDS,
            PnpmFailureText.classify("Ignored build scripts: esbuild, koffi.")?.kind,
        )
        assertEquals(
            PluginFailureVerdict.Kind.IGNORED_BUILDS,
            PnpmFailureText.classify("ERR_PNPM_IGNORED_BUILDS something")?.kind,
        )
    }

    @Test
    fun patchEntryWarningWinsOverGenericNotFound() {
        val verdict = PnpmFailureText.classify("patch: entry dsh-better-sidebar not found")
        assertEquals(PluginFailureVerdict.Kind.PATCH_ENTRY_NOT_FOUND, verdict?.kind)
    }

    @Test
    fun recognizesRegistry404() {
        assertEquals(
            PluginFailureVerdict.Kind.NOT_FOUND,
            PnpmFailureText.classify("ERR_PNPM_FETCH_404  GET https://registry.npmjs.org/x: Not Found")?.kind,
        )
    }

    @Test
    fun recognizesTransientNetworkFailure() {
        assertEquals(
            PluginFailureVerdict.Kind.TRANSIENT,
            PnpmFailureText.classify("FetchError: request failed\nECONNRESET")?.kind,
        )
    }

    @Test
    fun recognizesDownloadTimeout() {
        assertEquals(
            PluginFailureVerdict.Kind.TIMEOUT,
            PnpmFailureText.classify("ERR_PNPM_FETCH_TIMEOUT request timed out")?.kind,
        )
    }

    @Test
    fun fallsBackToGenericPnpmCode() {
        val verdict = PnpmFailureText.classify("ERR_PNPM_SOMETHING_ELSE while doing things")
        assertEquals(PluginFailureVerdict.Kind.ERR_PNPM, verdict?.kind)
        assertEquals(true, verdict?.message?.contains("ERR_PNPM_SOMETHING_ELSE"))
    }

    @Test
    fun returnsNullWhenNothingIsRecognized() {
        assertNull(PnpmFailureText.classify("some completely unrelated output"))
        assertNull(PnpmFailureText.classify(""))
    }

    @Test
    fun everyVerdictMessageIsActionable() {
        val samples = listOf(
            "pnpm not found on PATH",
            "Ignored build scripts: esbuild.",
            "patch: entry x not found",
            "ERR_PNPM_FETCH_404",
            "ECONNRESET",
            "ERR_PNPM_FETCH_TIMEOUT",
            "ERR_PNPM_OTHER",
        )
        for (sample in samples) {
            val message = PnpmFailureText.classify(sample)?.message
            assertEquals(true, message != null && message.contains(" / "))
        }
    }
}
