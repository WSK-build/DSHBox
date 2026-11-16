package com.dshbox.app.npu

import java.io.File

/**
 * 推理启动前的**前置检查**。
 *
 * 存在的原因：Genie/QNN 的失败模式大多是「不报错的失败」——
 *  · ctx-bin 少一段 → 加载到一半才崩，或悄悄退化成极慢；
 *  · ctx-bin 的编译版本高于运行时 → `GenieDialog_create` 返回 -1（~0.6 s 快速失败）；
 *  · 内存不足 → 加载 3 GB 权重时 LMKD 回收 mmap 页，DSP 侧 SMMU 缺页 → 永久挂起。
 *
 * 这些都可以在**进入 DSP 之前**用纯文件检查挡掉，比事后靠看门狗救回来便宜得多。
 */
object NpuPreflight {

    /** 启动 NPU 所需的最小可用内存。低于此值直接拒绝启动。 */
    const val MIN_AVAILABLE_MEMORY_BYTES = 4L * 1024 * 1024 * 1024

    /** 低于此值认为内存吃紧，返回「可用但有风险」而非直接失败。 */
    const val WARN_AVAILABLE_MEMORY_BYTES = 6L * 1024 * 1024 * 1024

    /** 检查结论。[fatal] 为 true 时不应进入 DSP。 */
    data class Result(
        val ok: Boolean,
        val fatal: Boolean,
        val message: String,
        val availableMemoryBytes: Long = 0,
    ) {
        companion object {
            fun ok(message: String, mem: Long = 0) = Result(true, false, message, mem)
            fun warn(message: String, mem: Long) = Result(true, false, message, mem)
            fun fail(message: String, mem: Long = 0) = Result(false, true, message, mem)
        }
    }

    /**
     * 检查模型目录是否完整。
     *
     * @param ctxBinFiles ctx-bin 各段（文件名 → **期望字节数**）。
     *   用精确字节数而不是「文件存在」，是因为历史上曾把 4B 的结果误记成 1B
     *   （因为两者都只是「几个 .bin 文件」）。字节数是唯一可靠的判据。
     * @param modelDir 模型目录（ctx-bin 所在处）。
     */
    fun checkModelFiles(
        ctxBinFiles: Map<String, Long>,
        modelDir: File,
    ): Result {
        if (ctxBinFiles.isEmpty()) {
            return Result.fail("模型清单为空：未提供 ctx-bin 分段信息")
        }
        if (!modelDir.isDirectory) {
            return Result.fail("模型目录不存在：${modelDir.absolutePath}")
        }
        for ((name, expected) in ctxBinFiles) {
            val f = File(modelDir, name)
            if (!f.isFile) {
                return Result.fail("缺少模型分段：$name")
            }
            val actual = f.length()
            if (actual != expected) {
                return Result.fail(
                    "模型分段字节数不符：$name 期望 $expected 实得 $actual（下载不完整或版本不符）",
                )
            }
        }
        return Result.ok("模型分段校验通过（${ctxBinFiles.size} 段）")
    }

    /**
     * 检查 ctx-bin 的编译版本是否不高于运行时版本。
     *
     * **前向不兼容**（实测）：ctx-bin 的编译版本 > 运行时 API 版本时，
     * `GenieDialog_create` 返回 -1。
     *
     * @param ctxBinQairtVersion ctx-bin 声明的 QAIRT 版本（如 "2.42.0"）。
     * @param runtimeApiVersion 运行时 API 版本（如 "2.40.0"）。
     */
    fun checkVersionCompatibility(ctxBinQairtVersion: String?, runtimeApiVersion: String?): Result {
        if (ctxBinQairtVersion.isNullOrBlank()) {
            // 版本信息缺失时不阻断：实测中部分包不带该字段但仍可加载。
            return Result.warn("ctx-bin 未声明 QAIRT 版本，无法预检版本兼容性", 0)
        }
        if (runtimeApiVersion.isNullOrBlank()) {
            return Result.warn("运行时版本不可读，无法预检版本兼容性", 0)
        }
        val ctx = parseVersion(ctxBinQairtVersion)
        val rt = parseVersion(runtimeApiVersion)
        if (ctx == null || rt == null) {
            return Result.warn("版本号无法解析（ctx=$ctxBinQairtVersion rt=$runtimeApiVersion），跳过版本预检", 0)
        }
        if (ctx > rt) {
            return Result.fail(
                "ctx-bin 编译版本 $ctxBinQairtVersion 高于运行时 $runtimeApiVersion：" +
                    "ctx-bin 前向不兼容，GenieDialog_create 会返回 -1",
            )
        }
        return Result.ok("版本兼容：ctx-bin $ctxBinQairtVersion ≤ 运行时 $runtimeApiVersion")
    }

    /** 检查可用内存是否够跑。 */
    fun checkMemory(availableBytes: Long): Result {
        return when {
            availableBytes < MIN_AVAILABLE_MEMORY_BYTES ->
                Result.fail(
                    "可用内存不足（${availableBytes / 1024 / 1024} MB < " +
                        "${MIN_AVAILABLE_MEMORY_BYTES / 1024 / 1024} MB），请先关闭后台应用",
                    availableBytes,
                )
            availableBytes < WARN_AVAILABLE_MEMORY_BYTES ->
                Result.warn(
                    "可用内存偏紧（${availableBytes / 1024 / 1024} MB），" +
                        "加载大模型时存在被回收的风险",
                    availableBytes,
                )
            else -> Result.ok("可用内存充足（${availableBytes / 1024 / 1024} MB）", availableBytes)
        }
    }

    /** 解析 "2.42.0" / "2.42.0.251225135753_193295" 形式的前三段数字。 */
    internal fun parseVersion(v: String): Triple<Int, Int, Int>? {
        val parts = v.trim().split('.')
        if (parts.size < 2) return null
        val nums = parts.take(3).map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: return null }
        return Triple(nums[0], nums[1], nums.getOrElse(2) { 0 })
    }
}
