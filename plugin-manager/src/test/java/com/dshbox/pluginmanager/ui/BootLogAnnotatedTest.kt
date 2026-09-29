package com.dshbox.pluginmanager.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import com.dshbox.app.sandbox.BootSegmentedLog
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [bootLogAnnotated] 的两条不变量。
 *
 * 它决定"哪一段是哪次启动"能不能一眼看出来，所以锁的是两件容易错的事：
 *  **只有标记行**被着成强调色（多着或漏着都会让人误判段边界），以及**正文一字不改**
 * （日志用于排障，任何增删都是把证据改掉）。
 */
class BootLogAnnotatedTest {

    private val markerColor = Color(0xFFFF0000)
    private val bodyColor = Color(0xFF00FF00)

    private fun colorAt(text: AnnotatedString, index: Int): Color? =
        text.spanStyles.lastOrNull { index >= it.start && index < it.end }?.item?.color

    @Test
    fun textIsPreservedVerbatim() {
        val source = buildString {
            appendLine(BootSegmentedLog.markerText(0L))
            appendLine("dsh web: http://127.0.0.1:3080")
            append("WARNING: linker: ...")
        }
        assertEquals(source, bootLogAnnotated(source, markerColor, bodyColor).text)
    }

    @Test
    fun onlyMarkerLinesUseTheMarkerColor() {
        val marker = BootSegmentedLog.markerText(0L)
        val source = "$marker\n普通日志一行\n$marker\n第二段\n"

        val annotated = bootLogAnnotated(source, markerColor, bodyColor)

        val firstMarkerAt = source.indexOf(marker)
        val plainAt = source.indexOf("普通日志一行")
        val secondMarkerAt = source.indexOf(marker, firstMarkerAt + 1)
        val secondPlainAt = source.indexOf("第二段")

        assertEquals("标记行应为强调色", markerColor, colorAt(annotated, firstMarkerAt))
        assertEquals("普通行应为正文色", bodyColor, colorAt(annotated, plainAt))
        assertEquals("第二段开头也应为强调色", markerColor, colorAt(annotated, secondMarkerAt))
        assertEquals("第二段正文应为正文色", bodyColor, colorAt(annotated, secondPlainAt))
    }

    /** 与标记行形似的普通行（少了首尾的等号）不得被当成分隔。 */
    @Test
    fun nearMissLinesAreNotTreatedAsMarkers() {
        val tooFewEquals = "== DSH boot 2026-09-26 18:42:07 =="
        val badStamp = "===== DSH boot not-a-time ====="
        val source = "$tooFewEquals\n$badStamp\n"
        val annotated = bootLogAnnotated(source, markerColor, bodyColor)

        assertEquals(bodyColor, colorAt(annotated, 3))
        assertEquals(bodyColor, colorAt(annotated, source.indexOf(badStamp)))
    }

    @Test
    fun emptyTextYieldsEmptyAnnotated() {
        assertEquals("", bootLogAnnotated("", markerColor, bodyColor).text)
    }
}
