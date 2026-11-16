package interlock.relay.core.exec

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * fMP4 封装器的纯 JVM 判据：盒子结构与尺寸自洽、初始化段只落一次、sequence/时间戳
 * 单调、关键帧旗标正确、Annex-B→AVCC 换算对截断与异常输入的行为，以及「关流不追加
 * 尾部 moov」这条与 MediaMuxer 的本质区别。设备侧的 MediaCodec 喂帧路径不在本范围内。
 */
class FragmentedMp4Test {

    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x1F, 0xAC.toByte(), 0xD9.toByte(), 0x40, 0x50)
    private val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xEC.toByte(), 0xB2.toByte(), 0x2C)

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        nals.forEach { nal ->
            out.write(byteArrayOf(0, 0, 0, 1))
            out.write(nal)
        }
        return out.toByteArray()
    }

    private fun writer(sink: ByteArrayOutputStream = ByteArrayOutputStream()) =
        FragmentedMp4Writer(1280, 720, fps = 15, sps = sps, pps = pps, out = sink)

    // ── 盒子遍历助手 ──

    private class Box(val type: String, val body: ByteArray)

    private fun boxesOf(bytes: ByteArray): List<Box> {
        val result = mutableListOf<Box>()
        var at = 0
        while (at + 8 <= bytes.size) {
            val size = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
            assertTrue("box size $size at $at must cover the remaining ${bytes.size - at} bytes", size >= 8 && at + size <= bytes.size)
            val type = String(bytes, at + 4, 4, Charsets.US_ASCII)
            result += Box(type, bytes.copyOfRange(at + 8, at + size))
            at += size
        }
        assertEquals("every byte must sit inside a box", bytes.size, at)
        return result
    }

    private fun findBox(bytes: ByteArray, path: List<String>): Box? {
        var current = Box("", bytes)
        for (step in path) {
            // 下降不走严格走盒：stsd 在子盒前有 8 字节 fullBox 头，avc1 前面是定长的
            // sample-entry 字段，按 8 字节对齐走都会踩进字段里。按字节扫目标类型，
            // 用「尺寸 ≥8 且不越过本层」把载荷里的假命中挡掉 —— 只用于下降定位。
            current = scanForBox(current.body, step) ?: return null
        }
        return current
    }

    private fun scanForBox(body: ByteArray, type: String): Box? {
        var p = 0
        while (p + 8 <= body.size) {
            val size = ((body[p].toInt() and 0xFF) shl 24) or ((body[p + 1].toInt() and 0xFF) shl 16) or
                ((body[p + 2].toInt() and 0xFF) shl 8) or (body[p + 3].toInt() and 0xFF)
            if (size >= 8 && p + size <= body.size && String(body, p + 4, 4, Charsets.US_ASCII) == type) {
                return Box(type, body.copyOfRange(p + 8, p + size))
            }
            p += 1
        }
        return null
    }

    private fun FragmentedMp4Test.Box.childBoxes(): List<Box> = boxesOf(body)

    // ── 初始化段 ──

    @Test
    fun `init segment is ftyp then moov with avcC carrying the exact sps and pps`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.writeInit()
        w.close()

        val top = boxesOf(sink.toByteArray())
        assertEquals(listOf("ftyp", "moov"), top.map { it.type })
        val avcC = findBox(sink.toByteArray(), listOf("moov", "trak", "mdia", "minf", "stbl", "stsd", "avc1", "avcC"))
            ?: return fail("avcC not found")
        // 配置字节：version=1，profile/compat/level 取 SPS[1..3]，长度前缀 4 字节
        assertEquals(1, avcC.body[0].toInt() and 0xFF)
        assertEquals(sps[1], avcC.body[1])
        assertEquals(sps[2], avcC.body[2])
        assertEquals(sps[3], avcC.body[3])
        assertEquals(0xFF, avcC.body[4].toInt() and 0xFF)
        // 1 条 SPS：内容逐字节等于构造时给的裸 NAL
        assertEquals(0xE1, avcC.body[5].toInt() and 0xFF)
        val spsLen = ((avcC.body[6].toInt() and 0xFF) shl 8) or (avcC.body[7].toInt() and 0xFF)
        assertEquals(sps.size, spsLen)
        assertArrayEquals(sps, avcC.body.copyOfRange(8, 8 + spsLen))
        // 1 条 PPS
        val ppsAt = 8 + spsLen
        assertEquals(1, avcC.body[ppsAt].toInt() and 0xFF)
        val ppsLen = ((avcC.body[ppsAt + 1].toInt() and 0xFF) shl 8) or (avcC.body[ppsAt + 2].toInt() and 0xFF)
        assertEquals(pps.size, ppsLen)
        assertArrayEquals(pps, avcC.body.copyOfRange(ppsAt + 3, ppsAt + 3 + ppsLen))
        // avc1 样本入口报的宽高与构造一致
        val avc1 = findBox(sink.toByteArray(), listOf("moov", "trak", "mdia", "minf", "stbl", "stsd", "avc1"))!!
        val reportedWidth = ((avc1.body[24].toInt() and 0xFF) shl 8) or (avc1.body[25].toInt() and 0xFF)
        val reportedHeight = ((avc1.body[26].toInt() and 0xFF) shl 8) or (avc1.body[27].toInt() and 0xFF)
        assertEquals(1280, reportedWidth)
        assertEquals(720, reportedHeight)
    }

    @Test
    fun `mvex is a plain box with exactly one trex`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.writeInit()
        w.close()
        val moov = boxesOf(sink.toByteArray()).first { it.type == "moov" }
        val mvex = boxesOf(moov.body).first { it.type == "mvex" }
        // mvex 不是 full box：body 必须正好是一个 32 字节的 trex，没有 4 字节 version/flags。
        assertEquals(32, mvex.body.size)
        assertEquals(listOf("trex"), boxesOf(mvex.body).map { it.type })
    }

    @Test
    fun `init is written exactly once even across samples and explicit calls`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.writeInit()
        w.addSample(annexB(byteArrayOf(0x65, 1, 2, 3)), keyFrame = true, ptsUs = 0)
        w.writeInit()
        w.addSample(annexB(byteArrayOf(0x41, 4, 5, 6)), keyFrame = false, ptsUs = 66_667)
        w.close()
        val all = sink.toByteArray()
        // 顶层走盒：只应有 1 个 ftyp、1 个 moov，其余全是 moof/mdat
        val kinds = mutableListOf<String>()
        var at = 0
        while (at + 8 <= all.size) {
            val size = ((all[at].toInt() and 0xFF) shl 24) or ((all[at + 1].toInt() and 0xFF) shl 16) or
                ((all[at + 2].toInt() and 0xFF) shl 8) or (all[at + 3].toInt() and 0xFF)
            kinds += String(all, at + 4, 4, Charsets.US_ASCII)
            at += size
        }
        assertEquals(listOf("ftyp", "moov", "moof", "mdat", "moof", "mdat"), kinds)
    }

    // ── 分片：sequence / 时间戳 / 旗标 ──

    @Test
    fun `fragments carry monotonically increasing sequence numbers starting at one`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        repeat(3) { index ->
            w.addSample(annexB(byteArrayOf(0x41, index.toByte())), keyFrame = index == 0, ptsUs = index * 66_667L)
        }
        w.close()
        val seqs = mutableListOf<Int>()
        var at = 0
        val all = sink.toByteArray()
        while (at + 8 <= all.size) {
            val size = ((all[at].toInt() and 0xFF) shl 24) or ((all[at + 1].toInt() and 0xFF) shl 16) or
                ((all[at + 2].toInt() and 0xFF) shl 8) or (all[at + 3].toInt() and 0xFF)
            if (String(all, at + 4, 4, Charsets.US_ASCII) == "moof") {
                val mfhd = boxesOf(all.copyOfRange(at + 8, at + size)).first { it.type == "mfhd" }
                // mfhd: version/flags(4) + sequence(4)
                seqs += ((mfhd.body[4].toInt() and 0xFF) shl 24) or ((mfhd.body[5].toInt() and 0xFF) shl 16) or
                    ((mfhd.body[6].toInt() and 0xFF) shl 8) or (mfhd.body[7].toInt() and 0xFF)
            }
            at += size
        }
        assertEquals(listOf(1, 2, 3), seqs)
    }

    @Test
    fun `a late pts is coerced forward so media time never goes backwards`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = 500_000)
        w.addSample(annexB(byteArrayOf(0x41, 2)), keyFrame = false, ptsUs = 100_000) // 早于上一帧
        w.addSample(annexB(byteArrayOf(0x41, 3)), keyFrame = false, ptsUs = 900_000)
        w.close()
        val times = baseMediaDecodeTimes(sink.toByteArray())
        // 首帧缓存到第二帧才写：时长 = 到第二帧的差值；第二帧紧接其后。
        assertEquals(listOf(0L, 1L, 72_001L), times.map { it.first }) // 90000/1e6
        // 第二段被压平后时长至少 1 tick，不得为 0 或负
        assertTrue(times[1].second >= 1L)
    }

    @Test
    fun `first fragment starts at media time zero and key frames carry the sync flag`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = 0)
        w.addSample(annexB(byteArrayOf(0x41, 2)), keyFrame = false, ptsUs = 66_667)
        w.close()
        val times = baseMediaDecodeTimes(sink.toByteArray())
        assertEquals(0L, times[0].first)
        val flags = sampleFlags(sink.toByteArray())
        assertEquals(listOf(0x02000000, 0x01010000), flags)
    }

    /** 每个 moof 的 (baseMediaDecodeTime, 首样本时长, tfdt version)，按流序回。 */
    private fun fragments(all: ByteArray): List<Triple<Long, Long, Int>> {
        val result = mutableListOf<Triple<Long, Long, Int>>()
        var at = 0
        while (at + 8 <= all.size) {
            val size = ((all[at].toInt() and 0xFF) shl 24) or ((all[at + 1].toInt() and 0xFF) shl 16) or
                ((all[at + 2].toInt() and 0xFF) shl 8) or (all[at + 3].toInt() and 0xFF)
            if (String(all, at + 4, 4, Charsets.US_ASCII) == "moof") {
                val traf = boxesOf(all.copyOfRange(at + 8, at + size)).first { it.type == "traf" }
                val tfdt = traf.childBoxes().first { it.type == "tfdt" }
                assertEquals("tfdt must be version 1 (64-bit base)", 1, tfdt.body[0].toInt() and 0xFF)
                var base = 0L
                for (i in 4..11) base = (base shl 8) or (tfdt.body[i].toLong() and 0xFF)
                val trun = traf.childBoxes().first { it.type == "trun" }
                // trun body 前 4 字节是 version/flags，随后 sample_count(4) data_offset(4) duration(4)...
                val duration = ((trun.body[12].toLong() and 0xFF) shl 24) or ((trun.body[13].toLong() and 0xFF) shl 16) or
                    ((trun.body[14].toLong() and 0xFF) shl 8) or (trun.body[15].toLong() and 0xFF)
                result += Triple(base, duration, tfdt.body[0].toInt() and 0xFF)
            }
            at += size
        }
        return result
    }

    private fun baseMediaDecodeTimes(all: ByteArray): List<Pair<Long, Long>> =
        fragments(all).map { it.first to it.second }

    /** 每个 moof 首样本的 sample_flags，按流序回。 */
    private fun sampleFlags(all: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        var at = 0
        while (at + 8 <= all.size) {
            val size = ((all[at].toInt() and 0xFF) shl 24) or ((all[at + 1].toInt() and 0xFF) shl 16) or
                ((all[at + 2].toInt() and 0xFF) shl 8) or (all[at + 3].toInt() and 0xFF)
            if (String(all, at + 4, 4, Charsets.US_ASCII) == "moof") {
                val traf = boxesOf(all.copyOfRange(at + 8, at + size)).first { it.type == "traf" }
                val trun = traf.childBoxes().first { it.type == "trun" }
                // body: version/flags(4) sample_count(4) data_offset(4) duration(4) size(4) flags(4)
                // （CTO 在末尾且未写）→ flags 在 20..23
                result += ((trun.body[20].toInt() and 0xFF) shl 24) or ((trun.body[21].toInt() and 0xFF) shl 16) or
                    ((trun.body[22].toInt() and 0xFF) shl 8) or (trun.body[23].toInt() and 0xFF)
            }
            at += size
        }
        return result
    }

    @Test
    fun `trun data offset lands exactly on the mdat payload that follows the moof`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1, 2, 3, 4)), keyFrame = true, ptsUs = 0)
        w.close()
        val all = sink.toByteArray()
        val moofAt = 0 // ftyp+moov 在前，第一个 moof 之后…… 直接找
        var at = 0
        var moofStart = -1
        while (at + 8 <= all.size) {
            val size = ((all[at].toInt() and 0xFF) shl 24) or ((all[at + 1].toInt() and 0xFF) shl 16) or
                ((all[at + 2].toInt() and 0xFF) shl 8) or (all[at + 3].toInt() and 0xFF)
            if (String(all, at + 4, 4, Charsets.US_ASCII) == "moof" && moofStart < 0) moofStart = at
            at += size
        }
        assertTrue(moofStart >= 0)
        val moof = boxesOf(all.copyOfRange(moofStart, all.size)).first { it.type == "moof" }
        val trun = boxesOf(moof.body).first { it.type == "traf" }.let { traf ->
            boxesOf(traf.body).first { it.type == "trun" }
        }
        val dataOffset = ((trun.body[8].toInt() and 0xFF) shl 24) or ((trun.body[9].toInt() and 0xFF) shl 16) or
            ((trun.body[10].toInt() and 0xFF) shl 8) or (trun.body[11].toInt() and 0xFF)
        // default-base-is-moof：偏移相对 moof 起点。moof 全盒 = 8 + body.size，
        // 其后是 mdat 的 8 字节头，载荷紧跟 —— 偏移必须精确落在载荷上。
        assertEquals(moof.body.size + 16, dataOffset)
    }

    // ── 关流不写尾部 ──

    @Test
    fun `closing appends no trailing moov - the stream ends on the last mdat`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = 0)
        w.close()
        val afterFirstClose = sink.toByteArray()
        w.close() // 幂等
        assertEquals(afterFirstClose.size, sink.size())
        val text = String(afterFirstClose, Charsets.ISO_8859_1)
        assertEquals("moov must appear exactly once (the init segment)", 1, "moov".let { needle ->
            var count = 0
            var idx = text.indexOf(needle)
            while (idx >= 0) {
                count++
                idx = text.indexOf(needle, idx + 1)
            }
            count
        })
    }

    // ── 构造与输入校验 ──

    @Test
    fun `malformed construction inputs are rejected`() {
        val sink = ByteArrayOutputStream()
        assertOddWidth(sink)
        assertBadSps(sink)
        assertBadPps(sink)
    }

    private fun assertOddWidth(sink: ByteArrayOutputStream) {
        try {
            FragmentedMp4Writer(1281, 720, fps = 15, sps = sps, pps = pps, out = sink)
            fail("odd width must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("width"))
        }
    }

    private fun assertBadSps(sink: ByteArrayOutputStream) {
        try {
            FragmentedMp4Writer(1280, 720, fps = 15, sps = byteArrayOf(0x67), pps = pps, out = sink)
            fail("a 1-byte sps must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("sps"))
        }
        try {
            FragmentedMp4Writer(1280, 720, fps = 15, sps = byteArrayOf(0x41, 1, 2, 3), pps = pps, out = sink)
            fail("a non-SPS NAL must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("sps"))
        }
    }

    private fun assertBadPps(sink: ByteArrayOutputStream) {
        try {
            FragmentedMp4Writer(1280, 720, fps = 15, sps = sps, pps = byteArrayOf(0x67, 1), out = sink)
            fail("a non-PPS NAL must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("pps"))
        }
    }

    @Test
    fun `empty and start-code-only samples are rejected without touching the output`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        val empty = try {
            w.addSample(ByteArray(0), keyFrame = true, ptsUs = 0)
            fail("empty sample must be rejected")
        } catch (expected: IllegalArgumentException) {
            0
        }
        assertEquals(0, empty)
        try {
            w.addSample(byteArrayOf(0, 0, 0, 1), keyFrame = true, ptsUs = 0)
            fail("a bare start code carries no NAL")
        } catch (expected: IllegalArgumentException) {
        }
        assertEquals("rejected samples must not write anything", 0, sink.size())
    }

    @Test
    fun `hasVcl tells a picture from a config-only sample`() {
        assertFalse("SPS/PPS 不是一帧", AvcNalUnits.hasVcl(annexB(sps, pps)))
        assertTrue("IDR 是 VCL", AvcNalUnits.hasVcl(annexB(byteArrayOf(0x65))))
        assertTrue("非 IDR 是 VCL", AvcNalUnits.hasVcl(annexB(byteArrayOf(0x41))))
        assertTrue("带内 SPS + IDR 仍是一帧", AvcNalUnits.hasVcl(annexB(sps, byteArrayOf(0x65))))
    }

    @Test
    fun `config only sample does not become an fMP4 sample`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        assertEquals(
            "只有 SPS/PPS 的样本必须回 0，不写 init/moof/mdat",
            0,
            w.addSample(annexB(sps, pps), keyFrame = true, ptsUs = 0),
        )
        assertEquals(0, sink.size())

        val wrote = w.addSample(annexB(byteArrayOf(0x65, 1, 2, 3)), keyFrame = true, ptsUs = 0)
        assertEquals("首帧先缓存，等下一帧才知道时长", 0, wrote)
        var boxes = boxesOf(sink.toByteArray())
        assertEquals("缓存帧还没落 moof", 0, boxes.count { it.type == "moof" })
        assertEquals("缓存帧还没落 mdat", 0, boxes.count { it.type == "mdat" })
        w.close()
        boxes = boxesOf(sink.toByteArray())
        assertEquals("收场时把缓存的帧落成一个 moof", 1, boxes.count { it.type == "moof" })
        assertEquals("收场时把缓存的帧落成一个 mdat", 1, boxes.count { it.type == "mdat" })
    }

    @Test
    fun `addSample after close is refused instead of appending to a dead stream`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = 0)
        w.close()
        try {
            w.addSample(annexB(byteArrayOf(0x41, 2)), keyFrame = false, ptsUs = 66_667)
            fail("writing after close must be refused")
        } catch (expected: IllegalStateException) {
        }
    }

    // ── Annex-B → AVCC 换算 ──

    @Test
    fun `annex-b samples are length-prefixed in mdat preserving nal order`() {
        val nal1 = byteArrayOf(0x65, 1, 2, 3)
        val nal2 = byteArrayOf(0x41, 9)
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(nal1, nal2), keyFrame = true, ptsUs = 0)
        w.close()
        val all = sink.toByteArray()
        var at = 0
        var mdat: ByteArray? = null
        while (at + 8 <= all.size) {
            val size = ((all[at].toInt() and 0xFF) shl 24) or ((all[at + 1].toInt() and 0xFF) shl 16) or
                ((all[at + 2].toInt() and 0xFF) shl 8) or (all[at + 3].toInt() and 0xFF)
            if (String(all, at + 4, 4, Charsets.US_ASCII) == "mdat") mdat = all.copyOfRange(at + 8, at + size)
            at += size
        }
        val payload = mdat!!
        var p = 0
        val seen = mutableListOf<ByteArray>()
        while (p + 4 <= payload.size) {
            val len = ((payload[p].toInt() and 0xFF) shl 24) or ((payload[p + 1].toInt() and 0xFF) shl 16) or
                ((payload[p + 2].toInt() and 0xFF) shl 8) or (payload[p + 3].toInt() and 0xFF)
            seen += payload.copyOfRange(p + 4, p + 4 + len)
            p += 4 + len
        }
        assertEquals(p, payload.size)
        assertArrayEquals(nal1, seen[0])
        assertArrayEquals(nal2, seen[1])
    }

    @Test
    fun `mixed three and four byte start codes both split cleanly`() {
        val nal = byteArrayOf(0x65, 7, 7)
        val three = byteArrayOf(0, 0, 1) + nal
        val four = byteArrayOf(0, 0, 0, 1) + nal
        assertArrayEquals(arrayOf(nal), AvcNalUnits.split(three).toTypedArray())
        assertArrayEquals(arrayOf(nal), AvcNalUnits.split(four).toTypedArray())
        // 混排 + 结尾截断的起始码
        val mixed = four + three + byteArrayOf(0, 0)
        assertArrayEquals(arrayOf(nal, nal), AvcNalUnits.split(mixed).toTypedArray())
        // 长度前缀输出的总量账
        val prefixed = AvcNalUnits.toLengthPrefixed(mixed)
        assertEquals(4 + nal.size + 4 + nal.size, prefixed.size)
    }

    @Test
    fun `truncated nal payloads are dropped and zero-length nals vanish`() {
        // 起始码后面只有零：既不是有效 NAL 也不是截断载荷 —— 产出为空
        assertTrue(AvcNalUnits.split(byteArrayOf(0, 0, 0, 1)).isEmpty())
        // 4 字节码的多余零不算独立 NAL
        val nal = byteArrayOf(0x41, 1)
        val padded = byteArrayOf(0, 0, 0, 0, 1) + nal
        assertArrayEquals(arrayOf(nal), AvcNalUnits.split(padded).toTypedArray())
    }

    @Test
    fun `nalAt returns the nth nal with start codes stripped`() {
        val spsNal = byteArrayOf(0x67, 1, 2)
        val ppsNal = byteArrayOf(0x68, 3)
        val csd = byteArrayOf(0, 0, 0, 1) + spsNal + byteArrayOf(0, 0, 1) + ppsNal
        assertArrayEquals(spsNal, AvcNalUnits.nalAt(csd, 0))
        assertArrayEquals(ppsNal, AvcNalUnits.nalAt(csd, 1))
        assertEquals(null, AvcNalUnits.nalAt(csd, 2))
    }

    // ── 盒级判据 ──

    @Test
    fun `moov carries mvex with a trex pinning track one and sample description one`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.writeInit()
        w.close()
        val mvex = findBox(sink.toByteArray(), listOf("moov", "mvex")) ?: return fail("mvex not found")
        val trex = findBox(mvex.body, listOf("trex")) ?: return fail("trex not found")
        // trex body: version/flags(4) + track_ID + default_sample_description_index + 3×default
        val trackId = ((trex.body[4].toInt() and 0xFF) shl 24) or ((trex.body[5].toInt() and 0xFF) shl 16) or
            ((trex.body[6].toInt() and 0xFF) shl 8) or (trex.body[7].toInt() and 0xFF)
        val descIndex = ((trex.body[8].toInt() and 0xFF) shl 24) or ((trex.body[9].toInt() and 0xFF) shl 16) or
            ((trex.body[10].toInt() and 0xFF) shl 8) or (trex.body[11].toInt() and 0xFF)
        assertEquals(1, trackId)
        assertEquals("sample description index must point at the avc1 entry", 1, descIndex)
    }

    @Test
    fun `trun carries no composition-time-offset flag and its size matches the declared flags`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = 0)
        w.close()
        val all = sink.toByteArray()
        val traf = boxesOf(
            boxesOf(all).first { it.type == "moof" }.body,
        ).first { it.type == "traf" }
        val trun = traf.childBoxes().first { it.type == "trun" }
        // 盒长必须与旗标严格互证：v0 + data_offset|duration|size|flags = 8+4+4+4+12 = 32；
        // 旗里没有 CTO，正文里也不许有 CTO 的 4 字节（有旗无字段/有字段无旗都是结构错）。
        assertEquals(32, trun.body.size + 8)
        // version/flags 的 24 位旗从第 1 字节起（第 0 字节是 version，恒 0）。
        val trunFlags = ((trun.body[1].toInt() and 0xFF) shl 16) or ((trun.body[2].toInt() and 0xFF) shl 8) or
            (trun.body[3].toInt() and 0xFF)
        assertEquals(0x000701, trunFlags)
    }

    @Test
    fun `media time is normalized to zero at the first frame regardless of the pts origin`() {
        // 24 小时处的开机时钟：µs → 90kHz 后 7_776_000_000 tick，越过 32 位 —— v1 才装得下。
        val dayUs = 24L * 60 * 60 * 1_000_000
        assertTrue(dayUs * 90_000L / 1_000_000L > Int.MAX_VALUE.toLong())
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = dayUs)
        w.addSample(annexB(byteArrayOf(0x41, 2)), keyFrame = false, ptsUs = dayUs + 66_667)
        w.close()
        val frags = fragments(sink.toByteArray())
        assertEquals(7_776_000_000L, dayUs * 90_000L / 1_000_000L)
        assertEquals(0L, frags[0].first)
        // 第二帧：66_667µs → 6_000 tick（整除），零点之上只走直播时长。
        assertEquals(6_000L, frags[1].first)
        // 版本与 64 位字段
        assertEquals(1, frags[0].third)
        assertEquals(1, frags[1].third)
    }

    @Test
    fun `late pts after normalization still never moves the timeline backwards`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = 10_000_000L)
        w.addSample(annexB(byteArrayOf(0x41, 2)), keyFrame = false, ptsUs = 9_000_000L) // 早于上一帧
        w.close()
        val frags = fragments(sink.toByteArray())
        assertEquals(0L, frags[0].first)
        // 迟到的第二帧不能回到 0：它被压到第一帧之后（tfdt 1），时长至少 1 tick。
        assertEquals(1L, frags[1].first)
        assertTrue(frags[1].second >= 1L)
    }

    // ── 时长记账 ──

    @Test
    fun `first sample duration comes from the next pts delta and later ones from pts deltas`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = 1_000_000)
        w.addSample(annexB(byteArrayOf(0x41, 2)), keyFrame = false, ptsUs = 1_066_667)
        w.close()
        val times = baseMediaDecodeTimes(sink.toByteArray())
        // 首帧缓存到第二帧才写：时长 = 到第二帧的差值 6000 tick（90kHz/15fps）。
        assertEquals(6_000L, times[0].second)
        // 第二帧按 pts 差记 5_999~6_000 tick（整除取整）
        assertTrue(times[1].second in 5_999L..6_000L)
        // 首帧即零点：pts 的绝对值（1_000_000us）不进时间轴
        assertEquals(0L, times[0].first)
    }

    @Test
    fun `written fragment bytes are readable back as a well-formed stream`() {
        val sink = ByteArrayOutputStream()
        val w = writer(sink)
        w.addSample(annexB(byteArrayOf(0x65, 1)), keyFrame = true, ptsUs = 0)
        w.addSample(annexB(byteArrayOf(0x41, 2)), keyFrame = false, ptsUs = 66_667)
        w.close()
        // 整段输出必须能按盒子无剩余地走完（boxesOf 的 size 守卫会断言越界）
        val all = sink.toByteArray()
        assertFalse(all.isEmpty())
        boxesOf(ByteArrayInputStream(all).readBytes())
    }
}
