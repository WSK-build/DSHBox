package com.dshbox.pluginmanager.market.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 市场侧"待生效开关"的记录与过期。
 *
 * 这张表只用来区分两种写入方：市场写的 `--patch` 层要等宿主下次组合，
 * 安装与 DSH 自身页面的开关走 profile/用户层并即时生效。记录错了的后果
 * 是状态文案说假话，所以过期判据必须只依赖"宿主此后启动过"这一个事实。
 */
class PendingToggleJournalTest {

    private fun tempJournal(): PendingToggleJournal {
        val file = Files.createTempDirectory("toggles").resolve("pending-toggles.json").toFile()
        return PendingToggleJournal(file)
    }

    @Test
    fun recordsAndReadsBack() {
        val journal = tempJournal()
        assertTrue(journal.pending().isEmpty())
        journal.record("alpha", atMillis = 1_000)
        journal.record("beta", atMillis = 1_000)
        assertEquals(setOf("alpha", "beta"), journal.pending())
    }

    @Test
    fun duplicateRecordKeepsLatestStamp() {
        val journal = tempJournal()
        journal.record("alpha", atMillis = 1_000)
        journal.record("alpha", atMillis = 5_000)
        journal.pruneStartedBefore(bootMillis = 3_000)
        // 最新一次拨动晚于启动时刻 → 仍待生效。
        assertEquals(setOf("alpha"), journal.pending())
    }

    @Test
    fun pruneDropsRecordsOlderThanBoot() {
        val journal = tempJournal()
        journal.record("old", atMillis = 1_000)
        journal.record("fresh", atMillis = 9_000)
        journal.pruneStartedBefore(bootMillis = 5_000)
        assertEquals(setOf("fresh"), journal.pending())
    }

    @Test
    fun unknownBootKeepsEverything() {
        val journal = tempJournal()
        journal.record("alpha", atMillis = 1_000)
        journal.pruneStartedBefore(bootMillis = null)
        assertEquals("读不到启动时刻时不得当作已兑现", setOf("alpha"), journal.pending())
    }

    @Test
    fun blankNameAndCorruptFileAreHarmless() {
        val journal = tempJournal()
        journal.record("   ")
        assertTrue(journal.pending().isEmpty())

        val file = Files.createTempDirectory("toggles2").resolve("pending-toggles.json").toFile()
        file.writeText("{ not json", Charsets.UTF_8)
        assertTrue(PendingToggleJournal(file).pending().isEmpty())
    }

    @Test
    fun bootStampTakesTheLastMarker() {
        val log = """
            ===== DSH boot 2026-10-30 08:00:00 =====
            some line
            ===== DSH boot 2026-10-30 22:19:54 =====
            tail
        """.trimIndent()
        val expected = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .parse("2026-10-30 22:19:54")!!.time
        assertEquals(expected, BootStamp.lastBootMillis(log))
    }

    @Test
    fun bootStampIgnoresMalformedOrMissingMarkers() {
        assertNull(BootStamp.lastBootMillis("no marker here"))
        assertNull(BootStamp.lastBootMillis("===== DSH boot not-a-time ====="))
        // 标记被日志截断时只剩半行，按"读不到"处理。
        assertNull(BootStamp.lastBootMillis("===== DSH boot 2026-10-30 22:1"))
    }

    @Test
    fun bootStampHandlesCrlfAndSurroundingText() {
        val log = "noise" + CRLF + "===== DSH boot 2026-10-30 22:19:54 =====" + CRLF + "more" + CRLF
        val expected = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .parse("2026-10-30 22:19:54")!!.time
        assertEquals(expected, BootStamp.lastBootMillis(log))
    }

    private companion object {
        const val CRLF = "\r\n"
    }
}
