package com.dshbox.app.util.viewer

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Office OOXML 纯文本抽取兜底（1.2.0 §6.10）。
 *
 * - docx：`word/document.xml` 文本节点（`w:p` 段落 / `w:t` 文本 / `w:tab` 制表 / `w:br` 换行）；
 * - xlsx：`xl/sharedStrings.xml` 共享字符串 + 工作表（`xl/worksheets/sheetN.xml`，
 *   支持共享引用 `t="s"` 与内联字符串 `t="inlineStr"`，数字取 `v` 原文）；
 * - JDK ZipFile + XmlPullParser，零第三方文档库（kxml2 仅提供 JVM 单测可用的
 *   XmlPullParser 实现，Android 平台自带）；doc/xls/ppt 旧格式不支持（信息卡兜底）。
 *
 * 只读：抽取结果仅供展示，绝不写回（TextCodeViewer 以只读态承接，编辑入口被分类器门禁关闭）。
 */
object OfficeTextExtractor {

    /** 抽取文本字符上限（防超大文档 OOM；超出截断并以 [Extracted.truncated] 标记）。 */
    const val MAX_TEXT_CHARS = 4_000_000

    data class Extracted(
        val text: String,
        val truncated: Boolean,
        /** xlsx：解析的工作表数；docx 恒 1。 */
        val sheetCount: Int,
    )

    /** docx → 纯文本。文件损坏/无 document.xml 返回 null（UI 落信息卡）。 */
    fun extractDocx(file: File): Extracted? = try {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml") ?: return null
            zip.getInputStream(entry).use { ins ->
                val (text, truncated) = parseDocx(ins)
                Extracted(text, truncated, 1)
            }
        }
    } catch (_: Exception) {
        null
    }

    /** xlsx → 按行/制表分隔的纯文本。损坏/无工作簿返回 null。 */
    fun extractXlsx(file: File): Extracted? = try {
        ZipFile(file).use { zip ->
            val shared = readSharedStrings(zip)
            val sheets = zip.entries().toList()
                .map { it.name }
                .filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") && !it.endsWith("/") }
                .sortedBy { sheetSortKey(it) }
            if (sheets.isEmpty()) return null
            val sb = StringBuilder()
            var truncated = false
            for (sheet in sheets) {
                val e = zip.getEntry(sheet) ?: continue
                zip.getInputStream(e).use { ins ->
                    if (!truncated) {
                        truncated = !parseSheet(ins, shared, sb)
                    }
                }
            }
            Extracted(sb.toString(), truncated, sheets.size)
        }
    } catch (_: Exception) {
        null
    }

    // ---------------- docx ----------------

    /** 返回 (文本, 是否已截断)。 */
    private fun parseDocx(input: InputStream): Pair<String, Boolean> {
        val sb = StringBuilder()
        val parser = newParser(input)
        var truncated = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser)) {
                    "p" -> if (sb.isNotEmpty()) sb.append('\n')
                    "t" -> sb.append(parser.nextText())
                    "tab" -> sb.append('\t')
                    "br", "cr" -> sb.append('\n')
                }
            }
            if (sb.length > MAX_TEXT_CHARS) {
                truncated = true
                break
            }
            event = parser.next()
        }
        return sb.toString() to truncated
    }

    // ---------------- xlsx ----------------

    private fun readSharedStrings(zip: ZipFile): List<String> {
        val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
        val out = ArrayList<String>()
        zip.getInputStream(entry).use { input ->
            val parser = newParser(input)
            var current: StringBuilder? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (local(parser)) {
                        // si 内可含多个 <t>（富文本 run）或 <r><t>：全部拼接为一个共享串
                        "si" -> current = StringBuilder()
                        "t" -> current?.append(parser.nextText())
                    }
                    XmlPullParser.END_TAG -> if (local(parser) == "si" && current != null) {
                        out.add(current.toString())
                        current = null
                    }
                }
                event = parser.next()
            }
        }
        return out
    }

    /** 追加工作表文本到 [sb]；返回是否完整未截断。 */
    private fun parseSheet(input: InputStream, shared: List<String>, sb: StringBuilder): Boolean {
        val parser = newParser(input)
        var truncated = false
        val row = StringBuilder()
        var inCell = false
        var cellText: StringBuilder? = null
        var cellSharedIndex: Int? = null
        var cellInline = false
        var firstRowOnSheet = sb.isEmpty()

        fun flushCell() {
            if (!inCell) return
            val value = when {
                cellInline -> cellText?.toString().orEmpty()
                cellSharedIndex != null -> shared.getOrNull(cellSharedIndex!!).orEmpty()
                else -> cellText?.toString().orEmpty()
            }
            if (value.isNotEmpty() && row.isNotEmpty()) row.append('\t')
            row.append(value)
            inCell = false
            cellText = null
            cellSharedIndex = null
            cellInline = false
        }

        fun flushRow() {
            flushCell()
            val line = row.toString().trimEnd('\t')
            row.setLength(0)
            if (line.isEmpty()) return
            if (!firstRowOnSheet) sb.append('\n')
            firstRowOnSheet = false
            sb.append(line)
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (local(parser)) {
                    "row" -> flushRow()
                    "c" -> {
                        flushCell()
                        inCell = true
                        when (parser.getAttributeValue(null, "t")) {
                            "s" -> cellSharedIndex = -1 // 索引在 <v> 文本给出
                            "inlineStr" -> cellInline = true
                        }
                    }
                    "is" -> cellInline = true
                    "v" -> {
                        val text = parser.nextText()
                        if (cellSharedIndex != null) {
                            cellSharedIndex = text.trim().toIntOrNull() ?: -2
                        } else {
                            if (cellText == null) cellText = StringBuilder()
                            cellText!!.append(text)
                        }
                    }
                    "t" -> {
                        if (cellInline || cellText != null) {
                            if (cellText == null) cellText = StringBuilder()
                            cellText!!.append(parser.nextText())
                        } else {
                            // 不在单元格内的 t（如工作表名）忽略
                            parser.nextText()
                        }
                    }
                }
            }
            if (sb.length > MAX_TEXT_CHARS) {
                truncated = true
                break
            }
            event = parser.next()
        }
        flushRow()
        return !truncated
    }

    /** sheet 名排序键：sheet1 < sheet2 < sheet10（按数字后缀，非字典序）。 */
    internal fun sheetSortKey(name: String): Int {
        val digits = name.substringAfterLast('/').removePrefix("sheet").removeSuffix(".xml")
        return digits.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        return parser
    }

    /**
     * 本地名：命名空间关闭时 kxml2 的 name 返回带前缀形式（如 `w:p`），
     * OOXML 文档对 `w:`/`x:` 等前缀均用冒号记法——统一取冒号后本地名，
     * 对「无前缀返回本地名」的平台实现同样正确。
     */
    private fun local(parser: XmlPullParser): String = parser.name.substringAfterLast(':')
}
