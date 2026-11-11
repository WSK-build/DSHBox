package com.dshbox.remote

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.SessionState
import com.dshbox.remote.session.ForegroundElapsedClock
import com.dshbox.remote.session.ProxySession
import com.dshbox.remote.session.SessionFactory
import com.dshbox.remote.session.SessionStartException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 显示槽阶段的控制器测试：会话状态机全图映射（Loading/Disconnected/Terminated
 * 补全）、内存保留策略真值表（IMMEDIATE 即时释放 / Retain 前台时钟倒计时与
 * 恢复前台补偿判定）、门禁令牌的显示槽暴露。
 */
class RemoteConnectionDisplaySlotTest {

    private class FakeSession(
        override val gateToken: String? = null,
        override val loopbackOrigin: String = "http://127.0.0.2:51000",
    ) : ProxySession {
        var started = false
        var stopped = false

        override fun start() {
            check(!started) { "session already started" }
            started = true
        }

        override fun stop() {
            stopped = true
        }
    }

    private class FakeSessionFactory : SessionFactory {
        var devices: List<Device> = emptyList()
        var sessionSupplier: () -> FakeSession = { FakeSession() }
        val sessions = mutableListOf<FakeSession>()

        /** 建立失败注入与可阻断闸门：失败路径与建立清理场景用。 */
        var createFailure: Exception? = null
        var createGate: CountDownLatch? = null
        var createExited: CountDownLatch? = null

        /** 删除设备的可阻断闸门：模拟「标记已落、清单未刷新」的删除在途窗口。 */
        var removeDeviceGate: CountDownLatch? = null
        var removeDeviceEntered: CountDownLatch? = null

        override fun devices(): List<Device> = devices

        override fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession {
            try {
                createGate?.await()
                createFailure?.let { throw it }
                return sessionSupplier().also { synchronized(sessions) { sessions.add(it) } }
            } finally {
                createExited?.countDown()
            }
        }

        override fun removeDevice(deviceId: DeviceId) {
            removeDeviceEntered?.countDown()
            // 限时等待：断言失败的用例不得遗留永久阻塞的删除线程
            removeDeviceGate?.await(10, TimeUnit.SECONDS)
            removedDeviceIds.add(deviceId)
            devices = devices.filter { it.id != deviceId }
        }

        val removedDeviceIds = mutableListOf<DeviceId>()
    }

    private class FakeRendererAdapter : DshRendererAdapter {
        override val id: String = "fake"
        override val displayName: String = "fake"
        override val capabilities = com.dshbox.dsh.renderer.api.DshRendererCapabilities()

