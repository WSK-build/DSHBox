package interlock.relay.core.transport

import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RELAY_PROTOCOL_VERSION
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控制通道的可测核心：v1 转写、取消判定表、status 映射、health 计数与信箱准入排序/驱逐
 * 的纯函数。消费循环本身依赖 FileObserver，无法在纯 JVM 单测里实例化；这些判定抽成
 * 无副作用函数后在这里钉死，控制通道的安全边界（只读、只转发、不执行能力动作）因此
 * 不依赖循环代码的正确性。
 */
class ControlChannelLogicTest {

    private val digest = "a".repeat(64)

    private fun submit(id: String = "req-1") = ControlRequest.Submit(
        id = id,
        capability = CapabilityId.UI_CLICK,
        args = JSONObject("{\"selector\":{\"text\":\"OK\"}}"),
        hostTtlMs = 90_000L,
        payloadDigest = digest,
    )

    private fun state(phase: RequestPhase, mayHaveDispatched: Boolean = false) = RequestState(
        id = "req-1",
        digest = digest,
        capability = "ui.click",
        phase = phase,
        createdAtMs = 1_000L,
        ttlMs = 90_000L,
        mayHaveDispatched = mayHaveDispatched,
    )

    /** 转写件必须是 v1 形状，且 id 与文件名咬合：直接过 v1 解码是唯一权威判据。 */
    @Test
    fun submitTranscribesIntoV1ShapeInterlockedWithTheId() {
        val ts = 1_760_000_000_000L
        val json = JSONObject(ControlChannelLogic.submitEnvelopeV1(submit(), ts))
        assertEquals(RELAY_PROTOCOL_VERSION, json.optInt("v"))
        assertEquals("req-1", json.optString("id"))
        assertEquals(ts, json.optLong("ts"))
        assertEquals("ui.click", json.optString("capability"))
        assertEquals(90_000L, json.optLong("ttlMs"))
        assertEquals("OK", json.optJSONObject("args")?.optJSONObject("selector")?.optString("text"))

        val decoded = EnvelopeCodec.decodeRequest(json.toString(), "req-1.json")
        val request = (decoded as DecodeResult.Valid).request
        assertEquals("req-1", request.id)
        assertEquals("ui.click", request.capability)
        assertEquals(90_000L, request.timeoutMs)
    }

    /** 取消判定表：五支各归各位，EXECUTING 与 WAITING 一律只能记取消意愿。 */
    @Test
    fun cancelPlanCoversAllFiveSituations() {
        // 未认领：文件还在收件箱，可证宿主尚未执行。
        assertEquals(CancelPlan.RemoveUnclaimed, ControlChannelLogic.cancelPlan(state(RequestPhase.QUEUED), fileInInbox = true))

        // 已认领但未执行（等本模块确认 / 等系统授权框 / 执行中）：只能记取消意愿。
        assertEquals(
            CancelPlan.MarkRequested,
            ControlChannelLogic.cancelPlan(state(RequestPhase.WAITING_RELAY_USER), fileInInbox = false),
        )
        assertEquals(
            CancelPlan.MarkRequested,
            ControlChannelLogic.cancelPlan(state(RequestPhase.WAITING_ANDROID_USER), fileInInbox = false),
        )
        assertEquals(
            CancelPlan.MarkRequested,
            ControlChannelLogic.cancelPlan(
                state(RequestPhase.EXECUTING, mayHaveDispatched = true),
                fileInInbox = false,
            ),
        )

        // 已终态：取消为时已晚，按既有终态答复。
        assertEquals(
            CancelPlan.AlreadySettled(RequestPhase.SUCCEEDED),
            ControlChannelLogic.cancelPlan(state(RequestPhase.SUCCEEDED), fileInInbox = true),
        )

        // 未找到：不断言它做过什么。
        assertEquals(CancelPlan.NotFound, ControlChannelLogic.cancelPlan(null, fileInInbox = true))
    }

    /** CANCEL_REQUESTED 不算终态：取消标记挂上后取消判定仍走「记意愿」一支。 */
    @Test
    fun cancelRequestedPhaseIsNotTerminalForTheCancelPlan() {
        assertEquals(
            CancelPlan.MarkRequested,
            ControlChannelLogic.cancelPlan(state(RequestPhase.CANCEL_REQUESTED), fileInInbox = false),
        )
    }

