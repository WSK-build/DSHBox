package com.dshbox.remote

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.parse.AnonRtParser
import com.dshbox.remote.parse.ChongYepDelimitedParser
import com.dshbox.remote.parse.DshRemoteSchemeParser
import com.dshbox.remote.parse.PairingParseException
import com.dshbox.remote.parse.PairingParser
import com.dshbox.remote.parse.ParserRegistry
import com.dshbox.remote.parse.ParsedPairing
import com.dshbox.remote.parse.SayaChJsonParser
import com.dshbox.remote.parse.p0UrlParsers
import com.dshbox.remote.session.PairingArchiver
import com.dshbox.remote.session.ProxySession
import com.dshbox.remote.session.SessionFactory
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

/**
 * 配对预览测试：扫码/手动输入建档前确认卡的数据形态（各类插件的解析
 * 器/设备名/地址/端点数/generic 标记），并锁定「只解析不建档」的无副作用
 * 契约（建档出口零调用）与畸形/空端点载荷的脱敏失败文案。
 */
class PreviewPairingTest {

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

    /**
     * 假工厂：只承载设备清单存根；建档出口按 [PairingArchiver] 记录调用次数
     * （预览路径零建档调用由计数断言锁定），真被调用即抛（清单快照同时可观测）。
     */
    private class FakeSessionFactory : SessionFactory, PairingArchiver {
        var devices: List<Device> = emptyList()

        /** 建档出口调用计数：预览路径必须为 0。 */
        var archivePairingCalls = 0

        /** 重配对出口调用计数：预览路径必须为 0。 */
        var rePairEndpointCalls = 0

        override fun devices(): List<Device> = devices

        override fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession {
            throw UnsupportedOperationException("sessions are not exercised in this test")
        }

        override fun removeDevice(deviceId: DeviceId) {
            devices = devices.filter { it.id != deviceId }
        }

        override fun archivePairing(
            pairing: ParsedPairing,
            displayName: String?,
            choices: List<com.dshbox.remote.model.EndpointChoice>?,
            target: com.dshbox.remote.model.PairingTarget,
        ): Device {
            archivePairingCalls++
            throw UnsupportedOperationException("archive is not exercised in preview tests")
        }

        override fun rePairEndpoint(
            deviceId: DeviceId,
            endpointId: EndpointId,
            pairing: ParsedPairing,
            requestedSessionMode: String?,
        ): Set<EndpointId> {
            rePairEndpointCalls++
            throw UnsupportedOperationException("re-pair is not exercised in preview tests")
        }

        override fun moveEndpoint(fromDeviceId: com.dshbox.remote.model.DeviceId, endpointId: EndpointId, toDeviceId: com.dshbox.remote.model.DeviceId): Boolean = false

        override fun removeEndpoint(deviceId: com.dshbox.remote.model.DeviceId, endpointId: EndpointId): Boolean = false

        override fun pendingCredentialEndpoints(): Set<EndpointId> = emptySet()
    }

    private lateinit var scope: CoroutineScope
    private lateinit var factory: FakeSessionFactory

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        factory = FakeSessionFactory()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun controller(registry: ParserRegistry = fullRegistry()): RemoteConnectionController =
        RemoteConnectionController(
            scope,
            object : RemoteConnectionHost {
                override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
            },
            sessionFactoryOverride = factory,
            registry = registry,
            ioDispatcher = Dispatchers.Unconfined,
        )

    /** 与控制器默认装配一致的完整注册表（空端点 fixture 用例单独注入假解析器）。 */
    private fun fullRegistry(): ParserRegistry = ParserRegistry(
        urlParsers = p0UrlParsers() + listOf(AnonRtParser()),
        schemeParsers = listOf(DshRemoteSchemeParser()),
        jsonParsers = listOf(SayaChJsonParser()),
        delimitedParsers = listOf(ChongYepDelimitedParser()),
    )

    @Test fun p0BridgePayloadPreviewsParserDeviceAndStrippedUrl() {
        val preview = controller().previewPairing("http://192.168.1.5:3082/?auth=" + "a".repeat(48))
            .getOrThrow()
        assertEquals("dsh-bridge", preview.parserId)
        assertEquals("192.168.1.5", preview.deviceName)
        // 地址已剥凭据：确认卡不回显令牌
        assertEquals("http://192.168.1.5:3082/", preview.targetUrl)
        assertEquals(1, preview.endpointCount)
        assertFalse(preview.isGeneric)
    }

