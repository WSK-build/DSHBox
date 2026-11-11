package com.dshbox.remote.session

/**
 * 前台累计时钟：仅宿主处于前台的时间段参与计时（毫秒）。
 *
 * 会话保留档（保留 N 分钟后释放）以本时钟计量失活时长：后台期间不推进，
 * 锁屏/切走不会误释放，也不会因后台滞留而永久不清。恢复前台时由消费方
 * 依据累计值做补偿判定。
 *
 * 时间源可注入（JVM 单测用假时钟驱动），默认取系统墙钟。
 */
class ForegroundElapsedClock(private val wall: () -> Long = System::currentTimeMillis) {

    /** 已完结前台片段的累计时长。 */
    private var accumulatedMillis = 0L

    /** 当前前台片段的起始墙钟时刻；null 表示处于后台。 */
    private var foregroundSpanStart: Long? = null

    /** 是否处于前台（宿主已上报进入前台且尚未上报退到后台）。 */
    val isForeground: Boolean get() = foregroundSpanStart != null

    /** 上报进入前台：开始新的计时片段（重复调用幂等）。 */
    fun onForeground() {
        if (foregroundSpanStart == null) foregroundSpanStart = wall()
    }

    /** 上报退到后台：结清当前片段并停止计时（重复调用幂等）。 */
    fun onBackground() {
        val start = foregroundSpanStart ?: return
        accumulatedMillis += (wall() - start).coerceAtLeast(0)
        foregroundSpanStart = null
    }

    /** 前台累计时长（毫秒）：后台期间读数不变。 */
    fun foregroundElapsedMillis(): Long =
        accumulatedMillis + (foregroundSpanStart?.let { (wall() - it).coerceAtLeast(0) } ?: 0L)
}
