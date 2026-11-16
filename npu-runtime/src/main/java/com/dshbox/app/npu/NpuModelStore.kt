package com.dshbox.app.npu

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 模型与运行时的存放约定。
 *
 * ## 布局
 *
 * ```
 * <filesDir>/npu/
 *   ├─ models/<modelId>/
 *   │    ├─ manifest.json          模型清单（分段字节数、QAIRT 版本）
 *   │    ├─ part1_of_4.bin …       ctx-bin 各段
 *   │    ├─ cfg32.json             由 NpuConfigBuilder 生成
 *   │    └─ htp_backend_ext_config.json
 * ```
 *
 * ## 为什么用「精确字节数」校验
 *
 * ctx-bin 是分段的（4B 模型 4 段、3B 模型 3 段）。只判断「文件存在」无法区分
 * 「完整的 4B」与「下载了一半的 3B」——历史上正因此把 4B 的数据误记成 1B。
 * 字节数是唯一可靠的判据。
 */
class NpuModelStore(
    private val context: Context,
    val modelId: String,
) {

    companion object {
        private const val TAG = "NpuModelStore"

        /** 模型清单文件名。 */
        const val MANIFEST = "manifest.json"

        /** 生成配置的目录名。 */
        const val CFG_NAME = "cfg32.json"

        /** HTP 扩展配置名。 */
        const val EXT_NAME = "htp_backend_ext_config.json"
    }

    /**
     * 模型清单。
     *
     * @param ctxBinFiles ctx-bin 分段名 → **精确字节数**。
     * @param qairtVersion 编译该 ctx-bin 的 QAIRT 版本（用于版本兼容性预检）。
     * @param socModel 目标 SoC 模型号（必须与设备一致）。
     * @param dspArch 目标 DSP 架构（如 "v81"）。
     */
    data class Manifest(
        val ctxBinFiles: Map<String, Long>,
        val qairtVersion: String?,
        val socModel: Int?,
        val dspArch: String?,
    )

    val rootDir: File = File(context.filesDir, "npu")
    val modelDir: File = File(rootDir, "models/$modelId")

    private var cachedManifest: Manifest? = null

    /** 读取清单；不存在时返回 null。 */
    fun loadManifest(): Manifest? {
        cachedManifest?.let { return it }
        val f = File(modelDir, MANIFEST)
        if (!f.isFile) return null
        return try {
            val obj = org.json.JSONObject(f.readText())
            val files = org.json.JSONObject(obj.optString("ctxBinFiles", "{}"))
            val map = LinkedHashMap<String, Long>()
            files.keys().forEach { k -> map[k] = files.optLong(k, -1L) }
            val m = Manifest(
                ctxBinFiles = map,
                qairtVersion = obj.optString("qairtVersion").takeIf { it.isNotBlank() },
                socModel = if (obj.has("socModel")) obj.optInt("socModel") else null,
                dspArch = obj.optString("dspArch").takeIf { it.isNotBlank() },
            )
            cachedManifest = m
            m
        } catch (e: Exception) {
            Log.e(TAG, "读取清单失败", e)
            null
        }
    }

    /** 模型分段完整性检查（精确字节数）。 */
    fun checkFiles(): NpuPreflight.Result {
        val m = loadManifest() ?: return NpuPreflight.Result.fail("模型清单缺失：$MANIFEST")
        return NpuPreflight.checkModelFiles(m.ctxBinFiles, modelDir)
    }

    /**
     * 版本兼容性预检。
     *
     * 运行时版本取自 native 侧报告的 API 版本（若不可得则跳过预检，只告警）。
     */
    fun checkVersion(): NpuPreflight.Result {
        val m = loadManifest() ?: return NpuPreflight.Result.fail("模型清单缺失：$MANIFEST")
        return NpuPreflight.checkVersionCompatibility(m.qairtVersion, runtimeApiVersion())
    }

    /**
     * 运行时 API 版本。读不到时返回 null（预检降级为告警）。
     *
     * 从 native 层查询并缓存到 [NpuRuntimeInfo.apiVersion]。
     */
    fun runtimeApiVersion(): String? {
        NpuRuntimeInfo.apiVersion?.let { return it }
        val v = NpuNative.runtimeApiVersion()
        if (!v.isNullOrBlank()) {
            NpuRuntimeInfo.apiVersion = v
        }
        return v
    }

    /** 可用内存字节数。 */
    fun availableMemoryBytes(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.availMem
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }

    /** 生成并写入 cfg / ext 两个配置文件。 */
    fun writeConfigs(maxTokens: Int): NpuPreflight.Result {
        val m = loadManifest() ?: return NpuPreflight.Result.fail("模型清单缺失：$MANIFEST")
        if (!modelDir.isDirectory && !modelDir.mkdirs()) {
            return NpuPreflight.Result.fail("无法创建模型目录：${modelDir.absolutePath}")
        }
        return try {
            val socModel = m.socModel ?: deviceSocModel()
            val arch = m.dspArch ?: deviceDspArch()
            File(modelDir, CFG_NAME).writeText(
                NpuConfigBuilder.buildDialogConfig(modelDir, maxTokens),
            )
            File(modelDir, EXT_NAME).writeText(
                NpuConfigBuilder.buildBackendExt(socModel, arch),
            )
            NpuPreflight.Result.ok("配置已生成（soc_model=$socModel dsp_arch=$arch）")
        } catch (e: Exception) {
            Log.e(TAG, "生成配置失败", e)
            NpuPreflight.Result.fail("生成配置失败：${e.message}")
        }
    }

    /** dialog 配置路径。 */
    fun dialogConfigPath(): String = File(modelDir, CFG_NAME).absolutePath

    /** 设备 SoC 模型号。 */
    fun deviceSocModel(): Int = NpuRuntimeInfo.socModel

    /** 设备 DSP 架构。 */
    fun deviceDspArch(): String = NpuRuntimeInfo.dspArch
}

