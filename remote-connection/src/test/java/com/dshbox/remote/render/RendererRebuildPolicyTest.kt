package com.dshbox.remote.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 渲染引擎崩溃重建记账测试：连续语义（成功渲染清零）与超限转失败。 */
class RendererRebuildPolicyTest {

    @Test fun crashesWithinLimitAreRebuilt() {
        val policy = RendererRebuildPolicy()
        assertTrue(policy.onCrash())
        assertTrue(policy.onCrash())
        assertTrue(policy.onCrash())
    }

    @Test fun consecutiveCrashesBeyondLimitGiveUp() {
        val policy = RendererRebuildPolicy()
        repeat(3) { assertTrue(policy.onCrash()) }
        assertFalse(policy.onCrash())
        assertFalse(policy.onCrash())
    }

    @Test fun successfulPageLoadResetsConsecutiveCount() {
        val policy = RendererRebuildPolicy()
        repeat(3) { assertTrue(policy.onCrash()) }
        policy.onPageLoaded()
        // 成功渲染后连续计数清零：再次崩溃重新从零起算
        assertTrue(policy.onCrash())
        assertTrue(policy.onCrash())
        assertTrue(policy.onCrash())
        assertFalse(policy.onCrash())
    }
}
