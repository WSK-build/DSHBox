package interlock.relay.core.exec.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可信屏不安全」标记的解除/失败判据矩阵（纯判据收口）：
 *
 * - 建屏自检只有 `selfCheck == "pass"` 且无 violations 的一支才清 unsafe
 * （[ShizukuBackend.selfCheckClearsUnsafe]；`unverified`、没读到、畸形的一律不清）；
 * - release 回执只有服务端可确认的 0/1 才清 unsafe
 * （[ShizukuBackend.releaseClearsUnsafe]；服务端 `TrustedDisplay.release` 收屏失败时
 * **保留引用并回负码**、不再冒充 0——先清账再收顺序已废除，见 RelayShellService）；
 * - 残留屏恢复（收紧）：显式 create 对 unsafe 在场的场景
 * 一律先显式 release，只有 0/1（同 [ShizukuBackend.releaseClearsUnsafe] 一表：
 * 删掉了重复的 clearableReleaseCode 影子判据）才继续新建；新建屏
 * `requireHealthProof=true`——自检 `unverified`/没读到/畸形按**失败**收场
 * （[ShizukuBackend.unverifiedIsFailure]，纯判据 JVM 测试同走）；收不掉保持 unsafe
 * 并按结构化失败回，不进建屏。
 */
class UnsafeRecoveryStrategyTest {

    // ──   第 1 条：只有 pass + 无 violations 清 unsafe ──

    @Test
    fun onlyARealPassWithNoViolationsClearsTheMark() {
        assertTrue(ShizukuBackend.selfCheckClearsUnsafe(ShizukuBackend.SELF_CHECK_PASS, noViolations = true))
        // pass 与 violations 并存（服务端不会写这一组合，判据仍保守）：不清。
        assertFalse(ShizukuBackend.selfCheckClearsUnsafe(ShizukuBackend.SELF_CHECK_PASS, noViolations = false))
    }

    @Test
    fun unverifiedNeverClearsTheMark() {
        assertFalse(ShizukuBackend.selfCheckClearsUnsafe(ShizukuBackend.SELF_CHECK_UNVERIFIED, noViolations = true))
        assertFalse(ShizukuBackend.selfCheckClearsUnsafe(ShizukuBackend.SELF_CHECK_UNVERIFIED, noViolations = false))
    }

    @Test
    fun unsafeLevelNeverClearsTheMark() {
        assertFalse(ShizukuBackend.selfCheckClearsUnsafe(ShizukuBackend.SELF_CHECK_UNSAFE, noViolations = true))
        assertFalse(ShizukuBackend.selfCheckClearsUnsafe(ShizukuBackend.SELF_CHECK_UNSAFE, noViolations = false))
    }

    /** `selfCheck` 缺失或畸形（宿主没解析出/回包不是 JSON）：一律按 unverified 口径，不清。 */
    @Test
    fun aMissingOrMalformedSelfCheckFieldNeverClearsTheMark() {
        assertFalse(ShizukuBackend.selfCheckClearsUnsafe(null, noViolations = true))
        listOf("", "PASS", "Pass", " pass", "trusted", "ok").forEach { level ->
            assertFalse("'$level' 不是 pass 字面量，不清", ShizukuBackend.selfCheckClearsUnsafe(level, noViolations = true))
        }
    }

    // ──   第 4 条：release 回执解除判据（disconnect 同表）──

    /** 0（本无屏）与 1（真收掉）解除；负值与本地放弃（占位码）不解除。 */
    @Test
    fun releaseClearsUnsafeOnServerConfirmedCodesOnly() {
        assertTrue(ShizukuBackend.releaseClearsUnsafe(0))
        assertTrue(ShizukuBackend.releaseClearsUnsafe(1))
        assertFalse("收屏失败（负）：屏可能还在", ShizukuBackend.releaseClearsUnsafe(-1))
        assertFalse("本地放弃等待：结局未知", ShizukuBackend.releaseClearsUnsafe(ShizukuBackend.RELAY_CODE_GAVE_UP))
        // 其余负码（服务端内部异常 -2、服务拒收 -1 之外的占位）同样不解除：负值一概保留。
        assertFalse(ShizukuBackend.releaseClearsUnsafe(-2))
        assertFalse(ShizukuBackend.releaseClearsUnsafe(-99))
    }

