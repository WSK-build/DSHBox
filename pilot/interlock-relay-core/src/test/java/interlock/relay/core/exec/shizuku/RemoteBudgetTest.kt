package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 远端等待预算分配的判据：它决定一条远端事务本地愿意等多久，以及剩余预算装不下
 * 最小往返时干脆不发。这里只验证能抽成纯函数的部分——分配函数、放行判定、放弃
 * 等待后的措辞与各常量之间的自洽。
 *
 * 执行器隔离的并发行为（binder 卡死、事务排队、本地放弃后远端最终完成）需要真机
 * 与真实 binder，纯 JVM 测不稳，靠实机矩阵覆盖。
 */
class RemoteBudgetTest {

    @Test
    fun `a generous budget keeps the default cap`() {
        assertEquals(
            15_000L,
            ShizukuBackend.remoteWaitMs(15_000L, 60_000L, ShizukuBackend.REMOTE_MARGIN_MS),
        )
        assertEquals(
            120_000L,
            ShizukuBackend.remoteWaitMs(
                120_000L,
                120_000L + ShizukuBackend.REMOTE_MARGIN_MS,
                ShizukuBackend.REMOTE_MARGIN_MS,
            ),
        )
    }

    @Test
    fun `a budget below the default clamps the wait`() {
        assertEquals(
            9_500L,
            ShizukuBackend.remoteWaitMs(15_000L, 10_000L, ShizukuBackend.REMOTE_MARGIN_MS),
        )
        assertEquals(
            ShizukuBackend.MIN_REMOTE_ROUNDTRIP_MS,
            ShizukuBackend.remoteWaitMs(
                15_000L,
                ShizukuBackend.MIN_REMOTE_ROUNDTRIP_MS + ShizukuBackend.REMOTE_MARGIN_MS,
                ShizukuBackend.REMOTE_MARGIN_MS,
            ),
        )
    }

    @Test
    fun `a budget that cannot cover one round trip refuses to issue`() {
        val floor = ShizukuBackend.MIN_REMOTE_ROUNDTRIP_MS + ShizukuBackend.REMOTE_MARGIN_MS
        assertNull(ShizukuBackend.remoteWaitMs(15_000L, floor - 1L, ShizukuBackend.REMOTE_MARGIN_MS))
        assertNull(ShizukuBackend.remoteWaitMs(15_000L, 0L, ShizukuBackend.REMOTE_MARGIN_MS))
        assertNull(ShizukuBackend.remoteWaitMs(15_000L, -1L, ShizukuBackend.REMOTE_MARGIN_MS))
    }

    @Test
    fun `the issue decision agrees with the allocation`() {
        assertTrue(ShizukuBackend.canIssueRemote(15_000L, 10_000L, ShizukuBackend.REMOTE_MARGIN_MS))
        assertFalse(
            ShizukuBackend.canIssueRemote(
                15_000L,
                ShizukuBackend.REMOTE_MARGIN_MS,
                ShizukuBackend.REMOTE_MARGIN_MS,
            ),
        )
    }

    @Test
    fun `the reserve covers the reply slack so the local wait stays inside the budget`() {
        // 本地等待 = 发给远端的超时 + 回执余量；余量必须吃进收尾预留里，
        // 本地等待才不会越过按预算分配出的那一格。
        assertTrue(ShizukuBackend.REMOTE_MARGIN_MS > ShizukuBackend.REMOTE_SLACK_MS)
        assertTrue(ShizukuBackend.REMOTE_MARGIN_ARTIFACT_MS > ShizukuBackend.REMOTE_SLACK_MS)
        // 带产物的收尾更重：落一份帧或 apk 文件比拼一段 JSON 费时。
        assertTrue(ShizukuBackend.REMOTE_MARGIN_ARTIFACT_MS >= ShizukuBackend.REMOTE_MARGIN_MS)
        assertTrue(ShizukuBackend.MIN_REMOTE_ROUNDTRIP_MS > 0L)
    }

    @Test
    fun `the give-up reason says the outcome is unknown, never not executed`() {
        val submitted = ShizukuBackend.remoteGiveUpReason("install", 1_000L, 120L)
        assertTrue(submitted.contains("outcome is unknown"))
        assertTrue(submitted.contains("do not treat this as not executed"))
        assertTrue(submitted.contains("submitted 120ms before the local budget expired"))

        val queued = ShizukuBackend.remoteGiveUpReason("command", 800L, null)
        assertTrue(queued.contains("outcome is unknown"))
        assertTrue(queued.contains("do not treat this as not executed"))
        assertTrue(queued.contains("still queued"))
    }
}
