package com.dshbox.app.util.viewer

import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码探测（1.2.0 §6.4，纯 JVM 自研，无 Android 依赖）。
 *
 * 不用 `android.icu.text.CharsetDetector`——它是 Android 平台类，纯 JVM 单测中不存在
 * （项目无 Robolectric，CI 跑 testDebugUnitTest）。自研 `java.nio.charset` 方案：
 *
 * 1. **BOM 直判**：UTF-8（EF BB BF）、UTF-16 LE（FF FE）/ BE（FE FF）；
 * 2. **ASCII 快速路径**（全字节 < 0x80）；
 * 3. **UTF-8 有效性严格校验**（[CharsetDecoder] REPORT 模式，非法序列即否决）；
 * 4. **GBK/GB18030 双字节范围启发式**（连续非法序列比例超阈值即否决）；
 * 5. 均不命中 → 默认 UTF-8（UI 提供手动切换重新解码）。
 *
 * 换行：识别原 CRLF/LF/CR 风格与 BOM，**保存时默认原样保留**（避免全文件 diff 被污染）；
 * 另提供换行转换入口（[NewlineStyle] 覆盖）。
 */
object TextEncoding {

    /** 支持的文本字符集（探测结果与手动切换共用）。 */
    enum class TextCharset(val label: String, val charsetName: String) {
        UTF_8("UTF-8", "UTF-8"),
        GBK("GBK", "GBK"),
        GB18030("GB18030", "GB18030"),
        // 返工 #5（2026-09-07 真机）：日文 Shift_JIS/EUC-JP 文件此前无手动切换项，
        // 探测兜底后只能看乱码；补选项（自动探测留 1.2.1 评估）
        SHIFT_JIS("Shift_JIS", "Shift_JIS"),
        EUC_JP("EUC-JP", "EUC-JP"),
        UTF_16LE("UTF-16LE", "UTF-16LE"),
        UTF_16BE("UTF-16BE", "UTF-16BE"),
        ;

        fun charset(): Charset = Charset.forName(charsetName)
    }

    /** 编码探测结果：[charset] 为解码用字符集；[bomLength] 为文件头 BOM 字节数（0 = 无）。 */
    data class Detection(val charset: TextCharset, val bomLength: Int) {
        val hasBom: Boolean get() = bomLength > 0
    }

    // ---------------- 编码探测 ----------------

    /** 对文件头采样（建议 ≥8KB）做编码探测。空文件/空样本按 UTF-8 处理。 */
    fun detect(head: ByteArray): Detection {
        // 1. BOM 直判
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) {
            return Detection(TextCharset.UTF_8, 3)
        }
        if (head.size >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) {
            return Detection(TextCharset.UTF_16LE, 2)
        }
        if (head.size >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) {
            return Detection(TextCharset.UTF_16BE, 2)
        }
        if (head.isEmpty()) return Detection(TextCharset.UTF_8, 0)

        // 2. ASCII 快速路径（含 UTF-8 BOM 已在上方处理）
        if (head.all { (it.toInt() and 0x80) == 0 }) return Detection(TextCharset.UTF_8, 0)

        // 3. UTF-8 严格校验（REPORT：非法序列抛异常即否决）。
        // 返工修正 #7：采样窗口尾部可能切断多字节序列（8KB 边界）——先裁掉尾部
        // 不完整序列再校验，否则合法 UTF-8 会被误否决、进而误判 GBK。
        if (isValidUtf8(trimTruncatedUtf8Tail(head))) return Detection(TextCharset.UTF_8, 0)

        // 4. GBK/GB18030 双字节范围启发式
        gbkPlausible(head)?.let { return Detection(it, 0) }

