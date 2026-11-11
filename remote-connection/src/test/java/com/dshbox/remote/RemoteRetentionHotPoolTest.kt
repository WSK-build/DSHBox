package com.dshbox.remote

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.SessionState
import com.dshbox.remote.model.toArchiveValue
import com.dshbox.remote.session.ForegroundElapsedClock
import com.dshbox.remote.session.InMemoryKeyValueStore
import com.dshbox.remote.session.PairingArchiver
import com.dshbox.remote.session.ProxySession
import com.dshbox.remote.session.SessionFactory
import com.dshbox.remote.parse.ParsedPairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 保留档与页面热池测试：端点覆盖优先于全局默认、默认档持久化（重启不丢）、
 * 热池进出（Retain 切走留池 / IMMEDIATE 切走移出 / FOREVER 不因计时移出 /
 * 改档即时移出 / releaseNow 显式移出 / 到期移出）。
 */
class RemoteRetentionHotPoolTest {

    private class FakeSession : ProxySession {
        var stopped = false
        override val loopbackOrigin: String = "http://127.0.0.2:51000"
        override fun start() = Unit
        override fun stop() {
            stopped = true
        }
    }

    /** 同时实现建档出口：端点级保留档覆盖的落盘写入由它记账。 */
    private class FakeArchiverFactory : SessionFactory, PairingArchiver {
        var devices: List<Device> = emptyList()
        val sessions = mutableListOf<FakeSession>()
        val recordedOverrides = mutableListOf<Triple<DeviceId, EndpointId, String?>>()

        override fun devices(): List<Device> = devices

        override fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession =
            FakeSession().also { synchronized(sessions) { sessions.add(it) } }

        override fun removeDevice(deviceId: DeviceId) {
            devices = devices.filter { it.id != deviceId }
        }

        override fun setEndpointRetentionOverride(
            deviceId: DeviceId,
            endpointId: EndpointId,
            retentionOverride: String?,
        ) {
            recordedOverrides.add(Triple(deviceId, endpointId, retentionOverride))
        }

        // 保留档测试不驱动配对建档链路：骨架空实现
        override fun archivePairing(
            pairing: ParsedPairing,
            displayName: String?,
            choices: List<com.dshbox.remote.model.EndpointChoice>?,
            target: com.dshbox.remote.model.PairingTarget,
        ): Device = throw UnsupportedOperationException("pairing is not exercised in this test")

        override fun rePairEndpoint(
            deviceId: DeviceId,
            endpointId: EndpointId,
            pairing: ParsedPairing,
            requestedSessionMode: String?,
        ): Set<EndpointId> = emptySet()

        override fun moveEndpoint(fromDeviceId: com.dshbox.remote.model.DeviceId, endpointId: EndpointId, toDeviceId: com.dshbox.remote.model.DeviceId): Boolean = false

        override fun removeEndpoint(deviceId: com.dshbox.remote.model.DeviceId, endpointId: EndpointId): Boolean = false

        override fun pendingCredentialEndpoints(): Set<EndpointId> = emptySet()
    }

    private lateinit var scope: CoroutineScope
    private lateinit var factory: FakeArchiverFactory

