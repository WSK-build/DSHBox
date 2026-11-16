package interlock.relay.core.exec

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * AVC 的 fMP4（fragmented MP4）封装器：**纯 Kotlin，不碰任何 android.* 成员**，
 * 因此封装形状可以在 JVM 上穷举测试；设备侧只负责把 MediaCodec 的输出喂进来。
 *
 * 为什么不用 `MediaMuxer`：它把 moov 写在文件尾（stop 时才落），而实时流是一次
 * 增量消费 —— 消费端（MSE）需要先拿到 ftyp+moov 初始化段，再随到随收 moof/mdat
 * 分片，没有「写完才知道头在哪」一说。这里自己铺盒子：输出即时写进 [out]，
 * 第一帧之前是初始化段，之后每帧一段 moof+mdat，流中途关掉也不欠任何尾部结构。
 *
 * 形状约定：
 * - 单视频轨（track_ID=1）、AVC、初始化段只写一次，moov 带 mvex/trex —— 没有它
 * 消费端按普通 mp4 找 stbl 的样本表，一个分片都解不出来；
 * - 一帧一个分片，sequence 单调递增（mfhd），baseMediaDecodeTime 从**本会话首帧**
 * 归零（调用方给的 pts 是 CLOCK_MONOTONIC 开机时长，直接落轴会让 MSE 从几千秒
 * 开始播），tfdt 用 version 1（64 位）—— 归零之后仍会随直播时长越过 32 位，
 * v0 在 90kHz 下 6.6 小时就封顶；时间轴只向前（过去 pts 压回上一帧）；
 * - 样本时长按相邻两帧 pts 的差记账（首帧用 1/fps）：稳态帧率下两者相等，
 * 帧距抖动时它把抖动记在**前**一帧头上，总时长到流结束差一帧的量 —— 对实时
 * 预览这个误差不存在（流没有结束时刻），换来的是单帧分片可以即时落笔；
 * - 样本字节是编码器吐出的 Annex-B（00 00 01 起始码），写进 mdat 前换成长度
 * 前缀（AVCC），换算在 [AvcNalUnits] 里，同样纯 JVM 可测。
 *
 * 生命周期：[writeInit]（可省略，首个样本会自动带上）→ 若干 [addSample] → [close]。
 * [close] 只 flush 流本身，不追加任何盒子 —— 这是与 MediaMuxer 的本质区别。
 */
