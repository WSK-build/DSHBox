package com.dshbox.app.sandbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [BootSegmentedLog] 的三条不变量。
 *
 * 这段代码决定了面板上"这次启动的日志"到底能不能被认出来，所以锁的不是"文件写没写"，
 * 而是三件容易写错、错了还看不出来的事：
 *
 *  - **标记行永不因裁剪而消失**：它是分段的唯一依据，丢了这一段就不再是独立一段；
 *  - **段数按次数收敛**（留最近 N 段），而不是按体积 —— 这是面板文案对用户的承诺；
 *  - **裁剪落在行边界上**：半行日志既读不懂，也会让"红色分隔"的判定错位。
 */
class BootSegmentedLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun logFile(): File = File(tmp.newFolder("logs"), "process-dsh.log")

    private fun lines(file: File): List<String> =
        file.readText(Charsets.UTF_8).split("\n").dropLastWhile { it.isEmpty() }

    private fun markers(file: File): Int = lines(file).count { BootSegmentedLog.isMarker(it) }

    @Test
    fun markerHasTheExpectedShape() {
        val marker = BootSegmentedLog.markerText(0L)
        assertTrue(BootSegmentedLog.isMarker(marker))
        assertTrue(
            "形如 `===== DSH 启动 yyyy-MM-dd HH:mm:ss =====`，实得：$marker",
            Regex("""^===== DSH boot \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} =====$""").matches(marker),
        )
        assertFalse("普通日志行不得被认成标记", BootSegmentedLog.isMarker("dsh web: http://127.0.0.1:3080"))
        assertFalse("首尾像但时间戳不成形，不得被认成标记", BootSegmentedLog.isMarker("===== DSH boot 早上 ====="))
        assertFalse(BootSegmentedLog.isMarker(""))
    }

    @Test
    fun beginBootStartsANewSegmentAndKeepsItsLines() {
        val file = logFile()
        val log = BootSegmentedLog(file)

        log.beginBoot()
        log.append("第一段的输出")

        assertEquals(1, markers(file))
        assertTrue(lines(file).contains("第一段的输出"))
    }

    /** 连续多次启动：只保留最近 N 段，最旧那段的**独有内容**必须消失。 */
    @Test
    fun keepsOnlyTheMostRecentSegments() {
        val file = logFile()
        val log = BootSegmentedLog(file, maxSegments = 3)

        repeat(5) { index ->
            log.beginBoot()
            log.append("第${index}次的输出")
        }

        assertEquals("只应保留 3 段", 3, markers(file))
        val text = file.readText(Charsets.UTF_8)
        assertFalse("最旧那段应被淘汰", text.contains("第0次的输出"))
        assertFalse(text.contains("第1次的输出"))
        assertTrue(text.contains("第2次的输出"))
        assertTrue(text.contains("第4次的输出"))
    }

    /** 单段超过上限：丢段头、留段尾，且**不切断行**、标记行仍在。 */
    @Test
    fun trimsSegmentHeadAndKeepsTailOnLineBoundaries() {
        val file = logFile()
        val cap = 4096
        val log = BootSegmentedLog(file, maxSegmentBytes = cap)

        log.beginBoot()
        repeat(12) { index -> log.append("L$index:" + "x".repeat(500)) }

        val all = lines(file)
        assertEquals("标记行必须留住", 1, all.count { BootSegmentedLog.isMarker(it) })
        assertTrue("段尾（最后一行）必须在", all.any { it.startsWith("L11:") })
        assertFalse("段头（最早那行）应被裁掉", all.any { it.startsWith("L0:") })
        assertTrue("裁剪后不应超过上限太多", file.length() <= cap + 200L)
        for (line in all) {
            if (BootSegmentedLog.isMarker(line)) continue
            assertTrue("必须是完整的行（不切断）：${line.take(12)}", line.matches(Regex("""L\d+:x+""")))
            assertEquals("行内容长度不得被改", 500, line.substringAfter(":").length)
        }
    }

    /** 偶发的超长单行也必须有界，且不能把标记行连带丢掉。 */
    @Test
    fun oversizedSingleLineIsTruncatedButMarkerSurvives() {
        val file = logFile()
        val cap = 2048
        val log = BootSegmentedLog(file, maxSegmentBytes = cap)

        log.beginBoot()
        log.append("H" + "y".repeat(20_000))

        val all = lines(file)
        assertEquals(1, all.count { BootSegmentedLog.isMarker(it) })
        assertTrue("整体应有界", file.length() <= cap + 200L)
        assertEquals("超长行只留尾部", 2, all.size)
    }

    /** 历史遗留的「无标记内容」自成一段，并在后续启动中被正常淘汰。 */
    @Test
    fun legacyContentWithoutMarkersCountsAsOneSegment() {
        val file = logFile()
        file.parentFile?.mkdirs()
        file.writeText("旧日志（没有标记行）\n", Charsets.UTF_8)
        val log = BootSegmentedLog(file, maxSegments = 1)

        log.beginBoot()
        log.append("新一段")

        val text = file.readText(Charsets.UTF_8)
        assertFalse("只有一段额度时，遗留内容应被新段挤掉", text.contains("旧日志"))
        assertTrue(text.contains("新一段"))
    }

    /** 未超限时不得改动已写内容（逐行原样）。 */
    @Test
    fun doesNotTouchContentUnderTheLimits() {
        val file = logFile()
        val log = BootSegmentedLog(file)

        log.beginBoot()
        listOf("a", "b", "c").forEach { log.append(it) }
        log.beginBoot()
        log.append("d")

        val all = lines(file)
        assertEquals(2, all.count { BootSegmentedLog.isMarker(it) })
        assertEquals(listOf("a", "b", "c"), all.filterNot { BootSegmentedLog.isMarker(it) }.take(3))
        assertEquals("d", all.last())
    }
}
