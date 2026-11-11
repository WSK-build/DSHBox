package com.dshbox.remote

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.SessionMode
import com.dshbox.remote.model.SessionState
import com.dshbox.remote.parse.ParserIds
import com.dshbox.remote.session.DirectSession
import com.dshbox.remote.session.ForegroundElapsedClock
import com.dshbox.remote.session.ProxySession
import com.dshbox.remote.session.SessionFactory
import com.dshbox.remote.ui.parserProductLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 直连会话模式（端点 SessionMode.DIRECT）：不建回环代理的会话语义、origin
 * 流的真实上游值、门禁 bypass、模式切换的释放收口与确认卡类型标签。
 */
class RemoteConnectionDirectSessionTest {

    private class FakeProxiedSession : ProxySession {
        var started = false
        var stopped = false
        override val loopbackOrigin: String = "http://127.0.0.3:51000"
        override val upstreamOrigin: String? = "https://gw.example.com"
        override fun start() {
            started = true
        }
        override fun stop() {
            stopped = true
        }
    }

    /** 模式感知假工厂：DIRECT 端点返回无代理会话（不建回环代理），其余走假代理。 */
    private class ModeAwareFactory : SessionFactory, com.dshbox.remote.session.PairingArchiver {
        var devices: List<Device> = emptyList()
        val createdEndpoints = mutableListOf<Endpoint>()
        val createdSessions = mutableListOf<ProxySession>()

        override fun devices(): List<Device> = devices

        override fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession {
            createdEndpoints.add(endpoint)
            return (if (endpoint.sessionMode == SessionMode.DIRECT.name) {
                // 直连：会话对象本身即「无代理」形态（start/stop 为空操作）
                DirectSession(
                    entryUrl = endpoint.targetUrl,
                    upstreamOrigin = "https://zcode.z.ai",
                )
            } else {
                FakeProxiedSession()
            }).also { createdSessions.add(it) }
        }

        override fun removeDevice(deviceId: DeviceId) {
            devices = devices.filter { it.id != deviceId }
        }

