package com.dshbox.remote

import com.dshbox.dsh.renderer.api.DshRendererAdapter
import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.DisplayTarget
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointChoice
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.model.RetentionPolicy
import com.dshbox.remote.model.SessionState
import com.dshbox.remote.parse.AnonRtParser
import com.dshbox.remote.parse.ChongYepDelimitedParser
import com.dshbox.remote.parse.DshRemoteSchemeParser
import com.dshbox.remote.parse.PairingParseException
import com.dshbox.remote.parse.ParsedPairing
import com.dshbox.remote.session.SayaSessionCredential
import com.dshbox.remote.parse.ParserIds
import com.dshbox.remote.parse.ParserRegistry
import com.dshbox.remote.parse.SayaChJsonParser
import com.dshbox.remote.parse.SayaChJsonParserTest
import com.dshbox.remote.parse.p0UrlParsers
import com.dshbox.remote.session.ForegroundElapsedClock
import com.dshbox.remote.session.PairingArchiver
import com.dshbox.remote.session.PairingIds
import com.dshbox.remote.session.ProxySession
import com.dshbox.remote.session.RePairAligner
import com.dshbox.remote.session.SessionFactory
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 配对建档与在途建立存活判定的内核测试：代次漂移废弃、设备作废、门面
 * 关闭拒收、页签离场期间落账的失活处置、配对入口的建档接线。
 */
class RemotePairingKernelTest {

    private class FakeSession(
        override val loopbackOrigin: String = "http://127.0.0.2:51000",
        private val startGate: CountDownLatch? = null,
    ) : ProxySession {
        var started = false
        var stopped = false

        override fun start() {
            check(!started) { "session already started" }
            startGate?.await()
            started = true
        }

        override fun stop() {
            stopped = true
        }
    }

    /** 会话工厂 + 配对建档假实现：内存档案与内存凭据/消费标记。 */
    private class FakeArchiverFactory : SessionFactory, PairingArchiver {

        override var credentialConsumedListener: ((EndpointId) -> Unit)? = null
        var devices: List<Device> = emptyList()
        var createFailure: Exception? = null
        var createGate: CountDownLatch? = null
        var createExited: CountDownLatch? = null
        val sessions = mutableListOf<FakeSession>()

        /** 端点凭据（明文镜像，仅测试观察用）：键 = endpoint.<id>。 */
        val credentials = mutableMapOf<String, String>()

        /**
         * 凭据的设备分桶记账（观察移动/删除端点的搬运用）：键 = endpoint.<id>，
         * 值 = 当前持有该端点敏感条目的设备标识。生产实现按设备 blob 分桶
         * （vault.moveEndpointEntries 换桶），镜像以归属表等价观察。
         */
        val credentialOwners = mutableMapOf<String, DeviceId>()

        /** 端点会话级 CA 证书（明文镜像，仅测试观察用）：键 = endpoint.<id>。 */
        val caCertificates = mutableMapOf<String, String>()

        /** 端点凭据消费标记（镜像）：键 = consumed.<id>，与生产 vault 同前缀。 */
        val consumed = mutableSetOf<String>()

        val sayaSessions = mutableMapOf<EndpointId, SayaSessionCredential>()

        override fun devices(): List<Device> = devices

        override fun createSession(deviceId: DeviceId, endpoint: Endpoint): ProxySession {
            try {
                createGate?.await()
                createFailure?.let { throw it }
                return FakeSession().also { synchronized(sessions) { sessions.add(it) } }
            } finally {
                createExited?.countDown()
            }
        }

        override fun removeDevice(deviceId: DeviceId) {
            devices = devices.filter { it.id != deviceId }
        }

        override fun archivePairing(
            pairing: ParsedPairing,
            displayName: String?,
            choices: List<EndpointChoice>?,
            target: com.dshbox.remote.model.PairingTarget,
        ): Device {
            val newEndpoints = pairing.endpoints.mapIndexed { index, parsed ->
                val choice = choices?.getOrNull(index)
                Endpoint(
                    PairingIds.newEndpointId(),
                    parsed.channelLabel,
                    parsed.targetUrl,
                    pairing.parserId,
                    // 与生产工厂同口径：落档前经直连口径函数收口
                    // （非 https 的直连选择/声明回落代理档）
                    sessionMode = com.dshbox.remote.model.effectiveSessionMode(
                        parsed.targetUrl,
                        choice?.sessionMode ?: parsed.sessionMode
                            ?: com.dshbox.remote.model.SessionMode.PROXIED.name,
                    ),
                    retentionOverride = choice?.retentionOverride,
                )
            }
            val device = when (target) {
                is com.dshbox.remote.model.PairingTarget.Existing ->
                    devices.firstOrNull { it.id == target.deviceId }?.let {
                        it.copy(endpoints = it.endpoints + newEndpoints)
                    }
                com.dshbox.remote.model.PairingTarget.Ungrouped ->
                    devices.firstOrNull { it.name.isEmpty() }?.let {
                        it.copy(endpoints = it.endpoints + newEndpoints)
                    } ?: Device(PairingIds.newDeviceId(), "", newEndpoints, 42L)
                is com.dshbox.remote.model.PairingTarget.NewGroup -> Device(
                    PairingIds.newDeviceId(),
                    displayName?.takeIf { it.isNotBlank() } ?: target.name?.takeIf { it.isNotBlank() }
                        ?: pairing.deviceName,
                    newEndpoints,
                    42L,
                )
            } ?: throw IllegalStateException("pairing target device is removed")
            // 凭据先入存档，条目后落盘（与生产实现的原子性口径一致）
            newEndpoints.zip(pairing.endpoints).forEach { (endpoint, parsed) ->
                parsed.credential?.let {
                    credentials["endpoint." + endpoint.id.value] = it
                    credentialOwners["endpoint." + endpoint.id.value] = device.id
                }
                parsed.caCertificate?.let {
                    caCertificates["cacert." + endpoint.id.value] = it
                    credentialOwners["cacert." + endpoint.id.value] = device.id
                }
            }
            // 并入目标时替换既有条目，新建目标时追加
            devices = if (target is com.dshbox.remote.model.PairingTarget.Existing ||
                target == com.dshbox.remote.model.PairingTarget.Ungrouped && devices.any { it.name.isEmpty() }
            ) {
                devices.map { if (it.id == device.id) device else it }
            } else {
                devices + device
            }
            return device
        }

