package com.dshbox.app.sandbox

import com.dshbox.app.common.AppResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** DshLayer.rollbackToPrevious 的原子互换与单副本不变量。 */
class DshLayerRollbackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var runtimeDir: File
    private lateinit var layer: DshLayer

    @Before
    fun setUp() {
        runtimeDir = File(tmp.root, "runtime")
        layer = DshLayer(runtimeDir, BundleManager(SandboxConfig(appFilesDir = tmp.root)))
    }

    private fun makeLayer(dir: File, version: String) {
        File(dir, DshLayer.DSHPK_GUEST_PATH).apply {
            parentFile?.mkdirs()
            writeText("// bin $version")
        }
        File(dir, DshLayer.VERSION_FILE).apply {
            parentFile?.mkdirs()
            writeText(version)
        }
    }

    @Test
    fun rollback_withoutPrevious_fails() {
        makeLayer(layer.dshDir(), "2.0.0")
        val result = layer.rollbackToPrevious()
        assertTrue(result is AppResult.Failure)
        assertEquals("DSH_NO_PREVIOUS", (result as AppResult.Failure).error.code)
        assertTrue(File(layer.dshDir(), DshLayer.DSHPK_GUEST_PATH).isFile)
    }

    @Test
    fun rollback_invalidPrevious_fails() {
        makeLayer(layer.dshDir(), "2.0.0")
        File(layer.previousDshDir(), "junk").apply { parentFile?.mkdirs(); writeText("x") }
        val result = layer.rollbackToPrevious()
        assertTrue(result is AppResult.Failure)
        assertEquals("DSH_NO_PREVIOUS", (result as AppResult.Failure).error.code)
    }

    @Test
    fun rollback_swapsAndKeepsSinglePrevious() {
        makeLayer(layer.dshDir(), "2.0.0")
        makeLayer(layer.previousDshDir(), "1.0.0")

        val result = layer.rollbackToPrevious()
        assertTrue(result is AppResult.Success)
        assertEquals("1.0.0", (result as AppResult.Success).value.version)
        assertEquals("1.0.0", layer.installedVersion())
        assertEquals("// bin 1.0.0", File(layer.dshDir(), DshLayer.DSHPK_GUEST_PATH).readText())
        // 被换下的当前层成为新的 previous（单副本）。
        assertEquals("// bin 2.0.0", File(layer.previousDshDir(), DshLayer.DSHPK_GUEST_PATH).readText())
        assertFalse(File(runtimeDir, "dsh-rollback-aside").exists())
    }

    @Test
    fun rollback_withoutCurrent_promotesPrevious() {
        makeLayer(layer.previousDshDir(), "1.0.0")
        val result = layer.rollbackToPrevious()
        assertTrue(result is AppResult.Success)
        assertEquals("1.0.0", layer.installedVersion())
        assertTrue(File(layer.dshDir(), DshLayer.DSHPK_GUEST_PATH).isFile)
    }

    @Test
    fun rollback_recoversLeftoverAsideWhenCurrentMissing() {
        // 上次失败残留：aside 持有当前层唯一副本，现役层缺失。回滚应先恢复再互换，不丢数据。
        makeLayer(File(runtimeDir, "dsh-rollback-aside"), "2.0.0")
        makeLayer(layer.previousDshDir(), "1.0.0")
        val result = layer.rollbackToPrevious()
        assertTrue(result is AppResult.Success)
        assertEquals("1.0.0", layer.installedVersion())
        // 被恢复又换下的 2.0.0 成为新 previous，aside 不残留。
        assertEquals("// bin 2.0.0", File(layer.previousDshDir(), DshLayer.DSHPK_GUEST_PATH).readText())
        assertFalse(File(runtimeDir, "dsh-rollback-aside").exists())
    }
}