        override fun create(
            context: android.content.Context,
            config: com.dshbox.dsh.renderer.api.DshRendererConfig,
            host: com.dshbox.dsh.renderer.api.DshRendererHost,
        ): com.dshbox.dsh.renderer.api.DshRenderer {
            throw UnsupportedOperationException("renderer is not exercised in this test")
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var factory: FakeSessionFactory
    private var wall = 0L

    private val device = Device(
        id = DeviceId("dev-1"),
        name = "工作电脑",
        endpoints = listOf(
            Endpoint(EndpointId("ep-1"), "局域网", "http://192.168.1.5:3081", "generic"),
            Endpoint(EndpointId("ep-2"), "公网", "http://gw.example.com", "generic"),
        ),
        addedAtMillis = 1_700_000_000_000,
    )

    private val ep1 = EndpointId("ep-1")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        factory = FakeSessionFactory().apply { devices = listOf(device) }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(clock: ForegroundElapsedClock): RemoteConnectionController =
        RemoteConnectionController(
            scope,
            object : RemoteConnectionHost {
                override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
            },
            sessionFactoryOverride = factory,
            ioDispatcher = Dispatchers.Unconfined,
            foregroundClock = clock,
        )

    // ---- 会话状态机全图映射 ----

    @Test fun stateEnumCoversEightStateDiagram() {
        // 全图八态逐一在册（不止数量，防同义态被合并/改名后静默漂移）
        assertEquals(
            setOf(
                SessionState.Idle,
                SessionState.Connecting,
                SessionState.Loading,
                SessionState.Ready,
                SessionState.Failed,
                SessionState.Disconnected,
                SessionState.Released,
                SessionState.Terminated,
            ),
            SessionState.values().toSet(),
        )
    }

    @Test fun proxyReadyMapsToReadyAndRendererEventsMapToLoadingAndBack() {
        val controller = controller(ForegroundElapsedClock())
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        assertEquals(SessionState.Ready, controller.sessionStates.value[ep1])

        // 渲染器加载中 → Loading，进度按端点记账
        controller.notifyRendererLoading(ep1)
        assertEquals(SessionState.Loading, controller.sessionStates.value[ep1])
        controller.notifyRendererProgress(ep1, 42)
        assertEquals(42, controller.rendererProgress.value[ep1])

        // 页面渲染完成 → Ready
        controller.notifyRendererReady(ep1)
        assertEquals(SessionState.Ready, controller.sessionStates.value[ep1])
    }

    @Test fun rendererFailureAfterReadyMapsToFailedWithReason() {
        val controller = controller(ForegroundElapsedClock())
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        controller.notifyRendererReady(ep1)

        controller.notifyRendererFailed(ep1, "HTTP 404")
        assertEquals(SessionState.Failed, controller.sessionStates.value[ep1])
        assertEquals("HTTP 404", controller.sessionFailureReasons.value[ep1])

        // Failed 不是断连来源：不得再转 Disconnected
        controller.notifySessionDisconnected(ep1)
        assertEquals(SessionState.Failed, controller.sessionStates.value[ep1])
    }

    @Test fun readyThenDisconnectedThenRetryReestablishesSession() {
        val controller = controller(ForegroundElapsedClock())
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        val first = factory.sessions.single()
        controller.notifyRendererReady(ep1)

        // Ready 后连接断开 → Disconnected
        controller.notifySessionDisconnected(ep1)
        assertEquals(SessionState.Disconnected, controller.sessionStates.value[ep1])

        // 重连：旧会话停止、新会话建立、状态经 Connecting 收敛
        controller.retryEndpoint(ep1)
        assertTrue(first.stopped)
        assertEquals(2, factory.sessions.size)
        assertTrue(factory.sessions.last().started)
        assertEquals(SessionState.Ready, controller.sessionStates.value[ep1])
        assertEquals(DisplayTarget.Session(ep1), controller.activeDisplay.value)
    }

    @Test fun disconnectedKeepsFailureReasonForOverlay() {
        // 断连迁移不清空原因：断连覆盖层需呈现原因
        val controller = controller(ForegroundElapsedClock())
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        controller.notifyRendererReady(ep1)

        controller.notifySessionDisconnected(ep1, "upstream unreachable")
        assertEquals(SessionState.Disconnected, controller.sessionStates.value[ep1])
        assertEquals("upstream unreachable", controller.sessionFailureReasons.value[ep1])

        // 未带新原因的重复断连：沿用既有记录，不因迁移清空
        controller.notifySessionDisconnected(ep1)
        assertEquals("upstream unreachable", controller.sessionFailureReasons.value[ep1])
    }

    @Test fun removeDeviceMapsDisplayedEndpointToTerminatedAndFallsBackLocal() {
        val controller = controller(ForegroundElapsedClock())
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        assertEquals(DisplayTarget.Session(ep1), controller.activeDisplay.value)

        controller.removeDevice(DeviceId("dev-1"))
        // Terminated 终态：会话停止、不可恢复；显示槽回退本机
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
    }

    @Test fun notifyRendererEventsIgnoredWithoutSession() {
        val controller = controller(ForegroundElapsedClock())
        controller.notifyRendererLoading(ep1)
        controller.notifyRendererReady(ep1)
        controller.notifyRendererFailed(ep1, "ghost")
        controller.notifySessionDisconnected(ep1)
        assertTrue(controller.sessionStates.value.isEmpty())
    }

    // ---- 内存策略：两档策略 × 页签/显示源触发的行为真值表 ----

    @Test fun immediatePolicyReleasesProxyOnSwitch() {
        val controller = controller(ForegroundElapsedClock())
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        controller.selectLocal()
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun retainPolicyKeepsProxyWithinWindowAndResumesWithoutRestart() {
        val clock = ForegroundElapsedClock()
        val controller = controller(clock)
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.onHostResumed() // 进入前台：开始计时
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        val session = factory.sessions.single()

        // 切走（保留档）：代理不关、槽位与端口保留、状态维持 Ready
        controller.selectLocal()
        assertFalse(session.stopped)
        assertEquals(SessionState.Ready, controller.sessionStates.value[ep1])

        // 期内切回：免重建（不新建代理、免重载），保留倒计时撤销
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        assertFalse(session.stopped)
        assertEquals(1, factory.sessions.size)
        assertEquals(DisplayTarget.Session(ep1), controller.activeDisplay.value)
    }

    @Test fun retainPolicyReleasesWhenForegroundElapsedReachesDeadline() {
        val clock = ForegroundElapsedClock { wall }
        val controller = controller(clock)
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.onHostResumed()
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        val session = factory.sessions.single()

        controller.selectLocal() // 失活时刻 = 0，期限 = 5 分钟
        assertFalse(session.stopped)

        wall += 6 * 60_000L // 前台时钟推进到期限之后
        controller.onHostResumed() // 恢复前台补偿判定：到点即释放
        assertTrue(session.stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
    }

    @Test fun retainPolicyDoesNotCountBackgroundTime() {
        val clock = ForegroundElapsedClock { wall }
        val controller = controller(clock)
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.onHostResumed()
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        val session = factory.sessions.single()

        controller.selectLocal() // 失活，期限 = 前台 5 分钟
        controller.onHostPaused() // 退后台：时钟停走
        wall += 10 * 60_000L // 后台滞留 10 分钟不计入
        controller.onHostResumed() // 回前台：累计值未到期，不释放
        assertFalse(session.stopped)

        wall += 6 * 60_000L // 前台累计到达期限
        controller.onHostResumed()
        assertTrue(session.stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
    }

    @Test fun switchingEndpointsUnderRetainKeepsPreviousProxy() {
        val controller = controller(ForegroundElapsedClock())
        controller.setRetentionDefault(RetentionPolicy.Retain(1))
        controller.onHostResumed()
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-2"))

        // 代理可多活：前一会话保留（计入端口占用），当前会话就绪
        assertFalse(factory.sessions[0].stopped)
        assertEquals(SessionState.Ready, controller.sessionStates.value[EndpointId("ep-2")])
        assertTrue(factory.sessions[1].started)
    }

    @Test fun retentionPolicySwitchAppliesToRetainedSessions() {
        val controller = controller(ForegroundElapsedClock())
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.onHostResumed()
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        controller.selectLocal()

        // 改回 IMMEDIATE：保留期内的失活会话立即释放
        controller.setRetentionDefault(RetentionPolicy.IMMEDIATE)
        assertTrue(factory.sessions.single().stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
    }

    // ---- 真值表「离开 DSH 页签」触发（页签保活，激活/失活由状态驱动） ----

    @Test fun immediatePolicyReleasesDisplayedSessionWhenDshTabHidden() {
        val controller = controller(ForegroundElapsedClock())
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        val session = factory.sessions.single()

        // 切走 DSH 页签：即时释放（渲染器随保活组合仍在，代理先收口）
        controller.onDshTabHidden()
        assertTrue(session.stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertEquals(DisplayTarget.Session(ep1), controller.activeDisplay.value)

        // 回到页签：已释放会话重新建立（Released → Connecting → Ready）
        controller.onDshTabVisible()
        assertEquals(2, factory.sessions.size)
        assertTrue(factory.sessions.last().started)
        assertEquals(SessionState.Ready, controller.sessionStates.value[ep1])
    }

    @Test fun retainPolicyArmsCountdownWhenDshTabHiddenAndResumesWithoutRestart() {
        val clock = ForegroundElapsedClock { wall }
        val controller = controller(clock)
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.onHostResumed()
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        val session = factory.sessions.single()

        // 切走页签（保留档）：代理不关、倒计时起算
        controller.onDshTabHidden()
        assertFalse(session.stopped)
        assertEquals(SessionState.Ready, controller.sessionStates.value[ep1])

        // 回场（期内）：撤销倒计时、沿用现有代理，免重建
        controller.onDshTabVisible()
        assertFalse(session.stopped)
        assertEquals(1, factory.sessions.size)

        // 若未回场：前台时钟到点后恢复前台补偿判定释放
        controller.onDshTabHidden()
        wall += 6 * 60_000L
        controller.onHostResumed()
        assertTrue(session.stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
    }

    @Test fun dshTabHiddenWithLocalDisplayIsNoOp() {
        val controller = controller(ForegroundElapsedClock())
        controller.onDshTabHidden()
        controller.onDshTabVisible()
        assertTrue(factory.sessions.isEmpty())
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
    }

    // ---- 门禁令牌暴露（显示槽门禁下发） ----

    @Test fun gateTokenExposedForSurfaceAndClearedWithSession() {
        val controller = controller(ForegroundElapsedClock())
        factory.sessionSupplier = { FakeSession(gateToken = "gate-token") }
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        assertEquals("gate-token", controller.sessionGateToken(ep1))
        assertEquals("http://127.0.0.2:51000", controller.sessionOrigins.value[ep1])

        controller.selectLocal()
        assertNull(controller.sessionGateToken(ep1))
        assertNull(controller.sessionOrigins.value[ep1])
    }

    @Test fun gateTokenNullForBypassSession() {
        val controller = controller(ForegroundElapsedClock())
        factory.sessionSupplier = { FakeSession(gateToken = null) }
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        assertNull(controller.sessionGateToken(ep1))
    }

    // ---- 删除设备 × 在途建立的交错防护（孤儿窗口） ----

    /** 真实 IO 调度器控制器：删除在途窗口的交错场景用。 */
    private fun asyncController(clock: ForegroundElapsedClock = ForegroundElapsedClock()): RemoteConnectionController =
        RemoteConnectionController(
            scope,
            object : RemoteConnectionHost {
                override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
            },
            sessionFactoryOverride = factory,
            ioDispatcher = Dispatchers.IO,
            foregroundClock = clock,
        )

    /** 轮询等待端点状态落账（异步建立经 IO 调度器，落账有短暂延迟）。 */
    private fun awaitEndpointState(
        controller: RemoteConnectionController,
        ep: EndpointId,
        expected: SessionState,
    ) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            controller.sessionStates.value[ep] != expected
        ) {
            Thread.sleep(5)
        }
        assertEquals(expected, controller.sessionStates.value[ep])
    }

    @Test fun reselectDuringDeviceRemovalWindowIsRefusedByDeviceVoiding() {
        // 交错窗口：删除设备已落作废登记（工厂删除闸门阻断在「删除已受理、
        // 清单未刷新、显示未回退」处），此刻重新选择该端点必须被标记拒收——
        // 登记处不得消费标记，否则回填失去拒收依据，已删设备的会话复活。
        // 用同步调度控制器：登记在闸门阻塞窗口内同步执行，拒收判定确定化
        // （IO 队列延迟会让登记跑到删除清理之后，窗口失守变成竞态）
        val controller = controller(ForegroundElapsedClock())
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        awaitEndpointState(controller, ep1, SessionState.Ready)

        factory.removeDeviceGate = CountDownLatch(1)
        factory.removeDeviceEntered = CountDownLatch(1)
        val remover = Thread { controller.removeDevice(DeviceId("dev-1")) }
        remover.start()
        assertTrue(factory.removeDeviceEntered!!.await(10, TimeUnit.SECONDS))
        // 删除在途：标记已落、代理已停、终止态已落账，清单与显示尚未回退
        assertTrue(factory.sessions.single().stopped)
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep1])
        assertEquals(DisplayTarget.Session(ep1), controller.activeDisplay.value)

        // 窗口内重新选择：拒收——不建新会话、不改写终止态、无在途残留
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        assertEquals(1, factory.sessions.size)
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep1])
        assertEquals(0, controller.pendingStartCountForTest())

        // 删除完成：清单刷新、显示回退本机、无在途建立的标记随删除清扫
        factory.removeDeviceGate!!.countDown()
        remover.join(10_000)
        assertEquals(listOf(DeviceId("dev-1")), factory.removedDeviceIds)
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep1])
    }

