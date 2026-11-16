package interlock.relay.core.exec.shizuku

import interlock.relay.core.exec.BackendResult
import interlock.relay.core.protocol.RelayError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * release 回包三档映射的**生产出口**断言：判据直接落在生产唯一映射
 * [ShizukuBackend.releaseOutcome]（internal，生产 [ShizukuBackend.displayAction] 的
 * release 分支两路都实走它）上，测试不靠自造 JSONObject——对四类场景把生产的
 * data（错误码/或成功 data）逐字段断出来。
 *
 * - 服务端回执负值（收屏失败）：`BackendResult.Failed`，错误码 [RelayError.BACKEND_UNAVAILABLE]，
 * data `inputStateRestored` 是**布尔** false（可确认"未恢复"）；
 * - 本地放弃等待（[ShizukuBackend.RELAY_CODE_GAVE_UP] 占位码）：`BackendResult.Failed`，
 * 错误码 [RelayError.TRANSPORT_TIMEOUT]（放弃原因原样在 reason），data 只带
 * 字符串 "unknown"——**不是成功**，回包不写 "released" 也不写"已释放"note；
 * - 服务端回执 0（本无屏）与 1（真收掉）：`BackendResult.Ok`，data 带
 * `released`（0→false、1→true）、字符串 "unknown" 与按回执分档的 note
 * （0 档的 note 不写"已释放"——无事可做）。
 * - 四档都没有 true 这一档：没有出口宣称"已恢复"。
 */
class ReleaseInputStateFieldTest {

    /** 成功档（服务端答话）：released 布尔真、unknown 字符串、note 是"已释放，手势未证实"。 */
    @Test
    fun acknowledgedOneIsFailureFreeWithUnknownAndTheReleasedNote() {
        val outcome = ShizukuBackend.releaseOutcome(1, "display already exists")
        val failed = outcome as BackendResult.Ok
        assertTrue(failed.data.getBoolean("released"))
        // 类型档：unknown 字符串档 getBoolean 抛，布尔 false 档 getBoolean 成立。
        assertEquals(ShizukuBackend.INPUT_STATE_UNKNOWN, failed.data.getString("inputStateRestored"))
        assertEquals(
            ShizukuBackend.RELEASE_NOTE_RELEASED,
            failed.data.getString("note"),
        )
        assertTrue(failed.data.getString("note").contains("cannot be confirmed"))
        assertTrue(failed.data.getString("note").contains("reboot the device once"))
    }

    /** 0（本无屏）档：released=false；note **不写"已释放"**，只写没有屏。 */
    @Test
    fun acknowledgedZeroKeepsReleasedFalseAndTheNoDisplayNote() {
        val outcome = ShizukuBackend.releaseOutcome(0, "no display")
        val failed = outcome as BackendResult.Ok
        assertFalse(failed.data.getBoolean("released"))
        assertEquals(ShizukuBackend.INPUT_STATE_UNKNOWN, failed.data.getString("inputStateRestored"))
        assertEquals(ShizukuBackend.RELEASE_NOTE_NOT_PRESENT, failed.data.getString("note"))
        // note 不写"已释放"（没有屏可收，无事可做）。
        assertFalse(failed.data.getString("note").contains("the trusted display is released"))
    }

    /** 失败档：BACKEND_UNAVAILABLE；data 布尔 false；reason 原样（不重写）。 */
    @Test
    fun failedBackendsReturnBooleanFalseLockedType() {
        val outcome = ShizukuBackend.releaseOutcome(-1, "release failed: ISE")
        val failed = outcome as BackendResult.Failed
        assertEquals(RelayError.BACKEND_UNAVAILABLE, failed.error)
        assertEquals("release failed: ISE", failed.reason)
        assertFalse(failed.data.getBoolean("inputStateRestored"))
        // 失败档 data 只有这一枚字段：不多不少（released 不落，无 note）。
        assertFalse(failed.data.has("released"))
        assertFalse(failed.data.has("note"))
    }