        override fun moveEndpoint(fromDeviceId: DeviceId, endpointId: EndpointId, toDeviceId: DeviceId): Boolean {
            if (fromDeviceId == toDeviceId) return false
            val from = devices.firstOrNull { it.id == fromDeviceId } ?: return false
            val to = devices.firstOrNull { it.id == toDeviceId } ?: return false
            val moved = from.endpoints.firstOrNull { it.id == endpointId } ?: return false
            devices = devices.map { device ->
                when (device.id) {
                    fromDeviceId -> device.copy(endpoints = device.endpoints.filter { it.id != endpointId })
                    toDeviceId -> device.copy(endpoints = device.endpoints + moved)
                    else -> device
                }
            }
            // 凭据/CA/消费标记随端点迁入目标设备：键值不变、持有设备换桶
            // （与 vault.moveEndpointEntries 的搬运语义同构——endpoint./cacert./
            // consumed. 三类键整体换桶）
            listOf(
                "endpoint." + endpointId.value,
                "cacert." + endpointId.value,
                "consumed." + endpointId.value,
            ).forEach { key ->
                if (credentialOwners.containsKey(key)) {
                    credentialOwners[key] = toDeviceId
                }
            }
            // 消费标记随迁（换桶记账，与生产 moveEndpointEntries 同口径）
            if ("consumed." + endpointId.value in consumed) {
                credentialOwners.getOrPut("consumed." + endpointId.value) { toDeviceId }
            }
            return true
        }

        override fun removeEndpoint(deviceId: DeviceId, endpointId: EndpointId): Boolean {
            val device = devices.firstOrNull { it.id == deviceId } ?: return false
            if (device.endpoints.none { it.id == endpointId }) return false
            devices = devices.map {
                if (it.id == deviceId) it.copy(endpoints = it.endpoints.filter { ep -> ep.id != endpointId }) else it
            }
            // 三类键整体清除（与 vault.clearEndpointEntries 同口径）
            listOf(
                "endpoint." + endpointId.value,
                "cacert." + endpointId.value,
                "consumed." + endpointId.value,
            ).forEach { key ->
                credentials.remove(key)
                caCertificates.remove(key)
                consumed.remove(key)
                credentialOwners.remove(key)
            }
            return true
        }

        override fun rePairEndpoint(
            deviceId: DeviceId,
            endpointId: EndpointId,
            pairing: ParsedPairing,
            requestedSessionMode: String?,
        ): Set<EndpointId> {
            val current = devices.firstOrNull { it.id == deviceId } ?: return emptySet()
            // 端点对齐与生产工厂共用同一对齐器（触发端点 + 同解析器同 host 兄弟端点）
            val outcome = RePairAligner.align(
                endpoints = current.endpoints,
                triggeredId = endpointId,
                pairing = pairing,
                currentCredential = { credentials["endpoint." + it.value] },
                currentCaCertificate = { caCertificates["cacert." + it.value] },
                requestedSessionMode = requestedSessionMode,
            ) ?: return emptySet()
            outcome.credentialUpdates.forEach { (id, value) -> credentials["endpoint." + id.value] = value }
            outcome.caCertificateUpdates.forEach { (id, value) -> caCertificates["cacert." + id.value] = value }
            outcome.resetConsumed.forEach { consumed.remove("consumed." + it.value) }
            outcome.resetConsumed.forEach { sayaSessions.remove(it) }
            devices = devices.map { device ->
                if (device.id == deviceId) device.copy(endpoints = outcome.endpoints) else device
            }
            return outcome.changedEndpointIds
        }

        override fun pendingCredentialEndpoints(): Set<EndpointId> =
            devices.flatMap { device ->
                device.endpoints
                    // 与生产口径一致：仅消费型解析器（一次性票据凭据）参与未消费集合
                    .filter {
                        it.parserId in ParserIds.CONSUMABLE_CREDENTIAL_PARSER_IDS &&
                            credentials.containsKey("endpoint." + it.id.value) &&
                            "consumed." + it.id.value !in consumed
                    }
                    .map { it.id }
            }.toSet()

        override fun markCredentialConsumed(endpointId: EndpointId, generation: Long) {
            // 代次校验与生产实现同口径：漂移即静默忽略（迟到提交不误标新凭据）
            val owner = devices.firstOrNull { device -> device.endpoints.any { it.id == endpointId } }
                ?: return
            val endpoint = owner.endpoints.firstOrNull { it.id == endpointId } ?: return
            if (endpoint.generation != generation) return
            consumed.add("consumed." + endpointId.value)
            credentialConsumedListener?.invoke(endpointId)
        }
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
    private lateinit var factory: FakeArchiverFactory

