package interlock.relay.core.interlock

import interlock.relay.core.protocol.RelayExitCode
import interlock.relay.core.transport.ControlChannelServer
import interlock.relay.core.transport.EnvelopeCodec
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ask 链路的契约测试：失败码 → 退出码、回包形状、旧件判定，以及
 * **跨语言的两条不变量**（宿主上屏预算与入口等待余量、问题/选项长度上限）。
 *
 * 为什么值得单独钉：失败分类若只存在于回包前缀里，两端就得各写一份
 * （宿主写原因串、入口按前缀猜），改一处漏一处不会有人发现；上屏预算和长度上限
 * 同样是"两边各写一个数"，任何一侧单独改都不会被编译器拦住。
 */
class AskContractTest {

    @Test
    fun exitCodesFollowTheSharedTable() {
        assertEquals(RelayExitCode.TIMEOUT, PromptCodes.exitCodeFor(PromptCodes.TIMEOUT_NO_ANSWER))
        assertEquals(RelayExitCode.TIMEOUT, PromptCodes.exitCodeFor(PromptCodes.TIMEOUT_COVERED))
        assertEquals(RelayExitCode.TIMEOUT, PromptCodes.exitCodeFor(PromptCodes.TIMEOUT_EXPIRED))
        assertEquals(RelayExitCode.NEEDS_USER_ACTION, PromptCodes.exitCodeFor(PromptCodes.NO_SURFACE_NOT_GRANTED))
        assertEquals(RelayExitCode.NEEDS_USER_ACTION, PromptCodes.exitCodeFor(PromptCodes.NO_SURFACE_BLIND))
        assertEquals(RelayExitCode.NEEDS_USER_ACTION, PromptCodes.exitCodeFor(PromptCodes.NO_SURFACE_HIDDEN))
        assertEquals(RelayExitCode.RETRY_LATER, PromptCodes.exitCodeFor(PromptCodes.BUSY_ANOTHER_QUESTION))
        assertEquals(RelayExitCode.RETRY_LATER, PromptCodes.exitCodeFor(PromptCodes.BUSY_APPROVAL_CARD))
        assertEquals(RelayExitCode.RETRY_LATER, PromptCodes.exitCodeFor(PromptCodes.BUSY_CHANNEL_STOPPED))
        assertEquals(RelayExitCode.INTERNAL, PromptCodes.exitCodeFor(PromptCodes.INTERNAL_NO_CHANNEL))
        assertEquals(RelayExitCode.INTERNAL, PromptCodes.exitCodeFor(PromptCodes.INTERNAL_DUPLICATE))
    }

    /**
     * 不认识的前缀一律按宿主内部故障收口：否则一个拼错的码就会让入口把它
     * 归进"需要用户动手"（4），把用户拉去做没用的事。
     */
    @Test
    fun unknownPrefixFallsBackToInternalNotToAUserAction() {
        assertEquals(PromptCodes.INTERNAL, PromptCodes.codeOf("E_ASK_SOMETHING_ELSE/whatever"))
        assertEquals(RelayExitCode.INTERNAL, PromptCodes.exitCodeFor("E_ASK_SOMETHING_ELSE/whatever"))
        assertEquals(RelayExitCode.INTERNAL, PromptCodes.exitCodeFor("garbage"))
        assertFalse(PromptCodes.retryable("E_ASK_SOMETHING_ELSE/whatever"))
        assertTrue(PromptCodes.retryable(PromptCodes.BUSY_ANOTHER_QUESTION))
        assertTrue(PromptCodes.retryable(PromptCodes.NO_SURFACE_NOT_GRANTED))
    }

    @Test
    fun failurePayloadCarriesCodeRetryableAndExitCode() {
        val busy = ControlChannelServer.askFailurePayload(PromptCodes.BUSY_APPROVAL_CARD)
        assertEquals(PromptCodes.BUSY_APPROVAL_CARD, busy.getString("cause"))
        assertEquals("E_ASK_BUSY", busy.getJSONObject("error").getString("code"))
        assertEquals(RelayExitCode.RETRY_LATER, busy.getJSONObject("error").getInt("exitCode"))
        assertTrue(busy.getJSONObject("error").getBoolean("retryable"))

        val internal = ControlChannelServer.askFailurePayload(PromptCodes.INTERNAL_NO_CHANNEL)
        assertEquals(RelayExitCode.INTERNAL, internal.getJSONObject("error").getInt("exitCode"))
        assertFalse(internal.getJSONObject("error").getBoolean("retryable"))
    }

    /**
     * 旧件判定：窗口 + 宽限之内算新鲜，越过就算躺过头；文件时间在未来（时钟异常）
     * 按新鲜处理——宁可多问一次，也不要因为对不上钟就把一件新的请求丢掉。
     */
    @Test
    fun staleAsksAreOnlyRefusedAfterTheirWholeBudgetHasElapsed() {
        val now = 1_000_000L
        val timeout = 60_000L
        val grace = ControlChannelServer.ASK_STALE_GRACE_MS
        assertEquals(0L, ControlChannelServer.askStaleMs(now - timeout - grace, timeout, now))
        assertTrue(ControlChannelServer.askStaleMs(now - timeout - grace - 1, timeout, now) > 0L)
        assertEquals("未来时间戳按新鲜处理", 0L, ControlChannelServer.askStaleMs(now + 5_000, timeout, now))
        assertEquals("拿不到时间戳时不拦", 0L, ControlChannelServer.askStaleMs(0L, timeout, now))
    }

