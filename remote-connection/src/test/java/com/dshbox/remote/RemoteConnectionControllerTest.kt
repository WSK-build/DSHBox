package com.dshbox.remote

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.SessionState
import com.dshbox.remote.session.ProxySession
import com.dshbox.remote.session.SessionFactory
import com.dshbox.remote.session.SessionStartException
import com.dshbox.remote.session.SlotAllocationException
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
import java.io.File
import java.util.concurrent.CountDownLatch

/** 控制器会话状态机测试：以假会话工厂驱动 Idle→Connecting→Ready→Released 与失败路径。 */
class RemoteConnectionControllerTest {

    private class FakeRendererAdapter : DshRendererAdapter {
        override val id: String = "fake"
        override val displayName: String = "fake"
        override val capabilities: com.dshbox.dsh.renderer.api.DshRendererCapabilities =
            com.dshbox.dsh.renderer.api.DshRendererCapabilities()

        override fun create(
            context: android.content.Context,
            config: com.dshbox.dsh.renderer.api.DshRendererConfig,
            host: com.dshbox.dsh.renderer.api.DshRendererHost,
        ): com.dshbox.dsh.renderer.api.DshRenderer {
            throw UnsupportedOperationException("renderer is not exercised in this test")
        }
    }

    private class FakeSession(
        private val startFailure: Exception? = null,
        private val startGate: CountDownLatch? = null,
        private val startEntered: CountDownLatch? = null,
    ) : ProxySession {
        var started = false
        var stopped = false
        override val loopbackOrigin: String = "http://127.0.0.2:51000"

        override fun start() {
            check(!started) { "session already started" }
            startEntered?.countDown()
            startGate?.await()
            startFailure?.let { throw it }
            started = true
        }

        override fun stop() {
            stopped = true
        }
    }

    private class FakeSessionFactory : SessionFactory, com.dshbox.remote.session.PairingArchiver {
        var devices: List<Device> = emptyList()
        var createFailure: Exception? = null
        var sessionSupplier: () -> FakeSession = { FakeSession() }
        var createGate: CountDownLatch? = null
        var createExited: CountDownLatch? = null

        val createdEndpointIds = mutableListOf<EndpointId>()
        val removedDeviceIds = mutableListOf<DeviceId>()
        val sessions = mutableListOf<FakeSession>()

        /** 工厂生命周期收尾转发计数：控制器每次 close 都转发一次。 */
        var closeCount = 0

        override fun close() {
            closeCount++
        }

        override fun devices(): List<Device> = devices

        override fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession {
            try {
                createGate?.await()
                createFailure?.let { throw it }
                synchronized(createdEndpointIds) { createdEndpointIds.add(endpoint.id) }
                return sessionSupplier().also { synchronized(sessions) { sessions.add(it) } }
            } finally {
                createExited?.countDown()
            }
        }

        override fun removeDevice(deviceId: DeviceId) {
            removedDeviceIds.add(deviceId)
            devices = devices.filter { it.id != deviceId }
        }

        override fun archivePairing(
            pairing: com.dshbox.remote.parse.ParsedPairing,
            displayName: String?,
            choices: List<com.dshbox.remote.model.EndpointChoice>?,
            target: com.dshbox.remote.model.PairingTarget,
        ): Device = throw UnsupportedOperationException("pairing is not exercised in this test")

        /** 采集显式模式选择的登记位（编辑链路透传一致性用）。 */
        var lastRequestedSessionMode: String? = null

        /**
         * 端点对齐与生产工厂共用同一对齐器（显式选择优先于载荷、收口口径
         * 一致）；仅更新触发端点的地址与模式（本测试不驱动兄弟端点语义）。
         */
        override fun rePairEndpoint(
            deviceId: DeviceId,
            endpointId: EndpointId,
            pairing: com.dshbox.remote.parse.ParsedPairing,
            requestedSessionMode: String?,
        ): Set<EndpointId> {
            lastRequestedSessionMode = requestedSessionMode
            val current = devices.firstOrNull { it.id == deviceId } ?: return emptySet()
            val outcome = com.dshbox.remote.session.RePairAligner.align(
                endpoints = current.endpoints,
                triggeredId = endpointId,
                pairing = pairing,
                currentCredential = { null },
                currentCaCertificate = { null },
                requestedSessionMode = requestedSessionMode,
            ) ?: return emptySet()
            devices = devices.map { device ->
                if (device.id == deviceId) device.copy(endpoints = outcome.endpoints) else device
            }
            return outcome.changedEndpointIds
        }