class FragmentedMp4Writer(
    /** 编码分辨率，必须为正且偶数（AVC 要求偶数边长）。 */
    private val width: Int,
    private val height: Int,
    /** 名义帧率：只用于首帧样本时长与时间刻度换算，不做任何节流。 */
    private val fps: Int,
    /** SPS（裸 NAL，不带起始码），用于 avcC。 */
    private val sps: ByteArray,
    /** PPS（裸 NAL，不带起始码），用于 avcC。 */
    private val pps: ByteArray,
    private val out: OutputStream,
    /** 媒体时间刻度。90000 是视频轨的常规取值，与 pts 微秒的换算在这里做。 */
    private val timescale: Int = DEFAULT_TIMESCALE,
) {

    init {
        require(width > 0 && width % 2 == 0) { "width must be a positive even number, got $width" }
        require(height > 0 && height % 2 == 0) { "height must be a positive even number, got $height" }
        require(fps > 0) { "fps must be positive, got $fps" }
        require(timescale > 0) { "timescale must be positive, got $timescale" }
        // avcC 里 profile/compat/level 直接取 SPS 的第 1..3 字节：拿不到就说明调用方
        // 递来的不是一条完整的 SPS —— 宁可当场拒，也不落一段解不出的初始化段。
        require(sps.size >= 4) { "sps must carry at least 4 bytes (NAL header + profile/compat/level), got ${sps.size}" }
        require(sps[0].toInt() and 0x1F == NAL_TYPE_SPS) { "sps is not an SPS NAL (type=${sps[0].toInt() and 0x1F})" }
        require(pps.isNotEmpty() && pps[0].toInt() and 0x1F == NAL_TYPE_PPS) { "pps is not a PPS NAL" }
    }

    private var initWritten = false
    private var closed = false
    private var sequence = 0
    private var lastMediaTime = -1L
    private var lastDuration = 1L

    /**
     * 待写的一帧：**等下一帧到了才落段**。首帧的时长只有下一帧的 pts 到手才知道；
     * 立即写只能给固定值，和下一帧的 tfdt 要么重叠、要么留零长度区间（MSE 卡在 t=0）。
     * 缓存一帧、用「到下一帧的差值」写上一帧，时间轴才是连续的。
     */
    private var pendingPayload: ByteArray? = null
    private var pendingPtsUs = -1L
    private var pendingKey = false

    /** 本会话时间轴零点（第一帧的 pts）。 */
    private var basePtsUs = -1L

    /** 初始化段（ftyp + moov）。幂等：写过多帧之后再调一次是空操作。 */
    fun writeInit() {
        check(!closed) { "writer is closed" }
        if (initWritten) return
        out.write(Mp4Boxes.box(FTYP, ftypPayload()))
        out.write(Mp4Boxes.box(MOOV, moovPayload()))
        initWritten = true
    }

    /**
     * 交一帧（Annex-B）并写一段 moof+mdat。返回本段写入的字节数。
     *
     * [ptsUs] 单调：比上一帧早的时刻被压回上一帧的时刻（媒体时间不倒流），
     * 负值按 0 记。空样本或只有起始码没有 NAL 的样本当场抛 —— 调用方（设备侧
     * 循环）把它当作「这一帧坏掉」按帧丢弃，而不是写一个空 mdat 出去。
     */
    fun addSample(annexB: ByteArray, keyFrame: Boolean, ptsUs: Long): Int {
        check(!closed) { "writer is closed" }
        // 空样本/只有起始码仍是畸形输入：保持抛错，交给调用方按帧丢弃。
        val nals = AvcNalUnits.split(annexB)
        require(nals.isNotEmpty()) { "sample carries no NAL unit (only ${annexB.size} bytes of start codes)" }
        // 编码器偶尔会把「只有 SPS/PPS、没有 VCL」的一小段单独吐出来（首段 31 字节，
        // NAL 类型 [7,8]）。SPS/PPS 已经写进初始化段的 avcC，再把它当一个样本写出去，
        // 它会占掉 timescale/fps 的 duration，而真正的 IDR 在几十微秒后就开始 ——
        // fMP4 时间轴重叠，MSE 直接 SourceBuffer 错误。没有 VCL 的样本不是一帧，丢掉。
        if (nals.none { AvcNalUnits.isVclNal(it) }) return 0
        val payload = AvcNalUnits.toLengthPrefixed(annexB)
        if (!initWritten) writeInit()
        if (pendingPayload == null) {
            pendingPayload = payload
            pendingPtsUs = ptsUs
            pendingKey = keyFrame
            if (basePtsUs < 0) basePtsUs = ptsUs
            return 0
        }
        val written = writePending(ptsUs)
        pendingPayload = payload
        pendingPtsUs = ptsUs
        pendingKey = keyFrame
        return written
    }

    /**
     * 把缓存的那一帧写成 moof+mdat：[nextPtsUs] 是下一帧的时刻（最后一帧传 null，
     * 用 1/fps 兜底）。返回写入字节数；没有缓存帧回 0。
     */
    private fun writePending(nextPtsUs: Long?): Int {
        val payload = pendingPayload ?: return 0
        val key = pendingKey
        pendingPayload = null
        val raw = if (pendingPtsUs <= basePtsUs) 0L else (pendingPtsUs - basePtsUs).toMediaTime()
        // 迟到的 pts 压到上一帧之后；正常流里 raw 恰好等于 last + lastDuration。
        val mediaTime = if (lastMediaTime < 0) raw else maxOf(raw, lastMediaTime + lastDuration)
        val duration = if (nextPtsUs == null) {
            (timescale.toLong() / fps).coerceAtLeast(1L)
        } else {
            maxOf((nextPtsUs - pendingPtsUs).toMediaTime(), 1L)
        }
        sequence += 1
        val head = moofPayload(sequence, mediaTime, duration, payload.size, key)
        val body = Mp4Boxes.box(MDAT, payload)
        out.write(head)
        out.write(body)
        lastMediaTime = mediaTime
        lastDuration = duration
        return head.size + body.size
    }

    /** 只 flush 并关流，**不写任何尾盒**：fMP4 的消费端靠分片活着，不靠 moov 在尾部。 */
    fun close() {
        if (closed) return
        // 最后一帧还在缓存里：用 1/fps 的默认时长落出去，别欠一段。
        runCatching { writePending(null) }
        closed = true
        runCatching { out.flush() }
        runCatching { out.close() }
    }

    private fun Long.toMediaTime(): Long = this * timescale / 1_000_000L

    // ── 盒子铺装 ──

    private fun ftypPayload(): ByteArray = ByteArrayOutputStream().also {
        it.write(FRAGMENT_BRAND.toByteArray(Charsets.US_ASCII))
        Mp4Boxes.u32(it, 0)
        COMPATIBLE_BRANDS.forEach { brand -> it.write(brand.toByteArray(Charsets.US_ASCII)) }
    }.toByteArray()

    private fun moovPayload(): ByteArray = ByteArrayOutputStream().also { moov ->
        // mvhd：直播流没有总时长，duration 记 0，消费端按分片自己累计。
        val mvhd = ByteArrayOutputStream().also { mv ->
            Mp4Boxes.u32(mv, 0) // creation
            Mp4Boxes.u32(mv, 0) // modification
            Mp4Boxes.u32(mv, timescale)
            Mp4Boxes.u32(mv, 0) // duration: unknown for a live stream
            Mp4Boxes.u32(mv, 0x00010000) // rate 1.0
            Mp4Boxes.u16(mv, 0x0100) // volume 1.0
            Mp4Boxes.u16(mv, 0) // reserved
            Mp4Boxes.u32(mv, 0)
            Mp4Boxes.u32(mv, 0) // reserved
            mv.write(Mp4Boxes.IDENTITY_MATRIX)
            repeat(6) { Mp4Boxes.u32(mv, 0) } // pre_defined
            Mp4Boxes.u32(mv, NEXT_TRACK_ID)
        }.toByteArray()

        val tkhd = ByteArrayOutputStream().also {
            Mp4Boxes.u32(it, 0) // creation
            Mp4Boxes.u32(it, 0) // modification
            Mp4Boxes.u32(it, TRACK_ID)
            Mp4Boxes.u32(it, 0) // reserved
            Mp4Boxes.u32(it, 0) // duration
            Mp4Boxes.u32(it, 0)
            Mp4Boxes.u32(it, 0) // reserved
            Mp4Boxes.u16(it, 0) // layer
            Mp4Boxes.u16(it, 0) // alternate_group
            Mp4Boxes.u16(it, 0) // volume: a video track is silent
            Mp4Boxes.u16(it, 0) // reserved
            it.write(Mp4Boxes.IDENTITY_MATRIX)
            Mp4Boxes.u32(it, width shl 16) // width 16.16
            Mp4Boxes.u32(it, height shl 16) // height 16.16
        }.toByteArray()

        val mdhd = ByteArrayOutputStream().also {
            Mp4Boxes.u32(it, 0)
            Mp4Boxes.u32(it, 0)
            Mp4Boxes.u32(it, timescale)
            Mp4Boxes.u32(it, 0) // duration: unknown
            Mp4Boxes.u16(it, LANGUAGE_UNDETERMINED)
            Mp4Boxes.u16(it, 0) // pre_defined
        }.toByteArray()

        val hdlr = ByteArrayOutputStream().also { hdlr ->
            Mp4Boxes.u32(hdlr, 0) // pre_defined
            hdlr.write("vide".toByteArray(Charsets.US_ASCII))
            repeat(3) { Mp4Boxes.u32(hdlr, 0) } // reserved
            hdlr.write("VideoHandler".toByteArray(Charsets.US_ASCII))
            hdlr.write(0)
        }.toByteArray()

        val vmhd = ByteArrayOutputStream().also {
            Mp4Boxes.u16(it, 0) // graphicsmode
            Mp4Boxes.u16(it, 0)
            Mp4Boxes.u16(it, 0)
            Mp4Boxes.u16(it, 0) // opcolor
        }.toByteArray()

        val url = Mp4Boxes.fullBox(URL, 0, 1) // flags=1: self-contained, dref 里不落名字
        val dref = Mp4Boxes.fullBox(DREF, 0, 0, Mp4Boxes.u32(1), url)
        val dinf = Mp4Boxes.box(DINF, dref)

        val avcC = avcCPayload()
        val avc1 = ByteArrayOutputStream().also { entry ->
            repeat(6) { entry.write(0) } // reserved
            Mp4Boxes.u16(entry, 1) // data_reference_index
            Mp4Boxes.u16(entry, 0) // pre_defined
            Mp4Boxes.u16(entry, 0) // reserved
            repeat(3) { Mp4Boxes.u32(entry, 0) } // pre_defined
            Mp4Boxes.u16(entry, width)
            Mp4Boxes.u16(entry, height)
            Mp4Boxes.u32(entry, 0x00480000) // hres 72dpi
            Mp4Boxes.u32(entry, 0x00480000) // vres 72dpi
            Mp4Boxes.u32(entry, 0) // reserved
            Mp4Boxes.u16(entry, 1) // frame_count
            entry.write(ByteArray(32)) // compressorname: 留空
            Mp4Boxes.u16(entry, 0x0018) // depth
            Mp4Boxes.u16(entry, -1 and 0xFFFF) // pre_defined = -1
            entry.write(Mp4Boxes.box(AVCC, avcC))
        }.toByteArray()

        val stsd = Mp4Boxes.fullBox(STSD, 0, 0, Mp4Boxes.u32(1), Mp4Boxes.box(AVC1, avc1))
        // 样本表全部留空：样本都在分片里，moov 只描述轨道。
        val emptyCount = Mp4Boxes.u32(0)
        val stts = Mp4Boxes.fullBox(STTS, 0, 0, emptyCount)
        val stsc = Mp4Boxes.fullBox(STSC, 0, 0, emptyCount)
        val stsz = Mp4Boxes.fullBox(STSZ, 0, 0, Mp4Boxes.u32(0), Mp4Boxes.u32(0))
        val stco = Mp4Boxes.fullBox(STCO, 0, 0, emptyCount)
        val stbl = Mp4Boxes.box(STBL, stsd, stts, stsc, stsz, stco)
        val minf = Mp4Boxes.box(MINF, Mp4Boxes.fullBox(VMHD, 0, 1, vmhd), dinf, stbl)
        val mdia = Mp4Boxes.box(MDIA, Mp4Boxes.fullBox(MDHD, 0, 0, mdhd), Mp4Boxes.fullBox(HDLR, 0, 0, hdlr), minf)
        val trak = Mp4Boxes.box(TRAK, Mp4Boxes.fullBox(TKHD, 0, 3, tkhd), mdia)
        // mvex/trex 声明"样本在分片里"：没有它，初始化段不是合法的 fragmented mp4，
        // 严格的消费端（MSE 的 mp4 解析器）会按 stbl 空表直接拒整段流。
        val trex = ByteArrayOutputStream().also {
            Mp4Boxes.u32(it, TRACK_ID)
            Mp4Boxes.u32(it, 1) // default_sample_description_index → avc1
            Mp4Boxes.u32(it, 0) // default_sample_duration：逐样本给
            Mp4Boxes.u32(it, 0) // default_sample_size：逐样本给
            Mp4Boxes.u32(it, 0) // default_sample_flags：逐样本给
        }.toByteArray()
        // mvex 是**普通 box**，不是 full box：它没有 version/flags。写成 fullBox 会多出
        // 4 个零字节，严格解析器把那 4 字节当成一个 size=0 的假盒子，trex 不被识别，
        // 分片里的 tfhd/trun 失去 track 映射（ffmpeg: "no tfhd was found"，MSE:
        // SourceBuffer 错误）。trex 本身仍是 full box。
        val mvex = Mp4Boxes.box(MVEX, Mp4Boxes.fullBox(TREX, 0, 0, trex))
        moov.write(Mp4Boxes.fullBox(MVHD, 0, 0, mvhd))
        moov.write(trak)
        moov.write(mvex)
    }.toByteArray()

    private fun avcCPayload(): ByteArray = ByteArrayOutputStream().also {
        it.write(1) // configurationVersion
        it.write(sps[1].toInt()) // AVCProfileIndication
        it.write(sps[2].toInt()) // profile_compatibility
        it.write(sps[3].toInt()) // AVCLevelIndication
        it.write(0xFF) // lengthSizeMinusOne = 3（4 字节长度前缀）
        it.write(0xE1) // numOfSequenceParameterSets = 1
        Mp4Boxes.u16(it, sps.size)
        it.write(sps)
        it.write(1) // numOfPictureParameterSets = 1
        Mp4Boxes.u16(it, pps.size)
        it.write(pps)
    }.toByteArray()

    /** 一个样本的分片（moof）。dataOffset 以 default-base-is-moof 计：moof 头之后紧跟的 mdat 载荷。 */
    private fun moofPayload(seq: Int, mediaTime: Long, duration: Long, sampleSize: Int, keyFrame: Boolean): ByteArray {
        val sampleFlags = if (keyFrame) SAMPLE_FLAG_SYNC else SAMPLE_FLAG_NON_SYNC
        // trun 头 8 + version/flags 4 + sample_count 4 + data_offset 4 + 每样本 12
        // （duration/size/flags；不写 composition time offset，也不挂它的旗 ——
        // 挂了旗却按无偏移铺字段是结构错误，消费端会按旗读出一个越界偏移）
        val trunSize = 8 + 4 + 4 + 4 + 12
        // tfhd 头 8 + version/flags 4 + track_ID 4（仅 default-base-is-moof 一面旗）
        val tfhdSize = 8 + 4 + 4
        // tfdt v1：version/flags 4 + baseMediaDecodeTime 8 —— 直播没有总时长上限，
        // v0 的 32 位在 90kHz 下 6.6 小时封顶，v1 才装得下长会话。
        val tfdtSize = 8 + 4 + 8
        val trafSize = 8 + tfhdSize + tfdtSize + trunSize
        val moofSize = 8 + (8 + 4 + 4) + trafSize // mfhd = 8 + 4 + 4
        val dataOffset = moofSize + 8 // mdat 头

        val tfhd = ByteArrayOutputStream().also {
            Mp4Boxes.u32(it, TRACK_ID)
        }.toByteArray()
        val tfdt = ByteArrayOutputStream().also {
            Mp4Boxes.u64(it, mediaTime.coerceAtLeast(0L))
        }.toByteArray()
        val trun = ByteArrayOutputStream().also {
            Mp4Boxes.u32(it, 1) // sample_count
            Mp4Boxes.s32(it, dataOffset)
            Mp4Boxes.u32(it, duration.toUInt().toInt())
            Mp4Boxes.u32(it, sampleSize)
            Mp4Boxes.u32(it, sampleFlags)
        }.toByteArray()
        val traf = Mp4Boxes.box(
            TRAF,
            Mp4Boxes.fullBox(TFHD, 0, TFHD_FLAG_DEFAULT_BASE_IS_MOOF, tfhd),
            Mp4Boxes.fullBox(TFDT, 1, 0, tfdt),
            Mp4Boxes.fullBox(
                TRUN, 0,
                TRUN_FLAG_DATA_OFFSET or TRUN_FLAG_DURATION or TRUN_FLAG_SIZE or TRUN_FLAG_FLAGS,
                trun,
            ),
        )
        val mfhd = Mp4Boxes.fullBox(MFHD, 0, 0, Mp4Boxes.u32(seq))
        return Mp4Boxes.box(MOOF, mfhd, traf)
    }

    private companion object {
        const val DEFAULT_TIMESCALE = 90_000
        const val NEXT_TRACK_ID = 2
        const val TRACK_ID = 1
        const val LANGUAGE_UNDETERMINED = 0x55C4
        const val NAL_TYPE_SPS = 7
        const val NAL_TYPE_PPS = 8

        const val FTYP = "ftyp"
        const val MOOV = "moov"
        const val MOOF = "moof"
        const val MDAT = "mdat"
        const val MVHD = "mvhd"
        const val TRAK = "trak"
        const val TKHD = "tkhd"
        const val MDIA = "mdia"
        const val MDHD = "mdhd"
        const val HDLR = "hdlr"
        const val MINF = "minf"
        const val VMHD = "vmhd"
        const val DINF = "dinf"
        const val DREF = "dref"
        const val URL = "url "
        const val STBL = "stbl"
        const val STSD = "stsd"
        const val STTS = "stts"
        const val STSC = "stsc"
        const val STSZ = "stsz"
        const val STCO = "stco"
        const val AVC1 = "avc1"
        const val AVCC = "avcC"
        const val MFHD = "mfhd"
        const val MVEX = "mvex"
        const val TREX = "trex"
        const val TRAF = "traf"
        const val TFHD = "tfhd"
        const val TFDT = "tfdt"
        const val TRUN = "trun"

        const val TFHD_FLAG_DEFAULT_BASE_IS_MOOF = 0x020000
        const val TRUN_FLAG_DATA_OFFSET = 0x000001
        const val TRUN_FLAG_DURATION = 0x000100
        const val TRUN_FLAG_SIZE = 0x000200
        const val TRUN_FLAG_FLAGS = 0x000400

        /** 依赖无关 + 同步样本（关键帧）。 */
        const val SAMPLE_FLAG_SYNC = 0x02000000
        /** 依赖前一帧的非同步样本。 */
        const val SAMPLE_FLAG_NON_SYNC = 0x01010000

        const val FRAGMENT_BRAND = "iso5"
        val COMPATIBLE_BRANDS = listOf("isom", "iso5", "msf1", "mp41")
    }
}

