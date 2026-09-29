package interlock.relay.core.exec

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/**
 * 把一帧一帧的画面编成一段 mp4。
 *
 * 为什么不用 `MediaRecorder`：它要一块能持续往 surface 上画的屏，而后台那块可信虚拟屏的
 * 表面在**另一个进程**里（Shizuku 用户服务持有 ImageReader），帧只能一帧一帧递过来。
 *
 * 也不用 `screenrecord`：它只认物理屏编号，虚拟屏编号递进去回的是 "Invalid physical
 * display ID"（逻辑编号与 SurfaceFlinger 那侧的编号都不行）。
 *
 * 输入是 RGBA 位图，编码器要 NV12（`YUV420SemiPlanar`），换算在这里手写：平台没有公开的
 * RGBA→YUV 转换器，`ScriptIntrinsic` 那条已随 RenderScript 一起废弃。系数取 BT.601 定点式，
 * 亮度落在 16..235、色度落在 16..240 的 limited range —— 铺满 0..255 会让画面整体偏灰。
 *
 * 生命周期只有三条：[start] → 若干 [push] → [finish]。[finish] 必须被调用到，否则容器里
 * 没有 moov 盒，落盘的文件是一段读不出时长的空壳。
 */
class FrameEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitsPerSecond: Int,
) {

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var frameIndex = 0
    private var lastPtsUs = 0L
    private var muxerRunning = false

    private var stride = 0
    private var sliceHeight = 0
    private var frameBytes = 0
    private var plane = ByteArray(0)

    /** 编码器与容器都建起来。任一步失败就自己收干净，回 false 让调用方如实报不可用。 */
    fun start(output: File): Boolean {
        // 建与配都在挑选那一步一起做完：声明吃得下这套像素格式的编码器，仍可能把 configure 拒掉。
        val created = pickEncoder() ?: return false
        codec = created
        return runCatching {
            created.start()
            // 补齐值只能问编码器本身：各家实现对 stride/sliceHeight 给的数不同，
            // 按自己算的宽高铺过去会得到一幅斜掉的画面，而这种坏帧不会报任何错。
            val input = created.inputFormat
            stride = input.orDefault(MediaFormat.KEY_STRIDE, width)
            sliceHeight = input.orDefault(MediaFormat.KEY_SLICE_HEIGHT, height)
            frameBytes = stride * sliceHeight * 3 / 2
            plane = ByteArray(frameBytes)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            true
        }.getOrElse {
            release()
            false
        }
    }

    /**
     * 挑一台**吃得下 NV12** 的 AVC 编码器。
     *
     * `createEncoderByType` 回的是系统推荐的那一台，而推荐与"能接我们铺的像素格式"是两件事：
     * 同一台机器上常常硬件、软件各有一台 AVC 编码器，一台只认 Flexible、另一台认
     * SemiPlanar。挑错那台时 `configure` 直接抛，回包说的是"这台设备没有 h264 编码器"，
     * 而实情是"有一台不吃这种格式，另一台吃" —— 于是只在模拟器上成立的一条能力，
     * 到真机上被记成设备不支持。
     *
     * 只认 SemiPlanar 这一档：[convert] 铺的就是 Y 平面 + 交错 UV 的 NV12。
     * `YUV420Flexible` 不接 —— 它的色度是交错还是分平面、UV 谁在前，都要按实现回读的参数
     * 现推，接错了得到的是一段颜色反了却**不报任何错**的录像，比录不出来更坏。
     */
    private fun pickEncoder(): MediaCodec? {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitsPerSecond)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_SECONDS)
        }
        val wanted = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrNull().orEmpty()
        val matches = infos.filter { info ->
            info.isEncoder && runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    ?.colorFormats?.contains(wanted) == true
            }.getOrDefault(false)
        }
        // 逐台建、逐台配：声明了这套像素格式不等于吃得下（档位、对齐、HAL 已死都会拒），
        // 而 createByCodecName 本身也可能因为别名或被禁用抛异常。只认第一台就把
        // "其实后面那台能用"说成"这台设备没有能用的编码器"。
        matches.forEach { info ->
            configureOrRelease(runCatching { MediaCodec.createByCodecName(info.name) }.getOrNull(), format)
                ?.let { return it }
        }
        // 一台都没配通时退回系统推荐的那一台（列表为空、或编码器只声明
        // Flexible/Planar 时以系统推荐台兜底）。
        return configureOrRelease(
            runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull(),
            format,
        )
    }

    /** 配不通就当场 release：编码器实例是有限资源，漏一台之后每一条调用都建不起来。 */
    private fun configureOrRelease(created: MediaCodec?, format: MediaFormat): MediaCodec? {
        val codec = created ?: return null
        val configured = runCatching {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }.isSuccess
        if (!configured) runCatching { codec.release() }
        return if (configured) codec else null
    }

    /**
     * 交一帧进去。false 只表示这一帧没被接住（编码器此刻不空），不是整段失败 ——
     * 一帧的代价是画面跳一下，不值得为此丢掉已经录下的部分。
     *
     * [ptsUs] 是这一帧**拍下的时刻**（相对本段起点），不是帧序号换算出来的：
     * 取帧给不到目标帧率时，按序号打时间戳会把八秒的实况压成三秒半的快放录像，
     * 而回包里的 seconds 说的就是那八秒。
     */
    fun push(source: Bitmap, ptsUs: Long): Boolean {
        val active = codec ?: return false
        if (source.width < 1 || source.height < 1) return false
        val index = runCatching { active.dequeueInputBuffer(INPUT_TIMEOUT_US) }.getOrDefault(-1)
        drain(active, timeoutUs = 0)
        if (index < 0) return false
        // `MediaCodec` 没有公开的 releaseInputBuffer：借到的 input 槽只有 queueInputBuffer
        // 一条归还路径，所以这里不试图"还槽"，只保证不带着未借到的 index 往下走。
        val input = runCatching { active.getInputBuffer(index) }.getOrNull() ?: return false
        convert(source)
        input.clear()
        input.put(plane, 0, frameBytes)
        active.queueInputBuffer(index, 0, frameBytes, ptsUs.coerceAtLeast(lastPtsUs), 0)
        lastPtsUs = ptsUs
        frameIndex++
        drain(active, timeoutUs = 0)
        return true
    }

    /** 送 EOS、把剩下的包抽干、结掉容器，回落到盘上的字节数。0 表示一段都没成。 */
    fun finish(): Long {
        val active = codec
        if (active == null) {
            release()
            return 0L
        }
        runCatching {
            val index = active.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index >= 0) active.queueInputBuffer(index, 0, 0, lastPtsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
        // 抽干要有上界：EOS 前编码器还在排参考帧，无界的循环在这里就是一次不返回的调用。
        var waited = 0L
        while (waited < DRAIN_BUDGET_US) {
            val before = frameIndex
            val sawEnd = drain(active, timeoutUs = FLUSH_POLL_US)
            if (sawEnd) break
            waited += FLUSH_POLL_US
            if (before == frameIndex && waited > FLUSH_POLL_US * DRAIN_IDLE_STOP) break
        }
        release()
        return written
    }

    private var written = 0L

    /**
     * 把编码器已经产出的包写进容器。返回 true 表示见到了 EOS。
     */
    private fun drain(active: MediaCodec, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val code = runCatching { active.dequeueOutputBuffer(info, timeoutUs) }.getOrDefault(
                MediaCodec.INFO_TRY_AGAIN_LATER,
            )
            when {
                code == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val opened = muxer ?: return true
                    track = opened.addTrack(active.outputFormat)
                    opened.start()
                    muxerRunning = true
                }

                code >= 0 -> {
                    if (info.size > 0 && muxerRunning && track >= 0) {
                        val payload = active.getOutputBuffer(code)
                        if (payload != null) {
                            payload.position(info.offset)
                            payload.limit(info.offset + info.size)
                            runCatching {
                                muxer?.writeSampleData(track, payload, info)
                                written += info.size.toLong()
                            }
                        }
                    }
                    active.releaseOutputBuffer(code, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
                }

                else -> return false
            }
        }
    }

    /**
     * RGBA_8888 → NV12，顺带把画面缩到编码尺寸。
     *
     * 缩放用最近邻取样而不是先 `createScaledBitmap`：后者每次都要先建一张全尺寸位图再画一遍，
     * 而这段循环一秒钟要走好几回。
     */
    private fun convert(source: Bitmap) {
        val rowSize = source.width
        if (scratch.size < rowSize * source.height) scratch = IntArray(rowSize * source.height)
        source.getPixels(scratch, 0, rowSize, 0, 0, rowSize, source.height)
        val stepX = maxOf(1, source.width / width)
        val stepY = maxOf(1, source.height / height)
        var row = 0
        while (row < height) {
            val srcRow = minOf(source.height - 1, row * stepY)
            var col = 0
            var at = row * stride
            while (col < width) {
                val color = scratch[srcRow * rowSize + minOf(rowSize - 1, col * stepX)]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                plane[at + col] = ((((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
                    .coerceIn(16, 235)).toByte()
                col += 1
            }
            row += 1
        }
        val chromaBase = stride * sliceHeight
        var chromaRow = 0
        while (chromaRow < height / 2) {
            val srcRow = minOf(source.height - 1, chromaRow * 2 * stepY)
            var col = 0
            var at = chromaBase + chromaRow * stride
            while (col < width / 2) {
                val color = scratch[srcRow * rowSize + minOf(rowSize - 1, col * 2 * stepX)]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                plane[at + col * 2] = ((((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                    .coerceIn(16, 240)).toByte()
                plane[at + col * 2 + 1] = ((((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                    .coerceIn(16, 240)).toByte()
                col += 1
            }
            chromaRow += 1
        }
    }

    private var scratch = IntArray(0)

    private fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { if (muxerRunning && track >= 0) muxer?.stop() }
        runCatching { muxer?.release() }
        muxerRunning = false
        codec = null
        muxer = null
        track = -1
    }

    private fun MediaFormat.orDefault(key: String, fallback: Int): Int =
        runCatching { getInteger(key) }.getOrNull() ?: fallback

    private companion object {
        const val I_FRAME_SECONDS = 2
        const val INPUT_TIMEOUT_US = 10_000L
        const val FLUSH_POLL_US = 10_000L
        const val DRAIN_BUDGET_US = 2_000_000L

        /** 连续这么多轮抽不出新包就当结束：EOS 万一没发回来，这里比上界先收场。 */
        const val DRAIN_IDLE_STOP = 20
    }
}
