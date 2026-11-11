package com.dshbox.remote.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话门禁时序器测试：加载放行时序（下发回调前不 load）与 404 自愈
 * （重发门禁 + 延迟重载一次，再失败才放弃）。
 */
class SessionGateSequencerTest {

    /** 可控的门禁通道：记录写入并手工触发完成回调。 */
    private class FakeWriter : GateCookieWriter {
        val writes = mutableListOf<Pair<String, String>>()
        val pendingCallbacks = mutableListOf<(Boolean) -> Unit>()

        override fun write(host: String, value: String, onResult: (Boolean) -> Unit) {
            writes.add(host to value)
            pendingCallbacks.add(onResult)
        }

        fun completeAll(success: Boolean = true) {
            val callbacks = pendingCallbacks.toList()
            pendingCallbacks.clear()
            callbacks.forEach { it(success) }
        }
    }

    /** 记录延迟与动作的调度器：单测手工推进时间。 */
    private class FakeScheduler : SessionGateSequencer.Scheduler {
        val scheduled = mutableListOf<Pair<Long, Runnable>>()

        override fun post(delayMillis: Long, action: Runnable) {
            scheduled.add(delayMillis to action)
        }

        fun runAll() {
            val tasks = scheduled.toList()
            scheduled.clear()
            tasks.forEach { it.second.run() }
        }
    }

    @Test fun firstLoadWaitsForGateCookieCallback() {
        val writer = FakeWriter()
        val sequencer = SessionGateSequencer(writer)
        var loads = 0
        sequencer.dispatchFirstLoad("127.0.0.2", "token-1") { loads += 1 }

        // 时序约束：setCookie 完成回调之前不得放行加载
        assertEquals(1, writer.writes.size)
        assertEquals(0, loads)
        writer.completeAll()
        assertEquals(1, loads)
    }

    @Test fun firstLoadProceedsWhenGateWriteFails() {
        // 下发失败同样放行：加载会命中代理 404，由自愈路径兜底
        val writer = FakeWriter()
        val sequencer = SessionGateSequencer(writer)
        var loads = 0
        sequencer.dispatchFirstLoad("127.0.0.2", "token-1") { loads += 1 }
        writer.completeAll(success = false)
        assertEquals(1, loads)
    }

    @Test fun bypassModeLoadsImmediately() {
        // 无门禁模式（gecko 降级）：无通道无令牌，直接放行
        val sequencer = SessionGateSequencer(null)
        var loads = 0
        sequencer.dispatchFirstLoad("127.0.0.2", null) { loads += 1 }
        assertEquals(1, loads)
    }

    @Test fun mainDocumentFailureResendsGateAndSchedulesSingleReload() {
        val writer = FakeWriter()
        val scheduler = FakeScheduler()
        val sequencer = SessionGateSequencer(writer, scheduler)
        var reloads = 0
        var gaveUp = false

        val healing = sequencer.onMainDocumentFailure(
            "127.0.0.2", "token-1",
            retryLoad = { reloads += 1 },
            onGiveUp = { gaveUp = true },
        )

        // 自愈路径：重发门禁 → 完成回调后延迟重载（延迟非零，不立即重载）
        assertTrue(healing)
        assertEquals(1, writer.writes.size)
        assertEquals(0, reloads)
        assertFalse(gaveUp)
        writer.completeAll()
        assertEquals(1, scheduler.scheduled.size)
        assertTrue(scheduler.scheduled.single().first > 0)
        scheduler.runAll()
        assertEquals(1, reloads)
    }

    @Test fun secondFailureGivesUpWithoutFurtherWrites() {
        val writer = FakeWriter()
        val scheduler = FakeScheduler()
        val sequencer = SessionGateSequencer(writer, scheduler)
        var reloads = 0
        var gaveUp = false

        sequencer.onMainDocumentFailure("127.0.0.2", "token-1", { reloads += 1 }, { gaveUp = true })
        writer.completeAll()
        scheduler.runAll()

        // 自愈机会已用尽：再次失败不再重发门禁，直接转失败态
        val healing = sequencer.onMainDocumentFailure(
            "127.0.0.2", "token-1",
            retryLoad = { reloads += 1 },
            onGiveUp = { gaveUp = true },
        )
        assertFalse(healing)
        assertTrue(gaveUp)
        assertEquals(1, writer.writes.size)
        assertEquals(1, reloads)
    }

    @Test fun bypassModeStillSelfHealsWithDelayOnly() {
        // 无门禁模式的失败重试：只延迟重载，不写 cookie
        val scheduler = FakeScheduler()
        val sequencer = SessionGateSequencer(null, scheduler)
        var reloads = 0
        var gaveUp = false

        val healing = sequencer.onMainDocumentFailure("127.0.0.2", null, { reloads += 1 }, { gaveUp = true })
        assertTrue(healing)
        assertFalse(gaveUp)
        scheduler.runAll()
        assertEquals(1, reloads)
    }
}
