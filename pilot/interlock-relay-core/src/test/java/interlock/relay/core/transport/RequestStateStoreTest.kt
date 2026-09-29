package interlock.relay.core.transport

import interlock.relay.core.log.LogEvent
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 请求状态是「同一件事」与副作用边界的持久依据，两条纪律在这里钉死：
 * 状态以文件为准（读不到按不存在），mayHaveDispatched 落盘不成功就不得执行。
 * 时钟全部注入，保留期的两段边界用推进时钟来测。
 */
class RequestStateStoreTest {

    private lateinit var tempDir: File
    private lateinit var stateDir: File
    private var clock = 1_000_000L

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("request-state").toFile()
        stateDir = File(tempDir, "requests")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun store() = RequestStateStore(stateDir, now = { clock }, wallNow = { clock + 7 })

    private val digest = "a".repeat(64)
    private val otherDigest = "b".repeat(64)

    @Test
    fun createIsIdempotentForSameDigestAndConflictingForDifferentDigest() {
        val first = store().create("r1", digest, "ui.click", 90_000L)
        assertTrue("expected Created, got $first", first is CreateOutcome.Created)
        val created = (first as CreateOutcome.Created).state
        assertEquals(RequestPhase.QUEUED, created.phase)
        assertEquals(clock, created.createdAtMs)

        // 同 id 同摘要：幂等命中，返回既有状态而不是第二条记录。
        val again = store().create("r1", digest, "ui.click", 90_000L)
        assertTrue(again is CreateOutcome.Existing)
        assertEquals(created.id, (again as CreateOutcome.Existing).state.id)

        // 同 id 异摘要：拒绝，绝不覆盖——否则重放判据会被后来者改写。
        val conflict = store().create("r1", otherDigest, "ui.click", 90_000L)
        assertTrue(conflict is CreateOutcome.DigestConflict)
        assertEquals(digest, (conflict as CreateOutcome.DigestConflict).existing.digest)
    }

