package interlock.relay.core.exec.stream

import interlock.relay.core.exec.shizuku.ShizukuBackend
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 设备侧（Shizuku 用户服务）那一面的 [StreamDevice] 实现。
 *
 * 宿主侧只认 [StreamDevice]：于是控制器的收场路径能在纯 JVM 上用替身穷举，
 * 而设备依赖（binder / ParcelFileDescriptor）留在这一个类里。
 */
internal class ShizukuStreamDevice(private val backend: ShizukuBackend) : StreamDevice {

    override suspend fun open(waitMs: Long): StreamDevice.OpenResult {
        val (txn, pipe) = backend.streamStart(waitMs)
        val descriptor = pipe ?: return when (txn) {
            is ShizukuBackend.StreamTxn.Replied -> StreamDevice.OpenResult.Failed(txn.note)
            is ShizukuBackend.StreamTxn.Submitted -> StreamDevice.OpenResult.Failed(txn.note)
            ShizukuBackend.StreamTxn.NotSent ->
                StreamDevice.OpenResult.Failed("stream service is not reachable")
        }
        // 回执里的 note 在成功时就是元数据 JSON（mime/宽高/帧率）。
        val metadata = (txn as? ShizukuBackend.StreamTxn.Replied)?.note.orEmpty().ifBlank { "{}" }
        return StreamDevice.OpenResult.Opened(ParcelReadEnd(descriptor), metadata)
    }

    override fun close(waitMs: Long): Boolean = when (val txn = backend.streamStopBlocking(waitMs)) {
        is ShizukuBackend.StreamTxn.Replied -> txn.status >= 0
        // binder 不在：用户服务进程已死，会话随进程消失 —— 收场已成立。
        is ShizukuBackend.StreamTxn.NotSent -> true
        // 递交了没等到回执：结局未知，如实回 false。
        is ShizukuBackend.StreamTxn.Submitted -> false
    }

    /** pipe 读端：关掉它才解阻塞设备侧的写（EPIPE 契约）。 */
    private class ParcelReadEnd(private val descriptor: android.os.ParcelFileDescriptor) :
        StreamReadEnd {
        private val opened = AtomicBoolean(false)

        override fun openStream(): InputStream? =
            if (opened.compareAndSet(false, true)) {
                // AutoCloseInputStream：读端关了 fd 也跟着关（设备侧的阻塞写随之醒）。
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            } else null

        override fun close() {
            runCatching { descriptor.close() }
        }
    }
}
