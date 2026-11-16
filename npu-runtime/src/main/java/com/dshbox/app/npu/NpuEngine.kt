package com.dshbox.app.npu

import android.content.Context
import android.util.Log

/**
 * NPU 推理的门面：上层（App / 插件）只需与这个类打交道。
 *
 * 职责边界：
 *  - [NpuModelStore]：模型与配置的存放、完整性校验；
 *  - [NpuPreflight]：进入 DSP 之前的纯检查（内存 / 文件 / 版本）；
 *  - [NpuSupervisor]：独立进程推理 + 看门狗 + 卡死恢复；
 *  - [NpuInferenceService]：`:npu` 进程里真正干活的地方；
 *  - [NpuNative]：native 桥接。
 *
 * ## 使用前提
 *
 * 设备必须具备对应的 Qualcomm 运行时（QNN HTP + Genie）。不具备时
 * [isAvailable] 返回 false，调用方应**回退到 CPU 路径**，而不是报错。
 *
 * ## 线程
 *
 * [generate] 会阻塞（内部有独立进程 + 超时控制），应在**工作线程**调用。
 */
class NpuEngine(
    context: Context,
    private val modelId: String,
) {

    companion object {
        private const val TAG = "NpuEngine"
    }

    private val appContext = context.applicationContext

    private val modelStore = NpuModelStore(appContext, modelId)

    private val supervisor = NpuSupervisor(appContext, modelStore)

    /**
     * NPU 是否可用。
     *
     * 判据是 native 库能载入**且**能读到厂商运行时版本——后者才能确认
     * 设备上确有可用的 QNN HTP 栈（而不是只有一个空壳 so）。
     */
    fun isAvailable(): Boolean {
        val ok = NpuNative.ensureLoaded()
        if (!ok) {
            Log.i(TAG, "NPU 不可用：native 库未载入")
            return false
        }
        val ver = NpuNative.runtimeApiVersion()
        if (ver.isNullOrBlank()) {
            Log.i(TAG, "NPU 不可用：读不到厂商运行时版本")
            return false
        }
        Log.i(TAG, "NPU 可用：运行时 API 版本 $ver")
        return true
    }

    /**
     * 推理前的前置检查。
     *
     * 返回 fatal 的结论时**不应**继续调用 [generate]——那会浪费一次
     * 数 GB 的权重加载，且有挂死风险。
     */
    fun preflight(maxTokens: Int = 64): NpuPreflight.Result {
        val mem = modelStore.availableMemoryBytes()
        val memResult = NpuPreflight.checkMemory(mem)
        if (!memResult.ok) return memResult

        val files = modelStore.checkFiles()
        if (!files.ok) return files

        val version = modelStore.checkVersion()
        if (!version.ok) return version

        val cfg = modelStore.writeConfigs(maxTokens)
        if (!cfg.ok) return cfg

        return NpuPreflight.ok(
            "前置检查通过（内存 ${mem / 1024 / 1024} MB，${modelStore.loadManifest()?.ctxBinFiles?.size ?: 0} 段 ctx-bin）",
            mem,
        )
    }

    /**
     * 跑一次推理。
     *
     * @param prompt 提示词。
     * @param maxTokens 最大生成 token 数。
     * @return 推理结论；失败时 [Outcome.status] 为负值。
     */
    fun generate(prompt: String, maxTokens: Int = 64): NpuSupervisor.Outcome {
        // 监督器内部会先做前置检查（内存 / 文件 / 版本），不通过就不进 DSP。
        return supervisor.generate(prompt, maxTokens)
    }

    /** 释放看门狗等长驻资源。 */
    fun release() {
        supervisor.release()
    }
}