    private val deviceId = DeviceId("dev-1")
    private val ep1 = EndpointId("ep-1")
    private val ep2 = EndpointId("ep-2")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        factory = FakeArchiverFactory().apply {
            devices = listOf(
                Device(
                    id = deviceId,
                    name = "work",
                    endpoints = listOf(
                        Endpoint(ep1, "LAN", "http://192.168.1.5:3081", "generic"),
                        Endpoint(ep2, "PUBLIC", "http://gw.example.com", "generic"),
                    ),
                    addedAtMillis = 1L,
                ),
            )
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(
        clock: ForegroundElapsedClock = ForegroundElapsedClock(),
        settingsStore: InMemoryKeyValueStore? = null,
    ) = RemoteConnectionController(
        scope = scope,
        host = object : RemoteConnectionHost {
            override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
        },
        sessionFactoryOverride = factory,
        ioDispatcher = Dispatchers.Unconfined,
        foregroundClock = clock,
        settingsStore = settingsStore,
    )

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

    // ---- retentionFor：覆盖优先 / 继承默认 / 改默认不影响已覆盖端点 ----

    @Test fun endpointOverrideTakesPrecedenceOverGlobalDefault() {
        val controller = controller()
        controller.setRetentionDefault(RetentionPolicy.Retain(1))
        controller.setEndpointRetention(deviceId, ep1, RetentionPolicy.FOREVER)
        assertEquals(RetentionPolicy.FOREVER, controller.retentionFor(ep1))
        assertEquals(RetentionPolicy.Retain(1), controller.retentionFor(ep2))
    }

    @Test fun changingDefaultDoesNotAffectOverriddenEndpoint() {
        val controller = controller()
        controller.setRetentionDefault(RetentionPolicy.Retain(1))
        controller.setEndpointRetention(deviceId, ep1, RetentionPolicy.IMMEDIATE)
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        assertEquals(RetentionPolicy.IMMEDIATE, controller.retentionFor(ep1))
        assertEquals(RetentionPolicy.Retain(5), controller.retentionFor(ep2))
    }

    @Test fun setEndpointRetentionWritesArchiveFieldAndClearsOnNull() = runBlocking {
        val controller = controller()
        controller.setEndpointRetention(deviceId, ep1, RetentionPolicy.Retain(15))
        assertEquals(
            RetentionPolicy.Retain(15).toArchiveValue(),
            controller.devices.value.single().endpoints.first { it.id == ep1 }.retentionOverride,
        )
        // null = 清除覆盖（继承默认）；落盘写入同步记账（Unconfined）
        controller.setEndpointRetention(deviceId, ep1, null)
        assertNull(controller.devices.value.single().endpoints.first { it.id == ep1 }.retentionOverride)
        assertEquals(
            listOf(
                Triple(deviceId, ep1, RetentionPolicy.Retain(15).toArchiveValue()),
                Triple(deviceId, ep1, null),
            ),
            factory.recordedOverrides,
        )
    }

    // ---- 持久化：内存 store 往返 + 重启（新建控制器）后默认值保持 ----

    @Test fun defaultPolicyPersistsAcrossControllerRecreation() {
        val store = InMemoryKeyValueStore()
        val first = controller(settingsStore = store)
        first.setRetentionDefault(RetentionPolicy.Retain(15))
        assertEquals(RetentionPolicy.Retain(15), first.retention.value)
        // 重启等价：同一设置存储上重建门面，默认档不丢
        val restarted = controller(settingsStore = store)
        assertEquals(RetentionPolicy.Retain(15), restarted.retention.value)
    }

    @Test fun defaultPolicyFallsBackToImmediateWithoutStoredValue() {
        val controller = controller(settingsStore = InMemoryKeyValueStore())
        assertEquals(RetentionPolicy.IMMEDIATE, controller.retention.value)
    }

    // ---- 热池进出 ----

    @Test fun retainKeepsSwitchedAwayEndpointInHotPool() {
        val controller = controller()
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.selectEndpoint(deviceId, ep1)
        controller.selectEndpoint(deviceId, ep2)
        // 切走不释放：旧端点留池（会话在册），新端点在场
        assertEquals(setOf(ep1, ep2), controller.hotEndpoints.value)
        assertFalse(factory.sessions[0].stopped)
        assertEquals(SessionState.Ready, controller.sessionStates.value[ep1])
        // 切回本机：Retain 档依旧留池
        controller.selectLocal()
        assertEquals(setOf(ep1, ep2), controller.hotEndpoints.value)
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
    }

    @Test fun immediateRemovesSwitchedAwayEndpointFromHotPool() {
        val controller = controller()
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal()
        assertTrue(controller.hotEndpoints.value.isEmpty())
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun foreverEndpointIsNeverReleasedByClock() {
        var wall = 0L
        val clock = ForegroundElapsedClock { wall }
        val controller = controller(clock = clock)
        controller.setRetentionDefault(RetentionPolicy.FOREVER)
        controller.onHostResumed()
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal()
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        // 前台时钟推进远超任何分钟档：FOREVER 不因计时移出
        wall += 24 * 60 * 60_000L
        controller.onHostResumed()
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        assertFalse(factory.sessions.single().stopped)
        assertEquals(SessionState.Ready, controller.sessionStates.value[ep1])
    }

    @Test fun switchingDefaultToImmediateReleasesInheritedRetainedEndpoint() {
        val controller = controller()
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal()
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        // 改默认档为 IMMEDIATE：继承默认的保留中会话立即释放并移出热池
        controller.setRetentionDefault(RetentionPolicy.IMMEDIATE)
        assertTrue(controller.hotEndpoints.value.isEmpty())
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun switchingDefaultFromForeverToRetainArmsDeadlinelessInactiveSession() {
        var wall = 0L
        val clock = ForegroundElapsedClock { wall }
        val controller = controller(clock = clock)
        controller.setRetentionDefault(RetentionPolicy.FOREVER)
        controller.onHostResumed()
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal() // FOREVER 期间失活：会话在册但无释放期限
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        wall += 30_000L
        // 改默认 Retain(1)：无期限的继承会话同样被武装倒计时（期限按失活
        // 口径起算），不立即释放也不放任不管
        controller.setRetentionDefault(RetentionPolicy.Retain(1))
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        assertFalse(factory.sessions.single().stopped)
        // 假时钟推进越过期限：回前台补偿判定释放
        wall += 2 * 60_000L
        controller.onHostResumed()
        assertFalse(controller.hotEndpoints.value.contains(ep1))
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun switchingDefaultFromForeverToImmediateReleasesDeadlinelessInactiveSession() {
        val controller = controller()
        controller.setRetentionDefault(RetentionPolicy.FOREVER)
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal() // FOREVER 期间失活：会话在册但无释放期限
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        // 改默认 IMMEDIATE：无期限的继承会话立即释放并移出热池
        controller.setRetentionDefault(RetentionPolicy.IMMEDIATE)
        assertTrue(controller.hotEndpoints.value.isEmpty())
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun clearingOverrideReevaluatesByInheritedDefault() {
        // 场景一：默认 IMMEDIATE → 清除覆盖（null）立即释放在册失活会话
        val immediate = controller()
        immediate.setRetentionDefault(RetentionPolicy.IMMEDIATE)
        immediate.setEndpointRetention(deviceId, ep1, RetentionPolicy.Retain(5))
        immediate.selectEndpoint(deviceId, ep1)
        immediate.selectLocal() // 覆盖档 Retain(5)：失活保留
        assertEquals(setOf(ep1), immediate.hotEndpoints.value)
        immediate.setEndpointRetention(deviceId, ep1, null)
        assertTrue(immediate.hotEndpoints.value.isEmpty())
        assertEquals(SessionState.Released, immediate.sessionStates.value[ep1])
        assertTrue(factory.sessions[0].stopped)

        // 场景二：默认 Retain(1) → 清除覆盖按 1 分钟武装（原失活时刻起算）
        var wall = 0L
        val clock = ForegroundElapsedClock { wall }
        val retain = controller(clock = clock)
        retain.setRetentionDefault(RetentionPolicy.Retain(1))
        retain.setEndpointRetention(deviceId, ep1, RetentionPolicy.Retain(5))
        retain.onHostResumed()
        retain.selectEndpoint(deviceId, ep1)
        retain.selectLocal() // 失活时刻 = 0，覆盖期限 5 分钟
        assertEquals(setOf(ep1), retain.hotEndpoints.value)
        wall += 2 * 60_000L // 推进 2 分钟：覆盖档期限内，不释放
        retain.onHostResumed()
        assertEquals(setOf(ep1), retain.hotEndpoints.value)
        // 清除覆盖：生效档变为继承的 Retain(1)，按原失活时刻起算已超期 → 释放
        retain.setEndpointRetention(deviceId, ep1, null)
        assertTrue(retain.hotEndpoints.value.isEmpty())
        assertEquals(SessionState.Released, retain.sessionStates.value[ep1])
        assertTrue(factory.sessions[1].stopped)
    }

    @Test fun settingEndpointRetentionToImmediateReleasesItAtOnce() {
        val controller = controller()
        controller.setRetentionDefault(RetentionPolicy.FOREVER)
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal()
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        controller.setEndpointRetention(deviceId, ep1, RetentionPolicy.IMMEDIATE)
        assertTrue(controller.hotEndpoints.value.isEmpty())
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun releaseNowRemovesFromHotPoolAndMarksReleased() {
        val controller = controller()
        controller.setRetentionDefault(RetentionPolicy.FOREVER)
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal()
        controller.releaseNow(ep1)
        assertTrue(controller.hotEndpoints.value.isEmpty())
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
        // 释放的是当前显示端点时，显示指向一并切回本机（回场不自动重建）
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
    }

    @Test fun retainedEndpointLeavesHotPoolWhenDeadlineExpires() {
        var wall = 0L
        val clock = ForegroundElapsedClock { wall }
        val controller = controller(clock = clock)
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.onHostResumed()
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal()
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        // 前台时钟推进越过保留期限，回前台补偿判定释放（热池随之移出）
        wall += 6 * 60_000L
        controller.onHostResumed()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline &&
            controller.hotEndpoints.value.contains(ep1)
        ) {
            Thread.sleep(10)
        }
        assertFalse(controller.hotEndpoints.value.contains(ep1))
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun removingDevicePurgesItsEndpointsFromHotPool() {
        val controller = controller()
        controller.setRetentionDefault(RetentionPolicy.FOREVER)
        controller.selectEndpoint(deviceId, ep1)
        controller.selectLocal()
        assertEquals(setOf(ep1), controller.hotEndpoints.value)
        controller.removeDevice(deviceId)
        assertTrue(controller.hotEndpoints.value.isEmpty())
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep1])
    }
}
