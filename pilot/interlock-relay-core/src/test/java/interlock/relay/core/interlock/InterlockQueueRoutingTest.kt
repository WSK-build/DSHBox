package interlock.relay.core.interlock

import interlock.relay.core.protocol.CapabilityId
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 确认通路怎么挑。
 *
 * 确认通路不得依赖把助手页带到前台：那会打断用户正在看的画面，而设备上的判据只是
 * 「页面有没有动」。通知栏承接了这一步，所以这条挑选顺序在设备之外也要钉住。
 */
class InterlockQueueRoutingTest {

    private var now = 1_000L
    private val seen = mutableListOf<InterlockQueue.Request?>()

    private fun prompt(
        capability: CapabilityId = CapabilityId.SECURE_SETTINGS,
        target: String? = "secure/window_animation_scale",
        detail: String? = null,
        identity: String? = null,
        volatile: Boolean = false,
        ttlMs: Long = 60_000L,
        allowsSessionGrant: Boolean = true,
    ) = ApprovalPrompt(
        capability = capability,
        // 身份默认与显示文字无关：真要比的是参数。各用例里凡按「对象/明细不同」
        // 断言不接的，都显式给出不同的 identity，否则测的是这个构造器而不是判据。
        argsIdentity = identity ?: SAME_ARGS,
        volatileTarget = volatile,
        titleRes = 0,
        summaryRes = 0,
        targetLabel = target,
        risk = RiskLevel.HIGH,
        callIndexInSession = 1,
        paramDetail = detail,
        allowsSessionGrant = allowsSessionGrant,
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

    /** 三条通路一条都不成立时如实回「问不了」，并且不往队列里挂任何东西。 */
    @Test
    fun noSurfaceRefusesInsteadOfParkingAQuestion() {
        val q = queue(foreground = false, canNotify = false)
        assertEquals(InterlockChoice.UNABLE_TO_SHOW, runBlocking { q.present(prompt()) })
        assertTrue(seen.isEmpty())
    }

    /** 通知栏能问到人就够了：不需要界面在前台，也不需要谁把界面推起来。 */
    @Test
    fun notificationAloneIsEnoughToAsk() {
        val q = queue(foreground = false, canNotify = true)
        assertEquals(InterlockChoice.AWAITING, runBlocking { q.present(prompt()) })
        assertEquals(1, seen.size)
        assertTrue(seen[0]!!.id > 0)
    }

    /** 一次答复只认它要答的那一张：迟到的那一下不能算答了后面另一件事。 */
    @Test
    fun answerCarriesTheRequestItWasAskedFor() {
        val q = queue()
        runBlocking { q.present(prompt()) }
        val pending = seen.last()!!
        assertFalse(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, pending.id + 7))
        assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, pending.id))
        // 答完要发一次「已经了结」，通知栏据此收掉那一条。
        assertNull(seen.last())
    }

    /**
     * 重试同一件事时挂的还是那一张，而且**不重发**呈现指令。
     *
     * 等待环每 500ms 重发同一问。若每次都再发一次呈现指令，通知就会每半秒换一次，
     * 用户刚要划掉它就又弹回来；页内那处也会被反复重画。因此「还是同一张」在这里
     * 体现为：展示位上仍是原来那一张，且呈现指令一次都不再发。
     */
    @Test
    fun retryOfTheSameQuestionKeepsThatRequestWithoutReposting() {
        val q = queue()
        runBlocking { q.present(prompt()) }
        val first = seen.last()!!
        assertEquals(InterlockChoice.AWAITING, runBlocking { q.present(prompt()) })
        assertEquals("同一问重发不产生新的呈现指令", 1, seen.size)
        assertSame(first, q.pendingSnapshot().displayed)
    }

    /**
     * 另一件事排在唯一的展示位后面：不拒绝、也不叠框，进了等待队列，本趟先回「还在等」。
     * 展示位上仍是第一问——等待中的问题不向任何通路呈现（排队与提升的规则在
     * InterlockQueueWaitingTurnTest 覆盖）。
     */
    @Test
    fun differentQuestionWaitsBehindTheLiveOne() {
        val q = queue()
        runBlocking { q.present(prompt(detail = "value=1", identity = "args-1")) }
        assertEquals(
            InterlockChoice.AWAITING,
            runBlocking { q.present(prompt(detail = "value=2", identity = "args-2")) },
        )
        assertEquals(1, seen.size)
        val snapshot = q.pendingSnapshot()
        assertEquals("args-1", snapshot.displayed!!.prompt.argsIdentity)
        assertEquals(1, snapshot.waitingCount)
    }

    /**
     * 过点的那一张挡不住后面的问题。
     *
     * 界面在前台时确认框自己到点收；只在通知栏上问的那一次没有谁来收它。
     * 不收的话，后面每一条不同的调用都会被挡成「还在等」，而屏上没有可答的东西。
     */
    @Test
    fun expiredRequestIsPrunedNotBlockingOthers() {
        val q = queue()
        runBlocking { q.present(prompt(capability = CapabilityId.CONTACT_READ, ttlMs = 50L)) }
        val first = seen.last()!!
        now += 5_000L
        assertEquals(InterlockChoice.AWAITING, runBlocking { q.present(prompt(capability = CapabilityId.CONTACT_WRITE)) })
        assertEquals(2, seen.size)
        assertNotEquals(first.id, seen[1]!!.id)
    }

    /** 等到答复那一次，裁决要原样回到调用方，并且把待决状态清干净。 */
    @Test
    fun answerReachesTheWaitingCaller() {
        val q = queue(inlineWaitMs = 2_000L)
        val outcome = runBlocking {
            val waiting = async { q.present(prompt()) }
            while (seen.isEmpty()) yield()
            val id = seen.last()!!.id
            assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, id))
            waiting.await()
        }
        assertEquals(InterlockChoice.ALLOW_ONCE, outcome)
        assertNull(seen.last())
    }

    /** 上限为「每次询问」的能力拿不到会话档：通知栏漏判时这里必须降级。 */
    @Test
    fun sessionGrantDowngradesWhenCeilingIsAskOnly() {
        val q = queue(inlineWaitMs = 2_000L)
        val outcome = runBlocking {
            val waiting = async { q.present(prompt(allowsSessionGrant = false)) }
            while (seen.isEmpty()) yield()
            val id = seen.last()!!.id
            assertTrue(q.resolveCurrent(InterlockChoice.ALLOW_SESSION, id))
            waiting.await()
        }
        assertEquals(InterlockChoice.ALLOW_ONCE, outcome)
    }

    /** 通道停了要能把挂着的那张收掉，别让通知栏留一条没人应答的按钮。 */
    @Test
    fun cancelAllClearsThePendingState() {
        val q = queue()
        runBlocking { q.present(prompt()) }
        val pending = seen.last()!!
        q.cancelAll()
        assertNull(seen.last())
        assertFalse(q.resolveCurrent(InterlockChoice.ALLOW_ONCE, pending.id))
    }
}