        /**
         * 端点对齐与生产工厂共用同一对齐器（显式选择的优先级、收口口径与
         * 代次推进一致）；只按触发端点更新地址/模式，不驱动凭据存档语义。
         */
        override fun rePairEndpoint(
            deviceId: DeviceId,
            endpointId: EndpointId,
            pairing: com.dshbox.remote.parse.ParsedPairing,
            requestedSessionMode: String?,
        ): Set<EndpointId> {
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

        override fun archivePairing(
            pairing: com.dshbox.remote.parse.ParsedPairing,
            displayName: String?,
            choices: List<com.dshbox.remote.model.EndpointChoice>?,
            target: com.dshbox.remote.model.PairingTarget,
        ): Device = throw UnsupportedOperationException("pairing is not exercised in this test")

        override fun moveEndpoint(fromDeviceId: com.dshbox.remote.model.DeviceId, endpointId: EndpointId, toDeviceId: com.dshbox.remote.model.DeviceId): Boolean = false

        override fun removeEndpoint(deviceId: com.dshbox.remote.model.DeviceId, endpointId: EndpointId): Boolean = false

        override fun pendingCredentialEndpoints(): Set<EndpointId> = emptySet()
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
    private lateinit var factory: ModeAwareFactory

    private val directEndpoint = Endpoint(
        id = EndpointId("ep-direct"),
        channelLabel = "PUBLIC",
        targetUrl = "https://zcode.z.ai/remote/abc",
        parserId = ParserIds.ZCODE_REMOTE,
        sessionMode = SessionMode.DIRECT.name,
    )
    private val proxiedEndpoint = Endpoint(
        id = EndpointId("ep-proxy"),
        channelLabel = "LAN",
        targetUrl = "https://gw.example.com",
        parserId = ParserIds.GENERIC,
    )
    private val device = Device(
        id = DeviceId("dev-1"),
        name = "account-dev",
        endpoints = listOf(directEndpoint, proxiedEndpoint),
        addedAtMillis = 1_700_000_000_000,
    )

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        factory = ModeAwareFactory().apply { devices = listOf(device) }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(): RemoteConnectionController = RemoteConnectionController(
        scope,
        object : RemoteConnectionHost {
            override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
        },
        sessionFactoryOverride = factory,
        registry = com.dshbox.remote.parse.ParserRegistry(
            urlParsers = listOf(com.dshbox.remote.parse.ZcodeRemoteParser()),
        ),
        ioDispatcher = Dispatchers.Unconfined,
    )

    // ---- DirectSession 语义 ----

    @Test fun directSessionCarriesRealOriginEntryAndNoGate() {
        val session = DirectSession(
            entryUrl = "https://zcode.z.ai/remote/abc",
            upstreamOrigin = "https://zcode.z.ai",
        )
        assertEquals("https://zcode.z.ai", session.loopbackOrigin)
        assertEquals("https://zcode.z.ai", session.upstreamOrigin)
        assertEquals("https://zcode.z.ai/remote/abc", session.initialLoadUrl)
        assertNull(session.gateToken)
        // 空操作启停：可重复调用且无副作用（无代理监听可启停）
        session.start()
        session.start()
        session.stop()
    }

    @Test fun directSessionRejectsBlankOrigin() {
        // 模型层第二道防线：空白 origin 不得进入会话账目——loopbackOrigin 以
        // upstreamOrigin 为渲染器可信基准，空值会让会话以空白可渲染值落账
        assertThrows(IllegalArgumentException::class.java) {
            DirectSession(
                entryUrl = "https://zcode.z.ai/remote/abc",
                upstreamOrigin = " ",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectSession(
                entryUrl = "https://zcode.z.ai/remote/abc",
                upstreamOrigin = null,
            )
        }
    }

    // ---- 直连端点的建立与 origin 流 ----

    @Test fun directEndpointSessionFallsThroughToNoProxySession() {
        val controller = controller()
        controller.selectEndpoint(DeviceId("dev-1"), directEndpoint.id)
        assertEquals(SessionState.Ready, controller.sessionStates.value[directEndpoint.id])
        // 建立请求按档案会话模式分流（工厂收到 DIRECT 端点）
        assertEquals(directEndpoint, factory.createdEndpoints.single())
        // origin 流取真实上游 origin（非回环）：可渲染判定与页内链接分流共用
        assertEquals(
            "https://zcode.z.ai",
            controller.sessionOrigins.value[directEndpoint.id],
        )
        assertEquals(
            "https://zcode.z.ai",
            controller.sessionUpstreamOrigins.value[directEndpoint.id],
        )
        // 直连无门禁（bypass 路径）；首载地址即真实入口
        assertNull(controller.sessionGateToken(directEndpoint.id))
        assertEquals(
            "https://zcode.z.ai/remote/abc",
            controller.sessionInitialLoadUrl(directEndpoint.id),
        )
        assertTrue(controller.sessionIsDirect(directEndpoint.id))
        assertFalse(controller.sessionIsDirect(proxiedEndpoint.id))
    }

    @Test fun proxiedEndpointKeepsLoopbackOriginsAndGate() {
        val controller = controller()
        controller.selectEndpoint(DeviceId("dev-1"), proxiedEndpoint.id)
        assertEquals(SessionState.Ready, controller.sessionStates.value[proxiedEndpoint.id])
        assertEquals(
            "http://127.0.0.3:51000",
            controller.sessionOrigins.value[proxiedEndpoint.id],
        )
        assertEquals(
            "https://gw.example.com",
            controller.sessionUpstreamOrigins.value[proxiedEndpoint.id],
        )
    }

    // ---- 模式切换（经编辑链路 updateEndpointTarget）：释放该端点会话，按新档重建 ----

    @Test fun modeSwitchReleasesLiveSessionUpdatesArchiveAndStopsProxy() {
        val controller = controller()
        controller.selectEndpoint(DeviceId("dev-1"), proxiedEndpoint.id)
        assertEquals(SessionState.Ready, controller.sessionStates.value[proxiedEndpoint.id])

        val result = kotlinx.coroutines.runBlocking {
            // 同 URL 显式改档：地址未动、模式真变更 → 更新分支
            controller.updateEndpointTarget(
                DeviceId("dev-1"),
                proxiedEndpoint.id,
                "https://gw.example.com",
                SessionMode.DIRECT.name,
            )
        }
        assertEquals(
            RemoteConnectionController.EndpointUpdateResult.UPDATED,
            result,
        )
        // 在用会话随切换释放（Released），代理停止被真正调用（编辑链路的
        // 释放不切显示指向：显示槽按新档回场重建，与显式释放入口不同——
        // 「回本机」是 releaseNow 的口径，不在本测试断言范围）
        assertEquals(SessionState.Released, controller.sessionStates.value[proxiedEndpoint.id])
        assertNull(controller.sessionOrigins.value[proxiedEndpoint.id])
        assertNull(controller.sessionUpstreamOrigins.value[proxiedEndpoint.id])
        val created = factory.createdSessions.single()
        assertTrue(created is FakeProxiedSession)
        assertTrue((created as FakeProxiedSession).stopped)
        // 档案即时更新（工厂内清单）：直连判定随新档生效，代次同步自增
        assertTrue(controller.sessionIsDirect(proxiedEndpoint.id))
        val endpoint = controller.devices.value.single().endpoints.first { it.id == proxiedEndpoint.id }
        assertEquals(SessionMode.DIRECT.name, endpoint.sessionMode)
        assertEquals(1L, endpoint.generation)
    }

    @Test fun modeSwitchBackToProxiedClearsDirectFlag() {
        val controller = controller()
        kotlinx.coroutines.runBlocking {
            // 同 URL 显式改回代理档：对齐结果经口径收口后落档案
            controller.updateEndpointTarget(
                DeviceId("dev-1"),
                directEndpoint.id,
                "https://zcode.z.ai/remote/abc",
                SessionMode.PROXIED.name,
            )
        }
        assertFalse(controller.sessionIsDirect(directEndpoint.id))
        val endpoint = controller.devices.value.single().endpoints.first { it.id == directEndpoint.id }
        assertEquals(1L, endpoint.generation)
    }

    @Test fun modeSwitchIsIdempotentWhenTargetEqualsArchive() {
        val controller = controller()
        val result = kotlinx.coroutines.runBlocking {
            // 直连档案空确认（显式 DIRECT 透传）：模式与既有档一致即为无变更
            // ——不推进代次、不清空直连标记、不打断任何会话
            controller.updateEndpointTarget(
                DeviceId("dev-1"),
                directEndpoint.id,
                "https://zcode.z.ai/remote/abc",
                SessionMode.DIRECT.name,
            )
        }
        assertEquals(
            RemoteConnectionController.EndpointUpdateResult.UNCHANGED,
            result,
        )
        val endpoint = controller.devices.value.single().endpoints.first { it.id == directEndpoint.id }
        assertEquals(SessionMode.DIRECT.name, endpoint.sessionMode)
        assertEquals(0L, endpoint.generation)
        assertTrue(controller.sessionIsDirect(directEndpoint.id))
    }

    @Test fun modeSwitchSkipsReleaseWhenNoSessionInProgressOrLive() {
        val controller = controller()
        // 从未建立的端点（无在册会话、无在途建立）：改档只写档案，不置任何
        // 会话态（覆盖层/状态表中不出现无主 Released），显示指向不动
        kotlinx.coroutines.runBlocking {
            controller.updateEndpointTarget(
                DeviceId("dev-1"),
                proxiedEndpoint.id,
                "https://gw.example.com",
                SessionMode.DIRECT.name,
            )
        }
        assertEquals(
            SessionMode.DIRECT.name,
            controller.devices.value.single().endpoints.first { it.id == proxiedEndpoint.id }.sessionMode,
        )
        assertTrue(controller.sessionStates.value.isEmpty())
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
    }

    // ---- 确认卡类型标签 ----

    @Test fun confirmCardTypeLabelsCoverDirectAccountParsers() {
        assertEquals(
            "ZCode 工作区",
            parserProductLabel(ParserIds.ZCODE_REMOTE),
        )
        assertEquals(
            "DSH Remote",
            parserProductLabel(ParserIds.DS_HARNESS_REMOTE),
        )
        // 未标注解析器不产出专名（generic/未知走资源或原样标识）
        assertNull(parserProductLabel(ParserIds.GENERIC))
        assertNull(parserProductLabel("unknown-parser"))
    }
}
