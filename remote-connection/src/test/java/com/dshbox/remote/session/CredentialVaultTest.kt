package com.dshbox.remote.session

import com.dshbox.remote.model.DeviceId
import com.dshbox.remote.proxy.CookieJar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 凭据存档测试：以内存存储 + 可逆测试加密实现覆盖存档往返与销毁语义。 */
class CredentialVaultTest {

    private class InMemoryVaultStore : VaultStore {
        val blobs = LinkedHashMap<String, ByteArray>()
        override fun read(name: String): ByteArray? = blobs[name]
        override fun write(name: String, data: ByteArray) {
            blobs[name] = data
        }
        override fun remove(name: String) {
            blobs.remove(name)
        }
    }

    /** 可逆测试加密：内容翻转，验证加解密边界被真实调用。 */
    private class FlippingCipher : VaultCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.map { it.toInt().inv().toByte() }.toByteArray()
        override fun decrypt(cipher: ByteArray): ByteArray = cipher.map { it.toInt().inv().toByte() }.toByteArray()
    }

    private val vault = CredentialVault(InMemoryVaultStore(), FlippingCipher())
    private val deviceId = DeviceId("dev-1")

    @Test fun credentialsRoundTrip() {
        vault.saveCredentials(deviceId, mapOf("authToken" to "s3cret", "gateToken" to "abc123"))
        assertEquals(mapOf("authToken" to "s3cret", "gateToken" to "abc123"), vault.loadCredentials(deviceId))
    }

    @Test fun credentialsAreEncryptedAtRest() {
        val store = InMemoryVaultStore()
        val vault = CredentialVault(store, FlippingCipher())
        vault.saveCredentials(deviceId, mapOf("authToken" to "s3cret-value"))
        val raw = String(store.blobs.getValue("cred.dev-1"), Charsets.UTF_8)
        // 密文落盘：明文凭据不出现在存储层
        assertFalse(raw.contains("s3cret-value"))
    }

    @Test fun missingCredentialsReturnEmptyMap() {
        assertTrue(vault.loadCredentials(DeviceId("ghost")).isEmpty())
    }

    @Test fun cookieJarPersistenceRoundTrip() {
        val jar = CookieJar()
        jar.storeAll("http://192.168.1.5:3081", listOf("sid=abc; Path=/; HttpOnly", "pref=dark; Path=/"))
        vault.saveCookieJar(deviceId, jar)
        val restored = vault.loadCookieJar(deviceId)!!
        val cookies = restored.cookiesFor("http://192.168.1.5:3081")
        assertEquals(setOf("sid", "pref"), cookies.map { it.name }.toSet())
        assertEquals("abc", cookies.first { it.name == "sid" }.value)
        assertTrue(cookies.first { it.name == "sid" }.httpOnly)
    }

    @Test fun missingJarReturnsNull() {
        assertNull(vault.loadCookieJar(DeviceId("ghost")))
    }

    @Test fun destroyDeviceRemovesAllEntries() {
        vault.saveCredentials(deviceId, mapOf("gateToken" to "t"))
        val jar = CookieJar()
        jar.storeAll("http://192.168.1.5:3081", listOf("sid=abc; Path=/"))
        vault.saveCookieJar(deviceId, jar)

        vault.destroyDevice(deviceId)
        assertTrue(vault.loadCredentials(deviceId).isEmpty())
        assertNull(vault.loadCookieJar(deviceId))
    }

    @Test fun devicesAreIsolatedInVault() {
        vault.saveCredentials(DeviceId("dev-1"), mapOf("gateToken" to "one"))
        vault.saveCredentials(DeviceId("dev-2"), mapOf("gateToken" to "two"))
        assertEquals("one", vault.loadCredentials(DeviceId("dev-1"))["gateToken"])
        assertEquals("two", vault.loadCredentials(DeviceId("dev-2"))["gateToken"])
        vault.destroyDevice(DeviceId("dev-1"))
        assertEquals("two", vault.loadCredentials(DeviceId("dev-2"))["gateToken"])
    }

    @Test fun caCertificateRoundTripAndDestroy() {
        val endpoint = com.dshbox.remote.model.EndpointId("ep-1")
        assertNull(vault.loadCaCertificate(deviceId, endpoint))
        // CA 证书随端点入加密存档（与端点凭据同一份键值对）
        vault.saveCredentials(deviceId, mapOf("endpoint.ep-1" to "gateway-token"))
        vault.saveCaCertificate(deviceId, endpoint, "MIIBTjCB9aADAgEC")
        assertEquals("MIIBTjCB9aADAgEC", vault.loadCaCertificate(deviceId, endpoint))
        assertEquals("gateway-token", vault.loadCredentials(deviceId)["endpoint.ep-1"])
        // CA 证书更新覆盖旧值
        vault.saveCaCertificate(deviceId, endpoint, " renewed ")
        assertEquals(" renewed ", vault.loadCaCertificate(deviceId, endpoint))
        // 删除设备 = 存档整体销毁：CA 证书一并清除
        vault.destroyDevice(deviceId)
        assertNull(vault.loadCaCertificate(deviceId, endpoint))
    }

    /** 只认自有格式密文的加密实现：解到无魔数前缀的输入即抛错（模拟 AEADBadTagException）。 */
    private class MagicPrefixedCipher : VaultCipher {
        override fun encrypt(plain: ByteArray): ByteArray = "OK:".toByteArray() + plain
        override fun decrypt(cipher: ByteArray): ByteArray {
            val prefix = "OK:".toByteArray()
            if (cipher.size < prefix.size || !cipher.copyOf(prefix.size).contentEquals(prefix)) {
                throw java.security.GeneralSecurityException("bad tag")
            }
            return cipher.copyOfRange(prefix.size, cipher.size)
        }
    }

    @Test fun corruptedCipherTextDegradesToEmptyArchiveAndAllowsRewrite() {
        val store = InMemoryVaultStore()
        val vault = CredentialVault(store, MagicPrefixedCipher())
        // 绕过 vault 直接落一条损坏密文（模拟密钥轮换/文件破坏）
        store.blobs["cred.dev-bad"] = "definitely-not-cipher-text".toByteArray()

        // 读取降级为空存档，不抛异常
        assertTrue(vault.loadCredentials(DeviceId("dev-bad")).isEmpty())
        // 损坏条目被清除，不残留
        assertNull(store.blobs["cred.dev-bad"])
        // 降级后可正常重新写入并读回
        vault.saveCredentials(DeviceId("dev-bad"), mapOf("gateToken" to "fresh"))
        assertEquals("fresh", vault.loadCredentials(DeviceId("dev-bad"))["gateToken"])
    }

    @Test fun corruptedJarBlobDegradesToNull() {
        val store = InMemoryVaultStore()
        val vault = CredentialVault(store, MagicPrefixedCipher())
        store.blobs["jar.dev-bad"] = "garbage".toByteArray()
        assertNull(vault.loadCookieJar(DeviceId("dev-bad")))
        assertNull(store.blobs["jar.dev-bad"])
    }

    @Test fun concurrentOnChangePersistsIntactJarSnapshot() {
        // 并发 Set-Cookie 触发的 onChange 回写（经真实文件存储）：写串行化
        // 保证任一时刻落盘的都是完整快照。各线程写同一批 cookie——最后完成
        // 的回写无论来自哪个线程，其快照都含全部 cookie，读回断言因此确定；
        // 不做任何补写，损坏（交错截断）会在读回时直接暴露。工作线程异常
        // 逐线程捕获并计入断言（JUnit4 不捕工作线程异常）。
        val vault = CredentialVault(
            FileVaultStore.of(java.nio.file.Files.createTempDirectory("vault-test").toFile()),
            MagicPrefixedCipher(),
        )
        val jar = CookieJar()
        jar.onChangeListener = { vault.saveCookieJar(deviceId, jar) }
        val batch = (0 until 5).map { "shared-cookie-$it=value-$it; Path=/" }
        val threadCount = 8
        val errors = arrayOfNulls<Throwable>(threadCount)
        val threads = (0 until threadCount).map { index ->
            Thread {
                try {
                    repeat(25) {
                        jar.storeAll("http://192.168.1.5:3081", batch)
                    }
                } catch (t: Throwable) {
                    errors[index] = t
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(errors.all { it == null })
        val restored = vault.loadCookieJar(deviceId)!!
        val cookies = restored.cookiesFor("http://192.168.1.5:3081")
        assertEquals((0 until 5).map { "shared-cookie-$it" }, cookies.map { it.name }.sorted())
        assertEquals((0 until 5).map { "value-$it" }, cookies.sortedBy { it.name }.map { it.value })
    }

    // ---- 端点敏感条目随设备迁移/删除（endpoint./cacert./consumed. 三类键） ----

    private val endpointId = com.dshbox.remote.model.EndpointId("ep-9")

    private fun seedEndpointEntries(vault: CredentialVault, device: DeviceId) {
        // 与生产工厂同一口径写入三类键：endpointCredentialKey 是唯一构造点
        val credentialKey = vault.endpointCredentialKey(endpointId)
        vault.saveCredentials(device, mapOf(credentialKey to "s3cret-ticket"))
        vault.saveCaCertificate(device, endpointId, "base64-ca")
        vault.markCredentialConsumed(device, endpointId)
    }

    @Test fun endpointCredentialKeyMatchesProductionPrefix() {
        // 生产端点凭据键前缀是 endpoint.（会话工厂按此读取），不与设备 blob
        // 前缀 cred. 混用
        assertEquals("endpoint.ep-9", vault.endpointCredentialKey(endpointId))
    }

    @Test fun moveEndpointEntriesRelocatesAllThreeKeys() {
        val from = DeviceId("dev-from")
        val to = DeviceId("dev-to")
        seedEndpointEntries(vault, from)

        vault.moveEndpointEntries(from, endpointId, to)

        // 目标设备读得到原凭据/CA；消费标记随迁（目标桶内已消费）
        assertEquals("s3cret-ticket", vault.loadCredentials(to)[vault.endpointCredentialKey(endpointId)])
        assertEquals("base64-ca", vault.loadCaCertificate(to, endpointId))
        assertTrue(vault.isCredentialConsumed(to, endpointId))
        // 源设备三类键清空
        assertTrue(vault.loadCredentials(from).isEmpty())
    }

    @Test fun moveEndpointEntriesWithoutEntriesIsLossless() {
        val from = DeviceId("dev-empty")
        val to = DeviceId("dev-keep")
        vault.saveCredentials(to, mapOf("keep" to "v"))

        // 源设备无该端点条目：无损搬运（目标既有条目不受影响）
        vault.moveEndpointEntries(from, endpointId, to)
        assertEquals(mapOf("keep" to "v"), vault.loadCredentials(to))
        assertTrue(vault.loadCredentials(from).isEmpty())
    }

    @Test fun clearEndpointEntriesRemovesAllThreeKeys() {
        seedEndpointEntries(vault, deviceId)

        vault.clearEndpointEntries(deviceId, endpointId)

        // 凭据/CA/消费标记三类键均为空
        assertTrue(vault.loadCredentials(deviceId).isEmpty())
        assertNull(vault.loadCaCertificate(deviceId, endpointId))
        assertFalse(vault.isCredentialConsumed(deviceId, endpointId))
    }

    // ---- saya-ch 通道键（saya.instance/cookieName/cookie/csrf） ----

    private fun seedSayaEntries(vault: CredentialVault, device: DeviceId) {
        vault.saveSayaPairingInstance(device, endpointId, "c4aa4290…fingerprint")
        vault.saveSayaSession(device, endpointId, "__Host-dsh_ma_session", "session-value", "csrf-value")
    }

    @Test fun sayaSessionRoundTripAndMissingReturnsNull() {
        assertNull(vault.loadSayaSession(deviceId, endpointId))
        seedSayaEntries(vault, deviceId)
        val session = vault.loadSayaSession(deviceId, endpointId)!!
        assertEquals("__Host-dsh_ma_session", session.cookieName)
        assertEquals("session-value", session.cookieValue)
        assertEquals("csrf-value", session.csrfToken)
        assertEquals("c4aa4290…fingerprint", vault.loadSayaPairingInstance(deviceId, endpointId))
        // 缺任一成员键按未兑换处理（三元组原子语义）
        vault.saveCredentials(deviceId, vault.loadCredentials(deviceId) - vault.sayaCsrfKey(endpointId))
        assertNull(vault.loadSayaSession(deviceId, endpointId))
    }

    @Test fun sayaKeysMoveAndClearWithEndpoint() {
        val from = DeviceId("dev-saya-from")
        val to = DeviceId("dev-saya-to")
        seedSayaEntries(vault, from)

        vault.moveEndpointEntries(from, endpointId, to)

        // 四类 saya 键随端点整体搬入目标设备，源设备清空
        assertEquals("__Host-dsh_ma_session", vault.loadSayaSession(to, endpointId)!!.cookieName)
        assertEquals("c4aa4290…fingerprint", vault.loadSayaPairingInstance(to, endpointId))
        assertTrue(vault.loadCredentials(from).isEmpty())

        // 端点删除：saya 键一并清除
        vault.clearEndpointEntries(to, endpointId)
        assertNull(vault.loadSayaSession(to, endpointId))
        assertNull(vault.loadSayaPairingInstance(to, endpointId))
    }

    @Test fun sayaKeysDestroyedWithDevice() {
        seedSayaEntries(vault, deviceId)
        vault.destroyDevice(deviceId)
        assertNull(vault.loadSayaSession(deviceId, endpointId))
        assertNull(vault.loadSayaPairingInstance(deviceId, endpointId))
    }
}
