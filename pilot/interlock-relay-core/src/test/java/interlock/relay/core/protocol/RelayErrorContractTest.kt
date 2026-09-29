package interlock.relay.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误码到退出码的那张表是助手脚本的分支依据，界面文案也从同一处取。
 * 一格填错就把「用户答了不」说成「你脚本写错了」——两条后续动作完全相反，
 * 而编译与运行时都不会提醒。
 */
class RelayErrorContractTest {

    @Test
    fun everyCodeLandsOnTheDocumentedExitClass() {
        val expected = mapOf(
            // 用法类（改脚本）
            RelayError.CAPABILITY_NOT_IMPLEMENTED to RelayExitCode.USAGE,
            RelayError.REQUEST_TOO_LARGE to RelayExitCode.USAGE,
            RelayError.TRANSPORT_MALFORMED to RelayExitCode.USAGE,
            RelayError.NO_EDITABLE_TARGET to RelayExitCode.USAGE,
            RelayError.NODE_NOT_FOUND to RelayExitCode.USAGE,
            RelayError.NODE_AMBIGUOUS to RelayExitCode.USAGE,
            RelayError.NODE_NOT_ACTIONABLE to RelayExitCode.USAGE,
            // 拒绝类（重试也会被再次挡下）
            RelayError.GATE_HOST_DENIED to RelayExitCode.DENIED,
            RelayError.SURFACE_SECURE_WINDOW to RelayExitCode.DENIED,
            // 超时类
            RelayError.GATE_APPROVAL_TIMEOUT to RelayExitCode.TIMEOUT,
            RelayError.TRANSPORT_TIMEOUT to RelayExitCode.TIMEOUT,
            RelayError.WAIT_TIMEOUT to RelayExitCode.TIMEOUT,
            // 要用户去手机上动一下
            RelayError.GATE_SYSTEM_MISSING to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.GATE_NO_FOREGROUND to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.CLIPBOARD_NO_FOCUS to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.CAPABILITY_UNAVAILABLE_ON_DEVICE to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.SURFACE_API_LEVEL to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.SURFACE_NO_SHELL to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.SURFACE_TRUSTED_DENIED to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.SURFACE_UNAVAILABLE to RelayExitCode.NEEDS_USER_ACTION,
            // 单一执行面能力被偏好卡死：要用户去切偏好/建屏，不是改脚本也不是重试。
            RelayError.SURFACE_MODE_REQUIRED to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.SURFACE_SYSTEM_CONSENT_REQUIRED to RelayExitCode.NEEDS_USER_ACTION,
            RelayError.NODE_SYSTEM_CONSENT_TARGET to RelayExitCode.NEEDS_USER_ACTION,
            // 瞬时可重试
            RelayError.GATE_AWAITING_CONSENT to RelayExitCode.RETRY_LATER,
            // 等待队列满：位置没排上，退避一阵后位置必然腾出。
            RelayError.GATE_WAITING_TURN to RelayExitCode.RETRY_LATER,
            // 等待用户答复期间被取消：可证未执行、没有副作用；原请求已终结，重发是新的一件事。
            RelayError.GATE_CANCELLED to RelayExitCode.RETRY_LATER,
            RelayError.GATE_SUSPENDED_BY_HOST to RelayExitCode.RETRY_LATER,
            RelayError.BACKEND_SHIZUKU_DEAD to RelayExitCode.RETRY_LATER,
            RelayError.BACKEND_UNAVAILABLE to RelayExitCode.RETRY_LATER,
            RelayError.NODE_ACTION_REJECTED to RelayExitCode.RETRY_LATER,
            RelayError.LAUNCH_NOT_LANDED to RelayExitCode.RETRY_LATER,
            RelayError.STORAGE_FULL to RelayExitCode.RETRY_LATER,
            RelayError.RATE_LIMITED to RelayExitCode.RETRY_LATER,
            // 队列满与限流同属「退避后再来」：满队退避一段时间后位置必然腾出。
            RelayError.QUEUE_FULL to RelayExitCode.RETRY_LATER,
            // 宿主侧故障
            // 目标判错：这颗节点根本没有输入法动作可触发（改脚本，不是去授予什么）
            RelayError.NODE_NO_IME_ACTION to RelayExitCode.USAGE,
            RelayError.INTERNAL to RelayExitCode.INTERNAL,
        )
        assertEquals("这张表必须覆盖整条枚举", RelayError.entries.size, expected.size)
        expected.forEach { (error, exitCode) -> assertEquals(error.code, exitCode, error.exitCode) }
    }

    @Test
    fun exitCodesOnlyEverComeFromTheDocumentedSet() {
        val documented = setOf(
            RelayExitCode.USAGE,
            RelayExitCode.DENIED,
            RelayExitCode.TIMEOUT,
            RelayExitCode.NEEDS_USER_ACTION,
            RelayExitCode.RETRY_LATER,
            RelayExitCode.INTERNAL,
        )
        RelayError.entries.forEach { assertTrue(it.code, it.exitCode in documented) }
    }

    /** 助手脚本按退出码分支：某一档若没有任何错误码可达，这条约定就不成立。 */
    @Test
    fun everyNonZeroExitClassIsActuallyReachable() {
        val produced = RelayError.entries.map { it.exitCode }.toSet()
        setOf(
            RelayExitCode.USAGE,
            RelayExitCode.DENIED,
            RelayExitCode.TIMEOUT,
            RelayExitCode.NEEDS_USER_ACTION,
            RelayExitCode.RETRY_LATER,
            RelayExitCode.INTERNAL,
        ).forEach { assertTrue("exit $it has no error code", it in produced) }
    }

    @Test
    fun retryableFlagIsPinnedPerCode() {
        val retryable = RelayError.entries.filter { it.retryable }.map { it.code }.toSet()
        assertEquals(
            setOf(
                "E_AWAITING_CONSENT",
                "E_GATE_WAITING_TURN",
                "E_TASK_SUSPENDED_BY_HOST",
                "E_GATE_NO_FOREGROUND",
                "E_CLIPBOARD_NO_FOCUS",
                "E_SURFACE_NO_SHELL",
                "E_LAUNCH_NOT_LANDED",
                "E_SURFACE_SYSTEM_CONSENT_REQUIRED",
                "E_BACKEND_SHIZUKU_DEAD",
                "E_BACKEND_UNAVAILABLE",
                "E_STORAGE_FULL",
                "E_RATE_LIMITED",
                "E_QUEUE_FULL",
                "E_TRANSPORT_TIMEOUT",
                "E_ACTION_REJECTED",
                "E_WAIT_TIMEOUT",
            ),
            retryable,
        )
    }

    @Test
    fun everyCodeRoundTripsThroughItsWireString() {
        val codes = RelayError.entries.map { it.code }
        assertEquals("错误码字面量不允许重复", codes.size, codes.distinct().size)
        RelayError.entries.forEach { assertSame(it, RelayError.fromCode(it.code)) }
    }

    @Test
    fun unknownCodeStringsResolveToNothingRatherThanToAFallback() {
        assertNull(RelayError.fromCode("E_NO_SUCH_THING"))
        assertNull(RelayError.fromCode(""))
        assertNull(RelayError.fromCode(RelayError.TRANSPORT_MALFORMED.name))
    }
}
