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
}
