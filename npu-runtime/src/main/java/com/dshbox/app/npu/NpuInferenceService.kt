package com.dshbox.app.npu

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 端侧 NPU 推理服务，跑在**独立进程**（`android:process=":npu"`）。
 *
 * ## 为什么必须是独立进程
 *
 * Genie/QNN 在 DSP（Hexagon HTP）上的会话是**独占资源**：一次 `GenieDialog_create`
 * 未释放时，同一进程内后续所有 NPU 调用都会排队阻塞。而实测中 `GenieDialog_create`
 * **会卡死**（建图 + 加载数 GB ctx-bin 的过程中偶发永久阻塞，且卡死后无自愈路径，
 * `stop/start cdsprpcd` 需要 root）。
 *
 * 唯一的恢复手段是**杀掉持有 DSP 会话的进程**（内核关闭 fastrpc fd →
 * DSP 侧回收 User PD → 立刻恢复，实测 `create` 随即返回 0）。若推理在主进程里，
 * 杀进程就等于杀掉整个 App；放到独立进程里，杀掉它只损失一次推理，
 * 主 App 完全不受影响。
 *
 * ## 契约
 *
 * 本服务是**单次请求、短生命周期**的：收到请求 → 跑一次推理 → 回结果 → 结束。
 * 不长期持有 DSP 会话，因此不需要跨请求复用对话上下文。
 */
class NpuInferenceService : Service() {

    companion object {
        private const val TAG = "NpuInferenceSvc"

        const val ACTION_RUN = "com.dshbox.app.npu.RUN"
        const val EXTRA_CFG_PATH = "cfg_path"
        const val EXTRA_PROMPT = "prompt"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfgPath = intent?.getStringExtra(EXTRA_CFG_PATH)
        val prompt = intent?.getStringExtra(EXTRA_PROMPT).orEmpty()

        if (cfgPath.isNullOrBlank()) {
            Log.w(TAG, "缺少配置路径，直接结束")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // 推理必须离开主线程：native 调用是阻塞的。
        Thread({
            var result: NpuNative.Result
            try {
                // 心跳线程：定期回传进度，让宿主能区分「慢」与「死」。
                // create 阶段（实测 3.7–5.7 s）一个 token 都没有，
                // 只能靠心跳判断进程还活着。
                val beat = Thread({
                    while (!Thread.currentThread().isInterrupted) {
                        val p = NpuNative.progress()
                        NpuResultBus.broadcastBeat(
                            applicationContext,
                            p.stage,
                            p.tokens,
                            p.lastProgressAgeMs,
                        )
                        try {
                            Thread.sleep(500)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return@Thread
                        }
                    }
                }, "npu-heartbeat")
                beat.isDaemon = true
                beat.start()

                result = NpuNative.run(cfgPath, prompt)
                beat.interrupt()
            } catch (t: Throwable) {
                Log.e(TAG, "推理异常", t)
                result = NpuNative.Result(-100, 0, t.message ?: t.javaClass.simpleName)
            }
            // 正常收尾：释放 DSP 会话，否则会独占住设备。
            NpuNative.release()
            Log.i(TAG, "推理结束 status=${result.status} tokens=${result.tokens}")

            // 回传结果给宿主进程。
            NpuResultBus.broadcastResult(
                applicationContext,
                NpuResultBus.Payload(
                    status = result.status,
                    tokens = result.tokens,
                    text = result.text,
                    error = if (result.status == 0) null else result.text,
                ),
            )
            stopSelf(startId)
        }, "npu-infer").start()

        // 不 sticky：进程被杀后不自动重启（由宿主的看门狗决定何时重启）。
        return START_NOT_STICKY
    }
}
