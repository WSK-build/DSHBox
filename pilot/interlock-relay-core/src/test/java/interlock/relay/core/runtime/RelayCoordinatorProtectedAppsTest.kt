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
import interlock.relay.core.interlock.ProtectedAppsStore
import interlock.relay.core.interlock.SystemStateProbe
import interlock.relay.core.interlock.TierStore
import interlock.relay.core.log.AuditLog
import interlock.relay.core.log.RunLog
import interlock.relay.core.protocol.AccessTier
import interlock.relay.core.protocol.BackendId
import interlock.relay.core.protocol.CapabilityId
import interlock.relay.core.protocol.RelayError
import interlock.relay.core.protocol.RelayRequest
import interlock.relay.core.protocol.RelayResponse
import interlock.relay.core.protocol.SurfaceKind
import interlock.relay.core.spi.RelayCapabilities
import interlock.relay.core.spi.RelayPrefs
import interlock.relay.core.spi.RelayText
import interlock.relay.core.storage.RelayPaths
import interlock.relay.core.surface.BackendDispatcher
import interlock.relay.core.surface.SurfacePolicy
import interlock.relay.core.surface.SurfacePreference
import interlock.relay.core.transport.HandlerResult
import interlock.relay.core.transport.ParkedRequest
import interlock.relay.core.transport.ParkedRequestBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 受保护应用在调用主链路上的三处拦截：
 * ①入口层（进审批之前）按参数里的目标包名拒绝，完全访问档不放行；
 * ②批准之后、真正派发之前再查一次名单（审批期间刚加名单的硬边界）；
 * ③挂起等待环每轮复查：等待期间目标包被加入名单，请求按拒绝收场而不是继续等。
 * 全部直调生产协调器与生产名单存储，不用自造 JSON 自证。
 */
class RelayCoordinatorProtectedAppsTest {

    private class MemoryPrefs : RelayPrefs {
        private val strings = HashMap<String, String>()
        override fun getString(file: String, key: String, defValue: String?): String? =
            strings[key] ?: defValue
        override fun putString(file: String, key: String, value: String) { strings[key] = value }
        override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean = defValue
        override fun putBoolean(file: String, key: String, value: Boolean) {}
    }

