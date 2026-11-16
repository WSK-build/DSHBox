package interlock.relay.core.interlock

import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SystemGrantState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三档 × 会话授权 × 系统权限的全量矩阵。矩阵逐格钉死期望值，
 * 任何一格翻转都意味着三档语义变了；禁用档、询问档、完全访问档对全部能力共用
 * 同一套规则，描述符上的上限元数据不出现在任何一格的输入里。
 */
class TierApprovalMatrixTest {

    /** 纯判定 + 审批判定的逐格期望。 */
    @Test
    fun matrixCoversEveryCombinationOfTierSessionAndSystem() {
        val systemStates = listOf(
            SystemGrantState.GRANTED,
            SystemGrantState.RUNTIME_ASKABLE,
            SystemGrantState.MANUAL_ONLY,
        )
        for (tier in AccessTier.entries) {
            for (session in listOf(false, true)) {
                for (system in systemStates) {
                    val gate = InterlockGate.verdict(system, tier)
                    if (InterlockGate.systemBlocks(system)) {
                        // 系统权限缺失先于档位：挡下并给出系统那一侧的错误码。
                        assertNotNull("$system/$tier/$session", gate)
                        assertEquals(RelayError.GATE_SYSTEM_MISSING, gate?.error)
                        continue
                    }
                    if (tier == AccessTier.DENIED) {
                        // 禁止档：系统权限满足也不执行，错误码指向档位本身。
                        assertNotNull("$system/$tier/$session", gate)
                        assertEquals(RelayError.GATE_HOST_DENIED, gate?.error)
                        assertEquals("tier_denied", gate?.decisionLabel)
                        continue
                    }
                    // 可达 + 档位非禁止：纯判定放行，弹不弹只由审批判定回答。
                    assertNull("$system/$tier/$session", gate)
                    val prompts = InterlockGate.requiresApproval(tier, session)
                    val expected = tier == AccessTier.ASK && !session
                    assertEquals("$tier/$session/$system", expected, prompts)
                }
            }
        }
    }

    /**
     * 注册表里每一条能力（含 `sys.shell`）的三档行为都由档位单独决定：
     * 上限元数据留在描述符上但不得改变弹不弹——两条 ALLWAYS 档的断言对所有条目成立。
     */
    @Test
    fun registryCeilingMetadataNeverOverrulesTheUserTier() {
        CapabilityRegistry.allDescriptors.forEach { descriptor ->
            val id = descriptor.id.wire
            assertTrue(
                "$id 完全访问档不得弹框",
                !InterlockGate.requiresApproval(AccessTier.ALWAYS, sessionGranted = false),
            )
            assertTrue(
                "$id 询问审批档必须弹框",
                InterlockGate.requiresApproval(AccessTier.ASK, sessionGranted = false),
            )
            assertTrue(
                "$id 询问审批档在本会话授权后不得弹框",
                !InterlockGate.requiresApproval(AccessTier.ASK, sessionGranted = true),
            )
        }
    }
}