    /** status 映射：命中带全四个读数，result 是终态回包 JSON；未命中只有 found:false。 */
    @Test
    fun statusPayloadMapsHitAndMiss() {
        val missing = ControlChannelLogic.statusPayload(null, "req-1")
        assertEquals("req-1", missing.optString("targetId"))
        assertFalse(missing.optBoolean("found"))
        assertTrue(missing.isNull("result"))

        val hit = ControlChannelLogic.statusPayload(
            state(RequestPhase.SUCCEEDED).copy(
                resultJson = """{"ok":true,"id":"req-1"}""",
                cancelRequested = true,
            ),
            "req-1",
        )
        assertTrue(hit.optBoolean("found"))
        assertEquals("SUCCEEDED", hit.optString("state"))
        assertEquals(true, hit.optJSONObject("result")?.optBoolean("ok"))
        assertFalse(hit.optBoolean("mayHaveDispatched"))
        assertTrue(hit.optBoolean("cancelRequested"))

        // 过期不当未执行：EXPIRED 的读数里没有 result，但状态名如实是 EXPIRED。
        val expired = ControlChannelLogic.statusPayload(state(RequestPhase.EXPIRED), "req-1")
        assertEquals("EXPIRED", expired.optString("state"))
        assertTrue(expired.isNull("result"))
    }

    /** 损坏的终态回包按「无结果」处理，绝不让解析异常逃出读数路径。 */
    @Test
    fun statusPayloadSurvivesACorruptResultJson() {
        val hit = ControlChannelLogic.statusPayload(
            state(RequestPhase.FAILED).copy(resultJson = "{not json"),
            "req-1",
        )
        assertEquals("FAILED", hit.optString("state"))
        assertTrue(hit.isNull("result"))
    }

    /** health 读数：三条线各说各的，没有最老待办时如实给 null。 */
    @Test
    fun healthPayloadCarriesLivenessDepthAndAge() {
        val payload = ControlChannelLogic.healthPayload(channelLive = true, queueDepth = 3, oldestQueuedAgeMs = 1_500L)
        assertTrue(payload.optBoolean("channelLive"))
        assertEquals(3, payload.optInt("queueDepth"))
        assertEquals(1_500L, payload.optLong("oldestQueuedAgeMs"))

        val cold = ControlChannelLogic.healthPayload(channelLive = false, queueDepth = 0, oldestQueuedAgeMs = null)
        assertFalse(cold.optBoolean("channelLive"))
        assertTrue(cold.isNull("oldestQueuedAgeMs"))
    }

    /** 探活计数：深度按文件数；最老待办取「宿主最早知道它」的时刻，收件箱与在途一并算。 */
    @Test
    fun queueCountsSumsDepthAndTakesTheOldestStamp() {
        val counts = MailboxServer.queueCounts(
            inbox = listOf("b.json", "c.json"),
            processing = listOf("a.json"),
            seenAt = mapOf("a.json" to 5_000L, "b.json" to 9_000L),
            admittedAt = mapOf("a.json" to 7_000L),
            nowMs = 12_000L,
        )
        assertEquals(3, counts.depth)
        // 最老的是 5 秒前首次见到、现已认领进 processing 的那件：等待不因认领而重新计时。
        assertEquals(7_000L, counts.oldestAgeMs)
    }

    /** 没有单调记账的残留件只计深度：内存里没有可信时刻，不拿可改写的 mtime 充数。 */
    @Test
    fun queueCountsIgnoresUntrackedFilesForAge() {
        val counts = MailboxServer.queueCounts(
            inbox = listOf("stale.json"),
            processing = emptyList(),
            seenAt = emptyMap(),
            admittedAt = emptyMap(),
            nowMs = 12_000L,
        )
        assertEquals(1, counts.depth)
        assertNull(counts.oldestAgeMs)
    }

    /** 准入排序按真实到达时刻，文件名不参与先后；没有记账的排最后再按名字。 */
    @Test
    fun pendingOrderFollowsArrivalNotFileName() {
        val ordered = MailboxServer.orderPending(
            listOf("z.json" to 10L, "a.json" to 30L, "m.json" to 20L, "new.json" to null),
        )
        assertEquals(listOf("z.json", "m.json", "a.json", "new.json"), ordered)

        // 同名时序（到达时刻相同）按名字稳定互排；全无记账时亦然。
        assertEquals(
            listOf("a.json", "b.json"),
            MailboxServer.orderPending(listOf("b.json" to 10L, "a.json" to 10L)),
        )
        assertEquals(
            listOf("a.json", "b.json"),
            MailboxServer.orderPending(listOf("b.json" to null, "a.json" to null)),
        )
    }

