package com.dshbox.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 外部分享条目的落盘文件名。
 *
 * 关键口径：来源端给出的**真实**名字原样保留（不做改写），但提供器只给内部标识
 * （纯数字、无扩展名、超长）时必须换成时间戳名并按内容类型补后缀——否则用户拿到的
 * 文件名会是 `1234567` 这种内部编号。
 */
class InboundShareNamingTest {

    private val ts = 1700000000000L

    @Test
    fun plausibleDisplayNameIsKeptVerbatim() {
        assertEquals("report.pdf", inboundFileName("report.pdf", "pdf", ts))
        assertEquals("photo-1.jpg", inboundFileName("photo-1.jpg", "jpeg", ts))
        assertEquals("com.foo-1.2.3.apk", inboundFileName("com.foo-1.2.3.apk", "apk", ts))
        assertEquals("  trimmed.txt  ".trim(), inboundFileName("  trimmed.txt  ", "txt", ts))
    }

    @Test
    fun internalIdentifierIsReplacedByTimestampedName() {
        // 纯数字的内部编号（多数提供器在拿不到显示名时会落到 URI 末段）
        assertEquals("shared-$ts.jpg", inboundFileName("1234567", "jpg", ts))
        // 数字主干 + 扩展名：同样不是真实名字
        assertEquals("shared-$ts.apk", inboundFileName("1234567.apk", "apk", ts))
        // 没有扩展名的一长串编码
        assertEquals("shared-$ts.png", inboundFileName("a1b2c3d4e5f6", "png", ts))
    }

    @Test
    fun missingDisplayNameFallsBackToTimestamp() {
        assertEquals("shared-$ts.png", inboundFileName(null, "png", ts))
        assertEquals("shared-$ts", inboundFileName("", "  ", ts))
        assertEquals("shared-$ts", inboundFileName(null, null, ts))
    }

    @Test
    fun overlongNameFallsBackToTimestamp() {
        // 超过 128 字符的显示名多半是提供器塞进来的编码串，不当作文件名
        val longName = "a".repeat(200) + ".txt"
        assertEquals("shared-$ts.txt", inboundFileName(longName, "txt", ts))
    }

    @Test
    fun textSubjectBecomesTxtFileName() {
        assertEquals("meeting-notes.txt", inboundTextFileName("meeting-notes", ts))
        assertEquals("notes.txt", inboundTextFileName("  notes  ", ts))
        // 主题自带后缀时按原样补齐为文本文件，不猜用户意图去掉后缀
        assertEquals("report.pdf.txt", inboundTextFileName("report.pdf", ts))
    }

    @Test
    fun blankSubjectFallsBackToTimestamp() {
        assertEquals("shared-$ts.txt", inboundTextFileName(null, ts))
        assertEquals("shared-$ts.txt", inboundTextFileName("", ts))
        assertEquals("shared-$ts.txt", inboundTextFileName("   ", ts))
    }
}
