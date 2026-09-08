package com.dshbox.app.util.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OfficeTextExtractor 单测（纯 JVM）：临时目录构造最小 docx/xlsx zip 样本，
 * 断言抽取文本、空态与损坏输入不崩（1.2.0 §6.10）。
 */
class OfficeTextExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- 样本构造 ----------------

    private fun zipOf(entries: Map<String, String>, file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 1700000000000L })
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun docxXml(body: String): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
        <w:body>$body</w:body></w:document>
    """.trimIndent()

    private fun sheetXml(rows: String): String = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
        <sheetData>$rows</sheetData></worksheet>
    """.trimIndent()

    // ---------------- docx ----------------

    @Test
    fun docxExtractsParagraphsTabsAndBreaks() {
        val f = tmp.newFile("sample.docx")
        zipOf(
            mapOf(
                "[Content_Types].xml" to "<Types/>",
                "word/document.xml" to docxXml(
                    "<w:p><w:r><w:t>第一段</w:t><w:t>合并</w:t></w:r></w:p>" +
                        "<w:p><w:r><w:t>缩进</w:t></w:r><w:r><w:tab/><w:t>后文本</w:t></w:r></w:p>" +
                        "<w:p><w:r><w:t>换行</w:t><w:br/><w:t>下半</w:t></w:r></w:p>",
                ),
            ),
            f,
        )
        val extracted = OfficeTextExtractor.extractDocx(f)
        assertNotNull(extracted)
        assertEquals(
            "第一段合并\n缩进\t后文本\n换行\n下半",
            extracted!!.text,
        )
        assertEquals(false, extracted.truncated)
        assertEquals(1, extracted.sheetCount)
    }

    @Test
    fun docxEmptyBodyYieldsEmptyText() {
        val f = tmp.newFile("empty.docx")
        zipOf(mapOf("word/document.xml" to docxXml("")), f)
        val extracted = OfficeTextExtractor.extractDocx(f)
        assertNotNull(extracted)
        assertEquals("", extracted!!.text)
        assertEquals(false, extracted.truncated)
    }

    @Test
    fun docxMissingDocumentXmlReturnsNull() {
        val f = tmp.newFile("no-doc.docx")
        zipOf(mapOf("[Content_Types].xml" to "<Types/>"), f)
        assertNull(OfficeTextExtractor.extractDocx(f))
    }

    @Test
    fun corruptedZipReturnsNullNotCrash() {
        val f = tmp.newFile("corrupt.docx")
        f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3, 4, 5))
        assertNull(OfficeTextExtractor.extractDocx(f))
        assertNull(OfficeTextExtractor.extractXlsx(f))
    }

    @Test
    fun docxXmlEntitiesAreDecoded() {
        val f = tmp.newFile("entity.docx")
        zipOf(
            mapOf("word/document.xml" to docxXml("<w:p><w:r><w:t>a&lt;b&gt;c&amp;d</w:t></w:r></w:p>")),
            f,
        )
        assertEquals("a<b>c&d", OfficeTextExtractor.extractDocx(f)!!.text)
    }

    // ---------------- xlsx ----------------

    @Test
    fun xlsxExtractsSharedInlineAndNumbers() {
        val f = tmp.newFile("sample.xlsx")
        zipOf(
            mapOf(
                "xl/sharedStrings.xml" to """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <si><t>名称</t></si>
                    <si><t>Alpha</t></si>
                    <si><r><t>富文本</t></r><r><t>B</t></r></si>
                    </sst>
                """.trimIndent(),
                "xl/worksheets/sheet1.xml" to sheetXml(
                    "<row><c t=\"s\"><v>0</v></c><c t=\"s\"><v>1</v></c></row>" +
                        "<row><c t=\"inlineStr\"><is><t>内联</t></is></c><c><v>3.14</v></c><c t=\"s\"><v>2</v></c></row>",
                ),
            ),
            f,
        )
        val extracted = OfficeTextExtractor.extractXlsx(f)
        assertNotNull(extracted)
        assertEquals("名称\tAlpha\n内联\t3.14\t富文本B", extracted!!.text)
        assertEquals(1, extracted.sheetCount)
        assertEquals(false, extracted.truncated)
    }

    @Test
    fun xlsxSheetsSortNumericallyNotLexicographically() {
        val f = tmp.newFile("order.xlsx")
        zipOf(
            mapOf(
                "xl/worksheets/sheet1.xml" to sheetXml("<row><c t=\"inlineStr\"><is><t>S1</t></is></c></row>"),
                "xl/worksheets/sheet2.xml" to sheetXml("<row><c t=\"inlineStr\"><is><t>S2</t></is></c></row>"),
                "xl/worksheets/sheet10.xml" to sheetXml("<row><c t=\"inlineStr\"><is><t>S10</t></is></c></row>"),
            ),
            f,
        )
        assertEquals("S1\nS2\nS10", OfficeTextExtractor.extractXlsx(f)!!.text)
    }

    @Test
    fun xlsxSharedIndexOutOfRangeYieldsEmptyCell() {
        val f = tmp.newFile("oob.xlsx")
        zipOf(
            mapOf(
                "xl/sharedStrings.xml" to "<sst><si><t>only</t></si></sst>",
                "xl/worksheets/sheet1.xml" to sheetXml(
                    "<row><c t=\"s\"><v>99</v></c><c t=\"s\"><v>not-a-number</v></c><c t=\"s\"><v>0</v></c></row>",
                ),
            ),
            f,
        )
        assertEquals("only", OfficeTextExtractor.extractXlsx(f)!!.text)
    }

    @Test
    fun xlsxNoSheetsReturnsNull() {
        val f = tmp.newFile("nosheet.xlsx")
        zipOf(mapOf("xl/sharedStrings.xml" to "<sst/>"), f)
        assertNull(OfficeTextExtractor.extractXlsx(f))
    }

    @Test
    fun xlsxEmptyRowsYieldEmptyText() {
        val f = tmp.newFile("blank.xlsx")
        zipOf(mapOf("xl/worksheets/sheet1.xml" to sheetXml("")), f)
        val extracted = OfficeTextExtractor.extractXlsx(f)
        assertNotNull(extracted)
        assertEquals("", extracted!!.text)
        assertEquals(1, extracted.sheetCount)
    }

    @Test
    fun sheetSortKeyIsNumeric() {
        assertEquals(1, OfficeTextExtractor.sheetSortKey("xl/worksheets/sheet1.xml"))
        assertEquals(10, OfficeTextExtractor.sheetSortKey("xl/worksheets/sheet10.xml"))
        assertEquals(Int.MAX_VALUE, OfficeTextExtractor.sheetSortKey("xl/worksheets/weird.xml"))
    }

    @Test
    fun truncatedExtractionIsFlagged() {
        // 构造超过上限的文档：MAX_TEXT_CHARS 上限以字符串重复填充
        val f = tmp.newFile("big.docx")
        val fill = "甲乙丙丁".repeat(64) // 256 chars
        val repeat = OfficeTextExtractor.MAX_TEXT_CHARS / 256 + 16
        val big = StringBuilder("<w:p><w:r><w:t>")
            .append(fill.repeat(repeat))
            .append("</w:t></w:r></w:p>")
            .toString()
        zipOf(mapOf("word/document.xml" to docxXml(big)), f)
        val extracted = OfficeTextExtractor.extractDocx(f)
        assertNotNull(extracted)
        assertTrue(extracted!!.text.length >= OfficeTextExtractor.MAX_TEXT_CHARS)
        assertTrue(extracted.truncated)
    }
}