/** MP4 盒子的最小铺装器：纯字节，供 [FragmentedMp4Writer] 与 JVM 测试共用。 */
internal object Mp4Boxes {

    /** 单位矩阵（视频轨 tkhd/vmhd 共用）。 */
    val IDENTITY_MATRIX = byteArrayOf(
        0x00, 0x01, 0x00, 0x00, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0x00, 0x01, 0x00, 0x00, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0x40, 0x00, 0x00, 0x00,
    )

    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArrayOutputStream()) { acc, part -> acc.write(part); acc }.toByteArray()
        val out = ByteArrayOutputStream(8 + body.size)
        u32(out, 8 + body.size)
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    fun fullBox(type: String, version: Int, flags: Int, vararg payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(version and 0xFF)
        body.write((flags shr 16) and 0xFF)
        body.write((flags shr 8) and 0xFF)
        body.write(flags and 0xFF)
        payload.forEach { body.write(it) }
        return box(type, body.toByteArray())
    }

    fun u32(target: OutputStream, value: Int) {
        target.write((value ushr 24) and 0xFF)
        target.write((value ushr 16) and 0xFF)
        target.write((value ushr 8) and 0xFF)
        target.write(value and 0xFF)
    }

    fun u16(target: OutputStream, value: Int) {
        target.write((value ushr 8) and 0xFF)
        target.write(value and 0xFF)
    }

    fun s32(target: OutputStream, value: Int) = u32(target, value)

    fun u32(value: Int): ByteArray {
        val out = ByteArrayOutputStream(4)
        u32(out, value)
        return out.toByteArray()
    }
    fun u64(target: OutputStream, value: Long) {
        u32(target, (value ushr 32).toInt())
        u32(target, value.toInt())
    }
}

