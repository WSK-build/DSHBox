package interlock.relay.core.exec.stream

import java.io.InputStream

/**
 * 设备侧流会话的后端面：控制器只认这一面，不直接认 Shizuku/ParcelFileDescriptor。
 *
 * 抽出它的理由：控制器的收场路径（开流失败 / 停流 / 看门狗 / EOF）必须在纯 JVM 上
 * 能用替身穷举 —— 那条路径上不许出现 android.os.* 的调用。
 */
interface StreamDevice {
    /** 开流结果。 */
    sealed class OpenResult {
        /** 开成了：读端与会话描述（metadata JSON）。 */
        class Opened(val readEnd: StreamReadEnd, val metadata: String) : OpenResult()

        /** 没开成：设备侧原因（人类可读短句）。 */
        class Failed(val reason: String) : OpenResult()
    }

    /** 开流：等设备侧的编码会话起起来。 */
    suspend fun open(waitMs: Long): OpenResult

    /**
     * 停流：true = 收场已确认（设备回执到了，或通道已死本来就没会话）；
     * false = 结局未知（回执丢了）。
     */
    fun close(waitMs: Long): Boolean
}

/**
 * 读端：设备侧字节的来源。
 *
 * [close] 是解阻塞设备侧写的**唯一**手段（pipe 契约：读端全关才把阻塞的 write
 * 唤醒成 EPIPE）—— 因此关读端与停设备必须同一条路走。
 */
interface StreamReadEnd {
    /** 打开字节流：已开过则回 null（源只可消费一次）。 */
    fun openStream(): InputStream?

    /** 关掉读端。 */
    fun close()
}
