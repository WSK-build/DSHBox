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
import interlock.relay.core.log.LogEvent
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
import interlock.relay.core.surface.TargetLandingTable
import interlock.relay.core.transport.HandlerResult
import interlock.relay.core.transport.ParkedRequestBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 调用主链路级的目标落点验证：`app.launch` 真的派发到后端并把核验落点写进登记簿，
 * 后续 `ui.*` 真的按目标沿用；后台失败按确定性/暂时性/未知分类，只有确定性失败
 * 允许一次前台兜底，结果未知不重放。只测纯函数证明不了装配 —— 分类若在主链路上
 * 被别处再压一次，那里才是旧语义的藏身处。
 */
class RelayCoordinatorLandingRoutingTest {

    private class MemoryPrefs : RelayPrefs {
        private val strings = HashMap<String, String>()
        override fun getString(file: String, key: String, defValue: String?): String? =
            strings[key] ?: defValue
        override fun putString(file: String, key: String, value: String) { strings[key] = value }
        override fun getBoolean(file: String, key: String, defValue: Boolean): Boolean = defValue
        override fun putBoolean(file: String, key: String, value: Boolean) {}
    }

    private class RecordingPresenter(
        script: List<InterlockChoice> = emptyList(),
    ) : InterlockPresenter {
        val prompts = mutableListOf<ApprovalPrompt>()
        private val answers = ArrayDeque(script)

        override suspend fun present(prompt: ApprovalPrompt): InterlockChoice {
            prompts += prompt
            return answers.removeFirstOrNull() ?: InterlockChoice.DENY
        }
    }

    /**
     * 挂起面假体：登记挂起项，续行体由测试直接调用 `park.resume()` 驱动
     * （续行体级测试，不经桥接触发），因此 [resumeParked] 留空是有意的。
     */
    private class FakeParkBridge : ParkedRequestBridge {
        val registered = mutableListOf<String>()
        override fun hasParkCapacity(): Boolean = true
        override fun register(park: interlock.relay.core.transport.ParkedRequest): Boolean {
            registered.add(park.requestId)
            return true
        }
        override fun resumeParked(requestId: String) = Unit
    }

    /** 剧本式后端：按脚本逐个交出结果；建屏调用顺带把"屏在不在"翻成真 —— 现有建屏逻辑的落点。 */
    private class ScriptableBackend : RelayBackend {
        var displayAlive = false
        val calls = mutableListOf<BackendCall>()
        private val script = ArrayDeque<BackendResult>()

        fun stage(vararg results: BackendResult) {
            script.addAll(results)
        }

        fun ok(vararg pairs: Pair<String, Any?>) = BackendResult.Ok(data = JSONObject().apply {
            pairs.forEach { (k, v) -> put(k, v) }
        })

        fun failed(error: RelayError, reason: String, data: JSONObject = JSONObject()) =
            BackendResult.Failed(error, reason, data = data)

        override suspend fun execute(call: BackendCall): BackendResult {
            calls += call
            if (call.descriptor.id == CapabilityId.SURFACE_VIRTUAL) displayAlive = true
            return script.removeFirstOrNull() ?: ok("verified" to true)
        }