    private val deviceId = DeviceId("dev-1")
    private val ep1 = EndpointId("ep-1")

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.Unconfined)
        factory = FakeArchiverFactory().apply {
            devices = listOf(
                Device(
                    id = deviceId,
                    name = "工作电脑",
                    endpoints = listOf(
                        Endpoint(ep1, "LAN", "http://192.168.1.5:3081/", "dsh-pocket"),
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

    /** 与生产门面同口径的注册表接线（URL 签名、自定义 scheme、JSON 描述符与竖线分隔文本四类解析器齐全）。 */
    private fun productionRegistry() = ParserRegistry(
        urlParsers = p0UrlParsers() + listOf(AnonRtParser()),
        schemeParsers = listOf(DshRemoteSchemeParser()),
        jsonParsers = listOf(SayaChJsonParser()),
        delimitedParsers = listOf(ChongYepDelimitedParser()),
    )

    private fun unconfinedController(clock: ForegroundElapsedClock = ForegroundElapsedClock()) =
        RemoteConnectionController(
            scope = scope,
            host = object : RemoteConnectionHost {
                override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
            },
            sessionFactoryOverride = factory,
            registry = productionRegistry(),
            ioDispatcher = Dispatchers.Unconfined,
            foregroundClock = clock,
        )

    private fun asyncController(clock: ForegroundElapsedClock = ForegroundElapsedClock()) =
        RemoteConnectionController(
            scope = scope,
            host = object : RemoteConnectionHost {
                override fun rendererAdapter(): DshRendererAdapter = FakeRendererAdapter()
            },
            sessionFactoryOverride = factory,
            registry = productionRegistry(),
            ioDispatcher = Dispatchers.IO,
            foregroundClock = clock,
        )

    private fun awaitState(controller: RemoteConnectionController, ep: EndpointId, expected: SessionState) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && controller.sessionStates.value[ep] != expected) {
            Thread.sleep(5)
        }
        assertEquals(expected, controller.sessionStates.value[ep])
    }

    // ---- 配对建档接线 ----

    @Test fun addFromPayloadArchivesDeviceAndRefreshesList() = runBlocking {
        val controller = unconfinedController()
        val result = controller.addFromPayload("http://192.168.1.7:3081")
        val device = result.getOrThrow()
        assertEquals("dsh-pocket", device.parserIdOfSingleEndpoint())
        assertEquals("192.168.1.7", device.name)
        assertEquals(0L, device.endpoints.single().generation)
        assertEquals(listOf(device.id), controller.devices.value.map { it.id }.filter { it != deviceId })
        // 载荷畸形：失败且不入清单
        val failure = controller.addFromPayload("dshremote://pair?token=secret-value")
        assertTrue(failure.exceptionOrNull() is PairingParseException)
        assertEquals(2, controller.devices.value.size)
    }

    @Test fun addFromPayloadWithChoicesArchivesSelectionInOnePass() = runBlocking {
        val controller = unconfinedController()
        // 确认卡收集的选择（直连 + 保留档覆盖）随建档一次写入端点档案：
        // 会话模式与保留档覆盖同批落盘，不再二次调用
        val device = controller.addFromPayload(
            "https://mirror.example.com/app",
            choices = listOf(com.dshbox.remote.model.EndpointChoice("DIRECT", "FOREVER")),
        ).getOrThrow()
        val endpoint = device.endpoints.single()
        assertEquals("DIRECT", endpoint.sessionMode)
        assertEquals("FOREVER", endpoint.retentionOverride)
        assertEquals(0L, endpoint.generation)
    }

    @Test fun addFromPayloadWithHttpDirectChoiceFallsBackToProxied() = runBlocking {
        val controller = unconfinedController()
        // 非法组合（http 目标 + 直连选择）在落档层收口：直连档回落代理档，
        // 保留档覆盖原样透传——端点不落入会话层恒失败的死档
        val device = controller.addFromPayload(
            "http://192.168.1.7:3081",
            choices = listOf(com.dshbox.remote.model.EndpointChoice("DIRECT", "RETAIN:15")),
        ).getOrThrow()
        val endpoint = device.endpoints.single()
        assertEquals(com.dshbox.remote.model.SessionMode.PROXIED.name, endpoint.sessionMode)
        assertEquals("RETAIN:15", endpoint.retentionOverride)
    }

    @Test fun addFromPayloadWithHttpsDirectChoiceStillArchivesDirect() = runBlocking {
        val controller = unconfinedController()
        // 合法直连（https 目标 + 直连选择）：DIRECT 原样落档（收口不改写
        // 合法组合，口径函数只拦非 https 的直连）
        val device = controller.addFromPayload(
            "https://mirror.example.com/app",
            choices = listOf(com.dshbox.remote.model.EndpointChoice("DIRECT", null)),
        ).getOrThrow()
        val endpoint = device.endpoints.single()
        assertEquals(com.dshbox.remote.model.SessionMode.DIRECT.name, endpoint.sessionMode)
        assertNull(endpoint.retentionOverride)
    }

    @Test fun addFromPayloadWithoutChoicesKeepsLegacyDefaults() = runBlocking {
        val controller = unconfinedController()
        // 不带选择（确认卡未收集/null）：默认值推导同路（generic 无声明 =
        // 代理档），保留档覆盖 null（继承全局）
        val device = controller.addFromPayload("http://192.168.1.7:3081").getOrThrow()
        val endpoint = device.endpoints.single()
        assertEquals(com.dshbox.remote.model.SessionMode.PROXIED.name, endpoint.sessionMode)
        assertNull(endpoint.retentionOverride)
    }

    @Test fun addFromPayloadWithSurplusChoicesDoesNotOverflow() = runBlocking {
        val controller = unconfinedController()
        // 选择条数多于解析端点：多余项丢弃不落档（逐端点按索引取选择，
        // 端点档案不含幽灵端点）
        val device = controller.addFromPayload(
            "http://192.168.1.7:3081",
            choices = listOf(
                com.dshbox.remote.model.EndpointChoice("PROXIED", "RETAIN:15"),
                com.dshbox.remote.model.EndpointChoice("DIRECT", "FOREVER"),
            ),
        ).getOrThrow()
        val endpoints = device.endpoints
        assertEquals(1, endpoints.size)
        assertEquals(com.dshbox.remote.model.SessionMode.PROXIED.name, endpoints.single().sessionMode)
        assertEquals("RETAIN:15", endpoints.single().retentionOverride)
    }

    @Test fun addFromPayloadWithTicketedGenericUrlKeepsSecretOutOfArchive() = runBlocking {
        val controller = unconfinedController()
        // 非 /pair 路径（/pair?ticket= 已由 anonRT 签名认领）：走通用通道
        val raw = "http://192.168.1.9:8899/connect?ticket=one-time-secret"
        val device = controller.addFromPayload(raw).getOrThrow()
        assertEquals("generic-url", device.parserIdOfSingleEndpoint())
        val endpoint = device.endpoints.single()
        // 清单只存剥离凭据的地址；完整 URL 整体入凭据存档
        assertEquals("http://192.168.1.9:8899/connect", endpoint.targetUrl)
        assertEquals(raw, factory.credentials.getValue("endpoint." + endpoint.id.value))
        // 显示请求随选择受理发出（计数增量驱动宿主切页签）
        assertEquals(0, controller.showDshRequests.value)
    }

    @Test fun dshRemoteSchemePayloadArchivesMultiEndpointDevice() = runBlocking {
        val controller = unconfinedController()
        val raw = "dshremote://pair?token=shared-token&server=192.168.1.5:8787&server=gw.example.com:8787"
        val device = controller.addFromPayload(raw).getOrThrow()
        // 一台设备两条端点（局域网/公网多通道），解析器标识一致
        assertEquals(2, device.endpoints.size)
        assertTrue(device.endpoints.all { it.parserId == "dsh-remote-scheme" })
        assertEquals("http://192.168.1.5:8787/", device.endpoints[0].targetUrl)
        assertEquals("http://gw.example.com:8787/", device.endpoints[1].targetUrl)
        // 令牌为该设备全部通道共用：每条端点各持一份存档凭据
        device.endpoints.forEach { endpoint ->
            assertEquals("shared-token", factory.credentials.getValue("endpoint." + endpoint.id.value))
        }
        // 长期令牌：全部端点都不进未消费集合
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)
    }

    @Test fun anonRtTicketEndpointJoinsPendingCredential() = runBlocking {
        val controller = unconfinedController()
        val raw = "http://192.168.1.9:8899/pair?ticket=one-time-secret"
        val device = controller.addFromPayload(raw).getOrThrow()
        assertEquals("anonrt-remote-mode", device.parserIdOfSingleEndpoint())
        val endpoint = device.endpoints.single()
        assertEquals("http://192.168.1.9:8899/pair", endpoint.targetUrl)
        assertEquals(raw, factory.credentials.getValue("endpoint." + endpoint.id.value))
        // 票据与通用票据同生命周期：进入未消费集合（消费门控覆盖）
        assertEquals(setOf(endpoint.id), controller.pendingCredential.value)
    }

    @Test fun chongYepDelimitedPayloadArchivesLongTermTokenEndpoint() = runBlocking {
        val controller = unconfinedController()
        val device = controller.addFromPayload("http://192.168.1.8:8787|long-term-token").getOrThrow()
        assertEquals("chongyep-remote", device.parserIdOfSingleEndpoint())
        val endpoint = device.endpoints.single()
        assertEquals("http://192.168.1.8:8787", endpoint.targetUrl)
        assertEquals("long-term-token", factory.credentials.getValue("endpoint." + endpoint.id.value))
        // 长期令牌非一次性票：不进未消费集合
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)
    }

