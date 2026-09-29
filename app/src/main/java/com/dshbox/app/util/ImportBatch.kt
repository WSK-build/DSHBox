package com.dshbox.app.util

/**
 * 批导入状态机（纯 JVM，收尾）。
 *
 * 队列推进/完成信号若写成 Compose 局部函数链，会形成
 * 函数环（runImport→finish→advance→startFileImport）而无法前向声明；改用协程等待
 * （CompletableDeferred）后仍会出现「冲突弹窗关闭未回传信号 → 驱动器永久挂起」的
 * 死锁分支。因此状态机必须纯函数化：否则这类分支只能在设备上碰运气。
 * 本类承载计数/取消/汇总判定，Compose 驱动器（LaunchedEffect）与各导入出口只做
 * 状态转换与信号回传；纯 JVM 单测 `ImportBatchStateTest` 锁定。
 */
data class ImportBatchState(
    val total: Int,
    val success: Int = 0,
    val failed: Int = 0,
    val cancelled: Boolean = false,
) {
    /** 单件结束回传：ok=true 计成功，false 计失败；取消后不再累计。 */
    fun itemDone(ok: Boolean): ImportBatchState =
        if (cancelled) this
        else copy(
            success = success + if (ok) 1 else 0,
            failed = failed + if (ok) 0 else 1,
        )

    /** 取消整个批（= 放弃剩余；与「进度取消中止整批」口径一致）。 */
    fun cancel(): ImportBatchState = copy(cancelled = true)

    /** 是否弹汇总 toast：多件导入且未被取消（单件/取消不弹）。 */
    fun wantsSummary(): Boolean = !cancelled && total > 1

    /** 汇总文案参数 (成功数, 失败数)。 */
    fun summaryArgs(): Pair<Int, Int> = success to failed
}