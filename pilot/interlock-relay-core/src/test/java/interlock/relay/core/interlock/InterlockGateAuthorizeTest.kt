package interlock.relay.core.interlock

import interlock.relay.core.log.RunLog
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.CapabilityDescriptor
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.CapabilityRegistry
import interlock.relay.core.protocol.ProbeResult
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SystemGrantState
import interlock.relay.core.spi.RelayPrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 闸门 [InterlockGate.authorize] 的装配级验证：用真实注册表里的描述符（含一条历史
 * 上限为 `ASK_ONLY`、一条为 `SESSION_ONLY` 的能力）配可控的呈现器，证明弹不弹由
 * 用户档位与会话授权决定，描述符上的旧上限元数据不再覆盖任何一格。
 *
 * 纯函数测试（verdict / requiresApproval）证明不了执行链——放行判断若在装配路径
 * 上被别处再压一次，那里才是旧语义的藏身处。本测试走 authorize 全程。
 * 闸门再往前的主链路 fail-fast（含"宿主带回前台"形状豁免）由
 * `RelayCoordinatorHostBringToFrontTest` 走 handle 全程覆盖，两层合起来才是完整的
 * 放行链证据。
 */
class InterlockGateAuthorizeTest {

    /** 内存偏好：档位写读都在这里，不落盘。 */
    private class MemoryPrefs : RelayPrefs {
        private val strings = HashMap<String, String>()
        override fun getString(file: String, key: String, defValue: String?): String? =
            strings[key] ?: defValue
        override fun putString(file: String, key: String, value: String) { strings[key] = value }
        override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean = defValue
        override fun putBoolean(file: String, key: String, value: Boolean) {}
    }

    /**
     * 剧本式呈现器：按脚本逐个交出答复，脚本耗尽后再被问到即失败。
     * [prompts] 记录它实际被问到的每一次，供断言「问都没问」与卡上选项。
     */
    private class ScriptedPresenter : InterlockPresenter {
        val prompts = mutableListOf<ApprovalPrompt>()
        private val choices = ArrayDeque<InterlockChoice>()

        fun script(vararg choice: InterlockChoice) {
            choices.clear()
            choices.addAll(choice)
        }

        override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
            prompts += prompt
            return choices.removeFirstOrNull()
                ?: error("unexpected approval prompt for ${prompt.capability.wire}")
        }
    }

    /** 历史上限各取一条：`ASK_ONLY`（app.stop）与 `SESSION_ONLY`（media.read）。 */
    private val cases = listOf(
        CapabilityRegistry.find(CapabilityId.APP_STOP)!!,
        CapabilityRegistry.find(CapabilityId.MEDIA_READ)!!,
    )

    private class Fixture {
        val tiers = TierStore(MemoryPrefs())
        val presenter = ScriptedPresenter()
        var nowMs = 10_000L
        private val runLog = RunLog(Files.createTempDirectory("gate-authorize").toFile())
        val gate = InterlockGate(
            tiers = tiers,
            broker = InterlockBroker(presenter, runLog),
            runLog = runLog,
            now = { nowMs },
        )

        fun probe() = ProbeResult(SystemGrantState.GRANTED, emptyList())
    }

    private suspend fun Fixture.authorize(
        descriptor: CapabilityDescriptor,
        hostBringToFront: Boolean = false,
    ): GateResult = gate.authorize(
        descriptor = descriptor,
        targetLabel = null,
        paramDetail = null,
        argsIdentity = "identity",
        volatileTarget = false,
        budgetMs = 60_000,
        system = probe(),
        hostBringToFront = hostBringToFront,
    )

    @Test
    fun alwaysTierExecutesSilentlyWhereTheOldCeilingSaidAskOnlyOrSessionOnly() = runBlocking {
        for (descriptor in cases) {
            val fixture = Fixture()
            fixture.tiers.setTier(descriptor.id, AccessTier.ALWAYS)
            val result = fixture.authorize(descriptor)
            assertTrue(descriptor.id.wire, result.allowed)
            assertFalse(descriptor.id.wire, result.requiresApproval)
            assertEquals(descriptor.id.wire, "auto", result.decisionLabel)
            assertEquals(
                "${descriptor.id.wire} 完全访问档不得弹框",
                0,
                fixture.presenter.prompts.size,
            )
        }
    }

    @Test
    fun askTierPromptsEveryCallWhileNoSessionGrantExists() = runBlocking {
        for (descriptor in cases) {
            val fixture = Fixture()
            fixture.tiers.setTier(descriptor.id, AccessTier.ASK)
            fixture.presenter.script(InterlockChoice.DENY)
            val first = fixture.authorize(descriptor)
            assertFalse(descriptor.id.wire, first.allowed)
            assertEquals(descriptor.id.wire, RelayError.GATE_HOST_DENIED, first.error)
            assertTrue(descriptor.id.wire, first.decisionLabel.startsWith("denied_"))
            assertEquals(1, fixture.presenter.prompts.size)
            // 询问审批档的卡上必须带「本会话内不再询问」。
            assertTrue(fixture.presenter.prompts.single().allowsSessionGrant)

            fixture.presenter.script(InterlockChoice.DENY)
            val second = fixture.authorize(descriptor)
            assertFalse(descriptor.id.wire, second.allowed)
            assertEquals(descriptor.id.wire, 2, fixture.presenter.prompts.size)
        }
    }

    /**
     * 「把宿主带回前台」这一形状不走审批：档位为禁止也不进确认框，只换前台、
     * 不落授权账。这是宿主自保护一侧的豁免，与档位无关，收窄即断任务链。
     */
    @Test
    fun hostBringToFrontPassesEveryTierWithoutAsking() = runBlocking {
        for (tier in AccessTier.entries) {
            val fixture = Fixture()
            fixture.tiers.setTier(CapabilityId.APP_LAUNCH, tier)
            val result = fixture.authorize(
                CapabilityRegistry.find(CapabilityId.APP_LAUNCH)!!,
                hostBringToFront = true,
            )
            assertTrue("$tier 必须放行", result.allowed)
            assertFalse("$tier 不走审批", result.requiresApproval)
            assertEquals("$tier", "host_bring_to_front", result.decisionLabel)
            assertEquals("$tier 不得弹框", 0, fixture.presenter.prompts.size)
        }
    }

    @Test
    fun sessionGrantSilencesAskTierAndExpiresAfterThirtyMinutes() = runBlocking {
        for (descriptor in cases) {
            val fixture = Fixture()
            fixture.tiers.setTier(descriptor.id, AccessTier.ASK)
            fixture.presenter.script(InterlockChoice.ALLOW_SESSION)
            val granted = fixture.authorize(descriptor)
            assertTrue(descriptor.id.wire, granted.allowed)
            assertEquals(descriptor.id.wire, "approved_session", granted.decisionLabel)

            // 会话授权在效：同样调用不再弹。
            val silenced = fixture.authorize(descriptor)
            assertTrue(descriptor.id.wire, silenced.allowed)
            assertEquals(descriptor.id.wire, "auto", silenced.decisionLabel)
            assertEquals(descriptor.id.wire, 1, fixture.presenter.prompts.size)

            // 超过 30 分钟窗口：授权失效，回到逐次询问。
            fixture.nowMs += InterlockGate.SESSION_GRANT_MS + 1
            fixture.presenter.script(InterlockChoice.DENY)
            val afterExpiry = fixture.authorize(descriptor)
            assertFalse(descriptor.id.wire, afterExpiry.allowed)
            assertEquals(descriptor.id.wire, 2, fixture.presenter.prompts.size)
        }
    }
}