    @Test fun createFailureWithDeviceMissingClearsResidualState() {
        // 建立在途失败且设备已不在清单（无作废登记路径）：残留的 Connecting
        // 必须清理，不留无主登记态
        val controller = asyncController()
        val ep = EndpointId("ep-1")
        factory.createFailure = SessionStartException("upstream unreachable")
        factory.createGate = CountDownLatch(1)
        factory.createExited = CountDownLatch(1)
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        awaitEndpointState(controller, ep, SessionState.Connecting)
        controller.applyDevicesSnapshotForTest(emptyList())

        factory.createGate!!.countDown()
        assertTrue(factory.createExited!!.await(10, TimeUnit.SECONDS))
        // 失败回填（清理）在工厂退出计数之后才执行，静默收敛到空态
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && controller.sessionStates.value[ep] != null) {
            Thread.sleep(5)
        }
        assertNull(controller.sessionStates.value[ep])
    }

    // ---- 门面销毁收口 ----

    @Test fun closeReleasesEverySessionAndStopsAllProxies() {
        val controller = controller(ForegroundElapsedClock())
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.onHostResumed()
        controller.selectEndpoint(DeviceId("dev-1"), ep1)
        controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-2"))

        // Retain 档两会话并存：close 全部释放（组合销毁不泄漏代理线程与端口）
        assertEquals(2, factory.sessions.size)
        controller.close()
        assertTrue(factory.sessions.all { it.stopped })
        assertTrue(controller.sessionOrigins.value.isEmpty())
        assertEquals(
            controller.sessionStates.value.values.toSet(),
            setOf(SessionState.Released),
        )
    }
}
