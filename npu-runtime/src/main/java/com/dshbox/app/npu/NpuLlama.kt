package com.dshbox.app.npu

/**
 * llama.cpp + Qualcomm HTP 的 JNI 绑定。
 *
 * 设计：**引擎可选**。原生库没构建时 [isAvailable] 返回 false，
 * 上层回退到 CPU / 云端，不会因为缺少 DSP 而启动失败。
 *
 * 实测环境：iQOO 15（SM8850 / Hexagon HTP v81 / Android 17）
 */
object NpuLlama {

    /** 只有 build-llama-htp.sh 跑过、jniLibs 里有 npu-llama 时才为 true */
    val loaded: Boolean = runCatching {
        System.loadLibrary("npu-llama"); true
    }.getOrElse { false }

    /** 引擎可用（原生库存在且符号齐全） */
    fun isAvailable(): Boolean = loaded && nativeAvailable()

    /**
     * 初始化：设置 ADSP_LIBRARY_PATH 并枚举后端设备。
     * @param nativeLibraryDir 传 `context.applicationInfo.nativeLibraryDir`
     * @return [ok, devCount, htpPresent]
     */
    fun init(nativeLibraryDir: String): InitResult {
        if (!loaded) return InitResult(false, 0, false)
        val r = nativeInit(nativeLibraryDir)
        // 哪怕 nativeInit 抛错/返回空，也给一个可判定的结果
        return if (r.size >= 3) InitResult(r[0] != 0L, r[1].toInt(), r[2] != 0L)
               else InitResult(false, 0, false)
    }

    /**
     * 生成。
     * @param nGpuLayers 卸载到 DSP 的层数；99 表示全部
     * @param nPredict   生成多少个 token（仅用于测速/短回复）
     */
    fun generate(modelPath: String, prompt: String, nGpuLayers: Int = 99, nPredict: Int = 16): GenResult {
        if (!loaded) return GenResult.fail()
        val r = nativeGenerate(modelPath, prompt, nGpuLayers, nPredict)
        if (r.size < 5 || r[0] == 0L) return GenResult.fail()
        return GenResult(true, r[1].toInt(), r[2], r[3] / 100.0, r[4])
    }

    data class InitResult(val ok: Boolean, val devCount: Int, val htpPresent: Boolean)
    data class GenResult(
        val ok: Boolean,
        val tokens: Int,
        val genMillis: Long,
        val tokensPerSecond: Double,
        val loadMillis: Long,
    ) {
        companion object { fun fail() = GenResult(false, 0, 0, 0.0, 0) }
    }

    private external fun nativeAvailable(): Boolean
    private external fun nativeInit(nativeLibraryDir: String): LongArray
    private external fun nativeGenerate(
        modelPath: String, prompt: String, nGpuLayers: Int, nPredict: Int,
    ): LongArray
}
