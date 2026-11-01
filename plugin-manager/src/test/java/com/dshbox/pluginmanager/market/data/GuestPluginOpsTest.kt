package com.dshbox.pluginmanager.market.data

import com.dshbox.app.common.AppResult
import com.dshbox.pluginmanager.core.GuestCommandResult
import com.dshbox.pluginmanager.core.GuestCommandRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * guest 命令的构造与目标校验。
 *
 * 安装目标会被拼进命令行（`… add '<target>'`），所以**以 `-` / `=` 开头的目标
 * 必须显式拒绝**：那会被 CLI 当成选项而不是包名（`--registry=…`、`=foo`），
 * 命令要么被劫持、要么报一个与用户操作无关的错。
 */
class GuestPluginOpsTest {

    private class RecordingRunner : GuestCommandRunner {
        val commands = mutableListOf<String>()
        override suspend fun run(command: String, onLine: (String) -> Unit): GuestCommandResult {
            commands.add(command)
            return GuestCommandResult(true, emptyList())
        }
    }

    private fun ops(runner: RecordingRunner) = GuestPluginOps(runner, "web")

    @Test
    fun targetValidationAcceptsNormalPackageShapes() {
        assertTrue(GuestPluginOps.isValidTarget("foo"))
        assertTrue(GuestPluginOps.isValidTarget("@scope/foo@^1.0.0"))
        assertTrue(GuestPluginOps.isValidTarget("github:owner/repo#main"))
        assertTrue(GuestPluginOps.isValidTarget("https://example.com/pkg-1.0.0.tgz"))
    }

    @Test
    fun targetStartingWithDashOrEqualsIsRejected() {
        assertFalse("以 - 开头的目标会被 CLI 当成选项", GuestPluginOps.isValidTarget("-foo"))
        assertFalse(GuestPluginOps.isValidTarget("--registry=http://evil"))
        assertFalse("以 = 开头的目标会被 CLI 当成选项", GuestPluginOps.isValidTarget("=foo"))
        assertFalse(GuestPluginOps.isValidTarget(""))
        assertFalse("空白与 shell 元字符仍被拒绝", GuestPluginOps.isValidTarget("foo; rm -rf /"))
    }

    @Test
    fun optionLikeTargetIsRejectedBeforeAnythingRuns() = runBlocking {
        val runner = RecordingRunner()
        val result = ops(runner).add("-foo", onLine = {})
        assertTrue(result is AppResult.Failure)
        assertEquals("TARGET_REJECTED", (result as AppResult.Failure).error.code)
        assertTrue("被拒绝的目标绝不该进命令行", runner.commands.isEmpty())
    }

    @Test
    fun validTargetIsForwardedWithTheProfileFlag() = runBlocking {
        val runner = RecordingRunner()
        assertTrue(ops(runner).add("foo@1.0.0", onLine = {}) is AppResult.Success)
        assertEquals(1, runner.commands.size)
        assertTrue(runner.commands.single().contains("add 'foo@1.0.0'"))
        assertTrue(runner.commands.single().contains("--profile 'web'"))
    }

    @Test
    fun updateAlwaysTargetsLatestOfTheRealName() = runBlocking {
        val runner = RecordingRunner()
        ops(runner).update("real-pkg", onLine = {})
        assertTrue(runner.commands.single().contains("add 'real-pkg@latest'"))
    }

    @Test
    fun workspaceProfileInjectsDashWOnlyForWorkspaceVerbs() = runBlocking {
        // workspace root 上的 add/remove 必须 `-w`（上游 pluginArgsFor 的语义）；
        // install/why 等动词不注入。
        val runner = RecordingRunner()
        val ops = GuestPluginOps(runner, "web", isWorkspaceProfile = { true })
        assertTrue(ops.add("foo", onLine = {}) is AppResult.Success)
        assertTrue(runner.commands.last().contains("add -w 'foo'"))
        assertTrue(ops.remove("foo", onLine = {}) is AppResult.Success)
        assertTrue(runner.commands.last().contains("remove -w 'foo'"))
    }

    @Test
    fun nonWorkspaceProfileNeverInjectsDashW() = runBlocking {
        val runner = RecordingRunner()
        val ops = GuestPluginOps(runner, "web", isWorkspaceProfile = { false })
        assertTrue(ops.add("foo", onLine = {}) is AppResult.Success)
        assertFalse(runner.commands.single().contains(" -w "))
    }

    @Test
    fun installCommandCarriesASoftTimeout() = runBlocking {
        // pnpm 静默挂住时命令要有明确终点：timeout 前缀必须始终存在。
        val runner = RecordingRunner()
        assertTrue(ops(runner).add("foo", onLine = {}) is AppResult.Success)
        assertTrue(runner.commands.single().startsWith("timeout "))
    }

    @Test
    fun subpathSelectorRejectsEmptyAndTraversalSegments() {
        // `#path:` 由宿主转发给 pnpm 做子目录安装：空段与 `..` 段会让落点逃出预期。
        assertTrue(GuestPluginOps.hasValidSubpath("github:o/r#path:/sub"))
        assertTrue(GuestPluginOps.hasValidSubpath("github:o/r"))
        assertFalse("空段", GuestPluginOps.hasValidSubpath("github:o/r#path://x"))
        assertFalse("父目录段", GuestPluginOps.hasValidSubpath("github:o/r#path:/../escape"))
        assertFalse("点段", GuestPluginOps.hasValidSubpath("github:o/r#path:/./x"))
    }

    @Test
    fun traversalSubpathIsRejectedBeforeAnythingRuns() = runBlocking {
        val runner = RecordingRunner()
        val result = ops(runner).add("github:o/r#path:/../x", onLine = {})
        assertTrue(result is AppResult.Failure)
        assertEquals("TARGET_REJECTED", (result as AppResult.Failure).error.code)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun bareDotDotSegmentsAreRejectedAtTargetLevel() {
        // 不带 #path: 的裸 `..` 段（含整个目标就是 `..`）会把 profile 上层目录
        // 当安装对象，目标级显式拒绝。
        assertFalse(GuestPluginOps.isValidTarget(".."))
        assertFalse(GuestPluginOps.isValidTarget("../x"))
        assertFalse(GuestPluginOps.isValidTarget("a/../b"))
        assertTrue(GuestPluginOps.isValidTarget("github:owner/repo#path:/sub/x"))
    }
}
