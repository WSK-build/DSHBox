package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 有界等待队列的排队规则。
 *
 * 展示位上只能有一张框：后到的不同问题按先后排进等待队列，展示位空出后按 FIFO 提升；
 * 队列也满时新问题当场回「还没轮到」，不无限积压。等待中的问题不向任何通路呈现，
 * 因此也绝不会被「答」——提升之后才有编号可认。
 */
class InterlockQueueWaitingTurnTest {

    private var now = 1_000L
    private val seen = mutableListOf<InterlockQueue.Request?>()

    private fun prompt(
        identity: String,
        ttlMs: Long = 60_000L,
    ) = ApprovalPrompt(
        capability = CapabilityId.SECURE_SETTINGS,
        argsIdentity = identity,
        volatileTarget = false,
        titleRes = 0,
        summaryRes = 0,
        targetLabel = "secure/$identity",
        risk = RiskLevel.HIGH,
        callIndexInSession = 1,
        paramDetail = identity,
        allowsSessionGrant = true,
        deadlineAtMs = now + ttlMs,
    )

    private fun queue(
        foreground: Boolean = false,
        canNotify: Boolean = true,
        inlineWaitMs: Long = 1L,
    ) = InterlockQueue(
        isForegroundCapable = { foreground },
        onRouting = { seen.add(it.request) },
        overlayBlind = { false },
        canNotify = { canNotify },
        now = { now },
        inlineWaitMs = inlineWaitMs,
    )

    /** 队列总容量三张：第 4 个并发的不同问题拿不到位置，回「还没轮到」。 */
    @Test
    fun fourthConcurrentQuestionGetsWaitingTurn() {
        val q = queue()
        runBlocking {
            assertEquals(InterlockChoice.AWAITING, q.present(prompt("args-1")))
            assertEquals(InterlockChoice.AWAITING, q.present(prompt("args-2")))
            assertEquals(InterlockChoice.AWAITING, q.present(prompt("args-3")))
            assertEquals(
                "前面三张都还没答，第四问连排队的位置都没有",
                InterlockChoice.WAITING_TURN,
                q.present(prompt("args-4")),
            )
        }
        // 只有第一问上过展示位：等待中的问题不驱动任何呈现。
        assertEquals(1, seen.size)
        val snapshot = q.pendingSnapshot()
        assertEquals("args-1", snapshot.displayed!!.prompt.argsIdentity)
        assertEquals(2, snapshot.waitingCount)
    }

    /** 展示位空出后按先后提升：答掉一张，最早排队的那张自动升上来并发一次回调。 */
    @Test
    fun promotionFollowsArrivalOrderWhenTheSlotClears() {
        val q = queue()
        runBlocking {
            q.present(prompt("args-1"))
            q.present(prompt("args-2"))
            q.present(prompt("args-3"))
            assertEquals(1, seen.size)

            assertTrue(q.resolveCurrent(InterlockChoice.DENY, seen[0]!!.id))
            val second = seen.last()!!
            assertEquals("args-2", second.prompt.argsIdentity)

            assertTrue(q.resolveCurrent(InterlockChoice.DENY, second.id))
            assertEquals("args-3", seen.last()!!.prompt.argsIdentity)

            assertTrue(q.resolveCurrent(InterlockChoice.DENY, seen.last()!!.id))
            assertNull("最后一张答完，展示位清空", seen.last())
        }
    }

    /**
     * 等待中的问题到点未提升就被移除，绝不升上来挡路；仍有人在等它的那一趟
     * 以 EXPIRED 收场，而不是干等一个永远不会提升的请求。
     */
    @Test
    fun expiredWaitingRequestIsDroppedNotPromoted() {
        val q = queue(inlineWaitMs = 5_000L)
        runBlocking {
            // 第一问占住展示位；第二问排进等待队列后就挂在那里等。
            val displayed = async(start = CoroutineStart.UNDISPATCHED) {
                q.present(prompt("args-1", ttlMs = 60_000L))
            }
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                q.present(prompt("args-2", ttlMs = 50L))
            }
            assertEquals(1, q.pendingSnapshot().waitingCount)
            // 第二问到点，但展示位还占着：提升必须跳过它。
            now += 5_000L
            assertTrue(q.resolveCurrent(InterlockChoice.DENY, seen[0]!!.id))
            assertNull("过点的等待项没有升上展示位", seen.last())
            assertEquals("仍在等它的那一趟拿到终态", InterlockChoice.EXPIRED, queued.await())
            assertEquals(InterlockChoice.DENY, displayed.await())
        }
    }

    /** cancelAll 连等待队列一起清：展示位与所有排着的等待方都当场放走。 */
    @Test
    fun cancelAllAlsoReleasesTheWaitingRequests() {
        val q = queue(inlineWaitMs = 10_000L)
        runBlocking {
            val calls = (1..3).map { index ->
                async(start = CoroutineStart.UNDISPATCHED) { q.present(prompt("args-$index")) }
            }
            assertNotNull("第一问在展示位上", q.pendingSnapshot().displayed)
            assertEquals(2, q.pendingSnapshot().waitingCount)

            q.cancelAll()

            val snapshot = q.pendingSnapshot()
            assertNull(snapshot.displayed)
            assertEquals(0, snapshot.waitingCount)
            assertFalse(q.resolveCurrent(InterlockChoice.ALLOW_ONCE))
            calls.forEach { assertEquals("没有等待方被留在原地", InterlockChoice.DENY, it.await()) }
            assertNull(seen.last())
        }
    }
}
