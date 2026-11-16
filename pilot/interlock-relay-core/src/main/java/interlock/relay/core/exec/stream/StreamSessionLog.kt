package interlock.relay.core.exec.stream

import interlock.relay.core.log.LogEvent
import interlock.relay.core.log.LogSubsystem
import interlock.relay.core.log.RunLog

/**
 * 一次宿主侧流会话的读数（写进运行日志的那一行）。
 *
 * 字段口径：
 * - [session]：**宿主侧会话序号**（"S3" 这样的计数），不是 token、不是流端口、
 * 不是浏览器侧那一枚 tab token —— 日志要能被复制出去，序号就够了。
 * - [phase]：会话走到哪一步（见 [StreamSessionPhase]）。
 * - [result]：这一步的结论（ok / failed / confirmed / unknown / timeout / eof）。
 * - [reason]：人读的短原因（设备侧原因或收场原因）；没有就留空。
 * - [bytes]：宿主 loopback 服务**已经写出**的字节数；这一层没有这个读数时留空。
 * - [durationMs]：这一步花了多久；这一层没有这个读数时留空。
 *
 * 不放屏幕内容、token、参数原文 —— 与其余运行日志事件同一把尺子。
 */
data class StreamSession(
    val session: String,
    val phase: String,
    val result: String,
    val reason: String = "",
    val bytes: Long? = null,
    val durationMs: Long? = null,
    /** 虚拟屏源尺寸（metadata 的 sourceWidth/sourceHeight）；没有就 null。 */
    val sourceW: Int? = null,
    val sourceH: Int? = null,
    /** 实际编码尺寸（metadata 的 width/height，1280 回退 720 时两者可不同）。 */
    val encodeW: Int? = null,
    val encodeH: Int? = null,
) {
    companion object {
        /**
         * 从 metadata JSON 提取 source/encode 维度。org.json 在 JVM 单测里是平台桩
         * （方法体被换成抛错/默认值），这里手动扫键值 —— metadata 由本仓库自己写出，
         * 键名就是这四个，值都是不带引号的整数。
         */
        fun dimensionsFrom(metadataJson: String): StreamSessionDims {
            fun intOf(key: String): Int? =
                Regex("\"" + key + "\"\\s*:\\s*(-?\\d+)").find(metadataJson)?.groupValues?.get(1)?.toIntOrNull()
            return StreamSessionDims(
                intOf("sourceWidth"),
                intOf("sourceHeight"),
                intOf("width"),
                intOf("height"),
            )
        }
    }
}

/** [StreamSession.dimensionsFrom] 的读数包。 */
data class StreamSessionDims(
    val sourceW: Int?,
    val sourceH: Int?,
    val encodeW: Int?,
    val encodeH: Int?,
)

/** 会话（与运行日志模板里的 phase 同口径）。 */
object StreamSessionPhase {
    /** 开流。 */
    const val START = "start"

    /** 停流。 */
    const val STOP = "stop"

    /** 流结束（设备侧 EOF / 客户端断开 / 会话被占用）。 */
    const val EOF = "eof"

    /** 宿主看门狗到点收场。 */
    const val WATCHDOG = "watchdog"

    /** 异常（控制面调用本身抛了）。 */
    const val ERROR = "error"

    /** 面板输入的留痕读数（动作@源像素 + 成败/outcome；无 token）。 */
    const val INPUT = "input"
}

/**
 * 会话读数的**写入口**：控制器与控制端点只负责产出读数，写到哪里由装配方决定
 * （生产写运行日志，测试写假 log）。
 *
 * 这样控制器与控制端点都不直接依赖任何具体的日志实现。
 */
fun interface StreamSessionLog {
    fun write(record: StreamSession)
}

/**
 * 把会话读数写进运行日志（宿主装配方注入的那一支）。
 *
 * 字段名与 [LogEvent.STREAM_SESSION] 的模板占位符逐字相等；字典测试按
 * [FIELDS] 对账（见 StageEventDictionaryTest）。
 */
class RunLogStreamSessionLog(private val runLog: RunLog) : StreamSessionLog {
    override fun write(record: StreamSession) {
        val event = if (record.result in FAILED_RESULTS) {
            LogEvent.STREAM_SESSION_FAILED
        } else {
            LogEvent.STREAM_SESSION
        }
        runLog.event(LogSubsystem.TRANSPORT, event, *fields(record))
    }

    companion object {
        /** 本事件填的字段集合：与事件模板的占位符集合必须逐个相等。 */
        val FIELDS = setOf(
            "session", "phase", "result", "reason", "bytes", "durationMs",
            "sourceW", "sourceH", "encodeW", "encodeH",
        )

        /**
         * 走 WARN（STREAM_SESSION_FAILED）的那几种结论：这一场**没有正常走完**。
         * 其余（ok / eof / confirmed / client_gone / conflict）走 INFO。
         */
        private val FAILED_RESULTS = setOf("failed", "timeout", "device_error")

        /** 一条读数摊成运行日志的字段（未知读数写空串，运行日志会渲染成 "-"）。 */
        private fun fields(record: StreamSession): Array<Pair<String, String>> = arrayOf(
            "session" to record.session,
            "phase" to record.phase,
            "result" to record.result,
            "reason" to record.reason,
            "bytes" to (record.bytes?.toString() ?: ""),
            "durationMs" to (record.durationMs?.toString() ?: ""),
            "sourceW" to (record.sourceW?.toString() ?: ""),
            "sourceH" to (record.sourceH?.toString() ?: ""),
            "encodeW" to (record.encodeW?.toString() ?: ""),
            "encodeH" to (record.encodeH?.toString() ?: ""),
        )
    }
}
