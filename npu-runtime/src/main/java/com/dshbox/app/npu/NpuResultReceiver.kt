package com.dshbox.app.npu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * 宿主进程侧的结果接收器。
 *
 * 只把 `:npu` 进程回传的结果交给 [NpuResultBus]，不做别的判断——
 * 判断逻辑集中在 [NpuSupervisor]，便于单测覆盖。
 */
class NpuResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NpuResultBus.ACTION_RESULT) return

        // 心跳与结果共用一个 action，用是否有 stage 字段区分。
        // 心跳不带 status（默认 -1），结果带 status。
        if (intent.hasExtra(NpuResultBus.EXTRA_STAGE)) {
            NpuResultBus.deliverBeat(
                NpuResultBus.Beat(
                    stage = intent.getIntExtra(NpuResultBus.EXTRA_STAGE, NpuNative.STAGE_IDLE),
                    tokens = intent.getIntExtra(NpuResultBus.EXTRA_TOKENS, 0),
                    idleMs = intent.getLongExtra(NpuResultBus.EXTRA_IDLE_MS, 0L),
                ),
            )
            return
        }

        val payload = NpuResultBus.Payload(
            status = intent.getIntExtra(NpuResultBus.EXTRA_STATUS, -1),
            tokens = intent.getIntExtra(NpuResultBus.EXTRA_TOKENS, 0),
            text = intent.getStringExtra(NpuResultBus.EXTRA_TEXT).orEmpty(),
            error = intent.getStringExtra(NpuResultBus.EXTRA_ERROR),
        )
        NpuResultBus.deliver(payload)
    }

    companion object {
        /** 注册（在宿主进程初始化时调用一次）。 */
        fun register(context: Context): NpuResultReceiver {
            val receiver = NpuResultReceiver()
            val filter = IntentFilter(NpuResultBus.ACTION_RESULT)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            return receiver
        }
    }
}
