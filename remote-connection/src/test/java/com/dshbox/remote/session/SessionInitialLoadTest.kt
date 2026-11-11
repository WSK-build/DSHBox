package com.dshbox.remote.session

import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.model.EndpointId

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话首次加载地址解析与一次性凭据消费标记测试：未消费的票按插件形态重建
 * 首载地址；消费后的回落按解析器区分——网关与 anonRT 配对页回环根，
 * 通用通道保留应用入口路径。
 */
class SessionInitialLoadTest {

    private val origin = "http://127.0.0.5:51000"

    @Test fun gatewayCredentialBuildsPairingUrlWithFragment() {
        val url = SessionInitialLoad.resolve(
            "dsh-remote-web-gateway", origin, "https://gw.example.com/pair#s3cret", consumed = false,
        )
        assertEquals("$origin/pair#s3cret", url)
    }

    @Test fun gatewayCredentialWithoutFragmentFallsBackToNull() {
        assertNull(SessionInitialLoad.resolve("dsh-remote-web-gateway", origin, "https://gw.example.com/pair", false))
    }

    @Test fun genericTicketRebuildsPathAndQueryOnLoopbackOrigin() {
        val url = SessionInitialLoad.resolve(
            "generic-url", origin, "http://192.168.1.9:8899/pair?ticket=one-time", consumed = false,
        )
        // 渲染器只加载回环 origin：凭据 URL 的路径与 query 重建到回环侧
        assertEquals("$origin/pair?ticket=one-time", url)
    }

    @Test fun anonRtTicketRidesFirstLoadAndFallsBackToRootAfterConsumption() {
        val credential = "http://192.168.1.9:8899/pair?ticket=one-time"
        // 首载：票据经首载地址携带（路径 /pair + ticket query 重建到回环 origin）
        val first = SessionInitialLoad.resolve("anonrt-remote-mode", origin, credential, consumed = false)
        assertEquals("$origin/pair?ticket=one-time", first)
        // 消费后：回落回环根（与 gateway 同口径；已兑换会话 cookie 由代理罐注入）
        val second = SessionInitialLoad.resolve("anonrt-remote-mode", origin, credential, consumed = true)
        assertNull(second)
    }

    @Test fun consumedCredentialFallsBackByParserKind() {
        // 网关票已消费：回环根（已兑换 cookie 由代理罐注入）
        assertNull(SessionInitialLoad.resolve("dsh-remote-web-gateway", origin, "https://gw.example.com/pair#s3cret", true))
        // anonRT 配对页已消费：回环根（与网关同口径）
        assertNull(SessionInitialLoad.resolve("anonrt-remote-mode", origin, "http://192.168.1.9:8899/pair?ticket=x", true))
        // 通用 ticket 已消费：保留应用入口路径，剥离票据 query
        assertEquals("$origin/pair", SessionInitialLoad.resolve("generic-url", origin, "http://192.168.1.9:8899/pair?ticket=x", true))
        assertNull(SessionInitialLoad.resolve("dsh-full-remote", origin, "https://gw.example.com/login?invite=x", false))
        assertNull(SessionInitialLoad.resolve("dsh-bridge", origin, "token", false))
        assertNull(SessionInitialLoad.resolve("dsh-pocket", origin, null, false))
    }

    private class InMemoryVaultStore : VaultStore {
        private val blobs = mutableMapOf<String, ByteArray>()
        override fun read(name: String): ByteArray? = blobs[name]
        override fun write(name: String, data: ByteArray) {
            blobs[name] = data
        }

        override fun remove(name: String) {
            blobs.remove(name)
        }
    }

    /** 可逆异或「加密」：只验证存档往返，不依赖系统密钥库。 */
    private class ReversibleTestCipher : VaultCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { (it + 1).toByte() }.toByteArray()
        override fun decrypt(cipher: ByteArray): ByteArray = cipher.map { (it - 1).toByte() }.toByteArray()
    }

    @Test fun genericTicketEndpointDowngradesAfterFirstSession() {
        val vault = CredentialVault(InMemoryVaultStore(), ReversibleTestCipher())
        val device = DeviceId("dev-1")
        val endpoint = EndpointId("ep-1")
        val credential = "http://192.168.1.9:8899/pair?ticket=one-time-secret"
        // 首会话：凭据未消费 → 首载走回环重建的带票地址
        val first = SessionInitialLoad.resolve("generic-url", origin, credential, vault.isCredentialConsumed(device, endpoint))
        assertEquals("$origin/pair?ticket=one-time-secret", first)
        // 会话代理观测到兑换完成（HttpOnly 会话 cookie）→ 消费置位
        vault.markCredentialConsumed(device, endpoint)
        // 第二会话：保留应用入口路径，剥离票据 query（靠已兑换 cookie，票据不重放）
        val second = SessionInitialLoad.resolve("generic-url", origin, credential, vault.isCredentialConsumed(device, endpoint))
        assertEquals("$origin/pair", second)
    }

    @Test fun genericTicketEndToEndArchiveConsumeThenSecondSessionKeepsEntryPath() {
        // 端到端口径：/connect?ticket= 建档（通用通道，清单存剥票地址、完整
        // URL 入加密存档）→ 消费置位 → 第二会话保应用入口路径
        val pairing = com.dshbox.remote.parse.GenericUrlParser()
            .parse("http://192.168.1.9:8899/connect?ticket=one-time-secret")
        assertEquals(com.dshbox.remote.parse.ParserIds.GENERIC, pairing.parserId)
        val endpoint = pairing.endpoints.single()
        assertEquals("http://192.168.1.9:8899/connect", endpoint.targetUrl)

        val vault = CredentialVault(InMemoryVaultStore(), ReversibleTestCipher())
        val device = DeviceId("dev-1")
        val endpointId = EndpointId("ep-1")
        vault.saveCredentials(device, mapOf("endpoint.${endpointId.value}" to endpoint.credential!!))

        // 第一会话：凭据未消费 → 票据经首载地址携带
        val first = SessionInitialLoad.resolve(
            com.dshbox.remote.parse.ParserIds.GENERIC,
            origin,
            vault.loadCredentials(device)["endpoint.${endpointId.value}"],
            vault.isCredentialConsumed(device, endpointId),
        )
        assertEquals("$origin/connect?ticket=one-time-secret", first)
        // 兑换完成（消费置位）→ 第二会话：保 /connect 应用入口路径，票不重放
        vault.markCredentialConsumed(device, endpointId)
        val second = SessionInitialLoad.resolve(
            com.dshbox.remote.parse.ParserIds.GENERIC,
            origin,
            vault.loadCredentials(device)["endpoint.${endpointId.value}"],
            vault.isCredentialConsumed(device, endpointId),
        )
        assertEquals("$origin/connect", second)
    }

    @Test fun credentialConsumedFlagRoundtrip() {
        val vault = CredentialVault(InMemoryVaultStore(), ReversibleTestCipher())
        val device = DeviceId("dev-1")
        val endpoint = EndpointId("ep-1")
        assertFalse(vault.isCredentialConsumed(device, endpoint))
        vault.markCredentialConsumed(device, endpoint)
        assertTrue(vault.isCredentialConsumed(device, endpoint))
        // 凭据更新（重新配对）即清除消费标记
        vault.clearCredentialConsumed(device, endpoint)
        assertFalse(vault.isCredentialConsumed(device, endpoint))
        // 与凭据键值互不干扰
        vault.saveCredentials(device, mapOf("endpoint.ep-1" to "url"))
        assertTrue(vault.loadCredentials(device).containsKey("endpoint.ep-1"))
    }
}