    /** 状态以文件为准：另一个实例（模拟另一次进程内读取）读到的必须是落盘内容。 */
    @Test
    fun phaseChainIsReadableFromDisk() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)
        val admitted = s.markAdmitted("r1")
        assertNotNull(admitted)
        assertEquals(RequestPhase.ADMITTED, admitted!!.phase)
        assertEquals(clock, admitted.admittedAtMs)

        val waitingRelayUser = s.markWaiting("r1", relayUser = true)
        assertEquals(RequestPhase.WAITING_RELAY_USER, waitingRelayUser!!.phase)
        val waitingAndroid = s.markWaiting("r1", relayUser = false)
        assertEquals(RequestPhase.WAITING_ANDROID_USER, waitingAndroid!!.phase)

        val fresh = store()
        val fromDisk = fresh.get("r1")
        assertNotNull(fromDisk)
        assertEquals(RequestPhase.WAITING_ANDROID_USER, fromDisk!!.phase)
        assertEquals(digest, fromDisk.digest)
        assertEquals("ui.click", fromDisk.capability)
        assertEquals(90_000L, fromDisk.ttlMs)
    }

    @Test
    fun dispatchGuardPersistsTheBoundaryAndSurvivesAInstanceReopen() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)
        assertTrue(s.markExecDispatchGuard("r1"))

        val fromDisk = store().get("r1")!!
        assertEquals(RequestPhase.EXECUTING, fromDisk.phase)
        assertTrue("边界标记必须落了盘，恢复扫描靠它判副作用", fromDisk.mayHaveDispatched)
    }

    /** 细分守卫结论：放行与「无可执行状态」（已终态/不存在）两支各归各位，布尔口径一致。 */
    @Test
    fun detailedDispatchGuardSplitsTheRefusalKinds() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)
        val armed = s.armExecDispatchGuard("r1")
        assertTrue("可执行状态放行", armed is DispatchGuardOutcome.Armed)
        assertEquals(RequestPhase.EXECUTING, (armed as DispatchGuardOutcome.Armed).state.phase)
        assertTrue("布尔口径与细分结论一致", s.markExecDispatchGuard("r1"))

        // 已终态：无可执行状态，携带读到的现状——重试同一条没有意义。
        s.recordTerminal("r1", RequestPhase.SUCCEEDED)
        val settled = s.armExecDispatchGuard("r1")
        assertTrue(settled is DispatchGuardOutcome.NotExecutable)
        assertEquals(RequestPhase.SUCCEEDED, (settled as DispatchGuardOutcome.NotExecutable).state?.phase)

        // 记录不存在：同样无可执行状态，现状如实为 null。
        val absent = s.armExecDispatchGuard("missing")
        assertTrue(absent is DispatchGuardOutcome.NotExecutable)
        assertNull((absent as DispatchGuardOutcome.NotExecutable).state)
    }

    /** stateDir 本身是个普通文件：一切读写静默失败，守卫返回 false，绝不抛异常。 */
    @Test
    fun dispatchGuardRefusesWhenPersistenceIsImpossible() {
        val blocker = File(tempDir, "blocker")
        assertTrue(blocker.createNewFile())
        val s = RequestStateStore(blocker, now = { clock }, wallNow = { clock + 7 })
        assertTrue("登记失败要走返回值", s.create("r1", digest, "ui.click", 90_000L) is CreateOutcome.PersistFailed)
        assertNull(s.get("r1"))
        assertFalse(s.markExecDispatchGuard("r1"))
        // 存储都建不起来时记录必然不存在：细分结论落在「无可执行状态」这一支，
        // 现状如实为 null（真正落到 PersistFailed 一支需要「读得到现状、写不进盘」，
        // 纯 JVM 测试给不出跨平台的确定性注入，由运行日志按 id 分辨）。
        val detailed = s.armExecDispatchGuard("r1")
        assertTrue(detailed is DispatchGuardOutcome.NotExecutable)
        assertNull((detailed as DispatchGuardOutcome.NotExecutable).state)
    }

    /** 状态文件被换成一个同名目录：按「不存在」处理，守卫同样拒绝执行。 */
    @Test
    fun unreadableStateFileIsTreatedAsAbsent() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)
        assertTrue(File(stateDir, "r1.json").delete())
        assertTrue(File(stateDir, "r1.json").mkdirs())
        assertNull(s.get("r1"))
        assertFalse(s.markExecDispatchGuard("r1"))
        assertNull(s.recordTerminal("r1", RequestPhase.SUCCEEDED, "{}"))
    }

    @Test
    fun terminalRecordsResultAndRefusesRewrites() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)
        val terminal = s.recordTerminal("r1", RequestPhase.SUCCEEDED, resultJson = """{"ok":true,"id":"r1"}""")
        assertNotNull(terminal)
        assertEquals(RequestPhase.SUCCEEDED, terminal!!.phase)
        assertEquals("""{"ok":true,"id":"r1"}""", terminal.resultJson)
        assertEquals(clock, terminal.terminalAtMs)

        // 先提交的终态是唯一结论：后到的覆盖会让两次读取得到两个「最终结果」。
        val rewritten = store().recordTerminal("r1", RequestPhase.FAILED, resultJson = """{"ok":false}""")
        assertEquals(RequestPhase.SUCCEEDED, rewritten!!.phase)
        assertEquals("""{"ok":true,"id":"r1"}""", rewritten.resultJson)
    }

    @Test
    fun terminalOnlyAcceptsTerminalPhases() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)
        assertNull("非终态不该从 recordTerminal 进来", s.recordTerminal("r1", RequestPhase.EXECUTING))
        assertEquals(RequestPhase.QUEUED, s.get("r1")!!.phase)
    }

    @Test
    fun cancelMarkerIsPersistedAndDistinguishesTheProvablyUndispatchedCase() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)
        val before = s.requestCancel("r1")
        assertTrue(before is CancelOutcome.Requested)
        assertTrue((before as CancelOutcome.Requested).provablyUndispatched)
        assertTrue(store().get("r1")!!.cancelRequested)

        // 派发之后取消标记仍可记，但不能再给出「可证未执行」的答复。
        s.markExecDispatchGuard("r1")
        val after = s.requestCancel("r1")
        assertTrue(after is CancelOutcome.Requested)
        assertFalse((after as CancelOutcome.Requested).provablyUndispatched)

        // 终态之后取消为时已晚：按当前终态答复，终态之上的改写不发生。
        s.recordTerminal("r1", RequestPhase.SUCCEEDED)
        assertTrue(s.requestCancel("r1") is CancelOutcome.AlreadyTerminal)
        val settled = store().get("r1")!!
        assertEquals(RequestPhase.SUCCEEDED, settled.phase)
        assertTrue("先前记下的取消意愿不因终态丢失", settled.cancelRequested)

        assertTrue(s.requestCancel("missing") is CancelOutcome.NotFound)
    }

    /** 终态满 30 分钟回包置空标 EXPIRED，满 24 小时删文件；寿命之内的未终态不受清理影响。 */
    @Test
    fun purgeExpiresInTwoStages() {
        val s = store()
        s.create("done", digest, "ui.click", 90_000L)
        s.recordTerminal("done", RequestPhase.SUCCEEDED, resultJson = """{"ok":true}""")

        s.purgeExpired(clock + 29L * 60L * 1000L)
        assertEquals(RequestPhase.SUCCEEDED, s.get("done")!!.phase)
        assertEquals("""{"ok":true}""", s.get("done")!!.resultJson)

        val expiredAt = clock + 31L * 60L * 1000L
        s.purgeExpired(expiredAt)
        val expired = s.get("done")!!
        assertEquals("过期不当未执行：查询仍要得到明确答复", RequestPhase.EXPIRED, expired.phase)
        assertNull(expired.resultJson)
        assertNotNull("过期后文件仍在，状态可查询", expired)

        s.purgeExpired(clock + 24L * 60L * 60L * 1000L + 60_000L)
        assertNull("过期记录满保留期后删除", s.get("done"))

        // 孤儿请求寿命之内的未终态请求不参与保留期清理，阶段照旧保留。
        s.create("flying", otherDigest, "ui.waitFor", 90_000L)
        assertEquals(RequestPhase.QUEUED, s.get("flying")!!.phase)
    }

    /**
     * 非终态记录超过孤儿请求寿命即视为遗留：守卫不再放行执行，巡检按持久化边界
     * 收尾成终态（可证未派发的终结，已派发的保守转 UNKNOWN），寿命之内的记录不碰。
     */
    @Test
    fun staleNonTerminalRecordsAreRefusedByTheGuardAndClosedByTheSweep() {
        val ttl = interlock.relay.core.storage.QuotaLedger.ORPHAN_REQUEST_TTL_MS
        val s = store()
        s.create("stale", digest, "app.install", 90_000L)

        // 寿命之内差一毫秒：记录仍算在飞，过期判定为否。
        clock += ttl - 1
        assertFalse(RequestStateStore.nonTerminalRecordExpired(s.get("stale")!!, clock))
        // 到点即遗留：纯判定为真，守卫按「无可执行状态」拒绝并携带现状。
        clock += 1
        assertTrue(RequestStateStore.nonTerminalRecordExpired(s.get("stale")!!, clock))
        val refused = s.armExecDispatchGuard("stale")
        assertTrue(refused is DispatchGuardOutcome.NotExecutable)
        assertEquals(RequestPhase.QUEUED, (refused as DispatchGuardOutcome.NotExecutable).state?.phase)

        // 巡检收尾：可证未派发的以 INTERRUPTED_BEFORE_EXECUTION 终结并落盘。
        s.purgeExpired(clock)
        val closed = s.get("stale")!!
        assertEquals(RequestPhase.INTERRUPTED_BEFORE_EXECUTION, closed.phase)
        assertEquals(clock, closed.terminalAtMs)

        // 已派发的遗留保守转 UNKNOWN，与启动恢复同一套持久化边界。
        s.create("maybe-ran", otherDigest, "ui.click", 90_000L)
        s.markExecDispatchGuard("maybe-ran")
        clock += ttl
        s.purgeExpired(clock)
        assertEquals(RequestPhase.UNKNOWN, s.get("maybe-ran")!!.phase)

        // 刚登记的非终态记录不受巡检影响。
        s.create("young", "d".repeat(64), "pkg.query", 90_000L)
        s.purgeExpired(clock)
        assertEquals(RequestPhase.QUEUED, s.get("young")!!.phase)
    }

    /** 恢复扫描按边界标记收尾：可证未派发的如实终结，不能证实的保守转 UNKNOWN。 */
    @Test
    fun recoverySplitsUnfinishedRequestsByTheDispatchBoundary() {
        val s = store()
        s.create("never-ran", digest, "ui.click", 90_000L)
        s.markAdmitted("never-ran")
        s.create("maybe-ran", otherDigest, "app.install", 90_000L)
        s.markExecDispatchGuard("maybe-ran")
        s.create("already-done", "c".repeat(64), "pkg.query", 90_000L)
        s.recordTerminal("already-done", RequestPhase.SUCCEEDED)

        val recovered = s.recoverUnfinished()
        assertEquals(2, recovered.size)
        val phases = recovered.associate { it.id to it.phase }
        assertEquals(RequestPhase.INTERRUPTED_BEFORE_EXECUTION, phases["never-ran"])
        assertEquals(RequestPhase.UNKNOWN, phases["maybe-ran"])

        // 收尾要落盘：新实例读到的是终态，终态记录不再被恢复改写。
        val fresh = store()
        assertEquals(RequestPhase.INTERRUPTED_BEFORE_EXECUTION, fresh.get("never-ran")!!.phase)
        assertEquals(RequestPhase.UNKNOWN, fresh.get("maybe-ran")!!.phase)
        assertEquals(RequestPhase.SUCCEEDED, fresh.get("already-done")!!.phase)
        assertTrue(fresh.recoverUnfinished().isEmpty())
    }

    /** 目录超过上限时，登记前先强制清理一轮：把过期终态腾出去，防灌不靠入口一处。 */
    @Test
    fun createForcesAPurgeWhenTheDirectoryOverflows() {
        val s = store()
        // 灌满上限：513 条已完成且早已过保留期的终态。
        repeat(RequestStateStore.MAX_TRACKED + 1) { index ->
            val id = "s$index"
            s.create(id, "%064x".format(index), "pkg.query", 90_000L)
            s.recordTerminal(id, RequestPhase.SUCCEEDED, resultJson = """{"n":$index}""")
        }
        clock += RequestStateStore.RESULT_RETENTION_MS + 60_000L
        val outcome = s.create("fresh", "f".repeat(64), "pkg.query", 90_000L)
        assertTrue(outcome is CreateOutcome.Created)

        val swept = store().get("s0")!!
        assertEquals("超限后的强制清理把旧终态标成过期并清空回包", RequestPhase.EXPIRED, swept.phase)
        assertNull(swept.resultJson)
    }

    /**
     * 受理这条阶段事件只记一次。
     *
     * 判据落在「阶段有没有真的换」上：`markAdmitted` 可能被多处按需调用（受理、
     * 恢复巡检、剔除后重排），重复调用不得把 `admittedAt` 改写成新的一笔 ——
     * 那会让 `REQUEST_COMPLETED` 的耗时从受理时刻起算变成从最近一次重复调用起算。
     * 日志本身在桌面 JVM 上不可写（依赖 android.util.Log），故断言状态与纯判据：
     * 第二次调用不换阶段，于是 [RequestStateStore.stageEntryEvent] 返回 null。
     */
    @Test
    fun markAdmittedRecordsTheTransitionOnce() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)

        val first = s.markAdmitted("r1")
        assertNotNull(first)
        assertEquals(RequestPhase.ADMITTED, first!!.phase)
        val admittedAt = first.admittedAtMs
        assertEquals(LogEvent.REQUEST_ADMITTED, RequestStateStore.stageEntryEvent(RequestPhase.QUEUED, first.phase))

        clock += 5_000
        val again = s.markAdmitted("r1")
        assertEquals("重复受理不得改写受理时刻", admittedAt, again!!.admittedAtMs)
        assertEquals(
            "阶段没换就不该再记一条受理",
            null,
            RequestStateStore.stageEntryEvent(first.phase, again.phase),
        )
    }

    /** 等待阶段同理：换到另一种等待（relay↔android）是又一次进入，要各记一次。 */
    @Test
    fun markWaitingRecordsEachKindOfEntryOnce() {
        val s = store()
        s.create("r1", digest, "ui.click", 90_000L)
        s.markAdmitted("r1")

        val relay = s.markWaiting("r1", relayUser = true)!!
        assertEquals(RequestPhase.WAITING_RELAY_USER, relay.phase)
        assertEquals(
            LogEvent.REQUEST_WAITING_USER,
            RequestStateStore.stageEntryEvent(RequestPhase.ADMITTED, relay.phase),
        )
        assertEquals(RequestStateStore.WAITING_KIND_RELAY, RequestStateStore.waitingKindOf(relay.phase))

        val same = s.markWaiting("r1", relayUser = true)!!
        assertEquals("同一种等待重复进入不再记", null, RequestStateStore.stageEntryEvent(relay.phase, same.phase))

        val android = s.markWaiting("r1", relayUser = false)!!
        assertEquals(RequestPhase.WAITING_ANDROID_USER, android.phase)
        assertEquals(
            "换到另一种等待是又一次进入",
            LogEvent.REQUEST_WAITING_USER,
            RequestStateStore.stageEntryEvent(relay.phase, android.phase),
        )
        assertEquals(RequestStateStore.WAITING_KIND_ANDROID, RequestStateStore.waitingKindOf(android.phase))
    }

    /** 记录不存在或已是终态时不产生「进入」：阶段事件只在真实迁移上出现。 */
    @Test
    fun stageEntryIsSilentWhenNothingActuallyMoved() {
        assertNull(RequestStateStore.stageEntryEvent(null, RequestPhase.ADMITTED))
        assertNull(RequestStateStore.stageEntryEvent(RequestPhase.ADMITTED, null))
        assertNull(RequestStateStore.stageEntryEvent(null, null))
        // 非本任务接的阶段（执行、终态）不在表内：交给各自的调用点报。
        assertNull(RequestStateStore.stageEntryEvent(RequestPhase.ADMITTED, RequestPhase.EXECUTING))
        assertNull(RequestStateStore.stageEntryEvent(RequestPhase.ADMITTED, RequestPhase.SUCCEEDED))
    }
}
