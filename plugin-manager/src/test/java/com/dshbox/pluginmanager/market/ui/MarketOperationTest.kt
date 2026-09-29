package com.dshbox.pluginmanager.market.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 操作记录的输出缓冲。
 *
 * 一次安装的 pnpm 输出可能上千行；记录本身必须有硬上限，否则长安装会一直
 * 占着内存。保留的是**最近**的行——用户关心的是尾部。
 */
class MarketOperationTest {

    @Test
    fun bufferedLinesAreCappedToTheMostRecentOnes() {
        val operation = MarketOperation(MarketOperationKind.INSTALL, "pkg")
        repeat(MAX_LINES + 120) { operation.append("line-$it") }
        assertEquals(MAX_LINES, operation.lines.size)
        assertEquals("line-120", operation.lines.first())
        assertEquals("line-${MAX_LINES + 119}", operation.lines.last())
    }

    @Test
    fun blankLinesAreIgnored() {
        val operation = MarketOperation(MarketOperationKind.UPDATE, "pkg")
        operation.append("   ")
        operation.append("real\n")
        assertEquals(listOf("real"), operation.lines)
    }

    @Test
    fun restartClearsTheBufferedOutput() {
        val operation = MarketOperation(MarketOperationKind.UNINSTALL, "pkg")
        operation.append("a")
        operation.restart()
        assertTrue(operation.lines.isEmpty())
    }
}
