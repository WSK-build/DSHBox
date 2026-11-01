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

    /**
     * 对齐 dsh-market 1.67 的 30 个分类码：逐个用上游注释里的**真实输出片段**
     * 作输入，确认都能认出来。
     *
     * 这张表本身就是契约：任何一条回归（正则写错、顺序被抢）都会让对应的
     * 断言红，并直接指出是哪一种失败失去了可读文案。
     */
    @Test
    fun recognizesEveryUpstreamFailureCode() {
        val cases: List<Pair<String, String>> = listOf(
            PluginFailureVerdict.Kind.PNPM_STALLED.name to
                "pnpm printed nothing for 600000ms and was terminated",
            PluginFailureVerdict.Kind.PROFILE_WRITE_LOCK.name to
                "dsh: atomic-write: timed out waiting for the writer lock at \"/root/projects/.dsh/profiles/web/package.json\"",
            PluginFailureVerdict.Kind.HOIST_PATTERN_DIFF.name to
                " ERR_PNPM_PUBLIC_HOIST_PATTERN_DIFF  The public hoist pattern of the modules directory",
            PluginFailureVerdict.Kind.HOIST_PATTERN_DIFF.name to
                " ERR_PNPM_VIRTUAL_STORE_DIR_MAX_LENGTH_DIFF",
            PluginFailureVerdict.Kind.UNEXPECTED_STORE.name to
                " ERR_PNPM_UNEXPECTED_STORE  The dependencies at \"/p/node_modules\" were installed",
            PluginFailureVerdict.Kind.MISSING_TARBALL_INTEGRITY.name to
                " ERR_PNPM_MISSING_TARBALL_INTEGRITY  GET https://x",
            PluginFailureVerdict.Kind.TARBALL_URL_MISMATCH.name to
                " ERR_PNPM_TARBALL_URL_MISMATCH  foo@1.0.0 has a tarball URL that differs",
            PluginFailureVerdict.Kind.PATCH_FAILED.name to
                " ERR_PNPM_PATCH_FAILED  Could not apply patch",
            PluginFailureVerdict.Kind.UNUSED_PATCH.name to
                "The following patches were not used: patches/foo.patch",
            PluginFailureVerdict.Kind.ADDING_TO_ROOT.name to
                " ERR_PNPM_ADDING_TO_ROOT ",
            PluginFailureVerdict.Kind.NOT_A_WORKSPACE.name to
                "--workspace-root may only be used inside a workspace",
            PluginFailureVerdict.Kind.RELEASE_AGE_VIOLATION.name to
                " ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION ",
            PluginFailureVerdict.Kind.RELEASE_AGE_VIOLATION.name to
                " ERR_PNPM_NO_MATURE_MATCHING_VERSION ",
            PluginFailureVerdict.Kind.NATIVE_OOM.name to
                "memory allocation of 5368709120 bytes failed",
            PluginFailureVerdict.Kind.UNPARSEABLE_BUILD_KEY.name to
                " ERR_PNPM_INVALID_VERSION_UNION  Found: \"foo@git+https://github.com/a/b\"",
            PluginFailureVerdict.Kind.SSH_AUTH_FAILED.name to
                "git@github.com: Permission denied (publickey).",
            PluginFailureVerdict.Kind.GIT_PREPARE_NOT_ALLOWED.name to
                " ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED ",
            PluginFailureVerdict.Kind.GIT_PREPARE_FAILED.name to
                "Failed to prepare git-hosted package fetched from \"https://codeload.github.com/a/b/tar.gz/sha\": boom",
            PluginFailureVerdict.Kind.NO_MATCHING_VERSION.name to
                " ERR_PNPM_NO_MATCHING_VERSION  No matching version found for foo@^9",
            PluginFailureVerdict.Kind.LOCKFILE_OUTDATED.name to
                " ERR_PNPM_OUTDATED_LOCKFILE ",
            PluginFailureVerdict.Kind.LOCKFILE_BROKEN.name to
                " ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY  Broken lockfile: no entry for 'foo@1.0.0'",
            PluginFailureVerdict.Kind.PROFILE_FILE_LOCKED.name to
                "ERR_PNPM_EPERM: operation not permitted, rename '/p/package.json.1234' -> '/p/package.json'",
            PluginFailureVerdict.Kind.WINDOWS_FILE_LOCKED.name to
                "EPERM: operation not permitted, unlink 'node_modules\\\\@scope\\\\foo_tmp_7788'",
            PluginFailureVerdict.Kind.PNPM_UNUSABLE.name to
                "spawn pnpm EACCES",
        )
        for ((expected, sample) in cases) {
            assertEquals("样例未被识别：$sample", expected, PnpmFailureText.classify(sample)?.kind?.name)
        }
    }

    @Test
    fun missingLocalDependencyNeedsThePnpmContextSentence() {
        val withContext = "ENOENT: no such file or directory, open '/x/y/package.json'\n" +
            "while installing a direct dependency of the workspace"
        assertEquals(
            PluginFailureVerdict.Kind.MISSING_LOCAL_DEPENDENCY,
            PnpmFailureText.classify(withContext)?.kind,
        )
        // 只有 ENOENT、没有 pnpm 的上下文句时不该归到这一类。
        val withoutContext = "ENOENT: no such file or directory, open '/x/y/package.json'"
        assertNull(PnpmFailureText.classify(withoutContext))
    }

    @Test
    fun exactVersionAndRangeMismatchAreSeparated() {
        val output = " ERR_PNPM_NO_MATCHING_VERSION  No matching version found"
        assertEquals(
            "没给请求形态时只能按区间无匹配置信",
            PluginFailureVerdict.Kind.NO_MATCHING_VERSION.name,
            PnpmFailureText.classify(output)?.kind?.name,
        )
        assertEquals(
            "精确版本应单独一类，提示「镜像还没同步」",
            PluginFailureVerdict.Kind.EXACT_VERSION_MISSING.name,
            PnpmFailureText.classify(output, "foo@1.2.3")?.kind?.name,
        )
        assertEquals(
            "@latest 不是精确版本，仍按区间无匹配",
            PluginFailureVerdict.Kind.NO_MATCHING_VERSION.name,
            PnpmFailureText.classify(output, "foo@latest")?.kind?.name,
        )
        assertEquals(
            "作用域包的精确版本也要认出来",
            PluginFailureVerdict.Kind.EXACT_VERSION_MISSING.name,
            PnpmFailureText.classify(output, "@scope/foo@0.2.1-rc.2")?.kind?.name,
        )
    }

    @Test
    fun specificCodesAreNotSwallowedByTheGenericFallback() {
        // 输出里同时出现具体码与一个未归类的码时，具体码必须胜出。
        val samples = listOf(
            " ERR_PNPM_UNEXPECTED_STORE  ... ERR_PNPM_SOMETHING_ELSE" to
                PluginFailureVerdict.Kind.UNEXPECTED_STORE.name,
            " ERR_PNPM_OUTDATED_LOCKFILE  ... ERR_PNPM_WHATEVER" to
                PluginFailureVerdict.Kind.LOCKFILE_OUTDATED.name,
            " ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED  ... ERR_PNPM_OTHER" to
                PluginFailureVerdict.Kind.GIT_PREPARE_NOT_ALLOWED.name,
        )
        for ((sample, expected) in samples) {
            assertEquals(expected, PnpmFailureText.classify(sample)?.kind?.name)
        }
    }

    @Test
    fun everyKindCarriesABilingualMessage() {
        // 每一条新增文案都必须是「中文 / English」，否则 UI 的 localizeBilingual 会拿不到可分的一半。
        val samples = listOf(
            "printed nothing for 600000ms and was terminated",
            "timed out waiting for the writer lock at \"/x\"",
            " ERR_PNPM_PUBLIC_HOIST_PATTERN_DIFF ",
            " ERR_PNPM_UNEXPECTED_STORE ",
            " ERR_PNPM_MISSING_TARBALL_INTEGRITY ",
            " ERR_PNPM_TARBALL_URL_MISMATCH ",
            " ERR_PNPM_PATCH_FAILED ",
            "The following patches were not used: x",
            " ERR_PNPM_ADDING_TO_ROOT ",
            "--workspace-root may only be used inside a workspace",
            " ERR_PNPM_MINIMUM_RELEASE_AGE_VIOLATION ",
            "memory allocation of 123 bytes failed",
            " ERR_PNPM_INVALID_VERSION_UNION ",
            "Permission denied (publickey)",
            " ERR_PNPM_GIT_DEP_PREPARE_NOT_ALLOWED ",
            "Failed to prepare git-hosted package fetched from \"https://x\"",
            " ERR_PNPM_NO_MATCHING_VERSION ",
            " ERR_PNPM_OUTDATED_LOCKFILE ",
            " ERR_PNPM_LOCKFILE_MISSING_DEPENDENCY ",
            "ERR_PNPM_EPERM rename '/p/package.json.1' -> '/p/package.json'",
            "unlink 'node_modules\\\\@a\\\\b_tmp_1'",
            "spawn pnpm EACCES",
        )
        for (sample in samples) {
            val message = PnpmFailureText.classify(sample, "foo@1.2.3")?.message
            assertEquals("缺少双语分隔：$sample", true, message != null && message.contains(" / "))
        }
    }
}