    /** 剧本式呈现器：按脚本作答；[onPresent] 在作答前执行（供"批准那一刻改名单"用）。 */
    private class RecordingPresenter(
        private val script: List<InterlockChoice> = emptyList(),
        private val fallback: InterlockChoice = InterlockChoice.DENY,
        private val onPresent: () -> Unit = {},
    ) : InterlockPresenter {
        val prompts = mutableListOf<ApprovalPrompt>()
        private val answers = ArrayDeque(script)

        override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
            prompts += prompt
            onPresent()
            return answers.removeFirstOrNull() ?: fallback
        }
    }

    private class RecordingParkBridge : ParkedRequestBridge {
        val parked = mutableListOf<ParkedRequest>()
        val resumed = mutableListOf<String>()
        override fun hasParkCapacity(): Boolean = true
        override fun register(park: ParkedRequest): Boolean {
            parked.add(park)
            return true
        }
        override fun resumeParked(requestId: String) { resumed.add(requestId) }
    }

    private class ScriptableBackend : RelayBackend {
        val calls = mutableListOf<BackendCall>()
        override suspend fun execute(call: BackendCall): BackendResult =
            BackendResult.Ok(data = JSONObject().put("verified", true)).also { calls += call }

        override fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean =
            surface != SurfaceKind.BACKEND_TRUSTED

        override fun available(): Boolean = true
    }

    private class HostContext : ContextWrapper(null) {
        private val app = object : ContextWrapper(null) {
            override fun getPackageName(): String = "com.example.host"
        }
        override fun getApplicationContext(): Context = app
        override fun getPackageName(): String = "com.example.host"
    }

    private class Fixture(
        tiers: Map<CapabilityId, AccessTier> = emptyMap(),
        protectedList: Set<String> = emptySet(),
        presenter: InterlockPresenter,
        parkBridge: ParkedRequestBridge? = null,
        parkScope: (() -> CoroutineScope?)? = null,
    ) {
        val backend = ScriptableBackend()
        private val prefs = MemoryPrefs()
        val store = ProtectedAppsStore(prefs).also { if (protectedList.isNotEmpty()) it.set(protectedList) }

        val coordinator: RelayCoordinator = RelayCoordinator(
            context = HostContext(),
            gatekeeper = InterlockGate(
                tiers = TierStore(prefs).apply { tiers.forEach { (id, tier) -> setTier(id, tier) } },
                broker = InterlockBroker(presenter, RunLog(Files.createTempDirectory("n5-broker").toFile())),
                runLog = RunLog(Files.createTempDirectory("n5-gate").toFile()),
            ),
            policy = SurfacePolicy(
                preference = { SurfacePreference.FOREGROUND },
                shellAvailable = { true },
                backendCanServe = { _, _ -> true },
                sdkInt = 33,
            ),
            dispatcher = BackendDispatcher(BackendId.entries.associateWith { backend }),
            capabilities = RelayCapabilities.BuiltIn,
            text = object : RelayText {
                override fun string(resId: Int): String = "text"
                override fun languageTags(): String? = null
            },
            probe = SystemStateProbe(
                HostContext(),
                available = { true },
                grantedVerified = { true },
                trustedDisplayAvailable = { false },
                userBound = { true },
                sdkInt = 33,
            ),
            landingTable = null,
            surfacePreference = { SurfacePreference.FOREGROUND },
            parkBridge = parkBridge,
            parkScope = parkScope,
            paths = RelayPaths(Files.createTempDirectory("n5-paths").toFile()),
            runLog = RunLog(Files.createTempDirectory("n5-run").toFile()),
            auditLog = AuditLog(Files.createTempDirectory("n5-audit").toFile(), "salt"),
            protectedApps = store,
        )

        suspend fun handle(
            capability: CapabilityId,
            args: JSONObject,
            budgetMs: Long = 60_000,
        ): RelayResponse {
            val outcome = coordinator.handle(
                RelayRequest(
                    id = "n5-${System.nanoTime()}",
                    sentAtMs = 0L,
                    capability = capability.wire,
                    args = args,
                    timeoutMs = RelayRequest.DEFAULT_TIMEOUT_MS,
                ),
                budgetMs,
            )
            return when (outcome) {
                is HandlerResult.Done -> outcome.response
                is HandlerResult.Parked -> throw AssertionError("unexpected park for ${capability.wire}")
            }
        }
    }

    private fun launchArgs(pkg: String) = JSONObject().put("package", pkg)

    private fun appOpsArgs(pkg: String) =
        JSONObject().put("package", pkg).put("op", "CAMERA").put("mode", "allow")

    private fun alwaysTier() = mapOf(
        CapabilityId.APP_LAUNCH to AccessTier.ALWAYS,
        CapabilityId.APP_STOP to AccessTier.ALWAYS,
        CapabilityId.APPOPS_SET to AccessTier.ALWAYS,
        CapabilityId.SYS_SHELL_FULL to AccessTier.ALWAYS,
    )

    // ── ①入口层：进审批之前拒绝 ──

    @Test
    fun `入口层命中在审批前拒绝且不弹卡不派发`() = runBlocking {
        val presenter = RecordingPresenter()
        val f = Fixture(tiers = alwaysTier(), protectedList = setOf("com.bank"), presenter = presenter)
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.bank"))
        assertEquals(RelayError.PACKAGE_PROTECTED, response.error)
        assertEquals("不进审批队列", 0, presenter.prompts.size)
        assertEquals("不派发", 0, f.backend.calls.size)
        // 回包只点该包受保护与尝试的包名，不返回完整名单。
        assertTrue(response.reason!!.contains("com.bank"))
    }

    @Test
    fun `app stop 与 appops 命中同样在审批前拒绝`() = runBlocking {
        val presenter = RecordingPresenter()
        val f = Fixture(tiers = alwaysTier(), protectedList = setOf("com.bank"), presenter = presenter)
        assertEquals(RelayError.PACKAGE_PROTECTED, f.handle(CapabilityId.APP_STOP, launchArgs("com.bank")).error)
        assertEquals(RelayError.PACKAGE_PROTECTED, f.handle(CapabilityId.APPOPS_SET, appOpsArgs("com.bank")).error)
        assertEquals(0, presenter.prompts.size)
        assertEquals(0, f.backend.calls.size)
    }

    @Test
    fun `notify read 带 package 命中名单在审批前拒绝`() = runBlocking {
        val presenter = RecordingPresenter()
        val f = Fixture(
            tiers = mapOf(CapabilityId.NOTIFY_READ to AccessTier.ALWAYS),
            protectedList = setOf("com.bank"),
            presenter = presenter,
        )
        val response = f.handle(CapabilityId.NOTIFY_READ, JSONObject().put("package", "com.bank"))
        assertEquals(RelayError.PACKAGE_PROTECTED, response.error)
        assertEquals("不进审批队列", 0, presenter.prompts.size)
        assertEquals("不派发", 0, f.backend.calls.size)
        assertTrue(response.reason!!.contains("com.bank"))
    }

    @Test
    fun `notify read 不带 package 的读全部不被名单整条拒绝`() = runBlocking {
        val presenter = RecordingPresenter()
        val f = Fixture(
            tiers = mapOf(CapabilityId.NOTIFY_READ to AccessTier.ALWAYS),
            protectedList = setOf("com.bank"),
            presenter = presenter,
        )
        // 名单里有 com.bank，但这条调用没有点名任何包：入口层必须放行（整条拒绝会把
        // 允许读的应用一并挡掉）。这条钉的是**入口判据**：它没有回 E_PACKAGE_PROTECTED
        // ——之后拦下这条调用的是夹具环境里另一个互不相关的门槛（通知使用权未授权，
        // E_GATE_SYSTEM_MISSING），数据面的逐条过滤在 DirectBackend 的纯函数里单测。
        val response = f.handle(CapabilityId.NOTIFY_READ, JSONObject().put("limit", 3))
        assertNotEquals(RelayError.PACKAGE_PROTECTED, response.error)
        assertEquals("没进审批队列（被系统门槛拦在审批之前）", 0, presenter.prompts.size)
    }

    @Test
    fun `完全访问档也不放行`() = runBlocking {
        // tiers 已是 ALWAYS（完全访问）：名单优先级高于档位，仍然拒绝。
        val presenter = RecordingPresenter()
        val f = Fixture(tiers = alwaysTier(), protectedList = setOf("com.bank"), presenter = presenter)
        assertEquals(RelayError.PACKAGE_PROTECTED, f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.bank")).error)
    }

    @Test
    fun `名单外照旧通过`() = runBlocking {
        val presenter = RecordingPresenter()
        val f = Fixture(tiers = alwaysTier(), protectedList = setOf("com.bank"), presenter = presenter)
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.other"))
        assertNull(response.error)
        assertEquals(1, f.backend.calls.size)
        assertEquals(CapabilityId.APP_LAUNCH, f.backend.calls[0].descriptor.id)
    }

    @Test
    fun `名单为空时行为与现在完全一致`() = runBlocking {
        val presenter = RecordingPresenter()
        val f = Fixture(tiers = alwaysTier(), protectedList = emptySet(), presenter = presenter)
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.bank")).error)
        assertEquals(1, f.backend.calls.size)
    }

    @Test
    fun `完全 shell 不解析命令不拦名单`() = runBlocking {
        val presenter = RecordingPresenter()
        val f = Fixture(tiers = alwaysTier(), protectedList = setOf("com.bank"), presenter = presenter)
        val response = f.handle(
            CapabilityId.SYS_SHELL_FULL,
            JSONObject().put("command", "am start -n com.bank/.MainActivity"),
        )
        assertNotEquals(RelayError.PACKAGE_PROTECTED, response.error)
        assertEquals("命令原样下发", 1, f.backend.calls.size)
        assertEquals(CapabilityId.SYS_SHELL_FULL, f.backend.calls[0].descriptor.id)
    }

    @Test
    fun `执行中途把包加进名单的下一次调用立即被拒`() = runBlocking {
        val presenter = RecordingPresenter()
        val f = Fixture(tiers = alwaysTier(), protectedList = emptySet(), presenter = presenter)
        assertNull("名单为空时照旧", f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.bank")).error)
        // 用户此刻把包勾进名单：同一条调用下一次立即被拒，不弹卡、不派发。
        assertTrue(f.store.set(setOf("com.bank")))
        assertEquals(RelayError.PACKAGE_PROTECTED, f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.bank")).error)
        assertEquals("第二次仍不进审批", 0, presenter.prompts.size)
        assertEquals(1, f.backend.calls.size)
    }

    // ── ②批准后、派发前复查 ──

    @Test
    fun `审批通过后包已被加入名单则拒绝且不派发`() = runBlocking {
        // 审批卡被点「允许」的那一刻，用户刚刚把目标包加进了名单：
        // 批准不再携带执行权，派发前的复查把这条调用按受保护拒绝。
        val storeHolder = arrayOfNulls<ProtectedAppsStore>(1)
        val presenter = RecordingPresenter(
            script = listOf(InterlockChoice.ALLOW_ONCE),
            onPresent = { storeHolder[0]?.set(setOf("com.bank")) },
        )
        // 档位拨到「询问审批」：确认框被点「允许」的那一刻 onPresent 把包加进名单。
        val f = Fixture(
            tiers = mapOf(CapabilityId.APP_LAUNCH to AccessTier.ASK),
            protectedList = emptySet(),
            presenter = presenter,
        )
        storeHolder[0] = f.store
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.bank"))
        assertEquals(RelayError.PACKAGE_PROTECTED, response.error)
        assertEquals("批准了也不派发", 0, f.backend.calls.size)
        assertEquals("用户确实被问过一次", 1, presenter.prompts.size)
    }

    @Test
    fun `审批通过且名单未变时照旧派发`() = runBlocking {
        val presenter = RecordingPresenter(script = listOf(InterlockChoice.ALLOW_ONCE))
        val f = Fixture(
            tiers = mapOf(CapabilityId.APP_LAUNCH to AccessTier.ASK),
            protectedList = emptySet(),
            presenter = presenter,
        )
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.bank")).error)
        assertEquals(1, f.backend.calls.size)
    }

    // ── ③挂起等待环：等待期间加名单 ──

    @Test
    fun `挂起等待期间目标包被加入名单则请求按拒绝收场`() = runBlocking {
        val presenter = RecordingPresenter(fallback = InterlockChoice.AWAITING)
        val bridge = RecordingParkBridge()
        val scope = CoroutineScope(Dispatchers.IO)
        val f = Fixture(
            // 「询问审批」才会挂起等人；完全访问档不需要审批，也就没有挂起可言。
            tiers = mapOf(CapabilityId.APP_LAUNCH to AccessTier.ASK),
            protectedList = emptySet(),
            presenter = presenter,
            parkBridge = bridge,
            parkScope = { scope },
        )
        // 预算给足：审批窗走满之后还装得下派发余量，才会走挂起面。
        val outcome = f.coordinator.handle(
            RelayRequest(
                id = "n5-parked",
                sentAtMs = 0L,
                capability = CapabilityId.APP_LAUNCH.wire,
                args = launchArgs("com.bank"),
                timeoutMs = RelayRequest.DEFAULT_TIMEOUT_MS,
            ),
            120_000,
        )
        assertTrue(outcome is HandlerResult.Parked)
        val park = bridge.parked.single()
        assertEquals("n5-parked", park.requestId)
        // 挂起期间用户把目标包加进名单：等待环在下一轮（≤500ms 退避）按拒绝收场。
        assertTrue(f.store.set(setOf("com.bank")))
        val waitedUntil = System.nanoTime() + 10_000_000_000L
        while (bridge.resumed.isEmpty() && System.nanoTime() < waitedUntil) {
            Thread.sleep(100)
        }
        assertEquals("等待环应在名单变更后收场", listOf("n5-parked"), bridge.resumed)
        val response = park.resume()
        assertEquals(RelayError.PACKAGE_PROTECTED, response.error)
        assertEquals("未执行", 0, f.backend.calls.size)
        scope.cancel()
    }
}
