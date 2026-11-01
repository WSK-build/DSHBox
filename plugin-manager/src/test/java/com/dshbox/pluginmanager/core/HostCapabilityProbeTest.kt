package com.dshbox.pluginmanager.core

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主能力探测的生产实现（文件系统接线的部分）。
 *
 * 契约判定本身由 [com.dshbox.pluginmanager.contract.HostContractSentinelTest] 覆盖；
 * 这里只验证**探测是否读对了位置**——它读错目录，能力判定再对也没用。
 */
class HostCapabilityProbeTest {

    private fun tempRoot(): java.io.File = Files.createTempDirectory("hostprobe").toFile()

    private fun layout(
        root: java.io.File,
        pluginManager: Boolean = false,
        compatibilityFile: Boolean = false,
    ): Pair<java.io.File, java.io.File> {
        val runtime = java.io.File(root, "runtime/runtime-current")
        val profile = java.io.File(root, "profiles/web")
        profile.mkdirs()
        if (pluginManager) {
            // 两个可能的位置都要能被认出来；这里放 DSH 层自带那个。
            java.io.File(runtime, "dsh/node_modules/@deepseek-ai/dsh-plugin-manager").mkdirs()
        }
        if (compatibilityFile) {
            java.io.File(profile, "compatibility.json").writeText("{}", Charsets.UTF_8)
        }
        return runtime to profile
    }

    @Test
    fun `探测到 plugin-manager 包与豁免文件时对应能力为真`() {
        val root = tempRoot()
        val (runtime, profile) = layout(root, pluginManager = true, compatibilityFile = true)
        val capabilities = HostCapabilityProbe(runtime, profile).detect()
        assertTrue(capabilities.hasOfficialPluginCommand)
        assertTrue(capabilities.enforcesVersionCompatibility)
        assertTrue(capabilities.acceptsPatchArray)
    }

    @Test
    fun `空布局下只有本地痕迹对应的能力为真`() {
        val root = tempRoot()
        val (runtime, profile) = layout(root)
        val capabilities = HostCapabilityProbe(runtime, profile).detect()
        assertFalse(capabilities.hasOfficialPluginCommand)
        assertFalse(capabilities.enforcesVersionCompatibility)
        assertTrue(capabilities.acceptsPatchArray)
    }

    @Test
    fun `整棵依赖树布局下的 plugin-manager 也能被认出`() {
        val root = tempRoot()
        val runtime = java.io.File(root, "runtime/runtime-current")
        val profile = java.io.File(root, "profiles/web").apply { mkdirs() }
        // 另一种打包方式：依赖树在运行层根，而不是 dsh 层内。
        java.io.File(runtime, "node_modules/@deepseek-ai/dsh-plugin-manager").mkdirs()
        assertTrue(HostCapabilityProbe(runtime, profile).detect().hasOfficialPluginCommand)
    }

    @Test
    fun `能力只由本地痕迹决定，与宿主版本无关`() {
        val root = tempRoot()
        // 同一布局下无论宿主多旧，都没有版本门槛参与判定。
        val (runtime, profile) = layout(root, pluginManager = false, compatibilityFile = true)
        val capabilities = HostCapabilityProbe(runtime, profile).detect()
        assertTrue("豁免文件在即认定门禁启用", capabilities.enforcesVersionCompatibility)
        assertFalse("无本地痕迹的能力不得凭版本推断", capabilities.skipsFailedEntries)
    }
}
