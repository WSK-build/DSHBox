package com.dshbox.app.util.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本编码探测单测（1.2.0 §10.1）：UTF-8/UTF-16 BOM/GBK 探测、UTF-8 有效性否决
 * （截断多字节序列）、CRLF/LF/CR 识别与保持、手动指定覆盖。
 */
class TextEncodingTest {

    // ---------------- 探测 ----------------

    @Test
    fun bomDetection() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()
        val d1 = TextEncoding.detect(utf8)
        assertEquals(TextEncoding.TextCharset.UTF_8, d1.charset)
        assertEquals(3, d1.bomLength)

        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x68, 0x00, 0x69, 0x00)
        val d2 = TextEncoding.detect(utf16le)
        assertEquals(TextEncoding.TextCharset.UTF_16LE, d2.charset)
        assertEquals(2, d2.bomLength)

        val utf16be = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x68, 0x00, 0x69)
        val d3 = TextEncoding.detect(utf16be)
        assertEquals(TextEncoding.TextCharset.UTF_16BE, d3.charset)
        assertEquals(2, d3.bomLength)
    }

    @Test
    fun asciiFastPathAndEmpty() {
        val ascii = "hello world 123".toByteArray(Charsets.US_ASCII)
        assertEquals(TextEncoding.TextCharset.UTF_8, TextEncoding.detect(ascii).charset)
        assertEquals(0, TextEncoding.detect(ascii).bomLength)
        assertEquals(TextEncoding.TextCharset.UTF_8, TextEncoding.detect(ByteArray(0)).charset)
    }

    @Test
    fun validUtf8MultibyteDetected() {
        // 「中文内容测试」UTF-8 编码
        val utf8 = "中文内容测试 abc".toByteArray(Charsets.UTF_8)
        assertEquals(TextEncoding.TextCharset.UTF_8, TextEncoding.detect(utf8).charset)
    }

    @Test
    fun truncatedUtf8Rejected() {
        // 截断的多字节序列：「中文abc」截到 5 字节——尾部不完整序列被裁剪后，
        // 剩余「中」是合法 UTF-8 前缀 → 判 UTF-8（返工修正 #7 后的正确语义）；
        // 若无裁剪，严格校验会误否决并可能误判 GBK
        val truncated = "中文abc".toByteArray(Charsets.UTF_8).copyOfRange(0, 5) // 「中」完整 + 「文」前 2 字节
        val det = TextEncoding.detect(truncated)
        assertEquals(TextEncoding.TextCharset.UTF_8, det.charset)
        // 对照：完整「中文abc」是合法 UTF-8，直接命中严格校验
        assertEquals(TextEncoding.TextCharset.UTF_8, TextEncoding.detect("中文abc".toByteArray(Charsets.UTF_8)).charset)
    }

    @Test
    fun windowCutMultibyteStillUtf8() {
        // 返工修正 #7 回归锁定：8KB 采样窗口在多字节序列中间切断——
        // 8000 个 ASCII + 「中」UTF-8 的首字节（E4 B8 落入窗口内但序列不完整）。
        // 修复前：严格校验否决 → GBK 启发式把 E4 B8 当合法对 → 误判 GBK；
        // 修复后：尾部不完整序列裁掉 → 全 ASCII → UTF-8。
        val ascii = ByteArray(8000) { 0x61 }
        val sample = ascii + byteArrayOf(0xE4.toByte(), 0xB8.toByte())
        assertEquals(TextEncoding.TextCharset.UTF_8, TextEncoding.detect(sample).charset)
        // 变体：多字节序列 lead 在窗口外（尾部全是 continuation 字节）
        val sample2 = ascii + byteArrayOf(0x80.toByte(), 0x80.toByte())
        assertEquals(TextEncoding.TextCharset.UTF_8, TextEncoding.detect(sample2).charset)
    }

    @Test
    fun gbkDetectedWhenUtf8Invalid() {
        // 「中文」GBK 编码 = D6 D0 CE C4（非合法 UTF-8 序列）
        val gbk = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        val det = TextEncoding.detect(gbk)
        assertTrue("actual=${det.charset}", det.charset == TextEncoding.TextCharset.GBK || det.charset == TextEncoding.TextCharset.GB18030)
        // GBK 解码还原「中文」
        assertEquals("中文", String(gbk, charsetOf(det)))
    }

    @Test
    fun fullyInvalidFallsBackToUtf8() {
        // 0x80 单独出现：既非合法 UTF-8 也非合法 GBK → 兜底 UTF-8
        val garbage = byteArrayOf(0x41, 0x80.toByte(), 0x42)
        assertEquals(TextEncoding.TextCharset.UTF_8, TextEncoding.detect(garbage).charset)
    }

    private fun charsetOf(d: TextEncoding.Detection) = d.charset.charset()

    // ---------------- 解码 / 编码往返 ----------------

    @Test
    fun decodeStripsBom() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        assertEquals("abc", TextEncoding.decode(utf8Bom, TextEncoding.detect(utf8Bom)))
    }

    @Test
    fun decodeCheckedDetectsLossyDecoding() {
        // 返工 P1：有损解码检测——REPORT 试解失败 = 存在无法解码的字节
        // 合法 UTF-8 → 无损
        val ok = TextEncoding.decodeChecked("正常内容".toByteArray(Charsets.UTF_8), TextEncoding.Detection(TextEncoding.TextCharset.UTF_8, 0))
        assertEquals(false, ok.lossy)
        assertEquals("正常内容", ok.text)
        // Shift_JIS 字节（「テスト」= 83 65 83 58 83 58）按 UTF-8 严格解码失败 → 有损
        val sjis = byteArrayOf(0x83.toByte(), 0x65.toByte(), 0x83.toByte(), 0x58.toByte())
        val lossy = TextEncoding.decodeChecked(sjis, TextEncoding.Detection(TextEncoding.TextCharset.UTF_8, 0))
        assertEquals(true, lossy.lossy)
        // 合法字符集路径：GBK 字节按 GBK 解码 → 无损
        assertEquals(false, TextEncoding.decodeChecked("中文".toByteArray(charset("GBK")), TextEncoding.TextCharset.GBK, 0).lossy)
        // 有损解码文本包含替换符（U+FFFD）
        assertTrue(lossy.text.contains('\uFFFD'))
        // 空/全 BOM 输入：无损
        assertEquals(false, TextEncoding.decodeChecked(ByteArray(0), TextEncoding.Detection(TextEncoding.TextCharset.UTF_8, 0)).lossy)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        for (cs in listOf(
            TextEncoding.TextCharset.UTF_8,
            TextEncoding.TextCharset.GBK,
            TextEncoding.TextCharset.UTF_16LE,
        )) {
            val text = "roundtrip 测试 123"
            val bytes = TextEncoding.encode(text, cs, withBom = false)
            assertEquals(text, TextEncoding.decode(bytes, cs, bomLength = 0))
        }
        // 带 BOM 往返（UTF-8）
        val withBom = TextEncoding.encode("bom", TextEncoding.TextCharset.UTF_8, withBom = true)
        assertEquals(3 + 3, withBom.size)
        assertEquals("bom", TextEncoding.decode(withBom, TextEncoding.detect(withBom)))
    }

    @Test
    fun manualOverrideDecodesWithChosenCharset() {
        val gbk = "中文".toByteArray(charset("GBK"))
        // 自动探测已是 GBK；强制按 UTF-8 解码 → 乱码但可控（手动切换语义）
        val asUtf8 = TextEncoding.decode(gbk, TextEncoding.TextCharset.UTF_8, bomLength = 0)
        assertFalse(asUtf8 == "中文")
        assertEquals("中文", TextEncoding.decode(gbk, TextEncoding.TextCharset.GBK, bomLength = 0))
    }

    // ---------------- 换行 ----------------

    @Test
    fun newlineDetection() {
        val crlf = TextEncoding.detectNewlines("a\r\nb\r\nc")
        assertEquals(TextEncoding.NewlineStyle.CRLF, crlf.style)
        assertFalse(crlf.mixed)

        val lf = TextEncoding.detectNewlines("a\nb\nc")
        assertEquals(TextEncoding.NewlineStyle.LF, lf.style)

        val cr = TextEncoding.detectNewlines("a\rb\rc")
        assertEquals(TextEncoding.NewlineStyle.CR, cr.style)

        val mixed = TextEncoding.detectNewlines("a\r\nb\nc")
        assertTrue(mixed.mixed)

        // 返工修正 #6：无任何换行（含空文件）固定 LF（原实现三者全 0 时落 CRLF）
        assertEquals(TextEncoding.NewlineStyle.LF, TextEncoding.detectNewlines("").style)
        assertEquals(TextEncoding.NewlineStyle.LF, TextEncoding.detectNewlines("no newline here").style)
    }

    @Test
    fun newlinePreserveAndConvert() {
        val original = "l1\r\nl2\r\n"
        val info = TextEncoding.detectNewlines(original)
        // 编辑表示归一为 LF
        val normalized = TextEncoding.normalizeToLf(original)
        assertEquals("l1\nl2\n", normalized)
        // 保存时按原风格还原（§6.4 默认原样保留）
        assertEquals(original, TextEncoding.applyNewlineStyle(normalized, info.style))
        // 显式转换（契约：输入为 LF 归一文本）
        assertEquals("l1\r\nl2\r\n", TextEncoding.applyNewlineStyle(normalized, TextEncoding.NewlineStyle.CRLF))
        assertEquals("l1\rl2\r", TextEncoding.applyNewlineStyle(normalized, TextEncoding.NewlineStyle.CR))
        assertEquals(normalized, TextEncoding.applyNewlineStyle(normalized, TextEncoding.NewlineStyle.LF))
    }
}
