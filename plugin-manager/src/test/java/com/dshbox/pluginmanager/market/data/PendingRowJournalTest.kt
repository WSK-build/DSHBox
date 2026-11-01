package com.dshbox.pluginmanager.market.data

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卸载中断行日志的存储与结算判定。
 *
 * 结算的代价是不对称的：误删会把真实生效的停用行删掉（插件复活），
 * 漏删只是层里多一条打不中的行。所以结算只信磁盘事实——包在安装清单里
 * 就一律视为"remove 没发生"，行保持原样。
 */
class PendingRowJournalTest {

    private fun tempJournal(): Pair<PendingRowJournal, java.io.File> {
        // 临时文件名与生产文件（PluginPaths.pendingRowsFile）对齐，防止后来者再漂移。
        val file = Files.createTempDirectory("prows").resolve("pending-rows.dshbox.json").toFile()
        return PendingRowJournal(file) to file
    }

    @Test
    fun beginUpsertsAndFinishRemoves() {
        val (journal, file) = tempJournal()
        journal.begin("a", listOf("row-a1", "row-a2"))
        journal.begin("a", listOf("row-a3"))
        journal.begin("b", listOf("row-b1"))
        assertEquals(
            listOf(PendingRowOp("a", listOf("row-a3")), PendingRowOp("b", listOf("row-b1"))),
            journal.list(),
        )
        journal.finish("a")
        assertEquals(listOf(PendingRowOp("b", listOf("row-b1"))), journal.list())
        assertTrue("记录落过盘", file.isFile)
    }

    @Test
    fun emptyRowsAndBlankNamesAreNotJournaled() {
        val (journal, _) = tempJournal()
        journal.begin("a", emptyList())
        journal.begin("  ", listOf("row-x"))
        assertTrue(journal.list().isEmpty())
    }

    @Test
    fun missingAndCorruptFilesReadAsEmpty() {
        val (journal, file) = tempJournal()
        assertTrue(journal.list().isEmpty())
        file.writeText("{ not json", Charsets.UTF_8)
        assertTrue(journal.list().isEmpty())
    }

    @Test
    fun finishAbsentNameDoesNotTouchDisk() {
        val (journal, file) = tempJournal()
        journal.finish("ghost")
        assertTrue("没有记录时不得凭空写文件", !file.exists())
    }

    @Test
    fun settleSplitsByInstalledTruth() {
        val ops = listOf(
            PendingRowOp("gone", listOf("row-1")),
            PendingRowOp("here", listOf("row-2")),
            PendingRowOp("", listOf("row-3")),
            PendingRowOp("empty", emptyList()),
        )
        val settled = PendingRowSettle.settle(ops, installedNames = setOf("here", "other"))
        assertEquals(listOf(PendingRowOp("gone", listOf("row-1"))), settled.orphaned)
        assertEquals(listOf(PendingRowOp("here", listOf("row-2"))), settled.intact)
    }
}
