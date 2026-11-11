package com.dshbox.remote.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一次性凭据消费门控测试：首个干净首帧提交一次；失败页与无凭据会话不提交。 */
class PairingConsumptionGateTest {

    @Test fun cleanFirstFrameFiresExactlyOnce() {
        val gate = PairingConsumptionGate(usesConsumableCredential = true)
        assertTrue(gate.onPageFinished(loadSucceeded = true, pageNavigationFailed = false))
        // 后续完结（刷新/重建）不再重复提交
        assertFalse(gate.onPageFinished(loadSucceeded = true, pageNavigationFailed = false))
    }

    @Test fun failedPageDoesNotFireUntilCleanFrame() {
        val gate = PairingConsumptionGate(usesConsumableCredential = true)
        // 错误页也发成功完结（system 引擎），但页内出现过主框架失败：不提交
        assertFalse(gate.onPageFinished(loadSucceeded = true, pageNavigationFailed = true))
        assertFalse(gate.onPageFinished(loadSucceeded = false, pageNavigationFailed = true))
        // 自愈重试后的干净首帧仍可提交（此时票已兑换成功）
        assertTrue(gate.onPageFinished(loadSucceeded = true, pageNavigationFailed = false))
    }

    @Test fun sessionsWithoutPairingLoadNeverFire() {
        val gate = PairingConsumptionGate(usesConsumableCredential = false)
        assertFalse(gate.onPageFinished(loadSucceeded = true, pageNavigationFailed = false))
        assertFalse(gate.onPageFinished(loadSucceeded = false, pageNavigationFailed = false))
    }
}
