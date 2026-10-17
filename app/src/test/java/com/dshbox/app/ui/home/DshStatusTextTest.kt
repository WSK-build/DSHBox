package com.dshbox.app.ui.home

import com.dshbox.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DSH 卡片状态文案映射的 JVM 测试，验证各状态独立呈现及分支优先级。
 *
 * 启动中状态须与停止状态独立呈现，本测试单独覆盖该状态及其优先级。
 */
class DshStatusTextTest {

    @Test
    fun mapsEachTierIndependently() {
        assertEquals(
            R.string.home_dsh_stopped,
            dshStatusTextRes(dshReady = false, dshError = false, dshUnresponsive = false, dshStarting = false),
        )
        assertEquals(
            R.string.home_dsh_starting,
            dshStatusTextRes(dshReady = false, dshError = false, dshUnresponsive = false, dshStarting = true),
        )
        assertEquals(
            R.string.home_dsh_ready,
            dshStatusTextRes(dshReady = true, dshError = false, dshUnresponsive = false, dshStarting = false),
        )
        assertEquals(
            R.string.home_dsh_unresponsive,
            dshStatusTextRes(dshReady = false, dshError = false, dshUnresponsive = true, dshStarting = false),
        )
        assertEquals(
            R.string.home_dsh_error,
            dshStatusTextRes(dshReady = false, dshError = true, dshUnresponsive = false, dshStarting = false),
        )
    }

    @Test
    fun errorOutranksEveryOtherTier() {
        assertEquals(
            R.string.home_dsh_error,
            dshStatusTextRes(dshReady = true, dshError = true, dshUnresponsive = true, dshStarting = true),
        )
    }

    @Test
    fun unresponsiveOutranksReadyAndStarting() {
        assertEquals(
            R.string.home_dsh_unresponsive,
            dshStatusTextRes(dshReady = true, dshError = false, dshUnresponsive = true, dshStarting = true),
        )
    }

    @Test
    fun readyOutranksStarting() {
        // 理论上互斥；真出现同帧交叉（READY 拍与 STARTING 拍重合）时以就绪为准。
        assertEquals(
            R.string.home_dsh_ready,
            dshStatusTextRes(dshReady = true, dshError = false, dshUnresponsive = false, dshStarting = true),
        )
    }
}