/**
 * 设备侧的运行时信息。
 *
 * 这里刻意**不**读取 `/sys/devices/soc0/sku` 等节点：实测 appdomain 对
 * `vendor_sysfs_soc` 只有受限权限（读被 AVC 拒绝）。
 * 所以 SoC 型号走 `Build.SOC_MODEL`（SDK 31+ 提供），DSP 架构由已知映射推导。
 */
object NpuRuntimeInfo {

    /**
     * 已知 SoC → HTP 架构的映射。
     *
     * 仅收录已实测确认的组合；未收录时回退到 [UNKNOWN_ARCH]，
     * 让预检失败而不是猜一个错的架构去建图。
     */
    private val SOC_TO_ARCH = mapOf(
        "SM8750" to "v79",   // 8 Elite
        "SM8850" to "v81",   // 8 Elite Gen 5
        "SM8650" to "v75",   // 8 Gen 3
        "SM8550" to "v73",   // 8 Gen 2
    )

    const val UNKNOWN_ARCH = "unknown"

    /** QNN 的 SoC 模型号（与 QNN_SOC_MODEL_* 枚举一致）。 */
    private val SOC_NAME_TO_MODEL = mapOf(
        "SM8550" to 69,
        "SM8650" to 75,
        "SM8750" to 79,
        "SM8850" to 87,
    )

    val socName: String = Build.SOC_MODEL ?: Build.HARDWARE ?: ""

    val socModel: Int = SOC_NAME_TO_MODEL[socName] ?: -1

    val dspArch: String = SOC_TO_ARCH[socName] ?: UNKNOWN_ARCH

    /**
     * 运行时 API 版本。
     *
     * 由 native 层在加载厂商库后报告（厂商运行时未加载时为 null）。
     */
    var apiVersion: String? = null
        internal set
}
