package interlock.relay.core.transport

import interlock.relay.core.protocol.RelayResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挂起登记簿与挂起-续行结果形状的纯逻辑测试。登记/摘取/去重/容量/到点判定是挂起-续行流
 * 并发正确性的核心（先到者赢、满员回退、到点兜底），全部可在无设备环境下穷举验证。
 * MailboxServer 本体依赖 FileObserver，无法在纯 JVM 单测里实例化，这里钉住它的可测内核。
 */
class ParkedRegistryTest {

    private fun park(id: String, deadlineAtMs: Long = 10_000L) = ParkedRequest(
        requestId = id,
        resume = { error("resume must not run in registry tests") },
        approvalDeadlineAtMs = deadlineAtMs,
        executionDeadlineAtMs = deadlineAtMs + 30_000L,
        approvalOwner = "ui.click",
    )

    @Test
    fun registersAndTakesByRequestId() {
        val registry = ParkedRegistry(capacity = 2, backstopGraceMs = 0)
        val a = park("a")
        assertTrue(registry.register(a))
        assertEquals(1, registry.size())
        assertSame(a, registry.take("a"))
        // 摘取即去重：答复事件与到点兜底竞速时，后到的那次触发拿不到东西
        assertNull(registry.take("a"))
        assertEquals(0, registry.size())
    }

    @Test
    fun duplicateRegistrationIsRejected() {
        val registry = ParkedRegistry(capacity = 4, backstopGraceMs = 0)
        assertTrue(registry.register(park("dup")))
        assertFalse("同一 requestId 只登记一次", registry.register(park("dup")))
        assertEquals(1, registry.size())
    }

    @Test
    fun fullRegistryRejectsNewParksUntilASlotIsFreed() {
        val registry = ParkedRegistry(capacity = 2, backstopGraceMs = 0)
        assertTrue(registry.register(park("a")))
        assertTrue(registry.register(park("b")))
        assertFalse("满员退回：处理方据此走内联等待", registry.register(park("c")))
        registry.take("a")
        assertTrue(registry.register(park("c")))
    }

    @Test
    fun expiredSelectsOnlyPastDeadlinePlusGrace() {
        val registry = ParkedRegistry(capacity = 4, backstopGraceMs = 100)
        registry.register(park("early", deadlineAtMs = 1_000))
        registry.register(park("late", deadlineAtMs = 5_000))
        // 宽限期内不算到点：等待环自己的到点判定先来，兜底只接失联的残局
        assertTrue(registry.expired(nowMs = 1_050).isEmpty())
        assertEquals(listOf("early"), registry.expired(nowMs = 1_100))
        assertEquals(listOf("early", "late"), registry.expired(nowMs = 5_100))
        // 到点兜底不改登记内容：摘取发生在续行入口，扫描是只读的
        assertEquals(2, registry.size())
    }

    @Test
    fun clearDropsEverythingForTheNextSession() {
        val registry = ParkedRegistry(capacity = 4, backstopGraceMs = 0)
        registry.register(park("a"))
        registry.register(park("b"))
        registry.clear()
        assertEquals(0, registry.size())
        assertNull(registry.take("a"))
    }

    /** HandlerResult 两支的载荷形状：Done 带响应，Parked 带挂起项与两个截止时刻。 */
    @Test
    fun handlerResultCarriesItsPayload() {
        val response = RelayResponse(
            id = "r1",
            ok = true,
            data = JSONObject(),
            artifacts = emptyList(),
            surface = null,
            degradedFrom = null,
            reason = null,
            error = null,
            elapsedMs = 1L,
        )
        val done = HandlerResult.Done(response)
        assertSame(response, done.response)

        val item = park("p", deadlineAtMs = 7_000)
        val parked = HandlerResult.Parked(item)
        assertSame(item, parked.park)
        assertEquals("p", item.requestId)
        assertEquals("ui.click", item.approvalOwner)
        assertEquals(7_000L, item.approvalDeadlineAtMs)
        assertEquals(37_000L, item.executionDeadlineAtMs)
    }
}