    /**
     * 断言间隔：0/1 之外的**任何**整数都按"收不掉"保留 unsafe——只有服务端明确答话
     * 且语义是"没有屏/真收掉"才能清，判据不得写成范围外放行。
     */
    @Test
    fun anyNonPositiveRepliedCodeKeepsTheMark() {
        listOf(-3, -50, Int.MIN_VALUE, ShizukuBackend.RELAY_CODE_GAVE_UP).forEach { code ->
            assertFalse("$code 不解除", ShizukuBackend.releaseClearsUnsafe(code))
        }
        listOf(0, 1, 2, 1024, Int.MAX_VALUE).forEach { code ->
            assertTrue("$code 解除", ShizukuBackend.releaseClearsUnsafe(code))
        }
    }

    // ── unverifiedIsFailure 判据矩阵 ──

    /** 恢复路径（requireHealthProof=true）：unverified/没读到/畸形一律失败，violations 非空更是失败。 */
    @Test
    fun theRecoveryPathFailsClosedOnAnyUnverifiedOutcome() {
        listOf(true, false).forEach { unverified ->
            listOf(emptyList(), listOf("TOP_TASK_CHANGED"), listOf("RESAMPLE_UNVERIFIED")).forEach { violations ->
                assertTrue(
                    "恢复路径 unverified=$unverified violations=$violations 必须按失败收场",
                    ShizukuBackend.unverifiedIsFailure(requireHealthProof = true, unverified = unverified, violations = violations),
                )
            }
        }
    }

    /** 普通 create（requireHealthProof=false）：无 violations 的 unverified 保持放行，语义不变。 */
    @Test
    fun aPlainCreateStaysPermissiveOnPureUnverified() {
        assertFalse(
            ShizukuBackend.unverifiedIsFailure(requireHealthProof = false, unverified = true, violations = emptyList()),
        )
    }

    /** 普通 create：violations 非空（含 RESAMPLE_UNVERIFIED）与 selfCheck=unsafe 仍然是失败。 */
    @Test
    fun aPlainCreateStillFailsOnViolationsOrUnsafe() {
        assertTrue(
            ShizukuBackend.unverifiedIsFailure(requireHealthProof = false, unverified = true, violations = listOf("LAUNCHER_MOVED_TO_VIRTUAL")),
        )
        assertTrue(
            ShizukuBackend.unverifiedIsFailure(requireHealthProof = false, unverified = true, violations = listOf("RESAMPLE_UNVERIFIED")),
        )
        assertTrue(
            "unverified=false 即服务端写 unsafe：失败",
            ShizukuBackend.unverifiedIsFailure(requireHealthProof = false, unverified = false, violations = emptyList()),
        )
    }

    // ── 常量即协议：三档 selfCheck 字面量与占位标记 ──

    @Test
    fun theSelfCheckLevelLiteralsArePartOfTheProtocol() {
        // 服务端 selfCheckJson 与宿主 selfCheckAfterCreate 的两处字面量必须一一相同；
        // 出现第二套拼法时两边口径分叉。
        assertEquals("pass", ShizukuBackend.SELF_CHECK_PASS)
        assertEquals("unsafe", ShizukuBackend.SELF_CHECK_UNSAFE)
        assertEquals("unverified", ShizukuBackend.SELF_CHECK_UNVERIFIED)
        assertTrue(ShizukuBackend.UNVERIFIED_MARK.isNotBlank())
    }

    // ── 顺带：unsafe 失败 data 的机器判读位（回归锚定）──

    @Test
    fun theUnsafeFailureDataCarriesDisplayAndViolations() {
        val data = ShizukuBackend.unsafeFailureData(3, listOf("TOP_TASK_CHANGED"))
        assertTrue(data.getBoolean("selfCheckFailed"))
        assertEquals(3, data.getInt("display"))
        assertEquals("TOP_TASK_CHANGED", data.getJSONArray("violations").get(0))
    }

    @Test
    fun anEmptyViolationListStillCarriesTheArrayKey() {
        val data = ShizukuBackend.unsafeFailureData(1, emptyList())
        assertEquals(0, data.getJSONArray("violations").length())
    }
}
