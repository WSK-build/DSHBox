package com.dshbox.app.npu

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 推理过程中的**双向通道**：结果回传 + 心跳上报。
 *
 * ## 为什么需要心跳
 *
 * 推理跑在 `:npu` 独立进程里。宿主无法直接读子进程的 native 进度
 * （native 的进度结构是进程内的），所以心跳必须由子进程**主动回传**。
 *
 * 心跳是「区分慢与死」的唯一手段：
 *  - **create 阶段**（建图 + 加载数 GB ctx-bin，实测 3.7–5.7 s）：一个 token 都没有，
 *    只能靠心跳判断进程还活着；
 *  - **生成阶段**（有 token 回调）：心跳随 token 推进。
 *
 * 若心跳停滞超过阈值，宿主判定为卡死并**杀掉推理进程**
 * （内核关闭 fastrpc fd → DSP 侧回收 User PD → 实测可恢复）。
 *
 * ## 传输方式
 *
 * 用显式广播（指定宿主包名）。跨进程回传一次性结果与心跳，广播足够；
 * 且不引入常驻 Binder 的复杂度。广播是显式的，不必担心被其它 App 截获。
 */
object NpuResultBus {

    private const val TAG = "NpuResultBus"

    /** 宿主侧接收结果/心跳的 action（显式广播）。 */
    const val ACTION_RESULT = "com.dshbox.app.npu.RESULT"

    const val EXTRA_STATUS = "status"
    const val EXTRA_TOKENS = "tokens"
    const val EXTRA_TEXT = "text"
    const val EXTRA_ERROR = "error"

    /** 心跳：当前阶段。 */
    const val EXTRA_STAGE = "stage"

    /** 心跳：距最近一次推进的毫秒数（由子进程计算）。 */
    const val EXTRA_IDLE_MS = "idle_ms"

    /** 一次推理的最终结果。 */
    data class Payload(val status: Int, val tokens: Int, val text: String, val error: String?)

    /** 一次心跳。 */
    data class Beat(val stage: Int, val tokens: Int, val idleMs: Long)

    private val pending = AtomicReference<CountDownLatch?>(null)
    private val payload = AtomicReference<Payload?>(null)

    /** 最近一次心跳的时间戳（宿主侧）。看门狗据此判断停滞。 */
    @Volatile
    var lastBeatAt: Long = 0L
        private set

    /** 最近一次心跳的 token 计数。 */
    @Volatile
    var lastBeatTokens: Int = 0
        private set

    /**
     * **在发起推理之前**调用：装好等待点并清空上一次的结果。
     *
     * 这一步必须早于 `startService()`——推理进程可能极快就回传结果，
     * 若此时还没装好等待点，结果会被丢掉（表现为「明明成功了却超时」）。
     */
    fun arm() {
        payload.set(null)
        val latch = CountDownLatch(1)
        pending.set(latch)
        resetBeat()
    }

    /**
     * 等待一次结果。
     *
     * @param timeoutMs 等待上限。
     * @return 结果；超时返回 null。
     */
    fun await(timeoutMs: Long): Payload? {
        val latch = pending.get() ?: run {
            // 没 arm 过：直接等待一个不可能到来的结果，等价于超时。
            return null
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            pending.set(null)
        }
        return payload.get()
    }

    /** 由宿主侧的接收器调用，交付结果。 */
    fun deliver(p: Payload) {
        payload.set(p)
        pending.get()?.countDown()
    }

    /** 由宿主侧的接收器调用，记录心跳。 */
    fun deliverBeat(beat: Beat) {
        lastBeatAt = System.currentTimeMillis()
        lastBeatTokens = beat.tokens
    }

    /** 推理开始时重置心跳状态。 */
    fun resetBeat() {
        lastBeatAt = System.currentTimeMillis()
        lastBeatTokens = 0
    }

    /** `:npu` 进程侧发出结果。 */
    fun broadcastResult(context: Context, p: Payload) {
        val intent = Intent(ACTION_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_STATUS, p.status)
            putExtra(EXTRA_TOKENS, p.tokens)
            putExtra(EXTRA_TEXT, p.text)
            putExtra(EXTRA_ERROR, p.error)
        }
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "回传结果失败", e)
        }
    }

    /** `:npu` 进程侧发出心跳。 */
    fun broadcastBeat(context: Context, stage: Int, tokens: Int, idleMs: Long) {
        val intent = Intent(ACTION_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_STAGE, stage)
            putExtra(EXTRA_TOKENS, tokens)
            putExtra(EXTRA_IDLE_MS, idleMs)
        }
        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "回传心跳失败", e)
        }
    }
}
