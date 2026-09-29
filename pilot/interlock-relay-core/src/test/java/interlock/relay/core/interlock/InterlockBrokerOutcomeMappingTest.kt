package interlock.relay.core.interlock

import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.RelayExitCode
import interlock.relay.core.log.RunLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 确认框结论到错误码的映射。四种失败压成同一条，助手就分不出「用户答了不」
 * 「没人答」与「框根本没弹出来」，而这三者的后续动作完全不同。
 */
class InterlockBrokerOutcomeMappingTest {

    /**
     * 这里只覆盖映射：[InterlockBroker.errorFor] 不写日志。预算与确认窗那一组用例
     * 走 [InterlockBroker.request]，那条路径每条出口都要记一行运行日志，而日志时间戳
     * 取的是 android.os.SystemClock —— 本模块的 JVM 单测环境里它没被 mock。
     */
    private val broker = InterlockBroker(
        presenter = object : InterlockPresenter {
            override suspend fun present(prompt: ApprovalPrompt): InterlockChoice = InterlockChoice.DENY
        },
        runLog = RunLog(Files.createTempDirectory("relay-approval-test").toFile()),
    )

    @Test
    fun eachFailureOutcomeHasItsOwnErrorCode() {
        assertEquals(RelayError.GATE_HOST_DENIED, broker.errorFor(ApprovalOutcome.Denied))
        assertEquals(RelayError.GATE_APPROVAL_TIMEOUT, broker.errorFor(ApprovalOutcome.TimedOut))
        assertEquals(RelayError.GATE_NO_FOREGROUND, broker.errorFor(ApprovalOutcome.CannotPresent))
    }

    /** 「框还挂在屏幕上、只是这次预算用完了」既不是超时也不是拒绝：它必须可重试。 */
    @Test
    fun awaitingConsentIsDistinctFromTimeoutAndRetryable() {
        val error = broker.errorFor(ApprovalOutcome.AwaitingConsent)!!
        assertEquals(RelayError.GATE_AWAITING_CONSENT, error)
        assertTrue(error.retryable)
        assertEquals(RelayExitCode.RETRY_LATER, error.exitCode)
    }

    /** 「等待队列也满了」是第三种等待：位置没排上，重试接不上任何一张框，只能退避。 */
    @Test
    fun waitingTurnIsItsOwnRetryableOutcome() {
        val error = broker.errorFor(ApprovalOutcome.WaitingTurn)!!
        assertEquals(RelayError.GATE_WAITING_TURN, error)
        assertTrue(error.retryable)
        assertEquals(RelayExitCode.RETRY_LATER, error.exitCode)
    }

    @Test
    fun grantedOutcomesCarryNoError() {
        assertNull(broker.errorFor(ApprovalOutcome.AllowedOnce))
        assertNull(broker.errorFor(ApprovalOutcome.AllowedForSession))
    }
}