        override fun supports(capability: CapabilityId, surface: SurfaceKind): Boolean = when {
            // 建屏本身不要求屏已存在：它就是造屏的那条能力。
            capability == CapabilityId.SURFACE_VIRTUAL -> surface == SurfaceKind.BACKEND_TRUSTED
            else -> surface != SurfaceKind.BACKEND_TRUSTED || displayAlive
        }

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
        preference: SurfacePreference = SurfacePreference.BACKGROUND_PREFERRED,
        virtualTier: AccessTier = AccessTier.ALWAYS,
        launchTier: AccessTier = AccessTier.ALWAYS,
        withTable: Boolean = true,
        presenterScript: List<InterlockChoice> = emptyList(),
        parkBridge: ParkedRequestBridge? = null,
        parkScope: (() -> CoroutineScope?)? = null,
        /** 系统版本：可信屏要 Android 13 起（SurfacePolicy.MIN_BACKEND_SDK）。 */
        sdkInt: Int = 33,
        /** shell 通路此刻在不在（Shizuku 未授权/掉线即不在）。 */
        shellAvailable: Boolean = true,
        /** 这台机器拒绝过可信建屏（ROM 拒绝标记）。 */
        trustedDenied: Boolean = false,
    ) {
        val backend = ScriptableBackend()
        val presenter = RecordingPresenter(presenterScript)
        var clock = 1_000L
        val landingTable = TargetLandingTable(
            now = { clock },
            preference = { preference },
        )

        /** 落点沿用的顶层核对读数：默认 null（核验不了 → 不沿用）。 */
        var topOnUserScreen: String? = null
        var topOnTrusted: String? = null

        val dispatcher = BackendDispatcher(BackendId.entries.associateWith { backend })

        /** 备屏档位跳过与降级原因的运行日志读数。 */
        val runLog = RunLog(Files.createTempDirectory("landing-route-run").toFile())

        val coordinator: RelayCoordinator = RelayCoordinator(
            context = HostContext(),
            gatekeeper = InterlockGate(
                tiers = TierStore(MemoryPrefs()).apply {
                    setTier(CapabilityId.APP_LAUNCH, launchTier)
                    setTier(CapabilityId.SURFACE_VIRTUAL, virtualTier)
                    setTier(CapabilityId.UI_TAP, AccessTier.ALWAYS)
                    setTier(CapabilityId.APP_STOP, AccessTier.ALWAYS)
                    setTier(CapabilityId.SCREEN_CAPTURE, AccessTier.ALWAYS)
                    setTier(CapabilityId.SCREEN_RECORD, AccessTier.ALWAYS)
                    setTier(CapabilityId.UI_SNAPSHOT, AccessTier.ALWAYS)
                    setTier(CapabilityId.PKG_QUERY, AccessTier.ALWAYS)
                },
                broker = InterlockBroker(presenter, RunLog(Files.createTempDirectory("landing-route").toFile())),
                runLog = runLog,
            ),
            policy = SurfacePolicy(
                preference = { preference },
                shellAvailable = { shellAvailable },
                backendCanServe = dispatcher::canServe,
                sdkInt = sdkInt,
            ),
            dispatcher = dispatcher,
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
                sdkInt = sdkInt,
            ),
            landingTable = if (withTable) landingTable else null,
            trustedDisplayAlive = { backend.displayAlive },
            trustedDisplayDenied = { trustedDenied },
            topPackageOn = { display ->
                if (display == 0) topOnUserScreen else topOnTrusted
            },
            surfacePreference = { preference },
            parkBridge = parkBridge,
            parkScope = parkScope,
            // 预算相关的判定（审批窗、备屏的剩余预算）走被测方自注入的时钟：JVM 单测里
            // android.os.SystemClock 桩恒返 0（monotonicNow 恒 0），注入真实单调时钟
            // 才能让这些按时间算的判定落在真实量纲上。
            now = { System.nanoTime() / 1_000_000L },
            paths = RelayPaths(Files.createTempDirectory("landing-route-paths").toFile()),
            runLog = runLog,
            auditLog = AuditLog(Files.createTempDirectory("landing-route-audit").toFile(), "salt"),
        )

        suspend fun handle(capability: CapabilityId, args: JSONObject, budgetMs: Long = 60_000): RelayResponse {
            val outcome = coordinator.handle(
                RelayRequest(
                    id = "req-${calls()}-${System.nanoTime()}",
                    sentAtMs = 0L,
                    capability = capability.wire,
                    args = args,
                    timeoutMs = RelayRequest.DEFAULT_TIMEOUT_MS,
                ),
                budgetMs,
            )
            return (outcome as HandlerResult.Done).response
        }

        private fun calls() = backend.calls.size