    /** 驱逐选最晚到的：先来的先受理，后来的拿可重试失败；未超限时不驱逐任何一件。 */
    @Test
    fun evictionPicksTheLatestArrivals() {
        val ordered = MailboxServer.orderPending(
            listOf("a.json" to 1L, "b.json" to 2L, "c.json" to 3L, "d.json" to 4L),
        )
        assertEquals(listOf("c.json", "d.json"), MailboxServer.evictionVictims(ordered, capacity = 2))
        assertTrue(MailboxServer.evictionVictims(ordered, capacity = 4).isEmpty())
        assertEquals(listOf("d.json"), MailboxServer.evictionVictims(ordered, capacity = 3))
    }

    /** 合规件（经改名到达）免静默期；其余按首次见到的时刻等满窗口。 */
    @Test
    fun renameArrivalsSkipTheSettleWindowOthersWaitItOut() {
        // 名字极大、刚落地：有改名标记就不等。
        assertFalse(MailboxServer.needsSettle(seenAtMs = 1_000L, nowMs = 1_000L, arrivedByRename = true))
        // 同一时刻、无标记：等满窗口。
        assertTrue(MailboxServer.needsSettle(seenAtMs = 1_000L, nowMs = 1_100L, arrivedByRename = false))
        // 窗口走完：不再等。
        assertFalse(MailboxServer.needsSettle(seenAtMs = 1_000L, nowMs = 1_300L, arrivedByRename = false))
    }

    /** 终态帧决策：交回了落盘记录就如实转述，与 status 查询读同一份记录、同一口径。 */
    @Test
    fun terminalFrameCarriesTheRecordedOutcomeInStepWithStatusQueries() {
        val recorded = state(RequestPhase.SUCCEEDED).copy(resultJson = """{"ok":true,"id":"req-1"}""")
        val frame = MailboxServer.terminalFramePayload("req-1", recorded)
        assertEquals("SUCCEEDED", frame.optString("state"))
        assertEquals("req-1", frame.optString("targetId"))
        assertEquals(true, frame.optJSONObject("result")?.optBoolean("ok"))
        // 与 status 查询逐字对齐：阶段名与结果来自同一份记录。
        val status = ControlChannelLogic.statusPayload(recorded, "req-1")
        assertEquals(status.optString("state"), frame.optString("state"))

        // 先提交的终态是唯一结论：已是终态的记录按现状转述，不按本次想写的阶段。
        val rewritten = MailboxServer.terminalFramePayload(
            "req-1",
            state(RequestPhase.UNKNOWN),
        )
        assertEquals("UNKNOWN", rewritten.optString("state"))
        assertTrue(rewritten.isNull("result"))
    }

    /** 终态没能落住（记录缺失或写盘失败）：帧改报 UNKNOWN，result 省略、附 cause。 */
    @Test
    fun terminalFrameFallsBackToUnknownWhenTheTerminalStateDidNotPersist() {
        val frame = MailboxServer.terminalFramePayload("req-1", recorded = null)
        assertEquals("UNKNOWN", frame.optString("state"))
        assertEquals("req-1", frame.optString("targetId"))
        assertTrue("落不住就不许带 result", frame.isNull("result"))
        assertTrue(frame.has("cause"))
        // cause 带「错误码/原因」形状：客户端按内嵌码对齐退出码。
        assertTrue(frame.optString("cause").startsWith("E_INTERNAL/"))
    }

    /** 损坏的终态回包在帧里按「无结果」处理，解析异常不得逃出决策路径。 */
    @Test
    fun terminalFrameSurvivesACorruptResultJson() {
        val frame = MailboxServer.terminalFramePayload(
            "req-1",
            state(RequestPhase.FAILED).copy(resultJson = "{not json"),
        )
        assertEquals("FAILED", frame.optString("state"))
        assertTrue(frame.isNull("result"))
    }

