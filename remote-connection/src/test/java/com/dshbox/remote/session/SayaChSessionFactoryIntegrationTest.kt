package com.dshbox.remote.session

import com.dshbox.remote.model.Device
import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.Endpoint
import com.dshbox.remote.model.EndpointId
import com.dshbox.remote.parse.ParserIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 真实生产工厂（[DefaultSessionFactory]）的 saya-ch 会话建立集成测试：
 * fake 传输覆盖 CA 引导/兑换 HTTP（不联网），逐条验证——
 * 公网无 CA 回落系统信任、LAN 首次引导 CA 落档、写前代次漂移丢弃产物、
 * consumed=true 注入器仍带 CSRF、罐已有会话 cookie 不重复兑换/不重写；
 * 恢复位（兑换成功但写档失败暂存的孤产物）路径——同代次采纳不耗票、
 * 代次不匹配清除后回落正常兑换、LAN 无 CA 孤产物明确失败。
 */
class SayaChSessionFactoryIntegrationTest {

    private class MemoryStore : VaultStore {
        val blobs = LinkedHashMap<String, ByteArray>()
        override fun read(name: String): ByteArray? = blobs[name]
        override fun write(name: String, data: ByteArray) { blobs[name] = data }
        override fun remove(name: String) { blobs.remove(name) }
    }

