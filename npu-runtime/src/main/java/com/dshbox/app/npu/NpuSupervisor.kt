package com.dshbox.app.npu

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * NPU 推理的**宿主侧监督器**。
 *
 * 推理跑在 `:npu` 独立进程里；本类在主进程负责：
 *  1. 前置检查（内存 / 模型完整性 / 版本兼容）——不通过就不进 DSP；
 *  2. 发起推理并**监督**它；
 *  3. 超时后**杀掉推理进程**并按需重启。
 *
 * ## 为什么超时只能靠杀进程
 *
 * Genie 没有可靠的 abort API；`GenieDialog_signal` 在 DSP 会话已卡死时只是设个标志位，
 * 唤不醒卡在内核 ioctl 里的线程。所以：
 *
 *  - **create 阶段**（建图 + 加载权重，实测 3.7–5.7 s）：此时一个 token 都还没产出，
 *    只能靠 native 侧的阶段心跳判断「还活着」。心跳停滞 → 杀进程。
 *  - **生成阶段**（有 token 回调）：靠 token 计数推进判断。
 *
 * 杀掉进程后，内核关闭 fastrpc fd，DSP 侧回收 User PD，实测随即恢复。
 * 但**重启有冷却期**：实测杀进程后立刻拉起新进程，仍会撞上旧 PD 的清理窗口而卡死；
 * 需要等待约 8 秒才稳定。所以这里的重启间隔不是拍脑袋，是实测值。
 */