    @Test fun sayaChDescriptorArchivesCaCertificateAndLongTermToken() = runBlocking {
        val controller = unconfinedController()
        val raw = """{"version":1,"type":"dsh-mobile-iroh-pairing","endpointId":"saya-box",""" +
            """"origin":"https://192.168.1.20:4433","localPort":4433,"irohCode":"482913",""" +
            """"gatewayToken":"gw-token-value","caCertificate":"${SayaChJsonParserTest.CA_BASE64}",""" +
            """"instanceId":"${SayaChJsonParserTest.CA_FINGERPRINT_HEX}","expiresAt":${System.currentTimeMillis() + 600_000}}"""
        val device = controller.addFromPayload(raw).getOrThrow()
        assertEquals("saya-ch-remote", device.parserIdOfSingleEndpoint())
        val endpoint = device.endpoints.single()
        assertEquals("https://192.168.1.20:4433", endpoint.targetUrl)
        assertEquals("LAN", endpoint.channelLabel)
        // 网关令牌（一次性票）入凭据存档；CA 证书随端点入加密存档（会话级信任来源）
        assertEquals("gw-token-value", factory.credentials.getValue("endpoint." + endpoint.id.value))
        assertEquals(
            SayaChJsonParserTest.CA_BASE64,
            factory.caCertificates.getValue("cacert." + endpoint.id.value),
        )
        // gatewayToken 是一次性配对票（native-pair 单次兑换）：进未消费集合
        assertEquals(setOf(endpoint.id), controller.pendingCredential.value)

        // 重新配对（新描述符）：凭据/CA 替换、代次自增；旧兑换产物清空——
        // 新码恢复未消费语义，可再次兑换
        val renewed = """{"version":1,"type":"dsh-mobile-iroh-pairing","endpointId":"saya-box",""" +
            """"origin":"https://192.168.1.20:4433","localPort":4433,"irohCode":"998877",""" +
            """"gatewayToken":"renewed-token","caCertificate":"${SayaChJsonParserTest.CA_BASE64}",""" +
            """"instanceId":"${SayaChJsonParserTest.CA_FINGERPRINT_HEX}","expiresAt":${System.currentTimeMillis() + 600_000}}"""
        factory.sayaSessions[endpoint.id] = SayaSessionCredential("__Host-dsh_ma_session", "stale", "stale-csrf")
        val result = controller.updateEndpointTarget(device.id, endpoint.id, renewed)
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, result)
        val updated = controller.devices.value.first { it.id == device.id }.endpoints.single()
        assertEquals(1L, updated.generation)
        assertEquals("renewed-token", factory.credentials.getValue("endpoint." + endpoint.id.value))
        assertNull(factory.sayaSessions[endpoint.id])
        assertEquals(setOf(endpoint.id), controller.pendingCredential.value)
    }

    @Test fun multiEndpointRePairAlignsSiblingEndpointsWithMatchingHost() = runBlocking {
        val controller = unconfinedController()
        val raw = "dshremote://pair?token=old-token&server=192.168.1.5:8787&server=gw.example.com:8787"
        val device = controller.addFromPayload(raw).getOrThrow()
        assertEquals(2, device.endpoints.size)

        // 重配对触发端点（局域网通道）：载荷同时携带两个 server 的新令牌
        val renewed = "dshremote://pair?token=new-token&server=192.168.1.5:8788&server=gw.example.com:8788"
        val result = controller.updateEndpointTarget(device.id, device.endpoints[0].id, renewed)
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, result)

        // 同设备同解析器的同 host 兄弟端点一并更新：旧令牌不再静默失效
        val endpoints = controller.devices.value.first { it.id == device.id }.endpoints
        assertEquals("http://192.168.1.5:8788/", endpoints[0].targetUrl)
        assertEquals("http://gw.example.com:8788/", endpoints[1].targetUrl)
        endpoints.forEach { endpoint ->
            assertEquals("new-token", factory.credentials.getValue("endpoint." + endpoint.id.value))
            assertEquals(1L, endpoint.generation)
        }
        // 长期令牌端点不进未消费集合
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)
    }

    @Test fun rePairWithUnmatchedHostUpdatesOnlyTriggeredEndpoint() = runBlocking {
        val controller = unconfinedController()
        val raw = "dshremote://pair?token=old-token&server=192.168.1.5:8787&server=gw.example.com:8787"
        val device = controller.addFromPayload(raw).getOrThrow()

        // 载荷仅含触发端点的 host（兄弟通道已下线）：兄弟端点不动
        val renewed = "dshremote://pair?token=new-token&server=192.168.1.5:8788"
        val result = controller.updateEndpointTarget(device.id, device.endpoints[0].id, renewed)
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, result)
        val endpoints = controller.devices.value.first { it.id == device.id }.endpoints
        assertEquals("http://192.168.1.5:8788/", endpoints[0].targetUrl)
        assertEquals("new-token", factory.credentials.getValue("endpoint." + endpoints[0].id.value))
        assertEquals(1L, endpoints[0].generation)
        // host 不在载荷中：地址与凭据保留
        assertEquals("http://gw.example.com:8787/", endpoints[1].targetUrl)
        assertEquals("old-token", factory.credentials.getValue("endpoint." + endpoints[1].id.value))
        assertEquals(0L, endpoints[1].generation)
    }

    @Test fun rePairReleasesLiveSiblingSessions() = runBlocking {
        val controller = unconfinedController()
        // 保留档：两个端点的会话可并存（失活不即时释放）
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        val raw = "dshremote://pair?token=old-token&server=192.168.1.5:8787&server=gw.example.com:8787"
        val device = controller.addFromPayload(raw).getOrThrow()
        val (epLan, epWan) = device.endpoints.map { it.id }
        controller.selectEndpoint(device.id, epLan)
        awaitState(controller, epLan, SessionState.Ready)
        controller.selectEndpoint(device.id, epWan)
        awaitState(controller, epWan, SessionState.Ready)
        assertEquals(2, factory.sessions.size)
        assertFalse(factory.sessions[0].stopped)
        assertFalse(factory.sessions[1].stopped)

        // 重配对触发端点：档案变更覆盖兄弟端点（同 host 对齐），两端的
        // 在用会话一并释放——兄弟会话仍持有旧令牌，静默沿用无效
        val renewed = "dshremote://pair?token=new-token&server=192.168.1.5:8788&server=gw.example.com:8788"
        val result = controller.updateEndpointTarget(device.id, epLan, renewed)
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, result)
        assertEquals(SessionState.Released, controller.sessionStates.value[epLan])
        assertEquals(SessionState.Released, controller.sessionStates.value[epWan])
        assertTrue(factory.sessions.all { it.stopped })
    }

    @Test fun endpointTargetEditBumpsGenerationAndDiscardsInFlightBuild() {
        val controller = asyncController()
        factory.createGate = CountDownLatch(1)
        factory.createExited = CountDownLatch(1)
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Connecting)

        // 建立在途时编辑地址（解析归一化 → 重新配对语义）：代次自增，清单刷新
        val updateResult = kotlinx.coroutines.runBlocking {
            controller.updateEndpointTarget(deviceId, ep1, "https://new-tunnel.example.com/")
        }
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, updateResult)
        assertEquals(1L, controller.devices.value.single().endpoints.single().generation)

        factory.createGate!!.countDown()
        assertTrue(factory.createExited!!.await(10, TimeUnit.SECONDS))
        // 回填代次漂移：本次建立废弃（代理停止、登记态清理），不落 Ready
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && controller.sessionStates.value[ep1] != null) {
            Thread.sleep(5)
        }
        assertNull(controller.sessionStates.value[ep1])
        assertTrue(factory.sessions.single().stopped)

        // 新代次重新选择：正常建立
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Ready)
        assertEquals("https://new-tunnel.example.com/", controller.devices.value.single().endpoints.single().targetUrl)
    }

    // ---- 门面关闭（closed 标记） ----

    @Test fun closeReleasesSessionsAndRejectsNewBuilds() {
        val controller = unconfinedController()
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Ready)

        controller.close()
        assertTrue(factory.sessions.single().stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])

        // 关闭后的建立一律拒收：无新会话、显示指向不变
        controller.selectEndpoint(deviceId, ep1)
        assertEquals(1, factory.sessions.size)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
    }

    @Test fun closeDuringInFlightBuildDoesNotAttachReady() {
        val controller = asyncController()
        factory.createGate = CountDownLatch(1)
        factory.createExited = CountDownLatch(1)
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Connecting)

        controller.close()
        factory.createGate!!.countDown()
        assertTrue(factory.createExited!!.await(10, TimeUnit.SECONDS))

        // 回填遇门面已关闭：按释放口径收尾，不落 Ready、代理停止
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && !factory.sessions.single().stopped) {
            Thread.sleep(5)
        }
        assertTrue(factory.sessions.single().stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
    }

    // ---- 在途建立 × 页签离场 ----

    @Test fun inFlightBuildLandedWhileDshTabHiddenReleasesImmediately() {
        val controller = asyncController()
        factory.createGate = CountDownLatch(1)
        factory.createExited = CountDownLatch(1)
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Connecting)

        // 页签在建立期间切走：IMMEDIATE 档落账即释放，不常驻
        controller.onDshTabHidden()
        factory.createGate!!.countDown()
        assertTrue(factory.createExited!!.await(10, TimeUnit.SECONDS))

        awaitState(controller, ep1, SessionState.Released)
        assertTrue(factory.sessions.single().stopped)
    }

    @Test fun inFlightBuildLandedWhileDshTabHiddenArmsRetainCountdown() {
        var wall = 0L
        val clock = ForegroundElapsedClock { wall }
        val controller = asyncController(clock)
        controller.setRetentionDefault(RetentionPolicy.Retain(5))
        controller.onHostResumed()
        factory.createGate = CountDownLatch(1)
        factory.createExited = CountDownLatch(1)
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Connecting)

        controller.onDshTabHidden()
        factory.createGate!!.countDown()
        assertTrue(factory.createExited!!.await(10, TimeUnit.SECONDS))

        // Retain 档：落账后武装倒计时（会话在册、不立即释放）
        awaitState(controller, ep1, SessionState.Ready)
        assertFalse(factory.sessions.single().stopped)
        wall += 6 * 60_000L
        controller.onHostResumed()
        assertTrue(factory.sessions.single().stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
    }

    @Test fun endpointEditReleasesLiveSessionAndUnchangedAddressIsNoOp() {
        val controller = unconfinedController()
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Ready)
        val session = factory.sessions.single()

        // 地址与凭据均未变化：无变更信号（UNCHANGED）、不释放会话、代次不动
        val unchanged = kotlinx.coroutines.runBlocking {
            controller.updateEndpointTarget(deviceId, ep1, "http://192.168.1.5:3081/")
        }
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UNCHANGED, unchanged)
        assertFalse(session.stopped)
        assertEquals(0L, controller.devices.value.single().endpoints.single().generation)

        // 编辑生效且会话在用：主动释放（Released），旧目标不静默沿用
        val updated = kotlinx.coroutines.runBlocking {
            controller.updateEndpointTarget(deviceId, ep1, "https://new-tunnel.example.com/")
        }
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, updated)
        assertTrue(session.stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        assertEquals(1L, controller.devices.value.single().endpoints.single().generation)
    }

    @Test fun panelSelectionRequestsDisplayAndExemptsBackfillFromDeactivation() {
        val controller = asyncController()
        factory.createGate = CountDownLatch(1)
        factory.createExited = CountDownLatch(1)
        val before = controller.showDshRequests.value
        controller.selectEndpoint(deviceId, ep1) // 面板选择：页签隐藏 + 豁免登记
        assertEquals(before + 1, controller.showDshRequests.value)

        // 豁免生效：落账不按策略释放（显示请求会随后把页签切到 DSH；
        // 选择与页签切换生效之间没有页签事件，豁免由本次回填消费）
        factory.createGate!!.countDown()
        assertTrue(factory.createExited!!.await(10, TimeUnit.SECONDS))
        awaitState(controller, ep1, SessionState.Ready)
        assertFalse(factory.sessions.single().stopped)

        // 恢复正常策略：豁免已消费，之后真实离场即按策略释放
        controller.onDshTabHidden()
        assertTrue(factory.sessions.single().stopped)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
    }

    @Test fun pendingCredentialTracksLifecycle() = runBlocking {
        val controller = unconfinedController()
        // anonRT 配对页建档（/pair?ticket= 归 anonRT 解析器，非 generic）：端点进入未消费凭据集合
        val raw = "http://192.168.1.9:8899/pair?ticket=one-time-secret"
        val device = controller.addFromPayload(raw).getOrThrow()
        val endpointId = device.endpoints.single().id
        assertEquals(setOf(endpointId), controller.pendingCredential.value)

        // 代理观测到兑换完成：先落存档消费标记（生产实现由工厂同步完成），
        // 监听触发后集合摘除该端点
        factory.consumed.add("consumed." + endpointId.value)
        factory.credentialConsumedListener!!.invoke(endpointId)
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)

        // 裸地址编辑（无凭据）：不改变集合
        val edit = controller.updateEndpointTarget(device.id, endpointId, "http://192.168.1.9:8899/")
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, edit)
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)

        // 删除设备：集合清空该设备端点
        controller.removeDevice(device.id)
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)
    }

    @Test fun fullRemoteSessionFlagsConsumableCredentialWithoutInitialLoad() = runBlocking {
        val controller = unconfinedController()
        val device = controller
            .addFromPayload("https://gw.example.com/_dsh_reverse_proxy/login?invite=Ab12Cd34")
            .getOrThrow()
        assertEquals("dsh-full-remote", device.parserIdOfSingleEndpoint())
        val endpointId = device.endpoints.single().id
        assertEquals(setOf(endpointId), controller.pendingCredential.value)

        controller.selectEndpoint(device.id, endpointId)
        awaitState(controller, endpointId, SessionState.Ready)
        // full-remote 的凭据走注入器改写（无首载地址）：消费门控前提经会话
        // 参数显式传入，不再凭 URL 推断——否则该类会话永不提交消费
        assertTrue(controller.sessionUsesConsumableCredential(endpointId))
        assertNull(controller.sessionInitialLoadUrl(endpointId))
    }

    @Test fun bareEditKeepsParserAndBridgeStaysOutOfPendingCredential() = runBlocking {
        val controller = unconfinedController()
        val bridgeToken = "0123456789abcdef0123456789abcdef0123456789abcdef"
        val device = controller.addFromPayload("http://192.168.1.6:3082/?auth=" + bridgeToken).getOrThrow()
        val endpointId = device.endpoints.single().id
        assertEquals("dsh-bridge", device.parserIdOfSingleEndpoint())
        // bridge 为长期令牌（非一次性票）：不进入未消费集合（401 不误示重新配对）
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)

        // 裸地址编辑：只更新地址与代次——parserId/凭据保留（不降级成通用通道）
        val result = controller.updateEndpointTarget(device.id, endpointId, "http://10.0.0.8:3082/")
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, result)
        val updated = controller.devices.value.firstOrNull { it.id == device.id }!!.endpoints.single()
        assertEquals("dsh-bridge", updated.parserId)
        assertEquals("http://10.0.0.8:3082/", updated.targetUrl)
        assertEquals(1L, updated.generation)
        assertEquals(bridgeToken, factory.credentials.getValue("endpoint." + endpointId.value))
        // 凭据未变，未消费集合仍不含长期令牌端点
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)
    }

    @Test fun bareAddressEditToTunnelKeepsInjectiveParserAndCredential() = runBlocking {
        val controller = unconfinedController()
        val bridgeToken = "0123456789abcdef0123456789abcdef0123456789abcdef"
        val device = controller.addFromPayload("http://192.168.1.6:3082/?auth=" + bridgeToken).getOrThrow()
        val endpointId = device.endpoints.single().id

        // bridge 端点裸地址编辑到新隧道域名：解析器/凭据保留（不隐式重分类）
        val result = controller.updateEndpointTarget(device.id, endpointId, "https://new-bridge.trycloudflare.com/")
        assertEquals(RemoteConnectionController.EndpointUpdateResult.UPDATED, result)
        val updated = controller.devices.value.firstOrNull { it.id == device.id }!!.endpoints.single()
        assertEquals("dsh-bridge", updated.parserId)
        assertEquals("https://new-bridge.trycloudflare.com/", updated.targetUrl)
        assertEquals(bridgeToken, factory.credentials.getValue("endpoint." + endpointId.value))
    }

    // ---- 归属建档（无分组 / 并入已有设备 / 新建分组）与端点移动/删除 ----

    @Test fun addFromPayloadIntoExistingDeviceMergesEndpoints() = runBlocking {
        val controller = unconfinedController()
        val result = controller.addFromPayload(
            "http://192.168.1.7:3081",
            target = com.dshbox.remote.model.PairingTarget.Existing(deviceId),
        )
        val merged = result.getOrThrow()
        // 不新建设备：端点并入既有设备档案末尾
        assertEquals(deviceId, merged.id)
        assertEquals(2, merged.endpoints.size)
        assertEquals(listOf(deviceId), controller.devices.value.map { it.id })
    }

    @Test fun addFromPayloadIntoUngroupedCreatesEmptyNamedDeviceSortedLast() = runBlocking {
        val controller = unconfinedController()
        // 无分组条目（名称为空串）：清单垫底
        val loose = controller.addFromPayload(
            "http://192.168.1.8:3081",
            target = com.dshbox.remote.model.PairingTarget.Ungrouped,
        ).getOrThrow()
        assertEquals("", loose.name)
        assertEquals(listOf(deviceId, loose.id), controller.devices.value.map { it.id })
        // 再次并入：进入同一条目（不重复建空名设备）
        val second = controller.addFromPayload(
            "http://192.168.1.9:3081",
            target = com.dshbox.remote.model.PairingTarget.Ungrouped,
        ).getOrThrow()
        assertEquals(loose.id, second.id)
        assertEquals(2, second.endpoints.size)
        assertEquals(listOf(deviceId, loose.id), controller.devices.value.map { it.id })
    }

    @Test fun addFromPayloadWithNewGroupKeepsProvidedName() = runBlocking {
        val controller = unconfinedController()
        val device = controller.addFromPayload(
            "http://192.168.1.10:3081",
            target = com.dshbox.remote.model.PairingTarget.NewGroup("书房主机"),
        ).getOrThrow()
        assertEquals("书房主机", device.name)
        // 初始一台 + 新建分组一台：清单两台
        assertEquals(2, controller.devices.value.size)
    }

    @Test fun addFromPayloadIntoRemovedDeviceFails() = runBlocking {
        val controller = unconfinedController()
        controller.removeDevice(deviceId)
        val result = controller.addFromPayload(
            "http://192.168.1.7:3081",
            target = com.dshbox.remote.model.PairingTarget.Existing(deviceId),
        )
        assertTrue(result.isFailure)
        assertTrue(controller.devices.value.isEmpty())
    }

    @Test fun moveEndpointRearrangesArchiveAndReleasesLiveSession() = runBlocking {
        val controller = unconfinedController()
        val targetDeviceId = DeviceId("dev-2")
        factory.devices = factory.devices + Device(
            id = targetDeviceId,
            name = "备用机",
            endpoints = emptyList(),
            addedAtMillis = 2L,
        )
        // setUp 直接建档的端点在镜像里登记凭据归属（生产由建档写入）
        factory.credentialOwners["endpoint." + ep1.value] = deviceId
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Ready)

        // 移动端点：档案重排（端点身份不变）+ 凭据随端点换桶到目标设备 +
        // 在用会话按 Released 释放
        controller.moveEndpointTo(deviceId, ep1, targetDeviceId)
        val moved = controller.devices.value.first { it.id == targetDeviceId }.endpoints.single()
        assertEquals(ep1, moved.id)
        assertEquals(SessionState.Released, controller.sessionStates.value[ep1])
        // 凭据随迁：目标设备持有（换桶），源设备不再持有
        assertEquals(targetDeviceId, factory.credentialOwners.getValue("endpoint." + ep1.value))
        // 移动最后一条端点：源设备移空即删除（不再残留空设备）
        assertTrue(controller.devices.value.none { it.id == deviceId })
    }

    @Test fun moveEndpointKeepsSourceDeviceWhenOtherEndpointsRemain() = runBlocking {
        val controller = unconfinedController()
        val targetDeviceId = DeviceId("dev-2")
        val extra = controller.addFromPayload(
            "http://192.168.1.21:3081",
            target = com.dshbox.remote.model.PairingTarget.Existing(deviceId),
        ).getOrThrow()
        factory.devices = factory.devices + Device(
            id = targetDeviceId,
            name = "备用机",
            endpoints = emptyList(),
            addedAtMillis = 2L,
        )

        // 源设备还有其余端点：移动后设备保留
        controller.moveEndpointTo(deviceId, ep1, targetDeviceId)
        val source = controller.devices.value.first { it.id == deviceId }
        assertEquals(listOf(extra.endpoints.last().id), source.endpoints.map { it.id })
        assertEquals(2, controller.devices.value.size)
    }

    @Test fun removeEndpointResetsDanglingDisplayTarget() = runBlocking {
        val controller = unconfinedController()
        // 先播种端点敏感条目（凭据/CA/消费标记三类键 + 归属记账），删除后
        // 的「键清空」断言才有意义（不恒真）
        factory.credentials["endpoint." + ep1.value] = "seeded-ticket"
        factory.caCertificates["cacert." + ep1.value] = "seeded-ca"
        factory.consumed.add("consumed." + ep1.value)
        factory.credentialOwners["endpoint." + ep1.value] = deviceId
        factory.credentialOwners["cacert." + ep1.value] = deviceId
        factory.credentialOwners["consumed." + ep1.value] = deviceId
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Ready)
        assertEquals(DisplayTarget.Session(ep1), controller.activeDisplay.value)

        // 删除正在显示的端点：会话转 Terminated 且显示指向复位本机（不留悬空
        // 的空白页与错误微条文案）
        controller.removeEndpoint(deviceId, ep1)
        assertEquals(SessionState.Terminated, controller.sessionStates.value[ep1])
        assertEquals(DisplayTarget.Local, controller.activeDisplay.value)
        // 删除端点的三类敏感键一并清除（键先播种，断言为真非恒真）
        assertFalse(factory.credentials.containsKey("endpoint." + ep1.value))
        assertFalse(factory.caCertificates.containsKey("cacert." + ep1.value))
        assertFalse(factory.consumed.contains("consumed." + ep1.value))
        assertFalse(factory.credentialOwners.containsKey("endpoint." + ep1.value))
        assertTrue(controller.devices.value.isEmpty()) // 最后一条端点：设备随之删除
    }

    @Test fun removeEndpointDropsEntryAndLastEndpointRemovesDevice() = runBlocking {
        val controller = unconfinedController()
        // 双端点无分组设备：两条载荷并入同一条目
        val first = controller.addFromPayload(
            "http://192.168.1.11:3081",
            target = com.dshbox.remote.model.PairingTarget.Ungrouped,
        ).getOrThrow()
        val second = controller.addFromPayload(
            "http://192.168.1.12:3081",
            target = com.dshbox.remote.model.PairingTarget.Ungrouped,
        ).getOrThrow()
        assertEquals(first.id, second.id)
        assertEquals(2, second.endpoints.size)
        val epA = first.endpoints.single().id
        val epB = second.endpoints.last().id

        // 删除单条端点：条目移出、设备保留
        controller.removeEndpoint(first.id, epB)
        val after = controller.devices.value.first { it.id == first.id }
        assertEquals(1, after.endpoints.size)

        // 删除设备的最后一条端点：转删除设备口径（整档消失）
        controller.removeEndpoint(first.id, epA)
        assertEquals(listOf(deviceId), controller.devices.value.map { it.id })
    }

    @Test fun devicesInitialValueSortsGroupsFirstAndUngroupedLast() {
        // 冷启动：工厂档案里无分组条目建档时间晚于部分分组，初值装载也要
        // 按「命名分组在前（按建档时间）、无分组垫底」排序
        val namedOld = Device(DeviceId("dev-old"), "旧分组", emptyList(), 1L)
        val loose = Device(PairingIds.newDeviceId(), "", emptyList(), 2L)
        val namedNew = Device(DeviceId("dev-new"), "新分组", emptyList(), 3L)
        factory.devices = listOf(namedNew, loose, namedOld)
        val controller = unconfinedController()
        assertEquals(
            listOf(namedOld.id, namedNew.id, loose.id),
            controller.devices.value.map { it.id },
        )
    }

    @Test fun setEndpointSessionModeFallsBackToProxiedForPlainText() = runBlocking {
        val controller = unconfinedController()
        // 非 https 端点选直连：口径收口回落代理档（会话层拒绝的组合不落档）
        controller.setEndpointSessionMode(deviceId, ep1, com.dshbox.remote.model.SessionMode.DIRECT)
        val endpoint = controller.devices.value.first { it.id == deviceId }.endpoints.single()
        assertEquals(
            com.dshbox.remote.model.SessionMode.PROXIED.name,
            endpoint.sessionMode ?: com.dshbox.remote.model.SessionMode.PROXIED.name,
        )
    }

    @Test fun ungroupedDeviceStaysLastWhenGroupAddedLater() = runBlocking {
        val controller = unconfinedController()
        val loose = controller.addFromPayload(
            "http://192.168.1.8:3081",
            target = com.dshbox.remote.model.PairingTarget.Ungrouped,
        ).getOrThrow()
        // 之后新建的分组排在无分组之前（无分组恒垫底）
        controller.addFromPayload(
            "http://192.168.1.12:3081",
            target = com.dshbox.remote.model.PairingTarget.NewGroup("后建分组"),
        ).getOrThrow()
        assertEquals(
            listOf("工作电脑", "后建分组", loose.id.value),
            controller.devices.value.map { if (it.name.isEmpty()) it.id.value else it.name },
        )
    }

    @Test fun markSessionCredentialConsumedRemovesPending() = runBlocking {
        val controller = unconfinedController()
        val raw = "http://192.168.1.9:8899/pair?ticket=one-time-secret"
        val device = controller.addFromPayload(raw).getOrThrow()
        val endpointId = device.endpoints.single().id
        assertEquals(setOf(endpointId), controller.pendingCredential.value)

        // 渲染器宿主干净首帧触发的消费提交：存档置位 + 未消费集合摘除
        controller.markSessionCredentialConsumed(endpointId)
        assertEquals(emptySet<EndpointId>(), controller.pendingCredential.value)
        assertTrue(factory.consumed.contains("consumed." + endpointId.value))
    }

    @Test fun staleGenerationConsumptionIsSilentlyIgnored() = runBlocking {
        val controller = unconfinedController()
        val raw = "http://192.168.1.9:8899/pair?ticket=one-time-secret"
        val device = controller.addFromPayload(raw).getOrThrow()
        val endpointId = device.endpoints.single().id
        val staleGeneration = device.endpoints.single().generation - 1

        // 迟到提交（代次已漂移）：静默忽略——未消费集合保留，存档不置位
        controller.markSessionCredentialConsumedWithGenerationForTest(endpointId, staleGeneration)
        assertEquals(setOf(endpointId), controller.pendingCredential.value)
        assertFalse(factory.consumed.contains("consumed." + endpointId.value))
    }

    @Test fun pairingRejectedFlagFlowsThroughFailureAndReady() {
        val controller = unconfinedController()
        controller.selectEndpoint(deviceId, ep1)
        awaitState(controller, ep1, SessionState.Ready)

        controller.notifyRendererFailed(ep1, "HTTP 401", pairingRejected = true)
        assertEquals(SessionState.Failed, controller.sessionStates.value[ep1])
        assertEquals(setOf(ep1), controller.pairingRejected.value)

        // 重试落账（Ready/Loading 收敛）即清除拒绝标记
        controller.retryEndpoint(ep1)
        awaitState(controller, ep1, SessionState.Ready)
        assertEquals(emptySet<EndpointId>(), controller.pairingRejected.value)
    }

    private fun Device.parserIdOfSingleEndpoint(): String = endpoints.single().parserId
}
