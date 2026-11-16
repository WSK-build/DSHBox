package com.dshbox.app.npu

/**
 * 原生层桥接。
 *
 * 只做「调 native + 拆返回值」，不含任何策略；策略在 [NpuEngine] / [NpuInferenceService]。
 */
internal object NpuNative {

    /** 推理阶段。上层的看门狗据此区分「在干活」与「卡住」。 */
    const val STAGE_IDLE = 0
    const val STAGE_LOADING = 1
    const val STAGE_CREATING = 2
    const val STAGE_GENERATING = 3
    const val STAGE_DONE = 4

    /**
     * 一次推理的结果。
     *
     * @param status 0 表示成功；负值为失败码（见 native 层各分支）。
     * @param tokens 回调次数（≈ token 数）。
     * @param text 生成的文本。
     */
    data class Result(val status: Int, val tokens: Int, val text: String)

    /** 进度快照。[lastProgressAgeMs] 是「距离最近一次推进过了多久」，看门狗的核心判据。 */
    data class Progress(val stage: Int, val tokens: Int, val lastProgressAgeMs: Long)

    @Suppress("unused") private external fun nativeRun(cfgPath: String, prompt: String): String
    @Suppress("unused") private external fun nativeProgress(): LongArray
    @Suppress("unused") private external fun nativeRelease()
    @Suppress("unused") private external fun nativeRuntimeApiVersion(): String

    @Volatile
    private var loaded = false

    /**
     * 载入 native 库。
     *
     * 失败时返回 false 而不是抛异常：设备上没有厂商运行时（无 Genie / 无对应 SoC）
     * 是**正常**状态，不该让调用方崩在类初始化上。
     */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("npu-genie")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * 运行时 QNN API 版本（如 "2.40.0"）；不可得时返回 null。
     *
     * 必须**运行时**查询：SDK 头文件里的 `QNN_API_VERSION_*` 宏（2.27）
     * 与设备实际运行时（实测 2.40）不是一回事。
     */
    fun runtimeApiVersion(): String? {
        if (!ensureLoaded()) return null
        return try {
            nativeRuntimeApiVersion().takeIf { it.isNotBlank() }
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    /**
     * 跑一次单轮推理。
     *
     * **阻塞调用**：native 里的 Genie 调用不吃线程中断，所以调用方必须
     * 保证它在可被杀的独立进程里跑（见 [NpuInferenceService]）。
     */
    fun run(cfgPath: String, prompt: String): Result {
        if (!ensureLoaded()) return Result(-100, 0, "native 库不可用")
        val raw = try {
            nativeRun(cfgPath, prompt)
        } catch (e: UnsatisfiedLinkError) {
            return Result(-100, 0, "native 调用失败: ${e.message}")
        }
        // 返回格式：status \t tokens \t text
        val tab1 = raw.indexOf('\t')
        if (tab1 < 0) return Result(-101, 0, raw)
        val tab2 = raw.indexOf('\t', tab1 + 1)
        if (tab2 < 0) return Result(-101, 0, raw)
        val status = raw.substring(0, tab1).toIntOrNull() ?: return Result(-101, 0, raw)
        val tokens = raw.substring(tab1 + 1, tab2).toIntOrNull() ?: 0
        val text = raw.substring(tab2 + 1)
        return Result(status, tokens, text)
    }

    fun progress(): Progress {
        if (!ensureLoaded()) return Progress(STAGE_IDLE, 0, 0)
        val arr = try {
            nativeProgress()
        } catch (e: UnsatisfiedLinkError) {
            return Progress(STAGE_IDLE, 0, 0)
        }
        if (arr.size < 3) return Progress(STAGE_IDLE, 0, 0)
        return Progress(arr[0].toInt(), arr[1].toInt(), arr[2])
    }

    /** 释放当前持有的 Genie 句柄。正常收尾时调用，避免 DSP 设备被独占住。 */
    fun release() {
        if (!loaded) return
        runCatching { nativeRelease() }
    }
}
