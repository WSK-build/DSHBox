package com.dshbox.pluginmanager.repair

import com.dshbox.app.common.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OpenCodeTool] 的命令构造不变量。
 *
 * 这几条命令直接决定"用户点了按钮会动到设备上的哪些文件"，因此锁的是**破坏性顺序**与**判据来源**：
 * 更新失败不能破坏旧版本、搬运必须发生在一份新装就绪之后、安装态不能拿常驻的命令包装脚本当判据。
 */
class OpenCodeToolTest {

    private val dir = Constants.OPENCODE_GUEST_DIR
    private val staging = Constants.OPENCODE_STAGING_GUEST_DIR

    @Test
    fun registriesPutTheMirrorFirstAndKeepTheOfficialAsFallback() {
        assertEquals(2, OpenCodeTool.registries.size)
        assertTrue("镜像应排在首位，实得 ${OpenCodeTool.registries}", OpenCodeTool.registries.first().contains("npmmirror"))
        assertTrue(
            "官方源应作为兜底，实得 ${OpenCodeTool.registries}",
            OpenCodeTool.registries.last().contains("registry.npmjs.org"),
        )
    }

    @Test
    fun installRunsInStagingThenMovesIntoPlace() {
        val script = OpenCodeTool.installScript("https://example.invalid")

        val cleanStaging = script.indexOf("rm -rf $staging")
        val npm = script.indexOf("npm install --prefix $staging")
        val cleanFinal = script.indexOf("rm -rf $dir")
        val move = script.indexOf("mv $staging $dir")

        assertTrue("脚本应清掉上次残留的暂存目录", cleanStaging >= 0)
        assertTrue("装到暂存目录（而不是直接装到正式位置）", npm >= 0)
        assertTrue("搬运前必须清掉正式位置，否则 mv 会把目录塞进去", cleanFinal >= 0)
        assertTrue("最后一步是同卷改名", move >= 0)
        assertTrue(cleanStaging < npm)
        assertTrue("npm 之后才清正式目录", npm < cleanFinal)
        assertTrue("清完正式目录才改名", cleanFinal < move)
    }

    /**
     * **核心回归**：npm 那一段必须排在"清正式目录"之前，且脚本用 `set -e`。
     *
     * 否则一次失败的更新会先把旧版本删掉、再装不上 → 用户手里连旧版都没了。
     */
    @Test
    fun failedInstallCannotDestroyTheExistingInstall() {
        val script = OpenCodeTool.installScript("https://example.invalid")

        assertTrue("缺少 set -e：npm 失败后仍会继续执行搬运", script.startsWith("set -e;"))
        assertTrue(
            "npm 必须在删除正式目录之前，否则失败即丢旧版",
            script.indexOf("npm install --prefix $staging") < script.indexOf("rm -rf $dir"),
        )
        assertTrue("应带上 registry", script.contains("--registry https://example.invalid"))
        assertTrue("应固定包名与最新版", script.contains("${OpenCodeTool.PACKAGE}@latest"))
    }

    @Test
    fun removeOnlyTouchesTheInstallDirectory() {
        assertEquals("rm -rf $dir", OpenCodeTool.removeScript())
        assertTrue("删除不得波及其它目录", !OpenCodeTool.removeScript().contains(staging))
    }

    /** 判据必须来自安装目录，而不是常驻的命令包装脚本。 */
    @Test
    fun probeLooksAtTheInstallDirectoryNotTheShim() {
        val script = OpenCodeTool.probeScript()

        assertTrue(script.contains(OpenCodeTool.launcher))
        assertTrue(script.contains(OpenCodeTool.MARKER_INSTALLED))
        assertTrue(script.contains(OpenCodeTool.MARKER_MISSING))
        assertTrue("不得用 command -v（包装脚本常驻，会永远报已安装）", !script.contains("command -v"))
    }

    @Test
    fun parseProbeReadsInstalledStateAndVersion() {
        val installed = OpenCodeTool.parseProbe(
            listOf("proot warning: ...", OpenCodeTool.MARKER_INSTALLED, "1.18.32"),
        )
        assertTrue(installed.installed)
        assertEquals("1.18.32", installed.version)

        val missing = OpenCodeTool.parseProbe(listOf("proot warning: ...", OpenCodeTool.MARKER_MISSING))
        assertEquals(false, missing.installed)
        assertNull(missing.version)
    }

    /** 版本行缺失或夹杂噪声时仍应判为"已安装"，只是没有版本号。 */
    @Test
    fun parseProbeToleratesMissingVersion() {
        val state = OpenCodeTool.parseProbe(listOf(OpenCodeTool.MARKER_INSTALLED, "npm notice something"))
        assertTrue(state.installed)
        assertNull(state.version)

        val noisy = OpenCodeTool.parseProbe(listOf(OpenCodeTool.MARKER_INSTALLED, "v1", "2.0.0-rc.1"))
        assertEquals("2.0.0-rc.1", noisy.version)
    }
}
