package interlock.relay.core.runtime

import android.content.Context
import android.content.ContextWrapper
import interlock.relay.core.exec.BackendCall
import interlock.relay.core.exec.BackendResult
import interlock.relay.core.exec.RelayBackend
import interlock.relay.core.interlock.ApprovalPrompt
import interlock.relay.core.interlock.InterlockBroker
import interlock.relay.core.interlock.InterlockChoice
import interlock.relay.core.interlock.InterlockGate
import interlock.relay.core.interlock.InterlockPresenter
import interlock.relay.core.interlock.SystemStateProbe
import interlock.relay.core.interlock.TierStore
import interlock.relay.core.log.AuditLog
import interlock.relay.core.log.RunLog
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.BackendId
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.ProbeResult
import interlock.relay.core.protocol.RelayRequest
import interlock.relay.core.protocol.RelayResponse
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.spi.RelayCapabilities
import interlock.relay.core.spi.RelayPrefs
import interlock.relay.core.spi.RelayText
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.surface.BackendDispatcher
import interlock.relay.core.surface.SurfacePolicy
import interlock.relay.core.transport.HandlerResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 调用主链路级的宿主带回前台验证：`app.launch` 且参数只有宿主包名这一形状，
 * 在三档档位下都走前台分派、不进审批、不落授权账。
 *
 * 只测闸门或形状纯函数证明不了主链路——禁止档的 fail-fast（[RelayCoordinator.earlyVerdict]）
 * 排在闸门之前，那里的豁免缺了，用户拨禁止后任务链照样收不了尾。本测试走 handle 全程。
 */
class RelayCoordinatorHostBringToFrontTest {

    private class MemoryPrefs : RelayPrefs {
        private val strings = HashMap<String, String>()
        override fun getString(file: String, key: String, defValue: String?): String? =
            strings[key] ?: defValue
        override fun putString(file: String, key: String, value: String) { strings[key] = value }
        override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean = defValue
        override fun putBoolean(file: String, key: String, value: Boolean) {}
    }