        override fun moveEndpoint(fromDeviceId: com.dshbox.remote.model.DeviceId, endpointId: EndpointId, toDeviceId: com.dshbox.remote.model.DeviceId): Boolean = false

        override fun removeEndpoint(deviceId: com.dshbox.remote.model.DeviceId, endpointId: EndpointId): Boolean = false

        override fun pendingCredentialEndpoints(): Set<EndpointId> = emptySet()
    }

    private lateinit var scope: CoroutineScope
    private lateinit var factory: FakeSessionFactory

    private val device = Device(
        id = DeviceId("dev-1"),
        name = "工作电脑",
        endpoints = listOf(
            Endpoint(EndpointId("ep-1"), "局域网", "http://192.168.1.5:3081", "generic"),
            Endpoint(EndpointId("ep-2"), "公网", "http://gw.example.com", "generic"),
        ),
        addedAtMillis = 1_700_000_000_000,
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        factory = FakeSessionFactory().apply { devices = listOf(device) }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(): RemoteConnectionController =
        RemoteConnectionController(scope, object : RemoteConnectionHost {
            override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
        }, sessionFactoryOverride = factory, ioDispatcher = Dispatchers.Unconfined)

    @Test fun selectEndpointReachesReadyAndStartsProxySession() {
        val controller = controller()
        controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-1"))
        assertEquals(SessionState.Ready, controller.sessionStates.value[EndpointId("ep-1")])
        assertTrue(factory.sessions.single().started)
        assertEquals(DisplayTarget.Session(EndpointId("ep-1")), controller.activeDisplay.value)
    }

    @Test fun selectLocalReleasesSession() {
        val controller = controller()
        val ep = EndpointId("ep-1")
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        controller.selectLocal()
        assertEquals(SessionState.Released, controller.sessionStates.value[ep])
        assertTrue(factory.sessions.single().stopped)
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
    }

    @Test fun switchingEndpointReleasesPreviousOne() {
        val controller = controller()
        controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-1"))
        controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-2"))
        assertEquals(SessionState.Released, controller.sessionStates.value[EndpointId("ep-1")])
        assertEquals(SessionState.Ready, controller.sessionStates.value[EndpointId("ep-2")])
        assertTrue(factory.sessions[0].stopped)
        assertTrue(factory.sessions[1].started)
    }

    @Test fun poolExhaustionTurnsSessionFailedWithReason() {
        factory.createFailure = SlotAllocationException.PoolExhausted("pool exhausted")
        val controller = controller()
        val ep = EndpointId("ep-1")
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertEquals(SessionState.Failed, controller.sessionStates.value[ep])
        assertEquals("pool exhausted", controller.sessionFailureReasons.value[ep])
        assertTrue(factory.sessions.isEmpty())
    }

    @Test fun proxyStartFailureTurnsSessionFailed() {
        factory.createFailure = SessionStartException("Failed to bind loopback proxy")
        val controller = controller()
        val ep = EndpointId("ep-1")
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertEquals(SessionState.Failed, controller.sessionStates.value[ep])
        assertTrue(controller.sessionFailureReasons.value.getValue(ep).contains("bind"))
    }

    @Test fun unknownEndpointTurnsFailed() {
        val controller = controller()
        val ep = EndpointId("ghost")
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertEquals(SessionState.Failed, controller.sessionStates.value[ep])
    }

    @Test fun removeDeviceStopsSessionsAndClearsArchive() {
        val controller = controller()
        val ep = EndpointId("ep-1")
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        controller.removeDevice(DeviceId("dev-1"))
        assertTrue(factory.sessions.single().stopped)
        assertEquals(listOf(DeviceId("dev-1")), factory.removedDeviceIds)
        assertTrue(controller.devices.value.isEmpty())
        // 删除设备 = 会话终止：存档与槽位销毁，不可恢复
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep])
    }

    @Test fun withoutSessionFactoryKeepsSkeletonBehavior() {
        val controller = RemoteConnectionController(scope, object : RemoteConnectionHost {
            override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
        })
        controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-1"))
        assertTrue(controller.sessionStates.value.isEmpty())
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
        assertTrue(controller.devices.value.isEmpty())
    }

    @Test
    fun closeForwardsFactoryLifecycleCloseIdempotently() {
        // 门面 close 转发工厂生命周期收尾：控制器自身无状态副作用（重复
        // close 不抛异常、不重复释放会话），转发语义按计数锁定——控制器每
        // 次 close 都原样转发一次工厂 close（工厂无资源时为默认空实现，重复
        // close 是安全 no-op），两次控制器
        // close → 转发两次。
        val controller = controller()
        assertEquals(0, factory.closeCount)
        controller.close()
        assertEquals(1, factory.closeCount)
        controller.close()
        assertEquals(2, factory.closeCount)
        assertEquals(0, factory.sessions.size)
    }

    /** 真实 IO 调度器的控制器：并发与在途场景用，建立结果经轮询等待。 */
    private fun asyncController(): RemoteConnectionController =
        RemoteConnectionController(scope, object : RemoteConnectionHost {
            override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
        }, sessionFactoryOverride = factory, ioDispatcher = Dispatchers.IO)

    private fun awaitReady(controller: RemoteConnectionController, ep: EndpointId): SessionState {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            controller.sessionStates.value[ep]?.let { if (it != SessionState.Connecting) return it }
            Thread.sleep(10)
        }
        return controller.sessionStates.value[ep] ?: SessionState.Connecting
    }

    private fun awaitStopped(session: FakeSession) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!session.stopped && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    /** 轮询等待指定状态（Connecting 现由登记协程在锁内写，经短暂延迟可达）。 */
    private fun awaitState(controller: RemoteConnectionController, ep: EndpointId, expected: SessionState) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (controller.sessionStates.value[ep] == expected) return
            Thread.sleep(5)
        }
        assertEquals(expected, controller.sessionStates.value[ep])
    }

    @Test fun concurrentSelectEndpointCreatesOnlyOneSession() {
        val controller = asyncController()
        val ep = EndpointId("ep-1")
        val startGate = CountDownLatch(1)
        val threads = (0 until 8).map {
            Thread {
                startGate.await()
                controller.selectEndpoint(DeviceId("dev-1"), ep)
            }
        }
        threads.forEach { it.start() }
        startGate.countDown()
        threads.forEach { it.join() }

        assertEquals(SessionState.Ready, awaitReady(controller, ep))
        assertEquals(1, factory.sessions.size)
        assertTrue(factory.sessions.single().started)
    }

    @Test fun removeDeviceDuringInFlightStartLeavesNoOrphan() {
        val controller = asyncController()
        val ep = EndpointId("ep-1")
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        factory.sessionSupplier = {
            FakeSession(startGate = releaseStart, startEntered = startEntered)
        }
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertTrue(startEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(SessionState.Connecting, controller.sessionStates.value[ep])

        // start 在途时删除设备：设备作废登记覆盖在途建立，完成回填前被拒收
        controller.removeDevice(DeviceId("dev-1"))
        // 删除设备 = 会话终止（终态不可逆）：在途建立一并覆盖
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep])
        releaseStart.countDown()

        awaitStopped(factory.sessions.single())
        assertTrue(factory.sessions.single().stopped)
        // 不复活：无 Ready 状态回填，终止态不被推翻
        assertEquals(listOf(DeviceId("dev-1")), factory.removedDeviceIds)
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep])
    }

    @Test fun selectLocalDuringInFlightStartReleasesInFlightSession() {
        val controller = asyncController()
        val ep = EndpointId("ep-1")
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        factory.sessionSupplier = {
            FakeSession(startGate = releaseStart, startEntered = startEntered)
        }
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertTrue(startEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))

        // 显示切回本机：在途 start 完成后按策略释放口径收尾，不回填不置 Ready
        controller.selectLocal()
        releaseStart.countDown()

        awaitStopped(factory.sessions.single())
        assertTrue(factory.sessions.single().stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep])
    }

    @Test fun pendingStartMarkerNotLeakedWhenScopeAlreadyCancelled() {
        // scope 已取消：建立协程体不会执行，在途标记必须不被登记（否则该
        // 端点此后永远无法建立会话）
        scope.cancel()
        val controller = controller()
        controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-1"))
        assertEquals(0, controller.pendingStartCountForTest())
    }

    @Test fun removeDeviceDuringCreateFailureLeavesNoFailedState() {
        // createSession 在途失败时设备已删除：状态随删除清理，catch 路径
        // 不得把 Failed 写回已删端点
        val controller = asyncController()
        val ep = EndpointId("ep-1")
        factory.createFailure = SessionStartException("vault destroyed")
        factory.createGate = CountDownLatch(1)
        factory.createExited = CountDownLatch(1)
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        awaitState(controller, ep, SessionState.Connecting)

        controller.removeDevice(DeviceId("dev-1"))
        // 删除设备 = 会话终止（终态不可逆）
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep])
        factory.createGate!!.countDown()
        assertTrue(factory.createExited!!.await(10, java.util.concurrent.TimeUnit.SECONDS))

        // 失败路径若违规置 Failed 会在此窗口内出现；保持终止态即通过
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (controller.sessionStates.value[ep] != SessionState.Terminated) {
                throw AssertionError("Failed state was written back for removed device")
            }
            Thread.sleep(20)
        }
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep])
    }

    @Test fun orphanBackfillClearsResidualConnectingState() {
        // 在途 start 期间删除设备后又触发「端点不在档」失败，回填拒收时
        // 必须把残留的 Connecting/Failed 状态一并清理
        val controller = asyncController()
        val ep = EndpointId("ep-1")
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        factory.sessionSupplier = {
            FakeSession(startGate = releaseStart, startEntered = startEntered)
        }
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertTrue(startEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        controller.removeDevice(DeviceId("dev-1"))
        // 设备已删：再次选择走「端点不在档」分支，置 Failed
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertEquals(SessionState.Failed, controller.sessionStates.value[ep])

        releaseStart.countDown()
        awaitStopped(factory.sessions.single())
        // 回填命中设备作废登记：代理停止，端点收敛到 Terminated 终态
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            controller.sessionStates.value[ep] != SessionState.Terminated
        ) {
            Thread.sleep(10)
        }
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun modeSwitchDuringInFlightStartDiscardsBackfillViaGenerationDrift() {
        // 编辑链路（同 URL 显式改档）在建立在途时自增代次：回填的代次快照
        // 判定命中漂移，旧模式的建立结果不落账（状态清理 + 代理停止），不
        // 给旧会话在新档挂账
        val controller = asyncController()
        val ep = EndpointId("ep-1")
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        factory.sessionSupplier = {
            FakeSession(startGate = releaseStart, startEntered = startEntered)
        }
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertTrue(startEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(SessionState.Connecting, controller.sessionStates.value[ep])

        // 同 URL 显式改直连（地址变更使非幂等）：代次自增（老请求按代次漂移
        // 废弃的前提）
        val updateResult = kotlinx.coroutines.runBlocking {
            controller.updateEndpointTarget(
                DeviceId("dev-1"),
                ep,
                "https://192.168.1.5:3081/",
                com.dshbox.remote.model.SessionMode.DIRECT.name,
            )
        }
        assertEquals(
            RemoteConnectionController.EndpointUpdateResult.UPDATED,
            updateResult,
        )
        val archived = controller.devices.value.single().endpoints.first { it.id == ep }
        assertEquals(1L, archived.generation)
        assertEquals(
            com.dshbox.remote.model.SessionMode.DIRECT.name,
            archived.sessionMode,
        )
        // 在途建立不经释放路径：由回填的代次漂移判定废弃（下方等待其清空）

        releaseStart.countDown()
        awaitStopped(factory.sessions.single())
        // 回填判定：代次漂移 → 状态清理（无残留 Connecting/Ready），代理停止
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && controller.sessionStates.value[ep] != null) {
            Thread.sleep(10)
        }
        assertNull(controller.sessionStates.value[ep])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun backfillRejectsWhenDeviceMissingWithoutVoiding() {
        // 设备从清单移除且无作废登记（模拟建档刷新路径的清单快照更新）：
        // 回填的在档重查分支必须拒收——代理停止、残留状态清理、不置 Ready
        val controller = asyncController()
        val ep = EndpointId("ep-1")
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        factory.sessionSupplier = {
            FakeSession(startGate = releaseStart, startEntered = startEntered)
        }
        controller.selectEndpoint(DeviceId("dev-1"), ep)
        assertTrue(startEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        awaitState(controller, ep, SessionState.Connecting)
        // 不经 removeDevice（无作废登记），直接把设备移出清单快照
        controller.applyDevicesSnapshotForTest(emptyList())
        assertTrue(factory.removedDeviceIds.isEmpty())

        releaseStart.countDown()
        awaitStopped(factory.sessions.single())
        assertTrue(factory.sessions.single().stopped)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && controller.sessionStates.value[ep] != null) {
            Thread.sleep(10)
        }
        assertNull(controller.sessionStates.value[ep])
    }

    @Test fun concurrentSelectEndpointNeverLeavesConnectingWithoutSession() {
        // 状态写序竞态回归：Connecting 只由登记方在锁内写，重复选择不会在
        // Ready/清理之后补写 Connecting——终态不可能残留「Connecting 且无
        // 会话」。多轮迭代放大调度窗口；每轮收敛后断言终态一致。
        repeat(50) {
            val iterFactory = FakeSessionFactory().apply { devices = listOf(device) }
            val iterScope = CoroutineScope(Dispatchers.IO)
            try {
                val controller = RemoteConnectionController(
                    iterScope,
                    object : RemoteConnectionHost {
                        override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
                    },
                    sessionFactoryOverride = iterFactory,
                    ioDispatcher = Dispatchers.IO,
                )
                val ep = EndpointId("ep-1")
                val startGate = CountDownLatch(1)
                val threads = (0 until 4).map {
                    Thread {
                        startGate.await()
                        controller.selectEndpoint(DeviceId("dev-1"), ep)
                    }
                }
                threads.forEach { it.start() }
                startGate.countDown()
                threads.forEach { it.join() }

                // 先等登记落账（首个竞胜协程写入 Connecting）：在途标记在协程
                // 启动前本就是 0，直接等它清零会在登记前空转过早断言
                val registrationDeadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < registrationDeadline &&
                    controller.sessionStates.value[ep] == null
                ) {
                    Thread.sleep(5)
                }
                // 静默收敛：在途标记全部释放（建立流程已走出 finally）
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline && controller.pendingStartCountForTest() > 0) {
                    Thread.sleep(5)
                }
                assertEquals(0, controller.pendingStartCountForTest())
                assertEquals(SessionState.Ready, controller.sessionStates.value[ep])
                assertEquals(1, iterFactory.sessions.size)
                assertTrue(iterFactory.sessions.single().started)
            } finally {
                iterScope.cancel()
            }
        }
    }

    @Test fun editWithSessionModeSelectionLandsSelectedModeInArchive() {
        // 编辑带模式选择（复用重配对对齐链路）：所选档位经对齐器收口后
        // 原子落进档案（代理档端点 + 用户选直连、载荷/裸地址不声明 → 直连）
        val controller = RemoteConnectionController(
            scope,
            object : RemoteConnectionHost {
                override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
            },
            sessionFactoryOverride = factory,
            registry = com.dshbox.remote.parse.ParserRegistry(
                urlParsers = com.dshbox.remote.parse.p0UrlParsers() + listOf(
                    com.dshbox.remote.parse.AnonRtParser(),
                    com.dshbox.remote.parse.ZcodeRemoteParser(),
                    com.dshbox.remote.parse.DsHarnessRemoteParser(),
                ),
                schemeParsers = listOf(com.dshbox.remote.parse.DshRemoteSchemeParser()),
                jsonParsers = listOf(com.dshbox.remote.parse.SayaChJsonParser()),
                delimitedParsers = listOf(com.dshbox.remote.parse.ChongYepDelimitedParser()),
            ),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val result = kotlinx.coroutines.runBlocking {
            controller.updateEndpointTarget(
                DeviceId("dev-1"),
                EndpointId("ep-1"),
                "https://new-tunnel.example.com/",
                com.dshbox.remote.model.SessionMode.DIRECT.name,
            )
        }
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, result)
        // 显式选择原样透传到对齐器（用户选择高于载荷）
        assertEquals(
            com.dshbox.remote.model.SessionMode.DIRECT.name,
            factory.lastRequestedSessionMode,
        )
        val archived = controller.devices.value.single().endpoints.first { it.id == EndpointId("ep-1") }
        assertEquals(com.dshbox.remote.model.SessionMode.DIRECT.name, archived.sessionMode)
        assertEquals("https://new-tunnel.example.com/", archived.targetUrl)
        assertEquals(1L, archived.generation)
    }

    // ---- 直连下载的同源校验（downloadDirect） ----

    private lateinit var tempDownloadDir: File

    /** 下载目录宿主（目录指向 [tempDownloadDir]，测试各自赋值后使用）。 */
    private val directHost: RemoteConnectionHost
        get() = object : RemoteConnectionHost {
            override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
            override fun downloadDirectory(): File = tempDownloadDir
        }

    /** 下载事件（最后一条通知）的同步读取（Unconfined 调度内联完成）。 */
    private fun lastDownloadNotice(controller: RemoteConnectionController) =
        controller.downloadEvents.value

    /** 会话携带真实上游 origin 的假工厂：直连下载同源判定的落账前提。 */
    private class UpstreamSessionFactory(
        devices: List<Device>,
        private val upstreamOrigin: String,
    ) : SessionFactory {
        var devices: List<Device> = devices
        override fun devices(): List<Device> = devices
        override fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession =
            object : ProxySession {
                override val loopbackOrigin = "http://127.0.0.9:51999"
                override val upstreamOrigin: String = this@UpstreamSessionFactory.upstreamOrigin
                override fun start() {}
                override fun stop() {}
            }

        override fun removeDevice(deviceId: DeviceId) {
            devices = devices.filter { it.id != deviceId }
        }
    }

    @Test fun downloadDirectRejectsCrossOriginUrls() {
        tempDownloadDir = java.nio.file.Files.createTempDirectory("dl-xorigin").toFile()
        val upstreamFactory = UpstreamSessionFactory(listOf(device), upstreamOrigin = "https://zcode.z.ai")
        val controller = RemoteConnectionController(
            scope,
            directHost,
            sessionFactoryOverride = upstreamFactory,
            ioDispatcher = Dispatchers.Unconfined,
        )
        // 会话先就绪：上游 origin 已落账（同源判定基准）
        controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-1"))
        assertEquals(SessionState.Ready, controller.sessionStates.value[EndpointId("ep-1")])
        assertEquals("https://zcode.z.ai", controller.sessionUpstreamOrigins.value[EndpointId("ep-1")])
        fun request(url: String) = com.dshbox.dsh.renderer.api.DshDownloadRequest(
            url = url,
            userAgent = null,
            contentDisposition = null,
            mimeType = null,
        )
        // 事后清理占位文件
        fun cleanup() = tempDownloadDir.listFiles()?.forEach { it.delete() }

        // scheme 不同（同 host）：跨源，拒收
        controller.downloadDirect(EndpointId("ep-1"), request("http://zcode.z.ai/file.bin"))
        assertFalse(lastDownloadNotice(controller).success)
        cleanup()
        // 端口不同（同 host 同 scheme）：跨源，拒收
        controller.downloadDirect(EndpointId("ep-1"), request("https://zcode.z.ai:8443/file.bin"))
        assertFalse(lastDownloadNotice(controller).success)
        cleanup()
        // host 不同：跨源，拒收
        controller.downloadDirect(EndpointId("ep-1"), request("https://evil.example.com/file.bin"))
        assertFalse(lastDownloadNotice(controller).success)
        cleanup()
        // 回环门禁/本机地址：非上游 origin，拒收
        controller.downloadDirect(EndpointId("ep-1"), request("http://127.0.0.1:9/file.bin"))
        assertFalse(lastDownloadNotice(controller).success)
        cleanup()
        // 畸形 URL：拒收
        controller.downloadDirect(EndpointId("ep-1"), request("not a url"))
        assertFalse(lastDownloadNotice(controller).success)
        cleanup()
        // 全部拒收后目录仍为空（未发起任何网络请求、未占位任何文件）
        assertTrue(tempDownloadDir.listFiles().isNullOrEmpty())
    }

    @Test fun downloadDirectAcceptsSameOriginUrl() {
        // 内嵌 http 上游 + 会话上游指向它：同 origin 请求应占位并完成落盘
        val upstream = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0),
            0,
        )
        try {
            upstream.createContext("/file.bin") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                val body = "DIRECT-OK".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            upstream.start()
            val port = upstream.address.port
            val dir = java.nio.file.Files.createTempDirectory("dl-sameorigin").toFile()
            tempDownloadDir = dir
            val host = object : RemoteConnectionHost {
                override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
                override fun downloadDirectory(): File = dir
            }
            val upstreamFactory = UpstreamSessionFactory(
                listOf(device),
                upstreamOrigin = "http://127.0.0.1:$port",
            )
            val controller = RemoteConnectionController(
                scope,
                host,
                sessionFactoryOverride = upstreamFactory,
                ioDispatcher = Dispatchers.Unconfined,
            )
            controller.selectEndpoint(DeviceId("dev-1"), EndpointId("ep-1"))
            assertEquals(SessionState.Ready, controller.sessionStates.value[EndpointId("ep-1")])
            controller.downloadDirect(
                EndpointId("ep-1"),
                com.dshbox.dsh.renderer.api.DshDownloadRequest(
                    url = "http://127.0.0.1:$port/file.bin",
                    userAgent = null,
                    contentDisposition = null,
                    mimeType = null,
                ),
            )
            val notice = lastDownloadNotice(controller)
            assertEquals(true, notice.success)
            val saved = dir.listFiles()!!.single()
            assertEquals("file.bin", saved.name)
            assertEquals("DIRECT-OK", saved.readText())
        } finally {
            upstream.stop(0)
        }
    }

    @Test fun downloadDirectWithoutLiveSessionUpstreamFails() {
        tempDownloadDir = java.nio.file.Files.createTempDirectory("dl-nosession").toFile()
        val controller = RemoteConnectionController(
            scope,
            directHost,
            sessionFactoryOverride = factory,
            ioDispatcher = Dispatchers.Unconfined,
        )
        // 端点无在册会话（无上游 origin 落账）：任何 URL 一律拒收
        controller.downloadDirect(
            EndpointId("ep-1"),
            com.dshbox.dsh.renderer.api.DshDownloadRequest(
                url = "https://zcode.z.ai/file.bin",
                userAgent = null,
                contentDisposition = null,
                mimeType = null,
            ),
        )
        assertFalse(lastDownloadNotice(controller).success)
        assertTrue(tempDownloadDir.listFiles().isNullOrEmpty())
    }
}