    /**
     * 跨语言不变量一：宿主上屏判定预算必须小于入口的等待余量，且余量还要装得下
     * 认领延迟（观察者失效时退化成一次 idle 轮询，5 秒）。
     *
     * 入口预算 = timeoutMs + 余量；宿主最坏耗时 = 认领延迟 + 上屏预算 + timeoutMs。
     * 余量一旦小于「认领延迟 + 上屏预算」，正常收尾的回包会落在客户端预算之外，
     * 被记成「没有回包」（而屏上那张卡还在）——这条不变量没有任何编译器守得住，
     * 所以在这里直接读入口脚本的常量来比。
     */
    @Test
    fun hostOnScreenBudgetStaysUnderTheEntryWaitMargin() {
        val margin = jsNumber("ASK_BUDGET_MARGIN_MS")
        assertTrue(
            "上屏预算(${RelayAskSurface.ON_SCREEN_BUDGET_MS}) 必须小于入口余量($margin)",
            RelayAskSurface.ON_SCREEN_BUDGET_MS < margin,
        )
        assertTrue(
            "入口余量($margin) 还要装下认领延迟($claimLatencyCeilingMs) + 上屏预算" +
                "(${RelayAskSurface.ON_SCREEN_BUDGET_MS})",
            margin >= claimLatencyCeilingMs + RelayAskSurface.ON_SCREEN_BUDGET_MS,
        )
    }

    /** 认领延迟上限：控制循环的 idle 轮询间隔（读生产常量本身，不抄数字）。 */
    private val claimLatencyCeilingMs = ControlChannelServer.IDLE_POLL_MS

    /**
     * 跨语言不变量二：问题与选项的长度上限在宿主与入口两侧是同一组数。
     * 两侧不一致时，入口放过的请求会被宿主整条拒掉，而入口只能把它报成内部故障。
     * 宿主侧读的是 [EnvelopeCodec] 的 internal 常量本身（不是抄一遍数字）。
     */
    @Test
    fun askLengthLimitsMatchBetweenHostAndEntry() {
        assertEquals(EnvelopeCodec.MAX_ASK_QUESTION_CHARS.toLong(), jsNumber("ASK_MAX_QUESTION_CHARS"))
        assertEquals(EnvelopeCodec.MAX_ASK_OPTION_CHARS.toLong(), jsNumber("ASK_MAX_OPTION_CHARS"))
        assertEquals(EnvelopeCodec.MIN_ASK_TIMEOUT_MS, jsNumber("ASK_MIN_TIMEOUT_MS"))
        assertEquals(EnvelopeCodec.MAX_ASK_TIMEOUT_MS, jsNumber("ASK_MAX_TIMEOUT_MS"))
        assertEquals(EnvelopeCodec.DEFAULT_ASK_TIMEOUT_MS, jsNumber("ASK_DEFAULT_TIMEOUT_MS"))
    }

    /** 从入口脚本里读一个 `const NAME = <number>;`。找不到当场失败，不静默给默认值。 */
    private fun jsNumber(name: String): Long {
        val script = entryScript()
        val match = Regex("const\\s+$name\\s*=\\s*(\\d+)").find(script)
            ?: throw AssertionError("入口脚本里找不到常量 $name")
        return match.groupValues[1].toLongOrNull()
            ?: throw AssertionError("常量 $name 不是十进制整数: ${match.groupValues[1]}")
    }

    private fun entryScript(): String {
        // 单测的工作目录是模块根（interlock-relay-core/），但为了不依赖这一点，几个候选路径都试一遍。
        val candidates = listOf(
            "src/main/assets/relay/relay-client.cjs",
        )
        return candidates.map { File(it) }.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到入口脚本 relay-client.cjs；候选路径：$candidates")
    }

    /**
     * 回包形状：成功帧只带 choice、失败帧只带 cause+error —— 两件事不混在同一个键里。
     * 断言落在真正产出的字节上（[EnvelopeCodec.encodeControl]），不是自己造的对象。
     */
    @Test
    fun successAndFailureFramesKeepChoiceAndErrorApart() {
        val chosen = EnvelopeCodec.encodeControl(
            "a1", "ask", ok = true,
            ControlChannelServer.askChosenPayload(index = 1, label = "B"),
        )
        val chosenFrame = JSONObject(chosen)
        assertEquals("ask", chosenFrame.getString("op"))
        assertEquals(1, chosenFrame.getJSONObject("choice").getInt("index"))
        assertFalse("成功帧不该带 error", chosenFrame.has("error"))

        val failed = EnvelopeCodec.encodeControl("a1", "ask", ok = false, askFailure(PromptCodes.BUSY))
        val failedFrame = JSONObject(failed)
        assertEquals(false, failedFrame.getBoolean("ok"))
        assertFalse("失败帧不该带 choice", failedFrame.has("choice"))
        assertEquals("E_ASK_BUSY", failedFrame.getJSONObject("error").getString("code"))

        // 驳回也是成功帧，且形状与"选中"同族（choice.kind 区分）——两个函数都断言，
        // 免得将来有人把驳回改写成失败。
        val rejected = JSONObject(
            EnvelopeCodec.encodeControl("a1", "ask", ok = true, ControlChannelServer.askRejectAllPayload()),
        )
        assertEquals("reject_all", rejected.getJSONObject("choice").getString("kind"))
        assertFalse(rejected.has("error"))

        // 「重新提问」同样是成功帧（否的是问题本身，不是失败）。
        val reasked = JSONObject(
            EnvelopeCodec.encodeControl("a1", "ask", ok = true, ControlChannelServer.askReaskPayload()),
        )
        assertEquals("reask", reasked.getJSONObject("choice").getString("kind"))
        assertFalse(reasked.has("error"))
    }

    private fun askFailure(cause: String): JSONObject = ControlChannelServer.askFailurePayload(cause)
}
