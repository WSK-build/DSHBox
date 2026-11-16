package com.dshbox.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁死 onboarding/服务两条启动路径共享的顺序：relay → 资产等待 → beforeSandbox →
 * sandbox。全新安装首次预览 404 的根因就是这条顺序在 onboarding 路径漂移。
 */
class SandboxStartupTest {

    @Test
    fun relayAndAssetsComeBeforeSandbox() = runBlocking {
        val order = mutableListOf<String>()
        val ready = runStartupSequence(
            startRelay = { order += "relay" },
            awaitRelayAssets = { order += "assets"; true },
            beforeSandbox = { order += "before" },
            startSandbox = { order += "sandbox" },
        )
        assertEquals(listOf("relay", "assets", "before", "sandbox"), order)
        assertTrue(ready)
    }

    @Test
    fun assetTimeoutStillStartsSandboxButReportsNotReady() = runBlocking {
        val order = mutableListOf<String>()
        val ready = runStartupSequence(
            startRelay = { order += "relay" },
            awaitRelayAssets = { order += "assets"; false },
            beforeSandbox = { order += "before" },
            startSandbox = { order += "sandbox" },
        )
        assertEquals(listOf("relay", "assets", "before", "sandbox"), order)
        assertFalse("超时不得阻断沙箱启动，但必须把未就绪如实交回", ready)
    }
}