/**
 * Annex-B（起始码 00 00 01 / 00 00 00 01）与 AVCC（4 字节长度前缀）之间的纯换算。
 * MediaCodec 的 AVC 输出是 Annex-B；fMP4 的样本必须是长度前缀。两头都在这里，
 * 因此两向都能在 JVM 上穷举：截断的起始码、零长 NAL、连着的起始码都不产出空样本。
 */
internal object AvcNalUnits {

    /**
     * Annex-B → 长度前缀。没有任何 NAL（空、只有起始码、只有零字节）时抛
     * [IllegalArgumentException]；零长 NAL 直接丢弃。
     */
    fun toLengthPrefixed(annexB: ByteArray): ByteArray {
        val nals = split(annexB)
        require(nals.isNotEmpty()) { "sample carries no NAL unit (only ${annexB.size} bytes of start codes)" }
        val out = ByteArrayOutputStream(nals.sumOf { it.size + 4 })
        nals.forEach { nal ->
            Mp4Boxes.u32(out, nal.size)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /**
     * 这段 Annex-B 里有没有 VCL（真正承载图像）的 NAL。
     *
     * 只有 SPS(7)/PPS(8)/SEI(6)/AUD(9) 的样本不是一帧；fMP4 的 avcC 已经带了 SPS/PPS，
     * 不能把它写成样本。VCL 只认非 IDR(1) 与 IDR(5)。
     */
    fun hasVcl(annexB: ByteArray): Boolean = split(annexB).any(::isVclNal)

    /** 一条去掉起始码的 NAL 是不是 VCL（非 IDR 1 / IDR 5）。 */
    fun isVclNal(nal: ByteArray): Boolean {
        val type = nal.firstOrNull()?.toInt()?.and(0x1F) ?: return false
        return type == 1 || type == 5
    }

    /**
     * 拆 Annex-B，回**不带起始码**的 NAL 列表。顺序保留；零长 NAL 丢弃。
     * 截断的输入（最后一个起始码只有前几个字节、没有载荷）不产出 NAL：
     * 末尾残留的零字节按「下一条起始码的前缀」从上一条 NAL 的结尾剥掉。
     */
    fun split(annexB: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var i = 0
        while (i + 2 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                // 4 字节起始码（00 00 00 01）从它的第 2 个零开始也算一次命中：
                // 两者按同一口径处理，NAL 载荷都从 01 之后开始。
                starts += i + 3
                i += 3
            } else {
                i += 1
            }
        }
        if (starts.isEmpty()) return emptyList()
        val nals = mutableListOf<ByteArray>()
        for (index in starts.indices) {
            val from = starts[index]
            var to = if (index + 1 < starts.size) {
                // 下一个起始码的开头：回退它前面的零字节（3 字节码是 00 00 01，
                // 4 字节码多出的那一个零也算上一个 NAL 的结尾填充）。
                var end = starts[index + 1] - 3
                while (end > from && annexB[end - 1].toInt() == 0) end -= 1
                end
            } else {
                // 最后一条：剥掉结尾的零字节 —— 它们是流被截断在起始码中间时残留的
                // 前缀（00 / 00 00 / 00 00 00），不是上一条 NAL 的载荷。
                var end = annexB.size
                while (end > from && annexB[end - 1].toInt() == 0) end -= 1
                end
            }
            if (to > from) nals += annexB.copyOfRange(from, to)
        }
        return nals
    }

    /** Annex-B 里第 [index] 条 NAL（不带起始码）；不存在时回 null。供 csd 提 SPS/PPS 用。 */
    fun nalAt(annexB: ByteArray, index: Int): ByteArray? = split(annexB).getOrNull(index)
}