    private class FlippingCipher : VaultCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()
        override fun decrypt(cipher: ByteArray): ByteArray = cipher.map { it.toInt().inv().toByte() }.toByteArray()
    }

    private class NoopCleaner : SessionDataCleaner {
        override fun clearOrigins(origins: List<String>, cookieUrls: List<String>) = Unit
    }

    /** fake 传输：CA 引导与兑换行为可编排，记录调用与凭证。 */
    private open class FakeTransport(
        private val caResult: String?,
        private val exchangeResult: SayaChPairResult?,
    ) : SayaChHttpTransport() {
        var fetchCaCalls = 0
        var exchangeCalls = 0
        var lastToken: String? = null
        var lastCaBase64: String? = null

        override fun fetchCa(origin: String): String? {
            fetchCaCalls += 1
            return caResult
        }

        override fun postNativePair(origin: String, token: String, caBase64: String?): SayaChPairResult {
            exchangeCalls += 1
            lastToken = token
            lastCaBase64 = caBase64
            return exchangeResult ?: throw IllegalStateException("no exchange result")
        }
    }

    private val store = InMemoryKeyValueStore()
    private val vaultDir: File = Files.createTempDirectory("saya-it").toFile()
    private val device = Device(
        id = DeviceId("dev-saya"),
        name = "saya-dev",
        endpoints = listOf(
            Endpoint(
                id = EndpointId("ep-lan"),
                channelLabel = "LAN",
                targetUrl = "https://192.168.1.20:3443",
                parserId = ParserIds.SAYA_CH,
            ),
            Endpoint(
                id = EndpointId("ep-public"),
                channelLabel = "PUBLIC",
                targetUrl = "https://dsh.example.com:443",
                parserId = ParserIds.SAYA_CH,
            ),
        ),
        addedAtMillis = 1_700_000_000_000,
    )

    /** 与生产工厂同一 vaultDir/加密口径的 vault（观察工厂落档结果）。 */
    private fun vault() = CredentialVault(
        FileVaultStore.of(vaultDir),
        FlippingCipher(),
    )

    private fun newFactory(transport: SayaChHttpTransport) = DefaultSessionFactory(
        StubContext(),
        storeOverride = { store },
        vaultDirOverride = vaultDir,
        cipherOverride = FlippingCipher(),
        cleanerOverride = NoopCleaner(),
        sayaTransportFactory = { transport },
    )

    private fun seedCredentials(endpoint: Endpoint, token: String, instance: String?) {
        val vault = vault()
        val credentials = buildMap {
            put("endpoint." + endpoint.id.value, token)
            instance?.let { put("saya.instance." + endpoint.id.value, it) }
        }
        vault.saveCredentials(device.id, credentials)
    }

    /**
     * 预置设备门禁令牌：会话建立在 saya 兑换前先经 GateTokenStore 取令牌，
     * 存档缺令牌时它会先写档——写档故障加密器会把失败错位到兑换流程之前，
     * 置位故障前须确保令牌在档。
     */
    private fun seedGateToken() {
        val vault = vault()
        val credentials = vault.loadCredentials(device.id)
        if (credentials.none { it.key.startsWith("gateToken") }) {
            vault.saveCredentials(device.id, credentials + ("gateToken" to "gate-token-seed"))
        }
    }

    @Test
    fun publicOriginWithoutCaFallsBackToSystemTrustAndExchanges() {
        com.dshbox.remote.session.DeviceStore(store).save(device)
        val endpoint = device.endpoints.first { it.id.value == "ep-public" }
        seedCredentials(endpoint, token = "pairing-token", instance = null)
        val transport = FakeTransport(
            caResult = null, // ca.cer 404 → 公网系统信任
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-v", "csrf-v"),
        )
        val session = newFactory(transport).createSession(device.id, endpoint)
        assertEquals(1, transport.fetchCaCalls)
        assertEquals(1, transport.exchangeCalls)
        // 公网无 instance + 无 CA：指纹核对跳过（系统信任），兑换成功
        assertNull(transport.lastCaBase64)
        // 会话建立：兑换产物已落档
        assertTrue(newFactory(FakeTransport(null, null)).sayaSessionExchanged(endpoint.id))
        session.stop()
    }

    @Test
    fun lanFirstSessionBootstrapsCaPersistsAndExchanges() {
        com.dshbox.remote.session.DeviceStore(store).save(device)
        val endpoint = device.endpoints.first { it.id.value == "ep-lan" }
        seedCredentials(endpoint, token = "pairing-token", instance = SayaChHttpTransportTest.TEST_CA_FINGERPRINT)
        val transport = FakeTransport(
            caResult = SayaChHttpTransportTest.TEST_CA_BASE64,
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-lan", "csrf-lan"),
        )
        val factory = newFactory(transport)
        val session = factory.createSession(device.id, endpoint)
        assertEquals(1, transport.fetchCaCalls)
        // 引导 CA 传给兑换（TLS 固定）
        assertEquals(SayaChHttpTransportTest.TEST_CA_BASE64, transport.lastCaBase64)
        // 引导 CA 已落档（cacert.<ep>），消费标记置位，会话三元组在档
        val vault = vault()
        assertEquals(SayaChHttpTransportTest.TEST_CA_BASE64, vault.loadCaCertificate(device.id, endpoint.id))
        assertTrue(vault.isCredentialConsumed(device.id, endpoint.id))
        val sessionCookie = vault.loadSayaSession(device.id, endpoint.id)!!
        assertEquals("__Host-dsh_ma_session", sessionCookie.cookieName)
        assertEquals("sess-lan", sessionCookie.cookieValue)
        assertEquals("csrf-lan", sessionCookie.csrfToken)
        session.stop()
    }

    @Test
    fun generationDriftBeforeExchangeFailsWithoutConsumingTicket() {
        com.dshbox.remote.session.DeviceStore(store).save(device)
        val endpoint = device.endpoints.first { it.id.value == "ep-lan" }
        seedCredentials(endpoint, token = "pairing-token", instance = SayaChHttpTransportTest.TEST_CA_FINGERPRINT)
        val transport = FakeTransport(
            caResult = SayaChHttpTransportTest.TEST_CA_BASE64,
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-v", "csrf-v"),
        )
        val factory = newFactory(transport)
        // 漂移的端点副本（代次 +1）：兑换前复查即失败，票不被消耗
        val drifted = endpoint.copy(generation = endpoint.generation + 1)
        val exception = assertThrows(SessionStartException::class.java) {
            factory.createSession(device.id, drifted)
        }
        assertTrue(exception.message!!.contains("discarded"))
        assertEquals(0, transport.exchangeCalls)
        val vault = vault()
        assertFalse(vault.isCredentialConsumed(device.id, endpoint.id))
        assertNull(vault.loadSayaSession(device.id, endpoint.id))
    }

    @Test
    fun consumedCredentialStillInjectsCsrfOnRebuiltSession() {
        com.dshbox.remote.session.DeviceStore(store).save(device)
        val endpoint = device.endpoints.first { it.id.value == "ep-lan" }
        seedCredentials(endpoint, token = "consumed-ticket", instance = SayaChHttpTransportTest.TEST_CA_FINGERPRINT)
        val transport = FakeTransport(
            caResult = SayaChHttpTransportTest.TEST_CA_BASE64,
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-v", "csrf-from-vault"),
        )
        val factory = newFactory(transport)
        val firstSession = factory.createSession(device.id, endpoint)
        firstSession.stop()
        // 重建会话（票已消费）：注入器仍带 CSRF（消费标记不禁用 CSRF）
        val injector = com.dshbox.remote.proxy.AuthInjectors.forParser(
            ParserIds.SAYA_CH,
            credential = "consumed-ticket",
            credentialConsumed = true,
            sessionAlreadyAuthenticated = true,
            sayaCsrf = "csrf-from-vault",
        )
        assertTrue(injector is com.dshbox.remote.proxy.SayaChSessionInjector)
        val request = com.dshbox.remote.proxy.ProxyRequest("POST", "/api/state", mutableListOf())
        injector.transformRequest(request, com.dshbox.remote.proxy.InjectorState())
        assertEquals("csrf-from-vault", request.firstHeader("x-dsh-mobile-csrf"))
        // 第二次建立不再兑换（罐已有会话 cookie）
        val secondTransport = FakeTransport(null, null)
        val second = newFactory(secondTransport).createSession(device.id, endpoint)
        assertEquals(0, secondTransport.fetchCaCalls)
        assertEquals(0, secondTransport.exchangeCalls)
        second.stop()
    }

    /** 可编排故障的加密器：failWrites 置位时写档抛异常（读路径不受影响）。 */
    private class FaultyCipher : VaultCipher {
        var failWrites = false
        override fun encrypt(plain: ByteArray): ByteArray {
            if (failWrites) throw java.io.IOException("simulated vault write failure")
            return plain.map { it.toInt().inv().toByte() }.toByteArray()
        }

        override fun decrypt(cipher: ByteArray): ByteArray = cipher.map { it.toInt().inv().toByte() }.toByteArray()
    }

    private fun newFactoryWithCipher(
        transportSupplier: () -> SayaChHttpTransport,
        cipher: VaultCipher,
    ) = DefaultSessionFactory(
        StubContext(),
        storeOverride = { store },
        vaultDirOverride = vaultDir,
        cipherOverride = cipher,
        cleanerOverride = NoopCleaner(),
        sayaTransportFactory = transportSupplier,
    )

    @Test
    fun orphanAdoptionReusesExchangedSessionWithoutConsumingAnotherTicket() {
        com.dshbox.remote.session.DeviceStore(store).save(device)
        val endpoint = device.endpoints.first { it.id.value == "ep-lan" }
        seedCredentials(endpoint, token = "pairing-token", instance = SayaChHttpTransportTest.TEST_CA_FINGERPRINT)
        val cipher = FaultyCipher()
        seedGateToken()
        cipher.failWrites = true
        val firstTransport = FakeTransport(
            caResult = SayaChHttpTransportTest.TEST_CA_BASE64,
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-orphan", "csrf-orphan"),
        )
        val transportRef = java.util.concurrent.atomic.AtomicReference<SayaChHttpTransport>(firstTransport)
        // 恢复位是工厂实例的内存态：采纳路径必须复用同一工厂（生产口径——
        // 工厂随门面存活），传输按次经引用切换
        val factory = newFactoryWithCipher({ transportRef.get() }, cipher)
        // 首次建立：兑换成功但写档失败 → 产物留在恢复位（票已在网关侧消耗）
        val failure = assertThrows(SessionStartException::class.java) {
            factory.createSession(device.id, endpoint)
        }
        assertTrue(failure.message!!.contains("persistence failed"))
        assertEquals(1, firstTransport.exchangeCalls)
        // 写档失败后 vault 不得已有兑换产物（恢复位不落档）
        val vault = vault()
        assertNull(vault.loadSayaSession(device.id, endpoint.id))
        // 修复写档后重建会话：孤产物同代次被采纳，不再引导/兑换（零网络调用）
        cipher.failWrites = false
        val secondTransport = FakeTransport(null, null)
        transportRef.set(secondTransport)
        val session = factory.createSession(device.id, endpoint)
        assertEquals(0, secondTransport.fetchCaCalls)
        assertEquals(0, secondTransport.exchangeCalls)
        // 采纳即落档：会话三元组与 CA 来自孤产物
        assertEquals("sess-orphan", vault.loadSayaSession(device.id, endpoint.id)!!.cookieValue)
        assertEquals(SayaChHttpTransportTest.TEST_CA_BASE64, vault.loadCaCertificate(device.id, endpoint.id))
        assertTrue(vault.isCredentialConsumed(device.id, endpoint.id))
        session.stop()
    }

    @Test
    fun orphanWithMismatchedGenerationIsClearedAndNormalExchangeProceeds() {
        com.dshbox.remote.session.DeviceStore(store).save(device)
        val endpoint = device.endpoints.first { it.id.value == "ep-lan" }
        seedCredentials(endpoint, token = "pairing-token", instance = SayaChHttpTransportTest.TEST_CA_FINGERPRINT)
        val cipher = FaultyCipher()
        seedGateToken()
        cipher.failWrites = true
        val firstTransport = FakeTransport(
            caResult = SayaChHttpTransportTest.TEST_CA_BASE64,
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-stale", "csrf-stale"),
        )
        val transportRef = java.util.concurrent.atomic.AtomicReference<SayaChHttpTransport>(firstTransport)
        val factory = newFactoryWithCipher({ transportRef.get() }, cipher)
        val firstFailure = assertThrows(SessionStartException::class.java) {
            factory.createSession(device.id, endpoint)
        }
        assertTrue(firstFailure.message!!.contains("persistence failed"))
        // 端点重配对（代次 +1，不经 rePairEndpoint——直接漂移档案，验证
        // ensureSayaSession 自身的恢复位清理）
        val drifted = endpoint.copy(generation = endpoint.generation + 1)
        com.dshbox.remote.session.DeviceStore(store).save(
            device.copy(
                endpoints = listOf(
                    drifted,
                    device.endpoints.first { it.id.value == "ep-public" },
                ),
            ),
        )
        // 重建会话（新代次档案）：旧孤产物被清掉，正常兑换流程跑通，
        // 旧 cookie 值不得落档
        cipher.failWrites = false
        val secondTransport = FakeTransport(
            caResult = SayaChHttpTransportTest.TEST_CA_BASE64,
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-fresh", "csrf-fresh"),
        )
        transportRef.set(secondTransport)
        val session = factory.createSession(device.id, drifted)
        assertEquals(1, secondTransport.exchangeCalls)
        val vault = vault()
        assertEquals("sess-fresh", vault.loadSayaSession(device.id, endpoint.id)!!.cookieValue)
        assertTrue(vault.isCredentialConsumed(device.id, endpoint.id))
        session.stop()
    }

    @Test
    fun lanOrphanWithoutCaFailsExplicitly() {
        com.dshbox.remote.session.DeviceStore(store).save(device)
        val endpoint = device.endpoints.first { it.id.value == "ep-lan" }
        seedCredentials(endpoint, token = "pairing-token", instance = SayaChHttpTransportTest.TEST_CA_FINGERPRINT)
        val cipher = FaultyCipher()
        seedGateToken()
        cipher.failWrites = true
        // 兑换产物不带 CA（公网语义的孤产物），但端点是 LAN 自签：sessionTlsFor
        // 无法信任上游，采纳必然失败
        val firstTransport = FakeTransport(
            caResult = null,
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-no-ca", "csrf-no-ca"),
        )
        val transportRef = java.util.concurrent.atomic.AtomicReference<SayaChHttpTransport>(firstTransport)
        val factory = newFactoryWithCipher({ transportRef.get() }, cipher)
        val firstFailure = assertThrows(SessionStartException::class.java) {
            factory.createSession(device.id, endpoint)
        }
        assertTrue(firstFailure.message!!.contains("persistence failed"))
        // 同代次 LAN 孤产物缺 CA：明确失败要求重新配对，不再兑换、不再落档
        val secondTransport = FakeTransport(
            caResult = SayaChHttpTransportTest.TEST_CA_BASE64,
            exchangeResult = SayaChPairResult("__Host-dsh_ma_session", "sess-should-not-happen", "csrf-x"),
        )
        transportRef.set(secondTransport)
        val secondFailure = assertThrows(SessionStartException::class.java) {
            factory.createSession(device.id, endpoint)
        }
        assertTrue(secondFailure.message!!.contains("CA is missing"))
        assertEquals(0, secondTransport.exchangeCalls)
        assertNull(vault().loadSayaSession(device.id, endpoint.id))
    }
}