        /** 建屏派发次数：任何「不建屏」的判据都靠它计数。 */
        fun surfaceVirtualCalls(): Int =
            backend.calls.count { it.descriptor.id == CapabilityId.SURFACE_VIRTUAL }
    }

    private fun launchArgs(pkg: String) = JSONObject().put("package", pkg)

    private fun tapArgs() = JSONObject().put("x", 10).put("y", 20)

    private fun clickArgs(pkg: String) =
        JSONObject().put("selector", JSONObject().put("package", pkg))

    // ── 后台成功与沿用 ──

    @Test
    fun `后台面缺失且建屏完全访问时先建屏再后台启动`() = runBlocking {
        // 建屏档位「完全访问」：自动建屏路径照旧（询问审批档那条入口拒绝只管 ASK，
        // 不许把这一档也带进拒绝里去 —— 见「询问审批档下后台启动不建屏不弹卡并回结构化错误」）。
        val f = Fixture(virtualTier = AccessTier.ALWAYS)
        f.backend.stage(f.backend.ok("state" to "created"), f.backend.ok("verified" to true, "display" to 7))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull(response.error)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, f.backend.calls[0].surface)
        assertEquals(CapabilityId.SURFACE_VIRTUAL, f.backend.calls[0].descriptor.id)
        // 启动落在后台面上，回包如报；落点按证据入账。
        assertEquals(CapabilityId.APP_LAUNCH, f.backend.calls[1].descriptor.id)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, f.backend.calls[1].surface)
        val record = checkNotNull(f.landingTable.get("com.target")) { "expected a landing record" }
        assertEquals(SurfaceKind.BACKEND_TRUSTED, record.surface)
        assertEquals(7, record.displayId)
        assertEquals(0, f.presenter.prompts.size)
    }

    @Test
    fun `后续界面调用按最近目标沿后台面`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(f.backend.ok("verified" to true, "display" to 7), f.backend.ok("verified" to true))
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target")).error)
        // 目标仍在后台面的最前面：沿记录走。
        f.topOnTrusted = "com.target"
        val follow = f.handle(CapabilityId.UI_TAP, tapArgs())
        assertNull(follow.error)
        // 不带目标参数的界面调用沿最近一次成功启动的目标：落在同一条后台面上。
        assertEquals(CapabilityId.UI_TAP, f.backend.calls[1].descriptor.id)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, f.backend.calls[1].surface)
    }

    @Test
    fun `用户切走后最近目标不再沿用退回偏好裁决`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        // A 被抓到用户真屏：前台落点记录。
        f.backend.stage(f.backend.failed(RelayError.LAUNCH_NOT_LANDED, "grabbed", JSONObject().put("grabbedUserScreen", true)))
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.a")).error)
        // 用户手动切到了未经 app.launch 的 C：A 的旧落点不作数，
        // 界面调用退回执行偏好裁决（优先后台 + 屏在 → 后台面），不按 A 的前台记录硬发。
        f.topOnUserScreen = "com.c"
        val follow = f.handle(CapabilityId.UI_TAP, tapArgs())
        assertNull(follow.error)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, f.backend.calls[1].surface)
    }

    @Test
    fun `全屏采集不被最近目标带走`() = runBlocking {
        val f = Fixture()
        // B 正常落在后台屏后屏被回收： 的记录都不该把 screen.capture 带去后台面。
        f.backend.displayAlive = true
        f.backend.stage(f.backend.ok("verified" to true, "display" to 7))
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.b")).error)
        f.backend.displayAlive = false
        f.backend.stage(f.backend.ok("verified" to true))
        val capture = f.handle(CapabilityId.SCREEN_CAPTURE, JSONObject())
        assertNull("全屏采集按执行偏好与屏级事实处理", capture.error)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[1].surface)
    }

    @Test
    fun `点名目标已不在记录位置上时给结构化错误`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(f.backend.ok("verified" to true, "display" to 7))
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.a")).error)
        // com.a 已不是后台面的当前顶层：点名它的调用不代为改道。
        f.topOnTrusted = "com.other"
        f.backend.stage(f.backend.ok("verified" to true))
        val follow = f.handle(CapabilityId.UI_CLICK, clickArgs("com.a"))
        assertEquals(RelayError.SURFACE_UNAVAILABLE, follow.error)
        assertEquals("不派发", 1, f.backend.calls.size)
    }

    @Test
    fun `目标各在各的面上互不牵连且停止即失效`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(
            // A 的后台启动把它起在了用户真屏：按证据记成前台落点（不重发前台启动）。
            f.backend.failed(RelayError.LAUNCH_NOT_LANDED, "grabbed", JSONObject().put("grabbedUserScreen", true)),
            // B 正常落在后台屏。
            f.backend.ok("verified" to true, "display" to 7),
            f.backend.ok("verified" to true),
        )
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.a")).error)
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.b")).error)
        // A 被抓到用户真屏（前台记录）、B 落在后台屏：界面调用沿最近启动的目标，
        // 两个目标各在各的面上，互不牵连。
        f.topOnTrusted = "com.b"
        assertNull(f.handle(CapabilityId.UI_TAP, tapArgs()).error)
        assertEquals("界面沿最近目标 B 落在后台面", SurfaceKind.BACKEND_TRUSTED, f.backend.calls[2].surface)
        // 目标 A 被停止后，A 的前台记录随停失效，B 的后台落点不受牵连。
        assertNull(f.handle(CapabilityId.APP_STOP, launchArgs("com.a")).error)
        assertNull(f.landingTable.get("com.a"))
        assertEquals(SurfaceKind.BACKEND_TRUSTED, checkNotNull(f.landingTable.get("com.b")).surface)
    }

    // ── 失败分类 ──

    @Test
    fun `后台启动抓到用户真屏时不再重发前台启动`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(
            f.backend.failed(RelayError.LAUNCH_NOT_LANDED, "came up on the user's display", JSONObject().put("grabbedUserScreen", true)),
        )
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        // 如实回成功：目标确实已在用户眼前；场景事实保留在 data 里。
        assertNull(response.error)
        assertTrue(response.ok)
        assertTrue(response.data.optBoolean("grabbedUserScreen"))
        assertEquals("一次派发就是全部：没有第二次前台启动", 1, f.backend.calls.size)
        val record = checkNotNull(f.landingTable.get("com.target")) { "expected a foreground record" }
        assertEquals(SurfaceKind.FOREGROUND, record.surface)
    }

    @Test
    fun `确定性失败走一次前台兜底并按核验落账`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(
            f.backend.failed(RelayError.LAUNCH_NOT_LANDED, "top is another app"),
            f.backend.ok("verified" to true),
        )
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull(response.error)
        assertEquals("后台一次 + 前台兜底一次", 2, f.backend.calls.size)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[1].surface)
        assertEquals(SurfaceKind.FOREGROUND, checkNotNull(f.landingTable.get("com.target")).surface)
    }

    @Test
    fun `前台兜底未核验同样不回成功`() = runBlocking {
        val f = Fixture(preference = SurfacePreference.FOREGROUND)
        // 后台确定性失败（确认停的是别的应用）→ 一次前台兜底 → 兜底也没核验到落点。
        f.backend.stage(
            f.backend.failed(RelayError.LAUNCH_NOT_LANDED, "top is another app"),
            f.backend.ok("verified" to false),
        )
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals("兜底未核验不是成功", RelayError.LAUNCH_NOT_LANDED, response.error)
        assertEquals("两次派发到此为止", 2, f.backend.calls.size)
        assertNull(f.landingTable.get("com.target"))
    }

    @Test
    fun `am start 被权限拒绝时同参重发不被幂等标记挡住`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        // am start 被服务侧权限拒绝：命令失败类，按启动前失败原样交回。
        f.backend.stage(f.backend.failed(RelayError.COMMAND_NOT_PERMITTED, "Permission Denial: am start from shell"))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.COMMAND_NOT_PERMITTED, response.error)
        assertEquals(1, f.backend.calls.size)
        // 同参重发是修复后的新尝试，不被 60 秒未知标记挡住。
        f.backend.stage(f.backend.failed(RelayError.COMMAND_NOT_PERMITTED, "Permission Denial: am start from shell"))
        val retry = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.COMMAND_NOT_PERMITTED, retry.error)
        assertEquals("重发照常派发", 2, f.backend.calls.size)
        assertNull(f.landingTable.get("com.target"))
    }

    @Test
    fun `暂时性失败不降级不落账`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(f.backend.failed(RelayError.BACKEND_SHIZUKU_DEAD, "binder died"))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.BACKEND_SHIZUKU_DEAD, response.error)
        assertEquals("保持后台意图：没有前台兜底的第二次派发", 1, f.backend.calls.size)
        assertNull("没有证据就不落账", f.landingTable.get("com.target"))
    }

    @Test
    fun `未知失败不重放且同参重发被幂等挡下`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(
            f.backend.failed(
                RelayError.BACKEND_UNAVAILABLE,
                "cannot read any display's top window",
                JSONObject().put("landingCheck", "failed"),
            ),
        )
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.BACKEND_UNAVAILABLE, response.error)
        assertEquals(1, f.backend.calls.size)
        // 紧随其后的同参重发被幂等标记挡下：先核实，不盲目再启一次。
        val retry = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.TRANSPORT_UNKNOWN, retry.error)
        assertEquals("未知结果不自动重放", 1, f.backend.calls.size)
    }

    // ── 失效与重校验 ──

    @Test
    fun `落点沿用时屏没了以结构化错误收场而不是静默改前台`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(f.backend.ok("verified" to true, "display" to 7), f.backend.ok("verified" to true))
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target")).error)
        // 屏被回收：下一次界面调用不得拿旧裁决硬发。
        f.backend.displayAlive = false
        val follow = f.handle(CapabilityId.UI_TAP, tapArgs())
        assertEquals(RelayError.SURFACE_UNAVAILABLE, follow.error)
        assertEquals("记录失效就不派发", 1, f.backend.calls.size)
    }

    @Test
    fun `目标被停止后落点随停失效`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(
            f.backend.ok("verified" to true, "display" to 7),
            f.backend.ok("stopped" to true),
            f.backend.ok("verified" to true),
        )
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target")).error)
        assertNotNull(f.landingTable.get("com.target"))
        assertNull(f.handle(CapabilityId.APP_STOP, launchArgs("com.target")).error)
        assertNull("目标被停止，落点随停失效", f.landingTable.get("com.target"))
    }

    // ── 未核验的 Ok 不是成功 ──

    @Test
    fun `verified false 且观察缺席按未知处理并挡同参重发`() = runBlocking {
        val f = Fixture(preference = SurfacePreference.FOREGROUND)
        f.backend.stage(f.backend.ok("verified" to false))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals("命令被接受但落点未确认：不是成功", RelayError.LAUNCH_NOT_LANDED, response.error)
        assertEquals("不落账、不兜底", 1, f.backend.calls.size)
        assertNull(f.landingTable.get("com.target"))
        // 同参重发被幂等标记挡下：先核实，不盲目再启。
        val retry = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.TRANSPORT_UNKNOWN, retry.error)
        assertEquals(1, f.backend.calls.size)
        // 标记只在窗口内挡人：窗口一过，同参重发照常走。
        f.clock += 61_000L
        f.backend.stage(f.backend.ok("verified" to true))
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target")).error)
        assertEquals(2, f.backend.calls.size)
    }

    @Test
    fun `成因可查的未核验启动不挡同参重发`() = runBlocking {
        val f = Fixture(preference = SurfacePreference.FOREGROUND)
        // 回包带着 observed（中介选择框/分身容器停在最前面）：成因明确、用户答完框即可再发。
        f.backend.stage(f.backend.ok("verified" to false, "observed" to "com.chooser"))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.LAUNCH_NOT_LANDED, response.error)
        assertEquals("确定性可修复错误不写幂等标记", 1, f.backend.calls.size)
        f.backend.stage(f.backend.ok("verified" to true))
        assertNull("用户处理完选择框后立即重发不被挡", f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target")).error)
        assertEquals(2, f.backend.calls.size)
    }

    @Test
    fun `成功读回现场后未知标记即摘`() = runBlocking {
        val f = Fixture(preference = SurfacePreference.FOREGROUND)
        f.backend.stage(f.backend.ok("verified" to false))
        assertEquals(RelayError.LAUNCH_NOT_LANDED, f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target")).error)
        // 一次成功的 ui.snapshot 就是标记要等的核实：摘标记，同参重发放行。
        f.backend.stage(f.backend.ok("verified" to true), f.backend.ok("verified" to true))
        assertNull(f.handle(CapabilityId.PKG_QUERY, JSONObject()).error)
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target")).error)
        assertEquals(3, f.backend.calls.size)
    }

    @Test
    fun `verified false 的后台启动同样回结构化失败`() = runBlocking {
        val f = Fixture()
        f.backend.displayAlive = true
        f.backend.stage(f.backend.ok("verified" to false, "display" to 7))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.LAUNCH_NOT_LANDED, response.error)
        assertEquals(1, f.backend.calls.size)
        assertNull(f.landingTable.get("com.target"))
    }

    @Test
    fun `未接路由层时路由行为等价且未核验仍不是成功`() = runBlocking {
        val f = Fixture(preference = SurfacePreference.FOREGROUND, withTable = false)
        f.backend.stage(f.backend.ok("verified" to true))
        assertNull(f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target")).error)
        // 无记录可查：全屏采集按执行偏好与屏级事实处理（前台偏好 → 前台）。
        f.backend.stage(f.backend.ok("verified" to true))
        val capture = f.handle(CapabilityId.SCREEN_CAPTURE, JSONObject())
        assertNull(capture.error)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[1].surface)
        // 未核验的 Ok 不因"没有路由层"就变回成功。
        f.backend.stage(f.backend.ok("verified" to false))
        val unverified = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.other"))
        assertEquals(RelayError.LAUNCH_NOT_LANDED, unverified.error)
    }

    // ── 备屏失败的性质 ──

    @Test
    fun `备屏暂时性失败保持后台意图不降级`() = runBlocking {
        val f = Fixture()
        f.backend.stage(f.backend.failed(RelayError.BACKEND_SHIZUKU_DEAD, "binder died"))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.BACKEND_UNAVAILABLE, response.error)
        assertEquals("只有建屏一次派发，没有前台启动", 1, f.backend.calls.size)
        assertNull(f.landingTable.get("com.target"))
    }

    @Test
    fun `备屏确定性失败按降级原因回前台启动`() = runBlocking {
        val f = Fixture()
        f.backend.stage(f.backend.failed(RelayError.SURFACE_UNAVAILABLE, "no safe size"), f.backend.ok("verified" to true))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull(response.error)
        assertEquals(2, f.backend.calls.size)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[1].surface)
        assertEquals("降级原因随回包", SurfaceKind.BACKEND_TRUSTED, response.degradedFrom)
    }

    @Test
    fun `询问审批档下后台启动不建屏不弹卡并回结构化错误`() = runBlocking {
        // 方案 A：建屏拨在「询问审批」时，这一次调用不建屏、不弹卡、不派发，直接回
        // 一条结构化错误，让助手先显式调 surface.virtual（它自己会弹一张审批卡，
        // 走既有挂起审批，不占执行槽），建好屏再重试这条启动。
        val f = Fixture(
            virtualTier = AccessTier.ASK,
            // 即便给一张允许，备屏也不该在这里问：这一支根本不进审批。
            presenterScript = listOf(InterlockChoice.ALLOW_ONCE),
        )
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertEquals(RelayError.SURFACE_UNAVAILABLE, response.error)
        assertEquals("不弹审批卡", 0, f.presenter.prompts.size)
        assertEquals("不派发：建屏与启动都没有", 0, f.backend.calls.size)
        assertEquals("不派发建屏", 0, f.surfaceVirtualCalls())
        assertTrue(
            "reason 点名下一步：先建屏再重试",
            response.reason?.contains("surface.virtual") == true &&
                response.reason.contains("{\"action\":\"create\"}") &&
                response.reason.contains("the background intent is kept"),
        )
        assertTrue(
            "已有会话授权时不会弹卡：措辞是「可能」要审批",
            response.reason?.contains("it may ask for approval") == true,
        )
        // 回包按既有 rejected 形状说清「本来要后台、但屏未就绪」。
        assertEquals(SurfaceKind.FOREGROUND, response.surface)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, response.degradedFrom)
        val skipped = f.runLog.snapshot().filter { it.event == LogEvent.SURFACE_PREPARE_SKIPPED }
        assertEquals("备屏被跳过要留一条结构化日志，不静默", 1, skipped.size)
        assertTrue("点名原因是档位询问审批：tier_ask", skipped.single().text.contains("tier_ask"))
    }

    // ── 询问审批档的入口拒绝：只在「那块屏此刻建得出来」时才成立 ──

    @Test
    fun `询问审批档且系统版本不够时不提示建屏而退回前台`() = runBlocking {
        // Android 10–12：可信屏根本建不出来（SurfacePolicy.MIN_BACKEND_SDK = 33）。
        // 「先建屏再重试」指向一个用户此刻做不到的动作，助手只会白弹一张建不出来的屏的
        // 审批卡：这一支不成立，落回裁决层的前台降级，并带上准确的原因。
        val f = Fixture(virtualTier = AccessTier.ASK, sdkInt = 30)
        f.backend.stage(f.backend.ok("verified" to true))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull("不可建时不回 ASK 提示错误", response.error)
        assertEquals("不弹审批卡", 0, f.presenter.prompts.size)
        assertEquals("不派发建屏", 0, f.surfaceVirtualCalls())
        assertEquals("只有前台启动这一次派发", 1, f.backend.calls.size)
        assertEquals(CapabilityId.APP_LAUNCH, f.backend.calls[0].descriptor.id)
        assertEquals("落回前台", SurfaceKind.FOREGROUND, f.backend.calls[0].surface)
        // 降级如实写进回包：本该后台、实际前台。
        assertEquals(SurfaceKind.FOREGROUND, response.surface)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, response.degradedFrom)
        assertEquals(
            "降级原因点名版本不够，不是泛化的屏不可用",
            RelayError.SURFACE_API_LEVEL.code,
            response.degradeReason,
        )
    }

    @Test
    fun `询问审批档且shell通路不在时不提示建屏而退回前台`() = runBlocking {
        // Shizuku 未授权/掉线：shell 身份调不动，那块屏建不出来。
        val f = Fixture(virtualTier = AccessTier.ASK, shellAvailable = false)
        f.backend.stage(f.backend.ok("verified" to true))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull("不可建时不回 ASK 提示错误", response.error)
        assertEquals("不弹审批卡", 0, f.presenter.prompts.size)
        assertEquals("不派发建屏", 0, f.surfaceVirtualCalls())
        assertEquals(1, f.backend.calls.size)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[0].surface)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, response.degradedFrom)
        assertEquals(
            "降级原因点名 shell 不在",
            RelayError.SURFACE_NO_SHELL.code,
            response.degradeReason,
        )
    }

    @Test
    fun `询问审批档且这台机器拒绝过建屏时不提示建屏而退回前台`() = runBlocking {
        // ROM 拒绝过可信建屏：再建议建屏是让助手撞同一堵墙。
        val f = Fixture(virtualTier = AccessTier.ASK, trustedDenied = true)
        f.backend.stage(f.backend.ok("verified" to true))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull("被拒过时不回 ASK 提示错误", response.error)
        assertEquals("不弹审批卡", 0, f.presenter.prompts.size)
        assertEquals("不派发建屏", 0, f.surfaceVirtualCalls())
        assertEquals(1, f.backend.calls.size)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[0].surface)
        assertEquals(SurfaceKind.FOREGROUND, response.surface)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, response.degradedFrom)
    }

    @Test
    fun `未接路由层时询问审批档按执行偏好裁决不提示建屏`() = runBlocking {
        // 「未接路由层 = 行为不变」：没有落点层就没有「建屏→重试」这套回程，
        // 这一支不成立，调用照旧走执行偏好裁决（前台）。
        val f = Fixture(virtualTier = AccessTier.ASK, withTable = false)
        f.backend.stage(f.backend.ok("verified" to true))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull(response.error)
        assertEquals("不派发建屏", 0, f.surfaceVirtualCalls())
        assertEquals(1, f.backend.calls.size)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[0].surface)
    }

    @Test
    fun `带回宿主的启动不触发询问审批档的建屏提示`() = runBlocking {
        // 「把宿主带回前台」这一形状与后台意图无关：它必须落在用户眼前那块屏上。
        val f = Fixture(virtualTier = AccessTier.ASK)
        f.backend.stage(f.backend.ok("verified" to true))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.example.host"))
        assertNull(response.error)
        assertEquals("不弹审批卡", 0, f.presenter.prompts.size)
        assertEquals("不派发建屏", 0, f.surfaceVirtualCalls())
        assertEquals(1, f.backend.calls.size)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[0].surface)
    }

    @Test
    fun `前台偏好下询问审批档不触发建屏提示`() = runBlocking {
        val f = Fixture(preference = SurfacePreference.FOREGROUND, virtualTier = AccessTier.ASK)
        f.backend.stage(f.backend.ok("verified" to true))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull(response.error)
        assertEquals("不弹审批卡", 0, f.presenter.prompts.size)
        assertEquals("不派发建屏", 0, f.surfaceVirtualCalls())
        assertEquals(1, f.backend.calls.size)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[0].surface)
    }

    @Test
    fun `屏已存在时询问审批档不影响正常后台路由`() = runBlocking {
        // 屏已经在：后台路由照旧走，不进 ASK 那条入口拒绝，也不建屏。
        val f = Fixture(virtualTier = AccessTier.ASK)
        f.backend.displayAlive = true
        f.backend.stage(f.backend.ok("verified" to true, "display" to 7))
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull(response.error)
        assertEquals("只有启动本身", 1, f.backend.calls.size)
        assertEquals(CapabilityId.APP_LAUNCH, f.backend.calls[0].descriptor.id)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, f.backend.calls[0].surface)
        assertEquals("不弹卡", 0, f.presenter.prompts.size)
    }

    @Test
    fun `建屏档位禁止时不建屏不弹卡按前台回退并记降级日志`() = runBlocking {
        val f = Fixture(virtualTier = AccessTier.DENIED)
        val response = f.handle(CapabilityId.APP_LAUNCH, launchArgs("com.target"))
        assertNull(response.error)
        assertEquals("档位禁止：没有建屏派发", 1, f.backend.calls.size)
        assertEquals(CapabilityId.APP_LAUNCH, f.backend.calls[0].descriptor.id)
        assertEquals(SurfaceKind.FOREGROUND, f.backend.calls[0].surface)
        assertEquals("不弹卡", 0, f.presenter.prompts.size)
        // 前台回退不静默：降级来源与降级原因都写在回包里。
        assertEquals(SurfaceKind.FOREGROUND, response.surface)
        assertEquals(SurfaceKind.BACKEND_TRUSTED, response.degradedFrom)
        assertEquals(
            "降级原因点名是档位不许建屏",
            RelayError.GATE_HOST_DENIED.code,
            response.degradeReason,
        )
        val skipped = f.runLog.snapshot().filter { it.event == LogEvent.SURFACE_PREPARE_SKIPPED }
        assertEquals("备屏被跳过要留一条结构化日志，不静默", 1, skipped.size)
        assertTrue("点名原因是档位禁止：tier_denied", skipped.single().text.contains("tier_denied"))
    }

    // ── 审批挂起后的重校验 ──

    @Test
    fun `审批期间屏被收掉后续行不按旧面派发`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val bridge = FakeParkBridge()
        val f = Fixture(
            launchTier = AccessTier.ASK,
            presenterScript = listOf(InterlockChoice.AWAITING, InterlockChoice.ALLOW_ONCE),
            parkBridge = bridge,
            parkScope = { scope },
        )
        f.backend.displayAlive = true
        val outcome = f.coordinator.handle(
            RelayRequest(
                id = "req-parked",
                sentAtMs = 0L,
                capability = CapabilityId.APP_LAUNCH.wire,
                args = launchArgs("com.target"),
                timeoutMs = RelayRequest.DEFAULT_TIMEOUT_MS,
            ),
            120_000,
        )
        // 框已挂上、等用户作答：请求进入挂起面。
        val park = (outcome as HandlerResult.Parked).park
        // 答复到达前屏被收掉：续行不得按旧的后台面裁决派发。
        f.backend.displayAlive = false
        val response = park.resume()
        assertEquals(RelayError.BACKEND_UNAVAILABLE, response.error)
        assertEquals("旧裁决不硬发", 0, f.backend.calls.size)
        assertTrue("挂起项已登记", bridge.registered.isNotEmpty())
        scope.cancel()
    }
}