        // 5. 兜底 UTF-8（UI 显示当前编码，可手动切换重新解码）
        return Detection(TextCharset.UTF_8, 0)
    }

    /**
     * 裁掉采样窗口尾部被切断的 UTF-8 序列：从末尾回溯连续 continuation 字节，
     * 若其 lead 字节的序列不完整（含 lead 在窗口外的情况）则裁到该边界。
     * 全部为高位字节（lead 在窗口外更深处）→ 返回空（交由后续启发式在原始字节上判定）。
     */
    private fun trimTruncatedUtf8Tail(bytes: ByteArray): ByteArray {
        var i = bytes.size - 1
        while (i >= 0 && (bytes[i].toInt() and 0xFF) in 0x80..0xBF) i--
        if (i < 0) return ByteArray(0)
        val b = bytes[i].toInt() and 0xFF
        if (b >= 0xC2) {
            val need = when {
                b < 0xE0 -> 1
                b < 0xF0 -> 2
                else -> 3
            }
            if (bytes.size - i <= need) return bytes.copyOf(i) // lead + continuation 不足 → 不完整
        }
        return bytes // ASCII 结尾或尾部序列完整
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean = runCatching {
        CodingErrorAction.REPORT.let { report ->
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(report)
                .onUnmappableCharacter(report)
                .decode(java.nio.ByteBuffer.wrap(bytes))
        }
    }.isSuccess

    /**
     * GBK/GB18030 启发式：逐字节校验双字节（GBK）与四字节（GB18030）序列范围，
     * 非法序列比例超阈值（1%）或完全没有多字节序列 → null（否决）。
     * 有四字节合法序列时判定为 GB18030（超集，正确解码 GBK 双字节）。
     */
    private fun gbkPlausible(bytes: ByteArray): TextCharset? {
        var validPairs = 0
        var validQuads = 0
        var invalid = 0
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b0 = bytes[i].toInt() and 0xFF
            if (b0 < 0x80) {
                i++
                continue
            }
            if (b0 == 0x80 || b0 == 0xFF) {
                invalid++
                i++
                continue
            }
            if (i + 1 >= n) {
                // 尾部截断的单 lead 字节：轻微问题，计一次非法
                invalid++
                break
            }
            val b1 = bytes[i + 1].toInt() and 0xFF
            when {
                b1 in 0x40..0x7E || b1 in 0x80..0xFE -> {
                    validPairs++
                    i += 2
                }
                b1 in 0x30..0x39 -> {
                    // GB18030 四字节序列：b2 lead、b3 0x30-0x39
                    if (i + 3 < n &&
                        (bytes[i + 2].toInt() and 0xFF) in 0x81..0xFE &&
                        (bytes[i + 3].toInt() and 0xFF) in 0x30..0x39
                    ) {
                        validQuads++
                        i += 4
                    } else {
                        invalid++
                        i += 2
                    }
                }
                else -> {
                    invalid++
                    i++
                }
            }
        }
        val total = validPairs + validQuads + invalid
        if (total == 0 || validPairs + validQuads == 0) return null
        if (invalid * 100 > total) return null // 非法比例 > 1% 否决
        return if (validQuads > 0) TextCharset.GB18030 else TextCharset.GBK
    }

    // ---------------- 解码 / 编码 ----------------

    /** 解码结果：[text] 为解码文本；[lossy] = true 表示存在无法解码的字节序列
     *  （已以 U+FFFD 呈现，原字节不可还原）。**有损结果禁止直接编辑保存**——
     *  编辑后的文本再编码回文件会用替换符永久覆盖原字节（返工 P1 护栏）。 */
    data class DecodedText(val text: String, val lossy: Boolean)

    /**
     * 带有损标记的解码（返工 P1）：先以 REPORT 严格试解，抛异常（存在非法/不可映射
     * 序列）则降级 REPLACE 解码并标记 lossy。典型触发：探测兜底判 UTF-8 的未知编码
     * 文件（Shift_JIS、ISO-8859-1、EUC-KR 等）。UI 收到 lossy 须禁编辑并常驻警告，
     * 仅放行编码切换与另存。
     */
    fun decodeChecked(bytes: ByteArray, detection: Detection): DecodedText =
        decodeChecked(bytes, detection.charset, detection.bomLength)

    /** [decodeChecked] 的手动指定字符集版本（编码切换重新解码用）。 */
    fun decodeChecked(bytes: ByteArray, charset: TextCharset, bomLength: Int): DecodedText {
        val start = bomLength.coerceIn(0, bytes.size)
        if (start >= bytes.size) return DecodedText("", lossy = false)
        val body = bytes.copyOfRange(start, bytes.size)
        val strict = runCatching {
            charset.charset().newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(body))
                .toString()
        }
        return strict.fold(
            onSuccess = { DecodedText(it, lossy = false) },
            onFailure = { DecodedText(String(body, charset.charset()), lossy = true) },
        )
    }

    /** 按探测结果解码（自动剥离 BOM）。非法序列以替换符呈现（仅手动选错编码时出现）。 */
    fun decode(bytes: ByteArray, detection: Detection): String = decodeChecked(bytes, detection).text

    /** 手动指定字符集解码（UI「切换编码」入口；[bomLength] 自动识别 BOM 是否存在）。 */
    fun decode(bytes: ByteArray, charset: TextCharset, bomLength: Int = bomLengthOf(bytes)): String =
        decodeChecked(bytes, charset, bomLength).text

    /** 编码为字节（[withBom] 时写入对应 BOM：UTF-8/UTF-16 家族；GBK/GB18030 无 BOM）。 */
    fun encode(text: String, charset: TextCharset, withBom: Boolean): ByteArray {
        val body = text.toByteArray(charset.charset())
        if (!withBom) return body
        val bom = when (charset) {
            TextCharset.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            TextCharset.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            TextCharset.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> return body
        }
        return bom + body
    }

    private fun bomLengthOf(bytes: ByteArray): Int = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> 2
        else -> 0
    }

    // ---------------- 换行识别与转换 ----------------

    enum class NewlineStyle(val label: String) {
        LF("LF"),
        CRLF("CRLF"),
        CR("CR"),
    }

    /** 换行统计：[style] 为占多数的风格；无任何换行（含空文件）为 LF（保存不受影响）。 */
    data class NewlineInfo(val style: NewlineStyle, val crlf: Int, val lf: Int, val cr: Int) {
        val total: Int get() = crlf + lf + cr
        val mixed: Boolean get() = listOf(crlf, lf, cr).count { it > 0 } > 1
    }

    /** 统计文本中的换行风格（编辑前调用，保存时默认按 [NewlineInfo.style] 还原）。 */
    fun detectNewlines(text: String): NewlineInfo {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') {
                    crlf++; i += 2
                } else {
                    cr++; i++
                }
                '\n' -> {
                    lf++; i++
                }
                else -> i++
            }
        }
        // 返工修正 #6：无任何换行（三者全 0，含空文件）固定 LF——
        // 原「crlf >= lf && crlf >= cr」在三者全 0 时会落到 CRLF，与注释矛盾
        val style = when {
            crlf == 0 && lf == 0 && cr == 0 -> NewlineStyle.LF
            crlf >= lf && crlf >= cr -> NewlineStyle.CRLF
            lf >= cr -> NewlineStyle.LF
            else -> NewlineStyle.CR
        }
        return NewlineInfo(style, crlf, lf, cr)
    }

    /** 全部换行归一为 LF（编辑器内部统一表示）。 */
    fun normalizeToLf(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** 将 LF 文本按 [style] 转换（保存链路：默认还原原文件风格；转换入口传显式 style）。 */
    fun applyNewlineStyle(text: String, style: NewlineStyle): String = when (style) {
        NewlineStyle.LF -> text
        NewlineStyle.CRLF -> text.replace("\r\n", "\n").replace("\n", "\r\n")
        NewlineStyle.CR -> text.replace("\r\n", "\n").replace('\n', '\r')
    }
}