class NpuSupervisor(
    private val context: Context,
    private val modelStore: NpuModelStore,
) {

    companion object {
        private const val TAG = "NpuSupervisor"

        /**
         * create 阶段超时。
         * 实测正常 3.7–5.7 s；留足余量取 30 s。
         */
        const val CREATE_TIMEOUT_MS = 30_000L

        /** 生成阶段单 token 超时。实测首字 183–306 ms，取 5 s。 */
        const val TOKEN_TIMEOUT_MS = 5_000L

        /** 整体请求超时。 */
        const val OVERALL_TIMEOUT_MS = 120_000L

        /**
         * 推理进程被杀后，到下次允许启动之间的冷却期。
         * 实测：杀进程后 < 8 s 就重启，新进程会撞上旧 DSP 会话的清理窗口并卡死。
         */
        const val RESTART_COOLDOWN_MS = 8_000L
    }

    /** 推理结果。[status] 0 为成功。 */
    data class Outcome(
        val status: Int,
        val tokens: Int,
        val text: String,
        val elapsedMs: Long,
        val error: String? = null,
    )

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "npu-watchdog").apply { isDaemon = true }
        }

    /**
     * 宿主侧的结果/心跳接收器。
     *
     * 必须注册，否则 `:npu` 进程回传的广播没人接——推理会一直等到超时。
     * 注册放在 Supervisor 初始化里，保证「谁用 Supervisor 谁负责注册」，
     * 不依赖上层 App 记得调用。
     */
    private var receiver: NpuResultReceiver? = null

    init {
        try {
            receiver = NpuResultReceiver.register(context)
        } catch (e: Exception) {
            Log.e(TAG, "注册结果接收器失败", e)
        }
    }

    /** 上次因超时被杀的时间戳（冷却期用）。 */
    @Volatile
    private var lastKillAt = 0L

    /**
     * 执行一次推理（含前置检查与看门狗）。
     *
     * 注意：本方法会阻塞调用线程（native 调用无法异步化），因此调用方
     * 应当在自己的工作线程上调用。
     */
    fun generate(prompt: String, maxTokens: Int = 64): Outcome {
        val t0 = System.currentTimeMillis()

        // ---- 冷却期检查 ----
        val sinceKill = System.currentTimeMillis() - lastKillAt
        if (lastKillAt != 0L && sinceKill < RESTART_COOLDOWN_MS) {
            return Outcome(
                status = -200,
                tokens = 0,
                text = "",
                elapsedMs = System.currentTimeMillis() - t0,
                error = "上次推理进程被杀后需等待 ${RESTART_COOLDOWN_MS - sinceKill} ms 再重启",
            )
        }

        // ---- 前置检查 ----
        val preflight = preflight(maxTokens)
        if (!preflight.ok) {
            return Outcome(
                status = -201,
                tokens = 0,
                text = "",
                elapsedMs = System.currentTimeMillis() - t0,
                error = preflight.message,
            )
        }
        if (preflight.message.isNotEmpty()) {
            Log.i(TAG, "前置检查：${preflight.message}")
        }

        // ---- 起独立进程做推理 ----
        return runGuarded(prompt, maxTokens, t0)
    }

    /**
     * 前置检查。任一项致命失败则不进入 DSP。
     */
    fun preflight(maxTokens: Int): NpuPreflight.Result {
        val mem = modelStore.availableMemoryBytes()
        val memResult = NpuPreflight.checkMemory(mem)
        if (memResult.fatal) return memResult

        val files = modelStore.checkFiles()
        if (!files.ok) return files

        val version = modelStore.checkVersion()
        if (!version.ok) return version

        // 配置生成（把实测最优参数固化进去）
        val cfgWrite = modelStore.writeConfigs(maxTokens)
        if (!cfgWrite.ok) return cfgWrite

        return NpuPreflight.Result.ok(
            "前置检查通过（内存 ${mem / 1024 / 1024} MB）",
            mem,
        )
    }

    /**
     * 在独立进程里跑推理，并监督它。
     */
    private fun runGuarded(prompt: String, maxTokens: Int, t0: Long): Outcome {
        val cfgPath = modelStore.dialogConfigPath()
        val intent = Intent(context, NpuInferenceService::class.java).apply {
            action = NpuInferenceService.ACTION_RUN
            putExtra(NpuInferenceService.EXTRA_CFG_PATH, cfgPath)
            putExtra(NpuInferenceService.EXTRA_PROMPT, prompt)
        }

        // 先装好等待点，再启动推理进程。
        // 顺序反了会丢结果：推理进程可能极快就回传，那时还没 arm，
        // 结果会被丢掉，表现为「明明成功了却一直等到超时」。
        NpuResultBus.arm()

        // 推理进程：与主进程同应用不同进程，可独立被杀。
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "启动推理服务失败", e)
            return Outcome(-202, 0, "", System.currentTimeMillis() - t0, e.message)
        }

        // 看门狗：`:npu` 进程持续回传心跳（每 500 ms 一次）。
        // 心跳停滞即判定卡死——这是唯一能在 create 阶段（一个 token 都没有）
        // 区分「慢」与「死」的手段。
        val heartbeat = HeartbeatMonitor()
        heartbeat.start()

        val result = NpuResultBus.await(timeoutMs = OVERALL_TIMEOUT_MS)
        heartbeat.stop()

        if (result == null) {
            // 超时：杀掉推理进程，让内核回收 DSP 会话。
            Log.w(TAG, "推理超时（${OVERALL_TIMEOUT_MS} ms），终止 :npu 进程")
            killNpuProcess()
            return Outcome(
                status = -203,
                tokens = 0,
                text = "",
                elapsedMs = System.currentTimeMillis() - t0,
                error = "推理超时（${OVERALL_TIMEOUT_MS} ms），已终止推理进程",
            )
        }

        return Outcome(
            status = result.status,
            tokens = result.tokens,
            text = result.text,
            elapsedMs = System.currentTimeMillis() - t0,
            error = result.error,
        )
    }

    /**
     * 心跳监视器。
     *
     * 依据 [NpuResultBus] 记录的「最近一次收到心跳」的时间戳判断停滞：
     *  - 尚未产出 token（create 阶段）：阈值 [CREATE_TIMEOUT_MS]；
     *  - 已产出 token（生成阶段）：阈值 [TOKEN_TIMEOUT_MS]（token 本该每 ~200 ms 来一个）。
     */
    private inner class HeartbeatMonitor {
        @Volatile
        private var stopped = false

        fun start() {
            scheduler.scheduleWithFixedDelay({
                if (stopped) return@scheduleWithFixedDelay
                val idle = System.currentTimeMillis() - NpuResultBus.lastBeatAt
                val threshold =
                    if (NpuResultBus.lastBeatTokens > 0) TOKEN_TIMEOUT_MS else CREATE_TIMEOUT_MS
                if (idle > threshold) {
                    Log.w(TAG, "心跳停滞 ${idle} ms（阈值 ${threshold} ms），判定推理卡死")
                    killNpuProcess()
                }
            }, 2, 2, TimeUnit.SECONDS)
        }

        fun stop() {
            stopped = true
        }
    }

    /** 杀掉卡死/超时的推理进程。 */
    private fun killNpuProcess() {
        val pid = findNpuProcessPid()
        if (pid > 0) {
            Log.w(TAG, "终止推理进程 pid=$pid")
            killProcess(pid)
            lastKillAt = System.currentTimeMillis()
        }
    }

    /** 查 :npu 进程 pid。 */
    private fun findNpuProcessPid(): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull {
                it.processName?.endsWith(":npu") == true
            }?.pid ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    /** 杀掉指定 pid（同 uid 应用间允许）。 */
    private fun killProcess(pid: Int) {
        try {
            android.os.Process.killProcess(pid)
            Log.i(TAG, "已终止推理进程 pid=$pid")
        } catch (e: Exception) {
            Log.e(TAG, "终止推理进程失败 pid=$pid", e)
        }
    }

    fun release() {
        scheduler.shutdownNow()
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.w(TAG, "反注册结果接收器失败", e)
            }
        }
        receiver = null
    }
}
