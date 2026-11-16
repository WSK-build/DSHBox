package interlock.relay.core.exec.direct

import android.media.MediaRecorder
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.storage.StorageReaper
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风采集。落一个有时长上限的音频产物，不做常驻录音：
 * 采集类能力的失控形态是「一直录」，所以时长上限做成参数上界，由校验夹住而不是靠调用方自觉。
 *
 * 内容上限是 120 秒；外层等待预算（入口/插件的 `--timeout` 档位与宿主有效期）都放在
 * **130 秒**——比内容多出的 10 秒是编码落盘、回执与回写的收尾余量，预算与内容同值
 * 会让满时长录音必然被外层提前掐断。三处同表，不新增任何配置项。
 *
 * 产物先写未绑定的暂存文件，采完再落进产物目录，与截图走同一条排他与符号链接纪律。
 */
class AudioCapture(
    private val reaper: StorageReaper,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
) {

    private val busy = AtomicBoolean(false)

    suspend fun capture(call: BackendCall): BackendResult {
        if (!busy.compareAndSet(false, true)) {
            return BackendResult.Failed(RelayError.RATE_LIMITED, "capture already in flight")
        }
        val seconds = clampSeconds(call.args.optInt(KEY_SECONDS, DEFAULT_SECONDS))
        val estimate = seconds.toLong() * BYTES_PER_SECOND
        val staged = reaper.newArtifactFile(call.requestId, ARTIFACT_EXTENSION, estimate)
            ?: run {
                busy.set(false)
                return BackendResult.Failed(RelayError.STORAGE_FULL, "quota exhausted")
            }
        // 无参构造在 Android 12 起被带进程标签的重载取代，而后者是 API 31；
        // 本模块下限 29，用无参那一档，两档行为一致。
        @Suppress("DEPRECATION")
        val recorder = MediaRecorder()
        try {
            val startFailure = runCatching {
                recorder.setAudioSource(audioSource)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                recorder.setMaxDuration(seconds * 1000)
                recorder.setOutputFile(staged.absolutePath)
                recorder.prepare()
                recorder.start()
            }.exceptionOrNull()
            if (startFailure != null) {
                // SecurityException = 麦克风授权被收回或整机禁用采集，要用户在设置里动一下；
                // 其余异常是采集器此刻的状态问题（被别的应用占着、参数被拒），稍后重来即可。
                return if (startFailure is SecurityException) {
                    BackendResult.Failed(
                        RelayError.GATE_SYSTEM_MISSING,
                        "RECORD_AUDIO not granted: recorder start refused",
                    )
                } else {
                    BackendResult.Failed(
                        RelayError.BACKEND_UNAVAILABLE,
                        "recorder rejected: ${startFailure.javaClass.simpleName}",
                    )
                }
            }

            delay(seconds * 1000L)
            // stop 会把缓冲区写完，取消或异常都不能跳过；失败分支统一在 finally 里丢弃暂存件。
            val stopped = runCatching { recorder.stop() }.isSuccess
            if (!stopped) return BackendResult.Failed(RelayError.BACKEND_UNAVAILABLE, "capture stopped early")

            val bytes = if (staged.length() > 0L) bytesOf(staged) else 0L
            if (bytes <= 0L) return BackendResult.Failed(RelayError.STORAGE_FULL, "empty capture")
            val artifact = reaper.publishArtifact(staged, call.requestId)
                ?: return BackendResult.Failed(RelayError.STORAGE_FULL, "artifact publish failed")
            val guestPath = RelayPaths.toGuestPath(artifact, call.paths)
                ?: run {
                    reaper.discard(artifact)
                    return BackendResult.Failed(RelayError.INTERNAL, "artifact path unmappable")
                }
            return BackendResult.Ok(
                data = org.json.JSONObject().put("seconds", seconds).put("bytes", bytes),
                artifacts = listOf(guestPath),
                artifactBytes = bytes,
            )
        } finally {
            runCatching { recorder.release() }
            if (staged.exists()) staged.delete()
            busy.set(false)
        }
    }

    private fun bytesOf(file: File): Long = file.length()

    internal companion object {
        const val KEY_SECONDS = "seconds"
        const val DEFAULT_SECONDS = 5

        /**
         * 单次采集的**内容**时长上限。等待方（入口/插件档位与宿主有效期）放在 130 秒：
         * 比内容多出的 10 秒是编码落盘与回执的收尾余量，等待与内容同值会让满时长录音
         * 被外层提前掐断——三处同表，任何一侧单改都会复发同一个假失败。
         */
        const val MAX_SECONDS = 120
        const val ARTIFACT_EXTENSION = "m4a"

        /** 按 AAC 上限估，宁可高估也不允许越过配额后全量清空。 */
        const val BYTES_PER_SECOND = 32L * 1024L

        /**
         * 时长参数的收敛口径：夹紧而不是拒绝。与截屏同表——越界的请求录到上限为止
         * 并如实回带时长，调用方从回包里看到夹紧，不必先撞一次参数错误。
         * 抽成纯函数是为了在 JVM 单测里把 30 秒以上、120 秒边界与越界三档穷举掉。
         */
        internal fun clampSeconds(raw: Int): Int = raw.coerceIn(1, MAX_SECONDS)
    }
}
