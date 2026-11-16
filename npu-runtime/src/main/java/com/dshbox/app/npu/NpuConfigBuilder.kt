package com.dshbox.app.npu

import java.io.File

/**
 * Genie/QNN 推理配置的**生成器**。
 *
 * 为什么用代码生成而不是让用户手写 JSON：这份配置里有若干「填错就静默劣化
 * 或直接挂死」的字段，手写 JSON 极易踩坑，且出错时表现隐蔽（不报错、只是慢）。
 * 下列每一条都来自 iQOO 15 / SM8850 / HTP v81 / Android 17 的实测：
 *
 * | 字段 | 值 | 依据 |
 * |---|---|---|
 * | `poll` | **false** | true 时退化为 0.3 char/s 且 query 返回 -6（22× 差距） |
 * | `rpc_control_latency` | **100** | 5 时可复现挂死；20/50/100 无显著差异，取无风险值 |
 * | `use-mmap` | true | false 无收益，但 mmap 路径在内存压力下更省 |
 * | `perf_profile` | burst | 官方最高功率档，已是顶格 |
 * | `soc_model` / `dsp_arch` | 由 SoC 推导 | 必须与 ctx-bin 编译目标**完全一致**，否则 create 失败 |
 * | `extensions` | **绝对路径** | 相对路径在 App 私有目录下解析不到 |
 *
 * 另有一条**硬约束**由实测得出：ctx-bin 的编译版本不能高于运行时的 API 版本，
 * 否则 `GenieDialog_create` 直接返回 -1（加载即失败，耗时 ~0.6 s）。
 * 这条无法从配置侧规避，只能靠 [NpuModelStore] 在加载前比对版本。
 */
object NpuConfigBuilder {

    /** 生成 Genie dialog 配置（cfg JSON）。[ctxBinDir] 是 ctx-bin 所在目录（绝对路径）。 */
    fun buildDialogConfig(
        ctxBinDir: File,
        maxNumTokens: Int = 64,
        nThreads: Int = 3,
    ): String {
        val dir = ctxBinDir.absolutePath
        return """
            {
              "version": 2,
              "dialog": {
                "engine": {
                  "n-threads": $nThreads,
                  "backend": {
                    "type": "QnnHtp",
                    "QnnHtp": {
                      "use-mmap": true,
                      "poll": false,
                      "spill-fill-bufsize": 0,
                      "mmap-budget": 0,
                      "kv-dim": 128,
                      "allow-async-init": false,
                      "pos-id-dim": 64,
                      "rope-theta": 5000000
                    },
                    "extensions": "${dir}/htp_backend_ext_config.json"
                  }
                }
              },
              "max-num-tokens": $maxNumTokens
            }
        """.trimIndent()
    }

    /** 生成 HTP backend 扩展配置（ext JSON）。[socModel] 来自设备 Build 信息。 */
    fun buildBackendExt(socModel: Int, dspArch: String): String {
        return """
            {
              "devices": [
                {
                  "soc_model": $socModel,
                  "dsp_arch": "$dspArch",
                  "cores": [
                    {
                      "core_id": 0,
                      "perf_profile": "burst",
                      "rpc_control_latency": 100
                    }
                  ]
                }
              ],
              "memory": { "mem_type": "shared_buffer" },
              "context": { "weight_sharing_enabled": true }
            }
        """.trimIndent()
    }
}