    /** GaveUp 占位码：失败/TRANSPORT_TIMEOUT、reason 保留、data unknown，且非 Ok。 */
    @Test
    fun gaveUpIsAFailureThatKeepsTheGiveUpReasonAndTheUnknownField() {
        val giveUpReason =
            "local wait for the display release transaction gave up after 500ms; the " +
                "transaction was not cancelled and its outcome is unknown"
        val outcome = ShizukuBackend.releaseOutcome(ShizukuBackend.RELAY_CODE_GAVE_UP, giveUpReason)
        val failed = outcome as BackendResult.Failed
        // 不是成功：Ok 会沿 release 字段打出"已释放"，恰恰是这里的错误读——
        // 回包要原样带"放弃"原因。
        assertEquals(RelayError.TRANSPORT_TIMEOUT, failed.error)
        assertEquals(giveUpReason, failed.reason)
        assertEquals(ShizukuBackend.INPUT_STATE_UNKNOWN, failed.data.getString("inputStateRestored"))
        // data 只有 unknown：不带 released，不带 note（不含"已释放"字样）。
        assertFalse(failed.data.has("released"))
        assertFalse(failed.data.has("note"))
    }

    /** 生产判据（releaseInputState）穷举四路：-x→RESTORED_FALSE、GAVE_UP→GAVE_UP、0/1→ACKNOWLEDGED。 */
    @Test
    fun judgementTableCoversAllThreeSentiments() {
        assertEquals(
            ShizukuBackend.ReleaseInputState.RESTORED_FALSE,
            ShizukuBackend.releaseInputState(-1),
        )
        assertEquals(
            ShizukuBackend.ReleaseInputState.RESTORED_FALSE,
            ShizukuBackend.releaseInputState(-7),
        )
        assertEquals(
            ShizukuBackend.ReleaseInputState.UNCONFIRMED_GAVE_UP,
            ShizukuBackend.releaseInputState(ShizukuBackend.RELAY_CODE_GAVE_UP),
        )
        assertEquals(
            ShizukuBackend.ReleaseInputState.UNCONFIRMED_ACKNOWLEDGED,
            ShizukuBackend.releaseInputState(0),
        )
        assertEquals(
            ShizukuBackend.ReleaseInputState.UNCONFIRMED_ACKNOWLEDGED,
            ShizukuBackend.releaseInputState(1),
        )
    }

    /** 判据值域只有三档（没有 true 档）；unknown 字面量与字面量不相混。 */
    @Test
    fun noPathClaimsTrue() {
        val values = ShizukuBackend.ReleaseInputState.values().toSet()
        assertEquals(
            setOf(
                ShizukuBackend.ReleaseInputState.RESTORED_FALSE,
                ShizukuBackend.ReleaseInputState.UNCONFIRMED_ACKNOWLEDGED,
                ShizukuBackend.ReleaseInputState.UNCONFIRMED_GAVE_UP,
            ),
            values,
        )
        assertTrue(ShizukuBackend.INPUT_STATE_UNKNOWN == "unknown")
    }

    /**
     * 无安全尺寸失败回包 data 的**生产构造**（[ShizukuBackend.noneSafeData]，生产
     * create 的 NONE_SAFE 出口用它写 data）：三枚键的**字段名与值**都断出来，
     * 不用字符串自证。
     */
    @Test
    fun noneSafeDataCarriesTheThreeStructuredFields() {
        val data = ShizukuBackend.noneSafeData(physicalShortSide = 2561, physicalLongSide = 3000)
        assertEquals(ShizukuBackend.SIZE_POLICY_NONE_SAFE, data.getString("sizePolicy"))
        assertEquals(2561, data.getInt("physicalShortSide"))
        assertEquals(3000, data.getInt("physicalLongSide"))
        // 只有这三枚：机器判读不需要第四个人读字段（reason 由回包单独带）。
        assertNull(data.optString("physicalWidth").takeIf { it.isNotEmpty() })
        assertNull(data.optString("physicalHeight").takeIf { it.isNotEmpty() })
        assertEquals(3, data.length())
    }
}