    private class RecordingPresenter : InterlockPresenter {
        val prompts = mutableListOf<ApprovalPrompt>()
        override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
            prompts += prompt
            return InterlockChoice.DENY
        }
    }

    /** 记下收到的调用与执行面；anything else 不该落到这里。 */
    private class RecordingBackend : RelayBackend {
        val calls = mutableListOf<BackendCall>()
        override suspend fun execute(call: BackendCall): BackendResult {
            calls += call
            return BackendResult.Ok()
        }

        override fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean = true
        override fun available(): Boolean = true
    }

    private val self = "com.example.host"

    private class HostContext : ContextWrapper(null) {
        private val app = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.example.host"
        }
        override fun getApplicationContext(): Context = app
        override fun getPackageName(): String = "com.example.host"
    }

    private fun fixture(
        tier: AccessTier,
        trustedDenied: Boolean = false,
    ): Triple<RelayCoordinator, RecordingBackend, RecordingPresenter> {
        val backend = RecordingBackend()
        val presenter = RecordingPresenter()
        val dir = Files.createTempDirectory("host-bring-to-front").toFile()
        val runLog = RunLog(dir)
        val prefs = MemoryPrefs()
        val tiers = TierStore(prefs)
        tiers.setTier(CapabilityId.APP_LAUNCH, tier)
        val coordinator = RelayCoordinator(
            context = HostContext(),
            gatekeeper = InterlockGate(
                tiers = tiers,
                broker = InterlockBroker(presenter, runLog),
                runLog = runLog,
            ),
            policy = SurfacePolicy(
                preference = { interlock.relay.core.surface.SurfacePreference.FOREGROUND },
                shellAvailable = { true },
                sdkInt = 33,
            ),
            dispatcher = BackendDispatcher(
                BackendId.entries.associateWith { backend },
            ),
            capabilities = RelayCapabilities.BuiltIn,
            text = object : RelayText {
                override fun string(resId: Int): String = "text"
                override fun languageTags(): String? = null
            },
            probe = SystemStateProbe(
                HostContext(),
                available = { true },
                grantedVerified = { true },
                trustedDisplayAvailable = { true },
                userBound = { true },
                sdkInt = 33,
            ),
            paths = RelayPaths(dir),
            runLog = runLog,
            auditLog = AuditLog(Files.createTempDirectory("host-bring-to-front-audit").toFile(), "salt"),
            trustedDisplayDenied = { trustedDenied },
        )
        return Triple(coordinator, backend, presenter)
    }

    private fun request() = RelayRequest(
        id = "req-bring-to-front",
        sentAtMs = 0L,
        capability = CapabilityId.APP_LAUNCH.wire,
        args = JSONObject().put("package", self),
        timeoutMs = RelayRequest.DEFAULT_TIMEOUT_MS,
    )

    @Test
    fun `禁止档下带回前台仍走前台分派且不进审批`() = runBlocking {
        val (coordinator, backend, presenter) = fixture(AccessTier.DENIED)
        val outcome = coordinator.handle(request(), 60_000)
        val response = (outcome as HandlerResult.Done).response
        assertNull("禁止档不得拦下带回前台形状", response.error)
        assertTrue(response.ok)
        assertEquals(SurfaceKind.FOREGROUND, backend.calls.single().surface)
        assertEquals(0, presenter.prompts.size)
    }

    @Test
    fun `询问审批档下带回前台不弹确认直接派发`() = runBlocking {
        val (coordinator, backend, presenter) = fixture(AccessTier.ASK)
        val outcome = coordinator.handle(request(), 60_000)
        val response = (outcome as HandlerResult.Done).response
        assertNull(response.error)
        assertEquals(SurfaceKind.FOREGROUND, backend.calls.single().surface)
        assertEquals(0, presenter.prompts.size)
    }

    @Test
    fun `完全访问档下带回前台照常派发`() = runBlocking {
        val (coordinator, backend, presenter) = fixture(AccessTier.ALWAYS)
        val outcome = coordinator.handle(request(), 60_000)
        val response = (outcome as HandlerResult.Done).response
        assertNull(response.error)
        assertEquals(SurfaceKind.FOREGROUND, backend.calls.single().surface)
        assertEquals(0, presenter.prompts.size)
    }

    /** 禁止档对普通形状依旧 fail-fast：豁免只属于带回前台这一形状，不是整条能力。 */
    @Test
    fun `禁止档下普通启动目标仍被禁止档拦下`() = runBlocking {
        val (coordinator, backend, presenter) = fixture(AccessTier.DENIED)
        val ordinary = RelayRequest(
            id = "req-ordinary",
            sentAtMs = 0L,
            capability = CapabilityId.APP_LAUNCH.wire,
            args = JSONObject().put("package", "com.other.app"),
            timeoutMs = RelayRequest.DEFAULT_TIMEOUT_MS,
        )
        val outcome = coordinator.handle(ordinary, 60_000)
        val response = (outcome as HandlerResult.Done).response
        assertEquals(RelayError.GATE_HOST_DENIED, response.error)
        assertEquals(0, backend.calls.size)
        assertEquals(0, presenter.prompts.size)
    }

    /** 机器侧的 trusted 拒绝标记也不拦带回前台：fail-fast 豁免覆盖两道早拒。 */
    @Test
    fun `机器拒绝标记下带回前台仍走前台分派`() = runBlocking {
        val (coordinator, backend, presenter) = fixture(AccessTier.DENIED, trustedDenied = true)
        val outcome = coordinator.handle(request(), 60_000)
        val response = (outcome as HandlerResult.Done).response
        assertNull("trusted 拒绝不得拦下带回前台形状", response.error)
        assertTrue(response.ok)
        assertEquals(SurfaceKind.FOREGROUND, backend.calls.single().surface)
        assertEquals(0, presenter.prompts.size)
    }

    /**
     * 参数里出现宿主包名（形状超出带回前台豁免）时，自身目标守卫在三档下都拒绝：
     * 宿主自保护不随档位变化，这与"带回前台豁免只认唯一形状"互为边界。
     */
    @Test
    fun `宿主包名参数在三档下都被自身目标守卫拒绝`() = runBlocking {
        for (tier in AccessTier.entries) {
            val (coordinator, backend, presenter) = fixture(tier)
            val selfTargeted = RelayRequest(
                id = "req-self-$tier",
                sentAtMs = 0L,
                capability = CapabilityId.APP_LAUNCH.wire,
                // 两个参数：超出"仅宿主包名"的带回前台形状，落回自身目标守卫。
                args = JSONObject().put("package", self).put("deeplink", "dsh://page"),
                timeoutMs = RelayRequest.DEFAULT_TIMEOUT_MS,
            )
            val outcome = coordinator.handle(selfTargeted, 60_000)
            val response = (outcome as HandlerResult.Done).response
            assertEquals("$tier", RelayError.GATE_HOST_DENIED, response.error)
            assertTrue("$tier", response.reason?.contains("names this app itself") == true)
            assertEquals("$tier 不派发", 0, backend.calls.size)
            assertEquals("$tier 不弹框", 0, presenter.prompts.size)
        }
    }
}