    @Test fun p0GatewayPayloadPreviewsPairingEndpoint() {
        val preview = controller().previewPairing("https://random-gw.trycloudflare.com/pair#s3cret")
            .getOrThrow()
        assertEquals("dsh-remote-web-gateway", preview.parserId)
        assertEquals("random-gw.trycloudflare.com", preview.deviceName)
        assertEquals("https://random-gw.trycloudflare.com/pair", preview.targetUrl)
        assertFalse(preview.isGeneric)
    }

    @Test fun p1SchemePayloadPreviewsEveryEndpoint() {
        val preview = controller().previewPairing(
            "dshremote://pair?token=shared-token&server=192.168.1.5:8787&server=gw.example.com:8787",
        ).getOrThrow()
        assertEquals("dsh-remote-scheme", preview.parserId)
        assertEquals("192.168.1.5", preview.deviceName)
        assertEquals("http://192.168.1.5:8787/", preview.targetUrl)
        assertEquals(2, preview.endpointCount)
        assertFalse(preview.isGeneric)
    }

    @Test fun p1DelimitedPayloadPreviewsChongYepChannel() {
        val preview = controller().previewPairing("https://chongyep.example.com|tok").getOrThrow()
        assertEquals("chongyep-remote", preview.parserId)
        assertEquals("chongyep.example.com", preview.deviceName)
        assertEquals("https://chongyep.example.com", preview.targetUrl)
        assertFalse(preview.isGeneric)
    }

    @Test fun plainUrlFallsIntoGenericChannel() {
        val preview = controller().previewPairing("http://192.168.1.5:8080").getOrThrow()
        assertEquals("generic-url", preview.parserId)
        assertTrue(preview.isGeneric)
    }

    @Test fun previewNeverArchivesDevices() {
        factory.devices = listOf(
            Device(
                id = DeviceId("dev-1"),
                name = "工作电脑",
                endpoints = listOf(
                    Endpoint(com.dshbox.remote.model.EndpointId("ep-1"), "LAN", "http://192.168.1.5:3081", "generic"),
                ),
                addedAtMillis = 1_700_000_000_000,
            ),
        )
        val controller = controller()
        val before = controller.devices.value.size
        controller.previewPairing("http://192.168.1.9:3082/?auth=" + "b".repeat(48)).getOrThrow()
        controller.previewPairing("http://192.168.1.9:8080").getOrThrow()
        // 只解析不建档：设备清单与工厂存档均无任何变更，建档/重配对出口零调用
        assertEquals(before, controller.devices.value.size)
        assertEquals(1, factory.devices.size)
        assertEquals(0, factory.archivePairingCalls)
        assertEquals(0, factory.rePairEndpointCalls)
    }

    @Test fun emptyEndpointPayloadFailsExplicitlyWithoutArchiving() {
        // 空端点载荷（解析器实现异常的兜底形态）：显式脱敏失败，不把
        // NoSuchElementException 抛在 Result.map 内
        val emptyRegistry = ParserRegistry(
            fallbackUrlParser = object : PairingParser {
                override val id: String = "empty-endpoint-fixture"
                override fun matches(raw: String): Boolean = true
                override fun parse(raw: String): ParsedPairing = ParsedPairing(
                    parserId = id,
                    deviceName = "空端点",
                    endpoints = emptyList(),
                )
            },
        )
        val failure = controller(emptyRegistry).previewPairing("http://192.168.1.9:8080")
            .exceptionOrNull()
        assertTrue(failure is PairingParseException)
        // 失败文案不携带载荷（脱敏口径），建档出口零调用
        assertFalse(failure!!.message!!.contains("192.168.1.9"))
        assertEquals(0, factory.archivePairingCalls)
    }

    @Test fun malformedPayloadFailsWithRedactedMessage() {
        val failure = controller().previewPairing("dshremote://pair?token=supersecrettoken")
            .exceptionOrNull()
        assertTrue(failure is com.dshbox.remote.parse.PairingParseException)
        assertFalse(failure!!.message!!.contains("supersecrettoken"))
        // 失败路径同样不产生任何建档副作用
        assertTrue(factory.devices.isEmpty())
    }

    @Test fun genericCredentialQueryIsStrippedFromPreviewUrl() {
        // 一次性票（query 携带）同样只入存档：确认卡地址不回显票据
        val preview = controller().previewPairing("https://quick.trycloudflare.com?ticket=one-time-ticket")
            .getOrThrow()
        assertEquals("generic-url", preview.parserId)
        assertTrue(preview.isGeneric)
        assertFalse(preview.targetUrl.contains("one-time-ticket"))
        assertNull(preview.targetUrl.substringAfter('?', "").ifEmpty { null })
    }
}
