package com.dshbox.app.npu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 配置生成器的单元测试。
 *
 * 这些断言对应「填错不报错、只是变慢或挂死」的字段：
 *  - `poll` 必须为 false（true 时退化为 0.3 char/s 且 query 返回 -6）；
 *  - `rpc_control_latency` 必须 ≥ 15（实测 5 时可复现挂死）；
 *  - `extensions` 必须是绝对路径（相对路径在 App 私有目录下解析不到）。
 */
class NpuConfigBuilderTest {

    private val modelDir = File("/data/data/com.dshbox.app/files/npu/models/qwen3-4b")

    @Test
    fun `poll 必须为 false`() {
        val json = NpuConfigBuilder.buildDialogConfig(modelDir)
        assertTrue(json.contains("\"poll\": false"))
        assertFalse(json.contains("\"poll\": true"))
    }

    @Test
    fun `extensions 必须是绝对路径`() {
        val json = NpuConfigBuilder.buildDialogConfig(modelDir)
        assertTrue(json.contains("\"${modelDir.absolutePath}/htp_backend_ext_config.json\""))
    }

    @Test
    fun `perf_profile 为 burst 且 rpc_control_latency 不低于 15`() {
        val json = NpuConfigBuilder.buildBackendExt(87, "v81")
        assertTrue(json.contains("\"perf_profile\": \"burst\""))
        val m = Regex("\"rpc_control_latency\": (\\d+)").find(json)
        assertTrue("未找到 rpc_control_latency", m != null)
        val latency = m!!.groupValues[1].toInt()
        assertTrue("rpc_control_latency=$latency 过低，实测 5 时可复现挂死", latency >= 15)
    }

    @Test
    fun `soc_model 与 dsp_arch 写入正确`() {
        val json = NpuConfigBuilder.buildBackendExt(87, "v81")
        assertTrue(json.contains("\"soc_model\": 87"))
        assertTrue(json.contains("\"dsp_arch\": \"v81\""))
    }

    @Test
    fun `生成的配置是可解析的 JSON`() {
        val cfg = org.json.JSONObject(NpuConfigBuilder.buildDialogConfig(modelDir))
        val engine = cfg.getJSONObject("dialog").getJSONObject("engine")
        val backend = engine.getJSONObject("backend")
        assertEquals("QnnHtp", backend.getString("type"))
        assertFalse(backend.getJSONObject("QnnHtp").getBoolean("poll"))

        val ext = org.json.JSONObject(NpuConfigBuilder.buildBackendExt(87, "v81"))
        val devices = ext.getJSONArray("devices")
        assertEquals(1, devices.length())
        assertEquals(87, devices.getJSONObject(0).getInt("soc_model"))
    }
}