    /**
     * 认领冲突判定：「真的有一条在飞」由 processing 文件或已越过受理的记录给出，
     * 任一命中即挡；QUEUED 是控制通道为这份文件自己建的受理记录，**绝不挡**——
     * 按记录非终态一刀切会把 v2 的每一条正常入队饿死在 QUEUED（真机实测事故，
     * 本用例即该事故的回归锚）。
     */
    @Test
    fun claimingIsBlockedOnlyWhileARequestWithTheSameIdIsGenuinelyInFlight() {
        // processing 区确有同 id 文件：文件本身就是归宿的权威信号，一律挡（含终态与无记录）。
        RequestState.TERMINAL_PHASES.plus(RequestPhase.QUEUED).forEach { phase ->
            assertTrue(
                "processing 文件在，$phase 也必须挡顶位",
                MailboxServer.claimBlockedByInFlight(state(phase), nowMs = 61_000L, processingFileExists = true),
            )
        }
        assertTrue(
            "processing 文件在，无记录也必须挡",
            MailboxServer.claimBlockedByInFlight(null, nowMs = 61_000L, processingFileExists = true),
        )
        // 无 processing 文件：QUEUED 是这份文件自己的受理记录，放行（回归锚点）。
        assertFalse(
            "QUEUED 受理记录不得挡自己的文件认领",
            MailboxServer.claimBlockedByInFlight(state(RequestPhase.QUEUED), nowMs = 61_000L, processingFileExists = false),
        )
        // 无 processing 文件但记录已越过受理：同 id 的另一份投递此刻到达，挡。
        listOf(
            RequestPhase.ADMITTED,
            RequestPhase.WAITING_RELAY_USER,
            RequestPhase.WAITING_ANDROID_USER,
            RequestPhase.READY,
            RequestPhase.EXECUTING,
            RequestPhase.CANCEL_REQUESTED,
        ).forEach { phase ->
            assertTrue(
                "已越受理的 $phase 挡第二份同 id 投递",
                MailboxServer.claimBlockedByInFlight(state(phase), nowMs = 61_000L, processingFileExists = false),
            )
        }
        RequestState.TERMINAL_PHASES.forEach { phase ->
            assertFalse(
                "终态 $phase 不挡认领",
                MailboxServer.claimBlockedByInFlight(state(phase), nowMs = 61_000L, processingFileExists = false),
            )
        }
        assertFalse(
            "v1 或无记录不挡认领",
            MailboxServer.claimBlockedByInFlight(null, nowMs = 61_000L, processingFileExists = false),
        )
    }

    /**
     * 非终态记录超过孤儿请求寿命即视为遗留：不再挡同 id 重投（对应请求文件早被
     * 按龄回收），寿命之内仍在飞的那条照旧挡住，终态记录永不参与过期判定。
     */
    @Test
    fun aNonTerminalRecordOlderThanTheOrphanLifetimeNoLongerBlocksClaiming() {
        val ttl = interlock.relay.core.storage.QuotaLedger.ORPHAN_REQUEST_TTL_MS
        // 寿命之内（差一毫秒）且无 processing 文件：已越受理的记录仍在飞，照旧挡。
        assertTrue(
            MailboxServer.claimBlockedByInFlight(
                state(RequestPhase.ADMITTED), nowMs = 1_000L + ttl - 1, processingFileExists = false,
            ),
        )
        // 寿命到点：遗留记录放行，同 id 得以重投。
        assertFalse(
            MailboxServer.claimBlockedByInFlight(
                state(RequestPhase.ADMITTED), nowMs = 1_000L + ttl, processingFileExists = false,
            ),
        )
        // 过期判定只碰非终态：终态记录无论多老都走「不挡」的既有路径。
        assertFalse(
            MailboxServer.claimBlockedByInFlight(
                state(RequestPhase.FAILED, mayHaveDispatched = true),
                nowMs = 1_000L + ttl * 10,
                processingFileExists = false,
            ),
        )
    }

    /**
     * 在途分片名不算畸形：三类写入中文名（CLI 的点前缀分片、控制通道的 `.part`、
     * 原子写的 `.tmp` 暂存件）必须从畸形候选集中排除，其余怪名字照旧回畸形。
     */
    @Test
    fun incomingPartNamesAreNeverAnsweredAsMalformed() {
        // 入口 CLI 的控制分片：点前缀 + .part 后缀。
        assertTrue(MailboxServer.isIncomingPartName(".req-1.json.part"))
        // 控制通道转写件的工作分片：只有 .part 后缀。
        assertTrue(MailboxServer.isIncomingPartName("req-1.part"))
        // 原子写落在收件箱里等改名的暂存件。
        assertTrue(MailboxServer.isIncomingPartName("req-1.part-4821.tmp"))
        // 除此之外的怪名字仍按畸形回话，不能被这个过滤器吞掉。
        assertFalse(MailboxServer.isIncomingPartName("bad name.json"))
        assertFalse(MailboxServer.isIncomingPartName("req-1.json.bak"))
        assertFalse(MailboxServer.isIncomingPartName("tmp"))
    }
}
