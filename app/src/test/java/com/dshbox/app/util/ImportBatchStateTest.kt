package com.dshbox.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批导入状态机单测（纯 JVM，2026-09-08 审查后新增）。
 * 锁定：逐件计数、取消后不再累计且抑制汇总、汇总阈值（单件不弹/多件弹）、文案参数。
 * 背景：多选导入曾因「冲突弹窗关闭未回传完成信号」导致驱动器永久挂起——该类分支
 * 只有抽成纯状态机才能被单测覆盖。
 */
class ImportBatchStateTest {

    @Test
    fun itemDoneCountsSuccessAndFailed() {
        var s = ImportBatchState(total = 3)
        s = s.itemDone(true)
        s = s.itemDone(false)
        s = s.itemDone(true)
        assertEquals(2, s.success)
        assertEquals(1, s.failed)
        assertFalse(s.cancelled)
    }

    @Test
    fun cancelStopsAccumulation() {
        var s = ImportBatchState(total = 5, success = 1)
        s = s.cancel()
        assertEquals(true, s.cancelled)
        val after = s.itemDone(true).itemDone(false)
        assertEquals(1, after.success)
        assertEquals(0, after.failed)
    }

    @Test
    fun summaryOnlyForMultipleAndNotCancelled() {
        assertFalse(ImportBatchState(total = 1).wantsSummary()) // 单件不弹
        assertTrue(ImportBatchState(total = 3).wantsSummary()) // 多件弹
        assertFalse(ImportBatchState(total = 3).cancel().wantsSummary()) // 取消不弹
    }

    @Test
    fun summaryArgsCarryCounts() {
        val s = ImportBatchState(total = 4, success = 2, failed = 1)
        assertEquals(2 to 1, s.summaryArgs())
    }
}